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

import io.evitadb.api.SessionTraits;
import io.evitadb.api.exception.ConcurrentInitializationException;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.core.catalog.Catalog;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SESSION;
import static io.evitadb.test.TestTags.SLOW;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sweeps the race that {@link SessionRegistry}'s admission lock exists to close: a catalog that does not support
 * transactions must admit **at most one** session, and the check that enforces it is separated from the map write
 * that completes the registration by the whole of the session's construction.
 *
 * The window has no seam. It sits between two statements inside one private method, so nothing can place a second
 * caller in it on demand - which is why the deterministic half of this rule is pinned by
 * `SessionRegistryWarmUpAdmissionTest` in the functional module (sequential refusal, re-admission after a close, and
 * the guarantee that the ALIVE path is not serialised) and the racing half is swept here instead, per
 * `.claude/rules/testing.md`.
 *
 * Each round releases {@value #CONTENDERS} threads from a {@link CyclicBarrier} straight into
 * {@link SessionRegistry#addSession(boolean, java.util.function.Supplier)} with {@code transactional = false}.
 * Exactly one must be admitted and every other one must be refused with {@link ConcurrentInitializationException}.
 * The winner is closed before the next round, so every round starts from an empty registry and a violation is
 * attributable to that round alone.
 *
 * **Calibration (measured, not estimated).** With {@code exclusiveAdmissionLock} removed from
 * {@link SessionRegistry#addSession(boolean, java.util.function.Supplier)} - the refusal check left where it was,
 * unguarded - the counterfactual admitted **two** sessions in **round 0**, the very first round, on each of five
 * consecutive runs. With the lock in place all {@value #ROUNDS} rounds pass, in 4 443 / 2 909 / 2 808 ms over three
 * consecutive runs (the first pays the JIT warm-up). Measured on a 24-core Linux box, OpenJDK 17.0.20, otherwise
 * idle.
 *
 * **What the sweep actually measures, and how it goes blunt.** No session is constructed here - the supplier hands
 * back a mock built before the loop - so the window swept is what `registerNewSession` does between the refusal
 * check and the map write: the proxy, the session-opened event, the version pin taken through the catalog supplier,
 * and the publication itself. **Two** things therefore decalibrate this test, and both look like improvements:
 * making `registerNewSession` cheaper, and making *this fixture* cheaper - a leaner catalog supplier or leaner
 * session stubs narrow the window just as effectively. Re-measure after either. A counterfactual that no longer
 * fails means the sweep has stopped reaching the window, not that the code got safer; widen it by raising
 * {@link #CONTENDERS} or {@link #ROUNDS} until it fails again, and record the new numbers here and at the guarded
 * code.
 *
 * **Building the counterfactual without touching the shared source.** Do not edit
 * `SessionRegistry.java` in place - `.claude/rules/testing.md` warns that a snapshot-mutate-restore harness
 * silently erases whatever a concurrent agent wrote to that file in the window. Copy the file to a scratch
 * directory, remove the lock there, compile the copy with `javac --release 17` against this module's test
 * classpath (`mvn -pl evita_test/evita_long_running_tests dependency:build-classpath -Dmdep.includeScope=test`),
 * and **prepend** the output directory to that classpath so it shadows the installed engine class. Then drive this
 * test class from a small `main` in the same package. Run the green side with:
 * <pre>
 * mvn -pl evita_test/evita_functional_tests,evita_test/evita_long_running_tests test -P longRunning \
 *     -Dtest=LongRunningSessionRegistryWarmUpAdmissionTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@Tag(SLOW)
@Tag(ENGINE)
@Tag(SESSION)
@DisplayName("Long-running single-session admission stress test")
class LongRunningSessionRegistryWarmUpAdmissionTest {
	/**
	 * Number of independent races. The window is wide - it spans the session's construction - so a broken admission
	 * fails almost immediately; the rounds are there to keep a *working* admission under sustained contention rather
	 * than to wait for a rare hit.
	 */
	private static final int ROUNDS = 50_000;
	/**
	 * How many threads contend for the single admission slot in each round. Two is the minimum that can expose the
	 * check-then-act; more only widens the odds of an overlap, and the assertion holds for any number.
	 */
	private static final int CONTENDERS = 2;
	/**
	 * Bound on a single round. Every contender is released from the same barrier and the registration itself is
	 * lock-free apart from the admission lock, so a healthy round finishes in microseconds - this only has to exceed
	 * scheduling noise on a loaded box.
	 */
	private static final int ROUND_TIMEOUT_SECONDS = 30;
	private static final String TEST_CATALOG = "testCatalog";
	private static final long CATALOG_VERSION = 1L;

	@Test
	@DisplayName("Admits exactly one session per round on a catalog that is not ALIVE")
	void shouldAdmitExactlyOneSessionPerRoundWhileCatalogIsNotAlive() throws Exception {
		// ONE catalog mock for the whole run, not one per supplier call. The registry resolves the catalog through
		// this supplier from *inside* the critical section, when it takes the version pin - minting a fresh mock
		// there would put fixture cost into the very window this test is measuring, and would mint one per
		// registration besides
		final Catalog catalog = Mockito.mock(Catalog.class, Mockito.withSettings().stubOnly());
		final SessionRegistry registry = new SessionRegistry(
			Mockito.mock(TracingContext.class),
			() -> catalog,
			SessionRegistry.createDataStore()
		);
		// built once and reused across rounds: creating a Mockito mock costs orders of magnitude more than the
		// registration being raced, and doing it inside the loop would push the two contenders apart rather than
		// leave them shoulder to shoulder at the barrier
		final EvitaSession[] sessions = new EvitaSession[CONTENDERS];
		for (int contender = 0; contender < CONTENDERS; contender++) {
			sessions[contender] = mockSession();
		}

		final ExecutorService contenders = Executors.newFixedThreadPool(
			CONTENDERS, daemonFactory("warm-up-admission")
		);
		try {
			final CyclicBarrier startLine = new CyclicBarrier(CONTENDERS);
			int violationRound = -1;
			int admittedInViolation = 0;
			int roundsRun = 0;
			for (int round = 0; round < ROUNDS; round++) {
				final List<Future<EvitaSession>> attempts = new ArrayList<>(CONTENDERS);
				for (int contender = 0; contender < CONTENDERS; contender++) {
					final EvitaSession session = sessions[contender];
					attempts.add(contenders.submit(admissionAttempt(registry, startLine, session)));
				}

				final List<EvitaSession> admitted = new ArrayList<>(CONTENDERS);
				for (final Future<EvitaSession> attempt : attempts) {
					final EvitaSession session = attempt.get(ROUND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
					if (session != null) {
						admitted.add(session);
					}
				}
				roundsRun = round + 1;

				// every session that got in has to be closed, violation or not - the next round must start from an
				// empty registry or its own result would be meaningless
				for (final EvitaSession session : admitted) {
					registry.removeSession(session);
				}

				if (admitted.size() != 1) {
					violationRound = round;
					admittedInViolation = admitted.size();
					log.warn(
						"Round {} admitted {} session(s) where exactly one is allowed.", round, admittedInViolation
					);
					break;
				}
			}

			final int observedRound = violationRound;
			final int observedAdmissions = admittedInViolation;
			final int observedRounds = roundsRun;
			assertEquals(
				-1, observedRound,
				() -> "A catalog that does not support transactions admitted " + observedAdmissions +
					" sessions at once in round " + observedRound + " of " + observedRounds +
					" - the admission lock in SessionRegistry.addSession is what makes the emptiness check and the " +
					"registration that follows it one indivisible step"
			);
		} finally {
			contenders.shutdownNow();
		}
	}

	/**
	 * Builds one contender's attempt: wait at the barrier, then register a warm-up session.
	 *
	 * @param registry  the registry being contended for
	 * @param startLine the barrier releasing every contender at once
	 * @param session   the session this contender attempts to register
	 * @return a task answering the admitted session, or NULL when the attempt was refused as the rule requires
	 */
	@Nonnull
	private static Callable<EvitaSession> admissionAttempt(
		@Nonnull SessionRegistry registry,
		@Nonnull CyclicBarrier startLine,
		@Nonnull EvitaSession session
	) {
		return () -> {
			try {
				startLine.await(ROUND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			} catch (BrokenBarrierException ex) {
				throw new IllegalStateException("The start line broke - a contender never arrived.", ex);
			}
			try {
				registry.addSession(false, () -> session);
				return session;
			} catch (ConcurrentInitializationException ex) {
				// the refusal this test is here to prove happens - not a failure
				return null;
			}
		};
	}

	/**
	 * Builds a session stub complete enough to travel the whole registration and removal path - the registry reads
	 * its id, catalog name, catalog version and traits, and hands its creation timestamp to the closing event.
	 *
	 * Stub-only, because these two mocks are reused for every round and nothing here ever verifies them: an ordinary
	 * mock retains an {@code Invocation} for each of the hundreds of thousands of calls a run makes, which would
	 * eventually look like a leak in the code under test rather than in the fixture.
	 *
	 * @return a mocked session, never NULL
	 */
	@Nonnull
	private static EvitaSession mockSession() {
		final EvitaSession session = Mockito.mock(EvitaSession.class, Mockito.withSettings().stubOnly());
		Mockito.when(session.getId()).thenReturn(UUID.randomUUID());
		Mockito.when(session.getCatalogName()).thenReturn(TEST_CATALOG);
		Mockito.when(session.getCatalogVersion()).thenReturn(CATALOG_VERSION);
		Mockito.when(session.getSessionTraits()).thenReturn(new SessionTraits(TEST_CATALOG));
		Mockito.when(session.getCreated()).thenReturn(OffsetDateTime.now());
		return session;
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
