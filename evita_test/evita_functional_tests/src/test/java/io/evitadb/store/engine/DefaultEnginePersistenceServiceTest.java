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
import io.evitadb.spi.store.catalog.shared.model.TransactionMutationWithWalReference;
import io.evitadb.spi.store.engine.model.EngineState;
import io.evitadb.spi.store.engine.model.CatalogInventoryDivergence;
import io.evitadb.spi.store.engine.model.UnprocessedTransactionRecord;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.model.reference.TransactionMutationWithWalFileReference;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
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
			// Regression guard for the `syncEngineStateByFolderContents` ordering bug. If folder-sync
			// reconciliation ran BEFORE the startup invariant check, a drifted state on disk would be "silently"
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
		@DisplayName("should expose reappeared and autoDiscovered divergence categories")
		void shouldExposeReappearedAndAutoDiscoveredDivergence() throws IOException {
			// Build a baseline state where one catalog already sits in the missing bucket and one
			// inactive catalog has its folder present on disk. Add an extra folder that the engine
			// state knows nothing about so we exercise auto-discovery too.
			final Path storageDirectory = DefaultEnginePersistenceServiceTest.this.storageOptions.storageDirectory();
			Files.createDirectory(storageDirectory.resolve("flapping"));
			Files.createDirectory(storageDirectory.resolve("discovered"));
			Files.createDirectory(storageDirectory.resolve("present"));

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
			assertEquals(List.of("discovered"), divergence.autoDiscovered());

			// Engine state must remain untouched — the persistence service does not rewrite the
			// bootstrap; `Evita` will drain the divergence through WAL-backed mutations.
			final EngineState<LogFileRecordReference> reloaded = DefaultEnginePersistenceServiceTest.this.service.getEngineState();
			assertEquals(2L, reloaded.version());
			assertEquals(List.of("flapping"), Arrays.asList(reloaded.missingCatalogs()));
			assertEquals(List.of("present"), Arrays.asList(reloaded.inactiveCatalogs()));
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
