/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.rest.api.catalog;

import io.evitadb.core.Evita;
import io.evitadb.externalApi.rest.api.testSuite.RestEndpointFunctionalTest;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.tester.RestTester;
import io.evitadb.test.tester.RestTester.Request;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator.REST_THOUSAND_PRODUCTS;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for auxiliary catalog endpoints.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class CatalogRestQueriesFunctionalTest extends RestEndpointFunctionalTest {

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should return OpenAPI specs")
	void shouldReturnOpenApiSpecs(Evita evita, RestTester tester) {
		tester.test(TEST_CATALOG)
			.httpMethod(Request.METHOD_GET)
			.acceptHeader("application/yaml")
			.executeAndThen()
			.statusCode(200)
			.body(notNullValue());
	}

	@Test
	@UseDataSet(REST_THOUSAND_PRODUCTS)
	@DisplayName("Should ignore trailing slash in endpoint URLs")
	void shouldIgnoreTrailingSlashInEndpointUrls(Evita evita, RestTester tester) {
		tester.test(TEST_CATALOG)
			.urlPathSuffix("/collections")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.statusCode(200);

		tester.test(TEST_CATALOG)
			.urlPathSuffix("/collections/")
			.httpMethod(Request.METHOD_GET)
			.executeAndThen()
			.statusCode(200);
	}
}
