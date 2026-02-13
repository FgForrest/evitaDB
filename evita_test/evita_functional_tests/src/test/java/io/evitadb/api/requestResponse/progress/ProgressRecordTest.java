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
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for {@link ProgressRecord}
 * verifying progress tracking, completion, observer
 * notification, and integration with
 * {@link ProgressingFuture}.
 *
 * Tests cover factory methods, initial state,
 * progress tracking with boundary validation,
 * successful and exceptional completion,
 * listener management, and ProgressingFuture
 * integration. A dedicated section documents
 * known limitations in the current implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ProgressRecord functionality")
class ProgressRecordTest implements EvitaTestSupport {

	private Executor executor;

	/**
	 * Sets up a thread pool executor for tests
	 * requiring asynchronous execution.
	 */
	@BeforeEach
	void setUp() {
		this.executor = Executors.newFixedThreadPool(4);
	}

	/**
	 * Tears down the executor after each test to
	 * release thread pool resources.
	 */
	@AfterEach
	void tearDown() {
		if (this.executor instanceof AutoCloseable) {
			try {
				((AutoCloseable) this.executor).close();
			} catch (Exception e) {
				// ignore
			}
		}
	}

	@Nested
	@DisplayName("Factory methods")
	class FactoryMethodsTest {

		/**
		 * Verifies that the static `completed` factory
		 * creates a fully completed progress with the
		 * given non-null result.
		 */
		@Test
		@DisplayName(
			"Should create completed progress with result"
		)
		void shouldCreateCompletedProgressWithResult()
			throws ExecutionException,
			InterruptedException,
			TimeoutException {

			final Progress<String> progress =
				ProgressRecord.completed("op", "value");

			assertEquals(100, progress.percentCompleted());
			assertTrue(progress.isCompletedSuccessfully());
			assertFalse(progress.isCompletedExceptionally());

			final CompletionStage<String> stage =
				progress.onCompletion();
			final String result =
				stage.toCompletableFuture()
					.get(1, TimeUnit.SECONDS);
			assertEquals("value", result);
		}

		/**
		 * Verifies that the static `completed` factory
		 * works correctly with a null result.
		 */
		@Test
		@DisplayName(
			"Should create completed progress with null"
		)
		void shouldCreateCompletedProgressWithNullResult()
			throws ExecutionException,
			InterruptedException,
			TimeoutException {

			final Progress<String> progress =
				ProgressRecord.completed("op", null);

			assertEquals(100, progress.percentCompleted());
			assertTrue(progress.isCompletedSuccessfully());
			assertFalse(progress.isCompletedExceptionally());

			final String result =
				progress.onCompletion()
					.toCompletableFuture()
					.get(1, TimeUnit.SECONDS);
			assertNull(result);
		}
	}

	@Nested
	@DisplayName("Initial state")
	class InitialStateTest {

		/**
		 * Verifies that a newly constructed ProgressRecord
		 * reports zero percent completed (the internal
		 * value is -1 but `percentCompleted()` floors it
		 * to 0 via `Math.max`).
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
		 * Verifies that a newly constructed ProgressRecord
		 * is neither successfully nor exceptionally
		 * completed.
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
		 * Verifies that the ProgressingFuture-based
		 * constructor calls `updatePercentCompleted(0)`
		 * during construction, and the observer receives
		 * the initial 0% notification.
		 */
		@Test
		@DisplayName(
			"Should start with zero percent"
			+ " when constructed with ProgressingFuture"
		)
		void shouldStartWithZeroPercentWhenConstructedWithProgressingFuture()
			throws ExecutionException,
			InterruptedException,
			TimeoutException {

			final List<Integer> observed =
				new CopyOnWriteArrayList<>();
			final IntConsumer observer = observed::add;

			final ProgressingFuture<String> future =
				new ProgressingFuture<>(
					5,
					theFuture -> "result"
				);

			final ProgressRecord<String> record =
				new ProgressRecord<>(
					"op", observer, future, executor
				);

			// wait for the future to complete
			future.get(2, TimeUnit.SECONDS);

			// observer should have received the
			// initial 0% notification
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
		 * Verifies that `updatePercentCompleted` sets the
		 * percentage and `percentCompleted()` returns it.
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
		 * Verifies that a negative percentage throws
		 * IllegalArgumentException.
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
		 * Verifies that a percentage above 100 throws
		 * IllegalArgumentException.
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
		 * Verifies that both boundary values 0 and 100
		 * are accepted without error.
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
		 * Verifies that setting the same percentage twice
		 * does not invoke the observer a second time.
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
		 * Verifies that all registered observers receive
		 * progress notifications.
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
		 * Verifies that `complete()` marks the record
		 * as successfully completed with percent at 100
		 * and the result accessible via `onCompletion()`.
		 */
		@Test
		@DisplayName(
			"Should complete with result"
		)
		void shouldCompleteWithResult()
			throws ExecutionException,
			InterruptedException,
			TimeoutException {

			final ProgressRecord<String> record =
				new ProgressRecord<>("op", null);

			record.complete("result");

			assertTrue(record.isCompletedSuccessfully());
			assertFalse(record.isCompletedExceptionally());
			assertEquals(
				100, record.percentCompleted()
			);

			final String result =
				record.onCompletion()
					.toCompletableFuture()
					.get(1, TimeUnit.SECONDS);
			assertEquals("result", result);
		}

		/**
		 * Verifies that `complete(null)` works properly
		 * and results in a null completion value.
		 */
		@Test
		@DisplayName(
			"Should complete with null result"
		)
		void shouldCompleteWithNullResult()
			throws ExecutionException,
			InterruptedException,
			TimeoutException {

			final ProgressRecord<String> record =
				new ProgressRecord<>("op", null);

			record.complete(null);

			assertTrue(record.isCompletedSuccessfully());

			final String result =
				record.onCompletion()
					.toCompletableFuture()
					.get(1, TimeUnit.SECONDS);
			assertNull(result);
		}

		/**
		 * Verifies that `complete()` updates percent
		 * to 100 even when it was previously at 50.
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
		 * Verifies that a second call to `complete()` is
		 * ignored and the first result is preserved.
		 */
		@Test
		@DisplayName(
			"Should ignore subsequent complete calls"
		)
		void shouldIgnoreSubsequentCompleteCalls()
			throws ExecutionException,
			InterruptedException,
			TimeoutException {

			final ProgressRecord<String> record =
				new ProgressRecord<>("op", null);

			record.complete("first");
			record.complete("second");

			final String result =
				record.onCompletion()
					.toCompletableFuture()
					.get(1, TimeUnit.SECONDS);
			assertEquals(
				"first", result,
				"First completion value should win"
			);
		}
	}

	@Nested
	@DisplayName("Exceptional completion")
	class ExceptionalCompletionTest {

		/**
		 * Verifies that `completeExceptionally()` marks
		 * the record as exceptionally completed and
		 * the exception is retrievable.
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
					.get(1, TimeUnit.SECONDS)
			);
			assertSame(cause, ex.getCause());
		}

		/**
		 * Verifies that observers are notified with 100
		 * when the record completes exceptionally.
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
		 * Verifies that calling `completeExceptionally()`
		 * twice is idempotent -- the first exception wins.
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
					.get(1, TimeUnit.SECONDS)
			);
			assertSame(
				first, ex.getCause(),
				"First exception should win"
			);
		}

		/**
		 * Verifies that calling `complete()` after
		 * `completeExceptionally()` is a no-op.
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
		 * Verifies that a dynamically added listener
		 * receives progress notifications.
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
		 * Verifies that a removed listener no longer
		 * receives notifications.
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
		 * Verifies that an exception thrown by one
		 * listener does not prevent other listeners
		 * from being notified and does not propagate
		 * to the caller.
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
		 * Verifies that removing a listener that was
		 * never added does not cause errors.
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
		 * Verifies that a ProgressRecord constructed
		 * with a ProgressingFuture correctly tracks
		 * percentage updates computed from step
		 * progress.
		 */
		@Test
		@DisplayName(
			"Should track progress"
			+ " from ProgressingFuture"
		)
		void shouldTrackProgressFromProgressingFuture()
			throws ExecutionException,
			InterruptedException,
			TimeoutException {

			final List<Integer> observed =
				new CopyOnWriteArrayList<>();
			final CountDownLatch started =
				new CountDownLatch(1);
			final CountDownLatch proceed =
				new CountDownLatch(1);
			final AtomicReference<ProgressingFuture<String>>
				futureRef = new AtomicReference<>();

			final ProgressingFuture<String> future =
				new ProgressingFuture<>(
					9,
					theFuture -> {
						started.countDown();
						try {
							proceed.await(
								2, TimeUnit.SECONDS
							);
						} catch (InterruptedException e) {
							Thread.currentThread()
								.interrupt();
						}
						final ProgressingFuture<String> f =
							futureRef.get();
						if (f != null) {
							// 5 of 10 steps = 50%
							f.updateProgress(5);
						}
						return "done";
					}
				);
			futureRef.set(future);

			final ProgressRecord<String> record =
				new ProgressRecord<>(
					"op", observed::add,
					future, executor
				);

			assertTrue(
				started.await(2, TimeUnit.SECONDS),
				"Task should start"
			);
			proceed.countDown();

			final String result =
				future.get(2, TimeUnit.SECONDS);
			assertEquals("done", result);

			// observer should have received 0
			// (initial) and 50 (5/10 * 100)
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
		 * Verifies that the ProgressRecord is marked
		 * as successfully completed when the underlying
		 * ProgressingFuture completes normally.
		 */
		@Test
		@DisplayName(
			"Should complete when"
			+ " ProgressingFuture completes"
		)
		void shouldCompleteWhenProgressingFutureCompletes()
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
					"op", null, future, executor
				);

			// wait for the progressing future
			future.get(2, TimeUnit.SECONDS);

			// the ProgressRecord's onCompletion is
			// derived from the future's whenComplete,
			// which propagates the original result
			final String result =
				record.onCompletion()
					.toCompletableFuture()
					.get(2, TimeUnit.SECONDS);
			assertEquals("hello", result);
		}

		/**
		 * Verifies that the `onProgressExecution`
		 * consumer is invoked during construction of
		 * the full constructor variant.
		 */
		@Test
		@DisplayName(
			"Should call onProgressExecution consumer"
		)
		void shouldCallOnProgressExecutionConsumer()
			throws ExecutionException,
			InterruptedException,
			TimeoutException {

			final AtomicBoolean executionCalled =
				new AtomicBoolean(false);

			final ProgressingFuture<String> future =
				new ProgressingFuture<>(
					5,
					theFuture -> "result"
				);

			final ProgressRecord<String> record =
				new ProgressRecord<>(
					"op",
					null,
					future,
					pr -> executionCalled.set(true),
					pr -> {},
					executor
				);

			// the callback is invoked during
			// construction, before execute()
			assertTrue(
				executionCalled.get(),
				"onProgressExecution should be called"
				+ " during construction"
			);

			future.get(2, TimeUnit.SECONDS);
		}

		/**
		 * Verifies that the `onProgressCompletion`
		 * consumer is invoked when the underlying
		 * ProgressingFuture completes.
		 */
		@Test
		@DisplayName(
			"Should call onProgressCompletion consumer"
		)
		void shouldCallOnProgressCompletionConsumer()
			throws ExecutionException,
			InterruptedException,
			TimeoutException {

			final CountDownLatch completionLatch =
				new CountDownLatch(1);

			final ProgressingFuture<String> future =
				new ProgressingFuture<>(
					5,
					theFuture -> "result"
				);

			final ProgressRecord<String> record =
				new ProgressRecord<>(
					"op",
					null,
					future,
					pr -> {},
					pr -> completionLatch.countDown(),
					executor
				);

			future.get(2, TimeUnit.SECONDS);

			assertTrue(
				completionLatch.await(
					2, TimeUnit.SECONDS
				),
				"onProgressCompletion should be called"
				+ " when the future completes"
			);
		}
	}

	@Nested
	@DisplayName("Completion percent consistency")
	class CompletionPercentConsistencyTest {

		/**
		 * Verifies that `completeExceptionally()` updates
		 * `percentCompleted()` to 100, matching the
		 * behavior of `complete()`.
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

	// -------------------------------------------------------------------------
	// Helper methods
	// -------------------------------------------------------------------------

	/**
	 * Creates a simple ProgressRecord with the given
	 * operation name and a null observer.
	 *
	 * @param operationName the name of the operation
	 * @return a new ProgressRecord instance
	 */
	@Nonnull
	private static <T> ProgressRecord<T> createRecord(
		@Nonnull String operationName
	) {
		return new ProgressRecord<>(
			operationName, null
		);
	}
}
