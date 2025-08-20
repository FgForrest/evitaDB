/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponseWriter;
import io.evitadb.externalApi.http.MimeTypes;
import io.evitadb.externalApi.rest.exception.OpenApiInternalError;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import io.evitadb.utils.Assert;
import io.netty.channel.EventLoop;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@link RestEndpointHandler} that uses JSON as a request and response body format.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public abstract class JsonRestHandler<CTX extends RestHandlingContext> extends RestEndpointHandler<CTX> {

	protected static final LinkedHashSet<String> DEFAULT_SUPPORTED_CONTENT_TYPES = new LinkedHashSet<>(List.of(MimeTypes.APPLICATION_JSON));

	protected JsonRestHandler(@Nonnull CTX restApiHandlingContext) {
		super(restApiHandlingContext);
	}

	/**
	 * Tries to parse input request body JSON into data class.
	 */
	@Nonnull
	protected <T> CompletableFuture<T> parseRequestBody(@Nonnull RestEndpointExecutionContext executionContext, @Nonnull Class<T> dataClass) {
		return readRawRequestBody(executionContext)
			.thenApply(content -> parseRequestBody(content, dataClass));
	}

	/**
	 * Tries to parse input request body JSON into data class.
	 */
	@Nonnull
	protected <T> T parseRequestBody(@Nonnull String rawRequestBody, @Nonnull Class<T> dataClass) {
		Assert.isTrue(
			!rawRequestBody.trim().isEmpty(),
			() -> new RestInvalidArgumentException("Request's body contains no data.")
		);

		try {
			return this.restHandlingContext.getObjectMapper().readValue(rawRequestBody, dataClass);
		} catch (JsonProcessingException e) {
			throw new RestInvalidArgumentException("Invalid request body: " + e.getLocation().toString(), e);
		}
	}

	@Override
	protected void writeResponse(@Nonnull RestEndpointExecutionContext executionContext, @Nonnull HttpResponseWriter responseWriter, @Nonnull Object result, @Nonnull EventLoop eventExecutors) {
		try {
			responseWriter.write(HttpData.copyOf(this.restHandlingContext.getObjectMapper().writeValueAsBytes(result)));
		} catch (IOException e) {
			throw new OpenApiInternalError(
				"Could not serialize Java object response to JSON: " + e.getMessage(),
				"Could not provide response data.", e
			);
		}
	}

	/**
	 * Converts result into an object that can be safely serialized by {@link com.fasterxml.jackson.databind.ObjectMapper}.
	 * By default, this method returns the result as-is.
	 *
	 * @param exchange endpoint exchange
	 * @param result original result to convert
	 * @return result object read to be serialized
	 */
	@Nonnull
	protected Object convertResultIntoSerializableObject(@Nonnull RestEndpointExecutionContext exchange, @Nonnull Object result) {
		return result;
	}
}
