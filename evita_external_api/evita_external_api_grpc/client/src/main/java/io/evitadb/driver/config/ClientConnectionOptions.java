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

import lombok.ToString;

import javax.annotation.Nonnull;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Record contains connection-related settings for the evitaDB Java client.
 *
 * @param clientId      The identification of the client used in logs and troubleshooting.
 *                      Defaults to `gRPC client at hostname`.
 * @param host          The IP address or host name where the gRPC server listens. Defaults to `localhost`.
 * @param port          The port the gRPC server listens on. Defaults to `5555`.
 * @param systemApiPort The port the system API server listens on. Used for automatic certificate management.
 *                      Defaults to `5555`.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record ClientConnectionOptions(
	@Nonnull String clientId,
	@Nonnull String host,
	int port,
	int systemApiPort
) {
	public static final String DEFAULT_HOST = "localhost";
	public static final int DEFAULT_PORT = 5555;
	public static final int DEFAULT_SYSTEM_API_PORT = 5555;

	/**
	 * Creates a new instance with all default values.
	 */
	public ClientConnectionOptions() {
		this(resolveDefaultClientId(), DEFAULT_HOST, DEFAULT_PORT, DEFAULT_SYSTEM_API_PORT);
	}

	/**
	 * Builder for the connection options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	@Nonnull
	public static ClientConnectionOptions.Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for the connection options initialized from an existing configuration.
	 */
	@Nonnull
	public static ClientConnectionOptions.Builder builder(@Nonnull ClientConnectionOptions connectionOptions) {
		return new Builder(connectionOptions);
	}

	/**
	 * Resolves the default client ID based on the local host name.
	 */
	@Nonnull
	static String resolveDefaultClientId() {
		try {
			final InetAddress inetAddress = InetAddress.getLocalHost();
			return "gRPC client at " + inetAddress.getHostName();
		} catch (UnknownHostException e) {
			return "Generic gRPC client";
		}
	}

	/**
	 * Standard builder pattern implementation.
	 */
	@ToString
	public static class Builder {
		private String clientId;
		private String host = DEFAULT_HOST;
		private int port = DEFAULT_PORT;
		private int systemApiPort = DEFAULT_SYSTEM_API_PORT;

		Builder() {
			this.clientId = resolveDefaultClientId();
		}

		Builder(@Nonnull ClientConnectionOptions connectionOptions) {
			this.clientId = connectionOptions.clientId();
			this.host = connectionOptions.host();
			this.port = connectionOptions.port();
			this.systemApiPort = connectionOptions.systemApiPort();
		}

		@Nonnull
		public ClientConnectionOptions.Builder clientId(@Nonnull String clientId) {
			this.clientId = clientId;
			return this;
		}

		@Nonnull
		public ClientConnectionOptions.Builder host(@Nonnull String host) {
			this.host = host;
			return this;
		}

		@Nonnull
		public ClientConnectionOptions.Builder port(int port) {
			this.port = port;
			return this;
		}

		@Nonnull
		public ClientConnectionOptions.Builder systemApiPort(int systemApiPort) {
			this.systemApiPort = systemApiPort;
			return this;
		}

		@Nonnull
		public ClientConnectionOptions build() {
			return new ClientConnectionOptions(this.clientId, this.host, this.port, this.systemApiPort);
		}
	}
}
