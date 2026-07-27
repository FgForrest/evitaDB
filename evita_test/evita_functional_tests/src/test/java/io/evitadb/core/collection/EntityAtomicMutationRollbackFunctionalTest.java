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

package io.evitadb.core.collection;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.TestTags;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the per-entity atomic rollback. When a single entity mutation inside a live
 * transaction fails after partially applying its index changes, the diff-layer savepoint opened by
 * {@link LocalMutationExecutorCollector} must revert exactly that entity's changes while leaving the surrounding
 * transaction (and all previously applied entities) intact, so the client can swallow the failure and keep writing.
 *
 * This is the structural replacement for the old hand-written `undoActions`, whose divergence from the forward path
 * produced the *"Facet 12 not found in index"* secondary failure that masked the real error in production. The
 * scenarios below deliberately drive a failing entity through the facet/reference and price index families (the exact
 * families the deleted undo path threaded through) so that a leaked, half-applied index entry would surface as an
 * orphan facet, a stale reference, or a phantom price-index hit after commit.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Per-entity atomic mutation rollback")
@Tag(TestTags.ENGINE)
@Tag(TestTags.TRANSACTION)
class EntityAtomicMutationRollbackFunctionalTest implements EvitaTestSupport {
	private static final String ATTRIBUTE_CODE = "code";
	private static final String REFERENCE_BRAND = "brand";
	private static final String PRICE_LIST_BASIC = "basic";
	private static final Currency CURRENCY_CZK = Currency.getInstance("CZK");
	private static final int BRAND_PRIMARY_KEY = 100;
	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("EntityAtomicMutationRollbackFunctionalTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);

		// define a product schema carrying a unique code, an indexed+faceted brand reference and an indexed price,
		// then seed brand #100 plus product #1 (unique code "A") in warm-up mode so later live writes can collide
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.BRAND)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);

				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::unique)
					.withReferenceToEntity(
						REFERENCE_BRAND, Entities.BRAND, Cardinality.ZERO_OR_ONE,
						whichIs -> whichIs.indexed().faceted()
					)
					.withPrice()
					.updateVia(session);

				session.upsertEntity(session.createNewEntity(Entities.BRAND, BRAND_PRIMARY_KEY));
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, 1).setAttribute(ATTRIBUTE_CODE, "A")
				);
			}
		);

		// flip the catalog to ALIVE so subsequent writes go through the transactional diff-layer path
		try (final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG)) {
			session.goLiveAndClose();
		}
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("Unique attribute index")
	class UniqueAttributeRollback {

		@Test
		@DisplayName("A failed entity in a batch is reverted while the transaction continues")
		@Tag(TestTags.ATTRIBUTE)
		void shouldRollBackSingleFailedEntityAndKeepTransactionAlive() {
			// one transaction, three entities: #2 succeeds, #3 violates uniqueness (caught & skipped), #4 succeeds.
			// #3's partial index changes must be surgically reverted by the savepoint so #4 and the commit succeed.
			EntityAtomicMutationRollbackFunctionalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 2).setAttribute(ATTRIBUTE_CODE, "B")
					);

					assertThrows(
						UniqueValueViolationException.class,
						() -> session.upsertEntity(
							// duplicate of entity #1's code → fails after partially touching the indexes
							session.createNewEntity(Entities.PRODUCT, 3).setAttribute(ATTRIBUTE_CODE, "A")
						)
					);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 4).setAttribute(ATTRIBUTE_CODE, "C")
					);
				}
			);

			// the transaction committed: #1, #2 and #4 are present, #3 was fully reverted
			EntityAtomicMutationRollbackFunctionalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(session.getEntity(Entities.PRODUCT, 1).isPresent());
					assertTrue(session.getEntity(Entities.PRODUCT, 2).isPresent());
					assertFalse(session.getEntity(Entities.PRODUCT, 3).isPresent(), "Reverted entity #3 must not exist!");
					assertTrue(session.getEntity(Entities.PRODUCT, 4).isPresent());
					return null;
				}
			);

			// the unique index is consistent — code "A" still resolves to entity #1 only (no orphan from #3),
			// and the newly written codes resolve to their entities
			assertCodeResolvesTo("A", 1);
			assertCodeResolvesTo("B", 2);
			assertCodeResolvesTo("C", 4);
		}

		@Test
		@DisplayName("Reusing the reverted code on a fresh entity succeeds after rollback")
		@Tag(TestTags.ATTRIBUTE)
		void shouldAllowReusingRevertedUniqueValueAfterRollback() {
			// entity #3 fails on duplicate code "A"; once reverted, code "A" must be free to (re)assign — proving the
			// savepoint truly removed #3's tentative unique-index entry rather than leaving a dangling reservation
			EntityAtomicMutationRollbackFunctionalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					assertThrows(
						UniqueValueViolationException.class,
						() -> session.upsertEntity(
							session.createNewEntity(Entities.PRODUCT, 3).setAttribute(ATTRIBUTE_CODE, "A")
						)
					);
					// remove the original owner of "A", then a brand-new entity may claim it
					session.deleteEntity(Entities.PRODUCT, 1);
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 5).setAttribute(ATTRIBUTE_CODE, "A")
					);
				}
			);

			EntityAtomicMutationRollbackFunctionalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertFalse(session.getEntity(Entities.PRODUCT, 1).isPresent());
					assertFalse(session.getEntity(Entities.PRODUCT, 3).isPresent());
					assertTrue(session.getEntity(Entities.PRODUCT, 5).isPresent());
					return null;
				}
			);
			assertCodeResolvesTo("A", 5);
		}
	}

	@Nested
	@DisplayName("Reference and facet index")
	class ReferenceFacetRollback {

		@Test
		@DisplayName("A failed entity leaves no orphan facet pointing at the reverted entity")
		@Tag(TestTags.FACET)
		@Tag(TestTags.REFERENCE)
		void shouldRollBackReferenceAndFacetOfFailedEntity() {
			// #2 (good) and #4 (good) both reference brand #100, so they contribute legitimate facet entries.
			// #3 ALSO references brand #100 and carries the duplicate code "A": the reference/facet index entries are
			// written first, then the unique-attribute consistency check throws — the savepoint must scrub #3's facet
			// entry so the surviving facet points only at #2 and #4 (the production "Facet N not found / orphan" class).
			EntityAtomicMutationRollbackFunctionalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 2)
							.setAttribute(ATTRIBUTE_CODE, "B")
							.setReference(REFERENCE_BRAND, BRAND_PRIMARY_KEY)
					);

					assertThrows(
						UniqueValueViolationException.class,
						() -> session.upsertEntity(
							session.createNewEntity(Entities.PRODUCT, 3)
								.setAttribute(ATTRIBUTE_CODE, "A")
								.setReference(REFERENCE_BRAND, BRAND_PRIMARY_KEY)
						)
					);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 4)
							.setAttribute(ATTRIBUTE_CODE, "C")
							.setReference(REFERENCE_BRAND, BRAND_PRIMARY_KEY)
					);
				}
			);

			// #3 is gone; #1 (no brand), #2 and #4 survive
			EntityAtomicMutationRollbackFunctionalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertTrue(session.getEntity(Entities.PRODUCT, 1).isPresent());
					assertTrue(session.getEntity(Entities.PRODUCT, 2).isPresent());
					assertFalse(session.getEntity(Entities.PRODUCT, 3).isPresent(), "Reverted entity #3 must not exist!");
					assertTrue(session.getEntity(Entities.PRODUCT, 4).isPresent());
					return null;
				}
			);

			// the facet for brand #100 must resolve to exactly {#2, #4} — never the reverted #3 (the orphan it would be
			// if its facet entry had leaked) and never a phantom entry that breaks facet computation
			assertFacetBrandResolvesExactlyTo(2, 4);
		}
	}

	@Nested
	@DisplayName("Price index")
	class PriceRollback {

		@Test
		@DisplayName("A failed entity contributes no price-index entry after rollback")
		@Tag(TestTags.PRICE)
		void shouldRollBackPriceOfFailedEntity() {
			// #2 (good) and #4 (good) both publish a sellable basic/CZK price. #3 ALSO publishes such a price and
			// carries the duplicate code "A": the price index entries are written first, then the unique-attribute
			// consistency check throws — the savepoint must remove #3's tentative price-index entry so a
			// priceInPriceLists/priceInCurrency filter returns only the surviving entities.
			EntityAtomicMutationRollbackFunctionalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 2)
							.setAttribute(ATTRIBUTE_CODE, "B")
							.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE)
							.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, true)
					);

					assertThrows(
						UniqueValueViolationException.class,
						() -> session.upsertEntity(
							session.createNewEntity(Entities.PRODUCT, 3)
								.setAttribute(ATTRIBUTE_CODE, "A")
								.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE)
								.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, true)
						)
					);

					session.upsertEntity(
						session.createNewEntity(Entities.PRODUCT, 4)
							.setAttribute(ATTRIBUTE_CODE, "C")
							.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE)
							.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, true)
					);
				}
			);

			EntityAtomicMutationRollbackFunctionalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertFalse(session.getEntity(Entities.PRODUCT, 3).isPresent(), "Reverted entity #3 must not exist!");
					return null;
				}
			);

			// the price index for basic/CZK must contain exactly {#2, #4}; a leaked entry for #3 would surface here
			assertPricedProductsAreExactly(2, 4);
		}
	}

	/**
	 * Asserts that querying the unique {@link #ATTRIBUTE_CODE} attribute for the given value resolves to exactly the
	 * expected primary key.
	 *
	 * @param code              the unique attribute value to look up
	 * @param expectedPrimaryKey the primary key the value must resolve to
	 */
	private void assertCodeResolvesTo(@Nonnull String code, int expectedPrimaryKey) {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final Optional<EntityReference> reference = session.queryOne(
					query(
						collection(Entities.PRODUCT),
						filterBy(attributeEquals(ATTRIBUTE_CODE, code))
					),
					EntityReference.class
				);
				assertEquals(
					new EntityReference(Entities.PRODUCT, expectedPrimaryKey),
					reference.orElseThrow()
				);
				return null;
			}
		);
	}

	/**
	 * Asserts that filtering products by the brand facet {@link #BRAND_PRIMARY_KEY} returns exactly the supplied
	 * primary keys (and no others). A reverted entity whose facet entry leaked would appear here as an extra
	 * primary key.
	 *
	 * @param expectedPrimaryKeys the complete set of product primary keys expected to carry the brand facet
	 */
	private void assertFacetBrandResolvesExactlyTo(int... expectedPrimaryKeys) {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<EntityReference> references = session.queryList(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							userFilter(
								facetHaving(REFERENCE_BRAND, entityPrimaryKeyInSet(BRAND_PRIMARY_KEY))
							)
						)
					),
					EntityReference.class
				);
				final int[] actualPrimaryKeys = references.stream()
					.mapToInt(EntityReference::getPrimaryKey)
					.sorted()
					.toArray();
				final int[] sortedExpected = expectedPrimaryKeys.clone();
				Arrays.sort(sortedExpected);
				assertEquals(
					Arrays.toString(sortedExpected),
					Arrays.toString(actualPrimaryKeys),
					"Brand facet must resolve to exactly the surviving entities (no orphan from a reverted entity)!"
				);
				return null;
			}
		);
	}

	/**
	 * Asserts that filtering products by the indexed {@link #PRICE_LIST_BASIC} price list in {@link #CURRENCY_CZK}
	 * returns exactly the supplied primary keys (and no others). A reverted entity whose price-index entry leaked
	 * would appear here as an extra primary key.
	 *
	 * @param expectedPrimaryKeys the complete set of product primary keys expected to be priced in basic/CZK
	 */
	private void assertPricedProductsAreExactly(int... expectedPrimaryKeys) {
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<EntityReference> references = session.queryList(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							priceInPriceLists(PRICE_LIST_BASIC),
							priceInCurrency(CURRENCY_CZK)
						)
					),
					EntityReference.class
				);
				final int[] actualPrimaryKeys = references.stream()
					.mapToInt(EntityReference::getPrimaryKey)
					.sorted()
					.toArray();
				final int[] sortedExpected = expectedPrimaryKeys.clone();
				Arrays.sort(sortedExpected);
				assertEquals(
					Arrays.toString(sortedExpected),
					Arrays.toString(actualPrimaryKeys),
					"Price index must contain exactly the surviving entities (no phantom from a reverted entity)!"
				);
				return null;
			}
		);
	}

}
