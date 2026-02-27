/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ChangeDataCaptureOptions} record and its builder.
 *
 * @author Claude
 */
@DisplayName("ChangeDataCaptureOptions")
class ChangeDataCaptureOptionsTest {

	@Nested
	@DisplayName("Default constructor")
	class DefaultConstructorTest {

		@Test
		@DisplayName("should use default values")
		void shouldUseDefaultValues() {
			final ChangeDataCaptureOptions options =
				new ChangeDataCaptureOptions();

			assertTrue(options.enabled());
			assertEquals(
				ChangeDataCaptureOptions
					.DEFAULT_RECENT_EVENTS_CACHE_LIMIT,
				options.recentEventsCacheLimit()
			);
			assertEquals(
				ChangeDataCaptureOptions
					.DEFAULT_SUBSCRIBER_BUFFER_SIZE,
				options.subscriberBufferSize()
			);
		}
	}

	@Nested
	@DisplayName("Builder")
	class BuilderTest {

		@Test
		@DisplayName("should build with defaults")
		void shouldBuildWithDefaults() {
			final ChangeDataCaptureOptions options =
				ChangeDataCaptureOptions.builder().build();

			assertTrue(options.enabled());
		}

		@Test
		@DisplayName("should override all fields")
		void shouldOverrideAllFields() {
			final ChangeDataCaptureOptions options =
				ChangeDataCaptureOptions.builder()
					.enabled(false)
					.recentEventsCacheLimit(512)
					.subscriberBufferSize(64)
					.build();

			assertFalse(options.enabled());
			assertEquals(
				512, options.recentEventsCacheLimit()
			);
			assertEquals(
				64, options.subscriberBufferSize()
			);
		}
	}

	@Nested
	@DisplayName("Copy constructor")
	class CopyConstructorTest {

		@Test
		@DisplayName(
			"should copy all fields from source"
		)
		void shouldCopyAllFieldsFromSource() {
			final ChangeDataCaptureOptions source =
				ChangeDataCaptureOptions.builder()
					.enabled(false)
					.recentEventsCacheLimit(1024)
					.subscriberBufferSize(128)
					.build();

			final ChangeDataCaptureOptions copy =
				ChangeDataCaptureOptions.builder(source)
					.build();

			assertEquals(
				source.enabled(), copy.enabled()
			);
			assertEquals(
				source.recentEventsCacheLimit(),
				copy.recentEventsCacheLimit()
			);
			assertEquals(
				source.subscriberBufferSize(),
				copy.subscriberBufferSize()
			);
		}
	}
}
