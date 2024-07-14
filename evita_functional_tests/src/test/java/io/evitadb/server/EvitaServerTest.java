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
import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import io.evitadb.core.Evita;
import io.evitadb.driver.EvitaClient;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.driver.interceptor.ClientSessionInterceptor;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.grpc.TestGrpcClientBuilderCreator;
import io.evitadb.externalApi.grpc.generated.EvitaServiceGrpc;
import io.evitadb.externalApi.grpc.generated.EvitaServiceGrpc.EvitaServiceBlockingStub;
import io.evitadb.externalApi.grpc.generated.GrpcEvitaServerStatusResponse;
import io.evitadb.externalApi.http.ExternalApiProviderRegistrar;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.observability.ObservabilityProvider;
import io.evitadb.externalApi.rest.RestProvider;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.TestConstants;
import io.evitadb.utils.CollectionUtils.Property;
import io.evitadb.utils.NetworkUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

	@BeforeEach
	void setUp() throws IOException {
		cleanTestSubDirectory(DIR_EVITA_SERVER_TEST);
	}

	@AfterEach
	void tearDown() throws IOException {
		cleanTestSubDirectory(DIR_EVITA_SERVER_TEST);
	}

	@Test
	void shouldStartAndStopTlsServerCorrectly() {
		final Set<String> apis = ExternalApiServer.gatherExternalApiProviders()
			.stream()
			.map(ExternalApiProviderRegistrar::getExternalApiCode)
			.collect(Collectors.toSet());

		final int[] ports = getPortManager().allocatePorts(DIR_EVITA_SERVER_TEST, apis.size());
		final AtomicInteger index = new AtomicInteger();
		final Map<String, Integer> servicePorts = new HashMap<>();
		//noinspection unchecked
		final EvitaServer evitaServer = new EvitaServer(
			getPathInTargetDirectory(DIR_EVITA_SERVER_TEST),
			createHashMap(
				Stream.concat(
					Stream.of(
						property("storage.storageDirectory", getTestDirectory().resolve(DIR_EVITA_SERVER_TEST).toString()),
						property("cache.enabled", "false")
					),
					apis.stream()
						.map(it -> {
							final int port = ports[index.getAndIncrement()];
							servicePorts.put(it, port);
							return property("api.endpoints." + it + ".host", "localhost:" + port);
						})
				)
				.toArray(Property[]::new)
			)
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

			final GrpcClientBuilder clientBuilder = TestGrpcClientBuilderCreator.getBuilder(new ClientSessionInterceptor(), externalApiServer);
			final EvitaSessionContract session = evitaClient.createReadWriteSession(TEST_CATALOG);
			assertNotNull(session);
			evitaClient.terminateSession(session);
			assertFalse(session.isActive());

			final EvitaServiceGrpc.EvitaServiceBlockingStub evitaBlockingStub = clientBuilder.build(EvitaServiceGrpc.EvitaServiceBlockingStub.class);
		} catch (Exception ex) {
			fail(ex);
		} finally {
			try {
				evitaServer.getEvita().deleteCatalogIfExists(TEST_CATALOG);
				evitaServer.stop();
			} catch (Exception ex) {
				fail(ex.getMessage(), ex);
			}
		}
	}

	@Test
	void shouldBeAbleToGetGrpcWebResponse() {
		final List<String> apis = List.of(GrpcProvider.CODE, SystemProvider.CODE);
		final int[] ports = getPortManager().allocatePorts(DIR_EVITA_SERVER_TEST, apis.size());
		final AtomicInteger index = new AtomicInteger();
		final Map<String, Integer> servicePorts = new HashMap<>();
		//noinspection unchecked
		final EvitaServer evitaServer = new EvitaServer(
			getPathInTargetDirectory(DIR_EVITA_SERVER_TEST),
			createHashMap(
				Stream.concat(
						Stream.of(
							property("storage.storageDirectory", getTestDirectory().resolve(DIR_EVITA_SERVER_TEST).toString()),
							property("cache.enabled", "false")
						),
						apis.stream()
							.map(it -> {
								final int port = ports[index.getAndIncrement()];
								servicePorts.put(it, port);
								return property("api.endpoints." + it + ".host", "localhost:" + port);
							})
					)
					.toArray(Property[]::new)
			)
		);
		try {
			evitaServer.run();

			final Evita evita = evitaServer.getEvita();
			evita.defineCatalog(TEST_CATALOG);
			assertFalse(evita.getConfiguration().cache().enabled());

			final GrpcEvitaServerStatusResponse grpcEvitaServerStatusResponse = GrpcClients.builder("gproto-web+https://localhost:" + servicePorts.get(GrpcProvider.CODE))
				.factory(ClientFactory.insecure())
				.build(EvitaServiceBlockingStub.class)
				.serverStatus(Empty.newBuilder().build());

			assertNotNull(grpcEvitaServerStatusResponse);
			assertTrue(grpcEvitaServerStatusResponse.getUptime() > 0);
		} catch (Exception ex) {
			fail(ex);
		} finally {
			try {
				evitaServer.getEvita().deleteCatalogIfExists(TEST_CATALOG);
				evitaServer.stop();
			} catch (Exception ex) {
				fail(ex.getMessage(), ex);
			}
		}
	}

	@Test
	void shouldStartAndStopPlainServerCorrectly() {
		final Set<String> apis = ExternalApiServer.gatherExternalApiProviders()
			.stream()
			.map(ExternalApiProviderRegistrar::getExternalApiCode)
			.collect(Collectors.toSet());

		final int[] ports = getPortManager().allocatePorts(DIR_EVITA_SERVER_TEST, apis.size());
		final AtomicInteger index = new AtomicInteger();
		final Map<String, Integer> servicePorts = new HashMap<>();
		//noinspection unchecked
		final EvitaServer evitaServer = new EvitaServer(
			getPathInTargetDirectory(DIR_EVITA_SERVER_TEST),
			createHashMap(
				Stream.concat(
						Stream.of(
							property("storage.storageDirectory", getTestDirectory().resolve(DIR_EVITA_SERVER_TEST).toString()),
							property("cache.enabled", "false"),
							property("api.endpoints.gRPC.tlsEnabled", "false")
						),
						apis.stream()
							.map(it -> {
								final int port = ports[index.getAndIncrement()];
								servicePorts.put(it, port);
								return property("api.endpoints." + it + ".host", "localhost:" + port);
							})
					)
					.toArray(Property[]::new)
			)
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
				evitaServer.stop();
			} catch (Exception ex) {
				fail(ex.getMessage(), ex);
			}
		}
	}

	@Test
	void shouldSignalizeReadinessAndHealthinessCorrectly() {
		final Set<String> apis = ExternalApiServer.gatherExternalApiProviders()
			.stream()
			.map(ExternalApiProviderRegistrar::getExternalApiCode)
			.collect(Collectors.toSet());

		final int[] ports = getPortManager().allocatePorts(DIR_EVITA_SERVER_TEST, apis.size());
		final AtomicInteger index = new AtomicInteger();
		//noinspection unchecked
		final EvitaServer evitaServer = new EvitaServer(
			getPathInTargetDirectory(DIR_EVITA_SERVER_TEST),
			createHashMap(
				Stream.concat(
						Stream.of(
							property("storage.storageDirectory", getTestDirectory().resolve(DIR_EVITA_SERVER_TEST).toString()),
							property("cache.enabled", "false")
						),
						apis.stream()
							.flatMap(
								it -> Stream.of(
									property("api.endpoints." + it + ".host", "localhost:" + ports[index.getAndIncrement()]),
									property("api.endpoints." + it + ".enabled", "true")
								)
							)
					)
					.toArray(Property[]::new)
			)
		);
		try {
			evitaServer.run();
			final String[] baseUrls = evitaServer.getExternalApiServer().getExternalApiProviderByCode(SystemProvider.CODE)
				.getConfiguration()
				.getBaseUrls(null);

			Optional<String> readiness;
			final long start = System.currentTimeMillis();
			do {
				final String url = baseUrls[0] + "readiness";
				log.info("Checking readiness at {}", url);
				readiness = NetworkUtils.fetchContent(
					url,
					"GET",
					"application/json",
					null
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
						"rest": "ready",
						"system": "ready",
						"graphQL": "ready",
						"lab": "ready",
						"observability": "ready",
						"gRPC": "ready"
					}
				}""",
				readiness.get().trim()
			);

			final Optional<String> liveness = NetworkUtils.fetchContent(
				baseUrls[0] + "liveness",
				"GET",
				"application/json",
				null
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
				null
			);

			assertTrue(status.isPresent());
			String output = status.get();
			output = Pattern.compile("(\"serverName\": \"evitaDB-)(.+?)\"").matcher(output).replaceAll("$1RANDOM\"");
			output = Pattern.compile("(\"version\": \")((?:\\?)|(?:\\d{4}\\.\\d{1,2}(-SNAPSHOT)?))\"").matcher(output).replaceAll("$1VARIABLE\"");
			output = Pattern.compile("(\"startedAt\": \")(.+?)\"").matcher(output).replaceAll("$1VARIABLE\"");
			output = Pattern.compile("(\"uptime\": )(\\d+?)").matcher(output).replaceAll("$1VARIABLE");
			output = Pattern.compile("(\"uptimeForHuman\": \")(.+?)\"").matcher(output).replaceAll("$1VARIABLE\"");
			output = Pattern.compile("(//)(.+:[0-9]+)(/)").matcher(output).replaceAll("$1VARIABLE$3");
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
				         "system": [
				            "http://VARIABLE/system/"
				         ]
				      },
				      {
				         "graphQL": [
				            "https://VARIABLE/gql/"
				         ]
				      },
				      {
				         "rest": [
				            "https://VARIABLE/rest/"
				         ]
				      },
				      {
				         "gRPC": [
				            "https://VARIABLE/"
				         ]
				      },
				      {
				         "lab": [
				            "https://VARIABLE/lab/"
				         ]
				      },
				      {
				         "observability": [
				            "http://VARIABLE/observability/"
				         ]
				      }
				   ]
				}""",
				output
			);

		} catch (Exception ex) {
			fail(ex);
		} finally {
			try {
				evitaServer.stop();
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

		//noinspection unchecked
		final EvitaServer evitaServer = new EvitaServer(
			getPathInTargetDirectory(DIR_EVITA_SERVER_TEST),
			createHashMap(
				Stream.of(
					property("storage.storageDirectory", getTestDirectory().resolve(DIR_EVITA_SERVER_TEST).toString())
				).toArray(Property[]::new)
			)
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
				evitaServer.stop();
			} catch (Exception ex) {
				fail(ex.getMessage(), ex);
			}
		}
	}
}
