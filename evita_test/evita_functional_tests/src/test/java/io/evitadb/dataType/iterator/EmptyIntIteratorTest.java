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

import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link EmptyIntIterator} verifying the singleton
 * contract and the empty primitive int iterator behavior:
 * always exhausted, always throws on `nextInt()`.
 *
 * @author evitaDB
 */
@DisplayName("EmptyIntIterator functionality")
class EmptyIntIteratorTest {

	@Nested
	@DisplayName("Singleton contract")
	class SingletonContractTest {

		/**
		 * Verifies that the public `INSTANCE` field is
		 * not null.
		 */
		@DisplayName(
			"should provide public singleton instance"
		)
		@Test
		void shouldProvidePublicSingletonInstance() {
			assertNotNull(EmptyIntIterator.INSTANCE);
		}

		/**
		 * Verifies that repeated access to the `INSTANCE`
		 * field always returns the exact same object
		 * (identity equality).
		 */
		@DisplayName(
			"should return same instance on repeated access"
		)
		@Test
		void shouldReturnSameInstanceOnRepeatedAccess() {
			final EmptyIntIterator first =
				EmptyIntIterator.INSTANCE;
			final EmptyIntIterator second =
				EmptyIntIterator.INSTANCE;

			assertSame(first, second);
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
			final EmptyIntIterator it =
				EmptyIntIterator.INSTANCE;

			assertFalse(it.hasNext());
			assertFalse(it.hasNext());
			assertFalse(it.hasNext());
		}

		/**
		 * Verifies that `nextInt()` throws
		 * {@link NoSuchElementException} with the expected
		 * message.
		 */
		@DisplayName(
			"should throw NoSuchElementException "
				+ "on nextInt()"
		)
		@Test
		void shouldThrowOnNextInt() {
			final EmptyIntIterator it =
				EmptyIntIterator.INSTANCE;

			final NoSuchElementException exception =
				assertThrows(
					NoSuchElementException.class,
					it::nextInt
				);

			assertEquals(
				"No data in stream!",
				exception.getMessage()
			);
		}

		/**
		 * Verifies that calling `hasNext()` followed by
		 * `nextInt()` still throws, confirming that
		 * `hasNext()` does not change the empty state.
		 */
		@DisplayName(
			"should throw on nextInt() "
				+ "after hasNext() check"
		)
		@Test
		void shouldThrowOnNextIntAfterHasNextCheck() {
			final EmptyIntIterator it =
				EmptyIntIterator.INSTANCE;

			assertFalse(it.hasNext());
			assertThrows(
				NoSuchElementException.class, it::nextInt
			);
		}
	}

	@Nested
	@DisplayName("Type compatibility")
	class TypeCompatibilityTest {

		/**
		 * Verifies that {@link EmptyIntIterator} is
		 * assignable to {@link PrimitiveIterator.OfInt}.
		 */
		@DisplayName(
			"should be assignable to "
				+ "PrimitiveIterator.OfInt"
		)
		@Test
		void shouldBeAssignableToPrimitiveIteratorOfInt() {
			assertInstanceOf(
				PrimitiveIterator.OfInt.class,
				EmptyIntIterator.INSTANCE
			);
		}
	}
}
