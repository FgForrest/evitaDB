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

import com.carrotsearch.hppc.predicates.IntPredicate;
import io.evitadb.roaringbitmap.PeekableIntIterator;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBatchIterator;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator.OfInt;
import java.util.stream.Collectors;

/**
 * IntegerBitmap implementation that is backed by {@link PersistentRoaringBitmap}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class BaseBitmap implements RoaringBitmapBackedBitmap {
	@Serial private static final long serialVersionUID = -8471705193727315151L;
	/**
	 * Mean number of record ids per 65 536-wide roaring container at which the {@link #BaseBitmap(int...)} constructor
	 * switches from the incremental build to the constant-memory writer. It mirrors {@code ArrayContainer.DEFAULT_MAX_SIZE}
	 * (package-private in the bitmap module): the cardinality above which a container is promoted from an
	 * {@code ArrayContainer} to a {@code BitmapContainer}. See {@link #BaseBitmap(int...)} for the full reasoning.
	 */
	private static final int WRITER_DISPATCH_DENSITY = 4096;
	private final PersistentRoaringBitmap roaringBitmap;
	private int memoizedCardinality;

	public BaseBitmap() {
		this.roaringBitmap = new PersistentRoaringBitmap();
		this.memoizedCardinality = 0;
	}

	/**
	 * Builds a bitmap from a raw array of record ids, adaptively picking the cheaper of two construction strategies:
	 *
	 * - the **incremental** path ({@link PersistentRoaringBitmap#add(int...)} into a fresh bitmap), which appends each
	 *   id into per-container {@code ArrayContainer}s — cheapest for small or sparse inputs; and
	 * - the **constant-memory writer** ({@link RoaringBitmapBackedBitmap#fromArray}), which fills a reused 65 536-bit
	 *   word buffer and materializes each container once — cheapest for large, densely-packed inputs, where the
	 *   incremental path otherwise pays repeated {@code ArrayContainer} reallocation plus the array→bitmap conversion.
	 *
	 * The two cross over at {@link #WRITER_DISPATCH_DENSITY} ids **per container**: below it a container stays a cheap
	 * {@code ArrayContainer} (incremental wins); at/above it the container becomes a {@code BitmapContainer} (writer
	 * wins). The choice is therefore gated on mean ids-per-container ({@link #isDense}), not raw length. A JMH sweep
	 * ({@code BitmapConstructionBenchmark}) measured the writer ≈2–3× faster with ≈⅓ the allocation on large dense
	 * arrays, but **6–7× slower** on equally-large *sparse* ones — its fixed 8 KB word buffer never amortizes when each
	 * container holds only a handful of ids — so a length-only threshold would badly regress scattered id sets. The
	 * density probe is O(1) (for the ascending arrays this constructor is fed, the extremes are the array ends), so it is
	 * unmeasurable against the build it guards: small and sparse inputs stay on the incremental path with no overhead,
	 * and only large, dense arrays take the writer.
	 *
	 * The writer path is normalized with {@link PersistentRoaringBitmap#removeRunCompression()} so its output is
	 * representation-identical to the incremental build: the writer canonicalizes a completely-full 65 536-container to a
	 * {@code RunContainer}, whereas incremental append leaves it a {@code BitmapContainer}, and the two — though
	 * {@link #equals(Object) equal} — carry different {@link #hashCode() hash codes}. Undoing that canonicalization keeps
	 * the constructor a pure speed-up with no observable change to the bitmap it produces (locked in by the equivalence
	 * test in {@code BaseBitmapTest}).
	 *
	 * @param recordIds the record ids to store; expected sorted ascending (the density probe reads only the ends, and
	 *                  falls back to the safe incremental path when they are not ascending)
	 */
	public BaseBitmap(@Nonnull int... recordIds) {
		final PersistentRoaringBitmap theRoaringBitmap;
		if (recordIds.length >= WRITER_DISPATCH_DENSITY && isDense(recordIds)) {
			// large + densely packed: the constant-memory writer avoids per-container reallocation and array→bitmap conversion
			theRoaringBitmap = RoaringBitmapBackedBitmap.fromArray(recordIds);
			// normalize any full-container RunContainer back to a BitmapContainer so the representation (and thus the hash
			// code) is identical to the incremental build — see the constructor JavaDoc
			theRoaringBitmap.removeRunCompression();
		} else {
			// small or sparse: incremental append into ArrayContainers is faster and allocates less (no 8 KB word buffer)
			theRoaringBitmap = new PersistentRoaringBitmap();
			theRoaringBitmap.add(recordIds);
		}
		this.roaringBitmap = theRoaringBitmap;
		this.memoizedCardinality = theRoaringBitmap.getCardinality();
	}

	/**
	 * O(1) density probe used by {@link #BaseBitmap(int...)} to choose between the incremental and writer build paths.
	 * Returns whether the ids pack densely enough — a mean of at least {@link #WRITER_DISPATCH_DENSITY} ids per
	 * 65 536-wide container — for the constant-memory writer to overtake incremental appends.
	 *
	 * The span of occupied containers is derived from the id extremes without scanning: index record-id sets are handed
	 * out sorted ascending, so `recordIds[0]` and `recordIds[length - 1]` are the min and max and their high 16 bits
	 * bound the container range. Should a caller ever pass unsorted ids, the ends may not be the extremes; the method
	 * then returns `false` (taking the always-correct incremental path) whenever they are not ascending, so a bogus span
	 * can never route a scattered array to the writer. This keeps the probe honest without an O(n) sortedness scan.
	 *
	 * @param recordIds the record ids, expected sorted ascending; non-empty (the length check in the caller guarantees it)
	 * @return `true` when the ids are dense enough for the writer path to win
	 */
	private static boolean isDense(@Nonnull int[] recordIds) {
		final int firstContainer = recordIds[0] >>> 16;
		final int lastContainer = recordIds[recordIds.length - 1] >>> 16;
		// ends not ascending ⇒ input is not sorted, so the extremes-from-ends assumption is void: take the safe path
		if (lastContainer < firstContainer) {
			return false;
		}
		final int containerSpan = lastContainer - firstContainer + 1;
		return recordIds.length >= (long) containerSpan * WRITER_DISPATCH_DENSITY;
	}

	public BaseBitmap(@Nonnull Bitmap bitmap) {
		final PersistentRoaringBitmap theRoaringBitmap;
		if (bitmap instanceof RoaringBitmapBackedBitmap) {
			theRoaringBitmap = ((RoaringBitmapBackedBitmap) bitmap).getRoaringBitmap().clone();
		} else {
			theRoaringBitmap = RoaringBitmapBackedBitmap.fromArray(bitmap.getArray());
		}
		this.roaringBitmap = theRoaringBitmap;
		this.memoizedCardinality = bitmap.size();
	}

	public BaseBitmap(@Nonnull PersistentRoaringBitmap bitmap) {
		this.roaringBitmap = bitmap;
		this.memoizedCardinality = bitmap.getCardinality();
	}

	@Nonnull
	@Override
	public PersistentRoaringBitmap getRoaringBitmap() {
		return this.roaringBitmap;
	}

	@Override
	public boolean add(int recordId) {
		final boolean added = this.roaringBitmap.checkedAdd(recordId);
		this.memoizedCardinality = added ? -1 : this.memoizedCardinality;
		return added;
	}

	@Override
	public void addAll(int... recordId) {
		this.roaringBitmap.add(recordId);
		this.memoizedCardinality = -1;
	}

	@Override
	public void addAll(@Nonnull Bitmap recordIds) {
		this.roaringBitmap.add(recordIds.getArray());
		this.memoizedCardinality = -1;
	}

	@Override
	public boolean remove(int recordId) {
		final boolean removed = this.roaringBitmap.checkedRemove(recordId);
		this.memoizedCardinality = removed ? -1 : this.memoizedCardinality;
		return removed;
	}

	@Override
	public void removeAll(int... recordId) {
		for (int recId : recordId) {
			this.roaringBitmap.remove(recId);
		}
		this.memoizedCardinality = -1;
	}

	@Override
	public void removeAll(@Nonnull Bitmap recordIds) {
		if (recordIds instanceof RoaringBitmapBackedBitmap) {
			this.roaringBitmap.andNot(((RoaringBitmapBackedBitmap) recordIds).getRoaringBitmap());
		} else {
			final OfInt it = recordIds.iterator();
			while (it.hasNext()) {
				final int recordId = it.nextInt();
				this.roaringBitmap.remove(recordId);
			}
		}
		this.memoizedCardinality = -1;
	}

	/**
	 * Removes all elements from the bitmap that satisfy the specified predicate.
	 *
	 * @param predicate a non-null predicate to test each element in the bitmap.
	 *                  Elements for which {@code predicate.apply(int)} returns {@code true} will be removed.
	 */
	public void removeAll(@Nonnull IntPredicate predicate) {
		final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		if (size() > 64) {
			final int[] buffer = new int[64];
			final RoaringBatchIterator batchIterator = this.roaringBitmap.getBatchIterator();
			while (batchIterator.hasNext()) {
				final int peek = batchIterator.nextBatch(buffer);
				for (int i = 0; i < peek; i++) {
					final int next = buffer[i];
					if (predicate.apply(next)) {
						writer.add(next);
					}
				}
			}
		} else {
			final PeekableIntIterator it = this.roaringBitmap.getIntIterator();
			while (it.hasNext()) {
				final int next = it.next();
				if (predicate.apply(next)) {
					writer.add(next);
				}
			}
		}
		this.roaringBitmap.andNot(writer.get());
		this.memoizedCardinality = -1;
	}

	/**
	 * Retains only the elements in the bitmap that satisfy the specified predicate.
	 *
	 * @param predicate a non-null predicate that tests each element in the bitmap.
	 *                  Elements for which {@code predicate.apply(int)} returns {@code false} are removed.
	 *                  Elements for which it returns {@code true} are retained.
	 */
	public void retainAll(@Nonnull IntPredicate predicate) {
		final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		if (size() > 64) {
			final int[] buffer = new int[64];
			final RoaringBatchIterator batchIterator = this.roaringBitmap.getBatchIterator();
			while (batchIterator.hasNext()) {
				final int peek = batchIterator.nextBatch(buffer);
				for (int i = 0; i < peek; i++) {
					final int next = buffer[i];
					if (!predicate.apply(next)) {
						writer.add(next);
					}
				}
			}
		} else {
			final PeekableIntIterator it = this.roaringBitmap.getIntIterator();
			while (it.hasNext()) {
				final int next = it.next();
				if (!predicate.apply(next)) {
					writer.add(next);
				}
			}
		}
		this.roaringBitmap.andNot(writer.get());
		this.memoizedCardinality = -1;
	}

	/**
	 * Clears all data in the bitmap.
	 * This method resets the internal bitmap structure, effectively removing all stored record IDs,
	 * and also resets the memoized cardinality to zero.
	 */
	public void clear() {
		this.roaringBitmap.clear();
		this.memoizedCardinality = 0;
	}

	@Override
	public boolean contains(int recordId) {
		return this.roaringBitmap.contains(recordId);
	}

	@Override
	public int indexOf(int recordId) {
		return RoaringBitmapBackedBitmap.indexOf(this.roaringBitmap, recordId);
	}

	@Override
	public int get(int index) {
		try {
			return this.roaringBitmap.select(index);
		} catch (IllegalArgumentException ex) {
			throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size());
		}
	}

	@Override
	public int[] getRange(int start, int end) {
		try {
			final int length = end - start;
			final int[] result = new int[length];
			if (result.length == 0) {
				return result;
			}
			result[0] = this.roaringBitmap.select(start);
			final PeekableIntIterator it = this.roaringBitmap.getIntIterator();
			it.advanceIfNeeded(result[0]);
			it.next();
			for (int i = 1; i < length; i++) {
				if (it.hasNext()) {
					result[i] = it.next();
				} else {
					throw new IndexOutOfBoundsException("Index: " + (start + i) + ", Size: " + size());
				}
			}
			return result;
		} catch (IllegalArgumentException ex) {
			throw new IndexOutOfBoundsException("Index: " + start + ", Size: " + size());
		}
	}

	@Override
	public int getFirst() {
		try {
			return this.roaringBitmap.first();
		} catch (NoSuchElementException ex) {
			throw new IndexOutOfBoundsException("IntegerBitmap is empty!");
		}
	}

	@Override
	public int getLast() {
		try {
			return this.roaringBitmap.last();
		} catch (NoSuchElementException ex) {
			throw new IndexOutOfBoundsException("IntegerBitmap is empty!");
		}
	}

	@Override
	public int[] getArray() {
		return RoaringBitmapBackedBitmap.toSignedArray(this.roaringBitmap);
	}

	@Nonnull
	@Override
	public OfInt iterator() {
		return new RoaringBitmapBackedBitmap.RoaringIntIteratorAdapter(this.roaringBitmap.getIntIterator());
	}

	@Override
	public boolean isEmpty() {
		return this.roaringBitmap.isEmpty();
	}

	@Override
	public int size() {
		if (this.memoizedCardinality == -1) {
			this.memoizedCardinality = this.roaringBitmap.getCardinality();
		}
		return this.memoizedCardinality;
	}

	@Override
	public int hashCode() {
		return this.roaringBitmap.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		BaseBitmap that = (BaseBitmap) o;
		return this.roaringBitmap.equals(that.roaringBitmap);
	}

	@Override
	public String toString() {
		// we need to unify the output with ArrayBitmap and other implementations
		return "[" + this.roaringBitmap.stream().mapToObj(Integer::toString).collect(Collectors.joining(", ")) + "]";
	}
}
