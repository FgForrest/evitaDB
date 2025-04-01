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

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.sort.CacheableSorter;
import io.evitadb.core.query.sort.ConditionalSorter;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.generic.AbstractRecordsSorter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.Consumer;
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
public class PreSortedRecordsSorter extends AbstractRecordsSorter implements ConditionalSorter {
	/**
	 * Contains the mode for combining multiple {@link SortedRecordsProvider} together.
	 */
	private final MergeMode mergeMode;
	/**
	 * This callback will be called when this sorter is computed.
	 */
	private final Consumer<CacheableSorter> computationCallback;
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
	 * This sorter instance will be used for sorting entities, that cannot be sorted by this sorter.
	 */
	private final Sorter unknownRecordIdsSorter;
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
		this(null, mergeMode, comparator, sortedRecordsSupplier, null);
	}

	private PreSortedRecordsSorter(
		@Nullable Consumer<CacheableSorter> computationCallback,
		@Nonnull MergeMode mergeMode,
		@SuppressWarnings("rawtypes") @Nullable Comparator comparator,
		@Nonnull Supplier<SortedRecordsProvider[]> sortedRecordsSupplier,
		@Nullable Sorter unknownRecordIdsSorter
	) {
		this.mergeMode = mergeMode;
		this.comparator = comparator;
		this.computationCallback = computationCallback;
		this.sortedRecordsSupplier = sortedRecordsSupplier;
		this.unknownRecordIdsSorter = unknownRecordIdsSorter;
	}

	@Nonnull
	@Override
	public Sorter cloneInstance() {
		return new PreSortedRecordsSorter(
			this.computationCallback,
			this.mergeMode,
			this.comparator,
			this.sortedRecordsSupplier,
			null
		);
	}

	@Nonnull
	@Override
	public Sorter andThen(@Nonnull Sorter sorterForUnknownRecords) {
		return new PreSortedRecordsSorter(
			this.computationCallback,
			this.mergeMode,
			this.comparator,
			this.sortedRecordsSupplier,
			sorterForUnknownRecords
		);
	}

	@Nullable
	@Override
	public Sorter getNextSorter() {
		return this.unknownRecordIdsSorter;
	}

	@Override
	public int sortAndSlice(
		@Nonnull QueryExecutionContext queryContext,
		@Nonnull Formula input,
		int startIndex,
		int endIndex,
		@Nonnull int[] result,
		int peak,
		int skipped,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		return getMemoizedResult()
			.sortAndSlice(queryContext, input, startIndex, endIndex, result, peak, skipped, skippedRecordsConsumer);
	}

	@Override
	public boolean shouldApply(@Nonnull QueryExecutionContext queryContext) {
		return queryContext.getPrefetchedEntities() == null;
	}

	@Nonnull
	public MergedSortedRecordsSupplierContract getMemoizedResult() {
		if (this.memoizedResult == null) {
			final SortedRecordsProvider[] sortedRecordsProviders = getSortedRecordsProviders();
			this.memoizedResult = this.mergeMode == MergeMode.APPEND_ALL ?
				new MergedSortedRecordsSupplierSorter(
					sortedRecordsProviders,
					this.unknownRecordIdsSorter
				) :
				new MergedComparableSortedRecordsSupplierSorter(
					Objects.requireNonNull(this.comparator),
					sortedRecordsProviders,
					this.unknownRecordIdsSorter
				);
		}
		return this.memoizedResult;
	}

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
