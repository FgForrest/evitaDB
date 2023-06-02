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
import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.graphql.io.GraphQLRequest;
import io.evitadb.utils.Assert;

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
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;

/**
 * Simple client for calling GraphQL requests from documentation.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class GraphQLClient {

	private static final ObjectMapper objectMapper = new ObjectMapper();

	private final URL url;

	public GraphQLClient(@Nonnull String url) {
		this(url, true);
	}

	public GraphQLClient(@Nonnull String url, boolean validateSsl) {
		try {
			this.url = new URL(url);
		} catch (MalformedURLException e) {
			throw new EvitaInvalidUsageException("Invalid url.", e);
		}

		if (!validateSsl) {
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

	@Nonnull
	public JsonNode call(@Nonnull String document) {
		HttpURLConnection connection = null;
		try {
			connection = createConnection();
			writeRequestBody(connection, document);

			connection.connect();
			Assert.isPremiseValid(connection.getResponseCode() == 200, "Call to GraphQL server ended with status " + connection.getResponseCode());

			final JsonNode responseBody = readResponseBody(connection);
			validateResponseBody(responseBody);

			return responseBody;
		} catch (IOException e) {
			throw new EvitaInternalError("Unexpected error.", e);
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	@Nonnull
	private HttpURLConnection createConnection() throws IOException {
		final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setRequestMethod("POST");
		connection.setRequestProperty("Content-Type", "application/graphql+json");
		connection.setRequestProperty("Accept", "application/graphql+json");
		connection.setDoOutput(true);
		return connection;
	}

	private void writeRequestBody(@Nonnull HttpURLConnection connection, @Nonnull String document) throws IOException {
		final GraphQLRequest requestBody = new GraphQLRequest(document, null, null);
		final String requestBodyJson = objectMapper.writeValueAsString(requestBody);

		try (OutputStream os = connection.getOutputStream()) {
			byte[] input = requestBodyJson.getBytes(StandardCharsets.UTF_8);
			os.write(input, 0, input.length);
		}
	}

	@Nonnull
	private JsonNode readResponseBody(@Nonnull HttpURLConnection connection) throws IOException {
		final StringBuilder rawResponseBody = new StringBuilder();
		try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
			String responseLine;
			while ((responseLine = br.readLine()) != null) {
				rawResponseBody.append(responseLine.trim());
			}
		}

		return objectMapper.readTree(rawResponseBody.toString());
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
