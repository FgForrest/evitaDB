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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Isolated;

import javax.annotation.Nonnull;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static io.evitadb.test.TestTags.OBSERVABILITY;
import static io.evitadb.test.TestTags.OBSERVABILITY_API;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises Prometheus query filtering and negotiation through the production server's nested prefix routers.
 * Filtered scrapes retain the selected metric and exclude other metrics in the shared scrape registry.
 * Isolation prevents concurrent registration of JVM-wide metric handlers by other server fixtures.
 * Uses the server startup and port allocation fixture of EvitaServerTest and CustomCertificateDownloadTest.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Prometheus scrape routing over HTTP")
@Tag(OBSERVABILITY_API)
@Tag(OBSERVABILITY)
@Isolated
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrometheusEndpointRoutingTest implements EvitaTestSupport {
	/** Port allocation owner, shared only by the sequential methods of this isolated fixture. */
	private static final String TEST_DIRECTORY = "prometheusEndpointRoutingTest";
	/** Isolated engine directories. */
	private TestPaths paths;
	/** Production server including both observability prefix routing levels. */
	private EvitaServer server;
	/** Bounded socket client without transparent content negotiation. */
	private HttpClient client;
	/** Advertised metrics URL of the allocated server. */
	private String metricsUrl;

	/** Starts only the observability provider using the production server configuration path. */
	@BeforeAll
	void setUp() {
		this.paths = createTestPaths(TEST_DIRECTORY);
		final int port = getPortManager().allocatePorts(TEST_DIRECTORY, 1)[0];
		final Map<String, String> arguments = createHashMap(16);
		arguments.put("storage.storageDirectory", this.paths.storage().toString());
		arguments.put("storage.workDirectory", this.paths.work().toString());
		arguments.put("cache.enabled", "false");
		arguments.put("api.requestTimeoutInMillis", "10K");
		arguments.put("api.certificate.folderPath", this.paths.work().resolve("certificates").toString());
		ExternalApiServer.gatherExternalApiProviders().forEach(registrar -> arguments.put(
			"api.endpoints." + registrar.getExternalApiCode() + ".enabled",
			Boolean.toString(ObservabilityProvider.CODE.equals(registrar.getExternalApiCode()))
		));
		arguments.put("api.endpoints.observability.host", "localhost:" + port);
		arguments.put("api.endpoints.observability.exposeOn", "localhost:" + port);
		arguments.put("api.endpoints.observability.tlsMode", "FORCE_NO_TLS");
		this.server = new EvitaServer(getPathInTargetDirectory(TEST_DIRECTORY), arguments);
		this.server.run();
		final ObservabilityProvider provider = this.server.getExternalApiServer()
			.getExternalApiProviderByCode(ObservabilityProvider.CODE);
		assertNotNull(provider);
		this.metricsUrl = provider.getConfiguration().getBaseUrls()[0] + "metrics";
		this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	}

	/** Closes sockets and the engine before releasing the test directories. */
	@AfterAll
	void tearDown() throws Exception {
		if (this.client != null) {
			this.client.close();
		}
		if (this.server != null) {
			final Evita evita = this.server.getEvita();
			final CompletableFuture<Void> stopped = this.server.stop();
			getPortManager().releasePortsOnCompletion(TEST_DIRECTORY, stopped);
			stopped.get(30, TimeUnit.SECONDS);
			if (evita != null) {
				evita.close();
			}
		}
		cleanupTestPaths(this.paths);
	}

	/** Each filtered scrape proves its selected metric exists and excludes the metric selected by the other. */
	@Test
	@DisplayName("Filters each existing metric without returning the other")
	void shouldFilterMetricsUsingOriginalQuery() throws Exception {
		final String memoryMetric = "jvm_memory_used_bytes{";
		final String buildMetric = "io_evitadb_build_info{";
		final HttpResponse<String> memory = scrape("?name[]=jvm_memory_used_bytes", "text/plain");
		assertEquals(200, memory.statusCode(), "Memory-filtered scrape must succeed");
		assertTrue(memory.body().contains(memoryMetric), "Filtering must retain the selected memory metric");
		final HttpResponse<String> build = scrape("?name[]=io_evitadb_build", "text/plain");
		assertEquals(200, build.statusCode(), "Build-filtered scrape must succeed");
		assertTrue(build.body().contains(buildMetric), "Filtering must retain the selected build metric");
		assertFalse(memory.body().contains(buildMetric), "Filtering must exclude the known build metric");
		assertFalse(build.body().contains(memoryMetric), "Filtering must exclude the known memory metric");
	}

	/** Negotiation must read the Accept header's value rather than the names of all request headers. */
	@Test
	@DisplayName("Negotiates OpenMetrics from the Accept header")
	void shouldNegotiateOpenMetricsUsingHeaderValue() throws Exception {
		final HttpResponse<String> response = scrape(
			"?name[]=io_evitadb_build", "application/openmetrics-text;version=1.0.0"
		);
		assertEquals(200, response.statusCode());
		assertTrue(response.headers().firstValue("Content-Type").orElse("")
			.startsWith("application/openmetrics-text"), "Accept must select the OpenMetrics content type");
		assertTrue(response.body().endsWith("# EOF\n"), "OpenMetrics must include its end marker");
	}

	/** A filter selecting no metric yields an empty exposition, which is still a successful scrape. */
	@Test
	@DisplayName("Answers an empty scrape when the filter matches no metric")
	void shouldAnswerEmptyScrapeWhenFilterMatchesNothing() throws Exception {
		final HttpResponse<String> response = scrape("?name[]=no_such_metric", "text/plain");

		assertEquals(200, response.statusCode(), "A scrape matching no metric must still succeed");
		assertEquals("", response.body(), "A scrape matching no metric must have an empty body");
	}

	/** Prometheus servers accept gzip by default, and the exporter then compresses the body itself. */
	@Test
	@DisplayName("Compresses the scrape when the client accepts gzip")
	void shouldCompressScrapeWhenClientAcceptsGzip() throws Exception {
		final HttpResponse<byte[]> response = send(
			"GET", "?name[]=io_evitadb_build", "Accept", "text/plain", "Accept-Encoding", "gzip"
		);

		assertEquals(200, response.statusCode(), "A compressed scrape must succeed");
		assertEquals("gzip", response.headers().firstValue("Content-Encoding").orElse(""));
		try (final GZIPInputStream body = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
			assertTrue(
				new String(body.readAllBytes(), StandardCharsets.UTF_8).contains("io_evitadb_build_info{"),
				"The decompressed scrape must contain the selected metric"
			);
		}
	}

	/** The exporter's `debug` parameter renders the selected exposition format as plain text. */
	@Test
	@DisplayName("Serves the debug exposition for the debug parameter")
	void shouldServeDebugOutputWhenDebugParameterGiven() throws Exception {
		final HttpResponse<String> response = scrape("?debug=text&name[]=io_evitadb_build", "text/plain");

		assertEquals(200, response.statusCode(), "A debug scrape must succeed");
		assertTrue(
			response.body().contains("io_evitadb_build_info{"), "The debug scrape must contain the selected metric"
		);
	}

	/** A HEAD scrape answers with the scrape's status and headers and sends no body. */
	@Test
	@DisplayName("Answers a HEAD scrape without a body")
	void shouldAnswerHeadScrapeWithoutBody() throws Exception {
		final HttpResponse<byte[]> response = send("HEAD", "?name[]=io_evitadb_build", "Accept", "text/plain");

		assertEquals(200, response.statusCode(), "A HEAD scrape must succeed");
		assertEquals(0, response.body().length, "A HEAD scrape must have no body");
	}

	/** Sends the request through the real server without following redirects. */
	@Nonnull
	private HttpResponse<String> scrape(@Nonnull String query, @Nonnull String accept) throws Exception {
		return this.client.send(
			HttpRequest.newBuilder(URI.create(this.metricsUrl + query)).timeout(Duration.ofSeconds(30))
				.header("Accept", accept).GET().build(),
			HttpResponse.BodyHandlers.ofString()
		);
	}

	/**
	 * Sends a bodiless request with the given method and header name/value pairs, keeping the raw response bytes so
	 * that a compressed body can be decoded by the test itself.
	 */
	@Nonnull
	private HttpResponse<byte[]> send(
		@Nonnull String method, @Nonnull String query, @Nonnull String... headers
	) throws Exception {
		return this.client.send(
			HttpRequest.newBuilder(URI.create(this.metricsUrl + query)).timeout(Duration.ofSeconds(30))
				.headers(headers).method(method, HttpRequest.BodyPublishers.noBody()).build(),
			HttpResponse.BodyHandlers.ofByteArray()
		);
	}
}
