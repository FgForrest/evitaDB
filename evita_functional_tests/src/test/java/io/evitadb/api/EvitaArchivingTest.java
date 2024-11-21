/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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


import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.core.Catalog;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
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
import java.nio.file.Path;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

import static io.evitadb.api.EvitaIndexingTest.getGlobalIndex;
import static io.evitadb.api.EvitaIndexingTest.getReferencedEntityIndex;
import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * This test verifies archiving (changing scope) of the entities.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class EvitaArchivingTest implements EvitaTestSupport {
	private static final String DIR_EVITA_ARCHIVING_TEST = "evitaArchivingTest";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_CODE_NAME = "codeName";
	private static final String ATTRIBUTE_CATEGORY_OPEN = "open";
	private static final String ATTRIBUTE_CATEGORY_MARKET_OPEN = "marketOpen";
	private static final String ATTRIBUTE_BRAND_EAN = "brandEan";
	private static final String ATTRIBUTE_CATEGORY_MARKET = "market";
	private static final String PRICE_LIST_BASIC = "basic";
	private static final Currency CURRENCY_CZK = Currency.getInstance("CZK");
	private static final Currency CURRENCY_EUR = Currency.getInstance("EUR");

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
		evita = new Evita(
			getEvitaConfiguration()
		);
		evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		evita.close();
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
		evita.updateCatalog(
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
		final Catalog catalog1 = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(Entities.PRODUCT, 100);
			}
		);

		// check product entity is not in any of indexes
		checkProductCannotBeLookedUpByIndexes(Scope.values());

		// check archive indexes exist and previous indexes are removed
		final Catalog catalog2 = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.restoreEntity(Entities.PRODUCT, 100);
			}
		);

		// check product entity is in indexes
		checkProductCanBeLookedUpByIndexes();

		// check live indexes exist and previous indexes are removed
		final Catalog catalog3 = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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
		evita.close();
		evita = new Evita(
			getEvitaConfiguration()
		);

		// check product entity is in indexes
		checkProductCanBeLookedUpByIndexes();

		// check live indexes exist and previous indexes are removed
		final Catalog catalog4 = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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
		evita.updateCatalog(
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
		final Catalog catalog1 = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(Entities.PRODUCT, 100);
			}
		);

		// check product entity is in LIVE indexes
		checkProductCannotBeLookedUpByIndexes();
		// check product entity is in ARCHIVED indexes
		checkProductCanBeLookedUpByIndexes(Scope.values());

		// check archive indexes exist and previous indexes are removed
		final Catalog catalog2 = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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
		evita.updateCatalog(
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
		final Catalog catalog3 = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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
		evita.close();
		evita = new Evita(
			getEvitaConfiguration()
		);

		// check product entity is in indexes
		checkProductCanBeLookedUpByIndexes();

		// check live indexes exist and previous indexes are removed
		final Catalog catalog4 = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.goLiveAndClose();
			}
		);

		// check live indexes exist and previous indexes are removed
		final Catalog catalog5 = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.goLiveAndClose();
			}
		);

		// upsert entities product depends on
		createBrandAndCategoryEntities();

		// create product entity
		evita.updateCatalog(
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
		final Catalog catalog1 = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(Entities.PRODUCT, 100);
			}
		);

		// check product entity is in LIVE indexes
		checkProductCannotBeLookedUpByIndexes();
		// check product entity is in ARCHIVED indexes
		checkProductCanBeLookedUpByIndexes(Scope.values());

		// check archive indexes exist and previous indexes are removed
		final Catalog catalog2 = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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
		evita.updateCatalog(
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
		final Catalog catalog3 = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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
		evita.close();
		evita = new Evita(
			getEvitaConfiguration()
		);

		// check product entity is in indexes
		checkProductCanBeLookedUpByIndexes();

		// check live indexes exist and previous indexes are removed
		final Catalog catalog4 = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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
		evita.updateCatalog(
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
		evita.updateCatalog(
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
		final Catalog catalog = (Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
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

	@DisplayName("Entity reflected references should be removed when entity is archived")
	@Test
	void shouldDropReflectedReferencesOnEntityArchivingAndCreateWhenBeingRestored() {
		/* create schema for entity archival */
		evita.defineCatalog(TEST_CATALOG)
			.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.uniqueGlobally().sortable())
			.updateViaNewSession(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.withReflectedReferenceToEntity(
						"products",
						Entities.PRODUCT,
						Entities.CATEGORY,
						whichIs -> whichIs.indexed().withAttributesInherited()
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
							.indexed()
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
		evita.updateCatalog(
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
		evita.updateCatalog(
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
		final SealedEntity category = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent())
					.orElse(null);
			}
		);
		assertNotNull(category);
		final ReferenceContract products = category.getReference("products", 100).orElse(null);
		assertNotNull(products);
		assertEquals("EU", products.getAttribute(ATTRIBUTE_CATEGORY_MARKET));

		// archive product entity
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(Entities.PRODUCT, 100);
			}
		);

		// check category has no reflected reference to product
		final SealedEntity categoryAfterArchiving = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent())
					.orElse(null);
			}
		);
		assertNotNull(categoryAfterArchiving);
		final ReferenceContract productsAfterArchiving = categoryAfterArchiving.getReference("products", 100).orElse(null);
		assertNull(productsAfterArchiving);

		// restore both category and product entity
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.restoreEntity(Entities.CATEGORY, 2);
				session.restoreEntity(Entities.PRODUCT, 100);
			}
		);

		// check restored category has reflected reference to product again
		final SealedEntity categoryAfterRestore = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent())
					.orElse(null);
			}
		);
		assertNotNull(categoryAfterRestore);
		final ReferenceContract productsAfterRestore = categoryAfterRestore.getReference("products", 100).orElse(null);
		assertNotNull(productsAfterRestore);
		assertEquals("EU", productsAfterRestore.getAttribute(ATTRIBUTE_CATEGORY_MARKET));
	}

	@Test
	void shouldFailToSetUpReflectedReferencesIncompatiblyWithMainReference() {
		/* create schema for entity archival */
		evita.defineCatalog(TEST_CATALOG)
			.updateViaNewSession(evita);

		assertThrows(
			InvalidSchemaMutationException.class,
			() ->
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.defineEntitySchema(Entities.CATEGORY)
							.withoutGeneratedPrimaryKey()
							.withReflectedReferenceToEntity(
								"products",
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
		evita.defineCatalog(TEST_CATALOG)
			.updateViaNewSession(evita);

		assertThrows(
			InvalidSchemaMutationException.class,
			() -> evita.updateCatalog(
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

	@DisplayName("Entity reflected references should be recreated in separate scopes")
	@Test
	void shouldRecreateReflectedReferencesInSeparateScopes() {
		/* create schema for entity archival */
		final Scope[] scopes = new Scope[]{Scope.LIVE, Scope.ARCHIVED};
		evita.defineCatalog(TEST_CATALOG)
			.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.uniqueGloballyInScope(scopes).sortableInScope(scopes))
			.updateViaNewSession(evita);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withGlobalAttribute(ATTRIBUTE_CODE)
					.withReflectedReferenceToEntity(
						"products",
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
		evita.updateCatalog(
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
		evita.updateCatalog(
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
		final SealedEntity category = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent())
					.orElse(null);
			}
		);
		assertNotNull(category);
		final ReferenceContract products = category.getReference("products", 100).orElse(null);
		assertNotNull(products);
		assertEquals("EU", products.getAttribute(ATTRIBUTE_CATEGORY_MARKET));

		// archive product entity
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(Entities.PRODUCT, 100);
			}
		);

		// check category has no reflected reference to product
		final SealedEntity categoryAfterArchiving = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent())
					.orElse(null);
			}
		);
		assertNotNull(categoryAfterArchiving);
		final ReferenceContract productsAfterArchiving = categoryAfterArchiving.getReference("products", 100).orElse(null);
		assertNull(productsAfterArchiving);

		// archive category entity
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(Entities.CATEGORY, 2);
			}
		);

		// check archived category has reflected reference to product
		final SealedEntity archivedCategory = evita.queryCatalog(
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
		final ReferenceContract archivedProducts = archivedCategory.getReference("products", 100).orElse(null);
		assertNotNull(archivedProducts);
		assertEquals("EU", archivedProducts.getAttribute(ATTRIBUTE_CATEGORY_MARKET));

		// restore both category and product entity
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.restoreEntity(Entities.CATEGORY, 2);
				session.restoreEntity(Entities.PRODUCT, 100);
			}
		);

		// check restored category has reflected reference to product again
		final SealedEntity categoryAfterRestore = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent())
					.orElse(null);
			}
		);
		assertNotNull(categoryAfterRestore);
		final ReferenceContract productsAfterRestore = categoryAfterRestore.getReference("products", 100).orElse(null);
		assertNotNull(productsAfterRestore);
		assertEquals("EU", productsAfterRestore.getAttribute(ATTRIBUTE_CATEGORY_MARKET));
	}

	@DisplayName("Entity querying should respect scope requirement")
	@Test
	void shouldListEntitiesInParticularScope() {
		/* create schema for entity archival */
		createSchemaForEntityArchiving(Scope.LIVE, Scope.ARCHIVED);

		// upsert entities product depends on
		createBrandAndCategoryEntities();

		// create product entity
		evita.updateCatalog(
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
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(Entities.PRODUCT, 100);
			}
		);

		evita.queryCatalog(
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

	@Test
	void shouldBeAbleToDeleteArchivedEntity() {
		/* create schema for entity archival */
		createSchemaForEntityArchiving(Scope.LIVE, Scope.ARCHIVED);

		// upsert entities product depends on
		createBrandAndCategoryEntities();

		// create product entity
		evita.updateCatalog(
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
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(Entities.PRODUCT, 100);
			}
		);

		// delete archived entity
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.deleteEntity(Entities.PRODUCT, 100);
			}
		);

		// check entity can't be found in any scope
		checkProductCannotBeLookedUpByIndexes(Scope.values());
	}

	@Test
	void shouldBeAbleToViolateUniqueConstraintsWhenEntityIsArchived() {
		/* create schema for entity archival */
		evita.defineCatalog(TEST_CATALOG)
			.withAttribute(
				ATTRIBUTE_CODE,
				String.class,
				thatIs -> thatIs
					.uniqueGloballyInScope(Scope.values())
					.sortableInScope(Scope.values())
			)
			.updateViaNewSession(evita);

		evita.updateCatalog(
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
		evita.updateCatalog(
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
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.archiveEntity(Entities.PRODUCT, 1);
			}
		);

		// upsert change unique key to conflict with archived entity and upsert it
		evita.updateCatalog(
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

	private void createBrandAndCategoryEntities() {
		evita.updateCatalog(
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
		evita.defineCatalog(TEST_CATALOG)
			.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.uniqueGloballyInScope(indexScope).sortableInScope(indexScope))
			.updateViaNewSession(evita);

		evita.updateCatalog(
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
							.indexedInScope(indexScope)
							.withAttribute(ATTRIBUTE_BRAND_EAN, String.class, whichIs -> whichIs.filterableInScope(indexScope).sortableInScope(indexScope))
					)
					.withReferenceToEntity(
						Entities.CATEGORY,
						Entities.CATEGORY,
						Cardinality.ZERO_OR_MORE,
						thatIs -> thatIs
							.indexedInScope(indexScope)
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

	private void checkProductCannotBeLookedUpByIndexes(Scope... scope) {
		// global attribute
		assertNull(queryProductReferenceBy(scope, attributeEquals(ATTRIBUTE_CODE, "TV-123")));
		// entity attribute
		assertNull(queryProductReferenceBy(scope, attributeEquals(ATTRIBUTE_NAME, "TV"), entityLocaleEquals(Locale.ENGLISH)));
		// references
		assertNull(queryProductReferenceBy(scope, referenceHaving(Entities.BRAND, entityPrimaryKeyInSet(1))));
		// hierarchy
		assertNull(queryProductReferenceBy(scope, hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(2))));
		assertNull(queryProductReferenceBy(scope, hierarchyWithin(Entities.CATEGORY, entityPrimaryKeyInSet(2))));
		// price
		assertNull(queryProductReferenceBy(scope, priceInCurrency(CURRENCY_CZK), priceInPriceLists(PRICE_LIST_BASIC)));
	}

	@Nullable
	private EntityReference queryProductReferenceBy(@Nonnull Scope[] scope, @Nonnull FilterConstraint... filterBy) {
		return evita.queryCatalog(
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
					.storageDirectory(getEvitaTestDirectory())
					.build()
			)
			.build();
	}

	@Nonnull
	private Path getEvitaTestDirectory() {
		return getTestDirectory().resolve(DIR_EVITA_ARCHIVING_TEST);
	}

}
