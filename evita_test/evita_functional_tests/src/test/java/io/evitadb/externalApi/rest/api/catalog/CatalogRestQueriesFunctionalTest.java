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

package io.evitadb.externalApi.rest.api.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.rest.RestProvider;
import io.evitadb.externalApi.rest.api.testSuite.RestEndpointFunctionalTest;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.tester.RestOpenApiAssertions;
import io.evitadb.test.tester.RestTester;
import io.evitadb.test.tester.RestTester.Request;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.REST_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REST;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for auxiliary catalog endpoints.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Tag(REST)
@Tag(EXTERNAL_API)
@Tag(QUERY)
@DisplayName("Auxiliary catalog REST endpoints")
class CatalogRestQueriesFunctionalTest extends RestEndpointFunctionalTest {

	private static final String REST_EMPTY_OPEN_API = "RestEmptyOpenApi";

	private static final String REST_NO_GLOBALLY_UNIQUE_OPEN_API = "RestNoGloballyUniqueOpenApi";

	private static final String REST_OPEN_API_REFERENCES = REST_THOUSAND_PRODUCTS + "OpenApiReferences";

	private static final String SCHEMAS = "/components/schemas/";
	private static final String ENTITY_REF = "#/components/schemas/Entity";
	private static final String ENTITY_UNION = "EntityUnion";
	private static final String LOCALIZED_ENTITY_UNION = "LocalizedEntityUnion";

	/**
	 * Reuses the REST fixtures with a small catalog and a reference configured for partial faceting.
	 */
	@DataSet(value = REST_OPEN_API_REFERENCES, openWebApi = RestProvider.CODE)
	@Nonnull
	protected DataCarrier setUpOpenApiReferences(@Nonnull Evita evita, @Nonnull EvitaServer evitaServer) {
		final DataCarrier data = super.setUpData(evita, evitaServer, 20, false);
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT)
				.withReferenceTo(
					"partiallyFaceted", "PartialFacetTarget", Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs.indexedForFilteringAndPartitioning().faceted()
						.withAttribute("priority", Integer.class, attribute -> attribute.filterable())
						.facetedPartially(ExpressionFactory.parse("$reference.attributes['priority'] > 0"))
				)
				.updateVia(session);
		});
		return data;
	}

	/**
	 * Prepares an empty catalog with globally unique attributes, including a localized one.
	 */
	@DataSet(value = REST_EMPTY_OPEN_API, openWebApi = RestProvider.CODE, readOnly = false, destroyAfterClass = true)
	@Nonnull
	protected DataCarrier setUpEmptyOpenApi(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.getCatalogSchema().openForWrite()
				.withAttribute("code", String.class, attribute -> attribute.uniqueGlobally())
				.withAttribute("url", String.class, attribute -> attribute.localized().uniqueGloballyWithinLocale())
				.updateVia(session);
		});
		return new DataCarrier();
	}

	/**
	 * Prepares a catalog with a localized collection but without any globally unique attribute, so no unknown-entity
	 * endpoint is built.
	 */
	@DataSet(value = REST_NO_GLOBALLY_UNIQUE_OPEN_API, openWebApi = RestProvider.CODE, destroyAfterClass = true)
	@Nonnull
	protected DataCarrier setUpNoGloballyUniqueOpenApi(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(Entities.PRODUCT)
				.withLocale(Locale.ENGLISH)
				.withAttribute("name", String.class, attribute -> attribute.localized())
				.updateVia(session);
		});
		return new DataCarrier();
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return OpenAPI specs")
	void shouldReturnOpenApiSpecs(Evita evita, RestTester tester) {
		tester.test(TEST_CATALOG)
			.httpMethod(Request.METHOD_GET)
			.acceptHeader("application/yaml")
			.executeAndThen()
			.statusCode(200)
			.body(notNullValue());
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should ignore trailing slash in endpoint URLs")
	void shouldIgnoreTrailingSlashInEndpointUrls(Evita evita, RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/collections")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.statusCode(200);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/collections/")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.statusCode(200);
	}

	/**
	 * Checks the served JSON independently of the in-memory OpenAPI validator.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Serialized OpenAPI references")
	class OpenApiReferences {

		/**
		 * Every reference in both served documents must resolve, including the partial-faceting array items.
		 */
		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {TEST_CATALOG, "system"})
		@UseDataSet(REST_OPEN_API_REFERENCES)
		@DisplayName("Should resolve every reference when serving an OpenAPI document")
		void shouldResolveEveryReferenceWhenServingDocument(
			@Nonnull String documentName, @Nonnull RestTester tester
		) throws IOException {
			final JsonNode document = RestOpenApiAssertions.fetchDocument(tester, documentName);
			RestOpenApiAssertions.assertReferencesResolve(document, documentName);
			if (TEST_CATALOG.equals(documentName)) {
				final JsonNode schemas = document.path("components").path("schemas");
				assertTrue(
					StreamSupport.stream(schemas.spliterator(), false).anyMatch(schema ->
						"#/components/schemas/ScopedFacetedPartially".equals(schema.path("properties")
							.path("facetedPartially").path("items").path("$ref").asText())),
					"A reference schema must expose facetedPartially.items.$ref to ScopedFacetedPartially"
				);
			}
		}

		/**
		 * Empty catalogs expose the plain `Entity` base object, and the entity unions appear only once the first
		 * collection triggers a REST schema refresh, without ever taking the base object's name.
		 */
		@Test
		@UseDataSet(REST_EMPTY_OPEN_API)
		@DisplayName("Should serve the Entity base object when empty and add the union after creating a collection")
		void shouldServeEntityBaseObjectWhenCatalogHasNoCollections(
			@Nonnull Evita evita, @Nonnull RestTester tester
		) throws IOException {
			assertTrue(evita.getCatalogInstanceOrThrowException(TEST_CATALOG).getSchema().getEntitySchemas().isEmpty());
			final JsonNode document = RestOpenApiAssertions.fetchDocument(tester, TEST_CATALOG);
			RestOpenApiAssertions.assertReferencesResolve(document, TEST_CATALOG);
			assertEntityBaseObject(document);
			assertTrue(document.findValues("$ref").stream()
				.anyMatch(ref -> ENTITY_REF.equals(ref.asText())));
			assertTrue(document.at(SCHEMAS + ENTITY_UNION).isMissingNode());
			assertTrue(document.at(SCHEMAS + LOCALIZED_ENTITY_UNION).isMissingNode());
			tester.test(TEST_CATALOG).httpMethod(Request.METHOD_GET).urlPathSuffix("/collections")
				.executeAndThen().statusCode(200);

			evita.updateCatalog(TEST_CATALOG, session -> {
				session.defineEntitySchema(Entities.PRODUCT).updateVia(session);
			});
			await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
				final JsonNode refreshed = RestOpenApiAssertions.fetchDocument(tester, TEST_CATALOG);
				final JsonNode union = refreshed.at(SCHEMAS + ENTITY_UNION + "/oneOf");
				assertTrue(union.isArray() && union.size() == 1,
					"EntityUnion must be a oneOf union after collection creation");
				assertEquals("#/components/schemas/Product", union.get(0).path("$ref").asText());
				assertEntityBaseObject(refreshed);
				RestOpenApiAssertions.assertReferencesResolve(refreshed, TEST_CATALOG);
			});
		}

		/**
		 * In a populated catalog every entity object extends the `Entity` base object, which is never a union, and
		 * the unknown-entity endpoints return the entity unions instead.
		 */
		@Test
		@UseDataSet(REST_OPEN_API_REFERENCES)
		@DisplayName("Should keep the Entity base object apart from the entity unions in a populated catalog")
		void shouldKeepEntityBaseObjectApartFromEntityUnions(@Nonnull RestTester tester) throws IOException {
			final JsonNode document = RestOpenApiAssertions.fetchDocument(tester, TEST_CATALOG);
			assertEntityBaseObject(document);

			for (String unionName : List.of(ENTITY_UNION, LOCALIZED_ENTITY_UNION)) {
				final JsonNode members = document.at(SCHEMAS + unionName + "/oneOf");
				assertTrue(members.isArray() && !members.isEmpty(), unionName + " must be a non-empty oneOf union");
				for (JsonNode member : members) {
					final String memberRef = member.path("$ref").asText();
					assertTrue(memberRef.startsWith("#/components/schemas/"),
						unionName + " members must reference component schemas, got `" + memberRef + "`");
					final JsonNode entityObject = document.at(memberRef.substring(1));
					assertTrue(
						StreamSupport.stream(entityObject.path("allOf").spliterator(), false)
							.anyMatch(item -> ENTITY_REF.equals(item.path("$ref").asText())),
						memberRef + " must extend " + ENTITY_REF + " via allOf"
					);
				}
			}

			final Map<String, JsonNode> successResponses = new HashMap<>();
			document.path("paths").forEach(path -> path.forEach(operation -> {
				if (operation.has("operationId")) {
					successResponses.put(
						operation.get("operationId").asText(), operation.path("responses").path("200")
					);
				}
			}));
			assertSuccessResponseReferences(successResponses, "getEntity", ENTITY_UNION);
			assertSuccessResponseReferences(successResponses, "listEntity", ENTITY_UNION);
			assertSuccessResponseReferences(successResponses, "getEntityLocalized", LOCALIZED_ENTITY_UNION);
			assertSuccessResponseReferences(successResponses, "listEntityLocalized", LOCALIZED_ENTITY_UNION);
		}

		/**
		 * Without globally unique attributes there is no unknown-entity endpoint, so the entity unions it would return
		 * are not registered either, while entity objects still extend the `Entity` base object.
		 */
		@Test
		@UseDataSet(REST_NO_GLOBALLY_UNIQUE_OPEN_API)
		@DisplayName("Should not register entity unions when no endpoint returns them")
		void shouldNotRegisterEntityUnionsWithoutGloballyUniqueAttributes(@Nonnull RestTester tester)
			throws IOException {
			final JsonNode document = RestOpenApiAssertions.fetchDocument(tester, TEST_CATALOG);
			RestOpenApiAssertions.assertReferencesResolve(document, TEST_CATALOG);
			assertEntityBaseObject(document);
			assertTrue(document.at(SCHEMAS + ENTITY_UNION).isMissingNode(), "EntityUnion must not be registered");
			assertTrue(document.at(SCHEMAS + LOCALIZED_ENTITY_UNION).isMissingNode(),
				"LocalizedEntityUnion must not be registered");
			for (String entityObject : List.of("Product", "ProductLocalized")) {
				assertTrue(
					StreamSupport.stream(document.at(SCHEMAS + entityObject + "/allOf").spliterator(), false)
						.anyMatch(item -> ENTITY_REF.equals(item.path("$ref").asText())),
					entityObject + " must extend " + ENTITY_REF + " via allOf"
				);
			}
		}

		/**
		 * Asserts that `Entity` is the plain base object with the common entity properties, not a union.
		 */
		private static void assertEntityBaseObject(@Nonnull JsonNode document) {
			final JsonNode entity = document.at(SCHEMAS + "Entity");
			assertEquals("object", entity.path("type").asText());
			final Set<String> properties = new HashSet<>();
			entity.path("properties").fieldNames().forEachRemaining(properties::add);
			assertEquals(Set.of("primaryKey", "type", "version", "scope"), properties);
			assertTrue(entity.path("oneOf").isMissingNode(), "Entity must not be a oneOf union");
			assertTrue(entity.path("anyOf").isMissingNode(), "Entity must not be an anyOf union");
		}

		/**
		 * Asserts that the 200 response of the given operation references exactly the given union.
		 */
		private static void assertSuccessResponseReferences(
			@Nonnull Map<String, JsonNode> successResponses,
			@Nonnull String operationId,
			@Nonnull String unionName
		) {
			final JsonNode response = successResponses.get(operationId);
			assertNotNull(response, "Operation " + operationId + " must be served");
			final Set<String> refs = new HashSet<>();
			response.findValues("$ref").forEach(ref -> refs.add(ref.asText()));
			assertEquals(Set.of("#/components/schemas/" + unionName), refs,
				"Operation " + operationId + " must return " + unionName);
		}
	}
}
