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

import com.fasterxml.jackson.databind.JsonNode;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.SchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.ExternalApiFunctionTestsSupport;
import io.evitadb.externalApi.ExternalApiWebSocketFunctionTestsSupport;
import io.evitadb.externalApi.FulltextMutationFunctionalTestSupport;
import io.evitadb.externalApi.FulltextMutationFunctionalTestSupport.MutationCase;
import io.evitadb.externalApi.FulltextMutationTestSupport;
import io.evitadb.externalApi.FulltextSchemaFunctionalTestSupport;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fulltext mutation input and Java-origin CDC output over a real Rest server. */
@Tag(SCHEMA)
@Tag(FULLTEXT)
@Tag(CDC)
@DisplayName("Rest fulltext mutation input and CDC")
class CatalogRestFulltextMutationsFunctionalTest extends RestEndpointFunctionalTest
	implements ExternalApiFunctionTestsSupport, ExternalApiWebSocketFunctionTestsSupport {
	private static final String DATA_SET = "RestFulltextMutations";

	/** Supplies every entity and catalog mutation fixture through the shared declarations. */
	@Nonnull
	private static Stream<MutationCase> allCases() {
		return cases();
	}

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
	@MethodSource("allCases")
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
	@MethodSource("allCases")
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
					assertFalse(frame.contains("\"type\":\"error\""), frame);
				}
				return frames.size() >= captures.size() + 1;
			}, Duration.ofSeconds(30)), "Expected all fulltext mutation captures");
		}, captures.size() + 1, events -> assertCapture(events, testCase));
	}
	/** Declares every mirror through REST and checks the catalog and both collection schema read routes. */
	@DisplayName("Reads every fulltext schema setting declared through REST")
	@Test
	@UseDataSet(DATA_SET)
	void shouldReadFulltextSchema(@Nonnull Evita evita, @Nonnull RestTester tester) {
		final String catalog = FulltextSchemaFunctionalTestSupport.prepare(evita);
		final List<Map<String, Object>> mutations = FulltextSchemaFunctionalTestSupport.declarations().stream()
			.map(FulltextMutationFunctionalTestSupport::input).toList();
		tester.test(catalog).urlPathSuffix("/schema").httpMethod(Request.METHOD_PUT)
			.requestBody(json(Map.of("mutations", mutations))).executeAndExpectOkAndThen();
		final JsonNode schema = FulltextSchemaFunctionalTestSupport.read(tester.test(catalog).urlPathSuffix("/schema")
			.httpMethod(Request.METHOD_GET).executeAndExpectOkAndThen().extract().asString());
		FulltextSchemaFunctionalTestSupport.assertCatalog(schema);
		FulltextSchemaFunctionalTestSupport.assertKeys(schema.path("attributes"), "globalText");
		FulltextSchemaFunctionalTestSupport.assertField(schema.path("attributes").path("globalText"), false);
		for (final String type : List.of("owner", "target")) {
			final JsonNode entity = FulltextSchemaFunctionalTestSupport.read(tester.test(catalog)
				.urlPathSuffix("/" + type + "/schema").httpMethod(Request.METHOD_GET)
				.executeAndExpectOkAndThen().extract().asString());
			FulltextSchemaFunctionalTestSupport.assertKeys(entity.path("attributes"),
				type.equals("owner") ? new String[]{"globalText", "text"} : new String[0]);
			for (final JsonNode field : entity.path("attributes")) {
				FulltextSchemaFunctionalTestSupport.assertField(field, false);
			}
			FulltextSchemaFunctionalTestSupport.assertKeys(entity.path("associatedData"),
				type.equals("owner") ? "text" : "content");
			for (final JsonNode field : entity.path("associatedData")) {
				FulltextSchemaFunctionalTestSupport.assertField(field, type.equals("owner"));
			}
			FulltextSchemaFunctionalTestSupport.assertKeys(entity.path("references"),
				type.equals("owner") ? new String[]{"related", "reflected"} : new String[]{"original"});
			if (type.equals("owner")) {
				for (final JsonNode ref : entity.path("references")) {
					FulltextSchemaFunctionalTestSupport.assertReference(ref);
					FulltextSchemaFunctionalTestSupport.assertKeys(ref.path("attributes"), "text");
					for (final JsonNode field : ref.path("attributes")) {
						FulltextSchemaFunctionalTestSupport.assertField(field, false);
					}
				}
			}
		}
	}

	/**
	 * A ranking weight of `0.0001`, which `Float#toString` renders as `1.0E-4`, reads as the decimal `0.00010` that
	 * the GraphQL schema read renders for the same weight.
	 */
	@DisplayName("Reads small ranking weights as plain decimals")
	@Test
	@UseDataSet(DATA_SET)
	void shouldReadSmallRankingWeightsAsPlainDecimals(@Nonnull Evita evita, @Nonnull RestTester tester) {
		final String catalog = FulltextSchemaFunctionalTestSupport.prepare(evita);
		final LocalEntitySchemaMutation[] fields = FulltextMutationTestSupport.mutations(0.0001f)
			.filter(mutation -> mutation.getClass() == CreateAttributeSchemaMutation.class ||
				mutation.getClass() == CreateAssociatedDataSchemaMutation.class ||
				mutation.getClass() == CreateReferenceSchemaMutation.class)
			.map(LocalEntitySchemaMutation.class::cast)
			.toArray(LocalEntitySchemaMutation[]::new);
		evita.updateCatalog(catalog, session -> {
			session.updateCatalogSchema(new ModifyEntitySchemaMutation("owner", fields));
		});

		final JsonNode entity = FulltextSchemaFunctionalTestSupport.read(tester.test(catalog)
			.urlPathSuffix("/owner/schema").httpMethod(Request.METHOD_GET)
			.executeAndExpectOkAndThen().extract().asString());

		assertEquals("0.00010", entity.path("attributes").path("text").path("searchWeight").asText());
		assertEquals("0.00010", entity.path("associatedData").path("text").path("searchWeight").asText());
		assertEquals("0.00010", entity.path("references").path("related").path("searchableThroughWeight").asText());
	}

	/** Verifies catalog and system OpenAPI registries and their mutation discriminators contain all nine types. */
	@DisplayName("Describes catalog fulltext capture bodies in catalog and system OpenAPI")
	@Test
	@UseDataSet(DATA_SET)
	void shouldDescribeCatalogFulltextMutations(@Nonnull Evita evita, @Nonnull RestTester tester) {
		final String catalog = FulltextSchemaFunctionalTestSupport.prepare(evita);
		for (final String api : List.of(catalog, "system")) {
			final JsonNode document = FulltextSchemaFunctionalTestSupport.read(tester.test(api)
				.httpMethod(Request.METHOD_GET).acceptHeader("application/json")
				.executeAndExpectOkAndThen().extract().asString());
			for (final SchemaMutation mutation :
				FulltextMutationTestSupport.catalogMutations().toList()) {
				final String name = mutation.getClass().getSimpleName();
				assertTrue(document.path("components").path("schemas").has(name), name + " absent from " + api);
				assertTrue(document.path("components").path("schemas").path("EvitaMutation")
					.path("discriminator").path("mapping").has(name), name + " absent from discriminator in " + api);
			}
		}
	}

}
