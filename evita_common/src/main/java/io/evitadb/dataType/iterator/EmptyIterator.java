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
 * This implementation of {@link Iterator} represents empty iterator.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
public class EmptyIterator<T> implements Iterator<T> {
	private static final EmptyIterator<?> INSTANCE = new EmptyIterator<>();
	private static final Iterable<?> ITERABLE_INSTANCE = new Iterable<>() {
		@SuppressWarnings("rawtypes")
		@Nonnull
		@Override
		public Iterator iterator() {
			return INSTANCE;
		}
	};

	/**
	 * Returns a shared instance of {@code Iterable}, a singleton implementation of an empty iterable.
	 *
	 * @param <T> the type of elements in the iterable
	 * @return a shared instance of empty iterable
	 */
	@Nonnull
	public static <T> Iterable<T> iterableInstance(@Nonnull Class<T> type) {
		//noinspection unchecked
		return (Iterable<T>) ITERABLE_INSTANCE;
	}

	/**
	 * Returns a shared instance of {@code EmptyIterator}, a singleton implementation of an empty iterator.
	 *
	 * @param <T> the type of elements in the iterator
	 * @return a shared instance of {@code EmptyIterator}
	 */
	@Nonnull
	public static <T> Iterator<T> iteratorInstance(@Nonnull Class<T> type) {
		//noinspection unchecked
		return (Iterator<T>) INSTANCE;
	}

	@Override
	public T next() {
		throw new NoSuchElementException("No data in stream!");
	}

	@Override
	public boolean hasNext() {
		return false;
	}
}
