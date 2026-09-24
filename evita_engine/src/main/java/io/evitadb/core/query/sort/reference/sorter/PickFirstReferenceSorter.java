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
import io.evitadb.roaringbitmap.PeekableIntIterator;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

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
 * A claim is pure bitmap work - the positions of the claimed owners in the index's sorted provider, and their removal
 * from the unclaimed rest - no value is read while claiming. Each index is asked in the cheaper of two ways: an
 * unclaimed rest far smaller than the index is resolved against it directly (the not-found result becomes the new
 * rest), otherwise the index is first intersected with the rest. An index without a sort index of the value is
 * skipped before any bitmap is touched, and the provider of an index is built only once the index has shown it holds
 * an unclaimed owner.
 *
 * The positions one index claimed, walked in ascending order, are already in the order of value in the ordering
 * direction and then of owner primary key in the same direction (the descending provider is the exact mirror of the
 * ascending one), so each index yields one sorted run. The runs are merged lazily by their head values into one
 * sorted provider for the whole reference, built per query; the merge reads values only for the owners it passes
 * and stops at the end of the requested page, so ordering a large selection costs its claims plus the page, never
 * a value read for every selected owner.
 *
 * Owners with no row carrying the value stay unsorted and fall through to the next sorter.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class PickFirstReferenceSorter implements Sorter {
	/**
	 * How many times larger than the unclaimed rest an index must be for the rest to be resolved against it directly.
	 * A direct resolution looks each unclaimed owner up in the index (a binary search, about `log2(index size)`
	 * comparisons), a linear intersection touches every owner of both sides once, so the direct path only pays off
	 * when the rest is a small fraction of the index - taken at comparable sizes, it looked a moderate selection up
	 * in every large residual index of a narrowed query and cost several times the intersection.
	 */
	private static final int DIRECT_RESOLUTION_RATIO = 16;
	/**
	 * Resolves the reduced indexes that may hold a row of the selection, in target order.
	 */
	@Nonnull private final PickFirstReducedIndexResolver indexResolver;
	/**
	 * Returns the source of the provider of sorted values of one reduced index, or `null` when the index holds none.
	 * Finding out whether an index holds values is cheap; building its provider is not (it creates the value seeker
	 * eagerly), so the provider is built only once the index has shown it holds an unclaimed owner.
	 */
	@Nonnull private final Function<ReducedEntityIndex, Supplier<SortedRecordsProvider>> providerFactory;
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
	 * @param indexResolver   resolves the reduced indexes that may hold a row of the selection
	 * @param providerFactory returns the source of the provider of sorted values of one reduced index, or `null` when
	 *                        the index holds no value
	 * @param comparator      comparator of the provided values in the ordering direction
	 * @param primaryKeyOrder direction of the ordering, applied to the primary keys of owners with equal values
	 */
	public PickFirstReferenceSorter(
		@Nonnull PickFirstReducedIndexResolver indexResolver,
		@Nonnull Function<ReducedEntityIndex, Supplier<SortedRecordsProvider>> providerFactory,
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
		final Claims claims = claim(queryContext, selection, indexes);
		if (claims.runCount == 0) {
			return sortingContext;
		}

		final int startIndex = sortingContext.recomputedStartIndex();
		final int endIndex = sortingContext.recomputedEndIndex();
		final int peak = sortingContext.peak();
		final int toRead = Math.min(endIndex - startIndex, result.length - peak);
		int alreadyRead = 0;
		int toSkip = startIndex;
		final RunMerge merge = new RunMerge(
			claims, this.comparator, this.primaryKeyOrder == OrderDirection.DESC
		);
		while ((toRead > alreadyRead || toSkip > 0) && merge.hasNext()) {
			final int recordId = merge.next();
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
			claims.unclaimed.isEmpty() ? EmptyBitmap.INSTANCE : new BaseBitmap(claims.unclaimed),
			alreadyRead,
			startIndex - toSkip
		);
	}

	/**
	 * Walks the indexes in target order and lets each claim the unclaimed owners it holds a value for. Claiming reads
	 * no value: an index contributes the positions of its claimed owners in its sorted provider, and the owners leave
	 * the unclaimed rest in one bitmap operation.
	 *
	 * @param queryContext the context of the executed query
	 * @param selection    the owners being sorted
	 * @param indexes      the reduced indexes that may hold a row of the selection, in target order
	 * @return one sorted run per index that claimed an owner, and the owners no index claimed
	 */
	@Nonnull
	private Claims claim(
		@Nonnull QueryExecutionContext queryContext,
		@Nonnull Bitmap selection,
		@Nonnull ReducedEntityIndex[] indexes
	) {
		// the working copy is copy-on-write, so removing claimed owners never touches the selection itself
		PersistentRoaringBitmap unclaimed = RoaringBitmapBackedBitmap.getRoaringBitmap(selection).clone();
		int unclaimedCount = unclaimed.getCardinality();
		final Claims claims = new Claims();
		final int[] bufferA = queryContext.borrowBuffer();
		final int[] bufferB = queryContext.borrowBuffer();
		// debug override (or null for cost-based) + per-strategy telemetry tally (null when telemetry is off)
		final ForcedSortResolution forcedResolution = SortResolutionStrategies.resolveForcedResolution(queryContext);
		final int[] strategyTally = SortResolutionStrategies.newStrategyTally(queryContext);
		try {
			for (ReducedEntityIndex index : indexes) {
				if (unclaimedCount == 0) {
					break;
				}
				final Bitmap indexOwners = index.getAllPrimaryKeys();
				if ((long) unclaimedCount * DIRECT_RESOLUTION_RATIO <= indexOwners.size()) {
					// the unclaimed rest is far smaller than the index: resolve it directly - a lookup per unclaimed
					// owner - and what the provider does not hold is exactly the new unclaimed rest, with no
					// intersection and no removal
					final Supplier<SortedRecordsProvider> providerSource = this.providerFactory.apply(index);
					if (providerSource == null) {
						continue;
					}
					final SortedRecordsProvider provider = providerSource.get();
					final PositionResolution resolution = provider.resolvePositions(
						unclaimed, unclaimedCount, bufferA, bufferB, forcedResolution
					);
					SortResolutionStrategies.tally(strategyTally, resolution);
					if (!resolution.mask().isEmpty()) {
						claims.addRun(provider, resolution.mask());
						unclaimed = resolution.notFoundRecords();
						unclaimedCount = resolution.notFoundRecordsCount();
					}
				} else {
					// comparable sizes, or the index is the smaller side: resolving the whole rest would look every
					// unclaimed owner up in the index and copy the rest into the not-found result once per index, so
					// the two are intersected linearly and only the owners the index holds are resolved and removed

					// an index without a sort index of the value holds nothing to claim - ask for it before touching
					// any bitmap, it is the whole cost of an ordering by a value few rows carry
					final Supplier<SortedRecordsProvider> providerSource = this.providerFactory.apply(index);
					if (providerSource == null) {
						continue;
					}
					final PersistentRoaringBitmap indexOwnerBitmap = RoaringBitmapBackedBitmap.getRoaringBitmap(indexOwners);
					// most indexes a gather hands over (the residual ones above all) hold no unclaimed owner, and this
					// check tells so without allocating the intersection
					if (!PersistentRoaringBitmap.intersects(indexOwnerBitmap, unclaimed)) {
						continue;
					}
					final PersistentRoaringBitmap candidates = PersistentRoaringBitmap.and(indexOwnerBitmap, unclaimed);
					final SortedRecordsProvider provider = providerSource.get();
					final PositionResolution resolution = provider.resolvePositions(
						candidates, candidates.getCardinality(), bufferA, bufferB, forcedResolution
					);
					SortResolutionStrategies.tally(strategyTally, resolution);
					if (!resolution.mask().isEmpty()) {
						claims.addRun(provider, resolution.mask());
						final PersistentRoaringBitmap claimed = resolution.notFoundRecordsCount() == 0 ?
							candidates :
							PersistentRoaringBitmap.andNot(candidates, resolution.notFoundRecords());
						unclaimed.andNot(claimed);
						unclaimedCount -= claimed.getCardinality();
					}
				}
			}
			claims.unclaimed = unclaimed;
			return claims;
		} finally {
			SortResolutionStrategies.report(queryContext, strategyTally);
			queryContext.returnBuffer(bufferA);
			queryContext.returnBuffer(bufferB);
		}
	}

	/**
	 * The sorted runs the walk produced - per claiming index its provider and the positions of the owners it claimed
	 * in that provider - and the owners left unclaimed.
	 */
	private static final class Claims {
		/**
		 * The owners left unclaimed, set when the walk ends.
		 */
		@Nonnull PersistentRoaringBitmap unclaimed = new PersistentRoaringBitmap();
		/**
		 * The provider of each run.
		 */
		@Nonnull SortedRecordsProvider[] providers = new SortedRecordsProvider[16];
		/**
		 * The positions of the claimed owners of each run in its provider.
		 */
		@Nonnull PersistentRoaringBitmap[] masks = new PersistentRoaringBitmap[16];
		/**
		 * The number of runs.
		 */
		int runCount;

		/**
		 * Records the run of one index.
		 *
		 * @param provider the sorted provider of the index
		 * @param mask     the positions of the owners the index claimed, never empty
		 */
		void addRun(@Nonnull SortedRecordsProvider provider, @Nonnull PersistentRoaringBitmap mask) {
			if (this.runCount == this.providers.length) {
				this.providers = Arrays.copyOf(this.providers, this.runCount * 2);
				this.masks = Arrays.copyOf(this.masks, this.runCount * 2);
			}
			this.providers[this.runCount] = provider;
			this.masks[this.runCount] = mask;
			this.runCount++;
		}
	}

	/**
	 * Lazy k-way merge of the runs: a binary min-heap of runs keyed by their head claim, by value in the ordering
	 * direction and then by owner primary key in the same direction. Only the head of each run has its value read, so
	 * the work done is proportional to the owners actually returned (plus one head per run).
	 */
	private static final class RunMerge {
		/**
		 * The provider of each run.
		 */
		@Nonnull private final SortedRecordsProvider[] providers;
		/**
		 * Iterator over the remaining positions of each run, ascending.
		 */
		@Nonnull private final PeekableIntIterator[] positions;
		/**
		 * Value seeker of each run, fed ascending positions.
		 */
		@Nonnull private final SortedComparableForwardSeeker[] seekers;
		/**
		 * Owner primary key at the head of each run.
		 */
		@Nonnull private final int[] headOwners;
		/**
		 * Value of the owner at the head of each run.
		 */
		@Nonnull private final Serializable[] headValues;
		/**
		 * Run identifiers organized as a binary min-heap over their heads.
		 */
		@Nonnull private final int[] heap;
		/**
		 * Comparator of the values in the ordering direction.
		 */
		@SuppressWarnings("rawtypes")
		@Nonnull private final Comparator comparator;
		/**
		 * Whether owners with equal values follow in descending primary key order.
		 */
		private final boolean primaryKeysDescending;
		/**
		 * The number of live runs in {@link #heap}.
		 */
		private int heapSize;

		/**
		 * Positions every run at its first claim and builds the heap.
		 *
		 * @param claims                the runs to merge
		 * @param comparator            comparator of the values in the ordering direction
		 * @param primaryKeysDescending whether owners with equal values follow in descending primary key order
		 */
		RunMerge(
			@Nonnull Claims claims,
			@SuppressWarnings("rawtypes") @Nonnull Comparator comparator,
			boolean primaryKeysDescending
		) {
			final int runCount = claims.runCount;
			this.providers = claims.providers;
			this.positions = new PeekableIntIterator[runCount];
			this.seekers = new SortedComparableForwardSeeker[runCount];
			this.headOwners = new int[runCount];
			this.headValues = new Serializable[runCount];
			this.heap = new int[runCount];
			this.comparator = comparator;
			this.primaryKeysDescending = primaryKeysDescending;
			for (int run = 0; run < runCount; run++) {
				this.positions[run] = claims.masks[run].getIntIterator();
				final SortedComparableForwardSeeker seeker = this.providers[run].getSortedComparableForwardSeeker();
				seeker.reset();
				this.seekers[run] = seeker;
				// every run is non-empty, so each has a head
				advance(run);
				this.heap[run] = run;
			}
			this.heapSize = runCount;
			for (int slot = this.heapSize / 2 - 1; slot >= 0; slot--) {
				siftDown(slot);
			}
		}

		/**
		 * Returns true when an owner remains to be returned.
		 *
		 * @return true if any run still has a head
		 */
		boolean hasNext() {
			return this.heapSize > 0;
		}

		/**
		 * Returns the next owner in the merged order and advances its run.
		 *
		 * @return the owner primary key
		 */
		int next() {
			final int run = this.heap[0];
			final int owner = this.headOwners[run];
			if (this.positions[run].hasNext()) {
				advance(run);
			} else {
				this.heap[0] = this.heap[--this.heapSize];
			}
			if (this.heapSize > 0) {
				siftDown(0);
			}
			return owner;
		}

		/**
		 * Moves the head of the run to its next claimed position, which must exist.
		 *
		 * @param run the run to advance
		 */
		private void advance(int run) {
			final int position = this.positions[run].next();
			this.headOwners[run] = this.providers[run].recordAt(position);
			this.headValues[run] = this.seekers[run].getValueToCompareOn(position);
		}

		/**
		 * Compares the heads of two runs.
		 *
		 * @param runA the first run
		 * @param runB the second run
		 * @return negative when the head of `runA` goes first
		 */
		private int compareHeads(int runA, int runB) {
			//noinspection unchecked
			final int result = this.comparator.compare(this.headValues[runA], this.headValues[runB]);
			if (result != 0) {
				return result;
			}
			return this.primaryKeysDescending ?
				Integer.compare(this.headOwners[runB], this.headOwners[runA]) :
				Integer.compare(this.headOwners[runA], this.headOwners[runB]);
		}

		/**
		 * Restores the heap property below the given slot.
		 *
		 * @param slot the slot whose run may be greater than its children
		 */
		private void siftDown(int slot) {
			int current = slot;
			while (true) {
				final int left = 2 * current + 1;
				if (left >= this.heapSize) {
					return;
				}
				final int right = left + 1;
				int smallest = left;
				if (right < this.heapSize && compareHeads(this.heap[right], this.heap[left]) < 0) {
					smallest = right;
				}
				if (compareHeads(this.heap[smallest], this.heap[current]) >= 0) {
					return;
				}
				final int swap = this.heap[current];
				this.heap[current] = this.heap[smallest];
				this.heap[smallest] = swap;
				current = smallest;
			}
		}
	}

}
