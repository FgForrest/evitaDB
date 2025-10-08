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

package io.evitadb.api.requestResponse.system;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for WriteAheadLogVersionDescriptor and its nested interfaces.
 */
@DisplayName("WriteAheadLogVersionDescriptor")
class WriteAheadLogVersionDescriptorTest {
	/**
	 * Simple stub of TransactionChanges for testing.
	 */
	private record TxChange(
		OffsetDateTime commitTimestamp,
		int mutationCount,
		long mutationSizeInBytes
	) implements WriteAheadLogVersionDescriptor.TransactionChanges {
		@Override
		@Nonnull
		public String toString(@Nullable final OffsetDateTime processedTimestamp) {
			final Duration lag = processedTimestamp == null ? Duration.ZERO :
				processingLag(processedTimestamp);
			return "TxChange(commit=" + this.commitTimestamp + ", cnt=" + this.mutationCount +
				", size=" + this.mutationSizeInBytes + ", lag=" + lag.toMillis() + ")";
		}

		@Override
		@Nonnull
		public OffsetDateTime commitTimestamp() {
			return this.commitTimestamp;
		}
	}

	/**
	 * Simple container for testing aggregation and string formatting.
	 */
	private static final class TxContainer
		implements WriteAheadLogVersionDescriptor.TransactionChangesContainer<TxChange> {
		private final TxChange[] changes;

		private TxContainer(final TxChange... changes) {
			this.changes = changes;
		}

		@Override
		@Nonnull
		public TxChange[] getTransactionChanges() {
			return this.changes;
		}

		@Override
		public String toString() {
			return "Container(totalBytes=" + mutationSizeInBytes() +
				", totalMutations=" + mutationCount() + ")";
		}
	}

	@Test
	@DisplayName("shouldComputeProcessingLagWhenProcessedAfterCommit")
	void shouldComputeProcessingLagWhenProcessedAfterCommit() {
		final OffsetDateTime commit = OffsetDateTime.of(2025, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC);
		final OffsetDateTime processed = commit.plusMinutes(5).plusSeconds(30);
		final TxChange tx = new TxChange(commit, 1, 10);

		final Duration lag = tx.processingLag(processed);

		assertEquals(Duration.ofMinutes(5).plusSeconds(30), lag,
			"Processing lag must be the difference between processed and commit timestamps");
	}

	@Test
	@DisplayName("shouldSumMutationCountAndSizeInContainer")
	void shouldSumMutationCountAndSizeInContainer() {
		final TxChange tx1 = new TxChange(nowFixed().minusMinutes(1), 3, 100);
		final TxChange tx2 = new TxChange(nowFixed(), 2, 250);
		final TxContainer container = new TxContainer(tx1, tx2);

		assertEquals(5, container.mutationCount(), "Mutation count should be summed over all txs");
		assertEquals(350L, container.mutationSizeInBytes(),
			"Mutation size should be summed over all txs");
	}

	@Test
	@DisplayName("shouldReturnZeroCountsWhenNoTransactionsInContainer")
	void shouldReturnZeroCountsWhenNoTransactionsInContainer() {
		final TxContainer container = new TxContainer();
		assertEquals(0, container.mutationCount(), "Empty container should have zero mutations");
		assertEquals(0L, container.mutationSizeInBytes(),
			"Empty container should have zero mutation size");
	}

	@Test
	@DisplayName("shouldFormatToStringWithVersionTimestampAndTransactionCount")
	void shouldFormatToStringWithVersionTimestampAndTransactionCount() {
		final OffsetDateTime processed = OffsetDateTime.of(2025, 8, 20, 12, 34, 56, 0,
			ZoneOffset.ofHours(2));
		final TxContainer container = new TxContainer(
			new TxChange(processed.minusSeconds(10), 1, 10),
			new TxChange(processed.minusSeconds(20), 2, 20)
		);
		final WriteAheadLogVersionDescriptor descriptor = new WriteAheadLogVersionDescriptor(
			42L, processed, container
		);

		final String s = descriptor.toString();
		final String formattedTs = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(processed);

		assertTrue(s.contains("Catalog version: 42"), "Version should be present in toString");
		assertTrue(s.contains("processed at " + formattedTs),
			"Processed timestamp should be formatted in ISO_OFFSET_DATE_TIME");
		assertTrue(s.contains(" with 2 transactions "),
			"Transaction count should match the number of provided transactions");
		assertTrue(s.endsWith(container.toString()),
			"toString should append container's toString output");
	}

	private static OffsetDateTime nowFixed() {
		return OffsetDateTime.of(2025, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);
	}
}
