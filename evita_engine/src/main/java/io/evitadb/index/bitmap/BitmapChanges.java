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

package io.evitadb.index.bitmap;

import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * This class/objects holds transactional changes upon read-only bitmap implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class BitmapChanges {
	/**
	 * Unmodifiable underlying bitmap.
	 */
	private final RoaringBitmap originalBitmap;
	/**
	 * IntegerBitmap of records ids added to the bitmap.
	 */
	private final RoaringBitmap insertions = new RoaringBitmap();
	/**
	 * IntegerBitmap of record ids removed from the bitmap.
	 */
	private final RoaringBitmap removals = new RoaringBitmap();
	/**
	 * Temporary intermediate result of the last {@link #getMergedBitmap()} operation. Nullified immediately with next
	 * change.
	 */
	@Nullable private RoaringBitmap memoizedMergedBitmap;

	BitmapChanges(RoaringBitmap original) {
		this.originalBitmap = original;
	}

	/**
	 * Returns true if bitmap with applied changes is empty.
	 */
	public boolean isEmpty() {
		return (this.originalBitmap.isEmpty() || RoaringBitmap.andNot(this.originalBitmap, this.removals).isEmpty()) && this.insertions.isEmpty();
	}

	/**
	 * Returns true if passed recordId is part of the modified delegate bitmap. I.e. whether it was newly inserted or
	 * contained in original bitmap and not removed so far.
	 */
	boolean contains(int recordId) {
		final boolean originalContainsRecord = this.originalBitmap.contains(recordId);
		if (originalContainsRecord) {
			return !this.removals.contains(recordId);
		} else {
			return this.insertions.contains(recordId);
		}
	}

	/**
	 * Adds new recordId to the bitmap (only when not already present).
	 * This operation also nullifies previous record id removal (if any).
	 */
	boolean addRecordId(int recordId) {
		// remove removal order for the record id if exists
		final boolean removalRemoved = this.removals.checkedRemove(recordId);
		// add insertion order for the record id
		if (!this.originalBitmap.contains(recordId) && this.insertions.checkedAdd(recordId)) {
			// nullify memoized result that becomes obsolete by this operation
			this.memoizedMergedBitmap = null;
			return true;
		} else {
			// nullify memoized result that becomes obsolete by this operation
			this.memoizedMergedBitmap = null;
			return removalRemoved;
		}
	}

	/**
	 * Removes recordId from the bitmap (only when present).
	 * This operation also nullifies previous record id insertion (if any).
	 */
	boolean removeRecordId(int recordId) {
		final boolean addedRemovalOrder = this.originalBitmap.contains(recordId) && this.removals.checkedAdd(recordId);
		final boolean removedInsertionOrder = this.insertions.checkedRemove(recordId);
		if (addedRemovalOrder || removedInsertionOrder) {
			// nullify memoized result that becomes obsolete by this operation
			this.memoizedMergedBitmap = null;
			return true;
		} else {
			return false;
		}
	}

	/**
	 * This method computes new bitmap from the immutable original bitmap and the set of insertions / removals made upon
	 * it.
	 */
	@Nonnull
	RoaringBitmap getMergedBitmap() {
		if (this.insertions.isEmpty() && this.removals.isEmpty()) {
			// if there are no insertions / removals - return the original
			return this.originalBitmap;
		} else {
			// compute results only when we can't reuse previous computation
			if (this.memoizedMergedBitmap == null) {
				// memoize costly computation and return
				final RoaringBitmap mergedBitmap = RoaringBitmap.andNot(
					RoaringBitmap.or(this.originalBitmap, this.insertions),
					this.removals
				);
				mergedBitmap.runOptimize();
				this.memoizedMergedBitmap = mergedBitmap;
			}

			return this.memoizedMergedBitmap;
		}
	}

	/**
	 * Computes length of the bitmap with all requested changes applied.
	 */
	int getMergedLength() {
		return this.originalBitmap.getCardinality() - this.removals.getCardinality() + this.insertions.getCardinality();
	}

}
