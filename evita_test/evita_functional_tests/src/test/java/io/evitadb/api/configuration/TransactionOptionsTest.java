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

import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.mutation.conflict.GranularConflictPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.TRANSACTION;

/**
 * Tests for {@link TransactionOptions} record and its builder.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("TransactionOptions")
@Tag(CONTRACT)
@Tag(MANAGEMENT)
@Tag(TRANSACTION)
class TransactionOptionsTest {

	@Nested
	@DisplayName("Default constructor")
	class DefaultConstructorTest {

		@Test
		@DisplayName("should use default values")
		void shouldUseDefaultValues() {
			final TransactionOptions options =
				new TransactionOptions();

			assertEquals(
				TransactionOptions.DEFAULT_TX_DIRECTORY,
				options.transactionWorkDirectory()
			);
			assertEquals(
				TransactionOptions
					.DEFAULT_TRANSACTION_MEMORY_BUFFER_LIMIT_SIZE,
				options.transactionMemoryBufferLimitSizeBytes()
			);
			assertEquals(
				TransactionOptions
					.DEFAULT_TRANSACTION_MEMORY_REGION_COUNT,
				options.transactionMemoryRegionCount()
			);
			assertEquals(
				TransactionOptions.DEFAULT_WAL_SIZE_BYTES,
				options.walFileSizeBytes()
			);
			assertEquals(
				TransactionOptions.DEFAULT_WAL_FILE_COUNT_KEPT,
				options.walFileCountKept()
			);
			assertEquals(
				TransactionOptions
					.DEFAULT_WAIT_FOR_TRANSACTION_ACCEPTANCE,
				options.waitForTransactionAcceptanceInMillis()
			);
			assertEquals(
				TransactionOptions.DEFAULT_FLUSH_FREQUENCY,
				options.flushFrequencyInMillis()
			);
			assertEquals(
				TransactionOptions
					.DEFAULT_CONFLICT_RING_BUFFER_SIZE,
				options.conflictRingBufferSize()
			);
			assertEquals(
				TransactionOptions.DEFAULT_CONFLICT_RESOLUTION,
				options.conflictPolicy()
			);
		}
	}

	@Nested
	@DisplayName("Builder")
	class BuilderTest {

		@Test
		@DisplayName("should build with defaults")
		void shouldBuildWithDefaults() {
			final TransactionOptions options =
				TransactionOptions.builder().build();

			assertEquals(
				TransactionOptions.DEFAULT_TX_DIRECTORY,
				options.transactionWorkDirectory()
			);
			assertEquals(
				TransactionOptions
					.DEFAULT_TRANSACTION_MEMORY_BUFFER_LIMIT_SIZE,
				options.transactionMemoryBufferLimitSizeBytes()
			);
		}

		@Test
		@DisplayName(
			"should override transaction work directory"
		)
		void shouldOverrideTransactionWorkDirectory() {
			final Path customDir = Paths.get("/tmp/custom");
			final TransactionOptions options =
				TransactionOptions.builder()
					.transactionWorkDirectory(customDir)
					.build();

			assertEquals(customDir, options.transactionWorkDirectory());
		}

		@Test
		@DisplayName("should override memory buffer size")
		void shouldOverrideMemoryBufferSize() {
			final TransactionOptions options =
				TransactionOptions.builder()
					.transactionMemoryBufferLimitSizeBytes(
						8_388_608L
					)
					.build();

			assertEquals(
				8_388_608L,
				options.transactionMemoryBufferLimitSizeBytes()
			);
		}

		@Test
		@DisplayName("should override WAL file size")
		void shouldOverrideWalFileSize() {
			final TransactionOptions options =
				TransactionOptions.builder()
					.walFileSizeBytes(4_194_304L)
					.build();

			assertEquals(
				4_194_304L, options.walFileSizeBytes()
			);
		}

		@Test
		@DisplayName("should override WAL file count kept")
		void shouldOverrideWalFileCountKept() {
			final TransactionOptions options =
				TransactionOptions.builder()
					.walFileCountKept(4)
					.build();

			assertEquals(4, options.walFileCountKept());
		}

		@Test
		@DisplayName(
			"should override wait for transaction acceptance via millis method"
		)
		void shouldOverrideWaitForTransactionAcceptanceInMillis() {
			final TransactionOptions options =
				TransactionOptions.builder()
					.waitForTransactionAcceptanceInMillis(120_000L)
					.build();

			assertEquals(
				120_000L, options.waitForTransactionAcceptanceInMillis()
			);
		}

		@Test
		@DisplayName(
			"should override flush frequency via millis method"
		)
		void shouldOverrideFlushFrequencyInMillis() {
			final TransactionOptions options =
				TransactionOptions.builder()
					.flushFrequencyInMillis(500L)
					.build();

			assertEquals(
				500L, options.flushFrequencyInMillis()
			);
		}

		@Test
		@DisplayName(
			"should delegate deprecated flushFrequency to " +
				"flushFrequencyInMillis"
		)
		@SuppressWarnings("deprecation")
		void shouldDelegateDeprecatedFlushFrequency() {
			final TransactionOptions options =
				TransactionOptions.builder()
					.flushFrequency(750L)
					.build();

			assertEquals(
				750L, options.flushFrequencyInMillis()
			);
		}

		@Test
		@DisplayName(
			"should set conflict resolution with granularity"
		)
		void shouldSetConflictResolutionWithGranularity() {
			final TransactionOptions options =
				TransactionOptions.builder()
					.conflictResolution(
						ConflictPolicy.ENTITY,
						GranularConflictPolicy.ENTITY_ATTRIBUTE
					)
					.build();

			assertEquals(
				ConflictPolicy.ENTITY,
				options.conflictPolicy().policy()
			);
			assertTrue(
				options.conflictPolicy().granularity()
					.contains(GranularConflictPolicy.ENTITY_ATTRIBUTE)
			);
		}

		@Test
		@DisplayName(
			"should configure last writer wins through a NONE resolution"
		)
		void shouldSetLastWriterWins() {
			final TransactionOptions options =
				TransactionOptions.builder()
					.conflictResolution(ConflictPolicy.NONE)
					.build();

			assertEquals(
				ConflictPolicy.NONE,
				options.conflictPolicy().policy()
			);
		}

		@Test
		@DisplayName(
			"should override conflict ring buffer size"
		)
		void shouldOverrideConflictRingBufferSize() {
			final TransactionOptions options =
				TransactionOptions.builder()
					.conflictRingBufferSize(1024)
					.build();

			assertEquals(
				1024, options.conflictRingBufferSize()
			);
		}
	}

	@Nested
	@DisplayName("Copy constructor")
	class CopyConstructorTest {

		@Test
		@DisplayName(
			"should copy all scalar fields from source"
		)
		void shouldCopyScalarFieldsFromSource() {
			final Path customDir = Paths.get("/tmp/custom");
			final TransactionOptions source =
				TransactionOptions.builder()
					.transactionWorkDirectory(customDir)
					.transactionMemoryBufferLimitSizeBytes(
						1_048_576L
					)
					.transactionMemoryRegionCount(64)
					.walFileSizeBytes(4_194_304L)
					.walFileCountKept(2)
					.waitForTransactionAcceptanceInMillis(90_000L)
					.flushFrequencyInMillis(250L)
					.conflictRingBufferSize(512)
					.build();

			final TransactionOptions copy =
				TransactionOptions.builder(source).build();

			assertEquals(
				customDir,
				copy.transactionWorkDirectory()
			);
			assertEquals(
				1_048_576L,
				copy.transactionMemoryBufferLimitSizeBytes()
			);
			assertEquals(
				64, copy.transactionMemoryRegionCount()
			);
			assertEquals(
				4_194_304L, copy.walFileSizeBytes()
			);
			assertEquals(2, copy.walFileCountKept());
			assertEquals(
				90_000L,
				copy.waitForTransactionAcceptanceInMillis()
			);
			assertEquals(
				250L, copy.flushFrequencyInMillis()
			);
			assertEquals(
				512, copy.conflictRingBufferSize()
			);
		}

		@Test
		@DisplayName(
			"should copy conflict resolution from source"
		)
		void shouldCopyConflictPolicyFromSource() {
			final TransactionOptions source =
				TransactionOptions.builder()
					.conflictResolution(ConflictPolicy.CATALOG)
					.build();

			final TransactionOptions copy =
				TransactionOptions.builder(source).build();

			assertEquals(
				new ConflictResolution(ConflictPolicy.CATALOG),
				copy.conflictPolicy()
			);
		}
	}

	@Nested
	@DisplayName("Temporary factory")
	class TemporaryFactoryTest {

		@Test
		@DisplayName(
			"should create temporary options with reduced " +
				"values"
		)
		void shouldCreateTemporaryOptions() {
			final TransactionOptions options =
				TransactionOptions.temporary();

			assertEquals(
				TransactionOptions.DEFAULT_TX_DIRECTORY,
				options.transactionWorkDirectory()
			);
			assertEquals(
				1_048_576L,
				options.transactionMemoryBufferLimitSizeBytes()
			);
			assertEquals(
				32, options.transactionMemoryRegionCount()
			);
			assertEquals(
				8_388_608L, options.walFileSizeBytes()
			);
			assertEquals(1, options.walFileCountKept());
			assertEquals(
				100L,
				options.waitForTransactionAcceptanceInMillis()
			);
			assertEquals(
				100L, options.flushFrequencyInMillis()
			);
			assertEquals(
				256, options.conflictRingBufferSize()
			);
		}
	}

	@Nested
	@DisplayName("Constructor null handling")
	class NullHandlingTest {

		@Test
		@DisplayName(
			"should use default directory when null is passed"
		)
		void shouldUseDefaultDirectoryWhenNull() {
			final TransactionOptions options =
				new TransactionOptions(
					null,
					TransactionOptions
						.DEFAULT_TRANSACTION_MEMORY_BUFFER_LIMIT_SIZE,
					TransactionOptions
						.DEFAULT_TRANSACTION_MEMORY_REGION_COUNT,
					TransactionOptions.DEFAULT_WAL_SIZE_BYTES,
					TransactionOptions
						.DEFAULT_WAL_FILE_COUNT_KEPT,
					TransactionOptions
						.DEFAULT_WAIT_FOR_TRANSACTION_ACCEPTANCE,
					TransactionOptions.DEFAULT_FLUSH_FREQUENCY,
					TransactionOptions.DEFAULT_CHECKPOINT_INTERVAL,
					TransactionOptions
						.DEFAULT_CONFLICT_RING_BUFFER_SIZE,
					TransactionOptions.DEFAULT_CONFLICT_RESOLUTION
				);

			assertEquals(
				TransactionOptions.DEFAULT_TX_DIRECTORY,
				options.transactionWorkDirectory()
			);
		}
	}
}
