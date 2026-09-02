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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaNameMutation;
import io.evitadb.core.executor.ImmediateScheduledThreadPoolExecutor;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.store.engine.DefaultEnginePersistenceService;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.WAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof that a crash **between the WAL append and the bootstrap rewrite** of a rename or a replace does
 * not wedge the engine.
 *
 * The engine WAL is a *completion* log: a record is appended once the work has already happened, and the
 * bootstrap file is rewritten immediately afterwards. The window between the two is microseconds wide, but a
 * process killed inside it leaves `walVersion == stateVersion + 1` on disk — and an operator that cannot rebuild
 * the state its completion phase would have produced makes the engine refuse **every** subsequent mutation until a
 * human reconciles the bootstrap by hand. That is what `replayCompletionState` exists to prevent, and this test is
 * what proves it works for the operator this line of work owns.
 *
 * `EngineTransactionManagerForwardReplayTest` already covers the transaction-manager side of the same path, but it
 * mocks `Evita` — so it can observe neither the folder binding the replay produces, nor which bucket the catalog
 * lands in, nor whether the catalog is actually loadable afterwards. Those are precisely the three things a rename
 * replay has to get right, so this test uses real components throughout and manufactures the crash artefact the
 * only way that needs no seam at all: by appending to the WAL while the engine is **shut down**.
 *
 * **The `.catalogname` label converges on load, and is asserted.** It used to be the one thing this window left
 * permanently wrong: written *after* the commit and best-effort, because nothing in the engine reads it back, it
 * kept naming the folder's previous occupant for ever once a crash — or an ordinary I/O failure — took out that
 * write. The label is not load-bearing for the engine, but it is the only artefact that makes a bare storage
 * directory readable, and a crash is exactly when someone reads one. `reconcileStoredCatalogIdentity` now repairs
 * it alongside the header and schema, which is why the assertion below is on the label's *contents* rather than on
 * a warning being logged. Note the placement: it follows the query, because convergence happens when the catalog
 * is loaded and the query is what forces that.
 *
 * **Calibration, run and recorded.** With `ModifyCatalogSchemaNameMutationOperator#replayCompletionState` reduced
 * to the interface default's `Optional.empty()`, both tests fail — the rename reporting `Present:
 * [crashReplaySourceCatalog]` and the replace `Present: [crashReplayTargetCatalog, crashReplaySourceCatalog]`. Note
 * *where* they fail: on the name membership, not on the `defineCatalog` probe. A wedged engine still boots without
 * complaint, and refuses only the next mutation, so the membership assertion is the substantive claim here and the
 * probe merely corroborates it. A future edit that reorders them turns a real failure into a misleading one.
 *
 * The two repeat-replay tests carry a second calibration, against the WAL-truncation guard in
 * `EngineTransactionManager`: with truncation allowed to run after a replay, both fail in `restoreStorage` with
 * *"The WAL must sit exactly one version ahead of the bootstrap"* — because the truncation had already cut the
 * replayed record away. That is the defect those tests were written to find: the bootstrap ends up at `walV` over
 * a WAL at `walV - 1`, a combination the startup table permits nowhere, so the **next** boot refuses to start
 * outright. It is reachable only when no further commit happens between the recovering boot and the next one,
 * which is why nothing had hit it before.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Crash between the rename commit and the bootstrap rewrite")
@Tag(ENGINE)
@Tag(MANAGEMENT)
@Tag(WAL)
class CatalogRenameCrashReplayTest implements EvitaTestSupport {
	private static final String SOURCE_CATALOG = "crashReplaySourceCatalog";
	private static final String TARGET_CATALOG = "crashReplayTargetCatalog";
	private static final int BRANDS_IN_SOURCE = 3;
	/**
	 * Deliberately different from {@link #BRANDS_IN_SOURCE}, so a brand count read after the replace says *which*
	 * of the two catalogs the surviving name is serving rather than merely that it serves something.
	 */
	private static final int BRANDS_IN_TARGET = 7;
	/**
	 * Catalog created and dropped purely to prove the engine still accepts mutations. Named so a leftover in a
	 * storage directory says where it came from.
	 */
	private static final String PROBE_CATALOG = "crashReplayProbeCatalog";

	private TestPaths paths;
	/**
	 * Managed by JUnit, so a snapshot of the storage directory needs no cleanup of its own — and lives outside the
	 * three directories the engine itself knows about, where nothing can mistake it for a catalog.
	 */
	@TempDir
	private Path snapshotRoot;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths(CatalogRenameCrashReplayTest.class.getSimpleName());
	}

	@AfterEach
	void tearDown() {
		cleanupTestPaths(this.paths);
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths).build();
	}

	@Nonnull
	private StorageOptions storageOptions() {
		return StorageOptions.builder()
			.storageDirectory(this.paths.storage())
			.workDirectory(this.paths.work())
			.build();
	}

	@Nonnull
	private static TransactionOptions transactionOptions() {
		return TransactionOptions.builder()
			.transactionMemoryBufferLimitSizeBytes(1024 << 10)
			.transactionMemoryRegionCount(4)
			.build();
	}

	/**
	 * Defines the named catalog, fills it with brands and takes it live, so the folder on disk holds a real
	 * catalog rather than a stub the replay could never load.
	 *
	 * @param evita       engine to create the catalog in
	 * @param catalogName catalog to create
	 * @param brandCount  number of brand entities to write
	 */
	private static void createCatalogWithBrands(
		@Nonnull Evita evita,
		@Nonnull String catalogName,
		int brandCount
	) {
		evita.defineCatalog(catalogName);
		evita.updateCatalog(
			catalogName,
			session -> {
				session.defineEntitySchema(Entities.BRAND);
				for (int i = 1; i <= brandCount; i++) {
					session.upsertEntity(session.createNewEntity(Entities.BRAND, i));
				}
			}
		);
		evita.updateCatalog(catalogName, EvitaSessionContract::goLiveAndClose);
	}

	/**
	 * Counts the brands the named catalog serves. Used to prove the surviving name is bound to the folder holding
	 * the *source* catalog's data rather than merely to some readable folder.
	 *
	 * @param evita       engine to query
	 * @param catalogName catalog to count in
	 * @return number of brand entities
	 */
	private static int countBrands(@Nonnull Evita evita, @Nonnull String catalogName) {
		// hoisted into a typed local because `queryCatalog` is overloaded for `Function` and `Consumer`, and an
		// inline lambda binds to neither
		final Function<EvitaSessionContract, Integer> brandCount =
			session -> session.getEntityCollectionSize(Entities.BRAND);
		return evita.queryCatalog(catalogName, brandCount);
	}

	/**
	 * Resolves a folder token to its directory under the fixture's storage root.
	 *
	 * @param folderId token to resolve
	 * @return the directory the token names
	 */
	@Nonnull
	private Path storagePathOf(@Nonnull CatalogFolderId folderId) {
		return this.paths.storage().resolve(folderId.id());
	}

	/**
	 * Asserts the folder's `.catalogname` label names the catalog that now lives in it.
	 *
	 * Read straight off the disk rather than asked of the engine, because the engine never reads this file: it
	 * exists for whoever opens the storage directory with no server to ask, and the only way to check it is the way
	 * they would. Both failure modes print what was actually found — a label naming the folder's previous occupant
	 * and a label that is missing entirely are different defects and must not report identically.
	 *
	 * @param folderId    folder whose label is examined
	 * @param catalogName catalog the label must name
	 */
	private void assertFolderLabelledAs(@Nonnull CatalogFolderId folderId, @Nonnull String catalogName) {
		final Path marker = storagePathOf(folderId).resolve(CatalogPersistenceService.CATALOG_NAME_FLAG);
		assertTrue(
			Files.exists(marker),
			() -> "Folder `" + folderId.id() + "` carries no catalog name label at all - a bare storage directory "
				+ "cannot be read back without it!"
		);
		final String label;
		try {
			label = Files.readString(marker, StandardCharsets.UTF_8);
		} catch (IOException ex) {
			throw new IllegalStateException("Cannot read the catalog name label at `" + marker + "`!", ex);
		}
		assertEquals(
			catalogName, label,
			"Folder `" + folderId.id() + "` is labelled with the name of its previous occupant. The label is the "
				+ "only record of which catalog a generated folder holds, and a crash is precisely when someone "
				+ "reads a storage root directly."
		);
	}

	/**
	 * Linear membership test over the persisted catalog buckets — the arrays are tiny and their order is not part
	 * of what is being asserted.
	 *
	 * @param catalogs bucket to search
	 * @param needle   catalog name to look for
	 * @return true when the bucket names the catalog
	 */
	private static boolean contains(@Nonnull String[] catalogs, @Nonnull String needle) {
		for (final String candidate : catalogs) {
			if (needle.equals(candidate)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Manufactures the crash artefact: appends the given mutation to the engine WAL at `stateVersion + 1` without
	 * the matching bootstrap rewrite, exactly as a process killed inside the commit window leaves the storage.
	 *
	 * The engine must be **closed** when this runs — the append goes through the real persistence service, on the
	 * real storage directory, so there is no seam and no double anywhere in the fixture.
	 *
	 * The starting state is asserted to be *undrifted* first. Without that check a fixture that was already
	 * drifted for some unrelated reason would make every assertion downstream pass for the wrong reason.
	 *
	 * @param mutation the completion record to append
	 */
	private void appendUncommittedEngineMutation(@Nonnull ModifyCatalogSchemaNameMutation mutation) {
		final long stateVersion;
		try (final DefaultEnginePersistenceService service = new DefaultEnginePersistenceService(
			storageOptions(), transactionOptions(), new Scheduler(new ImmediateScheduledThreadPoolExecutor())
		)) {
			stateVersion = service.getEngineState().version();
			assertEquals(
				stateVersion, service.getLastVersionInMutationStream(),
				"The fixture must start undrifted, or the drift this test relies on is not the one it created!"
			);
			service.appendWal(stateVersion + 1, UUID.randomUUID(), mutation);
		}

		assertDriftPresent();
	}

	/**
	 * Asserts, by re-reading the storage, that it currently carries the crash signature this whole test class is
	 * about: a WAL one version ahead of the bootstrap.
	 *
	 * Re-read rather than taken from whichever instance wrote it, because what the next boot sees is the only thing
	 * that matters. Called wherever the drift is created *or* re-created — a fixture step that quietly fails to
	 * produce it yields a boot that has nothing to replay, and a test that is green on an empty premise.
	 */
	private void assertDriftPresent() {
		try (final DefaultEnginePersistenceService probe = new DefaultEnginePersistenceService(
			storageOptions(), transactionOptions(), new Scheduler(new ImmediateScheduledThreadPoolExecutor())
		)) {
			final long stateVersion = probe.getEngineState().version();
			assertEquals(
				stateVersion + 1, probe.getLastVersionInMutationStream(),
				"The WAL must sit exactly one version ahead of the bootstrap - without that gap the boot below has "
					+ "nothing to replay and proves nothing. Bootstrap is at " + stateVersion + "."
			);
		}
	}

	/**
	 * Boots a real engine on the fixture storage, waits until it has finished initialising, and hands it to the
	 * given assertions. A boot that wedges never reaches them.
	 *
	 * @param assertions checks to run against the booted engine
	 */
	private void bootAndAssert(@Nonnull Consumer<Evita> assertions) {
		try (final Evita evita = new Evita(getEvitaConfiguration())) {
			evita.waitUntilFullyInitialized();
			assertions.accept(evita);
		}
	}

	/**
	 * Proves the engine is not wedged, by making it accept an engine mutation. A wedged engine refuses every one of
	 * them, so a mutation that lands is the difference between "boot recovered" and "boot merely survived".
	 *
	 * Kept out of the settled-state assertions on purpose: it **writes**, advancing the engine two versions, which
	 * makes it unusable in any boot whose drift a later step has to reproduce.
	 *
	 * @param evita booted engine
	 */
	private static void assertEngineAcceptsMutations(@Nonnull Evita evita) {
		evita.defineCatalog(PROBE_CATALOG);
		assertTrue(
			evita.getCatalogNames().contains(PROBE_CATALOG),
			"A wedged engine refuses every mutation - this one had to land for the boot to count as recovered!"
		);
		evita.deleteCatalogIfExists(PROBE_CATALOG);
	}

	/**
	 * Copies the whole storage directory aside, so a boot that heals the crash artefact can be undone completely.
	 *
	 * This is what makes a *genuine* double replay reachable. A boot that replays also reconciles the bootstrap, so
	 * the next boot finds `walVersion == stateVersion` and never enters the replay path at all — two ordinary boots
	 * in a row look like an idempotence test and are not one.
	 *
	 * **The whole directory, not just the bootstrap file.** Rewinding the bootstrap alone used to be enough, and
	 * stopped being so once a boot that replays a *replace* also reclaims the folder its tombstone names: the
	 * rewound state binds a catalog to a directory that is no longer there, which is a world no crash can produce.
	 * The delete happens strictly after the reconciled bootstrap is durable, so a boot that gets far enough to
	 * remove the folder can never be the boot whose bootstrap write went missing. Restoring everything keeps the
	 * fixture describing a state the engine could actually be found in — and keeps it doing so if a future boot
	 * step touches something else again.
	 *
	 * @return the directory the snapshot was written to
	 */
	@Nonnull
	private Path captureStorage() {
		final Path snapshot = this.snapshotRoot.resolve("storage-" + UUID.randomUUID());
		copyRecursively(this.paths.storage(), snapshot);
		return snapshot;
	}

	/**
	 * Puts back a snapshot taken by {@link #captureStorage()}, replacing the storage directory wholesale. The engine
	 * must be closed.
	 *
	 * @param snapshot directory to restore from
	 */
	private void restoreStorage(@Nonnull Path snapshot) {
		FileUtils.deleteDirectory(this.paths.storage());
		copyRecursively(snapshot, this.paths.storage());
		assertDriftPresent();
	}

	/**
	 * Copies a directory tree, creating the target if it does not exist. Deliberately plain: the trees involved are
	 * a handful of small files, and a fixture that needs explaining is a fixture that gets doubted.
	 *
	 * @param source tree to copy from
	 * @param target tree to copy into
	 */
	private static void copyRecursively(@Nonnull Path source, @Nonnull Path target) {
		try {
			final List<Path> entries;
			try (final Stream<Path> tree = Files.walk(source)) {
				entries = tree.toList();
			}
			for (final Path entry : entries) {
				final Path destination = target.resolve(source.relativize(entry).toString());
				if (Files.isDirectory(entry)) {
					Files.createDirectories(destination);
				} else {
					Files.createDirectories(destination.getParent());
					Files.copy(entry, destination, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		} catch (IOException ex) {
			throw new IllegalStateException("Cannot copy `" + source + "` to `" + target + "`!", ex);
		}
	}

	@Nested
	@DisplayName("Rename")
	class Rename {

		@Test
		@DisplayName("Boots, replays the rename forward and stays usable")
		void shouldReplayARenameThatNeverReachedTheBootstrap() {
			final CatalogFolderId sourceFolder;
			try (final Evita evita = new Evita(getEvitaConfiguration())) {
				createCatalogWithBrands(evita, SOURCE_CATALOG, BRANDS_IN_SOURCE);
				sourceFolder = evita.getCatalogFolderContext().folderIdFor(SOURCE_CATALOG);
			}

			appendUncommittedEngineMutation(
				new ModifyCatalogSchemaNameMutation(SOURCE_CATALOG, TARGET_CATALOG, false)
			);

			bootAndAssert(
				evita -> {
					assertRenameSettled(evita, sourceFolder);
					assertEngineAcceptsMutations(evita);
				}
			);

			// The second boot is *not* a second replay - the first one reconciled the bootstrap, so this one finds
			// no drift and never enters the replay path. What it proves is the other half: that the binding and the
			// bucket placement the replay produced survived being written to disk, which no assertion against the
			// first boot's in-memory state can show. Replaying twice is a separate scenario and needs a fixture
			// that puts the drift back - see the test below.
			bootAndAssert(evita -> assertRenameSettled(evita, sourceFolder));
		}

		@Test
		@DisplayName("Replays the same record twice without compounding its effect")
		void shouldReplayTheSameRenameRecordTwice() {
			final CatalogFolderId sourceFolder;
			try (final Evita evita = new Evita(getEvitaConfiguration())) {
				createCatalogWithBrands(evita, SOURCE_CATALOG, BRANDS_IN_SOURCE);
				sourceFolder = evita.getCatalogFolderContext().folderIdFor(SOURCE_CATALOG);
			}

			appendUncommittedEngineMutation(
				new ModifyCatalogSchemaNameMutation(SOURCE_CATALOG, TARGET_CATALOG, false)
			);
			// captured *after* the drift is created, so restoring it reinstates the drift rather than removing it
			final Path driftedStorage = captureStorage();

			// deliberately asserts nothing that writes: an engine mutation here would advance the WAL past the
			// restored snapshot and the next boot would refuse to start on a two-version gap instead
			bootAndAssert(evita -> assertRenameSettled(evita, sourceFolder));

			// A crash *during* replay, after the state was rebuilt but before the reconciled bootstrap reached
			// disk, is exactly this: the next boot finds the same drift and replays the same record again. Replay
			// must therefore be idempotent, which is the property this restores the fixture in order to test.
			restoreStorage(driftedStorage);

			bootAndAssert(
				evita -> {
					assertRenameSettled(evita, sourceFolder);
					assertEngineAcceptsMutations(evita);
				}
			);
		}

		/**
		 * Asserts everything a completed rename must be true of, whichever boot is being examined.
		 *
		 * @param evita        booted engine
		 * @param sourceFolder folder token the source catalog was bound to before the crash
		 */
		private void assertRenameSettled(@Nonnull Evita evita, @Nonnull CatalogFolderId sourceFolder) {
			final Set<String> catalogNames = evita.getCatalogNames();
			assertTrue(
				catalogNames.contains(TARGET_CATALOG),
				() -> "The renamed catalog must be reachable under its new name! Present: " + catalogNames
			);
			assertFalse(
				catalogNames.contains(SOURCE_CATALOG),
				() -> "The old name must be gone - a replay that adds without removing leaves two names on one "
					+ "folder. Present: " + catalogNames
			);
			// no folder is created, moved or copied by a rename: the same folder is simply reached by a new name
			assertEquals(
				sourceFolder, evita.getCatalogFolderContext().folderIdFor(TARGET_CATALOG),
				"The new name must be bound to the folder the data is actually in!"
			);
			// read from the persisted state rather than inferred by opening a read-write session: a session can be
			// refused for engine read-only mode, the catalog's own read-only flag or an in-flight go-live, so a
			// throw would not say which of the four it was
			assertTrue(
				contains(evita.getEngineState().engineState().activeCatalogs(), TARGET_CATALOG),
				"The replayed catalog must land in the active bucket - filed as inactive it comes back from the "
					+ "next boot switched off, with no exception anywhere to say why!"
			);
			assertEquals(
				BRANDS_IN_SOURCE, countBrands(evita, TARGET_CATALOG),
				"The new name must serve the source catalog's data!"
			);
			// strictly after the query above: the label is repaired when the catalog is loaded, and the query is
			// what forces the load to have happened by the time this reads the file
			assertFolderLabelledAs(sourceFolder, TARGET_CATALOG);
		}

	}

	@Nested
	@DisplayName("Replace")
	class Replace {

		@Test
		@DisplayName("Boots, replays the replace forward and retires the superseded folder")
		void shouldReplayAReplaceThatNeverReachedTheBootstrap() {
			final CatalogFolderId sourceFolder;
			final CatalogFolderId supersededFolder;
			try (final Evita evita = new Evita(getEvitaConfiguration())) {
				createCatalogWithBrands(evita, SOURCE_CATALOG, BRANDS_IN_SOURCE);
				createCatalogWithBrands(evita, TARGET_CATALOG, BRANDS_IN_TARGET);
				sourceFolder = evita.getCatalogFolderContext().folderIdFor(SOURCE_CATALOG);
				supersededFolder = evita.getCatalogFolderContext().folderIdFor(TARGET_CATALOG);
			}
			assertNotEquals(
				sourceFolder, supersededFolder,
				"Two live catalogs must never share a folder - the fixture is broken, not the engine!"
			);

			appendUncommittedEngineMutation(
				new ModifyCatalogSchemaNameMutation(SOURCE_CATALOG, TARGET_CATALOG, true)
			);

			bootAndAssert(
				evita -> {
					assertReplaceSettled(evita, sourceFolder);
					// The tombstone is a standing order and the boot drain is what carries it out; this is where
					// the two halves meet. Asserted on the boot that *recovered*, not the one after it: the
					// construction-time drain runs a layer down and against the bootstrap as it was found, so it
					// sees the folder as the live home of a bound catalog and rightly leaves it alone. That
					// ordering is deliberate and stays - a drain against an already-healed state would cost the
					// ability to diagnose a drifted boot - so the second, tombstone-only pass that runs after a
					// successful replay is what closes the gap. Remove that pass and this line fails while the
					// end-state assertion below still passes, which is the whole point of keeping both.
					assertTrue(
						Files.notExists(storagePathOf(supersededFolder)),
						"The folder the replace superseded must be reclaimed by the boot that recovered the crash!"
					);
					assertEngineAcceptsMutations(evita);
				}
			);
			bootAndAssert(evita -> assertReplaceSettled(evita, sourceFolder));

			assertTrue(
				Files.notExists(storagePathOf(supersededFolder)),
				"The superseded folder must stay gone - a later boot must not resurrect or re-adopt it!"
			);
		}

		@Test
		@DisplayName("Replays the same record twice without tombstoning the surviving folder")
		void shouldReplayTheSameReplaceRecordTwice() {
			final CatalogFolderId sourceFolder;
			final CatalogFolderId supersededFolder;
			try (final Evita evita = new Evita(getEvitaConfiguration())) {
				createCatalogWithBrands(evita, SOURCE_CATALOG, BRANDS_IN_SOURCE);
				createCatalogWithBrands(evita, TARGET_CATALOG, BRANDS_IN_TARGET);
				sourceFolder = evita.getCatalogFolderContext().folderIdFor(SOURCE_CATALOG);
				supersededFolder = evita.getCatalogFolderContext().folderIdFor(TARGET_CATALOG);
			}

			appendUncommittedEngineMutation(
				new ModifyCatalogSchemaNameMutation(SOURCE_CATALOG, TARGET_CATALOG, true)
			);
			final Path driftedStorage = captureStorage();

			bootAndAssert(evita -> assertReplaceSettled(evita, sourceFolder));

			// The replace replay is the riskier of the two to repeat, because it stages a *tombstone*. Run against
			// the drifted state a second time it re-reads which folder the target name is bound to - and the first
			// replay repointed that name at the prevailing folder. A replay that read the live binding instead of
			// the state it is replaying onto would therefore order the destruction of the surviving catalog's own
			// folder, which is why the operator refuses when both names resolve to one folder.
			restoreStorage(driftedStorage);

			bootAndAssert(
				evita -> {
					assertReplaceSettled(evita, sourceFolder);
					assertEngineAcceptsMutations(evita);
				}
			);
			assertTrue(
				Files.exists(storagePathOf(sourceFolder)),
				"The folder the surviving catalog lives in must never be tombstoned by a repeated replay!"
			);
			// The snapshot put the superseded folder back, so the second replay had it to reclaim all over again -
			// which is the part worth asserting here rather than in the sibling test. The post-replay drain is
			// reached on *every* boot that recovers, not only the first one to see a given tombstone, and a pass
			// that quietly became one-shot would still leave the sibling test green.
			assertTrue(
				Files.notExists(storagePathOf(supersededFolder)),
				"A repeated replay must reclaim the superseded folder again, not leave it standing!"
			);
		}

		/**
		 * Asserts everything a completed replace must be true of, whichever boot is being examined.
		 *
		 * @param evita        booted engine
		 * @param sourceFolder folder token of the catalog that prevailed
		 */
		private void assertReplaceSettled(@Nonnull Evita evita, @Nonnull CatalogFolderId sourceFolder) {
			final Set<String> catalogNames = evita.getCatalogNames();
			assertTrue(
				catalogNames.contains(TARGET_CATALOG),
				() -> "The surviving name must be reachable! Present: " + catalogNames
			);
			assertFalse(
				catalogNames.contains(SOURCE_CATALOG),
				() -> "The consumed name must be gone! Present: " + catalogNames
			);
			assertEquals(
				sourceFolder, evita.getCatalogFolderContext().folderIdFor(TARGET_CATALOG),
				"The surviving name must be bound to the prevailing catalog's folder!"
			);
			assertTrue(
				contains(evita.getEngineState().engineState().activeCatalogs(), TARGET_CATALOG),
				"The prevailing catalog must land in the active bucket!"
			);
			// the brand counts differ between the two fixtures precisely so this assertion distinguishes "serves
			// the prevailing data" from "serves the data that was already there"
			assertEquals(
				BRANDS_IN_SOURCE, countBrands(evita, TARGET_CATALOG),
				"The surviving name must serve the prevailing catalog's data, not the superseded one's!"
			);
			// after the query, for the same reason as in the rename case - and it matters more here: the folder
			// under examination genuinely did change occupant, so a stale label names a catalog that no longer
			// exists anywhere
			assertFolderLabelledAs(sourceFolder, TARGET_CATALOG);
		}

	}

}
