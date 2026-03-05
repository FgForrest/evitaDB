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

/**
 * This {@link java.util.Iterator} iterates over a passed object array
 * applying the changes on the fly. This iterator is expected to be used
 * only from {@link TransactionalObjArray}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
class TransactionalObjArrayIterator<T> implements Iterator<T> {
	private final T[] original;
	private final ObjArrayChanges<T> changes;
	@Nullable private T nextRecord;
	private int position;
	@Nullable private T[] insertion;
	private int insertionPosition = -1;

	/**
	 * Creates a new iterator over the given original array with the
	 * specified transactional changes applied on the fly.
	 *
	 * @param original the immutable baseline array
	 * @param changes  the transactional diff layer to apply
	 */
	public TransactionalObjArrayIterator(
		@Nonnull T[] original,
		@Nonnull ObjArrayChanges<T> changes
	) {
		this.original = original;
		this.changes = changes;
		this.position = -1;
		this.nextRecord = computeNextRecord();
	}

	@Override
	public T next() {
		if (this.nextRecord == null) {
			throw new NoSuchElementException("Stream exhausted!");
		}
		final T recordToReturn = this.nextRecord;
		this.nextRecord = computeNextRecord();
		return recordToReturn;
	}

	@Override
	public boolean hasNext() {
		return this.nextRecord != null;
	}

	/**
	 * Advances through the current insertion array or, once exhausted, returns the original
	 * element at the current position (if not removed).
	 *
	 * @param insertion the non-null insertion array currently being consumed
	 * @return next value from the insertion or original array, or `null` when neither applies
	 */
	@Nullable
	private T exhaustInsertionOrReturnOriginal(@Nonnull T[] insertion) {
		if (insertion.length > this.insertionPosition + 1) {
			return insertion[++this.insertionPosition];
		}
		this.insertion = null;
		if (this.original.length > this.position && !this.changes.isRemovalOnPosition(this.position)) {
			return this.original[this.position];
		}
		return null;
	}

	/**
	 * Computes next record to be returned from the iterator.
	 * @return next record or null if there are no more records
	 */
	@Nullable
	private T computeNextRecord() {
		// if insertion happens on this spot - first exhaust the insertion
		if (this.insertion != null) {
			final T result = exhaustInsertionOrReturnOriginal(this.insertion);
			if (result != null) {
				return result;
			}
		}

		do {
			this.position++;

			// if insertion should happen on this place, init insertion set and return first inserted element
			this.insertion = this.changes.getInsertionOnPosition(this.position);
			if (this.insertion != null) {
				this.insertionPosition = -1;
				return this.insertion[++this.insertionPosition];
			}

			// if original record should be removed skip it - otherwise return
			if (this.position < this.original.length) {
				final boolean removed = this.changes.isRemovalOnPosition(this.position);
				if (!removed) {
					return this.original[this.position];
				}
			} else {
				return null;
			}
		} while (true);
	}

}
