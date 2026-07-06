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

package io.evitadb.index.attribute;

import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.PositionResolution;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedComparableForwardSeeker;
import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.index.array.TransactionalUnorderedIntArray;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.roaringbitmap.BatchIterator;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Presorted array supplier. Allows really quickly provide information about record id at certain "presorted" position
 * and relatively quickly (much faster than binary search O(log n)) compute position of record with passed id.
 *
 * The supplier comes in two flavours:
 *
 * - **array-backed** (the legacy constructor): all four artifacts (record-id order, record positions, the record-id
 *   bitmap and the value seeker) are materialized up-front and stored. Used for structures without a live positional
 *   tree (e.g. the chain index) and for the large-selection array path.
 * - **tree-backed** (the tree constructor): the record-id order / positions / bitmap are NOT materialized; instead the
 *   positional {@link SortedRecordsProvider} operations ({@link #recordAt(int)}, {@link #positionOf(int)},
 *   {@link #resolvePositions}) resolve straight from the live {@link TransactionalUnorderedIntArray} in `O(log N)`,
 *   avoiding the per-query materialization and its resident memory. The materialized arrays are still exposed lazily
 *   (via the supplied warm-memo accessors) for the array fallback taken on large selections.
 */
public class SortedRecordsSupplier implements SortedRecordsProvider, Serializable {
	@Serial private static final long serialVersionUID = 6606884166778706442L;

	/**
	 * Selectivity threshold for the tree-vs-array path pick in {@link #resolvePositions}: the tree path (per-record
	 * `O(log N)` look-ups, no materialization) is taken while the selected count `K <= N / DIVISOR`; above it the array
	 * merge-walk over the (warm) materialized arrays wins. `SortIndexTreeDirectSortBenchmark` (N=100k) measures the
	 * crossover at roughly `K ~ N/78` (tree ~11x faster at K=100, ~1.4x at K=1000, losing by K~5000); `64` keeps the
	 * tree path within its clear-win band with margin. Constant for now — a future revision may expose it as
	 * a configuration knob, or make it adaptive in `N` since the true crossover scales with `N / log N`.
	 */
	private static final int TREE_PATH_SELECTIVITY_DIVISOR = 64;

	@Getter private final long transactionalId;
	/**
	 * The live positional tree backing this supplier, or `null` for a purely array-backed supplier. When present, the
	 * positional operations resolve straight from it and the materialized arrays are only built on the array fallback.
	 */
	@Nullable private final TransactionalUnorderedIntArray sortedRecords;
	/**
	 * Whether this supplier serves the descending order. Descending positions mirror the ascending tree via
	 * `recordCount - 1 - ascendingPosition`, so no reversed copy of the tree is needed.
	 */
	private final boolean descending;
	/**
	 * Total number of records in the sorted order (`sortedRecordIds.length` for an array-backed supplier).
	 * Captured at construction and must equal the live `sortedRecords` length for the query's (immutable)
	 * snapshot: the descending mirror `recordCount - 1 - position` and the mask `lastPosition` offset rely on it.
	 */
	private final int recordCount;
	/**
	 * Record ids in sorted order. Eagerly set for an array-backed supplier; lazily materialized via
	 * {@link #sortedRecordIdsSupplier} on first access for a tree-backed supplier.
	 */
	@Nullable private int[] sortedRecordIds;
	/**
	 * Position of each record from {@link #getAllRecords()} within {@link #getSortedRecordIds()}. Eager (array-backed) or
	 * lazy (tree-backed).
	 */
	@Nullable private int[] recordPositions;
	/**
	 * Bitmap of all record ids in ascending id order. Eager (array-backed) or lazy (tree-backed).
	 */
	@Nullable private Bitmap allRecords;
	/**
	 * Lazy accessor of the materialized record-id order (warm-memoized upstream), or `null` for an array-backed supplier.
	 */
	@Nullable private final Supplier<int[]> sortedRecordIdsSupplier;
	/**
	 * Lazy accessor of the materialized record positions, or `null` for an array-backed supplier.
	 */
	@Nullable private final Supplier<int[]> recordPositionsSupplier;
	/**
	 * Lazy accessor of the materialized record-id bitmap, or `null` for an array-backed supplier.
	 */
	@Nullable private final Supplier<Bitmap> allRecordsSupplier;
	/**
	 * The value seeker over the sorted order.
	 */
	@Getter @Nonnull private final SortedComparableForwardSeeker sortedComparableForwardSeeker;

	/**
	 * Creates an array-backed supplier from already-materialized artifacts (the legacy behaviour).
	 *
	 * @param transactionalId                the identity of the backing structure
	 * @param sortedRecordIds                record ids in sorted order
	 * @param recordPositions                position of each id-ordered record within `sortedRecordIds`
	 * @param allRecords                     bitmap of all record ids in ascending id order
	 * @param sortedComparableForwardSeeker  the value seeker over the sorted order
	 */
	public SortedRecordsSupplier(
		long transactionalId,
		@Nonnull int[] sortedRecordIds,
		@Nonnull int[] recordPositions,
		@Nonnull Bitmap allRecords,
		@Nonnull SortedComparableForwardSeeker sortedComparableForwardSeeker
	) {
		this.transactionalId = transactionalId;
		this.sortedRecordIds = sortedRecordIds;
		this.recordPositions = recordPositions;
		this.allRecords = allRecords;
		this.sortedComparableForwardSeeker = sortedComparableForwardSeeker;
		this.sortedRecords = null;
		this.descending = false;
		this.recordCount = sortedRecordIds.length;
		this.sortedRecordIdsSupplier = null;
		this.recordPositionsSupplier = null;
		this.allRecordsSupplier = null;
	}

	/**
	 * Creates a tree-backed supplier that resolves positions straight from the live positional tree, materializing the
	 * arrays lazily (via the supplied warm-memo accessors) only when the array fallback is taken.
	 *
	 * The lazily-materialized arrays are non-`final` and assigned without synchronization, so a tree-backed
	 * supplier instance must be consumed by a single thread (the per-query build model guarantees this).
	 *
	 * @param transactionalId                the identity of the backing structure
	 * @param sortedRecords                  the live positional tree (ascending logical order)
	 * @param descending                     whether this supplier serves the descending order
	 * @param recordCount                    total number of records in the sorted order
	 * @param sortedRecordIdsSupplier        lazy accessor of the materialized record-id order (for the array fallback)
	 * @param recordPositionsSupplier        lazy accessor of the materialized record positions (for the array fallback)
	 * @param allRecordsSupplier             lazy accessor of the materialized record-id bitmap (for the array fallback)
	 * @param sortedComparableForwardSeeker  the value seeker over the sorted order
	 */
	public SortedRecordsSupplier(
		long transactionalId,
		@Nonnull TransactionalUnorderedIntArray sortedRecords,
		boolean descending,
		int recordCount,
		@Nonnull Supplier<int[]> sortedRecordIdsSupplier,
		@Nonnull Supplier<int[]> recordPositionsSupplier,
		@Nonnull Supplier<Bitmap> allRecordsSupplier,
		@Nonnull SortedComparableForwardSeeker sortedComparableForwardSeeker
	) {
		this.transactionalId = transactionalId;
		this.sortedRecords = sortedRecords;
		this.descending = descending;
		this.recordCount = recordCount;
		this.sortedRecordIdsSupplier = sortedRecordIdsSupplier;
		this.recordPositionsSupplier = recordPositionsSupplier;
		this.allRecordsSupplier = allRecordsSupplier;
		this.sortedComparableForwardSeeker = sortedComparableForwardSeeker;
		this.sortedRecordIds = null;
		this.recordPositions = null;
		this.allRecords = null;
	}

	@Nonnull
	@Override
	public int[] getSortedRecordIds() {
		if (this.sortedRecordIds == null) {
			this.sortedRecordIds = Objects.requireNonNull(this.sortedRecordIdsSupplier).get();
		}
		return this.sortedRecordIds;
	}

	@Nonnull
	@Override
	public int[] getRecordPositions() {
		if (this.recordPositions == null) {
			this.recordPositions = Objects.requireNonNull(this.recordPositionsSupplier).get();
		}
		return this.recordPositions;
	}

	@Nonnull
	@Override
	public Bitmap getAllRecords() {
		if (this.allRecords == null) {
			this.allRecords = Objects.requireNonNull(this.allRecordsSupplier).get();
		}
		return this.allRecords;
	}

	@Override
	public int getRecordCount() {
		return this.recordCount;
	}

	@Override
	public int recordAt(int position) {
		if (this.sortedRecords == null) {
			return SortedRecordsProvider.super.recordAt(position);
		}
		// descending position p maps to ascending logical position recordCount - 1 - p (mirror, no reversed copy)
		return this.sortedRecords.get(this.descending ? this.recordCount - 1 - position : position);
	}

	@Override
	public int positionOf(int recordId) {
		if (this.sortedRecords == null) {
			return SortedRecordsProvider.super.positionOf(recordId);
		}
		final int ascendingPosition = this.sortedRecords.indexOf(recordId);
		if (ascendingPosition < 0) {
			return POSITION_NOT_FOUND;
		}
		return this.descending ? this.recordCount - 1 - ascendingPosition : ascendingPosition;
	}

	@Nonnull
	@Override
	public PositionResolution resolvePositions(
		@Nonnull PersistentRoaringBitmap selectedRecordIds,
		int selectedRecordCount,
		@Nonnull int[] bufferA,
		@Nonnull int[] bufferB
	) {
		// array-backed supplier, or a selection large enough that the O(N) warm-array walk beats K * O(log N) tree
		// look-ups -> fall back to the array merge-walk (which materializes / reuses the warm arrays lazily)
		if (this.sortedRecords == null
			|| (long) selectedRecordCount * TREE_PATH_SELECTIVITY_DIVISOR > this.recordCount) {
			return SortedRecordsProvider.super.resolvePositions(selectedRecordIds, selectedRecordCount, bufferA, bufferB);
		}
		// tree path: resolve each selected record's position straight from the tree, no array materialization
		final RoaringBitmapWriter<PersistentRoaringBitmap> mask = RoaringBitmapBackedBitmap.buildWriter();
		final RoaringBitmapWriter<PersistentRoaringBitmap> notFound = RoaringBitmapBackedBitmap.buildWriter();
		final int lastPosition = this.recordCount - 1;
		int notFoundCount = 0;
		final BatchIterator selectedIt = selectedRecordIds.getBatchIterator();
		int read;
		while ((read = selectedIt.nextBatch(bufferA)) > 0) {
			for (int i = 0; i < read; i++) {
				final int recordId = bufferA[i];
				final int ascendingPosition = this.sortedRecords.indexOf(recordId);
				if (ascendingPosition < 0) {
					notFound.add(recordId);
					notFoundCount++;
				} else {
					// the mask holds positions in emit order; a RoaringBitmap keeps them ascending regardless of add order
					mask.add(this.descending ? lastPosition - ascendingPosition : ascendingPosition);
				}
			}
		}
		return new PositionResolution(mask.get(), notFound.get(), notFoundCount);
	}

}
