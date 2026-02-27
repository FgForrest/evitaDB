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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link NullsLastComparatorWrapper} verifying that
 * null values are always sorted after non-null values while
 * delegating non-null comparison to the wrapped comparator.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("NullsLastComparatorWrapper")
class NullsLastComparatorWrapperTest {

	private final NullsLastComparatorWrapper<String> comparator =
		new NullsLastComparatorWrapper<String>(
			Comparator.naturalOrder()
		);

	@Nested
	@DisplayName("Null handling")
	class NullHandlingTest {

		@Test
		@DisplayName(
			"should return positive when first is null"
			+ " and second is not"
		)
		void shouldReturnPositiveWhenFirstIsNull() {
			assertTrue(NullsLastComparatorWrapperTest.this.comparator.compare(null, "A") > 0);
		}

		@Test
		@DisplayName(
			"should return negative when second is null"
			+ " and first is not"
		)
		void shouldReturnNegativeWhenSecondIsNull() {
			assertTrue(NullsLastComparatorWrapperTest.this.comparator.compare("A", null) < 0);
		}

		@Test
		@DisplayName(
			"should return zero when both are null"
		)
		void shouldMarkBothNullElementsAsEqual() {
			assertEquals(0, NullsLastComparatorWrapperTest.this.comparator.compare(null, null));
		}

		@Test
		@DisplayName(
			"should be anti-symmetric for null vs non-null"
		)
		void shouldBeAntiSymmetricForNullVsNonNull() {
			final int nullFirst =
				NullsLastComparatorWrapperTest.this.comparator.compare(null, "A");
			final int nullSecond =
				NullsLastComparatorWrapperTest.this.comparator.compare("A", null);

			// signs must be opposite
			assertTrue(nullFirst > 0);
			assertTrue(nullSecond < 0);
		}
	}

	@Nested
	@DisplayName("Delegation to wrapped comparator")
	class DelegationTest {

		@Test
		@DisplayName(
			"should delegate to natural order for"
			+ " non-null values"
		)
		void shouldDelegateToNaturalOrderForNonNullValues() {
			assertTrue(NullsLastComparatorWrapperTest.this.comparator.compare("A", "B") < 0);
			assertTrue(NullsLastComparatorWrapperTest.this.comparator.compare("B", "A") > 0);
			assertEquals(0, NullsLastComparatorWrapperTest.this.comparator.compare("A", "A"));
		}

		@Test
		@DisplayName(
			"should delegate to reverse order comparator"
		)
		void shouldDelegateToReverseOrderComparator() {
			final NullsLastComparatorWrapper<String>
				reverseComparator =
				new NullsLastComparatorWrapper<String>(
					Comparator.reverseOrder()
				);

			// with reversed delegate: "A" > "B"
			assertTrue(reverseComparator.compare("A", "B") > 0);
			assertTrue(reverseComparator.compare("B", "A") < 0);

			// null handling unchanged
			assertTrue(
				reverseComparator.compare(null, "A") > 0
			);
		}
	}

	@Nested
	@DisplayName("Array sorting")
	class ArraySortingTest {

		@Test
		@DisplayName(
			"should sort nulls at the end of array"
		)
		void shouldSortNullsAtTheEnd() {
			final String[] arrayToSort =
				{"D", "B", null, "Z", "A"};

			Arrays.sort(arrayToSort, NullsLastComparatorWrapperTest.this.comparator);

			assertArrayEquals(
				new String[]{"A", "B", "D", "Z", null},
				arrayToSort
			);
		}

		@Test
		@DisplayName(
			"should sort multiple nulls at the end"
		)
		void shouldSortMultipleNullsAtTheEnd() {
			final String[] arrayToSort =
				{null, "C", null, "A", null};

			Arrays.sort(arrayToSort, NullsLastComparatorWrapperTest.this.comparator);

			assertArrayEquals(
				new String[]{"A", "C", null, null, null},
				arrayToSort
			);
		}

		@Test
		@DisplayName(
			"should handle array with all null elements"
		)
		void shouldHandleArrayWithAllNullElements() {
			final String[] arrayToSort =
				{null, null, null};

			Arrays.sort(arrayToSort, NullsLastComparatorWrapperTest.this.comparator);

			assertArrayEquals(
				new String[]{null, null, null},
				arrayToSort
			);
		}

		@Test
		@DisplayName(
			"should handle single-element array"
		)
		void shouldHandleSingleElementArray() {
			final String[] arrayToSort = {"only"};

			Arrays.sort(arrayToSort, NullsLastComparatorWrapperTest.this.comparator);

			assertArrayEquals(
				new String[]{"only"},
				arrayToSort
			);
		}

		@Test
		@DisplayName(
			"should handle single null element array"
		)
		void shouldHandleSingleNullElementArray() {
			final String[] arrayToSort = {null};

			Arrays.sort(arrayToSort, NullsLastComparatorWrapperTest.this.comparator);

			assertArrayEquals(
				new String[]{null},
				arrayToSort
			);
		}

		@Test
		@DisplayName(
			"should handle array with no nulls"
		)
		void shouldHandleArrayWithNoNulls() {
			final String[] arrayToSort = {"C", "A", "B"};

			Arrays.sort(arrayToSort, NullsLastComparatorWrapperTest.this.comparator);

			assertArrayEquals(
				new String[]{"A", "B", "C"},
				arrayToSort
			);
		}
	}

}
