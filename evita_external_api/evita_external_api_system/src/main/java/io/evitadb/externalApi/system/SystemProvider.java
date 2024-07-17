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

package io.evitadb.externalApi.system;

import com.linecorp.armeria.server.HttpService;
import io.evitadb.externalApi.http.ExternalApiProviderWithConsoleOutput;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.system.configuration.SystemConfig;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.ConsoleWriter;
import io.evitadb.utils.ConsoleWriter.ConsoleColor;
import io.evitadb.utils.ConsoleWriter.ConsoleDecoration;
import io.evitadb.utils.NetworkUtils;
import io.evitadb.utils.StringUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Descriptor of external API provider that provides System API.
 *
 * @see SystemProviderRegistrar
 * @author Tomáš Pozler, 2023
 */
@RequiredArgsConstructor
public class SystemProvider implements ExternalApiProviderWithConsoleOutput<SystemConfig> {
	public static final String CODE = "system";

	@Nonnull
	@Getter
	private final SystemConfig configuration;

	@Nonnull
	@Getter
	private final HttpService apiHandler;

	@Nonnull
	@Getter
	private final String[] serverNameUrls;

	@Nonnull
	@Getter
	private final String[] rootCertificateUrls;

	@Nonnull
	@Getter
	private final String[] serverCertificateUrls;

	@Nonnull
	@Getter
	private final String[] clientCertificateUrls;

	@Nonnull
	@Getter
	private final String[] clientPrivateKeyUrls;

	/**
	 * Contains url that was at least once found reachable.
	 */
	private String reachableUrl;

	@Nonnull
	@Override
	public String getCode() {
		return CODE;
	}

	@Nonnull
	@Override
	public HttpServiceDefinition[] getHttpServiceDefinitions() {
		return new HttpServiceDefinition[] {
			new HttpServiceDefinition(
				apiHandler,
				PathHandlingMode.DYNAMIC_PATH_HANDLING
			)
		};
	}

	@Override
	public void writeToConsole() {
		ConsoleWriter.write(StringUtils.rightPad("   - server name served at: ", " ", ExternalApiServer.PADDING_START_UP));
		for (int i = 0; i < serverNameUrls.length; i++) {
			final String serverNameUrl = serverNameUrls[i];
			if (i > 0) {
				ConsoleWriter.write(", ", ConsoleColor.WHITE);
			}
			ConsoleWriter.write(serverNameUrl, ConsoleColor.DARK_BLUE, ConsoleDecoration.UNDERLINE);
		}
		ConsoleWriter.write("\n", ConsoleColor.WHITE);
		if (!ArrayUtils.isEmpty(rootCertificateUrls)) {
			ConsoleWriter.write(StringUtils.rightPad("   - CA certificate served at: ", " ", ExternalApiServer.PADDING_START_UP));
			for (int i = 0; i < rootCertificateUrls.length; i++) {
				final String rootCertificateUrl = rootCertificateUrls[i];
				if (i > 0) {
					ConsoleWriter.write(", ", ConsoleColor.WHITE);
				}
				ConsoleWriter.write(rootCertificateUrl, ConsoleColor.DARK_BLUE, ConsoleDecoration.UNDERLINE);
			}
			ConsoleWriter.write("\n", ConsoleColor.WHITE);
		}
		if (!ArrayUtils.isEmpty(serverCertificateUrls)) {
			ConsoleWriter.write(StringUtils.rightPad("   - server certificate served at: ", " ", ExternalApiServer.PADDING_START_UP));
			for (int i = 0; i < serverCertificateUrls.length; i++) {
				final String serverCertificateUrl = serverCertificateUrls[i];
				if (i > 0) {
					ConsoleWriter.write(", ", ConsoleColor.WHITE);
				}
				ConsoleWriter.write(serverCertificateUrl, ConsoleColor.DARK_BLUE, ConsoleDecoration.UNDERLINE);
			}
			ConsoleWriter.write("\n", ConsoleColor.WHITE);
		}
		if (!ArrayUtils.isEmpty(clientCertificateUrls)) {
			ConsoleWriter.write(StringUtils.rightPad("   - client certificate served at: ", " ", ExternalApiServer.PADDING_START_UP));
			for (int i = 0; i < clientCertificateUrls.length; i++) {
				final String clientCertificateUrl = clientCertificateUrls[i];
				if (i > 0) {
					ConsoleWriter.write(", ", ConsoleColor.WHITE);
				}
				ConsoleWriter.write(clientCertificateUrl, ConsoleColor.DARK_BLUE, ConsoleDecoration.UNDERLINE);
			}
			ConsoleWriter.write("\n", ConsoleColor.WHITE);
		}
		if (!ArrayUtils.isEmpty(clientPrivateKeyUrls)) {
			ConsoleWriter.write(StringUtils.rightPad("   - client private key served at: ", " ", ExternalApiServer.PADDING_START_UP));
			for (int i = 0; i < clientPrivateKeyUrls.length; i++) {
				final String clientPrivateKeyUrl = clientPrivateKeyUrls[i];
				if (i > 0) {
					ConsoleWriter.write(", ", ConsoleColor.WHITE);
				}
				ConsoleWriter.write(clientPrivateKeyUrl, ConsoleColor.DARK_BLUE, ConsoleDecoration.UNDERLINE);
			}
			ConsoleWriter.write("\n", ConsoleColor.WHITE);
		}
		if (!ArrayUtils.isEmpty(clientCertificateUrls)) {
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

	@Override
	public boolean isReady() {
		final String[] baseUrls = this.configuration.getBaseUrls(configuration.getExposedHost());
		if (this.reachableUrl == null) {
			for (String baseUrl : baseUrls) {
				if (NetworkUtils.isReachable(baseUrl)) {
					this.reachableUrl = baseUrl;
					return true;
				}
			}
			return false;
		} else {
			return NetworkUtils.isReachable(this.reachableUrl);
		}
	}
}
