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

package io.evitadb.index.array;

import com.carrotsearch.hppc.IntLongHashMap;
import com.carrotsearch.hppc.IntLongMap;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyReport;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
import io.evitadb.index.attribute.OwnerSortIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the position tree behind {@link TransactionalUnorderedIntArray} — the count-augmented
 * {@link UnorderedLookupTree} plus the {@code TransactionalIntToLongBPlusTree} order-key index it is paired with —
 * rewinds its non-transactional (WARM_UP) writes when the {@link WarmUpSavepoint} bracketing them is rolled back.
 *
 * The façade holds no state of its own: it delegates entirely to those two trees, whose nodes journal at node
 * granularity. What the tree itself contributes beyond its nodes is the memoized flattening returned by
 * {@link UnorderedLookupTree#getArray()}, and that memo is memoized ONLY on the no-transaction branch — precisely the
 * warm-up path a savepoint brackets. So every rollback test below deliberately READS the array while the savepoint is
 * open: that read repopulates the memo from the half-mutated tree, and an implementation relying only on the forward
 * mutators' invalidation passes without the read and fails with it.
 *
 * One nested class drops below the façade and drives {@link UnorderedLookupTree} directly, because the internal
 * nodes' per-child count augmentation is journalled at SLOT granularity rather than at node granularity and the two
 * granularities have to compose: a spine node takes per-slot inverses for a run of ordinary count adjustments and
 * falls back to its whole-node memento the moment a split reaches it. Those scenarios need a small fan-out and
 * containers with known free room, which the façade does not expose.
 *
 * The final nested class closes the loop end to end through {@link SortIndex}, whose {@code sortedRecords} IS one of
 * these façades — the last piece of that index's recovery, its memoized caches having been covered separately.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see io.evitadb.index.bPlusTree.WarmUpSavepointBPlusTreeRollbackTest for the node-level journaling this rests on
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(TRANSACTION)
@DisplayName("Warm-up savepoint rollback of the unordered lookup tree")
class WarmUpSavepointUnorderedLookupRollbackTest {

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

	/**
	 * Builds an unordered array holding `count` record ids in ascending append order.
	 */
	@Nonnull
	private static TransactionalUnorderedIntArray newArray(int count) {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray();
		final int[] recordIds = new int[count];
		for (int i = 0; i < count; i++) {
			recordIds[i] = i + 1;
		}
		array.appendAll(recordIds);
		return array;
	}

	/**
	 * Internal fan-out and container split threshold of the trees the spine scenarios drive. Deliberately the smallest
	 * fan-out the tree accepts: it puts two internal levels above ten containers for forty records, so a single insert
	 * adjusts a count slot on two distinct spine nodes and a single split cascades through both.
	 */
	private static final int BLOCK_SIZE = 4;
	/**
	 * Number of records the spine scenarios seed, chosen so the bulk load fills exactly ten containers.
	 */
	private static final int SEED_RECORDS = 40;

	/**
	 * Builds the spine every scenario in {@link SpineCountAugmentation} starts from: forty records over ten containers
	 * under two internal levels, with one record drained out of every container so an in-savepoint insert has room and
	 * adjusts the counts without splitting anything. The record ids surviving the drain are `1..40` minus every fourth.
	 */
	@Nonnull
	private static TreeUnderTest newSpine(boolean headAware) {
		final TreeUnderTest tested = new TreeUnderTest(BLOCK_SIZE, headAware);
		final int[] recordIds = new int[SEED_RECORDS];
		for (int i = 0; i < SEED_RECORDS; i++) {
			recordIds[i] = i + 1;
		}
		tested.bulkLoad(recordIds);
		// the bulk load packs every container up to the split threshold - drain the last record of each one
		for (int first = 1; first <= SEED_RECORDS; first += BLOCK_SIZE) {
			tested.remove(first + BLOCK_SIZE - 1);
		}
		assertTrue(tested.tree.isRootInternal(), "self-check: the seeded tree must carry an internal spine");
		assertConsistent(tested.tree);
		return tested;
	}

	/**
	 * Asserts the tree's own structural consistency report is CONSISTENT, surfacing its message on failure. The report
	 * re-derives every internal node's stored per-child subtree count — and, on a head-aware tree, its per-child head
	 * count — from the containers below it, so a count adjustment that a rollback restored to the wrong slot or left
	 * un-restored is reported here even when the record order itself happens to look right.
	 */
	private static void assertConsistent(@Nonnull UnorderedLookupTree tree) {
		final ConsistencyReport report = tree.getConsistencyReport();
		assertEquals(
			ConsistencyState.CONSISTENT, report.state(),
			"The position tree reported structural inconsistency:\n" + report.report()
		);
	}

	/**
	 * Pairs a bare {@link UnorderedLookupTree} with the stand-in `recordId → orderKey` value index the composite that
	 * drives it owns in production, and counts the order-key assignments the tree reports.
	 *
	 * The count is what the spine scenarios use to prove a container did or did not SPLIT: a plain insert reports
	 * exactly one assignment (the new record taking its container's key), whereas a split re-stamps every record moved
	 * into the freshly minted right container. That distinction is the whole point of those tests — a run of pure count
	 * adjustments journals per slot, a split takes the node's whole-node memento — so it is asserted rather than
	 * assumed.
	 */
	private static final class TreeUnderTest implements OrderKeyConsumer {
		@Nonnull final UnorderedLookupTree tree;
		@Nonnull private final IntLongMap valueIndex = new IntLongHashMap();
		private int assignments;

		TreeUnderTest(int blockSize, boolean headAware) {
			this.tree = new UnorderedLookupTree(blockSize, 1_000_000L, headAware);
		}

		@Override
		public void accept(int recordId, long orderKey) {
			this.valueIndex.put(recordId, orderKey);
			this.assignments++;
		}

		/**
		 * Returns the number of order-key assignments reported since the last {@link #resetAssignments()}.
		 */
		int assignments() {
			return this.assignments;
		}

		void resetAssignments() {
			this.assignments = 0;
		}

		void bulkLoad(@Nonnull int[] recordIds) {
			this.tree.bulkLoad(recordIds, this);
		}

		void addAfter(int previousRecordId, int recordId) {
			this.tree.insertAfter(this.valueIndex.get(previousRecordId), previousRecordId, recordId, this);
		}

		void remove(int recordId) {
			this.tree.removeByOrderKey(this.valueIndex.get(recordId), recordId, this);
		}

		void markHead(int recordId) {
			this.tree.markHead(this.valueIndex.get(recordId), recordId);
		}

		void unmarkHead(int recordId) {
			this.tree.unmarkHead(this.valueIndex.get(recordId), recordId);
		}

		@Nonnull
		int[] getArray() {
			return this.tree.getArray();
		}

		/**
		 * Returns the head rank of every logical position, i.e. the whole head-count augmentation as the spine's
		 * internal nodes answer it — one value per record, so a single restored-wrong head-count slot shows up as a
		 * mismatch at the first position it covers.
		 */
		@Nonnull
		int[] headRanks() {
			final int[] ranks = new int[this.tree.size()];
			for (int position = 0; position < ranks.length; position++) {
				ranks[position] = this.tree.headRank(position);
			}
			return ranks;
		}
	}

	@Nested
	@DisplayName("TransactionalUnorderedIntArray")
	class UnorderedArray {

		@Test
		@DisplayName("Rollback undoes an append burst that split the position tree")
		void shouldRestoreArrayAfterSplittingAppendBurst() {
			final TransactionalUnorderedIntArray array = newArray(8);
			final int[] expected = array.getArray();
			assertFalse(array.isRootInternal(), "self-check: the seeded array must still fit one container");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 0; i < 3000; i++) {
				array.add(array.getLastRecordId(), 1000 + i);
			}
			assertTrue(array.isRootInternal(), "self-check: the in-savepoint burst must have split the position tree");
			// read INSIDE the savepoint: this repopulates the memoized flattening from the half-mutated tree
			assertEquals(3008, array.getArray().length, "self-check on the in-savepoint contents");
			savepoint.rollback();

			assertArrayEquals(expected, array.getArray(), "Rollback must restore the exact pre-savepoint order.");
			assertEquals(8, array.getLength(), "Rollback must restore the array length.");
			assertFalse(array.isRootInternal(), "Rollback must restore the pre-split single-container shape.");

			// the array must remain usable afterwards
			array.add(8, 9);
			assertArrayEquals(
				new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, array.getArray(),
				"A write after the rollback must apply normally on top of the restored order."
			);
		}

		@Test
		@DisplayName("Rollback undoes an insert / remove churn in the middle of the order")
		void shouldRestoreArrayAfterMidOrderChurn() {
			final TransactionalUnorderedIntArray array = newArray(2000);
			final int[] expected = array.getArray();
			assertTrue(array.isRootInternal(), "self-check: the seeded array must span several containers");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			// insert after and remove around the middle so containers both split and drain below their minimum
			for (int i = 0; i < 400; i++) {
				array.add(1000, 5000 + i);
			}
			for (int i = 100; i < 900; i++) {
				array.remove(i);
			}
			assertEquals(1600, array.getArray().length, "self-check on the in-savepoint contents");
			savepoint.rollback();

			assertArrayEquals(expected, array.getArray(), "Rollback must restore the exact pre-savepoint order.");
			assertEquals(2000, array.getLength(), "Rollback must restore the array length.");
			assertEquals(500, array.indexOf(501), "Rollback must restore the position index of every record.");
		}

		@Test
		@DisplayName("Commit keeps an append burst that split the position tree")
		void shouldKeepAppendBurstOnCommit() {
			final TransactionalUnorderedIntArray array = newArray(8);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 0; i < 3000; i++) {
				array.add(array.getLastRecordId(), 1000 + i);
			}
			final int[] expected = array.getArray();
			savepoint.commit();

			assertArrayEquals(
				expected, array.getArray(), "Commit must keep every change made while the savepoint was open."
			);
			assertTrue(array.isRootInternal(), "Commit must keep the split shape.");
		}

		@Test
		@DisplayName("Rollback leaves an array that was never touched alone")
		void shouldNotTouchUnrelatedArray() {
			final TransactionalUnorderedIntArray touched = newArray(8);
			final TransactionalUnorderedIntArray untouched = newArray(2000);
			final int[] untouchedContents = untouched.getArray();

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 0; i < 3000; i++) {
				touched.add(touched.getLastRecordId(), 1000 + i);
			}
			savepoint.rollback();

			assertArrayEquals(
				untouchedContents, untouched.getArray(),
				"An array never write-touched inside the savepoint must be left exactly as it was."
			);
			assertEquals(8, touched.getLength(), "self-check: the touched array was rewound");
		}
	}

	@Nested
	@DisplayName("Internal-node count augmentation")
	class SpineCountAugmentation {

		@Test
		@DisplayName("Rollback undoes count adjustments that split nothing")
		void shouldRestoreSpineCountsAfterNonSplittingInserts() {
			final TreeUnderTest tested = newSpine(false);
			final int[] expected = tested.getArray();
			final int expectedSize = tested.tree.size();

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			tested.resetAssignments();
			int newRecordId = 1000;
			for (int first = 1; first <= SEED_RECORDS; first += BLOCK_SIZE) {
				tested.addAfter(first, newRecordId++);
			}
			final int inserted = SEED_RECORDS / BLOCK_SIZE;
			assertEquals(
				inserted, tested.assignments(),
				"self-check: every insert must have found room, so nothing may have split"
			);
			// read INSIDE the savepoint: this repopulates the memoized flattening from the half-mutated tree
			assertEquals(expectedSize + inserted, tested.getArray().length, "self-check on the in-savepoint contents");
			savepoint.rollback();

			assertArrayEquals(expected, tested.getArray(), "Rollback must restore the exact pre-savepoint order.");
			assertEquals(expectedSize, tested.tree.size(), "Rollback must restore the record count.");
			assertConsistent(tested.tree);

			// the tree must remain usable, and the restored counts must route the next write correctly
			tested.addAfter(1, 9999);
			assertEquals(expectedSize + 1, tested.tree.size(), "A write after the rollback must apply normally.");
			assertEquals(9999, tested.getArray()[1], "The record written after the rollback must land after record 1.");
			assertConsistent(tested.tree);
		}

		@Test
		@DisplayName("Commit keeps count adjustments made while the savepoint was open")
		void shouldKeepSpineCountsOnCommit() {
			final TreeUnderTest tested = newSpine(false);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			int newRecordId = 1000;
			for (int first = 1; first <= SEED_RECORDS; first += BLOCK_SIZE) {
				tested.addAfter(first, newRecordId++);
			}
			final int[] expected = tested.getArray();
			savepoint.commit();

			assertArrayEquals(
				expected, tested.getArray(), "Commit must keep every change made while the savepoint was open."
			);
			assertEquals(expected.length, tested.tree.size(), "Commit must keep the record count.");
			assertConsistent(tested.tree);
		}

		@Test
		@DisplayName("Rollback undoes count adjustments a later split of the same nodes overwrote")
		void shouldRestoreSpineCountsWhenASplitFollowsTheAdjustments() {
			final TreeUnderTest tested = newSpine(false);
			final int[] expected = tested.getArray();
			final int expectedSize = tested.tree.size();

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			// (1) pure count adjustments: three containers under the SAME spine node take a record each, so that node
			//     accumulates three per-slot inverses and is not captured whole
			tested.resetAssignments();
			tested.addAfter(1, 1001);
			tested.addAfter(5, 1002);
			tested.addAfter(9, 1003);
			assertEquals(3, tested.assignments(), "self-check: none of these inserts may split a container");
			// (2) a fourth record overflows the first container; the split inserts a child into that same spine node,
			//     which now takes its whole-node memento ON TOP of the inverses from step (1) and cascades to the root
			tested.resetAssignments();
			tested.addAfter(1, 1004);
			assertTrue(tested.assignments() > 1, "self-check: this insert must have split a container");
			assertConsistent(tested.tree);
			savepoint.rollback();

			assertArrayEquals(expected, tested.getArray(), "Rollback must restore the exact pre-savepoint order.");
			assertEquals(expectedSize, tested.tree.size(), "Rollback must restore the record count.");
			assertConsistent(tested.tree);
		}

		@Test
		@DisplayName("Rollback undoes repeated adjustments of one and the same count slot")
		void shouldRestoreSpineCountsAfterRepeatedAdjustmentsOfOneSlot() {
			final TreeUnderTest tested = newSpine(false);
			final int[] expected = tested.getArray();
			final int expectedSize = tested.tree.size();

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			tested.resetAssignments();
			// every one of these writes lands in the first container, so the same slot of both spine levels is
			// adjusted five times - only the EARLIEST inverse of that slot may survive the reverse replay
			tested.addAfter(1, 1001);
			tested.remove(1001);
			tested.addAfter(1, 1002);
			tested.remove(2);
			tested.addAfter(1, 1003);
			assertEquals(3, tested.assignments(), "self-check: none of these writes may split a container");
			assertEquals(expectedSize + 1, tested.tree.size(), "self-check on the in-savepoint record count");
			savepoint.rollback();

			assertArrayEquals(expected, tested.getArray(), "Rollback must restore the exact pre-savepoint order.");
			assertEquals(expectedSize, tested.tree.size(), "Rollback must restore the record count.");
			assertConsistent(tested.tree);
		}

		@Test
		@DisplayName("Rollback undoes head-count adjustments alongside the record counts")
		void shouldRestoreSpineHeadCountsAfterHeadChurn() {
			final TreeUnderTest tested = newSpine(true);
			// seed a head every third record so every spine node carries a non-trivial head count
			final int[] seeded = tested.getArray();
			for (int position = 0; position < seeded.length; position += 3) {
				tested.markHead(seeded[position]);
			}
			final int[] expected = tested.getArray();
			final int[] expectedRanks = tested.headRanks();
			assertConsistent(tested.tree);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			// mark and unmark so the head counts move on their own, without the record counts moving with them
			for (int position = 1; position < expected.length; position += 3) {
				tested.markHead(expected[position]);
			}
			for (int position = 0; position < expected.length; position += 6) {
				tested.unmarkHead(expected[position]);
			}
			// then move both columns together, first without and then with a container split
			tested.resetAssignments();
			tested.addAfter(expected[0], 2001);
			assertEquals(1, tested.assignments(), "self-check: this insert must have found room without splitting");
			tested.resetAssignments();
			tested.addAfter(expected[0], 2002);
			assertTrue(tested.assignments() > 1, "self-check: this insert must have split a container");
			assertConsistent(tested.tree);
			savepoint.rollback();

			assertArrayEquals(expected, tested.getArray(), "Rollback must restore the exact pre-savepoint order.");
			assertArrayEquals(
				expectedRanks, tested.headRanks(),
				"Rollback must restore every per-child head count on the spine."
			);
			assertConsistent(tested.tree);
		}
	}

	@Nested
	@DisplayName("SortIndex end to end")
	class SortIndexRecovery {

		/**
		 * Builds a sort index over single-character string values, one record per value, in ascending value order.
		 */
		@Nonnull
		private static SortIndex newIndex(int count) {
			final SortIndex index = new OwnerSortIndex(String.class, new AttributeIndexKey(null, "a", null));
			for (int i = 0; i < count; i++) {
				index.addRecord(String.format("v%05d", i), i + 1);
			}
			return index;
		}

		@Test
		@DisplayName("Rollback restores the sorted record order after a burst that split the position tree")
		void shouldRestoreSortedRecordsAfterInsertBurst() {
			final SortIndex index = newIndex(600);
			final int[] expected = index.getSortedRecords();

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 0; i < 2000; i++) {
				index.addRecord(String.format("w%05d", i), 10_000 + i);
			}
			// read INSIDE the savepoint so the sort-index caches and the position tree's memoized flattening are both
			// repopulated from the half-mutated state
			assertEquals(2600, index.getSortedRecords().length, "self-check on the in-savepoint order");
			savepoint.rollback();

			assertArrayEquals(
				expected, index.getSortedRecords(),
				"Rollback must restore the exact pre-savepoint sorted-record order."
			);
			assertEquals(600, index.size(), "Rollback must restore the index size.");

			// a subsequent write must apply on top of the restored order rather than on a stale view of it
			index.addRecord("v99999", 99_999);
			final int[] afterRollbackWrite = index.getSortedRecords();
			assertEquals(601, afterRollbackWrite.length, "A write after the rollback must apply normally.");
			assertEquals(
				99_999, afterRollbackWrite[afterRollbackWrite.length - 1],
				"The record written after the rollback must sort into the restored order."
			);
		}

		@Test
		@DisplayName("Rollback restores the sorted record order after a removal burst")
		void shouldRestoreSortedRecordsAfterRemovalBurst() {
			final SortIndex index = newIndex(2000);
			final int[] expected = index.getSortedRecords();

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 0; i < 1500; i++) {
				index.removeRecord(String.format("v%05d", i), i + 1);
			}
			assertEquals(500, index.getSortedRecords().length, "self-check on the in-savepoint order");
			savepoint.rollback();

			assertArrayEquals(
				expected, index.getSortedRecords(),
				"Rollback must restore the exact pre-savepoint sorted-record order."
			);
			assertEquals(2000, index.size(), "Rollback must restore the index size.");
		}

		@Test
		@DisplayName("Commit keeps the sorted record order written while the savepoint was open")
		void shouldKeepSortedRecordsOnCommit() {
			final SortIndex index = newIndex(600);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 0; i < 2000; i++) {
				index.addRecord(String.format("w%05d", i), 10_000 + i);
			}
			final int[] expected = index.getSortedRecords();
			savepoint.commit();

			assertArrayEquals(
				expected, index.getSortedRecords(),
				"Commit must keep every change made while the savepoint was open."
			);
		}
	}

}
