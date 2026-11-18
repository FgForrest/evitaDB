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
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.core.Evita;
import io.evitadb.dataType.DateTimeRange;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static io.evitadb.test.TestConstants.TEST_CATALOG;

/**
 * Fixture shared by the functional tests of ordering by price discount on the index route: the product collection,
 * the selling and reference price lists, the currency, indexed prices without tax and the query returning primary keys
 * ordered by discount from entity references alone, so that the order comes from the price records the engine selected
 * in its indexes and never from fetched entity bodies.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class PriceDiscountOrderingTestSupport {
	static final String PRODUCT = "product";
	static final String PRICE_LIST_BASIC = "basic";
	static final String PRICE_LIST_REFERENCE = "reference";
	static final Currency CZK = Currency.getInstance("CZK");
	/**
	 * Page size covering every product a test of the discount ordering creates.
	 */
	private static final int PAGE_SIZE = 50;

	private PriceDiscountOrderingTestSupport() {
		throw new UnsupportedOperationException("Test support class, not instantiable");
	}

	/**
	 * Returns primary keys of the products selling in {@link #PRICE_LIST_BASIC} ordered by their discount against the
	 * reference price lists, highest discount first, fetching entity references only.
	 *
	 * @param evita                   the evitaDB instance to query
	 * @param queryPriceMode          price mode of the query
	 * @param referencePriceListNames price lists of the reference price in the order of their priority
	 * @return primary keys in the order returned by the engine
	 */
	@Nonnull
	static List<Integer> queryOrderByDiscount(
		@Nonnull Evita evita,
		@Nonnull QueryPriceMode queryPriceMode,
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
						require(
							page(1, PAGE_SIZE),
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
	 * Sets an indexed price in CZK with no tax, so the price with tax equals the price without tax.
	 *
	 * @param product       builder of the product the price is set on
	 * @param priceId       id of the price
	 * @param priceList     price list of the price
	 * @param innerRecordId inner record the price belongs to
	 * @param amount        whole amount of the price
	 */
	static void setIndexedPrice(
		@Nonnull EntityBuilder product,
		int priceId,
		@Nonnull String priceList,
		int innerRecordId,
		int amount
	) {
		setIndexedPrice(product, priceId, priceList, innerRecordId, amount, null);
	}

	/**
	 * Sets an indexed price in CZK with no tax, valid within the passed validity.
	 *
	 * @param product       builder of the product the price is set on
	 * @param priceId       id of the price
	 * @param priceList     price list of the price
	 * @param innerRecordId inner record the price belongs to
	 * @param amount        whole amount of the price
	 * @param validity      validity of the price, `null` for a price valid at any moment
	 */
	static void setIndexedPrice(
		@Nonnull EntityBuilder product,
		int priceId,
		@Nonnull String priceList,
		int innerRecordId,
		int amount,
		@Nullable DateTimeRange validity
	) {
		final BigDecimal price = new BigDecimal(amount).setScale(2);
		product.setPrice(priceId, priceList, CZK, innerRecordId, price, BigDecimal.ZERO, price, validity, true);
	}
}
