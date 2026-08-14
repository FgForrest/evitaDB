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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TASK;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests verifying the cancellation, queue-counter, queue-limit and wrapper semantics of
 * {@link ObservableThreadExecutor} together with its {@link ObservableRunnable} / {@link ObservableCallable}
 * task wrappers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@SuppressWarnings("ResultOfMethodCallIgnored")
@Tag(ENGINE)
@Tag(TASK)
@DisplayName("ObservableThreadExecutor cancellation, queue tracking & wrapper semantics")
class ObservableThreadExecutorCancellationTest {

	/** Maximum thread count used by the saturation / queue-limit fixtures. */
	private static final int FIXTURE_MAX_THREADS = 2;
	/** Backlog limit used by the saturation / queue-limit fixtures. */
	private static final int FIXTURE_QUEUE_LIMIT = 3;
	/**
	 * Generous upper bound for every assertion-gating wait. The suite runs heavily parallelized on a
	 * CPU-saturated host, so a tight cap would produce starvation-induced false failures; a large cap costs
	 * nothing on the passing path because each `await` / `join` returns the instant its signal fires.
	 */
	private static final long AWAIT_SECONDS = 30L;

	/**
	 * Builds a fresh production-mode executor. Each test gets its own instance because the tests mutate pool
	 * state (saturate it, fill the backlog, cancel queued tasks) — sharing one across tests would leak state.
	 *
	 * @param name  logical pool name, surfaced in thread names and rejection messages
	 * @param max   maximum thread count
	 * @param queue backlog limit once all worker threads are busy
	 * @return a new executor that the caller must shut down
	 */
	@Nonnull
	private static ObservableThreadExecutor newExecutor(@Nonnull String name, int max, int queue) {
		return new ObservableThreadExecutor(
			name,
			new ThreadPoolOptions(1, max, Thread.NORM_PRIORITY, queue),
			false
		);
	}

	/**
	 * Deterministically saturates the executor: starts {@code maxThreads} blocking tasks (confirmed
	 * running via a started-latch so every worker thread is occupied) and then fills {@code queueLimit}
	 * backlog slots. On return the pool holds exactly {@code maxThreads} running + {@code queueLimit}
	 * queued tasks, so the next ordinary submission is rejected.
	 *
	 * The threads-first {@link ObservableThreadExecutor} grows the pool to {@code maxThreads} *before* it
	 * queues anything, so admission is {@code maxThreads + queueLimit} — not {@code queueLimit} alone.
	 * Occupying every worker before filling the backlog is essential: were any worker idle, it would drain
	 * the queue as we fill it and the exact rejection point would become timing-dependent.
	 *
	 * @param executor   the executor to saturate
	 * @param maxThreads the pool's maximum thread count
	 * @param queueLimit the pool's backlog limit
	 * @param outTasks   collects every submitted task so the caller can await / cancel them
	 * @return a latch that releases all submitted blocking tasks when counted down
	 */
	private static CountDownLatch saturate(
		ObservableThreadExecutor executor,
		int maxThreads,
		int queueLimit,
		List<CancellableRunnable> outTasks
	) throws InterruptedException {
		final CountDownLatch release = new CountDownLatch(1);
		final CountDownLatch started = new CountDownLatch(maxThreads);
		// occupy every worker thread first, so the backlog cannot be drained while we fill it
		for (int i = 0; i < maxThreads; i++) {
			final CancellableRunnable task = executor.createTask("running-" + i, () -> {
				started.countDown();
				try {
					release.await(AWAIT_SECONDS, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
			executor.execute(task);
			outTasks.add(task);
		}
		assertTrue(started.await(AWAIT_SECONDS, TimeUnit.SECONDS), "All " + maxThreads + " worker threads should be running");
		// now fill the backlog up to the limit — workers are blocked, so nothing is drained
		for (int i = 0; i < queueLimit; i++) {
			final CancellableRunnable task = executor.createTask("queued-" + i, () -> {
				try {
					release.await(AWAIT_SECONDS, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
			executor.execute(task);
			outTasks.add(task);
		}
		return release;
	}

	/**
	 * Waits until the executor has physically drained any cancelled tombstone tasks from its backlog.
	 *
	 * Cancelling a task that is still waiting in the backlog completes its {@code completionStage}
	 * immediately, but the task itself lingers in the {@link java.util.concurrent.ThreadPoolExecutor}'s
	 * work queue until a worker pulls and discards it. A probe task is submitted (retried while the
	 * backlog is still full) and awaited; once it runs, all tombstones ahead of it have been drained, so
	 * a subsequent {@link #saturate} starts from an empty backlog.
	 *
	 * @param executor the executor to drain
	 */
	private static void awaitDrained(ObservableThreadExecutor executor) throws Exception {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
		while (System.nanoTime() < deadline) {
			try {
				final CancellableRunnable probe = executor.createTask("drain-probe", () -> {});
				executor.execute(probe);
				probe.completionStage().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS);
				return;
			} catch (RejectedExecutionException | TimeoutException e) {
				// backlog still holds undrained tombstones (rejected) or the probe has not run yet under a
				// starved worker (timeout) — short courtesy backoff, then retry. This sleep is a poll backoff,
				// not an assertion-gating fixed wait, and onSpinWait would burn a core on the saturated host.
				Thread.sleep(10);
			}
		}
		// the backlog never drained within the deadline — fail fast with a clear cause instead of returning
		// silently and letting a later assertion fail with a misleading, timing-dependent symptom
		fail("Executor backlog did not drain within " + AWAIT_SECONDS + " seconds — cancelled tombstone tasks were never reclaimed by a worker");
	}

	/**
	 * An {@link ObservableRunnable} whose interrupt delivery the test can hold open: it signals the moment the cancel
	 * has claimed the task and is about to interrupt, then waits for permission before the interrupt actually lands.
	 *
	 * This is the seam that turns the finishing-worker race into a deterministic test. Without it the window between
	 * "the delegate returned" and "the interrupt arrives" can only be swept probabilistically, which belongs in the
	 * long-running module rather than the fast loop.
	 */
	private static class HeldInterruptRunnable extends ObservableRunnable {
		/** Counted down once the cancel has claimed the running task and reached the delivery point. */
		private final CountDownLatch deliveryReached;
		/** Awaited before the interrupt is delivered, so the test decides how long the delivery stays pending. */
		private final CountDownLatch deliveryRelease;

		private HeldInterruptRunnable(
			@Nonnull Runnable delegate,
			@Nonnull CountDownLatch deliveryReached,
			@Nonnull CountDownLatch deliveryRelease
		) {
			super("test-held-interrupt", delegate, Functions.noOpRunnable());
			this.deliveryReached = deliveryReached;
			this.deliveryRelease = deliveryRelease;
		}

		@Override
		protected void deliverInterrupt(@Nonnull Thread thread) {
			this.deliveryReached.countDown();
			try {
				assertTrue(
					this.deliveryRelease.await(AWAIT_SECONDS, TimeUnit.SECONDS),
					"interrupt delivery was never released"
				);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			super.deliverInterrupt(thread);
		}
	}

	/**
	 * An {@link ObservableRunnable} that holds the worker *inside* `finishExecution()`, after the delegate has
	 * returned and the result future has completed normally, but while the task is still `RUNNING`.
	 *
	 * That window is the one a late cancel slips into: `future.cancel(true)` fails against an already-completed
	 * future, yet the `RUNNING -> CANCELLING` transition still succeeds and a real interrupt reaches this worker.
	 * The seam turns that ordering into something deterministic rather than a probabilistic sweep.
	 */
	private static class HeldFinishRunnable extends ObservableRunnable {
		/** Counted down once the delegate has returned and the worker has entered `finishExecution()`. */
		private final CountDownLatch finishReached;
		/** Awaited before `super.finishExecution()` runs, so the test decides when the worker proceeds. */
		private final CountDownLatch finishRelease;
		/** The worker's interrupt flag as observed immediately after `super.finishExecution()` returned. */
		private final AtomicBoolean interruptedAfterFinish = new AtomicBoolean(false);

		private HeldFinishRunnable(@Nonnull CountDownLatch finishReached, @Nonnull CountDownLatch finishRelease) {
			super("test-held-finish", Functions.noOpRunnable(), Functions.noOpRunnable());
			this.finishReached = finishReached;
			this.finishRelease = finishRelease;
		}

		@Override
		protected void finishExecution() {
			this.finishReached.countDown();
			awaitKeepingInterruptFlag(this.finishRelease);
			super.finishExecution();
			this.interruptedAfterFinish.set(Thread.currentThread().isInterrupted());
		}

		/**
		 * Waits for the latch without consuming the interrupt flag this fixture exists to observe. The cancel under
		 * test lands while the worker is parked here, and a bare {@link CountDownLatch#await(long, TimeUnit)} would
		 * both throw and clear the flag, erasing the very state the assertion is about. The flag is restored before
		 * returning, which is what a worker interrupted mid-window genuinely carries into `finishExecution()`.
		 */
		private static void awaitKeepingInterruptFlag(@Nonnull CountDownLatch latch) {
			boolean interrupted = false;
			while (true) {
				try {
					assertTrue(
						latch.await(AWAIT_SECONDS, TimeUnit.SECONDS),
						"the worker was never released from finishExecution()"
					);
					break;
				} catch (InterruptedException e) {
					interrupted = true;
				}
			}
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}
	}

	@Nested
	@DisplayName("Completion propagation on pre-start cancellation")
	class CompletionPropagation {

		@Test
		@DisplayName("leaves an unwired external future untouched when a cancelled task runs")
		void shouldLeaveExternalFutureUntouchedWhenCompletionNotPropagated() {
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
		@DisplayName("cancels a wired external future when a cancelled task runs")
		void shouldCancelExternalFutureWhenCompletionPropagated() {
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
	}

	@Nested
	@DisplayName("Cancellation interrupts a running task")
	class CancellationInterruptsExecution {

		@Test
		@DisplayName("interrupts the worker thread when a running runnable is cancelled")
		void shouldInterruptWorkerThreadWhenRunningRunnableCancelled() throws Exception {
			final CountDownLatch taskStarted = new CountDownLatch(1);
			final AtomicBoolean wasInterrupted = new AtomicBoolean(false);

			final ObservableRunnable task = new ObservableRunnable(
				"test-cancel-during-run",
				() -> {
					taskStarted.countDown();
					try {
						// park on a latch that is never counted down: it survives starvation (won't wake on its
						// own before the test interrupts it) yet stays interruptible, so cancel() drives the path
						new CountDownLatch(1).await(AWAIT_SECONDS, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						wasInterrupted.set(true);
					}
				},
				Functions.noOpRunnable()
			);

			final Thread worker = new Thread(task, "test-worker");
			worker.start();
			assertTrue(taskStarted.await(AWAIT_SECONDS, TimeUnit.SECONDS), "Task should have started");

			task.cancel();

			worker.join(AWAIT_SECONDS * 1_000);
			assertFalse(worker.isAlive(), "Worker thread should have finished");
			assertTrue(wasInterrupted.get(), "Worker thread should have been interrupted");
			assertTrue(task.isFinished(), "Task should be marked as finished (cancelled)");
		}

		@Test
		@DisplayName("interrupts the worker thread when a running callable is cancelled")
		void shouldInterruptWorkerThreadWhenRunningCallableCancelled() throws Exception {
			final CountDownLatch taskStarted = new CountDownLatch(1);
			final AtomicBoolean wasInterrupted = new AtomicBoolean(false);

			final ObservableCallable<String> task = new ObservableCallable<>(
				"test-cancel-during-call",
				() -> {
					taskStarted.countDown();
					try {
						// park on a latch that is never counted down: it survives starvation (won't wake on its
						// own before the test interrupts it) yet stays interruptible, so cancel() drives the path
						new CountDownLatch(1).await(AWAIT_SECONDS, TimeUnit.SECONDS);
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
			assertTrue(taskStarted.await(AWAIT_SECONDS, TimeUnit.SECONDS), "Task should have started");

			task.cancel();

			worker.join(AWAIT_SECONDS * 1_000);
			assertFalse(worker.isAlive(), "Worker thread should have finished");
			assertTrue(wasInterrupted.get(), "Worker thread should have been interrupted");
			assertTrue(task.isFinished(), "Task should be marked as finished (cancelled)");
		}
	}

	@Nested
	@DisplayName("Interrupt delivery stays inside the cancelled task")
	class InterruptDeliveryConfinement {

		@Test
		@DisplayName("holds a finishing worker until a concurrent cancel has delivered its interrupt")
		void shouldHoldFinishingWorkerUntilConcurrentCancelDeliveredInterrupt() throws Exception {
			// the failure this pins: a cancel reads the executing thread, gets preempted, and calls interrupt() only
			// after the worker has finished this task and picked up the next one - so an unrelated piece of work dies
			// at its first interrupt checkpoint. Nothing but the wait in finishExecution() rules that out.
			final CountDownLatch bodyEntered = new CountDownLatch(1);
			final CountDownLatch bodyRelease = new CountDownLatch(1);
			final CountDownLatch deliveryReached = new CountDownLatch(1);
			final CountDownLatch deliveryRelease = new CountDownLatch(1);
			final CountDownLatch workerReturned = new CountDownLatch(1);
			final AtomicBoolean flagLeaked = new AtomicBoolean(false);

			final HeldInterruptRunnable task = new HeldInterruptRunnable(
				() -> {
					bodyEntered.countDown();
					try {
						// released only once the cancel has parked at the delivery point, so the delegate is
						// guaranteed to finish while the task is still held open for an interrupt
						assertTrue(bodyRelease.await(AWAIT_SECONDS, TimeUnit.SECONDS), "body was never released");
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				},
				deliveryReached, deliveryRelease
			);

			final Thread worker = new Thread(
				() -> {
					task.run();
					// read on the worker itself once run() has returned - this is the thread that would carry a
					// leaked interrupt into whatever the pool hands it next
					flagLeaked.set(Thread.currentThread().isInterrupted());
					workerReturned.countDown();
				},
				"test-worker"
			);
			worker.setDaemon(true);
			worker.start();
			assertTrue(bodyEntered.await(AWAIT_SECONDS, TimeUnit.SECONDS), "task never started");

			// cancel from its own thread: the delivery parks, so it cannot run on the test thread
			final Thread canceller = new Thread(task::cancel, "test-canceller");
			canceller.setDaemon(true);
			canceller.start();
			assertTrue(
				deliveryReached.await(AWAIT_SECONDS, TimeUnit.SECONDS),
				"the cancel never claimed the running task, so it never had an interrupt to deliver"
			);

			// the interrupt is now claimed but undelivered - let the delegate finish underneath it
			bodyRelease.countDown();

			// negative wait - correct short, and it cannot fail spuriously: a loaded machine can only delay a worker
			// that is already free to leave, never make a blocked one leave early
			assertFalse(
				workerReturned.await(250, TimeUnit.MILLISECONDS),
				"the worker left the finished task while a cancellation interrupt was still in flight towards it"
			);

			deliveryRelease.countDown();
			assertTrue(workerReturned.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the worker never finished");
			canceller.join(AWAIT_SECONDS * 1_000);
			assertFalse(
				flagLeaked.get(),
				"the cancellation interrupt outlived its task and would abort the worker's next piece of work"
			);
		}

		@Test
		@DisplayName("does not interrupt a worker that already finished the cancelled task")
		void shouldNotInterruptWorkerThatAlreadyFinishedCancelledTask() throws Exception {
			// the counterfactual: once the task is over, a cancel must deliver nothing at all rather than deliver
			// late. deliverInterrupt is never reached, so the seam's latch staying at one is the assertion.
			final CountDownLatch deliveryReached = new CountDownLatch(1);
			final AtomicBoolean bodyRan = new AtomicBoolean(false);

			final HeldInterruptRunnable task = new HeldInterruptRunnable(
				() -> bodyRan.set(true), deliveryReached, new CountDownLatch(0)
			);

			final Thread worker = new Thread(task, "test-worker");
			worker.setDaemon(true);
			worker.start();
			worker.join(AWAIT_SECONDS * 1_000);
			assertFalse(worker.isAlive(), "the worker never finished the task");
			assertTrue(bodyRan.get(), "the delegate never ran");

			task.cancel();

			// negative wait against a latch the delivery point would have counted down - it stays at one because the
			// task reached DONE before the cancel could claim it
			assertFalse(
				deliveryReached.await(250, TimeUnit.MILLISECONDS),
				"a cancel arriving after the task finished still tried to interrupt the worker thread"
			);
			assertFalse(
				Thread.currentThread().isInterrupted(),
				"the late cancel interrupted the thread that happened to call it"
			);
		}

		@Test
		@DisplayName("clears a delivered interrupt even when the cancel lost the race to the completed future")
		void shouldClearInterruptWhenCancelArrivedAfterFutureCompleted() throws Exception {
			// the gap the executionState machine leaves open on its own: interrupt *delivery* is gated on the state,
			// but flag *clearing* is gated on the future. A cancel arriving after the delegate completed normally
			// cannot cancel the future, yet still wins RUNNING -> CANCELLING and really does interrupt the worker -
			// so the two gates disagree and the flag rides out of finishExecution() into the next task.
			// The sibling test above covers the other side of the same window, where the cancel arrives first.
			final CountDownLatch finishReached = new CountDownLatch(1);
			final CountDownLatch finishRelease = new CountDownLatch(1);
			final HeldFinishRunnable task = new HeldFinishRunnable(finishReached, finishRelease);

			final Thread worker = new Thread(task, "test-worker");
			worker.setDaemon(true);
			worker.start();

			assertTrue(
				finishReached.await(AWAIT_SECONDS, TimeUnit.SECONDS),
				"the worker never reached finishExecution()"
			);

			task.cancel();
			assertFalse(
				task.completionStage().isCancelled(),
				"the delegate had already completed the future, so this cancel must not have cancelled it - without "
					+ "that the test would be exercising the ordinary cancelled-future path instead of this window"
			);

			finishRelease.countDown();
			worker.join(AWAIT_SECONDS * 1_000);
			assertFalse(worker.isAlive(), "the worker never finished the task");

			assertFalse(
				task.interruptedAfterFinish.get(),
				"the worker left finishExecution() still interrupted - the flag leaks onto whatever it runs next"
			);
		}
	}

	@Nested
	@DisplayName("Pre-start cancellation skips execution")
	class PreStartCancellationSkipsExecution {

		@Test
		@DisplayName("does not invoke the delegate when a runnable is cancelled before it runs")
		void shouldSkipDelegateWhenRunnableCancelledBeforeRun() {
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
		@DisplayName("does not invoke the delegate when a callable is cancelled before it runs")
		void shouldSkipDelegateWhenCallableCancelledBeforeCall() throws Exception {
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
	}

	@Nested
	@DisplayName("Queue counter is decremented exactly once")
	class QueueCounterDecrement {

		@Test
		@DisplayName("decrements the queue counter once when a runnable is cancelled before it runs")
		void shouldDecrementOnceWhenRunnableCancelledBeforeRun() {
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
		@DisplayName("decrements the queue counter once when a runnable is cancelled while running")
		void shouldDecrementOnceWhenRunnableCancelledDuringRun() throws Exception {
			final AtomicInteger queueSize = new AtomicInteger(1);
			final CountDownLatch taskStarted = new CountDownLatch(1);

			final ObservableRunnable task = new ObservableRunnable(
				"test-double-decrement-during",
				() -> {
					taskStarted.countDown();
					try {
						// park on a never-released latch: survives starvation yet stays interruptible by cancel()
						new CountDownLatch(1).await(AWAIT_SECONDS, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						// expected
					}
				},
				queueSize::decrementAndGet
			);

			final Thread worker = new Thread(task, "test-worker");
			worker.start();
			assertTrue(taskStarted.await(AWAIT_SECONDS, TimeUnit.SECONDS), "Task should have started");

			task.cancel();
			worker.join(AWAIT_SECONDS * 1_000);

			assertEquals(0, queueSize.get(), "queueSize should be decremented exactly once (cancel-during-run)");
		}

		@Test
		@DisplayName("decrements the queue counter once when a completed runnable is cancelled afterwards")
		void shouldDecrementOnceWhenRunnableCancelledAfterCompletion() {
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
		@DisplayName("decrements the queue counter once when a callable is cancelled before it runs")
		void shouldDecrementOnceWhenCallableCancelledBeforeCall() throws Exception {
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
		@DisplayName("decrements the queue counter once when a callable is cancelled while running")
		void shouldDecrementOnceWhenCallableCancelledDuringCall() throws Exception {
			final AtomicInteger queueSize = new AtomicInteger(1);
			final CountDownLatch taskStarted = new CountDownLatch(1);

			final ObservableCallable<String> task = new ObservableCallable<>(
				"test-double-decrement-callable-during",
				() -> {
					taskStarted.countDown();
					try {
						// park on a never-released latch: survives starvation yet stays interruptible by cancel()
						new CountDownLatch(1).await(AWAIT_SECONDS, TimeUnit.SECONDS);
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
			assertTrue(taskStarted.await(AWAIT_SECONDS, TimeUnit.SECONDS), "Task should have started");

			task.cancel();
			worker.join(AWAIT_SECONDS * 1_000);

			assertEquals(0, queueSize.get(), "queueSize should be decremented exactly once (cancel-during-call)");
		}
	}

	@Nested
	@DisplayName("Queue-size limit enforcement")
	class QueueSizeLimitEnforcement {

		/** Per-test executor with the uniform fixture sizing; rebuilt for every test and torn down after. */
		private ObservableThreadExecutor executor;

		@BeforeEach
		void setUp() {
			this.executor = newExecutor("test-queue-limit", FIXTURE_MAX_THREADS, FIXTURE_QUEUE_LIMIT);
		}

		@AfterEach
		void tearDown() {
			this.executor.shutdownNow();
		}

		@Test
		@DisplayName("rejects a task once all threads are busy and the backlog is full, then accepts again once drained")
		void shouldRejectExcessTaskWhenSaturatedAndAcceptAfterDrain() throws Exception {
			final List<CancellableRunnable> tasks = new ArrayList<>();
			final CountDownLatch release = saturate(this.executor, FIXTURE_MAX_THREADS, FIXTURE_QUEUE_LIMIT, tasks);

			// the next submission should be rejected — all threads busy AND the backlog is full
			assertThrows(
				RejectedExecutionException.class,
				() -> this.executor.execute(this.executor.createTask("excess-task", () -> {})),
				"Submitting beyond maxThreadCount + queueSize should throw RejectedExecutionException"
			);

			// Unblock all tasks and wait for completion
			release.countDown();
			for (CancellableRunnable task : tasks) {
				task.completionStage().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS);
			}

			// After all tasks complete, we should be able to submit again
			final AtomicBoolean executed = new AtomicBoolean(false);
			final CancellableRunnable postTask = this.executor.createTask("post-limit-task", () -> executed.set(true));
			this.executor.execute(postTask);
			postTask.completionStage().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS);
			assertTrue(executed.get(), "Task submitted after queue drained should execute successfully");
		}

		@Test
		@DisplayName("keeps enforcing the limit after repeated saturate-and-cancel cycles (counter does not drift negative)")
		void shouldKeepEnforcingLimitAfterRepeatedCancellations() throws Exception {
			// this test needs a deeper backlog than the shared fixture, so it uses its own executor
			final int maxThreads = 2;
			final int queueLimit = 5;
			final ObservableThreadExecutor driftExecutor = newExecutor("test-queue-drift", maxThreads, queueLimit);
			try {
				// Submit and cancel a full pool's worth of tasks repeatedly to stress queue-size tracking
				for (int round = 0; round < 3; round++) {
					final List<CancellableRunnable> tasks = new ArrayList<>();
					final CountDownLatch release = saturate(driftExecutor, maxThreads, queueLimit, tasks);

					// Cancel all tasks (this should only decrement queueSize once per task)
					for (CancellableRunnable task : tasks) {
						task.cancel();
					}
					release.countDown();

					// Wait for all tasks to finish (cancelled tasks throw CancellationException)
					for (CancellableRunnable task : tasks) {
						try {
							task.completionStage().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS);
						} catch (CancellationException | ExecutionException e) {
							// expected for cancelled tasks
						}
					}
					// cancelling a queued task completes its stage immediately but leaves a tombstone in the
					// backlog until a worker drains it; wait for that drain so the next saturate starts clean
					awaitDrained(driftExecutor);
				}

				// If queueSize drifted negative the limit would no longer be enforced. Saturate once more
				// and prove the next ordinary submission is still rejected.
				final List<CancellableRunnable> finalTasks = new ArrayList<>();
				final CountDownLatch releaseFinal = saturate(driftExecutor, maxThreads, queueLimit, finalTasks);

				// This must still be rejected — proves the queue size didn't drift negative
				assertThrows(
					RejectedExecutionException.class,
					() -> driftExecutor.execute(driftExecutor.createTask("must-reject", () -> {})),
					"Queue limit must still be enforced after cancellations (queueSize should not have drifted negative)"
				);

				releaseFinal.countDown();
				for (CancellableRunnable task : finalTasks) {
					task.completionStage().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS);
				}
			} finally {
				driftExecutor.shutdownNow();
			}
		}

		@Test
		@DisplayName("rejects an ordinary task when all threads are busy and the backlog is full")
		void shouldRejectNormalTaskWhenQueueFull() throws Exception {
			final List<CancellableRunnable> tasks = new ArrayList<>();
			final CountDownLatch release = saturate(this.executor, FIXTURE_MAX_THREADS, FIXTURE_QUEUE_LIMIT, tasks);

			// Normal task should be rejected
			assertThrows(
				RejectedExecutionException.class,
				() -> this.executor.execute(this.executor.createTask("normal-task", () -> {})),
				"Normal task should be rejected when all threads are busy and the backlog is full"
			);

			release.countDown();
			for (CancellableRunnable task : tasks) {
				task.completionStage().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS);
			}
		}

		@Test
		@DisplayName("balances the in-flight counter when invokeAll is rejected on a saturated pool")
		void shouldBalanceInFlightCounterWhenInvokeAllRejected() throws Exception {
			final List<CancellableRunnable> tasks = new ArrayList<>();
			final CountDownLatch release = saturate(this.executor, FIXTURE_MAX_THREADS, FIXTURE_QUEUE_LIMIT, tasks);

			// the saturation tasks are all blocked, so the in-flight counter is stable at this baseline
			final int inFlightBefore = this.executor.getInFlightTaskCount();
			final long submittedBefore = this.executor.getSubmittedTaskCount();

			// invokeAll wraps every task (incrementing the in-flight counter once per task in wrapToCancellableTask)
			// before delegating to the pool; on a saturated pool the pool rejects and the exception propagates out.
			// The rejected (and never-run) tasks must have their increments balanced back, or the in-flight counter
			// would drift upward and permanently skew the TaskQueue grow heuristic.
			assertThrows(
				RejectedExecutionException.class,
				() -> this.executor.invokeAll(List.of(
					(Callable<String>) () -> "a",
					(Callable<String>) () -> "b"
				)),
				"invokeAll on a saturated pool must surface the RejectedExecutionException"
			);

			assertEquals(
				inFlightBefore, this.executor.getInFlightTaskCount(),
				"A rejected invokeAll must return the in-flight counter to its pre-call baseline"
			);
			assertEquals(
				submittedBefore, this.executor.getSubmittedTaskCount(),
				"A rejected invokeAll must not increment the submitted-task count"
			);

			// release and drain the legitimate saturation tasks
			release.countDown();
			for (CancellableRunnable task : tasks) {
				task.completionStage().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS);
			}
			awaitDrained(this.executor);

			// once everything has drained the counter must settle back to zero — no residual drift. Poll on the
			// real signal (the counter) with a generous deadline and a small courtesy backoff (a poll backoff,
			// not an assertion-gating fixed wait; onSpinWait would burn a core on the saturated host).
			final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
			while (this.executor.getInFlightTaskCount() != 0 && System.nanoTime() < deadline) {
				Thread.sleep(10);
			}
			assertEquals(
				0, this.executor.getInFlightTaskCount(),
				"After every task has drained the in-flight counter must settle back to zero"
			);
		}
	}

	@Nested
	@DisplayName("Unrejectable task bypass")
	class UnrejectableBypass {

		/** Per-test executor with the uniform fixture sizing; rebuilt for every test and torn down after. */
		private ObservableThreadExecutor executor;

		@BeforeEach
		void setUp() {
			this.executor = newExecutor("test-unrejectable", FIXTURE_MAX_THREADS, FIXTURE_QUEUE_LIMIT);
		}

		@AfterEach
		void tearDown() {
			this.executor.shutdownNow();
		}

		@Test
		@DisplayName("admits a bare unrejectable runnable past the full backlog and runs it")
		void shouldBypassQueueLimitForUnrejectableRunnable() throws Exception {
			final List<CancellableRunnable> tasks = new ArrayList<>();
			final CountDownLatch release = saturate(this.executor, FIXTURE_MAX_THREADS, FIXTURE_QUEUE_LIMIT, tasks);

			// Normal task should be rejected
			assertThrows(
				RejectedExecutionException.class,
				() -> this.executor.execute(this.executor.createTask("normal-excess-task", () -> {})),
				"Normal task should be rejected when all threads are busy and the backlog is full"
			);

			// Unrejectable task should NOT be rejected
			final CountDownLatch executed = new CountDownLatch(1);
			final Runnable unrejectableRunnable = new UnrejectableTestRunnable(executed::countDown);
			assertDoesNotThrow(
				() -> this.executor.execute(unrejectableRunnable),
				"Unrejectable task should bypass queue limit"
			);

			// Unblock all tasks and wait
			release.countDown();
			for (CancellableRunnable task : tasks) {
				task.completionStage().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS);
			}

			// wait on the real signal: the unrejectable task counts the latch down from inside its body
			assertTrue(
				executed.await(AWAIT_SECONDS, TimeUnit.SECONDS),
				"Unrejectable task should have been executed"
			);
		}

		@Test
		@DisplayName("admits a pre-wrapped ObservableRunnable with an unrejectable delegate past the full backlog")
		void shouldBypassQueueLimitForObservableRunnableWithUnrejectableDelegate() throws Exception {
			final List<CancellableRunnable> tasks = new ArrayList<>();
			final CountDownLatch release = saturate(this.executor, FIXTURE_MAX_THREADS, FIXTURE_QUEUE_LIMIT, tasks);

			// Pre-wrapped ObservableRunnable with UnrejectableTask delegate should bypass
			final AtomicBoolean executed = new AtomicBoolean(false);
			final ObservableRunnable observableUnrejectable = new ObservableRunnable(
				new UnrejectableTestRunnable(() -> executed.set(true)),
				Functions.noOpRunnable()
			);
			assertTrue(observableUnrejectable.isUnrejectable(), "ObservableRunnable with UnrejectableTask delegate should report isUnrejectable()=true");
			assertDoesNotThrow(
				() -> this.executor.execute(observableUnrejectable),
				"ObservableRunnable with UnrejectableTask delegate should bypass queue limit"
			);

			release.countDown();
			for (CancellableRunnable task : tasks) {
				task.completionStage().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS);
			}

			observableUnrejectable.completionStage().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS);
			assertTrue(executed.get(), "ObservableRunnable with unrejectable delegate should have executed");
		}

		@Test
		@DisplayName("keeps queue tracking balanced so the limit is still enforced after unrejectable tasks complete")
		void shouldKeepQueueTrackingBalancedAfterUnrejectableTasks() throws Exception {
			final List<CancellableRunnable> tasks = new ArrayList<>();
			final CountDownLatch release = saturate(this.executor, FIXTURE_MAX_THREADS, FIXTURE_QUEUE_LIMIT, tasks);

			// Submit unrejectable tasks beyond the limit (using raw UnrejectableTestRunnable
			// so that wrapToCancellableTask wraps them with the correct queueSizeDecrementer). Each counts the
			// latch down from its body so we can wait on the real completion signal rather than a fixed sleep.
			final int unrejectableCount = 3;
			final CountDownLatch unrejectableExecuted = new CountDownLatch(unrejectableCount);
			for (int i = 0; i < unrejectableCount; i++) {
				assertDoesNotThrow(
					() -> this.executor.execute(new UnrejectableTestRunnable(unrejectableExecuted::countDown)),
					"Unrejectable tasks must never be rejected even past the backlog limit"
				);
			}

			// Unblock everything
			release.countDown();
			for (CancellableRunnable task : tasks) {
				task.completionStage().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS);
			}
			// wait on the real signal: every unrejectable task must have run before we re-saturate
			assertTrue(
				unrejectableExecuted.await(AWAIT_SECONDS, TimeUnit.SECONDS),
				"All unrejectable tasks must have executed before the pool is re-saturated"
			);

			// After all tasks complete, the queue should be back to accepting normal tasks.
			// Saturate again and verify the limit is still enforced — if queueSize drifted, this would fail.
			final List<CancellableRunnable> finalTasks = new ArrayList<>();
			final CountDownLatch releaseFinal = saturate(this.executor, FIXTURE_MAX_THREADS, FIXTURE_QUEUE_LIMIT, finalTasks);

			// This should still be rejected — proves queue tracking is balanced
			assertThrows(
				RejectedExecutionException.class,
				() -> this.executor.execute(this.executor.createTask("must-reject", () -> {})),
				"Queue limit must still be enforced after unrejectable tasks complete"
			);

			releaseFinal.countDown();
			for (CancellableRunnable task : finalTasks) {
				task.completionStage().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS);
			}
		}

		@Test
		@DisplayName("admits an unrejectable callable submitted via submit on a saturated pool and runs it")
		void shouldAdmitUnrejectableCallableWhenSubmittedToSaturatedPool() throws Exception {
			final List<CancellableRunnable> tasks = new ArrayList<>();
			final CountDownLatch release = saturate(this.executor, FIXTURE_MAX_THREADS, FIXTURE_QUEUE_LIMIT, tasks);

			// submit(Callable) hands the ObservableCallable to ThreadPoolExecutor.submit(), which would otherwise
			// wrap it in a plain FutureTask and hide the unrejectable marker from the rejection handler. The
			// executor instead routes an unrejectable submission through a marker-carrying future, so the bypass
			// survives and the task is force-enqueued rather than rejected.
			final Future<String> future = assertDoesNotThrow(
				() -> this.executor.submit(new UnrejectableTestCallable<>(() -> "unrejectable-result")),
				"An unrejectable callable submitted via submit must never be rejected, even on a saturated pool"
			);

			// unblock the saturation tasks so a worker becomes free to run the force-enqueued unrejectable task
			release.countDown();
			for (CancellableRunnable task : tasks) {
				task.completionStage().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS);
			}

			// the force-enqueued task must actually run and yield its result
			assertEquals(
				"unrejectable-result", future.get(AWAIT_SECONDS, TimeUnit.SECONDS),
				"The force-enqueued unrejectable callable must run once a worker frees up"
			);
		}
	}

	@Nested
	@DisplayName("Wrapper string representation")
	class WrapperStringRepresentation {

		@Test
		@DisplayName("returns the configured name from a named runnable wrapper")
		void shouldReturnNameFromNamedRunnable() {
			final ObservableRunnable task = new ObservableRunnable(
				"named-runnable",
				Functions.noOpRunnable(),
				Functions.noOpRunnable()
			);

			assertEquals("named-runnable", task.toString(), "A named runnable wrapper must return its name");
		}

		@Test
		@DisplayName("delegates to the delegate's toString for an unnamed runnable wrapper")
		void shouldDelegateToStringForUnnamedRunnable() {
			final Runnable delegate = new Runnable() {
				@Override
				public void run() {
					// no-op
				}

				@Override
				public String toString() {
					return "delegate-runnable-string";
				}
			};
			final ObservableRunnable task = new ObservableRunnable(delegate, Functions.noOpRunnable());

			assertEquals(
				"delegate-runnable-string", task.toString(),
				"An unnamed runnable wrapper must fall back to its delegate's toString"
			);
		}

		@Test
		@DisplayName("returns the configured name from a named callable wrapper")
		void shouldReturnNameFromNamedCallable() {
			final ObservableCallable<String> task = new ObservableCallable<>(
				"named-callable",
				() -> "result",
				Functions.noOpRunnable()
			);

			assertEquals("named-callable", task.toString(), "A named callable wrapper must return its name");
		}

		@Test
		@DisplayName("delegates to the delegate's toString for an unnamed callable wrapper")
		void shouldDelegateToStringForUnnamedCallable() {
			final Callable<String> delegate = new Callable<>() {
				@Override
				public String call() {
					return "result";
				}

				@Override
				public String toString() {
					return "delegate-callable-string";
				}
			};
			final ObservableCallable<String> task = new ObservableCallable<>(delegate, Functions.noOpRunnable());

			assertEquals(
				"delegate-callable-string", task.toString(),
				"An unnamed callable wrapper must fall back to its delegate's toString"
			);
		}
	}

	@Nested
	@DisplayName("Callable exception propagation")
	class CallableExceptionPropagation {

		@Test
		@DisplayName("surfaces a checked exception as its original type and completes the stage exceptionally with it")
		void shouldSurfaceCheckedExceptionAsOriginalType() {
			final IOException thrown = new IOException("checked-boom");
			final ObservableCallable<String> task = new ObservableCallable<>(
				"checked-failing-callable",
				() -> { throw thrown; },
				Functions.noOpRunnable()
			);

			final IOException caught = assertThrows(
				IOException.class,
				task::call,
				"A checked exception must surface to the caller as its original checked type"
			);
			assertSame(thrown, caught, "The original checked exception instance must be surfaced unchanged");

			final ExecutionException stageFailure = assertThrows(
				ExecutionException.class,
				() -> task.completionStage().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS),
				"The completion stage must complete exceptionally when the delegate throws"
			);
			assertSame(thrown, stageFailure.getCause(), "The completion stage must carry the original checked exception as its cause");
		}

		@Test
		@DisplayName("propagates a runtime exception unchanged without wrapping it")
		void shouldPropagateRuntimeExceptionUnchanged() {
			final IllegalStateException thrown = new IllegalStateException("runtime-boom");
			final ObservableCallable<String> task = new ObservableCallable<>(
				"runtime-failing-callable",
				() -> { throw thrown; },
				Functions.noOpRunnable()
			);

			final IllegalStateException caught = assertThrows(
				IllegalStateException.class,
				task::call,
				"A runtime exception must propagate as-is, not wrapped in a transport exception"
			);
			assertSame(thrown, caught, "The original runtime exception instance must be propagated unchanged");

			final ExecutionException stageFailure = assertThrows(
				ExecutionException.class,
				() -> task.completionStage().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS),
				"The completion stage must complete exceptionally when the delegate throws"
			);
			assertInstanceOf(
				IllegalStateException.class, stageFailure.getCause(),
				"The completion stage must carry the original runtime exception as its cause"
			);
		}
	}

	/**
	 * A test helper: a Runnable that also implements UnrejectableTask.
	 */
	private record UnrejectableTestRunnable(@Nonnull Runnable delegate) implements Runnable, UnrejectableTask {
		@Override
		public void run() {
			this.delegate.run();
		}
	}

	/**
	 * A test helper: a Callable that also implements UnrejectableTask, used to verify that the unrejectable
	 * bypass survives the {@code submit(Callable)} path.
	 *
	 * @param <V> the result type produced by the delegate
	 */
	private record UnrejectableTestCallable<V>(@Nonnull Callable<V> delegate) implements Callable<V>, UnrejectableTask {
		@Override
		public V call() throws Exception {
			return this.delegate.call();
		}
	}
}
