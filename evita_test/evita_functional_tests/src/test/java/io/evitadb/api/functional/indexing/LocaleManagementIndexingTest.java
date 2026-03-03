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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;
import java.util.Set;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.and;
import static io.evitadb.api.query.QueryConstraints.associatedDataContentAll;
import static io.evitadb.api.query.QueryConstraints.attributeContent;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.dataInLocalesAll;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.referenceContentAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for locale management in entity indexes, verifying that supported locale and currency sets
 * adapt correctly when entities are created, updated, and when localized data is removed.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Locale management in entity indexes")
class LocaleManagementIndexingTest implements EvitaTestSupport, IndexingTestSupport {
	private static final String DIR_LOCALE_MANAGEMENT_INDEXING_TEST = "localeManagementIndexingTest";
	private static final String DIR_LOCALE_MANAGEMENT_INDEXING_TEST_EXPORT = "localeManagementIndexingTest_export";

	private Evita evita;

	@Nullable
	private static EntityReferenceContract fetchProductInLocale(
		@Nonnull EvitaSessionContract session, int primaryKey, @Nonnull Locale locale
	) {
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
		cleanTestSubDirectoryWithRethrow(DIR_LOCALE_MANAGEMENT_INDEXING_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_LOCALE_MANAGEMENT_INDEXING_TEST_EXPORT);
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanTestSubDirectoryWithRethrow(DIR_LOCALE_MANAGEMENT_INDEXING_TEST);
		cleanTestSubDirectoryWithRethrow(DIR_LOCALE_MANAGEMENT_INDEXING_TEST_EXPORT);
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return EvitaConfiguration.builder()
			.server(
				ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(-1)
					.build()
			)
			.storage(
				StorageOptions.builder()
					.storageDirectory(getTestDirectory().resolve(DIR_LOCALE_MANAGEMENT_INDEXING_TEST))
					.build()
			)
			.export(
				FileSystemExportOptions.builder()
					.directory(getTestDirectory().resolve(DIR_LOCALE_MANAGEMENT_INDEXING_TEST_EXPORT))
					.build()
			)
			.build();
	}

	@Test
	@DisplayName("Should adapt supported locale and currency set when new data is added")
	void shouldAdaptSupportedLocaleAndCurrencySet() {
		this.evita.updateCatalog(
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
	@DisplayName("Should remove entity locale when all attributes of such locale are removed")
	void shouldRemoveEntityLocaleWhenAllAttributesOfSuchLocaleAreRemoved() {
		this.evita.updateCatalog(
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

		this.evita.queryCatalog(
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

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(Entities.PRODUCT, 1, attributeContent(), dataInLocalesAll())
					.orElseThrow()
					.openForWrite()
					.removeAttribute(ATTRIBUTE_NAME, Locale.ENGLISH)
					.upsertVia(session);
			}
		);

		this.evita.queryCatalog(
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
	@DisplayName("Should remove entity locale when all reference attributes of such locale are removed")
	void shouldRemoveEntityLocaleWhenAllReferenceAttributesOfSuchLocaleAreRemoved() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.BRAND);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(
						Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE,
						thatIs -> thatIs.withAttribute(
							ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().nullable())
					)
					.updateVia(session);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
						.setReference(
							Entities.BRAND, 1, thatIs -> thatIs
								.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Siemens")
								.setAttribute(ATTRIBUTE_NAME, Locale.FRENCH, "Siemens")
						)
						.setReference(
							Entities.BRAND, 2, thatIs -> thatIs.setAttribute(ATTRIBUTE_NAME, LOCALE_CZ, "\u0160koda"))
						.setReference(
							Entities.BRAND, 3, thatIs -> thatIs
								.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Tesla")
								.setAttribute(ATTRIBUTE_NAME, LOCALE_CZ, "Tesla")
								.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "Tesla")
						)
				);
			}
		);

		this.evita.queryCatalog(
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

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(Entities.PRODUCT, 1, referenceContentAll(), dataInLocalesAll())
					.orElseThrow()
					.openForWrite()
					.setReference(
						Entities.BRAND, 1, thatIs -> thatIs
							.removeAttribute(ATTRIBUTE_NAME, Locale.FRENCH)
							.removeAttribute(ATTRIBUTE_NAME, Locale.ENGLISH)
					)
					.upsertVia(session);
			}
		);

		this.evita.queryCatalog(
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

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(Entities.PRODUCT, 1, referenceContentAll(), dataInLocalesAll())
					.orElseThrow()
					.openForWrite()
					.removeReference(Entities.BRAND, 3)
					.upsertVia(session);
			}
		);

		this.evita.queryCatalog(
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
	@DisplayName("Should remove entity locale when all associated data of such locale are removed")
	void shouldRemoveEntityLocaleWhenAllAssociatedDataOfSuchLocaleAreRemoved() {
		this.evita.updateCatalog(
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

		this.evita.queryCatalog(
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

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(Entities.PRODUCT, 1, associatedDataContentAll(), dataInLocalesAll())
					.orElseThrow()
					.openForWrite()
					.removeAssociatedData(ATTRIBUTE_NAME, Locale.ENGLISH)
					.upsertVia(session);
			}
		);

		this.evita.queryCatalog(
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
}
