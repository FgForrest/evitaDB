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

package io.evitadb.documentation.graphql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.externalApi.graphql.io.GraphQLRequest;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;

/**
 * Simple client for calling GraphQL requests from documentation.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class GraphQLClient {

	private static final ObjectMapper objectMapper = new ObjectMapper();

	private final HttpClient httpClient;
	private final String url;

	public GraphQLClient(@Nonnull String url) {
		this.httpClient = HttpClient.newBuilder().build();
		this.url = url;
	}

	@Nonnull
	public JsonNode call(@Nonnull String document) throws IOException, InterruptedException {
		final GraphQLRequest requestBody = new GraphQLRequest(document, null, null);
		final String requestBodyJson = objectMapper.writeValueAsString(requestBody);

		final HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(url))
			.POST(BodyPublishers.ofString(requestBodyJson))
			.header("Content-Type", "application/graphql+json")
			.header("Accept", "application/graphql+json")
			.build();

		final HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
		final JsonNode responseBody = objectMapper.readTree(response.body());

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

		return responseBody;
	}
}
