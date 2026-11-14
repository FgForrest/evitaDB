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

package io.evitadb.externalApi.graphql.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.*;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import graphql.GraphQL;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.graphql.exception.GraphQLInternalError;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises complete catalog publication with real GraphQL schemas and HTTP handlers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(GRAPHQL)
@Tag(EXTERNAL_API)
@Tag(MANAGEMENT)
@DisplayName("GraphQL catalog routing publication")
class GraphQLRouterTest implements EvitaTestSupport {
	/** Stock values shared with every ordinary HTTP tracing context configuration. */
	private static final HeaderOptions DEFAULT_HEADERS = new HeaderOptions();
	private TestPaths paths;
	private Evita evita;

	/** Starts the same small engine fixture used by API schema tests. */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("GraphQLRouterTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
	}

	/** Releases the engine and its isolated directories. */
	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/** Both APIs and their methods are live after registration; duplicate registration changes neither API. */
	@Test
	@DisplayName("Both catalog APIs are complete and survive duplicate registration")
	void shouldPublishBothApisAndKeepThemOnDuplicateRegistration() throws Exception {
		final GraphQLRouter router = new GraphQLRouter(new ObjectMapper(), this.evita, new HeaderOptions());
		assertEquals(HttpStatus.NOT_FOUND, response(router, HttpMethod.GET, "/catalog", "firstData").status());
		assertEquals(HttpStatus.NOT_FOUND, response(router, HttpMethod.GET, "/catalog/schema", "firstSchema").status());
		router.registerCatalogApi("catalog", api("firstData"), api("firstSchema"));
		assertApi(router, "/catalog", "firstData");
		assertApi(router, "/catalog/schema", "firstSchema");

		assertThrows(GraphQLInternalError.class,
			() -> router.registerCatalogApi("catalog", api("secondData"), api("secondSchema")));
		assertApi(router, "/catalog", "firstData");
		assertApi(router, "/catalog/schema", "firstSchema");
	}

	/** CORS reads real configuration lists while assembling SCHEMA, before the complete router is published. */
	@Test
	@DisplayName("DATA cannot be visible while SCHEMA is still being assembled")
	void shouldPublishOnlyAfterBothApisAreBuilt() {
		final java.util.concurrent.atomic.AtomicReference<GraphQLRouter> reference =
			new java.util.concurrent.atomic.AtomicReference<>();
		final java.util.concurrent.atomic.AtomicInteger observations = new java.util.concurrent.atomic.AtomicInteger();
		final HeaderOptions headers = new HeaderOptions(
			DEFAULT_HEADERS.forwardedUri(), DEFAULT_HEADERS.forwardedFor(),
			DEFAULT_HEADERS.label(), DEFAULT_HEADERS.clientId(),
			new AssemblyObservationHeaders(() -> {
				final AggregatedHttpResponse data = assertDoesNotThrow(() ->
					response(reference.get(), HttpMethod.GET, "/catalog", "firstData"));
				final AggregatedHttpResponse schema = assertDoesNotThrow(() ->
					response(reference.get(), HttpMethod.GET, "/catalog/schema", "firstSchema"));
				assertEquals(HttpStatus.NOT_FOUND, data.status(), "DATA was published before SCHEMA assembly");
				assertEquals(HttpStatus.NOT_FOUND, schema.status());
				observations.incrementAndGet();
			})
		);
		final GraphQLRouter router = new GraphQLRouter(new ObjectMapper(), this.evita, headers);
		reference.set(router);
		router.registerCatalogApi("catalog", api("firstData"), api("firstSchema"));
		assertEquals(1, observations.get(), "The SCHEMA construction seam must have been observed");
		assertDoesNotThrow(() -> assertApi(router, "/catalog", "firstData"));
		assertDoesNotThrow(() -> assertApi(router, "/catalog/schema", "firstSchema"));
	}

	/** Every observable step of a two-reference refresh keeps DATA and SCHEMA routes available. */
	@Test
	@DisplayName("Both APIs stay reachable before, between and after reference swaps")
	void shouldKeepBothApisReachableDuringRefresh() throws Exception {
		final GraphQLRouter router = new GraphQLRouter(new ObjectMapper(), this.evita, new HeaderOptions());
		router.registerCatalogApi("catalog", api("oldData"), api("oldSchema"));
		final GraphQL newData = api("newData");
		final GraphQL newSchema = api("newSchema");
		assertApi(router, "/catalog", "oldData");
		assertApi(router, "/catalog/schema", "oldSchema");
		router.refreshCatalogApi("catalog", GraphQLInstanceType.DATA, newData);
		assertApi(router, "/catalog", "newData");
		assertApi(router, "/catalog/schema", "oldSchema");
		router.refreshCatalogApi("catalog", GraphQLInstanceType.SCHEMA, newSchema);
		assertApi(router, "/catalog", "newData");
		assertApi(router, "/catalog/schema", "newSchema");
	}

	/**
	 * A request is bound to its endpoint handler when it is routed, so one whose body arrives only after the refresh
	 * shows whether that handler reads the refreshed reference — which is also what keeps an established WebSocket on
	 * the current schema. A refresh that republished the catalog's handlers would answer it from the old instance.
	 */
	@Test
	@DisplayName("Requests routed before a refresh are answered by the refreshed GraphQL instances")
	void shouldAnswerRequestsRoutedBeforeRefreshFromRefreshedInstances() throws Exception {
		final GraphQLRouter router = new GraphQLRouter(new ObjectMapper(), this.evita, new HeaderOptions());
		router.registerCatalogApi("catalog", api("oldData"), api("oldSchema"));
		final HttpRequestWriter dataRequest = HttpRequest.streaming(queryHeaders("/catalog"));
		final HttpRequestWriter schemaRequest = HttpRequest.streaming(queryHeaders("/catalog/schema"));
		final CompletableFuture<AggregatedHttpResponse> data = serve(router, dataRequest);
		final CompletableFuture<AggregatedHttpResponse> schema = serve(router, schemaRequest);
		assertFalse(data.isDone(), "The DATA request must wait for its body");
		assertFalse(schema.isDone(), "The SCHEMA request must wait for its body");

		router.refreshCatalogApi("catalog", GraphQLInstanceType.DATA, api("newData"));
		router.refreshCatalogApi("catalog", GraphQLInstanceType.SCHEMA, api("newSchema"));
		dataRequest.write(HttpData.ofUtf8(query("newData")));
		dataRequest.close();
		schemaRequest.write(HttpData.ofUtf8(query("newSchema")));
		schemaRequest.close();

		assertEquals("{\"data\":{\"newData\":\"newData\"}}", data.get(30, TimeUnit.SECONDS).contentUtf8());
		assertEquals("{\"data\":{\"newSchema\":\"newSchema\"}}", schema.get(30, TimeUnit.SECONDS).contentUtf8());
	}

	/**
	 * Real single-value header configuration observed at the selected SCHEMA CORS constructor.
	 * No production hook or mocked service is needed: both APIs consume the same configured header names.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class AssemblyObservationHeaders extends java.util.AbstractList<String> {
		private final Runnable observation;
		private int traversals;

		/** Retains the assertion performed at SCHEMA construction. */
		private AssemblyObservationHeaders(@Nonnull Runnable observation) {
			this.observation = observation;
		}

		/** Observes the selected real SCHEMA configuration read, then supplies its ordinary header value. */
		@Nonnull
		@Override
		public String get(int index) {
			if (index != 0) {
				throw new IndexOutOfBoundsException(index);
			}
			if (++this.traversals == 2) {
				this.observation.run();
			}
			return DEFAULT_HEADERS.traceParent().get(index);
		}

		/** Exposes exactly one configured header. */
		@Override
		public int size() {
			return 1;
		}
	}

	/** Builds the smallest executable GraphQL schema with a response that identifies its API. */
	@Nonnull
	private static GraphQL api(@Nonnull String field) {
		return GraphQL.newGraphQL(new SchemaGenerator().makeExecutableSchema(
			new SchemaParser().parse("type Query { " + field + ": String }"),
			RuntimeWiring.newRuntimeWiring().type("Query", type -> type.dataFetcher(field, env -> field)).build()
		)).build();
	}

	/** Asserts all three methods route, with distinct schema and query content for each API. */
	private static void assertApi(@Nonnull HttpService router, @Nonnull String path, @Nonnull String field)
		throws Exception {
		for (final HttpMethod method : new HttpMethod[]{HttpMethod.GET, HttpMethod.POST, HttpMethod.OPTIONS}) {
			final AggregatedHttpResponse response = response(router, method, path, field);
			assertTrue(response.status().isSuccess(), method + " " + path + ": " + response.contentUtf8());
			if (method == HttpMethod.GET) {
				assertTrue(response.contentUtf8().contains(field + ": String"), response.contentUtf8());
			} else if (method == HttpMethod.POST) {
				assertEquals("{\"data\":{\"" + field + "\":\"" + field + "\"}}", response.contentUtf8());
			}
		}
	}

	/** Sends ordinary HTTP requests with the headers required by the real GraphQL and CORS handlers. */
	@Nonnull
	private static AggregatedHttpResponse response(
		@Nonnull HttpService router, @Nonnull HttpMethod method, @Nonnull String path, @Nonnull String field
	) throws Exception {
		final HttpRequest request;
		if (method == HttpMethod.POST) {
			request = HttpRequest.of(queryHeaders(path), HttpData.ofUtf8(query(field)));
		} else {
			final RequestHeadersBuilder headers = RequestHeaders.builder(method, path)
				.scheme("http").authority("localhost");
			if (method == HttpMethod.OPTIONS) {
				headers.add(HttpHeaderNames.ORIGIN, "http://localhost")
					.add(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST")
					.add(HttpHeaderNames.ACCESS_CONTROL_REQUEST_HEADERS, "content-type");
			}
			request = HttpRequest.of(headers.build());
		}
		return serve(router, request).get(30, TimeUnit.SECONDS);
	}

	/** Headers of a JSON GraphQL query, which the POST handler accepts. */
	@Nonnull
	private static RequestHeaders queryHeaders(@Nonnull String path) {
		return RequestHeaders.builder(HttpMethod.POST, path)
			.scheme("http").authority("localhost")
			.contentType(MediaType.JSON).add(HttpHeaderNames.ACCEPT, "application/json")
			.build();
	}

	/** JSON body of a query selecting the single field that identifies an API. */
	@Nonnull
	private static String query(@Nonnull String field) {
		return "{\"query\":\"{ " + field + " }\"}";
	}

	/** Routes the request at once; the response completes when the handler has the whole request body. */
	@Nonnull
	private static CompletableFuture<AggregatedHttpResponse> serve(
		@Nonnull HttpService router, @Nonnull HttpRequest request
	) throws Exception {
		return router.serve(ServiceRequestContext.builder(request).build(), request).aggregate();
	}
}
