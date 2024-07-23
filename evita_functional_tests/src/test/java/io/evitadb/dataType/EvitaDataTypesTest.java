/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test verifies {@link EvitaDataTypes} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class EvitaDataTypesTest {

	@Test
	void shouldFormatByte() {
		assertEquals("5", formatValue((byte)5));
	}

	@Test
	void shouldFormatShort() {
		assertEquals("5", formatValue((short)5));
	}

	@Test
	void shouldFormatInt() {
		assertEquals("5", formatValue(5));
	}

	@Test
	void shouldFormatLong() {
		assertEquals("5", formatValue((long)5));
	}

	@Test
	void shouldFormatChar() {
		assertEquals("'5'", formatValue('5'));
	}

	@Test
	void shouldFormatBoolean() {
		assertEquals("true", formatValue(true));
	}

	@Test
	void shouldFormatString() {
		assertEquals("'5'", formatValue("5"));
	}

	@Test
	void shouldFormatBigDecimal() {
		assertEquals("1.123", formatValue(new BigDecimal("1.123")));
	}

	@Test
	void shouldFormatLocale() {
		final Locale czechLocale = new Locale("cs", "CZ");
		assertEquals("'cs-CZ'", formatValue(czechLocale));
	}

	@Test
	void shouldFormatCurrency() {
		final Currency czkCurrency = Currency.getInstance("CZK");
		assertEquals("'CZK'", formatValue(czkCurrency));
	}

	@Test
	void shouldFormatUUID() {
		final UUID uuid = UUID.randomUUID();
		assertEquals("'" + uuid + "'", formatValue(uuid));
	}

	@Test
	void shouldFormatOffsetDateTime() {
		final OffsetDateTime dateTime = OffsetDateTime.of(2021, 1, 30, 14, 45, 16, 0, ZoneId.of("Europe/Prague").getRules().getOffset(LocalDateTime.of(2022, 12, 1, 0, 0)));
		assertEquals("2021-01-30T14:45:16+01:00", formatValue(dateTime));
	}

	@Test
	void shouldFormatLocalDateTime() {
		final LocalDateTime dateTime = LocalDateTime.of(2021, 1, 30, 14, 45, 16, 0);
		assertEquals("2021-01-30T14:45:16", formatValue(dateTime));
	}

	@Test
	void shouldFormatLocalDate() {
		final LocalDate date = LocalDate.of(2021, 1, 30);
		assertEquals("2021-01-30", formatValue(date));
	}

	@Test
	void shouldFormatLocalTime() {
		final LocalTime time = LocalTime.of(14, 45, 16, 0);
		assertEquals("14:45:16", formatValue(time));
	}

	@Test
	void shouldFormatNumberRange() {
		final NumberRange fromRange = BigDecimalNumberRange.from(new BigDecimal("45.45"), 2);
		assertEquals("[45.45,]", formatValue(fromRange));

		final NumberRange toRange = BigDecimalNumberRange.to(new BigDecimal("45.45"), 2);
		assertEquals("[,45.45]", formatValue(toRange));

		final NumberRange betweenRange = BigDecimalNumberRange.between(new BigDecimal("45.45"), new BigDecimal("89.5"), 2);
		assertEquals("[45.45,89.5]", formatValue(betweenRange));
	}

	@Test
	void shouldFormatDateTimeRange() {
		final OffsetDateTime dateTimeA = OffsetDateTime.of(2021, 1, 30, 14, 45, 16, 0, ZoneId.of("Europe/Prague").getRules().getOffset(LocalDateTime.of(2022, 12, 1, 0, 0)));
		final OffsetDateTime dateTimeB = OffsetDateTime.of(2022, 1, 30, 14, 45, 16, 0, ZoneId.of("Europe/Prague").getRules().getOffset(LocalDateTime.of(2022, 12, 1, 0, 0)));

		final DateTimeRange since = DateTimeRange.since(dateTimeA);
		assertEquals("[2021-01-30T14:45:16+01:00,]", formatValue(since));

		final DateTimeRange toRange = DateTimeRange.until(dateTimeA);
		assertEquals("[,2021-01-30T14:45:16+01:00]", formatValue(toRange));

		final DateTimeRange betweenRange = DateTimeRange.between(dateTimeA, dateTimeB);
		assertEquals("[2021-01-30T14:45:16+01:00,2022-01-30T14:45:16+01:00]", formatValue(betweenRange));
	}

	@Test
	void shouldReturnWrapperClass() {
		assertEquals(Integer.class, getWrappingPrimitiveClass(int.class));
		assertEquals(Character.class, getWrappingPrimitiveClass(char.class));
	}

	@Test
	void shouldConvertToWrapperClass() {
		assertEquals(Integer.class, toWrappedForm(int.class));
		assertEquals(Integer[].class, toWrappedForm(int[].class));
		assertEquals(Character.class, toWrappedForm(char.class));
		assertEquals(Character[].class, toWrappedForm(char[].class));
	}

	@Test
	void shouldConvertToByte() {
		assertEquals((byte)8, EvitaDataTypes.toTargetType(((byte)8), Byte.class));
		assertEquals((byte)8, EvitaDataTypes.toTargetType(((short)8), Byte.class));
		assertEquals((byte)8, EvitaDataTypes.toTargetType(8, Byte.class));
		assertEquals((byte)8, EvitaDataTypes.toTargetType(((long)8), Byte.class));
		assertEquals((byte)8, EvitaDataTypes.toTargetType(new BigDecimal("8"), Byte.class));
		assertEquals((byte)8, EvitaDataTypes.toTargetType("8", Byte.class));

		assertEquals((byte)8, EvitaDataTypes.toTargetType(((byte)8), byte.class));
		assertEquals((byte)8, EvitaDataTypes.toTargetType(((short)8), byte.class));
		assertEquals((byte)8, EvitaDataTypes.toTargetType(8, byte.class));
		assertEquals((byte)8, EvitaDataTypes.toTargetType(((long)8), byte.class));
		assertEquals((byte)8, EvitaDataTypes.toTargetType(new BigDecimal("8"), byte.class));
		assertEquals((byte)8, EvitaDataTypes.toTargetType("8", byte.class));

		assertArrayEquals(new Byte[] {8}, EvitaDataTypes.toTargetType(8, Byte[].class));
		assertArrayEquals(new Byte[] {8}, EvitaDataTypes.toTargetType(new int[] {8}, Byte[].class));
		assertArrayEquals(new Byte[] {8}, EvitaDataTypes.toTargetType(new Integer[] {8}, Byte[].class));
	}

	@Test
	void shouldFailToConvertToByte() {
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(Currency.getInstance("CZK"), Byte.class));
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(new BigDecimal("8.78"), Byte.class));
	}

	@Test
	void shouldConvertToShort() {
		assertEquals((short)8, EvitaDataTypes.toTargetType(((byte)8), Short.class));
		assertEquals((short)8, EvitaDataTypes.toTargetType(((short)8), Short.class));
		assertEquals((short)8, EvitaDataTypes.toTargetType(8, Short.class));
		assertEquals((short)8, EvitaDataTypes.toTargetType(((long)8), Short.class));
		assertEquals((short)8, EvitaDataTypes.toTargetType(new BigDecimal("8"), Short.class));
		assertEquals((short)8, EvitaDataTypes.toTargetType("8", Short.class));

		assertEquals((short)8, EvitaDataTypes.toTargetType(((byte)8), short.class));
		assertEquals((short)8, EvitaDataTypes.toTargetType(((short)8), short.class));
		assertEquals((short)8, EvitaDataTypes.toTargetType(8, short.class));
		assertEquals((short)8, EvitaDataTypes.toTargetType(((long)8), short.class));
		assertEquals((short)8, EvitaDataTypes.toTargetType(new BigDecimal("8"), short.class));
		assertEquals((short)8, EvitaDataTypes.toTargetType("8", short.class));

		assertArrayEquals(new Short[] {8}, EvitaDataTypes.toTargetType(8, Short[].class));
		assertArrayEquals(new Short[] {8}, EvitaDataTypes.toTargetType(new int[] {8}, Short[].class));
		assertArrayEquals(new Short[] {8}, EvitaDataTypes.toTargetType(new Integer[] {8}, Short[].class));
	}

	@Test
	void shouldFailToConvertToShort() {
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(Currency.getInstance("CZK"), Short.class));
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(new BigDecimal("8.78"), Short.class));
	}

	@Test
	void shouldConvertToInt() {
		assertEquals(8, EvitaDataTypes.toTargetType(((byte)8), Integer.class));
		assertEquals(8, EvitaDataTypes.toTargetType(((short)8), Integer.class));
		assertEquals(8, EvitaDataTypes.toTargetType(8, Integer.class));
		assertEquals(8, EvitaDataTypes.toTargetType(((long)8), Integer.class));
		assertEquals(8, EvitaDataTypes.toTargetType(new BigDecimal("8"), Integer.class));
		assertEquals(8, EvitaDataTypes.toTargetType("8", Integer.class));

		assertEquals(8, EvitaDataTypes.toTargetType(((byte)8), int.class));
		assertEquals(8, EvitaDataTypes.toTargetType(((short)8), int.class));
		assertEquals(8, EvitaDataTypes.toTargetType(8, int.class));
		assertEquals(8, EvitaDataTypes.toTargetType(((long)8), int.class));
		assertEquals(8, EvitaDataTypes.toTargetType(new BigDecimal("8"), int.class));
		assertEquals(8, EvitaDataTypes.toTargetType("8", int.class));

		assertArrayEquals(new Integer[] {8}, EvitaDataTypes.toTargetType(8, Integer[].class));
		assertArrayEquals(new Integer[] {8}, EvitaDataTypes.toTargetType(new int[] {8}, Integer[].class));
		assertArrayEquals(new Integer[] {8}, EvitaDataTypes.toTargetType(new Integer[] {8}, Integer[].class));
	}

	@Test
	void shouldFailToConvertToInt() {
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(Currency.getInstance("CZK"), Integer.class));
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(new BigDecimal("8.78"), Integer.class));
	}

	@Test
	void shouldConvertToLong() {
		assertEquals(8, EvitaDataTypes.toTargetType(((byte)8), Long.class));
		assertEquals(8, EvitaDataTypes.toTargetType(((short)8), Long.class));
		assertEquals(8, EvitaDataTypes.toTargetType(8, Long.class));
		assertEquals(8, EvitaDataTypes.toTargetType(((long)8), Long.class));
		assertEquals(8, EvitaDataTypes.toTargetType(new BigDecimal("8"), Long.class));
		assertEquals(8, EvitaDataTypes.toTargetType("8", Long.class));

		assertEquals(8, EvitaDataTypes.toTargetType(((byte)8), long.class));
		assertEquals(8, EvitaDataTypes.toTargetType(((short)8), long.class));
		assertEquals(8, EvitaDataTypes.toTargetType(8, long.class));
		assertEquals(8, EvitaDataTypes.toTargetType(((long)8), long.class));
		assertEquals(8, EvitaDataTypes.toTargetType(new BigDecimal("8"), long.class));
		assertEquals(8, EvitaDataTypes.toTargetType("8", long.class));

		assertArrayEquals(new Long[] {8L}, EvitaDataTypes.toTargetType(8, Long[].class));
		assertArrayEquals(new Long[] {8L}, EvitaDataTypes.toTargetType(new int[] {8}, Long[].class));
		assertArrayEquals(new Long[] {8L}, EvitaDataTypes.toTargetType(new Integer[] {8}, Long[].class));
	}

	@Test
	void shouldFailToConvertToLong() {
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(Currency.getInstance("CZK"), Long.class));
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(new BigDecimal("8.78"), Long.class));
	}

	@Test
	void shouldConvertToBigDecimal() {
		assertEquals(new BigDecimal("8"), EvitaDataTypes.toTargetType(((byte)8), BigDecimal.class));
		assertEquals(new BigDecimal("8"), EvitaDataTypes.toTargetType(((short)8), BigDecimal.class));
		assertEquals(new BigDecimal("8"), EvitaDataTypes.toTargetType(8, BigDecimal.class));
		assertEquals(new BigDecimal("8"), EvitaDataTypes.toTargetType(((long)8), BigDecimal.class));
		assertEquals(new BigDecimal("8"), EvitaDataTypes.toTargetType(new BigDecimal("8"), BigDecimal.class));
		assertEquals(new BigDecimal("8"), EvitaDataTypes.toTargetType("8", BigDecimal.class));

		assertArrayEquals(new BigDecimal[] {new BigDecimal("8")}, EvitaDataTypes.toTargetType(8, BigDecimal[].class));
		assertArrayEquals(new BigDecimal[] {new BigDecimal("8")}, EvitaDataTypes.toTargetType(new int[] {8}, BigDecimal[].class));
		assertArrayEquals(new BigDecimal[] {new BigDecimal("8")}, EvitaDataTypes.toTargetType(new Integer[] {8}, BigDecimal[].class));
	}

	@Test
	void shouldFailToConvertToBigDecimal() {
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(Currency.getInstance("CZK"), BigDecimal.class));
	}

	@Test
	void shouldConvertToBoolean() {
		assertTrue(EvitaDataTypes.toTargetType(((byte)1), Boolean.class));
		assertTrue(EvitaDataTypes.toTargetType(true, Boolean.class));
		assertTrue(EvitaDataTypes.toTargetType("true", Boolean.class));
		assertFalse(EvitaDataTypes.toTargetType(((byte)0), Boolean.class));
		assertFalse(EvitaDataTypes.toTargetType("false", Boolean.class));
	}

	@Test
	void shouldConvertToChar() {
		assertEquals((char)8, EvitaDataTypes.toTargetType(((byte)8), Character.class));
		assertEquals((char)8, EvitaDataTypes.toTargetType(((short)8), Character.class));
		assertEquals((char)8, EvitaDataTypes.toTargetType(((char)8), Character.class));
		assertEquals((char)8, EvitaDataTypes.toTargetType(((long)8), Character.class));
		assertEquals((char)8, EvitaDataTypes.toTargetType(new BigDecimal("8"), Character.class));
		assertEquals((char)56, EvitaDataTypes.toTargetType("8", Character.class));
		assertEquals((char)65, EvitaDataTypes.toTargetType("A", Character.class));
	}

	@Test
	void shouldFailToConvertToChar() {
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(Currency.getInstance("CZK"), Character.class));
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(new BigDecimal("8.78"), Character.class));
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType("AB", Character.class));
	}

	@Test
	void shouldConvertToOffsetDateTime() {
		final OffsetDateTime theDate = OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
		assertEquals(theDate, EvitaDataTypes.toTargetType(theDate, OffsetDateTime.class));
		assertEquals(theDate, EvitaDataTypes.toTargetType(theDate.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), OffsetDateTime.class));
		assertEquals(theDate, EvitaDataTypes.toTargetType(theDate.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE), OffsetDateTime.class));
	}

	@Test
	void shouldFailToConvertToOffsetDateTime() {
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(Currency.getInstance("CZK"), OffsetDateTime.class));
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(new BigDecimal("8.78"), OffsetDateTime.class));
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType("AB", OffsetDateTime.class));
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(LocalTime.of(11, 45), OffsetDateTime.class));
	}

	@Test
	void shouldConvertToLocalDateTime() {
		final OffsetDateTime theDate = OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
		final LocalDateTime theLocalDate = LocalDateTime.of(2021, 1, 1, 0, 0, 0, 0);
		assertEquals(theLocalDate, EvitaDataTypes.toTargetType(theDate, LocalDateTime.class));
		assertEquals(theLocalDate, EvitaDataTypes.toTargetType(theDate.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), LocalDateTime.class));
		assertEquals(theLocalDate, EvitaDataTypes.toTargetType(theDate.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE), LocalDateTime.class));
	}

	@Test
	void shouldFailToConvertToLocalDateTime() {
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(Currency.getInstance("CZK"), LocalDateTime.class));
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(new BigDecimal("8.78"), LocalDateTime.class));
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType("AB", LocalDateTime.class));
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(LocalTime.of(11, 45), OffsetDateTime.class));
	}

	@Test
	void shouldConvertToLocalDate() {
		final OffsetDateTime theDate = OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
		final LocalDate theLocalDate = LocalDate.of(2021, 1, 1);
		assertEquals(theLocalDate, EvitaDataTypes.toTargetType(theDate, LocalDate.class));
		assertEquals(theLocalDate, EvitaDataTypes.toTargetType(theDate.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), LocalDate.class));
		assertEquals(theLocalDate, EvitaDataTypes.toTargetType(theDate.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE), LocalDate.class));
	}

	@Test
	void shouldFailToConvertToLocalDate() {
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(Currency.getInstance("CZK"), LocalDate.class));
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(new BigDecimal("8.78"), LocalDate.class));
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType("AB", LocalDate.class));
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(LocalTime.of(11, 45), OffsetDateTime.class));
	}

	@Test
	void shouldConvertToLocalTime() {
		final OffsetDateTime theDate = OffsetDateTime.of(2021, 1, 1, 11, 45, 51, 0, ZoneOffset.UTC);
		final LocalTime theLocalTime = LocalTime.of(11, 45, 51);
		assertEquals(theLocalTime, EvitaDataTypes.toTargetType(theDate, LocalTime.class));
		assertEquals(theLocalTime, EvitaDataTypes.toTargetType(theDate.toLocalTime().format(DateTimeFormatter.ISO_LOCAL_TIME), LocalTime.class));
	}

	@Test
	void shouldFailToConvertToLocalTime() {
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(Currency.getInstance("CZK"), LocalTime.class));
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(new BigDecimal("8.78"), LocalTime.class));
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType("AB", LocalTime.class));
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(LocalTime.of(11, 45), OffsetDateTime.class));
	}

	@Test
	void shouldConvertToDateTimeRange() {
		final OffsetDateTime theDate = OffsetDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
		assertEquals(DateTimeRange.since(theDate), EvitaDataTypes.toTargetType(DateTimeRange.since(theDate), DateTimeRange.class));
		assertEquals(DateTimeRange.since(theDate), EvitaDataTypes.toTargetType("[" + theDate.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + ",]", DateTimeRange.class));
		assertEquals(DateTimeRange.since(theDate), EvitaDataTypes.toTargetType("[" + theDate.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE) + ",]", DateTimeRange.class));
	}


	@Test
	void shouldFailToConvertToDateTimeRange() {
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(Currency.getInstance("CZK"), DateTimeRange.class));
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(new BigDecimal("8.78"), DateTimeRange.class));
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType("AB", DateTimeRange.class));
	}

	@Test
	void shouldConvertToNumberRange() {
		assertEquals(ByteNumberRange.from((byte)8), EvitaDataTypes.toTargetType(ByteNumberRange.from((byte)8), ByteNumberRange.class));
		assertEquals(ByteNumberRange.from((byte)8), EvitaDataTypes.toTargetType(ShortNumberRange.from((short)8), ByteNumberRange.class));
		assertEquals(ByteNumberRange.from((byte)8), EvitaDataTypes.toTargetType(IntegerNumberRange.from(8), ByteNumberRange.class));
		assertEquals(ByteNumberRange.from((byte)8), EvitaDataTypes.toTargetType(LongNumberRange.from(8L), ByteNumberRange.class));

		assertEquals(ShortNumberRange.from((short)8), EvitaDataTypes.toTargetType(ByteNumberRange.from((byte)8), ShortNumberRange.class));
		assertEquals(ShortNumberRange.from((short)8), EvitaDataTypes.toTargetType(ShortNumberRange.from((short)8), ShortNumberRange.class));
		assertEquals(ShortNumberRange.from((short)8), EvitaDataTypes.toTargetType(IntegerNumberRange.from(8), ShortNumberRange.class));
		assertEquals(ShortNumberRange.from((short)8), EvitaDataTypes.toTargetType(LongNumberRange.from(8L), ShortNumberRange.class));

		assertEquals(IntegerNumberRange.from(8), EvitaDataTypes.toTargetType(ByteNumberRange.from((byte)8), IntegerNumberRange.class));
		assertEquals(IntegerNumberRange.from(8), EvitaDataTypes.toTargetType(ShortNumberRange.from((short)8), IntegerNumberRange.class));
		assertEquals(IntegerNumberRange.from(8), EvitaDataTypes.toTargetType(IntegerNumberRange.from(8), IntegerNumberRange.class));
		assertEquals(IntegerNumberRange.from(8), EvitaDataTypes.toTargetType(LongNumberRange.from(8L), IntegerNumberRange.class));

		assertEquals(LongNumberRange.from(8L), EvitaDataTypes.toTargetType(ByteNumberRange.from((byte)8), LongNumberRange.class));
		assertEquals(LongNumberRange.from(8L), EvitaDataTypes.toTargetType(ShortNumberRange.from((short)8), LongNumberRange.class));
		assertEquals(LongNumberRange.from(8L), EvitaDataTypes.toTargetType(IntegerNumberRange.from(8), LongNumberRange.class));
		assertEquals(LongNumberRange.from(8L), EvitaDataTypes.toTargetType(LongNumberRange.from(8L), LongNumberRange.class));

		assertEquals(BigDecimalNumberRange.from(new BigDecimal("8")), EvitaDataTypes.toTargetType(ByteNumberRange.from((byte)8), BigDecimalNumberRange.class));
		assertEquals(BigDecimalNumberRange.from(new BigDecimal("8")), EvitaDataTypes.toTargetType(ShortNumberRange.from((short)8), BigDecimalNumberRange.class));
		assertEquals(BigDecimalNumberRange.from(new BigDecimal("8")), EvitaDataTypes.toTargetType(IntegerNumberRange.from(8), BigDecimalNumberRange.class));
		assertEquals(BigDecimalNumberRange.from(new BigDecimal("8")), EvitaDataTypes.toTargetType(LongNumberRange.from(8L), BigDecimalNumberRange.class));

		assertEquals(ByteNumberRange.from((byte) 8), EvitaDataTypes.toTargetType("[8,]", ByteNumberRange.class));
		assertEquals(ShortNumberRange.from((short) 8), EvitaDataTypes.toTargetType("[8,]", ShortNumberRange.class));
		assertEquals(IntegerNumberRange.from(8), EvitaDataTypes.toTargetType("[8,]", IntegerNumberRange.class));
		assertEquals(LongNumberRange.from(8L), EvitaDataTypes.toTargetType("[8,]", LongNumberRange.class));
		assertEquals(BigDecimalNumberRange.from(new BigDecimal(8)), EvitaDataTypes.toTargetType("[8,]", BigDecimalNumberRange.class));

		assertEquals(BigDecimalNumberRange.to(new BigDecimal("8.78"), 2), EvitaDataTypes.toTargetType("[,8.78]", BigDecimalNumberRange.class, 2));
	}

	@Test
	void shouldFailToConvertToNumberRange() {
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(Currency.getInstance("CZK"), IntegerNumberRange.class));
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(new BigDecimal("8.78"), IntegerNumberRange.class));
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType("AB", IntegerNumberRange.class));
	}

	@Test
	void shouldConvertToLocale() {
		assertEquals(Locale.ENGLISH, EvitaDataTypes.toTargetType(Locale.ENGLISH, Locale.class));
		assertEquals(Locale.ENGLISH, EvitaDataTypes.toTargetType("en", Locale.class));
		assertEquals(new Locale("en", "US"), EvitaDataTypes.toTargetType("en-US", Locale.class));
	}

	@Test
	void shouldFailToConvertToLocale() {
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType("WHATEVER", Locale.class));
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(new BigDecimal("8.78"), Locale.class));
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType("", Locale.class));
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType("-cs", Locale.class));
	}

	@Test
	void shouldConvertToUuid() {
		final UUID uuid = UUID.randomUUID();
		assertEquals(uuid, EvitaDataTypes.toTargetType(uuid, UUID.class));
		assertEquals(uuid, EvitaDataTypes.toTargetType(uuid.toString(), UUID.class));
	}

	@Test
	void shouldFailToConvertToUuid() {
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType("WHATEVER", UUID.class));
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType(new BigDecimal("8.78"), UUID.class));
		assertThrows(InconvertibleDataTypeException.class, () -> EvitaDataTypes.toTargetType("", UUID.class));
	}

	@Test
	void shouldConvertStringBigDecimalValuesProperly() {
		final String integerBigDecimalValueString = "1";
		final BigDecimal integerBigDecimalValue = BigDecimal.valueOf(1);
		assertEquals(
			integerBigDecimalValue,
			EvitaDataTypes.toTargetType(integerBigDecimalValueString, BigDecimal.class)
		);

		final String positivelySignedBigDecimalValueString = "+3.14";
		final BigDecimal positivelySignedBigDecimalValue = BigDecimal.valueOf(3.14);
		assertEquals(
			positivelySignedBigDecimalValue,
			EvitaDataTypes.toTargetType(positivelySignedBigDecimalValueString, BigDecimal.class)
		);

		final String negativelySignedBigDecimalValueString = "-2.5";
		final BigDecimal negativelySignedBigDecimalValue = BigDecimal.valueOf(-2.5);
		assertEquals(
			negativelySignedBigDecimalValue,
			EvitaDataTypes.toTargetType(negativelySignedBigDecimalValueString, BigDecimal.class)
		);

		final String zeroLengthIntegerBigDecimalValueString = ".5";
		final BigDecimal zeroLengthIntegerBigDecimalValue = BigDecimal.valueOf(0.5);
		assertEquals(
			zeroLengthIntegerBigDecimalValue,
			EvitaDataTypes.toTargetType(zeroLengthIntegerBigDecimalValueString, BigDecimal.class)
		);

		final String exponentialBaseBigDecimalValueString = "2.5E8";
		final BigDecimal exponentialBaseBigDecimalValue = BigDecimal.valueOf(2.5e8);
		assertEquals(
			exponentialBaseBigDecimalValue,
			EvitaDataTypes.toTargetType(exponentialBaseBigDecimalValueString, BigDecimal.class)
		);

		final String zeroExponentBaseBigDecimalValueString = "2.5e0";
		final BigDecimal zeroExponentBaseBigDecimalValue = BigDecimal.valueOf(2.5);
		assertEquals(
			zeroExponentBaseBigDecimalValue,
			EvitaDataTypes.toTargetType(zeroExponentBaseBigDecimalValueString, BigDecimal.class)
		);
	}

	@Test
	void shouldFormatBigDecimalValuesProperly() {
		assertEquals("1", formatValue(new BigDecimal("1")));
		assertEquals("3.14", formatValue(new BigDecimal("+3.14")));
		assertEquals("-2.5", formatValue(new BigDecimal("-2.5")));
		assertEquals("0.5", formatValue(new BigDecimal(".5")));
		assertEquals("2.5e8", formatValue(new BigDecimal("2.5E8")));
		assertEquals("-2.5e-8", formatValue(new BigDecimal("-2.5E-8")));
		assertEquals("2.5", formatValue(new BigDecimal("2.5e0")));
	}
}
