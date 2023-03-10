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

import io.evitadb.api.CatalogContract;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.EvitaSystemDataProvider;
import io.evitadb.externalApi.api.system.model.CatalogDescriptor;
import io.evitadb.test.annotation.UseDataSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Map;

import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.MapBuilder.map;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests for GraphQL catalog collections query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class SystemGraphQLQueriesFunctionalTest extends SystemGraphQLEndpointFunctionalTest {

	private static final String ERRORS_PATH = "errors";
	private static final String LIVENESS_PATH = "data.liveness";
	private static final String CATALOG_PATH = "data.catalog";
	private static final String CATALOGS_PATH = "data.catalogs";

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should be alive")
	void shouldBeAlive(Evita evita) {
		testGraphQLCall()
			.document(
				"""
					query {
						liveness
					}
					"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(LIVENESS_PATH, equalTo(true));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return specific catalog")
	void shouldReturnSpecificCatalog(Evita evita) {
		final EvitaSystemDataProvider evitaSystemDataProvider = new EvitaSystemDataProvider(evita);
		final CatalogContract testCatalog = evitaSystemDataProvider.getCatalog(TEST_CATALOG);
		createCatalogDto(testCatalog);

		testGraphQLCall()
			.document(
				"""
					query {
						catalog(name: "%s") {
							name
							version
							catalogState
							supportsTransaction
							entityTypes
						}
					}
					""",
				TEST_CATALOG
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(CATALOG_PATH, equalTo(createCatalogDto(testCatalog)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return all catalogs")
	void shouldReturnAllCatalogs(Evita evita) {
		final EvitaSystemDataProvider evitaSystemDataProvider = new EvitaSystemDataProvider(evita);

		testGraphQLCall()
			.document(
				"""
					query {
						catalogs {
							name
							version
							catalogState
							supportsTransaction
							entityTypes
						}
					}
					""",
				TEST_CATALOG
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(
				CATALOGS_PATH,
				equalTo(
					evitaSystemDataProvider.getCatalogs()
						.stream()
						.map(SystemGraphQLQueriesFunctionalTest::createCatalogDto)
						.toList()
				)
			);
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return error if specific Evita catalog doesn't exist")
	void shouldReturnErrorIfSpecificCatalogDoesntExist(Evita evita) {
		testGraphQLCall()
			.document(
				"""
                query {
                    catalog(name: "somethingElse") {
                        name
                    }
                }
				"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should return errors query schema is invalid")
	void shouldReturnNullIfSpecificCatalogDoesntExist(Evita evita) {
		testGraphQLCall()
			.document(
				"""
                query {
                    myCatalog {
                        storage
                    }
                }
				"""
			)
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, hasSize(greaterThan(0)));
	}


	@Nonnull
	private static Map<String, Object> createCatalogDto(@Nonnull CatalogContract catalog) {
		return map()
			.e(CatalogDescriptor.NAME.name(), catalog.getName())
			.e(CatalogDescriptor.VERSION.name(), String.valueOf(catalog.getVersion()))
			.e(CatalogDescriptor.CATALOG_STATE.name(), catalog.getCatalogState().name())
			.e(CatalogDescriptor.SUPPORTS_TRANSACTION.name(), catalog.supportsTransaction())
			.e(CatalogDescriptor.ENTITY_TYPES.name(), new ArrayList<>(catalog.getEntityTypes()))
			.build();
	}
}
