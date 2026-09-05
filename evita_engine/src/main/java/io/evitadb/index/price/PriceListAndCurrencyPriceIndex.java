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

package io.evitadb.index.price;

import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.priceIndex.PriceIdContainerFormula;
import io.evitadb.core.transaction.memory.TransactionalStateProducer;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * General interface for price list related indexes. This interface allows unifying work with super and reference
 * price list and currency indexes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface PriceListAndCurrencyPriceIndex<COPY> extends IndexDataStructure, TransactionalStateProducer<COPY>, Serializable {

	/**
	 * Returns unique identification of this index - contains price list name and currency combination.
	 */
	@Nonnull
	PriceIndexKey getPriceIndexKey();

	/**
	 * Returns bitmap of all indexed records of this combination of price list and currency.
	 */
	@Nonnull
	Bitmap getIndexedPriceEntityIds() throws PriceListAndCurrencyPriceIndexTerminated;

	/**
	 * Returns the bitmap of all indexed internal price ids.
	 *
	 * The bitmap is returned as-is rather than as a materialized `int[]`: implementations already hold one, so this
	 * costs nothing and no caller pays for a copy it did not ask for. Callers that need an array call
	 * {@link Bitmap#getArray()} on the result and own the cost of doing so.
	 */
	@Nonnull
	Bitmap getIndexedPriceIds() throws PriceListAndCurrencyPriceIndexTerminated;

	/**
	 * Materializes the price records for the passed id bitmap into an array, in ascending key order.
	 * Every requested id MUST exist here — a missing id raises {@link GenericEvitaInternalError}.
	 * Prefer {@link #forEachPriceRecord} when the records only need streaming; this array form is for
	 * consumers that need random access (for example binary search).
	 */
	@Nonnull
	default PriceRecordContract[] getPriceRecords(@Nonnull Bitmap priceIds) throws PriceListAndCurrencyPriceIndexTerminated {
		final CompositeObjectArray<PriceRecordContract> filteredPriceRecords =
			new CompositeObjectArray<>(PriceRecordContract.class);
		forEachPriceRecord(
			priceIds,
			filteredPriceRecords::add,
			notFoundPriceId -> {
				throw new GenericEvitaInternalError("Price with id " + notFoundPriceId + " was not found in the same index!");
			}
		);
		return filteredPriceRecords.toArray();
	}

	/**
	 * Streams the price records for the passed id bitmap to `priceFoundCallback` in ascending key order; every id
	 * absent from this index is reported to `priceIdNotFoundCallback`. Unlike {@link #getPriceRecords(Bitmap)} it
	 * never materializes an array, so streaming consumers pay no per-call allocation.
	 *
	 * @param priceIds                ascending bitmap of internal price ids to resolve
	 * @param priceFoundCallback      receives each resolved price record in ascending key order
	 * @param priceIdNotFoundCallback receives each requested id not present in this index
	 */
	void forEachPriceRecord(
		@Nonnull Bitmap priceIds,
		@Nonnull Consumer<PriceRecordContract> priceFoundCallback,
		@Nonnull IntConsumer priceIdNotFoundCallback
	) throws PriceListAndCurrencyPriceIndexTerminated;

	/**
	 * Returns formula that computes all indexed records of this combination of price list and currency.
	 */
	@Nonnull
	Formula getIndexedPriceEntityIdsFormula() throws PriceListAndCurrencyPriceIndexTerminated;

	/**
	 * Returns formula that computes all indexed records of this combination of price list and currency that are valid
	 * at the passed moment.
	 */
	@Nonnull
	PriceIdContainerFormula getIndexedRecordIdsValidInFormula(@Nonnull OffsetDateTime theMoment) throws PriceListAndCurrencyPriceIndexTerminated;

	/**
	 * Cache-aware variant of {@link #getIndexedRecordIdsValidInFormula(OffsetDateTime)} intended for the
	 * {@code priceValidInNow} (suffix-{@code now}) variant of
	 * {@link io.evitadb.api.query.filter.PriceValidIn}. Routes the underlying validity-range lookup through
	 * the memoizing path on the internal {@code RangeIndex}, so consecutive same-bucket queries reuse the
	 * previously materialized bitmap.
	 */
	@Nonnull
	PriceIdContainerFormula getIndexedRecordIdsValidNowFormula(@Nonnull OffsetDateTime theMoment) throws PriceListAndCurrencyPriceIndexTerminated;

	/**
	 * Returns array of all {@link PriceRecord#internalPriceId()} of the entity.
	 */
	@Nullable
	int[] getInternalPriceIdsForEntity(int entityId) throws PriceListAndCurrencyPriceIndexTerminated;

	/**
	 * Returns array of the lowest prices distinct by {@link PriceRecordContract#innerRecordId()} that exists in this
	 * index and that belong to the particular entity sorted by price id.
	 */
	@Nullable
	PriceRecordContract[] getLowestPriceRecordsForEntity(int entityId) throws PriceListAndCurrencyPriceIndexTerminated;

	/**
	 * Streams what {@link #getLowestPriceRecordsForEntity(int)} would return to `priceConsumer`, in the very same
	 * order, without materializing the array. Prefer this on any path that runs per entity of a result set: the
	 * overwhelmingly common case is an entity with exactly one price here, whose holder keeps that price as a field
	 * and would have to build a one-element array to answer the array form.
	 *
	 * The default implementation falls back to the array form for indexes that have no cheaper route; the super price
	 * index, which owns the entity-to-prices mapping, overrides it with the allocation-free one. The ref index leaves
	 * it at this default, which then rejects the caller through {@link #getLowestPriceRecordsForEntity(int)} exactly
	 * as that method documents. The only other type that inherits this default rather than overriding it is
	 * {@code io.evitadb.spike.mock.MockPriceListAndCurrencyPriceIndex}, which lives in the performance-test module
	 * outside the default build reactor and rejects through the same delegate - so nothing built by a default build
	 * ever runs this default's loop body; the success path is exercised only via the super index's override.
	 *
	 * @param entityId      primary key of the entity whose lowest price records are wanted
	 * @param priceConsumer receives each of the entity's lowest price records, ordered by internal price id
	 * @return true when the entity has at least one price in this index (and `priceConsumer` was therefore called),
	 * false when it has none (and `priceConsumer` was not called at all)
	 */
	default boolean forEachLowestPriceRecordOfEntity(
		int entityId,
		@Nonnull Consumer<PriceRecordContract> priceConsumer
	) throws PriceListAndCurrencyPriceIndexTerminated {
		final PriceRecordContract[] lowestPriceRecords = getLowestPriceRecordsForEntity(entityId);
		if (lowestPriceRecords == null || lowestPriceRecords.length == 0) {
			return false;
		}
		for (final PriceRecordContract lowestPriceRecord : lowestPriceRecords) {
			priceConsumer.accept(lowestPriceRecord);
		}
		return true;
	}

	/**
	 * Returns array of all prices in this index ordered by price id in ascending order.
	 */
	@Nonnull
	PriceRecordContract[] getPriceRecords() throws PriceListAndCurrencyPriceIndexTerminated;

	/**
	 * Returns formula that computes all indexed records in this index. Depending on the type of the index it returns
	 * either entity ids or inner record ids.
	 *
	 * @param superPriceIndex the price index of the GLOBAL entity index of this index's collection and scope. The
	 *                        returned formula resolves the lowest price records of an entity through it, because only
	 *                        a super index owns the entity-to-prices mapping - a reduced (ref) index merely narrows
	 *                        which entities are in scope. Supplying it here, rather than letting the index hold
	 *                        a pointer to it, is what keeps a reduced index free of any catalog-version pin: the caller
	 *                        is already pinned to a catalog version and hands over that version's GLOBAL.
	 */
	@Nonnull
	Formula createPriceIndexFormulaWithAllRecords(@Nonnull PriceSuperIndex superPriceIndex)
		throws PriceListAndCurrencyPriceIndexTerminated;

	/**
	 * Returns true if index is empty.
	 */
	boolean isEmpty() throws PriceListAndCurrencyPriceIndexTerminated;

	/**
	 * Method creates container for storing any of price related indexes from memory to the persistent storage.
	 */
	@Nullable
	StoragePart createStoragePart(int entityIndexPrimaryKey) throws PriceListAndCurrencyPriceIndexTerminated;

	/**
	 * Appends this index's modified storage parts to `sink`. The default emits the single whole-index part from
	 * {@link #createStoragePart(int)} (the ref index and any non-paged index); the super price index overrides this to
	 * emit the granular `PAGED` leaf pages when its price-record tree spans multiple leaves.
	 *
	 * @param entityIndexPrimaryKey the owning entity index pk
	 * @param sink                  the trapped-changes accumulator for this commit
	 */
	default void appendStorageParts(int entityIndexPrimaryKey, @Nonnull TrappedChanges sink)
		throws PriceListAndCurrencyPriceIndexTerminated {
		final StoragePart part = createStoragePart(entityIndexPrimaryKey);
		if (part != null) {
			sink.addChangeToStore(part);
		}
	}

	/**
	 * Returns true if index is terminated.
	 * @return true if index is terminated
	 */
	boolean isTerminated();

	/**
	 * Sets price index as terminated.
	 */
	void terminate();

	/**
	 * Exception is thrown when outside logic tries to work with terminated price index.
	 */
	class PriceListAndCurrencyPriceIndexTerminated extends RuntimeException {
		@Serial private static final long serialVersionUID = 1551590894819222190L;

		public PriceListAndCurrencyPriceIndexTerminated(@Nonnull String message) {
			super(message);
		}

	}

}
