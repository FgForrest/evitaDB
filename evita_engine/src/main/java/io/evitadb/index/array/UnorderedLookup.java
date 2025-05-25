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

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.Comparator;

import static io.evitadb.utils.ArrayUtils.computeInsertPositionOfIntInOrderedArray;

/**
 * This class provides fast lookup operation upon unordered array so that its performance is on par with ordered array.
 * Lookup method {@link #findPosition(int)} is as fast as {@link Arrays#binarySearch(int[], int)}.
 *
 * Write methods - i.e. adding and removing record from this instance always create two new arrays and are slower. Lookup
 * for the slice position is also as fast as {@link Arrays#binarySearch(int[], int)}.
 *
 * Exit method {@link #getArray()} requires new array allocation and filling one record after another, but it's also
 * quite fast.
 *
 * Array must not contain duplicated recordIds!
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class UnorderedLookup implements Serializable {
	@Serial private static final long serialVersionUID = 4971547793733694511L;

	/**
	 * Unordered array of proper record positions of the record on the same index.
	 * Ie. position of `recordIds[i]` in the result array is `positions[i]`
	 */
	@Getter private int[] positions;
	/**
	 * Ordered array of record ids in ascending order.
	 */
	private int[] recordIds;
	/**
	 * Contains remembered unordered array that combines {@link #positions} and {@link #recordIds} so that it doesn't
	 * need to be computed repeatedly when nothing has changed.
	 */
	private int[] memoizedUnorderedArray;

	/**
	 * Returns ordered array of record ids in ascending order.
	 */
	public int[] getRecordIds() {
		return this.recordIds;
	}

	/**
	 * Creates new instance.
	 */
	public UnorderedLookup(int recordId) {
		this.positions = new int[]{0};
		this.recordIds = new int[]{recordId};
	}

	/**
	 * Creates new instance.
	 */
	public UnorderedLookup(int[] unorderedArray) {
		this.positions = new int[unorderedArray.length];
		// init record ids in ascending order
		this.recordIds = new int[unorderedArray.length];
		// both arrays fill with recordId and associated position in original array
		for (int i = 0; i < unorderedArray.length; i++) {
			final int recordId = unorderedArray[i];
			this.positions[i] = i;
			this.recordIds[i] = recordId;
		}
		Arrays.sort(this.recordIds);
		// now sort positions according to recordId value - this will change ordered positions to unordered ones
		new IntArrayWrapper(this.positions).sort(Comparator.comparing(o -> unorderedArray[o]));
		// we may initialize the cached result
		this.memoizedUnorderedArray = unorderedArray;
	}

	/**
	 * Finds and returns position of the passed record id in the result array.
	 *
	 * @return {@link Integer#MIN_VALUE} when recordId is not present in array
	 */
	public int findPosition(int recordId) {
		final int index = Arrays.binarySearch(this.recordIds, recordId);
		return index < 0 ? Integer.MIN_VALUE : this.positions[index];
	}

	/**
	 * Adds new record to the array on the position just after previous record id. Previous record id must exists
	 * in the array already. There is special value {@link Integer#MIN_VALUE} that is used as a wildcard for "no previous
	 * record" - i.e. situation when we want to add new record id to the head of the array.
	 *
	 * @param previousRecordId or {@link Integer#MIN_VALUE}
	 */
	public void addRecord(int previousRecordId, int recordId) {
		// find position of the previous record id
		final InsertionPosition insertionPosition = previousRecordId == Integer.MIN_VALUE ?
			new InsertionPosition(0, false) :
			computeInsertPositionOfIntInOrderedArray(previousRecordId, this.recordIds);

		// this slows insertion but we want this consistency check anyway
		Assert.isTrue(
			Arrays.binarySearch(this.recordIds, recordId) < 0,
			"Record with id " + recordId + " is already part of the array!"
		);

		final int newPosition;
		final InsertionPosition leadingPosition;
		// if previous record id is part of the array
		if (insertionPosition.alreadyPresent()) {
			// new position is position of the previous record plus one
			newPosition = this.positions[insertionPosition.position()] + 1;
			// compute position in the ordered array of record ids where new record should be placed
			leadingPosition = computeInsertPositionOfIntInOrderedArray(recordId, this.recordIds);
			// if record id should be added to the head of the array
		} else if (previousRecordId == Integer.MIN_VALUE) {
			// new position is zero = head of the array
			newPosition = 0;
			// compute position in the ordered array of record ids where new record should be placed
			leadingPosition = computeInsertPositionOfIntInOrderedArray(recordId, this.recordIds);
			// else signalize error - client wants to add record after non existing record
		} else {
			throw new GenericEvitaInternalError(
				"Record with id " + previousRecordId + " was not found in the array, cannot add record " + recordId + " after it!",
				"Referenced record was not found in the array! Cannot add record after it."
			);
		}

		// increment all positions that are same or equal to newPosition to maintain monotonic row or positions
		for (int i = 0; i < this.positions.length; i++) {
			if (this.positions[i] >= newPosition) {
				this.positions[i]++;
			}
		}
		// now place new record id into ordered array of other record ids on proper place
		this.recordIds = ArrayUtils.insertIntIntoArrayOnIndex(recordId, this.recordIds, leadingPosition.position());
		// now place the new position into unordered array of positions on the same index as record on previous line
		this.positions = ArrayUtils.insertIntIntoArrayOnIndex(newPosition, this.positions, leadingPosition.position());
		// we have to reset memoized result - modification has occurred
		this.memoizedUnorderedArray = null;
	}

	/**
	 * Adds new record to the array on the specified position.
	 */
	public void addRecordOnIndex(int index, int recordId) {
		// this slows insertion but we want this consistency check anyway
		Assert.isTrue(
			Arrays.binarySearch(this.recordIds, recordId) < 0,
			"Record with id " + recordId + " is already part of the array!"
		);

		// compute position in the ordered array of record ids where new record should be placed
		final InsertionPosition leadingPosition = computeInsertPositionOfIntInOrderedArray(recordId, this.recordIds);

		// increment all positions that are same or equal to newPosition to maintain monotonic row or positions
		for (int i = 0; i < this.positions.length; i++) {
			if (this.positions[i] >= index) {
				this.positions[i]++;
			}
		}
		// now place new record id into ordered array of other record ids on proper place
		this.recordIds = ArrayUtils.insertIntIntoArrayOnIndex(recordId, this.recordIds, leadingPosition.position());
		// now place the new position into unordered array of positions on the same index as record on previous line
		this.positions = ArrayUtils.insertIntIntoArrayOnIndex(index, this.positions, leadingPosition.position());
		// we have to reset memoized result - modification has occurred
		this.memoizedUnorderedArray = null;
	}

	/**
	 * Appends set of unsorted records at the end of the existing array.
	 */
	public void appendRecords(int[] newRecordIds) {
		if (ArrayUtils.isEmpty(newRecordIds)) {
			// quick return path
			return;
		}
		final int[] aggregatedPositions = new int[this.positions.length + newRecordIds.length];
		final int[] aggregatedRecordIds = new int[this.recordIds.length + newRecordIds.length];
		System.arraycopy(getArray(), 0, aggregatedRecordIds, 0, this.recordIds.length);
		System.arraycopy(newRecordIds, 0, aggregatedRecordIds, this.recordIds.length, newRecordIds.length);

		// fill associated position in aggregatedRecordIds array
		for (int i = 0; i < aggregatedRecordIds.length; i++) {
			aggregatedPositions[i] = i;
		}

		// now sort positions according to recordId value - this will change ordered positions to unordered ones
		new IntArrayWrapper(aggregatedPositions).sort(Comparator.comparing(o -> aggregatedRecordIds[o]));
		this.positions = aggregatedPositions;

		// now insert new record ids into the ordered array
		final int[] sortedNewRecordIds = new int[newRecordIds.length];
		System.arraycopy(newRecordIds, 0, sortedNewRecordIds, 0, newRecordIds.length);
		Arrays.sort(sortedNewRecordIds);

		final int[] aggregatedOrderedRecordIds = new int[this.recordIds.length + newRecordIds.length];
		int lastSourcePosition = 0;
		int lastDestinationPosition = 0;
		for (int newRecordId : sortedNewRecordIds) {
			final InsertionPosition ins = computeInsertPositionOfIntInOrderedArray(newRecordId, this.recordIds);
			final int position = ins.position();
			final int length = position - lastSourcePosition;
			System.arraycopy(this.recordIds, lastSourcePosition, aggregatedOrderedRecordIds, lastDestinationPosition, length);
			lastDestinationPosition += length;
			aggregatedOrderedRecordIds[lastDestinationPosition++] = newRecordId;
			lastSourcePosition = position;
		}
		System.arraycopy(this.recordIds, lastSourcePosition, aggregatedOrderedRecordIds, lastDestinationPosition, this.recordIds.length - lastSourcePosition);
		this.recordIds = aggregatedOrderedRecordIds;

		// we have to reset memoized result - modification has occurred
		this.memoizedUnorderedArray = null;
	}

	/**
	 * Removes existing record from the array.
	 *
	 * @throws IllegalArgumentException when record id is not part of the array
	 */
	public void removeRecord(int recordId) {
		// find record id by fast binary search
		final int index = Arrays.binarySearch(this.recordIds, recordId);
		if (index >= 0) {
			// when found, remove it and shrink the array
			this.recordIds = ArrayUtils.removeIntFromArrayOnIndex(this.recordIds, index);
			// find original position of the record id in the unordered array
			final int position = this.positions[index];
			// remove the same position and shrink the array
			this.positions = ArrayUtils.removeIntFromArrayOnIndex(this.positions, index);
			// now lower all positions above removed position by one to fill the gap after record and maintain monotonic row of positions
			for (int i = 0; i < this.positions.length; i++) {
				if (this.positions[i] > position) {
					this.positions[i]--;
				}
			}
		} else {
			throw new GenericEvitaInternalError(
				"Record id " + recordId + " is not part of the array!",
				"Record id is not part of the array!"
			);
		}

		// we have to reset memoized result - modification has occurred
		this.memoizedUnorderedArray = null;
	}

	/**
	 * Method removes all records between two indexes.
	 *
	 * @param startIndex inclusive
	 * @param endIndex exclusive
	 * @return removed records
	 */
	public int[] removeRange(int startIndex, int endIndex) {
		final int[] unorderedArray = getArray();
		final int[] removedRecordIds = new int[endIndex - startIndex];
		System.arraycopy(unorderedArray, startIndex, removedRecordIds, 0, removedRecordIds.length);

		final int[] newPositions = new int[unorderedArray.length - removedRecordIds.length];
		// init record ids in ascending order
		final int[] newRecordIds = new int[unorderedArray.length - removedRecordIds.length];
		// both arrays fill with recordId and associated position in original array
		for (int i = 0; i < unorderedArray.length - removedRecordIds.length; i++) {
			final int recordId = unorderedArray[i < startIndex ? i : i + removedRecordIds.length];
			newPositions[i] = i;
			newRecordIds[i] = recordId;
		}
		Arrays.sort(newRecordIds);
		// now sort positions according to recordId value - this will change ordered positions to unordered ones
		new IntArrayWrapper(newPositions).sort(Comparator.comparing(o -> unorderedArray[o < startIndex ? o : o + removedRecordIds.length]));

		// replace the internal arrays
		this.recordIds = newRecordIds;
		this.positions = newPositions;

		// we have to reset memoized result - modification has occurred
		this.memoizedUnorderedArray = null;
		return removedRecordIds;
	}

	/**
	 * Returns record id, that is on the passed position (or index if you want). Equivalent to `unorderedArray[position]`.
	 * This method is not fast because goes through unordered array of positions and has O(N) complexity.
	 */
	public int getRecordAt(int position) {
		for (int i = 0; i < this.positions.length; i++) {
			int examinedPosition = this.positions[i];
			if (examinedPosition == position) {
				return this.recordIds[i];
			}
		}
		throw new GenericEvitaInternalError(
			"Position " + position + " not found!",
			"Unknown position in the array!"
		);
	}

	/**
	 * Method returns last record in the array.
	 *
	 * @return record id
	 * @throws ArrayIndexOutOfBoundsException when array is empty
	 */
	public int getLastRecordId() throws ArrayIndexOutOfBoundsException {
		if (this.memoizedUnorderedArray != null) {
			return this.memoizedUnorderedArray[this.memoizedUnorderedArray.length - 1];
		} else {
			final int lastPosition = this.recordIds.length - 1;
			for (int i = 0; i < this.positions.length; i++) {
				int position = this.positions[i];
				if (position == lastPosition) {
					return this.recordIds[i];
				}
			}
			throw new ArrayIndexOutOfBoundsException("Array is empty!");
		}
	}

	/**
	 * Returns possibly modified unordered array of record ids.
	 */
	public int[] getArray() {
		if (this.memoizedUnorderedArray == null) {
			final int[] result = new int[this.positions.length];
			// place record ids into result array on target positions - this will reconstruct unordered record id array from ordered one
			for (int i = 0; i < this.positions.length; i++) {
				final int position = this.positions[i];
				result[position] = this.recordIds[i];
			}
			this.memoizedUnorderedArray = result;
		}
		return this.memoizedUnorderedArray;
	}

	/**
	 * Returns size of the array. Equivalent to `unorderedArray.length`.
	 */
	public int size() {
		return this.recordIds.length;
	}

	@Override
	public String toString() {
		return "UnorderedLookup{" +
			"positions=" + Arrays.toString(this.positions) +
			", recordIds=" + Arrays.toString(this.recordIds) +
			'}';
	}

	/**
	 * Internal helper class that is used to sort array A by comparator that uses array B. This cannot be easily done
	 * by other way than mimicking list to get advantage of {@link AbstractList#sort(Comparator)} function
	 * that accepts external comparator.
	 */
	@RequiredArgsConstructor
	private static class IntArrayWrapper extends AbstractList<Integer> {
		private final int[] elements;

		@Override
		public Integer get(int index) {
			return this.elements[index];
		}

		@Override
		public int size() {
			return this.elements.length;
		}

		@Override
		public Integer set(int index, Integer element) {
			int v = this.elements[index];
			this.elements[index] = element;
			return v;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			if (!super.equals(o)) return false;
			IntArrayWrapper integers = (IntArrayWrapper) o;
			return Arrays.equals(this.elements, integers.elements);
		}

		@Override
		public int hashCode() {
			int result = super.hashCode();
			result = 31 * result + Arrays.hashCode(this.elements);
			return result;
		}
	}

}
