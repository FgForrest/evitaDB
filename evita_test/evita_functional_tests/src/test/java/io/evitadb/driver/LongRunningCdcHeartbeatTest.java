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

package io.evitadb.driver;

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.driver.cdc.HeartBeatSensor;
import io.evitadb.driver.config.ClientTlsOptions;
import io.evitadb.driver.config.ClientTimeoutOptions;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.HostDefinition;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.grpc.requestResponse.cdc.HeartBeat;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.TestConstants;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.IOUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Long-running integration tests that verify CDC (Change Data Capture) heartbeats
 * keep subscriber connections alive for extended periods (~10 minutes) without actual
 * data modifications.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@Tag(TestConstants.LONG_RUNNING_TEST)
@Disabled("Long-running test - enable manually when needed")
@DisplayName("Long-running CDC heartbeat tests")
class LongRunningCdcHeartbeatTest implements TestConstants, EvitaTestSupport {

	/**
	 * Test duration in minutes.
	 */
	private static final int TEST_DURATION_MINUTES = 45;
	/**
	 * Server timeout in seconds.
	 */
	private static final int SERVER_TIMEOUT_SECONDS = 30;
	/**
	 * Client streaming timeout in seconds.
	 */
	private static final int CLIENT_STREAMING_TIMEOUT_SECONDS = 30;
	/**
	 * Expected heartbeat interval in seconds.
	 */
	private static final int EXPECTED_HEARTBEAT_INTERVAL_SECONDS = 25; // 30 - 5 = 25
	/**
	 * Data set name for the test.
	 */
	private static final String DATA_SET_NAME = "longRunningCdcHeartbeatTest";
	/**
	 * Catalog name for the test.
	 */
	private static final String TEST_CATALOG = "heartbeatTestCatalog";
	/**
	 * Entity name for the test.
	 */
	private static final String TEST_ENTITY = "TestEntity";

	/**
	 * Evita instance.
	 */
	private Evita evita;
	/**
	 * Evita server instance.
	 */
	private EvitaServer evitaServer;
	/**
	 * Evita client instance.
	 */
	private EvitaClient evitaClient;

	@BeforeEach
	void setUp() throws IOException {
		// Create temporary data directory using EvitaTestSupport helper
		final Path evitaDataDirectory = this.getPathInTargetDirectory(DATA_SET_NAME);
		evitaDataDirectory.toFile().mkdirs();

		// Create Evita instance with minimal configuration
		this.evita = new Evita(
			EvitaConfiguration.builder()
				.storage(StorageOptions.builder()
							 .storageDirectory(evitaDataDirectory)
							 .build())
				.build()
		);

		// Create catalog with simple entity collection
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG, session -> {
				session.defineEntitySchema(TEST_ENTITY)
					.withAttribute("name", String.class, AttributeSchemaEditor::filterable)
					.updateVia(session);
				// Transition to transactional (ALIVE) state
				session.goLiveAndClose();
				return null;
			}
		);

		// Create ApiOptions with custom timeout
		final ApiOptions apiOptions = ApiOptions.builder(
				EvitaParameterResolver.createApiOptions(
					DATA_SET_NAME,
					this.evita,
					this.getPortManager(),
					GrpcProvider.CODE, SystemProvider.CODE
				)
			)
			.requestTimeoutInMillis(SERVER_TIMEOUT_SECONDS * 1000)
			.build();

		// Start EvitaServer
		this.evitaServer = new EvitaServer(this.evita, apiOptions);
		this.evitaServer.run();

		// Get actual allocated ports from the running server
		final ApiOptions runningApiOptions = this.evitaServer.getExternalApiServer().getApiOptions();
		final HostDefinition grpcHost = runningApiOptions
			.getEndpointConfiguration(GrpcProvider.CODE)
			.getHost()[0];
		final HostDefinition systemHost = runningApiOptions
			.getEndpointConfiguration(SystemProvider.CODE)
			.getHost()[0];

		// Get certificate path for client
		final String serverCertificates = runningApiOptions.certificate().getFolderPath().toString();
		final Path clientCertificates = Path.of(serverCertificates + "-client");

		// Create EvitaClient with matching timeout
		this.evitaClient = new EvitaClient(
			EvitaClientConfiguration.builder()
				.host(grpcHost.hostAddress())
				.port(grpcHost.port())
				.systemApiPort(systemHost.port())
				.tls(
					ClientTlsOptions.builder()
						.mtlsEnabled(false)
						.certificateFolderPath(clientCertificates)
						.build()
				)
				.timeouts(
					ClientTimeoutOptions.builder()
						.streamingTimeout(CLIENT_STREAMING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
						.build()
				)
				.build()
		);
	}

	@AfterEach
	void tearDown() {
		// Use IOUtils.closeQuietly with method references - null checks outside like DiskRingBuffer pattern
		if (this.evitaClient != null) {
			IOUtils.closeQuietly(this.evitaClient::close);
		}
		if (this.evitaServer != null) {
			this.evitaServer.stop();
		}
		if (this.evita != null) {
			IOUtils.closeQuietly(this.evita::close);
		}
		this.getPortManager().releasePorts(DATA_SET_NAME);
		// Use EvitaTestSupport helper method for directory cleanup
		this.cleanTestSubDirectoryWithRethrow(DATA_SET_NAME);
	}

	@Test
	@DisplayName("should keep system CDC subscriber alive via heartbeats")
	void shouldKeepSystemCdcSubscriberAliveViaHeartbeats() throws Exception {
		try (final HeartbeatTrackingSubscriber<ChangeSystemCapture> subscriber =
				 new HeartbeatTrackingSubscriber<>()) {

			// Register system change capture
			final ChangeCapturePublisher<ChangeSystemCapture> publisher =
				this.evitaClient.registerSystemChangeCapture(
					new ChangeSystemCaptureRequest(null, null, ChangeCaptureContent.BODY)
				);
			publisher.subscribe(subscriber);

			// Wait for test duration
			log.info("Starting {} minute wait for system CDC heartbeat test...", TEST_DURATION_MINUTES);
			Thread.sleep(TimeUnit.MINUTES.toMillis(TEST_DURATION_MINUTES));

			// Verify results
			LongRunningCdcHeartbeatTest.verifyHeartbeats(subscriber);

			log.info(
				"System CDC test passed: {} heartbeats received, {} missed",
				subscriber.getHeartbeatCount(), subscriber.getMissedHeartbeats()
			);
		}
	}

	@Test
	@DisplayName("should keep catalog CDC subscriber alive via heartbeats")
	void shouldKeepCatalogCdcSubscriberAliveViaHeartbeats() throws Exception {
		try (final HeartbeatTrackingSubscriber<ChangeCatalogCapture> subscriber =
				 new HeartbeatTrackingSubscriber<>()) {

			// Register catalog change capture (catalog already created in setUp)
			this.evitaClient.updateCatalog(
				TEST_CATALOG, session -> {
					final ChangeCapturePublisher<ChangeCatalogCapture> publisher =
						session.registerChangeCatalogCapture(
							ChangeCatalogCaptureRequest.builder()
								.content(ChangeCaptureContent.BODY)
								.criteria(ChangeCatalogCaptureCriteria.builder().schemaArea().build())
								.build()
						);
					publisher.subscribe(subscriber);
					return null;
				}
			);

			// Wait for test duration
			log.info("Starting {} minute wait for catalog CDC heartbeat test...", TEST_DURATION_MINUTES);
			Thread.sleep(TimeUnit.MINUTES.toMillis(TEST_DURATION_MINUTES));

			// Verify results
			LongRunningCdcHeartbeatTest.verifyHeartbeats(subscriber);

			log.info(
				"Catalog CDC test passed: {} heartbeats received, {} missed",
				subscriber.getHeartbeatCount(), subscriber.getMissedHeartbeats()
			);
		}
	}

	/**
	 * Verifies that the subscriber received the expected number of heartbeats and no errors.
	 *
	 * @param subscriber subscriber to verify
	 */
	private static void verifyHeartbeats(@Nonnull final HeartbeatTrackingSubscriber<?> subscriber) {
		assertNull(subscriber.getError(), "Should not have received any errors");

		final int expectedMinHeartbeats = (TEST_DURATION_MINUTES * 60) / EXPECTED_HEARTBEAT_INTERVAL_SECONDS - 5;
		assertTrue(
			subscriber.getHeartbeatCount() >= expectedMinHeartbeats,
			"Expected at least " + expectedMinHeartbeats + " heartbeats, got " + subscriber.getHeartbeatCount()
		);

		assertEquals(
			0, subscriber.getMissedHeartbeats(),
			"Should have no missed heartbeats (all indices sequential)"
		);
	}

	/**
	 * Subscriber that tracks heartbeats and errors for CDC streams.
	 *
	 * @param <T> type of the change capture content
	 */
	private static class HeartbeatTrackingSubscriber<T> implements Subscriber<T>, HeartBeatSensor, AutoCloseable {
		/**
		 * Count of received heartbeats.
		 */
		private final AtomicInteger heartbeatCount = new AtomicInteger(0);
		/**
		 * Index of the last seen heartbeat.
		 */
		private final AtomicLong lastSeenIndex = new AtomicLong(-1);
		/**
		 * Count of missed heartbeats (detected by gaps in indices).
		 */
		private final AtomicInteger missedHeartbeats = new AtomicInteger(0);
		/**
		 * Any error that occurred during the subscription.
		 */
		@Nullable
		private volatile Throwable error = null;
		/**
		 * Current subscription.
		 */
		private Subscription subscription;

		@Override
		public void onSubscribe(final Subscription subscription) {
			this.subscription = subscription;
			this.subscription.request(Integer.MAX_VALUE);
		}

		@Override
		public void onNext(final T item) {
			// we don't expect any data in these tests
		}

		@Override
		public void onError(final Throwable throwable) {
			this.error = throwable;
			log.error("Subscription error", throwable);
		}

		@Override
		public void onComplete() {
			log.info("Subscription completed");
		}

		@Override
		public void onHeartBeat(@Nonnull final HeartBeat heartBeat) {
			this.heartbeatCount.incrementAndGet();
			final long expectedIndex = this.lastSeenIndex.get() + 1;
			if (this.lastSeenIndex.get() >= 0 && heartBeat.index() != expectedIndex) {
				this.missedHeartbeats.addAndGet((int) (heartBeat.index() - expectedIndex));
				log.warn("Missed heartbeat(s)! Expected index {}, got {}", expectedIndex, heartBeat.index());
			}
			this.lastSeenIndex.set(heartBeat.index());
			log.info("Received heartbeat #{} at index {}", this.heartbeatCount.get(), heartBeat.index());
		}

		@Override
		public void close() {
			if (this.subscription != null) {
				this.subscription.cancel();
			}
		}

		/**
		 * Returns the count of received heartbeats.
		 * @return count of heartbeats
		 */
		public int getHeartbeatCount() {
			return this.heartbeatCount.get();
		}

		/**
		 * Returns the count of missed heartbeats.
		 * @return count of missed heartbeats
		 */
		public int getMissedHeartbeats() {
			return this.missedHeartbeats.get();
		}

		/**
		 * Returns the error that occurred during the subscription, or null if none.
		 * @return Throwable error or null
		 */
		@Nullable
		public Throwable getError() {
			return this.error;
		}
	}
}
