/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import com.linecorp.armeria.client.grpc.GrpcClients;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.core.Evita;
import io.evitadb.driver.EvitaClient;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.externalApi.configuration.TlsMode;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.grpc.GrpcProvider;
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
import io.evitadb.utils.CollectionUtils.Property;
import io.evitadb.utils.NetworkUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.utils.CollectionUtils.createHashMap;
import static io.evitadb.utils.CollectionUtils.property;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test tries to start up the {@link EvitaServer} with default configuration.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
class EvitaServerTest implements TestConstants, EvitaTestSupport {
	private static final String DIR_EVITA_SERVER_TEST = "evitaServerTest";
	public static final int TIMEOUT_IN_MILLIS = 30_000;

	@Nonnull
	private static String replaceVariables(@Nonnull String status) {
		String output = status;
		output = Pattern.compile("(\"serverName\": \"evitaDB-)(.+?)\"").matcher(output).replaceAll("$1RANDOM\"");
		output = Pattern.compile("(\"version\": \")((?:\\?)|(?:\\d{4}\\.\\d{1,2}(-SNAPSHOT)?))\"").matcher(output).replaceAll("$1VARIABLE\"");
		output = Pattern.compile("(\"startedAt\": \")(.+?)\"").matcher(output).replaceAll("$1VARIABLE\"");
		output = Pattern.compile("(\"uptime\": )(.+?),").matcher(output).replaceAll("$1VARIABLE,");
		output = Pattern.compile("(\"uptimeForHuman\": \")(.+?)\"").matcher(output).replaceAll("$1VARIABLE\"");
		output = Pattern.compile("(//)(.+:[0-9]+)(/)").matcher(output).replaceAll("$1VARIABLE$3");
		return output;
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
			try {
				evitaServer.getEvita().deleteCatalogIfExists(TEST_CATALOG);
				getPortManager().releasePortsOnCompletion(DIR_EVITA_SERVER_TEST, evitaServer.stop());
			} catch (Exception ex) {
				fail(ex.getMessage(), ex);
			}
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
				error -> assertEquals("Error fetching content from URL: https://localhost:" + servicePorts.get(SystemProvider.CODE) + "/system/server-name HTTP status 403: This endpoint requires TLS.", error),
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
					.tlsEnabled(false)
					.build()
			);

			try {
				evitaClientBadPort.getCatalogNames();
				fail("gRPC call should have failed on bad port!");
			} catch (Exception ex) {
				assertEquals("io.grpc.StatusRuntimeException: UNIMPLEMENTED: HTTP status code 404", ex.getCause().getMessage());
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
				assertEquals("io.grpc.StatusRuntimeException: UNAVAILABLE", ex.getCause().getMessage());
			}

			// we should be able to access gRCP via correct scheme and port
			final EvitaClient correctEvitaClient = new EvitaClient(
				EvitaClientConfiguration.builder()
					.host("localhost")
					.port(servicePorts.get(GrpcProvider.CODE))
					.systemApiPort(servicePorts.get(SystemProvider.CODE))
					.tlsEnabled(false)
					.build()
			);

			assertEquals(0, correctEvitaClient.getCatalogNames().size());

			// we should be able to access gRCP via Lab scheme and port
			final EvitaClient labPortEvitaClient = new EvitaClient(
				EvitaClientConfiguration.builder()
					.host("localhost")
					.port(servicePorts.get(LabProvider.CODE))
					.systemApiPort(servicePorts.get(SystemProvider.CODE))
					.tlsEnabled(false)
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
			try {
				getPortManager().releasePortsOnCompletion(DIR_EVITA_SERVER_TEST, evitaServer.stop());
			} catch (Exception ex) {
				fail(ex.getMessage(), ex);
			}
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

			final GrpcEvitaServerStatusResponse grpcEvitaServerStatusResponse = GrpcClients.builder("gproto-web+https://localhost:" + servicePorts.get(GrpcProvider.CODE))
				.factory(ClientFactory.insecure())
				.build(EvitaManagementServiceBlockingStub.class)
				.serverStatus(Empty.newBuilder().build());

			assertNotNull(grpcEvitaServerStatusResponse);
			assertTrue(grpcEvitaServerStatusResponse.getUptime() > 0);
		} catch (Exception ex) {
			fail(ex);
		} finally {
			try {
				evitaServer.getEvita().deleteCatalogIfExists(TEST_CATALOG);
				getPortManager().releasePortsOnCompletion(DIR_EVITA_SERVER_TEST, evitaServer.stop());
			} catch (Exception ex) {
				fail(ex.getMessage(), ex);
			}
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
					.tlsEnabled(false)
					.build()
			);

			final EvitaSessionContract session = evitaClient.createReadWriteSession(TEST_CATALOG);
			assertNotNull(session);
			evitaClient.terminateSession(session);
			assertFalse(session.isActive());

		} catch (Exception ex) {
			fail(ex);
		} finally {
			try {
				evitaServer.getEvita().deleteCatalogIfExists(TEST_CATALOG);
				getPortManager().releasePortsOnCompletion(DIR_EVITA_SERVER_TEST, evitaServer.stop());
			} catch (Exception ex) {
				fail(ex.getMessage(), ex);
			}
		}
	}

	@Test
	void shouldSignalizeReadinessAndHealthinessCorrectly() {
		final EvitaServer evitaServer = new EvitaServer(
			getPathInTargetDirectory(DIR_EVITA_SERVER_TEST),
			constructTestArguments()
		);
		try {
			evitaServer.run();
			final String[] baseUrls = evitaServer.getExternalApiServer().getExternalApiProviderByCode(SystemProvider.CODE)
				.getConfiguration()
				.getBaseUrls();

			Optional<String> readiness;
			final long start = System.currentTimeMillis();
			do {
				final String url = baseUrls[0] + "readiness";
				log.info("Checking readiness at {}", url);
				readiness = NetworkUtils.fetchContent(
					url,
					"GET",
					"application/json",
					null,
					TIMEOUT_IN_MILLIS,
					error -> log.error("Error while checking readiness of API: {}", error),
					timeout -> log.error("Error while checking readiness of API: {}", timeout)
				);

				if (readiness.isPresent() && readiness.get().contains("\"status\": \"READY\"")) {
					break;
				}

			} while (System.currentTimeMillis() - start < 20000);

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

			final Optional<String> liveness = NetworkUtils.fetchContent(
				baseUrls[0] + "liveness",
				"GET",
				"application/json",
				null,
				TIMEOUT_IN_MILLIS,
				error -> log.error("Error while checking readiness of API: {}", error),
				timeout -> log.error("Error while checking readiness of API: {}", timeout)
			);

			assertTrue(liveness.isPresent());
			assertEquals(
				"{\"status\": \"healthy\"}",
				liveness.get().trim()
			);

			final Optional<String> status = NetworkUtils.fetchContent(
				baseUrls[0] + "status",
				"GET",
				"application/json",
				null,
				TIMEOUT_IN_MILLIS,
				error -> log.error("Error while checking readiness of API: {}", error),
				timeout -> log.error("Error while checking readiness of API: {}", timeout)
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
					   "catalogsCorrupted": 0,
					   "catalogsOk": 0,
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
			try {
				getPortManager().releasePortsOnCompletion(DIR_EVITA_SERVER_TEST, evitaServer.stop());
			} catch (Exception ex) {
				fail(ex.getMessage(), ex);
			}
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
			try {
				evitaServer.getEvita().deleteCatalogIfExists(TEST_CATALOG);
				getPortManager().releasePortsOnCompletion(DIR_EVITA_SERVER_TEST, evitaServer.stop());
			} catch (Exception ex) {
				fail(ex.getMessage(), ex);
			}
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
			assertEquals(TlsMode.FORCE_NO_TLS, externalApiServer.getExternalApiProviderByCode(SystemProvider.CODE).getConfiguration().getTlsMode());
			assertEquals(TlsMode.FORCE_NO_TLS, externalApiServer.getExternalApiProviderByCode(GraphQLProvider.CODE).getConfiguration().getTlsMode());
			assertEquals(TlsMode.FORCE_NO_TLS, externalApiServer.getExternalApiProviderByCode(RestProvider.CODE).getConfiguration().getTlsMode());
			assertEquals(TlsMode.FORCE_NO_TLS, externalApiServer.getExternalApiProviderByCode(GrpcProvider.CODE).getConfiguration().getTlsMode());
			assertEquals(TlsMode.FORCE_TLS, externalApiServer.getExternalApiProviderByCode(ObservabilityProvider.CODE).getConfiguration().getTlsMode());

		} catch (Exception ex) {
			fail(ex);
		} finally {
			try {
				evitaServer.getEvita().deleteCatalogIfExists(TEST_CATALOG);
				getPortManager().releasePortsOnCompletion(DIR_EVITA_SERVER_TEST, evitaServer.stop());
			} catch (Exception ex) {
				fail(ex.getMessage(), ex);
			}
		}
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

	@Nonnull
	private Map<String, String> constructTestArguments(
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
		final int[] ports = getPortManager().allocatePorts(DIR_EVITA_SERVER_TEST, apis.size());
		final AtomicInteger index = new AtomicInteger();
		//noinspection unchecked
		return createHashMap(
			Stream.of(
					Stream.of(
						property("storage.storageDirectory", getTestDirectory().resolve(DIR_EVITA_SERVER_TEST).toString()),
						property("cache.enabled", "false")
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
