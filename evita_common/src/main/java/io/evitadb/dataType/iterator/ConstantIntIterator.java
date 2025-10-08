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

import java.util.NoSuchElementException;
import java.util.PrimitiveIterator.OfInt;

/**
 * This {@link OfInt} implementation iterates over constant array of ints passed in constructor.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
public class ConstantIntIterator implements OfInt {
	private static final int END_OF_STREAM = -1;
	private final int[] constant;
	private int index = -1;
	private int nextNumberToReturn = END_OF_STREAM;

	public ConstantIntIterator(int[] constant) {
		this.constant = constant;
		if (this.constant.length > 0) {
			this.nextNumberToReturn = this.constant[++this.index];
		}
	}

	@Override
	public int nextInt() {
		if (this.nextNumberToReturn == END_OF_STREAM) {
			throw new NoSuchElementException("Stream exhausted!");
		}
		final int numberToReturn = this.nextNumberToReturn;
		final int nextIndex = this.index + 1;
		if (nextIndex < this.constant.length) {
			this.nextNumberToReturn = this.constant[++this.index];
		} else {
			this.index++;
			this.nextNumberToReturn = END_OF_STREAM;
		}
		return numberToReturn;
	}

	@Override
	public boolean hasNext() {
		return this.nextNumberToReturn != END_OF_STREAM;
	}

}
