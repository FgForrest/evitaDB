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

package io.evitadb.externalApi.graphql.api.catalog.dataApi;

import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.test.tester.GraphQLTester;
import io.evitadb.test.annotation.UseDataSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.builder.MapBuilder.map;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests for GraphQL catalog collections query.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class CatalogGraphQLAsyncQueriesFunctionalTest extends CatalogGraphQLDataEndpointFunctionalTest {

	private static final String DATA_PATH = "data";

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should correctly handle multiple parallel queries")
	void shouldCorrectlyHandleMultipleParallelQueries(GraphQLTester tester, List<SealedEntity> originalProductEntities) {
		final String singleQueryTemplate = """
				product%d: getProduct(primaryKey: %d) {
					primaryKey
				}
			""";

		final StringBuilder queryBuilder = new StringBuilder("query {\n");
		final var expectedBody = createHashMap(originalProductEntities.size());
		originalProductEntities
			.stream()
			.sorted(Comparator.comparing(EntityContract::getPrimaryKey))
			.forEach(product -> {
				queryBuilder
					.append(String.format(
						singleQueryTemplate,
						product.getPrimaryKey(),
						product.getPrimaryKey()))
					.append("\n");
				expectedBody.put(
					"product" + product.getPrimaryKey(),
					map()
						.e(EntityDescriptor.PRIMARY_KEY.name(), product.getPrimaryKey())
						.build()
				);
			});
		queryBuilder.append("}");

		tester.test(TEST_CATALOG)
			.document(queryBuilder.toString())
			.executeAndThen()
			.statusCode(200)
			.body(ERRORS_PATH, nullValue())
			.body(DATA_PATH, equalTo(expectedBody));
	}
}
