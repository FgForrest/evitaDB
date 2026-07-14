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

package io.evitadb.api;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Pool;
import com.github.javafaker.Faker;
import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.InstanceEditor;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.SealedInstance;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.system.MaterializedVersionBlock;
import io.evitadb.api.requestResponse.system.TimeFlow;
import io.evitadb.api.requestResponse.system.WriteAheadLogVersionDescriptor;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.session.EvitaSession;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.function.Functions;
import io.evitadb.function.TriConsumer;
import io.evitadb.function.TriFunction;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.store.catalog.DefaultCatalogPersistenceService;
import io.evitadb.store.catalog.DefaultIsolatedWalService;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.checksum.ChecksumFactory;
import io.evitadb.store.checksum.Crc32CChecksumFactory;
import io.evitadb.store.compression.CompressionFactory;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.offsetIndex.io.CatalogOffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.OffHeapWithFileBackupReference;
import io.evitadb.store.offsetIndex.io.WriteOnlyOffHeapWithFileBackupHandle;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.wal.CatalogWriteAheadLog;
import io.evitadb.store.wal.WalKryoConfigurer;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.UUIDUtil;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.dataInLocales;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.test.generator.DataGenerator.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This comprehensive test class validates the transactional behavior of evitaDB, including:
 * <ul>
 *     <li>ACID properties of transactions (Atomicity, Consistency, Isolation, Durability)</li>
 *     <li>Write-Ahead Log (WAL) processing and recovery</li>
 *     <li>Conflict detection and resolution with various conflict policies</li>
 *     <li>Concurrent transaction execution and isolation levels</li>
 *     <li>Delta mutations and commutative operations</li>
 *     <li>Catalog versioning and time travel functionality</li>
 *     <li>Backup and restore operations</li>
 *     <li>Data file rotation and compaction</li>
 * </ul>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("Long-running Evita transactional functionality")
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(CONTRACT)
@Tag(QUERY)
@Tag(TRANSACTION)
public class LongRunningEvitaTransactionalFunctionalTest implements EvitaTestSupport {
	public static final String REPLACED_OFFSET_DATE_TIME = "REPLACED_OFFSET_DATE_TIME";
	public static final String REPLACED_UUID = "REPLACED_UUID";
	private static final String TRANSACTIONAL_DATA_SET = "transactionalDataSet";
	private static final int SEED = 42;
	private static final TriFunction<String, EvitaSessionContract, Faker, Integer> RANDOM_ENTITY_PICKER = (entityType, session, faker) -> {
		try {
			final int entityCount = session.getEntityCollectionSize(entityType);
			final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
			return primaryKey == 0 ? null : primaryKey;
		} catch (Exception e) {
			return null;
		}
	};
	private static final Pattern DATE_TIME_PATTERN_1 = Pattern.compile(
		"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?\\+\\d{2}:\\d{2}");
	private static final Pattern DATE_TIME_PATTERN_2 = Pattern.compile(
		"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z");
	private static final Pattern UUID_PATTERN = Pattern.compile(
		"\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
	private static final Supplier<DataGenerator> GENERATOR_FACTORY = () -> new DataGenerator.Builder()
		.withCurrencies(CURRENCY_CZK)
		.withPriceLists(PRICE_LIST_BASIC)
		.build();
	private static final String BRAND_PRIORITY = "brandPriority";
	private static final String STORE_PRIORITY = "storePriority";
	private final DataGenerator dataGenerator = GENERATOR_FACTORY.get();
	private final Pool<Kryo> catalogKryoPool = new Pool<>(false, false, 1) {
		@Override
		protected Kryo create() {
			return KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		}
	};
	private final ObservableOutputKeeper observableOutputKeeper = ObservableOutputKeeper._internalBuild(
		Mockito.mock(Scheduler.class)
	);
	private final CatalogOffHeapMemoryManager offHeapMemoryManager = new CatalogOffHeapMemoryManager(
		TEST_CATALOG, 10_000_000, 128, ChecksumFactory.NO_OP
	);

	/* ======================================================================================== */
	/* HELPER METHODS */
	/* ======================================================================================== */

	/**
	 * Creates a random entity picker function that can be used with the data generator.
	 * The picker randomly selects an existing entity primary key from the given entity type,
	 * or returns null if no entities exist.
	 *
	 * @param session the evita session to query for entities
	 * @return a function that picks random entity primary keys
	 */
	@Nonnull
	private static BiFunction<String, Faker, Integer> createRandomEntityPicker(@Nonnull EvitaSessionContract session) {
		return (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(entityType, session, faker);
	}

	/* ======================================================================================== */
	/* STATIC HELPER METHODS */
	/* ======================================================================================== */

	/**
	 * Replaces timestamps in ISO OFFSET DATE TIME format (2024-02-26T14:48:54.984+01:00 or 2024-02-26T14:48:54.984Z)
	 * and UUIDs with placeholders to ensure repeatable test results.
	 * This is necessary because timestamps and UUIDs are generated dynamically and would cause
	 * test assertions to fail on each run.
	 *
	 * @param textWithTimestamps the text containing timestamps and UUIDs to replace
	 * @return the text with timestamps replaced by "REPLACED_OFFSET_DATE_TIME" and UUIDs replaced by "REPLACED_UUID"
	 */
	@Nonnull
	private static String replaceTimeStampsAndUuids(@Nonnull String textWithTimestamps) {
		// the pattern is in the form of 2024-02-26T14:48:54.984+01:00
		return UUID_PATTERN.matcher(
			DATE_TIME_PATTERN_2.matcher(
				DATE_TIME_PATTERN_1.matcher(textWithTimestamps).replaceAll(REPLACED_OFFSET_DATE_TIME)
			).replaceAll(REPLACED_OFFSET_DATE_TIME)
		).replaceAll(REPLACED_UUID);
	}

	/**
	 * Creates new products in massive parallelism (30 threads, 100 iterations each) and verifies that
	 * the entities are visible in the catalog according to SNAPSHOT isolation level.
	 * <p>
	 * This method tests:
	 * <ul>
	 *     <li>Concurrent entity creation with auto-generated primary keys</li>
	 *     <li>SNAPSHOT isolation - entities are only visible after their transaction commits</li>
	 *     <li>WAL append completion tracking</li>
	 *     <li>Proper transaction ordering and catalog version progression</li>
	 * </ul>
	 *
	 * @param evita                 the Evita instance to use for concurrent operations
	 * @param productSchema         the product schema to use for entity generation
	 * @param applyOnceWhileWaiting optional lambda to apply once when approximately half of entities have been inserted
	 * @return the set of primary keys with their associated catalog versions
	 * @throws Exception if any thread fails during execution
	 */
	@Nonnull
	private static Set<PkWithCatalogVersion> automaticallyGenerateEntitiesInParallel(
		@Nonnull EvitaContract evita,
		@Nonnull SealedEntitySchema productSchema,
		@Nullable Consumer<EvitaContract> applyOnceWhileWaiting
	) throws Exception {
		final int numberOfThreads = 30;
		final int iterations = 100;
		final int timeout = 300;
		final ExecutorService service = Executors.newFixedThreadPool(numberOfThreads);
		final CountDownLatch latch = new CountDownLatch(numberOfThreads);
		final Set<PkWithCatalogVersion> primaryKeysWithTxIds = new ConcurrentSkipListSet<>();

		final long initialStart = System.currentTimeMillis();
		final AtomicReference<Exception> thrownException = new AtomicReference<>();
		final DataGenerator dataGenerator = GENERATOR_FACTORY.get();
		for (int i = 0; i < numberOfThreads; i++) {
			final int threadSeed = SEED + i;
			service.execute(() -> {
				try {
					// primary keys should be automatically generated in monotonic fashion
					dataGenerator.generateEntities(
							productSchema,
							(entityType, faker) -> {
								try (EvitaSessionContract readOnlySession = evita.createReadOnlySession(TEST_CATALOG)) {
									return RANDOM_ENTITY_PICKER.apply(entityType, readOnlySession, faker);
								}
							},
							threadSeed
						)
						.limit(iterations)
						.map(it -> {
							assertFalse(Transaction.getTransaction().isPresent());
							final AtomicReference<EntityReferenceContract> createdReference = new AtomicReference<>();
							final CompletableFuture<CommitVersions> targetCatalogVersion = evita.updateCatalogAsync(
									TEST_CATALOG,
									session -> {
										final long currentCatalogVersion = session.getCatalogVersion();
										createdReference.set(session.upsertEntity(it));

										// verify that no entity with older transaction id is visible - i.e. SNAPSHOT isolation level
										for (PkWithCatalogVersion existingPk : primaryKeysWithTxIds) {
											final SealedEntity fetchedEntity = session.getEntity(
												existingPk.getType(), existingPk.getPrimaryKey()).orElse(null);
											if (existingPk.catalogVersion() <= currentCatalogVersion) {
												assertNotNull(
													fetchedEntity,
													"Entity with catalogVersion " + existingPk.catalogVersion() + " is missing in catalog version `" + currentCatalogVersion + "`!"
												);
											} else {
												assertNull(
													fetchedEntity,
													"Entity with catalogVersion `" + existingPk.catalogVersion() + "` is present in catalog version `" + currentCatalogVersion + "`!"
												);
											}
										}
									}, SessionFlags.READ_WRITE
								)
								.onWalAppended()
								.toCompletableFuture();
							try {
								final long catalogVersion = targetCatalogVersion.get().catalogVersion();
								final PkWithCatalogVersion pkWithCatalogVersion = new PkWithCatalogVersion(
									createdReference.get(), catalogVersion);
								primaryKeysWithTxIds.add(pkWithCatalogVersion);
								return pkWithCatalogVersion;
							} catch (ExecutionException | InterruptedException e) {
								// fail the test
								throw new RuntimeException(e);
							}
						})
						.forEach(it -> {
							// verify the entity is present in another transaction
							evita.queryCatalog(
								TEST_CATALOG,
								session -> {
									assertNotNull(session.getEntity(it.getType(), it.getPrimaryKey()));
								}
							);
						});
					log.info("Thread {} finished.", Thread.currentThread().getName());
				} catch (Exception ex) {
					thrownException.set(ex);
					log.error("Thread {} failed.", Thread.currentThread().getName(), ex);
				} finally {
					latch.countDown();
					log.info("{} threads remaining ...", latch.getCount());
				}
			});
		}

		log.info("Waiting for the entities to be inserted...");
		if (applyOnceWhileWaiting != null) {
			// wait until at least half of the data has been inserted
			long waitingStart = System.currentTimeMillis();
			while (
				primaryKeysWithTxIds.size() < (numberOfThreads * iterations + 1) / 2 &&
					// cap to one minute
					System.currentTimeMillis() - waitingStart < timeout * 1000
			) {
				Thread.onSpinWait();
			}
			// now submit the lambda
			service.submit(
				() -> applyOnceWhileWaiting.accept(evita)
			);
		}

		assertTrue(latch.await(timeout, TimeUnit.SECONDS), "Timeouted!");

		if (thrownException.get() != null) {
			throw thrownException.get();
		}

		// wait until Evita reaches the last version of the catalog
		long waitingStart = System.currentTimeMillis();
		int cnt = 0;
		while (
			// cap to one minute
			System.currentTimeMillis() - waitingStart < 120_000 &&
				// and finish when the last transaction is visible
				evita.queryCatalog(
					TEST_CATALOG, EvitaSessionContract::getCatalogVersion) < numberOfThreads * iterations + 1
		) {
			cnt++;
			Thread.onSpinWait();
			if (cnt % 1_000_000 == 0) {
				log.info(
					"Waiting for records to become present ({} of {})",
					evita.queryCatalog(TEST_CATALOG, EvitaSessionContract::getCatalogVersion),
					numberOfThreads * iterations + 1
				);
			}
		}

		assertEquals(numberOfThreads * iterations, primaryKeysWithTxIds.size());
		final Set<Integer> primaryKeys = primaryKeysWithTxIds.stream()
			.map(PkWithCatalogVersion::getPrimaryKey)
			.collect(Collectors.toSet());
		for (int i = 1; i <= numberOfThreads * iterations; i++) {
			assertTrue(primaryKeys.contains(i), "Primary key missing: " + (i));
		}

		System.out.println(
			"Created " + primaryKeysWithTxIds.size() + " entities in " + (numberOfThreads * iterations) +
				" transactions in " + (System.currentTimeMillis() - initialStart) + " ms."
		);

		return primaryKeysWithTxIds;
	}

	@DataSet(value = TRANSACTIONAL_DATA_SET, readOnly = false)
	SealedEntitySchema setUp(Evita evita) {
		return evita.updateCatalog(
			TEST_CATALOG, session -> {
				session.updateCatalogSchema(
					session.getCatalogSchema()
						.openForWrite()
						.withAttribute(
							ATTRIBUTE_CODE, String.class, whichIs -> whichIs.sortable().uniqueGlobally().nullable())
						.withAttribute(
							ATTRIBUTE_URL, String.class, whichIs -> whichIs.localized().uniqueGlobally().nullable())
				);

				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
					entityType, session, faker);
				this.dataGenerator.generateEntities(
						this.dataGenerator.getSampleBrandSchema(session),
						randomEntityPicker,
						SEED
					)
					.limit(5)
					.forEach(session::upsertEntity);

				this.dataGenerator.generateEntities(
						this.dataGenerator.getSampleCategorySchema(session),
						randomEntityPicker,
						SEED
					)
					.limit(10)
					.forEach(session::upsertEntity);

				this.dataGenerator.generateEntities(
						this.dataGenerator.getSamplePriceListSchema(session),
						randomEntityPicker,
						SEED
					)
					.limit(4)
					.forEach(session::upsertEntity);

				this.dataGenerator.generateEntities(
						this.dataGenerator.getSampleStoreSchema(session),
						randomEntityPicker,
						SEED
					)
					.limit(12)
					.forEach(session::upsertEntity);

				// create product schema
				return this.dataGenerator.getSampleProductSchema(
					session, schemaBuilder -> {
						return schemaBuilder
							.withoutGeneratedPrimaryKey()
							.withReferenceToEntity(
								Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
								whichIs -> whichIs
									.indexedForFilteringAndPartitioning()
									.faceted()
									.withAttribute(BRAND_PRIORITY, Long.class)
							)
							.withReferenceToEntity(
								Entities.STORE, Entities.STORE, Cardinality.ZERO_OR_MORE,
								whichIs -> whichIs
									.indexedForFilteringAndPartitioning()
									.faceted()
									.withAttribute(STORE_PRIORITY, Long.class)
							)
							.updateAndFetchVia(session);
					}
				);
			}
		);
	}

	/* ======================================================================================== */
	/* TEST SETUP */
	/* ======================================================================================== */

	@AfterEach
	void tearDown() {
		this.observableOutputKeeper.close();
	}

	/* ======================================================================================== */
	/* WAL PROCESSING AND RECOVERY TESTS */
	/* ======================================================================================== */

	@DisplayName("Catalog history should be aggregated correctly.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Tag(SLOW)
	@Test
	void shouldBuildCorrectHistory(Evita evita) throws IOException {
		// close evita first so that the processing pipeline is shut down
		final EvitaConfiguration cfg = evita.getConfiguration();
		final SealedCatalogSchema catalogSchema = evita.getCatalogInstance(TEST_CATALOG).orElseThrow().getSchema();
		final EntitySchemaContract productSchema = catalogSchema.getEntitySchema(Entities.PRODUCT).orElseThrow();
		evita.close();

		final Path catalogDirectory = cfg.storage().storageDirectory().resolve(TEST_CATALOG);
		try (
			final CatalogWriteAheadLog wal = new CatalogWriteAheadLog(
				0L,
				TEST_CATALOG,
				new LogFileRecordReference(index -> CatalogPersistenceService.getWalFileName(TEST_CATALOG, index)),
				catalogDirectory,
				this.catalogKryoPool,
				new StorageSettings(
					StorageOptions.builder().build(),
					TransactionOptions.builder().build()
				),
				Mockito.mock(Scheduler.class),
				Functions.noOpLongConsumer(),
				null
			)
		) {

			// create WAL file multiple times, start Catalog to crunch the history and close evitaDB again
			final int[][] transactionSizes = new int[][]{
				{3, 4, 2}, // 3 transactions, 9 mutations
				{5, 1, 1, 4, 2}, // 5 transactions, 13 mutations
				{1, 2, 5, 3, 4}, // 5 transactions, 15 mutations

				{3, 2, 1, 4}, // 4 transactions, 10 mutations
				{1, 5, 3, 2, 4}, // 5 transactions, 15 mutations
				{3, 4, 5, 1}, // 4 transactions, 13 mutations
				{2, 4, 1, 3, 5}, // 5 transactions, 15 mutations
				{5, 3, 2, 4}, // 4 transactions, 14 mutations

				{1, 2, 3, 5, 4}, // 5 transactions, 15 mutations
				{5, 1, 3, 4}, // 4 transactions, 13 mutations
				{4, 1, 5, 2, 3}, // 5 transactions, 15 mutations
				{2, 3, 5, 4}, // 4 transactions, 14 mutations
				{1, 4, 2, 5, 3} // 5 transactions, 15 mutations
			};
			long catalogVersion = 1L;
			for (int[] transactionSize : transactionSizes) {
				// create WAL file with a few contents first
				appendWal(
					catalogVersion, this.offHeapMemoryManager, wal, transactionSize, catalogSchema, productSchema
				);
				catalogVersion += transactionSize.length;

				// start evita again and wait for the WAL to be processed
				final Evita secondInstance = new Evita(cfg);
				secondInstance.waitUntilFullyInitialized();

				// now shut down evitaDB again
				secondInstance.close();
			}

			// now we can browse the history
			try (Evita thirdInstance = new Evita(cfg)) {
				thirdInstance.waitUntilFullyInitialized();

				final CatalogContract catalog = thirdInstance.getCatalogInstance(TEST_CATALOG).orElseThrow();

				final long[] versions = catalog.getCatalogVersions(TimeFlow.FROM_NEWEST_TO_OLDEST, 1, 5)
					.getData()
					.stream()
					.mapToLong(MaterializedVersionBlock::endVersion)
					.toArray();
				assertArrayEquals(new long[]{59, 54, 50, 45, 41}, versions);

				assertEquals(
					replaceTimeStampsAndUuids(
						"""
							Catalog version: 59, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 59, committed at REPLACED_OFFSET_DATE_TIME (3 mutations, 1 KB):
								 - changes in `PRODUCT`: 3 upserted entities
							Catalog version: 54, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 54, committed at REPLACED_OFFSET_DATE_TIME (4 mutations, 1 KB):
								 - changes in `PRODUCT`: 4 upserted entities
							Catalog version: 50, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 50, committed at REPLACED_OFFSET_DATE_TIME (3 mutations, 1 KB):
								 - changes in `PRODUCT`: 3 upserted entities
							Catalog version: 45, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 45, committed at REPLACED_OFFSET_DATE_TIME (4 mutations, 2 KB):
								 - changes in `PRODUCT`: 4 upserted entities
							Catalog version: 41, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 41, committed at REPLACED_OFFSET_DATE_TIME (4 mutations, 2 KB):
								 - changes in `PRODUCT`: 4 upserted entities"""
					),
					replaceTimeStampsAndUuids(
						catalog.getCatalogVersionDescriptors(versions)
							.stream()
							.map(WriteAheadLogVersionDescriptor::toString)
							.collect(Collectors.joining("\n"))
					)
				);

				assertEquals(
					replaceTimeStampsAndUuids(
						"""
							Catalog version: 42, processed at REPLACED_OFFSET_DATE_TIME in reversible transaction REPLACED_UUID, changes: Transaction to version: 42, committed at REPLACED_OFFSET_DATE_TIME (5 mutations, 2 KB):
								 - changes in `PRODUCT`: 5 upserted entities
							Catalog version: 43, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 43, committed at REPLACED_OFFSET_DATE_TIME (1 mutations, 566 B):
								 - changes in `PRODUCT`: 1 upserted entities
							Catalog version: 51, processed at REPLACED_OFFSET_DATE_TIME in reversible transaction REPLACED_UUID, changes: Transaction to version: 51, committed at REPLACED_OFFSET_DATE_TIME (2 mutations, 1 KB):
								 - changes in `PRODUCT`: 2 upserted entities
							Catalog version: 55, processed at REPLACED_OFFSET_DATE_TIME in reversible transaction REPLACED_UUID, changes: Transaction to version: 55, committed at REPLACED_OFFSET_DATE_TIME (1 mutations, 590 B):
								 - changes in `PRODUCT`: 1 upserted entities
							Catalog version: 59, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 59, committed at REPLACED_OFFSET_DATE_TIME (3 mutations, 1 KB):
								 - changes in `PRODUCT`: 3 upserted entities
							Catalog version: 46, processed at REPLACED_OFFSET_DATE_TIME in reversible transaction REPLACED_UUID, changes: Transaction to version: 46, committed at REPLACED_OFFSET_DATE_TIME (4 mutations, 2 KB):
								 - changes in `PRODUCT`: 4 upserted entities"""
					),
					replaceTimeStampsAndUuids(
						catalog.getCatalogVersionDescriptors(42, 43, 51, 55, 59, 46)
							.stream()
							.map(WriteAheadLogVersionDescriptor::toString)
							.collect(Collectors.joining("\n"))
					)
				);

				final long[] prevVersions = catalog.getCatalogVersions(TimeFlow.FROM_NEWEST_TO_OLDEST, 2, 5)
					.getData()
					.stream()
					.mapToLong(MaterializedVersionBlock::endVersion)
					.toArray();
				assertArrayEquals(new long[]{36, 32, 27, 23, 18}, prevVersions);

				assertEquals(
					replaceTimeStampsAndUuids(
						"""
							Catalog version: 36, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 36, committed at REPLACED_OFFSET_DATE_TIME (4 mutations, 2 KB):
								 - changes in `PRODUCT`: 4 upserted entities
							Catalog version: 32, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 32, committed at REPLACED_OFFSET_DATE_TIME (5 mutations, 2 KB):
								 - changes in `PRODUCT`: 5 upserted entities
							Catalog version: 27, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 27, committed at REPLACED_OFFSET_DATE_TIME (1 mutations, 606 B):
								 - changes in `PRODUCT`: 1 upserted entities
							Catalog version: 23, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 23, committed at REPLACED_OFFSET_DATE_TIME (4 mutations, 2 KB):
								 - changes in `PRODUCT`: 4 upserted entities
							Catalog version: 18, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 18, committed at REPLACED_OFFSET_DATE_TIME (4 mutations, 2 KB):
								 - changes in `PRODUCT`: 4 upserted entities"""
					),
					replaceTimeStampsAndUuids(
						catalog.getCatalogVersionDescriptors(prevVersions)
							.stream()
							.map(WriteAheadLogVersionDescriptor::toString)
							.collect(Collectors.joining("\n"))
					)
				);

				final long[] prevPrevVersions = catalog.getCatalogVersions(TimeFlow.FROM_NEWEST_TO_OLDEST, 3, 5)
					.getData()
					.stream()
					.mapToLong(MaterializedVersionBlock::endVersion)
					.toArray();
				assertArrayEquals(new long[]{14, 9, 4, 1, 0}, prevPrevVersions);

				// there is no information in WAL for version 1 and 0 as they are in WARM-UP state
				assertEquals(
					replaceTimeStampsAndUuids(
						"""
							Catalog version: 14, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 14, committed at REPLACED_OFFSET_DATE_TIME (4 mutations, 2 KB):
								 - changes in `PRODUCT`: 4 upserted entities
							Catalog version: 9, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 9, committed at REPLACED_OFFSET_DATE_TIME (2 mutations, 1019 B):
								 - changes in `PRODUCT`: 2 upserted entities
							Catalog version: 4, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 4, committed at REPLACED_OFFSET_DATE_TIME (2 mutations, 1 KB):
								 - changes in `PRODUCT`: 2 upserted entities"""
					),
					replaceTimeStampsAndUuids(
						catalog.getCatalogVersionDescriptors(prevPrevVersions)
							.stream()
							.filter(Objects::nonNull)
							.map(WriteAheadLogVersionDescriptor::toString)
							.collect(Collectors.joining("\n"))
					)
				);

				final MaterializedVersionBlock firstCatalogVersionBlock = catalog.getFirstCatalogVersionAfter(null);
				assertEquals(0, firstCatalogVersionBlock.startVersion());
				assertEquals(0, firstCatalogVersionBlock.endVersion());
				assertNotNull(firstCatalogVersionBlock.introducedAt());

				final MaterializedVersionBlock lastCatalogVersionBlock = catalog.getFirstCatalogVersionAfter(
					OffsetDateTime.now());
				assertEquals(59, lastCatalogVersionBlock.startVersion());
				assertEquals(59, lastCatalogVersionBlock.endVersion());
				assertNotNull(lastCatalogVersionBlock.introducedAt());

				final MaterializedVersionBlock nextToLastCatalogVersionBlock = catalog.getLastCatalogVersionBefore(
					OffsetDateTime.now());
				assertEquals(55, nextToLastCatalogVersionBlock.startVersion());
				assertEquals(59, nextToLastCatalogVersionBlock.endVersion());
				assertNotNull(nextToLastCatalogVersionBlock.introducedAt());
			}
		}
	}

	/* ======================================================================================== */
	/* DATA FILE ROTATION AND COMPACTION TESTS */
	/* ======================================================================================== */

	@DisplayName("Should handle large transaction.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Tag(SLOW)
	@Test
	void shouldHandleLargeTransaction(EvitaContract evita, SealedEntitySchema productSchema) {
		final EvitaSessionContract session = evita.createSession(
			new SessionTraits(TEST_CATALOG, CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, SessionFlags.READ_WRITE));

		final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
			entityType, session, faker);
		final int entityCount = 500;
		this.dataGenerator.generateEntities(productSchema, randomEntityPicker, SEED)
			.limit(entityCount)
			.map(InstanceEditor::toMutation)
			.flatMap(Optional::stream)
			.forEach(session::upsertEntity);

		session.close();

		evita.queryCatalog(
			TEST_CATALOG,
			theNewSession -> {
				final int productCount = theNewSession.getEntityCollectionSize(productSchema.getName());
				assertEquals(entityCount, productCount);
			}
		);
	}

	@DisplayName("Should execute multiple updates of same entity in one large transaction.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Tag(SLOW)
	@Test
	void shouldExecuteMultipleUpdatesOfSameEntityInOneLargeTransaction(EvitaContract originalEvita) throws Exception {
		final EvitaConfiguration originalConfiguration = ((Evita) originalEvita).getConfiguration();
		originalEvita.close();

		// reinitialize evita with a larger flush frequency
		final Faker faker = new Faker(new Random(40));
		final Map<Long, SealedEntity> versionedEntities = new HashMap<>(16_384);

		try (Evita evita = new Evita(
			EvitaConfiguration.builder()
				.name(originalConfiguration.name())
				.storage(originalConfiguration.storage())
				.export(originalConfiguration.export())
				.transaction(
					TransactionOptions.builder()
						.flushFrequencyInMillis(60_000)
						.conflictPolicyLastWriterWins()
						.build()
				)
				.server(
					ServerOptions.builder(originalConfiguration.server())
						.transactionThreadPool(
							ThreadPoolOptions.transactionThreadPoolBuilder()
								.threadPriority(Thread.MAX_PRIORITY)
								.queueSize(16_384)
								.build()
						)
						.build()
				)
				.cache(originalConfiguration.cache())
				.build()
		)
		) {
			evita.waitUntilFullyInitialized();

			final AtomicReference<SealedEntity> theEntityRef = new AtomicReference<>();
			CommitProgress commitProgress = null;
			AtomicReference<Consumer<CommitProgress>> commitProgressConsumer = new AtomicReference<>(null);
			for (int i = 0; i < 1000; i++) {
				commitProgress = evita.updateCatalogAsync(
					TEST_CATALOG,
					session -> {
						final Optional<SealedEntity> existingEntity = session.getEntity(
							Entities.PRODUCT, 1, attributeContentAll(), dataInLocales(Locale.ENGLISH)
						);

						if (existingEntity.isEmpty()) {
							// when entity doesn't exist, we need to wait for the commit to finish
							commitProgressConsumer.set(
								cp -> cp.onChangesVisible()
									.toCompletableFuture()
									.join()
							);
						} else {
							commitProgressConsumer.set(null);
						}

						final EntityBuilder entityBuilder = existingEntity
							.map(SealedInstance::openForWrite)
							.orElseGet(() -> session.createNewEntity(Entities.PRODUCT, 1))
							.setAttribute(ATTRIBUTE_URL, Locale.ENGLISH, faker.internet().url())
							.setAttribute(ATTRIBUTE_CODE, faker.code().isbn10())
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, faker.book().title())
							.setAttribute(ATTRIBUTE_PRIORITY, faker.number().numberBetween(1L, 1000L));
						theEntityRef.set(entityBuilder.toInstance());
						entityBuilder.upsertVia(session);
					}
				);

				// wait only for the WAL to be appended, not for the commit to finish
				commitProgress
					.onWalAppended()
					.thenAccept(cv -> versionedEntities.put(cv.catalogVersion(), theEntityRef.get()))
					.toCompletableFuture()
					.join();

				final Consumer<CommitProgress> cpc = commitProgressConsumer.get();
				if (cpc != null) {
					cpc.accept(commitProgress);
				}
			}

			// wait for the last commit to finish
			commitProgress.onChangesVisible()
				.toCompletableFuture()
				.join();

			// now check the last version of the entity
			evita.queryCatalog(
				TEST_CATALOG,
				evitaSessionContract -> {
					final long catalogVersion = evitaSessionContract.getCatalogVersion();
					final SealedEntity expectedEntity = versionedEntities.get(catalogVersion);
					final Optional<SealedEntity> entity = evitaSessionContract.getEntity(
						Entities.PRODUCT, 1, attributeContentAll(), dataInLocales(Locale.ENGLISH)
					);
					assertTrue(entity.isPresent(), "Entity should be present in the catalog!");
					final SealedEntity realEntity = entity.get();
					assertEquals(
						expectedEntity.getAttribute(ATTRIBUTE_CODE, String.class),
						realEntity.getAttribute(ATTRIBUTE_CODE, String.class),
						"Entity code at version " + catalogVersion + " should match the expected one!"
					);
					assertEquals(
						expectedEntity.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, String.class),
						realEntity.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, String.class),
						"Entity name at version " + catalogVersion + " should match the expected one!"
					);
					assertEquals(
						expectedEntity.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH, String.class),
						realEntity.getAttribute(ATTRIBUTE_URL, Locale.ENGLISH, String.class),
						"Entity URL at version " + catalogVersion + " should match the expected one!"
					);
				}
			);
		}
	}

	@DisplayName("Verify code has no problems assigning new PK in concurrent environment")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Tag(SLOW)
	@Test
	void shouldAutomaticallyGenerateEntitiesInParallel(
		EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		final EvitaConfiguration originalConfiguration = ((Evita) originalEvita).getConfiguration();
		originalEvita.close();

		// reinitialize evita with a larger queue size
		try (Evita evita = new Evita(
			EvitaConfiguration.builder()
				.name(originalConfiguration.name())
				.storage(originalConfiguration.storage())
				.export(originalConfiguration.export())
				.transaction(
					TransactionOptions.builder()
						.build()
				)
				.server(
					ServerOptions.builder(originalConfiguration.server())
						.transactionThreadPool(
							ThreadPoolOptions.transactionThreadPoolBuilder()
								.threadPriority(Thread.MAX_PRIORITY)
								.queueSize(16_384)
								.build()
						)
						.build()
				)
				.cache(originalConfiguration.cache())
				.build()
		)) {
			evita.waitUntilFullyInitialized();
			automaticallyGenerateEntitiesInParallel(evita, productSchema, null);
		}
	}

	/* ======================================================================================== */
	/* CONCURRENT OPERATIONS TESTS */
	/* ======================================================================================== */

	@DisplayName("Verify code has no problems assigning new PK in concurrent environment and simultaneous backup & restore")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Tag(SLOW)
	@Test
	void shouldBackupAndRestoreCatalogDuringHeavyParallelIndexing(
		EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		final EvitaConfiguration originalConfiguration = ((Evita) originalEvita).getConfiguration();
		originalEvita.close();

		// reinitialize evita with a larger queue size
		final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.name(originalConfiguration.name())
				.storage(originalConfiguration.storage())
				.export(originalConfiguration.export())
				.transaction(
					TransactionOptions.builder()
						.build()
				)
				.server(
					ServerOptions.builder(originalConfiguration.server())
						.transactionThreadPool(
							ThreadPoolOptions.transactionThreadPoolBuilder()
								.threadPriority(Thread.MAX_PRIORITY)
								.queueSize(16_384)
								.build()
						)
						.build()
				)
				.cache(originalConfiguration.cache())
				.build()
		);

		try {
			final AtomicReference<CompletableFuture<FileForFetch>> lastBackupProcess = new AtomicReference<>();
			final Set<PkWithCatalogVersion> insertedPrimaryKeysAndAssociatedTxs = automaticallyGenerateEntitiesInParallel(
				evita, productSchema, theEvita ->
					lastBackupProcess.set(theEvita.management().backupCatalog(TEST_CATALOG, null, null, false))
			);

			final CompletableFuture<FileForFetch> fileForFetchCompletableFuture = lastBackupProcess.get();
			assertNotNull(fileForFetchCompletableFuture, "No backup process was started!");
			final Path backupFilePath = fileForFetchCompletableFuture.get().path(
				((FileSystemExportOptions) evita.getConfiguration().export()).getDirectory());
			assertTrue(backupFilePath.toFile().exists(), "Backup file does not exist!");

			final String restoredCatalogName = TEST_CATALOG + "_restored";
			evita.management().restoreCatalog(
					restoredCatalogName, Files.size(backupFilePath), Files.newInputStream(backupFilePath))
				.getFutureResult().get(5, TimeUnit.MINUTES);
			evita.activateCatalog(restoredCatalogName);

			final long originalCatalogVersion = evita.queryCatalog(
				TEST_CATALOG,
				EvitaSessionContract::getCatalogVersion
			);

			log.info("Original catalog finished with version: " + originalCatalogVersion);

			evita.queryCatalog(
				restoredCatalogName,
				session -> {
					final long restoredCatalogVersion = session.getCatalogVersion();
					log.info("Restored catalog is version: " + restoredCatalogVersion);

					assertTrue(
						restoredCatalogVersion < originalCatalogVersion,
						"Restored catalog version should be lower than the original one!"
					);

					final AtomicInteger productCount = new AtomicInteger();
					insertedPrimaryKeysAndAssociatedTxs
						.stream()
						.filter(it -> it.catalogVersion() <= restoredCatalogVersion).forEach(
							it -> {
								final Optional<SealedEntity> entity = session.getEntity(it.getType(), it.getPrimaryKey());
								assertTrue(
									entity.isPresent(),
									"Entity `" + it + "` visible in version `" + it.catalogVersion() +
										"` not found in restored catalog with restored version `" + restoredCatalogVersion + "`!"
								);
								productCount.incrementAndGet();
							}
						);

					log.info("Restored catalog has " + productCount.get() + " products.");

					assertEquals(
						productCount.get(),
						session.getEntityCollectionSize(productSchema.getName()),
						"Number of products in restored catalog does not match the original catalog!"
					);

				}
			);
		} finally {
			if (evita.isActive()) {
				evita.close();
			}
		}
	}

	@DisplayName("Verify code has no problems assigning new PK in concurrent environment and simultaneous backup & restore including WAL")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Tag(SLOW)
	@Test
	void shouldBackupAndRestoreCatalogDuringHeavyParallelIndexingIncludingWal(
		EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		final EvitaConfiguration originalConfiguration = ((Evita) originalEvita).getConfiguration();
		originalEvita.close();

		// reinitialize evita with a larger queue size
		final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.name(originalConfiguration.name())
				.storage(originalConfiguration.storage())
				.export(originalConfiguration.export())
				.transaction(
					TransactionOptions.builder()
						.build()
				)
				.server(
					ServerOptions.builder(originalConfiguration.server())
						.transactionThreadPool(
							ThreadPoolOptions.transactionThreadPoolBuilder()
								.threadPriority(Thread.MAX_PRIORITY)
								.queueSize(16_384)
								.build()
						)
						.build()
				)
				.cache(originalConfiguration.cache())
				.build()
		);

		try {
			final AtomicReference<CompletableFuture<FileForFetch>> lastBackupProcess = new AtomicReference<>();
			final Set<PkWithCatalogVersion> insertedPrimaryKeysAndAssociatedTxs = automaticallyGenerateEntitiesInParallel(
				evita, productSchema,
				theEvita -> lastBackupProcess.set(theEvita.management().backupCatalog(TEST_CATALOG, null, null, false))
			);

			final Path backupFilePath = lastBackupProcess.get().get().path(
				((FileSystemExportOptions) evita.getConfiguration().export()).getDirectory());
			assertTrue(backupFilePath.toFile().exists(), "Backup file does not exist!");

			final String restoredCatalogName = TEST_CATALOG + "_restored";
			final CompletableFuture<Void> restoreFuture = evita.management().restoreCatalog(
					restoredCatalogName, Files.size(backupFilePath), Files.newInputStream(backupFilePath))
				.getFutureResult();
			restoreFuture.get(5, TimeUnit.MINUTES);
			evita.activateCatalog(restoredCatalogName);

			final long originalCatalogVersion = evita.queryCatalog(
				TEST_CATALOG,
				EvitaSessionContract::getCatalogVersion
			);

			log.info("Original catalog finished with version: " + originalCatalogVersion);

			evita.queryCatalog(
				restoredCatalogName,
				session -> {
					final long restoredCatalogVersion = session.getCatalogVersion();
					log.info("Restored catalog is version: " + restoredCatalogVersion);

					final AtomicInteger productCount = new AtomicInteger();
					insertedPrimaryKeysAndAssociatedTxs
						.stream()
						.filter(it -> it.catalogVersion() <= restoredCatalogVersion).forEach(
							it -> {
								final Optional<SealedEntity> entity = session.getEntity(it.getType(), it.getPrimaryKey());
								assertTrue(
									entity.isPresent(),
									"Entity `" + it + "` visible in version `" + it.catalogVersion() +
										"` not found in restored catalog with restored version `" + restoredCatalogVersion + "`!"
								);
								productCount.incrementAndGet();
							}
						);

					log.info("Restored catalog has " + productCount.get() + " products.");

					assertEquals(
						productCount.get(),
						session.getEntityCollectionSize(productSchema.getName()),
						"Number of products in restored catalog does not match the original catalog!"
					);

				}
			);
		} finally {
			if (evita.isActive()) {
				evita.close();
			}
		}
	}

	@Tag(SLOW)
	@ParameterizedTest(name = "This test verifies, that all data files are correctly rotated and compacted.")
	@ArgumentsSource(TimeArgumentProvider.class)
	void shouldCorrectlyRotateAllFiles(GenerationalTestInput input) throws Exception {
		final Path testDirectory = getTestDirectory().resolve("shouldCorrectlyRotateAllFiles");
		final Path testDirectoryExport = getTestDirectory().resolve("shouldCorrectlyRotateAllFiles_export");
		try {
			final Evita evita = new Evita(
				EvitaConfiguration.builder()
					.storage(
						StorageOptions.builder()
							.storageDirectory(testDirectory)
							.minimalActiveRecordShare(0.9)
							.fileSizeCompactionThresholdBytes(16_384)
							.timeTravelEnabled(true)
							.build()
					)
					.transaction(
						TransactionOptions.builder()
							.walFileSizeBytes(4_096)
							.walFileCountKept(2)
							.conflictPolicyLastWriterWins()
							.build()
					)
					.server(
						ServerOptions.builder()
							.queryTimeoutInMilliseconds(-1)
							.transactionTimeoutInMilliseconds(-1)
							.closeSessionsAfterSecondsOfInactivity(-1)
							.build()
					)
					.export(
						FileSystemExportOptions.builder()
							.directory(testDirectoryExport)
							.build()
					)
					.build()
			);

			try {
				final String entityProduct = "product";
				final String attributeUrl = "url";
				final String attributeCode = "code";
				final String attributeName = "name";
				final String attributePrice = "price";

				final Faker faker = new Faker(new Random(input.randomSeed()));
				evita.defineCatalog(TEST_CATALOG)
					.updateAndFetchViaNewSession(evita)
					.openForWrite()
					.withAttribute(attributeUrl, String.class)
					.withEntitySchema(
						entityProduct, productSchema -> productSchema
							.withoutGeneratedPrimaryKey()
							.withGlobalAttribute(attributeUrl)
							.withAttribute(attributeCode, String.class, thatIs -> thatIs.unique().sortable())
							.withAttribute(attributeName, String.class, thatIs -> thatIs.filterable().sortable())
							.withAttribute(attributePrice, BigDecimal.class, thatIs -> thatIs.filterable().sortable())
					)
					.updateViaNewSession(evita);
				evita.updateCatalog(
					TEST_CATALOG, session -> {
						session.createNewEntity(entityProduct, 1)
							.setAttribute(attributeUrl, faker.internet().url())
							.setAttribute(attributeCode, faker.code().isbn10())
							.setAttribute(attributeName, faker.book().title())
							.setAttribute(attributePrice, BigDecimal.valueOf(faker.number().randomDouble(2, 1, 1000)))
							.upsertVia(session);
						session.goLiveAndClose();
					}
				);

				final LocalDateTime initialStart = LocalDateTime.now();
				final ConcurrentHashMap<Long, SealedEntity> versionedEntities = new ConcurrentHashMap<>();

				Long expectedLastVersion;
				long lastWaitCatalogVersion = 0L;
				final AtomicReference<OffsetDateTime> overloaded = new AtomicReference<>();
				do {
					while (overloaded.get() != null && overloaded.get().isAfter(OffsetDateTime.now())) {
						synchronized (this) {
							Thread.sleep(500);
						}
					}

					final AtomicReference<SealedEntity> theEntityRef = new AtomicReference<>();
					final CommitProgress commitProgress = evita.updateCatalogAsync(
						TEST_CATALOG,
						session -> {
							final EntityBuilder entityBuilder = session.getEntity(
									entityProduct, 1, attributeContentAll()
								)
								.map(SealedInstance::openForWrite)
								.orElseThrow()
								.setAttribute(attributeUrl, faker.internet().url())
								.setAttribute(attributeCode, faker.code().isbn10())
								.setAttribute(attributeName, faker.book().title())
								.setAttribute(
									attributePrice, BigDecimal.valueOf(faker.number().randomDouble(2, 1, 1000)));
							theEntityRef.set(entityBuilder.toInstance());
							entityBuilder.upsertVia(session);
						}
					);

					// immediately retrieve the entity to be able to use it later
					final SealedEntity theEntity = theEntityRef.get();
					expectedLastVersion = commitProgress
						// fast track - we don't wait for anything (to cause as much "churn" as we can)
						.onWalAppended()
						.thenApply(commitVersions -> {
							versionedEntities.put(commitVersions.catalogVersion(), theEntity);
							return commitVersions.catalogVersion();
						})
						.toCompletableFuture()
						.get();

					commitProgress.onChangesVisible()
						.exceptionally(ex -> {
							log.error("Queues probably full - exception: " + ex.getMessage());
							overloaded.set(OffsetDateTime.now().plusSeconds(10));
							return new CommitVersions(0L, 0);
						});

					if (expectedLastVersion - 1000 > lastWaitCatalogVersion) {
						log.info("Letting the system breathe ...");
						lastWaitCatalogVersion = expectedLastVersion;
						// wait a while to let the system breathing
						synchronized (this) {
							Thread.sleep(10_000);
						}
					}

				} while (Duration.between(initialStart, LocalDateTime.now()).toMinutes() < input.intervalInMinutes());

				// check there is a first record
				final TriConsumer<EvitaContract, Long, String> catalogChecker = (theEvita, expectedCatalogVersion, catalogName) -> theEvita.queryCatalog(
					catalogName,
					evitaSessionContract -> {
						final long catalogVersion = evitaSessionContract.getCatalogVersion();
						assertEquals(
							expectedCatalogVersion, catalogVersion, "Catalog version should match the expected one!");

						final SealedEntity expectedEntity = versionedEntities.get(catalogVersion);
						final Optional<SealedEntity> entity = evitaSessionContract.getEntity(
							entityProduct, 1, attributeContentAll());
						assertTrue(entity.isPresent(), "Entity should be present in the catalog!");
						final SealedEntity realEntity = entity.get();
						assertEquals(
							expectedEntity.getAttribute(attributeCode, String.class),
							realEntity.getAttribute(attributeCode, String.class),
							"Entity code at version " + catalogVersion + " should match the expected one!"
						);
						assertEquals(
							expectedEntity.getAttribute(attributeName, String.class),
							realEntity.getAttribute(attributeName, String.class),
							"Entity name at version " + catalogVersion + " should match the expected one!"
						);
						assertEquals(
							expectedEntity.getAttribute(attributePrice, BigDecimal.class),
							realEntity.getAttribute(attributePrice, BigDecimal.class),
							"Entity price at version " + catalogVersion + " should match the expected one!"
						);
						assertEquals(
							expectedEntity.getAttribute(attributeUrl, String.class),
							realEntity.getAttribute(attributeUrl, String.class),
							"Entity URL at version " + catalogVersion + " should match the expected one!"
						);
					}
				);

				log.info("Waiting for catalog version " + expectedLastVersion + " to be processed.");

				final LocalDateTime wait = LocalDateTime.now();
				long lastReportedCatalogVersion = 0L;
				do {
					final Long currentCatalogVersion = evita.queryCatalog(
						TEST_CATALOG,
						EvitaSessionContract::getCatalogVersion
					);
					if (currentCatalogVersion >= expectedLastVersion) {
						log.info("Catalog version " + expectedLastVersion + " finally processed.");
						catalogChecker.accept(evita, expectedLastVersion, TEST_CATALOG);
						break;
					} else if (currentCatalogVersion - 500 > lastReportedCatalogVersion) {
						lastReportedCatalogVersion = currentCatalogVersion;
						log.info(
							"Waiting for catalog version " + expectedLastVersion + " to be processed (current " + currentCatalogVersion + ").");
					}
					Thread.onSpinWait();
				} while (Duration.between(wait, LocalDateTime.now()).toMinutes() < 1);

				final Long currentCatalogVersion = evita.queryCatalog(
					TEST_CATALOG,
					EvitaSessionContract::getCatalogVersion
				);
				log.info("Current catalog version: " + currentCatalogVersion);

				assertTrue(expectedLastVersion > 10L, "At least 10 versions should be created!");

				// close the evita
				evita.close();

				log.info("Re-initializing evita to verify persistence of data.");

				try (final Evita restartedEvita = new Evita(evita.getConfiguration())) {
					restartedEvita.waitUntilFullyInitialized();

					assertInstanceOf(
						Catalog.class, restartedEvita.getCatalogInstance(TEST_CATALOG).orElseThrow(),
						"Catalog should be loaded from the disk!"
					);

					log.info("evitaDB restarted and fully initialized");

					catalogChecker.accept(restartedEvita, expectedLastVersion, TEST_CATALOG);

					// read entire history
					try (
						final Stream<CatalogBootstrap> bootstrapStream = DefaultCatalogPersistenceService.getCatalogBootstrapRecordStream(
							TEST_CATALOG,
							// bootstrap records must never be compressed
							new StorageSettings(
								StorageOptions.builder(restartedEvita.getConfiguration().storage())
									.compress(false)
									.build(),
								TransactionOptions.builder()
									.build()
							)
						)
					) {
						bootstrapStream.forEach(
							record -> {
								try {
									log.info("Bootstrap record: " + record);
									// create backup from each point in time
									final Path backupPath = restartedEvita.management().backupCatalog(
											TEST_CATALOG, null, record.catalogVersion(), false)
										.get(2, TimeUnit.MINUTES).path(
											((FileSystemExportOptions) evita.getConfiguration()
												.export()).getDirectory());
									// restore it to unique new catalog
									final String restoredCatalogName = TEST_CATALOG + "_restored_" + record.catalogVersion();
									try (final InputStream inputStream = Files.newInputStream(backupPath)) {
										restartedEvita.management().restoreCatalog(
												restoredCatalogName, Files.size(backupPath), inputStream)
											.getFutureResult().get(2, TimeUnit.MINUTES);
										restartedEvita.activateCatalog(restoredCatalogName);
									}
									// connect to it and check existence of the first record
									catalogChecker.accept(restartedEvita, record.catalogVersion(), restoredCatalogName);

									// drop the restored catalog
									restartedEvita.deleteCatalogIfExists(restoredCatalogName);
								} catch (Exception e) {
									log.error("Exception thrown during backup & restore test!", e);
									throw new RuntimeException(e);
								}
							}
						);
					}
				}
			} catch (Exception ex) {
				log.error("Exception thrown within test!", ex);
				fail(ex);
			} finally {
				log.info("Closing evita instance (state is {}).", evita.isActive() ? "active" : "closed");
				if (evita.isActive()) {
					evita.close();
				}
			}
		} finally {
			log.info("Cleaning test directories.");
			cleanTestSubDirectory("shouldCorrectlyRotateAllFiles");
			cleanTestSubDirectory("shouldCorrectlyRotateAllFiles_export");
		}
	}

	@Tag(SLOW)
	@ParameterizedTest(name = "This test verifies, that all data files are correctly rotated and compacted.")
	@ArgumentsSource(TimeArgumentProvider.class)
	void shouldCorrectlyRotateAllFilesManyItems(GenerationalTestInput input) throws Exception {
		final Path testDirectory = getTestDirectory().resolve("shouldCorrectlyRotateAllFiles");
		final Path testDirectoryExport = getTestDirectory().resolve("shouldCorrectlyRotateAllFiles_export");
		cleanTestSubDirectory("shouldCorrectlyRotateAllFiles");
		cleanTestSubDirectory("shouldCorrectlyRotateAllFiles_export");

		try {
			final Evita evita = new Evita(
				EvitaConfiguration.builder()
					.storage(
						StorageOptions.builder()
							.storageDirectory(testDirectory)
							.minimalActiveRecordShare(0.9)
							.fileSizeCompactionThresholdBytes(250_000)
							.timeTravelEnabled(true)
							.compress(true)
							.build()
					)
					.export(
						FileSystemExportOptions.builder()
							.directory(testDirectoryExport)
							.build()
					)
					.transaction(
						TransactionOptions.builder()
							.walFileCountKept(2)
							.build()
					)
					.server(
						ServerOptions.builder()
							.queryTimeoutInMilliseconds(-1)
							.transactionTimeoutInMilliseconds(-1)
							.closeSessionsAfterSecondsOfInactivity(-1)
							.build()
					)
					.build()
			);

			try {
				final String entityProduct = "product";
				final String attributeUrl = "url";
				final String attributeCode = "code";
				final String attributeName = "name";
				final String attributePrice = "price";

				final Faker faker = new Faker(new Random(input.randomSeed()));
				evita.defineCatalog(TEST_CATALOG)
					.updateAndFetchViaNewSession(evita)
					.openForWrite()
					.withAttribute(attributeUrl, String.class)
					.withEntitySchema(
						entityProduct, productSchema -> productSchema
							.withoutGeneratedPrimaryKey()
							.withGlobalAttribute(attributeUrl)
							.withAttribute(attributeCode, String.class)
							.withAttribute(attributeName, String.class)
							.withAttribute(attributePrice, BigDecimal.class)
					)
					.updateViaNewSession(evita);
				evita.updateCatalog(
					TEST_CATALOG, EvitaSessionContract::goLiveAndClose
				);

				final LocalDateTime initialStart = LocalDateTime.now();
				final ConcurrentHashMap<Integer, SealedEntity> entities = new ConcurrentHashMap<>();

				Long expectedLastVersion = 0L;
				final AtomicInteger updates = new AtomicInteger(0);
				do {
					evita.updateCatalog(
						TEST_CATALOG,
						session -> {
							for (int i = 0; i < 1000; i++) {
								if (entities.size() < 10000 || faker.random().nextBoolean()) {
									final EntityReferenceContract ref = session.createNewEntity(
											entityProduct, entities.size() + 1)
										.setAttribute(attributeUrl, faker.internet().url())
										.setAttribute(attributeCode, faker.code().isbn10())
										.setAttribute(attributeName, faker.book().title())
										.setAttribute(
											attributePrice, BigDecimal.valueOf(faker.number().randomDouble(2, 1, 1000)))
										.upsertVia(session);
									final int pk = ref.getPrimaryKeyOrThrowException();
									entities.put(
										pk, session.getEntity(entityProduct, pk, attributeContentAll()).orElseThrow());
								} else {
									updates.incrementAndGet();
									final int entityPrimaryKey = faker.random().nextInt(entities.size()) + 1;
									final EntityBuilder entityBuilder = session.getEntity(
											entityProduct, entityPrimaryKey, attributeContentAll()
										)
										.map(SealedInstance::openForWrite)
										.orElseThrow(() -> new IllegalStateException(
											"Entity with primary key " + entityPrimaryKey + " not found!"))
										.setAttribute(attributeUrl, faker.internet().url())
										.setAttribute(attributeCode, faker.code().isbn10())
										.setAttribute(attributeName, faker.book().title())
										.setAttribute(
											attributePrice,
											BigDecimal.valueOf(faker.number().randomDouble(2, 1, 1000))
										);
									entityBuilder.upsertVia(session);

									entities.put(entityPrimaryKey, entityBuilder.toInstance());
								}
							}
						}
					);

				} while (Duration.between(initialStart, LocalDateTime.now()).toMinutes() < input.intervalInMinutes());

				// check there is a first record
				final TriConsumer<EvitaContract, Integer, String> catalogChecker = (theEvita, primaryKey, catalogName) -> theEvita.queryCatalog(
					catalogName,
					evitaSessionContract -> {
						final long catalogVersion = evitaSessionContract.getCatalogVersion();

						final SealedEntity expectedEntity = entities.get(primaryKey);
						final Optional<SealedEntity> entity = evitaSessionContract.getEntity(
							entityProduct, primaryKey, attributeContentAll());
						assertTrue(entity.isPresent(), "Entity should be present in the catalog!");
						final SealedEntity realEntity = entity.get();
						assertEquals(
							expectedEntity.getAttribute(attributeCode, String.class),
							realEntity.getAttribute(attributeCode, String.class),
							"Entity code at version " + catalogVersion + " should match the expected one!"
						);
						assertEquals(
							expectedEntity.getAttribute(attributeName, String.class),
							realEntity.getAttribute(attributeName, String.class),
							"Entity name at version " + catalogVersion + " should match the expected one!"
						);
						assertEquals(
							expectedEntity.getAttribute(attributePrice, BigDecimal.class),
							realEntity.getAttribute(attributePrice, BigDecimal.class),
							"Entity price at version " + catalogVersion + " should match the expected one!"
						);
						assertEquals(
							expectedEntity.getAttribute(attributeUrl, String.class),
							realEntity.getAttribute(attributeUrl, String.class),
							"Entity URL at version " + catalogVersion + " should match the expected one!"
						);
					}
				);

				log.info("Waiting for catalog version " + expectedLastVersion + " to be processed.");

				final LocalDateTime wait = LocalDateTime.now();
				long lastReportedCatalogVersion = 0L;
				do {
					final Long currentCatalogVersion = evita.queryCatalog(
						TEST_CATALOG,
						EvitaSessionContract::getCatalogVersion
					);
					if (currentCatalogVersion >= expectedLastVersion) {
						log.info("Catalog version " + expectedLastVersion + " finally processed.");
						for (Integer entityPrimaryKey : entities.keySet()) {
							catalogChecker.accept(evita, entityPrimaryKey, TEST_CATALOG);
						}
						break;
					} else if (currentCatalogVersion - 500 > lastReportedCatalogVersion) {
						lastReportedCatalogVersion = currentCatalogVersion;
						log.info(
							"Waiting for catalog version " + expectedLastVersion + " to be processed (current " + currentCatalogVersion + ").");
					}
					Thread.onSpinWait();
				} while (Duration.between(wait, LocalDateTime.now()).toMinutes() < 1);

				final Long currentCatalogVersion = evita.queryCatalog(
					TEST_CATALOG,
					EvitaSessionContract::getCatalogVersion
				);
				log.info(
					"Current catalog version: " + currentCatalogVersion + ", entities: " + entities.size() + ", updates: " + updates.get());

				// close the evita
				evita.close();

				try (final Evita restartedEvita = new Evita(evita.getConfiguration())) {
					restartedEvita.waitUntilFullyInitialized();

					assertInstanceOf(
						Catalog.class, restartedEvita.getCatalogInstance(TEST_CATALOG).orElseThrow(),
						"Catalog should be loaded from the disk!"
					);

					for (Integer entityPrimaryKey : entities.keySet()) {
						catalogChecker.accept(restartedEvita, entityPrimaryKey, TEST_CATALOG);
					}
				}
			} catch (Exception ex) {
				log.error("Exception thrown within test!", ex);
				fail(ex);
			} finally {
				if (evita.isActive()) {
					evita.close();
				}
			}
		} finally {
			cleanTestSubDirectory("shouldCorrectlyRotateAllFiles");
			cleanTestSubDirectory("shouldCorrectlyRotateAllFiles_export");
		}
	}


	/**
	 * Generates and upserts a single entity using the data generator with the given schema.
	 *
	 * @param session      the evita session to use for upserting
	 * @param entitySchema the schema of the entity to generate
	 * @param seed         the random seed for reproducible generation
	 * @return the upserted sealed entity
	 */
	@Nonnull
	private SealedEntity createSingleEntity(
		@Nonnull EvitaSessionContract session,
		@Nonnull SealedEntitySchema entitySchema,
		int seed
	) {
		final BiFunction<String, Faker, Integer> randomEntityPicker = createRandomEntityPicker(session);
		return this.dataGenerator.generateEntities(entitySchema, randomEntityPicker, seed)
			.limit(1)
			.map(session::upsertAndFetchEntity)
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("Failed to generate entity"));
	}

	/**
	 * Appends synthetic transactions to the Write-Ahead Log (WAL) for testing purposes.
	 * Creates multiple transactions with the specified number of entity mutations per transaction.
	 * <p>
	 * This method:
	 * <ul>
	 *     <li>Generates entities using the data generator</li>
	 *     <li>Writes them to an isolated WAL file</li>
	 *     <li>Appends the isolated WAL to the main catalog WAL</li>
	 *     <li>Returns the generated entities mapped by catalog version</li>
	 * </ul>
	 *
	 * @param baseCatalogVersion   the starting catalog version (transactions will be numbered from this + 1)
	 * @param offHeapMemoryManager the off-heap memory manager to use for WAL operations
	 * @param wal                  the catalog WAL to append to
	 * @param transactionSizes     an array where each element represents the number of mutations in one transaction
	 * @param catalogSchema        the catalog schema to use
	 * @param productSchema        the product schema to use for entity generation
	 * @return a map of catalog versions to their corresponding generated entities
	 */
	@Nonnull
	private Map<Long, List<EntityContract>> appendWal(
		long baseCatalogVersion,
		@Nonnull CatalogOffHeapMemoryManager offHeapMemoryManager,
		@Nonnull CatalogWriteAheadLog wal,
		int[] transactionSizes,
		@Nonnull SealedCatalogSchema catalogSchema,
		@Nonnull EntitySchemaContract productSchema
	) {
		final EvitaSession mockSession = Mockito.mock(EvitaSession.class);
		Mockito.when(mockSession.getCatalogSchema()).thenReturn(catalogSchema);

		final Path isolatedWalFilePath = Path.of(System.getProperty("java.io.tmpdir"))
			.resolve("evita")
			.resolve(getClass().getSimpleName())
			.resolve("isolatedWal.tmp");
		// delete if exists
		isolatedWalFilePath.toFile().delete();

		final DefaultIsolatedWalService walPersistenceService = new DefaultIsolatedWalService(
			TEST_CATALOG,
			UUID.randomUUID(),
			new ConflictResolution(ConflictPolicy.NONE),
			KryoFactory.createKryo(WalKryoConfigurer.INSTANCE),
			new WriteOnlyOffHeapWithFileBackupHandle(
				isolatedWalFilePath,
				StorageOptions.DEFAULT_OUTPUT_BUFFER_SIZE,
				false,
				this.observableOutputKeeper,
				offHeapMemoryManager,
				Crc32CChecksumFactory.INSTANCE,
				CompressionFactory.NO_COMPRESSION
			)
		);

		final Map<Long, List<EntityContract>> entitiesInMutations = CollectionUtils.createHashMap(
			transactionSizes.length);
		for (int i = 0; i < transactionSizes.length; i++) {
			int txSize = transactionSizes[i];
			final LinkedList<InstanceWithMutation> entities = this.dataGenerator.generateEntities(
					productSchema,
					(serializable, faker) -> null,
					42
				)
				.limit(txSize)
				.map(it -> new InstanceWithMutation(it.toInstance(), it.toMutation().orElseThrow()))
				.collect(Collectors.toCollection(LinkedList::new));

			final long catalogVersion = baseCatalogVersion + i + 1;
			for (InstanceWithMutation entity : entities) {
				walPersistenceService.write(catalogVersion, entity.mutation());
			}

			final OffHeapWithFileBackupReference walReference = walPersistenceService.getWalReference();
			final TransactionMutation transactionMutation = new TransactionMutation(
				UUIDUtil.randomUUID(),
				catalogVersion,
				entities.size(),
				walReference.getContentLength(),
				OffsetDateTime.now()
			);
			wal.append(
				transactionMutation,
				walReference
			);

			entitiesInMutations.put(
				catalogVersion,
				entities.stream()
					.map(InstanceWithMutation::instance)
					.toList()
			);
		}
		return entitiesInMutations;
	}

	/**
	 * A record that pairs an entity reference with the catalog version in which it was created or modified.
	 * Used for tracking when entities become visible in the catalog during concurrent operations.
	 * Implements Comparable to allow sorting by entity reference first, then by catalog version.
	 */
	private record PkWithCatalogVersion(
		@Nonnull EntityReferenceContract entityReference,
		long catalogVersion
	) implements Comparable<PkWithCatalogVersion> {

		@Override
		public int compareTo(PkWithCatalogVersion o) {
			final int first = this.entityReference.compareTo(o.entityReference);
			return first == 0 ? Long.compare(this.catalogVersion, o.catalogVersion) : first;
		}

		/**
		 * Returns the entity type of the referenced entity.
		 *
		 * @return the entity type
		 */
		public String getType() {
			return this.entityReference.getType();
		}

		/**
		 * Returns the primary key of the referenced entity.
		 *
		 * @return the primary key
		 */
		public int getPrimaryKey() {
			return this.entityReference.getPrimaryKey();
		}
	}

	/**
	 * An immutable record that represents an entity instance paired with its associated mutation.
	 * Used when generating and writing entities to the WAL, where we need both the final entity state
	 * and the mutation that created it.
	 */
	private record InstanceWithMutation(
		@Nonnull EntityContract instance,
		@Nonnull EntityMutation mutation
	) {
	}

}
