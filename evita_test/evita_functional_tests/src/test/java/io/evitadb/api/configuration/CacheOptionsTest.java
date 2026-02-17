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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CacheOptions} record and its builder.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("CacheOptions")
class CacheOptionsTest {

	@Test
	@DisplayName("should initialize all defaults via builder")
	void shouldInitDefaults() {
		final CacheOptions options =
			CacheOptions.builder().build();

		assertFalse(options.enabled());
		assertEquals(
			0, options.minimalComplexityThreshold()
		);
		assertEquals(
			0, options.minimalUsageThreshold()
		);
		assertEquals(
			0, options.anteroomRecordCount()
		);
		assertEquals(
			0, options.reevaluateEachSeconds()
		);
		assertTrue(options.cacheSizeInBytes() > 0);
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
					.reevaluateEachSeconds(30)
					.anteroomRecordCount(1000)
					.minimalComplexityThreshold(500L)
					.minimalUsageThreshold(10)
					.cacheSizeInBytes(1_000_000L)
					.build();

			final CacheOptions copy =
				CacheOptions.builder(source).build();

			assertEquals(
				ReflectionCachingBehaviour.NO_CACHE,
				copy.reflection()
			);
			assertTrue(copy.enabled());
			assertEquals(
				30, copy.reevaluateEachSeconds()
			);
			assertEquals(
				1000, copy.anteroomRecordCount()
			);
			assertEquals(
				500L, copy.minimalComplexityThreshold()
			);
			assertEquals(
				10, copy.minimalUsageThreshold()
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
}
