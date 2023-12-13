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
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.net.Socket;
import java.net.http.HttpClient;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * Ancestor for simple clients calling mainly JSON-based HTTP APIs.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
abstract class ApiClient {

	protected static final ObjectMapper objectMapper = new ObjectMapper();

	protected final boolean validateSsl;
	@Nonnull protected final String url;

	protected ApiClient(@Nonnull String url) {
		this(url, true);
	}

	protected ApiClient(@Nonnull String url, boolean validateSsl) {
		this.url = url;
		this.validateSsl = validateSsl;

	}

	@Nonnull
	protected HttpClient createClient() {
		// todo lho - this is terrible, but I all other way result in `HTTP/1.1 header parser received no bytes`
		if (validateSsl) {
			return HttpClient.newBuilder()
				.build();
		} else {
			// Create a trust manager that does not validate certificate chains
			final TrustManager trustManager = new NoValidateTrustManager();
			SSLContext sslContext = null;
			try {
				sslContext = SSLContext.getInstance("TLS");
				sslContext.init(null, new TrustManager[]{trustManager}, new SecureRandom());
			} catch (NoSuchAlgorithmException | KeyManagementException e) {
				throw new EvitaInternalError("Could create no-validate trust manager: ", e);
			}

			return HttpClient.newBuilder()
				.sslContext(sslContext)
				.build();
		}
	}

	@Nonnull
	protected JsonNode readResponseBody(@Nonnull String body) throws IOException {
		return objectMapper.readTree(body);
	}

	private static class NoValidateTrustManager extends X509ExtendedTrustManager {
		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {

		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {

		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {

		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {

		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {

		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {

		}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			return new X509Certificate[]{};
		}
	}
}
