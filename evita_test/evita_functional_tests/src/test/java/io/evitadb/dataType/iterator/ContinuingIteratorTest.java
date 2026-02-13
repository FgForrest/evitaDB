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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
 * Tests for {@link ContinuingIterator} verifying the composite
 * iterator contract: sequential traversal across multiple
 * sub-iterators, construction validation, and edge-case handling.
 *
 * @author Jan Novotn&#253; (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ContinuingIterator functionality")
class ContinuingIteratorTest {

	@Nested
	@DisplayName("Construction validation")
	class ConstructionValidationTest {

		/**
		 * Verifies that passing a null array reference
		 * to the constructor is rejected.
		 */
		@SuppressWarnings("ResultOfObjectAllocationIgnored")
		@DisplayName("should reject null sub-iterators array")
		@Test
		void shouldRejectNullSubIterators() {
			final IllegalArgumentException exception = assertThrows(
				IllegalArgumentException.class,
				() -> new ContinuingIterator<>(
					(Iterator<String>[]) null
				)
			);

			assertEquals(
				"Iterators array must not be null!",
				exception.getMessage()
			);
		}

		/**
		 * Verifies that passing an empty varargs array
		 * to the constructor is rejected.
		 */
		@SuppressWarnings("ResultOfObjectAllocationIgnored")
		@DisplayName("should reject empty sub-iterator array")
		@Test
		void shouldRejectEmptySubIteratorArray() {
			final IllegalArgumentException exception = assertThrows(
				IllegalArgumentException.class,
				ContinuingIterator::new
			);

			assertEquals(
				"Iterators array must not be empty!",
				exception.getMessage()
			);
		}

		/**
		 * Verifies that null elements inside the sub-iterator
		 * array are detected with a descriptive message
		 * including the index.
		 */
		@SuppressWarnings("ResultOfObjectAllocationIgnored")
		@DisplayName(
			"should reject null elements in sub-iterator array"
		)
		@Test
		void shouldRejectNullElementsInSubIteratorArray() {
			final Iterator<String> validIterator =
				Arrays.asList("test").iterator();

			final IllegalArgumentException exception = assertThrows(
				IllegalArgumentException.class,
				() -> new ContinuingIterator<>(
					validIterator, null
				)
			);

			assertEquals(
				"Sub-iterator at index 1 must not be null!",
				exception.getMessage()
			);
		}
	}

	@Nested
	@DisplayName("Single sub-iterator traversal")
	class SingleSubIteratorTest {

		/**
		 * Verifies that a single non-empty sub-iterator
		 * is fully traversed in order.
		 */
		@DisplayName(
			"should iterate all elements from one sub-iterator"
		)
		@Test
		void shouldHandleSingleSubIterator() {
			final List<String> data =
				Arrays.asList("first", "second", "third");
			final ContinuingIterator<String> it =
				new ContinuingIterator<>(data.iterator());

			assertTrue(it.hasNext());
			assertEquals("first", it.next());
			assertTrue(it.hasNext());
			assertEquals("second", it.next());
			assertTrue(it.hasNext());
			assertEquals("third", it.next());
			assertFalse(it.hasNext());
			assertThrows(
				NoSuchElementException.class, it::next
			);
		}

		/**
		 * Verifies that wrapping a single empty iterator
		 * results in an immediately exhausted composite.
		 */
		@DisplayName(
			"should report exhausted for single empty iterator"
		)
		@Test
		void shouldHandleEmptyIteratorInstance() {
			final Iterator<String> emptyIterator =
				EmptyIterator.iteratorInstance(String.class);
			final ContinuingIterator<String> it =
				new ContinuingIterator<>(emptyIterator);

			assertFalse(it.hasNext());
			assertThrows(
				NoSuchElementException.class, it::next
			);
		}

		/**
		 * Verifies that calling `next()` without a preceding
		 * `hasNext()` still returns elements correctly.
		 */
		@DisplayName(
			"should return elements when next() called "
				+ "without hasNext()"
		)
		@Test
		void shouldCallNextWithoutHasNext() {
			final List<String> data =
				Arrays.asList("alpha", "beta");
			final ContinuingIterator<String> it =
				new ContinuingIterator<>(data.iterator());

			// call next() directly without hasNext()
			assertEquals("alpha", it.next());
			assertEquals("beta", it.next());
			assertFalse(it.hasNext());
		}
	}

	@Nested
	@DisplayName("Multiple sub-iterator chaining")
	class MultipleSubIteratorTest {

		/**
		 * Verifies seamless traversal across three
		 * non-empty sub-iterators.
		 */
		@DisplayName(
			"should chain elements from multiple sub-iterators"
		)
		@Test
		void shouldHandleMultipleSubIterators() {
			final List<String> data1 =
				Arrays.asList("first", "second");
			final List<String> data2 =
				Arrays.asList("third", "fourth");
			final List<String> data3 =
				Arrays.asList("fifth");

			final ContinuingIterator<String> it =
				new ContinuingIterator<>(
					data1.iterator(),
					data2.iterator(),
					data3.iterator()
				);

			assertTrue(it.hasNext());
			assertEquals("first", it.next());
			assertTrue(it.hasNext());
			assertEquals("second", it.next());
			assertTrue(it.hasNext());
			assertEquals("third", it.next());
			assertTrue(it.hasNext());
			assertEquals("fourth", it.next());
			assertTrue(it.hasNext());
			assertEquals("fifth", it.next());
			assertFalse(it.hasNext());
			assertThrows(
				NoSuchElementException.class, it::next
			);
		}

		/**
		 * Verifies that multiple empty sub-iterators
		 * result in an immediately exhausted composite.
		 */
		@DisplayName(
			"should report exhausted for multiple empty iterators"
		)
		@Test
		void shouldHandleMultipleEmptyIterators() {
			final Iterator<String> empty1 =
				EmptyIterator.iteratorInstance(String.class);
			final Iterator<String> empty2 =
				Collections.<String>emptyList().iterator();
			final ContinuingIterator<String> it =
				new ContinuingIterator<>(empty1, empty2);

			assertFalse(it.hasNext());
			assertThrows(
				NoSuchElementException.class, it::next
			);
		}

		/**
		 * Verifies that empty iterators sandwiched between
		 * non-empty ones are skipped transparently.
		 */
		@DisplayName(
			"should skip empty iterators between non-empty ones"
		)
		@Test
		void shouldHandleEmptyIteratorsInBetween() {
			final List<String> data1 =
				Arrays.asList("first", "second");
			final Iterator<String> empty =
				Collections.<String>emptyList().iterator();
			final List<String> data2 =
				Arrays.asList("third");

			final ContinuingIterator<String> it =
				new ContinuingIterator<>(
					data1.iterator(),
					empty,
					data2.iterator()
				);

			assertTrue(it.hasNext());
			assertEquals("first", it.next());
			assertTrue(it.hasNext());
			assertEquals("second", it.next());
			assertTrue(it.hasNext());
			assertEquals("third", it.next());
			assertFalse(it.hasNext());
			assertThrows(
				NoSuchElementException.class, it::next
			);
		}

		/**
		 * Verifies that an empty iterator at position zero
		 * is skipped and the next non-empty one is used.
		 */
		@DisplayName(
			"should skip empty iterator at the beginning"
		)
		@Test
		void shouldHandleEmptyIteratorAtBeginning() {
			final Iterator<String> empty =
				Collections.<String>emptyList().iterator();
			final List<String> data =
				Arrays.asList("first", "second");

			final ContinuingIterator<String> it =
				new ContinuingIterator<>(
					empty, data.iterator()
				);

			assertTrue(it.hasNext());
			assertEquals("first", it.next());
			assertTrue(it.hasNext());
			assertEquals("second", it.next());
			assertFalse(it.hasNext());
		}

		/**
		 * Verifies that an empty iterator at the last position
		 * does not affect traversal of preceding elements.
		 */
		@DisplayName(
			"should skip empty iterator at the end"
		)
		@Test
		void shouldHandleEmptyIteratorAtEnd() {
			final List<String> data =
				Arrays.asList("first", "second");
			final Iterator<String> empty =
				Collections.<String>emptyList().iterator();

			final ContinuingIterator<String> it =
				new ContinuingIterator<>(
					data.iterator(), empty
				);

			assertTrue(it.hasNext());
			assertEquals("first", it.next());
			assertTrue(it.hasNext());
			assertEquals("second", it.next());
			assertFalse(it.hasNext());
		}

		/**
		 * Verifies that the composite iterator works with
		 * non-String types (Integer in this case).
		 */
		@DisplayName(
			"should work with Integer sub-iterators"
		)
		@Test
		void shouldWorkWithDifferentDataTypes() {
			final List<Integer> data1 = Arrays.asList(1, 2);
			final List<Integer> data2 = Arrays.asList(3, 4);

			final ContinuingIterator<Integer> it =
				new ContinuingIterator<>(
					data1.iterator(), data2.iterator()
				);

			assertTrue(it.hasNext());
			assertEquals(Integer.valueOf(1), it.next());
			assertTrue(it.hasNext());
			assertEquals(Integer.valueOf(2), it.next());
			assertTrue(it.hasNext());
			assertEquals(Integer.valueOf(3), it.next());
			assertTrue(it.hasNext());
			assertEquals(Integer.valueOf(4), it.next());
			assertFalse(it.hasNext());
		}

		/**
		 * Verifies that the iterator handles a large number of
		 * sub-iterators (100) without issues, each containing
		 * a single element.
		 */
		@SuppressWarnings("unchecked")
		@DisplayName(
			"should handle large number of sub-iterators"
		)
		@Test
		void shouldHandleLargeNumberOfSubIterators() {
			final int count = 100;
			final Iterator<Integer>[] iterators =
				new Iterator[count];
			for (int i = 0; i < count; i++) {
				iterators[i] =
					Collections.singletonList(i).iterator();
			}

			final ContinuingIterator<Integer> it =
				new ContinuingIterator<>(iterators);

			final List<Integer> collected = new ArrayList<>();
			while (it.hasNext()) {
				collected.add(it.next());
			}

			assertEquals(count, collected.size());
			for (int i = 0; i < count; i++) {
				assertEquals(
					Integer.valueOf(i), collected.get(i)
				);
			}
		}
	}

	@Nested
	@DisplayName("hasNext() idempotency")
	class HasNextIdempotencyTest {

		/**
		 * Verifies that calling `hasNext()` multiple times
		 * does not advance the iterator or change its state.
		 */
		@DisplayName(
			"should not advance state on repeated hasNext() calls"
		)
		@Test
		void shouldVerifyHasNextBehaviorWithMultipleCalls() {
			final List<String> data1 =
				Arrays.asList("first");
			final List<String> data2 =
				Arrays.asList("second");

			final ContinuingIterator<String> it =
				new ContinuingIterator<>(
					data1.iterator(), data2.iterator()
				);

			// multiple hasNext calls before first next
			assertTrue(it.hasNext());
			assertTrue(it.hasNext());
			assertTrue(it.hasNext());

			assertEquals("first", it.next());

			// multiple hasNext calls at sub-iterator boundary
			assertTrue(it.hasNext());
			assertTrue(it.hasNext());

			assertEquals("second", it.next());

			// multiple hasNext calls after exhaustion
			assertFalse(it.hasNext());
			assertFalse(it.hasNext());
			assertFalse(it.hasNext());
		}
	}

	@Nested
	@DisplayName("Error handling")
	class ErrorHandlingTest {

		/**
		 * Verifies that calling `next()` on a fully exhausted
		 * iterator throws {@link NoSuchElementException}
		 * with the expected message.
		 */
		@DisplayName(
			"should throw NoSuchElementException "
				+ "when exhausted"
		)
		@Test
		void shouldThrowNoSuchElementWhenExhausted() {
			final List<String> data =
				Arrays.asList("only");
			final ContinuingIterator<String> it =
				new ContinuingIterator<>(data.iterator());

			// exhaust the iterator
			assertEquals("only", it.next());

			final NoSuchElementException exception =
				assertThrows(
					NoSuchElementException.class, it::next
				);

			assertEquals(
				"No more elements available!",
				exception.getMessage()
			);
		}
	}
}
