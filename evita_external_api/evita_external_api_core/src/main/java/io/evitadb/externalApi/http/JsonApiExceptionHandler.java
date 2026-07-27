/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.HttpService;
import io.evitadb.api.exception.CatalogBeingUpgradedException;
import io.evitadb.api.exception.CatalogMissingException;
import io.evitadb.api.exception.CatalogRequiresUpgradeException;
import io.evitadb.exception.EvitaError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.exception.HttpExchangeException;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;

/**
 * Exception handler for all APIs that use JSON as a data format.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@Slf4j
public abstract class JsonApiExceptionHandler extends ExternalApiExceptionHandler {

	@Nonnull
	private final ObjectMapper objectMapper;

	protected JsonApiExceptionHandler(@Nonnull ObjectMapper objectMapper, @Nonnull HttpService next) {
		super(next);
		this.objectMapper = objectMapper;
	}

	@Override
	protected HttpResponse renderError(@Nonnull EvitaError evitaError, @Nonnull HttpRequest httpRequest) {
		if (evitaError instanceof final HttpExchangeException httpExchangeException) {
			return setResponse(httpExchangeException.getStatusCode(), httpExchangeException);
		} else if (evitaError instanceof CatalogMissingException) {
			// catalog's on-disk folder is gone — treat as 404 Not Found so clients surface the resource-not-available
			// semantic rather than a generic bad-request.
			return setResponse(HttpStatus.NOT_FOUND.code(), evitaError);
		} else if (evitaError instanceof CatalogBeingUpgradedException) {
			// transient conflict: the upgrade is in flight and will complete — retryable.
			return setResponse(HttpStatus.CONFLICT.code(), evitaError);
		} else if (evitaError instanceof CatalogRequiresUpgradeException) {
			// catalog exists but its current state (OUT_OF_DATE) conflicts with any access attempt until the upgrade
			// mutation is executed.
			return setResponse(HttpStatus.CONFLICT.code(), evitaError);
		} else if (evitaError instanceof EvitaInvalidUsageException) {
			return setResponse(HttpStatus.BAD_REQUEST.code(), evitaError);
		} else {
			return setResponse(HttpStatus.INTERNAL_SERVER_ERROR.code(), evitaError);
		}
	}

	/**
	 * Common way to set basic error response.
	 */
	private HttpResponse setResponse(int statusCode, @Nonnull EvitaError evitaError) {
		return buildResponse(
			statusCode,
			MediaType.JSON,
			serializeError(evitaError)
		);
	}

	private String serializeError(@Nonnull EvitaError evitaError) {
		final ErrorDto errorDto = new ErrorDto(evitaError.getErrorCode(), evitaError.getPublicMessage());
		try {
			return this.objectMapper.writeValueAsString(errorDto);
		} catch (JsonProcessingException e) {
			log.error("Could not serialize ErrorDto: {}", e.getMessage());
			return "";
		}
	}
}
