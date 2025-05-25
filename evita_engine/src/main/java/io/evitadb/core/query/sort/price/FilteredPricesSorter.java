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

package io.evitadb.core.query.sort.price;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.PriceNatural;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordAccessor;
import io.evitadb.core.query.algebra.price.FilteredPriceRecordsLookupResult;
import io.evitadb.core.query.algebra.price.termination.SumPriceTerminationFormula;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
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
import java.util.function.IntConsumer;

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

	public FilteredPricesSorter(
		@Nonnull OrderDirection sortOrder,
		@Nonnull QueryPriceMode queryPriceMode,
		@Nonnull Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors
	) {
		this.priceRecordComparator = getPriceRecordComparator(sortOrder, queryPriceMode);
		this.filteredPriceRecordAccessors = filteredPriceRecordAccessors;
		Assert.isTrue(!filteredPriceRecordAccessors.isEmpty(), "Price translate formulas must not be empty!");
	}

	public FilteredPricesSorter(
		@Nonnull Comparator<PriceRecordContract> priceRecordComparator,
		@Nonnull Collection<FilteredPriceRecordAccessor> filteredPriceRecordAccessors
	) {
		this.priceRecordComparator = priceRecordComparator;
		this.filteredPriceRecordAccessors = filteredPriceRecordAccessors;
		Assert.isTrue(!filteredPriceRecordAccessors.isEmpty(), "Price translate formulas must not be empty!");
	}

	@Nonnull
	@Override
	public SortingContext sortAndSlice(
		@Nonnull SortingContext sortingContext,
		@Nonnull int[] result,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		// compute entire set of entity pks that needs to be sorted
		final RoaringBitmap computeResultBitmap = RoaringBitmapBackedBitmap.getRoaringBitmap(sortingContext.nonSortedKeys());
		// collect price records from the filtering formulas
		final QueryExecutionContext queryContext = sortingContext.queryContext();
		this.priceRecordsLookupResult = new FilteredPriceRecordsCollector(
			computeResultBitmap, this.filteredPriceRecordAccessors, queryContext
		).getResult();

		// initialize the comparator with the lookup result
		if (this.priceRecordComparator instanceof PriceRecordsLookupResultAware priceRecordsLookupResultAware) {
			priceRecordsLookupResultAware.setPriceRecordsLookupResult(
				queryContext, computeResultBitmap, this.priceRecordsLookupResult
			);
		}

		// now sort filtered prices by passed comparator
		final PriceRecordContract[] translatedResult = this.priceRecordsLookupResult.getPriceRecords();
		Arrays.sort(translatedResult, getPriceRecordComparator());

		// determine the count and set of non-found (not-sorted) entities
		Bitmap notFoundEntities = this.priceRecordsLookupResult.getNotFoundEntities();
		if (this.priceRecordComparator instanceof NonSortedRecordsProvider notSortedRecordsProvider && notSortedRecordsProvider.getNonSortedRecords() != null) {
			if (notFoundEntities == null) {
				notFoundEntities = notSortedRecordsProvider.getNonSortedRecords();
			} else {
				notFoundEntities = new BaseBitmap(
					RoaringBitmap.or(
						RoaringBitmapBackedBitmap.getRoaringBitmap(notFoundEntities),
						RoaringBitmapBackedBitmap.getRoaringBitmap(notSortedRecordsProvider.getNonSortedRecords())
					)
				);
			}
		}
		final int notFoundEntitiesLength = notFoundEntities == null ? 0 : notFoundEntities.size();

		final int recomputedStartIndex = sortingContext.recomputedStartIndex();
		final int recomputedEndIndex = sortingContext.recomputedEndIndex();

		// slice the output and cut appropriate page from it
		final int peak = sortingContext.peak();
		final int pageSize = Math.min(recomputedEndIndex - recomputedStartIndex, translatedResult.length - notFoundEntitiesLength - recomputedStartIndex);
		int writtenRecords = 0;
		int skippedRecords = 0;
		for (int i = 0; i < recomputedStartIndex + pageSize; i++) {
			if (i < recomputedStartIndex) {
				skippedRecords++;
				if (skippedRecordsConsumer != null) {
					skippedRecordsConsumer.accept(translatedResult[i].entityPrimaryKey());
				}
			} else {
				result[peak + writtenRecords++] = translatedResult[i].entityPrimaryKey();
			}
		}

		return sortingContext.createResultContext(
			notFoundEntitiesLength == 0 ?
				EmptyBitmap.INSTANCE : new BaseBitmap(notFoundEntities),
			writtenRecords,
			skippedRecords
		);
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
	 * Provides a comparator used for sorting {@link PriceRecordContract} instances.
	 *
	 * @return A comparator for sorting {@link PriceRecordContract} objects.
	 */
	@Nonnull
	private Comparator<PriceRecordContract> getPriceRecordComparator() {
		return this.priceRecordComparator;
	}

	/**
	 * Interface that can be implemented by {@link FilteredPricesSorter#priceRecordComparator} that needs to be informed
	 * about the data internally calculated in the sorter prior to actual sorting starts.
	 */
	@SuppressWarnings("InterfaceWithOnlyOneDirectInheritor")
	public interface PriceRecordsLookupResultAware {

		/**
		 * Initializes the comparator with internally calculated data.
		 * Method is called before the comparator is used for the first time.
		 *
		 * @param queryContext The context of the query execution.
		 * @param filteredEntityPrimaryKeys The primary keys of the filtered entities.
		 * @param priceRecordsLookupResult The result of the filtered price records lookup.
		 */
		void setPriceRecordsLookupResult(
			@Nonnull QueryExecutionContext queryContext,
			@Nonnull RoaringBitmap filteredEntityPrimaryKeys,
			@Nonnull FilteredPriceRecordsLookupResult priceRecordsLookupResult
		);

	}

	/**
	 * Interface that can be implemented by {@link FilteredPricesSorter#priceRecordComparator} that produces its own
	 * set of non-sorted records that should be appended to the result of the sorting.
	 */
	@SuppressWarnings("InterfaceWithOnlyOneDirectInheritor")
	public interface NonSortedRecordsProvider {

		/**
		 * Returns an array of non-sorted entity primary keys. These keys will be appended to the unsorted records
		 * and passed to next sorter in the chain.
		 *
		 * @return an array of integers representing non-sorted record identifiers or null if no such records exist
		 */
		@Nullable
		Bitmap getNonSortedRecords();

	}

}
