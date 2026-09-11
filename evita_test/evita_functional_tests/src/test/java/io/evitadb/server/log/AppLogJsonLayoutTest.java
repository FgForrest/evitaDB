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
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.api.observability.trace.TracingContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

import static io.evitadb.test.TestTags.OBSERVABILITY;
import static io.evitadb.test.TestTags.SERVER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the JSON {@link AppLogJsonLayout} renders, with particular attention to `duration_ms` - which of the three
 * possible sources it comes from, and when it must be absent.
 *
 * Nothing else in the codebase asserts the shape of these lines, so the first test deliberately asserts the whole
 * rendered string rather than the presence of a single field: this class is what pins the format that log aggregators
 * consume.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("AppLogJsonLayout - JSON rendering and request duration")
@Tag(SERVER)
@Tag(OBSERVABILITY)
class AppLogJsonLayoutTest {

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
	 * Builds an MDC map with a single request-start entry, mirroring what a restored captured context looks like on
	 * a worker thread.
	 *
	 * @param requestStart the value to record, null allowed - a restored context writes keys unconditionally
	 * @return a mutable MDC map
	 */
	@Nonnull
	private static Map<String, String> mdcWithRequestStart(@Nullable String requestStart) {
		final Map<String, String> mdc = new HashMap<>(4);
		mdc.put(TracingContext.MDC_REQUEST_START_PROPERTY, requestStart);
		return mdc;
	}

	/**
	 * Extracts the numeric `duration_ms` value from a rendered line.
	 *
	 * @param renderedLine the line produced by the layout
	 * @return the parsed value
	 */
	private static long durationOf(@Nonnull String renderedLine) {
		final int start = renderedLine.indexOf("\"duration_ms\":") + "\"duration_ms\":".length();
		int end = start;
		while (end < renderedLine.length() && (Character.isDigit(renderedLine.charAt(end)))) {
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
			final Map<String, String> mdc = new HashMap<>();
			mdc.put(TracingContext.MDC_CLIENT_ID_PROPERTY, "client-a");
			mdc.put(TracingContext.MDC_TRACE_ID_PROPERTY, "trace-b");

			final String line = timestamplessLayout().doLayout(event("hello", mdc));

			assertEquals(
				"{\"level\":\"INFO\",\"message\":\"hello\",\"client_id\":\"client-a\",\"trace_id\":\"trace-b\"}"
					+ System.lineSeparator(),
				line
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

			assertTrue(line.contains("\"duration_ms\":"), line);
			assertEquals(250L, durationOf(line));
		}

		@Test
		@DisplayName("omits the field when the request start key is absent")
		void shouldOmitDurationWhenRequestStartIsAbsent() {
			final String line = timestamplessLayout().doLayout(event("working", Map.of()));

			assertFalse(line.contains("duration_ms"), line);
		}

		@Test
		@DisplayName("omits the field when the request start key is present but null")
		void shouldOmitDurationWhenRequestStartIsPresentButNull() {
			// a restored captured context writes every key unconditionally, so an unset value is a *present* key
			// holding null - reading it with a containment check instead of a null check would parse null here
			final Map<String, String> mdc = mdcWithRequestStart(null);
			assertTrue(mdc.containsKey(TracingContext.MDC_REQUEST_START_PROPERTY));

			final String line = timestamplessLayout().doLayout(event("working", mdc));

			assertFalse(line.contains("duration_ms"), line);
		}

		@Test
		@DisplayName("omits the field rather than throwing when the request start is malformed")
		void shouldOmitDurationWhenRequestStartIsMalformed() {
			final String line = timestamplessLayout().doLayout(event("working", mdcWithRequestStart("not-a-number")));

			assertFalse(line.contains("duration_ms"), line);
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
	}

	@Nested
	@DisplayName("duration_ms from the Armeria request context")
	class DurationFromArmeriaContext {

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
		@DisplayName("ignores an outbound client call current on the logging thread")
		void shouldIgnoreOutboundClientContext() {
			// RequestContext.currentOrNull() would match this; ServiceRequestContext.currentOrNull() must not, because
			// the duration of a call evitaDB is *making* says nothing about the request it is serving
			final ClientRequestContext clientContext = ClientRequestContext.builder(
				HttpRequest.of(HttpMethod.GET, "/outbound")
			).build();

			try (SafeCloseable ignored = clientContext.push()) {
				final String line = timestamplessLayout().doLayout(event("working", Map.of()));

				assertFalse(line.contains("duration_ms"), line);
			}
		}

		@Test
		@DisplayName("falls back to the captured request start when only an outbound call is current")
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
