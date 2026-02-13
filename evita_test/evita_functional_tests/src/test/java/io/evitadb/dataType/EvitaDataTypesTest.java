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

package io.evitadb.dataType;

import io.evitadb.dataType.exception.InconvertibleDataTypeException;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.MemoryMeasuringConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static io.evitadb.dataType.EvitaDataTypes.formatValue;
import static io.evitadb.dataType.EvitaDataTypes.getWrappingPrimitiveClass;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests verifying {@link EvitaDataTypes} contract including value
 * formatting, primitive wrapper resolution, and type conversion
 * for all supported data types.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("EvitaDataTypes")
class EvitaDataTypesTest {

	@Nested
	@DisplayName("Value formatting")
	class FormatValueTest {

		@Test
		@DisplayName("should format byte value")
		void shouldFormatByte() {
			assertEquals("5", formatValue((byte) 5));
		}

		@Test
		@DisplayName("should format short value")
		void shouldFormatShort() {
			assertEquals("5", formatValue((short) 5));
		}

		@Test
		@DisplayName("should format int value")
		void shouldFormatInt() {
			assertEquals("5", formatValue(5));
		}

		@Test
		@DisplayName("should format long value")
		void shouldFormatLong() {
			assertEquals("5", formatValue((long) 5));
		}

		@Test
		@DisplayName("should format char value")
		void shouldFormatChar() {
			assertEquals("'5'", formatValue('5'));
		}

		@Test
		@DisplayName("should format boolean value")
		void shouldFormatBoolean() {
			assertEquals("true", formatValue(true));
		}

		@Test
		@DisplayName("should format String value")
		void shouldFormatString() {
			assertEquals("'5'", formatValue("5"));
		}

		@Test
		@DisplayName("should format BigDecimal value")
		void shouldFormatBigDecimal() {
			assertEquals(
				"1.123",
				formatValue(new BigDecimal("1.123"))
			);
		}

		@Test
		@DisplayName("should format Locale value")
		void shouldFormatLocale() {
			final Locale czechLocale =
				new Locale("cs", "CZ");

			assertEquals(
				"'cs-CZ'", formatValue(czechLocale)
			);
		}

		@Test
		@DisplayName("should format Currency value")
		void shouldFormatCurrency() {
			final Currency czkCurrency =
				Currency.getInstance("CZK");

			assertEquals(
				"'CZK'", formatValue(czkCurrency)
			);
		}

		@Test
		@DisplayName("should format UUID value")
		void shouldFormatUUID() {
			final UUID uuid = UUID.randomUUID();

			assertEquals(
				"'" + uuid + "'", formatValue(uuid)
			);
		}

		@Test
		@DisplayName("should format OffsetDateTime value")
		void shouldFormatOffsetDateTime() {
			final OffsetDateTime dateTime =
				OffsetDateTime.of(
					2021, 1, 30, 14, 45, 16, 0,
					ZoneId.of("Europe/Prague")
						.getRules()
						.getOffset(
							LocalDateTime.of(
								2022, 12, 1, 0, 0
							)
						)
				);

			assertEquals(
				"2021-01-30T14:45:16+01:00",
				formatValue(dateTime)
			);
		}

		@Test
		@DisplayName("should format LocalDateTime value")
		void shouldFormatLocalDateTime() {
			final LocalDateTime dateTime =
				LocalDateTime.of(
					2021, 1, 30, 14, 45, 16, 0
				);

			assertEquals(
				"2021-01-30T14:45:16",
				formatValue(dateTime)
			);
		}

		@Test
		@DisplayName("should format LocalDate value")
		void shouldFormatLocalDate() {
			final LocalDate date =
				LocalDate.of(2021, 1, 30);

			assertEquals(
				"2021-01-30", formatValue(date)
			);
		}

		@Test
		@DisplayName("should format LocalTime value")
		void shouldFormatLocalTime() {
			final LocalTime time =
				LocalTime.of(14, 45, 16, 0);

			assertEquals(
				"14:45:16", formatValue(time)
			);
		}

		@Test
		@DisplayName("should format NumberRange values")
		void shouldFormatNumberRange() {
			final NumberRange<?> fromRange =
				BigDecimalNumberRange.from(
					new BigDecimal("45.45"), 2
				);
			assertEquals(
				"[45.45,]", formatValue(fromRange)
			);

			final NumberRange<?> toRange =
				BigDecimalNumberRange.to(
					new BigDecimal("45.45"), 2
				);
			assertEquals(
				"[,45.45]", formatValue(toRange)
			);

			final NumberRange<?> betweenRange =
				BigDecimalNumberRange.between(
					new BigDecimal("45.45"),
					new BigDecimal("89.5"), 2
				);
			assertEquals(
				"[45.45,89.5]",
				formatValue(betweenRange)
			);
		}

		@Test
		@DisplayName("should format DateTimeRange values")
		void shouldFormatDateTimeRange() {
			final OffsetDateTime dateTimeA =
				OffsetDateTime.of(
					2021, 1, 30, 14, 45, 16, 0,
					ZoneId.of("Europe/Prague")
						.getRules()
						.getOffset(
							LocalDateTime.of(
								2022, 12, 1, 0, 0
							)
						)
				);
			final OffsetDateTime dateTimeB =
				OffsetDateTime.of(
					2022, 1, 30, 14, 45, 16, 0,
					ZoneId.of("Europe/Prague")
						.getRules()
						.getOffset(
							LocalDateTime.of(
								2022, 12, 1, 0, 0
							)
						)
				);

			final DateTimeRange since =
				DateTimeRange.since(dateTimeA);
			assertEquals(
				"[2021-01-30T14:45:16+01:00,]",
				formatValue(since)
			);

			final DateTimeRange toRange =
				DateTimeRange.until(dateTimeA);
			assertEquals(
				"[,2021-01-30T14:45:16+01:00]",
				formatValue(toRange)
			);

			final DateTimeRange betweenRange =
				DateTimeRange.between(
					dateTimeA, dateTimeB
				);
			assertEquals(
				"[2021-01-30T14:45:16+01:00,"
					+ "2022-01-30T14:45:16+01:00]",
				formatValue(betweenRange)
			);
		}

		@Test
		@DisplayName(
			"should throw for null value"
		)
		void shouldThrowForNullValue() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> formatValue(null)
			);
		}

		@Test
		@DisplayName(
			"should format String with embedded quote"
		)
		void shouldFormatStringWithEmbeddedQuote() {
			assertEquals(
				"'it\\'s'",
				formatValue("it's")
			);
		}

		@Test
		@DisplayName(
			"should format Predecessor value"
		)
		void shouldFormatPredecessor() {
			final Predecessor predecessor =
				new Predecessor(42);

			assertEquals(
				predecessor.toString(),
				formatValue(predecessor)
			);
		}

		@Test
		@DisplayName(
			"should format ReferencedEntityPredecessor"
			+ " value"
		)
		void shouldFormatReferencedEntityPredecessor() {
			final ReferencedEntityPredecessor predecessor =
				new ReferencedEntityPredecessor(42);

			assertEquals(
				predecessor.toString(),
				formatValue(predecessor)
			);
		}
	}

	@Nested
	@DisplayName("Wrapper class resolution")
	class WrapperClassTest {

		@Test
		@DisplayName(
			"should return wrapper class for"
			+ " int and char"
		)
		void shouldReturnWrapperClass() {
			assertSame(Integer.class, EvitaDataTypes.getWrappingPrimitiveClass(int.class));
			assertSame(Character.class, EvitaDataTypes.getWrappingPrimitiveClass(char.class));
		}

		@Test
		@DisplayName(
			"should return wrapper class for boolean"
		)
		void shouldReturnWrapperClassForBoolean() {
			assertSame(Boolean.class, EvitaDataTypes.getWrappingPrimitiveClass(boolean.class));
		}

		@Test
		@DisplayName(
			"should return wrapper class for byte"
		)
		void shouldReturnWrapperClassForByte() {
			assertSame(Byte.class, EvitaDataTypes.getWrappingPrimitiveClass(byte.class));
		}

		@Test
		@DisplayName(
			"should return wrapper class for short"
		)
		void shouldReturnWrapperClassForShort() {
			assertSame(Short.class, EvitaDataTypes.getWrappingPrimitiveClass(short.class));
		}

		@Test
		@DisplayName(
			"should return wrapper class for long"
		)
		void shouldReturnWrapperClassForLong() {
			assertSame(Long.class, EvitaDataTypes.getWrappingPrimitiveClass(long.class));
		}

		@Test
		@DisplayName(
			"should return wrapper class for float"
		)
		void shouldReturnWrapperClassForFloat() {
			assertSame(Float.class, EvitaDataTypes.getWrappingPrimitiveClass(float.class));
		}

		@Test
		@DisplayName(
			"should return wrapper class for double"
		)
		void shouldReturnWrapperClassForDouble() {
			assertSame(Double.class, EvitaDataTypes.getWrappingPrimitiveClass(double.class));
		}

		@Test
		@DisplayName(
			"should throw for non-primitive class"
		)
		void shouldThrowForNonPrimitive() {
			assertThrows(
				IllegalArgumentException.class,
				() -> getWrappingPrimitiveClass(
					String.class
				)
			);
		}

		@Test
		@DisplayName(
			"should convert to wrapped form including"
			+ " arrays"
		)
		void shouldConvertToWrapperClass() {
			assertSame(Integer.class, EvitaDataTypes.toWrappedForm(int.class));
			assertSame(Integer[].class, EvitaDataTypes.toWrappedForm(int[].class));
			assertSame(Character.class, EvitaDataTypes.toWrappedForm(char.class));
			assertSame(Character[].class, EvitaDataTypes.toWrappedForm(char[].class));
		}

		@Test
		@DisplayName(
			"should pass through non-primitive in"
			+ " toWrappedForm"
		)
		void shouldPassThroughNonPrimitive() {
			assertSame(String.class, EvitaDataTypes.toWrappedForm(String.class));
		}

		@Test
		@DisplayName(
			"should pass through void in"
			+ " toWrappedForm"
		)
		void shouldPassThroughVoid() {
			assertSame(void.class, EvitaDataTypes.toWrappedForm(void.class));
		}

		@Test
		@DisplayName(
			"should wrap all 8 primitive types"
		)
		void shouldWrapAllPrimitiveTypes() {
			assertSame(Boolean.class, EvitaDataTypes.toWrappedForm(boolean.class));
			assertSame(Byte.class, EvitaDataTypes.toWrappedForm(byte.class));
			assertSame(Short.class, EvitaDataTypes.toWrappedForm(short.class));
			assertSame(Long.class, EvitaDataTypes.toWrappedForm(long.class));
			assertSame(Float.class, EvitaDataTypes.toWrappedForm(float.class));
			assertSame(Double.class, EvitaDataTypes.toWrappedForm(double.class));
		}
	}

	@Nested
	@DisplayName("Conversion to Byte")
	class ConvertToByteTest {

		@Test
		@DisplayName(
			"should convert various types to Byte"
		)
		void shouldConvertToByte() {
			assertEquals(
				(byte) 8,
				EvitaDataTypes.toTargetType(
					(byte) 8, Byte.class
				)
			);
			assertEquals(
				(byte) 8,
				EvitaDataTypes.toTargetType(
					(short) 8, Byte.class
				)
			);
			assertEquals(
				(byte) 8,
				EvitaDataTypes.toTargetType(
					8, Byte.class
				)
			);
			assertEquals(
				(byte) 8,
				EvitaDataTypes.toTargetType(
					(long) 8, Byte.class
				)
			);
			assertEquals(
				(byte) 8,
				EvitaDataTypes.toTargetType(
					new BigDecimal("8"), Byte.class
				)
			);
			assertEquals(
				(byte) 8,
				EvitaDataTypes.toTargetType(
					"8", Byte.class
				)
			);

			assertEquals(
				(byte) 8,
				EvitaDataTypes.toTargetType(
					(byte) 8, byte.class
				)
			);
			assertEquals(
				(byte) 8,
				EvitaDataTypes.toTargetType(
					(short) 8, byte.class
				)
			);
			assertEquals(
				(byte) 8,
				EvitaDataTypes.toTargetType(
					8, byte.class
				)
			);
			assertEquals(
				(byte) 8,
				EvitaDataTypes.toTargetType(
					(long) 8, byte.class
				)
			);
			assertEquals(
				(byte) 8,
				EvitaDataTypes.toTargetType(
					new BigDecimal("8"), byte.class
				)
			);
			assertEquals(
				(byte) 8,
				EvitaDataTypes.toTargetType(
					"8", byte.class
				)
			);

			assertArrayEquals(
				new Byte[]{8},
				EvitaDataTypes.toTargetType(
					8, Byte[].class
				)
			);
			assertArrayEquals(
				new Byte[]{8},
				EvitaDataTypes.toTargetType(
					new int[]{8}, Byte[].class
				)
			);
			assertArrayEquals(
				new Byte[]{8},
				EvitaDataTypes.toTargetType(
					new Integer[]{8}, Byte[].class
				)
			);
		}

		@Test
		@DisplayName(
			"should fail to convert incompatible types"
			+ " to Byte"
		)
		void shouldFailToConvertToByte() {
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					Currency.getInstance("CZK"),
					Byte.class
				)
			);
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					new BigDecimal("8.78"), Byte.class
				)
			);
		}
	}

	@Nested
	@DisplayName("Conversion to Short")
	class ConvertToShortTest {

		@Test
		@DisplayName(
			"should convert various types to Short"
		)
		void shouldConvertToShort() {
			assertEquals(
				(short) 8,
				EvitaDataTypes.toTargetType(
					(byte) 8, Short.class
				)
			);
			assertEquals(
				(short) 8,
				EvitaDataTypes.toTargetType(
					(short) 8, Short.class
				)
			);
			assertEquals(
				(short) 8,
				EvitaDataTypes.toTargetType(
					8, Short.class
				)
			);
			assertEquals(
				(short) 8,
				EvitaDataTypes.toTargetType(
					(long) 8, Short.class
				)
			);
			assertEquals(
				(short) 8,
				EvitaDataTypes.toTargetType(
					new BigDecimal("8"), Short.class
				)
			);
			assertEquals(
				(short) 8,
				EvitaDataTypes.toTargetType(
					"8", Short.class
				)
			);

			assertEquals(
				(short) 8,
				EvitaDataTypes.toTargetType(
					(byte) 8, short.class
				)
			);
			assertEquals(
				(short) 8,
				EvitaDataTypes.toTargetType(
					(short) 8, short.class
				)
			);
			assertEquals(
				(short) 8,
				EvitaDataTypes.toTargetType(
					8, short.class
				)
			);
			assertEquals(
				(short) 8,
				EvitaDataTypes.toTargetType(
					(long) 8, short.class
				)
			);
			assertEquals(
				(short) 8,
				EvitaDataTypes.toTargetType(
					new BigDecimal("8"), short.class
				)
			);
			assertEquals(
				(short) 8,
				EvitaDataTypes.toTargetType(
					"8", short.class
				)
			);

			assertArrayEquals(
				new Short[]{8},
				EvitaDataTypes.toTargetType(
					8, Short[].class
				)
			);
			assertArrayEquals(
				new Short[]{8},
				EvitaDataTypes.toTargetType(
					new int[]{8}, Short[].class
				)
			);
			assertArrayEquals(
				new Short[]{8},
				EvitaDataTypes.toTargetType(
					new Integer[]{8}, Short[].class
				)
			);
		}

		@Test
		@DisplayName(
			"should fail to convert incompatible types"
			+ " to Short"
		)
		void shouldFailToConvertToShort() {
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					Currency.getInstance("CZK"),
					Short.class
				)
			);
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					new BigDecimal("8.78"), Short.class
				)
			);
		}
	}

	@Nested
	@DisplayName("Conversion to Integer")
	class ConvertToIntTest {

		@Test
		@DisplayName(
			"should convert various types to Integer"
		)
		void shouldConvertToInt() {
			assertEquals(
				8,
				EvitaDataTypes.toTargetType(
					(byte) 8, Integer.class
				)
			);
			assertEquals(
				8,
				EvitaDataTypes.toTargetType(
					(short) 8, Integer.class
				)
			);
			assertEquals(
				8,
				EvitaDataTypes.toTargetType(
					8, Integer.class
				)
			);
			assertEquals(
				8,
				EvitaDataTypes.toTargetType(
					(long) 8, Integer.class
				)
			);
			assertEquals(
				8,
				EvitaDataTypes.toTargetType(
					new BigDecimal("8"), Integer.class
				)
			);
			assertEquals(
				8,
				EvitaDataTypes.toTargetType(
					"8", Integer.class
				)
			);

			assertEquals(
				8,
				EvitaDataTypes.toTargetType(
					(byte) 8, int.class
				)
			);
			assertEquals(
				8,
				EvitaDataTypes.toTargetType(
					(short) 8, int.class
				)
			);
			assertEquals(
				8,
				EvitaDataTypes.toTargetType(
					8, int.class
				)
			);
			assertEquals(
				8,
				EvitaDataTypes.toTargetType(
					(long) 8, int.class
				)
			);
			assertEquals(
				8,
				EvitaDataTypes.toTargetType(
					new BigDecimal("8"), int.class
				)
			);
			assertEquals(
				8,
				EvitaDataTypes.toTargetType(
					"8", int.class
				)
			);

			assertArrayEquals(
				new Integer[]{8},
				EvitaDataTypes.toTargetType(
					8, Integer[].class
				)
			);
			assertArrayEquals(
				new Integer[]{8},
				EvitaDataTypes.toTargetType(
					new int[]{8}, Integer[].class
				)
			);
			assertArrayEquals(
				new Integer[]{8},
				EvitaDataTypes.toTargetType(
					new Integer[]{8}, Integer[].class
				)
			);
		}

		@Test
		@DisplayName(
			"should fail to convert incompatible types"
			+ " to Integer"
		)
		void shouldFailToConvertToInt() {
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					Currency.getInstance("CZK"),
					Integer.class
				)
			);
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					new BigDecimal("8.78"),
					Integer.class
				)
			);
		}
	}

	@Nested
	@DisplayName("Conversion to Long")
	class ConvertToLongTest {

		@Test
		@DisplayName(
			"should convert various types to Long"
		)
		void shouldConvertToLong() {
			assertEquals(
				8,
				EvitaDataTypes.toTargetType(
					(byte) 8, Long.class
				)
			);
			assertEquals(
				8,
				EvitaDataTypes.toTargetType(
					(short) 8, Long.class
				)
			);
			assertEquals(
				8,
				EvitaDataTypes.toTargetType(
					8, Long.class
				)
			);
			assertEquals(
				8,
				EvitaDataTypes.toTargetType(
					(long) 8, Long.class
				)
			);
			assertEquals(
				8,
				EvitaDataTypes.toTargetType(
					new BigDecimal("8"), Long.class
				)
			);
			assertEquals(
				8,
				EvitaDataTypes.toTargetType(
					"8", Long.class
				)
			);

			assertEquals(
				8,
				EvitaDataTypes.toTargetType(
					(byte) 8, long.class
				)
			);
			assertEquals(
				8,
				EvitaDataTypes.toTargetType(
					(short) 8, long.class
				)
			);
			assertEquals(
				8,
				EvitaDataTypes.toTargetType(
					8, long.class
				)
			);
			assertEquals(
				8,
				EvitaDataTypes.toTargetType(
					(long) 8, long.class
				)
			);
			assertEquals(
				8,
				EvitaDataTypes.toTargetType(
					new BigDecimal("8"), long.class
				)
			);
			assertEquals(
				8,
				EvitaDataTypes.toTargetType(
					"8", long.class
				)
			);

			assertArrayEquals(
				new Long[]{8L},
				EvitaDataTypes.toTargetType(
					8, Long[].class
				)
			);
			assertArrayEquals(
				new Long[]{8L},
				EvitaDataTypes.toTargetType(
					new int[]{8}, Long[].class
				)
			);
			assertArrayEquals(
				new Long[]{8L},
				EvitaDataTypes.toTargetType(
					new Integer[]{8}, Long[].class
				)
			);
		}

		@Test
		@DisplayName(
			"should fail to convert incompatible types"
			+ " to Long"
		)
		void shouldFailToConvertToLong() {
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					Currency.getInstance("CZK"),
					Long.class
				)
			);
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					new BigDecimal("8.78"), Long.class
				)
			);
		}
	}

	@Nested
	@DisplayName("Conversion to BigDecimal")
	class ConvertToBigDecimalTest {

		@Test
		@DisplayName(
			"should convert various types to BigDecimal"
		)
		void shouldConvertToBigDecimal() {
			assertEquals(
				new BigDecimal("8"),
				EvitaDataTypes.toTargetType(
					(byte) 8, BigDecimal.class
				)
			);
			assertEquals(
				new BigDecimal("8"),
				EvitaDataTypes.toTargetType(
					(short) 8, BigDecimal.class
				)
			);
			assertEquals(
				new BigDecimal("8"),
				EvitaDataTypes.toTargetType(
					8, BigDecimal.class
				)
			);
			assertEquals(
				new BigDecimal("8"),
				EvitaDataTypes.toTargetType(
					(long) 8, BigDecimal.class
				)
			);
			assertEquals(
				new BigDecimal("8"),
				EvitaDataTypes.toTargetType(
					new BigDecimal("8"),
					BigDecimal.class
				)
			);
			assertEquals(
				new BigDecimal("8"),
				EvitaDataTypes.toTargetType(
					"8", BigDecimal.class
				)
			);

			assertArrayEquals(
				new BigDecimal[]{new BigDecimal("8")},
				EvitaDataTypes.toTargetType(
					8, BigDecimal[].class
				)
			);
			assertArrayEquals(
				new BigDecimal[]{new BigDecimal("8")},
				EvitaDataTypes.toTargetType(
					new int[]{8}, BigDecimal[].class
				)
			);
			assertArrayEquals(
				new BigDecimal[]{new BigDecimal("8")},
				EvitaDataTypes.toTargetType(
					new Integer[]{8},
					BigDecimal[].class
				)
			);
		}

		@Test
		@DisplayName(
			"should fail to convert incompatible types"
			+ " to BigDecimal"
		)
		void shouldFailToConvertToBigDecimal() {
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					Currency.getInstance("CZK"),
					BigDecimal.class
				)
			);
		}
	}

	@Nested
	@DisplayName("Conversion to Boolean")
	class ConvertToBooleanTest {

		@Test
		@DisplayName(
			"should convert various types to Boolean"
		)
		void shouldConvertToBoolean() {
			assertTrue(
				EvitaDataTypes.toTargetType(
					(byte) 1, Boolean.class
				)
			);
			assertTrue(
				EvitaDataTypes.toTargetType(
					true, Boolean.class
				)
			);
			assertTrue(
				EvitaDataTypes.toTargetType(
					"true", Boolean.class
				)
			);
			assertFalse(
				EvitaDataTypes.toTargetType(
					(byte) 0, Boolean.class
				)
			);
			assertFalse(
				EvitaDataTypes.toTargetType(
					"false", Boolean.class
				)
			);
		}
	}

	@Nested
	@DisplayName("Conversion to Character")
	class ConvertToCharTest {

		@Test
		@DisplayName(
			"should convert various types to Character"
		)
		void shouldConvertToChar() {
			assertEquals(
				(char) 8,
				EvitaDataTypes.toTargetType(
					(byte) 8, Character.class
				)
			);
			assertEquals(
				(char) 8,
				EvitaDataTypes.toTargetType(
					(short) 8, Character.class
				)
			);
			assertEquals(
				(char) 8,
				EvitaDataTypes.toTargetType(
					(char) 8, Character.class
				)
			);
			assertEquals(
				(char) 8,
				EvitaDataTypes.toTargetType(
					(long) 8, Character.class
				)
			);
			assertEquals(
				(char) 8,
				EvitaDataTypes.toTargetType(
					new BigDecimal("8"),
					Character.class
				)
			);
			assertEquals(
				(char) 56,
				EvitaDataTypes.toTargetType(
					"8", Character.class
				)
			);
			assertEquals(
				(char) 65,
				EvitaDataTypes.toTargetType(
					"A", Character.class
				)
			);
		}

		@Test
		@DisplayName(
			"should fail to convert incompatible types"
			+ " to Character"
		)
		void shouldFailToConvertToChar() {
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					Currency.getInstance("CZK"),
					Character.class
				)
			);
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					new BigDecimal("8.78"),
					Character.class
				)
			);
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					"AB", Character.class
				)
			);
		}
	}

	@Nested
	@DisplayName("Conversion to date/time types")
	class ConvertToDateTimeTest {

		@Test
		@DisplayName(
			"should convert to OffsetDateTime"
		)
		void shouldConvertToOffsetDateTime() {
			final OffsetDateTime theDate =
				OffsetDateTime.of(
					2021, 1, 1, 0, 0, 0, 0,
					ZoneOffset.UTC
				);

			assertEquals(
				theDate,
				EvitaDataTypes.toTargetType(
					theDate, OffsetDateTime.class
				)
			);
			assertEquals(
				theDate,
				EvitaDataTypes.toTargetType(
					theDate.toLocalDateTime()
						.format(
							DateTimeFormatter
								.ISO_LOCAL_DATE_TIME
						),
					OffsetDateTime.class
				)
			);
			assertEquals(
				theDate,
				EvitaDataTypes.toTargetType(
					theDate.toLocalDate()
						.format(
							DateTimeFormatter
								.ISO_LOCAL_DATE
						),
					OffsetDateTime.class
				)
			);
		}

		@Test
		@DisplayName(
			"should fail to convert incompatible types"
			+ " to OffsetDateTime"
		)
		void shouldFailToConvertToOffsetDateTime() {
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					Currency.getInstance("CZK"),
					OffsetDateTime.class
				)
			);
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					new BigDecimal("8.78"),
					OffsetDateTime.class
				)
			);
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					"AB", OffsetDateTime.class
				)
			);
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					LocalTime.of(11, 45),
					OffsetDateTime.class
				)
			);
		}

		@Test
		@DisplayName(
			"should convert to LocalDateTime"
		)
		void shouldConvertToLocalDateTime() {
			final OffsetDateTime theDate =
				OffsetDateTime.of(
					2021, 1, 1, 0, 0, 0, 0,
					ZoneOffset.UTC
				);
			final LocalDateTime theLocalDate =
				LocalDateTime.of(
					2021, 1, 1, 0, 0, 0, 0
				);

			assertEquals(
				theLocalDate,
				EvitaDataTypes.toTargetType(
					theDate, LocalDateTime.class
				)
			);
			assertEquals(
				theLocalDate,
				EvitaDataTypes.toTargetType(
					theDate.toLocalDateTime()
						.format(
							DateTimeFormatter
								.ISO_LOCAL_DATE_TIME
						),
					LocalDateTime.class
				)
			);
			assertEquals(
				theLocalDate,
				EvitaDataTypes.toTargetType(
					theDate.toLocalDate()
						.format(
							DateTimeFormatter
								.ISO_LOCAL_DATE
						),
					LocalDateTime.class
				)
			);
		}

		@Test
		@DisplayName(
			"should fail to convert incompatible types"
			+ " to LocalDateTime"
		)
		void shouldFailToConvertToLocalDateTime() {
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					Currency.getInstance("CZK"),
					LocalDateTime.class
				)
			);
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					new BigDecimal("8.78"),
					LocalDateTime.class
				)
			);
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					"AB", LocalDateTime.class
				)
			);
			// Bug fix: was testing OffsetDateTime.class
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					LocalTime.of(11, 45),
					LocalDateTime.class
				)
			);
		}

		@Test
		@DisplayName("should convert to LocalDate")
		void shouldConvertToLocalDate() {
			final OffsetDateTime theDate =
				OffsetDateTime.of(
					2021, 1, 1, 0, 0, 0, 0,
					ZoneOffset.UTC
				);
			final LocalDate theLocalDate =
				LocalDate.of(2021, 1, 1);

			assertEquals(
				theLocalDate,
				EvitaDataTypes.toTargetType(
					theDate, LocalDate.class
				)
			);
			assertEquals(
				theLocalDate,
				EvitaDataTypes.toTargetType(
					theDate.toLocalDateTime()
						.format(
							DateTimeFormatter
								.ISO_LOCAL_DATE_TIME
						),
					LocalDate.class
				)
			);
			assertEquals(
				theLocalDate,
				EvitaDataTypes.toTargetType(
					theDate.toLocalDate()
						.format(
							DateTimeFormatter
								.ISO_LOCAL_DATE
						),
					LocalDate.class
				)
			);
		}

		@Test
		@DisplayName(
			"should fail to convert incompatible types"
			+ " to LocalDate"
		)
		void shouldFailToConvertToLocalDate() {
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					Currency.getInstance("CZK"),
					LocalDate.class
				)
			);
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					new BigDecimal("8.78"),
					LocalDate.class
				)
			);
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					"AB", LocalDate.class
				)
			);
			// Bug fix: was testing OffsetDateTime.class
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					Boolean.TRUE,
					LocalDate.class
				)
			);
		}

		@Test
		@DisplayName("should convert to LocalTime")
		void shouldConvertToLocalTime() {
			final OffsetDateTime theDate =
				OffsetDateTime.of(
					2021, 1, 1, 11, 45, 51, 0,
					ZoneOffset.UTC
				);
			final LocalTime theLocalTime =
				LocalTime.of(11, 45, 51);

			assertEquals(
				theLocalTime,
				EvitaDataTypes.toTargetType(
					theDate, LocalTime.class
				)
			);
			assertEquals(
				theLocalTime,
				EvitaDataTypes.toTargetType(
					theDate.toLocalTime()
						.format(
							DateTimeFormatter
								.ISO_LOCAL_TIME
						),
					LocalTime.class
				)
			);
		}

		@Test
		@DisplayName(
			"should fail to convert incompatible types"
			+ " to LocalTime"
		)
		void shouldFailToConvertToLocalTime() {
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					Currency.getInstance("CZK"),
					LocalTime.class
				)
			);
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					new BigDecimal("8.78"),
					LocalTime.class
				)
			);
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					"AB", LocalTime.class
				)
			);
			// Bug fix: was testing
			// LocalTime->OffsetDateTime, replaced
			// with Boolean->LocalTime
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					Boolean.TRUE,
					LocalTime.class
				)
			);
		}
	}

	@Nested
	@DisplayName("Conversion to DateTimeRange")
	class ConvertToDateTimeRangeTest {

		@Test
		@DisplayName(
			"should convert to DateTimeRange"
		)
		void shouldConvertToDateTimeRange() {
			final OffsetDateTime theDate =
				OffsetDateTime.of(
					2021, 1, 1, 0, 0, 0, 0,
					ZoneOffset.UTC
				);

			assertEquals(
				DateTimeRange.since(theDate),
				EvitaDataTypes.toTargetType(
					DateTimeRange.since(theDate),
					DateTimeRange.class
				)
			);
			assertEquals(
				DateTimeRange.since(theDate),
				EvitaDataTypes.toTargetType(
					"["
						+ theDate.toLocalDateTime()
						.format(
							DateTimeFormatter
								.ISO_LOCAL_DATE_TIME
						)
						+ ",]",
					DateTimeRange.class
				)
			);
			assertEquals(
				DateTimeRange.since(theDate),
				EvitaDataTypes.toTargetType(
					"["
						+ theDate.toLocalDate()
						.format(
							DateTimeFormatter
								.ISO_LOCAL_DATE
						)
						+ ",]",
					DateTimeRange.class
				)
			);
		}

		@Test
		@DisplayName(
			"should fail to convert incompatible types"
			+ " to DateTimeRange"
		)
		void shouldFailToConvertToDateTimeRange() {
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					Currency.getInstance("CZK"),
					DateTimeRange.class
				)
			);
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					new BigDecimal("8.78"),
					DateTimeRange.class
				)
			);
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					"AB", DateTimeRange.class
				)
			);
		}
	}

	@Nested
	@DisplayName("Conversion to NumberRange types")
	class ConvertToNumberRangeTest {

		@Test
		@DisplayName(
			"should convert between NumberRange types"
		)
		void shouldConvertToNumberRange() {
			assertEquals(
				ByteNumberRange.from((byte) 8),
				EvitaDataTypes.toTargetType(
					ByteNumberRange.from((byte) 8),
					ByteNumberRange.class
				)
			);
			assertEquals(
				ByteNumberRange.from((byte) 8),
				EvitaDataTypes.toTargetType(
					ShortNumberRange.from((short) 8),
					ByteNumberRange.class
				)
			);
			assertEquals(
				ByteNumberRange.from((byte) 8),
				EvitaDataTypes.toTargetType(
					IntegerNumberRange.from(8),
					ByteNumberRange.class
				)
			);
			assertEquals(
				ByteNumberRange.from((byte) 8),
				EvitaDataTypes.toTargetType(
					LongNumberRange.from(8L),
					ByteNumberRange.class
				)
			);

			assertEquals(
				ShortNumberRange.from((short) 8),
				EvitaDataTypes.toTargetType(
					ByteNumberRange.from((byte) 8),
					ShortNumberRange.class
				)
			);
			assertEquals(
				ShortNumberRange.from((short) 8),
				EvitaDataTypes.toTargetType(
					ShortNumberRange.from((short) 8),
					ShortNumberRange.class
				)
			);
			assertEquals(
				ShortNumberRange.from((short) 8),
				EvitaDataTypes.toTargetType(
					IntegerNumberRange.from(8),
					ShortNumberRange.class
				)
			);
			assertEquals(
				ShortNumberRange.from((short) 8),
				EvitaDataTypes.toTargetType(
					LongNumberRange.from(8L),
					ShortNumberRange.class
				)
			);

			assertEquals(
				IntegerNumberRange.from(8),
				EvitaDataTypes.toTargetType(
					ByteNumberRange.from((byte) 8),
					IntegerNumberRange.class
				)
			);
			assertEquals(
				IntegerNumberRange.from(8),
				EvitaDataTypes.toTargetType(
					ShortNumberRange.from((short) 8),
					IntegerNumberRange.class
				)
			);
			assertEquals(
				IntegerNumberRange.from(8),
				EvitaDataTypes.toTargetType(
					IntegerNumberRange.from(8),
					IntegerNumberRange.class
				)
			);
			assertEquals(
				IntegerNumberRange.from(8),
				EvitaDataTypes.toTargetType(
					LongNumberRange.from(8L),
					IntegerNumberRange.class
				)
			);

			assertEquals(
				LongNumberRange.from(8L),
				EvitaDataTypes.toTargetType(
					ByteNumberRange.from((byte) 8),
					LongNumberRange.class
				)
			);
			assertEquals(
				LongNumberRange.from(8L),
				EvitaDataTypes.toTargetType(
					ShortNumberRange.from((short) 8),
					LongNumberRange.class
				)
			);
			assertEquals(
				LongNumberRange.from(8L),
				EvitaDataTypes.toTargetType(
					IntegerNumberRange.from(8),
					LongNumberRange.class
				)
			);
			assertEquals(
				LongNumberRange.from(8L),
				EvitaDataTypes.toTargetType(
					LongNumberRange.from(8L),
					LongNumberRange.class
				)
			);

			assertEquals(
				BigDecimalNumberRange.from(
					new BigDecimal("8")
				),
				EvitaDataTypes.toTargetType(
					ByteNumberRange.from((byte) 8),
					BigDecimalNumberRange.class
				)
			);
			assertEquals(
				BigDecimalNumberRange.from(
					new BigDecimal("8")
				),
				EvitaDataTypes.toTargetType(
					ShortNumberRange.from((short) 8),
					BigDecimalNumberRange.class
				)
			);
			assertEquals(
				BigDecimalNumberRange.from(
					new BigDecimal("8")
				),
				EvitaDataTypes.toTargetType(
					IntegerNumberRange.from(8),
					BigDecimalNumberRange.class
				)
			);
			assertEquals(
				BigDecimalNumberRange.from(
					new BigDecimal("8")
				),
				EvitaDataTypes.toTargetType(
					LongNumberRange.from(8L),
					BigDecimalNumberRange.class
				)
			);

			assertEquals(
				ByteNumberRange.from((byte) 8),
				EvitaDataTypes.toTargetType(
					"[8,]", ByteNumberRange.class
				)
			);
			assertEquals(
				ShortNumberRange.from((short) 8),
				EvitaDataTypes.toTargetType(
					"[8,]", ShortNumberRange.class
				)
			);
			assertEquals(
				IntegerNumberRange.from(8),
				EvitaDataTypes.toTargetType(
					"[8,]", IntegerNumberRange.class
				)
			);
			assertEquals(
				LongNumberRange.from(8L),
				EvitaDataTypes.toTargetType(
					"[8,]", LongNumberRange.class
				)
			);
			assertEquals(
				BigDecimalNumberRange.from(
					new BigDecimal(8)
				),
				EvitaDataTypes.toTargetType(
					"[8,]",
					BigDecimalNumberRange.class
				)
			);

			assertEquals(
				BigDecimalNumberRange.to(
					new BigDecimal("8.78"), 2
				),
				EvitaDataTypes.toTargetType(
					"[,8.78]",
					BigDecimalNumberRange.class, 2
				)
			);
		}

		@Test
		@DisplayName(
			"should fail to convert incompatible types"
			+ " to NumberRange"
		)
		void shouldFailToConvertToNumberRange() {
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					Currency.getInstance("CZK"),
					IntegerNumberRange.class
				)
			);
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					new BigDecimal("8.78"),
					IntegerNumberRange.class
				)
			);
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					"AB", IntegerNumberRange.class
				)
			);
		}
	}

	@Nested
	@DisplayName("Conversion to Locale")
	class ConvertToLocaleTest {

		@Test
		@DisplayName(
			"should convert to Locale"
		)
		void shouldConvertToLocale() {
			assertEquals(
				Locale.ENGLISH,
				EvitaDataTypes.toTargetType(
					Locale.ENGLISH, Locale.class
				)
			);
			assertEquals(
				Locale.ENGLISH,
				EvitaDataTypes.toTargetType(
					"en", Locale.class
				)
			);
			assertEquals(
				new Locale("en", "US"),
				EvitaDataTypes.toTargetType(
					"en-US", Locale.class
				)
			);
		}

		@Test
		@DisplayName(
			"should fail to convert incompatible values"
			+ " to Locale"
		)
		void shouldFailToConvertToLocale() {
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					"WHATEVER", Locale.class
				)
			);
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					new BigDecimal("8.78"),
					Locale.class
				)
			);
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					"", Locale.class
				)
			);
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					"-cs", Locale.class
				)
			);
		}
	}

	@Nested
	@DisplayName("Conversion to UUID")
	class ConvertToUuidTest {

		@Test
		@DisplayName("should convert to UUID")
		void shouldConvertToUuid() {
			final UUID uuid = UUID.randomUUID();

			assertEquals(
				uuid,
				EvitaDataTypes.toTargetType(
					uuid, UUID.class
				)
			);
			assertEquals(
				uuid,
				EvitaDataTypes.toTargetType(
					uuid.toString(), UUID.class
				)
			);
		}

		@Test
		@DisplayName(
			"should fail to convert incompatible values"
			+ " to UUID"
		)
		void shouldFailToConvertToUuid() {
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					"WHATEVER", UUID.class
				)
			);
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					new BigDecimal("8.78"), UUID.class
				)
			);
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					"", UUID.class
				)
			);
		}
	}

	@Nested
	@DisplayName("BigDecimal string formatting")
	class BigDecimalFormattingTest {

		@Test
		@DisplayName(
			"should convert string BigDecimal values"
			+ " properly"
		)
		void shouldConvertStringBigDecimalValues() {
			final String integerValueStr = "1";
			final BigDecimal integerValue =
				BigDecimal.valueOf(1);
			assertEquals(
				integerValue,
				EvitaDataTypes.toTargetType(
					integerValueStr, BigDecimal.class
				)
			);

			final String positiveStr = "+3.14";
			final BigDecimal positiveValue =
				BigDecimal.valueOf(3.14);
			assertEquals(
				positiveValue,
				EvitaDataTypes.toTargetType(
					positiveStr, BigDecimal.class
				)
			);

			final String negativeStr = "-2.5";
			final BigDecimal negativeValue =
				BigDecimal.valueOf(-2.5);
			assertEquals(
				negativeValue,
				EvitaDataTypes.toTargetType(
					negativeStr, BigDecimal.class
				)
			);

			final String zeroLengthStr = ".5";
			final BigDecimal zeroLengthValue =
				BigDecimal.valueOf(0.5);
			assertEquals(
				zeroLengthValue,
				EvitaDataTypes.toTargetType(
					zeroLengthStr, BigDecimal.class
				)
			);

			final String exponentialStr = "2.5E8";
			final BigDecimal exponentialValue =
				BigDecimal.valueOf(2.5e8);
			assertEquals(
				exponentialValue,
				EvitaDataTypes.toTargetType(
					exponentialStr, BigDecimal.class
				)
			);

			final String zeroExponentStr = "2.5e0";
			final BigDecimal zeroExponentValue =
				BigDecimal.valueOf(2.5);
			assertEquals(
				zeroExponentValue,
				EvitaDataTypes.toTargetType(
					zeroExponentStr, BigDecimal.class
				)
			);
		}

		@Test
		@DisplayName(
			"should format BigDecimal values properly"
		)
		void shouldFormatBigDecimalValues() {
			assertEquals(
				"1",
				formatValue(new BigDecimal("1"))
			);
			assertEquals(
				"3.14",
				formatValue(new BigDecimal("+3.14"))
			);
			assertEquals(
				"-2.5",
				formatValue(new BigDecimal("-2.5"))
			);
			assertEquals(
				"0.5",
				formatValue(new BigDecimal(".5"))
			);
			assertEquals(
				"2.5e8",
				formatValue(new BigDecimal("2.5E8"))
			);
			assertEquals(
				"-2.5e-8",
				formatValue(new BigDecimal("-2.5E-8"))
			);
			assertEquals(
				"2.5",
				formatValue(new BigDecimal("2.5e0"))
			);
		}
	}

	@Nested
	@DisplayName("Supported types")
	class SupportedTypesTest {

		@Test
		@DisplayName(
			"should return non-empty set of supported"
			+ " types"
		)
		void shouldReturnNonEmptySetOfSupportedTypes() {
			final Set<Class<?>> types =
				EvitaDataTypes.getSupportedDataTypes();

			assertNotNull(types);
			assertFalse(types.isEmpty());
		}

		@Test
		@DisplayName(
			"should contain all expected types"
		)
		void shouldContainAllExpectedTypes() {
			final Set<Class<?>> types =
				EvitaDataTypes.getSupportedDataTypes();

			assertTrue(types.contains(String.class));
			assertTrue(types.contains(Byte.class));
			assertTrue(types.contains(byte.class));
			assertTrue(types.contains(Short.class));
			assertTrue(types.contains(short.class));
			assertTrue(types.contains(Integer.class));
			assertTrue(types.contains(int.class));
			assertTrue(types.contains(Long.class));
			assertTrue(types.contains(long.class));
			assertTrue(types.contains(Boolean.class));
			assertTrue(types.contains(boolean.class));
			assertTrue(types.contains(Character.class));
			assertTrue(types.contains(char.class));
			assertTrue(
				types.contains(BigDecimal.class)
			);
			assertTrue(
				types.contains(OffsetDateTime.class)
			);
			assertTrue(
				types.contains(LocalDateTime.class)
			);
			assertTrue(
				types.contains(LocalDate.class)
			);
			assertTrue(
				types.contains(LocalTime.class)
			);
			assertTrue(
				types.contains(DateTimeRange.class)
			);
			assertTrue(
				types.contains(
					BigDecimalNumberRange.class
				)
			);
			assertTrue(
				types.contains(
					LongNumberRange.class
				)
			);
			assertTrue(
				types.contains(
					IntegerNumberRange.class
				)
			);
			assertTrue(
				types.contains(
					ShortNumberRange.class
				)
			);
			assertTrue(
				types.contains(
					ByteNumberRange.class
				)
			);
			assertTrue(types.contains(Locale.class));
			assertTrue(
				types.contains(Currency.class)
			);
			assertTrue(types.contains(UUID.class));
			assertTrue(
				types.contains(Predecessor.class)
			);
			assertTrue(
				types.contains(
					ReferencedEntityPredecessor.class
				)
			);
		}

		@Test
		@DisplayName(
			"should return unmodifiable set"
		)
		void shouldReturnUnmodifiableSet() {
			final Set<Class<?>> types =
				EvitaDataTypes.getSupportedDataTypes();

			assertThrows(
				UnsupportedOperationException.class,
				() -> types.add(Object.class)
			);
			assertThrows(
				UnsupportedOperationException.class,
				() -> types.remove(String.class)
			);
		}

		@Test
		@DisplayName(
			"should return true for all supported"
			+ " types"
		)
		void shouldReturnTrueForAllSupportedTypes() {
			assertTrue(
				EvitaDataTypes.isSupportedType(
					String.class
				)
			);
			assertTrue(
				EvitaDataTypes.isSupportedType(
					Integer.class
				)
			);
			assertTrue(
				EvitaDataTypes.isSupportedType(
					int.class
				)
			);
			assertTrue(
				EvitaDataTypes.isSupportedType(
					BigDecimal.class
				)
			);
			assertTrue(
				EvitaDataTypes.isSupportedType(
					OffsetDateTime.class
				)
			);
			assertTrue(
				EvitaDataTypes.isSupportedType(
					UUID.class
				)
			);
			assertTrue(
				EvitaDataTypes.isSupportedType(
					Predecessor.class
				)
			);
		}

		@Test
		@DisplayName(
			"should return false for unsupported types"
		)
		void shouldReturnFalseForUnsupportedTypes() {
			assertFalse(
				EvitaDataTypes.isSupportedType(
					Float.class
				)
			);
			assertFalse(
				EvitaDataTypes.isSupportedType(
					Double.class
				)
			);
			assertFalse(
				EvitaDataTypes.isSupportedType(
					Object.class
				)
			);
		}

		@Test
		@DisplayName(
			"should return true for supported array"
			+ " types"
		)
		void shouldReturnTrueForSupportedArrayTypes() {
			assertTrue(
				EvitaDataTypes.isSupportedTypeOrItsArray(
					String[].class
				)
			);
			assertTrue(
				EvitaDataTypes.isSupportedTypeOrItsArray(
					Integer[].class
				)
			);
		}

		@Test
		@DisplayName(
			"should return false for unsupported array"
			+ " types"
		)
		void shouldReturnFalseForUnsupportedArrayTypes() {
			assertFalse(
				EvitaDataTypes.isSupportedTypeOrItsArray(
					Float[].class
				)
			);
		}

		@Test
		@DisplayName(
			"should recognize supported enums"
		)
		void shouldRecognizeSupportedEnums() {
			// Scope is annotated with @SupportedEnum
			assertTrue(
				EvitaDataTypes
					.isSupportedTypeOrItsArrayOrEnum(
						Scope.class
					)
			);
			assertTrue(
				EvitaDataTypes
					.isSupportedTypeOrItsArrayOrEnum(
						Scope[].class
					)
			);
		}

		@Test
		@DisplayName(
			"should return true for enum type"
		)
		void shouldReturnTrueForEnumType() {
			assertTrue(
				EvitaDataTypes.isEnumOrArrayOfEnums(
					Scope.class
				)
			);
		}

		@Test
		@DisplayName(
			"should return true for enum array type"
		)
		void shouldReturnTrueForEnumArrayType() {
			assertTrue(
				EvitaDataTypes.isEnumOrArrayOfEnums(
					Scope[].class
				)
			);
		}

		@Test
		@DisplayName(
			"should return false for non-enum type"
		)
		void shouldReturnFalseForNonEnumType() {
			assertFalse(
				EvitaDataTypes.isEnumOrArrayOfEnums(
					String.class
				)
			);
		}
	}

	@Nested
	@DisplayName("Conversion to supported type")
	class ToSupportedTypeTest {

		@Test
		@DisplayName(
			"should return null for null input"
		)
		void shouldReturnNullForNullInput() {
			assertNull(
				EvitaDataTypes.toSupportedType(null)
			);
		}

		@Test
		@DisplayName(
			"should return supported types unchanged"
		)
		void shouldReturnSupportedTypesUnchanged() {
			final String string = "hello";
			assertSame(
				string,
				EvitaDataTypes.toSupportedType(string)
			);

			final Integer integer = 42;
			assertSame(
				integer,
				EvitaDataTypes.toSupportedType(integer)
			);

			final BigDecimal decimal =
				new BigDecimal("3.14");
			assertSame(
				decimal,
				EvitaDataTypes.toSupportedType(decimal)
			);
		}

		@Test
		@DisplayName(
			"should convert Float to BigDecimal"
		)
		void shouldConvertFloatToBigDecimal() {
			final Float input = 3.14f;

			final Object result =
				EvitaDataTypes.toSupportedType(input);

			assertInstanceOf(BigDecimal.class, result);
			assertEquals(
				new BigDecimal("3.14"),
				result
			);
		}

		@Test
		@DisplayName(
			"should convert Double to BigDecimal"
		)
		void shouldConvertDoubleToBigDecimal() {
			final Double input = 3.14;

			final Object result =
				EvitaDataTypes.toSupportedType(input);

			assertInstanceOf(BigDecimal.class, result);
			assertEquals(
				new BigDecimal("3.14"),
				result
			);
		}

		@Test
		@DisplayName(
			"should convert LocalDateTime to"
			+ " OffsetDateTime"
		)
		void shouldConvertLocalDateTimeToOffsetDateTime() {
			final LocalDateTime input =
				LocalDateTime.of(
					2021, 6, 15, 10, 30
				);

			final Object result =
				EvitaDataTypes.toSupportedType(input);

			assertInstanceOf(OffsetDateTime.class, result);
			final OffsetDateTime expected =
				input.atOffset(ZoneOffset.UTC);
			assertEquals(expected, result);
		}

		@Test
		@DisplayName(
			"should throw for unsupported type"
		)
		void shouldThrowForUnsupportedType() {
			final HashMap<String, String> map =
				new HashMap<>();

			assertThrows(
				UnsupportedDataTypeException.class,
				() -> EvitaDataTypes.toSupportedType(
					map
				)
			);
		}

		@Test
		@DisplayName(
			"should pass through @SupportedEnum"
		)
		void shouldPassThroughSupportedEnum() {
			// Scope is annotated with @SupportedEnum
			assertSame(
				Scope.LIVE,
				EvitaDataTypes.toSupportedType(
					Scope.LIVE
				)
			);
		}

		@Test
		@DisplayName(
			"should convert non-@SupportedEnum to"
			+ " String"
		)
		void shouldConvertNonSupportedEnumToString() {
			// ClassifierType is NOT annotated
			// with @SupportedEnum
			final Object result =
				EvitaDataTypes.toSupportedType(
					ClassifierType.CATALOG
				);

			assertEquals("CATALOG", result);
		}
	}

	@Nested
	@DisplayName("Size estimation")
	class EstimateSizeTest {

		@Test
		@DisplayName(
			"should return zero for null"
		)
		void shouldReturnZeroForNull() {
			assertEquals(
				0,
				EvitaDataTypes.estimateSize(null)
			);
		}

		@Test
		@DisplayName(
			"should estimate size of String"
		)
		void shouldEstimateSizeOfString() {
			final String value = "hello";
			final int size =
				EvitaDataTypes.estimateSize(value);

			assertEquals(
				MemoryMeasuringConstants
					.computeStringSize(value),
				size
			);
		}

		@Test
		@DisplayName(
			"should estimate size of Byte"
		)
		void shouldEstimateSizeOfByte() {
			assertEquals(
				MemoryMeasuringConstants
					.OBJECT_HEADER_SIZE
					+ MemoryMeasuringConstants
					.BYTE_SIZE,
				EvitaDataTypes.estimateSize((byte) 1)
			);
		}

		@Test
		@DisplayName(
			"should estimate size of Short"
		)
		void shouldEstimateSizeOfShort() {
			assertEquals(
				MemoryMeasuringConstants
					.OBJECT_HEADER_SIZE
					+ MemoryMeasuringConstants
					.SMALL_SIZE,
				EvitaDataTypes.estimateSize(
					(short) 1
				)
			);
		}

		@Test
		@DisplayName(
			"should estimate size of Integer"
		)
		void shouldEstimateSizeOfInteger() {
			assertEquals(
				MemoryMeasuringConstants
					.OBJECT_HEADER_SIZE
					+ MemoryMeasuringConstants
					.INT_SIZE,
				EvitaDataTypes.estimateSize(1)
			);
		}

		@Test
		@DisplayName(
			"should estimate size of Long"
		)
		void shouldEstimateSizeOfLong() {
			assertEquals(
				MemoryMeasuringConstants
					.OBJECT_HEADER_SIZE
					+ MemoryMeasuringConstants
					.LONG_SIZE,
				EvitaDataTypes.estimateSize(1L)
			);
		}

		@Test
		@DisplayName(
			"should estimate size of Boolean"
		)
		void shouldEstimateSizeOfBoolean() {
			assertEquals(
				MemoryMeasuringConstants
					.OBJECT_HEADER_SIZE
					+ MemoryMeasuringConstants
					.BYTE_SIZE,
				EvitaDataTypes.estimateSize(true)
			);
		}

		@Test
		@DisplayName(
			"should estimate size of Character"
		)
		void shouldEstimateSizeOfCharacter() {
			assertEquals(
				MemoryMeasuringConstants
					.OBJECT_HEADER_SIZE
					+ MemoryMeasuringConstants
					.CHAR_SIZE,
				EvitaDataTypes.estimateSize('A')
			);
		}

		@Test
		@DisplayName(
			"should estimate size of BigDecimal"
		)
		void shouldEstimateSizeOfBigDecimal() {
			assertEquals(
				MemoryMeasuringConstants
					.OBJECT_HEADER_SIZE
					+ MemoryMeasuringConstants
					.BIG_DECIMAL_SIZE,
				EvitaDataTypes.estimateSize(
					new BigDecimal("1.23")
				)
			);
		}

		@Test
		@DisplayName(
			"should estimate size of OffsetDateTime"
		)
		void shouldEstimateSizeOfOffsetDateTime() {
			final int expected =
				MemoryMeasuringConstants
					.OBJECT_HEADER_SIZE
					+ MemoryMeasuringConstants
					.LOCAL_DATE_TIME_SIZE
					+ MemoryMeasuringConstants
					.REFERENCE_SIZE;

			assertEquals(
				expected,
				EvitaDataTypes.estimateSize(
					OffsetDateTime.now()
				)
			);
		}

		@Test
		@DisplayName(
			"should estimate size of LocalDateTime"
		)
		void shouldEstimateSizeOfLocalDateTime() {
			assertEquals(
				MemoryMeasuringConstants
					.OBJECT_HEADER_SIZE
					+ MemoryMeasuringConstants
					.LOCAL_DATE_TIME_SIZE,
				EvitaDataTypes.estimateSize(
					LocalDateTime.now()
				)
			);
		}

		@Test
		@DisplayName(
			"should estimate size of LocalDate"
		)
		void shouldEstimateSizeOfLocalDate() {
			assertEquals(
				MemoryMeasuringConstants
					.OBJECT_HEADER_SIZE
					+ MemoryMeasuringConstants
					.LOCAL_DATE_SIZE,
				EvitaDataTypes.estimateSize(
					LocalDate.now()
				)
			);
		}

		@Test
		@DisplayName(
			"should estimate size of LocalTime"
		)
		void shouldEstimateSizeOfLocalTime() {
			assertEquals(
				MemoryMeasuringConstants
					.OBJECT_HEADER_SIZE
					+ MemoryMeasuringConstants
					.LOCAL_TIME_SIZE,
				EvitaDataTypes.estimateSize(
					LocalTime.now()
				)
			);
		}

		@Test
		@DisplayName(
			"should estimate size of DateTimeRange"
		)
		void shouldEstimateSizeOfDateTimeRange() {
			final OffsetDateTime now =
				OffsetDateTime.now();
			final DateTimeRange range =
				DateTimeRange.between(now, now);

			final int expected =
				MemoryMeasuringConstants
					.OBJECT_HEADER_SIZE
					+ 2 * (
					MemoryMeasuringConstants
						.OBJECT_HEADER_SIZE
						+ MemoryMeasuringConstants
						.LOCAL_DATE_TIME_SIZE
						+ MemoryMeasuringConstants
						.REFERENCE_SIZE
				)
					+ 2 * MemoryMeasuringConstants
					.LONG_SIZE;

			assertEquals(
				expected,
				EvitaDataTypes.estimateSize(range)
			);
		}

		@Test
		@DisplayName(
			"should estimate size of NumberRange"
			+ " (from-only)"
		)
		void shouldEstimateSizeOfNumberRangeFromOnly() {
			final LongNumberRange range =
				LongNumberRange.from(10L);
			final int size =
				EvitaDataTypes.estimateSize(range);

			assertTrue(
				size > 0,
				"NumberRange from-only size should"
					+ " be positive"
			);
		}

		@Test
		@DisplayName(
			"should estimate size of NumberRange"
			+ " (to-only)"
		)
		void shouldEstimateSizeOfNumberRangeToOnly() {
			final LongNumberRange range =
				LongNumberRange.to(10L);
			final int size =
				EvitaDataTypes.estimateSize(range);

			assertTrue(
				size > 0,
				"NumberRange to-only size should"
					+ " be positive"
			);
		}

		@Test
		@DisplayName(
			"should estimate size of NumberRange"
			+ " (both bounds)"
		)
		void shouldEstimateSizeOfNumberRangeBothBounds() {
			final LongNumberRange range =
				LongNumberRange.between(5L, 10L);
			final int size =
				EvitaDataTypes.estimateSize(range);

			assertTrue(
				size > 0,
				"NumberRange between size should"
					+ " be positive"
			);
		}

		@Test
		@DisplayName(
			"should return zero for Locale"
		)
		void shouldReturnZeroForLocale() {
			assertEquals(
				0,
				EvitaDataTypes.estimateSize(
					Locale.ENGLISH
				)
			);
		}

		@Test
		@DisplayName(
			"should return zero for Currency"
		)
		void shouldReturnZeroForCurrency() {
			assertEquals(
				0,
				EvitaDataTypes.estimateSize(
					Currency.getInstance("USD")
				)
			);
		}

		@Test
		@DisplayName(
			"should estimate size of UUID"
		)
		void shouldEstimateSizeOfUUID() {
			assertEquals(
				MemoryMeasuringConstants
					.OBJECT_HEADER_SIZE
					+ 2 * MemoryMeasuringConstants
					.LONG_SIZE,
				EvitaDataTypes.estimateSize(
					UUID.randomUUID()
				)
			);
		}

		@Test
		@DisplayName(
			"should estimate size of Predecessor"
		)
		void shouldEstimateSizeOfPredecessor() {
			assertEquals(
				MemoryMeasuringConstants
					.OBJECT_HEADER_SIZE
					+ MemoryMeasuringConstants
					.INT_SIZE,
				EvitaDataTypes.estimateSize(
					new Predecessor(42)
				)
			);
		}

		@Test
		@DisplayName(
			"should estimate size of"
			+ " ReferencedEntityPredecessor"
		)
		void shouldEstimateSizeOfRefEntityPredecessor() {
			assertEquals(
				MemoryMeasuringConstants
					.OBJECT_HEADER_SIZE
					+ MemoryMeasuringConstants
					.INT_SIZE,
				EvitaDataTypes.estimateSize(
					new ReferencedEntityPredecessor(42)
				)
			);
		}

		@Test
		@DisplayName(
			"should return zero for Enum"
		)
		void shouldReturnZeroForEnum() {
			assertEquals(
				0,
				EvitaDataTypes.estimateSize(
					Scope.LIVE
				)
			);
		}

		@Test
		@DisplayName(
			"should estimate size of array"
		)
		void shouldEstimateSizeOfArray() {
			final Integer[] array = {1, 2, 3};
			final int size =
				EvitaDataTypes.estimateSize(array);

			// array base size + 3 elements
			// (reference + int each)
			assertTrue(
				size > 0,
				"Array size should be positive"
			);
			final int expectedBase =
				MemoryMeasuringConstants
					.ARRAY_BASE_SIZE
					+ 3
					* MemoryMeasuringConstants
					.REFERENCE_SIZE;
			assertTrue(
				size >= expectedBase,
				"Array size should include base"
					+ " overhead"
			);
		}

		@Test
		@DisplayName(
			"should throw for unsupported type"
		)
		void shouldThrowForUnsupportedType() {
			assertThrows(
				UnsupportedDataTypeException.class,
				() -> EvitaDataTypes.estimateSize(
					new HashMap<>()
				)
			);
		}
	}

	@Nested
	@DisplayName("Conversion to String")
	class ConvertToStringTest {

		@Test
		@DisplayName(
			"should convert Integer to String"
		)
		void shouldConvertIntegerToString() {
			assertEquals(
				"42",
				EvitaDataTypes.toTargetType(
					42, String.class
				)
			);
		}

		@Test
		@DisplayName(
			"should convert BigDecimal to String"
		)
		void shouldConvertBigDecimalToString() {
			assertEquals(
				"3.14",
				EvitaDataTypes.toTargetType(
					new BigDecimal("3.14"),
					String.class
				)
			);
		}

		@Test
		@DisplayName(
			"should convert Boolean to String"
		)
		void shouldConvertBooleanToString() {
			assertEquals(
				"true",
				EvitaDataTypes.toTargetType(
					true, String.class
				)
			);
		}

		@Test
		@DisplayName(
			"should convert null to null"
		)
		void shouldConvertNullToNull() {
			assertNull(
				EvitaDataTypes.toTargetType(
					null, String.class
				)
			);
		}

		@Test
		@DisplayName(
			"should convert array to String array"
		)
		void shouldConvertArrayToStringArray() {
			assertArrayEquals(
				new String[]{"1", "2"},
				EvitaDataTypes.toTargetType(
					new Integer[]{1, 2},
					String[].class
				)
			);
		}
	}

	@Nested
	@DisplayName("Conversion to Currency")
	class ConvertToCurrencyTest {

		@Test
		@DisplayName(
			"should convert String to Currency"
		)
		void shouldConvertStringToCurrency() {
			assertSame(
				Currency.getInstance("USD"), EvitaDataTypes.toTargetType(
					"USD", Currency.class
				)
			);
		}

		@Test
		@DisplayName(
			"should pass through Currency instance"
		)
		void shouldPassThroughCurrencyInstance() {
			final Currency usd =
				Currency.getInstance("USD");

			assertSame(
				usd,
				EvitaDataTypes.toTargetType(
					usd, Currency.class
				)
			);
		}

		@Test
		@DisplayName(
			"should throw for invalid currency code"
		)
		void shouldThrowForInvalidCurrencyCode() {
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					"INVALID", Currency.class
				)
			);
		}

		@Test
		@DisplayName(
			"should throw for Number to Currency"
		)
		void shouldThrowForNumberToCurrency() {
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					42, Currency.class
				)
			);
		}
	}

	@Nested
	@DisplayName("Conversion to Enum")
	class ConvertToEnumTest {

		@Test
		@DisplayName(
			"should convert String to enum"
		)
		void shouldConvertStringToEnum() {
			assertEquals(
				Scope.LIVE,
				EvitaDataTypes.toTargetType(
					"LIVE", Scope.class
				)
			);
		}

		@Test
		@DisplayName(
			"should pass through enum instance"
		)
		void shouldPassThroughEnumInstance() {
			assertSame(
				Scope.LIVE,
				EvitaDataTypes.toTargetType(
					Scope.LIVE, Scope.class
				)
			);
		}

		@Test
		@DisplayName(
			"should convert String to another enum"
			+ " value"
		)
		void shouldConvertStringToAnotherEnumValue() {
			assertEquals(
				Scope.ARCHIVED,
				EvitaDataTypes.toTargetType(
					"ARCHIVED", Scope.class
				)
			);
		}
	}

	@Nested
	@DisplayName("Null and edge cases")
	class NullAndEdgeCaseTest {

		@Test
		@DisplayName(
			"should return null for null input"
			+ " to any type"
		)
		void shouldReturnNullForNullInputToAnyType() {
			assertNull(
				EvitaDataTypes.toTargetType(
					null, Integer.class
				)
			);
		}

		@Test
		@DisplayName(
			"should return same object when already"
			+ " correct type"
		)
		void shouldReturnSameObjectWhenCorrectType() {
			final Integer value = 42;

			assertSame(
				value,
				EvitaDataTypes.toTargetType(
					value, Integer.class
				)
			);
		}

		@Test
		@DisplayName(
			"should throw for unsupported requested"
			+ " type"
		)
		void shouldThrowForUnsupportedRequestedType() {
			assertThrows(
				IllegalArgumentException.class,
				() -> EvitaDataTypes.toTargetType(
					"test", Float.class
				)
			);
		}
	}

	@Nested
	@DisplayName("Regression bug fixes")
	class KnownBugTests {

		@Test
		@DisplayName(
			"Float and Double can be converted"
			+ " to BigDecimal"
		)
		void shouldConvertFloatAndDoubleToBigDecimal() {
			final BigDecimal fromFloat =
				EvitaDataTypes.toTargetType(
					3.14f, BigDecimal.class
				);
			assertNotNull(fromFloat);
			assertEquals(
				new BigDecimal("3.14"), fromFloat
			);

			final BigDecimal fromDouble =
				EvitaDataTypes.toTargetType(
					3.14d, BigDecimal.class
				);
			assertNotNull(fromDouble);
			assertEquals(
				new BigDecimal("3.14"), fromDouble
			);
		}

		@Test
		@DisplayName(
			"NaN and Infinity throw"
			+ " UnsupportedDataTypeException"
		)
		void shouldThrowUnsupportedDataTypeForNaN() {
			assertThrows(
				UnsupportedDataTypeException.class,
				() -> EvitaDataTypes.toSupportedType(
					Float.NaN
				)
			);
			assertThrows(
				UnsupportedDataTypeException.class,
				() -> EvitaDataTypes.toSupportedType(
					Float.POSITIVE_INFINITY
				)
			);
			assertThrows(
				UnsupportedDataTypeException.class,
				() -> EvitaDataTypes.toSupportedType(
					Double.NaN
				)
			);
			assertThrows(
				UnsupportedDataTypeException.class,
				() -> EvitaDataTypes.toSupportedType(
					Double.NEGATIVE_INFINITY
				)
			);
		}

		@Test
		@DisplayName(
			"Empty DateTimeRange [,] throws"
			+ " InconvertibleDataTypeException"
		)
		void shouldThrowInconvertibleForEmptyDateTimeRange() {
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					"[,]", DateTimeRange.class
				)
			);
		}

		@Test
		@DisplayName(
			"Empty NumberRange [,] throws"
			+ " InconvertibleDataTypeException"
		)
		void shouldThrowInconvertibleForEmptyNumberRange() {
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					"[,]", LongNumberRange.class
				)
			);
		}

		@Test
		@DisplayName(
			"Null array elements are preserved"
			+ " during conversion"
		)
		void shouldPreserveNullArrayElements() {
			final Integer[] result =
				EvitaDataTypes.toTargetType(
					new String[]{"42", null, "7"},
					Integer[].class
				);
			assertNotNull(result);
			assertEquals(3, result.length);
			assertEquals(42, result[0]);
			assertNull(result[1]);
			assertEquals(7, result[2]);
		}

		@Test
		@DisplayName(
			"Numbers in valid char range (0-65535)"
			+ " convert to Character"
		)
		void shouldConvertValidCharRangeNumbers() {
			final Character result =
				EvitaDataTypes.toTargetType(
					200, Character.class
				);
			assertEquals((char) 200, result);

			final Character maxChar =
				EvitaDataTypes.toTargetType(
					65535, Character.class
				);
			assertEquals((char) 65535, maxChar);

			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					-1, Character.class
				)
			);
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					65536, Character.class
				)
			);
		}

		@Test
		@DisplayName(
			"Invalid enum name throws"
			+ " InconvertibleDataTypeException"
		)
		void shouldThrowInconvertibleForInvalidEnumName() {
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					"NONEXISTENT_VALUE",
					Scope.class
				)
			);
		}

		@Test
		@DisplayName(
			"getElementSize returns correct sizes"
			+ " for primitive types"
		)
		void shouldReturnCorrectElementSizes() {
			assertEquals(
				MemoryMeasuringConstants.BYTE_SIZE,
				MemoryMeasuringConstants
					.getElementSize(byte.class)
			);
			assertEquals(
				MemoryMeasuringConstants.SMALL_SIZE,
				MemoryMeasuringConstants
					.getElementSize(short.class)
			);
			assertEquals(
				MemoryMeasuringConstants.INT_SIZE,
				MemoryMeasuringConstants
					.getElementSize(int.class)
			);
			assertEquals(
				MemoryMeasuringConstants.LONG_SIZE,
				MemoryMeasuringConstants
					.getElementSize(long.class)
			);
			assertEquals(
				MemoryMeasuringConstants.CHAR_SIZE,
				MemoryMeasuringConstants
					.getElementSize(char.class)
			);
			assertEquals(
				MemoryMeasuringConstants
					.REFERENCE_SIZE,
				MemoryMeasuringConstants
					.getElementSize(String.class)
			);
		}
	}

}
