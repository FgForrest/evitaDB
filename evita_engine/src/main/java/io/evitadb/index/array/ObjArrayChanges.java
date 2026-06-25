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

import io.evitadb.api.requestResponse.data.ContentComparator;
import io.evitadb.core.transaction.memory.Snapshotable;
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
 * Transactional diff layer for {@link TransactionalObjArray}. This class is
 * part of evitaDB's Software Transactional Memory (STM) framework and holds
 * insertion and removal commands recorded during a transaction. Insertions are
 * tracked via {@link InsertionBucket} instances keyed by position in the
 * delegate array. When the transaction commits, these commands are merged with
 * the immutable delegate array to produce an updated snapshot; on rollback,
 * they are simply discarded.
 *
 * This class is not thread-safe because each diff layer is bound to a single
 * transaction thread via a ThreadLocal-bound Transaction object.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@NotThreadSafe
public class ObjArrayChanges<T> implements Snapshotable<ObjArrayChanges.ObjArrayChangesMemento<T>> {
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
	private static void getNextOperations(
		int nextInsertionPosition,
		int nextRemovalPosition,
		@Nonnull ChangePlan plan
	) {
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

	/**
	 * Creates a new change layer over the given delegate array.
	 *
	 * @param delegate the immutable baseline array
	 */
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
				final int insertedIndex = Arrays.binarySearch(
					insertedRecordIds.getInsertedValues(),
					recordId, comparator
				);
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
			final boolean replaceOriginal =
				this.removals.length > 0 && this.removals[removalIndex] == i;
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
		final int index = Arrays.binarySearch(this.insertions, position);
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
			if (Arrays.binarySearch(this.removals, delegateIndex) < 0) {
				return true;
			}
			// delegate slot is removed - a content substitution may have queued a replacement with
			// the same comparator key in the insertion bucket at this position
			return insertionBucketContains(delegateIndex, recordId, comparator);
		} else {
			for (InsertionBucket<T> insertedValue : this.insertedValues) {
				final int pos = Arrays.binarySearch(
					insertedValue.getInsertedValues(), recordId, comparator
				);
				if (pos >= 0) {
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * Returns true if the insertion bucket queued at the given delegate position contains a record
	 * with the same comparator key as `recordId`. Used to recognize a content-substitution replacement
	 * that sits at a delegate position that is simultaneously marked for removal.
	 */
	private boolean insertionBucketContains(int position, @Nonnull T recordId, @Nonnull Comparator<T> comparator) {
		final int insIdx = Arrays.binarySearch(this.insertions, position);
		return insIdx >= 0
			&& Arrays.binarySearch(this.insertedValues[insIdx].getInsertedValues(), recordId, comparator) >= 0;
	}

	/**
	 * Adds new recordId to the array. Behaviour depends on the relationship to the original array:
	 *
	 *  - if a record with the same comparator key is already in the base delegate and the new
	 *    record carries different content (detected via {@link ContentComparator#differsFrom} when
	 *    the type implements it, otherwise via {@link Object#equals}), the existing record is
	 *    substituted — a removal is recorded for its position AND an insertion of the new instance
	 *    is queued at the same logical position. The merge step combines both operations atomically.
	 *  - if the record was previously marked for removal and the contents are equal, the removal
	 *    is cancelled (idempotent re-add).
	 *  - if the record is not in the base delegate, it is appended to the change layer.
	 */
	void addRecordId(@Nonnull T recordId, @Nonnull Comparator<T> comparator) {
		final InsertionPosition position =
			ArrayUtils.computeInsertPositionOfObjInOrderedArray(
				recordId, this.delegate, comparator
			);
		final int positionIndex = position.position();
		if (position.alreadyPresent()) {
			// base delegate already holds a record with the same comparator key
			final T existing = this.delegate[positionIndex];
			final int removalIndex = Arrays.binarySearch(this.removals, positionIndex);
			if (ContentComparator.contentDiffers(existing, recordId)) {
				// identity matches but content differs - substitute the record:
				// ensure the original position is marked for removal AND queue an insertion of
				// the new instance at the same logical position
				if (removalIndex < 0) {
					this.removals = ArrayUtils.insertIntIntoOrderedArray(positionIndex, this.removals);
				}
				insertIntoChangeLayer(recordId, positionIndex, comparator);
			} else if (removalIndex >= 0) {
				// contents are equal - cancel any pending removal
				this.removals = ArrayUtils.removeIntFromArrayOnIndex(this.removals, removalIndex);
				// also drop any orphaned substitution bucket that a prior addRecordId may have
				// queued at this position - otherwise the merge would resurrect the stale
				// replacement alongside the (now un-removed) delegate record
				dropInsertionBucketEntry(positionIndex, recordId, comparator);
			}
			// else: identical record already present and not removed -> no-op
		} else {
			insertIntoChangeLayer(recordId, positionIndex, comparator);
		}
		// nullify memoized result that becomes obsolete by this operation
		this.memoizedMergedArray = null;
	}

	/**
	 * Appends a record to the insertion bucket at the given logical position, creating the bucket
	 * when it does not yet exist. If a same-identity record is already present in the bucket, it is
	 * replaced with the new instance (so a remove-then-add of an in-flight insertion correctly
	 * substitutes the content).
	 */
	private void insertIntoChangeLayer(@Nonnull T recordId, int position, @Nonnull Comparator<T> comparator) {
		final int index = Arrays.binarySearch(this.insertions, position);
		if (index >= 0) {
			this.insertedValues[index].addRecord(recordId, comparator);
		} else {
			final int startIndex = -1 * (index) - 1;
			this.insertions = ArrayUtils.insertIntIntoArrayOnIndex(
				position, this.insertions, startIndex
			);
			final Class<?> componentType = this.delegate.getClass().getComponentType();
			this.insertedValues = ArrayUtils.insertRecordIntoArrayOnIndex(
				new InsertionBucket<>(recordId, componentType),
				this.insertedValues, startIndex
			);
		}
	}

	/**
	 * Drops the record from the insertion bucket queued at the given delegate position, when
	 * present. If the bucket becomes empty as a result, the bucket entry is removed from the
	 * parallel insertions/insertedValues arrays as well. Used to clean up substitution buckets
	 * left over by a prior add when the substitution gets reverted or removed.
	 */
	private void dropInsertionBucketEntry(int position, @Nonnull T recordId, @Nonnull Comparator<T> comparator) {
		final int insIdx = Arrays.binarySearch(this.insertions, position);
		if (insIdx < 0) {
			return;
		}
		final InsertionBucket<T> bucket = this.insertedValues[insIdx];
		if (Arrays.binarySearch(bucket.getInsertedValues(), recordId, comparator) < 0) {
			return;
		}
		bucket.removeRecord(recordId, comparator);
		if (bucket.isEmpty()) {
			this.insertions = ArrayUtils.removeIntFromArrayOnIndex(this.insertions, insIdx);
			this.insertedValues = ArrayUtils.removeRecordFromArrayOnIndex(this.insertedValues, insIdx);
		}
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
			// if a content substitution previously queued a replacement at this position, drop it too -
			// otherwise the merge would resurrect the (now removed) record from the insertion bucket
			dropInsertionBucketEntry(position, recordId, comparator);
		} else {
			// record is not part of the original array but might be present on change layer
			final int changePosition =
				ArrayUtils.computeInsertPositionOfObjInOrderedArray(
					recordId, this.delegate, comparator
				).position();
			final int insertionIndex = Arrays.binarySearch(this.insertions, changePosition);
			if (insertionIndex >= 0) {
				// yes the record was added recently and we need to rollback this insertion
				this.insertedValues[insertionIndex].removeRecord(recordId, comparator);
				if (this.insertedValues[insertionIndex].isEmpty()) {
					// inserted values are now empty, we need to shrink insertion arrays
					this.insertions = ArrayUtils.removeIntFromArrayOnIndex(
						this.insertions, insertionIndex
					);
					this.insertedValues = ArrayUtils.removeRecordFromArrayOnIndex(
						this.insertedValues, insertionIndex
					);
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
				@SuppressWarnings("unchecked")
				final T[] computedArray = (T[]) Array.newInstance(
					this.delegate.getClass().getComponentType(),
					getMergedLength()
				);
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
						final InsertionBucket<T> insertedRecords =
							this.insertedValues[insPositionIndex];
						final int originalCopyLength = plan.getPosition() - lastPosition;
						System.arraycopy(
							this.delegate, lastPosition,
							computedArray, lastComputedPosition,
							originalCopyLength
						);
						final int insertedLength = insertedRecords.size();
						System.arraycopy(
							insertedRecords.getInsertedValues(), 0,
							computedArray,
							lastComputedPosition + originalCopyLength,
							insertedLength
						);
						lastPosition = plan.getPosition() + 1;
						lastComputedPosition = lastComputedPosition
							+ originalCopyLength + insertedLength;

						// move insertions / removal cursors - if there are any
						nextInsertionPosition =
							this.insertions.length > insPositionIndex + 1
								? this.insertions[insPositionIndex + 1]
								: -1;
						nextRemovalPosition =
							this.removals.length > remPositionIndex + 1
								? this.removals[remPositionIndex + 1]
								: -1;

					} else {
						if (plan.isInsertion()) {
							// insertion is requested on specified position - move index in insertion array
							insPositionIndex++;

							// insert requested records in to the target array and after the existing record in original array
							final InsertionBucket<T> insertedRecords =
							this.insertedValues[insPositionIndex];
							final int originalCopyLength = plan.getPosition() - lastPosition;
							System.arraycopy(
								this.delegate, lastPosition,
								computedArray, lastComputedPosition,
								originalCopyLength
							);
							final int insertedLength = insertedRecords.size();
							System.arraycopy(
								insertedRecords.getInsertedValues(), 0,
								computedArray,
								lastComputedPosition + originalCopyLength,
								insertedLength
							);
							lastPosition = plan.getPosition();
							lastComputedPosition = lastComputedPosition
								+ originalCopyLength + insertedLength;

							// move insertions / removal cursors - if there are any
							nextInsertionPosition =
								this.insertions.length > insPositionIndex + 1
									? this.insertions[insPositionIndex + 1]
									: -1;

						} else {
							// removal is requested on specified position - move index in removal array
							remPositionIndex++;

							// copy contents of the original array skipping removed record
							final int originalCopyLength = plan.getPosition() - lastPosition;
							System.arraycopy(
								this.delegate, lastPosition,
								computedArray, lastComputedPosition,
								originalCopyLength
							);
							lastPosition = plan.getPosition() + 1;
							lastComputedPosition = lastComputedPosition
								+ originalCopyLength;

							// move insertions / removal cursors - if there are any
							nextRemovalPosition =
								this.removals.length > remPositionIndex + 1
									? this.removals[remPositionIndex + 1]
									: -1;

						}
					}

					// plan next operations
					getNextOperations(nextInsertionPosition, nextRemovalPosition, plan);
				}

				// copy rest of the original array into the result (no operations were planned for this part)
				if (lastPosition < this.delegate.length) {
					System.arraycopy(
						this.delegate, lastPosition,
						computedArray, lastComputedPosition,
						this.delegate.length - lastPosition
					);
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
	 * Captures the current mutable diff state (insertion positions, insertion buckets and removal
	 * positions) into an independent memento. The two co-indexed arrays
	 * {@link #insertions}/{@link #insertedValues} and the {@link #removals} array are deep-copied so a
	 * later mutation of this layer cannot corrupt the memento:
	 *
	 *  - {@link #insertions} and {@link #removals} are cloned (although they are reassigned
	 *    copy-on-write, cloning fully decouples and is cheap).
	 *  - {@link #insertedValues} is deep-copied: a fresh outer array is allocated and each
	 *    {@link InsertionBucket} is copied via {@link InsertionBucket#copy()} (which clones the
	 *    bucket's own backing array), because individual bucket arrays are mutated IN PLACE on a
	 *    same-key substitution.
	 *
	 * The {@link #delegate} baseline is final, immutable and shared by reference - it is intentionally
	 * not captured. The {@link #memoizedMergedArray} cache is derived state and is intentionally not
	 * captured either; it is rebuilt lazily after a restore.
	 *
	 * Element ({@code T}) payloads inside the buckets are captured by reference only - they are never
	 * mutated in place (only replaced wholesale), so deep-copying them is unnecessary and would
	 * violate the nested-layer boundary invariant.
	 *
	 * @return an independent memento of the current diff state
	 */
	@Nonnull
	@Override
	public ObjArrayChangesMemento<T> snapshot() {
		final int[] insertionsCopy = this.insertions.clone();
		final int[] removalsCopy = this.removals.clone();
		@SuppressWarnings("unchecked")
		final InsertionBucket<T>[] bucketsCopy = new InsertionBucket[this.insertedValues.length];
		for (int i = 0; i < bucketsCopy.length; i++) {
			bucketsCopy[i] = this.insertedValues[i].copy();
		}
		return new ObjArrayChangesMemento<>(insertionsCopy, removalsCopy, bucketsCopy);
	}

	/**
	 * Resets this diff layer back to the state captured by the given memento, undoing every insertion
	 * and removal recorded since the snapshot. The arrays are re-cloned (and the buckets re-copied) on
	 * the way out of the memento so the SAME memento can be restored more than once without a later
	 * mutation of this layer corrupting it.
	 *
	 * The {@link #delegate} baseline is final and untouched. The {@link #memoizedMergedArray} cache is
	 * reset to {@code null} so the next {@link #getMergedArray()} recomputes from the restored diff -
	 * a merged array computed after the snapshot must not survive the rollback.
	 *
	 * @param memento a memento previously produced by {@link #snapshot()} on this same layer
	 */
	@Override
	public void restore(@Nonnull ObjArrayChangesMemento<T> memento) {
		this.insertions = memento.insertions().clone();
		this.removals = memento.removals().clone();
		final InsertionBucket<T>[] mementoBuckets = memento.insertedValues();
		@SuppressWarnings("unchecked")
		final InsertionBucket<T>[] buckets = new InsertionBucket[mementoBuckets.length];
		for (int i = 0; i < buckets.length; i++) {
			buckets[i] = mementoBuckets[i].copy();
		}
		this.insertedValues = buckets;
		// derived cache - drop it so the next getMergedArray() recomputes from the restored diff
		this.memoizedMergedArray = null;
	}

	/**
	 * Immutable carrier of the {@link ObjArrayChanges} mutable diff state used by
	 * {@link ObjArrayChanges#snapshot()} / {@link ObjArrayChanges#restore(ObjArrayChangesMemento)}.
	 *
	 * It holds deep copies of the two co-indexed insertion arrays
	 * ({@code insertions}/{@code insertedValues}) and of the {@code removals} array; the immutable
	 * {@code delegate} baseline and the derived {@code memoizedMergedArray} cache are deliberately
	 * excluded. The element ({@code T}) payloads inside the buckets are held by reference (their own
	 * transactional state is handled by their own savepoints).
	 *
	 * @param insertions     deep copy of the sorted delegate positions where insertion buckets apply
	 * @param removals       deep copy of the sorted delegate positions marked for removal
	 * @param insertedValues deep copy of the index-aligned insertion buckets (each array cloned)
	 * @param <T>            the element type held by the originating {@link ObjArrayChanges}
	 */
	public record ObjArrayChangesMemento<T>(
		@Nonnull int[] insertions,
		@Nonnull int[] removals,
		@Nonnull InsertionBucket<T>[] insertedValues
	) {
	}

	/**
	 * Holds the ordered set of records queued for insertion at a single logical position of the
	 * delegate array. The parent {@link ObjArrayChanges} keeps one bucket per insertion position in
	 * its {@link #insertedValues} array, index-aligned with the {@link #insertions} positions; the
	 * merge step ({@link #getMergedArray()}) splices each bucket's records into the result at the
	 * position the bucket is bound to. Records inside a bucket stay sorted by the caller-supplied
	 * comparator so look-ups can use binary search.
	 *
	 * Package-private (not `private`) so it can appear as a component type of the public
	 * {@link ObjArrayChangesMemento} record produced by {@link ObjArrayChanges#snapshot()}.
	 */
	static class InsertionBucket<T> {
		/**
		 * Records queued for insertion at this bucket's position, kept sorted by the comparator used in
		 * {@link #addRecord}. Reassigned copy-on-write on insert/remove, but a same-key substitution
		 * writes a slot IN PLACE (see {@link #addRecord}) - hence {@link #copy()} clones this array.
		 */
		@Getter private T[] insertedValues;

		/**
		 * Creates a bucket seeded with a single record.
		 *
		 * @param insertedValue the first record placed into the bucket
		 * @param componentType the runtime element type, used to allocate the typed backing array
		 */
		@SuppressWarnings("unchecked")
		public InsertionBucket(@Nonnull T insertedValue, @Nonnull Class<?> componentType) {
			this.insertedValues = (T[]) Array.newInstance(componentType, 1);
			this.insertedValues[0] = insertedValue;
		}

		/**
		 * Creates a bucket directly over an already-prepared backing array. Used by {@link #copy()} to
		 * build an independent bucket whose array is a clone of an existing bucket's array.
		 *
		 * @param insertedValues the backing array this bucket takes ownership of
		 */
		private InsertionBucket(@Nonnull T[] insertedValues) {
			this.insertedValues = insertedValues;
		}

		/**
		 * Returns an independent copy of this bucket whose backing array is a clone of this bucket's
		 * array. The element ({@code T}) references are shared - elements are never mutated in place
		 * (only replaced wholesale), so cloning the array alone fully decouples the copy from later
		 * in-place slot writes (see {@link #addRecord}).
		 *
		 * @return a new bucket holding a clone of this bucket's backing array
		 */
		@Nonnull
		InsertionBucket<T> copy() {
			return new InsertionBucket<>(this.insertedValues.clone());
		}

		/**
		 * Inserts a record into the bucket keeping the backing array sorted. When a record sharing the
		 * same comparator key is already present, it is replaced in place rather than added a second
		 * time, so an updated instance (e.g. a content substitution) supersedes the stale one.
		 *
		 * @param recordId   the record to insert or use as the replacement
		 * @param comparator orders the bucket and locates the matching key
		 */
		public void addRecord(@Nonnull T recordId, @Nonnull Comparator<T> comparator) {
			final int idx = Arrays.binarySearch(this.insertedValues, recordId, comparator);
			if (idx >= 0) {
				// record with the same comparator key is already in the bucket - substitute it,
				// otherwise the new instance (potentially carrying updated content) would be dropped
				this.insertedValues[idx] = recordId;
			} else {
				this.insertedValues = ArrayUtils.insertRecordIntoArrayOnIndex(
					recordId, this.insertedValues, -idx - 1
				);
			}
		}

		/**
		 * Drops the record matching the comparator key from the bucket; a no-op when absent.
		 *
		 * @param recordId   the record to remove
		 * @param comparator locates the matching key in the sorted backing array
		 */
		public void removeRecord(@Nonnull T recordId, @Nonnull Comparator<T> comparator) {
			this.insertedValues = ArrayUtils.removeRecordFromOrderedArray(
				recordId, this.insertedValues, comparator
			);
		}

		/**
		 * Returns true when no records are queued, signalling the parent that the whole bucket entry
		 * can be dropped from the index-aligned insertion arrays.
		 */
		public boolean isEmpty() {
			return this.insertedValues.length == 0;
		}

		/**
		 * Number of records queued for insertion, i.e. how many slots this bucket contributes to the
		 * merged array.
		 */
		public int size() {
			return this.insertedValues.length;
		}
	}

}
