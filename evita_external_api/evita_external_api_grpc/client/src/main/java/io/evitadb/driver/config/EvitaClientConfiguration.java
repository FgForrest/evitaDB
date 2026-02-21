/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.dataType.data.ReflectionCachingBehaviour;
import io.evitadb.driver.EvitaClient;
import io.evitadb.utils.ReflectionLookup;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/**
 * This is a configuration class for the {@link EvitaClient}. It allows you to configure the basic settings necessary
 * for client to run.
 *
 * Basic connection settings ({@link #host()}, {@link #port()}, {@link #systemApiPort()}, {@link #clientId()}) are
 * available directly on the configuration and its builder for convenience. Advanced settings are organized into
 * logical groups:
 *
 * - {@link ClientConnectionOptions} for all connection settings (also accessible via the grouped
 *   {@link #connection()} accessor)
 * - {@link ClientTlsOptions} for TLS and certificate settings
 * - {@link ClientTimeoutOptions} for timeout settings
 * - {@link ThreadPoolOptions} for thread pool settings
 *
 * @param connection                Connection-related settings (host, port, clientId, systemApiPort).
 * @param tls                       TLS and certificate-related settings.
 * @param timeouts                  Timeout-related settings for regular and streaming calls.
 * @param threadPool                Defines limits for the client-side thread pool used for asynchronous operations
 *                                  such as session handling and background tasks.
 * @param reflectionLookupBehaviour The behaviour of {@link ReflectionLookup} class analyzing classes for reflective
 *                                  information. Controls whether the once analyzed reflection information should be
 *                                  cached or freshly (and costly) retrieved each time asked.
 * @param openTelemetryInstance     OpenTelemetry instance used for tracing. If `null`, no tracing will be performed.
 * @param retry                     Whether the client will retry the call in case of timeout or other network related
 *                                  problems.
 * @param trackedTaskLimit          The maximum number of server tasks that can be tracked by the client.
 * @param changeCaptureQueueSize    The maximum number of change capture events that can be buffered for each
 *                                  subscriber. If this limit is reached, an error is reported to the subscriber.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record EvitaClientConfiguration(
	@Nonnull ClientConnectionOptions connection,
	@Nonnull ClientTlsOptions tls,
	@Nonnull ClientTimeoutOptions timeouts,
	@Nonnull ThreadPoolOptions threadPool,
	@Nonnull ReflectionCachingBehaviour reflectionLookupBehaviour,
	@Nullable Object openTelemetryInstance,
	boolean retry,
	int trackedTaskLimit,
	int changeCaptureQueueSize
) {

	/**
	 * Builder for the client configuration. Recommended to use to avoid binary compatibility problems in the future.
	 */
	@Nonnull
	public static EvitaClientConfiguration.Builder builder() {
		return new EvitaClientConfiguration.Builder();
	}

	/**
	 * Builder for the client configuration initialized from an existing configuration.
	 */
	@Nonnull
	public static EvitaClientConfiguration.Builder builder(@Nonnull EvitaClientConfiguration configuration) {
		return new EvitaClientConfiguration.Builder(configuration);
	}

	// ============================================================================================
	// Top-level connection accessor methods (delegates to connection group)
	// ============================================================================================

	/**
	 * Returns the identification of the client instance. This value is used to distinguish requests from this client
	 * in server logs and troubleshooting tools.
	 *
	 * Delegates to {@link ClientConnectionOptions#clientId()}.
	 *
	 * @return the client identification string
	 */
	@Nonnull
	public String clientId() {
		return this.connection.clientId();
	}

	/**
	 * Returns the IP address or host name where the gRPC server listens.
	 *
	 * Delegates to {@link ClientConnectionOptions#host()}.
	 *
	 * @return the server host
	 */
	@Nonnull
	public String host() {
		return this.connection.host();
	}

	/**
	 * Returns the port the gRPC server listens on.
	 *
	 * Delegates to {@link ClientConnectionOptions#port()}.
	 *
	 * @return the server port
	 */
	public int port() {
		return this.connection.port();
	}

	/**
	 * Returns the port the system API server listens on.
	 *
	 * Delegates to {@link ClientConnectionOptions#systemApiPort()}.
	 *
	 * @return the system API port
	 */
	public int systemApiPort() {
		return this.connection.systemApiPort();
	}

	// ============================================================================================
	// Deprecated delegate accessor methods for backward compatibility
	// ============================================================================================

	/**
	 * @return Whether to use TLS encryption.
	 * @deprecated Use {@link #tls()}.{@link ClientTlsOptions#tlsEnabled() tlsEnabled()} instead.
	 */
	@Deprecated(since = "2026.3", forRemoval = true)
	public boolean tlsEnabled() {
		return this.tls.tlsEnabled();
	}

	/**
	 * @return Whether to use mutual TLS authentication.
	 * @deprecated Use {@link #tls()}.{@link ClientTlsOptions#mtlsEnabled() mtlsEnabled()} instead.
	 */
	@Deprecated(since = "2026.3", forRemoval = true)
	public boolean mtlsEnabled() {
		return this.tls.mtlsEnabled();
	}

	/**
	 * @return Whether to use generated certificate.
	 * @deprecated Use {@link #tls()} and {@link ClientTlsOptions#useGeneratedCertificate()} instead.
	 */
	@Deprecated(since = "2026.3", forRemoval = true)
	public boolean useGeneratedCertificate() {
		return this.tls.useGeneratedCertificate();
	}

	/**
	 * @return Whether to trust the server certificate.
	 * @deprecated Use {@link #tls()} and {@link ClientTlsOptions#trustCertificate()} instead.
	 */
	@Deprecated(since = "2026.3", forRemoval = true)
	public boolean trustCertificate() {
		return this.tls.trustCertificate();
	}

	/**
	 * @return Path to the server certificate.
	 * @deprecated Use {@link #tls()} and {@link ClientTlsOptions#serverCertificatePath()} instead.
	 */
	@Deprecated(since = "2026.3", forRemoval = true)
	@Nullable
	public Path serverCertificatePath() {
		return this.tls.serverCertificatePath();
	}

	/**
	 * @return Path to the server certificate.
	 * @deprecated Use {@link #tls()} and {@link ClientTlsOptions#serverCertificatePath()} instead.
	 */
	@Deprecated(since = "2024.11", forRemoval = true)
	@Nullable
	public Path rootCaCertificatePath() {
		return this.tls.serverCertificatePath();
	}

	/**
	 * @return Path to the client certificate file.
	 * @deprecated Use {@link #tls()} and {@link ClientTlsOptions#certificateFileName()} instead.
	 */
	@Deprecated(since = "2026.3", forRemoval = true)
	@Nullable
	public Path certificateFileName() {
		return this.tls.certificateFileName();
	}

	/**
	 * @return Path to the client private key file.
	 * @deprecated Use {@link #tls()} and {@link ClientTlsOptions#certificateKeyFileName()} instead.
	 */
	@Deprecated(since = "2026.3", forRemoval = true)
	@Nullable
	public Path certificateKeyFileName() {
		return this.tls.certificateKeyFileName();
	}

	/**
	 * @return Password for the client's private key.
	 * @deprecated Use {@link #tls()} and {@link ClientTlsOptions#certificateKeyPassword()} instead.
	 */
	@Deprecated(since = "2026.3", forRemoval = true)
	@Nullable
	public String certificateKeyPassword() {
		return this.tls.certificateKeyPassword();
	}

	/**
	 * @return Path to the certificate folder.
	 * @deprecated Use {@link #tls()} and {@link ClientTlsOptions#certificateFolderPath()} instead.
	 */
	@Deprecated(since = "2026.3", forRemoval = true)
	@Nullable
	public Path certificateFolderPath() {
		return this.tls.certificateFolderPath();
	}

	/**
	 * @return Password for the trust store.
	 * @deprecated Use {@link #tls()} and {@link ClientTlsOptions#trustStorePassword()} instead.
	 */
	@Deprecated(since = "2026.3", forRemoval = true)
	@Nonnull
	public String trustStorePassword() {
		return this.tls.trustStorePassword();
	}

	/**
	 * @return Number of time units for regular call timeout.
	 * @deprecated Use {@link #timeouts()}.{@link ClientTimeoutOptions#timeout() timeout()} instead.
	 */
	@Deprecated(since = "2026.3", forRemoval = true)
	public long timeout() {
		return this.timeouts.timeout();
	}

	/**
	 * @return Time unit for the regular call timeout.
	 * @deprecated Use {@link #timeouts()} and {@link ClientTimeoutOptions#timeoutUnit()} instead.
	 */
	@Deprecated(since = "2026.3", forRemoval = true)
	@Nonnull
	public TimeUnit timeoutUnit() {
		return this.timeouts.timeoutUnit();
	}

	/**
	 * @return Number of time units for streaming call timeout.
	 * @deprecated Use {@link #timeouts()} and {@link ClientTimeoutOptions#streamingTimeout()} instead.
	 */
	@Deprecated(since = "2026.3", forRemoval = true)
	public long streamingTimeout() {
		return this.timeouts.streamingTimeout();
	}

	/**
	 * @return Time unit for the streaming call timeout.
	 * @deprecated Use {@link #timeouts()} and {@link ClientTimeoutOptions#streamingTimeoutUnit()} instead.
	 */
	@Deprecated(since = "2026.3", forRemoval = true)
	@Nonnull
	public TimeUnit streamingTimeoutUnit() {
		return this.timeouts.streamingTimeoutUnit();
	}

	/**
	 * Standard builder pattern implementation.
	 *
	 * Connection settings ({@link #host(String)}, {@link #port(int)}, {@link #systemApiPort(int)},
	 * {@link #clientId(String)}) can be set directly on the builder or via the grouped
	 * {@link #connection(ClientConnectionOptions)} method. TLS and timeout settings should use the grouped setters
	 * {@link #tls(ClientTlsOptions)} and {@link #timeouts(ClientTimeoutOptions)}.
	 */
	@ToString
	public static class Builder {
		@Nonnull private ClientConnectionOptions.Builder connectionBuilder = ClientConnectionOptions.builder();
		@Nonnull private ClientTlsOptions.Builder tlsBuilder = ClientTlsOptions.builder();
		@Nonnull private ClientTimeoutOptions.Builder timeoutsBuilder = ClientTimeoutOptions.builder();
		@Nonnull private ReflectionCachingBehaviour reflectionCachingBehaviour = ReflectionCachingBehaviour.CACHE;
		@Nullable private Object openTelemetryInstance = null;
		private int trackedTaskLimit = 100;
		private boolean retry = false;
		private int changeCaptureQueueSize = Flow.defaultBufferSize();
		@Nonnull private ThreadPoolOptions threadPool = ThreadPoolOptions.clientThreadPoolBuilder().build();

		Builder() {
		}

		Builder(@Nonnull EvitaClientConfiguration configuration) {
			this.connectionBuilder = ClientConnectionOptions.builder(configuration.connection());
			this.tlsBuilder = ClientTlsOptions.builder(configuration.tls());
			this.timeoutsBuilder = ClientTimeoutOptions.builder(configuration.timeouts());
			this.threadPool = configuration.threadPool();
			this.reflectionCachingBehaviour = configuration.reflectionLookupBehaviour();
			this.openTelemetryInstance = configuration.openTelemetryInstance();
			this.trackedTaskLimit = configuration.trackedTaskLimit();
			this.retry = configuration.retry();
			this.changeCaptureQueueSize = configuration.changeCaptureQueueSize();
		}

		// ========================================================================================
		// New grouped setter methods
		// ========================================================================================

		/**
		 * Sets connection options as a pre-built group.
		 */
		@Nonnull
		public EvitaClientConfiguration.Builder connection(@Nonnull ClientConnectionOptions connection) {
			this.connectionBuilder = ClientConnectionOptions.builder(connection);
			return this;
		}

		/**
		 * Sets TLS options as a pre-built group.
		 */
		@Nonnull
		public EvitaClientConfiguration.Builder tls(@Nonnull ClientTlsOptions tls) {
			this.tlsBuilder = ClientTlsOptions.builder(tls);
			return this;
		}

		/**
		 * Sets timeout options as a pre-built group.
		 */
		@Nonnull
		public EvitaClientConfiguration.Builder timeouts(@Nonnull ClientTimeoutOptions timeouts) {
			this.timeoutsBuilder = ClientTimeoutOptions.builder(timeouts);
			return this;
		}

		// ========================================================================================
		// Top-level connection setter methods
		// ========================================================================================

		/**
		 * Sets the identification of the client instance.
		 *
		 * Delegates to {@link ClientConnectionOptions.Builder#clientId(String)}.
		 *
		 * @param clientId the client identification string
		 * @return this builder for chaining
		 */
		@Nonnull
		public EvitaClientConfiguration.Builder clientId(@Nonnull String clientId) {
			this.connectionBuilder.clientId(clientId);
			return this;
		}

		/**
		 * Sets the IP address or host name where the gRPC server listens.
		 *
		 * Delegates to {@link ClientConnectionOptions.Builder#host(String)}.
		 *
		 * @param host the server host
		 * @return this builder for chaining
		 */
		@Nonnull
		public EvitaClientConfiguration.Builder host(@Nonnull String host) {
			this.connectionBuilder.host(host);
			return this;
		}

		/**
		 * Sets the port the gRPC server listens on.
		 *
		 * Delegates to {@link ClientConnectionOptions.Builder#port(int)}.
		 *
		 * @param port the server port
		 * @return this builder for chaining
		 */
		@Nonnull
		public EvitaClientConfiguration.Builder port(int port) {
			this.connectionBuilder.port(port);
			return this;
		}

		/**
		 * Sets the port the system API server listens on.
		 *
		 * Delegates to {@link ClientConnectionOptions.Builder#systemApiPort(int)}.
		 *
		 * @param systemApiPort the system API port
		 * @return this builder for chaining
		 */
		@Nonnull
		public EvitaClientConfiguration.Builder systemApiPort(int systemApiPort) {
			this.connectionBuilder.systemApiPort(systemApiPort);
			return this;
		}

		// ========================================================================================
		// Deprecated flat setter methods for backward compatibility
		// ========================================================================================

		/**
		 * @deprecated Use {@link #tls(ClientTlsOptions)} instead.
		 */
		@Deprecated(since = "2026.3", forRemoval = true)
		@Nonnull
		public EvitaClientConfiguration.Builder useGeneratedCertificate(boolean useGeneratedCertificate) {
			this.tlsBuilder.useGeneratedCertificate(useGeneratedCertificate);
			return this;
		}

		/**
		 * @deprecated Use {@link #tls(ClientTlsOptions)} instead.
		 */
		@Deprecated(since = "2026.3", forRemoval = true)
		@Nonnull
		public EvitaClientConfiguration.Builder trustCertificate(boolean trustCertificate) {
			this.tlsBuilder.trustCertificate(trustCertificate);
			return this;
		}

		/**
		 * This setting was renamed to {@link #serverCertificatePath(Path)}.
		 *
		 * @deprecated Use {@link #tls(ClientTlsOptions)} instead.
		 * @param rootCaCertificatePath Path to the server certificate.
		 * @return Builder instance for chaining.
		 */
		@Deprecated(since = "2024.11", forRemoval = true)
		@Nonnull
		public EvitaClientConfiguration.Builder rootCaCertificatePath(@Nonnull Path rootCaCertificatePath) {
			this.tlsBuilder.serverCertificatePath(rootCaCertificatePath);
			return this;
		}

		/**
		 * @deprecated Use {@link #tls(ClientTlsOptions)} instead.
		 */
		@Deprecated(since = "2026.3", forRemoval = true)
		@Nonnull
		public EvitaClientConfiguration.Builder serverCertificatePath(@Nonnull Path serverCertificatePath) {
			this.tlsBuilder.serverCertificatePath(serverCertificatePath);
			return this;
		}

		/**
		 * @deprecated Use {@link #tls(ClientTlsOptions)} instead.
		 */
		@Deprecated(since = "2026.3", forRemoval = true)
		@Nonnull
		public EvitaClientConfiguration.Builder tlsEnabled(boolean tlsEnabled) {
			this.tlsBuilder.tlsEnabled(tlsEnabled);
			return this;
		}

		/**
		 * @deprecated Use {@link #tls(ClientTlsOptions)} instead.
		 */
		@Deprecated(since = "2026.3", forRemoval = true)
		@Nonnull
		public EvitaClientConfiguration.Builder mtlsEnabled(boolean mtlsEnabled) {
			this.tlsBuilder.mtlsEnabled(mtlsEnabled);
			return this;
		}

		/**
		 * @deprecated Use {@link #tls(ClientTlsOptions)} instead.
		 */
		@Deprecated(since = "2026.3", forRemoval = true)
		@Nonnull
		public EvitaClientConfiguration.Builder certificateFileName(@Nonnull Path certificateFileName) {
			this.tlsBuilder.certificateFileName(certificateFileName);
			return this;
		}

		/**
		 * @deprecated Use {@link #tls(ClientTlsOptions)} instead.
		 */
		@Deprecated(since = "2026.3", forRemoval = true)
		@Nonnull
		public EvitaClientConfiguration.Builder certificateKeyFileName(@Nonnull Path certificateKeyFileName) {
			this.tlsBuilder.certificateKeyFileName(certificateKeyFileName);
			return this;
		}

		/**
		 * @deprecated Use {@link #tls(ClientTlsOptions)} instead.
		 */
		@Deprecated(since = "2026.3", forRemoval = true)
		@Nonnull
		public EvitaClientConfiguration.Builder certificateKeyPassword(@Nonnull String certificateKeyPassword) {
			this.tlsBuilder.certificateKeyPassword(certificateKeyPassword);
			return this;
		}

		/**
		 * @deprecated Use {@link #tls(ClientTlsOptions)} instead.
		 */
		@Deprecated(since = "2026.3", forRemoval = true)
		@Nonnull
		public EvitaClientConfiguration.Builder certificateFolderPath(@Nonnull Path certificateFolderPath) {
			this.tlsBuilder.certificateFolderPath(certificateFolderPath);
			return this;
		}

		/**
		 * @deprecated Use {@link #tls(ClientTlsOptions)} instead.
		 */
		@Deprecated(since = "2026.3", forRemoval = true)
		@Nonnull
		public EvitaClientConfiguration.Builder trustStorePassword(@Nonnull String trustStorePassword) {
			this.tlsBuilder.trustStorePassword(trustStorePassword);
			return this;
		}

		/**
		 * Sets the reflection caching behaviour for the client.
		 */
		@Nonnull
		public EvitaClientConfiguration.Builder reflectionCachingBehaviour(
			@Nonnull ReflectionCachingBehaviour reflectionCachingBehaviour
		) {
			this.reflectionCachingBehaviour = reflectionCachingBehaviour;
			return this;
		}

		/**
		 * Renamed to {@link #timeout(long, TimeUnit)}.
		 *
		 * @deprecated Use {@link #timeouts(ClientTimeoutOptions)} instead.
		 */
		@Deprecated(since = "2024.11", forRemoval = true)
		@Nonnull
		public EvitaClientConfiguration.Builder timeoutUnit(long timeout, @Nonnull TimeUnit unit) {
			return timeout(timeout, unit);
		}

		/**
		 * @deprecated Use {@link #timeouts(ClientTimeoutOptions)} instead.
		 */
		@Deprecated(since = "2026.3", forRemoval = true)
		@Nonnull
		public EvitaClientConfiguration.Builder timeout(long timeout, @Nonnull TimeUnit unit) {
			this.timeoutsBuilder.timeout(timeout, unit);
			return this;
		}

		/**
		 * @deprecated Use {@link #timeouts(ClientTimeoutOptions)} instead.
		 */
		@Deprecated(since = "2026.3", forRemoval = true)
		@Nonnull
		public EvitaClientConfiguration.Builder streamingTimeout(long streamingTimeout, @Nonnull TimeUnit unit) {
			this.timeoutsBuilder.streamingTimeout(streamingTimeout, unit);
			return this;
		}

		// ========================================================================================
		// Top-level setter methods (not deprecated)
		// ========================================================================================

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
		public EvitaClientConfiguration.Builder threadPool(@Nonnull ThreadPoolOptions threadPool) {
			this.threadPool = threadPool;
			return this;
		}

		@Nonnull
		public EvitaClientConfiguration.Builder retry(boolean retry) {
			this.retry = retry;
			return this;
		}

		@Nonnull
		public EvitaClientConfiguration build() {
			return new EvitaClientConfiguration(
				this.connectionBuilder.build(),
				this.tlsBuilder.build(),
				this.timeoutsBuilder.build(),
				this.threadPool,
				this.reflectionCachingBehaviour,
				this.openTelemetryInstance,
				this.retry,
				this.trackedTaskLimit,
				this.changeCaptureQueueSize
			);
		}
	}
}
