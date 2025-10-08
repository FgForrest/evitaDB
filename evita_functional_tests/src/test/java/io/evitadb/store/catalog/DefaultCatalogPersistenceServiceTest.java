/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TrafficRecordingOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.exception.EntityTypeAlreadyPresentInCatalogSchemaException;
import io.evitadb.api.observability.trace.DefaultTracingContext;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
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
import io.evitadb.api.requestResponse.system.StoredVersion;
import io.evitadb.api.requestResponse.system.TimeFlow;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.Catalog;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.Evita;
import io.evitadb.core.EvitaSession;
import io.evitadb.core.buffer.WarmUpDataStoreMemoryBuffer;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.executor.ImmediateExecutorService;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.file.ExportFileService;
import io.evitadb.core.sequence.SequenceService;
import io.evitadb.core.traffic.TrafficRecordingEngine;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.exception.InvalidClassifierFormatException;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.entity.model.schema.CatalogSchemaStoragePart;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.offsetIndex.exception.UnexpectedCatalogContentsException;
import io.evitadb.store.offsetIndex.io.CatalogOffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.ReadOnlyFileHandle;
import io.evitadb.store.offsetIndex.io.ReadOnlyHandle;
import io.evitadb.store.offsetIndex.io.WriteOnlyOffHeapWithFileBackupHandle;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.store.spi.EntityCollectionPersistenceService;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
import io.evitadb.store.spi.exception.DirectoryNotEmptyException;
import io.evitadb.store.spi.model.CatalogHeader;
import io.evitadb.store.spi.model.EntityCollectionHeader;
import io.evitadb.store.spi.model.reference.CollectionFileReference;
import io.evitadb.store.wal.AbstractMutationLog;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.store.catalog.DefaultCatalogPersistenceService.getCatalogBootstrapForSpecificMoment;
import static io.evitadb.store.catalog.DefaultCatalogPersistenceService.getFirstCatalogBootstrap;
import static io.evitadb.store.catalog.DefaultCatalogPersistenceService.getLastCatalogBootstrap;
import static io.evitadb.store.catalog.DefaultIsolatedWalServiceTest.DATA_MUTATION_EXAMPLE;
import static io.evitadb.store.catalog.DefaultIsolatedWalServiceTest.SCHEMA_MUTATION_EXAMPLE;
import static io.evitadb.store.spi.CatalogPersistenceService.CATALOG_FILE_SUFFIX;
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

	private final UUID catalogId = UUID.randomUUID();
	private final UUID transactionId = UUID.randomUUID();
	private final Path walFile = getTestDirectory().resolve(this.transactionId.toString());
	private final Kryo kryo = KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
	private final ObservableOutputKeeper observableOutputKeeper = new ObservableOutputKeeper(
		TEST_CATALOG,
		StorageOptions.builder().build(),
		Mockito.mock(Scheduler.class)
	);
	private final WriteOnlyOffHeapWithFileBackupHandle writeHandle = new WriteOnlyOffHeapWithFileBackupHandle(
		getTestDirectory().resolve(this.transactionId.toString()),
		getStorageOptions(),
		this.observableOutputKeeper,
		new CatalogOffHeapMemoryManager(TEST_CATALOG, 512, 1)
	);
	private final DefaultIsolatedWalService walService = new DefaultIsolatedWalService(
		this.transactionId,
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

		final PaginatedList<StoredVersion> catalogVersions = ioService.getCatalogVersions(TimeFlow.FROM_OLDEST_TO_NEWEST, 1, 20);
		final StoredVersion firstRecord = catalogVersions.getData().get(0);
		assertEquals(sinceCatalogVersion, firstRecord.version());
		assertEquals(expectedVersion, firstRecord.version());
		assertEquals(expectedCount, catalogVersions.getTotalRecordCount());
	}

	@Nonnull
	private static TrafficRecordingEngine createTrafficRecordingEngine(@Nonnull SealedCatalogSchema catalogSchema) {
		return new TrafficRecordingEngine(
			catalogSchema.getName(),
			CatalogState.WARMING_UP,
			DefaultTracingContext.INSTANCE,
			EvitaConfiguration.builder()
				.storage(StorageOptions.builder().build())
				.server(
					ServerOptions.builder()
						.trafficRecording(
							TrafficRecordingOptions.builder()
								.build()
						).build()
				)
				.build(),
			Mockito.mock(ExportFileService.class),
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
		final CatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
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

		final List<EntityCollectionHeader> entityHeaders = new ArrayList<>(3);
		entityHeaders.add(productCollection.flush().header());
		entityHeaders.add(brandCollection.flush().header());
		entityHeaders.add(storeCollection.flush().header());

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

		try (final ReadOnlyHandle readOnlyHandle = new ReadOnlyFileHandle(walFile, StorageOptions.temporary())) {
			readOnlyHandle.execute(
				input -> {
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
	void shouldTraverseBootstrapRecordsFromOldestToNewest() {
		final String catalogName = SEALED_CATALOG_SCHEMA.getName();
		final StorageOptions storageOptions = getStorageOptions();
		final OffsetDateTime startTime = Instant.ofEpochMilli(System.currentTimeMillis() - 1_000_000_000L).atZone(ZoneId.systemDefault()).toOffsetDateTime();
		DefaultCatalogPersistenceService.CURRENT_TIME_MILLIS = () -> startTime.toInstant().toEpochMilli();

		try (
			final DefaultCatalogPersistenceService ioService = new DefaultCatalogPersistenceService(
				catalogName,
				storageOptions,
				getTransactionOptions(),
				Mockito.mock(Scheduler.class),
				Mockito.mock(ExportFileService.class)
			)
		) {

			for (int i = 0; i < 12; i++) {
				final int catalogVersion = i + 1;
				DefaultCatalogPersistenceService.CURRENT_TIME_MILLIS = () -> startTime.plusHours(catalogVersion).toInstant().toEpochMilli();
				ioService.recordBootstrap(catalogVersion, catalogName, 0, null);
			}

			final PaginatedList<StoredVersion> catalogVersions = ioService.getCatalogVersions(TimeFlow.FROM_OLDEST_TO_NEWEST, 1, 5);
			assertEquals(5, catalogVersions.getData().size());
			assertEquals(13, catalogVersions.getTotalRecordCount());
			for (int i = 0; i <= 4; i++) {
				final StoredVersion record = catalogVersions.getData().get(i);
				assertEquals(i, record.version());
				assertNotNull(record.introducedAt());
			}

			final PaginatedList<StoredVersion> catalogVersionsLastPage = ioService.getCatalogVersions(TimeFlow.FROM_OLDEST_TO_NEWEST, 3, 5);
			assertEquals(3, catalogVersionsLastPage.getData().size());
			for (int i = 0; i < 3; i++) {
				final StoredVersion record = catalogVersionsLastPage.getData().get(i);
				assertEquals(10 + i, record.version());
				assertNotNull(record.introducedAt());
			}

			final Optional<CatalogBootstrap> first = getFirstCatalogBootstrap(catalogName, storageOptions);
			assertTrue(first.isPresent());
			assertEquals(0, first.get().catalogVersion());

			final CatalogBootstrap last = getLastCatalogBootstrap(catalogName, storageOptions);
			assertNotNull(last);
			assertEquals(12, last.catalogVersion());

			final CatalogBootstrap m0 = getCatalogBootstrapForSpecificMoment(catalogName, storageOptions, startTime);
			assertNotNull(m0);
			assertEquals(0, m0.catalogVersion());

			final CatalogBootstrap m1 = getCatalogBootstrapForSpecificMoment(catalogName, storageOptions, startTime.plusHours(5));
			assertNotNull(m1);
			assertEquals(5, m1.catalogVersion());

			final CatalogBootstrap m2 = getCatalogBootstrapForSpecificMoment(catalogName, storageOptions, startTime.plusHours(5).plusMinutes(1));
			assertNotNull(m2);
			assertEquals(5, m2.catalogVersion());

			final CatalogBootstrap m3 = getCatalogBootstrapForSpecificMoment(catalogName, storageOptions, startTime.plusHours(5).minusMinutes(1));
			assertNotNull(m3);
			assertEquals(4, m3.catalogVersion());

			final CatalogBootstrap m4 = getCatalogBootstrapForSpecificMoment(catalogName, storageOptions, startTime.plusHours(15));
			assertNotNull(m4);
			assertEquals(12, m4.catalogVersion());
		}
	}

	@Test
	void shouldTraverseBootstrapRecordsFromNewestToOldest() {
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

			for (int i = 0; i < 12; i++) {
				ioService.recordBootstrap(i + 1, catalogName, 0, null);
			}

			final PaginatedList<StoredVersion> catalogVersions = ioService.getCatalogVersions(TimeFlow.FROM_NEWEST_TO_OLDEST, 1, 5);
			assertEquals(5, catalogVersions.getData().size());
			assertEquals(13, catalogVersions.getTotalRecordCount());
			for (int i = 0; i < 5; i++) {
				final StoredVersion record = catalogVersions.getData().get(i);
				assertEquals(13 - (i + 1), record.version());
				assertNotNull(record.introducedAt());
			}

			final PaginatedList<StoredVersion> catalogVersionsLastPage = ioService.getCatalogVersions(TimeFlow.FROM_NEWEST_TO_OLDEST, 3, 5);
			assertEquals(3, catalogVersionsLastPage.getData().size());
			for (int i = 0; i < 3; i++) {
				final StoredVersion record = catalogVersionsLastPage.getData().get(i);
				assertEquals(3 - (i + 1), record.version());
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

			final PaginatedList<StoredVersion> catalogVersions0 = ioService.getCatalogVersions(TimeFlow.FROM_OLDEST_TO_NEWEST, 1, 20);
			assertEquals(0, catalogVersions0.getData().get(0).version());
			assertEquals(13, catalogVersions0.getTotalRecordCount());

			trimAndCheck(ioService, 4, 4, 9);
			trimAndCheck(ioService, 7, 7, 6);
			trimAndCheck(ioService, 8, 8, 5);
		}
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
					productCollection.flush().header(),
					brandCollection.flush().header(),
					storeCollection.flush().header()
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
			false, false, true, 1.0, 0L, false,
			Long.MAX_VALUE, Long.MAX_VALUE
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
			TransactionOptions.DEFAULT_FLUSH_FREQUENCY
		);
	}

	@Nonnull
	private EntityCollection constructEntityCollectionWithSomeEntities(@Nonnull CatalogPersistenceService ioService, @Nonnull SealedCatalogSchema catalogSchema, @Nonnull SealedEntitySchema entitySchema, int entityTypePrimaryKey) {
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
		entityCollection.attachToCatalog(null, mockCatalog);

		// Use the captor when defining the mock behavior
		@SuppressWarnings("unchecked")
		final ArgumentCaptor<EngineMutation<?>> mutationCaptor = ArgumentCaptor.forClass(EngineMutation.class);
		final Evita mockEvita = mock(Evita.class);
		Mockito.doAnswer(invocation -> {
			final ModifyCatalogSchemaMutation mutation = invocation.getArgument(0);
			for (LocalCatalogSchemaMutation schemaMutation : mutation.getSchemaMutations()) {
				if (schemaMutation instanceof ModifyEntitySchemaMutation mesm && mesm.getEntityType().equals(entityType)) {
					entityCollection.updateSchema(catalogSchemaContract, mesm.getSchemaMutations());
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
		@Nonnull EntityCollectionHeader collectionHeader
	) {
		assertEquals(entityCollection.size(), collectionHeader.recordCount());
		final ObservableOutputKeeper outputKeeper = new ObservableOutputKeeper(
			TEST_CATALOG,
			getStorageOptions(),
			Mockito.mock(Scheduler.class)
		);

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

}
