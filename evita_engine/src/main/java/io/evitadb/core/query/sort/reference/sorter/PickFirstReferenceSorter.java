/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.query.sort.reference.sorter;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.PickFirstByEntityProperty;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.ForcedSortResolution;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.PositionResolution;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedComparableForwardSeeker;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.attribute.sorter.SortResolutionStrategies;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBatchIterator;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * Index route of a {@link PickFirstByEntityProperty} ordering: sorts owner entities by the value of their first row,
 * in target order, that carries the sorted value.
 *
 * The reduced indexes to walk are a function of the selection, so they are resolved here, at execution time, by
 * {@link PickFirstReducedIndexResolver} - never at planning time, when no selection exists yet and the only index
 * sets at hand are the candidates of whatever `referenceHaving` the filter contains.
 *
 * The indexes are then walked in target order and each claims the owners it holds a value for among those not
 * claimed yet - a winner-per-owner projection of "the first row in target order". Each index is asked only about
 * its own unclaimed owners (the intersection of its owner set with the unclaimed rest of the selection), so the walk
 * costs what the claimed rows cost rather than the size of the selection times the number of indexes, which is what
 * merging one provider per index cost: each provider had to report the whole unclaimed rest back as a new bitmap.
 * The claimed `(owner, value)` pairs are finally sorted once, by value in the ordering direction and then by owner
 * primary key in the same direction - one sorted provider for the whole reference, built per query.
 *
 * Owners with no row carrying the value stay unsorted and fall through to the next sorter.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class PickFirstReferenceSorter implements Sorter {
	/**
	 * Resolves the reduced indexes holding a row of the selection, in target order.
	 */
	@Nonnull private final PickFirstReducedIndexResolver indexResolver;
	/**
	 * Returns the provider of sorted values of one reduced index, or `null` when the index holds none.
	 */
	@Nonnull private final Function<ReducedEntityIndex, SortedRecordsProvider> providerFactory;
	/**
	 * Comparator of the provided values, already in the ordering direction.
	 */
	@SuppressWarnings("rawtypes")
	@Nonnull private final Comparator comparator;
	/**
	 * Direction of the ordering, applied to the primary keys of owners with equal values.
	 */
	@Nonnull private final OrderDirection primaryKeyOrder;

	/**
	 * Creates the sorter.
	 *
	 * @param indexResolver   resolves the reduced indexes holding a row of the selection
	 * @param providerFactory returns the provider of sorted values of one reduced index or `null`
	 * @param comparator      comparator of the provided values in the ordering direction
	 * @param primaryKeyOrder direction of the ordering, applied to the primary keys of owners with equal values
	 */
	public PickFirstReferenceSorter(
		@Nonnull PickFirstReducedIndexResolver indexResolver,
		@Nonnull Function<ReducedEntityIndex, SortedRecordsProvider> providerFactory,
		@SuppressWarnings("rawtypes") @Nonnull Comparator comparator,
		@Nonnull OrderDirection primaryKeyOrder
	) {
		this.indexResolver = indexResolver;
		this.providerFactory = providerFactory;
		this.comparator = comparator;
		this.primaryKeyOrder = primaryKeyOrder;
	}

	@Nonnull
	@Override
	public SortingContext sortAndSlice(
		@Nonnull SortingContext sortingContext,
		@Nonnull int[] result,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		final QueryExecutionContext queryContext = sortingContext.queryContext();
		final Bitmap selection = sortingContext.nonSortedKeys();
		if (queryContext.getPrefetchedEntities() != null || selection.isEmpty()) {
			return sortingContext;
		}
		final ReducedEntityIndex[] indexes = this.indexResolver.resolve(selection).indexes();
		final ClaimedValues claimed = claim(queryContext, selection, indexes);
		if (claimed.count == 0) {
			return sortingContext;
		}
		final int[] order = claimed.sortedOrder(this.comparator, this.primaryKeyOrder == OrderDirection.DESC);

		final int startIndex = sortingContext.recomputedStartIndex();
		final int endIndex = sortingContext.recomputedEndIndex();
		final int peak = sortingContext.peak();
		final int toRead = Math.min(endIndex - startIndex, result.length - peak);
		int alreadyRead = 0;
		int toSkip = startIndex;
		for (int i = 0; i < order.length && (toRead > alreadyRead || toSkip > 0); i++) {
			final int recordId = claimed.owners[order[i]];
			if (toSkip > 0) {
				toSkip--;
				if (skippedRecordsConsumer != null) {
					skippedRecordsConsumer.accept(recordId);
				}
			} else {
				result[peak + alreadyRead++] = recordId;
			}
		}
		return sortingContext.createResultContext(
			claimed.unclaimed.isEmpty() ? EmptyBitmap.INSTANCE : new BaseBitmap(claimed.unclaimed),
			alreadyRead,
			startIndex - toSkip
		);
	}

	/**
	 * Walks the indexes in target order and lets each claim the unclaimed owners it holds a value for.
	 *
	 * @param queryContext the context of the executed query
	 * @param selection    the owners being sorted
	 * @param indexes      the reduced indexes holding a row of the selection, in target order
	 * @return the claimed owners with their values, and the owners no index claimed
	 */
	@Nonnull
	private ClaimedValues claim(
		@Nonnull QueryExecutionContext queryContext,
		@Nonnull Bitmap selection,
		@Nonnull ReducedEntityIndex[] indexes
	) {
		// the working copy is copy-on-write, so removing claimed owners never touches the selection itself
		final PersistentRoaringBitmap unclaimed = RoaringBitmapBackedBitmap.getRoaringBitmap(selection).clone();
		final ClaimedValues claimed = new ClaimedValues(Math.min(selection.size(), 1024), unclaimed);
		final int[] bufferA = queryContext.borrowBuffer();
		final int[] bufferB = queryContext.borrowBuffer();
		// debug override (or null for cost-based) + per-strategy telemetry tally (null when telemetry is off)
		final ForcedSortResolution forcedResolution = SortResolutionStrategies.resolveForcedResolution(queryContext);
		final int[] strategyTally = SortResolutionStrategies.newStrategyTally(queryContext);
		try {
			for (ReducedEntityIndex index : indexes) {
				if (unclaimed.isEmpty()) {
					break;
				}
				final PersistentRoaringBitmap candidates = PersistentRoaringBitmap.and(
					RoaringBitmapBackedBitmap.getRoaringBitmap(index.getAllPrimaryKeys()), unclaimed
				);
				if (candidates.isEmpty()) {
					continue;
				}
				final SortedRecordsProvider provider = this.providerFactory.apply(index);
				if (provider == null) {
					continue;
				}
				final PositionResolution resolution = provider.resolvePositions(
					candidates, candidates.getCardinality(), bufferA, bufferB, forcedResolution
				);
				SortResolutionStrategies.tally(strategyTally, resolution);
				final SortedComparableForwardSeeker seeker = provider.getSortedComparableForwardSeeker();
				seeker.reset();
				final RoaringBatchIterator maskIterator = resolution.mask().getBatchIterator();
				while (maskIterator.hasNext()) {
					final int batchPeak = maskIterator.nextBatch(bufferB);
					if (batchPeak == 0) {
						break;
					}
					for (int i = 0; i < batchPeak; i++) {
						final int position = bufferB[i];
						final int owner = provider.recordAt(position);
						claimed.add(owner, seeker.getValueToCompareOn(position));
						unclaimed.remove(owner);
					}
				}
			}
			return claimed;
		} finally {
			SortResolutionStrategies.report(queryContext, strategyTally);
			queryContext.returnBuffer(bufferA);
			queryContext.returnBuffer(bufferB);
		}
	}

	/**
	 * The owners claimed by the walk, with the value each was claimed with, and the owners left unclaimed.
	 */
	private static final class ClaimedValues {
		/**
		 * The owners left unclaimed, updated in place by the walk.
		 */
		@Nonnull final PersistentRoaringBitmap unclaimed;
		/**
		 * Primary keys of the claimed owners, in the order they were claimed.
		 */
		@Nonnull int[] owners;
		/**
		 * The value each owner of {@link #owners} was claimed with.
		 */
		@Nonnull Serializable[] values;
		/**
		 * The number of claimed owners.
		 */
		int count;

		ClaimedValues(int initialCapacity, @Nonnull PersistentRoaringBitmap unclaimed) {
			this.owners = new int[Math.max(initialCapacity, 16)];
			this.values = new Serializable[this.owners.length];
			this.unclaimed = unclaimed;
		}

		/**
		 * Records a claimed owner.
		 *
		 * @param owner the owner primary key
		 * @param value the value it was claimed with
		 */
		void add(int owner, @Nonnull Serializable value) {
			if (this.count == this.owners.length) {
				this.owners = Arrays.copyOf(this.owners, this.count * 2);
				this.values = Arrays.copyOf(this.values, this.count * 2);
			}
			this.owners[this.count] = owner;
			this.values[this.count] = value;
			this.count++;
		}

		/**
		 * Returns the positions of the claimed owners sorted by value and then by owner primary key.
		 *
		 * @param comparator            comparator of the values in the ordering direction
		 * @param primaryKeysDescending whether owners with equal values follow in descending primary key order
		 * @return positions into {@link #owners}, in the order the owners are returned
		 */
		@Nonnull
		int[] sortedOrder(@SuppressWarnings("rawtypes") @Nonnull Comparator comparator, boolean primaryKeysDescending) {
			final int[] order = new int[this.count];
			for (int i = 0; i < this.count; i++) {
				order[i] = i;
			}
			ArrayUtils.sortArray(
				(a, b) -> {
					//noinspection unchecked
					final int result = comparator.compare(this.values[a], this.values[b]);
					if (result != 0) {
						return result;
					}
					return primaryKeysDescending ?
						Integer.compare(this.owners[b], this.owners[a]) :
						Integer.compare(this.owners[a], this.owners[b]);
				},
				order
			);
			return order;
		}
	}

}
