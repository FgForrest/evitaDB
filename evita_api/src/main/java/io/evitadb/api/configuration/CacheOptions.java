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
import io.evitadb.utils.Assert;
import lombok.ToString;

import javax.annotation.Nonnull;

/**
 * Configuration of the cross-query formula result cache, bounded by the estimated size of retained entries.
 *
 * @param reflection mode for caching Java reflection metadata; `CACHE` is recommended for normal operation
 * @param enabled whether cross-query caching of formula computation results is enabled
 * @param minimalComplexityThreshold minimal estimated cost of a computation whose result may be cached
 * @param cacheSizeInBytes target budget in bytes for the estimated size of retained entries; `0` selects the default
 *                         of 25% of free heap measured at class initialization
 * @param dropEntriesAfterSecondsOfInactivity idle time in seconds before an entry expires, also the maintenance
 *                                          period; `0` disables expiry and its maintenance timer
 * @param verifyOneHitIn sampling rate for verifying cache hits by recomputation; `1` verifies every hit,
 *                       `1000` verifies one in a thousand hits and `0` disables verification
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public record CacheOptions(
	@Nonnull ReflectionCachingBehaviour reflection,
	boolean enabled,
	long minimalComplexityThreshold,
	long cacheSizeInBytes,
	int dropEntriesAfterSecondsOfInactivity,
	int verifyOneHitIn
) {

	/** Cross-query caching stays off unless enabled; equal to the shipped server configuration's default. */
	public static final boolean DEFAULT_ENABLED = false;
	/** Minimal estimated computation cost for cache admission; equal to the shipped server configuration's default. */
	public static final long DEFAULT_MINIMAL_COMPLEXITY_THRESHOLD = 10_000L;
	/** Idle time in seconds before an entry expires; equal to the shipped server configuration's default. */
	public static final int DEFAULT_DROP_ENTRIES_AFTER_SECONDS_OF_INACTIVITY = 600;
	/** Verifies one in this many cache hits; equal to the shipped server configuration's default. */
	public static final int DEFAULT_VERIFY_ONE_HIT_IN = 1_000;
	private static final long DEFAULT_CACHE_SIZE;

	/*
		Initializer computes default size of the memory allocated to the EvitaDB instance and needs to be calculated
		fairly early. Before the catalog contents fill up the memory. It is computed as a 25% share from the difference
		between maximal amount of the memory the Java can have and currently used memory.

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
	@Nonnull
	public static CacheOptions.Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for the cache options. Recommended to use to avoid binary compatibility problems in the future.
	 */
	@Nonnull
	public static CacheOptions.Builder builder(@Nonnull CacheOptions cacheOptions) {
		return new Builder(cacheOptions);
	}

	/**
	 * Validates cache limits and resolves a zero budget to the default measured at class initialization.
	 */
	public CacheOptions {
		Assert.isTrue(minimalComplexityThreshold >= 0, "Minimal complexity threshold must not be negative.");
		Assert.isTrue(cacheSizeInBytes >= 0, "Cache size in bytes must not be negative.");
		Assert.isTrue(
			dropEntriesAfterSecondsOfInactivity >= 0, "Cache entry inactivity period must not be negative."
		);
		Assert.isTrue(verifyOneHitIn >= 0, "Cache hit verification rate must not be negative.");
		if (cacheSizeInBytes == 0) {
			cacheSizeInBytes = DEFAULT_CACHE_SIZE;
		}
	}

	/**
	 * Creates cache options with the defaults shared by embedded instances and the server configuration.
	 */
	public CacheOptions() {
		this(
			ReflectionCachingBehaviour.CACHE,
			DEFAULT_ENABLED,
			DEFAULT_MINIMAL_COMPLEXITY_THRESHOLD,
			DEFAULT_CACHE_SIZE,
			DEFAULT_DROP_ENTRIES_AFTER_SECONDS_OF_INACTIVITY,
			DEFAULT_VERIFY_ONE_HIT_IN
		);
	}

	/**
	 * Standard builder pattern implementation.
	 */
	@ToString
	public static class Builder {
		private ReflectionCachingBehaviour reflection = ReflectionCachingBehaviour.CACHE;
		private boolean enabled = DEFAULT_ENABLED;
		private long minimalComplexityThreshold = DEFAULT_MINIMAL_COMPLEXITY_THRESHOLD;
		private long cacheSizeInBytes = DEFAULT_CACHE_SIZE;
		private int dropEntriesAfterSecondsOfInactivity = DEFAULT_DROP_ENTRIES_AFTER_SECONDS_OF_INACTIVITY;
		private int verifyOneHitIn = DEFAULT_VERIFY_ONE_HIT_IN;

		Builder() {
		}

		Builder(@Nonnull CacheOptions cacheOptions) {
			this.reflection = cacheOptions.reflection;
			this.enabled = cacheOptions.enabled;
			this.minimalComplexityThreshold = cacheOptions.minimalComplexityThreshold;
			this.cacheSizeInBytes = cacheOptions.cacheSizeInBytes;
			this.dropEntriesAfterSecondsOfInactivity = cacheOptions.dropEntriesAfterSecondsOfInactivity;
			this.verifyOneHitIn = cacheOptions.verifyOneHitIn;
		}

		/**
		 * Sets the reflection metadata caching mode.
		 */
		@Nonnull
		public Builder reflection(@Nonnull ReflectionCachingBehaviour reflection) {
			this.reflection = reflection;
			return this;
		}

		/**
		 * Enables or disables cross-query formula result caching.
		 */
		@Nonnull
		public Builder enabled(boolean enabled) {
			this.enabled = enabled;
			return this;
		}

		/**
		 * Sets the minimal estimated computation cost required for cache admission.
		 */
		@Nonnull
		public Builder minimalComplexityThreshold(long minimalComplexityThreshold) {
			this.minimalComplexityThreshold = minimalComplexityThreshold;
			return this;
		}

		/**
		 * Sets the estimated retained size budget in bytes; `0` selects the default.
		 */
		@Nonnull
		public Builder cacheSizeInBytes(long cacheSizeInBytes) {
			this.cacheSizeInBytes = cacheSizeInBytes;
			return this;
		}

		/**
		 * Sets the idle expiry and maintenance period in seconds; `0` disables both.
		 */
		@Nonnull
		public Builder dropEntriesAfterSecondsOfInactivity(int dropEntriesAfterSecondsOfInactivity) {
			this.dropEntriesAfterSecondsOfInactivity = dropEntriesAfterSecondsOfInactivity;
			return this;
		}

		/**
		 * Sets the hit verification sampling rate; `0` disables verification.
		 */
		@Nonnull
		public Builder verifyOneHitIn(int verifyOneHitIn) {
			this.verifyOneHitIn = verifyOneHitIn;
			return this;
		}

		/**
		 * Builds validated cache options.
		 */
		@Nonnull
		public CacheOptions build() {
			return new CacheOptions(
				this.reflection, this.enabled,
				this.minimalComplexityThreshold,
				this.cacheSizeInBytes,
				this.dropEntriesAfterSecondsOfInactivity,
				this.verifyOneHitIn
			);
		}

	}

}
