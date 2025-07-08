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

package io.evitadb.dataType.iterator;

import javax.annotation.Nonnull;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * This implementation of {@link Iterator} represents a composite iterator that iterates over multiple
 * sub-iterators sequentially. It starts with the first iterator and when it's depleted, continues
 * with the next until reaching the end of the last iterator.
 *
 * @param <T> the type of elements returned by this iterator
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ContinuingIterator<T> implements Iterator<T> {
	private final Iterator<T>[] iterators;
	private int currentIteratorIndex;

	/**
	 * Creates a new ContinuingIterator with the given sub-iterators.
	 *
	 * @param iterators the sub-iterators to iterate over, must not be null or empty,
	 *                  and none of the sub-iterators must be null
	 * @throws IllegalArgumentException if iterators array is null, empty, or contains null elements
	 */
	@SafeVarargs
	public ContinuingIterator(@Nonnull Iterator<T>... iterators) {
		if (iterators == null) {
			throw new IllegalArgumentException("Iterators array must not be null!");
		}
		if (iterators.length == 0) {
			throw new IllegalArgumentException("Iterators array must not be empty!");
		}

		// Validate that no sub-iterator is null
		for (int i = 0; i < iterators.length; i++) {
			if (iterators[i] == null) {
				throw new IllegalArgumentException("Sub-iterator at index " + i + " must not be null!");
			}
		}

		this.iterators = iterators;
		this.currentIteratorIndex = 0;
	}

	@Override
	public boolean hasNext() {
		// Find the next iterator that has elements
		while (this.currentIteratorIndex < this.iterators.length) {
			if (this.iterators[this.currentIteratorIndex].hasNext()) {
				return true;
			}
			this.currentIteratorIndex++;
		}
		return false;
	}

	@Override
	public T next() {
		if (!hasNext()) {
			throw new NoSuchElementException("No more elements available!");
		}
		return this.iterators[this.currentIteratorIndex].next();
	}
}