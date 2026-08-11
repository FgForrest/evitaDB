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
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.MANAGEMENT;

/**
 * Tests for {@link StorageOptions} record and its builder.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("StorageOptions")
@Tag(CONTRACT)
@Tag(MANAGEMENT)
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
					.timeTravelSizeLimitBytes(2_000_000_000L)
					.minCompactionIntervalMilliseconds(600L)
					.maxWasteActiveShare(0.1)
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
			assertEquals(2_000_000_000L, copy.timeTravelSizeLimitBytes());
			assertEquals(600L, copy.minCompactionIntervalMilliseconds());
			assertEquals(0.1, copy.maxWasteActiveShare());
		}
	}

	@Nested
	@DisplayName("Time travel size limit")
	class TimeTravelSizeLimitTest {

		@Test
		@DisplayName("should default timeTravelSizeLimitBytes to 1GB")
		void shouldDefaultTimeTravelSizeLimitToOneGigabyte() {
			final StorageOptions options = StorageOptions.builder().build();

			assertEquals(1_073_741_824L, StorageOptions.DEFAULT_TIME_TRAVEL_SIZE_LIMIT_BYTES);
			assertEquals(1_073_741_824L, options.timeTravelSizeLimitBytes());
		}

		@Test
		@DisplayName("should apply the default through every previous-arity constructor")
		void shouldApplyDefaultThroughPreviousArityConstructors() {
			// callers compiled against the pre-timeTravelSizeLimitBytes signatures must get the bounded default rather
			// than an accidental zero, which would silently switch time travel off for them
			assertEquals(
				StorageOptions.DEFAULT_TIME_TRAVEL_SIZE_LIMIT_BYTES,
				new StorageOptions().timeTravelSizeLimitBytes()
			);
			assertEquals(
				StorageOptions.DEFAULT_TIME_TRAVEL_SIZE_LIMIT_BYTES,
				StorageOptions.temporary().timeTravelSizeLimitBytes()
			);
			assertEquals(
				StorageOptions.DEFAULT_TIME_TRAVEL_SIZE_LIMIT_BYTES,
				new StorageOptions(
					null, null, 5, 5, 2_097_152, 10, true, false, true,
					0.5, 100L, true
				).timeTravelSizeLimitBytes()
			);
			assertEquals(
				StorageOptions.DEFAULT_TIME_TRAVEL_SIZE_LIMIT_BYTES,
				new StorageOptions(
					null, null, 5, 5, 2_097_152, 10, true, false, true,
					0.5, 100L, true, 600L, 0.1
				).timeTravelSizeLimitBytes()
			);
		}

		@Test
		@DisplayName("should carry a negative limit through unchanged as the opt-out from bounding")
		void shouldCarryNegativeLimitThroughUnchanged() {
			final StorageOptions options = StorageOptions.builder()
				.timeTravelSizeLimitBytes(-1L)
				.build();

			assertEquals(-1L, options.timeTravelSizeLimitBytes());
			assertEquals(-1L, StorageOptions.builder(options).build().timeTravelSizeLimitBytes());
		}
	}

	@Nested
	@DisplayName("Compaction cadence knobs")
	class CompactionCadenceTest {

		@Test
		@DisplayName("should default minCompactionIntervalMilliseconds to 1 minute")
		void shouldDefaultMinCompactionIntervalMillisecondsToOneMinute() {
			final StorageOptions options = StorageOptions.builder().build();

			assertEquals(60_000L, StorageOptions.DEFAULT_MIN_COMPACTION_INTERVAL_MILLISECONDS);
			assertEquals(60_000L, options.minCompactionIntervalMilliseconds());
		}

		@Test
		@DisplayName("should default maxWasteActiveShare to 0.1, strictly below minimalActiveRecordShare's default")
		void shouldDefaultMaxWasteActiveShareBelowMinimalActiveRecordShare() {
			final StorageOptions options = StorageOptions.builder().build();

			assertEquals(0.1, StorageOptions.DEFAULT_MAX_WASTE_ACTIVE_SHARE);
			assertEquals(0.1, options.maxWasteActiveShare());
			// the interval only binds when maxWaste < A - verify the default pairing actually satisfies that
			assertTrue(options.maxWasteActiveShare() < options.minimalActiveRecordShare());
		}

		@Test
		@DisplayName("should allow overriding both knobs independently via the builder")
		void shouldOverrideBothKnobsIndependently() {
			final StorageOptions options = StorageOptions.builder()
				.minCompactionIntervalMilliseconds(600L)
				.maxWasteActiveShare(0.1)
				.build();

			assertEquals(600L, options.minCompactionIntervalMilliseconds());
			assertEquals(0.1, options.maxWasteActiveShare());
		}

		@Test
		@DisplayName("should clamp maxWasteActiveShare to minimalActiveRecordShare when only the latter is customized via the builder (BWC regression guard)")
		void shouldClampMaxWasteActiveShareWhenOnlyMinimalActiveRecordShareIsCustomized() {
			// builder() leaves maxWasteActiveShare at its static 0.1 default; overriding only
			// minimalActiveRecordShare below that (e.g. 0.01) must not leave maxWasteActiveShare stuck above it
			final StorageOptions options = StorageOptions.builder()
				.minimalActiveRecordShare(0.01)
				.build();

			assertEquals(0.01, options.minimalActiveRecordShare());
			assertEquals(0.01, options.maxWasteActiveShare());
		}

		@Test
		@DisplayName("maxWasteActiveShare should never exceed minimalActiveRecordShare, however constructed")
		void shouldNeverExceedMinimalActiveRecordShareInvariant() {
			final StorageOptions options = StorageOptions.builder()
				.minimalActiveRecordShare(0.2)
				.maxWasteActiveShare(0.9) // deliberately "wrong" - higher than A
				.build();

			assertTrue(options.maxWasteActiveShare() <= options.minimalActiveRecordShare());
			assertEquals(0.2, options.maxWasteActiveShare());
		}
	}

	@Nested
	@DisplayName("Previous-arity constructor (binary compatibility)")
	class PreviousArityConstructorTest {

		@SuppressWarnings("removal")
		@Test
		@DisplayName("should default minCompactionIntervalMilliseconds and clamp maxWasteActiveShare to the caller's minimalActiveRecordShare")
		void shouldDefaultNewKnobsForPreviousArityCallers() {
			final StorageOptions options = new StorageOptions(
				Path.of("/tmp/custom-data"),
				Path.of("/tmp/custom-work"),
				10, 15, 4_194_304, 50,
				false, true, false,
				0.75, 200_000_000L, true
			);

			assertEquals(
				StorageOptions.DEFAULT_MIN_COMPACTION_INTERVAL_MILLISECONDS,
				options.minCompactionIntervalMilliseconds()
			);
			// 0.75 > the static DEFAULT_MAX_WASTE_ACTIVE_SHARE (0.1), so the clamp is a no-op here
			assertEquals(
				StorageOptions.DEFAULT_MAX_WASTE_ACTIVE_SHARE,
				options.maxWasteActiveShare()
			);
			// unaffected fields still carry through correctly
			assertEquals(0.75, options.minimalActiveRecordShare());
			assertEquals(200_000_000L, options.fileSizeCompactionThresholdBytes());
			assertTrue(options.timeTravelEnabled());
		}

		@SuppressWarnings("removal")
		@Test
		@DisplayName("should clamp maxWasteActiveShare down when the caller's minimalActiveRecordShare is below the static default (BWC regression guard)")
		void shouldClampMaxWasteActiveShareBelowStaticDefault() {
			// a caller with a custom, aggressive minimalActiveRecordShare (e.g. 0.01) must NOT end up with
			// maxWasteActiveShare pinned at the static 0.1 default, or compaction would fire far more eagerly
			// than before (active < 0.1 instead of active < 0.01) regardless of minCompactionIntervalMilliseconds
			final StorageOptions options = new StorageOptions(
				Path.of("/tmp/custom-data"),
				Path.of("/tmp/custom-work"),
				10, 15, 4_194_304, 50,
				false, true, false,
				0.01, 1_000_000L, false
			);

			assertEquals(0.01, options.minimalActiveRecordShare());
			assertEquals(0.01, options.maxWasteActiveShare());
			assertTrue(options.maxWasteActiveShare() <= options.minimalActiveRecordShare());
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
