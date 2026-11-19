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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.utils.MapBuilder.map;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests for the engine mutations of the GraphQL system API.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
@Tag(GRAPHQL)
@Tag(EXTERNAL_API)
@Tag(QUERY)
public class SystemGraphQLMutationsFunctionalTest extends SystemGraphQLEndpointFunctionalTest {

	private static final String CATALOG_PATH = "data.catalog";
	private static final String CREATE_CATALOG_PATH = "data.createNewCatalog";
	private static final String RENAME_CATALOG_PATH = "data.renameCatalog";
	private static final String MAKE_CATALOG_ALIVE_PATH = "data.makeCatalogAlive";
	private static final String REPLACE_CATALOG_PATH = "data.replaceCatalog";
	private static final String DELETE_CATALOG_PATH = "data.deleteCatalogIfExists";
	/**
	 * Selection of both members of the `CatalogUnion` returned by the engine mutations.
	 */
	private static final String CATALOG_UNION_SELECTION = """
		{
			__typename
			... on Catalog { name catalogState readOnly }
			... on UnusableCatalog { name catalogState readOnly }
		}
		""";
	public static final String GRAPHQL_THOUSAND_PRODUCTS_SYSTEM_REPLACE = GRAPHQL_THOUSAND_PRODUCTS + "forReplace";

	@Override
	@DataSet(value = GRAPHQL_THOUSAND_PRODUCTS_SYSTEM_REPLACE, openWebApi = GraphQLProvider.CODE, readOnly = false, destroyAfterClass = true)
	protected DataCarrier setUp(Evita evita) {
		return super.setUpData(evita, 20, false);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_SYSTEM_REPLACE)
	@DisplayName("Should create, make alive, rename and delete catalog")
	void shouldCreateRenameAndDeleteCatalog(GraphQLTester tester) {
		tester.test(SYSTEM_URL)
			.document(
				"""
					mutation {
						createNewCatalog(name: "temporaryCatalog") {
							... on Catalog {
								name
								catalogState
								readOnly
							}
						}
					}
					"""
			)
			.executeAndExpectOkAndThen()
			.body(
				CREATE_CATALOG_PATH,
				equalTo(
					map()
						.e(CatalogDescriptor.NAME.name(), "temporaryCatalog")
						.e(CatalogDescriptor.CATALOG_STATE.name(), CatalogState.WARMING_UP.name())
						.e(CatalogDescriptor.READ_ONLY.name(), false)
						.build()
				)
			);

		tester.test(SYSTEM_URL)
			.document(
				"""
					mutation {
						makeCatalogAlive(name: "temporaryCatalog") {
							... on Catalog {
								name
								catalogState
							}
						}
					}
					"""
			)
			.executeAndExpectOkAndThen()
			.body(
				MAKE_CATALOG_ALIVE_PATH,
				equalTo(
					map()
						.e(CatalogDescriptor.NAME.name(), "temporaryCatalog")
						.e(CatalogDescriptor.CATALOG_STATE.name(), CatalogState.ALIVE.name())
						.build()
				)
			);

		tester.test(SYSTEM_URL)
			.document(
				"""
					query {
						catalog(name: "temporaryCatalog") {
							... on Catalog {
								name
								catalogState
							}
						}
					}
					"""
			)
			.executeAndExpectOkAndThen()
			.body(
				CATALOG_PATH,
				equalTo(
					map()
						.e(CatalogDescriptor.NAME.name(), "temporaryCatalog")
						.e(CatalogDescriptor.CATALOG_STATE.name(), CatalogState.ALIVE.name())
						.build()
				)
			);

		tester.test(SYSTEM_URL)
			.document(
				"""
					mutation {
						renameCatalog(name: "temporaryCatalog", newName: "temporaryCatalog2") {
							... on Catalog {
								name
							}
						}
					}
					"""
			)
			.executeAndExpectOkAndThen()
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
			.executeAndExpectOkAndThen()
			.body(DELETE_CATALOG_PATH, equalTo(true));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_SYSTEM_REPLACE)
	@DisplayName("Should duplicate, deactivate, activate and change mutability of catalog")
	void shouldDuplicateDeactivateActivateAndChangeMutability(GraphQLTester tester) {
		expectCatalog(tester, "createNewCatalog(name: \"mutabilitySource\")",
			"Catalog", "mutabilitySource", CatalogState.WARMING_UP, false);
		expectCatalog(tester, "makeCatalogAlive(name: \"mutabilitySource\")",
			"Catalog", "mutabilitySource", CatalogState.ALIVE, false);
		// a duplicate is created inactive, so the mutation must be able to return an unusable catalog
		expectCatalog(tester, "duplicateCatalog(name: \"mutabilitySource\", newName: \"mutabilityCopy\")",
			"UnusableCatalog", "mutabilityCopy", CatalogState.INACTIVE, false);
		expectCatalog(tester, "activateCatalog(name: \"mutabilityCopy\")",
			"Catalog", "mutabilityCopy", CatalogState.ALIVE, false);
		expectCatalog(tester, "makeCatalogImmutable(name: \"mutabilityCopy\")",
			"Catalog", "mutabilityCopy", CatalogState.ALIVE, true);
		// the read-only flag is kept and reported also for an inactive catalog
		expectCatalog(tester, "deactivateCatalog(name: \"mutabilityCopy\")",
			"UnusableCatalog", "mutabilityCopy", CatalogState.INACTIVE, true);
		expectCatalog(tester, "activateCatalog(name: \"mutabilityCopy\")",
			"Catalog", "mutabilityCopy", CatalogState.ALIVE, true);
		expectCatalog(tester, "makeCatalogMutable(name: \"mutabilityCopy\")",
			"Catalog", "mutabilityCopy", CatalogState.ALIVE, false);

		for (String catalogName : new String[]{"mutabilitySource", "mutabilityCopy"}) {
			tester.test(SYSTEM_URL)
				.document("mutation { deleteCatalogIfExists(name: \"%s\") }", catalogName)
				.executeAndExpectOkAndThen()
				.body(DELETE_CATALOG_PATH, equalTo(true));
		}
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_SYSTEM_REPLACE)
	@DisplayName("Should fail an operation on an unknown catalog")
	void shouldFailOperationOnUnknownCatalog(GraphQLTester tester) {
		tester.test(SYSTEM_URL)
			.document("mutation { activateCatalog(name: \"unknown\") { __typename } }")
			.executeAndExpectErrorsAndThen();
		tester.test(SYSTEM_URL)
			.document("mutation { activateCatalogWithProgress(name: \"unknown\") { progressId } }")
			.executeAndExpectErrorsAndThen();
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_SYSTEM_REPLACE)
	@DisplayName("Should refuse to rename catalog to a name that is taken")
	void shouldRefuseRenameToExistingName(GraphQLTester tester) {
		expectCatalog(tester, "createNewCatalog(name: \"clashCatalog\")",
			"Catalog", "clashCatalog", CatalogState.WARMING_UP, false);

		tester.test(SYSTEM_URL)
			.document(
				"mutation { renameCatalog(name: \"clashCatalog\", newName: \"%s\") { __typename } }",
				TEST_CATALOG
			)
			.executeAndExpectErrorsAndThen();

		tester.test(SYSTEM_URL)
			.document("mutation { deleteCatalogIfExists(name: \"clashCatalog\") }")
			.executeAndExpectOkAndThen()
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

		tester.test(SYSTEM_URL)
			.document(
				"""
					mutation {
						deleteCatalogIfExistsWithProgress(name: "unknown") {
							progressId
						}
					}
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body("data.deleteCatalogIfExistsWithProgress", nullValue());
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_SYSTEM_REPLACE)
	@DisplayName("Should return progress handle of an asynchronous operation")
	void shouldReturnProgressHandle(GraphQLTester tester) {
		expectCatalog(tester, "createNewCatalog(name: \"asyncCatalog\")",
			"Catalog", "asyncCatalog", CatalogState.WARMING_UP, false);

		tester.test(SYSTEM_URL)
			.document(
				"""
					mutation {
						makeCatalogAliveWithProgress(name: "asyncCatalog") {
							progressId
						}
					}
					"""
			)
			.executeAndExpectOkAndThen()
			.body("data.makeCatalogAliveWithProgress.progressId", notNullValue());

		tester.test(SYSTEM_URL)
			.document("mutation { deleteCatalogIfExists(name: \"asyncCatalog\") }")
			.executeAndExpectOkAndThen()
			.body(DELETE_CATALOG_PATH, equalTo(true));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS_SYSTEM_REPLACE)
	@DisplayName("Should replace catalog")
	void shouldReplaceCatalog(GraphQLTester tester) {
		// create new temporary catalog
		expectCatalog(tester, "createNewCatalog(name: \"temporaryCatalog\")",
			"Catalog", "temporaryCatalog", CatalogState.WARMING_UP, false);

		// replace test catalog to temporary catalog
		tester.test(SYSTEM_URL)
			.document(
				"""
					mutation {
						replaceCatalog(nameToBeReplaced: "temporaryCatalog", nameToBeReplacedWith: "%s") {
							... on Catalog {
								name
							}
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
		expectCatalog(tester, "createNewCatalog(name: \"" + TEST_CATALOG + "\")",
			"Catalog", TEST_CATALOG, CatalogState.WARMING_UP, false);

		// replace temporary catalog back to test catalog
		tester.test(SYSTEM_URL)
			.document(
				"""
					mutation {
						replaceCatalog(nameToBeReplaced: "%s", nameToBeReplacedWith: "temporaryCatalog") {
							... on Catalog {
								name
							}
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

	/**
	 * Executes a blocking engine mutation selecting the whole `CatalogUnion` and verifies the returned catalog.
	 */
	private static void expectCatalog(
		@Nonnull GraphQLTester tester,
		@Nonnull String mutationCall,
		@Nonnull String expectedTypeName,
		@Nonnull String expectedName,
		@Nonnull CatalogState expectedState,
		boolean expectedReadOnly
	) {
		final String fieldName = mutationCall.substring(0, mutationCall.indexOf('('));
		tester.test(SYSTEM_URL)
			.document("mutation { " + mutationCall + " " + CATALOG_UNION_SELECTION + " }")
			.executeAndExpectOkAndThen()
			.body(
				"data." + fieldName,
				equalTo(
					map()
						.e("__typename", expectedTypeName)
						.e(CatalogDescriptor.NAME.name(), expectedName)
						.e(CatalogDescriptor.CATALOG_STATE.name(), expectedState.name())
						.e(CatalogDescriptor.READ_ONLY.name(), expectedReadOnly)
						.build()
				)
			);
	}
}
