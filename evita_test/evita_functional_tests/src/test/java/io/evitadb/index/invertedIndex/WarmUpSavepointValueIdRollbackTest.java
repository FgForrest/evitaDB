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

package io.evitadb.index.invertedIndex;

import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that the value id machinery a substring accelerator installs on a shared value tree rewinds its
 * non-transactional (WARM_UP) writes when the {@link WarmUpSavepoint} bracketing them is rolled back.
 *
 * The value ids arrived with the trigram substring index and are minted on the delegate branch of
 * {@link ValueIdAllocator#allocate()} — the branch the bulk loader takes, where there is no diff layer to discard and
 * the counter therefore advances in place. Without journaling, a rolled-back entity would leave the high-water mark
 * advanced past ids that no value carries.
 *
 * The savepoint is opened directly rather than through `LocalMutationExecutorCollector`, because what is under test is
 * the allocator's own journaling and not the bracket — an open savepoint records the same thing however it came to be
 * open.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(ENGINE)
@Tag(TRANSACTION)
@DisplayName("Warm-up savepoint rollback of value ids")
class WarmUpSavepointValueIdRollbackTest {

	/**
	 * Closes a savepoint a failing test might have left bound to this thread — the binding is thread-wide, so a leaked
	 * savepoint would otherwise fail every subsequent test in this fork with a bogus "already open" error.
	 */
	@AfterEach
	void closeLeakedSavepoint() {
		final WarmUpSavepoint leaked = WarmUpSavepoint.getIfOpen();
		if (leaked != null) {
			leaked.commit();
		}
	}

	@Nested
	@DisplayName("ValueIdAllocator")
	class Allocator {

		@Test
		@DisplayName("Rollback gives back every id minted inside the savepoint, not just the last one")
		void shouldRestoreHighWaterMarkAfterRepeatedMints() {
			final ValueIdAllocator allocator = new ValueIdAllocator();
			final int before = allocator.getNextValueId();

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			allocator.allocate();
			allocator.allocate();
			allocator.allocate();
			assertEquals(
				before + 3, allocator.getNextValueId(),
				"self-check: the mints must have taken effect inside the savepoint"
			);
			savepoint.rollback();

			assertEquals(
				before, allocator.getNextValueId(),
				"Rollback must restore the high-water mark the allocator held before the savepoint opened - a " +
					"per-mint inverse that only stepped back once would leave it at " + (before + 2) + "."
			);
		}

		@Test
		@DisplayName("Rollback restores the mark of an allocator restored from a persisted position")
		void shouldRestorePersistedHighWaterMark() {
			final ValueIdAllocator allocator = new ValueIdAllocator(41);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			assertEquals(41, allocator.allocate(), "self-check: the restored mark is the id handed out next");
			savepoint.rollback();

			assertEquals(
				41, allocator.getNextValueId(),
				"An allocator restored from a persisted high-water must rewind to that position, not to the first id."
			);
		}

		@Test
		@DisplayName("Commit keeps the ids the savepoint minted")
		void shouldKeepMintedIdsOnCommit() {
			final ValueIdAllocator allocator = new ValueIdAllocator();
			final int before = allocator.getNextValueId();

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			allocator.allocate();
			allocator.allocate();
			savepoint.commit();

			assertEquals(
				before + 2, allocator.getNextValueId(),
				"A committed savepoint must leave the minted ids spent - rolling them back would hand the same id " +
					"to two different values."
			);
		}

		@Test
		@DisplayName("A second savepoint rewinds only to its own start, not to the first one's")
		void shouldRestoreOnlyToTheEnclosingSavepointStart() {
			final ValueIdAllocator allocator = new ValueIdAllocator();

			final WarmUpSavepoint first = WarmUpSavepoint.open();
			allocator.allocate();
			first.commit();
			final int afterFirst = allocator.getNextValueId();

			final WarmUpSavepoint second = WarmUpSavepoint.open();
			allocator.allocate();
			second.rollback();

			assertEquals(
				afterFirst, allocator.getNextValueId(),
				"The first-touch mark must be re-armed per savepoint - a stale mark would make the second savepoint " +
					"believe it had already captured the counter and skip the journal entirely."
			);
		}

		@Test
		@DisplayName("Minting outside any savepoint costs nothing and is never rewound")
		void shouldNotJournalOutsideASavepoint() {
			final ValueIdAllocator allocator = new ValueIdAllocator();
			allocator.allocate();
			final int outsideSavepoint = allocator.getNextValueId();

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			savepoint.rollback();

			assertEquals(
				outsideSavepoint, allocator.getNextValueId(),
				"A rollback may only undo what its own savepoint covered."
			);
		}
	}
}
