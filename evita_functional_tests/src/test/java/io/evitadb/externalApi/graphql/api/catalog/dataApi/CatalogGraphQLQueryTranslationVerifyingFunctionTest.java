/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi;

import io.evitadb.core.Evita;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.tester.GraphQLTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests the widest possible range of constraints and variants of evitaDB query through GraphQL API. It tests if
 * the query can be successfully parsed and converted into evitaDB API. It doesn't test if it returns correct data, that's
 * what other tests are for.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
public class CatalogGraphQLQueryTranslationVerifyingFunctionTest extends CatalogGraphQLDataEndpointFunctionalTest {

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should accept and process large query")
	void shouldAcceptAndProcessLargeQuery(Evita evita) throws IOException {
		new GraphQLTester("https://demo.evitadb.io:5555/gql").test("evita")
			.document(readFromClasspath("testData/CatalogGraphQLQueryTranslationVerifyingFunctionTest_query.graphql"))
			.executeAndExpectOkAndThen()
			.body(ERRORS_PATH, nullValue());
	}
}
