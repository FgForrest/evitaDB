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

package io.evitadb.externalApi;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.google.protobuf.Empty;
import com.linecorp.armeria.client.grpc.GrpcClients;
import io.evitadb.api.CatalogState;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.observability.ReadinessState;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.system.ProbesProvider.CatalogRoutingSettlement;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.CertificateOptions;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.configuration.TlsMode;
import io.evitadb.externalApi.graphql.GraphQLManager;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.graphql.GraphQLProviderRegistrar;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.grpc.GrpcProviderRegistrar;
import io.evitadb.externalApi.grpc.configuration.GrpcOptions;
import io.evitadb.externalApi.grpc.generated.EvitaManagementServiceGrpc.EvitaManagementServiceBlockingStub;
import io.evitadb.externalApi.grpc.generated.GrpcReadiness;
import io.evitadb.externalApi.http.ExternalApiProvider;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.observability.ObservabilityProvider;
import io.evitadb.externalApi.observability.ObservabilityProviderRegistrar;
import io.evitadb.externalApi.observability.configuration.ObservabilityOptions;
import io.evitadb.externalApi.observability.metric.CatalogRoutingReadiness;
import io.evitadb.externalApi.rest.RestManager;
import io.evitadb.externalApi.rest.RestProvider;
import io.evitadb.externalApi.rest.RestProviderRegistrar;
import io.evitadb.externalApi.rest.configuration.RestOptions;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.externalApi.system.SystemProviderRegistrar;
import io.evitadb.externalApi.system.configuration.SystemOptions;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.PortManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.REST;
import static io.evitadb.test.TestTags.SYSTEM_API;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Boots the engine and its web APIs the way the standalone server does - catalog loading deferred until the API
 * server has started, real thread pools - over a data directory that already holds a live catalog. The GraphQL and
 * REST managers are the production ones, except that their `registerCatalog` waits at its entry until the test
 * releases it. That is the only difference from production: it widens the window between the engine finishing the
 * catalog load and both APIs publishing the catalog's endpoints, which production passes through asynchronously.
 *
 * While both publications are held the catalog routes answer `404`, so the instance must not report itself ready;
 * once they are released, it must.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(SYSTEM_API)
@Tag(GRAPHQL)
@Tag(REST)
@Tag(MANAGEMENT)
@DisplayName("Readiness while loaded catalogs are being published by the web APIs")
class CatalogReadinessRoutingTest implements EvitaTestSupport {
	/** Catalog persisted before the boot under test. */
	private static final String CATALOG = "products";
	/** Label of the test directories and of the port allocation. */
	private static final String TEST_NAME = "CatalogReadinessRoutingTest";
	/** Bound of every positive wait and poll. */
	private static final long WAIT_SECONDS = 30L;
	/**
	 * Request threads: each held publication parks one, while the readiness handler and the providers' self-probes
	 * need more of them.
	 */
	private static final int REQUEST_THREADS = 16;
	/** Shared JSON reader of response bodies. */
	private static final ObjectMapper JSON = new ObjectMapper();

	/**
	 * Holds both routes during startup and again after the node has become ready.
	 *
	 * @throws Exception if startup, activation or a probe fails
	 */
	@Test
	@Tag(GRPC)
	@DisplayName("Should not report ready while GraphQL and REST have not published a loaded catalog")
	void shouldNotReportReadyWhileCatalogIsNotPublished() throws Exception {
		this.runCatalogReadinessScenario(false, true, null, (evita, server, endpoints, graphQLGate, restGate) -> {
			final EvitaManagementServiceBlockingStub management = GrpcClients
				.builder("gproto+http://" + address(endpoints.grpcPort()))
				.build(EvitaManagementServiceBlockingStub.class);
			// The release assertion still runs if readiness was reported too early.
			assertAll(
				() -> {
					try {
						assertNotReadyWhilePublicationIsHeld(
							evita, server, graphQLGate.get(), restGate.get(), endpoints
						);
						assertEquals(
							GrpcReadiness.API_STARTING, management.withDeadlineAfter(WAIT_SECONDS, SECONDS)
								.serverStatus(Empty.getDefaultInstance()).getReadiness(),
							"gRPC reported readiness before catalog publication"
						);
					} finally {
						graphQLGate.get().release().countDown();
					}
				},
				() -> {
					assertReadyOncePublicationIsReleased(endpoints);
					assertEquals(
						GrpcReadiness.API_READY, management.withDeadlineAfter(WAIT_SECONDS, SECONDS)
							.serverStatus(Empty.getDefaultInstance()).getReadiness(),
						"gRPC did not report readiness after catalog publication"
					);
					assertReadinessRemainsReady(evita, endpoints, graphQLGate, restGate);
				}
			);
		});
	}

	/**
	 * Verifies the readiness rule shared by the HTTP and gRPC status endpoints.
	 */
	@Test
	@DisplayName("Only downgrades ready probe status while catalog routing is pending")
	void shouldPreserveProbeStatusUnlessReadyWithPendingCatalogs() {
		assertAll(
			() -> assertEquals(ReadinessState.STARTING,
				CatalogRoutingSettlement.resolveReadinessState(ReadinessState.READY, false)),
			() -> assertEquals(ReadinessState.STALLING,
				CatalogRoutingSettlement.resolveReadinessState(ReadinessState.STALLING, false)),
			() -> assertEquals(ReadinessState.UNKNOWN,
				CatalogRoutingSettlement.resolveReadinessState(ReadinessState.UNKNOWN, false)),
			() -> assertEquals(ReadinessState.READY,
				CatalogRoutingSettlement.resolveReadinessState(ReadinessState.READY, true))
		);
	}

	/**
	 * Re-arms both publications and samples readiness while reactivation is waiting to publish.
	 *
	 * @param evita running engine
	 * @param endpoints HTTP requests against the running server
	 * @param graphQLGate gate used by the GraphQL manager
	 * @param restGate gate used by the REST manager
	 * @throws Exception if activation, publication or a probe fails
	 */
	private static void assertReadinessRemainsReady(
		@Nonnull Evita evita,
		@Nonnull Endpoints endpoints,
		@Nonnull AtomicReference<RegistrationGate> graphQLGate,
		@Nonnull AtomicReference<RegistrationGate> restGate
	) throws Exception {
		final CountDownLatch release = new CountDownLatch(1);
		graphQLGate.set(new RegistrationGate(release));
		restGate.set(new RegistrationGate(release));
		evita.deactivateCatalogWithProgress(CATALOG).onCompletion().toCompletableFuture().get(WAIT_SECONDS, SECONDS);
		final CompletableFuture<Void> activation = evita.activateCatalogWithProgress(CATALOG)
			.onCompletion().toCompletableFuture();
		assertTrue(graphQLGate.get().entered().await(WAIT_SECONDS, SECONDS), "GraphQL never started re-publication");
		assertTrue(restGate.get().entered().await(WAIT_SECONDS, SECONDS), "REST never started re-publication");
		try {
			assertEquals(404, endpoints.send(endpoints.graphQL()).statusCode());
			assertEquals(404, endpoints.send(endpoints.rest()).statusCode());
			// the publication stays held while polling, so only the latch can answer 200; polling only rides out
			// a probe that times out under load
			endpoints.poll(endpoints.readiness(), response -> response.statusCode() == 200);
		} finally {
			release.countDown();
		}
		activation.get(WAIT_SECONDS, SECONDS);
	}

	/**
	 * Covers the startup scan before loading, its timeout, and wiring after loading.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Startup catalog settlement")
	class StartupCatalogRouting {

		/**
		 * A catalog that has yet to load keeps startup routing pending before the server starts.
		 *
		 * @throws Exception if startup or settlement fails
		 */
		@Test
		@DisplayName("Waits for a transitional catalog to load and publish")
		void shouldWaitForTransitionalCatalogs() throws Exception {
			CatalogReadinessRoutingTest.this
				.runCatalogReadinessScenario(false, false, null, (evita, server, endpoints, graphQLGate, restGate) -> {
				assertEquals(CatalogState.BEING_ACTIVATED, evita.getCatalogState(CATALOG).orElseThrow());
				final CatalogRoutingSettlement beforeStart =
					server.getProbeProviders().getFirst().getCatalogRoutingSettlement(evita, server);
				assertFalse(beforeStart.settled(), "A transitional catalog must keep readiness pending");
				assertEquals(1, beforeStart.catalogsPending());

				server.start();
				assertTrue(graphQLGate.get().entered().await(WAIT_SECONDS, SECONDS), "GraphQL publication never began");
				assertTrue(restGate.get().entered().await(WAIT_SECONDS, SECONDS), "REST publication never began");
				graphQLGate.get().release().countDown();
				assertReadyOncePublicationIsReleased(endpoints);
			});
		}

		/**
		 * Holds real API builds and advances the scan's clock across the deadline.
		 *
		 * @throws Exception if startup or a publication gate fails
		 */
		@Test
		@DisplayName("Settles overdue publication and logs each unresolved API once")
		void shouldSettleOverdueCatalogPublication() throws Exception {
			CatalogReadinessRoutingTest.this
				.runCatalogReadinessScenario(false, false, null, (evita, server, endpoints, graphQLGate, restGate) -> {
				server.start();
				assertTrue(graphQLGate.get().entered().await(WAIT_SECONDS, SECONDS), "GraphQL publication never began");
				assertTrue(restGate.get().entered().await(WAIT_SECONDS, SECONDS), "REST publication never began");

				final long budgetMillis = 5_000;
				final long firstEvaluation = SECONDS.toNanos(120);
				final AtomicLong time = new AtomicLong(firstEvaluation);
				final CatalogRoutingReadiness scan = new CatalogRoutingReadiness(time::get, budgetMillis);
				final Logger logger = (Logger) LoggerFactory.getLogger(CatalogRoutingReadiness.class);
				final ListAppender<ILoggingEvent> appender = new ListAppender<>();
				appender.start();
				logger.addAppender(appender);
				try {
					final CatalogRoutingSettlement first = scan.evaluateCatalogRouting(
						evita, server.getRegisteredApiProviders()
					);
					assertFalse(first.settled());
					assertEquals(1, first.catalogsPending());
					time.set(firstEvaluation + MILLISECONDS.toNanos(budgetMillis - 1));
					assertFalse(scan.evaluateCatalogRouting(
						evita, server.getRegisteredApiProviders()
					).settled(), "The publication budget has not expired");
					assertTrue(
						appender.list.stream()
							.map(ILoggingEvent::getFormattedMessage)
							.noneMatch(message -> message.contains("Catalog `" + CATALOG + "`")),
						"Pending publication must be quiet before the deadline"
					);

					time.set(firstEvaluation + MILLISECONDS.toNanos(budgetMillis + 1));
					final CatalogRoutingSettlement overdue = scan.evaluateCatalogRouting(
						evita, server.getRegisteredApiProviders()
					);
					assertTrue(overdue.settled(), "Overdue publication must stop blocking readiness");
					assertEquals(0, overdue.catalogsPending());
					assertTrue(scan.evaluateCatalogRouting(evita, server.getRegisteredApiProviders()).settled());
					assertEquals(
						List.of(GraphQLProvider.CODE, RestProvider.CODE).stream().sorted().toList(),
						appender.list.stream()
							.filter(event -> event.getLevel() == Level.ERROR)
							.map(ILoggingEvent::getFormattedMessage)
							.filter(message -> message.contains("Catalog `" + CATALOG + "`"))
							.map(message -> message.split("`")[3])
							.sorted().toList()
					);
				} finally {
					logger.detachAppender(appender);
					appender.stop();
				}
			});
		}

		/**
		 * Holds both publications until the detector's periodic scan logs that they are overdue, then checks readiness
		 * while the routes are still unpublished.
		 *
		 * @throws Exception if startup, publication gates, or HTTP requests fail
		 */
		@Test
		@DisplayName("Logs overdue GraphQL and REST publications without a readiness request")
		void shouldLogOverdueCatalogPublicationWithoutReadinessRequests() throws Exception {
			CatalogReadinessRoutingTest.this.runCatalogReadinessScenario(
				false, false, 500L, (evita, server, endpoints, graphQLGate, restGate) -> {
					final Logger logger = (Logger) LoggerFactory.getLogger(CatalogRoutingReadiness.class);
					final OverduePublicationAppender appender = new OverduePublicationAppender();
					appender.start();
					logger.addAppender(appender);
					try {
						server.start();
						assertTrue(graphQLGate.get().entered().await(WAIT_SECONDS, SECONDS),
							"GraphQL publication never began");
						assertTrue(restGate.get().entered().await(WAIT_SECONDS, SECONDS),
							"REST publication never began");
						assertTrue(
							appender.overdue.await(WAIT_SECONDS, SECONDS),
							"Periodic scan did not log overdue publication of products for both graphQL and rest"
						);

						endpoints.poll(endpoints.readiness(), response -> response.statusCode() == 200);
						assertEquals(404, endpoints.send(endpoints.graphQL()).statusCode());
						assertEquals(404, endpoints.send(endpoints.rest()).statusCode());
					} finally {
						logger.detachAppender(appender);
						appender.stop();
					}
				}
			);
		}

		/**
		 * Verifies readiness when an embedding attaches to an engine that already finished loading.
		 *
		 * @throws Exception if startup or a request fails
		 */
		@Test
		@DisplayName("Becomes ready when the API server is wired after the engine load")
		void shouldBecomeReadyWhenWiredAfterLoading() throws Exception {
			CatalogReadinessRoutingTest.this
				.runCatalogReadinessScenario(true, false, null, (evita, server, endpoints, graphQLGate, restGate) -> {
				assertEquals(CatalogState.ALIVE, evita.getCatalogState(CATALOG).orElseThrow());
				server.start();
				endpoints.poll(endpoints.readiness(), response -> response.statusCode() == 200);
				assertEquals(404, endpoints.send(endpoints.graphQL()).statusCode());
				assertEquals(404, endpoints.send(endpoints.rest()).statusCode());
			});
		}
	}

	/**
	 * Signals when both APIs have logged their overdue startup publication for the test catalog.
	 */
	private static final class OverduePublicationAppender extends AppenderBase<ILoggingEvent> {
		private static final String OVERDUE_MESSAGE =
			"Catalog `%s` has not been published by API `%s` within the startup publication timeout.";
		private final CountDownLatch overdue = new CountDownLatch(2);
		/** Outstanding messages, accessed only by the serialized appender callback. */
		private final Set<String> expectedMessages = new HashSet<>(Set.of(
			OVERDUE_MESSAGE.formatted(CATALOG, GraphQLProvider.CODE),
			OVERDUE_MESSAGE.formatted(CATALOG, RestProvider.CODE)
		));

		/**
		 * Counts each expected error once.
		 *
		 * @param event log event from the catalog routing scan
		 */
		@Override
		protected void append(@Nonnull ILoggingEvent event) {
			if (event.getLevel() == Level.ERROR && this.expectedMessages.remove(event.getFormattedMessage())) {
				this.overdue.countDown();
			}
		}
	}

	/**
	 * Runs a scenario with isolated storage, ports and publication gates.
	 *
	 * @param lateWiring whether to finish the engine load before constructing the API server
	 * @param enableGrpc whether to expose the gRPC management service
	 * @param catalogPublicationTimeoutInMillis publication budget, or null to use the default
	 * @param scenario assertions to run with the fixture
	 * @throws Exception if the scenario or fixture fails
	 */
	private void runCatalogReadinessScenario(
		boolean lateWiring,
		boolean enableGrpc,
		@Nullable Long catalogPublicationTimeoutInMillis,
		@Nonnull CatalogReadinessScenario scenario
	) throws Exception {
		final TestPaths paths = createTestPaths(TEST_NAME);
		final PortManager portManager = getPortManager();
		final int[] ports = portManager.allocatePorts(TEST_NAME, enableGrpc ? 5 : 4);
		try {
			final EvitaConfiguration config = newTestEvitaConfigurationBuilder(paths)
				.server(
					ServerOptions.builder()
						.requestThreadPool(
							ThreadPoolOptions.requestThreadPoolBuilder()
								.minThreadCount(REQUEST_THREADS)
								.maxThreadCount(REQUEST_THREADS)
								.build()
						)
						.build()
				)
				.build();
			seedCatalog(config);
			final CountDownLatch release = new CountDownLatch(1);
			final AtomicReference<RegistrationGate> graphQLGate = new AtomicReference<>(new RegistrationGate(release));
			final AtomicReference<RegistrationGate> restGate = new AtomicReference<>(new RegistrationGate(release));
			try (final Evita evita = lateWiring ? new Evita(config) : new Evita(config, false, null, null, false)) {
				if (lateWiring) {
					evita.waitUntilFullyInitialized();
				}
				try (
					final ExternalApiServer server = new ExternalApiServer(
						evita, apiOptions(ports, catalogPublicationTimeoutInMillis), List.of(
							new SystemProviderRegistrar(), new ObservabilityProviderRegistrar(),
							new GrpcProviderRegistrar(),
							lateWiring ? new GraphQLProviderRegistrar() : new HeldGraphQLRegistrar(graphQLGate),
							lateWiring ? new RestProviderRegistrar() : new HeldRestRegistrar(restGate)
						)
					);
					final HttpClient client = HttpClient.newBuilder()
						.version(HttpClient.Version.HTTP_1_1)
						.connectTimeout(Duration.ofSeconds(WAIT_SECONDS))
						.build()
				) {
					try {
						scenario.run(evita, server, new Endpoints(client, ports), graphQLGate, restGate);
					} finally {
						graphQLGate.get().release().countDown();
						restGate.get().release().countDown();
					}
				}
			}
		} finally {
			portManager.releasePorts(TEST_NAME);
			cleanupTestPaths(paths);
		}
	}

	/**
	 * Assertions sharing the real startup fixture.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@FunctionalInterface
	private interface CatalogReadinessScenario {

		/**
		 * Exercises the engine and API server through the scenario's lifecycle.
		 *
		 * @param evita engine serving the APIs
		 * @param server server built over the engine
		 * @param endpoints requests against the server
		 * @param graphQLGate gate used by GraphQL publication
		 * @param restGate gate used by REST publication
		 * @throws Exception if a request or an awaited step fails
		 */
		void run(
			@Nonnull Evita evita,
			@Nonnull ExternalApiServer server,
			@Nonnull Endpoints endpoints,
			@Nonnull AtomicReference<RegistrationGate> graphQLGate,
			@Nonnull AtomicReference<RegistrationGate> restGate
		) throws Exception;
	}

	/**
	 * Starts the server, waits until both APIs are parked in the catalog's publication, the engine has finished
	 * every initial load and every API reports ready, then checks the routes and both probes.
	 *
	 * @param evita       engine booted with deferred loading, whose catalog load the server's start schedules
	 * @param server      API server built over the engine, not started yet
	 * @param graphQLGate gate the GraphQL publication of the catalog waits at
	 * @param restGate    gate the REST publication of the catalog waits at
	 * @param endpoints   requests sent to the started server
	 * @throws Exception when a request or a wait fails
	 */
	private static void assertNotReadyWhilePublicationIsHeld(
		@Nonnull Evita evita,
		@Nonnull ExternalApiServer server,
		@Nonnull RegistrationGate graphQLGate,
		@Nonnull RegistrationGate restGate,
		@Nonnull Endpoints endpoints
	) throws Exception {
		// opens the ports, then schedules the initial catalog loading
		server.start();
		assertTrue(graphQLGate.entered().await(WAIT_SECONDS, SECONDS), "GraphQL never started publishing the catalog");
		assertTrue(restGate.entered().await(WAIT_SECONDS, SECONDS), "REST never started publishing the catalog");
		assertEquals(CatalogState.ALIVE, evita.getCatalogState(CATALOG).orElse(null), "The catalog did not load");
		// Prove the engine finished loading even though the catalog is still unavailable through the APIs.
		CompletableFuture.allOf(evita.getInitialLoadCatalogFutures()).get(WAIT_SECONDS, SECONDS);
		evita.waitUntilFullyInitialized();

		final JsonNode allApisReady = JSON.readTree(
			"{\"graphQL\": \"ready\", \"gRPC\": \"ready\", \"observability\": \"ready\", "
				+ "\"rest\": \"ready\", \"system\": \"ready\"}"
		);
		// the body lists the per-API states whatever the status is, so this waits for the probes, not for a status
		final HttpResponse<String> readiness = endpoints.poll(
			endpoints.readiness(), response -> allApisReady.equals(readApiStates(response.body()))
		);
		final int graphQLStatus = endpoints.send(endpoints.graphQL()).statusCode();
		final int restStatus = endpoints.send(endpoints.rest()).statusCode();
		final HttpResponse<String> liveness = endpoints.poll(
			endpoints.liveness(), response -> response.statusCode() == 200
		);

		final String observed = "/system/readiness answered " + readiness.statusCode() + " with body:\n" +
			readiness.body() + "\nwhile POST /gql/" + CATALOG + " answered " + graphQLStatus +
			" and GET /rest/" + CATALOG + " answered " + restStatus;
		assertEquals(404, graphQLStatus, () -> "GraphQL routes the catalog whose publication is held; " + observed);
		assertEquals(404, restStatus, () -> "REST routes the catalog whose publication is held; " + observed);
		assertEquals(
			200, liveness.statusCode(),
			() -> "/system/liveness answered " + liveness.statusCode() + " " + liveness.body() + "; " + observed
		);
		assertEquals(503, readiness.statusCode(), () -> "Ready before the catalog is published: " + observed);
		final JsonNode readinessBody = JSON.readTree(readiness.body());
		final JsonNode expectedPending = JSON.readTree("1");
		assertAll(
			() -> assertEquals("STARTING", readinessBody.path("status").asText(), "Pending catalog readiness status"),
			() -> assertEquals(expectedPending, readinessBody.path("catalogsPending"), "Pending catalog count")
		);
	}

	/**
	 * After the release, readiness reaches `200` and both catalog routes serve a real request.
	 *
	 * @param endpoints requests sent to the started server
	 * @throws Exception when a request fails or a response does not arrive within the wait bound
	 */
	private static void assertReadyOncePublicationIsReleased(@Nonnull Endpoints endpoints) throws Exception {
		endpoints.poll(endpoints.readiness(), response -> response.statusCode() == 200);
		final HttpResponse<String> graphQL = endpoints.poll(
			endpoints.graphQL(), response -> response.statusCode() == 200
		);
		assertTrue(JSON.readTree(graphQL.body()).path("data").hasNonNull("__typename"), graphQL::body);
		final HttpResponse<String> rest = endpoints.poll(endpoints.rest(), response -> response.statusCode() == 200);
		assertTrue(JSON.readTree(rest.body()).hasNonNull("openapi"), rest::body);
	}

	/**
	 * Writes an ALIVE catalog with one collection and one entity, then closes the engine that wrote it.
	 *
	 * @param config configuration whose storage directory the boot under test reads afterwards
	 */
	private static void seedCatalog(@Nonnull EvitaConfiguration config) {
		try (final Evita seed = new Evita(config)) {
			seed.defineCatalog(CATALOG);
			seed.updateCatalog(CATALOG, session -> {
				session.defineEntitySchema("Product").withoutGeneratedPrimaryKey()
					.withAttribute("code", String.class).updateVia(session);
				session.upsertEntity(session.createNewEntity("Product", 1).setAttribute("code", "first"));
				session.goLiveAndClose();
			});
			assertEquals(CatalogState.ALIVE, seed.getCatalogState(CATALOG).orElse(null));
		}
	}

	/**
	 * Enables the test APIs on separate loopback ports over plain HTTP.
	 *
	 * @param ports ports for system, observability, GraphQL, REST and optionally gRPC
	 * @param catalogPublicationTimeoutInMillis publication budget, or null to use the default
	 * @return options enabling the APIs with allocated ports
	 */
	@Nonnull
	private static ApiOptions apiOptions(@Nonnull int[] ports, @Nullable Long catalogPublicationTimeoutInMillis) {
		final String noTls = TlsMode.FORCE_NO_TLS.name();
		final String observabilityAddress = address(ports[1]);
		final ObservabilityOptions observabilityOptions = catalogPublicationTimeoutInMillis == null
			? new ObservabilityOptions(observabilityAddress)
			: new ObservabilityOptions(
				true, observabilityAddress, null, noTls, null, null, null, null, null, null, null,
				catalogPublicationTimeoutInMillis
			);
		final ApiOptions.Builder apiOptions = ApiOptions.builder()
			.certificate(CertificateOptions.builder().generateAndUseSelfSigned(false).build())
			.enable(SystemProvider.CODE, new SystemOptions(address(ports[0])))
			.enable(ObservabilityProvider.CODE, observabilityOptions)
			.enable(GraphQLProvider.CODE, new GraphQLOptions(
				true, address(ports[2]), null, noTls, null, null, null, null, null, null, null
			))
			.enable(RestProvider.CODE, new RestOptions(true, address(ports[3]), null, noTls, null, null, null));
		if (ports.length > 4) {
			apiOptions.enable(GrpcProvider.CODE, new GrpcOptions(
				true, address(ports[4]), null, noTls, null, null, null, null, null
			));
		}
		return apiOptions.build();
	}

	/**
	 * Returns the loopback address shared by the server options and the HTTP client.
	 *
	 * @param port allocated port of one API
	 * @return `host:port` of the IPv4 loopback, without a scheme
	 */
	@Nonnull
	private static String address(int port) {
		return "127.0.0.1:" + port;
	}

	/**
	 * Returns the per-API states of a readiness body.
	 *
	 * @param body readiness response body
	 * @return the `apis` object mapping API codes to `ready` / `not ready`, or a missing node when the body (for
	 *         example `UNKNOWN` or `SHUTDOWN`) carries none or is not JSON - the server answers the plain text
	 *         `Request timed out.` when a probe outlives its request timeout under load
	 */
	@Nonnull
	private static JsonNode readApiStates(@Nonnull String body) {
		try {
			return JSON.readTree(body).path("apis");
		} catch (IOException ignored) {
			return MissingNode.getInstance();
		}
	}

	/**
	 * The probe and catalog requests the test sends, and the client sending them.
	 *
	 * @param client    HTTP/1.1 client of every request
	 * @param readiness GET of the readiness probe
	 * @param liveness  GET of the liveness probe
	 * @param graphQL   cheapest valid query of the catalog's GraphQL data API
	 * @param rest      GET of the catalog's REST OpenAPI specification
	 * @param grpcPort  port of the gRPC management service, or zero when disabled
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private record Endpoints(
		@Nonnull HttpClient client,
		@Nonnull HttpRequest readiness,
		@Nonnull HttpRequest liveness,
		@Nonnull HttpRequest graphQL,
		@Nonnull HttpRequest rest,
		int grpcPort
	) {

		/**
		 * Builds the requests against the test server.
		 *
		 * @param client HTTP/1.1 client of every request
		 * @param ports  ports for system, observability, GraphQL, REST and optionally gRPC
		 */
		private Endpoints(@Nonnull HttpClient client, @Nonnull int[] ports) {
			this(
				client,
				request(ports[0], "/system/readiness").GET().build(),
				request(ports[0], "/system/liveness").GET().build(),
				request(ports[2], "/gql/" + CATALOG)
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString("{\"query\": \"{ __typename }\"}"))
					.build(),
				request(ports[3], "/rest/" + CATALOG).GET().build(),
				ports.length > 4 ? ports[4] : 0
			);
		}

		/**
		 * Starts a bounded JSON request to the given port and path.
		 *
		 * @param port allocated port of the API serving the path
		 * @param path absolute path, starting with the API prefix
		 * @return builder with the URI, the wait bound as timeout and a JSON `Accept` header; method not set yet
		 */
		@Nonnull
		private static HttpRequest.Builder request(int port, @Nonnull String path) {
			return HttpRequest.newBuilder(URI.create("http://" + address(port) + path))
				.timeout(Duration.ofSeconds(WAIT_SECONDS))
				.header("Accept", "application/json");
		}

		/**
		 * Sends the request and returns the response with its body, whatever its status.
		 *
		 * @param request one of this record's requests
		 * @return the response, including error statuses
		 * @throws IOException          when the request cannot be sent or answered
		 * @throws InterruptedException when the test thread is interrupted while waiting
		 */
		@Nonnull
		private HttpResponse<String> send(@Nonnull HttpRequest request) throws IOException, InterruptedException {
			return this.client.send(request, HttpResponse.BodyHandlers.ofString());
		}

		/**
		 * Repeats the request until a response satisfies `accept`, and fails with the last status and body when none
		 * does within the wait bound.
		 *
		 * @param request one of this record's requests
		 * @param accept  condition the awaited response meets
		 * @return the first response meeting `accept`
		 * @throws IOException          when the request cannot be sent or answered
		 * @throws InterruptedException when the test thread is interrupted while waiting
		 */
		@Nonnull
		private HttpResponse<String> poll(
			@Nonnull HttpRequest request, @Nonnull Predicate<HttpResponse<String>> accept
		) throws IOException, InterruptedException {
			final long deadline = System.nanoTime() + SECONDS.toNanos(WAIT_SECONDS);
			HttpResponse<String> response;
			do {
				response = send(request);
				if (accept.test(response)) {
					return response;
				}
			} while (System.nanoTime() - deadline < 0);
			return fail(
				request.method() + " " + request.uri() + " last answered " + response.statusCode() + " with body:\n" +
					response.body()
			);
		}
	}

	/**
	 * Parks one API's publication of the catalog at its entry until the test releases it, and tells the test when
	 * the publication has arrived there.
	 *
	 * @param entered counted down when the API starts publishing the catalog
	 * @param release opened by the test to let every held publication continue
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private record RegistrationGate(@Nonnull CountDownLatch entered, @Nonnull CountDownLatch release) {

		/**
		 * Creates a gate of one API, sharing the release with the other APIs.
		 *
		 * @param release opened by the test to let every held publication continue
		 */
		private RegistrationGate(@Nonnull CountDownLatch release) {
			this(new CountDownLatch(1), release);
		}

		/**
		 * Holds a publication of the test catalog until the release; other catalogs pass. The wait is bounded only so
		 * that a broken test cannot leave the thread parked for good - the test always releases it far earlier.
		 *
		 * @param catalogName name of the catalog the API is about to publish
		 * @throws IllegalStateException when the release does not come within the bound, or the wait is interrupted
		 */
		private void pass(@Nonnull String catalogName) {
			if (CATALOG.equals(catalogName)) {
				this.entered.countDown();
				try {
					if (!this.release.await(5, MINUTES)) {
						throw new IllegalStateException(
							"The test never released the publication of `" + catalogName + "`"
						);
					}
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(
						"The held publication of `" + catalogName + "` was interrupted", ex
					);
				}
			}
		}
	}

	/**
	 * Builds the production GraphQL provider exactly as {@link GraphQLProviderRegistrar} does, around a manager whose
	 * catalog publication waits at the gate.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class HeldGraphQLRegistrar extends GraphQLProviderRegistrar {
		/** Gate the manager's publication waits at. */
		private final AtomicReference<RegistrationGate> gate;

		/**
		 * Creates the registrar handing the gate to its manager.
		 *
		 * @param gate gate the GraphQL publication of the catalog waits at
		 */
		private HeldGraphQLRegistrar(@Nonnull AtomicReference<RegistrationGate> gate) {
			this.gate = gate;
		}

		/**
		 * Mirrors the production registration with the gated manager.
		 *
		 * @param evita engine served by the API
		 * @param externalApiServer owning API server
		 * @param apiOptions shared API configuration
		 * @param endpointOptions endpoint configuration
		 * @return provider using the gated manager
		 */
		@Nonnull
		@Override
		public ExternalApiProvider<GraphQLOptions> register(
			@Nonnull Evita evita,
			@Nonnull ExternalApiServer externalApiServer,
			@Nonnull ApiOptions apiOptions,
			@Nonnull GraphQLOptions endpointOptions
		) {
			final GraphQLManager graphQLManager = new HeldGraphQLManager(
				evita, apiOptions.headers(), endpointOptions, this.gate
			);
			return new GraphQLProvider(endpointOptions, graphQLManager, apiOptions.requestTimeoutInMillis());
		}
	}

	/**
	 * The production GraphQL manager whose catalog publication first waits at the gate.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class HeldGraphQLManager extends GraphQLManager {
		/** Gate the publication waits at; `null` while the superclass constructor subscribes. */
		private final AtomicReference<RegistrationGate> gate;

		/**
		 * Subscribes the production observer, then installs the gate.
		 *
		 * @param evita         engine whose system change capture the manager subscribes to
		 * @param headers       header configuration of the API server
		 * @param graphQLConfig GraphQL endpoint configuration
		 * @param gate          gate the catalog publication waits at
		 */
		private HeldGraphQLManager(
			@Nonnull Evita evita,
			@Nonnull HeaderOptions headers,
			@Nonnull GraphQLOptions graphQLConfig,
			@Nonnull AtomicReference<RegistrationGate> gate
		) {
			super(evita, headers, graphQLConfig);
			this.gate = gate;
		}

		/**
		 * Waits at the current gate, then publishes through the production manager.
		 *
		 * @param catalogName catalog whose endpoints are being published
		 * @return whether publication succeeded
		 */
		@Override
		public boolean registerCatalog(@Nonnull String catalogName) {
			// the superclass constructor subscribes, and its observer may call in before the gate is assigned
			if (this.gate != null) {
				this.gate.get().pass(catalogName);
			}
			return super.registerCatalog(catalogName);
		}
	}

	/**
	 * Builds the production REST provider exactly as {@link RestProviderRegistrar} does, around a manager whose
	 * catalog publication waits at the gate.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class HeldRestRegistrar extends RestProviderRegistrar {
		/** Gate the manager's publication waits at. */
		private final AtomicReference<RegistrationGate> gate;

		/**
		 * Creates the registrar handing the gate to its manager.
		 *
		 * @param gate gate the REST publication of the catalog waits at
		 */
		private HeldRestRegistrar(@Nonnull AtomicReference<RegistrationGate> gate) {
			this.gate = gate;
		}

		/**
		 * Mirrors the production registration with the gated manager.
		 *
		 * @param evita engine served by the API
		 * @param externalApiServer owning API server
		 * @param apiOptions shared API configuration
		 * @param endpointOptions endpoint configuration
		 * @return provider using the gated manager
		 */
		@Nonnull
		@Override
		public ExternalApiProvider<RestOptions> register(
			@Nonnull Evita evita,
			@Nonnull ExternalApiServer externalApiServer,
			@Nonnull ApiOptions apiOptions,
			@Nonnull RestOptions endpointOptions
		) {
			final RestManager restManager = new HeldRestManager(
				evita, apiOptions.headers(), endpointOptions, this.gate
			);
			return new RestProvider(endpointOptions, restManager, apiOptions.requestTimeoutInMillis());
		}
	}

	/**
	 * The production REST manager whose catalog publication first waits at the gate.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class HeldRestManager extends RestManager {
		/** Gate the publication waits at; `null` while the superclass constructor subscribes. */
		private final AtomicReference<RegistrationGate> gate;

		/**
		 * Subscribes the production observer, then installs the gate.
		 *
		 * @param evita         engine whose system change capture the manager subscribes to
		 * @param headerOptions header configuration of the API server
		 * @param restOptions   REST endpoint configuration
		 * @param gate          gate the catalog publication waits at
		 */
		private HeldRestManager(
			@Nonnull Evita evita,
			@Nonnull HeaderOptions headerOptions,
			@Nonnull RestOptions restOptions,
			@Nonnull AtomicReference<RegistrationGate> gate
		) {
			super(evita, headerOptions, restOptions);
			this.gate = gate;
		}

		/**
		 * Waits at the current gate, then publishes through the production manager.
		 *
		 * @param catalogName catalog whose endpoints are being published
		 * @return whether publication succeeded
		 */
		@Override
		public boolean registerCatalog(@Nonnull String catalogName) {
			// the superclass constructor subscribes, and its observer may call in before the gate is assigned
			if (this.gate != null) {
				this.gate.get().pass(catalogName);
			}
			return super.registerCatalog(catalogName);
		}
	}
}
