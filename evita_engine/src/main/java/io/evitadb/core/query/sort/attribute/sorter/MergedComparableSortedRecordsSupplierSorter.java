/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.query.sort.attribute.sorter;

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.ForcedSortResolution;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.PositionResolution;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedComparableForwardSeeker;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.attribute.MergedSortedRecordsSupplierContract;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.utils.Assert;
import lombok.Getter;
import io.evitadb.roaringbitmap.RoaringBatchIterator;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * Implementation of the {@link SortedRecordsProvider} that merges multiple instances into a one discarding
 * the duplicate record primary keys. This operation is quite costly for large data sets and should be cached.
 *
 * When at most one provider holds selected records - always the case for a single provider - its records are emitted
 * straight from the position mask, without reading any value; only several contributing providers are merged.
 *
 * A record is claimed by the first provider in array order that contains it, so the order of the providers decides
 * which value a record present in several of them is compared on. Records comparing equal on their values follow in
 * the order of their primary keys in the direction of the ordering - ascending for an ascending order, descending for
 * a descending one - which is the order a single provider already yields for them, so merging never changes the order
 * of equal records.
 *
 * The runs the contributing providers claimed are merged lazily: their current heads form a binary min-heap, each
 * emitted record costs `O(log k)` for `k` contributing providers, a value is read only for a record the merge passes,
 * and the merge stops at the end of the requested page.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public final class MergedComparableSortedRecordsSupplierSorter implements Sorter, MergedSortedRecordsSupplierContract {
	/**
	 * Contains the {@link Comparator} for the sorting execution.
	 */
	@SuppressWarnings("rawtypes") private final Comparator comparator;
	/**
	 * Direction in which records comparing equal on their values are ordered by their primary keys.
	 */
	private final OrderDirection primaryKeyOrder;
	/**
	 * Contains the {@link SortedRecordsProvider} implementation with merged pre-sorted records.
	 */
	@Getter private final SortedRecordsProvider[] sortedRecordsProviders;

	/**
	 * Creates the sorter.
	 *
	 * @param comparator             comparator of the values the providers hold, already in the ordering direction
	 * @param primaryKeyOrder        direction of the ordering, applied to the primary keys of records with equal values
	 * @param sortedRecordsProviders providers in the order in which they claim records
	 */
	public MergedComparableSortedRecordsSupplierSorter(
		@SuppressWarnings("rawtypes") @Nonnull Comparator comparator,
		@Nonnull OrderDirection primaryKeyOrder,
		@Nonnull SortedRecordsProvider[] sortedRecordsProviders
	) {
		this.comparator = comparator;
		this.primaryKeyOrder = primaryKeyOrder;
		this.sortedRecordsProviders = sortedRecordsProviders;
	}

	@Nonnull
	@Override
	public SortingContext sortAndSlice(
		@Nonnull SortingContext sortingContext,
		@Nonnull int[] result,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		final QueryExecutionContext queryContext = sortingContext.queryContext();
		if (queryContext.getPrefetchedEntities() == null) {
			final int startIndex = sortingContext.recomputedStartIndex();
			final int endIndex = sortingContext.recomputedEndIndex();
			final int peak = sortingContext.peak();
			final Bitmap selectedRecordIds = sortingContext.nonSortedKeys();
			final int[] resolveBufferA = queryContext.borrowBuffer();
			final int[] resolveBufferB = queryContext.borrowBuffer();
			// debug override (or null for cost-based) + per-strategy telemetry tally (null when telemetry is off)
			final ForcedSortResolution forcedResolution = SortResolutionStrategies.resolveForcedResolution(queryContext);
			final int[] strategyTally = SortResolutionStrategies.newStrategyTally(queryContext);
			try {
				final int toRead = Math.min(endIndex - startIndex, result.length - peak);
				int alreadyRead = 0;
				int toSkip = startIndex;

				PersistentRoaringBitmap recordsToSort = RoaringBitmapBackedBitmap.getRoaringBitmap(selectedRecordIds);
				int recordsToSortCount = selectedRecordIds.size();

				// single-provider short-circuit: with one provider the mask positions are already in value-then-pk
				// order (position == the record's rank in the sorted order), so we can emit them directly and skip the
				// per-record seeker value lookup that the K-way merge below would otherwise pay (the dominant cost for
				// sparse selections - see the middle-ground reference/category listings)
				if (this.sortedRecordsProviders.length == 1) {
					final SortedRecordsProvider provider = this.sortedRecordsProviders[0];
					final PositionResolution resolution = provider.resolvePositions(
						recordsToSort, recordsToSortCount, resolveBufferA, resolveBufferB, forcedResolution
					);
					SortResolutionStrategies.tally(strategyTally, resolution);
					return emitSingleMask(
						sortingContext, provider, resolution.mask(), resolution.notFoundRecords(),
						result, resolveBufferB, toRead, skippedRecordsConsumer
					);
				}

				// first we need to create masks for all selected record ids using provided sorted record providers
				final PositionResolution[] maskResults = new PositionResolution[this.sortedRecordsProviders.length];
				int maskPeak = -1;
				// the only provider whose mask is not empty, -1 for none, -2 once a second one is found
				int contributingProvider = -1;
				for (int i = 0; i < this.sortedRecordsProviders.length; i++) {
					final SortedRecordsProvider sortedRecordsProvider = this.sortedRecordsProviders[++maskPeak];
					final PositionResolution resolution = sortedRecordsProvider.resolvePositions(
						recordsToSort, recordsToSortCount, resolveBufferA, resolveBufferB, forcedResolution
					);
					SortResolutionStrategies.tally(strategyTally, resolution);
					maskResults[maskPeak] = resolution;
					if (!resolution.mask().isEmpty()) {
						contributingProvider = contributingProvider == -1 ? maskPeak : -2;
					}
					recordsToSort = resolution.notFoundRecords();
					recordsToSortCount = resolution.notFoundRecordsCount();
					if (recordsToSortCount == 0) {
						break;
					}
				}

				if (contributingProvider != -2) {
					// at most one provider holds selected records - its mask positions are already in value-then-pk
					// order, so they are emitted directly; the merge would read the value of every record through
					// a seeker that may have to walk the provider's values from the start for nothing to compare
					return contributingProvider == -1 ?
						sortingContext.createResultContext(
							recordsToSort.isEmpty() ? EmptyBitmap.INSTANCE : new BaseBitmap(recordsToSort), 0, 0
						) :
						emitSingleMask(
							sortingContext, this.sortedRecordsProviders[contributingProvider],
							maskResults[contributingProvider].mask(), recordsToSort,
							result, resolveBufferB, toRead, skippedRecordsConsumer
						);
				}

				// next we need to fetch comparable values for head values from each mask and compare them
				final SortedRecordsProviderBuffer[] sortedRecordsProviderBuffers = new SortedRecordsProviderBuffer[maskPeak + 1];
				int sortedRecordsProviderBufferPeak = 0;
				final boolean primaryKeysDescending = this.primaryKeyOrder == OrderDirection.DESC;
				// init first values
				for (int i = 0; i <= maskPeak; i++) {
					final PositionResolution resolution = maskResults[i];
					if (resolution.mask().isEmpty()) {
						// a provider that claimed nothing gets no buffer - creating one borrows a buffer and a seeker
						continue;
					}
					final SortedRecordsProvider sortedRecordsProvider = this.sortedRecordsProviders[i];
					final SortedRecordsProviderBuffer sortedRecordsProviderBuffer = new SortedRecordsProviderBuffer(
						this.comparator, primaryKeysDescending,
						sortedRecordsProvider, resolution.mask(), queryContext
					);
					if (sortedRecordsProviderBuffer.fetchNext()) {
						sortedRecordsProviderBuffers[sortedRecordsProviderBufferPeak++] = sortedRecordsProviderBuffer;
					} else {
						sortedRecordsProviderBuffer.close();
					}
				}

				// the heads of the runs form a binary min-heap - one advance costs O(log k) comparisons and moves,
				// where keeping the heads in a sorted array shifted up to k - 1 of them per emitted record
				for (int i = (sortedRecordsProviderBufferPeak >>> 1) - 1; i >= 0; i--) {
					siftDown(sortedRecordsProviderBuffers, sortedRecordsProviderBufferPeak, i);
				}

				while ((toRead > alreadyRead || toSkip > 0) && sortedRecordsProviderBufferPeak > 0) {
					final SortedRecordsProviderBuffer sortedPk = sortedRecordsProviderBuffers[0];
					if (toSkip > 0) {
						toSkip--;
						if (skippedRecordsConsumer != null) {
							skippedRecordsConsumer.accept(sortedPk.primaryKey());
						}
					} else {
						result[peak + alreadyRead++] = sortedPk.primaryKey();
					}
					// fetch next value for the used one and restore the heap
					if (!sortedPk.fetchNext()) {
						// the run is exhausted - its last head takes the root and sinks
						sortedPk.close();
						sortedRecordsProviderBufferPeak--;
						sortedRecordsProviderBuffers[0] = sortedRecordsProviderBuffers[sortedRecordsProviderBufferPeak];
						sortedRecordsProviderBuffers[sortedRecordsProviderBufferPeak] = null;
					}
					if (sortedRecordsProviderBufferPeak > 1) {
						siftDown(sortedRecordsProviderBuffers, sortedRecordsProviderBufferPeak, 0);
					}
				}

				// finalize the rest of the sorted record providers
				for (int i = 0; i < sortedRecordsProviderBufferPeak; i++) {
					sortedRecordsProviderBuffers[i].close();
				}

				return sortingContext.createResultContext(
					recordsToSort.isEmpty() ?
						EmptyBitmap.INSTANCE : new BaseBitmap(recordsToSort),
					alreadyRead,
					startIndex - toSkip
				);
			} finally {
				SortResolutionStrategies.report(queryContext, strategyTally);
				queryContext.returnBuffer(resolveBufferA);
				queryContext.returnBuffer(resolveBufferB);
			}
		} else {
			return sortingContext;
		}
	}

	/**
	 * Restores the min-heap property of the run heads below `slot`: the head there sinks until neither of its children
	 * precedes it. The heads of different runs never compare equal - the runs hold disjoint records, and equal values
	 * fall back to the primary keys - so the heap yields exactly the order of the merge.
	 *
	 * @param heap the run heads, a binary heap in `[0, size)`
	 * @param size the number of heads in the heap
	 * @param slot the slot whose head may violate the heap property
	 */
	private static void siftDown(@Nonnull SortedRecordsProviderBuffer[] heap, int size, int slot) {
		final SortedRecordsProviderBuffer sinking = heap[slot];
		int current = slot;
		int child = (current << 1) + 1;
		while (child < size) {
			final int right = child + 1;
			if (right < size && heap[right].compareTo(heap[child]) < 0) {
				child = right;
			}
			if (heap[child].compareTo(sinking) >= 0) {
				break;
			}
			heap[current] = heap[child];
			current = child;
			child = (current << 1) + 1;
		}
		heap[current] = sinking;
	}

	/**
	 * Emits the records of the one provider holding selected records straight from its mask, whose positions are
	 * already in the order of the values and then of the primary keys - no value is read. Honours the requested
	 * slice: the records before it are skipped (and reported), the records after it are left out.
	 *
	 * @param sortingContext         the sorting context of the call
	 * @param provider               the provider holding the selected records
	 * @param mask                   positions of the selected records in the provider
	 * @param notFound               the selected records no provider holds
	 * @param result                 the array the records are written to
	 * @param buffer                 scratch buffer for the mask batches
	 * @param toRead                 how many records the slice asks for
	 * @param skippedRecordsConsumer consumer of the skipped records, or `null`
	 * @return the context carrying the records no provider holds
	 */
	@Nonnull
	private static SortingContext emitSingleMask(
		@Nonnull SortingContext sortingContext,
		@Nonnull SortedRecordsProvider provider,
		@Nonnull PersistentRoaringBitmap mask,
		@Nonnull PersistentRoaringBitmap notFound,
		@Nonnull int[] result,
		@Nonnull int[] buffer,
		int toRead,
		@Nullable IntConsumer skippedRecordsConsumer
	) {
		final int startIndex = sortingContext.recomputedStartIndex();
		final int peak = sortingContext.peak();
		int alreadyRead = 0;
		int toSkip = startIndex;
		final RoaringBatchIterator maskIterator = mask.getBatchIterator();
		while ((toRead > alreadyRead || toSkip > 0) && maskIterator.hasNext()) {
			final int batchPeak = maskIterator.nextBatch(buffer);
			if (batchPeak == 0) {
				break;
			}
			for (int i = 0; i < batchPeak && (toRead > alreadyRead || toSkip > 0); i++) {
				final int recordId = provider.recordAt(buffer[i]);
				if (toSkip > 0) {
					toSkip--;
					if (skippedRecordsConsumer != null) {
						skippedRecordsConsumer.accept(recordId);
					}
				} else {
					result[peak + alreadyRead++] = recordId;
				}
			}
		}
		return sortingContext.createResultContext(
			notFound.isEmpty() ? EmptyBitmap.INSTANCE : new BaseBitmap(notFound),
			alreadyRead,
			startIndex - toSkip
		);
	}

	/**
	 * Represents a buffer for managing and extracting data from sorted record providers while applying a mask filter.
	 * This class implements the {@link Comparable} interface for comparing buffer instances based on their current comparable value
	 * and can be used in a priority queue to process sorted records efficiently.
	 *
	 * Additionally, implements {@link AutoCloseable} to manage the lifecycle of allocated resources such as borrowed buffers.
	 * When closed, the buffer resources are returned to their respective providers.
	 *
	 * Each instance of this buffer holds state information required for iterating and comparing sorted records, as
	 * well as applying masks that specify which records are to be included.
	 */
	private static class SortedRecordsProviderBuffer implements Comparable<SortedRecordsProviderBuffer>, AutoCloseable {
		/**
		 * The comparator used for sorting and comparing records in the buffer.
		 */
		@SuppressWarnings("rawtypes") private final Comparator comparator;
		/**
		 * Whether records with equal values are ordered by descending primary key.
		 */
		private final boolean primaryKeysDescending;
		/**
		 * An iterator over batches of integers in the mask bitmap.
		 * Used to access the indices of relevant sorted records.
		 */
		private final RoaringBatchIterator maskIterator;
		/**
		 * The provider whose sorted order this buffer walks; the record id at a mask position is read positionally via
		 * {@link SortedRecordsProvider#recordAt(int)} (tree-direct or array-backed, per the provider) rather than through
		 * a materialized `int[]`.
		 */
		private final SortedRecordsProvider sortedRecordsProvider;
		/**
		 * Seeker for extracting comparable values from the sorted records array at specific positions.
		 * Used to compare records during sorting.
		 */
		private final SortedComparableForwardSeeker sortedComparableForwardSeeker;
		/**
		 * The query execution context containing utilities for managing resources like buffers.
		 */
		private final QueryExecutionContext queryContext;
		/**
		 * A reusable buffer borrowed from the query context for storing intermediate values during operation.
		 */
		private final int[] buffer;
		/**
		 * The primary key of the current record being processed.
		 * Updates as the buffer fetches the next record index from the mask.
		 */
		private int primaryKey;
		/**
		 * The current index within the mask buffer that is being processed.
		 */
		private int maskBufferIndex = 0;
		/**
		 * The maximum valid index in the mask buffer for the current batch.
		 */
		private int maskBufferPeak = 0;
		/**
		 * A flag indicating whether this buffer contains valid data for processing.
		 */
		private boolean usable;

		/**
		 * The comparable value of the currently processed record.
		 * Used for sorting and comparisons.
		 */
		private Serializable valueToCompare;

		public SortedRecordsProviderBuffer(
			@SuppressWarnings("rawtypes") @Nonnull Comparator comparator,
			boolean primaryKeysDescending,
			@Nonnull SortedRecordsProvider sortedRecordsProvider,
			@Nonnull PersistentRoaringBitmap mask,
			@Nonnull QueryExecutionContext queryContext
		) {
			this.comparator = comparator;
			this.primaryKeysDescending = primaryKeysDescending;
			this.maskIterator = mask.getBatchIterator();
			this.sortedRecordsProvider = sortedRecordsProvider;
			this.sortedComparableForwardSeeker = sortedRecordsProvider.getSortedComparableForwardSeeker();
			this.sortedComparableForwardSeeker.reset();
			this.queryContext = queryContext;
			this.buffer = queryContext.borrowBuffer();
		}

		/**
		 * Advances to the next valid record in the buffer. It fetches data in batches if the current batch
		 * is exhausted and determines usability based on the availability of data. Updates the primary key
		 * and comparable value for the currently active record in the buffer.
		 *
		 * @return {@code true} if a new record is successfully fetched and is usable, {@code false} if
		 * there are no more records available in the buffer.
		 */
		public boolean fetchNext() {
			if (this.maskBufferIndex >= this.maskBufferPeak) {
				if (this.maskIterator.hasNext()) {
					this.maskBufferPeak = this.maskIterator.nextBatch(this.buffer);
					this.maskBufferIndex = 0;
					this.usable = this.maskBufferPeak > 0;
				} else {
					this.usable = false;
				}
			}
			if (this.usable) {
				final int position = this.buffer[this.maskBufferIndex++];
				this.primaryKey = this.sortedRecordsProvider.recordAt(position);
				this.valueToCompare = this.sortedComparableForwardSeeker.getValueToCompareOn(position);
				return true;
			} else {
				return false;
			}
		}

		/**
		 * Retrieves the primary key of the current record in the buffer.
		 * The method ensures that the buffer is in a usable state before accessing the primary key.
		 *
		 * @return the primary key of the current record in the buffer
		 */
		public int primaryKey() {
			assertUsable();
			return this.primaryKey;
		}

		@Override
		public int compareTo(@Nonnull SortedRecordsProviderBuffer o) {
			assertUsable();
			// this is ok, we'll always be comparing values of same type and the sorting will never modify contents
			// of the comparable value, we use non-final fields to make the algorithm faster
			//noinspection unchecked,CompareToUsesNonFinalVariable
			final int comparisonResult = this.comparator.compare(this.valueToCompare, o.valueToCompare);
			if (comparisonResult == 0) {
				// then compare primary keys in the direction of the ordering
				//noinspection CompareToUsesNonFinalVariable
				return this.primaryKeysDescending ?
					Integer.compare(o.primaryKey, this.primaryKey) : Integer.compare(this.primaryKey, o.primaryKey);
			} else {
				return comparisonResult;
			}
		}

		@Override
		public void close() {
			// this will prevent returning buffer twice when close method is called multiple times
			if (this.maskBufferPeak >= 0) {
				this.queryContext.returnBuffer(this.buffer);
				this.maskBufferPeak = -1;
			}
		}

		@SuppressWarnings("NonFinalFieldReferencedInHashCode")
		@Override
		public int hashCode() {
			int result = this.primaryKey;
			result = 31 * result + Objects.hashCode(this.valueToCompare);
			return result;
		}

		@SuppressWarnings("NonFinalFieldReferenceInEquals")
		@Override
		public final boolean equals(Object o) {
			if (!(o instanceof SortedRecordsProviderBuffer that)) return false;

			return this.primaryKey == that.primaryKey && Objects.equals(this.valueToCompare, that.valueToCompare);
		}

		@Override
		public String toString() {
			return this.usable ?
				"SortedRecordsProviderBuffer: " + this.valueToCompare + " [" + this.primaryKey + "]" :
				"SortedRecordsProviderBuffer: ❌";
		}

		private void assertUsable() {
			Assert.isPremiseValid(this.usable, "Cannot compare unusable buffer");
		}
	}

}
