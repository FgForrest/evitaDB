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

import io.evitadb.api.CatalogState;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.system.model.CatalogDescriptor;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.tester.GraphQLTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.utils.MapBuilder.map;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests for GraphQL catalog collections query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class SystemGraphQLSubscriptionsFunctionalTest extends SystemGraphQLEndpointFunctionalTest {

	private static final String ON_SYSTEM_CHANGE_PATH = "data.onSystemChange";
	public static final String GRAPHQL_EMPTY_SYSTEM = "GraphQLEmptySystem";

	@Override
	@DataSet(value = GRAPHQL_EMPTY_SYSTEM, openWebApi = GraphQLProvider.CODE, readOnly = false, destroyAfterClass = true)
	protected DataCarrier setUp(Evita evita) {
		return new DataCarrier();
	}

	@Test
	@UseDataSet(GRAPHQL_EMPTY_SYSTEM)
	@DisplayName("Should create rename and delete catalog")
	void shouldConsumeHeadersOfSystemChanges(GraphQLTester tester) {

//		tester.test(SYSTEM_URL)
//			.document("""
//				subscription {
//					onSystemChange {
//						index
//						catalog
//						operation
//					}
//				}
//				""")
//				.exe
//
//		tester.test(SYSTEM_URL)
//			.document(
//				"""
//					mutation {
//						createCatalog(name: "temporaryCatalog") {
//							name
//							catalogState
//						}
//					}
//					"""
//			)
//			.executeAndExpectOkAndThen()
//			.body(
//				CREATE_CATALOG_PATH,
//				equalTo(
//					map()
//						.e(CatalogDescriptor.NAME.name(), "temporaryCatalog")
//						.e(CatalogDescriptor.CATALOG_STATE.name(), CatalogState.WARMING_UP.name())
//						.build()
//				)
//			);
//
//		tester.test(SYSTEM_URL)
//			.document(
//				"""
//					mutation {
//						switchCatalogToAliveState(name: "temporaryCatalog")
//					}
//					"""
//			)
//			.executeAndExpectOkAndThen()
//			.body(
//				SWITCH_CATALOG_PATH,
//				equalTo(true)
//			);
//
//		tester.test(SYSTEM_URL)
//			.document(
//				"""
//					query {
//						catalog(name: "temporaryCatalog") {
//							... on Catalog {
//								name
//								catalogState
//							}
//						}
//					}
//					"""
//			)
//			.executeAndExpectOkAndThen()
//			.body(
//				CATALOG_PATH,
//				equalTo(
//					map()
//						.e(CatalogDescriptor.NAME.name(), "temporaryCatalog")
//						.e(CatalogDescriptor.CATALOG_STATE.name(), CatalogState.ALIVE.name())
//						.build()
//				)
//			);
//
//		tester.test(SYSTEM_URL)
//			.document(
//				"""
//					mutation {
//						renameCatalog(name: "temporaryCatalog", newName: "temporaryCatalog2") {
//							name
//						}
//					}
//					"""
//			)
//			.executeAndExpectOkAndThen()
//			.body(
//				RENAME_CATALOG_PATH,
//				equalTo(
//					map()
//						.e(CatalogDescriptor.NAME.name(), "temporaryCatalog2")
//						.build()
//				)
//			);
//
//		tester.test(SYSTEM_URL)
//			.document(
//				"""
//					mutation {
//						deleteCatalogIfExists(name: "temporaryCatalog2")
//					}
//					"""
//			)
//			.executeAndExpectOkAndThen()
//			.body(DELETE_CATALOG_PATH, equalTo(true));
	}

}
