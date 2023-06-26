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

package io.evitadb.core.query.sort.attribute;

import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.sort.CacheableSorter;
import io.evitadb.core.query.sort.ConditionalSorter;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.attribute.cache.FlattenedMergedSortedRecordsProvider;
import io.evitadb.index.attribute.SortIndex.SortedRecordsSupplier;
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
	 * This is the type of entity that is being sorted.
	 */
	private final String entityType;
	/**
	 * This instance will be used by this sorter to access pre sorted arrays of entities.
	 */
	private final Supplier<SortedRecordsProvider[]> sortedRecordsSupplier;
	/**
	 * This sorter instance will be used for sorting entities, that cannot be sorted by this sorter.
	 */
	private final Sorter unknownRecordIdsSorter;
	/**
	 * TODO JNO - document me
	 */
	private SortedRecordsProvider[] memoizedSortedRecordsProviders;
	/**
	 * Contains memoized value of {@link #computeHash(LongHashFunction)} method.
	 */
	private Long memoizedHash;
	/**
	 * Contains memoized value of {@link #gatherTransactionalIds()} method.
	 */
	private long[] memoizedTransactionalIds;
	/**
	 * Contains memoized value of {@link #gatherTransactionalIds()} computed hash.
	 */
	private Long memoizedTransactionalIdHash;
	/**
	 * Contains memoized value of {@link #getSortedRecordsProviders()} method.
	 */
	private MergedSortedRecordsSupplier memoizedResult;

	public PreSortedRecordsSorter(
		@Nonnull String entityType,
		@Nonnull Supplier<SortedRecordsProvider[]> sortedRecordsSupplier
	) {
		this.computationCallback = null;
		this.entityType = entityType;
		this.sortedRecordsSupplier = sortedRecordsSupplier;
		this.unknownRecordIdsSorter = null;
	}

	@Nonnull
	@Override
	public Sorter andThen(Sorter sorterForUnknownRecords) {
		return new PreSortedRecordsSorter(
			computationCallback,
			entityType,
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
	public long computeHash(@Nonnull LongHashFunction hashFunction) {
		if (memoizedHash == null) {
			memoizedHash = hashFunction.hashLongs(
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
		return memoizedHash;
	}

	private SortedRecordsProvider[] getSortedRecordsProviders() {
		if (memoizedSortedRecordsProviders == null) {
			memoizedSortedRecordsProviders = sortedRecordsSupplier.get();
		}
		return memoizedSortedRecordsProviders;
	}

	@Override
	public long computeTransactionalIdHash(@Nonnull LongHashFunction hashFunction) {
		if (memoizedTransactionalIdHash == null) {
			memoizedTransactionalIdHash = hashFunction.hashLongs(gatherTransactionalIds());
		}
		return memoizedTransactionalIdHash;
	}

	@Nonnull
	@Override
	public long[] gatherTransactionalIds() {
		if (memoizedTransactionalIds == null) {
			memoizedTransactionalIds = Arrays.stream(getSortedRecordsProviders())
				.filter(SortedRecordsSupplier.class::isInstance)
				.map(SortedRecordsSupplier.class::cast)
				.mapToLong(SortedRecordsSupplier::getTransactionalId)
				.toArray();
		}
		return memoizedTransactionalIds;
	}

	@Override
	public long getEstimatedCost() {
		return Arrays.stream(getSortedRecordsProviders())
			.mapToInt(SortedRecordsProvider::getRecordCount)
			.sum() * getOperationCost();
	}

	@Override
	public long getCost() {
		return getEstimatedCost();
	}

	@Override
	public long getOperationCost() {
		return 19687L;
	}

	@Override
	public long getCostToPerformanceRatio() {
		return getEstimatedCost();
	}

	@Override
	public FlattenedMergedSortedRecordsProvider toSerializableResult(long extraResultHash, @Nonnull LongHashFunction hashFunction) {
		return new FlattenedMergedSortedRecordsProvider(
			computeHash(hashFunction),
			computeTransactionalIdHash(hashFunction),
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

	@Override
	public boolean shouldApply(@Nonnull QueryContext queryContext) {
		return queryContext.getPrefetchedEntities() == null;
	}

	@Nonnull
	@Override
	public int[] sortAndSlice(@Nonnull QueryContext queryContext, @Nonnull Formula input, int startIndex, int endIndex) {
		return getMemoizedResult().sortAndSlice(queryContext, input, startIndex, endIndex);
	}

	@Nonnull
	@Override
	public CacheableSorter getCloneWithComputationCallback(@Nonnull Consumer<CacheableSorter> selfOperator) {
		return new PreSortedRecordsSorter(
			selfOperator,
			entityType,
			sortedRecordsSupplier,
			unknownRecordIdsSorter
		);
	}

	@Nonnull
	public MergedSortedRecordsSupplier getMemoizedResult() {
		if (memoizedResult == null) {
			memoizedResult = new MergedSortedRecordsSupplier(
				getSortedRecordsProviders(),
				unknownRecordIdsSorter
			);
			if (computationCallback != null) {
				computationCallback.accept(this);
			}
		}
		return memoizedResult;
	}
}
