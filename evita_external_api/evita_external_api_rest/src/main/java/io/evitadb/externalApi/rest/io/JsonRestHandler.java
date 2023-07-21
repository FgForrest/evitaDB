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

package io.evitadb.externalApi.rest.io;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.evitadb.externalApi.http.MimeTypes;
import io.evitadb.externalApi.rest.exception.OpenApiInternalError;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * {@link RestEndpointHandler} that uses JSON as a request and response body format.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public abstract class JsonRestHandler<R, CTX extends RestHandlingContext> extends RestEndpointHandler<R, CTX> {

	protected static final LinkedHashSet<String> DEFAULT_SUPPORTED_CONTENT_TYPES = new LinkedHashSet<>(List.of(MimeTypes.APPLICATION_JSON));

	protected JsonRestHandler(@Nonnull CTX restApiHandlingContext) {
		super(restApiHandlingContext);
	}

	/**
	 * Tries to parse input request body JSON into data class.
	 */
	@Nonnull
	protected <T> T parseRequestBody(@Nonnull RestEndpointExchange exchange, @Nonnull Class<T> dataClass) {
		final String content = readRawRequestBody(exchange);
		Assert.isTrue(
			!content.trim().isEmpty(),
			() -> new RestInvalidArgumentException("Request's body contains no data.")
		);

		try {
			return restApiHandlingContext.getObjectMapper().readValue(content, dataClass);
		} catch (JsonProcessingException e) {
			throw new RestInternalError("Could not parse request body: ", e);
		}
	}

	@Nonnull
	@Override
	protected String serializeResult(@Nonnull RestEndpointExchange exchange, @Nonnull R responseData) {
		try {
			final JsonNode jsonNode = convertResultIntoJson(exchange, responseData);
			return restApiHandlingContext.getObjectMapper().writeValueAsString(jsonNode);
		} catch (JsonProcessingException e) {
			throw new OpenApiInternalError(
				"Could not serialize Java object response to JSON: " + e.getMessage(),
				"Could not provide response data.", e
			);
		}
	}

	@Nonnull
	protected JsonNode convertResultIntoJson(@Nonnull RestEndpointExchange exchange, @Nonnull R responseData) {
		return restApiHandlingContext.getObjectMapper().valueToTree(responseData);
	}
}
