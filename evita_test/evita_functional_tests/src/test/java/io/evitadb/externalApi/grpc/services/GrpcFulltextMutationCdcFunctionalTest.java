/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.externalApi.grpc.services;

import java.math.BigDecimal;
import com.google.protobuf.StringValue;
import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.*;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.analysis.FulltextAnalysisPhases;
import io.evitadb.api.requestResponse.schema.analysis.SynonymGroup;
import io.evitadb.api.requestResponse.schema.analysis.SynonymList;
import io.evitadb.api.requestResponse.schema.analysis.WordList;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.driver.EvitaClient;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.driver.interceptor.ClientSessionInterceptor;
import io.evitadb.driver.interceptor.ClientSessionInterceptor.SessionIdHolder;
import io.evitadb.externalApi.ExternalApiFunctionTestsSupport;
import io.evitadb.externalApi.ExternalApiWebSocketFunctionTestsSupport;
import io.evitadb.externalApi.FulltextMutationFunctionalTestSupport.MutationCase;
import io.evitadb.externalApi.FulltextSchemaFunctionalTestSupport;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.grpc.TestGrpcClientBuilderCreator;
import io.evitadb.externalApi.grpc.generated.EvitaServiceGrpc;
import io.evitadb.externalApi.grpc.generated.EvitaSessionServiceGrpc;
import io.evitadb.externalApi.grpc.generated.GetMutationsHistoryResponse;
import io.evitadb.externalApi.grpc.generated.GrpcCloseRequest;
import io.evitadb.externalApi.grpc.generated.GrpcCreateFulltextAnalyzerSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse;
import io.evitadb.externalApi.grpc.generated.GrpcLocalCatalogSchemaMutation;
import io.evitadb.externalApi.grpc.generated.GrpcUpdateCatalogSchemaRequest;
import io.evitadb.externalApi.grpc.requestResponse.cdc.ChangeCaptureConverter;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.VersionUtils.SemVer;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static java.math.BigDecimal.valueOf;
import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.externalApi.FulltextMutationFunctionalTestSupport.*;
import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Streams retained Java-origin fulltext mutation captures through the real gRPC service. */
@ExtendWith(EvitaParameterResolver.class)
@Tag(EXTERNAL_API)
@Tag(GRPC)
@Tag(SCHEMA)
@Tag(FULLTEXT)
@Tag(CDC)
@DisplayName("gRPC Java-origin fulltext mutation CDC")
class GrpcFulltextMutationCdcFunctionalTest
	implements ExternalApiFunctionTestsSupport, ExternalApiWebSocketFunctionTestsSupport {
	private static final String DATA_SET = "GrpcFulltextMutationCdc";

	/** Supplies every entity and catalog mutation fixture through the shared declarations. */
	@Nonnull
	private static Stream<MutationCase> allCases() {
		return cases();
	}

	/** Supplies catalog-only mutations for the real driver input path. */
	@Nonnull
	private static Stream<MutationCase> catalogInputCases() {
		return catalogCases();
	}

	/** Shares one real server and client channel across isolated catalog fixtures. */
	@DataSet(value = DATA_SET, openWebApi = {GrpcProvider.CODE, SystemProvider.CODE},
		readOnly = false, destroyAfterClass = true)
	@Nonnull
	GrpcClientBuilder setUp(@Nonnull EvitaServer server) {
		return TestGrpcClientBuilderCreator.getBuilder(
			new ClientSessionInterceptor(EvitaClientConfiguration.builder().build().clientId(), new SemVer(2026, 3)),
			server.getExternalApiServer());
	}

	/** Checks every capture body against the Java event, including nested wrappers and global attributes. */
	@DisplayName("Delivers all fulltext settings from a Java-origin CDC event")
	@ParameterizedTest
	@MethodSource("allCases")
	@UseDataSet(DATA_SET)
	void shouldDeliverJavaOriginFulltextCapture(
		@Nonnull MutationCase testCase, @Nonnull Evita evita, @Nonnull GrpcClientBuilder client
	) {
		final String catalog = prepare(evita, testCase);
		final long startVersion = getStartVersionForCatalogCDC(evita, catalog);
		evita.updateCatalog(catalog, session -> { apply(session, testCase); });
		final List<ChangeCatalogCapture> expected = history(evita, catalog, startVersion, testCase);
		final UUID catalogId = expected.getFirst().catalogId();
		final EvitaServiceGrpc.EvitaServiceBlockingStub service = client.build(
			EvitaServiceGrpc.EvitaServiceBlockingStub.class);
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub session = client.build(
			EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		final GrpcEvitaSessionResponse opened = service.createReadOnlySession(
			GrpcEvitaSessionRequest.newBuilder().setCatalogName(catalog).build());
		SessionIdHolder.setSessionId(opened.getSessionId());
		try {
			final Iterator<GetMutationsHistoryResponse> stream = session.getMutationsHistoryForward(
				ChangeCaptureConverter.toGrpcChangeCaptureRequest(ChangeCatalogCaptureRequest.builder()
					.sinceVersion(startVersion).content(ChangeCaptureContent.BODY).build()));
			final List<ChangeCatalogCapture> actual = new ArrayList<>();
			stream.forEachRemaining(response -> response.getChangeCaptureList().forEach(
				capture -> actual.add(ChangeCaptureConverter.toChangeCatalogCapture(capture, catalogId))));
			assertEquals(expected, actual);
		} finally {
			try {
				session.close(GrpcCloseRequest.getDefaultInstance());
			} finally {
				SessionIdHolder.reset();
			}
		}
	}
	/** Sends catalog mutations through the Java driver's real gRPC endpoint and inspects the Java-origin history. */
	@DisplayName("Accepts every catalog fulltext mutation over gRPC")
	@ParameterizedTest
	@MethodSource("catalogInputCases")
	@UseDataSet(DATA_SET)
	void shouldApplyFulltextMutation(
		@Nonnull MutationCase testCase, @Nonnull Evita evita, @Nonnull EvitaClient client
	) {
		final String catalog = prepare(evita, testCase);
		final long startVersion = getStartVersionForCatalogCDC(evita, catalog);
		client.updateCatalog(catalog, session -> { apply(session, testCase); });
		history(evita, catalog, startVersion, testCase);
	}

	/**
	 * Refuses a blank sample sent on the wire without changing the catalog schema. The Java client refuses the sample
	 * before sending it, so the request is built through the raw stubs.
	 *
	 * @param evita the engine used to verify that rejected input changes nothing
	 * @param client the builder of raw stubs for the real gRPC server
	 */
	@DisplayName("Rejects a blank analyzer sample as an invalid argument")
	@Test
	@UseDataSet(DATA_SET)
	void shouldRejectBlankAnalyzerSample(@Nonnull Evita evita, @Nonnull GrpcClientBuilder client) {
		final String catalog = FulltextSchemaFunctionalTestSupport.prepare(evita);
		final int version = evita.queryCatalog(catalog, session -> {
			return session.getCatalogSchema().version();
		});
		final GrpcUpdateCatalogSchemaRequest request = GrpcUpdateCatalogSchemaRequest.newBuilder()
			.addSchemaMutations(GrpcLocalCatalogSchemaMutation.newBuilder().setCreateFulltextAnalyzerSchemaMutation(
				GrpcCreateFulltextAnalyzerSchemaMutation.newBuilder()
					.setName("custom").setBasedOn(StringValue.of("text")).addSamples(" ")))
			.build();
		final EvitaServiceGrpc.EvitaServiceBlockingStub service = client.build(
			EvitaServiceGrpc.EvitaServiceBlockingStub.class);
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub session = client.build(
			EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		final GrpcEvitaSessionResponse opened = service.createReadWriteSession(
			GrpcEvitaSessionRequest.newBuilder().setCatalogName(catalog).build());
		SessionIdHolder.setSessionId(opened.getSessionId());
		try {
			final StatusRuntimeException exception = assertThrows(
				StatusRuntimeException.class, () -> session.updateCatalogSchema(request));

			assertEquals(Status.Code.INVALID_ARGUMENT, exception.getStatus().getCode());
			assertTrue(exception.getMessage().contains("blank"), exception.getMessage());
		} finally {
			try {
				session.close(GrpcCloseRequest.getDefaultInstance());
			} finally {
				SessionIdHolder.reset();
			}
		}
		evita.queryCatalog(catalog, local -> {
			assertEquals(version, local.getCatalogSchema().version());
			assertFalse(local.getCatalogSchema().getFulltextAnalyzers().containsKey("custom"));
			return null;
		});
	}

	/**
	 * Reads equal Java schemas with author samples and queries composition through shared target text.
	 *
	 * @param evita the embedded engine providing the Java-side schema
	 * @param client the real gRPC Java client
	 */
	@DisplayName("Declares and reads fulltext schema mirrors and queries composition over gRPC")
	@Test
	@UseDataSet(DATA_SET)
	void shouldReadFulltextSchemaAndQueryComposition(@Nonnull Evita evita, @Nonnull EvitaClient client) {
		final String catalog = FulltextSchemaFunctionalTestSupport.prepare(evita);
		client.updateCatalog(catalog, session -> {
			session.updateCatalogSchema(FulltextSchemaFunctionalTestSupport.declarations().toArray(
				LocalCatalogSchemaMutation[]::new));
		});
		client.queryCatalog(catalog, remote -> {
			evita.queryCatalog(catalog, local -> {
				final CatalogSchemaContract expected = local.getCatalogSchema();
				final CatalogSchemaContract actual = remote.getCatalogSchema();
				assertEquals(Set.of("globalText"), actual.getAttributes().keySet());
				assertEquals(Set.of("en"), actual.getFulltextLanguages().keySet());
				assertEquals(Set.of("terms", "synonyms"), actual.getFulltextResources().keySet());
				assertEquals(Set.of("custom", "standalone"), actual.getFulltextAnalyzers().keySet());
				assertEquals(expected.getAttributes(), actual.getAttributes());
				assertEquals(expected.getFulltextLanguages(), actual.getFulltextLanguages());
				assertEquals(expected.getFulltextResources(), actual.getFulltextResources());
				assertEquals(expected.getFulltextAnalyzers(), actual.getFulltextAnalyzers());
				final List<String> samples = actual.getFulltextAnalyzers().get("custom").getSamples();
				assertEquals(100, samples.size());
				assertEquals("Příliš žluťoučký kůň 東京", samples.getFirst());
				assertEquals("ž".repeat(1_000), samples.get(1));
				assertEquals("sample 99", samples.getLast());
				assertEquals(List.of(), actual.getFulltextAnalyzers().get("standalone").getSamples());
				assertEquals(valueOf(0), actual.getAttributes().get("globalText").getSearchWeight());
				assertEquals("html-text", actual.getAttributes().get("globalText").getAnalyzer());
				assertEquals(List.of("standard", "word-number-split"), actual.getFulltextLanguages().get("en")
					.getWordSplitting().stream()
					.map(AnalysisStep::name).toList());
				assertEquals(new WordList(List.of("and", "the")),
					actual.getFulltextResources().get("terms").getContent());
				assertEquals(Optional.of("text"), actual.getFulltextAnalyzers().get("custom").getBasedOn());
				assertEquals(new SynonymList(List.of(new SynonymGroup(List.of("quick", "fast")),
				new SynonymGroup(List.of("bright", "light")))),
					actual.getFulltextResources().get("synonyms").getContent());
				assertEquals(Optional.empty(), actual.getFulltextAnalyzers().get("standalone").getBasedOn());
				assertEquals(new FulltextAnalysisPhases(List.of(), List.of()),
					actual.getFulltextAnalyzers().get("standalone").getAllLanguages());
				assertEquals(Map.of(), actual.getFulltextAnalyzers().get("standalone").getLanguages());
				assertNull(
					actual.getFulltextAnalyzers().get("custom").getLanguages().get("en").text());
				assertEquals(List.of(), actual.getFulltextAnalyzers().get("custom").getLanguages().get("en").words());
				for (final String type : List.of("owner", "target")) {
					final EntitySchemaDecorator server = (EntitySchemaDecorator) local.getEntitySchemaOrThrow(type);
					final EntitySchemaDecorator received = (EntitySchemaDecorator) remote.getEntitySchemaOrThrow(type);
					assertEquals(server.getDelegate(), received.getDelegate());
					assertEquals(server.getAttributes(), received.getAttributes());
					assertEquals(server.getAssociatedData(), received.getAssociatedData());
					assertEquals(server.getReferences(), received.getReferences());
					assertEquals(type.equals("owner") ? Set.of("globalText", "text") : Set.of(),
						received.getAttributes().keySet());
					for (final AttributeSchemaContract field : received.getAttributes().values()) {
						assertEquals(Set.of(Scope.LIVE, Scope.ARCHIVED),
									field.getSearchableInScopes());
						assertEquals("html-text", field.getAnalyzer());
						assertEquals(73, field.getLengthPivot());
						assertEquals(valueOf(0), field.getSearchWeight());
					}
					assertEquals(Set.of(type.equals("owner") ? "text" : "content"),
						received.getAssociatedData().keySet());
					for (final AssociatedDataSchemaContract field : received.getAssociatedData().values()) {
						assertEquals(Set.of(Scope.LIVE, Scope.ARCHIVED),
									field.getSearchableInScopes());
						assertEquals(type.equals("owner") ? "$value.title" : null,
							field.getTextExpression() == null ? null : field.getTextExpression().toExpressionString());
						assertEquals("html-text", field.getAnalyzer());
						assertEquals(73, field.getLengthPivot());
						assertEquals(valueOf(0), field.getSearchWeight());
					}
					assertEquals(type.equals("owner") ? Set.of("related", "reflected") : Set.of("original"),
						received.getReferences().keySet());
					if (type.equals("owner")) {
						for (final ReferenceSchemaContract ref : received.getReferences().values()) {
							assertEquals(Map.of(Scope.LIVE, ReferenceSearchMode.COMPOSITION,
								Scope.ARCHIVED, ReferenceSearchMode.COMPOSITION),
								ref.getSearchableThroughInScopes());
							assertEquals(valueOf(0), ref.getSearchableThroughWeight());
							assertEquals(Set.of("text"), ref.getAttributes().keySet());
							for (final AttributeSchemaContract field : ref.getAttributes().values()) {
								assertEquals(Set.of(Scope.LIVE, Scope.ARCHIVED),
									field.getSearchableInScopes());
								assertEquals("html-text", field.getAnalyzer());
								assertEquals(73, field.getLengthPivot());
								assertEquals(valueOf(0), field.getSearchWeight());
							}
						}
					}
				}
				return null;
			});
			return null;
		});
		FulltextSchemaFunctionalTestSupport.insertSharedTarget(evita, catalog);
		client.queryCatalog(catalog, session -> {
			assertEquals(List.of(1, 2), session.queryList(query(collection("owner"),
				filterBy(entityLocaleEquals(Locale.ENGLISH), textMatches("quartz"))),
				EntityReference.class).stream()
				.map(EntityReference::getPrimaryKey).toList());
			return null;
		});
	}

}
