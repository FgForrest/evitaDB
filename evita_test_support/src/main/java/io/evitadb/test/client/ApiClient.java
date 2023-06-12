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
import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.exception.EvitaInternalError;

import javax.annotation.Nonnull;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;

/**
 * Ancestor for simple clients calling mainly JSON-based HTTP APIs.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
abstract class ApiClient {

	protected static final ObjectMapper objectMapper = new ObjectMapper();

	@Nonnull protected final String url;

	protected ApiClient(@Nonnull String url) {
		this(url, true);
	}

	protected ApiClient(@Nonnull String url, boolean validateSsl) {
		this.url = url;

		if (!validateSsl) {
			// Create a trust manager that does not validate certificate chains
			final TrustManager[] trustAllCerts = new TrustManager[] {
				new X509TrustManager() {
					public X509Certificate[] getAcceptedIssuers() {
						return null;
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
			HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

			// Install the all-trusting host verifier
			HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
		}
	}

	protected void writeRequestBody(@Nonnull HttpURLConnection connection, @Nonnull String body) throws IOException {
		try (OutputStream os = connection.getOutputStream()) {
			byte[] input = body.getBytes(StandardCharsets.UTF_8);
			os.write(input, 0, input.length);
		}
	}

	@Nonnull
	protected JsonNode readResponseBody(@Nonnull HttpURLConnection connection) throws IOException {
		final StringBuilder rawResponseBody = new StringBuilder();
		try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
			String responseLine;
			while ((responseLine = br.readLine()) != null) {
				rawResponseBody.append(responseLine.trim());
			}
		}

		return objectMapper.readTree(rawResponseBody.toString());
	}
}
