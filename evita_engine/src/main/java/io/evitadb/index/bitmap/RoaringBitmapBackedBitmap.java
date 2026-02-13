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

import io.evitadb.utils.ArrayUtils;
import org.roaringbitmap.ImmutableBitmapDataProvider;
import org.roaringbitmap.PeekableIntIterator;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.PrimitiveIterator;

/**
 * Implementations of this interface are backed with some form of {@link RoaringBitmap} and can produce it when asked.
 * This interface allows to optimize Immutable -> Mutable -> Immutable versions of RoaringBitmap roundtrips by allowing
 * to access internal representation of the RoaringBitmap.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface RoaringBitmapBackedBitmap extends Bitmap {

	/**
	 * Creates {@link RoaringBitmap} from the array of integers. Providing a sorted array in
	 * ascending order is preferred for performance, but unsorted input is also handled correctly.
	 */
	@Nonnull
	static RoaringBitmap fromArray(@Nonnull int... array) {
		if (ArrayUtils.isEmpty(array)) {
			return new RoaringBitmap();
		} else {
			final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapWriter
				.writer()
				.constantMemory()
				.runCompress(false)
				.get();
			writer.addMany(array);
			return writer.get();
		}
	}

	/**
	 * Returns {@link RoaringBitmap} from any bitmap in the argument. For
	 * {@link RoaringBitmapBackedBitmap} implementations, returns the internal bitmap reference
	 * directly (not a copy). For other {@link Bitmap} implementations, creates a new
	 * {@link RoaringBitmap} from the bitmap's array.
	 */
	@Nonnull
	static RoaringBitmap getRoaringBitmap(@Nonnull Bitmap bitmap) {
		if (bitmap instanceof RoaringBitmapBackedBitmap) {
			return ((RoaringBitmapBackedBitmap) bitmap).getRoaringBitmap();
		} else {
			return fromArray(bitmap.getArray());
		}
	}

	/**
	 * Returns a cloned {@link RoaringBitmap} from any bitmap in the argument. For
	 * {@link RoaringBitmapBackedBitmap} implementations, clones the internal bitmap. For other
	 * {@link Bitmap} implementations, creates a new {@link RoaringBitmap} from the bitmap's array.
	 * The returned bitmap is always safe to modify without affecting the original.
	 */
	@Nonnull
	static RoaringBitmap getRoaringBitmapClone(@Nonnull Bitmap bitmap) {
		if (bitmap instanceof RoaringBitmapBackedBitmap) {
			return (((RoaringBitmapBackedBitmap) bitmap).getRoaringBitmap()).clone();
		} else {
			return fromArray(bitmap.getArray());
		}
	}

	/**
	 * Returns index of the record inside {@link RoaringBitmap}. The method follows the same
	 * contract as {@link java.util.Arrays#binarySearch(int[], int)} - when the record id is found,
	 * returns its zero-based index; when not found, returns `-(insertion point) - 1` where
	 * the insertion point is the index at which the record id would be inserted.
	 */
	static int indexOf(@Nonnull ImmutableBitmapDataProvider roaringBitmap, int recordId) {
		if (roaringBitmap.isEmpty()) {
			return -1;
		}
		final int rank = roaringBitmap.rank(recordId);
		final int index = rank - 1;
		final int nextRecordId = index >= 0 ? roaringBitmap.select(index) : -1;
		return nextRecordId == recordId ? index : -1 * (rank + 1);
	}

	/**
	 * Method creates {@link RoaringBitmap} builder that is optimized for fast and memory efficient bitmap construction.
	 */
	@Nonnull
	static RoaringBitmapWriter<RoaringBitmap> buildWriter() {
		return RoaringBitmapWriter
			.writer()
			.constantMemory()
			.runCompress(false)
			.get();
	}

	/**
	 * Computes {@link Bitmap} by applying conjunction (AND / intersection) on all passed bitmaps
	 * in an optimal way. Returns {@link EmptyBitmap#INSTANCE} when the input array is empty or when
	 * any of the bitmaps is empty (since the intersection must be empty). Returns a {@link BaseBitmap}
	 * wrapping the single element when the array has exactly one bitmap. Bitmaps containing negative
	 * record ids are handled separately due to {@link RoaringBitmap} treating integers as unsigned.
	 */
	@Nonnull
	static Bitmap and(@Nonnull RoaringBitmap[] theBitmaps) {
		if (theBitmaps.length == 0) {
			return EmptyBitmap.INSTANCE;
		}
		// early exit if any bitmap is empty — intersection must be empty
		for (final RoaringBitmap theBitmap : theBitmaps) {
			if (theBitmap.isEmpty()) {
				return EmptyBitmap.INSTANCE;
			}
		}
		if (theBitmaps.length == 1) {
			return new BaseBitmap(theBitmaps[0]);
		} else {
			long min = Integer.MAX_VALUE;
			long max = 0L;
			final List<RoaringBitmap> roaringBitmaps = new ArrayList<>(theBitmaps.length);
			final List<RoaringBitmap> negativeRoaringBitmaps = new ArrayList<>(theBitmaps.length);
			for (final RoaringBitmap theBitmap : theBitmaps) {
				final int first = theBitmap.first();
				final int last = theBitmap.last();
				final int leftBound = Math.min(first, last);
				final int rightBound = Math.max(first, last);
				if (leftBound >= 0) {
					min = Math.min(min, leftBound);
					max = Math.max(max, rightBound);
					roaringBitmaps.add(theBitmap);
				} else {
					negativeRoaringBitmaps.add(theBitmap);
				}
			}

			RoaringBitmap intermediateResult;
			if (roaringBitmaps.isEmpty()) {
				intermediateResult = negativeRoaringBitmaps.get(0);
			} else if (roaringBitmaps.size() == 1) {
				intermediateResult = roaringBitmaps.get(0);
			} else {
				intermediateResult = RoaringBitmap.and(roaringBitmaps.iterator(), min, max + 1);
			}
			for (final RoaringBitmap theBitmap : negativeRoaringBitmaps) {
				intermediateResult = RoaringBitmap.and(theBitmap, intermediateResult);
			}
			final Bitmap theResult = new BaseBitmap(intermediateResult);
			return theResult;
		}
	}

	/**
	 * Returns the internal {@link RoaringBitmap} instance backing this bitmap. The returned bitmap
	 * is **not** a copy - modifications to it will affect this bitmap directly. Use
	 * {@link #getRoaringBitmapClone(Bitmap)} when an independent copy is needed.
	 */
	@Nonnull
	RoaringBitmap getRoaringBitmap();

	/**
	 * Thin adapter that wraps {@link PeekableIntIterator} as {@link PrimitiveIterator.OfInt}
	 * without the allocation overhead of `RoaringBitmap.stream().iterator()`.
	 */
	class RoaringIntIteratorAdapter implements PrimitiveIterator.OfInt {
		private final PeekableIntIterator delegate;

		RoaringIntIteratorAdapter(@Nonnull PeekableIntIterator delegate) {
			this.delegate = delegate;
		}

		@Override
		public boolean hasNext() {
			return this.delegate.hasNext();
		}

		@Override
		public int nextInt() {
			return this.delegate.next();
		}
	}
}
