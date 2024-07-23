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

package io.evitadb.externalApi.rest.api.openApi;

import io.evitadb.api.query.filter.AttributeSpecialValue;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ByteNumberRange;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.ShortNumberRange;
import io.swagger.v3.core.util.Yaml31;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Locale;
import java.util.UUID;

import static io.evitadb.externalApi.rest.api.openApi.OpenApiArray.arrayOf;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiEnum.enumFrom;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiScalar.scalarFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link OpenApiScalar}
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
class OpenApiScalarTest {

	@Test
	void shouldCreateStringSchema() {
		final var expectedSchema = "type: string";
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(String.class).toSchema()));
		assertEquals(expectedSchema, writeApiObjectToOneLine(new StringSchema()));
	}

	@Test
	void shouldCreateCharacterSchema() {
		final var expectedSchema = "type: string format: char maxLength: 1 minLength: 1";
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(Character.class).toSchema()));
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(char.class).toSchema()));
	}

	@Test
	void shouldCreateIntegerSchema() {
		final var expectedSchema = "type: integer format: int32";
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(Integer.class).toSchema()));
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(int.class).toSchema()));
	}

	@Test
	void shouldCreateShortSchema() {
		final var expectedSchema = "type: integer format: int16 example: 845";
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(Short.class).toSchema()));
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(short.class).toSchema()));
	}

	@Test
	void shouldCreateArrayOfIntegersSchema() {
		final var expectedSchema = "type: array items: type: integer format: int32";
		assertEquals(expectedSchema, writeApiObjectToOneLine(arrayOf(scalarFrom(Integer.class)).toSchema()));
	}

	@Test
	void shouldCreateLongSchema() {
		final var expectedSchema = "type: string format: int64 example: \"685487\"";
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(Long.class).toSchema()));
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(long.class).toSchema()));
	}

	@Test
	void shouldCreateBigDecimalSchema() {
		final var expectedSchema = "type: string format: decimal example: \"6584.25\" pattern: \"d+([.]d+)?\"";
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(BigDecimal.class).toSchema()));
	}

	@Test
	void shouldCreateByteSchema() {
		final var expectedSchema = "type: integer format: int8 example: 6";
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(Byte.class).toSchema()));
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(byte.class).toSchema()));
	}

	@Test
	void shouldCreateBooleanSchema() {
		final var expectedSchema = "type: boolean";
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(Boolean.class).toSchema()));
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(boolean.class).toSchema()));
	}

	@Test
	void shouldCreateOffsetLocalDateTimeSchema() {
		final var expectedSchema = "type: string format: date-time example: 2022-09-27T13:28:27.357+02:00";
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(OffsetDateTime.class).toSchema()));
	}

	@Test
	void shouldCreateLocalDateTimeSchema() {
		final var expectedSchema = "type: string format: local-date-time example: 2022-09-27T13:28:27.357";
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(LocalDateTime.class).toSchema()));
	}

	@Test
	void shouldCreateLocalDateSchema() {
		final var expectedSchema = "type: string format: date example: 2022-09-27";
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(LocalDate.class).toSchema()));
	}

	@Test
	void shouldCreateLocalTimeSchema() {
		final var expectedSchema = "type: string format: local-time example: 13:28:27.357";
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(LocalTime.class).toSchema()));
	}

	@Test
	void shouldCreateIntegerNumberRangeSchema() {
		final var expectedSchema = "type: array format: range items: type: integer format: int32 maxItems: 2 minItems: 2";
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(IntegerNumberRange.class).toSchema()));
	}

	@Test
	void shouldCreateLongNumberRangeSchema() {
		final var expectedSchema = "type: array format: range items: type: string format: int64 example: \"685487\" maxItems: 2 minItems: 2";
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(LongNumberRange.class).toSchema()));
	}

	@Test
	void shouldCreateShortNumberRangeSchema() {
		final var expectedSchema = "type: array format: range items: type: integer format: int16 example: 845 maxItems: 2 minItems: 2";
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(ShortNumberRange.class).toSchema()));
	}

	@Test
	void shouldCreateBigDecimalNumberRangeSchema() {
		final var expectedSchema = "type: array format: range items: type: string format: decimal example: \"6584.25\" pattern: \"d+([.]d+)?\" maxItems: 2 minItems: 2";
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(BigDecimalNumberRange.class).toSchema()));
	}

	@Test
	void shouldCreateByteNumberRangeSchema() {
		final var expectedSchema = "type: array format: range items: type: integer format: int8 example: 6 maxItems: 2 minItems: 2";
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(ByteNumberRange.class).toSchema()));
	}

	@Test
	void shouldCreateDateTimeRangeSchema() {
		final var expectedSchema = "type: array format: range items: type: string format: date-time example: 2022-09-27T13:28:27.357+02:00 maxItems: 2 minItems: 2";
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(DateTimeRange.class).toSchema()));
	}


	@Test
	void shouldCreateComplexDataObjectSchema() {
		final var expectedSchema = "type: object additionalProperties: true";
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(ComplexDataObject.class).toSchema()));
	}

	@Test
	void shouldCreateCurrencySchema() {
		final var expectedSchema = "type: string format: iso-4217 example: CZK";
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(Currency.class).toSchema()));
	}

	@Test
	void shouldCreateUuidSchema() {
		final var expectedSchema = "type: string format: uuid example: 01081e6f-851f-46b1-9f8f-075b582b5d2e";
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(UUID.class).toSchema()));
	}

	@Test
	void shouldCreateLocaleSchema() {
		final var expectedSchema = "type: string format: locale example: cs-CZ";
		assertEquals(expectedSchema, writeApiObjectToOneLine(scalarFrom(Locale.class).toSchema()));
	}

	@Test
	void shouldCreateAttributeSpecialValueSchema() {
		//for some reason is NULL expression wrapped by quotation marks
		final var expectedSchema = "type: string enum: - \"NULL\" - NOT_NULL example: \"NULL\"";
		assertEquals(expectedSchema, writeApiObjectToOneLine(enumFrom(AttributeSpecialValue.class).toSchema()));
	}

	@Test
	void shouldCreateOrderDirectionSchema() {
		final var expectedSchema = "type: string enum: - ASC - DESC example: ASC";
		assertEquals(expectedSchema, writeApiObjectToOneLine(enumFrom(OrderDirection.class).toSchema()));
	}

	public static String writeApiObjectToOneLine(Object schema) {
		return Yaml31.pretty(schema).replaceAll("\\n", " ").replaceAll(" +", " ").trim();
	}
}
