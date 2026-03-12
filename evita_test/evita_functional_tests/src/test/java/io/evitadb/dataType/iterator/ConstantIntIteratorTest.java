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
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ConstantIntIterator} verifying the primitive
 * int iterator contract over a constant array, including correct
 * handling of negative values such as `-1`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("ConstantIntIterator functionality")
class ConstantIntIteratorTest {

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
			final ConstantIntIterator it =
				new ConstantIntIterator(new int[0]);

			assertFalse(it.hasNext());
		}

		/**
		 * Verifies that calling `nextInt()` on an iterator
		 * backed by an empty array throws the expected
		 * exception with a descriptive message.
		 */
		@DisplayName(
			"should throw when calling nextInt() "
				+ "on empty array"
		)
		@Test
		void shouldThrowWhenCallingNextIntOnEmptyArray() {
			final ConstantIntIterator it =
				new ConstantIntIterator(new int[0]);

			final NoSuchElementException exception =
				assertThrows(
					NoSuchElementException.class,
					it::nextInt
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
		 * correctly and the iterator becomes exhausted after.
		 */
		@DisplayName("should iterate single element")
		@Test
		void shouldIterateSingleElement() {
			final ConstantIntIterator it =
				new ConstantIntIterator(new int[]{42});

			assertTrue(it.hasNext());
			assertEquals(42, it.nextInt());
			assertFalse(it.hasNext());
		}

		/**
		 * Verifies that zero is iterated correctly and is
		 * not confused with the sentinel value.
		 */
		@DisplayName("should iterate single zero value")
		@Test
		void shouldIterateSingleZero() {
			final ConstantIntIterator it =
				new ConstantIntIterator(new int[]{0});

			assertTrue(it.hasNext());
			assertEquals(0, it.nextInt());
			assertFalse(it.hasNext());
		}

		/**
		 * Verifies that `Integer.MAX_VALUE` is handled
		 * correctly as a boundary value.
		 */
		@DisplayName(
			"should iterate single Integer.MAX_VALUE"
		)
		@Test
		void shouldIterateSingleMaxValue() {
			final ConstantIntIterator it =
				new ConstantIntIterator(
					new int[]{Integer.MAX_VALUE}
				);

			assertTrue(it.hasNext());
			assertEquals(Integer.MAX_VALUE, it.nextInt());
			assertFalse(it.hasNext());
		}

		/**
		 * Verifies that `Integer.MIN_VALUE` is handled
		 * correctly as a boundary value.
		 */
		@DisplayName(
			"should iterate single Integer.MIN_VALUE"
		)
		@Test
		void shouldIterateSingleMinValue() {
			final ConstantIntIterator it =
				new ConstantIntIterator(
					new int[]{Integer.MIN_VALUE}
				);

			assertTrue(it.hasNext());
			assertEquals(
				Integer.MIN_VALUE, it.nextInt()
			);
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
			final ConstantIntIterator it =
				new ConstantIntIterator(
					new int[]{1, 2, 3, 4, 5}
				);

			final List<Integer> collected = new ArrayList<>();
			while (it.hasNext()) {
				collected.add(it.nextInt());
			}

			assertEquals(5, collected.size());
			assertEquals(1, collected.get(0));
			assertEquals(2, collected.get(1));
			assertEquals(3, collected.get(2));
			assertEquals(4, collected.get(3));
			assertEquals(5, collected.get(4));
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
			final ConstantIntIterator it =
				new ConstantIntIterator(
					new int[]{3, 3, 3}
				);

			assertEquals(3, it.nextInt());
			assertEquals(3, it.nextInt());
			assertEquals(3, it.nextInt());
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
			final int[] data = new int[size];
			for (int i = 0; i < size; i++) {
				data[i] = i * 2;
			}

			final ConstantIntIterator it =
				new ConstantIntIterator(data);

			int count = 0;
			while (it.hasNext()) {
				final int value = it.nextInt();
				assertEquals(count * 2, value);
				count++;
			}

			assertEquals(size, count);
		}

		/**
		 * Verifies that negative values (other than -1) are
		 * iterated correctly.
		 */
		@DisplayName(
			"should iterate negative values correctly"
		)
		@Test
		void shouldIterateNegativeValues() {
			final ConstantIntIterator it =
				new ConstantIntIterator(
					new int[]{-5, -3, -2, 0, 1}
				);

			assertEquals(-5, it.nextInt());
			assertEquals(-3, it.nextInt());
			assertEquals(-2, it.nextInt());
			assertEquals(0, it.nextInt());
			assertEquals(1, it.nextInt());
			assertFalse(it.hasNext());
		}
	}

	@Nested
	@DisplayName("hasNext() idempotency")
	class HasNextIdempotencyTest {

		/**
		 * Verifies that calling `hasNext()` multiple times
		 * without an intervening `nextInt()` always returns
		 * a consistent result.
		 */
		@DisplayName(
			"should return consistent hasNext() on "
				+ "repeated calls"
		)
		@Test
		void shouldReturnConsistentHasNext() {
			final ConstantIntIterator it =
				new ConstantIntIterator(new int[]{10});

			// triple-check before consuming element
			assertTrue(it.hasNext());
			assertTrue(it.hasNext());
			assertTrue(it.hasNext());

			assertEquals(10, it.nextInt());

			// triple-check after exhaustion
			assertFalse(it.hasNext());
			assertFalse(it.hasNext());
			assertFalse(it.hasNext());
		}

		/**
		 * Verifies that `nextInt()` can be called without
		 * a preceding `hasNext()` check.
		 */
		@DisplayName(
			"should allow nextInt() without hasNext()"
		)
		@Test
		void shouldAllowNextWithoutHasNext() {
			final ConstantIntIterator it =
				new ConstantIntIterator(new int[]{7, 8});

			// call nextInt() directly without hasNext()
			assertEquals(7, it.nextInt());
			assertEquals(8, it.nextInt());
			assertFalse(it.hasNext());
		}
	}

	@Nested
	@DisplayName("Negative value (-1) handling")
	class NegativeValueTest {

		/**
		 * Verifies that -1 at the start of the array is
		 * returned as a regular element and does not cause
		 * premature termination.
		 */
		@DisplayName(
			"should iterate -1 at array start correctly"
		)
		@Test
		void shouldIterateNegativeOneAtStart() {
			final ConstantIntIterator it =
				new ConstantIntIterator(
					new int[]{-1, 2, 3}
				);

			// -1 is a valid data value, not a sentinel
			assertTrue(it.hasNext());
			assertEquals(-1, it.nextInt());
			assertEquals(2, it.nextInt());
			assertEquals(3, it.nextInt());
			assertFalse(it.hasNext());
		}

		/**
		 * Verifies that -1 in the middle of the array is
		 * returned as a regular element and iteration
		 * continues past it.
		 */
		@DisplayName(
			"should iterate -1 in array middle correctly"
		)
		@Test
		void shouldIterateNegativeOneInMiddle() {
			final ConstantIntIterator it =
				new ConstantIntIterator(
					new int[]{1, 2, -1, 4, 5}
				);

			assertEquals(1, it.nextInt());
			assertEquals(2, it.nextInt());
			// -1 is returned as a regular element
			assertEquals(-1, it.nextInt());
			assertEquals(4, it.nextInt());
			assertEquals(5, it.nextInt());
			assertFalse(it.hasNext());
		}

		/**
		 * Verifies that -1 at the end of the array is
		 * returned as a regular element.
		 */
		@DisplayName(
			"should iterate -1 at array end correctly"
		)
		@Test
		void shouldIterateNegativeOneAtEnd() {
			final ConstantIntIterator it =
				new ConstantIntIterator(
					new int[]{1, 2, 3, -1}
				);

			assertEquals(1, it.nextInt());
			assertEquals(2, it.nextInt());
			assertEquals(3, it.nextInt());
			// -1 at the end is a valid element
			assertEquals(-1, it.nextInt());
			assertFalse(it.hasNext());
		}

		/**
		 * Verifies that an array containing only -1 values
		 * is iterated completely, returning all elements.
		 */
		@DisplayName(
			"should iterate all -1 values correctly"
		)
		@Test
		void shouldIterateAllNegativeOnes() {
			final ConstantIntIterator it =
				new ConstantIntIterator(
					new int[]{-1, -1, -1}
				);

			assertTrue(it.hasNext());
			assertEquals(-1, it.nextInt());
			assertEquals(-1, it.nextInt());
			assertEquals(-1, it.nextInt());
			assertFalse(it.hasNext());
		}
	}

	@Nested
	@DisplayName("Type compatibility")
	class TypeCompatibilityTest {

		/**
		 * Verifies that {@link ConstantIntIterator} is
		 * assignable to {@link PrimitiveIterator.OfInt}.
		 */
		@DisplayName(
			"should be assignable to "
				+ "PrimitiveIterator.OfInt"
		)
		@Test
		void shouldBeAssignableToPrimitiveIteratorOfInt() {
			final ConstantIntIterator it =
				new ConstantIntIterator(new int[]{1});

			assertInstanceOf(
				PrimitiveIterator.OfInt.class, it
			);
		}
	}
}
