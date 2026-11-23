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

package io.evitadb.externalApi.grpc.services;

import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.TrafficRecordingOptions;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.requestResponse.trafficRecording.SourceQueryContainer;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecording;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest;
import io.evitadb.core.Evita;
import io.evitadb.core.session.EvitaInternalSessionContract;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.driver.interceptor.ClientSessionInterceptor;
import io.evitadb.driver.interceptor.ClientSessionInterceptor.SessionIdHolder;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.grpc.TestGrpcClientBuilderCreator;
import io.evitadb.externalApi.grpc.generated.EvitaServiceGrpc;
import io.evitadb.externalApi.grpc.generated.EvitaSessionServiceGrpc;
import io.evitadb.externalApi.grpc.generated.GrpcCloseRequest;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionRequest;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaSessionResponse;
import io.evitadb.externalApi.grpc.generated.GrpcQueryParam;
import io.evitadb.externalApi.grpc.generated.GrpcQueryRequest;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.server.EvitaServer;
import io.evitadb.store.traffic.InputStreamTrafficRecordReader;
import io.evitadb.stream.AbstractRandomAccessInputStream;
import io.evitadb.stream.RandomAccessFileInputStream;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.FileUtils;
import io.evitadb.utils.IOUtils;
import io.evitadb.utils.UUIDUtil;
import io.evitadb.utils.VersionUtils.SemVer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.annotation.Nonnull;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.evitadb.externalApi.grpc.query.QueryConverter.convertQueryParam;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.TRAFFIC_ENGINE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the text the gRPC session service records as the source query of a request: the query string, then every
 * parameter value the request carried, one per line.
 *
 * Source query tracking is read from the server configuration when the service is created and no shared data set
 * enables it, so this class runs its own engine and gRPC server with ambient traffic recording turned on. The
 * recorded text is read back through the traffic recording export, which drains every closed session to disk before
 * it reads, so a session closed a moment earlier is guaranteed to be in it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("gRPC source query tracking")
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(TRAFFIC_ENGINE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EvitaSessionServiceSourceQueryTrackingTest implements EvitaTestSupport {
	/**
	 * Owner of the allocated ports and label of the test directories.
	 */
	private static final String DATA_SET_NAME = "grpcSourceQueryTrackingTest";
	/**
	 * The only entity collection of the test catalog - the queries need an existing collection to run.
	 */
	private static final String ENTITY_TYPE = "Product";
	/**
	 * Size of the chunk files inside the exported archive.
	 */
	private static final long EXPORT_CHUNK_SIZE = 16_000L;

	/**
	 * Isolated engine directories.
	 */
	private TestPaths paths;
	/**
	 * Engine with ambient traffic recording and source query tracking enabled.
	 */
	private Evita evita;
	/**
	 * Server exposing the gRPC API of {@link #evita}.
	 */
	private EvitaServer evitaServer;
	/**
	 * Builder of the gRPC stubs pointed at {@link #evitaServer}.
	 */
	private GrpcClientBuilder clientBuilder;

	/**
	 * Starts the engine with source query tracking enabled, makes the test catalog alive - the ambient recorder only
	 * records sessions of an alive catalog - and opens the gRPC API over it.
	 */
	@BeforeAll
	void setUp() {
		this.paths = createTestPaths(DATA_SET_NAME);
		this.evita = new Evita(
			newTestEvitaConfigurationBuilder(this.paths)
				.server(
					ServerOptions.builder()
						.trafficRecording(
							TrafficRecordingOptions.builder()
								.enabled(true)
								.sourceQueryTracking(true)
								.build()
						)
						.build()
				)
				.build()
		);
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_TYPE).updateVia(session);
				session.goLiveAndClose();
			}
		);
		this.evitaServer = new EvitaServer(
			this.evita,
			EvitaParameterResolver.createApiOptions(
				DATA_SET_NAME, this.evita, getPortManager(), GrpcProvider.CODE, SystemProvider.CODE
			)
		);
		this.evitaServer.run();
		this.clientBuilder = TestGrpcClientBuilderCreator.getBuilder(
			new ClientSessionInterceptor(
				EvitaClientConfiguration.builder().build().clientId(),
				new SemVer(2025, 4)
			),
			this.evitaServer.getExternalApiServer()
		);
	}

	/**
	 * Stops the server and the engine, then releases the ports and the directories.
	 */
	@AfterAll
	void tearDown() throws Exception {
		if (this.evitaServer != null) {
			final CompletableFuture<Void> stopped = this.evitaServer.stop();
			getPortManager().releasePortsOnCompletion(DATA_SET_NAME, stopped);
			stopped.get(30, TimeUnit.SECONDS);
		}
		if (this.evita != null) {
			this.evita.close();
		}
		if (this.paths != null) {
			// the API options place the server certificates next to the storage directory
			FileUtils.deleteDirectory(Path.of(this.paths.storage() + "-certificates"));
			cleanupTestPaths(this.paths);
		}
	}

	@Test
	@DisplayName("Should record both positional and named parameters when the request carries both kinds")
	void shouldRecordPositionalAndNamedParametersWhenRequestCarriesBoth() throws Exception {
		final String query = "query(collection(@collection), filterBy(entityPrimaryKeyInSet(?, ?)))";
		assertEquals(
			query + "\nParam 1: 1\nParam 2: 2\ncollection: 'Product'\n",
			recordSourceQuery(
				query,
				List.of(convertQueryParam(1), convertQueryParam(2)),
				Map.of("collection", convertQueryParam(ENTITY_TYPE))
			)
		);
	}

	@Test
	@DisplayName("Should separate named parameters from the query text")
	void shouldSeparateNamedParametersFromQueryText() throws Exception {
		final String query = "query(collection(@collection))";
		assertEquals(
			query + "\ncollection: 'Product'\n",
			recordSourceQuery(query, List.of(), Map.of("collection", convertQueryParam(ENTITY_TYPE)))
		);
	}

	@Test
	@DisplayName("Should list positional parameters in their order after the query text")
	void shouldListPositionalParametersInOrderAfterQueryText() throws Exception {
		final String query = "query(collection(?), filterBy(entityPrimaryKeyInSet(?)))";
		assertEquals(
			query + "\nParam 1: 'Product'\nParam 2: 1\n",
			recordSourceQuery(
				query,
				List.of(convertQueryParam(ENTITY_TYPE), convertQueryParam(1)),
				Map.of()
			)
		);
	}

	/**
	 * Sends the query over gRPC in a session of its own, closes that session and returns the source query text the
	 * traffic recording captured for it.
	 *
	 * @param query                 the query string with parameter placeholders
	 * @param positionalQueryParams the values of the positional placeholders, in order
	 * @param namedQueryParams      the values of the named placeholders, keyed by their names
	 * @return the source query text recorded for the session
	 * @throws Exception when the export fails or cannot be read
	 */
	@Nonnull
	private String recordSourceQuery(
		@Nonnull String query,
		@Nonnull List<GrpcQueryParam> positionalQueryParams,
		@Nonnull Map<String, GrpcQueryParam> namedQueryParams
	) throws Exception {
		final GrpcEvitaSessionResponse sessionResponse = this.clientBuilder
			.build(EvitaServiceGrpc.EvitaServiceBlockingStub.class)
			.createReadOnlySession(GrpcEvitaSessionRequest.newBuilder().setCatalogName(TEST_CATALOG).build());
		final UUID sessionId = UUIDUtil.uuid(sessionResponse.getSessionId());
		final EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub sessionStub = this.clientBuilder
			.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class);
		SessionIdHolder.setSessionId(sessionResponse.getSessionId());
		try {
			sessionStub.queryList(
				GrpcQueryRequest.newBuilder()
					.setQuery(query)
					.addAllPositionalQueryParams(positionalQueryParams)
					.putAllNamedQueryParams(namedQueryParams)
					.build()
			);
		} finally {
			// the recorder publishes the traffic of a session only once the session closes
			sessionStub.close(GrpcCloseRequest.newBuilder().setCatalogName(TEST_CATALOG).build());
			SessionIdHolder.reset();
		}

		final List<String> recordedSourceQueries = exportRecordings()
			.stream()
			.filter(SourceQueryContainer.class::isInstance)
			.map(SourceQueryContainer.class::cast)
			.filter(container -> container.sessionId().equals(sessionId))
			.map(SourceQueryContainer::sourceQuery)
			.toList();
		assertEquals(
			1, recordedSourceQueries.size(),
			"Exactly one source query must be recorded for the session, recorded: " + recordedSourceQueries
		);
		return recordedSourceQueries.get(0);
	}

	/**
	 * Exports the traffic recording of the test catalog and reads every record of the exported archive.
	 *
	 * @return every recording the exported archive carries
	 * @throws Exception when the export fails or cannot be read
	 */
	@Nonnull
	private List<TrafficRecording> exportRecordings() throws Exception {
		final FileForFetch exportedFile = this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					return ((EvitaInternalSessionContract) session).exportTrafficRecording(EXPORT_CHUNK_SIZE);
				}
			)
			.getFutureResult()
			.get(30, TimeUnit.SECONDS);

		final List<TrafficRecording> recordings = new ArrayList<>(64);
		try (
			final InputStream archive = this.evita.management().fetchFile(exportedFile.fileId());
			final ZipInputStream zipInputStream = new ZipInputStream(archive)
		) {
			ZipEntry entry;
			while ((entry = zipInputStream.getNextEntry()) != null) {
				if (entry.getName().endsWith(".bin")) {
					recordings.addAll(readRecordFile(zipInputStream));
				}
				zipInputStream.closeEntry();
			}
		}
		return recordings;
	}

	/**
	 * Reads one record file of an exported archive. The reader needs random access, so the entry is copied to a
	 * temporary file first.
	 *
	 * @param zipInputStream the archive positioned at the start of a record file entry
	 * @return every recording the record file carries
	 * @throws IOException when the entry cannot be copied or read
	 */
	@Nonnull
	private static List<TrafficRecording> readRecordFile(@Nonnull ZipInputStream zipInputStream) throws IOException {
		final Path tempFile = Files.createTempFile("evitaGrpcSourceQueryTrackingTest", ".bin");
		try {
			try (final OutputStream outputStream = new BufferedOutputStream(Files.newOutputStream(tempFile), 4_096)) {
				IOUtils.copy(zipInputStream, outputStream, new byte[4_096]);
			}
			try (
				final AbstractRandomAccessInputStream inputStream = new RandomAccessFileInputStream(
					new RandomAccessFile(tempFile.toFile(), "r")
				);
				final InputStreamTrafficRecordReader reader = new InputStreamTrafficRecordReader(inputStream);
				final Stream<TrafficRecording> recordings = reader.getRecordings(
					TrafficRecordingCaptureRequest.builder().build()
				)
			) {
				return recordings.toList();
			}
		} finally {
			Files.deleteIfExists(tempFile);
		}
	}

}
