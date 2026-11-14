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

package io.evitadb.externalApi.graphql.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.evitadb.externalApi.configuration.AbstractApiOptions;
import io.evitadb.externalApi.configuration.ApiWithSpecificPrefix;
import io.evitadb.externalApi.configuration.MtlsConfiguration;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static java.util.Optional.ofNullable;

/**
 * GraphQL API specific configuration.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class GraphQLOptions extends AbstractApiOptions implements ApiWithSpecificPrefix {
	private static final String BASE_GRAPHQL_PATH = "gql";
	/**
	 * Default memory budget of the parsed and validated document cache of a single GraphQL instance (16 MB).
	 */
	public static final long DEFAULT_DOCUMENT_CACHE_SIZE_IN_BYTES = 16L * 1024L * 1024L;

	/**
	 * Controls the prefix GraphQL API will react on.
	 * Default value is `gql`.
	 */
	@Getter private final String prefix;
	/**
	 * Controls whether the ReadDataFetcher will be executed in parallel.
	 */
	@Getter private final boolean parallelize;
	/**
	 * Approximate memory budget in bytes of the cache of parsed and validated GraphQL documents. Identical query
	 * texts are lexed, parsed and validated only once and the resulting document is reused by subsequent requests.
	 * The budget applies to each GraphQL instance separately - i.e. to the data API and the schema API of every
	 * catalog and to the system API. The size of a cached document is estimated from the length of its query text.
	 * Zero disables the cache, negative values are rejected. Default value is 16 MB.
	 */
	@Getter private final long documentCacheSizeInBytes;

	public GraphQLOptions() {
		super();
		this.prefix = BASE_GRAPHQL_PATH;
		this.parallelize = true;
		this.documentCacheSizeInBytes = DEFAULT_DOCUMENT_CACHE_SIZE_IN_BYTES;
	}

	public GraphQLOptions(@Nonnull String host) {
		super(true, host);
		this.prefix = BASE_GRAPHQL_PATH;
		this.parallelize = true;
		this.documentCacheSizeInBytes = DEFAULT_DOCUMENT_CACHE_SIZE_IN_BYTES;
	}

	@JsonCreator
	public GraphQLOptions(
		@Nullable @JsonProperty("enabled") Boolean enabled,
		@Nonnull @JsonProperty("host") String host,
		@Nullable @JsonProperty("exposeOn") String exposeOn,
		@Nullable @JsonProperty("tlsMode") String tlsMode,
		@Nullable @JsonProperty("keepAlive") Boolean keepAlive,
		@Nullable @JsonProperty("prefix") String prefix,
		@Nullable @JsonProperty("parallelize") Boolean parallelize,
		@Nullable @JsonProperty("documentCacheSizeInBytes") Long documentCacheSizeInBytes,
		@Nullable @JsonProperty("mTLS") MtlsConfiguration mtlsConfiguration
	) {
		super(enabled, host, exposeOn, tlsMode, keepAlive, mtlsConfiguration);
		this.prefix = ofNullable(prefix).orElse(BASE_GRAPHQL_PATH);
		this.parallelize = ofNullable(parallelize).orElse(false);
		this.documentCacheSizeInBytes = ofNullable(documentCacheSizeInBytes)
			.orElse(DEFAULT_DOCUMENT_CACHE_SIZE_IN_BYTES);
		Assert.isTrue(
			this.documentCacheSizeInBytes >= 0,
			() -> "GraphQL `documentCacheSizeInBytes` must not be negative (use 0 to disable the cache), but was: " +
				this.documentCacheSizeInBytes
		);
	}

}
