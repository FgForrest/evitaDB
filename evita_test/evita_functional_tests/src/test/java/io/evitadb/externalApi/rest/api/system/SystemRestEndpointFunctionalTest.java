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

package io.evitadb.externalApi.rest.api.system;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.core.Evita;
import io.evitadb.core.transaction.engine.EngineMutationProgressStatus;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NameVariantsDescriptor;
import io.evitadb.externalApi.api.system.model.CatalogDescriptor;
import io.evitadb.externalApi.api.system.model.progress.EngineMutationProgressHandleDescriptor;
import io.evitadb.externalApi.rest.RestProvider;
import io.evitadb.externalApi.rest.api.system.model.LivenessDescriptor;
import io.evitadb.externalApi.rest.api.testSuite.RestEndpointFunctionalTest;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.tester.RestTester;
import io.evitadb.test.tester.RestTester.Request;
import io.evitadb.utils.NamingConvention;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

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
import static io.evitadb.utils.MapBuilder.map;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for REST system management endpoints.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Tag(REST)
@Tag(EXTERNAL_API)
@Tag(QUERY)
@DisplayName("REST system management endpoints")
class SystemRestEndpointFunctionalTest extends RestEndpointFunctionalTest {

	private static final String SYSTEM_URL = "system";
	public static final String REST_THOUSAND_PRODUCTS_FOR_REPLACE = REST_THOUSAND_PRODUCTS + "forReplace";

	@Override
	@DataSet(value = REST_THOUSAND_PRODUCTS_FOR_REPLACE, openWebApi = RestProvider.CODE, readOnly = false, destroyAfterClass = true)
	protected DataCarrier setUp(Evita evita, EvitaServer evitaServer) {
		return super.setUpData(evita, evitaServer, 20, false);
	}

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
		@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_REPLACE)
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
		@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_REPLACE)
		@DisplayName("Should declare array types when requesting a supported representation")
		void shouldDeclareArrayTypesWhenRequestingSupportedRepresentation(
			@Nonnull final String mediaType,
			@Nonnull final RestTester tester
		) throws JsonProcessingException {
			final JsonNode specification = fetchSpecification(mediaType, tester);
			assertArraySchemasDeclareType(specification);
		}

		/**
		 * Fetches and parses an OpenAPI document using the requested representation's parser.
		 */
		@Nonnull
		private JsonNode fetchSpecification(
			@Nonnull final String mediaType,
			@Nonnull final RestTester tester
		) throws JsonProcessingException {
			final String responseBody = tester.test(SYSTEM_URL)
				.httpMethod(Request.METHOD_GET)
				.acceptHeader(mediaType)
				.executeAndExpectOkAndThen()
				.contentType(mediaType)
				.extract().asString();
			return parseSpecification(mediaType, responseBody);
		}
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_REPLACE)
	@DisplayName("Should be alive")
	void shouldBeAlive(Evita evita, RestTester tester) {
		tester.test(SYSTEM_URL)
			.urlPathSuffix("/liveness")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.statusCode(200)
			.body(
				"",
				equalTo(
					map()
						.e(LivenessDescriptor.LIVENESS.name(), true)
						.build()
				)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_REPLACE)
	@DisplayName("Should return specific catalog")
	void shouldReturnSpecificCatalog(Evita evita, RestTester tester) {
		final CatalogContract testCatalog = evita.getCatalogInstanceOrThrowException(TEST_CATALOG);
		createCatalogDto(testCatalog);

		tester.test(SYSTEM_URL)
			.urlPathSuffix("/catalogs/testCatalog")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createCatalogDto(testCatalog)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_REPLACE)
	@DisplayName("Should return all catalogs")
	void shouldReturnAllCatalogs(Evita evita, RestTester tester) {
		tester.test(SYSTEM_URL)
			.urlPathSuffix("/catalogs")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.statusCode(200)
			.body(
				"",
				equalTo(
					evita.getCatalogs()
						.stream()
						.map(SystemRestEndpointFunctionalTest::createCatalogDto)
						.toList()
				)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_REPLACE)
	@DisplayName("Should return error if specific Evita catalog doesn't exist")
	void shouldReturnErrorIfSpecificCatalogDoesntExist(Evita evita, RestTester tester) {
		tester.test(SYSTEM_URL)
			.urlPathSuffix("/catalogs/something-else")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.statusCode(404);
	}


	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_REPLACE)
	@DisplayName("Should create, rename and delete catalog")
	void shouldCreateRenameAndDeleteCatalog(Evita evita, RestTester tester) {
		// prepare temp catalog
		tester.test(SYSTEM_URL)
			.urlPathSuffix("/catalogs")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"name": "temporaryCatalog"
					}
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createCatalogDto(evita.getCatalogInstanceOrThrowException("temporaryCatalog"))));

		// rename
		tester.test(SYSTEM_URL)
			.urlPathSuffix("/catalogs/temporaryCatalog")
			.httpMethod(Request.METHOD_PATCH)
			.requestBody(
				"""
					{
						"name": "temporaryCatalog2"
					}
					"""
			)
			.executeAndExpectOkAndThen()
			.body(
				"",
				equalTo(
					createCatalogDto(evita.getCatalogInstanceOrThrowException("temporaryCatalog2"))
				)
			);

		// delete
		tester.test(SYSTEM_URL)
			.urlPathSuffix("/catalogs/temporaryCatalog2")
			.httpMethod(Request.METHOD_DELETE)
			.executeAndExpectOkWithoutBodyAndThen();
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_REPLACE)
	@DisplayName("Should create, switch to alive and delete catalog")
	void shouldCreateSwitchToAliveAndDeleteCatalog(Evita evita, RestTester tester) {
		// prepare temp catalog
		tester.test(SYSTEM_URL)
			.urlPathSuffix("/catalogs")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"name": "temporaryCatalog"
					}
					"""
			)
			.executeAndExpectOkAndThen()
			.body("catalogState", equalTo(CatalogState.WARMING_UP.name()));

		// switch to alive
		tester.test(SYSTEM_URL)
			.urlPathSuffix("/catalogs/temporaryCatalog")
			.httpMethod(Request.METHOD_PATCH)
			.requestBody(
				"""
					{
						"state": "ALIVE"
					}
					"""
			)
			.executeAndExpectOkAndThen()
			.body("catalogState", equalTo(CatalogState.ALIVE.name()));

		// delete
		tester.test(SYSTEM_URL)
			.urlPathSuffix("/catalogs/temporaryCatalog")
			.httpMethod(Request.METHOD_DELETE)
			.executeAndExpectOkWithoutBodyAndThen();
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_REPLACE)
	@DisplayName("Should not delete unknown catalog")
	void shouldNotDeleteUnknownCatalog(Evita evita, RestTester tester) {
		tester.test(SYSTEM_URL)
			.urlPathSuffix("/catalogs/unknown")
			.httpMethod(Request.METHOD_DELETE)
			.executeAndThen()
			.statusCode(404);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_REPLACE)
	@DisplayName("Should replace catalog")
	void shouldReplaceCatalog(Evita evita, RestTester tester) {
		// create new temporary catalog
		tester.test(SYSTEM_URL)
			.urlPathSuffix("/catalogs")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"name": "temporaryCatalog"
					}
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createCatalogDto(evita.getCatalogInstanceOrThrowException("temporaryCatalog"))));

		// replace temporary catalog with test catalog: `{name}` is the catalog to be replaced with, `{newName}` the
		// catalog to be replaced
		tester.test(SYSTEM_URL)
			.urlPathSuffix("/catalogs/testCatalog/actions/replace/temporaryCatalog")
			.httpMethod(Request.METHOD_POST)
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createCatalogDto(evita.getCatalogInstanceOrThrowException("temporaryCatalog"))));

		// recreate test catalog
		tester.test(SYSTEM_URL)
			.urlPathSuffix("/catalogs")
			.httpMethod(Request.METHOD_POST)
			.requestBody(
				"""
					{
						"name": "%s"
					}
					""",
				TEST_CATALOG
			)
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createCatalogDto(evita.getCatalogInstanceOrThrowException(TEST_CATALOG))));

		// replace temporary catalog back to test catalog
		tester.test(SYSTEM_URL)
			.urlPathSuffix("/catalogs/temporaryCatalog/actions/replace/" + TEST_CATALOG)
			.httpMethod(Request.METHOD_POST)
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createCatalogDto(evita.getCatalogInstanceOrThrowException(TEST_CATALOG))));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_REPLACE)
	@DisplayName("Should duplicate, deactivate, activate and change mutability of catalog")
	void shouldDuplicateDeactivateActivateAndChangeMutability(Evita evita, RestTester tester) {
		createCatalog(tester, "restMutabilitySource");
		patchCatalog(tester, "/catalogs/restMutabilitySource", "{\"state\": \"ALIVE\"}")
			.statusCode(200)
			.body(CatalogDescriptor.CATALOG_STATE.name(), equalTo(CatalogState.ALIVE.name()));

		// a duplicate is created inactive - the response is an unusable catalog
		tester.test(SYSTEM_URL)
			.urlPathSuffix("/catalogs/restMutabilitySource/actions/duplicate/restMutabilityCopy")
			.httpMethod(Request.METHOD_POST)
			.executeAndThen()
			.statusCode(200)
			.body(CatalogDescriptor.NAME.name(), equalTo("restMutabilityCopy"))
			.body(CatalogDescriptor.CATALOG_STATE.name(), equalTo(CatalogState.INACTIVE.name()))
			.body(CatalogDescriptor.UNUSABLE.name(), equalTo(true));

		patchCatalog(tester, "/catalogs/restMutabilityCopy", "{\"state\": \"ALIVE\"}")
			.statusCode(200)
			.body(CatalogDescriptor.CATALOG_STATE.name(), equalTo(CatalogState.ALIVE.name()))
			.body(CatalogDescriptor.READ_ONLY.name(), equalTo(false));
		patchCatalog(tester, "/catalogs/restMutabilityCopy", "{\"readOnly\": true}")
			.statusCode(200)
			.body(CatalogDescriptor.READ_ONLY.name(), equalTo(true));
		// the read-only flag is reported also for an inactive catalog
		patchCatalog(tester, "/catalogs/restMutabilityCopy", "{\"state\": \"INACTIVE\"}")
			.statusCode(200)
			.body(CatalogDescriptor.CATALOG_STATE.name(), equalTo(CatalogState.INACTIVE.name()))
			.body(CatalogDescriptor.READ_ONLY.name(), equalTo(true));
		patchCatalog(tester, "/catalogs/restMutabilityCopy", "{\"state\": \"ALIVE\"}")
			.statusCode(200)
			.body(CatalogDescriptor.CATALOG_STATE.name(), equalTo(CatalogState.ALIVE.name()));
		patchCatalog(tester, "/catalogs/restMutabilityCopy", "{\"readOnly\": false}")
			.statusCode(200)
			.body(CatalogDescriptor.READ_ONLY.name(), equalTo(false));

		deleteCatalog(tester, "restMutabilitySource");
		deleteCatalog(tester, "restMutabilityCopy");
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_REPLACE)
	@DisplayName("Should refuse a change request that does not contain exactly one change")
	void shouldRefuseInvalidChangeRequest(Evita evita, RestTester tester) {
		patchCatalog(tester, "/catalogs/" + TEST_CATALOG, "{\"name\": \"other\", \"readOnly\": true}")
			.statusCode(400);
		patchCatalog(tester, "/catalogs-async/" + TEST_CATALOG, "{\"name\": \"other\", \"readOnly\": true}")
			.statusCode(400);
		patchCatalog(tester, "/catalogs/" + TEST_CATALOG, "{}")
			.statusCode(400);
		patchCatalog(tester, "/catalogs/" + TEST_CATALOG, "{\"state\": \"WARMING_UP\"}")
			.statusCode(400);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_REPLACE)
	@DisplayName("Should answer 404 for an operation on an unknown catalog")
	void shouldReturnNotFoundForUnknownCatalog(Evita evita, RestTester tester) {
		patchCatalog(tester, "/catalogs/unknown", "{\"readOnly\": true}").statusCode(404);
		patchCatalog(tester, "/catalogs-async/unknown", "{\"readOnly\": true}").statusCode(404);
		tester.test(SYSTEM_URL)
			.urlPathSuffix("/catalogs-async/unknown")
			.httpMethod(Request.METHOD_DELETE)
			.executeAndThen()
			.statusCode(404);
		tester.test(SYSTEM_URL)
			.urlPathSuffix("/catalogs/unknown/actions/duplicate/unknownCopy")
			.httpMethod(Request.METHOD_POST)
			.executeAndThen()
			.statusCode(404);
		tester.test(SYSTEM_URL)
			.urlPathSuffix("/catalogs-async/unknown/actions/replace/" + TEST_CATALOG)
			.httpMethod(Request.METHOD_POST)
			.executeAndThen()
			.statusCode(404);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_REPLACE)
	@DisplayName("Should accept asynchronous operations with a progress handle")
	void shouldAcceptAsynchronousOperations(Evita evita, RestTester tester) {
		createCatalog(tester, "restAsyncCatalog");

		final String progressId = patchCatalog(tester, "/catalogs-async/restAsyncCatalog", "{\"state\": \"ALIVE\"}")
			.statusCode(202)
			.body(EngineMutationProgressHandleDescriptor.PROGRESS_ID.name(), notNullValue())
			.extract()
			.path(EngineMutationProgressHandleDescriptor.PROGRESS_ID.name());
		awaitCompletion(evita, progressId);
		assertEquals(CatalogState.ALIVE, evita.getCatalogState("restAsyncCatalog").orElseThrow());

		awaitCompletion(
			evita,
			tester.test(SYSTEM_URL)
				.urlPathSuffix("/catalogs-async/restAsyncCatalog/actions/duplicate/restAsyncCopy")
				.httpMethod(Request.METHOD_POST)
				.executeAndThen()
				.statusCode(202)
				.extract()
				.path(EngineMutationProgressHandleDescriptor.PROGRESS_ID.name())
		);
		assertEquals(CatalogState.INACTIVE, evita.getCatalogState("restAsyncCopy").orElseThrow());

		for (String catalogName : new String[]{"restAsyncCatalog", "restAsyncCopy"}) {
			awaitCompletion(
				evita,
				tester.test(SYSTEM_URL)
					.urlPathSuffix("/catalogs-async/" + catalogName)
					.httpMethod(Request.METHOD_DELETE)
					.executeAndThen()
					.statusCode(202)
					.extract()
					.path(EngineMutationProgressHandleDescriptor.PROGRESS_ID.name())
			);
			assertTrue(evita.getCatalogState(catalogName).isEmpty());
		}
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_REPLACE)
	@DisplayName("Should route catalogs named like the static path items of the new endpoints")
	void shouldRouteCatalogsNamedLikeStaticPathItems(Evita evita, RestTester tester) {
		for (String catalogName : new String[]{"progress", "actions"}) {
			createCatalog(tester, catalogName);
			awaitCompletion(
				evita,
				patchCatalog(tester, "/catalogs-async/" + catalogName, "{\"readOnly\": true}")
					.statusCode(202)
					.extract()
					.path(EngineMutationProgressHandleDescriptor.PROGRESS_ID.name())
			);
			tester.test(SYSTEM_URL)
				.urlPathSuffix("/catalogs/" + catalogName)
				.httpMethod(Request.METHOD_GET)
				.executeAndThen()
				.statusCode(200)
				.body(CatalogDescriptor.READ_ONLY.name(), equalTo(true));
			deleteCatalog(tester, catalogName);
		}
	}

	/**
	 * Creates a new catalog through the API.
	 */
	private static void createCatalog(@Nonnull RestTester tester, @Nonnull String catalogName) {
		tester.test(SYSTEM_URL)
			.urlPathSuffix("/catalogs")
			.httpMethod(Request.METHOD_POST)
			.requestBody("{\"name\": \"%s\"}", catalogName)
			.executeAndThen()
			.statusCode(200)
			.body(CatalogDescriptor.CATALOG_STATE.name(), equalTo(CatalogState.WARMING_UP.name()));
	}

	/**
	 * Deletes a catalog through the blocking API.
	 */
	private static void deleteCatalog(@Nonnull RestTester tester, @Nonnull String catalogName) {
		tester.test(SYSTEM_URL)
			.urlPathSuffix("/catalogs/" + catalogName)
			.httpMethod(Request.METHOD_DELETE)
			.executeAndExpectOkWithoutBodyAndThen();
	}

	/**
	 * Sends a `PATCH` request with the given body.
	 */
	@Nonnull
	private static ValidatableResponse patchCatalog(
		@Nonnull RestTester tester,
		@Nonnull String urlPathSuffix,
		@Nonnull String body
	) {
		return tester.test(SYSTEM_URL)
			.urlPathSuffix(urlPathSuffix)
			.httpMethod(Request.METHOD_PATCH)
			.requestBody(body)
			.executeAndThen();
	}

	/**
	 * Waits until the engine operation of the given progress id finishes successfully.
	 */
	private static void awaitCompletion(@Nonnull Evita evita, @Nonnull String progressId) {
		final CompletableFuture<Void> finished = new CompletableFuture<>();
		evita.subscribeToEngineMutationProgress(UUID.fromString(progressId)).subscribe(
			new Flow.Subscriber<>() {
				@Override
				public void onSubscribe(Flow.Subscription subscription) {
					subscription.request(Long.MAX_VALUE);
				}

				@Override
				public void onNext(EngineMutationProgressStatus item) {
					// only the outcome matters
				}

				@Override
				public void onError(Throwable throwable) {
					finished.completeExceptionally(throwable);
				}

				@Override
				public void onComplete() {
					finished.complete(null);
				}
			}
		);
		finished.orTimeout(30, TimeUnit.SECONDS).join();
	}

	@Nonnull
	private static Map<String, Object> createCatalogDto(@Nonnull CatalogContract catalog) {
		return map()
			.e(CatalogDescriptor.CATALOG_ID.name(), catalog.getCatalogId().toString())
			.e(CatalogDescriptor.NAME.name(), catalog.getName())
			.e(CatalogDescriptor.NAME_VARIANTS.name(), map()
				.e(NameVariantsDescriptor.CAMEL_CASE.name(), catalog.getSchema().getNameVariants().get(NamingConvention.CAMEL_CASE))
				.e(NameVariantsDescriptor.PASCAL_CASE.name(), catalog.getSchema().getNameVariants().get(NamingConvention.PASCAL_CASE))
				.e(NameVariantsDescriptor.SNAKE_CASE.name(), catalog.getSchema().getNameVariants().get(NamingConvention.SNAKE_CASE))
				.e(NameVariantsDescriptor.UPPER_SNAKE_CASE.name(), catalog.getSchema().getNameVariants().get(NamingConvention.UPPER_SNAKE_CASE))
				.e(NameVariantsDescriptor.KEBAB_CASE.name(), catalog.getSchema().getNameVariants().get(NamingConvention.KEBAB_CASE)))
			.e(CatalogDescriptor.VERSION.name(), String.valueOf(catalog.getVersion()))
			.e(CatalogDescriptor.CATALOG_STATE.name(), catalog.getCatalogState().name())
			.e(CatalogDescriptor.READ_ONLY.name(), false)
			.e(CatalogDescriptor.SUPPORTS_TRANSACTION.name(), catalog.supportsTransaction())
			.e(CatalogDescriptor.ENTITY_TYPES.name(), new ArrayList<>(catalog.getEntityTypes()))
			.e(CatalogDescriptor.UNUSABLE.name(), false)
			.build();
	}
}
