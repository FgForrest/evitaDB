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

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;

import java.util.Arrays;

import static io.evitadb.utils.ArrayUtils.EMPTY_LONG_ARRAY;

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
 *
 * The backing array follows the live content rather than the leaf block size: an empty column parks on the JVM-wide
 * {@code ArrayUtils.EMPTY_LONG_ARRAY} and owns nothing, the first write allocates
 * {@code ColumnSizing.MIN_PHYSICAL_LENGTH} slots, and growth doubles up to {@link #capacity()}. See
 * {@link RecordColumn} for the contract that follows from it.
 */
final class LongRecordColumn implements RecordColumn {
	/**
	 * The logical capacity — the leaf block size, fixed for the column's lifetime. See {@link #capacity()}.
	 */
	private final int capacity;
	/**
	 * The number of materialized records held in {@link #records}. Slots in {@code [size, capacity)} read as `0`.
	 */
	private int size;
	/**
	 * The primitive single-record backing array, sized to the live content rather than to {@link #capacity}. Slots in
	 * {@code [size, records.length)} are always zero.
	 */
	@Nonnull private long[] records;

	/**
	 * Creates an empty column for a leaf of the given block size. No backing storage is allocated until the first
	 * write — the array field parks on the JVM-wide shared empty array.
	 *
	 * @param capacity the logical capacity (the leaf block size)
	 */
	LongRecordColumn(int capacity) {
		this.capacity = capacity;
		this.size = 0;
		this.records = EMPTY_LONG_ARRAY;
	}

	/**
	 * Internal constructor adopting pre-built state (duplicate / trim paths).
	 *
	 * @param capacity the logical capacity
	 * @param size     the materialized record count
	 * @param records  the backing array to adopt
	 */
	private LongRecordColumn(int capacity, int size, @Nonnull long[] records) {
		this.capacity = capacity;
		this.size = size;
		this.records = records;
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
		return Math.min(this.size, this.records.length);
	}

	@Nonnull
	@Override
	public RecordColumn allocate(int capacity) {
		return new LongRecordColumn(capacity);
	}

	@Nonnull
	@Override
	public RecordColumn trimmed() {
		final int target = ColumnSizing.trimmedLength(this.size, this.records.length, this.capacity);
		if (target == this.records.length) {
			return this;
		}
		return new LongRecordColumn(this.capacity, this.size, Arrays.copyOf(this.records, target));
	}

	@Nonnull
	@Override
	public RecordColumn duplicate() {
		// an empty column keeps the shared empty array rather than cloning it into a private zero-length one - the
		// clone would cost an object header and break the shared-array identity every heap walk subtracts
		return new LongRecordColumn(
			this.capacity, this.size, this.records.length == 0 ? this.records : this.records.clone()
		);
	}

	@Nonnull
	@Override
	public RecordColumn duplicateForInsert() {
		// an empty column keeps the shared empty array, exactly as `duplicate()` does — `headroomLength` answers the
		// source's own length for it, and `Arrays.copyOf` of a zero-length array would allocate a private one
		final int target = ColumnSizing.headroomLength(this.size, this.records.length, this.capacity);
		return new LongRecordColumn(
			this.capacity, this.size, this.records.length == 0 ? this.records : Arrays.copyOf(this.records, target)
		);
	}

	@Override
	public int intAt(int index) {
		return index < this.size ? (int) this.records[index] : (int) emptySlotAt(index);
	}

	@Override
	public long longAt(int index) {
		return index < this.size ? this.records[index] : emptySlotAt(index);
	}

	@Override
	public void insertAt(int index, long value) {
		final int liveSize = this.size;
		if (index >= liveSize) {
			// inserting into the zero-valued tail: shifting zeroes right changes nothing, so this is a plain write
			setAt(index, value);
			return;
		}
		if (liveSize == this.records.length) {
			growAndInsertAt(index, value);
			return;
		}
		System.arraycopy(this.records, index, this.records, index + 1, liveSize - index);
		this.records[index] = value;
		this.size = liveSize + 1;
	}

	@Override
	public void bulkLoad(@Nonnull long[] payloads, int count) {
		ColumnSizing.assertLoadFitsCapacity(count, this.capacity);
		// always a fresh array: the contract says this column is freshly allocated, and reusing the existing backing
		// would make this the one mutator in the family that writes into an array it did not allocate
		final long[] target = newArray(count);
		System.arraycopy(payloads, 0, target, 0, count);
		this.records = target;
		this.size = count;
	}

	@Override
	public void setAt(int index, long value) {
		if (index < this.size) {
			this.records[index] = value;
			return;
		}
		materializeAndSetAt(index, value);
	}

	@Override
	public void removeAt(int index) {
		if (index >= this.size) {
			// the run past `size` is already zero - dropping one zero out of it leaves it zero
			return;
		}
		System.arraycopy(this.records, index + 1, this.records, index, this.size - index - 1);
		this.size--;
		this.records[this.size] = 0L;
	}

	@Override
	public void clearAt(int index) {
		if (index < this.size) {
			Arrays.fill(this.records, index, this.size, 0L);
			this.size = index;
		}
	}

	@Override
	public void copyRangeTo(int srcPos, @Nonnull RecordColumn dst, int dstPos, int length) {
		assertSourceRangeIsLive(srcPos, length);
		final LongRecordColumn target = asSameKind(dst);
		final int oldSize = target.size;
		final int required = dstPos + length;
		target.ensurePhysicalLength(required);
		if (dstPos > oldSize) {
			// a right shift opens a hole between the destination's old live end and dstPos; it must read as zero
			Arrays.fill(target.records, oldSize, dstPos, 0L);
		}
		System.arraycopy(this.records, srcPos, target.records, dstPos, length);
		target.size = Math.max(oldSize, required);
	}

	/**
	 * Refuses a source range that reaches past this column's live run, exactly as the key columns do.
	 *
	 * This family used to absorb the violation instead, copying zeroes for the slots beyond the run — legitimate for
	 * one live state, a value id column attached to an already-populated page and not yet back-filled, which was a
	 * legal steal / merge donor. That state no longer exists: every id column is created sized to the leaf it joins.
	 * With the producer gone the tolerance only hides caller bugs, and a record column silently copying zeroes over a
	 * neighbour's payloads is exactly as wrong as a key column copying empty keys would be.
	 *
	 * @param srcPos the start index the caller is reading from
	 * @param length the number of records the caller is reading
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
	 * @param length the number of records the caller was reading
	 */
	private void throwSourceRangeNotLive(int srcPos, int length) {
		throw new GenericEvitaInternalError(
			"Record column source range [" + srcPos + ", " + (srcPos + length) + ") runs past its live run ("
				+ this.size + ")!"
		);
	}

	@Override
	public void fillEmpty(int fromInclusive, int toExclusive) {
		if (fromInclusive < this.size) {
			Arrays.fill(this.records, fromInclusive, this.size, 0L);
			this.size = fromInclusive;
		}
	}

	@Override
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		long size = layout.sizeOfObject(layout.referenceSize() + 2L * Integer.BYTES);
		// an empty column parks on the JVM-wide shared empty array, which costs it nothing beyond the slot above
		if (this.records != EMPTY_LONG_ARRAY) {
			size += layout.sizeOfArray(this.records.length, Long.BYTES);
		}
		return size;
	}

	/**
	 * Answers a read of a slot that has never been materialized. Such a slot has always held `0` — the fixed arrays
	 * this column replaced were allocated zero-filled at the block size — so the read is legitimate right up to
	 * {@link #capacity()}, and only beyond it is a programming error.
	 *
	 * @param index the unmaterialized slot being read
	 * @return always `0`
	 */
	private long emptySlotAt(int index) {
		Assert.isPremiseValid(
			index < this.capacity,
			() -> "Slot " + index + " lies past this record column's logical capacity (" + this.capacity + ")!"
		);
		return 0L;
	}

	/**
	 * Reallocates {@link #records} so it holds at least {@code requiredLength} slots, carrying the live records
	 * across. Kept out of the mutators so their steady-state path stays a single field compare — the cursor-free
	 * insert path's escape analysis depends on that path staying small.
	 *
	 * **This can run against a column other threads are reading, and only one caller makes that possible.** The
	 * leaf's {@code createLayer()} passes its own columns as both origin and target of the split-copy constructor,
	 * so the self-copy {@code copyRangeTo(0, self, 0, peek + 1)} lands on the **committed** column. While
	 * {@code size == peek + 1} that call is inert — nothing grows and the size is reassigned to itself. When
	 * {@code size < peek + 1} it is not: this method publishes a new array into the field and the caller then
	 * raises {@code size}, two plain unordered stores on an object a query thread may be reading, so a reader that
	 * observes the new size against the old array reference throws {@link ArrayIndexOutOfBoundsException}. The old
	 * fixed arrays wrote identical values into an already-sized array and were benign under the same race.
	 *
	 * **That producer no longer exists.** The one state that could yield {@code size < peek + 1} was a never-sized
	 * value id column attached to an already-populated bulk-loaded page; every id column is now created sized to the
	 * leaf it joins, so the self-copy always finds the run it expects. The split-copy constructor also refuses a
	 * misaligned source *before* its first copy, so the race described above is unreachable rather than merely
	 * unreached — see `BPlusLeafTreeNode.assertSelfCopySourceIsAligned`. The description stays because it is the
	 * reason this method must never be made to grow a column a reader may hold.
	 *
	 * @param requiredLength the number of slots the caller is about to address
	 */
	private void ensurePhysicalLength(int requiredLength) {
		if (requiredLength > this.records.length) {
			this.records = Arrays.copyOf(
				this.records, ColumnSizing.grownLength(this.records.length, requiredLength, this.capacity)
			);
		}
	}

	/**
	 * The out-of-line half of {@link #insertAt}: grows the backing array, then performs the very same shift-and-set
	 * the fast path performs.
	 *
	 * @param index the insertion position
	 * @param value the record to insert
	 */
	private void growAndInsertAt(int index, long value) {
		ensurePhysicalLength(this.size + 1);
		System.arraycopy(this.records, index, this.records, index + 1, this.size - index);
		this.records[index] = value;
		this.size++;
	}

	/**
	 * The out-of-line half of {@link #setAt}: materializes the slots up to {@code index}, leaving the gap zeroed, then
	 * writes the value and extends the live run to cover it.
	 *
	 * @param index the slot to write
	 * @param value the record to store
	 */
	private void materializeAndSetAt(int index, long value) {
		ensurePhysicalLength(index + 1);
		// the gap between the old live end and `index` is already zero - `ensurePhysicalLength` copies into a
		// zero-filled array and every mutator clears what it releases
		this.records[index] = value;
		this.size = index + 1;
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
