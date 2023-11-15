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

package io.evitadb.api.query.parser.visitor;

import io.evitadb.api.query.parser.EnumWrapper;
import io.evitadb.api.query.parser.ParseContext;
import io.evitadb.api.query.parser.ParseMode;
import io.evitadb.api.query.parser.ParserExecutor;
import io.evitadb.api.query.parser.ParserFactory;
import io.evitadb.api.query.parser.Value;
import io.evitadb.api.query.parser.error.EvitaQLInvalidQueryError;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.LongNumberRange;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.evitadb.dataType.EvitaDataTypes.formatValue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EvitaQLValueTokenVisitor}
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2021
 */
class EvitaQLValueTokenVisitorTest {

    @Test
    void shouldCreateVisitorWithUserSpecifiedDataTypes() {
        final EvitaQLValueTokenVisitor visitor1 = EvitaQLValueTokenVisitor.withAllowedTypes();
        assertEquals(0, visitor1.allowedDataTypes.size());

        final EvitaQLValueTokenVisitor visitor2 = EvitaQLValueTokenVisitor.withAllowedTypes(int.class, String.class);
        assertEquals(2, visitor2.allowedDataTypes.size());
        assertTrue(visitor2.allowedDataTypes.contains(int.class));
        assertTrue(visitor2.allowedDataTypes.contains(String.class));
    }

    @Test
    void shouldCreateVisitorWithAllComparableDataTypes() {
        final EvitaQLValueTokenVisitor visitor = EvitaQLValueTokenVisitor.withComparableTypesAllowed();
        assertEquals(19, visitor.allowedDataTypes.size());
    }

    @Test
    void shouldCreateVisitorWithAllDataTypes() {
        final EvitaQLValueTokenVisitor visitor = EvitaQLValueTokenVisitor.withAllDataTypesAllowed();
        assertEquals(22, visitor.allowedDataTypes.size());
    }

    @Test
    void shouldParseVariadicPositionalParameterAsValue() {
        final Value value1 = parseVariadicValue("?", 1L);
        assertTrue(List.class.isAssignableFrom(value1.getType()));
        assertArrayEquals(new Integer[] { 1 }, value1.asIntegerArray());

        final Value value2 = parseVariadicValue("?", List.of(1L, 2));
        assertTrue(List.class.isAssignableFrom(value2.getType()));
        assertArrayEquals(new Integer[] { 1, 2 }, value2.asIntegerArray());

        final Value value3 = parseVariadicValue("?", new LinkedHashSet<>(List.of(1L, 2)));
        assertTrue(LinkedHashSet.class.isAssignableFrom(value3.getType()));
        assertArrayEquals(new Integer[] { 1, 2 }, value3.asIntegerArray());

        final Value value4 = parseVariadicValue("?", (Object) new Integer[] { 1, 2 });
        assertTrue(value4.getType().isArray());
        assertArrayEquals(new Integer[] { 1, 2 }, value4.asIntegerArray());
    }

    @Test
    void shouldNotParseVariadicPositionalParameterAsValue() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseVariadicValue("?"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseVariadicValue("?", Integer.class, (Object) new String[] { "a" }));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseVariadicValue("?", Integer.class, List.of("a")));
    }

    @Test
    void shouldParseVariadicNamedParameterAsValue() {
        final Value value1 = parseVariadicValue("@values", Map.of("values", 1L));
        assertTrue(List.class.isAssignableFrom(value1.getType()));
        assertArrayEquals(new Integer[] { 1 }, value1.asIntegerArray());

        final Value value2 = parseVariadicValue("@values", Map.of("values", List.of(1L, 2)));
        assertTrue(List.class.isAssignableFrom(value2.getType()));
        assertArrayEquals(new Integer[] { 1, 2 }, value2.asIntegerArray());

        final Value value3 = parseVariadicValue("@values", Map.of("values", new LinkedHashSet<>(List.of(1L, 2))));
        assertTrue(LinkedHashSet.class.isAssignableFrom(value3.getType()));
        assertArrayEquals(new Integer[] { 1, 2 }, value3.asIntegerArray());

        final Value value4 = parseVariadicValue("@values", Map.of("values", new Integer[] { 1, 2 }));
        assertTrue(value4.getType().isArray());
        assertArrayEquals(new Integer[] { 1, 2 }, value4.asIntegerArray());
    }

    @Test
    void shouldNotParseVariadicNamedParameterAsValue() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseVariadicValue("@values"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseVariadicValue("@values", Integer.class, Map.of("values", new String[] { "a" })));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseVariadicValue("@values", Integer.class, Map.of("values", List.of("a"))));
    }

    @Test
    void shouldParseVariadicExplicitValuesAsValue() {
        final Value value1 = parseVariadicValueUnsafe("1");
        assertTrue(List.class.isAssignableFrom(value1.getType()));
        assertArrayEquals(new Integer[] { 1 }, value1.asIntegerArray());

        final Value value2 = parseVariadicValueUnsafe("1,2");
        assertTrue(List.class.isAssignableFrom(value2.getType()));
        assertArrayEquals(new Integer[] { 1, 2 }, value2.asIntegerArray());
    }

    @Test
    void shouldNotParseVariadicExplicitValuesAsValue() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseVariadicValueUnsafe("'a','b'", Integer.class));
    }

    @Test
    void shouldParsePositionalParameterAsValue() {
        final Value value1 = parseValue("?", 1L);
        assertEquals(Long.class, value1.getType());
        assertEquals(1L, value1.asNumber().longValue());
    }

    @Test
    void shouldNotParsePositionalParameterAsValue() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValue("?", String.class, 1));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValue("?"));
    }

    @Test
    void shouldParseNamedParameterAsValue() {
        final Value value1 = parseValue("@name", Map.of("name", "code"));
        assertEquals(String.class, value1.getType());
        assertEquals("code", value1.asString());
    }

    @Test
    void shouldNotParseNamedParameterAsValue() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValue("@name", int.class, Map.of("name", "code")));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValue("@name"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValue("@name", Map.of("something", "code")));
    }

    @Test
    void shouldParseStringLiteral() {
        final Value value1 = parseValueUnsafe("'hello all'");
        assertEquals(String.class, value1.getType());
        assertEquals("hello all", value1.asString());

        final Value value2 = parseValueUnsafe(formatValue("hello all"));
        assertEquals(String.class, value2.getType());
        assertEquals("hello all", value2.asString());
    }

    @Test
    void shouldNotParseStringLiteral() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValue("'hello all'"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("'hello all'", int.class));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("hello all"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("\"hello\""));
    }

    @Test
    void shouldParseIntLiteral() {
        final Value value1 = parseValueUnsafe("100");
        assertEquals(Long.class, value1.getType());
        assertEquals(100L, value1.asNumber().longValue());

        final Value value2 = parseValueUnsafe(formatValue(100L));
        assertEquals(Long.class, value2.getType());
        assertEquals(100L, value2.asNumber().longValue());

        final Value value3 = parseValueUnsafe("-100");
        assertEquals(Long.class, value3.getType());
        assertEquals(-100L, value3.asNumber().longValue());

        final Value value4 = parseValueUnsafe(formatValue(-100L));
        assertEquals(Long.class, value4.getType());
        assertEquals(-100L, value4.asNumber().longValue());
    }

    @Test
    void shouldNotParseIntLiteral() {
        final Value value = parseValueUnsafe("100.0");
        assertNotEquals(Long.class, value.getType());

        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValue("10"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("10", String.class));
    }

    @Test
    void shouldParseFloatLiteral() {
        final Value value1 = parseValueUnsafe("100.55");
        assertEquals(BigDecimal.class, value1.getType());
        assertEquals(BigDecimal.valueOf(100.55), value1.asBigDecimal());

        final Value value2 = parseValueUnsafe(formatValue(BigDecimal.valueOf(100.55)));
        assertEquals(BigDecimal.class, value2.getType());
        assertEquals(BigDecimal.valueOf(100.55), value2.asBigDecimal());

        final Value value3 = parseValueUnsafe("-100.55");
        assertEquals(BigDecimal.class, value3.getType());
        assertEquals(BigDecimal.valueOf(-100.55), value3.asBigDecimal());

        final Value value4 = parseValueUnsafe(formatValue(BigDecimal.valueOf(-100.55)));
        assertEquals(BigDecimal.class, value4.getType());
        assertEquals(BigDecimal.valueOf(-100.55), value4.asBigDecimal());
    }

    @Test
    void shouldNotParseFloatLiteral() {
        final Value value = parseValueUnsafe("100");
        assertNotEquals(BigDecimal.class, value.getType());

        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValue("10.55"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("10.55", String.class));
    }

    @Test
    void shouldParseBooleanLiteral() {
        final Value valueTrue1 = parseValueUnsafe("true");
        assertEquals(Boolean.class, valueTrue1.getType());
        assertTrue(valueTrue1.asBoolean());

        final Value valueFalse1 = parseValueUnsafe("false");
        assertEquals(Boolean.class, valueFalse1.getType());
        assertFalse(valueFalse1.asBoolean());

        final Value valueTrue2 = parseValueUnsafe(formatValue(true));
        assertEquals(Boolean.class, valueTrue2.getType());
        assertTrue(valueTrue2.asBoolean());

        final Value valueFalse2 = parseValueUnsafe(formatValue(false));
        assertEquals(Boolean.class, valueFalse2.getType());
        assertFalse(valueFalse2.asBoolean());
    }

    @Test
    void shouldNotParseBoolean() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValue("true"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("true", String.class));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("something"));
    }

    @Test
    void shouldParseDateLiteral() {
        final Value value1 = parseValueUnsafe("2020-02-08");
        assertEquals(LocalDate.class, value1.getType());
        assertEquals(LocalDate.of(2020, 2, 8), value1.asLocalDate());

        final Value value2 = parseValueUnsafe(formatValue(LocalDate.of(2020, 2, 8)));
        assertEquals(LocalDate.class, value2.getType());
        assertEquals(LocalDate.of(2020, 2, 8), value2.asLocalDate());
    }

    @Test
    void shouldNotParseDateLiteral() {
        final Value value = parseValueUnsafe("2020-02-8");
        assertNotEquals(LocalDate.class, value.getType());

        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValue("2020-02-08"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("2020-02-08", String.class));
    }

    @Test
    void shouldParseTimeLiteral() {
        final Value value1 = parseValueUnsafe("13:30:55");
        assertEquals(LocalTime.class, value1.getType());
        assertEquals(LocalTime.of(13, 30, 55), value1.asLocalTime());

        final Value value2 = parseValueUnsafe(formatValue(LocalTime.of(13, 30, 55)));
        assertEquals(LocalTime.class, value2.getType());
        assertEquals(LocalTime.of(13, 30, 55), value2.asLocalTime());

        final Value value3 = parseValueUnsafe("13:30:55.123");
        assertEquals(LocalTime.class, value3.getType());
        assertEquals(LocalTime.of(13, 30, 55, 123000000), value3.asLocalTime());

        final Value value4 = parseValueUnsafe(formatValue(LocalTime.of(13, 30, 55, 123000000)));
        assertEquals(LocalTime.class, value4.getType());
        assertEquals(LocalTime.of(13, 30, 55, 123000000), value4.asLocalTime());

        final Value value5 = parseValueUnsafe("13:30:55.12345");
        assertEquals(LocalTime.class, value5.getType());
        assertEquals(LocalTime.of(13, 30, 55, 123000000), value5.asLocalTime());
    }

    @Test
    void shouldNotParseTimeLiteral() {
        final Value value = parseValueUnsafe("5:30");
        assertNotEquals(LocalTime.class, value.getType());

        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValue("13:30:55"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("13:30:55", String.class));
    }

    @Test
    void shouldParseDateTimeLiteral() {
        final Value value1 = parseValueUnsafe("2020-02-08T13:30:55");
        assertEquals(LocalDateTime.class, value1.getType());
        assertEquals(
                LocalDateTime.of(2020, 2, 8, 13, 30, 55),
                value1.asLocalDateTime()
        );

        final Value value2 = parseValueUnsafe(formatValue(LocalDateTime.of(2020, 2, 8, 13, 30, 55)));
        assertEquals(LocalDateTime.class, value2.getType());
        assertEquals(
                LocalDateTime.of(2020, 2, 8, 13, 30, 55),
                value2.asLocalDateTime()
        );

        final Value value3 = parseValueUnsafe("2020-02-08T13:30:55.123");
        assertEquals(LocalDateTime.class, value3.getType());
        assertEquals(
            LocalDateTime.of(2020, 2, 8, 13, 30, 55, 123000000),
            value3.asLocalDateTime()
        );

        final Value value4 = parseValueUnsafe(formatValue(LocalDateTime.of(2020, 2, 8, 13, 30, 55, 123000000)));
        assertEquals(LocalDateTime.class, value4.getType());
        assertEquals(
            LocalDateTime.of(2020, 2, 8, 13, 30, 55, 123000000),
            value4.asLocalDateTime()
        );

        final Value value5 = parseValueUnsafe("2020-02-08T13:30:55.12345");
        assertEquals(LocalDateTime.class, value5.getType());
        assertEquals(
            LocalDateTime.of(2020, 2, 8, 13, 30, 55, 123000000),
            value5.asLocalDateTime()
        );
    }

    @Test
    void shouldNotParseDateTimeLiteral() {
        final Value value = parseValueUnsafe("2020-Jan-08T13:30:55");
        assertNotEquals(LocalDateTime.class, value.getType());

        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValue("2020-02-08T13:30:55"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("2020-02-08T13:30:55", String.class));
    }

    @Test
    void shouldParseOffsetDateTimeLiteral() {
        final Value value1 = parseValueUnsafe("2020-02-08T13:30:55+01:00");
        assertEquals(OffsetDateTime.class, value1.getType());
        assertEquals(
            OffsetDateTime.of(2020, 2, 8, 13, 30, 55, 0, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now())),
                value1.asOffsetDateTime()
        );

        final Value value2 = parseValueUnsafe(formatValue(OffsetDateTime.of(2020, 2, 8, 13, 30, 55, 0, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now()))));
        assertEquals(OffsetDateTime.class, value2.getType());
        assertEquals(
            OffsetDateTime.of(2020, 2, 8, 13, 30, 55, 0, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now())),
                value2.asOffsetDateTime()
        );

        final Value value3 = parseValueUnsafe("2020-02-08T13:30:55.123+01:00");
        assertEquals(OffsetDateTime.class, value3.getType());
        assertEquals(
            OffsetDateTime.of(2020, 2, 8, 13, 30, 55, 123000000, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now())),
            value3.asOffsetDateTime()
        );

        final Value value4 = parseValueUnsafe(formatValue(OffsetDateTime.of(2020, 2, 8, 13, 30, 55, 123000000, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now()))));
        assertEquals(OffsetDateTime.class, value4.getType());
        assertEquals(
            OffsetDateTime.of(2020, 2, 8, 13, 30, 55, 123000000, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now())),
            value4.asOffsetDateTime()
        );

        final Value value5 = parseValueUnsafe("2020-02-08T13:30:55.12345+01:00");
        assertEquals(OffsetDateTime.class, value5.getType());
        assertEquals(
            OffsetDateTime.of(2020, 2, 8, 13, 30, 55, 123000000, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now())),
            value5.asOffsetDateTime()
        );
    }

    @Test
    void shouldNotParseOffsetDateTimeLiteral() {
        final Value value1 = parseValueUnsafe("2020-02-08T13:30:55");
        assertNotEquals(OffsetDateTime.class, value1.getType());

        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValue("2020-02-08T13:30:55+01:00"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("2020-02-08T13:30:55+01:00", String.class));
    }

    @Test
    void shouldParseFloatNumberRangeLiteral() {
        final Value valueFull1 = parseValueUnsafe("[102.2,500.1]");
        assertEquals(BigDecimalNumberRange.class, valueFull1.getType());
        assertEquals(BigDecimalNumberRange.between(BigDecimal.valueOf(102.2), BigDecimal.valueOf(500.1), 1), valueFull1.asBigDecimalNumberRange());

        final Value valueWithoutEnd1 = parseValueUnsafe("[102.3,]");
        assertEquals(BigDecimalNumberRange.class, valueWithoutEnd1.getType());
        assertEquals(BigDecimalNumberRange.from(BigDecimal.valueOf(102.3), 1), valueWithoutEnd1.asBigDecimalNumberRange());

        final Value valueWithoutStart1 = parseValueUnsafe("[,500.1]");
        assertEquals(BigDecimalNumberRange.class, valueWithoutStart1.getType());
        assertEquals(BigDecimalNumberRange.to(BigDecimal.valueOf(500.1), 1), valueWithoutStart1.asBigDecimalNumberRange());
    }

    @Test
    void shouldNotParseFloatNumberRangeLiteral() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValue("[102.2,500.1]"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("[102.2,500.1]", String.class));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("[858]"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("[858.2]"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("['a','b']"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("[]"));
    }

    @Test
    void shouldParseIntNumberRangeLiteral() {
        final Value valueFull1 = parseValueUnsafe("[102,500]");
        assertEquals(LongNumberRange.class, valueFull1.getType());
        assertEquals(LongNumberRange.between(102L, 500L), valueFull1.asLongNumberRange());

        final Value valueWithoutEnd1 = parseValueUnsafe("[102,]");
        assertEquals(LongNumberRange.class, valueWithoutEnd1.getType());
        assertEquals(LongNumberRange.from(102L), valueWithoutEnd1.asLongNumberRange());

        final Value valueWithoutStart1 = parseValueUnsafe("[,500]");
        assertEquals(LongNumberRange.class, valueWithoutStart1.getType());
        assertEquals(LongNumberRange.to(500L), valueWithoutStart1.asLongNumberRange());
    }

    @Test
    void shouldNotParseIntNumberRangeLiteral() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValue("[102,500]"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("[102,500]", String.class));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("[858]"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("[858.2]"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("['a','b']"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("[]"));
    }

    @Test
    void shouldParseOffsetDateTimeRangeLiteral() {
        final Value valueFull1 = parseValueUnsafe("[2020-02-08T13:30:55+01:00,2020-02-09T13:30:55+01:00]");
        assertEquals(DateTimeRange.class, valueFull1.getType());
        assertEquals(
                DateTimeRange.between(
                    OffsetDateTime.of(2020, 2, 8, 13, 30, 55, 0, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now())),
                    OffsetDateTime.of(2020, 2, 9, 13, 30, 55, 0, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now()))
                ),
                valueFull1.asDateTimeRange()
        );

        final Value valueWithoutEnd1 = parseValueUnsafe("[2020-02-08T13:30:55+01:00,]");
        assertEquals(DateTimeRange.class, valueWithoutEnd1.getType());
        assertEquals(
                DateTimeRange.since(
                    OffsetDateTime.of(2020, 2, 8, 13, 30, 55, 0, ZoneId.of("Europe/Prague").getRules().getOffset(LocalDateTime.of(2022, 12, 1, 0, 0)))
                ),
                valueWithoutEnd1.asDateTimeRange()
        );

        final Value valueWithoutStart1 = parseValueUnsafe("[,2020-02-09T13:30:55+01:00]");
        assertEquals(DateTimeRange.class, valueWithoutStart1.getType());
        assertEquals(
                DateTimeRange.until(
                    OffsetDateTime.of(2020, 2, 9, 13, 30, 55, 0, ZoneId.of("Europe/Prague").getRules().getOffset(LocalDateTime.of(2022, 12, 1, 0, 0)))
                ),
                valueWithoutStart1.asDateTimeRange()
        );

        final Value valueFull2 = parseValueUnsafe(formatValue(
                DateTimeRange.between(
                    OffsetDateTime.of(2020, 2, 8, 13, 30, 55, 0, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now())),
                    OffsetDateTime.of(2020, 2, 9, 13, 30, 55, 0, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now()))
                )
        ));
        assertEquals(DateTimeRange.class, valueFull2.getType());
        assertEquals(
                DateTimeRange.between(
                    OffsetDateTime.of(2020, 2, 8, 13, 30, 55, 0, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now())),
                    OffsetDateTime.of(2020, 2, 9, 13, 30, 55, 0, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now()))
                ),
                valueFull2.asDateTimeRange()
        );

        final Value valueWithoutEnd2 = parseValueUnsafe(formatValue(
                DateTimeRange.since(
                    OffsetDateTime.of(2020, 2, 8, 13, 30, 55, 0, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now()))
                )
        ));
        assertEquals(DateTimeRange.class, valueWithoutEnd2.getType());
        assertEquals(
                DateTimeRange.since(
                    OffsetDateTime.of(2020, 2, 8, 13, 30, 55, 0, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now()))
                ),
                valueWithoutEnd2.asDateTimeRange()
        );

        final Value valueWithoutStart2 = parseValueUnsafe(formatValue(
                DateTimeRange.until(
                    OffsetDateTime.of(2020, 2, 9, 13, 30, 55, 0, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now()))
                )
        ));
        assertEquals(DateTimeRange.class, valueWithoutStart2.getType());
        assertEquals(
                DateTimeRange.until(
                    OffsetDateTime.of(2020, 2, 9, 13, 30, 55, 0, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now()))
                ),
                valueWithoutStart2.asDateTimeRange()
        );

        final Value valueFull3 = parseValueUnsafe("[2020-02-08T13:30:55.123+01:00,2020-02-09T13:30:55.123+01:00]");
        assertEquals(DateTimeRange.class, valueFull3.getType());
        assertEquals(
            DateTimeRange.between(
                OffsetDateTime.of(2020, 2, 8, 13, 30, 55, 123000000, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now())),
                OffsetDateTime.of(2020, 2, 9, 13, 30, 55, 123000000, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now()))
            ),
            valueFull3.asDateTimeRange()
        );

        final Value valueFull4 = parseValueUnsafe(formatValue(
            DateTimeRange.between(
                OffsetDateTime.of(2020, 2, 8, 13, 30, 55, 123450000, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now())),
                OffsetDateTime.of(2020, 2, 9, 13, 30, 55, 123450000, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now()))
            )
        ));
        assertEquals(DateTimeRange.class, valueFull4.getType());
        assertEquals(
            DateTimeRange.between(
                OffsetDateTime.of(2020, 2, 8, 13, 30, 55, 123000000, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now())),
                OffsetDateTime.of(2020, 2, 9, 13, 30, 55, 123000000, ZoneId.of("Europe/Prague").getRules().getOffset(Instant.now()))
            ),
            valueFull4.asDateTimeRange()
        );
    }

    @Test
    void shouldNotParseOffsetDateTimeRangeLiteral() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValue("[2020-02-08T13:30:55+01:00,2020-02-09T13:30:55+01:00]"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("[2020-02-08T13:30:55+01:00,2020-02-09T13:30:55+01:00]", String.class));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("[2020-02-08T13:30:55,2020-02-09T13:30:55]"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("[2020-02-08T13:30:55+1:00]"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("[]"));
    }

    @Test
    void shouldParseEnumLiteral() {
        final Value value1 = parseValueUnsafe("WITH_TAX");
        assertEquals(EnumWrapper.class, value1.getType());
        assertEquals(QueryPriceMode.WITH_TAX, value1.asEnum(QueryPriceMode.class));
    }

    @Test
    void shouldNotParseEnumLiteral() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValue("WITH_TAX"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("WITH_TAX", String.class));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("withTax"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("_WITH-TAX"));
    }

    @Test
    void shouldParseUuidLiteral() {
        final Value value1 = parseValueUnsafe("2fbbfcf2-d4bb-4db9-9658-acf1d287cbe9");
        assertEquals(UUID.class, value1.getType());
        assertEquals(UUID.fromString("2fbbfcf2-d4bb-4db9-9658-acf1d287cbe9"), value1.asUuid());
    }

    @Test
    void shouldNotParseUuidLiteral() {
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValue("'2fbbfcf2-d4bb-4db9-9658-acf1d287cbe9'"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValue("2fbbfcf2-d4bb-4db9-9658-acf1d287c%be9"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValue("2fbbfcf2-d4bb-4db9-9658-acf1d287Cbe9"));
        assertThrows(EvitaQLInvalidQueryError.class, () -> parseValueUnsafe("2fbbfcf2-d4bb-4db9", String.class));
    }

    /**
     *  Using generated EvitaQL parser tries to parse string as grammar rule "valueToken" in unsafe mode
     *
     * @param string string to parse
     * @return parsed value literal
     */
    private Value parseValueUnsafe(@Nonnull String string) {
        final ParseContext context = new ParseContext();
        context.setMode(ParseMode.UNSAFE);
        return ParserExecutor.execute(
            context,
            () -> ParserFactory.getParser(string).valueToken().accept(EvitaQLValueTokenVisitor.withAllDataTypesAllowed())
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "valueToken" in unsafe mode
     *
     * @param string string to parse
     * @param allowedType value data type allowed
     * @return parsed value literal
     */
    private Value parseValueUnsafe(@Nonnull String string, @Nonnull Class<?> allowedType) {
        final ParseContext context = new ParseContext();
        context.setMode(ParseMode.UNSAFE);
        return ParserExecutor.execute(
            context,
            () -> ParserFactory.getParser(string).valueToken().accept(EvitaQLValueTokenVisitor.withAllowedTypes(allowedType))
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "literal"
     *
     * @param string string to parse
     * @return parsed value literal
     */
    private Value parseValue(@Nonnull String string) {
        return ParserExecutor.execute(
            new ParseContext(),
            () -> ParserFactory.getParser(string).valueToken().accept(EvitaQLValueTokenVisitor.withAllDataTypesAllowed())
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "literal"
     *
     * @param string string to parse
     * @param positionalArguments positional arguments to substitute
     * @return parsed value literal
     */
    private Value parseValue(@Nonnull String string, @Nonnull Object... positionalArguments) {
        return ParserExecutor.execute(
            new ParseContext(positionalArguments),
            () -> ParserFactory.getParser(string).valueToken().accept(EvitaQLValueTokenVisitor.withAllDataTypesAllowed())
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "literal"
     *
     * @param string string to parse
     * @param allowedType value data type allowed
     * @param positionalArguments positional arguments to substitute
     * @return parsed value literal
     */
    private Value parseValue(@Nonnull String string, @Nonnull Class<?> allowedType, @Nonnull Object... positionalArguments) {
        return ParserExecutor.execute(
            new ParseContext(positionalArguments),
            () -> ParserFactory.getParser(string).valueToken().accept(EvitaQLValueTokenVisitor.withAllowedTypes(allowedType))
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "literal"
     *
     * @param string string to parse
     * @param namedArguments named arguments to substitute
     * @return parsed value literal
     */
    private Value parseValue(@Nonnull String string, @Nonnull Map<String, Object> namedArguments) {
        return ParserExecutor.execute(
            new ParseContext(namedArguments),
            () -> ParserFactory.getParser(string).valueToken().accept(EvitaQLValueTokenVisitor.withAllDataTypesAllowed())
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "literal"
     *
     * @param string string to parse
     * @param allowedType value data type allowed
     * @param namedArguments named arguments to substitute
     * @return parsed value literal
     */
    private Value parseValue(@Nonnull String string, @Nonnull Class<?> allowedType, @Nonnull Map<String, Object> namedArguments) {
        return ParserExecutor.execute(
            new ParseContext(namedArguments),
            () -> ParserFactory.getParser(string).valueToken().accept(EvitaQLValueTokenVisitor.withAllowedTypes(allowedType))
        );
    }

    /**
     *  Using generated EvitaQL parser tries to parse string as grammar rule "valueToken" in unsafe mode
     *
     * @param string string to parse
     * @return parsed value literal
     */
    private Value parseVariadicValueUnsafe(@Nonnull String string) {
        final ParseContext context = new ParseContext();
        context.setMode(ParseMode.UNSAFE);
        return ParserExecutor.execute(
            context,
            () -> ParserFactory.getParser(string).variadicValueTokens().accept(EvitaQLValueTokenVisitor.withAllDataTypesAllowed())
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "valueToken" in unsafe mode
     *
     * @param string string to parse
     * @param allowedType value data type allowed
     * @return parsed value literal
     */
    private Value parseVariadicValueUnsafe(@Nonnull String string, @Nonnull Class<?> allowedType) {
        final ParseContext context = new ParseContext();
        context.setMode(ParseMode.UNSAFE);
        return ParserExecutor.execute(
            context,
            () -> ParserFactory.getParser(string).variadicValueTokens().accept(EvitaQLValueTokenVisitor.withAllowedTypes(allowedType))
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "literal"
     *
     * @param string string to parse
     * @return parsed value literal
     */
    private Value parseVariadicValue(@Nonnull String string) {
        return ParserExecutor.execute(
            new ParseContext(),
            () -> ParserFactory.getParser(string).variadicValueTokens().accept(EvitaQLValueTokenVisitor.withAllDataTypesAllowed())
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "literal"
     *
     * @param string string to parse
     * @param positionalArguments positional arguments to substitute
     * @return parsed value literal
     */
    private Value parseVariadicValue(@Nonnull String string, @Nonnull Object... positionalArguments) {
        return ParserExecutor.execute(
            new ParseContext(positionalArguments),
            () -> ParserFactory.getParser(string).variadicValueTokens().accept(EvitaQLValueTokenVisitor.withAllDataTypesAllowed())
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "literal"
     *
     * @param string string to parse
     * @param allowedType value data type allowed
     * @param positionalArguments positional arguments to substitute
     * @return parsed value literal
     */
    private Value parseVariadicValue(@Nonnull String string, @Nonnull Class<?> allowedType, @Nonnull Object... positionalArguments) {
        return ParserExecutor.execute(
            new ParseContext(positionalArguments),
            () -> ParserFactory.getParser(string).variadicValueTokens().accept(EvitaQLValueTokenVisitor.withAllowedTypes(allowedType))
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "literal"
     *
     * @param string string to parse
     * @param namedArguments named arguments to substitute
     * @return parsed value literal
     */
    private Value parseVariadicValue(@Nonnull String string, @Nonnull Map<String, Object> namedArguments) {
        return ParserExecutor.execute(
            new ParseContext(namedArguments),
            () -> ParserFactory.getParser(string).variadicValueTokens().accept(EvitaQLValueTokenVisitor.withAllDataTypesAllowed())
        );
    }

    /**
     * Using generated EvitaQL parser tries to parse string as grammar rule "literal"
     *
     * @param string string to parse
     * @param allowedType value data type allowed
     * @param namedArguments named arguments to substitute
     * @return parsed value literal
     */
    private Value parseVariadicValue(@Nonnull String string, @Nonnull Class<?> allowedType, @Nonnull Map<String, Object> namedArguments) {
        return ParserExecutor.execute(
            new ParseContext(namedArguments),
            () -> ParserFactory.getParser(string).variadicValueTokens().accept(EvitaQLValueTokenVisitor.withAllowedTypes(allowedType))
        );
    }
}
