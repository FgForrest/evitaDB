/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.cdc;

import io.evitadb.api.exception.TemporalDataNotAvailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies how a failed read of the write-ahead log on behalf of a lagging change capture subscriber is classified
 * against the WAL retention - the part both shared publishers delegate to {@link WalReadResult}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("WAL read result: classifying a failed catch-up read")
@Tag(ENGINE)
@Tag(CDC)
class WalReadResultTest {
	/**
	 * The position every read in these tests started at.
	 */
	private static final WalPointer READ_START = new WalPointer(10L, 0);

	@Test
	@DisplayName("should report a position the retention removed during the read as temporal data not available")
	void shouldReportAPositionRemovedDuringTheReadAsTemporalDataNotAvailable() {
		final RuntimeException readFailure = new IllegalStateException("the read failed");

		final RuntimeException classified = WalReadResult.classifyReadFailure(readFailure, READ_START, () -> 11L);

		final TemporalDataNotAvailableException notAvailable = assertInstanceOf(
			TemporalDataNotAvailableException.class, classified
		);
		assertEquals(11L, notAvailable.getCatalogVersion());
		assertSame(readFailure, notAvailable.getCause());
	}

	@Test
	@DisplayName("should rethrow the read failure when the position is still retained")
	void shouldRethrowTheReadFailureWhenThePositionIsStillRetained() {
		final RuntimeException readFailure = new IllegalStateException("the read failed");

		assertSame(readFailure, WalReadResult.classifyReadFailure(readFailure, READ_START, () -> 10L));
		assertSame(readFailure, WalReadResult.classifyReadFailure(readFailure, READ_START, () -> -1L));
	}

	@Test
	@DisplayName("should keep the read failure and attach a failing retention re-check to it")
	void shouldKeepTheReadFailureAndAttachAFailingRetentionRecheck() {
		final RuntimeException readFailure = new IllegalStateException("the read failed");
		final RuntimeException recheckFailure = new IllegalStateException("the WAL folder could not be listed");

		final RuntimeException classified = WalReadResult.classifyReadFailure(
			readFailure, READ_START, () -> {
				throw recheckFailure;
			}
		);

		assertSame(
			readFailure,
			classified,
			"The read failure is the only account of what went wrong with the read - a failure of the retention " +
				"re-check that follows it must not take its place."
		);
		assertArrayEquals(new Throwable[]{recheckFailure}, readFailure.getSuppressed());
	}

}
