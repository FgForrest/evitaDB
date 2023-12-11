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
import java.net.HttpURLConnection;
import java.net.URL;

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
		HttpURLConnection connection = null;
		try {
			connection = createConnection(instancePath);
			writeRequestBody(connection, document);

			connection.connect();
			final int responseCode = connection.getResponseCode();
			if (responseCode == 200) {
				final JsonNode responseBody = readResponseBody(connection.getInputStream());
				validateResponseBody(responseBody);

				return responseBody;
			}
			if (responseCode >= 400 && responseCode <= 499 && responseCode != 404) {
				final JsonNode errorResponse = readResponseBody(connection.getErrorStream());
				final String errorResponseString = objectMapper.writeValueAsString(errorResponse);
				throw new EvitaInternalError("Call to GraphQL instance `" + this.url + instancePath + "` ended with status " + responseCode + ", query was:\n" + document + "\n and response was: \n" + errorResponseString);
			}

			throw new EvitaInternalError("Call to GraphQL server ended with status " + responseCode + ", query was:\n" + document);
		} catch (IOException e) {
			throw new EvitaInternalError("Unexpected error.", e);
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	@Nonnull
	private HttpURLConnection createConnection(@Nonnull String instancePath) throws IOException {
		final URL url = new URL(this.url + instancePath);
		final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setRequestMethod("POST");
		connection.setRequestProperty("Content-Type", "application/json");
		connection.setRequestProperty("Accept", "application/graphql-response+json");
		connection.setDoOutput(true);
		return connection;
	}

	@Override
	protected void writeRequestBody(@Nonnull HttpURLConnection connection, @Nonnull String document) throws IOException {
		final GraphQLRequest requestBody = new GraphQLRequest(document, null, null, null);
		final String requestBodyJson = objectMapper.writeValueAsString(requestBody);

		super.writeRequestBody(connection, requestBodyJson);
	}

	private void validateResponseBody(@Nonnull JsonNode responseBody) throws JsonProcessingException {
		final JsonNode errors = responseBody.get("errors");
		Assert.isPremiseValid(
			errors == null || errors.isNull() || errors.isEmpty(),
			"Call to GraphQL server ended with errors: " + objectMapper.writeValueAsString(errors)
		);

		final JsonNode data = responseBody.get("data");
		Assert.isPremiseValid(
			data != null && !data.isNull() && !data.isEmpty(),
			"Call to GraphQL server ended with empty data."
		);
	}
}
