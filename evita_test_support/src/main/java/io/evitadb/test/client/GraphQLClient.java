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

package io.evitadb.test.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.graphql.io.GraphQLRequest;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

/**
 * Simple client for calling GraphQL requests from documentation.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class GraphQLClient extends ApiClient {

	public GraphQLClient(@Nonnull String url) {
		super(url);
	}

	public GraphQLClient(@Nonnull String url, boolean validateSsl) {
		super(url, validateSsl);
	}

	@Nonnull
	public JsonNode call(@Nonnull String document) {
		return call("/gql/evita", document);
	}

	@Nonnull
	public JsonNode call(@Nonnull String instancePath, @Nonnull String document) {
		try {
			final HttpRequest request = createRequest(instancePath, document);
			final HttpResponse<String> response = client.send(request, BodyHandlers.ofString());

			final int responseCode = response.statusCode();
			if (responseCode == 200) {
				final JsonNode responseBody = readResponseBody(response.body());
				validateResponseBody(responseBody);

				return responseBody;
			}
			if (responseCode >= 400 && responseCode <= 499 && responseCode != 404) {
				throw new EvitaInternalError("Call to GraphQL instance `" + this.url + instancePath + "` ended with status " + responseCode + ", query was:\n" + document + "\n and response was: \n" + response.body());
			}

			throw new EvitaInternalError("Call to GraphQL server ended with status " + responseCode + ", query was:\n" + document);
		} catch (IOException | InterruptedException e) {
			throw new EvitaInternalError("Unexpected error.", e);
		}
	}

	@Nonnull
	private HttpRequest createRequest(@Nonnull String instancePath, @Nonnull String document) throws IOException {
		final GraphQLRequest requestBody = new GraphQLRequest(document, null, null, null);
		final String requestBodyJson = objectMapper.writeValueAsString(requestBody);

		return HttpRequest.newBuilder()
			.uri(URI.create(this.url + instancePath))
			.method("POST", HttpRequest.BodyPublishers.ofString(requestBodyJson))
			.header("Accept", "application/graphql-response+json")
			.header("Content-Type", "application/json")
			.build();
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
