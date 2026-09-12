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

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.CatalogNotFoundException;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.task.Task;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.core.management.EvitaManagement;
import io.evitadb.dataType.PaginatedList;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.TASK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies `EvitaManagementContract#restoreCatalogToVersion` - backing a live catalog up at a past version and
 * swapping the result into service in one tracked operation.
 *
 * The fixture is a transactional catalog carrying one brand per version, so the version a restore landed on is
 * readable straight off the collection size: at version *v* the catalog holds exactly the brands numbered up to
 * the count committed by then. That makes "did we land on the right state" an equality rather than a spot check,
 * and it makes an off-by-one restore fail loudly instead of looking plausible.
 *
 * Time travel is switched **on** for every test here. It is off by default, and without it the historical data
 * files a past version points at are free to be reclaimed - which is a legitimate configuration, and gets its own
 * test asserting the honest failure rather than being left to make the others flaky.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Restore of a catalog to one of its earlier versions")
@Tag(ENGINE)
@Tag(MANAGEMENT)
@Tag(TASK)
class CatalogRestoreToVersionTest implements EvitaTestSupport {
	/**
	 * How many brands the fixture commits, one per transaction. Enough to leave a clearly-identifiable version in
	 * the middle with committed versions on both sides of it.
	 */
	private static final int BRAND_COUNT = 5;
	/**
	 * Generous bound on the whole backup-restore-swap operation. The catalogs here hold a handful of entities, so
	 * anything approaching this means a genuine stall rather than a slow machine.
	 */
	private static final int OPERATION_TIMEOUT_SECONDS = 120;

	private TestPaths paths;
	private Evita evita;
	/**
	 * Catalog version observed after each brand was committed - `versions.get(i)` is the version at which brands
	 * `1..i+1` exist.
	 */
	private List<Long> versions;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("CatalogRestoreToVersionTest");
		this.evita = new Evita(getEvitaConfiguration(true));
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(TEST_CATALOG, session -> session.defineEntitySchema(Entities.BRAND));
		this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
		this.versions = commitOneBrandPerVersion(TEST_CATALOG, BRAND_COUNT);
	}

	@AfterEach
	void tearDown() {
		if (this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("Restoring in place")
	class InPlace {

		@Test
		@DisplayName("The catalog keeps its name and holds exactly the state of the requested version")
		void shouldReplaceTheCatalogWithItsOwnEarlierVersion() throws Exception {
			final long thirdVersion = versions.get(2);

			awaitCompletion(
				evita.management().restoreCatalogToVersion(TEST_CATALOG, null, thirdVersion, null)
			);

			assertEquals(
				Set.of(TEST_CATALOG), evita.getCatalogNames(),
				"The restore must leave exactly the catalog it replaced - no temporary catalog may survive it!"
			);
			assertEquals(CatalogState.ALIVE, catalogState(TEST_CATALOG));
			assertEquals(
				3, brandCount(TEST_CATALOG),
				"The catalog must hold the three brands that existed at the restored version!"
			);
		}

		@Test
		@DisplayName("Selecting the state by moment lands on the same version as selecting it by number")
		void shouldRestoreToTheSameStateWhenSelectedByMoment() throws Exception {
			// the moment recorded *after* the third brand was committed and before the fourth one was: any
			// implementation resolving it must land on the third version
			final OffsetDateTime momentAfterThirdBrand = versionIntroducedAt(versions.get(2));

			awaitCompletion(
				evita.management().restoreCatalogToVersion(TEST_CATALOG, momentAfterThirdBrand, null, null)
			);

			assertEquals(3, brandCount(TEST_CATALOG));
		}

		@Test
		@DisplayName("An explicit version wins over a moment naming a different one")
		void shouldPreferTheVersionOverTheMoment() throws Exception {
			final OffsetDateTime momentOfTheFifthBrand = versionIntroducedAt(versions.get(4));

			awaitCompletion(
				evita.management().restoreCatalogToVersion(
					TEST_CATALOG, momentOfTheFifthBrand, versions.get(1), null
				)
			);

			assertEquals(
				2, brandCount(TEST_CATALOG),
				"`catalogVersion` must win over `pastMoment`, exactly as it does for a plain backup!"
			);
		}

		@Test
		@DisplayName("The restored catalog accepts writes again and they survive a restart")
		void shouldLeaveTheRestoredCatalogWritable() throws Exception {
			awaitCompletion(
				evita.management().restoreCatalogToVersion(TEST_CATALOG, null, versions.get(2), null)
			);

			evita.updateCatalog(
				TEST_CATALOG,
				session -> session.upsertEntity(session.createNewEntity(Entities.BRAND, 100))
			);
			assertEquals(4, brandCount(TEST_CATALOG));

			evita.close();
			evita = new Evita(getEvitaConfiguration(true));
			evita.waitUntilFullyInitialized();

			assertEquals(
				4, brandCount(TEST_CATALOG),
				"A catalog restored to an earlier version must reload from disk with the state it was left in!"
			);
		}
	}

	@Nested
	@DisplayName("Restoring under another name")
	class UnderAnotherName {

		@Test
		@DisplayName("A free target name is created and the source is left at its head version")
		void shouldRestoreIntoAFreeName() throws Exception {
			final String targetCatalogName = TEST_CATALOG + "Copy";

			awaitCompletion(
				evita.management().restoreCatalogToVersion(
					TEST_CATALOG, null, versions.get(1), targetCatalogName
				)
			);

			assertEquals(Set.of(TEST_CATALOG, targetCatalogName), evita.getCatalogNames());
			assertEquals(CatalogState.ALIVE, catalogState(targetCatalogName));
			assertEquals(2, brandCount(targetCatalogName));
			assertEquals(
				BRAND_COUNT, brandCount(TEST_CATALOG),
				"Restoring into another name must leave the source catalog untouched!"
			);
		}

		@Test
		@DisplayName("An occupied target name is replaced on the same terms as the source would be")
		void shouldReplaceAnUnrelatedExistingCatalog() throws Exception {
			final String targetCatalogName = TEST_CATALOG + "Other";
			evita.defineCatalog(targetCatalogName);
			evita.updateCatalog(
				targetCatalogName,
				session -> {
					session.defineEntitySchema(Entities.BRAND);
					for (int i = 1; i <= 42; i++) {
						session.upsertEntity(session.createNewEntity(Entities.BRAND, i));
					}
				}
			);
			assertEquals(42, brandCount(targetCatalogName));

			awaitCompletion(
				evita.management().restoreCatalogToVersion(
					TEST_CATALOG, null, versions.get(3), targetCatalogName
				)
			);

			assertEquals(Set.of(TEST_CATALOG, targetCatalogName), evita.getCatalogNames());
			assertEquals(
				4, brandCount(targetCatalogName),
				"The catalog holding the target name must have been replaced by the restored state!"
			);
			assertEquals(BRAND_COUNT, brandCount(TEST_CATALOG));
		}

		@Test
		@DisplayName("Naming the source catalog explicitly is the same as not naming a target at all")
		void shouldTreatTheSourceNameAsAnInPlaceRestore() throws Exception {
			awaitCompletion(
				evita.management().restoreCatalogToVersion(
					TEST_CATALOG, null, versions.get(0), TEST_CATALOG
				)
			);

			assertEquals(Set.of(TEST_CATALOG), evita.getCatalogNames());
			assertEquals(1, brandCount(TEST_CATALOG));
		}
	}

	@Nested
	@DisplayName("Refusals, and what they must not leave behind")
	class Refusals {

		@Test
		@DisplayName("A version that is no longer retained is refused before any work is started")
		void shouldRefuseAVersionThatIsNoLongerAvailable() {
			final EvitaManagement management = evita.management();
			final Set<String> catalogsBefore = evita.getCatalogNames();

			assertThrows(
				TemporalDataNotAvailableException.class,
				() -> management.restoreCatalogToVersion(
					TEST_CATALOG, null, versions.get(versions.size() - 1) + 1_000L, null
				)
			);

			assertEquals(
				catalogsBefore, evita.getCatalogNames(),
				"A refused restore must not leave a temporary catalog behind!"
			);
			assertTrue(
				management.listFilesToFetch(1, 100, Set.of()).getData().isEmpty(),
				"A refused restore must not leave a backup archive behind!"
			);
			assertEquals(BRAND_COUNT, brandCount(TEST_CATALOG));
		}

		@Test
		@DisplayName("An unknown source catalog is refused")
		void shouldRefuseAnUnknownSourceCatalog() {
			final EvitaManagement management = evita.management();
			assertThrows(
				CatalogNotFoundException.class,
				() -> management.restoreCatalogToVersion("nonExistingCatalog", null, 1L, null)
			);
		}

		@Test
		@DisplayName("A malformed target name is refused before any work is started")
		void shouldRefuseAMalformedTargetName() {
			final EvitaManagement management = evita.management();
			final Set<String> catalogsBefore = evita.getCatalogNames();

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> management.restoreCatalogToVersion(TEST_CATALOG, null, versions.get(1), "not a name!")
			);

			assertEquals(catalogsBefore, evita.getCatalogNames());
			assertTrue(management.listFilesToFetch(1, 100, Set.of()).getData().isEmpty());
		}

		@Test
		@DisplayName("A new target name colliding with an existing catalog in some naming convention is refused")
		void shouldRefuseATargetNameCollidingInANamingConvention() {
			final EvitaManagement management = evita.management();
			// differs from TEST_CATALOG only by case convention, so both map to the same name in at least one
			// convention - the check the replacement itself deliberately skips
			final String collidingName = TEST_CATALOG.toUpperCase();

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> management.restoreCatalogToVersion(TEST_CATALOG, null, versions.get(1), collidingName)
			);

			assertEquals(Set.of(TEST_CATALOG), evita.getCatalogNames());
		}
	}

	@Nested
	@DisplayName("The task clients monitor")
	class TaskSurface {

		@Test
		@DisplayName("The operation is listed under a stable task type and finishes at full progress")
		void shouldExposeTheOperationAsOneTask() throws Exception {
			final EvitaManagement management = evita.management();
			final Task<?, Void> task = management.restoreCatalogToVersion(
				TEST_CATALOG, null, versions.get(2), null
			);

			assertEquals(EvitaManagement.RESTORE_TO_VERSION_TASK_TYPE, task.getStatus().taskType());
			final PaginatedList<TaskStatus<?, ?>> listed = management.listTaskStatuses(
				1, 100, new String[]{EvitaManagement.RESTORE_TO_VERSION_TASK_TYPE}
			);
			assertTrue(
				listed.getData().stream().anyMatch(it -> it.taskId().equals(task.getStatus().taskId())),
				"The operation must be findable by its own task type, which is what a monitoring client filters on!"
			);

			awaitCompletion(task);

			final TaskStatus<?, ?> finalStatus = management.getTaskStatus(task.getStatus().taskId()).orElseThrow();
			assertEquals(TaskSimplifiedState.FINISHED, finalStatus.simplifiedState());
			assertEquals(100, finalStatus.progress());
		}

		@Test
		@DisplayName("The intermediate backup archive is gone once the operation succeeds")
		void shouldRemoveTheIntermediateArchiveOnSuccess() throws Exception {
			final EvitaManagement management = evita.management();

			awaitCompletion(
				management.restoreCatalogToVersion(TEST_CATALOG, null, versions.get(2), null)
			);

			final List<FileForFetch> filesLeft = management.listFilesToFetch(1, 100, Set.of()).getData();
			assertTrue(
				filesLeft.isEmpty(),
				() -> "The intermediate archive is an implementation detail and must not be left for download, " +
					"but found: " + filesLeft
			);
		}
	}

	@Nested
	@DisplayName("Without time travel")
	class WithoutTimeTravel {

		@Test
		@DisplayName("Restoring the current state still works when no history is retained")
		void shouldStillRestoreTheCurrentState() throws Exception {
			evita.close();
			evita = new Evita(getEvitaConfiguration(false));
			evita.waitUntilFullyInitialized();

			final String targetCatalogName = TEST_CATALOG + "Copy";
			awaitCompletion(
				evita.management().restoreCatalogToVersion(TEST_CATALOG, null, null, targetCatalogName)
			);

			assertEquals(
				BRAND_COUNT, brandCount(targetCatalogName),
				"Asking for no particular version copies the catalog as it stands, history or not!"
			);
		}
	}

	/**
	 * Commits one brand per transaction and reports the catalog version each of them produced.
	 *
	 * @param catalogName catalog to populate
	 * @param brandCount  number of brands, and therefore of versions, to create
	 * @return the version after each commit, in commit order
	 */
	@Nonnull
	private List<Long> commitOneBrandPerVersion(@Nonnull String catalogName, int brandCount) {
		final List<Long> committedVersions = new ArrayList<>(brandCount);
		for (int i = 1; i <= brandCount; i++) {
			final int brandId = i;
			// the default commit behaviour waits for the changes to become visible, so the version read straight
			// afterwards is the one this commit produced rather than whatever happens to be current
			this.evita.updateCatalog(
				catalogName,
				session -> session.upsertEntity(session.createNewEntity(Entities.BRAND, brandId))
			);
			committedVersions.add(
				this.evita.queryCatalog(catalogName, EvitaSessionContract::getCatalogVersion)
			);
		}
		return committedVersions;
	}

	/**
	 * Returns the moment the given catalog version was introduced, as recorded in the catalog's own history.
	 *
	 * @param catalogVersion version to look up
	 * @return the moment that version became the catalog's state
	 */
	@Nonnull
	private OffsetDateTime versionIntroducedAt(long catalogVersion) {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			session -> session.getCatalogVersionDescriptors(catalogVersion)
				.stream()
				.findFirst()
				.orElseThrow()
				.introducedAt()
		);
	}

	/**
	 * Returns the number of brands the named catalog currently holds.
	 *
	 * @param catalogName catalog to measure
	 * @return size of its brand collection
	 */
	private int brandCount(@Nonnull String catalogName) {
		return this.evita.queryCatalog(
			catalogName,
			session -> session.getEntityCollectionSize(Entities.BRAND)
		);
	}

	/**
	 * Returns the state the named catalog is currently in.
	 *
	 * @param catalogName catalog to inspect
	 * @return its state
	 */
	@Nonnull
	private CatalogState catalogState(@Nonnull String catalogName) {
		return this.evita.getCatalogState(catalogName).orElseThrow();
	}

	/**
	 * Waits for the operation to finish, failing the test rather than hanging when it does not.
	 *
	 * @param task the operation to wait for
	 */
	private static void awaitCompletion(@Nonnull Task<?, Void> task)
		throws ExecutionException, InterruptedException, TimeoutException {
		assertNotNull(task);
		task.getFutureResult().get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration(boolean timeTravelEnabled) {
		return newTestEvitaConfigurationBuilder(this.paths)
			.storage(
				StorageOptions.builder()
					.storageDirectory(this.paths.storage())
					.workDirectory(this.paths.work())
					.timeTravelEnabled(timeTravelEnabled)
					.build()
			)
			.build();
	}
}
