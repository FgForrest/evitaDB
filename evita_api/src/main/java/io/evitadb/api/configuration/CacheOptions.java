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

package io.evitadb.api.configuration;

import io.evitadb.dataType.data.ReflectionCachingBehaviour;
import lombok.ToString;

import javax.annotation.Nonnull;

/**
 * This class is simple DTO object holding all cache-related configuration options of the Evita.
 *
 * @param reflection                 Contains mode for accessing reflection data - CACHE mode is mostly recommended,
 *                                   unless you're running some kind of tests.
 * @param enabled                    Enables global-wide caching of Evita query results. If caching is enabled, costly
 *                                   computation results may be cached and next time they are found in the query
 *                                   computation tree immediately replaced with the already memoized computation result.
 * @param reevaluateEachSeconds      Contains interval in second the cache anteroom is asynchronously re-evaluated and
 *                                   its contents are either purged or moved to cache
 * @param anteroomRecordCount        Contains limit of maximal entries held in cache anteroom. When this limit is
 *                                   exceeded the anteroom needs to be re-evaluated and the adepts either cleared or
 *                                   moved to cache. In other words it's the size of the cache anteroom buffer.
 * @param minimalComplexityThreshold Contains minimal threshold of the cost to performance ration that cacheable object
 *                                   needs to exceed in order to become a cache adept, that may be potentially moved to
 *                                   cache.
 * @param minimalUsageThreshold      Contains minimal threshold the cacheable object must be "used" in order it could be
 *                                   considered to be moved to the cache. This allows us to avoid expensive "unicorn"
 *                                   cacheable objects (that occurs once a long while) to get cached. Each object needs
 *                                   to earn the placement in cache.
 * @param cacheSizeInBytes           Contains memory limit for the formula cache in Bytes. Java is very dynamic in
 *                                   object memory sizes, so we only try to estimate the size of cached data in order
 *                                   to control the cache size within the defined limit.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record CacheOptions(
	@Nonnull ReflectionCachingBehaviour reflection,
	boolean enabled,
	int reevaluateEachSeconds,
	int anteroomRecordCount,
	long minimalComplexityThreshold,
	int minimalUsageThreshold,
	long cacheSizeInBytes
) {

	public static final boolean DEFAULT_ENABLED = false;
	public static final int DEFAULT_REEVALUATE_EACH_SECONDS = 0;
	public static final int DEFAULT_ANTEROOM_RECORD_COUNT = 0;
	public static final long DEFAULT_MINIMAL_COMPLEXITY_THRESHOLD = 0L;
	public static final int DEFAULT_MINIMAL_USAGE_THRESHOLD = 0;
	private final static long DEFAULT_CACHE_SIZE;

	/*
		Initializer computes default size of the memory allocated to the EvitaDB instance and needs to be calculated
		fairly early. Before the catalog contents fill up the memory. It is computed as a 25% share from the difference
		between maximal amount of the memory the Java can hav and currently used memory.

		This of course cannot take the size of the catalogs that needs to be loaded into an account.
	 */
	static {
		final Runtime runtime = Runtime.getRuntime();
		final long maxMemory = runtime.maxMemory();
		final long usedMemory = runtime.totalMemory() - runtime.freeMemory();
		DEFAULT_CACHE_SIZE = (long) (((double) maxMemory - (double) usedMemory) * 0.25d);
	}

	/**
	 * Builder for the cache options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	public static CacheOptions.Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for the cache options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	public static CacheOptions.Builder builder(@Nonnull CacheOptions cacheOptions) {
		return new Builder(cacheOptions);
	}

	public CacheOptions() {
		this(
			ReflectionCachingBehaviour.CACHE,
			DEFAULT_ENABLED, DEFAULT_REEVALUATE_EACH_SECONDS,
			DEFAULT_ANTEROOM_RECORD_COUNT,
			DEFAULT_MINIMAL_COMPLEXITY_THRESHOLD,
			DEFAULT_MINIMAL_USAGE_THRESHOLD,
			DEFAULT_CACHE_SIZE
		);
	}

	/**
	 * Standard builder pattern implementation.
	 */
	@ToString
	public static class Builder {
		private ReflectionCachingBehaviour reflection = ReflectionCachingBehaviour.CACHE;
		private boolean enabled = DEFAULT_ENABLED;
		private int reevaluateEachSeconds = DEFAULT_REEVALUATE_EACH_SECONDS;
		private int anteroomRecordCount = DEFAULT_ANTEROOM_RECORD_COUNT;
		private long minimalComplexityThreshold = DEFAULT_MINIMAL_COMPLEXITY_THRESHOLD;
		private int minimalUsageThreshold = DEFAULT_MINIMAL_USAGE_THRESHOLD;
		private long cacheSizeInBytes = DEFAULT_CACHE_SIZE;

		Builder() {
		}

		Builder(@Nonnull CacheOptions cacheOptions) {
			this.reflection = cacheOptions.reflection;
			this.enabled = cacheOptions.enabled;
			this.reevaluateEachSeconds = cacheOptions.reevaluateEachSeconds;
			this.anteroomRecordCount = cacheOptions.anteroomRecordCount;
			this.minimalComplexityThreshold = cacheOptions.minimalComplexityThreshold;
			this.minimalUsageThreshold = cacheOptions.minimalUsageThreshold;
			this.cacheSizeInBytes = cacheOptions.cacheSizeInBytes;
		}

		public Builder reflection(ReflectionCachingBehaviour reflection) {
			this.reflection = reflection;
			return this;
		}

		public Builder enabled(boolean enabled) {
			this.enabled = enabled;
			return this;
		}

		public Builder reevaluateEachSeconds(int reevaluateEachSeconds) {
			this.reevaluateEachSeconds = reevaluateEachSeconds;
			return this;
		}

		public Builder anteroomRecordCount(int anteroomRecordCount) {
			this.anteroomRecordCount = anteroomRecordCount;
			return this;
		}

		public Builder minimalComplexityThreshold(long minimalComplexityThreshold) {
			this.minimalComplexityThreshold = minimalComplexityThreshold;
			return this;
		}

		public Builder minimalUsageThreshold(int minimalUsageThreshold) {
			this.minimalUsageThreshold = minimalUsageThreshold;
			return this;
		}

		public Builder cacheSizeInBytes(long cacheSizeInBytes) {
			this.cacheSizeInBytes = cacheSizeInBytes;
			return this;
		}

		public CacheOptions build() {
			return new CacheOptions(
				this.reflection, this.enabled,
				this.reevaluateEachSeconds,
				this.anteroomRecordCount,
				this.minimalComplexityThreshold,
				this.minimalUsageThreshold,
				this.cacheSizeInBytes
			);
		}

	}

}
