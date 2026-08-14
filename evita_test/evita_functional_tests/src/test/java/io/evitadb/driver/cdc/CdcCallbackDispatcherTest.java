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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.DRIVER;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.STREAM;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the single invariant {@link CdcCallbackDispatcher} exists to protect:
 *
 * **a consumer-supplied change data capture callback never runs on the thread that submitted it.**
 *
 * The submitting thread is an Armeria event loop — evitaDB's driver sets no gRPC call executor, so Armeria
 * dispatches inbound messages with a direct executor and consumer callbacks land on the I/O thread itself. A
 * callback that re-enters the driver (re-subscribing from `onError`, or from a `HeartBeatSensor` noticing a
 * stale stream) then blocks in `awaitAcknowledgement` waiting for a frame only that same thread could read,
 * which kills the whole HTTP/2 connection. See issue #1387.
 *
 * These tests cover both halves of the contract — the accepted path and the refused path — because the
 * publisher-level tests all use a synchronous executor and therefore cannot state "handed to the executor
 * rather than run here" at all.
 *
 * Note what the refused path asserts: the callback does **not** run, anywhere, and the refusal is reported so
 * the caller can terminate the subscription. An earlier revision moved refused callbacks onto a one-shot
 * rescue thread; that is deliberately gone, because nothing bounded those threads under sustained saturation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Change data capture callback dispatcher")
@Tag(DRIVER)
@Tag(GRPC)
@Tag(CDC)
@Tag(STREAM)
// Deliberately NOT @Isolated: nothing here reads a JVM-wide counter any more. The assertion that needed
// isolation was `shouldNotCreateThreadsWhenCallbacksAreRefused`, which inferred a rescue from
// ThreadMXBean#getTotalStartedThreadCount(); it is now `shouldNotRunAnyCallbackUnderSustainedRefusal`, which
// observes the callback *running* instead. That is both load-immune and strictly stronger - a thread count
// cannot see a rescue onto a cached pool or the common ForkJoin pool, so isolation would have bought a
// reliable measurement of the wrong thing.
class CdcCallbackDispatcherTest {

	@Nested
	@DisplayName("when the pool accepts the task")
	class AcceptingPool {

		@Test
		@DisplayName("Runs the callback on the pool, never on the calling thread")
		void shouldRunCallbackOnThePoolWhenTheTaskIsAccepted() throws InterruptedException {
			final ThreadPoolExecutor executor = createPool();
			try {
				final CountDownLatch ran = new CountDownLatch(1);
				final AtomicReference<Thread> callbackThread = new AtomicReference<>();

				final Throwable refusal = CdcCallbackDispatcher.dispatch(
					executor,
					() -> {
						callbackThread.set(Thread.currentThread());
						ran.countDown();
					},
					"test callback"
				);

				assertNull(refusal, "an accepting pool must report a successful hand-off");
				assertTrue(ran.await(30, TimeUnit.SECONDS), "the callback did not run in time");
				assertNotSame(
					Thread.currentThread(),
					callbackThread.get(),
					"the callback must run on the pool, not on the submitting thread"
				);
			} finally {
				executor.shutdownNow();
			}
		}
	}

	@Nested
	@DisplayName("when the executor refuses the task")
	class RefusingExecutor {

		@Test
		@DisplayName("Reports the refusal and never runs the callback on the caller")
		void shouldReportRefusalAndNotRunTheCallbackOnTheCaller() throws InterruptedException {
			final CountDownLatch ran = new CountDownLatch(1);
			final EvitaClientPoolSaturatedException thrownByThePool = new EvitaClientPoolSaturatedException(4, 100);

			final Throwable refusal = CdcCallbackDispatcher.dispatch(
				new RejectingExecutorService(() -> thrownByThePool),
				ran::countDown,
				"test callback"
			);

			assertNotNull(refusal, "a refused callback must be reported as refused so the caller can fail the " +
				"subscription rather than assume the consumer was notified");
			// The caller terminates the subscription with whatever comes back here, so this must be the pool's
			// own exception. A synthesized stand-in would tell a *saturated* operator the client is shutting
			// down, hiding the `maxThreadCount`/`queueSize` knobs that are the only actionable part of the report.
			assertSame(
				thrownByThePool,
				refusal,
				"the refusal must be surfaced as thrown, not reduced to a flag and re-created by the caller"
			);
			// The point of the whole class: refusal must NOT degrade into running the callback here. "Here" is
			// frequently the Armeria event loop, and running consumer code on it is precisely the
			// CallerRunsPolicy behaviour that captured the event loop in issue #1387.
			assertFalse(
				ran.await(250, TimeUnit.MILLISECONDS),
				"a refused callback must not run at all - least of all on the submitting thread"
			);
		}

		@Test
		@DisplayName("Never propagates the rejection to the caller")
		void shouldNotPropagateTheRejectionToTheCaller() {
			// the caller is frequently a gRPC inbound callback, which has no defined error path — the catch
			// must be broad enough to absorb a plain JDK rejection too, not only the driver's own type
			assertDoesNotThrow(
				() -> CdcCallbackDispatcher.dispatch(
					new RejectingExecutorService(() -> new RejectedExecutionException("pool is gone")),
					() -> {},
					"test callback"
				)
			);
		}

		@Test
		@DisplayName("Runs no callback at all under sustained refusal")
		void shouldNotRunAnyCallbackUnderSustainedRefusal() throws InterruptedException {
			// An earlier revision rescued each refused callback onto a fresh thread. Saturation arrives in
			// storms and the capture drain re-submits itself, so that traded a capture outage for unbounded
			// thread creation on an already struggling JVM. Refusal must now be free.
			//
			// What this adds over the sibling test above: that one proves a *single* refused callback does not
			// run *on the caller*. This one proves a *thousand* do not run *anywhere*. A per-refusal rescue
			// satisfies the first and violates the second, so only this shape catches the regression.
			//
			// A rescue is observed through the callback *running*, not through a thread count. That is the
			// definition rather than a proxy - a rescue that never runs the callback is not a rescue - and it
			// holds for every shape the regression could take: a fresh thread, a cached pool, or
			// `CompletableFuture.runAsync` on the common pool. A thread count cannot see the last two at all.
			//
			// It is also the only formulation that survives this suite. Tests run `<parallel>all</parallel>`
			// in a single reused fork, so `ThreadMXBean#getTotalStartedThreadCount` - which this assertion used
			// to read - counts every thread started by every sibling class booting an embedded server on
			// another test thread. Measured deltas of 102, 162, 174, 271 and 272 against a threshold of 100
			// made this test fail on a busy machine while the dispatcher was entirely innocent.
			final AtomicInteger ranCount = new AtomicInteger();
			final CountDownLatch ranAtLeastOnce = new CountDownLatch(1);

			final int refusals = 1_000;
			for (int i = 0; i < refusals; i++) {
				CdcCallbackDispatcher.dispatch(
					new RejectingExecutorService(() -> new EvitaClientPoolSaturatedException(4, 100)),
					() -> {
						ranCount.incrementAndGet();
						ranAtLeastOnce.countDown();
					},
					"test callback"
				);
			}

			// Negative wait: load can only delay a rescued callback, never invent one, so a short window cannot
			// fail spuriously and must not be lengthened. The old behaviour rescued *every* refusal, so 1 000
			// of them would have to miss this window unanimously to slip through.
			//
			// Calibration: adding `new Thread(guardedCallback).start()` to the refusal branch of
			// CdcCallbackDispatcher#dispatch fails this assertion in 191 ms - the very first rescued callback
			// trips the latch, with 59 ms to spare and 999 refusals still to come.
			assertFalse(
				ranAtLeastOnce.await(250, TimeUnit.MILLISECONDS),
				"a refused callback must not be rescued onto a thread of the dispatcher's own making"
			);
			assertEquals(
				0, ranCount.get(),
				() -> "refusing callbacks must not run them anywhere - " + ranCount.get() + " of " + refusals +
					" refused callbacks ran"
			);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Test fixtures
	// ---------------------------------------------------------------------------------------------

	/**
	 * Builds a single-thread pool with daemon workers so an assertion failure cannot hang the build.
	 *
	 * @return a pool that accepts submissions and runs them off the calling thread
	 */
	private static ThreadPoolExecutor createPool() {
		return new ThreadPoolExecutor(
			1, 1,
			0L, TimeUnit.MILLISECONDS,
			new LinkedBlockingQueue<>(16),
			r -> {
				final Thread thread = new Thread(r, "test-cdc-dispatcher-pool");
				thread.setDaemon(true);
				return thread;
			}
		);
	}

	/**
	 * Executor that refuses every submission with a caller-supplied throwable, standing in for a saturated
	 * or shut-down evitaDB client pool.
	 */
	private static final class RejectingExecutorService extends AbstractExecutorService {
		private final java.util.function.Supplier<RuntimeException> rejection;
		private volatile boolean shutdown;

		RejectingExecutorService(@Nonnull java.util.function.Supplier<RuntimeException> rejection) {
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
