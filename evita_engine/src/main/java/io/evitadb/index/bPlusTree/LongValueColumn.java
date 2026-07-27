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
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Comparator;

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
	 * The primitive key backing array.
	 */
	@Nonnull private final long[] keys;

	/**
	 * Creates a column wrapping the given codec and backing array (allocate / duplicate / split paths).
	 *
	 * @param codec the order-preserving codec
	 * @param keys  the backing array to adopt
	 */
	LongValueColumn(@Nonnull LongKeyCodec codec, @Nonnull long[] keys) {
		this.codec = codec;
		this.keys = keys;
	}

	@Override
	public int capacity() {
		return this.keys.length;
	}

	@Nonnull
	@Override
	public ValueColumn<M> allocate(int capacity) {
		return new LongValueColumn<>(this.codec, new long[capacity]);
	}

	@Nonnull
	@Override
	public ValueColumn<M> duplicate() {
		return new LongValueColumn<>(this.codec, this.keys.clone());
	}

	@Nonnull
	@Override
	public M keyAt(int index) {
		// boxing boundary — decoded exactly where the boxed leaf would have materialized the key
		return this.codec.decode(this.keys[index]);
	}

	@Override
	public void insertKeyAt(int index, @Nonnull M value) {
		System.arraycopy(this.keys, index, this.keys, index + 1, this.keys.length - index - 1);
		this.keys[index] = this.codec.encode(value);
	}

	@Override
	@SuppressWarnings("unchecked")
	public void bulkLoad(@Nonnull Object[] keys, int count) {
		for (int i = 0; i < count; i++) {
			this.keys[i] = this.codec.encode((M) keys[i]);
		}
	}

	@Override
	public void removeKeyAt(int index) {
		System.arraycopy(this.keys, index + 1, this.keys, index, this.keys.length - index - 1);
	}

	@Override
	public void clearAt(int index) {
		this.keys[index] = 0L;
	}

	@Override
	public void copyRangeTo(int srcPos, @Nonnull ValueColumn<M> dst, int dstPos, int length) {
		System.arraycopy(this.keys, srcPos, asSameKind(dst).keys, dstPos, length);
	}

	@Override
	public void fillEmpty(int fromInclusive, int toExclusive) {
		Arrays.fill(this.keys, fromInclusive, toExclusive, 0L);
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
		// cold path only (consistency verification / toString) — never the query hot path
		final M[] boxed = (M[]) Array.newInstance(this.codec.type(), this.keys.length);
		for (int i = 0; i < this.keys.length; i++) {
			boxed[i] = this.codec.decode(this.keys[i]);
		}
		return boxed;
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
