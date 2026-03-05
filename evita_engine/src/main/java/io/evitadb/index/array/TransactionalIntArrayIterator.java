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

import java.util.NoSuchElementException;
import java.util.PrimitiveIterator.OfInt;

/**
 * This {@link OfInt} iterator iterates over passed integer array with applying the changes on the fly. This iterator
 * is expected to be used only from {@link TransactionalIntArray}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
class TransactionalIntArrayIterator implements OfInt {
	private static final int END_OF_STREAM = -1;
	private final int[] original;
	private final ArrayChangesIteratorSupport changes;
	private int nextRecord;
	private int position;
	private int[] insertion;
	private int insertionPosition = -1;

	/**
	 * Creates an iterator that applies changes on the fly to the given original array.
	 *
	 * @param original the immutable baseline array
	 * @param changes  the change layer to apply during iteration
	 */
	public TransactionalIntArrayIterator(@Nonnull int[] original, @Nonnull ArrayChangesIteratorSupport changes) {
		this.original = original;
		this.changes = changes;
		this.position = -1;
		this.nextRecord = computeNextRecord();
	}

	@Override
	public int nextInt() {
		if (this.nextRecord == END_OF_STREAM) {
			throw new NoSuchElementException("Stream exhausted!");
		}
		final int recordToReturn = this.nextRecord;
		this.nextRecord = computeNextRecord();
		return recordToReturn;
	}

	@Override
	public boolean hasNext() {
		return this.nextRecord != END_OF_STREAM;
	}

	/**
	 * Advances through the current insertion array or, once exhausted, returns the original
	 * element at the current position (if not removed).
	 *
	 * @return next value from the insertion or original array, or {@link #END_OF_STREAM} when
	 * neither applies
	 */
	private int exhaustInsertionOrReturnOriginal() {
		if (this.insertion.length > this.insertionPosition + 1) {
			return this.insertion[++this.insertionPosition];
		}
		this.insertion = null;
		if (this.original.length > this.position && !this.changes.isRemovalOnPosition(this.position)) {
			return this.original[this.position];
		}
		return END_OF_STREAM;
	}

	/**
	 * Computes next record to be returned from the iterator.
	 *
	 * @return next record id, or {@link #END_OF_STREAM} when exhausted
	 */
	private int computeNextRecord() {
		// if insertion happens on this spot - first exhaust the insertion
		if (this.insertion != null) {
			final int result = exhaustInsertionOrReturnOriginal();
			if (result != END_OF_STREAM) {
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
				return END_OF_STREAM;
			}
		} while (true);
	}

}
