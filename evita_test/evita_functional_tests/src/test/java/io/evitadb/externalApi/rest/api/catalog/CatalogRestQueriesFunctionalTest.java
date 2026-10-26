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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.rest.api.testSuite.RestEndpointFunctionalTest;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.tester.RestTester;
import io.evitadb.test.tester.RestTester.Request;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.http.MimeTypes.APPLICATION_JSON;
import static io.evitadb.externalApi.http.MimeTypes.APPLICATION_YAML;
import static io.evitadb.externalApi.rest.api.testSuite.OpenApiAssertions.assertArraySchemasDeclareType;
import static io.evitadb.externalApi.rest.api.testSuite.OpenApiAssertions.assertDeclaresOpenApi31;
import static io.evitadb.externalApi.rest.api.testSuite.OpenApiAssertions.parseSpecification;
import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.REST_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REST;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for auxiliary catalog endpoints.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Tag(REST)
@Tag(EXTERNAL_API)
@Tag(QUERY)
@DisplayName("Auxiliary REST catalog endpoints")
class CatalogRestQueriesFunctionalTest extends RestEndpointFunctionalTest {

	/**
	 * Verifies the version and array types declared by the served OpenAPI representations.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@Tag(SERIALIZATION)
	@DisplayName("OpenAPI specification")
	class OpenApiSpecification {

		/**
		 * Fetches each supported representation and checks its explicit OpenAPI version.
		 */
		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {APPLICATION_JSON, APPLICATION_YAML})
		@UseDataSet(REST_THOUSAND_PRODUCTS)
		@DisplayName("Should declare OpenAPI 3.1.0 when requesting a supported representation")
		void shouldDeclareOpenApi31WhenRequestingSupportedRepresentation(
			@Nonnull final String mediaType,
			@Nonnull final RestTester tester
		) throws JsonProcessingException {
			assertDeclaresOpenApi31(fetchSpecification(mediaType, tester));
		}

		/**
		 * Checks every schema with items, including property schemas, in each served representation.
		 */
		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {APPLICATION_JSON, APPLICATION_YAML})
		@UseDataSet(REST_THOUSAND_PRODUCTS)
		@DisplayName("Should declare array types when requesting a supported representation")
		void shouldDeclareArrayTypesWhenRequestingSupportedRepresentation(
			@Nonnull final String mediaType,
			@Nonnull final RestTester tester
		) throws JsonProcessingException {
			final JsonNode specification = fetchSpecification(mediaType, tester);
			assertArraySchemasDeclareType(specification);
			final JsonNode validity = specification.at("/components/schemas/Price/properties/validity");
			assertNotNull(validity.get("items"), "Price validity must declare its range item schema.");
			assertEquals(2, validity.path("minItems").asInt(), "Price validity must be a two-bound range scalar.");
			assertEquals(2, validity.path("maxItems").asInt(), "Price validity must be a two-bound range scalar.");
		}

		/**
		 * Fetches and parses an OpenAPI document using the requested representation's parser.
		 */
		@Nonnull
		private JsonNode fetchSpecification(
			@Nonnull final String mediaType,
			@Nonnull final RestTester tester
		) throws JsonProcessingException {
			final String responseBody = tester.test(TEST_CATALOG)
				.httpMethod(Request.METHOD_GET)
				.acceptHeader(mediaType)
				.executeAndExpectOkAndThen()
				.contentType(mediaType)
				.extract().asString();
			return parseSpecification(mediaType, responseBody);
		}
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
}
