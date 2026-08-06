/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.store.catalog;

import com.esotericsoftware.kryo.Kryo;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TrafficRecordingOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.exception.EntityTypeAlreadyPresentInCatalogSchemaException;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.observability.trace.DefaultTracingContext;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.api.requestResponse.progress.ProgressRecord;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.api.requestResponse.system.MaterializedVersionBlock;
import io.evitadb.api.requestResponse.system.TimeFlow;
import io.evitadb.core.Evita;
import io.evitadb.core.buffer.WarmUpDataStoreMemoryBuffer;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.executor.ImmediateExecutorService;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.management.FileManagementService;
import io.evitadb.core.sequence.SequenceService;
import io.evitadb.core.session.EvitaSession;
import io.evitadb.core.traffic.TrafficRecordingEngine;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.InvalidClassifierFormatException;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.task.ServerTask;
import io.evitadb.api.task.TaskStatus.TaskSimplifiedState;
import io.evitadb.export.file.ExportFileService;
import io.evitadb.export.file.ExportFileService.ExportFileHandleLocal;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.catalog.persistence.EntityCollectionPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.schema.CatalogSchemaStoragePart;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.catalog.task.BackupTask;
import io.evitadb.store.checksum.ChecksumFactory;
import io.evitadb.store.checksum.Crc32CChecksumFactory;
import io.evitadb.store.compression.CompressionFactory;
import io.evitadb.store.exception.BootstrapFileNotFound;
import io.evitadb.store.exception.DirectoryNotEmptyException;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.offsetIndex.exception.UnexpectedCatalogContentsException;
import io.evitadb.store.offsetIndex.io.CatalogOffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.OffHeapWithFileBackupReference;
import io.evitadb.store.offsetIndex.io.ReadOnlyFileHandle;
import io.evitadb.store.offsetIndex.io.ReadOnlyHandle;
import io.evitadb.store.offsetIndex.io.WriteOnlyOffHeapWithFileBackupHandle;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.wal.AbstractMutationLog;
import io.evitadb.store.wal.WalKryoConfigurer;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.TestConstants;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.test.utils.ReflectionUtils;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.UUIDUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.CATALOG_FILE_SUFFIX;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getCatalogBootstrapFileName;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getCatalogDataStoreFileNamePattern;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getIndexFromCatalogFileName;
import static io.evitadb.store.catalog.DefaultCatalogPersistenceService.countObsoletePersistenceServices;
import static io.evitadb.store.catalog.DefaultCatalogPersistenceService.deserializeCatalogBootstrapRecord;
import static io.evitadb.store.catalog.DefaultCatalogPersistenceService.getCatalogBootstrapForSpecificMoment;
import static io.evitadb.store.catalog.DefaultCatalogPersistenceService.getFirstCatalogBootstrap;
import static io.evitadb.store.catalog.DefaultIsolatedWalServiceTest.DATA_MUTATION_EXAMPLE;
import static io.evitadb.store.catalog.DefaultIsolatedWalServiceTest.SCHEMA_MUTATION_EXAMPLE;
import static io.evitadb.test.Assertions.assertExactlyEquals;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.utils.ArrayUtils.computeInsertPositionOfLongInOrderedArray;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * This test verifies contract of {@link CatalogPersistenceService}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Tag(STORAGE)
@Tag(MANAGEMENT)
class DefaultCatalogPersistenceServiceTest implements EvitaTestSupport {
	public static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(TEST_CATALOG, NamingConvention.generate(TestConstants.TEST_CATALOG), null, EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE);
	public static final String DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST = "defaultCatalogPersistenceServiceTest";
	public static final String TX_DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST = "txDefaultCatalogPersistenceServiceTest";
	private static final String RENAMED_CATALOG = "somethingElse";
	private static final SealedCatalogSchema SEALED_CATALOG_SCHEMA = new CatalogSchemaDecorator(CATALOG_SCHEMA);
	private final DataGenerator dataGenerator = new DataGenerator();
	private final SequenceService sequenceService = new SequenceService();

	private final UUID catalogId = UUID.randomUUID();
	private final UUID transactionId = UUID.randomUUID();
	private final Path walFile = getTestDirectory().resolve(this.transactionId.toString());
	private final Kryo kryo = KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
	private final ObservableOutputKeeper observableOutputKeeper = ObservableOutputKeeper._internalBuild(
		Mockito.mock(Scheduler.class)
	);
	private final WriteOnlyOffHeapWithFileBackupHandle writeHandle = new WriteOnlyOffHeapWithFileBackupHandle(
		getTestDirectory().resolve(this.transactionId.toString()),
		StorageOptions.DEFAULT_OUTPUT_BUFFER_SIZE,
		false,
		this.observableOutputKeeper,
		new CatalogOffHeapMemoryManager(TEST_CATALOG, 512, 1, ChecksumFactory.NO_OP),
		ChecksumFactory.NO_OP,
		CompressionFactory.NO_COMPRESSION
	);
	private final DefaultIsolatedWalService walService = new DefaultIsolatedWalService(
		TEST_CATALOG,
		this.transactionId,
		new ConflictResolution(ConflictPolicy.NONE),
		this.kryo,
		this.writeHandle
	);

	private static int countFiles(@Nonnull Path catalogDirectory) throws IOException {
		try (var paths = Files.list(catalogDirectory)) {
			return (int) paths.count();
		}
	}

	@Nonnull
	private static Catalog getMockCatalog(SealedCatalogSchema catalogSchema, @Nonnull SealedEntitySchema schema) {
		final Catalog mockCatalog = mock(Catalog.class);
		when(mockCatalog.getSchema()).thenReturn(catalogSchema);
		when(mockCatalog.getEntitySchema(schema.getName())).thenReturn(of(schema));
		when(mockCatalog.getEntityIndexIfExists(Mockito.eq(schema.getName()), any(EntityIndexKey.class), any(Class.class))).thenReturn(empty());
		return mockCatalog;
	}

	private static void trimAndCheck(
		@Nonnull DefaultCatalogPersistenceService ioService,
		long sinceCatalogVersion,
		int expectedVersion,
		int expectedCount
	) {
		ioService.trimBootstrapFile(sinceCatalogVersion);

		final PaginatedList<MaterializedVersionBlock> catalogVersions = ioService.getCatalogVersions(TimeFlow.FROM_OLDEST_TO_NEWEST, 1, 20);
		final MaterializedVersionBlock firstRecord = catalogVersions.getData().get(0);
		assertEquals(sinceCatalogVersion, firstRecord.endVersion());
		assertEquals(expectedVersion, firstRecord.endVersion());
		assertEquals(expectedCount, catalogVersions.getTotalRecordCount());
	}

	@Nonnull
	private TrafficRecordingEngine createTrafficRecordingEngine(@Nonnull SealedCatalogSchema catalogSchema) {
		final TestPaths paths = createTestPaths("DefaultCatalogPersistenceServiceTest_traffic");
		final StorageOptions storageOptions = StorageOptions.builder()
			.storageDirectory(paths.storage())
			.workDirectory(paths.work())
			.build();
		return new TrafficRecordingEngine(
			catalogSchema.getName(),
			CatalogState.WARMING_UP,
			DefaultTracingContext.INSTANCE,
			newTestEvitaConfigurationBuilder(paths)
				.storage(storageOptions)
				.server(
					ServerOptions.builder()
						.trafficRecording(
							TrafficRecordingOptions.builder()
								.build()
						).build()
				)
				.build(),
			new FileManagementService(storageOptions),
			Mockito.mock(Scheduler.class)
		);
	}

	@BeforeEach
	public void setUp() throws IOException {
		final Path resolve = getTestDirectory().resolve(DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST);
		resolve.toFile().mkdirs();
	}

	@AfterEach
	void tearDown() throws IOException {
		// `CURRENT_TIME_MILLIS` is thread-scoped, which stops a pinned clock reaching tests running *concurrently* -
		// but not tests running *later*: surefire uses one reused fork and the parallel engine hands the same worker
		// thread to test after test, so a pin left behind here is inherited by whatever runs next on this thread.
		// Clear unconditionally rather than only in the tests that currently pin it, so a future test that forgets
		// its own restore cannot leak either.
		DefaultCatalogPersistenceService.CURRENT_TIME_MILLIS.remove();
		this.walService.close();
		this.observableOutputKeeper.close();
		final File file = this.walFile.toFile();
		if (file.exists()) {
			fail("File " + file + " should not exist after close!");
		}
		cleanTestSubDirectory(DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST);
	}

	@Test
	void shouldSerializeAndDeserializeCatalogHeader() {
		final DefaultCatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
			SEALED_CATALOG_SCHEMA.getName(),
			getStorageOptions(),
			getTransactionOptions(),
			Mockito.mock(Scheduler.class),
			Mockito.mock(ExportFileService.class)
		);

		ioService.getStoragePartPersistenceService(0L)
			.putStoragePart(0L, new CatalogSchemaStoragePart(CATALOG_SCHEMA));

		final EvitaSession mockSession = mock(EvitaSession.class);
		when(mockSession.getCatalogSchema()).thenReturn(SEALED_CATALOG_SCHEMA);

		final EntityCollection brandCollection = constructEntityCollectionWithSomeEntities(
			ioService, SEALED_CATALOG_SCHEMA, this.dataGenerator.getSampleBrandSchema(mockSession, EntitySchemaBuilder::toInstance), 1
		);
		final EntityCollection storeCollection = constructEntityCollectionWithSomeEntities(
			ioService, SEALED_CATALOG_SCHEMA, this.dataGenerator.getSampleStoreSchema(mockSession, EntitySchemaBuilder::toInstance), 2
		);
		final EntityCollection productCollection = constructEntityCollectionWithSomeEntities(
			ioService, SEALED_CATALOG_SCHEMA, this.dataGenerator.getSampleProductSchema(mockSession, EntitySchemaBuilder::toInstance), 3
		);

		final List<EntityCollectionFileHeader> entityHeaders = new ArrayList<>(3);
		entityHeaders.add((EntityCollectionFileHeader) productCollection.flush().header());
		entityHeaders.add((EntityCollectionFileHeader) brandCollection.flush().header());
		entityHeaders.add((EntityCollectionFileHeader) storeCollection.flush().header());

		// try to serialize
		ioService.storeHeader(
			this.catalogId,
			CatalogState.WARMING_UP,
			0,
			0,
			null,
			entityHeaders,
			new WarmUpDataStoreMemoryBuffer(ioService.getStoragePartPersistenceService(0L))
		);

		// try to deserialize again
		final CatalogHeader catalogHeader = ioService.getCatalogHeader(0L);

		assertNotNull(catalogHeader);
		final Map<String, CollectionFileReference> entityTypesIndex = catalogHeader.collectionFileIndex();
		assertEquals(3, entityTypesIndex.size());

		assertEntityCollectionsHaveIdenticalContent(ioService, SEALED_CATALOG_SCHEMA, brandCollection, ioService.getEntityCollectionHeader(0L, entityTypesIndex.get(Entities.BRAND).entityTypePrimaryKey()));
		assertEntityCollectionsHaveIdenticalContent(ioService, SEALED_CATALOG_SCHEMA, storeCollection, ioService.getEntityCollectionHeader(0L, entityTypesIndex.get(Entities.STORE).entityTypePrimaryKey()));
		assertEntityCollectionsHaveIdenticalContent(ioService, SEALED_CATALOG_SCHEMA, productCollection, ioService.getEntityCollectionHeader(0L, entityTypesIndex.get(Entities.PRODUCT).entityTypePrimaryKey()));

		ioService.close();
	}

	@Test
	void shouldDetectInvalidCatalogContents() {
		prepareInvalidCatalogContents();

		assertThrows(
			UnexpectedCatalogContentsException.class,
			() -> {
				//noinspection EmptyTryBlock
				try (
					var ignored = new DefaultCatalogPersistenceService(
						Mockito.mock(CatalogContract.class),
						RENAMED_CATALOG,
						getStorageOptions(),
						getTransactionOptions(),
						Mockito.mock(Scheduler.class),
						Mockito.mock(ExportFileService.class)
					)
				) {
					// do nothing
				}
			}
		);
	}

	@Test
	void shouldDetectInvalidCatalogContentsAndAutomaticallyAdaptThem() throws IOException {
		final Path renamedCatalogPath = prepareInvalidCatalogContents();
		renamedCatalogPath.resolve(CatalogPersistenceService.RESTORE_FLAG).toFile().createNewFile();

		try (
			var persistenceService = new DefaultCatalogPersistenceService(
				Mockito.mock(CatalogContract.class),
				RENAMED_CATALOG,
				getStorageOptions(),
				getTransactionOptions(),
				Mockito.mock(Scheduler.class),
				Mockito.mock(ExportFileService.class)
			)
		) {
			final long lastCatalogVersion = persistenceService.getLastCatalogVersion();
			final CatalogHeader catalogHeader = persistenceService.getCatalogHeader(lastCatalogVersion);
			assertNotNull(catalogHeader);
			assertEquals(RENAMED_CATALOG, catalogHeader.catalogName());

			CatalogSchemaStoragePart.deserializeWithCatalog(Mockito.mock(CatalogContract.class), () -> {
				final CatalogSchemaStoragePart catalogSchema = persistenceService.getStoragePartPersistenceService(lastCatalogVersion)
					.getStoragePart(lastCatalogVersion, 1, CatalogSchemaStoragePart.class);
				assertEquals(catalogSchema.catalogSchema().getName(), RENAMED_CATALOG);
				return null;
			});
		}
	}

	@Test
	void shouldSignalizeInvalidEntityNames() {
		assertThrows(
			InvalidClassifierFormatException.class,
			() -> {
				try (
					var cps = new DefaultCatalogPersistenceService(
						SEALED_CATALOG_SCHEMA.getName(),
						getStorageOptions(),
						getTransactionOptions(),
						Mockito.mock(Scheduler.class),
						Mockito.mock(ExportFileService.class)
					)
				) {
					cps.verifyEntityType(
						Collections.emptyList(),
						"→"
					);
				}
			}
		);
	}

	@Test
	void shouldSignalizeConflictingEntityNames() {
		assertThrows(
			EntityTypeAlreadyPresentInCatalogSchemaException.class,
			() -> {
				try (
					var cps = new DefaultCatalogPersistenceService(
						SEALED_CATALOG_SCHEMA.getName(),
						getStorageOptions(),
						getTransactionOptions(),
						Mockito.mock(Scheduler.class),
						Mockito.mock(ExportFileService.class)
					)
				) {
					final EntityCollection mockCollection = mock(EntityCollection.class);
					when(mockCollection.getEntityType()).thenReturn("a");
					when(mockCollection.getSchema()).thenReturn(new EntitySchemaDecorator(() -> SEALED_CATALOG_SCHEMA, EntitySchema._internalBuild("a")));
					cps.verifyEntityType(
						List.of(mockCollection),
						"A"
					);
				}
			}
		);
	}

	@Test
	void shouldRefuseDuplicateCatalogName() {
		//noinspection EmptyTryBlock
		try (
			var ignored1 = new DefaultCatalogPersistenceService(
				SEALED_CATALOG_SCHEMA.getName(),
				getStorageOptions(),
				getTransactionOptions(),
				Mockito.mock(Scheduler.class),
				Mockito.mock(ExportFileService.class)
			)
		) {
		}

		assertThrows(
			DirectoryNotEmptyException.class,
			() -> {
				//noinspection EmptyTryBlock
				try (
					var ignored2 = new DefaultCatalogPersistenceService(
						CATALOG_SCHEMA.getName(),
						getStorageOptions(),
						getTransactionOptions(),
						Mockito.mock(Scheduler.class),
						Mockito.mock(ExportFileService.class)
					)
				) {
				}
			}
		);
	}

	@Test
	void shouldTerminateAndDeleteCatalog() throws IOException {
		shouldSerializeAndDeserializeCatalogHeader();

		final Path catalogDirectory = getStorageOptions().storageDirectory().resolve(TEST_CATALOG);
		try (
			var cps = new DefaultCatalogPersistenceService(
				Mockito.mock(CatalogContract.class),
				TEST_CATALOG,
				getStorageOptions(),
				getTransactionOptions(),
				Mockito.mock(Scheduler.class),
				Mockito.mock(ExportFileService.class)
			)
		) {
			assertTrue(catalogDirectory.toFile().exists());
			assertTrue(countFiles(catalogDirectory) > 0);
			cps.closeAndDelete();
			assertFalse(catalogDirectory.toFile().exists());
		}
	}

	@Test
	void shouldReturnDefaultHeaderOnEmptyDirectory() {
		try (
			var cps = new DefaultCatalogPersistenceService(
				SEALED_CATALOG_SCHEMA.getName(),
				getStorageOptions(),
				getTransactionOptions(),
				Mockito.mock(Scheduler.class),
				Mockito.mock(ExportFileService.class)
			)
		) {
			final CatalogHeader header = cps.getCatalogHeader(0L);
			assertNotNull(header);
			assertEquals(CatalogState.WARMING_UP, header.catalogState());
			assertEquals(TEST_CATALOG, header.catalogName());
			assertEquals(0L, header.version());
		}
	}

	@Test
	void shouldAppendWalFromByteBufferAndReadItAgain() {
		this.walService.write(1L, DATA_MUTATION_EXAMPLE);
		this.walService.write(1L, SCHEMA_MUTATION_EXAMPLE);

		final OffHeapWithFileBackupReference walReference = this.walService.getWalReference();
		final String catalogName = SEALED_CATALOG_SCHEMA.getName();

		// first switch to the transactional mode
		try (
			var cps = new DefaultCatalogPersistenceService(
				catalogName,
				getStorageOptions(),
				getTransactionOptions(),
				Mockito.mock(Scheduler.class),
				Mockito.mock(ExportFileService.class)
			)
		) {
			cps.getStoragePartPersistenceService(0L)
				.putStoragePart(0L, new CatalogSchemaStoragePart(CATALOG_SCHEMA));
			cps.storeHeader(
				this.catalogId,
				CatalogState.ALIVE,
				2L,
				0,
				null,
				Collections.emptyList(),
				new WarmUpDataStoreMemoryBuffer(cps.getStoragePartPersistenceService(0L))
			);
		}

		final TransactionMutation writtenTransactionMutation = new TransactionMutation(
			this.transactionId, 1L, 2, walReference.getContentLength(), OffsetDateTime.MIN
		);

		// and then write to the WAL
		try (
			var cps = new DefaultCatalogPersistenceService(
				Mockito.mock(CatalogContract.class),
				catalogName,
				getStorageOptions(),
				getTransactionOptions(),
				Mockito.mock(Scheduler.class),
				Mockito.mock(ExportFileService.class)
			)
		) {
			cps.appendWalAndDiscardDeferringSync(
				2L,
				writtenTransactionMutation,
				walReference
			);
			// the append only writes; this test reads the file back, so make it durable first
			cps.syncWal();
		}

		// READ THE WAL AGAIN
		final Path walFile = getStorageOptions().storageDirectory()
			.resolve(catalogName)
			.resolve(CatalogPersistenceService.getWalFileName(catalogName, 0));

		try (final ReadOnlyHandle readOnlyHandle = new ReadOnlyFileHandle(walFile, Crc32CChecksumFactory.INSTANCE, CompressionFactory.NO_COMPRESSION)) {
			readOnlyHandle.execute(
				input -> {
					input.skip(AbstractMutationLog.CUMULATIVE_CRC32_SIZE); // skip leading cumulative hash
					final int transactionSize = input.readInt();
					// the 2 bytes are required to record the classId
					final int offsetDateTimeDelta = 11;
					assertEquals(walReference.getContentLength() + AbstractMutationLog.TRANSACTION_MUTATION_SIZE - offsetDateTimeDelta + 2, transactionSize);
					final Mutation loadedTransactionMutation = (Mutation) StorageRecord.read(input, (stream, length) -> this.kryo.readClassAndObject(stream)).payload();
					assertEquals(writtenTransactionMutation, loadedTransactionMutation);
					final Mutation firstMutation = (Mutation) StorageRecord.read(input, (stream, length) -> this.kryo.readClassAndObject(stream)).payload();
					assertEquals(DATA_MUTATION_EXAMPLE, firstMutation);
					final Mutation secondMutation = (Mutation) StorageRecord.read(input, (stream, length) -> this.kryo.readClassAndObject(stream)).payload();
					assertEquals(SCHEMA_MUTATION_EXAMPLE, secondMutation);
					return null;
				}
			);
		}
	}

	@Test
	void shouldTraverseBootstrapRecordsFromOldestToNewest() throws IOException {
		final String catalogName = SEALED_CATALOG_SCHEMA.getName();
		final StorageSettings storageSettings = new StorageSettings(
			getStorageOptions(),
			getTransactionOptions()
		);
		final OffsetDateTime startTime = Instant.ofEpochMilli(System.currentTimeMillis() - 1_000_000_000L).atZone(ZoneId.systemDefault()).toOffsetDateTime();
		// pins this thread's clock ~11.5 days into the past - cleared in the `finally` below (and, belt and braces,
		// in `tearDown`). Leaving it pinned stamps every bootstrap record written afterwards on this worker thread
		// with a past timestamp, which silently breaks any test that relates a bootstrap timestamp to the wall clock.
		DefaultCatalogPersistenceService.CURRENT_TIME_MILLIS.set(() -> startTime.toInstant().toEpochMilli());

		try (
			final DefaultCatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
				catalogName,
				getStorageOptions(),
				getTransactionOptions(),
				Mockito.mock(Scheduler.class),
				Mockito.mock(ExportFileService.class)
			);
		) {
			ioService.storeHeader(
				UUIDUtil.randomUUID(),
				CatalogState.ALIVE,
				0L,
				1,
				null,
				Collections.emptyList(),
				new WarmUpDataStoreMemoryBuffer(ioService.getStoragePartPersistenceService(0L))
			);
			for (int i = 0; i < 12; i++) {
				final int catalogVersion = i + 2;
				DefaultCatalogPersistenceService.CURRENT_TIME_MILLIS.set(() -> startTime.plusHours(catalogVersion).toInstant().toEpochMilli());
				ioService.recordBootstrap(catalogVersion, catalogName, 0, null);

				final File tempFile = File.createTempFile("test", ".tmp");
				final OffHeapWithFileBackupReference walReference = OffHeapWithFileBackupReference.withFilePath(
					tempFile.toPath(), 0, 0L, tempFile::delete
				);
				ioService.appendWalAndDiscardDeferringSync(
					catalogVersion,
					new TransactionMutation(UUIDUtil.randomUUID(), catalogVersion, 0, 0, OffsetDateTime.now()),
					walReference
				);
				ioService.syncWal();
			}

			final PaginatedList<MaterializedVersionBlock> catalogVersions = ioService.getCatalogVersions(TimeFlow.FROM_OLDEST_TO_NEWEST, 1, 5);
			assertEquals(5, catalogVersions.getData().size());
			// initial bootstrap, single header write and 12 recorded bootstraps
			assertEquals(14, catalogVersions.getTotalRecordCount());
			long startVersion = 0;
			for (int i = 0; i <= 4; i++) {
				final MaterializedVersionBlock record = catalogVersions.getData().get(i);
				assertEquals(startVersion, record.startVersion());
				assertEquals(i, record.endVersion());
				assertNotNull(record.introducedAt());
				startVersion = record.endVersion() + 1;
			}

			final PaginatedList<MaterializedVersionBlock> catalogVersionsLastPage = ioService.getCatalogVersions(TimeFlow.FROM_OLDEST_TO_NEWEST, 3, 5);
			assertEquals(4, catalogVersionsLastPage.getData().size());
			for (int i = 0; i < 4; i++) {
				final MaterializedVersionBlock record = catalogVersionsLastPage.getData().get(i);
				assertEquals(10 + i, record.startVersion());
				assertEquals(10 + i, record.endVersion());
				assertNotNull(record.introducedAt());
			}

			final Optional<CatalogBootstrap> first = getFirstCatalogBootstrap(catalogName, storageSettings);
			assertTrue(first.isPresent());
			assertEquals(0, first.get().catalogVersion());

			final CatalogBootstrap last = getLastCatalogBootstrap(catalogName, storageSettings);
			assertNotNull(last);
			assertEquals(13, last.catalogVersion());

			// a point-in-time lookup resolves the state AS OF the moment - the newest record whose timestamp is not
			// after it - so it may never return a record stamped later than the requested moment. Versions 2..13
			// carry the timestamp `startTime + version hours`, versions 0 and 1 both carry `startTime`.
			final CatalogBootstrap m0 = getCatalogBootstrapForSpecificMoment(catalogName, storageSettings, startTime);
			assertNotNull(m0);
			assertEquals(1, m0.catalogVersion());

			final CatalogBootstrap m1 = getCatalogBootstrapForSpecificMoment(catalogName, storageSettings, startTime.plusHours(5));
			assertNotNull(m1);
			assertEquals(5, m1.catalogVersion());

			// one minute PAST the version 5 checkpoint: version 6 is still an hour in the future of the requested
			// moment and must not be handed back
			final CatalogBootstrap m2 = getCatalogBootstrapForSpecificMoment(catalogName, storageSettings, startTime.plusHours(5).plusMinutes(1));
			assertNotNull(m2);
			assertEquals(5, m2.catalogVersion());

			// one minute BEFORE the version 5 checkpoint: the state at that moment is still version 4
			final CatalogBootstrap m3 = getCatalogBootstrapForSpecificMoment(catalogName, storageSettings, startTime.plusHours(5).minusMinutes(1));
			assertNotNull(m3);
			assertEquals(4, m3.catalogVersion());

			final CatalogBootstrap m4 = getCatalogBootstrapForSpecificMoment(catalogName, storageSettings, startTime.plusHours(15));
			assertNotNull(m4);
			assertEquals(13, m4.catalogVersion());

			// a moment that precedes the whole retained history has no state to return - reporting the oldest
			// retained record instead would silently answer a different question than the one asked
			assertThrows(
				TemporalDataNotAvailableException.class,
				() -> getCatalogBootstrapForSpecificMoment(
					catalogName, storageSettings, startTime.minusMinutes(1))
			);
		} finally {
			DefaultCatalogPersistenceService.CURRENT_TIME_MILLIS.remove();
		}
	}

	@Test
	void shouldInitializeCompactionCadenceTimestampsAtConstructionTime() {
		// gate 6 (timestamp lifecycle): both the catalog-file and entity-collection compaction-cadence clocks must
		// be seeded from the (test-overridable) construction-time clock, since a compacted file is always replaced
		// by a brand-new persistence service instance rather than having its timestamp mutated in place
		final String catalogName = SEALED_CATALOG_SCHEMA.getName();
		final long fixedNow = Instant.now().minusSeconds(3_600L).toEpochMilli();
		DefaultCatalogPersistenceService.CURRENT_TIME_MILLIS.set(() -> fixedNow);
		try (
			final DefaultCatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
				catalogName,
				getStorageOptions(),
				getTransactionOptions(),
				Mockito.mock(Scheduler.class),
				Mockito.mock(ExportFileService.class)
			)
		) {
			assertEquals(fixedNow, ioService.getLastCatalogCompactionAtMillis());

			ioService.storeHeader(
				UUIDUtil.randomUUID(),
				CatalogState.ALIVE,
				0L,
				1,
				null,
				Collections.emptyList(),
				new WarmUpDataStoreMemoryBuffer(ioService.getStoragePartPersistenceService(0L))
			);

			final DefaultEntityCollectionPersistenceService entityCollectionPersistenceService =
				ioService.getOrCreateEntityCollectionPersistenceService(0L, "product", 1);
			assertEquals(fixedNow, entityCollectionPersistenceService.getLastCompactionAtMillis());
		} finally {
			DefaultCatalogPersistenceService.CURRENT_TIME_MILLIS.remove();
		}
	}

	@Test
	void shouldTraverseBootstrapRecordsFromNewestToOldest() throws IOException {
		final String catalogName = SEALED_CATALOG_SCHEMA.getName();
		try (
			final DefaultCatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
				catalogName,
				getStorageOptions(),
				getTransactionOptions(),
				Mockito.mock(Scheduler.class),
				Mockito.mock(ExportFileService.class)
			)
		) {

			ioService.storeHeader(
				UUIDUtil.randomUUID(),
				CatalogState.ALIVE,
				0L,
				1,
				null,
				Collections.emptyList(),
				new WarmUpDataStoreMemoryBuffer(ioService.getStoragePartPersistenceService(0L))
			);

			for (int i = 0; i < 12; i++) {
				final int catalogVersion = i + 2;
				ioService.recordBootstrap(catalogVersion, catalogName, 0, null);

				final File tempFile = File.createTempFile("test", ".tmp");
				final OffHeapWithFileBackupReference walReference = OffHeapWithFileBackupReference.withFilePath(
					tempFile.toPath(), 0, 0L, tempFile::delete
				);
				ioService.appendWalAndDiscardDeferringSync(
					catalogVersion,
					new TransactionMutation(UUIDUtil.randomUUID(), catalogVersion, 0, 0, OffsetDateTime.now()),
					walReference
				);
				ioService.syncWal();
			}

			final PaginatedList<MaterializedVersionBlock> catalogVersions = ioService.getCatalogVersions(TimeFlow.FROM_NEWEST_TO_OLDEST, 1, 5);
			assertEquals(5, catalogVersions.getData().size());
			assertEquals(14, catalogVersions.getTotalRecordCount());
			for (int i = 0; i < 5; i++) {
				final MaterializedVersionBlock record = catalogVersions.getData().get(i);
				assertEquals(14 - (i + 1), record.startVersion());
				assertEquals(14 - (i + 1), record.endVersion());
				assertNotNull(record.introducedAt());
			}

			final PaginatedList<MaterializedVersionBlock> catalogVersionsLastPage = ioService.getCatalogVersions(TimeFlow.FROM_NEWEST_TO_OLDEST, 3, 5);
			assertEquals(4, catalogVersionsLastPage.getData().size());
			for (int i = 0; i < 3; i++) {
				final MaterializedVersionBlock record = catalogVersionsLastPage.getData().get(i);
				assertEquals(4 - (i + 1), record.startVersion());
				assertEquals(4 - (i + 1), record.endVersion());
				assertNotNull(record.introducedAt());
			}
		}
	}

	@Test
	void shouldTrimBootstrapRecords() {
		final String catalogName = SEALED_CATALOG_SCHEMA.getName();
		try (
			final DefaultCatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
				catalogName,
				getStorageOptions(),
				getTransactionOptions(),
				Mockito.mock(Scheduler.class),
				Mockito.mock(ExportFileService.class)
			)
		) {

			final OffsetDateTime timestamp = OffsetDateTime.now();
			for (int i = 0; i < 12; i++) {
				ioService.recordBootstrap(
					i + 1, catalogName, 0,
					timestamp.plusMinutes(i).toInstant().toEpochMilli(),
					null
				);
			}

			final PaginatedList<MaterializedVersionBlock> catalogVersions0 = ioService.getCatalogVersions(TimeFlow.FROM_OLDEST_TO_NEWEST, 1, 20);
			assertEquals(0, catalogVersions0.getData().get(0).endVersion());
			assertEquals(13, catalogVersions0.getTotalRecordCount());

			trimAndCheck(ioService, 4, 4, 9);
			trimAndCheck(ioService, 7, 7, 6);
			trimAndCheck(ioService, 8, 8, 5);
		}
	}

	@Test
	@DisplayName("Trimming marks every generation below the history floor obsolete, but never the one serving it")
	void shouldCountObsoletePersistenceServicesBelowHistoryFloor() {
		// generations registered at these versions; the one at or below the floor still serves it, because
		// `getStoragePartPersistenceService` resolves a version to the closest registered version at or below it
		final long[] registeredVersions = {0L, 10L, 20L, 30L};

		// floor lands exactly on a registered generation -> everything strictly below it is obsolete
		assertEquals(
			2,
			countObsoletePersistenceServices(
				computeInsertPositionOfLongInOrderedArray(20L, registeredVersions))
		);

		// floor lands BETWEEN two generations - the case WAL-driven trimming produces almost every time, since the
		// floor arrives as `lastVersionInFile + 1`. Generation 20 still serves version 25 and must be kept, so only
		// the two below it are obsolete.
		assertEquals(
			2,
			countObsoletePersistenceServices(
				computeInsertPositionOfLongInOrderedArray(25L, registeredVersions))
		);

		// floor above every generation - the newest one still serves it
		assertEquals(
			3,
			countObsoletePersistenceServices(
				computeInsertPositionOfLongInOrderedArray(99L, registeredVersions))
		);

		// floor at or below the oldest generation - nothing may be dropped
		assertEquals(
			0,
			countObsoletePersistenceServices(
				computeInsertPositionOfLongInOrderedArray(0L, registeredVersions))
		);
		assertEquals(
			0,
			countObsoletePersistenceServices(
				computeInsertPositionOfLongInOrderedArray(5L, registeredVersions))
		);
	}

	@Nonnull
	private Path prepareInvalidCatalogContents() {
		final Path dataDirectory = getTestDirectory().resolve(DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST);
		final Path catalogPath = dataDirectory.resolve(TEST_CATALOG);
		final Path renamedCatalogPath = dataDirectory.resolve(RENAMED_CATALOG);

		try (
			final DefaultCatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
				SEALED_CATALOG_SCHEMA.getName(),
				getStorageOptions(),
				getTransactionOptions(),
				Mockito.mock(Scheduler.class),
				Mockito.mock(ExportFileService.class)
			)
		) {
			ioService.getStoragePartPersistenceService(0L)
				.putStoragePart(0L, new CatalogSchemaStoragePart(CATALOG_SCHEMA));

			final EvitaSession mockSession = mock(EvitaSession.class);
			when(mockSession.getCatalogSchema()).thenReturn(SEALED_CATALOG_SCHEMA);

			final EntityCollection productCollection = constructEntityCollectionWithSomeEntities(
				ioService, SEALED_CATALOG_SCHEMA, this.dataGenerator.getSampleProductSchema(mockSession, EntitySchemaBuilder::toInstance), 1
			);
			final EntityCollection brandCollection = constructEntityCollectionWithSomeEntities(
				ioService, SEALED_CATALOG_SCHEMA, this.dataGenerator.getSampleBrandSchema(mockSession, EntitySchemaBuilder::toInstance), 2
			);
			final EntityCollection storeCollection = constructEntityCollectionWithSomeEntities(
				ioService, SEALED_CATALOG_SCHEMA, this.dataGenerator.getSampleStoreSchema(mockSession, EntitySchemaBuilder::toInstance), 3
			);

			// try to serialize
			ioService.storeHeader(
				this.catalogId,
				CatalogState.WARMING_UP,
				0L, 0, null,
				Arrays.asList(
					(EntityCollectionFileHeader) productCollection.flush().header(),
					(EntityCollectionFileHeader) brandCollection.flush().header(),
					(EntityCollectionFileHeader) storeCollection.flush().header()
				),
				new WarmUpDataStoreMemoryBuffer(ioService.getStoragePartPersistenceService(0L))
			);
		}

		// rename catalog bootstrap file
		assertTrue(catalogPath.resolve(CatalogPersistenceService.getCatalogBootstrapFileName(TEST_CATALOG)).toFile()
			.renameTo(catalogPath.resolve(CatalogPersistenceService.getCatalogBootstrapFileName(RENAMED_CATALOG)).toFile()));

		// rename all catalog indexes
		int index = findFirstExistingFileIndex(TEST_CATALOG);
		do {
			assertTrue(catalogPath.resolve(CatalogPersistenceService.getCatalogDataStoreFileName(TEST_CATALOG, index)).toFile()
				.renameTo(catalogPath.resolve(CatalogPersistenceService.getCatalogDataStoreFileName(RENAMED_CATALOG, index)).toFile()));
			index++;
		} while (catalogPath.resolve(CatalogPersistenceService.getCatalogDataStoreFileName(TEST_CATALOG, index)).toFile().exists());

		// finally rename folder
		assertTrue(catalogPath.toFile().renameTo(renamedCatalogPath.toFile()));

		return renamedCatalogPath;
	}

	@Nonnull
	private StorageOptions getStorageOptions() {
		return new StorageOptions(
			getTestDirectory().resolve(DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST),
			getTestDirectory().resolve(DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST),
			60, 60,
			StorageOptions.DEFAULT_OUTPUT_BUFFER_SIZE, 1,
			false, false, true, 0.01, 1_000_000L, false
		);
	}

	@Nonnull
	private TransactionOptions getTransactionOptions() {
		return new TransactionOptions(
			getTestDirectory().resolve(TX_DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST),
			TransactionOptions.DEFAULT_TRANSACTION_MEMORY_BUFFER_LIMIT_SIZE,
			TransactionOptions.DEFAULT_TRANSACTION_MEMORY_REGION_COUNT,
			TransactionOptions.DEFAULT_WAL_SIZE_BYTES,
			TransactionOptions.DEFAULT_WAL_FILE_COUNT_KEPT,
			TransactionOptions.DEFAULT_WAIT_FOR_TRANSACTION_ACCEPTANCE,
			TransactionOptions.DEFAULT_FLUSH_FREQUENCY,
			TransactionOptions.DEFAULT_CHECKPOINT_INTERVAL,
			TransactionOptions.DEFAULT_CONFLICT_RING_BUFFER_SIZE,
			TransactionOptions.DEFAULT_CONFLICT_RESOLUTION
		);
	}

	/**
	 * Number of catalog versions written by {@link #writeSeveralGenerations(DefaultCatalogPersistenceService)} - each
	 * one rewrites the catalog schema part and the catalog header, so every round leaves the previous copies as waste
	 * and trips the compaction thresholds set by {@link #eagerCompactionStorageOptions(boolean, long)}.
	 */
	private static final int VERSIONS_WRITTEN = 8;

	/**
	 * Storage options that compact on every round, so that each round registers its own catalog persistence service
	 * and leaves the previous data file behind. The time-travel mode is a parameter rather than a constant because
	 * the reclamation seam is shared by both modes and its guards must hold in each.
	 *
	 * @param timeTravelEnabled        whether compacted-away files are kept as history instead of deleted
	 * @param timeTravelSizeLimitBytes the history budget - irrelevant when time travel is off
	 * @return the storage options
	 */
	@Nonnull
	private StorageOptions eagerCompactionStorageOptions(boolean timeTravelEnabled, long timeTravelSizeLimitBytes) {
		return StorageOptions.builder()
			.storageDirectory(getTestDirectory().resolve(DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST))
			.workDirectory(getTestDirectory().resolve(DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST))
			.computeCRC32(true)
			// compact whatever the file size, as soon as a single record of waste appears
			.fileSizeCompactionThresholdBytes(1L)
			.minimalActiveRecordShare(0.99)
			.maxWasteActiveShare(0.99)
			.minCompactionIntervalMilliseconds(0L)
			.timeTravelEnabled(timeTravelEnabled)
			.timeTravelSizeLimitBytes(timeTravelSizeLimitBytes)
			.build();
	}

	/**
	 * Transaction options with checkpointing at the end of every round, so each version publishes its bootstrap
	 * record immediately instead of deferring it behind the checkpoint interval.
	 *
	 * @return the transaction options
	 */
	@Nonnull
	private TransactionOptions eagerCheckpointTransactionOptions() {
		return TransactionOptions.builder()
			.transactionWorkDirectory(getTestDirectory().resolve(TX_DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST))
			.checkpointIntervalInMillis(0L)
			.build();
	}

	/**
	 * Writes {@link #VERSIONS_WRITTEN} catalog versions, each leaving the previous catalog data file behind.
	 *
	 * @param ioService the service under test
	 */
	private void writeSeveralGenerations(@Nonnull DefaultCatalogPersistenceService ioService) {
		final UUID catalogId = UUIDUtil.randomUUID();
		long catalogVersion = 0L;
		for (int i = 0; i < VERSIONS_WRITTEN; i++) {
			ioService.getStoragePartPersistenceService(catalogVersion)
				.putStoragePart(catalogVersion, new CatalogSchemaStoragePart(CATALOG_SCHEMA));
			ioService.storeHeader(
				catalogId,
				CatalogState.ALIVE,
				catalogVersion,
				1,
				null,
				Collections.emptyList(),
				new WarmUpDataStoreMemoryBuffer(ioService.getStoragePartPersistenceService(catalogVersion))
			);
			// the first round transitions the catalog to ALIVE and lands on version 1 internally
			catalogVersion = catalogVersion == 0L ? 2L : catalogVersion + 1L;
		}
	}

	/**
	 * Lists the catalog data files present in the catalog folder, newest index last.
	 *
	 * @return the catalog data files sorted by their file index
	 */
	@Nonnull
	private List<File> listCatalogDataFiles() {
		return listCatalogDataFiles(TEST_CATALOG);
	}

	/**
	 * Lists the catalog data files of the named catalog, newest index last.
	 *
	 * @param catalogName name of the catalog whose folder should be listed
	 * @return the catalog data files sorted by their file index
	 */
	@Nonnull
	private List<File> listCatalogDataFiles(@Nonnull String catalogName) {
		final Path catalogDirectory = getTestDirectory()
			.resolve(DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST)
			.resolve(catalogName);
		return Arrays.stream(
				Objects.requireNonNull(
					catalogDirectory.toFile().listFiles((dir, name) -> name.endsWith(CATALOG_FILE_SUFFIX))
				)
			)
			.sorted(Comparator.comparingInt(file -> getIndexFromCatalogFileName(file.getName())))
			.toList();
	}

	@Nonnull
	private EntityCollection constructEntityCollectionWithSomeEntities(
		@Nonnull CatalogPersistenceService ioService,
		@Nonnull SealedCatalogSchema catalogSchema,
		@Nonnull SealedEntitySchema entitySchema,
		int entityTypePrimaryKey
	) {
		final Catalog mockCatalog = getMockCatalog(catalogSchema, entitySchema);
		Mockito.when(mockCatalog.getTrafficRecordingEngine()).thenReturn(Mockito.mock(TrafficRecordingEngine.class));
		final CatalogSchemaContract catalogSchemaContract = Mockito.mock(CatalogSchemaContract.class);
		final String entityType = entitySchema.getName();
		final EntityCollectionPersistenceService entityCollectionPersistenceService = ioService.getOrCreateEntityCollectionPersistenceService(
			0L, entityType, entityTypePrimaryKey
		);

		final EntityCollection entityCollection = new EntityCollection(
			catalogSchema.getName(),
			0L,
			CatalogState.WARMING_UP,
			entityTypePrimaryKey,
			entityType,
			64,
			ioService,
			entityCollectionPersistenceService,
			NoCacheSupervisor.INSTANCE,
			this.sequenceService,
			createTrafficRecordingEngine(catalogSchema)
		);

		ReflectionUtils.setFieldValue(entityCollection, "initialSchema", ((EntitySchemaDecorator)entitySchema).getDelegate());
		entityCollection.attachToCatalog(null, mockCatalog);

		// Use the captor when defining the mock behavior
		@SuppressWarnings("unchecked")
		final ArgumentCaptor<EngineMutation<?>> mutationCaptor = ArgumentCaptor.forClass(EngineMutation.class);
		final Evita mockEvita = mock(Evita.class);
		Mockito.doAnswer(invocation -> {
			final ModifyCatalogSchemaMutation mutation = invocation.getArgument(0);
			for (LocalCatalogSchemaMutation schemaMutation : mutation.getSchemaMutations()) {
				if (schemaMutation instanceof ModifyEntitySchemaMutation mesm && mesm.getName().equals(entityType)) {
					entityCollection.updateSchema(null, catalogSchemaContract, mesm.getSchemaMutations());
				}
			}
			return new ProgressRecord<>(
				"mock",
				null,
				new ProgressingFuture<Void>(0, __ -> null),
				new ImmediateExecutorService()
			);
		}).when(mockEvita).applyMutation(mutationCaptor.capture());

		final EvitaSession session = mock(EvitaSession.class);
		when(session.getEvita()).thenReturn(mockEvita);
		when(session.getCatalogSchema()).thenReturn(catalogSchema);

		this.dataGenerator.generateEntities(
				entitySchema,
				(serializable, faker) -> null,
				40
			)
			.limit(10)
			.forEach(it -> it.toMutation().ifPresent(mut -> entityCollection.upsertEntity(session, mut)));

		return entityCollection;
	}

	private void assertEntityCollectionsHaveIdenticalContent(
		@Nonnull CatalogPersistenceService ioService,
		@Nonnull SealedCatalogSchema catalogSchema,
		@Nonnull EntityCollection entityCollection,
		@Nonnull EntityCollectionFileHeader collectionHeader
	) {
		assertEquals(entityCollection.size(), collectionHeader.recordCount());
		final ObservableOutputKeeper outputKeeper = ObservableOutputKeeper._internalBuild(Mockito.mock(Scheduler.class));

		final SealedEntitySchema schema = entityCollection.getSchema();
		final String entityType = schema.getName();
		final int entityTypePrimaryKey = entityCollection.getEntityTypePrimaryKey();
		final EntityCollectionPersistenceService entityCollectionPersistenceService = ioService.getOrCreateEntityCollectionPersistenceService(
			0L, entityType, entityTypePrimaryKey
		);

		final EntityCollection collection = new EntityCollection(
			catalogSchema.getName(),
			0L,
			CatalogState.WARMING_UP,
			entityTypePrimaryKey,
			entityType,
			64,
			ioService,
			entityCollectionPersistenceService,
			NoCacheSupervisor.INSTANCE,
			this.sequenceService,
			createTrafficRecordingEngine(catalogSchema)
		);

		ReflectionUtils.setFieldValue(collection, "initialSchema", ((EntitySchemaDecorator)schema).getDelegate());
		collection.attachToCatalog(null, getMockCatalog(catalogSchema, schema));

		final EvitaSession mockSession = mock(EvitaSession.class);
		for (Integer primaryKey : entityCollection.getGlobalIndex().getAllPrimaryKeys()) {
			final EvitaRequest request = new EvitaRequest(
				query(
					collection(entityCollection.getSchema().getName()),
					filterBy(entityPrimaryKeyInSet(primaryKey)),
					require(entityFetchAll())
				),
				OffsetDateTime.now(),
				EntityClassifier.class,
				null
			);
			final SealedEntity deserializedEntity = collection.getEntity(primaryKey, request, mockSession).orElseThrow();
			final SealedEntity originEntity = entityCollection.getEntity(primaryKey, request, mockSession).orElseThrow();
			assertExactlyEquals(originEntity, deserializedEntity);
		}

		outputKeeper.close();
	}

	/**
	 * Finds the index of the first existing file for a given catalog name.
	 *
	 * @param catalogName the name of the catalog
	 * @return the index of the first existing file, or 0 if no files exist
	 */
	private int findFirstExistingFileIndex(@Nonnull String catalogName) {
		final Pattern pattern = getCatalogDataStoreFileNamePattern(catalogName);
		final File[] catalogFiles = getTestDirectory().resolve(DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST)
			.resolve(catalogName)
			.toFile()
			.listFiles(
				(dir, name) -> name.endsWith(CATALOG_FILE_SUFFIX)
			);
		if (catalogFiles.length == 0) {
			return 0;
		} else {
			int maxIndex = Integer.MAX_VALUE;
			for (File catalogFile : catalogFiles) {
				final String name = catalogFile.getName();
				final Matcher matcher = pattern.matcher(name);
				if (matcher.matches()) {
					final int index = Integer.parseInt(matcher.group(1));
					if (maxIndex > index) {
						maxIndex = index;
					}
				}
			}
			return maxIndex == Integer.MAX_VALUE ? 0 : maxIndex;
		}
	}

	/**
	 * Verifies that the `timeTravelSizeLimitBytes` guard reclaims history on real files, exercising the plumbing the
	 * pure {@link io.evitadb.store.catalog.TimeTravelRetention} tests cannot reach: the directory scan, the generation
	 * pins read out of real catalog headers, and the seam that trims the bootstrap file and deletes the files.
	 *
	 * The guard is invoked synchronously here rather than through its {@link Scheduler} task, which is mocked away -
	 * awaiting an asynchronous purge would only add flakiness to an otherwise deterministic assertion.
	 */
	@Nested
	@DisplayName("Time travel size guard")
	class TimeTravelSizeGuardTest {

		/**
		 * Storage options that compact on every round and keep the compacted-away file for time travel.
		 *
		 * @param timeTravelSizeLimitBytes the history budget under test
		 * @return the storage options
		 */
		@Nonnull
		private StorageOptions timeTravelStorageOptions(long timeTravelSizeLimitBytes) {
			return eagerCompactionStorageOptions(true, timeTravelSizeLimitBytes);
		}

		/**
		 * Transaction options that defer every checkpoint instead of publishing it with its round.
		 *
		 * `CheckpointCoordinator` initialises its last-completed timestamp at construction and compares it against
		 * this interval, so an interval far longer than the test can possibly take makes `isCheckpointDue()` false for
		 * every round - each one builds its bootstrap record and stashes it rather than publishing it. Publication is
		 * then entirely under the test's control through `checkpoint()`, with no wall clock involved.
		 *
		 * @return the transaction options
		 */
		@Nonnull
		private TransactionOptions deferredCheckpointTransactionOptions() {
			return TransactionOptions.builder()
				.transactionWorkDirectory(getTestDirectory().resolve(TX_DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST))
				.checkpointIntervalInMillis(60_000L)
				.build();
		}

		/**
		 * Writes {@link #VERSIONS_WRITTEN} warm-up flushes, each leaving the previous catalog data file behind.
		 *
		 * Warm-up rewrites the bootstrap file down to a single record on every flush, so the files left behind by its
		 * compactions are not history at all - no bootstrap record can reach them. Nothing else ever sweeps them
		 * either: the write-ahead log purge that normally would has no log to fire from here.
		 *
		 * @param ioService the service under test
		 */
		private void writeSeveralWarmUpGenerations(@Nonnull DefaultCatalogPersistenceService ioService) {
			final UUID catalogId = UUIDUtil.randomUUID();
			for (int i = 0; i < VERSIONS_WRITTEN; i++) {
				ioService.getStoragePartPersistenceService(0L)
					.putStoragePart(0L, new CatalogSchemaStoragePart(CATALOG_SCHEMA));
				ioService.storeHeader(
					catalogId,
					CatalogState.WARMING_UP,
					0L,
					1,
					null,
					Collections.emptyList(),
					new WarmUpDataStoreMemoryBuffer(ioService.getStoragePartPersistenceService(0L))
				);
			}
		}

		@Test
		@DisplayName("should give up every generation of history when the budget cannot hold one")
		void shouldGiveUpEveryGenerationWhenBudgetCannotHoldOne() {
			try (
				final DefaultCatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
					TEST_CATALOG,
					timeTravelStorageOptions(0L),
					eagerCheckpointTransactionOptions(),
					Mockito.mock(Scheduler.class),
					Mockito.mock(ExportFileService.class)
				)
			) {
				writeSeveralGenerations(ioService);

				// precondition - compaction really produced history to reclaim, else the assertions below are vacuous
				final List<File> beforeGuard = listCatalogDataFiles();
				assertTrue(
					beforeGuard.size() > 2,
					"expected several catalog data files to accumulate, got " + beforeGuard.size()
				);
				assertTrue(ioService.computeRetainedHistoryBytes() > 0L);

				ioService.enforceTimeTravelSizeLimit();

				final List<File> afterGuard = listCatalogDataFiles();
				assertEquals(
					1, afterGuard.size(),
					"a zero budget must leave only the active generation, got " + afterGuard.size() + " files"
				);
				// the surviving file must be the newest one - reclaiming from the wrong end would destroy the catalog
				assertEquals(
					beforeGuard.get(beforeGuard.size() - 1).getName(),
					afterGuard.get(0).getName()
				);
				assertEquals(0L, ioService.computeRetainedHistoryBytes());
			}
		}

		@Test
		@DisplayName("should reclaim a warm-up catalog's leftovers, which no bootstrap record can reach")
		void shouldReclaimWarmUpLeftovers() {
			try (
				final DefaultCatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
					TEST_CATALOG,
					timeTravelStorageOptions(0L),
					eagerCheckpointTransactionOptions(),
					Mockito.mock(Scheduler.class),
					Mockito.mock(ExportFileService.class)
				)
			) {
				writeSeveralWarmUpGenerations(ioService);

				final List<File> beforeGuard = listCatalogDataFiles();
				assertTrue(
					beforeGuard.size() > 2,
					"expected several catalog data files to accumulate, got " + beforeGuard.size()
				);

				ioService.enforceTimeTravelSizeLimit();

				final List<File> afterGuard = listCatalogDataFiles();
				assertEquals(
					1, afterGuard.size(),
					"warm-up leftovers must be reclaimable too, got " + afterGuard.size() + " files"
				);
				assertEquals(
					beforeGuard.get(beforeGuard.size() - 1).getName(),
					afterGuard.get(0).getName()
				);
			}
		}

		@Test
		@DisplayName("should freeze the whole catalog folder while a directory read hold is open")
		void shouldFreezeTheFolderWhileADirectoryReadHoldIsOpen() {
			try (
				final DefaultCatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
					TEST_CATALOG,
					timeTravelStorageOptions(0L),
					eagerCheckpointTransactionOptions(),
					Mockito.mock(Scheduler.class),
					Mockito.mock(ExportFileService.class)
				)
			) {
				// warm-up leftovers are the case a version cannot describe: no bootstrap record reaches them, so the
				// sweep is right to take them, and a consumer walking the folder is nonetheless reading them
				writeSeveralWarmUpGenerations(ioService);
				final List<File> beforeHold = listCatalogDataFiles();
				assertTrue(beforeHold.size() > 2);

				final CatalogDirectoryReadHold hold = ioService.acquireDirectoryReadHold();
				ioService.enforceTimeTravelSizeLimit();

				assertEquals(
					beforeHold.stream().map(File::getName).toList(),
					listCatalogDataFiles().stream().map(File::getName).toList(),
					"a directory read hold must freeze the folder, unreachable files included"
				);

				hold.close();
				ioService.enforceTimeTravelSizeLimit();
				assertEquals(
					1, listCatalogDataFiles().size(),
					"releasing the hold must let the deferred sweep through"
				);

				// closing a lease twice must not decrement the counter below zero and leave the folder permanently
				// unheld while a second consumer is still walking it
				hold.close();
				final CatalogDirectoryReadHold secondHold = ioService.acquireDirectoryReadHold();
				writeSeveralWarmUpGenerations(ioService);
				final List<File> beforeSecondSweep = listCatalogDataFiles();
				assertTrue(beforeSecondSweep.size() > 1);
				ioService.enforceTimeTravelSizeLimit();
				assertEquals(
					beforeSecondSweep.stream().map(File::getName).toList(),
					listCatalogDataFiles().stream().map(File::getName).toList(),
					"a double release of an earlier lease must not cancel a hold someone else is relying on"
				);
				secondHold.close();
			}
		}

		@Test
		@DisplayName("should still reclaim warm-up leftovers while an ordinary session holds a version")
		void shouldReclaimWarmUpLeftoversWhileAVersionIsPinned() {
			try (
				final DefaultCatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
					TEST_CATALOG,
					timeTravelStorageOptions(0L),
					eagerCheckpointTransactionOptions(),
					Mockito.mock(Scheduler.class),
					Mockito.mock(ExportFileService.class)
				)
			) {
				writeSeveralWarmUpGenerations(ioService);
				assertTrue(listCatalogDataFiles().size() > 2);

				// every open session pins the version it reads, so gating the sweep on "is anything pinned" gates it on
				// "is anyone connected". Warm-up is where that bites hardest: it permits a single session at a time and
				// a bulk import holds it for the whole import, which is exactly when these leftovers pile up
				ioService.catalogVersionPinned(0L);
				ioService.enforceTimeTravelSizeLimit();

				assertEquals(
					1, listCatalogDataFiles().size(),
					"a held version must not stop the sweep - it protects data reachable from a record, and these " +
						"leftovers are reachable from none"
				);
			}
		}

		@Test
		@DisplayName("should reclaim unreachable files even when the operator asked for unlimited history")
		void shouldReclaimUnreachableFilesUnderAnUnlimitedBudget() {
			// unlike the sibling tests this one goes through the scheduler rather than calling the guard directly:
			// what is under test is precisely the wiring - an unlimited budget used to leave the guard task unbound,
			// so nothing ever called the method the other tests invoke by hand
			final Scheduler scheduler = Mockito.mock(Scheduler.class);
			final ArgumentCaptor<Runnable> scheduledTask = ArgumentCaptor.forClass(Runnable.class);
			try (
				final DefaultCatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
					TEST_CATALOG,
					// a negative limit means unlimited history - it switches off the budget, not the reclamation of
					// files no bootstrap record can reach. Those were never history, and an operator asking for
					// unlimited history is precisely the one who would otherwise never get them back.
					timeTravelStorageOptions(-1L),
					eagerCheckpointTransactionOptions(),
					scheduler,
					Mockito.mock(ExportFileService.class)
				)
			) {
				writeSeveralWarmUpGenerations(ioService);

				final List<File> beforeGuard = listCatalogDataFiles();
				assertTrue(
					beforeGuard.size() > 2,
					"expected several catalog data files to accumulate, got " + beforeGuard.size()
				);

				// run whatever compaction planned on this thread instead of racing the scheduler
				Mockito.verify(scheduler, Mockito.atLeastOnce())
					.schedule(scheduledTask.capture(), Mockito.anyLong(), Mockito.any(TimeUnit.class));
				scheduledTask.getAllValues().forEach(Runnable::run);

				final List<File> afterGuard = listCatalogDataFiles();
				assertEquals(
					1, afterGuard.size(),
					"an unlimited budget is no reason to keep unreachable files, got " + afterGuard.size() + " files"
				);
				assertEquals(
					beforeGuard.get(beforeGuard.size() - 1).getName(),
					afterGuard.get(0).getName()
				);
			}
		}

		@Test
		@DisplayName("should keep exactly as much history as the budget affords")
		void shouldKeepAsMuchHistoryAsTheBudgetAffords() {
			// the sibling tests pin only the extremes - a zero budget and an unlimited one. This is the mode an
			// operator actually runs in, and the only one where the horizon search has to land on a record that is
			// neither the oldest nor the newest.
			//
			// The budget has to be derived from a real catalog's history size, and a service cannot be reopened on a
			// non-empty folder, so the measurement runs against a second catalog written by the identical sequence.
			final String measuredCatalog = TEST_CATALOG + "Measured";
			final long fullHistory;
			try (
				final DefaultCatalogPersistenceService measuringService = new DefaultCatalogPersistenceService(
					measuredCatalog,
					timeTravelStorageOptions(Long.MAX_VALUE),
					eagerCheckpointTransactionOptions(),
					Mockito.mock(Scheduler.class),
					Mockito.mock(ExportFileService.class)
				)
			) {
				writeSeveralGenerations(measuringService);
				assertTrue(listCatalogDataFiles(measuredCatalog).size() > 3);
				fullHistory = measuringService.computeRetainedHistoryBytes();
				assertTrue(fullHistory > 0L);
			}

			// a budget that can hold part of the history but not all of it
			final long budget = fullHistory / 2L;
			assertTrue(budget > 0L, "the catalog must be big enough for half its history to be a real budget");

			try (
				final DefaultCatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
					TEST_CATALOG,
					timeTravelStorageOptions(budget),
					eagerCheckpointTransactionOptions(),
					Mockito.mock(Scheduler.class),
					Mockito.mock(ExportFileService.class)
				)
			) {
				writeSeveralGenerations(ioService);
				final List<File> beforeGuard = listCatalogDataFiles();
				assertTrue(beforeGuard.size() > 3, "expected several generations, got " + beforeGuard.size());

				ioService.enforceTimeTravelSizeLimit();

				final long retained = ioService.computeRetainedHistoryBytes();
				assertTrue(retained <= budget, "history must fit the budget, was " + retained + " > " + budget);
				// and it must not have thrown the baby out - some history has to survive a budget that affords it
				assertTrue(retained > 0L, "a budget holding several generations must keep some history");

				final List<File> afterGuard = listCatalogDataFiles();
				assertTrue(
					afterGuard.size() > 1 && afterGuard.size() < beforeGuard.size(),
					"expected a horizon strictly between the extremes, kept " + afterGuard.size() +
						" of " + beforeGuard.size() + " files"
				);
				// the newest generation always survives - reclaiming from the wrong end would destroy the catalog
				assertEquals(
					beforeGuard.get(beforeGuard.size() - 1).getName(),
					afterGuard.get(afterGuard.size() - 1).getName()
				);
			}
		}

		@Test
		@DisplayName("should defer giving up history that a pinned catalog version still needs")
		void shouldDeferWhenAPinnedVersionStillNeedsTheHistory() {
			try (
				final DefaultCatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
					TEST_CATALOG,
					timeTravelStorageOptions(0L),
					eagerCheckpointTransactionOptions(),
					Mockito.mock(Scheduler.class),
					Mockito.mock(ExportFileService.class)
				)
			) {
				writeSeveralGenerations(ioService);
				final List<File> beforeGuard = listCatalogDataFiles();
				assertTrue(beforeGuard.size() > 2);

				// a point-in-time consumer starts reading the oldest retained version - the budget must yield to it
				ioService.catalogVersionPinned(1L);
				ioService.enforceTimeTravelSizeLimit();

				// the reachable history survives. The file count may still drop by the files no bootstrap record can
				// reach at all - a pin cannot make an unreachable file reachable, and a consumer holding a version
				// reads through the record that serves it, never through the folder. The consumers that *do* read the
				// folder hold it directly instead, which is what keeps this sweep from being gated on pins
				assertTrue(
					listCatalogDataFiles().size() > 1,
					"a zero budget must still not pull reachable data out from under a pinned version"
				);
				assertTrue(
					ioService.computeRetainedHistoryBytes() > 0L,
					"history pinned by a live consumer must not be given up"
				);

				// once the consumer is done the very same budget reclaims everything it deferred
				ioService.catalogVersionReleased(1L);
				ioService.enforceTimeTravelSizeLimit();

				assertEquals(
					1, listCatalogDataFiles().size(),
					"the deferred reclamation must happen once the pin is released"
				);
			}
		}

		@Test
		@DisplayName("should schedule the guard when a deferred checkpoint finally publishes its bootstrap record")
		void shouldScheduleTheGuardWhenADeferredCheckpointPublishes() {
			// the only test in this class that runs with a checkpoint coordinator. Its siblings set the interval to 0,
			// which leaves the coordinator null so retirement and publication happen in the same breath - and that is
			// exactly why scheduling the guard on retirement alone looked sufficient for as long as it did
			final Scheduler scheduler = Mockito.mock(Scheduler.class);
			final ArgumentCaptor<Runnable> scheduledTask = ArgumentCaptor.forClass(Runnable.class);
			try (
				final DefaultCatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
					TEST_CATALOG,
					timeTravelStorageOptions(0L),
					deferredCheckpointTransactionOptions(),
					scheduler,
					Mockito.mock(ExportFileService.class)
				)
			) {
				writeSeveralGenerations(ioService);

				// The guard scheduled by `retireDataFile` runs here, before any of those records were published. It can
				// see no history at all: the newest *published* record still pins the retired data file, so the guard
				// counts that file as part of the active data set rather than as a generation it could give up.
				//
				// It has to run through the task rather than by a direct call, because what comes next depends on the
				// task's own bookkeeping: `DelayedAsyncTask` coalesces a `schedule()` into an execution that is already
				// pending, so unless this run actually happens the publication below would find the guard still armed
				// from retirement and schedule nothing - which is indistinguishable from the bug under test.
				//
				// Only the guard's own task may run here. The checkpoint ticker shares this scheduler and calls
				// `checkpointIfOwed()`, which would publish the very record this step must leave unpublished. They are
				// told apart by their delay: the guard is a zero-delay task, the ticker carries the checkpoint interval
				Mockito.verify(scheduler, Mockito.atLeastOnce())
					.schedule(scheduledTask.capture(), Mockito.eq(0L), Mockito.any(TimeUnit.class));
				scheduledTask.getAllValues().forEach(Runnable::run);

				final List<File> beforePublish = listCatalogDataFiles();
				assertEquals(
					0L, ioService.computeRetainedHistoryBytes(),
					"a deferred round has published nothing, so the guard must not see any history yet"
				);
				assertTrue(
					beforePublish.size() > 1,
					"the retired data files must still be on disk, uncounted, got " + beforePublish.size()
				);

				// from here on every scheduling is caused by the publication alone - publishing settles the debt, so
				// the ticker is not re-armed and the guard is the only task left that can reach this scheduler
				Mockito.clearInvocations(scheduler);
				final ArgumentCaptor<Runnable> afterPublish = ArgumentCaptor.forClass(Runnable.class);
				ioService.checkpoint();

				assertTrue(
					ioService.computeRetainedHistoryBytes() > 0L,
					"publishing the deferred record must make the retired generation visible as history"
				);

				// publishing is what turns the retired generation into history, and it is the only moment that can
				// notice - the compaction that retired the file is long past and will not come round again
				Mockito.verify(scheduler, Mockito.atLeastOnce())
					.schedule(afterPublish.capture(), Mockito.anyLong(), Mockito.any(TimeUnit.class));
				afterPublish.getAllValues().forEach(Runnable::run);

				assertTrue(
					listCatalogDataFiles().size() < beforePublish.size(),
					"a zero budget must reclaim the history the publication just revealed"
				);
			}
		}

		/**
		 * Builds an export service whose handle writes the archive into memory, so a backup task can be run all the
		 * way to completion without touching the export storage.
		 *
		 * @return the export service
		 */
		@Nonnull
		private ExportFileService inMemoryExportService() {
			final UUID fileId = UUIDUtil.randomUUID();
			final ExportFileHandleLocal exportFileHandle = new ExportFileHandleLocal(
				fileId,
				CompletableFuture.completedFuture(
					new FileForFetch(
						fileId, "backup.zip", null, "application/zip", 0L, OffsetDateTime.now(), null
					)
				),
				getTestDirectory().resolve(DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST).resolve("backup.zip"),
				new ByteArrayOutputStream()
			);
			final ExportFileService exportFileService = Mockito.mock(ExportFileService.class);
			Mockito.when(
				exportFileService.storeFile(
					Mockito.anyString(), Mockito.any(), Mockito.anyString(), Mockito.any()
				)
			).thenReturn(exportFileHandle);
			return exportFileService;
		}

		@Test
		@DisplayName("should retry a write-ahead log horizon that a pin refused, once the pin is gone")
		void shouldRetryAHorizonRequestThatAPinRefused() {
			try (
				final DefaultCatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
					TEST_CATALOG,
					// unlimited budget, so nothing but the request under test can move the horizon
					timeTravelStorageOptions(-1L),
					eagerCheckpointTransactionOptions(),
					Mockito.mock(Scheduler.class),
					Mockito.mock(ExportFileService.class)
				)
			) {
				writeSeveralGenerations(ioService);
				assertTrue(listCatalogDataFiles().size() > 3);

				// the horizon is first moved on its own, so that the pin taken below sits *under* it. That is what
				// makes the next request refused outright rather than merely clamped: a clamp landing above the
				// horizon still does useful work, a clamp landing at or below it returns without doing anything
				ioService.advanceHistoryHorizon(4L);
				final List<File> afterFirstAdvance = listCatalogDataFiles();
				assertTrue(
					afterFirstAdvance.size() > 1,
					"the fixture must leave room for a second advance, got " + afterFirstAdvance.size()
				);

				// now a consumer pins a version below the horizon and write-ahead log rotation reports a much newer
				// floor. The log files that floor was derived from are already deleted by the time it is reported and
				// the rotation forgets them, so a request dropped here is never made again by anyone
				ioService.catalogVersionPinned(1L);
				ioService.advanceHistoryHorizon(ioService.getLastCatalogVersion());
				assertEquals(
					afterFirstAdvance.stream().map(File::getName).toList(),
					listCatalogDataFiles().stream().map(File::getName).toList(),
					"the pin must hold the request off entirely"
				);

				// releasing the pin has to make good on the request that was refused, not merely let the next one
				// through - there is no next one
				ioService.catalogVersionReleased(1L);

				assertTrue(
					listCatalogDataFiles().size() < afterFirstAdvance.size(),
					"the horizon the pin refused must be retried once the pin is released"
				);
			}
		}

		@Test
		@DisplayName("should retry the remainder of a horizon a pin only partly allowed")
		void shouldRetryTheRemainderOfAPartiallyClampedHorizonRequest() {
			try (
				final DefaultCatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
					TEST_CATALOG,
					// unlimited budget, so nothing but the request under test can move the horizon
					timeTravelStorageOptions(-1L),
					eagerCheckpointTransactionOptions(),
					Mockito.mock(Scheduler.class),
					Mockito.mock(ExportFileService.class)
				)
			) {
				writeSeveralGenerations(ioService);
				assertTrue(listCatalogDataFiles().size() > 3);

				// the horizon itself is the observable here rather than the file count, because a pinned version also
				// holds the unreachable-file sweep off - the trim and the sweep would otherwise be indistinguishable
				ioService.advanceHistoryHorizon(3L);
				final long afterFirstAdvance = ioService.getOldestRetainedCatalogVersion();

				// the pin sits *above* the horizon this time, which is what makes the clamp partial rather than total:
				// the request is lowered to the pin and still does real work, so the "nothing moved" branch that used
				// to be the only place a refusal was recorded is never reached
				ioService.catalogVersionPinned(5L);
				final long requestedHorizon = ioService.getLastCatalogVersion();
				ioService.advanceHistoryHorizon(requestedHorizon);

				final long afterPartialAdvance = ioService.getOldestRetainedCatalogVersion();
				assertTrue(
					afterPartialAdvance > afterFirstAdvance,
					"the clamped request must still advance the horizon as far as the pin allows, got " +
						afterPartialAdvance + " after " + afterFirstAdvance
				);
				assertTrue(
					afterPartialAdvance < requestedHorizon,
					"the pin must still be holding history back, otherwise there is no remainder to retry"
				);

				// the write-ahead log deleted the files behind that request before reporting it and has forgotten them,
				// so the part the pin refused is owed and nobody will ask for it again
				ioService.catalogVersionReleased(5L);

				assertTrue(
					ioService.getOldestRetainedCatalogVersion() > afterPartialAdvance,
					"the part of the request the pin refused must be retried once the pin is released"
				);
			}
		}

		@Test
		@DisplayName("should give the retained window back once the full backup has finished with it")
		void shouldReleaseTheRetainedWindowWhenTheFullBackupCompletes() {
			try (
				final DefaultCatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
					TEST_CATALOG,
					timeTravelStorageOptions(0L),
					eagerCheckpointTransactionOptions(),
					Mockito.mock(Scheduler.class),
					inMemoryExportService()
				)
			) {
				writeSeveralGenerations(ioService);

				final ServerTask<?, FileForFetch> backupTask = ioService.createFullBackupTask(
					ioService::catalogVersionPinned, ioService::catalogVersionReleased
				);
				assertTrue(
					ioService.getRetentionFloor() >= 0L,
					"the full backup must be holding the retained window while it runs"
				);

				// a freshly built task waits for a precondition until something queues it, and `execute()` silently
				// does nothing until then - the state assertion below is what keeps this test from passing on a
				// backup that never ran
				backupTask.transitionToIssued();
				backupTask.execute();
				assertEquals(
					TaskSimplifiedState.FINISHED, backupTask.getStatus().simplifiedState(),
					"the backup must have actually run, otherwise this asserts nothing about its tear-down"
				);

				// the pin is taken in the constructor and given back only by the task's own tear-down. Since a full
				// backup holds the *oldest* retained version, failing to release it does not merely delay one
				// reclamation - it freezes every reclamation the catalog would ever do, for the rest of its life
				assertEquals(
					-1L, ioService.getRetentionFloor(),
					"a finished full backup must give the retained window back"
				);

				// and the budget it was holding off now actually runs
				ioService.enforceTimeTravelSizeLimit();
				assertEquals(
					1, listCatalogDataFiles().size(),
					"the reclamation deferred for the backup must happen once the backup is done"
				);
			}
		}

		@Test
		@DisplayName("should hold the whole retained window while a full backup is copying it")
		void shouldHoldTheWholeRetainedWindowWhileAFullBackupRuns() {
			try (
				final DefaultCatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
					TEST_CATALOG,
					timeTravelStorageOptions(0L),
					eagerCheckpointTransactionOptions(),
					Mockito.mock(Scheduler.class),
					Mockito.mock(ExportFileService.class)
				)
			) {
				writeSeveralGenerations(ioService);
				assertTrue(listCatalogDataFiles().size() > 2);

				final AtomicLong pinnedVersion = new AtomicLong(-1L);
				ioService.createFullBackupTask(
					version -> {
						pinnedVersion.set(version);
						ioService.catalogVersionPinned(version);
					},
					ioService::catalogVersionReleased
				);

				// a full backup copies every file in the folder, historical ones included, so it has to hold the
				// oldest retained version - not the newest. A pin at the newest version clamps nothing, because the
				// retention floor is a minimum: every candidate horizon at or below it passes through untouched
				assertEquals(
					ioService.getOldestRetainedCatalogVersion(), pinnedVersion.get(),
					"a full backup must pin the oldest retained version, not the version it is nominally taken at"
				);
				assertTrue(
					pinnedVersion.get() < ioService.getLastCatalogVersion(),
					"the fixture must actually have history, otherwise this test cannot tell the two versions apart"
				);

				// with that pin held even a zero budget cannot pull the archive's contents out from under it
				ioService.enforceTimeTravelSizeLimit();
				assertTrue(
					listCatalogDataFiles().size() > 1,
					"history must not be reclaimed while a full backup is copying it"
				);
				assertTrue(
					ioService.computeRetainedHistoryBytes() > 0L,
					"the retained window must survive for the whole lifetime of the full backup"
				);
			}
		}

		@Test
		@DisplayName("should refuse a point-in-time backup whose history was reclaimed before it could pin it")
		void shouldRefuseAPointInTimeBackupOfAlreadyReclaimedHistory() {
			try (
				final DefaultCatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
					TEST_CATALOG,
					timeTravelStorageOptions(0L),
					eagerCheckpointTransactionOptions(),
					Mockito.mock(Scheduler.class),
					Mockito.mock(ExportFileService.class)
				)
			) {
				writeSeveralGenerations(ioService);

				// the record a point-in-time backup would have resolved just before the budget ran
				final CatalogBootstrap staleRecord = getFirstCatalogBootstrap(
					TEST_CATALOG,
					new StorageSettings(
						timeTravelStorageOptions(0L), eagerCheckpointTransactionOptions()
					).modifyForBootstrapFile()
				).orElseThrow();

				// the budget gives that history up in the window between resolving the record and pinning it
				ioService.enforceTimeTravelSizeLimit();
				assertTrue(ioService.getOldestRetainedCatalogVersion() > staleRecord.catalogVersion());

				// the task must notice it lost the race rather than copy files that are no longer there
				assertThrows(
					TemporalDataNotAvailableException.class,
					() -> new BackupTask(
						TEST_CATALOG, null, staleRecord.catalogVersion(), false,
						staleRecord, Mockito.mock(ExportFileService.class), ioService,
						ioService::catalogVersionPinned, ioService::catalogVersionReleased
					)
				);

				// and the pin it took while checking must not be left behind, or the catalog keeps its history forever
				assertEquals(
					-1L, ioService.getRetentionFloor(),
					"the pin taken for the rejected backup must be released again"
				);
			}
		}

		@Test
		@DisplayName("should keep every reachable generation when the history already fits the budget")
		void shouldKeepEveryReachableGenerationWhenHistoryFitsTheBudget() {
			try (
				final DefaultCatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
					TEST_CATALOG,
					timeTravelStorageOptions(Long.MAX_VALUE),
					eagerCheckpointTransactionOptions(),
					Mockito.mock(Scheduler.class),
					Mockito.mock(ExportFileService.class)
				)
			) {
				writeSeveralGenerations(ioService);

				assertTrue(listCatalogDataFiles().size() > 2);
				final long historyBefore = ioService.computeRetainedHistoryBytes();
				assertTrue(historyBefore > 0L);

				ioService.enforceTimeTravelSizeLimit();

				// the horizon must not have moved: the reachable history is exactly what it was. The file list may
				// still shrink, because the guard also sweeps files no retained record can reach - those were never
				// history and no budget, however generous, is a reason to keep them.
				final List<File> afterGuard = listCatalogDataFiles();
				assertTrue(afterGuard.size() > 1, "the reachable history must survive a budget that fits it");
				assertEquals(historyBefore, ioService.computeRetainedHistoryBytes());

				// and it is idempotent - a second run has nothing left to reclaim
				ioService.enforceTimeTravelSizeLimit();
				assertEquals(
					afterGuard.stream().map(File::getName).toList(),
					listCatalogDataFiles().stream().map(File::getName).toList()
				);
			}
		}
	}

	@Nested
	@DisplayName("History horizon clamp")
	class HistoryHorizonClampTest {

		@Test
		@DisplayName("should keep serving a pinned version after log rotation reports a newer floor")
		void shouldClampTheHorizonToTheRetentionFloorWithTimeTravelDisabled() {
			try (
				final DefaultCatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
					TEST_CATALOG,
					// time travel OFF - the default configuration, and the one where nothing used to clamp the seam
					eagerCompactionStorageOptions(false, StorageOptions.DEFAULT_TIME_TRAVEL_SIZE_LIMIT_BYTES),
					eagerCheckpointTransactionOptions(),
					Mockito.mock(Scheduler.class),
					Mockito.mock(ExportFileService.class)
				)
			) {
				writeSeveralGenerations(ioService);

				// precondition - the rounds really did register a generation of their own, otherwise there is nothing
				// below the floor for the trim to close and the assertion at the end would hold vacuously
				final long pinnedVersion = 2L;
				final CatalogOffsetIndexStoragePartPersistenceService servingThePin =
					ioService.getStoragePartPersistenceService(pinnedVersion);
				assertNotSame(
					servingThePin, ioService.getStoragePartPersistenceService(ioService.getLastCatalogVersion()),
					"the fixture must register more than one generation, otherwise nothing can be closed under a reader"
				);

				// a live session reads at a version well behind the head. Every session pins the version it reads -
				// that pin is the only thing standing between this reader and the trim below
				ioService.catalogVersionPinned(pinnedVersion);

				// write-ahead log rotation reports a floor far above the pin. Rotation deletes its log files *before*
				// reporting the floor they imply, so this is the only moment the floor can still be honoured
				ioService.advanceHistoryHorizon(ioService.getLastCatalogVersion());

				// the session must still resolve to the generation that serves it. Unclamped, the trim closes every
				// service below the floor and this resolution either fails outright or lands on a different generation
				assertSame(
					servingThePin, ioService.getStoragePartPersistenceService(pinnedVersion),
					"a pinned version must keep the persistence service that serves it"
				);
				// and the service must still be usable - being registered is not the same as being open
				assertNotNull(
					ioService.getStoragePartPersistenceService(pinnedVersion).getCatalogHeader(pinnedVersion),
					"the persistence service serving a pinned version must still be readable"
				);
			}
		}
	}

	/**
	 * Retrieves the last catalog bootstrap for a given catalog. If the last bootstrap record was not fully written,
	 * the previous one is returned instead. The correctness is verified by fixed length of the bootstrap record and
	 * CRC32C checksum of the record.
	 *
	 * @param catalogName     The name of the catalog.
	 * @param storageSettings The storage options for reading the bootstrap file.
	 * @return The last catalog bootstrap.
	 * @throws UnexpectedIOException If there is an error opening the catalog bootstrap file.
	 * @throws BootstrapFileNotFound If the catalog bootstrap file is not found.
	 */
	@Nonnull
	static CatalogBootstrap getLastCatalogBootstrap(
		@Nonnull String catalogName,
		@Nonnull StorageSettings storageSettings
	) {
		final String bootstrapFileName = getCatalogBootstrapFileName(catalogName);
		final Path catalogStoragePath = storageSettings.storageDirectory().resolve(catalogName);
		final Path bootstrapFilePath = catalogStoragePath.resolve(bootstrapFileName);
		final File bootstrapFile = bootstrapFilePath.toFile();
		if (bootstrapFile.exists()) {
			final long length = bootstrapFile.length();
			final long lastMeaningfulPosition = CatalogBootstrap.getLastMeaningfulPosition(length);
			return deserializeCatalogBootstrapRecord(storageSettings, bootstrapFilePath, lastMeaningfulPosition);
		} else {
			throw new BootstrapFileNotFound(catalogStoragePath, bootstrapFile);
		}
	}

}
