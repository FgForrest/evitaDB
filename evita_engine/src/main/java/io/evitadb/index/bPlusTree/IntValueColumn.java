/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.index.bPlusTree;

import io.evitadb.utils.ArrayUtils.InsertionPosition;
import io.evitadb.utils.Assert;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.ToLongFunction;

import static io.evitadb.utils.ArrayUtils.EMPTY_INT_ARRAY;
import static io.evitadb.utils.ArrayUtils.computeInsertPositionOfIntInOrderedArray;

/**
 * Primitive {@link ValueColumn} backed by an {@code int[]} for {@link Integer} keys that fit into 4 bytes. The boxed
 * {@link Integer} key is unboxed into / boxed from the backing {@code int} directly — a sign-preserving identity that is
 * trivially monotonic under natural order — so the column holds **no** boxed objects and the ordered search runs
 * directly on primitives.
 *
 * Compared with the 8-byte {@link LongValueColumn}, this column halves the per-bucket key footprint; it is intended for
 * keys that have already been normalized upstream to a scaled {@code int} — notably {@code BigDecimal} filter / sort
 * keys scaled by their {@code indexedDecimalPlaces} (so the column never sees the original wider type, only the
 * converted {@code int}). {@link Integer} is the **only** 32-bit encoding this column ever stores: every wider integral
 * / temporal type routes to {@link LongValueColumn}, so the unbox / box is inlined here rather than dispatched through a
 * codec abstraction. Collapsing this column into {@link LongValueColumn} would re-widen these keys back to 8 bytes and
 * defeat the footprint goal — hence the deliberate primitive specialization.
 *
 * Zero-allocation invariant: the only boxing happens at the decode boundary ({@link #keyAt}, {@link #asBoxedArray}) —
 * exactly the places the boxed leaf already materialized a key. All bulk / single-slot moves and the probe unboxing in
 * {@link #findKeyPosition} operate on the primitive array, so no per-element boxing ever occurs on a hot path.
 *
 * The backing array follows the live content rather than the leaf block size: an empty column parks on the JVM-wide
 * {@code ArrayUtils.EMPTY_INT_ARRAY} and owns nothing, the first insert allocates
 * {@code ColumnSizing.MIN_PHYSICAL_LENGTH} slots, and growth doubles up to {@link #capacity()}. See {@link ValueColumn}
 * for the family-wide contract that follows from it.
 *
 * Selected only when the tree comparator is natural order and the key type routes to an {@code int[]} column (see
 * {@link ValueColumnFactory}); otherwise the leaf keeps the universal {@link BoxedObjectColumn}.
 *
 * @param <M> the boxed key type as seen by the tree's generic API (always {@link Integer} in practice)
 */
final class IntValueColumn<M extends Comparable<M>> implements ValueColumn<M> {
	/**
	 * The logical capacity — the leaf block size, fixed for the column's lifetime. See {@link #capacity()}.
	 */
	private final int capacity;
	/**
	 * The number of live keys held in {@link #keys}, normally equal to the owning leaf's {@code peek + 1} — see
	 * {@link ValueColumn} for the three windows in which it is not.
	 */
	private int size;
	/**
	 * The primitive key backing array, sized to the live content rather than to {@link #capacity}. Slots in
	 * {@code [size, keys.length)} are always zero.
	 */
	@Nonnull private int[] keys;

	/**
	 * Creates an empty column for a leaf of the given block size. No backing storage is allocated until the first
	 * write — the array field parks on the JVM-wide shared empty array.
	 *
	 * @param capacity the logical capacity (the leaf block size)
	 */
	IntValueColumn(int capacity) {
		this.capacity = capacity;
		this.size = 0;
		this.keys = EMPTY_INT_ARRAY;
	}

	/**
	 * Internal constructor adopting pre-built state (duplicate / trim paths).
	 *
	 * @param capacity the logical capacity
	 * @param size     the live key count
	 * @param keys     the backing array to adopt
	 */
	private IntValueColumn(int capacity, int size, @Nonnull int[] keys) {
		this.capacity = capacity;
		this.size = size;
		this.keys = keys;
	}

	@Override
	public int capacity() {
		return this.capacity;
	}

	@Override
	public int size() {
		return this.size;
	}

	@Nonnull
	@Override
	public ValueColumn<M> allocate(int capacity) {
		return new IntValueColumn<>(capacity);
	}

	@Nonnull
	@Override
	public ValueColumn<M> trimmed() {
		final int target = ColumnSizing.trimmedLength(this.size, this.keys.length, this.capacity);
		if (target == this.keys.length) {
			return this;
		}
		return new IntValueColumn<>(this.capacity, this.size, Arrays.copyOf(this.keys, target));
	}

	@Nonnull
	@Override
	public ValueColumn<M> duplicate() {
		// an empty column keeps the shared empty array rather than cloning it into a private zero-length one - the
		// clone would cost an object header and break the shared-array identity every heap walk subtracts
		return new IntValueColumn<>(
			this.capacity, this.size, this.keys.length == 0 ? this.keys : this.keys.clone()
		);
	}

	@Nonnull
	@Override
	@SuppressWarnings("unchecked")
	public M keyAt(int index) {
		// boxing boundary — boxed exactly where the boxed leaf would have materialized the key
		return (M) Integer.valueOf(this.keys[index]);
	}

	@Override
	public void insertKeyAt(int index, @Nonnull M value) {
		if (this.size == this.keys.length) {
			growAndInsertKeyAt(index, value);
			return;
		}
		System.arraycopy(this.keys, index, this.keys, index + 1, this.size - index);
		this.keys[index] = (Integer) value;
		this.size++;
	}

	@Override
	public void bulkLoad(@Nonnull Object[] keys, int count) {
		// always a fresh array: the contract says this column is freshly allocated, and reusing the existing backing
		// would make this the one mutator in the family that writes into an array it did not allocate
		final int[] target = newArray(count);
		for (int i = 0; i < count; i++) {
			target[i] = (Integer) keys[i];
		}
		this.keys = target;
		this.size = count;
	}

	@Override
	public void removeKeyAt(int index) {
		if (index >= this.size) {
			// the run past `size` is already empty - dropping one empty slot out of it leaves it empty
			return;
		}
		System.arraycopy(this.keys, index + 1, this.keys, index, this.size - index - 1);
		this.size--;
		this.keys[this.size] = 0;
	}

	@Override
	public void clearAt(int index) {
		if (index < this.size) {
			Arrays.fill(this.keys, index, this.size, 0);
			this.size = index;
		}
	}

	@Override
	public void copyRangeTo(int srcPos, @Nonnull ValueColumn<M> dst, int dstPos, int length) {
		assertSourceRangeIsLive(srcPos, length);
		final IntValueColumn<M> target = asSameKind(dst);
		final int oldSize = target.size;
		final int required = dstPos + length;
		target.ensurePhysicalLength(required);
		if (dstPos > oldSize) {
			// a right shift opens a hole between the destination's old live end and dstPos; it must read as empty
			Arrays.fill(target.keys, oldSize, dstPos, 0);
		}
		System.arraycopy(this.keys, srcPos, target.keys, dstPos, length);
		target.size = Math.max(oldSize, required);
	}

	/**
	 * Refuses a source range that reaches past this column's live run. A key column has no empty key it could
	 * substitute, so absorbing the violation would turn a caller bug into a tree that silently holds wrong keys —
	 * the failure mode the leaf's split-range argument already warns about, where half a leaf can vanish with no
	 * exception at all.
	 *
	 * @param srcPos the start index the caller is reading from
	 * @param length the number of keys the caller is reading
	 */
	private void assertSourceRangeIsLive(int srcPos, int length) {
		Assert.isPremiseValid(
			srcPos >= 0 && srcPos + length <= this.size,
			() -> "Key column source range [" + srcPos + ", " + (srcPos + length) + ") runs past its live run ("
				+ this.size + ") — a key column has no empty key to substitute."
		);
	}

	@Override
	public void fillEmpty(int fromInclusive, int toExclusive) {
		if (fromInclusive < this.size) {
			Arrays.fill(this.keys, fromInclusive, this.size, 0);
			this.size = fromInclusive;
		}
	}

	@Nonnull
	@Override
	public InsertionPosition findKeyPosition(@Nonnull M key, int from, int to, @Nullable Comparator<M> comparator) {
		// the comparator is intentionally ignored: the factory only selects this column for natural-order trees, and the
		// int order is identical to natural Integer order; the probe is unboxed once
		final int probe = (Integer) key;
		return computeInsertPositionOfIntInOrderedArray(probe, this.keys, from, to);
	}

	@Override
	public void appendKey(@Nonnull StringBuilder sb, int index) {
		sb.append(this.keys[index]);
	}

	@Nonnull
	@Override
	@SuppressWarnings("unchecked")
	public M[] asBoxedArray() {
		// cold path only (consistency verification / toString) — never the query hot path; the live run is the whole
		// array, which satisfies the interface's "length >= size, tail empty" contract exactly
		final M[] boxed = (M[]) new Integer[this.size];
		for (int i = 0; i < this.size; i++) {
			boxed[i] = (M) Integer.valueOf(this.keys[i]);
		}
		return boxed;
	}

	@Override
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		long size = layout.sizeOfObject(layout.referenceSize() + 2L * Integer.BYTES);
		// an empty column parks on the JVM-wide shared empty array, which costs it nothing beyond the slot above
		if (this.keys != EMPTY_INT_ARRAY) {
			size += layout.sizeOfArray(this.keys.length, Integer.BYTES);
		}
		return size;
	}

	@Override
	public long getHeapSizeInBytes(@Nonnull ToLongFunction<? super M> elementSizer) {
		// keys are `int` values inside the array - there is nothing for the sizer to price
		return getHeapSizeInBytes();
	}

	/**
	 * Reallocates {@link #keys} so it holds at least {@code requiredLength} slots, carrying the live keys across. Kept
	 * out of the mutators so their steady-state path stays a single field compare against the array length — the
	 * cursor-free insert path's escape analysis depends on that path staying small.
	 *
	 * @param requiredLength the number of slots the caller is about to address
	 */
	private void ensurePhysicalLength(int requiredLength) {
		if (requiredLength > this.keys.length) {
			this.keys = Arrays.copyOf(
				this.keys, ColumnSizing.grownLength(this.keys.length, requiredLength, this.capacity)
			);
		}
	}

	/**
	 * The out-of-line half of {@link #insertKeyAt}: grows the backing array, then performs the very same shift-and-set
	 * the fast path performs.
	 *
	 * @param index the insertion position
	 * @param value the key to insert
	 */
	private void growAndInsertKeyAt(int index, @Nonnull M value) {
		ensurePhysicalLength(this.size + 1);
		System.arraycopy(this.keys, index, this.keys, index + 1, this.size - index);
		this.keys[index] = (Integer) value;
		this.size++;
	}

	/**
	 * Allocates a backing array of the given length, keeping a zero length on the shared empty array.
	 *
	 * @param length the array length
	 * @return the fresh array
	 */
	@Nonnull
	private static int[] newArray(int length) {
		return length == 0 ? EMPTY_INT_ARRAY : new int[length];
	}

	/**
	 * Narrows a sibling column to the same concrete kind (one attribute index = one value type ⇒ always holds).
	 *
	 * @param other the sibling column
	 * @return {@code other} as an {@link IntValueColumn}
	 */
	@Nonnull
	private IntValueColumn<M> asSameKind(@Nonnull ValueColumn<M> other) {
		if (other instanceof IntValueColumn<M> primitive) {
			return primitive;
		}
		throw new IllegalArgumentException(
			"Cannot mix value column kinds within one tree: " + other.getClass().getName());
	}
}
