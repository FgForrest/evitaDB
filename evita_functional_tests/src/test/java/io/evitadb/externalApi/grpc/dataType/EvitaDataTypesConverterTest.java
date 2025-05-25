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

package io.evitadb.externalApi.grpc.dataType;

import com.google.protobuf.Int32Value;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.ShortNumberRange;
import io.evitadb.dataType.data.DataItemMap;
import io.evitadb.dataType.data.DataItemValue;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter.AssociatedDataForm;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaAssociatedDataDataType;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaAssociatedDataValue;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaDataType;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaValue;
import io.evitadb.externalApi.grpc.generated.GrpcStringArray;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * This test verifies functionalities of methods in {@link EvitaDataTypesConverter} class.
 *
 * @author Tomáš Pozler, 2022
 */
class EvitaDataTypesConverterTest {

	private final ComplexDataObject complexDataObjectValue = new ComplexDataObject(new DataItemMap(Map.of("value", new DataItemValue("test"))));

	@Test
	void shouldConvertWithVersion() {
		//single value
		final String stringValue = "test";
		assertEquals(
			GrpcEvitaValue.newBuilder()
				.setType(GrpcEvitaDataType.STRING)
				.setVersion(Int32Value.of(1))
				.setStringValue(stringValue)
				.build(),
			EvitaDataTypesConverter.toGrpcEvitaValue(stringValue, 1)
		);

		//collections
		final String[] stringArrayValue = new String[]{"test1", "test2"};
		assertEquals(
			GrpcEvitaValue.newBuilder()
				.setType(GrpcEvitaDataType.STRING_ARRAY)
				.setVersion(Int32Value.of(1))
				.setStringValue(stringValue)
				.setStringArrayValue(GrpcStringArray.newBuilder().addAllValue(Arrays.asList(stringArrayValue)).build())
				.build(),
			EvitaDataTypesConverter.toGrpcEvitaValue(stringArrayValue, 1)
		);

		//complex data object
		assertThrows(EvitaInvalidUsageException.class, () -> EvitaDataTypesConverter.toGrpcEvitaValue(this.complexDataObjectValue, 1));
		Assertions.assertEquals(
			GrpcEvitaAssociatedDataValue.newBuilder()
				.setJsonValue(ComplexDataObjectConverter.convertComplexDataObjectToJson(this.complexDataObjectValue).toString())
				.setType(GrpcEvitaAssociatedDataDataType.GrpcEvitaDataType.COMPLEX_DATA_OBJECT)
				.setVersion(Int32Value.of(1))
				.build(),
			EvitaDataTypesConverter.toGrpcEvitaAssociatedDataValue(this.complexDataObjectValue, 1, AssociatedDataForm.JSON)
		);
		Assertions.assertEquals(
			GrpcEvitaAssociatedDataValue.newBuilder()
				.setRoot(EvitaDataTypesConverter.toGrpcDataItem(this.complexDataObjectValue.root()))
				.setType(GrpcEvitaAssociatedDataDataType.GrpcEvitaDataType.COMPLEX_DATA_OBJECT)
				.setVersion(Int32Value.of(1))
				.build(),
			EvitaDataTypesConverter.toGrpcEvitaAssociatedDataValue(this.complexDataObjectValue, 1, AssociatedDataForm.STRUCTURED_VALUE)
		);
	}

	@Test
	void shouldConvertSingleEvitaValue() {
		final String stringValue = "test";
		assertEquals(
			stringValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(stringValue))
		);
		final Character charValue = 't';
		assertEquals(
			charValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(charValue))
		);
		final Integer intValue = 1;
		assertEquals(
			intValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(intValue))
		);
		final Short shortValue = 2;
		assertEquals(
			shortValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(shortValue))
		);
		final Byte byteValue = 2;
		assertEquals(
			byteValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(byteValue))
		);
		final Long longValue = 100L;
		assertEquals(
			longValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(longValue))
		);
		final Boolean booleanValue = true;
		assertEquals(
			booleanValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(booleanValue))
		);
		final BigDecimal bigDecimalValue = BigDecimal.valueOf(1.1);
		assertEquals(
			bigDecimalValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(bigDecimalValue))
		);
		final OffsetDateTime offsetDateTimeValue = OffsetDateTime.now();
		assertEquals(
			offsetDateTimeValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(offsetDateTimeValue))
		);
		final LocalDateTime localDateTimeValue = LocalDateTime.now();
		assertEquals(
			localDateTimeValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(localDateTimeValue))
		);
		final LocalTime localTimeValue = LocalTime.now();
		assertEquals(
			localTimeValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(localTimeValue))
		);
		final LocalDate localDateValue = LocalDate.now();
		assertEquals(
			localDateValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(localDateValue))
		);
		final DateTimeRange dateTimeRangeValue = DateTimeRange.since(OffsetDateTime.now());
		assertEquals(
			dateTimeRangeValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(dateTimeRangeValue))
		);
		final BigDecimalNumberRange bigDecimalNumberRangeValue = BigDecimalNumberRange.from(BigDecimal.TEN);
		assertEquals(
			bigDecimalNumberRangeValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(bigDecimalNumberRangeValue))
		);
		final LongNumberRange longNumberRangeValue = LongNumberRange.from(10L);
		assertEquals(
			longNumberRangeValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(longNumberRangeValue))
		);
		final IntegerNumberRange integerNumberRangeValue = IntegerNumberRange.from(1);
		assertEquals(
			integerNumberRangeValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(integerNumberRangeValue))
		);
		final ShortNumberRange shortNumberRangeValue = ShortNumberRange.to((short) 1);
		assertEquals(
			shortNumberRangeValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(shortNumberRangeValue))
		);
		final ByteNumberRange byteNumberRangeValue = ByteNumberRange.to((byte) 1);
		assertEquals(
			byteNumberRangeValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(byteNumberRangeValue))
		);
		final Locale localeValue = Locale.ENGLISH;
		assertEquals(
			localeValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(localeValue))
		);
		final Currency currencyValue = Currency.getInstance("EUR");
		assertEquals(
			currencyValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(currencyValue))
		);
		final UUID uuidValue = UUID.randomUUID();
		assertEquals(
			uuidValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(uuidValue))
		);

		assertEquals(
			this.complexDataObjectValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaAssociatedDataValue(this.complexDataObjectValue, AssociatedDataForm.STRUCTURED_VALUE))
		);

		assertEquals(
			this.complexDataObjectValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaAssociatedDataValue(this.complexDataObjectValue, AssociatedDataForm.JSON))
		);
	}

	@Test
	void shouldConvertArrayEvitaValue() {
		final String[] stringValue = new String[] { "test", "test2" };
		assertArrayEquals(
			stringValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(stringValue))
		);
		final Character[] charValue = new Character[] { 't', 'a' };
		assertArrayEquals(
			charValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(charValue))
		);
		assertArrayEquals(
			charValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(new char[] { 't', 'a' }))
		);
		final Integer[] intValue = new Integer[] { 1, 10 };
		assertArrayEquals(
			intValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(intValue))
		);
		assertArrayEquals(
			intValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(new int[] { 1, 10 }))
		);
		final Short[] shortValue = new Short[] { 2, 20 };
		assertArrayEquals(
			shortValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(shortValue))
		);
		assertArrayEquals(
			shortValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(new short[] { 2, 20 }))
		);
		final Byte[] byteValue = new Byte[] { 2, 20 };
		assertArrayEquals(
			byteValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(byteValue))
		);
		assertArrayEquals(
			byteValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(new byte[] { 2, 20 }))
		);
		final Long[] longValue = new Long[] { 100L, 200L };
		assertArrayEquals(
			longValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(longValue))
		);
		assertArrayEquals(
			longValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(new long[] { 100L, 200L }))
		);
		final Boolean[] booleanValue = new Boolean[] { true, false };
		assertArrayEquals(
			booleanValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(booleanValue))
		);
		assertArrayEquals(
			booleanValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(new boolean[] { true, false }))
		);
		final BigDecimal[] bigDecimalValue = new BigDecimal[] { BigDecimal.valueOf(1.1), BigDecimal.TEN };
		assertArrayEquals(
			bigDecimalValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(bigDecimalValue))
		);
		final OffsetDateTime[] offsetDateTimeValue = new OffsetDateTime[] { OffsetDateTime.now(), OffsetDateTime.now().plusDays(1) };
		assertArrayEquals(
			offsetDateTimeValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(offsetDateTimeValue))
		);
		final LocalDateTime[] localDateTimeValue = new LocalDateTime[] { LocalDateTime.now(), LocalDateTime.now().plusDays(1) };
		assertArrayEquals(
			localDateTimeValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(localDateTimeValue))
		);
		final LocalTime[] localTimeValue = new LocalTime[] { LocalTime.now().plusHours(2) };
		assertArrayEquals(
			localTimeValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(localTimeValue))
		);
		final LocalDate[] localDateValue = new LocalDate[] { LocalDate.now().plusDays(1) };
		assertArrayEquals(
			localDateValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(localDateValue))
		);
		final DateTimeRange[] dateTimeRangeValue = new DateTimeRange[] { DateTimeRange.since(OffsetDateTime.now()), DateTimeRange.until(OffsetDateTime.now()) };
		assertArrayEquals(
			dateTimeRangeValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(dateTimeRangeValue))
		);
		final BigDecimalNumberRange[] bigDecimalNumberRangeValue = new BigDecimalNumberRange[] { BigDecimalNumberRange.from(BigDecimal.TEN), BigDecimalNumberRange.to(BigDecimal.TEN) };
		assertArrayEquals(
			bigDecimalNumberRangeValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(bigDecimalNumberRangeValue))
		);
		final LongNumberRange[] longNumberRangeValue = new LongNumberRange[] { LongNumberRange.from(10L), LongNumberRange.to(10L) };
		assertArrayEquals(
			longNumberRangeValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(longNumberRangeValue))
		);
		final IntegerNumberRange[] integerNumberRangeValue = new IntegerNumberRange[] { IntegerNumberRange.from(1), IntegerNumberRange.to(1) };
		assertArrayEquals(
			integerNumberRangeValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(integerNumberRangeValue))
		);
		final ShortNumberRange[] shortNumberRangeValue = new ShortNumberRange[] { ShortNumberRange.from((short) 1), ShortNumberRange.to((short) 1) };
		assertArrayEquals(
			shortNumberRangeValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(shortNumberRangeValue))
		);
		final ByteNumberRange[] byteNumberRangeValue = new ByteNumberRange[] { ByteNumberRange.from((byte) 1), ByteNumberRange.to((byte) 1) };
		assertArrayEquals(
			byteNumberRangeValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(byteNumberRangeValue))
		);
		final Locale[] localeValue = new Locale[] { Locale.ENGLISH, Locale.CANADA };
		assertArrayEquals(
			localeValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(localeValue))
		);
		final Currency[] currencyValue = new Currency[] { Currency.getInstance("EUR"), Currency.getInstance("USD") };
		assertArrayEquals(
			currencyValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(currencyValue))
		);
		final UUID[] uuidValue = new UUID[] { UUID.randomUUID(), UUID.randomUUID() };
		assertArrayEquals(
			uuidValue,
			EvitaDataTypesConverter.toEvitaValue(EvitaDataTypesConverter.toGrpcEvitaValue(uuidValue))
		);
	}

	@Test
	void getSupportedDataType() {
		assertEquals(BigDecimal.class, EvitaDataTypesConverter.toEvitaDataType(GrpcEvitaDataType.BIG_DECIMAL));
		assertEquals(Short.class, EvitaDataTypesConverter.toEvitaDataType(GrpcEvitaDataType.SHORT));
		assertEquals(DateTimeRange.class, EvitaDataTypesConverter.toEvitaDataType(GrpcEvitaDataType.DATE_TIME_RANGE));
		assertEquals(ByteNumberRange.class, EvitaDataTypesConverter.toEvitaDataType(GrpcEvitaDataType.BYTE_NUMBER_RANGE));
		assertEquals(Long[].class, EvitaDataTypesConverter.toEvitaDataType(GrpcEvitaDataType.LONG_ARRAY));
		assertEquals(Boolean[].class, EvitaDataTypesConverter.toEvitaDataType(GrpcEvitaDataType.BOOLEAN_ARRAY));

		assertEquals(ComplexDataObject.class, EvitaDataTypesConverter.toEvitaDataType(GrpcEvitaAssociatedDataDataType.GrpcEvitaDataType.COMPLEX_DATA_OBJECT));
	}

	@Test
	void getGrpcDataType() {
		assertEquals(GrpcEvitaDataType.STRING, EvitaDataTypesConverter.toGrpcEvitaDataType(String.class));
		assertEquals(GrpcEvitaDataType.BYTE, EvitaDataTypesConverter.toGrpcEvitaDataType(Byte.class));
		assertEquals(GrpcEvitaDataType.LOCAL_DATE, EvitaDataTypesConverter.toGrpcEvitaDataType(LocalDate.class));
		assertEquals(GrpcEvitaDataType.INTEGER_ARRAY, EvitaDataTypesConverter.toGrpcEvitaDataType(Integer[].class));
		assertEquals(GrpcEvitaDataType.DATE_TIME_RANGE_ARRAY, EvitaDataTypesConverter.toGrpcEvitaDataType(DateTimeRange[].class));

		assertEquals(GrpcEvitaAssociatedDataDataType.GrpcEvitaDataType.STRING, EvitaDataTypesConverter.toGrpcEvitaAssociatedDataDataType(String.class));
		assertEquals(GrpcEvitaAssociatedDataDataType.GrpcEvitaDataType.COMPLEX_DATA_OBJECT, EvitaDataTypesConverter.toGrpcEvitaAssociatedDataDataType(ComplexDataObject.class));
	}
}
