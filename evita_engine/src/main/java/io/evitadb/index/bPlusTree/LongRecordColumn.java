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

import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;

import java.util.Arrays;

/**
 * Primitive {@link RecordColumn} backed by a {@code long[]} — the 8-byte single-record column that backs the
 * global-unique value→entity tree, where each bucket's payload is a packed {@code (entityType, pk)} long (see
 * {@link io.evitadb.utils.NumberUtils#pack(int, int)}). Because that tree is genuinely unique (one entity per value),
 * the bucket is never promoted to the overflow bitmap, so this column always holds the live payload.
 *
 * Zero-allocation invariant: every mutation / read operates directly on the primitive array, mirroring
 * {@link IntRecordColumn}. {@link #intAt} narrows to the low 32 bits — valid only where the caller knows the payload was
 * a 32-bit value (the int-record tree never selects this column), so a global-unique tree reads its payload via
 * {@link #longAt}.
 */
final class LongRecordColumn implements RecordColumn {
	/**
	 * The primitive single-record backing array.
	 */
	@Nonnull private final long[] records;

	/**
	 * Creates a column wrapping the given backing array (allocate / duplicate / split paths).
	 *
	 * @param records the backing array to adopt
	 */
	LongRecordColumn(@Nonnull long[] records) {
		this.records = records;
	}

	@Override
	public int capacity() {
		return this.records.length;
	}

	@Nonnull
	@Override
	public RecordColumn allocate(int capacity) {
		return new LongRecordColumn(new long[capacity]);
	}

	@Nonnull
	@Override
	public RecordColumn duplicate() {
		return new LongRecordColumn(this.records.clone());
	}

	@Override
	public int intAt(int index) {
		return (int) this.records[index];
	}

	@Override
	public long longAt(int index) {
		return this.records[index];
	}

	@Override
	public void insertAt(int index, long value) {
		System.arraycopy(this.records, index, this.records, index + 1, this.records.length - index - 1);
		this.records[index] = value;
	}

	@Override
	public void bulkLoad(@Nonnull long[] payloads, int count) {
		System.arraycopy(payloads, 0, this.records, 0, count);
	}

	@Override
	public void setAt(int index, long value) {
		this.records[index] = value;
	}

	@Override
	public void removeAt(int index) {
		System.arraycopy(this.records, index + 1, this.records, index, this.records.length - index - 1);
	}

	@Override
	public void clearAt(int index) {
		this.records[index] = 0L;
	}

	@Override
	public void copyRangeTo(int srcPos, @Nonnull RecordColumn dst, int dstPos, int length) {
		System.arraycopy(this.records, srcPos, asSameKind(dst).records, dstPos, length);
	}

	@Override
	public void fillEmpty(int fromInclusive, int toExclusive) {
		Arrays.fill(this.records, fromInclusive, toExclusive, 0L);
	}

	@Override
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		return layout.sizeOfObject(layout.referenceSize())
			+ layout.sizeOfArray(this.records.length, Long.BYTES);
	}

	/**
	 * Narrows a sibling column to the same concrete kind (one tree = one payload width ⇒ always holds).
	 *
	 * @param other the sibling column
	 * @return {@code other} as a {@link LongRecordColumn}
	 */
	@Nonnull
	private static LongRecordColumn asSameKind(@Nonnull RecordColumn other) {
		if (other instanceof LongRecordColumn primitive) {
			return primitive;
		}
		throw new IllegalArgumentException(
			"Cannot mix record column kinds within one tree: " + other.getClass().getName());
	}
}
