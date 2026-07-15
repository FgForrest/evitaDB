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
 * @param clientId          The identification of the client used in logs and troubleshooting.
 *                          Defaults to `gRPC client at hostname`.
 * @param host              The IP address or host name where the gRPC server listens. Defaults to `localhost`.
 * @param port              The port the gRPC server listens on. Defaults to `5555`.
 * @param systemApiPort     The port the system API server listens on. Used for automatic certificate management.
 *                          Defaults to `5555`.
 * @param pingIntervalMillis The HTTP/2 keep-alive PING interval in milliseconds. When neither a read nor a write
 *                          happens on the connection for this long, a PING is sent; if the peer does not acknowledge
 *                          it within the same interval, the connection is closed. The interval therefore IS the stall
 *                          budget — it must exceed the worst tolerable GC / CPU-starvation pause, not act as a probe
 *                          frequency, otherwise a slow-but-alive call can be killed mid-flight. Defaults to
 *                          `30000` (30 s). `0` disables the client ping entirely (the connection is then reaped by the
 *                          idle timeout alone); any other value must be at least `1000` ms (the minimum Armeria
 *                          permits).
 *
 *                          **Precondition — the ping must stay strictly below {@link #idleTimeoutMillis()}.** Armeria
 *                          **silently disables** the ping (no error, no log) whenever `max(pingIntervalMillis, 1000)`
 *                          is greater than or equal to a positive connection idle timeout. The default pair
 *                          (`30000` ms ping, `300000` ms idle) satisfies this, so the watchdog is active out of the
 *                          box; `EvitaClient` logs a warning if a custom pair violates it. Also keep the interval below
 *                          any load-balancer / NAT idle window on the network path.
 * @param idleTimeoutMillis The connection idle timeout in milliseconds — how long a pooled HTTP/2 connection may sit
 *                          with no application traffic before Armeria closes it. This is deliberately **decoupled from
 *                          the per-call {@link ClientTimeoutOptions#timeout()}**: a short request deadline must not
 *                          force the physical connection to be torn down and re-established between calls. Defaults to
 *                          `300000` (300 s), comfortably above the `30000` ms ping so the keep-alive watchdog stays
 *                          active and healthy connections are kept warm — the client counts keep-alive pings as
 *                          activity (`keepAliveOnPing = true`), so a connection whose pings are acknowledged never
 *                          idles out. `0` disables the idle timeout entirely (the connection then lives until closed
 *                          by the peer, a ping failure or the pool). Must be `>= 0`; keep it strictly above
 *                          {@link #pingIntervalMillis()} to preserve the watchdog.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record ClientConnectionOptions(
	@Nonnull String clientId,
	@Nonnull String host,
	int port,
	int systemApiPort,
	int pingIntervalMillis,
	int idleTimeoutMillis
) {
	public static final String DEFAULT_HOST = "localhost";
	public static final int DEFAULT_PORT = 5555;
	public static final int DEFAULT_SYSTEM_API_PORT = 5555;
	public static final int DEFAULT_PING_INTERVAL_MILLIS = 30_000;
	public static final int DEFAULT_IDLE_TIMEOUT_MILLIS = 300_000;

	/**
	 * Creates a new instance with all default values.
	 */
	public ClientConnectionOptions() {
		this(
			resolveDefaultClientId(), DEFAULT_HOST, DEFAULT_PORT, DEFAULT_SYSTEM_API_PORT,
			DEFAULT_PING_INTERVAL_MILLIS, DEFAULT_IDLE_TIMEOUT_MILLIS
		);
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
		private int pingIntervalMillis = DEFAULT_PING_INTERVAL_MILLIS;
		private int idleTimeoutMillis = DEFAULT_IDLE_TIMEOUT_MILLIS;

		Builder() {
			this.clientId = resolveDefaultClientId();
		}

		Builder(@Nonnull ClientConnectionOptions connectionOptions) {
			this.clientId = connectionOptions.clientId();
			this.host = connectionOptions.host();
			this.port = connectionOptions.port();
			this.systemApiPort = connectionOptions.systemApiPort();
			this.pingIntervalMillis = connectionOptions.pingIntervalMillis();
			this.idleTimeoutMillis = connectionOptions.idleTimeoutMillis();
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

		/**
		 * Sets the HTTP/2 keep-alive PING interval in milliseconds. See
		 * {@link ClientConnectionOptions#pingIntervalMillis()} for the semantics and the accepted range
		 * (`0` to disable, otherwise `>= 1000`).
		 *
		 * @param pingIntervalMillis the keep-alive ping interval in milliseconds
		 * @return this builder for chaining
		 */
		@Nonnull
		public ClientConnectionOptions.Builder pingIntervalMillis(int pingIntervalMillis) {
			this.pingIntervalMillis = pingIntervalMillis;
			return this;
		}

		/**
		 * Sets the connection idle timeout in milliseconds. See
		 * {@link ClientConnectionOptions#idleTimeoutMillis()} for the semantics and the accepted range
		 * (`0` disables the idle timeout, otherwise `>= 0` and ideally above the ping interval).
		 *
		 * @param idleTimeoutMillis the connection idle timeout in milliseconds
		 * @return this builder for chaining
		 */
		@Nonnull
		public ClientConnectionOptions.Builder idleTimeoutMillis(int idleTimeoutMillis) {
			this.idleTimeoutMillis = idleTimeoutMillis;
			return this;
		}

		@Nonnull
		public ClientConnectionOptions build() {
			return new ClientConnectionOptions(
				this.clientId, this.host, this.port, this.systemApiPort,
				this.pingIntervalMillis, this.idleTimeoutMillis
			);
		}
	}
}
