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
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.price.UpsertPriceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.PRICE;

/**
 * Tests for price indexing operations in evitaDB, verifying price sellability changes
 * and price inner record handling modifications.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Price indexing operations")
@Tag(CONTRACT)
@Tag(INDEXING)
@Tag(PRICE)
class PriceIndexingTest implements EvitaTestSupport, IndexingTestSupport {

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

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("PriceIndexingTest");
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

	@Test
	@DisplayName("Should change price sellability by toggling indexed flag")
	void shouldChangePriceSellability() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session
					.defineEntitySchema(Entities.PRODUCT)
					.withPrice()
					.updateVia(session);

				final EntityBuilder product = session.createNewEntity(Entities.PRODUCT, 1)
					.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE)
					.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
					.setPrice(
						2, PRICE_LIST_BASIC, CURRENCY_EUR, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, false);

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
	@DisplayName("Should change inner record handling and remove price")
	void shouldChangePriceInnerRecordHandlingAndRemovePrice() {
		this.evita.updateCatalog(
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

	@DisplayName("Price index must not stay stale when a reference rebuild and a price change share one mutation")
	@ParameterizedTest(name = "old price {0} -> new price {1}")
	@CsvSource({"590.00, 295.00", "295.00, 590.00"})
	void shouldNotLeaveStalePriceIndexWhenReferenceRebuildAndPriceChangeShareOneMutation(
		@Nonnull BigDecimal oldPrice,
		@Nonnull BigDecimal newPrice
	) {
		// Reproduces #1193: a single EntityUpsertMutation that rebuilds the (filtering+partitioning)
		// reference FIRST - which re-seeds the reduced reference index from the still-unchanged body -
		// and only afterwards changes the LOWEST_PRICE inner-record prices reusing their internal price
		// ids. The transactional change layer used to keep the OLD price record because the new record
		// is identity-equal (same internal price id) yet content-different, so the index stayed stale.
		final int productPk = 100;
		final int categoryPkOld = 500;
		final int categoryPkNew = 501;
		final int innerA = 200;
		final int innerB = 201;

		// define the Category and Product (price + indexed reference) schemas, then seed in warm-up mode
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withPriceInCurrency(CURRENCY_CZK)
					.withReferenceToEntity(
						REFERENCE_PRODUCT_CATEGORY, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);

				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, categoryPkOld));
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, categoryPkNew));
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, productPk)
						.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_CZK, innerA, oldPrice, BigDecimal.ZERO, oldPrice, true)
						.setPrice(2, PRICE_LIST_BASIC, CURRENCY_CZK, innerB, oldPrice, BigDecimal.ZERO, oldPrice, true)
						.setReference(REFERENCE_PRODUCT_CATEGORY, categoryPkOld)
				);
			}
		);

		// switch to transactional mode - only then the mutation travels through the WAL / transaction
		// apply path (index-first, then body) that produced the divergence
		this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);

		// sanity: the product is found by its OLD price in the OLD category
		assertTrue(
			isProductFoundByPriceInCategory(productPk, categoryPkOld, oldPrice),
			"Pre-condition: product should be indexed with its OLD price in the OLD category"
		);

		// the offending mutation: rebuild the reference first, then change both prices - all in one tx
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.applyMutation(
					new EntityUpsertMutation(
						Entities.PRODUCT,
						productPk,
						EntityExistence.MUST_EXIST,
						List.of(
							new RemoveReferenceMutation(REFERENCE_PRODUCT_CATEGORY, categoryPkOld),
							new InsertReferenceMutation(new ReferenceKey(REFERENCE_PRODUCT_CATEGORY, categoryPkNew)),
							new UpsertPriceMutation(
								new PriceKey(1, PRICE_LIST_BASIC, CURRENCY_CZK), innerA,
								newPrice, BigDecimal.ZERO, newPrice, null, true
							),
							new UpsertPriceMutation(
								new PriceKey(2, PRICE_LIST_BASIC, CURRENCY_CZK), innerB,
								newPrice, BigDecimal.ZERO, newPrice, null, true
							)
						)
					)
				);
			}
		);

		// the index must reflect the new price: the product is found by the NEW price and NOT by the OLD one
		assertTrue(
			isProductFoundByPriceInCategory(productPk, categoryPkNew, newPrice),
			"Product must be found by its NEW price in the NEW category - the index must reflect the change"
		);
		assertFalse(
			isProductFoundByPriceInCategory(productPk, categoryPkNew, oldPrice),
			"Product must NOT be found by its OLD price - a stale index would still match the original value"
		);
	}

	/**
	 * Queries the catalog through the public API for the given product, filtered by a reference to the
	 * given category and by an exact price-for-sale. Both the reference filter (reduced reference index)
	 * and the price filter (price index) are served from the committed indexes, so the result reflects
	 * whether the indexes carry the expected price.
	 */
	private boolean isProductFoundByPriceInCategory(int productPk, int categoryPk, @Nonnull BigDecimal priceWithTax) {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOneEntityReference(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							entityPrimaryKeyInSet(productPk),
							referenceHaving(REFERENCE_PRODUCT_CATEGORY, entityPrimaryKeyInSet(categoryPk)),
							priceInCurrency(CURRENCY_CZK),
							priceInPriceLists(PRICE_LIST_BASIC),
							priceBetween(priceWithTax, priceWithTax)
						)
					)
				).isPresent();
			}
		);
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
}
