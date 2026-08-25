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
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.netty.util.internal.PlatformDependent;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.io.OutputStream;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
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
 * Proves that `EvitaManagementService.fetchFile` bounds the memory it holds on behalf of a client
 * that stops consuming the stream.
 *
 * `fetchFile` streams a file to the client in chunks. Armeria hands every `onNext` straight into the
 * response `StreamMessage`, whose write queue is unbounded, and reports back-pressure only through
 * `ServerCallStreamObserver.isReady()` (which, in Armeria, is `pendingMessages == 0`). A handler that
 * reads the file in a tight loop and never consults readiness therefore converts "slow consumer" into
 * "the whole file is resident in the server's memory" - the observed production symptom being a large
 * backup download over a slow tunnel that dies part-way through with a bare `UNKNOWN` status and no
 * message. `StreamingServerCall.sendMessage` marshals on the Armeria event loop, so the allocation
 * that finally fails - direct buffer memory, bounded by `MaxDirectMemorySize` rather than `-Xmx` -
 * fails inside Armeria, whose fallback for an escaping `Throwable` is exactly `UNKNOWN` with no
 * description (`verboseResponses` is off). Nothing in evitaDB remaps it either:
 * `executeWithClientContext` catches only `RuntimeException`, and `ObservableRunnable.run` catches
 * only `Exception`. The handler now paces itself with `GrpcOutboundGate`; this test is what holds it
 * to that.
 *
 * The test reproduces that shape deterministically and without relying on an actual memory
 * exhaustion (an OOME inside a surefire fork would take the rest of the module down with it):
 * a manual-flow-control client asks for a handful of messages and then stops requesting, and the
 * server is given a settle window in which an unbounded handler would drain the whole file into its
 * outbound queue. The assertion is on retained memory - heap *and* off-heap, because Armeria
 * marshals each message into a pooled buffer before queueing it - with a wide margin: it is an
 * order-of-magnitude question, not a precise one.
 *
 * **Calibration** (measured against the ungated handler, 512 MB file, `-Xmx5g`): the client
 * consumed 4 chunks / 256 KB, no thread remained inside `fetchFile` - the streaming loop had run to
 * completion - and the retained delta was **1035 MB**, against a 64 MB limit: a 16x margin. Almost
 * none of it was heap (70 MB -> 77 MB); it was Netty direct memory (4 MB -> 1084 MB). The ~2x
 * amplification over the file size is pooled-arena rounding - a 64 KB payload plus the 5-byte gRPC
 * frame header lands in the next size class up, so every chunk costs about 128 KB. That is why the
 * production ceiling is `MaxDirectMemorySize` rather than `-Xmx`, and why a 690 MB download needs
 * roughly 1.4 GB of direct memory to buffer.
 *
 * If this test stops failing when the readiness gating is removed from `fetchFile`, it has gone
 * decorative - the most likely reason being that `ObservabilityInterceptor` stopped delegating
 * `ServerCall.isReady()` (the `io.grpc.ServerCall` default returns `true` unconditionally, so a
 * non-delegating decorator silently disables the gating); that specific trap is guarded separately
 * and cheaply by `ObservabilityInterceptorReadinessTest` in the functional module.
 *
 * The gRPC-Web serialization format is deliberate: it is the framing evitaLab uses, and the one the
 * production report came from. It shares the framed code path with plain gRPC-proto, so the
 * buffering behaviour is identical either way.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("gRPC fetchFile must not buffer a whole file for a client that stops consuming")
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(MANAGEMENT)
@Tag(EXPORT)
@Tag(SLOW)
public class LongRunningGrpcFetchFileBackpressureTest {
	private static final String DATA_SET = "GrpcFetchFileBackpressure";
	/**
	 * Chunk size the server streams with - mirrors `EvitaManagementService.FETCH_FILE_CHUNK_SIZE`.
	 * Only used to make the log lines and the failure message legible; nothing here depends on the
	 * server actually using this value.
	 */
	private static final int CHUNK_SIZE = 1_048_576;
	/**
	 * Size of the file the client asks for. Big enough that "the server buffered all of it" and
	 * "the server buffered a couple of messages" are separated by three orders of magnitude, small
	 * enough to be written and read within a long-running test's budget.
	 */
	private static final long FILE_SIZE = 512L * 1024L * 1024L;
	/**
	 * Number of messages the client consumes before it stops requesting and goes silent.
	 */
	private static final int CHUNKS_CONSUMED_BEFORE_STALL = 4;
	/**
	 * Upper bound on the memory the server may retain while the client is silent, set from measurements
	 * on **both** sides rather than from a round fraction of the file:
	 *
	 * - gated (this fix): **14.4 MiB** retained, of which **12.0 MiB** was still there after the client
	 *   drained the stream - i.e. allocator arena growth, not queued messages. Genuine in-flight data
	 *   was ~2.4 MiB, about two 1 MB chunks, exactly what one message in flight predicts;
	 * - ungated (the bug): **1035 MiB**, the whole file plus pooled-arena rounding.
	 *
	 * 32 MiB sits ~2x above the green measurement - enough headroom for a machine whose Netty arenas
	 * (which scale with core count) reserve more than this one's - and 32x below the red one. It is
	 * deliberately *not* the 64 MiB the failing side alone would have justified: that would also pass a
	 * half-working fix that buffered 50 MB.
	 *
	 * **This measurement is JVM-wide, so a shared surefire fork can fail it spuriously.** It samples the
	 * whole heap and both direct-memory counters, not this call's allocations, so any sibling test
	 * allocating in the same fork counts against the budget. Running the module's whole `LongRunning*`
	 * set together does exactly that: the generational fuzz tests push the baseline heap near a gigabyte
	 * and the "retained" figure to ~3.6 GB, and this assertion fires while the fix is working perfectly.
	 * Before believing a failure here, read the two diagnostics logged just above it - a thread count of
	 * `1` inside `fetchFile` with the producer `TIMED_WAITING` **is the gate doing its job**, and points
	 * at a contaminated fork rather than a regression. Confirm by running this class on its own: the
	 * same code that "retained 3.6 GB" alongside the fuzz tests retains ~5 MB alone.
	 *
	 * Contamination cuts the other way too, and that direction is worse because it is silent: a sibling
	 * GC between the two samples can make the delta *negative* (observed: -91 MB), which sails past this
	 * assertion. A negative or implausibly small reading means the baseline was contaminated high, not
	 * that the gate is working - so it is only evidence when this class runs alone.
	 */
	private static final long MAX_TOLERATED_SERVER_BUFFERING = 32L * 1024L * 1024L;
	/**
	 * How long the server is given to drain the file into its outbound queue while the client is
	 * silent. This is a settle window, not a wait for a condition - a slower machine only reads
	 * *less* of the file within it, so it can never turn a genuine failure into a false pass, and a
	 * correct server has nothing to do during it at all.
	 */
	private static final long SETTLE_WINDOW_MILLIS = 20_000L;

	/**
	 * Memory the process is holding, split by where it is held.
	 *
	 * Both halves are needed: `StreamingServerCall` marshals every message into an `HttpData`
	 * *eagerly* and queues that, so a message waiting to be written retains a (typically pooled and
	 * direct) `ByteBuf` rather than the protobuf object it came from. Measuring only the Java heap
	 * would therefore under-report - possibly to zero - exactly the buffering this test is about.
	 *
	 * @param heapBytes       live data on the Java heap
	 * @param directBytes     off-heap buffer memory, whichever of the two counters sees more of it
	 * @param nettyDirect     Netty's own direct-memory counter (`-1` when Netty has it disabled)
	 * @param nioDirect       direct memory visible to the JVM's `BufferPoolMXBean`
	 */
	private record MemoryFootprint(long heapBytes, long directBytes, long nettyDirect, long nioDirect) {

		/**
		 * Returns the total memory retained regardless of where it sits.
		 */
		long total() {
			return this.heapBytes + this.directBytes;
		}

	}

	/**
	 * Returns the memory in use after asking the collector to run, so that anything unreachable is
	 * not counted as retained. Neither `System.gc()` nor the pauses between the rounds are a wait
	 * for a condition - they only sharpen the measurement.
	 *
	 * @return snapshot of retained heap and off-heap memory
	 */
	@Nonnull
	private static MemoryFootprint memoryAfterGc() throws InterruptedException {
		for (int i = 0; i < 3; i++) {
			System.gc();
			Thread.sleep(250L);
		}
		final long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
		// Netty's counter covers buffers allocated without a Cleaner (invisible to the JVM's own
		// bean); the JVM bean covers plain `ByteBuffer.allocateDirect`. Whichever is larger is the
		// better estimate - summing them would double-count buffers that both of them see.
		final long nettyDirect = PlatformDependent.usedDirectMemory();
		final long nioDirect = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)
			.stream()
			.filter(it -> "direct".equals(it.getName()))
			.mapToLong(BufferPoolMXBean::getMemoryUsed)
			.sum();
		return new MemoryFootprint(heap, Math.max(Math.max(nettyDirect, 0L), nioDirect), nettyDirect, nioDirect);
	}

	/**
	 * Logs whether any thread is currently inside the `fetchFile` streaming loop. Diagnostic only:
	 * a handler that ignores back-pressure has long since run the loop to completion by the time the
	 * settle window expires, whereas a gated one is either parked in it or waiting for an on-ready
	 * callback - which is why this is logged rather than asserted.
	 */
	private static void logFetchFileThreadState() {
		final ThreadInfo[] threads = ManagementFactory.getThreadMXBean().dumpAllThreads(false, false);
		final long inFetchFile = Arrays.stream(threads)
			.filter(it -> Arrays.stream(it.getStackTrace())
				.anyMatch(frame -> frame.getClassName().endsWith("EvitaManagementService") &&
					frame.getMethodName().contains("fetchFile")))
			.count();
		log.info("Threads currently inside EvitaManagementService.fetchFile: {}", inFetchFile);
		Arrays.stream(threads)
			.filter(it -> Arrays.stream(it.getStackTrace())
				.anyMatch(frame -> frame.getClassName().contains("GrpcOutboundGate") ||
					(frame.getClassName().endsWith("EvitaManagementService") &&
						frame.getMethodName().contains("fetchFile"))))
			.forEach(
				it -> log.info(
					"Producer thread `{}` is {}:\n\t{}",
					it.getThreadName(), it.getThreadState(),
					Arrays.stream(it.getStackTrace()).limit(12).map(StackTraceElement::toString)
						.reduce((a, b) -> a + "\n\t" + b).orElse("<no stack>")
				)
			);
	}

	/**
	 * Creates a file of the requested size and publishes it for fetching.
	 *
	 * @param evita        engine whose export service the file is stored in
	 * @param size         size of the file in bytes
	 * @param contentsCrc  accumulator the written contents are checksummed into
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
			"grpc-fetch-file-backpressure.bin",
			"Large file used by the fetchFile back-pressure test",
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
		// Mandatory here, not a preference. The default test engine collapses the request pool into a
		// direct executor, which makes `executeWithClientContext` run the streaming loop on the
		// transport event loop - the one thread that has to drain the outbound queue. Readiness gating
		// is impossible there by construction (the gate detects it and deliberately runs ungated), so
		// without real pools this test would measure the *unbounded* path no matter what the server
		// does, and could never fail. A networked server always runs on real pools.
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
		"Should keep server memory bounded while a client stalls mid-download, " +
			"and still deliver the file when it resumes"
	)
	void shouldNotBufferWholeFileForStalledClient(Evita evita, GrpcClientBuilder clientBuilder) throws Exception {
		final CRC32 expectedCrc = new CRC32();
		final FileForFetch bigFile = createExportedFile(evita, FILE_SIZE, expectedCrc);
		final AtomicReference<ClientCallStreamObserver<GrpcFetchFileRequest>> call = new AtomicReference<>();
		try {
			final EvitaManagementServiceStub managementStub = clientBuilder
				// gRPC-Web framing - the format evitaLab uses and the one the production report came from
				.serializationFormat(GrpcSerializationFormats.PROTO_WEB)
				// the client stalls on purpose; neither a response timeout nor the 10 MB default
				// response-length cap may be what ends the call
				.responseTimeoutMillis(0)
				.maxResponseLength(0)
				.build(EvitaManagementServiceStub.class);

			final CountDownLatch initialChunks = new CountDownLatch(CHUNKS_CONSUMED_BEFORE_STALL);
			final CountDownLatch terminated = new CountDownLatch(1);
			final AtomicReference<Throwable> failure = new AtomicReference<>();
			final AtomicInteger chunksReceived = new AtomicInteger();
			final AtomicLong bytesReceived = new AtomicLong();
			final CRC32 receivedCrc = new CRC32();

			final MemoryFootprint memoryBeforeDownload = memoryAfterGc();

			managementStub.fetchFile(
				GrpcFetchFileRequest.newBuilder()
					.setFileId(EvitaDataTypesConverter.toGrpcUuid(bigFile.fileId()))
					.build(),
				new ClientResponseObserver<GrpcFetchFileRequest, GrpcFetchFileResponse>() {

					@Override
					public void beforeStart(ClientCallStreamObserver<GrpcFetchFileRequest> requestStream) {
						// take manual control of the demand - the client asks for a handful of
						// messages up front and then deliberately goes silent
						requestStream.disableAutoRequestWithInitial(CHUNKS_CONSUMED_BEFORE_STALL);
						call.set(requestStream);
					}

					@Override
					public void onNext(GrpcFetchFileResponse value) {
						final byte[] contents = value.getFileContents().toByteArray();
						receivedCrc.update(contents);
						bytesReceived.addAndGet(contents.length);
						chunksReceived.incrementAndGet();
						initialChunks.countDown();
					}

					@Override
					public void onError(Throwable t) {
						failure.set(t);
						initialChunks.countDown();
						terminated.countDown();
					}

					@Override
					public void onCompleted() {
						terminated.countDown();
					}
				}
			);

			final boolean initialChunksDelivered = initialChunks.await(30, TimeUnit.SECONDS);
			if (!initialChunksDelivered) {
				// the producer's own state is the only thing that distinguishes "the gate is stuck"
				// from "the client never asked", and it is gone by the time the assertion is reported
				logFetchFileThreadState();
			}
			assertTrue(
				initialChunksDelivered,
				"Server did not deliver the first " + CHUNKS_CONSUMED_BEFORE_STALL + " chunks within 30 " +
					"seconds - got " + chunksReceived.get() + " (" + bytesReceived.get() + " B), failure: " +
					failure.get()
			);

			// give an unbounded server the time it needs to drain the whole file into its outbound queue
			Thread.sleep(SETTLE_WINDOW_MILLIS);

			final MemoryFootprint memoryWhileStalled = memoryAfterGc();
			final long retained = memoryWhileStalled.total() - memoryBeforeDownload.total();
			logFetchFileThreadState();
			log.info(
				"Client consumed {} chunks ({} B) of a {} B file; server retained {} B ({} MB). " +
					"Before: {}; while stalled: {}",
				chunksReceived.get(), bytesReceived.get(), FILE_SIZE, retained, retained / (1024 * 1024),
				memoryBeforeDownload, memoryWhileStalled
			);
			if (failure.get() instanceof StatusRuntimeException sre) {
				log.info(
					"Call already terminated while the client was stalled: status={}, description={}",
					sre.getStatus().getCode(), sre.getStatus().getDescription()
				);
			}

			// preconditions for the measurement to mean anything at all
			assertNull(
				failure.get(),
				"The call must survive a stalled client, but it ended with: " + failure.get()
			);
			assertEquals(
				CHUNKS_CONSUMED_BEFORE_STALL, chunksReceived.get(),
				"Client received messages it never requested - manual flow control is not in effect, " +
					"so this run proves nothing about server-side buffering."
			);

			// The client resumes: the transfer must still complete, intact. This runs *before* the
			// memory verdict on purpose - asserting the bound here would abort the method on the
			// currently-unbounded implementation and leave the correctness half of the test never
			// executed, so it would run for the first time on the day the fix lands and any bug in
			// it would read as a broken fix.
			call.get().request(Integer.MAX_VALUE);
			assertTrue(
				terminated.await(300, TimeUnit.SECONDS),
				"Download did not finish within 300 seconds after the client resumed consuming."
			);
			assertNull(failure.get(), "Download failed after the client resumed: " + failure.get());
			assertEquals(FILE_SIZE, bytesReceived.get(), "Client did not receive the whole file.");
			assertEquals(
				expectedCrc.getValue(), receivedCrc.getValue(),
				"Downloaded contents differ from the stored file."
			);

			// once everything has been drained the queue is empty again - the delta measured here is
			// the noise floor of the measurement itself (allocator growth, counter drift), and is
			// what makes the MAX_TOLERATED_SERVER_BUFFERING margin legible rather than arbitrary
			final MemoryFootprint memoryAfterDrain = memoryAfterGc();
			log.info(
				"After the client drained the stream, retained {} B relative to the start; footprint: {}",
				memoryAfterDrain.total() - memoryBeforeDownload.total(), memoryAfterDrain
			);

			assertTrue(
				retained < MAX_TOLERATED_SERVER_BUFFERING,
				"Server retained " + retained + " B for a client that consumed only " +
					(long) CHUNKS_CONSUMED_BEFORE_STALL * CHUNK_SIZE + " B - fetchFile is streaming " +
					"without regard for transport readiness and buffers the whole file (limit " +
					MAX_TOLERATED_SERVER_BUFFERING + " B)."
			);
		} finally {
			// an assertion that fires mid-download leaves the call - and whatever the server queued
			// behind it - alive; cancel it so the next test in the fork does not inherit the memory
			final ClientCallStreamObserver<GrpcFetchFileRequest> pendingCall = call.get();
			if (pendingCall != null) {
				pendingCall.cancel("test finished", null);
			}
			evita.management().deleteFile(bigFile.fileId());
		}
	}

}
