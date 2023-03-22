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

package io.evitadb.externalApi.rest.api.system;

import io.evitadb.api.CatalogContract;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.schemaApi.model.NameVariantsDescriptor;
import io.evitadb.externalApi.api.system.model.CatalogDescriptor;
import io.evitadb.externalApi.rest.api.system.model.LivenessDescriptor;
import io.evitadb.externalApi.rest.api.testSuite.RestEndpointFunctionalTest;
import io.evitadb.externalApi.rest.api.testSuite.RestTester.Request;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Map;

import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.REST_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.MapBuilder.map;
import static org.hamcrest.Matchers.*;

/**
 * Tests for REST system management endpoints.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class SystemRestEndpointFunctionalTest extends RestEndpointFunctionalTest {

	private static final String SYSTEM_URL = "system";

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return OpenAPI specs")
	void shouldReturnOpenApiSpecs(Evita evita) {
		testRestCall(SYSTEM_URL)
			.httpMethod(Request.METHOD_GET)
			.acceptHeader("application/yaml")
			.executeAndThen()
			.statusCode(200)
			.body(notNullValue());
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should be alive")
	void shouldBeAlive(Evita evita) {
		testRestCall(SYSTEM_URL)
			.urlPathSuffix("/liveness")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.statusCode(200)
			.body(
				"",
				equalTo(
					map()
						.e(LivenessDescriptor.ALIVE.name(), true)
						.build()
				)
			);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return specific catalog")
	void shouldReturnSpecificCatalog(Evita evita) {
		final CatalogContract testCatalog = evita.getCatalogInstanceOrThrowException(TEST_CATALOG);
		createCatalogDto(testCatalog);

		testRestCall(SYSTEM_URL)
			.urlPathSuffix("/catalogs/test-catalog")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.statusCode(200)
			.body("", equalTo(createCatalogDto(testCatalog)));
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return all catalogs")
	void shouldReturnAllCatalogs(Evita evita) {
		testRestCall(SYSTEM_URL)
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
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return error if specific Evita catalog doesn't exist")
	void shouldReturnErrorIfSpecificCatalogDoesntExist(Evita evita) {
		testRestCall(SYSTEM_URL)
			.urlPathSuffix("/catalogs/something-else")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.statusCode(404);
	}


	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should create, rename and delete catalog")
	void shouldCreateRenameAndDeleteCatalog(Evita evita) {
		// prepare temp catalog
		testRestCall(SYSTEM_URL)
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
		testRestCall(SYSTEM_URL)
			.urlPathSuffix("/catalogs/temporary-catalog")
			.httpMethod(Request.METHOD_PATCH)
			.requestBody(
				"""
					{
						"name": "temporaryCatalog2"
					}
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(
				"",
				equalTo(
					createCatalogDto(evita.getCatalogInstanceOrThrowException("temporaryCatalog2"))
				)
			);

		// delete
		testRestCall(SYSTEM_URL)
			.urlPathSuffix("/catalogs/temporary-catalog2")
			.httpMethod(Request.METHOD_DELETE)
			.executeAndThen()
			.statusCode(204);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should not delete unknown catalog")
	void shouldNotDeleteUnknownCatalog(Evita evita) {
		testRestCall(SYSTEM_URL)
			.urlPathSuffix("/catalogs/unknown")
			.httpMethod(Request.METHOD_DELETE)
			.executeAndThen()
			.statusCode(404);
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should replace catalog")
	void shouldReplaceCatalog(Evita evita) {
		// create new temporary catalog
		testRestCall(SYSTEM_URL)
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
		testRestCall(SYSTEM_URL)
			.urlPathSuffix("/catalogs/test-catalog")
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
		testRestCall(SYSTEM_URL)
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
		testRestCall(SYSTEM_URL)
			.urlPathSuffix("/catalogs/temporary-catalog")
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
			.e(CatalogDescriptor.CORRUPTED.name(), false)
			.build();
	}
}
