/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Pattern;

import static io.evitadb.test.TestTags.OBSERVABILITY;
import static io.evitadb.test.TestTags.SERVER;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the JSON that {@link AppLogJsonLayout} renders, with particular attention to `duration_ms` - which of the
 * three possible sources it comes from, and when it must be absent.
 *
 * Nothing else in the codebase asserts the shape of these lines, so several tests deliberately assert the whole
 * rendered string rather than the presence of a single field: this class is what pins the format that log aggregators
 * consume, escaping included - an unescaped quote or newline in a message turns one log line into two unparseable
 * ones.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("AppLogJsonLayout - JSON rendering and request duration")
@Tag(SERVER)
@Tag(OBSERVABILITY)
class AppLogJsonLayoutTest {

	/**
	 * Shape the `timestamp` field is declared to carry - `yyyy-MM-dd'T'HH:mm:ss.SSSZ`.
	 */
	private static final Pattern TIMESTAMP_SHAPE =
		Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{4}");

	/**
	 * Name of the field the duration is rendered under, and the exact text introducing it in a rendered line.
	 *
	 * The name is what log aggregators consume, so it is written once here: the extractor below and every assertion
	 * that checks for the field's presence or absence then cannot end up talking about different fields.
	 */
	private static final String DURATION_FIELD = "duration_ms";
	private static final String DURATION_FIELD_PREFIX = "\"" + DURATION_FIELD + "\":";

	/**
	 * Builds the layout under test with timestamps suppressed, so assertions can compare whole lines without
	 * depending on the wall clock.
	 *
	 * @return a started layout that omits the timestamp field
	 */
	@Nonnull
	private static AppLogJsonLayout timestamplessLayout() {
		final AppLogJsonLayout layout = new AppLogJsonLayout();
		layout.setLogTimestamp(false);
		layout.start();
		return layout;
	}

	/**
	 * Builds the layout under test in its default configuration, which renders the timestamp field.
	 *
	 * @return a started layout that emits the timestamp field
	 */
	@Nonnull
	private static AppLogJsonLayout timestampedLayout() {
		final AppLogJsonLayout layout = new AppLogJsonLayout();
		layout.start();
		return layout;
	}

	/**
	 * Builds a logging event carrying the given MDC contents.
	 *
	 * @param message the formatted message
	 * @param mdc     the MDC property map the event carries; may contain null values, as a restored context produces
	 * @return a mock event suitable for {@link AppLogJsonLayout#doLayout(ILoggingEvent)}
	 */
	@Nonnull
	private static ILoggingEvent event(@Nonnull String message, @Nonnull Map<String, String> mdc) {
		return event(message, mdc, System.currentTimeMillis());
	}

	/**
	 * Builds a logging event carrying the given MDC contents and timestamp.
	 *
	 * @param message   the formatted message
	 * @param mdc       the MDC property map the event carries
	 * @param timestamp the event timestamp in epoch milliseconds
	 * @return a mock event suitable for {@link AppLogJsonLayout#doLayout(ILoggingEvent)}
	 */
	@Nonnull
	private static ILoggingEvent event(@Nonnull String message, @Nonnull Map<String, String> mdc, long timestamp) {
		final ILoggingEvent event = mock(ILoggingEvent.class);
		when(event.getTimeStamp()).thenReturn(timestamp);
		when(event.getLevel()).thenReturn(Level.INFO);
		when(event.getFormattedMessage()).thenReturn(message);
		when(event.getMDCPropertyMap()).thenReturn(mdc);
		when(event.getThrowableProxy()).thenReturn(null);
		return event;
	}

	/**
	 * Builds a logging event that carries a throwable, which the layout appends to the message before escaping it.
	 *
	 * @param message   the formatted message
	 * @param throwable the throwable the event carries
	 * @return a mock event suitable for {@link AppLogJsonLayout#doLayout(ILoggingEvent)}
	 */
	@Nonnull
	private static ILoggingEvent eventWithThrowable(@Nonnull String message, @Nonnull Throwable throwable) {
		final ILoggingEvent event = mock(ILoggingEvent.class);
		when(event.getTimeStamp()).thenReturn(System.currentTimeMillis());
		when(event.getLevel()).thenReturn(Level.ERROR);
		when(event.getFormattedMessage()).thenReturn(message);
		when(event.getMDCPropertyMap()).thenReturn(Map.of());
		when(event.getThrowableProxy()).thenReturn(new ThrowableProxy(throwable));
		return event;
	}

	/**
	 * Builds an MDC map with a single request-start entry, mirroring what a restored captured context looks like on
	 * a worker thread.
	 *
	 * @param requestStart the value to record, null allowed - a restored context writes keys unconditionally
	 * @return a mutable MDC map
	 */
	@Nonnull
	private static Map<String, String> mdcWithRequestStart(@Nullable String requestStart) {
		final Map<String, String> mdc = CollectionUtils.createHashMap(1);
		mdc.put(TracingContext.MDC_REQUEST_START_PROPERTY, requestStart);
		return mdc;
	}

	/**
	 * Extracts the numeric `duration_ms` value from a rendered line.
	 *
	 * The field is asserted to be present first: without that the caller gets a parse failure instead of an
	 * assertion message, and a negative value is accepted so that a clamping defect reports the number it produced.
	 *
	 * @param renderedLine the line produced by the layout
	 * @return the parsed value
	 */
	private static long durationOf(@Nonnull String renderedLine) {
		final int fieldIndex = renderedLine.indexOf(DURATION_FIELD_PREFIX);
		assertTrue(fieldIndex >= 0, "no " + DURATION_FIELD + " field in the rendered line: " + renderedLine);
		final int start = fieldIndex + DURATION_FIELD_PREFIX.length();
		int end = start;
		if (end < renderedLine.length() && renderedLine.charAt(end) == '-') {
			end++;
		}
		while (end < renderedLine.length() && Character.isDigit(renderedLine.charAt(end))) {
			end++;
		}
		return Long.parseLong(renderedLine.substring(start, end));
	}

	@Nested
	@DisplayName("base JSON shape")
	class BaseShape {

		@Test
		@DisplayName("renders level and message and omits every optional field when nothing is known")
		void shouldRenderMinimalLineWhenNoContextIsAvailable() {
			final String line = timestamplessLayout().doLayout(event("hello", Map.of()));

			assertEquals("{\"level\":\"INFO\",\"message\":\"hello\"}" + System.lineSeparator(), line);
		}

		@Test
		@DisplayName("renders client_id and trace_id from the MDC")
		void shouldRenderClientAndTraceIdentifiers() {
			final Map<String, String> mdc = CollectionUtils.createHashMap(2);
			mdc.put(TracingContext.MDC_CLIENT_ID_PROPERTY, "client-a");
			mdc.put(TracingContext.MDC_TRACE_ID_PROPERTY, "trace-b");

			final String line = timestamplessLayout().doLayout(event("hello", mdc));

			assertEquals(
				"{\"level\":\"INFO\",\"message\":\"hello\",\"client_id\":\"client-a\",\"trace_id\":\"trace-b\"}"
					+ System.lineSeparator(),
				line
			);
		}

		@Test
		@DisplayName("escapes quotes, newlines and tabs so one event stays one parseable line")
		void shouldEscapeControlCharactersInTheMessage() {
			final String line = timestamplessLayout().doLayout(
				event("he said \"hi\"\n\tindented", Map.of())
			);

			assertEquals(
				"{\"level\":\"INFO\",\"message\":\"he said \\\"hi\\\"\\n   indented\"}" + System.lineSeparator(),
				line
			);
		}

		@Test
		@DisplayName("appends the stack trace to the message and leaves the line parseable")
		void shouldRenderThrowableInsideTheMessageField() {
			final String line = timestamplessLayout().doLayout(
				eventWithThrowable("boom", new IllegalStateException("kaboom"))
			);

			final String separator = System.lineSeparator();
			assertTrue(line.endsWith("\"}" + separator), line);
			// the stack trace is appended *inside* the message field, behind an escaped newline
			assertTrue(line.startsWith("{\"level\":\"ERROR\",\"message\":\"boom\\n"), line);
			assertTrue(line.contains("java.lang.IllegalStateException"), line);
			assertTrue(line.contains("kaboom"), line);
			// everything the throwable contributed is escaped: the only real line break is the terminator
			final String body = line.substring(0, line.length() - separator.length());
			assertFalse(body.contains("\n"), body);
			assertFalse(body.contains("\r"), body);
			assertFalse(body.contains("\t"), body);
		}

		@Test
		@DisplayName("renders the timestamp under the declared pattern when timestamps are enabled")
		void shouldRenderTimestampUnderTheDeclaredPattern() {
			final String line = timestampedLayout().doLayout(event("hello", Map.of()));

			final String prefix = "{\"timestamp\":\"";
			assertTrue(line.startsWith(prefix), line);
			final String rendered = line.substring(prefix.length(), line.indexOf('"', prefix.length()));
			assertTrue(TIMESTAMP_SHAPE.matcher(rendered).matches(), rendered);
			assertDoesNotThrow(
				() -> DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ").parse(rendered),
				"the rendered timestamp does not parse under the pattern the layout declares"
			);
		}
	}

	@Nested
	@DisplayName("duration_ms from the captured request start")
	class DurationFromMdc {

		@Test
		@DisplayName("reports the elapsed time since the recorded request start")
		void shouldReportElapsedTimeSinceRequestStart() {
			final long now = System.currentTimeMillis();
			final String line = timestamplessLayout().doLayout(
				event("working", mdcWithRequestStart(Long.toString(now - 250L)), now)
			);

			assertTrue(line.contains(DURATION_FIELD_PREFIX), line);
			assertEquals(250L, durationOf(line));
		}

		@Test
		@DisplayName("omits the field when the request start key is absent")
		void shouldOmitDurationWhenRequestStartIsAbsent() {
			final String line = timestamplessLayout().doLayout(event("working", Map.of()));

			assertFalse(line.contains(DURATION_FIELD), line);
		}

		@Test
		@DisplayName("omits the field when the request start key is present but null")
		void shouldOmitDurationWhenRequestStartIsPresentButNull() {
			// a restored captured context writes every key unconditionally, so an unset value is a *present* key
			// holding null - reading it with a containment check instead of a null check would parse null here
			final Map<String, String> mdc = mdcWithRequestStart(null);
			assertTrue(mdc.containsKey(TracingContext.MDC_REQUEST_START_PROPERTY));

			final String line = timestamplessLayout().doLayout(event("working", mdc));

			assertFalse(line.contains(DURATION_FIELD), line);
		}

		@Test
		@DisplayName("omits the field rather than throwing when the request start is malformed")
		void shouldOmitDurationWhenRequestStartIsMalformed() {
			final String line = timestamplessLayout().doLayout(event("working", mdcWithRequestStart("not-a-number")));

			assertFalse(line.contains(DURATION_FIELD), line);
		}

		@Test
		@DisplayName("clamps at zero when the request start lies in the future")
		void shouldClampDurationAtZeroWhenClockWentBackwards() {
			final long now = System.currentTimeMillis();
			final String line = timestamplessLayout().doLayout(
				event("working", mdcWithRequestStart(Long.toString(now + 5_000L)), now)
			);

			assertEquals(0L, durationOf(line));
		}

		@Test
		@DisplayName("omits the field when the recorded request start is not a real instant")
		void shouldOmitDurationWhenRequestStartIsNotARealInstant() {
			// a value that parses but cannot be an instant is dropped rather than clamped: clamping only the
			// *difference* would render roughly the whole epoch and hand the aggregator a plausible-looking number
			final long now = System.currentTimeMillis();

			final String negative = timestamplessLayout().doLayout(event("working", mdcWithRequestStart("-1"), now));
			assertFalse(negative.contains(DURATION_FIELD), negative);

			final String zero = timestamplessLayout().doLayout(event("working", mdcWithRequestStart("0"), now));
			assertFalse(zero.contains(DURATION_FIELD), zero);
		}
	}

	@Nested
	@DisplayName("duration_ms from the Armeria request context")
	class DurationFromArmeriaContext {

		@Test
		@DisplayName("reports Armeria's own measurement once the request has completed")
		void shouldReportCompletedRequestDurationMeasuredByArmeria() {
			final ServiceRequestContext ctx = ServiceRequestContext.builder(
				HttpRequest.of(HttpMethod.GET, "/whatever")
			).build();
			ctx.logBuilder().endRequest();
			ctx.logBuilder().endResponse();

			try (SafeCloseable ignored = ctx.push()) {
				final RequestLog log = ctx.log().partial();
				// the event is stamped a minute and a half past the request start, so the in-flight source would
				// answer 90000 - a completed request must be answered from Armeria's monotonic measurement instead
				final String line = timestamplessLayout().doLayout(
					event("working", Map.of(), log.requestStartTimeMillis() + 90_000L)
				);

				assertEquals(log.totalDurationNanos() / 1_000_000L, durationOf(line));
				assertTrue(durationOf(line) < 90_000L, line);
			}
		}

		@Test
		@DisplayName("reports the elapsed time of an in-flight request")
		void shouldReportElapsedTimeOfInFlightRequest() {
			final ServiceRequestContext ctx = ServiceRequestContext.builder(
				HttpRequest.of(HttpMethod.GET, "/whatever")
			).build();

			try (SafeCloseable ignored = ctx.push()) {
				final long requestStart = ctx.log().partial().requestStartTimeMillis();
				final String line = timestamplessLayout().doLayout(
					event("working", Map.of(), requestStart + 120L)
				);

				assertEquals(120L, durationOf(line));
			}
		}

		@Test
		@DisplayName("prefers the Armeria context over a request start recorded in the MDC")
		void shouldPreferArmeriaContextOverCapturedRequestStart() {
			final ServiceRequestContext ctx = ServiceRequestContext.builder(
				HttpRequest.of(HttpMethod.GET, "/whatever")
			).build();

			try (SafeCloseable ignored = ctx.push()) {
				final long requestStart = ctx.log().partial().requestStartTimeMillis();
				// the MDC claims a far older start - the live context must win
				final String line = timestamplessLayout().doLayout(
					event("working", mdcWithRequestStart(Long.toString(requestStart - 90_000L)), requestStart + 10L)
				);

				assertEquals(10L, durationOf(line));
			}
		}

		@Test
		@DisplayName("reports the served request's duration for a line written during an outbound call")
		void shouldReportServedRequestDurationUnderANestedOutboundCall() {
			final ServiceRequestContext serviceContext = ServiceRequestContext.builder(
				HttpRequest.of(HttpMethod.GET, "/served")
			).build();

			try (SafeCloseable ignoredService = serviceContext.push()) {
				// Armeria resolves the root of a client context at construction time, so a call *made from inside*
				// a served request keeps that request as its root - this is what `ServiceRequestContext
				// .currentOrNull()` returns while the outbound call is current, and the duration reported here is
				// therefore the served request's, not the outbound call's
				final ClientRequestContext clientContext = ClientRequestContext.builder(
					HttpRequest.of(HttpMethod.GET, "/outbound")
				).build();
				assertSame(serviceContext, clientContext.root());

				try (SafeCloseable ignoredClient = clientContext.push()) {
					final long requestStart = serviceContext.log().partial().requestStartTimeMillis();
					final String line = timestamplessLayout().doLayout(
						event("working", Map.of(), requestStart + 40L)
					);

					assertEquals(40L, durationOf(line));
				}
			}
		}

		@Test
		@DisplayName("omits the field for an outbound call made outside any served request")
		void shouldOmitDurationUnderARootlessOutboundCall() {
			// `ServiceRequestContext.currentOrNull()` is `RequestContext.currentOrNull()` followed by `root()`, so
			// it does not reject a client context - it resolves one to the request behind it. A client context
			// built with no served request current has no root, which is what makes this case yield nothing.
			final ClientRequestContext clientContext = ClientRequestContext.builder(
				HttpRequest.of(HttpMethod.GET, "/outbound")
			).build();
			assertNull(clientContext.root(), "the outbound call unexpectedly has a served request behind it");

			try (SafeCloseable ignored = clientContext.push()) {
				final String line = timestamplessLayout().doLayout(event("working", Map.of()));

				assertFalse(line.contains(DURATION_FIELD), line);
			}
		}

		@Test
		@DisplayName("falls back to the captured request start when only a rootless outbound call is current")
		void shouldFallBackToCapturedRequestStartUnderOutboundCall() {
			final ClientRequestContext clientContext = ClientRequestContext.builder(
				HttpRequest.of(HttpMethod.GET, "/outbound")
			).build();

			try (SafeCloseable ignored = clientContext.push()) {
				final long now = System.currentTimeMillis();
				final String line = timestamplessLayout().doLayout(
					event("working", mdcWithRequestStart(Long.toString(now - 33L)), now)
				);

				assertEquals(33L, durationOf(line));
			}
		}
	}
}
