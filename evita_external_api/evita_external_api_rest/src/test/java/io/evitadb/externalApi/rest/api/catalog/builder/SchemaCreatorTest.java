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

package io.evitadb.externalApi.rest.api.catalog.builder;

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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Locale;

import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createEnumSchema;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createSchemaByJavaType;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Description
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
class SchemaCreatorTest {

	@Test
	void shouldCreateStringSchema() {
		final var expectedSchema = "type: string";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(String.class)));
		assertEquals(expectedSchema, writeApiObjectToOneLine(new StringSchema()));
	}

	@Test
	void shouldCreateCharacterSchema() {
		final var expectedSchema = "type: string format: char maxLength: 1 minLength: 1";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(Character.class)));
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(char.class)));
	}

	@Test
	void shouldCreateIntegerSchema() {
		final var expectedSchema = "type: integer";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(Integer.class)));
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(int.class)));
	}

	@Test
	void shouldCreateShortSchema() {
		final var expectedSchema = "type: integer format: int16 example: 845";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(Short.class)));
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(short.class)));
	}

	@Test
	void shouldCreateArrayOfIntegersSchema() {
		final var expectedSchema = "type: array items: type: integer";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(Integer[].class)));
	}

	@Test
	void shouldCreateLongSchema() {
		final var expectedSchema = "type: string format: int64 example: \"685487\"";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(Long.class)));
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(long.class)));
	}

	@Test
	void shouldCreateBigDecimalSchema() {
		final var expectedSchema = "type: string format: decimal example: \"6584.25\" pattern: \"d+([.]d+)?\"";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(BigDecimal.class)));
	}

	@Test
	void shouldCreateByteSchema() {
		final var expectedSchema = "type: integer format: int8 example: 6";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(Byte.class)));
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(byte.class)));
	}

	@Test
	void shouldCreateBooleanSchema() {
		final var expectedSchema = "type: boolean";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(Boolean.class)));
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(boolean.class)));
	}

	@Test
	void shouldCreateOffsetLocalDateTimeSchema() {
		final var expectedSchema = "type: string format: date-time example: 2022-09-27T13:28:27.357442951+02:00";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(OffsetDateTime.class)));
	}

	@Test
	void shouldCreateLocalDateTimeSchema() {
		final var expectedSchema = "type: string format: local-date-time example: 2022-09-27T13:28:27.357442951";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(LocalDateTime.class)));
	}

	@Test
	void shouldCreateLocalDateSchema() {
		final var expectedSchema = "type: string format: date example: 2022-09-27";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(LocalDate.class)));
	}

	@Test
	void shouldCreateLocalTimeSchema() {
		final var expectedSchema = "type: string format: local-time example: 13:28:27.357442951";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(LocalTime.class)));
	}

	@Test
	void shouldCreateIntegerNumberRangeSchema() {
		final var expectedSchema = "type: array format: range items: type: integer maxItems: 2 minItems: 2";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(IntegerNumberRange.class)));
	}

	@Test
	void shouldCreateLongNumberRangeSchema() {
		final var expectedSchema = "type: array format: range items: type: string format: int64 example: \"685487\" maxItems: 2 minItems: 2";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(LongNumberRange.class)));
	}

	@Test
	void shouldCreateShortNumberRangeSchema() {
		final var expectedSchema = "type: array format: range items: type: integer format: int16 example: 845 maxItems: 2 minItems: 2";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(ShortNumberRange.class)));
	}

	@Test
	void shouldCreateBigDecimalNumberRangeSchema() {
		final var expectedSchema = "type: array format: range items: type: string format: decimal example: \"6584.25\" pattern: \"d+([.]d+)?\" maxItems: 2 minItems: 2";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(BigDecimalNumberRange.class)));
	}

	@Test
	void shouldCreateByteNumberRangeSchema() {
		final var expectedSchema = "type: array format: range items: type: integer format: int8 example: 6 maxItems: 2 minItems: 2";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(ByteNumberRange.class)));
	}

	@Test
	void shouldCreateDateTimeRangeSchema() {
		final var expectedSchema = "type: array format: range items: type: string format: date-time example: 2022-09-27T13:28:27.357442951+02:00 maxItems: 2 minItems: 2";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(DateTimeRange.class)));
	}


	@Test
	void shouldCreateComplexDataObjectSchema() {
		final var expectedSchema = "type: object properties: {}";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(ComplexDataObject.class)));
	}

	@Test
	void shouldCreateCurrencySchema() {
		final var expectedSchema = "type: string format: iso-4217 example: CZK";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(Currency.class)));
	}

	@Test
	void shouldCreateLocaleSchema() {
		final var expectedSchema = "type: string format: locale example: cs-CZ";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(Locale.class)));
	}

	@Test
	@Disabled("todo lho remove test probably, no longer applicable")
	void shouldCreateSchemaForSerializable() {
		final var expectedSchema = "type: string";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(Serializable.class)));
	}

	@Test
	void shouldCreateAttributeSpecialValueSchema() {
		//for some reason is NULL expression wrapped by quotation marks
		final var expectedSchema = "type: string enum: - \"NULL\" - NOT_NULL example: \"NULL\"";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaByJavaType(AttributeSpecialValue.class)));
	}

	@Test
	void shouldCreateOrderDirectionSchema() {
		final var expectedSchema = "type: string enum: - ASC - DESC example: ASC";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createEnumSchema(OrderDirection.class)));
	}

	public static String writeApiObjectToOneLine(Object schema) {
		return Yaml31.pretty(schema).replaceAll("\\n", " ").replaceAll(" +", " ").trim();
	}
}