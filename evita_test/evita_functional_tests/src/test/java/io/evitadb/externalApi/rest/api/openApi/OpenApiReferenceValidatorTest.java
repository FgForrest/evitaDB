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

package io.evitadb.externalApi.rest.api.openApi;

import io.evitadb.externalApi.api.model.mutation.MutationDescriptor;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.callbacks.Callback;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Encoding;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.evitadb.externalApi.rest.api.openApi.OpenApiArray.arrayOf;
import static io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference.typeRefTo;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.REST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests component schema reference validation in schemas and endpoint definitions.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Tag(REST)
@Tag(EXTERNAL_API)
@Tag(QUERY)
@Tag(REFERENCE)
@DisplayName("OpenAPI component schema reference validation")
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

	/**
	 * Covers schema keywords that are not represented by the legacy array subclass or object properties.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Nested schema references")
	class NestedSchemaReferences {

		/**
		 * Each schema-valued Swagger keyword must expose an unresolved reference to the validator.
		 */
		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {
			"items", "additionalProperties", "not", "prefixItems", "contains", "contentSchema",
			"propertyNames", "unevaluatedProperties", "additionalItems", "unevaluatedItems",
			"if", "then", "else", "patternProperties", "dependentSchemas", "allOf", "anyOf", "refSibling"
		})
		@DisplayName("Should report a missing schema when nested in a schema keyword")
		void shouldReportMissingSchemaWhenNestedInKeyword(@Nonnull String keyword) {
			final Schema<Object> schema = new Schema<>();
			final Schema<?> reference = typeRefTo("Missing").toSchema();
			switch (keyword) {
				case "items" -> {
					schema.setTypes(Set.of("array"));
					schema.setItems(reference);
				}
				case "additionalProperties" -> schema.setAdditionalProperties(reference);
				case "not" -> schema.setNot(reference);
				case "prefixItems" -> schema.setPrefixItems(List.of(reference));
				case "contains" -> schema.setContains(reference);
				case "contentSchema" -> schema.setContentSchema(reference);
				case "propertyNames" -> schema.setPropertyNames(reference);
				case "unevaluatedProperties" -> schema.setUnevaluatedProperties(reference);
				case "additionalItems" -> schema.setAdditionalItems(reference);
				case "unevaluatedItems" -> schema.setUnevaluatedItems(reference);
				case "if" -> schema.setIf(reference);
				case "then" -> schema.setThen(reference);
				case "else" -> schema.setElse(reference);
				case "patternProperties" -> schema.setPatternProperties(Map.of(".*", reference));
				case "dependentSchemas" -> schema.setDependentSchemas(Map.of("value", reference));
				case "allOf" -> schema.setAllOf(List.of(reference));
				case "anyOf" -> schema.setAnyOf(List.of(reference));
				case "refSibling" -> {
					schema.set$ref("#/components/schemas/Root");
					schema.addProperty("sibling", reference);
				}
				default -> throw new IllegalArgumentException("Unknown schema keyword: " + keyword);
			}
			final OpenAPI openAPI = new OpenAPI().components(new Components().addSchemas("Root", schema));
			assertEquals(Set.of("Missing"), new OpenApiReferenceValidator(openAPI).validateSchemaReferences(), keyword);
		}

		/**
		 * Unnamed children and different objects sharing a name must be visited, while real cycles terminate.
		 */
		@Test
		@DisplayName("Should visit inline schemas when their names are absent or shared")
		void shouldVisitInlineSchemasWhenNamesAreAbsentOrShared() {
			final Schema<Object> root = new ObjectSchema().name("Shared");
			final Schema<Object> inline = new ObjectSchema();
			final Schema<Object> sameName = new ObjectSchema().name("Shared");
			sameName.addProperty("missing", typeRefTo("Missing").toSchema());
			inline.addProperty("sameName", sameName);
			inline.addProperty("cycle", root);
			root.addProperty("inline", inline);
			final OpenAPI openAPI = new OpenAPI().components(new Components().addSchemas("Root", root));
			assertEquals(Set.of("Missing"), new OpenApiReferenceValidator(openAPI).validateSchemaReferences());
		}

		/**
		 * Interfaces name their implementing types only in discriminator mapping values, never through `$ref`.
		 */
		@Test
		@DisplayName("Should report a missing schema when referenced by a discriminator mapping")
		void shouldReportMissingSchemaWhenReferencedByDiscriminatorMapping() {
			final Schema<Object> root = OpenApiInterface.newInterface()
				.name("Root")
				.discriminator(MutationDescriptor.MUTATION_TYPE)
				.implementingType(typeRefTo("Present"))
				.implementingType(typeRefTo("Missing"))
				.build()
				.toSchema();
			final OpenAPI openAPI = new OpenAPI().components(
				new Components().addSchemas("Root", root).addSchemas("Present", new ObjectSchema())
			);
			assertEquals(Set.of("Missing"), new OpenApiReferenceValidator(openAPI).validateSchemaReferences());
		}
	}

	/**
	 * Covers references reachable only from endpoint definitions rather than registered schemas.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Path schema references")
	class PathSchemaReferences {

		/**
		 * Request bodies, responses and both parameter forms can contain inline schema references.
		 */
		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {"requestBody", "response", "parameter", "parameterContent", "pathParameter",
			"responseHeader", "encodingHeader", "callback", "webhook", "componentRequestBody", "componentPath",
			"componentParameter", "componentResponse", "componentHeader", "componentCallback"})
		@DisplayName("Should report a missing schema when referenced by a path")
		void shouldReportMissingSchemaWhenReferencedByPath(@Nonnull String location) {
			final Schema<?> reference = typeRefTo("Missing").toSchema();
			final Content content = new Content().addMediaType("application/json", new MediaType().schema(reference));
			final Operation operation = new Operation();
			final PathItem path = new PathItem().post(operation);
			final Components components = new Components().addSchemas("Present", new ObjectSchema());
			final OpenAPI openAPI = new OpenAPI().components(components);
			switch (location) {
				case "requestBody" -> operation.setRequestBody(new RequestBody().content(content));
				case "response" -> operation.setResponses(
					new ApiResponses().addApiResponse("200", new ApiResponse().content(content))
				);
				case "parameter" -> operation.addParametersItem(new Parameter().schema(reference));
				case "parameterContent" -> operation.addParametersItem(new Parameter().content(content));
				case "pathParameter" -> path.addParametersItem(new Parameter().schema(reference));
				case "responseHeader" -> operation.setResponses(new ApiResponses().addApiResponse(
					"200", new ApiResponse().addHeaderObject("value", new Header().schema(reference))
				));
				case "encodingHeader" -> {
					final MediaType mediaType = new MediaType().addEncoding("value", new Encoding()
						.addHeader("value", new Header().schema(reference)));
					operation.setRequestBody(new RequestBody().content(
						new Content().addMediaType("multipart/form-data", mediaType)
					));
				}
				case "callback" -> {
					final PathItem callbackPath = new PathItem().post(new Operation()
						.requestBody(new RequestBody().content(content)));
					operation.addCallback("callback", new Callback().addPathItem("/callback", callbackPath));
				}
				case "webhook" -> openAPI.addWebhooks("event", new PathItem().post(new Operation()
					.requestBody(new RequestBody().content(content))));
				case "componentRequestBody" -> components.addRequestBodies("body", new RequestBody().content(content));
				case "componentPath" -> components.addPathItem("path", new PathItem()
					.addParametersItem(new Parameter().schema(reference)));
				case "componentParameter" -> components.addParameters("parameter", new Parameter().schema(reference));
				case "componentResponse" -> components.addResponses("response", new ApiResponse().content(content));
				case "componentHeader" -> components.addHeaders("header", new Header().schema(reference));
				case "componentCallback" -> components.addCallbacks("callback", new Callback().addPathItem(
					"/callback", new PathItem().post(new Operation().requestBody(new RequestBody().content(content)))
				));
				default -> throw new IllegalArgumentException("Unknown path location: " + location);
			}
			openAPI.paths(new Paths().addPathItem("/test", path));
			assertEquals(
				Set.of("Missing"), new OpenApiReferenceValidator(openAPI).validateSchemaReferences(), location
			);
		}

		/**
		 * A callback may lead back to the path that declares it, so the walk must stop at a path it already visited.
		 */
		@Test
		@DisplayName("Should report a missing schema when callbacks lead back to the declaring path")
		void shouldReportMissingSchemaWhenCallbacksFormCycle() {
			final Operation operation = new Operation().requestBody(new RequestBody().content(
				new Content().addMediaType("application/json", new MediaType().schema(typeRefTo("Missing").toSchema()))
			));
			final PathItem path = new PathItem().post(operation);
			operation.addCallback("cycle", new Callback().addPathItem("/cycle", path));
			final OpenAPI openAPI = new OpenAPI()
				.components(new Components().addSchemas("Present", new ObjectSchema()))
				.paths(new Paths().addPathItem("/test", path));
			assertEquals(Set.of("Missing"), new OpenApiReferenceValidator(openAPI).validateSchemaReferences());
		}
	}
}
