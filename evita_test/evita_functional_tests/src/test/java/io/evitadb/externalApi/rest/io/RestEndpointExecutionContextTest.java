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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.core.Evita;
import io.evitadb.core.session.SuspendOperation;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.metric.event.request.ExecutedEvent;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static io.evitadb.test.TestTags.REST;
import static io.evitadb.test.TestTags.SESSION;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the session accessor of the real REST context, including after the engine drains an idle session
 * during catalog replacement.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(REST)
@Tag(SESSION)
@DisplayName("REST execution context session access")
class RestEndpointExecutionContextTest implements EvitaTestSupport {
	/** Catalog whose sessions the engine drains. */
	private static final String CATALOG_NAME = "terminatedSession";

	/** Isolated storage of the engine. */
	private TestPaths paths;
	/** Real engine owning the catalog and its session registry. */
	private Evita evita;
	/** Request the context serves. */
	private HttpRequest request;
	/** Armeria context of the request. */
	private ServiceRequestContext requestContext;
	/** Context under test; no session is provided to it yet. */
	private RestEndpointExecutionContext context;

	/** Starts a real engine with one catalog and creates a context for a request against it. */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("RestEndpointExecutionContextTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(CATALOG_NAME);
		this.request = HttpRequest.of(HttpMethod.POST, "/" + CATALOG_NAME + "/Product/list");
		this.requestContext = ServiceRequestContext.of(this.request);
		this.context = new RestEndpointExecutionContext(
			this.request, this.evita, this.requestContext,
			new ExecutedEvent(RestInstanceType.CATALOG, ExecutedEvent.OperationType.QUERY,
				CATALOG_NAME, "Product", "POST", "list")
		);
	}

	/** Closes the context (and the session it holds), then the engine, then removes the storage. */
	@AfterEach
	void tearDown() {
		try {
			if (this.context != null) {
				this.context.close();
			}
		} finally {
			try {
				if (this.evita != null) {
					this.evita.close();
				}
			} finally {
				cleanupTestPaths(this.paths);
			}
		}
	}

	@Nested
	@DisplayName("Session never provided")
	class SessionNotProvided {

		@Test
		@DisplayName("Report the missing session as an internal error")
		void shouldThrowInternalErrorWhenSessionWasNeverProvided() {
			assertThrows(
				RestInternalError.class, RestEndpointExecutionContextTest.this.context::session,
				"A missing session is still an internal error"
			);
		}

	}

	@Nested
	@DisplayName("Session closed by the engine")
	class SessionClosedByEngine {
		/** Idle session provided to the context; the engine drain closes it. */
		private EvitaSessionContract session;

		/** Opens a real session on the catalog and hands it to the context, as the endpoint handler does. */
		@BeforeEach
		void provideSession() {
			this.session = RestEndpointExecutionContextTest.this.evita.createReadOnlySession(CATALOG_NAME);
			RestEndpointExecutionContextTest.this.context.provideSession(this.session);
		}

		/** Lifts the suspension so the engine can close; a no-op when the test failed before draining. */
		@AfterEach
		void resumeCatalogOperations() {
			RestEndpointExecutionContextTest.this.evita.getCatalogSessionRegistry(CATALOG_NAME)
				.orElseThrow()
				.resumeOperations();
		}

		@Test
		@DisplayName("Return the session while active and report it as terminated once drained")
		void shouldThrowInstanceTerminatedWhenSessionWasClosedByEngine() {
			final RestEndpointExecutionContext context = RestEndpointExecutionContextTest.this.context;
			assertSame(this.session, context.session());

			drainCatalogSessions();

			assertThrows(InstanceTerminatedException.class, context::session);
		}

		@Test
		@DisplayName("Render the terminated session as a coded HTTP 400 response")
		void shouldRenderBadRequestWhenSessionWasClosedByEngine() throws Exception {
			final RestEndpointExecutionContext context = RestEndpointExecutionContextTest.this.context;
			drainCatalogSessions();
			final InstanceTerminatedException exception = assertThrows(
				InstanceTerminatedException.class, context::session
			);
			final ObjectMapper mapper = new ObjectMapper();
			final RestExceptionHandler handler = new RestExceptionHandler(mapper, (ctx, req) -> {
				context.session();
				return HttpResponse.of(HttpStatus.OK);
			});

			final AggregatedHttpResponse response = handler.serve(
				RestEndpointExecutionContextTest.this.requestContext, RestEndpointExecutionContextTest.this.request
			).aggregate().get(10, TimeUnit.SECONDS);

			assertEquals(HttpStatus.BAD_REQUEST, response.status());
			final JsonNode json = mapper.readTree(response.contentUtf8());
			assertEquals(exception.getErrorCode(), json.path("errorCode").asText());
			assertEquals(exception.getPublicMessage(), json.path("message").asText());
		}

		/** Engine suspension closes the idle session synchronously, before the handler next asks for it. */
		private void drainCatalogSessions() {
			assertTrue(
				RestEndpointExecutionContextTest.this.evita
					.closeAllSessionsAndSuspend(CATALOG_NAME, SuspendOperation.REJECT)
					.isPresent()
			);
			assertFalse(this.session.isActive(), "The real engine drain must close the idle session");
		}

	}
}
