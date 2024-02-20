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

import com.github.javafaker.Faker;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.core.Transaction;
import io.evitadb.function.TriFunction;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.generator.DataGenerator;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Optional;
import java.util.Set;
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
import java.util.stream.Collectors;

import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
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
public class EvitaTransactionalFunctionalTest {
	private static final String TRANSACTIONAL_DATA_SET = "transactionalDataSet";
	public static final ThreadLocal<Boolean> FETCH_CONTEXT = new ThreadLocal<>();
	private static final int SEED = 42;
	private final DataGenerator dataGenerator = new DataGenerator();

	private static final TriFunction<String, EvitaSessionContract, Faker, Integer> RANDOM_ENTITY_PICKER = (entityType, session, faker) -> {
		final int entityCount = session.getEntityCollectionSize(entityType);
		final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
		return primaryKey == 0 ? null : primaryKey;
	};

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
						.withGeneratedPrimaryKey()
						.updateAndFetchVia(session);
				}
			);
		});
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
	void shouldAutomaticallyGeneratePrimaryKeyInParallel(EvitaContract evita, SealedEntitySchema productSchema) throws Exception {
		final int numberOfThreads = 10;
		final int iterations = 100;
		final ExecutorService service = Executors.newFixedThreadPool(numberOfThreads);
		final CountDownLatch latch = new CountDownLatch(numberOfThreads);
		final Set<PkWithCatalogVersion> primaryKeysWithTxIds = new ConcurrentSkipListSet<>();

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
		long start = System.currentTimeMillis();
		while (
			// cap to one minute
			System.currentTimeMillis() - start < 120_000 &&
			// and finish when the last transaction is visible
			evita.queryCatalog(TEST_CATALOG, session -> { return session.getCatalogVersion(); }) < numberOfThreads * iterations + 1
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

}
