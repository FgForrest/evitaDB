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

package io.evitadb.driver;

import io.evitadb.driver.exception.EvitaClientPoolSaturatedException;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.evitadb.test.TestTags.DRIVER;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the fail-fast rejection policy of the shared evitaDB client thread pool (issue #1387).
 *
 * The pool used to run rejected tasks on the submitting thread (`ThreadPoolExecutor.CallerRunsPolicy`). When
 * that thread was the Armeria event loop, driver work — including work that blocks waiting for an inbound
 * message — ended up on the only thread able to read the connection, killing it outright. These tests pin the
 * replacement behaviour: the submission fails, it fails with a driver exception, and that exception is
 * deliberately *not* a {@link RejectedExecutionException}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("evitaDB client thread pool rejection policy")
@Tag(DRIVER)
@Tag(GRPC)
@Tag(MANAGEMENT)
class EvitaClientRejectingExecutorHandlerTest {
	private static final int MAX_THREAD_COUNT = 1;
	private static final int QUEUE_SIZE = 1;

	@Nested
	@DisplayName("on a saturated pool")
	class SaturatedPool {

		@Test
		@DisplayName("Rejects the submission instead of running it on the calling thread")
		void shouldRejectSubmissionInsteadOfRunningItOnCallingThread() throws InterruptedException {
			final CountDownLatch release = new CountDownLatch(1);
			final CountDownLatch started = new CountDownLatch(1);
			final AtomicBoolean ranOnCallingThread = new AtomicBoolean(false);
			final Thread callingThread = Thread.currentThread();
			final ThreadPoolExecutor executor = createExecutor();

			try {
				// occupy the single worker thread ...
				executor.execute(
					() -> {
						started.countDown();
						awaitQuietly(release);
					}
				);
				assertTrue(started.await(5, TimeUnit.SECONDS), "the pool worker did not start in time");
				// ... and fill the single backlog slot
				executor.execute(() -> {});

				// the pool is now saturated — the third task must be refused, NOT executed here
				final EvitaClientPoolSaturatedException thrown = assertThrows(
					EvitaClientPoolSaturatedException.class,
					() -> executor.execute(
						() -> ranOnCallingThread.set(Thread.currentThread() == callingThread)
					)
				);
				assertFalse(
					ranOnCallingThread.get(),
					"a rejected task must never be executed on the submitting thread - that is the " +
						"CallerRunsPolicy behaviour that captured the Armeria event loop"
				);
				// the message must name both knobs an operator can turn
				assertTrue(
					thrown.getPrivateMessage().contains("maxThreadCount")
						&& thrown.getPrivateMessage().contains("queueSize"),
					"the message must name both configurable knobs, was: " + thrown.getPrivateMessage()
				);
			} finally {
				release.countDown();
				executor.shutdownNow();
			}
		}

	}

	@Nested
	@DisplayName("on the exception it throws")
	class ExceptionContract {

		@Test
		@DisplayName("Stays outside the RejectedExecutionException hierarchy so consumers cannot swallow it")
		void shouldKeepSaturationExceptionOutsideRejectedExecutionHierarchy() {
			// a consumer catching RejectedExecutionException around submissions to its *own* scheduler must
			// not accidentally swallow driver-side pool saturation as a benign shutdown signal
			final EvitaClientPoolSaturatedException exception = new EvitaClientPoolSaturatedException(
				MAX_THREAD_COUNT, QUEUE_SIZE
			);
			assertFalse(
				RejectedExecutionException.class.isAssignableFrom(EvitaClientPoolSaturatedException.class),
				"the driver exception must not be catchable as `RejectedExecutionException`"
			);
			assertInstanceOf(
				EvitaInvalidUsageException.class,
				exception,
				"the driver exception must stay within the client exception family"
			);
		}
	}

	@Nested
	@DisplayName("on a pool that is shutting down")
	class ShuttingDownPool {

		@Test
		@DisplayName("Rejects the submission so the caller completes the cleanup itself")
		void shouldRejectSubmissionWhenPoolIsShutDown() {
			final ThreadPoolExecutor executor = createExecutor();
			executor.shutdown();

			// `CallerRunsPolicy` silently DISCARDED post-shutdown submissions; failing instead is what lets
			// the change-capture teardown paths notice and finish their cleanup in place
			assertThrows(
				EvitaClientPoolSaturatedException.class,
				() -> executor.execute(() -> {})
			);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Test fixtures
	// ---------------------------------------------------------------------------------------------

	/**
	 * Builds a deliberately tiny pool (one thread, one backlog slot) so saturation is reachable with two tasks.
	 *
	 * @return the executor guarded by {@link EvitaClientRejectingExecutorHandler}
	 */
	private static ThreadPoolExecutor createExecutor() {
		return new ThreadPoolExecutor(
			MAX_THREAD_COUNT, MAX_THREAD_COUNT,
			0L, TimeUnit.MILLISECONDS,
			new LinkedBlockingQueue<>(QUEUE_SIZE),
			r -> {
				final Thread thread = new Thread(r, "test-evita-client-pool");
				thread.setDaemon(true);
				return thread;
			},
			new EvitaClientRejectingExecutorHandler(MAX_THREAD_COUNT, QUEUE_SIZE)
		);
	}

	/**
	 * Awaits the latch, restoring the interrupt flag rather than propagating a checked exception into
	 * a {@link Runnable}.
	 *
	 * @param latch the latch to await
	 */
	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(10, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
