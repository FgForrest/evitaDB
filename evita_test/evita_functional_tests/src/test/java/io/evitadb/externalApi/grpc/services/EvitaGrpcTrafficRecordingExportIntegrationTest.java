/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

import com.google.protobuf.StringValue;
import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecording;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingWithLabels;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.session.EvitaInternalSessionContract;
import io.evitadb.core.traffic.TrafficRecordingEngine;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.driver.interceptor.ClientSessionInterceptor;
import io.evitadb.driver.interceptor.ClientSessionInterceptor.SessionIdHolder;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.grpc.TestGrpcClientBuilderCreator;
import io.evitadb.externalApi.grpc.generated.*;
import io.evitadb.externalApi.grpc.testUtils.TestDataProvider;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.server.EvitaServer;
import io.evitadb.store.traffic.InputStreamTrafficRecordReader;
import io.evitadb.stream.AbstractRandomAccessInputStream;
import io.evitadb.stream.RandomAccessFileInputStream;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.OnDataSetTearDown;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.IOUtils;
import io.evitadb.utils.VersionUtils.SemVer;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.externalApi.grpc.query.QueryConverter.convertQueryParam;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.TRAFFIC_ENGINE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Thin gRPC round-trip test proving the on-demand traffic recording export RPC wiring end to end
 * (issue #1282): {@code ExportTrafficRecording} -> poll {@code GetTaskStatus} ->
 * {@code ListFilesToFetch} -> {@code FetchFile}, with every exported {@code .bin} entry round-tripping
 * through {@link InputStreamTrafficRecordReader}. The traffic recorder is activated and the traffic
 * that ends up in the export is generated directly against {@link Evita} (server-side) rather than via
 * gRPC - the RPC surface under test here is the export/download flow itself, not session-scoped
 * query recording (already covered by {@code EvitaOnDemandTrafficRecordingTest}).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Evita gRPC on-demand traffic recording export integration test")
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(TRAFFIC_ENGINE)
public class EvitaGrpcTrafficRecordingExportIntegrationTest {
	private static final String GRPC_TRAFFIC_EXPORT_DATASET = "GrpcTrafficRecordingExportIntegrationTest";
	private static final String GRPC_STOP_RECORDING_DATASET = "GrpcTrafficRecordingStopIntegrationTest";
	private static final String GRPC_CLIENT_LABEL_DATASET = "GrpcTrafficRecordingClientLabelIntegrationTest";
	/**
	 * Header carrying a client label, and the label it carries. These are the shipped defaults of
	 * `api.headers.label` - the test asserts the stock configuration works, not a bespoke one.
	 */
	private static final String LABEL_HEADER = "X-EvitaDB-Label";
	private static final String LABEL_NAME = "tenant";
	private static final String LABEL_VALUE = "acme";

	@DataSet(value = GRPC_TRAFFIC_EXPORT_DATASET, openWebApi = {GrpcProvider.CODE, SystemProvider.CODE}, readOnly = false, destroyAfterClass = true)
	GrpcClientBuilder setUp(Evita evita, EvitaServer evitaServer) {
		new TestDataProvider().generateEntities(evita, 5);
		return TestGrpcClientBuilderCreator.getBuilder(
			new ClientSessionInterceptor(
				EvitaClientConfiguration.builder().build().clientId(),
				new SemVer(2025, 4)
			),
			evitaServer.getExternalApiServer()
		);
	}

	/**
	 * Dedicated (unshared) dataset for the regression test below - {@code startRecording}/
	 * {@code stopTrafficRecording} mutate catalog-wide singleton state (the {@code recordingActive}
	 * guard), so this test must not share an {@link Evita} instance with any other test method.
	 */
	@DataSet(value = GRPC_STOP_RECORDING_DATASET, openWebApi = {GrpcProvider.CODE, SystemProvider.CODE}, readOnly = false, destroyAfterClass = true)
	GrpcClientBuilder setUpForStopRecording(Evita evita, EvitaServer evitaServer) {
		return TestGrpcClientBuilderCreator.getBuilder(
			new ClientSessionInterceptor(
				EvitaClientConfiguration.builder().build().clientId(),
				new SemVer(2025, 4)
			),
			evitaServer.getExternalApiServer()
		);
	}

	/**
	 * Dedicated (unshared) dataset for the client-label test below - like the stop-recording test it
	 * activates the recorder, which is catalog-wide singleton state.
	 */
	@DataSet(value = GRPC_CLIENT_LABEL_DATASET, openWebApi = {GrpcProvider.CODE, SystemProvider.CODE}, readOnly = false, destroyAfterClass = true)
	GrpcClientBuilder setUpForClientLabel(Evita evita, EvitaServer evitaServer) {
		new TestDataProvider().generateEntities(evita, 5);
		return TestGrpcClientBuilderCreator.getBuilder(
			new ClientSessionInterceptor(
				EvitaClientConfiguration.builder().build().clientId(),
				new SemVer(2025, 4)
			),
			evitaServer.getExternalApiServer()
		);
	}

	@AfterEach
	public void afterEach() {
		SessionIdHolder.reset();
	}

	@OnDataSetTearDown(GRPC_TRAFFIC_EXPORT_DATASET)
	void onDataSetTearDown(GrpcClientBuilder clientBuilder) {

	}

	@OnDataSetTearDown(GRPC_STOP_RECORDING_DATASET)
	void onStopRecordingDataSetTearDown(GrpcClientBuilder clientBuilder) {

	}

	@OnDataSetTearDown(GRPC_CLIENT_LABEL_DATASET)
	void onClientLabelDataSetTearDown(GrpcClientBuilder clientBuilder) {

	}

	@Test
	@UseDataSet(GRPC_TRAFFIC_EXPORT_DATASET)
	@DisplayName("Should export the on-demand traffic recording over gRPC and fetch the resulting zip archive")
	void shouldExportTrafficRecordingOverGrpcAndFetchTheResultingZip(GrpcClientBuilder clientBuilder, Evita evita) throws IOException {
		// activate a real recorder and generate a bit of traffic server-side - this test proves the
		// export/download RPC wiring (gate #1), not the session-scoped query recording itself
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				((EvitaInternalSessionContract) session).startRecording(100, false, null, null, 16_000L);
			}
		);
		awaitRecordingActive(evita);
		for (int i = 0; i < 5; i++) {
			final int primaryKey = i + 1;
			evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					session.query(
						query(
							collection(Entities.PRODUCT),
							filterBy(entityPrimaryKeyInSet(primaryKey)),
							require(entityFetchAll())
						),
						SealedEntity.class
					);
				}
			);
		}

		final ExportedArchive exported = readArchive(exportTrafficRecordingArchive(clientBuilder));

		assertTrue(exported.metadataPresent(), "Exported zip must contain a metadata.txt entry");
		assertFalse(
			exported.recordings().isEmpty(),
			"Exported zip must contain at least one traffic recording record"
		);
	}

	@Test
	@UseDataSet(GRPC_STOP_RECORDING_DATASET)
	@DisplayName("Should send exactly one response when stopping traffic recording over gRPC (regression)")
	void shouldSendExactlyOneResponseWhenStoppingTrafficRecordingOverGrpc(GrpcClientBuilder clientBuilder) {
		final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = clientBuilder.build(EvitaServiceGrpc.EvitaServiceBlockingStub.class);
		final GrpcEvitaTrafficRecordingServiceGrpc.GrpcEvitaTrafficRecordingServiceBlockingStub trafficStub =
			clientBuilder.build(GrpcEvitaTrafficRecordingServiceGrpc.GrpcEvitaTrafficRecordingServiceBlockingStub.class);

		final GrpcEvitaSessionResponse sessionResponse = evitaBlockingStub.createReadWriteSession(
			GrpcEvitaSessionRequest.newBuilder().setCatalogName(TEST_CATALOG).build()
		);
		SessionIdHolder.setSessionId(sessionResponse.getSessionId());
		try {
			final GetTrafficRecordingStatusResponse startResponse = trafficStub.startTrafficRecording(
				GrpcStartTrafficRecordingRequest.newBuilder()
					.setSamplingRate(100)
					.setExportFile(false)
					.build()
			);
			final GrpcUuid taskId = startResponse.getTaskStatus().getTaskId();

			// a unary blocking-stub call throws (client-side) if the server sends more than one response
			// message before completing - exactly what regresses if `stopTrafficRecording` ever calls
			// `onNext` twice again
			final GetTrafficRecordingStatusResponse stopResponse = assertDoesNotThrow(
				() -> trafficStub.stopTrafficRecording(
					GrpcStopTrafficRecordingRequest.newBuilder().setTaskStatusId(taskId).build()
				),
				"stopTrafficRecording must send exactly one response message"
			);
			assertNotNull(stopResponse.getTaskStatus());
		} finally {
			clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class)
				.close(GrpcCloseRequest.newBuilder().setCatalogName(TEST_CATALOG).build());
			SessionIdHolder.reset();
		}
	}

	@Test
	@UseDataSet(GRPC_CLIENT_LABEL_DATASET)
	@DisplayName("Should carry a client label sent as a gRPC header all the way into the traffic recording")
	void shouldRecordClientLabelSentAsGrpcHeader(GrpcClientBuilder clientBuilder, Evita evita) throws IOException {
		// this is the whole chain in one test: a header on the wire -> ObservabilityInterceptor ->
		// GrpcTracingContext -> the client-label thread local -> TrafficRecordingEngine#collectSystemLabels
		// -> the recorded query. Every hop is unit-tested on its own; nothing but this proves they are joined up
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				((EvitaInternalSessionContract) session).startRecording(100, false, null, null, 16_000L);
			}
		);
		awaitRecordingActive(evita);

		// the label is attached to the query call alone, never to the whole client. Putting it on the shared
		// builder would also stamp it on the export session below, and the assertion would then pass on the
		// exporter's own traffic without the query path having propagated anything
		final Metadata labelHeader = new Metadata();
		labelHeader.put(
			Metadata.Key.of(LABEL_HEADER, Metadata.ASCII_STRING_MARSHALLER),
			LABEL_NAME + "=" + LABEL_VALUE
		);

		final GrpcEvitaSessionResponse sessionResponse = clientBuilder
			.build(EvitaServiceGrpc.EvitaServiceBlockingStub.class)
			.createReadOnlySession(GrpcEvitaSessionRequest.newBuilder().setCatalogName(TEST_CATALOG).build());
		SessionIdHolder.setSessionId(sessionResponse.getSessionId());
		try {
			clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class)
				.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(labelHeader))
				.queryOne(
					GrpcQueryRequest.newBuilder()
						// the query API runs in SAFE mode, which refuses inlined literals outright
						.setQuery("query(collection(?), filterBy(entityPrimaryKeyInSet(?)))")
						.addAllPositionalQueryParams(
							List.of(convertQueryParam(Entities.PRODUCT), convertQueryParam(1))
						)
						.build()
				);
		} finally {
			// the recorder flushes whole sessions, so nothing this session did is readable until it closes
			clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class)
				.close(GrpcCloseRequest.newBuilder().setCatalogName(TEST_CATALOG).build());
			SessionIdHolder.reset();
		}

		// the export drains every closed session to disk before reading, which is what makes the query above
		// visible at all - a plain `getRecordings` triggers no drain and legitimately sees nothing yet
		final List<String> recordedLabels = readArchive(exportTrafficRecordingArchive(clientBuilder))
			.recordings()
			.stream()
			.filter(TrafficRecordingWithLabels.class::isInstance)
			.flatMap(recording -> Arrays.stream(((TrafficRecordingWithLabels) recording).labels()))
			.map(label -> label.name() + "=" + label.value())
			.distinct()
			.toList();

		assertTrue(
			recordedLabels.contains(LABEL_NAME + "=" + LABEL_VALUE),
			"The `" + LABEL_HEADER + ": " + LABEL_NAME + "=" + LABEL_VALUE + "` header the gRPC caller sent " +
				"never reached the traffic recording. Labels actually recorded: " + recordedLabels
		);
	}

	/**
	 * Starts blocking until the on-demand recorder is genuinely recording.
	 *
	 * {@code startRecording} only submits an asynchronous {@code TrafficRecorderTask} to the scheduler and returns
	 * immediately; the rich recorder is not swapped in until that task runs. Traffic generated before then is
	 * handled by the no-op recorder and is silently absent from everything downstream - which reads as a failing
	 * assertion about the feature under test rather than as the race it is.
	 *
	 * @param evita the server whose test catalog is being recorded
	 */
	private static void awaitRecordingActive(@Nonnull Evita evita) {
		final TrafficRecordingEngine recordingEngine =
			((Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow()).getTrafficRecordingEngine();
		final long start = System.currentTimeMillis();
		while (!recordingEngine.isRecordingActive() && System.currentTimeMillis() - start < 30_000L) {
			sleepBriefly("waiting for traffic recording to activate");
		}
		assertTrue(recordingEngine.isRecordingActive(), "Traffic recording did not activate within 30s");
	}

	/**
	 * Drives the on-demand export RPC flow end to end and returns the zip archive it produced.
	 *
	 * Reading traffic through the export rather than through {@code getRecordings} is deliberate: the exporter
	 * synchronously drains every closed session from the off-heap buffer to disk before it walks it, so traffic
	 * generated moments earlier is guaranteed to be included. A direct read triggers no drain and will simply
	 * come back empty until a background flush happens to run.
	 *
	 * @param clientBuilder builder for the gRPC stubs, pointed at the test server
	 * @return the raw bytes of the exported zip archive
	 */
	@Nonnull
	private static byte[] exportTrafficRecordingArchive(@Nonnull GrpcClientBuilder clientBuilder) {
		final GrpcEvitaTrafficRecordingServiceGrpc.GrpcEvitaTrafficRecordingServiceBlockingStub trafficStub =
			clientBuilder.build(GrpcEvitaTrafficRecordingServiceGrpc.GrpcEvitaTrafficRecordingServiceBlockingStub.class);
		final EvitaManagementServiceGrpc.EvitaManagementServiceBlockingStub managementStub =
			clientBuilder.build(EvitaManagementServiceGrpc.EvitaManagementServiceBlockingStub.class);

		final GrpcEvitaSessionResponse sessionResponse = clientBuilder
			.build(EvitaServiceGrpc.EvitaServiceBlockingStub.class)
			.createReadWriteSession(GrpcEvitaSessionRequest.newBuilder().setCatalogName(TEST_CATALOG).build());
		SessionIdHolder.setSessionId(sessionResponse.getSessionId());

		final GetTrafficRecordingStatusResponse exportResponse = trafficStub.exportTrafficRecording(
			GrpcExportTrafficRecordingRequest.newBuilder().build()
		);
		final GrpcUuid taskId = exportResponse.getTaskStatus().getTaskId();

		// the export task runs independently of the session that submitted it - close the session
		// right away, matching how a client would in practice, since GetTaskStatus/ListFilesToFetch/
		// FetchFile are session-agnostic management operations
		clientBuilder.build(EvitaSessionServiceGrpc.EvitaSessionServiceBlockingStub.class)
			.close(GrpcCloseRequest.newBuilder().setCatalogName(TEST_CATALOG).build());
		SessionIdHolder.reset();

		final GrpcTaskStatus taskStatus = pollUntilFinished(managementStub, taskId);
		assertEquals(
			GrpcTaskSimplifiedState.TASK_FINISHED, taskStatus.getSimplifiedState(),
			"Export task must finish successfully: " + taskStatus
		);
		assertTrue(taskStatus.hasFile(), "Finished export task must carry a file result: " + taskStatus);

		final GrpcFilesToFetchResponse filesToFetchResponse = managementStub.listFilesToFetch(
			GrpcFilesToFetchRequest.newBuilder()
				.setPageNumber(1)
				.setPageSize(50)
				.addOrigin(StringValue.newBuilder().setValue("TrafficRecordingExportTask").build())
				.build()
		);
		final GrpcFile exportedFile = filesToFetchResponse.getFilesToFetchList().stream()
			.filter(file -> file.getFileId().equals(taskStatus.getFile().getFileId()))
			.findFirst()
			.orElseGet(() -> fail("Exported file not present in ListFilesToFetch response: " + filesToFetchResponse));

		final ByteArrayOutputStream fileContent = new ByteArrayOutputStream();
		final Iterator<GrpcFetchFileResponse> fetchIterator = managementStub.fetchFile(
			GrpcFetchFileRequest.newBuilder().setFileId(exportedFile.getFileId()).build()
		);
		fetchIterator.forEachRemaining(chunk -> fileContent.writeBytes(chunk.getFileContents().toByteArray()));
		return fileContent.toByteArray();
	}

	/**
	 * Unpacks an exported archive, round-tripping every {@code .bin} entry back through
	 * {@link InputStreamTrafficRecordReader}.
	 *
	 * @param archive the raw bytes of an exported zip archive
	 * @return every recording the archive carries, and whether it declared its metadata entry
	 * @throws IOException when the archive cannot be unpacked or a record file cannot be read
	 */
	@Nonnull
	private static ExportedArchive readArchive(@Nonnull byte[] archive) throws IOException {
		final List<TrafficRecording> allRecordings = new ArrayList<>(64);
		boolean metadataPresent = false;
		final byte[] buffer = new byte[4_096];
		try (final ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(archive))) {
			ZipEntry entry;
			while ((entry = zipInputStream.getNextEntry()) != null) {
				if (entry.getName().endsWith(".bin")) {
					final Path tempFile = Files.createTempFile("evitaGrpcTrafficRecordingExportTest", entry.getName());
					try {
						try (final OutputStream outputStream = new BufferedOutputStream(Files.newOutputStream(tempFile, StandardOpenOption.TRUNCATE_EXISTING), 4_096)) {
							IOUtils.copy(zipInputStream, outputStream, buffer);
						}
						try (
							final AbstractRandomAccessInputStream tempInputStream = new RandomAccessFileInputStream(new RandomAccessFile(tempFile.toFile(), "r"));
							final InputStreamTrafficRecordReader reader = new InputStreamTrafficRecordReader(tempInputStream)
						) {
							try (
								final Stream<TrafficRecording> recordings = reader.getRecordings(
									TrafficRecordingCaptureRequest.builder().build()
								)
							) {
								allRecordings.addAll(recordings.toList());
							}
						}
					} finally {
						Files.deleteIfExists(tempFile);
					}
				} else if (entry.getName().equals("metadata.txt")) {
					metadataPresent = true;
				}
				zipInputStream.closeEntry();
			}
		}
		return new ExportedArchive(allRecordings, metadataPresent);
	}

	/**
	 * What an exported traffic-recording archive was found to contain.
	 *
	 * @param recordings      every recording read out of the archive's `.bin` entries
	 * @param metadataPresent whether the archive declared its `metadata.txt` entry
	 */
	private record ExportedArchive(
		@Nonnull List<TrafficRecording> recordings,
		boolean metadataPresent
	) {
	}

	/**
	 * Sleeps for a poll interval, converting an interruption into a failure of the test rather than a silent
	 * early exit from the surrounding loop.
	 *
	 * @param what what the caller was waiting for, for the failure message
	 */
	private static void sleepBriefly(@Nonnull String what) {
		try {
			Thread.sleep(20L);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while " + what, e);
		}
	}

	@Nonnull
	private static GrpcTaskStatus pollUntilFinished(
		@Nonnull EvitaManagementServiceGrpc.EvitaManagementServiceBlockingStub managementStub,
		@Nonnull GrpcUuid taskId
	) {
		for (int attempt = 0; attempt < 100; attempt++) {
			final GrpcTaskStatusResponse response = managementStub.getTaskStatus(
				GrpcTaskStatusRequest.newBuilder().setTaskId(taskId).build()
			);
			if (response.hasTaskStatus()) {
				final GrpcTaskStatus status = response.getTaskStatus();
				if (status.getSimplifiedState() == GrpcTaskSimplifiedState.TASK_FINISHED ||
					status.getSimplifiedState() == GrpcTaskSimplifiedState.TASK_FAILED) {
					return status;
				}
			}
			try {
				Thread.sleep(100L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for the export task to finish.", e);
			}
		}
		throw new AssertionError("Export task did not finish within the expected time.");
	}

}
