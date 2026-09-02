/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.MarkCatalogMissingMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.RestoreCatalogSchemaMutation;
import io.evitadb.core.engine.CatalogFolderReservation;
import io.evitadb.core.executor.ImmediateScheduledThreadPoolExecutor;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.catalog.persistence.PersistenceService;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.spi.store.engine.model.EngineState;
import io.evitadb.spi.store.engine.model.CatalogInventoryDivergence;
import io.evitadb.store.engine.DefaultEnginePersistenceService;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ArrayUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.WAL;

/**
 * End-to-end test that verifies the boot-time catalog-inventory-divergence drain. The persistence
 * service captures divergence between the persisted `EngineState`'s catalog inventory and what the backing store
 * currently reports as a pure value; `Evita`'s constructor then drains it through the regular WAL-backed
 * `applyMutation` path so each reconciliation step produces a WAL record and bumps the engine version.
 *
 * The test uses {@link DefaultEnginePersistenceService} to seed the bootstrap with the desired baseline, then
 * boots a real `Evita` instance and inspects:
 *
 * - the resulting catalog states (ALIVE/INACTIVE/MISSING),
 * - the engine version (must advance by `becomeMissing + reappeared + autoDiscovered`),
 * - the engine WAL contents (one mutation per divergence entry; `becomeMissing` records strictly precede any
 *   restore, but `reappeared` and `autoDiscovered` are dispatched in parallel and may appear in either order),
 * - the next-boot idempotency (no further divergence after the first drain).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@DisplayName("Evita boot-time catalog inventory divergence drain")
@Tag(ENGINE)
@Tag(MANAGEMENT)
@Tag(WAL)
class EvitaBootDivergenceWalTest implements EvitaTestSupport {
	private TestPaths testPaths;
	private Path storageDirectory;

	@BeforeEach
	void setUp() throws IOException {
		this.testPaths = createTestPaths(EvitaBootDivergenceWalTest.class.getSimpleName());
		this.storageDirectory = this.testPaths.storage();
		Files.createDirectories(this.storageDirectory);
	}

	@AfterEach
	void tearDown() {
		cleanupTestPaths(this.testPaths);
	}

	/**
	 * Creates a folder in the shape boot-time discovery is willing to adopt: suffix-free and holding a catalog
	 * bootstrap file. A bare directory is classified as junk and deliberately left alone, since registering
	 * every unknown directory is what turned an operator's stray folder into a catalog the engine claimed.
	 *
	 * Folders standing in for a *reappeared* catalog need none of this — those are found through their binding
	 * rather than through discovery — which is why the `d` fixtures below stay bare.
	 *
	 * @param catalogName name of the catalog whose folder is being faked
	 * @throws IOException when the folder or the bootstrap file cannot be created
	 */
	private void createDiscoverableCatalogFolder(@Nonnull String catalogName) throws IOException {
		final Path folder = Files.createDirectory(this.storageDirectory.resolve(catalogName));
		Files.createFile(folder.resolve(catalogName + ".boot"));
	}

	@Test
	@DisplayName("should mark inactive catalog with missing folder as MISSING via WAL on boot")
	void shouldMarkCatalogWithMissingFolderAsMissing() {
		// Persist a baseline state at v=2 with one inactive catalog whose folder does not exist on disk.
		// `MarkCatalogMissingMutation(b)` must be issued during boot, bumping engine version to 3.
		// We use INACTIVE rather than ACTIVE so the post-divergence state has no active catalogs to
		// schedule for loading — that keeps the test focused on the WAL drain instead of fixturing a
		// real on-disk catalog payload for an active stub.
		final long seedVersion = seedEngineState(2L, ArrayUtils.EMPTY_STRING_ARRAY, new String[]{"b"}, ArrayUtils.EMPTY_STRING_ARRAY);

		try (final Evita evita = bootEvita()) {
			evita.waitUntilFullyInitialized();
			assertEquals(seedVersion + 1, evita.getEngineState().version(),
				"Engine version must advance once for the MarkCatalogMissingMutation drained at boot.");
			assertEquals(CatalogState.MISSING, evita.getCatalogState("b").orElseThrow());

			final List<EngineMutation<?>> walMutations = readWalMutations(evita, seedVersion + 1);
			assertEquals(1, walMutations.size());
			assertInstanceOf(MarkCatalogMissingMutation.class, walMutations.get(0));
			assertEquals("b", ((MarkCatalogMissingMutation) walMutations.get(0)).getCatalogName());
		}
	}

	@Test
	@DisplayName("should restore reappeared MISSING catalog to INACTIVE via WAL on boot")
	void shouldRestoreReappearedMissingCatalogToInactive() throws IOException {
		// Persist a baseline state at v=2 with `d` parked in the missing bucket; recreate the folder so
		// boot-time divergence detection sees it as reappeared. Expected: RestoreCatalogSchemaMutation(d).
		Files.createDirectory(this.storageDirectory.resolve("d"));
		final long seedVersion = seedEngineState(
			2L,
			ArrayUtils.EMPTY_STRING_ARRAY,
			ArrayUtils.EMPTY_STRING_ARRAY,
			new String[]{"d"}
		);

		try (final Evita evita = bootEvita()) {
			evita.waitUntilFullyInitialized();
			assertEquals(seedVersion + 1, evita.getEngineState().version());
			assertEquals(CatalogState.INACTIVE, evita.getCatalogState("d").orElseThrow());
			assertEquals(0, evita.getEngineState().engineState().missingCatalogs().length);

			final List<EngineMutation<?>> walMutations = readWalMutations(evita, seedVersion + 1);
			assertEquals(1, walMutations.size());
			assertInstanceOf(RestoreCatalogSchemaMutation.class, walMutations.get(0));
			assertEquals("d", ((RestoreCatalogSchemaMutation) walMutations.get(0)).getCatalogName());
		}
	}

	@Test
	@DisplayName("should register auto-discovered folder as INACTIVE via WAL on boot")
	void shouldRegisterAutoDiscoveredFolderAsInactive() throws IOException {
		// Empty baseline, single folder on disk → auto-discovered.
		createDiscoverableCatalogFolder("c");
		final long seedVersion = seedEngineState(2L, ArrayUtils.EMPTY_STRING_ARRAY, ArrayUtils.EMPTY_STRING_ARRAY, ArrayUtils.EMPTY_STRING_ARRAY);

		try (final Evita evita = bootEvita()) {
			evita.waitUntilFullyInitialized();
			assertEquals(seedVersion + 1, evita.getEngineState().version());
			assertEquals(CatalogState.INACTIVE, evita.getCatalogState("c").orElseThrow());

			final List<EngineMutation<?>> walMutations = readWalMutations(evita, seedVersion + 1);
			assertEquals(1, walMutations.size());
			assertInstanceOf(RestoreCatalogSchemaMutation.class, walMutations.get(0));
			assertEquals("c", ((RestoreCatalogSchemaMutation) walMutations.get(0)).getCatalogName());
		}
	}

	@Test
	@DisplayName("should drain mixed divergence with becomeMissing committed before any restore")
	void shouldDrainMixedDivergenceWithBecomeMissingBeforeRestores() throws IOException {
		// Baseline: inactive=[b], missing=[d]. On disk: d, c (c is brand new, b is gone).
		// Expected drain sequence:
		// 1) MarkCatalogMissingMutation(b)                     — Phase 1, awaited to completion
		// 2) RestoreCatalogSchemaMutation(d) and RestoreCatalogSchemaMutation(c) in EITHER order — Phase 2
		//    dispatches them in parallel so their relative WAL order is non-deterministic by design.
		Files.createDirectory(this.storageDirectory.resolve("d"));
		createDiscoverableCatalogFolder("c");
		final long seedVersion = seedEngineState(2L, ArrayUtils.EMPTY_STRING_ARRAY, new String[]{"b"}, new String[]{"d"});

		try (final Evita evita = bootEvita()) {
			evita.waitUntilFullyInitialized();
			assertEquals(seedVersion + 3, evita.getEngineState().version(),
				"Engine version must advance once per divergence entry (3 mutations).");
			assertEquals(CatalogState.MISSING, evita.getCatalogState("b").orElseThrow());
			assertEquals(CatalogState.INACTIVE, evita.getCatalogState("d").orElseThrow());
			assertEquals(CatalogState.INACTIVE, evita.getCatalogState("c").orElseThrow());

			final List<EngineMutation<?>> walMutations = readWalMutations(evita, seedVersion + 1);
			assertEquals(3, walMutations.size());
			// Phase 1 — strictly first, regardless of Phase 2 races.
			assertInstanceOf(MarkCatalogMissingMutation.class, walMutations.get(0));
			assertEquals("b", ((MarkCatalogMissingMutation) walMutations.get(0)).getCatalogName());
			// Phase 2 — both restores must be present, but their relative order is not guaranteed.
			assertInstanceOf(RestoreCatalogSchemaMutation.class, walMutations.get(1));
			assertInstanceOf(RestoreCatalogSchemaMutation.class, walMutations.get(2));
			final Set<String> phase2Names = Set.of(
				((RestoreCatalogSchemaMutation) walMutations.get(1)).getCatalogName(),
				((RestoreCatalogSchemaMutation) walMutations.get(2)).getCatalogName()
			);
			assertEquals(Set.of("c", "d"), phase2Names,
				"Phase 2 must restore both 'c' (auto-discovered) and 'd' (reappeared); their WAL order is racy by design.");
		}
	}

	@Test
	@DisplayName("should rename an adopted folder into the generation shape and bind the catalog to it")
	void shouldRenameAdoptedFolderIntoGenerationShape() throws IOException {
		// A bare `c` folder is what an older evitaDB leaves behind and what an operator hand-copies in; the
		// two are indistinguishable on disk and take the same path. Adoption brings it into the canonical
		// shape so it participates in the generation scheme from then on.
		createDiscoverableCatalogFolder("c");
		seedEngineState(
			2L, ArrayUtils.EMPTY_STRING_ARRAY, ArrayUtils.EMPTY_STRING_ARRAY, ArrayUtils.EMPTY_STRING_ARRAY
		);

		try (final Evita evita = bootEvita()) {
			evita.waitUntilFullyInitialized();

			// asserted through the binding rather than against the string `c_1`: what has to be true is that
			// the engine points at the folder holding the data, not that a particular name was produced
			final CatalogFolderId bound = evita.getCatalogFolderContext().folderIdFor("c");
			assertEquals(new CatalogFolderId("c_1"), bound);
			assertTrue(Files.notExists(this.storageDirectory.resolve("c")));
			assertTrue(Files.exists(this.storageDirectory.resolve(bound.id()).resolve("c.boot")));
			assertEquals(
				"c",
				Files.readString(
					this.storageDirectory.resolve(bound.id())
						.resolve(CatalogPersistenceService.CATALOG_NAME_FLAG)
				)
			);

			// Asserted here rather than in a test of its own because adoption only happens during a boot that
			// finds an adoptable folder, and this test has already paid for exactly that boot - a dedicated
			// test would duplicate the whole fixture to assert one more thing about the same event.
			//
			// Boot adoption is the one path that puts a reservation into the map without handing the caller a
			// closeable handle - it is released indirectly, by the registering mutation's `completeFolder`. If
			// that release is ever dropped, an adopted catalog keeps a stale claim for the life of the process
			// and every later restore or duplicate over its name is refused with an error blaming concurrency
			// for a single-threaded cause.
			try (final CatalogFolderReservation laterAttempt =
				     evita.getCatalogFolderContext().allocateFolderFor("c")) {
				assertNotNull(laterAttempt.folderId());
			}
		}
	}

	@Test
	@DisplayName("should not adopt onto a generation a folder already on disk has taken")
	void shouldSkipGenerationsAlreadyPresentOnDiskWhenAdopting() throws IOException {
		// `c_4` is litter from an operation that died before persisting its generation peak, so nothing in
		// the engine state knows the number was handed out. The disk scan is the term that catches it — and
		// if it did not, adoption would try to rename onto an occupied name.
		createDiscoverableCatalogFolder("c");
		Files.createDirectory(this.storageDirectory.resolve("c_4"));
		seedEngineState(
			2L, ArrayUtils.EMPTY_STRING_ARRAY, ArrayUtils.EMPTY_STRING_ARRAY, ArrayUtils.EMPTY_STRING_ARRAY
		);

		try (final Evita evita = bootEvita()) {
			evita.waitUntilFullyInitialized();

			assertEquals(new CatalogFolderId("c_5"), evita.getCatalogFolderContext().folderIdFor("c"));
			assertTrue(Files.isDirectory(this.storageDirectory.resolve("c_4")),
				"Adoption must not disturb the folder whose name it skipped past!");
		}
	}

	@Test
	@DisplayName("should detect no further divergence on second boot (idempotency)")
	void shouldNotEmitFurtherWalMutationsOnSecondBoot() throws IOException {
		createDiscoverableCatalogFolder("c");
		final long seedVersion = seedEngineState(2L, ArrayUtils.EMPTY_STRING_ARRAY, ArrayUtils.EMPTY_STRING_ARRAY, ArrayUtils.EMPTY_STRING_ARRAY);

		// First boot — drains the auto-discovery and bumps version once.
		try (final Evita evita = bootEvita()) {
			evita.waitUntilFullyInitialized();
			assertEquals(seedVersion + 1, evita.getEngineState().version());
		}

		// Second boot — persistence service must observe the drained state and report no divergence,
		// so no additional WAL records are appended and the version stays where the first boot left it.
		try (final DefaultEnginePersistenceService probe = new DefaultEnginePersistenceService(
			storageOptions(), transactionOptions(), new Scheduler(new ImmediateScheduledThreadPoolExecutor())
		)) {
			final CatalogInventoryDivergence divergence = probe.getPendingCatalogInventoryDivergence();
			assertTrue(divergence.isEmpty(),
				"Second boot must observe no divergence — the first drain already reconciled state with disk.");
			assertEquals(seedVersion + 1, probe.getEngineState().version());
			assertFalse(probe.getEngineState().activeCatalogs().length == 0
					&& probe.getEngineState().inactiveCatalogs().length == 0,
				"Persistence service must reflect the state produced by the first boot's WAL drain.");
		}
	}

	/**
	 * Persists a baseline at the given version by appending a placeholder WAL entry first (so D.1's
	 * `walVersion == stateVersion` invariant is satisfied) and then storing the matching engine state.
	 * Returns the post-seed version so the test can read WAL mutations starting *after* the placeholder
	 * — that way the assertions count only the boot-time divergence drain, not the seed.
	 *
	 * @return the version of the seeded state — start reading WAL mutations from this value + 1
	 */
	private long seedEngineState(
		long version,
		@Nonnull String[] activeCatalogs,
		@Nonnull String[] inactiveCatalogs,
		@Nonnull String[] missingCatalogs
	) {
		try (final DefaultEnginePersistenceService seed = new DefaultEnginePersistenceService(
			storageOptions(), transactionOptions(), new Scheduler(new ImmediateScheduledThreadPoolExecutor())
		)) {
			seed.appendWalAndStoreState(
				version,
				java.util.UUID.randomUUID(),
				new io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation("seed"),
				txRef -> new EngineState<>(
					PersistenceService.STORAGE_PROTOCOL_VERSION,
					version,
					OffsetDateTime.now(),
					(LogFileRecordReference) txRef.walReference(),
					activeCatalogs,
					inactiveCatalogs,
					ArrayUtils.EMPTY_STRING_ARRAY,
					missingCatalogs
				)
			);
		}
		return version;
	}

	@Nonnull
	private Evita bootEvita() {
		return new Evita(
			newTestEvitaConfigurationBuilder(this.testPaths)
				.storage(storageOptions())
				.transaction(transactionOptions())
				.build()
		);
	}

	@Nonnull
	private StorageOptions storageOptions() {
		return StorageOptions.builder()
			.storageDirectory(this.storageDirectory)
			.workDirectory(this.testPaths.work())
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
	 * Reads the application-level engine mutations stored in the engine WAL starting at `fromVersion`,
	 * skipping over the bracketing `TransactionMutation` envelopes that wrap each commit.
	 */
	@Nonnull
	private static List<EngineMutation<?>> readWalMutations(@Nonnull Evita evita, long fromVersion) {
		final List<EngineMutation<?>> result = new ArrayList<>();
		evita.getEngineTransactionManager()
			.getCommittedMutationStream(fromVersion)
			.forEach(mutation -> {
				if (mutation instanceof TransactionMutation) {
					return;
				}
				result.add(mutation);
			});
		return result;
	}
}
