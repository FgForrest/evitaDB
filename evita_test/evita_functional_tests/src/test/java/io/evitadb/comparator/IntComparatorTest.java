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

package io.evitadb.comparator;

import io.evitadb.comparator.IntComparator.IntAscendingComparator;
import io.evitadb.comparator.IntComparator.IntDescendingComparator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link IntComparator} verifying the ascending and
 * descending comparator implementations for primitive integers.
 *
 * @author evitaDB
 */
@DisplayName("IntComparator functionality")
class IntComparatorTest {

	@Nested
	@DisplayName("Ascending comparator")
	class AscendingComparatorTest {

		private final IntAscendingComparator comparator =
			IntAscendingComparator.INSTANCE;

		@Test
		@DisplayName(
			"should return zero when both values are equal"
		)
		void shouldReturnZeroWhenBothValuesAreEqual() {
			assertEquals(0, comparator.compare(5, 5));
			assertEquals(0, comparator.compare(0, 0));
			assertEquals(0, comparator.compare(-1, -1));
		}

		@Test
		@DisplayName(
			"should return negative when first is less than second"
		)
		void shouldReturnNegativeWhenFirstIsLessThanSecond() {
			assertTrue(comparator.compare(1, 5) < 0);
			assertTrue(comparator.compare(-10, 10) < 0);
			assertTrue(comparator.compare(0, 1) < 0);
		}

		@Test
		@DisplayName(
			"should return positive when first is greater than second"
		)
		void shouldReturnPositiveWhenFirstIsGreaterThanSecond() {
			assertTrue(comparator.compare(5, 1) > 0);
			assertTrue(comparator.compare(10, -10) > 0);
			assertTrue(comparator.compare(1, 0) > 0);
		}

		@Test
		@DisplayName(
			"should handle Integer.MIN_VALUE correctly"
		)
		void shouldHandleMinValueCorrectly() {
			assertTrue(
				comparator.compare(
					Integer.MIN_VALUE, Integer.MAX_VALUE
				) < 0
			);
			assertTrue(
				comparator.compare(Integer.MIN_VALUE, 0) < 0
			);
			assertEquals(
				0,
				comparator.compare(
					Integer.MIN_VALUE, Integer.MIN_VALUE
				)
			);
		}

		@Test
		@DisplayName(
			"should handle Integer.MAX_VALUE correctly"
		)
		void shouldHandleMaxValueCorrectly() {
			assertTrue(
				comparator.compare(
					Integer.MAX_VALUE, Integer.MIN_VALUE
				) > 0
			);
			assertTrue(
				comparator.compare(Integer.MAX_VALUE, 0) > 0
			);
			assertEquals(
				0,
				comparator.compare(
					Integer.MAX_VALUE, Integer.MAX_VALUE
				)
			);
		}

		@Test
		@DisplayName(
			"should handle comparisons with zero"
		)
		void shouldHandleComparisonsWithZero() {
			assertTrue(comparator.compare(0, 1) < 0);
			assertTrue(comparator.compare(1, 0) > 0);
			assertEquals(0, comparator.compare(0, 0));
			assertTrue(comparator.compare(-1, 0) < 0);
			assertTrue(comparator.compare(0, -1) > 0);
		}

		@Test
		@DisplayName(
			"should always return the same singleton instance"
		)
		void shouldAlwaysReturnTheSameSingletonInstance() {
			assertSame(
				IntAscendingComparator.INSTANCE,
				IntAscendingComparator.INSTANCE
			);
		}
	}

	@Nested
	@DisplayName("Descending comparator")
	class DescendingComparatorTest {

		private final IntDescendingComparator comparator =
			IntDescendingComparator.INSTANCE;

		@Test
		@DisplayName(
			"should return zero when both values are equal"
		)
		void shouldReturnZeroWhenBothValuesAreEqual() {
			assertEquals(0, comparator.compare(5, 5));
			assertEquals(0, comparator.compare(0, 0));
			assertEquals(0, comparator.compare(-1, -1));
		}

		@Test
		@DisplayName(
			"should return positive when first is less than second"
		)
		void shouldReturnPositiveWhenFirstIsLessThanSecond() {
			// descending: smaller first value means it should
			// come after, so compare returns positive
			assertTrue(comparator.compare(1, 5) > 0);
			assertTrue(comparator.compare(-10, 10) > 0);
			assertTrue(comparator.compare(0, 1) > 0);
		}

		@Test
		@DisplayName(
			"should return negative when first is greater"
			+ " than second"
		)
		void shouldReturnNegativeWhenFirstIsGreaterThanSecond() {
			// descending: larger first value means it should
			// come before, so compare returns negative
			assertTrue(comparator.compare(5, 1) < 0);
			assertTrue(comparator.compare(10, -10) < 0);
			assertTrue(comparator.compare(1, 0) < 0);
		}

		@Test
		@DisplayName(
			"should reverse the order of MIN_VALUE and"
			+ " MAX_VALUE"
		)
		void shouldReverseTheOrderOfMinValueAndMaxValue() {
			assertTrue(
				comparator.compare(
					Integer.MIN_VALUE, Integer.MAX_VALUE
				) > 0
			);
			assertTrue(
				comparator.compare(
					Integer.MAX_VALUE, Integer.MIN_VALUE
				) < 0
			);
		}

		@Test
		@DisplayName(
			"should handle comparisons with zero"
		)
		void shouldHandleComparisonsWithZero() {
			assertTrue(comparator.compare(0, 1) > 0);
			assertTrue(comparator.compare(1, 0) < 0);
			assertEquals(0, comparator.compare(0, 0));
			assertTrue(comparator.compare(-1, 0) > 0);
			assertTrue(comparator.compare(0, -1) < 0);
		}

		@Test
		@DisplayName(
			"should always return the same singleton instance"
		)
		void shouldAlwaysReturnTheSameSingletonInstance() {
			assertSame(
				IntDescendingComparator.INSTANCE,
				IntDescendingComparator.INSTANCE
			);
		}
	}

	@Nested
	@DisplayName("Ascending vs descending symmetry")
	class SymmetryTest {

		@Test
		@DisplayName(
			"should produce opposite signs for same inputs"
		)
		void shouldProduceOppositeSigns() {
			final IntAscendingComparator asc =
				IntAscendingComparator.INSTANCE;
			final IntDescendingComparator desc =
				IntDescendingComparator.INSTANCE;

			final int ascResult = asc.compare(3, 7);
			final int descResult = desc.compare(3, 7);

			// ascending says 3 < 7 (negative)
			// descending says 3 > 7 (positive)
			assertTrue(ascResult < 0);
			assertTrue(descResult > 0);
		}

		@Test
		@DisplayName(
			"should both return zero for equal values"
		)
		void shouldBothReturnZeroForEqualValues() {
			final IntAscendingComparator asc =
				IntAscendingComparator.INSTANCE;
			final IntDescendingComparator desc =
				IntDescendingComparator.INSTANCE;

			assertEquals(0, asc.compare(42, 42));
			assertEquals(0, desc.compare(42, 42));
		}
	}

}
