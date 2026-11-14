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

package io.evitadb.externalApi.rest.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.*;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.core.Evita;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.rest.api.Rest;
import io.evitadb.externalApi.rest.api.resolver.endpoint.OpenApiSpecificationHandler;
import io.evitadb.externalApi.rest.api.system.resolver.endpoint.SystemRestHandlingContext;
import io.evitadb.externalApi.rest.configuration.RestOptions;
import io.evitadb.externalApi.utils.UriPath;
import io.evitadb.test.EvitaTestSupport;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises catalog refresh with real REST models, handlers and request execution.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(REST)
@Tag(EXTERNAL_API)
@Tag(MANAGEMENT)
@DisplayName("REST catalog routing publication")
class RestRouterTest implements EvitaTestSupport {
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final HeaderOptions headers = new HeaderOptions();
	private TestPaths paths;
	private Evita evita;

	/** Starts the same small engine fixture used by API schema tests. */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("RestRouterTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
	}

	/** Releases the engine and its isolated directories. */
	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/** A failed endpoint build retains both old catalogs; a successful build replaces only its own catalog. */
	@Test
	@DisplayName("Failed refresh keeps the old router; successful refresh replaces only B")
	void shouldKeepOldCatalogOnFailedRefreshAndPublishSuccessfulRefresh() throws Exception {
		final RestRouter router = new RestRouter(this.objectMapper, this.headers, new RestOptions("localhost:5555"));
		final Rest apiA = api("unchangedA", "/spec");
		final Rest apiB = api("oldB", "/spec");
		router.registerCatalogApi("A", apiA);
		router.registerCatalogApi("B", apiB);
		final String responseA = response(router, "/A/spec");
		final String responseB = response(router, "/B/spec");
		assertTrue(responseA.contains("unchangedA"));
		assertTrue(responseB.contains("oldB"));

		final Rest invalidApi = api("invalidB", "/{first}", "/{second}");
		final GenericEvitaInternalError error = assertThrows(GenericEvitaInternalError.class,
			() -> router.refreshCatalogApi("B", invalidApi));
		assertTrue(error.getMessage().contains("Equivalent route already registered"));
		assertEquals(responseB, response(router, "/B/spec"), "Failed assembly must leave B's old router live");
		assertEquals(responseA, response(router, "/A/spec"));

		router.refreshCatalogApi("B", api("newB", "/spec"));
		final String replacement = response(router, "/B/spec");
		assertTrue(replacement.contains("newB"));
		assertFalse(replacement.contains("oldB"));
		assertEquals(responseA, response(router, "/A/spec"));
	}

	/** A bounded observer checks the replaced catalog and unchanged neighbour across successful refreshes. */
	@Test
	@DisplayName("successful refresh never removes the catalog prefix")
	void shouldKeepCatalogVisibleAcrossSuccessfulRefreshes() throws Exception {
		final RestRouter router = new RestRouter(this.objectMapper, this.headers, new RestOptions("localhost:5555"));
		router.registerCatalogApi("A", api("unchangedA", "/spec"));
		router.registerCatalogApi("B", api("generation0", "/spec"));
		final Rest[] replacements = {api("generation0", "/spec"), api("generation1", "/spec")};
		final java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
		final java.util.concurrent.atomic.AtomicInteger published = new java.util.concurrent.atomic.AtomicInteger();
		final java.util.concurrent.atomic.AtomicInteger spanned = new java.util.concurrent.atomic.AtomicInteger();
		final java.util.concurrent.CountDownLatch observed = new java.util.concurrent.CountDownLatch(2);
		final java.util.concurrent.ExecutorService readers = java.util.concurrent.Executors.newFixedThreadPool(2,
			Thread.ofPlatform().daemon().factory());
		final List<java.util.concurrent.Future<?>> results = new ArrayList<>(2);
		try {
			for (int reader = 0; reader < 2; reader++) {
				results.add(readers.submit(() -> {
					try {
						while (!finished.get()) {
							final int before = published.get();
							assertTrue(response(router, "/B/spec").contains("generation"));
							assertTrue(response(router, "/A/spec").contains("unchangedA"));
							if (published.get() > before) {
								spanned.incrementAndGet();
							}
							observed.countDown();
						}
					} catch (Exception exception) {
						throw new AssertionError(exception);
					}
				}));
			}
			assertTrue(observed.await(30, TimeUnit.SECONDS), "Observers did not read the initial router");
			for (int generation = 0; generation < 1000; generation++) {
				router.refreshCatalogApi("B", replacements[generation & 1]);
				published.incrementAndGet();
			}
		} finally {
			finished.set(true);
			readers.shutdown();
			assertTrue(readers.awaitTermination(30, TimeUnit.SECONDS), "Observers did not stop");
		}
		for (final java.util.concurrent.Future<?> result : results) {
			result.get(30, TimeUnit.SECONDS);
		}
		assertTrue(spanned.get() > 0, "No completed observation spanned successful refresh publications");
	}

	/** Builds a minimal real API around the production specification handler with a distinguishable title. */
	@Nonnull
	private Rest api(@Nonnull String title, @Nonnull String... endpointPaths) {
		final OpenAPI openApi = new OpenAPI().info(new Info().title(title).version("1"));
		final OpenApiSpecificationHandler<SystemRestHandlingContext> handler = new OpenApiSpecificationHandler<>(
			new SystemRestHandlingContext(this.objectMapper, this.evita, this.headers, openApi, Map.of(),
				new Operation().operationId("spec"), false)
		);
		final List<Rest.Endpoint> endpoints = new ArrayList<>(endpointPaths.length);
		for (final String path : endpointPaths) {
			endpoints.add(new Rest.Endpoint(UriPath.of("/", path.substring(1)), HttpMethod.GET, handler));
		}
		return new Rest(openApi, endpoints);
	}

	/** Executes the real endpoint and checks success before comparing its API identity in the body. */
	@Nonnull
	private static String response(@Nonnull RestRouter router, @Nonnull String path) throws Exception {
		final HttpRequest request = HttpRequest.of(RequestHeaders.builder(HttpMethod.GET, path)
			.scheme("http").authority("localhost").add(HttpHeaderNames.ACCEPT, "application/json").build());
		final AggregatedHttpResponse response = router.serve(ServiceRequestContext.builder(request).build(), request)
			.aggregate().get(30, TimeUnit.SECONDS);
		assertEquals(HttpStatus.OK, response.status(), response.contentUtf8());
		return response.contentUtf8();
	}
}
