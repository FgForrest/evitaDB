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
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.CACHE;

/**
 * Tests for {@link CacheOptions} record and its builder.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("CacheOptions")
@Tag(CONTRACT)
@Tag(MANAGEMENT)
@Tag(CACHE)
class CacheOptionsTest {

	@Test
	@DisplayName("should initialize all defaults via builder")
	void shouldInitDefaults() {
		final CacheOptions options =
			CacheOptions.builder().build();

		assertFalse(options.enabled());
		assertEquals(new CacheOptions(), options);
		assertEquals(10_000L, options.minimalComplexityThreshold());
		assertEquals(600, options.dropEntriesAfterSecondsOfInactivity());
		assertEquals(1_000, options.verifyOneHitIn());
	}

	@Nested
	@DisplayName("Reflection default")
	class ReflectionDefaultTest {

		@Test
		@DisplayName(
			"should use CACHE reflection by default"
		)
		void shouldUseCacheReflectionByDefault() {
			final CacheOptions options =
				CacheOptions.builder().build();

			assertEquals(
				ReflectionCachingBehaviour.CACHE,
				options.reflection()
			);
		}

		@Test
		@DisplayName(
			"should override reflection via builder"
		)
		void shouldOverrideReflectionViaBuilder() {
			final CacheOptions options =
				CacheOptions.builder()
					.reflection(
						ReflectionCachingBehaviour.NO_CACHE
					)
					.build();

			assertEquals(
				ReflectionCachingBehaviour.NO_CACHE,
				options.reflection()
			);
		}
	}

	@Nested
	@DisplayName("Builder copy constructor")
	class BuilderCopyTest {

		@Test
		@DisplayName(
			"should copy all fields from source"
		)
		void shouldCopyAllFieldsFromSource() {
			final CacheOptions source =
				CacheOptions.builder()
					.reflection(
						ReflectionCachingBehaviour.NO_CACHE
					)
					.enabled(true)
					.dropEntriesAfterSecondsOfInactivity(30)
					.minimalComplexityThreshold(500L)
					.verifyOneHitIn(10)
					.cacheSizeInBytes(1_000_000L)
					.build();

			final CacheOptions copy =
				CacheOptions.builder(source).build();

			assertEquals(source, copy);

			assertEquals(
				ReflectionCachingBehaviour.NO_CACHE,
				copy.reflection()
			);
			assertTrue(copy.enabled());
			assertEquals(
				30, copy.dropEntriesAfterSecondsOfInactivity()
			);
			assertEquals(
				500L, copy.minimalComplexityThreshold()
			);
			assertEquals(
				10, copy.verifyOneHitIn()
			);
			assertEquals(
				1_000_000L, copy.cacheSizeInBytes()
			);
		}
	}

	@Nested
	@DisplayName("Cache size")
	class CacheSizeTest {

		@Test
		@DisplayName(
			"should have positive default cache size"
		)
		void shouldHavePositiveDefaultCacheSize() {
			final CacheOptions options =
				CacheOptions.builder().build();

			assertTrue(
				options.cacheSizeInBytes() > 0,
				"Default cache size should be positive"
			);
		}

		@Test
		@DisplayName(
			"should allow overriding cache size"
		)
		void shouldAllowOverridingCacheSize() {
			final CacheOptions options =
				CacheOptions.builder()
					.cacheSizeInBytes(2_000_000L)
					.build();

			assertEquals(
				2_000_000L, options.cacheSizeInBytes()
			);
		}
	}

	@Nested
	@DisplayName("Zero values")
	class ZeroValueTest {

		@Test
		@DisplayName("should resolve a zero budget to the shared default")
		void shouldResolveZeroBudgetToDefault() {
			assertEquals(new CacheOptions(), CacheOptions.builder().cacheSizeInBytes(0).build());
			assertEquals(
				new CacheOptions(), new CacheOptions(ReflectionCachingBehaviour.CACHE, false, 10_000, 0, 600, 1_000)
			);
		}

		@Test
		@DisplayName("should disable expiry and verification with zero values")
		void shouldDisableExpiryAndVerificationWithZeroValues() {
			final CacheOptions options = CacheOptions.builder()
				.dropEntriesAfterSecondsOfInactivity(0)
				.verifyOneHitIn(0)
				.minimalComplexityThreshold(0)
				.build();
			assertEquals(0, options.dropEntriesAfterSecondsOfInactivity());
			assertEquals(0, options.verifyOneHitIn());
			assertEquals(0, options.minimalComplexityThreshold());
		}
	}

	@Nested
	@DisplayName("Negative limits")
	class NegativeLimitTest {

		@Test
		@DisplayName("should reject negative limits in the canonical constructor")
		void shouldRejectNegativeLimitsInCanonicalConstructor() {
			assertThrows(EvitaInvalidUsageException.class,
				() -> new CacheOptions(ReflectionCachingBehaviour.CACHE, false, -1, 1, 600, 1_000));
			assertThrows(EvitaInvalidUsageException.class,
				() -> new CacheOptions(ReflectionCachingBehaviour.CACHE, false, 10_000, -1, 600, 1_000));
			assertThrows(EvitaInvalidUsageException.class,
				() -> new CacheOptions(ReflectionCachingBehaviour.CACHE, false, 10_000, 1, -1, 1_000));
			assertThrows(EvitaInvalidUsageException.class,
				() -> new CacheOptions(ReflectionCachingBehaviour.CACHE, false, 10_000, 1, 600, -1));
		}

		@Test
		@DisplayName("should reject negative limits via the builder")
		void shouldRejectNegativeLimitsViaBuilder() {
			assertThrows(EvitaInvalidUsageException.class,
				() -> CacheOptions.builder().minimalComplexityThreshold(-1).build());
			assertThrows(EvitaInvalidUsageException.class,
				() -> CacheOptions.builder().cacheSizeInBytes(-1).build());
			assertThrows(EvitaInvalidUsageException.class,
				() -> CacheOptions.builder().dropEntriesAfterSecondsOfInactivity(-1).build());
			assertThrows(EvitaInvalidUsageException.class,
				() -> CacheOptions.builder().verifyOneHitIn(-1).build());
		}
	}
}
