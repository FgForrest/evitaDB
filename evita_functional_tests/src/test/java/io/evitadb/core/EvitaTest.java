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
import io.evitadb.api.CatalogState;
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.exception.*;
import io.evitadb.api.mock.MockCatalogStructuralChangeObserver;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.ModifyEntitySchemaDescriptionMutation;
import io.evitadb.core.exception.AttributeNotFilterableException;
import io.evitadb.core.exception.AttributeNotSortableException;
import io.evitadb.core.exception.CatalogCorruptedException;
import io.evitadb.core.exception.ReferenceNotFacetedException;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.core.sequence.SequenceService;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.rest.RestProvider;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.store.spi.CatalogPersistenceService.ENTITY_COLLECTION_FILE_SUFFIX;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test contains various integration tests for {@link Evita}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class EvitaTest implements EvitaTestSupport {
	public static final String ATTRIBUTE_NAME = "name";
	public static final String ATTRIBUTE_URL = "url";
	public static final String ATTRIBUTE_DESCRIPTION = "description";
	public static final String ATTRIBUTE_EAN = "ean";

	public static final String ATTRIBUTE_BRAND_NAME = "brandName";
	public static final String ATTRIBUTE_BRAND_DESCRIPTION = "brandDescription";
	public static final String ATTRIBUTE_BRAND_EAN = "brandEan";
	public static final String ATTRIBUTE_CATEGORY_PRIORITY = "categoryPriority";
	public static final String DIR_EVITA_TEST = "evitaTest";
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
		SequenceService.reset();
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_TEST);
		evita = new Evita(
			getEvitaConfiguration()
		);
		evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		evita.close();
		cleanTestSubDirectoryWithRethrow(DIR_EVITA_TEST);
	}

	@Test
	void shouldPreventOpeningParallelSessionsInWarmUpState() {
		assertThrows(
			ConcurrentInitializationException.class,
			() -> {
				try (final EvitaSessionContract theSession = evita.createReadOnlySession(TEST_CATALOG)) {
					evita.updateCatalog(
						TEST_CATALOG,
						session -> {
							session.defineEntitySchema(Entities.CATEGORY);
						}
					);
				}
			}
		);
	}

	@Test
	void shouldAutomaticallyCreateTransactionIfNoneExists() {
		try (final EvitaSessionContract writeSession = evita.createReadWriteSession(TEST_CATALOG)) {
			writeSession.goLiveAndClose();
		}

		try (final EvitaSessionContract writeSession = evita.createReadWriteSession(TEST_CATALOG)) {
			writeSession.defineEntitySchema(Entities.CATEGORY).updateVia(writeSession);
		}

		try (final EvitaSessionContract readSession = evita.createReadOnlySession(TEST_CATALOG)) {
			assertNotNull(readSession.getEntitySchema(Entities.CATEGORY));
		}
	}

	@Test
	void shouldFailGracefullyWhenTryingToFilterByNonIndexedReference() {
		try (final EvitaSessionContract session = evita.createReadWriteSession(TEST_CATALOG)) {
			session.defineEntitySchema(Entities.PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withReferenceTo(Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE)
				.updateVia(session);

			session.createNewEntity(
					Entities.PRODUCT,
					1
				)
				.setReference(Entities.BRAND, 1)
				.upsertVia(session);

			assertThrows(
				ReferenceNotIndexedException.class,
				() -> {
					session.query(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								referenceHaving(Entities.BRAND, entityPrimaryKeyInSet(1, 2))
							)
						),
						EntityClassifier.class
					);
				}
			);

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
	void shouldFailGracefullyWhenTryingToSummarizeByNonFacetedReference() {
		try (final EvitaSessionContract session = evita.createReadWriteSession(TEST_CATALOG)) {
			session.defineEntitySchema(Entities.PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withReferenceTo(Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE)
				.updateVia(session);

			session.createNewEntity(
					Entities.PRODUCT,
					1
				)
				.setReference(Entities.BRAND, 1)
				.upsertVia(session);

			assertThrows(
				ReferenceNotFacetedException.class,
				() -> {
					session.query(
						query(
							collection(Entities.PRODUCT),
							require(
								facetSummaryOfReference(
									Entities.BRAND,
									FacetStatisticsDepth.COUNTS,
									entityFetch(entityFetchAllContent()))
							)
						),
						EntityClassifier.class
					);
				}
			);

		}
	}

	@Test
	void shouldFailGracefullyWhenTryingToAddIndexingRequiredReferenceAttributeOnNonIndexedReference() {
		try (final EvitaSessionContract session = evita.createReadWriteSession(TEST_CATALOG)) {
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withReferenceTo(
							Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
							whichIs -> whichIs.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::filterable)
						)
						.updateVia(session);
				}
			);

			assertThrows(
				InvalidSchemaMutationException.class,
				() -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withReferenceTo(
							Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
							whichIs -> whichIs.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::unique)
						)
						.updateVia(session);
				}
			);

			assertThrows(
				InvalidSchemaMutationException.class,
				() -> {
					session.defineEntitySchema(Entities.PRODUCT)
						.withoutGeneratedPrimaryKey()
						.withReferenceTo(
							Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
							whichIs -> whichIs.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::sortable)
						)
						.updateVia(session);
				}
			);
		}
	}

	@Test
	void shouldFailGracefullyWhenTryingToFilterByNonFilterableAttribute() {
		try (final EvitaSessionContract session = evita.createReadWriteSession(TEST_CATALOG)) {
			session.defineEntitySchema(Entities.PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withAttribute(ATTRIBUTE_NAME, String.class)
				.updateVia(session);

			session.createNewEntity(
					Entities.PRODUCT,
					1
				)
				.setAttribute(ATTRIBUTE_NAME, "It's me")
				.upsertVia(session);

			assertThrows(
				AttributeNotFilterableException.class,
				() -> {
					session.query(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								attributeEquals(ATTRIBUTE_NAME, "ABC")
							)
						),
						EntityClassifier.class
					);
				}
			);

		}
	}

	@Test
	void shouldFailGracefullyWhenTryingToFilterByNonFilterableReferenceAttribute() {
		try (final EvitaSessionContract session = evita.createReadWriteSession(TEST_CATALOG)) {
			session.defineEntitySchema(Entities.PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withReferenceTo(
					Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
					whichIs -> whichIs.filterable().withAttribute(ATTRIBUTE_NAME, String.class)
				)
				.updateVia(session);

			session.createNewEntity(
					Entities.PRODUCT,
					1
				)
				.setReference(Entities.BRAND, 1, thatIs -> thatIs.setAttribute(ATTRIBUTE_NAME, "It's me"))
				.upsertVia(session);

			assertThrows(
				AttributeNotFilterableException.class,
				() -> {
					session.query(
						query(
							collection(Entities.PRODUCT),
							filterBy(
								referenceHaving(
									Entities.BRAND,
									attributeEquals(ATTRIBUTE_NAME, "ABC")
								)
							)
						),
						EntityClassifier.class
					);
				}
			);

		}
	}

	@Test
	void shouldFailGracefullyWhenTryingToOrderByNonSortableAttribute() {
		try (final EvitaSessionContract session = evita.createReadWriteSession(TEST_CATALOG)) {
			session.defineEntitySchema(Entities.PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withAttribute(ATTRIBUTE_NAME, String.class)
				.updateVia(session);

			session.createNewEntity(
					Entities.PRODUCT,
					1
				)
				.setAttribute(ATTRIBUTE_NAME, "It's me")
				.upsertVia(session);

			assertThrows(
				AttributeNotSortableException.class,
				() -> {
					session.query(
						query(
							collection(Entities.PRODUCT),
							orderBy(
								attributeNatural(ATTRIBUTE_NAME, OrderDirection.ASC)
							)
						),
						EntityClassifier.class
					);
				}
			);

		}
	}

	@Test
	void shouldFailGracefullyWhenTryingToOrderByNonSortableReferenceAttribute() {
		try (final EvitaSessionContract session = evita.createReadWriteSession(TEST_CATALOG)) {
			session.defineEntitySchema(Entities.PRODUCT)
				.withoutGeneratedPrimaryKey()
				.withReferenceTo(
					Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
					whichIs -> whichIs.filterable().withAttribute(ATTRIBUTE_NAME, String.class)
				)
				.updateVia(session);

			session.createNewEntity(
					Entities.PRODUCT,
					1
				)
				.setReference(Entities.BRAND, 1, thatIs -> thatIs.setAttribute(ATTRIBUTE_NAME, "It's me"))
				.upsertVia(session);

			assertThrows(
				AttributeNotSortableException.class,
				() -> {
					session.query(
						query(
							collection(Entities.PRODUCT),
							orderBy(
								referenceProperty(
									Entities.BRAND,
									attributeNatural(ATTRIBUTE_NAME, OrderDirection.ASC)
								)
							)
						),
						EntityClassifier.class
					);
				}
			);

		}
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
				session.getEntity(Entities.PRODUCT, 1, referenceContent(), dataInLocales())
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
				session.getEntity(Entities.PRODUCT, 1, referenceContent(), dataInLocales())
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
				final SealedEntity product = session.getEntity(Entities.PRODUCT, 1, attributeContent(), dataInLocales(), referenceContent())
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
						.getEntity(Entities.PRODUCT, 1, attributeContent(), dataInLocales(), referenceContent())
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
					session.getEntity(Entities.PRODUCT, 1, referenceContent())
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
					session.getEntity(Entities.PRODUCT, 1, referenceContent())
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
					session.getEntity(Entities.PRODUCT, 1, referenceContent())
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

				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1).setHierarchicalPlacement(1));
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 2).setHierarchicalPlacement(2));
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 3).setHierarchicalPlacement(1, 1));
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 4).setHierarchicalPlacement(1, 2));
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 5).setHierarchicalPlacement(3, 1));
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 6).setHierarchicalPlacement(3, 2));

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

				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1).setHierarchicalPlacement(1));

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

				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1).setHierarchicalPlacement(1));

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
	void shouldHandleQueryingEmptyCollection() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.BRAND);

				final EvitaResponse<SealedEntity> entities = session.query(
					query(
						collection(Entities.BRAND)
					),
					SealedEntity.class
				);

				// result is expected to be empty
				assertEquals(0, entities.getTotalRecordCount());
				assertTrue(entities.getRecordData().isEmpty());
			}
		);
	}

	@Test
	void shouldReturnZeroOrExactlyOne() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.BRAND);

				session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 2));

				assertNull(
					session.queryOneEntityReference(
						query(collection(Entities.BRAND), filterBy(entityPrimaryKeyInSet(10)))
					).orElse(null)
				);

				assertNotNull(
					session.queryOneEntityReference(
						query(collection(Entities.BRAND), filterBy(entityPrimaryKeyInSet(1)))
					)
				);

				assertThrows(
					UnexpectedResultCountException.class,
					() -> session.queryOneEntityReference(
						query(collection(Entities.BRAND), filterBy(entityPrimaryKeyInSet(1, 2)))
					)
				);

				assertThrows(
					UnexpectedResultException.class,
					() -> session.queryOneSealedEntity(
						query(collection(Entities.BRAND), filterBy(entityPrimaryKeyInSet(1, 2)))
					)
				);
			}
		);
	}

	@Test
	void shouldReturnMultipleResults() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.BRAND);

				final Integer[] pks = new Integer[50];
				for (int i = 0; i < 50; i++) {
					session.upsertEntity(session.createNewEntity(Entities.BRAND, i + 1));
					pks[i] = i + 1;
				}

				final EvitaResponse<EntityReference> emptyResult = session.query(
					query(collection(Entities.BRAND), filterBy(entityPrimaryKeyInSet(100))),
					EntityReference.class
				);

				assertEquals(0, emptyResult.getTotalRecordCount());
				assertTrue(emptyResult.getRecordData().isEmpty());

				final EvitaResponse<EntityReference> firstPageResult = session.query(
					query(collection(Entities.BRAND), filterBy(entityPrimaryKeyInSet(pks)), require(page(1, 5))),
					EntityReference.class
				);

				assertEquals(50, firstPageResult.getTotalRecordCount());
				assertArrayEquals(new int[]{1, 2, 3, 4, 5}, firstPageResult.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).toArray());

				final EvitaResponse<SealedEntity> thirdPageResult = session.query(
					query(collection(Entities.BRAND), filterBy(entityPrimaryKeyInSet(pks)), require(page(3, 5), entityFetch())),
					SealedEntity.class
				);

				assertEquals(50, thirdPageResult.getTotalRecordCount());
				assertArrayEquals(new int[]{11, 12, 13, 14, 15}, thirdPageResult.getRecordData().stream().mapToInt(SealedEntity::getPrimaryKey).toArray());

				assertThrows(
					UnexpectedResultException.class,
					() -> session.query(
						query(collection(Entities.BRAND), filterBy(entityPrimaryKeyInSet(1, 2))),
						SealedEntity.class
					)
				);
			}
		);
	}

	@Test
	void shouldCreateAndLoadCatalog() {
		evita.close();
		evita = new Evita(
			getEvitaConfiguration()
		);

		assertTrue(evita.getCatalogNames().contains(TEST_CATALOG));
	}

	@Test
	void shouldKillInactiveSessionsAutomatically() {
		evita.updateCatalog(
			TEST_CATALOG,
			it -> {
				it.goLiveAndClose();
			}
		);
		evita.close();

		evita = new Evita(
			getEvitaConfiguration(1)
		);

		final EvitaSessionContract sessionInactive = evita.createReadOnlySession(TEST_CATALOG);
		final EvitaSessionContract sessionActive = evita.createReadOnlySession(TEST_CATALOG);

		assertEquals(2L, evita.getActiveSessions().count());

		final long start = System.currentTimeMillis();
		do {
			assertNotNull(sessionActive.getCatalogSchema());
		} while (System.currentTimeMillis() - start < 2001);

		assertFalse(sessionInactive.isActive());
		assertTrue(sessionActive.isActive());
		assertEquals(1L, evita.getActiveSessions().count());
	}

	@Test
	void shouldCreateAndDropCatalog() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.BRAND);

				session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 2));
				assertEquals(2, session.getEntityCollectionSize(Entities.BRAND));
			}
		);

		evita.deleteCatalogIfExists(TEST_CATALOG);

		assertFalse(evita.getCatalogNames().contains(TEST_CATALOG));
	}

	@Test
	void shouldFailToCreateCatalogWithDuplicateNameInOneOfNamingConventions() {
		try {
			evita.defineCatalog("test-catalog");
			fail("Duplicated catalog name should be refused!");
		} catch (CatalogAlreadyPresentException ex) {
			assertEquals(
				"Catalog `test-catalog` and existing catalog `testCatalog` produce the same name `testCatalog` " +
					"in `CAMEL_CASE` convention! Please choose different catalog name.",
				ex.getMessage()
			);
		}
	}

	@Test
	void shouldCreateAndEntityCollectionFromWithinTheSession() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.BRAND);

				session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 2));
				assertEquals(2, session.getEntityCollectionSize(Entities.BRAND));

				session
					.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 10));
				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 11));
				assertEquals(2, session.getEntityCollectionSize(Entities.PRODUCT));

				session.deleteCollection(Entities.BRAND);
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertThrows(CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.BRAND));
				assertEquals(2, session.getEntityCollectionSize(Entities.PRODUCT));
				return null;
			}
		);
	}

	@Test
	void shouldRenameExistingCatalogInWarmUpMode() {
		doRenameCatalog(CatalogState.WARMING_UP);
	}

	@Test
	void shouldRenameExistingCatalogInTransactionalMode() {
		doRenameCatalog(CatalogState.ALIVE);
	}

	@Test
	void shouldReplaceExistingCatalogInWarmUpMode() {
		doReplaceCatalog(CatalogState.WARMING_UP);
	}

	@Test
	void shouldReplaceExistingCatalogInTransactionalMode() {
		doReplaceCatalog(CatalogState.ALIVE);
	}

	@Test
	void shouldNotifyCallbackAboutCatalogCreation() {
		MockCatalogStructuralChangeObserver.reset();

		evita.defineCatalog("newCatalog");

		assertEquals(1, MockCatalogStructuralChangeObserver.getCatalogCreated("newCatalog"));
	}

	@Test
	void shouldNotifyCallbackAboutCatalogDelete() {
		MockCatalogStructuralChangeObserver.reset();

		evita.deleteCatalogIfExists(TEST_CATALOG);

		assertEquals(1, MockCatalogStructuralChangeObserver.getCatalogDeleted(TEST_CATALOG));
	}

	@Test
	void shouldNotifyCallbackAboutCatalogSchemaUpdate() {
		MockCatalogStructuralChangeObserver.reset();

		evita.updateCatalog(TEST_CATALOG, session -> {
			session.getCatalogSchema()
				.openForWrite()
				.withAttribute("newAttribute", int.class)
				.updateVia(session);
		});

		assertEquals(1, MockCatalogStructuralChangeObserver.getCatalogSchemaUpdated(TEST_CATALOG));
	}

	@Test
	void shouldNotifyCallbackAboutCatalogSchemaDescriptionChange() {
		MockCatalogStructuralChangeObserver.reset();

		evita.update(
			new ModifyCatalogSchemaMutation(
				TEST_CATALOG,
				new ModifyCatalogSchemaDescriptionMutation("Brand new description.")
			)
		);

		assertEquals(1, MockCatalogStructuralChangeObserver.getCatalogSchemaUpdated(TEST_CATALOG));

		assertEquals(
			"Brand new description.",
			evita.getCatalogInstanceOrThrowException(TEST_CATALOG).getSchema().getDescription()
		);
	}

	@Test
	void shouldNotifyCallbackAboutEntitySchemaDescriptionChange() {
		MockCatalogStructuralChangeObserver.reset();

		evita.update(
			new ModifyCatalogSchemaMutation(
				TEST_CATALOG,
				new CreateEntitySchemaMutation(Entities.PRODUCT),
				new ModifyEntitySchemaMutation(
					Entities.PRODUCT,
					new ModifyEntitySchemaDescriptionMutation("Brand new description.")
				)
			)
		);

		assertEquals(
			"Brand new description.",
			evita.getCatalogInstanceOrThrowException(TEST_CATALOG).getEntitySchema(Entities.PRODUCT).orElseThrow().getDescription()
		);

		assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionCreated(TEST_CATALOG, Entities.PRODUCT));
	}

	@Test
	void shouldNotifyCallbackAboutCatalogSchemaUpdateInTransactionalMode() {
		MockCatalogStructuralChangeObserver.reset();

		evita.updateCatalog(TEST_CATALOG, session -> {
			session.goLiveAndClose();
		});
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.getCatalogSchema()
				.openForWrite()
				.withAttribute("newAttribute", int.class)
				.updateVia(session);
		});

		assertEquals(1, MockCatalogStructuralChangeObserver.getCatalogSchemaUpdated(TEST_CATALOG));
	}

	@Test
	void shouldNotifyCallbackAboutCatalogEntityCollectionCreate() {
		MockCatalogStructuralChangeObserver.reset();

		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("newEntity");
		});

		assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionCreated(TEST_CATALOG, "newEntity"));
	}

	@Test
	void shouldNotifyCallbackAboutCatalogEntityCollectionCreateInTransactionalMode() {
		MockCatalogStructuralChangeObserver.reset();

		evita.updateCatalog(TEST_CATALOG, session -> {
			session.goLiveAndClose();
		});
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("newEntity");
		});

		assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionCreated(TEST_CATALOG, "newEntity"));
	}

	@Test
	void shouldNotifyCallbackAboutCatalogEntityCollectionDelete() {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT);
		});

		MockCatalogStructuralChangeObserver.reset();

		evita.updateCatalog(TEST_CATALOG, session -> {
			session.deleteCollection(Entities.PRODUCT);
		});

		assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionDeleted(TEST_CATALOG, Entities.PRODUCT));
	}

	@Test
	void shouldNotifyCallbackAboutCatalogEntityCollectionDeleteInTransactionalMode() {
		MockCatalogStructuralChangeObserver.reset();

		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT);
			session.goLiveAndClose();
		});
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.deleteCollection(Entities.PRODUCT);
		});

		assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionDeleted(TEST_CATALOG, Entities.PRODUCT));
	}

	@Test
	void shouldNotifyCallbackAboutCatalogEntityCollectionSchemaUpdate() {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT);
		});

		MockCatalogStructuralChangeObserver.reset();

		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT)
				.withAttribute("code", String.class)
				.updateVia(session);
		});

		assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionSchemaUpdated(TEST_CATALOG, Entities.PRODUCT));
	}

	@Test
	void shouldNotifyCallbackAboutCatalogEntityCollectionSchemaUpdateInTransactionalMode() {
		MockCatalogStructuralChangeObserver.reset();

		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT);
			session.goLiveAndClose();
		});
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT)
				.withAttribute("code", String.class)
				.updateVia(session);
		});

		assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionSchemaUpdated(TEST_CATALOG, Entities.PRODUCT));
	}

	@Test
	void shouldCreateAndDropCollection() {
		setupCatalogWithProductAndCategory();

		evita.updateCatalog(TEST_CATALOG, session -> {
			session.deleteCollection(Entities.PRODUCT);
		});

		evita.queryCatalog(TEST_CATALOG, session -> {
			assertThrows(CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.PRODUCT));
			assertEquals(2, session.getEntityCollectionSize(Entities.CATEGORY));
			return null;
		});
	}

	@Test
	void shouldCreateAndRenameCollection() {
		setupCatalogWithProductAndCategory();

		final File theCollectionFile = getTestDirectory()
			.resolve(TEST_CATALOG + File.separator + Entities.PRODUCT.toLowerCase() + ENTITY_COLLECTION_FILE_SUFFIX)
			.toFile();
		assertTrue(theCollectionFile.exists());

		MockCatalogStructuralChangeObserver.reset();

		evita.updateCatalog(TEST_CATALOG, session -> {
			session.renameCollection(Entities.PRODUCT, Entities.STORE);
			assertEquals(Entities.STORE, session.getEntitySchemaOrThrow(Entities.STORE).getName());
		});

		assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionCreated(TEST_CATALOG, Entities.STORE));
		assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionDeleted(TEST_CATALOG, Entities.PRODUCT));

		evita.queryCatalog(TEST_CATALOG, session -> {
			assertThrows(CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.PRODUCT));
			assertEquals(1, session.getEntityCollectionSize(Entities.STORE));
			assertEquals(2, session.getEntityCollectionSize(Entities.CATEGORY));
			return null;
		});

		assertFalse(theCollectionFile.exists());
	}

	@Test
	void shouldFailToRenameCollectionToExistingCollection() {
		setupCatalogWithProductAndCategory();

		final File theCollectionFile = getTestDirectory()
			.resolve(TEST_CATALOG + File.separator + Entities.PRODUCT.toLowerCase() + ENTITY_COLLECTION_FILE_SUFFIX)
			.toFile();
		assertTrue(theCollectionFile.exists());

		evita.updateCatalog(TEST_CATALOG, session -> {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> session.renameCollection(Entities.PRODUCT, Entities.CATEGORY)
			);
		});
	}

	@Test
	void shouldCreateAndReplaceCollection() {
		setupCatalogWithProductAndCategory();

		final File theCollectionFile = getTestDirectory()
			.resolve(TEST_CATALOG + File.separator + Entities.PRODUCT.toLowerCase() + ENTITY_COLLECTION_FILE_SUFFIX)
			.toFile();
		assertTrue(theCollectionFile.exists());

		MockCatalogStructuralChangeObserver.reset();

		evita.updateCatalog(TEST_CATALOG, session -> {
			session.replaceCollection(Entities.CATEGORY, Entities.PRODUCT);
		});

		assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionSchemaUpdated(TEST_CATALOG, Entities.CATEGORY));
		assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionDeleted(TEST_CATALOG, Entities.PRODUCT));

		evita.queryCatalog(TEST_CATALOG, session -> {
			assertThrows(CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.PRODUCT));
			assertEquals(1, session.getEntityCollectionSize(Entities.CATEGORY));
			return null;
		});

		assertFalse(theCollectionFile.exists());
	}

	@Test
	void shouldCreateAndRenameCollectionInTransaction() {
		setupCatalogWithProductAndCategory();

		evita.updateCatalog(TEST_CATALOG, session -> { session.goLiveAndClose(); });

		MockCatalogStructuralChangeObserver.reset();

		evita.updateCatalog(TEST_CATALOG, session -> {
			session.renameCollection(Entities.PRODUCT, Entities.STORE);
			assertEquals(Entities.STORE, session.getEntitySchemaOrThrow(Entities.STORE).getName());
		});

		assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionCreated(TEST_CATALOG, Entities.STORE));
		assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionDeleted(TEST_CATALOG, Entities.PRODUCT));

		evita.queryCatalog(TEST_CATALOG, session -> {
			assertThrows(CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.PRODUCT));
			assertEquals(1, session.getEntityCollectionSize(Entities.STORE));
			assertEquals(2, session.getEntityCollectionSize(Entities.CATEGORY));
			return null;
		});
	}

	@Test
	void shouldCreateAndReplaceCollectionInTransaction() {
		setupCatalogWithProductAndCategory();

		evita.updateCatalog(TEST_CATALOG, session -> { session.goLiveAndClose(); });

		MockCatalogStructuralChangeObserver.reset();

		evita.updateCatalog(TEST_CATALOG, session -> {
			session.replaceCollection(Entities.CATEGORY, Entities.PRODUCT);
		});

		assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionSchemaUpdated(TEST_CATALOG, Entities.CATEGORY));
		assertEquals(1, MockCatalogStructuralChangeObserver.getEntityCollectionDeleted(TEST_CATALOG, Entities.PRODUCT));

		evita.queryCatalog(TEST_CATALOG, session -> {
			assertThrows(CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.PRODUCT));
			assertEquals(1, session.getEntityCollectionSize(Entities.CATEGORY));
			return null;
		});
	}

	@Test
	void shouldCreateAndDropCollectionsInTransaction() {
		setupCatalogWithProductAndCategory();

		evita.updateCatalog(TEST_CATALOG, session -> {
			session.goLiveAndClose();
		});

		evita.updateCatalog(TEST_CATALOG, session -> {
			session.deleteCollection(Entities.PRODUCT);
		});

		evita.queryCatalog(TEST_CATALOG, session -> {
			assertThrows(CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.PRODUCT));
			assertEquals(2, session.getEntityCollectionSize(Entities.CATEGORY));
			return null;
		});
	}

	@Test
	void shouldFetchEntityByLocalizedGlobalAttributeAutomaticallySelectingProperLocale() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(ATTRIBUTE_URL, String.class, whichIs -> whichIs.localized().uniqueGlobally())
					.updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withGlobalAttribute(ATTRIBUTE_URL)
					.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
					.updateVia(session);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Hence, product")
						.setAttribute(ATTRIBUTE_NAME, LOCALE_CZ, "Hle, produkt")
						.setAttribute(ATTRIBUTE_URL, Locale.ENGLISH, "/theProduct")
						.setAttribute(ATTRIBUTE_URL, LOCALE_CZ, "/tenProdukt")
				);

				final SealedEntity result = session.queryOneSealedEntity(
					query(
						collection(Entities.PRODUCT),
						filterBy(attributeEquals(ATTRIBUTE_URL, "/tenProdukt")),
						require(entityFetch(attributeContent()))
					)
				).orElseThrow();

				assertEquals("Hle, produkt", result.getAttribute(ATTRIBUTE_NAME));

				final Set<Locale> locales = result.getLocales();
				assertEquals(1, locales.size());
				assertTrue(locales.contains(LOCALE_CZ));
				assertEquals(LOCALE_CZ, ((EntityDecorator) result).getImplicitLocale());
			}
		);
	}

	@Test
	void shouldFetchEntityByLocalizedGlobalAttributeAutomaticallySelectingProperLocalePerEntity() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(ATTRIBUTE_URL, String.class, whichIs -> whichIs.localized().uniqueGlobally())
					.updateVia(session);

				session
					.defineEntitySchema(Entities.PRODUCT)
					.withGlobalAttribute(ATTRIBUTE_URL)
					.withAttribute(ATTRIBUTE_NAME, String.class, AttributeSchemaEditor::localized)
					.updateVia(session);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Hence, product")
						.setAttribute(ATTRIBUTE_NAME, LOCALE_CZ, "Hle, produkt")
						.setAttribute(ATTRIBUTE_URL, Locale.ENGLISH, "/theProduct")
						.setAttribute(ATTRIBUTE_URL, LOCALE_CZ, "/tenProdukt")
				);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 2)
						.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Hence, slightly different product")
						.setAttribute(ATTRIBUTE_NAME, LOCALE_CZ, "Hle, trochu jiný produkt")
						.setAttribute(ATTRIBUTE_URL, Locale.ENGLISH, "/theOtherProduct")
						.setAttribute(ATTRIBUTE_URL, LOCALE_CZ, "/tenJinýProdukt")
				);

				final List<SealedEntity> result = session.queryListOfSealedEntities(
					query(
						collection(Entities.PRODUCT),
						filterBy(attributeInSet(ATTRIBUTE_URL, "/tenProdukt", "/theOtherProduct")),
						require(entityFetch(attributeContent()))
					)
				);

				assertNotNull(result);

				final SealedEntity firstProduct = result.stream().filter(it -> Objects.equals(it.getPrimaryKey(), 1)).findFirst().orElse(null);
				final SealedEntity secondProduct = result.stream().filter(it -> Objects.equals(it.getPrimaryKey(), 2)).findFirst().orElse(null);
				assertNotNull(firstProduct);
				assertNotNull(secondProduct);

				assertEquals("Hle, produkt", firstProduct.getAttribute(ATTRIBUTE_NAME));
				final Set<Locale> firstProductLocales = firstProduct.getLocales();
				assertEquals(1, firstProductLocales.size());
				assertTrue(firstProductLocales.contains(LOCALE_CZ));
				assertEquals(LOCALE_CZ, ((EntityDecorator) firstProduct).getImplicitLocale());

				assertEquals("Hence, slightly different product", secondProduct.getAttribute(ATTRIBUTE_NAME));
				final Set<Locale> secondProductLocales = secondProduct.getLocales();
				assertEquals(1, secondProductLocales.size());
				assertTrue(secondProductLocales.contains(Locale.ENGLISH));
				assertEquals(Locale.ENGLISH, ((EntityDecorator) secondProduct).getImplicitLocale());
			}
		);
	}

	@Test
	void shouldFailToDefineTwoEntitiesSharingNameInSpecificNamingConvention() {
		assertThrows(
			EntityTypeAlreadyPresentInCatalogSchemaException.class,
			() -> evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema("abc");
					session.defineEntitySchema("ABc");
				}
			)
		);
	}

	@Test
	void shouldFailGracefullyWhenRequestingHierarchyOnNonHierarchyEntity() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
				);

				assertThrows(
					TargetEntityIsNotHierarchicalException.class,
					() -> session.queryListOfSealedEntities(
						query(
							collection(Entities.PRODUCT),
							filterBy(hierarchyWithinRootSelf()),
							require(entityFetch())
						)
					)
				);
			}
		);
	}

	@Test
	void shouldCorrectlyWorkWithOverlappingRangesWhenUpdated() {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withAttribute("range", IntegerNumberRange[].class, AttributeSchemaEditor::filterable)
					.updateVia(session);

				session.createNewEntity(Entities.PRODUCT, 1)
					.setAttribute(
						"range",
						new IntegerNumberRange[]{
							IntegerNumberRange.between(1, 5),
							IntegerNumberRange.between(5, 10),
						}
					).upsertVia(session);

				IntFunction<EntityReference> getInRange = (threshold) -> session.queryOneEntityReference(
					query(
						collection(Entities.PRODUCT),
						filterBy(attributeInRange("range", threshold))
					)
				).orElse(null);

				assertEquals(
					new EntityReference(Entities.PRODUCT, 1),
					getInRange.apply(4)
				);
				assertEquals(
					new EntityReference(Entities.PRODUCT, 1),
					getInRange.apply(7)
				);

				session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setAttribute("range", new IntegerNumberRange[]{IntegerNumberRange.between(1, 5)})
					.upsertVia(session);

				assertEquals(
					new EntityReference(Entities.PRODUCT, 1),
					getInRange.apply(4)
				);
				assertNull(
					getInRange.apply(7)
				);
			}
		);
	}

	@Test
	void shouldIsolateChangesInSchemaWithinTransactions() {
		// create some initial state
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withAttribute("someAttribute", String.class)
					.updateVia(session);
				session.goLiveAndClose();
			}
		);

		// now open a new session and modify something
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				assertTrue(session.isTransactionOpen());
				session.getCatalogSchema()
					.openForWrite()
					.withDescription("This is my beautiful catalog.")
					.updateVia(session);

				session.getEntitySchema(Entities.PRODUCT)
					.orElseThrow()
					.openForWrite()
					.withDescription("This is my beautiful product collection.")
					.withAttribute("someAttribute", String.class, thatIs -> thatIs.localized().filterable())
					.withAttribute("differentAttribute", Integer.class)
					.updateVia(session);

				// create different session in parallel (original session is not yet committed)
				final CountDownLatch latch = new CountDownLatch(1);
				final Thread testThread = new Thread(() -> {
					try {
						evita.queryCatalog(
							TEST_CATALOG,
							parallelSession -> {
								assertNull(parallelSession.getCatalogSchema().getDescription());

								final SealedEntitySchema productSchema = parallelSession.getEntitySchema(Entities.PRODUCT).orElseThrow();
								assertNull(productSchema.getDescription());

								final AttributeSchemaContract someAttributeSchema = productSchema
									.getAttribute("someAttribute")
									.orElseThrow();

								assertFalse(someAttributeSchema.isLocalized());
								assertFalse(someAttributeSchema.isFilterable());

								final AttributeSchemaContract differentAttributeSchema = productSchema
									.getAttribute("differentAttribute")
									.orElse(null);

								assertNull(differentAttributeSchema);

							}
						);
					} finally {
						latch.countDown();
					}
				});
				testThread.start();
				try {
					latch.await(10, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					fail(e.getMessage());
				}
			}
		);

		// verify the changes were propagated at last
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertEquals("This is my beautiful catalog.", session.getCatalogSchema().getDescription());

				final SealedEntitySchema productSchema = session.getEntitySchema(Entities.PRODUCT).orElseThrow();
				assertEquals("This is my beautiful product collection.", productSchema.getDescription());

				final AttributeSchemaContract someAttributeSchema = productSchema
					.getAttribute("someAttribute")
					.orElseThrow();

				assertTrue(someAttributeSchema.isLocalized());
				assertTrue(someAttributeSchema.isFilterable());

				final AttributeSchemaContract differentAttributeSchema = productSchema
					.getAttribute("differentAttribute")
					.orElseThrow();
				assertNotNull(differentAttributeSchema);
			}
		);
	}

	@Test
	void shouldStartEvenIfOneCatalogIsCorrupted() {
		evita.defineCatalog(TEST_CATALOG + "_1")
			.updateViaNewSession(evita);
		evita.updateCatalog(
			TEST_CATALOG + "_1",
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
				);
			}
		);

		evita.defineCatalog(TEST_CATALOG + "_2")
			.updateViaNewSession(evita);
		evita.updateCatalog(
			TEST_CATALOG + "_2",
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1)
				);
			}
		);

		evita.close();

		// damage the TEST_CATALOG_1 contents
		try {
			final Path productCollectionFile = getTestDirectory().resolve(TEST_CATALOG + "_1" + File.separator + Entities.PRODUCT.toLowerCase() + CatalogPersistenceService.ENTITY_COLLECTION_FILE_SUFFIX);
			Files.write(productCollectionFile, "Mangled content!".getBytes(StandardCharsets.UTF_8));
		} catch (Exception ex) {
			fail(ex);
		}

		evita = new Evita(
			getEvitaConfiguration()
		);

		try (ExternalApiServer externalApiServer = new ExternalApiServer(
			evita,
			ApiOptions.builder()
				.enable(GraphQLProvider.CODE)
				.enable(GrpcProvider.CODE)
				.enable(RestProvider.CODE)
				.build()
		)) {
			externalApiServer.start();
		}

		final Set<String> catalogNames = evita.getCatalogNames();
		assertEquals(3, catalogNames.size());

		assertThrows(
			CatalogCorruptedException.class,
			() -> {
				evita.updateCatalog(
					TEST_CATALOG + "_1",
					session -> {
						session.getAllEntityTypes();
					}
				);
			}
		);
	}

	private void doRenameCatalog(@Nonnull CatalogState catalogState) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.BRAND);

				session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 2));
				assertEquals(2, session.getEntityCollectionSize(Entities.BRAND));
			}
		);

		final String renamedCatalogName = TEST_CATALOG + "_renamed";
		final AtomicInteger versionBeforeRename = new AtomicInteger();
		if (catalogState == CatalogState.ALIVE) {
			evita.updateCatalog(TEST_CATALOG, session -> {
				versionBeforeRename.set(session.getCatalogSchema().getVersion());
				session.goLiveAndClose();
			});
		} else {
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					versionBeforeRename.set(session.getCatalogSchema().getVersion());
				}
			);
		}

		evita.renameCatalog(TEST_CATALOG, renamedCatalogName);

		assertFalse(evita.getCatalogNames().contains(TEST_CATALOG));
		assertTrue(evita.getCatalogNames().contains(renamedCatalogName));

		evita.queryCatalog(
			renamedCatalogName,
			session -> {
				assertEquals(renamedCatalogName, session.getCatalogSchema().getName());
				assertEquals(2, session.getEntityCollectionSize(Entities.BRAND));
				assertEquals(versionBeforeRename.get() + 1, session.getCatalogSchema().getVersion());
				return null;
			}
		);

		evita.close();

		evita = new Evita(
			getEvitaConfiguration()
		);

		evita.queryCatalog(
			renamedCatalogName,
			session -> {
				assertEquals(renamedCatalogName, session.getCatalogSchema().getName());
				assertEquals(2, session.getEntityCollectionSize(Entities.BRAND));
				return null;
			}
		);
	}

	private void doReplaceCatalog(@Nonnull CatalogState catalogState) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.BRAND);

				session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));
				session.upsertEntity(session.createNewEntity(Entities.BRAND, 2));
				assertEquals(2, session.getEntityCollectionSize(Entities.BRAND));
			}
		);

		final String temporaryCatalogName = TEST_CATALOG + "_tmp";
		evita.defineCatalog(temporaryCatalogName);
		evita.updateCatalog(
			temporaryCatalogName,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT);

				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 1));
				session.upsertEntity(session.createNewEntity(Entities.PRODUCT, 2));
				assertEquals(2, session.getEntityCollectionSize(Entities.PRODUCT));
			}
		);

		final AtomicInteger versionBeforeRename = new AtomicInteger();
		if (catalogState == CatalogState.ALIVE) {
			evita.updateCatalog(temporaryCatalogName, session -> {
				versionBeforeRename.set(session.getCatalogSchema().getVersion());
				session.goLiveAndClose();
			});
		} else {
			evita.queryCatalog(
				temporaryCatalogName,
				session -> {
					versionBeforeRename.set(session.getCatalogSchema().getVersion());
				}
			);
		}

		evita.replaceCatalog(TEST_CATALOG, temporaryCatalogName);

		assertFalse(evita.getCatalogNames().contains(temporaryCatalogName));
		assertTrue(evita.getCatalogNames().contains(TEST_CATALOG));

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertEquals(TEST_CATALOG, session.getCatalogSchema().getName());
				assertThrows(CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.BRAND));
				assertEquals(2, session.getEntityCollectionSize(Entities.PRODUCT));
				assertEquals(versionBeforeRename.get() + 1, session.getCatalogSchema().getVersion());
				return null;
			}
		);

		evita.close();

		evita = new Evita(
			getEvitaConfiguration()
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertEquals(TEST_CATALOG, session.getCatalogSchema().getName());
				assertThrows(CollectionNotFoundException.class, () -> session.getEntityCollectionSize(Entities.BRAND));
				assertEquals(2, session.getEntityCollectionSize(Entities.PRODUCT));
				return null;
			}
		);
	}

	private void setupCatalogWithProductAndCategory() {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT);

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

			session.defineEntitySchema(Entities.CATEGORY);

			session
				.defineEntitySchema(Entities.CATEGORY)
				.withHierarchy()
				.updateVia(session);

			session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1).setHierarchicalPlacement(1));
			session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 2).setHierarchicalPlacement(2));
		});
	}

	@Nullable
	private EntityIndex getHierarchyIndex(EntityCollectionContract productCollection, String entityType, int recordId) {
		Assert.isTrue(productCollection instanceof EntityCollection, "Unexpected entity collection type!");
		return ((EntityCollection) productCollection).getIndexByKeyIfExists(
			new EntityIndexKey(
				EntityIndexType.REFERENCED_HIERARCHY_NODE,
				new ReferenceKey(entityType, recordId)
			)
		);
	}

	@Nullable
	private EntityIndex getReferencedEntityIndex(EntityCollectionContract productCollection, String entityType, int recordId) {
		Assert.isTrue(productCollection instanceof EntityCollection, "Unexpected entity collection type!");
		return ((EntityCollection) productCollection).getIndexByKeyIfExists(
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
					.storageDirectory(getTestDirectory().resolve(DIR_EVITA_TEST))
					.build()
			)
			.build();
	}

}