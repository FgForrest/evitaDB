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
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.exception.EntityTypeAlreadyPresentInCatalogSchemaException;
import io.evitadb.api.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.observability.trace.DefaultTracingContext;
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
import io.evitadb.core.Catalog;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.EvitaSession;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.metric.event.storage.FileType;
import io.evitadb.core.sequence.SequenceService;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.InvalidClassifierFormatException;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.scheduling.Scheduler;
import io.evitadb.store.entity.model.schema.CatalogSchemaStoragePart;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.offsetIndex.OffsetIndex;
import io.evitadb.store.offsetIndex.exception.UnexpectedCatalogContentsException;
import io.evitadb.store.offsetIndex.io.OffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.ReadOnlyFileHandle;
import io.evitadb.store.offsetIndex.io.ReadOnlyHandle;
import io.evitadb.store.offsetIndex.io.WriteOnlyFileHandle;
import io.evitadb.store.offsetIndex.io.WriteOnlyOffHeapWithFileBackupHandle;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
import io.evitadb.store.spi.exception.DirectoryNotEmptyException;
import io.evitadb.store.spi.model.CatalogHeader;
import io.evitadb.store.spi.model.EntityCollectionHeader;
import io.evitadb.store.spi.model.reference.CollectionFileReference;
import io.evitadb.store.spi.model.reference.WalFileReference;
import io.evitadb.store.wal.CatalogWriteAheadLog;
import io.evitadb.store.wal.WalKryoConfigurer;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.TestConstants;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.store.catalog.CatalogOffsetIndexStoragePartPersistenceService.loadOffsetIndexDescriptor;
import static io.evitadb.store.catalog.DefaultCatalogPersistenceService.VERSIONED_KRYO_FACTORY;
import static io.evitadb.store.catalog.DefaultIsolatedWalServiceTest.DATA_MUTATION_EXAMPLE;
import static io.evitadb.store.catalog.DefaultIsolatedWalServiceTest.SCHEMA_MUTATION_EXAMPLE;
import static io.evitadb.store.spi.CatalogPersistenceService.CATALOG_FILE_SUFFIX;
import static io.evitadb.store.spi.CatalogPersistenceService.getCatalogDataStoreFileName;
import static io.evitadb.store.spi.CatalogPersistenceService.getCatalogDataStoreFileNamePattern;
import static io.evitadb.test.Assertions.assertExactlyEquals;
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
class DefaultCatalogPersistenceServiceTest implements EvitaTestSupport {
	public static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(TEST_CATALOG, NamingConvention.generate(TestConstants.TEST_CATALOG), EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE);
	public static final String DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST = "defaultCatalogPersistenceServiceTest";
	public static final String TX_DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST = "txDefaultCatalogPersistenceServiceTest";
	private static final String RENAMED_CATALOG = "somethingElse";
	private static final SealedCatalogSchema SEALED_CATALOG_SCHEMA = new CatalogSchemaDecorator(CATALOG_SCHEMA);
	private final DataGenerator dataGenerator = new DataGenerator();
	private final SequenceService sequenceService = new SequenceService();

	private final UUID transactionId = UUID.randomUUID();
	private final Path walFile = getTestDirectory().resolve(transactionId.toString());
	private final Kryo kryo = KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
	private final ObservableOutputKeeper observableOutputKeeper = new ObservableOutputKeeper(
		TEST_CATALOG,
		StorageOptions.builder().build(),
		Mockito.mock(Scheduler.class)
	);
	private final WriteOnlyOffHeapWithFileBackupHandle writeHandle = new WriteOnlyOffHeapWithFileBackupHandle(
		getTestDirectory().resolve(transactionId.toString()),
		observableOutputKeeper,
		new OffHeapMemoryManager(TEST_CATALOG, 512, 1)
	);
	private final DefaultIsolatedWalService walService = new DefaultIsolatedWalService(
		transactionId,
		kryo,
		writeHandle
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

	@Disabled("This test is not meant to be run in CI, it is for manual post-mortem analysis of the catalog data file remnants.")
	@Test
	void postMortemAnalysis() {
		final String catalogName = "INSERT_HERE";
		final Path basePath = Path.of("/www/oss/evitaDB/data/");
		final Path catalogFilePath = basePath.resolve(catalogName);
		final OffsetIndexRecordTypeRegistry recordRegistry = new OffsetIndexRecordTypeRegistry();
		final StorageOptions storageOptions = StorageOptions.builder().storageDirectory(basePath).build();
		DefaultCatalogPersistenceService.getBootstrapRecordStream(
			catalogName,
			storageOptions
		).forEach(it -> {
			System.out.print(it.catalogFileIndex() + "/" + it.catalogVersion() + ": " + it.timestamp() + " (" + it.fileLocation() + ")");
			final AtomicReference<CatalogHeader> catalogHeaderRef = new AtomicReference<>();
			try {
				final OffsetIndex indexRead = new OffsetIndex(
					it.catalogVersion(),
					catalogFilePath.resolve(getCatalogDataStoreFileName(catalogName, it.catalogFileIndex())),
					it.fileLocation(),
					storageOptions,
					recordRegistry,
					new WriteOnlyFileHandle(
						catalogName,
						FileType.CATALOG,
						catalogName,
						catalogFilePath,
						observableOutputKeeper
					),
					null,
					null,
					(indexBuilder, theInput) -> loadOffsetIndexDescriptor(
						catalogFilePath, recordRegistry, VERSIONED_KRYO_FACTORY,
						catalogHeaderRef::set,
						indexBuilder, theInput, it.fileLocation()
					)
				);
				final WalFileReference walRef = catalogHeaderRef.get().walFileReference();
				if (walRef == null) {
					System.out.println(" -> OK, size " + indexRead.getEntries().size());
				} else {
					System.out.println(" -> OK " + walRef.fileIndex() + "/" + walRef.fileLocation() + ", size " + indexRead.getEntries().size());
				}
			} catch (Exception e) {
				System.out.println(" -> ERROR: " + e.getMessage());
			}
		});
	}

	@Test
	void shouldSerializeAndDeserializeCatalogHeader() {
		final CatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
			SEALED_CATALOG_SCHEMA.getName(),
			getStorageOptions(),
			getTransactionOptions(),
			Mockito.mock(Scheduler.class)
		);

		ioService.getStoragePartPersistenceService(0L)
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
		final CatalogHeader catalogHeader = ioService.getCatalogHeader(0L);

		assertNotNull(catalogHeader);
		final Map<String, CollectionFileReference> entityTypesIndex = catalogHeader.collectionFileIndex();
		assertEquals(3, entityTypesIndex.size());

		assertEntityCollectionsHaveIdenticalContent(ioService, SEALED_CATALOG_SCHEMA, brandCollection, ioService.getEntityCollectionHeader(0L, entityTypesIndex.get(Entities.BRAND).entityTypePrimaryKey()));
		assertEntityCollectionsHaveIdenticalContent(ioService, SEALED_CATALOG_SCHEMA, storeCollection, ioService.getEntityCollectionHeader(0L, entityTypesIndex.get(Entities.STORE).entityTypePrimaryKey()));
		assertEntityCollectionsHaveIdenticalContent(ioService, SEALED_CATALOG_SCHEMA, productCollection, ioService.getEntityCollectionHeader(0L, entityTypesIndex.get(Entities.PRODUCT).entityTypePrimaryKey()));
	}

	@Test
	void shouldDetectInvalidCatalogContents() {
		final DefaultCatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
			SEALED_CATALOG_SCHEMA.getName(),
			getStorageOptions(),
			getTransactionOptions(),
			Mockito.mock(Scheduler.class)
		);

		ioService.getStoragePartPersistenceService(0L)
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
			0L, 0, null,
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

				//noinspection EmptyTryBlock
				try (var ignored = new DefaultCatalogPersistenceService(
					Mockito.mock(CatalogContract.class),
					RENAMED_CATALOG,
					renamedCatalogPath,
					getStorageOptions(),
					getTransactionOptions(),
					Mockito.mock(Scheduler.class)
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
					Mockito.mock(Scheduler.class)
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
					Mockito.mock(Scheduler.class)
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
			Mockito.mock(Scheduler.class)
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
					Mockito.mock(Scheduler.class)
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
			Mockito.mock(Scheduler.class)
		)) {
			assertTrue(catalogDirectory.toFile().exists());
			assertTrue(countFiles(catalogDirectory) > 0);
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
			Mockito.mock(Scheduler.class)
		)) {
			final CatalogHeader header = cps.getCatalogHeader(0L);
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
			Mockito.mock(Scheduler.class)
		)) {
			cps.getStoragePartPersistenceService(0L)
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
			Mockito.mock(Scheduler.class)
		)) {
			cps.appendWalAndDiscard(
				2L,
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
			Mockito.mock(Scheduler.class)
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
			Mockito.mock(Scheduler.class)
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

	@Test
	void shouldTrimBootstrapRecords() {
		final String catalogName = SEALED_CATALOG_SCHEMA.getName();
		final DefaultCatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
			catalogName,
			getStorageOptions(),
			getTransactionOptions(),
			Mockito.mock(Scheduler.class)
		);

		final OffsetDateTime timestamp = OffsetDateTime.now();
		for (int i = 0; i < 12; i++) {
			ioService.recordBootstrap(
				i + 1, catalogName, 0,
				timestamp.plusMinutes(i).toInstant().toEpochMilli()
			);
		}

		final PaginatedList<CatalogVersion> catalogVersions0 = ioService.getCatalogVersions(TimeFlow.FROM_OLDEST_TO_NEWEST, 1, 20);
		assertEquals(0, catalogVersions0.getData().get(0).version());
		assertEquals(13, catalogVersions0.getTotalRecordCount());

		trimAndCheck(ioService, timestamp.plusMinutes(3).plusSeconds(1), 4, 9);
		trimAndCheck(ioService, timestamp.plusMinutes(6), 7, 6);
		trimAndCheck(ioService, timestamp.plusMinutes(8).minusSeconds(1), 8, 5);
	}

	private static void trimAndCheck(
		@Nonnull DefaultCatalogPersistenceService ioService,
		@Nonnull OffsetDateTime toTimestamp,
		int expectedVersion,
		int expectedCount
	) {
		ioService.trimBootstrapFile(toTimestamp);

		final PaginatedList<CatalogVersion> catalogVersions = ioService.getCatalogVersions(TimeFlow.FROM_OLDEST_TO_NEWEST, 1, 20);
		final CatalogVersion firstRecord = catalogVersions.getData().get(0);
		assertTrue(toTimestamp.isAfter(firstRecord.timestamp()));
		assertEquals(expectedVersion, firstRecord.version());
		assertEquals(expectedCount, catalogVersions.getTotalRecordCount());
	}

	/*
		PRIVATE METHODS
	 */

	@Nonnull
	private StorageOptions getStorageOptions() {
		return new StorageOptions(
			getTestDirectory().resolve(DIR_DEFAULT_CATALOG_PERSISTENCE_SERVICE_TEST),
			60, 60,
			StorageOptions.DEFAULT_OUTPUT_BUFFER_SIZE, 1,
			true, 1.0, 0L
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

	@Nonnull
	private EntityCollection constructEntityCollectionWithSomeEntities(@Nonnull CatalogPersistenceService ioService, @Nonnull SealedCatalogSchema catalogSchema, @Nonnull SealedEntitySchema entitySchema, int entityTypePrimaryKey) {
		final Catalog mockCatalog = getMockCatalog(catalogSchema, entitySchema);
		final CatalogSchemaContract catalogSchemaContract = Mockito.mock(CatalogSchemaContract.class);
		final EntityCollection entityCollection = new EntityCollection(
			catalogSchema.getName(),
			0L,
			entityTypePrimaryKey,
			entitySchema.getName(),
			ioService,
			NoCacheSupervisor.INSTANCE,
			sequenceService,
			DefaultTracingContext.INSTANCE
		);
		entityCollection.attachToCatalog(null, mockCatalog);

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
			TEST_CATALOG,
			getStorageOptions(),
			Mockito.mock(Scheduler.class)
		);

		final SealedEntitySchema schema = entityCollection.getSchema();
		final EntityCollection collection = new EntityCollection(
			catalogSchema.getName(),
			0L,
			entityCollection.getEntityTypePrimaryKey(),
			schema.getName(),
			ioService,
			NoCacheSupervisor.INSTANCE,
			sequenceService,
			DefaultTracingContext.INSTANCE
		);
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
				null,
				EvitaRequest.CONVERSION_NOT_SUPPORTED
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

}
