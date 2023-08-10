/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api;

import io.evitadb.core.Evita;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The test verifies behaviour of {@link ClientContext} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("Evita client context API")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
class ClientContextTest {

	@Test
	void shouldWrapEvitaCallWithinClientContext(Evita evita) {
		// given
		String clientId = "client-id";
		String requestId = "request-id";

		// when
		ClientContext.executeWithClientAndRequestId(clientId, requestId, () -> {
			evita.defineCatalog(TEST_CATALOG);
			evita.queryCatalog(TEST_CATALOG, session -> {
				// then
				assertEquals(clientId, ClientContext.getClientId().orElseThrow());
				assertEquals(requestId, ClientContext.getRequestId().orElseThrow());
			});
		});

		ClientContext.executeWithClientAndRequestId(clientId, requestId, () -> {
			evita.defineCatalog(TEST_CATALOG);
			evita.queryCatalog(TEST_CATALOG, session -> {
				// then
				assertEquals(clientId, ClientContext.getClientId().orElseThrow());
				assertEquals(requestId, ClientContext.getRequestId().orElseThrow());
			});
			return null;
		});
	}

	@Test
	void shouldAllowChangingRequestIdsWithinSameSession(Evita evita) {
		// given
		String clientId = "client-id";

		// when
		ClientContext.executeWithClientId(clientId, () -> {
			evita.defineCatalog(TEST_CATALOG);
			evita.queryCatalog(TEST_CATALOG, session -> {
				// then
				assertEquals(clientId, ClientContext.getClientId().orElseThrow());
				assertTrue(ClientContext.getRequestId().isEmpty());

				ClientContext.executeWithRequestId("#1", () -> {
					assertEquals(clientId, ClientContext.getClientId().orElseThrow());
					assertEquals("#1", ClientContext.getRequestId().orElseThrow());
				});

				ClientContext.executeWithRequestId("#2", () -> {
					assertEquals(clientId, ClientContext.getClientId().orElseThrow());
					assertEquals("#2", ClientContext.getRequestId().orElseThrow());
				});

			});
		});

		ClientContext.executeWithClientId(clientId, () -> {
			evita.defineCatalog(TEST_CATALOG);
			return evita.queryCatalog(TEST_CATALOG, session -> {
				// then
				assertEquals(clientId, ClientContext.getClientId().orElseThrow());
				assertTrue(ClientContext.getRequestId().isEmpty());

				ClientContext.executeWithRequestId("#1", () -> {
					assertEquals(clientId, ClientContext.getClientId().orElseThrow());
					assertEquals("#1", ClientContext.getRequestId().orElseThrow());
				});

				ClientContext.executeWithRequestId("#2", () -> {
					assertEquals(clientId, ClientContext.getClientId().orElseThrow());
					assertEquals("#2", ClientContext.getRequestId().orElseThrow());
				});

				return null;
			});
		});
	}

	@Test
	void shouldAllowHavingSameRequestIdAmongDifferentSessions(Evita evita) {
		// given
		String clientId = "client-id";
		String requestId = "request-id";

		// when
		ClientContext.executeWithClientAndRequestId(clientId, requestId, () -> {
			evita.defineCatalog(TEST_CATALOG);
			evita.queryCatalog(TEST_CATALOG, session -> {
				// then
				assertEquals(clientId, ClientContext.getClientId().orElseThrow());
				assertEquals(requestId, ClientContext.getRequestId().orElseThrow());
			});
			evita.queryCatalog(TEST_CATALOG, session -> {
				// then
				assertEquals(clientId, ClientContext.getClientId().orElseThrow());
				assertEquals(requestId, ClientContext.getRequestId().orElseThrow());
			});
		});

		ClientContext.executeWithClientAndRequestId(clientId, requestId, () -> {
			evita.defineCatalog(TEST_CATALOG);
			evita.queryCatalog(TEST_CATALOG, session -> {
				// then
				assertEquals(clientId, ClientContext.getClientId().orElseThrow());
				assertEquals(requestId, ClientContext.getRequestId().orElseThrow());
			});
			evita.queryCatalog(TEST_CATALOG, session -> {
				// then
				assertEquals(clientId, ClientContext.getClientId().orElseThrow());
				assertEquals(requestId, ClientContext.getRequestId().orElseThrow());
			});
			return null;
		});
	}

	@Test
	void shouldAllowNestedRequestResolution(Evita evita) {
		// given
		String clientId = "client-id";

		// when
		ClientContext.executeWithClientAndRequestId(clientId, "A", () -> {
			evita.defineCatalog(TEST_CATALOG);
			evita.queryCatalog(TEST_CATALOG, session -> {
				// then
				assertEquals(clientId, ClientContext.getClientId().orElseThrow());
				assertEquals("A", ClientContext.getRequestId().orElseThrow());

				ClientContext.executeWithRequestId("#1", () -> {
					assertEquals(clientId, ClientContext.getClientId().orElseThrow());
					assertEquals("#1", ClientContext.getRequestId().orElseThrow());
				});

				ClientContext.executeWithRequestId("#2", () -> {
					assertEquals(clientId, ClientContext.getClientId().orElseThrow());
					assertEquals("#2", ClientContext.getRequestId().orElseThrow());
				});

				assertEquals("A", ClientContext.getRequestId().orElseThrow());
			});
		});

		ClientContext.executeWithClientAndRequestId(clientId, "A", () -> {
			evita.defineCatalog(TEST_CATALOG);
			evita.queryCatalog(TEST_CATALOG, session -> {
				// then
				assertEquals(clientId, ClientContext.getClientId().orElseThrow());
				assertEquals("A", ClientContext.getRequestId().orElseThrow());

				ClientContext.executeWithRequestId("#1", () -> {
					assertEquals(clientId, ClientContext.getClientId().orElseThrow());
					assertEquals("#1", ClientContext.getRequestId().orElseThrow());
				});

				ClientContext.executeWithRequestId("#2", () -> {
					assertEquals(clientId, ClientContext.getClientId().orElseThrow());
					assertEquals("#2", ClientContext.getRequestId().orElseThrow());
				});

				assertEquals("A", ClientContext.getRequestId().orElseThrow());
			});
			return null;
		});
	}

}