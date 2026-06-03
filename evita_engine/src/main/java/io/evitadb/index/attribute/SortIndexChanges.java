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

package io.evitadb.index.attribute;

import io.evitadb.index.bPlusTree.TransactionalObjectBPlusTree;
import io.evitadb.index.bPlusTree.TransactionalObjectBPlusTree.EntryCursor;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;
import lombok.AllArgsConstructor;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

import static io.evitadb.index.attribute.SortIndex.invert;
import static java.util.Optional.ofNullable;

/**
 * Class contains intermediate computation data structures that speed up access to the {@link SortedRecordsSupplier}
 * implementations and also allow to modify contents of the {@link SortIndex} data. All data inside this class can be
 * safely thrown out and recreated from {@link SortIndex} internal data again.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class SortIndexChanges implements Serializable {
	@Serial private static final long serialVersionUID = -4791973822619493092L;

	/**
	 * Reference to the {@link SortIndex} this data structure is linked to.
	 * It provides the foundational data on which sorting and modifications are based.
	 */
	private final SortIndex sortIndex;

	/**
	 * The comparator used to compare values in the sort index.
	 */
	@SuppressWarnings("rawtypes") private final Comparator valueComparator;

	/**
	 * Contains start indexes of the record id chunks (blocks) within {@link SortIndex#sortedRecords} - one entry per
	 * distinct value, in value order. This intermediate structure is used only when contents of the {@link SortIndex}
	 * are modified. The sort index itself avoids holding this data for memory optimization. It expands the per-value
	 * cardinalities stored inline in {@link SortIndex#sortedValues} (each `>= 1`) into a full prefix-sum of block start
	 * offsets - see {@link #getValueIndex(TransactionalObjectBPlusTree)}.
	 */
	private ValueStartIndex[] valueLocationIndex;

	/**
	 * Verifies that value is not present in value index.
	 */
	private static void assertNotPresent(boolean present, @Nonnull Serializable value) {
		Assert.isTrue(present, "Value `" + StringUtils.unknownToString(value) + "` unexpectedly found in value start index!");
	}

	public SortIndexChanges(@Nonnull SortIndex sortIndex, @SuppressWarnings("rawtypes") @Nonnull Comparator valueComparator) {
		this.sortIndex = sortIndex;
		this.valueComparator = valueComparator;
	}

	/**
	 * Returns {@link SortedRecordsSupplier} that contains records ids sorted by value in ascending order.
	 * Result of the method is cached and additional calls obtain memoized result.
	 */
	@Nonnull
	public SortedRecordsSupplier getAscendingOrderRecordsSupplier() {
		return ofNullable(this.sortIndex.getReferenceKey())
			.map(
				referenceKey -> (SortedRecordsSupplier) new ReferenceSortedRecordsProvider(
					this.sortIndex.sortedRecords.getId(),
					this.sortIndex.sortedRecords.getArray(),
					this.sortIndex.sortedRecords.getPositions(),
					this.sortIndex.sortedRecords.getRecordIds(),
					this.sortIndex.createSortedComparableForwardSeeker(),
					referenceKey
				)
			)
			.orElseGet(
				() -> new SortedRecordsSupplier(
					this.sortIndex.sortedRecords.getId(),
					this.sortIndex.sortedRecords.getArray(),
					this.sortIndex.sortedRecords.getPositions(),
					this.sortIndex.sortedRecords.getRecordIds(),
					this.sortIndex.createSortedComparableForwardSeeker()
				)
			);
	}

	/**
	 * Returns {@link SortedRecordsSupplier} that contains records ids sorted by value in descending order.
	 * Result of the method is cached and additional calls obtain memoized result.
	 */
	@Nonnull
	public SortedRecordsSupplier getDescendingOrderRecordsSupplier() {
		return ofNullable(this.sortIndex.getReferenceKey())
			.map(
				referenceKey -> (SortedRecordsSupplier) new ReferenceSortedRecordsProvider(
					this.sortIndex.getId(),
					ArrayUtils.reverse(this.sortIndex.sortedRecords.getArray()),
					invert(this.sortIndex.sortedRecords.getPositions()),
					this.sortIndex.sortedRecords.getRecordIds(),
					this.sortIndex.createReversedSortedComparableForwardSeeker(),
					referenceKey
				)
			)
			.orElseGet(
				() -> new SortedRecordsSupplier(
					this.sortIndex.getId(),
					ArrayUtils.reverse(this.sortIndex.sortedRecords.getArray()),
					invert(this.sortIndex.sortedRecords.getPositions()),
					this.sortIndex.sortedRecords.getRecordIds(),
					this.sortIndex.createReversedSortedComparableForwardSeeker()
				)
			);
	}

	/**
	 * Computes record id of the record id that should precede currently inserted record that is associated with passed
	 * `value`. When record id should be placed on the first index {@link Integer#MIN_VALUE} is returned. This aligns
	 * with {@link io.evitadb.index.array.TransactionalUnorderedIntArray#add(int, int)} contract.
	 */
	public int computePreviousRecord(@Nonnull Serializable value, int recordId) {
		final ValueStartIndex[] valueIndex = getValueIndex(this.sortIndex.sortedValues);
		// compute index of the value in the value index
		//noinspection unchecked
		final InsertionPosition valueInsertionPosition = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
			new ValueStartIndex(value, this.valueComparator, -1), valueIndex,
			(Comparator<ValueStartIndex>) (o1, o2) -> SortIndexChanges.this.valueComparator.compare(o1.getValue(), o2.getValue())
		);
		final int position = valueInsertionPosition.position();
		// if the value is already part of the index
		if (valueInsertionPosition.alreadyPresent()) {
			// compute record id block of the value (block size is equal to value cardinality)
			final ValueStartIndex targetBlock = valueIndex[position];
			final int blockStart = targetBlock.getIndex();
			final int blockEnd = position + 1 < valueIndex.length ? valueIndex[position + 1].getIndex() : this.sortIndex.sortedRecords.getLength();
			final int[] allRecordIds = this.sortIndex.sortedRecords.getArray();
			final int[] recordIdsInBlock = Arrays.copyOfRange(allRecordIds, blockStart, blockEnd);
			// within the block record ids are sorted in natural integer order
			final InsertionPosition recordInsertionPosition = ArrayUtils.computeInsertPositionOfIntInOrderedArray(recordId, recordIdsInBlock);
			// compute the target record id position as block start + relative position in the block
			final int recordPosition = blockStart + recordInsertionPosition.position() - 1;
			// if the record position is negative the record should be placed as first record of the sort index
			return recordPosition >= 0 ? allRecordIds[recordPosition] : Integer.MIN_VALUE;
		} else {
			if (position == 0) {
				// value is not in the index and should be placed as first
				return Integer.MIN_VALUE;
			} else if (position < valueIndex.length) {
				// value is not in the index and should be placed in the middle
				return this.sortIndex.sortedRecords.get(valueIndex[position].getIndex() - 1);
			} else {
				// value is not in the index and should be placed as last
				return this.sortIndex.sortedRecords.get(this.sortIndex.sortedRecords.getLength() - 1);
			}
		}
	}

	/**
	 * Method alters internal data structures when new value (that was not present before) is inserted in the {@link SortIndex}.
	 */
	public void valueAdded(@Nonnull Serializable value) {
		final ValueStartIndex[] valueIndex = getValueIndex(this.sortIndex.sortedValues);
		// compute the insertion position in value index
		@SuppressWarnings({"unchecked"}) final InsertionPosition insertionPosition = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
			new ValueStartIndex(value, this.valueComparator, -1), valueIndex,
			(Comparator<ValueStartIndex>) (o1, o2) -> this.valueComparator.compare(o1.getValue(), o2.getValue())
		);
		assertNotPresent(!insertionPosition.alreadyPresent(), value);
		// nod place the value in the value index with start position as previous block start + previous value cardinality
		final ValueStartIndex newValue = new ValueStartIndex(value, this.valueComparator, getStartPositionFor(valueIndex, insertionPosition.position()));
		this.valueLocationIndex = ArrayUtils.insertRecordIntoArrayOnIndex(newValue, valueIndex, insertionPosition.position());
		// update all values after the inserted one - their index should be greater by exactly one inserted record
		for (int i = insertionPosition.position() + 1; i < this.valueLocationIndex.length; i++) {
			this.valueLocationIndex[i].increment();
		}
	}

	/**
	 * Method alters internal data structures when existing value cardinality is incremented in the {@link SortIndex}.
	 */
	public void valueCardinalityIncreased(@Nonnull Serializable value) {
		final ValueStartIndex[] valueIndex = getValueIndex(this.sortIndex.sortedValues);
		final int position = findExistingValuePosition(valueIndex, value);
		// update this and all values after it - their index should be greater by exactly one inserted record
		for (int i = position + 1; i < valueIndex.length; i++) {
			valueIndex[i].increment();
		}
	}

	/**
	 * Method prepares value index if it hasn't exist yet. It needs to be called before anything in {@link SortIndex}
	 * is changed.
	 */
	public void prepare() {
		// force computation of the value index
		getValueIndex(this.sortIndex.sortedValues);
	}

	/**
	 * Method alters internal data structures when existing value is removed entirely from the {@link SortIndex}.
	 */
	public void valueRemoved(@Nonnull Serializable value) {
		final ValueStartIndex[] valueIndex = getValueIndex(this.sortIndex.sortedValues);
		final int position = findExistingValuePosition(valueIndex, value);
		// remove it from the value location index
		this.valueLocationIndex = ArrayUtils.removeRecordFromArrayOnIndex(valueIndex, position);
		// update all values after it - their index should be lesser by exactly one inserted record
		for (int i = position; i < this.valueLocationIndex.length; i++) {
			this.valueLocationIndex[i].decrement();
		}
	}

	/**
	 * Method alters internal data structures when existing value cardinality is decremented in the {@link SortIndex}.
	 */
	public void valueCardinalityDecreased(@Nonnull Serializable value) {
		final ValueStartIndex[] valueIndex = getValueIndex(this.sortIndex.sortedValues);
		final int position = findExistingValuePosition(valueIndex, value);
		// update it and all values after it - their index should be lesser by exactly one inserted record
		for (int i = position + 1; i < valueIndex.length; i++) {
			valueIndex[i].decrement();
		}
	}

	/**
	 * Computes value index if it hasn't exist yet. Result of this method is memoized. Method computes starting index
	 * (position) of the record ids block that belongs to specific value by iterating {@link SortIndex#sortedValues} in
	 * key order and accumulating each value's inline cardinality.
	 */
	@Nonnull
	@SuppressWarnings("rawtypes")
	ValueStartIndex[] getValueIndex(@Nonnull TransactionalObjectBPlusTree sortedValues) {
		if (this.valueLocationIndex == null) {
			final int valueCount = sortedValues.size();
			final ValueStartIndex[] theValueLocationIndex = new ValueStartIndex[valueCount];
			final EntryCursor cursor = sortedValues.entryCursor();
			int index = 0;
			int accumulator = 0;
			while (cursor.hasNext()) {
				final Serializable value = (Serializable) cursor.next();
				theValueLocationIndex[index++] = new ValueStartIndex(value, this.valueComparator, accumulator);
				accumulator += (Integer) cursor.value();
			}
			this.valueLocationIndex = theValueLocationIndex;
		}
		return this.valueLocationIndex;
	}

	/**
	 * Finds the position of an already-present `value` within the memoized value index via binary search, asserting it
	 * is indeed present. Shared by the mutation methods that locate an existing value before shifting the block start
	 * offsets of the values that follow it.
	 *
	 * @param valueIndex the memoized value index to search (obtained from {@link #getValueIndex})
	 * @param value      the value expected to be present in the index
	 * @return the index of `value` within `valueIndex`
	 */
	@SuppressWarnings("unchecked")
	private int findExistingValuePosition(@Nonnull ValueStartIndex[] valueIndex, @Nonnull Serializable value) {
		final int position = Arrays.binarySearch(
			valueIndex, new ValueStartIndex(value, this.valueComparator, -1),
			(o1, o2) -> this.valueComparator.compare(o1.getValue(), o2.getValue())
		);
		assertNotPresent(position >= 0, value);
		return position;
	}

	/**
	 * Computes start position for value at specified position in value index. The position is computed from previous
	 * value start position and previous value cardinality.
	 */
	private int getStartPositionFor(@Nonnull ValueStartIndex[] valueIndex, int position) {
		if (position == 0) {
			return 0;
		} else {
			final ValueStartIndex previousPosition = valueIndex[position - 1];
			final int previousPositionStart = previousPosition.getIndex();
			final int cardinality = this.sortIndex.getValueCardinality(previousPosition.getValue());
			return previousPositionStart + cardinality;
		}
	}

	/**
	 * Class that maintains information about record id block for certain value.
	 */
	@SuppressWarnings("rawtypes")
	@AllArgsConstructor
	static class ValueStartIndex implements Comparable<ValueStartIndex>, Serializable {
		@Serial private static final long serialVersionUID = -4953895484396265436L;

		/**
		 * The comparable value representing the sort key.
		 * This could be an attribute, timestamp, or any value used to determine ordering.
		 */
		@Getter private final Serializable value;
		/**
		 * The comparator used to compare the value with other values.
		 */
		private final Comparator valueComparator;
		/**
		 * Start index of the block of record IDs in the {@link SortIndex#sortedRecords} that belong to this value.
		 * This index points to where the records associated with the value begin in the sorted sequence.
		 */
		@Getter private int index;

		/**
		 * Increments start index of the block.
		 */
		public void increment() {
			this.index++;
		}

		/**
		 * Decrements start index of the block.
		 */
		public void decrement() {
			Assert.isPremiseValid(this.index > 0, "Index of the value start index cannot be negative!");
			this.index--;
		}

		@SuppressWarnings({"unchecked"})
		@Override
		public int compareTo(ValueStartIndex o) {
			return this.valueComparator.compare(this.value, o.value);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.value);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ValueStartIndex that = (ValueStartIndex) o;
			return this.value.equals(that.value);
		}

		@Override
		public String toString() {
			return this.value + ", " + this.index + '+';
		}

	}

}
