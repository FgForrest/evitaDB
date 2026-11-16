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

import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.core.Evita;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static io.evitadb.api.functional.price.PriceDiscountOrderingTestSupport.CZK;
import static io.evitadb.api.functional.price.PriceDiscountOrderingTestSupport.PRICE_LIST_BASIC;
import static io.evitadb.api.functional.price.PriceDiscountOrderingTestSupport.PRICE_LIST_REFERENCE;
import static io.evitadb.api.functional.price.PriceDiscountOrderingTestSupport.PRODUCT;
import static io.evitadb.api.functional.price.PriceDiscountOrderingTestSupport.queryOrderByDiscount;
import static io.evitadb.api.functional.price.PriceDiscountOrderingTestSupport.setIndexedPrice;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.PRICE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that ordering by discount pairs the selling price of every entity - the entity with the highest primary
 * key included - with every reference price of the same inner records, whatever order the reference prices of one
 * entity are examined in.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Ordering by discount against the reference prices of the selling inner records")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(PRICE)
@Tag(ORDER)
class PriceDiscountReferenceMatchingFunctionalTest {
	private static final String PRICE_LIST_SECONDARY_REFERENCE = "secondaryReference";
	/**
	 * Number of identical queries whose orders are compared. A single query fails already when the last selling price
	 * record is not compared with every reference price of its entity; the repetitions are there for an order that
	 * depends on the iteration order of the hash map the SUM termination examines the reference prices of an entity
	 * in, which is seeded per instance and so differs between the queries.
	 */
	private static final int QUERY_REPETITIONS = 10;
	/**
	 * Number of products of the SUM fixture.
	 */
	private static final int PRODUCT_COUNT = 20;

	/**
	 * The SUM termination examines the reference prices of one entity in the iteration order of a hash map, seeded per
	 * instance. The discount of every product - the last one included - must be computed from all its reference
	 * prices, and the order of the products must not depend on that iteration order.
	 */
	@Test
	@DisplayName("should return the same order by discount of SUM entities for repeated identical queries")
	void shouldReturnSameOrderByDiscountOfSumEntitiesForRepeatedQueries(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(PRODUCT).withPrice().updateVia(session);
				for (int pk = 1; pk <= PRODUCT_COUNT; pk++) {
					final EntityBuilder product = session.createNewEntity(PRODUCT, pk)
						.setPriceInnerRecordHandling(PriceInnerRecordHandling.SUM);
					setIndexedPrice(product, pk * 10 + 1, PRICE_LIST_BASIC, 1, 100);
					setIndexedPrice(product, pk * 10 + 2, PRICE_LIST_BASIC, 2, 100);
					setIndexedPrice(product, pk * 10 + 3, PRICE_LIST_BASIC, 3, 100);
					// inner record 3 has no reference price, its selling price is added as the missing component
					setIndexedPrice(product, pk * 10 + 4, PRICE_LIST_REFERENCE, 1, 100 + pk);
					setIndexedPrice(product, pk * 10 + 5, PRICE_LIST_REFERENCE, 2, 100 + (pk % 7) * 3);
					session.upsertEntity(product);
				}
			}
		);
		final Map<List<Integer>, Integer> orderCounts = CollectionUtils.createLinkedHashMap(QUERY_REPETITIONS);
		for (int i = 0; i < QUERY_REPETITIONS; i++) {
			orderCounts.merge(
				queryOrderByDiscount(evita, QueryPriceMode.WITH_TAX, PRICE_LIST_REFERENCE), 1, Integer::sum
			);
		}
		// the discount of a product is `pk + 3 * (pk % 7)`
		assertEquals(
			Map.of(List.of(20, 19, 13, 18, 12, 17, 6, 11, 16, 5, 10, 15, 4, 9, 14, 3, 8, 2, 7, 1), QUERY_REPETITIONS),
			orderCounts,
			"orders (order -> times): " + orderCounts
		);
	}

	/**
	 * The selling price of every product is its second inner record. Its reference price is only in the less
	 * prioritized reference price list, so it is examined after the reference price of the first inner record, which
	 * is in the more prioritized one - an order fixed by the price list priority rather than by the internal price ids
	 * the indexes assign. The product with the highest primary key is the last selling price record, and its discount
	 * is the largest.
	 */
	@Test
	@DisplayName("should order the entity with the highest primary key by its discount")
	void shouldOrderEntityWithHighestPrimaryKeyByItsDiscount(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(PRODUCT).withPrice().updateVia(session);
				for (int pk = 1; pk <= 3; pk++) {
					final EntityBuilder product = session.createNewEntity(PRODUCT, pk)
						.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE);
					// inner record 2 is the cheaper one and so the selling price, the discount grows with the pk
					setIndexedPrice(product, pk * 10 + 1, PRICE_LIST_BASIC, 1, 200);
					setIndexedPrice(product, pk * 10 + 2, PRICE_LIST_BASIC, 2, 100);
					setIndexedPrice(product, pk * 10 + 3, PRICE_LIST_REFERENCE, 1, 201);
					setIndexedPrice(product, pk * 10 + 4, PRICE_LIST_SECONDARY_REFERENCE, 2, 100 + 10 * pk);
					session.upsertEntity(product);
				}
			}
		);
		assertEquals(
			List.of(3, 2, 1),
			queryOrderByDiscount(evita, QueryPriceMode.WITH_TAX, PRICE_LIST_REFERENCE, PRICE_LIST_SECONDARY_REFERENCE)
		);
	}

	/**
	 * Every product sells through inner record 1. The reference price of inner record 2 is in the more prioritized
	 * reference price list, so it is examined before the reference price of inner record 1 - also for the product just
	 * before the last selling price record. Pairing that product with any other record than its own selling record
	 * would either drop its discount or replace it with the far larger discount of inner record 2.
	 */
	@Test
	@DisplayName("should pair the reference price of the selling inner record examined after another inner record")
	void shouldPairReferencePriceOfSellingInnerRecordExaminedAfterAnotherInnerRecord(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(PRODUCT).withPrice().updateVia(session);
				for (int pk = 1; pk <= 3; pk++) {
					final EntityBuilder product = session.createNewEntity(PRODUCT, pk)
						.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE);
					// inner record 1 is the cheaper one and so the selling price, its discount grows with the pk
					setIndexedPrice(product, pk * 10 + 1, PRICE_LIST_BASIC, 1, 100);
					setIndexedPrice(product, pk * 10 + 2, PRICE_LIST_BASIC, 2, 200);
					setIndexedPrice(product, pk * 10 + 3, PRICE_LIST_REFERENCE, 2, 1100);
					setIndexedPrice(product, pk * 10 + 4, PRICE_LIST_SECONDARY_REFERENCE, 1, 100 + 10 * pk);
					session.upsertEntity(product);
				}
			}
		);
		assertEquals(
			List.of(3, 2, 1),
			queryOrderByDiscount(evita, QueryPriceMode.WITH_TAX, PRICE_LIST_REFERENCE, PRICE_LIST_SECONDARY_REFERENCE)
		);
	}

	/**
	 * Prices of a {@link PriceInnerRecordHandling#LOWEST_PRICE} entity may omit the inner record id, and the selling
	 * price record of such an entity represents no inner record. Its reference price represents none either, so the
	 * two belong together.
	 */
	@Test
	@DisplayName("should order entities whose prices have no inner record by their discount")
	void shouldOrderLowestPriceEntitiesWithoutInnerRecordByDiscount(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(PRODUCT).withPrice().updateVia(session);
				final EntityBuilder smallDiscount = session.createNewEntity(PRODUCT, 1)
					.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE);
				setIndexedPriceWithoutInnerRecord(smallDiscount, 11, PRICE_LIST_BASIC, 80);
				setIndexedPriceWithoutInnerRecord(smallDiscount, 12, PRICE_LIST_REFERENCE, 90);
				session.upsertEntity(smallDiscount);
				final EntityBuilder largeDiscount = session.createNewEntity(PRODUCT, 2)
					.setPriceInnerRecordHandling(PriceInnerRecordHandling.LOWEST_PRICE);
				setIndexedPriceWithoutInnerRecord(largeDiscount, 21, PRICE_LIST_BASIC, 100);
				setIndexedPriceWithoutInnerRecord(largeDiscount, 22, PRICE_LIST_REFERENCE, 150);
				session.upsertEntity(largeDiscount);
			}
		);
		assertEquals(List.of(2, 1), queryOrderByDiscount(evita, QueryPriceMode.WITH_TAX, PRICE_LIST_REFERENCE));
	}

	/**
	 * Sets an indexed price in CZK with no tax and no inner record.
	 *
	 * @param product   builder of the product the price is set on
	 * @param priceId   id of the price
	 * @param priceList price list of the price
	 * @param amount    whole amount of the price
	 */
	private static void setIndexedPriceWithoutInnerRecord(
		@Nonnull EntityBuilder product,
		int priceId,
		@Nonnull String priceList,
		int amount
	) {
		final BigDecimal price = new BigDecimal(amount).setScale(2);
		product.setPrice(priceId, priceList, CZK, price, BigDecimal.ZERO, price, true);
	}
}
