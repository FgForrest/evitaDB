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

import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.externalApi.FulltextMutationTestSupport;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import com.fasterxml.jackson.databind.JsonNode;
import io.evitadb.api.requestResponse.cdc.CaptureArea;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.ExternalApiFunctionTestsSupport;
import io.evitadb.externalApi.ExternalApiWebSocketFunctionTestsSupport;
import io.evitadb.externalApi.FulltextMutationFunctionalTestSupport;
import io.evitadb.externalApi.FulltextMutationFunctionalTestSupport.MutationCase;
import io.evitadb.externalApi.FulltextSchemaFunctionalTestSupport;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.graphql.api.testSuite.GraphQLEndpointFunctionalTest;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.tester.GraphQLTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fulltext mutation input and Java-origin CDC output over a real GraphQL server. */
@Tag(SCHEMA)
@Tag(FULLTEXT)
@Tag(CDC)
@DisplayName("GraphQL fulltext mutation input and CDC")
class CatalogGraphQLFulltextMutationsFunctionalTest extends GraphQLEndpointFunctionalTest
	implements ExternalApiFunctionTestsSupport, ExternalApiWebSocketFunctionTestsSupport {
	private static final String DATA_SET = "GraphQLFulltextMutations";

	/** Supplies every entity and catalog mutation fixture through the shared declarations. */
	@Nonnull
	private static Stream<MutationCase> allCases() {
		return cases();
	}

	/** Uses one server while keeping mutation prerequisites isolated by catalog. */
	@Override
	@DataSet(value = DATA_SET, openWebApi = GraphQLProvider.CODE, readOnly = false, destroyAfterClass = true)
	@Nonnull
	protected DataCarrier setUp(@Nonnull Evita evita) {
		return new DataCarrier();
	}

	/**
	 * Supplies a null entry and a blank text for both the declaring and the replacing mutation; the blank text passes
	 * the GraphQL input type and is refused by the mutation built from the input.
	 *
	 * @return whether to modify, the GraphQL literal of the sample list and a fragment of the expected error
	 */
	@Nonnull
	private static Stream<Arguments> invalidSampleCases() {
		return Stream.of(
			Arguments.of(false, "[null]", "must not be null"),
			Arguments.of(true, "[null]", "must not be null"),
			Arguments.of(false, "[\" \"]", "blank"),
			Arguments.of(true, "[\" \"]", "blank")
		);
	}

	/**
	 * Refuses a null or blank sample at the endpoint without changing the declaration or schema version.
	 *
	 * @param modify whether to replace an existing analyzer rather than create one
	 * @param samples the GraphQL literal of the refused sample list
	 * @param error a fragment of the expected error message
	 * @param evita the engine used to verify that rejected input changes nothing
	 * @param tester the endpoint client sending the refused sample list
	 */
	@DisplayName("Rejects null and blank analyzer samples as client input errors")
	@ParameterizedTest
	@MethodSource("invalidSampleCases")
	@UseDataSet(DATA_SET)
	void shouldRejectInvalidAnalyzerSample(
		boolean modify,
		@Nonnull String samples,
		@Nonnull String error,
		@Nonnull Evita evita,
		@Nonnull GraphQLTester tester
	) {
		final String catalog = FulltextSchemaFunctionalTestSupport.prepare(evita);
		if (modify) {
			evita.updateCatalog(catalog, session -> {
				session.getCatalogSchema().openForWrite()
					.withFulltextAnalyzer("custom", analyzer -> analyzer.basedOn("text").samples("old"))
					.updateVia(session);
			});
		}
		final int version = evita.queryCatalog(catalog, session -> {
			return session.getCatalogSchema().version();
		});
		final String mutation = (modify ? "modify" : "create") + "FulltextAnalyzerSchemaMutation";
		tester.test(catalog).urlPathSuffix("/schema").document(
			"mutation { updateCatalogSchema(mutations: [{ " + mutation +
			" : { name: \"custom\", basedOn: \"text\", samples: " + samples + " } }]) { version } }")
			.executeAndExpectErrorsAndThen()
			.body("data", nullValue())
			.body("errors[0].message", containsString("samples"))
			.body("errors[0].message", containsString(error));
		evita.queryCatalog(catalog, session -> {
			assertEquals(version, session.getCatalogSchema().version());
			if (modify) {
				assertEquals(List.of("old"), session.getCatalogSchema().getFulltextAnalyzer("custom")
					.orElseThrow().getSamples());
			} else {
				assertFalse(session.getCatalogSchema().getFulltextAnalyzers().containsKey("custom"));
			}
			return null;
		});
	}

	/** Exercises optional fulltext fields through a real schema endpoint and verifies the Java mutation event. */
	@DisplayName("Applies fulltext settings through the schema endpoint")
	@ParameterizedTest
	@MethodSource("allCases")
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
			(typed ? " { " + (testCase.global() ? catalogTypedSelection() : typedSelection()) + " }" : "") + " }";
		tester.testWebSocket(catalog, "/schema", context -> {
			context.writer().write(createConnectionInitMessage());
			context.awaitEvents(1);
			context.writer().write(json(Map.of("id", subscriptionId, "type", "subscribe",
				"payload", Map.of("query", "subscription { " + subscription + " }"))));
			assertTrue(context.tryAwaitEvents(frames -> {
				for (final String frame : frames) {
					assertFalse(frame.contains("\"type\":\"error\""), frame);
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
	/**
	 * Selects catalog-only bodies through the catalog mutation union, including analyzer samples.
	 *
	 * @return the selection shared by typed catalog and system subscriptions
	 */
	@Nonnull
	public static String catalogTypedSelection() {
		final String phases = "text { step parameters } words { step parameters }";
		final String analyzer = "name basedOn samples allLanguages { " + phases +
			" } languages { language phases { " + phases + " } }";
		final String resource = "name content { kind words groups { words } }";
		final String attributes = "searchableInScopes analyzer lengthPivot searchWeight";
		return "... on EvitaMutation { mutationType } " +
			"... on CreateGlobalAttributeSchemaMutation { " + attributes + " } " +
			"... on SetAttributeSchemaSearchableMutation { " + attributes + " } " +
			"... on CreateFulltextLanguageSchemaMutation { language wordSplitting { step parameters } } " +
			"... on ModifyFulltextLanguageSchemaMutation { language wordSplitting { step parameters } } " +
			"... on RemoveFulltextLanguageSchemaMutation { language } " +
			"... on CreateFulltextResourceSchemaMutation { " + resource + " } " +
			"... on ModifyFulltextResourceSchemaMutation { " + resource + " } " +
			"... on RemoveFulltextResourceSchemaMutation { name } " +
			"... on CreateFulltextAnalyzerSchemaMutation { " + analyzer + " } " +
			"... on ModifyFulltextAnalyzerSchemaMutation { " + analyzer + " } " +
			"... on RemoveFulltextAnalyzerSchemaMutation { name }";
	}

	/**
	 * Decimal input and schema output retain the same significant digits in every weight field.
	 * @param weight decimal ranking contribution supplied by this case
	 * @param evita engine supplying the shared schema fixture
	 * @param tester client sending mutations and reading the resulting schema
	 */
	@ParameterizedTest
	@ValueSource(strings = {"0", "0.0000001", "12345678.5", "0.123456789012345678901234567891"})
	@UseDataSet(DATA_SET)
	@DisplayName("Round trips decimal schema weights without precision loss")
	void shouldRoundTripDecimalWeights(
		@Nonnull BigDecimal weight, @Nonnull Evita evita, @Nonnull GraphQLTester tester
	) {
		final String catalog = FulltextSchemaFunctionalTestSupport.prepare(evita);
		final LocalEntitySchemaMutation[] fields = FulltextMutationTestSupport.mutations(weight)
			.filter(mutation -> mutation instanceof CreateAttributeSchemaMutation ||
				mutation instanceof CreateAssociatedDataSchemaMutation ||
				mutation instanceof CreateReferenceSchemaMutation)
			.map(LocalEntitySchemaMutation.class::cast).toArray(LocalEntitySchemaMutation[]::new);
		tester.test(catalog).urlPathSuffix("/schema").document("mutation { updateCatalogSchema(mutations: [" +
			graphql(input(new ModifyEntitySchemaMutation("owner", fields)), "") + "]) { version } }")
			.executeAndExpectOkAndThen();
		final String response = tester.test(catalog).urlPathSuffix("/schema").document(
			"{ getCatalogSchema { entitySchemas { owner { " +
			"allAttributes { ... on EntityAttributeSchema { searchWeight } } " +
			"allAssociatedData { searchWeight } allReferences { searchableThroughWeight } } } } }")
			.executeAndExpectOkAndThen().extract().asString();
		final JsonNode schema = FulltextSchemaFunctionalTestSupport.read(response).path("data")
			.path("getCatalogSchema").path("entitySchemas").path("owner");
		for (final JsonNode decimal : List.of(schema.path("allAttributes").path(0).path("searchWeight"),
			schema.path("allAssociatedData").path(0).path("searchWeight"),
			schema.path("allReferences").path(0).path("searchableThroughWeight"))) {
			assertTrue(decimal.isTextual(), decimal.toString());
			assertEquals(0, weight.compareTo(new BigDecimal(decimal.asText())));
		}
	}

	/**
	 * Declares all schema mirrors through GraphQL and reads their literal values, including author samples.
	 *
	 * @param evita the engine receiving the declarations
	 * @param tester the real GraphQL endpoint client
	 */
	@DisplayName("Reads every fulltext schema setting declared through GraphQL")
	@Test
	@UseDataSet(DATA_SET)
	void shouldReadFulltextSchema(@Nonnull Evita evita, @Nonnull GraphQLTester tester) {
		final String catalog = FulltextSchemaFunctionalTestSupport.prepare(evita);
		final List<Map<String, Object>> mutations = FulltextSchemaFunctionalTestSupport.declarations().stream()
			.map(FulltextMutationFunctionalTestSupport::input).toList();
		tester.test(catalog).urlPathSuffix("/schema").document("mutation { updateCatalogSchema(mutations: " +
			graphql(mutations, "") + ") { version } }").executeAndExpectOkAndThen();
		final String fields = "name searchable analyzer lengthPivot searchWeight";
		final String reference = "name searchableThrough { scope mode } searchableThroughWeight " +
			"allAttributes { " + fields + " }";
		final String phases = "text { step parameters } words { step parameters }";
		final String response = tester.test(catalog).urlPathSuffix("/schema").document(
			"{ getCatalogSchema { allAttributes { " + fields + " } " +
			"fulltextLanguages { language wordSplitting { step parameters } } " +
			"fulltextResources { name content { kind words groups { words } } } " +
			"fulltextAnalyzers { name basedOn samples allLanguages { " + phases +
			" } languages { language phases { " + phases + " } } } " +
			"allEntitySchemas { name allAttributes { ... on EntityAttributeSchema { " + fields +
			" } ... on GlobalAttributeSchema { " + fields + " } } " +
			"allAssociatedData { " + fields + " textExpression } allReferences { " + reference + " } } " +
			"entitySchemas { owner { references { related { " + reference + " } reflected { " + reference +
			" } } } } } }").executeAndExpectOkAndThen().extract().asString();
		final JsonNode schema = FulltextSchemaFunctionalTestSupport.read(response)
			.path("data").path("getCatalogSchema");
		FulltextSchemaFunctionalTestSupport.assertCatalog(schema);
		assertEquals("{\"name\":\"standalone\",\"basedOn\":null,\"allLanguages\":{\"text\":[],\"words\":[]}," +
			"\"languages\":[]}",
			schema.path("fulltextAnalyzers").path(1).toString().replace(",\"samples\":[]", ""));
		FulltextSchemaFunctionalTestSupport.assertNames(schema.path("allAttributes"), "globalText");
		FulltextSchemaFunctionalTestSupport.assertField(schema.path("allAttributes").path(0), false);
		FulltextSchemaFunctionalTestSupport.assertNames(schema.path("allEntitySchemas"), "owner", "target");
		for (final JsonNode entity : schema.path("allEntitySchemas")) {
			final boolean owner = entity.path("name").asText().equals("owner");
			FulltextSchemaFunctionalTestSupport.assertNames(entity.path("allAttributes"),
				owner ? new String[]{"globalText", "text"} : new String[0]);
			for (final JsonNode field : entity.path("allAttributes")) {
				FulltextSchemaFunctionalTestSupport.assertField(field, false);
			}
			FulltextSchemaFunctionalTestSupport.assertNames(entity.path("allAssociatedData"),
				owner ? "text" : "content");
			for (final JsonNode field : entity.path("allAssociatedData")) {
				FulltextSchemaFunctionalTestSupport.assertField(field, entity.path("name").asText().equals("owner"));
			}
			FulltextSchemaFunctionalTestSupport.assertNames(entity.path("allReferences"),
				owner ? new String[]{"related", "reflected"} : new String[]{"original"});
			if (owner) {
				for (final JsonNode ref : entity.path("allReferences")) {
					FulltextSchemaFunctionalTestSupport.assertReference(ref);
					FulltextSchemaFunctionalTestSupport.assertNames(ref.path("allAttributes"), "text");
					for (final JsonNode field : ref.path("allAttributes")) {
						FulltextSchemaFunctionalTestSupport.assertField(field, false);
					}
				}
			}
		}
		final JsonNode references = schema.path("entitySchemas").path("owner").path("references");
		FulltextSchemaFunctionalTestSupport.assertKeys(references, "related", "reflected");
		for (final JsonNode ref : references) {
			FulltextSchemaFunctionalTestSupport.assertReference(ref);
			FulltextSchemaFunctionalTestSupport.assertNames(ref.path("allAttributes"), "text");
			for (final JsonNode field : ref.path("allAttributes")) {
				FulltextSchemaFunctionalTestSupport.assertField(field, false);
			}
		}
	}

	/** Checks both system API body resolvers against Java-origin catalog-only mutations. */
	@DisplayName("Delivers catalog fulltext bodies through the system GraphQL API")
	@ParameterizedTest
	@MethodSource("systemCaptureCases")
	@UseDataSet(DATA_SET)
	void shouldDeliverSystemFulltextCapture(
		@Nonnull MutationCase testCase, boolean typed, @Nonnull Evita evita, @Nonnull GraphQLTester tester
	) {
		final String catalog = prepare(evita, testCase);
		final long startVersion = getStartVersionForCatalogCDC(evita, catalog);
		evita.updateCatalog(catalog, session -> { apply(session, testCase); });
		final List<ChangeCatalogCapture> captures = history(evita, catalog, startVersion, testCase);
		final String subscriptionId = createSubscriptionId();
		final String subscription = (typed ? "onCatalogChange" : "onCatalogChangeUntyped") +
			"(catalogName: " + json(catalog) + ", sinceVersion: " + json(Long.toString(startVersion)) + ") { body" +
			(typed ? " { " + catalogTypedSelection() + " }" : "") + " }";
		tester.testWebSocket("system", context -> {
			context.writer().write(createConnectionInitMessage());
			context.awaitEvents(1);
			context.writer().write(json(Map.of("id", subscriptionId, "type", "subscribe",
				"payload", Map.of("query", "subscription { " + subscription + " }"))));
			assertTrue(context.tryAwaitEvents(frames -> {
				for (final String frame : frames) {
					assertFalse(frame.contains("\"type\":\"error\""), frame);
				}
				return frames.size() >= captures.size() + 1;
			}, Duration.ofSeconds(30)), "Expected catalog bodies from the system API");
		}, captures.size() + 1, events -> assertCapture(events, testCase));
	}

	/** Provides typed and untyped system captures for each catalog-only fulltext mutation. */
	@Nonnull
	static Stream<Arguments> systemCaptureCases() {
		return catalogCases()
			.flatMap(testCase -> Stream.of(Arguments.of(testCase, true), Arguments.of(testCase, false)));
	}

}
