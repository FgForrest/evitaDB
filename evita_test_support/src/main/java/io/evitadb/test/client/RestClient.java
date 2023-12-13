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
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Optional;

/**
 * Client for creating HTTP REST requests and executing them.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class RestClient extends ApiClient {

	public RestClient(@Nonnull String url) {
		super(url);
	}

	public RestClient(@Nonnull String url, boolean validateSsl) {
		super(url, validateSsl);
	}

	@Nullable
	public Optional<JsonNode> call(@Nonnull String method, @Nonnull String resource, @Nullable String body) {
		try {
			final HttpRequest request = createRequest(method, resource, body);
			final HttpResponse<String> response = createClient().send(request, BodyHandlers.ofString());

			final int responseCode = response.statusCode();
			if (responseCode == 200) {
				final JsonNode responseBody = readResponseBody(response.body());
				validateResponseBody(responseBody);

				return Optional.of(responseBody);
			}
			if (responseCode == 404) {
				return Optional.empty();
			}
			if (responseCode >= 400 && responseCode <= 499) {
				throw new EvitaInternalError("Call to REST server `" + this.url + resource + "` ended with status " + responseCode + " and response: \n" + response.body());
			}

			throw new EvitaInternalError("Call to REST server `" + this.url + resource + "` ended with status " + responseCode);
		} catch (IOException | URISyntaxException | InterruptedException e) {
			throw new EvitaInternalError("Unexpected error.", e);
		}
	}

	@Nonnull
	private  HttpRequest createRequest(@Nonnull String method, @Nonnull String resource, @Nullable String body) throws IOException, URISyntaxException {
		return HttpRequest.newBuilder()
			.uri(URI.create(this.url + resource))
			.method(method, body != null && !body.isBlank() ? HttpRequest.BodyPublishers.ofString(body) : HttpRequest.BodyPublishers.noBody())
			.header("Accept", "application/json")
			.header("Content-Type", "application/json")
			.build();
	}

	private void validateResponseBody(@Nonnull JsonNode responseBody) throws JsonProcessingException {
		Assert.isPremiseValid(
			responseBody != null && !responseBody.isNull(),
			"Call to REST server ended with empty data."
		);
	}
}
