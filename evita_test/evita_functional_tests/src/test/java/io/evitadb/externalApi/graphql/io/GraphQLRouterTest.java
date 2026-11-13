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
			new AssemblyObservationHeaders(() -> {
				final AggregatedHttpResponse data = assertDoesNotThrow(() ->
					response(reference.get(), HttpMethod.GET, "/catalog", "firstData"));
				final AggregatedHttpResponse schema = assertDoesNotThrow(() ->
					response(reference.get(), HttpMethod.GET, "/catalog/schema", "firstSchema"));
				assertEquals(HttpStatus.NOT_FOUND, data.status(), "DATA was published before SCHEMA assembly");
				assertEquals(HttpStatus.NOT_FOUND, schema.status());
				observations.incrementAndGet();
			}), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of()
		);
		final GraphQLRouter router = new GraphQLRouter(new ObjectMapper(), this.evita, headers);
		reference.set(router);
		router.registerCatalogApi("catalog", api("firstData"), api("firstSchema"));
		assertEquals(1, observations.get(), "The SCHEMA construction seam must have been observed");
		assertDoesNotThrow(() -> assertApi(router, "/catalog", "firstData"));
		assertDoesNotThrow(() -> assertApi(router, "/catalog/schema", "firstSchema"));
	}

	/**
	 * Real single-value header configuration observed on its second traversal (the SCHEMA CORS constructor).
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

		/** Observes the second real configuration read, then supplies its ordinary header value. */
		@Nonnull
		@Override
		public String get(int index) {
			if (index != 0) {
				throw new IndexOutOfBoundsException(index);
			}
			if (++this.traversals == 2) {
				this.observation.run();
			}
			return "x-forwarded-uri";
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
	private static void assertApi(@Nonnull GraphQLRouter router, @Nonnull String path, @Nonnull String field)
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
		@Nonnull GraphQLRouter router, @Nonnull HttpMethod method, @Nonnull String path, @Nonnull String field
	) throws Exception {
		final RequestHeadersBuilder headers = RequestHeaders.builder(method, path)
			.scheme("http").authority("localhost");
		final HttpRequest request;
		if (method == HttpMethod.POST) {
			headers.contentType(MediaType.JSON).add(HttpHeaderNames.ACCEPT, "application/json");
			request = HttpRequest.of(headers.build(), HttpData.ofUtf8("{\"query\":\"{ " + field + " }\"}"));
		} else {
			if (method == HttpMethod.OPTIONS) {
				headers.add(HttpHeaderNames.ORIGIN, "http://localhost")
					.add(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST")
					.add(HttpHeaderNames.ACCESS_CONTROL_REQUEST_HEADERS, "content-type");
			}
			request = HttpRequest.of(headers.build());
		}
		return router.serve(ServiceRequestContext.builder(request).build(), request)
			.aggregate().get(30, TimeUnit.SECONDS);
	}
}
