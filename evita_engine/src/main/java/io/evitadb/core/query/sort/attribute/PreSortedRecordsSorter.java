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
import io.evitadb.core.query.sort.attribute.cache.FlattenedMergedSortedRecordsProvider;
import io.evitadb.core.query.sort.generic.AbstractRecordsSorter;
import io.evitadb.index.attribute.SortedRecordsSupplier;
import io.evitadb.utils.Assert;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.stream.LongStream;
import java.util.stream.Stream;

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
public class PreSortedRecordsSorter extends AbstractRecordsSorter implements CacheableSorter, ConditionalSorter {
	private static final long CLASS_ID = 795011057191754417L;

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
	 * Contains memoized value of {@link #getEstimatedCost()}  of this formula.
	 */
	private final Long estimatedCost;
	/**
	 * Contains memoized value of {@link #getHash()} method.
	 */
	private final Long hash;
	/**
	 * Contains memoized value of {@link #gatherTransactionalIds()} method.
	 */
	private final long[] transactionalIds;
	/**
	 * Contains memoized value of {@link #gatherTransactionalIds()} computed hash.
	 */
	private final Long transactionalIdHash;
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
		this.hash = HASH_FUNCTION.hashLongs(
			Stream.of(
					LongStream.of(CLASS_ID),
					LongStream.of(
						Arrays.stream(getSortedRecordsProviders())
							.filter(SortedRecordsSupplier.class::isInstance)
							.map(SortedRecordsSupplier.class::cast)
							.mapToLong(SortedRecordsSupplier::getTransactionalId)
							.toArray()
					)
				)
				.flatMapToLong(Function.identity())
				.toArray()
		);
		this.transactionalIds = Arrays.stream(getSortedRecordsProviders())
			.filter(SortedRecordsSupplier.class::isInstance)
			.map(SortedRecordsSupplier.class::cast)
			.mapToLong(SortedRecordsSupplier::getTransactionalId)
			.toArray();
		this.transactionalIdHash = HASH_FUNCTION.hashLongs(this.transactionalIds);
		this.estimatedCost = Arrays.stream(getSortedRecordsProviders())
			.mapToInt(SortedRecordsProvider::getRecordCount)
			.sum() * getOperationCost();
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
	public Sorter andThen(Sorter sorterForUnknownRecords) {
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
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		return getMemoizedResult().sortAndSlice(queryContext, input, startIndex, endIndex, result, peak, skippedRecordsConsumer);
	}

	@Override
	public void initialize(@Nonnull QueryExecutionContext executionContext) {
		// do nothing
	}

	@Override
	public long getHash() {
		return this.hash;
	}

	@Override
	public long getTransactionalIdHash() {
		return this.transactionalIdHash;
	}

	@Nonnull
	@Override
	public long[] gatherTransactionalIds() {
		return this.transactionalIds;
	}

	@Override
	public long getEstimatedCost() {
		return this.estimatedCost;
	}

	@Override
	public long getCost() {
		return this.estimatedCost;
	}

	@Override
	public long getOperationCost() {
		return 19687L;
	}

	@Override
	public long getCostToPerformanceRatio() {
		Assert.isPremiseValid(this.estimatedCost != null, "Sorter must be initialized prior to calling getCostToPerformanceRatio().");
		return this.estimatedCost;
	}

	@Override
	public FlattenedMergedSortedRecordsProvider toSerializableResult(long extraResultHash, @Nonnull LongHashFunction hashFunction) {
		return new FlattenedMergedSortedRecordsProvider(
			getHash(),
			getTransactionalIdHash(),
			gatherTransactionalIds(),
			getMemoizedResult()
		);
	}

	@Override
	public int getSerializableResultSizeEstimate() {
		return FlattenedMergedSortedRecordsProvider.estimateSize(
			gatherTransactionalIds(),
			getMemoizedResult()
		);
	}

	@Nonnull
	@Override
	public CacheableSorter getCloneWithComputationCallback(@Nonnull Consumer<CacheableSorter> selfOperator) {
		return new PreSortedRecordsSorter(
			selfOperator,
			this.mergeMode,
			this.comparator,
			this.sortedRecordsSupplier,
			this.unknownRecordIdsSorter
		);
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
				new MergedSortedRecordsSupplier(
					sortedRecordsProviders,
					this.unknownRecordIdsSorter
				) :
				new MergedComparableSortedRecordsSupplier(
					this.comparator,
					sortedRecordsProviders,
					this.unknownRecordIdsSorter
				);
			if (this.computationCallback != null) {
				this.computationCallback.accept(this);
			}
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
