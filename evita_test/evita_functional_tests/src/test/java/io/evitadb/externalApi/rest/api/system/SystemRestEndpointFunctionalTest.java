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

import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NameVariantsDescriptor;
import io.evitadb.externalApi.api.system.model.CatalogDescriptor;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Map;

import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.REST_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.utils.MapBuilder.map;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for REST system management endpoints.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class SystemRestEndpointFunctionalTest extends RestEndpointFunctionalTest {

	private static final String SYSTEM_URL = "system";
	public static final String REST_THOUSAND_PRODUCTS_FOR_REPLACE = REST_THOUSAND_PRODUCTS + "forReplace";

	@Override
	@DataSet(value = REST_THOUSAND_PRODUCTS_FOR_REPLACE, openWebApi = RestProvider.CODE, readOnly = false, destroyAfterClass = true)
	protected DataCarrier setUp(Evita evita, EvitaServer evitaServer) {
		return super.setUpData(evita, evitaServer, 20, false);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS_FOR_REPLACE)
	@DisplayName("Should return OpenAPI specs")
	void shouldReturnOpenApiSpecs(Evita evita, RestTester tester) {
		tester.test(SYSTEM_URL)
			.httpMethod(Request.METHOD_GET)
			.acceptHeader("application/yaml")
			.executeAndThen()
			.statusCode(200)
			.body(notNullValue());
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
						"catalogState": "ALIVE"
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

		// replace test catalog to temporary catalog
		tester.test(SYSTEM_URL)
			.urlPathSuffix("/catalogs/testCatalog")
			.httpMethod(Request.METHOD_PATCH)
			.requestBody(
				"""
					{
						"name": "temporaryCatalog",
						"overwriteTarget": true
					}
					"""
			)
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
			.urlPathSuffix("/catalogs/temporaryCatalog")
			.httpMethod(Request.METHOD_PATCH)
			.requestBody(
				"""
					{
						"name": "%s",
						"overwriteTarget": true
					}
					""",
				TEST_CATALOG
			)
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createCatalogDto(evita.getCatalogInstanceOrThrowException(TEST_CATALOG))));
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
			.e(CatalogDescriptor.SUPPORTS_TRANSACTION.name(), catalog.supportsTransaction())
			.e(CatalogDescriptor.ENTITY_TYPES.name(), new ArrayList<>(catalog.getEntityTypes()))
			.e(CatalogDescriptor.UNUSABLE.name(), false)
			.build();
	}
}
