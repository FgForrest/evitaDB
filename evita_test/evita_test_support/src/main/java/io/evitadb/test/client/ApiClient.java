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

package io.evitadb.test.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.annotation.Nonnull;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Ancestor for simple clients calling mainly JSON-based HTTP APIs.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
abstract class ApiClient {

	protected static final ObjectMapper objectMapper = new ObjectMapper();
	@Nonnull protected final OkHttpClient client;
	protected final int numberOfRetries;
	@Nonnull protected final String url;

	protected ApiClient(@Nonnull String url, boolean validateSsl, boolean useConnectionPool, int numberOfRetries) {
		this.url = url;
		this.client = createClient(validateSsl, useConnectionPool);
		this.numberOfRetries = numberOfRetries;
	}

	protected OkHttpClient createClient(boolean validateSsl, boolean useConnectionPool) {
		final OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder();

		if (!useConnectionPool) {
			clientBuilder.connectionPool(new ConnectionPool(0, 1, TimeUnit.NANOSECONDS));
		}

		if (!validateSsl) {
			// Create a trust manager that does not validate certificate chains
			final TrustManager[] trustAllCerts = new TrustManager[]{
				new X509TrustManager() {
					public void checkClientTrusted(X509Certificate[] certs, String authType) {
					}

					public void checkServerTrusted(X509Certificate[] certs, String authType) {
					}

					public X509Certificate[] getAcceptedIssuers() {
						return new X509Certificate[0];
					}
				}
			};
			// Install the all-trusting trust manager
			final SSLContext sc;
			try {
				sc = SSLContext.getInstance("SSL");
			} catch (NoSuchAlgorithmException e) {
				throw new GenericEvitaInternalError("Cannot get SSL context.", e);
			}
			try {
				sc.init(null, trustAllCerts, new java.security.SecureRandom());
			} catch (KeyManagementException e) {
				throw new GenericEvitaInternalError("Cannot init SSL context with custom trust manager.", e);
			}

			// Create an all-trusting host verifier
			HostnameVerifier hostnameVerifier = (hostname, session) -> true;

			clientBuilder
				.sslSocketFactory(sc.getSocketFactory(), (X509TrustManager) trustAllCerts[0])
				.hostnameVerifier(hostnameVerifier);
		}
		return clientBuilder.build();
	}

	@Nonnull
	protected Optional<String> getResponseBodyString(@Nonnull Request request) {
		RuntimeException firstException = null;
		for (int i = 0; i < this.numberOfRetries; i++) {
			try (Response response = this.client.newCall(request).execute()) {
				final int responseCode = response.code();
				if (responseCode == 200) {
					return response.body() != null ?
						ofNullable(response.body().string()) : of("no response body");
				} else if (responseCode == 404) {
					return empty();
				} else if (responseCode >= 400 && responseCode <= 499) {
					final String errorResponseString = response.body() != null ? response.body().string() : "no response body";
					firstException = firstException == null ?
						new GenericEvitaInternalError("Call to web server `" + request.url() + "` ended with status " + responseCode + " and response: \n" + errorResponseString) :
						firstException;
				} else {
					firstException = firstException == null ?
						new GenericEvitaInternalError("Call to web server `" + request.url() + "` ended with status " + responseCode) :
						firstException;
				}
			} catch (IOException e) {
				firstException = firstException == null ?
					new GenericEvitaInternalError("Unexpected error.", e) : firstException;
			}
		}
		Assert.isPremiseValid(firstException != null, "An exception is expected here.");
		throw new GenericEvitaInternalError("Error calling server even with " + this.numberOfRetries + " retries:", firstException);
	}

	@Nonnull
	protected JsonNode readResponseBody(@Nonnull String responseBody) {
		try {
			return objectMapper.readTree(responseBody);
		} catch (IOException e) {
			throw new GenericEvitaInternalError("Failed to read response as JSON:" + e.getMessage(), e);
		}
	}
}
