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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.slf4j.MDC;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.OBSERVABILITY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

	/**
	 * One way of handing a task to {@link ObservableThreadExecutor}.
	 *
	 * The executor wraps work in one of two context-restoring shapes - `ObservableRunnable` for everything built
	 * from a {@link Runnable} and `ObservableCallable` for {@link Callable} - and each carries its own emptiness
	 * guard, so a hand-off proven through one of them proves nothing about the other.
	 */
	@FunctionalInterface
	private interface Handoff {

		/**
		 * Hands {@code task} to {@code executor} and returns once it has been accepted.
		 *
		 * @param executor the executor to hand the task to
		 * @param task     the task to run
		 * @throws Exception when the submission or the wait for its future fails
		 */
		void hand(@Nonnull ObservableThreadExecutor executor, @Nonnull Runnable task) throws Exception;
	}

	/**
	 * A named submission shape, so a failing dynamic test says which of the executor's entry points lost the value.
	 *
	 * @param name    the entry point as it is written at a call site
	 * @param handoff the invocation itself
	 */
	private record Submission(@Nonnull String name, @Nonnull Handoff handoff) {
	}

	/**
	 * Hands a task carrying a known request start to the executor through the given shape and asserts the worker
	 * thread saw it.
	 *
	 * @param submission the submission shape under test
	 * @throws Exception when the submission fails or the wait is interrupted
	 */
	private static void assertRequestStartReachesWorker(@Nonnull Submission submission) throws Exception {
		final ObservableThreadExecutor executor = new ObservableThreadExecutor(
			"test-request-start",
			new ThreadPoolOptions(1, 2, Thread.NORM_PRIORITY, 32),
			false
		);
		try {
			// the context is snapshotted when the task is constructed, so this must precede the hand-off
			MDC.put(TracingContext.MDC_REQUEST_START_PROPERTY, "7777");

			final CountDownLatch finished = new CountDownLatch(1);
			final AtomicReference<String> seenOnWorker = new AtomicReference<>();
			final AtomicReference<String> workerThreadName = new AtomicReference<>();
			submission.handoff().hand(executor, () -> {
				seenOnWorker.set(MDC.get(TracingContext.MDC_REQUEST_START_PROPERTY));
				workerThreadName.set(Thread.currentThread().getName());
				finished.countDown();
			});

			// a positive wait - generous on purpose, since a loaded machine can only make the hand-off slower
			assertTrue(finished.await(30, TimeUnit.SECONDS), submission.name() + " never reached a worker");
			assertEquals("7777", seenOnWorker.get(), submission.name() + " lost the request start");
			assertNotEquals(
				Thread.currentThread().getName(),
				workerThreadName.get(),
				submission.name() + " ran inline rather than on a pooled worker"
			);
		} finally {
			TracingContext.clearContext();
			executor.shutdownNow();
		}
	}

	@BeforeEach
	void setUp() {
		// sibling classes share the surefire worker thread, so an ambient MDC value would satisfy the
		// "nothing is set" and "previous value" assertions below without the code under test doing anything
		TracingContext.clearContext();
	}

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
			assertNotSame(CapturedContext.EMPTY, captured);
		}

		@Test
		@DisplayName("an entirely unset context is the shared empty instance rather than a fresh allocation")
		void shouldReturnEmptySingletonWhenNothingIsSet() {
			assertTrue(TracingContext.captureContext().isEmpty());
			// an all-null record is *equal* to the sentinel, so only identity proves the allocation was avoided
			assertSame(CapturedContext.EMPTY, TracingContext.captureContext());
		}

		@Test
		@DisplayName("the five-component constructor leaves the request start unset")
		void shouldLeaveRequestStartUnsetWhenBuiltFromFiveComponents() {
			final CapturedContext legacy = new CapturedContext("t", "c", "ip", "uri", new Label[0]);

			assertNull(legacy.requestStart());
			assertEquals("t", legacy.traceId());
		}

		@Test
		@DisplayName("every component of the record survives a capture, clear and restore")
		void shouldRoundTripEveryCapturedComponent() {
			final Label[] labels = new Label[]{new Label("tenant", "acme")};
			MDC.put(TracingContext.MDC_TRACE_ID_PROPERTY, "trace-a");
			MDC.put(TracingContext.MDC_CLIENT_ID_PROPERTY, "client-b");
			MDC.put(TracingContext.MDC_CLIENT_IP_ADDRESS, "10.0.0.7");
			MDC.put(TracingContext.MDC_CLIENT_URI, "/uri");
			MDC.put(TracingContext.MDC_REQUEST_START_PROPERTY, "9000");
			TracingContext.CLIENT_LABELS.set(labels);

			final CapturedContext captured = TracingContext.captureContext();
			TracingContext.clearContext();
			TracingContext.setContext(captured);

			assertEquals("trace-a", MDC.get(TracingContext.MDC_TRACE_ID_PROPERTY));
			assertEquals("client-b", MDC.get(TracingContext.MDC_CLIENT_ID_PROPERTY));
			assertEquals("10.0.0.7", MDC.get(TracingContext.MDC_CLIENT_IP_ADDRESS));
			assertEquals("/uri", MDC.get(TracingContext.MDC_CLIENT_URI));
			assertEquals("9000", MDC.get(TracingContext.MDC_REQUEST_START_PROPERTY));
			// the labels are the one component that does not travel through the MDC at all
			assertArrayEquals(labels, TracingContext.CLIENT_LABELS.get());
			assertEquals(
				6, CapturedContext.class.getRecordComponents().length,
				"a component was added to CapturedContext - assert it above, or it never reaches a worker thread"
			);
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
		@DisplayName("a request start restored from a captured context survives an inner client-context block")
		void shouldNotEraseRequestStartRestoredFromACapturedContext() {
			// the same guard reached the way production reaches it - `setContext` first, the client-context block
			// second - so this also breaks if `setContext` stops writing the request start at all
			TracingContext.setContext(
				new CapturedContext("trace-a", "client-b", "10.0.0.7", "/outer", new Label[0], "1000")
			);
			assertEquals("1000", MDC.get(TracingContext.MDC_REQUEST_START_PROPERTY));

			final String observedInside = TracingContext.executeWithClientContext(
				null, "1.2.3.4", "/inner", new Label[0],
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

		@TestFactory
		@DisplayName("every way of handing work to the executor carries the request start to the worker")
		Stream<DynamicTest> shouldPropagateRequestStartThroughEverySubmissionShape() {
			final List<Submission> submissions = List.of(
				new Submission("execute(Runnable)", (executor, task) -> executor.execute(task)),
				new Submission(
					"submit(Runnable)",
					(executor, task) -> executor.submit(task).get(30, TimeUnit.SECONDS)
				),
				new Submission(
					"submit(Runnable, result)",
					(executor, task) -> executor.submit(task, Boolean.TRUE).get(30, TimeUnit.SECONDS)
				),
				new Submission(
					"submit(Callable)",
					(executor, task) -> {
						// bound to an explicit Callable so the shape under test cannot silently become the
						// Runnable overload, which is already covered above
						final Callable<Boolean> callable = () -> {
							task.run();
							return Boolean.TRUE;
						};
						executor.submit(callable).get(30, TimeUnit.SECONDS);
					}
				)
			);
			assertFalse(submissions.isEmpty(), "an empty factory would report as a pass having proven nothing");

			return submissions.stream().map(
				submission -> DynamicTest.dynamicTest(
					submission.name(),
					() -> assertRequestStartReachesWorker(submission)
				)
			);
		}
	}
}
