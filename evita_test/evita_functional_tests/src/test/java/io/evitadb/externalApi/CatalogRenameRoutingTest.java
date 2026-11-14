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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.*;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.graphql.GraphQLManager;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import io.evitadb.externalApi.rest.RestManager;
import io.evitadb.externalApi.rest.configuration.RestOptions;
import io.evitadb.test.EvitaTestSupport;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.AbstractList;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real engine mutations delivered through system CDC to real managers, builders and HTTP routers.
 * Manager overrides observe existing call boundaries without replacing any successful production operation.
 * Header and GraphQL configuration reads supply deterministic build observations and failures. Routing state is read
 * only through requests the real routers serve, and build statistics only through the events the managers emit.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(REST)
@Tag(GRAPHQL)
@Tag(EXTERNAL_API)
@Tag(MANAGEMENT)
@DisplayName("Catalog rename and replacement routing")
class CatalogRenameRoutingTest implements EvitaTestSupport {
	/** Stock values also installed into the JVM-wide HTTP tracing context by these fixtures. */
	private static final HeaderOptions DEFAULT_HEADERS = new HeaderOptions();
	/** REST route of the collection only the renamed source has, so only its API routes it. */
	private static final String TARGET_SOURCE_ONLY_PATH = "/target/SourceOnly/get";
	/** Isolated paths and engine, closed after each independent lifecycle scenario. */
	private TestPaths paths;
	/** Real engine, restarted once to settle the initial catalog fixture. */
	private Evita evita;

	/** Starts the real engine with isolated storage. */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("CatalogRenameRoutingTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
	}

	/** Drains CDC before deleting fixture directories. */
	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/** Both rename forms keep the source until publication, and overwrite keeps the old target during assembly. */
	@ParameterizedTest(name = "graphql={0}, overwrite={1}")
	@CsvSource({"false, false", "false, true", "true, false", "true, true"})
	@DisplayName("Publish the new API before removing the source; leave the neighbour catalog untouched")
	void shouldPublishBeforeRemovingSource(boolean graphql, boolean overwrite) throws Exception {
		exercise(graphql, overwrite, null);
	}

	/** Internal errors are swallowed on first registration; unchecked exceptions escape. Both must clean up. */
	@ParameterizedTest(name = "graphql={0}, overwrite={1}, internal={2}")
	@CsvSource({
		"false, false, true", "false, true, true", "true, false, true", "true, true, true",
		"false, false, false", "false, true, false", "true, false, false", "true, true, false"
	})
	@DisplayName("Failed assembly removes both names and their statistics, then permits registration again")
	void shouldRemoveBothNamesWhenAssemblyFails(boolean graphql, boolean overwrite, boolean internal) throws Exception {
		exercise(graphql, overwrite, internal ? new GenericEvitaInternalError("Deliberate assembly failure") :
			new IllegalStateException("Deliberate assembly failure"));
	}

	/** A CORS construction failure happens after the old REST implementation recorded registration. */
	@Test
	@DisplayName("Failed REST publication leaves no registration and refresh can retry with statistics")
	void shouldRetryRestRegistrationAfterPublicationFails() throws Exception {
		createCatalog("target", "retry-target");
		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.waitUntilFullyInitialized();
		final PublicationFailureHeaders headers = new PublicationFailureHeaders();
		final RestManager manager = new RestManager(
			this.evita, headers.headers(), new RestOptions("localhost:5555")
		);
		headers.fail = true;
		assertFalse(manager.registerCatalog("target"));
		assertEquals(1, headers.failures, "The real router publication seam must throw exactly once");
		assertFalse(manager.isCatalogRegistered("target"), "Failed publication left the catalog registered");
		assertFalse(routed(manager.getRestRouter(), "/target"), "Failed publication left the catalog routed");
		assertFalse(hasBuildStatistics(() -> manager.emitObservabilityEvents("target")));

		headers.fail = false;
		assertTrue(manager.refreshCatalog("target"));
		assertTrue(manager.isCatalogRegistered("target"));
		assertTrue(routed(manager.getRestRouter(), "/target"));
		assertTrue(hasBuildStatistics(() -> manager.emitObservabilityEvents("target")));
		assertData(manager.getRestRouter(), false, "target", "retry-target");
	}

	/** Loads settled catalogs before subscribing, excluding unrelated initial host events from the observations. */
	private void exercise(boolean graphql, boolean overwrite, @Nullable RuntimeException failure) throws Exception {
		createCatalog("source", "new-source");
		createCatalog("neighbour", "unchanged");
		if (overwrite) {
			createCatalog("target", "old-target");
		}
		// No initial creation/schema/host notification may still refresh the neighbour during the assertion.
		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.waitUntilFullyInitialized();
		final Observation observation = new Observation(this.evita, graphql, overwrite, failure);
		final HttpService router;
		final Predicate<String> registered;
		final Predicate<String> register;
		final Consumer<String> emit;
		if (graphql) {
			final ObservedGraphQLManager manager = new ObservedGraphQLManager(this.evita, observation);
			router = manager.getGraphQLRouter();
			registered = manager::isCatalogRegistered;
			register = manager::registerCatalog;
			emit = manager::emitObservabilityEvents;
		} else {
			final ObservedRestManager manager = new ObservedRestManager(this.evita, observation);
			router = manager.getRestRouter();
			registered = manager::isCatalogRegistered;
			register = manager::registerCatalog;
			emit = manager::emitObservabilityEvents;
		}
		observation.router = router;
		for (final String name : this.evita.getCatalogNames()) {
			assertTrue(register.test(name));
		}
		assertData(router, graphql, "source", "new-source");
		assertData(router, graphql, "neighbour", "unchanged");
		if (overwrite) {
			assertData(router, graphql, "target", "old-target");
		}
		// the routing probe must tell a bound prefix from a missing one before the rename relies on it
		assertTrue(routed(router, "/neighbour"));
		assertEquals(overwrite, routed(router, "/target"));
		if (!graphql) {
			assertFalse(routed(router, TARGET_SOURCE_ONLY_PATH), "The old target already routes the source collection");
		}
		// routed to the overwritten target's GraphQL handler now, answered only once its body arrives after the rename
		final InFlightQuery inFlight = graphql && overwrite && failure == null ?
			InFlightQuery.start(router, "/target") : null;
		observation.armed = true;
		if (overwrite) {
			this.evita.replaceCatalog("source", "target");
		} else {
			this.evita.renameCatalog("source", "target");
		}
		assertTrue(observation.removed.await(30, TimeUnit.SECONDS), "Source cleanup was not observed");
		// the observer has returned; calls the test makes below are not part of the rename
		observation.armed = false;
		assertNull(observation.error.get(), () -> "Ordering observation failed: " + observation.error.get());
		assertTrue(observation.refreshObserved, "The mutation did not use refresh");
		assertTrue(observation.assemblyObserved, "The real API build seam was not reached");
		assertEquals(failure == null ? 1 : 0, observation.emissions, "Unexpected rename observability events");
		assertFalse(registered.test("source"));
		assertFalse(routed(router, "/source"));
		assertEquals(HttpStatus.NOT_FOUND, request(router, HttpMethod.GET, "/source", null).status());
		assertFalse(hasBuildStatistics(() -> emit.accept("source")));
		assertEquals(failure == null, registered.test("target"));
		assertTrue(routed(router, "/neighbour"));
		assertData(router, graphql, "neighbour", "unchanged");
		if (failure == null) {
			// a refreshed target keeps its statistics and counts a second build; a renamed one is built once
			assertEquals(overwrite ? "REFRESH" : "NEW", reportedBuildType(graphql, "target", emit));
			assertData(router, graphql, "target", "new-source");
			assertSourceSchema(router, graphql, "target");
			if (inFlight != null) {
				final JsonNode json = inFlight.answer(
					"{\"query\":\"{ getSourceOnly(primaryKey: 1) { primaryKey attributes { code } } }\"}"
				);
				assertEquals(
					"source-only", json.path("data").path("getSourceOnly").path("attributes").path("code").asText(),
					"A request routed before the refresh reached the replaced instance: " + json
				);
			}
		} else {
			assertFalse(hasBuildStatistics(() -> emit.accept("target")));
			assertFalse(routed(router, "/target"));
			assertEquals(HttpStatus.NOT_FOUND, request(router, HttpMethod.GET, "/target", null).status());
			if (graphql) {
				assertEquals(HttpStatus.NOT_FOUND, request(router, HttpMethod.GET, "/target/schema", null).status());
			}
			assertTrue(register.test("target"));
			emit.accept("target");
			assertData(router, graphql, "target", "new-source");
		}
	}

	/** Gives every catalog distinguishable data, and only the source an additional collection. */
	private void createCatalog(@Nonnull String name, @Nonnull String code) {
		this.evita.defineCatalog(name);
		this.evita.updateCatalog(name, session -> {
			session.defineEntitySchema("Product").withoutGeneratedPrimaryKey()
				.withAttribute("code", String.class).updateVia(session);
			session.upsertEntity(session.createNewEntity("Product", 1).setAttribute("code", code));
			if (name.equals("source")) {
				session.defineEntitySchema("SourceOnly").withoutGeneratedPrimaryKey()
					.withAttribute("code", String.class).updateVia(session);
				session.upsertEntity(session.createNewEntity("SourceOnly", 1).setAttribute("code", "source-only"));
			}
			session.goLiveAndClose();
		});
	}

	/** Checks exact entity data through the real REST or GraphQL execution pipeline. */
	private static void assertData(
		@Nonnull HttpService router, boolean graphql, @Nonnull String name, @Nonnull String code
	) throws Exception {
		final AggregatedHttpResponse response = graphql ?
			request(router, HttpMethod.POST, "/" + name,
				"{\"query\":\"{ getProduct(primaryKey: 1) { primaryKey attributes { code } } }\"}") :
			request(router, HttpMethod.GET,
				"/" + name + "/Product/get?primaryKey=1&bodyFetch=true&attributeContent=code", null);
		assertEquals(HttpStatus.OK, response.status(), response.contentUtf8());
		final JsonNode json = new ObjectMapper().readTree(response.contentUtf8());
		assertFalse(json.has("errors"), response.contentUtf8());
		final JsonNode entity = graphql ? json.path("data").path("getProduct") : json;
		assertEquals(1, entity.path("primaryKey").asInt(), response.contentUtf8());
		assertEquals(code, graphql ? entity.path("attributes").path("code").asText() :
			entity.path("attributes").path("global").path("code").asText(), response.contentUtf8());
	}

	/** Requires source-only generated fields on each GraphQL API, or the new collection's REST endpoint. */
	private static void assertSourceSchema(@Nonnull HttpService router, boolean graphql, @Nonnull String name)
		throws Exception {
		if (graphql) {
			assertAll(
				() -> assertSourceGraphQLSchema(router, "/" + name, "getSourceOnly("),
				() -> assertSourceGraphQLSchema(router, "/" + name + "/schema", "getSourceOnlySchema:")
			);
		} else {
			final AggregatedHttpResponse response = request(router, HttpMethod.GET,
				"/" + name + "/SourceOnly/get?primaryKey=1&bodyFetch=true&attributeContent=code", null);
			assertEquals(HttpStatus.OK, response.status(), response.contentUtf8());
			final JsonNode entity = new ObjectMapper().readTree(response.contentUtf8());
			assertEquals("SourceOnly", entity.path("type").asText(), response.contentUtf8());
			assertEquals("source-only", entity.path("attributes").path("global").path("code").asText(),
				response.contentUtf8());
		}
	}

	/** Fetches the generated SDL itself so catalog name lookup cannot conceal a stale GraphQL instance. */
	private static void assertSourceGraphQLSchema(
		@Nonnull HttpService router, @Nonnull String path, @Nonnull String generatedField
	) throws Exception {
		final AggregatedHttpResponse response = request(router, HttpMethod.GET, path, null);
		assertEquals(HttpStatus.OK, response.status(), response.contentUtf8());
		assertTrue(response.contentUtf8().contains(generatedField), path + " lacks " + generatedField);
	}

	/**
	 * Tells whether a router binds the path, without running any handler: no catalog endpoint accepts TRACE, so the
	 * endpoint router of a bound prefix answers 405 with the methods it does route, while an unbound one is a 404.
	 */
	private static boolean routed(@Nonnull HttpService router, @Nonnull String path) throws Exception {
		final HttpStatus status = request(router, HttpMethod.TRACE, path, null).status();
		assertTrue(status == HttpStatus.METHOD_NOT_ALLOWED || status == HttpStatus.NOT_FOUND,
			() -> "TRACE " + path + " answered " + status);
		return status == HttpStatus.METHOD_NOT_ALLOWED;
	}

	/** Emitting a catalog's build events succeeds exactly while the manager holds build statistics for it. */
	private static boolean hasBuildStatistics(@Nonnull Runnable emission) {
		try {
			emission.run();
			return true;
		} catch (EvitaInternalError ex) {
			assertTrue(ex.getPrivateMessage().startsWith("No build statistics found"), ex::getPrivateMessage);
			return false;
		}
	}

	/** Returns the build type of the catalog's emitted build events: NEW after one build, REFRESH after more. */
	@Nonnull
	private static String reportedBuildType(boolean graphql, @Nonnull String name, @Nonnull Consumer<String> emit) {
		final Class<? extends Event> builtEvent = graphql ?
			io.evitadb.externalApi.graphql.metric.event.instance.BuiltEvent.class :
			io.evitadb.externalApi.rest.metric.event.instance.BuiltEvent.class;
		final Set<String> buildTypes = new ConcurrentSkipListSet<>();
		try (final RecordingStream stream = new RecordingStream()) {
			stream.enable(builtEvent);
			stream.onEvent(EventType.getEventType(builtEvent).getName(), event -> {
				if (name.equals(event.getString("catalogName"))) {
					buildTypes.add(event.getString("buildType"));
				}
			});
			stream.startAsync();
			emit.accept(name);
			// waits until the stream has consumed every event recorded so far
			stream.stop();
		}
		assertEquals(1, buildTypes.size(), () -> "Expected one build type for `" + name + "`: " + buildTypes);
		return buildTypes.iterator().next();
	}

	/** Sends ordinary HTTP requests directly into the real router and aggregates with a bounded wait. */
	@Nonnull
	private static AggregatedHttpResponse request(
		@Nonnull HttpService router, @Nonnull HttpMethod method, @Nonnull String path, @Nullable String body
	) throws Exception {
		final RequestHeadersBuilder headers = RequestHeaders.builder(method, path)
			.scheme("http").authority("localhost").add(HttpHeaderNames.ACCEPT, "*/*");
		final HttpRequest request = body == null ? HttpRequest.of(headers.build()) :
			HttpRequest.of(headers.contentType(MediaType.JSON).build(), HttpData.ofUtf8(body));
		return serve(router, request).get(30, TimeUnit.SECONDS);
	}

	/** Routes the request at once; the response completes when the handler has the whole request body. */
	@Nonnull
	private static CompletableFuture<AggregatedHttpResponse> serve(
		@Nonnull HttpService router, @Nonnull HttpRequest request
	) throws Exception {
		return router.serve(ServiceRequestContext.builder(request).build(), request).aggregate();
	}

	/**
	 * A GraphQL POST routed to its endpoint handler when started, whose body is written only later.
	 *
	 * @param request  open request stream the body is written to
	 * @param response response of the handler selected when the request was routed
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private record InFlightQuery(
		@Nonnull HttpRequestWriter request, @Nonnull CompletableFuture<AggregatedHttpResponse> response
	) {

		/** Routes a bodiless query now and checks that its handler waits for the body. */
		@Nonnull
		private static InFlightQuery start(@Nonnull HttpService router, @Nonnull String path) throws Exception {
			final HttpRequestWriter request = HttpRequest.streaming(
				RequestHeaders.builder(HttpMethod.POST, path)
					.scheme("http").authority("localhost").add(HttpHeaderNames.ACCEPT, "*/*")
					.contentType(MediaType.JSON).build()
			);
			final CompletableFuture<AggregatedHttpResponse> response = serve(router, request);
			assertFalse(response.isDone(), "The routed query must wait for its body");
			return new InFlightQuery(request, response);
		}

		/** Completes the request with the query and returns the handler's successful JSON answer. */
		@Nonnull
		private JsonNode answer(@Nonnull String body) throws Exception {
			this.request.write(HttpData.ofUtf8(body));
			this.request.close();
			final AggregatedHttpResponse result = this.response.get(30, TimeUnit.SECONDS);
			assertEquals(HttpStatus.OK, result.status(), result.contentUtf8());
			return new ObjectMapper().readTree(result.contentUtf8());
		}
	}

	/**
	 * Captures assertions instead of throwing them into the observer's catch-all; the test thread reports them.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class Observation extends AbstractList<String> {
		/** Engine whose completed rename gates observations. */
		private final Evita evita;
		/** Whether the routers under observation are GraphQL ones, which have no per-collection routes. */
		private final boolean graphql;
		/** Whether the target has an old API that must survive assembly. */
		private final boolean overwrite;
		/** Optional exception raised by real configuration traversal during API construction. */
		private final RuntimeException failure;
		/** Signals completion of source cleanup on the CDC thread. */
		private final CountDownLatch removed = new CountDownLatch(1);
		/** First failed observation, reported on the test thread. */
		private final AtomicReference<Throwable> error = new AtomicReference<>();
		/** Real router probed during assembly and cleanup. */
		private HttpService router;
		/** Enables rename assertions only after initial registration completes and until the rename is handled. */
		private volatile boolean armed;
		/** True only while the target API is being assembled. */
		private boolean assembling;
		/** Proves the mutation reached the refresh path. */
		private boolean refreshObserved;
		/** Proves real API construction consumed the configured options. */
		private boolean assemblyObserved;
		/** Number of successful target event emissions for the rename. */
		private int emissions;

		/** Configures a successful build or a failure from real configuration traversal. */
		private Observation(
			@Nonnull Evita evita, boolean graphql, boolean overwrite, @Nullable RuntimeException failure
		) {
			this.evita = evita;
			this.graphql = graphql;
			this.overwrite = overwrite;
			this.failure = failure;
		}

		/** Supplies real header configuration whose ordinary traversal observes endpoint assembly. */
		@Nonnull
		private HeaderOptions headers() {
			return new HeaderOptions(DEFAULT_HEADERS.forwardedUri(), DEFAULT_HEADERS.forwardedFor(),
				DEFAULT_HEADERS.label(), DEFAULT_HEADERS.clientId(), this);
		}

		/** A rename must not reach the unrelated catalog: any manager operation on it would rebuild or drop its API. */
		private void beforeOperation(@Nonnull String name) {
			if (this.armed && name.equals("neighbour")) {
				observe(() -> fail("The rename operated on the unrelated catalog `" + name + "`"));
			}
		}

		/** Checks the source/target at the existing refresh boundary, after the engine has renamed the catalog. */
		private void beforeRefresh(@Nonnull String name) {
			beforeOperation(name);
			if (this.armed && name.equals("target") && !this.evita.getCatalogNames().contains("source")) {
				this.refreshObserved = true;
				this.assembling = true;
				observe(() -> {
					assertTrue(routed(this.router, "/source"), "Source removed before building target");
					assertEquals(this.overwrite, routed(this.router, "/target"),
						"Overwritten target removed before refresh");
				});
			}
		}

		/** At the existing source-removal boundary, the successful target must already have been published. */
		private void beforeRemove(@Nonnull String name) {
			beforeOperation(name);
			if (this.armed && name.equals("source")) {
				observe(() -> {
					assertTrue(this.refreshObserved, "Source removal preceded target refresh");
					assertEquals(this.failure == null, routed(this.router, "/target"),
						"Wrong target routing state at source cleanup");
					if (!this.graphql) {
						// REST publishes the whole new router, so only it routes the source's own collection
						assertEquals(this.failure == null, routed(this.router, TARGET_SOURCE_ONLY_PATH),
							"Wrong target API at source cleanup");
					}
				});
				this.assembling = false;
			}
		}

		/** Signals after the actual source removal, providing happens-before for every final assertion. */
		private void afterRemove(@Nonnull String name) {
			if (this.armed && name.equals("source")) {
				this.removed.countDown();
			}
		}

		/** Preserves the first assertion from the CDC thread for the main test thread. */
		private void observe(@Nonnull Executable check) {
			try {
				check.execute();
			} catch (Throwable ex) {
				this.error.compareAndSet(null, ex);
			}
		}

		/** Checks routes during real API construction, and optionally fails that build once. */
		private void duringBuild() {
			if (this.assembling) {
				observe(() -> {
					assertTrue(routed(this.router, "/source"), "Source missing during assembly");
					assertEquals(this.overwrite, routed(this.router, "/target"),
						"Target missing during replacement assembly");
					if (!this.graphql) {
						assertFalse(routed(this.router, TARGET_SOURCE_ONLY_PATH),
							"Target changed before its API was assembled");
					}
				});
				this.assemblyObserved = true;
				if (this.failure != null) {
					this.assembling = false;
					throw this.failure;
				}
			}
		}

		/** Observes REST endpoint assembly through its real CORS header configuration read. */
		@Nonnull
		@Override
		public String get(int index) {
			if (index != 0) {
				throw new IndexOutOfBoundsException(index);
			}
			duringBuild();
			return DEFAULT_HEADERS.traceParent().get(index);
		}

		/** One ordinary configured header is enough to reach each CORS construction. */
		@Override
		public int size() {
			return 1;
		}
	}

	/**
	 * Fails only the real CORS traversal during router construction, before any prefix is published.
	 * Other header lists and the value returned on successful reads remain the shipped defaults.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class PublicationFailureHeaders extends AbstractList<String> {
		/** Armed after system API construction; disabled before retrying the catalog. */
		private boolean fail;
		/** Proves failure came from the intended configuration traversal. */
		private int failures;

		/** Installs stock tracing configuration with one instrumented CORS list. */
		@Nonnull
		private HeaderOptions headers() {
			return new HeaderOptions(DEFAULT_HEADERS.forwardedUri(), DEFAULT_HEADERS.forwardedFor(),
				DEFAULT_HEADERS.label(), DEFAULT_HEADERS.clientId(), this);
		}

		/** Throws an internal error from the ordinary CORS header read when armed. */
		@Nonnull
		@Override
		public String get(int index) {
			final String value = DEFAULT_HEADERS.traceParent().get(index);
			if (this.fail) {
				this.failures++;
				throw new GenericEvitaInternalError("Deliberate REST publication failure");
			}
			return value;
		}

		/** Keeps exactly the default trace-parent list length. */
		@Override
		public int size() {
			return DEFAULT_HEADERS.traceParent().size();
		}
	}

	/**
	 * Observes existing Rest manager boundaries while retaining its real registration and refresh behavior.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class ObservedRestManager extends RestManager {
		/** Shared ordering and completion observations. */
		private final Observation observation;

		/** Subscribes the production observer and builds the production system API. */
		private ObservedRestManager(@Nonnull Evita evita, @Nonnull Observation observation) {
			super(evita, observation.headers(), new RestOptions("localhost:5555"));
			this.observation = observation;
		}

		/** Observes registration, which a refresh of an absent name delegates to, before the real one. */
		@Override
		public boolean registerCatalog(@Nonnull String name) {
			if (this.observation != null) {
				this.observation.beforeOperation(name);
			}
			return super.registerCatalog(name);
		}

		/** Observes before delegating to the real manager's complete build and publication. */
		@Override
		public boolean refreshCatalog(@Nonnull String name) {
			if (this.observation != null) {
				this.observation.beforeRefresh(name);
			}
			return super.refreshCatalog(name);
		}

		/** Emits real metrics and counts only emissions after the engine renamed the source. */
		@Override
		public void emitObservabilityEvents(@Nonnull String name) {
			super.emitObservabilityEvents(name);
			if (this.observation != null && this.observation.armed && name.equals("target") &&
				!this.observation.evita.getCatalogNames().contains("source")) {
				this.observation.emissions++;
			}
		}

		/** Observes both sides of real cleanup, including failed-build cleanup from the observer's finally. */
		@Override
		public void unregisterCatalog(@Nonnull String name) {
			if (this.observation != null) {
				this.observation.beforeRemove(name);
			}
			super.unregisterCatalog(name);
			if (this.observation != null) {
				this.observation.afterRemove(name);
			}
		}
	}

	/**
	 * Observes real GraphQL schema construction when AsyncDataFetcher reads its execution configuration.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class ObservedGraphQLOptions extends GraphQLOptions {
		/** Shared ordering and failure observations, armed only during the target build. */
		private final Observation observation;

		/** Retains ordinary API configuration with a real schema-construction observation. */
		private ObservedGraphQLOptions(@Nonnull Observation observation) {
			super("localhost:5555");
			this.observation = observation;
		}

		/** Observes before returning the configured execution mode consumed by real data-fetcher construction. */
		@Override
		public boolean isParallelize() {
			this.observation.duringBuild();
			return super.isParallelize();
		}
	}

	/**
	 * Observes existing GraphQL manager boundaries while retaining its real registration and refresh behavior.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class ObservedGraphQLManager extends GraphQLManager {
		/** Shared ordering and completion observations. */
		private final Observation observation;

		/** Subscribes the production observer and builds the production system API. */
		private ObservedGraphQLManager(@Nonnull Evita evita, @Nonnull Observation observation) {
			super(evita, observation.headers(), new ObservedGraphQLOptions(observation));
			this.observation = observation;
		}

		/** Observes registration, which a refresh of an absent name delegates to, before the real one. */
		@Override
		public boolean registerCatalog(@Nonnull String name) {
			if (this.observation != null) {
				this.observation.beforeOperation(name);
			}
			return super.registerCatalog(name);
		}

		/** Observes before delegating to the real manager's complete build and publication. */
		@Override
		public boolean refreshCatalog(@Nonnull String name) {
			if (this.observation != null) {
				this.observation.beforeRefresh(name);
			}
			return super.refreshCatalog(name);
		}

		/** Emits real metrics and counts only emissions after the engine renamed the source. */
		@Override
		public void emitObservabilityEvents(@Nonnull String name) {
			super.emitObservabilityEvents(name);
			if (this.observation != null && this.observation.armed && name.equals("target") &&
				!this.observation.evita.getCatalogNames().contains("source")) {
				this.observation.emissions++;
			}
		}

		/** Observes both sides of real cleanup, including failed-build cleanup from the observer's finally. */
		@Override
		public boolean unregisterCatalog(@Nonnull String name) {
			if (this.observation != null) {
				this.observation.beforeRemove(name);
			}
			final boolean removed = super.unregisterCatalog(name);
			if (this.observation != null) {
				this.observation.afterRemove(name);
			}
			return removed;
		}
	}
}
