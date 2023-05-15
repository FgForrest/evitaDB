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

package io.evitadb.index.bitmap;

import io.evitadb.utils.ArrayUtils;
import org.roaringbitmap.ImmutableBitmapDataProvider;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;
import org.roaringbitmap.buffer.MutableRoaringBitmap;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementations of this interface are backed with some form of {@link RoaringBitmap} and can produce it when asked.
 * This interface allows to optimize Immutable -> Mutable -> Immutable versions of RoaringBitmap roundtrips by allowing
 * to access internal representation of the RoaringBitmap.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface RoaringBitmapBackedBitmap extends Bitmap {

	/**
	 * Creates {@link MutableRoaringBitmap} from the array of integers.
	 * Array is expected to be sorted in ascending order!
	 */
	static RoaringBitmap fromArray(int... array) {
		if (ArrayUtils.isEmpty(array)) {
			return new RoaringBitmap();
		} else {
			final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapWriter
				.writer()
				.constantMemory()
				.expectedRange(array[0], array[array.length - 1])
				.runCompress(false)
				.get();
			writer.addMany(array);
			return writer.get();
		}
	}

	/**
	 * Returns {@link MutableRoaringBitmap} from any bitmap in the argument.
	 */
	static RoaringBitmap getRoaringBitmap(Bitmap bitmap) {
		if (bitmap instanceof RoaringBitmapBackedBitmap) {
			return ((RoaringBitmapBackedBitmap) bitmap).getRoaringBitmap();
		} else {
			return fromArray(bitmap.getArray());
		}
	}

	/**
	 * Returns index of the record inside RoaringBitMap - method behaves same as {@link java.util.Arrays#binarySearch(int[], int)}.
	 * Returns negative integer when record id is not present in the array, positive if it is present. Returned number
	 * reflect the index where the record id is or should be present.
	 */
	static int indexOf(ImmutableBitmapDataProvider roaringBitmap, int recordId) {
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
	static RoaringBitmapWriter<RoaringBitmap> buildWriter() {
		return RoaringBitmapWriter
			.writer()
			.constantMemory()
			.runCompress(false)
			.get();
	}

	/**
	 * Computes {@link Bitmap} by applying conjunction on all passed bitmaps in an optimal way.
	 */
	@Nonnull
	static Bitmap and(@Nonnull RoaringBitmap[] theBitmaps) {
		if (theBitmaps.length == 0) {
			return EmptyBitmap.INSTANCE;
		} else if (theBitmaps.length == 1) {
			return new BaseBitmap(theBitmaps[0]);
		} else {
			final Bitmap theResult;
			long min = Integer.MAX_VALUE;
			long max = 0L;
			List<RoaringBitmap> roaringBitmaps = new ArrayList<>(theBitmaps.length);
			List<RoaringBitmap> negativeRoaringBitmaps = new ArrayList<>(theBitmaps.length);
			for (RoaringBitmap theBitmap : theBitmaps) {
				if (theBitmap.isEmpty()) {
					return EmptyBitmap.INSTANCE;
				}
				final int first = theBitmap.first();
				final int last = theBitmap.last();
				final int leftBound = Math.min(first, last);
				final int rightBound = Math.max(first, last);
				if (leftBound >= 0) {
					min = Math.min(first, leftBound);
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
			for (RoaringBitmap theBitmap : negativeRoaringBitmaps) {
				intermediateResult = RoaringBitmap.and(theBitmap, intermediateResult);
			}
			theResult = new BaseBitmap(intermediateResult);
			return theResult;
		}
	}

	/**
	 * Produces mutable copy of the roaring bitmap.
	 */
	RoaringBitmap getRoaringBitmap();
}
