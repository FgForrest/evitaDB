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
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.observability.ObservabilityProvider;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.EvitaTestSupport;
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

/**
 * Exercises unfiltered Prometheus scrapes before and after fully closing a server in the same JVM.
 * Isolation prevents concurrent registration of JVM-wide metric handlers by unrelated server fixtures.
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

	/** A fully stopped server must release its globally registered Armeria metrics before the next boot. */
	@Test
	@DisplayName("Scrapes exactly one Armeria build series after a server restart")
	void shouldScrapeMetricsAfterServerRestartInSameJvm() throws Exception {
		final int[] ports = getPortManager().allocatePorts(TEST_DIRECTORY, 2);
		try (final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
			scrapeServer(client, TEST_DIRECTORY + "First", ports[0], "First");
			scrapeServer(client, TEST_DIRECTORY + "Second", ports[1], "Second");
		} finally {
			getPortManager().releasePorts(TEST_DIRECTORY);
		}
	}

	/** Starts only observability, scrapes without a metric filter, then fully stops and closes the server. */
	private void scrapeServer(
		@Nonnull HttpClient client, @Nonnull String directory, int port, @Nonnull String lifetime
	) throws Exception {
		final TestPaths paths = createTestPaths(directory);
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
		arguments.put("api.endpoints.observability.tlsMode", "FORCE_NO_TLS");
		final EvitaServer server = new EvitaServer(getPathInTargetDirectory(directory), arguments);
		try {
			server.run();
			final ObservabilityProvider provider = server.getExternalApiServer()
				.getExternalApiProviderByCode(ObservabilityProvider.CODE);
			assertNotNull(provider);
			final String metricsUrl = provider.getConfiguration().getBaseUrls()[0] + "metrics";
			final HttpResponse<String> response = client.send(
				HttpRequest.newBuilder(URI.create(metricsUrl)).timeout(Duration.ofSeconds(30))
					.header("Accept", "text/plain").GET().build(),
				HttpResponse.BodyHandlers.ofString()
			);
			assertEquals(200, response.statusCode(), lifetime + " unfiltered scrape must succeed");
			assertEquals(
				1L, response.body().lines().filter(line -> line.startsWith("armeria_build_info{")).count(),
				lifetime + " scrape must contain exactly one Armeria build series"
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
}
