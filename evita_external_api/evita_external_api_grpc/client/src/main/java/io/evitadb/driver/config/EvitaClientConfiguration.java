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

package io.evitadb.driver.config;

import io.evitadb.dataType.data.ReflectionCachingBehaviour;
import io.evitadb.driver.EvitaClient;
import io.evitadb.externalApi.grpc.certificate.ClientCertificateManager;
import io.evitadb.utils.ReflectionLookup;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.concurrent.Flow;
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
 * @param tlsEnabled                Whether to use HTTP/2 without TLS encryption. Corresponding setting must be
 *                                  set on the server side.
 * @param mtlsEnabled               Whether to use mutual TLS encryption. Corresponding setting must be set on the
 *                                  server side.
 * @param serverCertificatePath     A relative path to the server certificate. Has to be provided when
 *                                  `useGeneratedCertificate` and `trustCertificate` flag is disabled and server
 *                                  is using non-trusted certificate (for example self-signed one).
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
 * @param trackedTaskLimit		    The maximum number of server tasks that can be tracked by the client.
 * @param retry                     Whether the client will retry the call in case of timeout or other network related problems.
 * @param changeCaptureQueueSize    The maximum number of change capture events that can be buffered for each subscriber.
 *                                  If this limit is reached, an error is reported to the subscriber.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record EvitaClientConfiguration(
	@Nonnull String clientId,
	@Nonnull String host,
	int port,
	int systemApiPort,
	boolean useGeneratedCertificate,
	boolean trustCertificate,
	boolean tlsEnabled,
	boolean mtlsEnabled,
	@Nullable Path serverCertificatePath,
	@Nullable Path certificateFileName,
	@Nullable Path certificateKeyFileName,
	@Nullable String certificateKeyPassword,
	@Nullable Path certificateFolderPath,
	@Nullable String trustStorePassword,
	@Nonnull ReflectionCachingBehaviour reflectionLookupBehaviour,
	long timeout,
	@Nonnull TimeUnit timeoutUnit,
	@Nullable Object openTelemetryInstance,
	boolean retry,
	int trackedTaskLimit,
	int changeCaptureQueueSize
) {
	private static final int DEFAULT_PORT = 5555;

	/**
	 * Builder for the cache options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	public static EvitaClientConfiguration.Builder builder() {
		return new EvitaClientConfiguration.Builder();
	}

	/**
	 * Returns the path to the server certificate.
	 *
	 * @return The path to the server certificate.
	 * @deprecated Use {@link #serverCertificatePath()} instead.
	 */
	@Deprecated(since = "2024.11", forRemoval = true)
	@Nullable
	public Path rootCaCertificatePath() {
		return this.serverCertificatePath;
	}

	/**
	 * Standard builder pattern implementation.
	 */
	@ToString
	public static class Builder {
		private String clientId;
		private String host = "localhost";
		private int port = DEFAULT_PORT;
		private int systemApiPort = DEFAULT_PORT;
		private boolean useGeneratedCertificate = true;
		private boolean trustCertificate = false;
		private boolean tlsEnabled = true;
		private boolean mtlsEnabled = false;
		private Path serverCertificatePath = null;
		private Path certificatePath = null;
		private Path certificateKeyPath = null;
		private String certificateKeyPassword = null;
		private long timeout = 5;
		private TimeUnit timeoutUnit = TimeUnit.SECONDS;
		private Path certificateFolderPath = ClientCertificateManager.getDefaultClientCertificateFolderPath();
		private String trustStorePassword = "trustStorePassword";
		private ReflectionCachingBehaviour reflectionCachingBehaviour = ReflectionCachingBehaviour.CACHE;
		@Nullable private Object openTelemetryInstance = null;
		private int trackedTaskLimit = 100;
		private boolean retry = false;
		private int changeCaptureQueueSize = Flow.defaultBufferSize();

		Builder() {
			try {
				final InetAddress inetAddress = InetAddress.getLocalHost();
				this.clientId = "gRPC client at " + inetAddress.getHostName();
			} catch (UnknownHostException e) {
				this.clientId = "Generic gRPC client";
			}
		}

		@Nonnull
		public EvitaClientConfiguration.Builder clientId(@Nonnull String clientId) {
			this.clientId = clientId;
			return this;
		}

		@Nonnull
		public EvitaClientConfiguration.Builder host(@Nonnull String host) {
			this.host = host;
			return this;
		}

		@Nonnull
		public EvitaClientConfiguration.Builder port(int port) {
			this.port = port;
			return this;
		}

		@Nonnull
		public EvitaClientConfiguration.Builder systemApiPort(int systemApiPort) {
			this.systemApiPort = systemApiPort;
			return this;
		}

		@Nonnull
		public EvitaClientConfiguration.Builder useGeneratedCertificate(boolean useGeneratedCertificate) {
			this.useGeneratedCertificate = useGeneratedCertificate;
			return this;
		}

		@Nonnull
		public EvitaClientConfiguration.Builder trustCertificate(boolean trustCertificate) {
			this.trustCertificate = trustCertificate;
			return this;
		}

		/**
		 * This setting was renamed to {@link #serverCertificatePath(Path)}.
		 *
		 * @deprecated Use {@link #serverCertificatePath(Path)} instead.
		 * @param rootCaCertificatePath Path to the server certificate that should be used for TLS connection.
		 * @return Builder instance for chaining.
		 */
		@Deprecated(since = "2024.11", forRemoval = true)
		@Nonnull
		public EvitaClientConfiguration.Builder rootCaCertificatePath(@Nonnull Path rootCaCertificatePath) {
			this.serverCertificatePath = rootCaCertificatePath;
			return this;
		}

		@Nonnull
		public EvitaClientConfiguration.Builder serverCertificatePath(@Nonnull Path rootCaCertificatePath) {
			this.serverCertificatePath = rootCaCertificatePath;
			return this;
		}

		@Nonnull
		public EvitaClientConfiguration.Builder reflectionCachingBehaviour(@Nonnull ReflectionCachingBehaviour reflectionCachingBehaviour) {
			this.reflectionCachingBehaviour = reflectionCachingBehaviour;
			return this;
		}

		@Nonnull
		public EvitaClientConfiguration.Builder certificateFolderPath(@Nonnull Path certificateFolderPath) {
			this.certificateFolderPath = certificateFolderPath;
			return this;
		}

		/**
		 * Renamed to {@link #timeout(long, TimeUnit)}
		 *
		 * @deprecated Use {@link #timeout(long, TimeUnit)} instead.
		 */
		@Deprecated(since = "2024.11", forRemoval = true)
		@Nonnull
		public EvitaClientConfiguration.Builder timeoutUnit(long timeout, @Nonnull TimeUnit unit) {
			return timeout(timeout, unit);
		}

		@Nonnull
		public EvitaClientConfiguration.Builder timeout(long timeout, @Nonnull TimeUnit unit) {
			this.timeout = timeout;
			this.timeoutUnit = unit;
			return this;
		}

		@Nonnull
		public EvitaClientConfiguration.Builder tlsEnabled(boolean tlsEnabled) {
			this.tlsEnabled = tlsEnabled;
			return this;
		}

		@Nonnull
		public EvitaClientConfiguration.Builder mtlsEnabled(boolean mtlsEnabled) {
			this.mtlsEnabled = mtlsEnabled;
			return this;
		}

		@Nonnull
		public EvitaClientConfiguration.Builder certificateFileName(@Nonnull Path certificateFileName) {
			this.certificatePath = certificateFileName;
			return this;
		}

		@Nonnull
		public EvitaClientConfiguration.Builder certificateKeyFileName(@Nonnull Path certificateKeyFileName) {
			this.certificateKeyPath = certificateKeyFileName;
			return this;
		}

		@Nonnull
		public EvitaClientConfiguration.Builder certificateKeyPassword(@Nonnull String certificateKeyPassword) {
			this.certificateKeyPassword = certificateKeyPassword;
			return this;
		}

		@Nonnull
		public EvitaClientConfiguration.Builder trustStorePassword(@Nonnull String trustStorePassword) {
			this.trustStorePassword = trustStorePassword;
			return this;
		}

		@Nonnull
		public EvitaClientConfiguration.Builder openTelemetryInstance(@Nullable Object openTelemetryInstance) {
			this.openTelemetryInstance = openTelemetryInstance;
			return this;
		}

 	@Nonnull
 	public EvitaClientConfiguration.Builder trackedTaskLimit(int trackedTaskLimit) {
 		this.trackedTaskLimit = trackedTaskLimit;
 		return this;
 	}

 	@Nonnull
 	public EvitaClientConfiguration.Builder changeCaptureQueueSize(int changeCaptureQueueSize) {
 		this.changeCaptureQueueSize = changeCaptureQueueSize;
 		return this;
 	}

		@Nonnull
		public EvitaClientConfiguration.Builder retry(boolean retry) {
			this.retry = retry;
			return this;
		}

		public EvitaClientConfiguration build() {
			return new EvitaClientConfiguration(
				this.clientId,
				this.host,
				this.port,
				this.systemApiPort,
				this.useGeneratedCertificate,
				this.trustCertificate,
				this.tlsEnabled,
				this.mtlsEnabled,
				this.serverCertificatePath,
				this.certificatePath,
				this.certificateKeyPath,
				this.certificateKeyPassword,
				this.certificateFolderPath,
				this.trustStorePassword,
				this.reflectionCachingBehaviour,
				this.timeout,
				this.timeoutUnit,
				this.openTelemetryInstance,
				this.retry,
				this.trackedTaskLimit,
				this.changeCaptureQueueSize
			);
		}

	}

}
