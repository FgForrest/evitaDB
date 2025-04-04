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

package io.evitadb.externalApi.observability.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.evitadb.externalApi.configuration.AbstractApiOptions;
import io.evitadb.externalApi.configuration.ApiWithSpecificPrefix;
import io.evitadb.externalApi.configuration.MtlsConfiguration;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * Observability API specific configuration.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
public class ObservabilityOptions extends AbstractApiOptions implements ApiWithSpecificPrefix {
	/**
	 * Port on which will server be run and on which will channel be opened.
	 */
	public static final int DEFAULT_OBSERVABILITY_PORT = 5555;
	private static final String BASE_OBSERVABILITY_PATH = "observability";

	/**
	 * Controls the prefix Metrics API will react on.
	 * Default value is `metrics`.
	 */
	@Getter private final String prefix;
	@Getter private final TracingConfig tracing;

	@Getter @Nullable private final List<String> allowedEvents;

	public ObservabilityOptions() {
		super(true, "0.0.0.0:" + DEFAULT_OBSERVABILITY_PORT, null, null, null, null);
		this.prefix = BASE_OBSERVABILITY_PATH;
		this.tracing = new TracingConfig();
		this.allowedEvents = null;
	}

	public ObservabilityOptions(@Nonnull String host) {
		super(true, host, null, null, null, null);
		this.prefix = BASE_OBSERVABILITY_PATH;
		this.tracing = new TracingConfig();
		this.allowedEvents = null;
	}

	@JsonCreator
	public ObservabilityOptions(@Nullable @JsonProperty("enabled") Boolean enabled,
	                            @Nonnull @JsonProperty("host") String host,
	                            @Nullable @JsonProperty("exposeOn") String exposeOn,
	                            @Nullable @JsonProperty("tlsMode") String tlsMode,
	                            @Nullable @JsonProperty("keepAlive") Boolean keepAlive,
	                            @Nullable @JsonProperty("prefix") String prefix,
	                            @Nullable @JsonProperty("tracing") TracingConfig tracing,
	                            @Nullable @JsonProperty("allowedEvents") List<String> allowedEvents,
	                            @Nullable @JsonProperty("mTLS") MtlsConfiguration mtlsConfiguration
	) {
		super(enabled, host, exposeOn, tlsMode, keepAlive, mtlsConfiguration);
		this.prefix = Optional.ofNullable(prefix).orElse(BASE_OBSERVABILITY_PATH);
		this.tracing = Optional.ofNullable(tracing).orElse(new TracingConfig());
		this.allowedEvents = allowedEvents;
	}
}
