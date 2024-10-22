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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.graphql.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.evitadb.externalApi.configuration.AbstractApiConfiguration;
import io.evitadb.externalApi.configuration.ApiWithSpecificPrefix;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static java.util.Optional.ofNullable;

/**
 * GraphQL API specific configuration.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class GraphQLConfig extends AbstractApiConfiguration implements ApiWithSpecificPrefix {
	private static final String BASE_GRAPHQL_PATH = "gql";

	/**
	 * Controls the prefix GraphQL API will react on.
	 * Default value is `gql`.
	 */
	@Getter private final String prefix;
	/**
	 * Controls whether the ReadDataFetcher will be executed in parallel.
	 */
	@Getter private final boolean parallelize;

	public GraphQLConfig() {
		super();
		this.prefix = BASE_GRAPHQL_PATH;
		this.parallelize = true;
	}

	public GraphQLConfig(@Nonnull String host) {
		super(true, host);
		this.prefix = BASE_GRAPHQL_PATH;
		this.parallelize = true;
	}

	@JsonCreator
	public GraphQLConfig(
		@Nullable @JsonProperty("enabled") Boolean enabled,
		@Nonnull @JsonProperty("host") String host,
		@Nullable @JsonProperty("exposeOn") String exposeOn,
		@Nullable @JsonProperty("tlsMode") String tlsMode,
		@Nullable @JsonProperty("prefix") String prefix,
		@Nullable @JsonProperty("parallelize") Boolean parallelize
	) {
		super(enabled, host, exposeOn, tlsMode);
		this.prefix = ofNullable(prefix).orElse(BASE_GRAPHQL_PATH);
		this.parallelize = ofNullable(parallelize).orElse(false);
	}

}
