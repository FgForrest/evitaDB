/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.store.spi;

import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprehensive test suite for {@link ProgressingFuture} contract verification.
 *
 * This test class validates all aspects of the ProgressingFuture functionality including:
 * - Basic future creation and completion
 * - Progress tracking and reporting
 * - Nested future composition and aggregation
 * - Exception handling in both simple and nested scenarios
 * - Manual completion and exceptional completion
 * - Thread safety and synchronization
 *
 * All tests are designed to avoid timing issues and race conditions by using proper
 * synchronization mechanisms like CountDownLatch instead of Thread.sleep().
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
class ProgressingFutureTest {

	private Executor executor;

	@BeforeEach
	void setUp() {
		this.executor = Executors.newFixedThreadPool(4);
	}

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

	/**
	 * Verifies that a simple ProgressingFuture can be created and completed successfully
	 * without a progress consumer callback.
	 */
	@Test
	@DisplayName("Should create and complete simple ProgressingFuture without progress consumer")
	void shouldCreateSimpleProgressingFutureWithoutProgressConsumer() throws ExecutionException, InterruptedException, TimeoutException {
		final ProgressingFuture<String> future = new ProgressingFuture<>(
			10,
			theFuture -> "test result"
		);
		future.execute(this.executor);

		assertEquals(11, future.getTotalSteps());
		assertEquals("test result", future.get(1, TimeUnit.SECONDS));
	}

	/**
	 * Verifies that a simple ProgressingFuture can be created and completed successfully
	 * with a progress consumer callback that receives progress updates.
	 */
	@Test
	@DisplayName("Should create and complete simple ProgressingFuture with progress consumer")
	void shouldCreateSimpleProgressingFutureWithProgressConsumer() throws ExecutionException, InterruptedException, TimeoutException {
		final List<String> progressUpdates = new ArrayList<>();
		final ProgressingFuture<String> future = new ProgressingFuture<>(
			10,
			theFuture -> "test result"
		);

		future.setProgressConsumer((stepsDone, totalSteps) -> progressUpdates.add(stepsDone + "/" + totalSteps));
		future.execute(this.executor);

		assertEquals(11, future.getTotalSteps());
		assertEquals("test result", future.get(1, TimeUnit.SECONDS));

		// Should have at least the final progress update when completing
		assertFalse(progressUpdates.isEmpty());
		assertTrue(progressUpdates.contains("11/11"));
	}

	/**
	 * Verifies that progress can be tracked manually by calling updateProgress()
	 * from within the task execution and that progress updates are properly reported.
	 * Uses CountDownLatch to ensure proper synchronization and avoid race conditions.
	 */
	@Test
	@DisplayName("Should track progress manually with updateProgress() calls")
	void shouldTrackProgressManually() throws ExecutionException, InterruptedException, TimeoutException {
		final List<String> progressUpdates = new ArrayList<>();
		final CountDownLatch taskStartedLatch = new CountDownLatch(1);
		final CountDownLatch progressUpdatesLatch = new CountDownLatch(1);
		final AtomicReference<ProgressingFuture<String>> futureRef = new AtomicReference<>();

		final ProgressingFuture<String> future = new ProgressingFuture<>(
			5,
			theFuture -> {
				// Signal that the task has started
				taskStartedLatch.countDown();
				try {
					// Wait for the future reference to be set
					progressUpdatesLatch.await(1, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				final ProgressingFuture<String> currentFuture = futureRef.get();
				if (currentFuture != null) {
					currentFuture.updateProgress(1);
					currentFuture.updateProgress(3);
					currentFuture.updateProgress(5);
				}
				return "completed";
			}
		);

		future.setProgressConsumer(
			(stepsDone, totalSteps) -> progressUpdates.add(stepsDone + "/" + totalSteps)
		);
		future.execute(this.executor);

		// Wait for task to start, then set the reference and signal to proceed
		assertTrue(taskStartedLatch.await(1, TimeUnit.SECONDS), "Task should start within 1 second");
		futureRef.set(future);
		progressUpdatesLatch.countDown();

		assertEquals("completed", future.get(2, TimeUnit.SECONDS));

		// Should contain manual progress updates
		assertTrue(progressUpdates.contains("1/6"));
		assertTrue(progressUpdates.contains("3/6"));
		assertTrue(progressUpdates.contains("5/6"));
		// Final completion should reach 100%
		assertTrue(progressUpdates.contains("6/6"));
	}

	/**
	 * Verifies that nested ProgressingFutures can be created and composed together,
	 * with proper aggregation of total steps and results collection.
	 */
	@Test
	@DisplayName("Should create and compose nested ProgressingFutures")
	void shouldCreateNestedProgressingFuture() throws ExecutionException, InterruptedException, TimeoutException {
		final Collection<ProgressingFuture<String>> nestedFutures = Arrays.asList(
			new ProgressingFuture<>(
				5,
				theFuture -> "result1"
			),
			new ProgressingFuture<>(
				3,
				theFuture -> "result2"
			)
		);

		final BiFunction<ProgressingFuture<List<String>>, Collection<String>, List<String>> resultMapper =
			(progress, results) -> new ArrayList<>(results);

		final ProgressingFuture<List<String>> future = new ProgressingFuture<>(
			2, // additional steps for this level
			nestedFutures,
			resultMapper
		);
		future.execute(this.executor);

		assertEquals(13, future.getTotalSteps()); // 2 + 1 + 5 + 1 + 3 + 1
		final List<String> result = future.get(2, TimeUnit.SECONDS);

		assertEquals(2, result.size());
		assertTrue(result.contains("result1"));
		assertTrue(result.contains("result2"));

		// Verify the future completed successfully
		assertTrue(future.isDone());
		assertFalse(future.isCompletedExceptionally());
	}

	/**
	 * Verifies that progress from multiple nested ProgressingFutures is properly aggregated
	 * and that the total steps calculation includes all nested future steps.
	 */
	@Test
	@DisplayName("Should aggregate progress from multiple nested ProgressingFutures")
	void shouldAggregateProgressFromNestedFutures() throws ExecutionException, InterruptedException, TimeoutException {
		final Collection<ProgressingFuture<String>> nestedFactories = Arrays.asList(
			new ProgressingFuture<>(
				4,
				theFuture -> "nested1"
			),
			new ProgressingFuture<>(
				6,
				theFuture -> "nested2"
			)
		);

		final BiFunction<ProgressingFuture<List<String>>, Collection<String>, List<String>> resultMapper =
			(progress, results) -> new ArrayList<>(results);

		final ProgressingFuture<List<String>> future = new ProgressingFuture<>(
			0, // no additional steps
			nestedFactories,
			resultMapper
		);
		future.execute(this.executor);

		assertEquals(13, future.getTotalSteps()); // 0 + 1 + 4 + 1 + 6 + 1
		final List<String> result = future.get(2, TimeUnit.SECONDS);

		assertEquals(2, result.size());
		assertTrue(result.contains("nested1"));
		assertTrue(result.contains("nested2"));

		// Verify the future completed successfully
		assertTrue(future.isDone());
		assertFalse(future.isCompletedExceptionally());
	}

	/**
	 * Verifies that exceptions thrown during simple ProgressingFuture execution
	 * are properly propagated and wrapped in ExecutionException.
	 */
	@Test
	@DisplayName("Should handle exceptions in simple ProgressingFuture execution")
	void shouldHandleExceptionInSimpleFuture() {
		final RuntimeException testException = new RuntimeException("Test exception");
		final ProgressingFuture<String> future = new ProgressingFuture<>(
			5,
			theFuture -> {
				throw testException;
			}
		);
		future.execute(this.executor);

		final ExecutionException exception = assertThrows(
			ExecutionException.class,
			() -> future.get(1, TimeUnit.SECONDS)
		);

		assertEquals(testException, exception.getCause());
	}

	/**
	 * Verifies that exceptions thrown during nested ProgressingFuture execution
	 * are properly propagated up through the composition chain.
	 */
	@Test
	@DisplayName("Should handle exceptions in nested ProgressingFuture execution")
	void shouldHandleExceptionInNestedFuture() {
		final RuntimeException testException = new RuntimeException("Nested exception");

		final Collection<ProgressingFuture<String>> nestedFutures = List.of(
			new ProgressingFuture<>(
				3,
				theFuture -> {
					throw testException;
				}
			)
		);

		final ProgressingFuture<List<String>> future = new ProgressingFuture<>(
			2,
			nestedFutures,
			(progress, results) -> new ArrayList<>(results)
		);

		future.execute(this.executor);

		final ExecutionException exception = assertThrows(
			ExecutionException.class,
			() -> future.get(2, TimeUnit.SECONDS)
		);

		assertEquals(testException, exception.getCause());
	}

	/**
	 * Verifies that progress is properly set to 100% even when an exception occurs
	 * during ProgressingFuture execution, ensuring consistent progress reporting.
	 */
	@Test
	@DisplayName("Should complete progress to 100% even when exception occurs")
	void shouldCompleteProgressOnException() throws InterruptedException {
		final List<String> progressUpdates = new ArrayList<>();
		final RuntimeException testException = new RuntimeException("Test exception");

		final ProgressingFuture<String> future = new ProgressingFuture<>(
			5,
			theFuture -> {
				throw testException;
			}
		);
		future.setProgressConsumer((stepsDone, totalSteps) -> progressUpdates.add(stepsDone + "/" + totalSteps));
		future.execute(this.executor);

		try {
			future.get(100, TimeUnit.SECONDS);
		} catch (ExecutionException | TimeoutException e) {
			// Expected
		}

		// Progress should be set to 100% even on exception
		assertTrue(progressUpdates.contains("6/6"));
	}

	/**
	 * Verifies that ProgressingFuture handles empty nested futures collection gracefully
	 * and completes successfully with an empty result.
	 */
	@Test
	@DisplayName("Should handle empty nested futures collection gracefully")
	void shouldHandleEmptyNestedFuturesCollection() throws ExecutionException, InterruptedException, TimeoutException {
		final ProgressingFuture<Collection<Object>> future = new ProgressingFuture<>(
			5,
			Collections.emptyList(), // empty collection
			(progress, results) -> results
		);
		future.execute(this.executor);

		assertEquals(6, future.getTotalSteps());
		final Collection<Object> result = future.get(1, TimeUnit.SECONDS);
		assertTrue(result.isEmpty());
	}

	/**
	 * Verifies that ProgressingFuture handles null progress consumer gracefully
	 * without throwing exceptions during execution or manual progress updates.
	 */
	@Test
	@DisplayName("Should handle null progress consumer without errors")
	void shouldHandleNullProgressConsumer() throws ExecutionException, InterruptedException, TimeoutException {
		final ProgressingFuture<String> future = new ProgressingFuture<>(
			10,
			theFuture -> "result"
		);
		future.execute(this.executor);

		// Should not throw exception and should complete normally
		assertEquals("result", future.get(1, TimeUnit.SECONDS));

		// Manual progress update should also not throw
		future.updateProgress(5);
	}

	/**
	 * Verifies that progress updates work correctly with multiple nested ProgressingFutures
	 * and that the total steps calculation and result aggregation function properly.
	 */
	@Test
	@DisplayName("Should update progress correctly with multiple nested futures")
	void shouldUpdateProgressCorrectlyWithMultipleNestedFutures() throws ExecutionException, InterruptedException, TimeoutException {
		final Collection<ProgressingFuture<Integer>> nestedFutures = Arrays.asList(
			new ProgressingFuture<>(
				10,
				theFuture -> 1
			),
			new ProgressingFuture<>(
				20,
				theFuture -> 2
			),
			new ProgressingFuture<>(
				30,
				theFuture -> 3
			)
		);

		final ProgressingFuture<Integer> future = new ProgressingFuture<>(
			5, // additional steps
			nestedFutures,
			(progress, results) -> results.stream().mapToInt(Integer::intValue).sum()
		);
		future.execute(this.executor);

		assertEquals(69, future.getTotalSteps()); // 5 + 1 + 10 + 1 + 20 + 1 + 30 + 1
		assertEquals(6, future.get(3, TimeUnit.SECONDS)); // 1 + 2 + 3

		// Verify the future completed successfully
		assertTrue(future.isDone());
		assertFalse(future.isCompletedExceptionally());
	}

	/**
	 * Verifies that a ProgressingFuture can be manually completed before its natural completion.
	 * Uses CountDownLatch for proper synchronization instead of Thread.sleep() to avoid timing issues.
	 */
	@Test
	@DisplayName("Should handle manual completion of long-running task")
	void shouldHandleManualCompletion() throws ExecutionException, InterruptedException, TimeoutException {
		final List<String> progressUpdates = new ArrayList<>();
		final CountDownLatch taskStartedLatch = new CountDownLatch(1);
		final CountDownLatch completionLatch = new CountDownLatch(1);

		final ProgressingFuture<String> future = new ProgressingFuture<>(
			10,
			theFuture -> {
				// Signal that the task has started
				taskStartedLatch.countDown();
				try {
					// Wait indefinitely until manually completed
					completionLatch.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return "never";
			}
		);

		future.setProgressConsumer((stepsDone, totalSteps) -> progressUpdates.add(stepsDone + "/" + totalSteps));
		future.execute(this.executor);

		// Wait for the task to start before manually completing
		assertTrue(taskStartedLatch.await(1, TimeUnit.SECONDS), "Task should start within 1 second");

		// Manually complete the future
		future.complete("manually completed");

		assertEquals("manually completed", future.get(100, TimeUnit.MILLISECONDS));
		assertTrue(progressUpdates.contains("11/11"));
	}

	/**
	 * Verifies that a ProgressingFuture can be manually completed exceptionally before its natural completion.
	 * Uses CountDownLatch for proper synchronization instead of Thread.sleep() to avoid timing issues.
	 */
	@Test
	@DisplayName("Should handle manual exceptional completion of long-running task")
	void shouldHandleManualExceptionalCompletion() throws InterruptedException {
		final List<String> progressUpdates = new ArrayList<>();
		final RuntimeException testException = new RuntimeException("Manual exception");
		final CountDownLatch taskStartedLatch = new CountDownLatch(1);
		final CountDownLatch completionLatch = new CountDownLatch(1);

		final ProgressingFuture<String> future = new ProgressingFuture<>(
			10,
			theFuture -> {
				// Signal that the task has started
				taskStartedLatch.countDown();
				try {
					// Wait indefinitely until manually completed
					completionLatch.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return "never";
			}
		);

		future.setProgressConsumer((stepsDone, totalSteps) -> progressUpdates.add(stepsDone + "/" + totalSteps));
		future.execute(this.executor);

		// Wait for the task to start before manually completing exceptionally
		assertTrue(taskStartedLatch.await(1, TimeUnit.SECONDS), "Task should start within 1 second");

		// Manually complete the future exceptionally
		future.completeExceptionally(testException);

		final ExecutionException exception = assertThrows(
			ExecutionException.class,
			() -> future.get(100, TimeUnit.MILLISECONDS)
		);

		assertEquals(testException, exception.getCause());
		assertTrue(progressUpdates.contains("11/11"));
	}

	/**
	 * Verifies that the new constructor with initializer works correctly in the success path
	 * and that the onFailure handler is not called when everything succeeds.
	 */
	@Test
	@DisplayName("Should execute successfully with initializer constructor")
	void shouldExecuteSuccessfullyWithInitializerConstructor() throws ExecutionException, InterruptedException, TimeoutException {
		final List<String> onFailureCalls = new ArrayList<>();
		final List<String> progressUpdates = new ArrayList<>();

		final ProgressingFuture<String> future = new ProgressingFuture<>(
			2, // actionSteps
			() -> "initial data", // initializer
			initResult -> List.of( // nestedFutureFactory
				new ProgressingFuture<>(3, f -> "nested1"),
				new ProgressingFuture<>(4, f -> "nested2")
			),
			(progressFuture, initResult, nestedResults) -> // resultMapper
				initResult + ":" + nestedResults.stream().map(Object::toString).reduce((a, b) -> a + "," + b).orElse(""),
			(initResult, throwable) -> // onFailure
				onFailureCalls.add("Failed with init: " + initResult + ", error: " + throwable.getMessage())
		);

		future.setProgressConsumer((stepsDone, totalSteps) -> progressUpdates.add(stepsDone + "/" + totalSteps));
		future.execute(this.executor);

		final String result = future.get(2, TimeUnit.SECONDS);
		assertEquals("initial data:nested1,nested2", result);
		assertTrue(onFailureCalls.isEmpty(), "onFailure should not be called on success");
		assertTrue(progressUpdates.contains("12/12")); // 2 + 1 + 3 + 1 + 4 + 1 = 12
	}

	/**
	 * Verifies that the onFailure handler is called when the initializer throws an exception.
	 */
	@Test
	@DisplayName("Should call onFailure handler when initializer throws exception")
	void shouldCallOnFailureWhenInitializerThrowsException() {
		final List<String> onFailureCalls = new ArrayList<>();
		final RuntimeException testException = new RuntimeException("Initializer failed");

		final ProgressingFuture<String> future = new ProgressingFuture<>(
			2, // actionSteps
			() -> { // initializer that throws
				throw testException;
			},
			initResult -> List.of( // nestedFutureFactory
				new ProgressingFuture<>(3, f -> "nested1")
			),
			(progressFuture, initResult, nestedResults) -> // resultMapper
				"should not reach here",
			(initResult, throwable) -> // onFailure
				onFailureCalls.add("Failed with init: " + initResult + ", error: " + throwable.getMessage())
		);

		future.execute(this.executor);

		final ExecutionException exception = assertThrows(
			ExecutionException.class,
			() -> future.get(1, TimeUnit.SECONDS)
		);

		assertEquals(testException, exception.getCause());
		assertEquals(1, onFailureCalls.size());
		assertEquals("Failed with init: null, error: Initializer failed", onFailureCalls.get(0));
	}

	/**
	 * Verifies that the onFailure handler is called when the nestedFutureFactory throws an exception.
	 */
	@Test
	@DisplayName("Should call onFailure handler when nestedFutureFactory throws exception")
	void shouldCallOnFailureWhenNestedFutureFactoryThrowsException() {
		final List<String> onFailureCalls = new ArrayList<>();
		final RuntimeException testException = new RuntimeException("NestedFutureFactory failed");

		final ProgressingFuture<String> future = new ProgressingFuture<>(
			2, // actionSteps
			() -> "initial data", // initializer
			initResult -> { // nestedFutureFactory that throws
				throw testException;
			},
			(progressFuture, initResult, nestedResults) -> // resultMapper
				"should not reach here",
			(initResult, throwable) -> // onFailure
				onFailureCalls.add("Failed with init: " + initResult + ", error: " + throwable.getMessage())
		);

		future.execute(this.executor);

		final ExecutionException exception = assertThrows(
			ExecutionException.class,
			() -> future.get(1, TimeUnit.SECONDS)
		);

		assertEquals(testException, exception.getCause());
		assertEquals(1, onFailureCalls.size());
		assertEquals("Failed with init: null, error: NestedFutureFactory failed", onFailureCalls.get(0));
	}

	/**
	 * Verifies that the onFailure handler is called when one of the nested futures fails.
	 */
	@Test
	@DisplayName("Should call onFailure handler when nested future fails")
	void shouldCallOnFailureWhenNestedFutureFails() {
		final List<String> onFailureCalls = new ArrayList<>();
		final RuntimeException testException = new RuntimeException("Nested future failed");

		final ProgressingFuture<String> future = new ProgressingFuture<>(
			2, // actionSteps
			() -> "initial data", // initializer
			initResult -> List.of( // nestedFutureFactory
				new ProgressingFuture<>(3, f -> "nested1"),
				new ProgressingFuture<>(4, f -> {
					throw testException;
				})
			),
			(progressFuture, initResult, nestedResults) -> // resultMapper
				"should not reach here",
			(initResult, throwable) -> // onFailure
				onFailureCalls.add("Failed with init: " + initResult + ", error: " + throwable.getMessage())
		);

		future.execute(this.executor);

		final ExecutionException exception = assertThrows(
			ExecutionException.class,
			() -> future.get(2, TimeUnit.SECONDS)
		);

		assertEquals(testException, exception.getCause());
		assertEquals(1, onFailureCalls.size());
		assertEquals("Failed with init: initial data, error: java.lang.RuntimeException: Nested future failed", onFailureCalls.get(0));
	}

	/**
	 * Verifies that the onFailure handler is called when the resultMapper throws an exception.
	 */
	@Test
	@DisplayName("Should call onFailure handler when resultMapper throws exception")
	void shouldCallOnFailureWhenResultMapperThrowsException() {
		final List<String> onFailureCalls = new ArrayList<>();
		final RuntimeException testException = new RuntimeException("ResultMapper failed");

		final ProgressingFuture<String> future = new ProgressingFuture<>(
			2, // actionSteps
			() -> "initial data", // initializer
			initResult -> List.of( // nestedFutureFactory
				new ProgressingFuture<>(3, f -> "nested1"),
				new ProgressingFuture<>(4, f -> "nested2")
			),
			(progressFuture, initResult, nestedResults) -> { // resultMapper that throws
				throw testException;
			},
			(initResult, throwable) -> // onFailure
				onFailureCalls.add("Failed with init: " + initResult + ", error: " + throwable.getMessage())
		);

		future.execute(this.executor);

		final ExecutionException exception = assertThrows(
			ExecutionException.class,
			() -> future.get(2, TimeUnit.SECONDS)
		);

		assertEquals(testException, exception.getCause());
		assertEquals(1, onFailureCalls.size());
		assertEquals("Failed with init: initial data, error: java.lang.RuntimeException: ResultMapper failed", onFailureCalls.get(0));
	}

	/**
	 * Verifies that the onFailure handler is called when the future is manually completed exceptionally.
	 */
	@Test
	@DisplayName("Should call onFailure handler when manually completed exceptionally")
	void shouldCallOnFailureWhenManuallyCompletedExceptionally() throws InterruptedException {
		final List<String> onFailureCalls = new ArrayList<>();
		final RuntimeException testException = new RuntimeException("Manual exception");
		final CountDownLatch taskStartedLatch = new CountDownLatch(1);
		final CountDownLatch completionLatch = new CountDownLatch(1);

		final ProgressingFuture<String> future = new ProgressingFuture<>(
			2, // actionSteps
			() -> "initial data", // initializer
			initResult -> List.of( // nestedFutureFactory
				new ProgressingFuture<>(3, f -> {
					taskStartedLatch.countDown();
					try {
						completionLatch.await();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					return "nested1";
				})
			),
			(progressFuture, initResult, nestedResults) -> // resultMapper
				"should not reach here",
			(initResult, throwable) -> // onFailure
				onFailureCalls.add("Failed with init: " + initResult + ", error: " + throwable.getMessage())
		);

		future.execute(this.executor);

		// Wait for nested future to start
		assertTrue(taskStartedLatch.await(1, TimeUnit.SECONDS), "Nested task should start within 1 second");

		// Manually complete exceptionally
		future.completeExceptionally(testException);

		final ExecutionException exception = assertThrows(
			ExecutionException.class,
			() -> future.get(100, TimeUnit.MILLISECONDS)
		);

		// When manually completing exceptionally while nested futures are running,
		// the nested futures get cancelled, which can result in CancellationException
		assertTrue(
			exception.getCause() instanceof java.util.concurrent.CancellationException ||
			exception.getCause() == testException,
			"Exception cause should be CancellationException or the original exception"
		);
		// onFailure might be called multiple times when manually completing exceptionally
		// while nested futures are running (once for manual completion, once for nested future cancellation)
		assertTrue(onFailureCalls.size() >= 1, "onFailure should be called at least once");
		assertTrue(
			onFailureCalls.stream().anyMatch(call -> call.contains("Manual exception")),
			"At least one onFailure call should contain the manual exception"
		);
	}

	/**
	 * Verifies that the onFailure handler is called with correct parameters when multiple nested futures fail.
	 */
	@Test
	@DisplayName("Should call onFailure handler when multiple nested futures fail")
	void shouldCallOnFailureWhenMultipleNestedFuturesFail() {
		final List<String> onFailureCalls = new ArrayList<>();
		final RuntimeException testException1 = new RuntimeException("First nested future failed");
		final RuntimeException testException2 = new RuntimeException("Second nested future failed");

		final ProgressingFuture<String> future = new ProgressingFuture<>(
			2, // actionSteps
			() -> "initial data", // initializer
			initResult -> List.of( // nestedFutureFactory
				new ProgressingFuture<>(3, f -> {
					throw testException1;
				}),
				new ProgressingFuture<>(4, f -> {
					throw testException2;
				})
			),
			(progressFuture, initResult, nestedResults) -> // resultMapper
				"should not reach here",
			(initResult, throwable) -> // onFailure
				onFailureCalls.add("Failed with init: " + initResult + ", error: " + throwable.getMessage())
		);

		future.execute(this.executor);

		final ExecutionException exception = assertThrows(
			ExecutionException.class,
			() -> future.get(2, TimeUnit.SECONDS)
		);

		// One of the exceptions should be the cause (CompletableFuture.allOf behavior)
		assertTrue(
			exception.getCause() == testException1 || exception.getCause() == testException2,
			"Exception cause should be one of the nested future exceptions"
		);
		assertEquals(1, onFailureCalls.size());
		assertTrue(
			onFailureCalls.get(0).contains("initial data"),
			"onFailure should be called with correct init result"
		);
	}

	/**
	 * Verifies that the onFailure handler is called with null initResult when initializer fails
	 * and that progress is still updated to 100% on failure.
	 */
	@Test
	@DisplayName("Should call onFailure handler with null initResult when initializer fails and update progress")
	void shouldCallOnFailureWithNullInitResultWhenInitializerFailsAndUpdateProgress() throws InterruptedException {
		final List<String> onFailureCalls = new ArrayList<>();
		final List<String> progressUpdates = new ArrayList<>();
		final RuntimeException testException = new RuntimeException("Initializer failed");

		final ProgressingFuture<String> future = new ProgressingFuture<>(
			5, // actionSteps
			() -> { // initializer that throws
				throw testException;
			},
			initResult -> List.of( // nestedFutureFactory
				new ProgressingFuture<>(3, f -> "nested1")
			),
			(progressFuture, initResult, nestedResults) -> // resultMapper
				"should not reach here",
			(initResult, throwable) -> // onFailure
				onFailureCalls.add("Failed with init: " + initResult + ", error: " + throwable.getMessage())
		);

		future.setProgressConsumer((stepsDone, totalSteps) -> progressUpdates.add(stepsDone + "/" + totalSteps));
		future.execute(this.executor);

		try {
			future.get(1, TimeUnit.SECONDS);
		} catch (ExecutionException | TimeoutException e) {
			// Expected
		}

		assertEquals(1, onFailureCalls.size());
		assertEquals("Failed with init: null, error: Initializer failed", onFailureCalls.get(0));
		// Progress should be updated to completion even on failure
		assertTrue(progressUpdates.contains("6/6")); // 5 + 1 = 6
	}
}
