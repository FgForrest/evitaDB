/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.api.functional.indexing;

import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.PriceHistogram;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.exception.AttributeNotFilterableException;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import io.evitadb.core.exception.AttributeNotSortableException;
import io.evitadb.core.exception.HierarchyNotIndexedException;
import io.evitadb.core.exception.PriceNotIndexedException;
import io.evitadb.core.exception.ReferenceNotFacetedException;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.functional.indexing.IndexingTestSupport.getGlobalIndex;
import static io.evitadb.api.functional.indexing.IndexingTestSupport.getReferencedEntityIndex;
import static io.evitadb.api.functional.indexing.IndexingTestSupport.getReferencedGroupEntityIndex;
import static io.evitadb.api.functional.indexing.IndexingTestSupport.getReferencedGroupEntityTypeIndex;
import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.utils.StringUtils.normalizeLineEndings;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.INDEXING;

/**
 * This test verifies archiving (changing scope) of the entities.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@SuppressWarnings("SameParameterValue")
@Tag(CONTRACT)
@Tag(INDEXING)
public class EvitaArchivingTest implements EvitaTestSupport, IndexingTestSupport {
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_URL = "url";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_DESCRIPTION = "description";
	private static final String ATTRIBUTE_EAN = "ean";
	private static final String ATTRIBUTE_WIDTH = "width";
	private static final String ATTRIBUTE_CODE_NAME = "codeName";
	private static final String ATTRIBUTE_CODE_EAN = "codeEan";
	private static final String ATTRIBUTE_CATEGORY_OPEN = "open";
	private static final String ATTRIBUTE_CATEGORY_MARKET_OPEN = "marketOpen";
	private static final String ATTRIBUTE_BRAND_EAN = "brandEan";
	private static final String ATTRIBUTE_CATEGORY_MARKET = "market";
	private static final String PRICE_LIST_BASIC = "basic";
	private static final Currency CURRENCY_CZK = Currency.getInstance("CZK");
	private static final Currency CURRENCY_EUR = Currency.getInstance("EUR");
	private static final String REFLECTED_REFERENCE_NAME = "products";

	private TestPaths paths;
	private Evita evita;

	@Nullable
	private static EntityReference queryOne(@Nonnull EvitaSessionContract session, int entityPrimaryKey, @Nonnull Scope... scope) {
		return session.queryOne(
			Query.query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(entityPrimaryKey),
					scope(scope)
				)
			),
			EntityReference.class
		).orElse(null);
	}

	@Nonnull
	private static List<Integer> queryList(@Nonnull EvitaSessionContract session, @Nonnull Scope... scope) {
		return session.queryList(
				Query.query(
					collection(Entities.PRODUCT),
					filterBy(
						scope(scope)
					)
				),
				EntityReference.class
			)
			.stream()
			.map(EntityReference::getPrimaryKey)
			.toList();
	}

	@Nonnull
	private static List<Integer> queryPage(@Nonnull EvitaSessionContract session, @Nonnull Scope... scope) {
		return session.query(
				Query.query(
					collection(Entities.PRODUCT),
					filterBy(
						scope(scope)
					),
					require(
						page(1, Integer.MAX_VALUE)
					)
				),
				EntityReference.class
			)
			.getRecordData()
			.stream()
			.map(EntityReference::getPrimaryKey)
			.toList();
	}

	@Nullable
	private static EntityReference queryOne(@Nonnull EvitaSessionContract session, @Nonnull String code, @Nonnull Scope... scope) {
		return session.queryOne(
			Query.query(
				collection(Entities.PRODUCT),
				filterBy(
					attributeEquals(ATTRIBUTE_CODE, code),
					scope(scope)
				)
			),
			EntityReference.class
		).orElse(null);
	}

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("EvitaArchivingTest");
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("Basic archive and restore")
	class BasicArchiveAndRestoreTest {

		@Test
		@DisplayName("Entity should be removed from indexes when archived")
		void shouldArchiveEntityAndRemoveFromIndexes() {
			/* create schema for entity archival */
			createSchemaForEntityArchiving(Scope.LIVE);

			// upsert entities product depends on
			createBrandAndCategoryEntities();

			// create product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.PRODUCT, 100)
						.setAttribute(ATTRIBUTE_CODE, "TV-123")
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "TV")
						.setReference(Entities.BRAND, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_BRAND_EAN, "123"))
						.setReference(
							Entities.CATEGORY, 2,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "EU")
								.setAttribute(ATTRIBUTE_CATEGORY_OPEN, true)
						)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), true)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_EUR, new BigDecimal("10"), new BigDecimal("21"), new BigDecimal("12.1"), true)
						.upsertVia(session);
				}
			);

			// check product entity is in indexes
			checkProductCanBeLookedUpByIndexes();

			// check indexes exist
			final Catalog catalog1 = (Catalog) EvitaArchivingTest.this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
			final EntityCollectionContract productCollection1 = catalog1.getCollectionForEntity(Entities.PRODUCT)
				.orElseThrow();

			assertNotNull(catalog1.getCatalogIndexIfExits(Scope.LIVE).orElse(null));
			assertNull(getReferencedEntityIndex(productCollection1, Scope.LIVE, Entities.CATEGORY, 1));
			assertNotNull(getReferencedEntityIndex(productCollection1, Scope.LIVE, Entities.CATEGORY, 2));
			assertNotNull(getReferencedEntityIndex(productCollection1, Scope.LIVE, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection1, Scope.LIVE, Entities.BRAND, 2));

			assertNull(catalog1.getCatalogIndexIfExits(Scope.ARCHIVED).orElse(null));
			assertNull(getGlobalIndex(productCollection1, Scope.ARCHIVED));
			assertNull(getReferencedEntityIndex(productCollection1, Scope.ARCHIVED, Entities.CATEGORY, 1));
			assertNull(getReferencedEntityIndex(productCollection1, Scope.ARCHIVED, Entities.CATEGORY, 2));
			assertNull(getReferencedEntityIndex(productCollection1, Scope.ARCHIVED, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection1, Scope.ARCHIVED, Entities.BRAND, 2));

			// archive product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);

			// check product entity is not in any of indexes
			checkProductCannotBeLookedUpByIndexes(Scope.LIVE);
			checkProductCannotBeLookedUpByIndexes(Scope.ARCHIVED);

			// check archive indexes exist and previous indexes are removed
			final Catalog catalog2 = (Catalog) EvitaArchivingTest.this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
			final EntityCollectionContract productCollection2 = catalog2.getCollectionForEntity(Entities.PRODUCT)
				.orElseThrow();

			assertNotNull(catalog2.getCatalogIndexIfExits(Scope.LIVE).orElse(null));
			assertNull(getReferencedEntityIndex(productCollection2, Scope.LIVE, Entities.CATEGORY, 1));
			assertNull(getReferencedEntityIndex(productCollection2, Scope.LIVE, Entities.CATEGORY, 2));
			assertNull(getReferencedEntityIndex(productCollection2, Scope.LIVE, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection2, Scope.LIVE, Entities.BRAND, 2));

			assertNull(catalog2.getCatalogIndexIfExits(Scope.ARCHIVED).filter(it -> !it.isEmpty()).orElse(null));
			/* primary key is always indexed in all scopes, no matter what */
			assertNotNull(getGlobalIndex(productCollection2, Scope.ARCHIVED));
			assertNull(getReferencedEntityIndex(productCollection2, Scope.ARCHIVED, Entities.CATEGORY, 1));
			assertNull(getReferencedEntityIndex(productCollection2, Scope.ARCHIVED, Entities.CATEGORY, 2));
			assertNull(getReferencedEntityIndex(productCollection2, Scope.ARCHIVED, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection2, Scope.ARCHIVED, Entities.BRAND, 2));

			// restore product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.restoreEntity(Entities.PRODUCT, 100);
				}
			);

			// check product entity is in indexes
			checkProductCanBeLookedUpByIndexes();

			// check live indexes exist and previous indexes are removed
			final Catalog catalog3 = (Catalog) EvitaArchivingTest.this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
			final EntityCollectionContract productCollection3 = catalog3.getCollectionForEntity(Entities.PRODUCT)
				.orElseThrow();

			assertNotNull(catalog3.getCatalogIndexIfExits(Scope.LIVE).orElse(null));
			assertNull(getReferencedEntityIndex(productCollection3, Scope.LIVE, Entities.CATEGORY, 1));
			assertNotNull(getReferencedEntityIndex(productCollection3, Scope.LIVE, Entities.CATEGORY, 2));
			assertNotNull(getReferencedEntityIndex(productCollection3, Scope.LIVE, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection3, Scope.LIVE, Entities.BRAND, 2));

			assertNull(catalog3.getCatalogIndexIfExits(Scope.ARCHIVED).filter(it -> !it.isEmpty()).orElse(null));
			assertNull(getGlobalIndex(productCollection3, Scope.ARCHIVED));
			assertNull(getReferencedEntityIndex(productCollection3, Scope.ARCHIVED, Entities.CATEGORY, 1));
			assertNull(getReferencedEntityIndex(productCollection3, Scope.ARCHIVED, Entities.CATEGORY, 2));
			assertNull(getReferencedEntityIndex(productCollection3, Scope.ARCHIVED, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection3, Scope.ARCHIVED, Entities.BRAND, 2));

			// close evita and reload it from disk again
			EvitaArchivingTest.this.evita.close();
			EvitaArchivingTest.this.evita = new Evita(
				getEvitaConfiguration()
			);
			EvitaArchivingTest.this.evita.waitUntilFullyInitialized();

			// check product entity is in indexes
			checkProductCanBeLookedUpByIndexes();

			// check live indexes exist and previous indexes are removed
			final Catalog catalog4 = (Catalog) EvitaArchivingTest.this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
			final EntityCollectionContract productCollection4 = catalog4.getCollectionForEntity(Entities.PRODUCT)
				.orElseThrow();

			assertNotNull(catalog4.getCatalogIndexIfExits(Scope.LIVE).orElse(null));
			assertNull(getReferencedEntityIndex(productCollection4, Scope.LIVE, Entities.CATEGORY, 1));
			assertNotNull(getReferencedEntityIndex(productCollection4, Scope.LIVE, Entities.CATEGORY, 2));
			assertNotNull(getReferencedEntityIndex(productCollection4, Scope.LIVE, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection4, Scope.LIVE, Entities.BRAND, 2));

			assertNull(catalog4.getCatalogIndexIfExits(Scope.ARCHIVED).orElse(null));
			assertNull(getGlobalIndex(productCollection4, Scope.ARCHIVED));
			assertNull(getReferencedEntityIndex(productCollection4, Scope.ARCHIVED, Entities.CATEGORY, 1));
			assertNull(getReferencedEntityIndex(productCollection4, Scope.ARCHIVED, Entities.CATEGORY, 2));
			assertNull(getReferencedEntityIndex(productCollection4, Scope.ARCHIVED, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection4, Scope.ARCHIVED, Entities.BRAND, 2));
		}

		@Test
		@DisplayName("Entity should be moved to archive indexes when archived")
		void shouldArchiveEntityAndMoveToArchivedIndexes() {
			/* create schema for entity archival */
			createSchemaForEntityArchiving(Scope.LIVE, Scope.ARCHIVED);

			// upsert entities product depends on
			createBrandAndCategoryEntities();

			// create product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.PRODUCT, 100)
						.setAttribute(ATTRIBUTE_CODE, "TV-123")
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "TV")
						.setReference(Entities.BRAND, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_BRAND_EAN, "123"))
						.setReference(
							Entities.CATEGORY, 2,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "EU")
								.setAttribute(ATTRIBUTE_CATEGORY_OPEN, true)
						)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), true)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_EUR, new BigDecimal("10"), new BigDecimal("21"), new BigDecimal("12.1"), true)
						.upsertVia(session);
				}
			);

			// check product entity is in LIVE indexes
			checkProductCanBeLookedUpByIndexes();
			// check product entity is not in ARCHIVED indexes
			checkProductCannotBeLookedUpByIndexes(Scope.ARCHIVED);

			// check indexes exist
			final Catalog catalog1 = (Catalog) EvitaArchivingTest.this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
			final EntityCollectionContract productCollection1 = catalog1.getCollectionForEntity(Entities.PRODUCT)
				.orElseThrow();

			assertNotNull(catalog1.getCatalogIndexIfExits(Scope.LIVE).orElse(null));
			assertNull(getReferencedEntityIndex(productCollection1, Scope.LIVE, Entities.CATEGORY, 1));
			assertNotNull(getReferencedEntityIndex(productCollection1, Scope.LIVE, Entities.CATEGORY, 2));
			assertNotNull(getReferencedEntityIndex(productCollection1, Scope.LIVE, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection1, Scope.LIVE, Entities.BRAND, 2));

			assertNull(catalog1.getCatalogIndexIfExits(Scope.ARCHIVED).orElse(null));
			assertNull(getGlobalIndex(productCollection1, Scope.ARCHIVED));
			assertNull(getReferencedEntityIndex(productCollection1, Scope.ARCHIVED, Entities.CATEGORY, 1));
			assertNull(getReferencedEntityIndex(productCollection1, Scope.ARCHIVED, Entities.CATEGORY, 2));
			assertNull(getReferencedEntityIndex(productCollection1, Scope.ARCHIVED, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection1, Scope.ARCHIVED, Entities.BRAND, 2));

			// archive product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);

			// check product entity is in LIVE indexes
			checkProductCannotBeLookedUpByIndexes(Scope.LIVE);
			// check product entity is in ARCHIVED indexes
			checkProductCanBeLookedUpByIndexes(Scope.values());

			// check archive indexes exist and previous indexes are removed
			final Catalog catalog2 = (Catalog) EvitaArchivingTest.this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
			final EntityCollectionContract productCollection2 = catalog2.getCollectionForEntity(Entities.PRODUCT)
				.orElseThrow();

			assertNotNull(catalog2.getCatalogIndexIfExits(Scope.LIVE).orElse(null));
			assertNull(getReferencedEntityIndex(productCollection2, Scope.LIVE, Entities.CATEGORY, 1));
			assertNull(getReferencedEntityIndex(productCollection2, Scope.LIVE, Entities.CATEGORY, 2));
			assertNull(getReferencedEntityIndex(productCollection2, Scope.LIVE, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection2, Scope.LIVE, Entities.BRAND, 2));

			assertNotNull(catalog2.getCatalogIndexIfExits(Scope.ARCHIVED).orElse(null));
			assertNotNull(getGlobalIndex(productCollection2, Scope.ARCHIVED));
			assertNull(getReferencedEntityIndex(productCollection2, Scope.ARCHIVED, Entities.CATEGORY, 1));
			assertNotNull(getReferencedEntityIndex(productCollection2, Scope.ARCHIVED, Entities.CATEGORY, 2));
			assertNotNull(getReferencedEntityIndex(productCollection2, Scope.ARCHIVED, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection2, Scope.ARCHIVED, Entities.BRAND, 2));

			// restore product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.restoreEntity(Entities.PRODUCT, 100);
				}
			);

			// check product entity is in LIVE indexes
			checkProductCanBeLookedUpByIndexes();
			// check product entity is not in ARCHIVED indexes
			checkProductCannotBeLookedUpByIndexes(Scope.ARCHIVED);

			// check live indexes exist and previous indexes are removed
			final Catalog catalog3 = (Catalog) EvitaArchivingTest.this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
			final EntityCollectionContract productCollection3 = catalog3.getCollectionForEntity(Entities.PRODUCT)
				.orElseThrow();

			assertNotNull(catalog3.getCatalogIndexIfExits(Scope.LIVE).orElse(null));
			assertNull(getReferencedEntityIndex(productCollection3, Scope.LIVE, Entities.CATEGORY, 1));
			assertNotNull(getReferencedEntityIndex(productCollection3, Scope.LIVE, Entities.CATEGORY, 2));
			assertNotNull(getReferencedEntityIndex(productCollection3, Scope.LIVE, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection3, Scope.LIVE, Entities.BRAND, 2));

			assertNull(catalog3.getCatalogIndexIfExits(Scope.ARCHIVED).filter(it -> !it.isEmpty()).orElse(null));
			assertNull(getGlobalIndex(productCollection3, Scope.ARCHIVED));
			assertNull(getReferencedEntityIndex(productCollection3, Scope.ARCHIVED, Entities.CATEGORY, 1));
			assertNull(getReferencedEntityIndex(productCollection3, Scope.ARCHIVED, Entities.CATEGORY, 2));
			assertNull(getReferencedEntityIndex(productCollection3, Scope.ARCHIVED, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection3, Scope.ARCHIVED, Entities.BRAND, 2));

			// close evita and reload it from disk again
			EvitaArchivingTest.this.evita.close();
			EvitaArchivingTest.this.evita = new Evita(
				getEvitaConfiguration()
			);
			EvitaArchivingTest.this.evita.waitUntilFullyInitialized();

			// check product entity is in indexes
			checkProductCanBeLookedUpByIndexes();

			// check live indexes exist and previous indexes are removed
			final Catalog catalog4 = (Catalog) EvitaArchivingTest.this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
			final EntityCollectionContract productCollection4 = catalog4.getCollectionForEntity(Entities.PRODUCT)
				.orElseThrow();

			assertNotNull(catalog4.getCatalogIndexIfExits(Scope.LIVE).orElse(null));
			assertNull(getReferencedEntityIndex(productCollection4, Scope.LIVE, Entities.CATEGORY, 1));
			assertNotNull(getReferencedEntityIndex(productCollection4, Scope.LIVE, Entities.CATEGORY, 2));
			assertNotNull(getReferencedEntityIndex(productCollection4, Scope.LIVE, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection4, Scope.LIVE, Entities.BRAND, 2));

			assertNull(catalog4.getCatalogIndexIfExits(Scope.ARCHIVED).orElse(null));
			assertNull(getGlobalIndex(productCollection4, Scope.ARCHIVED));
			assertNull(getReferencedEntityIndex(productCollection4, Scope.ARCHIVED, Entities.CATEGORY, 1));
			assertNull(getReferencedEntityIndex(productCollection4, Scope.ARCHIVED, Entities.CATEGORY, 2));
			assertNull(getReferencedEntityIndex(productCollection4, Scope.ARCHIVED, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection4, Scope.ARCHIVED, Entities.BRAND, 2));

			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				EvitaSessionContract::goLiveAndClose
			);

			// check live indexes exist and previous indexes are removed
			final Catalog catalog5 = (Catalog) EvitaArchivingTest.this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
			final EntityCollectionContract productCollection5 = catalog5.getCollectionForEntity(Entities.PRODUCT)
				.orElseThrow();

			assertNotNull(catalog5.getCatalogIndexIfExits(Scope.LIVE).orElse(null));
			assertNull(getReferencedEntityIndex(productCollection5, Scope.LIVE, Entities.CATEGORY, 1));
			assertNotNull(getReferencedEntityIndex(productCollection5, Scope.LIVE, Entities.CATEGORY, 2));
			assertNotNull(getReferencedEntityIndex(productCollection5, Scope.LIVE, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection5, Scope.LIVE, Entities.BRAND, 2));

			assertNull(catalog5.getCatalogIndexIfExits(Scope.ARCHIVED).orElse(null));
			assertNull(getGlobalIndex(productCollection5, Scope.ARCHIVED));
			assertNull(getReferencedEntityIndex(productCollection5, Scope.ARCHIVED, Entities.CATEGORY, 1));
			assertNull(getReferencedEntityIndex(productCollection5, Scope.ARCHIVED, Entities.CATEGORY, 2));
			assertNull(getReferencedEntityIndex(productCollection5, Scope.ARCHIVED, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection5, Scope.ARCHIVED, Entities.BRAND, 2));
		}

		@Test
		@DisplayName("Entity should be moved to archive indexes when archived (in tx mode)")
		void shouldArchiveEntityAndMoveToArchivedIndexesInTransactionalMode() {
			/* create schema for entity archival */
			createSchemaForEntityArchiving(Scope.LIVE, Scope.ARCHIVED);

			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				EvitaSessionContract::goLiveAndClose
			);

			// upsert entities product depends on
			createBrandAndCategoryEntities();

			// create product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.PRODUCT, 100)
						.setAttribute(ATTRIBUTE_CODE, "TV-123")
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "TV")
						.setReference(Entities.BRAND, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_BRAND_EAN, "123"))
						.setReference(
							Entities.CATEGORY, 2,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "EU")
								.setAttribute(ATTRIBUTE_CATEGORY_OPEN, true)
						)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), true)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_EUR, new BigDecimal("10"), new BigDecimal("21"), new BigDecimal("12.1"), true)
						.upsertVia(session);
				}
			);

			// check product entity is in LIVE indexes
			checkProductCanBeLookedUpByIndexes();
			// check product entity is not in ARCHIVED indexes
			checkProductCannotBeLookedUpByIndexes(Scope.ARCHIVED);

			// check indexes exist
			final Catalog catalog1 = (Catalog) EvitaArchivingTest.this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
			final EntityCollectionContract productCollection1 = catalog1.getCollectionForEntity(Entities.PRODUCT)
				.orElseThrow();

			assertNotNull(catalog1.getCatalogIndexIfExits(Scope.LIVE).orElse(null));
			assertNull(getReferencedEntityIndex(productCollection1, Scope.LIVE, Entities.CATEGORY, 1));
			assertNotNull(getReferencedEntityIndex(productCollection1, Scope.LIVE, Entities.CATEGORY, 2));
			assertNotNull(getReferencedEntityIndex(productCollection1, Scope.LIVE, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection1, Scope.LIVE, Entities.BRAND, 2));

			assertNull(catalog1.getCatalogIndexIfExits(Scope.ARCHIVED).orElse(null));
			assertNull(getGlobalIndex(productCollection1, Scope.ARCHIVED));
			assertNull(getReferencedEntityIndex(productCollection1, Scope.ARCHIVED, Entities.CATEGORY, 1));
			assertNull(getReferencedEntityIndex(productCollection1, Scope.ARCHIVED, Entities.CATEGORY, 2));
			assertNull(getReferencedEntityIndex(productCollection1, Scope.ARCHIVED, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection1, Scope.ARCHIVED, Entities.BRAND, 2));

			// archive product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);

			// check product entity is in LIVE indexes
			checkProductCannotBeLookedUpByIndexes(Scope.LIVE);
			// check product entity is in ARCHIVED indexes
			checkProductCanBeLookedUpByIndexes(Scope.values());

			// check archive indexes exist and previous indexes are removed
			final Catalog catalog2 = (Catalog) EvitaArchivingTest.this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
			final EntityCollectionContract productCollection2 = catalog2.getCollectionForEntity(Entities.PRODUCT)
				.orElseThrow();

			assertNotNull(catalog2.getCatalogIndexIfExits(Scope.LIVE).orElse(null));
			assertNull(getReferencedEntityIndex(productCollection2, Scope.LIVE, Entities.CATEGORY, 1));
			assertNull(getReferencedEntityIndex(productCollection2, Scope.LIVE, Entities.CATEGORY, 2));
			assertNull(getReferencedEntityIndex(productCollection2, Scope.LIVE, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection2, Scope.LIVE, Entities.BRAND, 2));

			assertNotNull(catalog2.getCatalogIndexIfExits(Scope.ARCHIVED).orElse(null));
			assertNotNull(getGlobalIndex(productCollection2, Scope.ARCHIVED));
			assertNull(getReferencedEntityIndex(productCollection2, Scope.ARCHIVED, Entities.CATEGORY, 1));
			assertNotNull(getReferencedEntityIndex(productCollection2, Scope.ARCHIVED, Entities.CATEGORY, 2));
			assertNotNull(getReferencedEntityIndex(productCollection2, Scope.ARCHIVED, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection2, Scope.ARCHIVED, Entities.BRAND, 2));

			// restore product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.restoreEntity(Entities.PRODUCT, 100);
				}
			);

			// check product entity is in LIVE indexes
			checkProductCanBeLookedUpByIndexes();
			// check product entity is not in ARCHIVED indexes
			checkProductCannotBeLookedUpByIndexes(Scope.ARCHIVED);

			// check live indexes exist and previous indexes are removed
			final Catalog catalog3 = (Catalog) EvitaArchivingTest.this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
			final EntityCollectionContract productCollection3 = catalog3.getCollectionForEntity(Entities.PRODUCT)
				.orElseThrow();

			assertNotNull(catalog3.getCatalogIndexIfExits(Scope.LIVE).orElse(null));
			assertNull(getReferencedEntityIndex(productCollection3, Scope.LIVE, Entities.CATEGORY, 1));
			assertNotNull(getReferencedEntityIndex(productCollection3, Scope.LIVE, Entities.CATEGORY, 2));
			assertNotNull(getReferencedEntityIndex(productCollection3, Scope.LIVE, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection3, Scope.LIVE, Entities.BRAND, 2));

			assertNull(catalog3.getCatalogIndexIfExits(Scope.ARCHIVED).filter(it -> !it.isEmpty()).orElse(null));
			assertNull(getGlobalIndex(productCollection3, Scope.ARCHIVED));
			assertNull(getReferencedEntityIndex(productCollection3, Scope.ARCHIVED, Entities.CATEGORY, 1));
			assertNull(getReferencedEntityIndex(productCollection3, Scope.ARCHIVED, Entities.CATEGORY, 2));
			assertNull(getReferencedEntityIndex(productCollection3, Scope.ARCHIVED, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection3, Scope.ARCHIVED, Entities.BRAND, 2));

			// close evita and reload it from disk again
			EvitaArchivingTest.this.evita.close();
			EvitaArchivingTest.this.evita = new Evita(
				getEvitaConfiguration()
			);
			EvitaArchivingTest.this.evita.waitUntilFullyInitialized();

			// check product entity is in indexes
			checkProductCanBeLookedUpByIndexes();

			// check live indexes exist and previous indexes are removed
			final Catalog catalog4 = (Catalog) EvitaArchivingTest.this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
			final EntityCollectionContract productCollection4 = catalog4.getCollectionForEntity(Entities.PRODUCT)
				.orElseThrow();

			assertNotNull(catalog4.getCatalogIndexIfExits(Scope.LIVE).orElse(null));
			assertNull(getReferencedEntityIndex(productCollection4, Scope.LIVE, Entities.CATEGORY, 1));
			assertNotNull(getReferencedEntityIndex(productCollection4, Scope.LIVE, Entities.CATEGORY, 2));
			assertNotNull(getReferencedEntityIndex(productCollection4, Scope.LIVE, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection4, Scope.LIVE, Entities.BRAND, 2));

			assertNull(catalog4.getCatalogIndexIfExits(Scope.ARCHIVED).orElse(null));
			assertNull(getGlobalIndex(productCollection4, Scope.ARCHIVED));
			assertNull(getReferencedEntityIndex(productCollection4, Scope.ARCHIVED, Entities.CATEGORY, 1));
			assertNull(getReferencedEntityIndex(productCollection4, Scope.ARCHIVED, Entities.CATEGORY, 2));
			assertNull(getReferencedEntityIndex(productCollection4, Scope.ARCHIVED, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection4, Scope.ARCHIVED, Entities.BRAND, 2));
		}

	}

	@Nested
	@DisplayName("Group entity index archival and restoration")
	class GroupEntityIndexArchivalTest {

		@Test
		@DisplayName("Group entity indexes should be cleaned up on archive and rebuilt on restore")
		void shouldCleanUpGroupEntityIndexesOnArchiveAndRebuildOnRestore() {
			// create schema with group entity indexing enabled on the CATEGORY reference
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.BRAND)
						.withoutGeneratedPrimaryKey()
						.updateVia(session);

					session.defineEntitySchema(Entities.CATEGORY)
						.withoutGeneratedPrimaryKey()
						.updateVia(session);

					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::filterable)
						.withReferenceToEntity(
							Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
							thatIs -> thatIs
								.indexedWithComponents(
									ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
								.withGroupTypeRelatedToEntity(Entities.BRAND)
						)
						.updateVia(session);

					// create referenced entities
					session.upsertEntity(session.createNewEntity(Entities.BRAND, 10));
					session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 20));

					// create product with a reference to CATEGORY grouped by BRAND
					session.createNewEntity(Entities.PRODUCT, 100)
						.setAttribute(ATTRIBUTE_CODE, "TV-123")
						.setReference(
							Entities.CATEGORY, 20,
							whichIs -> whichIs.setGroup(Entities.BRAND, 10)
						)
						.upsertVia(session);
				}
			);

			// verify group entity index exists in LIVE scope
			final Catalog catalog1 = (Catalog) EvitaArchivingTest.this.evita
				.getCatalogInstance(TEST_CATALOG).orElseThrow();
			final EntityCollectionContract productCollection1 = catalog1
				.getCollectionForEntity(Entities.PRODUCT).orElseThrow();

			assertNotNull(
				getReferencedGroupEntityIndex(
					productCollection1, Scope.LIVE, Entities.CATEGORY, 10
				),
				"Group entity index should exist in LIVE before archiving"
			);
			assertNotNull(
				getReferencedGroupEntityTypeIndex(
					productCollection1, Scope.LIVE, Entities.CATEGORY
				),
				"Group entity type index should exist in LIVE before archiving"
			);

			// archive the product
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);

			// verify group entity index is removed from LIVE scope
			final Catalog catalog2 = (Catalog) EvitaArchivingTest.this.evita
				.getCatalogInstance(TEST_CATALOG).orElseThrow();
			final EntityCollectionContract productCollection2 = catalog2
				.getCollectionForEntity(Entities.PRODUCT).orElseThrow();

			assertNull(
				getReferencedGroupEntityIndex(
					productCollection2, Scope.LIVE, Entities.CATEGORY, 10
				),
				"Group entity index should be removed from LIVE after archiving"
			);
			assertNull(
				getReferencedEntityIndex(
					productCollection2, Scope.LIVE, Entities.CATEGORY, 20
				),
				"Referenced entity index should be removed from LIVE after archiving"
			);

			// restore the product
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.restoreEntity(Entities.PRODUCT, 100);
				}
			);

			// verify group entity index is rebuilt in LIVE scope
			final Catalog catalog3 = (Catalog) EvitaArchivingTest.this.evita
				.getCatalogInstance(TEST_CATALOG).orElseThrow();
			final EntityCollectionContract productCollection3 = catalog3
				.getCollectionForEntity(Entities.PRODUCT).orElseThrow();

			assertNotNull(
				getReferencedGroupEntityIndex(
					productCollection3, Scope.LIVE, Entities.CATEGORY, 10
				),
				"Group entity index should be rebuilt in LIVE after restore"
			);
			assertNotNull(
				getReferencedGroupEntityTypeIndex(
					productCollection3, Scope.LIVE, Entities.CATEGORY
				),
				"Group entity type index should be rebuilt in LIVE after restore"
			);
			assertNotNull(
				getReferencedEntityIndex(
					productCollection3, Scope.LIVE, Entities.CATEGORY, 20
				),
				"Referenced entity index should be rebuilt in LIVE after restore"
			);
		}

		@Test
		@DisplayName("Group entity index should survive archive/restore cycle with multiple products")
		void shouldRetainGroupEntityIndexForRemainingProductsAfterArchivingOne() {
			// create schema with group entity indexing enabled
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.BRAND)
						.withoutGeneratedPrimaryKey()
						.updateVia(session);

					session.defineEntitySchema(Entities.CATEGORY)
						.withoutGeneratedPrimaryKey()
						.updateVia(session);

					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::filterable)
						.withReferenceToEntity(
							Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
							thatIs -> thatIs
								.indexedWithComponents(
									ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
								.withGroupTypeRelatedToEntity(Entities.BRAND)
						)
						.updateVia(session);

					// create referenced entities
					session.upsertEntity(session.createNewEntity(Entities.BRAND, 10));
					session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 20));

					// create two products referencing the same category with the same group
					session.createNewEntity(Entities.PRODUCT, 100)
						.setAttribute(ATTRIBUTE_CODE, "TV-100")
						.setReference(
							Entities.CATEGORY, 20,
							whichIs -> whichIs.setGroup(Entities.BRAND, 10)
						)
						.upsertVia(session);

					session.createNewEntity(Entities.PRODUCT, 200)
						.setAttribute(ATTRIBUTE_CODE, "TV-200")
						.setReference(
							Entities.CATEGORY, 20,
							whichIs -> whichIs.setGroup(Entities.BRAND, 10)
						)
						.upsertVia(session);
				}
			);

			// verify both products are in group entity index
			final Catalog catalog1 = (Catalog) EvitaArchivingTest.this.evita
				.getCatalogInstance(TEST_CATALOG).orElseThrow();
			final EntityCollectionContract productColl1 = catalog1
				.getCollectionForEntity(Entities.PRODUCT).orElseThrow();

			assertNotNull(
				getReferencedGroupEntityIndex(
					productColl1, Scope.LIVE, Entities.CATEGORY, 10
				),
				"Group entity index should exist for both products"
			);
			assertTrue(
				getReferencedGroupEntityIndex(
					productColl1, Scope.LIVE, Entities.CATEGORY, 10
				).getAllPrimaryKeys().contains(100),
				"Group entity index should contain product 100"
			);
			assertTrue(
				getReferencedGroupEntityIndex(
					productColl1, Scope.LIVE, Entities.CATEGORY, 10
				).getAllPrimaryKeys().contains(200),
				"Group entity index should contain product 200"
			);

			// archive only product 100
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);

			// group entity index should still exist (product 200 keeps it alive)
			final Catalog catalog2 = (Catalog) EvitaArchivingTest.this.evita
				.getCatalogInstance(TEST_CATALOG).orElseThrow();
			final EntityCollectionContract productColl2 = catalog2
				.getCollectionForEntity(Entities.PRODUCT).orElseThrow();

			assertNotNull(
				getReferencedGroupEntityIndex(
					productColl2, Scope.LIVE, Entities.CATEGORY, 10
				),
				"Group entity index should still exist after archiving one product"
			);
			assertFalse(
				getReferencedGroupEntityIndex(
					productColl2, Scope.LIVE, Entities.CATEGORY, 10
				).getAllPrimaryKeys().contains(100),
				"Product 100 should be removed from group entity index"
			);
			assertTrue(
				getReferencedGroupEntityIndex(
					productColl2, Scope.LIVE, Entities.CATEGORY, 10
				).getAllPrimaryKeys().contains(200),
				"Product 200 should still be in group entity index"
			);

			// restore product 100
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.restoreEntity(Entities.PRODUCT, 100);
				}
			);

			// both products should be in group entity index again
			final Catalog catalog3 = (Catalog) EvitaArchivingTest.this.evita
				.getCatalogInstance(TEST_CATALOG).orElseThrow();
			final EntityCollectionContract productColl3 = catalog3
				.getCollectionForEntity(Entities.PRODUCT).orElseThrow();

			assertTrue(
				getReferencedGroupEntityIndex(
					productColl3, Scope.LIVE, Entities.CATEGORY, 10
				).getAllPrimaryKeys().contains(100),
				"Product 100 should be back in group entity index after restore"
			);
			assertTrue(
				getReferencedGroupEntityIndex(
					productColl3, Scope.LIVE, Entities.CATEGORY, 10
				).getAllPrimaryKeys().contains(200),
				"Product 200 should still be in group entity index"
			);
		}
	}

	@Nested
	@DisplayName("Archived entity creation")
	class ArchivedEntityCreationTest {

		@Test
		@DisplayName("Entity could be created in already archived state")
		void shouldCreateArchivedEntity() {
			/* create schema for entity archival */
			createSchemaForEntityArchiving(Scope.LIVE, Scope.ARCHIVED);

			// upsert entities product depends on
			createBrandAndCategoryEntities();

			// create product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.PRODUCT, 100)
						.setScope(Scope.ARCHIVED)
						.setAttribute(ATTRIBUTE_CODE, "TV-123")
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "TV")
						.setReference(Entities.BRAND, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_BRAND_EAN, "123"))
						.setReference(
							Entities.CATEGORY, 2,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "EU")
								.setAttribute(ATTRIBUTE_CATEGORY_OPEN, true)
						)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), true)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_EUR, new BigDecimal("10"), new BigDecimal("21"), new BigDecimal("12.1"), true)
						.upsertVia(session);

					session.archiveEntity(Entities.BRAND, 1);
					session.archiveEntity(Entities.CATEGORY, 2);
				}
			);

			// check product entity is not in any of indexes
			checkProductCannotBeLookedUpByIndexes(Scope.LIVE);
			checkProductCanBeLookedUpByIndexes(Scope.ARCHIVED);
		}

		@DisplayName("Entity could be created in already archived state without indexes in archived scope")
		@Test
		void shouldCreateArchivedEntityWithNoDataIndexedInArchiveScope() {
			/* create schema for entity archival */
			createSchemaForEntityArchiving(Scope.LIVE);

			// upsert entities product depends on
			createBrandAndCategoryEntities();

			// create product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.PRODUCT, 100)
						.setScope(Scope.ARCHIVED)
						.setAttribute(ATTRIBUTE_CODE, "TV-123")
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "TV")
						.setReference(Entities.BRAND, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_BRAND_EAN, "123"))
						.setReference(
							Entities.CATEGORY, 2,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "EU")
								.setAttribute(ATTRIBUTE_CATEGORY_OPEN, true)
						)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), true)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_EUR, new BigDecimal("10"), new BigDecimal("21"), new BigDecimal("12.1"), true)
						.upsertVia(session);

					session.archiveEntity(Entities.BRAND, 1);
					session.archiveEntity(Entities.CATEGORY, 2);
				}
			);

			// check product entity is not in any of indexes
			checkProductCannotBeLookedUpByIndexes(Scope.LIVE);

			// check live indexes exist and previous indexes are removed
			final Catalog catalog = (Catalog) EvitaArchivingTest.this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
			final EntityCollectionContract productCollection = catalog.getCollectionForEntity(Entities.PRODUCT)
				.orElseThrow();

			assertNotNull(catalog.getCatalogIndexIfExits(Scope.LIVE).orElse(null));
			assertNull(getReferencedEntityIndex(productCollection, Scope.LIVE, Entities.CATEGORY, 1));
			assertNull(getReferencedEntityIndex(productCollection, Scope.LIVE, Entities.CATEGORY, 2));
			assertNull(getReferencedEntityIndex(productCollection, Scope.LIVE, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection, Scope.LIVE, Entities.BRAND, 2));

			assertNull(catalog.getCatalogIndexIfExits(Scope.ARCHIVED).orElse(null));
			assertNotNull(getGlobalIndex(productCollection, Scope.ARCHIVED));
			assertNull(getReferencedEntityIndex(productCollection, Scope.ARCHIVED, Entities.CATEGORY, 1));
			assertNull(getReferencedEntityIndex(productCollection, Scope.ARCHIVED, Entities.CATEGORY, 2));
			assertNull(getReferencedEntityIndex(productCollection, Scope.ARCHIVED, Entities.BRAND, 1));
			assertNull(getReferencedEntityIndex(productCollection, Scope.ARCHIVED, Entities.BRAND, 2));
		}

	}

	@Nested
	@DisplayName("Multi-scope querying")
	class MultiScopeQueryingTest {

		@Test
		@DisplayName("Results respect the filter even when both scopes are combined")
		void shouldReturnOnlyLimitedSetOfReferencedEntities() {
			/* create schema for entity archival */
			createSchemaForEntityArchiving(Scope.LIVE);

			// upsert entities product depends on
			createBrandAndCategoryEntities();

			// create product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.PRODUCT, 100)
						.setAttribute(ATTRIBUTE_CODE, "TV-123")
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "TV")
						.setReference(Entities.BRAND, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_BRAND_EAN, "123"))
						.setReference(Entities.BRAND, 2, whichIs -> whichIs.setAttribute(ATTRIBUTE_BRAND_EAN, "456"))
						.setReference(
							Entities.CATEGORY, 1,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "EU")
								.setAttribute(ATTRIBUTE_CATEGORY_OPEN, true)
						)
						.setReference(
							Entities.CATEGORY, 2,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "US")
								.setAttribute(ATTRIBUTE_CATEGORY_OPEN, true)
						)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), true)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_EUR, new BigDecimal("10"), new BigDecimal("21"), new BigDecimal("12.1"), true)
						.upsertVia(session);
				}
			);

			final Query complexQuery = query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(100),
					inScope(
						Scope.LIVE,
						entityLocaleEquals(Locale.ENGLISH),
						attributeInSet(ATTRIBUTE_NAME, "TV", "Radio"),
						attributeInSet(ATTRIBUTE_CODE, "TV-123", "TV-456"),
						referenceHaving(
							Entities.BRAND,
							attributeInSet(ATTRIBUTE_BRAND_EAN, "123", "456")
						),
						referenceHaving(
							Entities.CATEGORY,
							attributeInSet(ATTRIBUTE_CATEGORY_MARKET, "EU", "US")
						),
						priceInPriceLists(PRICE_LIST_BASIC),
						priceInCurrency(CURRENCY_CZK)
					),
					scope(Scope.LIVE, Scope.ARCHIVED)
				),
				require(
					entityFetch(
						attributeContent(ATTRIBUTE_CODE, ATTRIBUTE_NAME),
						referenceContentWithAttributes(
							Entities.BRAND,
							filterBy(inScope(Scope.LIVE, entityPrimaryKeyInSet(2))),
							entityFetchAll()
						),
						referenceContentWithAttributes(
							Entities.CATEGORY,
							filterBy(inScope(Scope.LIVE, entityHaving(attributeInSet(ATTRIBUTE_CODE, "electronics")))),
							entityFetchAll()
						),
						priceContentRespectingFilter()
					)
				)
			);

			// find products with complex query - there are no archived data at the moment
			final List<SealedEntity> liveProducts = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.queryList(complexQuery, SealedEntity.class);
				}
			);
			assertArrayEquals(
				new int[]{100},
				liveProducts.stream()
					.mapToInt(SealedEntity::getPrimaryKeyOrThrowException)
					.toArray()
			);

			for (final SealedEntity liveProduct : liveProducts) {
				assertEquals(1, liveProduct.getReferences(Entities.BRAND).size());
				assertEquals(1, liveProduct.getReferences(Entities.CATEGORY).size());
				for (final ReferenceContract reference : liveProduct.getReferences()) {
					assertNotNull(reference.getReferencedEntity());
				}
			}
		}

		@Test
		@DisplayName("Results should be merged from both scopes when querying and fetching contents")
		void shouldCombineArchivedAndNonArchiveDataInQueryAndFetch() {
			/* create schema for entity archival */
			createSchemaForEntityArchiving(Scope.LIVE);

			// upsert entities product depends on
			createBrandAndCategoryEntities();

			// create product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.PRODUCT, 100)
						.setAttribute(ATTRIBUTE_CODE, "TV-123")
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "TV")
						.setReference(Entities.BRAND, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_BRAND_EAN, "123"))
						.setReference(
							Entities.CATEGORY, 2,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "EU")
								.setAttribute(ATTRIBUTE_CATEGORY_OPEN, true)
						)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), true)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_EUR, new BigDecimal("10"), new BigDecimal("21"), new BigDecimal("12.1"), true)
						.upsertVia(session);

					session.createNewEntity(Entities.PRODUCT, 101)
						.setAttribute(ATTRIBUTE_CODE, "TV-456")
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Radio")
						.setReference(Entities.BRAND, 2, whichIs -> whichIs.setAttribute(ATTRIBUTE_BRAND_EAN, "456"))
						.setReference(
							Entities.CATEGORY, 1,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "US")
								.setAttribute(ATTRIBUTE_CATEGORY_OPEN, true)
						)
						.setPrice(2, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), true)
						.setPrice(2, PRICE_LIST_BASIC, CURRENCY_EUR, new BigDecimal("10"), new BigDecimal("21"), new BigDecimal("12.1"), true)
						.upsertVia(session);
				}
			);

			final Query complexQuery = query(
				collection(Entities.PRODUCT),
				filterBy(
					entityPrimaryKeyInSet(100, 101),
					inScope(
						Scope.LIVE,
						entityLocaleEquals(Locale.ENGLISH),
						attributeInSet(ATTRIBUTE_NAME, "TV", "Radio"),
						attributeInSet(ATTRIBUTE_CODE, "TV-123", "TV-456"),
						referenceHaving(
							Entities.BRAND,
							attributeInSet(ATTRIBUTE_BRAND_EAN, "123", "456")
						),
						referenceHaving(
							Entities.CATEGORY,
							attributeInSet(ATTRIBUTE_CATEGORY_MARKET, "EU", "US")
						),
						priceInPriceLists(PRICE_LIST_BASIC),
						priceInCurrency(CURRENCY_CZK)
					),
					scope(Scope.LIVE, Scope.ARCHIVED)
				),
				require(
					entityFetch(
						attributeContent(ATTRIBUTE_CODE, ATTRIBUTE_NAME),
						referenceContentWithAttributes(
							Entities.BRAND,
							filterBy(inScope(Scope.LIVE, entityPrimaryKeyInSet(1, 2))),
							entityFetchAll()
						),
						referenceContentWithAttributes(
							Entities.CATEGORY,
							filterBy(inScope(Scope.LIVE, entityHaving(attributeInSet(ATTRIBUTE_CODE, "electronics", "TV")))),
							entityFetchAll()
						),
						priceContentRespectingFilter()
					)
				)
			);

			// find products with complex query - there are no archived data at the moment
			final List<SealedEntity> liveProducts = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.queryList(complexQuery, SealedEntity.class);
				}
			);
			assertArrayEquals(
				new int[]{100, 101},
				liveProducts.stream()
					.mapToInt(SealedEntity::getPrimaryKeyOrThrowException)
					.toArray()
			);

			for (final SealedEntity liveProduct : liveProducts) {
				assertEquals(1, liveProduct.getReferences(Entities.BRAND).size());
				assertEquals(1, liveProduct.getReferences(Entities.CATEGORY).size());
				for (final ReferenceContract reference : liveProduct.getReferences()) {
					assertNotNull(reference.getReferencedEntity());
				}
			}

			// archive product entity and all brands
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
					session.archiveEntity(Entities.BRAND, 1);
					session.archiveEntity(Entities.BRAND, 2);
				}
			);

			// find products with complex query - both live and archived (combination)
			final List<SealedEntity> liveAndArchiveProductsTogether = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.queryList(complexQuery, SealedEntity.class);
				}
			);
			assertArrayEquals(
				new int[]{100, 101},
				liveAndArchiveProductsTogether.stream()
					.mapToInt(SealedEntity::getPrimaryKeyOrThrowException)
					.toArray()
			);

			for (final SealedEntity product : liveAndArchiveProductsTogether) {
				assertEquals(1, product.getReferences(Entities.BRAND).size());
				assertEquals(1, product.getReferences(Entities.CATEGORY).size());
				for (final ReferenceContract reference : product.getReferences()) {
					assertNotNull(reference.getReferencedEntity());
				}
			}

			assertThrows(
				ReferenceNotIndexedException.class,
				() -> EvitaArchivingTest.this.evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						return session.queryList(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									entityPrimaryKeyInSet(100, 101),
									entityLocaleEquals(Locale.ENGLISH),
									attributeInSet(ATTRIBUTE_NAME, "TV", "Radio"),
									attributeInSet(ATTRIBUTE_CODE, "TV-123", "TV-456"),
									referenceHaving(
										Entities.BRAND,
										attributeInSet(ATTRIBUTE_BRAND_EAN, "123", "456")
									),
									referenceHaving(
										Entities.CATEGORY,
										attributeInSet(ATTRIBUTE_CATEGORY_MARKET, "EU", "US")
									),
									priceInPriceLists(PRICE_LIST_BASIC),
									priceInCurrency(CURRENCY_CZK),
									scope(Scope.LIVE, Scope.ARCHIVED)
								),
								require(
									entityFetchAllContent()
								)
							),
							SealedEntity.class
						);
					}
				)
			);

			assertThrows(
				ReferenceNotIndexedException.class,
				() -> EvitaArchivingTest.this.evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						return session.queryList(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									entityPrimaryKeyInSet(100, 101),
									entityLocaleEquals(Locale.ENGLISH),
									scope(Scope.LIVE, Scope.ARCHIVED)
								),
								require(
									entityFetch(
										attributeContent(ATTRIBUTE_CODE, ATTRIBUTE_NAME),
										referenceContentWithAttributes(
											Entities.BRAND,
											filterBy(attributeInSet(ATTRIBUTE_BRAND_EAN, "123", "456"))
										),
										referenceContentWithAttributes(
											Entities.CATEGORY,
											filterBy(attributeInSet(ATTRIBUTE_CATEGORY_MARKET, "EU", "US"))
										),
										priceContentRespectingFilter()
									)
								)
							),
							SealedEntity.class
						);
					}
				)
			);
		}

		@Test
		@DisplayName("Entity querying should respect scope requirement")
		void shouldListEntitiesInParticularScope() {
			/* create schema for entity archival */
			createSchemaForEntityArchiving(Scope.LIVE, Scope.ARCHIVED);

			// upsert entities product depends on
			createBrandAndCategoryEntities();

			// create product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.PRODUCT, 100)
						.setAttribute(ATTRIBUTE_CODE, "TV-123")
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "TV")
						.setReference(Entities.BRAND, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_BRAND_EAN, "123"))
						.setReference(
							Entities.CATEGORY, 2,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "EU")
								.setAttribute(ATTRIBUTE_CATEGORY_OPEN, true)
						)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), true)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_EUR, new BigDecimal("10"), new BigDecimal("21"), new BigDecimal("12.1"), true)
						.upsertVia(session);

					session.createNewEntity(Entities.PRODUCT, 101)
						.setAttribute(ATTRIBUTE_CODE, "TV-578")
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "LG TV")
						.setReference(Entities.BRAND, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_BRAND_EAN, "457"))
						.setReference(
							Entities.CATEGORY, 2,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "US")
								.setAttribute(ATTRIBUTE_CATEGORY_OPEN, true)
						)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("110"), new BigDecimal("21"), new BigDecimal("133.1"), true)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_EUR, new BigDecimal("20"), new BigDecimal("21"), new BigDecimal("24.2"), true)
						.upsertVia(session);
				}
			);

			// archive product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);

			EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// check only product 101 is retrievable in live scope
					assertNull(session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.LIVE}, entityFetchAllContent()).orElse(null));
					assertNotNull(session.getEntity(Entities.PRODUCT, 101, new Scope[]{Scope.LIVE}, entityFetchAllContent()).orElse(null));
					assertNull(queryOne(session, 100, Scope.LIVE));
					assertNotNull(queryOne(session, 101, Scope.LIVE));
					assertNull(queryOne(session, "TV-123", Scope.LIVE));
					assertNotNull(queryOne(session, "TV-578", Scope.LIVE));
					assertEquals(List.of(101), queryList(session, Scope.LIVE));
					assertEquals(List.of(101), queryPage(session, Scope.LIVE));

					// check only product 100 is retrievable in archive scope
					assertNotNull(session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null));
					assertNull(session.getEntity(Entities.PRODUCT, 101, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null));
					assertNotNull(queryOne(session, 100, Scope.ARCHIVED));
					assertNull(queryOne(session, 101, Scope.ARCHIVED));
					assertNotNull(queryOne(session, "TV-123", Scope.ARCHIVED));
					assertNull(queryOne(session, "TV-578", Scope.ARCHIVED));
					assertEquals(List.of(100), queryList(session, Scope.ARCHIVED));
					assertEquals(List.of(100), queryPage(session, Scope.ARCHIVED));

					// check both products are retrievable in all scopes
					assertNotNull(session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.LIVE, Scope.ARCHIVED}, entityFetchAllContent()).orElse(null));
					assertNotNull(session.getEntity(Entities.PRODUCT, 101, new Scope[]{Scope.LIVE, Scope.ARCHIVED}, entityFetchAllContent()).orElse(null));
					assertNotNull(queryOne(session, 100, Scope.LIVE, Scope.ARCHIVED));
					assertNotNull(queryOne(session, 101, Scope.LIVE, Scope.ARCHIVED));
					assertNotNull(queryOne(session, "TV-123", Scope.LIVE, Scope.ARCHIVED));
					assertNotNull(queryOne(session, "TV-578", Scope.LIVE, Scope.ARCHIVED));
					assertEquals(List.of(100, 101), queryList(session, Scope.LIVE, Scope.ARCHIVED));
					assertEquals(List.of(100, 101), queryPage(session, Scope.LIVE, Scope.ARCHIVED));
				}
			);
		}

	}

	@Nested
	@DisplayName("Reflected references across scopes")
	class ReflectedReferencesAcrossScopesTest {

		@Test
		@DisplayName("Entity reflected references should be removed when entity is archived")
		void shouldDropReflectedReferencesOnEntityArchivingAndCreateWhenBeingRestored() {
			/* create schema for entity archival */
			EvitaArchivingTest.this.evita.defineCatalog(TEST_CATALOG)
				.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.uniqueGlobally().sortable())
				.updateViaNewSession(EvitaArchivingTest.this.evita);

			EvitaArchivingTest.this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.withReflectedReferenceToEntity(
						REFLECTED_REFERENCE_NAME,
						Entities.PRODUCT,
						Entities.CATEGORY,
						whichIs -> whichIs.indexedForFilteringAndPartitioning().withAttributesInherited()
					)
					.withHierarchy()
					.updateVia(session);

				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> thatIs.localized().filterable().sortable())
					.withSortableAttributeCompound(
						ATTRIBUTE_CODE_NAME,
						new AttributeElement(ATTRIBUTE_CODE, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
						new AttributeElement(ATTRIBUTE_NAME, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
					)
					.withPriceInCurrency(CURRENCY_CZK, CURRENCY_EUR)
					.withReferenceToEntity(
						Entities.CATEGORY,
						Entities.CATEGORY,
						Cardinality.ZERO_OR_MORE,
						thatIs -> thatIs
							.indexedForFilteringAndPartitioning()
							.withAttribute(ATTRIBUTE_CATEGORY_MARKET, String.class, whichIs -> whichIs.filterable().sortable())
							.withAttribute(ATTRIBUTE_CATEGORY_OPEN, Boolean.class, AttributeSchemaEditor::filterable)
							.withSortableAttributeCompound(
								ATTRIBUTE_CATEGORY_MARKET_OPEN,
								new AttributeElement(ATTRIBUTE_CATEGORY_MARKET, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
								new AttributeElement(ATTRIBUTE_CATEGORY_OPEN, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
							)
					)
						.updateVia(session);
				}
			);

			// upsert entities product depends on
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.CATEGORY, 1)
						.setAttribute(ATTRIBUTE_CODE, "electronics")
						.upsertVia(session);

					session.createNewEntity(Entities.CATEGORY, 2)
						.setParent(1)
						.setAttribute(ATTRIBUTE_CODE, "TV")
						.upsertVia(session);
				}
			);

			// create product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.PRODUCT, 100)
						.setAttribute(ATTRIBUTE_CODE, "TV-123")
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "TV")
						.setReference(
							Entities.CATEGORY, 2,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "EU")
								.setAttribute(ATTRIBUTE_CATEGORY_OPEN, true)
						)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), true)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_EUR, new BigDecimal("10"), new BigDecimal("21"), new BigDecimal("12.1"), true)
						.upsertVia(session);
				}
			);

			// check category has reflected reference to product
			final SealedEntity category = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent())
						.orElse(null);
				}
			);
			assertNotNull(category);
			final ReferenceContract products = category.getReference(REFLECTED_REFERENCE_NAME, 100).orElse(null);
			assertNotNull(products);
			assertEquals("EU", products.getAttribute(ATTRIBUTE_CATEGORY_MARKET));

			// archive product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);

			// check category has no reflected reference to product
			final SealedEntity categoryAfterArchiving = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent())
						.orElse(null);
				}
			);
			assertNotNull(categoryAfterArchiving);
			final ReferenceContract productsAfterArchiving =
				categoryAfterArchiving.getReference(REFLECTED_REFERENCE_NAME, 100).orElse(null);
			assertNull(productsAfterArchiving);

			// restore both category and product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.restoreEntity(Entities.CATEGORY, 2);
					session.restoreEntity(Entities.PRODUCT, 100);
				}
			);

			// check restored category has reflected reference to product again
			final SealedEntity categoryAfterRestore = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent())
						.orElse(null);
				}
			);
			assertNotNull(categoryAfterRestore);
			final ReferenceContract productsAfterRestore =
				categoryAfterRestore.getReference(REFLECTED_REFERENCE_NAME, 100).orElse(null);
			assertNotNull(productsAfterRestore);
			assertEquals("EU", productsAfterRestore.getAttribute(ATTRIBUTE_CATEGORY_MARKET));
		}

		@Test
		@DisplayName("Should fail to set up reflected references incompatibly with main reference")
		void shouldFailToSetUpReflectedReferencesIncompatiblyWithMainReference() {
			/* create schema for entity archival */
			EvitaArchivingTest.this.evita.defineCatalog(TEST_CATALOG)
				.updateViaNewSession(EvitaArchivingTest.this.evita);

			assertThrows(
				InvalidSchemaMutationException.class,
				() ->
					EvitaArchivingTest.this.evita.updateCatalog(
						TEST_CATALOG,
						session -> {
							session.defineEntitySchema(Entities.CATEGORY)
								.withoutGeneratedPrimaryKey()
								.withReflectedReferenceToEntity(
									REFLECTED_REFERENCE_NAME,
									Entities.PRODUCT,
									Entities.CATEGORY,
									whichIs -> whichIs.indexedInScope(Scope.ARCHIVED).withAttributesInherited()
								)
								.updateVia(session);

							session.defineEntitySchema(Entities.PRODUCT)
								.withoutGeneratedPrimaryKey()
								.withReferenceToEntity(
									Entities.CATEGORY,
									Entities.CATEGORY,
									Cardinality.ZERO_OR_MORE,
									thatIs -> thatIs
										.indexedInScope(Scope.LIVE)
										.withAttribute(ATTRIBUTE_CATEGORY_MARKET, String.class, whichIs -> whichIs.filterable().sortable())
										.withAttribute(ATTRIBUTE_CATEGORY_OPEN, Boolean.class,
										               AttributeSchemaEditor::filterable
										)
								)
								.updateVia(session);
						}
					)
			);
		}

		@Test
		@DisplayName("Should fail to set up entity with reference attributes in incompatible scopes")
		void shouldFailToSetUpEntityWithReferenceAttributesInIncompatibleScopes() {
			/* create schema for entity archival */
			EvitaArchivingTest.this.evita.defineCatalog(TEST_CATALOG)
				.updateViaNewSession(EvitaArchivingTest.this.evita);

			assertThrows(
				InvalidSchemaMutationException.class,
				() -> EvitaArchivingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.defineEntitySchema(Entities.PRODUCT)
							.withoutGeneratedPrimaryKey()
							.withReferenceToEntity(
								Entities.CATEGORY,
								Entities.CATEGORY,
								Cardinality.ZERO_OR_MORE,
								thatIs -> thatIs
									.indexedInScope(Scope.LIVE)
									.withAttribute(
										ATTRIBUTE_CATEGORY_MARKET, String.class,
										whichIs -> whichIs.filterableInScope(Scope.LIVE).sortableInScope(Scope.ARCHIVED)
									)
									.withAttribute(ATTRIBUTE_CATEGORY_OPEN, Boolean.class, whichIs -> whichIs.filterableInScope(Scope.ARCHIVED))
							)
							.updateVia(session);
					}
				)
			);
		}

		@Test
		@DisplayName("Entity reflected references should remain across scopes")
		void shouldRecreateReflectedReferencesInSeparateScopes() {
			/* create schema for entity archival */
			final Scope[] scopes = new Scope[]{Scope.LIVE, Scope.ARCHIVED};
			EvitaArchivingTest.this.evita.defineCatalog(TEST_CATALOG)
				.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.uniqueGloballyInScope(scopes).sortableInScope(scopes))
				.updateViaNewSession(EvitaArchivingTest.this.evita);

			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.CATEGORY)
						.withoutGeneratedPrimaryKey()
						.withGlobalAttribute(ATTRIBUTE_CODE)
						.withReflectedReferenceToEntity(
							REFLECTED_REFERENCE_NAME,
							Entities.PRODUCT,
							Entities.CATEGORY,
							whichIs -> whichIs.indexedInScope(scopes).withAttributesInherited()
						)
						.withHierarchy()
						.updateVia(session);

					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withGlobalAttribute(ATTRIBUTE_CODE)
						.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> thatIs.localized().filterableInScope(scopes).sortableInScope(scopes))
						.withSortableAttributeCompound(
							ATTRIBUTE_CODE_NAME,
							new AttributeElement(ATTRIBUTE_CODE, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
							new AttributeElement(ATTRIBUTE_NAME, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
						)
						.withPriceInCurrency(CURRENCY_CZK, CURRENCY_EUR)
						.withReferenceToEntity(
							Entities.CATEGORY,
							Entities.CATEGORY,
							Cardinality.ZERO_OR_MORE,
							thatIs -> thatIs
								.indexedInScope(scopes)
								.withAttribute(ATTRIBUTE_CATEGORY_MARKET, String.class, whichIs -> whichIs.filterableInScope(scopes).sortableInScope(scopes))
								.withAttribute(ATTRIBUTE_CATEGORY_OPEN, Boolean.class, whichIs -> whichIs.filterableInScope(scopes))
								.withSortableAttributeCompound(
									ATTRIBUTE_CATEGORY_MARKET_OPEN,
									new AttributeElement(ATTRIBUTE_CATEGORY_MARKET, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
									new AttributeElement(ATTRIBUTE_CATEGORY_OPEN, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
								)
						)
						.updateVia(session);
				}
			);

			// upsert entities product depends on
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.CATEGORY, 1)
						.setAttribute(ATTRIBUTE_CODE, "electronics")
						.upsertVia(session);

					session.createNewEntity(Entities.CATEGORY, 2)
						.setParent(1)
						.setAttribute(ATTRIBUTE_CODE, "TV")
						.upsertVia(session);
				}
			);

			// create product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.PRODUCT, 100)
						.setAttribute(ATTRIBUTE_CODE, "TV-123")
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "TV")
						.setReference(Entities.CATEGORY, 2, whichIs -> whichIs.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "EU").setAttribute(ATTRIBUTE_CATEGORY_OPEN, true))
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), true)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_EUR, new BigDecimal("10"), new BigDecimal("21"), new BigDecimal("12.1"), true)
						.upsertVia(session);
				}
			);

			// check category has reflected reference to product
			final SealedEntity category = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent())
						.orElse(null);
				}
			);
			assertNotNull(category);
			final ReferenceContract products = category.getReference(REFLECTED_REFERENCE_NAME, 100).orElse(null);
			assertNotNull(products);
			assertEquals("EU", products.getAttribute(ATTRIBUTE_CATEGORY_MARKET));

			// client can query for category by having product
			assertCategoryContainsProduct(new EntityReference(Entities.CATEGORY, 2), 100, Scope.LIVE);
			assertProductContainsCategory(new EntityReference(Entities.PRODUCT, 100), 2, Scope.LIVE);

			// archive product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);

			// check category still has reflected reference to product
			final SealedEntity categoryAfterArchiving = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent())
						.orElse(null);
				}
			);
			assertNotNull(categoryAfterArchiving);
			final ReferenceContract productsAfterArchiving = categoryAfterArchiving.getReference(REFLECTED_REFERENCE_NAME, 100).orElse(null);
			assertNotNull(productsAfterArchiving);

			// client can query for category by having product
			assertCategoryContainsProduct(new EntityReference(Entities.CATEGORY, 2), 100, Scope.LIVE, Scope.ARCHIVED);
			assertProductContainsCategory(new EntityReference(Entities.PRODUCT, 100), 2, Scope.LIVE, Scope.ARCHIVED);
			// each entity is reachable only in its own scope, but the cross-scope relation is visible from both ends:
			// the live category keeps its maintained mirror to the archived product, and the archived product keeps its
			// primary reference queryable in the archived scope (invariant I1)
			assertCategoryContainsProduct(new EntityReference(Entities.CATEGORY, 2), 100, Scope.LIVE);
			assertCategoryDoesNotContainProduct(100, Scope.ARCHIVED);
			assertProductDoesNotContainCategory(2, Scope.LIVE);
			assertProductContainsCategory(new EntityReference(Entities.PRODUCT, 100), 2, Scope.ARCHIVED);

			// archive category entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.CATEGORY, 2);
				}
			);

			// check archived category still has reflected reference to product
			final SealedEntity archivedCategory = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.queryOne(
						Query.query(
							collection(Entities.CATEGORY),
							filterBy(
								scope(Scope.ARCHIVED),
								entityPrimaryKeyInSet(2)
							),
							require(
								entityFetchAll()
							)
						),
						SealedEntity.class
					).orElse(null);
				}
			);

			assertNotNull(archivedCategory);
			final ReferenceContract archivedProducts = archivedCategory.getReference(REFLECTED_REFERENCE_NAME, 100).orElse(null);
			assertNotNull(archivedProducts);
			assertEquals("EU", archivedProducts.getAttribute(ATTRIBUTE_CATEGORY_MARKET));

			// client can query for category by having product
			assertCategoryContainsProduct(new EntityReference(Entities.CATEGORY, 2), 100, Scope.LIVE, Scope.ARCHIVED);
			assertCategoryContainsProduct(new EntityReference(Entities.CATEGORY, 2), 100, Scope.ARCHIVED);
			assertProductContainsCategory(new EntityReference(Entities.PRODUCT, 100), 2, Scope.LIVE, Scope.ARCHIVED);
			assertProductContainsCategory(new EntityReference(Entities.PRODUCT, 100), 2, Scope.ARCHIVED);
			// but not in live scope
			assertCategoryDoesNotContainProduct(100, Scope.LIVE);
			assertProductDoesNotContainCategory(2, Scope.LIVE);

			// restore both category and product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.restoreEntity(Entities.CATEGORY, 2);
					session.restoreEntity(Entities.PRODUCT, 100);
				}
			);

			// check restored category has still reflected reference to product
			final SealedEntity categoryAfterRestore = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent())
						.orElse(null);
				}
			);
			assertNotNull(categoryAfterRestore);
			final ReferenceContract productsAfterRestore = categoryAfterRestore.getReference(REFLECTED_REFERENCE_NAME, 100).orElse(null);
			assertNotNull(productsAfterRestore);
			assertEquals("EU", productsAfterRestore.getAttribute(ATTRIBUTE_CATEGORY_MARKET));

			// client can query for category by having product
			assertCategoryContainsProduct(new EntityReference(Entities.CATEGORY, 2), 100, Scope.LIVE, Scope.ARCHIVED);
			assertCategoryContainsProduct(new EntityReference(Entities.CATEGORY, 2), 100, Scope.LIVE);
			assertProductContainsCategory(new EntityReference(Entities.PRODUCT, 100), 2, Scope.LIVE, Scope.ARCHIVED);
			assertProductContainsCategory(new EntityReference(Entities.PRODUCT, 100), 2, Scope.LIVE);
			// but not in archived scope
			assertCategoryDoesNotContainProduct(100, Scope.ARCHIVED);
			assertProductDoesNotContainCategory(2, Scope.ARCHIVED);
		}

	}

	@Nested
	@DisplayName("Sorting in multiple scopes")
	class SortingInMultipleScopesTest {

		@Test
		@DisplayName("Entity sorting in multiple scopes")
		void shouldOrderInAllScopes() {
			EvitaArchivingTest.this.evita.defineCatalog(TEST_CATALOG)
				.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.sortableInScope(Scope.values()))
				.updateViaNewSession(EvitaArchivingTest.this.evita);

			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withGlobalAttribute(ATTRIBUTE_CODE)
						.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> thatIs.localized().sortableInScope(Scope.LIVE).nullable())
						.withAttribute(ATTRIBUTE_DESCRIPTION, String.class, thatIs -> thatIs.localized().nullable())
						.withSortableAttributeCompound(
							ATTRIBUTE_CODE_NAME,
							new AttributeElement[]{
								new AttributeElement(ATTRIBUTE_CODE, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
								new AttributeElement(ATTRIBUTE_NAME, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
							},
							whichIs -> whichIs.indexedInScope(Scope.LIVE)
						)
						.withAttribute(ATTRIBUTE_EAN, String.class, thatIs -> thatIs.sortableInScope(Scope.ARCHIVED).nullable())
						.withSortableAttributeCompound(
							ATTRIBUTE_CODE_EAN,
							new AttributeElement[]{
								new AttributeElement(ATTRIBUTE_CODE, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
								new AttributeElement(ATTRIBUTE_EAN, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
							},
							whichIs -> whichIs.indexedInScope(Scope.ARCHIVED)
						)
						.updateVia(session);
				}
			);

			// create product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.PRODUCT, 100)
						.setAttribute(ATTRIBUTE_CODE, "TV-123")
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "LG TV, 24\"")
						.setAttribute(ATTRIBUTE_EAN, "A099")
						.upsertVia(session);

					session.createNewEntity(Entities.PRODUCT, 101)
						.setAttribute(ATTRIBUTE_CODE, "TV-456")
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Philips TV, 32\"")
						.setAttribute(ATTRIBUTE_EAN, "A041")
						.upsertVia(session);

					session.createNewEntity(Entities.PRODUCT, 102)
						.setAttribute(ATTRIBUTE_CODE, "Radio-123")
						.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH, "Whatever")
						.upsertVia(session);

					session.createNewEntity(Entities.PRODUCT, 110)
						.setAttribute(ATTRIBUTE_CODE, "TV-023")
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "LG TV, 24\", rev. 2020")
						.setAttribute(ATTRIBUTE_EAN, "A098")
						.upsertVia(session);

					session.createNewEntity(Entities.PRODUCT, 111)
						.setAttribute(ATTRIBUTE_CODE, "TV-056")
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Philips TV, 32\", rev. 2020")
						.setAttribute(ATTRIBUTE_EAN, "A040")
						.upsertVia(session);

					session.createNewEntity(Entities.PRODUCT, 112)
						.setAttribute(ATTRIBUTE_CODE, "Radio-023")
						.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH, "Whatever")
						.upsertVia(session);
				}
			);

			// archive product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 110);
					session.archiveEntity(Entities.PRODUCT, 111);
					session.archiveEntity(Entities.PRODUCT, 112);
				}
			);

			EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final int[] sortedProductsBySharedAttribute = session.queryList(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									scope(Scope.LIVE, Scope.ARCHIVED)
								),
								orderBy(
									attributeNatural(ATTRIBUTE_CODE, OrderDirection.DESC)
								)
							),
							EntityReference.class
						).stream()
						.mapToInt(EntityReference::getPrimaryKeyOrThrowException)
						.toArray();

					assertArrayEquals(
						new int[]{101, 100, 111, 110, 102, 112},
						sortedProductsBySharedAttribute
					);

					final int[] sortedProductsByAttributes = session.queryList(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									scope(Scope.LIVE, Scope.ARCHIVED),
									entityLocaleEquals(Locale.ENGLISH)
								),
								orderBy(
									inScope(Scope.LIVE, attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC)),
									inScope(Scope.ARCHIVED, attributeNatural(ATTRIBUTE_EAN, OrderDirection.DESC)),
									attributeNatural(ATTRIBUTE_CODE, OrderDirection.ASC)
								)
							),
							EntityReference.class
						).stream()
						.mapToInt(EntityReference::getPrimaryKeyOrThrowException)
						.toArray();

					assertArrayEquals(
						new int[]{101, 100, 110, 111, 112, 102},
						sortedProductsByAttributes
					);

					final int[] sortedProductsByCompounds = session.queryList(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									scope(Scope.LIVE, Scope.ARCHIVED),
									entityLocaleEquals(Locale.ENGLISH)
								),
								orderBy(
									inScope(Scope.LIVE, attributeNatural(ATTRIBUTE_CODE_NAME, OrderDirection.DESC)),
									inScope(Scope.ARCHIVED, attributeNatural(ATTRIBUTE_CODE_EAN, OrderDirection.DESC))
								)
							),
							EntityReference.class
						).stream()
						.mapToInt(EntityReference::getPrimaryKeyOrThrowException)
						.toArray();

					assertArrayEquals(
						// first live by code name, then archived by code EAN
						new int[]{101, 100, 102, 111, 110, 112},
						sortedProductsByCompounds
					);

					assertThrows(
						AttributeNotSortableException.class,
						() -> session.queryList(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									scope(Scope.LIVE, Scope.ARCHIVED),
									entityLocaleEquals(Locale.ENGLISH)
								),
								orderBy(
									attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC)
								)
							),
							EntityReference.class
						)
					);

					assertThrows(
						AttributeNotSortableException.class,
						() -> session.queryList(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									scope(Scope.LIVE, Scope.ARCHIVED),
									entityLocaleEquals(Locale.ENGLISH)
								),
								orderBy(
									attributeNatural(ATTRIBUTE_EAN, OrderDirection.DESC)
								)
							),
							EntityReference.class
						)
					);

					assertThrows(
						AttributeNotSortableException.class,
						() -> session.queryList(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									scope(Scope.LIVE)
								),
								orderBy(
									attributeNatural(ATTRIBUTE_EAN, OrderDirection.DESC)
								)
							),
							EntityReference.class
						)
					);

					assertThrows(
						AttributeNotSortableException.class,
						() -> session.queryList(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									scope(Scope.ARCHIVED),
									entityLocaleEquals(Locale.ENGLISH)
								),
								orderBy(
									attributeNatural(ATTRIBUTE_NAME, OrderDirection.DESC)
								)
							),
							EntityReference.class
						)
					);
				}
			);
		}

	}

	@Nested
	@DisplayName("Extra results across scopes")
	class ExtraResultsAcrossScopesTest {

		@Test
		@DisplayName("Entity extra result generation in multiple scopes")
		void shouldGenerateResultsInMultipleScopes() {
			EvitaArchivingTest.this.evita.defineCatalog(TEST_CATALOG);

			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.BRAND)
						.withoutGeneratedPrimaryKey()
						.updateVia(session);

					session.defineEntitySchema(Entities.CATEGORY)
						.withoutGeneratedPrimaryKey()
						.withHierarchyIndexedInScope(Scope.LIVE)
						.updateVia(session);

					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withPriceIndexedInScope(Scope.ARCHIVED)
						.withAttribute(ATTRIBUTE_WIDTH, int.class, thatIs -> thatIs.filterableInScope(Scope.ARCHIVED))
						.withReferenceToEntity(
							Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
							thatIs -> thatIs.indexedInScope(Scope.LIVE).facetedInScope(Scope.LIVE)
						)
						.withReferenceToEntity(
							Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
							thatIs -> thatIs.indexedInScope(Scope.LIVE)
						)
						.updateVia(session);
				}
			);

			// create product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.BRAND, 1).upsertVia(session);
					session.createNewEntity(Entities.BRAND, 2).upsertVia(session);
					session.createNewEntity(Entities.BRAND, 3).upsertVia(session);

					session.createNewEntity(Entities.CATEGORY, 1).upsertVia(session);
					session.createNewEntity(Entities.CATEGORY, 2).upsertVia(session);
					session.createNewEntity(Entities.CATEGORY, 3).setParent(1).upsertVia(session);
					session.createNewEntity(Entities.CATEGORY, 4).setParent(1).upsertVia(session);
					session.createNewEntity(Entities.CATEGORY, 5).setParent(2).upsertVia(session);

					session.createNewEntity(Entities.PRODUCT, 100)
						.setAttribute(ATTRIBUTE_WIDTH, 623)
						.setReference(Entities.BRAND, 1)
						.setReference(Entities.CATEGORY, 3)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("600"), new BigDecimal("21"), new BigDecimal("621"), true)
						.upsertVia(session);

					session.createNewEntity(Entities.PRODUCT, 101)
						.setAttribute(ATTRIBUTE_WIDTH, 756)
						.setReference(Entities.BRAND, 2)
						.setReference(Entities.CATEGORY, 4)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("700"), new BigDecimal("21"), new BigDecimal("721"), true)
						.upsertVia(session);

					session.createNewEntity(Entities.PRODUCT, 102)
						.setAttribute(ATTRIBUTE_WIDTH, 989)
						.setReference(Entities.BRAND, 3)
						.setReference(Entities.CATEGORY, 5)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("900"), new BigDecimal("21"), new BigDecimal("821"), true)
						.upsertVia(session);

					session.createNewEntity(Entities.PRODUCT, 110)
						.setAttribute(ATTRIBUTE_WIDTH, 123)
						.setReference(Entities.BRAND, 1)
						.setReference(Entities.CATEGORY, 2)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), true)
						.upsertVia(session);

					session.createNewEntity(Entities.PRODUCT, 111)
						.setAttribute(ATTRIBUTE_WIDTH, 456)
						.setReference(Entities.BRAND, 2)
						.setReference(Entities.CATEGORY, 1)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("200"), new BigDecimal("21"), new BigDecimal("221"), true)
						.upsertVia(session);

					session.createNewEntity(Entities.PRODUCT, 112)
						.setAttribute(ATTRIBUTE_WIDTH, 789)
						.setReference(Entities.BRAND, 2)
						.setReference(Entities.CATEGORY, 2)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("300"), new BigDecimal("21"), new BigDecimal("321"), true)
						.upsertVia(session);
				}
			);

			// archive product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 110);
					session.archiveEntity(Entities.PRODUCT, 111);
					session.archiveEntity(Entities.PRODUCT, 112);
				}
			);

			EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReference> result = session.query(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								scope(Scope.LIVE, Scope.ARCHIVED),
								inScope(
									Scope.ARCHIVED,
									priceInPriceLists(PRICE_LIST_BASIC),
									priceInCurrency(CURRENCY_CZK)
								)
							),
							require(
								inScope(
									Scope.LIVE,
									facetSummaryOfReference(Entities.BRAND),
									hierarchyOfReference(
										Entities.CATEGORY,
										children("menu", statistics(StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT))
									)
								),
								inScope(
									Scope.ARCHIVED,
									attributeHistogram(10, ATTRIBUTE_WIDTH),
									priceHistogram(10)
								)
							)
						),
						EntityReference.class
					);

					assertNotNull(result);

					assertNotNull(result.getExtraResult(AttributeHistogram.class));
					assertNotNull(result.getExtraResult(AttributeHistogram.class).getHistogram(ATTRIBUTE_WIDTH));
					assertEquals(3, result.getExtraResult(AttributeHistogram.class).getHistogram(ATTRIBUTE_WIDTH).getOverallCount());

					assertNotNull(result.getExtraResult(PriceHistogram.class));
					assertEquals(3, result.getExtraResult(PriceHistogram.class).getOverallCount());

					assertNotNull(result.getExtraResult(FacetSummary.class));
					assertEquals(
						"""
							Facet summary:
								BRAND: non-grouped [3]:
									[ ] 1 (1)
									[ ] 2 (1)
									[ ] 3 (1)""",
						result.getExtraResult(FacetSummary.class).prettyPrint()
					);

					assertNotNull(result.getExtraResult(Hierarchy.class));
					assertEquals(
						"""
							CATEGORY
							    menu
							        [2:2] CATEGORY: 1
							            [1:0] CATEGORY: 3
							            [1:0] CATEGORY: 4
							        [1:1] CATEGORY: 2
							            [1:0] CATEGORY: 5
							""",
						normalizeLineEndings(
							result.getExtraResult(Hierarchy.class).toString()
						)
					);

					assertThrows(
						ReferenceNotFacetedException.class,
						() -> session.query(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									scope(Scope.LIVE, Scope.ARCHIVED)
								),
								require(
									facetSummaryOfReference(Entities.BRAND)
								)
							),
							EntityReference.class
						)
					);

					assertThrows(
						EvitaInvalidUsageException.class,
						() -> session.query(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									scope(Scope.LIVE, Scope.ARCHIVED)
								),
								require(
									hierarchyOfReference(
										Entities.CATEGORY,
										children("menu", statistics(StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT))
									)
								)
							),
							EntityReference.class
						)
					);

					assertThrows(
						HierarchyNotIndexedException.class,
						() -> session.query(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									scope(Scope.ARCHIVED)
								),
								require(
									hierarchyOfReference(
										Entities.CATEGORY,
										children("menu", statistics(StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT))
									)
								)
							),
							EntityReference.class
						)
					);

					assertThrows(
						AttributeNotFilterableException.class,
						() -> session.query(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									scope(Scope.LIVE, Scope.ARCHIVED)
								),
								require(
									attributeHistogram(10, ATTRIBUTE_WIDTH)
								)
							),
							EntityReference.class
						)
					);

					assertThrows(
						PriceNotIndexedException.class,
						() -> session.query(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									scope(Scope.LIVE, Scope.ARCHIVED),
									inScope(
										Scope.ARCHIVED,
										priceInPriceLists(PRICE_LIST_BASIC),
										priceInCurrency(CURRENCY_CZK)
									)
								),
								require(
									priceHistogram(10)
								)
							),
							EntityReference.class
						)
					);
				}
			);
		}

		@Test
		@DisplayName("Entity extra result generation over multiple scopes")
		void shouldGenerateResultsInOverMultipleScopes() {
			EvitaArchivingTest.this.evita.defineCatalog(TEST_CATALOG);

			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.BRAND)
						.withoutGeneratedPrimaryKey()
						.updateVia(session);

					session.defineEntitySchema(Entities.CATEGORY)
						.withoutGeneratedPrimaryKey()
						.withHierarchyIndexedInScope(Scope.values())
						.updateVia(session);

					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withPriceIndexedInScope(Scope.values())
						.withAttribute(ATTRIBUTE_WIDTH, int.class, thatIs -> thatIs.filterableInScope(Scope.values()))
						.withReferenceToEntity(
							Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
							thatIs -> thatIs.indexedForFilteringAndPartitioningInScope(Scope.values()).facetedInScope(Scope.values())
						)
						.withReferenceToEntity(
							Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
							thatIs -> thatIs.indexedForFilteringAndPartitioningInScope(Scope.values())
						)
						.updateVia(session);
				}
			);

			// create product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.BRAND, 1).upsertVia(session);
					session.createNewEntity(Entities.BRAND, 2).upsertVia(session);
					session.createNewEntity(Entities.BRAND, 3).upsertVia(session);

					session.createNewEntity(Entities.CATEGORY, 1).upsertVia(session);
					session.createNewEntity(Entities.CATEGORY, 2).upsertVia(session);
					session.createNewEntity(Entities.CATEGORY, 3).setParent(1).upsertVia(session);
					session.createNewEntity(Entities.CATEGORY, 4).setParent(1).upsertVia(session);
					session.createNewEntity(Entities.CATEGORY, 5).setParent(2).upsertVia(session);

					session.createNewEntity(Entities.PRODUCT, 100)
						.setAttribute(ATTRIBUTE_WIDTH, 623)
						.setReference(Entities.BRAND, 1)
						.setReference(Entities.CATEGORY, 3)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("600"), new BigDecimal("21"), new BigDecimal("621"), true)
						.upsertVia(session);

					session.createNewEntity(Entities.PRODUCT, 101)
						.setAttribute(ATTRIBUTE_WIDTH, 756)
						.setReference(Entities.BRAND, 2)
						.setReference(Entities.CATEGORY, 4)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("700"), new BigDecimal("21"), new BigDecimal("721"), true)
						.upsertVia(session);

					session.createNewEntity(Entities.PRODUCT, 102)
						.setAttribute(ATTRIBUTE_WIDTH, 989)
						.setReference(Entities.BRAND, 3)
						.setReference(Entities.CATEGORY, 5)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("900"), new BigDecimal("21"), new BigDecimal("821"), true)
						.upsertVia(session);

					session.createNewEntity(Entities.PRODUCT, 110)
						.setAttribute(ATTRIBUTE_WIDTH, 123)
						.setReference(Entities.BRAND, 1)
						.setReference(Entities.CATEGORY, 2)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), true)
						.upsertVia(session);

					session.createNewEntity(Entities.PRODUCT, 111)
						.setAttribute(ATTRIBUTE_WIDTH, 456)
						.setReference(Entities.BRAND, 2)
						.setReference(Entities.CATEGORY, 1)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("200"), new BigDecimal("21"), new BigDecimal("221"), true)
						.upsertVia(session);

					session.createNewEntity(Entities.PRODUCT, 112)
						.setAttribute(ATTRIBUTE_WIDTH, 789)
						.setReference(Entities.BRAND, 2)
						.setReference(Entities.CATEGORY, 2)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("300"), new BigDecimal("21"), new BigDecimal("321"), true)
						.upsertVia(session);
				}
			);

			// archive product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 110);
					session.archiveEntity(Entities.PRODUCT, 111);
					session.archiveEntity(Entities.PRODUCT, 112);
				}
			);

			EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final EvitaResponse<EntityReference> result = session.query(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								scope(Scope.LIVE, Scope.ARCHIVED),
								priceInPriceLists(PRICE_LIST_BASIC),
								priceInCurrency(CURRENCY_CZK)
							),
							require(
								facetSummaryOfReference(Entities.BRAND),
								inScope(
									Scope.LIVE,
									hierarchyOfReference(
										Entities.CATEGORY,
										children("liveMenu", statistics(StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT))
									)
								),
								inScope(
									Scope.ARCHIVED,
									hierarchyOfReference(
										Entities.CATEGORY,
										children("archiveMenu", statistics(StatisticsType.CHILDREN_COUNT, StatisticsType.QUERIED_ENTITY_COUNT))
									)
								),
								attributeHistogram(10, ATTRIBUTE_WIDTH),
								priceHistogram(10)
							)
						),
						EntityReference.class
					);

					assertNotNull(result);

					assertNotNull(result.getExtraResult(AttributeHistogram.class));
					assertNotNull(result.getExtraResult(AttributeHistogram.class).getHistogram(ATTRIBUTE_WIDTH));
					assertEquals(6, result.getExtraResult(AttributeHistogram.class).getHistogram(ATTRIBUTE_WIDTH).getOverallCount());

					assertNotNull(result.getExtraResult(PriceHistogram.class));
					assertEquals(6, result.getExtraResult(PriceHistogram.class).getOverallCount());

					assertNotNull(result.getExtraResult(FacetSummary.class));
					assertEquals(
						"""
							Facet summary:
								BRAND: non-grouped [6]:
									[ ] 1 (2)
									[ ] 2 (3)
									[ ] 3 (1)""",
						result.getExtraResult(FacetSummary.class).prettyPrint()
					);

					assertNotNull(result.getExtraResult(Hierarchy.class));
					assertEquals(
						"""
							CATEGORY
							    liveMenu
							        [2:2] CATEGORY: 1
							            [1:0] CATEGORY: 3
							            [1:0] CATEGORY: 4
							        [1:1] CATEGORY: 2
							            [1:0] CATEGORY: 5
							""",
						normalizeLineEndings(result.getExtraResult(Hierarchy.class).toString())
					);
				}
			);
		}

	}

	@Nested
	@DisplayName("Unique attributes across scopes")
	class UniqueAttributesAcrossScopesTest {

		@Test
		@DisplayName("Archived entity should be deletable")
		void shouldBeAbleToDeleteArchivedEntity() {
			/* create schema for entity archival */
			createSchemaForEntityArchiving(Scope.LIVE, Scope.ARCHIVED);

			// upsert entities product depends on
			createBrandAndCategoryEntities();

			// create product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.PRODUCT, 100)
						.setAttribute(ATTRIBUTE_CODE, "TV-123")
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "TV")
						.setReference(Entities.BRAND, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_BRAND_EAN, "123"))
						.setReference(
							Entities.CATEGORY, 2,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "EU")
								.setAttribute(ATTRIBUTE_CATEGORY_OPEN, true)
						)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), true)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_EUR, new BigDecimal("10"), new BigDecimal("21"), new BigDecimal("12.1"), true)
						.upsertVia(session);
				}
			);

			// archive product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);

			// delete archived entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.deleteEntity(Entities.PRODUCT, 100);
				}
			);

			// check entity can't be found in any scope
			checkProductCannotBeLookedUpByIndexes(Scope.LIVE);
			checkProductCannotBeLookedUpByIndexes(Scope.ARCHIVED);
		}

		@Test
		@DisplayName("Should be able to violate unique constraints when entity is archived")
		void shouldBeAbleToViolateUniqueConstraintsWhenEntityIsArchived() {
			/* create schema for entity archival */
			EvitaArchivingTest.this.evita.defineCatalog(TEST_CATALOG)
				.withAttribute(
					ATTRIBUTE_CODE,
					String.class,
					thatIs -> thatIs
						.uniqueGloballyInScope(Scope.values())
						.sortableInScope(Scope.values())
				)
				.updateViaNewSession(EvitaArchivingTest.this.evita);

			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withGlobalAttribute(ATTRIBUTE_CODE)
						.withAttribute(
							ATTRIBUTE_NAME,
							String.class,
							thatIs -> thatIs.localized().uniqueWithinLocaleInScope(Scope.values()))
						.updateVia(session);
				}
			);

			// upsert non-conflicting entities
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(ATTRIBUTE_CODE, "electronics")
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "electronics")
						.upsertVia(session);

					session.createNewEntity(Entities.PRODUCT, 2)
						.setAttribute(ATTRIBUTE_CODE, "TV")
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "TV")
						.upsertVia(session);
				}
			);

			// archive product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 1);
				}
			);

			// upsert change unique key to conflict with archived entity and upsert it
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(Entities.PRODUCT, 2, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setAttribute(ATTRIBUTE_CODE, "electronics")
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "electronics")
						.upsertVia(session);
				}
			);

			// try to find entities by the conflicting unique key
			assertEquals(
				new EntityReference(Entities.PRODUCT, 2),
				queryProductReferenceBy(new Scope[]{Scope.LIVE}, attributeEquals(ATTRIBUTE_CODE, "electronics"))
			);
			assertEquals(
				new EntityReference(Entities.PRODUCT, 2),
				queryProductReferenceBy(
					new Scope[]{Scope.LIVE},
					attributeEquals(ATTRIBUTE_NAME, "electronics"), entityLocaleEquals(Locale.ENGLISH)
				)
			);
			assertEquals(
				new EntityReference(Entities.PRODUCT, 1),
				queryProductReferenceBy(new Scope[]{Scope.ARCHIVED}, attributeEquals(ATTRIBUTE_CODE, "electronics"))
			);
			assertEquals(
				new EntityReference(Entities.PRODUCT, 1),
				queryProductReferenceBy(
					new Scope[]{Scope.ARCHIVED},
					attributeEquals(ATTRIBUTE_NAME, "electronics"), entityLocaleEquals(Locale.ENGLISH)
				)
			);

			// when we look for the unique key in both scopes, the engine should prefer the live entity
			assertEquals(
				new EntityReference(Entities.PRODUCT, 2),
				queryProductReferenceBy(
					new Scope[]{Scope.LIVE, Scope.ARCHIVED},
					attributeEquals(ATTRIBUTE_CODE, "electronics")
				)
			);
			assertEquals(
				new EntityReference(Entities.PRODUCT, 2),
				queryProductReferenceBy(
					new Scope[]{Scope.LIVE, Scope.ARCHIVED},
					attributeEquals(ATTRIBUTE_NAME, "electronics"), entityLocaleEquals(Locale.ENGLISH)
				)
			);
		}

		@Test
		@DisplayName("Should be able to retrieve entities by globally unique attributes in both scopes")
		void shouldBeAbleToRetrieveEntitiesByGloballyUniqueAttributesInBothScopes() {
			/* create schema for entity archival */
			EvitaArchivingTest.this.evita.defineCatalog(TEST_CATALOG)
				.withAttribute(
					ATTRIBUTE_URL,
					String.class,
					thatIs -> thatIs
						.uniqueGloballyInScope(Scope.values())
						.localized()
				)
				.updateViaNewSession(EvitaArchivingTest.this.evita);

			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withGlobalAttribute(ATTRIBUTE_URL)
						.updateVia(session);
				}
			);

			// upsert non-conflicting entities
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(ATTRIBUTE_URL, Locale.ENGLISH, "/electronics")
						.upsertVia(session);

					session.createNewEntity(Entities.PRODUCT, 2)
						.setAttribute(ATTRIBUTE_URL, Locale.ENGLISH, "/tv")
						.upsertVia(session);
				}
			);

			// archive product entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 1);
				}
			);

			EvitaArchivingTest.this.evita.close();

			EvitaArchivingTest.this.evita = new Evita(
				getEvitaConfiguration()
			);
			EvitaArchivingTest.this.evita.waitUntilFullyInitialized();

			// try to find entities by the conflicting unique key
			assertArrayEquals(
				new EntityReference[]{
					new EntityReference(Entities.PRODUCT, 1),
					new EntityReference(Entities.PRODUCT, 2)
				},
				EvitaArchivingTest.this.evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						return session.queryList(
							query(
								collection(Entities.PRODUCT),
								filterBy(
									scope(Scope.LIVE, Scope.ARCHIVED),
									attributeInSet(ATTRIBUTE_URL, "/electronics", "/tv")
								)
							).normalizeQuery(),
							EntityReference.class
						).toArray(EntityReference[]::new);
					}
				)
			);
		}

	}

	private void createBrandAndCategoryEntities() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.createNewEntity(Entities.BRAND, 1).upsertVia(session);
				session.createNewEntity(Entities.BRAND, 2).upsertVia(session);

				session.createNewEntity(Entities.CATEGORY, 1)
					.setAttribute(ATTRIBUTE_CODE, "electronics")
					.upsertVia(session);

				session.createNewEntity(Entities.CATEGORY, 2)
					.setParent(1)
					.setAttribute(ATTRIBUTE_CODE, "TV")
					.upsertVia(session);
			}
		);
	}

	private void createSchemaForEntityArchiving(@Nonnull Scope... indexScope) {
		this.evita.defineCatalog(TEST_CATALOG)
			.withAttribute(
				ATTRIBUTE_CODE, String.class,
				thatIs -> thatIs.uniqueGloballyInScope(indexScope).sortableInScope(indexScope)
			)
			.updateViaNewSession(this.evita);

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.BRAND)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);

				session.defineEntitySchema(Entities.CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.withHierarchyIndexedInScope(indexScope)
					.updateVia(session);

				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> thatIs.localized().filterableInScope(indexScope).sortableInScope(indexScope))
					.withSortableAttributeCompound(
						ATTRIBUTE_CODE_NAME,
						new AttributeElement[]{
							new AttributeElement(ATTRIBUTE_CODE, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
							new AttributeElement(ATTRIBUTE_NAME, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
						},
						whichIs -> whichIs.indexedInScope(indexScope)
					)
					.withPriceInCurrencyIndexedInScope(2, new Currency[]{CURRENCY_CZK, CURRENCY_EUR}, indexScope)
					.withReferenceToEntity(
						Entities.BRAND,
						Entities.BRAND,
						Cardinality.ZERO_OR_ONE,
						thatIs -> thatIs
							.indexedForFilteringAndPartitioningInScope(indexScope)
							.withAttribute(ATTRIBUTE_BRAND_EAN, String.class, whichIs -> whichIs.filterableInScope(indexScope).sortableInScope(indexScope))
					)
					.withReferenceToEntity(
						Entities.CATEGORY,
						Entities.CATEGORY,
						Cardinality.ZERO_OR_MORE,
						thatIs -> thatIs
							.indexedForFilteringAndPartitioningInScope(indexScope)
							.withAttribute(ATTRIBUTE_CATEGORY_MARKET, String.class, whichIs -> whichIs.filterableInScope(indexScope).sortableInScope(indexScope))
							.withAttribute(ATTRIBUTE_CATEGORY_OPEN, Boolean.class, whichIs -> whichIs.filterableInScope(indexScope))
							.withSortableAttributeCompound(
								ATTRIBUTE_CATEGORY_MARKET_OPEN,
								new AttributeElement(ATTRIBUTE_CATEGORY_MARKET, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
								new AttributeElement(ATTRIBUTE_CATEGORY_OPEN, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
							)
					)
					.updateVia(session);
			}
		);
	}

	private void checkProductCanBeLookedUpByIndexes(Scope... scope) {
		final EntityReference productReference = new EntityReference(Entities.PRODUCT, 100);
		assertEquals(
			productReference,
			queryProductReferenceBy(scope, attributeEquals(ATTRIBUTE_CODE, "TV-123"))
		);
		assertEquals(
			productReference,
			queryProductReferenceBy(scope, attributeEquals(ATTRIBUTE_NAME, "TV"), entityLocaleEquals(Locale.ENGLISH))
		);
		assertEquals(
			productReference,
			queryProductReferenceBy(scope, referenceHaving(Entities.BRAND, entityPrimaryKeyInSet(1)))
		);
		assertEquals(
			productReference,
			queryProductReferenceBy(scope, hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(2)))
		);
		assertEquals(
			productReference,
			queryProductReferenceBy(scope, priceInCurrency(CURRENCY_CZK), priceInPriceLists(PRICE_LIST_BASIC))
		);
	}

	private void checkProductCannotBeLookedUpByIndexes(@Nonnull Scope scope) {
		final SealedEntitySchema schema = this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntitySchema(Entities.PRODUCT).orElseThrow();
			}
		);
		// global attribute
		final EntityAttributeSchemaContract codeSchema = schema.getAttribute(ATTRIBUTE_CODE).orElseThrow();
		if (codeSchema.isFilterableInScope(scope) || codeSchema.isUniqueInScope(scope)) {
			assertNull(queryProductReferenceBy(new Scope[] {scope}, attributeEquals(ATTRIBUTE_CODE, "TV-123")));
		} else {
			assertThrows(
				AttributeNotFilterableException.class,
				() -> queryProductReferenceBy(new Scope[] {scope}, attributeEquals(ATTRIBUTE_CODE, "TV-123"))
			);
		}
		// entity attribute
		if (schema.getAttribute(ATTRIBUTE_NAME).orElseThrow().isFilterableInScope(scope)) {
			assertNull(queryProductReferenceBy(new Scope[] {scope}, attributeEquals(ATTRIBUTE_NAME, "TV"), entityLocaleEquals(Locale.ENGLISH)));
		} else {
			assertThrows(
				AttributeNotFilterableException.class,
				() -> queryProductReferenceBy(new Scope[] {scope}, attributeEquals(ATTRIBUTE_NAME, "TV"), entityLocaleEquals(Locale.ENGLISH))
			);
		}
		// references
		if (schema.getReference(Entities.BRAND).orElseThrow().isIndexedInScope(scope)) {
			assertNull(queryProductReferenceBy(new Scope[] {scope}, referenceHaving(Entities.BRAND, entityPrimaryKeyInSet(1))));
		} else {
			assertThrows(
				ReferenceNotIndexedException.class,
				() -> queryProductReferenceBy(new Scope[] {scope}, referenceHaving(Entities.BRAND, entityPrimaryKeyInSet(1)))
			);
		}
		// hierarchy
		if (schema.getReference(Entities.CATEGORY).orElseThrow().isIndexedInScope(scope)) {
			assertNull(queryProductReferenceBy(new Scope[] {scope}, hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(2))));
		} else {
			assertThrows(
				ReferenceNotIndexedException.class,
				() -> queryProductReferenceBy(new Scope[] {scope}, hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(2)))
			);
		}
		// price
		if (schema.isPriceIndexedInScope(scope)) {
			assertNull(queryProductReferenceBy(new Scope[] {scope}, priceInCurrency(CURRENCY_CZK), priceInPriceLists(PRICE_LIST_BASIC)));
		} else {
			assertThrows(
				PriceNotIndexedException.class,
				() -> queryProductReferenceBy(new Scope[] {scope}, priceInCurrency(CURRENCY_CZK), priceInPriceLists(PRICE_LIST_BASIC))
			);
		}
	}

	@Nullable
	private EntityReference queryProductReferenceBy(@Nonnull Scope[] scope, @Nonnull FilterConstraint... filterBy) {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOne(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							ArrayUtils.mergeArrays(
								filterBy,
								ArrayUtils.isEmptyOrItsValuesNull(scope) ?
									FilterConstraint.EMPTY_ARRAY : new FilterConstraint[] { scope(scope) }
							)
						)
					).normalizeQuery(),
					EntityReference.class
				);
			}
		).orElse(null);
	}

	private void assertCategoryContainsProduct(
		@Nonnull EntityReference category,
		int productPk,
		@Nonnull Scope... scopes
	) {
		assertEquals(
			category,
			this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.queryOne(
						Query.query(
							collection(Entities.CATEGORY),
							filterBy(
								referenceHaving(
									REFLECTED_REFERENCE_NAME,
									entityPrimaryKeyInSet(productPk)
								),
								scope(scopes)
							)
						),
						EntityReference.class
					).orElse(null);
				}
			)
		);
	}

	private void assertCategoryDoesNotContainProduct(
		int productPk,
		@Nonnull Scope... scopes
	) {
		assertNull(
			this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.queryOne(
						Query.query(
							collection(Entities.CATEGORY),
							filterBy(
								referenceHaving(
									REFLECTED_REFERENCE_NAME,
									entityPrimaryKeyInSet(productPk)
								),
								scope(scopes)
							)
						),
						EntityReference.class
					).orElse(null);
				}
			)
		);
	}

	private void assertProductContainsCategory(
		@Nonnull EntityReference product,
		int categoryPk,
		@Nonnull Scope... scopes
	) {
		assertEquals(
			product,
			this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.queryOne(
						Query.query(
							collection(Entities.PRODUCT),
							filterBy(
								referenceHaving(
									Entities.CATEGORY,
									entityPrimaryKeyInSet(categoryPk)
								),
								scope(scopes)
							)
						),
						EntityReference.class
					).orElse(null);
				}
			)
		);
	}

	private void assertProductDoesNotContainCategory(
		int categoryPk,
		@Nonnull Scope... scopes
	) {
		assertNull(
			this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.queryOne(
						Query.query(
							collection(Entities.PRODUCT),
							filterBy(
								referenceHaving(
									Entities.CATEGORY,
									entityPrimaryKeyInSet(categoryPk)
								),
								scope(scopes)
							)
						),
						EntityReference.class
					).orElse(null);
				}
			)
		);
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return getEvitaConfiguration(-1);
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration(int inactivityTimeoutInSeconds) {
		return newTestEvitaConfigurationBuilder(this.paths)
			.server(
				ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(inactivityTimeoutInSeconds)
					.build()
			)
			.build();
	}


	/**
	 * Defines a catalog schema where {@link Entities#PRODUCT} owns the primary reference to {@link Entities#CATEGORY}
	 * and {@link Entities#CATEGORY} carries the reflected reference back to the product. Both references are indexed in
	 * the {@link Scope#LIVE} scope only, so archiving the product drops the reflected reference on the target side.
	 */
	private void defineLiveOnlyReflectedSchema() {
		this.evita.defineCatalog(TEST_CATALOG)
			.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.uniqueGlobally().sortable())
			.updateViaNewSession(this.evita);

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.withReflectedReferenceToEntity(
						REFLECTED_REFERENCE_NAME,
						Entities.PRODUCT,
						Entities.CATEGORY,
						whichIs -> whichIs.indexedForFilteringAndPartitioning().withAttributesInherited()
					)
					.updateVia(session);

				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> thatIs.localized().filterable().sortable())
					.withPriceInCurrency(CURRENCY_CZK, CURRENCY_EUR)
					.withReferenceToEntity(
						Entities.CATEGORY,
						Entities.CATEGORY,
						Cardinality.ZERO_OR_MORE,
						thatIs -> thatIs
							.indexedForFilteringAndPartitioning()
							.withAttribute(ATTRIBUTE_CATEGORY_MARKET, String.class, whichIs -> whichIs.filterable().sortable())
							.withAttribute(ATTRIBUTE_CATEGORY_OPEN, Boolean.class, AttributeSchemaEditor::filterable)
					)
					.updateVia(session);
			}
		);
	}

	/**
	 * Creates category 2 and product 100 with the product holding a primary reference to that category (the product
	 * references a reflected-target entity that some scenarios later remove).
	 */
	private void createArchivingFixtureEntities() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.createNewEntity(Entities.CATEGORY, 2)
					.setAttribute(ATTRIBUTE_CODE, "TV")
					.upsertVia(session);
			}
		);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.createNewEntity(Entities.PRODUCT, 100)
					.setAttribute(ATTRIBUTE_CODE, "TV-123")
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "TV")
					.setReference(Entities.CATEGORY, 2, whichIs -> whichIs.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "EU").setAttribute(ATTRIBUTE_CATEGORY_OPEN, true))
					.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), true)
					.setPrice(1, PRICE_LIST_BASIC, CURRENCY_EUR, new BigDecimal("10"), new BigDecimal("21"), new BigDecimal("12.1"), true)
					.upsertVia(session);
			}
		);
	}

	/**
	 * Variant of {@link #defineLiveOnlyReflectedSchema()} where both the primary reference and the reflected reference
	 * are indexed in the {@link Scope#LIVE} and {@link Scope#ARCHIVED} scopes. In this configuration archiving the
	 * holder retains the reflected reference across scopes instead of dropping it.
	 */
	private void defineBothScopesReflectedSchema() {
		final Scope[] scopes = new Scope[]{Scope.LIVE, Scope.ARCHIVED};
		this.evita.defineCatalog(TEST_CATALOG)
			.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.uniqueGloballyInScope(scopes).sortableInScope(scopes))
			.updateViaNewSession(this.evita);

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.withReflectedReferenceToEntity(
						REFLECTED_REFERENCE_NAME,
						Entities.PRODUCT,
						Entities.CATEGORY,
						whichIs -> whichIs.indexedInScope(scopes).withAttributesInherited()
					)
					.updateVia(session);

				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> thatIs.localized().filterableInScope(scopes).sortableInScope(scopes))
					.withPriceInCurrency(CURRENCY_CZK, CURRENCY_EUR)
					.withReferenceToEntity(
						Entities.CATEGORY,
						Entities.CATEGORY,
						Cardinality.ZERO_OR_MORE,
						thatIs -> thatIs
							.indexedInScope(scopes)
							.withAttribute(ATTRIBUTE_CATEGORY_MARKET, String.class, whichIs -> whichIs.filterableInScope(scopes).sortableInScope(scopes))
							.withAttribute(ATTRIBUTE_CATEGORY_OPEN, Boolean.class, whichIs -> whichIs.filterableInScope(scopes))
					)
					.updateVia(session);
			}
		);
	}

	/**
	 * Variant of the reflected schema where the primary reference is indexed in both the {@link Scope#LIVE} and
	 * {@link Scope#ARCHIVED} scopes while the reflected reference is indexed in the {@link Scope#LIVE} scope only. This
	 * is the third legal indexing configuration (the reflected scopes must be a subset of the primary scopes): archiving
	 * the holder drops the reflected mirror on the target yet keeps the holder's primary reference indexed in the
	 * archived scope.
	 */
	private void defineMixedScopeReflectedSchema() {
		final Scope[] bothScopes = new Scope[]{Scope.LIVE, Scope.ARCHIVED};
		this.evita.defineCatalog(TEST_CATALOG)
			.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.uniqueGloballyInScope(bothScopes).sortableInScope(bothScopes))
			.updateViaNewSession(this.evita);

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.withReflectedReferenceToEntity(
						REFLECTED_REFERENCE_NAME,
						Entities.PRODUCT,
						Entities.CATEGORY,
						whichIs -> whichIs.indexedForFilteringAndPartitioning().withAttributesInherited()
					)
					.updateVia(session);

				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> thatIs.localized().filterableInScope(bothScopes).sortableInScope(bothScopes))
					.withPriceInCurrency(CURRENCY_CZK, CURRENCY_EUR)
					.withReferenceToEntity(
						Entities.CATEGORY,
						Entities.CATEGORY,
						Cardinality.ZERO_OR_MORE,
						thatIs -> thatIs
							.indexedInScope(bothScopes)
							.withAttribute(ATTRIBUTE_CATEGORY_MARKET, String.class, whichIs -> whichIs.filterableInScope(bothScopes).sortableInScope(bothScopes))
							.withAttribute(ATTRIBUTE_CATEGORY_OPEN, Boolean.class, whichIs -> whichIs.filterableInScope(bothScopes))
					)
					.updateVia(session);
			}
		);
	}

	/**
	 * Variant of {@link #createArchivingFixtureEntities()} that creates two reflected-target categories (2 and 3) and a
	 * product 100 holding a primary reference to both. Used by the cardinality scenarios that remove one target while
	 * the other survives.
	 */
	private void createTwoTargetArchivingFixture() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.createNewEntity(Entities.CATEGORY, 2)
					.setAttribute(ATTRIBUTE_CODE, "TV")
					.upsertVia(session);
				session.createNewEntity(Entities.CATEGORY, 3)
					.setAttribute(ATTRIBUTE_CODE, "Radio")
					.upsertVia(session);
			}
		);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.createNewEntity(Entities.PRODUCT, 100)
					.setAttribute(ATTRIBUTE_CODE, "TV-123")
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "TV")
					.setReference(Entities.CATEGORY, 2, whichIs -> whichIs.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "EU").setAttribute(ATTRIBUTE_CATEGORY_OPEN, true))
					.setReference(Entities.CATEGORY, 3, whichIs -> whichIs.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "US").setAttribute(ATTRIBUTE_CATEGORY_OPEN, true))
					.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), true)
					.setPrice(1, PRICE_LIST_BASIC, CURRENCY_EUR, new BigDecimal("10"), new BigDecimal("21"), new BigDecimal("12.1"), true)
					.upsertVia(session);
			}
		);
	}

	/**
	 * Missing E2E coverage for archiving/removal behaviour of plain (non-reflected) references. These scenarios act as
	 * the control group for the reflected-reference scenarios: a plain reference carries no reflected mirror, so the
	 * cross-scope reflected maintenance is never triggered and the primary-side bookkeeping must stay consistent.
	 */
	@Nested
	@DisplayName("Simple (non-reflected) reference behaviour across scopes")
	class SimpleReferenceArchivingScenarios {

		@DisplayName("Removing a dangling simple reference from an archived holder whose target was removed from the archived scope must not fail")
		@Test
		void shouldRemoveDanglingSimpleReferenceWhenTargetRemovedFromArchivedScope() {
			createSimpleReferenceFixture(Scope.LIVE, Scope.ARCHIVED);

			// archive both the holder and the target so the target is removed later from the ARCHIVED scope
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
					session.archiveEntity(Entities.CATEGORY, 2);
				}
			);
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(session.deleteEntity(Entities.CATEGORY, 2));
				}
			);

			final SealedEntity archivedProduct = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedProduct);
			assertNotNull(archivedProduct.getReference(Entities.CATEGORY, 2).orElse(null));

			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.CATEGORY, 2)
						.upsertVia(session);
				}
			);

			final SealedEntity archivedProductAfter = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedProductAfter);
			assertNull(archivedProductAfter.getReference(Entities.CATEGORY, 2).orElse(null));
		}

		@DisplayName("Deleting an archived holder with a simple reference to a live target must not fail")
		@Test
		void shouldDeleteArchivedHolderWithSimpleReferenceWhenTargetAlive() {
			createSimpleReferenceFixture(Scope.LIVE, Scope.ARCHIVED);

			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);

			// delete the archived holder while the target is alive
			assertDoesNotThrow(
				() -> EvitaArchivingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						assertTrue(session.deleteEntity(Entities.PRODUCT, 100));
					}
				),
				"Deleting an archived holder with a live simple-reference target must not fail"
			);

			// the holder is gone from every scope, the target is untouched
			final SealedEntity product = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.LIVE, Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNull(product);
			final SealedEntity category = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(category);
		}

		@DisplayName("Removing a dangling sibling simple reference from an archived holder keeps the surviving sibling intact")
		@Test
		void shouldRemoveOnlyDanglingSimpleSiblingAndKeepLiveSibling() {
			createSimpleTwoTargetFixture(Scope.LIVE, Scope.ARCHIVED);

			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);
			// remove one target (category 3) -> its reference on the archived holder becomes dangling
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(session.deleteEntity(Entities.CATEGORY, 3));
				}
			);
			// remove the dangling sibling reference (category 3) from the archived holder
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.CATEGORY, 3)
						.upsertVia(session);
				}
			);

			// EXACT: the surviving sibling reference stays, the dead sibling reference is gone
			final SealedEntity archivedProduct = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedProduct);
			assertNotNull(archivedProduct.getReference(Entities.CATEGORY, 2).orElse(null));
			assertNull(archivedProduct.getReference(Entities.CATEGORY, 3).orElse(null));
		}

		@DisplayName("Removing a dangling simple reference from an archived holder must not fail when the target was removed before archiving")
		@Test
		void shouldRemoveDanglingSimpleReferenceWhenTargetRemovedBeforeHolderArchived() {
			createSimpleReferenceFixture(Scope.LIVE);

			// remove the target first while the holder is live (reverse order)
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(session.deleteEntity(Entities.CATEGORY, 2));
				}
			);
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);

			final SealedEntity archivedProduct = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedProduct);
			if (archivedProduct.getReference(Entities.CATEGORY, 2).isPresent()) {
				assertDoesNotThrow(
					() -> EvitaArchivingTest.this.evita.updateCatalog(
						TEST_CATALOG,
						session -> {
							session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent())
								.orElseThrow()
								.openForWrite()
								.removeReference(Entities.CATEGORY, 2)
								.upsertVia(session);
						}
					),
					"Removing the dangling simple reference after reverse-order removal must not fail"
				);
			}
			final SealedEntity archivedProductAfter = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedProductAfter);
			assertNull(archivedProductAfter.getReference(Entities.CATEGORY, 2).orElse(null));
		}

		@DisplayName("Restoring an archived holder whose simple-reference target was removed must not fail")
		@Test
		void shouldRestoreArchivedHolderWithSimpleReferenceWhenTargetRemoved() {
			createSimpleReferenceFixture(Scope.LIVE);

			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(session.deleteEntity(Entities.CATEGORY, 2));
				}
			);

			// restore the archived holder to live even though its simple-reference target no longer exists
			assertDoesNotThrow(
				() -> EvitaArchivingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.restoreEntity(Entities.PRODUCT, 100);
					}
				),
				"Restoring an archived holder whose simple-reference target was removed must not fail"
			);

			final SealedEntity liveProduct = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(liveProduct);
		}

		@DisplayName("Archiving a simple-reference target while the holder stays live must keep both entities consistent")
		@Test
		void shouldArchiveSimpleReferenceTargetWhileHolderStaysLive() {
			createSimpleReferenceFixture(Scope.LIVE, Scope.ARCHIVED);

			// archive the referenced target while the holder stays live
			assertDoesNotThrow(
				() -> EvitaArchivingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.archiveEntity(Entities.CATEGORY, 2);
					}
				),
				"Archiving a simple-reference target must not fail"
			);

			final SealedEntity liveHolder = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(liveHolder);
			final SealedEntity archivedTarget = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.CATEGORY, 2, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedTarget);

			// the holder's simple reference must remain removable without error
			assertDoesNotThrow(
				() -> EvitaArchivingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntity(Entities.PRODUCT, 100, entityFetchAllContent())
							.orElseThrow()
							.openForWrite()
							.removeReference(Entities.CATEGORY, 2)
							.upsertVia(session);
					}
				),
				"Removing the simple reference after the target was archived must not fail"
			);
		}

		/**
		 * Builds the plain-reference fixture with two target categories (2 and 3) referenced by product 100. Used by the
		 * cardinality scenario that removes one target while the other survives.
		 *
		 * @param indexScope the scopes in which the schema elements are indexed
		 */
		private void createSimpleTwoTargetFixture(@Nonnull Scope... indexScope) {
			createSchemaForEntityArchiving(indexScope);
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.CATEGORY, 2)
						.setAttribute(ATTRIBUTE_CODE, "TV")
						.upsertVia(session);
					session.createNewEntity(Entities.CATEGORY, 3)
						.setAttribute(ATTRIBUTE_CODE, "Radio")
						.upsertVia(session);
				}
			);
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.PRODUCT, 100)
						.setAttribute(ATTRIBUTE_CODE, "TV-123")
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "TV")
						.setReference(Entities.CATEGORY, 2, whichIs -> whichIs.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "EU").setAttribute(ATTRIBUTE_CATEGORY_OPEN, true))
						.setReference(Entities.CATEGORY, 3, whichIs -> whichIs.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "US").setAttribute(ATTRIBUTE_CATEGORY_OPEN, true))
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), true)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_EUR, new BigDecimal("10"), new BigDecimal("21"), new BigDecimal("12.1"), true)
						.upsertVia(session);
				}
			);
		}

		@DisplayName("Removing a dangling simple reference from an archived holder after its target was removed must not fail")
		@Test
		void shouldRemoveDanglingSimpleReferenceOnArchivedHolderWhenTargetRemoved() {
			createSimpleReferenceFixture(Scope.LIVE);

			// archive the holder
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);
			// remove the referenced target entity
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(session.deleteEntity(Entities.CATEGORY, 2));
				}
			);
			// the archived holder still carries the now-dangling simple reference
			final SealedEntity archivedProduct = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedProduct);
			assertNotNull(archivedProduct.getReference(Entities.CATEGORY, 2).orElse(null));

			// removing the dangling simple reference must succeed - no reflected maintenance is involved here
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.CATEGORY, 2)
						.upsertVia(session);
				}
			);

			final SealedEntity archivedProductAfter = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedProductAfter);
			assertNull(archivedProductAfter.getReference(Entities.CATEGORY, 2).orElse(null));
		}

		/**
		 * Builds the plain-reference fixture: the {@link #createSchemaForEntityArchiving(Scope...)} schema (product owns
		 * a plain reference to a category), categories 1 and 2, and product 100 referencing category 2.
		 *
		 * @param indexScope the scopes in which the schema elements are indexed
		 */
		private void createSimpleReferenceFixture(@Nonnull Scope... indexScope) {
			createSchemaForEntityArchiving(indexScope);
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.CATEGORY, 2)
						.setAttribute(ATTRIBUTE_CODE, "TV")
						.upsertVia(session);
				}
			);
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.PRODUCT, 100)
						.setAttribute(ATTRIBUTE_CODE, "TV-123")
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "TV")
						.setReference(Entities.CATEGORY, 2, whichIs -> whichIs.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "EU").setAttribute(ATTRIBUTE_CATEGORY_OPEN, true))
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), true)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_EUR, new BigDecimal("10"), new BigDecimal("21"), new BigDecimal("12.1"), true)
						.upsertVia(session);
				}
			);
		}
	}

	/**
	 * Missing E2E coverage for archiving/removal behaviour of reflected references across the LIVE and ARCHIVED scopes.
	 * The holder (product) owns the primary reference to the target (category); the target carries the reflected mirror
	 * back to the holder. These scenarios exercise the full lifecycle matrix across the three legal indexing
	 * configurations (primary+reflected LIVE-only, primary+reflected both-scopes, primary both-scopes / reflected
	 * LIVE-only).
	 */
	@SuppressWarnings("SameParameterValue")
	@Nested
	@DisplayName("Reflected reference behaviour across scopes")
	class ReflectedReferenceArchivingScenarios {

		@DisplayName("Removing a primary reference from a live holder removes the reflected mirror on a live target (both scopes indexed) - LIVE control")
		@Test
		void shouldRemoveReflectedMirrorFromLiveTargetWhenPrimaryRemovedFromLiveHolder() {
			defineBothScopesReflectedSchema();
			createArchivingFixtureEntities();

			// holder stays LIVE; the reflected mirror is present in the LIVE scope
			assertCategoryContainsProduct(new EntityReference(Entities.CATEGORY, 2), 100, Scope.LIVE);

			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(Entities.PRODUCT, 100, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.CATEGORY, 2)
						.upsertVia(session);
				}
			);

			// EXACT: the reflected mirror on the live target must be gone
			assertCategoryDoesNotContainProduct(100, Scope.LIVE);

			final SealedEntity liveProduct = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(liveProduct);
			assertNull(liveProduct.getReference(Entities.CATEGORY, 2).orElse(null));
		}

		@DisplayName("Deleting an archived holder removes the reflected mirror on a live target (both scopes indexed)")
		@Test
		void shouldRemoveReflectedMirrorFromLiveTargetWhenArchivedHolderDeleted() {
			defineBothScopesReflectedSchema();
			createArchivingFixtureEntities();

			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);
			assertCategoryContainsProduct(new EntityReference(Entities.CATEGORY, 2), 100, Scope.LIVE, Scope.ARCHIVED);

			// delete the archived holder entirely
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(session.deleteEntity(Entities.PRODUCT, 100));
				}
			);

			// EXACT: the reflected mirror on the live target must be gone in every scope
			assertCategoryDoesNotContainProduct(100, Scope.LIVE);
			assertCategoryDoesNotContainProduct(100, Scope.ARCHIVED);
			// the target category itself is untouched
			final SealedEntity category = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(category);
		}

		@DisplayName("Deleting a live holder removes the reflected mirror on a live target (both scopes indexed) - LIVE control")
		@Test
		void shouldRemoveReflectedMirrorFromLiveTargetWhenLiveHolderDeleted() {
			defineBothScopesReflectedSchema();
			createArchivingFixtureEntities();

			assertCategoryContainsProduct(new EntityReference(Entities.CATEGORY, 2), 100, Scope.LIVE);

			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(session.deleteEntity(Entities.PRODUCT, 100));
				}
			);

			assertCategoryDoesNotContainProduct(100, Scope.LIVE);
			final SealedEntity category = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(category);
		}

		@DisplayName("Archiving a holder drops the LIVE-only reflected mirror while keeping the primary reference indexed in the archived scope (mixed scopes)")
		@Test
		void shouldDropReflectedMirrorButRetainIndexedPrimaryWhenHolderArchivedWithMixedScopes() {
			defineMixedScopeReflectedSchema();
			createArchivingFixtureEntities();

			// before archiving: mirror present in LIVE and the holder is queryable by its primary reference in LIVE
			assertCategoryContainsProduct(new EntityReference(Entities.CATEGORY, 2), 100, Scope.LIVE);
			assertProductContainsCategory(new EntityReference(Entities.PRODUCT, 100), 2, Scope.LIVE);

			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);

			// EXACT: the LIVE-only reflected mirror is dropped in the LIVE scope (it is not indexed in ARCHIVED, so the
			// reflected reference cannot be queried there at all)
			assertCategoryDoesNotContainProduct(100, Scope.LIVE);
			// EXACT: the primary reference is indexed in ARCHIVED, so the archived holder is still queryable by it
			assertProductContainsCategory(new EntityReference(Entities.PRODUCT, 100), 2, Scope.ARCHIVED);
		}

		@DisplayName("Removing a dangling primary reference from an archived holder whose target was removed must not fail (mixed scopes)")
		@Test
		void shouldRemoveDanglingPrimaryReferenceOnArchivedHolderWithMixedScopesWhenTargetRemoved() {
			defineMixedScopeReflectedSchema();
			createArchivingFixtureEntities();

			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(session.deleteEntity(Entities.CATEGORY, 2));
				}
			);

			// the archived holder still carries the now-dangling primary reference
			final SealedEntity archivedProduct = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedProduct);
			assertNotNull(archivedProduct.getReference(Entities.CATEGORY, 2).orElse(null));

			// removing the dangling primary reference must succeed
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.CATEGORY, 2)
						.upsertVia(session);
				}
			);

			final SealedEntity archivedProductAfter = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedProductAfter);
			assertNull(archivedProductAfter.getReference(Entities.CATEGORY, 2).orElse(null));
		}

		@DisplayName("Archiving the reflected target while the holder stays live must keep both entities consistent and the primary reference removable")
		@Test
		void shouldArchiveReflectedTargetWhileHolderStaysLive() {
			defineBothScopesReflectedSchema();
			createArchivingFixtureEntities();

			// archive the TARGET (the reflected-mirror holder) while the primary holder stays LIVE
			assertDoesNotThrow(
				() -> EvitaArchivingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.archiveEntity(Entities.CATEGORY, 2);
					}
				),
				"Archiving the reflected target must not fail"
			);

			// INVARIANT: both entities remain retrievable in their respective scopes
			final SealedEntity liveHolder = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(liveHolder);
			final SealedEntity archivedTarget = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.CATEGORY, 2, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedTarget);

			// INVARIANT: whatever the mirror state, the holder's primary reference must remain removable without error
			assertDoesNotThrow(
				() -> EvitaArchivingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntity(Entities.PRODUCT, 100, entityFetchAllContent())
							.orElseThrow()
							.openForWrite()
							.removeReference(Entities.CATEGORY, 2)
							.upsertVia(session);
					}
				),
				"Removing the primary reference after the reflected target was archived must not fail"
			);
		}

		@DisplayName("Removing a dangling primary reference from an archived holder whose target was removed from the archived scope must not fail")
		@Test
		void shouldRemoveDanglingPrimaryReferenceWhenTargetRemovedFromArchivedScope() {
			defineBothScopesReflectedSchema();
			createArchivingFixtureEntities();

			// archive BOTH the holder and the target; with both-scopes indexing the mirror lives in the archived scope
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
					session.archiveEntity(Entities.CATEGORY, 2);
				}
			);
			assertCategoryContainsProduct(new EntityReference(Entities.CATEGORY, 2), 100, Scope.ARCHIVED);

			// remove the target from the ARCHIVED scope
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(session.deleteEntity(Entities.CATEGORY, 2));
				}
			);

			// the archived holder must stay consistent: whether the reference dangles or was auto-cleaned by the
			// reflected maintenance, the holder must remain retrievable and must not keep a reference that cannot be removed
			final SealedEntity archivedProduct = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedProduct);
			if (archivedProduct.getReference(Entities.CATEGORY, 2).isPresent()) {
				EvitaArchivingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent())
							.orElseThrow()
							.openForWrite()
							.removeReference(Entities.CATEGORY, 2)
							.upsertVia(session);
					}
				);
			}

			final SealedEntity archivedProductAfter = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedProductAfter);
			assertNull(archivedProductAfter.getReference(Entities.CATEGORY, 2).orElse(null));
		}

		@DisplayName("Restoring the holder to live while the reflected target stays archived must keep both entities consistent")
		@Test
		void shouldRestoreHolderToLiveWhileReflectedTargetStaysArchived() {
			defineBothScopesReflectedSchema();
			createArchivingFixtureEntities();

			// archive both holder and target
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
					session.archiveEntity(Entities.CATEGORY, 2);
				}
			);
			assertCategoryContainsProduct(new EntityReference(Entities.CATEGORY, 2), 100, Scope.ARCHIVED);

			// restore ONLY the holder to LIVE; the target remains ARCHIVED
			assertDoesNotThrow(
				() -> EvitaArchivingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.restoreEntity(Entities.PRODUCT, 100);
					}
				),
				"Restoring the holder while its reflected target stays archived must not fail"
			);

			// INVARIANT: holder retrievable in LIVE, target retrievable in ARCHIVED
			final SealedEntity liveHolder = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(liveHolder);
			final SealedEntity archivedTarget = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.CATEGORY, 2, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedTarget);

			// INVARIANT: the holder's primary reference must remain removable without error
			assertDoesNotThrow(
				() -> EvitaArchivingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntity(Entities.PRODUCT, 100, entityFetchAllContent())
							.orElseThrow()
							.openForWrite()
							.removeReference(Entities.CATEGORY, 2)
							.upsertVia(session);
					}
				),
				"Removing the primary reference after asymmetric restore must not fail"
			);
		}

		@DisplayName("Restoring a holder recreates the reflected mirror only on the surviving target, skipping the removed one")
		@Test
		void shouldRecreateReflectedMirrorOnlyForSurvivingTargetWhenHolderRestored() {
			defineLiveOnlyReflectedSchema();
			createTwoTargetArchivingFixture();

			// both targets carry the reflected mirror while the holder is live
			assertReflectedMirrorOnCategory(2, true);
			assertReflectedMirrorOnCategory(3, true);

			// archive the holder -> LIVE-only mirrors are dropped
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);
			assertCategoryDoesNotContainProduct(100, Scope.LIVE);

			// remove ONE target (category 3) while the holder is archived
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(session.deleteEntity(Entities.CATEGORY, 3));
				}
			);

			// restore the holder to LIVE
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.restoreEntity(Entities.PRODUCT, 100);
				}
			);

			// EXACT: exactly the surviving target (category 2) mirrors the product again; the removed one does not
			final List<Integer> categoriesReferencingProduct = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.queryList(
						Query.query(
							collection(Entities.CATEGORY),
							filterBy(
								referenceHaving(REFLECTED_REFERENCE_NAME, entityPrimaryKeyInSet(100)),
								scope(Scope.LIVE)
							)
						),
						EntityReference.class
					).stream().map(EntityReference::getPrimaryKey).toList();
				}
			);
			assertEquals(List.of(2), categoriesReferencingProduct);
		}

		@DisplayName("Removing a dangling sibling reference from an archived holder keeps the surviving sibling and its reflected mirror intact")
		@Test
		void shouldRemoveOnlyDanglingSiblingReferenceAndKeepLiveSiblingOnArchivedHolder() {
			defineBothScopesReflectedSchema();
			createTwoTargetArchivingFixture();

			// archive the holder -> both mirrors retained across scopes
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);
			// the mirrors live on the (still LIVE) categories - they are retained across the scope span; both
			// categories mirror the product, so their presence is asserted on the entity bodies directly
			assertReflectedMirrorOnCategory(2, true);
			assertReflectedMirrorOnCategory(3, true);

			// remove ONE target (category 3); with both-scopes indexing the relation is maintained across the scope
			// split, so the removal may propagate to the holder's primary reference automatically
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(session.deleteEntity(Entities.CATEGORY, 3));
				}
			);

			// if the sibling reference (category 3) still dangles on the archived holder, remove it explicitly
			final SealedEntity archivedProductBefore = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedProductBefore);
			if (archivedProductBefore.getReference(Entities.CATEGORY, 3).isPresent()) {
				EvitaArchivingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent())
							.orElseThrow()
							.openForWrite()
							.removeReference(Entities.CATEGORY, 3)
							.upsertVia(session);
					}
				);
			}

			// EXACT (adversarial for the existence-filter fix): the surviving sibling and its mirror must stay intact
			final SealedEntity archivedProduct = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedProduct);
			assertNotNull(archivedProduct.getReference(Entities.CATEGORY, 2).orElse(null));
			assertNull(archivedProduct.getReference(Entities.CATEGORY, 3).orElse(null));
			// after removing the dangling sibling only category 2 mirrors the product - now queryOne is unambiguous
			assertCategoryContainsProduct(new EntityReference(Entities.CATEGORY, 2), 100, Scope.LIVE, Scope.ARCHIVED);
		}

		@DisplayName("Updating an inherited reference attribute on an archived holder propagates to the reflected mirror on a live target")
		@Test
		void shouldPropagateInheritedAttributeToReflectedMirrorWhenArchivedHolderReferenceUpdated() {
			defineBothScopesReflectedSchema();
			createArchivingFixtureEntities();

			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);
			// the mirror initially inherits market = "EU"
			assertReflectedMirrorMarket("EU");

			// update the inherited attribute on the archived holder's primary reference
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(Entities.CATEGORY, 2, whichIs -> whichIs.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "US").setAttribute(ATTRIBUTE_CATEGORY_OPEN, true))
						.upsertVia(session);
				}
			);

			// the primary-side write must have landed on the archived holder itself (isolates "write dropped" from
			// "mirror propagation skipped")
			final SealedEntity archivedHolder = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedHolder);
			assertEquals("US", archivedHolder.getReference(Entities.CATEGORY, 2).orElseThrow().getAttribute(ATTRIBUTE_CATEGORY_MARKET));

			// EXACT: the reflected mirror on the live target reflects the updated inherited attribute
			assertReflectedMirrorMarket("US");
		}

		@DisplayName("Updating an inherited reference attribute on a live holder propagates to the reflected mirror - LIVE control")
		@Test
		void shouldPropagateInheritedAttributeToReflectedMirrorWhenLiveHolderReferenceUpdated() {
			defineBothScopesReflectedSchema();
			createArchivingFixtureEntities();

			assertReflectedMirrorMarket("EU");

			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(Entities.PRODUCT, 100, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(Entities.CATEGORY, 2, whichIs -> whichIs.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "US").setAttribute(ATTRIBUTE_CATEGORY_OPEN, true))
						.upsertVia(session);
				}
			);

			assertReflectedMirrorMarket("US");
		}

		@DisplayName("Removing a dangling primary reference from an archived holder must not fail when the target was removed before archiving")
		@Test
		void shouldRemoveDanglingPrimaryReferenceWhenTargetRemovedBeforeHolderArchived() {
			defineLiveOnlyReflectedSchema();
			createArchivingFixtureEntities();

			// remove the target FIRST while the holder is live (reverse of the usual archive-then-remove order)
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(session.deleteEntity(Entities.CATEGORY, 2));
				}
			);

			// archive the holder (its reference to the removed target may still dangle)
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);

			// INVARIANT: the archived holder must not end up with a reference that cannot be removed
			final SealedEntity archivedProduct = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedProduct);
			if (archivedProduct.getReference(Entities.CATEGORY, 2).isPresent()) {
				assertDoesNotThrow(
					() -> EvitaArchivingTest.this.evita.updateCatalog(
						TEST_CATALOG,
						session -> {
							session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent())
								.orElseThrow()
								.openForWrite()
								.removeReference(Entities.CATEGORY, 2)
								.upsertVia(session);
						}
					),
					"Removing the dangling reference after reverse-order removal must not fail"
				);
			}
			final SealedEntity archivedProductAfter = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedProductAfter);
			assertNull(archivedProductAfter.getReference(Entities.CATEGORY, 2).orElse(null));
		}

		@DisplayName("Adding a primary reference to an archived holder creates the reflected mirror on the target")
		@Test
		void shouldCreateReflectedMirrorWhenPrimaryReferenceAddedToArchivedHolder() {
			defineBothScopesReflectedSchema();
			createArchivingFixtureEntities();

			// add a second live target the holder does not yet reference
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.CATEGORY, 3)
						.setAttribute(ATTRIBUTE_CODE, "Radio")
						.upsertVia(session);
				}
			);

			// archive the holder
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);

			// before: category 3 carries no reflected mirror to the holder
			final SealedEntity categoryBefore = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.CATEGORY, 3, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(categoryBefore);
			assertNull(categoryBefore.getReference(REFLECTED_REFERENCE_NAME, 100).orElse(null));

			// add a primary reference to category 3 on the archived holder
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(Entities.CATEGORY, 3, whichIs -> whichIs.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "US").setAttribute(ATTRIBUTE_CATEGORY_OPEN, true))
						.upsertVia(session);
				}
			);

			// the primary-side write must have landed on the archived holder itself (isolates "write dropped" from
			// "mirror not created")
			final SealedEntity archivedHolder = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedHolder);
			assertNotNull(archivedHolder.getReference(Entities.CATEGORY, 3).orElse(null));

			// EXACT: the reflected mirror on category 3 is created
			final SealedEntity categoryAfter = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.CATEGORY, 3, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(categoryAfter);
			assertNotNull(categoryAfter.getReference(REFLECTED_REFERENCE_NAME, 100).orElse(null));
		}

		/**
		 * Asserts that the reflected mirror on category 2 pointing back to product 100 inherits the given market
		 * attribute value.
		 *
		 * @param expectedMarket the expected inherited market attribute value on the reflected mirror
		 */
		private void assertReflectedMirrorMarket(@Nonnull String expectedMarket) {
			final SealedEntity category = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(category);
			final ReferenceContract mirror = category.getReference(REFLECTED_REFERENCE_NAME, 100).orElse(null);
			assertNotNull(mirror);
			assertEquals(expectedMarket, mirror.getAttribute(ATTRIBUTE_CATEGORY_MARKET));
		}

		/**
		 * Asserts the presence or absence of the reflected mirror to product 100 on the given category, checking the
		 * category entity body directly (works even when several categories mirror the same product, where a
		 * reference-having query would match more than one record).
		 *
		 * @param categoryPk      the primary key of the category to inspect
		 * @param expectedPresent whether the reflected mirror to product 100 is expected on the category
		 */
		private void assertReflectedMirrorOnCategory(int categoryPk, boolean expectedPresent) {
			final SealedEntity category = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.CATEGORY, categoryPk, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(category);
			if (expectedPresent) {
				assertNotNull(category.getReference(REFLECTED_REFERENCE_NAME, 100).orElse(null));
			} else {
				assertNull(category.getReference(REFLECTED_REFERENCE_NAME, 100).orElse(null));
			}
		}

		@DisplayName("Removing a primary reference from an archived holder removes the reflected mirror on a live target (both scopes indexed)")
		@Test
		void shouldRemoveReflectedMirrorFromLiveTargetWhenPrimaryRemovedFromArchivedHolder() {
			defineBothScopesReflectedSchema();
			createArchivingFixtureEntities();

			// archive the holder: with both-scopes indexing the reflected mirror is retained across scopes
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);
			// the reflected mirror on the (still live) category 2 must be present across scopes
			assertCategoryContainsProduct(new EntityReference(Entities.CATEGORY, 2), 100, Scope.LIVE, Scope.ARCHIVED);

			// remove the primary reference from the archived holder while the target is still alive
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.CATEGORY, 2)
						.upsertVia(session);
				}
			);

			// EXACT: the reflected mirror on the live target must be gone in every scope
			assertCategoryDoesNotContainProduct(100, Scope.LIVE);
			assertCategoryDoesNotContainProduct(100, Scope.ARCHIVED);

			// and the archived holder no longer carries the reference
			final SealedEntity archivedProduct = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedProduct);
			assertNull(archivedProduct.getReference(Entities.CATEGORY, 2).orElse(null));
		}

		/**
		 * Asserts the presence or absence of the reflected mirror to product 100 on the given category fetched from the
		 * given scope, reading the category entity body directly. Unlike {@link #assertReflectedMirrorOnCategory(int,
		 * boolean)} this variant works when the category itself was archived and therefore cannot be reached in the
		 * default {@link Scope#LIVE} scope.
		 *
		 * @param categoryPk      the primary key of the category to inspect
		 * @param scope           the scope from which the category is fetched
		 * @param expectedPresent whether the reflected mirror to product 100 is expected on the category
		 */
		private void assertReflectedMirrorOnCategoryInScope(int categoryPk, @Nonnull Scope scope, boolean expectedPresent) {
			final SealedEntity category = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.CATEGORY, categoryPk, new Scope[]{scope}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(category);
			if (expectedPresent) {
				assertNotNull(category.getReference(REFLECTED_REFERENCE_NAME, 100).orElse(null));
			} else {
				assertNull(category.getReference(REFLECTED_REFERENCE_NAME, 100).orElse(null));
			}
		}

		/**
		 * Asserts that the reflected mirror to product 100 on the given category fetched from the given scope inherits
		 * the expected market attribute value. Complements {@link #assertReflectedMirrorMarket(String)} for cases where
		 * the category was archived and therefore is not reachable in the default {@link Scope#LIVE} scope.
		 *
		 * @param categoryPk     the primary key of the category to inspect
		 * @param scope          the scope from which the category is fetched
		 * @param expectedMarket the expected inherited market attribute value on the reflected mirror
		 */
		private void assertReflectedMirrorMarketInScope(int categoryPk, @Nonnull Scope scope, @Nonnull String expectedMarket) {
			final SealedEntity category = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.CATEGORY, categoryPk, new Scope[]{scope}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(category);
			final ReferenceContract mirror = category.getReference(REFLECTED_REFERENCE_NAME, 100).orElse(null);
			assertNotNull(mirror);
			assertEquals(expectedMarket, mirror.getAttribute(ATTRIBUTE_CATEGORY_MARKET));
		}

		@DisplayName("Restoring a mixed-scope holder to live recreates the reflected mirror discarded on archiving")
		@Test
		void shouldRecreateReflectedMirrorWhenMixedScopeHolderRestoredToLive() {
			defineMixedScopeReflectedSchema();
			createArchivingFixtureEntities();

			// before archiving: the LIVE-only mirror is present on the live target
			assertReflectedMirrorOnCategory(2, true);

			// archive the holder -> the LIVE-only mirror is discarded
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);
			assertReflectedMirrorOnCategory(2, false);

			// restore the holder back to LIVE -> both ends are LIVE again, so the mirror must be recreated
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.restoreEntity(Entities.PRODUCT, 100);
				}
			);

			// EXACT: the reflected mirror is recreated on the target with its inherited attribute intact
			assertReflectedMirrorOnCategory(2, true);
			assertReflectedMirrorMarket("EU");
		}

		@DisplayName("Archiving a mixed-scope reflected target while the holder stays live discards the mirror yet keeps the primary reference queryable")
		@Test
		void shouldArchiveReflectedTargetWithMixedScopesWhileHolderStaysLive() {
			defineMixedScopeReflectedSchema();
			createArchivingFixtureEntities();

			// before archiving: the LIVE-only mirror is present on the still-live target
			assertReflectedMirrorOnCategory(2, true);

			// archive the TARGET while the holder stays LIVE -> the relation spans two scopes, reflected is LIVE-only
			assertDoesNotThrow(
				() -> EvitaArchivingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.archiveEntity(Entities.CATEGORY, 2);
					}
				),
				"Archiving the mixed-scope reflected target must not fail"
			);

			// INVARIANT: discarding the mirror must never remove the holder's primary reference (spec section 3); the
			// live holder keeps its primary reference on its body regardless of the target moving to the archived scope
			final SealedEntity liveHolder = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(liveHolder);
			assertNotNull(liveHolder.getReference(Entities.CATEGORY, 2).orElse(null));

			// EXACT: the mirror is discarded on the now-archived target
			assertReflectedMirrorOnCategoryInScope(2, Scope.ARCHIVED, false);
		}

		@DisplayName("Archiving a LIVE-only reflected target while the holder stays live discards the mirror without failing")
		@Test
		void shouldArchiveReflectedTargetWithLiveOnlyScopesWhileHolderStaysLive() {
			defineLiveOnlyReflectedSchema();
			createArchivingFixtureEntities();

			// before archiving: the LIVE-only mirror is present on the still-live target
			assertReflectedMirrorOnCategory(2, true);

			// archive the TARGET while the holder stays LIVE -> the relation spans two scopes, reflected is LIVE-only
			assertDoesNotThrow(
				() -> EvitaArchivingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.archiveEntity(Entities.CATEGORY, 2);
					}
				),
				"Archiving the LIVE-only reflected target must not fail"
			);

			// INVARIANT: the live holder remains retrievable and keeps its primary reference (LIVE index)
			final SealedEntity liveHolder = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(liveHolder);
			assertNotNull(liveHolder.getReference(Entities.CATEGORY, 2).orElse(null));

			// EXACT: the mirror is discarded on the now-archived target
			assertReflectedMirrorOnCategoryInScope(2, Scope.ARCHIVED, false);
		}

		@DisplayName("Adding a primary reference to an archived mixed-scope holder lands the write but creates no LIVE-only mirror")
		@Test
		void shouldNotCreateReflectedMirrorWhenPrimaryReferenceAddedToArchivedMixedScopeHolder() {
			defineMixedScopeReflectedSchema();
			createArchivingFixtureEntities();

			// add a second live target the holder does not yet reference
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.CATEGORY, 3)
						.setAttribute(ATTRIBUTE_CODE, "Radio")
						.upsertVia(session);
				}
			);

			// archive the holder
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);

			// add a primary reference to the live category 3 on the archived holder
			assertDoesNotThrow(
				() -> EvitaArchivingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent())
							.orElseThrow()
							.openForWrite()
							.setReference(Entities.CATEGORY, 3, whichIs -> whichIs.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "US").setAttribute(ATTRIBUTE_CATEGORY_OPEN, true))
							.upsertVia(session);
					}
				),
				"Adding a primary reference to the archived mixed-scope holder must not fail"
			);

			// EXACT: the primary-side write lands on the archived holder and is queryable in the ARCHIVED scope
			final SealedEntity archivedHolder = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedHolder);
			assertNotNull(archivedHolder.getReference(Entities.CATEGORY, 3).orElse(null));

			// EXACT: the reflected reference is LIVE-only, so no mirror is created on the live target
			assertReflectedMirrorOnCategory(3, false);

			// EXACT: the primary schema is indexed in ARCHIVED, so the newly added primary reference is queryable there
			assertProductContainsCategory(new EntityReference(Entities.PRODUCT, 100), 3, Scope.ARCHIVED);
		}

		@DisplayName("Deleting an archived mixed-scope holder while its reflected target is alive must not fail")
		@Test
		void shouldDeleteArchivedMixedScopeHolderWhenReflectedTargetAlive() {
			defineMixedScopeReflectedSchema();
			createArchivingFixtureEntities();

			// archive the holder while the target stays LIVE
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);

			// delete the archived holder entirely
			assertDoesNotThrow(
				() -> EvitaArchivingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						assertTrue(session.deleteEntity(Entities.PRODUCT, 100));
					}
				),
				"Deleting the archived mixed-scope holder must not fail"
			);

			// EXACT: the holder is gone from both scopes
			final SealedEntity liveHolder = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, entityFetchAllContent()).orElse(null);
				}
			);
			assertNull(liveHolder);
			final SealedEntity archivedHolder = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNull(archivedHolder);

			// EXACT: the target category is untouched
			final SealedEntity category = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(category);
		}

		@DisplayName("Removing a dangling sibling reference from an archived mixed-scope holder keeps the surviving primary reference intact")
		@Test
		void shouldRemoveOnlyDanglingSiblingReferenceWithMixedScopesOnArchivedHolder() {
			defineMixedScopeReflectedSchema();
			createTwoTargetArchivingFixture();

			// archive the holder -> LIVE-only mirrors are discarded but the primary references remain in the archived scope
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);
			// the archived holder retains both primary references on its body; the references are inspected on the entity
			// body directly to isolate sibling handling from the archived-scope primary reference index
			final SealedEntity archivedHolderBefore = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedHolderBefore);
			assertNotNull(archivedHolderBefore.getReference(Entities.CATEGORY, 2).orElse(null));
			assertNotNull(archivedHolderBefore.getReference(Entities.CATEGORY, 3).orElse(null));

			// remove ONE target (category 3) -> its reference on the archived holder becomes dangling
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(session.deleteEntity(Entities.CATEGORY, 3));
				}
			);

			// remove the dangling sibling reference (category 3) from the archived holder
			assertDoesNotThrow(
				() -> EvitaArchivingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent())
							.orElseThrow()
							.openForWrite()
							.removeReference(Entities.CATEGORY, 3)
							.upsertVia(session);
					}
				),
				"Removing the dangling sibling reference on the archived mixed-scope holder must not fail"
			);

			// EXACT: the surviving sibling reference stays intact, only the dangling one is gone
			final SealedEntity archivedProduct = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedProduct);
			assertNotNull(archivedProduct.getReference(Entities.CATEGORY, 2).orElse(null));
			assertNull(archivedProduct.getReference(Entities.CATEGORY, 3).orElse(null));
		}

		@DisplayName("Updating an inherited attribute while both holder and target are archived propagates to the mirror (both scopes indexed)")
		@Test
		void shouldPropagateInheritedAttributeToReflectedMirrorWhenBothHolderAndTargetArchived() {
			defineBothScopesReflectedSchema();
			createArchivingFixtureEntities();

			// archive BOTH the holder and the target; with both-scopes indexing the mirror lives in the archived scope
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
					session.archiveEntity(Entities.CATEGORY, 2);
				}
			);
			// the mirror on the archived target initially inherits market = "EU"
			assertReflectedMirrorMarketInScope(2, Scope.ARCHIVED, "EU");

			// update the inherited attribute on the archived holder's primary reference
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(Entities.CATEGORY, 2, whichIs -> whichIs.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "US").setAttribute(ATTRIBUTE_CATEGORY_OPEN, true))
						.upsertVia(session);
				}
			);

			// the primary-side write must have landed on the archived holder itself
			final SealedEntity archivedHolder = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedHolder);
			assertEquals("US", archivedHolder.getReference(Entities.CATEGORY, 2).orElseThrow().getAttribute(ATTRIBUTE_CATEGORY_MARKET));

			// EXACT: the reflected mirror on the archived target reflects the updated inherited attribute
			assertReflectedMirrorMarketInScope(2, Scope.ARCHIVED, "US");
		}

		@DisplayName("Adding a primary reference while both holder and target are archived creates the mirror (both scopes indexed)")
		@Test
		void shouldCreateReflectedMirrorWhenPrimaryReferenceAddedToBothArchivedHolderAndTarget() {
			defineBothScopesReflectedSchema();
			createArchivingFixtureEntities();

			// add a second target the holder does not yet reference
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.CATEGORY, 3)
						.setAttribute(ATTRIBUTE_CODE, "Radio")
						.upsertVia(session);
				}
			);

			// archive BOTH the holder and the new target
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
					session.archiveEntity(Entities.CATEGORY, 3);
				}
			);

			// before: the archived category 3 carries no reflected mirror to the holder
			assertReflectedMirrorOnCategoryInScope(3, Scope.ARCHIVED, false);

			// add a primary reference to the archived category 3 on the archived holder
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(Entities.CATEGORY, 3, whichIs -> whichIs.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "US").setAttribute(ATTRIBUTE_CATEGORY_OPEN, true))
						.upsertVia(session);
				}
			);

			// the primary-side write must have landed on the archived holder itself
			final SealedEntity archivedHolder = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedHolder);
			assertNotNull(archivedHolder.getReference(Entities.CATEGORY, 3).orElse(null));

			// EXACT: the reflected mirror is created on the archived target (both schemas indexed in ARCHIVED)
			assertReflectedMirrorOnCategoryInScope(3, Scope.ARCHIVED, true);
		}

		@DisplayName("Adding a primary reference to an archived LIVE-only holder lands the write but creates no mirror")
		@Test
		void shouldNotCreateReflectedMirrorWhenPrimaryReferenceAddedToArchivedLiveOnlyHolder() {
			defineLiveOnlyReflectedSchema();
			createArchivingFixtureEntities();

			// archive the holder
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);

			// add a fresh live target the holder does not yet reference
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.CATEGORY, 3)
						.setAttribute(ATTRIBUTE_CODE, "Radio")
						.upsertVia(session);
				}
			);

			// add a primary reference to the live category 3 on the archived holder
			assertDoesNotThrow(
				() -> EvitaArchivingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent())
							.orElseThrow()
							.openForWrite()
							.setReference(Entities.CATEGORY, 3, whichIs -> whichIs.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "US").setAttribute(ATTRIBUTE_CATEGORY_OPEN, true))
							.upsertVia(session);
					}
				),
				"Adding a primary reference to the archived LIVE-only holder must not fail"
			);

			// EXACT: the primary-side write lands on the archived holder body (the primary is LIVE-only, so it is not
			// queryable in the ARCHIVED scope, but the reference is stored on the entity)
			final SealedEntity archivedHolder = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedHolder);
			assertNotNull(archivedHolder.getReference(Entities.CATEGORY, 3).orElse(null));

			// EXACT: the ends are not mutually visible, so no mirror is created on the live target
			assertReflectedMirrorOnCategory(3, false);
		}

		@DisplayName("Restoring a LIVE-only reflected target while the holder stays live recreates the discarded mirror")
		@Test
		void shouldRecreateReflectedMirrorWhenLiveOnlyTargetRestoredWhileHolderStaysLive() {
			defineLiveOnlyReflectedSchema();
			createArchivingFixtureEntities();

			// before archiving: the LIVE-only mirror is present on the live target
			assertReflectedMirrorOnCategory(2, true);

			// archive the TARGET while the holder stays LIVE -> the mirror is discarded
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.CATEGORY, 2);
				}
			);
			assertReflectedMirrorOnCategoryInScope(2, Scope.ARCHIVED, false);

			// restore the TARGET back to LIVE -> both ends are LIVE again, so the mirror must be recreated
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.restoreEntity(Entities.CATEGORY, 2);
				}
			);

			// EXACT: the reflected mirror is recreated on the restored target with its inherited attribute intact
			assertReflectedMirrorOnCategory(2, true);
			assertReflectedMirrorMarket("EU");
		}

		@DisplayName("Updating an inherited attribute on an archived mixed-scope holder lands the primary write without a LIVE-only mirror")
		@Test
		void shouldLandPrimaryWriteWithoutMirrorWhenArchivedMixedScopeHolderAttributeUpdated() {
			defineMixedScopeReflectedSchema();
			createArchivingFixtureEntities();

			// archive the holder -> the LIVE-only mirror is discarded, the primary reference stays in the archived scope
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);
			assertReflectedMirrorOnCategory(2, false);

			// update the inherited attribute on the archived holder's primary reference
			assertDoesNotThrow(
				() -> EvitaArchivingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent())
							.orElseThrow()
							.openForWrite()
							.setReference(Entities.CATEGORY, 2, whichIs -> whichIs.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "US").setAttribute(ATTRIBUTE_CATEGORY_OPEN, true))
							.upsertVia(session);
					}
				),
				"Updating the inherited attribute on the archived mixed-scope holder must not fail"
			);

			// EXACT: the primary-side write lands and is queryable in the ARCHIVED scope
			final SealedEntity archivedHolder = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedHolder);
			assertEquals("US", archivedHolder.getReference(Entities.CATEGORY, 2).orElseThrow().getAttribute(ATTRIBUTE_CATEGORY_MARKET));

			// EXACT: the reflected reference is LIVE-only and the mirror was discarded, so none exists on the live target
			assertReflectedMirrorOnCategory(2, false);
		}

		@DisplayName("Updating an inherited attribute on a live holder propagates to the archived target's mirror (both scopes indexed)")
		@Test
		void shouldPropagateInheritedAttributeToArchivedTargetWhenLiveHolderReferenceUpdated() {
			defineBothScopesReflectedSchema();
			createArchivingFixtureEntities();

			// archive ONLY the target; with both-scopes indexing the mirror is retained cross-scope on the archived target
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.CATEGORY, 2);
				}
			);
			assertReflectedMirrorMarketInScope(2, Scope.ARCHIVED, "EU");

			// update the inherited attribute on the live holder's primary reference
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(Entities.PRODUCT, 100, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(Entities.CATEGORY, 2, whichIs -> whichIs.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "US").setAttribute(ATTRIBUTE_CATEGORY_OPEN, true))
						.upsertVia(session);
				}
			);

			// EXACT: the cross-scope mirror on the archived target reflects the updated inherited attribute
			assertReflectedMirrorMarketInScope(2, Scope.ARCHIVED, "US");
		}

		@DisplayName("Removing a primary reference from an archived LIVE-only holder whose target is alive must not fail")
		@Test
		void shouldRemovePrimaryReferenceOnArchivedLiveOnlyHolderWhenTargetAlive() {
			defineLiveOnlyReflectedSchema();
			createArchivingFixtureEntities();

			// archive the holder while the target stays LIVE (the LIVE-only mirror is discarded on archiving)
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);

			// the archived holder still carries the primary reference on its body
			final SealedEntity archivedProduct = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedProduct);
			assertNotNull(archivedProduct.getReference(Entities.CATEGORY, 2).orElse(null));

			// remove the primary reference on the archived holder while the target is alive
			assertDoesNotThrow(
				() -> EvitaArchivingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent())
							.orElseThrow()
							.openForWrite()
							.removeReference(Entities.CATEGORY, 2)
							.upsertVia(session);
					}
				),
				"Removing the primary reference on the archived LIVE-only holder must not fail"
			);

			// EXACT: the reference is gone from the archived holder body; the target category is untouched
			final SealedEntity archivedProductAfter = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedProductAfter);
			assertNull(archivedProductAfter.getReference(Entities.CATEGORY, 2).orElse(null));
			final SealedEntity category = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(category);
		}

		@DisplayName("Removing a primary reference from an archived mixed-scope holder whose target is alive must not fail")
		@Test
		void shouldRemovePrimaryReferenceOnArchivedMixedScopeHolderWhenTargetAlive() {
			defineMixedScopeReflectedSchema();
			createArchivingFixtureEntities();

			// archive the holder while the target stays LIVE
			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.archiveEntity(Entities.PRODUCT, 100);
				}
			);
			// the archived holder retains the primary reference on its body; the reference is inspected on the entity
			// body directly to isolate removal handling from the archived-scope primary reference index
			final SealedEntity archivedProductBefore = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedProductBefore);
			assertNotNull(archivedProductBefore.getReference(Entities.CATEGORY, 2).orElse(null));

			// remove the primary reference on the archived holder while the target is alive
			assertDoesNotThrow(
				() -> EvitaArchivingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent())
							.orElseThrow()
							.openForWrite()
							.removeReference(Entities.CATEGORY, 2)
							.upsertVia(session);
					}
				),
				"Removing the primary reference on the archived mixed-scope holder must not fail"
			);

			// EXACT: the reference is gone from the holder body; the target is untouched
			final SealedEntity archivedProductAfter = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.PRODUCT, 100, new Scope[]{Scope.ARCHIVED}, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(archivedProductAfter);
			assertNull(archivedProductAfter.getReference(Entities.CATEGORY, 2).orElse(null));
			final SealedEntity category = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(category);
		}

		@DisplayName("Removing a primary reference from a live LIVE-only holder removes the reflected mirror on the live target - LIVE control")
		@Test
		void shouldRemoveReflectedMirrorFromLiveTargetWhenPrimaryRemovedFromLiveOnlyHolder() {
			defineLiveOnlyReflectedSchema();
			createArchivingFixtureEntities();

			// holder stays LIVE; the reflected mirror is present in the LIVE scope
			assertCategoryContainsProduct(new EntityReference(Entities.CATEGORY, 2), 100, Scope.LIVE);

			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(Entities.PRODUCT, 100, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.CATEGORY, 2)
						.upsertVia(session);
				}
			);

			// EXACT: the reflected mirror on the live target must be gone
			assertCategoryDoesNotContainProduct(100, Scope.LIVE);
		}

		@DisplayName("Deleting a live LIVE-only holder removes the reflected mirror on the live target - LIVE control")
		@Test
		void shouldRemoveReflectedMirrorFromLiveTargetWhenLiveOnlyHolderDeleted() {
			defineLiveOnlyReflectedSchema();
			createArchivingFixtureEntities();

			assertCategoryContainsProduct(new EntityReference(Entities.CATEGORY, 2), 100, Scope.LIVE);

			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(session.deleteEntity(Entities.PRODUCT, 100));
				}
			);

			assertCategoryDoesNotContainProduct(100, Scope.LIVE);
			final SealedEntity category = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(category);
		}

		@DisplayName("Removing a primary reference from a live mixed-scope holder removes the reflected mirror on the live target - LIVE control")
		@Test
		void shouldRemoveReflectedMirrorFromLiveTargetWhenPrimaryRemovedFromLiveMixedScopeHolder() {
			defineMixedScopeReflectedSchema();
			createArchivingFixtureEntities();

			assertCategoryContainsProduct(new EntityReference(Entities.CATEGORY, 2), 100, Scope.LIVE);

			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(Entities.PRODUCT, 100, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.CATEGORY, 2)
						.upsertVia(session);
				}
			);

			assertCategoryDoesNotContainProduct(100, Scope.LIVE);
		}

		@DisplayName("Deleting a live mixed-scope holder removes the reflected mirror on the live target - LIVE control")
		@Test
		void shouldRemoveReflectedMirrorFromLiveTargetWhenLiveMixedScopeHolderDeleted() {
			defineMixedScopeReflectedSchema();
			createArchivingFixtureEntities();

			assertCategoryContainsProduct(new EntityReference(Entities.CATEGORY, 2), 100, Scope.LIVE);

			EvitaArchivingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(session.deleteEntity(Entities.PRODUCT, 100));
				}
			);

			assertCategoryDoesNotContainProduct(100, Scope.LIVE);
			final SealedEntity category = EvitaArchivingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent()).orElse(null);
				}
			);
			assertNotNull(category);
		}
	}


}
