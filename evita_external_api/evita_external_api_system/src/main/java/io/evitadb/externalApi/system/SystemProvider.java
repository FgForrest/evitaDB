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

package io.evitadb.externalApi.system;

import io.evitadb.externalApi.http.ExternalApiProviderWithConsoleOutput;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.system.configuration.SystemConfig;
import io.evitadb.utils.ConsoleWriter;
import io.evitadb.utils.ConsoleWriter.ConsoleColor;
import io.evitadb.utils.ConsoleWriter.ConsoleDecoration;
import io.evitadb.utils.StringUtils;
import io.undertow.server.HttpHandler;
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
	private final HttpHandler apiHandler;

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

	@Nonnull
	@Override
	public String getCode() {
		return CODE;
	}

	@Override
	public void writeToConsole() {
		for (String serverNameUrl : serverNameUrls) {
			ConsoleWriter.write(StringUtils.rightPad("   - server name served at: ", " ", ExternalApiServer.PADDING_START_UP));
			ConsoleWriter.write(serverNameUrl + "\n", ConsoleColor.DARK_BLUE, ConsoleDecoration.UNDERLINE);
		}
		for (String certificateUrl : rootCertificateUrls) {
			ConsoleWriter.write(StringUtils.rightPad("   - CA certificate served at: ", " ", ExternalApiServer.PADDING_START_UP));
			ConsoleWriter.write(certificateUrl + "\n", ConsoleColor.DARK_BLUE, ConsoleDecoration.UNDERLINE);
		}
		for (String certificateUrl : serverCertificateUrls) {
			ConsoleWriter.write(StringUtils.rightPad("   - server certificate served at: ", " ", ExternalApiServer.PADDING_START_UP));
			ConsoleWriter.write(certificateUrl + "\n", ConsoleColor.DARK_BLUE, ConsoleDecoration.UNDERLINE);
		}
		for (String certificateUrl : clientCertificateUrls) {
			ConsoleWriter.write(StringUtils.rightPad("   - client certificate served at: ", " ", ExternalApiServer.PADDING_START_UP));
			ConsoleWriter.write(certificateUrl + "\n", ConsoleColor.DARK_BLUE, ConsoleDecoration.UNDERLINE);
		}
		for (String clientPrivateKey : clientPrivateKeyUrls) {
			ConsoleWriter.write(StringUtils.rightPad("   - client private key served at: ", " ", ExternalApiServer.PADDING_START_UP));
			ConsoleWriter.write(clientPrivateKey + "\n", ConsoleColor.DARK_BLUE, ConsoleDecoration.UNDERLINE);
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
}
