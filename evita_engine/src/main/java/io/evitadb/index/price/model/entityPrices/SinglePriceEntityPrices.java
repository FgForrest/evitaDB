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

import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * This internal data structure aggregates prices for single entity. We need to answer the question of which prices
 * (with or without tax) are assigned to which entity.
 *
 * This implementation of {@link EntityPrices} maintains the simplest form of entity prices - it holds only single price
 * no matter whether it is {@link PriceRecord#isInnerRecordSpecific()} or not.
 */
@ThreadSafe
class SinglePriceEntityPrices extends EntityPrices {
	private static final int[] NO_PRICE_IDS = ArrayUtils.EMPTY_INT_ARRAY;
	private static final PriceRecordContract[] NO_PRICES = new PriceRecordContract[0];
	public static final EntityPrices EMPTY = new SinglePriceEntityPrices(null);

	/**
	 * Contains the price.
	 * This particular data structure keeps always array of size 1 or NULL.
	 */
	@Nonnull private final PriceRecordContract[] price;
	/**
	 * Contains array of all price ids ({@link PriceRecordContract#internalPriceId()} connected with this entity.
	 * This particular data structure keeps always array of size 1 or NULL.
	 */
	@Nonnull private final int[] internalPriceId;

	SinglePriceEntityPrices(@Nullable PriceRecordContract priceRecord) {
		this.price = priceRecord == null ? NO_PRICES : new PriceRecordContract[] {priceRecord};
		this.internalPriceId = priceRecord == null ? NO_PRICE_IDS : new int[] {priceRecord.internalPriceId()};
	}

	@Override
	public PriceRecordContract[] getLowestPriceRecords() {
		return this.price;
	}

	@Override
	public int getSize() {
		return this.price.length;
	}

	@Override
	public boolean containsPriceRecord(int priceId) {
		return this.price.length > 0 && this.price[0].priceId() == priceId;
	}

	@Override
	public boolean containsInnerRecord(int innerRecordId) {
		return false;
	}

	@Nonnull
	@Override
	public int[] getInternalPriceIds() {
		return this.internalPriceId;
	}

	@Nonnull
	@Override
	protected PriceRecordContract[] getAllPrices() {
		return this.price;
	}

	@Override
	protected boolean isInnerRecordSpecific() {
		return this.price.length > 0 && this.price[0].isInnerRecordSpecific();
	}


	@Nonnull
	@Override
	protected PriceRecordContract[] computePricesAdding(@Nonnull PriceRecordContract priceRecord) {
		if (this.price.length == 0) {
			return new PriceRecordContract[] {priceRecord};
		} else {
			if (PRICE_ID_COMPARATOR.compare(priceRecord, this.price[0]) >= 0) {
				return new PriceRecordContract[]{this.price[0], priceRecord};
			} else {
				return new PriceRecordContract[]{priceRecord, this.price[0]};
			}
		}
	}

	@Nonnull
	@Override
	protected PriceRecordContract[] computePricesRemoving(@Nonnull PriceRecordContract priceRecord) {
		Assert.isTrue(
			this.price.length > 0 && priceRecord.internalPriceId() == this.price[0].internalPriceId(),
			"Price with id `" + priceRecord.priceId() + "` (internal id `" + priceRecord.internalPriceId() + "`) was not found!"
		);
		return EMPTY_PRICES;
	}

}
