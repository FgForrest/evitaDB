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

package io.evitadb.core.query.sort.price;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.PriceNatural;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordsLookupResult;
import io.evitadb.core.query.algebra.price.termination.SumPriceTerminationFormula;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.utils.SortUtils;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.utils.Assert;
import lombok.Getter;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;

/**
 * This sorter implementation executes sorting by price according to passed {@link PriceNatural} and {@link QueryPriceMode}.
 * Unfortunately there is no way how to use presorted datasets because the inner workings of the prices is too complicated
 * (there may be multiple prices for the same entity even in the same price list - for example with different time validity,
 * and correct price matters). The sorter also works with "virtual" prices that get created by {@link SumPriceTerminationFormula}
 * and contain accumulated price for all inner records. This is the second argument why pre-sorted prices are problematic
 * to be used.
 *
 * Sorter outputs set of entity ids sorted by price.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class FilteredPricesSorter implements Sorter {
	/**
	 * Comparator sorts {@link PriceRecord} by price with tax in ascending order.
	 */
	protected static final Comparator<PriceRecordContract> ASC_PRICE_WITH_TAX = Comparator.comparingInt(PriceRecordContract::priceWithTax);
	/**
	 * Comparator sorts {@link PriceRecord} by price without tax in ascending order.
	 */
	protected static final Comparator<PriceRecordContract> ASC_PRICE_WITHOUT_TAX = Comparator.comparingInt(PriceRecordContract::priceWithoutTax);
	/**
	 * Comparator sorts {@link PriceRecord} by price with tax in descending order.
	 */
	protected static final Comparator<PriceRecordContract> DESC_PRICE_WITH_TAX = (o1, o2) -> Integer.compare(o2.priceWithTax(), o1.priceWithTax());
	/**
	 * Comparator sorts {@link PriceRecord} by price without tax in descending order.
	 */
	protected static final Comparator<PriceRecordContract> DESC_PRICE_WITHOUT_TAX = (o1, o2) -> Integer.compare(o2.priceWithoutTax(), o1.priceWithoutTax());
	/**
	 * This sorter instance will be used for sorting entities, that cannot be sorted by this sorter.
	 */
	protected final Sorter unknownRecordIdsSorter;
	/**
	 * This collection contains list of {@link FilteredPriceRecordAccessor} that were used in the filtering query
	 * and already posses limited set of {@link PriceRecord} data that can be used for sorting. We want to avoid sorting
	 * excessive large data sets and using already prefiltered records allows it.
	 */
	private final Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors;
	/**
	 * Contains comparator that will be used for sorting price records (initialized in constructor).
	 */
	private final Comparator<PriceRecordContract> priceRecordComparator;
	/**
	 * Contains DTO that holds array of all {@link PriceRecord} that match entity primary keys produced by filtering
	 * formula and also array of entity primary keys that are not linked to any price.
	 */
	@Getter private FilteredPriceRecordsLookupResult priceRecordsLookupResult;

	public FilteredPricesSorter(@Nonnull OrderDirection sortOrder, @Nonnull QueryPriceMode queryPriceMode, @Nonnull Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors) {
		this.unknownRecordIdsSorter = null;
		this.priceRecordComparator = getPriceRecordComparator(sortOrder, queryPriceMode);
		this.filteredPriceRecordAccessors = filteredPriceRecordAccessors;
		Assert.isTrue(!filteredPriceRecordAccessors.isEmpty(), "Price translate formulas must not be empty!");
	}

	private FilteredPricesSorter(@Nonnull Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors, @Nonnull Comparator<PriceRecordContract> priceRecordComparator, @Nonnull Sorter unknownRecordIdsSorter) {
		this.unknownRecordIdsSorter = unknownRecordIdsSorter;
		this.filteredPriceRecordAccessors = filteredPriceRecordAccessors;
		this.priceRecordComparator = priceRecordComparator;
	}

	@Nonnull
	@Override
	public Sorter andThen(Sorter sorterForUnknownRecords) {
		return new FilteredPricesSorter(
			filteredPriceRecordAccessors,
			priceRecordComparator,
			sorterForUnknownRecords
		);
	}

	@Nonnull
	@Override
	public Sorter cloneInstance() {
		return new FilteredPricesSorter(
			filteredPriceRecordAccessors,
			priceRecordComparator,
			null
		);
	}

	@Nullable
	@Override
	public Sorter getNextSorter() {
		return unknownRecordIdsSorter;
	}

	@Override
	public int sortAndSlice(@Nonnull QueryContext queryContext, @Nonnull Formula input, int startIndex, int endIndex, @Nonnull int[] result, int peak) {
		// compute entire set of entity pks that needs to be sorted
		final Bitmap computeResult = input.compute();
		final RoaringBitmap computeResultBitmap = RoaringBitmapBackedBitmap.getRoaringBitmap(computeResult);
		// collect price records from the filtering formulas
		priceRecordsLookupResult = new FilteredPriceRecordsCollector(
			computeResultBitmap, filteredPriceRecordAccessors
		).getResult();

		// now sort filtered prices by passed comparator
		final PriceRecordContract[] translatedResult = priceRecordsLookupResult.getPriceRecords();
		Arrays.sort(translatedResult, getPriceRecordComparator());

		// slice the output and cut appropriate page from it
		final int pageSize = Math.min(endIndex - startIndex, translatedResult.length - startIndex);
		int written = 0;
		for (int i = startIndex; i < pageSize; i++) {
			result[peak + written++] = translatedResult[i].entityPrimaryKey();
		}

		// if the output is not complete, and we have not found entity PKs
		final int[] notFoundEntities = priceRecordsLookupResult.getNotFoundEntities();
		if (translatedResult.length < endIndex && (notFoundEntities == null || notFoundEntities.length > 0)) {
			// pass them to another sorter
			final int recomputedStartIndex = Math.max(0, startIndex - written);
			final int recomputedEndIndex = Math.max(0, endIndex - written);
			// combine sorted result with the unknown rest using additional sorter or default own
			return appendSortedUnknownEntityPks(
				queryContext,
				computeResult, computeResultBitmap,
				notFoundEntities,
				recomputedStartIndex, recomputedEndIndex,
				result, peak + written
			);
		} else {
			return peak + written;
		}
	}

	/**
	 * Creates {@link PriceRecord} comparator for sorting according to input `sortOrder` and `queryPriceMode`.
	 */
	private static Comparator<PriceRecordContract> getPriceRecordComparator(OrderDirection sortOrder, QueryPriceMode queryPriceMode) {
		return switch (sortOrder) {
			case ASC -> queryPriceMode == QueryPriceMode.WITH_TAX ? ASC_PRICE_WITH_TAX : ASC_PRICE_WITHOUT_TAX;
			case DESC -> queryPriceMode == QueryPriceMode.WITH_TAX ? DESC_PRICE_WITH_TAX : DESC_PRICE_WITHOUT_TAX;
		};
	}

	/**
	 * Method fills the missing gap for requested page with unknown entities sorted by {@link #unknownRecordIdsSorter}
	 * or by default in ascending order of PKs.
	 */
	private int appendSortedUnknownEntityPks(
		@Nonnull QueryContext queryContext,
		@Nonnull Bitmap computeResult,
		@Nonnull RoaringBitmap computeResultBitmap,
		@Nullable int[] notFoundArray,
		int startIndex,
		int endIndex,
		@Nonnull int[] result,
		int peak
	) {
		// compute the rest we need to fill in
		if (notFoundArray == null) {
			if (unknownRecordIdsSorter != null) {
				// use provided unknown sorter to sort the rest and copy the result to the output
				return unknownRecordIdsSorter.sortAndSlice(
					queryContext, new ConstantFormula(computeResult),
					startIndex, endIndex, result, peak
				);
			} else {
				// copy the not found ids sorted by PK asc in to the result
				final int[] buffer = queryContext.borrowBuffer();
				try {
					return SortUtils.appendNotFoundResult(
						result, peak, startIndex, endIndex, computeResultBitmap, buffer
					);
				} finally {
					queryContext.returnBuffer(buffer);
				}
			}
		} else {
			if (unknownRecordIdsSorter != null) {
				// use provided unknown sorter to sort the rest and copty the result to the output
				return unknownRecordIdsSorter.sortAndSlice(
					queryContext, new ConstantFormula(new BaseBitmap(notFoundArray)),
					startIndex, endIndex, result, peak
				);
			} else {
				// copy the not found ids sorted by PK asc in to the result
				return SortUtils.appendNotFoundResult(
					result, peak, startIndex, endIndex, notFoundArray
				);
			}
		}
	}

	private Comparator<PriceRecordContract> getPriceRecordComparator() {
		return priceRecordComparator;
	}

}
