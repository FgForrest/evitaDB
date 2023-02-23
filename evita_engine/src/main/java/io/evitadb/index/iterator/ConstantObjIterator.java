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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.index.iterator;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * This {@link Iterator} implementation iterates over constant array of objects passed in constructor.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
public class ConstantObjIterator<T extends Comparable<T>> implements Iterator<T> {
	private final T[] constant;
	private int index = -1;
	private T nextNumberToReturn = null;

	public ConstantObjIterator(T[] constant) {
		this.constant = constant;
		if (this.constant.length > 0) {
			this.nextNumberToReturn = this.constant[++index];
		}
	}

	@Override
	public T next() {
		if (nextNumberToReturn == null) {
			throw new NoSuchElementException("Stream exhausted!");
		}
		final T numberToReturn = this.nextNumberToReturn;
		final int nextIndex = index + 1;
		if (nextIndex < constant.length) {
			this.nextNumberToReturn = this.constant[++index];
		} else {
			index++;
			this.nextNumberToReturn = null;
		}
		return numberToReturn;
	}

	@Override
	public boolean hasNext() {
		return nextNumberToReturn != null;
	}

}
