/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.dataType.IntegerNumberRange;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.externalApi.rest.api.openApi.OpenApiArray.arrayOf;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiProperty.newProperty;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiScalar.scalarFrom;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiScalarTest.writeApiObjectToOneLine;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.REST;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that {@link OpenApiProperty#getSchema()} keeps the type readable by request handling and serializable by
 * the OpenAPI 3.1 writers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(REST)
@Tag(EXTERNAL_API)
@Tag(SERIALIZATION)
@DisplayName("OpenAPI property schema")
class OpenApiPropertyTest {

	/**
	 * Array-typed properties are copied into a plain schema, so the array type must survive the copy.
	 */
	@Test
	@DisplayName("Should declare array type when property is an array")
	void shouldDeclareArrayTypeWhenPropertyIsArray() {
		final Schema<?> schema = newProperty()
			.name("codes")
			.type(arrayOf(scalarFrom(String.class)))
			.build()
			.getSchema();

		assertEquals("array", schema.getType());
		assertEquals("type: array items: type: string", writeApiObjectToOneLine(schema));
	}

	/**
	 * Range scalars are represented by a two-item array, so they must declare the array type too.
	 */
	@Test
	@DisplayName("Should declare array type when property is a range")
	void shouldDeclareArrayTypeWhenPropertyIsRange() {
		final Schema<?> schema = newProperty()
			.name("validity")
			.type(scalarFrom(IntegerNumberRange.class))
			.build()
			.getSchema();

		assertEquals("array", schema.getType());
		assertEquals(
			"type: array items: type: integer format: int32 maxItems: 2 minItems: 2",
			writeApiObjectToOneLine(schema)
		);
	}

	/**
	 * Plain scalars are copied into a plain schema as well, so their scalar type must survive the copy.
	 */
	@Test
	@DisplayName("Should declare scalar type when property is a scalar")
	void shouldDeclareScalarTypeWhenPropertyIsScalar() {
		final Schema<?> schema = newProperty()
			.name("code")
			.type(scalarFrom(String.class))
			.build()
			.getSchema();

		assertEquals("string", schema.getType());
		assertEquals("type: string", writeApiObjectToOneLine(schema));
	}
}
