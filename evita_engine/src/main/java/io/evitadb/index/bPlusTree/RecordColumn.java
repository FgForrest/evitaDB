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

/**
 * Pluggable single-record (payload) column of a {@link TransactionalBucketBPlusTree} leaf. It abstracts the leaf's
 * single-record column — the lone record id (pk) held per bucket when the bucket has not been promoted to the lazy
 * overflow {@link io.evitadb.index.bitmap.TransactionalBitmap} — so the leaf can store it in the cheapest **primitive**
 * representation: an {@link IntRecordColumn} ({@code int[]}, the 4-byte default that backs the inverted / owner-unique
 * indexes) or a {@link LongRecordColumn} ({@code long[]}, the 8-byte variant that backs the global-unique value→entity
 * tree, where the payload is a packed `(entityType, pk)` long).
 *
 * This is the primitive sibling of {@link ValueColumn} (which abstracts the **key** column). The two columns have
 * different contracts and are deliberately kept apart: the key column owns ordered search (`findKeyPosition`) over boxed
 * keys, whereas the payload column is purely **positional** — it moves in lockstep with the key column by index and is
 * never searched. Crucially, its mutation / read API is **primitive** (`long` at the boundary, widening an `int` for
 * free), so a record insert allocates **nothing**; routing the payload through the boxed {@link ValueColumn#insertKeyAt}
 * would box every pk on the hot `addRecord` path. The {@code long} boundary lets one interface serve both the 4-byte and
 * the 8-byte backing without a second type parameter — an {@link IntRecordColumn} narrows on write and widens on read.
 *
 * Design contract (so the abstraction adds **no** allocation over the raw {@code int[]} it replaces):
 *
 * - The column owns all bulk / single-slot moves ({@link #copyRangeTo}, {@link #insertAt}, {@link #removeAt},
 *   {@link #clearAt}, {@link #fillEmpty}) so the tree never touches the backing array directly on any hot path.
 * - MVCC copy-on-write mirrors the key column: {@link #duplicate} is a **deep** copy (new backing array, new identity)
 *   used to decouple a transactional layer on first write; the leaf shares the *same* column reference into its
 *   transactional layer so the `layer.payload == this.payload` reference check fires exactly once.
 * - {@link #copyRangeTo} assumes {@code dst} is the **same concrete kind** as this column — true within one tree (one
 *   index = one payload width); it is asserted defensively. It supports overlapping ranges when {@code dst == this}
 *   (like {@code System.arraycopy}).
 *
 * ## Logical capacity, physical backing
 *
 * Exactly as for {@link ValueColumn}: {@link #capacity()} is the **logical** leaf block size and never moves, while
 * the backing array is sized to the live content — an empty column allocates nothing, the first write allocates
 * {@code ColumnSizing.MIN_PHYSICAL_LENGTH} slots, and growth doubles up to the logical capacity.
 *
 * **A record column is a logical run of {@code capacity()} zero-valued slots of which the first {@link #size()} are
 * materialized.** Reading, clearing or removing a slot past the live run answers `0` rather than throwing, which is
 * exactly what the fixed, zero-filled arrays this family used to allocate did — so no caller has to tell "not yet
 * materialized" apart from "vacated", and the value id column's `0` sentinel keeps meaning "unassigned" wherever it is
 * read. Only an index at or beyond {@link #capacity()} is a programming error. {@link #copyRangeTo} is the one
 * operation that refuses to read past the live run rather than answering zeroes; see there for why.
 *
 * {@link #size()} is normally the owning leaf's {@code peek + 1}, but a reader must bound itself by {@code peek} and
 * never by {@link #size()} — see {@link ValueColumn} for the two transient windows in which the two disagree, both
 * of them inside a single leaf mutation.
 */
sealed interface RecordColumn permits IntRecordColumn, LongRecordColumn {

	/**
	 * Returns the **logical** capacity — the leaf block size this column was created with, which no mutation ever
	 * changes. The physical backing array is usually shorter; slots in {@code [size(), capacity())} are unused and
	 * read as `0`.
	 *
	 * This is what the leaf's {@code isFull()} / {@code isNearlyFull()} / {@code capacity()} read to decide whether to
	 * split, so it must never be answered with the backing array's length: a column shortened to its content would
	 * otherwise make a five-value tree split, gain an internal root and start persisting leaf pages.
	 *
	 * @return the logical capacity (the leaf block size)
	 */
	int capacity();

	/**
	 * Returns the number of materialized records in this column — normally equal to the owning leaf's {@code peek + 1}.
	 * Everything from here to {@link #capacity()} reads as `0`.
	 *
	 * @return the materialized record count
	 */
	int size();

	/**
	 * Returns the live run a reader holding **no happens-before edge** to the writer may bound itself by —
	 * {@code min(size(), physical length)} — rather than {@link #size()}. See
	 * {@link ValueColumn#observableLiveRun()} for the whole argument; it holds here verbatim, because this family
	 * grows exactly the way the key columns do.
	 *
	 * @return the live run that is safe to index without synchronization
	 */
	int observableLiveRun();

	/**
	 * Creates a new **empty** column of the same concrete kind and the given **logical** capacity (split / layer
	 * target). The returned column allocates no backing storage until its first write.
	 *
	 * @param capacity the logical capacity of the new column (the leaf block size)
	 * @return a fresh empty column of the same kind
	 */
	@Nonnull
	RecordColumn allocate(int capacity);

	/**
	 * Returns a column holding the same records with its physical backing shrunk to the live content, or {@code this}
	 * when the slack does not justify the copy. See {@link ValueColumn#trimmed()} for why it belongs at the commit
	 * merge and nowhere else.
	 *
	 * @return a shrunk copy, or {@code this} when no shrink is warranted
	 */
	@Nonnull
	RecordColumn trimmed();

	/**
	 * Creates a **deep** copy of this column (new backing array, new identity). Used to decouple a transactional layer's
	 * payload column from the shared base on first write, and to snapshot it into a savepoint memento.
	 *
	 * **The copy keeps the source's physical length verbatim and never trims it** (use {@link #trimmed()} for that): a
	 * decoupled layer is about to be written, and a memento has to be a faithful pre-image so a rollback does not
	 * change the leaf's physical shape as a side effect.
	 *
	 * @return an independent deep copy of this column
	 */
	@Nonnull
	RecordColumn duplicate();

	/**
	 * The MVCC decouple's variant of {@link #duplicate()}, for the case where the layer's very first act on the copy
	 * will be an **insert**: identical in content and depth, but a column whose live run exactly fills its backing
	 * array is copied straight to the length its next insert would grow it to, so that insert lands in place. See
	 * {@link ValueColumn#duplicateForInsert()} for the measurement behind it and for the invariant that the savepoint
	 * memento must keep using {@link #duplicate()}.
	 *
	 * @return an independent deep copy of this column, sized to absorb one more entry without reallocating
	 */
	@Nonnull
	RecordColumn duplicateForInsert();

	/**
	 * Returns the record at the given index narrowed to {@code int}. Valid for an {@link IntRecordColumn} (identity) and
	 * for a {@link LongRecordColumn} (the caller asserts the payload fits 32 bits) — used by the int-record tree API.
	 *
	 * An index at or beyond {@link #size()} but below {@link #capacity()} reads `0`, the value an unwritten slot has
	 * always held.
	 *
	 * @param index the slot to read
	 * @return the record at {@code index} as an {@code int}
	 */
	int intAt(int index);

	/**
	 * Returns the record at the given index as a {@code long}. An {@link IntRecordColumn} widens its {@code int} (free,
	 * sign-preserving); a {@link LongRecordColumn} returns the stored value verbatim.
	 *
	 * An index at or beyond {@link #size()} but below {@link #capacity()} reads `0`, the value an unwritten slot has
	 * always held.
	 *
	 * @param index the slot to read
	 * @return the record at {@code index} as a {@code long}
	 */
	long longAt(int index);

	/**
	 * Inserts {@code value} at {@code index}, shifting the live tail one slot to the right and raising {@link #size()}
	 * by one (the leaf grows {@code peek} afterwards). An {@link IntRecordColumn} narrows {@code value} to {@code int}.
	 *
	 * **Grows the physical backing first when the live run already fills it**, so the caller never has to pre-size the
	 * column. Only the live tail moves — {@code size() - index} slots — never the whole block. An insert at or beyond
	 * {@link #size()} degenerates to {@link #setAt}: shifting the zero-valued tail right changes nothing.
	 *
	 * @param index the insertion position
	 * @param value the record to insert (widened {@code int} or a packed {@code long})
	 */
	void insertAt(int index, long value);

	/**
	 * Bulk-populates this freshly-{@link #allocate}d (empty) column with {@code count} already-known payloads in a
	 * single pass — the load-time counterpart to {@code count} sequential {@link #insertAt} calls (used when the
	 * full payload set is known up front, e.g. loading a persisted leaf page). {@link #insertAt} shifts the live tail
	 * on each call, so {@code count} sequential calls cost Θ(count²/2) element copies where this method costs
	 * O(count).
	 *
	 * **Sizes the physical backing exactly to {@code count}** and sets {@link #size()} to it, so every persisted page
	 * lands at its exact footprint with no overshoot at all.
	 *
	 * @param payloads the payloads to load; only {@code payloads[0, count)} are read
	 * @param count    the number of live payloads ({@code <= capacity()})
	 */
	void bulkLoad(@Nonnull long[] payloads, int count);

	/**
	 * Overwrites the record at {@code index} in place, without shifting the tail (the leaf's {@code peek} is
	 * unchanged). Used by the commit-merge to demote a multi bucket — drained to a single record — back to the
	 * primitive single form: the sole surviving id is written over the don't-care slot. An {@link IntRecordColumn}
	 * narrows {@code value} to {@code int}. Allocation-free on the common path.
	 *
	 * **Writing past the live run materializes it**: the backing array grows, the gap is zero-filled and
	 * {@link #size()} becomes {@code index + 1}. That is what lets the value id column be attached to a leaf empty and
	 * then stamped slot by slot, which both the bulk-load path and the minter back-fill do.
	 *
	 * @param index the slot to overwrite
	 * @param value the record to store (widened {@code int} or a packed {@code long})
	 */
	void setAt(int index, long value);

	/**
	 * Removes the record at {@code index}, shifting the live tail one slot to the left and lowering {@link #size()} by
	 * one (the leaf clears the freed last slot via {@link #clearAt} and shrinks {@code peek} afterwards). The vacated
	 * slot is zeroed, so nothing stale survives past the live run.
	 *
	 * Removing a slot at or beyond {@link #size()} is a no-op rather than an error: that region is already zero, and
	 * dropping one zero out of a run of zeroes leaves a run of zeroes.
	 *
	 * @param index the slot to remove
	 */
	void removeAt(int index);

	/**
	 * Truncates the live run to {@code index}, zeroing everything from there on — used to release the freed last slot
	 * after a delete, and reached with a still-live slot when a downward {@code setPeek} shortens a leaf.
	 *
	 * **Size-authoritative, so it is a strict no-op for {@code index >= size()}.** That is what makes it safe on the
	 * committed column a transactional layer still aliases, and it is why the leaf may call it with its pre-decrement
	 * {@code peek} right after {@link #removeAt} has already dropped the entry.
	 *
	 * @param index the first slot to release
	 */
	void clearAt(int index);

	/**
	 * Bulk lockstep move: copies {@code length} records from {@code this[srcPos]} into {@code dst[dstPos]} (supports
	 * overlapping ranges when {@code dst == this}, like {@code System.arraycopy}). {@code dst} must be the same concrete
	 * kind as this column.
	 *
	 * **Grows the destination to {@code dstPos + length} before any record moves** and sets its {@link #size()} to
	 * {@code max(oldSize, dstPos + length)}, zeroing anything between the destination's old live end and
	 * {@code dstPos}. The destination therefore never has to be pre-sized, and the in-place right shift the leaf's
	 * steal-from-left performs ({@code dst == this}) becomes a copy into a larger array rather than an overlapping
	 * move.
	 *
	 * **A source range reaching past {@code this.size()} is refused**, exactly as {@link ValueColumn#copyRangeTo}
	 * refuses it. This family used to absorb it by copying zeroes, because one live state produced it: a value id
	 * column the leaf had attached to an already-populated page and nothing had back-filled yet was a legitimate
	 * steal / merge donor. That state is gone — every id column is now created sized to the leaf it joins — and
	 * with its only producer removed the tolerance would hide caller bugs rather than absorb a known one.
	 *
	 * @param srcPos the start index in this column
	 * @param dst    the destination column (same concrete kind)
	 * @param dstPos the start index in the destination
	 * @param length the number of records to copy
	 */
	void copyRangeTo(int srcPos, @Nonnull RecordColumn dst, int dstPos, int length);

	/**
	 * Truncates the live run to {@code fromInclusive}, zeroing everything from there on (truncated-tail cleanup on
	 * split / {@code setPeek}).
	 *
	 * **Size-authoritative: {@code toExclusive} bounds nothing and needs no relation to the physical length.** The
	 * split constructor passes {@link #capacity()} there, and {@code createLayer()} routes it onto the committed
	 * column, where the call has to be a harmless no-op rather than an out-of-bounds fill.
	 *
	 * @param fromInclusive the first slot to release (inclusive); a value at or beyond {@link #size()} is a no-op
	 * @param toExclusive   the caller's idea of where the released run ends; retained for call-site readability
	 */
	void fillEmpty(int fromInclusive, int toExclusive);

	/**
	 * Returns the heap this column occupies in bytes.
	 *
	 * The backing array is measured at its *allocated* length — which follows the live content rather than the leaf
	 * block size, because it is grown on demand and trimmed at the commit merge. **The figure therefore moves as
	 * records are inserted and removed**, and an empty column costs its object alone: it parks on the JVM-wide shared
	 * empty array and owns no storage at all. Growth overshoots the live count by up to a factor of two between
	 * reallocations, so the figure tracks content in steps rather than exactly.
	 *
	 * Unlike {@link ValueColumn}, this family needs no element sizer: records are primitive ids stored as values
	 * inside the array, so a record column can never point at an object owned by somebody else.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	long getHeapSizeInBytes();

}
