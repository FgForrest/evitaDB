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

package io.evitadb.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.Currency;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test verifies contract of {@link ComparatorUtils} class.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ComparatorUtils contract tests")
class ComparatorUtilsTest {

	@Nested
	@DisplayName("Locale comparison tests")
	class LocaleComparisonTests {

		@Test
		@DisplayName("Should return zero when both locales are null")
		void shouldReturnZeroWhenBothNull() {
			final int result = ComparatorUtils.compareLocale(null, null, () -> 0);
			assertEquals(0, result);
		}

		@Test
		@DisplayName("Should return negative when first locale is non-null and second is null")
		void shouldReturnNegativeWhenFirstNonNullSecondNull() {
			final int result = ComparatorUtils.compareLocale(Locale.ENGLISH, null, () -> 0);
			assertEquals(-1, result);
		}

		@Test
		@DisplayName("Should return positive when first locale is null and second is non-null")
		void shouldReturnPositiveWhenFirstNullSecondNonNull() {
			final int result = ComparatorUtils.compareLocale(null, Locale.ENGLISH, () -> 0);
			assertEquals(1, result);
		}

		@Test
		@DisplayName("Should compare non-null locales")
		void shouldCompareNonNullLocales() {
			final int result1 = ComparatorUtils.compareLocale(Locale.ENGLISH, Locale.FRENCH, () -> 0);
			final int result2 = ComparatorUtils.compareLocale(Locale.FRENCH, Locale.ENGLISH, () -> 0);
			final int result3 = ComparatorUtils.compareLocale(Locale.ENGLISH, Locale.ENGLISH, () -> 0);

			assertTrue(result1 < 0, "en should be less than fr");
			assertTrue(result2 > 0, "fr should be greater than en");
			assertEquals(0, result3, "en should be equal to en");
		}

		@Test
		@DisplayName("Should use tie breaker when locales are equal")
		void shouldUseTieBreaker() {
			final int result = ComparatorUtils.compareLocale(Locale.ENGLISH, Locale.ENGLISH, () -> 42);
			assertEquals(42, result);
		}

		@Test
		@DisplayName("Should not use tie breaker when locales are different")
		void shouldNotUseTieBreakerWhenDifferent() {
			final int result = ComparatorUtils.compareLocale(Locale.ENGLISH, Locale.FRENCH, () -> 999);
			assertTrue(result < 0, "Should use locale comparison result, not tie breaker");
		}

		@Test
		@DisplayName("Should use tie breaker when both locales are null")
		void shouldUseTieBreakerWhenBothNull() {
			final int result = ComparatorUtils.compareLocale(null, null, () -> 100);
			assertEquals(100, result);
		}

		@Test
		@DisplayName("Should create locale comparator")
		void shouldCreateLocaleComparator() {
			final Comparator<Locale> comparator = ComparatorUtils.localeComparator();
			assertTrue(comparator.compare(Locale.ENGLISH, Locale.FRENCH) < 0);
			assertTrue(comparator.compare(Locale.FRENCH, Locale.ENGLISH) > 0);
			assertEquals(0, comparator.compare(Locale.ENGLISH, Locale.ENGLISH));
		}
	}

	@Nested
	@DisplayName("Currency comparison tests")
	class CurrencyComparisonTests {

		@Test
		@DisplayName("Should return zero when both currencies are null")
		void shouldReturnZeroWhenBothNull() {
			final int result = ComparatorUtils.compareCurrency(null, null, () -> 0);
			assertEquals(0, result);
		}

		@Test
		@DisplayName("Should return negative when first currency is non-null and second is null")
		void shouldReturnNegativeWhenFirstNonNullSecondNull() {
			final int result = ComparatorUtils.compareCurrency(Currency.getInstance("USD"), null, () -> 0);
			assertEquals(-1, result);
		}

		@Test
		@DisplayName("Should return positive when first currency is null and second is non-null")
		void shouldReturnPositiveWhenFirstNullSecondNonNull() {
			final int result = ComparatorUtils.compareCurrency(null, Currency.getInstance("USD"), () -> 0);
			assertEquals(1, result);
		}

		@Test
		@DisplayName("Should compare currencies by code")
		void shouldCompareCurrenciesByCode() {
			final Currency eur = Currency.getInstance("EUR");
			final Currency usd = Currency.getInstance("USD");
			final Currency gbp = Currency.getInstance("GBP");

			final int eurVsUsd = ComparatorUtils.compareCurrency(eur, usd, () -> 0);
			final int usdVsEur = ComparatorUtils.compareCurrency(usd, eur, () -> 0);
			final int eurVsEur = ComparatorUtils.compareCurrency(eur, eur, () -> 0);

			assertTrue(eurVsUsd < 0, "EUR should be less than USD");
			assertTrue(usdVsEur > 0, "USD should be greater than EUR");
			assertEquals(0, eurVsEur, "EUR should be equal to EUR");

			// GBP vs others
			assertTrue(ComparatorUtils.compareCurrency(gbp, usd, () -> 0) < 0, "GBP should be less than USD");
			assertTrue(ComparatorUtils.compareCurrency(gbp, eur, () -> 0) > 0, "GBP should be greater than EUR");
		}

		@Test
		@DisplayName("Should use tie breaker when currencies are equal")
		void shouldUseTieBreaker() {
			final Currency usd = Currency.getInstance("USD");
			final int result = ComparatorUtils.compareCurrency(usd, usd, () -> 42);
			assertEquals(42, result);
		}

		@Test
		@DisplayName("Should not use tie breaker when currencies are different")
		void shouldNotUseTieBreakerWhenDifferent() {
			final Currency eur = Currency.getInstance("EUR");
			final Currency usd = Currency.getInstance("USD");
			final int result = ComparatorUtils.compareCurrency(eur, usd, () -> 999);
			assertTrue(result < 0, "Should use currency comparison result, not tie breaker");
		}

		@Test
		@DisplayName("Should use tie breaker when both currencies are null")
		void shouldUseTieBreakerWhenBothNull() {
			final int result = ComparatorUtils.compareCurrency(null, null, () -> 100);
			assertEquals(100, result);
		}

		@Test
		@DisplayName("Should create currency comparator")
		void shouldCreateCurrencyComparator() {
			final Comparator<Currency> comparator = ComparatorUtils.currencyComparator();
			final Currency eur = Currency.getInstance("EUR");
			final Currency usd = Currency.getInstance("USD");

			assertTrue(comparator.compare(eur, usd) < 0);
			assertTrue(comparator.compare(usd, eur) > 0);
			assertEquals(0, comparator.compare(eur, eur));
		}
	}
}
