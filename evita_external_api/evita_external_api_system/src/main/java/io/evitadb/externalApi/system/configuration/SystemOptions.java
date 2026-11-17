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

package io.evitadb.externalApi.system.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.evitadb.externalApi.configuration.AbstractApiOptions;
import io.evitadb.externalApi.configuration.ApiWithSpecificPrefix;
import io.evitadb.externalApi.configuration.MtlsConfiguration;
import io.evitadb.externalApi.configuration.TlsMode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;

/**
 * System API specific configuration.
 *
 * @author Tomáš Pozler, 2023
 */
public class SystemOptions extends AbstractApiOptions implements ApiWithSpecificPrefix {
	/**
	 * Port on which will server be run and on which will channel be opened.
	 */
	public static final int DEFAULT_SYSTEM_PORT = 5555;
	private static final String BASE_SYSTEM_PATH = "system";

	/**
	 * Controls the prefix System API will react on.
	 * Default value is `system`.
	 */
	@Getter private final String prefix;

	/**
	 * Creates enabled options listening on all network interfaces at {@link #DEFAULT_SYSTEM_PORT}, with `tlsMode`
	 * {@link TlsMode#FORCE_NO_TLS}.
	 */
	public SystemOptions() {
		super(true, ":" + DEFAULT_SYSTEM_PORT, TlsMode.FORCE_NO_TLS);
		this.prefix = BASE_SYSTEM_PATH;
	}

	/**
	 * Creates enabled options listening on `host` (comma-separated `hostname:port` definitions), with `tlsMode`
	 * {@link TlsMode#FORCE_NO_TLS}.
	 */
	public SystemOptions(@Nonnull String host) {
		super(true, host, TlsMode.FORCE_NO_TLS);
		this.prefix = BASE_SYSTEM_PATH;
	}

	/**
	 * Creates the options from a configuration file. A missing `tlsMode` resolves to {@link TlsMode#FORCE_NO_TLS},
	 * the same default as the other constructors, because the endpoint serves the server certificate over plain HTTP.
	 */
	@JsonCreator
	public SystemOptions(@Nullable @JsonProperty("enabled") Boolean enabled,
	                     @Nonnull @JsonProperty("host") String host,
	                     @Nullable @JsonProperty("exposeOn") String exposeOn,
	                     @Nullable @JsonProperty("tlsMode") String tlsMode,
	                     @Nullable @JsonProperty("keepAlive") Boolean keepAlive,
	                     @Nullable @JsonProperty("prefix") String prefix,
	                     @Nullable @JsonProperty("mTLS") MtlsConfiguration mtlsConfiguration
	) {
		super(enabled, host, exposeOn, tlsMode, keepAlive, mtlsConfiguration, TlsMode.FORCE_NO_TLS);
		this.prefix = Optional.ofNullable(prefix).orElse(BASE_SYSTEM_PATH);
	}
}
