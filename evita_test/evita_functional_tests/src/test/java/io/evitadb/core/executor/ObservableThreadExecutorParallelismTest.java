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
import io.evitadb.core.metric.event.system.RequestThreadPoolStatisticsEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TASK;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests verifying the relationship between the configured {@link ThreadPoolOptions} thread counts and the
 * actual concurrency / rejection behavior of {@link ObservableThreadExecutor}.
 *
 * These tests are the regression guard for the production incident in which the `request` pool threw
 * "Evita executor queue full. Please add more threads to the `request` pool." while the thread-pool gauges
 * (running / active workers) never approached the configured maximum. The root cause was the former
 * `ForkJoinPool` backend, whose effective parallelism was clamped to the CPU count, so the configured
 * `maxThreadCount` was never delivered for the predominantly *blocking* request workload — starving the
 * pool of the concurrency it was configured for.
 *
 * The pool is now a {@link java.util.concurrent.ThreadPoolExecutor} fronted by a *threads-first*
 * `TaskQueue`. These tests pin down the corrected, CPU-count-independent behaviour:
 *
 * 1. **Grow to maximum** — for a blocking workload the pool grows all the way to `maxThreadCount` worker
 *    threads *before* it starts queueing, even when `maxThreadCount` exceeds the available CPU count. The
 *    clamp that caused the incident can no longer recur.
 * 2. **Bounded rejection** — rejection occurs only once all `maxThreadCount` threads are busy *and* the
 *    `queueSize`-bounded backlog is full; admission is therefore `maxThreadCount + queueSize` tasks.
 * 3. **Unrejectable bypass** — tasks implementing {@link UnrejectableTask} are force-enqueued and never
 *    rejected, even past the backlog limit.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Tag(ENGINE)
@Tag(TASK)
@DisplayName("ObservableThreadExecutor concurrency & rejection semantics")
class ObservableThreadExecutorParallelismTest {

	/**
	 * Generous upper bound for every assertion-gating wait. The suite runs heavily parallelized on a
	 * CPU-saturated host, so a tight cap would produce starvation-induced false failures; a large cap costs
	 * nothing on the passing path because each `await` returns the instant its signal fires.
	 */
	private static final long AWAIT_SECONDS = 30L;

	/**
	 * Submits a blocking task to the executor. The task signals its entry on `started`, then parks on
	 * `release` until the test unblocks it. `concurrent` tracks the live count, `peak` records the maximum
	 * observed concurrency.
	 */
	private static void submitBlockingTask(
		ObservableThreadExecutor executor,
		String name,
		AtomicInteger concurrent,
		AtomicInteger peak,
		CountDownLatch started,
		CountDownLatch release
	) {
		executor.execute(executor.createTask(name, () -> {
			final int now = concurrent.incrementAndGet();
			peak.accumulateAndGet(now, Math::max);
			started.countDown();
			try {
				release.await(AWAIT_SECONDS, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				concurrent.decrementAndGet();
			}
		}));
	}

	@Nested
	@DisplayName("Effective parallelism")
	class GrowToMaximum {

		@Test
		@DisplayName("grows to maxThreadCount for a blocking workload, ignoring the CPU count")
		void shouldGrowToMaxThreadCountForBlockingWorkload() throws Exception {
			final int processors = Runtime.getRuntime().availableProcessors();
			// a maximum set above the CPU count but bounded (extra threads capped at min(processors, 8)) so the
			// blocked-worker count and CI resource use stay deterministic on large build agents, while still
			// proving concurrency is no longer clamped to availableProcessors()
			final int maxThreads = processors + Math.min(processors, 8);
			// start from a single core thread so the growth from min -> max is exercised
			final int minThreads = 1;
			// queue large enough that nothing is ever queued — every task should get its own worker thread
			final int queueSize = maxThreads + 50;

			final ObservableThreadExecutor executor = new ObservableThreadExecutor(
				"test-grow-to-max",
				new ThreadPoolOptions(minThreads, maxThreads, Thread.NORM_PRIORITY, queueSize),
				false
			);
			try {
				final AtomicInteger concurrent = new AtomicInteger();
				final AtomicInteger peak = new AtomicInteger();
				final CountDownLatch release = new CountDownLatch(1);
				// counts down once per task that actually starts running concurrently
				final CountDownLatch allRunning = new CountDownLatch(maxThreads);

				for (int i = 0; i < maxThreads; i++) {
					submitBlockingTask(executor, "blocker-" + i, concurrent, peak, allRunning, release);
				}

				// the pool must ramp all the way up to maxThreadCount worker threads
				assertTrue(
					allRunning.await(AWAIT_SECONDS, TimeUnit.SECONDS),
					"Pool failed to grow to its configured maximum of " + maxThreads + " threads"
				);

				assertEquals(
					maxThreads, peak.get(),
					"All " + maxThreads + " configured threads must run concurrently for a blocking workload"
				);
				assertTrue(
					peak.get() > processors,
					"Concurrency (" + peak.get() + ") must exceed the CPU count (" + processors + ") — the " +
						"pool must not clamp parallelism to the processor count"
				);

				release.countDown();
			} finally {
				executor.shutdownNow();
			}
		}
	}

	@Nested
	@DisplayName("Bounded rejection")
	class QueueRejection {

		@Test
		@DisplayName("rejects only once all threads are busy and the backlog is full (max + queueSize admitted)")
		void shouldRejectOnlyWhenAllThreadsBusyAndBacklogFull() throws Exception {
			final int maxThreads = 2;
			final int queueSize = 3;
			// total admission before rejection: maxThreads running + queueSize queued
			final int admissible = maxThreads + queueSize;

			final ObservableThreadExecutor executor = new ObservableThreadExecutor(
				"test-bounded-reject",
				new ThreadPoolOptions(1, maxThreads, Thread.NORM_PRIORITY, queueSize),
				false
			);
			try {
				final CountDownLatch release = new CountDownLatch(1);
				final AtomicInteger concurrent = new AtomicInteger();
				final AtomicInteger peak = new AtomicInteger();
				// only maxThreads tasks can actually run; the rest fill the backlog
				final CountDownLatch allThreadsBusy = new CountDownLatch(maxThreads);

				// fill the running threads and the backlog right up to the limit — none of these may be rejected
				assertDoesNotThrow(() -> {
					for (int i = 0; i < admissible; i++) {
						submitBlockingTask(executor, "blocker-" + i, concurrent, peak, allThreadsBusy, release);
					}
				}, "The first " + admissible + " tasks (max running + full backlog) must all be admitted");

				// the next submission must be rejected — all threads busy AND the backlog is full
				assertThrows(
					RejectedExecutionException.class,
					() -> executor.execute(executor.createTask("overflow", () -> { })),
					"Submission #" + (admissible + 1) + " must be rejected once " + maxThreads +
						" threads are busy and the " + queueSize + "-slot backlog is full"
				);

				assertTrue(
					allThreadsBusy.await(AWAIT_SECONDS, TimeUnit.SECONDS),
					"All " + maxThreads + " worker threads should have started running"
				);
				assertEquals(
					maxThreads, peak.get(),
					"Exactly maxThreadCount=" + maxThreads + " tasks run concurrently; the remaining " +
						queueSize + " wait in the backlog"
				);

				release.countDown();
			} finally {
				executor.shutdownNow();
			}
		}
	}

	@Nested
	@DisplayName("Unrejectable bypass")
	class UnrejectableBypass {

		@Test
		@DisplayName("never rejects an UnrejectableTask, even past the backlog limit")
		void shouldNeverRejectUnrejectableTask() throws Exception {
			final int maxThreads = 2;
			final int queueSize = 3;
			final int admissible = maxThreads + queueSize;

			final ObservableThreadExecutor executor = new ObservableThreadExecutor(
				"test-unrejectable",
				new ThreadPoolOptions(1, maxThreads, Thread.NORM_PRIORITY, queueSize),
				false
			);
			try {
				final CountDownLatch release = new CountDownLatch(1);
				final AtomicInteger concurrent = new AtomicInteger();
				final AtomicInteger peak = new AtomicInteger();
				final CountDownLatch allThreadsBusy = new CountDownLatch(maxThreads);

				// saturate the pool: maxThreads running + a full queueSize backlog
				for (int i = 0; i < admissible; i++) {
					submitBlockingTask(executor, "blocker-" + i, concurrent, peak, allThreadsBusy, release);
				}
				assertTrue(
					allThreadsBusy.await(AWAIT_SECONDS, TimeUnit.SECONDS),
					"All " + maxThreads + " worker threads should have started running"
				);

				// an ordinary task would be rejected here, but an UnrejectableTask must be force-enqueued
				assertDoesNotThrow(
					() -> executor.execute(executor.createTask("unrejectable", new UnrejectableBlocker(() -> { }))),
					"An UnrejectableTask must never be rejected even when the backlog is already full"
				);

				release.countDown();
			} finally {
				executor.shutdownNow();
			}
		}
	}

	@Nested
	@DisplayName("Shutdown & metrics")
	class ShutdownAndMetrics {

		@Test
		@DisplayName("rejects with the shutdown message when a task is submitted after shutdownNow")
		void shouldRejectWithShutdownMessageWhenSubmittingAfterShutdown() {
			final ObservableThreadExecutor executor = new ObservableThreadExecutor(
				"test-shutdown-reject",
				new ThreadPoolOptions(1, 2, Thread.NORM_PRIORITY, 3),
				false
			);
			executor.shutdownNow();

			final RejectedExecutionException ex = assertThrows(
				RejectedExecutionException.class,
				() -> executor.execute(executor.createTask("after-shutdown", () -> { })),
				"Submitting after shutdownNow must be rejected"
			);
			assertTrue(
				ex.getMessage() != null && ex.getMessage().contains("shut down"),
				"Rejection after shutdown must carry the shutdown message, was: " + ex.getMessage()
			);
		}

		@Test
		@DisplayName("increments the rejected-task count by exactly one when the backlog is full")
		void shouldIncrementRejectedCountWhenBacklogFull() throws Exception {
			final int maxThreads = 2;
			final int queueSize = 3;
			final int admissible = maxThreads + queueSize;

			final ObservableThreadExecutor executor = new ObservableThreadExecutor(
				"test-rejected-count",
				new ThreadPoolOptions(1, maxThreads, Thread.NORM_PRIORITY, queueSize),
				false
			);
			try {
				final CountDownLatch release = new CountDownLatch(1);
				final AtomicInteger concurrent = new AtomicInteger();
				final AtomicInteger peak = new AtomicInteger();
				final CountDownLatch allThreadsBusy = new CountDownLatch(maxThreads);

				// saturate: maxThreads running + a full queueSize backlog
				for (int i = 0; i < admissible; i++) {
					submitBlockingTask(executor, "blocker-" + i, concurrent, peak, allThreadsBusy, release);
				}
				assertTrue(
					allThreadsBusy.await(AWAIT_SECONDS, TimeUnit.SECONDS),
					"All " + maxThreads + " worker threads should have started running"
				);

				final long rejectedBefore = executor.getRejectedTaskCount();
				assertThrows(
					RejectedExecutionException.class,
					() -> executor.execute(executor.createTask("overflow", () -> { })),
					"Submission beyond the backlog limit must be rejected"
				);

				assertEquals(
					rejectedBefore + 1, executor.getRejectedTaskCount(),
					"A single rejected submission must bump the rejected-task count by exactly one"
				);

				release.countDown();
			} finally {
				executor.shutdownNow();
			}
		}

		@Test
		@DisplayName("increments the submitted-task count on a successful submission")
		void shouldIncrementSubmittedCountOnSuccessfulSubmit() throws Exception {
			final ObservableThreadExecutor executor = new ObservableThreadExecutor(
				"test-submitted-count",
				new ThreadPoolOptions(1, 2, Thread.NORM_PRIORITY, 5),
				false
			);
			try {
				final long submittedBefore = executor.getSubmittedTaskCount();
				final CountDownLatch done = new CountDownLatch(1);

				executor.execute(executor.createTask("accepted", done::countDown));

				assertTrue(done.await(AWAIT_SECONDS, TimeUnit.SECONDS), "Submitted task should have run");
				assertEquals(
					submittedBefore + 1, executor.getSubmittedTaskCount(),
					"A successful submission must bump the submitted-task count by exactly one"
				);
			} finally {
				executor.shutdownNow();
			}
		}

		@Test
		@DisplayName("emits the completed-task count as a per-tick delta, not the cumulative total")
		void shouldEmitCompletedTaskCountAsPerTickDelta() throws Exception {
			final AtomicLong lastEmittedCompleted = new AtomicLong(-1);
			// a capturing factory records the `completed` value handed to each event before committing it
			final ObservableThreadExecutor executor = new ObservableThreadExecutor(
				"test-completed-delta",
				new ThreadPoolOptions(1, 4, Thread.NORM_PRIORITY, 100),
				false,
				(completed, active, queued, queueRemaining, poolSize, poolCore, poolMax, largestPoolSize) -> {
					lastEmittedCompleted.set(completed);
					return new RequestThreadPoolStatisticsEvent(
						completed, active, queued, queueRemaining, poolSize, poolCore, poolMax, largestPoolSize
					);
				}
			);
			try {
				// run a batch of tasks to completion
				final int batch = 5;
				final List<CancellableRunnable> tasks = new ArrayList<>();
				for (int i = 0; i < batch; i++) {
					final CancellableRunnable task = executor.createTask("task-" + i, () -> { });
					executor.execute(task);
					tasks.add(task);
				}
				for (CancellableRunnable task : tasks) {
					task.completionStage().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS);
				}

				// shutdown()+awaitTermination() is a deterministic barrier: it flushes every worker's
				// completedTaskCount increment (which lags the completion stage), so the count is fully settled
				// with no polling. emitStatistics() still works afterwards — it only reads the pool's getters.
				executor.shutdown();
				assertTrue(
					executor.awaitTermination(AWAIT_SECONDS, TimeUnit.SECONDS),
					"executor must terminate so every completed-task increment is flushed before the first emit"
				);

				// the first emit must report the whole batch as the delta against the (zero) baseline
				executor.emitStatistics();
				assertEquals(
					(long) batch, lastEmittedCompleted.get(),
					"the first emit must report all completed tasks as the per-tick delta"
				);

				// a further emit with no new work must report 0 — proving `completed` is a per-tick delta and not
				// the cumulative total (a cumulative emitter would report the running total again here)
				executor.emitStatistics();
				assertEquals(
					0L, lastEmittedCompleted.get(),
					"an emit with no new work must report 0 completed, not the cumulative total"
				);
			} finally {
				executor.shutdownNow();
			}
		}

		@Test
		@DisplayName("performs no statistics emission when constructed without a factory")
		void shouldNotEmitStatisticsWhenNoFactoryBound() throws Exception {
			// the 3-arg constructor binds a null factory, so emitStatistics() must be an inert no-op:
			// it must not touch the pool, must not emit anything, and must never throw
			final ObservableThreadExecutor executor = new ObservableThreadExecutor(
				"test-no-factory",
				new ThreadPoolOptions(1, 2, Thread.NORM_PRIORITY, 5),
				false
			);
			try {
				// drive some real work through the pool so there is completed-task state the no-op path skips over
				final CountDownLatch done = new CountDownLatch(1);
				executor.execute(executor.createTask("accepted", done::countDown));
				assertTrue(done.await(AWAIT_SECONDS, TimeUnit.SECONDS), "Submitted task should have run");

				// with no factory bound there is nothing to capture; the only observable contract is that the
				// call returns silently. This is the one place a bare does-not-throw IS the behavioural contract.
				assertDoesNotThrow(executor::emitStatistics, "emitStatistics() with no factory must not throw");
				// repeated invocation must remain an inert no-op (the periodic callback fires many times)
				assertDoesNotThrow(executor::emitStatistics, "a repeated emitStatistics() with no factory must stay a no-op");
			} finally {
				executor.shutdownNow();
			}
		}

		@Test
		@DisplayName("reports queueRemaining as soft-cap headroom and clamps it to zero past the limit")
		void shouldClampQueueRemainingToZeroWhenBacklogExceedsSoftLimit() throws Exception {
			final int maxThreads = 2;
			final int queueLimit = 3;
			final int admissible = maxThreads + queueLimit;

			final AtomicInteger lastQueued = new AtomicInteger(-1);
			final AtomicInteger lastQueueRemaining = new AtomicInteger(-1);
			// a capturing factory records the queued / queueRemaining gauges handed to each event before committing
			final ObservableThreadExecutor executor = new ObservableThreadExecutor(
				"test-queue-remaining",
				new ThreadPoolOptions(1, maxThreads, Thread.NORM_PRIORITY, queueLimit),
				false,
				(completed, active, queued, queueRemaining, poolSize, poolCore, poolMax, largestPoolSize) -> {
					lastQueued.set(queued);
					lastQueueRemaining.set(queueRemaining);
					return new RequestThreadPoolStatisticsEvent(
						completed, active, queued, queueRemaining, poolSize, poolCore, poolMax, largestPoolSize
					);
				}
			);
			final CountDownLatch release = new CountDownLatch(1);
			try {
				final AtomicInteger concurrent = new AtomicInteger();
				final AtomicInteger peak = new AtomicInteger();
				final CountDownLatch allThreadsBusy = new CountDownLatch(maxThreads);

				// normal case: only the worker threads are busy, nothing is queued yet -> full soft-cap headroom
				for (int i = 0; i < maxThreads; i++) {
					submitBlockingTask(executor, "worker-" + i, concurrent, peak, allThreadsBusy, release);
				}
				assertTrue(
					allThreadsBusy.await(AWAIT_SECONDS, TimeUnit.SECONDS),
					"All " + maxThreads + " worker threads should have started running"
				);

				executor.emitStatistics();
				assertEquals(0, lastQueued.get(), "with all workers busy and nothing queued, queued must be 0");
				assertEquals(
					queueLimit - lastQueued.get(), lastQueueRemaining.get(),
					"queueRemaining must be the headroom up to the soft queueLimit, not the unbounded queue's raw remaining capacity"
				);

				// fill the bounded backlog right up to the soft limit (these stay queued behind the busy workers)
				for (int i = maxThreads; i < admissible; i++) {
					submitBlockingTask(executor, "backlog-" + i, concurrent, peak, allThreadsBusy, release);
				}
				// force-enqueue extra UnrejectableTasks past the soft limit — the backing queue is unbounded, so
				// these push queued above queueLimit and would drive queueLimit - queued negative without the clamp
				final int overLimit = 2;
				for (int i = 0; i < overLimit; i++) {
					executor.execute(executor.createTask("over-limit-" + i, new UnrejectableBlocker(() -> {
						try {
							release.await(AWAIT_SECONDS, TimeUnit.SECONDS);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
					})));
				}

				// clamp case: queued now exceeds the soft queueLimit, so queueRemaining must be clamped to 0
				executor.emitStatistics();
				assertTrue(
					lastQueued.get() > queueLimit,
					"backlog plus force-enqueued unrejectable tasks must push queued (" + lastQueued.get() +
						") above the soft queueLimit (" + queueLimit + ")"
				);
				assertEquals(
					0, lastQueueRemaining.get(),
					"queueRemaining must be clamped to 0 once queued exceeds the soft queueLimit, never negative"
				);

				release.countDown();
			} finally {
				release.countDown();
				executor.shutdownNow();
			}
		}
	}

	/**
	 * A {@link Runnable} that also carries the {@link UnrejectableTask} marker, so the executor recognises it
	 * as a task that must bypass the bounded-queue rejection.
	 */
	private static final class UnrejectableBlocker implements Runnable, UnrejectableTask {
		private final Runnable delegate;

		UnrejectableBlocker(@Nonnull Runnable delegate) {
			this.delegate = delegate;
		}

		@Override
		public void run() {
			this.delegate.run();
		}
	}
}
