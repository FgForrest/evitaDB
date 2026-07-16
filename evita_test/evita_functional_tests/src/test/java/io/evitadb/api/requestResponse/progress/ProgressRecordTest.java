/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.api.requestResponse.progress;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.TASK;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for {@link ProgressRecord} verifying progress tracking, completion, observer
 * notification, and integration with {@link ProgressingFuture}.
 *
 * Tests cover factory methods, initial state, progress tracking with boundary validation, successful and
 * exceptional completion, and listener management.
 *
 * ## Determinism note
 *
 * All semantic tests run the task with the synchronous `Runnable::run` executor, which executes the task
 * inline inside the {@link ProgressRecord} constructor. This exercises the IDENTICAL wiring as a real thread
 * pool — the constructor registers the `whenComplete` handler and the progress consumer BEFORE it calls
 * {@link ProgressingFuture#execute(java.util.concurrent.Executor)} as its last statement — but with zero
 * concurrency, so there are no latches, timeouts or thread hand-offs to starve under a CPU-saturated
 * `parallel=all` run. The single genuinely cross-thread scenario lives in
 * {@link RealExecutorIntegrationTest}, which owns its own pool and uses generous (≥ 60 s) budgets per the
 * house rule for explicitly-async tests.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ProgressRecord functionality")
@Tag(CONTRACT)
@Tag(TASK)
class ProgressRecordTest implements EvitaTestSupport {

	@Nested
	@DisplayName("Factory methods")
	class FactoryMethodsTest {

		/**
		 * Verifies that the static `completed` factory creates a fully completed progress with the given
		 * non-null result. The record is already completed on return, so its completion value is read with the
		 * non-blocking `getNow`.
		 */
		@Test
		@DisplayName(
			"Should create completed progress with result"
		)
		void shouldCreateCompletedProgressWithResult() {
			final Progress<String> progress =
				ProgressRecord.completed("op", "value");

			assertEquals(100, progress.percentCompleted());
			assertTrue(progress.isCompletedSuccessfully());
			assertFalse(progress.isCompletedExceptionally());

			final CompletionStage<String> stage =
				progress.onCompletion();
			assertEquals("value", stage.toCompletableFuture().getNow(null));
		}

		/**
		 * Verifies that the static `completed` factory works correctly with a null result. The record is already
		 * completed on return, so its completion value is read with the non-blocking `getNow`.
		 */
		@Test
		@DisplayName(
			"Should create completed progress with null"
		)
		void shouldCreateCompletedProgressWithNullResult() {
			final Progress<String> progress =
				ProgressRecord.completed("op", null);

			assertEquals(100, progress.percentCompleted());
			assertTrue(progress.isCompletedSuccessfully());
			assertFalse(progress.isCompletedExceptionally());

			assertNull(progress.onCompletion().toCompletableFuture().getNow(null));
		}
	}

	@Nested
	@DisplayName("Initial state")
	class InitialStateTest {

		/**
		 * Verifies that a newly constructed ProgressRecord reports zero percent completed (the internal value is
		 * -1 but `percentCompleted()` floors it to 0 via `Math.max`).
		 */
		@Test
		@DisplayName(
			"Should start with zero percent"
			+ " when constructed with basic constructor"
		)
		void shouldStartWithZeroPercentWhenConstructedWithBasicConstructor() {
			final ProgressRecord<String> record =
				new ProgressRecord<>("op", null);

			assertEquals(0, record.percentCompleted());
		}

		/**
		 * Verifies that a newly constructed ProgressRecord is neither successfully nor exceptionally completed.
		 */
		@Test
		@DisplayName(
			"Should not be completed after construction"
		)
		void shouldNotBeCompletedAfterConstruction() {
			final ProgressRecord<String> record =
				new ProgressRecord<>("op", null);

			assertFalse(record.isCompletedSuccessfully());
			assertFalse(record.isCompletedExceptionally());
		}

		/**
		 * Verifies that the ProgressingFuture-based constructor calls `updatePercentCompleted(0)` during
		 * construction, and the observer receives the initial 0% notification. The synchronous `Runnable::run`
		 * executor runs the task inline in the constructor, so the observation is fully settled on return.
		 */
		@Test
		@DisplayName(
			"Should start with zero percent"
			+ " when constructed with ProgressingFuture"
		)
		void shouldStartWithZeroPercentWhenConstructedWithProgressingFuture() {
			final List<Integer> observed = new ArrayList<>();

			final ProgressingFuture<String> future =
				new ProgressingFuture<>(
					5,
					theFuture -> "result"
				);

			new ProgressRecord<>(
				"op", observed::add, future, Runnable::run
			);

			assertEquals("result", future.getNow(null));
			// observer should have received the initial 0% notification first
			assertTrue(
				observed.contains(0),
				"Observer should receive initial 0%"
			);
			assertEquals(0, observed.get(0));
		}
	}

	@Nested
	@DisplayName("Progress tracking")
	class ProgressTrackingTest {

		/**
		 * Verifies that `updatePercentCompleted` sets the percentage and `percentCompleted()` returns it.
		 */
		@Test
		@DisplayName(
			"Should update percent completed"
		)
		void shouldUpdatePercentCompleted() {
			final ProgressRecord<String> record =
				new ProgressRecord<>("op", null);

			record.updatePercentCompleted(50);

			assertEquals(50, record.percentCompleted());
		}

		/**
		 * Verifies that a negative percentage throws {@link GenericEvitaInternalError}.
		 */
		@Test
		@DisplayName(
			"Should throw on negative percentage"
		)
		void shouldThrowOnNegativePercentage() {
			final ProgressRecord<String> record =
				new ProgressRecord<>("op", null);

			final GenericEvitaInternalError ex =
				assertThrows(
					GenericEvitaInternalError.class,
					() -> record
						.updatePercentCompleted(-1)
				);
			assertTrue(
				ex.getMessage().contains("-1"),
				"Message should contain the bad value"
			);
		}

		/**
		 * Verifies that a percentage above 100 throws {@link GenericEvitaInternalError}.
		 */
		@Test
		@DisplayName(
			"Should throw on percentage above 100"
		)
		void shouldThrowOnPercentageAbove100() {
			final ProgressRecord<String> record =
				new ProgressRecord<>("op", null);

			final GenericEvitaInternalError ex =
				assertThrows(
					GenericEvitaInternalError.class,
					() -> record
						.updatePercentCompleted(101)
				);
			assertTrue(
				ex.getMessage().contains("101"),
				"Message should contain the bad value"
			);
		}

		/**
		 * Verifies that both boundary values 0 and 100 are accepted without error.
		 */
		@Test
		@DisplayName(
			"Should accept boundary values 0 and 100"
		)
		void shouldAcceptBoundaryValues() {
			final ProgressRecord<String> record =
				new ProgressRecord<>("op", null);

			assertDoesNotThrow(
				() -> record.updatePercentCompleted(0)
			);
			assertDoesNotThrow(
				() -> record.updatePercentCompleted(100)
			);
		}

		/**
		 * Verifies that setting the same percentage twice does not invoke the observer a second time.
		 */
		@Test
		@DisplayName(
			"Should not notify observer"
			+ " when value unchanged"
		)
		void shouldNotNotifyObserverWhenValueUnchanged() {
			final AtomicInteger callCount =
				new AtomicInteger(0);
			final IntConsumer observer =
				pct -> callCount.incrementAndGet();
			final ProgressRecord<String> record =
				new ProgressRecord<>("op", observer);

			record.updatePercentCompleted(50);
			record.updatePercentCompleted(50);

			assertEquals(
				1, callCount.get(),
				"Observer should be called only once"
				+ " for duplicate values"
			);
		}

		/**
		 * Verifies that all registered observers receive progress notifications.
		 */
		@Test
		@DisplayName(
			"Should notify multiple observers"
		)
		void shouldNotifyMultipleObservers() {
			final List<Integer> obs1 = new ArrayList<>();
			final List<Integer> obs2 = new ArrayList<>();
			final List<Integer> obs3 = new ArrayList<>();

			final ProgressRecord<String> record =
				new ProgressRecord<>(
					"op", obs1::add
				);
			record.addProgressListener(obs2::add);
			record.addProgressListener(obs3::add);

			record.updatePercentCompleted(75);

			assertEquals(
				List.of(75), obs1,
				"Observer 1 should receive update"
			);
			assertEquals(
				List.of(75), obs2,
				"Observer 2 should receive update"
			);
			assertEquals(
				List.of(75), obs3,
				"Observer 3 should receive update"
			);
		}
	}

	@Nested
	@DisplayName("Successful completion")
	class SuccessfulCompletionTest {

		/**
		 * Verifies that `complete()` marks the record as successfully completed with percent at 100 and the
		 * result accessible via `onCompletion()`. The record is completed synchronously, so the value is read
		 * with the non-blocking `getNow`.
		 */
		@Test
		@DisplayName(
			"Should complete with result"
		)
		void shouldCompleteWithResult() {
			final ProgressRecord<String> record =
				new ProgressRecord<>("op", null);

			record.complete("result");

			assertTrue(record.isCompletedSuccessfully());
			assertFalse(record.isCompletedExceptionally());
			assertEquals(
				100, record.percentCompleted()
			);

			assertEquals(
				"result",
				record.onCompletion().toCompletableFuture().getNow(null)
			);
		}

		/**
		 * Verifies that `complete(null)` works properly and results in a null completion value.
		 */
		@Test
		@DisplayName(
			"Should complete with null result"
		)
		void shouldCompleteWithNullResult() {
			final ProgressRecord<String> record =
				new ProgressRecord<>("op", null);

			record.complete(null);

			assertTrue(record.isCompletedSuccessfully());

			assertNull(
				record.onCompletion().toCompletableFuture().getNow(null)
			);
		}

		/**
		 * Verifies that `complete()` updates percent to 100 even when it was previously at 50.
		 */
		@Test
		@DisplayName(
			"Should update percent to 100 on complete"
		)
		void shouldUpdatePercentTo100OnComplete() {
			final ProgressRecord<String> record =
				new ProgressRecord<>("op", null);
			record.updatePercentCompleted(50);

			assertEquals(50, record.percentCompleted());

			record.complete("done");

			assertEquals(100, record.percentCompleted());
		}

		/**
		 * Verifies that a second call to `complete()` is ignored and the first result is preserved.
		 */
		@Test
		@DisplayName(
			"Should ignore subsequent complete calls"
		)
		void shouldIgnoreSubsequentCompleteCalls() {
			final ProgressRecord<String> record =
				new ProgressRecord<>("op", null);

			record.complete("first");
			record.complete("second");

			assertEquals(
				"first",
				record.onCompletion().toCompletableFuture().getNow(null),
				"First completion value should win"
			);
		}
	}

	@Nested
	@DisplayName("Exceptional completion")
	class ExceptionalCompletionTest {

		/**
		 * Verifies that `completeExceptionally()` marks the record as exceptionally completed and the exception
		 * is retrievable. The record is completed synchronously, so the unbounded `get()` returns immediately.
		 */
		@Test
		@DisplayName(
			"Should complete exceptionally"
		)
		void shouldCompleteExceptionally() {
			final RuntimeException cause =
				new RuntimeException("boom");
			final ProgressRecord<String> record =
				new ProgressRecord<>("op", null);

			record.completeExceptionally(cause);

			assertTrue(record.isCompletedExceptionally());
			assertFalse(record.isCompletedSuccessfully());

			final ExecutionException ex = assertThrows(
				ExecutionException.class,
				() -> record.onCompletion()
					.toCompletableFuture()
					.get()
			);
			assertSame(cause, ex.getCause());
		}

		/**
		 * Verifies that observers are notified with 100 when the record completes exceptionally.
		 */
		@Test
		@DisplayName(
			"Should notify observer with 100"
			+ " on exceptional completion"
		)
		void shouldNotifyObserverWith100OnExceptionalCompletion() {
			final List<Integer> observed =
				new ArrayList<>();
			final ProgressRecord<String> record =
				new ProgressRecord<>(
					"op", observed::add
				);

			record.completeExceptionally(
				new RuntimeException("err")
			);

			assertTrue(
				observed.contains(100),
				"Observer should receive 100"
			);
		}

		/**
		 * Verifies that calling `completeExceptionally()` twice is idempotent -- the first exception wins.
		 */
		@Test
		@DisplayName(
			"Should ignore subsequent exceptional"
			+ " complete calls"
		)
		void shouldIgnoreSubsequentExceptionalCompleteCalls() {
			final RuntimeException first =
				new RuntimeException("first");
			final RuntimeException second =
				new RuntimeException("second");
			final ProgressRecord<String> record =
				new ProgressRecord<>("op", null);

			record.completeExceptionally(first);
			record.completeExceptionally(second);

			final ExecutionException ex = assertThrows(
				ExecutionException.class,
				() -> record.onCompletion()
					.toCompletableFuture()
					.get()
			);
			assertSame(
				first, ex.getCause(),
				"First exception should win"
			);
		}

		/**
		 * Verifies that calling `complete()` after `completeExceptionally()` is a no-op.
		 */
		@Test
		@DisplayName(
			"Should ignore complete after"
			+ " exceptional completion"
		)
		void shouldIgnoreCompleteAfterExceptionalCompletion() {
			final RuntimeException cause =
				new RuntimeException("err");
			final ProgressRecord<String> record =
				new ProgressRecord<>("op", null);

			record.completeExceptionally(cause);
			record.complete("ignored");

			assertTrue(record.isCompletedExceptionally());
			assertFalse(record.isCompletedSuccessfully());
		}
	}

	@Nested
	@DisplayName("Progress listeners")
	class ProgressListenersTest {

		/**
		 * Verifies that a dynamically added listener receives progress notifications.
		 */
		@Test
		@DisplayName(
			"Should add and notify listener"
		)
		void shouldAddAndNotifyListener() {
			final ProgressRecord<String> record =
				new ProgressRecord<>("op", null);
			final List<Integer> observed =
				new ArrayList<>();
			record.addProgressListener(observed::add);

			record.updatePercentCompleted(42);

			assertEquals(List.of(42), observed);
		}

		/**
		 * Verifies that a removed listener no longer receives notifications.
		 */
		@Test
		@DisplayName(
			"Should remove listener"
		)
		void shouldRemoveListener() {
			final ProgressRecord<String> record =
				new ProgressRecord<>("op", null);
			final List<Integer> observed =
				new ArrayList<>();
			final IntConsumer listener = observed::add;

			record.addProgressListener(listener);
			record.removeProgressListener(listener);

			record.updatePercentCompleted(42);

			assertTrue(
				observed.isEmpty(),
				"Removed listener should not be notified"
			);
		}

		/**
		 * Verifies that an exception thrown by one listener does not prevent other listeners from being notified
		 * and does not propagate to the caller.
		 */
		@Test
		@DisplayName(
			"Should handle listener exception"
		)
		void shouldHandleListenerException() {
			final List<Integer> goodObserved =
				new ArrayList<>();
			final IntConsumer badListener =
				pct -> {
					throw new RuntimeException(
						"listener error"
					);
				};

			final ProgressRecord<String> record =
				new ProgressRecord<>("op", badListener);
			record.addProgressListener(goodObserved::add);

			// should not propagate the exception
			assertDoesNotThrow(
				() -> record.updatePercentCompleted(60)
			);

			assertEquals(
				List.of(60), goodObserved,
				"Good listener should still be notified"
			);
		}

		/**
		 * Verifies that removing a listener that was never added does not cause errors.
		 */
		@Test
		@DisplayName(
			"Should handle removing non-existent listener"
		)
		void shouldHandleRemovingNonExistentListener() {
			final ProgressRecord<String> record =
				new ProgressRecord<>("op", null);
			final IntConsumer listener = pct -> {};

			assertDoesNotThrow(
				() -> record
					.removeProgressListener(listener)
			);
		}
	}

	@Nested
	@DisplayName("ProgressingFuture integration")
	class ProgressingFutureIntegrationTest {

		/**
		 * Verifies that a ProgressRecord constructed with a ProgressingFuture correctly tracks percentage
		 * updates computed from step progress. The task reaches the future through its own lambda parameter
		 * (`lambda.apply(this)`), so no external hand-off is needed; the synchronous `Runnable::run` executor
		 * runs it inline inside the constructor, which — because the constructor wires the progress consumer and
		 * `whenComplete` BEFORE calling `execute` — exercises the identical wiring with zero concurrency. A
		 * future reordering of the constructor that publishes progress after execution would break this test
		 * loudly (the observer would miss the mid-flight 50%).
		 */
		@Test
		@DisplayName(
			"Should track progress"
			+ " from ProgressingFuture"
		)
		void shouldTrackProgressFromProgressingFuture() {
			final List<Integer> observed = new ArrayList<>();
			final ProgressingFuture<String> future =
				new ProgressingFuture<>(
					9,
					theFuture -> {
						// 5 of 10 steps (actionSteps + 1) = 50%
						theFuture.updateProgress(5);
						return "done";
					}
				);

			new ProgressRecord<>(
				"op", observed::add, future, Runnable::run
			);

			assertEquals("done", future.getNow(null));
			assertTrue(
				observed.contains(0),
				"Should receive initial 0%"
			);
			assertTrue(
				observed.contains(50),
				"Should receive 50% at midpoint"
			);
		}

		/**
		 * Verifies that the ProgressRecord is marked as successfully completed when the underlying
		 * ProgressingFuture completes normally, and that its `onCompletion` propagates the original result. The
		 * task runs inline via `Runnable::run`, so completion is settled on constructor return.
		 */
		@Test
		@DisplayName(
			"Should complete when"
			+ " ProgressingFuture completes"
		)
		void shouldCompleteWhenProgressingFutureCompletes() {
			final ProgressingFuture<String> future =
				new ProgressingFuture<>(
					5,
					theFuture -> "hello"
				);

			final ProgressRecord<String> record =
				new ProgressRecord<>(
					"op", null, future, Runnable::run
				);

			assertTrue(record.isCompletedSuccessfully());
			// the ProgressRecord's onCompletion is derived from the future's whenComplete, which propagates
			// the original result
			assertEquals(
				"hello",
				record.onCompletion().toCompletableFuture().getNow(null)
			);
		}

		/**
		 * Verifies that the `onProgressExecution` consumer is invoked during construction of the full
		 * constructor variant, before the task executes.
		 */
		@Test
		@DisplayName(
			"Should call onProgressExecution consumer"
		)
		void shouldCallOnProgressExecutionConsumer() {
			final AtomicBoolean executionCalled =
				new AtomicBoolean(false);

			final ProgressingFuture<String> future =
				new ProgressingFuture<>(
					5,
					theFuture -> "result"
				);

			new ProgressRecord<>(
				"op",
				null,
				future,
				pr -> executionCalled.set(true),
				pr -> {},
				Runnable::run
			);

			// the callback is invoked during construction, before execute()
			assertTrue(
				executionCalled.get(),
				"onProgressExecution should be called"
				+ " during construction"
			);
			assertEquals("result", future.getNow(null));
		}

		/**
		 * Verifies that the `onProgressCompletion` consumer is invoked when the underlying ProgressingFuture
		 * completes. Because the task runs inline via `Runnable::run`, completion — and therefore the
		 * `whenComplete`-driven callback — happens synchronously inside the constructor.
		 */
		@Test
		@DisplayName(
			"Should call onProgressCompletion consumer"
		)
		void shouldCallOnProgressCompletionConsumer() {
			final AtomicBoolean completionCalled =
				new AtomicBoolean(false);

			final ProgressingFuture<String> future =
				new ProgressingFuture<>(
					5,
					theFuture -> "result"
				);

			new ProgressRecord<>(
				"op",
				null,
				future,
				pr -> {},
				pr -> completionCalled.set(true),
				Runnable::run
			);

			assertTrue(
				completionCalled.get(),
				"onProgressCompletion should be called"
				+ " when the future completes"
			);
			assertEquals("result", future.getNow(null));
		}
	}

	@Nested
	@DisplayName("Completion percent consistency")
	class CompletionPercentConsistencyTest {

		/**
		 * Verifies that `completeExceptionally()` updates `percentCompleted()` to 100 (matching the behaviour of
		 * `complete()`) even when it was previously at an intermediate value, and that the observer receives 100.
		 */
		@Test
		@DisplayName(
			"Should update percentCompleted to 100"
			+ " on exceptional completion"
		)
		void shouldUpdatePercentCompletedTo100OnExceptionalCompletion() {
			final List<Integer> observed =
				new ArrayList<>();
			final ProgressRecord<String> record =
				new ProgressRecord<>(
					"op", observed::add
				);
			record.updatePercentCompleted(40);

			record.completeExceptionally(
				new RuntimeException("err")
			);

			assertEquals(
				100,
				record.percentCompleted(),
				"percentCompleted() should return"
				+ " 100 after exceptional completion"
			);

			assertTrue(
				observed.contains(100),
				"Observer should receive 100"
			);
		}
	}

	@Nested
	@DisplayName("Real-executor integration")
	class RealExecutorIntegrationTest {

		private ExecutorService executor;

		/**
		 * Creates a real thread pool for the genuinely cross-thread scenario in this nested class.
		 */
		@BeforeEach
		void setUp() {
			this.executor = Executors.newFixedThreadPool(4);
		}

		/**
		 * Shuts the pool down after each test via the explicit `shutdownNow()`. `ExecutorService` implements
		 * `AutoCloseable` only since JDK 19; evitaDB builds on OpenJDK 17, so an `instanceof AutoCloseable` guard
		 * (as an earlier revision of this class used) would silently never fire and leak the pool.
		 */
		@AfterEach
		void tearDown() {
			if (this.executor != null) {
				this.executor.shutdownNow();
			}
		}

		/**
		 * Exercises the real cross-thread execution path: the task runs on a separate pool thread via
		 * {@link ProgressingFuture#execute(java.util.concurrent.Executor)} and the record completes with the
		 * task's result. This is the one scenario whose asserted property IS the cross-thread behaviour, so it
		 * keeps a real executor; per the house rule for explicitly-async tests it uses a generous 60 s budget
		 * (never the flake-prone small budgets) as a backstop against a genuine deadlock.
		 */
		@Test
		@DisplayName(
			"Should complete via a real executor thread"
		)
		void shouldCompleteViaRealExecutorThread()
			throws ExecutionException,
			InterruptedException,
			TimeoutException {

			final ProgressingFuture<String> future =
				new ProgressingFuture<>(
					5,
					theFuture -> "hello"
				);

			final ProgressRecord<String> record =
				new ProgressRecord<>(
					"op", null, future, this.executor
				);

			assertEquals("hello", future.get(60, TimeUnit.SECONDS));
			assertEquals(
				"hello",
				record.onCompletion()
					.toCompletableFuture()
					.get(60, TimeUnit.SECONDS)
			);
			assertTrue(record.isCompletedSuccessfully());
		}
	}
}
