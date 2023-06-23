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

import io.evitadb.api.CatalogContract;
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.MandatoryAssociatedDataNotProvidedException;
import io.evitadb.api.exception.MandatoryAttributesNotProvidedException;
import io.evitadb.api.exception.ReferenceCardinalityViolatedException;
import io.evitadb.api.query.Query;
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
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.exception.EvitaInvalidUsageException;
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
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Currency;
import java.util.Locale;
import java.util.Set;
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
	public static final String ATTRIBUTE_CODE = "code";
	public static final String ATTRIBUTE_NAME = "name";
	public static final String ATTRIBUTE_URL = "url";
	public static final String ATTRIBUTE_DESCRIPTION = "description";
	public static final String ATTRIBUTE_EAN = "ean";

	public static final String ATTRIBUTE_BRAND_NAME = "brandName";
	public static final String ATTRIBUTE_BRAND_DESCRIPTION = "brandDescription";
	public static final String ATTRIBUTE_BRAND_EAN = "brandEan";
	public static final String ATTRIBUTE_CATEGORY_PRIORITY = "categoryPriority";
	public static final String DIR_EVITA_INDEXING_TEST = "evitaIndexingTest";
	private static final Locale LOCALE_CZ = new Locale("cs", "CZ");
	private static final Currency CURRENCY_CZK = Currency.getInstance("CZK");
	private static final Currency CURRENCY_EUR = Currency.getInstance("EUR");
	private static final Currency CURRENCY_GBP = Currency.getInstance("GBP");
	private static final String PRICE_LIST_BASIC = "basic";
	private static final String PRICE_LIST_VIP = "vip";
	private Evita evita;

	private static void assertDataWasPropagated(EntityIndex categoryIndex, int recordId) {
		assertNotNull(categoryIndex);
		assertTrue(categoryIndex.getUniqueIndex(new AttributeKey(ATTRIBUTE_EAN)).getRecordIds().contains(recordId));
		assertTrue(categoryIndex.getFilterIndex(new AttributeKey(ATTRIBUTE_EAN)).getAllRecords().contains(recordId));
		assertTrue(ArrayUtils.contains(categoryIndex.getSortIndex(new AttributeKey(ATTRIBUTE_EAN)).getSortedRecords(), recordId));
		assertTrue(categoryIndex.getPriceIndex(PRICE_LIST_BASIC, CURRENCY_CZK, PriceInnerRecordHandling.NONE).getIndexedPriceEntityIds().contains(recordId));
		// EUR price is not sellable
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

	@BeforeEach
	void setUp() {
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_INDEXING_TEST);
		evita = new Evita(
			getEvitaConfiguration()
		);
		evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		evita.close();
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_INDEXING_TEST);
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
						Query.query(
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
						Entities.CATEGORY,
						Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.filterable()
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
						Entities.CATEGORY,
						Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.filterable()
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
					.setPriceInnerRecordHandling(PriceInnerRecordHandling.FIRST_OCCURRENCE)
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
				final SealedEntity entity = session.getEntity(Entities.PRODUCT, 1, dataInLocales())
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
				session.getEntity(Entities.PRODUCT, 1, attributeContent(), dataInLocales())
					.orElseThrow()
					.openForWrite()
					.removeAttribute(ATTRIBUTE_NAME, Locale.ENGLISH)
					.upsertVia(session);
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity entity = session.getEntity(Entities.PRODUCT, 1, dataInLocales())
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
						thatIs -> thatIs.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
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
				final SealedEntity entity = session.getEntity(Entities.PRODUCT, 1, dataInLocales())
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
				session.getEntity(Entities.PRODUCT, 1, referenceContentAll(), dataInLocales())
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
				final SealedEntity entity = session.getEntity(Entities.PRODUCT, 1, dataInLocales())
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
				session.getEntity(Entities.PRODUCT, 1, referenceContentAll(), dataInLocales())
					.orElseThrow()
					.openForWrite()
					.removeReference(Entities.BRAND, 3)
					.upsertVia(session);
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity entity = session.getEntity(Entities.PRODUCT, 1, dataInLocales())
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
				final SealedEntity entity = session.getEntity(Entities.PRODUCT, 1, dataInLocales())
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
				session.getEntity(Entities.PRODUCT, 1, associatedDataContent(), dataInLocales())
					.orElseThrow()
					.openForWrite()
					.removeAssociatedData(ATTRIBUTE_NAME, Locale.ENGLISH)
					.upsertVia(session);
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity entity = session.getEntity(Entities.PRODUCT, 1, dataInLocales())
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
				final SealedEntity product = session.getEntity(Entities.PRODUCT, 1, attributeContent(), dataInLocales(), referenceContentAll())
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
				final SealedEntity product = session.getEntity(Entities.PRODUCT, 1, attributeContent(), dataInLocales(), referenceContentAll())
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
						session.getEntity(Entities.PRODUCT, 1, attributeContent(), dataInLocales(), referenceContentAll())
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
				session.getEntity(Entities.PRODUCT, 1, attributeContent(), dataInLocales(), referenceContentAll())
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
				final SealedEntity product = session.getEntity(Entities.PRODUCT, 1, attributeContent(), dataInLocales(), referenceContentAll())
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
						.getEntity(Entities.PRODUCT, 1, attributeContent(), dataInLocales(), referenceContentAll())
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
						.getEntity(Entities.PRODUCT, 1, associatedDataContent(), dataInLocales())
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
						ReferenceSchemaEditor::faceted
					)
					.withReferenceToEntity(
						Entities.BRAND,
						Entities.BRAND,
						Cardinality.ZERO_OR_ONE,
						ReferenceSchemaEditor::faceted
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

				assertNull(getHierarchyIndex(productCollection, Entities.CATEGORY, 1));
				assertNull(getReferencedEntityIndex(productCollection, Entities.BRAND, 1));

				// load it and add references
				session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setReference(Entities.BRAND, 1)
					.setReference(Entities.CATEGORY, 1)
					.upsertVia(session);

				// assert data from global index were propagated
				assertNull(getReferencedEntityIndex(productCollection, Entities.CATEGORY, 1));
				final EntityIndex categoryIndex = getHierarchyIndex(productCollection, Entities.CATEGORY, 1);
				assertDataWasPropagated(categoryIndex, 1);

				assertNull(getHierarchyIndex(productCollection, Entities.BRAND, 1));
				final EntityIndex brandIndex = getReferencedEntityIndex(productCollection, Entities.BRAND, 1);
				assertDataWasPropagated(brandIndex, 1);

				// load it and remove references
				session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.removeReference(Entities.BRAND, 1)
					.removeReference(Entities.CATEGORY, 1)
					.upsertVia(session);
				;

				// assert indexes were emptied and removed
				assertNull(getHierarchyIndex(productCollection, Entities.CATEGORY, 1));
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

				assertNull(getHierarchyIndex(productCollection, Entities.CATEGORY, 1));
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
				final Consumer<Comparable<?>[]> verifyIndexContents = expected -> {
					final EntityIndex globalIndex = getGlobalIndex(productCollection);
					assertNotNull(globalIndex);

					final SortIndex sortIndex = globalIndex.getSortIndex(new AttributeKey(attributeCodeEan));
					assertNotNull(sortIndex);

					assertArrayEquals(new int[] {1}, sortIndex.getRecordsEqualTo(expected).getArray());
				};

				verifyIndexContents.accept(new Comparable<?>[] {null, null});

				session.getEntity(Entities.PRODUCT, 1, attributeContentAll())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_EAN, "123")
					.setAttribute(ATTRIBUTE_CODE, "ABC")
					.upsertVia(session);

				verifyIndexContents.accept(new Comparable<?>[] {"ABC", "123"});
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

				assertThrows(EvitaInvalidUsageException.class, () -> sortIndex.getRecordsEqualTo(new Comparable<?>[] {"ABC", "123"}));
				assertArrayEquals(new int[] {1}, sortIndex.getRecordsEqualTo(new Comparable<?>[] {"Whatever", "578"}).getArray());
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

				session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), dataInLocales())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "The product")
					.setAttribute(ATTRIBUTE_CODE, "ABC")
					.upsertVia(session);

				final EntityIndex updatedGlobalIndex = getGlobalIndex(productCollection);
				assertNotNull(updatedGlobalIndex);

				final SortIndex englishSortIndex = updatedGlobalIndex.getSortIndex(new AttributeKey(attributeCodeEan, Locale.ENGLISH));
				assertNotNull(englishSortIndex);

				assertArrayEquals(new int[] {1}, englishSortIndex.getRecordsEqualTo(new Comparable<?>[] {"ABC", null}).getArray());

				session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), dataInLocales())
					.orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_EAN, Locale.CANADA, "123")
					.setAttribute(ATTRIBUTE_CODE, "ABC")
					.upsertVia(session);

				final EntityIndex updatedGlobalIndexAgain = getGlobalIndex(productCollection);
				assertNotNull(updatedGlobalIndexAgain);

				final SortIndex englishSortIndexAgain = updatedGlobalIndexAgain.getSortIndex(new AttributeKey(attributeCodeEan, Locale.ENGLISH));
				assertNotNull(englishSortIndexAgain);

				assertArrayEquals(new int[] {1}, englishSortIndexAgain.getRecordsEqualTo(new Comparable<?>[] {"ABC", null}).getArray());

				final SortIndex canadianSortIndex = updatedGlobalIndexAgain.getSortIndex(new AttributeKey(attributeCodeEan, Locale.CANADA));
				assertNotNull(canadianSortIndex);

				assertArrayEquals(new int[] {1}, canadianSortIndex.getRecordsEqualTo(new Comparable<?>[] {"ABC", "123"}).getArray());
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
				session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), dataInLocales())
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

				assertThrows(EvitaInvalidUsageException.class, () -> sortIndex.getRecordsEqualTo(new Comparable<?>[] {"ABC", "123"}));
				assertArrayEquals(new int[] {1}, sortIndex.getRecordsEqualTo(new Comparable<?>[] {"Whatever", "578"}).getArray());
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

				session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), dataInLocales())
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
				assertArrayEquals(new int[] {1}, canadianSortIndex.getRecordsEqualTo(new Comparable<?>[] {"ABC", "123"}).getArray());

				session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), dataInLocales())
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
					.withReferenceToEntity(Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE, whichIs -> whichIs.filterable())
					.withReferenceToEntity(Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE, whichIs -> whichIs.filterable())
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

					assertArrayEquals(new int[] {1}, sortIndex.getRecordsEqualTo(new Comparable<?>[] {"ABC", "123"}).getArray());
				};

				verifyIndexContents.accept(getHierarchyIndex(productCollection, Entities.CATEGORY, 10));
				verifyIndexContents.accept(getHierarchyIndex(productCollection, Entities.CATEGORY, 11));
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

					assertArrayEquals(new int[] {1}, sortIndex.getRecordsEqualTo(new Comparable<?>[] {"ABC", "123"}).getArray());
				};

				assertNull(getHierarchyIndex(productCollection, Entities.CATEGORY, 10));
				verifyIndexContentsContains.accept(getHierarchyIndex(productCollection, Entities.CATEGORY, 11));
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
						whichIs -> whichIs.filterable()
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
						whichIs -> whichIs.filterable()
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
				final BiConsumer<EntityIndex, Comparable<?>[]> verifyIndexContents = (entityIndex, expected) -> {
					assertNotNull(entityIndex);

					final SortIndex sortIndex = entityIndex.getSortIndex(new AttributeKey(attributeCodeEan));
					assertNotNull(sortIndex);

					assertArrayEquals(new int[] {1}, sortIndex.getRecordsEqualTo(expected).getArray());
				};

				verifyIndexContents.accept(getHierarchyIndex(productCollection, Entities.CATEGORY, 10), new Comparable<?>[] {null, null});
				verifyIndexContents.accept(getReferencedEntityIndex(productCollection, Entities.BRAND, 20), new Comparable<?>[] {null, null});

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

				verifyIndexContents.accept(getHierarchyIndex(productCollection, Entities.CATEGORY, 10), new Comparable<?>[] {"ABC", "123"});
				verifyIndexContents.accept(getReferencedEntityIndex(productCollection, Entities.BRAND, 20), new Comparable<?>[] {"ABC", "123"});
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

				assertNull(getHierarchyIndex(productCollection, Entities.CATEGORY, 10));
				assertNull(getHierarchyIndex(productCollection, Entities.CATEGORY, 11));
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
						whichIs -> whichIs.filterable()
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
						whichIs -> whichIs.filterable()
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

				assertNull(getHierarchyIndex(productCollection, Entities.CATEGORY, 10).getSortIndex(new AttributeKey(attributeCodeEan)));
				assertNull(getReferencedEntityIndex(productCollection, Entities.BRAND, 20).getSortIndex(new AttributeKey(attributeCodeEan, Locale.ENGLISH)));

				// this function allows us to repeatedly verify index contents
				final TriConsumer<EntityIndex, Locale, Comparable<?>[]> verifyIndexContents = (entityIndex, locale, expected) -> {
					assertNotNull(entityIndex);

					final SortIndex sortIndex = entityIndex.getSortIndex(new AttributeKey(attributeCodeEan, locale));
					assertNotNull(sortIndex);

					assertArrayEquals(new int[] {1}, sortIndex.getRecordsEqualTo(expected).getArray());
				};

				session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), referenceContentAll(), dataInLocales())
					.orElseThrow()
					.openForWrite()
					.setReference(Entities.CATEGORY, 10, whichIs -> whichIs
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "The product")
					)
					.setReference(Entities.BRAND, 20, whichIs -> whichIs
						.setAttribute(ATTRIBUTE_NAME, Locale.CANADA, "The product")
					)
					.upsertVia(session);

				verifyIndexContents.accept(getHierarchyIndex(productCollection, Entities.CATEGORY, 10), Locale.ENGLISH, new Comparable<?>[] {null, null});
				verifyIndexContents.accept(getReferencedEntityIndex(productCollection, Entities.BRAND, 20), Locale.CANADA, new Comparable<?>[] {null, null});

				session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), referenceContentAll(), dataInLocales())
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

				verifyIndexContents.accept(getHierarchyIndex(productCollection, Entities.CATEGORY, 10), Locale.ENGLISH, new Comparable<?>[] {"The product", "123"});
				verifyIndexContents.accept(getReferencedEntityIndex(productCollection, Entities.BRAND, 20), Locale.CANADA, new Comparable<?>[] {"The CA product", "456"});
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
				final TriConsumer<EntityIndex, Locale, Comparable<?>[]> verifyIndexContents = (entityIndex, locale, expected) -> {
					assertNotNull(entityIndex);

					final SortIndex sortIndex = entityIndex.getSortIndex(new AttributeKey(attributeCodeEan, locale));
					assertNotNull(sortIndex);

					assertArrayEquals(new int[] {1}, sortIndex.getRecordsEqualTo(expected).getArray());
				};

				session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), referenceContentAll(), dataInLocales())
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

				verifyIndexContents.accept(getHierarchyIndex(productCollection, Entities.CATEGORY, 10), Locale.ENGLISH, new Comparable<?>[] {"Whatever", "567"});
				verifyIndexContents.accept(getReferencedEntityIndex(productCollection, Entities.BRAND, 20), Locale.CANADA, new Comparable<?>[] {"Else", "624"});
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

				session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), referenceContentAll(), dataInLocales())
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

				assertNull(getHierarchyIndex(productCollection, Entities.CATEGORY, 10).getSortIndex(new AttributeKey(attributeCodeEan, Locale.ENGLISH)));
				assertNotNull(getReferencedEntityIndex(productCollection, Entities.BRAND, 20).getSortIndex(new AttributeKey(attributeCodeEan, Locale.CANADA)));

				session.getEntity(Entities.PRODUCT, 1, attributeContentAll(), referenceContentAll(), dataInLocales())
					.orElseThrow()
					.openForWrite()
					.setReference(Entities.BRAND, 20, whichIs -> whichIs
						.removeAttribute(ATTRIBUTE_NAME, Locale.CANADA)
						.removeAttribute(ATTRIBUTE_EAN, Locale.CANADA)
					)
					.upsertVia(session);

				assertNull(getHierarchyIndex(productCollection, Entities.CATEGORY, 10).getSortIndex(new AttributeKey(attributeCodeEan, Locale.ENGLISH)));
				assertNull(getReferencedEntityIndex(productCollection, Entities.BRAND, 20).getSortIndex(new AttributeKey(attributeCodeEan, Locale.CANADA)));
			}
		);
	}

	@Nullable
	private EntityIndex getGlobalIndex(EntityCollectionContract collection) {
		Assert.isTrue(collection instanceof EntityCollection, "Unexpected entity collection type!");
		return ((EntityCollection) collection).getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.GLOBAL)
		);
	}

	@Nullable
	private EntityIndex getHierarchyIndex(EntityCollectionContract collection, String entityType, int recordId) {
		Assert.isTrue(collection instanceof EntityCollection, "Unexpected entity collection type!");
		return ((EntityCollection) collection).getIndexByKeyIfExists(
			new EntityIndexKey(
				EntityIndexType.REFERENCED_HIERARCHY_NODE,
				new ReferenceKey(entityType, recordId)
			)
		);
	}

	@Nullable
	private EntityIndex getReferencedEntityIndex(EntityCollectionContract collection, String entityType, int recordId) {
		Assert.isTrue(collection instanceof EntityCollection, "Unexpected entity collection type!");
		return ((EntityCollection) collection).getIndexByKeyIfExists(
			new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY,
				new ReferenceKey(entityType, recordId)
			)
		);
	}

	private int[] getAllCategories(EvitaSessionContract session) {
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

	private int countProductsWithPriceListCurrencyCombination(EvitaSessionContract session, String priceList, Currency currency) {
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
		return getTestDirectory().resolve(DIR_EVITA_INDEXING_TEST);
	}

}