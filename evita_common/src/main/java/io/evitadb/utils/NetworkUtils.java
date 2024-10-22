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

package io.evitadb.utils;

import io.evitadb.exception.InvalidHostDefinitionException;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Request.Builder;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Utility class for network related operations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@Slf4j
public class NetworkUtils {
	/**
	 * This shouldn't be changed - only in tests which needs to extend this timeout for slower machines runnning
	 * parallel tests and squeezing the resources.
	 */
	public static int DEFAULT_CLIENT_TIMEOUT = 1000;
	private static OkHttpClient HTTP_CLIENT;

	/**
	 * Returns human comprehensible host name of the given host.
	 *
	 * @param host host to get the name for
	 * @return human comprehensible host name of the given host
	 */
	@Nonnull
	public static String getHostName(@Nonnull InetAddress host) {
		try {
			return host.isAnyLocalAddress() ? InetAddress.getLocalHost().getHostName() : host.getCanonicalHostName();
		} catch (UnknownHostException ignored) {
			return host.getCanonicalHostName();
		}
	}

	/**
	 * Returns human comprehensible host name of the local host.
	 *
	 * @return human comprehensible host name of the local host
	 */
	@Nonnull
	public static String getLocalHostName() {
		try {
			return getHostName(InetAddress.getLocalHost());
		} catch (UnknownHostException e) {
			return InetAddress.getLoopbackAddress().getCanonicalHostName();
		}
	}

	/**
	 * Returns true if the URL is reachable and returns some content
	 *
	 * @param url URL to check
	 * @param origin request origin to use
	 * @return true if the URL is reachable and returns some content
	 */
	public static boolean isReachable(
		@Nonnull String url,
		@Nullable String origin,
		@Nullable Consumer<String> errorConsumer,
		@Nullable Consumer<String> timeoutConsumer
	) {
		try {
			final Builder requestBuilder = new Builder()
				.url(url)
				.get();
			if (origin != null) {
				requestBuilder.addHeader("Origin", origin);
			}

			try (final Response response = getHttpClient().newCall(requestBuilder.build()).execute()) {
				if (!response.isSuccessful()) {
					ofNullable(errorConsumer)
						.ifPresent(it -> it.accept("Error fetching content from URL: " + url + " HTTP status " + response.code() + " - " + response.message()));
					return false;
				}
				return response.code() == 200;
			}
		} catch (SocketTimeoutException e) {
			ofNullable(timeoutConsumer)
				.ifPresent(it -> it.accept("Fetching content from URL timed out: " + url + " - " + e.getMessage()));
			return false;
		} catch (IOException e) {
			ofNullable(errorConsumer)
				.ifPresent(it -> it.accept("Error fetching content from URL: " + url + " - " + e.getMessage()));
			return false;
		}
	}

	/**
	 * Returns content of the URL if it is reachable and returns some content.
	 *
	 * @param url         URL to check
	 * @param method      HTTP method to use
	 * @param contentType content type to use
	 * @param body        body to send
	 * @return the content of the URL as a string or empty optional if the URL is not reachable or
	 * does not return any content
	 */
	@Nonnull
	public static Optional<String> fetchContent(
		@Nonnull String url,
		@Nullable String method,
		@Nonnull String contentType,
		@Nullable String origin,
		@Nullable String body,
		@Nullable Consumer<String> errorConsumer,
		@Nullable Consumer<String> timeoutConsumer
	) {
		try {
			final RequestBody requestBody = ofNullable(body)
				.map(theBody -> RequestBody.create(theBody, MediaType.parse(contentType)))
				.orElse(null);

			final Request.Builder requestBuilder = new Request.Builder()
				.url(url)
				.method(method != null ? method : "GET", requestBody)
				.addHeader("Accept", contentType)
				.addHeader("Content-Type", contentType);
			if (origin != null) {
				requestBuilder.addHeader("Origin", origin);
			}

			try (final Response response = getHttpClient().newCall(requestBuilder.build()).execute()) {
				if (!response.isSuccessful()) {
					ofNullable(errorConsumer)
						.ifPresent(it -> it.accept(
							"Error fetching content from URL: " + url + " HTTP status " + response.code() + (response.message().isBlank() ? "" : " - " + response.message()) + (response.body().contentLength() > 0 ? ": " + readBodyString(response) : ""))
						);
					return empty();
				} else {
					return of(response.body().string());
				}
			}
		} catch (SocketTimeoutException e) {
			ofNullable(timeoutConsumer)
				.ifPresent(it -> it.accept("Fetching content from URL timed out: " + url + " - " + e.getMessage()));
			return empty();
		} catch (IOException e) {
			ofNullable(errorConsumer)
				.ifPresent(it -> it.accept("Error fetching content from URL: " + url + " - " + e.getMessage()));
			return empty();
		}
	}

	/**
	 * Returns the IP address of the given host.
	 *
	 * @param host host to get the IP address for
	 * @return the IP address of the given host
	 */
	@Nonnull
	public static InetAddress getByName(@Nonnull String host) {
		try {
			return InetAddress.getByName(host);
		} catch (UnknownHostException e) {
			throw new InvalidHostDefinitionException(
				"Invalid host definition in evita server configuration!",
				"Invalid host definition `" + host + "` in evita server configuration!",
				e
			);
		}
	}

	/**
	 * Reads the body string from the given response.
	 *
	 * @param response the HTTP response, must not be null
	 * @return the body of the response as a string, or an error message if an exception occurs, never null
	 */
	private static String readBodyString(@Nonnull Response response) {
		try {
			return response.body().string();
		} catch (IOException e) {
			return "Error reading response body: " + e.getMessage();
		}
	}

	/**
	 * Returns the cached HTTP client instance. Instance has low timeouts and trusts all certificates, it doesn't reuse
	 * connections.
	 *
	 * @return the HTTP client instance
	 */
	@Nonnull
	private static OkHttpClient getHttpClient() {
		if (HTTP_CLIENT == null) {
			try {
				// Get a new SSL context
				final SSLContext sc = SSLContext.getInstance("TLSv1.3");
				sc.init(null, new TrustManager[]{TrustAllX509TrustManager.INSTANCE}, new java.security.SecureRandom());

				HTTP_CLIENT = new OkHttpClient.Builder()
					.hostnameVerifier((hostname, session) -> true)
					.sslSocketFactory(sc.getSocketFactory(), TrustAllX509TrustManager.INSTANCE)
					.protocols(Arrays.asList(Protocol.HTTP_1_1, Protocol.HTTP_2))
					.readTimeout(DEFAULT_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS)
					.callTimeout(DEFAULT_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS)
					.connectionPool(new ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
					.build();
			} catch (NoSuchAlgorithmException | KeyManagementException e) {
				throw new IllegalStateException("Failed to create HTTP client", e);
			}
		}
		return HTTP_CLIENT;
	}

	/**
	 * This trust manager is meant to be used only by this class to enable trust of all certificates. It's unsafe so
	 * it should be used only for fetching the data from local HTTP servers that are in the same network (trusted zone).
	 */
	@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
	private static class TrustAllX509TrustManager implements X509TrustManager {
		public static final TrustAllX509TrustManager INSTANCE = new TrustAllX509TrustManager();

		public void checkClientTrusted(X509Certificate[] certs, String authType) {
		}

		public void checkServerTrusted(X509Certificate[] certs, String authType) {
		}

		public X509Certificate[] getAcceptedIssuers() {
			return new X509Certificate[0];
		}

	}
}
