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
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.exception.ConflictingCatalogCommutativeMutationException;
import io.evitadb.api.exception.ConflictingCatalogMutationException;
import io.evitadb.api.exception.ReadOnlyException;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.InstanceEditor;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.mutation.conflict.GranularConflictPolicy;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.session.EvitaSession;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.function.Functions;
import io.evitadb.function.TriFunction;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.catalog.persistence.PersistenceService;
import io.evitadb.store.catalog.DefaultIsolatedWalService;
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
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.test.generator.DataGenerator.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.TRANSACTION;

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
@DisplayName("Evita entity transactional functionality")
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(CONTRACT)
@Tag(QUERY)
@Tag(TRANSACTION)
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
	private static final String ASSOCIATED_DATA_FEED_HEUREKA = "feed-heureka";
	private static final String ATTRIBUTE_SNIPPET_EXPIRATION = "snippetExpiration";
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

	/**
	 * Asserts that an entity with the given type and primary key exists in the session.
	 *
	 * @param session    the evita session to query
	 * @param entityType the entity type
	 * @param primaryKey the primary key
	 * @return the found entity
	 */
	@Nonnull
	private static SealedEntity assertEntityPresent(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		int primaryKey
	) {
		final Optional<SealedEntity> entity = session.getEntity(entityType, primaryKey, entityFetchAllContent());
		assertTrue(entity.isPresent(), "Entity " + entityType + ":" + primaryKey + " should be present");
		return entity.get();
	}

	/**
	 * Asserts that an entity with the given type and primary key does not exist in the session.
	 *
	 * @param session    the evita session to query
	 * @param entityType the entity type
	 * @param primaryKey the primary key
	 */
	private static void assertEntityAbsent(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		int primaryKey
	) {
		final Optional<SealedEntity> entity = session.getEntity(entityType, primaryKey, entityFetchAllContent());
		assertFalse(entity.isPresent(), "Entity " + entityType + ":" + primaryKey + " should not be present");
	}

	/**
	 * Asserts that two entities are equal.
	 *
	 * @param expected the expected entity
	 * @param actual   the actual entity
	 */
	private static void assertEntityEquals(@Nonnull SealedEntity expected, @Nonnull SealedEntity actual) {
		assertEquals(expected, actual, "Entities should be equal");
	}

	/**
	 * Stages an update of the `priority` attribute of the given product in the passed session. The write
	 * is only buffered in the session's transaction — it competes for the entity-level conflict key at
	 * the time the session commits.
	 *
	 * @param session       the session to stage the write in
	 * @param productSchema the product schema identifying the entity type
	 * @param primaryKey    the primary key of the product to update
	 * @param priority      the new priority value
	 */
	private static void setPriority(
		@Nonnull EvitaSessionContract session,
		@Nonnull SealedEntitySchema productSchema,
		int primaryKey,
		long priority
	) {
		session.getEntity(productSchema.getName(), primaryKey, entityFetchAllContent())
			.orElseThrow()
			.openForWrite()
			.setAttribute(ATTRIBUTE_PRIORITY, priority)
			.upsertVia(session);
	}

	/**
	 * Closes the passed session expecting its commit to be rejected with
	 * a {@link ConflictingCatalogMutationException}. The exception may surface directly or wrapped
	 * (e.g. in a completion or transaction exception), so the cause chain is walked to find it.
	 *
	 * @param session the session whose commit is expected to conflict
	 */
	private static void assertConflictOnClose(@Nonnull EvitaSessionContract session) {
		try {
			session.close();
			fail("ConflictingCatalogMutationException expected, but the commit succeeded!");
		} catch (Throwable ex) {
			Throwable cause = ex;
			while (cause != null && !(cause instanceof ConflictingCatalogMutationException)) {
				cause = cause.getCause();
			}
			assertNotNull(
				cause,
				"Expected ConflictingCatalogMutationException in the cause chain, but got: " + ex
			);
		}
	}

	/**
	 * Executes a concurrent update in a separate thread and waits for it to complete.
	 * This is a common pattern in conflict testing where one thread updates while another
	 * thread waits and then attempts a conflicting operation.
	 *
	 * @param evita       the evita instance to use
	 * @param catalogName the catalog name
	 * @param updateLogic the update logic to execute in the concurrent thread
	 * @throws InterruptedException if the waiting thread is interrupted
	 */
	private static void executeConcurrentUpdate(
		@Nonnull EvitaContract evita,
		@Nonnull String catalogName,
		@Nonnull Consumer<EvitaSessionContract> updateLogic
	) throws InterruptedException {
		final CountDownLatch latch = new CountDownLatch(1);
		new Thread(() -> {
			try {
				evita.updateCatalog(catalogName, updateLogic);
			} finally {
				latch.countDown();
			}
		}).start();

		if (!latch.await(10, TimeUnit.SECONDS)) {
			fail("Concurrent update timed out!");
		}
	}

	/**
	 * Reinitializes Evita with a custom configuration. Closes the original instance first.
	 *
	 * @param originalEvita        the original evita instance to close
	 * @param configurationBuilder a function that modifies the configuration builder
	 * @return the new evita instance with the custom configuration
	 */
	@Nonnull
	private static Evita reinitializeEvitaWithConfig(
		@Nonnull EvitaContract originalEvita,
		@Nonnull UnaryOperator<EvitaConfiguration.Builder> configurationBuilder
	) throws Exception {
		final EvitaConfiguration originalConfiguration = ((Evita) originalEvita).getConfiguration();
		originalEvita.close();

		final EvitaConfiguration.Builder builder = EvitaConfiguration.builder()
			.name(originalConfiguration.name())
			.storage(originalConfiguration.storage())
			.export(originalConfiguration.export())
			.server(originalConfiguration.server())
			.cache(originalConfiguration.cache())
			.transaction(originalConfiguration.transaction());

		final Evita evita = new Evita(configurationBuilder.apply(builder).build());
		evita.waitUntilFullyInitialized();
		return evita;
	}

	/**
	 * Upserts a single generated product entity into the test catalog and returns it fully fetched.
	 * Mirrors the entity-seeding block shared by the concurrent-conflict tests so those tests can focus
	 * on the conflict assertions rather than repeating the entity generation boilerplate.
	 *
	 * @param evita         the evita instance to write into
	 * @param productSchema the product schema used to generate the entity
	 * @return the upserted product entity, fully fetched
	 */
	@Nonnull
	private SealedEntity upsertSingleGeneratedProduct(
		@Nonnull EvitaContract evita,
		@Nonnull SealedEntitySchema productSchema
	) {
		return evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
					entityType, session, faker);
				final Optional<SealedEntity> upsertedEntity = this.dataGenerator.generateEntities(
						productSchema, randomEntityPicker, SEED)
					.limit(1)
					.map(session::upsertAndFetchEntity)
					.findFirst();
				assertTrue(upsertedEntity.isPresent());
				return upsertedEntity.get();
			}
		);
	}

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

	/**
	 * Returns the number of Write-Ahead Log (WAL) files in the catalog directory.
	 * WAL files have the suffix defined by {@link CatalogPersistenceService#WAL_FILE_SUFFIX}.
	 *
	 * @param catalogPath the path to the catalog directory to scan
	 * @return the number of WAL files found
	 * @throws IOException when the directory cannot be read
	 */
	private static int numberOfWalFiles(@Nonnull Path catalogPath) throws IOException {
		try (final Stream<Path> list = Files.list(catalogPath)) {
			return list
				.filter(it -> it.getFileName().toString().endsWith(PersistenceService.WAL_FILE_SUFFIX))
				.mapToInt(it -> 1)
				.sum();
		}
	}

	/**
	 * Returns the lowest index of catalog data files in the catalog directory.
	 * This is used to verify that old catalog files have been removed during compaction.
	 * Catalog data files have the suffix defined by {@link CatalogPersistenceService#CATALOG_FILE_SUFFIX}.
	 *
	 * @param catalogPath the path to the catalog directory to scan
	 * @return the minimum index found, or 0 if no files exist
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
	 * Returns the lowest index of entity collection data files for the specified entity type in the catalog directory.
	 * This is used to verify that old entity collection files have been removed during compaction.
	 * Entity collection files have the suffix defined by {@link CatalogPersistenceService#ENTITY_COLLECTION_FILE_SUFFIX}.
	 *
	 * @param catalogPath the path to the catalog directory to scan
	 * @param entityType  the entity type to search for (e.g., "Product")
	 * @return the minimum index found
	 * @throws IOException                      when the directory cannot be read
	 * @throws java.util.NoSuchElementException if no files are found for the given entity type
	 */
	private static int firstIndexOfCollectionDataFile(
		@Nonnull Path catalogPath, @Nonnull String entityType) throws IOException {
		try (final Stream<Path> list = Files.list(catalogPath)) {
			return list
				.filter(it -> it.getFileName()
					.toString()
					.endsWith(CatalogPersistenceService.ENTITY_COLLECTION_FILE_SUFFIX) && it.getFileName()
					.toString()
					.toLowerCase()
					.startsWith(entityType.toLowerCase() + "-"))
				.mapToInt(it -> CatalogPersistenceService.getEntityPrimaryKeyAndIndexFromEntityCollectionFileName(
					it.getFileName().toString()).fileIndex())
				.min()
				.orElseThrow();
		}
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
			new LogFileRecordReference(index -> CatalogPersistenceService.getWalFileName(TEST_CATALOG, index)),
			catalogDirectory,
			this.catalogKryoPool,
			new StorageSettings(
				StorageOptions.builder().build(),
				TransactionOptions.builder().build()
			),
			Mockito.mock(Scheduler.class),
			Functions.noOpLongConsumer()
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

	/* ======================================================================================== */
	/* WAL PROCESSING AND RECOVERY TESTS */
	/* ======================================================================================== */

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
		try (final Stream<Path> fileListing = Files.list(storageDir)) {
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
			final byte[] gibberish = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF, 0x01, 0x02, 0x03};
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


	@DisplayName("Update catalog with another product - synchronously.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldUpdateCatalogWithAnotherProduct(EvitaContract evita, SealedEntitySchema productSchema) {
		final SealedEntity addedEntity = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				return createSingleEntity(session, productSchema, SEED);
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity fetchedEntity = assertEntityPresent(
					session, productSchema.getName(), addedEntity.getPrimaryKey());
				assertEntityEquals(addedEntity, fetchedEntity);
			}
		);
	}

	/* ======================================================================================== */
	/* BASIC TRANSACTION TESTS */
	/* ======================================================================================== */

	@DisplayName("Update catalog with another product - asynchronously.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldUpdateCatalogWithAnotherProductAsynchronously(
		EvitaContract evita,
		SealedEntitySchema productSchema
	) throws ExecutionException, InterruptedException, TimeoutException {
		final CompletableFuture<SealedEntity> addedEntity = evita.updateCatalogAsync(
			TEST_CATALOG,
			session -> {
				return createSingleEntity(session, productSchema, SEED);
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
					final Optional<SealedEntity> entityFetchedAgain = session.getEntity(
						productSchema.getName(), addedEntityPrimaryKey);
					return entityFetchedAgain.isPresent();
				}
			);
		}

		assertTrue(expectedResult, "Entity not found in catalog!");
	}

	@DisplayName("Automatically rollback transaction in manually opened session when exception is thrown.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldAutomaticallyRollbackTheTransactionWhenExceptionIsThrownInManuallyOpenedSession(
		EvitaContract evita, SealedEntitySchema productSchema) {
		final EvitaSessionContract session = evita.createSession(
			new SessionTraits(TEST_CATALOG, CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, SessionFlags.READ_WRITE));

		final BiFunction<String, Faker, Integer> randomEntityPicker = createRandomEntityPicker(session);
		final Optional<EntityMutation> entityMutation = this.dataGenerator.generateEntities(
				productSchema, randomEntityPicker, SEED)
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
				assertEntityAbsent(theNewSession, productSchema.getName(), addedEntity.getPrimaryKey());
			}
		);
	}

	@DisplayName("Automatically rollback transaction in lambda when uncaught exception is thrown.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldAutomaticallyRollbackTheTransactionWhenExceptionIsThrownInLambda(
		EvitaContract evita, SealedEntitySchema productSchema) {
		final AtomicReference<SealedEntity> addedEntity = new AtomicReference<>();
		try {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					final BiFunction<String, Faker, Integer> randomEntityPicker = createRandomEntityPicker(session);
					final Optional<EntityMutation> entityMutation = this.dataGenerator.generateEntities(
							productSchema, randomEntityPicker, SEED)
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
				assertEntityAbsent(theNewSession, productSchema.getName(), addedEntity.get().getPrimaryKey());
			}
		);
	}

	@DisplayName("Don't rollback action when exception is throw and caught in lambda.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldNotRollbackTheTransactionWhenExceptionIsThrownAndCaughtInLambda(
		EvitaContract evita, SealedEntitySchema productSchema) {
		final SealedEntity addedEntity = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final BiFunction<String, Faker, Integer> randomEntityPicker = createRandomEntityPicker(session);
				final Optional<EntityMutation> entityMutation = this.dataGenerator.generateEntities(
						productSchema, randomEntityPicker, SEED)
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
				final SealedEntity fetchedEntity = assertEntityPresent(
					session, productSchema.getName(), addedEntity.getPrimaryKeyOrThrowException());
				assertEntityEquals(addedEntity, fetchedEntity);
			}
		);
	}

	@DisplayName("When two parallel transactions update same product, conflict is raised.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldRaiseConflictWhenTwoProductsAreUpdatedConcurrently(
		EvitaContract evita, SealedEntitySchema productSchema) {
		final SealedEntity addedEntity = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
					entityType, session, faker);
				final Optional<SealedEntity> upsertedEntity = this.dataGenerator.generateEntities(
						productSchema, randomEntityPicker, SEED)
					.limit(1)
					.map(session::upsertAndFetchEntity)
					.findFirst();
				assertTrue(upsertedEntity.isPresent());
				return upsertedEntity.get();
			}
		);

		final Random rnd = new Random();
		assertThrows(
			ConflictingCatalogMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					final BiFunction<String, Faker, Integer> rep1 = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
						entityType, session, faker);
					final ModificationFunction mf1 = this.dataGenerator.createModificationFunction(rep1, rnd);

					// this mutation will generate a conflict, but only at the time of the commit, not now
					mf1.apply(
						session.getEntity(productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
							.orElseThrow()
					).upsertVia(session);

					try {
						// this concurrent session will try to do the same, and commits first
						executeConcurrentUpdate(
							evita, TEST_CATALOG, concurrentSession -> {
								final BiFunction<String, Faker, Integer> rep2 = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
									entityType, concurrentSession, faker);
								final ModificationFunction mf2 = this.dataGenerator.createModificationFunction(
									rep2, rnd);

								// this mutation will generate a conflict, but only at the time of the commit, not now
								mf2.apply(
									concurrentSession.getEntity(
											productSchema.getName(), addedEntity.getPrimaryKey(),
											entityFetchAllContent()
										)
										.orElseThrow()
								).upsertVia(concurrentSession);
							}
						);
					} catch (InterruptedException e) {
						fail("Test thread was interrupted!", e);
					}

					log.info("Attempting to commit conflicting transaction...");
				}
			)
		);

		// but no conflict should be raised when there is another update after everything settled
		evita.updateCatalog(
			TEST_CATALOG,
			followUpSession -> {
				final BiFunction<String, Faker, Integer> rep3 = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
					entityType, followUpSession, faker);
				final ModificationFunction mf3 = this.dataGenerator.createModificationFunction(
					rep3, rnd);

				// this mutation will generate a conflict, but only at the time of the commit, not now
				mf3.apply(
					followUpSession.getEntity(
							productSchema.getName(), addedEntity.getPrimaryKey(),
							entityFetchAllContent()
						)
						.orElseThrow()
				).upsertVia(followUpSession);
			}
		);
	}

	/* ======================================================================================== */
	/* CONFLICT DETECTION TESTS */
	/* ======================================================================================== */

	@DisplayName("Commit of a long-running transaction conflicts with sessions whose snapshot predates that commit.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldDetectConflictOfLongRunningTransactionAgainstNewerSnapshot(
		EvitaContract evita, SealedEntitySchema productSchema
	) {
		// seed two products - the contended one and an unrelated one used to advance the catalog version
		final List<SealedEntity> addedEntities = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				return this.dataGenerator.generateEntities(
						productSchema,
						(entityType, faker) -> RANDOM_ENTITY_PICKER.apply(entityType, session, faker),
						SEED
					)
					.limit(2)
					.map(session::upsertAndFetchEntity)
					.toList();
			}
		);
		final int contendedPk = addedEntities.get(0).getPrimaryKeyOrThrowException();
		final int unrelatedPk = addedEntities.get(1).getPrimaryKeyOrThrowException();

		// the long-running writer opens its snapshot first and stages a write to the contended product
		final EvitaSessionContract longRunningSession = evita.createSession(
			new SessionTraits(TEST_CATALOG, CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, SessionFlags.READ_WRITE)
		);
		setPriority(longRunningSession, productSchema, contendedPk, 1000L);
		final long longRunningSnapshotVersion = longRunningSession.getCatalogVersion();

		// an unrelated transaction commits meanwhile and becomes visible, so sessions opened from
		// now on start at a newer snapshot than the long-running writer's
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				setPriority(session, productSchema, unrelatedPk, 2000L);
			}
		);

		// two competing sessions open their snapshots AFTER the unrelated commit became visible,
		// but BEFORE the long-running writer commits
		final EvitaSessionContract firstCompetingSession = evita.createSession(
			new SessionTraits(TEST_CATALOG, CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, SessionFlags.READ_WRITE)
		);
		setPriority(firstCompetingSession, productSchema, contendedPk, 3000L);
		final EvitaSessionContract secondCompetingSession = evita.createSession(
			new SessionTraits(TEST_CATALOG, CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, SessionFlags.READ_WRITE)
		);
		setPriority(secondCompetingSession, productSchema, contendedPk, 4000L);
		// the interleaving this test exists for requires the competing snapshots to be strictly newer
		// than the long-running writer's snapshot while still predating its commit
		assertTrue(
			firstCompetingSession.getCatalogVersion() > longRunningSnapshotVersion,
			"Competing sessions must open a newer snapshot than the long-running writer!"
		);

		// the long-running writer commits first and wins
		longRunningSession.close();

		// both competing snapshots predate the long-running commit version, so they are concurrent
		// with it and must be rejected; the second rejection also guards the rollback path - the
		// first rejected commit must not release the long-running transaction's conflict keys
		assertConflictOnClose(firstCompetingSession);
		assertConflictOnClose(secondCompetingSession);

		// a session opened after the long-running commit is its successor and must pass
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				setPriority(session, productSchema, contendedPk, 5000L);
			}
		);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Long priority = session.getEntity(
						productSchema.getName(), contendedPk, entityFetchAllContent()
					)
					.orElseThrow()
					.getAttribute(ATTRIBUTE_PRIORITY);
				assertEquals(5000L, priority);
			}
		);
	}

	@DisplayName("When parallel transactions remove and update same product, conflict is raised.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldRaiseConflictWhenProductIsRemovedAndUpdatedConcurrently(
		EvitaContract evita, SealedEntitySchema productSchema) {
		final SealedEntity addedEntity = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
					entityType, session, faker);
				final Optional<SealedEntity> upsertedEntity = this.dataGenerator.generateEntities(
						productSchema, randomEntityPicker, SEED)
					.limit(1)
					.map(session::upsertAndFetchEntity)
					.findFirst();
				assertTrue(upsertedEntity.isPresent());
				return upsertedEntity.get();
			}
		);

		final Random rnd = new Random();
		assertThrows(
			ConflictingCatalogMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					// this mutation will generate a conflict, but only at the time of the commit, not now
					session.deleteEntity(productSchema.getName(), addedEntity.getPrimaryKey());

					try {
						// this concurrent session will try to do the same, and commits first
						executeConcurrentUpdate(
							evita, TEST_CATALOG, concurrentSession -> {
								final BiFunction<String, Faker, Integer> rep2 = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
									entityType, concurrentSession, faker);
								final ModificationFunction mf2 = this.dataGenerator.createModificationFunction(
									rep2, rnd);

								// this mutation will generate a conflict, but only at the time of the commit, not now
								mf2.apply(
									concurrentSession.getEntity(
											productSchema.getName(), addedEntity.getPrimaryKey(),
											entityFetchAllContent()
										)
										.orElseThrow()
								).upsertVia(concurrentSession);
							}
						);
					} catch (InterruptedException e) {
						fail("Test thread was interrupted!", e);
					}

					log.info("Attempting to commit conflicting transaction...");
				}
			)
		);
	}

	@DisplayName("When parallel transactions update and remove same product, conflict is raised.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldRaiseConflictWhenProductIsUpdatedAndRemovedConcurrently(
		EvitaContract evita, SealedEntitySchema productSchema) {
		final SealedEntity addedEntity = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
					entityType, session, faker);
				final Optional<SealedEntity> upsertedEntity = this.dataGenerator.generateEntities(
						productSchema, randomEntityPicker, SEED)
					.limit(1)
					.map(session::upsertAndFetchEntity)
					.findFirst();
				assertTrue(upsertedEntity.isPresent());
				return upsertedEntity.get();
			}
		);

		final Random rnd = new Random();
		assertThrows(
			ConflictingCatalogMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					// this mutation will generate a conflict, but only at the time of the commit, not now
					final BiFunction<String, Faker, Integer> rep1 = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
						entityType, session, faker);
					final ModificationFunction mf1 = this.dataGenerator.createModificationFunction(
						rep1, rnd);

					// this mutation will generate a conflict, but only at the time of the commit, not now
					mf1.apply(
						session.getEntity(
								productSchema.getName(), addedEntity.getPrimaryKey(),
								entityFetchAllContent()
							)
							.orElseThrow()
					).upsertVia(session);

					try {
						// this concurrent session will try to do the same, and commits first
						executeConcurrentUpdate(
							evita, TEST_CATALOG, concurrentSession -> {
								concurrentSession.deleteEntity(productSchema.getName(), addedEntity.getPrimaryKey());
							}
						);
					} catch (InterruptedException e) {
						fail("Test thread was interrupted!", e);
					}

					log.info("Attempting to commit conflicting transaction...");
				}
			)
		);
	}

	@DisplayName("When two parallel transactions update same product on non-conflicting granular level (different attributes), no conflict is raised.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldNotRaiseConflictWhenTwoProductsAreUpdatedConcurrentlyOnNonConflictingGranularLevel(
		EvitaContract originalEvita,
		SealedEntitySchema productSchema
	) throws Exception {
		// reinitialize evita with a specific narrowed conflict policy
		final Evita evita = reinitializeEvitaWithConfig(
			originalEvita,
			builder -> builder.transaction(
				TransactionOptions.builder(builder.build().transaction())
					.conflictResolution(ConflictPolicy.ENTITY, GranularConflictPolicy.ENTITY_ATTRIBUTE)
					.build()
			)
		);

		final SealedEntity addedEntity = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
					entityType, session, faker);
				final Optional<SealedEntity> upsertedEntity = this.dataGenerator.generateEntities(
						productSchema, randomEntityPicker, SEED)
					.limit(1)
					.map(session::upsertAndFetchEntity)
					.findFirst();
				assertTrue(upsertedEntity.isPresent());
				return upsertedEntity.get();
			}
		);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_PRIORITY, 19846L)
					.upsertVia(session);

				try {
					// this concurrent session will try to do the same, and commits first
					executeConcurrentUpdate(
						evita, TEST_CATALOG, concurrentSession -> {
							// this mutation will generate a conflict, but only at the time of the commit, not now
							concurrentSession.getEntity(
									productSchema.getName(), addedEntity.getPrimaryKey(),
									entityFetchAllContent()
								)
								.orElseThrow()
								.openForWrite()
								.setAttribute(ATTRIBUTE_CODE, "some-changed-code")
								.upsertVia(concurrentSession);
						}
					);
				} catch (InterruptedException e) {
					fail("Test thread was interrupted!", e);
				}

				log.info("Attempting to commit non-conflicting transaction...");
			}
		);
	}

	@DisplayName("When two parallel transactions update same product on conflicting granular level (same attributes), conflict is raised.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldRaiseConflictWhenTwoProductsAreUpdatedConcurrentlyOnConflictingGranularLevel(
		EvitaContract evita, SealedEntitySchema productSchema) {
		final SealedEntity addedEntity = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
					entityType, session, faker);
				final Optional<SealedEntity> upsertedEntity = this.dataGenerator.generateEntities(
						productSchema, randomEntityPicker, SEED)
					.limit(1)
					.map(session::upsertAndFetchEntity)
					.findFirst();
				assertTrue(upsertedEntity.isPresent());
				return upsertedEntity.get();
			}
		);

		assertThrows(
			ConflictingCatalogMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTRIBUTE_PRIORITY, 19846L)
						.upsertVia(session);

					try {
						// this concurrent session will try to do the same, and commits first
						executeConcurrentUpdate(
							evita, TEST_CATALOG, concurrentSession -> {
								// this mutation will generate a conflict, but only at the time of the commit, not now
								concurrentSession.getEntity(
										productSchema.getName(), addedEntity.getPrimaryKey(),
										entityFetchAllContent()
									)
									.orElseThrow()
									.openForWrite()
									.setAttribute(ATTRIBUTE_PRIORITY, 27954L)
									.upsertVia(concurrentSession);
							}
						);
					} catch (InterruptedException e) {
						fail("Test thread was interrupted!", e);
					}

					log.info("Attempting to commit conflicting transaction...");
				}
			)
		);

		// but no conflict should be raised when there is another update after everything settled
		evita.updateCatalog(
			TEST_CATALOG,
			followUpSession -> {
				followUpSession.getEntity(
						productSchema.getName(), addedEntity.getPrimaryKey(),
						entityFetchAllContent()
					)
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_PRIORITY, 27954L)
					.upsertVia(followUpSession);
			}
		);
	}

	@DisplayName("When the catalog schema declares an ENTITY conflict policy, it overrides a NONE engine default and concurrent writes to the same product conflict.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldRaiseConflictWhenCatalogSchemaDeclaresEntityPolicyOverridingNoneEngineDefault(
		EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		// the engine default disables conflict detection entirely (last-writer-wins)...
		final Evita evita = reinitializeEvitaWithConfig(
			originalEvita,
			builder -> builder.transaction(
				TransactionOptions.builder(builder.build().transaction())
					.conflictResolution(ConflictPolicy.NONE)
					.build()
			)
		);

		// ...but the catalog schema re-enables entity-level conflict detection on the live write path
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withConflictResolution(new ConflictResolution(ConflictPolicy.ENTITY))
					.updateVia(session);
			}
		);

		final SealedEntity addedEntity = upsertSingleGeneratedProduct(evita, productSchema);

		assertThrows(
			ConflictingCatalogMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTRIBUTE_PRIORITY, 19846L)
						.upsertVia(session);

					try {
						// concurrent session touches a different attribute of the SAME entity and commits first;
						// under the whole-entity key mandated by the catalog schema this still conflicts
						executeConcurrentUpdate(
							evita, TEST_CATALOG, concurrentSession -> {
								concurrentSession.getEntity(
										productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
									.orElseThrow()
									.openForWrite()
									.setAttribute(ATTRIBUTE_CODE, "catalog-schema-conflict-code")
									.upsertVia(concurrentSession);
							}
						);
					} catch (InterruptedException e) {
						fail("Test thread was interrupted!", e);
					}

					log.info("Attempting to commit transaction expected to conflict via catalog-schema policy...");
				}
			)
		);
	}

	@DisplayName("When the engine default is NONE and no schema declares a conflict policy, concurrent writes to the same product do not conflict.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldNotRaiseConflictWhenEngineDefaultIsNoneAndNoSchemaDeclaresPolicy(
		EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		// engine default disables conflict detection and, unlike the previous test, no schema overrides it
		final Evita evita = reinitializeEvitaWithConfig(
			originalEvita,
			builder -> builder.transaction(
				TransactionOptions.builder(builder.build().transaction())
					.conflictResolution(ConflictPolicy.NONE)
					.build()
			)
		);

		final SealedEntity addedEntity = upsertSingleGeneratedProduct(evita, productSchema);

		// two concurrent writes to the SAME attribute of the SAME entity would conflict under any
		// entity-or-coarser policy, yet the NONE engine default lets the last writer silently win
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_PRIORITY, 19846L)
					.upsertVia(session);

				try {
					executeConcurrentUpdate(
						evita, TEST_CATALOG, concurrentSession -> {
							concurrentSession.getEntity(
									productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
								.orElseThrow()
								.openForWrite()
								.setAttribute(ATTRIBUTE_PRIORITY, 27954L)
								.upsertVia(concurrentSession);
						}
					);
				} catch (InterruptedException e) {
					fail("Test thread was interrupted!", e);
				}

				log.info("Committing transaction expected NOT to conflict under the NONE engine default...");
			}
		);
	}

	@DisplayName("When the entity schema declares a NONE conflict policy, it overrides an ENTITY catalog-schema policy and concurrent writes to the same product do not conflict.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldNotRaiseConflictWhenEntitySchemaDeclaresNonePolicyOverridingCatalogEntityPolicy(
		EvitaContract evita, SealedEntitySchema productSchema) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// catalog schema would force entity-level conflicts for every collection...
				session.getCatalogSchema()
					.openForWrite()
					.withConflictResolution(new ConflictResolution(ConflictPolicy.ENTITY))
					.updateVia(session);
				// ...but the entity schema relaxes conflict detection entirely for this collection
				session.getEntitySchema(productSchema.getName())
					.orElseThrow()
					.openForWrite()
					.withConflictResolution(new ConflictResolution(ConflictPolicy.NONE))
					.updateVia(session);
			}
		);

		final SealedEntity addedEntity = upsertSingleGeneratedProduct(evita, productSchema);

		// two concurrent writes to the SAME attribute of the SAME entity would conflict under the
		// catalog policy, but the more specific entity-schema NONE override wins whole-record
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_PRIORITY, 19846L)
					.upsertVia(session);

				try {
					executeConcurrentUpdate(
						evita, TEST_CATALOG, concurrentSession -> {
							concurrentSession.getEntity(
									productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
								.orElseThrow()
								.openForWrite()
								.setAttribute(ATTRIBUTE_PRIORITY, 27954L)
								.upsertVia(concurrentSession);
						}
					);
				} catch (InterruptedException e) {
					fail("Test thread was interrupted!", e);
				}

				log.info("Committing transaction expected NOT to conflict via entity-schema NONE override...");
			}
		);
	}

	@DisplayName("When two entity attributes each declare a GRANULAR per-item conflict override, concurrent writes to different attributes of the same product do not conflict.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldNotRaiseConflictWhenPerItemGranularOverrideIsolatesDistinctAttributes(
		EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		// coarse ENTITY policy with no granular refinements => every write emits the whole-entity key...
		final Evita evita = reinitializeEvitaWithConfig(
			originalEvita,
			builder -> builder.transaction(
				TransactionOptions.builder(builder.build().transaction())
					.conflictResolution(ConflictPolicy.ENTITY)
					.build()
			)
		);

		// ...but two attributes opt themselves into their own attribute-scoped conflict keys
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntitySchema(productSchema.getName())
					.orElseThrow()
					.openForWrite()
					.withAttribute(
						ATTRIBUTE_PRIORITY, Long.class,
						whichIs -> whichIs.withConflictResolutionOverride(ConflictResolutionOverride.GRANULAR))
					.withAttribute(
						ATTRIBUTE_QUANTITY, BigDecimal.class,
						whichIs -> whichIs.withConflictResolutionOverride(ConflictResolutionOverride.GRANULAR))
					.updateVia(session);
			}
		);

		final SealedEntity addedEntity = upsertSingleGeneratedProduct(evita, productSchema);

		// writes to two distinct GRANULAR-overridden attributes emit disjoint keys => no conflict
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_PRIORITY, 19846L)
					.upsertVia(session);

				try {
					executeConcurrentUpdate(
						evita, TEST_CATALOG, concurrentSession -> {
							concurrentSession.getEntity(
									productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
								.orElseThrow()
								.openForWrite()
								.setAttribute(ATTRIBUTE_QUANTITY, new BigDecimal("123.45"))
								.upsertVia(concurrentSession);
						}
					);
				} catch (InterruptedException e) {
					fail("Test thread was interrupted!", e);
				}

				log.info("Committing transaction expected NOT to conflict via per-item GRANULAR overrides...");
			}
		);
	}

	@DisplayName("When an entity attribute declares a GRANULAR per-item conflict override, concurrent writes to that same attribute of the same product still conflict.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldRaiseConflictWhenPerItemGranularOverrideStillGuardsSameAttribute(
		EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		// coarse ENTITY policy with no granular refinements as the baseline...
		final Evita evita = reinitializeEvitaWithConfig(
			originalEvita,
			builder -> builder.transaction(
				TransactionOptions.builder(builder.build().transaction())
					.conflictResolution(ConflictPolicy.ENTITY)
					.build()
			)
		);

		// ...and a GRANULAR override on the priority attribute, isolating it into its own key
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntitySchema(productSchema.getName())
					.orElseThrow()
					.openForWrite()
					.withAttribute(
						ATTRIBUTE_PRIORITY, Long.class,
						whichIs -> whichIs.withConflictResolutionOverride(ConflictResolutionOverride.GRANULAR))
					.updateVia(session);
			}
		);

		final SealedEntity addedEntity = upsertSingleGeneratedProduct(evita, productSchema);

		// both writes touch the SAME granular-overridden attribute => same key => conflict
		assertThrows(
			ConflictingCatalogMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTRIBUTE_PRIORITY, 19846L)
						.upsertVia(session);

					try {
						executeConcurrentUpdate(
							evita, TEST_CATALOG, concurrentSession -> {
								concurrentSession.getEntity(
										productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
									.orElseThrow()
									.openForWrite()
									.setAttribute(ATTRIBUTE_PRIORITY, 27954L)
									.upsertVia(concurrentSession);
							}
						);
					} catch (InterruptedException e) {
						fail("Test thread was interrupted!", e);
					}

					log.info("Attempting to commit conflicting write to the same granular attribute...");
				}
			)
		);
	}

	@DisplayName("When two transactions concurrently create an entity with the same explicitly-assigned primary key under a fully granular policy, the second commit is rejected.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldRaiseConflictWhenTwoTransactionsCreateEntityWithSamePrimaryKeyUnderGranularPolicy(
		EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		// fully granular policy: attribute writes to the same entity would NOT conflict...
		final Evita evita = reinitializeEvitaWithConfig(
			originalEvita,
			builder -> builder.transaction(
				TransactionOptions.builder(builder.build().transaction())
					.conflictResolution(ConflictPolicy.ENTITY, GranularConflictPolicy.ENTITY_ATTRIBUTE)
					.build()
			)
		);

		final int sharedPrimaryKey = 1000;

		// ...yet forced creation (MUST_NOT_EXIST) still emits the coarse whole-entity key, so two
		// concurrent creations of the same primary key can never both succeed
		assertThrows(
			ConflictingCatalogMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(productSchema.getName(), sharedPrimaryKey)
						.setAttribute(ATTRIBUTE_PRIORITY, 19846L)
						.upsertVia(session);

					try {
						// concurrent session creates the same primary key and commits first
						executeConcurrentUpdate(
							evita, TEST_CATALOG, concurrentSession -> {
								concurrentSession.createNewEntity(productSchema.getName(), sharedPrimaryKey)
									.setAttribute(ATTRIBUTE_PRIORITY, 27954L)
									.upsertVia(concurrentSession);
							}
						);
					} catch (InterruptedException e) {
						fail("Test thread was interrupted!", e);
					}

					log.info("Attempting to commit a second creation of the same primary key...");
				}
			)
		);
	}

	/**
	 * Reinitializes evita with coarse {@link ConflictPolicy#ENTITY} and declares the associated data
	 * {@link #ASSOCIATED_DATA_FEED_HEUREKA} and the attribute {@link #ATTRIBUTE_SNIPPET_EXPIRATION} as
	 * per-item {@link ConflictResolutionOverride#GRANULAR} on the product schema — the carve-out fixture
	 * shared by the end-to-end conflict tests below.
	 *
	 * @param originalEvita the evita instance to reinitialize
	 * @param productSchema the product schema to declare the carved-out items on
	 * @return the reinitialized evita instance
	 */
	@Nonnull
	private static Evita setUpPerItemCarveOutSchema(
		@Nonnull EvitaContract originalEvita, @Nonnull SealedEntitySchema productSchema
	) throws Exception {
		final Evita evita = reinitializeEvitaWithConfig(
			originalEvita,
			builder -> builder.transaction(
				TransactionOptions.builder(builder.build().transaction())
					.conflictResolution(ConflictPolicy.ENTITY)
					.build()
			)
		);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntitySchema(productSchema.getName())
					.orElseThrow()
					.openForWrite()
					.withAssociatedData(
						ASSOCIATED_DATA_FEED_HEUREKA, String.class,
						whichIs -> whichIs.nullable().withConflictResolutionOverride(ConflictResolutionOverride.GRANULAR))
					.withAttribute(
						ATTRIBUTE_SNIPPET_EXPIRATION, Long.class,
						whichIs -> whichIs.nullable().withConflictResolutionOverride(ConflictResolutionOverride.GRANULAR))
					.updateVia(session);
			}
		);
		return evita;
	}

	/**
	 * Reinitializes evita with coarse {@link ConflictPolicy#ENTITY} refined by the
	 * {@link GranularConflictPolicy#ASSOCIATED_DATA} granularity-set flavor (no per-item schema overrides at
	 * all): every associated data item of every entity of this type is carved out purely by the inherited
	 * refinement. Declares {@link #ASSOCIATED_DATA_FEED_HEUREKA} on the schema without any per-item override
	 * so its carve-out status comes solely from the granularity set.
	 *
	 * @param originalEvita the evita instance to reinitialize
	 * @param productSchema the product schema to declare the associated data item on
	 * @return the reinitialized evita instance
	 */
	@Nonnull
	private static Evita setUpGranularitySetCarveOutSchema(
		@Nonnull EvitaContract originalEvita, @Nonnull SealedEntitySchema productSchema
	) throws Exception {
		final Evita evita = reinitializeEvitaWithConfig(
			originalEvita,
			builder -> builder.transaction(
				TransactionOptions.builder(builder.build().transaction())
					.conflictResolution(ConflictPolicy.ENTITY, GranularConflictPolicy.ASSOCIATED_DATA)
					.build()
			)
		);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntitySchema(productSchema.getName())
					.orElseThrow()
					.openForWrite()
					.withAssociatedData(ASSOCIATED_DATA_FEED_HEUREKA, String.class, whichIs -> whichIs.nullable())
					.updateVia(session);
			}
		);
		return evita;
	}

	@DisplayName("A coarse writer of a plain attribute and a writer of a carved-out associated data item do not conflict — the granular carve-out fix.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldNotRaiseConflictBetweenCoarseAttributeAndCarvedOutAssociatedDataWriters(
		EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		final Evita evita = setUpPerItemCarveOutSchema(originalEvita, productSchema);
		final SealedEntity addedEntity = upsertSingleGeneratedProduct(evita, productSchema);

		// txn A writes a plain, non-carved-out attribute (the entity's shared surface); txn B writes the
		// carved-out associated data item - before the fix, A's coarse fallback key would have been the
		// full entity key, which falsely contained B's granular key
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				setPriority(session, productSchema, addedEntity.getPrimaryKey(), 19846L);

				try {
					executeConcurrentUpdate(
						evita, TEST_CATALOG, concurrentSession -> {
							concurrentSession.getEntity(productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
								.orElseThrow()
								.openForWrite()
								.setAssociatedData(ASSOCIATED_DATA_FEED_HEUREKA, "payload")
								.upsertVia(concurrentSession);
						}
					);
				} catch (InterruptedException e) {
					fail("Test thread was interrupted!", e);
				}

				log.info("Committing transaction expected NOT to conflict via the residual/granular carve-out...");
			}
		);
	}

	@DisplayName("Two concurrent writers of the same carved-out associated data item still conflict.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldRaiseConflictBetweenTwoWritersOfSameCarvedOutAssociatedDataItem(
		EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		final Evita evita = setUpPerItemCarveOutSchema(originalEvita, productSchema);
		final SealedEntity addedEntity = upsertSingleGeneratedProduct(evita, productSchema);

		assertThrows(
			ConflictingCatalogMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAssociatedData(ASSOCIATED_DATA_FEED_HEUREKA, "payload-A")
						.upsertVia(session);

					try {
						executeConcurrentUpdate(
							evita, TEST_CATALOG, concurrentSession -> {
								concurrentSession.getEntity(productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
									.orElseThrow()
									.openForWrite()
									.setAssociatedData(ASSOCIATED_DATA_FEED_HEUREKA, "payload-B")
									.upsertVia(concurrentSession);
							}
						);
					} catch (InterruptedException e) {
						fail("Test thread was interrupted!", e);
					}

					log.info("Attempting to commit conflicting write to the same carved-out associated data item...");
				}
			)
		);
	}

	@DisplayName("Two coarse writers touching disjoint non-carved-out items (an attribute and a price) still conflict via the shared residual key.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldRaiseConflictBetweenTwoCoarseWritersOfDisjointNonGranularItems(
		EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		final Evita evita = setUpPerItemCarveOutSchema(originalEvita, productSchema);
		final SealedEntity addedEntity = upsertSingleGeneratedProduct(evita, productSchema);

		assertThrows(
			ConflictingCatalogMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					setPriority(session, productSchema, addedEntity.getPrimaryKey(), 19846L);

					try {
						executeConcurrentUpdate(
							evita, TEST_CATALOG, concurrentSession -> {
								concurrentSession.getEntity(productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
									.orElseThrow()
									.openForWrite()
									// a distinct price list avoids AmbiguousPriceException against the
									// generated entity's existing (unbounded-validity) basic-price-list price
									.setPrice(555, "carveout", CURRENCY_CZK, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, true)
									.upsertVia(concurrentSession);
							}
						);
					} catch (InterruptedException e) {
						fail("Test thread was interrupted!", e);
					}

					log.info("Attempting to commit conflicting transaction sharing the entity's residual surface...");
				}
			)
		);
	}

	@DisplayName("Entity removal conflicts with a concurrent writer of a carved-out associated data item.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldRaiseConflictBetweenEntityRemovalAndCarvedOutAssociatedDataWriter(
		EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		final Evita evita = setUpPerItemCarveOutSchema(originalEvita, productSchema);
		final SealedEntity addedEntity = upsertSingleGeneratedProduct(evita, productSchema);

		assertThrows(
			ConflictingCatalogMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					// this mutation will generate a conflict, but only at the time of the commit, not now
					session.deleteEntity(productSchema.getName(), addedEntity.getPrimaryKey());

					try {
						executeConcurrentUpdate(
							evita, TEST_CATALOG, concurrentSession -> {
								concurrentSession.getEntity(productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
									.orElseThrow()
									.openForWrite()
									.setAssociatedData(ASSOCIATED_DATA_FEED_HEUREKA, "payload")
									.upsertVia(concurrentSession);
							}
						);
					} catch (InterruptedException e) {
						fail("Test thread was interrupted!", e);
					}

					log.info("Attempting to commit removal conflicting with a carved-out associated data writer...");
				}
			)
		);
	}

	@DisplayName("A scope change conflicts with a concurrent writer of a carved-out associated data item.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldRaiseConflictBetweenScopeChangeAndCarvedOutAssociatedDataWriter(
		EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		final Evita evita = setUpPerItemCarveOutSchema(originalEvita, productSchema);
		final SealedEntity addedEntity = upsertSingleGeneratedProduct(evita, productSchema);

		assertThrows(
			ConflictingCatalogMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					// a scope change is a whole-entity operation: it must conflict with every carved-out
					// item too, even though the coarse fallback now only covers the shared surface
					session.getEntity(productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setScope(Scope.ARCHIVED)
						.upsertVia(session);

					try {
						executeConcurrentUpdate(
							evita, TEST_CATALOG, concurrentSession -> {
								concurrentSession.getEntity(productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
									.orElseThrow()
									.openForWrite()
									.setAssociatedData(ASSOCIATED_DATA_FEED_HEUREKA, "payload")
									.upsertVia(concurrentSession);
							}
						);
					} catch (InterruptedException e) {
						fail("Test thread was interrupted!", e);
					}

					log.info("Attempting to commit scope change conflicting with a carved-out associated data writer...");
				}
			)
		);
	}

	@DisplayName("Forced creation of the same primary key conflicts with a concurrent carved-out associated data writer on that pk.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldRaiseConflictBetweenForcedCreationAndCarvedOutAssociatedDataWriterOnSamePrimaryKey(
		EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		final Evita evita = setUpPerItemCarveOutSchema(originalEvita, productSchema);

		final int sharedPrimaryKey = 2000;

		// both transactions create the same, not-yet-existing primary key (MUST_NOT_EXIST): forced
		// creation always emits the full entity key, which conflicts with the concurrent writer's
		// carved-out associated data key too, even though the two touch disjoint items
		assertThrows(
			ConflictingCatalogMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(productSchema.getName(), sharedPrimaryKey)
						.setAttribute(ATTRIBUTE_PRIORITY, 19846L)
						.upsertVia(session);

					try {
						executeConcurrentUpdate(
							evita, TEST_CATALOG, concurrentSession -> {
								// `priority` is a mandatory attribute, so a brand new entity must set it
								// regardless of which item this transaction is actually exercising
								concurrentSession.createNewEntity(productSchema.getName(), sharedPrimaryKey)
									.setAttribute(ATTRIBUTE_PRIORITY, 27954L)
									.setAssociatedData(ASSOCIATED_DATA_FEED_HEUREKA, "payload")
									.upsertVia(concurrentSession);
							}
						);
					} catch (InterruptedException e) {
						fail("Test thread was interrupted!", e);
					}

					log.info("Attempting to commit a second creation of the same primary key...");
				}
			)
		);
	}

	@DisplayName("A coarse writer and a writer touching only carved-out items (a granular attribute and a granular associated data item) do not conflict.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldNotRaiseConflictBetweenCoarseWriterAndFullyGranularWriter(
		EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		final Evita evita = setUpPerItemCarveOutSchema(originalEvita, productSchema);
		final SealedEntity addedEntity = upsertSingleGeneratedProduct(evita, productSchema);

		// txn A writes the shared surface (residual key); txn B writes two DIFFERENT carved-out items
		// (a granular attribute and a granular associated data item) - since every one of B's mutations
		// produces its own granular key, B never falls back to the residual or full entity key at all
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				setPriority(session, productSchema, addedEntity.getPrimaryKey(), 19846L);

				try {
					executeConcurrentUpdate(
						evita, TEST_CATALOG, concurrentSession -> {
							concurrentSession.getEntity(productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
								.orElseThrow()
								.openForWrite()
								.setAttribute(ATTRIBUTE_SNIPPET_EXPIRATION, 1234L)
								.setAssociatedData(ASSOCIATED_DATA_FEED_HEUREKA, "payload")
								.upsertVia(concurrentSession);
						}
					);
				} catch (InterruptedException e) {
					fail("Test thread was interrupted!", e);
				}

				log.info("Committing transaction expected NOT to conflict — the writer touches only carved-out items...");
			}
		);
	}

	@DisplayName("Granularity-set flavor: an attribute writer and an associated-data writer do not conflict when ASSOCIATED_DATA is carved out via the entity's granularity set (no per-item overrides).")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldNotRaiseConflictUnderGranularitySetCarveOut(
		EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		final Evita evita = setUpGranularitySetCarveOutSchema(originalEvita, productSchema);
		final SealedEntity addedEntity = upsertSingleGeneratedProduct(evita, productSchema);

		// no per-item ConflictResolutionOverride is declared anywhere: the associated data item is carved
		// out purely because the entity's inherited granularity set includes ASSOCIATED_DATA
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				setPriority(session, productSchema, addedEntity.getPrimaryKey(), 19846L);

				try {
					executeConcurrentUpdate(
						evita, TEST_CATALOG, concurrentSession -> {
							concurrentSession.getEntity(productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
								.orElseThrow()
								.openForWrite()
								.setAssociatedData(ASSOCIATED_DATA_FEED_HEUREKA, "payload")
								.upsertVia(concurrentSession);
						}
					);
				} catch (InterruptedException e) {
					fail("Test thread was interrupted!", e);
				}

				log.info("Committing transaction expected NOT to conflict via the granularity-set carve-out...");
			}
		);
	}

	@DisplayName("When two parallel transactions update same product on conflicting granular level (same attributes) via delta mutation, no conflict is raised.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldNotRaiseConflictWhenTwoProductsAreUpdatedConcurrentlyOnConflictingGranularLevelViaDeltaChange(
		EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		final EvitaConfiguration originalConfiguration = ((Evita) originalEvita).getConfiguration();
		originalEvita.close();

		// reinitialize evita with a specific narrowed WAL limitations
		final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.name(originalConfiguration.name())
				.transaction(
					TransactionOptions.builder(originalConfiguration.transaction())
						.conflictResolution(ConflictPolicy.ENTITY, GranularConflictPolicy.ENTITY_ATTRIBUTE)
						.build()
				)
				.storage(originalConfiguration.storage())
				.export(originalConfiguration.export())
				.server(originalConfiguration.server())
				.cache(originalConfiguration.cache())
				.build()
		);
		evita.waitUntilFullyInitialized();

		final SealedEntity addedEntity = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
					entityType, session, faker);
				final Optional<SealedEntity> upsertedEntity = this.dataGenerator.generateEntities(
						productSchema, randomEntityPicker, SEED)
					.limit(1)
					.map(session::upsertAndFetchEntity)
					.findFirst();
				assertTrue(upsertedEntity.isPresent());
				return upsertedEntity.get();
			}
		);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.mutate(new ApplyDeltaAttributeMutation<>(ATTRIBUTE_PRIORITY, 1L))
					.upsertVia(session);

				try {
					// this concurrent session will try to do the same, and commits first
					executeConcurrentUpdate(
						evita, TEST_CATALOG, concurrentSession -> {
							// this mutation will generate a conflict, but only at the time of the commit, not now
							concurrentSession.getEntity(
									productSchema.getName(), addedEntity.getPrimaryKey(),
									entityFetchAllContent()
								)
								.orElseThrow()
								.openForWrite()
								.mutate(new ApplyDeltaAttributeMutation<>(ATTRIBUTE_PRIORITY, 1L))
								.upsertVia(concurrentSession);
						}
					);
				} catch (InterruptedException e) {
					fail("Test thread was interrupted!", e);
				}

				log.info("Attempting to commit conflicting transaction...");
			}
		);
	}

	@DisplayName("When two parallel transactions update same product on conflicting granular level (same attributes) via delta mutation, conflict is raised when range is not satisfied.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldRaiseConflictWhenTwoProductsAreUpdatedConcurrentlyOnConflictingGranularLevelViaDeltaChangeOutsideRange(
		EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		final EvitaConfiguration originalConfiguration = ((Evita) originalEvita).getConfiguration();
		originalEvita.close();

		// reinitialize evita with a specific narrowed WAL limitations
		final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.name(originalConfiguration.name())
				.transaction(
					TransactionOptions.builder(originalConfiguration.transaction())
						.conflictResolution(ConflictPolicy.ENTITY, GranularConflictPolicy.ENTITY_ATTRIBUTE)
						.build()
				)
				.storage(originalConfiguration.storage())
				.export(originalConfiguration.export())
				.server(originalConfiguration.server())
				.cache(originalConfiguration.cache())
				.build()
		);
		evita.waitUntilFullyInitialized();

		final SealedEntity addedEntity = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
					entityType, session, faker);
				final Optional<SealedEntity> upsertedEntity = this.dataGenerator.generateEntities(
						productSchema, randomEntityPicker, SEED)
					.limit(1)
					.map(session::upsertAndFetchEntity)
					.findFirst();
				assertTrue(upsertedEntity.isPresent());
				return upsertedEntity.get();
			}
		);

		assertThrows(
			ConflictingCatalogMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntity theEntity = session.getEntity(
							productSchema.getName(),
							addedEntity.getPrimaryKey(),
							entityFetchAllContent()
						)
						.orElseThrow();

					final Long basePriority = theEntity.getAttribute(ATTRIBUTE_PRIORITY, Long.class);
					theEntity
						.openForWrite()
						.mutate(
							new ApplyDeltaAttributeMutation<>(
								ATTRIBUTE_PRIORITY, 1L,
								// this won't allow any other increment updates concurrently
								LongNumberRange.to(basePriority + 1L)
							)
						)
						.upsertVia(session);

					final CountDownLatch latch = new CountDownLatch(1);
					new Thread(() -> {
						try {
							// this concurrent session will try to do the same, and commits first
							evita.updateCatalog(
								TEST_CATALOG,
								concurrentSession -> {
									// this mutation will generate a conflict, but only at the time of the commit, not now
									concurrentSession.getEntity(
											productSchema.getName(), addedEntity.getPrimaryKey(),
											entityFetchAllContent()
										)
										.orElseThrow()
										.openForWrite()
										.mutate(new ApplyDeltaAttributeMutation<>(ATTRIBUTE_PRIORITY, 1L))
										.upsertVia(concurrentSession);
								}
							);
						} finally {
							latch.countDown();
						}
					}).start();

					try {
						latch.await();
					} catch (InterruptedException e) {
						fail("Test thread was interrupted!", e);
					}

					log.info("Attempting to commit conflicting transaction...");
				}
			)
		);
	}

	@DisplayName("A lone range-constrained delta whose result lands on the range boundary commits without a false conflict")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldCommitLoneRangeConstrainedDeltaOnBoundaryWithoutFalseConflict(
		EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		final TransactionOptions txOptions = ((Evita) originalEvita).getConfiguration().transaction();
		final Evita evita = reinitializeEvitaWithConfig(
			originalEvita,
			builder -> builder.transaction(
				TransactionOptions.builder(txOptions)
					.conflictResolution(ConflictPolicy.ENTITY, GranularConflictPolicy.ENTITY_ATTRIBUTE)
					.build()
			)
		);

		final SealedEntity addedEntity = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
					entityType, session, faker);
				final Optional<SealedEntity> upsertedEntity = this.dataGenerator.generateEntities(
						productSchema, randomEntityPicker, SEED)
					.limit(1)
					.map(session::upsertAndFetchEntity)
					.findFirst();
				assertTrue(upsertedEntity.isPresent());
				return upsertedEntity.get();
			}
		);

		final Long basePriority = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntity(productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
					.orElseThrow()
					.getAttribute(ATTRIBUTE_PRIORITY, Long.class);
			}
		);

		// a single, uncontended delta of +1 whose post-application value lands exactly on the inclusive
		// upper bound must be accepted: the only accumulated value is basePriority + 1, never + 2
		assertDoesNotThrow(
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(
							productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent()
						)
						.orElseThrow()
						.openForWrite()
						.mutate(
							new ApplyDeltaAttributeMutation<>(
								ATTRIBUTE_PRIORITY, 1L, LongNumberRange.to(basePriority + 1L)
							)
						)
						.upsertVia(session);
				}
			)
		);

		final Long updatedPriority = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntity(productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
					.orElseThrow()
					.getAttribute(ATTRIBUTE_PRIORITY, Long.class);
			}
		);
		assertEquals(Long.valueOf(basePriority + 1L), updatedPriority);
	}

	@DisplayName("Two committed deltas of different values on the same attribute accumulate against an incoming delta's range")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldAccumulateDistinctCommittedDeltasAgainstIncomingDeltaRange(
		EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		final TransactionOptions txOptions = ((Evita) originalEvita).getConfiguration().transaction();
		final Evita evita = reinitializeEvitaWithConfig(
			originalEvita,
			builder -> builder.transaction(
				TransactionOptions.builder(txOptions)
					.conflictResolution(ConflictPolicy.ENTITY, GranularConflictPolicy.ENTITY_ATTRIBUTE)
					.build()
			)
		);

		final SealedEntity addedEntity = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
					entityType, session, faker);
				final Optional<SealedEntity> upsertedEntity = this.dataGenerator.generateEntities(
						productSchema, randomEntityPicker, SEED)
					.limit(1)
					.map(session::upsertAndFetchEntity)
					.findFirst();
				assertTrue(upsertedEntity.isPresent());
				return upsertedEntity.get();
			}
		);

		// the incoming transaction snapshots the pre-conflict value and stages a +1 delta whose own result
		// (base + 1) sits well within the range; only the two concurrently committed deltas of distinct
		// values (+5, +3) push the true accumulated value (base + 9) past the upper bound base + 5. Because
		// the two committed deltas differ they occupy the same accumulation slot only when grouped by the
		// delta-agnostic aggregation key, so this asserts they are summed rather than each looked up alone.
		assertThrows(
			ConflictingCatalogCommutativeMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntity theEntity = session.getEntity(
							productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent()
						)
						.orElseThrow();
					final Long basePriority = theEntity.getAttribute(ATTRIBUTE_PRIORITY, Long.class);

					theEntity
						.openForWrite()
						.mutate(
							new ApplyDeltaAttributeMutation<>(
								ATTRIBUTE_PRIORITY, 1L, LongNumberRange.to(basePriority + 5L)
							)
						)
						.upsertVia(session);

					try {
						// first concurrent transaction commits a +5 delta on the same attribute
						executeConcurrentUpdate(
							evita, TEST_CATALOG, concurrentSession -> {
								concurrentSession.getEntity(
										productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent()
									)
									.orElseThrow()
									.openForWrite()
									.mutate(new ApplyDeltaAttributeMutation<>(ATTRIBUTE_PRIORITY, 5L))
									.upsertVia(concurrentSession);
							}
						);
						// second concurrent transaction commits a distinct +3 delta on the same attribute
						executeConcurrentUpdate(
							evita, TEST_CATALOG, concurrentSession -> {
								concurrentSession.getEntity(
										productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent()
									)
									.orElseThrow()
									.openForWrite()
									.mutate(new ApplyDeltaAttributeMutation<>(ATTRIBUTE_PRIORITY, 3L))
									.upsertVia(concurrentSession);
							}
						);
					} catch (InterruptedException e) {
						fail("Test thread was interrupted!", e);
					}

					log.info("Attempting to commit conflicting transaction...");
				}
			)
		);
	}

	@DisplayName("When a committed range-constrained delta races an incoming absolute set of the same attribute, conflict is raised (committed-delta direction).")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldRaiseConflictWhenCommittedDeltaRacesIncomingAbsoluteSet(
		EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		// refine the coarse ENTITY policy with ENTITY_ATTRIBUTE granularity so that both the absolute set
		// and the delta produce attribute-level (not entity-level) conflict keys — this is the granular
		// level at which the commutative-vs-absolute containment probe must fire
		final TransactionOptions txOptions = ((Evita) originalEvita).getConfiguration().transaction();
		final Evita evita = reinitializeEvitaWithConfig(
			originalEvita,
			builder -> builder.transaction(
				TransactionOptions.builder(txOptions)
					.conflictResolution(ConflictPolicy.ENTITY, GranularConflictPolicy.ENTITY_ATTRIBUTE)
					.build()
			)
		);

		final SealedEntity addedEntity = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
					entityType, session, faker);
				final Optional<SealedEntity> upsertedEntity = this.dataGenerator.generateEntities(
						productSchema, randomEntityPicker, SEED)
					.limit(1)
					.map(session::upsertAndFetchEntity)
					.findFirst();
				assertTrue(upsertedEntity.isPresent());
				return upsertedEntity.get();
			}
		);

		assertThrows(
			ConflictingCatalogMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntity theEntity = session.getEntity(
							productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent()
						)
						.orElseThrow();
					final Long basePriority = theEntity.getAttribute(ATTRIBUTE_PRIORITY, Long.class);

					// incoming transaction absolutely overwrites the attribute
					theEntity
						.openForWrite()
						.setAttribute(ATTRIBUTE_PRIORITY, basePriority + 100L)
						.upsertVia(session);

					try {
						// concurrent transaction commits a range-constrained delta on the same attribute first
						executeConcurrentUpdate(
							evita, TEST_CATALOG, concurrentSession -> {
								final SealedEntity concurrentEntity = concurrentSession.getEntity(
										productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent()
									)
									.orElseThrow();
								final Long concurrentBase = concurrentEntity.getAttribute(ATTRIBUTE_PRIORITY, Long.class);
								concurrentEntity
									.openForWrite()
									.mutate(new ApplyDeltaAttributeMutation<>(
										ATTRIBUTE_PRIORITY, 1L, LongNumberRange.to(concurrentBase + 5L)
									))
									.upsertVia(concurrentSession);
							}
						);
					} catch (InterruptedException e) {
						fail("Test thread was interrupted!", e);
					}

					log.info("Attempting to commit conflicting transaction...");
				}
			)
		);
	}

	@DisplayName("When a committed absolute set races an incoming range-constrained delta of the same attribute, conflict is raised (committed-absolute direction).")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldRaiseConflictWhenCommittedAbsoluteSetRacesIncomingDelta(
		EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		final TransactionOptions txOptions = ((Evita) originalEvita).getConfiguration().transaction();
		final Evita evita = reinitializeEvitaWithConfig(
			originalEvita,
			builder -> builder.transaction(
				TransactionOptions.builder(txOptions)
					.conflictResolution(ConflictPolicy.ENTITY, GranularConflictPolicy.ENTITY_ATTRIBUTE)
					.build()
			)
		);

		final SealedEntity addedEntity = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
					entityType, session, faker);
				final Optional<SealedEntity> upsertedEntity = this.dataGenerator.generateEntities(
						productSchema, randomEntityPicker, SEED)
					.limit(1)
					.map(session::upsertAndFetchEntity)
					.findFirst();
				assertTrue(upsertedEntity.isPresent());
				return upsertedEntity.get();
			}
		);

		assertThrows(
			ConflictingCatalogMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntity theEntity = session.getEntity(
							productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent()
						)
						.orElseThrow();
					final Long basePriority = theEntity.getAttribute(ATTRIBUTE_PRIORITY, Long.class);

					// incoming transaction applies a range-constrained delta on the attribute
					theEntity
						.openForWrite()
						.mutate(new ApplyDeltaAttributeMutation<>(
							ATTRIBUTE_PRIORITY, 1L, LongNumberRange.to(basePriority + 5L)
						))
						.upsertVia(session);

					try {
						// concurrent transaction commits an absolute overwrite of the same attribute first
						executeConcurrentUpdate(
							evita, TEST_CATALOG, concurrentSession -> {
								final SealedEntity concurrentEntity = concurrentSession.getEntity(
										productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent()
									)
									.orElseThrow();
								final Long concurrentBase = concurrentEntity.getAttribute(ATTRIBUTE_PRIORITY, Long.class);
								concurrentEntity
									.openForWrite()
									.setAttribute(ATTRIBUTE_PRIORITY, concurrentBase + 100L)
									.upsertVia(concurrentSession);
							}
						);
					} catch (InterruptedException e) {
						fail("Test thread was interrupted!", e);
					}

					log.info("Attempting to commit conflicting transaction...");
				}
			)
		);
	}

	@DisplayName("When a committed entity removal races an incoming granular attribute update under granular policy, conflict is raised.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldRaiseConflictWhenRemovalRacesGranularAttributeUpdate(
		EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		// under a granular policy an entity removal emits only the coarse entity conflict key; scope
		// containment must still make it conflict with a concurrent finer-grained attribute update to the
		// same entity (the entity key contains the attribute key)
		final TransactionOptions txOptions = ((Evita) originalEvita).getConfiguration().transaction();
		final Evita evita = reinitializeEvitaWithConfig(
			originalEvita,
			builder -> builder.transaction(
				TransactionOptions.builder(txOptions)
					.conflictResolution(ConflictPolicy.ENTITY, GranularConflictPolicy.ENTITY_ATTRIBUTE)
					.build()
			)
		);

		final SealedEntity addedEntity = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
					entityType, session, faker);
				final Optional<SealedEntity> upsertedEntity = this.dataGenerator.generateEntities(
						productSchema, randomEntityPicker, SEED)
					.limit(1)
					.map(session::upsertAndFetchEntity)
					.findFirst();
				assertTrue(upsertedEntity.isPresent());
				return upsertedEntity.get();
			}
		);

		assertThrows(
			ConflictingCatalogMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntity theEntity = session.getEntity(
							productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent()
						)
						.orElseThrow();
					final Long basePriority = theEntity.getAttribute(ATTRIBUTE_PRIORITY, Long.class);

					// incoming transaction updates a single attribute (granular scope)
					theEntity
						.openForWrite()
						.setAttribute(ATTRIBUTE_PRIORITY, basePriority + 100L)
						.upsertVia(session);

					try {
						// concurrent transaction removes the whole entity and commits first
						executeConcurrentUpdate(
							evita, TEST_CATALOG,
							concurrentSession -> concurrentSession.deleteEntity(
								productSchema.getName(), addedEntity.getPrimaryKey()
							)
						);
					} catch (InterruptedException e) {
						fail("Test thread was interrupted!", e);
					}

					log.info("Attempting to commit conflicting transaction...");
				}
			)
		);
	}

	@DisplayName("When two parallel transactions update same product on conflicting granular level (same attributes) via reference attribute delta mutation, no conflict is raised.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldNotRaiseConflictWhenTwoProductsAreUpdatedConcurrentlyOnConflictingGranularLevelViaReferenceAttributeDeltaChange(
		EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		final EvitaConfiguration originalConfiguration = ((Evita) originalEvita).getConfiguration();
		originalEvita.close();

		// reinitialize evita with a specific narrowed WAL limitations
		final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.name(originalConfiguration.name())
				.transaction(
					TransactionOptions.builder(originalConfiguration.transaction())
						.conflictResolution(ConflictPolicy.ENTITY, GranularConflictPolicy.REFERENCE_ATTRIBUTE)
						.build()
				)
				.storage(originalConfiguration.storage())
				.export(originalConfiguration.export())
				.server(originalConfiguration.server())
				.cache(originalConfiguration.cache())
				.build()
		);
		evita.waitUntilFullyInitialized();

		final SealedEntity addedEntity = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
					entityType, session, faker);
				final Optional<SealedEntity> upsertedEntity = this.dataGenerator.generateEntities(
						productSchema, randomEntityPicker, SEED)
					.limit(1)
					.map(session::upsertAndFetchEntity)
					.findFirst();
				assertTrue(upsertedEntity.isPresent());
				return upsertedEntity.get();
			}
		);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity theEntity = session.getEntity(
						productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
					.orElseThrow();
				final ReferenceKey referenceKey = theEntity
					.getReferences(Entities.STORE)
					.stream()
					.filter(it -> it.getAttribute(STORE_PRIORITY) != null)
					.map(ReferenceContract::getReferenceKey)
					.findFirst()
					.orElseThrow();

				theEntity
					.openForWrite()
					.mutate(
						new ReferenceAttributeMutation(
							referenceKey,
							new ApplyDeltaAttributeMutation<>(STORE_PRIORITY, 1L)
						)
					)
					.upsertVia(session);

				final CountDownLatch latch = new CountDownLatch(1);
				new Thread(() -> {
					try {
						// this concurrent session will try to do the same, and commits first
						evita.updateCatalog(
							TEST_CATALOG,
							concurrentSession -> {
								// this mutation will generate a conflict, but only at the time of the commit, not now
								concurrentSession.getEntity(
										productSchema.getName(), addedEntity.getPrimaryKey(),
										entityFetchAllContent()
									)
									.orElseThrow()
									.openForWrite()
									.mutate(
										new ReferenceAttributeMutation(
											referenceKey,
											new ApplyDeltaAttributeMutation<>(STORE_PRIORITY, 1L)
										)
									)
									.upsertVia(concurrentSession);
							}
						);
					} finally {
						latch.countDown();
					}
				}).start();

				try {
					latch.await();
				} catch (InterruptedException e) {
					fail("Test thread was interrupted!", e);
				}

				log.info("Attempting to commit conflicting transaction...");
			}
		);
	}

	@DisplayName("When two parallel transactions update same product on conflicting granular level (same attributes) via reference attribute delta mutation, no conflict is raised when change in range.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldNotRaiseConflictWhenTwoProductsAreUpdatedConcurrentlyOnConflictingGranularLevelViaReferenceAttributeDeltaChangeInRange(
		EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		final EvitaConfiguration originalConfiguration = ((Evita) originalEvita).getConfiguration();
		originalEvita.close();

		// reinitialize evita with a specific narrowed WAL limitations
		final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.name(originalConfiguration.name())
				.transaction(
					TransactionOptions.builder(originalConfiguration.transaction())
						.conflictResolution(ConflictPolicy.ENTITY, GranularConflictPolicy.REFERENCE_ATTRIBUTE)
						.build()
				)
				.storage(originalConfiguration.storage())
				.export(originalConfiguration.export())
				.server(originalConfiguration.server())
				.cache(originalConfiguration.cache())
				.build()
		);
		evita.waitUntilFullyInitialized();

		final SealedEntity addedEntity = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
					entityType, session, faker);
				final Optional<SealedEntity> upsertedEntity = this.dataGenerator.generateEntities(
						productSchema, randomEntityPicker, SEED)
					.limit(1)
					.map(session::upsertAndFetchEntity)
					.findFirst();
				assertTrue(upsertedEntity.isPresent());
				return upsertedEntity.get();
			}
		);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity theEntity = session.getEntity(
						productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
					.orElseThrow();
				final ReferenceKey referenceKey = theEntity
					.getReferences(Entities.STORE)
					.stream()
					.filter(it -> it.getAttribute(STORE_PRIORITY) != null)
					.map(ReferenceContract::getReferenceKey)
					.findFirst()
					.orElseThrow();
				final Long currentPriority = theEntity
					.getReference(referenceKey)
					.orElseThrow()
					.getAttribute(STORE_PRIORITY, Long.class);

				theEntity
					.openForWrite()
					.mutate(
						new ReferenceAttributeMutation(
							referenceKey,
							new ApplyDeltaAttributeMutation<>(
								STORE_PRIORITY, 1L, LongNumberRange.to(currentPriority + 10L))
						)
					)
					.upsertVia(session);

				final CountDownLatch latch = new CountDownLatch(1);
				new Thread(() -> {
					try {
						// this concurrent session will try to do the same, and commits first
						evita.updateCatalog(
							TEST_CATALOG,
							concurrentSession -> {
								// this mutation will generate a conflict, but only at the time of the commit, not now
								concurrentSession.getEntity(
										productSchema.getName(), addedEntity.getPrimaryKey(),
										entityFetchAllContent()
									)
									.orElseThrow()
									.openForWrite()
									.mutate(
										new ReferenceAttributeMutation(
											referenceKey,
											new ApplyDeltaAttributeMutation<>(STORE_PRIORITY, 1L)
										)
									)
									.upsertVia(concurrentSession);
							}
						);
					} finally {
						latch.countDown();
					}
				}).start();

				try {
					latch.await();
				} catch (InterruptedException e) {
					fail("Test thread was interrupted!", e);
				}

				log.info("Attempting to commit conflicting transaction...");
			}
		);
	}

	@DisplayName("When two parallel transactions update same product on conflicting granular level (same attributes) via reference attribute delta mutation, conflict is raised when not in range.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldRaiseConflictWhenTwoProductsAreUpdatedConcurrentlyOnConflictingGranularLevelViaReferenceAttributeDeltaChange(
		EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
		final EvitaConfiguration originalConfiguration = ((Evita) originalEvita).getConfiguration();
		originalEvita.close();

		// reinitialize evita with a specific narrowed WAL limitations
		final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.name(originalConfiguration.name())
				.transaction(
					TransactionOptions.builder(originalConfiguration.transaction())
						.conflictResolution(ConflictPolicy.ENTITY, GranularConflictPolicy.REFERENCE_ATTRIBUTE)
						.build()
				)
				.storage(originalConfiguration.storage())
				.export(originalConfiguration.export())
				.server(originalConfiguration.server())
				.cache(originalConfiguration.cache())
				.build()
		);
		evita.waitUntilFullyInitialized();

		final SealedEntity addedEntity = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
					entityType, session, faker);
				final Optional<SealedEntity> upsertedEntity = this.dataGenerator.generateEntities(
						productSchema, randomEntityPicker, SEED)
					.limit(1)
					.map(session::upsertAndFetchEntity)
					.findFirst();
				assertTrue(upsertedEntity.isPresent());
				return upsertedEntity.get();
			}
		);

		assertThrows(
			ConflictingCatalogCommutativeMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntity theEntity = session.getEntity(
							productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
						.orElseThrow();
					final ReferenceKey referenceKey = theEntity
						.getReferences(Entities.STORE)
						.stream()
						.filter(it -> it.getAttribute(STORE_PRIORITY) != null)
						.map(ReferenceContract::getReferenceKey)
						.findFirst()
						.orElseThrow();
					final Long currentPriority = theEntity
						.getReference(referenceKey)
						.orElseThrow()
						.getAttribute(STORE_PRIORITY, Long.class);

					theEntity
						.openForWrite()
						.mutate(
							new ReferenceAttributeMutation(
								referenceKey,
								new ApplyDeltaAttributeMutation<>(
									STORE_PRIORITY, 1L, LongNumberRange.to(currentPriority + 1L))
							)
						)
						.upsertVia(session);

					final CountDownLatch latch = new CountDownLatch(1);
					new Thread(() -> {
						try {
							// this concurrent session will try to do the same, and commits first
							evita.updateCatalog(
								TEST_CATALOG,
								concurrentSession -> {
									// this mutation will generate a conflict, but only at the time of the commit, not now
									concurrentSession.getEntity(
											productSchema.getName(), addedEntity.getPrimaryKey(),
											entityFetchAllContent()
										)
										.orElseThrow()
										.openForWrite()
										.mutate(
											new ReferenceAttributeMutation(
												referenceKey,
												new ApplyDeltaAttributeMutation<>(
													STORE_PRIORITY, 1L, LongNumberRange.to(currentPriority + 1L))
											)
										)
										.upsertVia(concurrentSession);
								}
							);
						} finally {
							latch.countDown();
						}
					}).start();

					try {
						latch.await();
					} catch (InterruptedException e) {
						fail("Test thread was interrupted!", e);
					}

					log.info("Attempting to commit conflicting transaction...");
				}
			)
		);
	}

	@DisplayName("When a committed reference removal races an incoming update of an attribute on the same reference, conflict is raised.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldRaiseConflictWhenReferenceRemovalRacesReferenceAttributeUpdate(
		EvitaContract originalEvita, SealedEntitySchema productSchema
	) throws Exception {
		// removing a reference must conflict with a concurrent update to that reference's attribute: the
		// coarse reference key contains the finer reference-attribute key through the ancestor chain
		final Evita evita = reinitializeEvitaWithConfig(
			originalEvita,
			builder -> builder.transaction(
				TransactionOptions.builder(builder.build().transaction())
					.conflictResolution(ConflictPolicy.ENTITY, GranularConflictPolicy.REFERENCE, GranularConflictPolicy.REFERENCE_ATTRIBUTE)
					.build()
			)
		);

		final SealedEntity addedEntity = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
					entityType, session, faker);
				final Optional<SealedEntity> upsertedEntity = this.dataGenerator.generateEntities(
						productSchema, randomEntityPicker, SEED)
					.limit(1)
					.map(session::upsertAndFetchEntity)
					.findFirst();
				assertTrue(upsertedEntity.isPresent());
				return upsertedEntity.get();
			}
		);

		assertThrows(
			ConflictingCatalogMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntity theEntity = session.getEntity(
							productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
						.orElseThrow();
					final ReferenceKey referenceKey = theEntity
						.getReferences(Entities.STORE)
						.stream()
						.filter(it -> it.getAttribute(STORE_PRIORITY) != null)
						.map(ReferenceContract::getReferenceKey)
						.findFirst()
						.orElseThrow();

					// the incoming transaction updates the attribute of the store reference
					theEntity
						.openForWrite()
						.mutate(
							new ReferenceAttributeMutation(
								referenceKey,
								new ApplyDeltaAttributeMutation<>(STORE_PRIORITY, 1L)
							)
						)
						.upsertVia(session);

					try {
						// the concurrent transaction removes that very reference and commits first
						executeConcurrentUpdate(
							evita, TEST_CATALOG, concurrentSession ->
								concurrentSession.getEntity(
										productSchema.getName(), addedEntity.getPrimaryKey(),
										entityFetchAllContent()
									)
									.orElseThrow()
									.openForWrite()
									.removeReference(referenceKey)
									.upsertVia(concurrentSession)
						);
					} catch (InterruptedException e) {
						fail("Test thread was interrupted!", e);
					}

					log.info("Attempting to commit reference-attribute update after concurrent reference removal...");
				}
			)
		);
	}

	@DisplayName("When parallel transactions update product on granular level (different attributes), and remove it completely, conflict is raised.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldRaiseConflictWhenTwoProductsAreUpdatedAndRemovedConcurrentlyOnGranularLevel(
		EvitaContract originalEvita,
		SealedEntitySchema productSchema
	) throws Exception {
		final EvitaConfiguration originalConfiguration = ((Evita) originalEvita).getConfiguration();
		originalEvita.close();

		// reinitialize evita with a specific narrowed WAL limitations
		final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.name(originalConfiguration.name())
				.transaction(
					TransactionOptions.builder(originalConfiguration.transaction())
						.conflictResolution(ConflictPolicy.ENTITY, GranularConflictPolicy.ENTITY_ATTRIBUTE)
						.build()
				)
				.storage(originalConfiguration.storage())
				.export(originalConfiguration.export())
				.server(originalConfiguration.server())
				.cache(originalConfiguration.cache())
				.build()
		);
		evita.waitUntilFullyInitialized();

		final SealedEntity addedEntity = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
					entityType, session, faker);
				final Optional<SealedEntity> upsertedEntity = this.dataGenerator.generateEntities(
						productSchema, randomEntityPicker, SEED)
					.limit(1)
					.map(session::upsertAndFetchEntity)
					.findFirst();
				assertTrue(upsertedEntity.isPresent());
				return upsertedEntity.get();
			}
		);

		assertThrows(
			ConflictingCatalogMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTRIBUTE_PRIORITY, 19846L)
						.upsertVia(session);

					final CountDownLatch latch = new CountDownLatch(1);
					new Thread(() -> {
						try {
							// this concurrent session will try to do the same, and commits first
							evita.updateCatalog(
								TEST_CATALOG,
								concurrentSession -> {
									// this mutation will generate a conflict, but only at the time of the commit, not now
									assertTrue(
										concurrentSession.deleteEntity(
											productSchema.getName(), addedEntity.getPrimaryKey()
										)
									);
								}
							);
						} finally {
							latch.countDown();
						}
					}).start();

					try {
						latch.await();
					} catch (InterruptedException e) {
						fail("Test thread was interrupted!", e);
					}

					log.info("Attempting to commit non-conflicting transaction...");
				}
			)
		);
	}

	@DisplayName("When parallel transactions remove product and update product on granular level (different attributes), conflict is raised.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldRaiseConflictWhenTwoProductsAreRemovedAndUpdatedConcurrentlyOnGranularLevel(
		EvitaContract originalEvita,
		SealedEntitySchema productSchema
	) throws Exception {
		final EvitaConfiguration originalConfiguration = ((Evita) originalEvita).getConfiguration();
		originalEvita.close();

		// reinitialize evita with a specific narrowed WAL limitations
		final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.name(originalConfiguration.name())
				.transaction(
					TransactionOptions.builder(originalConfiguration.transaction())
						.conflictResolution(ConflictPolicy.ENTITY, GranularConflictPolicy.ENTITY_ATTRIBUTE)
						.build()
				)
				.storage(originalConfiguration.storage())
				.export(originalConfiguration.export())
				.server(originalConfiguration.server())
				.cache(originalConfiguration.cache())
				.build()
		);
		evita.waitUntilFullyInitialized();

		final SealedEntity addedEntity = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
					entityType, session, faker);
				final Optional<SealedEntity> upsertedEntity = this.dataGenerator.generateEntities(
						productSchema, randomEntityPicker, SEED)
					.limit(1)
					.map(session::upsertAndFetchEntity)
					.findFirst();
				assertTrue(upsertedEntity.isPresent());
				return upsertedEntity.get();
			}
		);

		assertThrows(
			ConflictingCatalogMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(
						session.deleteEntity(
							productSchema.getName(), addedEntity.getPrimaryKey()
						)
					);

					final CountDownLatch latch = new CountDownLatch(1);
					new Thread(() -> {
						try {
							// this concurrent session will try to do the same, and commits first
							evita.updateCatalog(
								TEST_CATALOG,
								concurrentSession -> {
									// this mutation will generate a conflict, but only at the time of the commit, not now
									concurrentSession.getEntity(
											productSchema.getName(), addedEntity.getPrimaryKey(), entityFetchAllContent())
										.orElseThrow()
										.openForWrite()
										.setAttribute(ATTRIBUTE_PRIORITY, 19846L)
										.upsertVia(concurrentSession);
								}
							);
						} finally {
							latch.countDown();
						}
					}).start();

					try {
						latch.await();
					} catch (InterruptedException e) {
						fail("Test thread was interrupted!", e);
					}

					log.info("Attempting to commit non-conflicting transaction...");
				}
			)
		);
	}

	@DisplayName("When parallel transactions update product on granular level (different attributes), and remove it completely, conflict is raised.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldRaiseConflictWhenTwoProductsAreUpdatedAndRemovedConcurrentlyEvenIfRingBufferRotated(
		EvitaContract originalEvita,
		SealedEntitySchema productSchema
	) throws Exception {
		final EvitaConfiguration originalConfiguration = ((Evita) originalEvita).getConfiguration();
		originalEvita.close();

		// reinitialize evita with a specific narrowed WAL limitations
		final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.name(originalConfiguration.name())
				.transaction(
					TransactionOptions.builder(originalConfiguration.transaction())
						.conflictRingBufferSize(5)
						.build()
				)
				.storage(originalConfiguration.storage())
				.export(originalConfiguration.export())
				.server(originalConfiguration.server())
				.cache(originalConfiguration.cache())
				.build()
		);
		evita.waitUntilFullyInitialized();

		final List<SealedEntity> createdEntities = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final List<SealedEntity> result = new ArrayList<>(10);
				for (int i = 0; i < 10; i++) {
					final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
						entityType, session, faker);
					final Optional<SealedEntity> upsertedEntity = this.dataGenerator.generateEntities(
							productSchema, randomEntityPicker, SEED + i)
						.limit(1)
						.map(session::upsertAndFetchEntity)
						.findFirst();
					assertTrue(upsertedEntity.isPresent());
					result.add(upsertedEntity.get());
				}
				return result;
			}
		);

		assertThrows(
			ConflictingCatalogMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					// this mutation will generate a conflict, but only at the time of the commit, not now
					assertTrue(
						session.deleteEntity(
							productSchema.getName(), createdEntities.get(0).getPrimaryKey()
						)
					);

					final CountDownLatch latch = new CountDownLatch(1);
					new Thread(() -> {
						try {
							// this concurrent session will try to do the same, and commits first
							evita.updateCatalog(
								TEST_CATALOG,
								concurrentSession -> {
									final Random rnd = new Random();

									for (SealedEntity createdEntity : createdEntities) {
										final BiFunction<String, Faker, Integer> rep = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
											entityType, concurrentSession, faker);

										final ModificationFunction mf1 = this.dataGenerator.createModificationFunction(
											rep, rnd);

										// this mutation will generate a conflict, but only at the time of the commit, not now
										mf1.apply(
											concurrentSession.getEntity(
													productSchema.getName(), createdEntity.getPrimaryKey(),
													entityFetchAllContent()
												)
												.orElseThrow()
										).upsertVia(concurrentSession);
									}
								}
							);
						} finally {
							latch.countDown();
						}
					}).start();

					try {
						latch.await();
					} catch (InterruptedException e) {
						fail("Test thread was interrupted!", e);
					}

					log.info("Attempting to commit non-conflicting transaction...");
				}
			)
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
				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
					entityType, session, faker);
				final Optional<SealedEntity> upsertedEntity = this.dataGenerator.generateEntities(
						productSchema, randomEntityPicker, SEED)
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
				final Optional<SealedEntity> fetchedEntity = session.getEntity(
					productSchema.getName(), theEntity.getPrimaryKey());
				assertTrue(fetchedEntity.isPresent());
				assertEquals(theEntity, fetchedEntity.get());
			}
		);
	}

	@DisplayName("Update catalog with another product - asynchronously using runnable.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldUpdateCatalogWithAnotherProductAsynchronouslyUsingRunnable(
		EvitaContract evita, SealedEntitySchema productSchema) {
		final CommitVersions nonSenseValue = new CommitVersions(Long.MIN_VALUE, Integer.MIN_VALUE);
		final AtomicReference<SealedEntity> addedEntity = new AtomicReference<>();
		final CompletableFuture<CommitVersions> nextCatalogVersion = evita.updateCatalogAsync(
				TEST_CATALOG,
				session -> {
					final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
						entityType, session, faker);
					final Optional<SealedEntity> upsertedEntity = this.dataGenerator.generateEntities(
							productSchema, randomEntityPicker, SEED)
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
				if (nextCatalogVersion.isDone() && nextCatalogVersion.getNow(nonSenseValue)
					.catalogVersion() == catalogVersion) {
					// the entity is already propagated to indexes
					final Optional<SealedEntity> fetchedEntity = session.getEntity(
						productSchema.getName(), addedEntityPrimaryKey);
					assertTrue(fetchedEntity.isPresent());
				} else {
					// the entity will not yet be propagated to indexes
					final Optional<SealedEntity> fetchedEntity = session.getEntity(
						productSchema.getName(), addedEntityPrimaryKey);
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
					final Optional<SealedEntity> entityFetchedAgain = session.getEntity(
						productSchema.getName(), addedEntityPrimaryKey);
					final long catalogVersion = session.getCatalogVersion();
					final long expectedCatalogVersion = nextCatalogVersion.getNow(nonSenseValue).catalogVersion();
					if (entityFetchedAgain.isPresent()) {
						assertEquals(expectedCatalogVersion, catalogVersion);
						return true;
					} else {
						// we must try again to see if the entity is present, because it happens asynchronously
						// the catalog version might have been updated between fetch and version fetch
						assertTrue(
							catalogVersion < expectedCatalogVersion || expectedCatalogVersion == Long.MIN_VALUE || session.getEntity(
								productSchema.getName(), addedEntityPrimaryKey).isPresent(),
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
	void shouldRemoveOldDataFilesAndVerifyTimeTravel(
		EvitaContract originalEvita, SealedEntitySchema productSchema) throws Exception {
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
						// this test deliberately forces compaction on (nearly) every mutating write
						// via a tiny 4KB threshold + 0.9 active-share (design predates the cadence
						// gate), so it needs repeated rapid compactions and is incompatible with any
						// non-zero cadence floor - disable the gate entirely, not just lower it
						.minCompactionIntervalMilliseconds(0)
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
				.export(originalConfiguration.export())
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
						entity -> entity.openForWrite()
							.setAttribute(ATTRIBUTE_CODE, "Iteration #" + itCnt + " modification")
							.upsertVia(session)
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

		// the oldest catalog + collection data files are dropped by the same background compaction that
		// trims the WAL above; a fixed short sleep is racy under load, so poll (bounded) until both first
		// indices advance past 0 rather than asserting on a single 250ms-delayed snapshot
		final long compactionStart = System.currentTimeMillis();
		while ((firstIndexOfCatalogDataFile(catalogPath) <= 0
			|| firstIndexOfCollectionDataFile(catalogPath, Entities.PRODUCT) <= 0)
			&& System.currentTimeMillis() - compactionStart < 60_000) {
			synchronized (this) {
				Thread.sleep(250);
			}
		}

		log.info("Verifying the previous data files were removed as well.");

		// verify that the old data is not present
		assertTrue(firstIndexOfCatalogDataFile(catalogPath) > 0, "Old catalog data file was not removed!");
		assertTrue(
			firstIndexOfCollectionDataFile(catalogPath, Entities.PRODUCT) > 0,
			"Old collection data file was not removed!"
		);

		evita.close();
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
					final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
						entityType, session, faker);

					// Transaction 1: Create 1 entity
					// Transaction 2: Create 2 entities
					// Transaction 3: Create 1 entity
					final int entitiesToCreate = transactionIndex == 1 ? 2 : 1;

					for (int j = 0; j < entitiesToCreate; j++) {
						final SealedEntity entity = this.dataGenerator.generateEntities(
								productSchema, randomEntityPicker, SEED + transactionIndex * 10 + j)
							.limit(1)
							.map(session::upsertAndFetchEntity)
							.findFirst()
							.orElseThrow();
						assertNotNull(entity, "Entity should have been created");
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
				assertTrue(
					previous.getVersion() < current.getVersion(),
					"Transaction mutations should be in chronological order (version " + previous.getVersion() + " should be < " + current.getVersion() + ")"
				);
				assertTrue(
					previous.getCommitTimestamp()
						.isBefore(current.getCommitTimestamp()) || previous.getCommitTimestamp()
						.equals(current.getCommitTimestamp()),
					"Transaction mutations should be in chronological order by commit timestamp"
				);
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
			assertTrue(
				upsertCount >= 4, "Should have at least 4 UPSERT operations (1+2+1 entities created in our test)");

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
					final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(
						entityType, session, faker);

					// Transaction 1: Create 1 entity
					// Transaction 2: Create 2 entities
					// Transaction 3: Create 1 entity
					final int entitiesToCreate = transactionIndex == 1 ? 2 : 1;

					for (int j = 0; j < entitiesToCreate; j++) {
						final SealedEntity entity = this.dataGenerator.generateEntities(
								productSchema, randomEntityPicker, SEED + transactionIndex * 10 + j)
							.limit(1)
							.map(session::upsertAndFetchEntity)
							.findFirst()
							.orElseThrow();
						assertNotNull(entity, "Entity should have been created");
					}

					return session.getCatalogVersion();
				}
			);
		}

		// Test getReversedCommittedMutationStream starting from the last version
		try (final Stream<EngineMutation<?>> reversedMutationStream = ((Evita) evita).getReversedCommittedMutationStream(
			null)) {
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
				assertTrue(
					previous.getVersion() > current.getVersion(),
					"Transaction mutations should be in reverse chronological order (version " + previous.getVersion() + " should be > " + current.getVersion() + ")"
				);
				assertTrue(
					previous.getCommitTimestamp().isAfter(current.getCommitTimestamp()) || previous.getCommitTimestamp()
						.equals(current.getCommitTimestamp()),
					"Transaction mutations should be in reverse chronological order by commit timestamp"
				);
			}

			// Get the first 3 transactions (which should be the last 3 transactions we created, in reverse order)
			final List<TransactionMutation> firstThreeTransactions = reversedTransactionMutations.subList(
				0, Math.min(3, reversedTransactionMutations.size()));

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
			assertTrue(
				upsertCount >= 4, "Should have at least 4 UPSERT operations (1+2+1 entities created in our test)");

			// Verify we have TRANSACTION operations
			final long transactionCount = reversedMutations.stream()
				.filter(mutation -> mutation.operation() == Operation.TRANSACTION)
				.count();
			assertTrue(transactionCount >= 3, "Should have at least 3 TRANSACTION operations");

			// Verify that the highest version transaction comes first in reversed stream
			final TransactionMutation firstTransaction = reversedTransactionMutations.get(0);
			final TransactionMutation lastTransaction = reversedTransactionMutations.get(
				reversedTransactionMutations.size() - 1);
			assertTrue(
				firstTransaction.getVersion() > lastTransaction.getVersion(),
				"First transaction in reversed stream should have higher version than last"
			);
		}
	}

	/* ======================================================================================== */
	/* MUTATION STREAM TESTS */
	/* ======================================================================================== */

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
