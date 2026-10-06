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

package io.evitadb.store.engine;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.MarkCatalogMissingMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.UpgradeCatalogFormatMutation;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.core.executor.ImmediateScheduledThreadPoolExecutor;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.catalog.exception.CatalogWriteAheadLastTransactionMismatchException;
import io.evitadb.spi.store.catalog.shared.model.TransactionMutationWithWalReference;
import io.evitadb.spi.store.catalog.wal.VersionSource;
import io.evitadb.spi.store.engine.model.AdoptableCatalogFolder;
import io.evitadb.spi.store.engine.model.CatalogFolderBinding;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.spi.store.engine.model.EngineState;
import io.evitadb.spi.store.engine.model.CatalogInventoryDivergence;
import io.evitadb.spi.store.engine.model.RetiredFolder;
import io.evitadb.spi.store.engine.EnginePersistenceService;
import io.evitadb.spi.store.engine.model.UnprocessedTransactionRecord;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.model.reference.TransactionMutationWithWalFileReference;
import io.evitadb.store.wal.AbstractMutationLog;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;

import static io.evitadb.spi.store.engine.EnginePersistenceService.STORAGE_PROTOCOL_VERSION;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.MANAGEMENT;

/**
 * This test verifies the behavior of {@link DefaultEnginePersistenceService}.
 *
 * Tests are grouped by area:
 *
 * - `StartupInvariant` — startup WAL/engine-state version invariant
 * - `CatalogInventoryReconciliation` — catalog-inventory reconciliation that must not bump the engine version
 * - `FusedAppendAndStoreState` — atomic fused append-and-store critical section
 * - `ForwardReplayPrimitives` — forward-replay primitives used by the transaction manager
 * - `CatalogLifecycleMutations` — catalog lifecycle mutations serialized through the WAL
 *
 * Legacy WAL stream queries and raw append tests live in the `WalOperations` group at the bottom.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("DefaultEnginePersistenceService functionality")
@Tag(STORAGE)
@Tag(MANAGEMENT)
class DefaultEnginePersistenceServiceTest implements EvitaTestSupport {
	private DefaultEnginePersistenceService service;
	private StorageOptions storageOptions;
	private TransactionOptions transactionOptions;
	private Scheduler scheduler;

	/**
	 * Creates a test EngineMutation for testing.
	 */
	@Nonnull
	private static EngineMutation createTestEngineMutation() {
		// Create a mock EngineMutation
		return createTestEngineMutation(TEST_CATALOG);
	}

	/**
	 * Creates a test EngineMutation for testing.
	 * @param catalogName name of the catalog for which the mutation is created
	 */
	@Nonnull
	private static EngineMutation createTestEngineMutation(String catalogName) {
		// Create a mock EngineMutation
		return new CreateCatalogSchemaMutation(catalogName);
	}

	/**
	 * Builds a minimal {@link EngineState} at the requested version, embedding the WAL reference produced by
	 * the fused commit primitive. Used by tests that only care about WAL stream content and do not need to
	 * assert any catalog bucket layout.
	 */
	@Nonnull
	private static EngineState<LogFileRecordReference> minimalEngineState(
		long version,
		@Nonnull TransactionMutationWithWalReference txRef
	) {
		return new EngineState<>(
			STORAGE_PROTOCOL_VERSION,
			version,
			OffsetDateTime.now(),
			(LogFileRecordReference) txRef.walReference(),
			ArrayUtils.EMPTY_STRING_ARRAY,
			ArrayUtils.EMPTY_STRING_ARRAY,
			ArrayUtils.EMPTY_STRING_ARRAY
		);
	}

	@BeforeEach
	void setUp() throws IOException {
		cleanTestSubDirectory(this.getClass().getSimpleName());
		final Path testDirectory = getPathInTargetDirectory(this.getClass().getSimpleName());
		assertTrue(testDirectory.toFile().mkdirs());

		// Create configuration for dependencies
		this.storageOptions =
			StorageOptions.builder()
			.storageDirectory(testDirectory)
			.build();
		this.transactionOptions =
			TransactionOptions.builder()
			.transactionMemoryBufferLimitSizeBytes(1024 << 10)
			.transactionMemoryRegionCount(4)
			.build();
		this.scheduler = new Scheduler(new ImmediateScheduledThreadPoolExecutor());

		// Create the service
		this.service = new DefaultEnginePersistenceService(
			this.storageOptions,
			this.transactionOptions,
			this.scheduler
		);
	}

	@AfterEach
	void tearDown() throws IOException {
		if (this.service != null) {
			this.service.close();
		}
		cleanTestSubDirectory(this.getClass().getSimpleName());
	}

	// Note: a dedicated protocol-migration test was removed because it required fabricating an impossible state
	// (v5-format WAL + bootstrap claiming v4 protocol) that real deployments never see; when migration ran in that
	// state it re-encoded the already-upgraded WAL and corrupted it. Migration version-preservation is enforced by
	// rewriteEngineStateInPlace() in DefaultEnginePersistenceService which asserts the version stays the same
	// whenever a caller rewrites the bootstrap in place.

	/**
	 * Tests for the startup invariant that enforces a consistent relationship between the
	 * persisted engine state version and the WAL last-written version at reboot.
	 */
	@Nested
	@DisplayName("Startup invariant")
	class StartupInvariant {

		@Test
		@DisplayName("should return if service is new")
		void shouldReturnIfServiceIsNew() {
			// Test the isNew method
			boolean isNew = DefaultEnginePersistenceServiceTest.this.service.isNew();

			// The service should be new since we're using an empty temp directory
			assertTrue(isNew);
		}

		@Test
		@DisplayName("should return engine state")
		void shouldReturnEngineState() {
			// Test the getEngineState method
			EngineState engineState = DefaultEnginePersistenceServiceTest.this.service.getEngineState();

			// Verify the engine state properties
			assertNotNull(engineState);
			assertEquals(STORAGE_PROTOCOL_VERSION, engineState.storageProtocolVersion());
			assertEquals(1L, engineState.version());
			assertNull(engineState.walReference());

			// A new engine state should have empty catalog arrays
			assertNotNull(engineState.activeCatalogs());
			assertNotNull(engineState.inactiveCatalogs());
		}

		@Test
		@DisplayName("should fail loudly when WAL is more than one step ahead of engine state on startup")
		void shouldFailLoudWhenWalMoreThanOneStepAheadOfEngineState() {
			// Append two WAL entries at versions 2 and 3 WITHOUT advancing the engine state. The startup invariant
			// allows walV == stateV + 1 (the single-mutation crash window that forward WAL replay handles), but any
			// larger drift still indicates real corruption and must fail loudly.
			DefaultEnginePersistenceServiceTest.this.service.appendWal(2L, UUID.randomUUID(), createTestEngineMutation());
			DefaultEnginePersistenceServiceTest.this.service.appendWal(3L, UUID.randomUUID(), createTestEngineMutation("other"));
			DefaultEnginePersistenceServiceTest.this.service.close();

			assertThrows(
				GenericEvitaInternalError.class,
				() -> DefaultEnginePersistenceServiceTest.this.service = new DefaultEnginePersistenceService(
					DefaultEnginePersistenceServiceTest.this.storageOptions,
					DefaultEnginePersistenceServiceTest.this.transactionOptions,
					DefaultEnginePersistenceServiceTest.this.scheduler
				),
				"Startup must fail loudly when WAL lastWrittenVersion exceeds engineState.version by more than one."
			);
		}

		@Test
		@DisplayName("should fail loudly when engine state is ahead of WAL on startup")
		void shouldFailLoudWhenEngineStateAheadOfWal() {
			// Advance the engine state to version 2 WITHOUT a matching WAL append.
			// This is the opposite drift direction — the bootstrap file claims a
			// mutation was committed but the WAL has no record of it. There is no
			// legitimate runtime path that produces this, so startup must fail.
			final EngineState<LogFileRecordReference> stateWithoutWal = new EngineState<>(
				STORAGE_PROTOCOL_VERSION,
				2L,
				OffsetDateTime.now(),
				null,
				ArrayUtils.EMPTY_STRING_ARRAY,
				ArrayUtils.EMPTY_STRING_ARRAY,
				ArrayUtils.EMPTY_STRING_ARRAY
			);
			DefaultEnginePersistenceServiceTest.this.service.storeEngineState(stateWithoutWal);
			DefaultEnginePersistenceServiceTest.this.service.close();

			assertThrows(
				GenericEvitaInternalError.class,
				() -> DefaultEnginePersistenceServiceTest.this.service = new DefaultEnginePersistenceService(
					DefaultEnginePersistenceServiceTest.this.storageOptions,
					DefaultEnginePersistenceServiceTest.this.transactionOptions,
					DefaultEnginePersistenceServiceTest.this.scheduler
				),
				"Startup must fail loudly when engineState.version > WAL lastWrittenVersion."
			);
		}

		@Test
		@DisplayName("should start cleanly when WAL and engine state versions match")
		void shouldStartCleanlyWhenWalAndEngineStateMatch() throws IOException {
			// Normal non-crashed path: fused WAL append + state store leaves both at version 2.
			DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
				2L,
				UUID.randomUUID(),
				createTestEngineMutation(),
				txRef -> new EngineState<>(
					STORAGE_PROTOCOL_VERSION,
					2L,
					OffsetDateTime.now(),
					(LogFileRecordReference) txRef.walReference(),
					new String[]{TEST_CATALOG},
					ArrayUtils.EMPTY_STRING_ARRAY,
					ArrayUtils.EMPTY_STRING_ARRAY
				)
			);
			DefaultEnginePersistenceServiceTest.this.service.close();

			// Create the catalog folder on disk so that folder-sync reconciliation on reboot does not
			// strip the active catalog. Using the real filesystem is equivalent to stubbing
			// `FileUtils.listDirectories` and avoids the `MockedStatic` overhead.
			Files.createDirectory(
				DefaultEnginePersistenceServiceTest.this.storageOptions.storageDirectory().resolve(TEST_CATALOG));

			DefaultEnginePersistenceServiceTest.this.service = new DefaultEnginePersistenceService(
				DefaultEnginePersistenceServiceTest.this.storageOptions,
				DefaultEnginePersistenceServiceTest.this.transactionOptions,
				DefaultEnginePersistenceServiceTest.this.scheduler
			);

			final EngineState<LogFileRecordReference> reloaded = DefaultEnginePersistenceServiceTest.this.service.getEngineState();
			assertEquals(2L, reloaded.version());
			assertEquals(2L, DefaultEnginePersistenceServiceTest.this.service.getLastVersionInMutationStream());
		}

		@Test
		@DisplayName("should allow WAL one step ahead of engine state on startup")
		void shouldAllowWalOneStepAheadOfEngineStateOnStartup() {
			// The fail-loud startup check is relaxed to allow `walVersion == stateVersion + 1`. This is the narrow
			// OS-crash window where
			// `appendWalAndStoreState` appended to the WAL but crashed before the bootstrap
			// rewrite completed. Forward replay itself lives in EngineTransactionManager;
			// at this layer we only assert that the persistence service boots cleanly
			// and reports the drift via getEngineState()/getLastVersionInMutationStream().
			DefaultEnginePersistenceServiceTest.this.service.appendWal(2L, UUID.randomUUID(), createTestEngineMutation());
			DefaultEnginePersistenceServiceTest.this.service.close();

			// The service must boot without throwing even though WAL is at 2 and state at 1.
			DefaultEnginePersistenceServiceTest.this.service = new DefaultEnginePersistenceService(
				DefaultEnginePersistenceServiceTest.this.storageOptions,
				DefaultEnginePersistenceServiceTest.this.transactionOptions,
				DefaultEnginePersistenceServiceTest.this.scheduler
			);

			assertEquals(1L, DefaultEnginePersistenceServiceTest.this.service.getEngineState().version(),
			             "Engine state must still report the pre-crash version on reboot.");
			assertEquals(2L, DefaultEnginePersistenceServiceTest.this.service.getLastVersionInMutationStream(),
			             "WAL must report the committed version so forward replay can reconcile.");
		}

		@Test
		@DisplayName("should not mutate bootstrap when WAL is ahead by more than one on startup")
		void shouldNotMutateBootstrapWhenWalAheadByMoreThanOne() throws IOException {
			// Regression guard for the boot-time reconciliation ordering bug. If folder reconciliation ran
			// BEFORE the startup invariant check, a drifted state on disk would be "silently"
			// mended — the bootstrap file
			// would be rewritten with the reconciled catalog arrays even though startup was about to
			// throw. Any such rewrite is forbidden: on a drifted state we must surface the problem
			// without touching persistent state, otherwise the original drift fingerprint is lost and
			// operators cannot diagnose what actually happened on disk.
			//
			// We build a state that (a) violates D.1 (walV > stateV + 1) AND (b) would normally trigger
			// a rewrite-in-place by folder-sync — the bootstrap claims active catalogs that do not
			// exist on disk. Expected behaviour: startup throws AND the bootstrap file's last-modified
			// timestamp is unchanged.
			DefaultEnginePersistenceServiceTest.this.service.appendWal(2L, UUID.randomUUID(), createTestEngineMutation("a"));
			DefaultEnginePersistenceServiceTest.this.service.appendWal(3L, UUID.randomUUID(), createTestEngineMutation("b"));
			DefaultEnginePersistenceServiceTest.this.service.appendWal(4L, UUID.randomUUID(), createTestEngineMutation("c"));
			DefaultEnginePersistenceServiceTest.this.service.appendWal(5L, UUID.randomUUID(), createTestEngineMutation("d"));

			// Persist an engine state at v=2 that claims ghost catalogs — they have no directory on
			// disk so folder-sync would want to move them into `missingCatalogs` and rewrite the file.
			// Writing at v=2 keeps WAL at 5 and state at 2 so the overall drift is >1.
			final EngineState<LogFileRecordReference> driftedState = new EngineState<>(
				STORAGE_PROTOCOL_VERSION,
				2L,
				OffsetDateTime.now(),
				null,
				new String[]{"ghost-a", "ghost-b"},
				ArrayUtils.EMPTY_STRING_ARRAY,
				ArrayUtils.EMPTY_STRING_ARRAY
			);
			DefaultEnginePersistenceServiceTest.this.service.storeEngineState(driftedState);
			DefaultEnginePersistenceServiceTest.this.service.close();

			// Record the last-modified timestamp of the bootstrap file so we can verify nothing touches
			// it during the failed reboot.
			final Path bootstrapFile = DefaultEnginePersistenceServiceTest.this.storageOptions.storageDirectory().resolve("evitaDB.boot");
			assertTrue(Files.exists(bootstrapFile));
			final long bootstrapMtimeBefore = Files.getLastModifiedTime(bootstrapFile).toMillis();

			// Sleep to ensure the filesystem clock moves forward — on some filesystems mtime resolution
			// is second-level, so a rewrite that happens within the same second would be invisible.
			try {
				Thread.sleep(1_050L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}

			assertThrows(
				GenericEvitaInternalError.class,
				() -> DefaultEnginePersistenceServiceTest.this.service = new DefaultEnginePersistenceService(
					DefaultEnginePersistenceServiceTest.this.storageOptions, DefaultEnginePersistenceServiceTest.this.transactionOptions, DefaultEnginePersistenceServiceTest.this.scheduler
				),
				"Startup must fail loudly before any bootstrap rewrite can happen on a drifted state."
			);

			final long bootstrapMtimeAfter = Files.getLastModifiedTime(bootstrapFile).toMillis();
			assertEquals(
				bootstrapMtimeBefore, bootstrapMtimeAfter,
				"Bootstrap file must not be rewritten when D.1 is about to throw — the drift fingerprint "
					+ "must survive the failed boot so operators can diagnose the root cause."
			);
		}

	}

	/**
	 * Tests for the boot-time catalog-inventory-divergence detection. The persistence service no longer
	 * rewrites the bootstrap in place when active/inactive catalogs are missing on disk. Instead it
	 * computes a {@link CatalogInventoryDivergence} value during construction and exposes it through
	 * {@link DefaultEnginePersistenceService#getPendingCatalogInventoryDivergence()}. The actual reconciliation
	 * is later performed by `Evita` through WAL-backed engine mutations once
	 * `EngineTransactionManager` is available.
	 */
	@Nested
	@DisplayName("Boot-time catalog inventory divergence")
	class CatalogInventoryReconciliation {

		@Test
		@DisplayName("should preserve persisted active/inactive arrays when folders are present on disk")
		void shouldPreservePersistedArraysWhenFoldersPresent() throws IOException {
			// Fused WAL append + state store advances both to version 2 in one critical section, so the startup
			// invariant sees WAL lastWrittenVersion == engineState.version on every reboot below.
			DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
				2L,
				UUID.randomUUID(),
				createTestEngineMutation(),
				txRef -> new EngineState<>(
					STORAGE_PROTOCOL_VERSION,
					2L,
					OffsetDateTime.now(),
					(LogFileRecordReference) txRef.walReference(),
					new String[]{"catalog1", "catalog2", "catalog3"},
					new String[]{"inactiveCatalog"},
					new String[]{"readOnlyCatalog"}
				)
			);

			// Verify the engine state was updated
			EngineState retrievedState = DefaultEnginePersistenceServiceTest.this.service.getEngineState();
			assertEquals(2L, retrievedState.version());
			assertEquals(3, retrievedState.activeCatalogs().length);
			assertEquals(1, retrievedState.inactiveCatalogs().length);

			// try to restart the service to ensure the state is persisted
			DefaultEnginePersistenceServiceTest.this.service.close();

			// Create real directories on disk for every catalog listed in the stored state so that
			// boot-time divergence detection finds no drift on reboot. Using the real filesystem
			// keeps the test faithful to production behaviour instead of stubbing
			// `FileUtils.listDirectories` with `MockedStatic`.
			final Path storageDirectory = DefaultEnginePersistenceServiceTest.this.storageOptions.storageDirectory();
			Files.createDirectory(storageDirectory.resolve("catalog1"));
			Files.createDirectory(storageDirectory.resolve("catalog2"));
			Files.createDirectory(storageDirectory.resolve("catalog3"));
			Files.createDirectory(storageDirectory.resolve("inactiveCatalog"));

			DefaultEnginePersistenceServiceTest.this.service = new DefaultEnginePersistenceService(
				DefaultEnginePersistenceServiceTest.this.storageOptions,
				DefaultEnginePersistenceServiceTest.this.transactionOptions,
				DefaultEnginePersistenceServiceTest.this.scheduler
			);

			// Verify the engine state is still persisted after restart and no divergence is exposed.
			final EngineState restartedState = DefaultEnginePersistenceServiceTest.this.service.getEngineState();
			assertEquals(2L, restartedState.version());
			assertEquals(3, restartedState.activeCatalogs().length);
			assertEquals(1, restartedState.inactiveCatalogs().length);
			assertTrue(
				DefaultEnginePersistenceServiceTest.this.service.getPendingCatalogInventoryDivergence().isEmpty(),
				"All folders present on disk — divergence drain must have nothing to do."
			);
		}

		@Test
		@DisplayName("should expose missing catalogs as becomeMissing divergence without rewriting bootstrap")
		void shouldExposeBecomeMissingDivergence() {
			// Fused WAL append + state store keeps WAL lastWrittenVersion == engineState.version. The state
			// claims catalogs on disk that do not actually exist — this is the exact scenario the boot-time
			// divergence drain reconciles via WAL mutations.
			DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
				2L,
				UUID.randomUUID(),
				createTestEngineMutation(),
				txRef -> new EngineState<>(
					STORAGE_PROTOCOL_VERSION,
					2L,
					OffsetDateTime.now(),
					(LogFileRecordReference) txRef.walReference(),
					new String[]{"ghost-a", "ghost-b"},
					ArrayUtils.EMPTY_STRING_ARRAY,
					ArrayUtils.EMPTY_STRING_ARRAY
				)
			);
			DefaultEnginePersistenceServiceTest.this.service.close();

			// Reboot — with no directories on disk the persistence service must keep the engine
			// state untouched (so the WAL-first invariant stays intact) and report the divergence
			// through the SPI for `Evita` to drain via WAL mutations.
			DefaultEnginePersistenceServiceTest.this.service = new DefaultEnginePersistenceService(
				DefaultEnginePersistenceServiceTest.this.storageOptions,
				DefaultEnginePersistenceServiceTest.this.transactionOptions,
				DefaultEnginePersistenceServiceTest.this.scheduler
			);

			final EngineState<LogFileRecordReference> reloaded = DefaultEnginePersistenceServiceTest.this.service.getEngineState();
			assertEquals(
				2L, reloaded.version(),
				"Catalog-inventory-divergence detection must not bump engine version (would drift WAL ↔ state)."
			);
			assertEquals(2, reloaded.activeCatalogs().length,
				"Persisted active catalogs must survive verbatim — drain happens later through WAL.");
			assertEquals(0, reloaded.inactiveCatalogs().length);

			final CatalogInventoryDivergence divergence = DefaultEnginePersistenceServiceTest.this.service.getPendingCatalogInventoryDivergence();
			assertFalse(divergence.isEmpty());
			assertEquals(List.of("ghost-a", "ghost-b"), divergence.becomeMissing(),
				"Both ghost active catalogs must be staged for MISSING transition (alphabetically sorted).");
			assertTrue(divergence.reappeared().isEmpty());
			assertTrue(divergence.autoDiscovered().isEmpty());
		}

		@Test
		@DisplayName("should leave a discovered folder alone when its name cannot be adopted")
		void shouldRefuseToAdoptFoldersWhoseNameIsUnusable() throws IOException {
			// Adoption renames the folder into the shape the engine allocates and only then dispatches the
			// mutation that validates the name. A folder that cannot be registered under its own name must
			// therefore be rejected here, before anything is moved - otherwise boot reconciliation fails after
			// the operator's import has already been renamed out from under them.
			final Path storageDirectory = DefaultEnginePersistenceServiceTest.this.storageOptions.storageDirectory();
			// a name a catalog may not have - the classifier format allows no spaces
			final Path unusableName = Files.createDirectory(storageDirectory.resolve("not a catalog"));
			Files.createFile(unusableName.resolve("not a catalog.boot"));
			// a name that is free on disk but already belongs to a registered catalog living elsewhere
			final Path takenName = Files.createDirectory(storageDirectory.resolve("present"));
			Files.createFile(takenName.resolve("present.boot"));

			DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
				2L,
				UUID.randomUUID(),
				createTestEngineMutation(),
				txRef -> EngineState.<LogFileRecordReference>builder()
					.storageProtocolVersion(STORAGE_PROTOCOL_VERSION)
					.version(2L)
					.introducedAt(OffsetDateTime.now())
					.walFileReference((LogFileRecordReference) txRef.walReference())
					.inactiveCatalogs(new String[]{"present"})
					.catalogFolders(
						new CatalogFolderBinding[]{
							new CatalogFolderBinding("present", new CatalogFolderId("present_1"))
						}
					)
					.build()
			);
			DefaultEnginePersistenceServiceTest.this.service.close();

			DefaultEnginePersistenceServiceTest.this.service = new DefaultEnginePersistenceService(
				DefaultEnginePersistenceServiceTest.this.storageOptions,
				DefaultEnginePersistenceServiceTest.this.transactionOptions,
				DefaultEnginePersistenceServiceTest.this.scheduler
			);

			final CatalogInventoryDivergence divergence =
				DefaultEnginePersistenceServiceTest.this.service.getPendingCatalogInventoryDivergence();
			assertTrue(
				divergence.autoDiscovered().isEmpty(),
				() -> "Neither folder may be offered for adoption, but got: " + divergence.autoDiscovered()
			);
			// and both are exactly where the operator left them
			assertTrue(Files.isDirectory(unusableName), "An unadoptable folder must not be touched!");
			assertTrue(Files.isDirectory(takenName), "A folder whose name is taken must not be touched!");
		}

		@Test
		@DisplayName("should expose reappeared and autoDiscovered divergence categories")
		void shouldExposeReappearedAndAutoDiscoveredDivergence() throws IOException {
			// Build a baseline state where one catalog already sits in the missing bucket and one
			// inactive catalog has its folder present on disk. Add an extra folder that the engine
			// state knows nothing about so we exercise auto-discovery too.
			final Path storageDirectory = DefaultEnginePersistenceServiceTest.this.storageOptions.storageDirectory();
			Files.createDirectory(storageDirectory.resolve("flapping"));
			Files.createDirectory(storageDirectory.resolve("present"));
			// A folder is only offered for adoption when it actually holds a catalog, so the discovered one
			// needs a bootstrap file - a bare directory is classified as junk and deliberately left alone.
			// `flapping` and `present` need none: both are reached through their binding, not by discovery.
			Files.createDirectory(storageDirectory.resolve("discovered"));
			Files.createFile(storageDirectory.resolve("discovered").resolve("discovered.boot"));

			DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
				2L,
				UUID.randomUUID(),
				createTestEngineMutation(),
				txRef -> new EngineState<>(
					STORAGE_PROTOCOL_VERSION,
					2L,
					OffsetDateTime.now(),
					(LogFileRecordReference) txRef.walReference(),
					ArrayUtils.EMPTY_STRING_ARRAY,
					new String[]{"present"},
					ArrayUtils.EMPTY_STRING_ARRAY,
					new String[]{"flapping"}
				)
			);
			DefaultEnginePersistenceServiceTest.this.service.close();

			DefaultEnginePersistenceServiceTest.this.service = new DefaultEnginePersistenceService(
				DefaultEnginePersistenceServiceTest.this.storageOptions,
				DefaultEnginePersistenceServiceTest.this.transactionOptions,
				DefaultEnginePersistenceServiceTest.this.scheduler
			);

			final CatalogInventoryDivergence divergence = DefaultEnginePersistenceServiceTest.this.service.getPendingCatalogInventoryDivergence();
			assertTrue(divergence.becomeMissing().isEmpty());
			assertEquals(List.of("flapping"), divergence.reappeared());
			// the folder token travels beside the name: the two coincide for an adoptable folder, which is
			// suffix-free by definition, but the drain must not have to re-derive one from the other
			assertEquals(
				List.of(new AdoptableCatalogFolder("discovered", new CatalogFolderId("discovered"))),
				divergence.autoDiscovered()
			);

			// Engine state must remain untouched — the persistence service does not rewrite the
			// bootstrap; `Evita` will drain the divergence through WAL-backed mutations.
			final EngineState<LogFileRecordReference> reloaded = DefaultEnginePersistenceServiceTest.this.service.getEngineState();
			assertEquals(2L, reloaded.version());
			assertEquals(List.of("flapping"), Arrays.asList(reloaded.missingCatalogs()));
			assertEquals(List.of("present"), Arrays.asList(reloaded.inactiveCatalogs()));
		}

		@Test
		@DisplayName("should neither adopt nor remove an unreferenced folder holding no bootstrap file")
		void shouldLeaveFolderWithoutBootstrapFileAlone() throws IOException {
			// Registering every unknown directory is the hole this closes: it turned an operator's stray
			// folder into a catalog the engine claimed to own. The folder must survive untouched all the
			// same - we have no evidence it is ours, and removing it would be unrecoverable.
			final Path storageDirectory = DefaultEnginePersistenceServiceTest.this.storageOptions.storageDirectory();
			final Path stray = Files.createDirectory(storageDirectory.resolve("stray"));
			Files.createFile(stray.resolve("notes.txt"));

			DefaultEnginePersistenceServiceTest.this.service.close();
			DefaultEnginePersistenceServiceTest.this.service = new DefaultEnginePersistenceService(
				DefaultEnginePersistenceServiceTest.this.storageOptions,
				DefaultEnginePersistenceServiceTest.this.transactionOptions,
				DefaultEnginePersistenceServiceTest.this.scheduler
			);

			final CatalogInventoryDivergence divergence =
				DefaultEnginePersistenceServiceTest.this.service.getPendingCatalogInventoryDivergence();
			assertTrue(
				divergence.autoDiscovered().isEmpty(),
				"A folder without a bootstrap file must not be offered for adoption!"
			);
			assertTrue(Files.isDirectory(stray), "The folder must be left exactly where it is!");
			assertTrue(Files.exists(stray.resolve("notes.txt")), "Its contents must be left alone too!");
		}

		@Test
		@DisplayName("should neither adopt nor remove an unreferenced folder carrying a generation suffix")
		void shouldLeaveSuffixedUnreferencedFolderAlone() throws IOException {
			// Discovery is restricted to suffix-free names, so a folder shaped like one evitaDB allocated is
			// reported rather than reclaimed - it is most likely copied in from another instance.
			final Path storageDirectory = DefaultEnginePersistenceServiceTest.this.storageOptions.storageDirectory();
			final Path unclaimed = Files.createDirectory(storageDirectory.resolve("products_7"));
			Files.createFile(unclaimed.resolve("products.boot"));

			DefaultEnginePersistenceServiceTest.this.service.close();
			DefaultEnginePersistenceServiceTest.this.service = new DefaultEnginePersistenceService(
				DefaultEnginePersistenceServiceTest.this.storageOptions,
				DefaultEnginePersistenceServiceTest.this.transactionOptions,
				DefaultEnginePersistenceServiceTest.this.scheduler
			);

			final CatalogInventoryDivergence divergence =
				DefaultEnginePersistenceServiceTest.this.service.getPendingCatalogInventoryDivergence();
			assertTrue(
				divergence.autoDiscovered().isEmpty(),
				"A suffixed folder must not be offered for adoption!"
			);
			assertTrue(Files.exists(unclaimed.resolve("products.boot")), "The folder must be left untouched!");
		}

		@Test
		@DisplayName("should delete a tombstoned folder at boot and report it so its tombstone can be discharged")
		void shouldDrainAPersistedTombstoneAtBoot() throws IOException {
			// Classification, deletion, the Kryo round-trip and the in-run discharge each have a test of their
			// own; nothing proved that a tombstone which *survived a restart* is acted on at all. No bootstrap
			// file is needed - classification matches the tombstone before it ever looks for one.
			final Path storageDirectory = DefaultEnginePersistenceServiceTest.this.storageOptions.storageDirectory();
			final Path retired = Files.createDirectory(storageDirectory.resolve("products_4"));
			Files.createFile(retired.resolve("leftover.dat"));

			DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
				2L,
				UUID.randomUUID(),
				createTestEngineMutation(),
				txRef -> EngineState.<LogFileRecordReference>builder()
					.storageProtocolVersion(STORAGE_PROTOCOL_VERSION)
					.version(2L)
					.introducedAt(OffsetDateTime.now())
					.walFileReference((LogFileRecordReference) txRef.walReference())
					.retiredFolders(
						new RetiredFolder[]{
							new RetiredFolder("products", new CatalogFolderId("products_4"))
						}
					)
					.build()
			);
			DefaultEnginePersistenceServiceTest.this.service.close();

			DefaultEnginePersistenceServiceTest.this.service = new DefaultEnginePersistenceService(
				DefaultEnginePersistenceServiceTest.this.storageOptions,
				DefaultEnginePersistenceServiceTest.this.transactionOptions,
				DefaultEnginePersistenceServiceTest.this.scheduler
			);

			final CatalogInventoryDivergence divergence =
				DefaultEnginePersistenceServiceTest.this.service.getPendingCatalogInventoryDivergence();

			// The two assertions catch different reverts, which is why both are here. Dropping RETIRED from the
			// cleaner's drained states leaves the folder sitting on disk; dropping the removed-folder disjunct
			// deletes it but reports nothing, so the engine goes on owing a deletion it has already performed
			// and no later classification ever refills the entry.
			assertTrue(Files.notExists(retired), "A tombstoned folder must be removed at boot!");
			assertTrue(
				divergence.drainedFolders().contains(new CatalogFolderId("products_4")),
				() -> "The removal must be reported so the tombstone can be discharged, but got: " +
					divergence.drainedFolders()
			);
		}

	}

	/**
	 * Tests for the the fused `appendWalAndStoreState` critical section that appends to the
	 * WAL and rewrites the bootstrap as one indivisible operation, preserving the D.1 invariant by
	 * construction.
	 */
	@Nested
	@DisplayName("Fused append-and-store")
	class FusedAppendAndStoreState {

		@Test
		@DisplayName("should atomically append WAL and store engine state in a single critical section")
		void shouldAtomicallyAppendWalAndStoreEngineState() throws IOException {
			// The fused method must append to the WAL AND write the bootstrap file as one indivisible critical
			// section. After a successful call both the WAL lastWrittenVersion and the engine state version must
			// equal the passed version (the startup invariant must hold by construction).
			final UUID transactionId = UUID.randomUUID();
			final EngineMutation<?> mutation = createTestEngineMutation();

			final TransactionMutationWithWalReference result = DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
				2L,
				transactionId,
				mutation,
				txRef -> new EngineState<>(
					STORAGE_PROTOCOL_VERSION,
					2L,
					OffsetDateTime.now(),
					(LogFileRecordReference) txRef.walReference(),
					new String[]{TEST_CATALOG},
					ArrayUtils.EMPTY_STRING_ARRAY,
					ArrayUtils.EMPTY_STRING_ARRAY
				)
			);

			assertNotNull(result);
			assertNotNull(result.walReference());
			assertInstanceOf(TransactionMutationWithWalFileReference.class, result);
			assertEquals(2L, result.transactionMutation().getVersion());
			assertEquals(transactionId, result.transactionMutation().getTransactionId());

			// Both sides of the WAL/state pair must now be at version 2.
			assertEquals(2L, DefaultEnginePersistenceServiceTest.this.service.getLastVersionInMutationStream());
			assertEquals(2L, DefaultEnginePersistenceServiceTest.this.service.getEngineState().version());

			// D.1 invariant: after close + reboot the versions must still match.
			DefaultEnginePersistenceServiceTest.this.service.close();

			// Create the catalog folder on disk so reconciliation does not strip it away on reboot.
			Files.createDirectory(
				DefaultEnginePersistenceServiceTest.this.storageOptions.storageDirectory().resolve(TEST_CATALOG));

			DefaultEnginePersistenceServiceTest.this.service = new DefaultEnginePersistenceService(
				DefaultEnginePersistenceServiceTest.this.storageOptions,
				DefaultEnginePersistenceServiceTest.this.transactionOptions,
				DefaultEnginePersistenceServiceTest.this.scheduler
			);

			assertEquals(2L, DefaultEnginePersistenceServiceTest.this.service.getEngineState().version());
			assertEquals(2L, DefaultEnginePersistenceServiceTest.this.service.getLastVersionInMutationStream());
		}

		@Test
		@DisplayName("should reject appendWalAndStoreState with non-incremental version")
		void shouldRejectAppendWalAndStoreStateWithNonIncrementalVersion() {
			// Current engine state version is 1 and no WAL entries exist yet; calling the fused method with
			// version 3 (skipping 2) must fail the strict
			// `previous + 1` invariant. The method must short-circuit BEFORE any
			// WAL side-effect so that no partial apply is observable afterwards.
			final Function<TransactionMutationWithWalReference, EngineState<LogFileRecordReference>> factory =
				txRef -> {
					throw new AssertionError("stateFactory must not be invoked when the version invariant fails");
				};

			assertThrows(
				GenericEvitaInternalError.class,
				() -> DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
					3L,
					UUID.randomUUID(),
					createTestEngineMutation(),
					factory
				)
			);

			// Neither the WAL nor the engine state may have advanced.
			assertEquals(1L, DefaultEnginePersistenceServiceTest.this.service.getVersion());
			assertEquals(0L, DefaultEnginePersistenceServiceTest.this.service.getLastVersionInMutationStream());
		}

		@Test
		@DisplayName("should propagate stateFactory exception cleanly and roll back WAL append")
		void shouldPropagateStateFactoryExceptionCleanly() {
			// All-or-nothing atomicity: when the state factory throws AFTER the WAL append has succeeded, the fused
			// method must roll back the WAL so that the service is left in a well-defined state — engine state
			// version unchanged AND (after rollback) the startup invariant holds on reboot.
			final RuntimeException simulated = new RuntimeException("simulated failure");
			final Function<TransactionMutationWithWalReference, EngineState<LogFileRecordReference>> failingFactory =
				txRef -> {
					throw simulated;
				};

			final RuntimeException thrown = assertThrows(
				RuntimeException.class,
				() -> DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
					2L,
					UUID.randomUUID(),
					createTestEngineMutation(),
					failingFactory
				)
			);
			assertSame(simulated, thrown);

			// Engine state version must be unchanged.
			assertEquals(1L, DefaultEnginePersistenceServiceTest.this.service.getVersion());

			// D.1 invariant must still hold on reboot — WAL has been rolled back to
			// pre-append, so it reports 0 and engine state reports 1 (the
			// "never-used service" legitimate case the startup check allows).
			DefaultEnginePersistenceServiceTest.this.service.close();
			DefaultEnginePersistenceServiceTest.this.service = new DefaultEnginePersistenceService(
				DefaultEnginePersistenceServiceTest.this.storageOptions,
				DefaultEnginePersistenceServiceTest.this.transactionOptions,
				DefaultEnginePersistenceServiceTest.this.scheduler
			);
			assertEquals(1L, DefaultEnginePersistenceServiceTest.this.service.getVersion());
			assertEquals(0L, DefaultEnginePersistenceServiceTest.this.service.getLastVersionInMutationStream());
		}

		/**
		 * A rolled-back append must leave the WAL readable. The transactions committed before it are still on disk
		 * and published, and the system change capture reads them while catching a lagging subscriber up - it bounds
		 * the read by the last version in the mutation stream and reads it through the live stream. Answering both
		 * as if no WAL existed until the next append re-opens the log stalls such a subscriber silently.
		 */
		@Test
		@DisplayName("should keep serving the WAL after an append was rolled back")
		void shouldKeepServingTheWalAfterARolledBackAppend() {
			final long firstVersion = DefaultEnginePersistenceServiceTest.this.service.getVersion() + 1;
			final long lastCommittedVersion = firstVersion + 3;
			for (long version = firstVersion; version <= lastCommittedVersion; version++) {
				final long committedVersion = version;
				DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
					committedVersion, UUID.randomUUID(), createTestEngineMutation("catalog" + committedVersion),
					txRef -> minimalEngineState(committedVersion, txRef)
				);
			}
			final RuntimeException simulated = new RuntimeException("simulated failure");
			final RuntimeException thrown = assertThrows(
				RuntimeException.class,
				() -> DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
					lastCommittedVersion + 1, UUID.randomUUID(), createTestEngineMutation("rolledBack"),
					txRef -> {
						throw simulated;
					}
				)
			);
			assertSame(simulated, thrown);

			assertEquals(
				lastCommittedVersion,
				DefaultEnginePersistenceServiceTest.this.service.getLastVersionInMutationStream(),
				"Versions " + firstVersion + " to " + lastCommittedVersion + " are committed and on disk - the " +
					"rollback removed only the transaction after them, so the WAL must not read as missing."
			);
			final long transactionCount;
			try (
				final Stream<EngineMutation<?>> stream = DefaultEnginePersistenceServiceTest.this.service
					.getCommittedLiveMutationStream(firstVersion, lastCommittedVersion, VersionSource.INTERNAL)
			) {
				transactionCount = stream.filter(TransactionMutation.class::isInstance).count();
			}
			assertEquals(
				lastCommittedVersion - firstVersion + 1,
				transactionCount,
				"Every committed transaction must still be readable right after a rolled-back append, not only " +
					"once a later append happens to re-open the log."
			);
		}

	}

	/**
	 * Tests for an engine WAL that rotates into a new file.
	 *
	 * Rotation runs inside the append that does not fit the current file any more: it finalizes the current file with
	 * its trailer, creates the next one holding only its cumulative checksum header, and only then appends. A crash in
	 * between leaves a WAL whose active file holds no transaction while every committed one sits in the finalized file
	 * before it. The tests produce that state from a real rotation: the append that rotated is written through the
	 * test-only {@link DefaultEnginePersistenceService#appendWal} - so the engine state stays one version behind, as
	 * the crash left it - and the new file is cut back to its header.
	 */
	@Nested
	@DisplayName("WAL rotation")
	class WalRotation {
		/**
		 * Size an engine WAL file may reach before it is rotated away - a handful of engine mutations fill it.
		 */
		private static final long ROTATING_WAL_FILE_SIZE_BYTES = 1_024L;
		/**
		 * Upper bound of the versions appended while waiting for the WAL to rotate.
		 */
		private static final long MAX_VERSION_BEFORE_ROTATION = 1_000L;

		/**
		 * Restarts the service with a WAL file size small enough to rotate.
		 */
		@BeforeEach
		void useRotatingWal() {
			DefaultEnginePersistenceServiceTest.this.service.close();
			DefaultEnginePersistenceServiceTest.this.transactionOptions = TransactionOptions
				.builder(DefaultEnginePersistenceServiceTest.this.transactionOptions)
				.walFileSizeBytes(ROTATING_WAL_FILE_SIZE_BYTES)
				.build();
			DefaultEnginePersistenceServiceTest.this.service = reopenService();
		}

		@Test
		@DisplayName("should boot when the WAL rotated and the process crashed before the first append to the new file")
		void shouldBootWhenTheWalRotatedAndCrashedBeforeTheFirstAppend() throws IOException {
			final long crashedVersion = appendUntilTheWalRotates();
			final long lastCommittedVersion = crashedVersion - 1;
			crashBeforeTheFirstAppendIntoTheRotatedFile();

			DefaultEnginePersistenceServiceTest.this.service = assertDoesNotThrow(
				this::reopenService,
				"The engine state and the WAL agree on version " + lastCommittedVersion + " - it is the last version " +
					"of the finalized WAL file before the empty active one. Startup must compare the state with the " +
					"last version of the whole WAL, not with the active file that holds nothing yet."
			);
			assertEquals(lastCommittedVersion, DefaultEnginePersistenceServiceTest.this.service.getVersion());
			assertEquals(
				lastCommittedVersion,
				DefaultEnginePersistenceServiceTest.this.service.getLastVersionInMutationStream()
			);
		}

		@Test
		@DisplayName("should refuse an append that repeats a version of the finalized WAL file")
		void shouldRefuseAnAppendThatRepeatsAVersionOfTheFinalizedWalFile() throws IOException {
			final long crashedVersion = appendUntilTheWalRotates();
			final long lastCommittedVersion = crashedVersion - 1;
			crashBeforeTheFirstAppendIntoTheRotatedFile();
			DefaultEnginePersistenceServiceTest.this.service = reopenService();

			assertThrows(
				CatalogWriteAheadLastTransactionMismatchException.class,
				() -> DefaultEnginePersistenceServiceTest.this.service.appendWal(
					lastCommittedVersion, UUID.randomUUID(), createTestEngineMutation("repeated")
				),
				"Version " + lastCommittedVersion + " is already in the finalized WAL file. The empty active file " +
					"continues it, so an append there must carry the next version - accepting a repeated one leaves " +
					"two files that do not continue one another, which the next startup refuses to open."
			);
		}

		@Test
		@DisplayName("should keep the finalized WAL file intact when startup discards an unfinished tail")
		void shouldKeepTheFinalizedWalFileIntactWhenStartupDiscardsAnUnfinishedTail() throws IOException {
			final long crashedVersion = appendUntilTheWalRotates();
			crashBeforeTheFirstAppendIntoTheRotatedFile();
			DefaultEnginePersistenceServiceTest.this.service = reopenService();

			// what `EngineTransactionManager` does on every startup that replays nothing: the engine state references
			// the last transaction of the finalized file, and only its trailer follows it there
			DefaultEnginePersistenceServiceTest.this.service.truncateWriteAheadLog(
				DefaultEnginePersistenceServiceTest.this.service.getEngineState().walReference()
			);
			DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
				crashedVersion, UUID.randomUUID(), createTestEngineMutation("afterCrash"),
				txRef -> minimalEngineState(crashedVersion, txRef)
			);
			DefaultEnginePersistenceServiceTest.this.service.close();

			DefaultEnginePersistenceServiceTest.this.service = assertDoesNotThrow(
				this::reopenService,
				"The bytes following the engine state's WAL reference in a finalized file are that file's trailer, " +
					"not an unfinished transaction. Cutting them off makes the next startup read the tail of a " +
					"transaction as the file's version range."
			);
			assertEquals(crashedVersion, DefaultEnginePersistenceServiceTest.this.service.getVersion());
			assertEquals(crashedVersion, DefaultEnginePersistenceServiceTest.this.service.getLastVersionInMutationStream());
		}

		@Test
		@DisplayName("should roll back an append that rotated the WAL to exactly the WAL before the append")
		void shouldRollBackAnAppendThatRotatedTheWal() {
			final String rotatedMessage = "the append rotated the WAL";
			long rolledBackVersion = -1L;
			for (long version = DefaultEnginePersistenceServiceTest.this.service.getVersion() + 1;
			     version <= MAX_VERSION_BEFORE_ROTATION && rolledBackVersion == -1L; version++) {
				final long appendedVersion = version;
				try {
					DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
						appendedVersion, UUID.randomUUID(), createTestEngineMutation("catalog" + appendedVersion),
						txRef -> {
							if (((LogFileRecordReference) txRef.walReference()).fileIndex() > 0) {
								throw new IllegalStateException(rotatedMessage);
							}
							return minimalEngineState(appendedVersion, txRef);
						}
					);
				} catch (IllegalStateException ex) {
					assertEquals(rotatedMessage, ex.getMessage());
					rolledBackVersion = appendedVersion;
				}
			}
			assertTrue(rolledBackVersion > 0L, "The WAL never rotated - lower ROTATING_WAL_FILE_SIZE_BYTES.");
			assertEquals(rolledBackVersion - 1, DefaultEnginePersistenceServiceTest.this.service.getVersion());
			assertFalse(
				walFilePath(1).toFile().exists(),
				"The WAL file the rolled-back append rotated into holds nothing but that transaction."
			);

			// the version that was rolled back is free again, and the WAL it is appended to must reopen
			final long retriedVersion = rolledBackVersion;
			DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
				retriedVersion, UUID.randomUUID(), createTestEngineMutation("retried"),
				txRef -> minimalEngineState(retriedVersion, txRef)
			);
			DefaultEnginePersistenceServiceTest.this.service.close();
			DefaultEnginePersistenceServiceTest.this.service = assertDoesNotThrow(
				this::reopenService,
				"A rollback that leaves the rolled-back transaction in the next WAL file, after a predecessor whose " +
					"trailer it cut off, leaves a WAL whose files do not continue one another."
			);
			assertEquals(retriedVersion, DefaultEnginePersistenceServiceTest.this.service.getVersion());
			assertEquals(retriedVersion, DefaultEnginePersistenceServiceTest.this.service.getLastVersionInMutationStream());
		}

		/**
		 * Once the bootstrap record names a transaction, rolling its WAL append back removes bytes a published record
		 * reaches. What the publish triggers afterwards - reporting the version as processed, which schedules the
		 * removal of rotated WAL files - is housekeeping, and its failure must not reach the rollback. The test makes
		 * it fail the way an abruptly shut down scheduler does: the append that rotates queues a removal the retention
		 * cannot run yet, and the scheduler goes away between that append and its publish.
		 */
		@Test
		@DisplayName("should keep a published append when the removal of rotated WAL files cannot be scheduled")
		void shouldKeepAPublishedAppendWhenTheRemovalOfRotatedWalFilesCannotBeScheduled() {
			DefaultEnginePersistenceServiceTest.this.service.close();
			// one kept file, so the very first rotation queues the removal of the file before it
			DefaultEnginePersistenceServiceTest.this.transactionOptions = TransactionOptions
				.builder(DefaultEnginePersistenceServiceTest.this.transactionOptions)
				.walFileCountKept(1)
				.build();
			final ImmediateScheduledThreadPoolExecutor executor = new ImmediateScheduledThreadPoolExecutor();
			DefaultEnginePersistenceServiceTest.this.scheduler = new Scheduler(executor);
			DefaultEnginePersistenceServiceTest.this.service = reopenService();

			long publishedVersion = -1L;
			for (long version = DefaultEnginePersistenceServiceTest.this.service.getVersion() + 1;
			     version <= MAX_VERSION_BEFORE_ROTATION && publishedVersion == -1L; version++) {
				final long appendedVersion = version;
				final boolean[] rotated = {false};
				assertDoesNotThrow(
					() -> DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
						appendedVersion, UUID.randomUUID(), createTestEngineMutation("catalog" + appendedVersion),
						txRef -> {
							if (((LogFileRecordReference) txRef.walReference()).fileIndex() > 0) {
								rotated[0] = true;
								executor.shutdownNow();
							}
							return minimalEngineState(appendedVersion, txRef);
						}
					),
					"Version " + appendedVersion + " was published by the bootstrap record before scheduling the " +
						"removal of rotated WAL files failed. A failure after the publish must not roll back an " +
						"append the published record already references."
				);
				if (rotated[0]) {
					publishedVersion = appendedVersion;
				}
			}
			assertTrue(publishedVersion > 0L, "The WAL never rotated - lower ROTATING_WAL_FILE_SIZE_BYTES.");
			assertEquals(publishedVersion, DefaultEnginePersistenceServiceTest.this.service.getVersion());
			assertEquals(
				publishedVersion, DefaultEnginePersistenceServiceTest.this.service.getLastVersionInMutationStream()
			);

			DefaultEnginePersistenceServiceTest.this.service.close();
			DefaultEnginePersistenceServiceTest.this.scheduler = new Scheduler(new ImmediateScheduledThreadPoolExecutor());
			DefaultEnginePersistenceServiceTest.this.service = assertDoesNotThrow(this::reopenService);
			assertEquals(publishedVersion, DefaultEnginePersistenceServiceTest.this.service.getVersion());
			assertEquals(
				publishedVersion, DefaultEnginePersistenceServiceTest.this.service.getLastVersionInMutationStream()
			);
		}

		/**
		 * After a crash between rotation and the first append, the published engine state references the last
		 * transaction of the finalized file while appends land in the empty file after it. A rolled-back append
		 * there has to be removed from the file it landed in - the file the published reference points into ends
		 * with its trailer and has nothing to give back. Leaving the transaction behind makes every retry of the
		 * version fail, and a restart replays a mutation its caller was told had failed.
		 */
		@Test
		@DisplayName("should roll back an append into the WAL file a crash after rotation left without a transaction")
		void shouldRollBackAnAppendIntoAWalFileLeftEmptyByACrashAfterRotation() throws IOException {
			final long crashedVersion = appendUntilTheWalRotates();
			crashBeforeTheFirstAppendIntoTheRotatedFile();
			DefaultEnginePersistenceServiceTest.this.service = reopenService();

			final String failureMessage = "the engine state could not be built";
			final IllegalStateException failure = assertThrows(
				IllegalStateException.class,
				() -> DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
					crashedVersion, UUID.randomUUID(), createTestEngineMutation("rolledBack"),
					txRef -> {
						throw new IllegalStateException(failureMessage);
					}
				)
			);
			assertEquals(failureMessage, failure.getMessage());
			assertEquals(
				AbstractMutationLog.CUMULATIVE_CRC32_SIZE,
				walFilePath(1).toFile().length(),
				"The WAL file the rolled-back append landed in held only its header before the append, and must " +
					"hold only its header after the rollback."
			);

			assertDoesNotThrow(
				() -> DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
					crashedVersion, UUID.randomUUID(), createTestEngineMutation("retried"),
					txRef -> minimalEngineState(crashedVersion, txRef)
				),
				"Version " + crashedVersion + " was rolled back, so it is free to be committed again."
			);
			DefaultEnginePersistenceServiceTest.this.service.close();
			DefaultEnginePersistenceServiceTest.this.service = reopenService();
			assertEquals(crashedVersion, DefaultEnginePersistenceServiceTest.this.service.getVersion());
			assertEquals(
				crashedVersion, DefaultEnginePersistenceServiceTest.this.service.getLastVersionInMutationStream()
			);
		}

		/**
		 * The engine reports every published version to its WAL as processed, so the retention removes the WAL files
		 * holding nothing newer - file `0` included, while the engine runs or at the latest when its log closes, and
		 * a restart then opens the log from the published reference. A rolled-back append must leave the log
		 * able to take the next append: a log re-opened from the first WAL file instead of from the published
		 * reference refuses to start from a file that is gone, and every later engine mutation fails until restart.
		 */
		@Test
		@DisplayName("should accept appends after a rolled-back append once the retention removed the first WAL file")
		void shouldAcceptAppendsAfterARolledBackAppendOnceRetentionRemovedTheFirstWalFile() {
			DefaultEnginePersistenceServiceTest.this.service.close();
			DefaultEnginePersistenceServiceTest.this.transactionOptions = TransactionOptions
				.builder(DefaultEnginePersistenceServiceTest.this.transactionOptions)
				.walFileCountKept(1)
				.build();
			DefaultEnginePersistenceServiceTest.this.service = reopenService();

			long version = DefaultEnginePersistenceServiceTest.this.service.getVersion() + 1;
			for (; version <= MAX_VERSION_BEFORE_ROTATION && !walFilePath(1).toFile().exists(); version++) {
				final long committedVersion = version;
				DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
					committedVersion, UUID.randomUUID(), createTestEngineMutation("catalog" + committedVersion),
					txRef -> minimalEngineState(committedVersion, txRef)
				);
			}
			assertTrue(walFilePath(1).toFile().exists(), "The WAL never rotated - lower ROTATING_WAL_FILE_SIZE_BYTES.");
			// the removal the rotation queued is due once its version is published, and closing the log drains it
			// deterministically - the scheduler runs it with a delay otherwise
			DefaultEnginePersistenceServiceTest.this.service.close();
			DefaultEnginePersistenceServiceTest.this.service = reopenService();
			assertFalse(
				walFilePath(0).toFile().exists(),
				"precondition: the retention removed the first WAL file once the rotating append was published"
			);

			final long failedVersion = version;
			final RuntimeException simulated = new RuntimeException("simulated failure");
			assertSame(
				simulated,
				assertThrows(
					RuntimeException.class,
					() -> DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
						failedVersion, UUID.randomUUID(), createTestEngineMutation("rolledBack"),
						txRef -> {
							throw simulated;
						}
					)
				)
			);

			assertDoesNotThrow(
				() -> DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
					failedVersion, UUID.randomUUID(), createTestEngineMutation("retried"),
					txRef -> minimalEngineState(failedVersion, txRef)
				),
				"One failed engine commit must not wedge every later one - the log has to continue from the " +
					"published reference, which points into a WAL file that still exists."
			);
			DefaultEnginePersistenceServiceTest.this.service.close();
			DefaultEnginePersistenceServiceTest.this.service = reopenService();
			assertEquals(failedVersion, DefaultEnginePersistenceServiceTest.this.service.getVersion());
			assertEquals(
				failedVersion, DefaultEnginePersistenceServiceTest.this.service.getLastVersionInMutationStream()
			);
		}

		/**
		 * A transaction sized close to the WAL file size limit fits the limit but not together with the header an
		 * empty file starts with. Appending it to such a file used to rotate that file - which holds no transaction,
		 * so rotation has no version range to finalize it with and fails - instead of appending it. An empty active
		 * file is a fresh log, or one left by a crash between rotation and the first append into the new file; both
		 * are exercised.
		 */
		@Test
		@DisplayName("should append a transaction of the full WAL file size to a WAL file that holds no transaction yet")
		void shouldAppendAFullSizeTransactionToAWalFileThatHoldsNoTransactionYet() throws IOException {
			final String catalogName = "fullSize";
			final long transactionSize = measureTransactionSize(catalogName);
			// the limit equals the transaction - it fits, but not after the 8-byte header and the 4-byte prefix
			DefaultEnginePersistenceServiceTest.this.service.close();
			DefaultEnginePersistenceServiceTest.this.transactionOptions = TransactionOptions
				.builder(DefaultEnginePersistenceServiceTest.this.transactionOptions)
				.walFileSizeBytes(transactionSize)
				.build();
			DefaultEnginePersistenceServiceTest.this.service = reopenService();

			// a fresh log: its first file is created holding only its header
			final long firstVersion = DefaultEnginePersistenceServiceTest.this.service.getVersion() + 1;
			assertDoesNotThrow(
				() -> DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
					firstVersion, UUID.randomUUID(), createTestEngineMutation(catalogName),
					txRef -> minimalEngineState(firstVersion, txRef)
				),
				"A transaction of " + transactionSize + " bytes fits a WAL file size limit of the same size and has to " +
					"be appended to the empty first file - rotating that file is impossible and pointless."
			);

			// the next one rotates the file holding the first transaction away; a crash before it lands leaves the
			// file it rotated into empty
			final long secondVersion = firstVersion + 1;
			final TransactionMutationWithWalFileReference rotatedAppend = DefaultEnginePersistenceServiceTest.this.service
				.appendWal(secondVersion, UUID.randomUUID(), createTestEngineMutation(catalogName));
			assertEquals(1, rotatedAppend.walReference().fileIndex(), "precondition: the second append must rotate");
			crashBeforeTheFirstAppendIntoTheRotatedFile();
			DefaultEnginePersistenceServiceTest.this.service = reopenService();
			assertDoesNotThrow(
				() -> DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
					secondVersion, UUID.randomUUID(), createTestEngineMutation(catalogName),
					txRef -> minimalEngineState(secondVersion, txRef)
				),
				"The same transaction has to be appended to the empty file a rotation left behind as well."
			);

			DefaultEnginePersistenceServiceTest.this.service.close();
			DefaultEnginePersistenceServiceTest.this.service = assertDoesNotThrow(this::reopenService);
			assertEquals(secondVersion, DefaultEnginePersistenceServiceTest.this.service.getVersion());
			assertEquals(secondVersion, DefaultEnginePersistenceServiceTest.this.service.getLastVersionInMutationStream());
		}

		/**
		 * Measures the WAL size of an engine mutation creating a catalog of the passed name, in a storage of its own.
		 * The size depends only on the name, so the same mutation has the same size in the storage under test.
		 *
		 * @param catalogName the name of the catalog the measured mutation creates
		 * @return the size the mutation occupies in the WAL, which is what the WAL file size limit is compared with
		 */
		private long measureTransactionSize(@Nonnull String catalogName) throws IOException {
			final String probeDirectoryName = DefaultEnginePersistenceServiceTest.class.getSimpleName() + "Probe";
			cleanTestSubDirectory(probeDirectoryName);
			final Path probeDirectory = Files.createDirectories(getPathInTargetDirectory(probeDirectoryName));
			final DefaultEnginePersistenceService probe = new DefaultEnginePersistenceService(
				StorageOptions.builder().storageDirectory(probeDirectory).build(),
				DefaultEnginePersistenceServiceTest.this.transactionOptions,
				DefaultEnginePersistenceServiceTest.this.scheduler
			);
			try {
				return probe.appendWal(probe.getVersion() + 1, UUID.randomUUID(), createTestEngineMutation(catalogName))
					.transactionMutation()
					.getWalSizeInBytes();
			} finally {
				probe.close();
				cleanTestSubDirectory(probeDirectoryName);
			}
		}

		/**
		 * Commits one engine mutation per version until an append rotates the WAL. The append that rotated is left
		 * without its engine state, as a crash right after it would leave it.
		 *
		 * @return the version whose append rotated the WAL - the engine state is one version behind it
		 */
		private long appendUntilTheWalRotates() {
			for (long version = DefaultEnginePersistenceServiceTest.this.service.getVersion() + 1;
			     version <= MAX_VERSION_BEFORE_ROTATION; version++) {
				final TransactionMutationWithWalFileReference txRef = DefaultEnginePersistenceServiceTest.this.service
					.appendWal(version, UUID.randomUUID(), createTestEngineMutation("catalog" + version));
				if (txRef.walReference().fileIndex() > 0) {
					assertEquals(version - 1, DefaultEnginePersistenceServiceTest.this.service.getVersion());
					return version;
				}
				DefaultEnginePersistenceServiceTest.this.service.rewriteEngineStateAtNextVersion(
					minimalEngineState(version, txRef)
				);
			}
			throw new AssertionError("The WAL never rotated - lower ROTATING_WAL_FILE_SIZE_BYTES.");
		}

		/**
		 * Stops the service and cuts the WAL file the last append rotated into back to the header rotation wrote -
		 * the state of a crash before that append landed.
		 */
		private void crashBeforeTheFirstAppendIntoTheRotatedFile() throws IOException {
			DefaultEnginePersistenceServiceTest.this.service.close();
			try (final RandomAccessFile raf = new RandomAccessFile(walFilePath(1).toFile(), "rw")) {
				raf.setLength(AbstractMutationLog.CUMULATIVE_CRC32_SIZE);
			}
		}

		/**
		 * Resolves the engine WAL file with the passed index.
		 *
		 * @param walFileIndex index of the file
		 * @return the path of the file
		 */
		@Nonnull
		private Path walFilePath(int walFileIndex) {
			return DefaultEnginePersistenceServiceTest.this.storageOptions.storageDirectory()
				.resolve(EnginePersistenceService.getWalFileName(walFileIndex));
		}

		/**
		 * Starts a new service over the test storage with the current options.
		 *
		 * @return the started service
		 */
		@Nonnull
		private DefaultEnginePersistenceService reopenService() {
			return new DefaultEnginePersistenceService(
				DefaultEnginePersistenceServiceTest.this.storageOptions,
				DefaultEnginePersistenceServiceTest.this.transactionOptions,
				DefaultEnginePersistenceServiceTest.this.scheduler
			);
		}

	}

	/**
	 * Tests for the forward-replay primitives that let the transaction manager reconcile the
	 * bootstrap file after the WAL has been advanced out-of-band (the single-mutation crash window).
	 */
	@Nested
	@DisplayName("Forward-replay primitives")
	class ForwardReplayPrimitives {

		@Test
		@DisplayName("should expose rewriteEngineStateAtNextVersion to reconcile bootstrap after WAL commit")
		void shouldExposeRewriteEngineStateAtNextVersion() throws IOException {
			// rewriteEngineStateAtNextVersion is the persistence-side primitive used by EngineTransactionManager's
			// forward-replay path to rewrite the bootstrap file without re-appending to the WAL. The method must:
			// 1. accept the next-version engine state after the WAL was already advanced,
			// 2. bump the persisted engine version so the startup invariant is satisfied on subsequent reboots.
			DefaultEnginePersistenceServiceTest.this.service.appendWal(2L, UUID.randomUUID(), createTestEngineMutation());

			final EngineState<LogFileRecordReference> stateAtV2 = new EngineState<>(
				STORAGE_PROTOCOL_VERSION,
				2L,
				OffsetDateTime.now(),
				// The WAL reference would normally come from the just-appended entry; for
				// this direct test any non-null reference suffices because we verify that
				// the reboot-time D.1 check succeeds when walVersion == stateVersion.
				DefaultEnginePersistenceServiceTest.this.service.getEngineState().walReference(),
				new String[]{TEST_CATALOG},
				ArrayUtils.EMPTY_STRING_ARRAY,
				ArrayUtils.EMPTY_STRING_ARRAY
			);

			DefaultEnginePersistenceServiceTest.this.service.rewriteEngineStateAtNextVersion(stateAtV2);
			assertEquals(2L, DefaultEnginePersistenceServiceTest.this.service.getEngineState().version());
			assertEquals(2L, DefaultEnginePersistenceServiceTest.this.service.getLastVersionInMutationStream());

			// D.1 must now pass cleanly on reboot — versions match.
			DefaultEnginePersistenceServiceTest.this.service.close();

			// Create the catalog folder on disk so reconciliation does not strip it away on reboot.
			Files.createDirectory(
				DefaultEnginePersistenceServiceTest.this.storageOptions.storageDirectory().resolve(TEST_CATALOG));

			DefaultEnginePersistenceServiceTest.this.service = new DefaultEnginePersistenceService(
				DefaultEnginePersistenceServiceTest.this.storageOptions,
				DefaultEnginePersistenceServiceTest.this.transactionOptions,
				DefaultEnginePersistenceServiceTest.this.scheduler
			);
			assertEquals(2L, DefaultEnginePersistenceServiceTest.this.service.getEngineState().version());
			assertEquals(2L, DefaultEnginePersistenceServiceTest.this.service.getLastVersionInMutationStream());
		}

		@Test
		@DisplayName("should reject rewriteEngineStateAtNextVersion when WAL is not advanced")
		void shouldRejectRewriteEngineStateAtNextVersionWhenWalNotAdvanced() {
			// rewriteEngineStateAtNextVersion must never be used without a committed WAL entry at the target
			// version — that would re-introduce the version drift the fused critical section was designed to
			// prevent.
			final EngineState<LogFileRecordReference> stateAtV2 = new EngineState<>(
				STORAGE_PROTOCOL_VERSION,
				2L,
				OffsetDateTime.now(),
				null,
				new String[]{TEST_CATALOG},
				ArrayUtils.EMPTY_STRING_ARRAY,
				ArrayUtils.EMPTY_STRING_ARRAY
			);

			// Current engine version is 1 and there is no WAL entry yet — rewrite must be
			// rejected loudly because the WAL precondition is violated.
			assertThrows(
				GenericEvitaInternalError.class,
				() -> DefaultEnginePersistenceServiceTest.this.service.rewriteEngineStateAtNextVersion(stateAtV2)
			);

			// The service must remain at its original version and WAL state.
			assertEquals(1L, DefaultEnginePersistenceServiceTest.this.service.getVersion());
			assertEquals(0L, DefaultEnginePersistenceServiceTest.this.service.getLastVersionInMutationStream());
		}

		@Test
		@DisplayName("getUnprocessedTransaction returns empty when WAL has not been initialised yet")
		void shouldReturnEmptyUnprocessedTransactionWhenWalNotInitialised() {
			// Fresh service with no appends — `mutationLog` is still null. Empty here is the legitimate
			// "no work to do" signal, not a corruption flag.
			final Optional<UnprocessedTransactionRecord<LogFileRecordReference>> result =
				DefaultEnginePersistenceServiceTest.this.service.getUnprocessedTransaction();
			assertTrue(result.isEmpty(),
				"Fresh service must report no unprocessed transaction.");
		}

		@Test
		@DisplayName("getUnprocessedTransaction returns empty when engine state's walReference covers the WAL")
		void shouldReturnEmptyUnprocessedTransactionWhenEngineStateCoversWal() {
			// After a successful fused commit walReference advances together with the engine state, so the WAL
			// has nothing past the engine state's reference — the legitimate steady-state empty.
			DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
				2L,
				UUID.randomUUID(),
				createTestEngineMutation(),
				txRef -> minimalEngineState(2L, txRef)
			);

			final Optional<UnprocessedTransactionRecord<LogFileRecordReference>> result =
				DefaultEnginePersistenceServiceTest.this.service.getUnprocessedTransaction();
			assertTrue(result.isEmpty(),
				"After a successful fused commit there must be no unprocessed transaction.");
		}

		@Test
		@DisplayName("getUnprocessedTransaction surfaces the unprocessed record when WAL is one step ahead")
		void shouldReturnUnprocessedTransactionWhenWalAhead() {
			// Construct the OS-crash window directly: WAL has a record at v=2 the engine state's walReference does
			// NOT cover yet. `getUnprocessedTransaction` must return that record so forward replay can recompute
			// and persist the reconciled state.
			final UUID transactionId = UUID.randomUUID();
			final EngineMutation<?> mutation = new MarkCatalogMissingMutation("catalog-A");
			DefaultEnginePersistenceServiceTest.this.service.appendWal(2L, transactionId, mutation);

			final Optional<UnprocessedTransactionRecord<LogFileRecordReference>> result =
				DefaultEnginePersistenceServiceTest.this.service.getUnprocessedTransaction();
			assertTrue(result.isPresent(),
				"WAL is at v=2 and engine state still at v=1 — the v=2 record must surface.");
			final UnprocessedTransactionRecord<LogFileRecordReference> record = result.get();
			assertEquals(2L, record.version(), "Record must carry the WAL version that drove the OS-crash window.");
			assertInstanceOf(MarkCatalogMissingMutation.class, record.mutation(),
				"Record must carry the engine mutation body, not the transaction header.");
			assertEquals("catalog-A", ((MarkCatalogMissingMutation) record.mutation()).getCatalogName());
			assertNotNull(record.walReference(),
				"Record must carry the WAL reference that the bootstrap rewrite will embed.");
		}

		// Note: The two `WriteAheadLogCorruptedException` (WalKind.ENGINE) throw branches in
		// `getUnprocessedTransaction` (header-without-body and stream-truncated-mid-record) are
		// intentionally not unit-tested here. Reaching them deterministically requires either
		// (a) injecting a `EngineMutationLog` test double via reflection, which collides with the
		// project's mockito-inline / mockito-core version layout and produces flaky intercept
		// behaviour for inherited methods, or (b) byte-level WAL file truncation that depends on
		// the binary record layout and would be brittle to format changes. Both throw branches are
		// short and side-effect-free, so direct inspection is the pragmatic verification path.
		// The structural invariant they guard against (header without matching body) is impossible
		// to produce through the public API because `mutationLog.append(header, body)` is atomic.

	}

	/**
	 * Tests for Parts C and A — catalog-lifecycle mutations (`MarkCatalogMissingMutation`,
	 * `UpgradeCatalogFormatMutation`) routed through the fused WAL-first primitive and verified to
	 * survive the full Kryo round-trip across a service reboot.
	 */
	@Nested
	@DisplayName("Catalog lifecycle mutations (Parts C + A)")
	class CatalogLifecycleMutations {

		@Test
		@DisplayName("should move catalog into missingCatalogs via fused WAL append + store state")
		void shouldMoveCatalogToMissingViaMutation() {
			// Writing a MarkCatalogMissingMutation through the fused WAL-first primitive must:
			// (a) leave the WAL pointing at that mutation at version 2,
			// (b) persist an EngineState that has the catalog in `missingCatalogs[]`, and
			// (c) strip the catalog from `activeCatalogs[]` / `inactiveCatalogs[]`.
			// The state factory mirrors the runtime `MarkCatalogMissingMutationOperator` transformation
			// because the operator itself runs through `EngineTransactionManager`, not the persistence
			// service directly.
			final UUID transactionId = UUID.randomUUID();
			final MarkCatalogMissingMutation mutation = new MarkCatalogMissingMutation("ghost");

			final TransactionMutationWithWalReference result = DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
				2L,
				transactionId,
				mutation,
				txRef -> EngineState.<LogFileRecordReference>builder()
					.storageProtocolVersion(STORAGE_PROTOCOL_VERSION)
					.version(2L)
					.introducedAt(OffsetDateTime.now())
					.walFileReference((LogFileRecordReference) txRef.walReference())
					.activeCatalogs(ArrayUtils.EMPTY_STRING_ARRAY)
					.inactiveCatalogs(ArrayUtils.EMPTY_STRING_ARRAY)
					.readOnlyCatalogs(ArrayUtils.EMPTY_STRING_ARRAY)
					.missingCatalogs(new String[]{"ghost"})
					.build()
			);

			assertNotNull(result);
			assertEquals(2L, result.transactionMutation().getVersion());

			final EngineState<LogFileRecordReference> stored = DefaultEnginePersistenceServiceTest.this.service.getEngineState();
			assertEquals(2L, stored.version());
			assertEquals(0, stored.activeCatalogs().length);
			assertEquals(0, stored.inactiveCatalogs().length);
			assertEquals(1, stored.missingCatalogs().length);
			assertEquals("ghost", stored.missingCatalogs()[0]);
		}

		@Test
		@DisplayName("should replay MarkCatalogMissingMutation from WAL after reboot")
		void shouldSerializeMarkCatalogMissingMutationThroughWal() {
			// Verifies the end-to-end durability guarantee:
			// 1. Append a MarkCatalogMissingMutation via the fused primitive so both WAL and bootstrap
			// agree at version 2.
			// 2. Close the service, then reopen — the mutation must still be readable from the WAL and
			// the persisted EngineState must still report the catalog as MISSING.
			final UUID transactionId = UUID.randomUUID();
			final String missingName = "vanished";
			final MarkCatalogMissingMutation mutation = new MarkCatalogMissingMutation(missingName);

			DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
				2L,
				transactionId,
				mutation,
				txRef -> EngineState.<LogFileRecordReference>builder()
					.storageProtocolVersion(STORAGE_PROTOCOL_VERSION)
					.version(2L)
					.introducedAt(OffsetDateTime.now())
					.walFileReference((LogFileRecordReference) txRef.walReference())
					.activeCatalogs(ArrayUtils.EMPTY_STRING_ARRAY)
					.inactiveCatalogs(ArrayUtils.EMPTY_STRING_ARRAY)
					.readOnlyCatalogs(ArrayUtils.EMPTY_STRING_ARRAY)
					.missingCatalogs(new String[]{missingName})
					.build()
			);

			DefaultEnginePersistenceServiceTest.this.service.close();

			DefaultEnginePersistenceServiceTest.this.service = new DefaultEnginePersistenceService(
				DefaultEnginePersistenceServiceTest.this.storageOptions,
				DefaultEnginePersistenceServiceTest.this.transactionOptions,
				DefaultEnginePersistenceServiceTest.this.scheduler
			);

			// The persisted engine state must still list the catalog as missing after restart.
			final EngineState<LogFileRecordReference> reloaded = DefaultEnginePersistenceServiceTest.this.service.getEngineState();
			assertEquals(2L, reloaded.version());
			assertEquals(1, reloaded.missingCatalogs().length);
			assertEquals(missingName, reloaded.missingCatalogs()[0]);

			// The WAL entry itself must also survive — the mutation decoded from the WAL must equal the
			// one we appended, proving the full Kryo round-trip works end-to-end. After reboot the engine state
			// is in sync with the WAL (no unprocessed tail), so we read back through the committed-stream API.
			final MarkCatalogMissingMutation readBack;
			try (final Stream<EngineMutation<?>> stream = DefaultEnginePersistenceServiceTest.this.service.getCommittedMutationStream(2L)) {
				readBack = stream
					.filter(MarkCatalogMissingMutation.class::isInstance)
					.map(MarkCatalogMissingMutation.class::cast)
					.findFirst()
					.orElseThrow(() -> new AssertionError("MarkCatalogMissingMutation missing from WAL stream at v=2"));
			}
			assertEquals(missingName, readBack.getCatalogName());
		}

		@Test
		@DisplayName("should replay UpgradeCatalogFormatMutation from WAL after reboot")
		void shouldSerializeUpgradeCatalogFormatMutationThroughWal() {
			// End-to-end durability of the format-upgrade mutation:
			// 1. Append an UpgradeCatalogFormatMutation via the fused primitive so WAL and bootstrap agree at v2.
			// 2. Close + reopen the service — the mutation must still be readable from the WAL with all three payload
			// fields intact (catalog name, fromProtocolVersion, toProtocolVersion).
			final UUID transactionId = UUID.randomUUID();
			final String upgradingCatalog = "legacyCatalog";
			final int fromProtocol = STORAGE_PROTOCOL_VERSION - 1;
			final int toProtocol = STORAGE_PROTOCOL_VERSION;
			final UpgradeCatalogFormatMutation mutation =
				new UpgradeCatalogFormatMutation(upgradingCatalog, fromProtocol, toProtocol);

			DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
				2L,
				transactionId,
				mutation,
				txRef -> EngineState.<LogFileRecordReference>builder()
					.storageProtocolVersion(STORAGE_PROTOCOL_VERSION)
					.version(2L)
					.introducedAt(OffsetDateTime.now())
					.walFileReference((LogFileRecordReference) txRef.walReference())
					.activeCatalogs(new String[]{upgradingCatalog})
					.inactiveCatalogs(ArrayUtils.EMPTY_STRING_ARRAY)
					.readOnlyCatalogs(ArrayUtils.EMPTY_STRING_ARRAY)
					.build()
			);

			DefaultEnginePersistenceServiceTest.this.service.close();

			DefaultEnginePersistenceServiceTest.this.service = new DefaultEnginePersistenceService(
				DefaultEnginePersistenceServiceTest.this.storageOptions,
				DefaultEnginePersistenceServiceTest.this.transactionOptions,
				DefaultEnginePersistenceServiceTest.this.scheduler
			);

			// WAL entry must survive the reboot — decoding it back must reproduce all three fields. After reboot
			// the engine state is in sync with the WAL (no unprocessed tail), so we read back through the
			// committed-stream API.
			final UpgradeCatalogFormatMutation decoded;
			try (final Stream<EngineMutation<?>> stream = DefaultEnginePersistenceServiceTest.this.service.getCommittedMutationStream(2L)) {
				decoded = stream
					.filter(UpgradeCatalogFormatMutation.class::isInstance)
					.map(UpgradeCatalogFormatMutation.class::cast)
					.findFirst()
					.orElseThrow(() -> new AssertionError("UpgradeCatalogFormatMutation missing from WAL stream at v=2"));
			}
			assertEquals(upgradingCatalog, decoded.getCatalogName());
			assertEquals(fromProtocol, decoded.getFromProtocolVersion());
			assertEquals(toProtocol, decoded.getToProtocolVersion());
		}

	}

	/**
	 * Legacy WAL-facing tests covering raw append, mutation-stream queries, and engine-state
	 * version validation that predate the split.
	 */
	@Nested
	@DisplayName("WAL operations (legacy)")
	class WalOperations {

		@Test
		@DisplayName("should throw exception when storing engine state with invalid version")
		void shouldThrowExceptionWhenStoringEngineStateWithInvalidVersion() {
			// Create a new engine state with invalid version (not incremented by 1)
			EngineState invalidEngineState = new EngineState(
				STORAGE_PROTOCOL_VERSION,
				3L, // Should be 2L (current version + 1)
				OffsetDateTime.now(),
				null,
				new String[]{"catalog1", "catalog2"},
				new String[]{"inactiveCatalog"},
				new String[]{"readOnlyCatalog"}
			);

			// Attempt to store the invalid engine state
			//noinspection unchecked
			assertThrows(
				GenericEvitaInternalError.class,
				() -> DefaultEnginePersistenceServiceTest.this.service.storeEngineState(invalidEngineState)
			);
		}

		@Test
		@DisplayName("should get first non-processed transaction in WAL when none exists")
		void shouldGetFirstNonProcessedTransactionInWalWhenNoneExists() {
			// Call getFirstNonProcessedTransactionInWal with no transactions
			Optional<TransactionMutation> result = DefaultEnginePersistenceServiceTest.this.service.getFirstNonProcessedTransactionInWal(1L);

			// Verify the result is empty
			assertFalse(result.isPresent());
		}

		@Test
		@DisplayName("should get first non-processed transaction in WAL after appending")
		void shouldGetFirstNonProcessedTransactionInWalAfterAppending() {
			// Use the test-only appendWal so the WAL entry stays "non-processed" — the fused primitive would
			// advance the engine state's walReference to match and the query would return empty by definition.
			DefaultEnginePersistenceServiceTest.this.service.appendWal(2L, UUID.randomUUID(), createTestEngineMutation());

			// Call getFirstNonProcessedTransactionInWal — must surface the appended entry
			Optional<TransactionMutation> result = DefaultEnginePersistenceServiceTest.this.service.getFirstNonProcessedTransactionInWal(1L);

			// Verify the result
			assertTrue(result.isPresent());
			TransactionMutation transaction = result.get();
			assertNotNull(transaction);
		}

		@Test
		@DisplayName("should get empty committed mutation stream when none exists")
		void shouldGetEmptyCommittedMutationStreamWhenNoneExists() {
			// Call getCommittedMutationStream with no mutations
			Stream<EngineMutation<?>> result = DefaultEnginePersistenceServiceTest.this.service.getCommittedMutationStream(1L);

			// Verify the result
			assertNotNull(result);
			assertEquals(0, result.count());
		}

		@Test
		@DisplayName("should get committed mutation stream after appending")
		void shouldGetCommittedMutationStreamAfterAppending() {
			// Append two mutations to the WAL through the fused primitive (advances WAL + state to v=2 then v=3)
			DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
				2L, UUID.randomUUID(), createTestEngineMutation("a"),
				txRef -> minimalEngineState(2L, txRef)
			);
			DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
				3L, UUID.randomUUID(), createTestEngineMutation("b"),
				txRef -> minimalEngineState(3L, txRef)
			);

			// Get the committed mutation stream starting from the first appended transaction
			Stream<EngineMutation<?>> result = DefaultEnginePersistenceServiceTest.this.service.getCommittedMutationStream(2L);

			// Verify the result
			assertNotNull(result);

			final Mutation[] mutations = result.toArray(Mutation[]::new);
			assertEquals(4, mutations.length);

			assertInstanceOf(TransactionMutation.class, mutations[0]);
			assertEquals(2L, ((TransactionMutation)mutations[0]).getVersion());
			assertEquals(createTestEngineMutation("a"), mutations[1]);
			assertInstanceOf(TransactionMutation.class, mutations[2]);
			assertEquals(3L, ((TransactionMutation)mutations[2]).getVersion());
			assertEquals(createTestEngineMutation("b"), mutations[3]);
		}

		@Test
		@DisplayName("should get empty reversed committed mutation stream when none exists")
		void shouldGetEmptyReversedCommittedMutationStreamWhenNoneExists() {
			// Call getReversedCommittedMutationStream with no mutations
			Stream<EngineMutation<?>> result = DefaultEnginePersistenceServiceTest.this.service.getReversedCommittedMutationStream(2L);

			// Verify the result
			assertNotNull(result);
			assertEquals(0, result.count());
		}

		@Test
		@DisplayName("should get reversed committed mutation stream after appending")
		void shouldGetReversedCommittedMutationStreamAfterAppending() {
			// Append two mutations through the fused primitive (advances WAL + state to v=2 then v=3)
			DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
				2L, UUID.randomUUID(), createTestEngineMutation("a"),
				txRef -> minimalEngineState(2L, txRef)
			);
			DefaultEnginePersistenceServiceTest.this.service.appendWalAndStoreState(
				3L, UUID.randomUUID(), createTestEngineMutation("b"),
				txRef -> minimalEngineState(3L, txRef)
			);

			// Get the reversed committed mutation stream starting from the latest version
			Stream<EngineMutation<?>> result = DefaultEnginePersistenceServiceTest.this.service.getReversedCommittedMutationStream(3L);

			// Verify the result
			assertNotNull(result);

			final Mutation[] mutations = result.toArray(Mutation[]::new);
			assertEquals(4, mutations.length);

			assertInstanceOf(TransactionMutation.class, mutations[0]);
			assertEquals(3L, ((TransactionMutation)mutations[0]).getVersion());
			assertEquals(createTestEngineMutation("b"), mutations[1]);
			assertInstanceOf(TransactionMutation.class, mutations[2]);
			assertEquals(2L, ((TransactionMutation)mutations[2]).getVersion());
			assertEquals(createTestEngineMutation("a"), mutations[3]);
		}

		@Test
		@DisplayName("should get last version in mutation stream when none exists")
		void shouldGetLastVersionInMutationStreamWhenNoneExists() {
			// Call getLastVersionInMutationStream with no mutations
			long result = DefaultEnginePersistenceServiceTest.this.service.getLastVersionInMutationStream();

			// Verify the result is 0
			assertEquals(0L, result);
		}

	}

}
