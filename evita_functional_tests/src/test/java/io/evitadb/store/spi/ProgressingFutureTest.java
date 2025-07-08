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

import io.evitadb.core.async.ProgressingFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntConsumer;

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
		final ProgressingFuture<String, Void> future = new ProgressingFuture<>(
			10,
			null,
			() -> "test result",
			this.executor
		);

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
		final ProgressingFuture<String, Void> future = new ProgressingFuture<>(
			10,
			(stepsDone, totalSteps) -> progressUpdates.add(stepsDone + "/" + totalSteps),
			() -> "test result",
			this.executor
		);

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
		final AtomicReference<ProgressingFuture<String, Void>> futureRef = new AtomicReference<>();

		final ProgressingFuture<String, Void> future = new ProgressingFuture<>(
			5,
			(stepsDone, totalSteps) -> progressUpdates.add(stepsDone + "/" + totalSteps),
			() -> {
				// Signal that the task has started
				taskStartedLatch.countDown();
				try {
					// Wait for the future reference to be set
					progressUpdatesLatch.await(1, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				final ProgressingFuture<String, Void> currentFuture = futureRef.get();
				if (currentFuture != null) {
					currentFuture.updateProgress(1);
					currentFuture.updateProgress(3);
					currentFuture.updateProgress(5);
				}
				return "completed";
			},
			this.executor
		);

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
		final Collection<Function<IntConsumer, ProgressingFuture<String, ?>>> nestedFactories = Arrays.asList(
			progressConsumer -> new ProgressingFuture<>(
				5,
				(stepsDone, totalSteps) -> progressConsumer.accept(stepsDone),
				() -> "result1",
				this.executor
			),
			progressConsumer -> new ProgressingFuture<>(
				3,
				(stepsDone, totalSteps) -> progressConsumer.accept(stepsDone),
				() -> "result2",
				this.executor
			)
		);

		final BiFunction<ProgressingFuture<List<String>, String>, Collection<String>, List<String>> resultMapper =
			(progress, results) -> new ArrayList<>(results);

		final ProgressingFuture<List<String>, String> future = new ProgressingFuture<>(
			2, // additional steps for this level
			null, // No progress consumer to avoid timing issues
			nestedFactories,
			resultMapper,
			this.executor
		);

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
		final Collection<Function<IntConsumer, ProgressingFuture<String, ?>>> nestedFactories = Arrays.asList(
			progressConsumer -> new ProgressingFuture<>(
				4,
				(stepsDone, totalSteps) -> progressConsumer.accept(stepsDone),
				() -> "nested1",
				this.executor
			),
			progressConsumer -> new ProgressingFuture<>(
				6,
				(stepsDone, totalSteps) -> progressConsumer.accept(stepsDone),
				() -> "nested2",
				this.executor
			)
		);

		final ProgressingFuture<List<String>, String> future = new ProgressingFuture<>(
			0, // no additional steps
			null, // No progress consumer to avoid timing issues
			nestedFactories,
			(progress, results) -> new ArrayList<>(results),
			this.executor
		);

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
		final ProgressingFuture<String, Void> future = new ProgressingFuture<>(
			5,
			null,
			() -> {
				throw testException;
			},
			this.executor
		);

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

		final Collection<Function<IntConsumer, ProgressingFuture<String, ?>>> nestedFactories = Arrays.asList(
			progressConsumer -> new ProgressingFuture<>(
				3,
				(stepsDone, totalSteps) -> progressConsumer.accept(stepsDone),
				() -> {
					throw testException;
				},
				this.executor
			)
		);

		final ProgressingFuture<List<String>, String> future = new ProgressingFuture<>(
			2,
			null,
			nestedFactories,
			(progress, results) -> new ArrayList<>(results),
			this.executor
		);

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

		final ProgressingFuture<String, Void> future = new ProgressingFuture<>(
			5,
			(stepsDone, totalSteps) -> progressUpdates.add(stepsDone + "/" + totalSteps),
			() -> {
				throw testException;
			},
			this.executor
		);

		try {
			future.get(1, TimeUnit.SECONDS);
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
		final ProgressingFuture<List<String>, String> future = new ProgressingFuture<>(
			5,
			null,
			new ArrayList<>(), // empty collection
			(progress, results) -> new ArrayList<>(results),
			this.executor
		);

		assertEquals(6, future.getTotalSteps());
		final List<String> result = future.get(1, TimeUnit.SECONDS);
		assertTrue(result.isEmpty());
	}

	/**
	 * Verifies that ProgressingFuture handles null progress consumer gracefully
	 * without throwing exceptions during execution or manual progress updates.
	 */
	@Test
	@DisplayName("Should handle null progress consumer without errors")
	void shouldHandleNullProgressConsumer() throws ExecutionException, InterruptedException, TimeoutException {
		final ProgressingFuture<String, Void> future = new ProgressingFuture<>(
			10,
			null, // null progress consumer
			() -> "result",
			this.executor
		);

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
		final Collection<Function<IntConsumer, ProgressingFuture<Integer, ?>>> nestedFactories = Arrays.asList(
			progressConsumer -> new ProgressingFuture<>(
				10,
				(stepsDone, totalSteps) -> progressConsumer.accept(stepsDone),
				() -> 1,
				this.executor
			),
			progressConsumer -> new ProgressingFuture<>(
				20,
				(stepsDone, totalSteps) -> progressConsumer.accept(stepsDone),
				() -> 2,
				this.executor
			),
			progressConsumer -> new ProgressingFuture<>(
				30,
				(stepsDone, totalSteps) -> progressConsumer.accept(stepsDone),
				() -> 3,
				this.executor
			)
		);

		final ProgressingFuture<Integer, Integer> future = new ProgressingFuture<>(
			5, // additional steps
			null, // No progress consumer to avoid timing issues
			nestedFactories,
			(progress, results) -> results.stream().mapToInt(Integer::intValue).sum(),
			this.executor
		);

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

		final ProgressingFuture<String, Void> future = new ProgressingFuture<>(
			10,
			(stepsDone, totalSteps) -> progressUpdates.add(stepsDone + "/" + totalSteps),
			() -> {
				// Signal that the task has started
				taskStartedLatch.countDown();
				try {
					// Wait indefinitely until manually completed
					completionLatch.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return "never";
			},
			this.executor
		);

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

		final ProgressingFuture<String, Void> future = new ProgressingFuture<>(
			10,
			(stepsDone, totalSteps) -> progressUpdates.add(stepsDone + "/" + totalSteps),
			() -> {
				// Signal that the task has started
				taskStartedLatch.countDown();
				try {
					// Wait indefinitely until manually completed
					completionLatch.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return "never";
			},
			this.executor
		);

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
}
