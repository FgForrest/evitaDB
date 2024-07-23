/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.index.price.model.entityPrices;

import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Arrays;

/**
 * This internal data structure aggregates prices for single entity. We need to answer the question of which prices
 * (with or without tax) are assigned to which entity.
 *
 * This implementation of {@link EntityPrices} maintains the maintains more than single price but none of them
 * is {@link PriceRecordContract#isInnerRecordSpecific()}.
 */
@ThreadSafe
class MultiplePriceEntityPrices extends EntityPrices {
	/**
	 * Contains all entity prices.
	 */
	@Nonnull private final PriceRecordContract[] prices;
	/**
	 * Contains the lowest price of the entity.
	 * This particular data structure keeps always array of size 1.
	 */
	@Nonnull private final PriceRecordContract[] lowestPrice;
	/**
	 * Contains array of all price ids ({@link PriceRecordContract#internalPriceId()} connected with this entity.
	 */
	@Nonnull private final int[] internalPriceIds;

	MultiplePriceEntityPrices(@Nonnull PriceRecordContract[] prices) {
		this.prices = prices;
		this.internalPriceIds = new int[prices.length];

		PriceRecordContract theLowestPrice = null;
		for (int i = 0; i < prices.length; i++) {
			this.internalPriceIds[i] = prices[i].internalPriceId();
			if (theLowestPrice == null || WITHOUT_TAX_COMPARATOR.compare(prices[i], theLowestPrice) < 0) {
				theLowestPrice = prices[i];
			}
		}

		this.lowestPrice = new PriceRecordContract[] {theLowestPrice};
	}

	@Override
	public PriceRecordContract[] getLowestPriceRecords() {
		return lowestPrice;
	}

	@Override
	public int getSize() {
		return prices.length;
	}

	@Override
	public boolean containsPriceRecord(int priceId) {
		return Arrays.stream(prices).anyMatch(it -> it.priceId() == priceId);
	}

	@Override
	public boolean containsInnerRecord(int innerRecordId) {
		return false;
	}

	@Nonnull
	@Override
	protected PriceRecordContract[] getAllPrices() {
		return prices;
	}

	@Nonnull
	@Override
	public int[] getInternalPriceIds() {
		return internalPriceIds;
	}

	@Override
	protected boolean isInnerRecordSpecific() {
		return false;
	}

	@Nonnull
	@Override
	protected PriceRecordContract[] computePricesAdding(@Nonnull PriceRecordContract PriceRecordContract) {
		return ArrayUtils.insertRecordIntoOrderedArray(PriceRecordContract, prices, PRICE_ID_COMPARATOR);
	}

	@Nonnull
	@Override
	protected PriceRecordContract[] computePricesRemoving(@Nonnull PriceRecordContract PriceRecordContract) {
		return ArrayUtils.removeRecordFromOrderedArray(PriceRecordContract, prices, PRICE_ID_COMPARATOR);
	}
}
