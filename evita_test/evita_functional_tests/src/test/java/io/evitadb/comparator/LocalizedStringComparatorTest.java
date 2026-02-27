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

import java.text.Collator;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LocalizedStringComparator} verifying
 * locale-aware string comparison behaviour including national
 * character ordering.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("LocalizedStringComparator")
class LocalizedStringComparatorTest {

	@Nested
	@DisplayName("National character ordering")
	class NationalCharacterOrderingTest {

		@Test
		@DisplayName(
			"should sort Czech 'Ch' after 'C' but before 'D'"
		)
		void shouldSortCzechChAfterCButBeforeD() {
			final LocalizedStringComparator czechComparator =
				new LocalizedStringComparator(
					Collator.getInstance(new Locale("cs"))
				);

			// in Czech, "Ch" is a digraph sorted after "H"
			// so "Chladnicka" > "Citron"
			assertTrue(
				czechComparator.compare(
					"Chladnička", "Citrón"
				) > 0
			);
		}

		@Test
		@DisplayName(
			"should sort English 'Ch' before 'Ci'"
		)
		void shouldSortEnglishChBeforeCi() {
			final LocalizedStringComparator englishComparator =
				new LocalizedStringComparator(
					Collator.getInstance(Locale.ENGLISH)
				);

			// in English, "Ch" < "Ci" by standard ordering
			assertTrue(
				englishComparator.compare(
					"Chladnička", "Citrón"
				) < 0
			);
		}

		@Test
		@DisplayName(
			"should order Czech vs English differently"
			+ " for national characters"
		)
		void shouldCorrectlySortNationalCharacters() {
			final LocalizedStringComparator czechComparator =
				new LocalizedStringComparator(
					Collator.getInstance(new Locale("cs"))
				);
			final LocalizedStringComparator englishComparator =
				new LocalizedStringComparator(
					Collator.getInstance(Locale.ENGLISH)
				);

			assertTrue(
				czechComparator.compare(
					"Chladnička", "Citrón"
				) > 0
			);
			assertTrue(
				englishComparator.compare(
					"Chladnička", "Citrón"
				) < 0
			);
		}

		@Test
		@DisplayName(
			"should sort German umlauts correctly"
		)
		void shouldSortGermanUmlautsCorrectly() {
			final LocalizedStringComparator germanComparator =
				new LocalizedStringComparator(Locale.GERMAN);

			// "ä" should be treated near "a" in German
			final String[] array = {"Bär", "Aal", "Zug"};
			Arrays.sort(array, germanComparator);

			assertArrayEquals(
				new String[]{"Aal", "Bär", "Zug"},
				array
			);
		}
	}

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName(
			"should create comparator from Locale"
		)
		void shouldCreateComparatorFromLocale() {
			final LocalizedStringComparator comparator =
				new LocalizedStringComparator(Locale.ENGLISH);

			// verify it works by comparing simple strings
			assertTrue(comparator.compare("apple", "banana") < 0);
			assertTrue(comparator.compare("banana", "apple") > 0);
		}

		@Test
		@DisplayName(
			"should create comparator from Collator"
		)
		void shouldCreateComparatorFromCollator() {
			final Collator collator =
				Collator.getInstance(Locale.ENGLISH);
			final LocalizedStringComparator comparator =
				new LocalizedStringComparator(collator);

			assertTrue(comparator.compare("apple", "banana") < 0);
			assertTrue(comparator.compare("banana", "apple") > 0);
		}
	}

	@Nested
	@DisplayName("Basic comparison")
	class BasicComparisonTest {

		private final LocalizedStringComparator comparator =
			new LocalizedStringComparator(Locale.ENGLISH);

		@Test
		@DisplayName(
			"should return zero for identical strings"
		)
		void shouldReturnZeroForIdenticalStrings() {
			assertEquals(0, this.comparator.compare("hello", "hello"));
		}

		@Test
		@DisplayName(
			"should return zero for both empty strings"
		)
		void shouldReturnZeroForBothEmptyStrings() {
			assertEquals(0, this.comparator.compare("", ""));
		}

		@Test
		@DisplayName(
			"should sort empty string before non-empty"
		)
		void shouldSortEmptyStringBeforeNonEmpty() {
			assertTrue(this.comparator.compare("", "a") < 0);
			assertTrue(this.comparator.compare("a", "") > 0);
		}

		@Test
		@DisplayName(
			"should be case-aware by default"
		)
		void shouldBeCaseAwareByDefault() {
			// Collator default strength is TERTIARY,
			// which distinguishes case
			final int result =
				this.comparator.compare("Apple", "apple");

			// uppercase 'A' typically sorts before lowercase 'a'
			// in English locale with default Collator strength
			assertTrue(result != 0);
		}
	}

	@Nested
	@DisplayName("Sorting arrays")
	class SortingArraysTest {

		@Test
		@DisplayName(
			"should sort English strings in alphabetical order"
		)
		void shouldSortEnglishStringsInAlphabeticalOrder() {
			final LocalizedStringComparator comparator =
				new LocalizedStringComparator(Locale.ENGLISH);
			final String[] array =
				{"delta", "alpha", "charlie", "bravo"};

			Arrays.sort(array, comparator);

			assertArrayEquals(
				new String[]{
					"alpha", "bravo", "charlie", "delta"
				},
				array
			);
		}

		@Test
		@DisplayName(
			"should sort single-element array without error"
		)
		void shouldSortSingleElementArray() {
			final LocalizedStringComparator comparator =
				new LocalizedStringComparator(Locale.ENGLISH);
			final String[] array = {"solo"};

			Arrays.sort(array, comparator);

			assertArrayEquals(new String[]{"solo"}, array);
		}
	}

}
