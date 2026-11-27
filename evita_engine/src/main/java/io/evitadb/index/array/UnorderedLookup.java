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

package io.evitadb.index.array;

import com.carrotsearch.hppc.IntIntHashMap;
import com.carrotsearch.hppc.IntIntMap;
import io.evitadb.utils.VMLayout;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * A flattened snapshot of distinct record ids and their positions in logical order.
 * The record ids are sorted in ascending order, and each entry in the position array gives the logical position
 * of the corresponding sorted id. The original logical order is available through {@link #getArray()}.
 *
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class UnorderedLookup implements Serializable {
	@Serial private static final long serialVersionUID = 4971547793733694511L;

	/**
	 * Unordered array of proper record positions of the record on
	 * the same index. I.e. position of `recordIds[i]` in the result
	 * array is `positions[i]`.
	 */
	@Nonnull @Getter private final int[] positions;
	/**
	 * Ordered array of record ids in ascending order.
	 */
	private final int[] recordIds;
	/**
	 * Record ids in logical order, adopted from the constructor input.
	 */
	@Nonnull private final int[] memoizedUnorderedArray;

	/**
	 * Returns ordered array of record ids in ascending order.
	 */
	@Nonnull
	public int[] getRecordIds() {
		return this.recordIds;
	}

	/**
	 * Creates a lookup over the given record ids in their target ("unordered") order.
	 *
	 * @param unorderedArray record ids in the order this lookup serves; the ids must be
	 *                       distinct - a duplicate would collapse two entries onto one
	 *                       position and silently corrupt the lookup
	 */
	public UnorderedLookup(@Nonnull int[] unorderedArray) {
		final int length = unorderedArray.length;
		// map each record id to its position in the unordered array (primitive, no boxing)
		final IntIntMap recordToPosition = new IntIntHashMap(length);
		for (int position = 0; position < length; position++) {
			recordToPosition.put(unorderedArray[position], position);
		}
		// record ids in ascending order
		final int[] ascendingRecordIds = unorderedArray.clone();
		Arrays.sort(ascendingRecordIds);
		// positions[i] = position of the i-th smallest record id in the unordered array. Built via a primitive
		// hash-map lookup instead of the former boxed AbstractList comparator sort (which boxed every element and every
		// comparison key). Record ids are distinct by contract, so the map holds exactly one position per id.
		final int[] orderedPositions = new int[length];
		for (int i = 0; i < length; i++) {
			orderedPositions[i] = recordToPosition.get(ascendingRecordIds[i]);
		}
		this.recordIds = ascendingRecordIds;
		this.positions = orderedPositions;
		this.memoizedUnorderedArray = unorderedArray;
	}

	/**
	 * Returns the heap this lookup occupies, in bytes — its own object and its three `int[]`s.
	 *
	 * All three are this lookup's own. The two built here are freshly allocated, and the unordered array adopted by
	 * {@link #UnorderedLookup(int[])} is likewise handed over rather than borrowed: its only caller slices a fresh
	 * result out of the element snapshot precisely so the shared memoized array is never aliased.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// the positions / recordIds / memoizedUnorderedArray slots
		return layout.sizeOfObject(3L * layout.referenceSize())
			+ layout.sizeOfArray(this.positions.length, Integer.BYTES)
			+ layout.sizeOfArray(this.recordIds.length, Integer.BYTES)
			+ layout.sizeOfArray(this.memoizedUnorderedArray.length, Integer.BYTES);
	}

	/**
	 * Returns the record ids in logical order.
	 *
	 * @return the original record order as supplied to the constructor
	 */
	@Nonnull
	public int[] getArray() {
		return this.memoizedUnorderedArray;
	}

	@Override
	public String toString() {
		return "UnorderedLookup{" +
			"positions=" + Arrays.toString(this.positions) +
			", recordIds=" + Arrays.toString(this.recordIds) +
			'}';
	}

}
