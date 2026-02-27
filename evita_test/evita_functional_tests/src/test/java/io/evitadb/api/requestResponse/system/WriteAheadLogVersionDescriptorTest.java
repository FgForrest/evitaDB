/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.api.requestResponse.system;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link WriteAheadLogVersionDescriptor} verifying
 * processing lag computation and toString formatting.
 *
 * @author evitaDB
 */
@DisplayName("WriteAheadLogVersionDescriptor")
class WriteAheadLogVersionDescriptorTest {

	/**
	 * Simple stub of {@link WriteAheadLogVersionDescriptor.TransactionChanges}
	 * for testing the default `processingLag` method and descriptor formatting.
	 */
	private record TxChange(
		@Nonnull OffsetDateTime commitTimestamp,
		int mutationCount,
		long walSizeInBytes
	) implements WriteAheadLogVersionDescriptor.TransactionChanges {

		@Override
		@Nonnull
		public String toString(
			@Nullable final OffsetDateTime processedTimestamp
		) {
			final Duration lag = processedTimestamp == null
				? Duration.ZERO
				: processingLag(processedTimestamp);
			return "TxChange(commit=" + this.commitTimestamp
				+ ", cnt=" + this.mutationCount
				+ ", size=" + this.walSizeInBytes
				+ ", lag=" + lag.toMillis() + ")";
		}

		@Override
		@Nonnull
		public OffsetDateTime commitTimestamp() {
			return this.commitTimestamp;
		}
	}

	@Nested
	@DisplayName("Processing lag computation")
	class ProcessingLagTest {

		@Test
		@DisplayName(
			"Should compute positive lag when processed after commit"
		)
		void shouldComputePositiveLagWhenProcessedAfterCommit() {
			final OffsetDateTime commit = OffsetDateTime.of(
				2025, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC
			);
			final OffsetDateTime processed =
				commit.plusMinutes(5).plusSeconds(30);
			final TxChange tx = new TxChange(commit, 1, 10);

			final Duration lag = tx.processingLag(processed);

			assertEquals(
				Duration.ofMinutes(5).plusSeconds(30),
				lag,
				"Processing lag must equal the difference "
					+ "between processed and commit timestamps"
			);
		}

		@Test
		@DisplayName(
			"Should return zero duration when processed at commit time"
		)
		void shouldReturnZeroDurationWhenProcessedAtCommitTime() {
			final OffsetDateTime commit = OffsetDateTime.of(
				2025, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC
			);
			final TxChange tx = new TxChange(commit, 5, 1024);

			final Duration lag = tx.processingLag(commit);

			assertEquals(
				Duration.ZERO,
				lag,
				"Processing lag must be zero when processed "
					+ "at the same instant as commit"
			);
		}

		@Test
		@DisplayName(
			"Should compute negative lag when processed before commit"
		)
		void shouldComputeNegativeLagWhenProcessedBeforeCommit() {
			final OffsetDateTime commit = OffsetDateTime.of(
				2025, 1, 1, 10, 5, 0, 0, ZoneOffset.UTC
			);
			// processed 5 minutes BEFORE commit (clock skew)
			final OffsetDateTime processed =
				commit.minusMinutes(5);
			final TxChange tx = new TxChange(commit, 3, 512);

			final Duration lag = tx.processingLag(processed);

			assertTrue(
				lag.isNegative(),
				"Processing lag must be negative when "
					+ "processed before commit"
			);
			assertEquals(
				Duration.ofMinutes(-5),
				lag,
				"Processing lag must be -5 minutes"
			);
		}
	}

	@Nested
	@DisplayName("Descriptor toString")
	class ToStringTest {

		@Test
		@DisplayName("Should contain version number")
		void shouldContainVersionNumber() {
			final WriteAheadLogVersionDescriptor descriptor =
				createDescriptor(42, false);

			final String result = descriptor.toString();

			assertTrue(
				result.contains("42"),
				"toString must contain the version number"
			);
		}

		@Test
		@DisplayName("Should contain formatted timestamp")
		void shouldContainFormattedTimestamp() {
			final OffsetDateTime timestamp = OffsetDateTime.of(
				2025, 6, 15, 14, 30, 0, 0, ZoneOffset.UTC
			);
			final WriteAheadLogVersionDescriptor descriptor =
				new WriteAheadLogVersionDescriptor(
					1L,
					UUID.randomUUID(),
					timestamp,
					new TxChange(timestamp, 1, 100),
					false
				);

			final String result = descriptor.toString();

			assertTrue(
				result.contains("2025-06-15T14:30:00Z"),
				"toString must contain the ISO-formatted "
					+ "processed timestamp"
			);
		}

		@Test
		@DisplayName("Should contain transaction ID")
		void shouldContainTransactionId() {
			final UUID txId =
				UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
			final OffsetDateTime now = OffsetDateTime.of(
				2025, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC
			);
			final WriteAheadLogVersionDescriptor descriptor =
				new WriteAheadLogVersionDescriptor(
					1L,
					txId,
					now,
					new TxChange(now, 1, 100),
					false
				);

			final String result = descriptor.toString();

			assertTrue(
				result.contains(txId.toString()),
				"toString must contain the transaction UUID"
			);
		}

		@Test
		@DisplayName(
			"Should include 'reversible' label when reversible is true"
		)
		void shouldIncludeReversibleLabelWhenTrue() {
			final WriteAheadLogVersionDescriptor descriptor =
				createDescriptor(1, true);

			final String result = descriptor.toString();

			assertTrue(
				result.contains("reversible"),
				"toString must contain 'reversible' when "
					+ "the flag is true"
			);
		}

		@Test
		@DisplayName(
			"Should omit 'reversible' label when reversible is false"
		)
		void shouldOmitReversibleLabelWhenFalse() {
			final WriteAheadLogVersionDescriptor descriptor =
				createDescriptor(1, false);

			final String result = descriptor.toString();

			assertFalse(
				result.contains("reversible"),
				"toString must not contain 'reversible' "
					+ "when the flag is false"
			);
		}
	}

	/**
	 * Creates a {@link WriteAheadLogVersionDescriptor} with sensible
	 * defaults for fields not relevant to the specific test.
	 *
	 * @param version    the catalog version number
	 * @param reversible whether the transaction is reversible
	 * @return a new descriptor instance
	 */
	@Nonnull
	private static WriteAheadLogVersionDescriptor createDescriptor(
		long version,
		boolean reversible
	) {
		final OffsetDateTime now = OffsetDateTime.of(
			2025, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC
		);
		return new WriteAheadLogVersionDescriptor(
			version,
			UUID.randomUUID(),
			now,
			new TxChange(now, 1, 100),
			reversible
		);
	}
}
