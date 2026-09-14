/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.server.log;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.LayoutBase;
import ch.qos.logback.core.util.CachingDateFormatter;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.utils.StringUtils;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * Logs events as JSON objects for easier digestion by log aggregators like Loki. We construct the JSON manually
 * so we don't slow down the logging process by using Jackson.
 *
 * **Fields written**, in this order, each omitted when its condition does not hold:
 *
 * - `timestamp` — always, unless the `logTimestamp` property is set to `false` in the logback configuration
 * - `level` — always
 * - `message` — always; a stack trace is appended to it, and the whole thing is escaped
 * - `client_id` — a client identifier is known for the request
 * - `trace_id` — tracing is enabled and a span is active
 * - `duration_ms` — the line belongs to a request whose start instant is known; see
 *   {@link #getRequestDurationMs(ILoggingEvent)} for the three sources that instant can come from and the order they
 *   are consulted in
 *
 * **This is an operator-facing contract.** The layout is named from a logback configuration file, and the same set
 * of fields - plus the MDC keys a layout of the operator's own may read instead - is published in
 * `documentation/user/en/operate/observe.md` ("Tooling for log aggregators"). A field added, renamed or dropped here
 * is a user-visible change, and that document has to move with it.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class AppLogJsonLayout extends LayoutBase<ILoggingEvent> {

	private static final String[] ESCAPED_CHARS = new String[] { "\r\n", "\n", "\r", "\f", "\b", "\\", "\"", "\t" };
	private static final String[] REPLACEMENTS_FOR_ESCAPED_CHARS = new String[] { "\\r\\n", "\\n", "\\r", "\\f", "\\b", "\\\\", "\\\"", "   " };

	private final CachingDateFormatter cachingDateFormatter = new CachingDateFormatter("yyyy-MM-dd'T'HH:mm:ss.SSSZ", null);
	private final MessageConverter messageConverter = new MessageConverter();
	private final ThrowableProxyConverter throwableProxyConverter = new ThrowableProxyConverter();

	@Setter private boolean logTimestamp = true;

	@Override
	public void start() {
		this.messageConverter.start();
		this.throwableProxyConverter.start();
		super.start();
	}

	/**
	 * Renders one logging event as a single-line JSON object, in the field order the class documentation lists.
	 *
	 * @param event the event to render, supplying the level, the message, the MDC and the timestamp every duration is
	 *              measured to
	 * @return the JSON object followed by the platform line separator
	 */
	@Override
	public String doLayout(ILoggingEvent event) {
		final StringBuilder buf = new StringBuilder(512);

		buf.append("{");

		if (this.logTimestamp) {
			buf.append("\"timestamp\":\"");
			buf.append(this.cachingDateFormatter.format(event.getTimeStamp()));
			buf.append("\"");

			buf.append(",");
		}

		buf.append("\"level\":\"");
		buf.append(event.getLevel().toString());
		buf.append("\"");

		buf.append(",");

		String completeMessage = this.messageConverter.convert(event);
		if (event.getThrowableProxy() != null) {
			completeMessage += "\n" + this.throwableProxyConverter.convert(event);
		}
		buf.append("\"message\":\"");
		buf.append(escapeMessage(completeMessage));
		buf.append("\"");

		// Add client_id field only if it is available
		final String clientId = event.getMDCPropertyMap().get(TracingContext.MDC_CLIENT_ID_PROPERTY);
		if (clientId != null) {
			buf.append(",");
			buf.append("\"client_id\":\"");
			buf.append(clientId);
			buf.append("\"");
		}

		// Add trace_id field only if it is available
		final String traceId = event.getMDCPropertyMap().get(TracingContext.MDC_TRACE_ID_PROPERTY);
		if (traceId != null) {
			buf.append(",");
			buf.append("\"trace_id\":\"");
			buf.append(traceId);
			buf.append("\"");
		}

		// Add duration_ms field only if the line belongs to a request whose start instant is known
		final Long durationMs = getRequestDurationMs(event);
		if (durationMs != null) {
			buf.append(",");
			buf.append("\"duration_ms\":");
			buf.append(durationMs);
		}

		buf.append("}");
		buf.append(CoreConstants.LINE_SEPARATOR);

		return buf.toString();
	}

	/**
	 * Escapes special characters in a given message by replacing them with their corresponding escape sequences.
	 * The escape sequences are defined in the {@link AppLogJsonLayout} class.
	 *
	 * @param message the message to escape
	 * @return the escaped message
	 */
	@Nonnull
	private static String escapeMessage(@Nonnull String message) {
		return StringUtils.replaceEach(message, ESCAPED_CHARS, REPLACEMENTS_FOR_ESCAPED_CHARS);
	}

	/**
	 * Resolves how long the request this line belongs to had been running when the line was written.
	 *
	 * Three sources are consulted, in descending order of accuracy:
	 *
	 * 1. A completed Armeria request whose context is current on this thread — Armeria's own
	 *    {@link RequestLog#totalDurationNanos()}, measured monotonically. This is what the access log line gets.
	 * 2. An in-flight Armeria request whose context is current — the difference between the event's timestamp and
	 *    the request start. Covers every line written on the event loop and on a gRPC listener callback.
	 * 3. {@link TracingContext#MDC_REQUEST_START_PROPERTY} carried in the event's MDC — the only source available on
	 *    an evitaDB worker thread, which never has an Armeria context pushed.
	 *
	 * {@link ServiceRequestContext#currentOrNull()} resolves to the **root** service context, so a line written
	 * while this thread makes an outbound client call still reports the duration of the request being served. Only
	 * a client call with no served request behind it yields null and falls through to source 3.
	 *
	 * Sources 2 and 3 subtract two wall-clock readings, so a backward clock adjustment can make them negative; the
	 * result is clamped at zero rather than reported as a nonsensical duration.
	 *
	 * @param event the event being rendered, supplying the timestamp the elapsed time is measured to
	 * @return milliseconds since the request started, or null when this line does not belong to a request
	 */
	@Nullable
	private static Long getRequestDurationMs(@Nonnull ILoggingEvent event) {
		final ServiceRequestContext requestContext = ServiceRequestContext.currentOrNull();
		if (requestContext != null) {
			final RequestLogAccess logAccess = requestContext.log();
			final RequestLog partialLog = logAccess.partial();
			if (logAccess.isAvailable(RequestLogProperty.RESPONSE_END_TIME) && partialLog.isRequestComplete()) {
				return partialLog.totalDurationNanos() / 1_000_000L;
			}
			// the request is still running - REQUEST_START_TIME is set inside the ServiceRequestContext constructor,
			// so it is available to anything that can observe the context at all
			if (logAccess.isAvailable(RequestLogProperty.REQUEST_START_TIME)) {
				return Math.max(0L, event.getTimeStamp() - partialLog.requestStartTimeMillis());
			}
		}
		return getCapturedRequestDurationMs(event);
	}

	/**
	 * Computes how long the request had been running when the line was written from the request start recorded in the
	 * event's MDC, which is how the value reaches worker threads that never see an Armeria context.
	 *
	 * The key is read with a null check rather than a containment check on purpose: the context restored onto a
	 * worker writes every key unconditionally, so a key whose value was never set is *present* with a null value.
	 * A malformed value is ignored rather than propagated - rendering a log line must not throw.
	 *
	 * Unlike the Armeria sources above, this one reads a value anyone with an MDC-populating logging filter can
	 * write, so a value that is not a real instant is dropped rather than clamped: clamping the *difference* would
	 * turn a negative reading into an epoch-sized duration and hand the aggregator a plausible-looking number.
	 *
	 * @param event the event being rendered
	 * @return milliseconds since the request started, or null when no usable request start is recorded
	 */
	@Nullable
	private static Long getCapturedRequestDurationMs(@Nonnull ILoggingEvent event) {
		final String requestStart = event.getMDCPropertyMap().get(TracingContext.MDC_REQUEST_START_PROPERTY);
		if (requestStart == null) {
			return null;
		}
		try {
			final long start = Long.parseLong(requestStart);
			if (start <= 0L) {
				return null;
			}
			return Math.max(0L, event.getTimeStamp() - start);
		} catch (NumberFormatException ex) {
			return null;
		}
	}
}
