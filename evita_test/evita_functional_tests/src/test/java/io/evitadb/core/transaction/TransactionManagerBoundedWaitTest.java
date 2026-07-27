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

package io.evitadb.core.transaction;

import io.evitadb.api.exception.TransactionTimedOutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicLong;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the bounded live-version wait in
 * {@link TransactionManager#waitUntilVersionReaches}.
 *
 * The wait used to be an unbounded `Thread.onSpinWait()` loop: when catalog-version propagation to
 * the live view stalled, the trunk-incorporation thread burned a full core forever and the affected
 * commit-progress record was never completed, leaving the client awaiting that commit stuck
 * indefinitely. The contract under test here is that the wait is **bounded**: it returns promptly
 * once the version is reached and throws {@link TransactionTimedOutException} — which every
 * caller translates into an exceptional completion of the commit-progress record or a watchdog
 * reschedule — once the deadline expires.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("TransactionManager — bounded wait for live-version propagation")
@Tag(ENGINE)
@Tag(TRANSACTION)
class TransactionManagerBoundedWaitTest {

	@Test
	@Timeout(10)
	@DisplayName("should throw TransactionTimedOutException instead of waiting forever when the live version never reaches the target")
	void shouldThrowTransactionTimedOutWhenLiveVersionNeverReachesTarget() {
		final TransactionTimedOutException exception = assertThrows(
			TransactionTimedOutException.class,
			() -> TransactionManager.waitUntilVersionReaches(
				() -> 1L,
				2L,
				50L,
				"testCatalog"
			),
			"A live version that never reaches the target must end in a timeout, not an endless spin."
		);
		assertTrue(
			exception.getMessage().contains("did not reach version 2"),
			"Timeout message should name the unreached version: " + exception.getMessage()
		);
		assertTrue(
			exception.getMessage().contains("stuck at version 1"),
			"Timeout message should name the stuck version: " + exception.getMessage()
		);
	}

	@Test
	@Timeout(10)
	@DisplayName("should return immediately when the live version already reached the target")
	void shouldReturnImmediatelyWhenVersionAlreadyReached() {
		assertDoesNotThrow(
			() -> TransactionManager.waitUntilVersionReaches(
				() -> 5L,
				5L,
				50L,
				"testCatalog"
			)
		);
	}

	@Test
	@Timeout(10)
	@DisplayName("should return without exception when the live version reaches the target before the deadline")
	void shouldReturnWhenVersionReachesTargetBeforeDeadline() {
		// the supplier advances by one on every poll, so the target is reached after a few
		// spin iterations - far inside both the spin window and the generous deadline
		final AtomicLong liveVersion = new AtomicLong(0L);
		assertDoesNotThrow(
			() -> TransactionManager.waitUntilVersionReaches(
				liveVersion::incrementAndGet,
				10L,
				60_000L,
				"testCatalog"
			)
		);
		assertTrue(
			liveVersion.get() >= 10L,
			"Supplier must have been polled until the target version was observed."
		);
	}
}
