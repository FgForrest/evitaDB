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
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ConstantObjIterator} verifying the generic
 * object iterator contract over a constant array, including
 * correct handling of `null` elements within the array.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("ConstantObjIterator functionality")
class ConstantObjIteratorTest {

	@Nested
	@DisplayName("Empty array iteration")
	class EmptyArrayTest {

		/**
		 * Verifies that an iterator over an empty array
		 * immediately reports no elements available.
		 */
		@DisplayName(
			"should report no elements for empty array"
		)
		@Test
		void shouldReportNoElementsForEmptyArray() {
			final ConstantObjIterator<String> it =
				new ConstantObjIterator<>(new String[0]);

			assertFalse(it.hasNext());
		}

		/**
		 * Verifies that calling `next()` on an iterator
		 * backed by an empty array throws the expected
		 * exception with a descriptive message.
		 */
		@DisplayName(
			"should throw when calling next() "
				+ "on empty array"
		)
		@Test
		void shouldThrowWhenCallingNextOnEmptyArray() {
			final ConstantObjIterator<String> it =
				new ConstantObjIterator<>(new String[0]);

			final NoSuchElementException exception =
				assertThrows(
					NoSuchElementException.class, it::next
				);

			assertEquals(
				"Stream exhausted!", exception.getMessage()
			);
		}
	}

	@Nested
	@DisplayName("Single element iteration")
	class SingleElementTest {

		/**
		 * Verifies that a single-element array is iterated
		 * correctly and the iterator becomes exhausted.
		 */
		@DisplayName("should iterate single element")
		@Test
		void shouldIterateSingleElement() {
			final ConstantObjIterator<String> it =
				new ConstantObjIterator<>(
					new String[]{"hello"}
				);

			assertTrue(it.hasNext());
			assertEquals("hello", it.next());
			assertFalse(it.hasNext());
		}

		/**
		 * Verifies that an empty string is a valid element
		 * and is not confused with a sentinel value.
		 */
		@DisplayName(
			"should iterate single empty string"
		)
		@Test
		void shouldIterateSingleEmptyString() {
			final ConstantObjIterator<String> it =
				new ConstantObjIterator<>(
					new String[]{""}
				);

			assertTrue(it.hasNext());
			assertEquals("", it.next());
			assertFalse(it.hasNext());
		}
	}

	@Nested
	@DisplayName("Multi-element iteration")
	class MultiElementTest {

		/**
		 * Verifies that multiple elements are returned
		 * in the original array order.
		 */
		@DisplayName(
			"should iterate multiple elements in order"
		)
		@Test
		void shouldIterateMultipleElements() {
			final ConstantObjIterator<String> it =
				new ConstantObjIterator<>(
					new String[]{"a", "b", "c"}
				);

			assertEquals("a", it.next());
			assertEquals("b", it.next());
			assertEquals("c", it.next());
			assertFalse(it.hasNext());
		}

		/**
		 * Verifies that duplicate values in the array are
		 * each returned individually.
		 */
		@DisplayName(
			"should iterate array with duplicate values"
		)
		@Test
		void shouldIterateWithDuplicates() {
			final ConstantObjIterator<String> it =
				new ConstantObjIterator<>(
					new String[]{"x", "x", "x"}
				);

			assertEquals("x", it.next());
			assertEquals("x", it.next());
			assertEquals("x", it.next());
			assertFalse(it.hasNext());
		}

		/**
		 * Verifies that the iterator works with a
		 * non-String object type (Integer).
		 */
		@DisplayName(
			"should iterate different object types"
		)
		@Test
		void shouldIterateDifferentObjectTypes() {
			final ConstantObjIterator<Integer> it =
				new ConstantObjIterator<>(
					new Integer[]{1, 2, 3}
				);

			assertEquals(Integer.valueOf(1), it.next());
			assertEquals(Integer.valueOf(2), it.next());
			assertEquals(Integer.valueOf(3), it.next());
			assertFalse(it.hasNext());
		}

		/**
		 * Verifies correct iteration over a large array
		 * of 1000 elements.
		 */
		@DisplayName("should iterate large array")
		@Test
		void shouldIterateLargeArray() {
			final int size = 1000;
			final String[] data = new String[size];
			for (int i = 0; i < size; i++) {
				data[i] = "item-" + i;
			}

			final ConstantObjIterator<String> it =
				new ConstantObjIterator<>(data);

			final List<String> collected = new ArrayList<>();
			while (it.hasNext()) {
				collected.add(it.next());
			}

			assertEquals(size, collected.size());
			for (int i = 0; i < size; i++) {
				assertEquals(
					"item-" + i, collected.get(i)
				);
			}
		}
	}

	@Nested
	@DisplayName("hasNext() idempotency")
	class HasNextIdempotencyTest {

		/**
		 * Verifies that calling `hasNext()` multiple times
		 * without an intervening `next()` always returns
		 * a consistent result.
		 */
		@DisplayName(
			"should return consistent hasNext() on "
				+ "repeated calls"
		)
		@Test
		void shouldReturnConsistentHasNext() {
			final ConstantObjIterator<String> it =
				new ConstantObjIterator<>(
					new String[]{"only"}
				);

			// triple-check before consuming element
			assertTrue(it.hasNext());
			assertTrue(it.hasNext());
			assertTrue(it.hasNext());

			assertEquals("only", it.next());

			// triple-check after exhaustion
			assertFalse(it.hasNext());
			assertFalse(it.hasNext());
			assertFalse(it.hasNext());
		}

		/**
		 * Verifies that `next()` can be called without
		 * a preceding `hasNext()` check.
		 */
		@DisplayName(
			"should allow next() without hasNext()"
		)
		@Test
		void shouldAllowNextWithoutHasNext() {
			final ConstantObjIterator<String> it =
				new ConstantObjIterator<>(
					new String[]{"alpha", "beta"}
				);

			// call next() directly without hasNext()
			assertEquals("alpha", it.next());
			assertEquals("beta", it.next());
			assertFalse(it.hasNext());
		}
	}

	@Nested
	@DisplayName("Null element handling")
	class NullElementTest {

		/**
		 * Verifies that a `null` element at the start of
		 * the array is returned as a regular element and
		 * does not cause premature termination.
		 */
		@DisplayName(
			"should iterate null at array start correctly"
		)
		@Test
		void shouldIterateNullAtStart() {
			final ConstantObjIterator<String> it =
				new ConstantObjIterator<>(
					new String[]{null, "b", "c"}
				);

			// null is a valid data value, not a sentinel
			assertTrue(it.hasNext());
			assertNull(it.next());
			assertEquals("b", it.next());
			assertEquals("c", it.next());
			assertFalse(it.hasNext());
		}

		/**
		 * Verifies that a `null` element in the middle of
		 * the array is returned as a regular element and
		 * iteration continues past it.
		 */
		@DisplayName(
			"should iterate null in array middle correctly"
		)
		@Test
		void shouldIterateNullInMiddle() {
			final ConstantObjIterator<String> it =
				new ConstantObjIterator<>(
					new String[]{"a", null, "c"}
				);

			assertEquals("a", it.next());
			// null is returned as a regular element
			assertNull(it.next());
			assertEquals("c", it.next());
			assertFalse(it.hasNext());
		}

		/**
		 * Verifies that a `null` element at the end of the
		 * array is returned as a regular element.
		 */
		@DisplayName(
			"should iterate null at array end correctly"
		)
		@Test
		void shouldIterateNullAtEnd() {
			final ConstantObjIterator<String> it =
				new ConstantObjIterator<>(
					new String[]{"a", "b", null}
				);

			assertEquals("a", it.next());
			assertEquals("b", it.next());
			// null at the end is a valid element
			assertNull(it.next());
			assertFalse(it.hasNext());
		}

		/**
		 * Verifies that an array containing only `null`
		 * values is iterated completely, returning all
		 * elements.
		 */
		@DisplayName(
			"should iterate all null values correctly"
		)
		@Test
		void shouldIterateAllNulls() {
			final ConstantObjIterator<String> it =
				new ConstantObjIterator<>(
					new String[]{null, null, null}
				);

			assertTrue(it.hasNext());
			assertNull(it.next());
			assertNull(it.next());
			assertNull(it.next());
			assertFalse(it.hasNext());
		}
	}

	@Nested
	@DisplayName("Type compatibility")
	class TypeCompatibilityTest {

		/**
		 * Verifies that {@link ConstantObjIterator} is
		 * assignable to {@link Iterator}.
		 */
		@DisplayName(
			"should be assignable to Iterator"
		)
		@Test
		void shouldBeAssignableToIterator() {
			final ConstantObjIterator<String> it =
				new ConstantObjIterator<>(
					new String[]{"test"}
				);

			assertInstanceOf(Iterator.class, it);
		}
	}
}
