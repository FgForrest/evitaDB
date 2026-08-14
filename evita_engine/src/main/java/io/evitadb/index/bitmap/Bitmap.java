/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.index.bitmap;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.PrimitiveIterator.OfInt;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

/**
 * Bitmap represents a sorted set of unique integers that represent record ids. Internal representation
 * may use a more efficient data structure (e.g. {@link io.evitadb.roaringbitmap.PersistentRoaringBitmap}) but can always
 * produce a sorted array of integers (record ids) on demand.
 *
 * Bitmaps always contain a sorted sequence of unique integer values - duplicates are never stored.
 *
 * Some implementations are immutable and throw {@link UnsupportedOperationException} on mutation methods
 * ({@link #add(int)}, {@link #addAll(int...)}, {@link #remove(int)}, {@link #removeAll(int...)}).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface Bitmap extends Iterable<Integer>, Serializable {

	/**
	 * Returns true when bitmap contains no record ids.
	 */
	boolean isEmpty();

	/**
	 * Returns number of records in the bitmap.
	 */
	int size();

	/**
	 * Adds single record to the bitmap. The record is not added when it is already present.
	 * The sorted order of the bitmap is maintained automatically.
	 *
	 * @return true if the record was added, false if it was already present
	 * @throws UnsupportedOperationException if the bitmap is immutable
	 */
	boolean add(int recordId);

	/**
	 * Adds multiple record ids to the bitmap. Duplicate ids are skipped silently.
	 * The sorted order of the bitmap is maintained automatically.
	 *
	 * @throws UnsupportedOperationException if the bitmap is immutable
	 */
	void addAll(int... recordId);

	/**
	 * Adds multiple record ids to the bitmap. Duplicate ids are skipped silently.
	 * The sorted order of the bitmap is maintained automatically.
	 *
	 * @throws UnsupportedOperationException if the bitmap is immutable
	 */
	void addAll(@Nonnull Bitmap recordIds);

	/**
	 * Removes single record from the bitmap. If the record doesn't exist, nothing happens.
	 *
	 * @return true if the record was removed, false if it was not present
	 * @throws UnsupportedOperationException if the bitmap does not support removals
	 */
	boolean remove(int recordId);

	/**
	 * Removes multiple record ids from the bitmap. Non-existing record ids are skipped silently.
	 *
	 * @throws UnsupportedOperationException if the bitmap does not support removals
	 */
	void removeAll(int... recordId);

	/**
	 * Removes multiple record ids from the bitmap. Non-existing record ids are skipped silently.
	 *
	 * @throws UnsupportedOperationException if the bitmap does not support removals
	 */
	void removeAll(@Nonnull Bitmap recordIds);

	/**
	 * Returns true if bitmap contains record of this id.
	 */
	boolean contains(int recordId);

	/**
	 * Returns index of the record id in the bitmap. The method follows the same contract as
	 * {@link java.util.Arrays#binarySearch(int[], int)} - when the record id is found, returns its
	 * zero-based index; when not found, returns `-(insertion point) - 1` where the insertion point
	 * is the index at which the record id would be inserted to maintain sorted order.
	 *
	 * @return zero-based index of the record id, or a negative value if not present
	 */
	int indexOf(int recordId);

	/**
	 * Returns record id on specified index of the bitmap (i.e. Nth record id in the bitmap).
	 *
	 * @return record id
	 * @throws IndexOutOfBoundsException when index exceeds {@link #size()} -1
	 */
	int get(int index);

	/**
	 * Returns slice of records starting from `start` index (inclusive) and ending with `end` index (exclusive).
	 *
	 * @throws IndexOutOfBoundsException when end index exceeds the size of the bitmap
	 */
	int[] getRange(int start, int end);

	/**
	 * Returns first (smallest) record id in the bitmap.
	 *
	 * @throws IndexOutOfBoundsException when the bitmap is empty
	 */
	int getFirst();

	/**
	 * Returns last (largest) record id in the bitmap.
	 *
	 * @throws IndexOutOfBoundsException when the bitmap is empty
	 */
	int getLast();

	/**
	 * Produces (allocates) new sorted array of the records stored in the bitmap.
	 */
	int[] getArray();

	/**
	 * Returns the heap this bitmap occupies, in bytes — the figure reported through the statistics API's
	 * memory-footprint component.
	 *
	 * # What is counted
	 *
	 * Everything the bitmap **owns**: its own object and whatever backing structure it allocated, measured
	 * at that structure's *allocated capacity* rather than at {@link #size()}. The two differ without bound.
	 * A roaring container never trims its backing array when records are removed, so a bitmap grown large
	 * and then emptied keeps the whole allocation; pricing it by cardinality can under-report by more than
	 * an order of magnitude, and an under-report is the one error a heap figure must never make.
	 *
	 * Structure aliased with a **previous version** of the same bitmap is counted in full. evitaDB's roaring
	 * fork is copy-on-write and the commit path merges through operations that alias, so a freshly committed
	 * bitmap shares almost everything with the version it replaced. That predecessor is collected shortly
	 * afterwards, leaving this bitmap the sole owner, so excluding the aliased part would describe a state
	 * lasting milliseconds and would report a near-empty figure for a mature index.
	 *
	 * # What is not counted
	 *
	 * Anything the bitmap merely borrows and does not own: a shared singleton such as
	 * {@link EmptyBitmap#INSTANCE} costs its holder nothing beyond the reference slot, and a
	 * {@link TransactionalBitmap}'s uncommitted diff belongs to the open transaction rather than to the
	 * bitmap. This is what keeps per-index figures summing to a sensible total instead of exceeding the heap.
	 *
	 * Implementations must answer in `O(1)` or `O(chunks)` — never by walking the record ids.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	long getHeapSizeInBytes();

	/**
	 * Produces iterator over all record ids.
	 */
	@Nonnull
	OfInt iterator();

	/**
	 * Returns an {@link IntStream} over all record ids in the bitmap. The stream has
	 * {@link Spliterator#ORDERED}, {@link Spliterator#DISTINCT}, {@link Spliterator#IMMUTABLE},
	 * and {@link Spliterator#SORTED} characteristics.
	 */
	@Nonnull
	default IntStream stream() {
		return StreamSupport.intStream(
			() -> Spliterators.spliterator(iterator(), size(),Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.IMMUTABLE | Spliterator.SORTED),
			Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.IMMUTABLE | Spliterator.SORTED,
			false
		);
	}

}
