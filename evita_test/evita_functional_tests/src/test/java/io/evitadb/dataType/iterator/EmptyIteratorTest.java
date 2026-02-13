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

import java.util.Iterator;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link EmptyIterator} verifying the singleton
 * contracts for both the iterator and iterable factory methods,
 * and the empty iterator behavior: always exhausted, always
 * throws on `next()`.
 *
 * @author evitaDB
 */
@DisplayName("EmptyIterator functionality")
class EmptyIteratorTest {

	@Nested
	@DisplayName("Iterator singleton")
	class IteratorSingletonTest {

		/**
		 * Verifies that the iterator factory method returns
		 * a non-null instance.
		 */
		@DisplayName(
			"should return non-null iterator instance"
		)
		@Test
		void shouldReturnNonNullIteratorInstance() {
			final Iterator<String> it =
				EmptyIterator.iteratorInstance(
					String.class
				);

			assertNotNull(it);
		}

		/**
		 * Verifies that repeated calls to
		 * `iteratorInstance()` with the same type return
		 * the exact same singleton object.
		 */
		@DisplayName(
			"should return same iterator instance "
				+ "for same type"
		)
		@Test
		void shouldReturnSameIteratorInstanceForSameType() {
			final Iterator<String> first =
				EmptyIterator.iteratorInstance(
					String.class
				);
			final Iterator<String> second =
				EmptyIterator.iteratorInstance(
					String.class
				);

			assertSame(first, second);
		}

		/**
		 * Verifies that `iteratorInstance()` returns the
		 * same singleton regardless of the type parameter,
		 * since the empty iterator is stateless and
		 * type-erased.
		 */
		@DisplayName(
			"should return same iterator instance "
				+ "for different types"
		)
		@Test
		void shouldReturnSameIteratorInstanceForDifferentTypes() {
			final Iterator<String> stringIt =
				EmptyIterator.iteratorInstance(
					String.class
				);
			final Iterator<Integer> intIt =
				EmptyIterator.iteratorInstance(
					Integer.class
				);

			assertSame(stringIt, intIt);
		}
	}

	@Nested
	@DisplayName("Iterable singleton")
	class IterableSingletonTest {

		/**
		 * Verifies that the iterable factory method returns
		 * a non-null instance.
		 */
		@DisplayName(
			"should return non-null iterable instance"
		)
		@Test
		void shouldReturnNonNullIterableInstance() {
			final Iterable<String> iterable =
				EmptyIterator.iterableInstance(
					String.class
				);

			assertNotNull(iterable);
		}

		/**
		 * Verifies that repeated calls to
		 * `iterableInstance()` with the same type return
		 * the exact same singleton object.
		 */
		@DisplayName(
			"should return same iterable instance "
				+ "for same type"
		)
		@Test
		void shouldReturnSameIterableInstanceForSameType() {
			final Iterable<String> first =
				EmptyIterator.iterableInstance(
					String.class
				);
			final Iterable<String> second =
				EmptyIterator.iterableInstance(
					String.class
				);

			assertSame(first, second);
		}

		/**
		 * Verifies that `iterableInstance()` returns the
		 * same singleton regardless of the type parameter.
		 */
		@DisplayName(
			"should return same iterable instance "
				+ "for different types"
		)
		@Test
		void shouldReturnSameIterableInstanceForDifferentTypes() {
			final Iterable<String> stringIt =
				EmptyIterator.iterableInstance(
					String.class
				);
			final Iterable<Integer> intIt =
				EmptyIterator.iterableInstance(
					Integer.class
				);

			assertSame(stringIt, intIt);
		}

		/**
		 * Verifies that the iterable's `iterator()` method
		 * returns an empty iterator (i.e., one where
		 * `hasNext()` is immediately false).
		 */
		@DisplayName(
			"should return empty iterator from iterable"
		)
		@Test
		void shouldReturnEmptyIteratorFromIterable() {
			final Iterable<String> iterable =
				EmptyIterator.iterableInstance(
					String.class
				);
			final Iterator<String> it = iterable.iterator();

			assertNotNull(it);
			assertFalse(it.hasNext());
		}
	}

	@Nested
	@DisplayName("Iterator behavior")
	class IteratorBehaviorTest {

		/**
		 * Verifies that `hasNext()` always returns `false`,
		 * even when called multiple times.
		 */
		@DisplayName(
			"should always return false for hasNext()"
		)
		@Test
		void shouldAlwaysReturnFalseForHasNext() {
			final Iterator<String> it =
				EmptyIterator.iteratorInstance(
					String.class
				);

			assertFalse(it.hasNext());
			assertFalse(it.hasNext());
			assertFalse(it.hasNext());
		}

		/**
		 * Verifies that `next()` throws
		 * {@link NoSuchElementException} with the expected
		 * message.
		 */
		@DisplayName(
			"should throw NoSuchElementException on next()"
		)
		@Test
		void shouldThrowOnNext() {
			final Iterator<String> it =
				EmptyIterator.iteratorInstance(
					String.class
				);

			final NoSuchElementException exception =
				assertThrows(
					NoSuchElementException.class, it::next
				);

			assertEquals(
				"No data in stream!",
				exception.getMessage()
			);
		}

		/**
		 * Verifies that calling `hasNext()` followed by
		 * `next()` still throws, confirming that
		 * `hasNext()` does not change the empty state.
		 */
		@DisplayName(
			"should throw on next() after hasNext() check"
		)
		@Test
		void shouldThrowOnNextAfterHasNextCheck() {
			final Iterator<String> it =
				EmptyIterator.iteratorInstance(
					String.class
				);

			assertFalse(it.hasNext());
			assertThrows(
				NoSuchElementException.class, it::next
			);
		}
	}

	@Nested
	@DisplayName("Type compatibility")
	class TypeCompatibilityTest {

		/**
		 * Verifies that the iterable instance can be used
		 * in a for-each loop without iterating any elements.
		 */
		@DisplayName(
			"should be usable in for-each loop "
				+ "with zero iterations"
		)
		@Test
		void shouldBeUsableInForEachLoop() {
			final Iterable<String> iterable =
				EmptyIterator.iterableInstance(
					String.class
				);

			int count = 0;
			for (final String ignored : iterable) {
				count++;
			}

			assertEquals(0, count);
		}
	}
}
