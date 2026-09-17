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

import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.exception.CatalogAlreadyPresentException;
import io.evitadb.api.exception.CatalogNotFoundException;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.task.ServerTask;
import io.evitadb.api.task.Task;
import io.evitadb.api.task.TaskStatus;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.core.catalog.CatalogConsumerControl;
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
import java.util.concurrent.CancellationException;
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
	/**
	 * Wall-clock moment recorded right after each commit - `momentsAfterCommit.get(i)` falls after the version in
	 * `versions.get(i)` was recorded and before the next one was.
	 */
	private List<OffsetDateTime> momentsAfterCommit;

	@BeforeEach
	void setUp() throws InterruptedException {
		this.paths = createTestPaths("CatalogRestoreToVersionTest");
		this.evita = new Evita(getEvitaConfiguration(true));
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.BRAND);
		});
		this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
		this.versions = commitOneBrandPerVersion(TEST_CATALOG, BRAND_COUNT);
		// Only a version a bootstrap record names can be restored to, and which commits get one is a property of
		// the checkpoint cadence rather than of this fixture. Asserting it here makes a cadence change surface as
		// "the fixture stopped producing restorable versions" instead of as nine unrelated-looking failures.
		assertEquals(
			BRAND_COUNT, this.versions.stream().distinct().count(),
			"Every commit must have produced a distinct catalog version!"
		);
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
			// the moment recorded after the third brand was committed and before the fourth one was
			final OffsetDateTime momentAfterThirdBrand = momentsAfterCommit.get(2);
			// Pinned down first, so a failure below can only mean the restore landed somewhere other than where
			// the engine itself resolves this moment to - rather than the fixture having picked an ambiguous one.
			assertEquals(
				versions.get(2).longValue(), versionValidAt(momentAfterThirdBrand),
				"The fixture's moment must unambiguously identify the third version!"
			);

			awaitCompletion(
				evita.management().restoreCatalogToVersion(TEST_CATALOG, momentAfterThirdBrand, null, null)
			);

			assertEquals(3, brandCount(TEST_CATALOG));
		}

		@Test
		@DisplayName("An explicit version wins over a moment naming a different one")
		void shouldPreferTheVersionOverTheMoment() throws Exception {
			final OffsetDateTime momentOfTheFifthBrand = momentsAfterCommit.get(4);

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
				session -> {
					session.upsertEntity(session.createNewEntity(Entities.BRAND, 100));
				}
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
			// `testCatalog` and `test_catalog` are the same name in the snake-case convention, so registering the
			// second one would be refused - and this is exactly the check the replacement skips when it overwrites,
			// which is why the operation has to make it itself
			final String collidingName = "test_catalog";

			assertThrows(
				CatalogAlreadyPresentException.class,
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

		/**
		 * Cancellation is checked at phase boundaries only - `CompletableFuture#join` ignores interrupts, so a
		 * cancel landing inside an engine mutation is not seen until that mutation returns. This test therefore
		 * asserts the *invariant* rather than a timing: whichever boundary the cancel lands on (including "after
		 * the last one", where it lands on an operation that already finished), the engine is left in one of the
		 * two legal end states and never in between.
		 *
		 * Deliberately not a test that the operation stops. Pinning that would need a cancel injected at a known
		 * phase, and a test named for a window it does not reliably reach reports coverage that does not exist.
		 */
		@Test
		@DisplayName("Cancelling leaves the engine in one of the two legal end states, never in between")
		void shouldLeaveNoResidueWhenCancelled() {
			final EvitaManagement management = evita.management();
			final Task<?, Void> task = management.restoreCatalogToVersion(
				TEST_CATALOG, null, versions.get(2), null
			);

			task.cancel();
			// the task's own future may complete either way; what matters is what it leaves on disk and in the
			// engine, which is settled once the task is no longer running
			awaitSettled(task);

			assertEquals(
				Set.of(TEST_CATALOG), evita.getCatalogNames(),
				"A cancelled restore must never leave its scratch catalog behind!"
			);
			final int brands = brandCount(TEST_CATALOG);
			assertTrue(
				brands == BRAND_COUNT || brands == 3,
				() -> "The catalog must be either untouched (" + BRAND_COUNT + " brands) or fully restored " +
					"(3 brands), never anything in between - but held " + brands + "!"
			);
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
	@DisplayName("Ownership of the backup step")
	class BackupTaskOwnership {

		/**
		 * Not queueing is the *only* difference between `createBackupTask` and `backup`, and it is the reason the
		 * method was split out at all - the restore runs the backup as one step of its own sequence, so a copy
		 * queued here as well would run twice. Nothing else asserts it, so an edit submitting the task "for
		 * symmetry" would be caught by no test.
		 *
		 * The version pin the task takes in its constructor is deliberately *not* re-tested here - that mechanism
		 * is proven at `DefaultCatalogPersistenceServiceTest`, which reads the retention floor back directly. This
		 * asserts only the hand-off the new method introduced.
		 */
		@Test
		@DisplayName("The backup task is built unqueued, so only the sequence that owns it can run it")
		void shouldNotQueueTheBackupTaskItCreates() {
			final EvitaManagement management = evita.management();
			final CatalogContract sourceCatalog = evita.getCatalogInstanceOrThrowException(TEST_CATALOG);
			final CatalogConsumerControl consumerControl = evita.obtainCatalogSessionRegistry(TEST_CATALOG)
				.map(registry -> registry.createCatalogConsumerControl(TEST_CATALOG))
				.orElseThrow();

			final ServerTask<?, FileForFetch> backupTask = sourceCatalog.createBackupTask(
				null, versions.get(2), false, consumerControl::pinCatalogVersion
			);
			try {
				assertEquals(
					TaskSimplifiedState.WAITING_FOR_PRECONDITION, backupTask.getStatus().simplifiedState(),
					"A task the scheduler never received must not report itself as queued!"
				);
				assertTrue(
					management.listTaskStatuses(1, 100, (String[]) null).getData().stream()
						.noneMatch(it -> it.taskId().equals(backupTask.getStatus().taskId())),
					"The backup task must not appear among the scheduler's tasks - nobody queued it!"
				);
			} finally {
				// the constructor already pinned the version this copy would have read, and only running the task
				// or cancelling it gives that pin back
				backupTask.cancel();
			}
		}
	}

	@Nested
	@DisplayName("The state the restored catalog comes back in")
	class RestoredState {

		@Test
		@DisplayName("A source that never went live comes back in the state it was left in")
		void shouldLeaveTheRestoredCatalogInTheStateTheSourceHeld() throws Exception {
			final String warmUpCatalogName = TEST_CATALOG + "WarmUp";
			evita.defineCatalog(warmUpCatalogName);
			evita.updateCatalog(
				warmUpCatalogName,
				session -> {
					session.defineEntitySchema(Entities.BRAND);
					session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
				}
			);
			assertEquals(CatalogState.WARMING_UP, catalogState(warmUpCatalogName));

			awaitCompletion(
				evita.management().restoreCatalogToVersion(warmUpCatalogName, null, null, null)
			);

			// the archive carries whatever state the source held, registering applies the schema only, and
			// activating is not `goLive` - so a catalog the operator deliberately left in bulk-load mode comes back
			// in bulk-load mode, which is what `EvitaManagementContract#restoreCatalogToVersion` promises. This
			// fails the moment someone slips a `goLive` into the sequence to make a shorter promise true.
			assertEquals(
				CatalogState.WARMING_UP, catalogState(warmUpCatalogName),
				"The restored catalog must carry the state its source held at the selected version!"
			);
			assertEquals(1, brandCount(warmUpCatalogName));
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
	private List<Long> commitOneBrandPerVersion(@Nonnull String catalogName, int brandCount)
		throws InterruptedException {
		final List<Long> committedVersions = new ArrayList<>(brandCount);
		this.momentsAfterCommit = new ArrayList<>(brandCount);
		for (int i = 1; i <= brandCount; i++) {
			final int brandId = i;
			// the default commit behaviour waits for the changes to become visible, so the version read straight
			// afterwards is the one this commit produced rather than whatever happens to be current
			this.evita.updateCatalog(
				catalogName,
				session -> {
					session.upsertEntity(session.createNewEntity(Entities.BRAND, brandId));
				}
			);
			committedVersions.add(
				this.evita.queryCatalog(catalogName, EvitaSessionContract::getCatalogVersion)
			);
			this.momentsAfterCommit.add(OffsetDateTime.now());
			// Version timestamps carry millisecond precision, so two commits landing inside the same millisecond
			// would make the moment recorded above ambiguous between them - and a point-in-time test resolving to
			// the neighbouring version would look like a bug in the feature rather than in the fixture.
			Thread.sleep(2);
		}
		return committedVersions;
	}

	/**
	 * Returns the catalog version the engine considers current at the given moment.
	 *
	 * Asked of the engine rather than computed here on purpose: this is the same history a point-in-time backup
	 * consults, so it is the only answer that says what a restore *should* land on.
	 *
	 * @param moment moment to resolve
	 * @return the version valid at that moment
	 */
	private long versionValidAt(@Nonnull OffsetDateTime moment) {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getLastCatalogVersionBefore(moment).endVersion();
			}
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
			session -> {
				return session.getEntityCollectionSize(Entities.BRAND);
			}
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

	/**
	 * Waits for the operation to stop running, whatever its outcome.
	 *
	 * Unlike {@link #awaitCompletion(Task)} this tolerates a failure or a cancellation - it is for tests that
	 * assert on the state the operation left behind rather than on it succeeding.
	 *
	 * @param task the operation to wait for
	 */
	private static void awaitSettled(@Nonnull Task<?, Void> task) {
		try {
			task.getFutureResult().get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (ExecutionException | CancellationException e) {
			// an expected outcome for a cancelled or failed operation - the assertions are on what it left behind
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for the operation to settle!", e);
		} catch (TimeoutException e) {
			throw new IllegalStateException("The operation never stopped running!", e);
		}
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
			.transaction(
				TransactionOptions.builder()
					// A version can only be restored to if a bootstrap record names it, and in the live state
					// publication is deferred - at the default one-second cadence all five of this fixture's commits
					// land inside a single checkpoint and only the last of them gets a record. Checkpointing every
					// round is what gives each commit a version a client could actually ask for.
					.checkpointIntervalInMillis(0)
					.build()
			)
			.build();
	}
}
