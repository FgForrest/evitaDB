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

package io.evitadb.driver.cdc;

import io.evitadb.driver.exception.EvitaClientPoolSaturatedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.DRIVER;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.STREAM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the invariants of {@link SerialCdcExecutor} directly, rather than through the publisher.
 *
 * The class carries three separate mechanisms that the end-to-end heartbeat tests exercise only incidentally:
 * a CAS-guarded single-active-drain, the re-check after releasing that guard, and the terminal handling of
 * a delegate that refuses the drain. Each is a concurrency mechanism whose failure mode is a *silent stall*,
 * so pinning them here makes a regression fail next to its cause.
 *
 * The ordering guarantee is what the whole class exists for: a {@link HeartBeatSensor} derives missed-heartbeat
 * counts from `HeartBeat#index()` continuity, so two reordered notifications manufacture a phantom gap.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Serial change data capture executor")
@Tag(DRIVER)
@Tag(GRPC)
@Tag(CDC)
@Tag(STREAM)
class SerialCdcExecutorTest {

	@Nested
	@DisplayName("when the delegate accepts the drain")
	class AcceptingDelegate {

		@Test
		@DisplayName("Runs tasks in submission order even on a multi-threaded delegate")
		void shouldRunTasksInSubmissionOrderOnAMultiThreadedDelegate() throws Exception {
			// a plain multi-threaded pool would reorder these; the serial executor must not
			final ExecutorService delegate = Executors.newFixedThreadPool(8);
			try {
				final int taskCount = 500;
				final List<Integer> observed = Collections.synchronizedList(new ArrayList<>(taskCount));
				final CountDownLatch done = new CountDownLatch(taskCount);
				final SerialCdcExecutor executor = createExecutor(delegate, failure -> {});

				for (int i = 0; i < taskCount; i++) {
					final int index = i;
					executor.execute(() -> {
						observed.add(index);
						done.countDown();
					});
				}

				// generous on purpose: this is a liveness bound, not a performance assertion, and the suite runs
				// in parallel forks that contend for CPU. A passing run never waits, a hung one still fails.
				assertTrue(done.await(30, TimeUnit.SECONDS), "the tasks did not finish in time");
				final List<Integer> expected = new ArrayList<>(taskCount);
				for (int i = 0; i < taskCount; i++) {
					expected.add(i);
				}
				assertEquals(expected, observed, "tasks must run in submission order");
			} finally {
				delegate.shutdownNow();
			}
		}

		@Test
		@DisplayName("Runs at most one task at a time under concurrent submission")
		void shouldRunAtMostOneTaskAtATime() throws Exception {
			final ExecutorService delegate = Executors.newFixedThreadPool(8);
			final ExecutorService submitters = Executors.newFixedThreadPool(8);
			try {
				final int submitterCount = 8;
				final int perSubmitter = 50;
				final AtomicInteger concurrentlyRunning = new AtomicInteger();
				final AtomicInteger maxConcurrentlyRunning = new AtomicInteger();
				final CountDownLatch done = new CountDownLatch(submitterCount * perSubmitter);
				final SerialCdcExecutor executor = createExecutor(delegate, failure -> {});
				final CyclicBarrier startTogether = new CyclicBarrier(submitterCount);

				for (int s = 0; s < submitterCount; s++) {
					submitters.execute(() -> {
						try {
							// all eight submitters must reach the barrier before any proceeds, so this bound
							// depends on the whole pool being scheduled - the one wait here most exposed to a
							// busy machine, and the one whose expiry would fail the test for no real reason
							startTogether.await(30, TimeUnit.SECONDS);
						} catch (Exception ex) {
							throw new IllegalStateException(ex);
						}
						for (int i = 0; i < perSubmitter; i++) {
							executor.execute(() -> {
								final int running = concurrentlyRunning.incrementAndGet();
								maxConcurrentlyRunning.accumulateAndGet(running, Math::max);
								try {
									// widen the window in which an overlap would be observable
									Thread.sleep(1);
								} catch (InterruptedException ex) {
									Thread.currentThread().interrupt();
								} finally {
									concurrentlyRunning.decrementAndGet();
									done.countDown();
								}
							});
						}
					});
				}

				assertTrue(done.await(30, TimeUnit.SECONDS), "the tasks did not finish in time");
				assertEquals(
					1, maxConcurrentlyRunning.get(),
					"the single-active-drain guard must keep execution serial regardless of delegate width"
				);
			} finally {
				submitters.shutdownNow();
				delegate.shutdownNow();
			}
		}

		@Test
		@DisplayName("Picks up a task enqueued while a drain is already active")
		void shouldPickUpTaskEnqueuedWhileADrainIsActive() throws Exception {
			// A submission that arrives while a drain owns the guard gets no drain of its own - `scheduleDrain`
			// loses the CAS and returns - so the in-flight drain loop has to notice it. That is what this pins.
			//
			// It deliberately does NOT cover the narrower window between the loop finding the queue empty and
			// releasing the guard, which the re-check in `drain()`'s `finally` exists for: reaching that window
			// requires the enqueue to land between two adjacent statements, and no test in this module can
			// place it there - verified by removing the re-check, after which this whole module still passed.
			// That window is covered by `LongRunningSerialCdcExecutorStressTest`, which sweeps the timing
			// instead of fixing it and therefore belongs in the long-running module rather than here.
			final ExecutorService delegate = Executors.newFixedThreadPool(2);
			try {
				final SerialCdcExecutor executor = createExecutor(delegate, failure -> {});
				final CountDownLatch firstRunning = new CountDownLatch(1);
				final CountDownLatch releaseFirst = new CountDownLatch(1);
				final CountDownLatch secondRan = new CountDownLatch(1);

				executor.execute(() -> {
					firstRunning.countDown();
					try {
						releaseFirst.await(5, TimeUnit.SECONDS);
					} catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
				});

				assertTrue(firstRunning.await(5, TimeUnit.SECONDS), "the first task never started");
				// enqueued while the drain is still active and therefore owns the guard
				executor.execute(secondRan::countDown);
				releaseFirst.countDown();

				assertTrue(
					secondRan.await(5, TimeUnit.SECONDS),
					"a task enqueued while a drain was active must still be picked up"
				);
			} finally {
				delegate.shutdownNow();
			}
		}

		@Test
		@DisplayName("Keeps draining after a task throws")
		void shouldKeepDrainingAfterATaskThrows() throws Exception {
			final ExecutorService delegate = Executors.newSingleThreadExecutor();
			try {
				final SerialCdcExecutor executor = createExecutor(delegate, failure -> {});
				final CountDownLatch afterThrow = new CountDownLatch(1);

				executor.execute(() -> {
					throw new IllegalStateException("consumer callback blew up");
				});
				executor.execute(afterThrow::countDown);

				assertTrue(
					afterThrow.await(5, TimeUnit.SECONDS),
					"one failing consumer callback must not strand the callbacks queued behind it"
				);
			} finally {
				delegate.shutdownNow();
			}
		}

		@Test
		@DisplayName("Never runs a task on the submitting thread")
		void shouldNeverRunATaskOnTheSubmittingThread() throws Exception {
			final ExecutorService delegate = Executors.newSingleThreadExecutor();
			try {
				final SerialCdcExecutor executor = createExecutor(delegate, failure -> {});
				final AtomicReference<Thread> taskThread = new AtomicReference<>();
				final CountDownLatch ran = new CountDownLatch(1);

				executor.execute(() -> {
					taskThread.set(Thread.currentThread());
					ran.countDown();
				});

				assertTrue(ran.await(5, TimeUnit.SECONDS), "the task did not run in time");
				assertNotSame(
					Thread.currentThread(), taskThread.get(),
					"running on the submitter is the event-loop capture this class exists to prevent"
				);
			} finally {
				delegate.shutdownNow();
			}
		}
	}

	@Nested
	@DisplayName("when the delegate refuses the drain")
	class RefusingDelegate {

		@Test
		@DisplayName("Terminates the subscription instead of silently swallowing the callback")
		void shouldTerminateTheSubscriptionWhenTheDrainIsRefused() {
			final AtomicReference<Throwable> reported = new AtomicReference<>();
			final AtomicBoolean ran = new AtomicBoolean();
			final EvitaClientPoolSaturatedException thrownByThePool = new EvitaClientPoolSaturatedException(4, 100);
			final SerialCdcExecutor executor = createExecutor(
				new RejectingExecutorService(() -> thrownByThePool),
				reported::set
			);

			executor.execute(() -> ran.set(true));

			assertNotNull(
				reported.get(),
				"a refused drain must be reported so the owner can fail the subscription - a heartbeat that " +
					"silently resumes after a gap reads as missed *server* heartbeats"
			);
			assertInstanceOf(EvitaClientPoolSaturatedException.class, reported.get());
			// The pool throws two different exceptions: a saturation one naming `maxThreadCount`/`queueSize`,
			// and a shutdown one that names nothing. Re-creating either here would pick the wrong message half
			// the time - and the saturation message is the only one an overloaded operator can act on.
			assertSame(
				thrownByThePool,
				reported.get(),
				"the owner must be handed the refusal the pool threw, not a synthesized stand-in"
			);
			assertTrue(
				reported.get().getMessage().contains("saturated"),
				"the reported cause must keep the saturation wording, not the shutdown wording: " +
					reported.get().getMessage()
			);
			assertFalse(ran.get(), "the callback must not run on the submitting thread");
		}

		@Test
		@DisplayName("Reports the failure only once, however many callbacks follow")
		void shouldReportTheFailureOnlyOnce() {
			final AtomicInteger reportCount = new AtomicInteger();
			final SerialCdcExecutor executor = createExecutor(
				new RejectingExecutorService(() -> new EvitaClientPoolSaturatedException(4, 100)),
				failure -> reportCount.incrementAndGet()
			);

			for (int i = 0; i < 100; i++) {
				executor.execute(() -> {});
			}

			assertEquals(
				1, reportCount.get(),
				"termination is one-way - a subscription must not be failed repeatedly by the callbacks " +
					"still arriving on the inbound thread"
			);
		}

		@Test
		@DisplayName("Never throws at the submission site")
		void shouldNeverThrowAtTheSubmissionSite() {
			// the caller is a gRPC inbound callback, which has no defined error path
			final SerialCdcExecutor executor = createExecutor(
				new RejectingExecutorService(() -> new EvitaClientPoolSaturatedException(4, 100)),
				failure -> {
					throw new IllegalStateException("even a failing termination handler must be contained");
				}
			);

			executor.execute(() -> {});
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Test fixtures
	// ---------------------------------------------------------------------------------------------

	/**
	 * Builds an executor under test with a short description and the given failure handler.
	 *
	 * @param delegate          executor the drain runs on
	 * @param onDispatchFailure invoked when the delegate refuses the drain
	 * @return the executor under test
	 */
	@Nonnull
	private static SerialCdcExecutor createExecutor(
		@Nonnull java.util.concurrent.Executor delegate,
		@Nonnull java.util.function.Consumer<Throwable> onDispatchFailure
	) {
		return new SerialCdcExecutor(delegate, "test callback", onDispatchFailure);
	}

	/**
	 * Executor that refuses every submission with a caller-supplied throwable, standing in for a saturated
	 * capture callback executor.
	 */
	private static final class RejectingExecutorService extends AbstractExecutorService {
		private final Supplier<RuntimeException> rejection;
		private volatile boolean shutdown;

		RejectingExecutorService(@Nonnull Supplier<RuntimeException> rejection) {
			this.rejection = rejection;
		}

		@Override
		public void shutdown() {
			this.shutdown = true;
		}

		@Nonnull
		@Override
		public List<Runnable> shutdownNow() {
			this.shutdown = true;
			return Collections.emptyList();
		}

		@Override
		public boolean isShutdown() {
			return this.shutdown;
		}

		@Override
		public boolean isTerminated() {
			return this.shutdown;
		}

		@Override
		public boolean awaitTermination(long timeout, @Nonnull TimeUnit unit) {
			return true;
		}

		@Override
		public void execute(@Nonnull Runnable command) {
			throw this.rejection.get();
		}
	}

}
