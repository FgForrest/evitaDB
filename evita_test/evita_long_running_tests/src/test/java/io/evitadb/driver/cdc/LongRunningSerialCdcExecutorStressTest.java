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

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.DRIVER;
import static io.evitadb.test.TestTags.SLOW;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Stress verification of the one invariant in {@link SerialCdcExecutor} that no deterministic test can
 * reach: the re-check in `drain()`'s `finally` block.
 *
 * A submission that arrives *between* the drain loop finding the queue empty and the drain releasing the
 * `draining` guard belongs to nobody — its own `scheduleDrain` loses the CAS to the drain that is already
 * finishing, and the drain has stopped polling. Without the re-check that callback is stranded until some
 * later submission happens to rescue it, and the last one of a subscription is never rescued at all: a
 * consumer silently loses its final capture or, worse, its terminal notification.
 *
 * That window is two adjacent statements wide. Nothing can place a submission inside it on demand, so
 * `SerialCdcExecutorTest` in the functional module deliberately does not try — it pins the neighbouring,
 * reachable case (a task enqueued while the drain is still *running*) and says so. This test covers the
 * remainder the only way it can be covered: by sweeping the arrival time of a competing submission across
 * the whole drain start-up window, over enough rounds that the narrow case is hit repeatedly.
 *
 * **It lives here, disabled, on purpose.** A probabilistic test in the fast loop is a test that fails once
 * every few hundred CI runs and trains everyone to press re-run; the same test on a quiet machine, run
 * deliberately, is real evidence. Enable it after touching {@link SerialCdcExecutor}'s drain loop.
 *
 * **Calibration (measured, not estimated).** With the re-check removed, the first strand appears around
 * round 20 000 and the run hits its 10-strand cap by round 27 000, failing in ~10 s. With the re-check in
 * place all 200 000 rounds pass in ~2 s. Re-measure after changing the drain loop: if the counterfactual
 * stops failing, the sweep no longer reaches the window and this test has quietly become decorative —
 * widen `SPIN_SWEEP` or raise `ROUNDS` until it fails again before trusting a green run.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@Tag(SLOW)
@Tag(DRIVER)
@Tag(CDC)
@Disabled("Probabilistic stress test - needs a quiet machine; enable manually after touching SerialCdcExecutor")
@DisplayName("Long-running serial CDC executor stress tests")
class LongRunningSerialCdcExecutorStressTest {

	/**
	 * Number of independent races. Each round is a fresh executor, so a strand is permanent within its
	 * round rather than being rescued by the next round's submission — which is what makes a strand
	 * observable at all.
	 */
	private static final int ROUNDS = 200_000;
	/**
	 * The competing submission is delayed by `round % SPIN_SWEEP` spin-waits, sweeping its arrival across
	 * the drain's start-up so that some rounds land inside the guard-release window rather than before it.
	 * A fixed delay would either always arrive too early (picked up by the drain loop, proving nothing) or
	 * always too late (a fresh drain, also proving nothing).
	 */
	private static final int SPIN_SWEEP = 512;
	/**
	 * How long a round waits before declaring the callback stranded. Both callbacks are empty, so a healthy
	 * round completes in microseconds; this bound only has to exceed scheduling noise.
	 */
	private static final long ROUND_TIMEOUT_MS = 1_000L;
	/**
	 * Stop after this many strands — the count is diagnostic, and a broken drain strands often enough that
	 * running all rounds would only add `ROUND_TIMEOUT_MS` per strand to an already-failing test.
	 */
	private static final int MAX_OBSERVED_STRANDS = 10;

	@Test
	@DisplayName("Never strands a callback submitted while the drain guard is being released")
	void shouldNeverStrandACallbackSubmittedWhileTheGuardIsBeingReleased() throws Exception {
		final ExecutorService drainPool = Executors.newFixedThreadPool(4, daemonFactory("stress-drain"));
		final ExecutorService contender = Executors.newSingleThreadExecutor(daemonFactory("stress-contender"));
		try {
			int stranded = 0;
			int completedRounds = 0;
			for (int round = 0; round < ROUNDS && stranded < MAX_OBSERVED_STRANDS; round++) {
				final CountDownLatch bothRan = new CountDownLatch(2);
				final SerialCdcExecutor executor = new SerialCdcExecutor(
					drainPool, "stress callback", failure -> {}
				);
				final int spins = round % SPIN_SWEEP;
				final Future<?> competing = contender.submit(() -> {
					for (int spin = 0; spin < spins; spin++) {
						Thread.onSpinWait();
					}
					executor.execute(bothRan::countDown);
				});

				executor.execute(bothRan::countDown);

				// make sure the competing submission actually happened before judging the round
				competing.get(30, TimeUnit.SECONDS);
				if (!bothRan.await(ROUND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
					stranded++;
					log.warn(
						"Round {} stranded a callback ({} spin(s) of delay); {} of {} callbacks ran.",
						round, spins, 2 - bothRan.getCount(), 2
					);
				}
				completedRounds = round + 1;
			}

			assertEquals(
				0, stranded,
				"a callback submitted while the drain guard was being released was never run - the re-check " +
					"in SerialCdcExecutor.drain()'s finally block is what rescues it (" + stranded +
					" strand(s) over " + completedRounds + " rounds)"
			);
		} finally {
			contender.shutdownNow();
			drainPool.shutdownNow();
		}
	}

	/**
	 * Builds a daemon thread factory so a hung round can never keep the surefire JVM alive.
	 *
	 * @param namePrefix prefix of the created thread names, to keep a thread dump readable
	 * @return thread factory producing daemon threads, never NULL
	 */
	@Nonnull
	private static ThreadFactory daemonFactory(@Nonnull String namePrefix) {
		return runnable -> {
			final Thread thread = new Thread(runnable, namePrefix);
			thread.setDaemon(true);
			return thread;
		};
	}

}
