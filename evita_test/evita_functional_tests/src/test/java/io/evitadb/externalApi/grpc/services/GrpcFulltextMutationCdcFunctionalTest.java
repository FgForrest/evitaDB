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

import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.core.Evita;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.driver.interceptor.ClientSessionInterceptor;
import io.evitadb.driver.interceptor.ClientSessionInterceptor.SessionIdHolder;
import io.evitadb.externalApi.ExternalApiFunctionTestsSupport;
import io.evitadb.externalApi.ExternalApiWebSocketFunctionTestsSupport;
import io.evitadb.externalApi.FulltextMutationFunctionalTestSupport.MutationCase;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.grpc.TestGrpcClientBuilderCreator;
import io.evitadb.externalApi.grpc.generated.EvitaServiceGrpc;
import io.evitadb.externalApi.grpc.generated.EvitaSessionServiceGrpc;
import io.evitadb.externalApi.grpc.generated.GetMutationsHistoryResponse;
import io.evitadb.externalApi.grpc.generated.GrpcCloseRequest;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse;
import io.evitadb.externalApi.grpc.requestResponse.cdc.ChangeCaptureConverter;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.VersionUtils.SemVer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import static io.evitadb.externalApi.FulltextMutationFunctionalTestSupport.*;
import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
	@MethodSource("io.evitadb.externalApi.FulltextMutationFunctionalTestSupport#cases")
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
}
