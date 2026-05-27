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
 * Transactional change layer for a sorted object array. Accumulates insertions and removals made within a
 * single transaction and produces the merged result on demand via {@link #getMergedArray()}, without modifying
 * the immutable delegate array seen by concurrent readers.
 *
 * The change layer is keyed by the element's *comparator identity* (the same comparator used to maintain
 * sort order in the delegate). When an element's identity matches an existing entry but its *content* has
 * changed (detected via {@link io.evitadb.api.requestResponse.data.ContentComparator#differsFrom} when
 * the type implements it, otherwise via `Object.equals`), the layer records a paired remove + insert that
 * atomically substitutes the old instance with the new one during the merge step. This prevents a price-index
 * staleness class of bug where a record removed and re-added in the same transaction with the same
 * `internalPriceId` but a different `priceWithTax` was silently dropped.
 *
 * Typical flow inside a transaction:
 * 1. {@link TransactionalObjArray#add}/{@link TransactionalObjArray#remove} delegate to
 *    {@link #addRecordId}/{@link #removeRecordId}.
 * 2. On commit, {@link #getMergedArray()} is called once; the result is memoized so repeated calls within
 *    the same transaction are cheap.
 * 3. The memoized result is invalidated by every subsequent mutation.
 *
 * Not thread-safe — one instance is owned by exactly one transaction thread.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@NotThreadSafe
public class ObjArrayChanges<T> {
	/**
	 * Immutable snapshot of the array as it existed when the transaction opened. Never modified.
	 */
	private final T[] delegate;
	/**
	 * Sorted array of delegate positions (zero-based indexes) at which pending insertions should be spliced in.
	 * Parallel to {@link #insertedValues}: `insertions[i]` is the delegate position for the bucket
	 * `insertedValues[i]`.
	 */
	private int[] insertions = ArrayUtils.EMPTY_INT_ARRAY;
	/**
	 * Parallel to {@link #insertions}: for each pending insertion position, the sorted bucket of records to
	 * splice in at that position. A bucket may contain more than one record when multiple distinct elements sort
	 * into the same slot between two delegate entries.
	 */
	@SuppressWarnings("unchecked")
	private InsertionBucket<T>[] insertedValues = new InsertionBucket[0];
	/**
	 * Sorted array of delegate positions whose original elements have been removed (or replaced) in this
	 * transaction. When a position appears in both {@link #insertions} and this array, the merge step
	 * replaces the original element with the new one (content-substitution).
	 */
	private int[] removals = ArrayUtils.EMPTY_INT_ARRAY;
	/**
	 * Cached result of the most recent {@link #getMergedArray()} call. Nullified on every mutation so
	 * repeated reads within the same quiescent period are allocation-free.
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
	 * Records an insertion of `recordId` into the change layer, with three distinct cases based on the
	 * relationship between the new record and the base delegate:
	 *
	 * **Content substitution** — when the delegate already holds a record with the same comparator key
	 * but different content (detected via {@link io.evitadb.api.requestResponse.data.ContentComparator#differsFrom}
	 * when the type implements it, otherwise via `Object.equals`): the original delegate position is marked
	 * for removal AND the new instance is queued for insertion at the same position. The merge step
	 * therefore atomically replaces the stale record. This is the fix for the price-index staleness bug
	 * where a price with an unchanged `internalPriceId` but a modified `priceWithTax` was silently dropped.
	 *
	 * **Idempotent re-add** — when a matching record exists in the delegate, was previously marked for
	 * removal in this transaction, and the new instance carries equal content: the pending removal is
	 * cancelled, restoring the element.
	 *
	 * **Fresh insertion** — when no matching record exists in the delegate: the new instance is appended
	 * to the appropriate {@link InsertionBucket} in the change layer.
	 *
	 * @param recordId   the record to add or substitute; must not be null
	 * @param comparator the same comparator that governs the sort order of the delegate array
	 */
	void addRecordId(@Nonnull T recordId, @Nonnull Comparator<T> comparator) {
		final InsertionPosition position = ArrayUtils.computeInsertPositionOfObjInOrderedArray(recordId, this.delegate, comparator);
		final int positionIndex = position.position();
		if (position.alreadyPresent()) {
			// base delegate already holds a record with the same comparator key
			final T existing = this.delegate[positionIndex];
			final int removalIndex = Arrays.binarySearch(this.removals, positionIndex);
			if (contentDiffers(existing, recordId)) {
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
			}
			// else: identical record already present and not removed -> no-op
		} else {
			insertIntoChangeLayer(recordId, positionIndex, comparator);
		}
		// nullify memoized result that becomes obsolete by this operation
		this.memoizedMergedArray = null;
	}

	/**
	 * Appends `recordId` to the {@link InsertionBucket} at `position` in the change layer, creating
	 * a new bucket if none exists at that position yet.
	 *
	 * If the bucket already contains a record with the same comparator identity, the existing entry is
	 * replaced by the new instance. This is essential for the remove-then-re-add scenario on the change
	 * layer itself: when a record that was freshly inserted (not yet in the delegate) is removed and
	 * re-added with updated content, the bucket must hold the latest instance rather than the stale one.
	 *
	 * @param recordId   the record to insert or replace within the bucket
	 * @param position   the insertion slot index in the delegate array (the index before which the bucket
	 *                   will be placed when building the merged array)
	 * @param comparator the comparator used to locate the record within the bucket
	 */
	private void insertIntoChangeLayer(@Nonnull T recordId, int position, @Nonnull Comparator<T> comparator) {
		final int index = Arrays.binarySearch(this.insertions, position);
		if (index >= 0) {
			this.insertedValues[index].addRecord(recordId, comparator);
		} else {
			final int startIndex = -1 * (index) - 1;
			this.insertions = ArrayUtils.insertIntIntoArrayOnIndex(position, this.insertions, startIndex);
			final Class<?> componentType = this.delegate.getClass().getComponentType();
			this.insertedValues = ArrayUtils.insertRecordIntoArrayOnIndex(
				new InsertionBucket<>(recordId, componentType), this.insertedValues, startIndex
			);
		}
	}

	/**
	 * Returns `true` when `existing` and `candidate` represent the same logical slot (same comparator
	 * identity) but their *content* has diverged.
	 *
	 * Decision order:
	 * 1. If both references point to the same object instance — no difference (`false`).
	 * 2. If `candidate` implements {@link io.evitadb.api.requestResponse.data.ContentComparator}, its
	 *    {@code differsFrom} method is authoritative (handles the price-record case where `equals` keys
	 *    only on `internalPriceId` while `differsFrom` also compares amount fields).
	 * 3. Otherwise falls back to `!existing.equals(candidate)` so plain-`equals` types work unchanged.
	 *
	 * @param existing  the record currently in the base delegate
	 * @param candidate the record being added in the current transaction
	 * @return `true` if the two records are identity-equal but content-different
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static <T> boolean contentDiffers(@Nonnull T existing, @Nonnull T candidate) {
		if (existing == candidate) {
			return false;
		}
		if (candidate instanceof ContentComparator) {
			return ((ContentComparator) candidate).differsFrom(existing);
		}
		return !existing.equals(candidate);
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
	 * Sorted mini-array of records pending insertion at a single logical slot in the delegate.
	 *
	 * A bucket is created for each distinct insertion position tracked in {@link #insertions}. Most
	 * buckets hold exactly one record, but two distinct elements that sort between the same pair of
	 * consecutive delegate entries share one bucket and are kept in comparator order within it.
	 */
	private static class InsertionBucket<T> {
		/** Sorted array of records waiting to be spliced into the merged output at this bucket's position. */
		@Getter private T[] insertedValues;

		@SuppressWarnings("unchecked")
		public InsertionBucket(@Nonnull T insertedValue, @Nonnull Class<?> componentType) {
			this.insertedValues = (T[]) Array.newInstance(componentType, 1);
			this.insertedValues[0] = insertedValue;
		}

		/**
		 * Adds `recordId` to the bucket in sorted order.
		 *
		 * If a record with the same comparator identity is already present, the existing entry is
		 * *replaced* by the new instance rather than dropped. This is critical for the case where a
		 * fresh insertion is removed and re-added within the same transaction with updated content: without
		 * the replacement the bucket would retain the stale first instance.
		 *
		 * @param recordId   the record to insert or replace
		 * @param comparator the comparator governing sort order within the bucket
		 */
		public void addRecord(@Nonnull T recordId, @Nonnull Comparator<T> comparator) {
			final int idx = Arrays.binarySearch(this.insertedValues, recordId, comparator);
			if (idx >= 0) {
				// record with the same comparator key is already in the bucket - substitute it,
				// otherwise the new instance (potentially carrying updated content) would be dropped
				this.insertedValues[idx] = recordId;
			} else {
				this.insertedValues = ArrayUtils.insertRecordIntoArrayOnIndex(recordId, this.insertedValues, -idx - 1);
			}
		}

		/**
		 * Removes `recordId` from the bucket. No-op if the record is not present.
		 */
		public void removeRecord(@Nonnull T recordId, @Nonnull Comparator<T> comparator) {
			this.insertedValues = ArrayUtils.removeRecordFromOrderedArray(recordId, this.insertedValues, comparator);
		}

		/** Returns `true` when there are no pending insertions remaining in this bucket. */
		public boolean isEmpty() {
			return this.insertedValues.length == 0;
		}

		/** Returns the number of records pending insertion in this bucket. */
		public int size() {
			return this.insertedValues.length;
		}
	}

}
