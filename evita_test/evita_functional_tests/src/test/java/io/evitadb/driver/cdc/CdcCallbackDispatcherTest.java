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
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.DRIVER;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.STREAM;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
 * publisher-level tests all use a synchronous executor and therefore cannot state "handed to the pool rather
 * than run here" at all.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Change data capture callback dispatcher")
@Tag(DRIVER)
@Tag(GRPC)
@Tag(CDC)
@Tag(STREAM)
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

				final boolean dispatched = CdcCallbackDispatcher.dispatch(
					executor,
					() -> {
						callbackThread.set(Thread.currentThread());
						ran.countDown();
					},
					"test callback"
				);

				assertTrue(dispatched, "an accepting pool must report a successful hand-off");
				assertTrue(ran.await(5, TimeUnit.SECONDS), "the callback did not run in time");
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
	@DisplayName("when the pool refuses the task")
	class RefusingPool {

		@Test
		@DisplayName("Still runs the callback, on a daemon thread that is not the caller")
		void shouldRunCallbackOffTheCallingThreadWhenTheTaskIsRefused() throws InterruptedException {
			final CountDownLatch ran = new CountDownLatch(1);
			final AtomicReference<Thread> callbackThread = new AtomicReference<>();

			final boolean dispatched = CdcCallbackDispatcher.dispatch(
				new RejectingExecutorService(() -> new EvitaClientPoolSaturatedException(4, 100)),
				() -> {
					callbackThread.set(Thread.currentThread());
					ran.countDown();
				},
				"test callback"
			);

			assertTrue(dispatched, "a refused callback must still be handed to a rescue thread");
			assertTrue(ran.await(5, TimeUnit.SECONDS), "the rescued callback did not run in time");
			assertNotSame(
				Thread.currentThread(),
				callbackThread.get(),
				"the rescue path must never degrade into running the callback on the caller - that is " +
					"precisely the CallerRunsPolicy behaviour that captured the event loop"
			);
			// a non-daemon rescue thread would keep the JVM alive after the client is done with it
			assertTrue(callbackThread.get().isDaemon(), "the rescue thread must be a daemon thread");
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
		@DisplayName("Contains a throwing callback instead of letting it escape the rescue thread")
		void shouldContainThrowingCallbackOnTheRescueThread() throws InterruptedException {
			// consumer code that throws must not kill a driver thread nor bypass logging onto stderr
			final CountDownLatch ran = new CountDownLatch(1);

			final boolean dispatched = CdcCallbackDispatcher.dispatch(
				new RejectingExecutorService(() -> new EvitaClientPoolSaturatedException(4, 100)),
				() -> {
					ran.countDown();
					throw new IllegalStateException("consumer callback blew up");
				},
				"test callback"
			);

			assertTrue(dispatched, "a refused callback must still be handed to a rescue thread");
			assertTrue(ran.await(5, TimeUnit.SECONDS), "the rescued callback did not run in time");
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
