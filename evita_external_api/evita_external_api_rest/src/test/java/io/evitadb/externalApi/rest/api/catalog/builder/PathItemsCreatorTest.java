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

import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static io.evitadb.externalApi.rest.api.catalog.builder.PathItemsCreator.*;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreatorTest.writeApiObjectToOneLine;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Description
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
class PathItemsCreatorTest {

	@Test
	void shouldCreateMediaTypeAsReferenceToSchema() {
		final var expectedSchema = "schema: type: string";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createMediaType(SchemaCreator.createStringSchema())));
	}

	@Test
	void shouldCreateApplicationJsonContent() {
		final var expectedSchema = "application/json: schema: type: string";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createApplicationJsonContent(createMediaType(SchemaCreator.createStringSchema()))));
	}

	@Test
	void shouldCreateAndAddOkResponse() {
		final var apiResponses = new ApiResponses();
		createAndAddOkResponse(apiResponses, SchemaCreator.createStringSchema());
		final var expectedSchema = "\"200\": content: application/json: schema: type: string";
		assertEquals(expectedSchema, writeApiObjectToOneLine(apiResponses));
	}

	@Test
	void shouldCreateSchemaResponse() {
		final var expectedSchema = "content: application/json: schema: type: string";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaResponse(SchemaCreator.createStringSchema())));
	}

	@SuppressWarnings("unchecked")
	@Disabled("todo lho remove test probably, no longer applicable")
	@Test
	void shouldCreateRequestListSchema() {
		final var expectedSchema = "type: object properties: filterBy: $ref: '#/components/schemas/Filter' orderBy: $ref: '#/components/schemas/Order'";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createRequestListSchema(SchemaCreator.createStringSchema().name("Filter"), SchemaCreator.createStringSchema().name("Order"), null)));
	}

	@SuppressWarnings("unchecked")
	@Test
	@Disabled("todo lho remove test probably, no longer applicable")
	void shouldCreateRequestQuerySchemaWithoutRequired() {
		final var expectedSchema = "type: object properties: filterBy: $ref: '#/components/schemas/Filter' orderBy: $ref: '#/components/schemas/Order'";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createRequestQuerySchema(SchemaCreator.createStringSchema().name("Filter"), SchemaCreator.createStringSchema().name("Order"), null)));
	}

	@SuppressWarnings("unchecked")
	@Test
	@Disabled("todo lho remove test probably, no longer applicable")
	void shouldCreateRequestQuerySchemaWithRequired() {
		final var expectedSchema = "type: object properties: filterBy: $ref: '#/components/schemas/Filter' orderBy: $ref: '#/components/schemas/Order' require: $ref: '#/components/schemas/Required'";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createRequestQuerySchema(SchemaCreator.createStringSchema().name("Filter"), SchemaCreator.createStringSchema().name("Order"), SchemaCreator.createStringSchema().name("Required"))));
	}

	@Test
	void shouldCreateSchemaArrayResponse() {
		final var expectedSchema = "content: application/json: schema: type: array items: type: string";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaArrayResponse(SchemaCreator.createStringSchema())));
	}

	@SuppressWarnings("unchecked")
	@Test
	void shouldCreateSchemaArrayResponseAsReference() {
		final var expectedSchema = "content: application/json: schema: type: array items: $ref: '#/components/schemas/testSchema'";
		assertEquals(expectedSchema, writeApiObjectToOneLine(createSchemaArrayResponse(SchemaCreator.createReferenceSchema(SchemaCreator.createStringSchema().name("testSchema")))));
	}
}