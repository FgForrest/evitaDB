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
	 * Returns bitmap of all indexed price ids.
	 */
	@Nonnull
	int[] getIndexedPriceIds() throws PriceListAndCurrencyPriceIndexTerminated;

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
	 * Returns array of all prices in this index ordered by price id in ascending order.
	 */
	@Nonnull
	PriceRecordContract[] getPriceRecords() throws PriceListAndCurrencyPriceIndexTerminated;

	/**
	 * Returns formula that computes all indexed records in this index. Depending on the type of the index it returns
	 * either entity ids or inner record ids.
	 */
	@Nonnull
	Formula createPriceIndexFormulaWithAllRecords() throws PriceListAndCurrencyPriceIndexTerminated;

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
