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
import io.evitadb.roaringbitmap.ImmutableBitmapDataProvider;
import io.evitadb.roaringbitmap.PeekableIntIterator;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.PrimitiveIterator;

/**
 * Implementations of this interface are backed with some form of {@link PersistentRoaringBitmap} and can produce it when asked.
 * This interface allows to optimize Immutable -> Mutable -> Immutable versions of PersistentRoaringBitmap roundtrips by allowing
 * to access internal representation of the PersistentRoaringBitmap.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface RoaringBitmapBackedBitmap extends Bitmap {

	/**
	 * Mean number of record ids per 65 536-wide roaring container at which {@link #fromArray(int...)}
	 * switches from the incremental build to the constant-memory writer. It mirrors {@code
	 * ArrayContainer.DEFAULT_MAX_SIZE} (package-private in the bitmap module): the cardinality above
	 * which a container is promoted from an {@code ArrayContainer} to a {@code BitmapContainer}. See
	 * {@link #fromArray(int...)} for the full reasoning.
	 */
	int WRITER_DISPATCH_DENSITY = 4096;

	/**
	 * Creates {@link PersistentRoaringBitmap} from the array of integers, adaptively picking the
	 * cheaper of two construction strategies:
	 *
	 * - the **incremental** path ({@link PersistentRoaringBitmap#add(int...)} into a fresh bitmap),
	 *   which appends each id into per-container {@code ArrayContainer}s — cheapest for small or
	 *   sparse inputs; and
	 * - the **constant-memory writer** ({@link #buildWriter()}), which fills a reused 65 536-bit word
	 *   buffer and materializes each container once — cheapest for large, densely-packed inputs, where
	 *   the incremental path otherwise pays repeated {@code ArrayContainer} reallocation plus the
	 *   array→bitmap conversion.
	 *
	 * The two cross over at {@link #WRITER_DISPATCH_DENSITY} ids **per container**: below it a
	 * container stays a cheap {@code ArrayContainer} (incremental wins); at/above it the container
	 * becomes a {@code BitmapContainer} (writer wins). The choice is therefore gated on mean
	 * ids-per-container (see the private {@code isDense} probe), not raw length. A JMH sweep ({@code
	 * BitmapConstructionBenchmark}) measured the writer ≈2–3× faster with ≈⅓ the allocation on large
	 * dense arrays, but **6–7× slower** on equally-large *sparse* ones — its fixed 8 KB word buffer
	 * never amortizes when each container holds only a handful of ids — so a length-only threshold
	 * would badly regress scattered id sets. The density probe is O(1) (for the ascending arrays this
	 * method is fed, the extremes are the array ends), so it is unmeasurable against the build it
	 * guards: small and sparse inputs stay on the incremental path with no overhead, and only large,
	 * dense arrays take the writer.
	 *
	 * The writer path is normalized with {@link PersistentRoaringBitmap#removeRunCompression()} so its
	 * output is representation-identical to the incremental build: the writer canonicalizes a
	 * completely-full 65 536-wide container to a {@code RunContainer}, whereas incremental append
	 * leaves it a {@code BitmapContainer}, and the two — though equal — carry different hash codes.
	 * Undoing that canonicalization keeps this method a pure speed-up with no observable change to the
	 * bitmap it produces (locked in by the equivalence test in {@code BaseBitmapTest}).
	 *
	 * Providing a sorted array in ascending order is preferred for performance, but unsorted input is
	 * also handled correctly (the density probe reads only the array ends, and falls back to the safe
	 * incremental path when they are not ascending).
	 */
	@Nonnull
	static PersistentRoaringBitmap fromArray(@Nonnull int... array) {
		if (ArrayUtils.isEmpty(array)) {
			return new PersistentRoaringBitmap();
		} else if (array.length >= WRITER_DISPATCH_DENSITY && isDense(array)) {
			// large + densely packed: the constant-memory writer avoids per-container reallocation and
			// array->bitmap conversion
			final RoaringBitmapWriter<PersistentRoaringBitmap> writer = buildWriter();
			writer.addMany(array);
			final PersistentRoaringBitmap result = writer.get();
			// normalize any full-container RunContainer back to a BitmapContainer so representation (and
			// hashCode) matches the incremental build
			result.removeRunCompression();
			return result;
		} else {
			// small or sparse: incremental append into ArrayContainers is faster and allocates less (no 8 KB
			// word buffer)
			final PersistentRoaringBitmap result = new PersistentRoaringBitmap();
			result.add(array);
			return result;
		}
	}

	/**
	 * O(1) density probe used by {@link #fromArray(int...)} to choose between the incremental and
	 * writer build paths. Returns whether the ids pack densely enough — a mean of at least {@link
	 * #WRITER_DISPATCH_DENSITY} ids per 65 536-wide container — for the constant-memory writer to
	 * overtake incremental appends.
	 *
	 * The span of occupied containers is derived from the id extremes without scanning: index
	 * record-id sets are handed out sorted ascending, so `recordIds[0]` and `recordIds[length - 1]`
	 * are the min and max and their high 16 bits bound the container range. Should a caller ever pass
	 * unsorted ids, the ends may not be the extremes; the method then returns `false` (taking the
	 * always-correct incremental path) whenever they are not ascending, so a bogus span can never
	 * route a scattered array to the writer. This keeps the probe honest without an O(n) sortedness
	 * scan.
	 *
	 * @param recordIds the record ids, expected sorted ascending; non-empty (the length check in the
	 *                  caller guarantees it)
	 * @return `true` when the ids are dense enough for the writer path to win
	 */
	private static boolean isDense(@Nonnull int[] recordIds) {
		final int firstContainer = recordIds[0] >>> 16;
		final int lastContainer = recordIds[recordIds.length - 1] >>> 16;
		// ends not ascending: input isn't sorted, extremes-from-ends assumption is void, take safe path
		if (lastContainer < firstContainer) {
			return false;
		}
		final int containerSpan = lastContainer - firstContainer + 1;
		return recordIds.length >= (long) containerSpan * WRITER_DISPATCH_DENSITY;
	}

	/**
	 * Returns {@link PersistentRoaringBitmap} from any bitmap in the argument. For
	 * {@link RoaringBitmapBackedBitmap} implementations, returns the internal bitmap reference
	 * directly (not a copy). For other {@link Bitmap} implementations, creates a new
	 * {@link PersistentRoaringBitmap} from the bitmap's array.
	 */
	@Nonnull
	static PersistentRoaringBitmap getRoaringBitmap(@Nonnull Bitmap bitmap) {
		if (bitmap instanceof RoaringBitmapBackedBitmap) {
			return ((RoaringBitmapBackedBitmap) bitmap).getRoaringBitmap();
		} else {
			return fromArray(bitmap.getArray());
		}
	}

	/**
	 * Returns a cloned {@link PersistentRoaringBitmap} from any bitmap in the argument. For
	 * {@link RoaringBitmapBackedBitmap} implementations, clones the internal bitmap. For other
	 * {@link Bitmap} implementations, creates a new {@link PersistentRoaringBitmap} from the bitmap's array.
	 * The returned bitmap is always safe to modify without affecting the original.
	 */
	@Nonnull
	static PersistentRoaringBitmap getRoaringBitmapClone(@Nonnull Bitmap bitmap) {
		if (bitmap instanceof RoaringBitmapBackedBitmap) {
			return (((RoaringBitmapBackedBitmap) bitmap).getRoaringBitmap()).clone();
		} else {
			return fromArray(bitmap.getArray());
		}
	}

	/**
	 * Returns index of the record inside {@link PersistentRoaringBitmap}. The method follows the same
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
	 * Extracts contents of the passed {@link PersistentRoaringBitmap} as an `int[]` ordered by signed
	 * integer value. Delegates to {@link PersistentRoaringBitmap#toSignedArray()}, where the logic now
	 * lives (co-located with the bitmap now that RoaringBitmap is vendored rather than an external
	 * library). That implementation fills the result in signed order in a single pass — no rotation
	 * step and no second array allocation on top of the result array.
	 */
	@Nonnull
	static int[] toSignedArray(@Nonnull PersistentRoaringBitmap roaringBitmap) {
		return roaringBitmap.toSignedArray();
	}

	/**
	 * Method creates {@link PersistentRoaringBitmap} builder that is optimized for fast and memory efficient bitmap construction.
	 */
	@Nonnull
	static RoaringBitmapWriter<PersistentRoaringBitmap> buildWriter() {
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
	 * record ids are handled separately due to {@link PersistentRoaringBitmap} treating integers as unsigned.
	 */
	@Nonnull
	static Bitmap and(@Nonnull PersistentRoaringBitmap[] theBitmaps) {
		if (theBitmaps.length == 0) {
			return EmptyBitmap.INSTANCE;
		}
		// early exit if any bitmap is empty — intersection must be empty
		for (final PersistentRoaringBitmap theBitmap : theBitmaps) {
			if (theBitmap.isEmpty()) {
				return EmptyBitmap.INSTANCE;
			}
		}
		if (theBitmaps.length == 1) {
			return new BaseBitmap(theBitmaps[0]);
		} else {
			long min = Integer.MAX_VALUE;
			long max = 0L;
			final List<PersistentRoaringBitmap> roaringBitmaps = new ArrayList<>(theBitmaps.length);
			final List<PersistentRoaringBitmap> negativeRoaringBitmaps = new ArrayList<>(theBitmaps.length);
			for (final PersistentRoaringBitmap theBitmap : theBitmaps) {
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

			PersistentRoaringBitmap intermediateResult;
			if (roaringBitmaps.isEmpty()) {
				intermediateResult = negativeRoaringBitmaps.get(0);
			} else if (roaringBitmaps.size() == 1) {
				intermediateResult = roaringBitmaps.get(0);
			} else {
				intermediateResult = PersistentRoaringBitmap.and(roaringBitmaps.iterator(), min, max + 1);
			}
			for (final PersistentRoaringBitmap theBitmap : negativeRoaringBitmaps) {
				intermediateResult = PersistentRoaringBitmap.and(theBitmap, intermediateResult);
			}
			return new BaseBitmap(intermediateResult);
		}
	}

	/**
	 * Returns the internal {@link PersistentRoaringBitmap} instance backing this bitmap. The returned bitmap
	 * is **not** a copy - modifications to it will affect this bitmap directly. Use
	 * {@link #getRoaringBitmapClone(Bitmap)} when an independent copy is needed.
	 */
	@Nonnull
	PersistentRoaringBitmap getRoaringBitmap();

	/**
	 * Thin adapter that wraps {@link PeekableIntIterator} as {@link PrimitiveIterator.OfInt}
	 * without the allocation overhead of `PersistentRoaringBitmap.stream().iterator()`.
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
