/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.dataType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.Currency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ComparableCurrency} verifying comparison,
 * equality, toString, and serialization behavior.
 *
 * @author evitaDB
 */
@DisplayName("ComparableCurrency functionality")
class ComparableCurrencyTest {

	@Nested
	@DisplayName("Comparison")
	class ComparisonTest {

		@Test
		@DisplayName("should order alphabetically by currency code")
		void shouldOrderAlphabeticallyByCurrencyCode() {
			final ComparableCurrency czk =
				new ComparableCurrency(Currency.getInstance("CZK"));
			final ComparableCurrency eur =
				new ComparableCurrency(Currency.getInstance("EUR"));
			final ComparableCurrency usd =
				new ComparableCurrency(Currency.getInstance("USD"));

			assertTrue(czk.compareTo(eur) < 0);
			assertTrue(eur.compareTo(usd) < 0);
			assertTrue(czk.compareTo(usd) < 0);
		}

		@Test
		@DisplayName(
			"should return negative when first currency " +
				"precedes second alphabetically"
		)
		void shouldReturnNegativeWhenFirstPrecedesSecond() {
			final ComparableCurrency czk =
				new ComparableCurrency(Currency.getInstance("CZK"));
			final ComparableCurrency usd =
				new ComparableCurrency(Currency.getInstance("USD"));

			assertTrue(czk.compareTo(usd) < 0);
		}

		@Test
		@DisplayName(
			"should return positive when first currency " +
				"follows second alphabetically"
		)
		void shouldReturnPositiveWhenFirstFollowsSecond() {
			final ComparableCurrency usd =
				new ComparableCurrency(Currency.getInstance("USD"));
			final ComparableCurrency czk =
				new ComparableCurrency(Currency.getInstance("CZK"));

			assertTrue(usd.compareTo(czk) > 0);
		}

		@Test
		@DisplayName(
			"should return zero when comparing " +
				"equal currency codes"
		)
		void shouldReturnZeroForEqualCurrencies() {
			final ComparableCurrency czk1 =
				new ComparableCurrency(Currency.getInstance("CZK"));
			final ComparableCurrency czk2 =
				new ComparableCurrency(Currency.getInstance("CZK"));

			assertEquals(0, czk1.compareTo(czk2));
		}

		@Test
		@DisplayName("should sort array correctly")
		void shouldSortArrayCorrectly() {
			final ComparableCurrency usd =
				new ComparableCurrency(Currency.getInstance("USD"));
			final ComparableCurrency czk =
				new ComparableCurrency(Currency.getInstance("CZK"));
			final ComparableCurrency eur =
				new ComparableCurrency(Currency.getInstance("EUR"));

			final ComparableCurrency[] array = {usd, czk, eur};
			Arrays.sort(array);

			assertEquals("CZK", array[0].toString());
			assertEquals("EUR", array[1].toString());
			assertEquals("USD", array[2].toString());
		}
	}

	@Nested
	@DisplayName("Equality")
	class EqualityTest {

		@Test
		@DisplayName("should be reflexive")
		void shouldBeReflexive() {
			final ComparableCurrency czk =
				new ComparableCurrency(Currency.getInstance("CZK"));

			assertEquals(czk, czk);
		}

		@Test
		@DisplayName("should be symmetric")
		void shouldBeSymmetric() {
			final ComparableCurrency czk1 =
				new ComparableCurrency(Currency.getInstance("CZK"));
			final ComparableCurrency czk2 =
				new ComparableCurrency(Currency.getInstance("CZK"));

			assertEquals(czk1, czk2);
			assertEquals(czk2, czk1);
		}

		@Test
		@DisplayName("should be transitive")
		void shouldBeTransitive() {
			final ComparableCurrency czk1 =
				new ComparableCurrency(Currency.getInstance("CZK"));
			final ComparableCurrency czk2 =
				new ComparableCurrency(Currency.getInstance("CZK"));
			final ComparableCurrency czk3 =
				new ComparableCurrency(Currency.getInstance("CZK"));

			assertEquals(czk1, czk2);
			assertEquals(czk2, czk3);
			assertEquals(czk1, czk3);
		}

		@Test
		@DisplayName("should not be equal to null")
		void shouldNotBeEqualToNull() {
			final ComparableCurrency czk =
				new ComparableCurrency(Currency.getInstance("CZK"));

			assertNotEquals(null, czk);
		}

		@Test
		@DisplayName("should not be equal to different currency")
		void shouldNotBeEqualToDifferentCurrency() {
			final ComparableCurrency czk =
				new ComparableCurrency(Currency.getInstance("CZK"));
			final ComparableCurrency eur =
				new ComparableCurrency(Currency.getInstance("EUR"));

			assertNotEquals(czk, eur);
		}

		@Test
		@DisplayName(
			"should produce consistent hash codes " +
				"for equal instances"
		)
		void shouldProduceConsistentHashCodes() {
			final ComparableCurrency czk1 =
				new ComparableCurrency(Currency.getInstance("CZK"));
			final ComparableCurrency czk2 =
				new ComparableCurrency(Currency.getInstance("CZK"));

			assertEquals(czk1.hashCode(), czk2.hashCode());
		}

		@Test
		@DisplayName(
			"should not be equal to object of different type"
		)
		void shouldNotBeEqualToDifferentType() {
			final ComparableCurrency czk =
				new ComparableCurrency(Currency.getInstance("CZK"));

			assertNotEquals("CZK", czk);
		}
	}

	@Nested
	@DisplayName("ToString")
	class ToStringTest {

		@Test
		@DisplayName("should return currency code")
		void shouldReturnCurrencyCode() {
			final ComparableCurrency czk =
				new ComparableCurrency(Currency.getInstance("CZK"));

			assertEquals("CZK", czk.toString());
		}

		@Test
		@DisplayName("should return EUR for euro currency")
		void shouldReturnEurForEuroCurrency() {
			final ComparableCurrency eur =
				new ComparableCurrency(Currency.getInstance("EUR"));

			assertEquals("EUR", eur.toString());
		}
	}

	@Nested
	@DisplayName("Serialization")
	class SerializationTest {

		@Test
		@DisplayName(
			"should survive Java serialization round-trip"
		)
		void shouldSurviveSerializationRoundTrip()
			throws Exception {
			final ComparableCurrency original =
				new ComparableCurrency(Currency.getInstance("CZK"));

			final ByteArrayOutputStream baos =
				new ByteArrayOutputStream();
			try (final ObjectOutputStream oos =
				     new ObjectOutputStream(baos)) {
				oos.writeObject(original);
			}

			final ByteArrayInputStream bais =
				new ByteArrayInputStream(baos.toByteArray());
			try (final ObjectInputStream ois =
				     new ObjectInputStream(bais)) {
				final ComparableCurrency deserialized =
					(ComparableCurrency) ois.readObject();

				assertEquals(original, deserialized);
				assertEquals(
					original.toString(),
					deserialized.toString()
				);
			}
		}
	}

	@Nested
	@DisplayName("Constructor edge cases")
	class ConstructorEdgeCaseTest {

		@Test
		@DisplayName(
			"should reject null currency at construction time"
		)
		void shouldRejectNullCurrencyAtConstructionTime() {
			// @Nonnull on the field causes Lombok to generate
			// a null-check in the constructor
			assertThrows(
				NullPointerException.class,
				() -> new ComparableCurrency(null)
			);
		}

		@Test
		@DisplayName("should expose currency via getter")
		void shouldExposeCurrencyViaGetter() {
			final Currency eurCurrency =
				Currency.getInstance("EUR");
			final ComparableCurrency comparable =
				new ComparableCurrency(eurCurrency);

			assertSame(eurCurrency, comparable.getCurrency());
		}
	}
}
