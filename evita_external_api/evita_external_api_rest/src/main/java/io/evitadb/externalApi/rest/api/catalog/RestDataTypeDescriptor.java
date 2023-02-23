/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.rest.api.catalog;

import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.ShortNumberRange;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Locale;

/**
 * Contains description for data types used in REST
 *
 * @author Martin Veska, FG Forrest a.s. (c) 2022
 */
public interface RestDataTypeDescriptor {

    DataTypeDescriptor STRING = DataTypeDescriptor.builder()
        .name("String")
        .description("A string.")
        .type(String.class)
        .build();

    DataTypeDescriptor CHAR = DataTypeDescriptor.builder()
        .name("Character")
        .description("A character.")
        .type(Character.class)
        .build();

    //todo correct type description
    DataTypeDescriptor BYTE = DataTypeDescriptor.builder()
        .name("Byte")
        .description("A 8-bit signed integer.")
        .type(Byte.class)
        .build();

    DataTypeDescriptor SHORT = DataTypeDescriptor.builder()
        .name("Short")
        .description("A 16-bit signed integer.")
        .type(Short.class)
        .build();

    DataTypeDescriptor INT = DataTypeDescriptor.builder()
        .name("Integer")
        .description("A 32-bit signed integer.")
        .type(Integer.class)
        .build();

    DataTypeDescriptor LONG = DataTypeDescriptor.builder()
        .name("Long")
        .description("A 64-bit signed integer.")
        .type(Integer.class)
        .build();

    DataTypeDescriptor BOOLEAN = DataTypeDescriptor.builder()
        .name("Boolean")
        .description("A boolean.")
        .type(Boolean.class)
        .build();

    DataTypeDescriptor BIG_DECIMAL = DataTypeDescriptor.builder()
        .name("BigDecimal")
        .description("An arbitrary precision signed decimal.")
        .type(BigDecimal.class)
        .build();

    DataTypeDescriptor LOCAL_DATE = DataTypeDescriptor.builder()
        .name("LocalDate")
        .description("An RFC-3339 compliant Full Date.")
        .type(LocalDate.class)
        .build();

    DataTypeDescriptor LOCAL_TIME = DataTypeDescriptor.builder()
        .name("LocalTime")
        .description("24-hour clock time value string in the format `hh:mm:ss` or `hh:mm:ss.sss`.")
        .type(LocalTime.class)
        .build();

    DataTypeDescriptor LOCAL_DATE_TIME = DataTypeDescriptor.builder()
        .name("LocalDateTime")
        .description("ISO date time without offset and zone.")
        .type(LocalDateTime.class)
        .build();

    DataTypeDescriptor OFFSET_DATE_TIME = DataTypeDescriptor.builder()
        .name("OffsetDateTime")
        .description("ISO date time with offset.")
        .type(OffsetDateTime.class)
        .build();

    DataTypeDescriptor DATE_TIME_RANGE = DataTypeDescriptor.builder()
        .name("DateTimeRange")
        .description("Range of offset date times.")
        .type(DateTimeRange.class)
        .build();

    DataTypeDescriptor BIG_DECIMAL_NUMBER_RANGE = DataTypeDescriptor.builder()
        .name("BigDecimalNumberRange")
        .description("Range of an arbitrary precision signed decimal values.")
        .type(BigDecimalNumberRange.class)
        .build();

    // todo lho correct description
    DataTypeDescriptor BYTE_NUMBER_RANGE = DataTypeDescriptor.builder()
        .name("ByteNumberRange")
        .description("Range of a 8-bit signed integer values.")
        .type(ByteNumberRange.class)
        .build();

    DataTypeDescriptor SHORT_NUMBER_RANGE = DataTypeDescriptor.builder()
        .name("ShortNumberRange")
        .description("Range of a 16-bit signed integer values.")
        .type(ShortNumberRange.class)
        .build();

    DataTypeDescriptor INTEGER_NUMBER_RANGE = DataTypeDescriptor.builder()
        .name("IntegerNumberRange")
        .description("Range of a 32-bit signed integer values.")
        .type(IntegerNumberRange.class)
        .build();

    DataTypeDescriptor LONG_NUMBER_RANGE = DataTypeDescriptor.builder()
        .name("LongNumberRange")
        .description("Range of a 64-bit signed integer values.")
        .type(LongNumberRange.class)
        .build();

    DataTypeDescriptor LOCALE = DataTypeDescriptor.builder()
        .name("Locale")
        .description("A IETF BCP 47 language tag.")
        .type(Locale.class)
        .build();

    DataTypeDescriptor CURRENCY = DataTypeDescriptor.builder()
        .name("Currency")
        .description("Currency in ISO 4217 format.")
        .type(Currency.class)
        .build();

    DataTypeDescriptor COMPLEX_DATA_OBJECT = DataTypeDescriptor.builder()
        .name("ComplexDataObject")
        .description("A generic complex data object.")
        .type(ComplexDataObject.class)
        .build();

    DataTypeDescriptor STRING_ARRAY = DataTypeDescriptor.builder()
        .name("StringArray")
        .description("An array of strings.")
        .type(String[].class)
        .build();

    DataTypeDescriptor CHAR_ARRAY = DataTypeDescriptor.builder()
        .name("CharacterArray")
        .description("An array of characters.")
        .type(Character.class)
        .build();

    DataTypeDescriptor BYTE_ARRAY = DataTypeDescriptor.builder()
        .name("ByteArray")
        .description("An array of 8-bit signed integers.")
        .type(Byte[].class)
        .build();

    DataTypeDescriptor SHORT_ARRAY = DataTypeDescriptor.builder()
        .name("ShortArray")
        .description("An array of 16-bit signed integers.")
        .type(Short[].class)
        .build();

    DataTypeDescriptor INT_ARRAY = DataTypeDescriptor.builder()
        .name("IntegerArray")
        .description("An array of 32-bit signed integers.")
        .type(Integer[].class)
        .build();

    DataTypeDescriptor LONG_ARRAY = DataTypeDescriptor.builder()
        .name("LongArray")
        .description("An array of 64-bit signed integers.")
        .type(Integer[].class)
        .build();

    DataTypeDescriptor BOOLEAN_ARRAY = DataTypeDescriptor.builder()
        .name("BooleanArray")
        .description("An array of booleans.")
        .type(Boolean[].class)
        .build();

    DataTypeDescriptor BIG_DECIMAL_ARRAY = DataTypeDescriptor.builder()
        .name("BigDecimalArray")
        .description("An array of arbitrary precision signed decimals.")
        .type(BigDecimal[].class)
        .build();

    DataTypeDescriptor LOCAL_DATE_ARRAY = DataTypeDescriptor.builder()
        .name("LocalDateArray")
        .description("An array of RFC-3339 compliant Full Dates.")
        .type(LocalDate[].class)
        .build();

    DataTypeDescriptor LOCAL_TIME_ARRAY = DataTypeDescriptor.builder()
        .name("LocalTimeArray")
        .description("An array of 24-hour clock time value strings in the format `hh:mm:ss` or `hh:mm:ss.sss`.")
        .type(LocalTime[].class)
        .build();

    DataTypeDescriptor LOCAL_DATE_TIME_ARRAY = DataTypeDescriptor.builder()
        .name("LocalDateTimeArray")
        .description("An array of ISO date times without offset and zone.")
        .type(LocalDateTime[].class)
        .build();

    DataTypeDescriptor OFFSET_DATE_TIME_ARRAY = DataTypeDescriptor.builder()
        .name("OffsetDateTimeArray")
        .description("An array of ISO date times with offset.")
        .type(OffsetDateTime[].class)
        .build();

    DataTypeDescriptor DATE_TIME_RANGE_ARRAY = DataTypeDescriptor.builder()
        .name("DateTimeRangeArray")
        .description("An array of ranges of offset date time.")
        .type(DateTimeRange[].class)
        .build();

    DataTypeDescriptor BIG_DECIMAL_NUMBER_RANGE_ARRAY = DataTypeDescriptor.builder()
        .name("BigDecimalNumberRangeArray")
        .description("An array of ranges of an arbitrary precision signed decimal values.")
        .type(BigDecimalNumberRange[].class)
        .build();

    //todo lho correct description
    DataTypeDescriptor BYTE_NUMBER_RANGE_ARRAY = DataTypeDescriptor.builder()
        .name("ByteNumberRangeArray")
        .description("An array of ranges of a 8-bit signed integer values.")
        .type(ByteNumberRange[].class)
        .build();

    DataTypeDescriptor SHORT_NUMBER_RANGE_ARRAY = DataTypeDescriptor.builder()
        .name("ShortNumberRangeArray")
        .description("An array of ranges of a 16-bit signed integer values.")
        .type(ShortNumberRange[].class)
        .build();

    DataTypeDescriptor INTEGER_NUMBER_RANGE_ARRAY = DataTypeDescriptor.builder()
        .name("IntegerNumberRangeArray")
        .description("An array of ranges of a 32-bit signed integer values.")
        .type(IntegerNumberRange[].class)
        .build();

    DataTypeDescriptor LONG_NUMBER_RANGE_ARRAY = DataTypeDescriptor.builder()
        .name("LongNumberRangeArray")
        .description("An array of ranges of a 64-bit signed integer values.")
        .type(LongNumberRange[].class)
        .build();

    DataTypeDescriptor LOCALE_ARRAY = DataTypeDescriptor.builder()
        .name("LocaleArray")
        .description("An array of IETF BCP 47 language tags.")
        .type(Locale[].class)
        .build();

    DataTypeDescriptor CURRENCY_ARRAY = DataTypeDescriptor.builder()
        .name("CurrencyArray")
        .description("An array of currencies in ISO 4217 format.")
        .type(Currency[].class)
        .build();
}
