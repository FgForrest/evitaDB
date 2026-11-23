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
import io.evitadb.externalApi.configuration.TlsMode;
import graphql.validation.QueryComplexityLimits;
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
	 * Default maximum depth of a GraphQL query, the graphql-java default
	 * ({@value QueryComplexityLimits#DEFAULT_MAX_DEPTH}).
	 */
	public static final int DEFAULT_MAX_QUERY_DEPTH = QueryComplexityLimits.DEFAULT_MAX_DEPTH;
	/**
	 * Default maximum number of fields of a GraphQL query, the graphql-java default
	 * ({@value QueryComplexityLimits#DEFAULT_MAX_FIELDS_COUNT}).
	 */
	public static final int DEFAULT_MAX_QUERY_FIELDS_COUNT = QueryComplexityLimits.DEFAULT_MAX_FIELDS_COUNT;

	/**
	 * Controls the prefix GraphQL API will react on.
	 * Default value is `gql`.
	 */
	@Getter private final String prefix;
	/**
	 * Controls whether the data fetchers wrapped in
	 * {@link io.evitadb.externalApi.graphql.api.resolver.dataFetcher.AsyncDataFetcher} run asynchronously: reads on
	 * the request executor, writes on the transaction executor. These are the fetchers of all top-level query and
	 * mutation fields of the data API and the schema API of every catalog and of the system API; subscription fields
	 * are not wrapped and are not affected. When disabled, the wrapped fetchers run on the calling thread. Default
	 * value is `true`.
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
	/**
	 * Maximum depth of field selections in a GraphQL query document, fragments included. A deeper query is rejected
	 * during validation, before it executes. The limit applies to the query document, not to the size of the schema
	 * or of the result. Introspection queries are additionally capped by the graphql-java good faith introspection
	 * limits. Must be positive. Default value is {@value #DEFAULT_MAX_QUERY_DEPTH}.
	 */
	@Getter private final int maxQueryDepth;
	/**
	 * Maximum number of fields in a GraphQL query document, counted with every fragment spread expanded. A query with
	 * more fields is rejected during validation, before it executes. The limit applies to the query document, not to
	 * the size of the schema or of the result. Must be positive. Default value is
	 * {@value #DEFAULT_MAX_QUERY_FIELDS_COUNT}.
	 */
	@Getter private final int maxQueryFieldsCount;

	/**
	 * Creates enabled options listening on all network interfaces at {@link AbstractApiOptions#DEFAULT_PORT}, with
	 * `tlsMode` {@link TlsMode#FORCE_TLS}.
	 */
	public GraphQLOptions() {
		super(TlsMode.FORCE_TLS);
		this.prefix = BASE_GRAPHQL_PATH;
		this.parallelize = true;
		this.documentCacheSizeInBytes = DEFAULT_DOCUMENT_CACHE_SIZE_IN_BYTES;
		this.maxQueryDepth = DEFAULT_MAX_QUERY_DEPTH;
		this.maxQueryFieldsCount = DEFAULT_MAX_QUERY_FIELDS_COUNT;
	}

	/**
	 * Creates enabled options listening on `host` (comma-separated `hostname:port` definitions), with `tlsMode`
	 * {@link TlsMode#FORCE_TLS}.
	 */
	public GraphQLOptions(@Nonnull String host) {
		super(true, host, TlsMode.FORCE_TLS);
		this.prefix = BASE_GRAPHQL_PATH;
		this.parallelize = true;
		this.documentCacheSizeInBytes = DEFAULT_DOCUMENT_CACHE_SIZE_IN_BYTES;
		this.maxQueryDepth = DEFAULT_MAX_QUERY_DEPTH;
		this.maxQueryFieldsCount = DEFAULT_MAX_QUERY_FIELDS_COUNT;
	}

	/**
	 * Creates options from configuration; a missing `tlsMode` resolves to {@link TlsMode#FORCE_TLS} and an unknown name
	 * fails.
	 */
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
		@Nullable @JsonProperty("maxQueryDepth") Integer maxQueryDepth,
		@Nullable @JsonProperty("maxQueryFieldsCount") Integer maxQueryFieldsCount,
		@Nullable @JsonProperty("mTLS") MtlsConfiguration mtlsConfiguration
	) {
		super(enabled, host, exposeOn, tlsMode, keepAlive, mtlsConfiguration, TlsMode.FORCE_TLS);
		this.prefix = ofNullable(prefix).orElse(BASE_GRAPHQL_PATH);
		this.parallelize = ofNullable(parallelize).orElse(true);
		this.documentCacheSizeInBytes = ofNullable(documentCacheSizeInBytes)
			.orElse(DEFAULT_DOCUMENT_CACHE_SIZE_IN_BYTES);
		Assert.isTrue(
			this.documentCacheSizeInBytes >= 0,
			() -> "GraphQL `documentCacheSizeInBytes` must not be negative (use 0 to disable the cache), but was: " +
				this.documentCacheSizeInBytes
		);
		this.maxQueryDepth = ofNullable(maxQueryDepth).orElse(DEFAULT_MAX_QUERY_DEPTH);
		Assert.isTrue(
			this.maxQueryDepth > 0,
			() -> "GraphQL `maxQueryDepth` must be positive, but was: " + this.maxQueryDepth
		);
		this.maxQueryFieldsCount = ofNullable(maxQueryFieldsCount).orElse(DEFAULT_MAX_QUERY_FIELDS_COUNT);
		Assert.isTrue(
			this.maxQueryFieldsCount > 0,
			() -> "GraphQL `maxQueryFieldsCount` must be positive, but was: " + this.maxQueryFieldsCount
		);
	}

}
