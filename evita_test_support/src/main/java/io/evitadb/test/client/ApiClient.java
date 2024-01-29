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
import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.exception.EvitaInternalError;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.ResponseBody;

import javax.annotation.Nonnull;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

/**
 * Ancestor for simple clients calling mainly JSON-based HTTP APIs.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
abstract class ApiClient {

	protected static final ObjectMapper objectMapper = new ObjectMapper();

	@Nonnull protected final String url;
	@Nonnull protected final OkHttpClient client;

	protected ApiClient(@Nonnull String url) {
		this(url, true);
	}

	protected ApiClient(@Nonnull String url, boolean validateSsl) {
		this.url = url;

		final OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
			.connectionPool(new ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(30, TimeUnit.SECONDS);
		if (!validateSsl) {
			// Create a trust manager that does not validate certificate chains
			final TrustManager[] trustAllCerts = new TrustManager[] {
				new X509TrustManager() {
					public X509Certificate[] getAcceptedIssuers() {
						return new X509Certificate[0];
					}
					public void checkClientTrusted(X509Certificate[] certs, String authType) {}
					public void checkServerTrusted(X509Certificate[] certs, String authType) {}
				}
			};
			// Install the all-trusting trust manager
			final SSLContext sc;
			try {
				sc = SSLContext.getInstance("SSL");
			} catch (NoSuchAlgorithmException e) {
				throw new EvitaInternalError("Cannot get SSL context.", e);
			}
			try {
				sc.init(null, trustAllCerts, new java.security.SecureRandom());
			} catch (KeyManagementException e) {
				throw new EvitaInternalError("Cannot init SSL context with custom trust manager.", e);
			}

			// Create an all-trusting host verifier
			HostnameVerifier hostnameVerifier = (hostname, session) -> true;

			clientBuilder
				.sslSocketFactory(sc.getSocketFactory(), (X509TrustManager) trustAllCerts[0])
				.hostnameVerifier(hostnameVerifier);
		}
		this.client = clientBuilder.build();
	}

	@Nonnull
	protected JsonNode readResponseBody(@Nonnull ResponseBody responseBody) throws IOException {
		return objectMapper.readTree(responseBody.string());
	}
}
