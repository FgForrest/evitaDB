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

package io.evitadb.externalApi.system;

import com.linecorp.armeria.server.HttpService;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.event.ReadinessEvent;
import io.evitadb.externalApi.event.ReadinessEvent.Prospective;
import io.evitadb.externalApi.event.ReadinessEvent.Result;
import io.evitadb.externalApi.http.ExternalApiProviderWithConsoleOutput;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.system.configuration.SystemOptions;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.ConsoleWriter;
import io.evitadb.utils.ConsoleWriter.ConsoleColor;
import io.evitadb.utils.ConsoleWriter.ConsoleDecoration;
import io.evitadb.utils.NetworkUtils;
import io.evitadb.utils.StringUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

import static io.evitadb.externalApi.system.SystemProviderRegistrar.ENDPOINT_SERVER_NAME;

/**
 * Descriptor of external API provider that provides System API.
 *
 * @author Tomáš Pozler, 2023
 * @see SystemProviderRegistrar
 */
@Slf4j
public class SystemProvider implements ExternalApiProviderWithConsoleOutput<SystemOptions> {
	public static final String CODE = "system";
	public static final String SERVER_CERTIFICATE_URL = "serverCertificateUrl";
	public static final String CLIENT_CERTIFICATE_URL = "clientCertificateUrl";
	public static final String CLIENT_PRIVATE_KEY_URL = "clientPrivateKeyUrl";
	public static final String SERVER_NAME_URL = "serverNameUrl";

	@Nonnull
	@Getter
	private final SystemOptions configuration;

	@Nonnull
	@Getter
	private final HttpService apiHandler;

	@Nonnull
	private final LinkedHashMap<String, String[]> endpoints;

	/**
	 * Timeout taken from {@link ApiOptions#requestTimeoutInMillis()} that will be used in {@link #isReady()}
	 * method.
	 */
	private final long requestTimeout;

	/**
	 * Contains url that was at least once found reachable.
	 */
	private String reachableUrl;

	public SystemProvider(
		@Nonnull SystemOptions configuration,
		@Nonnull HttpService apiHandler,
		@Nonnull LinkedHashMap<String, String[]> endpoints,
		long requestTimeoutInMillis
	) {
		this.configuration = configuration;
		this.apiHandler = apiHandler;
		this.endpoints = endpoints;
		this.requestTimeout = requestTimeoutInMillis;
	}

	@Nonnull
	@Override
	public String getCode() {
		return CODE;
	}

	@Nonnull
	@Override
	public HttpServiceDefinition[] getHttpServiceDefinitions() {
		return new HttpServiceDefinition[]{
			new HttpServiceDefinition(
				this.apiHandler,
				PathHandlingMode.DYNAMIC_PATH_HANDLING
			)
		};
	}

	@Override
	public boolean isReady() {
		final Predicate<String> isReady = url -> {
			final ReadinessEvent readinessEvent = new ReadinessEvent(CODE, Prospective.CLIENT);
			final boolean reachable = NetworkUtils.isReachable(
				url,
				this.requestTimeout,
				error -> {
					log.error("Error while checking readiness of System API: {}", error);
					readinessEvent.finish(Result.ERROR);
				},
				timeouted -> {
					log.error("{}", timeouted);
					readinessEvent.finish(Result.TIMEOUT);
				}
			);
			if (reachable) {
				readinessEvent.finish(Result.READY);
			}
			return reachable;
		};
		final String[] baseUrls = this.configuration.getBaseUrls();
		if (this.reachableUrl == null) {
			for (String baseUrl : baseUrls) {
				final String nameUrl = baseUrl + ENDPOINT_SERVER_NAME;
				if (isReady.test(nameUrl)) {
					this.reachableUrl = nameUrl;
					return true;
				}
			}
			return false;
		} else {
			return isReady.test(this.reachableUrl);
		}
	}

	@Nonnull
	@Override
	public Map<String, String[]> getKeyEndPoints() {
		return this.endpoints;
	}

	@Override
	public void writeToConsole() {
		writeLine("   - server name served at: ", SERVER_NAME_URL);
		writeLine("   - server certificate served at: ", SERVER_CERTIFICATE_URL);
		writeLine("   - client certificate served at: ", CLIENT_CERTIFICATE_URL);
		writeLine("   - client private key served at: ", CLIENT_PRIVATE_KEY_URL);

		if (!ArrayUtils.isEmpty(this.endpoints.get(CLIENT_CERTIFICATE_URL))) {
			ConsoleWriter.write("""
					
					************************* WARNING!!! *************************
					You use mTLS with automatically generated client certificate.
					This is not safe for production environments!
					Supply the certificate for production manually and set `useGeneratedCertificate` to false.
					************************* WARNING!!! *************************
					
					""",
				ConsoleColor.BRIGHT_RED, ConsoleDecoration.BOLD
			);
		}
	}

	/**
	 * Writes line to the console.
	 *
	 * @param label       label
	 * @param endpointKey key of the endpoint
	 */
	private void writeLine(@Nonnull String label, @Nonnull String endpointKey) {
		final String[] urls = this.endpoints.get(endpointKey);
		if (!ArrayUtils.isEmpty(urls)) {
			ConsoleWriter.write(StringUtils.rightPad(label, " ", ExternalApiServer.PADDING_START_UP));
			for (int i = 0; i < urls.length; i++) {
				final String serverNameUrl = urls[i];
				if (i > 0) {
					ConsoleWriter.write(", ", ConsoleColor.WHITE);
				}
				ConsoleWriter.write(serverNameUrl, ConsoleColor.DARK_BLUE, ConsoleDecoration.UNDERLINE);
			}
			ConsoleWriter.write("\n", ConsoleColor.WHITE);
		}
	}
}
