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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import static io.evitadb.core.Transaction.suppressTransactionalMemoryLayerFor;

/**
 * This iterator is used only from {@link TransactionalComplexObjArray} to iterate over dynamic content of the array.
 * Data in the iterator are computed lazily as the iterator advances through array.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
class TransactionalComplexObjArrayIterator<T extends TransactionalObject<T, ?> & Comparable<T>> implements Iterator<T> {
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
	 * Contains changed in transactional memory recorded for the array.
	 */
	@Nonnull private final ComplexObjArrayChanges<T> changes;
	/**
	 * Contains next computed record.
	 */
	private T nextRecord;
	/**
	 * Contains current position in the record.
	 */
	private int position;
	/**
	 * Contains current insertion set (i.e. set processed for current position in orignal array).
	 */
	private T[] insertion;
	/**
	 * Contains index in the currently processed insertion set.
	 */
	private int insertionPosition = -1;
	private boolean skipInsertion;

	TransactionalComplexObjArrayIterator(@Nonnull T[] original, @Nonnull ComplexObjArrayChanges<T> changes, @Nullable BiConsumer<T, T> combiner, @Nullable BiConsumer<T, T> reducer, @Nullable Predicate<T> obsoleteChecker) {
		this.original = original;
		this.changes = changes;
		this.combiner = combiner;
		this.reducer = reducer;
		this.obsoleteChecker = obsoleteChecker;
		this.position = -1;
		this.nextRecord = computeNextRecord();
	}

	@Override
	public boolean hasNext() {
		return this.nextRecord != null;
	}

	@Override
	public T next() {
		if (this.nextRecord == null) {
			throw new NoSuchElementException("Stream exhausted!");
		}
		T recordToReturn = this.nextRecord;
		this.nextRecord = computeNextRecord();
		return recordToReturn;
	}

	/**
	 * Computes next dynamic record to returning.
	 */
	private T computeNextRecord() {
		// if we have insertion set already resolved, advance through it
		if (this.insertion != null) {
			this.insertionPosition++;
			// return all inserted records except last
			if (this.insertion.length > this.insertionPosition + 1) {
				return this.insertion[this.insertionPosition];
			} else {
				final T lastInsertedRecord = this.insertion[this.insertionPosition];
				// if inserted set was exhausted - reset current insertion set to leave this section on next computation
				this.insertion = null;
				// get original record at current position
				final T originalRecord = this.original.length > this.position ? this.original[this.position] : null;
				// if inserted record matches the original
				final boolean insertedRecordMatchesOriginal = originalRecord != null && originalRecord.compareTo(lastInsertedRecord) == 0;

				final T recordToReturn;
				if (insertedRecordMatchesOriginal) {
					if (this.combiner != null) {
						// combine both records
						recordToReturn = originalRecord.makeClone();
						suppressTransactionalMemoryLayerFor(recordToReturn, it -> this.combiner.accept(it, lastInsertedRecord));
					} else {
						// return original record - skipping added one
						recordToReturn = lastInsertedRecord.makeClone();
					}
					// look at the removal positions
					final T removedValue = this.changes.getRemovalOnPosition(this.position);
					// if there is none
					if (removedValue == null) {
						// return record on specified position in the original array
						return recordToReturn;
					} else if (this.reducer != null && this.obsoleteChecker != null) {
						// if there are reducers - just reduce scope of the record
						suppressTransactionalMemoryLayerFor(recordToReturn, it -> this.reducer.accept(it, removedValue));
						// and if still not obsolete return it
						if (!this.obsoleteChecker.test(recordToReturn)) {
							return recordToReturn;
						}
					} else {
						// compute additional record
						return computeNextRecord();
					}
				} else {
					// go back with position to reduce original value at the position - but skip redoing insertion (this would make infinite loop)
					this.position--;
					this.skipInsertion = true;
					// return inserted record
					return lastInsertedRecord;
				}
			}
		}

		// advance position of the iterator
		this.position++;

		if (this.skipInsertion) {
			// reset the one-time flag
			this.skipInsertion = false;
		} else {
			// check if there is insertion at this position
			this.insertion = this.changes.getInsertionOnPosition(this.position);
			if (this.insertion != null) {
				// if so - reset index for iteration through inserts and retry
				this.insertionPosition = -1;
				return computeNextRecord();
			}
		}

		/* if original array was not completely exhausted */
		if (this.position < this.original.length) {
			// check whether there is some removal recorded for this position
			final T removedValue = this.changes.getRemovalOnPosition(this.position);

			if (removedValue == null) {
				// if not return original record at this position
				return this.original[this.position];
			} else if (this.reducer != null) {
				// if there is and reducer is present - clone original record at this position
				final T originalRecordClone = this.original[this.position].makeClone();
				// reduce the scope
				suppressTransactionalMemoryLayerFor(originalRecordClone, it -> this.reducer.accept(it, removedValue));
				// and if the record is not yet obsolete - return it
				if (this.obsoleteChecker != null && !this.obsoleteChecker.test(originalRecordClone)) {
					return originalRecordClone;
				} else {
					// compute additional record
					return computeNextRecord();
				}
			} else {
				// compute additional record
				return computeNextRecord();
			}
		} else {
			// return null - iterator is exhausted
			return null;
		}
	}

}
