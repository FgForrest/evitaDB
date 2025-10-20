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

import static org.junit.jupiter.api.Assertions.assertEquals;

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
		long walSizeInBytes
	) implements WriteAheadLogVersionDescriptor.TransactionChanges {
		@Override
		@Nonnull
		public String toString(@Nullable final OffsetDateTime processedTimestamp) {
			final Duration lag = processedTimestamp == null ? Duration.ZERO :
				processingLag(processedTimestamp);
			return "TxChange(commit=" + this.commitTimestamp + ", cnt=" + this.mutationCount +
				", size=" + this.walSizeInBytes + ", lag=" + lag.toMillis() + ")";
		}

		@Override
		@Nonnull
		public OffsetDateTime commitTimestamp() {
			return this.commitTimestamp;
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

	private static OffsetDateTime nowFixed() {
		return OffsetDateTime.of(2025, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);
	}
}
