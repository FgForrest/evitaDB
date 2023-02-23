/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.index.price;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.priceIndex.PriceIdContainerFormula;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.index.IndexDataStructure;
import io.evitadb.index.array.CompositeObjectArray;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.iterator.BatchArrayIterator;
import io.evitadb.index.iterator.RoaringBitmapBatchArrayIterator;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.transactionalMemory.TransactionalLayerProducer;
import io.evitadb.store.model.StoragePart;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * General interface for price list related indexes. This interface allows unifying work with super and reference
 * price list and currency indexes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface PriceListAndCurrencyPriceIndex<DIFF_PIECE, COPY> extends IndexDataStructure, TransactionalLayerProducer<DIFF_PIECE, COPY>, Serializable {

	/**
	 * Returns unique identification of this index - contains price list name and currency combination.
	 */
	@Nonnull
	PriceIndexKey getPriceIndexKey();

	/**
	 * Returns bitmap of all indexed records of this combination of price list and currency.
	 */
	@Nonnull
	Bitmap getIndexedPriceEntityIds();

	/**
	 * Returns bitmap of all indexed price ids.
	 */
	@Nonnull
	int[] getIndexedPriceIds();

	/**
	 * Returns array of {@link PriceRecordContract} for passed bitmap of ids.
	 */
	@Nonnull
	default PriceRecordContract[] getPriceRecords(@Nonnull Bitmap priceIds) {
		return getPriceRecords(
			priceIds,
			priceRecordContract -> {
			},
			notFoundPriceId -> {
				throw new EvitaInternalError("Price with id " + notFoundPriceId + " was not found in the same index!");
			}
		);
	}

	/**
	 * Returns array of {@link PriceRecordContract} for passed bitmap of ids.
	 */
	@Nonnull
	default PriceRecordContract[] getPriceRecords(@Nonnull Bitmap priceIds, @Nonnull Consumer<PriceRecordContract> priceFoundCallback, @Nonnull IntConsumer priceIdNotFoundCallback) {
		// TOBEDONE JNO - there is also an issue https://github.com/RoaringBitmap/RoaringBitmap/issues/562 that could make this algorithm faster
		final BatchArrayIterator filteredPriceIdsIterator = new RoaringBitmapBatchArrayIterator(RoaringBitmapBackedBitmap.getRoaringBitmap(priceIds).getBatchIterator());
		final int[] supersetPriceIds = getIndexedPriceIds();
		final PriceRecordContract[] priceRecords = getPriceRecords();

		final CompositeObjectArray<PriceRecordContract> filteredPriceRecords = new CompositeObjectArray<>(PriceRecordContract.class);
		int priceIndex;
		int lastPriceId = 0;
		int lastPriceIndex = -1;
		int lastExpectedPriceIndex;
		int searchEndIndex;

		while (filteredPriceIdsIterator.hasNext()) {
			final int[] filteredBatch = filteredPriceIdsIterator.nextBatch();
			// get the last price id from the batch
			final int lastExpectedPriceId = filteredPriceIdsIterator.getPeek() > 0 ? filteredBatch[filteredPriceIdsIterator.getPeek() - 1] : -1;
			// compute the index of the last price in a batch
			lastExpectedPriceIndex = Arrays.binarySearch(
				supersetPriceIds,
				lastPriceIndex + 1,
				// we can even here optimise the end index to the max difference of the last really found price to batch end price
				lastExpectedPriceId == -1 ? supersetPriceIds.length : Math.min(supersetPriceIds.length, lastPriceIndex + lastExpectedPriceId - lastPriceId + 1),
				lastExpectedPriceId
			);
			// compute the end index that needs to be looked within for all prices in filter batch
			searchEndIndex = lastExpectedPriceIndex >= 0 ? lastExpectedPriceIndex + 1 : -1 * (lastExpectedPriceIndex) - 2 + 1;

			// iterate over all prices in filter batch
			for (int i = 0; i < filteredPriceIdsIterator.getPeek(); i++) {
				int filteredPriceId = filteredBatch[i];

				// if we reached the end of our price records
				if (lastPriceIndex >= priceRecords.length) {
					// iterate over rest of the filtered prices and report they were not found and finish
					for (int j = i; j < filteredPriceIdsIterator.getPeek(); j++) {
						priceIdNotFoundCallback.accept(filteredBatch[j]);
					}
					break;
				} else if (filteredPriceId == lastExpectedPriceId) {
					// if we reached the end price of the current batch - we can reuse the already known information
					if (lastExpectedPriceIndex < 0) {
						// the price was not found - report it
						priceIdNotFoundCallback.accept(filteredPriceId);
						lastPriceIndex = -1 * (lastExpectedPriceIndex) - 2;
					} else {
						// the price was found - report it
						final PriceRecordContract priceRecord = priceRecords[lastExpectedPriceIndex];
						priceFoundCallback.accept(priceRecord);
						filteredPriceRecords.add(priceRecord);
						lastPriceIndex = lastExpectedPriceIndex;
					}
				} else {
					// look for the index of price detail (searched block is getting smaller and smaller with each match)
					final int fromIndex = lastPriceIndex + 1;
					final int toIndex = Math.min(fromIndex + filteredPriceId - lastPriceId, searchEndIndex);

					// look for the price in currently read super set batch
					priceIndex = Arrays.binarySearch(supersetPriceIds, fromIndex, toIndex, filteredPriceId);

					if (priceIndex < 0) {
						// the price was not found - report it
						priceIdNotFoundCallback.accept(filteredPriceId);
						lastPriceIndex = -1 * (priceIndex) - 2;
					} else {
						// the price was found - report it
						final PriceRecordContract priceRecord = priceRecords[priceIndex];
						priceFoundCallback.accept(priceRecord);
						filteredPriceRecords.add(priceRecord);
						lastPriceIndex = priceIndex;
					}
				}
				lastPriceId = filteredPriceId;
			}
		}

		// return found prices as array
		return filteredPriceRecords.toArray();
	}

	/**
	 * Returns formula that computes all indexed records of this combination of price list and currency.
	 */
	@Nonnull
	Formula getIndexedPriceEntityIdsFormula();

	/**
	 * Returns formula that computes all indexed records of this combination of price list and currency that are valid
	 * at the passed moment.
	 */
	@Nonnull
	PriceIdContainerFormula getIndexedRecordIdsValidInFormula(OffsetDateTime theMoment);

	/**
	 * Returns array of all {@link PriceRecord#internalPriceId()} of the entity.
	 */
	@Nullable
	int[] getInternalPriceIdsForEntity(int entityId);

	/**
	 * Returns array of the lowest prices distinct by {@link PriceRecordContract#innerRecordId()} that exists in this
	 * index and that belong to the particular entity sorted by price id.
	 */
	@Nullable
	PriceRecordContract[] getLowestPriceRecordsForEntity(int entityId);

	/**
	 * Returns array of all prices in this index ordered by price id in ascending order.
	 */
	@Nonnull
	PriceRecordContract[] getPriceRecords();

	/**
	 * Returns formula that computes all indexed records in this index. Depending on the type of the index it returns
	 * either entity ids or inner record ids.
	 */
	@Nonnull
	Formula createPriceIndexFormulaWithAllRecords();

	/**
	 * Returns true if index is empty.
	 */
	boolean isEmpty();

	/**
	 * Method creates container for storing any of price related indexes from memory to the persistent storage.
	 */
	@Nullable
	StoragePart createStoragePart(int entityIndexPrimaryKey);

}
