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


package io.evitadb.externalApi.graphql.api.catalog.schemaApi;

import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.ExternalApiFunctionTestsSupport;
import io.evitadb.externalApi.ExternalApiWebSocketFunctionTestsSupport;
import io.evitadb.externalApi.FulltextMutationFunctionalTestSupport;
import io.evitadb.externalApi.FulltextMutationFunctionalTestSupport.MutationCase;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.graphql.api.testSuite.GraphQLEndpointFunctionalTest;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.tester.GraphQLTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.evitadb.externalApi.FulltextMutationFunctionalTestSupport.*;
import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fulltext mutation input and Java-origin CDC output over a real GraphQL server. */
@Tag(SCHEMA)
@Tag(FULLTEXT)
@Tag(CDC)
@DisplayName("GraphQL fulltext mutation input and CDC")
class CatalogGraphQLFulltextMutationsFunctionalTest extends GraphQLEndpointFunctionalTest
	implements ExternalApiFunctionTestsSupport, ExternalApiWebSocketFunctionTestsSupport {
	private static final String DATA_SET = "GraphQLFulltextMutations";

	/** Uses one server while keeping mutation prerequisites isolated by catalog. */
	@Override
	@DataSet(value = DATA_SET, openWebApi = GraphQLProvider.CODE, readOnly = false, destroyAfterClass = true)
	@Nonnull
	protected DataCarrier setUp(@Nonnull Evita evita) {
		return new DataCarrier();
	}

	/** Exercises optional fulltext fields through a real schema endpoint and verifies the Java mutation event. */
	@DisplayName("Applies fulltext settings through the schema endpoint")
	@ParameterizedTest
	@MethodSource("io.evitadb.externalApi.FulltextMutationFunctionalTestSupport#cases")
	@UseDataSet(DATA_SET)
	void shouldApplyFulltextMutation(
		@Nonnull MutationCase testCase, @Nonnull Evita evita, @Nonnull GraphQLTester tester
	) {
		final String catalog = prepare(evita, testCase);
		final long startVersion = getStartVersionForCatalogCDC(evita, catalog);
		final String operation = testCase.global() ? "updateCatalogSchema" : "updateOwnerSchema";
		tester.test(catalog).urlPathSuffix("/schema")
			.document("mutation { " + operation + "(mutations: [" +
				graphql(input(testCase.mutation()), "") + "]) { version } }")
			.executeAndExpectOkAndThen();
		history(evita, catalog, startVersion, testCase);
	}

	/** Replays Java-origin mutation events over the API and checks all declared fulltext output properties. */
	@DisplayName("Delivers all fulltext settings from a Java-origin CDC event")
	@ParameterizedTest
	@MethodSource("captureCases")
	@UseDataSet(DATA_SET)
	void shouldDeliverJavaOriginFulltextCapture(
		@Nonnull MutationCase testCase, boolean typed, @Nonnull Evita evita, @Nonnull GraphQLTester tester
	) {
		final String catalog = prepare(evita, testCase);
		final long startVersion = getStartVersionForCatalogCDC(evita, catalog);
		evita.updateCatalog(catalog, session -> { apply(session, testCase); });
		final List<ChangeCatalogCapture> captures = history(evita, catalog, startVersion, testCase);
		final String subscriptionId = createSubscriptionId();
		final long schemaEvents = captures.stream().filter(capture -> capture.area() == CaptureArea.SCHEMA).count();
		final String subscription = (typed ? "onSchemaChange" : "onSchemaChangeUntyped") +
			"(sinceVersion: " + json(Long.toString(startVersion)) + ") { body" +
			(typed ? " { " + typedSelection() + " }" : "") + " }";
		tester.testWebSocket(catalog, "/schema", context -> {
			context.writer().write(createConnectionInitMessage());
			context.awaitEvents(1);
			context.writer().write(json(Map.of("id", subscriptionId, "type", "subscribe",
				"payload", Map.of("query", "subscription { " + subscription + " }"))));
			assertTrue(context.tryAwaitEvents(frames -> {
				for (final String frame : frames) {
					assertTrue(!frame.contains("\"type\":\"error\""), frame);
				}
				return frames.size() >= Math.toIntExact(schemaEvents + 1);
			}, Duration.ofSeconds(30)), "Expected all fulltext mutation captures");
		}, Math.toIntExact(schemaEvents + 1), events -> assertCapture(events, testCase));
	}

	/** Covers the typed resolver and the untyped converter for each Java-origin mutation. */
	@Nonnull
	static Stream<Arguments> captureCases() {
		return cases().flatMap(testCase -> Stream.of(Arguments.of(testCase, true), Arguments.of(testCase, false)));
	}

	/** Selects all in-scope fulltext settings to exercise typed mutation resolution and custom expression output. */
	@Nonnull
	private static String typedSelection() {
		final String attributes = "searchableInScopes analyzer lengthPivot searchWeight";
		final String references = "searchableThroughInScopes { scope mode } searchableThroughWeight";
		return "... on EvitaMutation { mutationType } " +
			"... on ModifyReferenceAttributeSchemaMutation { attributeSchemaMutation { " +
			"... on EvitaMutation { mutationType } " +
			"... on CreateAttributeSchemaMutation { " + attributes + " } " +
			"... on SetAttributeSchemaSearchableMutation { " + attributes + " } } } " +
			"... on CreateAttributeSchemaMutation { " + attributes + " } " +
			"... on CreateGlobalAttributeSchemaMutation { " + attributes + " } " +
			"... on SetAttributeSchemaSearchableMutation { " + attributes + " } " +
			"... on CreateAssociatedDataSchemaMutation { " + attributes + " textExpression } " +
			"... on SetAssociatedDataSchemaSearchableMutation { " + attributes + " textExpression } " +
			"... on CreateReferenceSchemaMutation { " + references + " } " +
			"... on CreateReflectedReferenceSchemaMutation { indexedInScopes { scope indexType } " +
			references + " } " +
			"... on SetReferenceSchemaSearchableThroughMutation { searchableThroughInScopes { scope mode } weight }";
	}
}
