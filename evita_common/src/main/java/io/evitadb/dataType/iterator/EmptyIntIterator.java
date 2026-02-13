/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import java.util.NoSuchElementException;
import java.util.PrimitiveIterator.OfInt;

/**
 * This implementation of {@link OfInt} represents an empty iterator.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
public class EmptyIntIterator implements OfInt {
	public static final EmptyIntIterator INSTANCE = new EmptyIntIterator();

	private EmptyIntIterator() {}

	/**
	 * Always throws {@link NoSuchElementException} since this iterator is empty.
	 */
	@Override
	public int nextInt() {
		throw new NoSuchElementException("No data in stream!");
	}

	/**
	 * Always returns false since this iterator is empty.
	 */
	@Override
	public boolean hasNext() {
		return false;
	}
}
