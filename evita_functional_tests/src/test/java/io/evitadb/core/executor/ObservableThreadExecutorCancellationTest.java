/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.core.executor;

import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.requestResponse.progress.UnrejectableTask;
import io.evitadb.core.executor.ObservableThreadExecutor.ObservableCallable;
import io.evitadb.core.executor.ObservableThreadExecutor.ObservableRunnable;
import io.evitadb.function.Functions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests verifying cancellation behavior of {@link ObservableRunnable} and {@link ObservableCallable}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class ObservableThreadExecutorCancellationTest {

	// ---- Bug 2: dangling result future on pre-start cancellation ----

	@Test
	void withoutPropagateCompletion_externalFutureHangsOnPreStartCancel() {
		final ObservableRunnable task = new ObservableRunnable(
			"test-dangling-future",
			() -> { throw new IllegalStateException("Should not execute"); },
			Functions.noOpRunnable()
		);

		final CompletableFuture<String> externalResult = new CompletableFuture<>();
		// intentionally NOT wiring completion propagation

		task.cancel();
		task.run();

		assertTrue(task.isFinished(), "Task should be finished (cancelled)");
		assertFalse(externalResult.isDone(), "External result should NOT be completed without completion propagation");
	}

	@Test
	void withPropagateCompletion_externalFutureCompletesOnPreStartCancel() {
		final ObservableRunnable task = new ObservableRunnable(
			"test-propagated-future",
			() -> { throw new IllegalStateException("Should not execute"); },
			Functions.noOpRunnable()
		);

		final CompletableFuture<String> externalResult = new CompletableFuture<>();
		task.completionStage().whenComplete((v, ex) -> {
			if (!externalResult.isDone()) {
				externalResult.cancel(false);
			}
		});

		task.cancel();
		task.run();

		assertTrue(task.isFinished(), "Task should be finished (cancelled)");
		assertTrue(externalResult.isDone(), "External result should be completed via completion propagation");
		assertTrue(externalResult.isCancelled(), "External result should be cancelled");
	}

	// ---- General cancellation behavior tests ----

	@Test
	void cancelDuringRunShouldInterruptExecutingThread() throws Exception {
		final CountDownLatch taskStarted = new CountDownLatch(1);
		final AtomicBoolean wasInterrupted = new AtomicBoolean(false);

		final ObservableRunnable task = new ObservableRunnable(
			"test-cancel-during-run",
			() -> {
				taskStarted.countDown();
				try {
					Thread.sleep(10_000);
				} catch (InterruptedException e) {
					wasInterrupted.set(true);
				}
			},
			Functions.noOpRunnable()
		);

		final Thread worker = new Thread(task, "test-worker");
		worker.start();
		assertTrue(taskStarted.await(5, TimeUnit.SECONDS), "Task should have started");

		task.cancel();

		worker.join(5_000);
		assertFalse(worker.isAlive(), "Worker thread should have finished");
		assertTrue(wasInterrupted.get(), "Worker thread should have been interrupted");
		assertTrue(task.isFinished(), "Task should be marked as finished (cancelled)");
	}

	@Test
	void cancelDuringCallShouldInterruptExecutingThread() throws Exception {
		final CountDownLatch taskStarted = new CountDownLatch(1);
		final AtomicBoolean wasInterrupted = new AtomicBoolean(false);

		final ObservableCallable<String> task = new ObservableCallable<>(
			"test-cancel-during-call",
			() -> {
				taskStarted.countDown();
				try {
					Thread.sleep(10_000);
					return "should-not-reach";
				} catch (InterruptedException e) {
					wasInterrupted.set(true);
					return "interrupted";
				}
			},
			Functions.noOpRunnable()
		);

		final Thread worker = new Thread(() -> {
			try {
				task.call();
			} catch (Exception e) {
				// expected
			}
		}, "test-worker");
		worker.start();
		assertTrue(taskStarted.await(5, TimeUnit.SECONDS), "Task should have started");

		task.cancel();

		worker.join(5_000);
		assertFalse(worker.isAlive(), "Worker thread should have finished");
		assertTrue(wasInterrupted.get(), "Worker thread should have been interrupted");
		assertTrue(task.isFinished(), "Task should be marked as finished (cancelled)");
	}

	@Test
	void cancelBeforeRunShouldSkipExecution() {
		final AtomicBoolean delegateExecuted = new AtomicBoolean(false);

		final ObservableRunnable task = new ObservableRunnable(
			"test-cancel-before-run",
			() -> delegateExecuted.set(true),
			Functions.noOpRunnable()
		);

		task.cancel();
		task.run();

		assertFalse(delegateExecuted.get(), "Delegate should not have been executed");
		assertTrue(task.isFinished(), "Task should be marked as finished (cancelled)");
	}

	@Test
	void cancelBeforeCallShouldSkipExecution() throws Exception {
		final AtomicBoolean delegateExecuted = new AtomicBoolean(false);

		final ObservableCallable<String> task = new ObservableCallable<>(
			"test-cancel-before-call",
			() -> {
				delegateExecuted.set(true);
				return "result";
			},
			Functions.noOpRunnable()
		);

		task.cancel();
		task.call();

		assertFalse(delegateExecuted.get(), "Delegate should not have been executed");
		assertTrue(task.isFinished(), "Task should be marked as finished (cancelled)");
	}

	// ---- Double-decrement bug regression tests ----

	@Test
	void cancelBeforeRunShouldDecrementQueueSizeExactlyOnce() {
		final AtomicInteger queueSize = new AtomicInteger(1);

		final ObservableRunnable task = new ObservableRunnable(
			"test-double-decrement",
			() -> {},
			queueSize::decrementAndGet
		);

		task.cancel();
		task.run();

		assertEquals(0, queueSize.get(), "queueSize should be decremented exactly once (cancel-before-run)");
	}

	@Test
	void cancelDuringRunShouldDecrementQueueSizeExactlyOnce() throws Exception {
		final AtomicInteger queueSize = new AtomicInteger(1);
		final CountDownLatch taskStarted = new CountDownLatch(1);

		final ObservableRunnable task = new ObservableRunnable(
			"test-double-decrement-during",
			() -> {
				taskStarted.countDown();
				try {
					Thread.sleep(10_000);
				} catch (InterruptedException e) {
					// expected
				}
			},
			queueSize::decrementAndGet
		);

		final Thread worker = new Thread(task, "test-worker");
		worker.start();
		assertTrue(taskStarted.await(5, TimeUnit.SECONDS), "Task should have started");

		task.cancel();
		worker.join(5_000);

		assertEquals(0, queueSize.get(), "queueSize should be decremented exactly once (cancel-during-run)");
	}

	@Test
	void cancelAfterRunCompletionShouldDecrementQueueSizeExactlyOnce() {
		final AtomicInteger queueSize = new AtomicInteger(1);

		final ObservableRunnable task = new ObservableRunnable(
			"test-double-decrement-after",
			() -> {},
			queueSize::decrementAndGet
		);

		task.run();
		task.cancel();

		assertEquals(0, queueSize.get(), "queueSize should be decremented exactly once (cancel-after-completion)");
	}

	@Test
	void cancelBeforeCallShouldDecrementQueueSizeExactlyOnce() throws Exception {
		final AtomicInteger queueSize = new AtomicInteger(1);

		final ObservableCallable<String> task = new ObservableCallable<>(
			"test-double-decrement-callable",
			() -> "result",
			queueSize::decrementAndGet
		);

		task.cancel();
		task.call();

		assertEquals(0, queueSize.get(), "queueSize should be decremented exactly once (cancel-before-call)");
	}

	@Test
	void cancelDuringCallShouldDecrementQueueSizeExactlyOnce() throws Exception {
		final AtomicInteger queueSize = new AtomicInteger(1);
		final CountDownLatch taskStarted = new CountDownLatch(1);

		final ObservableCallable<String> task = new ObservableCallable<>(
			"test-double-decrement-callable-during",
			() -> {
				taskStarted.countDown();
				try {
					Thread.sleep(10_000);
					return "should-not-reach";
				} catch (InterruptedException e) {
					return "interrupted";
				}
			},
			queueSize::decrementAndGet
		);

		final Thread worker = new Thread(() -> {
			try {
				task.call();
			} catch (Exception e) {
				// expected
			}
		}, "test-worker");
		worker.start();
		assertTrue(taskStarted.await(5, TimeUnit.SECONDS), "Task should have started");

		task.cancel();
		worker.join(5_000);

		assertEquals(0, queueSize.get(), "queueSize should be decremented exactly once (cancel-during-call)");
	}

	// ---- Queue size limit tests ----

	@Test
	void queueSizeLimitShouldRejectExcessTasks() throws Exception {
		final int queueLimit = 3;
		final ObservableThreadExecutor executor = new ObservableThreadExecutor(
			"test-queue-limit",
			new ThreadPoolOptions(1, 2, Thread.NORM_PRIORITY, queueLimit),
			false
		);
		try {
			final CountDownLatch blockTasks = new CountDownLatch(1);
			final CountDownLatch allTasksStartedOrQueued = new CountDownLatch(1);

			// Submit queueLimit + 1 tasks (the check is "> queueLimit", so queueLimit+1 tasks are accepted)
			final List<CancellableRunnable> tasks = new ArrayList<>();
			for (int i = 0; i <= queueLimit; i++) {
				final CancellableRunnable task = executor.createTask("blocking-task-" + i, () -> {
					try {
						blockTasks.await(10, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				});
				executor.execute(task);
				tasks.add(task);
			}

			// The next submission should be rejected
			assertThrows(
				RejectedExecutionException.class,
				() -> executor.execute(executor.createTask("excess-task", () -> {})),
				"Submitting beyond queue limit should throw RejectedExecutionException"
			);

			// Unblock all tasks and wait for completion
			blockTasks.countDown();
			for (CancellableRunnable task : tasks) {
				task.completionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
			}

			// After all tasks complete, we should be able to submit again
			final AtomicBoolean executed = new AtomicBoolean(false);
			final CancellableRunnable postTask = executor.createTask("post-limit-task", () -> executed.set(true));
			executor.execute(postTask);
			postTask.completionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
			assertTrue(executed.get(), "Task submitted after queue drained should execute successfully");
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void queueSizeShouldNotDriftNegativeAfterCancellations() throws Exception {
		final int queueLimit = 5;
		final ObservableThreadExecutor executor = new ObservableThreadExecutor(
			"test-queue-drift",
			new ThreadPoolOptions(1, 2, Thread.NORM_PRIORITY, queueLimit),
			false
		);
		try {
			// Submit and cancel tasks repeatedly to stress-test the queue size tracking
			for (int round = 0; round < 3; round++) {
				final CountDownLatch blockTasks = new CountDownLatch(1);
				final List<CancellableRunnable> tasks = new ArrayList<>();

				// Fill up to the limit
				for (int i = 0; i <= queueLimit; i++) {
					final CancellableRunnable task = executor.createTask("drift-task-" + round + "-" + i, () -> {
						try {
							blockTasks.await(10, TimeUnit.SECONDS);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
					});
					executor.execute(task);
					tasks.add(task);
				}

				// Cancel all tasks (this should only decrement queueSize once per task)
				for (CancellableRunnable task : tasks) {
					task.cancel();
				}
				blockTasks.countDown();

				// Wait for all tasks to finish (cancelled tasks throw CancellationException)
				for (CancellableRunnable task : tasks) {
					try {
						task.completionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
					} catch (CancellationException | ExecutionException e) {
						// expected for cancelled tasks
					}
				}
			}

			// If queueSize drifted negative, we would be able to submit infinitely many tasks
			// (negative value > positive queueLimit is always false).
			// Verify the limit is still enforced by filling the queue again.
			final CountDownLatch blockFinal = new CountDownLatch(1);
			final List<CancellableRunnable> finalTasks = new ArrayList<>();
			for (int i = 0; i <= queueLimit; i++) {
				final CancellableRunnable task = executor.createTask("final-task-" + i, () -> {
					try {
						blockFinal.await(10, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				});
				executor.execute(task);
				finalTasks.add(task);
			}

			// This must still be rejected — proves the queue size didn't drift negative
			assertThrows(
				RejectedExecutionException.class,
				() -> executor.execute(executor.createTask("must-reject", () -> {})),
				"Queue limit must still be enforced after cancellations (queueSize should not have drifted negative)"
			);

			blockFinal.countDown();
			for (CancellableRunnable task : finalTasks) {
				task.completionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
			}
		} finally {
			executor.shutdownNow();
		}
	}

	// ---- Unrejectable task bypass tests ----

	@Test
	void unrejectableTaskShouldBypassQueueLimit() throws Exception {
		final int queueLimit = 3;
		final ObservableThreadExecutor executor = new ObservableThreadExecutor(
			"test-unrejectable-bypass",
			new ThreadPoolOptions(1, 2, Thread.NORM_PRIORITY, queueLimit),
			false
		);
		try {
			final CountDownLatch blockTasks = new CountDownLatch(1);
			final List<CancellableRunnable> tasks = new ArrayList<>();

			// Fill queue to limit
			for (int i = 0; i <= queueLimit; i++) {
				final CancellableRunnable task = executor.createTask("blocking-task-" + i, () -> {
					try {
						blockTasks.await(10, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				});
				executor.execute(task);
				tasks.add(task);
			}

			// Normal task should be rejected
			assertThrows(
				RejectedExecutionException.class,
				() -> executor.execute(executor.createTask("normal-excess-task", () -> {})),
				"Normal task should be rejected when queue is full"
			);

			// Unrejectable task should NOT be rejected
			final AtomicBoolean unrejectableExecuted = new AtomicBoolean(false);
			final Runnable unrejectableRunnable = new UnrejectableTestRunnable(() -> {
				unrejectableExecuted.set(true);
			});
			assertDoesNotThrow(
				() -> executor.execute(unrejectableRunnable),
				"Unrejectable task should bypass queue limit"
			);

			// Unblock all tasks and wait
			blockTasks.countDown();
			for (CancellableRunnable task : tasks) {
				task.completionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
			}

			// Give the unrejectable task time to execute
			Thread.sleep(200);
			assertTrue(unrejectableExecuted.get(), "Unrejectable task should have been executed");
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void normalTaskShouldStillBeRejectedWhenQueueFull() throws Exception {
		final int queueLimit = 3;
		final ObservableThreadExecutor executor = new ObservableThreadExecutor(
			"test-normal-rejected",
			new ThreadPoolOptions(1, 2, Thread.NORM_PRIORITY, queueLimit),
			false
		);
		try {
			final CountDownLatch blockTasks = new CountDownLatch(1);
			final List<CancellableRunnable> tasks = new ArrayList<>();

			// Fill queue to limit
			for (int i = 0; i <= queueLimit; i++) {
				final CancellableRunnable task = executor.createTask("blocking-task-" + i, () -> {
					try {
						blockTasks.await(10, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				});
				executor.execute(task);
				tasks.add(task);
			}

			// Normal task should be rejected
			assertThrows(
				RejectedExecutionException.class,
				() -> executor.execute(executor.createTask("normal-task", () -> {})),
				"Normal task should be rejected when queue is full"
			);

			blockTasks.countDown();
			for (CancellableRunnable task : tasks) {
				task.completionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
			}
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void observableRunnableWithUnrejectableDelegateShouldBypassQueueLimit() throws Exception {
		final int queueLimit = 3;
		final ObservableThreadExecutor executor = new ObservableThreadExecutor(
			"test-observable-unrejectable",
			new ThreadPoolOptions(1, 2, Thread.NORM_PRIORITY, queueLimit),
			false
		);
		try {
			final CountDownLatch blockTasks = new CountDownLatch(1);
			final List<CancellableRunnable> tasks = new ArrayList<>();

			// Fill queue to limit
			for (int i = 0; i <= queueLimit; i++) {
				final CancellableRunnable task = executor.createTask("blocking-task-" + i, () -> {
					try {
						blockTasks.await(10, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				});
				executor.execute(task);
				tasks.add(task);
			}

			// Pre-wrapped ObservableRunnable with UnrejectableTask delegate should bypass
			final AtomicBoolean executed = new AtomicBoolean(false);
			final ObservableRunnable observableUnrejectable = new ObservableRunnable(
				new UnrejectableTestRunnable(() -> executed.set(true)),
				Functions.noOpRunnable()
			);
			assertTrue(observableUnrejectable.isUnrejectable(), "ObservableRunnable with UnrejectableTask delegate should report isUnrejectable()=true");
			assertDoesNotThrow(
				() -> executor.execute(observableUnrejectable),
				"ObservableRunnable with UnrejectableTask delegate should bypass queue limit"
			);

			blockTasks.countDown();
			for (CancellableRunnable task : tasks) {
				task.completionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
			}

			observableUnrejectable.completionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
			assertTrue(executed.get(), "ObservableRunnable with unrejectable delegate should have executed");
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void queueSizeTrackingBalancedForUnrejectableTasks() throws Exception {
		final int queueLimit = 3;
		final ObservableThreadExecutor executor = new ObservableThreadExecutor(
			"test-unrejectable-queue-tracking",
			new ThreadPoolOptions(1, 2, Thread.NORM_PRIORITY, queueLimit),
			false
		);
		try {
			final CountDownLatch blockTasks = new CountDownLatch(1);
			final List<CancellableRunnable> tasks = new ArrayList<>();

			// Fill queue to limit
			for (int i = 0; i <= queueLimit; i++) {
				final CancellableRunnable task = executor.createTask("blocking-task-" + i, () -> {
					try {
						blockTasks.await(10, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				});
				executor.execute(task);
				tasks.add(task);
			}

			// Submit unrejectable tasks beyond the limit (using raw UnrejectableTestRunnable
			// so that wrapToCancellableTask wraps them with the correct queueSizeDecrementer)
			for (int i = 0; i < 3; i++) {
				executor.execute(new UnrejectableTestRunnable(() -> {}));
			}

			// Unblock everything
			blockTasks.countDown();
			for (CancellableRunnable task : tasks) {
				task.completionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
			}
			// Give unrejectable tasks time to complete
			Thread.sleep(200);

			// After all tasks complete, the queue should be back to accepting normal tasks.
			// Verify by filling the queue again — if queueSize drifted, this would fail.
			final CountDownLatch blockFinal = new CountDownLatch(1);
			final List<CancellableRunnable> finalTasks = new ArrayList<>();
			for (int i = 0; i <= queueLimit; i++) {
				final CancellableRunnable task = executor.createTask("final-task-" + i, () -> {
					try {
						blockFinal.await(10, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				});
				executor.execute(task);
				finalTasks.add(task);
			}

			// This should still be rejected — proves queue tracking is balanced
			assertThrows(
				RejectedExecutionException.class,
				() -> executor.execute(executor.createTask("must-reject", () -> {})),
				"Queue limit must still be enforced after unrejectable tasks complete"
			);

			blockFinal.countDown();
			for (CancellableRunnable task : finalTasks) {
				task.completionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
			}
		} finally {
			executor.shutdownNow();
		}
	}

	/**
	 * A test helper: a Runnable that also implements UnrejectableTask.
	 */
	private record UnrejectableTestRunnable(@javax.annotation.Nonnull Runnable delegate) implements Runnable, UnrejectableTask {
		@Override
		public void run() {
			delegate.run();
		}
	}
}
