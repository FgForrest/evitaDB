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
 * Tests for {@link TrafficRecordingOptions} record and its builder.
 *
 * @author Claude
 */
@DisplayName("TrafficRecordingOptions")
class TrafficRecordingOptionsTest {

	@Nested
	@DisplayName("Default constructor")
	class DefaultConstructorTest {

		@Test
		@DisplayName("should use default values")
		void shouldUseDefaultValues() {
			final TrafficRecordingOptions options =
				new TrafficRecordingOptions();

			assertFalse(options.enabled());
			assertFalse(options.sourceQueryTracking());
			assertEquals(
				TrafficRecordingOptions
					.DEFAULT_TRAFFIC_MEMORY_BUFFER,
				options.trafficMemoryBufferSizeInBytes()
			);
			assertEquals(
				TrafficRecordingOptions
					.DEFAULT_TRAFFIC_DISK_BUFFER,
				options.trafficDiskBufferSizeInBytes()
			);
			assertEquals(
				TrafficRecordingOptions
					.DEFAULT_EXPORT_FILE_CHUNK_SIZE,
				options.exportFileChunkSizeInBytes()
			);
			assertEquals(
				TrafficRecordingOptions
					.DEFAULT_TRAFFIC_SAMPLING_PERCENTAGE,
				options.trafficSamplingPercentage()
			);
			assertEquals(
				TrafficRecordingOptions
					.DEFAULT_TRAFFIC_FLUSH_INTERVAL,
				options.trafficFlushIntervalInMilliseconds()
			);
		}
	}

	@Nested
	@DisplayName("sourceQueryTrackingEnabled logic")
	class SourceQueryTrackingEnabledTest {

		@Test
		@DisplayName(
			"should return false when both flags are false"
		)
		void shouldReturnFalseWhenBothFalse() {
			final TrafficRecordingOptions options =
				TrafficRecordingOptions.builder()
					.enabled(false)
					.sourceQueryTracking(false)
					.build();

			assertFalse(options.sourceQueryTrackingEnabled());
		}

		@Test
		@DisplayName(
			"should return false when enabled but tracking off"
		)
		void shouldReturnFalseWhenEnabledButTrackingOff() {
			final TrafficRecordingOptions options =
				TrafficRecordingOptions.builder()
					.enabled(true)
					.sourceQueryTracking(false)
					.build();

			assertFalse(options.sourceQueryTrackingEnabled());
		}

		@Test
		@DisplayName(
			"should return false when tracking on but " +
				"disabled"
		)
		void shouldReturnFalseWhenTrackingOnButDisabled() {
			final TrafficRecordingOptions options =
				TrafficRecordingOptions.builder()
					.enabled(false)
					.sourceQueryTracking(true)
					.build();

			assertFalse(options.sourceQueryTrackingEnabled());
		}

		@Test
		@DisplayName(
			"should return true when both flags are true"
		)
		void shouldReturnTrueWhenBothTrue() {
			final TrafficRecordingOptions options =
				TrafficRecordingOptions.builder()
					.enabled(true)
					.sourceQueryTracking(true)
					.build();

			assertTrue(options.sourceQueryTrackingEnabled());
		}
	}

	@Nested
	@DisplayName("Builder")
	class BuilderTest {

		@Test
		@DisplayName("should override all fields")
		void shouldOverrideAllFields() {
			final TrafficRecordingOptions options =
				TrafficRecordingOptions.builder()
					.enabled(true)
					.sourceQueryTracking(true)
					.trafficMemoryBufferSizeInBytes(1024L)
					.trafficDiskBufferSizeInBytes(2048L)
					.exportFileChunkSizeInBytes(512L)
					.trafficSamplingPercentage(50)
					.trafficFlushIntervalInMilliseconds(
						30_000L
					)
					.build();

			assertTrue(options.enabled());
			assertTrue(options.sourceQueryTracking());
			assertEquals(
				1024L,
				options.trafficMemoryBufferSizeInBytes()
			);
			assertEquals(
				2048L,
				options.trafficDiskBufferSizeInBytes()
			);
			assertEquals(
				512L,
				options.exportFileChunkSizeInBytes()
			);
			assertEquals(
				50,
				options.trafficSamplingPercentage()
			);
			assertEquals(
				30_000L,
				options.trafficFlushIntervalInMilliseconds()
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
			final TrafficRecordingOptions source =
				TrafficRecordingOptions.builder()
					.enabled(true)
					.sourceQueryTracking(true)
					.trafficMemoryBufferSizeInBytes(2048L)
					.trafficDiskBufferSizeInBytes(4096L)
					.exportFileChunkSizeInBytes(1024L)
					.trafficSamplingPercentage(75)
					.trafficFlushIntervalInMilliseconds(
						10_000L
					)
					.build();

			final TrafficRecordingOptions copy =
				TrafficRecordingOptions.builder(source)
					.build();

			assertEquals(
				source.enabled(), copy.enabled()
			);
			assertEquals(
				source.sourceQueryTracking(),
				copy.sourceQueryTracking()
			);
			assertEquals(
				source.trafficMemoryBufferSizeInBytes(),
				copy.trafficMemoryBufferSizeInBytes()
			);
			assertEquals(
				source.trafficDiskBufferSizeInBytes(),
				copy.trafficDiskBufferSizeInBytes()
			);
			assertEquals(
				source.exportFileChunkSizeInBytes(),
				copy.exportFileChunkSizeInBytes()
			);
			assertEquals(
				source.trafficSamplingPercentage(),
				copy.trafficSamplingPercentage()
			);
			assertEquals(
				source.trafficFlushIntervalInMilliseconds(),
				copy.trafficFlushIntervalInMilliseconds()
			);
		}
	}
}
