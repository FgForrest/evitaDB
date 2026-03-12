/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api.requestResponse.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Cardinality} enum verifying minimum/maximum bounds,
 * duplicate allowance rules, and enum completeness.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Cardinality enum")
class CardinalityTest {

	@Nested
	@DisplayName("Minimum and maximum bounds")
	class MinimumAndMaximumBoundsTest {

		@Test
		@DisplayName("ZERO_OR_ONE has min=0 and max=1")
		void shouldHaveCorrectBoundsForZeroOrOne() {
			assertEquals(0, Cardinality.ZERO_OR_ONE.getMin());
			assertEquals(1, Cardinality.ZERO_OR_ONE.getMax());
		}

		@Test
		@DisplayName("EXACTLY_ONE has min=1 and max=1")
		void shouldHaveCorrectBoundsForExactlyOne() {
			assertEquals(1, Cardinality.EXACTLY_ONE.getMin());
			assertEquals(1, Cardinality.EXACTLY_ONE.getMax());
		}

		@Test
		@DisplayName("ZERO_OR_MORE has min=0 and max=MAX_VALUE")
		void shouldHaveCorrectBoundsForZeroOrMore() {
			assertEquals(0, Cardinality.ZERO_OR_MORE.getMin());
			assertEquals(Integer.MAX_VALUE, Cardinality.ZERO_OR_MORE.getMax());
		}

		@Test
		@DisplayName("ONE_OR_MORE has min=1 and max=MAX_VALUE")
		void shouldHaveCorrectBoundsForOneOrMore() {
			assertEquals(1, Cardinality.ONE_OR_MORE.getMin());
			assertEquals(Integer.MAX_VALUE, Cardinality.ONE_OR_MORE.getMax());
		}

		@Test
		@DisplayName("ZERO_OR_MORE_WITH_DUPLICATES has min=0 and max=MAX_VALUE")
		void shouldHaveCorrectBoundsForZeroOrMoreWithDuplicates() {
			assertEquals(0, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES.getMin());
			assertEquals(Integer.MAX_VALUE, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES.getMax());
		}

		@Test
		@DisplayName("ONE_OR_MORE_WITH_DUPLICATES has min=1 and max=MAX_VALUE")
		void shouldHaveCorrectBoundsForOneOrMoreWithDuplicates() {
			assertEquals(1, Cardinality.ONE_OR_MORE_WITH_DUPLICATES.getMin());
			assertEquals(Integer.MAX_VALUE, Cardinality.ONE_OR_MORE_WITH_DUPLICATES.getMax());
		}
	}

	@Nested
	@DisplayName("Duplicate allowance")
	class DuplicateAllowanceTest {

		@Test
		@DisplayName("ZERO_OR_ONE does not allow duplicates")
		void shouldNotAllowDuplicatesForZeroOrOne() {
			assertFalse(Cardinality.ZERO_OR_ONE.allowsDuplicates());
		}

		@Test
		@DisplayName("EXACTLY_ONE does not allow duplicates")
		void shouldNotAllowDuplicatesForExactlyOne() {
			assertFalse(Cardinality.EXACTLY_ONE.allowsDuplicates());
		}

		@Test
		@DisplayName("ZERO_OR_MORE does not allow duplicates")
		void shouldNotAllowDuplicatesForZeroOrMore() {
			assertFalse(Cardinality.ZERO_OR_MORE.allowsDuplicates());
		}

		@Test
		@DisplayName("ONE_OR_MORE does not allow duplicates")
		void shouldNotAllowDuplicatesForOneOrMore() {
			assertFalse(Cardinality.ONE_OR_MORE.allowsDuplicates());
		}

		@Test
		@DisplayName("ZERO_OR_MORE_WITH_DUPLICATES allows duplicates")
		void shouldAllowDuplicatesForZeroOrMoreWithDuplicates() {
			assertTrue(Cardinality.ZERO_OR_MORE_WITH_DUPLICATES.allowsDuplicates());
		}

		@Test
		@DisplayName("ONE_OR_MORE_WITH_DUPLICATES allows duplicates")
		void shouldAllowDuplicatesForOneOrMoreWithDuplicates() {
			assertTrue(Cardinality.ONE_OR_MORE_WITH_DUPLICATES.allowsDuplicates());
		}
	}

	@Nested
	@DisplayName("Enum completeness")
	class EnumCompletenessTest {

		@Test
		@DisplayName("contains exactly 6 values")
		void shouldContainExactlySixValues() {
			assertEquals(6, Cardinality.values().length);
		}

		@Test
		@DisplayName("values are in the expected order")
		void shouldHaveValuesInExpectedOrder() {
			final Cardinality[] expectedOrder = {
				Cardinality.ZERO_OR_ONE,
				Cardinality.EXACTLY_ONE,
				Cardinality.ZERO_OR_MORE,
				Cardinality.ONE_OR_MORE,
				Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				Cardinality.ONE_OR_MORE_WITH_DUPLICATES
			};

			assertArrayEquals(expectedOrder, Cardinality.values());
		}

		@Test
		@DisplayName("valueOf resolves all enum names correctly")
		void shouldResolveAllEnumNamesByValueOf() {
			assertEquals(Cardinality.ZERO_OR_ONE, Cardinality.valueOf("ZERO_OR_ONE"));
			assertEquals(Cardinality.EXACTLY_ONE, Cardinality.valueOf("EXACTLY_ONE"));
			assertEquals(Cardinality.ZERO_OR_MORE, Cardinality.valueOf("ZERO_OR_MORE"));
			assertEquals(Cardinality.ONE_OR_MORE, Cardinality.valueOf("ONE_OR_MORE"));
			assertEquals(
				Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				Cardinality.valueOf("ZERO_OR_MORE_WITH_DUPLICATES")
			);
			assertEquals(
				Cardinality.ONE_OR_MORE_WITH_DUPLICATES,
				Cardinality.valueOf("ONE_OR_MORE_WITH_DUPLICATES")
			);
		}
	}
}
