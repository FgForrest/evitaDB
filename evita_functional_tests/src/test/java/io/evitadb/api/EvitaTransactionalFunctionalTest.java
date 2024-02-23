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

package io.evitadb.api;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Pool;
import com.github.javafaker.Faker;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.InstanceEditor;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.EvitaSession;
import io.evitadb.core.Transaction;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.function.TriFunction;
import io.evitadb.store.catalog.DefaultIsolatedWalService;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.offsetIndex.io.OffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.WriteOnlyOffHeapWithFileBackupHandle;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.spi.IsolatedWalPersistenceService;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_URL;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test aims to test transactional behaviour of evitaDB.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("Evita entity transactional functionality")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
public class EvitaTransactionalFunctionalTest implements EvitaTestSupport {
	public static final ThreadLocal<Boolean> FETCH_CONTEXT = new ThreadLocal<>();
	private static final String TRANSACTIONAL_DATA_SET = "transactionalDataSet";
	private static final int SEED = 42;
	private static final TriFunction<String, EvitaSessionContract, Faker, Integer> RANDOM_ENTITY_PICKER = (entityType, session, faker) -> {
		final int entityCount = session.getEntityCollectionSize(entityType);
		final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
		return primaryKey == 0 ? null : primaryKey;
	};
	private final DataGenerator dataGenerator = new DataGenerator();
	private final Pool<Kryo> catalogKryoPool = new Pool<>(false, false, 1) {
		@Override
		protected Kryo create() {
			return KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		}
	};
	private final ObservableOutputKeeper observableOutputKeeper = new ObservableOutputKeeper(
		StorageOptions.builder().build()
	);
	private final OffHeapMemoryManager offHeapMemoryManager = new OffHeapMemoryManager(10_000_000, 128);

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
			dataGenerator.generateEntities(
					dataGenerator.getSampleBrandSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(5)
				.forEach(session::upsertEntity);

			dataGenerator.generateEntities(
					dataGenerator.getSampleCategorySchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(10)
				.forEach(session::upsertEntity);

			dataGenerator.generateEntities(
					dataGenerator.getSamplePriceListSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(4)
				.forEach(session::upsertEntity);

			dataGenerator.generateEntities(
					dataGenerator.getSampleStoreSchema(session),
					randomEntityPicker,
					SEED
				)
				.limit(12)
				.forEach(session::upsertEntity);

			// create product schema
			return dataGenerator.getSampleProductSchema(
				session, schemaBuilder -> {
					return schemaBuilder
						.withoutGeneratedPrimaryKey()
						.updateAndFetchVia(session);
				}
			);
		});
	}

	@DisplayName("Catalog should be automatically updated after a load with existing WAL contents.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldPickUpExistingWalOnStartAndReplayItsContents(Evita evita) throws Exception {
		// close evita first so that the processing pipeline is shut down
		final EvitaConfiguration cfg = evita.getConfiguration();
		final SealedCatalogSchema catalogSchema = evita.getCatalogInstance(TEST_CATALOG).orElseThrow().getSchema();
		final EntitySchemaContract productSchema = catalogSchema.getEntitySchema(Entities.PRODUCT).orElseThrow();
		evita.close();

		final Path catalogDirectory = cfg.storage().storageDirectory().resolve(TEST_CATALOG);
		final CatalogWriteAheadLog wal = new CatalogWriteAheadLog(
			TEST_CATALOG,
			catalogDirectory,
			catalogKryoPool,
			StorageOptions.builder().build(),
			TransactionOptions.builder().build(),
			Mockito.mock(ScheduledExecutorService.class)
		);

		// create WAL file with a few contents first
		final Map<Long, List<EntityContract>> generatedEntities = appendWal(
			1L, offHeapMemoryManager, wal, new int[]{3, 4, 2}, catalogSchema, productSchema
		);

		// start evita again and wait for the WAL to be processed
		final Evita secondInstance = new Evita(cfg);

		// verify the documents in the evitaDB catalog
		final long catalogVersion = verifyCatalogContents(secondInstance, generatedEntities, 4L);
		assertEquals(4L, catalogVersion);

		// now shut down evitaDB again
		secondInstance.close();

		// append a few additional WAL entries
		final Map<Long, List<EntityContract>> additionalGeneratedEntities = appendWal(
			4L, offHeapMemoryManager, wal, new int[]{5, 7}, catalogSchema, productSchema
		);

		// start evitaDB again and wait for the WAL to be processed
		final Evita thirdInstance = new Evita(cfg);

		// verify the documents in the evitaDB catalog
		final long nextCatalogVersion = verifyCatalogContents(thirdInstance, additionalGeneratedEntities, 6L);
		assertEquals(6L, nextCatalogVersion);

		thirdInstance.close();
	}

	@DisplayName("Update catalog with another product - synchronously.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldUpdateCatalogWithAnotherProduct(EvitaContract evita, SealedEntitySchema productSchema) {
		final SealedEntity addedEntity = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(entityType, session, faker);
				final Optional<SealedEntity> upsertedEntity = dataGenerator.generateEntities(productSchema, randomEntityPicker, SEED)
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
				final Optional<SealedEntity> upsertedEntity = dataGenerator.generateEntities(productSchema, randomEntityPicker, SEED)
					.limit(1)
					.map(session::upsertAndFetchEntity)
					.findFirst();
				assertTrue(upsertedEntity.isPresent());
				return upsertedEntity.get();
			},
			CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION
		);

		while (!addedEntity.isDone()) {
			Thread.onSpinWait();
		}

		final Integer addedEntityPrimaryKey = addedEntity.get(1, TimeUnit.SECONDS).getPrimaryKey();
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				// the entity will not yet be propagated to indexes
				final Optional<SealedEntity> fetchedEntity = session.getEntity(productSchema.getName(), addedEntityPrimaryKey);
				assertTrue(fetchedEntity.isEmpty());
			}
		);

		boolean expectedResult = false;
		for (int i = 0; i < 10_000; i++) {
			//noinspection NonShortCircuitBooleanExpression
			expectedResult = expectedResult | evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final Optional<SealedEntity> entityFetchedAgain = session.getEntity(productSchema.getName(), addedEntityPrimaryKey);
					if (entityFetchedAgain.isPresent()) {
						return true;
					} else {
						return false;
					}
				}
			);
		}

		assertTrue(expectedResult, "Entity not found in catalog!");
	}

	@DisplayName("Update rollback transaction in manually opened session when exception is thrown.")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Test
	void shouldAutomaticallyRollbackTheTransactionWhenExceptionIsThrownInManuallyOpenedSession(EvitaContract evita, SealedEntitySchema productSchema) {
		final EvitaSessionContract session = evita.createSession(new SessionTraits(TEST_CATALOG, CommitBehavior.WAIT_FOR_INDEX_PROPAGATION, SessionFlags.READ_WRITE));

		final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(entityType, session, faker);
		final Optional<EntityMutation> entityMutation = dataGenerator.generateEntities(productSchema, randomEntityPicker, SEED)
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
					final Optional<EntityMutation> entityMutation = dataGenerator.generateEntities(productSchema, randomEntityPicker, SEED)
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
				final Optional<EntityMutation> entityMutation = dataGenerator.generateEntities(productSchema, randomEntityPicker, SEED)
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
				final Optional<SealedEntity> fetchedEntity = session.getEntity(productSchema.getName(), addedEntity.getPrimaryKey());
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
				final Optional<SealedEntity> upsertedEntity = dataGenerator.generateEntities(productSchema, randomEntityPicker, SEED)
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
		final AtomicReference<SealedEntity> addedEntity = new AtomicReference<>();
		final CompletableFuture<Long> nextCatalogVersion = evita.updateCatalogAsync(
			TEST_CATALOG,
			session -> {
				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> RANDOM_ENTITY_PICKER.apply(entityType, session, faker);
				final Optional<SealedEntity> upsertedEntity = dataGenerator.generateEntities(productSchema, randomEntityPicker, SEED)
					.limit(1)
					.map(session::upsertAndFetchEntity)
					.findFirst();
				assertTrue(upsertedEntity.isPresent());
				addedEntity.set(upsertedEntity.get());
			},
			CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION
		);

		while (!nextCatalogVersion.isDone()) {
			Thread.onSpinWait();
		}

		final Integer addedEntityPrimaryKey = addedEntity.get().getPrimaryKey();
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				System.out.println(session.getCatalogVersion());
				// the entity will not yet be propagated to indexes
				final Optional<SealedEntity> fetchedEntity = session.getEntity(productSchema.getName(), addedEntityPrimaryKey);
				assertTrue(fetchedEntity.isEmpty());
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
					final long expectedCatalogVersion = nextCatalogVersion.getNow(Long.MIN_VALUE);
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

	@DisplayName("Verify code has no problems assigning new PK in concurrent environment")
	@UseDataSet(value = TRANSACTIONAL_DATA_SET, destroyAfterTest = true)
	@Tag(LONG_RUNNING_TEST)
	@Test
	void shouldAutomaticallyGenerateEntitiesInParallel(EvitaContract evita, SealedEntitySchema productSchema) throws Exception {
		final int numberOfThreads = 10;
		final int iterations = 100;
		final ExecutorService service = Executors.newFixedThreadPool(numberOfThreads);
		final CountDownLatch latch = new CountDownLatch(numberOfThreads);
		final Set<PkWithCatalogVersion> primaryKeysWithTxIds = new ConcurrentSkipListSet<>();

		final long initialStart = System.currentTimeMillis();
		final AtomicReference<Exception> thrownException = new AtomicReference<>();
		try (EvitaSessionContract readOnlySession = evita.createReadOnlySession(TEST_CATALOG)) {
			final DataGenerator dataGenerator = new DataGenerator();
			for (int i = 0; i < numberOfThreads; i++) {
				final int threadSeed = SEED + i;
				service.execute(() -> {
					try {
						// primary keys should be automatically generated in monotonic fashion
						dataGenerator.generateEntities(
								productSchema,
								(entityType, faker) -> RANDOM_ENTITY_PICKER.apply(entityType, readOnlySession, faker),
								threadSeed
							)
							.limit(iterations)
							.map(it -> {
								assertFalse(Transaction.getTransaction().isPresent());
								final AtomicReference<EntityReference> createdReference = new AtomicReference<>();
								final CompletableFuture<Long> targetCatalogVersion = evita.updateCatalogAsync(
									TEST_CATALOG,
									session -> {
										final long currentCatalogVersion = session.getCatalogVersion();
										createdReference.set(session.upsertEntity(it));

										// verify that no entity with older transaction id is visible - i.e. SNAPSHOT isolation level
										try {
											for (PkWithCatalogVersion existingPk : primaryKeysWithTxIds) {
												if (existingPk.catalogVersion() <= currentCatalogVersion) {
													assertNotNull(
														session.getEntity(existingPk.getType(), existingPk.getPrimaryKey()).orElse(null),
														"Entity with catalogVersion " + existingPk.catalogVersion() + " is missing in catalog version `" + currentCatalogVersion + "`!"
													);
												} else {
													assertNull(
														session.getEntity(existingPk.getType(), existingPk.getPrimaryKey()).orElse(null),
														"Entity with catalogVersion `" + existingPk.catalogVersion() + "` is present in catalog version `" + currentCatalogVersion + "`!"
													);
												}
											}
										} finally {
											FETCH_CONTEXT.set(false);
										}
									}, CommitBehavior.WAIT_FOR_WAL_PERSISTENCE, SessionFlags.READ_WRITE
								);
								try {
									final Long catalogVersion = targetCatalogVersion.get();
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
					} catch (Exception ex) {
						thrownException.set(ex);
					} finally {
						latch.countDown();
					}
				});
			}
			assertTrue(latch.await(300, TimeUnit.SECONDS), "Timeouted!");
		}

		if (thrownException.get() != null) {
			throw thrownException.get();
		}

		// wait until Evita reaches the last version of the catalog
		long watitingStart = System.currentTimeMillis();
		while (
			// cap to one minute
			System.currentTimeMillis() - watitingStart < 120_000 &&
				// and finish when the last transaction is visible
				evita.queryCatalog(TEST_CATALOG, session -> {
					return session.getCatalogVersion();
				}) < numberOfThreads * iterations + 1
		) {
			Thread.onSpinWait();
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
				" transactions and " + (System.currentTimeMillis() - initialStart) + " ms."
		);
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
		@Nonnull OffHeapMemoryManager offHeapMemoryManager,
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
				isolatedWalFilePath, this.observableOutputKeeper, offHeapMemoryManager
			)
		);

		final Map<Long, List<EntityContract>> entitiesInMutations = CollectionUtils.createHashMap(transactionSizes.length);
		for (int i = 0; i < transactionSizes.length; i++) {
			int txSize = transactionSizes[i];
			final LinkedList<InstanceWithMutation> entities = dataGenerator.generateEntities(
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
				walReference.getContentLength()
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

	private record PkWithCatalogVersion(
		EntityReference entityReference,
		long catalogVersion
	) implements Comparable<PkWithCatalogVersion> {

		@Override
		public int compareTo(PkWithCatalogVersion o) {
			final int first = entityReference.compareTo(o.entityReference);
			return first == 0 ? Long.compare(catalogVersion, o.catalogVersion) : first;
		}

		public String getType() {
			return entityReference.getType();
		}

		public int getPrimaryKey() {
			return entityReference.getPrimaryKey();
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
