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

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.addReferenceSchemaAsOneOf;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createArraySchemaOf;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createObjectSchema;
import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createReferenceSchema;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Description
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
class OpenApiSchemaReferenceValidatorTest {

	@Test
	void shouldSuccessfullyValidateSchemaWithPropertyReference() {
		final OpenAPI openAPI = new OpenAPI();

		final Components components = new Components();
		openAPI.components(components);

		final Schema<Object> firstSchema = createObjectSchema();
		firstSchema.setName("firstSchema");
		components.addSchemas(firstSchema.getName(), firstSchema);

		final Schema<Object> secondSchema = createObjectSchema();
		secondSchema.setName("secondSchema");
		secondSchema.addProperty("firstReference", createReferenceSchema(firstSchema));
		components.addSchemas(secondSchema.getName(), secondSchema);

		final boolean valid = new OpenApiSchemaReferenceValidator(openAPI).validateSchemaReferences();
		assertTrue(valid);
	}

	@Test
	void shouldSuccessfullyValidateSchemaWithOneOfReference() {
		final OpenAPI openAPI = new OpenAPI();

		final Components components = new Components();
		openAPI.components(components);

		final Schema<Object> firstSchema = createObjectSchema();
		firstSchema.setName("firstSchema");
		components.addSchemas(firstSchema.getName(), firstSchema);

		final Schema<Object> secondSchema = createObjectSchema();
		secondSchema.setName("secondSchema");
		addReferenceSchemaAsOneOf(secondSchema, firstSchema);
		components.addSchemas(secondSchema.getName(), secondSchema);

		final boolean valid = new OpenApiSchemaReferenceValidator(openAPI).validateSchemaReferences();
		assertTrue(valid);
	}

	@Test
	void shouldFailToValidateSchemaWithPropertyReference() {
		final OpenAPI openAPI = new OpenAPI();

		final Components components = new Components();
		openAPI.components(components);

		final Schema<Object> firstSchema = createObjectSchema();
		firstSchema.setName("firstSchema");
		//schema is not added in components

		final Schema<Object> secondSchema = createObjectSchema();
		secondSchema.setName("secondSchema");
		secondSchema.addProperty("firstReference", createReferenceSchema(firstSchema));
		components.addSchemas(secondSchema.getName(), secondSchema);

		final OpenApiSchemaReferenceValidator validator = new OpenApiSchemaReferenceValidator(openAPI);
		final boolean valid = validator.validateSchemaReferences();
		assertFalse(valid);

		final Optional<String> missingSchema = validator.getMissingSchemas().stream().findFirst();
		assertTrue(missingSchema.isPresent());
		assertEquals(firstSchema.getName(), missingSchema.get());
	}

	@Test
	void shouldFailToValidateSchemaWithArrayReference() {
		final OpenAPI openAPI = new OpenAPI();

		final Components components = new Components();
		openAPI.components(components);

		final Schema<Object> firstSchema = createObjectSchema();
		firstSchema.setName("firstSchema");
		//schema is not added in components

		final Schema<Object> secondSchema = createObjectSchema();
		secondSchema.setName("secondSchema");
		secondSchema.addProperty("firstReference", createArraySchemaOf(createReferenceSchema(firstSchema)));
		components.addSchemas(secondSchema.getName(), secondSchema);

		final OpenApiSchemaReferenceValidator validator = new OpenApiSchemaReferenceValidator(openAPI);
		final boolean valid = validator.validateSchemaReferences();
		assertFalse(valid);

		final Optional<String> missingSchema = validator.getMissingSchemas().stream().findFirst();
		assertTrue(missingSchema.isPresent());
		assertEquals(firstSchema.getName(), missingSchema.get());
	}

	@Test
	void shouldFailToValidateArraySchemaWithItemReference() {
		final OpenAPI openAPI = new OpenAPI();

		final Components components = new Components();
		openAPI.components(components);

		final Schema<Object> firstSchema = createObjectSchema();
		firstSchema.setName("firstSchema");
		//schema is not added in components


		final ArraySchema arraySchema = createArraySchemaOf(createReferenceSchema(firstSchema));
		arraySchema.name("arraySchema");
		components.addSchemas(arraySchema.getName(), arraySchema);

		final OpenApiSchemaReferenceValidator validator = new OpenApiSchemaReferenceValidator(openAPI);
		final boolean valid = validator.validateSchemaReferences();
		assertFalse(valid);

		final Optional<String> missingSchema = validator.getMissingSchemas().stream().findFirst();
		assertTrue(missingSchema.isPresent());
		assertEquals(firstSchema.getName(), missingSchema.get());
	}

	@Test
	void shouldFailValidateSchemaWithOneOfReference() {
		final OpenAPI openAPI = new OpenAPI();

		final Components components = new Components();
		openAPI.components(components);

		final Schema<Object> firstSchema = createObjectSchema();
		firstSchema.setName("firstSchema");
		//schema is not added in components

		final Schema<Object> secondSchema = createObjectSchema();
		secondSchema.setName("secondSchema");
		addReferenceSchemaAsOneOf(secondSchema, firstSchema);
		components.addSchemas(secondSchema.getName(), secondSchema);

		final OpenApiSchemaReferenceValidator validator = new OpenApiSchemaReferenceValidator(openAPI);
		final boolean valid = validator.validateSchemaReferences();
		assertFalse(valid);

		final Optional<String> missingSchema = validator.getMissingSchemas().stream().findFirst();
		assertTrue(missingSchema.isPresent());
		assertEquals(firstSchema.getName(), missingSchema.get());
	}
}