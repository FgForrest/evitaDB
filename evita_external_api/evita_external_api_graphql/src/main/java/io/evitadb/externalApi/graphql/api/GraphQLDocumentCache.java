/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.externalApi.graphql.api;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.execution.preparsed.PreparsedDocumentEntry;
import graphql.execution.preparsed.PreparsedDocumentProvider;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Bounded cache of parsed and validated GraphQL documents of a single {@link GraphQL} instance. An identical query
 * text is lexed, parsed and validated only once; subsequent requests reuse the cached document and skip these phases
 * entirely (including the `beginParse` / `beginValidation` instrumentation callbacks).
 *
 * The query text alone is a sufficient key: validation in graphql-java depends only on the schema and the document
 * (neither variables nor operation name take part in it), and the schema is fixed for the lifetime of the owning
 * {@link GraphQL} instance. A schema change in evitaDB always builds a brand-new {@link GraphQL} instance with a new
 * cache, so no explicit invalidation is needed. Cached {@link graphql.language.Document}s are immutable and safe to
 * share across threads.
 *
 * Entries that carry parse or validation errors are deliberately not cached, so that a client sending many distinct
 * invalid queries cannot evict the hot valid ones. Concurrent misses of the same key may parse the same text twice,
 * which is harmless - the work is idempotent and the cache is never locked while parsing.
 *
 * The cache is bounded by an approximate memory budget. The retained size of a parsed document measured on real
 * e-commerce storefront queries (about 1 - 12 KB of text) is roughly 6.3 - 7.4 times the length of its query text,
 * while tiny documents are dominated by a fixed cost of several hundred bytes (a 9-character query retains ~650 B).
 * The weight of an entry is therefore estimated as {@link #BASE_ENTRY_WEIGHT} plus
 * {@link #WEIGHT_PER_QUERY_CHARACTER} times the query length (covering the key and the cache node as well).
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class GraphQLDocumentCache implements PreparsedDocumentProvider {

	/**
	 * Estimated number of retained bytes per character of the query text (parsed document plus the key).
	 */
	static final int WEIGHT_PER_QUERY_CHARACTER = 8;
	/**
	 * Estimated fixed number of retained bytes of every entry regardless of its query length (document skeleton,
	 * cache entry, key object).
	 */
	static final int BASE_ENTRY_WEIGHT = 1024;

	/**
	 * Underlying cache of successfully parsed and validated documents keyed by the query text.
	 */
	@Nonnull private final Cache<String, PreparsedDocumentEntry> cache;

	/**
	 * Creates a new cache with the given memory budget.
	 *
	 * @param maximumSizeInBytes approximate memory budget of the cache in bytes, must be positive
	 */
	public GraphQLDocumentCache(long maximumSizeInBytes) {
		Assert.isPremiseValid(
			maximumSizeInBytes > 0,
			"GraphQL document cache size must be positive, but was: " + maximumSizeInBytes
		);
		this.cache = Caffeine.newBuilder()
			.maximumWeight(maximumSizeInBytes)
			.weigher((String query, PreparsedDocumentEntry entry) -> estimateWeight(query))
			// eviction maintenance runs on the calling thread (non-blocking try-lock inside Caffeine) instead of
			// on the common fork-join pool; writes happen on misses only, so the cost is negligible and the bound
			// is enforced without delay
			.executor(Runnable::run)
			.build();
	}

	/**
	 * Estimates the retained size in bytes of a cache entry for the given query text.
	 *
	 * @param query query text used as the key
	 * @return estimated weight, saturated to {@link Integer#MAX_VALUE}
	 */
	static int estimateWeight(@Nonnull String query) {
		return (int) Math.min(
			BASE_ENTRY_WEIGHT + (long) query.length() * WEIGHT_PER_QUERY_CHARACTER,
			Integer.MAX_VALUE
		);
	}

	/**
	 * Returns the cached document for the query text of the input, or parses and validates it using the passed
	 * function and caches the result when it carries no errors.
	 *
	 * @param executionInput           input of the request, its query text is the cache key
	 * @param parseAndValidateFunction graphql-java function that parses and validates the query (and fires the
	 *                                 parse and validation instrumentation callbacks)
	 * @return completed future with the cached or freshly computed entry
	 */
	@Nonnull
	@Override
	public CompletableFuture<PreparsedDocumentEntry> getDocumentAsync(
		@Nonnull ExecutionInput executionInput,
		@Nonnull Function<ExecutionInput, PreparsedDocumentEntry> parseAndValidateFunction
	) {
		final String query = executionInput.getQuery();
		final PreparsedDocumentEntry cachedEntry = this.cache.getIfPresent(query);
		if (cachedEntry != null) {
			return CompletableFuture.completedFuture(cachedEntry);
		}

		// computed outside the cache on purpose - parsing must not run under Caffeine's per-key lock and error
		// entries must not be stored at all
		final PreparsedDocumentEntry computedEntry = parseAndValidateFunction.apply(executionInput);
		if (!computedEntry.hasErrors()) {
			this.cache.put(query, computedEntry);
		}
		return CompletableFuture.completedFuture(computedEntry);
	}
}
