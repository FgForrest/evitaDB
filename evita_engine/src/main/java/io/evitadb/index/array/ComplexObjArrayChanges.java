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

import io.evitadb.core.transaction.memory.Snapshotable;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalStateProducer;
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

import static io.evitadb.core.transaction.Transaction.getTransactionalLayerMaintainer;
import static io.evitadb.core.transaction.Transaction.suppressTransactionalMemoryLayerFor;
import static io.evitadb.core.transaction.Transaction.suppressTransactionalMemoryLayerForWithResult;

/**
 * Collects insertions, removals, and combine/reduce operations recorded against
 * a {@link TransactionalComplexObjArray} during a transaction. On commit the diff is merged with the immutable
 * baseline to produce a new snapshot.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@NotThreadSafe
@SuppressWarnings("unchecked")
class ComplexObjArrayChanges<T extends TransactionalObject<T> & Comparable<T>>
	implements Snapshotable<ComplexObjArrayChanges.ComplexObjArrayChangesMemento<T>> {
	/**
	 * Keeps type of the object that is monitored in changes.
	 */
	@Nonnull private final Class<T> objectType;
	/**
	 * Contains reference to original immutable array of objects.
	 */
	@Nonnull private final T[] original;
	/**
	 * Consumer function that adds second argument to the first argument. Since first argument is transactional
	 * object this addition doesn't affect the original "wrapped" object but only its transactional layer.
	 *
	 * If this effect would not be in place, `BiFunction<T,T,T>` would be more appropriate here.
	 */
	@Nullable private final BiConsumer<T, T> combiner;
	/**
	 * Consumer function that subtracts second argument from the first argument. Since first argument is
	 * transactional object this reduction doesn't affect the original "wrapped" object but only its transactional
	 * layer.
	 *
	 * If this effect would not be in place, `BiFunction<T,T,T>` would be more appropriate here.
	 */
	@Nullable private final BiConsumer<T, T> reducer;
	/**
	 * Predicate determining whether object T is effectively empty (unnecessary) and might be removed entirely.
	 * Used after reducer to check whether the reduction fully emptied the container and it can be removed.
	 */
	@Nullable private final Predicate<T> obsoleteChecker;
	/**
	 * Comparator used to sort values in a map.
	 */
	@Nonnull private final Comparator<T> comparator;
	/**
	 * Deeply compares contents of the object T with another object T and returns true if the objects have same
	 * deep contents.
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
	 * Array of entirely removed records. Their order conforms to the order of the {@link #removals} indexes.
	 */
	private T[] removedValues;
	/**
	 * Temporary intermediate result of the last {@link #getMergedArray()} operation. Nullified immediately with
	 * next change.
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
		final T[] delegateArray;
		if (transactionalLayer != null) {
			final T[] delegateCopy = (T[]) Array.newInstance(objectType, values.length);
			for (int i = 0; i < values.length; i++) {
				final T item = values[i];
				if (item instanceof TransactionalStateProducer<?> producer) {
					delegateCopy[i] = getTransactionalCopy(transactionalLayer, producer);
				} else {
					delegateCopy[i] = item;
				}
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
		@Nonnull TransactionalStateProducer<?> value
	) {
		return transactionalLayer == null
			? (T) value
			: (T) transactionalLayer.getStateCopyWithCommittedChanges(value);
	}

	/**
	 * Creates new instance of the record with applied transactional changes if transactional layer is available.
	 * Transactional state of the original object is not discarded by this operation.
	 */
	@Nonnull
	private static <T> T getTransactionalCopyWithoutDiscardingState(
		@Nullable TransactionalLayerMaintainer transactionalLayer,
		@Nonnull TransactionalStateProducer<?> value
	) {
		return transactionalLayer == null
			? (T) value
			: (T) transactionalLayer.getStateCopyWithCommittedChangesWithoutDiscardingState(value);
	}

	/**
	 * Creates a simple diff layer without combine/reduce callbacks.
	 *
	 * @param objectType element runtime type
	 * @param comparator comparator defining sort order
	 * @param original   the immutable baseline array
	 */
	ComplexObjArrayChanges(
		@Nonnull Class<T> objectType,
		@Nonnull Comparator<T> comparator,
		@Nonnull T[] original
	) {
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

	/**
	 * Creates a diff layer with combine/reduce callbacks for merging and reducing records during transactions.
	 *
	 * @param objectType      element runtime type
	 * @param comparator      comparator defining sort order
	 * @param original        the immutable baseline array
	 * @param combiner        merges two equal records
	 * @param reducer         subtracts record data on removal
	 * @param obsoleteChecker tests if a record is empty
	 * @param deepComparator  deep content equality test
	 */
	ComplexObjArrayChanges(
		@Nonnull Class<T> objectType,
		@Nonnull Comparator<T> comparator,
		@Nonnull T[] original,
		@Nonnull BiConsumer<T, T> combiner,
		@Nonnull BiConsumer<T, T> reducer,
		@Nonnull Predicate<T> obsoleteChecker,
		@Nonnull BiPredicate<T, T> deepComparator
	) {
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
	 * @return the index where removal would occur should all transactional changes have been applied
	 */
	public int computeRemovalIndex(int position, int insertedValuesRemovalIndex) {
		int removalIndex = 0;
		int insertionsPeek = 0;
		int removalsPeek = 0;
		for (int i = 0; i <= position; i++) {
			boolean reduced = false;
			if (this.removals.length > removalsPeek && this.removals[removalsPeek] == i) {
				// get record that is removed on that position
				final T removedValue = getRemovalOnPositionWithoutDiscardingState(
					getTransactionalLayerMaintainer(), i
				);
				// if reducer is present
				if (this.reducer != null) {
					// and the removed value doesn't happen on position of item in original array
					final boolean sameAsOriginal =
						this.removals[removalsPeek] == position
							&& this.comparator.compare(
							this.removedValues[removalsPeek], this.original[i]
						) == 0;
					if (!sameAsOriginal) {
						// clone the original item
						final T clonedOriginal = this.original[i].makeClone();
						// apply reducer
						suppressTransactionalMemoryLayerFor(
							clonedOriginal, it -> this.reducer.accept(it, removedValue)
						);
						// if something left increase removal index
						if (this.obsoleteChecker == null || !this.obsoleteChecker.test(clonedOriginal)) {
							removalIndex++;
						} else {
							// value was entirely removed
							reduced = true;
						}
					}
				}
				removalsPeek++;
			} else if (this.original.length > i && position > i) {
				// original item is still present
				removalIndex++;
			}
			if (this.insertions.length > insertionsPeek && this.insertions[insertionsPeek] == i) {
				// there are insertions at this index
				if (this.insertions[insertionsPeek] == position && insertedValuesRemovalIndex >= 0) {
					// we are on place where removal occurred
					removalIndex += insertedValuesRemovalIndex;
				} else {
					// add count of newly added values
					final T lastInsertedRecord =
						this.insertedValues[insertionsPeek][this.insertedValues[insertionsPeek].length - 1];
					final boolean sameAsOriginal =
						this.original.length > i
							&& this.comparator.compare(lastInsertedRecord, this.original[i]) == 0;
					// lower by one if one of the modified values equals the original (combinator)
					removalIndex +=
						this.insertedValues[insertionsPeek].length - (sameAsOriginal && !reduced ? 1 : 0);
				}
				insertionsPeek++;
			}
		}
		return removalIndex;
	}

	/**
	 * Propagates diff-layer removal to the passed element, if the element participates in the transactional memory.
	 *
	 * Elements of this array are only required to be {@link TransactionalObject}s - producing transactional state is
	 * **not** part of that contract, so the capability has to be tested rather than assumed. Assuming it would break
	 * arrays of non-producing elements, which are a supported case.
	 *
	 * @param value              element to propagate the removal to; may be NULL
	 * @param transactionalLayer object that provides access to entire transactional memory
	 */
	private static void removeLayerIfProducer(
		@Nullable Object value,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		if (value instanceof TransactionalStateProducer<?> transactionalStateProducer) {
			transactionalStateProducer.removeLayer(transactionalLayer);
		}
	}

	/**
	 * Propagates diff-layer removal to the passed element within the current transaction, if the element participates
	 * in the transactional memory. See {@link #removeLayerIfProducer(Object, TransactionalLayerMaintainer)}.
	 *
	 * @param value element to propagate the removal to; may be NULL
	 */
	private static void removeLayerIfProducer(@Nullable Object value) {
		if (value instanceof TransactionalStateProducer<?> transactionalStateProducer) {
			transactionalStateProducer.removeLayer();
		}
	}

	/**
	 * Cleans information of all internal original and inserted data.
	 */
	public void cleanAll(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		for (T originalValue : this.original) {
			removeLayerIfProducer(originalValue, transactionalLayer);
		}
		for (T removedValue : this.removedValues) {
			removeLayerIfProducer(removedValue, transactionalLayer);
		}
		for (T[] insertedValues : this.insertedValues) {
			for (T insertedValue : insertedValues) {
				removeLayerIfProducer(insertedValue, transactionalLayer);
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
	 * Adds new recordId to the array (only when not already present). This operation also nullifies previous record
	 * id removal (if any). When {@link #combiner} is specified existing records might be enriched by additional
	 * internal data of added record. When {@link #combiner} is not defined and record is already present, insertion
	 * is ignored.
	 *
	 * @return two element array with indexes: 0 = index of the bucket, 1 = index of the record in bucket
	 */
	@Nonnull
	int[] addRecordOnPosition(@Nonnull T recordId, int position) {
		// first check for existing removal request
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
	 * Adds new recordId to the array (only when not already present). This operation also nullifies previous record
	 * id removal (if any). When {@link #combiner} is specified existing records might be enriched by additional
	 * internal data of added record. When {@link #combiner} is not defined and record is already present, insertion
	 * is ignored.
	 */
	int addRecordOnPositionComputingIndex(@Nonnull T recordId, int position) {
		final int[] positions = addRecordOnPosition(recordId, position);
		final int pointPosition = positions[0];
		final int relativePosition = positions[1];

		int accumulatedPosition = this.insertions[pointPosition];
		for (int i = 0; i < pointPosition; i++) {
			final T[] insertedValue = this.insertedValues[i];
			accumulatedPosition += insertedValue.length;
			if (this.comparator.compare(
				insertedValue[insertedValue.length - 1], this.original[this.insertions[i]]
			) == 0) {
				accumulatedPosition--;
			}
		}
		final BiPredicate<T, Integer> couldBeRemoved = this.obsoleteChecker == null
			? (originalValue, examinedPosition) -> true
			: (originalValue, examinedPosition) -> {
				final T clonedValue = originalValue.makeClone();
				return Objects.requireNonNull(
					suppressTransactionalMemoryLayerForWithResult(
						clonedValue, it -> {
							final int insertPoint = Arrays.binarySearch(
								this.insertions, this.removals[examinedPosition]
							);
							if (insertPoint >= 0) {
								final T[] insertedValuesRef = this.insertedValues[insertPoint];
								final T insertedValue =
									insertedValuesRef[insertedValuesRef.length - 1];
								if (this.comparator.compare(insertedValue, it) == 0) {
									Assert.isPremiseValid(
										this.combiner != null,
										"Combiner must be defined when checking for obsolete records!"
									);
									// avoid creating additional transactional states here
									suppressTransactionalMemoryLayerFor(
										it, theIt -> this.combiner.accept(it, insertedValue)
									);
								}
							}
							// avoid creating additional transactional states here
							Assert.isPremiseValid(
								this.reducer != null,
								"Reducer must be defined when checking for obsolete records!"
							);
							suppressTransactionalMemoryLayerFor(
								it,
								theIt -> this.reducer.accept(theIt, this.removedValues[examinedPosition])
							);
							return this.obsoleteChecker.test(it);
						}
					)
				);
			};
		for (int i = 0; i < this.removals.length; i++) {
			final int removal = this.removals[i];
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
	 * This method computes new array from the immutable original array and the set of insertions/removals made upon
	 * it. This method may be called repeatedly upon the transactional object and doesn't wipe the objects from the
	 * transactional memory.
	 */
	@Nonnull
	T[] getMergedArray() {
		return getMergedArray(null);
	}

	/**
	 * This method computes new array from the immutable original array and the set of insertions/removals made upon
	 * it. This method may be called only ONCE when `transactionalLayer` is passed because it removes internal objects
	 * from the transactional memory and this makes the operation non-repeatable!
	 */
	@Nonnull
	T[] getMergedArray(@Nullable TransactionalLayerMaintainer transactionalLayer) {
		if (this.insertions.length == 0 && this.removals.length == 0) {
			// no insertions/removals - return the original
			return getTransactionalCopy(transactionalLayer, this.objectType, this.original);
		} else {
			// compute results only when we can't reuse previous
			if (this.memoizedMergedArray == null || transactionalLayer != null) {
				// allocate array for biggest possible scenario (we can't precisely compute its size now)
				final T[] computedArray = (T[]) Array.newInstance(
					this.objectType, getArrayLengthWithInsertionsOnly()
				);
				int lastPosition = 0;
				int lastComputedPosition = 0;

				int insPositionIndex = -1;
				int nextInsertionPosition =
					this.insertions.length > 0 ? this.insertions[insPositionIndex + 1] : -1;

				int remPositionIndex = -1;
				int nextRemovalPosition =
					this.removals.length > 0 ? this.removals[remPositionIndex + 1] : -1;

				// get first position with change operations
				final ChangePlan plan = new ChangePlan();
				plan.planNextOperation(nextInsertionPosition, nextRemovalPosition);

				// create new array instance
				T[] delegateArray = getTransactionalCopy(
					transactionalLayer, this.objectType, this.original
				);

				// execute modification orders
				while (plan.hasAnythingToDo()) {
					// insertion is always processed first
					if (plan.bothOperationsRequested() || plan.isInsertion()) {
						// move index in insertion array
						insPositionIndex++;

						// insert records into the target array
						final T[] insertedRecords = this.insertedValues[insPositionIndex];
						final int delegateCopyLength = plan.getPosition() - lastPosition;
						System.arraycopy(
							delegateArray, lastPosition,
							computedArray, lastComputedPosition,
							delegateCopyLength
						);

						// create copy from inserted records
						final T[] insertedRecordValues = getTransactionalCopy(
							transactionalLayer, this.objectType, insertedRecords
						);

						// compare last processed record in original array and first to be inserted
						final T lastDelegateRecord =
							lastPosition + delegateCopyLength < delegateArray.length
								? delegateArray[lastPosition + delegateCopyLength]
								: null;
						final T lastInsertedRecord =
							insertedRecordValues[insertedRecordValues.length - 1];
						if (lastDelegateRecord != null
							&& this.comparator.compare(lastDelegateRecord, lastInsertedRecord) == 0) {
							// they match - clone original
							final T lastDelegateRecordClone = lastDelegateRecord.makeClone();
							// merge if combiner is available
							if (this.combiner != null) {
								// avoid recording deltas
								suppressTransactionalMemoryLayerFor(
									lastDelegateRecordClone,
									it -> this.combiner.accept(it, lastInsertedRecord)
								);
							}

							// copy all except the one equal to delegate clone
							final int insertedLength = insertedRecordValues.length - 1;
							System.arraycopy(
								insertedRecordValues, 0,
								computedArray, lastComputedPosition + delegateCopyLength,
								insertedLength
							);

							// advance peek position
							lastComputedPosition =
								lastComputedPosition + delegateCopyLength + insertedLength;

							// append the cloned record
							computedArray[lastComputedPosition++] = lastDelegateRecordClone;

							// advance original array position
							lastPosition = plan.getPosition() + 1;
						} else {
							// insert all new values
							final int insertedLength = insertedRecordValues.length;
							System.arraycopy(
								insertedRecordValues, 0,
								computedArray, lastComputedPosition + delegateCopyLength,
								insertedLength
							);

							// advance peek position
							lastComputedPosition =
								lastComputedPosition + delegateCopyLength + insertedLength;

							// advance original array position
							lastPosition = plan.getPosition();
						}
					} else {
						// removal - move index in removal array
						remPositionIndex++;

						// get record removed at position
						final T removedValue = getRemovalOnPosition(transactionalLayer, plan.getPosition());

						// if last result record matches removed
						if (lastComputedPosition > 0
							&& this.comparator.compare(
							computedArray[lastComputedPosition - 1], removedValue
						) == 0) {
							// addition and removal on same spot
							final T lastDelegateRecordClone = computedArray[lastComputedPosition - 1];

							// reduce values on position
							if (this.reducer != null) {
								// avoid recording deltas
								suppressTransactionalMemoryLayerFor(
									lastDelegateRecordClone, it -> this.reducer.accept(it, removedValue)
								);
							}

						} else {
							// just removal at position
							final T lastDelegateRecordClone =
								delegateArray[plan.getPosition()].makeClone();

							// reduce the cloned record
							if (this.reducer != null) {
								// avoid recording deltas
								suppressTransactionalMemoryLayerFor(
									lastDelegateRecordClone, it -> this.reducer.accept(it, removedValue)
								);
							}

							final int delegateCopyLength;
							// check if record is obsolete
							if (this.obsoleteChecker == null
								|| this.obsoleteChecker.test(lastDelegateRecordClone)) {
								// remove it entirely
								delegateCopyLength = plan.getPosition() - lastPosition;
								System.arraycopy(
									delegateArray, lastPosition,
									computedArray, lastComputedPosition,
									delegateCopyLength
								);
							} else {
								// add the reduced clone
								delegateCopyLength = plan.getPosition() - lastPosition + 1;
								System.arraycopy(
									delegateArray, lastPosition,
									computedArray, lastComputedPosition,
									delegateCopyLength
								);
								computedArray[lastComputedPosition + delegateCopyLength - 1] =
									lastDelegateRecordClone;
							}

							// advance original array position
							lastPosition = plan.getPosition() + 1;

							// advance peek position
							lastComputedPosition = lastComputedPosition + delegateCopyLength;
						}
					}

					// move insertion/removal cursors
					nextInsertionPosition = this.insertions.length > insPositionIndex + 1
						? this.insertions[insPositionIndex + 1]
						: -1;
					nextRemovalPosition = this.removals.length > remPositionIndex + 1
						? this.removals[remPositionIndex + 1]
						: -1;

					// plan next operations
					plan.planNextOperation(nextInsertionPosition, nextRemovalPosition);
				}

				// copy rest of the original array
				if (lastPosition < delegateArray.length) {
					final int length = delegateArray.length - lastPosition;
					System.arraycopy(
						delegateArray, lastPosition,
						computedArray, lastComputedPosition,
						length
					);
					lastComputedPosition += length;
				}

				// shrink array to real size
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
		final int index = Arrays.binarySearch(this.insertions, position);
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

		// nullify memoized array
		this.memoizedMergedArray = null;

		return removedInInsertedValues >= 0
			? removedInInsertedValues
			: computeRemovalIndex(position, 0);
	}

	/**
	 * Returns record removed on certain position in the original array and discards the transactional state of
	 * the object.
	 */
	@Nullable
	T getRemovalOnPosition(@Nullable TransactionalLayerMaintainer transactionalLayer, int position) {
		final int removalIndex = Arrays.binarySearch(this.removals, position);
		if (removalIndex >= 0) {
			final T removedValue = this.removedValues[removalIndex];
			if (transactionalLayer == null || !(removedValue instanceof TransactionalStateProducer<?>)) {
				return removedValue;
			}
			return getTransactionalCopy(transactionalLayer, (TransactionalStateProducer<?>) removedValue);
		} else {
			return null;
		}
	}

	/**
	 * Returns object removed on certain position in the original array.
	 * Transactional state of the object is not discarded.
	 */
	@Nullable
	T getRemovalOnPositionWithoutDiscardingState(
		@Nullable TransactionalLayerMaintainer transactionalLayer,
		int position
	) {
		final int removalIndex = Arrays.binarySearch(this.removals, position);
		if (removalIndex >= 0) {
			final T removedValue = this.removedValues[removalIndex];
			if (transactionalLayer == null || !(removedValue instanceof TransactionalStateProducer<?>)) {
				return removedValue;
			}
			return getTransactionalCopyWithoutDiscardingState(
				transactionalLayer, (TransactionalStateProducer<?>) removedValue
			);
		} else {
			return null;
		}
	}

	/**
	 * This method creates new insertion point at the specified index.
	 * Passed record will becomes the first element at this insertion point.
	 */
	private int addRecordCreatingNewSetAtInsertionPoint(@Nonnull T recordId, int position, int index) {
		// no insertion recorded for the requested position
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

		// setup new inserted values array
		final T[][] newInsertedValues = (T[][]) Array.newInstance(this.objectType, targetSize, 0);
		System.arraycopy(this.insertedValues, 0, newInsertedValues, 0, startIndex);
		System.arraycopy(this.insertedValues, startIndex, newInsertedValues, startIndex + 1, suffixLength);
		final T[] newInsertedValuesContent = (T[]) Array.newInstance(this.objectType, 1);
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
		// already insertion recorded for this position
		final T[] insertedValuesBefore = this.insertedValues[index];
		// compute internal place for new record
		final InsertionPosition innerPosition = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
			recordId, insertedValuesBefore, this.comparator
		);
		if (innerPosition.alreadyPresent()) {
			// existing record on place - combine or ignore
			if (this.combiner != null) {
				final T delegateClone = insertedValuesBefore[innerPosition.position()];
				this.combiner.accept(delegateClone, recordId);
				// remove orphan transactional memory
				removeLayerIfProducer(recordId);
			}
		} else {
			// no existing record, add at target place
			final T[] newInsertedValues = ArrayUtils.insertRecordIntoArrayOnIndex(
				recordId, insertedValuesBefore, innerPosition.position()
			);
			this.insertedValues[index] = newInsertedValues;
		}
		return innerPosition.position();
	}

	/**
	 * This method finds a removal order and either discards it completely or reduces its scope according to the
	 * passed record that is being added.
	 */
	private void reduceRemovalOrder(@Nonnull T recordId, int position) {
		final int removalIndex = Arrays.binarySearch(this.removals, position);
		if (removalIndex >= 0) {
			final T removedValue = this.removedValues[removalIndex];
			T reducedValue;
			final boolean recordsAreEqual = this.comparator.compare(removedValue, recordId) == 0;
			if (this.reducer != null) {
				// reducer present and values equal
				if (recordsAreEqual) {
					this.reducer.accept(removedValue, recordId);
				}
				reducedValue = removedValue;
			} else if (recordsAreEqual) {
				// no reducer, values equal - nullify
				reducedValue = null;
			} else {
				// no reducer, values differ - keep intact
				reducedValue = removedValue;
			}
			// if reduced value is null or obsolete
			if (reducedValue == null
				|| (this.obsoleteChecker != null && this.obsoleteChecker.test(reducedValue))) {
				// remove removed value
				removeLayerIfProducer(reducedValue);
				// remove added value (negates removal)
				removeLayerIfProducer(recordId);

				final int[] newRemovals = new int[this.removals.length - 1];
				System.arraycopy(this.removals, 0, newRemovals, 0, removalIndex);
				System.arraycopy(
					this.removals, removalIndex + 1, newRemovals, removalIndex,
					newRemovals.length - removalIndex
				);
				this.removals = newRemovals;

				final T[] newRemovedValues =
					(T[]) Array.newInstance(this.objectType, this.removedValues.length - 1);
				System.arraycopy(this.removedValues, 0, newRemovedValues, 0, removalIndex);
				System.arraycopy(
					this.removedValues, removalIndex + 1, newRemovedValues, removalIndex,
					newRemovedValues.length - removalIndex
				);
				this.removedValues = newRemovedValues;
			}
		}
	}

	/**
	 * This method will add a removal order to the array of removal orders. If there already exists a removal order
	 * for this record - either ignore the action if no {@link #combiner} is defined or use the {@link #combiner}
	 * to expand the existing removal order.
	 */
	private void recordRemovalOfTheRecord(@Nonnull T recordId, int position) {
		// check whether removal really removes something
		if (this.reducer != null) {
			final T originalClone = this.original[position].makeClone();
			suppressTransactionalMemoryLayerFor(originalClone, it -> this.reducer.accept(it, recordId));
			if (this.deepComparator != null
				&& this.deepComparator.test(originalClone, this.original[position])) {
				// nothing has changed - do not record
				return;
			}
		}

		final int index = Arrays.binarySearch(this.removals, position);
		// already removal recorded for the position
		if (index >= 0) {
			// combine removal orders or ignore
			if (this.combiner != null) {
				this.combiner.accept(this.removedValues[index], recordId);
			}
		} else {
			// no removal recorded for position yet
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

			// add information about removed record
			final T[] newRemovedValues = (T[]) Array.newInstance(this.objectType, targetSize);
			System.arraycopy(this.removedValues, 0, newRemovedValues, 0, startIndex);
			System.arraycopy(this.removedValues, startIndex, newRemovedValues, startIndex + 1, suffixLength);
			newRemovedValues[startIndex] = recordId;
			this.removedValues = newRemovedValues;
		}
	}

	/**
	 * Looks at the insertion order whether there is an order to insert the record. If there is and {@link #reducer}
	 * is not defined, removes the insertion order entirely. If there is and {@link #reducer} is defined, reduces
	 * the insertion order by the scope of the passed record.
	 */
	private int removeOrReduceInsertionOrder(@Nonnull T recordId, int position) {
		final int insertionIndex = Arrays.binarySearch(this.insertions, position);
		if (insertionIndex >= 0) {
			// either remove it or reduce its scope
			final T[] insertedValuesBefore = this.insertedValues[insertionIndex];
			// iterate over insertions and find match
			T reducedValue = null;
			int insertedValuesRemovalIndex = -1;
			int insertedValuesIterationPeek = -1;
			if (this.reducer != null) {
				for (T value : insertedValuesBefore) {
					insertedValuesIterationPeek++;
					if (this.comparator.compare(value, recordId) == 0) {
						// match - reduce scope
						this.reducer.accept(value, recordId);
						reducedValue = value;
						insertedValuesRemovalIndex = insertedValuesIterationPeek;
						break;
					}
				}
			}
			// no reducer or reduced record is obsolete
			if (reducedValue == null
				|| (this.obsoleteChecker != null && this.obsoleteChecker.test(reducedValue))) {
				removeLayerIfProducer(reducedValue);

				// remove from the insertion set
				final T[] insertedValuesAfter = ArrayUtils.removeRecordFromOrderedArray(
					recordId, insertedValuesBefore, this.comparator
				);
				this.insertedValues[insertionIndex] = insertedValuesAfter;

				// if no records left at insertion set
				if (insertedValuesAfter.length == 0) {
					// shrink and remove entire set
					final int length = this.insertions.length - insertionIndex - 1;

					final int[] newInsertions = new int[this.insertions.length - 1];
					System.arraycopy(this.insertions, 0, newInsertions, 0, insertionIndex);
					System.arraycopy(
						this.insertions, insertionIndex + 1, newInsertions, insertionIndex, length
					);
					this.insertions = newInsertions;

					final T[][] newInsertedValues = (T[][]) Array.newInstance(
						this.objectType, this.insertedValues.length - 1, 0
					);
					System.arraycopy(this.insertedValues, 0, newInsertedValues, 0, insertionIndex);
					System.arraycopy(
						this.insertedValues, insertionIndex + 1,
						newInsertedValues, insertionIndex,
						length
					);
					this.insertedValues = newInsertedValues;
				}
			}

			return computeRemovalIndex(position, insertedValuesRemovalIndex);
		}

		return -1;
	}

	/**
	 * Compute biggest possible scenario size of the array. All former elements stay and only recorded insertions
	 * apply. Removals are ignored completely as well as the situation when insertion is combined with the existing
	 * element.
	 */
	private int getArrayLengthWithInsertionsOnly() {
		int result = this.original.length;
		for (T[] insertedValue : this.insertedValues) {
			result += insertedValue.length;
		}
		return result;
	}

	/**
	 * Captures the current positional diff (the two co-indexed pairs) into an immutable
	 * {@link ComplexObjArrayChangesMemento}.
	 *
	 * Independence is achieved cheaply because the layer mutates its arrays with strict copy-on-write
	 * discipline:
	 *
	 * - {@link #insertions}, {@link #removals} and {@link #removedValues} are reference-captured as-is — every
	 *   mutation path replaces the whole field with a freshly allocated array (fresh allocation +
	 *   `System.arraycopy`), never writing an existing element, so the captured reference can never be observed
	 *   mutating. This is the {@link IntArrayChanges#snapshot()} precedent applied here.
	 * - {@link #insertedValues} requires a ONE-LEVEL clone of the outer array: its slots are reassigned in place
	 *   by {@link #addRecordToExistingSetAtInsertionPoint} and {@link #removeOrReduceInsertionOrder}
	 *   (`this.insertedValues[index] = ...`), which would otherwise corrupt the memento. The inner bucket arrays
	 *   are shared by reference because they too are only ever replaced wholesale, never element-mutated.
	 * - {@link #memoizedMergedArray} is a DERIVED, rebuildable cache and is deliberately NOT captured. Cloning it
	 *   here was the source of the O(index-size) per-entity savepoint cliff (a single-element change to a large
	 *   index still copied the whole merged array). Because every mutator nullifies it, dropping it from the
	 *   memento is exact: {@link #restore(ComplexObjArrayChangesMemento)} resets it to `null` and the next
	 *   {@link #getMergedArray()} lazily recomputes it — the rare rollback path pays one recompute instead of
	 *   every savepoint paying a full clone. Dropping (rather than reference-capturing) is additionally immune to
	 *   any caller mutating the array returned by {@link #getMergedArray()}.
	 *
	 * The element payloads (`T`) held in the buckets and in {@link #removedValues} are nested
	 * {@link TransactionalLayerProducer} instances and are captured strictly by reference (nested-layer boundary).
	 * Their internal mutable state is rewound by their own {@link Snapshotable}, coordinated by the
	 * maintainer-level savepoint. The final config / identity fields ({@link #objectType}, {@link #original},
	 * {@link #combiner}, {@link #reducer}, {@link #obsoleteChecker}, {@link #comparator}, {@link #deepComparator})
	 * are immutable / stateless and are never captured.
	 *
	 * @return a memento that {@link #restore(ComplexObjArrayChangesMemento)} can use to rewind this layer
	 */
	@Nonnull
	@Override
	public ComplexObjArrayChangesMemento<T> snapshot() {
		return new ComplexObjArrayChangesMemento<>(
			// reference-captured: replaced wholesale on each mutation, never element-mutated
			this.insertions,
			// one-level clone: outer slots are reassigned in place (add / removeOrReduce insertion order)
			cloneInsertedValues(this.insertedValues),
			// reference-captured: replaced wholesale on each mutation, never element-mutated
			this.removals,
			// reference-captured: replaced wholesale on each mutation, never element-mutated
			this.removedValues
		);
	}

	/**
	 * Rewinds this layer's positional diff to exactly the state captured by the given memento, undoing every
	 * insertion / removal recorded since the snapshot. All four diff arrays (both co-indexed pairs) are reset
	 * together so the load-bearing pairing invariant (`insertions` ↔ `insertedValues`, `removals` ↔
	 * `removedValues`) is restored atomically; the derived {@link #memoizedMergedArray} is reset to `null`
	 * alongside them so {@link #getMergedArray()} cannot return a memo inconsistent with the restored diff (it is
	 * lazily recomputed on the next call).
	 *
	 * The memento may be restored repeatedly. {@link #insertions}, {@link #removals} and {@link #removedValues}
	 * are only ever reassigned wholesale by subsequent mutations (never element-mutated), so aliasing the
	 * memento's references straight into the live fields is safe. The outer {@link #insertedValues} array,
	 * however, has its slots reassigned in place, so it MUST be re-cloned here (mirroring the snapshot clone);
	 * otherwise a mutation after the first restore would write through into the memento and corrupt a subsequent
	 * restore. The element payloads are reattached by reference only; their internal state is the responsibility
	 * of their own {@link Snapshotable} (nested-layer boundary).
	 *
	 * @param memento a memento previously produced by {@link #snapshot()} on this same layer
	 */
	@Override
	public void restore(@Nonnull ComplexObjArrayChangesMemento<T> memento) {
		this.insertions = memento.insertions();
		this.insertedValues = cloneInsertedValues(memento.insertedValues());
		this.removals = memento.removals();
		this.removedValues = memento.removedValues();
		this.memoizedMergedArray = null;
	}

	/**
	 * Creates a one-level (outer dimension) copy of the two-dimensional inserted-values array. The inner bucket
	 * arrays are intentionally shared by reference because they are only ever replaced wholesale, never mutated in
	 * place; cloning only the outer dimension prevents later per-slot reassignment from aliasing the snapshot.
	 *
	 * @param insertedValues the two-dimensional inserted-values array to copy
	 * @return a fresh outer array whose slots reference the same inner bucket arrays
	 */
	@Nonnull
	private T[][] cloneInsertedValues(@Nonnull T[][] insertedValues) {
		return Arrays.copyOf(insertedValues, insertedValues.length);
	}

	/**
	 * Immutable carrier of the mutable positional diff of a {@link ComplexObjArrayChanges} layer captured at the
	 * moment of {@link ComplexObjArrayChanges#snapshot()}. Holds the two co-indexed pairs that describe the diff
	 * against the immutable baseline:
	 *
	 * - `insertions` ↔ `insertedValues`: bucket of element refs to splice in at each baseline position
	 * - `removals` ↔ `removedValues`: element ref recording the removal / reduction at each baseline position
	 *
	 * The derived `memoizedMergedArray` merge-result cache is intentionally NOT carried here — it is rebuildable
	 * from the four diff arrays, so {@link ComplexObjArrayChanges#restore} simply nulls it and lets the next
	 * {@link ComplexObjArrayChanges#getMergedArray()} recompute it lazily. Carrying (and cloning) it was the
	 * source of the O(index-size) per-entity savepoint cliff this optimization removes.
	 *
	 * The element payloads (`T`) are nested {@link TransactionalLayerProducer} instances held strictly by
	 * reference; this memento never captures their internal state (nested-layer boundary). The immutable / stateless
	 * config and identity fields of the layer are not part of the diff and are therefore not carried here.
	 *
	 * @param insertions         sorted baseline positions where insertion buckets occur (co-indexed with
	 *                           `insertedValues`)
	 * @param insertedValues     one-level copy of the two-dimensional inserted-value buckets (inner buckets shared
	 *                           by reference)
	 * @param removals           sorted baseline positions that are removed / reduced (co-indexed with
	 *                           `removedValues`)
	 * @param removedValues      element refs recorded per removal position (carry the subtracted delta for the
	 *                           reduce family)
	 * @param <T>                element runtime type, a transactional, comparable object
	 */
	public record ComplexObjArrayChangesMemento<T extends TransactionalObject<T> & Comparable<T>>(
		@Nonnull int[] insertions,
		@Nonnull T[][] insertedValues,
		@Nonnull int[] removals,
		@Nonnull T[] removedValues
	) {
	}

}
