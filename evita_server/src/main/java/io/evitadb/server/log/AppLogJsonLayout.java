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
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.utils.StringUtils;
import lombok.Setter;

import javax.annotation.Nonnull;


/**
 * Logs events as JSON objects for easier digestion by log aggregators like Loki. We construct the JSON manually
 * so we don't slow down the logging process by using Jackson.
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

		buf.append(",");

		final String clientId = event.getMDCPropertyMap().get(TracingContext.MDC_CLIENT_ID_PROPERTY);
		buf.append("\"client_id\":");
		if (clientId == null) {
			buf.append("null");
		} else {
			buf.append("\"");
			buf.append(clientId);
			buf.append("\"");
		}

		buf.append(",");

		final String traceId = event.getMDCPropertyMap().get(TracingContext.MDC_TRACE_ID_PROPERTY);
		buf.append("\"trace_id\":");
		if (traceId == null) {
			buf.append("null");
		} else {
			buf.append("\"");
			buf.append(traceId);
			buf.append("\"");
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
	private static String escapeMessage(@Nonnull String message) {
		return StringUtils.replaceEach(message, ESCAPED_CHARS, REPLACEMENTS_FOR_ESCAPED_CHARS);
	}
}
