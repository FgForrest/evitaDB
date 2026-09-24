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
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.exception.ReferenceCardinalityViolatedException;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.facet.FacetGroupIndex;
import io.evitadb.index.facet.FacetIdIndex;
import io.evitadb.index.facet.FacetReferenceIndex;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.functional.indexing.IndexingTestSupport.*;
import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;

/**
 * Tests for reference indexing operations in evitaDB, verifying cardinality validation,
 * data propagation to reduced indexes, non-indexed references, and hierarchy operations.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Reference indexing operations")
@Tag(CONTRACT)
@Tag(INDEXING)
@Tag(REFERENCE)
class ReferenceIndexingTest implements EvitaTestSupport, IndexingTestSupport {

	private TestPaths paths;
	private Evita evita;

	private static int countProductsWithPriceListCurrencyCombination(
		@Nonnull EvitaSessionContract session, @Nonnull String priceList, @Nonnull Currency currency
	) {
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

	private static int[] getAllCategories(@Nonnull EvitaSessionContract session) {
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

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ReferenceIndexingTest");
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

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.server(
				ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(-1)
					.build()
			)
			.build();
	}

	@Nested
	@DisplayName("Reference cardinality validation")
	class ReferenceCardinalityValidationTest {

		@Test
		@DisplayName("Should fail to violate ZERO_OR_ONE cardinality on new entity")
		void shouldFailToViolateReferenceCardinalityExactlyZeroOrOne() {
			try {
				ReferenceIndexingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session
							.defineEntitySchema(Entities.PRODUCT)
							.withReferenceTo(Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE)
							.verifySchemaStrictly()
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
		@DisplayName("Should fail to violate ZERO_OR_ONE cardinality on existing entity")
		void shouldFailToViolateReferenceCardinalityExactlyZeroOrOneOnExistingEntity() {
			ReferenceIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceTo(Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE)
						.verifySchemaStrictly()
						.updateVia(session);

					final EntityBuilder product = session.createNewEntity(Entities.PRODUCT, 1)
						.setReference(Entities.BRAND, 1);
					session.upsertEntity(product);
				}
			);

			try {
				ReferenceIndexingTest.this.evita.updateCatalog(
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
		@DisplayName("Should fail to violate EXACTLY_ONE cardinality on new entity")
		void shouldFailToViolateReferenceCardinalityExactlyOne() {
			try {
				ReferenceIndexingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session
							.defineEntitySchema(Entities.PRODUCT)
							.withReferenceTo(Entities.BRAND, Entities.BRAND, Cardinality.EXACTLY_ONE)
							.verifySchemaStrictly()
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
		@DisplayName("Should fail to violate EXACTLY_ONE cardinality on existing entity")
		void shouldFailToViolateReferenceCardinalityExactlyOneOnExistingEntity() {
			ReferenceIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceTo(Entities.BRAND, Entities.BRAND, Cardinality.EXACTLY_ONE)
						.verifySchemaStrictly()
						.updateVia(session);

					final EntityBuilder product = session.createNewEntity(Entities.PRODUCT, 1)
						.setReference(Entities.BRAND, 1);
					session.upsertEntity(product);
				}
			);

			try {
				ReferenceIndexingTest.this.evita.updateCatalog(
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
		@DisplayName("Should fail to violate ONE_OR_MORE cardinality on new entity")
		void shouldFailToViolateReferenceCardinalityExactlyOneOrMore() {
			try {
				ReferenceIndexingTest.this.evita.updateCatalog(
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
		@DisplayName("Should fail to violate ONE_OR_MORE cardinality on existing entity")
		void shouldFailToViolateReferenceCardinalityExactlyOneOrMoreOnExistingEntity() {
			ReferenceIndexingTest.this.evita.updateCatalog(
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
				ReferenceIndexingTest.this.evita.updateCatalog(
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
		@DisplayName("Should accept entity with references of multiple names and cardinalities")
		void shouldAcceptEntityWithReferencesOfMultipleNames() {
			ReferenceIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceTo(Entities.BRAND, Entities.BRAND, Cardinality.EXACTLY_ONE)
						.withReferenceTo(Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE)
						.withReferenceTo(Entities.STORE, Entities.STORE, Cardinality.ONE_OR_MORE)
						.verifySchemaStrictly()
						.updateVia(session);

					session.createNewEntity(Entities.PRODUCT, 1)
						.setReference(Entities.BRAND, 1)
						.setReference(Entities.CATEGORY, 1)
						.setReference(Entities.CATEGORY, 2)
						.setReference(Entities.CATEGORY, 3)
						.setReference(Entities.STORE, 1)
						.setReference(Entities.STORE, 2)
						.upsertVia(session);
				}
			);

			ReferenceIndexingTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntity product = session.getEntity(Entities.PRODUCT, 1, referenceContentAll())
						.orElseThrow();
					assertEquals(1, product.getReferences(Entities.BRAND).size());
					assertEquals(3, product.getReferences(Entities.CATEGORY).size());
					assertEquals(2, product.getReferences(Entities.STORE).size());
				}
			);
		}

		@Test
		@DisplayName("Should fail to violate mandatory cardinality when references of other names are present")
		void shouldFailToViolateMandatoryReferenceCardinalityWhenOtherReferencesArePresent() {
			try {
				ReferenceIndexingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session
							.defineEntitySchema(Entities.PRODUCT)
							.withReferenceTo(Entities.BRAND, Entities.BRAND, Cardinality.ZERO_OR_MORE)
							.withReferenceTo(Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE)
							.withReferenceTo(Entities.STORE, Entities.STORE, Cardinality.EXACTLY_ONE)
							.verifySchemaStrictly()
							.updateVia(session);

						session.createNewEntity(Entities.PRODUCT, 1)
							.setReference(Entities.BRAND, 1)
							.setReference(Entities.BRAND, 2)
							.setReference(Entities.CATEGORY, 1)
							.upsertVia(session);
					}
				);

				fail("ReferenceCardinalityViolatedException is expected to be thrown");

			} catch (ReferenceCardinalityViolatedException ex) {
				assertEquals(
					"Expected reference cardinalities are violated in entity `PRODUCT`: reference `STORE` is " +
						"expected to be `EXACTLY_ONE` - but entity contains 0 references.",
					ex.getMessage()
				);
			}
		}

		@Test
		@DisplayName("Should report all mandatory references that are missing on the entity")
		void shouldReportAllMissingMandatoryReferences() {
			try {
				ReferenceIndexingTest.this.evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session
							.defineEntitySchema(Entities.PRODUCT)
							.withReferenceTo(Entities.BRAND, Entities.BRAND, Cardinality.EXACTLY_ONE)
							.withReferenceTo(Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE)
							.withReferenceTo(Entities.STORE, Entities.STORE, Cardinality.ONE_OR_MORE)
							.verifySchemaStrictly()
							.updateVia(session);

						session.createNewEntity(Entities.PRODUCT, 1)
							.setReference(Entities.CATEGORY, 1)
							.setReference(Entities.CATEGORY, 2)
							.upsertVia(session);
					}
				);

				fail("ReferenceCardinalityViolatedException is expected to be thrown");

			} catch (ReferenceCardinalityViolatedException ex) {
				// both missing references must be reported in a single exception - the order of the violations
				// follows the iteration order of the schema reference index and is not asserted on purpose
				assertTrue(
					ex.getMessage().startsWith("Expected reference cardinalities are violated in entity `PRODUCT`: "),
					ex.getMessage()
				);
				assertTrue(
					ex.getMessage().contains(
						"reference `BRAND` is expected to be `EXACTLY_ONE` - but entity contains 0 references"
					),
					ex.getMessage()
				);
				assertTrue(
					ex.getMessage().contains(
						"reference `STORE` is expected to be `ONE_OR_MORE` - but entity contains 0 references"
					),
					ex.getMessage()
				);
			}
		}

		@Test
		@DisplayName("Should fail to violate mandatory cardinality when the last reference of the name is removed")
		void shouldFailToViolateMandatoryReferenceCardinalityWhenLastReferenceOfNameIsRemoved() {
			ReferenceIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceTo(Entities.BRAND, Entities.BRAND, Cardinality.EXACTLY_ONE)
						.withReferenceTo(Entities.CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE)
						.verifySchemaStrictly()
						.updateVia(session);

					session.createNewEntity(Entities.PRODUCT, 1)
						.setReference(Entities.BRAND, 1)
						.setReference(Entities.CATEGORY, 1)
						.setReference(Entities.CATEGORY, 2)
						.upsertVia(session);
				}
			);

			try {
				ReferenceIndexingTest.this.evita.updateCatalog(
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
	}

	@Nested
	@DisplayName("Data propagation to reduced indexes")
	class DataPropagationToReducedIndexesTest {

		@Test
		@DisplayName("Should index attributes and prices after reference to hierarchical entity is set")
		void shouldIndexAllAttributesAndPricesAfterReferenceToHierarchicalEntityIsSet() {
			ReferenceIndexingTest.this.evita.updateCatalog(
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
						.setPrice(
							1, PRICE_LIST_BASIC, CURRENCY_CZK,
							BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true
						)
						.setPrice(
							2, PRICE_LIST_BASIC, CURRENCY_EUR,
							BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, false
						)
						.setAttribute(ATTRIBUTE_EAN, "123_ABC");

					// first create entity without references
					session.upsertEntity(product);

					// check there are no specialized entity indexes
					final CatalogContract catalog = ReferenceIndexingTest.this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
					final EntityCollectionContract productCollection =
						catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();

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
					final EntityIndex categoryIndex =
						getReferencedEntityIndex(productCollection, Entities.CATEGORY, 1);
					assertDataWasPropagated(categoryIndex, 1);

					assertNotNull(getReferencedEntityIndex(productCollection, Entities.BRAND, 1));
					final EntityIndex brandIndex =
						getReferencedEntityIndex(productCollection, Entities.BRAND, 1);
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
		@DisplayName("Should create, delete and recreate referenced entity with same attribute")
		void shouldCreateDeleteAndRecreateReferencedEntityWithSameAttribute() {
			ReferenceIndexingTest.this.evita.updateCatalog(
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
									AttributeSchemaEditor::sortable
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

					final SealedEntity loadedEntity =
						session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent()).orElseThrow();

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
		@DisplayName("Should create, delete and recreate sortable attribute for referenced entity")
		void shouldCreateDeleteAndRecreateSortableAttributeForReferencedEntity() {
			ReferenceIndexingTest.this.evita.updateCatalog(
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

					final SealedEntity loadedEntity =
						session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent()).orElseThrow();

					assertEquals(
						5L,
						(Long) loadedEntity
							.getReference(Entities.CATEGORY, 1).orElseThrow()
							.getAttribute(ATTRIBUTE_CATEGORY_PRIORITY)
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("Non-indexed references")
	class NonIndexedReferencesTest {

		@Test
		@DisplayName("Should avoid creating indexes for non-indexed references")
		void shouldAvoidCreatingIndexesForNonIndexedReferences() {
			ReferenceIndexingTest.this.evita.updateCatalog(
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
							thatIs -> thatIs.withAttribute(ATTRIBUTE_CATEGORY_PRIORITY, Long.class)
						)
						.withReferenceToEntity(
							Entities.BRAND,
							Entities.BRAND,
							Cardinality.ZERO_OR_ONE
						)
						.updateVia(session);

					final EntityBuilder product = session.createNewEntity(Entities.PRODUCT, 1)
						.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE)
						.setPrice(
							1, PRICE_LIST_BASIC, CURRENCY_CZK,
							BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true
						)
						.setPrice(
							2, PRICE_LIST_BASIC, CURRENCY_EUR,
							BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, false
						)
						.setAttribute(ATTRIBUTE_EAN, "123_ABC")
						.setReference(Entities.BRAND, 1)
						.setReference(
							Entities.CATEGORY, 1,
							thatIs -> thatIs.setAttribute(ATTRIBUTE_CATEGORY_PRIORITY, 1L)
						);

					// create entity with non-indexed references
					session.upsertEntity(product);

					// verify no reduced indexes are created for non-indexed references
					final CatalogContract catalog = ReferenceIndexingTest.this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
					final EntityCollectionContract productCollection =
						catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();

					assertNull(getReferencedEntityIndex(productCollection, Entities.CATEGORY, 1));
					assertNull(getReferencedEntityIndex(productCollection, Entities.BRAND, 1));
				}
			);
		}
	}

	@Nested
	@DisplayName("Hierarchy operations")
	class HierarchyOperationsTest {

		@Test
		@DisplayName("Should remove deep structure of hierarchical entity")
		void shouldRemoveDeepStructureOfHierarchicalEntity() {
			ReferenceIndexingTest.this.evita.updateCatalog(
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
	}

	@Nested
	@DisplayName("Cross-reference facet propagation")
	class CrossReferenceFacetPropagationTest {

		/**
		 * Asserts that the facet of `facetReferenceName`/`facetPK` is registered for `ownerPK` in the passed index.
		 *
		 * @param index              the index to inspect - deliberately any index, not only the global one
		 * @param facetReferenceName name of the reference the facet belongs to
		 * @param facetPK            primary key of the faceted entity
		 * @param ownerPK            primary key of the entity owning the reference
		 */
		private void assertFacetPresent(
			@Nonnull EntityIndex index,
			@Nonnull String facetReferenceName,
			int facetPK,
			int ownerPK
		) {
			final FacetReferenceIndex facetReferenceIndex = index.getFacetingEntities().get(facetReferenceName);
			assertNotNull(
				facetReferenceIndex,
				"FacetReferenceIndex for `" + facetReferenceName + "` must exist in " + index.getIndexKey()
			);
			final FacetGroupIndex facetGroupIndex = facetReferenceIndex.getFacetsInGroup(null);
			assertNotNull(facetGroupIndex, "Ungrouped FacetGroupIndex must exist in " + index.getIndexKey());
			final FacetIdIndex facetIdIndex = facetGroupIndex.getFacetIdIndex(facetPK);
			assertNotNull(
				facetIdIndex,
				"FacetIdIndex for facet PK " + facetPK + " must exist in " + index.getIndexKey()
			);
			assertTrue(
				facetIdIndex.getRecords().contains(ownerPK),
				"Entity " + ownerPK + " must be registered as a facet of `" + facetReferenceName + ":" + facetPK +
					"` in " + index.getIndexKey()
			);
		}

		/**
		 * Asserts that no facet of `facetReferenceName` is registered in the passed index at all.
		 *
		 * @param index              the index to inspect
		 * @param facetReferenceName name of the reference that must not be faceted
		 */
		private void assertNoFacetOfReference(@Nonnull EntityIndex index, @Nonnull String facetReferenceName) {
			assertNull(
				index.getFacetingEntities().get(facetReferenceName),
				"Reference `" + facetReferenceName + "` is not faceted and must not be registered in "
					+ index.getIndexKey()
			);
		}

		@Test
		@DisplayName("Should index faceted reference into reduced index of its non-faceted sibling")
		void shouldIndexFacetedReferenceIntoReducedIndexOfNonFacetedSibling() {
			// This pins the cross-reference fan-out for the one entity shape nothing else in the suite covers:
			// an indexed but *non-faceted* reference living next to an indexed *faceted* one. The fan-out is what
			// carries the faceted `category` into the reduced index of the non-faceted `brand`, and it is skipped
			// whenever the mutated reference cannot be faceted at all - so this asserts the skip does not reach
			// the case where the mutated reference *is* faceted.
			ReferenceIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.CATEGORY).updateVia(session);
					session.defineEntitySchema(Entities.BRAND).updateVia(session);
					session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1));
					session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));

					session
						.defineEntitySchema(Entities.PRODUCT)
						// indexed, but deliberately NOT faceted
						.withReferenceToEntity(
							Entities.BRAND,
							Entities.BRAND,
							Cardinality.ZERO_OR_ONE,
							whichIs -> whichIs.indexedForFilteringAndPartitioning()
						)
						// indexed AND faceted sibling sitting on the very same entity
						.withReferenceToEntity(
							Entities.CATEGORY,
							Entities.CATEGORY,
							Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs.indexedForFilteringAndPartitioning().faceted()
						)
						.updateVia(session);

					// establish the non-faceted sibling first, so that it is already present in the reference
					// container by the time the faceted reference is inserted and fans out across it
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1).setReference(Entities.BRAND, 1)
					);
					session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(Entities.CATEGORY, 1)
						.upsertVia(session);

					final CatalogContract catalog =
						ReferenceIndexingTest.this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
					final EntityCollectionContract productCollection =
						catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();

					final EntityIndex brandIndex = getReferencedEntityIndex(productCollection, Entities.BRAND, 1);
					assertNotNull(brandIndex, "Reduced index of the non-faceted `brand` reference must exist");
					// the faceted category must have been propagated into the non-faceted sibling's reduced index
					assertFacetPresent(brandIndex, Entities.CATEGORY, 1, 1);
					// ...while the non-faceted brand itself must not be registered as a facet anywhere
					assertNoFacetOfReference(brandIndex, Entities.BRAND);

					final EntityIndex categoryIndex =
						getReferencedEntityIndex(productCollection, Entities.CATEGORY, 1);
					assertNotNull(categoryIndex, "Reduced index of the faceted `category` reference must exist");
					assertNoFacetOfReference(categoryIndex, Entities.BRAND);

					// the global index keeps the faceted reference and only that one
					final EntityIndex globalIndex = getGlobalIndex(productCollection);
					assertNotNull(globalIndex);
					assertFacetPresent(globalIndex, Entities.CATEGORY, 1, 1);
					assertNoFacetOfReference(globalIndex, Entities.BRAND);
				}
			);
		}

		@Test
		@DisplayName("Should back-fill an existing faceted reference into a later non-faceted sibling's index")
		void shouldBackFillExistingFacetedReferenceIntoLaterNonFacetedSiblingIndex() {
			// The reverse order of the test above, and it exercises a different mechanism. Here the faceted
			// `category` already exists when the non-faceted `brand` is added, so the brand's reduced index is
			// created *after* the facet — and it is `ReferenceIndexMutator#indexAllFacets`, reached from the
			// direct path via `indexAllExistingData`, that has to back-fill the existing category facet into it.
			// That scan is skipped when the entity schema declares no faceted reference at all, so this pins that
			// the skip does not reach the case where one does.
			ReferenceIndexingTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(Entities.CATEGORY).updateVia(session);
					session.defineEntitySchema(Entities.BRAND).updateVia(session);
					session.upsertEntity(session.createNewEntity(Entities.CATEGORY, 1));
					session.upsertEntity(session.createNewEntity(Entities.BRAND, 1));

					session
						.defineEntitySchema(Entities.PRODUCT)
						.withReferenceToEntity(
							Entities.BRAND,
							Entities.BRAND,
							Cardinality.ZERO_OR_ONE,
							whichIs -> whichIs.indexedForFilteringAndPartitioning()
						)
						.withReferenceToEntity(
							Entities.CATEGORY,
							Entities.CATEGORY,
							Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs.indexedForFilteringAndPartitioning().faceted()
						)
						.updateVia(session);

					// faceted reference first - the brand's reduced index does not exist yet
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 1).setReference(Entities.CATEGORY, 1)
					);
					final CatalogContract catalog =
						ReferenceIndexingTest.this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
					final EntityCollectionContract productCollection =
						catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
					assertNull(
						getReferencedEntityIndex(productCollection, Entities.BRAND, 1),
						"Brand reduced index must not exist before the brand reference is added"
					);

					// adding the non-faceted sibling creates its reduced index, which must be back-filled
					session.getEntity(Entities.PRODUCT, 1, entityFetchAllContent())
						.orElseThrow()
						.openForWrite()
						.setReference(Entities.BRAND, 1)
						.upsertVia(session);

					final EntityIndex brandIndex = getReferencedEntityIndex(productCollection, Entities.BRAND, 1);
					assertNotNull(brandIndex, "Reduced index of the non-faceted `brand` reference must exist");
					assertFacetPresent(brandIndex, Entities.CATEGORY, 1, 1);
					assertNoFacetOfReference(brandIndex, Entities.BRAND);
				}
			);
		}
	}
}
