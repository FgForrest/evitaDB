/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.query;

import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.filter.AttributeInRange;
import io.evitadb.api.query.head.Collection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BaseConstraint} - the abstract base class for all evitaDB query constraints.
 * Uses concrete constraint subclasses ({@link Collection}, {@link AttributeEquals}) to test base behavior.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("BaseConstraint")
class BaseConstraintTest {

	@Nested
	@DisplayName("Name derivation")
	class NameDerivation {

		@Test
		@DisplayName("should derive default name from class name")
		void shouldDeriveDefaultNameFromClassName() {
			final Collection constraint = collection("product");
			assertEquals("collection", constraint.getName());
		}

		@Test
		@DisplayName("should derive name for AttributeEquals")
		void shouldDeriveNameForAttributeEquals() {
			final AttributeEquals constraint = attributeEquals("code", "abc");
			assertEquals("attributeEquals", constraint.getName());
		}

		@Test
		@DisplayName("should append suffix for ConstraintWithSuffix")
		void shouldAppendSuffixForConstraintWithSuffix() {
			// AttributeInRange with OffsetDateTime triggers the "now" suffix
			final AttributeInRange constraint = attributeInRangeNow("validity");
			assertEquals("attributeInRangeNow", constraint.getName());
		}

		@Test
		@DisplayName("should not append suffix when suffix is not applied")
		void shouldNotAppendSuffixWhenNotApplied() {
			final AttributeInRange constraint = attributeInRange("validity", 42);
			assertEquals("attributeInRange", constraint.getName());
		}
	}

	@Nested
	@DisplayName("Argument processing")
	class ArgumentProcessing {

		@Test
		@DisplayName("should store supported argument types directly")
		void shouldStoreSupportedTypesDirectly() {
			final Collection constraint = collection("product");
			assertArrayEquals(new Object[]{"product"}, constraint.getArguments());
		}

		@Test
		@DisplayName("should store multiple arguments")
		void shouldStoreMultipleArguments() {
			final AttributeEquals constraint = attributeEquals("code", "samsung");
			assertArrayEquals(new Object[]{"code", "samsung"}, constraint.getArguments());
		}

		@Test
		@DisplayName("should handle numeric arguments")
		void shouldHandleNumericArguments() {
			final AttributeEquals constraint = attributeEquals("count", 42);
			assertArrayEquals(new Object[]{"count", 42}, constraint.getArguments());
		}

		@Test
		@DisplayName("should handle BigDecimal arguments")
		void shouldHandleBigDecimalArguments() {
			final AttributeEquals constraint = attributeEquals("price", new BigDecimal("10.5"));
			assertArrayEquals(new Object[]{"price", new BigDecimal("10.5")}, constraint.getArguments());
		}
	}

	@Nested
	@DisplayName("Argument validation")
	class ArgumentValidation {

		@Test
		@DisplayName("should return true when all arguments are non-null")
		void shouldReturnTrueWhenAllArgumentsNonNull() {
			final Collection constraint = collection("product");
			assertTrue(constraint.isApplicable());
		}

		@Test
		@DisplayName("should return null from factory method when argument is null")
		void shouldReturnNullFromFactoryForNullArgument() {
			// factory method returns null for null value arguments
			final AttributeEquals constraint = attributeEquals("code", null);
			assertNull(constraint);
		}
	}

	@Nested
	@DisplayName("String serialization")
	class StringSerialization {

		@Test
		@DisplayName("should convert null to <NULL>")
		void shouldConvertNullToNullString() {
			assertEquals("<NULL>", BaseConstraint.convertToString(null));
		}

		@Test
		@DisplayName("should convert string with quotes")
		void shouldConvertStringWithQuotes() {
			assertEquals("'hello'", BaseConstraint.convertToString("hello"));
		}

		@Test
		@DisplayName("should convert integer")
		void shouldConvertInteger() {
			assertEquals("42", BaseConstraint.convertToString(42));
		}

		@Test
		@DisplayName("should convert OffsetDateTime")
		void shouldConvertOffsetDateTime() {
			final OffsetDateTime dateTime = OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
			assertEquals("2020-01-01T00:00:00Z", BaseConstraint.convertToString(dateTime));
		}

		@Test
		@DisplayName("should produce correct toString format")
		void shouldProduceCorrectToStringFormat() {
			final Collection constraint = collection("product");
			assertEquals("collection('product')", constraint.toString());
		}

		@Test
		@DisplayName("should produce correct toString for multi-arg constraint")
		void shouldProduceCorrectToStringForMultiArg() {
			final AttributeEquals constraint = attributeEquals("code", "samsung");
			assertEquals("attributeEquals('code','samsung')", constraint.toString());
		}

		@Test
		@DisplayName("should hide implicit arguments for suffix constraints")
		void shouldHideImplicitArgumentsForSuffix() {
			final AttributeInRange constraint = attributeInRangeNow("validity");
			// The "now" suffix hides the OffsetDateTime argument
			final String str = constraint.toString();
			assertTrue(str.startsWith("attributeInRangeNow("));
			assertTrue(str.contains("'validity'"));
		}
	}

	@Nested
	@DisplayName("Equals and hash code")
	class EqualsAndHashCode {

		@Test
		@DisplayName("should be equal with same arguments")
		void shouldBeEqualWithSameArguments() {
			final Collection a = collection("product");
			final Collection b = collection("product");
			assertEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("should not be equal with different arguments")
		void shouldNotBeEqualWithDifferentArguments() {
			final Collection a = collection("product");
			final Collection b = collection("brand");
			assertNotEquals(a, b);
		}

		@Test
		@DisplayName("should be equal for constraints with same array content")
		void shouldBeEqualForSameArrayContent() {
			final AttributeEquals a = attributeEquals("code", "samsung");
			final AttributeEquals b = attributeEquals("code", "samsung");
			assertEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("should not be equal for constraints with different array content")
		void shouldNotBeEqualForDifferentArrayContent() {
			final AttributeEquals a = attributeEquals("code", "samsung");
			final AttributeEquals b = attributeEquals("code", "nokia");
			assertNotEquals(a, b);
		}
	}
}
