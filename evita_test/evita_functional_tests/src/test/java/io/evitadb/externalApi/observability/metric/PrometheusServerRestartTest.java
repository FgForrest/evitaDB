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

package io.evitadb.externalApi.observability.metric;

import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.observability.ObservabilityProvider;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.EvitaTestSupport;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import javax.annotation.Nonnull;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.evitadb.test.TestTags.OBSERVABILITY;
import static io.evitadb.test.TestTags.OBSERVABILITY_API;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Counts this test's server-owned metric series relative to the JVM-wide registry at each test's start.
 * Live servers retained by other classes' shared datasets are expected and contribute to that baseline.
 * `@Isolated` prevents concurrent server start/stop while measuring the delta; it does not stop existing servers.
 * Registry snapshots retain duplicate series, whereas HTTP text serialization rejects their identical labels.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Prometheus registry lifecycle across server restarts")
@Tag(OBSERVABILITY_API)
@Tag(OBSERVABILITY)
@Isolated
class PrometheusServerRestartTest implements EvitaTestSupport {
	/** Reserves distinct ports for both server lifetimes. */
	private static final String TEST_DIRECTORY = "prometheusServerRestartTest";

	/** Info family containing one data point per successfully constructed Armeria server. */
	private static final String BUILD_METRIC = "armeria_build";
	/** Gauge family bound once per server, before the TLS check that can refuse construction. */
	private static final String WORKERS_METRIC = "armeria_netty_worker_event_loop_workers";

	/** A fully stopped server must release its globally registered Armeria metrics before the next boot. */
	@Test
	@DisplayName("Releases each server's Armeria build series after stopping")
	void shouldReleaseArmeriaSeriesWhenServerStops() throws Exception {
		final int baseline = countSeries(BUILD_METRIC);
		final int[] ports = getPortManager().allocatePorts(TEST_DIRECTORY, 2);
		try {
			assertServerLifecycle(TEST_DIRECTORY + "First", ports[0], BUILD_METRIC, baseline, false);
			assertServerLifecycle(TEST_DIRECTORY + "Second", ports[1], BUILD_METRIC, baseline, false);
		} finally {
			getPortManager().releasePorts(TEST_DIRECTORY);
		}
	}

	/** A server whose construction fails after binding its event-loop gauges must not leave them registered. */
	@Test
	@DisplayName("Releases a failed server's event-loop series before a healthy server starts")
	void shouldReleaseEventLoopSeriesWhenConstructionFails() throws Exception {
		final int baseline = countSeries(WORKERS_METRIC);
		final int[] ports = getPortManager().allocatePorts(TEST_DIRECTORY, 2);
		try {
			failServerConstruction(TEST_DIRECTORY + "Failed", ports[0]);
			assertEquals(
				baseline, countSeries(WORKERS_METRIC),
				"Failed construction must release its worker event-loop series"
			);
			assertServerLifecycle(TEST_DIRECTORY + "Healthy", ports[1], WORKERS_METRIC, baseline, true);
		} finally {
			getPortManager().releasePorts(TEST_DIRECTORY);
		}
	}

	/** Counts every data point across snapshots, including identical series owned by different live servers. */
	private static int countSeries(@Nonnull String metricName) {
		final MetricSnapshots snapshots = PrometheusRegistry.defaultRegistry.scrape(metricName::equals);
		return snapshots.stream().mapToInt(snapshot -> snapshot.getDataPoints().size()).sum();
	}

	/**
	 * Boots a server whose only endpoint requires TLS in custom certificate mode with no certificate configured. The
	 * construction is refused by the TLS check in `ExternalApiServer#configureArmeria`, which runs after the meter
	 * registry has been created and the event-loop gauges bound to it.
	 */
	private void failServerConstruction(@Nonnull String directory, int port) throws Exception {
		final TestPaths paths = createTestPaths(directory);
		final Map<String, String> arguments = observabilityArguments(paths, port, "FORCE_TLS");
		arguments.put("api.certificate.generateAndUseSelfSigned", "false");
		final EvitaServer server = new EvitaServer(getPathInTargetDirectory(directory), arguments);
		try {
			final EvitaInvalidUsageException failure = assertThrows(EvitaInvalidUsageException.class, server::run);
			assertEquals(
				"Certificate path must be set when at least one endpoint requires TLS.", failure.getMessage(),
				"The construction must fail at the TLS check that follows the meter registry creation"
			);
		} finally {
			final Evita evita = server.getEvita();
			try {
				server.stop().get(30, TimeUnit.SECONDS);
			} finally {
				if (evita != null) {
					evita.close();
				}
				cleanupTestPaths(paths);
			}
		}
	}

	/** Builds the arguments of a server running only the observability endpoint on the given port. */
	@Nonnull
	private static Map<String, String> observabilityArguments(
		@Nonnull TestPaths paths, int port, @Nonnull String tlsMode
	) {
		final Map<String, String> arguments = createHashMap(16);
		arguments.put("storage.storageDirectory", paths.storage().toString());
		arguments.put("storage.workDirectory", paths.work().toString());
		arguments.put("cache.enabled", "false");
		arguments.put("api.requestTimeoutInMillis", "10K");
		arguments.put("api.certificate.folderPath", paths.work().resolve("certificates").toString());
		ExternalApiServer.gatherExternalApiProviders().forEach(registrar -> arguments.put(
			"api.endpoints." + registrar.getExternalApiCode() + ".enabled",
			Boolean.toString(ObservabilityProvider.CODE.equals(registrar.getExternalApiCode()))
		));
		arguments.put("api.endpoints.observability.host", "localhost:" + port);
		arguments.put("api.endpoints.observability.exposeOn", "localhost:" + port);
		arguments.put("api.endpoints.observability.tlsMode", tlsMode);
		return arguments;
	}

	/**
	 * Starts only observability, checks its metric delta, then fully stops and closes the server before recounting.
	 * The healthy server after failed construction also answers one HTTP scrape filtered to evitaDB's build info.
	 */
	private void assertServerLifecycle(
		@Nonnull String directory, int port, @Nonnull String metricName, int baseline, boolean verifyHttp
	) throws Exception {
		final TestPaths paths = createTestPaths(directory);
		final Map<String, String> arguments = observabilityArguments(paths, port, "FORCE_NO_TLS");
		final EvitaServer server = new EvitaServer(getPathInTargetDirectory(directory), arguments);
		try {
			server.run();
			assertEquals(
				baseline + 1, countSeries(metricName), directory + " must add exactly one " + metricName + " series"
			);
			if (verifyHttp) {
				assertBuildInfoScrape(server);
			}
		} finally {
			final Evita evita = server.getEvita();
			try {
				server.stop().get(30, TimeUnit.SECONDS);
			} finally {
				if (evita != null) {
					evita.close();
				}
				cleanupTestPaths(paths);
			}
		}
		assertEquals(baseline, countSeries(metricName), directory + " must release its " + metricName + " series");
	}

	/** Proves the healthy server serves HTTP using a family unaffected by other live servers' Armeria meters. */
	private static void assertBuildInfoScrape(@Nonnull EvitaServer server) throws Exception {
		final ObservabilityProvider provider = server.getExternalApiServer()
			.getExternalApiProviderByCode(ObservabilityProvider.CODE);
		assertNotNull(provider);
		final String metricsUrl = provider.getConfiguration().getBaseUrls()[0] + "metrics?name[]=io_evitadb_build";
		try (final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
			final HttpResponse<String> response = client.send(
				HttpRequest.newBuilder(URI.create(metricsUrl)).timeout(Duration.ofSeconds(30))
					.header("Accept", "text/plain").GET().build(),
				HttpResponse.BodyHandlers.ofString()
			);
			assertEquals(200, response.statusCode(), "Healthy build-info-filtered scrape must succeed");
			assertEquals(
				1L, response.body().lines().filter(line -> line.startsWith("io_evitadb_build_info{")).count(),
				"The filtered scrape must contain the evitaDB build series"
			);
		}
	}
}
