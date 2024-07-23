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

package io.evitadb.externalApi.rest.api.catalog.builder;

import io.evitadb.externalApi.rest.api.openApi.OpenApiReferenceValidator;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static io.evitadb.externalApi.rest.api.openApi.OpenApiArray.arrayOf;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Description
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
class OpenApiReferenceValidatorTest {

	@Test
	void shouldSuccessfullyValidateSchemaWithPropertyReference() {
		final OpenAPI openAPI = new OpenAPI();

		final Components components = new Components();
		openAPI.components(components);

		final Schema<Object> firstSchema = new ObjectSchema();
		firstSchema.setName("firstSchema");
		components.addSchemas(firstSchema.getName(), firstSchema);

		final Schema<Object> secondSchema = new ObjectSchema();
		secondSchema.setName("secondSchema");
		secondSchema.addProperty("firstReference", typeRefTo(firstSchema.getName()).toSchema());
		components.addSchemas(secondSchema.getName(), secondSchema);

		final Set<String> missingSchemas = new OpenApiReferenceValidator(openAPI).validateSchemaReferences();
		assertTrue(missingSchemas.isEmpty());
	}

	@Test
	void shouldSuccessfullyValidateSchemaWithOneOfReference() {
		final OpenAPI openAPI = new OpenAPI();

		final Components components = new Components();
		openAPI.components(components);

		final Schema<Object> firstSchema = new ObjectSchema();
		firstSchema.setName("firstSchema");
		components.addSchemas(firstSchema.getName(), firstSchema);

		final Schema<Object> secondSchema = new ObjectSchema();
		secondSchema.setName("secondSchema");
		secondSchema.addOneOfItem(typeRefTo(firstSchema.getName()).toSchema());
		components.addSchemas(secondSchema.getName(), secondSchema);

		final Set<String> missingSchemas = new OpenApiReferenceValidator(openAPI).validateSchemaReferences();
		assertTrue(missingSchemas.isEmpty());
	}

	@Test
	void shouldFailToValidateSchemaWithPropertyReference() {
		final OpenAPI openAPI = new OpenAPI();

		final Components components = new Components();
		openAPI.components(components);

		final Schema<Object> firstSchema = new ObjectSchema();
		firstSchema.setName("firstSchema");
		//schema is not added in components

		final Schema<Object> secondSchema = new ObjectSchema();
		secondSchema.setName("secondSchema");
		secondSchema.addProperty("firstReference", typeRefTo(firstSchema.getName()).toSchema());
		components.addSchemas(secondSchema.getName(), secondSchema);

		final OpenApiReferenceValidator validator = new OpenApiReferenceValidator(openAPI);
		final Set<String> missingSchemas = validator.validateSchemaReferences();
		assertFalse(missingSchemas.isEmpty());

		final Optional<String> missingSchema = validator.getMissingSchemas().stream().findFirst();
		assertTrue(missingSchema.isPresent());
		assertEquals(firstSchema.getName(), missingSchema.get());
	}

	@Test
	void shouldFailToValidateSchemaWithArrayReference() {
		final OpenAPI openAPI = new OpenAPI();

		final Components components = new Components();
		openAPI.components(components);

		final Schema<Object> firstSchema = new ObjectSchema();
		firstSchema.setName("firstSchema");
		//schema is not added in components

		final Schema<Object> secondSchema = new ObjectSchema();
		secondSchema.setName("secondSchema");
		secondSchema.addProperty("firstReference", arrayOf(typeRefTo(firstSchema.getName())).toSchema());
		components.addSchemas(secondSchema.getName(), secondSchema);

		final OpenApiReferenceValidator validator = new OpenApiReferenceValidator(openAPI);
		final Set<String> missingSchemas = validator.validateSchemaReferences();
		assertFalse(missingSchemas.isEmpty());

		final Optional<String> missingSchema = validator.getMissingSchemas().stream().findFirst();
		assertTrue(missingSchema.isPresent());
		assertEquals(firstSchema.getName(), missingSchema.get());
	}

	@Test
	void shouldFailToValidateArraySchemaWithItemReference() {
		final OpenAPI openAPI = new OpenAPI();

		final Components components = new Components();
		openAPI.components(components);

		final Schema<Object> firstSchema = new ObjectSchema();
		firstSchema.setName("firstSchema");
		//schema is not added in components


		final Schema<?> arraySchema = arrayOf(typeRefTo(firstSchema.getName())).toSchema();
		arraySchema.name("arraySchema");
		components.addSchemas(arraySchema.getName(), arraySchema);

		final OpenApiReferenceValidator validator = new OpenApiReferenceValidator(openAPI);
		final Set<String> missingSchemas = validator.validateSchemaReferences();
		assertFalse(missingSchemas.isEmpty());

		final Optional<String> missingSchema = validator.getMissingSchemas().stream().findFirst();
		assertTrue(missingSchema.isPresent());
		assertEquals(firstSchema.getName(), missingSchema.get());
	}

	@Test
	void shouldFailValidateSchemaWithOneOfReference() {
		final OpenAPI openAPI = new OpenAPI();

		final Components components = new Components();
		openAPI.components(components);

		final Schema<Object> firstSchema = new ObjectSchema();
		firstSchema.setName("firstSchema");
		//schema is not added in components

		final Schema<Object> secondSchema = new ObjectSchema();
		secondSchema.setName("secondSchema");
		secondSchema.addOneOfItem(typeRefTo(firstSchema.getName()).toSchema());
		components.addSchemas(secondSchema.getName(), secondSchema);

		final OpenApiReferenceValidator validator = new OpenApiReferenceValidator(openAPI);
		final Set<String> missingSchemas = validator.validateSchemaReferences();
		assertFalse(missingSchemas.isEmpty());

		final Optional<String> missingSchema = validator.getMissingSchemas().stream().findFirst();
		assertTrue(missingSchema.isPresent());
		assertEquals(firstSchema.getName(), missingSchema.get());
	}
}
