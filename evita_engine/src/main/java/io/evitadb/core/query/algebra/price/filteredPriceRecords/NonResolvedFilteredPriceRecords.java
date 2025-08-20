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

package io.evitadb.core.query.algebra.price.filteredPriceRecords;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.price.PriceListAndCurrencyPriceIndex;
import io.evitadb.index.price.model.priceRecord.CumulatedVirtualPriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.utils.Assert;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This implementation is similar to {@link ResolvedFilteredPriceRecords} but contains only array
 * of {@link CumulatedVirtualPriceRecord} and bitmap of price ids that can be looked up in {@link PriceListAndCurrencyPriceIndex}
 * lazily. This represents minimal state kept in cache that allows to reconstruct original data needed for sorting
 * the entities according to a properly identified price.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
public class NonResolvedFilteredPriceRecords implements FilteredPriceRecords {
	@Serial private static final long serialVersionUID = 81390910058928098L;

	/**
	 * Contains references to price list indexes that will provide the appropriate price for provided
	 * {@link PriceRecordContract#internalPriceId()}. The order of price indexes is crucial the first price index
	 * that returns non-null price will finish the lookup for particular price.
	 */
	private final PriceListAndCurrencyPriceIndex<?,?>[] priceIndexes;
	/**
	 * Contains the array of {@link CumulatedVirtualPriceRecord} that cannot be looked up anywhere and must be kept
	 * the same as were previously computed.
	 */
	private final PriceRecordContract[] cumulatedPriceRecords;
	/**
	 * Collected price records that corresponds with the formula {@link Formula#compute()} output.
	 */
	private final Bitmap priceRecordsIds;
	/**
	 * Contains the flag whether the prices were already resolved. Because the computation is costly we want to guard
	 * that the resolving happens only once.
	 */
	private boolean alreadyResolved;

	public NonResolvedFilteredPriceRecords(
		@Nonnull PriceRecordContract[] cumulatedPriceRecords,
		@Nonnull Bitmap priceRecordsIds,
		@Nonnull PriceListAndCurrencyPriceIndex<?,?>[] priceIndexes
	) {
		this.cumulatedPriceRecords = cumulatedPriceRecords;
		this.priceRecordsIds = priceRecordsIds;
		this.priceIndexes = priceIndexes;
	}

	/**
	 * Method returns the array of {@link PriceRecordContract} that were provided/filtered by appropriate
	 * {@link Formula}. The price records are always sorted by {@link PriceRecordContract#entityPrimaryKey()} in
	 * ascending order.
	 *
	 * Beware! This method internally calls {@link #toResolvedFilteredPriceRecords()}.
	 */
	@Override
	public @Nonnull PriceRecordLookup getPriceRecordsLookup() {
		return toResolvedFilteredPriceRecords().getPriceRecordsLookup();
	}

	/**
	 * This method returns price records in this object and ensures the output is sorted by entity id in the ascending
	 * order.
	 */
	@Nonnull
	public ResolvedFilteredPriceRecords toResolvedFilteredPriceRecords() {
		Assert.isPremiseValid(!this.alreadyResolved, "This instance was already resolved!");
		final PriceRecordContract[] result = new PriceRecordContract[this.cumulatedPriceRecords.length + this.priceRecordsIds.size()];
		System.arraycopy(this.cumulatedPriceRecords, 0, result, 0, this.cumulatedPriceRecords.length);
		final AtomicInteger resultPeek = new AtomicInteger(this.cumulatedPriceRecords.length);

		final RoaringBitmapWriter<RoaringBitmap> notFound = RoaringBitmapBackedBitmap.buildWriter();
		for (PriceListAndCurrencyPriceIndex<?,?> priceIndex : this.priceIndexes) {
			priceIndex.getPriceRecords(
				this.priceRecordsIds,
				priceRecordContract -> result[resultPeek.getAndIncrement()] = priceRecordContract,
				notFound::add
			);
		}
		Assert.isPremiseValid(resultPeek.get() == result.length, "Not all records were resolved!");
		this.alreadyResolved = true;
		return new ResolvedFilteredPriceRecords(result, SortingForm.NOT_SORTED);
	}

}
