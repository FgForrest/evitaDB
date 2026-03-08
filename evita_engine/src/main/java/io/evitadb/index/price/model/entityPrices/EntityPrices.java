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
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;

/**
 * This internal data structure aggregates prices for single entity. We need to answer the question of which prices
 * (with or without tax) are assigned to which entity. This abstract class is the entry point to multiple
 * implementations:
 *
 * - {@link SinglePriceEntityPrices}: maintains only single price (with or without inner record id)
 * - {@link MultiplePriceEntityPrices}: maintains multiple prices that are not linked to inner record id
 * - {@link FullBlownEntityPrices}: maintains multiple prices (with or without inner record id)
 *
 * These implementations are divided into three variants to optimize memory - i.e. to keep data structures
 * with a minimal set of fields to reduce heap memory consumption.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public abstract class EntityPrices {
	protected static final PriceRecord[] EMPTY_PRICES = new PriceRecord[0];
	/**
	 * Comparator that orders {@link PriceRecordContract} by their
	 * {@link PriceRecordContract#internalPriceId()} in ascending order.
	 */
	protected static final Comparator<PriceRecordContract> PRICE_ID_COMPARATOR =
		Comparator.comparingInt(PriceRecordContract::internalPriceId);
	/**
	 * Comparator that orders {@link PriceRecordContract} by
	 * {@link PriceRecordContract#priceWithoutTax()} first, then by
	 * {@link PriceRecordContract#internalPriceId()} to break ties.
	 */
	protected static final Comparator<PriceRecordContract> WITHOUT_TAX_COMPARATOR =
		Comparator.comparingInt(PriceRecordContract::priceWithoutTax)
			.thenComparing(PriceRecordContract::internalPriceId);

	/**
	 * Creates duplicate of the `original` DTO adding new `priceRecord` to it in the process.
	 */
	@Nonnull
	public static EntityPrices addPriceRecord(@Nonnull EntityPrices original, @Nonnull PriceRecordContract priceRecord) {
		if (original.isEmpty()) {
			return new SinglePriceEntityPrices(priceRecord);
		} else if (priceRecord.isInnerRecordSpecific() || original.isInnerRecordSpecific()) {
			return new FullBlownEntityPrices(
				original.computePricesAdding(priceRecord)
			);
		} else {
			return new MultiplePriceEntityPrices(
				original.computePricesAdding(priceRecord)
			);
		}
	}

	/**
	 * Creates new EntityPrices object for single {@link PriceRecord}.
	 */
	@Nonnull
	public static EntityPrices create(@Nonnull PriceRecordContract priceRecord) {
		return new SinglePriceEntityPrices(priceRecord);
	}

	/**
	 * Creates duplicate of the `original` DTO removing existing `priceRecord` from it in the process.
	 * Method doesn't check the existence of the price in the DTO - it should be checked elsewhere.
	 */
	@Nonnull
	public static EntityPrices removePrice(@Nonnull EntityPrices original, @Nonnull PriceRecordContract priceRecord) {
		if (original.getSize() < 2) {
			return SinglePriceEntityPrices.EMPTY;
		} else if (original.getSize() < 3) {
			final PriceRecordContract[] priceRecords = original.computePricesRemoving(priceRecord);
			Assert.isPremiseValid(priceRecords.length == 1, "Expected single result!");
			return new SinglePriceEntityPrices(priceRecords[0]);
		} else if (priceRecord.isInnerRecordSpecific() || original.isInnerRecordSpecific()) {
			return new FullBlownEntityPrices(original.computePricesRemoving(priceRecord));
		} else {
			return new MultiplePriceEntityPrices(original.computePricesRemoving(priceRecord));
		}
	}

	/**
	 * Returns the array of the lowest prices for each inner record id group.
	 */
	@Nullable
	public abstract PriceRecordContract[] getLowestPriceRecords();

	/**
	 * Returns true if there is no single price for the entity.
	 */
	public boolean isEmpty() {
		return getSize() == 0;
	}

	/**
	 * Returns the number of prices for the entity.
	 */
	public abstract int getSize();

	/**
	 * Returns true if there is a single price that matches passed original price id.
	 */
	public abstract boolean containsPriceRecord(int priceId);

	/**
	 * Returns true if there is single price that matches passed inner record id.
	 */
	public abstract boolean containsInnerRecord(int innerRecordId);

	/**
	 * Returns true if this entity contains at least single price that can also be found in passed array of price
	 * triples.
	 */
	public boolean containsAnyOf(@Nonnull PriceRecordContract[] priceTriples) {
		final int[] internalPriceIds = getInternalPriceIds();
		int priceRecordIndex = -1;
		int lastPriceId = 0;
		for (final PriceRecordContract priceRecordContract : priceTriples) {
			final int lookedUpPriceId = priceRecordContract.internalPriceId();
			final int fromIndex = priceRecordIndex + 1;
			final int toIndex = Math.min(fromIndex + lookedUpPriceId - lastPriceId, internalPriceIds.length);
			priceRecordIndex = Arrays.binarySearch(internalPriceIds, fromIndex, toIndex, lookedUpPriceId);
			if (priceRecordIndex >= 0) {
				return true;
			} else {
				priceRecordIndex = -1 * (priceRecordIndex) - 2;
			}
			lastPriceId = lookedUpPriceId;
		}
		return false;
	}

	/**
	 * Returns all prices of the entity.
	 */
	@Nonnull
	protected abstract PriceRecordContract[] getAllPrices();

	/**
	 * Returns array of all {@link PriceRecord#internalPriceId()}.
	 */
	@Nonnull
	public abstract int[] getInternalPriceIds();

	/**
	 * Returns true if any prices in this EntityPrices returns true for {@link PriceRecord#isInnerRecordSpecific()}.
	 */
	protected abstract boolean isInnerRecordSpecific();

	/**
	 * Creates new array of {@link PriceRecord} combining internal prices with another price passed in argument.
	 */
	@Nonnull
	protected abstract PriceRecordContract[] computePricesAdding(@Nonnull PriceRecordContract priceRecord);

	/**
	 * Creates new array of {@link PriceRecord} removing price passed in argument from internal prices.
	 */
	@Nonnull
	protected abstract PriceRecordContract[] computePricesRemoving(@Nonnull PriceRecordContract priceRecord);

}
