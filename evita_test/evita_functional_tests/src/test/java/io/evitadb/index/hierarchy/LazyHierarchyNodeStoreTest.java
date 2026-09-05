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

import io.evitadb.api.query.order.TraversalMode;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.component.EntityIndexManifest;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HierarchyIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HierarchyIndexStoragePart.LevelIndex;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.VMLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static io.evitadb.index.IndexHeapSizeAssertions.readField;
import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * Much of this file is counterfactual proof rather than behaviour coverage: every test in {@link UntouchedIndex},
 * and every `assertNull(nodeStoreOf(...))` elsewhere, fails the moment eager allocation is restored. Each one pins
 * a different allocation site — construction, a read, a formula, a traversal, a persist, a bootstrap that brings
 * nothing, the commit merge, a reload — so the apparent redundancy is the point and none may be merged away.
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
	 * Reads the store field directly — absence is what is asserted, and it has no public surface.
	 *
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

	/**
	 * Asserts that a read of a primary key no hierarchy holds fails identically on an index that never received a node
	 * and on an index holding a tree — the contract is "the same failure", not a literal message this test owns — and
	 * that the failure left the absent store absent.
	 *
	 * @param storeless a hierarchy index no node has ever been written to
	 * @param populated a hierarchy index holding a tree, asked for the same unknown primary key
	 * @param read      the accessor to invoke on both
	 */
	private static void assertFailsAlike(
		@Nonnull HierarchyIndex storeless,
		@Nonnull HierarchyIndex populated,
		@Nonnull Consumer<HierarchyIndex> read
	) {
		final EvitaInvalidUsageException onPopulated = assertThrows(
			EvitaInvalidUsageException.class, () -> read.accept(populated),
			"a hierarchy holding nodes must reject a key it does not hold"
		);
		final EvitaInvalidUsageException onStoreless = assertThrows(
			EvitaInvalidUsageException.class, () -> read.accept(storeless),
			"a hierarchy that never received a node must reject it the same way"
		);
		assertEquals(
			onPopulated.getMessage(), onStoreless.getMessage(),
			"an absent store must fail with the message an unknown primary key always produced"
		);
		assertNull(nodeStoreOf(storeless), "a read that failed to find a node must not allocate the store");
	}

	/**
	 * The property every read must uphold: neither an accessor, a formula, a traversal, a persist nor a bootstrap that
	 * brings no node may bring the store into existence, and a key the hierarchy does not hold must fail as it always
	 * failed.
	 */
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
			final HierarchyIndex index = new HierarchyIndex();

			assertNull(nodeStoreOf(index), "a fresh hierarchy index must hold no store");
			// the public-API companion of the assertion above: it says the same thing without naming a field, so
			// a rename of `nodeStore` degrades into a clear failure rather than a silently vacuous test
			assertEquals(
				emptyIndexBytes(), index.getHeapSizeInBytes(),
				"and must weigh what an index holding no store weighs"
			);
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
			assertEquals(
				emptyIndexBytes(), index.getHeapSizeInBytes(),
				"and must not change what the index weighs"
			);
		}

		@Test
		@DisplayName("writes no storage part when it holds nothing")
		void shouldWriteNoStoragePartWhenNothingWasIndexed() {
			final HierarchyIndex index = new HierarchyIndex();

			assertNull(index.createStoragePart(1), "a clean, empty hierarchy has nothing to persist");
			assertNull(nodeStoreOf(index));
			assertEquals(
				emptyIndexBytes(), index.getHeapSizeInBytes(),
				"and asking it to persist itself must not have allocated anything"
			);
		}

		@Test
		@DisplayName("traverses and counts nothing without allocating a store")
		void shouldNotAllocateTheStoreByTraversingOrCountingIt() {
			final HierarchyIndex index = new HierarchyIndex();
			final AtomicInteger visited = new AtomicInteger();
			final HierarchyVisitor countingVisitor = (node, level, distance, traverser) -> visited.incrementAndGet();

			assertSame(
				EmptyBitmap.INSTANCE,
				index.listHierarchyNodesFromRoot(TraversalMode.DEPTH_FIRST, UnaryOperator.identity())
			);
			assertSame(
				EmptyBitmap.INSTANCE,
				index.listHierarchyNodesFromRoot(TraversalMode.BREADTH_FIRST, UnaryOperator.identity())
			);
			assertSame(EmptyBitmap.INSTANCE, index.listHierarchyNodesFromParent(1, ALL_NODES));
			assertSame(EmptyBitmap.INSTANCE, index.listHierarchyNodesFromParentIncludingItselfDownTo(1, 1, ALL_NODES));
			assertSame(EmptyBitmap.INSTANCE, index.listNodesIncludingParents(EmptyBitmap.INSTANCE));
			assertEquals(0, index.getHierarchyNodeCountFromRootDownTo(1, ALL_NODES));
			assertEquals(0, index.getHierarchyNodeCountFromParent(1, ALL_NODES));
			assertEquals(0, index.getHierarchyNodeCountForParent(1, ALL_NODES));

			index.traverseHierarchy(countingVisitor, ALL_NODES);
			index.traverseHierarchyFromNode(countingVisitor, 1, false, ALL_NODES);
			index.traverseHierarchyToRoot(countingVisitor, 1);
			assertEquals(0, visited.get(), "there is no node to hand to a visitor, so it must never be called");

			assertNull(nodeStoreOf(index), "walking an absent hierarchy must not bring its store into existence");
			assertEquals(
				emptyIndexBytes(), index.getHeapSizeInBytes(),
				"and must not change what the index weighs"
			);
		}

		@Test
		@DisplayName("fails an unknown-node read exactly as a populated hierarchy fails it")
		void shouldFailTheSameWayAnUnknownNodeAlwaysFailed() {
			final HierarchyIndex index = new HierarchyIndex();
			final HierarchyIndex populated = seededIndex();

			assertFailsAlike(index, populated, hierarchy -> hierarchy.getParentNode(99));
			assertFailsAlike(index, populated, hierarchy -> hierarchy.listNodesIncludingParents(new BaseBitmap(99)));
			assertFailsAlike(index, populated, hierarchy -> hierarchy.removeNode(99));

			// the contract the two reads below are measured against: a hierarchy holding nodes rejects a parent it
			// does not hold, and both of them declare that rejection in their own javadoc
			assertEquals(
				"Parent node `99` is not present in the index!",
				assertThrows(
					EvitaInvalidUsageException.class,
					() -> populated.listHierarchyNodesFromParentDownTo(99, 1, ALL_NODES)
				).getMessage()
			);
			assertEquals(
				"Parent node `99` is not present in the index!",
				assertThrows(
					EvitaInvalidUsageException.class,
					() -> populated.getHierarchyNodeCountFromParentDownTo(99, 1, ALL_NODES)
				).getMessage()
			);

			assertFailsAlike(index, populated, hierarchy -> hierarchy.listHierarchyNodesFromParentDownTo(99, 1, ALL_NODES));
			assertFailsAlike(index, populated, hierarchy -> hierarchy.getHierarchyNodeCountFromParentDownTo(99, 1, ALL_NODES));

			assertNull(nodeStoreOf(index), "none of the reads above may allocate the store");
		}

		@Test
		@DisplayName("writes its empty part without announcing a hierarchy in the manifest")
		void shouldNotAnnounceAHierarchyItDoesNotHave() {
			final HierarchyIndex index = new HierarchyIndex();
			// the bootstrap marks the index dirty even though it brought no node, so the flush still owes a part
			index.initRootNodes(EmptyBitmap.INSTANCE);

			final EntityIndexManifest manifest = new EntityIndexManifest();
			final TrappedChanges changes = new TrappedChanges();
			index.collectModifiedStorageParts(1, manifest, changes);

			assertEquals(1, changes.getTrappedChangesCount(), "a dirty hierarchy owes its part whether or not it holds nodes");
			// the one place the part write and the presence flag deliberately disagree: the loader treats a raised flag
			// with no part behind it as corruption, never the other way round
			assertFalse(
				manifest.isHierarchyPresent(),
				"a hierarchy holding no node must not announce itself as present"
			);
			assertNull(nodeStoreOf(index), "and must not have allocated a store to write that part");
		}
	}

	/**
	 * The write that allocates the store, and the writes that must not.
	 *
	 * There is deliberately no multi-threaded `addNode` stress test here. The four structures inside the store are not
	 * thread-safe outside a transaction, so racing two writers races a `HashMap` rather than the publication lock, and
	 * the transactional helpers this suite uses run a single transaction on the calling thread and cannot express two
	 * concurrent transactional layers. {@link FirstWrite#shouldPublishOneStoreAndNeverReplaceIt()} is the
	 * deterministic form of the guarantee the double-checked lock exists to give: whatever the interleaving, an index
	 * has one store for its whole life.
	 */
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
			// the public-API companion: `isHierarchyIndexEmpty()` is true under eager allocation too, so without this
			// line the test would keep passing if the reflective lookup above ever stopped resolving
			assertEquals(
				emptyIndexBytes(), index.getHeapSizeInBytes(),
				"and must leave the index weighing what a fresh one weighs"
			);
		}

		@Test
		@DisplayName("allocates the node store for a bootstrap that brings roots")
		void shouldAllocateTheStoreForABootstrapThatBringsRoots() {
			final HierarchyIndex index = new HierarchyIndex();

			index.initRootNodes(new BaseBitmap(10, 20));

			assertNotNull(nodeStoreOf(index), "a bootstrap that brings roots must allocate the store");
			assertArrayEquals(new int[]{10, 20}, index.getRootHierarchyNodes(ALL_NODES).getArray());
			assertEquals(2, index.getHierarchySize());
			assertFalse(index.isHierarchyIndexEmpty());
		}

		@Test
		@DisplayName("publishes one store and never replaces it")
		void shouldPublishOneStoreAndNeverReplaceIt() {
			final HierarchyIndex index = new HierarchyIndex();
			index.addNode(1, null);
			final Object published = nodeStoreOf(index);
			assertNotNull(published, "the first node written publishes the store");

			// every shape of write the index knows: a child, an orphan, a re-parenting, a removal down to empty and
			// a write that starts the hierarchy over again
			index.addNode(2, 1);
			index.addNode(3, 99);
			index.addNode(2, null);
			index.removeNode(2);
			index.removeNode(1);
			index.removeNode(3);
			assertTrue(index.isHierarchyIndexEmpty(), "the hierarchy was emptied in place");
			index.addNode(4, null);

			// a second store instance is exactly the failure the double-checked publication exists to prevent: the
			// loser's diff layers would key on an orphaned instance and its writes would vanish at commit
			assertSame(published, nodeStoreOf(index), "the published store must never be replaced");
			assertArrayEquals(new int[]{4}, index.getRootHierarchyNodes(ALL_NODES).getArray());
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

	/**
	 * What commit and rollback do with the store: carry it into the copy for a node that survives, drop it again for a
	 * hierarchy emptied before the merge, and leave the pre-commit instance holding the shell a rolled-back write
	 * materialised.
	 */
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

		@Test
		@DisplayName("hands itself back when dirty but nothing was ever written")
		void shouldReturnItselfWhenDirtyButNothingWasEverWritten() {
			final HierarchyIndex index = new HierarchyIndex();

			assertStateAfterCommit(
				index,
				original -> original.initRootNodes(EmptyBitmap.INSTANCE),
				(original, committed) -> {
					// the bootstrap raised the dirty flag but allocated nothing, so the merge has no structure to copy
					// and the cheapest correct answer is the instance itself
					assertSame(original, committed, "a commit with nothing to merge must not build a new index");
					assertTrue(committed.isHierarchyIndexEmpty());
					assertNull(nodeStoreOf(committed), "and must not allocate the store it had nothing to merge into");
					assertEquals(emptyIndexBytes(), committed.getHeapSizeInBytes());
				}
			);
		}

		@Test
		@DisplayName("discharges no diff layer for a store it never allocated")
		void shouldDischargeNoLayerForAnIndexThatOwnsNoStore() {
			final HierarchyIndex index = new HierarchyIndex();

			assertStateAfterRollback(
				index,
				original -> assertDoesNotThrow(
					() -> original.removeLayer(Transaction.getTransactionalLayerMaintainer()),
					"discharging the layers of an index that owns no store must not reach into the absent store"
				),
				(original, committed) -> {
					assertNull(committed, "a rolled-back transaction commits nothing");
					assertTrue(original.isHierarchyIndexEmpty());
					assertNull(nodeStoreOf(original), "and the discharge must not have allocated one");
				}
			);
		}
	}

	/**
	 * What the four-argument constructor decides on the way back from disk: the store is rebuilt only when at least
	 * one of the four structures came back with data, so a lone orphan keeps it and an empty part costs nothing.
	 */
	@Nested
	@DisplayName("Reconstruction from persisted state")
	class ReconstructionFromPersistedState {

		@Test
		@DisplayName("drops the store when every structure came back empty")
		void shouldDropTheStoreWhenEveryStructureCameBackEmpty() {
			final HierarchyIndex index = new HierarchyIndex(
				ArrayUtils.EMPTY_INT_ARRAY, new LevelIndex[0], Map.of(), ArrayUtils.EMPTY_INT_ARRAY
			);

			assertNull(nodeStoreOf(index), "a hierarchy that came back empty must not rebuild its scaffolding");
			assertTrue(index.isHierarchyIndexEmpty());
			assertEquals(
				emptyIndexBytes(), index.getHeapSizeInBytes(),
				"a reloaded empty hierarchy must weigh what a fresh one weighs"
			);
		}

		@Test
		@DisplayName("keeps the store for a hierarchy that is nothing but orphans")
		void shouldKeepTheStoreForAHierarchyThatIsNothingButOrphans() {
			final HierarchyIndex index = new HierarchyIndex(
				ArrayUtils.EMPTY_INT_ARRAY, new LevelIndex[0], Map.of(9, new HierarchyNode(9, 8)), new int[]{9}
			);

			// the drop rule has to read all four structures: roots and the level index are empty here, and dropping on
			// those two alone would throw an orphan-only hierarchy's data away on every commit
			assertNotNull(nodeStoreOf(index), "an orphan is data and must keep the store holding it");
			assertFalse(index.isHierarchyIndexEmpty());
			assertEquals(0, index.getHierarchySize(), "an orphan is reachable from no root");
			assertArrayEquals(new int[]{9}, index.getOrphanHierarchyNodes().getArray());
		}

		@Test
		@DisplayName("round trips a populated hierarchy through its storage part")
		void shouldRoundTripAPopulatedHierarchyThroughItsStoragePart() {
			final HierarchyIndex index = seededIndex();
			index.addNode(9, 8);

			final HierarchyIndexStoragePart part = (HierarchyIndexStoragePart) index.createStoragePart(1);
			assertNotNull(part);
			final HierarchyIndex rebuilt = new HierarchyIndex(
				part.getRoots(), part.getLevelIndex(), new HashMap<>(part.getItemIndex()), part.getOrphans()
			);

			assertNotNull(nodeStoreOf(rebuilt), "a hierarchy that came back with nodes must own a store");
			assertArrayEquals(
				index.getRootHierarchyNodes(ALL_NODES).getArray(), rebuilt.getRootHierarchyNodes(ALL_NODES).getArray());
			assertArrayEquals(index.getAllHierarchyNodes().getArray(), rebuilt.getAllHierarchyNodes().getArray());
			assertArrayEquals(
				index.getOrphanHierarchyNodes().getArray(), rebuilt.getOrphanHierarchyNodes().getArray());
			assertEquals(index.toString(), rebuilt.toString(), "the rebuilt tree must have the same shape");
		}

		@Test
		@DisplayName("round trips the empty part back to no store at all")
		void shouldRoundTripTheEmptyPartBackToNoStoreAtAll() {
			final HierarchyIndex index = new HierarchyIndex();
			index.initRootNodes(EmptyBitmap.INSTANCE);
			final HierarchyIndexStoragePart part = (HierarchyIndexStoragePart) index.createStoragePart(1);
			assertNotNull(part);

			final HierarchyIndex rebuilt = new HierarchyIndex(
				part.getRoots(), part.getLevelIndex(), new HashMap<>(part.getItemIndex()), part.getOrphans()
			);

			assertNull(nodeStoreOf(rebuilt), "reloading a hierarchy that persisted nothing must allocate nothing");
			assertEquals(emptyIndexBytes(), rebuilt.getHeapSizeInBytes());
		}
	}

	/**
	 * The answers a hierarchy holding nodes gives.
	 *
	 * The overlap with `HierarchyIndexTest` is deliberate and must not be tidied away: these are the "the answers did
	 * not change once the store became lazy" half of the counterfactual, and without them this file would assert only
	 * absence.
	 */
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

			final EntityIndexManifest manifest = new EntityIndexManifest();
			final TrappedChanges changes = new TrappedChanges();
			index.collectModifiedStorageParts(1, manifest, changes);
			assertTrue(changes.getTrappedChangesCount() > 0, "a dirty hierarchy must emit its part");
			assertTrue(manifest.isHierarchyPresent(), "and must announce itself, so the parent index flips its bit");

			final StoragePart part = index.createStoragePart(1);
			assertInstanceOf(HierarchyIndexStoragePart.class, part);
			final HierarchyIndexStoragePart hierarchyPart = (HierarchyIndexStoragePart) part;
			assertArrayEquals(new int[]{1}, hierarchyPart.getRoots());
			assertEquals(4, hierarchyPart.getItemIndex().size());
		}

		@Test
		@DisplayName("keeps the store when its last node is removed outside a transaction")
		void shouldKeepTheStoreWhenTheLastNodeIsRemovedOutsideATransaction() {
			final HierarchyIndex index = new HierarchyIndex();
			index.addNode(1, null);
			final Object published = nodeStoreOf(index);

			index.removeNode(1);

			// the asymmetry with the commit merge is deliberate: a removal cannot drop a reference a concurrent reader
			// may already hold, and a transaction's diff needs somewhere to live, so only the merge - which builds
			// a fresh index nobody has seen yet - gets to give the store back
			assertSame(published, nodeStoreOf(index), "emptying in place must keep the store it emptied");
			assertTrue(index.isHierarchyIndexEmpty());
			assertTrue(
				index.getHeapSizeInBytes() > emptyIndexBytes(),
				"and the empty structures it still holds are charged honestly"
			);

			index.addNode(2, null);

			assertSame(published, nodeStoreOf(index), "the next node reuses that store rather than allocating another");
			assertArrayEquals(new int[]{2}, index.getRootHierarchyNodes(ALL_NODES).getArray());
		}
	}
}
