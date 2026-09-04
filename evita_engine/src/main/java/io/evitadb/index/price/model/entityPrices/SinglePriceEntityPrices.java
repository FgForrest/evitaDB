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
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.function.Consumer;

/**
 * This internal data structure aggregates prices for single entity. We need to answer the question of which prices
 * (with or without tax) are assigned to which entity.
 *
 * This implementation of {@link EntityPrices} maintains the simplest form of entity prices - it holds only single price
 * no matter whether it is {@link PriceRecord#isInnerRecordSpecific()} or not.
 *
 * # Why the price is a scalar and not a one-element array
 *
 * This is by far the most numerous {@link EntityPrices} shape: an entity normally carries exactly one price per
 * price-list and currency combination, so a production catalog holds millions of these holders and only a handful of
 * the richer ones. A one-element `PriceRecordContract[]` plus a one-element `int[]` cost two array headers on top of
 * this object - three heap objects where one suffices, and the two arrays together outweigh the holder itself.
 *
 * The price and its {@link PriceRecordContract#internalPriceId()} are therefore stored as plain fields, and the two
 * array-returning accessors ({@link #getLowestPriceRecords()}, {@link #getInternalPriceIds()}) build their
 * one-element answer on demand. Callers on the query and mutation paths use the non-allocating accessors
 * ({@link #getLowestPriceRecordCount()}, {@link #forEachLowestPriceRecord(Consumer)},
 * {@link #getInternalPriceId(int)}) instead, so the built arrays are reached only by cold callers - assertions,
 * diagnostics and tests.
 */
@ThreadSafe
class SinglePriceEntityPrices extends EntityPrices {
	private static final int[] NO_PRICE_IDS = ArrayUtils.EMPTY_INT_ARRAY;
	private static final PriceRecordContract[] NO_PRICES = new PriceRecordContract[0];
	public static final EntityPrices EMPTY = new SinglePriceEntityPrices(null);

	/**
	 * Contains the single price of the entity, or `null` when this holder is the empty one.
	 */
	@Nullable private final PriceRecordContract price;
	/**
	 * Contains {@link PriceRecordContract#internalPriceId()} of {@link #price}, or `0` when there is no price.
	 * Held next to the price rather than read through it so that the id-keyed lookups this holder answers most often
	 * touch a single cache line and never dereference the shared price record.
	 */
	private final int internalPriceId;

	SinglePriceEntityPrices(@Nullable PriceRecordContract priceRecord) {
		this.price = priceRecord;
		this.internalPriceId = priceRecord == null ? 0 : priceRecord.internalPriceId();
	}

	@Nullable
	@Override
	public PriceRecordContract[] getLowestPriceRecords() {
		return this.price == null ? NO_PRICES : new PriceRecordContract[] {this.price};
	}

	@Override
	public int getLowestPriceRecordCount() {
		return this.price == null ? 0 : 1;
	}

	@Override
	public void forEachLowestPriceRecord(@Nonnull Consumer<PriceRecordContract> priceConsumer) {
		if (this.price != null) {
			priceConsumer.accept(this.price);
		}
	}

	@Override
	public int getSize() {
		return this.price == null ? 0 : 1;
	}

	@Override
	public boolean containsPriceRecord(int priceId) {
		return this.price != null && this.price.priceId() == priceId;
	}

	@Override
	public boolean containsInnerRecord(int innerRecordId) {
		return this.price != null
			&& this.price.isInnerRecordSpecific()
			&& this.price.innerRecordId() == innerRecordId;
	}

	/**
	 * Scans the passed triples for this holder's single internal price id directly. The inherited implementation
	 * binary-searches an `int[]` of this holder's ids, which for a single price would mean allocating a one-element
	 * array to search it - so the scalar comparison replaces it outright.
	 *
	 * @param priceTriples price records to test against this entity's price, ordered by internal price id
	 * @return true when one of the triples is this entity's price
	 */
	@Override
	public boolean containsAnyOf(@Nonnull PriceRecordContract[] priceTriples) {
		if (this.price == null) {
			return false;
		}
		for (final PriceRecordContract priceTriple : priceTriples) {
			if (priceTriple.internalPriceId() == this.internalPriceId) {
				return true;
			}
		}
		return false;
	}

	@Nonnull
	@Override
	public int[] getInternalPriceIds() {
		return this.price == null ? NO_PRICE_IDS : new int[] {this.internalPriceId};
	}

	@Override
	public int getInternalPriceId(int index) {
		if (index != 0 || this.price == null) {
			throw new IndexOutOfBoundsException(
				"Index " + index + " is out of bounds for entity prices holding " + getSize() + " price(s)!"
			);
		}
		return this.internalPriceId;
	}

	@Nonnull
	@Override
	protected PriceRecordContract[] getAllPrices() {
		return this.price == null ? NO_PRICES : new PriceRecordContract[] {this.price};
	}

	@Override
	protected boolean isInnerRecordSpecific() {
		return this.price != null && this.price.isInnerRecordSpecific();
	}

	@Nonnull
	@Override
	protected PriceRecordContract[] computePricesAdding(@Nonnull PriceRecordContract priceRecord) {
		if (this.price == null) {
			return new PriceRecordContract[] {priceRecord};
		} else if (PRICE_ID_COMPARATOR.compare(priceRecord, this.price) >= 0) {
			return new PriceRecordContract[] {this.price, priceRecord};
		} else {
			return new PriceRecordContract[] {priceRecord, this.price};
		}
	}

	@Nonnull
	@Override
	protected PriceRecordContract[] computePricesRemoving(@Nonnull PriceRecordContract priceRecord) {
		Assert.isTrue(
			this.price != null && priceRecord.internalPriceId() == this.internalPriceId,
			"Price with id `" + priceRecord.priceId() + "` (internal id `" + priceRecord.internalPriceId() + "`) was not found!"
		);
		return EMPTY_PRICES;
	}

	/**
	 * Returns the heap this wrapper occupies, in bytes - its own object alone, never the price record it points at,
	 * which the owning super index's price-record tree charges.
	 *
	 * Unlike the multi-price shapes this holder owns no array at all: the single price is a field, its internal price
	 * id is a field, and the one-element arrays the array-returning accessors hand out belong to their callers.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	@Override
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// the price reference and the internalPriceId int
		return layout.sizeOfObject((long) layout.referenceSize() + Integer.BYTES);
	}

}
