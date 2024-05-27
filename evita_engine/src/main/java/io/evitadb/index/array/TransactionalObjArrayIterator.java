/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * This {@link java.util.Iterator} iterator iterates over passed integer array with applying the changes on the flight. This iterator
 * is expected only to be used only from {@link TransactionalIntArray}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
class TransactionalObjArrayIterator<T extends Comparable<T>> implements Iterator<T> {
	private final T[] original;
	private final ObjArrayChanges<T> changes;
	private T nextRecord;
	private int position;
	private T[] insertion;
	private int insertionPosition = -1;

	public TransactionalObjArrayIterator(T[] original, ObjArrayChanges<T> changes) {
		this.original = original;
		this.changes = changes;
		this.position = -1;
		this.nextRecord = computeNextRecord();
	}

	@Override
	public T next() {
		if (nextRecord == null) {
			throw new NoSuchElementException("Stream exhausted!");
		}
		T recordToReturn = this.nextRecord;
		this.nextRecord = computeNextRecord();
		return recordToReturn;
	}

	@Override
	public boolean hasNext() {
		return nextRecord != null;
	}

	/**
	 * Computes next record to be returned from the iterator.
	 * @return
	 */
	private T computeNextRecord() {
		// if insertion happens on this spot - first exhaust the insertion
		if (this.insertion != null) {
			if (this.insertion.length > insertionPosition + 1) {
				return this.insertion[++insertionPosition];
			} else {
				// if the original record should not be removed, return it
				this.insertion = null;
				boolean originalRemoved = changes.isRemovalOnPosition(this.position);
				if (!originalRemoved && this.original.length > this.position) {
					return this.original[this.position];
				}
			}
		}

		do {
			this.position++;

			// if insertion should happen on this place, init insertion set and return first inserted element
			this.insertion = changes.getInsertionOnPosition(this.position);
			if (this.insertion != null) {
				this.insertionPosition = -1;
				return this.insertion[++insertionPosition];
			}

			// if original record should be removed skip it - otherwise return
			if (this.position < this.original.length) {
				boolean removalPosition = changes.isRemovalOnPosition(this.position);
				if (!removalPosition) {
					return this.original[this.position];
				}
			} else {
				return null;
			}
		} while (true);
	}

}
