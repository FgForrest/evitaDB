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
import java.util.Locale;
import java.util.UUID;

import static io.evitadb.dataType.EvitaDataTypes.formatValue;
import static io.evitadb.dataType.EvitaDataTypes.getWrappingPrimitiveClass;
import static io.evitadb.dataType.EvitaDataTypes.toWrappedForm;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests verifying {@link EvitaDataTypes} contract including value
 * formatting, primitive wrapper resolution, and type conversion
 * for all supported data types.
 *
 * @author Jan Novotn&#253; (novotny@fg.cz), FG Forrest a.s. (c) 2021
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
	}

	@Nested
	@DisplayName("Wrapper class resolution")
	class WrapperClassTest {

		@Test
		@DisplayName(
			"should return wrapper class for primitives"
		)
		void shouldReturnWrapperClass() {
			assertEquals(
				Integer.class,
				getWrappingPrimitiveClass(int.class)
			);
			assertEquals(
				Character.class,
				getWrappingPrimitiveClass(char.class)
			);
		}

		@Test
		@DisplayName(
			"should convert to wrapped form including"
			+ " arrays"
		)
		void shouldConvertToWrapperClass() {
			assertEquals(
				Integer.class,
				toWrappedForm(int.class)
			);
			assertEquals(
				Integer[].class,
				toWrappedForm(int[].class)
			);
			assertEquals(
				Character.class,
				toWrappedForm(char.class)
			);
			assertEquals(
				Character[].class,
				toWrappedForm(char[].class)
			);
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
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					LocalTime.of(11, 45),
					OffsetDateTime.class
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
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					LocalTime.of(11, 45),
					OffsetDateTime.class
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
			assertThrows(
				InconvertibleDataTypeException.class,
				() -> EvitaDataTypes.toTargetType(
					LocalTime.of(11, 45),
					OffsetDateTime.class
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

}
