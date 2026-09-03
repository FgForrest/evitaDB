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
import io.evitadb.api.exception.CatalogNotFoundException;
import io.evitadb.api.exception.ConcurrentInitializationException;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.core.catalog.Catalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SESSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the admission rule a catalog that does not support transactions is served under: it admits **at most one**
 * session, and a second caller is refused with {@link ConcurrentInitializationException} rather than served.
 *
 * The rule is load-bearing far beyond the session count. A warm-up write mutates published indexes in place, outside
 * any transaction, so the single admitted session is the only thing standing between a bulk load and a second thread
 * reading a structure that is being rewritten underneath it.
 *
 * **What this class covers and what it does not.** The four cases below are the deterministic half - the sequential
 * refusal, the re-admission after a close, the guarantee that a registration failing on its way in leaves nothing
 * behind to refuse the next one, and the guarantee that the ALIVE path is not serialised by the admission lock at
 * all. The *racing* half - two threads entering
 * {@link SessionRegistry#addSession(boolean, java.util.function.Supplier)} at once - has no seam to hook, because the
 * window sits between the emptiness check and the map write inside one private method. It is swept instead by
 * `LongRunningSessionRegistryWarmUpAdmissionTest` in `evita_test/evita_long_running_tests`, which carries the
 * calibration of that sweep.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Single-session admission on a catalog that is not ALIVE")
@Tag(ENGINE)
@Tag(SESSION)
class SessionRegistryWarmUpAdmissionTest {
	private static final String TEST_CATALOG = "testCatalog";
	private static final long CATALOG_VERSION = 1L;
	private static final long WAIT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30);

	/**
	 * Thrown out of a registration that is deliberately abandoned once it has served its purpose as a stopping point
	 * inside the registry. Failing out keeps the scenario free of a half-registered stub session whose close would
	 * otherwise have to be simulated too.
	 */
	private static final class RegistrationAbandoned extends RuntimeException {
		@Serial private static final long serialVersionUID = -1858246079382335167L;

		RegistrationAbandoned() {
			super("registration abandoned on purpose");
		}
	}

	/**
	 * Builds a registry backed by a mocked catalog - the admission rule is decided by the {@code transactional} flag
	 * the caller passes, so no catalog state needs to be simulated.
	 *
	 * @return a fresh registry, never NULL
	 */
	@Nonnull
	private static SessionRegistry newRegistry() {
		return new SessionRegistry(
			Mockito.mock(TracingContext.class),
			() -> Mockito.mock(Catalog.class),
			SessionRegistry.createDataStore()
		);
	}

	/**
	 * Builds a session stub complete enough to travel the whole registration and removal path - the registry reads its
	 * id, catalog name, catalog version and traits, and hands its creation timestamp to the closing event.
	 *
	 * @return a mocked session, never NULL
	 */
	@Nonnull
	private static EvitaSession mockSession() {
		final EvitaSession session = Mockito.mock(EvitaSession.class);
		Mockito.when(session.getId()).thenReturn(UUID.randomUUID());
		Mockito.when(session.getCatalogName()).thenReturn(TEST_CATALOG);
		Mockito.when(session.getCatalogVersion()).thenReturn(CATALOG_VERSION);
		Mockito.when(session.getSessionTraits()).thenReturn(new SessionTraits(TEST_CATALOG));
		Mockito.when(session.getCreated()).thenReturn(OffsetDateTime.now());
		return session;
	}

	/**
	 * Registers a session the way the engine does - through
	 * {@link SessionRegistry#createSession(java.util.function.Function)}, whose factory ends in
	 * {@link SessionRegistry#addSession(boolean, java.util.function.Supplier)}.
	 *
	 * @param registry      the registry to register into
	 * @param transactional TRUE when the catalog is to be treated as ALIVE
	 * @param session       the session to register
	 * @return the registered session's proxy, never NULL
	 */
	@Nonnull
	private static EvitaInternalSessionContract register(
		@Nonnull SessionRegistry registry,
		boolean transactional,
		@Nonnull EvitaSession session
	) {
		return registry.createSession(theRegistry -> theRegistry.addSession(transactional, () -> session));
	}

	@Test
	@DisplayName("A second session on a catalog that is not ALIVE is refused")
	void shouldRefuseSecondSessionWhileCatalogIsNotAlive() {
		final SessionRegistry registry = newRegistry();
		final EvitaSession firstSession = mockSession();

		assertNotNull(register(registry, false, firstSession));
		assertEquals(1, registry.countActiveSessions().activeSessions());

		final EvitaSession secondSession = mockSession();
		final ConcurrentInitializationException exception = assertThrows(
			ConcurrentInitializationException.class,
			() -> register(registry, false, secondSession),
			"A catalog that does not support transactions must never admit a second session!"
		);
		assertTrue(
			exception.getMessage().contains(firstSession.getId().toString()),
			"The refusal must name the session that is already holding the catalog!"
		);
		assertEquals(
			1, registry.countActiveSessions().activeSessions(),
			"A refused registration must leave nothing behind!"
		);
	}

	@Test
	@DisplayName("A catalog that is not ALIVE admits a new session once the first one is closed")
	void shouldAdmitAnotherSessionOnceTheFirstIsClosed() {
		final SessionRegistry registry = newRegistry();
		final EvitaSession firstSession = mockSession();
		assertNotNull(register(registry, false, firstSession));

		registry.removeSession(firstSession);
		assertEquals(0, registry.countActiveSessions().activeSessions());

		// the rule is "at most one at a time", not "one ever" - a warm-up client that closes its session must be able
		// to open the next one, which is the whole of the bulk-indexing workflow
		final EvitaSession secondSession = mockSession();
		assertNotNull(register(registry, false, secondSession));
		assertEquals(1, registry.countActiveSessions().activeSessions());
	}

	@Test
	@DisplayName("A registration that fails while taking its version pin leaves the registry admitting again")
	void shouldLeaveNothingBehindWhenTheVersionPinCannotBeTaken() {
		// the registry's real catalog supplier - `Evita.createSessionNewRegistry`'s lambda - throws rather than
		// answering NULL once the catalog is gone, and the census only absorbs `CatalogTransitioningException`
		final AtomicBoolean catalogGone = new AtomicBoolean(true);
		final Catalog catalog = Mockito.mock(Catalog.class);
		final SessionRegistry registry = new SessionRegistry(
			Mockito.mock(TracingContext.class),
			() -> {
				if (catalogGone.get()) {
					throw new CatalogNotFoundException(TEST_CATALOG);
				}
				return catalog;
			},
			SessionRegistry.createDataStore()
		);

		assertThrows(
			CatalogNotFoundException.class,
			() -> register(registry, false, mockSession()),
			"A catalog that has gone must fail the registration rather than half-complete it!"
		);
		// the point of the ordering inside `registerNewSession`: a session that was never handed to a caller is
		// never closed by one, and on a catalog that is not transactional a single orphan would refuse every later
		// admission for the life of the process
		assertEquals(
			0, registry.countActiveSessions().activeSessions(),
			"A failed registration must leave no session behind!"
		);

		catalogGone.set(false);
		assertNotNull(
			register(registry, false, mockSession()),
			"The registry must admit a session again once the catalog is back!"
		);
		assertEquals(1, registry.countActiveSessions().activeSessions());
	}

	@Test
	@DisplayName("A registry derived from another admits into the same single-session slot")
	void shouldRefuseASecondSessionAcrossARegistryDerivedFromTheSameOrigin() {
		// a catalog rename hands the registry's whole state to a new instance built by `withDifferentCatalogSupplier`,
		// and the two then serve the same catalog at once. Two registries admitting into one map would each see it
		// empty in turn and admit a session each, which is the rule's whole failure mode
		final SessionRegistry origin = newRegistry();
		final SessionRegistry derived = origin.withDifferentCatalogSupplier(() -> Mockito.mock(Catalog.class));

		final EvitaSession firstSession = mockSession();
		assertNotNull(register(derived, false, firstSession));
		assertEquals(
			1, origin.countActiveSessions().activeSessions(),
			"the origin must count a session the derived registry admitted!"
		);

		final ConcurrentInitializationException refusedByOrigin = assertThrows(
			ConcurrentInitializationException.class,
			() -> register(origin, false, mockSession()),
			"the origin must refuse a second session the derived registry already admitted one for!"
		);
		assertTrue(
			refusedByOrigin.getMessage().contains(firstSession.getId().toString()),
			"The refusal must name the session that is already holding the catalog!"
		);

		// and the same in reverse, so the assertion above cannot pass by the derived registry simply being inert
		origin.removeSession(firstSession);
		final EvitaSession secondSession = mockSession();
		assertNotNull(register(origin, false, secondSession));
		assertThrows(
			ConcurrentInitializationException.class,
			() -> register(derived, false, mockSession()),
			"the derived registry must refuse a second session the origin already admitted one for!"
		);
	}

	@Test
	@DisplayName("A derived registry waits on the same admission lock as the registry it came from")
	void shouldHoldOffADerivedRegistryAdmissionWhileTheOriginHoldsTheAdmissionLock()
		throws InterruptedException {
		final SessionRegistry origin = newRegistry();
		final SessionRegistry derived = origin.withDifferentCatalogSupplier(() -> Mockito.mock(Catalog.class));
		final CountDownLatch admissionEntered = new CountDownLatch(1);
		final CountDownLatch releaseAdmission = new CountDownLatch(1);
		final CountDownLatch derivedAdmitted = new CountDownLatch(1);
		final AtomicReference<Throwable> originOutcome = new AtomicReference<>();

		final Thread originAdmission = new Thread(
			() -> {
				try {
					origin.addSession(
						false,
						() -> {
							admissionEntered.countDown();
							awaitOrFail(releaseAdmission, "The origin admission was never released!");
							throw new RegistrationAbandoned();
						}
					);
				} catch (Throwable ex) {
					originOutcome.set(ex);
				}
			},
			"origin-admission"
		);
		originAdmission.setDaemon(true);
		originAdmission.start();
		assertTrue(
			admissionEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS), "The origin admission never got going!");

		final Thread derivedAdmission = new Thread(
			() -> {
				try {
					register(derived, false, mockSession());
				} catch (Throwable ignored) {
					// whichever way it ends, the point below is only whether it got that far while the lock was held
				} finally {
					derivedAdmitted.countDown();
				}
			},
			"derived-admission"
		);
		derivedAdmission.setDaemon(true);
		derivedAdmission.start();
		try {
			// Negative wait, and deliberately short: a loaded machine can only make the derived thread slower, so
			// this can never fail spuriously. Were the derived registry given a lock of its own, it would find the
			// map still empty and complete immediately instead of waiting here
			assertFalse(
				derivedAdmitted.await(250, TimeUnit.MILLISECONDS),
				"A derived registry admitted a session while the origin held the admission lock - the two must " +
					"share one lock, or neither guards the single-session rule!"
			);
		} finally {
			releaseAdmission.countDown();
		}

		assertTrue(
			derivedAdmitted.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS),
			"The derived admission never completed once the lock was given back!"
		);
		originAdmission.join(WAIT_TIMEOUT_MS);
		assertInstanceOf(
			RegistrationAbandoned.class, originOutcome.get(),
			"The origin admission failed for some other reason than the one this test arranges!"
		);
	}

	@Test
	@DisplayName("A session whose version pin fails is closed rather than abandoned")
	void shouldCloseTheSessionWhenTheVersionPinCannotBeTaken() {
		// the pin is taken after the supplier has already built the session, and the caller never receives the
		// session, so nothing else will ever close it - it holds an open traffic-recording session and, on an ALIVE
		// catalog, an open transaction. The registry's own state is covered by the case above; this covers the
		// session's
		final SessionRegistry registry = new SessionRegistry(
			Mockito.mock(TracingContext.class),
			() -> {
				throw new CatalogNotFoundException(TEST_CATALOG);
			},
			SessionRegistry.createDataStore()
		);
		final EvitaSession session = mockSession();

		assertThrows(CatalogNotFoundException.class, () -> register(registry, false, session));
		Mockito.verify(session).closeNow(Mockito.any());
	}

	@Test
	@DisplayName("An ALIVE registration is not serialised by the admission lock")
	void shouldNotSerialiseAliveRegistrationBehindWarmUpAdmission() throws InterruptedException {
		final SessionRegistry registry = newRegistry();
		final CountDownLatch admissionEntered = new CountDownLatch(1);
		final CountDownLatch releaseAdmission = new CountDownLatch(1);
		final AtomicReference<Throwable> warmUpOutcome = new AtomicReference<>();

		// parked inside the supplier, this thread holds the admission lock for as long as the test wants it held -
		// the supplier is the one point inside the critical section a caller can stop time at
		final Thread warmUpAdmission = new Thread(
			() -> {
				try {
					registry.addSession(
						false,
						() -> {
							admissionEntered.countDown();
							awaitOrFail(releaseAdmission, "The warm-up admission was never released!");
							throw new RegistrationAbandoned();
						}
					);
				} catch (Throwable ex) {
					warmUpOutcome.set(ex);
				}
			},
			"warm-up-admission"
		);
		warmUpAdmission.setDaemon(true);
		warmUpAdmission.start();
		assertTrue(
			admissionEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS),
			"The warm-up admission never got going!"
		);

		// Positive wait, and generous: the ALIVE path must not take the admission lock, so this registration has to
		// complete while the warm-up one is parked inside it. Were the lock promoted to cover both paths - the gate's
		// write lock among the tempting ways to do that - this thread would sit here until the timeout.
		final EvitaSession aliveSession = mockSession();
		final AtomicReference<Throwable> aliveOutcome = new AtomicReference<>();
		final Thread aliveRegistration = new Thread(
			() -> {
				try {
					assertNotNull(register(registry, true, aliveSession));
				} catch (Throwable ex) {
					aliveOutcome.set(ex);
				}
			},
			"alive-registration"
		);
		aliveRegistration.setDaemon(true);
		aliveRegistration.start();
		try {
			aliveRegistration.join(WAIT_TIMEOUT_MS);
			assertFalse(
				aliveRegistration.isAlive(),
				"An ALIVE registration blocked behind a warm-up admission - the admission lock must not be taken " +
					"on the transactional path!"
			);
			assertNull(aliveOutcome.get(), () -> "The ALIVE registration failed: " + aliveOutcome.get());
			assertEquals(1, registry.countActiveSessions().activeSessions());
		} finally {
			// released in a `finally` so that a failing assertion above reports itself immediately: left parked, the
			// warm-up thread would sit on both the admission lock and the gate's read lock for its full 30 s budget
			// and then fail with a second, unrelated-looking error on top of the real one
			releaseAdmission.countDown();
		}
		warmUpAdmission.join(WAIT_TIMEOUT_MS);
		assertFalse(warmUpAdmission.isAlive(), "The warm-up admission never finished once it was released!");
		assertInstanceOf(
			RegistrationAbandoned.class, warmUpOutcome.get(),
			"The warm-up admission failed for some other reason than the one this test arranges!"
		);

		// and the lock has to be given back on the way out of a failed admission as much as a successful one -
		// leaking it would wedge every later warm-up session on this registry rather than any single one. With the
		// ALIVE session gone the registry is empty again, so this warm-up admission must be served rather than
		// blocked on a lock the abandoned registration never released
		registry.removeSession(aliveSession);
		assertNotNull(register(registry, false, mockSession()));
	}

	/**
	 * Awaits a latch within the generous positive-wait budget and fails the test when it does not arrive.
	 *
	 * @param latch   the latch to await
	 * @param message the message reported when the latch never counts down
	 */
	private static void awaitOrFail(@Nonnull CountDownLatch latch, @Nonnull String message) {
		try {
			assertTrue(latch.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS), message);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}
}
