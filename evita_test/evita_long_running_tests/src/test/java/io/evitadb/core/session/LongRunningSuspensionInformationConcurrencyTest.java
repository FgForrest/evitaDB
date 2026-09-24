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

package io.evitadb.core.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeoutException;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SESSION;
import static io.evitadb.test.TestTags.SLOW;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sweeps the census {@link SuspensionInformation} keeps of the sessions a quiesce took away from their owners, under
 * the concurrency it is actually written from.
 *
 * **Three threads write it, and they are not the same thread.** The drain's forced-close lambda
 * ({@code SessionRegistry#closeAllActiveSessionsAndSuspend}) is postponed by {@code EvitaSessionProxy} and then runs
 * **on the session's own thread** when its in-flight method returns - the very cross-thread execution the drain's
 * own {@code ConcurrentLinkedQueue} was introduced for; {@code EvitaSession#goLiveAndCloseWithProgress} adds its own
 * id from the go-live caller's thread; and {@code Evita#wasSessionForcefullyClosedForCatalog} reads the census from
 * request threads, which is where a gRPC client's "my session vanished" answer comes from. A warm-up catalog admits
 * one session at a time, but the other callers of the same drain - {@code Evita#closeAllSessions}, deactivate, drop
 * and rename - quiesce ALIVE catalogs holding many, so several deferred closes finish on several threads at once.
 *
 * **Why a sweep rather than a deterministic test.** The interleaving is two threads inside {@code HashMap#put} on
 * the same table; there is no seam between the statements that race, so `.claude/rules/testing.md` sends it here
 * rather than into the fast loop.
 *
 * **Calibration (measured, not estimated; 24-core Linux box, OpenJDK 17, otherwise idle).** The counterfactual is
 * the plain {@link java.util.HashSet} this class was written against: with it, {@value #ROUNDS} rounds of
 * {@value #CONTENDERS} threads adding {@value #IDS_PER_CONTENDER} distinct ids each lost at least one id in **365**
 * rounds out of 1 000 - the first loss landing in round 0 - and the whole sweep cost about a second. The size
 * matters in both directions: ten times as many ids per contender lost ids in **every** round but was also measured
 * to **hang outright** (a thread stuck inside {@code HashMap#put}, one run in two), which is why this sweep is
 * deliberately small enough never to have been observed to hang and bounds every round anyway.
 *
 * **How it goes blunt.** A cheaper {@code add} narrows the window just as effectively as a safer one widens it, so
 * a sweep that stops observing losses has not necessarily been fixed - re-measure before believing it, and raise
 * {@link #ROUNDS} or {@link #CONTENDERS} until it reaches the window again.
 *
 * Run it with:
 *
 * ```
 * mvn -pl evita_test/evita_functional_tests,evita_test/evita_long_running_tests test -P longRunning \
 *     -Dtest=LongRunningSuspensionInformationConcurrencyTest -Dsurefire.failIfNoSpecifiedTests=false
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(SLOW)
@Tag(ENGINE)
@Tag(SESSION)
@DisplayName("Long-running forcefully-closed session census stress test")
class LongRunningSuspensionInformationConcurrencyTest {
	/**
	 * Number of independent races. Sized so that the sweep as a whole is decisive rather than probabilistic: at the
	 * measured per-round loss rate of roughly a third, a thousand rounds miss the window with a probability below
	 * 10^-190.
	 */
	private static final int ROUNDS = 1_000;
	/**
	 * How many threads write the census at once in each round. Eight, because the callers that produce concurrent
	 * adds are the deferred closes of an ALIVE catalog's sessions, and they finish independently.
	 */
	private static final int CONTENDERS = 8;
	/**
	 * How many distinct session ids each contender adds. Kept small on purpose - see the class javadoc: ten times
	 * this was measured to wedge a thread inside the unsynchronised map rather than merely lose an entry.
	 */
	private static final int IDS_PER_CONTENDER = 200;
	/**
	 * Bound on a single round. A healthy round finishes in microseconds; this only has to exceed scheduling noise
	 * on a loaded box - and it doubles as the guard that keeps a wedged contender from hanging the whole suite.
	 */
	private static final long ROUND_TIMEOUT_SECONDS = 30;

	@Test
	@DisplayName("Keeps every forcefully closed session id that concurrent closes recorded")
	void shouldKeepEverySessionIdRecordedByConcurrentCloses() throws Exception {
		int lossyRounds = 0;
		int firstLossyRound = -1;
		for (int round = 0; round < ROUNDS; round++) {
			final int missing = raceOneRound();
			if (missing != 0) {
				lossyRounds++;
				if (firstLossyRound < 0) {
					firstLossyRound = round;
				}
			}
		}

		// Not one round may lose an id: the census is the whole of the answer
		// `Evita#wasSessionForcefullyClosedForCatalog` gives a client whose session vanished, and a client whose id
		// was dropped hears "you closed it yourself" instead. The counterfactual is the plain `HashSet` this class
		// was written against - see the calibration in the class javadoc.
		final int observedLossyRounds = lossyRounds;
		final int observedFirstLossyRound = firstLossyRound;
		assertEquals(
			0, observedLossyRounds,
			() -> "the census lost ids under concurrent adds (lossy rounds: " + observedLossyRounds + " of " +
				ROUNDS + ", first at " + observedFirstLossyRound + ")"
		);
	}

	/**
	 * Runs one race: {@value #CONTENDERS} threads are released from a barrier straight into
	 * {@link SuspensionInformation#addForcefullyClosedSession(UUID)}, each adding {@value #IDS_PER_CONTENDER} ids
	 * nobody else adds, and the census is then read back on this thread alone.
	 *
	 * A round that never finishes counts as the whole round lost rather than hanging the suite: an unsynchronised
	 * map can wedge a writer as readily as it can drop an entry, and both are the same defect.
	 *
	 * @return how many of the ids added are no longer visible in the census
	 * @throws InterruptedException when this thread is interrupted while waiting the round out
	 */
	private static int raceOneRound() throws InterruptedException {
		final int totalIds = CONTENDERS * IDS_PER_CONTENDER;
		final SuspensionInformation census = new SuspensionInformation(totalIds);
		final UUID[][] ids = new UUID[CONTENDERS][IDS_PER_CONTENDER];
		for (int contender = 0; contender < CONTENDERS; contender++) {
			for (int id = 0; id < IDS_PER_CONTENDER; id++) {
				ids[contender][id] = UUID.randomUUID();
			}
		}

		final CyclicBarrier startLine = new CyclicBarrier(CONTENDERS);
		final CountDownLatch finished = new CountDownLatch(CONTENDERS);
		for (int contender = 0; contender < CONTENDERS; contender++) {
			final UUID[] mine = ids[contender];
			final Thread thread = new Thread(
				() -> {
					try {
						startLine.await(ROUND_TIMEOUT_SECONDS, SECONDS);
						for (UUID id : mine) {
							census.addForcefullyClosedSession(id);
						}
					} catch (BrokenBarrierException | InterruptedException | TimeoutException ex) {
						// a contender that never reached the start line adds nothing, and the round then counts
						// every id it owed as missing - the same verdict a wedged writer earns
						Thread.currentThread().interrupt();
					} finally {
						finished.countDown();
					}
				},
				"census-contender"
			);
			// daemon, so that a contender wedged inside the unsynchronised map cannot keep the surefire fork alive
			thread.setDaemon(true);
			thread.start();
		}

		if (!finished.await(ROUND_TIMEOUT_SECONDS, SECONDS)) {
			return totalIds;
		}
		return countMissing(census, ids);
	}

	/**
	 * Reads the census back and counts the ids that are no longer in it. A read that throws counts the id as
	 * missing too - a corrupted map answers a lookup by failing just as readily as by saying no.
	 *
	 * @param census the census the round wrote
	 * @param ids    every id the round added, by contender
	 * @return how many of them are no longer visible
	 */
	private static int countMissing(@Nonnull SuspensionInformation census, @Nonnull UUID[][] ids) {
		int missing = 0;
		for (UUID[] contenderIds : ids) {
			for (UUID id : contenderIds) {
				try {
					if (!census.contains(id)) {
						missing++;
					}
				} catch (Throwable ex) {
					missing++;
				}
			}
		}
		return missing;
	}
}
