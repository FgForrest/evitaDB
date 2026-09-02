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

import com.google.protobuf.ByteString;
import io.evitadb.api.EvitaManagementContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.driver.ClientTask;
import io.evitadb.driver.EvitaClient;
import io.evitadb.driver.EvitaClientManagement;
import io.evitadb.driver.interceptor.ClientSessionInterceptor;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.HostDefinition;
import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import io.evitadb.externalApi.grpc.generated.EvitaManagementServiceGrpc.EvitaManagementServiceStub;
import io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogRequest;
import io.evitadb.externalApi.grpc.generated.GrpcRestoreCatalogResponse;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.CertificateUtils;
import io.evitadb.utils.VersionUtils.SemVer;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.stub.StreamObserver;
import io.evitadb.driver.config.ClientTimeoutOptions;
import io.evitadb.driver.config.ClientTlsOptions;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.STREAM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that a catalog backup survives being uploaded across many messages - over both restore RPCs
 * evitaDB exposes, because each one is load-bearing for a different reason.
 *
 * **`RestoreCatalogUnary`, which the driver uses.** A client-streaming RPC delivers the whole
 * conversation as a single HTTP request, so the server's `maxRequestLength` - which evitaDB wires to
 * `api.maxEntitySizeInBytes`, 2 MB by default - bounded the *entire backup*. Anything larger died
 * part-way through with a bare `RESOURCE_EXHAUSTED`, which made `EvitaClient`'s restore unusable for a
 * catalog of any real size. The driver now uploads through `RestoreCatalogUnary`, where every chunk is
 * a request of its own and only the chunk has to fit. {@link #SERVER_REQUEST_LIMIT} is asserted from
 * below for exactly this reason: shrink the payload under it and the test stops proving anything.
 *
 * **The client-streaming `RestoreCatalog`, which third parties still use.** The server no longer
 * writes to disk inline on the Armeria event loop; each chunk is appended on a per-call
 * {@link java.util.concurrent.CompletableFuture} chain running on a worker, and the conversation
 * advances only because each completed write hand-issues demand for the next chunk. Since the driver
 * was rerouted to the unary RPC, nothing else drives that path across more than one message.
 * {@link #shouldRestoreCatalogUploadedThroughClientStreamingRpc} is what keeps it covered - read that
 * method's JavaDoc before trusting it, because it records both what its calibration detected and, more
 * importantly, what it could not.
 *
 * A ZIP is the ideal detector for both - its central directory and per-entry CRCs make reordering or
 * truncation fail loudly - so the assertion is that the restored catalog opens and holds exactly what
 * was backed up, payloads compared byte for byte.
 *
 * **Why this lives in the long-running module rather than beside the functional backup/restore test.**
 * The functional round trip
 * (`EvitaClientReadWriteTest#shouldBackupAndRestoreCatalogViaDownloadingAndUploadingFileContents`)
 * backs up a ten-product catalog, whose archive fits in a **single** message - so neither the size
 * ceiling nor the per-chunk hand-off is exercised by it at all. Making the upload span many messages
 * costs the time to build and archive a payload that large, which is what buys the `@Tag(SLOW)`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("gRPC catalog restore must carry an upload intact - past the request-body limit, and in order")
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(GRPC)
@Tag(EXTERNAL_API)
@Tag(MANAGEMENT)
@Tag(STREAM)
@Tag(SLOW)
public class LongRunningGrpcRestoreCatalogUploadTest {
	private static final String DATA_SET = "GrpcRestoreCatalogUpload";
	private static final String TEST_CATALOG = "testCatalog";
	/**
	 * Second, deliberately small catalog. The client-streaming RPC cannot carry a backup past
	 * {@link #SERVER_REQUEST_LIMIT} at all - that ceiling is the whole reason the driver left it - so
	 * the test that keeps that RPC covered needs a payload that fits under it while still spanning
	 * enough messages to exercise the per-chunk hand-off.
	 */
	private static final String TEST_CATALOG_SMALL = "testCatalogSmall";
	private static final String ENTITY_TYPE = "blob";
	private static final String ATTRIBUTE_PAYLOAD = "payload";
	/**
	 * Number of entities the backed-up catalog holds. Chosen so that the resulting archive lands
	 * comfortably **over** {@link #SERVER_REQUEST_LIMIT} - the ceiling the upload used to die on.
	 */
	private static final int ENTITY_COUNT = 200;
	/**
	 * Number of entities {@link #TEST_CATALOG_SMALL} holds. Sized for the opposite constraint: the
	 * archive must stay under {@link #SERVER_REQUEST_LIMIT} with headroom, yet still take at least
	 * {@link #MIN_EXPECTED_STREAMED_CHUNKS} messages of {@link #STREAMING_CHUNK_SIZE}.
	 */
	private static final int SMALL_ENTITY_COUNT = 20;
	/**
	 * Size of each entity's payload attribute. The content is high-entropy on purpose - a compressible
	 * payload would shrink the archive back to a couple of messages and quietly defeat the test.
	 */
	private static final int PAYLOAD_SIZE = 32_768;
	/**
	 * Chunk size the driver uploads with (`EvitaClientManagement.RESTORE_CHUNK_SIZE`).
	 */
	private static final int CLIENT_CHUNK_SIZE = 524_288;
	/**
	 * Largest request body the server accepts: Armeria's `maxRequestLength`, which evitaDB wires to
	 * `api.maxEntitySizeInBytes` (`ApiOptions.DEFAULT_MAX_ENTITY_SIZE`, 2 MB).
	 *
	 * This is what made the *client-streaming* `RestoreCatalog` unusable for a real catalog: the whole
	 * upload is one request, so the limit bounded the entire backup rather than a chunk, and anything
	 * larger died part-way through with a bare `RESOURCE_EXHAUSTED`. The limit is deliberate operator
	 * policy and stays; the driver now uploads through `RestoreCatalogUnary`, where each chunk is a
	 * request of its own and only the chunk has to fit.
	 */
	private static final long SERVER_REQUEST_LIMIT = 2L * 1024L * 1024L;
	/**
	 * Lower bound on the number of messages the upload must span for this test to be testing anything
	 * the functional backup/restore test does not already cover - that one fits in a single message.
	 */
	private static final int MIN_EXPECTED_CHUNKS = 8;
	/**
	 * Chunk size the client-streaming upload is cut into. Much smaller than {@link #CLIENT_CHUNK_SIZE}
	 * so that a backup which must stay under {@link #SERVER_REQUEST_LIMIT} still spans many messages -
	 * roughly 17 at the current fixture size, which leaves room for the archive to shrink before the
	 * precondition below starts failing on its own.
	 */
	private static final int STREAMING_CHUNK_SIZE = 32_768;
	/**
	 * Lower bound on the number of messages the client-streaming upload must span. One message would
	 * leave the chunk-by-chunk hand-off - the thing that test exists for - entirely unexercised.
	 */
	private static final int MIN_EXPECTED_STREAMED_CHUNKS = 8;
	/**
	 * Fixed seed - the payloads must be reproducible across the two sessions that write and verify them.
	 */
	private static final long PAYLOAD_SEED = 0x5EEDL;

	/**
	 * Builds the payload of the entity with the given primary key. Deterministic, so the verification
	 * pass can regenerate exactly what the setup wrote.
	 *
	 * @param primaryKey primary key of the entity the payload belongs to
	 * @return payload contents
	 */
	@Nonnull
	private static String payloadOf(int primaryKey) {
		// a per-entity seed keeps the payloads independent of the order they are generated in
		final Random random = new Random(PAYLOAD_SEED + primaryKey);
		final char[] contents = new char[PAYLOAD_SIZE];
		for (int i = 0; i < contents.length; i++) {
			// printable, high-entropy, single-byte in UTF-8
			contents[i] = (char) ('!' + random.nextInt('~' - '!' + 1));
		}
		return new String(contents);
	}

	@DataSet(
		value = DATA_SET, openWebApi = {GrpcProvider.CODE, SystemProvider.CODE},
		readOnly = false, destroyAfterClass = true,
		// Mandatory, not a preference: the default test engine collapses the request pool into a direct
		// executor, so the upload's hand-off would run inline on the event loop - the very topology this
		// test exists to verify the absence of. Without it the ordered chain is never scheduled and the
		// test would pass against the old, event-loop-blocking implementation too.
		useRealThreadPools = true
	)
	static EvitaClient setUp(EvitaServer evitaServer) {
		final EvitaClient evitaClient = createClient(evitaServer);
		// `testCatalog` is the one the harness pre-creates; the small one has to be defined by hand
		fillCatalog(evitaClient, TEST_CATALOG, ENTITY_COUNT);
		evitaClient.defineCatalog(TEST_CATALOG_SMALL);
		fillCatalog(evitaClient, TEST_CATALOG_SMALL, SMALL_ENTITY_COUNT);
		return evitaClient;
	}

	/**
	 * Fills the given catalog with `entityCount` entities carrying {@link #payloadOf(int)} payloads and
	 * takes it live, so it can be backed up.
	 *
	 * @param evitaClient client to work through
	 * @param catalogName catalog to fill
	 * @param entityCount number of entities to create
	 */
	private static void fillCatalog(
		@Nonnull EvitaClient evitaClient,
		@Nonnull String catalogName,
		int entityCount
	) {
		evitaClient.updateCatalog(
			catalogName,
			session -> {
				session.defineEntitySchema(ENTITY_TYPE)
					.withAttribute(ATTRIBUTE_PAYLOAD, String.class, AttributeSchemaEditor::nullable)
					.updateVia(session);
				for (int primaryKey = 1; primaryKey <= entityCount; primaryKey++) {
					session.createNewEntity(ENTITY_TYPE, primaryKey)
						.setAttribute(ATTRIBUTE_PAYLOAD, payloadOf(primaryKey))
						.upsertVia(session);
				}
				session.goLiveAndClose();
				return null;
			}
		);
	}

	/**
	 * Builds a driver client pointed at the running test server.
	 *
	 * @param evitaServer the running server
	 * @return a client connected to it
	 */
	@Nonnull
	private static EvitaClient createClient(@Nonnull EvitaServer evitaServer) {
		final ApiOptions apiOptions = evitaServer.getExternalApiServer().getApiOptions();
		final HostDefinition grpcHost = apiOptions.getEndpointConfiguration(GrpcProvider.CODE).getHost()[0];
		final HostDefinition systemHost = apiOptions.getEndpointConfiguration(SystemProvider.CODE).getHost()[0];

		final String serverCertificates = apiOptions.certificate().getFolderPath().toString();
		final int lastDash = serverCertificates.lastIndexOf('-');
		assertTrue(lastDash > 0, "Dash not found! Look at the evita-configuration.yml in test resources!");
		final Path clientCertificates = Path.of(serverCertificates.substring(0, lastDash) + "-client");

		return new EvitaClient(
			EvitaClientConfiguration.builder()
				.host(grpcHost.hostAddress())
				.port(grpcHost.port())
				.systemApiPort(systemHost.port())
				.tls(
					ClientTlsOptions.builder()
						.mtlsEnabled(false)
						.certificateFolderPath(clientCertificates)
						.certificateFileName(Path.of(CertificateUtils.getGeneratedClientCertificateFileName()))
						.certificateKeyFileName(
							Path.of(CertificateUtils.getGeneratedClientCertificatePrivateKeyFileName())
						)
						.build()
				)
				.timeouts(
					ClientTimeoutOptions.builder()
						.timeout(10, TimeUnit.MINUTES)
						.build()
				)
				.build()
		);
	}

	/**
	 * Asserts that a restored catalog holds exactly what was backed up. The entity count alone would
	 * survive a payload that arrived scrambled or short, so every payload is compared byte for byte -
	 * that comparison is what actually pins the ordering of the uploaded chunks.
	 *
	 * @param evitaClient         client to verify through
	 * @param restoredCatalogName name the catalog was restored under
	 * @param entityCount         number of entities the catalog held when it was backed up
	 */
	private static void assertCatalogRestoredIntact(
		@Nonnull EvitaClient evitaClient,
		@Nonnull String restoredCatalogName,
		int entityCount
	) {
		final Set<String> catalogNames = evitaClient.getCatalogNames();
		assertTrue(catalogNames.contains(restoredCatalogName), "Restored catalog is not present on the server.");
		evitaClient.activateCatalog(restoredCatalogName);

		assertEquals(
			Integer.valueOf(entityCount),
			evitaClient.queryCatalog(
				// a block body with an explicit return keeps this off the void-returning overload
				restoredCatalogName, session -> {
					return session.getEntityCollectionSize(ENTITY_TYPE);
				}
			),
			"Restored catalog does not hold every entity that was backed up."
		);

		evitaClient.queryCatalog(
			restoredCatalogName,
			(Consumer<EvitaSessionContract>) session -> {
				for (int primaryKey = 1; primaryKey <= entityCount; primaryKey++) {
					final int currentKey = primaryKey;
					final SealedEntity entity = session
						.getEntity(ENTITY_TYPE, currentKey, attributeContentAll())
						.orElseThrow(
							() -> new IllegalStateException(
								"Entity " + ENTITY_TYPE + " #" + currentKey + " is missing after restore."
							)
						);
					assertEquals(
						payloadOf(currentKey),
						entity.getAttribute(ATTRIBUTE_PAYLOAD),
						"Payload of entity " + currentKey + " differs from the backed-up one."
					);
				}
			}
		);
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should restore a catalog uploaded across hundreds of messages, byte for byte")
	void shouldRestoreCatalogUploadedInManyChunks(EvitaClient evitaClient) throws Exception {
		final EvitaManagementContract management = evitaClient.management();
		final FileForFetch backup = management.backupCatalog(TEST_CATALOG, null, null, true)
			.get(10, TimeUnit.MINUTES);

		final long expectedChunks = backup.totalSizeInBytes() / CLIENT_CHUNK_SIZE;
		log.info(
			"Backed up {} B, which the driver uploads as roughly {} messages of {} B.",
			backup.totalSizeInBytes(), expectedChunks, CLIENT_CHUNK_SIZE
		);
		assertTrue(
			expectedChunks >= MIN_EXPECTED_CHUNKS,
			"Backup is only " + backup.totalSizeInBytes() + " B, i.e. about " + expectedChunks +
				" upload messages - too few to exercise the chunk-by-chunk hand-off this test guards. " +
				"Raise ENTITY_COUNT/PAYLOAD_SIZE or lower MIN_EXPECTED_CHUNKS deliberately."
		);
		assertTrue(
			backup.totalSizeInBytes() > SERVER_REQUEST_LIMIT,
			"Backup is only " + backup.totalSizeInBytes() + " B, under the server's " + SERVER_REQUEST_LIMIT +
				" B request-body limit - so it would upload happily through the old client-streaming path " +
				"too, and this test would no longer prove that the ceiling is gone. Grow the payload."
		);

		final String restoredCatalogName = TEST_CATALOG + "_restored";
		try (final InputStream inputStream = management.fetchFile(backup.fileId())) {
			management.restoreCatalog(restoredCatalogName, backup.totalSizeInBytes(), inputStream)
				.getFutureResult()
				.get(10, TimeUnit.MINUTES);
		}

		assertCatalogRestoredIntact(evitaClient, restoredCatalogName, ENTITY_COUNT);
	}

	/**
	 * Keeps the **client-streaming** `RestoreCatalog` RPC covered end to end. The driver no longer calls
	 * it - it was rerouted to `RestoreCatalogUnary` to escape {@link #SERVER_REQUEST_LIMIT} - but it
	 * stays on the wire for gRPC-Web and third-party clients, and this is the only test that drives
	 * `RestoreCatalogUploadObserver` across more than one message.
	 *
	 * **What it demonstrably guards: the hand-issued demand protocol.** The server uploads with
	 * `disableAutoRequest()` and re-issues `request(1)` from inside each completed write step, so the
	 * whole conversation advances only because a worker asks for the next chunk. Delete that
	 * `eventLoop().execute(() -> request(1))` and the upload stops dead after chunk one: no error, no
	 * status, the call simply never terminates. Verified - the counterfactual fails here at the 30 s
	 * latch with "Client-streaming upload never terminated", deterministically.
	 *
	 * **What it does *not* prove, despite the byte-for-byte comparison: write ordering.** Two mechanisms
	 * serialise the writes - the per-call `CompletableFuture` chain and the one-message-at-a-time demand
	 * above - and they are redundant. Removing the chain from `onCompleted()` alone leaves this test
	 * green, because demand for the next chunk is issued from inside the *completed* write step, so
	 * half-close cannot reach `submitRestoration` before the last write has landed. Removing **both**,
	 * with a 2 ms widener inside the write step, also left it green - the writes still arrived in order
	 * (measured at nine chunks, before {@link #STREAMING_CHUNK_SIZE} was halved to widen the margin on
	 * the precondition above). That is a failure to detect, not evidence of safety - a race that does
	 * not manifest is not a race that is absent. Treat the ordering invariant as **unverified by this
	 * test**, and do not read a green run here as licence to delete either mechanism.
	 *
	 * @param evitaClient driver client, used for the backup, the download and the verification
	 * @param evitaServer running server, needed to build a raw stub that speaks the streaming RPC
	 */
	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should restore a catalog uploaded through the client-streaming RPC, byte for byte")
	void shouldRestoreCatalogUploadedThroughClientStreamingRpc(
		EvitaClient evitaClient,
		EvitaServer evitaServer
	) throws Exception {
		final EvitaManagementContract management = evitaClient.management();
		final FileForFetch backup = management.backupCatalog(TEST_CATALOG_SMALL, null, null, true)
			.get(10, TimeUnit.MINUTES);

		final byte[] backupContents;
		try (final InputStream inputStream = management.fetchFile(backup.fileId())) {
			backupContents = inputStream.readAllBytes();
		}
		final int expectedChunks = (backupContents.length + STREAMING_CHUNK_SIZE - 1) / STREAMING_CHUNK_SIZE;
		log.info(
			"Backed up {} B, uploaded through the client-streaming RPC as {} messages of {} B.",
			backupContents.length, expectedChunks, STREAMING_CHUNK_SIZE
		);
		assertTrue(
			expectedChunks >= MIN_EXPECTED_STREAMED_CHUNKS,
			"Backup is only " + backupContents.length + " B, i.e. " + expectedChunks + " upload messages - " +
				"too few to exercise the per-chunk hand-off this test guards. Lower STREAMING_CHUNK_SIZE: " +
				"this bound and the SERVER_REQUEST_LIMIT one below hold the fixture from opposite sides, so " +
				"growing SMALL_ENTITY_COUNT to satisfy this one walks straight into that one."
		);
		assertTrue(
			backupContents.length < SERVER_REQUEST_LIMIT,
			"Backup is " + backupContents.length + " B, at or over the server's " + SERVER_REQUEST_LIMIT +
				" B request-body limit. A client-streaming upload is one request, so this would be rejected " +
				"outright and the failure would read as a regression of the ordered chain rather than an " +
				"oversized fixture. Shrink SMALL_ENTITY_COUNT."
		);

		final EvitaManagementServiceStub managementStub = TestGrpcClientBuilderCreator.getBuilder(
				new ClientSessionInterceptor(
					EvitaClientConfiguration.builder().build().clientId(),
					new SemVer(2025, 4)
				),
				evitaServer.getExternalApiServer()
			)
			.build(EvitaManagementServiceStub.class);

		final String restoredCatalogName = TEST_CATALOG_SMALL + "_streamRestored";
		final CountDownLatch uploadFinished = new CountDownLatch(1);
		final AtomicReference<GrpcRestoreCatalogResponse> uploadResponse = new AtomicReference<>();
		final AtomicReference<Throwable> uploadFailure = new AtomicReference<>();
		final StreamObserver<GrpcRestoreCatalogRequest> requestObserver = managementStub.restoreCatalog(
			new StreamObserver<>() {
				@Override
				public void onNext(GrpcRestoreCatalogResponse value) {
					uploadResponse.set(value);
				}

				@Override
				public void onError(Throwable t) {
					uploadFailure.set(t);
					uploadFinished.countDown();
				}

				@Override
				public void onCompleted() {
					uploadFinished.countDown();
				}
			}
		);

		int chunksSent = 0;
		for (int offset = 0; offset < backupContents.length; offset += STREAMING_CHUNK_SIZE) {
			final int length = Math.min(STREAMING_CHUNK_SIZE, backupContents.length - offset);
			requestObserver.onNext(
				GrpcRestoreCatalogRequest.newBuilder()
					.setCatalogName(restoredCatalogName)
					.setBackupFile(ByteString.copyFrom(backupContents, offset, length))
					.build()
			);
			chunksSent++;
		}
		requestObserver.onCompleted();

		// positive wait - a latch sized generously, so a loaded box cannot fail it spuriously
		assertTrue(uploadFinished.await(30, TimeUnit.SECONDS), "Client-streaming upload never terminated.");
		// nothing else covers this path, so its failure message is the whole diagnostic - a bare
		// Throwable concatenation would read "failed: null" for a status carrying no message
		assertNull(
			uploadFailure.get(),
			() -> "Client-streaming upload failed: " + Status.fromThrowable(uploadFailure.get())
		);

		final GrpcRestoreCatalogResponse response = uploadResponse.get();
		assertNotNull(response, "Server completed the upload without handing back a restoration task.");
		// the server's own byte accounting - independent of ZIP integrity, and the first thing to check
		// when the chain drops a chunk, because it localises the loss to the upload rather than the restore
		assertEquals(
			backupContents.length, response.getRead(),
			"Server accounted for a different number of bytes than the " + chunksSent + " messages carried."
		);

		// the restoration task runs asynchronously on the server; the driver's own task tracker turns it
		// into a real future, which is what keeps this off a sleep-poll loop
		final ClientTask<?, ?> restorationTask = ((EvitaClientManagement) management)
			.createTask(EvitaDataTypesConverter.toTaskStatus(response.getTask()));
		restorationTask.getFutureResult().get(10, TimeUnit.MINUTES);

		assertCatalogRestoredIntact(evitaClient, restoredCatalogName, SMALL_ENTITY_COUNT);
	}

	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Should tell a client that still streams an oversized upload which limit it hit")
	void shouldExplainWhyAnOversizedStreamingUploadIsRejected(EvitaServer evitaServer) throws Exception {
		// the driver no longer uses the client-streaming RPC, but it stays on the wire for gRPC-Web and
		// third-party clients - and for them the server's request-body limit is still a hard ceiling.
		// What must not happen is what used to: a bare RESOURCE_EXHAUSTED with no description, which a
		// client cannot distinguish from any other resource failure.
		final EvitaManagementServiceStub managementStub = TestGrpcClientBuilderCreator.getBuilder(
				new ClientSessionInterceptor(
					EvitaClientConfiguration.builder().build().clientId(),
					new SemVer(2025, 4)
				),
				evitaServer.getExternalApiServer()
			)
			.build(EvitaManagementServiceStub.class);

		final CountDownLatch terminated = new CountDownLatch(1);
		final AtomicReference<Throwable> failure = new AtomicReference<>();
		final StreamObserver<GrpcRestoreCatalogRequest> requestObserver = managementStub.restoreCatalog(
			new StreamObserver<>() {
				@Override
				public void onNext(GrpcRestoreCatalogResponse value) {
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

		final byte[] chunk = new byte[65_536];
		long sent = 0L;
		try {
			// push past the server's request-body limit; the exact overshoot does not matter
			while (sent <= SERVER_REQUEST_LIMIT) {
				requestObserver.onNext(
					GrpcRestoreCatalogRequest.newBuilder()
						.setCatalogName(TEST_CATALOG + "_oversized")
						.setBackupFile(ByteString.copyFrom(chunk))
						.build()
				);
				sent += chunk.length;
			}
			requestObserver.onCompleted();
		} catch (RuntimeException ex) {
			// the server may abort the call while we are still pushing - that is the condition under
			// test, not a failure of it
			log.debug("Upload aborted by the server after {} B.", sent);
		}

		assertTrue(terminated.await(30, TimeUnit.SECONDS), "Oversized upload never terminated.");
		final Throwable serverFailure = failure.get();
		assertNotNull(serverFailure, "Server accepted an upload larger than its own request-body limit.");

		final Status status = Status.fromThrowable(serverFailure);
		assertEquals(
			Code.RESOURCE_EXHAUSTED, status.getCode(),
			"Oversized upload should be reported as RESOURCE_EXHAUSTED, got: " + status
		);
		final String description = status.getDescription();
		assertNotNull(
			description,
			"Oversized upload was rejected with a bare status and no description - the client cannot tell " +
				"a configured limit from any other resource failure."
		);
		assertTrue(
			description.contains("maxEntitySizeInBytes"),
			"Rejection should name the limit that was hit, was: " + description
		);
		assertTrue(
			description.contains("RestoreCatalogUnary"),
			"Rejection should point at the RPC that has no such ceiling, was: " + description
		);
	}

}
