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

import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@link CommonElementsIterator#hasNext()} method.
 * This method checks whether a common element exists across all the iterators.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
class CommonElementsIteratorTest {

	@SafeVarargs
	private static <V> void verifyCommonElements(@Nonnull Iterator<V> iterator, V... expectedValues) {
		for (V expectedValue : expectedValues) {
			assertTrue(iterator.hasNext(), "Expected more elements in iterator");
			V actualValue = iterator.next();
			assertEquals(expectedValue, actualValue, "Expected value: " + expectedValue + ", but got: " + actualValue);
		}
		assertFalse(iterator.hasNext(), "Expected no more elements in iterator");

	}

	@Test
	void shouldHasNextWithEmptyIterators() {
		List<Iterator<Long>> iterators = Collections.emptyList();
		CommonElementsIterator commonElementsIterator = new CommonElementsIterator(iterators);

		assertFalse(commonElementsIterator.hasNext(), "Expected hasNext to return false for empty iterator list");
	}

	@Test
	void shouldHasNextWithNoCommonElements() {
		List<Iterator<Long>> iterators = new ArrayList<>();
		iterators.add(List.of(1L, 2L, 3L).iterator());
		iterators.add(List.of(4L, 5L, 6L).iterator());

		CommonElementsIterator commonElementsIterator = new CommonElementsIterator(iterators);

		assertFalse(commonElementsIterator.hasNext(), "Expected hasNext to return false for iterators with no common elements");
	}

	@Test
	void shouldHasNextWithCommonElements() {
		List<Iterator<Long>> iterators = new ArrayList<>();
		iterators.add(List.of(1L, 2L, 3L).iterator());
		iterators.add(List.of(2L, 3L, 4L, 5L).iterator());

		CommonElementsIterator commonElementsIterator = new CommonElementsIterator(iterators);

		assertTrue(commonElementsIterator.hasNext(), "Expected hasNext to return true for iterators with common elements");
	}

	@Test
	void shouldHasNextWithOneIteratorEmpty() {
		List<Iterator<Long>> iterators = new ArrayList<>();
		iterators.add(List.of(1L, 2L, 3L).iterator());
		iterators.add(Collections.emptyIterator());

		CommonElementsIterator commonElementsIterator = new CommonElementsIterator(iterators);

		assertFalse(commonElementsIterator.hasNext(), "Expected hasNext to return false when one iterator is empty");
	}

	@Test
	void shouldHasNextWithSingleIterator() {
		List<Iterator<Long>> iterators = new ArrayList<>();
		iterators.add(List.of(1L, 2L, 3L).iterator());

		CommonElementsIterator commonElementsIterator = new CommonElementsIterator(iterators);

		assertTrue(commonElementsIterator.hasNext(), "Expected hasNext to return true for a single non-empty iterator");
	}

	@Test
	void shouldHasNextAfterExhaustingIterator() {
		List<Iterator<Long>> iterators = new ArrayList<>();
		iterators.add(List.of(1L, 2L, 3L).iterator());
		iterators.add(List.of(3L, 4L).iterator());

		CommonElementsIterator commonElementsIterator = new CommonElementsIterator(iterators);

		assertTrue(commonElementsIterator.hasNext(), "Expected hasNext to return true initially");

		// Exhaust all common elements
		while (commonElementsIterator.hasNext()) {
			commonElementsIterator.next();
		}

		assertFalse(commonElementsIterator.hasNext(), "Expected hasNext to return false after iterators are exhausted");
	}

	@Test
	void shouldHasNextWithAllIteratorsEmpty() {
		List<Iterator<Long>> iterators = new ArrayList<>();
		iterators.add(Collections.emptyIterator());
		iterators.add(Collections.emptyIterator());

		CommonElementsIterator commonElementsIterator = new CommonElementsIterator(iterators);

		assertFalse(commonElementsIterator.hasNext(), "Expected hasNext to return false when all iterators are empty");
	}

	@Test
	void shouldReconstructExpectedSharedSequenceSimple() {
		List<Iterator<Long>> iterators = new ArrayList<>();
		iterators.add(List.of(1L, 2L, 3L).iterator());
		iterators.add(List.of(2L, 3L, 4L, 5L).iterator());

		CommonElementsIterator commonElementsIterator = new CommonElementsIterator(iterators);

		verifyCommonElements(commonElementsIterator, 2L, 3L);
	}

	@Test
	void shouldReconstructExpectedSharedSequenceWithFiveIterators() {
		List<Iterator<Long>> iterators = new ArrayList<>();
		iterators.add(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L).iterator());
		iterators.add(List.of(2L, 4L, 6L, 8L, 10L, 12L, 14L, 16L, 18L, 20L, 22L, 24L, 26L, 28L, 30L, 32L, 34L, 36L, 38L, 40L).iterator());
		iterators.add(List.of(8L, 12L, 14L, 16L, 18L, 20L, 22L, 24L, 26L, 28L, 30L, 32L, 34L, 36L, 38L, 40L, 42L, 44L, 46L, 48L).iterator());
		iterators.add(List.of(4L, 8L, 14L, 18L, 20L, 22L, 24L, 26L, 28L, 30L, 32L, 34L, 36L, 38L, 40L, 42L, 44L, 48L, 50L, 52L).iterator());
		iterators.add(List.of(8L, 14L, 16L, 18L, 20L, 22L, 24L, 26L, 28L, 32L, 34L, 36L, 38L, 40L, 44L, 46L, 48L, 54L, 56L, 58L).iterator());

		CommonElementsIterator commonElementsIterator = new CommonElementsIterator(iterators);

		verifyCommonElements(commonElementsIterator, 8L, 14L, 18L, 20L);
	}

}