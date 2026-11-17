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
import io.evitadb.api.query.parser.DefaultQueryParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;

/**
 * Tests for {@link BaseConstraint} - the abstract base class for all evitaDB query constraints.
 * Uses concrete constraint subclasses ({@link Collection}, {@link AttributeEquals}) to test base behavior.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("BaseConstraint")
@Tag(CONTRACT)
@Tag(QUERY)
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
	@DisplayName("Temporal argument normalization")
	class TemporalArgumentNormalization {

		@Test
		@DisplayName("should cut an OffsetDateTime argument to whole milliseconds")
		void shouldTruncateOffsetDateTimeArgument() {
			// an OffsetDateTime is already a supported type - the value still has to be normalized, or the
			// probe would keep sub-millisecond digits the stored value was cut of when it was written
			final AttributeEquals constraint = attributeEquals(
				"validity",
				OffsetDateTime.of(2021, 6, 15, 10, 15, 30, 123_456_789, ZoneOffset.UTC)
			);

			assertEquals(
				OffsetDateTime.of(2021, 6, 15, 10, 15, 30, 123_000_000, ZoneOffset.UTC),
				constraint.getAttributeValue()
			);
			assertEquals(123_000_000, ((OffsetDateTime) constraint.getAttributeValue()).getNano());
		}

		@Test
		@DisplayName("should cut a LocalDateTime argument to whole milliseconds")
		void shouldTruncateLocalDateTimeArgument() {
			// the query path additionally rewrites a LocalDateTime to an OffsetDateTime at UTC
			final AttributeEquals constraint = attributeEquals(
				"publishedAt",
				LocalDateTime.of(2021, 6, 15, 10, 15, 30, 123_456_789)
			);

			assertEquals(
				OffsetDateTime.of(2021, 6, 15, 10, 15, 30, 123_000_000, ZoneOffset.UTC),
				constraint.getAttributeValue()
			);
		}

		@Test
		@DisplayName("should cut a LocalTime argument to whole milliseconds")
		void shouldTruncateLocalTimeArgument() {
			final AttributeEquals constraint = attributeEquals(
				"openedAt",
				LocalTime.of(10, 15, 30, 123_456_789)
			);

			assertEquals(LocalTime.of(10, 15, 30, 123_000_000), constraint.getAttributeValue());
		}

		@Test
		@DisplayName("should cut a temporal argument of a range constraint")
		void shouldTruncateTemporalArgumentOfRangeConstraint() {
			final AttributeInRange constraint = attributeInRange(
				"validity",
				OffsetDateTime.of(2021, 6, 15, 10, 15, 30, 123_456_789, ZoneOffset.UTC)
			);

			assertEquals(
				OffsetDateTime.of(2021, 6, 15, 10, 15, 30, 123_000_000, ZoneOffset.UTC),
				constraint.getTheMoment()
			);
		}

		@Test
		@DisplayName("should leave a non-temporal argument alone")
		void shouldLeaveNonTemporalArgumentAlone() {
			final String code = "samsung";
			final AttributeEquals constraint = attributeEquals("code", code);

			assertSame(code, constraint.getAttributeValue());
		}

		@Test
		@DisplayName("should clamp a moment beyond the representable window to its edge")
		void shouldClampUnrepresentableMoment() {
			final AttributeEquals latest = attributeEquals("validity", LocalDateTime.MAX);
			final AttributeEquals earliest = attributeEquals("validity", OffsetDateTime.MIN);

			// a query argument lands on the same edge a stored value does, so the two still meet
			final Instant latestInstant = Instant.ofEpochMilli((Long.MAX_VALUE / 1_000L - 1L) * 1_000L + 999L);
			final Instant earliestInstant = Instant.ofEpochMilli((Long.MIN_VALUE / 1_000L + 1L) * 1_000L);
			assertEquals(OffsetDateTime.ofInstant(latestInstant, ZoneOffset.UTC), latest.getAttributeValue());
			assertEquals(OffsetDateTime.ofInstant(earliestInstant, ZoneOffset.MAX), earliest.getAttributeValue());
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

		/** A compact extreme scale falls back to the exponent form instead of rendering its magnitude. */
		@Test
		@DisplayName("should print extreme decimal scales in exponent form")
		void shouldPrintExtremeDecimalScalesInExponentForm() {
			assertEquals("1e2147483648",
				BaseConstraint.convertToString(new BigDecimal(BigInteger.ONE, Integer.MIN_VALUE)));
			assertEquals("1e-2147483647",
				BaseConstraint.convertToString(new BigDecimal(BigInteger.ONE, Integer.MAX_VALUE)));
			assertEquals("1e1000000", BaseConstraint.convertToString(new BigDecimal("1E+1000000")));
		}

		/** The 201-character plain bound covers every accepted place weight, including its sign. */
		@Test
		@DisplayName("should print decimals plain up to 201 characters and in exponent form above")
		void shouldPrintDecimalsPlainUpToTheBound() {
			assertEquals("1" + "0".repeat(200), BaseConstraint.convertToString(new BigDecimal(BigInteger.ONE, -200)));
			assertEquals("1e201", BaseConstraint.convertToString(new BigDecimal(BigInteger.ONE, -201)));
			assertEquals("12" + "0".repeat(199),
				BaseConstraint.convertToString(new BigDecimal(BigInteger.valueOf(12), -199)));
			assertEquals("1.2e201", BaseConstraint.convertToString(new BigDecimal(BigInteger.valueOf(12), -200)));
			assertEquals("-1" + "0".repeat(199),
				BaseConstraint.convertToString(new BigDecimal(BigInteger.ONE.negate(), -199)));
			assertEquals("-1e200", BaseConstraint.convertToString(new BigDecimal(BigInteger.ONE.negate(), -200)));
			assertEquals("0." + "0".repeat(198) + "1",
				BaseConstraint.convertToString(new BigDecimal(BigInteger.ONE, 199)));
			assertEquals("1e-200", BaseConstraint.convertToString(new BigDecimal(BigInteger.ONE, 200)));
			assertEquals("0.123", BaseConstraint.convertToString(new BigDecimal("0.123")));
			assertEquals("0.00", BaseConstraint.convertToString(new BigDecimal("0.00")));
			assertEquals("0", BaseConstraint.convertToString(new BigDecimal(BigInteger.ZERO, -300)));
			assertEquals("3.00", BaseConstraint.convertToString(new BigDecimal("3.00")));
		}

		/** The EvitaQL grammar reads no exponent, so a printed decimal argument must parse back to its number. */
		@Test
		@DisplayName("should print negative-scale and tiny decimal arguments as text the parser reads back")
		void shouldPrintDecimalArgumentsTheParserReadsBack() {
			final AttributeEquals thousand = attributeEquals("price", new BigDecimal("1E+3"));
			final AttributeEquals tiny = attributeEquals("price", new BigDecimal("1E-7"));
			final DefaultQueryParser parser = DefaultQueryParser.getInstance();

			final AttributeEquals parsedThousand =
				(AttributeEquals) parser.parseFilterConstraintListUnsafe(thousand.toString()).get(0);
			final FilterConstraint parsedTiny = parser.parseFilterConstraintListUnsafe(tiny.toString()).get(0);

			assertEquals("attributeEquals('price',1000)", thousand.toString());
			assertEquals("attributeEquals('price',0.0000001)", tiny.toString());
			// an integer literal parses as an integral number, so the thousand compares by numeric value
			assertEquals(0, new BigDecimal(String.valueOf((Object) parsedThousand.getAttributeValue()))
				.compareTo(new BigDecimal("1E+3")));
			assertEquals(tiny, parsedTiny);
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
