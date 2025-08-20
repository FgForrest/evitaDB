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

import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import static io.evitadb.core.Transaction.getTransactionalLayerMaintainer;
import static io.evitadb.core.Transaction.suppressTransactionalMemoryLayerFor;
import static io.evitadb.core.Transaction.suppressTransactionalMemoryLayerForWithResult;
import static java.util.Optional.ofNullable;

/**
 * Support class that handles isolated transactional changes upon an array.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@NotThreadSafe
@SuppressWarnings("unchecked")
class ComplexObjArrayChanges<T extends TransactionalObject<T, ?> & Comparable<T>> {
	/**
	 * Keeps type of the object that is monitored in changes.
	 */
	@Nonnull private final Class<T> objectType;
	/**
	 * Contains reference to original immutable array of objects.
	 */
	@Nonnull private final T[] original;
	/**
	 * This is a consumer function that adds second argument to the first argument. Since first argument is transactional
	 * object this addition doesn't affect the original "wrapped" object but only it's transactional layer.
	 *
	 * If this effect would not be in place, BiFunction<T,T,T> would be more appropriate here.
	 */
	@Nullable private final BiConsumer<T, T> combiner;
	/**
	 * This is a consumer function that subtracts second argument from the first argument. Since first argument is transactional
	 * object this reduction doesn't affect the original "wrapped" object but only it's transactional layer.
	 *
	 * If this effect would not be in place, BiFunction<T,T,T> would be more appropriate here.
	 */
	@Nullable private final BiConsumer<T, T> reducer;
	/**
	 * This predicate determines whether object T is effectively empty = unnecessary and might be removed entirely.
	 * Obsolete checker is used after reducer to check whether the reduction fully emptied the container and the container
	 * itself might be removed.
	 */
	@Nullable private final Predicate<T> obsoleteChecker;
	/**
	 * This comparator is used to sort values in a map.
	 */
	private final Comparator<T> comparator;
	/**
	 * This function deeply compares contents of the object T with another object T and returns true if the objects
	 * has same deep contents.
	 */
	@Nullable private final BiPredicate<T, T> deepComparator;

	/**
	 * Array of positions (indexes) in delegate array where insertions are expected to occur.
	 */
	private int[] insertions = ArrayUtils.EMPTY_INT_ARRAY;
	/**
	 * Two-dimensional array where there are recordIds (in second dimension) expected to be inserted at particular
	 * position in delegate. The position is retrieved from {@link #insertions} on the same index as index of first
	 * dimension in this array.
	 */
	private T[][] insertedValues;
	/**
	 * Array of positions (indexes) in delegate array where removals are expected to occur.
	 */
	private int[] removals = ArrayUtils.EMPTY_INT_ARRAY;
	/**
	 * Array of entirely removed records. Their order conform to the order of the {@link #removals} indexes.
	 */
	private T[] removedValues;
	/**
	 * Temporary intermediate result of the last {@link #getMergedArray()} operation. Nullified immediately with next
	 * change.
	 */
	@Nullable private T[] memoizedMergedArray;

	/**
	 * Creates new instance of the record array with applied transactional changes if transactional layer is available.
	 */
	@Nonnull
	private static <T> T[] getTransactionalCopy(
		@Nullable TransactionalLayerMaintainer transactionalLayer,
		@Nonnull Class<T> objectType,
		@Nonnull T[] values
	) {
		T[] delegateArray;
		if (transactionalLayer != null) {
			final T[] delegateCopy = (T[]) Array.newInstance(objectType, values.length);
			for (int i = 0; i < values.length; i++) {
				T item = values[i];
				delegateCopy[i] = getTransactionalCopy(transactionalLayer, (TransactionalLayerProducer<?, ?>) item);
			}
			delegateArray = delegateCopy;
		} else {
			delegateArray = values;
		}
		return delegateArray;
	}

	/**
	 * Creates new instance of the record with applied transactional changes if transactional layer is available.
	 */
	@Nonnull
	private static <T> T getTransactionalCopy(
		@Nullable TransactionalLayerMaintainer transactionalLayer,
		@Nonnull TransactionalLayerProducer<?, ?> value
	) {
		return transactionalLayer == null ? (T) value : (T) transactionalLayer.getStateCopyWithCommittedChanges(value);
	}

	/**
	 * Creates new instance of the record with applied transactional changes if transactional layer is available.
	 * Transactional state of the original object is not discarded by this operation.
	 */
	@Nonnull
	private static <T> T getTransactionalCopyWithoutDiscardingState(
		@Nullable TransactionalLayerMaintainer transactionalLayer,
		@Nonnull TransactionalLayerProducer<?, ?> value
	) {
		return transactionalLayer == null ? (T) value : (T) transactionalLayer.getStateCopyWithCommittedChangesWithoutDiscardingState(value);
	}

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

	ComplexObjArrayChanges(@Nonnull Class<T> objectType, @Nonnull Comparator<T> comparator, @Nonnull T[] original) {
		this.objectType = objectType;
		this.original = original;
		this.combiner = null;
		this.reducer = null;
		this.obsoleteChecker = null;
		this.comparator = comparator;
		this.deepComparator = null;
		this.insertedValues = (T[][]) Array.newInstance(objectType, 0, 0);
		this.removedValues = (T[]) Array.newInstance(objectType, 0);
	}

	ComplexObjArrayChanges(@Nonnull Class<T> objectType, @Nonnull Comparator<T> comparator, @Nonnull T[] original, @Nonnull BiConsumer<T, T> combiner, @Nonnull BiConsumer<T, T> reducer, @Nonnull Predicate<T> obsoleteChecker, @Nonnull BiPredicate<T, T> deepComparator) {
		this.objectType = objectType;
		this.original = original;
		this.combiner = combiner;
		this.reducer = reducer;
		this.obsoleteChecker = obsoleteChecker;
		this.comparator = comparator;
		this.deepComparator = deepComparator;
		this.insertedValues = (T[][]) Array.newInstance(objectType, 0, 0);
		this.removedValues = (T[]) Array.newInstance(objectType, 0);
	}

	/**
	 * Computes the index when removal occurs for the array that would have transactional changes applied.
	 *
	 * @param position                   position of the original array where removal occurs
	 * @param insertedValuesRemovalIndex position in the inserted values where removal occurs
	 * @return combined information - the index where removal would occurred should all transactional changes have
	 * been applied
	 */
	public int computeRemovalIndex(int position, int insertedValuesRemovalIndex) {
		int removalIndex = 0;
		int insertionsPeek = 0;
		int removalsPeek = 0;
		for (int i = 0; i <= position; i++) {
			boolean reduced = false;
			if (this.removals.length > removalsPeek && this.removals[removalsPeek] == i) {
				// get record that is removed on that position
				final T removedValue = getRemovalOnPositionWithoutDiscardingState(getTransactionalLayerMaintainer(), i);
				// if reducer is present
				if (this.reducer != null) {
					// and the removed value doesn't happen on position of item in original array
					boolean sameAsOriginal = this.removals[removalsPeek] == position && this.comparator.compare(this.removedValues[removalsPeek], this.original[i]) == 0;
					if (!sameAsOriginal) {
						// clone the original item
						final T clonedOriginal = this.original[i].makeClone();
						// apply reducer
						suppressTransactionalMemoryLayerFor(
							clonedOriginal, it -> this.reducer.accept(it, removedValue)
						);
						// and if there is something left increase removal index
						if (this.obsoleteChecker == null || !this.obsoleteChecker.test(clonedOriginal)) {
							removalIndex++;
						} else {
							// otherwise just write down that this value was entirely removed
							reduced = true;
						}
					}
				}
				removalsPeek++;
			} else if (this.original.length > i && position > i) {
				// the original item is still present increase removal index
				removalIndex++;
			}
			if (this.insertions.length > insertionsPeek && this.insertions[insertionsPeek] == i) {
				// there are insertions at this index
				if (this.insertions[insertionsPeek] == position && insertedValuesRemovalIndex >= 0) {
					// we are on place where removal occurred, add only the second parameter of the method (relative index)
					removalIndex += insertedValuesRemovalIndex;
				} else {
					// add count of newly added values
					final T lastInsertedRecord = this.insertedValues[insertionsPeek][this.insertedValues[insertionsPeek].length - 1];
					boolean sameAsOriginal = this.original.length > i && this.comparator.compare(lastInsertedRecord, this.original[i]) == 0;
					// lower the value by one, if one of the modified values is the same as the original value (combinator applies)
					removalIndex += this.insertedValues[insertionsPeek].length - (sameAsOriginal && !reduced ? 1 : 0);
				}
				insertionsPeek++;
			}
		}
		return removalIndex;
	}

	/**
	 * Cleans information of all internal original and inserted data.
	 */
	public void cleanAll(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		for (T originalValue : this.original) {
			originalValue.removeLayer(transactionalLayer);
		}
		for (T removedValue : this.removedValues) {
			removedValue.removeLayer(transactionalLayer);
		}
		for (T[] insertedValues : this.insertedValues) {
			for (T insertedValue : insertedValues) {
				insertedValue.removeLayer(transactionalLayer);
			}
		}
	}

	/**
	 * Returns true if passed recordId is part of the modified delegate array. I.e. whether it was newly inserted or
	 * contained in original array and not removed so far.
	 */
	boolean contains(@Nonnull T recordId) {
		final int delegateIndex = Arrays.binarySearch(this.original, recordId, this.comparator);
		if (delegateIndex >= 0) {
			return Arrays.binarySearch(this.removals, delegateIndex) < 0;
		} else {
			for (T[] insertedValue : this.insertedValues) {
				if (Arrays.binarySearch(insertedValue, recordId, this.comparator) >= 0) {
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * Adds new recordId to the array (only when not already present).
	 * This operation also nullifies previous record id removal (if any).
	 * When {@link #combiner} is specified existing records might be enriched by additional internal data of added
	 * record. When {@link #combiner} is not defined and record is already present in the array insertion is ignored.
	 *
	 * @return two element array with indexes: 0 = index of the bucket, 1 = index of the record in bucket
	 */
	@Nonnull
	int[] addRecordOnPosition(@Nonnull T recordId, int position) {
		// first look whether there is not existing request for removal of the same object
		reduceRemovalOrder(recordId, position);

		// now execute the insertion
		final int index = Arrays.binarySearch(this.insertions, position);
		final int pointPosition;
		final int relativePosition;
		if (index >= 0) {
			pointPosition = index;
			relativePosition = addRecordToExistingSetAtInsertionPoint(recordId, index);
		} else {
			pointPosition = addRecordCreatingNewSetAtInsertionPoint(recordId, position, index);
			relativePosition = 0;
		}

		// nullify memoized array - this operation requires new result
		this.memoizedMergedArray = null;

		return new int[]{pointPosition, relativePosition};
	}

	/**
	 * Adds new recordId to the array (only when not already present).
	 * This operation also nullifies previous record id removal (if any).
	 * When {@link #combiner} is specified existing records might be enriched by additional internal data of added
	 * record. When {@link #combiner} is not defined and record is already present in the array insertion is ignored.
	 */
	int addRecordOnPositionComputingIndex(@Nonnull T recordId, int position) {
		final int[] positions = addRecordOnPosition(recordId, position);
		final int pointPosition = positions[0];
		final int relativePosition = positions[1];

		int accumulatedPosition = this.insertions[pointPosition];
		for (int i = 0; i < pointPosition; i++) {
			final T[] insertedValue = this.insertedValues[i];
			accumulatedPosition += insertedValue.length;
			if (this.comparator.compare(insertedValue[insertedValue.length - 1], this.original[this.insertions[i]]) == 0) {
				accumulatedPosition--;
			}
		}
		final BiPredicate<T, Integer> couldBeRemoved = this.obsoleteChecker == null ? (originalValue, examinedPosition) -> true : (originalValue, examinedPosition) -> {
			final T clonedValue = originalValue.makeClone();
			return Objects.requireNonNull(
				suppressTransactionalMemoryLayerForWithResult(clonedValue, it -> {
						final int insertPoint = Arrays.binarySearch(this.insertions, this.removals[examinedPosition]);
						if (insertPoint >= 0) {
							final T[] insertedValuesRef = this.insertedValues[insertPoint];
							final T insertedValue = insertedValuesRef[insertedValuesRef.length - 1];
							if (this.comparator.compare(insertedValue, it) == 0) {
								Assert.isPremiseValid(this.combiner != null, "Combiner must be defined when checking for obsolete records!");
								// we need to avoid creating additional transactional states here
								suppressTransactionalMemoryLayerFor(
									it, theIt -> this.combiner.accept(it, insertedValue)
								);
							}
						}
						// we need to avoid creating additional transactional states here
						Assert.isPremiseValid(this.reducer != null, "Reducer must be defined when checking for obsolete records!");
						suppressTransactionalMemoryLayerFor(
							it, theIt -> this.reducer.accept(theIt, this.removedValues[examinedPosition])
						);
						return this.obsoleteChecker.test(it);
					}
				)
			);
		};
		for (int i = 0; i < this.removals.length; i++) {
			int removal = this.removals[i];
			if (removal >= this.insertions[pointPosition]) {
				break;
			}
			if (couldBeRemoved.test(this.original[removal], i)) {
				accumulatedPosition--;
			}
		}
		return accumulatedPosition + relativePosition;
	}

	/**
	 * This method computes new array from the immutable original array and the set of insertions / removals made upon
	 * it. This method may be called repeatedly upon the transactional object and doesn't wipe the objects from
	 * the transactional memory.
	 */
	@Nonnull
	T[] getMergedArray() {
		return getMergedArray(null);
	}

	/**
	 * This method computes new array from the immutable original array and the set of insertions / removals made upon
	 * it. This Method may be called only ONCE when `transactionalLayer` is passed because it removes internal objects
	 * from the transactional memory and this makes the operation non-repeatable!
	 */
	@Nonnull
	T[] getMergedArray(@Nullable TransactionalLayerMaintainer transactionalLayer) {
		if (this.insertions.length == 0 && this.removals.length == 0) {
			// if there are no insertions / removals - return the original
			return getTransactionalCopy(transactionalLayer, this.objectType, this.original);
		} else {
			// compute results only when we can't reuse previous computation
			if (this.memoizedMergedArray == null || transactionalLayer != null) {
				// create new array that will be filled with updated data
				// we can't precisely compute it's size now - so we need to allocate array for biggest possible scenario
				final T[] computedArray = (T[]) Array.newInstance(this.objectType, getArrayLengthWithInsertionsOnly());
				int lastPosition = 0;
				int lastComputedPosition = 0;

				int insPositionIndex = -1;
				int nextInsertionPosition = this.insertions.length > 0 ? this.insertions[insPositionIndex + 1] : -1;

				int remPositionIndex = -1;
				int nextRemovalPosition = this.removals.length > 0 ? this.removals[remPositionIndex + 1] : -1;

				// from left to right get first position with change operations
				final ChangePlan plan = new ChangePlan();
				getNextOperations(nextInsertionPosition, nextRemovalPosition, plan);

				// create new array instance
				T[] delegateArray = getTransactionalCopy(transactionalLayer, this.objectType, this.original);

				// execute modification orders while there are any
				while (plan.hasAnythingToDo()) {
					// insertion operation is always processed first
					if (plan.bothOperationsRequested() || plan.isInsertion()) {
						// insertion is requested on specified position - move index in insertion array
						insPositionIndex++;

						// insert requested records in to the target array and after the existing record in original array
						final T[] insertedRecords = this.insertedValues[insPositionIndex];
						final int delegateCopyLength = plan.getPosition() - lastPosition;
						System.arraycopy(delegateArray, lastPosition, computedArray, lastComputedPosition, delegateCopyLength);

						// create copy from the inserted records at the position
						final T[] insertedRecordValues = getTransactionalCopy(transactionalLayer, this.objectType, insertedRecords);

						// compare last processed record in original array and first record to be inserted
						final T lastDelegateRecord = lastPosition + delegateCopyLength < delegateArray.length ? delegateArray[lastPosition + delegateCopyLength] : null;
						final T lastInsertedRecord = insertedRecordValues[insertedRecordValues.length - 1];
						if (lastDelegateRecord != null && this.comparator.compare(lastDelegateRecord, lastInsertedRecord) == 0) {
							// if they match - clone original record
							final T lastDelegateRecordClone = lastDelegateRecord.makeClone();
							// and if combiner is available - merge it to the cloned record
							if (this.combiner != null) {
								// we are inside transactional memory and combination would only record deltas - we need to avoid this and compute final contents
								suppressTransactionalMemoryLayerFor(
									lastDelegateRecordClone, it -> this.combiner.accept(it, lastInsertedRecord)
								);
							}

							// copy all new record except the first one that is equal to delegate clone
							final int insertedLength = insertedRecordValues.length - 1;
							System.arraycopy(insertedRecordValues, 0, computedArray, lastComputedPosition + delegateCopyLength, insertedLength);

							// advance peek position in the target array
							lastComputedPosition = lastComputedPosition + delegateCopyLength + insertedLength;

							// append the cloned record to the result
							computedArray[lastComputedPosition++] = lastDelegateRecordClone;

							// advance last position in the original array
							lastPosition = plan.getPosition() + 1;
						} else {
							// just insert all new values to the result
							final int insertedLength = insertedRecordValues.length;
							System.arraycopy(insertedRecordValues, 0, computedArray, lastComputedPosition + delegateCopyLength, insertedLength);

							// advance peek position in the target array
							lastComputedPosition = lastComputedPosition + delegateCopyLength + insertedLength;

							// advance last position in the original array
							lastPosition = plan.getPosition();
						}
					} else {
						// removal is requested on specified position - move index in removal array
						remPositionIndex++;

						// get record that is removed on that position
						final T removedValue = getRemovalOnPosition(transactionalLayer, plan.getPosition());

						// if last record in the result array match the removed record
						if (lastComputedPosition > 0 && this.comparator.compare(computedArray[lastComputedPosition - 1], removedValue) == 0) {
							// there is addition and removal on the same spot of computed array
							final T lastDelegateRecordClone = computedArray[lastComputedPosition - 1];

							// just reduce values on already added position, there is guarantee that at least something will stay after reducing
							if (this.reducer != null) {
								// we are inside transactional memory and reduction would only record deltas - we need to avoid this and compute final contents
								suppressTransactionalMemoryLayerFor(
									lastDelegateRecordClone, it -> this.reducer.accept(it, removedValue)
								);
							}

						} else {
							// there is just removal on the specified position
							final T lastDelegateRecordClone = delegateArray[plan.getPosition()].makeClone();

							// if reducer is available - reduce contents of the cloned record
							if (this.reducer != null) {
								// we are inside transactional memory and reduction would only record deltas - we need to avoid this and compute final contents
								suppressTransactionalMemoryLayerFor(
									lastDelegateRecordClone, it -> this.reducer.accept(it, removedValue)
								);
							}

							final int delegateCopyLength;
							// if the obsolete checker is not available or says that record is obsolete after reduction
							if (this.obsoleteChecker == null || this.obsoleteChecker.test(lastDelegateRecordClone)) {
								// remove it entirely
								delegateCopyLength = plan.getPosition() - lastPosition;
								System.arraycopy(delegateArray, lastPosition, computedArray, lastComputedPosition, delegateCopyLength);
							} else {
								// add the reduced clone to the result
								delegateCopyLength = plan.getPosition() - lastPosition + 1;
								System.arraycopy(delegateArray, lastPosition, computedArray, lastComputedPosition, delegateCopyLength);
								computedArray[lastComputedPosition + delegateCopyLength - 1] = lastDelegateRecordClone;
							}

							// advance last position in the original array
							lastPosition = plan.getPosition() + 1;

							// advance peek position in the target array
							lastComputedPosition = lastComputedPosition + delegateCopyLength;
						}
					}

					// move insertions / removal cursors - if there are any
					nextInsertionPosition = this.insertions.length > insPositionIndex + 1 ? this.insertions[insPositionIndex + 1] : -1;
					nextRemovalPosition = this.removals.length > remPositionIndex + 1 ? this.removals[remPositionIndex + 1] : -1;

					// plan next operations
					getNextOperations(nextInsertionPosition, nextRemovalPosition, plan);
				}

				// copy rest of the original array into the result (no operations were planned for this part)
				if (lastPosition < delegateArray.length) {
					final int length = delegateArray.length - lastPosition;
					System.arraycopy(delegateArray, lastPosition, computedArray, lastComputedPosition, length);
					lastComputedPosition += length;
				}

				// now shrink possibly too large array to real size
				final T[] resultArray = Arrays.copyOf(computedArray, lastComputedPosition);

				// memoize costly computation and return
				if (transactionalLayer == null) {
					this.memoizedMergedArray = resultArray;
				}
				return resultArray;
			} else {
				return this.memoizedMergedArray;
			}
		}
	}

	/**
	 * Returns set of inserted record ids on specified position of the array.
	 */
	@Nullable
	T[] getInsertionOnPosition(int position) {
		int index = Arrays.binarySearch(this.insertions, position);
		return index >= 0 ? this.insertedValues[index] : null;
	}

	/**
	 * Returns record removed on certain position in the original array.
	 */
	@Nullable
	T getRemovalOnPosition(int position) {
		final int removalIndex = Arrays.binarySearch(this.removals, position);
		if (removalIndex >= 0) {
			return this.removedValues[removalIndex];
		} else {
			return null;
		}
	}

	/**
	 * Removes recordId from the array (only when present).
	 * This operation also nullifies previous record id insertion (if any).
	 */
	int removeRecordOnPosition(@Nonnull T recordId, int position, boolean exist) {
		// if record exists in the original array
		if (exist) {
			recordRemovalOfTheRecord(recordId, position);
		}

		// look whether the record doesn't also exist in the insertion orders
		final int removedInInsertedValues = removeOrReduceInsertionOrder(recordId, position);

		// nullify memoized array - this operation requires new result
		this.memoizedMergedArray = null;

		return removedInInsertedValues >= 0 ? removedInInsertedValues : computeRemovalIndex(position, 0);
	}

	/**
	 * Returns record removed on certain position in the original array and discards the transactional
	 * state of the object.
	 */
	@Nullable
	T getRemovalOnPosition(@Nullable TransactionalLayerMaintainer transactionalLayer, int position) {
		final int removalIndex = Arrays.binarySearch(this.removals, position);
		if (removalIndex >= 0) {
			return transactionalLayer == null ?
				this.removedValues[removalIndex] :
				getTransactionalCopy(transactionalLayer, (TransactionalLayerProducer<?, ?>) this.removedValues[removalIndex]);
		} else {
			return null;
		}
	}

	/**
	 * Returns object removed on certain position in the original array.
	 * Transactional state of the object is not discarded.
	 */
	@Nullable
	T getRemovalOnPositionWithoutDiscardingState(@Nullable TransactionalLayerMaintainer transactionalLayer, int position) {
		final int removalIndex = Arrays.binarySearch(this.removals, position);
		if (removalIndex >= 0) {
			return transactionalLayer == null ?
				this.removedValues[removalIndex] :
				getTransactionalCopyWithoutDiscardingState(transactionalLayer, (TransactionalLayerProducer<?, ?>) this.removedValues[removalIndex]);
		} else {
			return null;
		}
	}

	/**
	 * This method creates new insertion point at the specified index.
	 * Passed record will becomes the first element at this insertion point.
	 */
	private int addRecordCreatingNewSetAtInsertionPoint(@Nonnull T recordId, int position, int index) {
		// there is no insertion recorded for the requested position
		final int startIndex = -1 * index - 1;
		final int len = this.insertions.length;
		final int targetSize = len + 1;
		final int suffixLength = len - startIndex;

		// add new insertion point
		final int[] newInsertions = new int[targetSize];
		System.arraycopy(this.insertions, 0, newInsertions, 0, startIndex);
		System.arraycopy(this.insertions, startIndex, newInsertions, startIndex + 1, suffixLength);
		newInsertions[startIndex] = position;
		this.insertions = newInsertions;

		// setup new inserted values array for the same insertion point
		final T[][] newInsertedValues = (T[][]) Array.newInstance(this.objectType, targetSize, 0);
		System.arraycopy(this.insertedValues, 0, newInsertedValues, 0, startIndex);
		System.arraycopy(this.insertedValues, startIndex, newInsertedValues, startIndex + 1, suffixLength);
		final T[] newInsertedValuesContent = (T[]) Array.newInstance(recordId.getClass(), 1);
		newInsertedValuesContent[0] = recordId;
		newInsertedValues[startIndex] = newInsertedValuesContent;
		this.insertedValues = newInsertedValues;

		return startIndex;
	}

	/**
	 * This method adds record to the already existing set or records at the specified insertion point.
	 * When there already is a record that is equal to the inserted record the operation is:
	 *
	 * - ignored when there is no {@link #combiner}
	 * - both records are combined using {@link #combiner} if supplied
	 */
	private int addRecordToExistingSetAtInsertionPoint(@Nonnull T recordId, int index) {
		// there is already insertion recorded for requested position
		final T[] insertedValuesBefore = this.insertedValues[index];
		// compute internal place for the newly added record
		final InsertionPosition innerPosition = ArrayUtils.computeInsertPositionOfObjInOrderedArray(recordId, insertedValuesBefore, this.comparator);
		if (innerPosition.alreadyPresent()) {
			// there already is existing record on the place - if we have combined combine the objects, otherwise ignore action
			if (this.combiner != null) {
				T delegateClone = insertedValuesBefore[innerPosition.position()];
				this.combiner.accept(delegateClone, recordId);
				// passed record might have been in transactional memory and by combining it with existing record we effectively discard it
				// so we need to remove its transactional memory so that it doesn't get orphan
				recordId.removeLayer();
			}
		} else {
			// there is no existing record, add it on the target place
			final T[] newInsertedValues = ArrayUtils.insertRecordIntoArrayOnIndex(recordId, insertedValuesBefore, innerPosition.position());
			this.insertedValues[index] = newInsertedValues;
		}
		return innerPosition.position();
	}

	/**
	 * This method find removal order and either discards it completely or reduce its scope according to passed record
	 * that is being added.
	 */
	private void reduceRemovalOrder(@Nonnull T recordId, int position) {
		int removalIndex = Arrays.binarySearch(this.removals, position);
		if (removalIndex >= 0) {
			T removedValue = this.removedValues[removalIndex];
			T reducedValue;
			final boolean recordsAreEqual = this.comparator.compare(removedValue, recordId) == 0;
			if (this.reducer != null) {
				// we have reducer and both values are equal, apply reducer
				if (recordsAreEqual) {
					this.reducer.accept(removedValue, recordId);
				}
				reducedValue = removedValue;
			} else if (recordsAreEqual) {
				// we have no reducer and both values are equal, nullify reduced value so that it is removed from removed values
				reducedValue = null;
			} else {
				// we have no reducer, but values are not equal - removed values must stay intact
				reducedValue = removedValue;
			}
			// if reduced value is null or obsolete checker says that reduced value doesn't make sense anymore
			if (reducedValue == null || (this.obsoleteChecker != null && this.obsoleteChecker.test(reducedValue))) {
				// remove removed value
				ofNullable(reducedValue).ifPresent(TransactionalObject::removeLayer);
				// remove added value (adding negates removal)
				recordId.removeLayer();

				final int[] newRemovals = new int[this.removals.length - 1];
				System.arraycopy(this.removals, 0, newRemovals, 0, removalIndex);
				System.arraycopy(this.removals, removalIndex + 1, newRemovals, removalIndex, newRemovals.length - removalIndex);
				this.removals = newRemovals;

				final T[] newRemovedValues = (T[]) Array.newInstance(this.objectType, this.removedValues.length - 1);
				System.arraycopy(this.removedValues, 0, newRemovedValues, 0, removalIndex);
				System.arraycopy(this.removedValues, removalIndex + 1, newRemovedValues, removalIndex, newRemovedValues.length - removalIndex);
				this.removedValues = newRemovedValues;
			}
		}
	}

	/**
	 * This method will add removal order to the array of removal orders.
	 * If there already exists removal order for this particular record - either ignore the action if no {@link #combiner}
	 * is defined or use the {@link #combiner} to expand the existing removal order.
	 */
	private void recordRemovalOfTheRecord(@Nonnull T recordId, int position) {
		// if the reducer exists, check whether removal does really remove something
		if (this.reducer != null) {
			final T originalClone = this.original[position].makeClone();
			suppressTransactionalMemoryLayerFor(
				originalClone, it -> this.reducer.accept(it, recordId)
			);
			if (this.deepComparator != null && this.deepComparator.test(originalClone, this.original[position])) {
				// nothing has changed - removal removes non-existing part of the original - do not record this removal, it probably targets transactional diff
				return;
			}
		}

		final int index = Arrays.binarySearch(this.removals, position);
		// there is already removal recorded for the position
		if (index >= 0) {
			// if we have combiner - combine removal orders - otherwise ignore action
			if (this.combiner != null) {
				this.combiner.accept(this.removedValues[index], recordId);
			}
		} else {
			// there is no removal recorded for the position yet
			final int startIndex = -1 * (index) - 1;
			final int len = this.removals.length;
			final int targetSize = len + 1;
			final int suffixLength = len - startIndex;

			// add new removal point
			final int[] newRemovals = new int[targetSize];
			System.arraycopy(this.removals, 0, newRemovals, 0, startIndex);
			System.arraycopy(this.removals, startIndex, newRemovals, startIndex + 1, suffixLength);
			newRemovals[startIndex] = position;
			this.removals = newRemovals;

			// add information about removed record at the point
			final T[] newRemovedValues = (T[]) Array.newInstance(this.objectType, targetSize);
			System.arraycopy(this.removedValues, 0, newRemovedValues, 0, startIndex);
			System.arraycopy(this.removedValues, startIndex, newRemovedValues, startIndex + 1, suffixLength);
			newRemovedValues[startIndex] = recordId;
			this.removedValues = newRemovedValues;
		}
	}

	/**
	 * Looks at the insertion order whether there order to insert the record.
	 * If there is any and {@link #reducer} is not defined, removes the insertion order entirely.
	 * If there is any and {@link #reducer} is defined, reduces the insertion order by the scope of the passed record.
	 */
	private int removeOrReduceInsertionOrder(@Nonnull T recordId, int position) {
		int insertionIndex = Arrays.binarySearch(this.insertions, position);
		if (insertionIndex >= 0) {
			// if exists we have to either remove it or reduce its scope
			final T[] insertedValuesBefore = this.insertedValues[insertionIndex];
			// if we have reducer available, iterate over insertions and find matching record
			T reducedValue = null;
			int insertedValuesRemovalIndex = -1;
			int insertedValuesIterationPeek = -1;
			if (this.reducer != null) {
				for (T value : insertedValuesBefore) {
					insertedValuesIterationPeek++;
					if (this.comparator.compare(value, recordId) == 0) {
						// match found - reduce the record scope
						this.reducer.accept(value, recordId);
						reducedValue = value;
						insertedValuesRemovalIndex = insertedValuesIterationPeek;
						break;
					}
				}
			}
			// if there is no reducer or reduced record is obsolete now
			if (reducedValue == null || (this.obsoleteChecker != null && this.obsoleteChecker.test(reducedValue))) {
				ofNullable(reducedValue).ifPresent(TransactionalObject::removeLayer);

				// remove the record from the insertion set
				final T[] insertedValuesAfter = ArrayUtils.removeRecordFromOrderedArray(recordId, insertedValuesBefore, this.comparator);
				this.insertedValues[insertionIndex] = insertedValuesAfter;

				// if there are no records at the insertion set left after record removal
				if (insertedValuesAfter.length == 0) {
					// shrink the insertion orders and remove entire set
					final int length = this.insertions.length - insertionIndex - 1;

					final int[] newInsertions = new int[this.insertions.length - 1];
					System.arraycopy(this.insertions, 0, newInsertions, 0, insertionIndex);
					System.arraycopy(this.insertions, insertionIndex + 1, newInsertions, insertionIndex, length);
					this.insertions = newInsertions;

					final T[][] newInsertedValues = (T[][]) Array.newInstance(this.objectType, this.insertedValues.length - 1, 0);
					System.arraycopy(this.insertedValues, 0, newInsertedValues, 0, insertionIndex);
					System.arraycopy(this.insertedValues, insertionIndex + 1, newInsertedValues, insertionIndex, length);
					this.insertedValues = newInsertedValues;
				}
			}

			return computeRemovalIndex(position, insertedValuesRemovalIndex);
		}

		return -1;
	}

	/**
	 * Compute biggest possible scenario size of the array. That means that all former elements in the array stay and
	 * only recorded insertion applies. Removals are ignored completely as well as the situation when insertion is
	 * combined with the existing element.
	 */
	private int getArrayLengthWithInsertionsOnly() {
		int result = this.original.length;
		for (T[] insertedValue : this.insertedValues) {
			result += insertedValue.length;
		}
		return result;
	}

}
