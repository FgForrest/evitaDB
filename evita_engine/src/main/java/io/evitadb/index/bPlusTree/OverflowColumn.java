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
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.utils.Assert;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Arrays;

/**
 * The lazy **multi-record** column of a {@link TransactionalBucketBPlusTree} leaf: one record-set reference per
 * bucket, `null` at every bucket that still holds its single record in the primitive {@link RecordColumn}. The
 * presence of a record set at a slot **is** the leaf's single/multi discriminator, so this column is the third
 * parallel column of the leaf and moves in lockstep with the key and record columns through every insert, delete,
 * split, steal and merge.
 *
 * ## A slot is an untyped reference, and what may legally sit in it
 *
 * A live slot holds either a sorted, immutable `int[]` (the small-bucket tier) or a {@link TransactionalBitmap} (the
 * large one); {@link OverflowRecords} owns the whole state machine that decides which, and every operation on a slot's
 * content. This column is deliberately ignorant of the distinction — it moves references and nothing else.
 *
 * The slots are typed `Object` rather than a sealed interface because an `int[]` cannot implement one, and wrapping
 * every small bucket to give it a type would add a header and an indirection to hundreds of thousands of buckets. See
 * {@link OverflowRecords} for the full argument.
 *
 * ## Why it is a class and not another member of the sealed family
 *
 * {@link ValueColumn} and {@link RecordColumn} are sealed interfaces because their storage has several genuinely
 * different physical shapes (boxed vs. primitive keys, 4-byte vs. 8-byte payloads) that the tree picks between at
 * construction. The overflow column has exactly one: an array of references. A second type would buy nothing, so this
 * is a plain final class carrying the same contract the two interfaces state.
 *
 * ## Logical capacity, physical backing
 *
 * Exactly as for the other two columns: {@link #capacity()} is the **logical** leaf block size and never moves, while
 * the backing array follows the live content — the first materialization allocates
 * {@code ColumnSizing.MIN_PHYSICAL_LENGTH} slots and growth doubles up to the logical capacity.
 *
 * **The column is a logical run of {@code capacity()} `null` slots of which the first {@link #size()} are
 * materialized.** Reading, clearing or removing a slot past the live run answers `null` rather than throwing, which is
 * exactly what the fixed, null-filled array this type replaced answered. Only an index at or beyond
 * {@link #capacity()} is a programming error.
 *
 * {@link #size()} is normally the owning leaf's {@code peek + 1}; see {@link ValueColumn} for the transient windows in
 * which a leaf mutation makes the two disagree.
 *
 * ## The memento contract — the clone is deliberately SHALLOW
 *
 * {@link #duplicate()} copies the **array**, never the record sets in it, and that is not an optimization but the
 * transactional contract. It holds for both tiers, for two different reasons.
 *
 * A {@link TransactionalBitmap} is itself a transactional structure owning its own diff layer and its own savepoint
 * memento, so a leaf-level snapshot only has to remember *which slot points at which bitmap*; the bitmaps snapshot
 * and restore themselves. Deep-copying them here would produce a second, detached instance whose later mutations the
 * commit sweep would never see — and would double-discard the layer of the one it replaced.
 *
 * An `int[]` slot is never written in place ({@link OverflowRecords} returns a new array for every content change),
 * so the pre-image a memento has to preserve is the *reference*, and copying the reference array preserves it
 * exactly. A deep copy would preserve nothing extra and would cost one array allocation per multi bucket per
 * snapshot.
 *
 * The same shallow contract serves the MVCC decouple of a transactional layer, where the layer must be free to null a
 * slot or point it at a freshly promoted record set without disturbing the committed leaf, while a record set both of
 * them still reference stays exactly one object.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class OverflowColumn {
	/**
	 * The logical capacity — the leaf block size, fixed for the column's lifetime. See {@link #capacity()}.
	 */
	private final int capacity;
	/**
	 * The number of materialized slots held in {@link #recordSets}. Slots in {@code [size, capacity)} read as `null`.
	 */
	private int size;
	/**
	 * The record-set reference backing array, sized to the live content rather than to {@link #capacity}. Slots in
	 * {@code [size, recordSets.length)} are always `null`.
	 */
	@Nonnull private Object[] recordSets;

	/**
	 * Creates an empty column for a leaf of the given block size.
	 *
	 * **Unlike its two sibling column families this one does not park on a JVM-wide shared empty array**, and the
	 * asymmetry is deliberate. A leaf allocates its key and record columns unconditionally, so the great majority of
	 * them are genuinely empty and a shared array saves a real object each time; an overflow column is allocated only
	 * once the leaf has a multi-record bucket to put in it, so the empty state lasts exactly as long as the statement
	 * that fills it. Owning its array from the start keeps {@link #getHeapSizeInBytes()} exactly equal to what a JOL
	 * walk measures, without every heap test in the codebase having to learn to subtract one more shared root.
	 *
	 * @param capacity the logical capacity (the leaf block size)
	 */
	OverflowColumn(int capacity) {
		this.capacity = capacity;
		this.size = 0;
		this.recordSets = new Object[Math.min(ColumnSizing.MIN_PHYSICAL_LENGTH, capacity)];
	}

	/**
	 * Internal constructor adopting pre-built state (duplicate / trim paths).
	 *
	 * @param capacity   the logical capacity
	 * @param size       the materialized slot count
	 * @param recordSets the backing array to adopt
	 */
	private OverflowColumn(int capacity, int size, @Nonnull Object[] recordSets) {
		this.capacity = capacity;
		this.size = size;
		this.recordSets = recordSets;
	}

	/**
	 * Creates a column whose live run already covers {@code size} slots, all of them `null`. This is how a leaf
	 * promotes its first multi bucket: the column has to arrive already aligned with the key and record columns, so
	 * that the very next {@link #setAt} / {@link #insertAt} lands on the same slot the sibling columns use.
	 *
	 * @param capacity the logical capacity (the leaf block size)
	 * @param size     the live run to materialize, all `null`
	 * @return the aligned, all-null column
	 */
	@Nonnull
	static OverflowColumn withLiveRun(int capacity, int size) {
		final OverflowColumn column = new OverflowColumn(capacity);
		column.ensurePhysicalLength(size);
		column.size = size;
		return column;
	}

	/**
	 * Returns the **logical** capacity — the leaf block size this column was created with, which no mutation ever
	 * changes. The physical backing array is usually shorter; slots in {@code [size(), capacity())} are unused and
	 * read as `null`.
	 *
	 * @return the logical capacity (the leaf block size)
	 */
	int capacity() {
		return this.capacity;
	}

	/**
	 * Returns the number of materialized slots in this column — normally equal to the owning leaf's {@code peek + 1}.
	 * Everything from here to {@link #capacity()} reads as `null`.
	 *
	 * @return the materialized slot count
	 */
	int size() {
		return this.size;
	}

	/**
	 * Returns the live run a reader holding **no happens-before edge** to the writer may bound itself by:
	 * {@code min(size(), backing array length)}. See {@link ValueColumn#observableLiveRun()} for the whole argument —
	 * it holds here verbatim, because this column grows exactly the way the key and record columns do.
	 *
	 * @return the live run that is safe to index without synchronization
	 */
	int observableLiveRun() {
		return Math.min(this.size, this.recordSets.length);
	}

	/**
	 * Returns a column holding the same record-set references with its physical backing shrunk to the live content, or
	 * {@code this} when the slack does not justify the copy. See {@link ValueColumn#trimmed()} for why it belongs at
	 * the commit merge and nowhere else.
	 *
	 * The shrunk copy shares the record sets with this column, for the reason {@link #duplicate()} states.
	 *
	 * @return a shrunk copy, or {@code this} when no shrink is warranted
	 */
	@Nonnull
	OverflowColumn trimmed() {
		final int target = ColumnSizing.trimmedLength(this.size, this.recordSets.length, this.capacity);
		if (target == this.recordSets.length) {
			return this;
		}
		return new OverflowColumn(this.capacity, this.size, Arrays.copyOf(this.recordSets, target));
	}

	/**
	 * Creates a copy of this column with an independent backing array but the **same** record-set instances in it. Used to
	 * decouple a transactional layer's overflow column from the shared base on first write, and to snapshot it into a
	 * savepoint memento.
	 *
	 * **The clone is shallow by contract, not by omission** — see the type javadoc: a {@link TransactionalBitmap} owns
	 * its own diff layer and its own memento, and an `int[]` slot is never written in place, so the leaf must remember
	 * only which slot points at which record set.
	 *
	 * **The copy keeps the source's physical length verbatim and never trims it** (use {@link #trimmed()} for that): a
	 * decoupled layer is about to be written, and a memento has to be a faithful pre-image so a rollback does not
	 * change the leaf's physical shape as a side effect.
	 *
	 * @return a shallow copy of this column
	 */
	@Nonnull
	OverflowColumn duplicate() {
		return new OverflowColumn(this.capacity, this.size, this.recordSets.clone());
	}

	/**
	 * The MVCC decouple's variant of {@link #duplicate()}, for the case where the layer's very first act on the copy
	 * will be an **insert**: the same shallow copy, but a column whose live run exactly fills its backing array is
	 * copied straight to the length its next insert would grow it to, so that insert lands in place. See
	 * {@link ValueColumn#duplicateForInsert()} for the measurement behind it and for the invariant that the savepoint
	 * memento must keep using {@link #duplicate()}.
	 *
	 * The record sets are shared with this column, for the reason {@link #duplicate()} states; the slots the extra length
	 * opens are `null`, which is what an unwritten overflow slot has always read as.
	 *
	 * @return a shallow copy of this column, sized to absorb one more slot without reallocating
	 */
	@Nonnull
	OverflowColumn duplicateForInsert() {
		return new OverflowColumn(
			this.capacity, this.size,
			Arrays.copyOf(this.recordSets, ColumnSizing.headroomLength(this.size, this.recordSets.length, this.capacity))
		);
	}

	/**
	 * Returns the multi-record record set held at the given slot — a sorted `int[]` or a {@link TransactionalBitmap},
	 * as {@link OverflowRecords} decides — or `null` when that bucket is still single.
	 *
	 * A slot at or beyond {@link #size()} but below {@link #capacity()} reads `null`, the value an unwritten slot has
	 * always held.
	 *
	 * The discriminating read is {@link #observableLiveRun()} rather than {@link #size()} alone, because the two
	 * fields it compares — the count and the backing array — are read independently, and this method is reached from
	 * a request thread holding no session (the leaf's heap-size walk and the bucket cursors). A slot that the raised
	 * count already claims but the older array cannot yet serve therefore reads as the `null` an unwritten slot has
	 * always held, instead of raising an {@link ArrayIndexOutOfBoundsException}. For any caller sharing a
	 * happens-before edge with the writer the bound is `size()` exactly, since a grow always materializes the array
	 * before it raises the count — see {@link ValueColumn#observableLiveRun()}.
	 *
	 * @param index the slot to read
	 * @return the bucket's record set, or `null` when the bucket is single
	 */
	@Nullable
	Object recordsAt(int index) {
		final Object[] theRecordSets = this.recordSets;
		return index < Math.min(this.size, theRecordSets.length) ? theRecordSets[index] : emptySlotAt(index);
	}

	/**
	 * Overwrites the slot at {@code index} in place, without shifting the tail (the leaf's {@code peek} is unchanged).
	 * Used to promote a single bucket to a multi one, and to demote it back at the commit merge by writing `null`.
	 *
	 * **Writing past the live run materializes it**: the backing array grows, the gap is null-filled and
	 * {@link #size()} becomes {@code index + 1}.
	 *
	 * @param index   the slot to overwrite
	 * @param records the bucket's record set - a sorted `int[]` or a {@link TransactionalBitmap} - or `null` to mark the
	 *                bucket single
	 */
	void setAt(int index, @Nullable Object records) {
		if (index >= this.size) {
			ensurePhysicalLength(index + 1);
			// the gap between the old live end and `index` is already null - the array grows out of a null-filled
			// allocation and every mutator nulls what it releases
			this.size = index + 1;
		}
		this.recordSets[index] = records;
	}

	/**
	 * Inserts {@code records} at {@code index}, shifting the live tail one slot to the right and raising {@link #size()}
	 * by one (the leaf grows {@code peek} afterwards). Passing `null` is how a newly inserted **single** bucket takes
	 * its place in a leaf that already carries multi buckets.
	 *
	 * **Grows the physical backing first when the live run already fills it**, so the caller never has to pre-size the
	 * column. Only the live tail moves — {@code size() - index} slots — never the whole block.
	 *
	 * @param index   the insertion position
	 * @param records the inserted bucket's record set, or `null` when the new bucket is single
	 */
	void insertAt(int index, @Nullable Object records) {
		final int liveSize = this.size;
		if (index >= liveSize) {
			// inserting into the null tail: shifting nulls right changes nothing, so this is a plain write
			setAt(index, records);
			return;
		}
		ensurePhysicalLength(liveSize + 1);
		System.arraycopy(this.recordSets, index, this.recordSets, index + 1, liveSize - index);
		this.recordSets[index] = records;
		this.size = liveSize + 1;
	}

	/**
	 * Bulk-populates this freshly created (empty) column from an already-known slice of record-set references — the
	 * load-time counterpart to {@code count} sequential {@link #insertAt} calls, used when a persisted leaf page is
	 * replayed and the full slot set is known up front.
	 *
	 * **Sizes the physical backing exactly to {@code count}** and sets {@link #size()} to it, so every persisted page
	 * lands at its exact footprint with no overshoot at all. A source shorter than {@code count} leaves the remaining
	 * slots `null`, which is what a page holding no multi bucket past that point means.
	 *
	 * @param recordSets the record-set references to load; only {@code recordSets[0, count)} are read
	 * @param count      the number of live slots ({@code <= capacity()})
	 */
	void bulkLoad(@Nonnull Object[] recordSets, int count) {
		ColumnSizing.assertLoadFitsCapacity(count, this.capacity);
		// always a fresh array: the contract says this column is freshly created, and reusing the existing backing
		// would make this the one mutator in the family that writes into an array it did not allocate
		final Object[] target = new Object[count];
		System.arraycopy(recordSets, 0, target, 0, Math.min(count, recordSets.length));
		this.recordSets = target;
		this.size = count;
	}

	/**
	 * Removes the slot at {@code index}, shifting the live tail one slot to the left and lowering {@link #size()} by
	 * one (the leaf clears the freed last slot via {@link #clearAt} and shrinks {@code peek} afterwards). The vacated
	 * slot is nulled, so no record-set reference survives past the live run and a moved record set is never aliased at
	 * two slots — the failure mode differs per tier: a leaked bitmap alias would be committed and discarded twice by
	 * the transactional merge sweep, while a leaked array alias would masquerade as a live multi bucket at a slot that
	 * must read `null`, breaking the single/multi discriminator.
	 *
	 * Removing a slot at or beyond {@link #size()} is a no-op rather than an error: that region is already `null`, and
	 * dropping one `null` out of a run of `null`s leaves a run of `null`s.
	 *
	 * @param index the slot to remove
	 */
	void removeAt(int index) {
		if (index >= this.size) {
			return;
		}
		System.arraycopy(this.recordSets, index + 1, this.recordSets, index, this.size - index - 1);
		this.size--;
		this.recordSets[this.size] = null;
	}

	/**
	 * Truncates the live run to {@code index}, nulling everything from there on — used to release the freed last slot
	 * after a delete, and reached with a still-live slot when a downward {@code setPeek} shortens a leaf.
	 *
	 * **Size-authoritative, so it is a strict no-op for {@code index >= size()}.** That is what makes it safe on the
	 * committed column a transactional layer still aliases.
	 *
	 * @param index the first slot to release
	 */
	void clearAt(int index) {
		if (index < this.size) {
			Arrays.fill(this.recordSets, index, this.size, null);
			this.size = index;
		}
	}

	/**
	 * Truncates the live run to {@code fromInclusive}, nulling everything from there on (truncated-tail cleanup on
	 * split / {@code setPeek}).
	 *
	 * **Size-authoritative: {@code toExclusive} bounds nothing and needs no relation to the physical length.** The
	 * leaf's split constructor passes {@link #capacity()} there, and {@code createLayer()} routes it onto the
	 * committed column, where the call has to be a harmless no-op rather than an out-of-bounds fill.
	 *
	 * @param fromInclusive the first slot to release (inclusive); a value at or beyond {@link #size()} is a no-op
	 * @param toExclusive   the caller's idea of where the released run ends; retained for call-site readability
	 */
	void fillEmpty(int fromInclusive, int toExclusive) {
		clearAt(fromInclusive);
	}

	/**
	 * Materializes {@code length} `null` slots starting at {@code dstPos}, growing the live run to cover them.
	 *
	 * This is the "donor carries no overflow column" half of the leaf's rebalancing: the receiver has multi buckets
	 * and the donor does not, so every donated bucket is single and its slot here must read `null`. The range cannot
	 * simply be left alone — the receiver has just shifted its own buckets aside with a copy rather than a move, so
	 * the vacated range still aliases the shifted-from record sets, and leaving one aliased at two slots hazards
	 * either tier — a bitmap alias would be committed and discarded twice by the transactional merge sweep, and an
	 * array alias would masquerade as a live multi bucket at a slot that must read `null`.
	 *
	 * **The range must start at or before the live end** ({@code dstPos <= size()}), so it either overwrites live
	 * slots or extends the run contiguously. Every rebalancing shape satisfies that: the receiver's column is created
	 * covering the buckets it already holds, and the donated range begins exactly where those end. A start past the
	 * live end is refused rather than absorbed, because absorbing it would raise {@link #size()} over a gap of slots
	 * no caller ever materialized — the leaf would report more buckets than its key column holds.
	 *
	 * @param dstPos the first slot to null; must not exceed {@link #size()}
	 * @param length the number of slots to null
	 */
	void fillNulls(int dstPos, int length) {
		if (dstPos > this.size) {
			throwFillRangeNotContiguous(dstPos, length);
		}
		final int required = dstPos + length;
		ensurePhysicalLength(required);
		Arrays.fill(this.recordSets, dstPos, required, null);
		this.size = Math.max(this.size, required);
	}

	/**
	 * Bulk lockstep move: copies {@code length} record-set references from {@code this[srcPos]} into {@code dst[dstPos]}
	 * (supports overlapping ranges when {@code dst == this}, like {@code System.arraycopy}).
	 *
	 * **Grows the destination to {@code dstPos + length} before any reference moves** and sets its {@link #size()} to
	 * {@code max(oldSize, dstPos + length)}, nulling anything between the destination's old live end and
	 * {@code dstPos}. The destination therefore never has to be pre-sized, and the in-place right shift the leaf's
	 * steal-from-left performs ({@code dst == this}) becomes a copy into a larger array rather than an overlapping
	 * move.
	 *
	 * **A source range reaching past {@code this.size()} is refused**, exactly as {@link ValueColumn#copyRangeTo} and
	 * {@link RecordColumn#copyRangeTo} refuse it. On this column absorbing the shortfall would be worse than on
	 * either of those: a `null` here is not an empty slot, it **is** the leaf's single/multi discriminator, so every
	 * multi bucket in the shortfall would arrive at the receiver marked single and keep one record out of its whole
	 * set. A donor whose overflow column is shorter than the range its key column donates is a caller bug, and the
	 * only way to keep it from silently dropping records is to report it.
	 *
	 * @param srcPos the start slot in this column
	 * @param dst    the destination column
	 * @param dstPos the start slot in the destination
	 * @param length the number of slots to copy
	 */
	void copyRangeTo(int srcPos, @Nonnull OverflowColumn dst, int dstPos, int length) {
		assertSourceRangeIsLive(srcPos, length);
		final int oldSize = dst.size;
		final int required = dstPos + length;
		dst.ensurePhysicalLength(required);
		if (dstPos > oldSize) {
			// a right shift opens a hole between the destination's old live end and dstPos; it must read as null
			Arrays.fill(dst.recordSets, oldSize, dstPos, null);
		}
		System.arraycopy(this.recordSets, srcPos, dst.recordSets, dstPos, length);
		dst.size = Math.max(oldSize, required);
	}

	/**
	 * Refuses a source range that reaches past this column's live run, the way the key and record column families
	 * refuse theirs. See {@link #copyRangeTo} for why absorbing it costs records rather than merely slots.
	 *
	 * @param srcPos the start slot the caller is reading from
	 * @param length the number of slots the caller is reading
	 */
	private void assertSourceRangeIsLive(int srcPos, int length) {
		if (srcPos < 0 || srcPos + length > this.size) {
			throwSourceRangeNotLive(srcPos, length);
		}
	}

	/**
	 * Builds and throws the out-of-range report. Kept out of {@link #assertSourceRangeIsLive} so the check itself is
	 * a pair of integer compares that allocates nothing: it runs on every range copy, and `createLayer()` performs
	 * one per column on the first transactional touch of every leaf, so a message supplier here would allocate
	 * thousands of objects per commit for a check that never fails.
	 *
	 * @param srcPos the start slot the caller was reading from
	 * @param length the number of slots the caller was reading
	 */
	private void throwSourceRangeNotLive(int srcPos, int length) {
		throw new GenericEvitaInternalError(
			"Overflow column source range [" + srcPos + ", " + (srcPos + length) + ") runs past its live run ("
				+ this.size + ") — the shortfall would demote every multi bucket in it to a single one."
		);
	}

	/**
	 * Builds and throws the non-contiguous fill report. Kept out of {@link #fillNulls} for the same reason
	 * {@link #throwSourceRangeNotLive} is kept out of {@link #assertSourceRangeIsLive}.
	 *
	 * @param dstPos the start slot the caller was filling from
	 * @param length the number of slots the caller was filling
	 */
	private void throwFillRangeNotContiguous(int dstPos, int length) {
		throw new GenericEvitaInternalError(
			"Overflow column fill range [" + dstPos + ", " + (dstPos + length) + ") starts past its live run ("
				+ this.size + ") — the slots in between would be counted live without ever being materialized."
		);
	}

	/**
	 * Returns the heap this column occupies in bytes, the record sets it points at **excluded** — they are charged by
	 * the leaf, which walks them one by one and asks {@link OverflowRecords} for each one's own footprint.
	 *
	 * The backing array is measured at its *allocated* length, which follows the live content rather than the leaf
	 * block size, so the figure moves as buckets are promoted and demoted. The array is always this column's own —
	 * see the constructor for why this family owns even its empty one.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		return layout.sizeOfObject(layout.referenceSize() + 2L * Integer.BYTES)
			+ layout.sizeOfArray(this.recordSets.length, layout.referenceSize());
	}

	/**
	 * Answers a read of a slot that has never been materialized. Such a slot has always held `null` — the fixed array
	 * this column replaced was allocated null-filled at the block size — so the read is legitimate right up to
	 * {@link #capacity()}, and only beyond it is a programming error.
	 *
	 * @param index the unmaterialized slot being read
	 * @return always `null`
	 */
	@Nullable
	private Object emptySlotAt(int index) {
		Assert.isPremiseValid(
			index < this.capacity,
			() -> "Slot " + index + " lies past this overflow column's logical capacity (" + this.capacity + ")!"
		);
		return null;
	}

	/**
	 * Reallocates {@link #recordSets} so it holds at least {@code requiredLength} slots, carrying the live references
	 * across. Kept out of the mutators so their steady-state path stays a single field compare.
	 *
	 * @param requiredLength the number of slots the caller is about to address
	 */
	private void ensurePhysicalLength(int requiredLength) {
		if (requiredLength > this.recordSets.length) {
			this.recordSets = Arrays.copyOf(
				this.recordSets, ColumnSizing.grownLength(this.recordSets.length, requiredLength, this.capacity)
			);
		}
	}
}
