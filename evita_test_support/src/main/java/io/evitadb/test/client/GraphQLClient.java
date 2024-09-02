/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.test.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.graphql.io.GraphQLRequest;
import io.evitadb.utils.Assert;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Optional;

/**
 * Simple client for calling GraphQL requests from documentation.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class GraphQLClient extends ApiClient {

	private static void validateResponseBody(@Nonnull String document, @Nonnull JsonNode responseBody) {
		try {
			final JsonNode errors = responseBody.get("errors");
			Assert.isPremiseValid(
				errors == null || errors.isNull() || errors.isEmpty(),
				"Call to GraphQL server with document: " + document + " ended with errors: " + objectMapper.writeValueAsString(errors)
			);
		} catch (JsonProcessingException e) {
			throw new GenericEvitaInternalError("Failed to validate GraphQL response:" + e.getMessage(), e);
		}
	}

	public GraphQLClient(@Nonnull String url, boolean validateSsl, boolean useConnectionPool, int numberOfRetries) {
		super(url, validateSsl, useConnectionPool, numberOfRetries);
	}

	@Nonnull
	public Optional<JsonNode> call(@Nonnull String document) {
		return call("/gql/evita", document);
	}

	@Nonnull
	public Optional<JsonNode> call(@Nonnull String instancePath, @Nonnull String document) {
		final Request request = createRequest(instancePath, document);

		return getResponseBodyString(request)
			.map(it -> {
				final JsonNode responseBody = readResponseBody(it);
				validateResponseBody(it, responseBody);
				return responseBody;
			})
			.filter(it -> {
				final JsonNode data = it.get("data");
				return data != null && !data.isNull();
			});
	}

	protected String createRequestBody(@Nonnull String document) {
		try {
			final GraphQLRequest requestBody = new GraphQLRequest(document, null, null, null);
			return objectMapper.writeValueAsString(requestBody);
		} catch (IOException e) {
			throw new GenericEvitaInternalError("Failed to create GraphQL request:" + e.getMessage(), e);
		}
	}

	@Nonnull
	private Request createRequest(@Nonnull String instancePath, @Nonnull String document) {
		return new Request.Builder()
			.url(this.url + instancePath)
			.addHeader("Accept", "application/graphql-response+json")
			.addHeader("Content-Type", "application/json")
			.method("POST", RequestBody.create(createRequestBody(document), MediaType.parse("application/json")))
			.build();
	}
}
