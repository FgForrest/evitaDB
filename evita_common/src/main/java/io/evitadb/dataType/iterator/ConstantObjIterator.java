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
 * This {@link Iterator} implementation iterates over constant array of objects passed in constructor.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
public class ConstantObjIterator<T> implements Iterator<T> {
	private final T[] constant;
	private int index = 0;

	/**
	 * Creates a new iterator over the given array of objects. The array is
	 * stored by reference and must not be modified during iteration.
	 *
	 * @param constant the array of objects to iterate over
	 */
	public ConstantObjIterator(@Nonnull T[] constant) {
		this.constant = constant;
	}

	/**
	 * Returns the next element in the iteration.
	 */
	@Override
	public T next() {
		if (this.index >= this.constant.length) {
			throw new NoSuchElementException("Stream exhausted!");
		}
		return this.constant[this.index++];
	}

	/**
	 * Returns true if there are more elements to iterate over.
	 */
	@Override
	public boolean hasNext() {
		return this.index < this.constant.length;
	}

}
