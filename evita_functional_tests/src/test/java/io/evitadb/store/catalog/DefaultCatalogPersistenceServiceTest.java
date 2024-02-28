/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.exception.EntityTypeAlreadyPresentInCatalogSchemaException;
import io.evitadb.api.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.system.CatalogVersion;
import io.evitadb.api.requestResponse.system.TimeFlow;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.api.trace.DefaultTracingContext;
import io.evitadb.core.Catalog;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.EvitaSession;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.sequence.SequenceService;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.InvalidClassifierFormatException;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.store.entity.model.schema.CatalogSchemaStoragePart;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.offsetIndex.exception.UnexpectedCatalogContentsException;
import io.evitadb.store.offsetIndex.io.OffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.ReadOnlyFileHandle;
import io.evitadb.store.offsetIndex.io.ReadOnlyHandle;
import io.evitadb.store.offsetIndex.io.WriteOnlyOffHeapWithFileBackupHandle;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
import io.evitadb.store.spi.exception.DirectoryNotEmptyException;
import io.evitadb.store.spi.model.CatalogHeader;
import io.evitadb.store.spi.model.EntityCollectionHeader;
import io.evitadb.store.spi.model.reference.CollectionFileReference;
import io.evitadb.store.wal.CatalogWriteAheadLog;
import io.evitadb.store.wal.WalKryoConfigurer;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.TestConstants;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.store.catalog.DefaultIsolatedWalServiceTest.DATA_MUTATION_EXAMPLE;
import static io.evitadb.store.catalog.DefaultIsolatedWalServiceTest.SCHEMA_MUTATION_EXAMPLE;
import static io.evitadb.test.Assertions.assertExactlyEquals;
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
class DefaultCatalogPersistenceServiceTest implements EvitaTestSupport {
	private static final String RENAMED_CATALOG = "somethingElse";
	public static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(TEST_CATALOG, NamingConvention.generate(TestConstants.TEST_CATALOG), EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE);
	private static final SealedCatalogSchema SEALED_CATALOG_SCHEMA = new CatalogSchemaDecorator(CATALOG_SCHEMA);
	public static final String DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST = "defaultCatalogPersistenceServiceTest";
	public static final String TX_DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST = "txDefaultCatalogPersistenceServiceTest";

	private final DataGenerator dataGenerator = new DataGenerator();
	private final SequenceService sequenceService = new SequenceService();

	private final UUID transactionId = UUID.randomUUID();
	private final Path walFile = getTestDirectory().resolve(transactionId.toString());
	private final Kryo kryo = KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
	private final ObservableOutputKeeper observableOutputKeeper = new ObservableOutputKeeper(
		StorageOptions.builder().build(),
		Mockito.mock(ScheduledExecutorService.class)
	);
	private final WriteOnlyOffHeapWithFileBackupHandle writeHandle = new WriteOnlyOffHeapWithFileBackupHandle(
		getTestDirectory().resolve(transactionId.toString()),
		observableOutputKeeper,
		new OffHeapMemoryManager(512, 1)
	);
	private final DefaultIsolatedWalService walService = new DefaultIsolatedWalService(
		transactionId,
		kryo,
		writeHandle
	);

	@BeforeEach
	public void setUp() throws IOException {
		final Path resolve = getTestDirectory().resolve(DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST);
		resolve.toFile().mkdirs();
	}

	@AfterEach
	void tearDown() throws IOException {
		walService.close();
		observableOutputKeeper.close();
		final File file = walFile.toFile();
		if (file.exists()) {
			fail("File " + file + " should not exist after close!");
		}
		cleanTestSubDirectory(DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST);
	}

	@Nonnull
	private StorageOptions getStorageOptions() {
		return new StorageOptions(
			getTestDirectory().resolve(DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST),
			60, 60,
			StorageOptions.DEFAULT_OUTPUT_BUFFER_SIZE, 1, true
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
			TransactionOptions.DEFAULT_MAX_QUEUE_SIZE,
			TransactionOptions.DEFAULT_FLUSH_FREQUENCY
		);
	}

	@Test
	void shouldSerializeAndDeserializeCatalogHeader() {
		final CatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
			SEALED_CATALOG_SCHEMA.getName(),
			getStorageOptions(),
			getTransactionOptions(),
			Mockito.mock(ScheduledExecutorService.class)
		);

		ioService.getStoragePartPersistenceService()
			.putStoragePart(0L, new CatalogSchemaStoragePart(CATALOG_SCHEMA));

		final EvitaSession mockSession = mock(EvitaSession.class);
		when(mockSession.getCatalogSchema()).thenReturn(SEALED_CATALOG_SCHEMA);

		final EntityCollection brandCollection = constructEntityCollectionWithSomeEntities(
			ioService, SEALED_CATALOG_SCHEMA, dataGenerator.getSampleBrandSchema(mockSession, EntitySchemaBuilder::toInstance), 1
		);
		final EntityCollection storeCollection = constructEntityCollectionWithSomeEntities(
			ioService, SEALED_CATALOG_SCHEMA, dataGenerator.getSampleStoreSchema(mockSession, EntitySchemaBuilder::toInstance), 2
		);
		final EntityCollection productCollection = constructEntityCollectionWithSomeEntities(
			ioService, SEALED_CATALOG_SCHEMA, dataGenerator.getSampleProductSchema(mockSession, EntitySchemaBuilder::toInstance), 3
		);

		final List<EntityCollectionHeader> entityHeaders = new ArrayList<>(3);
		entityHeaders.add(productCollection.flush());
		entityHeaders.add(brandCollection.flush());
		entityHeaders.add(storeCollection.flush());

		// try to serialize
		ioService.storeHeader(CatalogState.WARMING_UP, 0, 0, null, entityHeaders);

		// try to deserialize again
		final CatalogHeader catalogHeader = ioService.getCatalogHeader();

		assertNotNull(catalogHeader);
		final Map<String, CollectionFileReference> entityTypesIndex = catalogHeader.collectionFileIndex();
		assertEquals(3, entityTypesIndex.size());

		assertEntityCollectionsHaveIdenticalContent(ioService, SEALED_CATALOG_SCHEMA, brandCollection, ioService.getEntityCollectionHeader(entityTypesIndex.get(Entities.BRAND)));
		assertEntityCollectionsHaveIdenticalContent(ioService, SEALED_CATALOG_SCHEMA, storeCollection, ioService.getEntityCollectionHeader(entityTypesIndex.get(Entities.STORE)));
		assertEntityCollectionsHaveIdenticalContent(ioService, SEALED_CATALOG_SCHEMA, productCollection, ioService.getEntityCollectionHeader(entityTypesIndex.get(Entities.PRODUCT)));
	}

	@Test
	void shouldDetectInvalidCatalogContents() {
		final CatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
			SEALED_CATALOG_SCHEMA.getName(),
			getStorageOptions(),
			getTransactionOptions(),
			Mockito.mock(ScheduledExecutorService.class)
		);

		ioService.getStoragePartPersistenceService()
			.putStoragePart(0L, new CatalogSchemaStoragePart(CATALOG_SCHEMA));

		final EvitaSession mockSession = mock(EvitaSession.class);
		when(mockSession.getCatalogSchema()).thenReturn(SEALED_CATALOG_SCHEMA);

		final EntityCollection productCollection = constructEntityCollectionWithSomeEntities(
			ioService, SEALED_CATALOG_SCHEMA, dataGenerator.getSampleProductSchema(mockSession, EntitySchemaBuilder::toInstance), 1
		);
		final EntityCollection brandCollection = constructEntityCollectionWithSomeEntities(
			ioService, SEALED_CATALOG_SCHEMA, dataGenerator.getSampleBrandSchema(mockSession, EntitySchemaBuilder::toInstance), 2
		);
		final EntityCollection storeCollection = constructEntityCollectionWithSomeEntities(
			ioService, SEALED_CATALOG_SCHEMA, dataGenerator.getSampleStoreSchema(mockSession, EntitySchemaBuilder::toInstance), 3
		);

		// try to serialize
		ioService.storeHeader(
			CatalogState.WARMING_UP,
			0, 0, null,
			Arrays.asList(
				productCollection.flush(),
				brandCollection.flush(),
				storeCollection.flush()
			)
		);

		assertThrows(
			UnexpectedCatalogContentsException.class,
			() -> {
				final Path dataDirectory = getTestDirectory().resolve(DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST);
				final Path catalogPath = dataDirectory.resolve(TEST_CATALOG);
				final Path renamedCatalogPath = dataDirectory.resolve(RENAMED_CATALOG);
				assertTrue(catalogPath.resolve(CatalogPersistenceService.getCatalogBootstrapFileName(TEST_CATALOG)).toFile()
					.renameTo(catalogPath.resolve(CatalogPersistenceService.getCatalogBootstrapFileName(RENAMED_CATALOG)).toFile()));
				assertTrue(catalogPath.resolve(CatalogPersistenceService.getCatalogDataStoreFileName(TEST_CATALOG, 0)).toFile()
					.renameTo(catalogPath.resolve(CatalogPersistenceService.getCatalogDataStoreFileName(RENAMED_CATALOG, 0)).toFile()));
				assertTrue(catalogPath.toFile().renameTo(renamedCatalogPath.toFile()));
				//noinspection EmptyTryBlock
				try (var ignored = new DefaultCatalogPersistenceService(
					Mockito.mock(CatalogContract.class),
					RENAMED_CATALOG,
					renamedCatalogPath,
					getStorageOptions(),
					getTransactionOptions(),
					Mockito.mock(ScheduledExecutorService.class)
				)) {
					// do nothing
				}
			}
		);
	}

	@Test
	void shouldSignalizeInvalidEntityNames() {
		assertThrows(
			InvalidClassifierFormatException.class,
			() -> {
				try (var cps = new DefaultCatalogPersistenceService(
					SEALED_CATALOG_SCHEMA.getName(),
					getStorageOptions(),
					getTransactionOptions(),
					Mockito.mock(ScheduledExecutorService.class)
				)) {
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
				try (var cps = new DefaultCatalogPersistenceService(
					SEALED_CATALOG_SCHEMA.getName(),
					getStorageOptions(),
					getTransactionOptions(),
					Mockito.mock(ScheduledExecutorService.class)
				)) {
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
		try (var ignored1 = new DefaultCatalogPersistenceService(
			SEALED_CATALOG_SCHEMA.getName(),
			getStorageOptions(),
			getTransactionOptions(),
			Mockito.mock(ScheduledExecutorService.class)
		)) {
		}

		assertThrows(
			DirectoryNotEmptyException.class,
			() -> {
				//noinspection EmptyTryBlock
				try (var ignored2 = new DefaultCatalogPersistenceService(
					CATALOG_SCHEMA.getName(),
					getStorageOptions(),
					getTransactionOptions(),
					Mockito.mock(ScheduledExecutorService.class)
				)) {
				}
			}
		);
	}

	@Test
	void shouldDeleteCatalog() throws IOException {
		shouldSerializeAndDeserializeCatalogHeader();

		final Path catalogDirectory = getStorageOptions().storageDirectoryOrDefault().resolve(TEST_CATALOG);
		try (var cps = new DefaultCatalogPersistenceService(
			Mockito.mock(CatalogContract.class),
			TEST_CATALOG,
			catalogDirectory,
			getStorageOptions(),
			getTransactionOptions(),
			Mockito.mock(ScheduledExecutorService.class)
		)) {
			assertTrue(catalogDirectory.toFile().exists());
			assertEquals(5, countFiles(catalogDirectory));
			cps.delete();
			assertFalse(catalogDirectory.toFile().exists());
		}
	}

	@Test
	void shouldReturnDefaultHeaderOnEmptyDirectory() {
		try (var cps = new DefaultCatalogPersistenceService(
			SEALED_CATALOG_SCHEMA.getName(),
			getStorageOptions(),
			getTransactionOptions(),
			Mockito.mock(ScheduledExecutorService.class)
		)) {
			final CatalogHeader header = cps.getCatalogHeader();
			assertNotNull(header);
			assertEquals(CatalogState.WARMING_UP, header.catalogState());
			assertEquals(TEST_CATALOG, header.catalogName());
			assertEquals(0L, header.version());
		}
	}

	@Test
	void shouldAppendWalFromByteBufferAndReadItAgain() {
		walService.write(1L, DATA_MUTATION_EXAMPLE);
		walService.write(1L, SCHEMA_MUTATION_EXAMPLE);

		final OffHeapWithFileBackupReference walReference = walService.getWalReference();
		final String catalogName = SEALED_CATALOG_SCHEMA.getName();

		// first switch to the transactional mode
		try (var cps = new DefaultCatalogPersistenceService(
			catalogName,
			getStorageOptions(),
			getTransactionOptions(),
			Mockito.mock(ScheduledExecutorService.class)
		)) {
			cps.getStoragePartPersistenceService()
				.putStoragePart(0L, new CatalogSchemaStoragePart(CATALOG_SCHEMA));
			cps.storeHeader(
				CatalogState.ALIVE,
				2L,
				0,
				null,
				Collections.emptyList()
			);
		}

		final TransactionMutation writtenTransactionMutation = new TransactionMutation(
			transactionId, 1L, 2, walReference.getContentLength(), OffsetDateTime.MIN
		);

		// and then write to the WAL
		try (var cps = new DefaultCatalogPersistenceService(
			Mockito.mock(CatalogContract.class),
			catalogName,
			getStorageOptions().storageDirectory().resolve(catalogName),
			getStorageOptions(),
			getTransactionOptions(),
			Mockito.mock(ScheduledExecutorService.class)
		)) {
			cps.appendWalAndDiscard(
				writtenTransactionMutation,
				walReference
			);
		}

		// READ THE WAL AGAIN
		final Path walFile = getStorageOptions().storageDirectory()
			.resolve(catalogName)
			.resolve(CatalogPersistenceService.getWalFileName(catalogName, 0));

		final ReadOnlyHandle readOnlyHandle = new ReadOnlyFileHandle(walFile, true);
		readOnlyHandle.execute(
			input -> {
				final int transactionSize = input.readInt();
				// the 2 bytes are required to record the classId
				final int offsetDateTimeDelta = 11;
				assertEquals(walReference.getContentLength() + CatalogWriteAheadLog.TRANSACTION_MUTATION_SIZE - offsetDateTimeDelta + 2, transactionSize);
				final Mutation loadedTransactionMutation = (Mutation) StorageRecord.read(input, (stream, length) -> kryo.readClassAndObject(stream)).payload();
				assertEquals(writtenTransactionMutation, loadedTransactionMutation);
				final Mutation firstMutation = (Mutation) StorageRecord.read(input, (stream, length) -> kryo.readClassAndObject(stream)).payload();
				assertEquals(DATA_MUTATION_EXAMPLE, firstMutation);
				final Mutation secondMutation = (Mutation) StorageRecord.read(input, (stream, length) -> kryo.readClassAndObject(stream)).payload();
				assertEquals(SCHEMA_MUTATION_EXAMPLE, secondMutation);
				return null;
			}
		);
	}

	@Test
	void shouldTraverseBootstrapRecordsFromOldestToNewest() {
		final String catalogName = SEALED_CATALOG_SCHEMA.getName();
		final DefaultCatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
			catalogName,
			getStorageOptions(),
			getTransactionOptions(),
			Mockito.mock(ScheduledExecutorService.class)
		);

		for (int i = 0; i < 12; i++) {
			ioService.recordBootstrap(i + 1, catalogName, 0);
		}

		final PaginatedList<CatalogVersion> catalogVersions = ioService.getCatalogVersions(TimeFlow.FROM_OLDEST_TO_NEWEST, 1, 5);
		assertEquals(5, catalogVersions.getData().size());
		assertEquals(13, catalogVersions.getTotalRecordCount());
		for (int i = 0; i <= 4; i++) {
			final CatalogVersion record = catalogVersions.getData().get(i);
			assertEquals(i, record.version());
			assertNotNull(record.timestamp());
		}

		final PaginatedList<CatalogVersion> catalogVersionsLastPage = ioService.getCatalogVersions(TimeFlow.FROM_OLDEST_TO_NEWEST, 3, 5);
		assertEquals(3, catalogVersionsLastPage.getData().size());
		for (int i = 0; i < 3; i++) {
			final CatalogVersion record = catalogVersionsLastPage.getData().get(i);
			assertEquals(10 + i, record.version());
			assertNotNull(record.timestamp());
		}
	}

	@Test
	void shouldTraverseBootstrapRecordsFromNewestToOldest() {
		final String catalogName = SEALED_CATALOG_SCHEMA.getName();
		final DefaultCatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
			catalogName,
			getStorageOptions(),
			getTransactionOptions(),
			Mockito.mock(ScheduledExecutorService.class)
		);

		for (int i = 0; i < 12; i++) {
			ioService.recordBootstrap(i + 1, catalogName, 0);
		}

		final PaginatedList<CatalogVersion> catalogVersions = ioService.getCatalogVersions(TimeFlow.FROM_NEWEST_TO_OLDEST, 1, 5);
		assertEquals(5, catalogVersions.getData().size());
		assertEquals(13, catalogVersions.getTotalRecordCount());
		for (int i = 0; i < 5; i++) {
			final CatalogVersion record = catalogVersions.getData().get(i);
			assertEquals(13 - (i + 1), record.version());
			assertNotNull(record.timestamp());
		}

		final PaginatedList<CatalogVersion> catalogVersionsLastPage = ioService.getCatalogVersions(TimeFlow.FROM_NEWEST_TO_OLDEST, 3, 5);
		assertEquals(3, catalogVersionsLastPage.getData().size());
		for (int i = 0; i < 3; i++) {
			final CatalogVersion record = catalogVersionsLastPage.getData().get(i);
			assertEquals(3 - (i + 1), record.version());
			assertNotNull(record.timestamp());
		}
	}

	/*
		PRIVATE METHODS
	 */

	private static int countFiles(@Nonnull Path catalogDirectory) throws IOException {
		try (var paths = Files.list(catalogDirectory)) {
			return (int) paths.count();
		}
	}

	@Nonnull
	private EntityCollection constructEntityCollectionWithSomeEntities(@Nonnull CatalogPersistenceService ioService, @Nonnull SealedCatalogSchema catalogSchema, @Nonnull SealedEntitySchema entitySchema, int entityTypePrimaryKey) {
		final Catalog mockCatalog = getMockCatalog(catalogSchema, entitySchema);
		final CatalogSchemaContract catalogSchemaContract = Mockito.mock(CatalogSchemaContract.class);
		final EntityCollection entityCollection = new EntityCollection(
			mockCatalog,
			entityTypePrimaryKey,
			entitySchema.getName(),
			ioService,
			NoCacheSupervisor.INSTANCE,
			sequenceService,
			DefaultTracingContext.INSTANCE
		);


		final ArgumentCaptor<Mutation> mutationCaptor = ArgumentCaptor.forClass(Mutation.class);

		// Use the captor when defining the mock behavior
		Mockito.doAnswer(invocation -> {
			if (mutationCaptor.getValue() instanceof ModifyEntitySchemaMutation modifyEntitySchemaMutation) {
				return entityCollection.updateSchema(catalogSchemaContract, modifyEntitySchemaMutation.getSchemaMutations());
			} else {
				return null;
			}
		}).when(mockCatalog).applyMutation(mutationCaptor.capture());

		dataGenerator.generateEntities(
				entitySchema,
				(serializable, faker) -> null,
				40
			)
			.limit(10)
			.forEach(it -> it.toMutation().ifPresent(entityCollection::upsertEntity));

		return entityCollection;
	}

	private void assertEntityCollectionsHaveIdenticalContent(
		@Nonnull CatalogPersistenceService ioService,
		@Nonnull SealedCatalogSchema catalogSchema,
		@Nonnull EntityCollection entityCollection,
		@Nonnull EntityCollectionHeader collectionHeader
	) {
		assertEquals(entityCollection.size(), collectionHeader.recordCount());
		final ObservableOutputKeeper outputKeeper = new ObservableOutputKeeper(
			getStorageOptions(),
			Mockito.mock(ScheduledExecutorService.class)
		);

		final SealedEntitySchema schema = entityCollection.getSchema();
		final EntityCollection collection = new EntityCollection(
			getMockCatalog(catalogSchema, schema),
			entityCollection.getEntityTypePrimaryKey(),
			schema.getName(),
			ioService,
			NoCacheSupervisor.INSTANCE,
			sequenceService,
			DefaultTracingContext.INSTANCE
		);

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
				null,
				EvitaRequest.CONVERSION_NOT_SUPPORTED
			);
			final SealedEntity deserializedEntity = collection.getEntity(primaryKey, request, mockSession).orElseThrow();
			final SealedEntity originEntity = entityCollection.getEntity(primaryKey, request, mockSession).orElseThrow();
			assertExactlyEquals(originEntity, deserializedEntity);
		}

		outputKeeper.close();
	}

	@Nonnull
	private static Catalog getMockCatalog(SealedCatalogSchema catalogSchema, @Nonnull SealedEntitySchema schema) {
		final Catalog mockCatalog = mock(Catalog.class);
		when(mockCatalog.getSchema()).thenReturn(catalogSchema);
		when(mockCatalog.getEntitySchema(schema.getName())).thenReturn(of(schema));
		when(mockCatalog.getEntityIndexIfExists(Mockito.eq(schema.getName()), any(EntityIndexKey.class))).thenReturn(null);
		return mockCatalog;
	}

}
