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

package io.evitadb.index.hierarchy;

import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.index.component.EntityIndexManifest;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HierarchyIndexStoragePart;
import io.evitadb.utils.VMLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static io.evitadb.index.IndexHeapSizeAssertions.readField;
import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the lazy allocation of the four structures a {@link HierarchyIndex} is made of.
 *
 * # What is being defended
 *
 * Every entity index in the catalog owns a hierarchy index whether or not its entity type is hierarchical, and almost
 * none of them are: a production e-commerce catalog carried 564,187 entity indexes and 647 hierarchy nodes in total.
 * The node index, the per-parent children index and the two id arrays used to be built by the constructor and cost
 * 224 B per index that never held a node.
 *
 * The property that removes it is narrow: **the store must be allocated by a write and by nothing else**, and the
 * commit merge must not resurrect it for a hierarchy that committed nothing. The read-path test is the one that would
 * catch a future accessor quietly calling `getOrCreateNodeStore()` because it wanted a non-null map to call `get` on.
 *
 * The field is read reflectively because absence is exactly what is being asserted, and an unallocated store has no
 * public surface to observe — which is the point of it.
 *
 * @author Claude (lazy hierarchy node store), FG Forrest a.s. (c) 2026
 */
@DisplayName("Lazy hierarchy node store")
@Tag(INDEXING)
@Tag(HIERARCHY)
class LazyHierarchyNodeStoreTest {

	/**
	 * Accepts every node, so a traversal assertion is about the traversal rather than about the filter.
	 */
	private static final HierarchyFilteringPredicate ALL_NODES =
		HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE;

	/**
	 * @param index the index to inspect
	 * @return the node store, or `null` while no node has ever been written
	 */
	@Nullable
	private static Object nodeStoreOf(@Nonnull HierarchyIndex index) {
		return readField(index, "nodeStore");
	}

	/**
	 * What a {@link HierarchyIndex} that has never been written to must report: its own object and the dirty flag.
	 * The object holds an id and three references — the dirty flag, the node store and the memoized all-nodes bitmap
	 * — and those slots exist whether or not anything hangs off them.
	 *
	 * @return the expected heap size of an untouched hierarchy index in bytes
	 */
	private static long emptyIndexBytes() {
		final VMLayout layout = VMLayout.current();
		// the index object, then the transactional boolean it always owns (an id and the flag)
		return layout.sizeOfObject(Long.BYTES + 3L * layout.referenceSize())
			+ layout.sizeOfObject(Long.BYTES + 1L);
	}

	/**
	 * Builds a small three-level tree so a traversal has something to walk.
	 *
	 * @return an index holding 1 as a root, 2 and 3 as its children and 4 under 2
	 */
	@Nonnull
	private static HierarchyIndex seededIndex() {
		final HierarchyIndex index = new HierarchyIndex();
		index.addNode(1, null);
		index.addNode(2, 1);
		index.addNode(3, 1);
		index.addNode(4, 2);
		return index;
	}

	@Nested
	@DisplayName("An index nothing was ever written to")
	class UntouchedIndex {

		@Test
		@DisplayName("costs its shell and its dirty flag alone, a fifth of the 280 B it used to cost")
		void shouldCostOnlyItsShell() {
			final HierarchyIndex index = new HierarchyIndex();

			final long reported = index.getHeapSizeInBytes();
			assertEquals(
				emptyIndexBytes(), reported,
				"an untouched hierarchy index must weigh its object and its dirty flag, and nothing more"
			);
			// stated absolutely, as the production measurement was: on a 64-bit VM with compressed oops this is 56 B,
			// where the eagerly built index measured 280 B (48 B shell + 24 B dirty + 2 x 24 B id arrays + 2 x 80 B maps)
			assertTrue(
				reported <= 64,
				"an untouched hierarchy index must stay under 64 B, where it used to be 280 B - was " + reported
			);
		}

		@Test
		@DisplayName("allocates no node store at construction")
		void shouldAllocateNoNodeStoreAtConstruction() {
			assertNull(nodeStoreOf(new HierarchyIndex()), "a fresh hierarchy index must hold no store");
		}

		@Test
		@DisplayName("answers every read accessor without allocating one")
		void shouldNotAllocateTheStoreByReadingFromIt() {
			final HierarchyIndex index = new HierarchyIndex();

			assertTrue(index.isHierarchyIndexEmpty());
			assertEquals(0, index.getHierarchySize());
			assertEquals(0, index.getHierarchySizeIncludingOrphans());
			assertEquals(0, index.getRootHierarchyNodeCount(ALL_NODES));
			assertSame(EmptyBitmap.INSTANCE, index.getRootHierarchyNodes(ALL_NODES));
			assertSame(EmptyBitmap.INSTANCE, index.getOrphanHierarchyNodes());
			assertSame(EmptyBitmap.INSTANCE, index.getAllHierarchyNodes());
			assertSame(EmptyBitmap.INSTANCE, index.listHierarchyNodesFromRoot(ALL_NODES));
			assertSame(EmptyBitmap.INSTANCE, index.listHierarchyNodesFromRootDownTo(2, ALL_NODES));
			assertSame(EmptyBitmap.INSTANCE, index.getHierarchyNodesForParent(1, ALL_NODES));
			assertSame(EmptyBitmap.INSTANCE, index.listHierarchyNodesFromParentIncludingItself(1, ALL_NODES));
			assertEquals("Orphans: []", index.toString(), "an absent store renders as an empty hierarchy did");

			assertNull(nodeStoreOf(index), "reading an absent hierarchy must not bring its store into existence");
			assertEquals(
				emptyIndexBytes(), index.getHeapSizeInBytes(),
				"and must not change what the index weighs"
			);
		}

		@Test
		@DisplayName("hands back an empty formula rather than deferring a computation over nothing")
		void shouldReturnAnEmptyFormulaWhileTheStoreIsAbsent() {
			final HierarchyIndex index = new HierarchyIndex();

			// a deferred formula's memo key is built from the two structures' transactional ids, and an absent store
			// has none - there is also nothing to defer, because the answer is provably empty
			final Formula fromRoot = index.getListHierarchyNodesFromRootFormula(ALL_NODES);
			assertSame(EmptyFormula.INSTANCE, fromRoot);
			assertSame(EmptyFormula.INSTANCE, index.getRootHierarchyNodesFormula(ALL_NODES));
			assertSame(EmptyFormula.INSTANCE, index.getHierarchyNodesForParentFormula(1, ALL_NODES));
			assertSame(EmptyFormula.INSTANCE, index.getListHierarchyNodesFromParentFormula(1, ALL_NODES));
			assertSame(EmptyFormula.INSTANCE, index.getListHierarchyNodesFromParentIncludingItselfFormula(1, ALL_NODES));

			assertNull(nodeStoreOf(index), "asking for a formula must not allocate the store either");
		}

		@Test
		@DisplayName("writes no storage part when it holds nothing")
		void shouldWriteNoStoragePartWhenNothingWasIndexed() {
			final HierarchyIndex index = new HierarchyIndex();

			assertNull(index.createStoragePart(1), "a clean, empty hierarchy has nothing to persist");
			assertNull(nodeStoreOf(index));
		}
	}

	@Nested
	@DisplayName("The first node written")
	class FirstWrite {

		@Test
		@DisplayName("allocates the node store")
		void shouldAllocateTheStoreOnTheFirstNode() {
			final HierarchyIndex index = new HierarchyIndex();

			index.addNode(1, null);

			assertNotNull(nodeStoreOf(index), "the first node written must allocate the store");
			assertFalse(index.isHierarchyIndexEmpty());
			assertArrayEquals(new int[]{1}, index.getRootHierarchyNodes(ALL_NODES).getArray());
		}

		@Test
		@DisplayName("is what a bootstrap with no root avoids paying for")
		void shouldNotAllocateTheStoreForABootstrapWithNoRoot() {
			final HierarchyIndex index = new HierarchyIndex();

			index.initRootNodes(EmptyBitmap.INSTANCE);

			assertNull(nodeStoreOf(index), "a bootstrap that brings no node must not allocate the store");
			assertTrue(index.isHierarchyIndexEmpty());
		}

		@Test
		@DisplayName("still persists exactly what an eagerly allocated empty hierarchy persisted")
		void shouldPersistTheSameEmptyShapeAfterANoOpBootstrap() {
			final HierarchyIndex index = new HierarchyIndex();
			// initRootNodes marks the index dirty even when it brings nothing, so the flush still writes a part -
			// and that part must look exactly as it did when the four structures were always allocated
			index.initRootNodes(EmptyBitmap.INSTANCE);

			final StoragePart part = index.createStoragePart(1);
			assertInstanceOf(HierarchyIndexStoragePart.class, part);
			final HierarchyIndexStoragePart hierarchyPart = (HierarchyIndexStoragePart) part;
			assertEquals(0, hierarchyPart.getRoots().length, "no roots");
			assertEquals(0, hierarchyPart.getOrphans().length, "no orphans");
			assertEquals(0, hierarchyPart.getLevelIndex().length, "no level index entries");
			assertTrue(hierarchyPart.getItemIndex().isEmpty(), "no items");
		}
	}

	@Nested
	@DisplayName("Transactional lifecycle of the first node")
	class TransactionalLifecycle {

		@Test
		@DisplayName("is visible in the committed copy")
		void shouldMakeAFirstNodeVisibleAfterCommit() {
			final HierarchyIndex index = new HierarchyIndex();

			assertStateAfterCommit(
				index,
				original -> original.addNode(1, null),
				(original, committed) -> {
					assertNotNull(committed);
					assertNotNull(nodeStoreOf(committed), "the committed copy holds the node written");
					assertArrayEquals(new int[]{1}, committed.getRootHierarchyNodes(ALL_NODES).getArray());
				}
			);
		}

		@Test
		@DisplayName("leaves the store absent in a copy committed after the node was removed again")
		void shouldLeaveTheStoreAbsentInACopyCommittedFromAnEmptiedHierarchy() {
			final HierarchyIndex index = new HierarchyIndex();

			assertStateAfterCommit(
				index,
				original -> {
					original.addNode(1, null);
					original.removeNode(1);
				},
				(original, committed) -> {
					assertNotNull(committed);
					assertTrue(committed.isHierarchyIndexEmpty(), "the node was removed before the commit");
					// the four-argument constructor is the one place an emptied hierarchy gets to go back to holding
					// no store at all, and this is what proves it does rather than carrying it forward
					assertNull(nodeStoreOf(committed), "an emptied hierarchy must not carry its store forward");
					assertEquals(
						emptyIndexBytes(), committed.getHeapSizeInBytes(),
						"a snapshot that committed no node must weigh what a fresh index weighs"
					);
				}
			);
		}

		@Test
		@DisplayName("commits nothing when rolled back, and leaves behind only the store's empty shell")
		void shouldKeepNoNodeInAStoreMaterialisedByARolledBackWrite() {
			final HierarchyIndex index = new HierarchyIndex();

			assertStateAfterRollback(
				index,
				original -> original.addNode(1, null),
				(original, committed) -> {
					assertNull(committed, "a rolled-back transaction commits nothing");
					assertTrue(original.isHierarchyIndexEmpty(), "and leaves no node behind");
				}
			);

			// The residue this design accepts, pinned so it cannot silently grow: the store OBJECT stays on the
			// pre-commit instance, because a write inside a transaction has to have somewhere to put its diff. It is
			// bounded by what construction used to cost unconditionally, and the committed copy is rebuilt without it.
			assertNotNull(nodeStoreOf(index), "the rolled-back write did materialise the store");
			assertTrue(
				index.getHeapSizeInBytes() > emptyIndexBytes(),
				"and the empty structures it holds are charged honestly"
			);
		}
	}

	@Nested
	@DisplayName("A populated hierarchy")
	class PopulatedHierarchy {

		@Test
		@DisplayName("answers every accessor exactly as it did before the store became lazy")
		void shouldAnswerEveryAccessorOnAPopulatedHierarchy() {
			final HierarchyIndex index = seededIndex();

			assertFalse(index.isHierarchyIndexEmpty());
			assertEquals(4, index.getHierarchySizeIncludingOrphans());
			assertEquals(4, index.getHierarchySize());
			assertArrayEquals(new int[]{1}, index.getRootHierarchyNodes(ALL_NODES).getArray());
			assertEquals(1, index.getRootHierarchyNodeCount(ALL_NODES));
			assertArrayEquals(new int[]{1, 2, 3, 4}, index.getAllHierarchyNodes().getArray());
			assertArrayEquals(
				new int[]{2, 3, 4}, index.listHierarchyNodesFromParent(1, ALL_NODES).getArray());
			assertArrayEquals(
				new int[]{1, 2, 3}, index.listHierarchyNodesFromParentIncludingItselfDownTo(1, 1, ALL_NODES).getArray());
			assertEquals(3, index.getHierarchyNodeCountFromParent(1, ALL_NODES));
			assertEquals(2, index.getParentNode(4).orElseThrow());
			assertTrue(index.getOrphanHierarchyNodes().isEmpty(), "every node is reachable from the root");
		}

		@Test
		@DisplayName("keeps a node whose parent has not arrived yet as an orphan")
		void shouldStillCollectOrphans() {
			final HierarchyIndex index = new HierarchyIndex();

			index.addNode(9, 8);

			final Bitmap orphans = index.getOrphanHierarchyNodes();
			assertArrayEquals(new int[]{9}, orphans.getArray());
			assertInstanceOf(BaseBitmap.class, orphans, "a non-empty orphan set is still materialised per call");
			assertEquals(0, index.getHierarchySize(), "an orphan is not reachable from any root");
		}

		@Test
		@DisplayName("still writes the storage part it always wrote")
		void shouldPersistThePopulatedHierarchy() {
			final HierarchyIndex index = seededIndex();

			final TrappedChanges changes = new TrappedChanges();
			index.collectModifiedStorageParts(1, new EntityIndexManifest(), changes);
			assertTrue(changes.getTrappedChangesCount() > 0, "a dirty hierarchy must emit its part");

			final StoragePart part = index.createStoragePart(1);
			assertInstanceOf(HierarchyIndexStoragePart.class, part);
			final HierarchyIndexStoragePart hierarchyPart = (HierarchyIndexStoragePart) part;
			assertArrayEquals(new int[]{1}, hierarchyPart.getRoots());
			assertEquals(4, hierarchyPart.getItemIndex().size());
		}
	}
}
