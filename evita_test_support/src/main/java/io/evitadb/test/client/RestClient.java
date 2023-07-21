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

import com.fasterxml.jackson.databind.JsonNode;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
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
		HttpURLConnection connection = null;
		try {
			connection = createConnection(method, resource);
			if (body != null && !body.isBlank()) {
				writeRequestBody(connection, body);
			}

			connection.connect();

			final int responseCode = connection.getResponseCode();
			if (responseCode == 200) {
				return Optional.of(readResponseBody(connection.getInputStream()));
			}
			if (responseCode == 404) {
				return Optional.empty();
			}
			if (responseCode >= 400 && responseCode <= 499) {
				final JsonNode errorResponse = readResponseBody(connection.getErrorStream());
				final String errorResponseString = objectMapper.writeValueAsString(errorResponse);
				throw new EvitaInternalError("Call to REST server `" + this.url + resource + "` ended with status " + responseCode + " and response: \n" + errorResponseString);
			}

			throw new EvitaInternalError("Call to REST server `" + this.url + resource + "` ended with status " + responseCode);
		} catch (IOException | URISyntaxException e) {
			throw new EvitaInternalError("Unexpected error.", e);
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	@Nonnull
	private  HttpURLConnection createConnection(@Nonnull String method, @Nonnull String resource) throws IOException, URISyntaxException {
		final URL url = new URL(this.url + resource);
		final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setRequestProperty("Accept", "application/json");
		connection.setRequestProperty("Content-Type", "application/json");
		connection.setRequestMethod(method);
		connection.setDoOutput(true);
		return connection;
	}
}
