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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;

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
 * Selected only when the tree comparator is natural order and the key type routes to an {@code int[]} column (see
 * {@link ValueColumnFactory}); otherwise the leaf keeps the universal {@link BoxedObjectColumn}.
 *
 * @param <M> the boxed key type as seen by the tree's generic API (always {@link Integer} in practice)
 */
final class IntValueColumn<M extends Comparable<M>> implements ValueColumn<M> {
	/**
	 * The primitive key backing array.
	 */
	@Nonnull private final int[] keys;

	/**
	 * Creates a column wrapping the given backing array (allocate / duplicate / split paths).
	 *
	 * @param keys the backing array to adopt
	 */
	IntValueColumn(@Nonnull int[] keys) {
		this.keys = keys;
	}

	@Override
	public int capacity() {
		return this.keys.length;
	}

	@Nonnull
	@Override
	public ValueColumn<M> allocate(int capacity) {
		return new IntValueColumn<>(new int[capacity]);
	}

	@Nonnull
	@Override
	public ValueColumn<M> duplicate() {
		return new IntValueColumn<>(this.keys.clone());
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
		System.arraycopy(this.keys, index, this.keys, index + 1, this.keys.length - index - 1);
		this.keys[index] = (Integer) value;
	}

	@Override
	public void bulkLoad(@Nonnull Object[] keys, int count) {
		for (int i = 0; i < count; i++) {
			this.keys[i] = (Integer) keys[i];
		}
	}

	@Override
	public void removeKeyAt(int index) {
		System.arraycopy(this.keys, index + 1, this.keys, index, this.keys.length - index - 1);
	}

	@Override
	public void clearAt(int index) {
		this.keys[index] = 0;
	}

	@Override
	public void copyRangeTo(int srcPos, @Nonnull ValueColumn<M> dst, int dstPos, int length) {
		System.arraycopy(this.keys, srcPos, asSameKind(dst).keys, dstPos, length);
	}

	@Override
	public void fillEmpty(int fromInclusive, int toExclusive) {
		Arrays.fill(this.keys, fromInclusive, toExclusive, 0);
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
		// cold path only (consistency verification / toString) — never the query hot path
		final M[] boxed = (M[]) new Integer[this.keys.length];
		for (int i = 0; i < this.keys.length; i++) {
			boxed[i] = (M) Integer.valueOf(this.keys[i]);
		}
		return boxed;
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
