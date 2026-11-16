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

import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.evitadb.api.functional.price.PriceDiscountOrderingTestSupport.CZK;
import static io.evitadb.api.functional.price.PriceDiscountOrderingTestSupport.PRICE_LIST_BASIC;
import static io.evitadb.api.functional.price.PriceDiscountOrderingTestSupport.PRICE_LIST_REFERENCE;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static io.evitadb.api.functional.price.PriceDiscountOrderingTestSupport.PRODUCT;
import static io.evitadb.api.functional.price.PriceDiscountOrderingTestSupport.queryOrderByDiscount;
import static io.evitadb.api.functional.price.PriceDiscountOrderingTestSupport.setIndexedPrice;
import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.PRICE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies that ordering by price discount is stable for entities using
 * {@link PriceInnerRecordHandling#LOWEST_PRICE} whose inner records sell for the same price but are discounted against
 * different reference prices. The discount pairs the reference price with the inner record of the selected selling
 * price, so the selection among the tied inner records decides the discount and, through it, the order - which
 * makes the order the observable both of the stability of the selection and of its agreement with the price for sale
 * the fetched entity reports. The same agreement is verified for an inner record with several prices tied in a single
 * price list, where the selection within the inner record decides the price the entity sells for, and for the price
 * mode of the query, which decides the inner record the entity sells through on the index path, on the prefetch path
 * and in the price for sale, the price range and the accompanying prices the entity reports.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Ordering of entities whose prices tie on the selling amount")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(PRICE)
@Tag(ORDER)
class LowestPriceTieOrderingFunctionalTest {
	/**
	 * Number of products whose two inner records tie on the selling price.
	 */
	private static final int TIED_PRODUCT_COUNT = 20;
	/**
	 * Number of identical queries whose orders are compared.
	 */
	private static final int REPETITIONS = 20;
	/**
	 * Number of products in each of the two groups of the fixture tied in a single price mode.
	 */
	private static final int GROUP_SIZE = 10;

	@Test
	@DisplayName("should order tied products by the discount of the lower price id for repeated identical queries")
	void shouldOrderTiedProductsByDiscountOfLowerPriceIdForRepeatedQueries(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(PRODUCT).withPrice().updateVia(session);
				for (int pk = 1; pk <= TIED_PRODUCT_COUNT; pk++) {
					final EntityBuilder product = session.createNewEntity(PRODUCT, pk)
						.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE);
					// both inner records sell for 100, the reference price of inner record 2 gives the larger discount
					setIndexedPrice(product, pk * 10 + 1, PRICE_LIST_BASIC, 1, 100);
					setIndexedPrice(product, pk * 10 + 2, PRICE_LIST_BASIC, 2, 100);
					setIndexedPrice(product, pk * 10 + 3, PRICE_LIST_REFERENCE, 1, 100 + pk);
					setIndexedPrice(product, pk * 10 + 4, PRICE_LIST_REFERENCE, 2, 1100 + pk);
					session.upsertEntity(product);
				}
				// the product with the highest primary key has a single inner record, so it never ties
				final int lastPk = TIED_PRODUCT_COUNT + 1;
				final EntityBuilder lastProduct = session.createNewEntity(PRODUCT, lastPk)
					.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE);
				setIndexedPrice(lastProduct, lastPk * 10 + 1, PRICE_LIST_BASIC, 1, 100);
				setIndexedPrice(lastProduct, lastPk * 10 + 3, PRICE_LIST_REFERENCE, 1, 5100);
				session.upsertEntity(lastProduct);
			}
		);

		final Map<List<Integer>, Integer> orderCounts = CollectionUtils.createLinkedHashMap(REPETITIONS);
		for (int i = 0; i < REPETITIONS; i++) {
			orderCounts.merge(
				queryOrderByDiscount(evita, QueryPriceMode.WITH_TAX, PRICE_LIST_REFERENCE), 1, Integer::sum
			);
		}

		// the lower price id wins the tie, so every tied product sells through inner record 1 and its discount is its
		// primary key, below the discount of the product with a single inner record
		assertEquals(
			Map.of(
				List.of(21, 20, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1), REPETITIONS
			),
			orderCounts,
			"orders (order -> times returned): " + orderCounts
		);
	}

	@Test
	@DisplayName("should order by the discount of the price for sale the entity reports when queried with tax")
	void shouldOrderByDiscountOfEntityPriceForSaleWhenQueriedWithTax(Evita evita) {
		createProductsTiedInSinglePriceMode(evita);

		assertEquals(
			computeOrderByEntityPriceForSale(evita, QueryPriceMode.WITH_TAX),
			queryOrderByDiscount(evita, QueryPriceMode.WITH_TAX, PRICE_LIST_REFERENCE)
		);
	}

	@Test
	@DisplayName("should order by the discount of the price for sale the entity reports when queried without tax")
	void shouldOrderByDiscountOfEntityPriceForSaleWhenQueriedWithoutTax(Evita evita) {
		createProductsTiedInSinglePriceMode(evita);

		final SealedEntity firstProduct = queryFirstProduct(
			evita,
			entityFetch(priceContentRespectingFilter())
		);
		assertAll(
			// inner record 2 of the first product sells for 100.00 without tax, inner record 1 for 110.00
			() -> assertEquals(
				new BigDecimal("100.00"), firstProduct.getPriceForSale().orElseThrow().priceWithoutTax()
			),
			() -> assertEquals(
				computeOrderByEntityPriceForSale(evita, QueryPriceMode.WITHOUT_TAX),
				queryOrderByDiscount(evita, QueryPriceMode.WITHOUT_TAX, PRICE_LIST_REFERENCE)
			)
		);
	}

	/**
	 * The price for sale range and the accompanying prices are computed by the same comparison as the price for sale.
	 * Queried without tax, the first product's cheapest inner record is inner record 2 (`100.00` against `110.00`), so
	 * the lowest price of the range is `100.00` and the reference price accompanying the price for sale is the one of
	 * inner record 2 (`1101.00` without tax).
	 */
	@Test
	@DisplayName("should compute the price range and the accompanying prices by the price without tax when queried so")
	void shouldComputePriceRangeAndAccompanyingPricesByPriceWithoutTaxWhenQueriedSo(Evita evita) {
		createProductsTiedInSinglePriceMode(evita);

		final SealedEntity firstProduct = queryFirstProduct(
			evita,
			entityFetch(
				priceContentRespectingFilter(PRICE_LIST_REFERENCE),
				accompanyingPriceContent(PRICE_LIST_REFERENCE, PRICE_LIST_REFERENCE)
			)
		);
		assertAll(
			() -> assertEquals(
				new BigDecimal("100.00"),
				firstProduct.getPriceRangeForSale().orElseThrow().lowestPrice().priceWithoutTax()
			),
			() -> assertEquals(
				new BigDecimal("1101.00"),
				firstProduct.getPriceForSaleWithAccompanyingPrices().orElseThrow()
					.accompanyingPrices().get(PRICE_LIST_REFERENCE).orElseThrow().priceWithoutTax()
			)
		);
	}

	/**
	 * An entity fetched without any price constraint in the filter has no price for sale context, so its price for sale
	 * is computed from the arguments of the call. The price mode is not one of them: it is the mode of the query the
	 * entity was fetched by, also for the builder opened on the entity.
	 */
	@Test
	@DisplayName("should compute the price for sale by the price mode of the query when called with explicit arguments")
	void shouldComputePriceForSaleByQueryPriceModeWhenCalledWithExplicitArguments(Evita evita) {
		createProductsTiedInSinglePriceMode(evita);

		final SealedEntity firstProduct = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOneSealedEntity(
					query(
						collection(PRODUCT),
						filterBy(entityPrimaryKeyInSet(1)),
						require(
							priceType(QueryPriceMode.WITHOUT_TAX),
							entityFetch(priceContentAll())
						)
					)
				).orElseThrow();
			}
		);
		assertAll(
			() -> assertEquals(
				new BigDecimal("100.00"),
				firstProduct.getPriceForSale(CZK, null, PRICE_LIST_BASIC).orElseThrow().priceWithoutTax()
			),
			() -> assertEquals(
				new BigDecimal("100.00"),
				firstProduct.getPriceRangeForSale(CZK, null, PRICE_LIST_BASIC).orElseThrow().lowestPrice()
					.priceWithoutTax()
			),
			() -> assertEquals(
				new BigDecimal("100.00"),
				firstProduct.openForWrite().getPriceForSale(CZK, null, PRICE_LIST_BASIC).orElseThrow()
					.priceWithoutTax()
			)
		);
	}

	/**
	 * A limited view of a fetched entity is built by a request that carries only the content requirements, so the
	 * price mode of that request is the default one. The view keeps the price context of the entity it limits and
	 * must keep its price mode as well.
	 */
	@Test
	@DisplayName("should keep the price mode of the fetched entity in its limited view")
	void shouldKeepPriceModeOfFetchedEntityInItsLimitedView(Evita evita) {
		createProductsTiedInSinglePriceMode(evita);

		final BigDecimal priceForSale = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity firstProduct = session.queryOneSealedEntity(
					query(
						collection(PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(1),
								priceInCurrency(CZK),
								priceInPriceLists(PRICE_LIST_BASIC)
							)
						),
						require(
							priceType(QueryPriceMode.WITHOUT_TAX),
							entityFetch(priceContentAll())
						)
					)
				).orElseThrow();
				final SealedEntity limitedView = session.enrichOrLimitEntity(
					firstProduct, priceContentRespectingFilter()
				);
				return limitedView.getPriceForSale().orElseThrow().priceWithoutTax();
			}
		);
		assertEquals(new BigDecimal("100.00"), priceForSale);
	}

	@Test
	@DisplayName("should order by the discount on the prefetch path as on the index path when queried without tax")
	void shouldOrderByDiscountOnPrefetchPathAsOnIndexPathWhenQueriedWithoutTax(Evita evita) {
		createProductsTiedInSinglePriceMode(evita);

		final OrderConstraint byDiscount = priceDiscount(DESC, PRICE_LIST_REFERENCE);
		assertEquals(
			queryOrder(evita, QueryPriceMode.WITHOUT_TAX, byDiscount, DebugMode.PREFER_INDEX_SCAN),
			queryOrder(evita, QueryPriceMode.WITHOUT_TAX, byDiscount, DebugMode.PREFER_PREFETCHING)
		);
	}

	@Test
	@DisplayName("should order by the price on the prefetch path as on the index path when queried without tax")
	void shouldOrderByPriceOnPrefetchPathAsOnIndexPathWhenQueriedWithoutTax(Evita evita) {
		createProductsTiedInSinglePriceMode(evita);

		final OrderConstraint byPrice = priceNatural();
		assertEquals(
			queryOrder(evita, QueryPriceMode.WITHOUT_TAX, byPrice, DebugMode.PREFER_INDEX_SCAN),
			queryOrder(evita, QueryPriceMode.WITHOUT_TAX, byPrice, DebugMode.PREFER_PREFETCHING)
		);
	}

	/**
	 * Inner record 1 of the first product has two prices in the selling price list. Their validities do not overlap
	 * and the query names no moment, so both of them stand for the inner record, and every selling price of the
	 * product is the same amount in both price modes. Tied on the amount, inner record 1 is represented by its lower
	 * price id and wins the tie against inner record 2 by that very price id, which discounts the product less than
	 * the second product.
	 */
	@Test
	@DisplayName("should order by the discount of the entity price for sale when an inner record has tied prices")
	void shouldOrderByDiscountOfEntityPriceForSaleWhenInnerRecordHasTiedPricesInOnePriceList(Evita evita) {
		createProductWithTiedPricesOfOneInnerRecord(evita);

		assertEquals(10, queryPriceForSale(evita, 1, QueryPriceMode.WITH_TAX).priceId());
		assertEquals(
			computeOrderByEntityPriceForSale(evita, QueryPriceMode.WITH_TAX),
			queryOrderByDiscount(evita, QueryPriceMode.WITH_TAX, PRICE_LIST_REFERENCE)
		);
	}

	/**
	 * The first product has a single inner record with two prices in the selling price list. Their validities do not
	 * overlap and the query names no moment, so both of them stand for the inner record. The prices tie without tax
	 * and differ with tax; the price indexed first holds the higher price id and the lower amount with tax. Tied
	 * without tax, the inner record is represented by the lower price id, so its price for sale with tax is the higher
	 * amount, and ordering by the price with tax must place the product after the second product, which sells for an
	 * amount in between.
	 */
	@Test
	@DisplayName("should order by the entity price for sale when prices of an inner record tie without tax")
	void shouldOrderByEntityPriceForSaleWhenPricesOfInnerRecordTieWithoutTax(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(PRODUCT).withPrice().updateVia(session);
				final EntityBuilder tied = session.createNewEntity(PRODUCT, 1)
					.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE);
				setTaxedPrice(tied, 30, PRICE_LIST_BASIC, 1, 100, 10, 0, firstYear());
				session.upsertEntity(tied);
				final EntityBuilder inBetween = session.createNewEntity(PRODUCT, 2)
					.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE);
				setTaxedPrice(inBetween, 40, PRICE_LIST_BASIC, 1, 100, 15, 0, null);
				session.upsertEntity(inBetween);
			}
		);
		// added by a later upsert, so the price holds a higher internal price id than the one indexed first
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EntityBuilder tied = session.getEntity(PRODUCT, 1, priceContentAll())
					.orElseThrow()
					.openForWrite();
				setTaxedPrice(tied, 10, PRICE_LIST_BASIC, 1, 100, 21, 0, secondYear());
				session.upsertEntity(tied);
			}
		);

		assertEquals(
			new BigDecimal("121.00"),
			queryPriceForSale(evita, 1, QueryPriceMode.WITH_TAX).priceWithTax()
		);
		assertEquals(List.of(2, 1), queryOrderByPrice(evita, QueryPriceMode.WITH_TAX));
	}

	/**
	 * The same tie as in {@link #shouldOrderByEntityPriceForSaleWhenPricesOfInnerRecordTieWithoutTax(Evita)} for an
	 * entity with {@link PriceInnerRecordHandling#NONE}: two prices in the selling price list valid in different years,
	 * tied without tax and differing with tax, the one indexed first holding the higher price id and the lower amount
	 * with tax. Tied without tax, the price with the lower price id is the price for sale, so its price for sale with
	 * tax is the higher amount.
	 */
	@Test
	@DisplayName("should order by the entity price for sale when prices of an entity without inner records tie")
	void shouldOrderByEntityPriceForSaleWhenPricesOfEntityWithoutInnerRecordsTieWithoutTax(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(PRODUCT).withPrice().updateVia(session);
				final EntityBuilder tied = session.createNewEntity(PRODUCT, 1)
					.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE);
				setPlainTaxedPrice(tied, 30, 10, firstYear());
				session.upsertEntity(tied);
				final EntityBuilder inBetween = session.createNewEntity(PRODUCT, 2)
					.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE);
				setPlainTaxedPrice(inBetween, 40, 15, null);
				session.upsertEntity(inBetween);
			}
		);
		// added by a later upsert, so the price holds a higher internal price id than the one indexed first
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EntityBuilder tied = session.getEntity(PRODUCT, 1, priceContentAll())
					.orElseThrow()
					.openForWrite();
				setPlainTaxedPrice(tied, 10, 21, secondYear());
				session.upsertEntity(tied);
			}
		);

		assertEquals(
			new BigDecimal("121.00"),
			queryPriceForSale(evita, 1, QueryPriceMode.WITH_TAX).priceWithTax()
		);
		assertEquals(List.of(2, 1), queryOrderByPrice(evita, QueryPriceMode.WITH_TAX));
	}

	/**
	 * Creates two products with {@link PriceInnerRecordHandling#LOWEST_PRICE} whose prices all sell for 100 in both
	 * price modes:
	 *
	 * - the first one has inner record 1 with two prices in the selling price list valid in two different years,
	 *   price 30 and price 10, and inner record 2 with price 20; the reference price of inner record 1 discounts it by
	 *   50, the one of inner record 2 by 1000;
	 * - the second one has a single inner record discounted by 500.
	 *
	 * Price 10 is added by a later upsert, so it holds a higher internal price id than price 30 whatever order the
	 * prices of a single upsert are indexed in.
	 *
	 * @param evita the evitaDB instance the products are created in
	 */
	private static void createProductWithTiedPricesOfOneInnerRecord(@Nonnull Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(PRODUCT).withPrice().updateVia(session);
				final EntityBuilder tied = session.createNewEntity(PRODUCT, 1)
					.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE);
				setIndexedPrice(tied, 30, PRICE_LIST_BASIC, 1, 100, firstYear());
				setIndexedPrice(tied, 20, PRICE_LIST_BASIC, 2, 100, null);
				setIndexedPrice(tied, 31, PRICE_LIST_REFERENCE, 1, 150, null);
				setIndexedPrice(tied, 21, PRICE_LIST_REFERENCE, 2, 1100, null);
				session.upsertEntity(tied);
				final EntityBuilder control = session.createNewEntity(PRODUCT, 2)
					.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE);
				setIndexedPrice(control, 40, PRICE_LIST_BASIC, 1, 100, null);
				setIndexedPrice(control, 41, PRICE_LIST_REFERENCE, 1, 600, null);
				session.upsertEntity(control);
			}
		);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EntityBuilder tied = session.getEntity(PRODUCT, 1, priceContentAll())
					.orElseThrow()
					.openForWrite();
				setIndexedPrice(tied, 10, PRICE_LIST_BASIC, 1, 100, secondYear());
				session.upsertEntity(tied);
			}
		);
	}

	/**
	 * Returns primary keys of all products in the order of their price for sale, lowest first, as the engine sorts
	 * them from the price records it selected in its indexes.
	 *
	 * @param evita          the evitaDB instance to query
	 * @param queryPriceMode price mode of the query
	 * @return primary keys in the order returned by the engine
	 */
	@Nonnull
	private static List<Integer> queryOrderByPrice(@Nonnull Evita evita, @Nonnull QueryPriceMode queryPriceMode) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<EntityReferenceContract> references = session.queryListOfEntityReferences(
					query(
						collection(PRODUCT),
						filterBy(
							and(
								priceInCurrency(CZK),
								priceInPriceLists(PRICE_LIST_BASIC)
							)
						),
						orderBy(priceNatural()),
						require(
							priceType(queryPriceMode),
							// a cheap prefetch would sort by the fetched entities and never reach the index route
							debug(DebugMode.PREFER_INDEX_SCAN)
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
	 * Sets an indexed price in CZK with no inner record in the basic price list, whose amount without tax is 100.
	 *
	 * @param product  builder of the product the price is set on
	 * @param priceId  id of the price
	 * @param taxRate  tax rate in percent applied to the amount
	 * @param validity validity of the price, `null` for a price valid at any moment
	 */
	private static void setPlainTaxedPrice(
		@Nonnull EntityBuilder product,
		int priceId,
		int taxRate,
		@Nullable DateTimeRange validity
	) {
		final BigDecimal base = new BigDecimal("100.00");
		final BigDecimal rate = new BigDecimal(taxRate);
		final BigDecimal withTax = base.add(base.multiply(rate).movePointLeft(2)).setScale(2);
		product.setPrice(priceId, PRICE_LIST_BASIC, CZK, base, rate, withTax, validity, true);
	}

	/**
	 * Fetches the product with its prices and returns the price for sale it reports.
	 *
	 * @param evita          the evitaDB instance to query
	 * @param primaryKey     primary key of the product
	 * @param queryPriceMode price mode of the query
	 * @return the price for sale of the product
	 */
	@Nonnull
	private static PriceContract queryPriceForSale(
		@Nonnull Evita evita,
		int primaryKey,
		@Nonnull QueryPriceMode queryPriceMode
	) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity product = session.queryOneSealedEntity(
					query(
						collection(PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(primaryKey),
								priceInCurrency(CZK),
								priceInPriceLists(PRICE_LIST_BASIC)
							)
						),
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
	 * Creates two groups of products with {@link PriceInnerRecordHandling#LOWEST_PRICE} whose two inner records tie on
	 * the selling price in one price mode and differ in the other: the first group ties with tax, the second without
	 * tax. In both groups the inner record that is cheaper in the mode the amounts differ in carries the higher price
	 * id, so neither a mode mix-up nor the tie-break can pass unnoticed. Reference prices are set per inner record so
	 * that the discount of inner record 2 is always larger than the discount of inner record 1. The product with the
	 * highest primary key has a single inner record and the largest discount, so it comes first in both price modes.
	 *
	 * @param evita the evitaDB instance the products are created in
	 */
	private static void createProductsTiedInSinglePriceMode(@Nonnull Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(PRODUCT).withPrice().updateVia(session);
				for (int pk = 1; pk <= 2 * GROUP_SIZE; pk++) {
					final EntityBuilder product = session.createNewEntity(PRODUCT, pk)
						.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE);
					if (pk <= GROUP_SIZE) {
						// tied with tax at 121, inner record 2 is cheaper without tax
						setTaxedPrice(product, pk * 10 + 1, PRICE_LIST_BASIC, 1, 110, 10, 0);
						setTaxedPrice(product, pk * 10 + 2, PRICE_LIST_BASIC, 2, 100, 21, 0);
						setTaxedPrice(product, pk * 10 + 3, PRICE_LIST_REFERENCE, 1, 110, 10, pk);
						setTaxedPrice(product, pk * 10 + 4, PRICE_LIST_REFERENCE, 2, 100, 21, 1000 + pk);
					} else {
						// tied without tax at 100, inner record 2 is cheaper with tax
						setTaxedPrice(product, pk * 10 + 1, PRICE_LIST_BASIC, 1, 100, 21, 0);
						setTaxedPrice(product, pk * 10 + 2, PRICE_LIST_BASIC, 2, 100, 10, 0);
						setTaxedPrice(product, pk * 10 + 3, PRICE_LIST_REFERENCE, 1, 100, 21, pk);
						setTaxedPrice(product, pk * 10 + 4, PRICE_LIST_REFERENCE, 2, 100, 10, 1000 + pk);
					}
					session.upsertEntity(product);
				}
				final int lastPk = 2 * GROUP_SIZE + 1;
				final EntityBuilder lastProduct = session.createNewEntity(PRODUCT, lastPk)
					.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE);
				setTaxedPrice(lastProduct, lastPk * 10 + 1, PRICE_LIST_BASIC, 1, 100, 21, 0);
				setTaxedPrice(lastProduct, lastPk * 10 + 3, PRICE_LIST_REFERENCE, 1, 100, 21, 5000);
				session.upsertEntity(lastProduct);
			}
		);
	}

	/**
	 * Fetches all products with their prices and orders their primary keys by the discount computed from the price for
	 * sale each entity reports and from the reference price of the same inner record, highest discount first. The
	 * amounts are taken in the passed price mode, as the engine takes them.
	 *
	 * @param evita          the evitaDB instance to query
	 * @param queryPriceMode price mode of the query and of the amounts the discount is computed from
	 * @return primary keys in the order the prices for sale of the fetched entities imply
	 */
	@Nonnull
	private static List<Integer> computeOrderByEntityPriceForSale(
		@Nonnull Evita evita,
		@Nonnull QueryPriceMode queryPriceMode
	) {
		final Map<Integer, BigDecimal> discounts = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<SealedEntity> products = session.queryListOfSealedEntities(
					query(
						collection(PRODUCT),
						filterBy(
							and(
								priceInCurrency(CZK),
								priceInPriceLists(PRICE_LIST_BASIC)
							)
						),
						require(
							page(1, 2 * GROUP_SIZE + 1),
							priceType(queryPriceMode),
							entityFetch(priceContentRespectingFilter(PRICE_LIST_REFERENCE))
						)
					)
				);
				final Map<Integer, BigDecimal> result = CollectionUtils.createHashMap(products.size());
				for (final SealedEntity product : products) {
					final PriceContract priceForSale = product.getPriceForSale().orElseThrow();
					PriceContract referencePrice = null;
					for (final PriceContract price : product.getPrices()) {
						if (PRICE_LIST_REFERENCE.equals(price.priceList()) &&
							Objects.equals(price.innerRecordId(), priceForSale.innerRecordId())) {
							referencePrice = price;
						}
					}
					assertNotNull(referencePrice, "Product " + product.getPrimaryKey() + " has no reference price.");
					result.put(
						product.getPrimaryKeyOrThrowException(),
						amountOf(referencePrice, queryPriceMode).subtract(amountOf(priceForSale, queryPriceMode))
					);
				}
				return result;
			}
		);
		final List<Integer> primaryKeys = new ArrayList<>(discounts.keySet());
		primaryKeys.sort(Comparator.comparing(discounts::get, Comparator.reverseOrder()));
		return primaryKeys;
	}

	/**
	 * Fetches the first product of {@link #createProductsTiedInSinglePriceMode(Evita)} by a query in the basic price
	 * list priced without tax.
	 *
	 * @param evita       the evitaDB instance to query
	 * @param entityFetch the content of the product to fetch
	 * @return the fetched product
	 */
	@Nonnull
	private static SealedEntity queryFirstProduct(@Nonnull Evita evita, @Nonnull EntityFetch entityFetch) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOneSealedEntity(
					query(
						collection(PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(1),
								priceInCurrency(CZK),
								priceInPriceLists(PRICE_LIST_BASIC)
							)
						),
						require(
							priceType(QueryPriceMode.WITHOUT_TAX),
							entityFetch
						)
					)
				).orElseThrow();
			}
		);
	}

	/**
	 * Returns primary keys of all products of {@link #createProductsTiedInSinglePriceMode(Evita)} in the order the
	 * engine sorts them by. The filter names the primary keys, so the planner may answer the query by prefetching the
	 * entities, and the passed debug mode decides which of the two paths it takes.
	 *
	 * @param evita          the evitaDB instance to query
	 * @param queryPriceMode price mode of the query
	 * @param orderBy        the ordering constraint
	 * @param debugMode      {@link DebugMode#PREFER_INDEX_SCAN} or {@link DebugMode#PREFER_PREFETCHING}
	 * @return primary keys in the order returned by the engine
	 */
	@Nonnull
	private static List<Integer> queryOrder(
		@Nonnull Evita evita,
		@Nonnull QueryPriceMode queryPriceMode,
		@Nonnull OrderConstraint orderBy,
		@Nonnull DebugMode debugMode
	) {
		final int productCount = 2 * GROUP_SIZE + 1;
		final Integer[] allPrimaryKeys = new Integer[productCount];
		for (int i = 0; i < productCount; i++) {
			allPrimaryKeys[i] = i + 1;
		}
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<EntityReferenceContract> references = session.queryListOfEntityReferences(
					query(
						collection(PRODUCT),
						filterBy(
							and(
								entityPrimaryKeyInSet(allPrimaryKeys),
								priceInCurrency(CZK),
								priceInPriceLists(PRICE_LIST_BASIC)
							)
						),
						orderBy(orderBy),
						require(
							page(1, productCount),
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
	 * Returns the amount of the price in the passed price mode.
	 *
	 * @param price          the price
	 * @param queryPriceMode price mode deciding whether the amount with or without tax is returned
	 * @return the amount
	 */
	@Nonnull
	private static BigDecimal amountOf(@Nonnull PriceContract price, @Nonnull QueryPriceMode queryPriceMode) {
		return queryPriceMode == QueryPriceMode.WITH_TAX ? price.priceWithTax() : price.priceWithoutTax();
	}

	/**
	 * Sets an indexed price in CZK whose amount without tax is the base amount raised by the passed increment and
	 * whose amount with tax is the base amount with tax raised by the very same increment, so that the increment is
	 * the discount against a selling price of the base amount in both price modes.
	 *
	 * @param product       builder of the product the price is set on
	 * @param priceId       id of the price
	 * @param priceList     price list of the price
	 * @param innerRecordId inner record the price belongs to
	 * @param baseAmount    whole amount without tax the price derives from
	 * @param taxRate       tax rate in percent applied to the base amount
	 * @param increment     whole amount added to both the amount without and with tax
	 */
	private static void setTaxedPrice(
		@Nonnull EntityBuilder product,
		int priceId,
		@Nonnull String priceList,
		int innerRecordId,
		int baseAmount,
		int taxRate,
		int increment
	) {
		setTaxedPrice(product, priceId, priceList, innerRecordId, baseAmount, taxRate, increment, null);
	}

	/**
	 * Sets an indexed price in CZK as {@link #setTaxedPrice(EntityBuilder, int, String, int, int, int, int)} does,
	 * valid within the passed validity.
	 *
	 * @param product       builder of the product the price is set on
	 * @param priceId       id of the price
	 * @param priceList     price list of the price
	 * @param innerRecordId inner record the price belongs to
	 * @param baseAmount    whole amount without tax the price derives from
	 * @param taxRate       tax rate in percent applied to the base amount
	 * @param increment     whole amount added to both the amount without and with tax
	 * @param validity      validity of the price, `null` for a price valid at any moment
	 */
	private static void setTaxedPrice(
		@Nonnull EntityBuilder product,
		int priceId,
		@Nonnull String priceList,
		int innerRecordId,
		int baseAmount,
		int taxRate,
		int increment,
		@Nullable DateTimeRange validity
	) {
		final BigDecimal base = new BigDecimal(baseAmount).setScale(2);
		final BigDecimal rate = new BigDecimal(taxRate);
		final BigDecimal withTax = base.add(base.multiply(rate).movePointLeft(2)).setScale(2);
		final BigDecimal raise = new BigDecimal(increment).setScale(2);
		product.setPrice(
			priceId, priceList, CZK, innerRecordId, base.add(raise), rate, withTax.add(raise), validity, true
		);
	}

}
