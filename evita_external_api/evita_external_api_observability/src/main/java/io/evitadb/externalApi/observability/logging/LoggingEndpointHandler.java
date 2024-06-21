/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.externalApi.observability.logging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.linecorp.armeria.common.HttpRequest;
import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.evitadb.externalApi.exception.ExternalApiInvalidUsageException;
import io.evitadb.externalApi.http.AbstractHttpService;
import io.evitadb.externalApi.http.EndpointHandler;
import io.evitadb.externalApi.http.EndpointRequest;
import io.evitadb.externalApi.http.MimeTypes;
import io.evitadb.externalApi.observability.ObservabilityManager;
import io.evitadb.externalApi.observability.exception.ObservabilityInternalError;
import io.evitadb.externalApi.observability.exception.ObservabilityInvalidUsageException;
import io.evitadb.utils.Assert;
import io.undertow.server.HttpServerExchange;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Generic HTTP request handler for processing Observability API requests and responses.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public abstract class LoggingEndpointHandler<E extends EndpointRequest> extends AbstractHttpService<E> {
	protected static final LinkedHashSet<String> DEFAULT_SUPPORTED_CONTENT_TYPES = new LinkedHashSet<>(List.of(MimeTypes.APPLICATION_JSON));
	protected final ObservabilityManager manager;

	public LoggingEndpointHandler(ObservabilityManager manager) {
		this.manager = manager;
	}

	@Nonnull
	@Override
	protected E createEndpointExchange(
		@Nonnull HttpRequest httpRequest,
		@Nonnull String method,
		@Nullable String requestBodyMediaType,
		@Nullable String preferredResponseMediaType) {
		return (E) new LoggingEndpointExchange(
			httpRequest,
			method,
			requestBodyMediaType,
			preferredResponseMediaType
		);
	}

	@Nonnull
	@Override
	protected <T extends ExternalApiInternalError> T createInternalError(@Nonnull String message) {
		//noinspection unchecked
		return (T) new ObservabilityInternalError(message);
	}

	@Nonnull
	@Override
	protected <T extends ExternalApiInternalError> T createInternalError(@Nonnull String message, @Nonnull Throwable cause) {
		//noinspection unchecked
		return (T) new ObservabilityInternalError(message, cause);
	}

	@Nonnull
	@Override
	protected <T extends ExternalApiInvalidUsageException> T createInvalidUsageException(@Nonnull String message) {
		//noinspection unchecked
		return (T) new ObservabilityInvalidUsageException(message);
	}

	/**
	 * Tries to parse input request body JSON into data class.
	 */
	@Nonnull
	protected <T> T parseRequestBody(@Nonnull E exchange, @Nonnull Class<T> dataClass) {
		final String content = readRawRequestBody(exchange);
		Assert.isTrue(
			!content.trim().isEmpty(),
			() -> createInvalidUsageException("Request's body contains no data.")
		);

		try {
			return manager.getObjectMapper().readValue(content, dataClass);
		} catch (JsonProcessingException e) {
			throw createInternalError("Could not parse request body: ", e);
		}
	}

	/*@Override
	protected void writeResult(@Nonnull LoggingEndpointExchange exchange, @Nonnull OutputStream outputStream, @Nonnull Object result) {
		try {
			manager.getObjectMapper().writeValue(outputStream, result);
		} catch (IOException e) {
			throw new ObservabilityInternalError(
				"Could not serialize Java object response to JSON: " + e.getMessage(),
				"Could not provide response data.", e
			);
		}
	}*/
}
