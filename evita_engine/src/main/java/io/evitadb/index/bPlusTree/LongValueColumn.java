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
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.ToLongFunction;

import static io.evitadb.utils.ArrayUtils.EMPTY_LONG_ARRAY;
import static io.evitadb.utils.ArrayUtils.computeInsertPositionOfLongInOrderedArray;

/**
 * Primitive {@link ValueColumn} backed by a {@code long[]} for integral / temporal attribute keys. The boxed key type
 * {@code M} is mapped to / from the backing {@code long} by an order-preserving {@link LongKeyCodec} bijection, so the
 * column holds **no** boxed objects and the ordered search runs directly on primitives.
 *
 * Zero-allocation invariant: the only boxing happens at the decode boundary ({@link #keyAt}, {@link #asBoxedArray}) and
 * when the probe is encoded once in {@link #findKeyPosition} — exactly the places the boxed leaf already materialized a
 * key. All bulk / single-slot moves operate on the primitive array, so no per-element boxing ever occurs on a hot path.
 *
 * The backing array follows the live content rather than the leaf block size: an empty column parks on the JVM-wide
 * {@code ArrayUtils.EMPTY_LONG_ARRAY} and owns nothing, the first insert allocates
 * {@code ColumnSizing.MIN_PHYSICAL_LENGTH} slots, and growth doubles up to {@link #capacity()}. See {@link ValueColumn}
 * for the family-wide contract that follows from it and {@code ColumnSizing} for the numbers.
 *
 * Selected only when the tree comparator is natural order and the key type has a {@link LongKeyCodec} (see
 * {@link ValueColumnFactory}); otherwise the leaf keeps the universal {@link BoxedObjectColumn}.
 *
 * @param <M> the boxed key type as seen by the tree's generic API
 */
final class LongValueColumn<M extends Comparable<M>> implements ValueColumn<M> {
	/**
	 * The order-preserving codec mapping {@code M ↔ long} (raw type — the codec methods are generic per call).
	 */
	@Nonnull private final LongKeyCodec codec;
	/**
	 * The logical capacity — the leaf block size, fixed for the column's lifetime. See {@link #capacity()}.
	 */
	private final int capacity;
	/**
	 * The number of live keys held in {@link #keys}, normally equal to the owning leaf's {@code peek + 1} — see
	 * {@link ValueColumn} for the two transient windows in which it is not.
	 */
	private int size;
	/**
	 * The primitive key backing array, sized to the live content rather than to {@link #capacity}. Slots in
	 * {@code [size, keys.length)} are always zero.
	 */
	@Nonnull private long[] keys;

	/**
	 * Creates an empty column for a leaf of the given block size. No backing storage is allocated until the first
	 * write — the array field parks on the JVM-wide shared empty array.
	 *
	 * @param codec    the order-preserving codec
	 * @param capacity the logical capacity (the leaf block size)
	 */
	LongValueColumn(@Nonnull LongKeyCodec codec, int capacity) {
		this.codec = codec;
		this.capacity = capacity;
		this.size = 0;
		this.keys = EMPTY_LONG_ARRAY;
	}

	/**
	 * Internal constructor adopting pre-built state (duplicate / trim paths).
	 *
	 * @param codec    the order-preserving codec
	 * @param capacity the logical capacity
	 * @param size     the live key count
	 * @param keys     the backing array to adopt
	 */
	private LongValueColumn(@Nonnull LongKeyCodec codec, int capacity, int size, @Nonnull long[] keys) {
		this.codec = codec;
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

	@Override
	public int observableLiveRun() {
		return Math.min(this.size, this.keys.length);
	}

	@Nonnull
	@Override
	public ValueColumn<M> allocate(int capacity) {
		return new LongValueColumn<>(this.codec, capacity);
	}

	@Nonnull
	@Override
	public ValueColumn<M> trimmed() {
		final int target = ColumnSizing.trimmedLength(this.size, this.keys.length, this.capacity);
		if (target == this.keys.length) {
			return this;
		}
		return new LongValueColumn<>(this.codec, this.capacity, this.size, Arrays.copyOf(this.keys, target));
	}

	@Nonnull
	@Override
	public ValueColumn<M> duplicate() {
		// an empty column keeps the shared empty array rather than cloning it into a private zero-length one - the
		// clone would cost an object header and break the shared-array identity every heap walk subtracts
		return new LongValueColumn<>(
			this.codec, this.capacity, this.size, this.keys.length == 0 ? this.keys : this.keys.clone()
		);
	}

	@Nonnull
	@Override
	public ValueColumn<M> duplicateForInsert() {
		// an empty column keeps the shared empty array, exactly as `duplicate()` does — `headroomLength` answers the
		// source's own length for it, and `Arrays.copyOf` of a zero-length array would allocate a private one
		final int target = ColumnSizing.headroomLength(this.size, this.keys.length, this.capacity);
		return new LongValueColumn<>(
			this.codec, this.capacity, this.size, this.keys.length == 0 ? this.keys : Arrays.copyOf(this.keys, target)
		);
	}

	@Nonnull
	@Override
	public M keyAt(int index) {
		// boxing boundary — decoded exactly where the boxed leaf would have materialized the key
		return this.codec.decode(this.keys[index]);
	}

	@Override
	public void insertKeyAt(int index, @Nonnull M value) {
		if (this.size == this.keys.length) {
			growAndInsertKeyAt(index, value);
			return;
		}
		System.arraycopy(this.keys, index, this.keys, index + 1, this.size - index);
		this.keys[index] = this.codec.encode(value);
		this.size++;
	}

	@Override
	@SuppressWarnings("unchecked")
	public void bulkLoad(@Nonnull Object[] keys, int count) {
		ColumnSizing.assertLoadFitsCapacity(count, this.capacity);
		// always a fresh array: the contract says this column is freshly allocated, and reusing the existing backing
		// would make this the one mutator in the family that writes into an array it did not allocate
		final long[] target = newArray(count);
		for (int i = 0; i < count; i++) {
			target[i] = this.codec.encode((M) keys[i]);
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
		this.keys[this.size] = 0L;
	}

	@Override
	public void clearAt(int index) {
		if (index < this.size) {
			Arrays.fill(this.keys, index, this.size, 0L);
			this.size = index;
		}
	}

	@Override
	public void copyRangeTo(int srcPos, @Nonnull ValueColumn<M> dst, int dstPos, int length) {
		assertSourceRangeIsLive(srcPos, length);
		final LongValueColumn<M> target = asSameKind(dst);
		final int oldSize = target.size;
		final int required = dstPos + length;
		target.ensurePhysicalLength(required);
		if (dstPos > oldSize) {
			// a right shift opens a hole between the destination's old live end and dstPos; it must read as empty
			Arrays.fill(target.keys, oldSize, dstPos, 0L);
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
		if (srcPos < 0 || srcPos + length > this.size) {
			throwSourceRangeNotLive(srcPos, length);
		}
	}

	/**
	 * Builds and throws the out-of-range report. Kept out of {@link #assertSourceRangeIsLive} so the check itself is
	 * a pair of integer compares that allocates nothing: it runs on every range copy, and `createLayer()` performs one
	 * per column on the first transactional touch of every leaf, so a message supplier here would allocate thousands
	 * of objects per commit for a check that never fails.
	 *
	 * @param srcPos the start index the caller was reading from
	 * @param length the number of keys the caller was reading
	 */
	private void throwSourceRangeNotLive(int srcPos, int length) {
		throw new GenericEvitaInternalError(
			"Key column source range [" + srcPos + ", " + (srcPos + length) + ") runs past its live run ("
				+ this.size + ") — a key column has no empty key to substitute."
		);
	}

	@Override
	public void fillEmpty(int fromInclusive, int toExclusive) {
		if (fromInclusive < this.size) {
			Arrays.fill(this.keys, fromInclusive, this.size, 0L);
			this.size = fromInclusive;
		}
	}

	@Nonnull
	@Override
	public InsertionPosition findKeyPosition(@Nonnull M key, int from, int to, @Nullable Comparator<M> comparator) {
		// the comparator is intentionally ignored: the factory only selects this column for natural-order trees, and the
		// codec is monotonic, so the long order is identical to the comparator order; the probe is boxed-encoded once
		final long probe = this.codec.encode(key);
		return computeInsertPositionOfLongInOrderedArray(probe, this.keys, from, to);
	}

	@Override
	public void appendKey(@Nonnull StringBuilder sb, int index) {
		sb.append(keyAt(index));
	}

	@Nonnull
	@Override
	@SuppressWarnings("unchecked")
	public M[] asBoxedArray() {
		// cold path only (consistency verification / toString) — never the query hot path; the live run is the whole
		// array, which satisfies the interface's "length >= size, tail empty" contract exactly
		final M[] boxed = (M[]) Array.newInstance(this.codec.type(), this.size);
		for (int i = 0; i < this.size; i++) {
			boxed[i] = this.codec.decode(this.keys[i]);
		}
		return boxed;
	}

	@Override
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// `codec` addresses a LongKeyCodec enum constant - a JVM-wide singleton shared by every column of this key
		// type, so only the reference slot belongs here
		long size = layout.sizeOfObject(2L * layout.referenceSize() + 2L * Integer.BYTES);
		// an empty column parks on the JVM-wide shared empty array, which costs it nothing beyond the slot above
		if (this.keys != EMPTY_LONG_ARRAY) {
			size += layout.sizeOfArray(this.keys.length, Long.BYTES);
		}
		return size;
	}

	@Override
	public long getHeapSizeInBytes(@Nonnull ToLongFunction<? super M> elementSizer) {
		// keys are `long` values inside the array, decoded on demand - there is nothing for the sizer to price
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
		this.keys[index] = this.codec.encode(value);
		this.size++;
	}

	/**
	 * Allocates a backing array of the given length, keeping a zero length on the shared empty array.
	 *
	 * @param length the array length
	 * @return the fresh array
	 */
	@Nonnull
	private static long[] newArray(int length) {
		return length == 0 ? EMPTY_LONG_ARRAY : new long[length];
	}

	/**
	 * Narrows a sibling column to the same concrete kind (one attribute index = one value type ⇒ always holds).
	 *
	 * @param other the sibling column
	 * @return {@code other} as a {@link LongValueColumn}
	 */
	@Nonnull
	private LongValueColumn<M> asSameKind(@Nonnull ValueColumn<M> other) {
		if (other instanceof LongValueColumn<M> primitive) {
			return primitive;
		}
		throw new IllegalArgumentException(
			"Cannot mix value column kinds within one tree: " + other.getClass().getName());
	}
}
