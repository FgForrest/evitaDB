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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link StorageOptions} record and its builder.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("StorageOptions")
class StorageOptionsTest {

	@Test
	@DisplayName("should initialize all defaults via builder")
	void shouldInitDefaults() {
		final StorageOptions options =
			StorageOptions.builder().build();

		assertNotNull(options.storageDirectory());
		assertNotNull(options.workDirectory());
		assertTrue(options.maxOpenedReadHandles() > 0);
		assertEquals(5, options.lockTimeoutSeconds());
		assertEquals(5, options.waitOnCloseSeconds());
		assertEquals(
			2_097_152, options.outputBufferSize()
		);
		assertTrue(options.computeCRC32C());
		assertFalse(options.compress());
	}

	@Nested
	@DisplayName("temporary() factory")
	class TemporaryFactoryTest {

		@Test
		@DisplayName(
			"should create with temp directory paths"
		)
		void shouldCreateWithTempDirectoryPaths() {
			final StorageOptions options =
				StorageOptions.temporary();

			assertTrue(
				options.storageDirectory().toString()
					.contains("evita")
			);
			assertTrue(
				options.workDirectory().toString()
					.contains("evita")
			);
		}

		@Test
		@DisplayName(
			"should disable sync writes for testing"
		)
		void shouldDisableSyncWritesForTesting() {
			final StorageOptions options =
				StorageOptions.temporary();

			assertFalse(options.syncWrites());
		}

		@Test
		@DisplayName("should enable CRC32C checking")
		void shouldEnableCrc32cChecking() {
			final StorageOptions options =
				StorageOptions.temporary();

			assertTrue(options.computeCRC32C());
		}
	}

	@Nested
	@DisplayName("maxOpenedReadHandlesOrDefault")
	class MaxOpenedReadHandlesTest {

		@Test
		@DisplayName(
			"should return value when explicitly set"
		)
		void shouldReturnValueWhenExplicitlySet() {
			final StorageOptions options =
				StorageOptions.builder()
					.maxOpenedReadHandles(42)
					.build();

			assertEquals(
				42, options.maxOpenedReadHandlesOrDefault()
			);
		}

		@Test
		@DisplayName(
			"should return default when using builder default"
		)
		void shouldReturnDefaultFromBuilder() {
			final StorageOptions options =
				StorageOptions.builder().build();

			assertEquals(
				StorageOptions
					.DEFAULT_MAX_OPENED_READ_HANDLES,
				options.maxOpenedReadHandlesOrDefault()
			);
		}
	}

	@Nested
	@DisplayName("Work directory randomization")
	class WorkDirectoryRandomizationTest {

		@Test
		@DisplayName(
			"should create unique work directories"
		)
		void shouldCreateUniqueWorkDirectories() {
			final StorageOptions options1 =
				StorageOptions.builder().build();
			final StorageOptions options2 =
				StorageOptions.builder().build();

			assertNotEquals(
				options1.workDirectory(),
				options2.workDirectory(),
				"Each builder invocation should produce " +
					"a unique work directory"
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
			final StorageOptions source =
				StorageOptions.builder()
					.storageDirectory(
						Path.of("/tmp/custom-data")
					)
					.lockTimeoutSeconds(10)
					.waitOnCloseSeconds(15)
					.outputBufferSize(4_194_304)
					.maxOpenedReadHandles(50)
					.syncWrites(false)
					.compress(true)
					.computeCRC32(false)
					.minimalActiveRecordShare(0.75)
					.fileSizeCompactionThresholdBytes(
						200_000_000L
					)
					.timeTravelEnabled(true)
					.build();

			final StorageOptions copy =
				StorageOptions.builder(source).build();

			assertEquals(
				Path.of("/tmp/custom-data"),
				copy.storageDirectory()
			);
			assertEquals(10, copy.lockTimeoutSeconds());
			assertEquals(15, copy.waitOnCloseSeconds());
			assertEquals(
				4_194_304, copy.outputBufferSize()
			);
			assertEquals(
				50, copy.maxOpenedReadHandles()
			);
			assertFalse(copy.syncWrites());
			assertTrue(copy.compress());
			assertFalse(copy.computeCRC32C());
			assertEquals(
				0.75, copy.minimalActiveRecordShare()
			);
			assertEquals(
				200_000_000L,
				copy.fileSizeCompactionThresholdBytes()
			);
			assertTrue(copy.timeTravelEnabled());
		}
	}

	@Nested
	@DisplayName("Default constants")
	class DefaultConstantsTest {

		@Test
		@DisplayName(
			"should have correct default compaction share"
		)
		void shouldHaveCorrectDefaultCompactionShare() {
			final StorageOptions options =
				StorageOptions.builder().build();

			assertEquals(
				StorageOptions
					.DEFAULT_MINIMAL_ACTIVE_RECORD_SHARE,
				options.minimalActiveRecordShare()
			);
		}

		@Test
		@DisplayName(
			"should have time travel disabled by default"
		)
		void shouldHaveTimeTravelDisabledByDefault() {
			final StorageOptions options =
				StorageOptions.builder().build();

			assertFalse(options.timeTravelEnabled());
		}
	}
}
