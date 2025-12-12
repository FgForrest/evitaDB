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

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies {@link ContinuingIterator} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
class ContinuingIteratorTest {

	@SuppressWarnings("ResultOfObjectAllocationIgnored")
	@Test
	void shouldRejectNullSubIterators() {
		assertThrows(
			IllegalArgumentException.class,
			() -> new ContinuingIterator<>((Iterator<String>[]) null),
			"Iterators array must not be null!"
		);
	}

	@SuppressWarnings("ResultOfObjectAllocationIgnored")
	@Test
	void shouldRejectEmptySubIteratorArray() {
		assertThrows(
			IllegalArgumentException.class,
			ContinuingIterator::new,
			"Iterators array must not be empty!"
		);
	}

	@SuppressWarnings("ResultOfObjectAllocationIgnored")
	@Test
	void shouldRejectNullElementsInSubIteratorArray() {
		final Iterator<String> validIterator = Arrays.asList("test").iterator();
		assertThrows(
			IllegalArgumentException.class,
			() -> new ContinuingIterator<>(validIterator, null),
			"Sub-iterator at index 1 must not be null!"
		);
	}

	@Test
	void shouldHandleEmptyIteratorInstance() {
		final Iterator<String> emptyIterator = EmptyIterator.iteratorInstance(String.class);
		final ContinuingIterator<String> compositeIterator = new ContinuingIterator<>(emptyIterator);

		assertFalse(compositeIterator.hasNext());
		assertThrows(NoSuchElementException.class, compositeIterator::next);
	}

	@Test
	void shouldHandleMultipleEmptyIterators() {
		final Iterator<String> emptyIterator1 = EmptyIterator.iteratorInstance(String.class);
		final Iterator<String> emptyIterator2 = Collections.<String>emptyList().iterator();
		final ContinuingIterator<String> compositeIterator = new ContinuingIterator<>(emptyIterator1, emptyIterator2);

		assertFalse(compositeIterator.hasNext());
		assertThrows(NoSuchElementException.class, compositeIterator::next);
	}

	@Test
	void shouldHandleSingleSubIterator() {
		final List<String> data = Arrays.asList("first", "second", "third");
		final ContinuingIterator<String> compositeIterator = new ContinuingIterator<>(data.iterator());

		assertTrue(compositeIterator.hasNext());
		assertEquals("first", compositeIterator.next());
		assertTrue(compositeIterator.hasNext());
		assertEquals("second", compositeIterator.next());
		assertTrue(compositeIterator.hasNext());
		assertEquals("third", compositeIterator.next());
		assertFalse(compositeIterator.hasNext());
		assertThrows(NoSuchElementException.class, compositeIterator::next);
	}

	@Test
	void shouldHandleMultipleSubIterators() {
		final List<String> data1 = Arrays.asList("first", "second");
		final List<String> data2 = Arrays.asList("third", "fourth");
		final List<String> data3 = Arrays.asList("fifth");

		final ContinuingIterator<String> compositeIterator = new ContinuingIterator<>(
			data1.iterator(),
			data2.iterator(),
			data3.iterator()
		);

		assertTrue(compositeIterator.hasNext());
		assertEquals("first", compositeIterator.next());
		assertTrue(compositeIterator.hasNext());
		assertEquals("second", compositeIterator.next());
		assertTrue(compositeIterator.hasNext());
		assertEquals("third", compositeIterator.next());
		assertTrue(compositeIterator.hasNext());
		assertEquals("fourth", compositeIterator.next());
		assertTrue(compositeIterator.hasNext());
		assertEquals("fifth", compositeIterator.next());
		assertFalse(compositeIterator.hasNext());
		assertThrows(NoSuchElementException.class, compositeIterator::next);
	}

	@Test
	void shouldHandleEmptyIteratorsInBetween() {
		final List<String> data1 = Arrays.asList("first", "second");
		final Iterator<String> emptyIterator = Collections.<String>emptyList().iterator();
		final List<String> data2 = Arrays.asList("third");

		final ContinuingIterator<String> compositeIterator = new ContinuingIterator<>(
			data1.iterator(),
			emptyIterator,
			data2.iterator()
		);

		assertTrue(compositeIterator.hasNext());
		assertEquals("first", compositeIterator.next());
		assertTrue(compositeIterator.hasNext());
		assertEquals("second", compositeIterator.next());
		assertTrue(compositeIterator.hasNext());
		assertEquals("third", compositeIterator.next());
		assertFalse(compositeIterator.hasNext());
		assertThrows(NoSuchElementException.class, compositeIterator::next);
	}

	@Test
	void shouldVerifyHasNextBehaviorWithMultipleCalls() {
		final List<String> data1 = Arrays.asList("first");
		final List<String> data2 = Arrays.asList("second");

		final ContinuingIterator<String> compositeIterator = new ContinuingIterator<>(
			data1.iterator(),
			data2.iterator()
		);

		// Multiple hasNext calls should not affect the state
		assertTrue(compositeIterator.hasNext());
		assertTrue(compositeIterator.hasNext());
		assertTrue(compositeIterator.hasNext());

		assertEquals("first", compositeIterator.next());

		// Multiple hasNext calls should not affect the state
		assertTrue(compositeIterator.hasNext());
		assertTrue(compositeIterator.hasNext());

		assertEquals("second", compositeIterator.next());

		// Multiple hasNext calls should not affect the state
		assertFalse(compositeIterator.hasNext());
		assertFalse(compositeIterator.hasNext());
		assertFalse(compositeIterator.hasNext());
	}

	@Test
	void shouldHandleEmptyIteratorAtBeginning() {
		final Iterator<String> emptyIterator = Collections.<String>emptyList().iterator();
		final List<String> data = Arrays.asList("first", "second");

		final ContinuingIterator<String> compositeIterator = new ContinuingIterator<>(
			emptyIterator,
			data.iterator()
		);

		assertTrue(compositeIterator.hasNext());
		assertEquals("first", compositeIterator.next());
		assertTrue(compositeIterator.hasNext());
		assertEquals("second", compositeIterator.next());
		assertFalse(compositeIterator.hasNext());
	}

	@Test
	void shouldHandleEmptyIteratorAtEnd() {
		final List<String> data = Arrays.asList("first", "second");
		final Iterator<String> emptyIterator = Collections.<String>emptyList().iterator();

		final ContinuingIterator<String> compositeIterator = new ContinuingIterator<>(
			data.iterator(),
			emptyIterator
		);

		assertTrue(compositeIterator.hasNext());
		assertEquals("first", compositeIterator.next());
		assertTrue(compositeIterator.hasNext());
		assertEquals("second", compositeIterator.next());
		assertFalse(compositeIterator.hasNext());
	}

	@Test
	void shouldWorkWithDifferentDataTypes() {
		final List<Integer> data1 = Arrays.asList(1, 2);
		final List<Integer> data2 = Arrays.asList(3, 4);

		final ContinuingIterator<Integer> compositeIterator = new ContinuingIterator<>(
			data1.iterator(),
			data2.iterator()
		);

		assertTrue(compositeIterator.hasNext());
		assertEquals(Integer.valueOf(1), compositeIterator.next());
		assertTrue(compositeIterator.hasNext());
		assertEquals(Integer.valueOf(2), compositeIterator.next());
		assertTrue(compositeIterator.hasNext());
		assertEquals(Integer.valueOf(3), compositeIterator.next());
		assertTrue(compositeIterator.hasNext());
		assertEquals(Integer.valueOf(4), compositeIterator.next());
		assertFalse(compositeIterator.hasNext());
	}
}