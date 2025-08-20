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

package io.evitadb.api.functional.indexing;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.EntityMissingException;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.MandatoryAssociatedDataNotProvidedException;
import io.evitadb.api.exception.MandatoryAttributesNotProvidedException;
import io.evitadb.api.exception.ReferenceCardinalityViolatedException;
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.core.EntityCollection;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.function.TriConsumer;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test contains various integration tests for {@link Evita}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class EvitaIndexingTest implements EvitaTestSupport {
	private static final String DIR_EVITA_INDEXING_TEST = "evitaIndexingTest";
	private static final String DIR_EVITA_INDEXING_TEST_EXPORT = "evitaIndexingTest_export";
	private static final String REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY = "productsInCategory";
	private static final String REFERENCE_PRODUCT_CATEGORY = "productCategory";

	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_DESCRIPTION = "description";
	private static final String ATTRIBUTE_EAN = "ean";

	private static final String ATTRIBUTE_BRAND_NAME = "brandName";
	private static final String ATTRIBUTE_BRAND_DESCRIPTION = "brandDescription";
	private static final String ATTRIBUTE_BRAND_EAN = "brandEan";
	private static final String ATTRIBUTE_CATEGORY_PRIORITY = "categoryPriority";
	private static final Locale LOCALE_CZ = new Locale("cs", "CZ");
	private static final Currency CURRENCY_CZK = Currency.getInstance("CZK");
	private static final Currency CURRENCY_EUR = Currency.getInstance("EUR");
	private static final Currency CURRENCY_GBP = Currency.getInstance("GBP");
	private static final String PRICE_LIST_BASIC = "basic";
	private static final String PRICE_LIST_VIP = "vip";
	private static final String ATTRIBUTE_PRODUCT_CATEGORY_NOT_INHERITED = "notInherited";
	private static final String ATTRIBUTE_PRODUCT_CATEGORY_INHERITED = "inherited";
	private static final String ATTRIBUTE_CATEGORY_MARKET = "market";
	private static final String ATTRIBUTE_PRODUCT_CATEGORY_VARIANT = "variant";
	private Evita evita;

	private static void assertDataWasPropagated(EntityIndex categoryIndex, int recordId) {
		assertNotNull(categoryIndex);
		assertTrue(categoryIndex.getUniqueIndex(AttributeSchema._internalBuild(ATTRIBUTE_EAN, String.class, false), null).getRecordIds().contains(recordId));
		assertTrue(categoryIndex.getFilterIndex(new AttributeKey(ATTRIBUTE_EAN)).getAllRecords().contains(recordId));
		assertTrue(ArrayUtils.contains(categoryIndex.getSortIndex(new AttributeKey(ATTRIBUTE_EAN)).getSortedRecords(), recordId));
		assertTrue(categoryIndex.getPriceIndex(PRICE_LIST_BASIC, CURRENCY_CZK, PriceInnerRecordHandling.NONE).getIndexedPriceEntityIds().contains(recordId));
		// EUR price is not indexed
		assertNull(categoryIndex.getPriceIndex(PRICE_LIST_BASIC, CURRENCY_EUR, PriceInnerRecordHandling.NONE));
	}

	@Nullable
	private static EntityReference fetchProductInLocale(@Nonnull EvitaSessionContract session, int primaryKey, @Nonnull Locale locale) {
		return session.queryOneEntityReference(
			query(
				collection(Entities.PRODUCT),
				filterBy(
					and(
						entityPrimaryKeyInSet(primaryKey),
						entityLocaleEquals(locale)
					)
				)
			)
		).orElse(null);
	}

	/**
	 * Asserts that the references between product and category entities are linked correctly.
	 *
	 * @param session the session to use for entity fetching
	 */
	private static void assertReferencesAreEntangled(@Nonnull EvitaSessionContract session) {
		final SealedEntity product = session.getEntity(Entities.PRODUCT, 10, entityFetchAllContent()).orElseThrow();
		assertTrue(product.getReference(REFERENCE_PRODUCT_CATEGORY, 1).isPresent());
		final SealedEntity category = session.getEntity(Entities.CATEGORY, 1, entityFetchAllContent()).orElseThrow();
		assertTrue(category.getReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10).isPresent());
	}

	/**
	 * Asserts that the references between product and category entities are linked correctly.
	 *
	 * @param session the session to use for entity fetching
	 */
	private static void assertReferencesAreNotEntangled(@Nonnull EvitaSessionContract session) {
		final SealedEntity product = session.getEntity(Entities.PRODUCT, 10, entityFetchAllContent()).orElseThrow();
		assertTrue(product.getReference(REFERENCE_PRODUCT_CATEGORY, 1).isEmpty());
		final SealedEntity category = session.getEntity(Entities.CATEGORY, 1, entityFetchAllContent()).orElseThrow();
		assertTrue(category.getReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10).isEmpty());
	}

	/**
	 * Asserts that the references between product and category entities are linked correctly.
	 *
	 * @param session the session to use for entity fetching
	 */
	private static void assertEntitiesAreNotEntangled(@Nonnull EvitaSessionContract session) {
		final Optional<SealedEntity> product = session.getEntity(Entities.PRODUCT, 10, entityFetchAllContent());
		product.ifPresent(it -> assertTrue(it.getReference(REFERENCE_PRODUCT_CATEGORY, 1).isEmpty()));
		final Optional<SealedEntity> category = session.getEntity(Entities.CATEGORY, 1, entityFetchAllContent());
		category.ifPresent(it -> assertTrue(it.getReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10).isEmpty()));
		assertTrue(
			product.isPresent() || category.isPresent(),
			"Neither product nor category entity was found!"
		);
		assertFalse(
			product.isPresent() && category.isPresent(),
			"Both product and category entities were found!"
		);
	}

	/**
	 * Creates a schema with reflected references between CATEGORY and PRODUCT entities using the given session.
	 *
	 * @param session the session to use for schema creation
	 */
	private static void createEntangledSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(Entities.CATEGORY)
			.withReflectedReferenceToEntity(
				REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY
			)
			.updateVia(session);
		session
			.defineEntitySchema(Entities.PRODUCT)
			.withReferenceToEntity(
				REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.indexedForFilteringAndPartitioning()
			)
			.updateVia(session);
	}

	/**
	 * Creates a schema with reflected references between CATEGORY and PRODUCT entities using the given session.
	 *
	 * @param session the session to use for schema creation
	 */
	private static void createEntangledSchemaWithInheritedAttributes(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(Entities.CATEGORY)
			.withReflectedReferenceToEntity(
				REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY,
				whichIs -> whichIs.withAttributesInheritedExcept(ATTRIBUTE_PRODUCT_CATEGORY_NOT_INHERITED)
					.withAttribute(ATTRIBUTE_CATEGORY_MARKET, String.class, thatIs -> thatIs.filterable().sortable().withDefaultValue("CZ"))
			)
			.updateVia(session);
		session
			.defineEntitySchema(Entities.PRODUCT)
			.withReferenceToEntity(
				REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.indexedForFilteringAndPartitioning()
					.withAttribute(ATTRIBUTE_PRODUCT_CATEGORY_NOT_INHERITED, String.class, thatIs -> thatIs.filterable().sortable().withDefaultValue("default"))
					.withAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, String.class, thatIs -> thatIs.filterable().sortable().nullable())
			)
			.updateVia(session);
	}

	/**
	 * Creates a schema with reflected references between CATEGORY and PRODUCT entities using the given session.
	 *
	 * @param session the session to use for schema creation
	 */
	private static void createEntangledSchemaWithInheritedAttributesWithoutDefaults(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(Entities.CATEGORY)
			.withReflectedReferenceToEntity(
				REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY,
				whichIs -> whichIs.withAttributesInheritedExcept(ATTRIBUTE_PRODUCT_CATEGORY_NOT_INHERITED)
					.withAttribute(ATTRIBUTE_CATEGORY_MARKET, String.class, thatIs -> thatIs.filterable().sortable())
			)
			.updateVia(session);
		session
			.defineEntitySchema(Entities.PRODUCT)
			.withReferenceToEntity(
				REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.indexedForFilteringAndPartitioning()
					.withAttribute(ATTRIBUTE_PRODUCT_CATEGORY_NOT_INHERITED, String.class, thatIs -> thatIs.filterable().sortable())
					.withAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, String.class, thatIs -> thatIs.filterable().sortable())
			)
			.updateVia(session);
	}

	/**
	 * Asserts the values of inherited and non-inherited attributes for specified product and category entities.
	 *
	 * @param session the session to use for entity fetching
	 * @param productNotInherited expected value for the non-inherited product attribute
	 * @param productInherited expected value for the inherited product attribute
	 * @param categoryMarket expected value for the category market attribute
	 */
	private static void assertInheritedAttributesValues(
		@Nonnull EvitaSessionContract session,
		@Nonnull String productNotInherited,
		@Nullable String productInherited,
		@Nonnull String categoryMarket
	) {
		final SealedEntity product = session.getEntity(Entities.PRODUCT, 10, entityFetchAllContent()).orElseThrow();
		final ReferenceContract productCategory = product.getReference(REFERENCE_PRODUCT_CATEGORY, 1).orElseThrow();
		assertEquals(productNotInherited, productCategory.getAttribute(ATTRIBUTE_PRODUCT_CATEGORY_NOT_INHERITED));
		if (productInherited != null) {
			assertEquals(productInherited, productCategory.getAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED));
		} else {
			assertNull(productCategory.getAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED));
		}

		final SealedEntity category = session.getEntity(Entities.CATEGORY, 1, entityFetchAllContent()).orElseThrow();
		final ReferenceContract productsInCategory = category.getReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10).orElseThrow();
		assertEquals(categoryMarket, productsInCategory.getAttribute(ATTRIBUTE_CATEGORY_MARKET));
		if (productInherited != null) {
			assertEquals(productInherited, productsInCategory.getAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED));
		} else {
			assertNull(productsInCategory.getAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED));
		}
	}

	@Nullable
	static EntityIndex getGlobalIndex(@Nonnull EntityCollectionContract collection) {
		return getGlobalIndex(collection, Scope.LIVE);
	}

	@Nullable
	static EntityIndex getGlobalIndex(@Nonnull EntityCollectionContract collection, @Nonnull Scope scope) {
		Assert.isTrue(collection instanceof EntityCollection, "Unexpected entity collection type!");
		return ((EntityCollection) collection).getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.GLOBAL, scope)
		);
	}

	@Nullable
	static EntityIndex getReferencedEntityIndex(@Nonnull EntityCollectionContract collection, @Nonnull String entityType, int recordId) {
		return getReferencedEntityIndex(collection, Scope.LIVE, entityType, recordId);
	}

	@Nullable
	static EntityIndex getReferencedEntityIndex(@Nonnull EntityCollectionContract collection, @Nonnull Scope scope, @Nonnull String entityType, int recordId) {
		Assert.isTrue(collection instanceof EntityCollection, "Unexpected entity collection type!");
		return ((EntityCollection) collection).getIndexByKeyIfExists(
			new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY,
				scope,
				new ReferenceKey(entityType, recordId)
			)
		);
	}

	private static int[] getAllCategories(EvitaSessionContract session) {
		return session.query(
				query(
					collection(Entities.CATEGORY)
				),
				EntityReferenceContract.class
			)
			.getRecordData()
			.stream()
			.mapToInt(EntityReferenceContract::getPrimaryKey)
			.toArray();
	}

	private static int countProductsWithPriceListCurrencyCombination(EvitaSessionContract session, String priceList, Currency currency) {
		return session.query(
				query(
					collection(Entities.PRODUCT),
					filterBy(
						and(
							priceInPriceLists(priceList),
							priceInCurrency(currency)
						)
					)
				),
				EntityReferenceContract.class
			)
			.getTotalRecordCount();
	}

	@BeforeEach
	void setUp() {
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_INDEXING_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_INDEXING_TEST_EXPORT);
		evita = new Evita(
			getEvitaConfiguration()
		);
		evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		evita.close();
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_INDEXING_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_INDEXING_TEST_EXPORT);
	}

	@DisplayName("Update catalog in warm-up mode with another product - synchronously.")
	@Test
	void shouldUpdateCatalogWithAnotherProduct() {
		final SealedEntity addedEntity = evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				evita.defineCatalog(TEST_CATALOG)
					.withDescription("Some description.")
					.withEntitySchema(
						Entities.PRODUCT,
						whichIs -> whichIs
							.withDescription("My fabulous product.")
							.withGeneratedPrimaryKey()
					)
					.updateVia(session);

				final SealedEntity upsertedEntity = session.upsertAndFetchEntity(
					session.createNewEntity(Entities.PRODUCT)
				);
				assertNotNull(upsertedEntity);
				return upsertedEntity;
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Optional<SealedEntity> fetchedEntity = session.getEntity(Entities.PRODUCT, addedEntity.getPrimaryKey());
				assertTrue(fetchedEntity.isPresent());
				assertEquals(addedEntity, fetchedEntity.get());
			}
		);
	}

	@DisplayName("Update catalog in warm-up mode with another product - asynchronously.")
	@Test
	void shouldUpdateCatalogWithAnotherProductAsynchronously() throws ExecutionException, InterruptedException, TimeoutException {
		shouldUpdateCatalogWithAnotherProduct();

		final int addedEntityPrimaryKey = evita.updateCatalogAsync(
			TEST_CATALOG,
			session -> {
				final SealedEntity upsertedEntity = session.upsertAndFetchEntity(
					session.createNewEntity(Entities.PRODUCT)
				);
				assertNotNull(upsertedEntity);
				return upsertedEntity;
			},
			CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION
		).toCompletableFuture()
			.get(1, TimeUnit.MINUTES)
			.getPrimaryKeyOrThrowException();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				// the entity will immediately available in indexes
				final Optional<SealedEntity> fetchedEntity = session.getEntity(Entities.PRODUCT, addedEntityPrimaryKey);
				assertTrue(fetchedEntity.isPresent());
			}
		);
	}

	@Test
	void shouldAllowCreatingCatalogAndEntityCollectionsInPrototypingMode() {
		final String someCatalogName = "differentCatalog";
		try {
			evita.defineCatalog(someCatalogName)
				.withDescription("This is a tutorial catalog.")
				.updateViaNewSession(evita);

			assertTrue(evita.getCatalogNames().contains(someCatalogName));
			evita.updateCatalog(
				someCatalogName,
				session -> {
					session.createNewEntity("Brand", 1)
						.setAttribute("name", Locale.ENGLISH, "Lenovo")
						.upsertVia(session);

					final Optional<SealedEntitySchema> brand = session.getEntitySchema("Brand");
					assertTrue(brand.isPresent());

					final Optional<EntityAttributeSchemaContract> nameAttribute = brand.get().getAttribute("name");
					assertTrue(nameAttribute.isPresent());
					assertTrue(nameAttribute.get().isLocalized());

					// now create an example category tree
					session.createNewEntity("Category", 10)
						.setAttribute("name", Locale.ENGLISH, "Electronics")
						.upsertVia(session);

					session.createNewEntity("Category", 11)
						.setAttribute("name", Locale.ENGLISH, "Laptops")
						// laptops will be a child category of electronics
						.setParent(10)
						.upsertVia(session);

					// finally, create a product
					session.createNewEntity("Product")
						// with a few attributes
						.setAttribute("name", Locale.ENGLISH, "ThinkPad P15 Gen 1")
						.setAttribute("cores", 8)
						.setAttribute("graphics", "NVIDIA Quadro RTX 4000 with Max-Q Design")
						// and price for sale
						.setPrice(
							1, "basic",
							Currency.getInstance("USD"),
							new BigDecimal("1420"), new BigDecimal("20"), new BigDecimal("1704"),
							true
						)
						// link it to the manufacturer
						.setReference(
							"brand", "Brand",
							Cardinality.EXACTLY_ONE,
							1
						)
						// and to the laptop category
						.setReference(
							"categories", "Category",
							Cardinality.ZERO_OR_MORE,
							11
						)
						.upsertVia(session);

					final Optional<SealedEntitySchema> product = session.getEntitySchema("Product");
					assertTrue(product.isPresent());

					final Optional<EntityAttributeSchemaContract> productNameAttribute = product.get().getAttribute("name");
					assertTrue(productNameAttribute.isPresent());
					assertTrue(productNameAttribute.get().isLocalized());
				}
			);

		} finally {
			evita.deleteCatalogIfExists(someCatalogName);
		}
	}

	@Test
	void shouldAllowCreatingCatalogAndEntityCollectionsAlongWithTheSchema() {
		final String someCatalogName = "differentCatalog";
		try {
			evita.defineCatalog(someCatalogName)
				.withDescription("Some description.")
				.withEntitySchema(
					Entities.PRODUCT,
					whichIs -> whichIs
						.withDescription("My fabulous product.")
						.withAttribute("someAttribute", String.class, thatIs -> thatIs.filterable().nullable())
				)
				.updateViaNewSession(evita);

			assertTrue(evita.getCatalogNames().contains(someCatalogName));
			evita.queryCatalog(
				someCatalogName,
				session -> {
					assertEquals("Some description.", session.getCatalogSchema().getDescription());
					final SealedEntitySchema productSchema = session.getEntitySchema(Entities.PRODUCT).orElseThrow();

					assertEquals("My fabulous product.", productSchema.getDescription());
					final AttributeSchemaContract attributeSchema = productSchema.getAttribute("someAttribute").orElseThrow();
					assertTrue(attributeSchema.isFilterable());
					assertTrue(attributeSchema.isNullable());
					assertFalse(attributeSchema.isSortable());
				}
			);

		} finally {
			evita.deleteCatalogIfExists(someCatalogName);
		}
	}

	@Test
	void shouldAllowUpdatingCatalogAndEntityCollectionsAlongWithTheSchema() {
		/* first update the catalog the standard way */
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withAttribute(ATTRIBUTE_NAME, String.class)
					.withDescription("Test")
					.updateVia(session);
			}
		);

		// now alter it again from the birds view
		try {
			evita.defineCatalog(TEST_CATALOG)
				.withDescription("Some description.")
				.withEntitySchema(
					Entities.PRODUCT,
					whichIs -> whichIs
						.withDescription("My fabulous product.")
						.withAttribute("someAttribute", String.class, thatIs -> thatIs.filterable().nullable())
				)
				.updateViaNewSession(evita);

			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertEquals("Some description.", session.getCatalogSchema().getDescription());
					final SealedEntitySchema productSchema = session.getEntitySchema(Entities.PRODUCT).orElseThrow();

					assertEquals("My fabulous product.", productSchema.getDescription());

					final AttributeSchemaContract alreadyExistingAttributeSchema = productSchema.getAttribute(ATTRIBUTE_NAME).orElseThrow();
					assertEquals(String.class, alreadyExistingAttributeSchema.getType());
					assertFalse(alreadyExistingAttributeSchema.isFilterable());
					assertFalse(alreadyExistingAttributeSchema.isNullable());
					assertFalse(alreadyExistingAttributeSchema.isSortable());

					final AttributeSchemaContract attributeSchema = productSchema.getAttribute("someAttribute").orElseThrow();
					assertEquals(String.class, alreadyExistingAttributeSchema.getType());
					assertTrue(attributeSchema.isFilterable());
					assertTrue(attributeSchema.isNullable());
					assertFalse(attributeSchema.isSortable());
				}
			);

		} finally {
			evita.deleteCatalogIfExists(TEST_CATALOG);
		}
	}

	@Test
	void shouldFailToUpsertUnknownEntityToStrictlyValidatedCatalogSchema() {
		/* set strict schema verification */
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.verifyCatalogSchemaStrictly()
					.updateVia(session);
			}
		);

		// now try to upset an unknown entity
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.createNewEntity(Entities.PRODUCT)
						.setAttribute(ATTRIBUTE_NAME, LOCALE_CZ, "Produkt")
						.upsertVia(session);
				}
			)
		);
	}

	@Test
	void shouldUpsertUnknownEntityToLaxlyValidatedCatalogSchema() {
		/* set strict schema verification */
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.verifyCatalogSchemaButCreateOnTheFly()
					.updateVia(session);
			}
		);

		// now try to upset an unknown entity
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.createNewEntity(Entities.PRODUCT)
					.setAttribute(ATTRIBUTE_NAME, LOCALE_CZ, "Produkt")
					.upsertVia(session);
			}
		);

		assertNotNull(
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return session.queryOneSealedEntity(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								and(
									entityLocaleEquals(LOCALE_CZ),
									attributeEquals(ATTRIBUTE_NAME, "Produkt")
								)
							),
							require(
								entityFetchAll()
							)
						)
					);
				}
			)
		);
	}

	@Test
	void shouldCreateDeleteAndRecreateReferencedEntityWithSameAttribute() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY);
				session.defineEntitySchema(Entities.PRODUCT)
					.withReferenceTo(
						Entities.CATEGORY,
						"externalCategory",
						Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioning()
							.withAttribute(
								ATTRIBUTE_CATEGORY_PRIORITY,
								Long.class,
								thatIs -> thatIs.sortable()
							)
					).updateVia(session);

				final EntityBuilder product = session.createNewEntity(Entities.PRODUCT, 1)
					.setReference(
						Entities.CATEGORY, 1,
						thatIs -> thatIs.setAttribute(ATTRIBUTE_CATEGORY_PRIORITY, 1L)
					);

				session.upsertEntity(product);

				session.upsertEntity(
					session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.CATEGORY, 1)
				);

				session.upsertEntity(
					session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(
							Entities.CATEGORY, 8,
							thatIs -> thatIs.setAttribute(ATTRIBUTE_CATEGORY_PRIORITY, 5L)
						)
				);

				final SealedEntity loadedEntity = session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow();

				assertNull(
					loadedEntity.getReference(Entities.CATEGORY, 1).orElse(null)
				);
				assertEquals(
					5L,
					(Long) loadedEntity
						.getReference(Entities.CATEGORY, 8)
						.orElseThrow()
						.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY)
				);
			}
		);
	}

	@Test
	void shouldCreateDeleteAndRecreateSortableAttributeForReferencedEntity() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {

				session.defineEntitySchema(Entities.CATEGORY);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceTo(
						Entities.CATEGORY,
						"externalCategory",
						Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFilteringAndPartitioning()
							.withAttribute(
								ATTRIBUTE_CATEGORY_PRIORITY,
								Long.class,
								thatIs -> thatIs.sortable().nullable()
							)
					)
					.updateVia(session);

				final EntityBuilder product = session.createNewEntity(Entities.PRODUCT, 1)
					.setReference(
						Entities.CATEGORY, 1,
						thatIs -> thatIs.setAttribute(ATTRIBUTE_CATEGORY_PRIORITY, 1L)
					);

				session.upsertEntity(product);

				session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setReference(
						Entities.CATEGORY, 1,
						thatIs -> thatIs.removeAttribute(ATTRIBUTE_CATEGORY_PRIORITY)
					)
					.upsertVia(session);

				session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setReference(
						Entities.CATEGORY, 1,
						thatIs -> thatIs.setAttribute(ATTRIBUTE_CATEGORY_PRIORITY, 5L)
					)
					.upsertVia(session);

				final SealedEntity loadedEntity = session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow();

				assertEquals(
					5L,
					(Long) loadedEntity
						.getReference(Entities.CATEGORY, 1).orElseThrow()
						.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY)
				);
			}
		);
	}

	@Test
	void shouldAdaptSupportedLocaleAndCurrencySet() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT)
					.verifySchemaButCreateOnTheFly()
					.updateVia(session);

				final EntityBuilder product = session.createNewEntity(Entities.PRODUCT, 1)
					.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE)
					.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
					.setPrice(2, PRICE_LIST_BASIC, CURRENCY_EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
					.setPrice(3, PRICE_LIST_VIP, CURRENCY_CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
					.setPrice(4, PRICE_LIST_VIP, CURRENCY_EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "The product");

				session.upsertEntity(product);

				final SealedEntitySchema entitySchema = session.getEntitySchema(Entities.PRODUCT)
					.orElseThrow();

				final Set<Locale> locales = entitySchema.getLocales();
				assertEquals(1, locales.size());
				assertTrue(locales.contains(Locale.ENGLISH));

				final Set<Currency> currencies = entitySchema.getCurrencies();
				assertEquals(2, currencies.size());
				assertTrue(currencies.contains(CURRENCY_CZK));
				assertTrue(currencies.contains(CURRENCY_EUR));

				session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setPrice(5, PRICE_LIST_VIP, CURRENCY_GBP, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
					.setAttribute(ATTRIBUTE_NAME, LOCALE_CZ, "Produkt")
					.upsertVia(session);

				final SealedEntitySchema entitySchemaAgain = session.getEntitySchema(Entities.PRODUCT)
					.orElseThrow();

				assertNotNull(entitySchemaAgain);
				final Set<Locale> localesAgain = entitySchemaAgain.getLocales();
				assertEquals(2, localesAgain.size());
				assertTrue(localesAgain.contains(Locale.ENGLISH));
				assertTrue(localesAgain.contains(LOCALE_CZ));

				final Set<Currency> currenciesAgain = entitySchemaAgain.getCurrencies();
				assertEquals(3, currenciesAgain.size());
				assertTrue(currenciesAgain.contains(CURRENCY_CZK));
				assertTrue(currenciesAgain.contains(CURRENCY_EUR));
				assertTrue(currenciesAgain.contains(CURRENCY_GBP));
			}
		);
	}

	@Test
	void shouldChangePriceInnerRecordHandlingAndRemovePrice() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withPrice()
					.updateVia(session);

				final EntityBuilder product = session.createNewEntity(Entities.PRODUCT, 1)
					.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE)
					.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
					.setPrice(2, PRICE_LIST_BASIC, CURRENCY_EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
					.setPrice(3, PRICE_LIST_VIP, CURRENCY_CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
					.setPrice(4, PRICE_LIST_VIP, CURRENCY_EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true);

				session.upsertEntity(product);

				session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE)
					.upsertVia(session);

				session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.removePrice(1, PRICE_LIST_BASIC, CURRENCY_CZK)
					.removePrice(3, PRICE_LIST_VIP, CURRENCY_CZK)
					.upsertVia(session);

				final SealedEntity loadedEntity = session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow();

				assertEquals(
					2,
					loadedEntity
						.getPrices()
						.size()
				);
			}
		);
	}

	@Test
	void shouldRemoveEntityLocaleWhenAllAttributesOfSuchLocaleAreRemoved() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
					.updateVia(session);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Hence, product")
						.setAttribute(ATTRIBUTE_NAME, LOCALE_CZ, "Hle, produkt")
				);
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity entity = session.getEntity(Entities.PRODUCT, 1, dataInLocalesAll())
					.orElseThrow();

				final Set<Locale> locales = entity.getLocales();
				assertEquals(2, locales.size());
				assertTrue(locales.contains(Locale.ENGLISH));
				assertTrue(locales.contains(LOCALE_CZ));

				assertNotNull(fetchProductInLocale(session, 1, Locale.ENGLISH));
				assertNotNull(fetchProductInLocale(session, 1, LOCALE_CZ));
			}
		);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(Entities.PRODUCT, 1, attributeContent(), dataInLocalesAll())
					.orElseThrow()
					.openForWrite()
					.removeAttribute(ATTRIBUTE_NAME, Locale.ENGLISH)
					.upsertVia(session);
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity entity = session.getEntity(Entities.PRODUCT, 1, dataInLocalesAll())
					.orElseThrow();

				final Set<Locale> locales = entity.getLocales();
				assertEquals(1, locales.size());
				assertTrue(locales.contains(LOCALE_CZ));

				assertNull(fetchProductInLocale(session, 1, Locale.ENGLISH));
				assertNotNull(fetchProductInLocale(session, 1, LOCALE_CZ));
			}
		);
	}

	@Test
	void shouldRemoveEntityLocaleWhenAllReferenceAttributesOfSuchLocaleAreRemoved() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.BRAND);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(
						Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE,
						thatIs -> thatIs.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().nullable())
					)
					.updateVia(session);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
						.setReference(Entities.BRAND, 1, thatIs -> thatIs
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Siemens")
							.setAttribute(ATTRIBUTE_NAME, Locale.FRENCH, "Siemens")
						)
						.setReference(Entities.BRAND, 2, thatIs -> thatIs.setAttribute(ATTRIBUTE_NAME, LOCALE_CZ, "Škoda"))
						.setReference(Entities.BRAND, 3, thatIs -> thatIs
							.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Tesla")
							.setAttribute(ATTRIBUTE_NAME, LOCALE_CZ, "Tesla")
							.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "Tesla")
						)
				);
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity entity = session.getEntity(Entities.PRODUCT, 1, dataInLocalesAll())
					.orElseThrow();

				final Set<Locale> locales = entity.getLocales();
				assertEquals(4, locales.size());
				assertTrue(locales.contains(Locale.ENGLISH));
				assertTrue(locales.contains(Locale.GERMAN));
				assertTrue(locales.contains(Locale.FRENCH));
				assertTrue(locales.contains(LOCALE_CZ));

				assertNotNull(fetchProductInLocale(session, 1, Locale.ENGLISH));
				assertNotNull(fetchProductInLocale(session, 1, Locale.GERMAN));
				assertNotNull(fetchProductInLocale(session, 1, Locale.FRENCH));
				assertNotNull(fetchProductInLocale(session, 1, LOCALE_CZ));
			}
		);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(Entities.PRODUCT, 1, referenceContentAll(), dataInLocalesAll())
					.orElseThrow()
					.openForWrite()
					.setReference(Entities.BRAND, 1, thatIs -> thatIs
						.removeAttribute(ATTRIBUTE_NAME, Locale.FRENCH)
						.removeAttribute(ATTRIBUTE_NAME, Locale.ENGLISH))
					.upsertVia(session);
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity entity = session.getEntity(Entities.PRODUCT, 1, dataInLocalesAll())
					.orElseThrow();

				final Set<Locale> locales = entity.getLocales();
				assertEquals(3, locales.size());
				assertTrue(locales.contains(Locale.ENGLISH));
				assertTrue(locales.contains(Locale.GERMAN));
				assertTrue(locales.contains(LOCALE_CZ));

				assertNotNull(fetchProductInLocale(session, 1, Locale.ENGLISH));
				assertNotNull(fetchProductInLocale(session, 1, Locale.GERMAN));
				assertNotNull(fetchProductInLocale(session, 1, LOCALE_CZ));
				assertNull(fetchProductInLocale(session, 1, Locale.FRENCH));
			}
		);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(Entities.PRODUCT, 1, referenceContentAll(), dataInLocalesAll())
					.orElseThrow()
					.openForWrite()
					.removeReference(Entities.BRAND, 3)
					.upsertVia(session);
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity entity = session.getEntity(Entities.PRODUCT, 1, dataInLocalesAll())
					.orElseThrow();

				final Set<Locale> locales = entity.getLocales();
				assertEquals(1, locales.size());
				assertTrue(locales.contains(LOCALE_CZ));

				assertNotNull(fetchProductInLocale(session, 1, LOCALE_CZ));
				assertNull(fetchProductInLocale(session, 1, Locale.ENGLISH));
				assertNull(fetchProductInLocale(session, 1, Locale.GERMAN));
				assertNull(fetchProductInLocale(session, 1, Locale.FRENCH));
			}
		);
	}

	@Test
	void shouldRemoveEntityLocaleWhenAllAssociatedDataOfSuchLocaleAreRemoved() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withAssociatedData(ATTRIBUTE_NAME, String.class, thatIs -> thatIs.localized().nullable())
					.updateVia(session);

				session.createNewEntity(Entities.PRODUCT, 1)
					.setAssociatedData(ATTRIBUTE_NAME, Locale.ENGLISH, "Hence, product")
					.setAssociatedData(ATTRIBUTE_NAME, LOCALE_CZ, "Hle, produkt")
					.upsertVia(session);
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity entity = session.getEntity(Entities.PRODUCT, 1, dataInLocalesAll())
					.orElseThrow();

				final Set<Locale> locales = entity.getLocales();
				assertEquals(2, locales.size());
				assertTrue(locales.contains(Locale.ENGLISH));
				assertTrue(locales.contains(LOCALE_CZ));

				assertNotNull(fetchProductInLocale(session, 1, Locale.ENGLISH));
				assertNotNull(fetchProductInLocale(session, 1, LOCALE_CZ));
			}
		);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(Entities.PRODUCT, 1, associatedDataContentAll(), dataInLocalesAll())
					.orElseThrow()
					.openForWrite()
					.removeAssociatedData(ATTRIBUTE_NAME, Locale.ENGLISH)
					.upsertVia(session);
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity entity = session.getEntity(Entities.PRODUCT, 1, dataInLocalesAll())
					.orElseThrow();

				final Set<Locale> locales = entity.getLocales();
				assertEquals(1, locales.size());
				assertTrue(locales.contains(LOCALE_CZ));

				assertNull(fetchProductInLocale(session, 1, Locale.ENGLISH));
				assertNotNull(fetchProductInLocale(session, 1, LOCALE_CZ));
			}
		);
	}

	@Test
	void shouldAcceptNullInNonNullableAttributeWhenDefaultValueIsSet() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withAttribute(ATTRIBUTE_EAN, String.class, whichIs -> whichIs.withDefaultValue("01"))
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().withDefaultValue("A"))
					.withAttribute(ATTRIBUTE_DESCRIPTION, String.class, whichIs -> whichIs.localized().nullable())
					.withReferenceTo(
						Entities.BRAND, Entities.BRAND, Cardinality.EXACTLY_ONE,
						thatIs -> thatIs
							.withAttribute(ATTRIBUTE_BRAND_EAN, String.class, whichIs -> whichIs.withDefaultValue("01"))
							.withAttribute(ATTRIBUTE_BRAND_NAME, String.class, whichIs -> whichIs.localized().withDefaultValue("A"))
							.withAttribute(ATTRIBUTE_BRAND_DESCRIPTION, String.class, whichIs -> whichIs.localized().nullable())
					)
					.updateVia(session);

				session
					.createNewEntity(Entities.PRODUCT, 1)
					.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH, "A")
					.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.GERMAN, "B")
					.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.FRENCH, "C")
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "D")
					.setReference(
						Entities.BRAND, 1,
						whichIs -> whichIs
							.setAttribute(ATTRIBUTE_BRAND_DESCRIPTION, Locale.ENGLISH, "A")
							.setAttribute(ATTRIBUTE_BRAND_DESCRIPTION, Locale.GERMAN, "B")
							.setAttribute(ATTRIBUTE_BRAND_DESCRIPTION, Locale.FRENCH, "C")
							.setAttribute(ATTRIBUTE_BRAND_NAME, Locale.ENGLISH, "D")
					).upsertVia(session);
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity product = session.getEntity(Entities.PRODUCT, 1, attributeContent(), dataInLocalesAll(), referenceContentAllWithAttributes())
					.orElseThrow();

				assertEquals("01", product.getAttribute(ATTRIBUTE_EAN));
				assertEquals("D", product.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH));
				assertEquals("A", product.getAttribute(ATTRIBUTE_NAME, Locale.GERMAN));
				assertEquals("A", product.getAttribute(ATTRIBUTE_NAME, Locale.FRENCH));

				final ReferenceContract theBrand = product.getReference(Entities.BRAND, 1).orElseThrow();
				assertEquals("01", theBrand.getAttribute(ATTRIBUTE_BRAND_EAN));
				assertEquals("D", theBrand.getAttribute(ATTRIBUTE_BRAND_NAME, Locale.ENGLISH));
				assertEquals("A", theBrand.getAttribute(ATTRIBUTE_BRAND_NAME, Locale.GERMAN));
				assertEquals("A", theBrand.getAttribute(ATTRIBUTE_BRAND_NAME, Locale.FRENCH));
			}
		);
	}

	@Test
	void shouldDifferentClientsSeeSchemaUnchangedUntilCommitted() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withGeneratedPrimaryKey()
					.updateVia(session);
				// switch to transactional mode
				session.goLiveAndClose();
			}
		);
		// change schema by upserting first entity
		try (final EvitaSessionContract session = evita.createReadWriteSession(TEST_CATALOG)) {
			// now implicitly update the schema
			session
				.createNewEntity(Entities.PRODUCT, 1)
				.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH, "A")
				.upsertVia(session);

			// try to read the schema in different session
			final Thread asyncThread = new Thread(() -> {
				evita.queryCatalog(
					TEST_CATALOG,
					differentSession -> {
						final SealedEntitySchema theSchema = differentSession.getEntitySchema(Entities.PRODUCT).orElseThrow();
						assertTrue(theSchema.isWithGeneratedPrimaryKey());
						assertTrue(theSchema.getAttributes().isEmpty());
						assertNull(differentSession.getEntity(Entities.PRODUCT, 1).orElse(null));
					}
				);
			});
			asyncThread.start();
			int i = 0;
			do {
				Thread.onSpinWait();
				i++;
			} while (asyncThread.isAlive() && i < 1_000_000);
		}

		// try to read the schema in different session when changes has been committed
		final Thread asyncThread = new Thread(() -> {
			evita.queryCatalog(
				TEST_CATALOG,
				differentSession -> {
					final SealedEntitySchema theSchema = differentSession.getEntitySchema(Entities.PRODUCT).orElseThrow();
					assertFalse(theSchema.isWithGeneratedPrimaryKey());
					assertFalse(theSchema.getAttributes().isEmpty());
				}
			);
		});
	}

	@Test
	void shouldAcceptNullForNonNullableLocalizedAttributeWhenEntityLocaleIsMissing() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized())
					.withAttribute(ATTRIBUTE_DESCRIPTION, String.class, whichIs -> whichIs.localized())
					.updateVia(session);

				session
					.createNewEntity(Entities.PRODUCT, 1)
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "A")
					.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH, "A")
					.setAttribute(ATTRIBUTE_NAME, Locale.FRENCH, "B")
					.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.FRENCH, "B")
					.upsertVia(session);
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity product = session.getEntity(Entities.PRODUCT, 1, attributeContent(), dataInLocalesAll(), referenceContentAll())
					.orElseThrow();

				assertEquals("A", product.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH));
				assertEquals("A", product.getAttribute(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH));
				assertEquals("B", product.getAttribute(ATTRIBUTE_NAME, Locale.FRENCH));
				assertEquals("B", product.getAttribute(ATTRIBUTE_DESCRIPTION, Locale.FRENCH));
				assertEquals(2, product.getAllLocales().size());
			}
		);

		assertThrows(
			MandatoryAttributesNotProvidedException.class,
			() -> {
				evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getEntity(Entities.PRODUCT, 1, attributeContent(), dataInLocalesAll(), referenceContentAll())
							.orElseThrow()
							.openForWrite()
							.removeAttribute(ATTRIBUTE_DESCRIPTION, Locale.FRENCH)
							.upsertVia(session);
					}
				);
			}
		);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(Entities.PRODUCT, 1, attributeContent(), dataInLocalesAll(), referenceContentAll())
					.orElseThrow()
					.openForWrite()
					.removeAttribute(ATTRIBUTE_NAME, Locale.FRENCH)
					.removeAttribute(ATTRIBUTE_DESCRIPTION, Locale.FRENCH)
					.upsertVia(session);
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity product = session.getEntity(Entities.PRODUCT, 1, attributeContent(), dataInLocalesAll(), referenceContentAll())
					.orElseThrow();

				assertEquals("A", product.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH));
				assertEquals("A", product.getAttribute(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH));
				assertEquals(1, product.getAllLocales().size());
				assertEquals(Locale.ENGLISH, product.getAllLocales().iterator().next());
			}
		);
	}

	@Test
	void shouldFailToSetNonNullableAttributeToNull() {
		try {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.updateEntitySchema(
						session
							.defineEntitySchema(Entities.PRODUCT)
							.withAttribute(ATTRIBUTE_EAN, String.class)
							.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized())
							.withAttribute(ATTRIBUTE_DESCRIPTION, String.class, whichIs -> whichIs.localized().nullable())
							.withReferenceTo(
								Entities.BRAND, Entities.BRAND, Cardinality.EXACTLY_ONE,
								thatIs -> thatIs
									.withAttribute(ATTRIBUTE_BRAND_EAN, String.class)
									.withAttribute(ATTRIBUTE_BRAND_NAME, String.class, whichIs -> whichIs.localized())
									.withAttribute(ATTRIBUTE_BRAND_DESCRIPTION, String.class, whichIs -> whichIs.localized().nullable())
							)
					);

					final EntityBuilder product = session
						.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH, "A")
						.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.GERMAN, "B")
						.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.FRENCH, "C")
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "D")
						.setReference(
							Entities.BRAND, 1,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_BRAND_DESCRIPTION, Locale.ENGLISH, "A")
								.setAttribute(ATTRIBUTE_BRAND_DESCRIPTION, Locale.GERMAN, "B")
								.setAttribute(ATTRIBUTE_BRAND_DESCRIPTION, Locale.FRENCH, "C")
								.setAttribute(ATTRIBUTE_BRAND_NAME, Locale.ENGLISH, "D")
						);
					session.upsertEntity(product);
				}
			);

			fail("MandatoryAttributesNotProvidedException was expected to be thrown!");

		} catch (MandatoryAttributesNotProvidedException ex) {
			assertEquals(
				"Entity `PRODUCT` requires these attributes to be non-null, but they are missing: `ean`.\n" +
					"Entity `PRODUCT` requires these localized attributes to be specified for all localized versions of the entity, but values for some locales are missing: `name` in locales: `de`, `fr`.\n" +
					"Entity `PRODUCT` reference `BRAND` requires these attributes to be non-null, but they are missing: `brandEan`.\n" +
					"Entity `PRODUCT` reference `BRAND` requires these localized attributes to be specified for all localized versions of the entity, but values for some locales are missing: `brandName` in locales: `de`, `fr`.",
				ex.getMessage()
			);
		}
	}

	@Test
	void shouldFailToSetNonNullableAttributeToNullOnExistingEntity() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withAttribute(ATTRIBUTE_EAN, String.class)
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized())
					.withAttribute(ATTRIBUTE_DESCRIPTION, String.class, whichIs -> whichIs.localized().nullable())
					.withReferenceTo(
						Entities.BRAND, Entities.BRAND, Cardinality.EXACTLY_ONE,
						thatIs -> thatIs
							.withAttribute(ATTRIBUTE_BRAND_EAN, String.class)
							.withAttribute(ATTRIBUTE_BRAND_NAME, String.class, whichIs -> whichIs.localized())
							.withAttribute(ATTRIBUTE_BRAND_DESCRIPTION, String.class, whichIs -> whichIs.localized().nullable())
					)
					.updateVia(session);

				final EntityBuilder product = session
					.createNewEntity(Entities.PRODUCT, 1)
					.setAttribute(ATTRIBUTE_EAN, "1")
					.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH, "A")
					.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.GERMAN, "B")
					.setAttribute(ATTRIBUTE_DESCRIPTION, Locale.FRENCH, "C")
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "D")
					.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "E")
					.setAttribute(ATTRIBUTE_NAME, Locale.FRENCH, "F")
					.setReference(
						Entities.BRAND, 1,
						whichIs -> whichIs
							.setAttribute(ATTRIBUTE_BRAND_EAN, "1")
							.setAttribute(ATTRIBUTE_BRAND_DESCRIPTION, Locale.ENGLISH, "A")
							.setAttribute(ATTRIBUTE_BRAND_DESCRIPTION, Locale.GERMAN, "B")
							.setAttribute(ATTRIBUTE_BRAND_DESCRIPTION, Locale.FRENCH, "C")
							.setAttribute(ATTRIBUTE_BRAND_NAME, Locale.ENGLISH, "D")
							.setAttribute(ATTRIBUTE_BRAND_NAME, Locale.GERMAN, "E")
							.setAttribute(ATTRIBUTE_BRAND_NAME, Locale.FRENCH, "F")
					);
				session.upsertEntity(product);
			}
		);

		try {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.getEntity(Entities.PRODUCT, 1, attributeContent(), dataInLocalesAll(), referenceContentAll())
						.orElseThrow()
						.openForWrite()
						.removeAttribute(ATTRIBUTE_EAN)
						.removeAttribute(ATTRIBUTE_NAME, Locale.GERMAN)
						.removeAttribute(ATTRIBUTE_NAME, Locale.FRENCH)
						.setReference(
							Entities.BRAND, 1,
							whichIs -> whichIs
								.removeAttribute(ATTRIBUTE_BRAND_EAN)
								.removeAttribute(ATTRIBUTE_BRAND_NAME, Locale.GERMAN)
								.removeAttribute(ATTRIBUTE_BRAND_NAME, Locale.FRENCH)
						)
						.upsertVia(session);
				}
			);

			fail("MandatoryAttributesNotProvidedException was expected to be thrown!");

		} catch (MandatoryAttributesNotProvidedException ex) {
			assertEquals(
				"Entity `PRODUCT` requires these attributes to be non-null, but they are missing: `ean`.\n" +
					"Entity `PRODUCT` requires these localized attributes to be specified for all localized versions of the entity, but values for some locales are missing: `name` in locales: `de`, `fr`.\n" +
					"Entity `PRODUCT` reference `BRAND` requires these attributes to be non-null, but they are missing: `brandEan`.\n" +
					"Entity `PRODUCT` reference `BRAND` requires these localized attributes to be specified for all localized versions of the entity, but values for some locales are missing: `brandName` in locales: `de`, `fr`.",
				ex.getMessage()
			);
		}
	}

	@Test
	void shouldAllowToCreateTwoUniqueAttributes() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.updateEntitySchema(
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().unique())
				);

				session
					.createNewEntity(Entities.PRODUCT, 1)
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "A")
					.upsertVia(session);

				session
					.createNewEntity(Entities.PRODUCT, 2)
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "B")
					.upsertVia(session);
			}
		);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertTrue(session.queryOneEntityReference(query(collection(Entities.PRODUCT), filterBy(attributeEquals(ATTRIBUTE_NAME, "A")))).isPresent());
				assertTrue(session.queryOneEntityReference(query(collection(Entities.PRODUCT), filterBy(attributeEquals(ATTRIBUTE_NAME, "B")))).isPresent());
				assertFalse(session.queryOneEntityReference(query(collection(Entities.PRODUCT), filterBy(attributeEquals(ATTRIBUTE_NAME, "V")))).isPresent());
			}
		);
	}

	@Test
	void shouldFailToCreateTwoNonUniqueAttributes() {
		try {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.updateEntitySchema(
						session
							.defineEntitySchema(Entities.PRODUCT)
							.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().unique())
					);

					session
						.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "A")
						.upsertVia(session);
					session
						.createNewEntity(Entities.PRODUCT, 2)
						.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "A")
						.upsertVia(session);
				}
			);

			fail("UniqueValueViolationException was expected to be thrown!");

		} catch (UniqueValueViolationException ex) {
			assertEquals(
				"Unique constraint violation: attribute `name` value A` is already present for entity `PRODUCT` (existing entity PK: 1, newly inserted  entity PK: 2)!",
				ex.getMessage()
			);
		}
	}

	@Test
	void shouldAllowToCreateTwoLocaleSpecificUniqueAttributes() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.updateEntitySchema(
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().uniqueWithinLocale())
				);

				session
					.createNewEntity(Entities.PRODUCT, 1)
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "A")
					.upsertVia(session);

				session
					.createNewEntity(Entities.PRODUCT, 2)
					.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "A")
					.upsertVia(session);
			}
		);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertTrue(session.queryOneEntityReference(query(collection(Entities.PRODUCT), filterBy(attributeEquals(ATTRIBUTE_NAME, "A"), entityLocaleEquals(Locale.ENGLISH)))).isPresent());
				assertTrue(session.queryOneEntityReference(query(collection(Entities.PRODUCT), filterBy(attributeEquals(ATTRIBUTE_NAME, "A"), entityLocaleEquals(Locale.GERMAN)))).isPresent());
				assertFalse(session.queryOneEntityReference(query(collection(Entities.PRODUCT), filterBy(attributeEquals(ATTRIBUTE_NAME, "A"), entityLocaleEquals(Locale.FRENCH)))).isPresent());
			}
		);
	}

	@Test
	void shouldFailToCreateTwoLocaleSpecificNonUniqueAttributes() {
		try {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.updateEntitySchema(
						session
							.defineEntitySchema(Entities.PRODUCT)
							.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().uniqueWithinLocale())
					);

					session
						.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "A")
						.upsertVia(session);
					session
						.createNewEntity(Entities.PRODUCT, 2)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "A")
						.upsertVia(session);
				}
			);

			fail("UniqueValueViolationException was expected to be thrown!");

		} catch (UniqueValueViolationException ex) {
			assertEquals(
				"Unique constraint violation: attribute `name` value A` in locale `en` is already present for entity `PRODUCT` (existing entity PK: 1, newly inserted  entity PK: 2)!",
				ex.getMessage()
			);
		}
	}

	@Test
	void shouldAllowToCreateTwoUniqueGloballyAttributes() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().uniqueGlobally())
					.updateVia(session);

				session
					.defineEntitySchema(Entities.CATEGORY)
					.withGlobalAttribute(ATTRIBUTE_NAME)
					.updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withGlobalAttribute(ATTRIBUTE_NAME)
					.updateVia(session);

				session
					.createNewEntity(Entities.PRODUCT, 1)
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "A")
					.upsertVia(session);

				session
					.createNewEntity(Entities.CATEGORY, 2)
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "B")
					.upsertVia(session);
			}
		);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertTrue(session.queryOneEntityReference(query(filterBy(attributeEquals(ATTRIBUTE_NAME, "A")))).isPresent());
				assertTrue(session.queryOneEntityReference(query(filterBy(attributeEquals(ATTRIBUTE_NAME, "B")))).isPresent());
				assertFalse(session.queryOneEntityReference(query(filterBy(attributeEquals(ATTRIBUTE_NAME, "V")))).isPresent());
			}
		);
	}

	@Test
	void shouldFailToCreateTwoNonUniqueGloballyAttributes() {
		try {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getCatalogSchema()
						.openForWrite()
						.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().uniqueGlobally())
						.updateVia(session);

					session
						.defineEntitySchema(Entities.CATEGORY)
						.withGlobalAttribute(ATTRIBUTE_NAME)
						.updateVia(session);

					session
						.defineEntitySchema(Entities.PRODUCT)
						.withGlobalAttribute(ATTRIBUTE_NAME)
						.updateVia(session);

					session
						.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "A")
						.upsertVia(session);

					session
						.createNewEntity(Entities.CATEGORY, 2)
						.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "A")
						.upsertVia(session);
				}
			);

			fail("UniqueValueViolationException was expected to be thrown!");

		} catch (UniqueValueViolationException ex) {
			assertEquals(
				"Unique constraint violation: attribute `name` value A` is already present for entity `PRODUCT` (existing entity PK: 1, newly inserted `CATEGORY` entity PK: 2)!",
				ex.getMessage()
			);
		}
	}

	@Test
	void shouldAllowToCreateTwoLocaleSpecificUniqueGloballyAttributes() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().uniqueGloballyWithinLocale())
					.updateVia(session);

				session
					.defineEntitySchema(Entities.CATEGORY)
					.withGlobalAttribute(ATTRIBUTE_NAME)
					.updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withGlobalAttribute(ATTRIBUTE_NAME)
					.updateVia(session);

				session
					.createNewEntity(Entities.PRODUCT, 1)
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "A")
					.upsertVia(session);

				session
					.createNewEntity(Entities.CATEGORY, 2)
					.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "A")
					.upsertVia(session);
			}
		);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertTrue(session.queryOneEntityReference(query(filterBy(attributeEquals(ATTRIBUTE_NAME, "A"), entityLocaleEquals(Locale.ENGLISH)))).isPresent());
				assertTrue(session.queryOneEntityReference(query(filterBy(attributeEquals(ATTRIBUTE_NAME, "A"), entityLocaleEquals(Locale.GERMAN)))).isPresent());
				assertFalse(session.queryOneEntityReference(query(filterBy(attributeEquals(ATTRIBUTE_NAME, "A"), entityLocaleEquals(Locale.FRENCH)))).isPresent());
			}
		);
	}

	@Test
	void shouldFailToCreateTwoLocaleSpecificNonUniqueGloballyAttributes() {
		try {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getCatalogSchema()
						.openForWrite()
						.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().uniqueGloballyWithinLocale())
						.updateVia(session);

					session
						.defineEntitySchema(Entities.CATEGORY)
						.withGlobalAttribute(ATTRIBUTE_NAME)
						.updateVia(session);

					session
						.defineEntitySchema(Entities.PRODUCT)
						.withGlobalAttribute(ATTRIBUTE_NAME)
						.updateVia(session);

					session
						.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "A")
						.upsertVia(session);

					session
						.createNewEntity(Entities.CATEGORY, 2)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "A")
						.upsertVia(session);
				}
			);

			fail("UniqueValueViolationException was expected to be thrown!");

		} catch (UniqueValueViolationException ex) {
			assertEquals(
				"Unique constraint violation: attribute `name` value A` in locale `en` is already present for entity `PRODUCT` (existing entity PK: 1, newly inserted `CATEGORY` entity PK: 2)!",
				ex.getMessage()
			);
		}
	}

	@Test
	void shouldMarkPredecessorAsSortable() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withEntitySchema(
						"whatever",
						whichIs -> whichIs.withAttribute("whatever", Predecessor.class, AttributeSchemaEditor::sortable)
					)
					.updateVia(session);
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertTrue(
					session.getEntitySchemaOrThrow("whatever").getAttribute("whatever").orElseThrow().isSortable()
				);
			}
		);
	}

	@Test
	void shouldFailToMarkPredecessorAsFilterable() {
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getCatalogSchema()
						.openForWrite()
						.withEntitySchema(
							"whatever",
							whichIs -> whichIs.withAttribute("whatever", Predecessor.class, AttributeSchemaEditor::filterable)
						)
						.updateVia(session);
				}
			)
		);
	}

	@Test
	void shouldFailToSetUpReferencedEntityPredecessorAsDirectAttribute() {
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getCatalogSchema()
						.openForWrite()
						.withEntitySchema(
							"whatever",
							whichIs -> whichIs.withAttribute("whatever", ReferencedEntityPredecessor.class)
						)
						.updateVia(session);
				}
			)
		);
	}

	@Test
	void shouldFailToMarkReferencedEntityPredecessorAsFilterable() {
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getCatalogSchema()
						.openForWrite()
						.withEntitySchema(
							"whatever",
							thatIs -> thatIs.withReferenceToEntity(
								"whatever", "whatever", Cardinality.ZERO_OR_MORE,
								whichIs -> whichIs.withAttribute("whatever", ReferencedEntityPredecessor.class, AttributeSchemaEditor::filterable)
							)
						)
						.updateVia(session);
				}
			)
		);
	}

	@Test
	void shouldFailToSetUpPredecessorAsAssociatedData() {
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getCatalogSchema()
						.openForWrite()
						.withEntitySchema(
							"whatever",
							whichIs -> whichIs.withAssociatedData("whatever", Predecessor.class)
						)
						.updateVia(session);
				}
			)
		);
	}

	@Test
	void shouldFailToSetUpReferencedEntityPredecessorAsAssociatedData() {
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getCatalogSchema()
						.openForWrite()
						.withEntitySchema(
							"whatever",
							whichIs -> whichIs.withAssociatedData("whatever", ReferencedEntityPredecessor.class)
						)
						.updateVia(session);
				}
			)
		);
	}

	@Test
	void shouldMarkCurrencyAsFilterable() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withEntitySchema(
						"whatever",
						whichIs -> whichIs.withAttribute("whatever", Currency.class, AttributeSchemaEditor::filterable)
					)
					.updateVia(session);
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertTrue(
					session.getEntitySchemaOrThrow("whatever").getAttribute("whatever").orElseThrow().isFilterable()
				);
			}
		);
	}

	@Test
	void shouldFailToSetNonNullableAssociatedDataToNull() {
		try {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withAssociatedData(ATTRIBUTE_EAN, String.class)
						.withAssociatedData(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized())
						.withAssociatedData(ATTRIBUTE_DESCRIPTION, String.class, whichIs -> whichIs.localized().nullable())
						.updateVia(session);

					session
						.createNewEntity(Entities.PRODUCT, 1)
						.setAssociatedData(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH, "A")
						.setAssociatedData(ATTRIBUTE_DESCRIPTION, Locale.GERMAN, "B")
						.setAssociatedData(ATTRIBUTE_DESCRIPTION, Locale.FRENCH, "C")
						.setAssociatedData(ATTRIBUTE_NAME, Locale.ENGLISH, "D")
						.upsertVia(session);
				}
			);

			fail("MandatoryAssociatedDataNotProvidedException was expected to be thrown!");

		} catch (MandatoryAssociatedDataNotProvidedException ex) {
			assertEquals(
				"Entity `PRODUCT` requires these associated data to be non-null, but they are missing: `ean`.\n" +
					"Entity `PRODUCT` requires these localized associated data to be specified for all localized versions " +
					"of the entity, but values for some locales are missing: `name` in locales: `de`, `fr`.",
				ex.getMessage()
			);
		}
	}

	@Test
	void shouldFailToSetNonNullableAssociatedDataToNullOnExistingEntity() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withAssociatedData(ATTRIBUTE_EAN, String.class)
					.withAssociatedData(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized())
					.withAssociatedData(ATTRIBUTE_DESCRIPTION, String.class, whichIs -> whichIs.localized().nullable())
					.updateVia(session);

				session
					.createNewEntity(Entities.PRODUCT, 1)
					.setAssociatedData(ATTRIBUTE_EAN, "1")
					.setAssociatedData(ATTRIBUTE_DESCRIPTION, Locale.ENGLISH, "A")
					.setAssociatedData(ATTRIBUTE_DESCRIPTION, Locale.GERMAN, "B")
					.setAssociatedData(ATTRIBUTE_DESCRIPTION, Locale.FRENCH, "C")
					.setAssociatedData(ATTRIBUTE_NAME, Locale.ENGLISH, "D")
					.setAssociatedData(ATTRIBUTE_NAME, Locale.GERMAN, "E")
					.setAssociatedData(ATTRIBUTE_NAME, Locale.FRENCH, "F")
					.upsertVia(session);
			}
		);

		try {

			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.getEntity(Entities.PRODUCT, 1, associatedDataContentAll(), dataInLocalesAll())
						.orElseThrow()
						.openForWrite()
						.removeAssociatedData(ATTRIBUTE_EAN)
						.removeAssociatedData(ATTRIBUTE_NAME, Locale.GERMAN)
						.removeAssociatedData(ATTRIBUTE_NAME, Locale.FRENCH)
						.upsertVia(session);
				}
			);

			fail("MandatoryAssociatedDataNotProvidedException was expected to be thrown!");

		} catch (MandatoryAssociatedDataNotProvidedException ex) {
			assertEquals(
				"Entity `PRODUCT` requires these associated data to be non-null, but they are missing: `ean`.\n" +
					"Entity `PRODUCT` requires these localized associated data to be specified for all localized versions " +
					"of the entity, but values for some locales are missing: `name` in locales: `de`, `fr`.",
				ex.getMessage()
			);
		}
	}

	@Test
	void shouldFailToViolateReferenceCardinalityExactlyZeroOrOne() {
		try {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceTo(Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE)
						.updateVia(session);

					session.createNewEntity(Entities.PRODUCT, 1)
						.setReference(Entities.BRAND, 1)
						.setReference(Entities.BRAND, 2)
						.upsertVia(session);
				}
			);

			fail("ReferenceCardinalityViolatedException is expected to be thrown");

		} catch (ReferenceCardinalityViolatedException ex) {
			assertEquals(
				"Expected reference cardinalities are violated in entity `PRODUCT`: reference `BRAND` is " +
					"expected to be `ZERO_OR_ONE` - but entity contains 2 references.",
				ex.getMessage()
			);
		}
	}

	@Test
	void shouldFailToViolateReferenceCardinalityExactlyZeroOrOneOnExistingEntity() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceTo(Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE)
					.updateVia(session);

				final EntityBuilder product = session.createNewEntity(Entities.PRODUCT, 1)
					.setReference(Entities.BRAND, 1);
				session.upsertEntity(product);
			}
		);

		try {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(Entities.PRODUCT, 1, referenceContentAll())
						.orElseThrow()
						.openForWrite()
						.setReference(Entities.BRAND, 2)
						.upsertVia(session);
				}
			);

			fail("ReferenceCardinalityViolatedException is expected to be thrown");

		} catch (ReferenceCardinalityViolatedException ex) {
			assertEquals(
				"Expected reference cardinalities are violated in entity `PRODUCT`: reference `BRAND` is " +
					"expected to be `ZERO_OR_ONE` - but entity contains 2 references.",
				ex.getMessage()
			);
		}
	}

	@Test
	void shouldFailToViolateReferenceCardinalityExactlyOne() {
		try {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceTo(Entities.BRAND, Entities.BRAND, Cardinality.EXACTLY_ONE)
						.updateVia(session);

					final EntityBuilder product = session.createNewEntity(Entities.PRODUCT, 1);
					session.upsertEntity(product);
				}
			);

			fail("ReferenceCardinalityViolatedException is expected to be thrown");

		} catch (ReferenceCardinalityViolatedException ex) {
			assertEquals(
				"Expected reference cardinalities are violated in entity `PRODUCT`: reference `BRAND` is " +
					"expected to be `EXACTLY_ONE` - but entity contains 0 references.",
				ex.getMessage()
			);
		}
	}

	@Test
	void shouldFailToViolateReferenceCardinalityExactlyOneOnExistingEntity() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceTo(Entities.BRAND, Entities.BRAND, Cardinality.EXACTLY_ONE)
					.updateVia(session);

				final EntityBuilder product = session.createNewEntity(Entities.PRODUCT, 1)
					.setReference(Entities.BRAND, 1);
				session.upsertEntity(product);
			}
		);

		try {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(Entities.PRODUCT, 1, referenceContentAll())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.BRAND, 1)
						.upsertVia(session);
				}
			);

			fail("ReferenceCardinalityViolatedException is expected to be thrown");

		} catch (ReferenceCardinalityViolatedException ex) {
			assertEquals(
				"Expected reference cardinalities are violated in entity `PRODUCT`: reference `BRAND` is " +
					"expected to be `EXACTLY_ONE` - but entity contains 0 references.",
				ex.getMessage()
			);
		}
	}

	@Test
	void shouldFailToViolateReferenceCardinalityExactlyOneOrMore() {
		try {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceTo(Entities.BRAND, Entities.BRAND, Cardinality.ONE_OR_MORE)
						.updateVia(session);

					final EntityBuilder product = session.createNewEntity(Entities.PRODUCT, 1);
					session.upsertEntity(product);
				}
			);

			fail("ReferenceCardinalityViolatedException is expected to be thrown");

		} catch (ReferenceCardinalityViolatedException ex) {
			assertEquals(
				"Expected reference cardinalities are violated in entity `PRODUCT`: reference `BRAND` is " +
					"expected to be `ONE_OR_MORE` - but entity contains 0 references.",
				ex.getMessage()
			);
		}
	}

	@Test
	void shouldFailToViolateReferenceCardinalityExactlyOneOrMoreOnExistingEntity() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceTo(Entities.BRAND, Entities.BRAND, Cardinality.ONE_OR_MORE)
					.updateVia(session);

				final EntityBuilder product = session.createNewEntity(Entities.PRODUCT, 1)
					.setReference(Entities.BRAND, 1);
				session.upsertEntity(product);
			}
		);

		try {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.getEntity(Entities.PRODUCT, 1, referenceContentAll())
						.orElseThrow()
						.openForWrite()
						.removeReference(Entities.BRAND, 1)
						.upsertVia(session);
				}
			);

			fail("ReferenceCardinalityViolatedException is expected to be thrown");

		} catch (ReferenceCardinalityViolatedException ex) {
			assertEquals(
				"Expected reference cardinalities are violated in entity `PRODUCT`: reference `BRAND` is " +
					"expected to be `ONE_OR_MORE` - but entity contains 0 references.",
				ex.getMessage()
			);
		}
	}

	@Test
	void shouldChangePriceSellability() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withPrice()
					.updateVia(session);

				final EntityBuilder product = session.createNewEntity(Entities.PRODUCT, 1)
					.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE)
					.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
					.setPrice(2, PRICE_LIST_BASIC, CURRENCY_EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, false);

				session.upsertEntity(product);

				assertEquals(1, countProductsWithPriceListCurrencyCombination(session, PRICE_LIST_BASIC, CURRENCY_CZK));
				assertEquals(0, countProductsWithPriceListCurrencyCombination(session, PRICE_LIST_BASIC, CURRENCY_EUR));

				session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, false)
					.setPrice(2, PRICE_LIST_BASIC, CURRENCY_EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
					.upsertVia(session);

				assertEquals(0, countProductsWithPriceListCurrencyCombination(session, PRICE_LIST_BASIC, CURRENCY_CZK));
				assertEquals(1, countProductsWithPriceListCurrencyCombination(session, PRICE_LIST_BASIC, CURRENCY_EUR));
			}
		);
	}

	@Test
	void shouldRemoveDeepStructureOfHierarchicalEntity() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.CATEGORY)
					.withHierarchy()
					.updateVia(session);

				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1));
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 2));
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 3).setParent(1));
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 4).setParent(1));
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 5).setParent(3));
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 6).setParent(3));

				assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6}, getAllCategories(session));
				session.deleteEntityAndItsHierarchy(Entities.CATEGORY, 3);
				assertArrayEquals(new int[]{1, 2, 4}, getAllCategories(session));
				session.deleteEntityAndItsHierarchy(Entities.CATEGORY, 1);
				assertArrayEquals(new int[]{2}, getAllCategories(session));
			}
		);
	}

	@Test
	void shouldIndexAllAttributesAndPricesAfterReferenceToHierarchicalEntityIsSet() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.CATEGORY)
					.withHierarchy()
					.updateVia(session);

				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1));

				session
					.defineEntitySchema(Entities.BRAND);

				session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withAttribute(ATTRIBUTE_EAN, String.class, thatIs -> thatIs.unique().sortable())
					.withPrice()
					.withReferenceToEntity(
						Entities.CATEGORY,
						Entities.CATEGORY,
						Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning().faceted()
					)
					.withReferenceToEntity(
						Entities.BRAND,
						Entities.BRAND,
						Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning().faceted()
					)
					.updateVia(session);

				final EntityBuilder product = session.createNewEntity(Entities.PRODUCT, 1)
					.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE)
					.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
					.setPrice(2, PRICE_LIST_BASIC, CURRENCY_EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, false)
					.setAttribute(ATTRIBUTE_EAN, "123_ABC");

				// first create entity without references
				session.upsertEntity(product);

				// check there are no specialized entity indexes
				final CatalogContract catalog = evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
				final EntityCollectionContract productCollection = catalog.getCollectionForEntity(Entities.PRODUCT)
					.orElseThrow();

				assertNull(getReferencedEntityIndex(productCollection, Entities.CATEGORY, 1));
				assertNull(getReferencedEntityIndex(productCollection, Entities.BRAND, 1));

				// load it and add references
				session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setReference(Entities.BRAND, 1)
					.setReference(Entities.CATEGORY, 1)
					.upsertVia(session);

				// assert data from global index were propagated
				assertNotNull(getReferencedEntityIndex(productCollection, Entities.CATEGORY, 1));
				final EntityIndex categoryIndex = getReferencedEntityIndex(productCollection, Entities.CATEGORY, 1);
				assertDataWasPropagated(categoryIndex, 1);

				assertNotNull(getReferencedEntityIndex(productCollection, Entities.BRAND, 1));
				final EntityIndex brandIndex = getReferencedEntityIndex(productCollection, Entities.BRAND, 1);
				assertDataWasPropagated(brandIndex, 1);

				// load it and remove references
				session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.removeReference(Entities.BRAND, 1)
					.removeReference(Entities.CATEGORY, 1)
					.upsertVia(session);

				// assert indexes were emptied and removed
				assertNull(getReferencedEntityIndex(productCollection, Entities.CATEGORY, 1));
				assertNull(getReferencedEntityIndex(productCollection, Entities.BRAND, 1));
			}
		);
	}

	@Test
	void shouldAvoidCreatingIndexesForNonIndexedReferences() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.CATEGORY)
					.withHierarchy()
					.updateVia(session);

				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1));

				session.defineEntitySchema(Entities.BRAND);

				session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withAttribute(ATTRIBUTE_EAN, String.class, thatIs -> thatIs.unique().sortable())
					.withPrice()
					.withReferenceToEntity(
						Entities.CATEGORY,
						Entities.CATEGORY,
						Cardinality.ZERO_OR_MORE,
						thatIs -> thatIs.withAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class))
					.withReferenceToEntity(
						Entities.BRAND,
						Entities.BRAND,
						Cardinality.ZERO_OR_ONE
					)
					.updateVia(session);

				final EntityBuilder product = session.createNewEntity(Entities.PRODUCT, 1)
					.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE)
					.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
					.setPrice(2, PRICE_LIST_BASIC, CURRENCY_EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, false)
					.setAttribute(ATTRIBUTE_EAN, "123_ABC")
					.setReference(Entities.BRAND, 1)
					.setReference(Entities.CATEGORY, 1, thatIs -> thatIs.setAttribute(ATTRIBUTE_CATEGORY_PRIORITY, 1L));

				// first create entity without references
				session.upsertEntity(product);

				// check there are no specialized entity indexes
				final CatalogContract catalog = evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
				final EntityCollectionContract productCollection = catalog.getCollectionForEntity(Entities.PRODUCT)
					.orElseThrow();

				assertNull(getReferencedEntityIndex(productCollection, Entities.CATEGORY, 1));
				assertNull(getReferencedEntityIndex(productCollection, Entities.BRAND, 1));
			}
		);
	}

	@Test
	void shouldRegisterNonLocalizedCompoundForEachCreatedEntity() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final String attributeCodeEan = ATTRIBUTE_CODE + StringUtils.capitalize(ATTRIBUTE_EAN);
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.nullable())
					.withAttribute(ATTRIBUTE_EAN, String.class, whichIs -> whichIs.nullable())
					.withSortableAttributeCompound(
						attributeCodeEan,
						AttributeElement.attributeElement(ATTRIBUTE_CODE, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
						AttributeElement.attributeElement(ATTRIBUTE_EAN, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
					)
					.updateVia(session);

				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 1));

				// check there are no specialized entity indexes
				final CatalogContract catalog = evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
				final EntityCollectionContract productCollection = catalog.getCollectionForEntity(Entities.PRODUCT)
					.orElseThrow();

				// this function allows us to repeatedly verify index contents
				final Consumer<Serializable[]> verifyIndexContents = expected -> {
					final EntityIndex globalIndex = getGlobalIndex(productCollection);
					assertNotNull(globalIndex);

					final SortIndex sortIndex = globalIndex.getSortIndex(new AttributeKey(attributeCodeEan));
					if (ArrayUtils.isEmptyOrItsValuesNull(expected)) {
						assertNull(sortIndex);
					} else {
						assertNotNull(sortIndex);
						assertArrayEquals(new int[]{1}, sortIndex.getRecordsEqualTo(expected).getArray());
					}
				};

				verifyIndexContents.accept(new Serializable[]{null, null});

				session.getEntity(Entities.PRODUCT, 1, attributeContentAll())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_EAN, "123")
					.setAttribute(ATTRIBUTE_CODE, "ABC")
					.upsertVia(session);

				verifyIndexContents.accept(new Serializable[]{"ABC", "123"});
			}
		);
	}

	@Test
	void shouldUpdateNonLocalizedEntityCompoundsOnChange() {
		shouldRegisterNonLocalizedCompoundForEachCreatedEntity();

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final String attributeCodeEan = ATTRIBUTE_CODE + StringUtils.capitalize(ATTRIBUTE_EAN);
				session.getEntity(Entities.PRODUCT, 1, attributeContentAll())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_EAN, "578")
					.setAttribute(ATTRIBUTE_CODE, "Whatever")
					.upsertVia(session);

				// check there are no specialized entity indexes
				final CatalogContract catalog = evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
				final EntityCollectionContract productCollection = catalog.getCollectionForEntity(Entities.PRODUCT)
					.orElseThrow();


				final EntityIndex globalIndex = getGlobalIndex(productCollection);
				assertNotNull(globalIndex);

				final SortIndex sortIndex = globalIndex.getSortIndex(new AttributeKey(attributeCodeEan));
				assertNotNull(sortIndex);

				assertTrue(sortIndex.getRecordsEqualTo(new Serializable[]{"ABC", "123"}).isEmpty());
				assertArrayEquals(new int[]{1}, sortIndex.getRecordsEqualTo(new Serializable[]{"Whatever", "578"}).getArray());
			}
		);
	}

	@Test
	void shouldDropNonLocalizedCompoundForEachRemovedEntity() {
		shouldRegisterNonLocalizedCompoundForEachCreatedEntity();

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final String attributeCodeEan = ATTRIBUTE_CODE + StringUtils.capitalize(ATTRIBUTE_EAN);

				session.deleteEntity(Entities.PRODUCT, 1);

				// check there are no specialized entity indexes
				final CatalogContract catalog = evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
				final EntityCollectionContract productCollection = catalog.getCollectionForEntity(Entities.PRODUCT)
					.orElseThrow();

				final EntityIndex globalIndex = getGlobalIndex(productCollection);
				assertNotNull(globalIndex);

				final SortIndex sortIndex = globalIndex.getSortIndex(new AttributeKey(attributeCodeEan));
				assertNull(sortIndex);
			}
		);
	}

	@Test
	void shouldRegisterLocalizedCompoundForEachCreatedEntityLanguage() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final String attributeCodeEan = ATTRIBUTE_CODE + StringUtils.capitalize(ATTRIBUTE_EAN);
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.nullable())
					.withAttribute(ATTRIBUTE_EAN, String.class, whichIs -> whichIs.localized().nullable())
					.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().nullable())
					.withSortableAttributeCompound(
						attributeCodeEan,
						AttributeElement.attributeElement(ATTRIBUTE_CODE, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
						AttributeElement.attributeElement(ATTRIBUTE_EAN, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
					)
					.updateVia(session);

				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 1));

				// check there are no specialized entity indexes
				final CatalogContract catalog = evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
				final EntityCollectionContract productCollection = catalog.getCollectionForEntity(Entities.PRODUCT)
					.orElseThrow();

				final EntityIndex globalIndex = getGlobalIndex(productCollection);
				assertNotNull(globalIndex);

				assertNull(globalIndex.getSortIndex(new AttributeKey(attributeCodeEan)));
				assertNull(globalIndex.getSortIndex(new AttributeKey(attributeCodeEan, Locale.ENGLISH)));

				session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), dataInLocalesAll())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "The product")
					.setAttribute(ATTRIBUTE_CODE, "ABC")
					.upsertVia(session);

				final EntityIndex updatedGlobalIndex = getGlobalIndex(productCollection);
				assertNotNull(updatedGlobalIndex);

				final SortIndex englishSortIndex = updatedGlobalIndex.getSortIndex(new AttributeKey(attributeCodeEan, Locale.ENGLISH));
				assertNotNull(englishSortIndex);

				assertArrayEquals(new int[]{1}, englishSortIndex.getRecordsEqualTo(new Serializable[]{"ABC", null}).getArray());

				session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), dataInLocalesAll())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_EAN, Locale.CANADA, "123")
					.setAttribute(ATTRIBUTE_CODE, "ABC")
					.upsertVia(session);

				final EntityIndex updatedGlobalIndexAgain = getGlobalIndex(productCollection);
				assertNotNull(updatedGlobalIndexAgain);

				final SortIndex englishSortIndexAgain = updatedGlobalIndexAgain.getSortIndex(new AttributeKey(attributeCodeEan, Locale.ENGLISH));
				assertNotNull(englishSortIndexAgain);

				assertArrayEquals(new int[]{1}, englishSortIndexAgain.getRecordsEqualTo(new Serializable[]{"ABC", null}).getArray());

				final SortIndex canadianSortIndex = updatedGlobalIndexAgain.getSortIndex(new AttributeKey(attributeCodeEan, Locale.CANADA));
				assertNotNull(canadianSortIndex);

				assertArrayEquals(new int[]{1}, canadianSortIndex.getRecordsEqualTo(new Serializable[]{"ABC", "123"}).getArray());

				final SealedEntity finalEntity = session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), dataInLocalesAll())
					.orElseThrow();

				final Set<Locale> finalAttributeLocales = finalEntity.getAttributeLocales();
				assertTrue(finalAttributeLocales.contains(Locale.ENGLISH));
				assertTrue(finalAttributeLocales.contains(Locale.CANADA));
			}
		);
	}

	@Test
	void shouldUpdateLocalizedEntityCompoundsOnChange() {
		shouldRegisterLocalizedCompoundForEachCreatedEntityLanguage();

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final String attributeCodeEan = ATTRIBUTE_CODE + StringUtils.capitalize(ATTRIBUTE_EAN);
				session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), dataInLocalesAll())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_EAN, Locale.CANADA, "578")
					.setAttribute(ATTRIBUTE_CODE, "Whatever")
					.upsertVia(session);

				// check there are no specialized entity indexes
				final CatalogContract catalog = evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
				final EntityCollectionContract productCollection = catalog.getCollectionForEntity(Entities.PRODUCT)
					.orElseThrow();


				final EntityIndex globalIndex = getGlobalIndex(productCollection);
				assertNotNull(globalIndex);

				final SortIndex sortIndex = globalIndex.getSortIndex(new AttributeKey(attributeCodeEan, Locale.CANADA));
				assertNotNull(sortIndex);

				assertTrue(sortIndex.getRecordsEqualTo(new Serializable[]{"ABC", "123"}).isEmpty());
				assertArrayEquals(new int[]{1}, sortIndex.getRecordsEqualTo(new Serializable[]{"Whatever", "578"}).getArray());
			}
		);
	}

	@Test
	void shouldDropLocalizedCompoundForEachDroppedEntityLanguage() {
		shouldRegisterLocalizedCompoundForEachCreatedEntityLanguage();

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final String attributeCodeEan = ATTRIBUTE_CODE + StringUtils.capitalize(ATTRIBUTE_EAN);

				session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), dataInLocalesAll())
					.orElseThrow()
					.openForWrite()
					.removeAttribute(ATTRIBUTE_NAME, Locale.ENGLISH)
					.setAttribute(ATTRIBUTE_CODE, "ABC")
					.upsertVia(session);

				// check there are no specialized entity indexes
				final CatalogContract catalog = evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
				final EntityCollectionContract productCollection = catalog.getCollectionForEntity(Entities.PRODUCT)
					.orElseThrow();

				final EntityIndex globalIndex = getGlobalIndex(productCollection);
				assertNotNull(globalIndex);
				assertNull(globalIndex.getSortIndex(new AttributeKey(attributeCodeEan)));

				final SortIndex englishSortIndex = globalIndex.getSortIndex(new AttributeKey(attributeCodeEan, Locale.ENGLISH));
				assertNull(englishSortIndex);

				final SortIndex canadianSortIndex = globalIndex.getSortIndex(new AttributeKey(attributeCodeEan, Locale.CANADA));
				assertNotNull(canadianSortIndex);
				assertArrayEquals(new int[]{1}, canadianSortIndex.getRecordsEqualTo(new Serializable[]{"ABC", "123"}).getArray());

				session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), dataInLocalesAll())
					.orElseThrow()
					.openForWrite()
					.removeAttribute(ATTRIBUTE_EAN, Locale.CANADA)
					.setAttribute(ATTRIBUTE_CODE, "ABC")
					.upsertVia(session);

				final EntityIndex updatedGlobalIndex = getGlobalIndex(productCollection);
				assertNotNull(updatedGlobalIndex);
				assertNull(updatedGlobalIndex.getSortIndex(new AttributeKey(attributeCodeEan)));
				assertNull(updatedGlobalIndex.getSortIndex(new AttributeKey(attributeCodeEan, Locale.ENGLISH)));
				assertNull(updatedGlobalIndex.getSortIndex(new AttributeKey(attributeCodeEan, Locale.CANADA)));
			}
		);
	}

	@Test
	void shouldRegisterEntityCompoundsInReducedIndexesOnTheirCreation() {
		shouldRegisterNonLocalizedCompoundForEachCreatedEntity();

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {

				session
					.defineEntitySchema(Entities.CATEGORY)
					.withHierarchy()
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 10));
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 11));

				session
					.defineEntitySchema(Entities.BRAND)
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 20));
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 21));

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE, whichIs -> whichIs.indexedForFilteringAndPartitioning())
					.withReferenceToEntity(Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE, whichIs -> whichIs.indexedForFilteringAndPartitioning())
					.updateVia(session);

				final String attributeCodeEan = ATTRIBUTE_CODE + StringUtils.capitalize(ATTRIBUTE_EAN);
				session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), referenceContentAll())
					.orElseThrow()
					.openForWrite()
					.setReference(Entities.CATEGORY, 10)
					.setReference(Entities.CATEGORY, 11)
					.setReference(Entities.BRAND, 20)
					.setReference(Entities.BRAND, 21)
					.upsertVia(session);

				// check there are no specialized entity indexes
				final CatalogContract catalog = evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
				final EntityCollectionContract productCollection = catalog.getCollectionForEntity(Entities.PRODUCT)
					.orElseThrow();

				final Consumer<EntityIndex> verifyIndexContents = entityIndex -> {
					assertNotNull(entityIndex);

					final SortIndex sortIndex = entityIndex.getSortIndex(new AttributeKey(attributeCodeEan));
					assertNotNull(sortIndex);

					assertArrayEquals(new int[]{1}, sortIndex.getRecordsEqualTo(new Serializable[]{"ABC", "123"}).getArray());
				};

				verifyIndexContents.accept(getReferencedEntityIndex(productCollection, Entities.CATEGORY, 10));
				verifyIndexContents.accept(getReferencedEntityIndex(productCollection, Entities.CATEGORY, 11));
				verifyIndexContents.accept(getReferencedEntityIndex(productCollection, Entities.BRAND, 20));
				verifyIndexContents.accept(getReferencedEntityIndex(productCollection, Entities.BRAND, 21));
			}
		);
	}

	@Test
	void shouldDropEntityCompoundsInReducedIndexesOnTheirRemoval() {
		shouldRegisterEntityCompoundsInReducedIndexesOnTheirCreation();

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final String attributeCodeEan = ATTRIBUTE_CODE + StringUtils.capitalize(ATTRIBUTE_EAN);
				session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), referenceContentAll())
					.orElseThrow()
					.openForWrite()
					.removeReference(Entities.CATEGORY, 10)
					.removeReference(Entities.BRAND, 20)
					.upsertVia(session);

				// check there are no specialized entity indexes
				final CatalogContract catalog = evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
				final EntityCollectionContract productCollection = catalog.getCollectionForEntity(Entities.PRODUCT)
					.orElseThrow();

				final Consumer<EntityIndex> verifyIndexContentsContains = entityIndex -> {
					assertNotNull(entityIndex);

					final SortIndex sortIndex = entityIndex.getSortIndex(new AttributeKey(attributeCodeEan));
					assertNotNull(sortIndex);

					assertArrayEquals(new int[]{1}, sortIndex.getRecordsEqualTo(new Serializable[]{"ABC", "123"}).getArray());
				};

				assertNull(getReferencedEntityIndex(productCollection, Entities.CATEGORY, 10));
				verifyIndexContentsContains.accept(getReferencedEntityIndex(productCollection, Entities.CATEGORY, 11));
				assertNull(getReferencedEntityIndex(productCollection, Entities.BRAND, 20));
				verifyIndexContentsContains.accept(getReferencedEntityIndex(productCollection, Entities.BRAND, 21));
			}
		);
	}

	@Test
	void shouldRegisterNonLocalizedReferenceCompoundForEachCreatedEntity() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final String attributeCodeEan = ATTRIBUTE_CODE + StringUtils.capitalize(ATTRIBUTE_EAN);

				session
					.defineEntitySchema(Entities.CATEGORY)
					.withHierarchy()
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 10));
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 11));

				session
					.defineEntitySchema(Entities.BRAND)
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 20));
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 21));

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(
						Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
							.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.nullable())
							.withAttribute(ATTRIBUTE_EAN, String.class, thatIs -> thatIs.nullable())
							.withSortableAttributeCompound(
								attributeCodeEan,
								AttributeElement.attributeElement(ATTRIBUTE_CODE, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
								AttributeElement.attributeElement(ATTRIBUTE_EAN, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
							)
					)
					.withReferenceToEntity(
						Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
							.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.nullable())
							.withAttribute(ATTRIBUTE_EAN, String.class, thatIs -> thatIs.nullable())
							.withSortableAttributeCompound(
								attributeCodeEan,
								AttributeElement.attributeElement(ATTRIBUTE_CODE, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
								AttributeElement.attributeElement(ATTRIBUTE_EAN, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
							)
					)
					.updateVia(session);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
						.setReference(Entities.CATEGORY, 10)
						.setReference(Entities.BRAND, 20)
				);

				// check there are no specialized entity indexes
				final CatalogContract catalog = evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
				final EntityCollectionContract productCollection = catalog.getCollectionForEntity(Entities.PRODUCT)
					.orElseThrow();

				// this function allows us to repeatedly verify index contents
				final BiConsumer<EntityIndex, Serializable[]> verifyIndexContents = (entityIndex, expected) -> {
					assertNotNull(entityIndex);

					final SortIndex sortIndex = entityIndex.getSortIndex(new AttributeKey(attributeCodeEan));
					if (ArrayUtils.isEmptyOrItsValuesNull(expected)) {
						assertNull(sortIndex);
					} else {
						assertNotNull(sortIndex);
						assertArrayEquals(new int[]{1}, sortIndex.getRecordsEqualTo(expected).getArray());
					}
				};

				verifyIndexContents.accept(getReferencedEntityIndex(productCollection, Entities.CATEGORY, 10), new Serializable[]{null, null});
				verifyIndexContents.accept(getReferencedEntityIndex(productCollection, Entities.BRAND, 20), new Serializable[]{null, null});

				session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), referenceContentAll())
					.orElseThrow()
					.openForWrite()
					.setReference(
						Entities.CATEGORY, 10,
						whichIs -> whichIs
							.setAttribute(ATTRIBUTE_CODE, "ABC")
							.setAttribute(ATTRIBUTE_EAN, "123")
					)
					.setReference(
						Entities.BRAND, 20,
						whichIs -> whichIs
							.setAttribute(ATTRIBUTE_CODE, "ABC")
							.setAttribute(ATTRIBUTE_EAN, "123")
					)
					.upsertVia(session);

				verifyIndexContents.accept(getReferencedEntityIndex(productCollection, Entities.CATEGORY, 10), new Serializable[]{"ABC", "123"});
				verifyIndexContents.accept(getReferencedEntityIndex(productCollection, Entities.BRAND, 20), new Serializable[]{"ABC", "123"});
			}
		);
	}

	@Test
	void shouldDropNonLocalizedReferenceCompoundForEachRemovedEntity() {
		shouldRegisterNonLocalizedReferenceCompoundForEachCreatedEntity();

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.deleteEntity(Entities.PRODUCT, 1);

				// check there are no specialized entity indexes
				final CatalogContract catalog = evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
				final EntityCollectionContract productCollection = catalog.getCollectionForEntity(Entities.PRODUCT)
					.orElseThrow();

				final EntityIndex globalIndex = getGlobalIndex(productCollection);
				assertNotNull(globalIndex);

				assertNull(getReferencedEntityIndex(productCollection, Entities.CATEGORY, 10));
				assertNull(getReferencedEntityIndex(productCollection, Entities.CATEGORY, 11));
				assertNull(getReferencedEntityIndex(productCollection, Entities.BRAND, 20));
				assertNull(getReferencedEntityIndex(productCollection, Entities.BRAND, 21));
			}
		);
	}

	@Test
	void shouldRegisterLocalizedReferenceCompoundForEachCreatedEntityLanguage() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final String attributeCodeEan = ATTRIBUTE_CODE + StringUtils.capitalize(ATTRIBUTE_EAN);

				session
					.defineEntitySchema(Entities.CATEGORY)
					.withHierarchy()
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 10));
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 11));

				session
					.defineEntitySchema(Entities.BRAND)
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 20));
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 21));

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(
						Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
							.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.nullable())
							.withAttribute(ATTRIBUTE_EAN, String.class, thatIs -> thatIs.localized().nullable())
							.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> thatIs.localized().nullable())
							.withSortableAttributeCompound(
								attributeCodeEan,
								AttributeElement.attributeElement(ATTRIBUTE_CODE, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
								AttributeElement.attributeElement(ATTRIBUTE_EAN, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
							)
					)
					.withReferenceToEntity(
						Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
							.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.nullable())
							.withAttribute(ATTRIBUTE_EAN, String.class, thatIs -> thatIs.localized().nullable())
							.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> thatIs.localized().nullable())
							.withSortableAttributeCompound(
								attributeCodeEan,
								AttributeElement.attributeElement(ATTRIBUTE_CODE, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
								AttributeElement.attributeElement(ATTRIBUTE_EAN, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
							)
					)
					.updateVia(session);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
						.setReference(Entities.CATEGORY, 10)
						.setReference(Entities.BRAND, 20)
				);

				// check there are no specialized entity indexes
				final CatalogContract catalog = evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
				final EntityCollectionContract productCollection = catalog.getCollectionForEntity(Entities.PRODUCT)
					.orElseThrow();

				assertNull(getReferencedEntityIndex(productCollection, Entities.CATEGORY, 10).getSortIndex(new AttributeKey(attributeCodeEan)));
				assertNull(getReferencedEntityIndex(productCollection, Entities.BRAND, 20).getSortIndex(new AttributeKey(attributeCodeEan, Locale.ENGLISH)));

				// this function allows us to repeatedly verify index contents
				final TriConsumer<EntityIndex, Locale, Serializable[]> verifyIndexContents = (entityIndex, locale, expected) -> {
					assertNotNull(entityIndex);

					final SortIndex sortIndex = entityIndex.getSortIndex(new AttributeKey(attributeCodeEan, locale));
					if (ArrayUtils.isEmptyOrItsValuesNull(expected)) {
						assertNull(sortIndex);
					} else {
						assertNotNull(sortIndex);
						assertArrayEquals(new int[]{1}, sortIndex.getRecordsEqualTo(expected).getArray());
					}
				};

				session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), referenceContentAll(), dataInLocalesAll())
					.orElseThrow()
					.openForWrite()
					.setReference(Entities.CATEGORY, 10, whichIs -> whichIs
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "The product")
					)
					.setReference(Entities.BRAND, 20, whichIs -> whichIs
						.setAttribute(ATTRIBUTE_NAME, Locale.CANADA, "The product")
					)
					.upsertVia(session);

				verifyIndexContents.accept(getReferencedEntityIndex(productCollection, Entities.CATEGORY, 10), Locale.ENGLISH, new Serializable[]{null, null});
				verifyIndexContents.accept(getReferencedEntityIndex(productCollection, Entities.BRAND, 20), Locale.CANADA, new Serializable[]{null, null});

				session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), referenceContentAll(), dataInLocalesAll())
					.orElseThrow()
					.openForWrite()
					.setReference(Entities.CATEGORY, 10, whichIs -> whichIs
						.setAttribute(ATTRIBUTE_CODE, "The product")
						.setAttribute(ATTRIBUTE_EAN, Locale.ENGLISH, "123")
					)
					.setReference(Entities.BRAND, 20, whichIs -> whichIs
						.setAttribute(ATTRIBUTE_CODE, "The CA product")
						.setAttribute(ATTRIBUTE_EAN, Locale.CANADA, "456")
					)
					.upsertVia(session);

				verifyIndexContents.accept(getReferencedEntityIndex(productCollection, Entities.CATEGORY, 10), Locale.ENGLISH, new Serializable[]{"The product", "123"});
				verifyIndexContents.accept(getReferencedEntityIndex(productCollection, Entities.BRAND, 20), Locale.CANADA, new Serializable[]{"The CA product", "456"});
			}
		);
	}

	@Test
	void shouldUpdateLocalizedEntityReferenceCompoundsOnChange() {
		shouldRegisterLocalizedReferenceCompoundForEachCreatedEntityLanguage();

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final String attributeCodeEan = ATTRIBUTE_CODE + StringUtils.capitalize(ATTRIBUTE_EAN);

				// this function allows us to repeatedly verify index contents
				final TriConsumer<EntityIndex, Locale, Serializable[]> verifyIndexContents = (entityIndex, locale, expected) -> {
					assertNotNull(entityIndex);

					final SortIndex sortIndex = entityIndex.getSortIndex(new AttributeKey(attributeCodeEan, locale));
					assertNotNull(sortIndex);

					assertArrayEquals(new int[]{1}, sortIndex.getRecordsEqualTo(expected).getArray());
				};

				session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), referenceContentAll(), dataInLocalesAll())
					.orElseThrow()
					.openForWrite()
					.setReference(
						Entities.CATEGORY, 10,
						whichIs -> whichIs
							.setAttribute(ATTRIBUTE_CODE, "Whatever")
							.setAttribute(ATTRIBUTE_EAN, Locale.ENGLISH, "567")
					)
					.setReference(
						Entities.BRAND, 20,
						whichIs -> whichIs
							.setAttribute(ATTRIBUTE_CODE, "Else")
							.setAttribute(ATTRIBUTE_EAN, Locale.CANADA, "624")
					)
					.upsertVia(session);

				// check there are no specialized entity indexes
				final CatalogContract catalog = evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
				final EntityCollectionContract productCollection = catalog.getCollectionForEntity(Entities.PRODUCT)
					.orElseThrow();

				verifyIndexContents.accept(getReferencedEntityIndex(productCollection, Entities.CATEGORY, 10), Locale.ENGLISH, new Serializable[]{"Whatever", "567"});
				verifyIndexContents.accept(getReferencedEntityIndex(productCollection, Entities.BRAND, 20), Locale.CANADA, new Serializable[]{"Else", "624"});
			}
		);
	}

	@Test
	void shouldDropLocalizedReferenceCompoundForEachDroppedEntityLanguage() {
		shouldRegisterLocalizedReferenceCompoundForEachCreatedEntityLanguage();

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final String attributeCodeEan = ATTRIBUTE_CODE + StringUtils.capitalize(ATTRIBUTE_EAN);

				session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), referenceContentAll(), dataInLocalesAll())
					.orElseThrow()
					.openForWrite()
					.setReference(Entities.CATEGORY, 10, whichIs -> whichIs
						.removeAttribute(ATTRIBUTE_NAME, Locale.ENGLISH)
						.removeAttribute(ATTRIBUTE_EAN, Locale.ENGLISH)
					)
					.upsertVia(session);

				// check there are no specialized entity indexes
				final CatalogContract catalog = evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
				final EntityCollectionContract productCollection = catalog.getCollectionForEntity(Entities.PRODUCT)
					.orElseThrow();

				assertNull(getReferencedEntityIndex(productCollection, Entities.CATEGORY, 10).getSortIndex(new AttributeKey(attributeCodeEan, Locale.ENGLISH)));
				assertNotNull(getReferencedEntityIndex(productCollection, Entities.BRAND, 20).getSortIndex(new AttributeKey(attributeCodeEan, Locale.CANADA)));

				session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), referenceContentAll(), dataInLocalesAll())
					.orElseThrow()
					.openForWrite()
					.setReference(Entities.BRAND, 20, whichIs -> whichIs
						.removeAttribute(ATTRIBUTE_NAME, Locale.CANADA)
						.removeAttribute(ATTRIBUTE_EAN, Locale.CANADA)
					)
					.upsertVia(session);

				assertNull(getReferencedEntityIndex(productCollection, Entities.CATEGORY, 10).getSortIndex(new AttributeKey(attributeCodeEan, Locale.ENGLISH)));
				assertNull(getReferencedEntityIndex(productCollection, Entities.BRAND, 20).getSortIndex(new AttributeKey(attributeCodeEan, Locale.CANADA)));
			}
		);
	}

	@Test
	void shouldAutomaticallySetupReflectedReferencesOnEntityCreation() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createEntangledSchema(session);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 10)
						.setReference(REFERENCE_PRODUCT_CATEGORY, 1)
				);

				session.upsertEntity(
					session.createNewEntity(Entities.CATEGORY, 1)
				);

				assertReferencesAreEntangled(session);
			}
		);
	}

	@Test
	void shouldAutomaticallySetupOneToManyReflectedReferencesIncludingAttributesOnEntityCreation() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY)
					.withReflectedReferenceToEntity(
						REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, Entities.PRODUCT, REFERENCE_PRODUCT_CATEGORY,
						whichIs -> whichIs.withAttributesInherited().withCardinality(Cardinality.ZERO_OR_MORE)
					)
					.updateVia(session);
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(
						REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ONE_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
							.withAttribute(ATTRIBUTE_PRODUCT_CATEGORY_VARIANT, Boolean.class, thatIs -> thatIs.filterable())
					)
					.updateVia(session);

				session.upsertEntity(
					session.createNewEntity(Entities.CATEGORY, 1)
				);
				session.upsertEntity(
					session.createNewEntity(Entities.CATEGORY, 2)
				);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 10)
						.setReference(REFERENCE_PRODUCT_CATEGORY, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_VARIANT, true))
						.setReference(REFERENCE_PRODUCT_CATEGORY, 2, whichIs -> whichIs.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_VARIANT, false))
				);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 11)
						.setReference(REFERENCE_PRODUCT_CATEGORY, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_VARIANT, false))
						.setReference(REFERENCE_PRODUCT_CATEGORY, 2, whichIs -> whichIs.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_VARIANT, true))
				);
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity category1 = session.getEntity(Entities.CATEGORY, 1, entityFetchAllContent()).orElseThrow();
				assertTrue(category1.getReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10).orElseThrow().getAttribute(ATTRIBUTE_PRODUCT_CATEGORY_VARIANT, Boolean.class).booleanValue());
				assertFalse(category1.getReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 11).orElseThrow().getAttribute(ATTRIBUTE_PRODUCT_CATEGORY_VARIANT, Boolean.class).booleanValue());

				final SealedEntity category2 = session.getEntity(Entities.CATEGORY, 2, entityFetchAllContent()).orElseThrow();
				assertFalse(category2.getReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10).orElseThrow().getAttribute(ATTRIBUTE_PRODUCT_CATEGORY_VARIANT, Boolean.class).booleanValue());
				assertTrue(category2.getReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 11).orElseThrow().getAttribute(ATTRIBUTE_PRODUCT_CATEGORY_VARIANT, Boolean.class).booleanValue());
			}
		);
	}

	@Test
	void shouldFailToCreateReflectedReferenceWhenMandatoryAttributesHasNoDefaultValues() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createEntangledSchemaWithInheritedAttributesWithoutDefaults(session);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 10)
						.setReference(
							REFERENCE_PRODUCT_CATEGORY, 1,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, "ABC")
								.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_NOT_INHERITED, "123")
						)
				);

				assertThrows(
					MandatoryAttributesNotProvidedException.class,
					() -> session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
					)
				);
			}
		);
	}

	@Test
	void shouldAutomaticallySetupReflectedReferencesOnEntityCreationIncludingInheritedAttributes() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createEntangledSchemaWithInheritedAttributes(session);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 10)
						.setReference(
							REFERENCE_PRODUCT_CATEGORY, 1,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, "ABC")
								.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_NOT_INHERITED, "123")
						)
				);

				session.upsertEntity(
					session.createNewEntity(Entities.CATEGORY, 1)
				);

				assertReferencesAreEntangled(session);
				assertInheritedAttributesValues(session, "123", "ABC", "CZ");
			}
		);
	}

	@Test
	void shouldUpdateInheritedAttributeOnReflectedReference() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createEntangledSchemaWithInheritedAttributes(session);

				session.upsertEntity(
					session.createNewEntity(Entities.CATEGORY, 1)
				);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 10)
						.setReference(
							REFERENCE_PRODUCT_CATEGORY, 1,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, "ABC")
						)
				);

				assertReferencesAreEntangled(session);
				assertInheritedAttributesValues(session, "default", "ABC", "CZ");

				session.upsertEntity(
					session.getEntity(Entities.PRODUCT, 10, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(
							REFERENCE_PRODUCT_CATEGORY, 1,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, "123")
						)
				);

				assertReferencesAreEntangled(session);
				assertInheritedAttributesValues(session, "default", "123", "CZ");
			}
		);
	}

	@Test
	void shouldUpdateInheritedAttributeOnReferenceWhenSetOnReflectedOne() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createEntangledSchemaWithInheritedAttributes(session);

				session.upsertEntity(
					session.createNewEntity(Entities.CATEGORY, 1)
				);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 10)
						.setReference(
							REFERENCE_PRODUCT_CATEGORY, 1,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, "ABC")
						)
				);

				assertReferencesAreEntangled(session);
				assertInheritedAttributesValues(session, "default", "ABC", "CZ");

				session.upsertEntity(
					session.getEntity(Entities.CATEGORY, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(
							REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, "123")
						)
				);

				assertReferencesAreEntangled(session);
				assertInheritedAttributesValues(session, "default", "123", "CZ");
			}
		);
	}

	@Test
	void shouldRemoveInheritedAttributeOnReflectedReference() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createEntangledSchemaWithInheritedAttributes(session);

				session.upsertEntity(
					session.createNewEntity(Entities.CATEGORY, 1)
				);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 10)
						.setReference(
							REFERENCE_PRODUCT_CATEGORY, 1,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, "ABC")
						)
				);

				assertReferencesAreEntangled(session);
				assertInheritedAttributesValues(session, "default", "ABC", "CZ");

				session.upsertEntity(
					session.getEntity(Entities.PRODUCT, 10, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(
							REFERENCE_PRODUCT_CATEGORY, 1,
							whichIs -> whichIs
								.removeAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED)
						)
				);

				assertReferencesAreEntangled(session);
				assertInheritedAttributesValues(session, "default", null, "CZ");
			}
		);
	}

	@Test
	void shouldRemoveInheritedAttributeOnReferenceWhenSetOnReflectedOne() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createEntangledSchemaWithInheritedAttributes(session);

				session.upsertEntity(
					session.createNewEntity(Entities.CATEGORY, 1)
				);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 10)
						.setReference(
							REFERENCE_PRODUCT_CATEGORY, 1,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, "ABC")
						)
				);

				assertReferencesAreEntangled(session);
				assertInheritedAttributesValues(session, "default", "ABC", "CZ");

				session.upsertEntity(
					session.getEntity(Entities.CATEGORY, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(
							REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10,
							whichIs -> whichIs
								.removeAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED)
						)
				);

				assertReferencesAreEntangled(session);
				assertInheritedAttributesValues(session, "default", null, "CZ");
			}
		);
	}

	@Test
	void shouldAutomaticallySetupReferencesWhenReflectedOnesExistOnEntityCreation() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createEntangledSchema(session);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 10)
				);

				session.upsertEntity(
					session.createNewEntity(Entities.CATEGORY, 1)
						.setReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10)
				);

				assertReferencesAreEntangled(session);
			}
		);
	}

	@Test
	void shouldAutomaticallySetupReferencesWhenReflectedOnesExistOnEntityCreationIncludingInheritedAttributes() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createEntangledSchemaWithInheritedAttributes(session);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 10)
				);

				session.upsertEntity(
					session.createNewEntity(Entities.CATEGORY, 1)
						.setReference(
							REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, "ABC")
						)
				);

				assertReferencesAreEntangled(session);
				assertInheritedAttributesValues(session, "default", "ABC", "CZ");
			}
		);
	}

	@Test
	void shouldFailToCreateReflectedReferencesWhenBaseEntityIsNotPresentDuringCreation() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createEntangledSchema(session);

				assertThrows(
					EntityMissingException.class,
					() -> session.upsertEntity(
						session.createNewEntity(Entities.CATEGORY, 1)
							.setReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10)
					)
				);
			}
		);
	}

	@Test
	void shouldFailToCreateReflectedReferencesWhenBaseEntityIsNotPresentDuringUpdate() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createEntangledSchema(session);

				session.upsertEntity(
					session.createNewEntity(Entities.CATEGORY, 1)
				);

				assertThrows(
					EntityMissingException.class,
					() -> session.upsertEntity(
						session.getEntity(Entities.CATEGORY, 1, entityFetchAllContent())
							.orElseThrow()
							.openForWrite()
							.setReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10)
							.upsertVia(session)
					)
				);
			}
		);
	}

	@Test
	void shouldAutomaticallySetupReflectedReferencesByReferencesOnCreatedEntity() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createEntangledSchema(session);

				session.upsertEntity(
					session.createNewEntity(Entities.CATEGORY, 1)
				);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 10)
						.setReference(REFERENCE_PRODUCT_CATEGORY, 1)
				);

				assertReferencesAreEntangled(session);
			}
		);
	}

	@Test
	void shouldAutomaticallySetupReflectedReferencesByReferencesOnCreatedEntityIncludingInheritedValues() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createEntangledSchemaWithInheritedAttributes(session);

				session.upsertEntity(
					session.createNewEntity(Entities.CATEGORY, 1)
				);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 10)
						.setReference(
							REFERENCE_PRODUCT_CATEGORY, 1,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, "ABC")
						)
				);

				assertReferencesAreEntangled(session);
				assertInheritedAttributesValues(session, "default", "ABC", "CZ");
			}
		);
	}

	@Test
	void shouldAutomaticallySetupReferencesByReflectedReferencesOnCreatedEntity() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createEntangledSchema(session);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 10)
				);

				session.upsertEntity(
					session.createNewEntity(Entities.CATEGORY, 1)
						.setReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10)
				);

				assertReferencesAreEntangled(session);
			}
		);
	}

	@Test
	void shouldAutomaticallySetupReferencesByReflectedReferencesOnCreatedEntityIncludingInheritedAttributes() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createEntangledSchemaWithInheritedAttributes(session);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 10)
				);

				session.upsertEntity(
					session.createNewEntity(Entities.CATEGORY, 1)
						.setReference(
							REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10,
							whichIs -> whichIs
								.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, "ABC")
						)
				);

				assertReferencesAreEntangled(session);
				assertInheritedAttributesValues(session, "default", "ABC", "CZ");
			}
		);
	}

	@Test
	void shouldAutomaticallySetupReflectedReference() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createEntangledSchema(session);

				// first create both entities without any references
				session.upsertEntity(
					session.createNewEntity(Entities.CATEGORY, 1)
				);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 10)
				);

				// then add regular reference
				session.getEntity(Entities.PRODUCT, 10, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setReference(REFERENCE_PRODUCT_CATEGORY, 1)
					.upsertVia(session);

				assertReferencesAreEntangled(session);
			}
		);
	}

	@Test
	void shouldAutomaticallySetupReflectedReferenceIncludingInheritedAttributes() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createEntangledSchemaWithInheritedAttributes(session);

				// first create both entities without any references
				session.upsertEntity(
					session.createNewEntity(Entities.CATEGORY, 1)
				);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 10)
				);

				// then add regular reference
				session.getEntity(Entities.PRODUCT, 10, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setReference(
						REFERENCE_PRODUCT_CATEGORY, 1,
						whichIs -> whichIs
							.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, "ABC")
					)
					.upsertVia(session);

				assertReferencesAreEntangled(session);
				assertInheritedAttributesValues(session, "default", "ABC", "CZ");
			}
		);
	}

	@Test
	void shouldAutomaticallySetupReferenceViaReflectedReference() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createEntangledSchema(session);

				// first create both entities without any references
				session.upsertEntity(
					session.createNewEntity(Entities.CATEGORY, 1)
				);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 10)
				);

				// then add regular reference
				session.getEntity(Entities.CATEGORY, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10)
					.upsertVia(session);

				assertReferencesAreEntangled(session);
			}
		);
	}

	@Test
	void shouldAutomaticallySetupReferenceViaReflectedReferenceWithInheritedAttributes() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createEntangledSchemaWithInheritedAttributes(session);

				// first create both entities without any references
				session.upsertEntity(
					session.createNewEntity(Entities.CATEGORY, 1)
				);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 10)
				);

				// then add regular reference
				session.getEntity(Entities.CATEGORY, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setReference(
						REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10,
						whichIs -> whichIs
							.setAttribute(ATTRIBUTE_PRODUCT_CATEGORY_INHERITED, "ABC")
					)
					.upsertVia(session);

				assertReferencesAreEntangled(session);
				assertInheritedAttributesValues(session, "default", "ABC", "CZ");
			}
		);
	}

	@Test
	void shouldAutomaticallyRemoveReferenceViaReflectedReferenceIsRemoved() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createEntangledSchema(session);

				// first create both entities with particular reference
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 10)
				);

				session.upsertEntity(
					session.createNewEntity(Entities.CATEGORY, 1)
						.setReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10)
				);

				// then remove reflected reference
				session.getEntity(Entities.CATEGORY, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.removeReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10)
					.upsertVia(session);

				assertReferencesAreNotEntangled(session);
			}
		);
	}

	@Test
	void shouldAutomaticallyRemoveReflectedReferenceViaReferenceIsRemoved() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createEntangledSchema(session);

				// first create both entities with particular reference
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 10)
				);

				session.upsertEntity(
					session.createNewEntity(Entities.CATEGORY, 1)
						.setReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10)
				);

				// then remove regular reference
				session.getEntity(Entities.PRODUCT, 10, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.removeReference(REFERENCE_PRODUCT_CATEGORY, 1)
					.upsertVia(session);

				assertReferencesAreNotEntangled(session);
			}
		);
	}

	@Test
	void shouldAutomaticallyRemoveReflectedReferenceViaReferenceOnEntityRemoval() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createEntangledSchema(session);

				// first create both entities with particular reference
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 10)
				);

				session.upsertEntity(
					session.createNewEntity(Entities.CATEGORY, 1)
						.setReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10)
				);

				// then delete entity
				session.deleteEntity(Entities.PRODUCT, 10, entityFetchAllContent());

				assertEntitiesAreNotEntangled(session);
			}
		);
	}

	@Test
	void shouldAutomaticallyRemoveReferenceViaReflectedReferenceOnEntityRemoval() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				createEntangledSchema(session);

				// first create both entities with particular reference
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 10)
				);

				session.upsertEntity(
					session.createNewEntity(Entities.CATEGORY, 1)
						.setReference(REFERENCE_REFLECTION_PRODUCTS_IN_CATEGORY, 10)
				);

				// then delete entity
				session.deleteEntity(Entities.CATEGORY, 1, entityFetchAllContent());

				assertEntitiesAreNotEntangled(session);
			}
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
					.storageDirectory(getTestDirectory().resolve(DIR_EVITA_INDEXING_TEST))
					.exportDirectory(getTestDirectory().resolve(DIR_EVITA_INDEXING_TEST_EXPORT))
					.build()
			)
			.build();
	}

}
