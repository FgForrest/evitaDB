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

import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Support class that handles isolated transactional changes upon an Comparable array.
 * This data object is not thread safe and contains modification layer data that can be merged with immutable delegate
 * array to produce new array with requested modifications.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@NotThreadSafe
public class ObjArrayChanges<T> {
	/**
	 * Unmodifiable underlying array.
	 */
	private final T[] delegate;
	/**
	 * Array of positions (indexes) in delegate array where insertions are expected to occur.
	 */
	private int[] insertions = ArrayUtils.EMPTY_INT_ARRAY;
	/**
	 * Two-dimensional array where there are recordIds (in second dimension) expected to be inserted at particular
	 * position in delegate. The position is retrieved from {@link #insertions} on the same index as index of first
	 * dimension in this array.
	 */
	@SuppressWarnings("unchecked")
	private InsertionBucket<T>[] insertedValues = new InsertionBucket[0];
	/**
	 * Array of positions (indexes) in delegate array where removals are expected to occur.
	 */
	private int[] removals = ArrayUtils.EMPTY_INT_ARRAY;
	/**
	 * Temporary intermediate result of the last {@link #getMergedArray()} operation. Nullified immediately with next
	 * change.
	 */
	@Nullable private T[] memoizedMergedArray;

	/**
	 * Computes closest modification operation that should occur upon the original array.
	 *
	 * @param nextInsertionPosition index of the next non-processed insertion command
	 * @param nextRemovalPosition   index of the next non-processed removal command
	 */
	private static void getNextOperations(int nextInsertionPosition, int nextRemovalPosition, @Nonnull ChangePlan plan) {
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

	ObjArrayChanges(@Nonnull T[] delegate) {
		this.delegate = delegate;
	}

	/**
	 * Returns index (position) of the record id in the array taking all changes into an account.
	 *
	 * @return negative value when record is not found, positive if found
	 */
	public int indexOf(@Nonnull T recordId, @Nonnull Comparator<T> comparator) {
		int index = -1;
		int removalIndex = 0;
		int insertIndex = 0;
		for (int i = 0; i <= this.delegate.length; i++) {
			// add inserted values
			if (this.insertions.length > 0 && this.insertions[insertIndex] == i) {
				final InsertionBucket<T> insertedRecordIds = this.insertedValues[insertIndex];
				final int insertedIndex = Arrays.binarySearch(insertedRecordIds.getInsertedValues(), recordId, comparator);
				if (insertedIndex >= 0) {
					return index + insertedIndex + 1;
				} else {
					index += insertedRecordIds.size();
				}
			}
			// count value from original array
			if (i < this.delegate.length) {
				index++;
			}
			// subtract value from original array
			final boolean replaceOriginal = this.removals.length > 0 && this.removals[removalIndex] == i;
			if (replaceOriginal) {
				index--;
			}
			// if not found in original array and reached end - return -1
			if (this.delegate.length == i) {
				return -1;
			}
			// if found in original array - return actual position
			if (comparator.compare(this.delegate[i], recordId) == 0 && !replaceOriginal) {
				// value found in original array
				return index;
			}
			// move pointers when we reach altered index in original array
			if (this.removals.length > removalIndex + 1 && this.removals[removalIndex] == i) {
				removalIndex++;
			}
			if (this.insertions.length > insertIndex + 1 && this.insertions[insertIndex] == i) {
				insertIndex++;
			}
		}
		return -1;
	}

	/**
	 * Returns set of inserted record ids on specified position of the array.
	 */
	@Nullable
	public T[] getInsertionOnPosition(int position) {
		int index = Arrays.binarySearch(this.insertions, position);
		return index >= 0 ? this.insertedValues[index].getInsertedValues() : null;
	}

	/**
	 * Returns true if record on certain position in the original array was removed.
	 */
	public boolean isRemovalOnPosition(int position) {
		return Arrays.binarySearch(this.removals, position) >= 0;
	}

	/**
	 * Returns true if passed recordId is part of the modified delegate array. I.e. whether it was newly inserted or
	 * contained in original array and not removed so far.
	 */
	boolean contains(@Nonnull T recordId, @Nonnull Comparator<T> comparator) {
		final int delegateIndex = Arrays.binarySearch(this.delegate, recordId, comparator);
		if (delegateIndex >= 0) {
			return Arrays.binarySearch(this.removals, delegateIndex) < 0;
		} else {
			for (InsertionBucket<T> insertedValue : this.insertedValues) {
				if (Arrays.binarySearch(insertedValue.getInsertedValues(), recordId, comparator) >= 0) {
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * Adds new recordId to the array (only when not already present).
	 * This operation also nullifies previous record id removal (if any).
	 */
	void addRecordId(@Nonnull T recordId, @Nonnull Comparator<T> comparator) {
		final InsertionPosition position = ArrayUtils.computeInsertPositionOfObjInOrderedArray(recordId, this.delegate, comparator);
		// record id was already part of the array, but may have been removed
		if (position.alreadyPresent()) {
			int removalIndex = Arrays.binarySearch(this.removals, position.position());
			if (removalIndex >= 0) {
				// just remove the position from the removals
				this.removals = ArrayUtils.removeIntFromArrayOnIndex(this.removals, removalIndex);
			}
		} else {
			// compute expected position of the record
			final int index = Arrays.binarySearch(this.insertions, position.position());
			if (index >= 0) {
				// if there is already waiting array of appended record append also this record id there
				this.insertedValues[index].addRecord(recordId, comparator);
			} else {
				// if not - create new list of additions on expected position
				final int startIndex = -1 * (index) - 1;
				this.insertions = ArrayUtils.insertIntIntoArrayOnIndex(position.position(), this.insertions, startIndex);
				final Class<?> componentType = this.delegate.getClass().getComponentType();
				this.insertedValues = ArrayUtils.insertRecordIntoArrayOnIndex(new InsertionBucket<>(recordId, componentType), this.insertedValues, startIndex);
			}
		}
		// nullify memoized result that becomes obsolete by this operation
		this.memoizedMergedArray = null;
	}

	/**
	 * Removes recordId from the array (only when present).
	 * This operation also nullifies previous record id insertion (if any).
	 */
	void removeRecordId(@Nonnull T recordId, @Nonnull Comparator<T> comparator) {
		final int position = Arrays.binarySearch(this.delegate, recordId, comparator);
		// check whether the record is part of the original array
		if (position >= 0) {
			// if so, mark this position for removal (this operation is idempotent)
			this.removals = ArrayUtils.insertIntIntoOrderedArray(position, this.removals);
		} else {
			// record is not part of the original array but might be present on change layer
			final int changePosition = ArrayUtils.computeInsertPositionOfObjInOrderedArray(recordId, this.delegate, comparator).position();
			int insertionIndex = Arrays.binarySearch(this.insertions, changePosition);
			if (insertionIndex >= 0) {
				// yes the record was added recently and we need to rollback this insertion
				this.insertedValues[insertionIndex].removeRecord(recordId, comparator);
				if (this.insertedValues[insertionIndex].isEmpty()) {
					// inserted values are now empty, we need to shrink insertion arrays
					this.insertions = ArrayUtils.removeIntFromArrayOnIndex(this.insertions, insertionIndex);
					this.insertedValues = ArrayUtils.removeRecordFromArrayOnIndex(this.insertedValues, insertionIndex);
				}
			}
		}
		// nullify memoized result that becomes obsolete by this operation
		this.memoizedMergedArray = null;
	}

	/**
	 * This method computes new array from the immutable original array and the set of insertions / removals made upon
	 * it.
	 */
	@Nonnull
	T[] getMergedArray() {
		if (this.insertions.length == 0 && this.removals.length == 0) {
			// if there are no insertions / removals - return the original
			return this.delegate;
		} else {
			// compute results only when we can't reuse previous computation
			if (this.memoizedMergedArray == null) {
				// create new array that will be filled with updated data
				@SuppressWarnings("unchecked") final T[] computedArray = (T[]) Array.newInstance(this.delegate.getClass().getComponentType(), getMergedLength());
				int lastPosition = 0;
				int lastComputedPosition = 0;

				int insPositionIndex = -1;
				int nextInsertionPosition = this.insertions.length > 0 ? this.insertions[0] : -1;

				int remPositionIndex = -1;
				int nextRemovalPosition = this.removals.length > 0 ? this.removals[0] : -1;

				// from left to right get first position with change operations
				final ChangePlan plan = new ChangePlan();
				getNextOperations(nextInsertionPosition, nextRemovalPosition, plan);

				while (plan.hasAnythingToDo()) {
					if (plan.bothOperationsRequested()) {
						// both insertion and removal occurred on this position - move indexes in both insertion and removal arrays
						insPositionIndex++;
						remPositionIndex++;

						// insert requested records in to the target array and skip removed record from original array
						final InsertionBucket<T> insertedRecords = this.insertedValues[insPositionIndex];
						final int originalCopyLength = plan.getPosition() - lastPosition;
						System.arraycopy(this.delegate, lastPosition, computedArray, lastComputedPosition, originalCopyLength);
						final int insertedLength = insertedRecords.size();
						System.arraycopy(insertedRecords.getInsertedValues(), 0, computedArray, lastComputedPosition + originalCopyLength, insertedLength);
						lastPosition = plan.getPosition() + 1;
						lastComputedPosition = lastComputedPosition + originalCopyLength + insertedLength;

						// move insertions / removal cursors - if there are any
						nextInsertionPosition = this.insertions.length > insPositionIndex + 1 ? this.insertions[insPositionIndex + 1] : -1;
						nextRemovalPosition = this.removals.length > remPositionIndex + 1 ? this.removals[remPositionIndex + 1] : -1;

					} else {
						if (plan.isInsertion()) {
							// insertion is requested on specified position - move index in insertion array
							insPositionIndex++;

							// insert requested records in to the target array and after the existing record in original array
							final InsertionBucket<T> insertedRecords = this.insertedValues[insPositionIndex];
							final int originalCopyLength = plan.getPosition() - lastPosition;
							System.arraycopy(this.delegate, lastPosition, computedArray, lastComputedPosition, originalCopyLength);
							final int insertedLength = insertedRecords.size();
							System.arraycopy(insertedRecords.getInsertedValues(), 0, computedArray, lastComputedPosition + originalCopyLength, insertedLength);
							lastPosition = plan.getPosition();
							lastComputedPosition = lastComputedPosition + originalCopyLength + insertedLength;

							// move insertions / removal cursors - if there are any
							nextInsertionPosition = this.insertions.length > insPositionIndex + 1 ? this.insertions[insPositionIndex + 1] : -1;

						} else {
							// removal is requested on specified position - move index in removal array
							remPositionIndex++;

							// copy contents of the original array skipping removed record
							final int originalCopyLength = plan.getPosition() - lastPosition;
							System.arraycopy(this.delegate, lastPosition, computedArray, lastComputedPosition, originalCopyLength);
							lastPosition = plan.getPosition() + 1;
							lastComputedPosition = lastComputedPosition + originalCopyLength;

							// move insertions / removal cursors - if there are any
							nextRemovalPosition = this.removals.length > remPositionIndex + 1 ? this.removals[remPositionIndex + 1] : -1;

						}
					}

					// plan next operations
					getNextOperations(nextInsertionPosition, nextRemovalPosition, plan);
				}

				// copy rest of the original array into the result (no operations were planned for this part)
				if (lastPosition < this.delegate.length) {
					System.arraycopy(this.delegate, lastPosition, computedArray, lastComputedPosition, this.delegate.length - lastPosition);
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
		int result = this.delegate.length - this.removals.length;
		for (InsertionBucket<T> insertedValue : this.insertedValues) {
			result += insertedValue.size();
		}
		return result;
	}

	/**
	 * Bucket contains all records on certain position.
	 */
	private static class InsertionBucket<T> {
		@Getter private T[] insertedValues;

		@SuppressWarnings("unchecked")
		public InsertionBucket(@Nonnull T insertedValue, @Nonnull Class<?> componentType) {
			this.insertedValues = (T[]) Array.newInstance(componentType, 1);
			this.insertedValues[0] = insertedValue;
		}

		public void addRecord(@Nonnull T recordId, @Nonnull Comparator<T> comparator) {
			this.insertedValues = ArrayUtils.insertRecordIntoOrderedArray(recordId, this.insertedValues, comparator);
		}

		public void removeRecord(@Nonnull T recordId, @Nonnull Comparator<T> comparator) {
			this.insertedValues = ArrayUtils.removeRecordFromOrderedArray(recordId, this.insertedValues, comparator);
		}

		public boolean isEmpty() {
			return this.insertedValues.length == 0;
		}

		public int size() {
			return this.insertedValues.length;
		}
	}

}
