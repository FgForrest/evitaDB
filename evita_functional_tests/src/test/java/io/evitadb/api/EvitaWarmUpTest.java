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

import com.github.javafaker.Faker;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Predecessor;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.generator.DataGenerator;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.function.BiFunction;

import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_URL;

/**
 * This test contains various integration tests for {@link Evita}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
class EvitaWarmUpTest implements EvitaTestSupport {
	public static final String DIR_EVITA_TEST = "evitaWarmUpTest";
	public static final String DIR_EVITA_TEST_EXPORT = "evitaWarmUpTest_export";
	private static final String ATTRIBUTE_ORDER = "order";
	private static final String ATTRIBUTE_CATEGORY_ORDER = "categoryOrder";
	private static final String REFERENCE_CATEGORY_PRODUCTS = "products";
	private static final String ATTRIBUTE_CATEGORY_MARKET = "market";
	private static final String ATTRIBUTE_INCEPTION_YEAR = "inceptionYear";
	private static final String ATTRIBUTE_MARKET_INCEPTION_YEAR = "marketInceptionYear";
	private static final int SEED = 40;
	private static final int PRODUCT_COUNT = 5_000;
	private static final int CATEGORY_COUNT = 10;
	private Evita evita;

	@BeforeEach
	void setUp() {
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_TEST_EXPORT);
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_TEST_EXPORT);
	}

	/**
	 * This test tries to simulate situation when there is a load of entities inserted with reference to a small amount
	 * of entities having reflected reference to that entity. This scenario tries to simulate problem that was documented
	 * <a href="https://github.com/FgForrest/evitaDB/issues/689">in issue #689</a>.
	 */
	@Tag(LONG_RUNNING_TEST)
	@Test
	void shouldGenerateLoadOfDataInWarmUpPhase() {
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.defineCatalog("otherCatalog");

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.updateCatalogSchema(
					session.getCatalogSchema()
						.openForWrite()
						.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.sortable().uniqueGlobally().nullable())
						.withAttribute(ATTRIBUTE_URL, String.class, whichIs -> whichIs.localized().uniqueGlobally().nullable())
				);

				final DataGenerator dataGenerator = new DataGenerator.Builder()
					.registerValueGenerator(
						Entities.PRODUCT, ATTRIBUTE_ORDER,
						faker -> Predecessor.HEAD
					)
					// we need to update the order in second pass
					.registerValueGenerator(
						Entities.PRODUCT, ATTRIBUTE_CATEGORY_ORDER,
						faker -> Predecessor.HEAD
					)
					.build();

				final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> {
					final int entityCount = session.getEntityCollectionSize(entityType);
					final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
					return primaryKey == 0 ? null : primaryKey;
				};

				// we need to create category schema first
				final SealedEntitySchema categorySchema = dataGenerator.getSampleCategorySchema(
					session,
					schemaBuilder -> {
						schemaBuilder
							.withReflectedReferenceToEntity(
								REFERENCE_CATEGORY_PRODUCTS,
								Entities.PRODUCT,
								Entities.CATEGORY,
								whichIs -> whichIs
									.withAttributesInherited()
									.withCardinality(Cardinality.ZERO_OR_MORE)
							);
					}
				);

				// then the product schema
				final SealedEntitySchema productSchema = dataGenerator.getSampleProductSchema(
					session,
					schemaBuilder -> {
						schemaBuilder
							.withAttribute(
								ATTRIBUTE_ORDER, Predecessor.class,
								AttributeSchemaEditor::sortable
							)
							.withReferenceToEntity(
								Entities.CATEGORY,
								Entities.CATEGORY,
								Cardinality.EXACTLY_ONE,
								whichIs -> whichIs.indexedForFilteringAndPartitioning()
									.withAttribute(ATTRIBUTE_CATEGORY_ORDER, Predecessor.class, AttributeSchemaEditor::sortable)
									.withAttribute(ATTRIBUTE_CATEGORY_MARKET, String.class, thatIs -> thatIs.nullable().sortable())
									.withAttribute(ATTRIBUTE_INCEPTION_YEAR, String.class, thatIs -> thatIs.nullable().sortable())
									.withSortableAttributeCompound(
										ATTRIBUTE_MARKET_INCEPTION_YEAR,
										new AttributeElement(ATTRIBUTE_CATEGORY_MARKET, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
										new AttributeElement(ATTRIBUTE_INCEPTION_YEAR, OrderDirection.DESC, OrderBehaviour.NULLS_LAST)
									)
							);
						// we need only category references in this test
						for (String referenceName : schemaBuilder.getReferences().keySet()) {
							if (!referenceName.equals(Entities.CATEGORY)) {
								schemaBuilder.withoutReferenceTo(referenceName);
							}
						}
					}
				);

				// and now data for both of them (since they are intertwined via reflected reference)
				dataGenerator.generateEntities(
						categorySchema,
						randomEntityPicker,
						SEED
					)
					.limit(CATEGORY_COUNT)
					.forEach(session::upsertEntity);

				dataGenerator.generateEntities(
						productSchema,
						(s, faker) -> faker.random().nextInt(1, CATEGORY_COUNT + 1),
						SEED
					)
					.limit(PRODUCT_COUNT)
					.forEach(session::upsertEntity);

				session.goLiveAndClose();
			}
		);

		log.info("Set-up completed");

		this.evita.replaceCatalog(TEST_CATALOG, "otherCatalog");

		this.evita.queryCatalog("otherCatalog", session -> {
			Assertions.assertEquals(CatalogState.ALIVE, session.getCatalogState());
		});
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return getEvitaConfiguration(-1);
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration(int inactivityTimeoutInSeconds) {
		return EvitaConfiguration.builder()
			.server(
				ServerOptions.builder()
					.serviceThreadPool(
						ThreadPoolOptions.serviceThreadPoolBuilder()
							.minThreadCount(1)
							.maxThreadCount(1)
							.queueSize(10_000)
							.build()
					)
					.closeSessionsAfterSecondsOfInactivity(inactivityTimeoutInSeconds)
					.build()
			)
			.storage(
				StorageOptions.builder()
					.storageDirectory(getTestDirectory().resolve(DIR_EVITA_TEST))
					.exportDirectory(getTestDirectory().resolve(DIR_EVITA_TEST_EXPORT))
					.timeTravelEnabled(false)
					.fileSizeCompactionThresholdBytes(1_000_000)
					.minimalActiveRecordShare(0.8)
					.build()
			)
			.build();
	}

}
