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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link OrderBehaviour} enum verifying the two null-ordering
 * behaviour values and enum completeness.
 *
 * @author evitaDB
 */
@DisplayName("OrderBehaviour enum")
class OrderBehaviourTest {

	@Nested
	@DisplayName("Enum completeness")
	class EnumCompletenessTest {

		@Test
		@DisplayName("contains exactly 2 values")
		void shouldContainExactlyTwoValues() {
			assertEquals(2, OrderBehaviour.values().length);
		}

		@Test
		@DisplayName("values are NULLS_FIRST then NULLS_LAST")
		void shouldHaveValuesInExpectedOrder() {
			final OrderBehaviour[] expectedOrder = {
				OrderBehaviour.NULLS_FIRST,
				OrderBehaviour.NULLS_LAST
			};

			assertArrayEquals(expectedOrder, OrderBehaviour.values());
		}
	}

	@Nested
	@DisplayName("Value resolution")
	class ValueResolutionTest {

		@Test
		@DisplayName("valueOf resolves NULLS_FIRST")
		void shouldResolveNullsFirst() {
			assertEquals(
				OrderBehaviour.NULLS_FIRST,
				OrderBehaviour.valueOf("NULLS_FIRST")
			);
		}

		@Test
		@DisplayName("valueOf resolves NULLS_LAST")
		void shouldResolveNullsLast() {
			assertEquals(
				OrderBehaviour.NULLS_LAST,
				OrderBehaviour.valueOf("NULLS_LAST")
			);
		}

		@Test
		@DisplayName("valueOf throws for unknown name")
		void shouldThrowForUnknownName() {
			assertThrows(
				IllegalArgumentException.class,
				() -> OrderBehaviour.valueOf("NONEXISTENT")
			);
		}

		@Test
		@DisplayName("NULLS_FIRST has ordinal 0 and NULLS_LAST has ordinal 1")
		void shouldHaveCorrectOrdinals() {
			assertEquals(0, OrderBehaviour.NULLS_FIRST.ordinal());
			assertEquals(1, OrderBehaviour.NULLS_LAST.ordinal());
		}
	}
}
