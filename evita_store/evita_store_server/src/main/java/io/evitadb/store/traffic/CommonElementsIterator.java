/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.store.traffic;


import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * This class implements an iterator that returns common elements from multiple sorted iterators.
 * Each iterator must be sorted in ascending order and contain unique elements.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class CommonElementsIterator implements Iterator<Long> {
	@Nonnull private final List<Iterator<Long>> iterators;
	@Nonnull private final long[] currentValues;
	@Nullable private Long nextElement;

	public CommonElementsIterator(@Nonnull List<Iterator<Long>> iterators) {
		this.iterators = iterators;
		this.currentValues = new long[this.iterators.size()];
		if (iterators.isEmpty()) {
			this.nextElement = null;
		} else {
			long max = Long.MIN_VALUE;
			for (int i = 0; i < iterators.size(); i++) {
				final Iterator<Long> iterator = iterators.get(i);
				if (iterator == null || !iterator.hasNext()) {
					this.nextElement = null;
					return;
				} else {
					final Long next = iterator.next();
					this.currentValues[i] = next;
					if (this.currentValues[i] > max) {
						max = this.currentValues[i];
					}
				}
			}
			this.nextElement = findNextCommonElement(max);
		}
	}

	@Override
	public boolean hasNext() {
		return this.nextElement != null;
	}

	@Override
	public Long next() {
		if (this.nextElement == null) {
			throw new NoSuchElementException();
		}
		final Long result = this.nextElement;
		this.nextElement = findNextCommonElement(result + 1);
		return result;
	}

	/**
	 * Finds the next common element across all iterators starting from the specified maximum value.
	 * Each iterator is expected to be sorted in ascending order and contain unique elements.
	 * The method ensures the validity of these assumptions during execution.
	 *
	 * @param max the minimum value that the next common element should be greater than or equal to
	 * @return the next common element if found, or null if no such element exists
	 */
	@Nullable
	private Long findNextCommonElement(long max) {
		for (int i = 0; i < this.iterators.size(); i++) {
			if (this.currentValues[i] < max) {
				final Iterator<Long> iterator = this.iterators.get(i);
				Long itNext = null;
				do {
					if (!iterator.hasNext()) {
						return null;
					}
					final Long theNextValue = iterator.next();
					Assert.isPremiseValid(
						theNextValue > (itNext == null ? this.currentValues[i] : itNext),
						"Iterator is not sorted or contains duplicates"
					);
					itNext = theNextValue;
				} while (itNext < max);

				Assert.isPremiseValid(this.currentValues[i] < itNext, "Iterator is not sorted or contains duplicates");
				this.currentValues[i] = itNext;
				if (this.currentValues[i] >= max) {
					max = this.currentValues[i];
				}
			}
		}
		return max;
	}

}
