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

import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.exception.SessionBusyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SESSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that suspending a registry cannot report a catalog quiesced while a session registration is still in
 * flight on it.
 *
 * Callers of {@link SessionRegistry#closeAllActiveSessionsAndSuspend(SuspendOperation)} act on its return by
 * destroying what the sessions were reading - a rename closes the persistence service the catalog was being served
 * from. A registration that slips in behind the drain therefore does not merely produce an extra session: it produces
 * a reader that gets `PersistenceServiceClosed`, an internal error, where the contract owes it a well-defined
 * invalid-usage answer.
 *
 * The interleaving is reached deterministically through the seam
 * {@link SessionRegistry#addSession(boolean, java.util.function.Supplier)} already offers - the caller supplies the
 * session, so the supplier is a point inside the registration that a test can stop time at.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Session registration fenced against suspension")
@Tag(ENGINE)
@Tag(SESSION)
class SessionRegistrySuspensionTest {
	/**
	 * Thrown by the blocked registration instead of returning a session. The test needs a registration that is
	 * *inside* the registry, not one that completes - and failing out of it keeps the whole scenario free of a
	 * stubbed session, whose forced close would otherwise have to be simulated too.
	 */
	private static final class RegistrationAbandoned extends RuntimeException {
		RegistrationAbandoned() {
			super("registration abandoned on purpose");
		}
	}

	@Nonnull
	private static SessionRegistry newRegistry() {
		return new SessionRegistry(
			Mockito.mock(TracingContext.class),
			() -> Mockito.mock(Catalog.class),
			SessionRegistry.createDataStore()
		);
	}

	@Test
	@DisplayName("A suspension may not complete while a registration is still in flight")
	void shouldNotCompleteSuspensionWhileRegistrationIsInFlight() throws InterruptedException {
		final SessionRegistry registry = newRegistry();
		final CountDownLatch registrationEntered = new CountDownLatch(1);
		final CountDownLatch releaseRegistration = new CountDownLatch(1);
		final AtomicReference<Throwable> registrationOutcome = new AtomicReference<>();

		// this thread stands where the race lives: past the suspension check, not yet in `activeSessions`
		final Thread registration = new Thread(
			() -> {
				try {
					registry.addSession(
						true,
						() -> {
							registrationEntered.countDown();
							try {
								assertTrue(
									releaseRegistration.await(30, TimeUnit.SECONDS),
									"The registration was never released!"
								);
							} catch (InterruptedException ex) {
								Thread.currentThread().interrupt();
								throw new IllegalStateException(ex);
							}
							throw new RegistrationAbandoned();
						}
					);
				} catch (Throwable ex) {
					registrationOutcome.set(ex);
				}
			},
			"registration"
		);
		registration.setDaemon(true);
		registration.start();
		assertTrue(registrationEntered.await(30, TimeUnit.SECONDS), "The registration never got going!");

		final Thread suspension = new Thread(
			() -> registry.closeAllActiveSessionsAndSuspend(SuspendOperation.POSTPONE),
			"suspension"
		);
		suspension.setDaemon(true);
		suspension.start();

		// Negative wait, and short on purpose: a loaded machine can only make the suspension slower to reach its
		// barrier, which keeps this thread alive and the assertion holding - it cannot fail spuriously. Without the
		// barrier the suspension finds an empty map, concludes the catalog is quiesced and returns well inside this
		// window, which is exactly the report that gets a live reader's catalog destroyed underneath it.
		suspension.join(250);
		assertTrue(
			suspension.isAlive(),
			"The suspension reported the catalog quiesced while a session registration was still in flight!"
		);

		releaseRegistration.countDown();

		suspension.join(TimeUnit.SECONDS.toMillis(30));
		assertFalse(suspension.isAlive(), "The suspension never finished once the registration let go!");
		registration.join(TimeUnit.SECONDS.toMillis(30));
		assertFalse(registration.isAlive(), "The registration never finished!");

		// the gate has to be given back on the way out of a failed registration as much as a successful one -
		// leaking it would wedge every later suspension on this registry rather than any single session
		assertInstanceOf(
			RegistrationAbandoned.class, registrationOutcome.get(),
			"The registration failed for some other reason than the one this test arranges!"
		);
		assertEquals(0, registry.countActiveSessions().activeSessions(), "No session may survive the suspension!");
	}

	@Test
	@DisplayName("A registration arriving after a rejecting suspension is refused, not served")
	void shouldRefuseRegistrationAfterRejectingSuspension() {
		final SessionRegistry registry = newRegistry();
		registry.closeAllActiveSessionsAndSuspend(SuspendOperation.REJECT);

		assertThrows(
			InstanceTerminatedException.class,
			() -> registry.addSession(true, () -> Mockito.mock(EvitaSession.class))
		);
		assertEquals(
			0, registry.countActiveSessions().activeSessions(),
			"A refused registration must leave nothing behind!"
		);
	}

	@Test
	@DisplayName("A registration arriving during a postponing suspension that never lifts reports the registry busy")
	void shouldReportBusyWhenPostponingSuspensionNeverLifts() {
		final SessionRegistry registry = newRegistry();
		registry.closeAllActiveSessionsAndSuspend(SuspendOperation.POSTPONE);

		// a postponed registration waits the suspension out and only then gives up - the caller is told the registry
		// is busy rather than that the catalog is gone, because it is not
		assertThrows(
			SessionBusyException.class,
			() -> registry.addSession(true, () -> Mockito.mock(EvitaSession.class))
		);
		assertEquals(
			0, registry.countActiveSessions().activeSessions(),
			"A refused registration must leave nothing behind!"
		);
	}
}
