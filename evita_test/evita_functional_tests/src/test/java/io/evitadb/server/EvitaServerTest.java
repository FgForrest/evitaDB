/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.server;

import com.google.protobuf.Empty;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.Route;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.ExportOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.core.Evita;
import io.evitadb.driver.EvitaClient;
import io.evitadb.driver.config.ClientTlsOptions;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.externalApi.certificate.LoadedCertificates;
import io.evitadb.externalApi.certificate.ServerCertificateManager;
import io.evitadb.externalApi.certificate.ServerCertificateManager.CertificateType;
import io.evitadb.externalApi.configuration.AbstractApiOptions;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.CertificateOptions;
import io.evitadb.externalApi.configuration.CertificatePath;
import io.evitadb.externalApi.configuration.TlsMode;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.grpc.certificate.ClientCertificateManager;
import io.evitadb.externalApi.grpc.generated.EvitaManagementServiceGrpc.EvitaManagementServiceBlockingStub;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse;
import io.evitadb.externalApi.http.ExternalApiProviderRegistrar;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.lab.LabProvider;
import io.evitadb.externalApi.observability.ObservabilityProvider;
import io.evitadb.externalApi.rest.RestProvider;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.TestConstants;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.CertificateUtils;
import io.evitadb.utils.CollectionUtils.Property;
import io.evitadb.utils.NetworkUtils;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;

import static io.evitadb.utils.CollectionUtils.createHashMap;
import static io.evitadb.utils.CollectionUtils.createLinkedHashMap;
import static io.evitadb.utils.CollectionUtils.property;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.SERVER;
import static io.evitadb.test.TestTags.MANAGEMENT;

/**
 * This test tries to start up the {@link EvitaServer} with default configuration.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
@Tag(SERVER)
@Tag(MANAGEMENT)
class EvitaServerTest implements TestConstants, EvitaTestSupport {
	private static final String DIR_EVITA_SERVER_TEST = "evitaServerTest";
	public static final int TIMEOUT_IN_MILLIS = 30_000;
	/**
	 * Time budget for polling a health probe (`readiness` / `liveness` / `status`) until it settles.
	 * The probes are eventually-consistent right after start-up — a probe can briefly return a non-2xx
	 * status (surfaced as an empty result) or a transitional payload before the server settles — so a
	 * single request is prone to spurious failures. Retrying within this window removes the flakiness.
	 */
	private static final long PROBE_POLL_BUDGET_MILLIS = 20_000L;
	/**
	 * Documented TLS default of every endpoint. The values are written out instead of being read from the options
	 * classes, so a changed default fails the loader tests rather than moving the expectation.
	 */
	private static final Map<String, TlsMode> ENDPOINT_TLS_DEFAULTS = createLinkedHashMap(
		property(GraphQLProvider.CODE, TlsMode.FORCE_TLS),
		property(RestProvider.CODE, TlsMode.FORCE_TLS),
		property(GrpcProvider.CODE, TlsMode.FORCE_TLS),
		property(LabProvider.CODE, TlsMode.FORCE_TLS),
		property(SystemProvider.CODE, TlsMode.FORCE_NO_TLS),
		property(ObservabilityProvider.CODE, TlsMode.FORCE_NO_TLS)
	);

	@Nonnull
	private static String replaceVariables(@Nonnull String status) {
		String output = status;
		output = Pattern.compile("(\"serverName\": \"evitaDB-)(.+?)\"").matcher(output).replaceAll("$1RANDOM\"");
		output = Pattern.compile("(\"version\": \")((?:\\?)|(?:\\d{4}\\.\\d{1,2}(?:\\.[A-Za-z0-9]+)?(-SNAPSHOT)?))\"").matcher(output).replaceAll("$1VARIABLE\"");
		output = Pattern.compile("(\"startedAt\": \")(.+?)\"").matcher(output).replaceAll("$1VARIABLE\"");
		output = Pattern.compile("(\"introducedAt\": \")(.+?)\"").matcher(output).replaceAll("$1VARIABLE\"");
		output = Pattern.compile("(\"uptime\": )(.+?),").matcher(output).replaceAll("$1VARIABLE,");
		output = Pattern.compile("(\"uptimeForHuman\": \")(.+?)\"").matcher(output).replaceAll("$1VARIABLE\"");
		output = Pattern.compile("(//)(.+:[0-9]+)(/)").matcher(output).replaceAll("$1VARIABLE$3");
		return output;
	}

	/**
	 * Polls the given health-probe URL until the fetched body satisfies `accept` or the
	 * `budgetInMillis` window elapses. The `readiness` / `liveness` / `status` probes are
	 * eventually-consistent right after start-up, so a single request is prone to spurious failures: a
	 * probe can momentarily return a non-2xx status ({@link NetworkUtils#fetchContent} surfaces that as
	 * an empty result) or a transitional payload before the server settles. Retrying within a bounded
	 * window removes that flakiness without masking a genuinely unhealthy server — one that never
	 * satisfies `accept` returns the last observed result here and still fails the caller's assertions.
	 *
	 * @param url            the probe URL to fetch
	 * @param accept         predicate the fetched body must satisfy for the poll to stop early
	 * @param budgetInMillis the maximum time to keep retrying
	 * @return the last fetched content — present and accepted when the poll succeeded, otherwise the
	 *         last observed result so the caller's assertions report the actual state
	 */
	@Nonnull
	private static Optional<String> fetchContentUntil(
		@Nonnull String url,
		@Nonnull Predicate<String> accept,
		long budgetInMillis
	) {
		final long start = System.currentTimeMillis();
		Optional<String> content;
		do {
			log.info("Probing {}", url);
			content = NetworkUtils.fetchContent(
				url,
				"GET",
				"application/json",
				null,
				TIMEOUT_IN_MILLIS,
				error -> log.error("Error while probing API: {}", error),
				timeout -> log.error("Timeout while probing API: {}", timeout)
			);
			if (content.isPresent() && accept.test(content.get())) {
				return content;
			}
		} while (System.currentTimeMillis() - start < budgetInMillis);
		return content;
	}

	/**
	 * Retrieves the value from a nested map using a dot-separated key string.
	 *
	 * @param map The map from which the value is to be retrieved.
	 * @param key The dot-separated key string used to navigate the nested maps.
	 * @return The value associated with the specified key, or null if the key does not exist.
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	private static Object getMapValue(@Nonnull Map<String, Object> map, @Nonnull String key) {
		final String[] keysArray = key.split("\\.");
		Map<String, Object> tempMap = map;

		for (int i = 0; i < keysArray.length - 1; i++) {
			Object value = tempMap.get(keysArray[i]);
			if (value instanceof Map) {
				tempMap = (Map<String, Object>) value;
			} else {
				return null;
			}
		}
		return tempMap.get(keysArray[keysArray.length - 1]);
	}

	/**
	 * Converts a given immutable map into a mutable HashMap, recursively converting any nested immutable maps as well.
	 *
	 * @param immutableMap the immutable map to be converted
	 * @return a mutable HashMap with the same entries as the specified immutable map
	 */
	@SuppressWarnings("unchecked")
	@Nonnull
	private static Map<String, Object> convertImmutableMapsToHashMaps(@Nonnull Map<String, Object> immutableMap) {
		Map<String, Object> result = new HashMap<>();
		for (Map.Entry<String, Object> entry : immutableMap.entrySet()) {
			Object value = entry.getValue();
			if (value instanceof Map) {
				value = convertImmutableMapsToHashMaps((Map<String, Object>) value);
			}
			result.put(entry.getKey(), value);
		}
		return result;
	}

	@BeforeEach
	void setUp() throws IOException {
		cleanTestSubDirectory(DIR_EVITA_SERVER_TEST);
	}

	@AfterEach
	void tearDown() throws IOException {
		cleanTestSubDirectory(DIR_EVITA_SERVER_TEST);
	}

	@Test
	void shouldReplaceEndpointVariables() {
		final Map<String, Object> endpointDefaults = Map.of(
			"enabled", false,
			"exposeOn", "http://whatnot:7787"
		);
		final Map<String, Object> initialMap = Map.of(
			"api", Map.of(
				"endpoints", Map.of(
					"rest", Map.of(
						"enabled", true,
						"exposeOn", "http://localhost:5555"
					),
					"system", Map.of(
						"exposeOn", "http://localhost:5556"
					),
					"graphQL", Map.of(
						"enabled", true
					),
					"lab", Map.of(
						"tlsMode", "FORCE_TLS"
					)
				)
			)
		);

		final Map<String, Object> configuration = convertImmutableMapsToHashMaps(initialMap);
		EvitaServer.applyEndpointDefaults(configuration, endpointDefaults);

		assertNull(getMapValue(configuration, "api.endpointDefaults"));
		assertEquals(true, getMapValue(configuration, "api.endpoints.rest.enabled"));
		assertEquals("http://localhost:5555", getMapValue(configuration, "api.endpoints.rest.exposeOn"));
		assertEquals(false, getMapValue(configuration, "api.endpoints.system.enabled"));
		assertEquals("http://localhost:5556", getMapValue(configuration, "api.endpoints.system.exposeOn"));
		assertEquals(true, getMapValue(configuration, "api.endpoints.graphQL.enabled"));
		assertEquals("http://whatnot:7787", getMapValue(configuration, "api.endpoints.graphQL.exposeOn"));
		assertEquals(false, getMapValue(configuration, "api.endpoints.lab.enabled"));
		assertEquals("http://whatnot:7787", getMapValue(configuration, "api.endpoints.lab.exposeOn"));
		assertEquals("FORCE_TLS", getMapValue(configuration, "api.endpoints.lab.tlsMode"));
	}

	@Test
	void shouldStartAndStopTlsServerCorrectly() {
		final Map<String, Integer> servicePorts = new HashMap<>();
		final EvitaServer evitaServer = new EvitaServer(
			getPathInTargetDirectory(DIR_EVITA_SERVER_TEST),
			constructTestArguments(servicePorts)
		);
		try {
			evitaServer.run();

			final Evita evita = evitaServer.getEvita();
			evita.defineCatalog(TEST_CATALOG);
			assertFalse(evita.getConfiguration().cache().enabled());

			final EvitaClient evitaClient = new EvitaClient(
				EvitaClientConfiguration.builder()
					.host("localhost")
					.port(servicePorts.get(GrpcProvider.CODE))
					.systemApiPort(servicePorts.get(SystemProvider.CODE))
					.build()
			);

			final ExternalApiServer externalApiServer = evitaServer.getExternalApiServer();
			final GrpcProvider grpcProvider = externalApiServer.getExternalApiProviderByCode(GrpcProvider.CODE);
			assertNotNull(grpcProvider);

			final EvitaSessionContract session = evitaClient.createReadWriteSession(TEST_CATALOG);
			assertNotNull(session);
			evitaClient.terminateSession(session);
			assertFalse(session.isActive());

		} catch (Exception ex) {
			fail(ex);
		} finally {
			closeServerAndEvita(evitaServer);
		}
	}

	/** Closes the server backed by the shared server-test port allocation. */
	private void closeServerAndEvita(@Nonnull EvitaServer evitaServer) {
		closeServerAndEvita(evitaServer, DIR_EVITA_SERVER_TEST);
	}

	/** Closes a server and returns the ports allocated for its fixture. */
	private void closeServerAndEvita(@Nonnull EvitaServer evitaServer, @Nonnull String portDataSetName) {
		try {
			final Evita evita = evitaServer.getEvita();
			if (evita != null) {
				evita.deleteCatalogIfExists(TEST_CATALOG);
			}
			getPortManager().releasePortsOnCompletion(portDataSetName, evitaServer.stop());
			if (evita != null) {
				evita.close();
			}
		} catch (Exception ex) {
			fail(ex.getMessage(), ex);
		}
	}

	/** Verifies method negotiation through the real server's system and lab routers. */
	@Test
	@DisplayName("Should answer 405 with Allow for unsupported system and lab methods")
	void shouldRejectUnsupportedSystemAndLabMethods() {
		final Map<String, Integer> servicePorts = createHashMap(6);
		final EvitaServer evitaServer = new EvitaServer(
			getPathInTargetDirectory(DIR_EVITA_SERVER_TEST),
			constructTestArguments(
				servicePorts,
				List.of(property("api.endpoints.lab.tlsMode", "FORCE_NO_TLS")),
				SystemProvider.CODE, LabProvider.CODE
			)
		);
		try {
			evitaServer.run();
			final AggregatedHttpResponse systemResponse = WebClient.of(
				"http://localhost:" + servicePorts.get(SystemProvider.CODE)
			).blocking().post("/system/server-name", "");
			assertEquals(405, systemResponse.status().code());
			assertEquals("GET", systemResponse.headers().get("Allow"));

			final AggregatedHttpResponse labResponse = WebClient.of(
				"http://localhost:" + servicePorts.get(LabProvider.CODE)
			).blocking().post("/lab/", "");
			assertEquals(405, labResponse.status().code());
			assertEquals("OPTIONS, GET", labResponse.headers().get("Allow"));
		} finally {
			closeServerAndEvita(evitaServer);
		}
	}

	@Test
	void shouldRestrictAccessViaNonSupportedProtocolsAndPortsExceptLab() {
		final Map<String, Integer> servicePorts = new HashMap<>();
		final EvitaServer evitaServer = new EvitaServer(
			getPathInTargetDirectory(DIR_EVITA_SERVER_TEST),
			constructTestArguments(
				servicePorts,
				List.of(
					property("api.endpoints.rest.tlsMode", "RELAXED"),
					property("api.endpoints.graphQL.tlsMode", "FORCE_NO_TLS"),
					property("api.endpoints.gRPC.tlsMode", "FORCE_NO_TLS"),
					property("api.endpoints.lab.tlsMode", "RELAXED")
				),
				Set.of(
					RestProvider.CODE, GraphQLProvider.CODE, SystemProvider.CODE
				)
			)
		);

		try {
			evitaServer.run();

			// attempt to access the system API via GraphQL port
			NetworkUtils.fetchContent(
				"http://localhost:" + servicePorts.get(ObservabilityProvider.CODE) + "/system/server-name",
				"GET",
				"text/plain",
				null,
				TIMEOUT_IN_MILLIS,
				error -> assertEquals("Error fetching content from URL: http://localhost:" + servicePorts.get(ObservabilityProvider.CODE) + "/system/server-name HTTP status 404 - Not Found: Service not available.", error),
				timeout -> assertEquals("Error fetching content from URL: http://localhost:" + servicePorts.get(ObservabilityProvider.CODE) + "/system/server-name HTTP status 404 - Not Found: Service not available.", timeout)
			).ifPresent(
				content -> fail("The system API is accessible via Observability port: " + content)
			);

			// attempt to access the system API via invalid scheme and correct port
			NetworkUtils.fetchContent(
				"https://localhost:" + servicePorts.get(SystemProvider.CODE) + "/system/server-name",
				"GET",
				"text/plain",
				null,
				TIMEOUT_IN_MILLIS,
				error -> assertEquals("Error fetching content from URL: https://localhost:" + servicePorts.get(SystemProvider.CODE) + "/system/server-name HTTP status 403: This endpoint does not support TLS.", error),
				timeout -> assertEquals("Error fetching content from URL: http://localhost:" + servicePorts.get(ObservabilityProvider.CODE) + "/system/server-name HTTP status 404 - Not Found: Service not available.", timeout)
			).ifPresent(
				content -> fail("The system API is accessible via invalid scheme: " + content)
			);

			// attempt to access the system API via both invalid scheme and port
			NetworkUtils.fetchContent(
				"https://localhost:" + servicePorts.get(ObservabilityProvider.CODE) + "/system/server-name",
				"GET",
				"text/plain",
				null,
				TIMEOUT_IN_MILLIS,
				error -> assertTrue(error.contains("Error fetching content from URL: https://localhost:" + servicePorts.get(ObservabilityProvider.CODE) + "/system/server-name")),
				timeout -> assertEquals("Error fetching content from URL: http://localhost:" + servicePorts.get(ObservabilityProvider.CODE) + "/system/server-name HTTP status 404 - Not Found: Service not available.", timeout)
			).ifPresent(
				content -> fail("The system API is accessible via invalid scheme and Observability port: " + content)
			);

			// attempt to access the system API via correct scheme and port
			NetworkUtils.fetchContent(
				"http://localhost:" + servicePorts.get(SystemProvider.CODE) + "/system/server-name",
				"GET",
				"text/plain",
				null,
				TIMEOUT_IN_MILLIS,
				error -> fail("The system API should be accessible via correct scheme and port: " + error),
				timeout -> assertEquals("Error fetching content from URL: http://localhost:" + servicePorts.get(ObservabilityProvider.CODE) + "/system/server-name HTTP status 404 - Not Found: Service not available.", timeout)
			).ifPresent(
				content -> assertTrue(content.contains("evitaDB-"), "The system API should be accessible via correct scheme and port: " + content)
			);

			// attempt to access the system API via correct scheme and Lab port
			NetworkUtils.fetchContent(
				"http://localhost:" + servicePorts.get(LabProvider.CODE) + "/system/server-name",
				"GET",
				"text/plain",
				null,
				TIMEOUT_IN_MILLIS,
				error -> fail("The system API should be accessible via Lab scheme and port: " + error),
				timeout -> assertEquals("Error fetching content from URL: http://localhost:" + servicePorts.get(ObservabilityProvider.CODE) + "/system/server-name HTTP status 404 - Not Found: Service not available.", timeout)
			).ifPresent(
				content -> assertTrue(content.contains("evitaDB-"), "The system API should be accessible via Lab scheme and port: " + content)
			);

			// attempt to access the system API via correct scheme and Lab port
			NetworkUtils.fetchContent(
				"https://localhost:" + servicePorts.get(LabProvider.CODE) + "/system/server-name",
				"GET",
				"text/plain",
				null,
				TIMEOUT_IN_MILLIS,
				error -> fail("The system API should be accessible via Lab scheme and port: " + error),
				timeout -> assertEquals("Error fetching content from URL: http://localhost:" + servicePorts.get(ObservabilityProvider.CODE) + "/system/server-name HTTP status 404 - Not Found: Service not available.", timeout)
			).ifPresent(
				content -> assertTrue(content.contains("evitaDB-"), "The system API should be accessible via Lab scheme and port: " + content)
			);

			final EvitaClient evitaClientBadPort = new EvitaClient(
				EvitaClientConfiguration.builder()
					.host("localhost")
					.port(servicePorts.get(ObservabilityProvider.CODE))
					.systemApiPort(servicePorts.get(SystemProvider.CODE))
					.tls(ClientTlsOptions.builder().tlsEnabled(false).build())
					.build()
			);

			try {
				evitaClientBadPort.getCatalogNames();
				fail("gRPC call should have failed on bad port!");
			} catch (Exception ex) {
				assertEquals("UNIMPLEMENTED: HTTP status code 404", ex.getMessage());
			}

			final EvitaClient evitaClientBadScheme = new EvitaClient(
				EvitaClientConfiguration.builder()
					.host("localhost")
					.port(servicePorts.get(GrpcProvider.CODE))
					.systemApiPort(servicePorts.get(SystemProvider.CODE))
					.build()
			);

			try {
				evitaClientBadScheme.getCatalogNames();
				fail("gRPC call should have failed on bad scheme!");
			} catch (Exception ex) {
				assertEquals("UNAVAILABLE", ex.getMessage());
			}

			// we should be able to access gRCP via correct scheme and port
			final EvitaClient correctEvitaClient = new EvitaClient(
				EvitaClientConfiguration.builder()
					.host("localhost")
					.port(servicePorts.get(GrpcProvider.CODE))
					.systemApiPort(servicePorts.get(SystemProvider.CODE))
					.tls(ClientTlsOptions.builder().tlsEnabled(false).build())
					.build()
			);

			assertEquals(0, correctEvitaClient.getCatalogNames().size());

			// we should be able to access gRCP via Lab scheme and port
			final EvitaClient labPortEvitaClient = new EvitaClient(
				EvitaClientConfiguration.builder()
					.host("localhost")
					.port(servicePorts.get(LabProvider.CODE))
					.systemApiPort(servicePorts.get(SystemProvider.CODE))
					.tls(ClientTlsOptions.builder().tlsEnabled(false).build())
					.build()
			);

			assertEquals(0, labPortEvitaClient.getCatalogNames().size());

			// we should be able to access gRCP via Lab scheme and port
			final EvitaClient labPortEvitaClientDifferentScheme = new EvitaClient(
				EvitaClientConfiguration.builder()
					.host("localhost")
					.port(servicePorts.get(LabProvider.CODE))
					.systemApiPort(servicePorts.get(SystemProvider.CODE))
					.build()
			);

			assertEquals(0, labPortEvitaClientDifferentScheme.getCatalogNames().size());
		} finally {
			closeServerAndEvita(evitaServer);
		}
	}

	@Test
	@SneakyThrows
	void shouldRestrictAccessWhenMtlsConfiguredOnlyOnTlsServicesOfRpcType() {
		final Map<String, Integer> servicePorts = new HashMap<>();
		final EvitaServer evitaServer = new EvitaServer(
			getPathInTargetDirectory(DIR_EVITA_SERVER_TEST),
			constructTestArguments(
				servicePorts,
				List.of(
					property("api.endpoints.rest.tlsMode", "RELAXED"),
					property("api.endpoints.rest.mTLS.enabled", "true"),
					property("api.endpoints.rest.mTLS.allowedClientCertificatesPaths", "[\""+getGeneratedOtherCertificateFileName()+"\"]"),
					property("api.endpoints.graphQL.tlsMode", "RELAXED"),
					property("api.endpoints.gRPC.tlsMode", "RELAXED"),
					property("api.endpoints.gRPC.mTLS.enabled", "true"),
					property("api.endpoints.gRPC.mTLS.allowedClientCertificatesPaths", "[\""+CertificateUtils.getGeneratedClientCertificateFileName()+"\"]"),
					property("api.endpoints.lab.tlsMode", "RELAXED"),
					property("api.endpoints.observability.enabled", "false")
				),
				Set.of(
					RestProvider.CODE, GraphQLProvider.CODE, SystemProvider.CODE
				)
			)
		);

		try {
			generateTestCertificate("evita-server-certificates");

			evitaServer.run();

			NetworkUtils.fetchContent(
				"http://localhost:" + servicePorts.get(SystemProvider.CODE) + "/system/server-name",
				"GET",
				"text/plain",
				null,
				TIMEOUT_IN_MILLIS,
				error -> fail("The system API should be accessible via Lab scheme and port: " + error),
				timeout -> assertEquals("Error fetching content from URL: http://localhost:" + servicePorts.get(ObservabilityProvider.CODE) + "/system/server-name HTTP status 404 - Not Found: Service not available.", timeout)
			).ifPresent(
				content -> assertTrue(content.contains("evitaDB-"), "The system API should be accessible via Lab scheme and port: " + content)
			);

			// we should be able also to access the system API with trailing slash
			NetworkUtils.fetchContent(
				"http://localhost:" + servicePorts.get(SystemProvider.CODE) + "/system/server-name/",
				"GET",
				"text/plain",
				null,
				TIMEOUT_IN_MILLIS,
				error -> fail("The system API should be accessible via Lab scheme and port: " + error),
				timeout -> assertEquals("Error fetching content from URL: http://localhost:" + servicePorts.get(ObservabilityProvider.CODE) + "/system/server-name/ HTTP status 404 - Not Found: Service not available.", timeout)
			).ifPresent(
				content -> assertTrue(content.contains("evitaDB-"), "The system API should be accessible via Lab scheme and port: " + content)
			);

			NetworkUtils.fetchContent(
				"https://localhost:" + servicePorts.get(GraphQLProvider.CODE) + "/gql/system",
				"POST",
				"application/json",
				"{\"query\":\"{catalogs{__typename}}\"}",
				TIMEOUT_IN_MILLIS,
				error -> fail("The system API should be accessible via Lab scheme and port: " + error),
				timeout -> assertEquals("Error fetching content from URL: http://localhost:" + servicePorts.get(ObservabilityProvider.CODE) + "/system/server-name HTTP status 404 - Not Found: Service not available.", timeout)
			).ifPresent(
				content -> assertTrue(content.contains("{\"data\":{\"catalogs\":[]}}"), "The system API should be accessible via Lab scheme and port: " + content)
			);

			final ClientCertificateManager invalidClientCertificateWithClientCertificateManager = new ClientCertificateManager.Builder()
				.useGeneratedCertificate(false, "localhost", servicePorts.get(RestProvider.CODE))
				.usingTrustedServerCertificate(false)
				.mtls(true)
				.certificateClientFolderPath(Path.of("evita-server-certificates"))
				.serverCertificateFilePath(Path.of(CertificateUtils.getGeneratedServerCertificateFileName()))
				.clientCertificateFilePath(Path.of(getGeneratedOtherCertificateFileName()))
				.clientPrivateKeyFilePath(Path.of(getGeneratedOtherCertificateKeyFileName()))
				.build();

			final ClientFactory clientFactory = invalidClientCertificateWithClientCertificateManager.buildClientSslContext(
				null,
				ClientFactory.builder()
					.workerGroup(Runtime.getRuntime().availableProcessors() / 4)
					.idleTimeoutMillis(TimeUnit.MILLISECONDS.convert(5, TimeUnit.SECONDS))
				).build();

			final WebClient httpWebClient = WebClient.builder("http://localhost:" + servicePorts.get(RestProvider.CODE))
				.factory(clientFactory)
				.build();

			final AggregatedHttpResponse httpResponse = httpWebClient.blocking().get("/rest/system");
			assertTrue(httpResponse.contentUtf8().contains("\"openapi\" :"), "The system API should be accessible via Lab scheme and port: " + httpResponse.contentUtf8());

			final WebClient httpsWebClient = WebClient.builder("https://localhost:" + servicePorts.get(RestProvider.CODE))
				.factory(clientFactory)
				.build();

			final AggregatedHttpResponse httpsResponse = httpsWebClient.blocking().get("/rest/system");
			assertTrue(httpsResponse.contentUtf8().contains("\"openapi\" :"), "The system API should be accessible via Lab scheme and port: " + httpResponse.contentUtf8());

			NetworkUtils.fetchContent(
				"https://localhost:" + servicePorts.get(RestProvider.CODE) + "/rest/system",
				"GET",
				"application/json",
				null,
				TIMEOUT_IN_MILLIS,
				error -> assertTrue(error.contains("HTTP status 401: Client certificate not provided.")),
				timeout -> assertEquals("Error fetching content from URL: http://localhost:" + servicePorts.get(ObservabilityProvider.CODE) + "/system/server-name HTTP status 404 - Not Found: Service not available.", timeout)
			).ifPresent(
				content -> assertTrue(content.contains("\"openapi\" :" ), "The system API should be accessible via Lab scheme and port: " + content)
			);

			final EvitaClient notAllowedCertificate = new EvitaClient(
				EvitaClientConfiguration.builder()
					.host("localhost")
					.port(servicePorts.get(GrpcProvider.CODE))
					.systemApiPort(servicePorts.get(SystemProvider.CODE))
					.tls(
						ClientTlsOptions.builder()
							.tlsEnabled(true)
							.mtlsEnabled(true)
							.certificateFolderPath(Path.of("evita-server-certificates"))
							.serverCertificatePath(Path.of(CertificateUtils.getGeneratedServerCertificateFileName()))
							.certificateFileName(Path.of(getGeneratedOtherCertificateFileName()))
							.certificateKeyFileName(Path.of(getGeneratedOtherCertificateKeyFileName()))
							.useGeneratedCertificate(false)
							.trustCertificate(false)
							.build()
					)
					.build()
			);

			try {
				notAllowedCertificate.getCatalogNames();
				fail("gRPC call should should be made with allowed client certificate!");
			} catch (Exception ex) {
				assertEquals("PERMISSION_DENIED: HTTP status code 403", ex.getMessage());
			}

			// we should be able to access gRCP via correct scheme and port
			final EvitaClient correctEvitaClient = new EvitaClient(
				EvitaClientConfiguration.builder()
					.host("localhost")
					.port(servicePorts.get(GrpcProvider.CODE))
					.systemApiPort(servicePorts.get(SystemProvider.CODE))
					.tls(
						ClientTlsOptions.builder()
							.tlsEnabled(true)
							.mtlsEnabled(true)
							.certificateFolderPath(Path.of("evita-server-certificates"))
							.serverCertificatePath(Path.of(CertificateUtils.getGeneratedServerCertificateFileName()))
							.certificateFileName(Path.of(CertificateUtils.getGeneratedClientCertificateFileName()))
							.certificateKeyFileName(Path.of(CertificateUtils.getGeneratedClientCertificatePrivateKeyFileName()))
							.useGeneratedCertificate(false)
							.trustCertificate(false)
							.build()
					)
					.build()
			);

			assertEquals(0, correctEvitaClient.getCatalogNames().size());

		} finally {
			closeServerAndEvita(evitaServer);
		}
	}

	@Test
	void shouldReloadUsedCertificateAfterCertificateFileChanged() {
		final Map<String, Integer> servicePorts = new HashMap<>();
		final EvitaServer evitaServer = new EvitaServer(
			getPathInTargetDirectory(DIR_EVITA_SERVER_TEST),
			constructTestArguments(
				servicePorts,
				List.of(
					property("api.endpoints.rest.tlsMode", "RELAXED"),
					property("api.endpoints.rest.mTLS.enabled", "true"),
					property("api.endpoints.graphQL.tlsMode", "RELAXED"),
					property("api.endpoints.gRPC.tlsMode", "RELAXED"),
					property("api.endpoints.gRPC.mTLS.enabled", "true"),
					property("api.endpoints.gRPC.mTLS.allowedClientCertificatesPaths", "[\""+CertificateUtils.getGeneratedClientCertificateFileName()+"\"]"),
					property("api.endpoints.lab.tlsMode", "RELAXED"),
					property("api.endpoints.observability.enabled", "false")
				),
				Set.of(
					RestProvider.CODE, GraphQLProvider.CODE, SystemProvider.CODE
				)
			)
		);

		try {
			evitaServer.run();

			final ExternalApiServer externalApiServer = evitaServer.getExternalApiServer();
			final LoadedCertificates loadedCertificates = externalApiServer.getLoadedCertificates();
			assertNotNull(loadedCertificates);
			final TlsKeyPair tlsKeyPair = loadedCertificates.tlsKeyPair();

			assertFalse(externalApiServer.reloadCertificatesIfAnyModified());

			final ServerCertificateManager serverCertificateManager = new ServerCertificateManager(
				new CertificateOptions(true, "./evita-server-certificates/", new CertificatePath(null, null, null))
			);
			serverCertificateManager.generateSelfSignedCertificate(CertificateType.CLIENT, CertificateType.SERVER);

			assertTrue(externalApiServer.reloadCertificatesIfAnyModified());

			final LoadedCertificates newLoadedCertificates = externalApiServer.getLoadedCertificates();
			assertNotNull(newLoadedCertificates);
			final TlsKeyPair newTlsKeyPair = newLoadedCertificates.tlsKeyPair();
			assertNotEquals(tlsKeyPair, newTlsKeyPair);
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			closeServerAndEvita(evitaServer);
		}
	}

	@Test
	void shouldBeAbleToGetGrpcWebResponse() {
		final Map<String, Integer> servicePorts = new HashMap<>();
		final EvitaServer evitaServer = new EvitaServer(
			getPathInTargetDirectory(DIR_EVITA_SERVER_TEST),
			constructTestArguments(servicePorts, GrpcProvider.CODE, SystemProvider.CODE)
		);
		try {
			evitaServer.run();

			final Evita evita = evitaServer.getEvita();
			evita.defineCatalog(TEST_CATALOG);
			assertFalse(evita.getConfiguration().cache().enabled());

			long uptime = 0;
			for (int i = 0; i < 5; i++) {
				final GrpcEvitaServerStatusResponse grpcEvitaServerStatusResponse = GrpcClients.builder("gproto-web+https://localhost:" + servicePorts.get(GrpcProvider.CODE))
					.factory(ClientFactory.insecure())
					.build(EvitaManagementServiceBlockingStub.class)
					.serverStatus(Empty.newBuilder().build());

				assertNotNull(grpcEvitaServerStatusResponse);
				uptime = grpcEvitaServerStatusResponse.getUptime();
				if (uptime > 0) {
					break;
				} else {
					synchronized(this) {
						TimeUnit.SECONDS.sleep(1);
					}
				}
			}

			assertTrue(uptime > 0);
		} catch (Exception ex) {
			fail(ex);
		} finally {
			closeServerAndEvita(evitaServer);
		}
	}

	@Test
	void shouldStartAndStopPlainServerCorrectly() {
		final Map<String, Integer> servicePorts = new HashMap<>();
		final EvitaServer evitaServer = new EvitaServer(
			getPathInTargetDirectory(DIR_EVITA_SERVER_TEST),
			constructTestArguments(servicePorts, List.of(property("api.endpoints.gRPC.tlsMode", "FORCE_NO_TLS")))
		);
		try {
			evitaServer.run();

			final Evita evita = evitaServer.getEvita();
			evita.defineCatalog(TEST_CATALOG);
			assertFalse(evita.getConfiguration().cache().enabled());

			final EvitaClient evitaClient = new EvitaClient(
				EvitaClientConfiguration.builder()
					.host("localhost")
					.port(servicePorts.get(GrpcProvider.CODE))
					.systemApiPort(servicePorts.get(SystemProvider.CODE))
					.tls(ClientTlsOptions.builder().tlsEnabled(false).build())
					.build()
			);

			final EvitaSessionContract session = evitaClient.createReadWriteSession(TEST_CATALOG);
			assertNotNull(session);
			evitaClient.terminateSession(session);
			assertFalse(session.isActive());

		} catch (Exception ex) {
			fail(ex);
		} finally {
			closeServerAndEvita(evitaServer);
		}
	}

	@Test
	void shouldSignalizeReadinessAndHealthinessCorrectly() {
		final EvitaServer evitaServer = new EvitaServer(
			getPathInTargetDirectory(DIR_EVITA_SERVER_TEST),
			constructTestArguments(GrpcProvider.CODE, GraphQLProvider.CODE, RestProvider.CODE, SystemProvider.CODE, LabProvider.CODE, ObservabilityProvider.CODE)
		);
		try {
			evitaServer.run();
			final String[] baseUrls = evitaServer.getExternalApiServer().getExternalApiProviderByCode(SystemProvider.CODE)
				.getConfiguration()
				.getBaseUrls();

			final Optional<String> readiness = fetchContentUntil(
				baseUrls[0] + "readiness",
				content -> content.contains("\"status\": \"READY\""),
				PROBE_POLL_BUDGET_MILLIS
			);

			assertTrue(readiness.isPresent());
			assertEquals(
				"""
					{
						"status": "READY",
						"apis": {
							"gRPC": "ready",
							"graphQL": "ready",
							"lab": "ready",
							"observability": "ready",
							"rest": "ready",
							"system": "ready"
						}
					}""",
				readiness.get().trim()
			);

			final Optional<String> liveness = fetchContentUntil(
				baseUrls[0] + "liveness",
				content -> content.contains("\"status\": \"healthy\""),
				PROBE_POLL_BUDGET_MILLIS
			);

			assertTrue(liveness.isPresent());
			assertEquals(
				"{\"status\": \"healthy\"}",
				liveness.get().trim()
			);

			final Optional<String> status = fetchContentUntil(
				baseUrls[0] + "status",
				content -> content.contains("\"serverName\""),
				PROBE_POLL_BUDGET_MILLIS
			);

			assertTrue(status.isPresent());
			final String output = replaceVariables(status.get());
			assertEquals(
				"""
					{
					   "serverName": "evitaDB-RANDOM",
					   "version": "VARIABLE",
					   "startedAt": "VARIABLE",
					   "uptime": VARIABLE,
					   "uptimeForHuman": "VARIABLE",
					   "engineVersion": 1,
					   "introducedAt": "VARIABLE",
					   "catalogsCorrupted": 0,
					   "catalogsActive": 0,
					   "catalogsInactive": 0,
					   "healthProblems": [],
					   "apis": [
					      {
					         "gRPC": [
					            "https://VARIABLE/",
					            "https://VARIABLE/"
					         ]
					      },
					      {
					         "graphQL": [
					            "https://VARIABLE/gql/",
					            "https://VARIABLE/gql/"
					         ]
					      },
					      {
					         "lab": [
					            "https://VARIABLE/lab/",
					            "https://VARIABLE/lab/"
					         ]
					      },
					      {
					         "observability": [
					            "http://VARIABLE/observability/",
					            "http://VARIABLE/observability/"
					         ]
					      },
					      {
					         "rest": [
					            "https://VARIABLE/rest/",
					            "https://VARIABLE/rest/"
					         ]
					      },
					      {
					         "system": [
					            "http://VARIABLE/system/",
					            "http://VARIABLE/system/"
					         ]
					      }
					   ]
					}""",
				output,
				"Original output: " + status.get()
			);

		} catch (Exception ex) {
			fail(ex);
		} finally {
			closeServerAndEvita(evitaServer);
		}
	}

	@Test
	void shouldResolveMillisecondNamedPropertiesFromBundledDefaultsCorrectly() {
		// regression guard: SpecialConfigInputFormatsHandler must scale the seconds-based TIME_FORMAT shorthand
		// (e.g. "1m") to milliseconds for properties whose name ends in Millis/Milliseconds. Before the fix,
		// the bundled default `trafficFlushIntervalInMilliseconds: 1m` silently resolved to `60` (60 milliseconds)
		// instead of the intended `60000` (1 minute) - a 1000x more frequent flush than intended whenever traffic
		// recording is enabled with the shipped default.
		final EvitaServer evitaServer = new EvitaServer(
			getPathInTargetDirectory(DIR_EVITA_SERVER_TEST),
			constructTestArguments()
		);
		try {
			evitaServer.run();

			final Evita evita = evitaServer.getEvita();
			assertEquals(5_000L, evita.getConfiguration().server().queryTimeoutInMilliseconds());
			assertEquals(300_000L, evita.getConfiguration().server().transactionTimeoutInMilliseconds());
			assertEquals(60_000L, evita.getConfiguration().server().trafficRecording().trafficFlushIntervalInMilliseconds());
			// same shorthand-resolution mechanism backs the new compaction cadence knobs
			assertEquals(60_000L, evita.getConfiguration().storage().minCompactionIntervalMilliseconds());
			assertEquals(0.1, evita.getConfiguration().storage().maxWasteActiveShare());
		} catch (Exception ex) {
			fail(ex);
		} finally {
			closeServerAndEvita(evitaServer);
		}
	}

	@Test
	void shouldResolveGraphQLDocumentCacheSizeFromConfiguration() {
		// the size shorthand (`1KB`) passed as a property override must reach the GraphQL options as bytes
		final EvitaServer evitaServer = new EvitaServer(
			getPathInTargetDirectory(DIR_EVITA_SERVER_TEST),
			constructTestArguments(
				new HashMap<>(),
				List.of(property("api.endpoints.graphQL.documentCacheSizeInBytes", "1KB")),
				Set.of(GraphQLProvider.CODE)
			)
		);
		try {
			evitaServer.run();

			final GraphQLProvider graphQLProvider = evitaServer.getExternalApiServer()
				.getExternalApiProviderByCode(GraphQLProvider.CODE);
			assertNotNull(graphQLProvider);
			assertEquals(1024L, graphQLProvider.getConfiguration().getDocumentCacheSizeInBytes());
		} catch (Exception ex) {
			fail(ex);
		} finally {
			closeServerAndEvita(evitaServer);
		}
	}

	@Test
	void shouldResolveGraphQLQueryComplexityLimitsFromConfiguration() {
		// the limits passed as property overrides must reach the GraphQL options
		final EvitaServer evitaServer = new EvitaServer(
			getPathInTargetDirectory(DIR_EVITA_SERVER_TEST),
			constructTestArguments(
				new HashMap<>(),
				List.of(
					property("api.endpoints.graphQL.maxQueryDepth", "15"),
					property("api.endpoints.graphQL.maxQueryFieldsCount", "2000")
				),
				Set.of(GraphQLProvider.CODE)
			)
		);
		try {
			evitaServer.run();

			final GraphQLProvider graphQLProvider = evitaServer.getExternalApiServer()
				.getExternalApiProviderByCode(GraphQLProvider.CODE);
			assertNotNull(graphQLProvider);
			assertEquals(15, graphQLProvider.getConfiguration().getMaxQueryDepth());
			assertEquals(2000, graphQLProvider.getConfiguration().getMaxQueryFieldsCount());
		} catch (Exception ex) {
			fail(ex);
		} finally {
			closeServerAndEvita(evitaServer);
		}
	}

	/**
	 * A user configuration that leaves `parallelize` absent, empty or `null` must keep the documented default of
	 * parallel GraphQL fetching, while an explicit `false` still turns it off. The YAML merge lets an empty or
	 * `null` value in a later file replace the bundled `true`, so the default must also hold in the options
	 * constructor that receives the merged value.
	 */
	@ParameterizedTest
	@DisplayName("should default GraphQL parallel fetching to true unless YAML sets it to false")
	@CsvSource({"null, true", "empty, true", "absent, true", "false, false"})
	void shouldDefaultGraphQLParallelizeToTrueFromYaml(String value, boolean expected) throws IOException {
		final Path configDirectory = getPathInTargetDirectory(DIR_EVITA_SERVER_TEST);
		Files.createDirectories(configDirectory);
		final String graphQLSettings = switch (value) {
			case "absent" -> "enabled: true";
			case "empty" -> "parallelize:";
			default -> "parallelize: " + value;
		};
		Files.writeString(
			configDirectory.resolve("evita-configuration.yaml"),
			"""
			api:
			  endpoints:
			    graphQL:
			      %s
			""".formatted(graphQLSettings)
		);
		final EvitaServer evitaServer = new EvitaServer(
			configDirectory, constructTestArguments(GraphQLProvider.CODE)
		);
		try {
			evitaServer.run();
			final GraphQLProvider graphQLProvider = evitaServer.getExternalApiServer()
				.getExternalApiProviderByCode(GraphQLProvider.CODE);
			assertNotNull(graphQLProvider);
			assertEquals(expected, graphQLProvider.getConfiguration().isParallelize(), "YAML parallelize=" + value);
		} finally {
			closeServerAndEvita(evitaServer);
		}
	}

	@Test
	void shouldMergeMultipleYamlConfigurationTogether() {
		EvitaTestSupport.bootstrapEvitaServerConfigurationFileFrom(
			DIR_EVITA_SERVER_TEST,
			"/testData/evita-configuration-one.yaml",
			"evita-configuration-one.yaml"
		);
		EvitaTestSupport.bootstrapEvitaServerConfigurationFileFrom(
			DIR_EVITA_SERVER_TEST,
			"/testData/evita-configuration-two.yaml",
			"evita-configuration-two.yaml"
		);

		final EvitaServer evitaServer = new EvitaServer(
			getPathInTargetDirectory(DIR_EVITA_SERVER_TEST),
			constructTestArguments()
		);
		try {
			evitaServer.run();

			final Evita evita = evitaServer.getEvita();
			evita.defineCatalog(TEST_CATALOG);
			assertFalse(evita.getConfiguration().cache().enabled());

			final ExternalApiServer externalApiServer = evitaServer.getExternalApiServer();
			assertNull(externalApiServer.getExternalApiProviderByCode(SystemProvider.CODE));
			assertNull(externalApiServer.getExternalApiProviderByCode(GraphQLProvider.CODE));
			assertNull(externalApiServer.getExternalApiProviderByCode(RestProvider.CODE));
			assertNull(externalApiServer.getExternalApiProviderByCode(GrpcProvider.CODE));
			assertNull(externalApiServer.getExternalApiProviderByCode(ObservabilityProvider.CODE));

		} catch (Exception ex) {
			fail(ex);
		} finally {
			closeServerAndEvita(evitaServer);
		}
	}

	@Test
	void shouldLoadCompactionCadenceConfiguration() {
		EvitaTestSupport.bootstrapEvitaServerConfigurationFileFrom(
			DIR_EVITA_SERVER_TEST,
			"/testData/evita-configuration-compaction-cadence.yaml",
			"evita-configuration-compaction-cadence.yaml"
		);

		final EvitaServer evitaServer = new EvitaServer(
			getPathInTargetDirectory(DIR_EVITA_SERVER_TEST),
			constructTestArguments()
		);
		try {
			evitaServer.run();

			final Evita evita = evitaServer.getEvita();
			final StorageOptions storageOptions = evita.getConfiguration().storage();
			// "10m" must be parsed via the seconds-based TIME_FORMAT handler and then scaled to milliseconds
			// because the property name ends in "Milliseconds" - not left as a raw "10" or unscaled "600"
			assertEquals(600_000L, storageOptions.minCompactionIntervalMilliseconds());
			assertEquals(0.4, storageOptions.minimalActiveRecordShare());
			assertEquals(0.2, storageOptions.maxWasteActiveShare());

		} catch (Exception ex) {
			fail(ex);
		} finally {
			closeServerAndEvita(evitaServer);
		}
	}

	@Test
	void shouldLoadDeprecatedConfiguration() {
		EvitaTestSupport.bootstrapEvitaServerConfigurationFileFrom(
			DIR_EVITA_SERVER_TEST,
			"/testData/evita-configuration-deprecated.yaml",
			"evita-configuration-deprecated.yaml"
		);

		final EvitaServer evitaServer = new EvitaServer(
			getPathInTargetDirectory(DIR_EVITA_SERVER_TEST),
			constructTestArguments()
		);
		try {
			evitaServer.run();

			final ExternalApiServer externalApiServer = evitaServer.getExternalApiServer();
			final AbstractApiOptions systemConfig = externalApiServer.getExternalApiProviderByCode(SystemProvider.CODE).getConfiguration();
			assertEquals(TlsMode.FORCE_NO_TLS, systemConfig.getTlsMode());
			assertFalse(systemConfig.isKeepAlive());
			final AbstractApiOptions graphQLConfig = externalApiServer.getExternalApiProviderByCode(GraphQLProvider.CODE).getConfiguration();
			assertEquals(TlsMode.FORCE_NO_TLS, graphQLConfig.getTlsMode());
			assertFalse(graphQLConfig.isKeepAlive());
			final AbstractApiOptions restConfig = externalApiServer.getExternalApiProviderByCode(RestProvider.CODE).getConfiguration();
			assertEquals(TlsMode.FORCE_NO_TLS, restConfig.getTlsMode());
			assertFalse(restConfig.isKeepAlive());
			final AbstractApiOptions grpcConfig = externalApiServer.getExternalApiProviderByCode(GrpcProvider.CODE).getConfiguration();
			assertEquals(TlsMode.FORCE_NO_TLS, grpcConfig.getTlsMode());
			// gRPC is always keep-alive - it is a requirement, WARNING is logged when it is set to false
			assertTrue(grpcConfig.isKeepAlive());
			final AbstractApiOptions observabilityConfig = externalApiServer.getExternalApiProviderByCode(ObservabilityProvider.CODE).getConfiguration();
			assertEquals(TlsMode.FORCE_TLS, observabilityConfig.getTlsMode());
			assertFalse(observabilityConfig.isKeepAlive());

			// verify deprecated export settings were migrated correctly
			final Evita evita = evitaServer.getEvita();
			final ExportOptions exportOptions = evita.getConfiguration().export();
			assertEquals(536870912L, exportOptions.getSizeLimitBytes());
			assertEquals(86400L, exportOptions.getHistoryExpirationSeconds());
			assertTrue(
				((FileSystemExportOptions) exportOptions)
					.getDirectory().toString().contains("deprecated-export")
			);

		} catch (Exception ex) {
			fail(ex);
		} finally {
			closeServerAndEvita(evitaServer);
		}
	}

	/**
	 * A `null` shared mode leaves the endpoints the user configuration does not mention on their bundled values:
	 * `null` for GraphQL, REST, gRPC and lab, which therefore fall back to the default of their options, and the
	 * explicit `FORCE_NO_TLS` of system and observability.
	 */
	@Test
	@DisplayName("Should use endpoint TLS defaults when only the shared mode is null")
	void shouldResolveEndpointTlsDefaultsWhenSharedModeIsNull() throws IOException {
		runServerAndAssert(
			writeUserConfiguration(
				"""
				api:
				  endpointDefaults:
				    tlsMode: null
				"""
			),
			constructTestArguments(),
			server -> assertTlsModes(server, ENDPOINT_TLS_DEFAULTS, "shared tlsMode null")
		);
	}

	/**
	 * When the shared mode and an endpoint's own mode are both `null`, the options are created without any mode, so
	 * each endpoint must apply its own default instead of a single shared fallback.
	 */
	@Test
	@DisplayName("Should use endpoint TLS defaults when the shared mode and every endpoint mode are null")
	void shouldResolveEndpointTlsDefaultsWhenSharedAndEndpointModesAreNull() throws IOException {
		runServerAndAssert(
			writeUserConfiguration(
				"""
				api:
				  endpointDefaults:
				    tlsMode: null
				  endpoints:
				    graphQL:
				      tlsMode: null
				    rest:
				      tlsMode: null
				    gRPC:
				      tlsMode: null
				    lab:
				      tlsMode: null
				    system:
				      tlsMode: null
				    observability:
				      tlsMode: null
				"""
			),
			constructTestArguments(),
			server -> assertTlsModes(server, ENDPOINT_TLS_DEFAULTS, "shared and endpoint tlsMode null")
		);
	}

	/** Pins the loader's null inheritance rule for the endpoints whose own defaults allow plain HTTP. */
	@Test
	@DisplayName("Should inherit the shared TLS mode when only an endpoint mode is null")
	void shouldInheritSharedTlsModeForNullEndpoint() throws IOException {
		runServerAndAssert(
			writeUserConfiguration(
				"""
				api:
				  endpoints:
				    system:
				      tlsMode: null
				    observability:
				      tlsMode: null
				"""
			),
			constructTestArguments(SystemProvider.CODE, ObservabilityProvider.CODE),
			server -> assertTlsModes(
				server,
				Map.of(SystemProvider.CODE, TlsMode.FORCE_TLS, ObservabilityProvider.CODE, TlsMode.FORCE_TLS),
				"endpoint tlsMode null inherits the shared FORCE_TLS"
			)
		);
	}

	/**
	 * A quoted empty YAML scalar stays an empty string and a wrongly cased name matches no mode, so both must fail
	 * options construction with a diagnostic naming the endpoint options, the rejected value and the accepted names.
	 * The constructor fails before any port is reserved or bound.
	 */
	@ParameterizedTest(name = "tlsMode: {0}")
	@DisplayName("Should reject a quoted empty or wrongly cased TLS mode from YAML")
	@CsvSource({"'\"\"', ''", "force_tls, force_tls"})
	void shouldRejectInvalidTlsNamesFromYaml(
		@Nonnull String yamlValue, @Nonnull String rejectedValue
	) throws IOException {
		final Path configDirectory = writeUserConfiguration(
			"""
			api:
			  endpoints:
			    graphQL:
			      tlsMode: %s
			""".formatted(yamlValue)
		);
		final Map<String, String> arguments = createHashMap(0);

		final IllegalArgumentException exception = assertThrows(
			IllegalArgumentException.class, () -> new EvitaServer(configDirectory, arguments),
			"YAML tlsMode " + yamlValue + " must fail options construction"
		);

		final String message = exception.getMessage();
		assertTrue(message.contains("'" + rejectedValue + "' for GraphQLOptions"), message);
		for (final TlsMode mode : TlsMode.values()) {
			assertTrue(message.contains(mode.name()), message);
		}
	}

	/** An unquoted empty YAML scalar is `null`, so the endpoint inherits the shared mode instead of failing. */
	@Test
	@DisplayName("Should treat an unquoted empty YAML TLS mode as null")
	void shouldTreatEmptyYamlTlsModeAsNull() throws IOException {
		runServerAndAssert(
			writeUserConfiguration(
				"""
				api:
				  endpointDefaults:
				    tlsMode: FORCE_NO_TLS
				  endpoints:
				    graphQL:
				      tlsMode:
				"""
			),
			constructTestArguments(GraphQLProvider.CODE),
			server -> assertTlsModes(
				server, Map.of(GraphQLProvider.CODE, TlsMode.FORCE_NO_TLS), "unquoted empty YAML tlsMode"
			)
		);
	}

	/**
	 * An empty override argument is substituted into the bundled unquoted placeholder before the YAML is parsed, so it
	 * becomes a `null` scalar and the endpoint inherits the shared mode instead of failing.
	 */
	@Test
	@DisplayName("Should treat an empty TLS mode override argument as null")
	void shouldTreatEmptyTlsModeArgumentAsNull() {
		final Map<String, String> arguments = constructTestArguments(GraphQLProvider.CODE);
		arguments.put("api.endpoints.graphQL.tlsMode", "");
		arguments.put("api.endpointDefaults.tlsMode", "FORCE_NO_TLS");

		runServerAndAssert(
			getPathInTargetDirectory(DIR_EVITA_SERVER_TEST),
			arguments,
			server -> assertTlsModes(
				server, Map.of(GraphQLProvider.CODE, TlsMode.FORCE_NO_TLS), "empty tlsMode override argument"
			)
		);
	}

	/**
	 * The system endpoint serves the server certificate over plain HTTP, so a user configuration that does not set its
	 * `tlsMode` keeps the bundled `FORCE_NO_TLS` although the shared default is `FORCE_TLS`.
	 */
	@Test
	@DisplayName("should keep the system endpoint on FORCE_NO_TLS when YAML does not set its TLS mode")
	void shouldDefaultSystemTlsModeToForceNoTlsFromYaml() throws IOException {
		runServerAndAssert(
			writeUserConfiguration(
				"""
				api:
				  endpoints:
				    system:
				      enabled: true
				"""
			),
			constructTestArguments(SystemProvider.CODE),
			server -> assertTlsModes(
				server, Map.of(SystemProvider.CODE, TlsMode.FORCE_NO_TLS), "system tlsMode absent from YAML"
			)
		);
	}

	/**
	 * The documented default of `api.certificate.generateAndUseSelfSigned` is `true`. A user configuration that leaves
	 * the value absent or `null` (an empty value is the same YAML `null`) must keep that default, while an explicit
	 * `false` still turns it off. Only the plain-HTTP system endpoint runs, so the server starts without any
	 * certificate either way.
	 */
	@ParameterizedTest(name = "generateAndUseSelfSigned {0} -> {1}")
	@DisplayName("should default self-signed certificate generation to true unless YAML sets it to false")
	@CsvSource({"absent, true", "null, true", "false, false"})
	void shouldDefaultGenerateAndUseSelfSignedToTrueFromYaml(
		@Nonnull String value, boolean expected
	) throws IOException {
		final String certificateSettings = "absent".equals(value) ?
			"folderPath: './evita-server-certificates/'" : "generateAndUseSelfSigned: " + value;
		runServerAndAssert(
			writeUserConfiguration(
				"""
				api:
				  certificate:
				    %s
				""".formatted(certificateSettings)
			),
			constructTestArguments(SystemProvider.CODE),
			server -> assertEquals(
				expected,
				server.getExternalApiServer().getApiOptions().certificate().generateAndUseSelfSigned(),
				"YAML generateAndUseSelfSigned=" + value
			)
		);
	}

	@Test
	void shouldLoadConfigurationWithUnknownProperties() {
		EvitaTestSupport.bootstrapEvitaServerConfigurationFileFrom(
			DIR_EVITA_SERVER_TEST,
			"/testData/evita-configuration-unknown-props.yaml",
			"evita-configuration-unknown-props.yaml"
		);

		final EvitaServer evitaServer = new EvitaServer(
			getPathInTargetDirectory(DIR_EVITA_SERVER_TEST),
			constructTestArguments()
		);
		try {
			evitaServer.run();
		} catch (Exception ex) {
			fail(ex);
		} finally {
			closeServerAndEvita(evitaServer);
		}
	}

	@Test
	void shouldFailToLoadConfigurationWithUnknownPropertiesOnStrictSettings() {
		EvitaTestSupport.bootstrapEvitaServerConfigurationFileFrom(
			DIR_EVITA_SERVER_TEST,
			"/testData/evita-configuration-unknown-props.yaml",
			"evita-configuration-unknown-props.yaml"
		);

		try {
			new EvitaServer(
				getPathInTargetDirectory(DIR_EVITA_SERVER_TEST),
				true, null,
				constructTestArguments()
			);
			fail("The server should have failed to start due to unknown properties in the configuration file.");
		} catch (Exception ex) {
			// this is okey
		} finally {
			// the constructor fails before anything is bound, so the reservation is returned at once - left behind, it
			// would fail the next port allocation of this class
			getPortManager().releasePorts(DIR_EVITA_SERVER_TEST);
		}
	}

	/**
	 * Writes the given YAML as the only user configuration file of the test configuration directory, which the server
	 * merges over the bundled default configuration.
	 *
	 * @param content YAML content of the user configuration file
	 * @return the configuration directory to start the server from
	 */
	@Nonnull
	private Path writeUserConfiguration(@Nonnull String content) throws IOException {
		final Path configDirectory = getPathInTargetDirectory(DIR_EVITA_SERVER_TEST);
		Files.createDirectories(configDirectory);
		Files.writeString(configDirectory.resolve("evita-configuration.yaml"), content);
		return configDirectory;
	}

	/**
	 * Starts a server from the given configuration, applies the assertions and always shuts the server down again.
	 * A constructor failure happens before anything is bound, so the ports reserved for the arguments are released at
	 * once; left reserved, they would make the next port allocation of this class fail.
	 *
	 * @param configDirectory configuration directory to start the server from
	 * @param arguments       server arguments built from this class's shared port allocation
	 * @param assertions      assertions run against the started server
	 */
	private void runServerAndAssert(
		@Nonnull Path configDirectory,
		@Nonnull Map<String, String> arguments,
		@Nonnull Consumer<EvitaServer> assertions
	) {
		final EvitaServer server;
		try {
			server = new EvitaServer(configDirectory, arguments);
		} catch (RuntimeException | Error ex) {
			getPortManager().releasePorts(DIR_EVITA_SERVER_TEST);
			throw ex;
		}
		try {
			server.run();
			assertions.accept(server);
		} finally {
			closeServerAndEvita(server);
		}
	}

	/**
	 * Asserts the TLS mode each listed endpoint of a running server resolved to, reporting every mismatch at once.
	 *
	 * @param server   the running server
	 * @param expected expected TLS mode by endpoint code
	 * @param scenario description of the configuration, added to each failure message
	 */
	private static void assertTlsModes(
		@Nonnull EvitaServer server, @Nonnull Map<String, TlsMode> expected, @Nonnull String scenario
	) {
		final ApiOptions apiOptions = server.getExternalApiServer().getApiOptions();
		assertAll(
			expected.entrySet().stream().map(
				endpoint -> (Executable) () -> assertEquals(
					endpoint.getValue(),
					apiOptions.getEndpointConfiguration(endpoint.getKey()).getTlsMode(),
					endpoint.getKey() + ": " + scenario
				)
			)
		);
	}

	@Nonnull
	private Map<String, String> constructTestArguments(
		@Nonnull String... enabledApis
	) {
		return constructTestArguments(null, null, Set.of(), enabledApis);
	}

	@Nonnull
	private Map<String, String> constructTestArguments(
		@Nullable Map<String, Integer> servicePorts,
		@Nonnull String... enabledApis
	) {
		return constructTestArguments(servicePorts, null, Set.of(), enabledApis);
	}

	@Nonnull
	private Map<String, String> constructTestArguments(
		@Nullable Map<String, Integer> servicePorts,
		@Nullable List<Property> properties,
		@Nonnull String... enabledApis
	) {
		return constructTestArguments(servicePorts, properties, Set.of(), enabledApis);
	}

	/** Builds server arguments using the shared server-test port allocation. */
	@Nonnull
	private Map<String, String> constructTestArguments(
		@Nullable Map<String, Integer> servicePorts,
		@Nullable List<Property> properties,
		@Nonnull Set<String> apisWithSharedPorts,
		@Nonnull String... enabledApis
	) {
		return constructTestArguments(DIR_EVITA_SERVER_TEST, servicePorts, properties, apisWithSharedPorts, enabledApis);
	}

	/** Builds server arguments with an independent port allocation for a fixture. */
	@Nonnull
	private Map<String, String> constructTestArguments(
		@Nonnull String portDataSetName,
		@Nullable Map<String, Integer> servicePorts,
		@Nullable List<Property> properties,
		@Nonnull Set<String> apisWithSharedPorts,
		@Nonnull String... enabledApis
	) {
		final Set<String> allApis = ExternalApiServer.gatherExternalApiProviders()
			.stream()
			.map(ExternalApiProviderRegistrar::getExternalApiCode)
			.collect(Collectors.toSet());
		final Set<String> apis = ArrayUtils.isEmpty(enabledApis) ?
			allApis :
			Set.of(enabledApis);
		final int[] ports = getPortManager().allocatePorts(portDataSetName, apis.size());
		final AtomicInteger index = new AtomicInteger();
		//noinspection unchecked
		return createHashMap(
			Stream.of(
					Stream.of(
						property("storage.storageDirectory", getTestDirectory().resolve(DIR_EVITA_SERVER_TEST).toString()),
						property("export.fileSystem.enabled", "true"),
						property("export.fileSystem.directory", getTestDirectory().resolve(DIR_EVITA_SERVER_TEST + "_export").toString()),
						property("cache.enabled", "false"),
						property("api.requestTimeoutInMillis", "10K")
					),
					allApis.stream()
						.filter(apis::contains)
						.flatMap(
							it -> {
								final int allocatedPort;
								if (apisWithSharedPorts.contains(it) && apis.stream().anyMatch(servicePorts::containsKey)) {
									allocatedPort = servicePorts.get(apis.stream().filter(servicePorts::containsKey)
										.findFirst()
										.orElseThrow());
								} else {
									allocatedPort = ports[index.getAndIncrement()];
								}
								if (servicePorts != null) {
									servicePorts.put(it, allocatedPort);
								}
								return Stream.of(
									property("api.endpoints." + it + ".host", "localhost:" + allocatedPort),
									property("api.endpoints." + it + ".exposeOn", "localhost:" + allocatedPort),
									property("api.endpoints." + it + ".enabled", "true")
								);
							}
						),
					allApis.stream()
						.filter(it -> !apis.contains(it))
						.flatMap(
							it -> Stream.of(
								property("api.endpoints." + it + ".enabled", "false")
							)
						),
					properties == null ? Stream.empty() : properties.stream()
				)
				.flatMap(it -> it)
				.toArray(Property[]::new)
		);
	}
}
