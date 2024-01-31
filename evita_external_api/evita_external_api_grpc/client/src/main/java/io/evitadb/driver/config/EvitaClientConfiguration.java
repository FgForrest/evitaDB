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

package io.evitadb.driver.config;

import io.evitadb.dataType.data.ReflectionCachingBehaviour;
import io.evitadb.driver.EvitaClient;
import io.evitadb.driver.certificate.ClientCertificateManager;
import io.evitadb.utils.ReflectionLookup;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * This is a configuration class for the {@link EvitaClient}. It allows to configure the basic
 * setting necessary for client to run.
 *
 * @param clientId                  The identification of the client.
 * @param host                      The IP address or host name where the gRPC server listens.
 * @param port                      The port the gRPC server listens on.
 * @param systemApiPort             The port the system API server listens on.
 * @param useGeneratedCertificate   Whether to use generated certificate by the server for the connection or not.
 * @param trustCertificate          Whether to trust the server CA certificate or not when it's not trusted CA.
 * @param rootCaCertificatePath     A relative path to the root CA certificate. Has to be provided when
 *                                  `useGeneratedCertificate` and `trustCertificate` flag is disabled and server
 *                                  is using non-trusted CA certificate.
 * @param certificateFolderPath     A relative path to the folder where the client certificate and private key will be located,
 *                                  or if already not present there, downloaded. In the latter, the default path in the
 *                                  `temp` folder will be used.
 * @param reflectionLookupBehaviour The behaviour of {@link ReflectionLookup} class analyzing classes
 *                                  for reflective information. Controls whether the once analyzed reflection
 *                                  information should be cached or freshly (and costly) retrieved each time asked.
 * @param timeout                   Number of {@link EvitaClientConfiguration#timeoutUnit time units} client should
 *                                  wait for server to respond before throwing an exception or closing connection
 *                                  forcefully.
 * @param timeoutUnit               Time unit for {@link EvitaClientConfiguration#timeout property}.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record EvitaClientConfiguration(
	@Nonnull String clientId,
	@Nonnull String host,
	int port,
	int systemApiPort,
	boolean useGeneratedCertificate,
	boolean trustCertificate,
	boolean mtlsEnabled,
	@Nullable Path rootCaCertificatePath,
	@Nullable Path certificateFileName,
	@Nullable Path certificateKeyFileName,
	@Nullable String certificateKeyPassword,
	@Nullable Path certificateFolderPath,
	@Nullable String trustStorePassword,
	@Nonnull ReflectionCachingBehaviour reflectionLookupBehaviour,
	long timeout,
	@Nonnull TimeUnit timeoutUnit
) {

	private static final int DEFAULT_GRPC_API_PORT = 5555;
	private static final int DEFAULT_SYSTEM_API_PORT = 5557;

	/**
	 * Builder for the cache options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	public static EvitaClientConfiguration.Builder builder() {
		return new EvitaClientConfiguration.Builder();
	}

	/**
	 * Standard builder pattern implementation.
	 */
	@ToString
	public static class Builder {
		private String clientId;
		private String host = "localhost";
		private int port = DEFAULT_GRPC_API_PORT;
		private int systemApiPort = DEFAULT_SYSTEM_API_PORT;
		private boolean useGeneratedCertificate = true;
		private boolean trustCertificate = false;
		private boolean mtlsEnabled = false;
		private Path rootCaCertificatePath = null;
		private Path certificatePath = null;
		private Path certificateKeyPath = null;
		private String certificateKeyPassword = null;
		private long timeout = 5;
		private TimeUnit timeoutUnit = TimeUnit.SECONDS;
		private Path certificateFolderPath = ClientCertificateManager.getDefaultClientCertificateFolderPath();
		private String trustStorePassword = "trustStorePassword";
		private ReflectionCachingBehaviour reflectionCachingBehaviour = ReflectionCachingBehaviour.CACHE;

		Builder() {
			try {
				final InetAddress inetAddress = InetAddress.getLocalHost();
				clientId = "gRPC client at " + inetAddress.getHostName();
			} catch (UnknownHostException e) {
				clientId = "Generic gRPC client";
			}
		}

		public EvitaClientConfiguration.Builder clientId(@Nonnull String clientId) {
			this.clientId = clientId;
			return this;
		}

		public EvitaClientConfiguration.Builder host(@Nonnull String host) {
			this.host = host;
			return this;
		}

		public EvitaClientConfiguration.Builder port(int port) {
			this.port = port;
			return this;
		}

		public EvitaClientConfiguration.Builder systemApiPort(int systemApiPort) {
			this.systemApiPort = systemApiPort;
			return this;
		}

		public EvitaClientConfiguration.Builder useGeneratedCertificate(boolean useGeneratedCertificate) {
			this.useGeneratedCertificate = useGeneratedCertificate;
			return this;
		}

		public EvitaClientConfiguration.Builder trustCertificate(boolean trustCertificate) {
			this.trustCertificate = trustCertificate;
			return this;
		}

		public EvitaClientConfiguration.Builder rootCaCertificatePath(@Nonnull Path rootCaCertificatePath) {
			this.rootCaCertificatePath = rootCaCertificatePath;
			return this;
		}

		public EvitaClientConfiguration.Builder reflectionCachingBehaviour(@Nonnull ReflectionCachingBehaviour reflectionCachingBehaviour) {
			this.reflectionCachingBehaviour = reflectionCachingBehaviour;
			return this;
		}

		public EvitaClientConfiguration.Builder certificateFolderPath(@Nonnull Path certificateFolderPath) {
			this.certificateFolderPath = certificateFolderPath;
			return this;
		}

		public EvitaClientConfiguration.Builder timeoutUnit(long timeout, @Nonnull TimeUnit unit) {
			this.timeout = timeout;
			this.timeoutUnit = unit;
			return this;
		}

		public EvitaClientConfiguration.Builder mtlsEnabled(boolean mtlsEnabled) {
			this.mtlsEnabled = mtlsEnabled;
			return this;
		}

		public EvitaClientConfiguration.Builder certificateFileName(@Nonnull Path certificateFileName) {
			this.certificatePath = certificateFileName;
			return this;
		}

		public EvitaClientConfiguration.Builder certificateKeyFileName(@Nonnull Path certificateKeyFileName) {
			this.certificateKeyPath = certificateKeyFileName;
			return this;
		}

		public EvitaClientConfiguration.Builder certificateKeyPassword(@Nonnull String certificateKeyPassword) {
			this.certificateKeyPassword = certificateKeyPassword;
			return this;
		}

		public EvitaClientConfiguration.Builder trustStorePassword(@Nonnull String trustStorePassword) {
			this.trustStorePassword = trustStorePassword;
			return this;
		}

		public EvitaClientConfiguration build() {
			return new EvitaClientConfiguration(
				clientId,
				host,
				port,
				systemApiPort,
				useGeneratedCertificate,
				trustCertificate,
				mtlsEnabled,
				rootCaCertificatePath,
				certificatePath,
				certificateKeyPath,
				certificateKeyPassword,
				certificateFolderPath,
				trustStorePassword,
				reflectionCachingBehaviour,
				timeout,
				TimeUnit.SECONDS
			);
		}

	}

}
