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

package io.evitadb.externalApi.rest.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.evitadb.externalApi.configuration.AbstractApiOptions;
import io.evitadb.externalApi.configuration.ApiWithSpecificPrefix;
import io.evitadb.externalApi.configuration.MtlsConfiguration;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;

/**
 * REST API specific configuration.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
public class RestOptions extends AbstractApiOptions implements ApiWithSpecificPrefix {
	private static final String BASE_REST_PATH = "rest";

	/**
	 * Controls the prefix REST API will react on.
	 * Default value is `rest`.
	 */
	@Getter private final String prefix;

	public RestOptions() {
		super();
		this.prefix = BASE_REST_PATH;
	}

	public RestOptions(@Nonnull String host) {
		super(true, host);
		this.prefix = BASE_REST_PATH;
	}

	@JsonCreator
	public RestOptions(@Nullable @JsonProperty("enabled") Boolean enabled,
	                   @Nonnull @JsonProperty("host") String host,
	                   @Nullable @JsonProperty("exposeOn") String exposeOn,
	                   @Nullable @JsonProperty("tlsMode") String tlsMode,
	                   @Nullable @JsonProperty("keepAlive") Boolean keepAlive,
	                   @Nullable @JsonProperty("prefix") String prefix,
	                   @Nullable @JsonProperty("mTLS") MtlsConfiguration mtlsConfiguration
	) {
		super(enabled, host, exposeOn, tlsMode, keepAlive, mtlsConfiguration);
		this.prefix = Optional.ofNullable(prefix).orElse(BASE_REST_PATH);
	}
}
