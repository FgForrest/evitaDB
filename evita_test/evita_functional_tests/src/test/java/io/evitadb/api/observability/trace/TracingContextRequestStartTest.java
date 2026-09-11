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

package io.evitadb.api.observability.trace;

import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.observability.trace.TracingContext.CapturedContext;
import io.evitadb.api.query.head.Label;
import io.evitadb.core.executor.ObservableThreadExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.OBSERVABILITY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the instant a request started survives every hop between the thread that accepted the request and
 * the thread that ends up logging.
 *
 * The interesting cases here are not the happy path but the two ways the value can be silently lost: a captured
 * context that reports itself empty (the executor then skips restoring it altogether), and a nested client-context
 * scope that does not know the request start and would otherwise blank the one its caller established.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("TracingContext - request start propagation")
@Tag(ENGINE)
@Tag(OBSERVABILITY)
class TracingContextRequestStartTest {

	@AfterEach
	void tearDown() {
		TracingContext.clearContext();
	}

	@Nested
	@DisplayName("capture and restore")
	class CaptureAndRestore {

		@Test
		@DisplayName("captures the request start from the MDC and restores it")
		void shouldCaptureAndRestoreRequestStart() {
			MDC.put(TracingContext.MDC_REQUEST_START_PROPERTY, "1234");

			final CapturedContext captured = TracingContext.captureContext();
			assertEquals("1234", captured.requestStart());

			TracingContext.clearContext();
			assertNull(MDC.get(TracingContext.MDC_REQUEST_START_PROPERTY));

			TracingContext.setContext(captured);
			assertEquals("1234", MDC.get(TracingContext.MDC_REQUEST_START_PROPERTY));
		}

		@Test
		@DisplayName("a context holding only the request start does not report itself empty")
		void shouldNotReportEmptyWhenOnlyRequestStartIsSet() {
			// ObservableThreadExecutor skips restoring the context entirely when isEmpty() is true, and the gRPC
			// session path captures before any trace or client id exists - so a request start alone must count
			MDC.put(TracingContext.MDC_REQUEST_START_PROPERTY, "42");

			final CapturedContext captured = TracingContext.captureContext();

			assertFalse(captured.isEmpty());
			assertFalse(captured == CapturedContext.EMPTY);
		}

		@Test
		@DisplayName("an entirely unset context is still the shared empty instance")
		void shouldReturnEmptySingletonWhenNothingIsSet() {
			assertTrue(TracingContext.captureContext().isEmpty());
			assertEquals(CapturedContext.EMPTY, TracingContext.captureContext());
		}

		@Test
		@DisplayName("the five-argument constructor still compiles and leaves the request start unset")
		void shouldSupportLegacyFiveArgumentConstructor() {
			final CapturedContext legacy = new CapturedContext("t", "c", "ip", "uri", new Label[0]);

			assertNull(legacy.requestStart());
			assertEquals("t", legacy.traceId());
		}
	}

	@Nested
	@DisplayName("nested scopes")
	class NestedScopes {

		@Test
		@DisplayName("a null request start leaves the enclosing one untouched")
		void shouldNotEraseRequestStartWhenInnerScopeHasNone() {
			// this is the regression guard for the defect that would have blanked the field on the gRPC session
			// path: the service method opens its client-context block on a worker thread *after* the captured
			// context was restored, and it has no Armeria context to read a request start from
			MDC.put(TracingContext.MDC_REQUEST_START_PROPERTY, "1000");

			final String observedInside = TracingContext.executeWithClientContext(
				null, "1.2.3.4", "/uri", new Label[0],
				() -> MDC.get(TracingContext.MDC_REQUEST_START_PROPERTY)
			);

			assertEquals("1000", observedInside);
			assertEquals("1000", MDC.get(TracingContext.MDC_REQUEST_START_PROPERTY));
		}

		@Test
		@DisplayName("restores the enclosing client context rather than removing it")
		void shouldRestoreOuterClientContextAfterInnerScope() {
			MDC.put(TracingContext.MDC_CLIENT_IP_ADDRESS, "outer-ip");
			MDC.put(TracingContext.MDC_CLIENT_URI, "/outer");

			TracingContext.executeWithClientContext(
				"2000", "inner-ip", "/inner", new Label[0],
				() -> {
					assertEquals("inner-ip", MDC.get(TracingContext.MDC_CLIENT_IP_ADDRESS));
					assertEquals("2000", MDC.get(TracingContext.MDC_REQUEST_START_PROPERTY));
					return null;
				}
			);

			assertEquals("outer-ip", MDC.get(TracingContext.MDC_CLIENT_IP_ADDRESS));
			assertEquals("/outer", MDC.get(TracingContext.MDC_CLIENT_URI));
			assertNull(MDC.get(TracingContext.MDC_REQUEST_START_PROPERTY));
		}

		@Test
		@DisplayName("executeWithRequestStart records the value and restores the previous one")
		void shouldScopeRequestStartOnly() {
			MDC.put(TracingContext.MDC_CLIENT_IP_ADDRESS, "kept");
			MDC.put(TracingContext.MDC_REQUEST_START_PROPERTY, "111");

			final String observed = TracingContext.executeWithRequestStart(
				"222", () -> MDC.get(TracingContext.MDC_REQUEST_START_PROPERTY)
			);

			assertEquals("222", observed);
			assertEquals("111", MDC.get(TracingContext.MDC_REQUEST_START_PROPERTY));
			assertEquals("kept", MDC.get(TracingContext.MDC_CLIENT_IP_ADDRESS));
		}

		@Test
		@DisplayName("executeWithRequestStart with null touches nothing at all")
		void shouldLeaveEverythingAloneWhenRequestStartIsNull() {
			MDC.put(TracingContext.MDC_REQUEST_START_PROPERTY, "333");

			final String observed = TracingContext.executeWithRequestStart(
				null, () -> MDC.get(TracingContext.MDC_REQUEST_START_PROPERTY)
			);

			assertEquals("333", observed);
			assertEquals("333", MDC.get(TracingContext.MDC_REQUEST_START_PROPERTY));
		}
	}

	@Nested
	@DisplayName("hand-off to a worker thread")
	class WorkerHandOff {

		@Test
		@DisplayName("a task submitted with only a request start set sees it on the worker")
		void shouldPropagateRequestStartToWorkerThread() throws Exception {
			final ObservableThreadExecutor executor = new ObservableThreadExecutor(
				"test-request-start",
				new ThreadPoolOptions(1, 2, Thread.NORM_PRIORITY, 32),
				false
			);
			try {
				MDC.put(TracingContext.MDC_REQUEST_START_PROPERTY, "7777");

				final AtomicReference<String> seenOnWorker = new AtomicReference<>();
				final AtomicReference<String> workerThreadName = new AtomicReference<>();
				executor.submit(() -> {
					seenOnWorker.set(MDC.get(TracingContext.MDC_REQUEST_START_PROPERTY));
					workerThreadName.set(Thread.currentThread().getName());
				}).get(30, TimeUnit.SECONDS);

				assertEquals("7777", seenOnWorker.get());
				assertFalse(
					Thread.currentThread().getName().equals(workerThreadName.get()),
					"task must have run on a pooled worker, not inline"
				);
			} finally {
				executor.shutdownNow();
			}
		}
	}
}
