/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
 * Bitmap represents simple sorted "array" or plain integers that represents record ids.
 * Internal representation may be more effective data structure providing that it can any time produce sorted array
 * of integers (record ids).
 *
 * Bitmaps always contains sorted row of integer values. Bitmaps may or may not contain duplicate integers.
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
	 * Adds single record to the bitmap. Record is not added when it's already present in the bitmap.
	 *
	 * @return true if record was added
	 */
	boolean add(int recordId);

	/**
	 * Adds multiple record ids to the bitmap. Duplicated ids are skipped silently.
	 */
	void addAll(int... recordId);

	/**
	 * Adds multiple record ids to the bitmap. Duplicated ids are skipped silently.
	 */
	void addAll(@Nonnull Bitmap recordIds);

	/**
	 * Removes single record from the bitmap. If record doesn't exist it's not removed.
	 *
	 * @return true if record was removed
	 */
	boolean remove(int recordId);

	/**
	 * Removes multiple record ids from the bitmap. Non existing record ids in bitmap are skipped silently.
	 */
	void removeAll(int... recordId);

	/**
	 * Removes multiple record ids from the bitmap. Non existing record ids in bitmap are skipped silently.
	 */
	void removeAll(@Nonnull Bitmap recordIds);

	/**
	 * Returns true if bitmap contains record of this id.
	 */
	boolean contains(int recordId);

	/**
	 * Returns index of the record id in the bitmap.
	 *
	 * @return negative value if record id is not part of the bitmap
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
	 * Returns first record id in bitmap.
	 *
	 * @throws IndexOutOfBoundsException bitmap is empty
	 */
	int getFirst();

	/**
	 * Returns last record id in bitmap.
	 *
	 * @throws IndexOutOfBoundsException bitmap is empty
	 */
	int getLast();

	/**
	 * Produces (allocates) new sorted array of the records stored in the bitmap.
	 */
	int[] getArray();

	/**
	 * Produces iterator over all record ids.
	 */
	@Nonnull
	OfInt iterator();

	/**
	 * Serves bitmap as stream of its values.
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
