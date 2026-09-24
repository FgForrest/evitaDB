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

package io.evitadb.externalApi.grpc.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.evitadb.externalApi.configuration.AbstractApiOptions;
import io.evitadb.externalApi.configuration.MtlsConfiguration;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static java.util.Optional.ofNullable;

/**
 * gRPC API specific configuration.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Slf4j
public class GrpcOptions extends AbstractApiOptions {

	private static final String BASE_GRPC_PATH = "";
	/**
	 * Port on which will server be run and on which will channel be opened.
	 */
	public static final int DEFAULT_GRPC_PORT = 5555;
	/**
	 * Default value of {@link #getStreamingRequestTimeoutInMillis()}.
	 */
	public static final long DEFAULT_STREAMING_REQUEST_TIMEOUT_IN_MILLIS = 300_000L;
	/**
	 * Allows to expose the Armeria specific docs service on the gRPC API.
	 */
	@Getter private final boolean exposeDocsService;

	/**
	 * How long a **streaming** RPC may make no progress before the server abandons it, in milliseconds.
	 *
	 * `api.requestTimeoutInMillis` is a budget for a *whole request*. That is the right shape for a unary
	 * call, where the work is one bounded round trip, and the wrong shape for a server-streaming one: a
	 * download's duration is a function of the file's size and the link's speed, neither of which the
	 * server knows, so any whole-request budget silently becomes a cap on what can be transferred at all.
	 * This option is the streaming counterpart - it bounds *silence*. It is re-armed every time a message
	 * is handed to the transport, so a slow but steadily progressing transfer never reaches it however
	 * long it runs.
	 *
	 * The same value bounds how long {@link io.evitadb.externalApi.grpc.utils.GrpcOutboundGate} parks a
	 * producing worker waiting for a client that has stopped reading, so it is also the point at which
	 * such a stream is abandoned with `DEADLINE_EXCEEDED`. Size it against the slowest client to be
	 * served: it must comfortably exceed the time a **single message** takes to reach that client.
	 * Lowering it towards `requestTimeoutInMillis` reintroduces a minimum viable link speed - `fetchFile`
	 * streams 1 MB chunks, so a 2 s budget would demand roughly 4 Mbit/s sustained.
	 *
	 * This lives here rather than in `ApiOptions` because it is enforced by gRPC-specific machinery. The
	 * other APIs' long-lived endpoints (the REST and GraphQL WebSocket handlers) do not consume it.
	 */
	@Getter private final long streamingRequestTimeoutInMillis;

	/**
	 * Controls the prefix gRPC API will react on.
	 * Default value is empty string - gRPC currently doesn't support running on any prefix.
	 * This is unfortunately limitation of original implementation - see <a href="https://github.com/grpc/grpc-java/issues/9671">related issue</a>.
	 */
	@Getter private final String prefix;

	public GrpcOptions() {
		super(true, ":" + DEFAULT_GRPC_PORT);
		this.exposeDocsService = false;
		this.prefix = BASE_GRPC_PATH;
		this.streamingRequestTimeoutInMillis = DEFAULT_STREAMING_REQUEST_TIMEOUT_IN_MILLIS;
	}

	public GrpcOptions(@Nonnull String host) {
		super(true, host);
		this.exposeDocsService = false;
		this.prefix = BASE_GRPC_PATH;
		this.streamingRequestTimeoutInMillis = DEFAULT_STREAMING_REQUEST_TIMEOUT_IN_MILLIS;
	}

	@JsonCreator
	public GrpcOptions(@Nullable @JsonProperty("enabled") Boolean enabled,
	                   @Nonnull @JsonProperty("host") String host,
	                   @Nullable @JsonProperty("exposeOn") String exposeOn,
	                   @Nullable @JsonProperty("tlsMode") String tlsMode,
	                   @Nullable @JsonProperty("keepAlive") Boolean keepAlive,
	                   @Nullable @JsonProperty("exposeDocsService") Boolean exposeDocsService,
	                   @Nullable @JsonProperty("prefix") String prefix,
	                   @Nullable @JsonProperty("streamingRequestTimeoutInMillis") Long streamingRequestTimeoutInMillis,
	                   @Nullable @JsonProperty("mTLS") MtlsConfiguration mtlsConfiguration
	) {
		super(enabled, host, exposeOn, tlsMode, keepAlive, mtlsConfiguration);
		this.exposeDocsService = ofNullable(exposeDocsService).orElse(false);
		this.prefix = ofNullable(prefix).orElse(BASE_GRPC_PATH);
		// a non-positive value is meaningless for a stall budget and would disable the re-arm entirely,
		// so it falls back to the default rather than silently reinstating the whole-request budget
		this.streamingRequestTimeoutInMillis = ofNullable(streamingRequestTimeoutInMillis)
			.filter(it -> it > 0L)
			.orElse(DEFAULT_STREAMING_REQUEST_TIMEOUT_IN_MILLIS);
	}

	@Override
	public boolean isKeepAlive() {
		if (!super.isKeepAlive()) {
			log.warn(
				"Keep alive is disabled for gRPC API in the configuration settings. However, this setting results in " +
					"unpredictable behavior and should be enabled. The settings from the configuration are ignored."
			);
		}
		return true;
	}
}
