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

package io.evitadb.utils;

import java.util.NoSuchElementException;
import java.util.PrimitiveIterator.OfInt;

/**
 * String utils contains base method for working with Iterators.
 * We know these functions are available in Apache Commons or Guava, but we try to keep our transitive dependencies
 * as low as possible, so we rather went through duplication of the code.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class Iterators {

	private Iterators() {
	}

	/**
	 * Returns iterator that goes through internal iterators one after another until all are exhausted.
	 */
	public static OfInt concat(OfInt... iterators) {
		if (iterators.length == 0) {
			throw new IllegalArgumentException("At least one iterator is expected!");
		}
		return iterators.length == 1 ? iterators[0] : new ConcatenatedOfInt(iterators);
	}

	/**
	 * This iterator goes through all inner iterators until all are depleted.
	 */
	private static class ConcatenatedOfInt implements OfInt {
		private final OfInt[] iterators;
		private int index = 0;

		public ConcatenatedOfInt(OfInt[] iterators) {
			Assert.isTrue(iterators.length > 1, "At least two iterators are expected here!");
			this.iterators = iterators;
		}

		@Override
		public int nextInt() {
			do {
				if (this.iterators[this.index].hasNext()) {
					return this.iterators[this.index].nextInt();
				}
			} while (this.index++ < this.iterators.length - 1);

			throw new NoSuchElementException("All iterators are exhausted!");
		}

		@Override
		public boolean hasNext() {
			int i = this.index;
			do {
				if (this.iterators[i].hasNext()) {
					return true;
				}
			} while (i++ < this.iterators.length - 1);

			return false;
		}
	}
}
