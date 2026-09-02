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
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Comparator;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

	@Nested
	@DisplayName("Value id column of the shared value tree")
	class IdColumn {

		/**
		 * Builds a tree carrying value ids, exactly as an inverted index does when a substring accelerator attaches to
		 * it: a fresh tree with a minter installed, so every bucket born is stamped.
		 *
		 * @param allocator the allocator to mint from
		 * @return the tree under test
		 */
		@Nonnull
		private TransactionalBucketBPlusTree<Integer> treeCarryingValueIds(@Nonnull ValueIdAllocator allocator) {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(Integer.class);
			tree.installValueIdMinter(allocator::allocate);
			return tree;
		}

		@Test
		@DisplayName("Rollback of a value's death puts its id back on the bucket, not an unassigned slot")
		void shouldRestoreValueIdWhenADeletedBucketComesBack() {
			final ValueIdAllocator allocator = new ValueIdAllocator();
			final TransactionalBucketBPlusTree<Integer> tree = treeCarryingValueIds(allocator);
			tree.addRecord(10, 1);
			tree.addRecord(20, 2);
			final int idOfTwenty = tree.valueIdOf(20);
			assertNotEquals(
				ValueIdAllocator.UNASSIGNED_VALUE_ID, idOfTwenty,
				"self-check: a tree with a minter stamps every bucket it creates"
			);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			tree.removeRecord(20, 2);
			savepoint.rollback();

			assertEquals(
				idOfTwenty, tree.valueIdOf(20),
				"The re-inserted bucket must carry the id it died with. The forward insert leaves the id slot " +
					"unassigned because the tree stamps it immediately afterwards - on a rollback replay nothing " +
					"does, so the inverse has to put the dying id back itself."
			);
		}

		@Test
		@DisplayName("Rollback keeps the id column aligned with the keys beside it")
		void shouldKeepIdColumnAlignedWithNeighbouringBuckets() {
			final ValueIdAllocator allocator = new ValueIdAllocator();
			final TransactionalBucketBPlusTree<Integer> tree = treeCarryingValueIds(allocator);
			for (int value = 10; value <= 50; value += 10) {
				tree.addRecord(value, value / 10);
			}
			final int[] idsBefore = new int[5];
			for (int i = 0; i < 5; i++) {
				idsBefore[i] = tree.valueIdOf((i + 1) * 10);
			}

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			// delete from the MIDDLE, so a column that fails to shift back in lockstep misaligns every id after it
			tree.removeRecord(30, 3);
			savepoint.rollback();

			for (int i = 0; i < 5; i++) {
				final int value = (i + 1) * 10;
				assertEquals(
					idsBefore[i], tree.valueIdOf(value),
					"Value " + value + " must keep the id it had before the savepoint - a rollback that shifts the " +
						"key and record columns without the id column beside them hands one value another's id."
				);
			}
		}

		@Test
		@DisplayName("Commit leaves a dead value's id spent and its bucket gone")
		void shouldLeaveTheDeadValueGoneOnCommit() {
			final ValueIdAllocator allocator = new ValueIdAllocator();
			final TransactionalBucketBPlusTree<Integer> tree = treeCarryingValueIds(allocator);
			tree.addRecord(10, 1);
			tree.addRecord(20, 2);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			tree.removeRecord(20, 2);
			savepoint.commit();

			assertEquals(
				ValueIdAllocator.UNASSIGNED_VALUE_ID, tree.valueIdOf(20),
				"A committed death must leave the value gone - ids are monotonic with holes, so its id is simply " +
					"never handed out again."
			);
		}
	}

	@Nested
	@DisplayName("Value id directory")
	class Directory {

		private static final String TEST_CONSUMER = "test-consumer";

		/**
		 * @return a fresh tree of `String` values that already carries value ids, as one does once a substring
		 * accelerator has attached to it
		 */
		@Nonnull
		private InvertedIndex treeWithIds() {
			final InvertedIndex tree = new InvertedIndex(
				String.class, FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder(), 0
			);
			tree.attachValueIdConsumer(TEST_CONSUMER);
			return tree;
		}

		@Test
		@DisplayName("A read that rebuilds the directory mid-savepoint does not survive the rollback")
		void shouldNotLeaveTheDirectoryDescribingARewoundTree() {
			final InvertedIndex tree = treeWithIds();
			tree.addRecord("alpha", 1);
			tree.addRecord("beta", 2);
			final int alphaId = tree.getValueId("alpha");
			final int betaId = tree.getValueId("beta");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			tree.addRecord("gamma", 3);
			final int gammaId = tree.getValueId("gamma");
			// the read is what makes this reachable: it finds the directory stale, rebuilds it over the tree as it
			// stands INSIDE the savepoint, and clears the staleness flag on the way out. A query arriving between a
			// write and its rollback does exactly this
			assertEquals("alpha", tree.getValueById(alphaId), "self-check: the directory answers before the rollback");
			savepoint.rollback();

			assertNull(
				tree.getValueById(gammaId),
				"The rolled-back value must be gone. A rollback mutates the leaves in place without going back " +
					"through the write path, so nothing re-raises the staleness flag the read above cleared - and a " +
					"directory left marked fresh keeps answering from slot positions the rewind has since moved."
			);
			assertEquals(
				"alpha", tree.getValueById(alphaId),
				"The values that survived the rollback must still resolve to themselves."
			);
			assertEquals(
				"beta", tree.getValueById(betaId),
				"The values that survived the rollback must still resolve to themselves."
			);
		}

		@Test
		@DisplayName("A committed savepoint leaves the directory answering for the values it added")
		void shouldKeepTheDirectoryUsableAfterCommit() {
			final InvertedIndex tree = treeWithIds();
			tree.addRecord("alpha", 1);
			final int alphaId = tree.getValueId("alpha");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			tree.addRecord("gamma", 3);
			final int gammaId = tree.getValueId("gamma");
			assertEquals("alpha", tree.getValueById(alphaId), "self-check: the directory answers before the commit");
			savepoint.commit();

			assertEquals(
				"gamma", tree.getValueById(gammaId),
				"A committed savepoint keeps its writes, so the directory must resolve the value it added."
			);
		}
	}
}
