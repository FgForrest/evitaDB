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

package io.evitadb.core.session.task;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.core.session.EvitaInternalSessionContract;

import javax.annotation.Nonnull;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lets tests drive a {@link SessionKiller} verdict without depending on timing. A session is made "inactive" by
 * waiting until it reports an inactivity past the timeout - a lower bound a slow machine only prolongs - and kept
 * "active" by a real call held open on it, which the killer must spare however long its pass takes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class SessionKillerTestSupport {
	/**
	 * Upper bound for every wait in this class, so that a defect surfaces as a failure rather than a hang.
	 */
	private static final long AWAIT_TIMEOUT_SECONDS = 30;
	/**
	 * Interval between two inactivity probes while waiting for a session to age past the timeout.
	 */
	private static final long POLL_INTERVAL_MILLIS = 50;

	private SessionKillerTestSupport() {
	}

	/**
	 * Returns the internal contract of the session, which exposes the inactivity probes the killer relies on.
	 * Those probes are answered by the session proxy itself and do not count as session activity.
	 *
	 * @param session the session created by the evitaDB instance
	 * @return the same session viewed through its internal contract
	 */
	@Nonnull
	public static EvitaInternalSessionContract internal(@Nonnull EvitaSessionContract session) {
		return assertInstanceOf(EvitaInternalSessionContract.class, session);
	}

	/**
	 * Waits until the session reports an inactivity of at least the timeout. The inactivity is measured from the
	 * last call that entered or left the session, so the wait is a lower bound - it never ends early, and a slow
	 * machine only prolongs it.
	 *
	 * @param session                  the session to wait for
	 * @param inactivityTimeoutSeconds the inactivity timeout the killer under test enforces
	 * @throws InterruptedException when the waiting thread is interrupted
	 */
	public static void awaitInactivityPastTimeout(
		@Nonnull EvitaInternalSessionContract session,
		int inactivityTimeoutSeconds
	) throws InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_TIMEOUT_SECONDS);
		while (session.getInactivityDurationInSeconds() < inactivityTimeoutSeconds) {
			assertTrue(
				System.nanoTime() < deadline,
				"The session did not report inactivity past the timeout within " + AWAIT_TIMEOUT_SECONDS + "s."
			);
			Thread.sleep(POLL_INTERVAL_MILLIS);
		}
	}

	/**
	 * Waits until the session is old enough to be killed and verifies nothing keeps it alive, so the following
	 * {@link SessionKiller#run()} is expected to terminate it.
	 *
	 * @param session                  the session to wait for
	 * @param inactivityTimeoutSeconds the inactivity timeout the killer under test enforces
	 * @throws InterruptedException when the waiting thread is interrupted
	 */
	public static void awaitExpiry(
		@Nonnull EvitaInternalSessionContract session,
		int inactivityTimeoutSeconds
	) throws InterruptedException {
		awaitInactivityPastTimeout(session, inactivityTimeoutSeconds);
		assertTrue(
			session.isInactiveAndIdle(inactivityTimeoutSeconds),
			"The session is expected to be inactive and idle - nothing is calling it."
		);
	}

	/**
	 * A real session call held open on a dedicated thread. The call goes through the session proxy, so the proxy
	 * counts it as in flight from the moment {@link #awaitEntered()} returns until {@link #release()} lets it
	 * finish. Before it returns, the call asks the session for its catalog version, which fails when the session
	 * was terminated while the call was held - {@link #awaitCompletion()} then reports that failure.
	 *
	 * The call is an `execute()`, which opens a transaction when the catalog is alive - a session held on an alive
	 * catalog must therefore be read-write.
	 */
	public static final class HeldInvocation implements AutoCloseable {
		/**
		 * Opened by the held call once it runs inside the session proxy.
		 */
		private final CountDownLatch entered = new CountDownLatch(1);
		/**
		 * Opened by the test to let the held call finish.
		 */
		private final CountDownLatch released = new CountDownLatch(1);
		/**
		 * The held call and its outcome.
		 */
		private final FutureTask<Long> call;
		/**
		 * The dedicated thread the held call runs on.
		 */
		private final Thread thread;

		/**
		 * Creates the held call for the session without starting it.
		 *
		 * @param session the session the call is made on
		 */
		private HeldInvocation(@Nonnull EvitaInternalSessionContract session) {
			this.call = new FutureTask<>(
				() -> session.execute(
					plainSession -> {
						this.entered.countDown();
						awaitLatch(this.released);
						// asserts the session is still active - a terminated session throws here
						return plainSession.getCatalogVersion();
					}
				)
			);
			this.thread = new Thread(this.call, "held-session-invocation");
			this.thread.setDaemon(true);
		}

		/**
		 * Starts a held call on the session on a dedicated thread.
		 *
		 * @param session the session the call is made on
		 * @return the started held call
		 */
		@Nonnull
		public static HeldInvocation start(@Nonnull EvitaInternalSessionContract session) {
			final HeldInvocation heldInvocation = new HeldInvocation(session);
			heldInvocation.thread.start();
			return heldInvocation;
		}

		/**
		 * Waits for the latch inside the held call, failing the call when it is not opened in time.
		 *
		 * @param latch the latch to wait for
		 */
		private static void awaitLatch(@Nonnull CountDownLatch latch) {
			try {
				if (!latch.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
					throw new IllegalStateException("The held call was not released in time.");
				}
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("The held call was interrupted.", ex);
			}
		}

		/**
		 * Waits until the held call runs inside the session proxy and is therefore counted as in flight.
		 *
		 * @throws InterruptedException when the waiting thread is interrupted
		 */
		public void awaitEntered() throws InterruptedException {
			assertTrue(
				this.entered.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
				"The held call did not enter the session in time."
			);
		}

		/**
		 * Lets the held call finish.
		 */
		public void release() {
			this.released.countDown();
		}

		/**
		 * Waits for the held call to finish and verifies it succeeded.
		 *
		 * @throws InterruptedException when the waiting thread is interrupted
		 * @throws TimeoutException     when the held call does not finish in time
		 */
		public void awaitCompletion() throws InterruptedException, TimeoutException {
			try {
				this.call.get(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			} catch (ExecutionException ex) {
				throw new AssertionError(
					"The held call failed - the session was terminated while the call was in flight.",
					ex.getCause()
				);
			}
		}

		/**
		 * Releases the held call and waits for its thread, so a failed test leaves no thread behind.
		 *
		 * @throws InterruptedException when the waiting thread is interrupted
		 */
		@Override
		public void close() throws InterruptedException {
			this.released.countDown();
			this.thread.join(TimeUnit.SECONDS.toMillis(AWAIT_TIMEOUT_SECONDS));
		}
	}
}
