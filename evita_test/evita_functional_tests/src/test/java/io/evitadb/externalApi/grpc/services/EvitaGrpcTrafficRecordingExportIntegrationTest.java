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
import java.util.Iterator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.TRAFFIC_ENGINE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
		// startRecording only submits an asynchronous TrafficRecorderTask to the scheduler and returns
		// immediately; the rich recorder is not swapped in until that task runs. Wait until recording is
		// genuinely active before generating traffic, otherwise the queries below race ahead of activation,
		// get handled by the no-op recorder, and the exported zip comes back empty (totalRecordCount == 0).
		final TrafficRecordingEngine recordingEngine =
			((Catalog) evita.getCatalogInstance(TEST_CATALOG).orElseThrow()).getTrafficRecordingEngine();
		final long recordingActivationStart = System.currentTimeMillis();
		while (!recordingEngine.isRecordingActive()
			&& System.currentTimeMillis() - recordingActivationStart < 30_000L) {
			try {
				Thread.sleep(20L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for traffic recording to activate", e);
			}
		}
		assertTrue(recordingEngine.isRecordingActive(), "Traffic recording did not activate within 30s");
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

		final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = clientBuilder.build(EvitaServiceGrpc.EvitaServiceBlockingStub.class);
		final GrpcEvitaTrafficRecordingServiceGrpc.GrpcEvitaTrafficRecordingServiceBlockingStub trafficStub =
			clientBuilder.build(GrpcEvitaTrafficRecordingServiceGrpc.GrpcEvitaTrafficRecordingServiceBlockingStub.class);
		final EvitaManagementServiceGrpc.EvitaManagementServiceBlockingStub managementStub =
			clientBuilder.build(EvitaManagementServiceGrpc.EvitaManagementServiceBlockingStub.class);

		final GrpcEvitaSessionResponse sessionResponse = evitaBlockingStub.createReadWriteSession(
			GrpcEvitaSessionRequest.newBuilder().setCatalogName(TEST_CATALOG).build()
		);
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

		int totalRecordCount = 0;
		boolean metadataPresent = false;
		final byte[] buffer = new byte[4_096];
		try (final ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(fileContent.toByteArray()))) {
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
								totalRecordCount += recordings.toList().size();
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

		assertTrue(metadataPresent, "Exported zip must contain a metadata.txt entry");
		assertTrue(totalRecordCount > 0, "Exported zip must contain at least one traffic recording record");
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
