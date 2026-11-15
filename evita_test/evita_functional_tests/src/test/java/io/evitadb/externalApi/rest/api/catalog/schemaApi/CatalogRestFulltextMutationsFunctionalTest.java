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


package io.evitadb.externalApi.rest.api.catalog.schemaApi;

import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.ExternalApiFunctionTestsSupport;
import io.evitadb.externalApi.ExternalApiWebSocketFunctionTestsSupport;
import io.evitadb.externalApi.FulltextMutationFunctionalTestSupport;
import io.evitadb.externalApi.FulltextMutationFunctionalTestSupport.MutationCase;
import io.evitadb.externalApi.rest.RestProvider;
import io.evitadb.externalApi.rest.api.testSuite.RestEndpointFunctionalTest;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.tester.RestTester;
import io.evitadb.test.tester.RestTester.Request;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.evitadb.externalApi.FulltextMutationFunctionalTestSupport.*;
import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fulltext mutation input and Java-origin CDC output over a real Rest server. */
@Tag(SCHEMA)
@Tag(FULLTEXT)
@Tag(CDC)
@DisplayName("Rest fulltext mutation input and CDC")
class CatalogRestFulltextMutationsFunctionalTest extends RestEndpointFunctionalTest
	implements ExternalApiFunctionTestsSupport, ExternalApiWebSocketFunctionTestsSupport {
	private static final String DATA_SET = "RestFulltextMutations";

	/** Uses one server while keeping mutation prerequisites isolated by catalog. */
	@Override
	@DataSet(value = DATA_SET, openWebApi = RestProvider.CODE, readOnly = false, destroyAfterClass = true)
	@Nonnull
	protected DataCarrier setUp(@Nonnull Evita evita, @Nonnull EvitaServer evitaServer) {
		return new DataCarrier();
	}

	/** Exercises optional fulltext fields through a real schema endpoint and verifies the Java mutation event. */
	@DisplayName("Applies fulltext settings through the schema endpoint")
	@ParameterizedTest
	@MethodSource("io.evitadb.externalApi.FulltextMutationFunctionalTestSupport#cases")
	@UseDataSet(DATA_SET)
	void shouldApplyFulltextMutation(
		@Nonnull MutationCase testCase, @Nonnull Evita evita, @Nonnull RestTester tester
	) {
		final String catalog = prepare(evita, testCase);
		final long startVersion = getStartVersionForCatalogCDC(evita, catalog);
		tester.test(catalog).urlPathSuffix(testCase.global() ? "/schema" : "/owner/schema")
			.httpMethod(Request.METHOD_PUT)
			.requestBody(json(Map.of("mutations", List.of(input(testCase.mutation())))))
			.executeAndExpectOkAndThen();
		history(evita, catalog, startVersion, testCase);
	}

	/** Replays Java-origin mutation events over the API and checks all declared fulltext output properties. */
	@DisplayName("Delivers all fulltext settings from a Java-origin CDC event")
	@ParameterizedTest
	@MethodSource("io.evitadb.externalApi.FulltextMutationFunctionalTestSupport#cases")
	@UseDataSet(DATA_SET)
	void shouldDeliverJavaOriginFulltextCapture(
		@Nonnull MutationCase testCase, @Nonnull Evita evita, @Nonnull RestTester tester
	) {
		final String catalog = prepare(evita, testCase);
		final long startVersion = getStartVersionForCatalogCDC(evita, catalog);
		evita.updateCatalog(catalog, session -> { apply(session, testCase); });
		final List<ChangeCatalogCapture> captures = history(evita, catalog, startVersion, testCase);
		final String subscriptionId = createSubscriptionId();
		tester.testWebSocket(catalog, "/change-captures", context -> {
			context.writer().write(createConnectionInitMessage());
			context.awaitEvents(1);
			context.writer().write(json(Map.of("id", subscriptionId, "type", "subscribe",
				"payload", Map.of("sinceVersion", Long.toString(startVersion), "content", "BODY"))));
			assertTrue(context.tryAwaitEvents(frames -> {
				for (final String frame : frames) {
					assertTrue(!frame.contains("\"type\":\"error\""), frame);
				}
				return frames.size() >= captures.size() + 1;
			}, Duration.ofSeconds(30)), "Expected all fulltext mutation captures");
		}, captures.size() + 1, events -> assertCapture(events, testCase));
	}
}
