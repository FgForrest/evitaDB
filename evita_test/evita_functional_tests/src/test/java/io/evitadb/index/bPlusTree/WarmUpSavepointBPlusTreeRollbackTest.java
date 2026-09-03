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
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BPlusLeafTreeNode;
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
import java.util.OptionalLong;
import java.util.TreeMap;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the five B+ trees rewind their non-transactional (WARM_UP) writes when the {@link WarmUpSavepoint}
 * bracketing them is rolled back, and keep them when it is committed.
 *
 * The trees journal at TWO granularities, and which one applies is a property of the write rather than of the tree.
 * {@link TransactionalObjectBPlusTree} still journals purely at NODE granularity: every node mutator resolves its diff
 * layer through {@link WarmUpSavepoint#writeLayer}, which on the layer-null branch captures the node's own bounded
 * memento the first time that node is write-touched. The other four take {@code perOperationWriteLayer} in
 * {@code insert} / {@code delete} and push a PER-SLOT inverse instead, capturing a whole-node memento only when a
 * structural change asks for one. Three rules make the two compose, and each is worth a test of its own:
 *
 * - a per-slot inverse is ABSOLUTE and KEY-ADDRESSED, never positional — it re-finds its slot by key when it runs,
 *   because a memento restored before it may have moved everything;
 * - it is pushed BEFORE the write it undoes, and gated on {@code savepoint != null && !savepoint.isCaptured(this)},
 *   so a node holding a whole-node memento never also journals per slot;
 * - a split calls {@code captureBeforeStructuralChange()} rather than inheriting a memento from whichever mutator
 *   called it. Reverse replay then runs that memento FIRST and the earlier per-slot inverses refine it.
 *
 * The tree-level root and size references journal separately (they are {@code TransactionalReference}s), and nodes
 * CREATED inside the savepoint need no inverse at all — restoring the old root and the parents' child pointers makes
 * them unreachable garbage.
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
		private static TransactionalObjectBPlusTree<Integer, Integer> newTree(int count) {
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
		private static TreeMap<Integer, Integer> contents(@Nonnull TransactionalObjectBPlusTree<Integer, Integer> tree) {
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
		private static TransactionalLongBPlusTree<Integer> newTree(int count) {
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
		private static TreeMap<Long, Integer> contents(@Nonnull TransactionalLongBPlusTree<Integer> tree) {
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
				// modulo, so a key repeats and the upsert takes its EXISTING-key branch interleaved with the splits
				// and merges around it - with a distinct key per round that branch is never entered at all
				tree.upsert(2000L + (i % 15), value -> value == null ? 1 : value + 1);
			}
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must restore the exact pre-savepoint contents.");
			assertConsistent(tree.getConsistencyReport(), "after a rolled-back interleaved churn");
		}

		@Test
		@DisplayName("Rollback undoes values replaced in place by upsert")
		void shouldRestoreValuesReplacedByUpsert() {
			final TransactionalLongBPlusTree<Integer> tree = newTree(40);
			final TreeMap<Long, Integer> expected = contents(tree);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			// every key already exists, so each upsert writes a slot IN PLACE rather than inserting. That write is
			// journalled by the slot inverse and by nothing else - it used to be rewindable only as a side effect of
			// markDirty() taking the leaf's whole-node memento, which per-slot journalling removed.
			for (int i = 0; i < 40; i++) {
				tree.upsert(i, value -> value == null ? 1 : value + 1);
			}
			assertNotEquals(expected, contents(tree), "self-check: the upserts must have changed every value");
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must restore every replaced value.");
			assertConsistent(tree.getConsistencyReport(), "after a rolled-back upsert replacement burst");
		}

		@Test
		@DisplayName("Rollback undoes values overwritten by insert at existing keys")
		void shouldRestoreValuesOverwrittenAtExistingKeys() {
			final TransactionalLongBPlusTree<Integer> tree = newTree(40);
			final TreeMap<Long, Integer> expected = contents(tree);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			// insert on an ALREADY PRESENT key overwrites the value slot instead of adding an entry - the same
			// in-place write as upsert takes, reached through the other entry point
			for (int i = 0; i < 40; i++) {
				tree.insert(i, -i);
			}
			assertNotEquals(expected, contents(tree), "self-check: the inserts must have overwritten every value");
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must restore every overwritten value.");
			assertConsistent(tree.getConsistencyReport(), "after a rolled-back overwrite burst");
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
		private static TransactionalIntToLongBPlusTree newTree(int count) {
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
		private static TreeMap<Integer, Long> contents(@Nonnull TransactionalIntToLongBPlusTree tree) {
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

		@Test
		@DisplayName("Rollback undoes values replaced in place by upsert")
		void shouldRestoreValuesReplacedByUpsert() {
			final TransactionalIntToLongBPlusTree tree = newTree(40);
			final TreeMap<Integer, Long> expected = contents(tree);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			// upsert reaches the value slot by a DIFFERENT route than insert does: it writes `values[existingIndex]`
			// straight after decoupleTransactionalArrays(), which journals nothing. An insert at an existing key is
			// journalled and passes even when this route is not, so the two need separate tests.
			for (int i = 0; i < 40; i++) {
				tree.upsert(i, value -> value + 1L);
			}
			assertNotEquals(expected, contents(tree), "self-check: the upserts must have changed every value");
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must restore every replaced value.");
		}

		@Test
		@DisplayName("Rollback undoes upserts interleaved with splits and merges")
		void shouldRestoreValuesUpsertedAcrossStructuralChurn() {
			final TransactionalIntToLongBPlusTree tree = newTree(40);
			final TreeMap<Integer, Long> expected = contents(tree);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			// proves the per-slot inverse survives a whole-node memento landing on top of it later in the savepoint
			for (int i = 0; i < 30; i++) {
				tree.insert(1000 + i, i * 10L);
				tree.delete(i % 15);
				tree.upsert(20 + (i % 15), value -> value + 1L);
			}
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must restore the exact pre-savepoint contents.");
		}

		@Test
		@DisplayName("Rollback undoes values overwritten at existing keys")
		void shouldRestoreValuesOverwrittenAtExistingKeys() {
			final TransactionalIntToLongBPlusTree tree = newTree(40);
			final TreeMap<Integer, Long> expected = contents(tree);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			// insert on an ALREADY PRESENT key overwrites the value slot instead of adding an entry. This is the tree
			// half that maps a record id to its order key, so this is also the write a container split makes when it
			// re-stamps the order keys of the records it moved.
			for (int i = 0; i < 40; i++) {
				tree.insert(i, -i * 10L);
			}
			assertNotEquals(expected, contents(tree), "self-check: the inserts must have overwritten every value");
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must restore every overwritten order key.");
		}
	}

	@Nested
	@DisplayName("TransactionalElementBPlusTree")
	class ElementTree {

		/**
		 * Element whose identity is only PART of it, so replacing an element at an existing key is observable. The
		 * fixture built on {@link Integer} keyed by {@link Integer#intValue()} cannot see such a replacement at all —
		 * element and key are the same value there, so a lost replacement inverse restores something indistinguishable
		 * from what it should have restored. Production keys elements this way (a price record by its internal price
		 * id), which is what makes the distinction worth carrying in the fixture.
		 *
		 * @param key     the element's identity in the tree
		 * @param payload the part of the element a replacement actually changes
		 */
		record KeyedPayload(int key, @Nonnull String payload) {
		}

		/**
		 * Builds a tree of `count` elements whose payload differs from their key, so an element REPLACED at an existing
		 * key is visible in {@link #payloads(TransactionalElementBPlusTree)}.
		 */
		@Nonnull
		private static TransactionalElementBPlusTree<KeyedPayload> newKeyedTree(int count) {
			final TransactionalElementBPlusTree<KeyedPayload> tree = new TransactionalElementBPlusTree<>(
				8, 3, 7, 3, KeyedPayload.class, KeyedPayload::key
			);
			for (int i = 0; i < count; i++) {
				tree.insert(new KeyedPayload(i, "original-" + i));
			}
			return tree;
		}

		/**
		 * Reads the keyed tree's whole logical content into a comparable reference value.
		 */
		@Nonnull
		private static List<KeyedPayload> payloads(@Nonnull TransactionalElementBPlusTree<KeyedPayload> tree) {
			final List<KeyedPayload> result = new ArrayList<>(tree.size());
			final Iterator<KeyedPayload> it = tree.valueIterator();
			while (it.hasNext()) {
				result.add(it.next());
			}
			return result;
		}

		@Test
		@DisplayName("Rollback undoes elements replaced at existing keys")
		void shouldRestoreElementsReplacedAtExistingKeys() {
			final TransactionalElementBPlusTree<KeyedPayload> tree = newKeyedTree(40);
			final List<KeyedPayload> expected = payloads(tree);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			// same key, different payload - the element slot is overwritten in place rather than added to
			for (int i = 0; i < 40; i++) {
				tree.insert(new KeyedPayload(i, "replaced-" + i));
			}
			assertNotEquals(expected, payloads(tree), "self-check: the inserts must have replaced every element");
			assertEquals(40, tree.size(), "self-check: replacing must not have grown the tree");
			savepoint.rollback();

			assertEquals(expected, payloads(tree), "Rollback must restore every replaced element.");
		}

		/**
		 * Builds a tree of `count` consecutive elements. The element is the key itself, so the tree stores no separate
		 * value array — the leaf memento is the element array plus the peek index.
		 */
		@Nonnull
		private static TransactionalElementBPlusTree<Integer> newTree(int count) {
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
		private static List<Integer> contents(@Nonnull TransactionalElementBPlusTree<Integer> tree) {
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
		private static TransactionalBucketBPlusTree<Integer> newTree(int count) {
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
		private static TreeMap<Integer, List<Integer>> contents(@Nonnull TransactionalBucketBPlusTree<Integer> tree) {
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

	/**
	 * Covers the per-OPERATION half of `TransactionalBucketBPlusTree.BPlusLeafTreeNode`'s journalling, one test per
	 * arm of the leaf mutators.
	 *
	 * The leaf is the one node in the family that does not take a whole-node memento for an ordinary bucket write: a
	 * memento duplicates both columns plus a clone of the overflow array, which is most of the mechanism's cost on the
	 * bulk-ingest profile for a write that reaches one slot. Each arm instead pushes an inverse restoring the single
	 * bucket it touches, and the arms that write nothing into the columns — a record joining a bitmap bucket, a record
	 * already held — push nothing at all.
	 *
	 * Every test therefore asserts TWO things, and the second is the one the contents comparison cannot see:
	 *
	 * - the rollback (or the commit) reaches the right state, as everywhere else in this class, and
	 * - {@link WarmUpSavepoint#isCaptured} still answers `false` for the leaf, i.e. the write did NOT fall back to a
	 *   whole-node memento. Without it a regression that quietly reinstated the memento would pass every test here
	 *   while giving back the whole optimization.
	 *
	 * The interplay tests are the exception: they deliberately drive a structural operation onto a leaf that already
	 * carries per-slot inverses, which is exactly when the memento SHOULD appear.
	 */
	@Nested
	@DisplayName("TransactionalBucketBPlusTree — per-operation leaf journalling")
	class BucketTreePerOperationJournalling {

		/**
		 * Builds a bucket tree of `count` consecutive single-record buckets, in leaves of eight.
		 */
		@Nonnull
		private static TransactionalBucketBPlusTree<Integer> newTree(int count) {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(
				8, 3, 7, 3, Integer.class, null
			);
			for (int i = 0; i < count; i++) {
				tree.addRecord(i, i + 1);
			}
			return tree;
		}

		/**
		 * Builds a `long`-payload bucket tree of `count` consecutive buckets, in leaves of eight.
		 */
		@Nonnull
		@SuppressWarnings({"unchecked", "rawtypes"})
		private static TransactionalBucketBPlusTree<Integer> newLongTree(int count) {
			final ValueColumnFactory factory = ValueColumnFactory.forKey(Integer.class, null);
			//noinspection unchecked
			final TransactionalBucketBPlusTree<Integer> tree = TransactionalBucketBPlusTree.withLongPayload(
				8, 3, 7, 3, Integer.class, null, factory
			);
			for (int i = 0; i < count; i++) {
				tree.addLongRecord(i, 1000L + i);
			}
			return tree;
		}

		/**
		 * Reads the whole bucket-to-records mapping into a comparable reference value.
		 */
		@Nonnull
		private static TreeMap<Integer, List<Integer>> contents(@Nonnull TransactionalBucketBPlusTree<Integer> tree) {
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

		/**
		 * Reads a `long`-payload tree's whole bucket-to-payload mapping into a comparable reference value.
		 */
		@Nonnull
		private static TreeMap<Integer, Long> longContents(@Nonnull TransactionalBucketBPlusTree<Integer> tree) {
			final TreeMap<Integer, Long> result = new TreeMap<>();
			for (int i = -20; i < 200; i++) {
				final OptionalLong payload = tree.getLongRecordEqualTo(i);
				if (payload.isPresent()) {
					result.put(i, payload.getAsLong());
				}
			}
			return result;
		}

		/**
		 * Returns the tree's only leaf. Every test that asserts on the overflow column or on the absence of a
		 * whole-node memento works on a single-leaf tree, so the mutation and the assertion cannot drift apart.
		 */
		@Nonnull
		private static BPlusLeafTreeNode<Integer> onlyLeaf(@Nonnull TransactionalBucketBPlusTree<Integer> tree) {
			final List<BPlusLeafTreeNode<Integer>> leaves = tree.enumerateLeaves();
			assertEquals(1, leaves.size(), "self-check: this test needs a single-leaf tree");
			return leaves.get(0);
		}

		/**
		 * Asserts the leaf has NOT been given a whole-node memento inside the open savepoint — i.e. the writes it just
		 * took were journalled per operation, which is the whole point of the mechanism under test.
		 */
		private static void assertJournalledPerOperation(
			@Nonnull WarmUpSavepoint savepoint,
			@Nonnull BPlusLeafTreeNode<Integer> leaf
		) {
			assertFalse(
				savepoint.isCaptured(leaf),
				"The leaf must journal this write per operation, not fall back to a whole-node memento."
			);
		}

		@Test
		@DisplayName("A record joining a bitmap bucket journals nothing in the leaf and rewinds through the bitmap")
		void shouldRewindMultiBucketAdditionThroughTheBitmapAlone() {
			final TransactionalBucketBPlusTree<Integer> tree = newTree(6);
			// promote bucket 0 BEFORE the savepoint, so the in-savepoint add lands on an existing bitmap
			tree.addRecord(0, 900);
			final TreeMap<Integer, List<Integer>> expected = contents(tree);
			final BPlusLeafTreeNode<Integer> leaf = onlyLeaf(tree);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			tree.addRecord(0, 901);
			assertEquals(List.of(1, 900, 901), contents(tree).get(0), "self-check: the bitmap took the new record");
			assertJournalledPerOperation(savepoint, leaf);
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must restore the bucket's pre-savepoint members.");
			assertConsistent(tree.getConsistencyReport(), "after a rolled-back multi-bucket addition");
		}

		@Test
		@DisplayName("Re-adding the record a single bucket already holds writes nothing and rewinds to itself")
		void shouldLeaveASingleBucketUntouchedWhenItsOwnRecordIsReAdded() {
			final TransactionalBucketBPlusTree<Integer> tree = newTree(6);
			final TreeMap<Integer, List<Integer>> expected = contents(tree);
			final BPlusLeafTreeNode<Integer> leaf = onlyLeaf(tree);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			tree.addRecord(3, 4);
			assertEquals(expected, contents(tree), "self-check: re-adding the held record is a no-op");
			assertJournalledPerOperation(savepoint, leaf);
			assertNull(leaf.getOverflow(), "self-check: a no-op must not allocate the overflow column");
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must leave the untouched bucket exactly as it was.");
			assertNull(leaf.getOverflow(), "Rollback must not have created an overflow column either.");
			assertConsistent(tree.getConsistencyReport(), "after a rolled-back no-op addition");
		}

		@Test
		@DisplayName("Rollback demotes a promoted bucket and drops the overflow column the promotion created")
		void shouldDemoteAPromotedBucketAndDropTheOverflowColumnItCreated() {
			final TransactionalBucketBPlusTree<Integer> tree = newTree(6);
			final TreeMap<Integer, List<Integer>> expected = contents(tree);
			final BPlusLeafTreeNode<Integer> leaf = onlyLeaf(tree);
			assertNull(leaf.getOverflow(), "self-check: the seeded leaf carries no overflow column");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			tree.addRecord(2, 777);
			assertEquals(List.of(3, 777), contents(tree).get(2), "self-check: bucket 2 was promoted");
			assertNotNull(leaf.getOverflow(), "self-check: the promotion allocated the overflow column");
			assertJournalledPerOperation(savepoint, leaf);
			savepoint.rollback();

			assertEquals(
				expected, contents(tree),
				"Rollback must demote the bucket back to the single record the record column still held."
			);
			assertNull(
				leaf.getOverflow(),
				"Rollback must drop the overflow column the promotion created, not leave an empty one behind."
			);
			assertConsistent(tree.getConsistencyReport(), "after a rolled-back promotion");
		}

		@Test
		@DisplayName("A promotion into an existing overflow column keeps that column on rollback")
		void shouldKeepAPreExistingOverflowColumnWhenDemotingAPromotedBucket() {
			final TransactionalBucketBPlusTree<Integer> tree = newTree(6);
			// bucket 5 is multi BEFORE the savepoint, so the leaf already owns an overflow column the rollback keeps
			tree.addRecord(5, 950);
			final TreeMap<Integer, List<Integer>> expected = contents(tree);
			final BPlusLeafTreeNode<Integer> leaf = onlyLeaf(tree);
			assertNotNull(leaf.getOverflow(), "self-check: the seeded leaf already carries an overflow column");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			tree.addRecord(1, 778);
			assertJournalledPerOperation(savepoint, leaf);
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must demote only the bucket the promotion touched.");
			assertNotNull(
				leaf.getOverflow(),
				"Rollback must NOT drop an overflow column that already existed before the savepoint."
			);
			assertConsistent(tree.getConsistencyReport(), "after a rolled-back promotion into an existing column");
		}

		@Test
		@DisplayName("Rollback deletes a bucket the savepoint inserted")
		void shouldDeleteABucketInsertedInsideTheSavepoint() {
			final TransactionalBucketBPlusTree<Integer> tree = newTree(6);
			final TreeMap<Integer, List<Integer>> expected = contents(tree);
			final BPlusLeafTreeNode<Integer> leaf = onlyLeaf(tree);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			tree.addRecord(50, 51);
			assertEquals(7, tree.bucketCount(), "self-check: the new bucket landed");
			assertJournalledPerOperation(savepoint, leaf);
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must remove the bucket the savepoint inserted.");
			assertEquals(6, tree.bucketCount(), "Rollback must restore the bucket count.");
			assertConsistent(tree.getConsistencyReport(), "after a rolled-back bucket insertion");
		}

		@Test
		@DisplayName("Rollback deletes a multi-record bucket the savepoint inserted, column and all")
		void shouldDeleteAMultiRecordBucketInsertedInsideTheSavepoint() {
			final TransactionalBucketBPlusTree<Integer> tree = newTree(6);
			final TreeMap<Integer, List<Integer>> expected = contents(tree);
			final BPlusLeafTreeNode<Integer> leaf = onlyLeaf(tree);
			assertNull(leaf.getOverflow(), "self-check: the seeded leaf carries no overflow column");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			tree.addRecord(50, 51, 52, 53);
			assertEquals(List.of(51, 52, 53), contents(tree).get(50), "self-check: the new bucket is a multi bucket");
			assertJournalledPerOperation(savepoint, leaf);
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must remove the multi bucket the savepoint inserted.");
			assertNull(
				leaf.getOverflow(),
				"Rollback must drop the overflow column the multi-bucket insertion created."
			);
			assertConsistent(tree.getConsistencyReport(), "after a rolled-back multi-bucket insertion");
		}

		@Test
		@DisplayName("Rollback demotes a bucket promoted by a bulk addition")
		void shouldDemoteABucketPromotedByABulkAddition() {
			final TransactionalBucketBPlusTree<Integer> tree = newTree(6);
			final TreeMap<Integer, List<Integer>> expected = contents(tree);
			final BPlusLeafTreeNode<Integer> leaf = onlyLeaf(tree);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			tree.addRecord(4, 801, 802, 803);
			assertEquals(List.of(5, 801, 802, 803), contents(tree).get(4), "self-check: bucket 4 was promoted");
			assertJournalledPerOperation(savepoint, leaf);
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must demote the bulk-promoted bucket.");
			assertNull(leaf.getOverflow(), "Rollback must drop the overflow column the promotion created.");
			assertConsistent(tree.getConsistencyReport(), "after a rolled-back bulk promotion");
		}

		@Test
		@DisplayName("A partial removal from a bitmap bucket journals nothing in the leaf")
		void shouldRewindAPartialMultiBucketRemovalThroughTheBitmapAlone() {
			final TransactionalBucketBPlusTree<Integer> tree = newTree(6);
			tree.addRecord(0, 900, 901);
			final TreeMap<Integer, List<Integer>> expected = contents(tree);
			final BPlusLeafTreeNode<Integer> leaf = onlyLeaf(tree);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			tree.removeRecord(0, 900);
			assertEquals(List.of(1, 901), contents(tree).get(0), "self-check: the bucket survived the removal");
			assertJournalledPerOperation(savepoint, leaf);
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must put the removed member back into the bitmap.");
			assertConsistent(tree.getConsistencyReport(), "after a rolled-back partial removal");
		}

		@Test
		@DisplayName("Rollback re-inserts a drained bitmap bucket and the bitmap then refills it")
		void shouldReinsertADrainedBucketBeforeItsBitmapIsRefilled() {
			// the ordering test: the bitmap's own removeAll inverse is pushed BEFORE the leaf's delete inverse, so
			// reverse replay must re-attach the (empty) bucket first and only then put its members back
			final TransactionalBucketBPlusTree<Integer> tree = newTree(6);
			tree.addRecord(3, 930, 931);
			final TreeMap<Integer, List<Integer>> expected = contents(tree);
			final BPlusLeafTreeNode<Integer> leaf = onlyLeaf(tree);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			tree.removeRecord(3, 4, 930, 931);
			assertFalse(tree.contains(3), "self-check: the drained bucket was deleted");
			assertEquals(5, tree.bucketCount(), "self-check: the bucket count dropped");
			assertJournalledPerOperation(savepoint, leaf);
			savepoint.rollback();

			assertEquals(
				expected, contents(tree),
				"Rollback must re-insert the drained bucket AND refill it with every member it held."
			);
			assertEquals(6, tree.bucketCount(), "Rollback must restore the bucket count.");
			assertConsistent(tree.getConsistencyReport(), "after a rolled-back drain");
		}

		@Test
		@DisplayName("Rollback re-inserts a deleted single-record bucket with the record it held")
		void shouldReinsertADeletedSingleRecordBucket() {
			final TransactionalBucketBPlusTree<Integer> tree = newTree(6);
			final TreeMap<Integer, List<Integer>> expected = contents(tree);
			final BPlusLeafTreeNode<Integer> leaf = onlyLeaf(tree);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			tree.removeRecord(2, 3);
			assertFalse(tree.contains(2), "self-check: the single bucket was deleted");
			assertJournalledPerOperation(savepoint, leaf);
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must re-insert the bucket with its record.");
			assertConsistent(tree.getConsistencyReport(), "after a rolled-back single-bucket deletion");
		}

		@Test
		@DisplayName("A removal matching nothing in a single bucket writes nothing")
		void shouldLeaveASingleBucketUntouchedWhenNoRemovedIdMatches() {
			final TransactionalBucketBPlusTree<Integer> tree = newTree(6);
			final TreeMap<Integer, List<Integer>> expected = contents(tree);
			final BPlusLeafTreeNode<Integer> leaf = onlyLeaf(tree);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			tree.removeRecord(2, 12345);
			assertEquals(expected, contents(tree), "self-check: a non-matching removal is a no-op");
			assertJournalledPerOperation(savepoint, leaf);
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must leave the untouched bucket exactly as it was.");
			assertConsistent(tree.getConsistencyReport(), "after a rolled-back non-matching removal");
		}

		@Test
		@DisplayName("Rollback restores the record of a bucket promoted and then drained in one savepoint")
		void shouldRestoreTheRecordOfABucketPromotedAndThenDrained() {
			// the minimal case where two inverses for one key have to compose: the deletion's inverse rebuilds the
			// bucket's slot, and the promotion's inverse - pushed earlier, so replayed LATER - then demotes it back to
			// the single form and reads the record column again. Neither inverse may rely on what the other left in
			// that slot, or the bucket comes back holding the multi form's don't-care value instead of its record
			final TransactionalBucketBPlusTree<Integer> tree = newTree(6);
			final TreeMap<Integer, List<Integer>> expected = contents(tree);
			final BPlusLeafTreeNode<Integer> leaf = onlyLeaf(tree);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			tree.addRecord(2, 500);
			assertEquals(List.of(3, 500), contents(tree).get(2), "self-check: bucket 2 was promoted");
			tree.removeRecord(2, 3, 500);
			assertFalse(tree.contains(2), "self-check: the promoted bucket was then drained and deleted");
			assertJournalledPerOperation(savepoint, leaf);
			savepoint.rollback();

			assertEquals(
				expected, contents(tree),
				"Rollback must give bucket 2 back its pre-savepoint record, not the multi form's don't-care value."
			);
			assertNull(leaf.getOverflow(), "Rollback must drop the overflow column the promotion created.");
			assertConsistent(tree.getConsistencyReport(), "after a rolled-back promote-then-drain of one key");
		}

		@Test
		@DisplayName("Rollback collapses every write made to one key inside a single savepoint")
		void shouldCollapseRepeatedWritesToTheSameKey() {
			// the per-operation contract in one test: the EARLIEST inverse for a slot replays LAST and wins, so a key
			// inserted, promoted, drained and re-inserted inside one savepoint still ends up absent
			final TransactionalBucketBPlusTree<Integer> tree = newTree(6);
			final TreeMap<Integer, List<Integer>> expected = contents(tree);
			final BPlusLeafTreeNode<Integer> leaf = onlyLeaf(tree);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			tree.addRecord(60, 61);
			tree.addRecord(60, 62);
			tree.addRecord(60, 63);
			tree.removeRecord(60, 61, 62, 63);
			tree.addRecord(60, 64);
			tree.addRecord(2, 500);
			tree.removeRecord(2, 3, 500);
			assertJournalledPerOperation(savepoint, leaf);
			savepoint.rollback();

			assertEquals(
				expected, contents(tree),
				"Rollback must collapse every write made to a key back to its pre-savepoint state."
			);
			assertEquals(6, tree.bucketCount(), "Rollback must restore the bucket count.");
			assertConsistent(tree.getConsistencyReport(), "after rolling back repeated writes to one key");
		}

		@Test
		@DisplayName("Commit keeps every per-operation write, promotions and deletions alike")
		void shouldKeepEveryPerOperationWriteOnCommit() {
			final TransactionalBucketBPlusTree<Integer> tree = newTree(6);
			final BPlusLeafTreeNode<Integer> leaf = onlyLeaf(tree);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			tree.addRecord(0, 900);
			tree.addRecord(0, 901);
			tree.addRecord(50, 51);
			tree.addRecord(51, 52, 53);
			tree.removeRecord(2, 3);
			tree.removeRecord(1, 2);
			final TreeMap<Integer, List<Integer>> expected = contents(tree);
			assertJournalledPerOperation(savepoint, leaf);
			savepoint.commit();

			assertEquals(expected, contents(tree), "Commit must keep every change made while the savepoint was open.");
			assertConsistent(tree.getConsistencyReport(), "after a committed per-operation burst");
		}

		@Test
		@DisplayName("Rollback undoes per-slot writes that a later split on the same leaf did not capture")
		void shouldRewindPerSlotWritesAcrossASplitOfTheSameLeaf() {
			// a leaf split copies both halves into FRESH columns and leaves the former leaf untouched, so the per-slot
			// inverses recorded before it still address the very columns they were recorded against
			final TransactionalBucketBPlusTree<Integer> tree = newTree(6);
			final TreeMap<Integer, List<Integer>> expected = contents(tree);
			assertFalse(tree.isRootInternal(), "self-check: the seeded tree must still be a single leaf");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			tree.addRecord(0, 900);
			tree.addRecord(50, 51);
			tree.addRecord(51, 52);
			assertTrue(tree.isRootInternal(), "self-check: the burst must have split the leaf");
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must restore the exact pre-savepoint contents.");
			assertFalse(tree.isRootInternal(), "Rollback must restore the pre-split single-leaf shape.");
			assertConsistent(tree.getConsistencyReport(), "after a rolled-back split over per-slot writes");
		}

		@Test
		@DisplayName("Rollback undoes per-slot writes that a later rebalance of the same leaf captured wholesale")
		void shouldRewindPerSlotWritesAcrossARebalanceOfTheSameLeaf() {
			// the interplay the design rests on: the structural operation takes a whole-node memento of the leaf's
			// MID-savepoint state, which reverse replay installs FIRST; the older per-slot inverses then refine
			// exactly the slots they had overwritten, back to the pre-savepoint value
			final TransactionalBucketBPlusTree<Integer> tree = newTree(40);
			final TreeMap<Integer, List<Integer>> expected = contents(tree);
			assertTrue(tree.isRootInternal(), "self-check: the seeded tree must span several leaves");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			// per-slot writes across the first leaves...
			tree.addRecord(0, 900);
			tree.addRecord(1, 901, 902);
			tree.addRecord(2, 903);
			// ...then drain those leaves below the minimum occupancy so the rebalancer steals and merges into them
			for (int i = 3; i < 20; i++) {
				tree.removeRecord(i, i + 1);
			}
			savepoint.rollback();

			assertEquals(expected, contents(tree), "Rollback must restore the exact pre-savepoint contents.");
			assertEquals(40, tree.bucketCount(), "Rollback must restore the bucket count.");
			assertConsistent(tree.getConsistencyReport(), "after a rolled-back rebalance over per-slot writes");
		}

		@Test
		@DisplayName("Commit keeps per-slot writes a later rebalance of the same leaf captured wholesale")
		void shouldKeepPerSlotWritesAcrossARebalanceOnCommit() {
			final TransactionalBucketBPlusTree<Integer> tree = newTree(40);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			tree.addRecord(0, 900);
			tree.addRecord(1, 901, 902);
			for (int i = 3; i < 20; i++) {
				tree.removeRecord(i, i + 1);
			}
			final TreeMap<Integer, List<Integer>> expected = contents(tree);
			savepoint.commit();

			assertEquals(expected, contents(tree), "Commit must keep every change made while the savepoint was open.");
			assertConsistent(tree.getConsistencyReport(), "after a committed rebalance over per-slot writes");
		}

		@Test
		@DisplayName("Rollback deletes a long-payload bucket the savepoint inserted")
		void shouldDeleteALongPayloadBucketInsertedInsideTheSavepoint() {
			final TransactionalBucketBPlusTree<Integer> tree = newLongTree(6);
			final TreeMap<Integer, Long> expected = longContents(tree);
			final BPlusLeafTreeNode<Integer> leaf = onlyLeaf(tree);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			tree.addLongRecord(50, Long.MIN_VALUE + 7);
			assertEquals(
				Long.MIN_VALUE + 7, tree.getLongRecordEqualTo(50).orElseThrow(),
				"self-check: the long-payload bucket landed"
			);
			assertJournalledPerOperation(savepoint, leaf);
			savepoint.rollback();

			assertEquals(expected, longContents(tree), "Rollback must remove the long-payload bucket.");
			assertFalse(tree.contains(50), "Rollback must leave the key absent.");
			assertConsistent(tree.getConsistencyReport(), "after a rolled-back long-payload insertion");
		}

		@Test
		@DisplayName("Commit keeps a long-payload bucket the savepoint inserted")
		void shouldKeepALongPayloadBucketOnCommit() {
			final TransactionalBucketBPlusTree<Integer> tree = newLongTree(6);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			tree.addLongRecord(50, 4242L);
			final TreeMap<Integer, Long> expected = longContents(tree);
			savepoint.commit();

			assertEquals(expected, longContents(tree), "Commit must keep the long-payload bucket.");
			assertConsistent(tree.getConsistencyReport(), "after a committed long-payload insertion");
		}

		@Test
		@DisplayName("Rollback restores a long-payload bucket the savepoint removed")
		void shouldRestoreALongPayloadBucketRemovedInsideTheSavepoint() {
			// removeLongRecord keeps the whole-node memento (it is not one of the converted arms), so this test also
			// pins that the two granularities coexist on the same leaf without either losing a write
			final TransactionalBucketBPlusTree<Integer> tree = newLongTree(6);
			final TreeMap<Integer, Long> expected = longContents(tree);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			tree.addLongRecord(50, 4242L);
			assertTrue(tree.removeLongRecord(3), "self-check: the bucket was removed");
			savepoint.rollback();

			assertEquals(expected, longContents(tree), "Rollback must restore the removed long-payload bucket.");
			assertConsistent(tree.getConsistencyReport(), "after a rolled-back long-payload removal");
		}
	}

}
