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

package io.evitadb.index;

import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.hierarchy.HierarchyIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link TransactionalBitmap} and the composite indexes' own memoized state rewind their
 * non-transactional (WARM_UP) writes when the {@link WarmUpSavepoint} bracketing them is rolled back, and keep them
 * when it is committed.
 *
 * The bitmap journals like the array wrappers — one capture on the FIRST write-touch, covering every write that
 * follows — but unlike them it mutates its delegate IN PLACE, so the capture is a copy-on-write clone rather than a
 * free outgoing reference. What that makes worth testing beyond "a write was rewound" is interference: the tests below
 * drive several writes per savepoint, across every mutator overload including the bulk ones that reach roaring's
 * whole-container fast paths, and assert the clone taken before the first of them still describes the pre-savepoint
 * members after all of them.
 *
 * The composite indexes contribute nothing of their own to a rollback except their memoized caches — everything else
 * they hold is one of the wrapper structures covered by the sibling suites. A cache is left INVALIDATED rather than
 * restored, so the tests deliberately READ each index while the savepoint is open (which repopulates the cache from
 * half-mutated data) before rolling back: an implementation that only relied on the forward mutator's invalidation
 * passes without that read and fails with it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see WarmUpSavepointScalarAndArrayRollbackTest for the structures whose pre-image is a bare reference
 * @see WarmUpSavepointCollectionRollbackTest for the structures that journal per operation instead
 */
@Tag(ENGINE)
@Tag(TRANSACTION)
@DisplayName("Warm-up savepoint rollback of bitmaps and composite indexes")
class WarmUpSavepointIndexRollbackTest {

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
	@DisplayName("TransactionalBitmap")
	class Bitmaps {

		@Test
		@DisplayName("Rollback restores the record set through every mutator kind")
		void shouldRestoreRecordSetAfterMixedMutations() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 3, 5, 7);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			bitmap.add(2);
			bitmap.remove(3);
			bitmap.addAll(4, 6);
			bitmap.removeAll(1, 5);
			bitmap.addAll(new BaseBitmap(8, 9));
			bitmap.removeAll(new BaseBitmap(7, 8));
			assertArrayEquals(
				new int[]{2, 4, 6, 9}, bitmap.getArray(), "self-check on the in-savepoint state"
			);
			savepoint.rollback();

			assertArrayEquals(
				new int[]{1, 3, 5, 7}, bitmap.getArray(),
				"Rollback must restore the exact pre-savepoint record set."
			);
		}

		@Test
		@DisplayName("Rollback re-invalidates a cardinality memoized from the half-mutated bitmap")
		void shouldNotLeaveStaleMemoizedCardinality() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2, 3);
			assertEquals(3, bitmap.size(), "self-check: the pre-savepoint cardinality is memoized");

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			bitmap.addAll(4, 5);
			// reading the size INSIDE the savepoint is what makes this test bite: it recomputes the memo from the
			// half-mutated bitmap, so a restore that only swapped the delegate back would leave 5 behind
			assertEquals(5, bitmap.size(), "self-check: the in-savepoint cardinality is memoized");
			savepoint.rollback();

			assertEquals(3, bitmap.size(), "The memoized cardinality must describe the restored record set.");
			assertArrayEquals(new int[]{1, 2, 3}, bitmap.getArray());
		}

		@Test
		@DisplayName("Rollback restores members spread across several roaring containers")
		void shouldRestoreAcrossContainerBoundaries() {
			// three distinct chunk keys, so the clone shares three containers with the live bitmap and every write
			// below has to copy one of them out from under it rather than write through
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 70_000, 140_000);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			bitmap.add(2);
			bitmap.add(70_001);
			bitmap.remove(140_000);
			bitmap.addAll(new BaseBitmap(210_000));
			savepoint.rollback();

			assertArrayEquals(
				new int[]{1, 70_000, 140_000}, bitmap.getArray(),
				"An in-place write to a shared container must not reach the captured clone."
			);
			assertEquals(3, bitmap.size());
		}

		@Test
		@DisplayName("Rollback restores a bitmap emptied inside the savepoint")
		void shouldRestoreEmptiedBitmap() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2, 3);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			bitmap.removeAll(new BaseBitmap(1, 2, 3));
			assertTrue(bitmap.isEmpty(), "self-check: the bitmap was emptied inside the savepoint");
			savepoint.rollback();

			assertArrayEquals(new int[]{1, 2, 3}, bitmap.getArray());
			assertEquals(3, bitmap.size());
		}

		@Test
		@DisplayName("Rollback restores a bitmap that was empty before the savepoint opened")
		void shouldRestoreInitiallyEmptyBitmap() {
			final TransactionalBitmap bitmap = new TransactionalBitmap();

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			bitmap.addAll(1, 2, 3);
			savepoint.rollback();

			assertTrue(bitmap.isEmpty(), "A bitmap that held nothing must hold nothing again.");
			assertEquals(0, bitmap.size());
		}

		@Test
		@DisplayName("Each savepoint captures afresh, so successive rollbacks each restore their own pre-state")
		void shouldRestoreAcrossSuccessiveSavepoints() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2);

			final WarmUpSavepoint first = WarmUpSavepoint.open();
			bitmap.add(3);
			first.rollback();
			assertArrayEquals(new int[]{1, 2}, bitmap.getArray());

			// the second savepoint's baseline is a bitmap that IS the clone the first one captured; a capture that
			// aliased it would now hand the same instance back as its own pre-image and lose this write
			final WarmUpSavepoint second = WarmUpSavepoint.open();
			bitmap.add(4);
			second.commit();
			assertArrayEquals(new int[]{1, 2, 4}, bitmap.getArray());

			final WarmUpSavepoint third = WarmUpSavepoint.open();
			bitmap.remove(1);
			third.rollback();
			assertArrayEquals(
				new int[]{1, 2, 4}, bitmap.getArray(),
				"The third rollback must restore the state the second savepoint committed."
			);
		}

		@Test
		@DisplayName("Commit keeps the writes made inside the savepoint")
		void shouldKeepRecordSetOnCommit() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			bitmap.add(3);
			bitmap.removeAll(1);
			savepoint.commit();

			assertArrayEquals(new int[]{2, 3}, bitmap.getArray(), "Commit must keep the savepoint's writes.");
			assertEquals(2, bitmap.size());
		}

		@Test
		@DisplayName("Only the bitmaps actually written inside the savepoint are rewound")
		void shouldLeaveUntouchedBitmapsAlone() {
			final TransactionalBitmap touched = new TransactionalBitmap(1);
			final TransactionalBitmap untouched = new TransactionalBitmap(9);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			touched.add(2);
			savepoint.rollback();

			assertArrayEquals(new int[]{1}, touched.getArray(), "The written bitmap must be rewound.");
			assertArrayEquals(new int[]{9}, untouched.getArray(), "A bitmap nobody wrote must be left as it was.");
		}

		@Test
		@DisplayName("A no-op mutator leaves the bitmap untouched on rollback")
		void shouldTolerateNoOpMutations() {
			final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2);

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			// neither call changes anything - `add` of a present id and `remove` of an absent one short-circuit
			bitmap.add(1);
			bitmap.remove(3);
			savepoint.rollback();

			assertArrayEquals(new int[]{1, 2}, bitmap.getArray());
			assertEquals(2, bitmap.size());
		}
	}

	@Nested
	@DisplayName("HierarchyIndex")
	@Tag(HIERARCHY)
	class CompositeIndexes {

		/**
		 * Builds the same small forest every test in this class starts from: two roots (`1` and `2`), two children
		 * under `1`, one child under `2`, and one orphan whose parent does not exist.
		 *
		 * @return a freshly populated hierarchy index
		 */
		@Nonnull
		private HierarchyIndex newSeededHierarchy() {
			final HierarchyIndex index = new HierarchyIndex();
			index.addNode(1, null);
			index.addNode(2, null);
			index.addNode(3, 1);
			index.addNode(4, 1);
			index.addNode(5, 2);
			index.addNode(6, 99);
			return index;
		}

		@Test
		@DisplayName("Rollback restores the whole hierarchy, memoized formula included")
		void shouldRestoreHierarchyAndInvalidateMemoizedFormula() {
			final HierarchyIndex index = newSeededHierarchy();
			final int[] nodesBefore = index.listHierarchyNodesFromRoot().getArray();
			final int[] orphansBefore = index.getOrphanHierarchyNodes().getArray();
			final int[] allNodesBefore = index.getAllHierarchyNodesFormula().compute().getArray();

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			index.addNode(7, 3);
			index.removeNode(4);
			index.addNode(8, null);
			// the read INSIDE the savepoint is the point of this test: it repopulates the memoized formula from the
			// half-mutated hierarchy, so a rollback that rewound only the nodes would answer the next query from it
			final int[] allNodesInside = index.getAllHierarchyNodesFormula().compute().getArray();
			assertTrue(
				allNodesInside.length != allNodesBefore.length,
				"self-check: the in-savepoint batch must have changed the node set"
			);
			savepoint.rollback();

			assertArrayEquals(
				nodesBefore, index.listHierarchyNodesFromRoot().getArray(),
				"Rollback must restore the exact pre-savepoint node order."
			);
			assertArrayEquals(
				orphansBefore, index.getOrphanHierarchyNodes().getArray(),
				"Rollback must restore the orphans as well."
			);
			assertArrayEquals(
				allNodesBefore, index.getAllHierarchyNodesFormula().compute().getArray(),
				"The memoized formula must be recomputed from the restored hierarchy, not served stale."
			);
		}

		@Test
		@DisplayName("Rollback restores a node whose removal orphaned its children")
		void shouldRestoreChildrenOrphanedByARemoval() {
			final HierarchyIndex index = newSeededHierarchy();
			final int[] nodesBefore = index.listHierarchyNodesFromRoot().getArray();
			final int[] orphansBefore = index.getOrphanHierarchyNodes().getArray();

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			// removing a root pushes its whole subtree into the orphan collection - the widest single mutation this
			// index performs, touching the item index, the level index, the roots array and the orphans array at once
			index.removeNode(1);
			assertTrue(index.getOrphanHierarchyNodes().size() > orphansBefore.length, "self-check: children orphaned");
			savepoint.rollback();

			assertArrayEquals(nodesBefore, index.listHierarchyNodesFromRoot().getArray());
			assertArrayEquals(orphansBefore, index.getOrphanHierarchyNodes().getArray());
		}

		@Test
		@DisplayName("Rollback restores an orphan promoted by the arrival of its parent")
		void shouldRestorePromotedOrphan() {
			final HierarchyIndex index = newSeededHierarchy();
			final int[] nodesBefore = index.listHierarchyNodesFromRoot().getArray();
			final int[] orphansBefore = index.getOrphanHierarchyNodes().getArray();

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			// node 6 has been waiting for parent 99; creating it promotes 6 out of the orphans and into the tree
			index.addNode(99, null);
			assertEquals(0, index.getOrphanHierarchyNodes().size(), "self-check: the orphan was promoted");
			savepoint.rollback();

			assertArrayEquals(nodesBefore, index.listHierarchyNodesFromRoot().getArray());
			assertArrayEquals(
				orphansBefore, index.getOrphanHierarchyNodes().getArray(),
				"The promoted node must be an orphan again."
			);
		}

		@Test
		@DisplayName("Commit keeps the writes made inside the savepoint")
		void shouldKeepHierarchyOnCommit() {
			final HierarchyIndex index = newSeededHierarchy();

			final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
			index.addNode(7, 3);
			savepoint.commit();

			final Bitmap nodes = index.listHierarchyNodesFromRoot();
			assertTrue(nodes.contains(7), "Commit must keep the savepoint's writes.");
		}
	}

}
