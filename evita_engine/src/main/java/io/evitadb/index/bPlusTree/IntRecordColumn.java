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

package io.evitadb.index.bPlusTree;

import javax.annotation.Nonnull;

import java.util.Arrays;

/**
 * Primitive {@link RecordColumn} backed by an {@code int[]} — the 4-byte default single-record column that backs the
 * inverted and owner-unique indexes. It is byte-for-byte the representation the leaf used before the column abstraction
 * was introduced (a bare {@code int[]}), wrapped in a thin column object: the storage footprint per record is unchanged
 * (4 bytes), the only delta being one wrapper object per leaf — exactly as the key column already incurs.
 *
 * Zero-allocation invariant: every mutation / read operates directly on the primitive array. {@link #longAt} widens the
 * {@code int} sign-preservingly (free) and {@link #insertAt} narrows the {@code long} back to {@code int} (the int-record
 * tree only ever stores 32-bit pks), so no boxing ever occurs on a hot path.
 */
final class IntRecordColumn implements RecordColumn {
	/**
	 * The primitive single-record backing array.
	 */
	@Nonnull private final int[] records;

	/**
	 * Creates a column wrapping the given backing array (allocate / duplicate / split paths).
	 *
	 * @param records the backing array to adopt
	 */
	IntRecordColumn(@Nonnull int[] records) {
		this.records = records;
	}

	@Override
	public int capacity() {
		return this.records.length;
	}

	@Nonnull
	@Override
	public RecordColumn allocate(int capacity) {
		return new IntRecordColumn(new int[capacity]);
	}

	@Nonnull
	@Override
	public RecordColumn duplicate() {
		return new IntRecordColumn(this.records.clone());
	}

	@Override
	public int intAt(int index) {
		return this.records[index];
	}

	@Override
	public long longAt(int index) {
		return this.records[index];
	}

	@Override
	public void insertAt(int index, long value) {
		System.arraycopy(this.records, index, this.records, index + 1, this.records.length - index - 1);
		this.records[index] = (int) value;
	}

	@Override
	public void bulkLoad(@Nonnull long[] payloads, int count) {
		for (int i = 0; i < count; i++) {
			this.records[i] = (int) payloads[i];
		}
	}

	@Override
	public void setAt(int index, long value) {
		this.records[index] = (int) value;
	}

	@Override
	public void removeAt(int index) {
		System.arraycopy(this.records, index + 1, this.records, index, this.records.length - index - 1);
	}

	@Override
	public void clearAt(int index) {
		this.records[index] = 0;
	}

	@Override
	public void copyRangeTo(int srcPos, @Nonnull RecordColumn dst, int dstPos, int length) {
		System.arraycopy(this.records, srcPos, asSameKind(dst).records, dstPos, length);
	}

	@Override
	public void fillEmpty(int fromInclusive, int toExclusive) {
		Arrays.fill(this.records, fromInclusive, toExclusive, 0);
	}

	/**
	 * Narrows a sibling column to the same concrete kind (one tree = one payload width ⇒ always holds).
	 *
	 * @param other the sibling column
	 * @return {@code other} as an {@link IntRecordColumn}
	 */
	@Nonnull
	private static IntRecordColumn asSameKind(@Nonnull RecordColumn other) {
		if (other instanceof IntRecordColumn primitive) {
			return primitive;
		}
		throw new IllegalArgumentException(
			"Cannot mix record column kinds within one tree: " + other.getClass().getName());
	}
}
