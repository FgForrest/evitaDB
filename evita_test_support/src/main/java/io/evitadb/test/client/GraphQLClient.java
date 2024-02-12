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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.graphql.io.GraphQLRequest;
import io.evitadb.utils.Assert;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.annotation.Nonnull;
import java.io.IOException;

/**
 * Simple client for calling GraphQL requests from documentation.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class GraphQLClient extends ApiClient {

	public GraphQLClient(@Nonnull String url, boolean validateSsl, boolean useConnectionPool) {
		super(url, validateSsl, useConnectionPool);
	}

	@Nonnull
	public JsonNode call(@Nonnull String document) {
		return call("/gql/evita", document);
	}

	@Nonnull
	public JsonNode call(@Nonnull String instancePath, @Nonnull String document) {
		final Request request;
		try {
			request = createRequest(instancePath, document);
		} catch (IOException e) {
			throw new EvitaInternalError("Unexpected error.", e);
		}

		try (Response response = client.newCall(request).execute()) {
			final int responseCode = response.code();
			if (responseCode == 200) {
				final JsonNode responseBody = readResponseBody(response.body());
				validateResponseBody(responseBody);

				return responseBody;
			}
			if (responseCode >= 400 && responseCode <= 499 && responseCode != 404) {
				final String errorResponseString = response.body() != null ? response.body().string() : "no response body";
				throw new EvitaInternalError("Call to GraphQL instance `" + this.url + instancePath + "` ended with status " + responseCode + ", query was:\n" + document + "\n and response was: \n" + errorResponseString);
			}

			throw new EvitaInternalError("Call to GraphQL server ended with status " + responseCode + ", query was:\n" + document);
		} catch (IOException e) {
			throw new EvitaInternalError("Unexpected error.", e);
		}
	}

	@Nonnull
	private Request createRequest(@Nonnull String instancePath, @Nonnull String document) throws IOException {
		return new Request.Builder()
			.url(this.url + instancePath)
			.addHeader("Accept", "application/graphql-response+json")
			.addHeader("Content-Type", "application/json")
			.method("POST", RequestBody.create(createRequestBody(document), MediaType.parse("application/json")))
			.build();
	}

	protected String createRequestBody(@Nonnull String document) throws IOException {
		final GraphQLRequest requestBody = new GraphQLRequest(document, null, null, null);
		final String requestBodyJson = objectMapper.writeValueAsString(requestBody);

		return requestBodyJson;
	}

	private void validateResponseBody(@Nonnull JsonNode responseBody) throws JsonProcessingException {
		final JsonNode errors = responseBody.get("errors");
		Assert.isPremiseValid(
			errors == null || errors.isNull() || errors.isEmpty(),
			"Call to GraphQL server ended with errors: " + objectMapper.writeValueAsString(errors)
		);

		final JsonNode data = responseBody.get("data");
		Assert.isPremiseValid(
			data != null && !data.isNull() && (data.isValueNode() || !data.isEmpty()),
			"Call to GraphQL server ended with empty data."
		);
		data.elements().forEachRemaining(element -> {
			Assert.isPremiseValid(
				element != null && !element.isNull(),
				"Call to GraphQL server ended with empty data."
			);
		});
	}
}
