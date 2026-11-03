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

package io.evitadb.core.session.task;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.exception.ConcurrentSessionAccessException;
import io.evitadb.core.Evita;
import io.evitadb.core.executor.ImmediateScheduledThreadPoolExecutor;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.session.EvitaInternalSessionContract;
import io.evitadb.core.session.task.SessionKillerTestSupport.HeldInvocation;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.evitadb.core.session.task.SessionKillerTestSupport.awaitExpiry;
import static io.evitadb.core.session.task.SessionKillerTestSupport.awaitInactivityPastTimeout;
import static io.evitadb.core.session.task.SessionKillerTestSupport.internal;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SESSION;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * This test verifies the correct functionality of the {@link SessionKiller} class.
 *
 * Every verdict here is decided by the test's own call of {@link SessionKiller#run()} and by nothing else, so the
 * outcome does not depend on how fast the machine is:
 *
 * - the evitaDB instance is configured to build no session killer of its own, and the killer under test is closed
 *   right after construction, so no background tick can close a session behind the test's back,
 * - "inactive" is a lower bound - the test waits until the session itself reports an inactivity past the timeout,
 *   so a slow machine only makes the session older,
 * - "a call in flight" is a real proxied session call held open by latches on a dedicated thread, observed to have
 *   entered before anything is decided,
 * - "recently active" is a touch made immediately before {@link SessionKiller#run()}; the premise that less than
 *   the timeout elapsed between the two is checked afterwards, and a run that stalled for longer is aborted rather
 *   than reported as a failure, because the expected verdict would no longer be defined.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("Session killer functionality")
@Tag(ENGINE)
@Tag(SESSION)
class SessionKillerTest implements EvitaTestSupport {
	/**
	 * The inactivity timeout the killer under test enforces.
	 */
	private static final int INACTIVITY_TIMEOUT_SECONDS = 1;
	/**
	 * The inactivity timeout in milliseconds, used to verify the "recently active" premise.
	 */
	private static final long INACTIVITY_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(INACTIVITY_TIMEOUT_SECONDS);
	private TestPaths paths;
	private Evita evita;
	private Scheduler killerScheduler;
	private SessionKiller sessionKiller;

	/**
	 * Aborts the test when more than the inactivity timeout elapsed since the last touch of a session that is
	 * expected to survive. Such a stall turns a "recently active" session into an inactive one, and killing it
	 * would then be correct - the scenario simply did not happen as intended.
	 *
	 * @param touchedAtMillis the wall-clock time taken immediately before the session was touched
	 */
	private static void assumeTouchedWithinTimeout(long touchedAtMillis) {
		final long elapsedMillis = System.currentTimeMillis() - touchedAtMillis;
		assumeTrue(
			elapsedMillis < INACTIVITY_TIMEOUT_MILLIS,
			"The thread stalled for " + elapsedMillis + "ms between touching the session and running the killer, " +
				"so the session was no longer recently active and the expected verdict is undefined."
		);
	}

	@BeforeEach
	void setUp() throws IOException {
		this.paths = createTestPaths("SessionKillerTest");
		this.evita = new Evita(
			newTestEvitaConfigurationBuilder(this.paths)
				.server(
					ServerOptions.builder()
						// no session killer of the instance itself - its periodic tick would race the verdicts below
						.closeSessionsAfterSecondsOfInactivity(-1)
						.build()
				)
				.build()
		);
		this.killerScheduler = new Scheduler(new ImmediateScheduledThreadPoolExecutor());
		this.sessionKiller = new SessionKiller(INACTIVITY_TIMEOUT_SECONDS, this.evita, this.killerScheduler);
		// stop the periodic schedule straight away - only the explicit `run()` calls of the tests decide
		this.sessionKiller.close();
	}

	@AfterEach
	void tearDown() throws IOException {
		this.killerScheduler.shutdownNow();
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("Inactivity detection")
	class InactivityDetectionTest {

		@Test
		@DisplayName("should kill session after interval of inactivity")
		void shouldKillSessionAfterIntervalOfInactivity() throws InterruptedException {
			SessionKillerTest.this.evita.defineCatalog("test");
			final EvitaSessionContract session = SessionKillerTest.this.evita.createReadOnlySession("test");
			awaitExpiry(internal(session), INACTIVITY_TIMEOUT_SECONDS);

			SessionKillerTest.this.sessionKiller.run();

			assertFalse(session.isActive());
		}

		@Test
		@DisplayName("should not kill session when there are ongoing invocations")
		void shouldNotKillSessionWhenThereAreInvocations() throws InterruptedException {
			SessionKillerTest.this.evita.defineCatalog("test");
			final EvitaSessionContract session = SessionKillerTest.this.evita.createReadOnlySession("test");
			// the session is old enough to be killed, so only the invocation below can save it
			awaitExpiry(internal(session), INACTIVITY_TIMEOUT_SECONDS);

			final long touchedAt = System.currentTimeMillis();
			assertNotNull(session.getCatalogName());
			SessionKillerTest.this.sessionKiller.run();
			assumeTouchedWithinTimeout(touchedAt);

			assertTrue(session.isActive());
		}

		@Test
		@DisplayName("should not kill any session when no sessions are active")
		void shouldNotKillAnySessionWhenNoSessionsAreActive() {
			SessionKillerTest.this.evita.defineCatalog("test");
			assertDoesNotThrow(() -> SessionKillerTest.this.sessionKiller.run());
		}

		@Test
		@DisplayName("should kill only inactive sessions when multiple sessions exist")
		void shouldKillOnlyInactiveSessionsWhenMultipleSessionsExist() throws Exception {
			SessionKillerTest.this.evita.defineCatalog("test");
			SessionKillerTest.this.evita.makeCatalogAlive("test");

			final EvitaSessionContract inactiveSession =
				SessionKillerTest.this.evita.createReadOnlySession("test");
			// read-write, because the held call is an `execute()`, which opens a transaction on an alive catalog
			final EvitaSessionContract activeSession =
				SessionKillerTest.this.evita.createReadWriteSession("test");

			// both sessions are old enough to be killed, only the active one has a call in flight - a held call keeps
			// the verdict independent of how long the killer pass takes, so no premise of this test depends on timing
			try (final HeldInvocation heldInvocation = HeldInvocation.start(internal(activeSession))) {
				heldInvocation.awaitEntered();
				awaitExpiry(internal(inactiveSession), INACTIVITY_TIMEOUT_SECONDS);
				awaitInactivityPastTimeout(internal(activeSession), INACTIVITY_TIMEOUT_SECONDS);

				SessionKillerTest.this.sessionKiller.run();

				assertFalse(inactiveSession.isActive());
				assertTrue(activeSession.isActive());
				heldInvocation.release();
				heldInvocation.awaitCompletion();
			}
		}

		@Test
		@DisplayName("should not kill session when there is a long-lasting invocation active")
		void shouldNotKillSessionWhenThereIsLongLastingInvocationCallActive() throws Exception {
			SessionKillerTest.this.evita.defineCatalog("test");
			final EvitaSessionContract session = SessionKillerTest.this.evita.createReadOnlySession("test");
			final EvitaInternalSessionContract internalSession = internal(session);

			try (final HeldInvocation heldInvocation = HeldInvocation.start(internalSession)) {
				heldInvocation.awaitEntered();
				// the last call entered longer than the timeout ago, so only the call in flight keeps the session
				awaitInactivityPastTimeout(internalSession, INACTIVITY_TIMEOUT_SECONDS);
				assertTrue(internalSession.methodIsRunning());

				SessionKillerTest.this.sessionKiller.run();

				assertTrue(session.isActive());
				heldInvocation.release();
				heldInvocation.awaitCompletion();
			}

			// with no call in flight the session expires like any other
			awaitExpiry(internalSession, INACTIVITY_TIMEOUT_SECONDS);
			SessionKillerTest.this.sessionKiller.run();
			assertFalse(session.isActive());
		}

		@Test
		@DisplayName("should not kill session when method completes just before termination")
		void shouldNotKillSessionWhenMethodCompletesJustBeforeTermination() throws Exception {
			// This test verifies that the atomic check prevents race conditions where:
			// 1. Session appears inactive (method started long ago)
			// 2. Method completes and updates lastCall timestamp
			// 3. Session killer checks methodIsRunning (returns false)
			// 4. Session would be incorrectly terminated despite recent activity
			//
			// The key verification is that immediately after a method completes,
			// isInactiveAndIdle returns false because lastCall was just updated.
			SessionKillerTest.this.evita.defineCatalog("test");
			final EvitaSessionContract session = SessionKillerTest.this.evita.createReadOnlySession("test");
			final EvitaInternalSessionContract internalSession = internal(session);

			final long touchedAt;
			try (final HeldInvocation heldInvocation = HeldInvocation.start(internalSession)) {
				heldInvocation.awaitEntered();
				// the method started longer than the timeout ago
				awaitInactivityPastTimeout(internalSession, INACTIVITY_TIMEOUT_SECONDS);

				// the atomic check must identify that a method is still running
				assertFalse(internalSession.isInactiveAndIdle(INACTIVITY_TIMEOUT_SECONDS));

				// now allow the method to complete - this updates lastCall to the current time; the held call
				// itself fails the completion when the session was terminated while it was running
				touchedAt = System.currentTimeMillis();
				heldInvocation.release();
				heldInvocation.awaitCompletion();
			}

			// KEY ASSERTION: immediately after the method completed, lastCall was just updated, so the session
			// must not be considered inactive even though no method is running
			final boolean inactiveAndIdleAfterCompletion =
				internalSession.isInactiveAndIdle(INACTIVITY_TIMEOUT_SECONDS);
			// the killer must not kill the session for the same reason
			SessionKillerTest.this.sessionKiller.run();
			assumeTouchedWithinTimeout(touchedAt);

			assertFalse(
				inactiveAndIdleAfterCompletion,
				"The session was reported inactive right after its method completed - the completion must " +
					"update lastCall before the in-flight counter drops to zero."
			);
			assertTrue(
				session.isActive(),
				"Session was unexpectedly killed despite having recent activity (lastCall was just updated " +
					"after method completion)."
			);
		}
	}

	@Nested
	@DisplayName("Pass resilience")
	class PassResilienceTest {

		@Test
		@DisplayName("should kill other expired sessions when one session is caught mid-call")
		void shouldKillOtherExpiredSessionsWhenOneSessionIsCaughtMidCall() throws InterruptedException {
			SessionKillerTest.this.evita.defineCatalog("test");
			final EvitaSessionContract expiredSession = SessionKillerTest.this.evita.createReadWriteSession("test");
			awaitExpiry(internal(expiredSession), INACTIVITY_TIMEOUT_SECONDS);

			// A read-write session whose client call started after the killer's idle check: the call claims the
			// proxy's ownership guard before it is counted as in flight, so the check sees the session idle, and the
			// killer's own next call is then rejected by the guard. No real call can be held in that state - every
			// held call is already counted - so the session is stubbed to the bare minimum the killer touches.
			final EvitaInternalSessionContract caughtSession = Mockito.mock(EvitaInternalSessionContract.class);
			Mockito.when(caughtSession.isInactiveAndIdle(ArgumentMatchers.anyLong())).thenReturn(true);
			Mockito.when(caughtSession.getCatalogName()).thenThrow(
				new ConcurrentSessionAccessException(UUID.randomUUID(), "client-thread", "session-killer-thread")
			);
			// the caught session is visited first, so it decides whether the rest of the pass still runs
			final Evita evitaWithCaughtSession = Mockito.spy(SessionKillerTest.this.evita);
			Mockito.doReturn(Stream.of(caughtSession, expiredSession))
				.when(evitaWithCaughtSession)
				.getActiveSessions();
			final SessionKiller killer = new SessionKiller(
				INACTIVITY_TIMEOUT_SECONDS, evitaWithCaughtSession, SessionKillerTest.this.killerScheduler
			);
			killer.close();

			killer.run();

			Mockito.verify(caughtSession).getCatalogName();
			assertFalse(
				expiredSession.isActive(),
				"The expired session must be killed in the same pass as the session caught mid-call."
			);
		}
	}

	@Nested
	@DisplayName("Lifecycle")
	class LifecycleTest {

		@Test
		@DisplayName("should allow close to be called multiple times")
		void shouldAllowCloseToBeCalledMultipleTimes() {
			assertDoesNotThrow(() -> {
				SessionKillerTest.this.sessionKiller.close();
				SessionKillerTest.this.sessionKiller.close();
			});
		}

		@Test
		@DisplayName("should still allow direct run after close")
		void shouldStillAllowDirectRunAfterClose() {
			SessionKillerTest.this.evita.defineCatalog("test");
			SessionKillerTest.this.sessionKiller.close();
			// direct run() should still work even after close() stops scheduling
			assertDoesNotThrow(() -> SessionKillerTest.this.sessionKiller.run());
		}
	}
}
