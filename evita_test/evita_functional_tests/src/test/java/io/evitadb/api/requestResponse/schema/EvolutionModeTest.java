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
 * Tests for {@link EvolutionMode} enum verifying all 9 evolution mode values
 * and enum completeness.
 *
 * @author evitaDB
 */
@DisplayName("EvolutionMode enum")
class EvolutionModeTest {

	@Nested
	@DisplayName("Enum completeness")
	class EnumCompletenessTest {

		@Test
		@DisplayName("contains exactly 9 values")
		void shouldContainExactlyNineValues() {
			assertEquals(9, EvolutionMode.values().length);
		}

		@Test
		@DisplayName("values are in the expected order")
		void shouldHaveValuesInExpectedOrder() {
			final EvolutionMode[] expectedOrder = {
				EvolutionMode.ADAPT_PRIMARY_KEY_GENERATION,
				EvolutionMode.ADDING_ATTRIBUTES,
				EvolutionMode.ADDING_ASSOCIATED_DATA,
				EvolutionMode.ADDING_REFERENCES,
				EvolutionMode.ADDING_PRICES,
				EvolutionMode.ADDING_LOCALES,
				EvolutionMode.ADDING_CURRENCIES,
				EvolutionMode.ADDING_HIERARCHY,
				EvolutionMode.UPDATING_REFERENCE_CARDINALITY
			};

			assertArrayEquals(expectedOrder, EvolutionMode.values());
		}
	}

	@Nested
	@DisplayName("Value resolution")
	class ValueResolutionTest {

		@Test
		@DisplayName("valueOf resolves all enum names correctly")
		void shouldResolveAllEnumNamesByValueOf() {
			assertEquals(
				EvolutionMode.ADAPT_PRIMARY_KEY_GENERATION,
				EvolutionMode.valueOf("ADAPT_PRIMARY_KEY_GENERATION")
			);
			assertEquals(
				EvolutionMode.ADDING_ATTRIBUTES,
				EvolutionMode.valueOf("ADDING_ATTRIBUTES")
			);
			assertEquals(
				EvolutionMode.ADDING_ASSOCIATED_DATA,
				EvolutionMode.valueOf("ADDING_ASSOCIATED_DATA")
			);
			assertEquals(
				EvolutionMode.ADDING_REFERENCES,
				EvolutionMode.valueOf("ADDING_REFERENCES")
			);
			assertEquals(
				EvolutionMode.ADDING_PRICES,
				EvolutionMode.valueOf("ADDING_PRICES")
			);
			assertEquals(
				EvolutionMode.ADDING_LOCALES,
				EvolutionMode.valueOf("ADDING_LOCALES")
			);
			assertEquals(
				EvolutionMode.ADDING_CURRENCIES,
				EvolutionMode.valueOf("ADDING_CURRENCIES")
			);
			assertEquals(
				EvolutionMode.ADDING_HIERARCHY,
				EvolutionMode.valueOf("ADDING_HIERARCHY")
			);
			assertEquals(
				EvolutionMode.UPDATING_REFERENCE_CARDINALITY,
				EvolutionMode.valueOf("UPDATING_REFERENCE_CARDINALITY")
			);
		}

		@Test
		@DisplayName("valueOf throws for unknown name")
		void shouldThrowForUnknownName() {
			assertThrows(
				IllegalArgumentException.class,
				() -> EvolutionMode.valueOf("NONEXISTENT")
			);
		}

		@Test
		@DisplayName("ordinal values are sequential starting from zero")
		void shouldHaveSequentialOrdinals() {
			final EvolutionMode[] values = EvolutionMode.values();
			for (int i = 0; i < values.length; i++) {
				assertEquals(i, values[i].ordinal());
			}
		}
	}
}
