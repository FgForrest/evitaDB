/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.externalApi.graphql.api.catalog.dataApi.resolver.dataFetcher.QueryEntitiesDataFetcher;
import io.evitadb.externalApi.graphql.api.testSuite.GraphQLEndpointFunctionalTest;
import io.evitadb.server.log.ServedRequestLogAssertions;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.tester.GraphQLTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.externalApi.graphql.api.testSuite.TestDataGenerator.GRAPHQL_THOUSAND_PRODUCTS;
import static io.evitadb.server.log.ServedRequestLogAssertions.PROBE_PRIMARY_KEY;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.OBSERVABILITY;

/**
 * Verifies that a line logged while a GraphQL query is being served reports how far into the request it was written.
 *
 * The data fetcher this exercises runs from work the endpoint submits to evitaDB's request pool, and it submits it
 * from the callback that completes once Armeria has aggregated the request body — the hand-off at which the request
 * start has nowhere to come from but the context the endpoint was holding at that moment. What the claim is and why it is made end to end:
 * {@link ServedRequestLogAssertions}. The sibling
 * {@code io.evitadb.externalApi.rest.api.catalog.dataApi.RestRequestDurationLoggingTest} makes it for REST.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(OBSERVABILITY)
class GraphQLRequestDurationLoggingTest extends GraphQLEndpointFunctionalTest {

	@Test
	@UseDataSet(GRAPHQL_THOUSAND_PRODUCTS)
	@DisplayName("Should report how far into the request a line logged while serving a GraphQL query was written")
	void shouldReportRequestDurationForALineLoggedWhileServingQuery(GraphQLTester tester) {
		ServedRequestLogAssertions.assertServedRequestLogsItsDuration(
			QueryEntitiesDataFetcher.class,
			() -> tester.test(TEST_CATALOG)
				.document(
					"""
						query {
							queryProduct(
								filterBy: {
									entityPrimaryKeyInSet: [%d]
								}
							) {
								recordPage {
									data {
										primaryKey
									}
								}
							}
						}
						""",
					PROBE_PRIMARY_KEY
				)
				.executeAndThen()
				.statusCode(200)
		);
	}
}
