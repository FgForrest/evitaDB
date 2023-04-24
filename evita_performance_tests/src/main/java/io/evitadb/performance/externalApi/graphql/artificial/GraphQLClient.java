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

package io.evitadb.performance.externalApi.graphql.artificial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.utils.Assert;
import lombok.SneakyThrows;

import javax.annotation.Nonnull;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Map;

/**
 * Client for creating HTTP GraphQL requests and executing them.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class GraphQLClient {

	private static final ObjectMapper objectMapper = new ObjectMapper();

	@SneakyThrows
	public GraphQLClient() {
		// Create a trust manager that does not validate certificate chains
		final TrustManager[] trustAllCerts = new TrustManager[] {
			new X509TrustManager() {
				public java.security.cert.X509Certificate[] getAcceptedIssuers() {
					return null;
				}
				public void checkClientTrusted(X509Certificate[] certs, String authType) {}
				public void checkServerTrusted(X509Certificate[] certs, String authType) {}
			}
		};
		// Install the all-trusting trust manager
		SSLContext sc = SSLContext.getInstance("SSL");
		sc.init(null, trustAllCerts, new java.security.SecureRandom());
		HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

		// Install the all-trusting host verifier
		HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
	}

	@SneakyThrows
	public JsonNode call(@Nonnull String document) {
		final Map<String, Object> requestBody = Map.of(
			"query", document
		);
		final String requestBodyJson = objectMapper.writeValueAsString(requestBody);

		final URL url = new URL("https://" + InetAddress.getByName("localhost").getHostAddress() + ":5555/gql/test-catalog");
		final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setRequestMethod("POST");
		connection.setRequestProperty("Content-Type", "application/json");
		connection.setRequestProperty("Accept", "application/json");
		connection.setDoOutput(true);

		try (OutputStream os = connection.getOutputStream()) {
			byte[] input = requestBodyJson.getBytes(StandardCharsets.UTF_8);
			os.write(input, 0, input.length);
		}

		connection.connect();

		Assert.isPremiseValid(connection.getResponseCode() == 200, "Call to GraphQL server ended with status " + connection.getResponseCode());
		final StringBuilder response = new StringBuilder();
		try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
			String responseLine;
			while ((responseLine = br.readLine()) != null) {
				response.append(responseLine.trim());
			}
		}
		final JsonNode result = objectMapper.readTree(response.toString());
		final JsonNode errors = result.get("errors");
		Assert.isPremiseValid(
			errors == null || errors.isNull() || errors.isEmpty(),
			"Call to GraphQL server ended with errors: " + objectMapper.writeValueAsString(errors)
		);

		connection.disconnect();
		return result;
	}
}
