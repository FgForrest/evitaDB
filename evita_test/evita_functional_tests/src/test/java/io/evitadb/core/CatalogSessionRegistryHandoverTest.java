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

package io.evitadb.core;

import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.exception.CatalogNotFoundException;
import io.evitadb.api.requestResponse.progress.Progress;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SESSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies what happens to a catalog's {@link io.evitadb.core.session.SessionRegistry} when the catalog changes
 * the name it is reached by.
 *
 * A registry is not an accessory of the name: it carries the active sessions, the FIFO queue and the
 * consumed-version census that backups pin against, all of which belong to the *catalog*. A rename and a replace
 * both leave a live catalog under the target name, so the registry must travel there rather than be abandoned
 * under a name that no longer exists and silently rebuilt, empty, under the new one.
 *
 * The tests below assert the handover **positively** — the registry is present under the target name before
 * anything opens a session on it, which nothing but the handover could have caused — because an
 * absence-of-exception test passes just as happily against a freshly built empty registry, and that is precisely
 * the bug.
 *
 * Driven against a real `Evita` throughout: the behaviour under test is the interaction of the mutation
 * operator, the engine state and the session registry map, and a double for any one of them would test the
 * double.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Session registry handover across a rename and a replace")
@Tag(ENGINE)
@Tag(MANAGEMENT)
@Tag(SESSION)
class CatalogSessionRegistryHandoverTest implements EvitaTestSupport {
	private static final String SOURCE_CATALOG = "handoverSourceCatalog";
	private static final String TARGET_CATALOG = "handoverTargetCatalog";
	private static final int BRANDS_IN_SOURCE = 2;
	/**
	 * Deliberately different from {@link #BRANDS_IN_SOURCE}, so an observed collection size says which of the two
	 * catalogs a session was served against.
	 */
	private static final int BRANDS_IN_TARGET = 5;
	/**
	 * Upper bound on session attempts, so a replace that never completes fails the test instead of spinning for
	 * ever. Never reached in practice — the probe this grew from opened ~850 sessions before the operation
	 * finished.
	 */
	private static final int MAX_SESSION_ATTEMPTS = 100_000;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("CatalogSessionRegistryHandoverTest");
		this.evita = new Evita(getEvitaConfiguration());
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/**
	 * Defines the named catalog and fills it with brands, then takes it live so the tests exercise the same code
	 * path a running installation would.
	 *
	 * @param catalogName catalog to create
	 * @param brandCount  number of brand entities to write
	 */
	private void createCatalogWithBrands(@Nonnull String catalogName, int brandCount) {
		this.evita.defineCatalog(catalogName);
		this.evita.updateCatalog(
			catalogName,
			session -> {
				session.defineEntitySchema(Entities.BRAND);
				for (int i = 1; i <= brandCount; i++) {
					session.upsertEntity(session.createNewEntity(Entities.BRAND, i));
				}
			}
		);
		this.evita.updateCatalog(catalogName, EvitaSessionContract::goLiveAndClose);
	}

	/**
	 * Closes and reopens the engine against the same storage, so every catalog is present on disk but none has a
	 * session registry — the state a production engine is in for every catalog it has not yet served.
	 */
	private void restartEngine() {
		this.evita.close();
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.waitUntilFullyInitialized();
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths).build();
	}

	@Nested
	@DisplayName("Drop")
	class Drop {

		@Test
		@DisplayName("Never serves a catalog that has no registry while it is being wiped")
		void shouldRefuseSessionsOnAnUnsessionedCatalogWhileItIsBeingDropped()
			throws InterruptedException, ExecutionException, TimeoutException {
			createCatalogWithBrands(TARGET_CATALOG, BRANDS_IN_TARGET);

			// After a restart the catalog is on disk with no session registry - the state in which the replace
			// path leaked, and the reason this test was written.
			//
			// **It does not guard the same mechanism.** Calibration says so: this test passes unchanged with the
			// registry quiesce reverted to the name-keyed lookup, because a drop stages a `BEING_DELETED`
			// placeholder through the *transition* updater before it does anything, and
			// `Evita#createSessionInternal` refuses on an `UnusableCatalog` whether or not a registry exists.
			// What this test therefore covers is that placeholder, which nothing else asserts - and it would
			// catch a future change that dropped the transition staging on the assumption the registry
			// suspension was carrying the weight. Rename and replace are the operations with no transition
			// updater at all, which is exactly why the leak was theirs.
			restartEngine();

			final Progress<Void> progress = CatalogSessionRegistryHandoverTest.this.evita
				.deleteCatalogIfExistsWithProgress(TARGET_CATALOG)
				.orElseThrow();
			final CompletableFuture<Void> completion = progress.onCompletion().toCompletableFuture();

			final Function<EvitaSessionContract, Integer> brandCounter =
				session -> session.getEntityCollectionSize(Entities.BRAND);
			final Set<Integer> observedSizes = new HashSet<>(4);
			int refusals = 0;
			int attempts = 0;
			while (!completion.isDone() && attempts < MAX_SESSION_ATTEMPTS) {
				attempts++;
				try {
					observedSizes.add(
						CatalogSessionRegistryHandoverTest.this.evita.queryCatalog(TARGET_CATALOG, brandCounter));
				} catch (EvitaInvalidUsageException ex) {
					// the catalog is being destroyed and says so - the outcome this test is here to require
					refusals++;
				}
			}

			completion.get(30, TimeUnit.SECONDS);

			assertTrue(attempts > 0, "The loop never ran, so the test exercised nothing!");
			// A session that got through here read a catalog whose folder was about to be deleted underneath it.
			assertFalse(
				observedSizes.contains(BRANDS_IN_TARGET),
				() -> "A session was served against the catalog being wiped - observed sizes: " + observedSizes
			);
			assertTrue(
				refusals > 0,
				"No session request was refused while the catalog was being dropped - either the loop never " +
					"overlapped the operation, or the catalog was not quiesced at all!"
			);

			assertFalse(
				CatalogSessionRegistryHandoverTest.this.evita.getCatalogNames().contains(TARGET_CATALOG),
				"The dropped catalog must be gone once the operation completes!"
			);
			assertThrows(
				CatalogNotFoundException.class,
				() -> CatalogSessionRegistryHandoverTest.this.evita.queryCatalog(
					TARGET_CATALOG, EvitaSessionContract::getCatalogVersion),
				"A dropped catalog must report that it names nothing, not that it is terminated or busy!"
			);
		}

	}

	@Nested
	@DisplayName("Rename")
	class Rename {

		@Test
		@DisplayName("Hands the registry to the new name and leaves nothing behind under the old one")
		void shouldHandTheRegistryOverToTheRenamedCatalog() {
			createCatalogWithBrands(SOURCE_CATALOG, BRANDS_IN_SOURCE);
			// opening a session is what creates the registry in the first place
			CatalogSessionRegistryHandoverTest.this.evita.queryCatalog(
				SOURCE_CATALOG, EvitaSessionContract::getCatalogVersion);
			assertTrue(
				CatalogSessionRegistryHandoverTest.this.evita.getCatalogSessionRegistry(SOURCE_CATALOG).isPresent(),
				"The fixture must leave a registry under the original name, or the test proves nothing!"
			);

			CatalogSessionRegistryHandoverTest.this.evita.renameCatalog(SOURCE_CATALOG, TARGET_CATALOG);

			// Asserted before anything opens a session on the new name: a registry can only be there because the
			// rename put it there, since the lazy path that would otherwise build one has not been reached.
			assertTrue(
				CatalogSessionRegistryHandoverTest.this.evita.getCatalogSessionRegistry(TARGET_CATALOG).isPresent(),
				"The renamed catalog must carry its session registry to the new name!"
			);
			assertFalse(
				CatalogSessionRegistryHandoverTest.this.evita.getCatalogSessionRegistry(SOURCE_CATALOG).isPresent(),
				"A name that no longer names a catalog must not keep holding a session registry!"
			);
		}

		@Test
		@DisplayName("Answers `not found` on the old name rather than `busy`, for ever")
		void shouldReportTheOldNameAsUnknownAfterARename() {
			createCatalogWithBrands(SOURCE_CATALOG, BRANDS_IN_SOURCE);
			CatalogSessionRegistryHandoverTest.this.evita.queryCatalog(
				SOURCE_CATALOG, EvitaSessionContract::getCatalogVersion);

			CatalogSessionRegistryHandoverTest.this.evita.renameCatalog(SOURCE_CATALOG, TARGET_CATALOG);

			// An orphaned registry answers `SessionBusyException` after a 500 ms stall, and keeps doing so for
			// the life of the process - asserted twice for exactly that reason.
			for (int attempt = 1; attempt <= 2; attempt++) {
				assertThrows(
					CatalogNotFoundException.class,
					() -> CatalogSessionRegistryHandoverTest.this.evita.queryCatalog(
						SOURCE_CATALOG, EvitaSessionContract::getCatalogVersion),
					"A renamed-away name must report that it names nothing, not that it is busy!"
				);
			}

			// and the catalog itself is servable under its new name straight away
			final Function<EvitaSessionContract, Integer> brandCounter =
				session -> session.getEntityCollectionSize(Entities.BRAND);
			assertEquals(
				BRANDS_IN_SOURCE,
				CatalogSessionRegistryHandoverTest.this.evita.queryCatalog(TARGET_CATALOG, brandCounter),
				"The renamed catalog must serve its data under the new name!"
			);
		}

	}

	@Nested
	@DisplayName("Replace")
	class Replace {

		@Test
		@DisplayName("Hands the source's registry to the target it replaced")
		void shouldHandTheRegistryOverToTheReplacedCatalog() {
			createCatalogWithBrands(TARGET_CATALOG, BRANDS_IN_TARGET);
			createCatalogWithBrands(SOURCE_CATALOG, BRANDS_IN_SOURCE);
			CatalogSessionRegistryHandoverTest.this.evita.queryCatalog(
				SOURCE_CATALOG, EvitaSessionContract::getCatalogVersion);

			CatalogSessionRegistryHandoverTest.this.evita.replaceCatalog(SOURCE_CATALOG, TARGET_CATALOG);

			assertTrue(
				CatalogSessionRegistryHandoverTest.this.evita.getCatalogSessionRegistry(TARGET_CATALOG).isPresent(),
				"The surviving catalog must carry its session registry to the name it now answers to!"
			);
			assertFalse(
				CatalogSessionRegistryHandoverTest.this.evita.getCatalogSessionRegistry(SOURCE_CATALOG).isPresent(),
				"The consumed source name must not keep holding a session registry!"
			);
			assertThrows(
				CatalogNotFoundException.class,
				() -> CatalogSessionRegistryHandoverTest.this.evita.queryCatalog(
					SOURCE_CATALOG, EvitaSessionContract::getCatalogVersion),
				"A name consumed by a replace must report that it names nothing!"
			);
		}

		@Test
		@DisplayName("Never serves a target that has no registry while it is being destroyed")
		void shouldRefuseSessionsOnAnUnsessionedTargetWhileItIsBeingReplaced()
			throws InterruptedException, ExecutionException, TimeoutException {
			createCatalogWithBrands(TARGET_CATALOG, BRANDS_IN_TARGET);
			createCatalogWithBrands(SOURCE_CATALOG, BRANDS_IN_SOURCE);

			// Restarted on purpose. Creating the catalogs above needs sessions, which build registries — and a
			// registry that already exists is precisely the case that was never broken. A catalog loaded from
			// disk and not yet queried has none, which is the state a production engine is in for every catalog
			// after a boot, and the state in which the quiesce used to suspend nothing at all.
			restartEngine();

			// submitted rather than awaited: the operation quiesces the target before this returns, which is
			// exactly the window the sessions below are opened into
			final Progress<CommitVersions> progress = CatalogSessionRegistryHandoverTest.this.evita
				.replaceCatalogWithProgress(SOURCE_CATALOG, TARGET_CATALOG);
			final CompletableFuture<CommitVersions> completion = progress.onCompletion().toCompletableFuture();

			final Function<EvitaSessionContract, Integer> brandCounter =
				session -> session.getEntityCollectionSize(Entities.BRAND);
			final Set<Integer> observedSizes = new HashSet<>(4);
			int refusals = 0;
			int attempts = 0;
			while (!completion.isDone() && attempts < MAX_SESSION_ATTEMPTS) {
				attempts++;
				try {
					observedSizes.add(
						CatalogSessionRegistryHandoverTest.this.evita.queryCatalog(TARGET_CATALOG, brandCounter));
				} catch (EvitaInvalidUsageException ex) {
					// the catalog is being destroyed and says so - the outcome this test is here to require
					refusals++;
				}
			}

			// a `get` rather than a `join` so a hang fails the test on its own rather than through the surefire
			// timeout
			completion.get(30, TimeUnit.SECONDS);

			// Without this the loop could have run zero times and proved nothing, however broken the operation is.
			assertTrue(attempts > 0, "The loop never ran, so the test exercised nothing!");
			// The heart of it, and asserted before the corroborating count below so that a regression reports the
			// leak itself rather than the absence of refusals: a size of BRANDS_IN_TARGET could only have come
			// from the catalog the operation was in the middle of destroying. Sizes from the incoming catalog are
			// fine - those are sessions served after the swap landed, which is the correct outcome, not a leak.
			assertFalse(
				observedSizes.contains(BRANDS_IN_TARGET),
				() -> "A session was served against the catalog being destroyed - observed sizes: " + observedSizes
			);
			// Corroboration rather than the claim: the target actively said no while it was being replaced. A
			// pass here with an empty `observedSizes` is the ideal outcome, not a vacuous one.
			assertTrue(
				refusals > 0,
				"No session request was refused while the target was being replaced - either the loop never " +
					"overlapped the operation, or the target was not quiesced at all!"
			);

			// and the swap is intact - refusing sessions must not mean the operation was quietly abandoned
			assertEquals(
				BRANDS_IN_SOURCE,
				CatalogSessionRegistryHandoverTest.this.evita.queryCatalog(TARGET_CATALOG, brandCounter),
				"The target must serve the data of the catalog that replaced it!"
			);
			assertFalse(
				CatalogSessionRegistryHandoverTest.this.evita.getCatalogNames().contains(SOURCE_CATALOG),
				"The source name must stop naming anything once it has been replaced into the target!"
			);
		}

	}

}
