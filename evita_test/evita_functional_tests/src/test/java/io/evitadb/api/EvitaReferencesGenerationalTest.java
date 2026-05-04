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

import com.github.javafaker.Faker;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import io.evitadb.test.generator.DataGenerator;
import lombok.extern.apachecommons.CommonsLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.generator.DataGenerator.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * This test contains various integration tests for {@link Evita}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@CommonsLog
@Tag(CONTRACT)
@Tag(QUERY)
@Tag(REFERENCE)
class EvitaReferencesGenerationalTest implements EvitaTestSupport {
	/**
	 * Seed for data generation.
	 */
	private static final long SEED = 10;
	/**
	 * Count of the product that will exist in the database BEFORE the test starts.
	 */
	private static final int INITIAL_COUNT_OF_PRODUCTS = 1000;
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_CATEGORY_GROUP = "categoryGroup";
	private static final String ATTRIBUTE_CATEGORY_ORDER = "categoryOrder";
	private static final String REFERENCE_PRODUCTS = "products";
	/**
	 * Instance of the data generator that is used for randomizing artificial test data.
	 */
	protected final DataGenerator dataGenerator = new DataGenerator();
	/**
	 * Index of created entities that allows to retrieve referenced entities when creating product.
	 */
	protected final Map<Serializable, Integer> generatedEntities = new HashMap<>();
	/**
	 * Function allowing to pseudo randomly pick referenced entity for the product.
	 */
	protected final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> {
		final Integer entityCount = this.generatedEntities.computeIfAbsent(entityType, serializable -> 0);
		final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
		return primaryKey == 0 ? null : primaryKey;
	};
	/**
	 * Created randomized category schema.
	 */
	protected SealedEntitySchema categorySchema;
	/**
	 * Created randomized product schema.
	 */
	protected SealedEntitySchema productSchema;
	/**
	 * Iterator that infinitely produces new artificial products.
	 */
	protected Iterator<EntityBuilder> productIterator;
	/**
	 * Evita instance.
	 */
	private TestPaths paths;
	private Evita evita;

	/**
	 * Creates new product stream for the iteration.
	 */
	protected Stream<EntityBuilder> getProductStream() {
		return this.dataGenerator.generateEntities(
			this.productSchema,
			this.randomEntityPicker,
			SEED
		);
	}

	@BeforeEach
	void setUp() throws IOException {
		this.paths = createTestPaths("EvitaReferencesGenerationalTest");
		this.dataGenerator.clear();
		this.generatedEntities.clear();
		final String catalogName = "testCatalog";
		// prepare database
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
		// create bunch or entities for referencing in products
		this.evita.updateCatalog(
			catalogName,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(ATTRIBUTE_CODE, String.class, GlobalAttributeSchemaEditor::uniqueGlobally)
					.updateVia(session);

				final SealedEntitySchema brandSchema = session.defineEntitySchema(Entities.BRAND)
					.verifySchemaStrictly()
					.withGeneratedPrimaryKey()
					.withoutHierarchy()
					.withoutPrice()
					.withLocale(Locale.ENGLISH, Locale.FRENCH, Locale.GERMAN)
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.filterable().localized().sortable().nullable())
					.updateAndFetchVia(session);

				this.dataGenerator.generateEntities(
						brandSchema,
						this.randomEntityPicker,
						SEED
					)
					.limit(5)
					.map(session::upsertAndFetchEntity)
					.forEach(it -> this.generatedEntities.put(Entities.BRAND, it.getPrimaryKeyOrThrowException()));

				this.categorySchema = session.defineEntitySchema(Entities.CATEGORY)
					.verifySchemaStrictly()
					.withGeneratedPrimaryKey()
					.withoutHierarchy()
					.withoutPrice()
					.withLocale(Locale.ENGLISH, Locale.FRENCH, Locale.GERMAN)
					/* here we define list of attributes with indexes for search / sort */
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.filterable().localized().sortable().nullable())
					.withReflectedReferenceToEntity(
						REFERENCE_PRODUCTS,
						Entities.PRODUCT,
						Entities.CATEGORY,
						whichIs -> whichIs.withCardinality(Cardinality.ZERO_OR_MORE_WITH_DUPLICATES)
							.indexedForFiltering()
							.withAttributesInheritedExcept(ATTRIBUTE_CATEGORY_ORDER)
					)
					.updateAndFetchVia(session);

				this.dataGenerator.generateEntities(
						this.categorySchema,
						this.randomEntityPicker,
						SEED
					)
					.limit(10)
					.map(session::upsertAndFetchEntity)
					.forEach(it -> this.generatedEntities.put(Entities.CATEGORY, it.getPrimaryKeyOrThrowException()));

				this.productSchema = session.defineEntitySchema(Entities.PRODUCT)
					.verifySchemaStrictly()
					.withoutGeneratedPrimaryKey()
					.withoutHierarchy()
					.withPriceInCurrency(
						Currency.getInstance("CZK"),
						Currency.getInstance("EUR"),
						Currency.getInstance("USD"),
						Currency.getInstance("GBP")
					)
					.withLocale(Locale.ENGLISH, Locale.FRENCH, Locale.GERMAN)
					/* here we define list of attributes with indexes for search / sort */
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.filterable().localized().sortable().nullable())
					.withAttribute(ATTRIBUTE_EAN, String.class, whichIs -> whichIs.filterable().nullable())
					.withAttribute(ATTRIBUTE_PRIORITY, Long.class, whichIs -> whichIs.sortable())
					.withAttribute(ATTRIBUTE_VALIDITY, DateTimeRange.class, whichIs -> whichIs.filterable().nullable())
					.withAttribute(ATTRIBUTE_QUANTITY, BigDecimal.class, whichIs -> whichIs.filterable().indexDecimalPlaces(2).nullable())
					.withAttribute(ATTRIBUTE_ALIAS, Boolean.class, whichIs -> whichIs.filterable().withDefaultValue(false))
					/* here we define set of associated data, that can be stored along with entity */
					.withAssociatedData(ASSOCIATED_DATA_REFERENCED_FILES, ReferencedFileSet.class, whichIs -> whichIs.nullable())
					.withAssociatedData(ASSOCIATED_DATA_LABELS, Labels.class, whichIs -> whichIs.localized().nullable())
					/* here we define facets that relate to another entities stored in Evita */
					.withReferenceToEntity(
						Entities.CATEGORY,
						Entities.CATEGORY,
						Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
						whichIs ->
							/* we can specify special attributes on relation */
							whichIs.indexedForFilteringAndPartitioning()
								.indexedWithComponents(
									ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
								.withGroupTypeRelatedToEntity(Entities.BRAND)
								.withAttribute(ATTRIBUTE_CATEGORY_GROUP, String.class, thatIs -> thatIs.filterable().representative())
								.withAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class, thatIs -> thatIs.filterable().sortable())
								.withAttribute(ATTRIBUTE_CATEGORY_ORDER, Long.class, thatIs -> thatIs.sortable())
					)
					.updateAndFetchVia(session);

				this.dataGenerator.generateEntities(
						this.productSchema,
						this.randomEntityPicker,
						SEED
					)
					.limit(INITIAL_COUNT_OF_PRODUCTS)
					.forEach(session::upsertEntity);

				session.goLiveAndClose();
			}
		);
		// create product iterator
		this.productIterator = getProductStream().iterator();
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	void loadTest() {
		this.evita.close();

		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.waitUntilFullyInitialized();

		assertNotNull(this.evita);
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.build();
	}

}
