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

package io.evitadb.api.query.parser;

import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.exception.InconvertibleDataTypeException;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Value} verifying type conversions, array
 * handling, and error conditions for all supported data types.
 *
 * @author evitaDB
 */
@DisplayName("Value")
class ValueTest {

	/**
	 * Test enum for enum-related conversion tests.
	 */
	enum TestEnum { VALUE_A, VALUE_B, VALUE_C }

	@Nested
	@DisplayName("String conversions")
	class StringConversions {

		@Test
		@DisplayName("should accept and return string")
		void shouldAcceptAndReturnString() {
			final Value value = new Value("name");

			assertSame(String.class, value.getType());
			assertEquals("name", value.getActualValue());
			assertEquals("name", value.asString());
		}

		@Test
		@DisplayName("should convert Character to string")
		void shouldConvertCharacterToString() {
			final Value value = new Value('A');

			assertEquals("A", value.asString());
		}

		@Test
		@DisplayName("should not cast non-string to string")
		void shouldNotCastNonStringToString() {
			assertThrows(EvitaInternalError.class, () -> new Value(123).asString());
		}
	}

	@Nested
	@DisplayName("Numeric conversions")
	class NumericConversions {

		@Test
		@DisplayName("should return int from Long value")
		void shouldReturnIntFromLong() {
			final Value value = new Value(42L);

			assertEquals(42, value.asInt());
		}

		@Test
		@DisplayName("should return int from Integer value")
		void shouldReturnIntFromInteger() {
			final Value value = new Value(42);

			assertEquals(42, value.asInt());
		}

		@Test
		@DisplayName("should throw when Long overflows int range")
		void shouldThrowWhenLongOverflowsIntRange() {
			final Value value = new Value(Long.MAX_VALUE);

			assertThrows(EvitaInvalidUsageException.class, value::asInt);
		}

		@Test
		@DisplayName("should return long value")
		void shouldReturnLongValue() {
			final Value value = new Value(100L);

			assertEquals(100L, value.asLong());
		}

		@Test
		@DisplayName("should return long from int value")
		void shouldReturnLongFromInt() {
			final Value value = new Value(50);

			assertEquals(50L, value.asLong());
		}

		@Test
		@DisplayName("should return number")
		void shouldReturnNumber() {
			final Value value = new Value(99L);
			final Number number = value.asNumber();

			assertEquals(99L, number);
		}

		@Test
		@DisplayName("should convert number to target type")
		void shouldConvertNumberToTargetType() {
			final Value value = new Value(42L);
			final BigDecimal result = value.asNumber(BigDecimal.class);

			assertEquals(new BigDecimal("42"), result);
		}

		@Test
		@DisplayName("should return BigDecimal")
		void shouldReturnBigDecimal() {
			final BigDecimal expected = new BigDecimal("123.45");
			final Value value = new Value(expected);

			assertEquals(expected, value.asBigDecimal());
		}

		@Test
		@DisplayName("should not cast string to number")
		void shouldNotCastStringToNumber() {
			assertThrows(EvitaInternalError.class, () -> new Value("name").asLong());
		}
	}

	@Nested
	@DisplayName("Boolean conversions")
	class BooleanConversions {

		@Test
		@DisplayName("should return true boolean")
		void shouldReturnTrueBoolean() {
			final Value value = new Value(true);

			assertTrue(value.asBoolean());
		}

		@Test
		@DisplayName("should return false boolean")
		void shouldReturnFalseBoolean() {
			final Value value = new Value(false);

			assertFalse(value.asBoolean());
		}

		@Test
		@DisplayName("should not cast non-boolean to boolean")
		void shouldNotCastNonBooleanToBoolean() {
			assertThrows(EvitaInternalError.class, () -> new Value("true").asBoolean());
		}
	}

	@Nested
	@DisplayName("Date/time conversions")
	class DateTimeConversions {

		@Test
		@DisplayName("should return OffsetDateTime")
		void shouldReturnOffsetDateTime() {
			final OffsetDateTime expected = OffsetDateTime.of(2023, 6, 15, 10, 30, 0, 0, ZoneOffset.UTC);
			final Value value = new Value(expected);

			assertEquals(expected, value.asOffsetDateTime());
		}

		@Test
		@DisplayName("should return LocalDateTime")
		void shouldReturnLocalDateTime() {
			final LocalDateTime expected = LocalDateTime.of(2023, 6, 15, 10, 30);
			final Value value = new Value(expected);

			assertEquals(expected, value.asLocalDateTime());
		}

		@Test
		@DisplayName("should return LocalDate")
		void shouldReturnLocalDate() {
			final LocalDate expected = LocalDate.of(2023, 6, 15);
			final Value value = new Value(expected);

			assertEquals(expected, value.asLocalDate());
		}

		@Test
		@DisplayName("should return LocalTime")
		void shouldReturnLocalTime() {
			final LocalTime expected = LocalTime.of(10, 30, 45);
			final Value value = new Value(expected);

			assertEquals(expected, value.asLocalTime());
		}
	}

	@Nested
	@DisplayName("Range conversions")
	class RangeConversions {

		@Test
		@DisplayName("should return DateTimeRange")
		void shouldReturnDateTimeRange() {
			final OffsetDateTime from = OffsetDateTime.of(2023, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
			final OffsetDateTime to = OffsetDateTime.of(2023, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC);
			final DateTimeRange expected = DateTimeRange.between(from, to);
			final Value value = new Value(expected);

			assertEquals(expected, value.asDateTimeRange());
		}

		@Test
		@DisplayName("should return BigDecimalNumberRange")
		void shouldReturnBigDecimalNumberRange() {
			final BigDecimalNumberRange expected =
				BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("100.0"));
			final Value value = new Value(expected);

			assertEquals(expected, value.asBigDecimalNumberRange());
		}

		@Test
		@DisplayName("should return LongNumberRange")
		void shouldReturnLongNumberRange() {
			final LongNumberRange expected = LongNumberRange.between(1L, 100L);
			final Value value = new Value(expected);

			assertEquals(expected, value.asLongNumberRange());
		}
	}

	@Nested
	@DisplayName("Enum conversions")
	class EnumConversions {

		@Test
		@DisplayName("should return enum from direct enum value")
		void shouldReturnEnumFromDirectValue() {
			final Value value = new Value(TestEnum.VALUE_A);

			assertEquals(TestEnum.VALUE_A, value.asEnum(TestEnum.class));
		}

		@Test
		@DisplayName("should return enum from EnumWrapper")
		void shouldReturnEnumFromEnumWrapper() {
			final Value value = new Value(EnumWrapper.fromString("VALUE_B"));

			assertEquals(TestEnum.VALUE_B, value.asEnum(TestEnum.class));
		}

		@Test
		@DisplayName("should throw for invalid type in asEnum")
		void shouldThrowForInvalidTypeInAsEnum() {
			assertThrows(EvitaInvalidUsageException.class, () -> new Value("VALUE_A").asEnum(TestEnum.class));
		}

		@Test
		@DisplayName("should return enum array from EnumWrapper list")
		void shouldReturnEnumArrayFromEnumWrapperList() {
			final Value value = new Value(List.of(
				EnumWrapper.fromString("VALUE_A"),
				EnumWrapper.fromString("VALUE_C")
			));

			final TestEnum[] result = value.asEnumArray(TestEnum.class);

			assertArrayEquals(new TestEnum[]{TestEnum.VALUE_A, TestEnum.VALUE_C}, result);
		}

		@Test
		@DisplayName("should return enum array from enum array")
		void shouldReturnEnumArrayFromEnumArray() {
			final TestEnum[] input = new TestEnum[]{TestEnum.VALUE_B, TestEnum.VALUE_A};
			final Value value = new Value(input);

			final TestEnum[] result = value.asEnumArray(TestEnum.class);

			assertArrayEquals(input, result);
		}
	}

	@Nested
	@DisplayName("Locale conversions")
	class LocaleConversions {

		@Test
		@DisplayName("should return Locale from Locale")
		void shouldReturnLocaleFromLocale() {
			final Value locale = new Value(new Locale("cs", "CZ"));

			assertEquals(new Locale("cs", "CZ"), locale.asLocale());
		}

		@Test
		@DisplayName("should return Locale from string")
		void shouldReturnLocaleFromString() {
			final Value stringLocale = new Value("cs-CZ");

			assertEquals(new Locale("cs", "CZ"), stringLocale.asLocale());
		}

		@Test
		@DisplayName("should not return Locale from invalid input")
		void shouldNotReturnLocaleFromInvalidInput() {
			assertThrows(EvitaInvalidUsageException.class, () -> new Value(1).asLocale());
			assertThrows(InconvertibleDataTypeException.class, () -> new Value("").asLocale());
			assertThrows(InconvertibleDataTypeException.class, () -> new Value("zz").asLocale());
		}

		@Test
		@DisplayName("should return locale array")
		void shouldReturnLocaleArray() {
			final Value stringLocales = new Value(List.of("en", "fr"));

			assertArrayEquals(new Locale[]{Locale.ENGLISH, Locale.FRENCH}, stringLocales.asLocaleArray());

			final Value locales = new Value(List.of(Locale.ENGLISH, Locale.FRENCH));

			assertArrayEquals(new Locale[]{Locale.ENGLISH, Locale.FRENCH}, locales.asLocaleArray());
		}
	}

	@Nested
	@DisplayName("Currency conversions")
	class CurrencyConversions {

		@Test
		@DisplayName("should return Currency from Currency")
		void shouldReturnCurrencyFromCurrency() {
			final Currency expected = Currency.getInstance("USD");
			final Value value = new Value(expected);

			assertSame(expected, value.asCurrency());
		}

		@Test
		@DisplayName("should return Currency from string")
		void shouldReturnCurrencyFromString() {
			final Value value = new Value("EUR");

			assertSame(Currency.getInstance("EUR"), value.asCurrency());
		}

		@Test
		@DisplayName("should throw for invalid type in asCurrency")
		void shouldThrowForInvalidTypeInAsCurrency() {
			assertThrows(EvitaInvalidUsageException.class, () -> new Value(42).asCurrency());
		}
	}

	@Nested
	@DisplayName("UUID conversions")
	class UuidConversions {

		@Test
		@DisplayName("should return UUID from UUID")
		void shouldReturnUuidFromUuid() {
			final UUID expected = UUID.randomUUID();
			final Value value = new Value(expected);

			assertEquals(expected, value.asUuid());
		}

		@Test
		@DisplayName("should return UUID from string")
		void shouldReturnUuidFromString() {
			final UUID expected = UUID.randomUUID();
			final Value value = new Value(expected.toString());

			assertEquals(expected, value.asUuid());
		}

		@Test
		@DisplayName("should throw for invalid type in asUuid")
		void shouldThrowForInvalidTypeInAsUuid() {
			assertThrows(EvitaInvalidUsageException.class, () -> new Value(42).asUuid());
		}
	}

	@Nested
	@DisplayName("Array conversions")
	class ArrayConversions {

		@Test
		@DisplayName("should return integer array")
		void shouldReturnIntegerArray() {
			final Value value = new Value(List.of(4, 5L));

			assertArrayEquals(new Integer[]{4, 5}, value.asIntegerArray());
		}

		@Test
		@DisplayName("should not return integer array from mixed types")
		void shouldNotReturnIntegerArrayFromMixedTypes() {
			assertThrows(EvitaInternalError.class, () -> new Value(List.of(1, "b")).asIntegerArray());
		}

		@Test
		@DisplayName("should return string array from list")
		void shouldReturnStringArrayFromList() {
			final Value value = new Value(List.of("a", "b", "c"));

			assertArrayEquals(new String[]{"a", "b", "c"}, value.asStringArray());
		}

		@Test
		@DisplayName("should return string array from array")
		void shouldReturnStringArrayFromArray() {
			final Value value = new Value(new String[]{"x", "y"});

			assertArrayEquals(new String[]{"x", "y"}, value.asStringArray());
		}

		@Test
		@DisplayName("should throw for invalid type in asStringArray")
		void shouldThrowForInvalidTypeInAsStringArray() {
			assertThrows(EvitaInvalidUsageException.class, () -> new Value(42).asStringArray());
		}

		@Test
		@DisplayName("should return serializable array from list")
		void shouldReturnSerializableArrayFromList() {
			final Value value = new Value(List.of("a", "b"));

			assertArrayEquals(new String[]{"a", "b"}, value.asSerializableArray());
		}
	}

	@Nested
	@DisplayName("Generic conversions")
	class GenericConversions {

		@Test
		@DisplayName("should return as serializable")
		void shouldReturnAsSerializable() {
			final Value value = new Value("hello");

			assertEquals("hello", value.asSerializable());
		}

		@Test
		@DisplayName("should return as comparable")
		void shouldReturnAsComparable() {
			final Value value = new Value("name");

			assertEquals("name", value.asComparable());
		}

		@Test
		@DisplayName("should return as serializable and comparable")
		void shouldReturnAsSerializableAndComparable() {
			final Value value = new Value("name");

			assertEquals("name", value.asSerializableAndComparable());
		}

		@Test
		@DisplayName("should throw when value is not comparable")
		void shouldThrowWhenValueIsNotComparable() {
			final Value value = new Value(List.of("a", "b"));

			assertThrows(EvitaInternalError.class, value::asComparable);
		}

		@Test
		@DisplayName("should throw when value is not serializable and comparable")
		void shouldThrowWhenNotSerializableAndComparable() {
			final Value value = new Value(List.of("a", "b"));

			assertThrows(EvitaInternalError.class, value::asSerializableAndComparable);
		}
	}
}
