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

import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
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
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.DESC;
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
	private static final String PRODUCT = "product";
	private static final String PRICE_LIST_BASIC = "basic";
	private static final String PRICE_LIST_REFERENCE = "reference";
	private static final String PRICE_LIST_SECONDARY_REFERENCE = "secondaryReference";
	private static final Currency CZK = Currency.getInstance("CZK");
	/**
	 * Number of identical queries whose orders are compared.
	 */
	private static final int QUERY_REPETITIONS = 20;
	/**
	 * Upper bound of the number of products a test creates, used as the page size.
	 */
	private static final int PRODUCT_COUNT = 20;

	/**
	 * The SUM termination examines the reference prices of one entity in the iteration order of a hash map, seeded per
	 * instance. The discount of every product - the last one included - must not depend on it.
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
			orderCounts.merge(queryOrderByDiscount(evita, PRICE_LIST_REFERENCE), 1, Integer::sum);
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
			queryOrderByDiscount(evita, PRICE_LIST_REFERENCE, PRICE_LIST_SECONDARY_REFERENCE)
		);
	}

	/**
	 * Returns primary keys of the products ordered by their discount, highest first, fetching references only.
	 *
	 * @param evita                   the evitaDB instance to query
	 * @param referencePriceListNames price lists of the reference price in the order of their priority
	 * @return primary keys in the order returned by the engine
	 */
	@Nonnull
	private static List<Integer> queryOrderByDiscount(
		@Nonnull Evita evita,
		@Nonnull String... referencePriceListNames
	) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final List<EntityReferenceContract> references = session.queryListOfEntityReferences(
					query(
						collection(PRODUCT),
						filterBy(and(priceInCurrency(CZK), priceInPriceLists(PRICE_LIST_BASIC))),
						orderBy(priceDiscount(DESC, referencePriceListNames)),
						// a cheap prefetch would sort by the fetched entities and never reach the index route
						require(page(1, PRODUCT_COUNT), debug(DebugMode.PREFER_INDEX_SCAN))
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
	 * Sets an indexed price in CZK with no tax.
	 *
	 * @param product       builder of the product the price is set on
	 * @param priceId       id of the price
	 * @param priceList     price list of the price
	 * @param innerRecordId inner record the price belongs to
	 * @param amount        whole amount of the price
	 */
	private static void setIndexedPrice(
		@Nonnull EntityBuilder product,
		int priceId,
		@Nonnull String priceList,
		int innerRecordId,
		int amount
	) {
		final BigDecimal price = new BigDecimal(amount).setScale(2);
		product.setPrice(priceId, priceList, CZK, innerRecordId, price, BigDecimal.ZERO, price, true);
	}
}
