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
 */
sealed interface RecordColumn permits IntRecordColumn, LongRecordColumn {

	/**
	 * Returns the backing capacity (== the leaf's block size); slots in {@code [size, capacity)} are unused.
	 *
	 * @return the backing capacity
	 */
	int capacity();

	/**
	 * Creates a new **empty** column of the same concrete kind and the given capacity (split / layer target).
	 *
	 * @param capacity the capacity of the new column
	 * @return a fresh empty column of the same kind
	 */
	@Nonnull
	RecordColumn allocate(int capacity);

	/**
	 * Creates a **deep** copy of this column (new backing array, new identity). Used to decouple a transactional layer's
	 * payload column from the shared base on first write, and to snapshot it into a savepoint memento.
	 *
	 * @return an independent deep copy of this column
	 */
	@Nonnull
	RecordColumn duplicate();

	/**
	 * Returns the record at the given index narrowed to {@code int}. Valid for an {@link IntRecordColumn} (identity) and
	 * for a {@link LongRecordColumn} (the caller asserts the payload fits 32 bits) — used by the int-record tree API.
	 *
	 * @param index the slot to read
	 * @return the record at {@code index} as an {@code int}
	 */
	int intAt(int index);

	/**
	 * Returns the record at the given index as a {@code long}. An {@link IntRecordColumn} widens its {@code int} (free,
	 * sign-preserving); a {@link LongRecordColumn} returns the stored value verbatim.
	 *
	 * @param index the slot to read
	 * @return the record at {@code index} as a {@code long}
	 */
	long longAt(int index);

	/**
	 * Inserts {@code value} at {@code index}, shifting the tail one slot to the right (the leaf grows {@code peek}
	 * afterwards). An {@link IntRecordColumn} narrows {@code value} to {@code int}.
	 *
	 * @param index the insertion position
	 * @param value the record to insert (widened {@code int} or a packed {@code long})
	 */
	void insertAt(int index, long value);

	/**
	 * Removes the record at {@code index}, shifting the tail one slot to the left (the leaf clears the freed last slot
	 * via {@link #clearAt} and shrinks {@code peek} afterwards).
	 *
	 * @param index the slot to remove
	 */
	void removeAt(int index);

	/**
	 * Clears (zeroes) the slot at {@code index} — used to release the freed last slot after a delete or a downward
	 * {@code setPeek}.
	 *
	 * @param index the slot to clear
	 */
	void clearAt(int index);

	/**
	 * Bulk lockstep move: copies {@code length} records from {@code this[srcPos]} into {@code dst[dstPos]} (supports
	 * overlapping ranges when {@code dst == this}, like {@code System.arraycopy}). {@code dst} must be the same concrete
	 * kind as this column.
	 *
	 * @param srcPos the start index in this column
	 * @param dst    the destination column (same concrete kind)
	 * @param dstPos the start index in the destination
	 * @param length the number of records to copy
	 */
	void copyRangeTo(int srcPos, @Nonnull RecordColumn dst, int dstPos, int length);

	/**
	 * Clears the slots in {@code [fromInclusive, toExclusive)} (truncated-tail cleanup on split / {@code setPeek}).
	 *
	 * @param fromInclusive the first slot to clear (inclusive)
	 * @param toExclusive   the slot to stop at (exclusive)
	 */
	void fillEmpty(int fromInclusive, int toExclusive);

}
