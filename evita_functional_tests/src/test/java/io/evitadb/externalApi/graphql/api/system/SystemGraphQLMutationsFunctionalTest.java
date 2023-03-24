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

package io.evitadb.externalApi.graphql.api.system;

import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.system.model.CatalogDescriptor;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.graphql.api.testSuite.GraphQLTester;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.MapBuilder.map;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests for GraphQL catalog collections query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class SystemGraphQLMutationsFunctionalTest extends SystemGraphQLEndpointFunctionalTest {

	private static final String ERRORS_PATH = "errors";
	private static final String CREATE_CATALOG_PATH = "data.createCatalog";
	private static final String RENAME_CATALOG_PATH = "data.renameCatalog";
	private static final String REPLACE_CATALOG_PATH = "data.replaceCatalog";
	private static final String DELETE_CATALOG_PATH = "data.deleteCatalogIfExists";
	public static final String GRAPHQL_THOUSAND_PRODUCTS_SYSTEM_REPLACE = GRAPHQL_THOUSAND_PRODUCTS + "forReplace";

	@Override
	@DataSet(value = GRAPHQL_THOUSAND_PRODUCTS_SYSTEM_REPLACE, openWebApi = GraphQLProvider.CODE, readOnly = false, destroyAfterClass = true)
	protected DataCarrier setUp(Evita evita, EvitaServer evitaServer) {
		return super.setUpData(evita, evitaServer, 20);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_SYSTEM_REPLACE)
	@DisplayName("Should create rename and delete catalog")
	void shouldCreateRenameAndDeleteCatalog(GraphQLTester tester) {
		tester.test(SYSTEM_URL)
			.document(
				"""
					mutation {
						createCatalog(name: "temporaryCatalog") {
							name
						}
					}
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				CREATE_CATALOG_PATH,
				equalTo(
					map()
						.e(CatalogDescriptor.NAME.name(), "temporaryCatalog")
						.build()
				)
			);

		tester.test(SYSTEM_URL)
			.document(
				"""
					mutation {
						renameCatalog(name: "temporaryCatalog", newName: "temporaryCatalog2") {
							name
						}
					}
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				RENAME_CATALOG_PATH,
				equalTo(
					map()
						.e(CatalogDescriptor.NAME.name(), "temporaryCatalog2")
						.build()
				)
			);

		tester.test(SYSTEM_URL)
			.document(
				"""
					mutation {
						deleteCatalogIfExists(name: "temporaryCatalog2")
					}
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(DELETE_CATALOG_PATH, equalTo(true));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_SYSTEM_REPLACE)
	@DisplayName("Should not delete unknown catalog")
	void shouldNotDeleteUnknownCatalog(GraphQLTester tester) {
		tester.test(SYSTEM_URL)
			.document(
				"""
					mutation {
						deleteCatalogIfExists(name: "unknown")
					}
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(DELETE_CATALOG_PATH, equalTo(false));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_SYSTEM_REPLACE)
	@DisplayName("Should replace catalog")
	void shouldReplaceCatalog(GraphQLTester tester) {
		// create new temporary catalog
		tester.test(SYSTEM_URL)
			.document(
				"""
					mutation {
						createCatalog(name: "temporaryCatalog") {
							name
						}
					}
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				CREATE_CATALOG_PATH,
				equalTo(
					map()
						.e(CatalogDescriptor.NAME.name(), "temporaryCatalog")
						.build()
				)
			);

		// replace test catalog to temporary catalog
		tester.test(SYSTEM_URL)
			.document(
				"""
					mutation {
						replaceCatalog(nameToBeReplaced: "temporaryCatalog", nameToBeReplacedWith: "%s") {
							name
						}
					}
					""",
				TEST_CATALOG
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				REPLACE_CATALOG_PATH,
				equalTo(
					map()
						.e(CatalogDescriptor.NAME.name(), "temporaryCatalog")
						.build()
				)
			);

		// recreate test catalog
		tester.test(SYSTEM_URL)
			.document(
				"""
					mutation {
						createCatalog(name: "%s") {
							name
						}
					}
					""",
				TEST_CATALOG
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				CREATE_CATALOG_PATH,
				equalTo(
					map()
						.e(CatalogDescriptor.NAME.name(), TEST_CATALOG)
						.build()
				)
			);

		// replace temporary catalog back to test catalog
		tester.test(SYSTEM_URL)
			.document(
				"""
					mutation {
						replaceCatalog(nameToBeReplaced: "%s", nameToBeReplacedWith: "temporaryCatalog") {
							name
						}
					}
					""",
				TEST_CATALOG
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				REPLACE_CATALOG_PATH,
				equalTo(
					map()
						.e(CatalogDescriptor.NAME.name(), TEST_CATALOG)
						.build()
				)
			);
	}
}
