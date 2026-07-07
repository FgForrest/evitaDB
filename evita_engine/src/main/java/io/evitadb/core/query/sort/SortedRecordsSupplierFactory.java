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

package io.evitadb.core.query.sort;

import io.evitadb.api.query.require.DebugMode;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.BatchIterator;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;

/**
 * Provides access to presorted arrays of records according to certain attribute or other data value.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
public interface SortedRecordsSupplierFactory {

	/**
	 * Creates sorted records supplier for ascending order.
	 */
	@Nonnull
	SortedRecordsProvider getAscendingOrderRecordsSupplier();

	/**
	 * Creates sorted records supplier for descending order.
	 */
	@Nonnull
	SortedRecordsProvider getDescendingOrderRecordsSupplier();

	/**
	 * Provides access to sorted records array.
	 */
	interface SortedRecordsProvider {

		/**
		 * Empty sorted records provider behaves as if the sort index was empty.
		 */
		SortedRecordsProvider EMPTY = new SortedRecordsProvider() {

			@Override
			public int getRecordCount() {
				return 0;
			}

			@Nonnull
			@Override
			public Bitmap getAllRecords() {
				return EmptyBitmap.INSTANCE;
			}

			@Nonnull
			@Override
			public int[] getRecordPositions() {
				return ArrayUtils.EMPTY_INT_ARRAY;
			}

			@Nonnull
			@Override
			public int[] getSortedRecordIds() {
				return ArrayUtils.EMPTY_INT_ARRAY;
			}

			@Nonnull
			@Override
			public SortedComparableForwardSeeker getSortedComparableForwardSeeker() {
				return SortedComparableForwardSeeker.EMPTY;
			}
		};

		/**
		 * Returns count of all records of the supplier.
		 */
		int getRecordCount();

		/**
		 * Returns bitmap of all record ids present in the sort supplier in distinct ascending order.
		 * Example: 1, 3, 4, 6, 8, 12
		 */
		@Nonnull
		Bitmap getAllRecords();

		/**
		 * Contains index of record from {@link #getAllRecords()} in {@link #getSortedRecordIds()} array.
		 * Example: 1, 4, 5, 0, 3, 2
		 */
		@Nonnull
		int[] getRecordPositions();

		/**
		 * Returns array of records in "sorted" order - i.e. order that conforms to the referring {@link Comparable} order.
		 * Example: 6, 1, 12, 8, 3, 4
		 */
		@Nonnull
		int[] getSortedRecordIds();

		/**
		 * Returns the {@link SortedComparableForwardSeeker} that can be used to retrieve the sorted comparable value
		 * for a given position in the sorted records.
		 *
		 * @return the {@link SortedComparableForwardSeeker} instance.
		 */
		@Nonnull
		SortedComparableForwardSeeker getSortedComparableForwardSeeker();

		/**
		 * Sentinel returned by {@link #positionOf(int)} when the record id is not present in this provider's sorted
		 * order. Deliberately `-1` (not {@link Integer#MIN_VALUE}) so it stays distinct from the position-0 remapping
		 * used by the predecessor comparators' position caches, while still being an impossible real position.
		 */
		int POSITION_NOT_FOUND = -1;

		/**
		 * Returns the record id occupying the given position in this provider's sorted order. The default is a direct
		 * index into {@link #getSortedRecordIds()}; a tree-backed provider overrides this to resolve the record straight
		 * from its ordered structure, avoiding materialization of the flattened array.
		 *
		 * @param position the position in `[0, getRecordCount())`
		 * @return the record id at the given sorted position
		 */
		default int recordAt(int position) {
			return getSortedRecordIds()[position];
		}

		/**
		 * Returns the sorted position of the given record id, or {@link #POSITION_NOT_FOUND} when the record is absent
		 * from this provider. The default composes the two materialized arrays ({@link #getAllRecords()} index →
		 * {@link #getRecordPositions()}); a tree-backed provider overrides this with a single `O(log N)` lookup that
		 * touches no materialized array.
		 *
		 * @param recordId the record id whose sorted position is requested
		 * @return the sorted position, or {@link #POSITION_NOT_FOUND} when the record is not in this provider
		 */
		default int positionOf(int recordId) {
			final int indexInAllRecords = getAllRecords().indexOf(recordId);
			return indexInAllRecords < 0 ? POSITION_NOT_FOUND : getRecordPositions()[indexInAllRecords];
		}

		/**
		 * Resolves, for the set of `selectedRecordIds`, the mask of their positions in this provider's sorted order and
		 * the subset that this provider does not contain (handed to the next provider / sorter). This is the positional
		 * core the merged sorters consume in place of a raw scan of {@link #getSortedRecordIds()}.
		 *
		 * This convenience overload leaves the resolution family to the cost-based selector; see
		 * {@link #resolvePositions(PersistentRoaringBitmap, int, int[], int[], ForcedSortResolution)} for the debug-only
		 * override that pins it.
		 *
		 * @param selectedRecordIds   the record ids to locate, in ascending id order
		 * @param selectedRecordCount the count of selected record ids (walk terminates once all are matched)
		 * @param bufferA             scratch buffer for batch iteration (array default only)
		 * @param bufferB             scratch buffer for batch iteration (array default only)
		 * @return the position mask, the not-found record ids, their count and the strategy actually used
		 */
		@Nonnull
		default PositionResolution resolvePositions(
			@Nonnull PersistentRoaringBitmap selectedRecordIds,
			int selectedRecordCount,
			@Nonnull int[] bufferA,
			@Nonnull int[] bufferB
		) {
			return resolvePositions(selectedRecordIds, selectedRecordCount, bufferA, bufferB, null);
		}

		/**
		 * Resolves, for the set of `selectedRecordIds`, the mask of their positions in this provider's sorted order and
		 * the subset that this provider does not contain (handed to the next provider / sorter).
		 *
		 * The default is the array merge-walk (`O(N + K)`): it batch-iterates {@link #getAllRecords()} against the
		 * selected ids and maps every match through {@link #getRecordPositions()}. A tree-backed provider overrides this
		 * with a per-record `O(K log N)` lookup that never materializes the position array. Both fill an ascending mask
		 * bitmap of positions (so the caller can emit in sorted order) and a bitmap of the not-found record ids.
		 *
		 * The two scratch buffers are supplied by the caller (typically borrowed from the query buffer pool) purely as an
		 * allocation aid for the array walk; the tree-backed override ignores them.
		 *
		 * `forcedResolution` is a debug-only override (mapped from {@link DebugMode}) that
		 * pins the resolution family instead of the cost-based choice; a purely array-backed provider has no tree, so this
		 * default always performs the array merge-walk and reports {@link SortResolutionStrategy#ARRAY_MERGE_WALK}
		 * regardless of the override.
		 *
		 * @param selectedRecordIds   the record ids to locate, in ascending id order
		 * @param selectedRecordCount the count of selected record ids (walk terminates once all are matched)
		 * @param bufferA             scratch buffer for batch iteration (array default only)
		 * @param bufferB             scratch buffer for batch iteration (array default only)
		 * @param forcedResolution    debug override pinning the resolution family, or `null` for cost-based selection
		 * @return the position mask, the not-found record ids, their count and the strategy actually used
		 */
		@Nonnull
		default PositionResolution resolvePositions(
			@Nonnull PersistentRoaringBitmap selectedRecordIds,
			int selectedRecordCount,
			@Nonnull int[] bufferA,
			@Nonnull int[] bufferB,
			@Nullable ForcedSortResolution forcedResolution
		) {
			final Bitmap unsortedRecordIds = getAllRecords();
			final int[] recordPositions = getRecordPositions();

			final RoaringBitmapWriter<PersistentRoaringBitmap> mask = RoaringBitmapBackedBitmap.buildWriter();
			final RoaringBitmapWriter<PersistentRoaringBitmap> notFound = RoaringBitmapBackedBitmap.buildWriter();

			final BatchIterator unsortedRecordIdsIt = RoaringBitmapBackedBitmap.getRoaringBitmap(unsortedRecordIds).getBatchIterator();
			final BatchIterator selectedRecordIdsIt = selectedRecordIds.getBatchIterator();

			int matchesFound = 0;
			int notFoundCount = 0;
			int unsortedRecordsPeak = -1;
			int unsortedRecordsRead = -1;
			int selectedRecordsPeak = -1;
			int selectedRecordsRead = -1;
			int unsortedRecordsAcc = 1;
			do {
				if (unsortedRecordsPeak == unsortedRecordsRead && unsortedRecordsRead != 0) {
					unsortedRecordsAcc += unsortedRecordsRead;
					unsortedRecordsRead = unsortedRecordIdsIt.nextBatch(bufferA);
					unsortedRecordsPeak = 0;
				}
				if (selectedRecordsPeak == selectedRecordsRead) {
					selectedRecordsRead = selectedRecordIdsIt.nextBatch(bufferB);
					selectedRecordsPeak = 0;
				}
				if (unsortedRecordsPeak < unsortedRecordsRead && bufferA[unsortedRecordsPeak] == bufferB[selectedRecordsPeak]) {
					mask.add(recordPositions[unsortedRecordsAcc + unsortedRecordsPeak]);
					matchesFound++;
					selectedRecordsPeak++;
					unsortedRecordsPeak++;
				} else if (selectedRecordsPeak < selectedRecordsRead && (unsortedRecordsPeak >= unsortedRecordsRead || bufferA[unsortedRecordsPeak] > bufferB[selectedRecordsPeak])) {
					notFound.add(bufferB[selectedRecordsPeak]);
					notFoundCount++;
					selectedRecordsPeak++;
				} else {
					unsortedRecordsPeak++;
				}
			} while (matchesFound < selectedRecordCount && selectedRecordsRead > 0);

			return new PositionResolution(
				mask.get(), notFound.get(), notFoundCount, SortResolutionStrategy.ARRAY_MERGE_WALK
			);
		}

	}

	/**
	 * Debug-only override that pins {@link SortedRecordsProvider#resolvePositions} onto a specific resolution family,
	 * bypassing the cost-based (selectivity) choice. Mapped at the sorter from the
	 * {@link DebugMode#PREFER_TREE_SORT} /
	 * {@link DebugMode#PREFER_PRESORT_ARRAYS} overrides; `null` means cost-based selection.
	 * A purely array-backed provider has no tree, so {@link #TREE} degrades to the array walk for it.
	 */
	enum ForcedSortResolution {

		/**
		 * Force the tree path (sparse probe or dense walk) even when warm materialized arrays would otherwise be
		 * preferred by the cost-based selector.
		 */
		TREE,
		/**
		 * Force the array merge-walk, materializing the arrays on a cold tree-backed provider if necessary, even when the
		 * selection is sparse enough that the tree probe would otherwise be preferred.
		 */
		ARRAY

	}

	/**
	 * The resolution family actually used by {@link SortedRecordsProvider#resolvePositions}, surfaced through
	 * {@link PositionResolution#strategy()} so the sorters can record it into query telemetry.
	 */
	enum SortResolutionStrategy {

		/**
		 * Array merge-walk over the materialized arrays - used by a purely array-backed provider, or by a tree-backed
		 * provider for a dense selection once its arrays are warm (or when {@link ForcedSortResolution#ARRAY} is set).
		 */
		ARRAY_MERGE_WALK,
		/**
		 * Per-record `O(log N)` tree probe used for a sparse selection (`K <= N / divisor`), materializing nothing.
		 */
		TREE_SPARSE_PROBE,
		/**
		 * Single `O(N)` tree cursor walk used for a dense selection on a cold tree-backed provider, materializing neither
		 * the `int[N]` order / position arrays nor the id-order bitmap.
		 */
		TREE_DENSE_WALK

	}

	/**
	 * Result of {@link SortedRecordsProvider#resolvePositions}: the ascending mask of matched positions in the sorted
	 * order, plus the record ids that the provider did not contain (and their count) for hand-off to the next provider.
	 *
	 * @param mask                 bitmap of positions (into the sorted order) of the matched record ids, ascending
	 * @param notFoundRecords      bitmap of the selected record ids absent from this provider
	 * @param notFoundRecordsCount cardinality of `notFoundRecords`
	 * @param strategy             the resolution family actually used, for query telemetry
	 */
	record PositionResolution(
		@Nonnull PersistentRoaringBitmap mask,
		@Nonnull PersistentRoaringBitmap notFoundRecords,
		int notFoundRecordsCount,
		@Nonnull SortResolutionStrategy strategy
	) {
	}

	/**
	 * Interface representing a forward seeker specifically for sorted collections of comparable records.
	 * Allows retrieval of a comparable value at a specific position in a sorted collection.
	 * The returned value should be consistent with the defined sorting order of the underlying records.
	 *
	 * Forward seeker is a design pattern that allows for efficient traversal of a collection in a forward direction.
	 */
	interface SortedComparableForwardSeeker {

		/**
		 * Empty sorted comparable forward seeker behaves as if the sort index was empty.
		 */
		SortedComparableForwardSeeker EMPTY = new SortedComparableForwardSeeker() {

			@Nonnull
			@Override
			public Serializable getValueToCompareOn(int position) throws ArrayIndexOutOfBoundsException {
				throw new ArrayIndexOutOfBoundsException("No comparable value available for the given position.");
			}
		};

		/**
		 * Resets the forward seeker to its initial state, allowing it to be reused for a new traversal.
		 */
		default void reset() {
			// No-op by default, can be overridden by subclasses if needed.
		}

		/**
		 * Retrieves a comparable value at the specified position within the sorted collection.
		 *
		 * @param position The position within the sorted collection from which the comparable
		 *                 value will be retrieved. Must be a valid index within the collection.
		 * @return The comparable value located at the specified position in the sorted collection.
		 * @throws ArrayIndexOutOfBoundsException If the provided position exceeds the bounds of the collection.
		 */
		@Nonnull
		Serializable getValueToCompareOn(int position)
			throws ArrayIndexOutOfBoundsException;
	}


}
