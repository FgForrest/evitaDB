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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.observability.trace.TracingContext;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ReflectionLookup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SESSION;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a session is protected against reclamation of its catalog version from the moment the registry resolves
 * the catalog it is built on - not merely from the moment it is published.
 *
 * A session reads entity bodies and collection sizes through offset indexes shared by every catalog version, and
 * resolves its own version against their per-version roots. Those roots are released once every consumer of a version
 * has left, and a read at a released version is silently answered from the oldest root still retained - a newer state.
 * When a session was pinned only after it had been built, every other consumer of its version could leave in between,
 * and the session then saw entities committed after its own version: the snapshot-isolation failure
 * `LongRunningEvitaTransactionalFunctionalTest` reported as "Entity with catalogVersion `559` is present in catalog
 * version `558`".
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Session version pin taken before the session's catalog is resolved")
@Tag(ENGINE)
@Tag(SESSION)
@Tag(TRANSACTION)
class SessionRegistryVersionPinTest implements EvitaTestSupport {

	@Nested
	@DisplayName("On a live catalog")
	class OnLiveCatalog {
		private static final String CATALOG = "sessionVersionPinCatalog";
		private TestPaths paths;
		private Evita evita;

		@BeforeEach
		void setUp() {
			this.paths = createTestPaths("SessionRegistryVersionPinTest");
			this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		}

		@AfterEach
		void tearDown() {
			this.evita.close();
			cleanupTestPaths(this.paths);
		}

		/**
		 * Commits a new brand in a session of its own, which - like every session - leaves the version it was opened
		 * at when it closes.
		 *
		 * @param primaryKey primary key of the brand to create
		 */
		private void commitBrand(int primaryKey) {
			this.evita.updateCatalog(
				CATALOG,
				session -> {
					session.upsertEntity(session.createNewEntity(Entities.BRAND, primaryKey));
				}
			);
		}

		/**
		 * Opens and closes a session on the current version, so that its departure reports every older version as
		 * left by all of its consumers.
		 */
		private void leaveCurrentVersion() {
			this.evita.queryCatalog(CATALOG, EvitaSessionContract::getCatalogVersion);
		}

		@Test
		@DisplayName("A session keeps reading its own version when every other consumer leaves it while it is built")
		void shouldReadOwnVersionWhenAllOtherConsumersLeaveWhileSessionIsBuilt() {
			this.evita.defineCatalog(CATALOG);
			this.evita.updateCatalog(
				CATALOG,
				session -> {
					session.defineEntitySchema(Entities.BRAND);
					session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
				}
			);
			this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
			commitBrand(2);

			final SessionRegistry registry = this.evita.getCatalogSessionRegistry(CATALOG).orElseThrow();
			final long versionBeforeRegistration = registry.getCatalog().getVersion();
			final AtomicLong resolvedVersion = new AtomicLong(-1L);

			// The registration is held open at the point the engine's own factory runs: the catalog has been resolved,
			// the session is not published yet. Meanwhile, newer versions are committed and every other consumer of
			// the resolved version leaves - twice, so that a release recorded by the first departure is applied by the
			// flush of the next commit, which is when the offset index actually drops the roots.
			final EvitaInternalSessionContract session = registry.createSession(
				theRegistry -> theRegistry.addSession(
					true,
					resolvedCatalog -> {
						resolvedVersion.set(resolvedCatalog.getVersion());
						CompletableFuture.runAsync(
							() -> {
								commitBrand(3);
								leaveCurrentVersion();
								commitBrand(4);
								leaveCurrentVersion();
								commitBrand(5);
							}
						).orTimeout(30, TimeUnit.SECONDS).join();
						return new EvitaSession(
							this.evita, resolvedCatalog, ReflectionLookup.NO_CACHE_INSTANCE,
							theSession -> theRegistry.removeSession((EvitaSession) theSession),
							CommitBehavior.defaultBehaviour(),
							new SessionTraits(CATALOG),
							theRegistry::createCatalogConsumerControl
						);
					}
				)
			);
			try {
				// the premises: the interleaving ran after the catalog was resolved, and it did publish newer versions
				assertEquals(versionBeforeRegistration, resolvedVersion.get());
				assertEquals(versionBeforeRegistration, session.getCatalogVersion());
				assertTrue(
					this.evita.queryCatalog(CATALOG, EvitaSessionContract::getCatalogVersion) > versionBeforeRegistration,
					"The interleaving must have published newer catalog versions, or the test proves nothing!"
				);

				assertTrue(
					session.getEntity(Entities.BRAND, 2).isPresent(),
					"A brand committed before the session's version must be visible to it!"
				);
				assertFalse(
					session.getEntity(Entities.BRAND, 3).isPresent(),
					"A brand committed after the session's version leaked into it - its version was released while " +
						"the session was being built!"
				);
				assertFalse(
					session.getEntity(Entities.BRAND, 4).isPresent(),
					"A brand committed after the session's version leaked into it - its version was released while " +
						"the session was being built!"
				);
				assertEquals(
					2, session.getEntityCollectionSize(Entities.BRAND),
					"The session must count the brands of its own version, not of a newer one!"
				);
			} finally {
				session.close();
			}
		}

	}

	@Nested
	@DisplayName("Handing the capture pin over to the session")
	class CapturePinHandover {
		private static final String CATALOG = "testCatalog";

		/**
		 * Builds a session stub complete enough to travel the whole registration and removal path.
		 *
		 * @param catalogVersion the version the session reports it reads
		 * @return a mocked session, never NULL
		 */
		@Nonnull
		private static EvitaSession mockSession(long catalogVersion) {
			final EvitaSession session = Mockito.mock(EvitaSession.class);
			Mockito.when(session.getId()).thenReturn(UUID.randomUUID());
			Mockito.when(session.getCatalogName()).thenReturn(CATALOG);
			Mockito.when(session.getCatalogVersion()).thenReturn(catalogVersion);
			Mockito.when(session.getSessionTraits()).thenReturn(new SessionTraits(CATALOG));
			Mockito.when(session.getCreated()).thenReturn(OffsetDateTime.now());
			return session;
		}

		@Test
		@DisplayName("The version pinned before the build becomes the session's own pin when nothing was committed")
		void shouldKeepCapturePinWhenSessionReadsThePinnedVersion() {
			final Catalog catalog = Mockito.mock(Catalog.class);
			Mockito.when(catalog.getVersion()).thenReturn(7L);
			final SessionRegistry registry = new SessionRegistry(
				Mockito.mock(TracingContext.class), () -> catalog, SessionRegistry.createDataStore()
			);
			final EvitaSession session = mockSession(7L);

			registry.addSession(true, resolvedCatalog -> session);
			// one pin, held for the session's life - no second pin taken and nothing released on the way
			Mockito.verify(catalog, Mockito.times(1)).catalogVersionPinned(7L);
			Mockito.verify(catalog, Mockito.never()).catalogVersionReleased(Mockito.anyLong());

			registry.removeSession(session);
			Mockito.verify(catalog, Mockito.times(1)).catalogVersionReleased(7L);
		}

		@Test
		@DisplayName("A session built on a newer version is pinned at it before the version pinned for the build is released")
		void shouldPinNewerVersionBeforeReleasingCapturePin() {
			final Catalog catalog = Mockito.mock(Catalog.class);
			// the registry pins version 7, and the session turns out to read version 8 - a commit landed in between
			Mockito.when(catalog.getVersion()).thenReturn(7L);
			final SessionRegistry registry = new SessionRegistry(
				Mockito.mock(TracingContext.class), () -> catalog, SessionRegistry.createDataStore()
			);
			final EvitaSession session = mockSession(8L);

			registry.addSession(true, resolvedCatalog -> session);
			// the order is the whole point: releasing 7 before 8 is held leaves an instant in which no pin covers
			// the session's version at all
			final InOrder inOrder = Mockito.inOrder(catalog);
			inOrder.verify(catalog).catalogVersionPinned(7L);
			inOrder.verify(catalog).catalogVersionPinned(8L);
			inOrder.verify(catalog).catalogVersionReleased(7L);
			Mockito.verify(catalog, Mockito.never()).catalogVersionReleased(8L);

			registry.removeSession(session);
			Mockito.verify(catalog, Mockito.times(1)).catalogVersionReleased(8L);
		}

		@Test
		@DisplayName("The version pinned for the build is released when the session cannot be built")
		void shouldReleaseCapturePinWhenSessionFactoryFails() {
			final Catalog catalog = Mockito.mock(Catalog.class);
			Mockito.when(catalog.getVersion()).thenReturn(7L);
			final SessionRegistry registry = new SessionRegistry(
				Mockito.mock(TracingContext.class), () -> catalog, SessionRegistry.createDataStore()
			);

			final IllegalStateException failure = new IllegalStateException("session construction failed on purpose");
			final IllegalStateException thrown = assertThrows(
				IllegalStateException.class,
				() -> registry.addSession(
					true,
					resolvedCatalog -> {
						throw failure;
					}
				)
			);
			assertEquals(failure, thrown);
			Mockito.verify(catalog, Mockito.times(1)).catalogVersionPinned(7L);
			Mockito.verify(catalog, Mockito.times(1)).catalogVersionReleased(7L);
			assertEquals(0, registry.countActiveSessions().activeSessions());
		}

	}

}
