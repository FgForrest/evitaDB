/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.externalApi.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpService;
import io.evitadb.api.exception.CatalogBeingUpgradedException;
import io.evitadb.api.exception.CatalogMissingException;
import io.evitadb.api.exception.CatalogRequiresUpgradeException;
import io.evitadb.api.exception.CatalogNotFoundException;
import io.evitadb.exception.EvitaError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Verifies that {@link JsonApiExceptionHandler#renderError(EvitaError, HttpRequest)} maps the catalog
 * lifecycle exceptions to specific HTTP status codes rather than collapsing them into the generic 400 Bad Request
 * behaviour inherited from `EvitaInvalidUsageException`.
 *
 * The handler is shared by both the REST and GraphQL over-HTTP entry points (see {@code RestExceptionHandler} and
 * {@code GraphQLExceptionHandler}); this test pins the contract at the common base class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("JsonApiExceptionHandler - catalog lifecycle exception mapping")
class JsonApiExceptionHandlerTest {

	private JsonApiExceptionHandler handler;

	@BeforeEach
	void init() {
		this.handler = new JsonApiExceptionHandler(new ObjectMapper(), mock(HttpService.class)) {
			@Nonnull
			@Override
			protected String getExternalApiCode() {
				return "test";
			}
		};
	}

	@Test
	@DisplayName("maps CatalogMissingException to HTTP 404 Not Found")
	void shouldMapCatalogMissingExceptionToHttp404() throws Exception {
		final int statusCode = renderStatusFor(new CatalogMissingException("testCatalog"));
		assertEquals(HttpStatus.NOT_FOUND.code(), statusCode);
	}

	@Test
	@DisplayName("maps CatalogBeingUpgradedException to HTTP 409 Conflict (transient)")
	void shouldMapCatalogBeingUpgradedExceptionToHttp409() throws Exception {
		final int statusCode = renderStatusFor(new CatalogBeingUpgradedException("testCatalog"));
		assertEquals(HttpStatus.CONFLICT.code(), statusCode);
	}

	@Test
	@DisplayName("maps CatalogRequiresUpgradeException to HTTP 409 Conflict")
	void shouldMapCatalogRequiresUpgradeExceptionToHttp409() throws Exception {
		final int statusCode = renderStatusFor(new CatalogRequiresUpgradeException("testCatalog"));
		assertEquals(HttpStatus.CONFLICT.code(), statusCode);
	}

	@Test
	@DisplayName("regression guard: an unrelated EvitaInvalidUsageException still maps to HTTP 400")
	void shouldKeepDefaultBadRequestMappingForUnrelatedUsageException() throws Exception {
		final int statusCode = renderStatusFor(new CatalogNotFoundException("testCatalog"));
		assertEquals(HttpStatus.BAD_REQUEST.code(), statusCode);
	}

	private int renderStatusFor(@Nonnull EvitaError error) throws Exception {
		final Method renderError = JsonApiExceptionHandler.class.getDeclaredMethod(
			"renderError", EvitaError.class, HttpRequest.class
		);
		renderError.setAccessible(true);
		final HttpResponse response = (HttpResponse) renderError.invoke(this.handler, error, mock(HttpRequest.class));
		final AggregatedHttpResponse aggregated = response.aggregate().join();
		return aggregated.status().code();
	}
}
