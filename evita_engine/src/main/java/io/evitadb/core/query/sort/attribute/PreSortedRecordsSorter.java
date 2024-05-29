/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.sort.CacheableSorter;
import io.evitadb.core.query.sort.ConditionalSorter;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.attribute.cache.FlattenedMergedSortedRecordsProvider;
import io.evitadb.index.attribute.SortedRecordsSupplier;
import lombok.RequiredArgsConstructor;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * This sorter sorts {@link AbstractFormula#compute()} result according to {@link SortedRecordsProvider} that contains information
 * about record ids in already sorted fashion. This sorter executes following operation:
 *
 * - creates mask of positions in presorted array that refer to the {@link AbstractFormula#compute()} result
 * - copies slice of record ids in the presorted array that conform to the mask (in the order of presorted array)
 * the slice respects requested start and end index
 * - if the result is complete it is returned, if there is space left and there were record ids that were not found
 * in presorted array append these to the tail of the result until end index is reached or the set is exhausted
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class PreSortedRecordsSorter extends AbstractRecordsSorter implements CacheableSorter, ConditionalSorter {
	private static final long CLASS_ID = 795011057191754417L;
	/**
	 * This callback will be called when this sorter is computed.
	 */
	private final Consumer<CacheableSorter> computationCallback;
	/**
	 * This instance will be used by this sorter to access pre sorted arrays of entities.
	 */
	private final Supplier<SortedRecordsProvider[]> sortedRecordsSupplier;
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
	private Long estimatedCost;
	/**
	 * Contains memoized value of {@link #getHash()} method.
	 */
	private Long hash;
	/**
	 * Contains memoized value of {@link #gatherTransactionalIds()} method.
	 */
	private long[] transactionalIds;
	/**
	 * Contains memoized value of {@link #gatherTransactionalIds()} computed hash.
	 */
	private Long transactionalIdHash;
	/**
	 * Contains memoized value of {@link #getSortedRecordsProviders()} method.
	 */
	private MergedSortedRecordsSupplier memoizedResult;

	public PreSortedRecordsSorter(
		@Nonnull Supplier<SortedRecordsProvider[]> sortedRecordsSupplier
	) {
		this.computationCallback = null;
		this.sortedRecordsSupplier = sortedRecordsSupplier;
		this.unknownRecordIdsSorter = null;
	}

	@Nonnull
	@Override
	public Sorter cloneInstance() {
		return new PreSortedRecordsSorter(
			computationCallback,
			sortedRecordsSupplier,
			null
		);
	}

	@Nonnull
	@Override
	public Sorter andThen(Sorter sorterForUnknownRecords) {
		return new PreSortedRecordsSorter(
			computationCallback,
			sortedRecordsSupplier,
			sorterForUnknownRecords
		);
	}

	@Nullable
	@Override
	public Sorter getNextSorter() {
		return unknownRecordIdsSorter;
	}

	@Override
	public int sortAndSlice(@Nonnull QueryContext queryContext, @Nonnull Formula input, int startIndex, int endIndex, @Nonnull int[] result, int peak) {
		return getMemoizedResult().sortAndSlice(queryContext, input, startIndex, endIndex, result, peak);
	}

	@Override
	public void initialize(@Nonnull CalculationContext calculationContext) {
		if (this.hash == null) {
			this.hash = calculationContext.getHashFunction().hashLongs(
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
		}
		if (this.transactionalIds == null) {
			this.transactionalIds = Arrays.stream(getSortedRecordsProviders())
				.filter(SortedRecordsSupplier.class::isInstance)
				.map(SortedRecordsSupplier.class::cast)
				.mapToLong(SortedRecordsSupplier::getTransactionalId)
				.toArray();
			this.transactionalIdHash = calculationContext.getHashFunction().hashLongs(this.transactionalIds);
		}
		if (this.estimatedCost == null) {
			if (calculationContext.visit(CalculationType.ESTIMATED_COST, this)) {
				this.estimatedCost = Arrays.stream(getSortedRecordsProviders())
					.mapToInt(SortedRecordsProvider::getRecordCount)
					.sum() * getOperationCost();
			} else {
				this.estimatedCost = 0L;
			}
		}
	}

	@Override
	public long getHash() {
		if (this.hash == null) {
			initialize(CalculationContext.NO_CACHING_INSTANCE);
		}
		return this.hash;
	}

	@Override
	public long getTransactionalIdHash() {
		if (this.transactionalIdHash == null) {
			initialize(CalculationContext.NO_CACHING_INSTANCE);
		}
		return this.transactionalIdHash;
	}

	@Nonnull
	@Override
	public long[] gatherTransactionalIds() {
		if (this.transactionalIds == null) {
			initialize(CalculationContext.NO_CACHING_INSTANCE);
		}
		return this.transactionalIds;
	}

	@Override
	public long getEstimatedCost() {
		if (this.estimatedCost == null) {
			initialize(CalculationContext.NO_CACHING_INSTANCE);
		}
		return this.estimatedCost;
	}

	@Override
	public long getCost() {
		if (this.estimatedCost == null) {
			initialize(CalculationContext.NO_CACHING_INSTANCE);
		}
		return this.estimatedCost;
	}

	@Override
	public long getOperationCost() {
		return 19687L;
	}

	@Override
	public long getCostToPerformanceRatio() {
		if (this.estimatedCost == null) {
			initialize(CalculationContext.NO_CACHING_INSTANCE);
		}
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
			sortedRecordsSupplier,
			unknownRecordIdsSorter
		);
	}

	@Override
	public boolean shouldApply(@Nonnull QueryContext queryContext) {
		return queryContext.getPrefetchedEntities() == null;
	}

	@Nonnull
	public MergedSortedRecordsSupplier getMemoizedResult() {
		if (memoizedResult == null) {
			final SortedRecordsProvider[] sortedRecordsProviders = getSortedRecordsProviders();
			memoizedResult = new MergedSortedRecordsSupplier(
				sortedRecordsProviders,
				unknownRecordIdsSorter
			);
			if (computationCallback != null) {
				computationCallback.accept(this);
			}
		}
		return memoizedResult;
	}

	@Nonnull
	private SortedRecordsProvider[] getSortedRecordsProviders() {
		if (memoizedSortedRecordsProviders == null) {
			memoizedSortedRecordsProviders = sortedRecordsSupplier.get();
		}
		return memoizedSortedRecordsProviders;
	}
}
