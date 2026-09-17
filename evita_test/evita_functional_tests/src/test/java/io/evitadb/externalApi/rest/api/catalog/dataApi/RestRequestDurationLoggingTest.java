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

package io.evitadb.externalApi.rest.api.catalog.dataApi;

import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint.QueryEntitiesHandler;
import io.evitadb.externalApi.rest.api.testSuite.RestEndpointFunctionalTest;
import io.evitadb.server.log.ServedRequestLogAssertions;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.tester.RestTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.REST_THOUSAND_PRODUCTS;
import static io.evitadb.server.log.ServedRequestLogAssertions.PROBE_PRIMARY_KEY;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.OBSERVABILITY;

/**
 * Verifies that a line logged while a REST query is being served reports how far into the request it was written.
 *
 * The handler this exercises logs from inside the work it submits to evitaDB's request pool, and it submits it from
 * the callback that completes once Armeria has aggregated the request body — the hand-off at which the request start
 * has nowhere to come from but the context the endpoint was holding at that moment. What the
 * claim is and why it is made end to end: {@link ServedRequestLogAssertions}. The sibling
 * {@code io.evitadb.externalApi.graphql.api.catalog.dataApi.GraphQLRequestDurationLoggingTest} makes it for GraphQL.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(OBSERVABILITY)
class RestRequestDurationLoggingTest extends RestEndpointFunctionalTest {

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should report how far into the request a line logged while serving a REST query was written")
	void shouldReportRequestDurationForALineLoggedWhileServingQuery(RestTester tester) {
		ServedRequestLogAssertions.assertServedRequestLogsItsDuration(
			QueryEntitiesHandler.class,
			() -> tester.test(TEST_CATALOG)
				.post("/PRODUCT/query")
				.requestBody(
					"""
						{
							"filterBy": {
								"entityPrimaryKeyInSet": [%d]
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
