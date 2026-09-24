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

package io.evitadb.externalApi.grpc;

import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.core.Evita;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.driver.interceptor.ClientSessionInterceptor;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.EvitaManagementServiceGrpc.EvitaManagementServiceStub;
import io.evitadb.externalApi.grpc.generated.GrpcFetchFileRequest;
import io.evitadb.externalApi.grpc.generated.GrpcFetchFileResponse;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.server.EvitaServer;
import io.evitadb.spi.export.model.ExportFileHandle;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.VersionUtils.SemVer;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;

import static io.evitadb.test.TestTags.EXPORT;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SLOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that a **progressing** download survives a server request deadline far shorter than the
 * transfer takes - i.e. that the deadline bounds *silence* rather than the length of the exchange.
 *
 * This is the invariant `GrpcOutboundGate` re-arms for, and it only became load-bearing once the gate
 * existed. Before it, `fetchFile` pushed the whole file into Armeria's unbounded outbound queue at disk
 * speed; afterwards the handler lives exactly as long as the *client* takes to consume, so any fixed
 * request budget becomes a cap on file size divided by link speed. A 1-2 s budget is not a contrived
 * setting either - it is what a client sending no `grpc-timeout` at all actually gets, because
 * Armeria's `FramedGrpcService` overrides the context timeout **only** when the header is present and
 * otherwise leaves `api.requestTimeoutInMillis` (1 s code default, 2 s shipped) in force. A browser
 * over gRPC-Web is therefore the *most* exposed client, not the least.
 *
 * **Why the test is shaped the way it is.** Three earlier attempts failed to reach the defect at all,
 * and each trap is easy to fall back into:
 *
 * - **The deadline has to be set by the client but enforced only by the server.** `responseTimeoutMillis(0)`
 *   stops Armeria's client from ending the call on its own - but it also stops it emitting the
 *   `grpc-timeout` header, so the server silently falls back to its configured budget (10 minutes under
 *   the test harness) and nothing can ever expire. The header is therefore injected by hand, through a
 *   {@link ClientInterceptor}, which sets it without arming any client-side clock.
 * - **Size alone does not make a client slow.** At 8 MB the server produced every chunk in ~6 ms: the
 *   handler had finished before any deadline could bite, and a "slow" consumer throttled nothing. What
 *   paces the handler is the gate, and what paces the gate is *demand* - so the client here takes manual
 *   control of flow control and drip-feeds `request(1)`.
 * - **The producer must not be on the event loop.** See `useRealThreadPools` on the data set below.
 *
 * **Calibration.** With the re-arm removed from {@link io.evitadb.externalApi.grpc.utils.GrpcOutboundGate}
 * this test fails, the stream dying with `RST_STREAM`/`DEADLINE_EXCEEDED` at roughly
 * {@link #SERVER_REQUEST_TIMEOUT_MILLIS} - about a fifth of the way in. The measured numbers are quoted
 * in `documentation/adr/2026-08-24-grpc-streaming-backpressure-readiness-gate.md`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@ExtendWith(EvitaParameterResolver.class)
@DisplayName("gRPC fetchFile must not cap a progressing download at the request deadline")
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(MANAGEMENT)
@Tag(EXPORT)
@Tag(SLOW)
public class LongRunningGrpcFetchFileDeadlineTest {
	private static final String DATA_SET = "GrpcFetchFileDeadline";
	/**
	 * Chunk size the server streams with - mirrors `EvitaManagementService.FETCH_FILE_CHUNK_SIZE`.
	 */
	private static final int CHUNK_SIZE = 1_048_576;
	/**
	 * Size of the downloaded file. Chosen together with {@link #CONSUMER_PACING_MILLIS} so the transfer
	 * takes several times {@link #SERVER_REQUEST_TIMEOUT_MILLIS} while no single gap between messages
	 * comes anywhere near it - the two conditions the test needs to hold simultaneously.
	 */
	private static final long FILE_SIZE = 64L * 1024L * 1024L;
	/**
	 * Delay the client inserts before asking for each subsequent message. This paces the *server*,
	 * because the gate advances only on demand. It is not a poll and cannot fail the test spuriously:
	 * a loaded machine stretches the transfer, which only widens the margin the test asserts.
	 */
	private static final long CONSUMER_PACING_MILLIS = 100L;
	/**
	 * Request deadline the client asks the server to enforce, in milliseconds. Deliberately far below
	 * the transfer's duration and far above one message's worth of pacing.
	 */
	private static final long SERVER_REQUEST_TIMEOUT_MILLIS = 2_000L;
	/**
	 * Upper bound on the whole download. Generous by design - it exists to fail a hang, not to time the
	 * transfer, and a passing run returns as soon as the stream completes.
	 */
	private static final long COMPLETION_TIMEOUT_SECONDS = 120L;

	/**
	 * gRPC's own header carrying the caller's deadline. Injected by hand rather than by
	 * `withDeadlineAfter`, so that the deadline exists on the server and nowhere else.
	 */
	private static final Metadata.Key<String> GRPC_TIMEOUT = Metadata.Key.of(
		"grpc-timeout", Metadata.ASCII_STRING_MARSHALLER
	);

	/**
	 * Sets `grpc-timeout` on every outgoing call without arming any client-side clock.
	 *
	 * The point of the separation is that the test must observe the *server* abandoning a healthy
	 * transfer. Had the deadline been set through `CallOptions.deadline`, gRPC would enforce it locally
	 * too and a failure would prove nothing about which side gave up.
	 */
	private static final ClientInterceptor SERVER_ONLY_DEADLINE = new ClientInterceptor() {

		@Override
		public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
			MethodDescriptor<ReqT, RespT> method,
			CallOptions callOptions,
			Channel next
		) {
			return new SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {

				@Override
				public void start(Listener<RespT> responseListener, Metadata headers) {
					// `m` is the gRPC wire unit for milliseconds
					headers.put(GRPC_TIMEOUT, SERVER_REQUEST_TIMEOUT_MILLIS + "m");
					super.start(responseListener, headers);
				}
			};
		}
	};

	/**
	 * Creates a file of the requested size and publishes it for fetching.
	 *
	 * @param evita       engine whose export service the file is stored in
	 * @param size        size of the file in bytes
	 * @param contentsCrc accumulator the written contents are checksummed into
	 * @return descriptor of the published file
	 */
	@Nonnull
	private static FileForFetch createExportedFile(
		@Nonnull Evita evita,
		long size,
		@Nonnull CRC32 contentsCrc
	) throws Exception {
		final byte[] pattern = new byte[CHUNK_SIZE];
		for (int i = 0; i < pattern.length; i++) {
			pattern[i] = (byte) (i * 31 + 7);
		}
		final ExportFileHandle handle = evita.management().exportService().storeFile(
			"grpc-fetch-file-deadline.bin",
			"Large file used by the fetchFile deadline test",
			"application/octet-stream",
			"test"
		);
		try {
			final OutputStream outputStream = handle.outputStream();
			long written = 0L;
			while (written < size) {
				final int toWrite = (int) Math.min(pattern.length, size - written);
				outputStream.write(pattern, 0, toWrite);
				contentsCrc.update(pattern, 0, toWrite);
				written += toWrite;
			}
		} finally {
			handle.close();
		}
		final FileForFetch fileForFetch = handle.fileForFetchFuture().get(60, TimeUnit.SECONDS);
		assertEquals(size, fileForFetch.totalSizeInBytes(), "Exported file has an unexpected size.");
		return fileForFetch;
	}

	@DataSet(
		value = DATA_SET, openWebApi = {GrpcProvider.CODE, SystemProvider.CODE},
		readOnly = false, destroyAfterClass = true,
		// Mandatory, for the same reason as the back-pressure test: the default test engine collapses
		// the request pool into a direct executor, which runs the producing loop on the transport event
		// loop. The gate detects that and deliberately runs ungated, so the handler would again outrun
		// the client and finish long before any deadline mattered.
		useRealThreadPools = true
	)
	GrpcClientBuilder setUp(EvitaServer evitaServer) {
		return TestGrpcClientBuilderCreator.getBuilder(
			new ClientSessionInterceptor(
				EvitaClientConfiguration.builder().build().clientId(),
				new SemVer(2025, 4)
			),
			evitaServer.getExternalApiServer()
		);
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName(
		"Should deliver a whole file to a slow but steady client, though the transfer far outlives " +
			"the server's request deadline"
	)
	void shouldNotEndAProgressingDownloadAtTheRequestDeadline(
		Evita evita,
		GrpcClientBuilder clientBuilder
	) throws Exception {
		final CRC32 expectedCrc = new CRC32();
		final FileForFetch bigFile = createExportedFile(evita, FILE_SIZE, expectedCrc);

		final ThreadFactory daemonFactory = runnable -> {
			final Thread thread = new Thread(runnable, "grpc-fetch-file-deadline-pacer");
			thread.setDaemon(true);
			return thread;
		};
		final ScheduledExecutorService pacer = Executors.newSingleThreadScheduledExecutor(daemonFactory);
		try {
			final EvitaManagementServiceStub managementStub = clientBuilder
				// gRPC-Web framing - the format evitaLab uses, and the client shape most exposed to this
				.serializationFormat(GrpcSerializationFormats.PROTO_WEB)
				// only the server may end this call: no client response timeout, and no response-length
				// cap that could terminate it for an unrelated reason
				.responseTimeoutMillis(0)
				.maxResponseLength(0)
				.intercept(SERVER_ONLY_DEADLINE)
				.build(EvitaManagementServiceStub.class);

			final CountDownLatch terminated = new CountDownLatch(1);
			final AtomicReference<Throwable> failure = new AtomicReference<>();
			final AtomicInteger chunksReceived = new AtomicInteger();
			final AtomicLong bytesReceived = new AtomicLong();
			final CRC32 receivedCrc = new CRC32();
			final long startNanos = System.nanoTime();

			managementStub.fetchFile(
				GrpcFetchFileRequest.newBuilder()
					.setFileId(EvitaDataTypesConverter.toGrpcUuid(bigFile.fileId()))
					.build(),
				new ClientResponseObserver<GrpcFetchFileRequest, GrpcFetchFileResponse>() {
					private ClientCallStreamObserver<GrpcFetchFileRequest> call;

					@Override
					public void beforeStart(ClientCallStreamObserver<GrpcFetchFileRequest> requestStream) {
						// one message in flight at a time, so that the pacing below governs the server
						requestStream.disableAutoRequestWithInitial(1);
						this.call = requestStream;
					}

					@Override
					public void onNext(GrpcFetchFileResponse value) {
						final byte[] contents = value.getFileContents().toByteArray();
						receivedCrc.update(contents);
						bytesReceived.addAndGet(contents.length);
						chunksReceived.incrementAndGet();
						// ask for the next message *later*, and from another thread - this callback runs
						// on the transport event loop, where sleeping would stall the very thread that
						// has to deliver what we are waiting for
						pacer.schedule(
							() -> this.call.request(1), CONSUMER_PACING_MILLIS, TimeUnit.MILLISECONDS
						);
					}

					@Override
					public void onError(Throwable t) {
						failure.set(t);
						terminated.countDown();
					}

					@Override
					public void onCompleted() {
						terminated.countDown();
					}
				}
			);

			final boolean completed = terminated.await(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			final long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

			log.info(
				"Download of {} B ended after {} ms in {} chunks ({} B received); server deadline was {} ms.",
				FILE_SIZE, elapsedMillis, chunksReceived.get(), bytesReceived.get(),
				SERVER_REQUEST_TIMEOUT_MILLIS
			);

			assertTrue(
				completed,
				"The download neither completed nor failed within " + COMPLETION_TIMEOUT_SECONDS +
					" s - it received " + bytesReceived.get() + " B of " + FILE_SIZE + " B."
			);
			assertNull(
				failure.get(),
				() -> "A steadily progressing download was terminated after " + elapsedMillis + " ms " +
					"with " + bytesReceived.get() + " B of " + FILE_SIZE + " B delivered: " +
					Status.fromThrowable(failure.get()) + ". The request deadline is meant to bound " +
					"silence, not the length of the transfer - see `GrpcOutboundGate`."
			);

			// Without this the test could pass on a machine fast enough to finish inside the deadline,
			// proving nothing at all. It is an assertion about the *scenario*, not about the code.
			assertTrue(
				elapsedMillis > 2 * SERVER_REQUEST_TIMEOUT_MILLIS,
				"The transfer took only " + elapsedMillis + " ms against a " +
					SERVER_REQUEST_TIMEOUT_MILLIS + " ms deadline, so it never outlived the budget it " +
					"is supposed to outlive. Raise FILE_SIZE or CONSUMER_PACING_MILLIS."
			);
			assertEquals(FILE_SIZE, bytesReceived.get(), "Client did not receive the whole file.");
			assertEquals(
				expectedCrc.getValue(), receivedCrc.getValue(),
				"Downloaded contents differ from what was exported."
			);
		} finally {
			pacer.shutdownNow();
		}
	}

}
