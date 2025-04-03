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

package io.evitadb.core.query.sort.attribute;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.Sorter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * This sorter sorts {@link Formula#compute()} result according to {@link SortedRecordsProvider} that contains information
 * about record ids in already sorted fashion. This sorter executes following operation:
 *
 * - creates mask of positions in presorted array that refer to the {@link Formula#compute()} result
 * - copies slice of record ids in the presorted array that conform to the mask (in the order of presorted array)
 * the slice respects requested start and end index
 * - if the result is complete it is returned, if there is space left and there were record ids that were not found
 * in presorted array append these to the tail of the result until end index is reached or the set is exhausted
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class PreSortedRecordsSorter implements Sorter {
	/**
	 * Contains the mode for combining multiple {@link SortedRecordsProvider} together.
	 */
	private final MergeMode mergeMode;
	/**
	 * This instance will be used by this sorter to access pre sorted arrays of entities.
	 */
	private final Supplier<SortedRecordsProvider[]> sortedRecordsSupplier;
	/**
	 * The {@link Comparator} instance used for sorting records when {@link MergeMode#APPEND_FIRST} is used and multiple
	 * {@link SortedRecordsProvider} are provided and needs to be merged together.
	 */
	@SuppressWarnings("rawtypes") private final Comparator comparator;
	/**
	 * Field contains memoized value of {@link #getSortedRecordsProviders()} method.
	 */
	private SortedRecordsProvider[] memoizedSortedRecordsProviders;
	/**
	 * Contains memoized value of {@link #getSortedRecordsProviders()} method.
	 */
	private MergedSortedRecordsSupplierContract memoizedResult;

	public PreSortedRecordsSorter(
		@Nonnull MergeMode mergeMode,
		@SuppressWarnings("rawtypes") @Nullable Comparator comparator,
		@Nonnull Supplier<SortedRecordsProvider[]> sortedRecordsSupplier
	) {
		this.mergeMode = mergeMode;
		this.comparator = comparator;
		this.sortedRecordsSupplier = sortedRecordsSupplier;
	}

	@Nonnull
	@Override
	public SortingContext sortAndSlice(
		@Nonnull SortingContext sortingContext,
		@Nonnull int[] result,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		if (sortingContext.queryContext().getPrefetchedEntities() == null) {
			return getMemoizedResult()
				.sortAndSlice(sortingContext, result, skippedRecordsConsumer);
		} else {
			return sortingContext;
		}
	}

	/**
	 * Retrieves the memoized result of the merged sorted records supplier. If the result is not memoized yet,
	 * it initializes the memoized result based on the specified merge mode. When {@code MergeMode.APPEND_ALL}
	 * is used, it creates a {@link MergedSortedRecordsSupplierSorter}. Otherwise, it initializes a
	 * {@link MergedComparableSortedRecordsSupplierSorter} with the provided comparator and sorted records providers.
	 *
	 * @return a {@link MergedSortedRecordsSupplierContract} representing the memoized merged sorted records supplier.
	 */
	@Nonnull
	private MergedSortedRecordsSupplierContract getMemoizedResult() {
		if (this.memoizedResult == null) {
			this.memoizedResult = this.mergeMode == MergeMode.APPEND_ALL ?
				new MergedSortedRecordsSupplierSorter(
					getSortedRecordsProviders()
				) :
				new MergedComparableSortedRecordsSupplierSorter(
					Objects.requireNonNull(this.comparator),
					getSortedRecordsProviders()
				);
		}
		return this.memoizedResult;
	}

	/**
	 * Retrieves an array of sorted records providers. The result is memoized to ensure the sorted records
	 * are only loaded once and reused on subsequent invocations.
	 *
	 * @return an array of {@link SortedRecordsProvider} containing the sorted records providers.
	 */
	@Nonnull
	private SortedRecordsProvider[] getSortedRecordsProviders() {
		if (this.memoizedSortedRecordsProviders == null) {
			this.memoizedSortedRecordsProviders = this.sortedRecordsSupplier.get();
		}
		return this.memoizedSortedRecordsProviders;
	}

	/**
	 * Enum representing the modes of merging sorted records in the {@code PreSortedRecordsSorter}.
	 * This enum defines the strategies for combining multiple sorted records from different providers
	 * with behavior determined by the mode selected.
	 */
	public enum MergeMode {

		/**
		 * This mode will append all sorted records from first {@link SortedRecordsProvider}, then second, etc. until all
		 * sorted records are consumed. Duplicate record records are eliminated from the result.
		 */
		APPEND_ALL,
		/**
		 * This mode will append sorted record one by one from each {@link SortedRecordsProvider} selecting next one
		 * using provided {@link java.util.Comparator} instance applied on first element from each provider.
		 * Duplicate record records are eliminated from the result.
		 */
		APPEND_FIRST

	}

}
