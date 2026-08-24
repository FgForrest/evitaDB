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

package io.evitadb.index.bPlusTree;

import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyReport;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the five B+ trees rewind their non-transactional (WARM_UP) writes when the {@link WarmUpSavepoint}
 * bracketing them is rolled back, and keep them when it is committed.
 *
 * The trees journal at NODE granularity: every node mutator resolves its diff layer through
 * {@link WarmUpSavepoint#writeLayer}, which on the layer-null branch captures the node's own bounded memento the first
 * time that node is write-touched. The tree-level root and size references journal separately (they are
 * {@code TransactionalReference}s), and nodes CREATED inside the savepoint need no inverse at all — restoring the old
 * root and the parents' child pointers makes them unreachable garbage.
 *
 * What that design makes worth testing beyond "a write was rewound" is the STRUCTURAL churn, because that is where a
 * mutation reaches beyond the node it is called on:
 *
 * - **Splits.** Every test that grows a tree drives it past a split, asserted through
 *   {@code isRootInternal()} flipping while the savepoint is open and flipping back after the rollback — the tree's
 *   whole shape, not merely its contents, has to return.
 * - **Merges, steals and root collapse.** Every test that shrinks a tree drains leaves below the minimum occupancy so
 *   the rebalancer borrows from and merges with siblings, which mutates nodes the deletion was never called on. Those
 *   siblings must record their own first touch (they do, through {@code setPeek} / the {@code ...ForUpdate}
 *   accessors), or the rollback would restore a tree whose leaves no longer agree with their separators.
 * - **Mid-savepoint reads.** Each rollback test reads the tree while the savepoint is open. The iterators cache leaf
 *   arrays as they walk, so a read is also what would strand a stale cached view past a rollback.
 *
 * A rolled-back tree is finally asserted CONSISTENT through {@code getConsistencyReport()} where the tree offers one —
 * a contents comparison alone would pass on a tree whose separators lie, since the iterators walk leaves in order and
 * never consult them.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see io.evitadb.index.WarmUpSavepointIndexRollbackTest for the bitmaps and composite indexes the trees sit inside
 */
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
@DisplayName("Warm-up savepoint rollback of the B+ trees")
class WarmUpSavepointBPlusTreeRollbackTest {

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
	 * Asserts the passed tree reports itself internally consistent — the check a contents comparison cannot make,
	 * since it verifies the separator keys and per-level occupancy the iterators never look at.
	 *
	 * @param report the report of the tree under test
	 * @param when   what the tree had just been put through, for the failure message
	 */
	private static void assertConsistent(@Nonnull ConsistencyReport report, @Nonnull String when) {
		assertEquals(
			ConsistencyState.CONSISTENT, report.state(),
			() -> "The tree must be internally consistent " + when + ": " + report.report()
		);
	}

	@Nested
	@DisplayName("TransactionalObjectBPlusTree")
	class ObjectTree {

		/**
		 * Builds a single-leaf tree of `count` consecutive keys with block sizes small enough that a handful of further
		 * inserts splits it.
		 */
		@Nonnull
		private TransactionalObjectBPlusTree<Integer, Integer> newTree(int count) {
			final TransactionalObjectBPlusTree<Integer, Integer> tree = new TransactionalObjectBPlusTree<>(
				8, 3, 7, 3, Integer.class, Integer.class
			);
			for (int i = 0; i < count; i++) {
				tree.insert(i, i * 10);
			}
			return tree;
		}

		/**
		 * Reads the tree's whole logical content into a comparable reference value.
		 */
		@Nonnull
		private TreeMap<Integer, Integer> contents(@Nonnull TransactionalObjectBPlusTree<Integer, Integer> tree) {
			final TreeMap<Integer, Integer> result = new TreeMap<>();
			final Iterator<TransactionalObjectBPlusTree.Entry<Integer, Integer>> it = tree.entryIterator();
			while (it.hasNext()) {
				final TransactionalObjectBPlusTree.Entry<Integer, Integer> entry = it.next();
				result.put(entry.key(), entry.value());
			}
			return result;
		}

		@Test
		@DisplayName("Rollback undoes an insert burst that split the tree")
		void shouldRestoreTreeAfterSplittingInsertBurst() {
			final TransactionalObjectBPlusTree<Integer, Integer> tree = newTree(6);
			final TreeMap<Integer, Integer> expected = contents(tree);
			assertFalse(tree.isRootInternal(), "self-check: the seeded tree must still be a single leaf");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 100; i < 140; i++) {
				tree.insert(i, i * 10);
			}
			assertTrue(tree.isRootInternal(), "self-check: the in-savepoint burst must have split the tree");
			// read INSIDE the savepoint: the iterators cache leaf arrays, so this is what would strand a stale view
			assertNotEquals(expected, contents(tree), "self-check: the in-savepoint contents must differ");
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must restore the exact pre-savepoint contents.");
			assertFalse(tree.isRootInternal(), "Rollback must restore the pre-split single-leaf shape.");
			assertEquals(6, tree.size(), "Rollback must restore the tree size.");
			assertConsistent(tree.getConsistencyReport(), "after a rolled-back split");

			// the tree must remain usable afterwards
			tree.insert(500, 5000);
			assertEquals(7, tree.size(), "A write after the rollback must apply normally.");
		}

		@Test
		@DisplayName("Rollback undoes a delete burst that merged leaves and collapsed the root")
		void shouldRestoreTreeAfterMergingDeleteBurst() {
			final TransactionalObjectBPlusTree<Integer, Integer> tree = newTree(60);
			final TreeMap<Integer, Integer> expected = contents(tree);
			assertTrue(tree.isRootInternal(), "self-check: the seeded tree must span several leaves");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			// drain leaves below the minimum occupancy so the rebalancer borrows from and merges with siblings
			for (int i = 0; i < 56; i++) {
				tree.delete(i);
			}
			assertFalse(tree.isRootInternal(), "self-check: the in-savepoint burst must have collapsed the root");
			assertEquals(4, contents(tree).size(), "self-check on the in-savepoint contents");
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must restore the exact pre-savepoint contents.");
			assertTrue(tree.isRootInternal(), "Rollback must restore the pre-collapse multi-leaf shape.");
			assertEquals(60, tree.size(), "Rollback must restore the tree size.");
			assertConsistent(tree.getConsistencyReport(), "after a rolled-back merge cascade");
		}

		@Test
		@DisplayName("Commit keeps an insert burst that split the tree")
		void shouldKeepSplitOnCommit() {
			final TransactionalObjectBPlusTree<Integer, Integer> tree = newTree(6);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 100; i < 140; i++) {
				tree.insert(i, i * 10);
			}
			final TreeMap<Integer, Integer> expected = contents(tree);
			savepoint.commit();

			assertEquals(expected, contents(tree), "Commit must keep every change made while the savepoint was open.");
			assertTrue(tree.isRootInternal(), "Commit must keep the split shape.");
			assertConsistent(tree.getConsistencyReport(), "after a committed split");
		}

		@Test
		@DisplayName("Two savepoints in a row each restore from their own pre-image")
		void shouldRestoreRepeatedly() {
			final TransactionalObjectBPlusTree<Integer, Integer> tree = newTree(6);
			final TreeMap<Integer, Integer> initial = contents(tree);

			final WarmUpSavepoint first = WarmUpSavepoint.open();
			for (int i = 100; i < 130; i++) {
				tree.insert(i, i * 10);
			}
			first.rollback();
			assertEquals(initial, contents(tree), "The first rollback must restore the initial contents.");

			// a write between the savepoints must survive the second rollback
			tree.insert(7, 70);
			final TreeMap<Integer, Integer> betweenSavepoints = contents(tree);

			final WarmUpSavepoint second = WarmUpSavepoint.open();
			for (int i = 200; i < 230; i++) {
				tree.insert(i, i * 10);
			}
			second.rollback();

			assertEquals(
				betweenSavepoints, contents(tree),
				"The second rollback must restore the state as of its own opening, keeping the between-savepoint write."
			);
			assertConsistent(tree.getConsistencyReport(), "after two consecutive rolled-back savepoints");
		}

		@Test
		@DisplayName("Rollback leaves a tree that was never touched alone")
		void shouldNotTouchUnrelatedTree() {
			final TransactionalObjectBPlusTree<Integer, Integer> touched = newTree(6);
			final TransactionalObjectBPlusTree<Integer, Integer> untouched = newTree(20);
			final TreeMap<Integer, Integer> untouchedContents = contents(untouched);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 100; i < 140; i++) {
				touched.insert(i, i * 10);
			}
			savepoint.rollback();

			assertEquals(
				untouchedContents, contents(untouched),
				"A tree never write-touched inside the savepoint must be left exactly as it was."
			);
			assertEquals(6, touched.size(), "self-check: the touched tree was rewound");
		}
	}

	@Nested
	@DisplayName("TransactionalLongBPlusTree")
	class LongTree {

		/**
		 * Builds a tree of `count` consecutive long keys with block sizes small enough to split readily.
		 */
		@Nonnull
		private TransactionalLongBPlusTree<Integer> newTree(int count) {
			final TransactionalLongBPlusTree<Integer> tree = new TransactionalLongBPlusTree<>(
				8, 3, 7, 3, Integer.class
			);
			for (int i = 0; i < count; i++) {
				tree.insert(i, i * 10);
			}
			return tree;
		}

		/**
		 * Reads the tree's whole logical content into a comparable reference value.
		 */
		@Nonnull
		private TreeMap<Long, Integer> contents(@Nonnull TransactionalLongBPlusTree<Integer> tree) {
			final TreeMap<Long, Integer> result = new TreeMap<>();
			final Iterator<TransactionalLongBPlusTree.Entry<Integer>> it = tree.entryIterator();
			while (it.hasNext()) {
				final TransactionalLongBPlusTree.Entry<Integer> entry = it.next();
				result.put(entry.key(), entry.value());
			}
			return result;
		}

		@Test
		@DisplayName("Rollback undoes an insert burst that split the tree")
		void shouldRestoreTreeAfterSplittingInsertBurst() {
			final TransactionalLongBPlusTree<Integer> tree = newTree(6);
			final TreeMap<Long, Integer> expected = contents(tree);
			assertFalse(tree.isRootInternal(), "self-check: the seeded tree must still be a single leaf");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 100; i < 140; i++) {
				tree.insert(i, i * 10);
			}
			assertTrue(tree.isRootInternal(), "self-check: the in-savepoint burst must have split the tree");
			assertNotEquals(expected, contents(tree), "self-check: the in-savepoint contents must differ");
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must restore the exact pre-savepoint contents.");
			assertFalse(tree.isRootInternal(), "Rollback must restore the pre-split single-leaf shape.");
			assertConsistent(tree.getConsistencyReport(), "after a rolled-back split");
		}

		@Test
		@DisplayName("Rollback undoes a delete burst that merged leaves and collapsed the root")
		void shouldRestoreTreeAfterMergingDeleteBurst() {
			final TransactionalLongBPlusTree<Integer> tree = newTree(60);
			final TreeMap<Long, Integer> expected = contents(tree);
			assertTrue(tree.isRootInternal(), "self-check: the seeded tree must span several leaves");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 0; i < 56; i++) {
				tree.delete(i);
			}
			assertFalse(tree.isRootInternal(), "self-check: the in-savepoint burst must have collapsed the root");
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must restore the exact pre-savepoint contents.");
			assertTrue(tree.isRootInternal(), "Rollback must restore the pre-collapse multi-leaf shape.");
			assertEquals(60, tree.size(), "Rollback must restore the tree size.");
			assertConsistent(tree.getConsistencyReport(), "after a rolled-back merge cascade");
		}

		@Test
		@DisplayName("Rollback undoes an interleaved insert / delete churn")
		void shouldRestoreTreeAfterInterleavedChurn() {
			final TransactionalLongBPlusTree<Integer> tree = newTree(40);
			final TreeMap<Long, Integer> expected = contents(tree);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			// alternate growth and shrinkage so splits and merges interleave over the same nodes
			for (int i = 0; i < 30; i++) {
				tree.insert(1000 + i, i);
				tree.delete(i);
				tree.upsert(2000L + i, value -> value == null ? 1 : value + 1);
			}
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must restore the exact pre-savepoint contents.");
			assertConsistent(tree.getConsistencyReport(), "after a rolled-back interleaved churn");
		}

		@Test
		@DisplayName("Commit keeps an insert burst that split the tree")
		void shouldKeepSplitOnCommit() {
			final TransactionalLongBPlusTree<Integer> tree = newTree(6);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 100; i < 140; i++) {
				tree.insert(i, i * 10);
			}
			final TreeMap<Long, Integer> expected = contents(tree);
			savepoint.commit();

			assertEquals(expected, contents(tree), "Commit must keep every change made while the savepoint was open.");
			assertConsistent(tree.getConsistencyReport(), "after a committed split");
		}
	}

	@Nested
	@DisplayName("TransactionalIntToLongBPlusTree")
	class IntToLongTree {

		/**
		 * Builds a tree of `count` consecutive int keys with block sizes small enough to split readily.
		 */
		@Nonnull
		private TransactionalIntToLongBPlusTree newTree(int count) {
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(8, 3, 7, 3);
			for (int i = 0; i < count; i++) {
				tree.insert(i, i * 10L);
			}
			return tree;
		}

		/**
		 * Reads the tree's whole logical content into a comparable reference value.
		 */
		@Nonnull
		private TreeMap<Integer, Long> contents(@Nonnull TransactionalIntToLongBPlusTree tree) {
			final TreeMap<Integer, Long> result = new TreeMap<>();
			final Iterator<TransactionalIntToLongBPlusTree.Entry> it = tree.entryIterator();
			while (it.hasNext()) {
				final TransactionalIntToLongBPlusTree.Entry entry = it.next();
				result.put(entry.key(), entry.value());
			}
			return result;
		}

		@Test
		@DisplayName("Rollback undoes an insert burst that split the tree")
		void shouldRestoreTreeAfterSplittingInsertBurst() {
			final TransactionalIntToLongBPlusTree tree = newTree(6);
			final TreeMap<Integer, Long> expected = contents(tree);
			assertFalse(tree.isRootInternal(), "self-check: the seeded tree must still be a single leaf");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 100; i < 140; i++) {
				tree.insert(i, i * 10L);
			}
			assertTrue(tree.isRootInternal(), "self-check: the in-savepoint burst must have split the tree");
			assertNotEquals(expected, contents(tree), "self-check: the in-savepoint contents must differ");
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must restore the exact pre-savepoint contents.");
			assertFalse(tree.isRootInternal(), "Rollback must restore the pre-split single-leaf shape.");
			assertEquals(6, tree.size(), "Rollback must restore the tree size.");
		}

		@Test
		@DisplayName("Rollback undoes a delete burst that merged leaves and collapsed the root")
		void shouldRestoreTreeAfterMergingDeleteBurst() {
			final TransactionalIntToLongBPlusTree tree = newTree(60);
			final TreeMap<Integer, Long> expected = contents(tree);
			assertTrue(tree.isRootInternal(), "self-check: the seeded tree must span several leaves");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 0; i < 56; i++) {
				tree.delete(i);
			}
			assertFalse(tree.isRootInternal(), "self-check: the in-savepoint burst must have collapsed the root");
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must restore the exact pre-savepoint contents.");
			assertTrue(tree.isRootInternal(), "Rollback must restore the pre-collapse multi-leaf shape.");
			assertEquals(60, tree.size(), "Rollback must restore the tree size.");
		}

		@Test
		@DisplayName("Commit keeps an insert burst that split the tree")
		void shouldKeepSplitOnCommit() {
			final TransactionalIntToLongBPlusTree tree = newTree(6);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 100; i < 140; i++) {
				tree.insert(i, i * 10L);
			}
			final TreeMap<Integer, Long> expected = contents(tree);
			savepoint.commit();

			assertEquals(expected, contents(tree), "Commit must keep every change made while the savepoint was open.");
		}
	}

	@Nested
	@DisplayName("TransactionalElementBPlusTree")
	class ElementTree {

		/**
		 * Builds a tree of `count` consecutive elements. The element is the key itself, so the tree stores no separate
		 * value array — the leaf memento is the element array plus the peek index.
		 */
		@Nonnull
		private TransactionalElementBPlusTree<Integer> newTree(int count) {
			final TransactionalElementBPlusTree<Integer> tree = new TransactionalElementBPlusTree<>(
				8, 3, 7, 3, Integer.class, Integer::intValue
			);
			for (int i = 0; i < count; i++) {
				tree.insert(i);
			}
			return tree;
		}

		/**
		 * Reads the tree's whole logical content into a comparable reference value.
		 */
		@Nonnull
		private List<Integer> contents(@Nonnull TransactionalElementBPlusTree<Integer> tree) {
			final List<Integer> result = new ArrayList<>(tree.size());
			final Iterator<Integer> it = tree.valueIterator();
			while (it.hasNext()) {
				result.add(it.next());
			}
			return result;
		}

		@Test
		@DisplayName("Rollback undoes an insert burst that split the tree")
		void shouldRestoreTreeAfterSplittingInsertBurst() {
			// this tree's split adopts the former leaf's element array IN PLACE for the right half (unlike its four
			// siblings, which allocate fresh arrays), so the rollback here rests on the leaf memento being a CLONE:
			// the array the split shifted out from under the former leaf is not the array its memento holds
			final TransactionalElementBPlusTree<Integer> tree = newTree(6);
			final List<Integer> expected = contents(tree);
			assertFalse(tree.isRootInternal(), "self-check: the seeded tree must still be a single leaf");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 100; i < 140; i++) {
				tree.insert(i);
			}
			assertTrue(tree.isRootInternal(), "self-check: the in-savepoint burst must have split the tree");
			assertNotEquals(expected, contents(tree), "self-check: the in-savepoint contents must differ");
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must restore the exact pre-savepoint contents.");
			assertFalse(tree.isRootInternal(), "Rollback must restore the pre-split single-leaf shape.");
			assertEquals(6, tree.size(), "Rollback must restore the tree size.");
		}

		@Test
		@DisplayName("Rollback undoes a delete burst that merged leaves and collapsed the root")
		void shouldRestoreTreeAfterMergingDeleteBurst() {
			final TransactionalElementBPlusTree<Integer> tree = newTree(60);
			final List<Integer> expected = contents(tree);
			assertTrue(tree.isRootInternal(), "self-check: the seeded tree must span several leaves");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 0; i < 56; i++) {
				tree.delete(i);
			}
			assertFalse(tree.isRootInternal(), "self-check: the in-savepoint burst must have collapsed the root");
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must restore the exact pre-savepoint contents.");
			assertTrue(tree.isRootInternal(), "Rollback must restore the pre-collapse multi-leaf shape.");
			assertEquals(60, tree.size(), "Rollback must restore the tree size.");
		}

		@Test
		@DisplayName("Commit keeps an insert burst that split the tree")
		void shouldKeepSplitOnCommit() {
			final TransactionalElementBPlusTree<Integer> tree = newTree(6);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 100; i < 140; i++) {
				tree.insert(i);
			}
			final List<Integer> expected = contents(tree);
			savepoint.commit();

			assertEquals(expected, contents(tree), "Commit must keep every change made while the savepoint was open.");
		}
	}

	@Nested
	@DisplayName("TransactionalBucketBPlusTree")
	class BucketTree {

		/**
		 * Builds a bucket tree of `count` consecutive bucket values, each holding a single record.
		 */
		@Nonnull
		private TransactionalBucketBPlusTree<Integer> newTree(int count) {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(
				8, 3, 7, 3, Integer.class, null
			);
			for (int i = 0; i < count; i++) {
				tree.addRecord(i, i + 1);
			}
			return tree;
		}

		/**
		 * Reads the whole bucket-to-records mapping into a comparable reference value.
		 */
		@Nonnull
		private TreeMap<Integer, List<Integer>> contents(@Nonnull TransactionalBucketBPlusTree<Integer> tree) {
			final TreeMap<Integer, List<Integer>> result = new TreeMap<>();
			final BucketCursor<Integer> cursor = tree.cursor();
			while (cursor.next()) {
				final int[] array = cursor.records().getArray();
				final List<Integer> records = new ArrayList<>(array.length);
				for (final int record : array) {
					records.add(record);
				}
				result.put(cursor.value(), records);
			}
			return result;
		}

		@Test
		@DisplayName("Rollback undoes an insert burst that split the tree")
		void shouldRestoreTreeAfterSplittingInsertBurst() {
			final TransactionalBucketBPlusTree<Integer> tree = newTree(6);
			final TreeMap<Integer, List<Integer>> expected = contents(tree);
			assertFalse(tree.isRootInternal(), "self-check: the seeded tree must still be a single leaf");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 100; i < 140; i++) {
				tree.addRecord(i, i + 1);
			}
			assertTrue(tree.isRootInternal(), "self-check: the in-savepoint burst must have split the tree");
			assertNotEquals(expected, contents(tree), "self-check: the in-savepoint contents must differ");
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must restore the exact pre-savepoint contents.");
			assertFalse(tree.isRootInternal(), "Rollback must restore the pre-split single-leaf shape.");
			assertEquals(6, tree.bucketCount(), "Rollback must restore the bucket count.");
			assertConsistent(tree.getConsistencyReport(), "after a rolled-back split");
		}

		@Test
		@DisplayName("Rollback undoes bucket promotion to the overflow bitmap")
		void shouldRestoreSingleRecordBucketsAfterPromotion() {
			// a bucket that grows past one record is PROMOTED from the primitive record column to a TransactionalBitmap
			// held in the lazily allocated overflow column - two pieces of leaf state (the column reference and the
			// slot) that only this shape of mutation touches
			final TransactionalBucketBPlusTree<Integer> tree = newTree(6);
			final TreeMap<Integer, List<Integer>> expected = contents(tree);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 0; i < 6; i++) {
				tree.addRecord(i, 1000 + i);
				tree.addRecord(i, 2000 + i);
			}
			assertEquals(3, contents(tree).get(0).size(), "self-check: bucket 0 was promoted to a multi bucket");
			savepoint.rollback();

			assertEquals(
				expected, contents(tree),
				"Rollback must demote the promoted buckets back to their single pre-savepoint record."
			);
			assertConsistent(tree.getConsistencyReport(), "after a rolled-back promotion");
		}

		@Test
		@DisplayName("Rollback undoes a removal burst that merged leaves and collapsed the root")
		void shouldRestoreTreeAfterMergingRemovalBurst() {
			final TransactionalBucketBPlusTree<Integer> tree = newTree(60);
			final TreeMap<Integer, List<Integer>> expected = contents(tree);
			assertTrue(tree.isRootInternal(), "self-check: the seeded tree must span several leaves");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 0; i < 56; i++) {
				tree.removeRecord(i, i + 1);
			}
			assertFalse(tree.isRootInternal(), "self-check: the in-savepoint burst must have collapsed the root");
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must restore the exact pre-savepoint contents.");
			assertTrue(tree.isRootInternal(), "Rollback must restore the pre-collapse multi-leaf shape.");
			assertEquals(60, tree.bucketCount(), "Rollback must restore the bucket count.");
			assertConsistent(tree.getConsistencyReport(), "after a rolled-back merge cascade");
		}

		@Test
		@DisplayName("Commit keeps an insert burst that split the tree")
		void shouldKeepSplitOnCommit() {
			final TransactionalBucketBPlusTree<Integer> tree = newTree(6);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			for (int i = 100; i < 140; i++) {
				tree.addRecord(i, i + 1);
			}
			final TreeMap<Integer, List<Integer>> expected = contents(tree);
			savepoint.commit();

			assertEquals(expected, contents(tree), "Commit must keep every change made while the savepoint was open.");
			assertConsistent(tree.getConsistencyReport(), "after a committed split");
		}
	}

}
