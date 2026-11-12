/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.functional.price;

import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.PRICE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that the engine filters and orders entities by the very price for sale the fetched entity reports, on the
 * index path as well as on the prefetch path, for the shapes of data where the two used to follow different rules:
 * several prices of one inner record in one price list, an entity without inner record handling whose prices carry
 * inner record ids, a price filter combined with the lowest price handling, and a price list named twice in the query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Selection of the price for sale shared by the engine and the entity")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(PRICE)
@Tag(ORDER)
@Tag(FILTER)
class PriceForSaleSelectionRuleFunctionalTest {
	private static final String PRODUCT = "product";
	private static final String PRICE_LIST_BASIC = "basic";
	private static final String PRICE_LIST_REFERENCE = "reference";
	private static final String PRICE_LIST_A = "A";
	private static final String PRICE_LIST_B = "B";
	private static final Currency CZK = Currency.getInstance("CZK");

	/**
	 * Returns primary keys of the products matching the filter, in the order the engine sorts them by.
	 *
	 * @param evita          the evitaDB instance to query
	 * @param filter         the filter constraint
	 * @param orderBy        the ordering constraint, `null` for the order of primary keys
	 * @param queryPriceMode price mode of the query
	 * @param debugMode      {@link DebugMode#PREFER_INDEX_SCAN} or {@link DebugMode#PREFER_PREFETCHING}
	 * @return primary keys in the order returned by the engine
	 */
	@Nonnull
	private static List<Integer> queryProducts(
		@Nonnull Evita evita,
		@Nonnull FilterConstraint filter,
		@Nullable OrderConstraint orderBy,
		@Nonnull QueryPriceMode queryPriceMode,
		@Nonnull DebugMode debugMode
	) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<EntityReferenceContract> references = session.queryListOfEntityReferences(
					query(
						collection(PRODUCT),
						filterBy(filter),
						orderBy == null ? null : orderBy(orderBy),
						require(
							priceType(queryPriceMode),
							debug(debugMode)
						)
					)
				);
				final List<Integer> primaryKeys = new ArrayList<>(references.size());
				for (final EntityReferenceContract reference : references) {
					primaryKeys.add(reference.getPrimaryKey());
				}
				return primaryKeys;
			}
		);
	}

	/**
	 * Fetches the product with its prices by the price constraints of the passed filter and returns the price for sale
	 * it reports.
	 *
	 * @param evita          the evitaDB instance to query
	 * @param primaryKey     primary key of the product
	 * @param priceFilter    the price constraints the product is fetched by
	 * @param queryPriceMode price mode of the query
	 * @return the price for sale of the product
	 */
	@Nonnull
	private static PriceContract queryPriceForSale(
		@Nonnull Evita evita,
		int primaryKey,
		@Nonnull FilterConstraint priceFilter,
		@Nonnull QueryPriceMode queryPriceMode
	) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity product = session.queryOneSealedEntity(
					query(
						collection(PRODUCT),
						filterBy(and(entityPrimaryKeyInSet(primaryKey), priceFilter)),
						require(
							priceType(queryPriceMode),
							entityFetch(priceContentRespectingFilter())
						)
					)
				).orElseThrow();
				return product.getPriceForSale().orElseThrow();
			}
		);
	}

	/**
	 * Returns the validity spanning the year 2020.
	 *
	 * @return the validity
	 */
	@Nonnull
	private static DateTimeRange firstYear() {
		return DateTimeRange.between(
			OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
			OffsetDateTime.of(2020, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC)
		);
	}

	/**
	 * Returns the validity spanning the year 2021, which does not overlap {@link #firstYear()}.
	 *
	 * @return the validity
	 */
	@Nonnull
	private static DateTimeRange secondYear() {
		return DateTimeRange.between(
			OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
			OffsetDateTime.of(2021, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC)
		);
	}

	/**
	 * Sets an indexed price in CZK whose amount without tax is the base amount raised by the passed increment and
	 * whose amount with tax is the base amount with tax raised by the very same increment.
	 *
	 * @param product       builder of the product the price is set on
	 * @param priceId       id of the price
	 * @param priceList     price list of the price
	 * @param innerRecordId inner record the price belongs to, `null` for a price without one
	 * @param baseAmount    whole amount without tax the price derives from
	 * @param taxRate       tax rate in percent applied to the base amount
	 * @param increment     whole amount added to both the amount without and with tax
	 * @param validity      validity of the price, `null` for a price valid at any moment
	 */
	private static void setPrice(
		@Nonnull EntityBuilder product,
		int priceId,
		@Nonnull String priceList,
		@Nullable Integer innerRecordId,
		int baseAmount,
		int taxRate,
		int increment,
		@Nullable DateTimeRange validity
	) {
		final BigDecimal base = new BigDecimal(baseAmount).setScale(2);
		final BigDecimal rate = new BigDecimal(taxRate);
		final BigDecimal withTax = base.add(base.multiply(rate).movePointLeft(2)).setScale(2);
		final BigDecimal raise = new BigDecimal(increment).setScale(2);
		if (innerRecordId == null) {
			product.setPrice(priceId, priceList, CZK, base.add(raise), rate, withTax.add(raise), validity, true);
		} else {
			product.setPrice(
				priceId, priceList, CZK, innerRecordId, base.add(raise), rate, withTax.add(raise), validity, true
			);
		}
	}

	/**
	 * Sets an indexed price in CZK without tax, so the amount with tax equals the amount without tax.
	 *
	 * @param product       builder of the product the price is set on
	 * @param priceId       id of the price
	 * @param priceList     price list of the price
	 * @param innerRecordId inner record the price belongs to, `null` for a price without one
	 * @param amount        whole amount of the price
	 * @param validity      validity of the price, `null` for a price valid at any moment
	 */
	private static void setPrice(
		@Nonnull EntityBuilder product,
		int priceId,
		@Nonnull String priceList,
		@Nullable Integer innerRecordId,
		int amount,
		@Nullable DateTimeRange validity
	) {
		setPrice(product, priceId, priceList, innerRecordId, amount, 0, 0, validity);
	}

	/**
	 * Several prices of one inner record in one price list are all candidates when the query names no moment, because
	 * their validity is ignored then. The cheapest of them without tax stands for the inner record, a tie going to the
	 * lower price id.
	 */
	@Nested
	@DisplayName("Several prices of one inner record in one price list")
	class SeveralPricesOfOneInnerRecordInOnePriceList {

		/**
		 * Inner record 1 has price 10 selling for 200 and price 30 selling for 100, valid in different years; inner
		 * record 2 has price 20 selling for 150. Inner record 1 is represented by price 30, the cheaper one, so the
		 * product sells for 100 and comes before the product selling for 125.
		 */
		@Test
		@DisplayName("should sell the cheapest price of an inner record when its prices differ in amount")
		void shouldSellCheapestPriceOfInnerRecordWhenItsPricesDifferInAmount(Evita evita) {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(PRODUCT).withPrice().updateVia(session);
					final EntityBuilder product = session.createNewEntity(PRODUCT, 1)
						.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE);
					setPrice(product, 10, PRICE_LIST_BASIC, 1, 200, firstYear());
					setPrice(product, 30, PRICE_LIST_BASIC, 1, 100, secondYear());
					setPrice(product, 20, PRICE_LIST_BASIC, 2, 150, null);
					session.upsertEntity(product);
					final EntityBuilder inBetween = session.createNewEntity(PRODUCT, 2)
						.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE);
					setPrice(inBetween, 40, PRICE_LIST_BASIC, 1, 125, null);
					session.upsertEntity(inBetween);
				}
			);

			final FilterConstraint priceFilter = and(priceInCurrency(CZK), priceInPriceLists(PRICE_LIST_BASIC));
			assertAll(
				() -> assertEquals(
					new BigDecimal("100.00"),
					queryPriceForSale(evita, 1, priceFilter, QueryPriceMode.WITH_TAX).priceWithTax()
				),
				() -> assertEquals(
					List.of(1, 2),
					queryProducts(
						evita, priceFilter, priceNatural(), QueryPriceMode.WITH_TAX, DebugMode.PREFER_INDEX_SCAN
					)
				)
			);
		}

		/**
		 * Inner record 1 has price 10 (110 without tax, 121 with tax) and price 30 (100 without tax, 121 with tax),
		 * valid in different years; inner record 2 has price 20 selling for 121 with tax. Inner record 1 is represented
		 * by price 30, the cheaper one without tax, whatever the price mode of the query. Queried with tax, price 30
		 * ties with price 20, and the lower price id wins: the product sells through inner record 2, whose reference
		 * price discounts it by 1000.
		 */
		@Test
		@DisplayName("should represent an inner record by its price cheapest without tax when its prices tie with tax")
		void shouldRepresentInnerRecordByPriceCheapestWithoutTaxWhenItsPricesTieWithTax(Evita evita) {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(PRODUCT).withPrice().updateVia(session);
					final EntityBuilder tied = session.createNewEntity(PRODUCT, 1)
						.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE);
					setPrice(tied, 10, PRICE_LIST_BASIC, 1, 110, 10, 0, firstYear());
					setPrice(tied, 30, PRICE_LIST_BASIC, 1, 100, 21, 0, secondYear());
					setPrice(tied, 20, PRICE_LIST_BASIC, 2, 100, 21, 0, null);
					setPrice(tied, 11, PRICE_LIST_REFERENCE, 1, 110, 10, 50, null);
					setPrice(tied, 21, PRICE_LIST_REFERENCE, 2, 100, 21, 1000, null);
					session.upsertEntity(tied);
					final EntityBuilder control = session.createNewEntity(PRODUCT, 2)
						.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE);
					setPrice(control, 40, PRICE_LIST_BASIC, 1, 100, 21, 0, null);
					setPrice(control, 41, PRICE_LIST_REFERENCE, 1, 100, 21, 500, null);
					session.upsertEntity(control);
				}
			);

			final FilterConstraint priceFilter = and(priceInCurrency(CZK), priceInPriceLists(PRICE_LIST_BASIC));
			assertAll(
				() -> assertEquals(
					20,
					queryPriceForSale(evita, 1, priceFilter, QueryPriceMode.WITH_TAX).priceId()
				),
				() -> assertEquals(
					List.of(1, 2),
					queryProducts(
						evita, priceFilter, priceDiscount(DESC, PRICE_LIST_REFERENCE),
						QueryPriceMode.WITH_TAX, DebugMode.PREFER_INDEX_SCAN
					)
				)
			);
		}

		/**
		 * An entity without inner record handling has price 10 selling for 200 and price 20 selling for 100 in one
		 * price list, valid in different years. Price 20, the cheaper one, is the price for sale, so the product comes
		 * before the product selling for 150.
		 */
		@Test
		@DisplayName("should sell the cheapest price of an entity without inner record handling when its prices differ")
		void shouldSellCheapestPriceOfEntityWithoutInnerRecordHandlingWhenItsPricesDiffer(Evita evita) {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(PRODUCT).withPrice().updateVia(session);
					final EntityBuilder product = session.createNewEntity(PRODUCT, 1)
						.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE);
					setPrice(product, 10, PRICE_LIST_BASIC, null, 200, firstYear());
					setPrice(product, 20, PRICE_LIST_BASIC, null, 100, secondYear());
					session.upsertEntity(product);
					final EntityBuilder inBetween = session.createNewEntity(PRODUCT, 2)
						.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE);
					setPrice(inBetween, 30, PRICE_LIST_BASIC, null, 150, null);
					session.upsertEntity(inBetween);
				}
			);

			final FilterConstraint priceFilter = and(priceInCurrency(CZK), priceInPriceLists(PRICE_LIST_BASIC));
			assertAll(
				() -> assertEquals(
					20,
					queryPriceForSale(evita, 1, priceFilter, QueryPriceMode.WITH_TAX).priceId()
				),
				() -> assertEquals(
					List.of(1, 2),
					queryProducts(
						evita, priceFilter, priceNatural(), QueryPriceMode.WITH_TAX, DebugMode.PREFER_INDEX_SCAN
					)
				)
			);
		}

	}

	/**
	 * For an entity without inner record handling the inner record id of a price has no meaning: all prices of
	 * a price list form one group and the entity has a single selected price, which the price filter tests and the
	 * ordering sorts by.
	 */
	@Nested
	@DisplayName("Entity without inner record handling whose prices carry inner record ids")
	class EntityWithoutInnerRecordHandling {

		/**
		 * Product 1 has price 10 of inner record 1 selling for 100 and price 20 of inner record 2 selling for 200,
		 * valid in different years, so both are candidates of a query naming no moment. Product 2 sells for 150.
		 *
		 * @param evita the evitaDB instance the products are created in
		 */
		private void createProducts(@Nonnull Evita evita) {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(PRODUCT).withPrice().updateVia(session);
					final EntityBuilder product = session.createNewEntity(PRODUCT, 1)
						.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE);
					setPrice(product, 10, PRICE_LIST_BASIC, 1, 100, firstYear());
					setPrice(product, 20, PRICE_LIST_BASIC, 2, 200, secondYear());
					session.upsertEntity(product);
					final EntityBuilder other = session.createNewEntity(PRODUCT, 2)
						.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE);
					setPrice(other, 30, PRICE_LIST_BASIC, null, 150, null);
					session.upsertEntity(other);
				}
			);
		}

		@Test
		@DisplayName("should filter by the one selected price on both paths")
		void shouldFilterByOneSelectedPriceOnBothPaths(Evita evita) {
			createProducts(evita);

			final FilterConstraint filter = and(
				entityPrimaryKeyInSet(1, 2),
				priceInCurrency(CZK),
				priceInPriceLists(PRICE_LIST_BASIC),
				priceBetween(new BigDecimal("140"), new BigDecimal("220"))
			);
			assertAll(
				() -> assertEquals(
					10,
					queryPriceForSale(
						evita, 1, and(priceInCurrency(CZK), priceInPriceLists(PRICE_LIST_BASIC)),
						QueryPriceMode.WITH_TAX
					).priceId()
				),
				() -> assertEquals(
					List.of(2),
					queryProducts(evita, filter, null, QueryPriceMode.WITH_TAX, DebugMode.PREFER_INDEX_SCAN)
				),
				() -> assertEquals(
					List.of(2),
					queryProducts(evita, filter, null, QueryPriceMode.WITH_TAX, DebugMode.PREFER_PREFETCHING)
				)
			);
		}

		@Test
		@DisplayName("should order by the one selected price on both paths")
		void shouldOrderByOneSelectedPriceOnBothPaths(Evita evita) {
			createProducts(evita);

			final FilterConstraint filter = and(
				entityPrimaryKeyInSet(1, 2),
				priceInCurrency(CZK),
				priceInPriceLists(PRICE_LIST_BASIC)
			);
			assertAll(
				() -> assertEquals(
					List.of(1, 2),
					queryProducts(evita, filter, priceNatural(), QueryPriceMode.WITH_TAX, DebugMode.PREFER_INDEX_SCAN)
				),
				() -> assertEquals(
					List.of(2, 1),
					queryProducts(
						evita, filter, priceNatural(DESC), QueryPriceMode.WITH_TAX, DebugMode.PREFER_INDEX_SCAN
					)
				),
				() -> assertEquals(
					List.of(1, 2),
					queryProducts(
						evita, filter, priceNatural(), QueryPriceMode.WITH_TAX, DebugMode.PREFER_PREFETCHING
					)
				),
				() -> assertEquals(
					List.of(2, 1),
					queryProducts(
						evita, filter, priceNatural(DESC), QueryPriceMode.WITH_TAX, DebugMode.PREFER_PREFETCHING
					)
				)
			);
		}

	}

	/**
	 * With the lowest price handling and a price filter, the index path and the prefetch path represent the entity
	 * in ordering and in the discount by the same variant, and the price for sale the entity reports stays the
	 * cheapest of all its variants whatever the filter.
	 *
	 * Product 1 has inner record 1 selling for 100 with a reference price of 150 and inner record 2 selling for 200
	 * with a reference price of 1200. Product 2 has a single inner record selling for 180 with a reference price of
	 * 680. Both products have a price inside the range from 150 to 250.
	 */
	@Nested
	@DisplayName("Price filter with the lowest price handling")
	class PriceFilterWithLowestPriceHandling {
		/**
		 * Creates the two products described on the class.
		 *
		 * @param evita the evitaDB instance the products are created in
		 */
		private void createProducts(@Nonnull Evita evita) {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(PRODUCT).withPrice().updateVia(session);
					final EntityBuilder product = session.createNewEntity(PRODUCT, 1)
						.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE);
					setPrice(product, 11, PRICE_LIST_BASIC, 1, 100, null);
					setPrice(product, 12, PRICE_LIST_BASIC, 2, 200, null);
					setPrice(product, 13, PRICE_LIST_REFERENCE, 1, 150, null);
					setPrice(product, 14, PRICE_LIST_REFERENCE, 2, 1200, null);
					session.upsertEntity(product);
					final EntityBuilder other = session.createNewEntity(PRODUCT, 2)
						.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE);
					setPrice(other, 21, PRICE_LIST_BASIC, 1, 180, null);
					setPrice(other, 23, PRICE_LIST_REFERENCE, 1, 680, null);
					session.upsertEntity(other);
				}
			);
		}

		/**
		 * Returns the filter of both products in the basic price list, limited to the range from 150 to 250.
		 *
		 * @return the filter constraint
		 */
		@Nonnull
		private FilterConstraint rangeFilter() {
			return and(
				entityPrimaryKeyInSet(1, 2),
				priceInCurrency(CZK),
				priceInPriceLists(PRICE_LIST_BASIC),
				priceBetween(new BigDecimal("150"), new BigDecimal("250"))
			);
		}

		@Test
		@DisplayName("should order by the price alike on both paths")
		void shouldOrderByPriceAlikeOnBothPaths(Evita evita) {
			createProducts(evita);

			assertEquals(
				queryProducts(
					evita, rangeFilter(), priceNatural(), QueryPriceMode.WITH_TAX, DebugMode.PREFER_INDEX_SCAN
				),
				queryProducts(
					evita, rangeFilter(), priceNatural(), QueryPriceMode.WITH_TAX, DebugMode.PREFER_PREFETCHING
				)
			);
		}

		@Test
		@DisplayName("should order by the discount alike on both paths")
		void shouldOrderByDiscountAlikeOnBothPaths(Evita evita) {
			createProducts(evita);

			final OrderConstraint byDiscount = priceDiscount(DESC, PRICE_LIST_REFERENCE);
			assertEquals(
				queryProducts(evita, rangeFilter(), byDiscount, QueryPriceMode.WITH_TAX, DebugMode.PREFER_INDEX_SCAN),
				queryProducts(evita, rangeFilter(), byDiscount, QueryPriceMode.WITH_TAX, DebugMode.PREFER_PREFETCHING)
			);
		}

		@Test
		@DisplayName("should report the cheapest of all variants as the price for sale despite the range")
		void shouldReportCheapestOfAllVariantsAsPriceForSaleDespiteRange(Evita evita) {
			createProducts(evita);

			assertEquals(
				11,
				queryPriceForSale(
					evita, 1,
					and(
						priceInCurrency(CZK),
						priceInPriceLists(PRICE_LIST_BASIC),
						priceBetween(new BigDecimal("150"), new BigDecimal("250"))
					),
					QueryPriceMode.WITH_TAX
				).priceId()
			);
		}

	}

	/**
	 * A price list named twice in the query keeps the position it is named at first.
	 */
	@Nested
	@DisplayName("Price list named twice in the query")
	class PriceListNamedTwice {

		/**
		 * Product 1 sells for 100 in price list A and for 200 in price list B, product 2 sells for 150 in price list
		 * B only. Queried with the price lists A, B, A, price list A takes precedence.
		 */
		@Test
		@DisplayName("should take the first position of a price list named twice on both paths")
		void shouldTakeFirstPositionOfPriceListNamedTwiceOnBothPaths(Evita evita) {
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.defineEntitySchema(PRODUCT).withPrice().updateVia(session);
					final EntityBuilder product = session.createNewEntity(PRODUCT, 1)
						.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE);
					setPrice(product, 1, PRICE_LIST_A, null, 100, null);
					setPrice(product, 2, PRICE_LIST_B, null, 200, null);
					session.upsertEntity(product);
					final EntityBuilder other = session.createNewEntity(PRODUCT, 2)
						.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE);
					setPrice(other, 3, PRICE_LIST_B, null, 150, null);
					session.upsertEntity(other);
				}
			);

			final FilterConstraint priceFilter = and(
				priceInCurrency(CZK), priceInPriceLists(PRICE_LIST_A, PRICE_LIST_B, PRICE_LIST_A)
			);
			final FilterConstraint rangeFilter = and(
				entityPrimaryKeyInSet(1, 2),
				priceInCurrency(CZK),
				priceInPriceLists(PRICE_LIST_A, PRICE_LIST_B, PRICE_LIST_A),
				priceBetween(new BigDecimal("90"), new BigDecimal("110"))
			);
			assertAll(
				() -> assertEquals(
					1,
					queryPriceForSale(evita, 1, priceFilter, QueryPriceMode.WITH_TAX).priceId()
				),
				() -> assertEquals(
					List.of(1),
					queryProducts(evita, rangeFilter, null, QueryPriceMode.WITH_TAX, DebugMode.PREFER_INDEX_SCAN)
				),
				() -> assertEquals(
					List.of(1),
					queryProducts(evita, rangeFilter, null, QueryPriceMode.WITH_TAX, DebugMode.PREFER_PREFETCHING)
				)
			);
		}

	}

}
