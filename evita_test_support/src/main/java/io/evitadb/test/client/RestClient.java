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

import com.fasterxml.jackson.databind.JsonNode;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.utils.Assert;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Optional;

/**
 * Client for creating HTTP REST requests and executing them.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class RestClient extends ApiClient {

	public RestClient(@Nonnull String url, boolean validateSsl, boolean useConnectionPool) {
		super(url, validateSsl, useConnectionPool);
	}

	@Nullable
	public Optional<JsonNode> call(@Nonnull String method, @Nonnull String resource, @Nullable String body) {
		final Request request = createRequest(method, resource, body);

		try (Response response = client.newCall(request).execute()) {
			final int responseCode = response.code();
			if (responseCode == 200) {
				final JsonNode responseBody = readResponseBody(response.body());
				validateResponseBody(responseBody);

				return Optional.of(responseBody);
			}
			if (responseCode == 404) {
				return Optional.empty();
			}
			if (responseCode >= 400 && responseCode <= 499) {
				final String errorResponseString = response.body() != null ? response.body().string() : "no response body";
				throw new EvitaInternalError("Call to REST server `" + this.url + resource + "` ended with status " + responseCode + " and response: \n" + errorResponseString);
			}

			throw new EvitaInternalError("Call to REST server `" + this.url + resource + "` ended with status " + responseCode);
		} catch (IOException e) {
			throw new EvitaInternalError("Unexpected error.", e);
		}
	}

	@Nonnull
	private Request createRequest(@Nonnull String method, @Nonnull String resource, @Nullable String body) {
		return new Request.Builder()
			.url(this.url + resource)
			.addHeader("Accept", "application/json")
			.addHeader("Content-Type", "application/json")
			.method(method, body != null && !body.isBlank() ? RequestBody.create(body, MediaType.parse("application/json")) : null)
			.build();

	}

	private void validateResponseBody(@Nonnull JsonNode responseBody) {
		Assert.isPremiseValid(
			responseBody != null && !responseBody.isNull(),
			"Call to REST server ended with empty data."
		);
	}
}
