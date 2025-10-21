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
import io.evitadb.api.exception.ReadOnlyException;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.InstanceEditor;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.SealedInstance;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.system.MaterializedVersionBlock;
import io.evitadb.api.requestResponse.system.TimeFlow;
import io.evitadb.api.requestResponse.system.WriteAheadLogVersionDescriptor;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.Catalog;
import io.evitadb.core.Evita;
import io.evitadb.core.EvitaSession;
import io.evitadb.core.Transaction;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.function.Functions;
import io.evitadb.function.TriConsumer;
import io.evitadb.function.TriFunction;
import io.evitadb.store.catalog.DefaultCatalogPersistenceService;
import io.evitadb.store.catalog.DefaultIsolatedWalService;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.offsetIndex.io.CatalogOffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.WriteOnlyOffHeapWithFileBackupHandle;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.store.spi.IsolatedWalPersistenceService;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
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
import java.util.concurrent.TimeoutException;
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
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.generator.DataGenerator.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test aims to test transactional behavior of evitaDB.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("Evita entity transactional functionality")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
public class EvitaTransactionalFunctionalTest implements EvitaTestSupport {
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
	private static final Pattern DATE_TIME_PATTERN_1 = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?\\+\\d{2}:\\d{2}");
	private static final Pattern DATE_TIME_PATTERN_2 = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z");
	private static final Pattern LAG_PATTERN = Pattern.compile("lag -?\\d*m?s");
	private static final Pattern UUID_PATTERN = Pattern.compile("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
	private static final Supplier<DataGenerator> GENERATOR_FACTORY = () -> new DataGenerator.Builder()
		.withCurrencies(CURRENCY_CZK)
		.withPriceLists(PRICE_LIST_BASIC)
		.build();
	private final DataGenerator dataGenerator = GENERATOR_FACTORY.get();
	private final Pool<Kryo> catalogKryoPool = new Pool<>(false, false, 1) {
		@Override
		protected Kryo create() {
			return KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		}
	};
	private final ObservableOutputKeeper observableOutputKeeper = new ObservableOutputKeeper(
		TEST_CATALOG,
		StorageOptions.temporary(),
		Mockito.mock(Scheduler.class)
	);
	private final CatalogOffHeapMemoryManager offHeapMemoryManager = new CatalogOffHeapMemoryManager(TEST_CATALOG, 10_000_000, 128);

	/**
	 * Verifies the contents of the catalog in the given Evita instance.
	 *
	 * @param secondInstance    The Evita instance to verify.
	 * @param generatedEntities The map of generated entities for verification.
	 * @param expectedVersion   The expected version of the catalog.
	 * @return The catalog version after verification.
	 */
	private static long verifyCatalogContents(
		@Nonnull Evita secondInstance,
		@Nonnull Map<Long, List<EntityContract>> generatedEntities,
		long expectedVersion
	) {
		long catalogVersion = 0L;
		for (int i = 0; i < 100_000; i++) {
			catalogVersion = secondInstance.queryCatalog(
				TEST_CATALOG,
				EvitaSessionContract::getCatalogVersion
			);
			if (catalogVersion == expectedVersion) {
				// the WAL has been processed
				secondInstance.queryCatalog(
					TEST_CATALOG,
					session -> {
						generatedEntities.values().stream()
							.flatMap(List::stream)
							.forEach(entity -> {
								final Optional<SealedEntity> fetchedEntity = session.getEntity(
									entity.getType(), entity.getPrimaryKey(), QueryConstraints.entityFetchAllContent()
								);
								assertTrue(fetchedEntity.isPresent());
								assertFalse(entity.differsFrom(fetchedEntity.get()));
							});
					}
				);

				break;
			}
		}
		return catalogVersion;
	}

	/**
	 * Replaces timestamps in ISO OFFSET DATE TIME format (2024-02-26T14:48:54.984) to a placeholder.
	 * We need to do this in order to get the repeatable test results.
	 *
	 * @param textWithTimestamps The text with timestamps to replace.
	 * @return the text with timestamps replaced.
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
	 * Replaces occurrences of "lag 123ms" pattern in the given text with the string "REPLACED_LAG".
	 * We need to do this in order to get the repeatable test results.
	 *
	 * @param textWithLag the input text that may contain occurrences of "lag 123ms" pattern
	 * @return the updated text with occurrences of "lag 123ms" pattern replaced by "REPLACED_LAG"
	 */
	@Nonnull
	private static String replaceLag(@Nonnull String textWithLag) {
		// the pattern is in the form of lag 123ms
		return LAG_PATTERN.matcher(textWithLag).replaceAll("REPLACED_LAG");
	}

	/**
	 * Creates new products in massive parallelism and verifies that the entities are visible in the catalog according
	 * to SNAPSHOT isolation level.
	 *
	 * @param evita                 The Evita instance to use.
	 * @param productSchema         The product schema to use.
	 * @param applyOnceWhileWaiting The lambda to apply once while waiting for the entities to be inserted.
	 * @return The set of primary keys with catalog versions.
	 * @throws Exception If something goes wrong.
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
							final AtomicReference<EntityReference> createdReference = new AtomicReference<>();
							final CompletableFuture<CommitVersions> targetCatalogVersion = evita.updateCatalogAsync(
									TEST_CATALOG,
									session -> {
										final long currentCatalogVersion = session.getCatalogVersion();
										createdReference.set(session.upsertEntity(it));

										// verify that no entity with older transaction id is visible - i.e. SNAPSHOT isolation level
										for (PkWithCatalogVersion existingPk : primaryKeysWithTxIds) {
											final SealedEntity fetchedEntity = session.getEntity(existingPk.getType(), existingPk.getPrimaryKey()).orElse(null);
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
								final PkWithCatalogVersion pkWithCatalogVersion = new PkWithCatalogVersion(createdReference.get(), catalogVersion);
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
				evita.queryCatalog(TEST_CATALOG, EvitaSessionContract::getCatalogVersion) < numberOfThreads * iterations + 1
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

		assertEquals(primaryKeysWithTxIds.size(), numberOfThreads * iterations);
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

	/**
	 * Method returns number of WAL files in the catalog directory.
	 *
	 * @param catalogPath path to the catalog directory
	 * @return number of WAL files
	 * @throws IOException when the directory cannot be read
	 */
	private static int numberOfWalFiles(@Nonnull Path catalogPath) throws IOException {
		try (final Stream<Path> list = Files.list(catalogPath)) {
			return list
				.filter(it -> it.getFileName().toString().endsWith(CatalogPersistenceService.WAL_FILE_SUFFIX))
				.mapToInt(it -> 1)
				.sum();
		}
	}

	/**
	 * Method returns first index of the catalog data file in the catalog directory.
	 *
	 * @param catalogPath path to the catalog directory
	 * @return first index found
	 * @throws IOException when the directory cannot be read
	 */
	private static int firstIndexOfCatalogDataFile(@Nonnull Path catalogPath) throws IOException {
		try (final Stream<Path> list = Files.list(catalogPath)) {
			return list
				.filter(it -> it.getFileName().toString().endsWith(CatalogPersistenceService.CATALOG_FILE_SUFFIX))
				.mapToInt(it -> CatalogPersistenceService.getIndexFromCatalogFileName(it.getFileName().toString()))
				.min()
				.orElse(0);
		}
	}

	/**
	 * Method returns first index of the entity data file in the catalog directory.
	 *
	 * @param catalogPath path to the catalog directory
	 * @param entityType  entity type
	 * @return first index found
	 * @throws IOException when the directory cannot be read
	 */
	private static int firstIndexOfCollectionDataFile(@Nonnull Path catalogPath, @Nonnull String entityType) throws IOException {
		try (final Stream<Path> list = Files.list(catalogPath)) {
			return list
				.filter(it -> it.getFileName().toString().endsWith(CatalogPersistenceService.ENTITY_COLLECTION_FILE_SUFFIX) && it.getFileName().toString().toLowerCase().startsWith(entityType.toLowerCase() + "-"))
				.mapToInt(it -> CatalogPersistenceService.getEntityPrimaryKeyAndIndexFromEntityCollectionFileName(it.getFileName().toString()).fileIndex())
				.min()
				.orElseThrow();
		}
	}

	@DataSet(value = TRANSACTIONAL_DATA_SET, readOnly = false)
	SealedEntitySchema setUp(Evita evita) {
		return evita.updateCatalog(TEST_CATALOG, session -> {
			session.updateCatalogSchema(
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.sortable().uniqueGlobally().nullable())
					.withAttribute(ATTRIBUTE_URL, String.class, whichIs -> whichIs.localized().uniqueGlobally().nullable())
			);

			final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(entityType, session, faker);
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
						.updateAndFetchVia(session);
				}
			);
		});
	}

	@AfterEach
	void tearDown() {
		this.observableOutputKeeper.close();
	}

	@DisplayName("Catalog should be automatically updated after a load with existing WAL contents.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldPickUpExistingWalOnStartAndReplayItsContents(Evita evita) {
		// close evita first so that the processing pipeline is shut down
		final EvitaConfiguration cfg = evita.getConfiguration();
		final SealedCatalogSchema catalogSchema = evita.getCatalogInstance(TEST_CATALOG).orElseThrow().getSchema();
		final EntitySchemaContract productSchema = catalogSchema.getEntitySchema(Entities.PRODUCT).orElseThrow();
		evita.close();

		final Path catalogDirectory = cfg.storage().storageDirectory().resolve(TEST_CATALOG);
		final CatalogWriteAheadLog wal = new CatalogWriteAheadLog(
			0L,
			TEST_CATALOG,
			index -> CatalogPersistenceService.getWalFileName(TEST_CATALOG, index),
			catalogDirectory,
			this.catalogKryoPool,
			StorageOptions.builder().build(),
			TransactionOptions.builder().build(),
			Mockito.mock(Scheduler.class),
			Functions.noOpLongConsumer(),
			null
		);

		// create WAL file with a few contents first
		final Map<Long, List<EntityContract>> generatedEntities = appendWal(
			1L, this.offHeapMemoryManager, wal, new int[]{3, 4, 2}, catalogSchema, productSchema
		);

		// start evita again and wait for the WAL to be processed
		final Evita secondInstance = new Evita(cfg);
		secondInstance.waitUntilFullyInitialized();

		// verify the documents in the evitaDB catalog
		final long catalogVersion = verifyCatalogContents(secondInstance, generatedEntities, 4L);
		assertEquals(4L, catalogVersion);

		// now shut down evitaDB again
		secondInstance.close();

		// append a few additional WAL entries
		final Map<Long, List<EntityContract>> additionalGeneratedEntities = appendWal(
			4L, this.offHeapMemoryManager, wal, new int[]{5, 7}, catalogSchema, productSchema
		);

		// start evitaDB again and wait for the WAL to be processed
		final Evita thirdInstance = new Evita(cfg);
		thirdInstance.waitUntilFullyInitialized();

		// verify the documents in the evitaDB catalog
		final long nextCatalogVersion = verifyCatalogContents(thirdInstance, additionalGeneratedEntities, 6L);
		assertEquals(6L, nextCatalogVersion);

		thirdInstance.close();
	}

	@DisplayName("Engine log should be truncated automatically when there is content after current state reference.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldTruncateEngineLogAndStartCorrectly(Evita evita) {
		// ensure there is at least one engine mutation recorded in WAL and EngineState references it
		final EvitaConfiguration cfg = evita.getConfiguration();
		// perform a simple engine-level mutation: make catalog immutable (writes to engine WAL)
		evita.makeCatalogImmutableWithProgress(TEST_CATALOG).onCompletion().toCompletableFuture().join();
		// close evita so files are released
		evita.close();

		// locate engine WAL file in engine storage root directory
		final Path storageDir = cfg.storage().storageDirectory();
		final Optional<Path> walFileOpt;
		try (final Stream<Path> fileListing = Files.list(storageDir);) {
			walFileOpt = fileListing
				.filter(p -> p.getFileName().toString().endsWith(".wal"))
				.findFirst();
		} catch (IOException e) {
			throw new RuntimeException("Failed to list files in storage directory: " + storageDir, e);
		}
		assertTrue(walFileOpt.isPresent(), "Engine WAL file should exist after engine mutation");
		final Path walFile = walFileOpt.get();

		try {
			final long originalSize = Files.size(walFile);
			// append gibberish bytes to the end of the WAL file
			final byte[] gibberish = new byte[]{(byte)0xDE, (byte)0xAD, (byte)0xBE, (byte)0xEF, 0x01, 0x02, 0x03};
			Files.write(walFile, gibberish, java.nio.file.StandardOpenOption.APPEND);
			final long corruptedSize = Files.size(walFile);
			assertTrue(corruptedSize > originalSize, "WAL file size should increase after appending gibberish");

			// start evita again - it should truncate the WAL file to the recorded end position and start correctly
			final Evita restarted = new Evita(cfg);
			restarted.waitUntilFullyInitialized();

			// verify engine is operational by performing a simple read from the catalog
			restarted.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(session.getAllEntityTypes().contains(Entities.PRODUCT));
				}
			);

			// verify catalog is immutable (last successful mutation was to make catalog immutable)
			assertThrows(
				ReadOnlyException.class,
				() -> restarted.updateCatalog(
					TEST_CATALOG,
					session -> {
						fail("Catalog should be immutable, but update was allowed!");
					}
				)
			);

			// verify the WAL file has been truncated back to the original size
			final long truncatedSize = Files.size(walFile);
			assertEquals(originalSize, truncatedSize, "WAL should be truncated back to original size");

			restarted.close();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@DisplayName("Catalog history should be aggregated correctly.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Tag(LONG_RUNNING_TEST)
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
				index -> CatalogPersistenceService.getWalFileName(TEST_CATALOG, index),
				catalogDirectory,
				this.catalogKryoPool,
				StorageOptions.builder().build(),
				TransactionOptions.builder().build(),
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
							Catalog version: 59, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 59, committed at REPLACED_OFFSET_DATE_TIME(3 mutations, 1 KB):
								 - changes in `PRODUCT`: 3 upserted entities
							Catalog version: 54, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 54, committed at REPLACED_OFFSET_DATE_TIME(4 mutations, 1 KB):
								 - changes in `PRODUCT`: 4 upserted entities
							Catalog version: 50, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 50, committed at REPLACED_OFFSET_DATE_TIME(3 mutations, 1 KB):
								 - changes in `PRODUCT`: 3 upserted entities
							Catalog version: 45, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 45, committed at REPLACED_OFFSET_DATE_TIME(4 mutations, 2 KB):
								 - changes in `PRODUCT`: 4 upserted entities
							Catalog version: 41, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 41, committed at REPLACED_OFFSET_DATE_TIME(4 mutations, 2 KB):
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
							Catalog version: 42, processed at REPLACED_OFFSET_DATE_TIME in reversible transaction REPLACED_UUID, changes: Transaction to version: 42, committed at REPLACED_OFFSET_DATE_TIME(5 mutations, 2 KB):
								 - changes in `PRODUCT`: 5 upserted entities
							Catalog version: 43, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 43, committed at REPLACED_OFFSET_DATE_TIME(1 mutations, 566 B):
								 - changes in `PRODUCT`: 1 upserted entities
							Catalog version: 51, processed at REPLACED_OFFSET_DATE_TIME in reversible transaction REPLACED_UUID, changes: Transaction to version: 51, committed at REPLACED_OFFSET_DATE_TIME(2 mutations, 1 KB):
								 - changes in `PRODUCT`: 2 upserted entities
							Catalog version: 55, processed at REPLACED_OFFSET_DATE_TIME in reversible transaction REPLACED_UUID, changes: Transaction to version: 55, committed at REPLACED_OFFSET_DATE_TIME(1 mutations, 590 B):
								 - changes in `PRODUCT`: 1 upserted entities
							Catalog version: 59, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 59, committed at REPLACED_OFFSET_DATE_TIME(3 mutations, 1 KB):
								 - changes in `PRODUCT`: 3 upserted entities
							Catalog version: 46, processed at REPLACED_OFFSET_DATE_TIME in reversible transaction REPLACED_UUID, changes: Transaction to version: 46, committed at REPLACED_OFFSET_DATE_TIME(4 mutations, 2 KB):
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
							Catalog version: 36, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 36, committed at REPLACED_OFFSET_DATE_TIME(4 mutations, 2 KB):
								 - changes in `PRODUCT`: 4 upserted entities
							Catalog version: 32, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 32, committed at REPLACED_OFFSET_DATE_TIME(5 mutations, 2 KB):
								 - changes in `PRODUCT`: 5 upserted entities
							Catalog version: 27, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 27, committed at REPLACED_OFFSET_DATE_TIME(1 mutations, 606 B):
								 - changes in `PRODUCT`: 1 upserted entities
							Catalog version: 23, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 23, committed at REPLACED_OFFSET_DATE_TIME(4 mutations, 2 KB):
								 - changes in `PRODUCT`: 4 upserted entities
							Catalog version: 18, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 18, committed at REPLACED_OFFSET_DATE_TIME(4 mutations, 2 KB):
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
							Catalog version: 14, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 14, committed at REPLACED_OFFSET_DATE_TIME(4 mutations, 2 KB):
								 - changes in `PRODUCT`: 4 upserted entities
							Catalog version: 9, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 9, committed at REPLACED_OFFSET_DATE_TIME(2 mutations, 1019 B):
								 - changes in `PRODUCT`: 2 upserted entities
							Catalog version: 4, processed at REPLACED_OFFSET_DATE_TIME in transaction REPLACED_UUID, changes: Transaction to version: 4, committed at REPLACED_OFFSET_DATE_TIME(2 mutations, 1 KB):
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

				final MaterializedVersionBlock firstCatalogVersionBlock = catalog.getCatalogVersionAt(null);
				assertEquals(0, firstCatalogVersionBlock.startVersion());
				assertEquals(0, firstCatalogVersionBlock.endVersion());
				assertNotNull(firstCatalogVersionBlock.introducedAt());

				final MaterializedVersionBlock lastCatalogVersionBlock = catalog.getCatalogVersionAt(OffsetDateTime.now());
				assertEquals(59, lastCatalogVersionBlock.startVersion());
				assertEquals(59, lastCatalogVersionBlock.endVersion());
				assertNotNull(lastCatalogVersionBlock.introducedAt());
			}
		}
	}

	@DisplayName("Update catalog with another product - synchronously.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldUpdateCatalogWithAnotherProduct(EvitaContract evita, SealedEntitySchema productSchema) {
		final SealedEntity addedEntity = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(entityType, session, faker);
				final Optional<SealedEntity> upsertedEntity = this.dataGenerator.generateEntities(productSchema, randomEntityPicker, SEED)
					.limit(1)
					.map(session::upsertAndFetchEntity)
					.findFirst();
				assertTrue(upsertedEntity.isPresent());
				return upsertedEntity.get();
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Optional<SealedEntity> fetchedEntity = session.getEntity(productSchema.getName(), addedEntity.getPrimaryKey());
				assertTrue(fetchedEntity.isPresent());
				assertEquals(addedEntity, fetchedEntity.get());
			}
		);
	}

	@DisplayName("Update catalog with another product - asynchronously.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldUpdateCatalogWithAnotherProductAsynchronously(EvitaContract evita, SealedEntitySchema productSchema) throws ExecutionException, InterruptedException, TimeoutException {
		final CompletableFuture<SealedEntity> addedEntity = evita.updateCatalogAsync(
			TEST_CATALOG,
			session -> {
				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(entityType, session, faker);
				final Optional<SealedEntity> upsertedEntity = this.dataGenerator.generateEntities(productSchema, randomEntityPicker, SEED)
					.limit(1)
					.map(session::upsertAndFetchEntity)
					.findFirst();
				assertTrue(upsertedEntity.isPresent());
				return upsertedEntity.get();
			},
			CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION
		).toCompletableFuture();

		while (!addedEntity.isDone()) {
			Thread.onSpinWait();
		}

		final Integer addedEntityPrimaryKey = addedEntity.get(1, TimeUnit.SECONDS).getPrimaryKey();
		boolean expectedResult = false;
		for (int i = 0; i < 100_000; i++) {
			//noinspection NonShortCircuitBooleanExpression
			expectedResult = expectedResult | evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final Optional<SealedEntity> entityFetchedAgain = session.getEntity(productSchema.getName(), addedEntityPrimaryKey);
					return entityFetchedAgain.isPresent();
				}
			);
		}

		assertTrue(expectedResult, "Entity not found in catalog!");
	}

	@DisplayName("Update rollback transaction in manually opened session when exception is thrown.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldAutomaticallyRollbackTheTransactionWhenExceptionIsThrownInManuallyOpenedSession(EvitaContract evita, SealedEntitySchema productSchema) {
		final EvitaSessionContract session = evita.createSession(new SessionTraits(TEST_CATALOG, CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, SessionFlags.READ_WRITE));

		final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(entityType, session, faker);
		final Optional<EntityMutation> entityMutation = this.dataGenerator.generateEntities(productSchema, randomEntityPicker, SEED)
			.findFirst()
			.flatMap(InstanceEditor::toMutation);
		assertTrue(entityMutation.isPresent());

		final SealedEntity addedEntity = session.upsertAndFetchEntity(entityMutation.get(), entityFetchAllContent());

		try {
			session.upsertEntity(entityMutation.get());
			fail("Exception should be thrown (duplicate values)!");
		} catch (Exception ex) {
			// yes, we expect exception
		}

		try {
			session.close();
			fail("Exception should be thrown (rollback)!");
		} catch (RollbackException ex) {
			// yes, we expect exception on rollback that documents that the evitaDB automatically rolled back the transaction
		}

		evita.queryCatalog(
			TEST_CATALOG,
			theNewSession -> {
				final Optional<SealedEntity> fetchedEntity = theNewSession.getEntity(productSchema.getName(), addedEntity.getPrimaryKey());
				assertFalse(fetchedEntity.isPresent());
			}
		);
	}

	@DisplayName("Update rollback transaction in manually opened session when exception is thrown.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldAutomaticallyRollbackTheTransactionWhenExceptionIsThrownInLambda(EvitaContract evita, SealedEntitySchema productSchema) {
		final AtomicReference<SealedEntity> addedEntity = new AtomicReference<>();
		try {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(entityType, session, faker);
					final Optional<EntityMutation> entityMutation = this.dataGenerator.generateEntities(productSchema, randomEntityPicker, SEED)
						.findFirst()
						.flatMap(InstanceEditor::toMutation);
					assertTrue(entityMutation.isPresent());

					addedEntity.set(session.upsertAndFetchEntity(entityMutation.get(), entityFetchAllContent()));
					// this call will throw an exception
					session.upsertEntity(entityMutation.get());
					fail("Exception should be thrown (duplicate values)!");
				}
			);
		} catch (EvitaInvalidUsageException ex) {
			// yes, we expect exception (duplicate values)
		}

		evita.queryCatalog(
			TEST_CATALOG,
			theNewSession -> {
				final Optional<SealedEntity> fetchedEntity = theNewSession.getEntity(productSchema.getName(), addedEntity.get().getPrimaryKey());
				assertFalse(fetchedEntity.isPresent());
			}
		);
	}

	@DisplayName("Don't rollback action when exception is throw and caught in lambda.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldNotRollbackTheTransactionWhenExceptionIsThrownAndCaughtInLambda(EvitaContract evita, SealedEntitySchema productSchema) {
		final SealedEntity addedEntity = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(entityType, session, faker);
				final Optional<EntityMutation> entityMutation = this.dataGenerator.generateEntities(productSchema, randomEntityPicker, SEED)
					.findFirst()
					.flatMap(InstanceEditor::toMutation);
				assertTrue(entityMutation.isPresent());

				final SealedEntity result = session.upsertAndFetchEntity(entityMutation.get(), entityFetchAllContent());

				try {
					session.upsertEntity(entityMutation.get());
					fail("Exception should be thrown (duplicate values)!");
				} catch (Exception ex) {
					// yes, we expect exception
				}

				return result;
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Optional<SealedEntity> fetchedEntity = session.getEntity(productSchema.getName(), addedEntity.getPrimaryKeyOrThrowException());
				assertTrue(fetchedEntity.isPresent());
				assertEquals(addedEntity, fetchedEntity.get());
			}
		);
	}

	@DisplayName("Update catalog with another product - synchronously using runnable.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldUpdateCatalogWithAnotherProductUsingRunnable(EvitaContract evita, SealedEntitySchema productSchema) {
		final AtomicReference<SealedEntity> addedEntity = new AtomicReference<>();
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(entityType, session, faker);
				final Optional<SealedEntity> upsertedEntity = this.dataGenerator.generateEntities(productSchema, randomEntityPicker, SEED)
					.limit(1)
					.map(session::upsertAndFetchEntity)
					.findFirst();
				assertTrue(upsertedEntity.isPresent());
				addedEntity.set(upsertedEntity.get());
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity theEntity = addedEntity.get();
				final Optional<SealedEntity> fetchedEntity = session.getEntity(productSchema.getName(), theEntity.getPrimaryKey());
				assertTrue(fetchedEntity.isPresent());
				assertEquals(theEntity, fetchedEntity.get());
			}
		);
	}

	@DisplayName("Update catalog with another product - asynchronously using runnable.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldUpdateCatalogWithAnotherProductAsynchronouslyUsingRunnable(EvitaContract evita, SealedEntitySchema productSchema) {
		final CommitVersions nonSenseValue = new CommitVersions(Long.MIN_VALUE, Integer.MIN_VALUE);
		final AtomicReference<SealedEntity> addedEntity = new AtomicReference<>();
		final CompletableFuture<CommitVersions> nextCatalogVersion = evita.updateCatalogAsync(
				TEST_CATALOG,
				session -> {
					final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(entityType, session, faker);
					final Optional<SealedEntity> upsertedEntity = this.dataGenerator.generateEntities(productSchema, randomEntityPicker, SEED)
						.limit(1)
						.map(session::upsertAndFetchEntity)
						.findFirst();
					assertTrue(upsertedEntity.isPresent());
					addedEntity.set(upsertedEntity.get());
				}
			)
			.onConflictResolved()
			.toCompletableFuture();

		final int addedEntityPrimaryKey = addedEntity.get().getPrimaryKeyOrThrowException();
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final long catalogVersion = session.getCatalogVersion();
				if (nextCatalogVersion.isDone() && nextCatalogVersion.getNow(nonSenseValue).catalogVersion() == catalogVersion) {
					// the entity is already propagated to indexes
					final Optional<SealedEntity> fetchedEntity = session.getEntity(productSchema.getName(), addedEntityPrimaryKey);
					assertTrue(fetchedEntity.isPresent());
				} else {
					// the entity will not yet be propagated to indexes
					final Optional<SealedEntity> fetchedEntity = session.getEntity(productSchema.getName(), addedEntityPrimaryKey);
					assertTrue(fetchedEntity.isEmpty());
				}
			}
		);

		boolean expectedResult = false;
		for (int i = 0; i < 10_000; i++) {
			//noinspection NonShortCircuitBooleanExpression
			expectedResult = expectedResult | evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final Optional<SealedEntity> entityFetchedAgain = session.getEntity(productSchema.getName(), addedEntityPrimaryKey);
					final long catalogVersion = session.getCatalogVersion();
					final long expectedCatalogVersion = nextCatalogVersion.getNow(nonSenseValue).catalogVersion();
					if (entityFetchedAgain.isPresent()) {
						assertEquals(expectedCatalogVersion, catalogVersion);
						return true;
					} else {
						// we must try again to see if the entity is present, because it happens asynchronously
						// the catalog version might have been updated between fetch and version fetch
						assertTrue(
							catalogVersion < expectedCatalogVersion || expectedCatalogVersion == Long.MIN_VALUE || session.getEntity(productSchema.getName(), addedEntityPrimaryKey).isPresent(),
							"Catalog version should be lower than the one returned by the async operation (observed `" + catalogVersion + "`, next `" + expectedCatalogVersion + "`)!"
						);
						Thread.onSpinWait();
						return false;
					}
				}
			);
		}

		assertTrue(expectedResult, "Entity not found in catalog!");
	}

	@DisplayName("When enough data is written, old data should be removed but time travel is still possible")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldRemoveOldDataFilesAndVerifyTimeTravel(EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		final EvitaConfiguration originalConfiguration = ((Evita) originalEvita).getConfiguration();
		originalEvita.close();

		// reinitialize evita with a specific narrowed WAL limitations
		final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.name(originalConfiguration.name())
				.storage(
					StorageOptions.builder(originalConfiguration.storage())
						.minimalActiveRecordShare(0.9)
						.timeTravelEnabled(true)
						.fileSizeCompactionThresholdBytes(4_096)
						.build()
				)
				.transaction(
					TransactionOptions.builder(originalConfiguration.transaction())
						.walFileSizeBytes(16_384)
						.walFileCountKept(2)
						.build()
				)
				.server(originalConfiguration.server())
				.cache(originalConfiguration.cache())
				.build()
		);
		evita.waitUntilFullyInitialized();

		// insert enough data to rotate WAL more than twice
		for (int i = 0; i < 10; i++) {
			int itCnt = i;
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					final int bulkSize = 16;
					this.dataGenerator.generateEntities(
							productSchema,
							(entityType, faker) -> {
								try (EvitaSessionContract readOnlySession = evita.createReadOnlySession(TEST_CATALOG)) {
									return RANDOM_ENTITY_PICKER.apply(entityType, readOnlySession, faker);
								}
							},
							1
						)
						.limit(bulkSize)
						.forEach(session::upsertEntity);
					// in each following session remove each other entity inserted in previous session
					if (itCnt > 0) {
						for (int j = ((itCnt - 1) * bulkSize) + 1; j <= (itCnt * bulkSize); j += 2) {
							session.deleteEntity(productSchema.getName(), j);
						}
					}
					// and also update the code of the leading entity plus one
					session.getEntity(
						productSchema.getName(),
						((itCnt - 1) * bulkSize) + 2,
						entityFetchAllContent()
					).ifPresent(
						entity -> {
							entity.openForWrite()
								.setAttribute(ATTRIBUTE_CODE, "Iteration #" + itCnt + " modification")
								.upsertVia(session);
						}
					);
					// by this we will be able to verify that the time travel worked as expected
				}
			);
		}

		log.info("Waiting for the WAL to be cleaned up.");

		// we need to wait for the WAL to be cleaned up
		final Path catalogPath = evita.getConfiguration().storage().storageDirectory().resolve(TEST_CATALOG);
		final long start = System.currentTimeMillis();
		do {
			synchronized (this) {
				Thread.sleep(250);
			}
		} while (numberOfWalFiles(catalogPath) > 2 && System.currentTimeMillis() - start < 60_000);

		assertEquals(2, numberOfWalFiles(catalogPath), "There should be only two WAL files left!");

		log.info("WAL cleaned up, letting the system breathe.");

		// and when that happens wait another while to let the other files to be cleaned
		synchronized (this) {
			Thread.sleep(250);
		}

		log.info("Verifying the previous data files were removed as well.");

		// verify that the old data is not present
		assertTrue(firstIndexOfCatalogDataFile(catalogPath) > 0);
		assertTrue(firstIndexOfCollectionDataFile(catalogPath, Entities.PRODUCT) > 0);

		evita.close();
	}

	@DisplayName("Should handle large transaction.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Tag(LONG_RUNNING_TEST)
	@Test
	void shouldHandleLargeTransaction(EvitaContract evita, SealedEntitySchema productSchema) {
		final EvitaSessionContract session = evita.createSession(new SessionTraits(TEST_CATALOG, CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, SessionFlags.READ_WRITE));

		final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(entityType, session, faker);
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
	@Tag(LONG_RUNNING_TEST)
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
				.transaction(
					TransactionOptions.builder()
						.flushFrequencyInMillis(60_000)
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
	@Tag(LONG_RUNNING_TEST)
	@Test
	void shouldAutomaticallyGenerateEntitiesInParallel(EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		final EvitaConfiguration originalConfiguration = ((Evita) originalEvita).getConfiguration();
		originalEvita.close();

		// reinitialize evita with a larger queue size
		try (Evita evita = new Evita(
			EvitaConfiguration.builder()
				.name(originalConfiguration.name())
				.storage(originalConfiguration.storage())
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
			automaticallyGenerateEntitiesInParallel(evita, productSchema, null);
		}
	}

	@DisplayName("Verify code has no problems assigning new PK in concurrent environment and simultaneous backup & restore")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Tag(LONG_RUNNING_TEST)
	@Test
	void shouldBackupAndRestoreCatalogDuringHeavyParallelIndexing(EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		final EvitaConfiguration originalConfiguration = ((Evita) originalEvita).getConfiguration();
		originalEvita.close();

		// reinitialize evita with a larger queue size
		final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.name(originalConfiguration.name())
				.storage(originalConfiguration.storage())
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
				evita, productSchema, theEvita -> {
					lastBackupProcess.set(theEvita.management().backupCatalog(TEST_CATALOG, null, null, false));
				}
			);

			final Path backupFilePath = lastBackupProcess.get().get().path(evita.getConfiguration().storage().exportDirectory());
			assertTrue(backupFilePath.toFile().exists(), "Backup file does not exist!");

			final String restoredCatalogName = TEST_CATALOG + "_restored";
			evita.management().restoreCatalog(restoredCatalogName, Files.size(backupFilePath), Files.newInputStream(backupFilePath))
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
	@Tag(LONG_RUNNING_TEST)
	@Test
	void shouldBackupAndRestoreCatalogDuringHeavyParallelIndexingIncludingWal(EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		final EvitaConfiguration originalConfiguration = ((Evita) originalEvita).getConfiguration();
		originalEvita.close();

		// reinitialize evita with a larger queue size
		final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.name(originalConfiguration.name())
				.storage(originalConfiguration.storage())
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
				evita, productSchema, theEvita -> {
					lastBackupProcess.set(theEvita.management().backupCatalog(TEST_CATALOG, null, null, false));
				}
			);

			final Path backupFilePath = lastBackupProcess.get().get().path(evita.getConfiguration().storage().exportDirectory());
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

	@Tag(LONG_RUNNING_TEST)
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
							.exportDirectory(testDirectoryExport)
							.minimalActiveRecordShare(0.9)
							.fileSizeCompactionThresholdBytes(16_384)
							.timeTravelEnabled(true)
							.build()
					)
					.transaction(
						TransactionOptions.builder()
							.walFileSizeBytes(4_096)
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
					.withEntitySchema(entityProduct, productSchema -> productSchema
						.withoutGeneratedPrimaryKey()
						.withGlobalAttribute(attributeUrl)
						.withAttribute(attributeCode, String.class, thatIs -> thatIs.unique().sortable())
						.withAttribute(attributeName, String.class, thatIs -> thatIs.filterable().sortable())
						.withAttribute(attributePrice, BigDecimal.class, thatIs -> thatIs.filterable().sortable()))
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
								.setAttribute(attributePrice, BigDecimal.valueOf(faker.number().randomDouble(2, 1, 1000)));
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
						assertEquals(expectedCatalogVersion, catalogVersion, "Catalog version should match the expected one!");

						final SealedEntity expectedEntity = versionedEntities.get(catalogVersion);
						final Optional<SealedEntity> entity = evitaSessionContract.getEntity(entityProduct, 1, attributeContentAll());
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
						log.info("Waiting for catalog version " + expectedLastVersion + " to be processed (current " + currentCatalogVersion + ").");
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

				try (final Evita restartedEvita = new Evita(evita.getConfiguration())) {
					restartedEvita.waitUntilFullyInitialized();

					assertInstanceOf(
						Catalog.class, restartedEvita.getCatalogInstance(TEST_CATALOG).orElseThrow(),
						"Catalog should be loaded from the disk!"
					);

					catalogChecker.accept(restartedEvita, expectedLastVersion, TEST_CATALOG);

					// read entire history
					try (
						final Stream<CatalogBootstrap> bootstrapStream = DefaultCatalogPersistenceService.getCatalogBootstrapRecordStream(
							TEST_CATALOG,
							// bootstrap records must never be compressed
							StorageOptions.builder(restartedEvita.getConfiguration().storage())
								.compress(false)
								.build()
						)
					) {
						bootstrapStream.forEach(
							record -> {
								try {
									log.info("Bootstrap record: " + record);
									// create backup from each point in time
									final Path backupPath = restartedEvita.management().backupCatalog(TEST_CATALOG, null, record.catalogVersion(), false)
										.get(2, TimeUnit.MINUTES).path(evita.getConfiguration().storage().exportDirectory());
									// restore it to unique new catalog
									final String restoredCatalogName = TEST_CATALOG + "_restored_" + record.catalogVersion();
									try (final InputStream inputStream = Files.newInputStream(backupPath)) {
										restartedEvita.management().restoreCatalog(restoredCatalogName, Files.size(backupPath), inputStream)
											.getFutureResult().get(2, TimeUnit.MINUTES);
									}
									restartedEvita.activateCatalog(restoredCatalogName);
									// connect to it and check existence of the first record
									catalogChecker.accept(restartedEvita, record.catalogVersion(), restoredCatalogName);
								} catch (Exception e) {
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
				if (evita.isActive()) {
					evita.close();
				}
			}
		} finally {
			cleanTestSubDirectory("shouldCorrectlyRotateAllFiles");
			cleanTestSubDirectory("shouldCorrectlyRotateAllFiles_export");
		}
	}

	@Tag(LONG_RUNNING_TEST)
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
							.exportDirectory(testDirectoryExport)
							.minimalActiveRecordShare(0.9)
							.fileSizeCompactionThresholdBytes(250_000)
							.timeTravelEnabled(true)
							.compress(true)
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
					.withEntitySchema(entityProduct, productSchema -> productSchema
						.withoutGeneratedPrimaryKey()
						.withGlobalAttribute(attributeUrl)
						.withAttribute(attributeCode, String.class)
						.withAttribute(attributeName, String.class)
						.withAttribute(attributePrice, BigDecimal.class))
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
									final EntityReference ref = session.createNewEntity(entityProduct, entities.size() + 1)
										.setAttribute(attributeUrl, faker.internet().url())
										.setAttribute(attributeCode, faker.code().isbn10())
										.setAttribute(attributeName, faker.book().title())
										.setAttribute(attributePrice, BigDecimal.valueOf(faker.number().randomDouble(2, 1, 1000)))
										.upsertVia(session);
									entities.put(ref.primaryKey(), session.getEntity(entityProduct, ref.primaryKey(), attributeContentAll()).orElseThrow());
								} else {
									updates.incrementAndGet();
									final int entityPrimaryKey = faker.random().nextInt(entities.size()) + 1;
									final EntityBuilder entityBuilder = session.getEntity(
											entityProduct, entityPrimaryKey, attributeContentAll()
										)
										.map(SealedInstance::openForWrite)
										.orElseThrow(() -> new IllegalStateException("Entity with primary key " + entityPrimaryKey + " not found!"))
										.setAttribute(attributeUrl, faker.internet().url())
										.setAttribute(attributeCode, faker.code().isbn10())
										.setAttribute(attributeName, faker.book().title())
										.setAttribute(attributePrice, BigDecimal.valueOf(faker.number().randomDouble(2, 1, 1000)));
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
						final Optional<SealedEntity> entity = evitaSessionContract.getEntity(entityProduct, primaryKey, attributeContentAll());
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
						log.info("Waiting for catalog version " + expectedLastVersion + " to be processed (current " + currentCatalogVersion + ").");
					}
					Thread.onSpinWait();
				} while (Duration.between(wait, LocalDateTime.now()).toMinutes() < 1);

				final Long currentCatalogVersion = evita.queryCatalog(
					TEST_CATALOG,
					EvitaSessionContract::getCatalogVersion
				);
				log.info("Current catalog version: " + currentCatalogVersion + ", entities: " + entities.size() + ", updates: " + updates.get());

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
	 * Writes the Write-Ahead Log (WAL) using the provided off-heap memory manager.
	 *
	 * @param offHeapMemoryManager the off-heap memory manager to use
	 * @param transactionSizes     an array of transaction sizes
	 * @return a map of catalog versions to corresponding mutations
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

		final IsolatedWalPersistenceService walPersistenceService = new DefaultIsolatedWalService(
			UUID.randomUUID(),
			KryoFactory.createKryo(WalKryoConfigurer.INSTANCE),
			new WriteOnlyOffHeapWithFileBackupHandle(
				isolatedWalFilePath,
				StorageOptions.temporary(),
				this.observableOutputKeeper,
				offHeapMemoryManager
			)
		);

		final Map<Long, List<EntityContract>> entitiesInMutations = CollectionUtils.createHashMap(transactionSizes.length);
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

 @DisplayName("Should retrieve committed mutation stream in chronological order.")
 @UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
 @Test
 void shouldGetCommittedMutationStream(EvitaContract evita, SealedEntitySchema productSchema) {
 	// Execute 3 transactions with operations
 	for (int i = 0; i < 3; i++) {
 		final int transactionIndex = i;
 		final Long version = evita.updateCatalog(
 			TEST_CATALOG,
 			session -> {
 				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(entityType, session, faker);

 				// Transaction 1: Create 1 entity
 				// Transaction 2: Create 2 entities
 				// Transaction 3: Create 1 entity
 				final int entitiesToCreate = transactionIndex == 1 ? 2 : 1;

 				for (int j = 0; j < entitiesToCreate; j++) {
 					final SealedEntity entity = this.dataGenerator.generateEntities(productSchema, randomEntityPicker, SEED + transactionIndex * 10 + j)
 						.limit(1)
 						.map(session::upsertAndFetchEntity)
 						.findFirst()
 						.orElseThrow();
 				}

 				return session.getCatalogVersion();
 			}
 		);
 	}

 	// Test getCommittedMutationStream starting from version 1
 	try (final Stream<EngineMutation<?>> mutationStream = ((Evita) evita).getCommittedMutationStream(1L)) {
 		final List<EngineMutation<?>> mutations = mutationStream.toList();

 		assertFalse(mutations.isEmpty(), "Mutation stream should not be empty");

 		// Verify we have mutations from all transactions
 		// Each transaction should have a TransactionMutation plus entity mutations
 		assertTrue(mutations.size() >= 3, "Should have mutations from at least 3 transactions");

 		// Filter TransactionMutations to verify transaction order
 		final List<TransactionMutation> transactionMutations = mutations.stream()
 			.filter(TransactionMutation.class::isInstance)
 			.map(TransactionMutation.class::cast)
 			.toList();

 		assertTrue(transactionMutations.size() >= 3, "Should have at least 3 transaction mutations");

 		// Verify chronological order - versions should be increasing
 		for (int i = 1; i < transactionMutations.size(); i++) {
 			final TransactionMutation previous = transactionMutations.get(i - 1);
 			final TransactionMutation current = transactionMutations.get(i);
 			assertTrue(previous.getVersion() < current.getVersion(),
 				"Transaction mutations should be in chronological order (version " + previous.getVersion() + " should be < " + current.getVersion() + ")");
 			assertTrue(previous.getCommitTimestamp().isBefore(current.getCommitTimestamp()) || previous.getCommitTimestamp().equals(current.getCommitTimestamp()),
 				"Transaction mutations should be in chronological order by commit timestamp");
 		}

 		// Get the last 3 transactions (which should be the ones we created in this test)
 		final List<TransactionMutation> lastThreeTransactions = transactionMutations.subList(
 			Math.max(0, transactionMutations.size() - 3),
 			transactionMutations.size()
 		);

 		// Verify we have at least 3 transactions from our test
 		assertTrue(lastThreeTransactions.size() >= 3, "Should have at least 3 test transactions");

 		// Verify that each transaction has a positive mutation count
 		for (TransactionMutation transaction : lastThreeTransactions) {
 			assertTrue(transaction.getMutationCount() > 0, "Each transaction should have at least one mutation");
 		}

 		// Verify we have UPSERT operations for entity creations
 		final long upsertCount = mutations.stream()
 			.filter(mutation -> mutation.operation() == Operation.UPSERT)
 			.count();
 		assertTrue(upsertCount >= 4, "Should have at least 4 UPSERT operations (1+2+1 entities created in our test)");

 		// Verify we have TRANSACTION operations
 		final long transactionCount = mutations.stream()
 			.filter(mutation -> mutation.operation() == Operation.TRANSACTION)
 			.count();
 		assertTrue(transactionCount >= 3, "Should have at least 3 TRANSACTION operations");
 	}
 }

 @DisplayName("Should retrieve reversed committed mutation stream with transactions in reverse order.")
 @UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
 @Test
 void shouldGetReversedCommittedMutationStream(EvitaContract evita, SealedEntitySchema productSchema) {
 	// Execute 3 transactions with operations
 	for (int i = 0; i < 3; i++) {
 		final int transactionIndex = i;
 		final Long version = evita.updateCatalog(
 			TEST_CATALOG,
 			session -> {
 				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(entityType, session, faker);

 				// Transaction 1: Create 1 entity
 				// Transaction 2: Create 2 entities
 				// Transaction 3: Create 1 entity
 				final int entitiesToCreate = transactionIndex == 1 ? 2 : 1;

 				for (int j = 0; j < entitiesToCreate; j++) {
 					final SealedEntity entity = this.dataGenerator.generateEntities(productSchema, randomEntityPicker, SEED + transactionIndex * 10 + j)
 						.limit(1)
 						.map(session::upsertAndFetchEntity)
 						.findFirst()
 						.orElseThrow();
 				}

 				return session.getCatalogVersion();
 			}
 		);
 	}

 	// Test getReversedCommittedMutationStream starting from the last version
 	try (final Stream<EngineMutation<?>> reversedMutationStream = ((Evita) evita).getReversedCommittedMutationStream(null)) {
 		final List<EngineMutation<?>> reversedMutations = reversedMutationStream.toList();

 		assertFalse(reversedMutations.isEmpty(), "Reversed mutation stream should not be empty");

 		// Verify we have mutations from all transactions
 		assertTrue(reversedMutations.size() >= 3, "Should have mutations from at least 3 transactions");

 		// Filter TransactionMutations to verify reverse transaction order
 		final List<TransactionMutation> reversedTransactionMutations = reversedMutations.stream()
 			.filter(TransactionMutation.class::isInstance)
 			.map(TransactionMutation.class::cast)
 			.toList();

 		assertTrue(reversedTransactionMutations.size() >= 3, "Should have at least 3 transaction mutations");

 		// Verify reverse chronological order - versions should be decreasing
 		for (int i = 1; i < reversedTransactionMutations.size(); i++) {
 			final TransactionMutation previous = reversedTransactionMutations.get(i - 1);
 			final TransactionMutation current = reversedTransactionMutations.get(i);
 			assertTrue(previous.getVersion() > current.getVersion(),
 				"Transaction mutations should be in reverse chronological order (version " + previous.getVersion() + " should be > " + current.getVersion() + ")");
 			assertTrue(previous.getCommitTimestamp().isAfter(current.getCommitTimestamp()) || previous.getCommitTimestamp().equals(current.getCommitTimestamp()),
 				"Transaction mutations should be in reverse chronological order by commit timestamp");
 		}

 		// Get the first 3 transactions (which should be the last 3 transactions we created, in reverse order)
 		final List<TransactionMutation> firstThreeTransactions = reversedTransactionMutations.subList(0, Math.min(3, reversedTransactionMutations.size()));

 		// Verify we have at least 3 transactions from our test
 		assertTrue(firstThreeTransactions.size() >= 3, "Should have at least 3 test transactions");

 		// Verify that each transaction has a positive mutation count
 		for (TransactionMutation transaction : firstThreeTransactions) {
 			assertTrue(transaction.getMutationCount() > 0, "Each transaction should have at least one mutation");
 		}

 		// Verify we have UPSERT operations for entity creations
 		final long upsertCount = reversedMutations.stream()
 			.filter(mutation -> mutation.operation() == Operation.UPSERT)
 			.count();
 		assertTrue(upsertCount >= 4, "Should have at least 4 UPSERT operations (1+2+1 entities created in our test)");

 		// Verify we have TRANSACTION operations
 		final long transactionCount = reversedMutations.stream()
 			.filter(mutation -> mutation.operation() == Operation.TRANSACTION)
 			.count();
 		assertTrue(transactionCount >= 3, "Should have at least 3 TRANSACTION operations");

 		// Verify that the highest version transaction comes first in reversed stream
 		final TransactionMutation firstTransaction = reversedTransactionMutations.get(0);
 		final TransactionMutation lastTransaction = reversedTransactionMutations.get(reversedTransactionMutations.size() - 1);
 		assertTrue(firstTransaction.getVersion() > lastTransaction.getVersion(),
 			"First transaction in reversed stream should have higher version than last");
 	}
 }

 private record PkWithCatalogVersion(
		EntityReference entityReference,
		long catalogVersion
	) implements Comparable<PkWithCatalogVersion> {

		@Override
		public int compareTo(PkWithCatalogVersion o) {
			final int first = this.entityReference.compareTo(o.entityReference);
			return first == 0 ? Long.compare(this.catalogVersion, o.catalogVersion) : first;
		}

		public String getType() {
			return this.entityReference.getType();
		}

		public int getPrimaryKey() {
			return this.entityReference.getPrimaryKey();
		}
	}

	/**
	 * An immutable class that represents an instance with its associated mutation.
	 */
	private record InstanceWithMutation(
		@Nonnull EntityContract instance,
		@Nonnull EntityMutation mutation
	) {
	}

}
