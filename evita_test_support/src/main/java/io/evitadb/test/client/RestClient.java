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

import com.fasterxml.jackson.databind.JsonNode;
import io.evitadb.utils.Assert;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Client for creating HTTP REST requests and executing them.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class RestClient extends ApiClient {

	public RestClient(@Nonnull String url, boolean validateSsl, boolean useConnectionPool, int numberOfRetries) {
		super(url, validateSsl, useConnectionPool, numberOfRetries);
	}

	@Nullable
	public Optional<JsonNode> call(@Nonnull String method, @Nonnull String resource, @Nullable String body) {
		final Request request = createRequest(method, resource, body);

		return getResponseBodyString(request)
			.map(it -> {
				final JsonNode responseBody = readResponseBody(it);
				validateResponseBody(responseBody);
				return responseBody;
			});
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

	private static void validateResponseBody(@Nonnull JsonNode responseBody) {
		Assert.isPremiseValid(
			responseBody != null && !responseBody.isNull(),
			"Call to REST server ended with empty data."
		);
	}
}
