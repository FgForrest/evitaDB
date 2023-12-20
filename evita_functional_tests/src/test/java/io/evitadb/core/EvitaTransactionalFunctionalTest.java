/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.core;

import com.github.javafaker.Faker;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
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

import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_URL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
	private static final int SEED = 42;
	private static final TriFunction<String, EvitaSessionContract, Faker, Integer> RANDOM_ENTITY_PICKER = (entityType, session, faker) -> {
		final int entityCount = session.getEntityCollectionSize(entityType);
		final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
		return primaryKey == 0 ? null : primaryKey;
	};

	@DataSet(value = TRANSACTIONAL_DATA_SET, readOnly = false, destroyAfterClass = true)
	SealedEntitySchema setUp(Evita evita) {
		return evita.updateCatalog(TEST_CATALOG, session -> {
			session.updateCatalogSchema(
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.sortable().uniqueGlobally().nullable())
					.withAttribute(ATTRIBUTE_URL, String.class, whichIs -> whichIs.localized().uniqueGlobally().nullable())
			);

			final DataGenerator dataGenerator = new DataGenerator();
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
						.toInstance();
				}
			);
		});
	}

	@DisplayName("Verify code has no problems assigning new PK in concurrent environment")
	@UseDataSet(TRANSACTIONAL_DATA_SET)
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
			for (int i = 0; i < numberOfThreads; i++) {
				service.execute(() -> {
					try {
						final DataGenerator dataGenerator = new DataGenerator();
						// primary keys should be automatically generated in monotonic fashion
						dataGenerator.generateEntities(
								productSchema,
								(entityType, faker) -> RANDOM_ENTITY_PICKER.apply(entityType, readOnlySession, faker),
								SEED
							)
							.limit(iterations)
							.map(it -> evita.updateCatalog(
								TEST_CATALOG,
								session -> {
									final long currentCatalogVersion = session.getCatalogVersion();
									final EntityReference entityReference = session.upsertEntity(it);

									// verify that no entity with older transaction id is visible - i.e. SNAPSHOT isolation level
									for (PkWithCatalogVersion existingPk : primaryKeysWithTxIds) {
										if (existingPk.catalogVersion <= currentCatalogVersion) {
											assertNotNull(
												session.getEntity(existingPk.getType(), existingPk.getPrimaryKey()).orElse(null),
												"Entity with catalogVersion " + existingPk.catalogVersion + " is missing in catalog version `" + currentCatalogVersion + "`!"
											);
										} else {
											assertNull(
												session.getEntity(existingPk.getType(), existingPk.getPrimaryKey()).orElse(null),
												"Entity with catalogVersion `" + existingPk.catalogVersion + "` is present in catalog version `" + currentCatalogVersion + "`!"
											);
										}
									}

									final PkWithCatalogVersion pkWithCatalogVersion = new PkWithCatalogVersion(
										entityReference, session.getCatalogVersion()
									);
									primaryKeysWithTxIds.add(pkWithCatalogVersion);
									return pkWithCatalogVersion;
								}
							))
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
			assertTrue(latch.await(45, TimeUnit.SECONDS), "Timeouted!");
		}

		if (thrownException.get() != null) {
			throw thrownException.get();
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
