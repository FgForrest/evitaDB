/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.core.executor.ImmediateScheduledThreadPoolExecutor;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.engine.model.EngineState;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static io.evitadb.spi.store.engine.EnginePersistenceService.STORAGE_PROTOCOL_VERSION;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

/**
 * This test verifies the behavior of {@link DefaultEnginePersistenceService}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("DefaultEnginePersistenceService functionality")
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

	@Test
	@DisplayName("should return if service is new")
	void shouldReturnIfServiceIsNew() {
		// Test the isNew method
		boolean isNew = this.service.isNew();

		// The service should be new since we're using an empty temp directory
		assertTrue(isNew);
	}

	@Test
	@DisplayName("should return engine version")
	void shouldReturnEngineVersion() {
		// Test the getVersion method
		long version = this.service.getVersion();

		// The version should be 1 for a new service
		assertEquals(1L, version);
	}

	@Test
	@DisplayName("should return engine state")
	void shouldReturnEngineState() {
		// Test the getEngineState method
		EngineState engineState = this.service.getEngineState();

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
	@DisplayName("should store engine state")
	void shouldStoreEngineState() {
		// Create a new engine state with incremented version
		EngineState newEngineState = new EngineState(
			STORAGE_PROTOCOL_VERSION,
			2L,
			OffsetDateTime.now(),
			null,
			new String[]{"catalog1", "catalog2", "catalog3"},
			new String[]{"inactiveCatalog"},
			new String[]{"readOnlyCatalog"}
		);

		// Store the new engine state
		this.service.storeEngineState(newEngineState);

		// Verify the engine state was updated
		EngineState retrievedState = this.service.getEngineState();
		assertEquals(2L, retrievedState.version());
		assertEquals(3, retrievedState.activeCatalogs().length);
		assertEquals(1, retrievedState.inactiveCatalogs().length);

		// try to restart the service to ensure the state is persisted
		this.service.close();


		// mock FileUtils.listDirectories to return expected catalog folders
		try (MockedStatic<FileUtils> fileUtilsMock = mockStatic(FileUtils.class)){
			fileUtilsMock.when(() -> FileUtils.listDirectories(this.storageOptions.storageDirectory()))
				.thenReturn(new Path[]{
					this.storageOptions.storageDirectory().resolve("catalog1"),
					this.storageOptions.storageDirectory().resolve("catalog2"),
					this.storageOptions.storageDirectory().resolve("catalog3"),
					this.storageOptions.storageDirectory().resolve("inactiveCatalog")
				});

			this.service = new DefaultEnginePersistenceService(
				this.storageOptions,
				this.transactionOptions,
				this.scheduler
			);

			// Verify the engine state is still persisted after restart
			EngineState restartedState = this.service.getEngineState();
			assertEquals(2L, restartedState.version());
			assertEquals(3, restartedState.activeCatalogs().length);
			assertEquals(1, restartedState.inactiveCatalogs().length);

			this.service.close();
		}

		// try again without folders
		this.service = new DefaultEnginePersistenceService(
			this.storageOptions,
			this.transactionOptions,
			this.scheduler
		);

		// Verify the engine state is still persisted after restart.
		// Folder-sync reconciliation strips the active catalogs (none exist on disk)
		// but must NOT advance the engine version — reconciliation is not a WAL mutation (#1137).
		EngineState restartedState = this.service.getEngineState();
		assertEquals(2L, restartedState.version());
		assertEquals(0, restartedState.activeCatalogs().length);
		assertEquals(0, restartedState.inactiveCatalogs().length);
	}

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
		assertThrows(
			GenericEvitaInternalError.class,
			() -> this.service.storeEngineState(invalidEngineState)
		);
	}

	@Test
	@DisplayName("should append WAL and return file reference")
	void shouldAppendWalAndReturnFileReference() {
		// Create a test EngineMutation
		EngineMutation<?> mutation = createTestEngineMutation();

		// Call appendWal
		LogFileRecordReference result = this.service.appendWal(1L, UUID.randomUUID(), mutation).walReference();

		// Verify the result
		assertNotNull(result);
		assertEquals(0, result.fileIndex());
	}

	@Test
	@DisplayName("should get first non-processed transaction in WAL when none exists")
	void shouldGetFirstNonProcessedTransactionInWalWhenNoneExists() {
		// Call getFirstNonProcessedTransactionInWal with no transactions
		Optional<TransactionMutation> result = this.service.getFirstNonProcessedTransactionInWal(1L);

		// Verify the result is empty
		assertFalse(result.isPresent());
	}

	@Test
	@DisplayName("should get first non-processed transaction in WAL after appending")
	void shouldGetFirstNonProcessedTransactionInWalAfterAppending() {
		// Append a mutation to the WAL
		EngineMutation<?> mutation = createTestEngineMutation();
		this.service.appendWal(1L, UUID.randomUUID(), mutation);

		// Call getFirstNonProcessedTransactionInWal
		Optional<TransactionMutation> result = this.service.getFirstNonProcessedTransactionInWal(1L);

		// Verify the result
		assertTrue(result.isPresent());
		TransactionMutation transaction = result.get();
		assertNotNull(transaction);
	}

	@Test
	@DisplayName("should get empty committed mutation stream when none exists")
	void shouldGetEmptyCommittedMutationStreamWhenNoneExists() {
		// Call getCommittedMutationStream with no mutations
		Stream<EngineMutation<?>> result = this.service.getCommittedMutationStream(1L);

		// Verify the result
		assertNotNull(result);
		assertEquals(0, result.count());
	}

	@Test
	@DisplayName("should get committed mutation stream after appending")
	void shouldGetCommittedMutationStreamAfterAppending() {
		// Append a mutation to the WAL
		this.service.appendWal(1L, UUID.randomUUID(), createTestEngineMutation("a"));
		this.service.appendWal(2L, UUID.randomUUID(), createTestEngineMutation("b"));

		// Get the committed mutation stream
		Stream<EngineMutation<?>> result = this.service.getCommittedMutationStream(1L);

		// Verify the result
		assertNotNull(result);

		final Mutation[] mutations = result.toArray(Mutation[]::new);
		assertEquals(4, mutations.length);

		assertInstanceOf(TransactionMutation.class, mutations[0]);
		assertEquals(1L, ((TransactionMutation)mutations[0]).getVersion());
		assertEquals(createTestEngineMutation("a"), mutations[1]);
		assertInstanceOf(TransactionMutation.class, mutations[2]);
		assertEquals(2L, ((TransactionMutation)mutations[2]).getVersion());
		assertEquals(createTestEngineMutation("b"), mutations[3]);
	}

	@Test
	@DisplayName("should get empty reversed committed mutation stream when none exists")
	void shouldGetEmptyReversedCommittedMutationStreamWhenNoneExists() {
		// Call getReversedCommittedMutationStream with no mutations
		Stream<EngineMutation<?>> result = this.service.getReversedCommittedMutationStream(2L);

		// Verify the result
		assertNotNull(result);
		assertEquals(0, result.count());
	}

	@Test
	@DisplayName("should get reversed committed mutation stream after appending")
	void shouldGetReversedCommittedMutationStreamAfterAppending() {
		// Append a mutation to the WAL
		this.service.appendWal(1L, UUID.randomUUID(), createTestEngineMutation("a"));
		this.service.appendWal(2L, UUID.randomUUID(), createTestEngineMutation("b"));

		// Get the reversed committed mutation stream
		Stream<EngineMutation<?>> result = this.service.getReversedCommittedMutationStream(2L);

		// Verify the result
		assertNotNull(result);

		final Mutation[] mutations = result.toArray(Mutation[]::new);
		assertEquals(4, mutations.length);

		assertInstanceOf(TransactionMutation.class, mutations[0]);
		assertEquals(2L, ((TransactionMutation)mutations[0]).getVersion());
		assertEquals(createTestEngineMutation("b"), mutations[1]);
		assertInstanceOf(TransactionMutation.class, mutations[2]);
		assertEquals(1L, ((TransactionMutation)mutations[2]).getVersion());
		assertEquals(createTestEngineMutation("a"), mutations[3]);
	}

	@Test
	@DisplayName("should get last version in mutation stream when none exists")
	void shouldGetLastVersionInMutationStreamWhenNoneExists() {
		// Call getLastVersionInMutationStream with no mutations
		long result = this.service.getLastVersionInMutationStream();

		// Verify the result is 0
		assertEquals(0L, result);
	}

	@Test
	@DisplayName("should close resources")
	void shouldCloseResources() {
		// Call close
		this.service.close();

		// Verify isClosed returns true
		assertTrue(this.service.isClosed());
	}

	@Test
	@DisplayName("should create bootstrap file")
	void shouldCreateBootstrapFile() {
		// Verify bootstrap file was created
		Path bootstrapFile = getPathInTargetDirectory(this.getClass().getSimpleName()).resolve("evitaDB.boot");
		assertTrue(Files.exists(bootstrapFile));
	}

	@Test
	@DisplayName("should not bump engine version during folder-sync reconciliation")
	void shouldNotBumpEngineVersionDuringFolderSyncReconciliation() {
		// Persist a state that claims catalogs on disk that do not actually exist —
		// this forces syncEngineStateByFolderContents to reconcile the active list.
		final EngineState<LogFileRecordReference> stateClaimingMissingCatalogs = new EngineState<>(
			STORAGE_PROTOCOL_VERSION,
			2L,
			OffsetDateTime.now(),
			null,
			new String[]{"ghost-a", "ghost-b"},
			ArrayUtils.EMPTY_STRING_ARRAY,
			ArrayUtils.EMPTY_STRING_ARRAY
		);
		this.service.storeEngineState(stateClaimingMissingCatalogs);
		this.service.close();

		// Reboot — with no directories on disk, sync will strip the ghost catalogs
		// from the active list. Reconciliation must NOT advance the engine version,
		// because version advancement requires a corresponding WAL mutation.
		this.service = new DefaultEnginePersistenceService(
			this.storageOptions,
			this.transactionOptions,
			this.scheduler
		);

		final EngineState<LogFileRecordReference> reloaded = this.service.getEngineState();
		assertEquals(
			2L, reloaded.version(),
			"Folder sync reconciliation must not bump engine version (would drift WAL ↔ state)."
		);
		assertEquals(0, reloaded.activeCatalogs().length);
		assertEquals(0, reloaded.inactiveCatalogs().length);
	}

	@Test
	@DisplayName("should not bump engine version during storage-protocol migration")
	void shouldNotBumpEngineVersionDuringStorageProtocolMigration() {
		// Persist a state that reports an older storage protocol version — this
		// forces the migration branch to run on next boot.
		final EngineState<LogFileRecordReference> oldProtocolState = new EngineState<>(
			STORAGE_PROTOCOL_VERSION - 1,
			2L,
			OffsetDateTime.now(),
			null,
			ArrayUtils.EMPTY_STRING_ARRAY,
			ArrayUtils.EMPTY_STRING_ARRAY,
			ArrayUtils.EMPTY_STRING_ARRAY
		);
		this.service.storeEngineState(oldProtocolState);
		this.service.close();

		// Reboot — migration path should rewrite the bootstrap with the current
		// protocol version but MUST NOT advance the engine version counter
		// (protocol migration is storage-layer metadata, not a logical mutation).
		this.service = new DefaultEnginePersistenceService(
			this.storageOptions,
			this.transactionOptions,
			this.scheduler
		);

		final EngineState<LogFileRecordReference> reloaded = this.service.getEngineState();
		assertEquals(
			STORAGE_PROTOCOL_VERSION, reloaded.storageProtocolVersion(),
			"Storage protocol version must be upgraded on boot."
		);
		assertEquals(
			2L, reloaded.version(),
			"Storage protocol migration must not bump engine version (would drift WAL ↔ state)."
		);
	}
}
