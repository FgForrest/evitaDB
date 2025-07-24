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
import io.evitadb.api.configuration.StorageOptions;
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
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Catalog;
import io.evitadb.core.Evita;
import io.evitadb.core.exception.AttributeNotFilterableException;
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
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

import static io.evitadb.api.functional.indexing.EvitaIndexingTest.getGlobalIndex;
import static io.evitadb.api.functional.indexing.EvitaIndexingTest.getReferencedEntityIndex;
import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.utils.StringUtils.normalizeLineEndings;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies archiving (changing scope) of the entities.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class EvitaArchivingTest implements EvitaTestSupport {
	private static final String DIR_EVITA_ARCHIVING_TEST = "evitaArchivingTest";
	private static final String DIR_EVITA_ARCHIVING_TEST_EXPORT = "evitaArchivingTest_export";
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
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_ARCHIVING_TEST);
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_ARCHIVING_TEST);
	}

	@DisplayName("Entity should be removed from indexes when archived")
	@Test
	void shouldArchiveEntityAndRemoveFromIndexes() {
		/* create schema for entity archival */
		createSchemaForEntityArchiving(Scope.LIVE);

		// upsert entities product depends on
		createBrandAndCategoryEntities();

		// create product entity
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.createNewEntity(Entities.PRODUCT, 100)
					.setAttribute(ATTRIBUTE_CODE, "TV-123")
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "TV")
					.setReference(Entities.BRAND, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_BRAND_EAN, "123"))
					.setReference(Entities.CATEGORY, 2, whichIs -> whichIs.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "EU").setAttribute(ATTRIBUTE_CATEGORY_OPEN, true))
					.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), true)
					.setPrice(1, PRICE_LIST_BASIC, CURRENCY_EUR, new BigDecimal("10"), new BigDecimal("21"), new BigDecimal("12.1"), true)
					.upsertVia(session);
			}
		);

		// check product entity is in indexes
		checkProductCanBeLookedUpByIndexes();

		// check indexes exist
		final Catalog catalog1 = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(Entities.PRODUCT, 100);
			}
		);

		// check product entity is not in any of indexes
		checkProductCannotBeLookedUpByIndexes(Scope.LIVE);
		checkProductCannotBeLookedUpByIndexes(Scope.ARCHIVED);

		// check archive indexes exist and previous indexes are removed
		final Catalog catalog2 = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.restoreEntity(Entities.PRODUCT, 100);
			}
		);

		// check product entity is in indexes
		checkProductCanBeLookedUpByIndexes();

		// check live indexes exist and previous indexes are removed
		final Catalog catalog3 = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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
		this.evita.close();
		this.evita = new Evita(
			getEvitaConfiguration()
		);

		// check product entity is in indexes
		checkProductCanBeLookedUpByIndexes();

		// check live indexes exist and previous indexes are removed
		final Catalog catalog4 = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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

	@DisplayName("Entity should be moved to archive indexes when archived")
	@Test
	void shouldArchiveEntityAndMoveToArchivedIndexes() {
		/* create schema for entity archival */
		createSchemaForEntityArchiving(Scope.LIVE, Scope.ARCHIVED);

		// upsert entities product depends on
		createBrandAndCategoryEntities();

		// create product entity
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.createNewEntity(Entities.PRODUCT, 100)
					.setAttribute(ATTRIBUTE_CODE, "TV-123")
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "TV")
					.setReference(Entities.BRAND, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_BRAND_EAN, "123"))
					.setReference(Entities.CATEGORY, 2, whichIs -> whichIs.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "EU").setAttribute(ATTRIBUTE_CATEGORY_OPEN, true))
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
		final Catalog catalog1 = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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
		this.evita.updateCatalog(
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
		final Catalog catalog2 = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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
		this.evita.updateCatalog(
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
		final Catalog catalog3 = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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
		this.evita.close();
		this.evita = new Evita(
			getEvitaConfiguration()
		);

		// check product entity is in indexes
		checkProductCanBeLookedUpByIndexes();

		// check live indexes exist and previous indexes are removed
		final Catalog catalog4 = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.goLiveAndClose();
			}
		);

		// check live indexes exist and previous indexes are removed
		final Catalog catalog5 = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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

	@DisplayName("Entity should be moved to archive indexes when archived (in tx mode)")
	@Test
	void shouldArchiveEntityAndMoveToArchivedIndexesInTransactionalMode() {
		/* create schema for entity archival */
		createSchemaForEntityArchiving(Scope.LIVE, Scope.ARCHIVED);

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.goLiveAndClose();
			}
		);

		// upsert entities product depends on
		createBrandAndCategoryEntities();

		// create product entity
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.createNewEntity(Entities.PRODUCT, 100)
					.setAttribute(ATTRIBUTE_CODE, "TV-123")
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "TV")
					.setReference(Entities.BRAND, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_BRAND_EAN, "123"))
					.setReference(Entities.CATEGORY, 2, whichIs -> whichIs.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "EU").setAttribute(ATTRIBUTE_CATEGORY_OPEN, true))
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
		final Catalog catalog1 = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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
		this.evita.updateCatalog(
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
		final Catalog catalog2 = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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
		this.evita.updateCatalog(
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
		final Catalog catalog3 = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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
		this.evita.close();
		this.evita = new Evita(
			getEvitaConfiguration()
		);

		// check product entity is in indexes
		checkProductCanBeLookedUpByIndexes();

		// check live indexes exist and previous indexes are removed
		final Catalog catalog4 = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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

	@DisplayName("Entity could be created in already archived state")
	@Test
	void shouldCreateArchivedEntity() {
		/* create schema for entity archival */
		createSchemaForEntityArchiving(Scope.LIVE, Scope.ARCHIVED);

		// upsert entities product depends on
		createBrandAndCategoryEntities();

		// create product entity
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.createNewEntity(Entities.PRODUCT, 100)
					.setScope(Scope.ARCHIVED)
					.setAttribute(ATTRIBUTE_CODE, "TV-123")
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "TV")
					.setReference(Entities.BRAND, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_BRAND_EAN, "123"))
					.setReference(Entities.CATEGORY, 2, whichIs -> whichIs.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "EU").setAttribute(ATTRIBUTE_CATEGORY_OPEN, true))
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
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.createNewEntity(Entities.PRODUCT, 100)
					.setScope(Scope.ARCHIVED)
					.setAttribute(ATTRIBUTE_CODE, "TV-123")
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "TV")
					.setReference(Entities.BRAND, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_BRAND_EAN, "123"))
					.setReference(Entities.CATEGORY, 2, whichIs -> whichIs.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "EU").setAttribute(ATTRIBUTE_CATEGORY_OPEN, true))
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
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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
		// indexes contain only information about language entity presence
		assertNotNull(getReferencedEntityIndex(productCollection, Scope.ARCHIVED, Entities.CATEGORY, 2));
		assertNotNull(getReferencedEntityIndex(productCollection, Scope.ARCHIVED, Entities.BRAND, 1));
		assertNull(getReferencedEntityIndex(productCollection, Scope.ARCHIVED, Entities.BRAND, 2));
	}

	@DisplayName("Results should be merged from both scopes when querying and fetching contents")
	@Test
	void shouldCombineArchivedAndNonArchiveDataInQueryAndFetch() {
		/* create schema for entity archival */
		createSchemaForEntityArchiving(Scope.LIVE);

		// upsert entities product depends on
		createBrandAndCategoryEntities();

		// create product entity
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.createNewEntity(Entities.PRODUCT, 100)
					.setAttribute(ATTRIBUTE_CODE, "TV-123")
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "TV")
					.setReference(Entities.BRAND, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_BRAND_EAN, "123"))
					.setReference(Entities.CATEGORY, 2, whichIs -> whichIs.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "EU").setAttribute(ATTRIBUTE_CATEGORY_OPEN, true))
					.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), true)
					.setPrice(1, PRICE_LIST_BASIC, CURRENCY_EUR, new BigDecimal("10"), new BigDecimal("21"), new BigDecimal("12.1"), true)
					.upsertVia(session);

				session.createNewEntity(Entities.PRODUCT, 101)
					.setAttribute(ATTRIBUTE_CODE, "TV-456")
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Radio")
					.setReference(Entities.BRAND, 2, whichIs -> whichIs.setAttribute(ATTRIBUTE_BRAND_EAN, "456"))
					.setReference(Entities.CATEGORY, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "US").setAttribute(ATTRIBUTE_CATEGORY_OPEN, true))
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
					referenceContentWithAttributes(Entities.BRAND, filterBy(inScope(Scope.LIVE, entityPrimaryKeyInSet(1, 2))), entityFetchAll()),
					referenceContentWithAttributes(Entities.CATEGORY, filterBy(inScope(Scope.LIVE, entityHaving(attributeInSet(ATTRIBUTE_CODE, "electronics", "TV")))), entityFetchAll()),
					priceContentRespectingFilter()
				)
			)
		);

		// find products with complex query - there are no archived data at the moment
		final List<SealedEntity> liveProducts = this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryList(complexQuery, SealedEntity.class);
			}
		);
		assertArrayEquals(
			new int[] {100, 101},
			liveProducts.stream()
				.mapToInt(SealedEntity::getPrimaryKeyOrThrowException)
				.toArray()
		);

		for (SealedEntity liveProduct : liveProducts) {
			assertEquals(1, liveProduct.getReferences(Entities.BRAND).size());
			assertEquals(1, liveProduct.getReferences(Entities.CATEGORY).size());
			// all bodies are fetched
			for (ReferenceContract reference : liveProduct.getReferences()) {
				assertNotNull(reference.getReferencedEntity());
			}
		}

		// archive product entity and all brands
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(Entities.PRODUCT, 100);
				session.archiveEntity(Entities.BRAND, 1);
				session.archiveEntity(Entities.BRAND, 2);
			}
		);

		// find products with complex query - both live and archived (combination)
		final List<SealedEntity> liveAndArchiveProductsTogether = this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryList(complexQuery, SealedEntity.class);
			}
		);
		assertArrayEquals(
			new int[] {100, 101},
			liveAndArchiveProductsTogether.stream()
				.mapToInt(SealedEntity::getPrimaryKeyOrThrowException)
				.toArray()
		);

		for (SealedEntity liveProduct : liveAndArchiveProductsTogether) {
			assertEquals(1, liveProduct.getReferences(Entities.BRAND).size());
			assertEquals(1, liveProduct.getReferences(Entities.CATEGORY).size());
			// all bodies are fetched
			for (ReferenceContract reference : liveProduct.getReferences()) {
				assertNotNull(reference.getReferencedEntity());
			}
		}

		assertThrows(
			ReferenceNotIndexedException.class,
			() -> this.evita.queryCatalog(
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
			() -> this.evita.queryCatalog(
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
									referenceContentWithAttributes(Entities.BRAND, filterBy(attributeInSet(ATTRIBUTE_BRAND_EAN, "123", "456"))),
									referenceContentWithAttributes(Entities.CATEGORY, filterBy(attributeInSet(ATTRIBUTE_CATEGORY_MARKET, "EU", "US"))),
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

	@DisplayName("Entity reflected references should be removed when entity is archived")
	@Test
	void shouldDropReflectedReferencesOnEntityArchivingAndCreateWhenBeingRestored() {
		/* create schema for entity archival */
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
							.withAttribute(ATTRIBUTE_CATEGORY_OPEN, Boolean.class, whichIs -> whichIs.filterable())
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
		this.evita.updateCatalog(
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

		// check category has reflected reference to product
		final SealedEntity category = this.evita.queryCatalog(
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
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(Entities.PRODUCT, 100);
			}
		);

		// check category has no reflected reference to product
		final SealedEntity categoryAfterArchiving = this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent())
					.orElse(null);
			}
		);
		assertNotNull(categoryAfterArchiving);
		final ReferenceContract productsAfterArchiving = categoryAfterArchiving.getReference(REFLECTED_REFERENCE_NAME, 100).orElse(null);
		assertNull(productsAfterArchiving);

		// restore both category and product entity
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.restoreEntity(Entities.CATEGORY, 2);
				session.restoreEntity(Entities.PRODUCT, 100);
			}
		);

		// check restored category has reflected reference to product again
		final SealedEntity categoryAfterRestore = this.evita.queryCatalog(
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
	}

	@Test
	void shouldFailToSetUpReflectedReferencesIncompatiblyWithMainReference() {
		/* create schema for entity archival */
		this.evita.defineCatalog(TEST_CATALOG)
			.updateViaNewSession(this.evita);

		assertThrows(
			InvalidSchemaMutationException.class,
			() ->
				this.evita.updateCatalog(
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
									.withAttribute(ATTRIBUTE_CATEGORY_OPEN, Boolean.class, whichIs -> whichIs.filterable())
							)
							.updateVia(session);
					}
				)
		);
	}

	@Test
	void shouldFailToSetUpEntityWithReferenceAttributesInIncompatibleScopes() {
		/* create schema for entity archival */
		this.evita.defineCatalog(TEST_CATALOG)
			.updateViaNewSession(this.evita);

		assertThrows(
			InvalidSchemaMutationException.class,
			() -> this.evita.updateCatalog(
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
								.withAttribute(ATTRIBUTE_CATEGORY_MARKET, String.class, whichIs -> whichIs.filterableInScope(Scope.LIVE).sortableInScope(Scope.ARCHIVED))
								.withAttribute(ATTRIBUTE_CATEGORY_OPEN, Boolean.class, whichIs -> whichIs.filterableInScope(Scope.ARCHIVED))
						)
						.updateVia(session);
				}
			)
		);

	}

	@DisplayName("Entity reflected references should remain across scopes")
	@Test
	void shouldRecreateReflectedReferencesInSeparateScopes() {
		/* create schema for entity archival */
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
		this.evita.updateCatalog(
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

		// check category has reflected reference to product
		final SealedEntity category = this.evita.queryCatalog(
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
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(Entities.PRODUCT, 100);
			}
		);

		// check category still has reflected reference to product
		final SealedEntity categoryAfterArchiving = this.evita.queryCatalog(
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
		// but not in each scope separately
		assertCategoryDoesNotContainProduct(100, Scope.LIVE);
		assertCategoryDoesNotContainProduct(100, Scope.ARCHIVED);
		assertProductDoesNotContainCategory(2, Scope.LIVE);
		assertProductDoesNotContainCategory(2, Scope.ARCHIVED);

		// archive category entity
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(Entities.CATEGORY, 2);
			}
		);

		// check archived category still has reflected reference to product
		final SealedEntity archivedCategory = this.evita.queryCatalog(
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
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.restoreEntity(Entities.CATEGORY, 2);
				session.restoreEntity(Entities.PRODUCT, 100);
			}
		);

		// check restored category has still reflected reference to product
		final SealedEntity categoryAfterRestore = this.evita.queryCatalog(
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

	@DisplayName("Entity querying should respect scope requirement")
	@Test
	void shouldListEntitiesInParticularScope() {
		/* create schema for entity archival */
		createSchemaForEntityArchiving(Scope.LIVE, Scope.ARCHIVED);

		// upsert entities product depends on
		createBrandAndCategoryEntities();

		// create product entity
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.createNewEntity(Entities.PRODUCT, 100)
					.setAttribute(ATTRIBUTE_CODE, "TV-123")
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "TV")
					.setReference(Entities.BRAND, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_BRAND_EAN, "123"))
					.setReference(Entities.CATEGORY, 2, whichIs -> whichIs.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "EU").setAttribute(ATTRIBUTE_CATEGORY_OPEN, true))
					.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), true)
					.setPrice(1, PRICE_LIST_BASIC, CURRENCY_EUR, new BigDecimal("10"), new BigDecimal("21"), new BigDecimal("12.1"), true)
					.upsertVia(session);

				session.createNewEntity(Entities.PRODUCT, 101)
					.setAttribute(ATTRIBUTE_CODE, "TV-578")
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "LG TV")
					.setReference(Entities.BRAND, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_BRAND_EAN, "457"))
					.setReference(Entities.CATEGORY, 2, whichIs -> whichIs.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "US").setAttribute(ATTRIBUTE_CATEGORY_OPEN, true))
					.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("110"), new BigDecimal("21"), new BigDecimal("133.1"), true)
					.setPrice(1, PRICE_LIST_BASIC, CURRENCY_EUR, new BigDecimal("20"), new BigDecimal("21"), new BigDecimal("24.2"), true)
					.upsertVia(session);
			}
		);

		// archive product entity
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(Entities.PRODUCT, 100);
			}
		);

		this.evita.queryCatalog(
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

	@DisplayName("Entity sorting in multiple scopes")
	@Test
	void shouldOrderInAllScopes() {
		this.evita.defineCatalog(TEST_CATALOG)
			.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.sortableInScope(Scope.values()))
			.updateViaNewSession(this.evita);

		this.evita.updateCatalog(
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
		this.evita.updateCatalog(
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
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(Entities.PRODUCT, 110);
				session.archiveEntity(Entities.PRODUCT, 111);
				session.archiveEntity(Entities.PRODUCT, 112);
			}
		);

		this.evita.queryCatalog(
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
					new int[] { 101, 100, 111, 110, 102, 112 },
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
					new int[] { 101, 100, 110, 111, 112, 102 },
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
					new int[] { 101, 100, 102, 111, 110, 112 },
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

	@DisplayName("Entity extra result generation in multiple scopes")
	@Test
	void shouldGenerateResultsInMultipleScopes() {
		this.evita.defineCatalog(TEST_CATALOG);

		this.evita.updateCatalog(
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
					.withReferenceToEntity(Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE, thatIs -> thatIs.indexedInScope(Scope.LIVE).facetedInScope(Scope.LIVE))
					.withReferenceToEntity(Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE, thatIs -> thatIs.indexedInScope(Scope.LIVE))
					.updateVia(session);
			}
		);

		// create product entity
		this.evita.updateCatalog(
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
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(Entities.PRODUCT, 110);
				session.archiveEntity(Entities.PRODUCT, 111);
				session.archiveEntity(Entities.PRODUCT, 112);
			}
		);

		this.evita.queryCatalog(
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

	@DisplayName("Entity extra result generation over multiple scopes")
	@Test
	void shouldGenerateResultsInOverMultipleScopes() {
		this.evita.defineCatalog(TEST_CATALOG);

		this.evita.updateCatalog(
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
					.withReferenceToEntity(Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE, thatIs -> thatIs.indexedForFilteringAndPartitioningInScope(Scope.values()).facetedInScope(Scope.values()))
					.withReferenceToEntity(Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE, thatIs -> thatIs.indexedForFilteringAndPartitioningInScope(Scope.values()))
					.updateVia(session);
			}
		);

		// create product entity
		this.evita.updateCatalog(
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
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(Entities.PRODUCT, 110);
				session.archiveEntity(Entities.PRODUCT, 111);
				session.archiveEntity(Entities.PRODUCT, 112);
			}
		);

		this.evita.queryCatalog(
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

	@Test
	void shouldBeAbleToDeleteArchivedEntity() {
		/* create schema for entity archival */
		createSchemaForEntityArchiving(Scope.LIVE, Scope.ARCHIVED);

		// upsert entities product depends on
		createBrandAndCategoryEntities();

		// create product entity
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.createNewEntity(Entities.PRODUCT, 100)
					.setAttribute(ATTRIBUTE_CODE, "TV-123")
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "TV")
					.setReference(Entities.BRAND, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_BRAND_EAN, "123"))
					.setReference(Entities.CATEGORY, 2, whichIs -> whichIs.setAttribute(ATTRIBUTE_CATEGORY_MARKET, "EU").setAttribute(ATTRIBUTE_CATEGORY_OPEN, true))
					.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, new BigDecimal("100"), new BigDecimal("21"), new BigDecimal("121"), true)
					.setPrice(1, PRICE_LIST_BASIC, CURRENCY_EUR, new BigDecimal("10"), new BigDecimal("21"), new BigDecimal("12.1"), true)
					.upsertVia(session);
			}
		);

		// archive product entity
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(Entities.PRODUCT, 100);
			}
		);

		// delete archived entity
		this.evita.updateCatalog(
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
	void shouldBeAbleToViolateUniqueConstraintsWhenEntityIsArchived() {
		/* create schema for entity archival */
		this.evita.defineCatalog(TEST_CATALOG)
			.withAttribute(
				ATTRIBUTE_CODE,
				String.class,
				thatIs -> thatIs
					.uniqueGloballyInScope(Scope.values())
					.sortableInScope(Scope.values())
			)
			.updateViaNewSession(this.evita);

		this.evita.updateCatalog(
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
		this.evita.updateCatalog(
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
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(Entities.PRODUCT, 1);
			}
		);

		// upsert change unique key to conflict with archived entity and upsert it
		this.evita.updateCatalog(
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
			queryProductReferenceBy(new Scope[] { Scope.LIVE }, attributeEquals(ATTRIBUTE_CODE, "electronics"))
		);
		assertEquals(
			new EntityReference(Entities.PRODUCT, 2),
			queryProductReferenceBy(new Scope[] { Scope.LIVE }, attributeEquals(ATTRIBUTE_NAME, "electronics"), entityLocaleEquals(Locale.ENGLISH))
		);
		assertEquals(
			new EntityReference(Entities.PRODUCT, 1),
			queryProductReferenceBy(new Scope[] { Scope.ARCHIVED }, attributeEquals(ATTRIBUTE_CODE, "electronics"))
		);
		assertEquals(
			new EntityReference(Entities.PRODUCT, 1),
			queryProductReferenceBy(new Scope[] { Scope.ARCHIVED }, attributeEquals(ATTRIBUTE_NAME, "electronics"), entityLocaleEquals(Locale.ENGLISH))
		);

		// when we look for the unique key in both scopes, the engine should prefer the live entity
		assertEquals(
			new EntityReference(Entities.PRODUCT, 2),
			queryProductReferenceBy(new Scope[] { Scope.LIVE, Scope.ARCHIVED }, attributeEquals(ATTRIBUTE_CODE, "electronics"))
		);
		assertEquals(
			new EntityReference(Entities.PRODUCT, 2),
			queryProductReferenceBy(new Scope[] { Scope.LIVE, Scope.ARCHIVED }, attributeEquals(ATTRIBUTE_NAME, "electronics"), entityLocaleEquals(Locale.ENGLISH))
		);
	}

	@Test
	void shouldBeAbleToRetrieveEntitiesByGloballyUniqueAttributesInBothScopes() {
		/* create schema for entity archival */
		this.evita.defineCatalog(TEST_CATALOG)
			.withAttribute(
				ATTRIBUTE_URL,
				String.class,
				thatIs -> thatIs
					.uniqueGloballyInScope(Scope.values())
					.localized()
			)
			.updateViaNewSession(this.evita);

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withGlobalAttribute(ATTRIBUTE_URL)
					.updateVia(session);
			}
		);

		// upsert non-conflicting entities
		this.evita.updateCatalog(
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
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(Entities.PRODUCT, 1);
			}
		);

		this.evita.close();

		this.evita = new Evita(
			getEvitaConfiguration()
		);

		// try to find entities by the conflicting unique key
		assertArrayEquals(
			new EntityReference[]{
				new EntityReference(Entities.PRODUCT, 1),
				new EntityReference(Entities.PRODUCT, 2)
			},
			this.evita.queryCatalog(
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
			.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.uniqueGloballyInScope(indexScope).sortableInScope(indexScope))
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

	private void checkProductCannotBeLookedUpByIndexes(Scope scope) {
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
									new FilterConstraint[0] : new FilterConstraint[] { scope(scope) }
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
		return EvitaConfiguration.builder()
			.server(
				ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(inactivityTimeoutInSeconds)
					.build()
			)
			.storage(
				StorageOptions.builder()
					.storageDirectory(getTestDirectory().resolve(DIR_EVITA_ARCHIVING_TEST))
					.exportDirectory(getTestDirectory().resolve(DIR_EVITA_ARCHIVING_TEST_EXPORT))
					.build()
			)
			.build();
	}

}
