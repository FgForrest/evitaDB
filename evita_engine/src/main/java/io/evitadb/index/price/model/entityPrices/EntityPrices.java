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
import java.util.function.Consumer;

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
 * # Nothing here is ever persisted
 *
 * An entity-prices holder is a derived, in-memory-only view. The persisted form of a
 * {@link io.evitadb.index.price.PriceListAndCurrencyPriceSuperIndex} is its array of
 * {@link PriceRecordContract} (see its `createStoragePart`), and the entity-keyed map of holders is rebuilt from
 * those records on load. Adding, removing or reshaping a field of any implementation therefore has no storage
 * format consequence and needs no serializer or backward-compatibility work.
 *
 * # Array-returning accessors allocate, indexed accessors do not
 *
 * {@link #getLowestPriceRecords()}, {@link #getInternalPriceIds()} and {@link #getAllPrices()} are free for the
 * multi-price shapes, which store the arrays anyway, but the single-price shape - by far the most numerous one -
 * has to build them. Callers that only iterate or probe must therefore use {@link #getLowestPriceRecordCount()},
 * {@link #forEachLowestPriceRecord(Consumer)}, {@link #getSize()} and {@link #getInternalPriceIdAt(int)}, which are
 * allocation-free in every implementation. The array forms are for cold callers - assertions, diagnostics, tests.
 *
 * All three array forms are read-only to their caller. The multi-price shapes return the very array they hold, so
 * writing into one rewrites the holder's own state - and the entity would then report ids it does not own to
 * {@link io.evitadb.index.price.PriceListAndCurrencyPriceRefIndex}, which walks them to decide whether the entity
 * still belongs in its index. Whether a particular call hands back a live array or a freshly built one is not part
 * of the contract and differs between the shapes; a caller that needs to keep or modify the result copies it.
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
	 *
	 * The single-price implementation builds this array on demand, so a caller that merely iterates the result must
	 * use {@link #forEachLowestPriceRecord(Consumer)} instead.
	 *
	 * The result is read-only: the multi-price implementations hand out the array they hold, so writing into it
	 * rewrites this holder.
	 */
	@Nullable
	public abstract PriceRecordContract[] getLowestPriceRecords();

	/**
	 * Returns how many prices {@link #getLowestPriceRecords()} would report - one per inner record id group - without
	 * building the array.
	 *
	 * The default delegates to {@link #getLowestPriceRecords()}, which is free for the multi-price shapes since they
	 * already hold that array. {@link SinglePriceEntityPrices} overrides it to avoid building a one-element array.
	 *
	 * @return number of the lowest price records of this entity, zero when the entity has no price here
	 */
	public int getLowestPriceRecordCount() {
		return getLowestPriceRecords().length;
	}

	/**
	 * Hands every price {@link #getLowestPriceRecords()} would report to `priceConsumer`, in the very same order, and
	 * allocates nothing in any implementation. This is the accessor the query path uses: it runs once per entity of
	 * a result set, where materializing a one-element array per entity is the whole cost.
	 *
	 * The default delegates to {@link #getLowestPriceRecords()}, which is free for the multi-price shapes since they
	 * already hold that array. {@link SinglePriceEntityPrices} overrides it to avoid building a one-element array.
	 *
	 * @param priceConsumer receives each of this entity's lowest price records, ordered by internal price id
	 */
	public void forEachLowestPriceRecord(@Nonnull Consumer<PriceRecordContract> priceConsumer) {
		for (final PriceRecordContract priceRecord : getLowestPriceRecords()) {
			priceConsumer.accept(priceRecord);
		}
	}

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
	 *
	 * The lookup binary-searches this entity's id array within a window that advances with the probed ids, so both
	 * sides have to be ascending by {@link PriceRecordContract#internalPriceId()}: an out-of-order `priceTriples`
	 * collapses the window and {@link Arrays#binarySearch} rejects it outright.
	 *
	 * @param priceTriples price records to test, ordered by {@link PriceRecordContract#internalPriceId()}
	 * @return true when at least one triple is a price of this entity
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
	 *
	 * The single-price implementation builds this array on demand.
	 */
	@Nonnull
	protected abstract PriceRecordContract[] getAllPrices();

	/**
	 * Returns array of all {@link PriceRecord#internalPriceId()}, ascending.
	 *
	 * The single-price implementation builds this array on demand, so a caller that merely walks the ids must use
	 * {@link #getSize()} together with {@link #getInternalPriceIdAt(int)} instead.
	 *
	 * The result is read-only: the multi-price implementations hand out the array they hold, so writing into it
	 * rewrites this holder.
	 */
	@Nonnull
	public abstract int[] getInternalPriceIds();

	/**
	 * Returns the {@link PriceRecord#internalPriceId()} that {@link #getInternalPriceIds()} would report at `index`,
	 * without building the array. The ids are ordered ascending and there are exactly {@link #getSize()} of them.
	 *
	 * The default delegates to {@link #getInternalPriceIds()}, which is free for the multi-price shapes since they
	 * already hold that array; its own indexing throws {@link IndexOutOfBoundsException} for an out-of-range index.
	 * {@link SinglePriceEntityPrices} overrides it to avoid building a one-element array and throws the same
	 * exception explicitly, with a clearer message.
	 *
	 * @param index position of the wanted id, in `[0, getSize())`
	 * @return the internal price id at that position
	 * @throws IndexOutOfBoundsException when `index` addresses no price of this entity
	 */
	public int getInternalPriceIdAt(int index) {
		return getInternalPriceIds()[index];
	}

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

	/**
	 * Returns the heap this wrapper occupies, in bytes — its own object and the arrays it allocates, but **not** the
	 * price records those arrays point at.
	 *
	 * The records belong to the `priceRecords` tree of the
	 * {@link io.evitadb.index.price.PriceListAndCurrencyPriceSuperIndex} that holds this wrapper: an entity-prices map
	 * is that same information re-indexed by entity id, so charging the bodies here would bill each of them twice.
	 * The lowest-price array is a second alias again — it points at entries of the all-prices array — so it too
	 * contributes its slots alone.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public abstract long getHeapSizeInBytes();

}
