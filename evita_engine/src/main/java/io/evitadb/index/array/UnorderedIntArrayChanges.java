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
import io.evitadb.utils.Assert;
import lombok.Data;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Arrays;

/**
 * Support class that handles isolated transactional changes upon an array.
 * This data object is not thread safe and contains modification layer data that can be merged with immutable delegate
 * array to produce new array with requested modifications.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@NotThreadSafe
public class UnorderedIntArrayChanges implements ArrayChangesIteratorSupport {
	private static final UnorderedLookup[] EMPTY_LOOKUP_ARRAY = new UnorderedLookup[0];
	/**
	 * The lookup from {@link TransactionalUnorderedIntArray}.
	 */
	private final UnorderedLookup delegate;
	/**
	 * Array of positions (indexes) in delegate array where insertions are expected to occur.
	 */
	private int[] insertions = ArrayUtils.EMPTY_INT_ARRAY;
	/**
	 * Array where there are recordIds expected to be inserted at particular position in delegate. The position is
	 * retrieved from {@link #insertions} on the same index as index in this array.
	 */
	private UnorderedLookup[] insertedValues = EMPTY_LOOKUP_ARRAY;
	/**
	 * Array of positions (indexes) in delegate array where removals are expected to occur.
	 */
	private int[] removals = ArrayUtils.EMPTY_INT_ARRAY;
	/**
	 * Temporary intermediate result of the last {@link #getMergedArray()} operation. Nullified
	 * immediately with next change.
	 */
	private int[] memoizedMergedArray;

	/**
	 * Computes closest modification operation that should occur upon the original array.
	 *
	 * @param nextInsertionPosition index of the next non-processed insertion command
	 * @param nextRemovalPosition   index of the next non-processed removal command
	 */
	private static void getNextOperations(int nextInsertionPosition, int nextRemovalPosition, ChangePlan plan) {
		if (nextInsertionPosition >= 0) {
			if (nextRemovalPosition == -1 || nextRemovalPosition > nextInsertionPosition) {
				plan.planInsertOperation(nextInsertionPosition);
			} else if (nextInsertionPosition == nextRemovalPosition) {
				plan.planBothOperations(nextInsertionPosition);
			} else {
				plan.planRemovalOperation(nextRemovalPosition);
			}
		} else if (nextRemovalPosition >= 0 && nextInsertionPosition == -1) {
			plan.planRemovalOperation(nextRemovalPosition);
		} else {
			plan.noOperations();
		}
	}

	public UnorderedIntArrayChanges(@Nonnull UnorderedLookup delegate) {
		this.delegate = delegate;
	}

	/**
	 * Returns true if passed recordId is part of the modified delegate array. I.e. whether it was newly inserted or
	 * contained in original array and not removed so far.
	 */
	public boolean contains(int recordId) {
		final int delegateIndex = this.delegate.findPosition(recordId);
		boolean result = delegateIndex >= 0 && Arrays.binarySearch(this.removals, delegateIndex) < 0;

		if (!result) {
			for (UnorderedLookup insertedValue : this.insertedValues) {
				result = insertedValue.findPosition(recordId) >= 0;
				if (result) {
					break;
				}
			}
		}

		return result;
	}

	/**
	 * Returns index of the record id in the unordered array taking changes into an account.
	 *
	 * @return -1 if record id was not found (or was removed)
	 */
	public int indexOf(int recordId) {
		int delegateIndex = this.delegate.findPosition(recordId);

		int removalsCount = 0;
		int insertsCount = 0;
		if (delegateIndex < 0) {
			// record was not present in the delegate array - check insertions
			int insertPosition = -1;
			for (int i = 0; i < this.insertedValues.length; i++) {
				final UnorderedLookup insertedValue = this.insertedValues[i];
				final int position = insertedValue.findPosition(recordId);
				if (position >= 0) {
					insertPosition = position;
					delegateIndex = this.insertions[i];
					break;
				}
			}
			if (insertPosition < 0) {
				return -1;
			}
			for (int removal : this.removals) {
				if (removal < delegateIndex) {
					removalsCount++;
				} else {
					break;
				}
			}
			for (int i = 0; i < this.insertions.length; i++) {
				final int insertion = this.insertions[i];
				if (insertion < delegateIndex) {
					insertsCount += this.insertedValues[i].size();
				} else if (insertion == delegateIndex) {
					insertsCount += insertPosition;
				} else {
					break;
				}
			}
		} else {
			// record was present in the delegate array - count for removals and insertions
			boolean wasRemoved = false;
			for (int removal : this.removals) {
				if (removal < delegateIndex) {
					removalsCount++;
				} else if (removal == delegateIndex) {
					wasRemoved = true;
				} else {
					break;
				}
			}
			// count insertions
			boolean wasAdded = false;
			for (int i = 0; i < this.insertions.length; i++) {
				final int insertion = this.insertions[i];
				if (insertion <= delegateIndex || wasRemoved) {
					final UnorderedLookup insertedValue = this.insertedValues[i];
					final int position = insertedValue.findPosition(recordId);
					if (position < 0) {
						insertsCount += insertedValue.size();
					} else {
						insertsCount += position;
						wasAdded = true;
					}
				} else {
					break;
				}
			}

			if (wasRemoved && !wasAdded) {
				return -1;
			}
		}
		return Math.max(delegateIndex, 0) + insertsCount - removalsCount;
	}

	/**
	 * Method returns last record in the array.
	 *
	 * @return record id
	 * @throws ArrayIndexOutOfBoundsException when array is empty
	 */
	public int getLastRecordId() throws ArrayIndexOutOfBoundsException {
		if (this.memoizedMergedArray != null) {
			return this.memoizedMergedArray[this.memoizedMergedArray.length - 1];
		} else {
			final int[] recordIds = this.delegate.getRecordIds();
			// find out the last non-removed element from the original array
			int lastRealElement = recordIds.length - 1;
			while (Arrays.binarySearch(this.removals, lastRealElement) >= 0) {
				lastRealElement--;
			}
			// if anything was appended at the end of the array
			for (int i = this.insertions.length - 1; i >= 0; i--) {
				if (this.insertions[i] > lastRealElement) {
					return this.insertedValues[i].getLastRecordId();
				} else if (this.insertions[i] < lastRealElement) {
					break;
				}
			}

			// else return the last record of the underlying array
			return this.delegate.getRecordAt(lastRealElement);
		}
	}

	/**
	 * Returns set of inserted record ids on specified position of the array.
	 */
	@Override
	public int[] getInsertionOnPosition(int position) {
		int index = Arrays.binarySearch(this.insertions, position);
		return index >= 0 ? this.insertedValues[index].getArray() : null;
	}

	/**
	 * Returns true if record on certain position in the original array was removed.
	 */
	@Override
	public boolean isRemovalOnPosition(int position) {
		return Arrays.binarySearch(this.removals, position) >= 0;
	}

	/**
	 * Adds new recordId to the array (only when not already present) on the position just after the `previousRecordId`.
	 * This operation also nullifies previous record id removal (if any).
	 */
	void addIntAfterRecord(int previousRecordId, int recordId) {
		final PositionLookup prevRecLookup = new PositionLookup(this.delegate, this.removals, this.insertions, this.insertedValues, previousRecordId);
		final PositionLookup recLookup = new PositionLookup(this.delegate, this.removals, this.insertions, this.insertedValues, recordId);

		Assert.isTrue(
			previousRecordId == Integer.MIN_VALUE || prevRecLookup.exists(),
			"Previous record " + previousRecordId + " is not present in the array!"
		);

		int len = this.insertions.length;

		// added record was already inserted / moved in this transaction, remove original command so that new can be created
		if (recLookup.isPresentInDiff()) {
			removeIntOnChangePosition(recordId, recLookup.getInsertionIndex());
		}

		// move or insertion is required
		if (prevRecLookup.isPresentInDiff()) {
			// simple case - just add record in existing diff after the prev record
			this.insertedValues[prevRecLookup.getInsertionIndex()].addRecord(previousRecordId, recordId);
			// if rec already exists in current array order a removal of it
			if (recLookup.isPresentInBase()) {
				this.removals = ArrayUtils.insertIntIntoOrderedArray(recordId, this.removals);
			}
			this.memoizedMergedArray = null;
		} else if (prevRecLookup.getExistingPosition() != recLookup.getExistingPosition() - 1) {
			final int position = prevRecLookup.getExistingPosition() == Integer.MIN_VALUE ? 0 : prevRecLookup.getExistingPosition() + 1;
			if (this.delegate.size() > position && this.delegate.getRecordAt(position) == recordId && Arrays.binarySearch(this.removals, position) >= 0) {
				this.removals = ArrayUtils.removeIntFromOrderedArray(position, this.removals);
				final int insertsOnThePosition = Arrays.binarySearch(this.insertions, position);
				/* if there are insertions at the restored spot */
				if (insertsOnThePosition >= 0) {
					this.insertions[insertsOnThePosition]++;
					if (insertsOnThePosition + 1 < this.insertions.length && this.insertions[insertsOnThePosition + 1] == this.insertions[insertsOnThePosition]) {
						this.insertions = ArrayUtils.removeIntFromArrayOnIndex(this.insertions, insertsOnThePosition + 1);
						this.insertedValues[insertsOnThePosition].appendRecords(this.insertedValues[insertsOnThePosition + 1].getArray());
						this.insertedValues = ArrayUtils.removeRecordFromArrayOnIndex(this.insertedValues, insertsOnThePosition + 1);
					}
				}
			} else {
				final int insertsOnThePosition = Arrays.binarySearch(this.insertions, position);
				if (insertsOnThePosition >= 0) {
					// just add another inserted value
					final UnorderedLookup newInsertedValues = this.insertedValues[insertsOnThePosition];
					newInsertedValues.addRecord(Integer.MIN_VALUE, recordId);
				} else {
					final int startIndex = -1 * insertsOnThePosition - 1;
					final int targetSize = len + 1;
					final int suffixLength = this.insertions.length - startIndex;

					setupInsertionArraysForRecord(recordId, position, startIndex, targetSize, suffixLength);
				}
			}
			this.memoizedMergedArray = null;
		} else {
			// do nothing the record is already on proper place
		}
	}

	/**
	 * Adds new recordId to the array (only when not already present) on the specified index.
	 * This operation also nullifies previous record id removal (if any).
	 */
	void addIntOnIndex(int index, int recordId) {
		final PositionLookup recLookup = new PositionLookup(this.delegate, this.removals, this.insertions, this.insertedValues, recordId);

		int len = this.insertions.length;

		// added record was already inserted / moved in this transaction, remove original command so that new can be created
		if (recLookup.isPresentInDiff()) {
			removeIntOnChangePosition(recordId, recLookup.getInsertionIndex());
		}

		int insertionIndex = 0;
		int removalIndex = 0;
		int relativeIndex = index;
		for (int i = 0; i <= this.delegate.size(); i++) {
			if (this.insertions.length > insertionIndex && this.insertions[insertionIndex] == i) {
				relativeIndex -= this.insertedValues[insertionIndex].size();
				if (relativeIndex <= 0) {
					this.insertedValues[insertionIndex].addRecordOnIndex(this.insertedValues[insertionIndex].size() + relativeIndex, recordId);
					this.memoizedMergedArray = null;
					return;
				}
				insertionIndex++;
			}
			if (this.removals.length > removalIndex && this.removals[removalIndex] == i) {
				relativeIndex++;
				removalIndex++;
			}
			if (relativeIndex == 0) {
				final int targetSize = len + 1;
				final int suffixLength = this.insertions.length - insertionIndex;

				setupInsertionArraysForRecord(recordId, i, insertionIndex, targetSize, suffixLength);

				this.memoizedMergedArray = null;
				return;
			}
			relativeIndex--;
		}
	}

	/**
	 * Appends record ids at the end of the array
	 * @param recordIds unsorted array of record ids
	 */
	public void appendRecords(int[] recordIds) {
		final int lastPositionInsertion = Arrays.binarySearch(this.insertions, this.delegate.getRecordIds().length);
		if (lastPositionInsertion >= 0) {
			this.insertedValues[lastPositionInsertion].appendRecords(recordIds);
		} else {
			final int[] newInsertions = new int[this.insertions.length + 1];
			System.arraycopy(this.insertions, 0, newInsertions, 0, this.insertions.length);
			newInsertions[this.insertions.length] = this.delegate.getRecordIds().length;

			final UnorderedLookup[] newInsertedValues = new UnorderedLookup[this.insertedValues.length + 1];
			System.arraycopy(this.insertedValues, 0, newInsertedValues, 0, this.insertedValues.length);
			newInsertedValues[this.insertedValues.length] = new UnorderedLookup(recordIds);

			this.insertions = newInsertions;
			this.insertedValues = newInsertedValues;
		}
		this.memoizedMergedArray = null;
	}

	/**
	 * Removes recordId from the array on the specified position.
	 * This operation also nullifies previous record id insertion (if any).
	 */
	void removeRecord(int recordId) {
		final PositionLookup recLookup = new PositionLookup(this.delegate, this.removals, this.insertions, this.insertedValues, recordId);
		if (recLookup.isPresentInDiff()) {
			// record was added / moved in the same transaction, just remove it from diff
			removeIntOnChangePosition(recordId, recLookup.getInsertionIndex());
			this.memoizedMergedArray = null;
		} else if (recLookup.isPresentInBase()) {
			// add the record to the list of removed records in this transaction
			this.removals = ArrayUtils.insertIntIntoOrderedArray(recLookup.existingPosition, this.removals);
			this.memoizedMergedArray = null;
		} else {
			throw new GenericEvitaInternalError(
				"Record id " + recordId + " is not part of the array!",
				"Record id is not part of the array."
			);
		}
	}

	/**
	 * Method removes all records between two indexes.
	 *
	 * @param startIndex inclusive
	 * @param endIndex exclusive
	 * @return removed records
	 */
	public int[] removeRange(int startIndex, int endIndex) {
		final int[] mergedArray = getMergedArray();
		for (int i = startIndex; i < endIndex; i++) {
			removeRecord(mergedArray[i]);
		}
		return Arrays.copyOfRange(mergedArray, startIndex, endIndex);
	}

	/**
	 * This method computes new array from the immutable original array and the set of insertions / removals made upon
	 * it.
	 */
	int[] getMergedArray() {
		if (this.insertions.length == 0 && this.removals.length == 0) {
			// if there are no insertions / removals - return the original
			return this.delegate.getArray();
		} else {
			// compute results only when we can't reuse previous computation
			if (this.memoizedMergedArray == null) {
				// create new array that will be filled with updated data
				final int[] computedArray = new int[getMergedLength()];
				int lastPosition = 0;
				int lastComputedPosition = 0;

				int insPositionIndex = -1;
				int nextInsertionPosition = this.insertions.length > 0 ? this.insertions[insPositionIndex + 1] : -1;

				int remPositionIndex = -1;
				int nextRemovalPosition = this.removals.length > 0 ? this.removals[remPositionIndex + 1] : -1;

				// from left to right get first position with change operations
				final ChangePlan plan = new ChangePlan();
				getNextOperations(nextInsertionPosition, nextRemovalPosition, plan);

				while (plan.hasAnythingToDo()) {
					final int position = plan.getPosition();
					if (plan.bothOperationsRequested()) {
						// both insertion and removal occurred on this position - move indexes in both insertion and removal arrays
						insPositionIndex++;
						remPositionIndex++;

						// insert requested records in to the target array and skip removed record from original array
						final UnorderedLookup insertedRecords = this.insertedValues[insPositionIndex];
						final int originalCopyLength = position - lastPosition;
						System.arraycopy(this.delegate.getArray(), lastPosition, computedArray, lastComputedPosition, originalCopyLength);
						final int insertedLength = insertedRecords.size();
						System.arraycopy(insertedRecords.getArray(), 0, computedArray, lastComputedPosition + originalCopyLength, insertedLength);
						lastPosition = position + 1;
						lastComputedPosition = lastComputedPosition + originalCopyLength + insertedLength;

					} else {
						if (plan.isInsertion()) {
							// insertion is requested on specified position - move index in insertion array
							insPositionIndex++;

							// insert requested records in to the target array and after the existing record in original array
							final UnorderedLookup insertedRecords = this.insertedValues[insPositionIndex];
							final int originalCopyLength = position - lastPosition;
							System.arraycopy(this.delegate.getArray(), lastPosition, computedArray, lastComputedPosition, originalCopyLength);
							final int insertedLength = insertedRecords.size();
							System.arraycopy(insertedRecords.getArray(), 0, computedArray, lastComputedPosition + originalCopyLength, insertedLength);
							lastPosition = position;
							lastComputedPosition = lastComputedPosition + originalCopyLength + insertedLength;
						} else {
							// removal is requested on specified position - move index in removal array
							remPositionIndex++;

							// copy contents of the original array skipping removed record
							final int originalCopyLength = position - lastPosition;
							System.arraycopy(this.delegate.getArray(), lastPosition, computedArray, lastComputedPosition, originalCopyLength);
							lastPosition = position + 1;
							lastComputedPosition = lastComputedPosition + originalCopyLength;
						}
					}

					// move insertions / removal cursors - if there are any
					nextInsertionPosition = this.insertions.length > insPositionIndex + 1 ? this.insertions[insPositionIndex + 1] : -1;
					nextRemovalPosition = this.removals.length > remPositionIndex + 1 ? this.removals[remPositionIndex + 1] : -1;

					// plan next operations
					getNextOperations(nextInsertionPosition, nextRemovalPosition, plan);
				}

				// copy rest of the original array into the result (no operations were planned for this part)
				if (lastPosition < this.delegate.size()) {
					System.arraycopy(this.delegate.getArray(), lastPosition, computedArray, lastComputedPosition, this.delegate.size() - lastPosition);
				}

				// memoize costly computation and return
				this.memoizedMergedArray = computedArray;
				return computedArray;
			} else {
				// quickly return previous result
				return this.memoizedMergedArray;
			}
		}
	}

	/**
	 * Computes length of the array with all requested changes applied.
	 */
	int getMergedLength() {
		int result = this.delegate.size() - this.removals.length;
		for (UnorderedLookup insertedValue : this.insertedValues) {
			result += insertedValue.size();
		}
		return result;
	}

	/**
	 * Creates new item for inserted values in local memory.
	 */
	private void setupInsertionArraysForRecord(int recordId, int position, int startIndex, int targetSize, int suffixLength) {
		int[] newInsertions = new int[targetSize];
		System.arraycopy(this.insertions, 0, newInsertions, 0, startIndex);
		System.arraycopy(this.insertions, startIndex, newInsertions, startIndex + 1, suffixLength);
		newInsertions[startIndex] = position;
		this.insertions = newInsertions;

		UnorderedLookup[] newInsertedValues = new UnorderedLookup[targetSize];
		System.arraycopy(this.insertedValues, 0, newInsertedValues, 0, startIndex);
		System.arraycopy(this.insertedValues, startIndex, newInsertedValues, startIndex + 1, suffixLength);
		newInsertedValues[startIndex] = new UnorderedLookup(recordId);
		this.insertedValues = newInsertedValues;
	}

	/**
	 * Removes record id that is present in diff index (i.e. was not in original array but was added in the meantime).
	 */
	private void removeIntOnChangePosition(int recordId, int insertionIndex) {
		UnorderedLookup diffValues = this.insertedValues[insertionIndex];
		diffValues.removeRecord(recordId);
		if (diffValues.size() == 0) {
			int[] newInsertions = new int[this.insertions.length - 1];
			System.arraycopy(this.insertions, 0, newInsertions, 0, insertionIndex);
			System.arraycopy(this.insertions, insertionIndex + 1, newInsertions, insertionIndex, this.insertions.length - insertionIndex - 1);
			this.insertions = newInsertions;

			UnorderedLookup[] newInsertedValues = new UnorderedLookup[this.insertedValues.length - 1];
			System.arraycopy(this.insertedValues, 0, newInsertedValues, 0, insertionIndex);
			System.arraycopy(this.insertedValues, insertionIndex + 1, newInsertedValues, insertionIndex, this.insertedValues.length - insertionIndex - 1);
			this.insertedValues = newInsertedValues;
		}

		this.memoizedMergedArray = null;
	}

	/**
	 * Class that wraps lookup into the diff object and original one.
	 */
	@Data
	private static class PositionLookup {
		/**
		 * Index of the record id in the original array.
		 */
		private final int existingPosition;
		/**
		 * Index of the chunk of insertions in the diff object where record id is present.
		 */
		private final int insertionIndex;
		/**
		 * Index of the insertion chunk where record id is exactly present (i.e. insertions[insertionIndex][diffPosition])
		 */
		private final int diffPosition;

		public PositionLookup(@Nonnull UnorderedLookup delegate, @Nonnull int[] removals, @Nonnull int[] insertions, @Nonnull UnorderedLookup[] insertedValues, int recordId) {
			int theExistingPosition = delegate.findPosition(recordId);
			if (theExistingPosition >= 0 && Arrays.binarySearch(removals, theExistingPosition) >= 0) {
				theExistingPosition = Integer.MIN_VALUE;
			}
			int theDiffPosition = Integer.MIN_VALUE;
			int theInsertionIndex = Integer.MIN_VALUE;

			if (theExistingPosition < 0) {
				for (int i = 0; i < insertedValues.length; i++) {
					final int insertedPosition = insertions[i];
					final UnorderedLookup insertedValue = insertedValues[i];
					final int diffInsertedPosition = insertedValue.findPosition(recordId);
					if (diffInsertedPosition >= 0) {
						theInsertionIndex = i;
						theExistingPosition = insertedPosition;
						theDiffPosition = diffInsertedPosition;
						break;
					}
				}
			}

			this.insertionIndex = theInsertionIndex;
			this.existingPosition = theExistingPosition;
			this.diffPosition = theDiffPosition;
		}

		/**
		 * Returns true if the record id was newly added (i.e. was not in the original array).
		 */
		public boolean isPresentInDiff() {
			return this.diffPosition >= 0;
		}

		/**
		 * Returns true if the record id was present in the original array.
		 */
		public boolean isPresentInBase() {
			return this.existingPosition >= 0;
		}

		/**
		 * Returns true if the record id is already present in the transactional array (either in diff part or in the
		 * original array).
		 */
		public boolean exists() {
			return isPresentInDiff() || isPresentInBase();
		}

	}

}
