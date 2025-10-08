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

package io.evitadb.index.price.model.entityPrices;

import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import com.carrotsearch.hppc.cursors.ObjectCursor;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Arrays;

/**
 * This internal data structure aggregates prices for single entity. We need to answer the question of which prices
 * (with or without tax) are assigned to which entity.
 *
 * This implementation of {@link EntityPrices} maintains the richest set of prices possibles. It means that it maintains
 * more than single price and that at least one prices is {@link PriceRecordContract#isInnerRecordSpecific()}.
 */
@ThreadSafe
class FullBlownEntityPrices extends EntityPrices {
	/**
	 * Contains prices sorted by price id.
	 */
	@Nonnull private final PriceRecordContract[] prices;
	/**
	 * Contains the lowest price per inner record id sorted by price id in ascending fashion.
	 */
	@Nonnull private final PriceRecordContract[] lowestPrices;
	/**
	 * Contains array of all price ids ({@link PriceRecordContract#internalPriceId()} connected with this entity.
	 */
	@Nonnull private final int[] internalPriceIds;

	FullBlownEntityPrices(@Nonnull PriceRecordContract[] prices) {
		this.prices = prices;
		this.internalPriceIds = new int[prices.length];

		final IntObjectMap<PriceRecordContract> theLowestPrices = new IntObjectHashMap<>(prices.length);
		for (int i = 0; i < prices.length; i++) {
			final PriceRecordContract price = prices[i];
			this.internalPriceIds[i] = price.internalPriceId();
			final int innerRecordId = price.innerRecordId();
			final PriceRecordContract lowestPrice = theLowestPrices.get(innerRecordId);
			if (lowestPrice == null || WITHOUT_TAX_COMPARATOR.compare(price, lowestPrice) < 0) {
				theLowestPrices.put(innerRecordId, price);
			}
		}

		this.lowestPrices = new PriceRecordContract[theLowestPrices.size()];
		int i = 0;
		for (ObjectCursor<PriceRecordContract> lowestPrice : theLowestPrices.values()) {
			this.lowestPrices[i++] = lowestPrice.value;
		}
		Arrays.sort(this.lowestPrices, PRICE_ID_COMPARATOR);
	}

	@Override
	public PriceRecordContract[] getLowestPriceRecords() {
		return this.lowestPrices;
	}

	@Override
	public int getSize() {
		return this.prices.length;
	}

	@Override
	public boolean containsPriceRecord(int priceId) {
		return Arrays.stream(this.prices).anyMatch(it -> it.priceId() == priceId);
	}

	@Override
	public boolean containsInnerRecord(int innerRecordId) {
		return Arrays.stream(this.prices).anyMatch(it -> it.innerRecordId() == innerRecordId);
	}

	@Nonnull
	@Override
	public int[] getInternalPriceIds() {
		return this.internalPriceIds;
	}

	@Nonnull
	@Override
	protected PriceRecordContract[] getAllPrices() {
		return this.prices;
	}

	@Override
	protected boolean isInnerRecordSpecific() {
		return true;
	}

	@Nonnull
	@Override
	protected PriceRecordContract[] computePricesAdding(@Nonnull PriceRecordContract priceRecord) {
		return ArrayUtils.insertRecordIntoOrderedArray(priceRecord, this.prices, PRICE_ID_COMPARATOR);
	}

	@Nonnull
	@Override
	protected PriceRecordContract[] computePricesRemoving(@Nonnull PriceRecordContract priceRecord) {
		return ArrayUtils.removeRecordFromOrderedArray(priceRecord, this.prices, PRICE_ID_COMPARATOR);
	}

}
