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

package io.evitadb.index.bitmap;

import io.evitadb.dataType.iterator.ConstantIntIterator;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;

import java.io.Serial;
import java.util.Arrays;
import java.util.PrimitiveIterator.OfInt;

/**
 * Immutable, read-only {@link Bitmap} over a sorted `int[]` of record ids that it **shares rather than copies**. It is
 * the multi-record sibling of {@link SingleRecordBitmap}: the leanest representation of a handful of record ids, one
 * object plus the array it points at, with no {@link io.evitadb.roaringbitmap.PersistentRoaringBitmap} and no
 * {@link io.evitadb.dataType.array.CompositeIntArray}.
 *
 * ## Why this is not {@link ArrayBitmap}
 *
 * {@link ArrayBitmap} is a different structure wearing a similar name, and the two are not interchangeable. It backs
 * itself with a chunked {@link io.evitadb.dataType.array.CompositeIntArray} built by **copying** the ids handed to it,
 * keeps them in insertion order rather than sorted, and permits `add`. This class copies nothing, is sorted by
 * contract, and refuses every mutator. The distinction matters wherever the array is owned by a structure that hands
 * the view out: a mutable view over borrowed storage would let a caller corrupt every version of that structure which
 * still shares the array.
 *
 * ## Ordering — UNSIGNED inside, and the same split {@link TransactionalBitmap} presents outside
 *
 * The wrapped array must be sorted by {@link Integer#compareUnsigned} and hold no duplicates — that is the order a
 * {@link io.evitadb.roaringbitmap.PersistentRoaringBitmap} enumerates in. Keeping to it is what lets a structure
 * switch a record set between this representation and a roaring-backed one without any consumer noticing.
 *
 * A roaring-backed bitmap does not present one order on its whole surface, and this class reproduces the split
 * exactly rather than tidying it up — a tidier view would silently differ from the tier it stands in for:
 *
 * | method | order |
 * |---|---|
 * | {@link #iterator()} | **unsigned** — roaring's own enumeration |
 * | {@link #get(int)} | **unsigned** — roaring's own enumeration |
 * | {@link #getFirst()} | **unsigned** — roaring's own enumeration |
 * | {@link #getLast()} | **unsigned** — roaring's own enumeration |
 * | {@link #indexOf(int)} | **unsigned** — roaring's own enumeration |
 * | {@link #getRange(int, int)} | **unsigned** — roaring's own enumeration |
 * | {@link #getArray()} | **signed** — matches {@link TransactionalBitmap#getArray()} (via `toSignedArray`) |
 *
 * The two coincide unless the set holds a negative record id, which sorts after every non-negative one under
 * unsigned comparison and before every one of them under signed comparison.
 *
 * ## Ownership
 *
 * The array is stored by reference and must never be written to afterwards — by this class, which cannot, or by
 * whoever handed it in, which must not. A producer that needs to change the record set builds a different array and a
 * different view over it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ThreadSafe
public class SortedArrayBitmap implements Bitmap {
	@Serial private static final long serialVersionUID = -6027498134120056251L;
	private static final String ERROR_READ_ONLY = "Sorted array bitmap is read only.";
	/**
	 * {@link #getOwnerId()} of a view over an array that no identified structure owns.
	 */
	public static final long NO_OWNER = 0L;
	/**
	 * The record ids, sorted by {@link Integer#compareUnsigned} and distinct. Never written to.
	 */
	@Nonnull private final int[] recordIds;
	/**
	 * Identity of the structure that OWNS {@link #recordIds}, or {@link #NO_OWNER} when the view was built over an
	 * array nobody else holds.
	 *
	 * A view carries no identity of its own - it is created fresh on every read, so its own object identity is
	 * worthless as a cache key. The owner's identity is not: two structures holding byte-identical record sets are
	 * still two structures that are written to independently, and a consumer keying a cached answer on a record set
	 * has to be able to tell them apart. Where the owner is versioned (a B+ tree leaf is a fresh instance with a
	 * fresh id after every commit that rebuilt it), this doubles as a staleness token.
	 */
	private final long ownerId;

	/**
	 * Locates `recordId` in an array sorted by {@link Integer#compareUnsigned}, with the same return contract as
	 * {@link Arrays#binarySearch(int[], int)}: the index of the match, or `-(insertion point) - 1` when absent.
	 *
	 * The JDK offers no unsigned overload of {@link Arrays#binarySearch(int[], int)}, and the signed one silently
	 * answers the wrong slot as soon as the array holds a negative record id — which sorts *after* every
	 * non-negative one here. This is the one place that comparison is defined, so that every consumer of an
	 * unsigned-sorted record array searches it the same way.
	 *
	 * @param recordIds the array to search, sorted by unsigned comparison
	 * @param recordId  the record id to look for
	 * @return the index of `recordId`, or `-(insertion point) - 1` when it is not present
	 */
	public static int unsignedBinarySearch(@Nonnull int[] recordIds, int recordId) {
		int low = 0;
		int high = recordIds.length - 1;
		while (low <= high) {
			final int mid = (low + high) >>> 1;
			final int comparison = Integer.compareUnsigned(recordIds[mid], recordId);
			if (comparison < 0) {
				low = mid + 1;
			} else if (comparison > 0) {
				high = mid - 1;
			} else {
				return mid;
			}
		}
		return -(low + 1);
	}

	/**
	 * Returns the index of the first negative record id in an array sorted by {@link Integer#compareUnsigned}, i.e.
	 * the length of its non-negative prefix. Every negative id is unsigned-greater than every non-negative one, so the
	 * negatives always form a suffix and this one search separates the two signed halves.
	 *
	 * @param recordIds the array to split, sorted by unsigned comparison
	 * @return the index of the first negative id, or the array length when there is none
	 */
	public static int firstNegativeIndex(@Nonnull int[] recordIds) {
		final int position = unsignedBinarySearch(recordIds, Integer.MIN_VALUE);
		return position >= 0 ? position : -position - 1;
	}

	/**
	 * Creates a read-only view over the passed record ids.
	 *
	 * @param recordIds the record ids, sorted by {@link Integer#compareUnsigned} and distinct; stored by reference and
	 *                  never written to, so the caller must not write to it either
	 */
	public SortedArrayBitmap(@Nonnull int... recordIds) {
		this(NO_OWNER, recordIds);
	}

	/**
	 * Creates a read-only view over record ids owned by an identified structure.
	 *
	 * @param ownerId   identity of the structure that owns the array - see {@link #getOwnerId()}
	 * @param recordIds the record ids, sorted by {@link Integer#compareUnsigned} and distinct; stored by reference and
	 *                  never written to, so the owner must not write to it while the view is alive
	 */
	public SortedArrayBitmap(long ownerId, @Nonnull int... recordIds) {
		this.ownerId = ownerId;
		this.recordIds = recordIds;
	}

	/**
	 * Returns the identity of the structure owning the wrapped array, or {@link #NO_OWNER} when there is none.
	 *
	 * @return the owner's identity, or {@link #NO_OWNER}
	 */
	public long getOwnerId() {
		return this.ownerId;
	}

	@Override
	public boolean isEmpty() {
		return this.recordIds.length == 0;
	}

	@Override
	public int size() {
		return this.recordIds.length;
	}

	@Override
	public boolean add(int recordId) {
		throw new UnsupportedOperationException(ERROR_READ_ONLY);
	}

	@Override
	public void addAll(int... recordId) {
		throw new UnsupportedOperationException(ERROR_READ_ONLY);
	}

	@Override
	public void addAll(@Nonnull Bitmap recordIds) {
		throw new UnsupportedOperationException(ERROR_READ_ONLY);
	}

	@Override
	public boolean remove(int recordId) {
		throw new UnsupportedOperationException(ERROR_READ_ONLY);
	}

	@Override
	public void removeAll(int... recordId) {
		throw new UnsupportedOperationException(ERROR_READ_ONLY);
	}

	@Override
	public void removeAll(@Nonnull Bitmap recordIds) {
		throw new UnsupportedOperationException(ERROR_READ_ONLY);
	}

	@Override
	public boolean contains(int recordId) {
		return unsignedBinarySearch(this.recordIds, recordId) >= 0;
	}

	@Override
	public int indexOf(int recordId) {
		return unsignedBinarySearch(this.recordIds, recordId);
	}

	@Override
	public int get(int index) {
		return this.recordIds[index];
	}

	@Override
	public int[] getRange(int start, int end) {
		if (start < 0 || end > this.recordIds.length || start > end) {
			throw new IndexOutOfBoundsException("start: " + start + ", end: " + end);
		}
		return Arrays.copyOfRange(this.recordIds, start, end);
	}

	@Override
	public int getFirst() {
		return this.recordIds[0];
	}

	@Override
	public int getLast() {
		return this.recordIds[this.recordIds.length - 1];
	}

	/**
	 * Returns the record ids in **signed** ascending order, which is the order
	 * {@link TransactionalBitmap#getArray()} answers in and therefore the one every consumer of a bucket's record
	 * array already expects. The wrapped array is kept in unsigned order, so a set holding a negative id is rotated
	 * here: the negative tail moves to the front.
	 *
	 * Always a fresh array, exactly as the interface promises ("produces (allocates) new sorted array") - the wrapped
	 * one is storage owned by somebody else, and handing it out would let a caller write into it.
	 *
	 * @return the record ids, signed ascending
	 */
	@Override
	public int[] getArray() {
		final int firstNegative = firstNegativeIndex(this.recordIds);
		if (firstNegative == this.recordIds.length) {
			// no negative id - the two orders coincide
			return this.recordIds.clone();
		}
		final int[] signed = new int[this.recordIds.length];
		final int negativeCount = this.recordIds.length - firstNegative;
		System.arraycopy(this.recordIds, firstNegative, signed, 0, negativeCount);
		System.arraycopy(this.recordIds, 0, signed, negativeCount, firstNegative);
		return signed;
	}

	/**
	 * This object plus the record id array it points at.
	 *
	 * The array is charged in full even though this view merely borrows it, because the question a caller asks of a
	 * bitmap is what its record set costs, and answering ~16 bytes for a set of a hundred ids would be useless. The
	 * consequence is that this figure must not be summed into a structure that already prices the array itself —
	 * which is why the bucket tree charges its slots through the tier
	 * ({@code OverflowRecords.heapSizeInBytes}) rather than by wrapping each one in a view, and why an
	 * {@code InvertedIndexSubSet} prices buckets it merely slices at zero.
	 */
	@Override
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// the array reference and the owner id, plus the array itself
		return layout.sizeOfObject(layout.referenceSize() + Long.BYTES)
			+ layout.sizeOfArray(this.recordIds.length, Integer.BYTES);
	}

	@Nonnull
	@Override
	public OfInt iterator() {
		return new ConstantIntIterator(this.recordIds);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		// type-sensitive, consistent with the other Bitmap implementations (BaseBitmap / ArrayBitmap /
		// SingleRecordBitmap) - content equality across representations is what `getContentHash` is for
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		return Arrays.equals(this.recordIds, ((SortedArrayBitmap) o).recordIds);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(this.recordIds);
	}

	@Override
	public String toString() {
		return Arrays.toString(this.recordIds);
	}

}
