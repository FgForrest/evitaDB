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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.ConstraintVisitor;
import io.evitadb.api.query.FilterConstraint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Currency;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.QueryConstraints.priceInCurrency;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PriceInCurrency} verifying construction, applicability, property accessors,
 * cloning, visitor support, string representation, and equality contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("PriceInCurrency constraint")
class PriceInCurrencyTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create via factory method with String currency code")
		void shouldCreateViaFactoryWithStringCurrency() {
			final PriceInCurrency constraint = priceInCurrency("CZK");

			assertNotNull(constraint);
			assertEquals(Currency.getInstance("CZK"), constraint.getCurrency());
		}

		@Test
		@DisplayName("should create via factory method with Currency object")
		void shouldCreateViaFactoryWithCurrencyObject() {
			final Currency eur = Currency.getInstance("EUR");
			final PriceInCurrency constraint = priceInCurrency(eur);

			assertNotNull(constraint);
			assertEquals(eur, constraint.getCurrency());
		}

		@Test
		@DisplayName("should return null from String factory when null is passed")
		void shouldReturnNullFromStringFactoryWhenNull() {
			assertNull(priceInCurrency((String) null));
		}

		@Test
		@DisplayName("should return null from Currency factory when null is passed")
		void shouldReturnNullFromCurrencyFactoryWhenNull() {
			assertNull(priceInCurrency((Currency) null));
		}
	}

	@Nested
	@DisplayName("Applicability")
	class ApplicabilityTest {

		@Test
		@DisplayName("should be applicable when constructed with String currency")
		void shouldBeApplicableWithStringCurrency() {
			assertTrue(priceInCurrency("CZK").isApplicable());
		}

		@Test
		@DisplayName("should be applicable when constructed with Currency object")
		void shouldBeApplicableWithCurrencyObject() {
			assertTrue(priceInCurrency(Currency.getInstance("USD")).isApplicable());
		}

		@Test
		@DisplayName("should throw NullPointerException when constructed with null String")
		void shouldThrowWhenConstructedWithNullString() {
			assertThrows(NullPointerException.class, () -> new PriceInCurrency((String) null));
		}
	}

	@Nested
	@DisplayName("Property accessors")
	class PropertyAccessorsTest {

		@Test
		@DisplayName("should return same Currency from String-constructed instance")
		void shouldReturnCurrencyFromStringConstructed() {
			final PriceInCurrency constraint = priceInCurrency("USD");

			assertEquals(Currency.getInstance("USD"), constraint.getCurrency());
		}

		@Test
		@DisplayName("should return same Currency from Currency-constructed instance")
		void shouldReturnCurrencyFromCurrencyConstructed() {
			final Currency gbp = Currency.getInstance("GBP");
			final PriceInCurrency constraint = priceInCurrency(gbp);

			assertEquals(gbp, constraint.getCurrency());
		}

		@Test
		@DisplayName("should return identical Currency result regardless of construction path")
		void shouldReturnIdenticalCurrencyFromBothPaths() {
			final PriceInCurrency fromString = priceInCurrency("EUR");
			final PriceInCurrency fromCurrency = priceInCurrency(Currency.getInstance("EUR"));

			assertEquals(fromString.getCurrency(), fromCurrency.getCurrency());
		}
	}

	@Nested
	@DisplayName("Cloning")
	class CloningTest {

		@Test
		@DisplayName("should produce equal but not same instance via cloneWithArguments")
		void shouldCloneWithArguments() {
			final PriceInCurrency original = priceInCurrency("CZK");
			final FilterConstraint clone = original.cloneWithArguments(new Serializable[]{"CZK"});

			assertEquals(original, clone);
			assertNotSame(original, clone);
			assertInstanceOf(PriceInCurrency.class, clone);
		}
	}

	@Nested
	@DisplayName("Visitor support")
	class VisitorSupportTest {

		@Test
		@DisplayName("should accept visitor and call visit method")
		void shouldAcceptVisitor() {
			final PriceInCurrency constraint = priceInCurrency("CZK");
			final AtomicReference<Constraint<?>> visited = new AtomicReference<>();
			constraint.accept(new ConstraintVisitor() {
				@Override
				public void visit(@Nonnull Constraint<?> constraint) {
					visited.set(constraint);
				}
			});

			assertSame(constraint, visited.get());
		}

		@Test
		@DisplayName("should return FilterConstraint class as type")
		void shouldReturnCorrectType() {
			final PriceInCurrency constraint = priceInCurrency("CZK");

			assertEquals(FilterConstraint.class, constraint.getType());
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce expected format from String-constructed instance")
		void shouldToStringFromStringConstructed() {
			assertEquals("priceInCurrency('CZK')", priceInCurrency("CZK").toString());
		}

		@Test
		@DisplayName("should produce expected format from Currency-constructed instance")
		void shouldToStringFromCurrencyConstructed() {
			assertEquals("priceInCurrency('EUR')", priceInCurrency(Currency.getInstance("EUR")).toString());
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class EqualityTest {

		@Test
		@DisplayName("should conform to equals and hashCode contract for same construction path")
		void shouldConformToEqualsAndHashContract() {
			assertNotSame(priceInCurrency("CZK"), priceInCurrency("CZK"));
			assertEquals(priceInCurrency("CZK"), priceInCurrency("CZK"));
			assertNotEquals(priceInCurrency("CZK"), priceInCurrency("EUR"));
			assertEquals(priceInCurrency("CZK").hashCode(), priceInCurrency("CZK").hashCode());
			assertNotEquals(priceInCurrency("CZK").hashCode(), priceInCurrency("EUR").hashCode());
		}

		@Test
		@DisplayName("should be equal between String-constructed and Currency-constructed instances")
		void shouldBeEqualBetweenStringAndCurrencyConstructed() {
			final PriceInCurrency fromString = priceInCurrency("CZK");
			final PriceInCurrency fromCurrency = priceInCurrency(Currency.getInstance("CZK"));

			assertEquals(fromString, fromCurrency);
			assertEquals(fromString.hashCode(), fromCurrency.hashCode());
		}

		@Test
		@DisplayName("should have equal Currency-constructed instances")
		void shouldHaveEqualCurrencyConstructedInstances() {
			final PriceInCurrency first = priceInCurrency(Currency.getInstance("EUR"));
			final PriceInCurrency second = priceInCurrency(Currency.getInstance("EUR"));

			assertEquals(first, second);
			assertEquals(first.hashCode(), second.hashCode());
		}
	}
}
