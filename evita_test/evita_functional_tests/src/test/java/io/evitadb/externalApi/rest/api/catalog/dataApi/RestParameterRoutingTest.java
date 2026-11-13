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

import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.externalApi.rest.api.testSuite.TestDataGenerator;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.tester.RestTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;

import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Wire-level parameter transport through production REST catalog routers and the real entity handler.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(REST)
@Tag(EXTERNAL_API)
@Tag(QUERY)
@DisplayName("REST parameters use URL data")
class RestParameterRoutingTest extends CatalogRestDataEndpointFunctionalTest {
	/** Path captures and query values reach the handler, while a client internal header cannot change its result. */
	@Test
	@UseDataSet(TestDataGenerator.REST_THOUSAND_PRODUCTS)
	@DisplayName("Entity fetch uses path and query but ignores internal headers")
	void shouldUsePathAndQueryAndIgnoreInternalHeaders(
		@Nonnull List<SealedEntity> originalProductEntities, @Nonnull RestTester tester
	) {
		final int primaryKey = originalProductEntities.getFirst().getPrimaryKey();
		final String path = "/PRODUCT/get/" + primaryKey;
		final String baseline = tester.test(TEST_CATALOG).get(path)
			.executeAndThen().statusCode(200).extract().asString();
		final String fetched = tester.test(TEST_CATALOG).get(path)
			.requestParam("bodyFetch", true).executeAndThen().statusCode(200).extract().asString();
		assertNotEquals(baseline, fetched, "bodyFetch must visibly change the real entity response");
		final String mixedCase = tester.test(TEST_CATALOG).get(path).requestParam("BODYFETCH", true)
			.executeAndThen().statusCode(200).extract().asString();
		assertEquals(fetched, mixedCase, "Declared parameter names remain case-insensitive");
		// Keep the existing too-many-values exception mapping; this round changes transport only.
		tester.test(TEST_CATALOG).get(path).requestParam("primaryKey", primaryKey)
			.executeAndThen().statusCode(500)
			.body("message", org.hamcrest.Matchers.containsString("found: 2"));
		tester.test(TEST_CATALOG).get(path).requestParam("notDeclared", "value")
			.executeAndThen().statusCode(400);
		assertTrue(fetched.contains("\"primaryKey\":" + primaryKey), fetched);
		final String injected = tester.test(TEST_CATALOG).get(path).header("internal_bodyFetch", "true")
			.executeAndThen().statusCode(200).extract().asString();
		assertEquals(baseline, injected, "Client internal_bodyFetch must have no effect");
	}
}
