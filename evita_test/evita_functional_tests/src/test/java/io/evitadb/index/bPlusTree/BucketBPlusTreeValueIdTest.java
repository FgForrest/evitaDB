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

import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.TransactionHandler;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.invertedIndex.ValueIdAllocator;
import io.evitadb.utils.CollectionUtils;
import com.carrotsearch.hppc.LongArrayList;
import com.carrotsearch.hppc.LongLongHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import static io.evitadb.index.IndexHeapSizeAssertions.readField;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the value-id surface of {@link BucketBPlusTree} at the level it is declared on — the minter lifecycle, the
 * bulk-load column, the reverse-lookup directory and its two rebuild variants.
 *
 * # Why these cannot live beside the `InvertedIndex` cases
 *
 * `InvertedIndex` is the guarded entry point: it refuses an attach on a populated tree, refuses a reverse lookup while
 * a transaction is open, and never hands a caller a persisted id column of the wrong length. Those refusals are exactly
 * what makes the tree's own premises unreachable from there — so the contracts below have no index-level fixture that
 * could provoke them, and the interface invites a future caller to reach the tree directly.
 *
 * The trees here use a deliberately tiny leaf block, so a split, a steal and a merge each happen at a handful of values
 * and a failure names a slot a reader can hold in their head.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@DisplayName("Value ids on the bucket B+ tree itself")
class BucketBPlusTreeValueIdTest {

	/**
	 * Leaf block size of every fixture below — five buckets per leaf, so twenty values span several leaves and draining
	 * them provokes real steals and merges. The tree refuses a minimum that is not strictly below half the block, and
	 * the single-argument constructor derives the minimum as `blockSize / 2`, so an odd block is what makes it legal.
	 */
	private static final int LEAF_BLOCK_SIZE = 5;

	/**
	 * Builds a fresh, empty tree over `Integer` keys in natural order.
	 *
	 * @return the empty tree
	 */
	@Nonnull
	private static TransactionalBucketBPlusTree<Integer> emptyTree() {
		return new TransactionalBucketBPlusTree<>(LEAF_BLOCK_SIZE, Integer.class);
	}

	/**
	 * Builds a tree already carrying value ids, holding one record per value for `0..valueCount-1` inserted in
	 * ascending order — so the id of value `v` is `FIRST_VALUE_ID + v`.
	 *
	 * @param valueCount how many distinct values to insert
	 * @return the populated, id-carrying tree
	 */
	@Nonnull
	private static TransactionalBucketBPlusTree<Integer> treeWithIds(int valueCount) {
		final TransactionalBucketBPlusTree<Integer> tree = emptyTree();
		tree.installValueIdMinter(new ValueIdAllocator()::allocate);
		for (int value = 0; value < valueCount; value++) {
			tree.addRecord(value, value + 1);
		}
		return tree;
	}

	/**
	 * Runs `insideTransaction` with a real transaction bound to the calling thread, rolling it back afterwards. The
	 * tests that use it assert on what a reader sees WHILE the transaction is still open — never on what it leaves
	 * behind — so the transaction exists only to make the writes performed inside it transaction-local.
	 *
	 * @param insideTransaction the body to run with a transaction on the thread
	 */
	private static void executeInsideTransaction(@Nonnull Runnable insideTransaction) {
		Transaction.executeInTransactionIfProvided(
			new Transaction(
				UUID.randomUUID(),
				new TransactionHandler() {
					@Override
					public void registerMutation(@Nonnull Mutation mutation) {
						// no mutation recording is needed for a structure-level test
					}

					@Override
					public void commit(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
						// unused - the transaction is always rolled back
					}

					@Override
					public void rollback(
						@Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Throwable cause
					) {
						// the writes made inside are discarded; only what was observed inside matters
					}
				},
				false
			),
			() -> {
				final Transaction transaction = Transaction.getTransaction().orElseThrow();
				try {
					insideTransaction.run();
				} finally {
					// closed in a finally so a failing assertion cannot leave a transaction bound to the thread and
					// poison every test that runs after it in the same fork
					transaction.setRollbackOnly();
					transaction.close();
				}
			}
		);
	}

	@Nested
	@DisplayName("Switching the id column on")
	class MinterInstallation {

		@Test
		@DisplayName("a populated tree refuses to be back-filled from inside a transaction")
		void shouldRefuseBackFillingPopulatedTreeInsideTransaction() {
			final TransactionalBucketBPlusTree<Integer> tree = emptyTree();
			for (int value = 0; value < 10; value++) {
				tree.addRecord(value, value + 1);
			}

			// the back-fill walks the BASE leaves, so ids stamped here would be visible to readers of the previous
			// version and would vanish again if the transaction rolled back
			executeInsideTransaction(
				() -> assertThrows(
					GenericEvitaInternalError.class,
					() -> tree.installValueIdMinter(new ValueIdAllocator()::allocate)
				)
			);
		}

		@Test
		@DisplayName("an empty tree may still be switched on from inside a transaction")
		void shouldAllowInstallingMinterOnEmptyTreeInsideTransaction() {
			// the counterfactual for the refusal above: the guard is conditional on the tree holding a bucket, and an
			// empty tree has nothing for the walk to write. Without this case the refusal test would keep passing even
			// if the guard became unconditional - which is a real behaviour change, since it is the only shape a
			// consumer registering on a live catalog can legitimately arrive in
			final TransactionalBucketBPlusTree<Integer> tree = emptyTree();

			executeInsideTransaction(() -> {
				tree.installValueIdMinter(new ValueIdAllocator()::allocate);

				assertTrue(tree.carriesValueIds());
				tree.addRecord(10, 1);
				assertEquals(ValueIdAllocator.FIRST_VALUE_ID, tree.valueIdOf(10));
			});
		}

		@Test
		@DisplayName("a persisted id column that does not match the tree is refused before anything is stamped")
		void shouldRefusePersistedValueIdColumnThatDoesNotMatchTheTree() {
			final int valueCount = 10;
			final TransactionalBucketBPlusTree<Integer> tree = emptyTree();
			for (int value = 0; value < valueCount; value++) {
				tree.addRecord(value, value + 1);
			}

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> tree.installValueIdMinter(new ValueIdAllocator()::allocate, new int[valueCount - 1])
			);

			// both counts, because "the column is misaligned" is unactionable without knowing which side is short
			assertTrue(
				error.getMessage().contains(String.valueOf(valueCount - 1))
					&& error.getMessage().contains(String.valueOf(valueCount)),
				"the refusal must name both counts, but was: " + error.getMessage()
			);
			// the check deliberately runs BEFORE the walk, so no bucket was stamped - but the minter field is assigned
			// on the way in, so the tree reports itself id-carrying while none of its leaves holds a column. The only
			// caller is the load path, which discards the whole tree when a load throws
			assertTrue(tree.carriesValueIds(), "the minter is installed before the column is checked");
			assertEquals(
				ValueIdAllocator.UNASSIGNED_VALUE_ID, tree.valueIdOf(0),
				"no bucket may be stamped by a walk the guard stopped from running"
			);
		}

		@Test
		@DisplayName("re-pointing the minter over an id-carrying tree renumbers nothing")
		void shouldNotRenumberWhenMinterIsReInstalled() {
			final int valueCount = 10;
			final TransactionalBucketBPlusTree<Integer> tree = treeWithIds(valueCount);
			final int[] idsBefore = new int[valueCount];
			for (int value = 0; value < valueCount; value++) {
				idsBefore[value] = tree.valueIdOf(value);
			}

			// what a commit merge does: the surviving tree is re-pointed at the surviving allocator, and every bucket
			// it carried forward must keep the id it already has
			tree.installValueIdMinter(new ValueIdAllocator(1_000)::allocate);

			for (int value = 0; value < valueCount; value++) {
				assertEquals(idsBefore[value], tree.valueIdOf(value), "value " + value + " was renumbered");
			}
			// and the re-point did take effect, so the assertion above is about a tree that really changed minter
			tree.addRecord(valueCount, valueCount + 1);
			assertEquals(1_000, tree.valueIdOf(valueCount));
		}
	}

	@Nested
	@DisplayName("Switching the id column off")
	class MinterRemoval {

		@Test
		@DisplayName("dropping the id columns of a populated tree from inside a transaction is refused")
		void shouldRefuseDroppingValueIdColumnsInsideTransaction() {
			final TransactionalBucketBPlusTree<Integer> tree = treeWithIds(10);

			// the walk clears the column on the BASE leaves, so a transaction that later rolled back would leave the
			// tree stripped of ids it never agreed to give up
			executeInsideTransaction(
				() -> assertThrows(GenericEvitaInternalError.class, tree::removeValueIdMinter)
			);
		}

		@Test
		@DisplayName("an empty tree may still be switched off from inside a transaction")
		void shouldAllowDroppingValueIdColumnsOfEmptyTreeInsideTransaction() {
			// the counterfactual for the refusal above, and the mirror image of
			// `shouldAllowInstallingMinterOnEmptyTreeInsideTransaction`: both guards protect the same walk, so both
			// must be conditional on the tree actually holding a bucket. An empty tree has no id to clear and
			// therefore nothing a transaction could leak - and a schema mutation always arrives with a transaction
			// bound to the thread, so an unconditional guard here would make the whole drop path unreachable from the
			// only caller that would ever use it
			final TransactionalBucketBPlusTree<Integer> tree = emptyTree();
			tree.installValueIdMinter(new ValueIdAllocator()::allocate);

			executeInsideTransaction(() -> {
				tree.removeValueIdMinter();

				assertFalse(tree.carriesValueIds());
			});
		}

		@Test
		@DisplayName("dropping ids from a tree that carries none is a silent no-op, transaction or not")
		void shouldIgnoreRemovalWhenTreeCarriesNoValueIds() {
			final TransactionalBucketBPlusTree<Integer> tree = emptyTree();
			tree.addRecord(10, 1);

			tree.removeValueIdMinter();
			assertFalse(tree.carriesValueIds());

			// the early return sits ABOVE the transaction premise, so a tree with nothing to drop is never refused
			executeInsideTransaction(() -> {
				tree.removeValueIdMinter();
				assertFalse(tree.carriesValueIds());
			});
		}
	}

	@Nested
	@DisplayName("Restoring a persisted page")
	class BulkLoad {

		@Test
		@DisplayName("a bulk-loaded page whose id column is shorter than the page is refused")
		void shouldRefuseBulkLoadedPageWhoseValueIdColumnIsShorterThanThePage() {
			final TransactionalBucketBPlusTree<Integer> tree = emptyTree();

			assertThrows(
				GenericEvitaInternalError.class,
				() -> tree.bulkLoadPage(
					new Object[]{10, 20, 30}, new long[]{1, 2, 3}, null, new int[]{11, 22}, 3
				)
			);
		}

		@Test
		@DisplayName("a page bulk-loaded without an id column carries none")
		void shouldBulkLoadPageWithoutValueIdColumn() {
			final TransactionalBucketBPlusTree<Integer> tree = emptyTree();

			tree.bulkLoadPage(new Object[]{10, 20, 30}, new long[]{1, 2, 3}, null, null, 3);

			assertFalse(tree.carriesValueIds());
			assertEquals(ValueIdAllocator.UNASSIGNED_VALUE_ID, tree.valueIdOf(20));
			// read through the cursor as well: the tree-level lookup short-circuits on the absent minter, so only the
			// cursor actually reports what the loaded leaf holds
			final BucketCursor<Integer> cursor = tree.cursor();
			int buckets = 0;
			while (cursor.next()) {
				assertEquals(
					ValueIdAllocator.UNASSIGNED_VALUE_ID, cursor.valueId(),
					"a page loaded without an id column must hold no id in any slot"
				);
				buckets++;
			}
			assertEquals(3, buckets, "every bulk-loaded bucket must be reachable");
		}

		@Test
		@DisplayName("a page loaded into an id-carrying tree gets a column sized to the page")
		void shouldSizeTheValueIdColumnToThePageWhenTheTreeCarriesIdsButThePageDoesNot() {
			// the one state that used to attach a never-sized id column to an already-populated leaf: the tree mints
			// ids, but the page was persisted before ids existed, so `valueIds` arrives null
			final TransactionalBucketBPlusTree<Integer> tree = emptyTree();
			tree.installValueIdMinter(new ValueIdAllocator()::allocate);
			tree.bulkLoadPage(new Object[]{10, 20, 30}, new long[]{1, 2, 3}, null, null, 3);

			assertColumnsAlignedWithLeaves(tree);
			final RecordColumn valueIds = tree.enumerateLeaves().get(0).getValueIds();
			assertNotNull(valueIds, "an id-carrying tree must give every leaf a column");
			assertEquals(3, valueIds.size(), "the column must cover the page it was attached to");
			for (int slot = 0; slot < 3; slot++) {
				assertEquals(
					ValueIdAllocator.UNASSIGNED_VALUE_ID, valueIds.intAt(slot),
					"an unstamped slot reads as the unassigned sentinel"
				);
			}
		}

		@Test
		@DisplayName("a page loaded with persisted ids sizes its column exactly to the page")
		void shouldSizeTheValueIdColumnExactlyWhenThePageCarriesPersistedIds() {
			final TransactionalBucketBPlusTree<Integer> tree = emptyTree();
			tree.installValueIdMinter(new ValueIdAllocator()::allocate);
			tree.bulkLoadPage(
				new Object[]{10, 20, 30}, new long[]{1, 2, 3}, null, new int[]{11, 22, 33}, 3
			);

			assertColumnsAlignedWithLeaves(tree);
			final RecordColumn valueIds = tree.enumerateLeaves().get(0).getValueIds();
			assertNotNull(valueIds);
			assertEquals(3, valueIds.size());
			assertArrayEquals(new int[]{11, 22, 33}, new int[]{
				valueIds.intAt(0), valueIds.intAt(1), valueIds.intAt(2)
			});
			// exactly the page, with no geometric overshoot: the id column must not cost more than the record column
			// it runs beside, which was bulk-loaded from the same page
			assertEquals(
				tree.enumerateLeaves().get(0).getRecords().getHeapSizeInBytes(), valueIds.getHeapSizeInBytes(),
				"the id column must be sized exactly to the page, like every other bulk-loaded column"
			);
		}
	}

	/**
	 * Asserts the leaf-column alignment invariant across every leaf of a tree: each column's live run covers exactly
	 * the buckets the leaf holds, no more and no less.
	 *
	 * @param tree the tree to check
	 */
	private static void assertColumnsAlignedWithLeaves(@Nonnull TransactionalBucketBPlusTree<Integer> tree) {
		for (final TransactionalBucketBPlusTree.BPlusLeafTreeNode<Integer> leaf : tree.enumerateLeaves()) {
			final int expected = leaf.getPeek() + 1;
			assertEquals(expected, leaf.getKeyColumn().size(), "key column misaligned");
			assertEquals(expected, leaf.getRecords().size(), "record column misaligned");
			if (leaf.getValueIds() != null) {
				assertEquals(expected, leaf.getValueIds().size(), "value id column misaligned");
			}
			if (leaf.getOverflow() != null) {
				assertEquals(expected, leaf.getOverflow().size(), "overflow column misaligned");
			}
		}
	}

	@Nested
	@DisplayName("Keeping the id column in lockstep with the leaf")
	class ColumnLockstep {

		@Test
		@DisplayName("insert, delete, split, steal and merge all leave the id column aligned")
		void shouldKeepTheIdColumnAlignedThroughEveryStructuralChange() {
			final TransactionalBucketBPlusTree<Integer> tree = treeWithIds(20);
			assertColumnsAlignedWithLeaves(tree);

			// promote a scattering of buckets so the overflow column joins the lockstep too
			for (int value = 0; value < 20; value += 3) {
				tree.addRecord(value, 1_000 + value);
			}
			assertColumnsAlignedWithLeaves(tree);

			// draining every other value forces steals and merges across the whole spine
			for (int value = 0; value < 20; value += 2) {
				tree.removeRecord(value, value + 1, 1_000 + value);
			}
			assertColumnsAlignedWithLeaves(tree);

			// and the surviving ids are still the ones minted for their values
			for (int value = 1; value < 20; value += 2) {
				assertEquals(
					ValueIdAllocator.FIRST_VALUE_ID + value, tree.valueIdOf(value),
					"the id of value " + value + " must survive every rebalance"
				);
			}
		}

		@Test
		@DisplayName("a vacated slot reads back as unassigned")
		void shouldReadBackZeroFromAVacatedSlot() {
			final TransactionalBucketBPlusTree<Integer> tree = treeWithIds(4);
			final RecordColumn valueIds = tree.enumerateLeaves().get(0).getValueIds();
			assertNotNull(valueIds);
			assertEquals(4, valueIds.size());

			tree.removeRecord(3, 4);
			assertEquals(3, valueIds.size(), "the column shrinks with the leaf");
			assertEquals(
				ValueIdAllocator.UNASSIGNED_VALUE_ID, valueIds.intAt(3),
				"the slot the deleted bucket vacated must read as unassigned, not as a stale id"
			);
		}

		@Test
		@DisplayName("the back-filled column is sized exactly to the leaf, not to the next power of two")
		void shouldSizeTheBackFilledColumnExactlyToTheLeaf() {
			// 100 buckets in a 255-bucket leaf: geometric growth would land the id column on 128 slots, and the 4:1
			// trim threshold never gives those 28 back - the leaf would carry them for the rest of its life. The
			// record column beside it was grown one insert at a time and IS on 128, which is what makes the
			// comparison below a real assertion rather than a tautology
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(255, Integer.class);
			for (int value = 0; value < 100; value++) {
				tree.addRecord(value, value + 1);
			}
			assertEquals(1, tree.enumerateLeaves().size(), "the fixture must stay a single leaf");

			tree.installValueIdMinter(new ValueIdAllocator()::allocate);
			final RecordColumn valueIds = tree.enumerateLeaves().get(0).getValueIds();
			assertNotNull(valueIds);
			assertEquals(100, valueIds.size());

			// physical length is not exposed, so it is read back through the arithmetic: an int column costs its
			// object plus a four-byte slot per allocated element, and the two candidate lengths are far enough apart
			// that the comparison cannot be confused by alignment padding
			final long exact = valueIds.getHeapSizeInBytes();
			final RecordColumn hundredSlotReference = RecordColumnFactory.INT.create(255);
			hundredSlotReference.bulkLoad(new long[100], 100);
			assertEquals(
				hundredSlotReference.getHeapSizeInBytes(), exact,
				"the back-filled id column must be sized exactly to the leaf's 100 buckets"
			);
			assertTrue(
				exact < tree.enumerateLeaves().get(0).getRecords().getHeapSizeInBytes(),
				"the geometrically grown record column beside it must be the larger of the two"
			);
		}

		@Test
		@DisplayName("the back-fill sizes a loaded tree's columns to its live count")
		void shouldSizeTheBackFilledColumnToTheLiveCountOfEachLeaf() {
			// a tree loaded WITHOUT ids and switched on afterwards: every leaf gets its column after the fact, and it
			// has to arrive covering the buckets already there - the back-fill reads every live slot straight back
			final TransactionalBucketBPlusTree<Integer> tree = emptyTree();
			for (int value = 0; value < 12; value++) {
				tree.addRecord(value, value + 1);
			}
			assertFalse(tree.carriesValueIds());

			tree.installValueIdMinter(new ValueIdAllocator()::allocate);
			assertColumnsAlignedWithLeaves(tree);
			for (int value = 0; value < 12; value++) {
				assertNotEquals(
					ValueIdAllocator.UNASSIGNED_VALUE_ID, tree.valueIdOf(value),
					"every value of a back-filled tree must carry a minted id"
				);
			}
		}
	}

	@Nested
	@DisplayName("Resolving an id back to its value")
	class ReverseLookup {

		@Test
		@DisplayName("an id minted inside a transaction has no entry in the committed directory")
		void shouldNotResolveIdMintedInsideTransactionThroughCommittedDirectory() {
			// characterizes, one layer below `InvertedIndex#getValueById`'s refusal, exactly what that refusal spares a
			// caller from. The directory is built per published version and carries no diff layer - which is what buys
			// MVCC here without one - so the forward and reverse lookups disagree inside a transaction: the forward one
			// descends the transaction-aware tree and finds the id, the reverse one consults a directory the
			// transaction never touched and finds nothing. Whoever makes the reverse lookup transaction-aware needs
			// this pinned rather than assumed
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(10, 1);
			tree.addRecord(20, 2);
			tree.installValueIdMinter(new ValueIdAllocator()::allocate);
			tree.rebuildValueIdDirectory();
			final int idOfTwenty = tree.valueIdOf(20);
			assertEquals(20, tree.valueOf(idOfTwenty), "the fixture must resolve before the transaction opens");

			executeInsideTransaction(() -> {
				tree.addRecord(30, 3);
				final int mintedInside = tree.valueIdOf(30);
				assertNotEquals(
					ValueIdAllocator.UNASSIGNED_VALUE_ID, mintedInside,
					"the forward lookup must see the id the open transaction minted, or the assertion below is made "
						+ "against a tree that minted nothing"
				);

				assertNull(
					tree.valueOf(mintedInside),
					"the directory was built for the last published version, so an id minted inside the transaction "
						+ "cannot be resolved through it - a false negative, which is the whole reason the "
						+ "index-level reverse lookup refuses to answer here"
				);
			});

			assertEquals(20, tree.valueOf(idOfTwenty), "the committed answer must be unaffected by the rollback");
		}

		@Test
		@DisplayName("an id whose whole leaf was merged away resolves to nothing")
		void shouldResolveNothingWhenTheLeafItselfDisappeared() {
			final int valueCount = 20;
			final int survivors = 3;
			final TransactionalBucketBPlusTree<Integer> tree = treeWithIds(valueCount);
			tree.rebuildValueIdDirectory();
			final Map<Integer, Integer> idByValue = CollectionUtils.createHashMap(valueCount);
			for (int value = 0; value < valueCount; value++) {
				idByValue.put(value, tree.valueIdOf(value));
				assertEquals(
					value, tree.valueOf(idByValue.get(value)), "the fixture must resolve before it is drained"
				);
			}

			// drain seven leaves down to one: the directory keeps its location entries, but the rebuilt leaf map no
			// longer holds most of the leaf ids those entries address - so the lookup has to give up before it can read
			// a slot, rather than reading whatever now sits in the slot a dead value used to occupy
			for (int value = survivors; value < valueCount; value++) {
				tree.removeRecord(value, value + 1);
			}
			tree.rebuildValueIdDirectory();

			for (int value = survivors; value < valueCount; value++) {
				assertNull(
					tree.valueOf(idByValue.get(value)),
					"the id of dead value " + value + " must name nothing at all"
				);
			}
			// and the drained tree still answers for what survived, so the assertions above cannot pass by a directory
			// that simply resolves nothing any more
			for (int value = 0; value < survivors; value++) {
				assertEquals(
					value, tree.valueOf(idByValue.get(value)), "surviving value " + value + " stopped resolving"
				);
			}
		}

		@Test
		@DisplayName("reading a value id off a cursor that was never advanced is refused")
		void shouldRefuseReadingValueIdFromUnpositionedCursor() {
			final TransactionalBucketBPlusTree<Integer> tree = treeWithIds(10);

			assertThrows(GenericEvitaInternalError.class, () -> tree.cursor().valueId());
		}
	}

	@Nested
	@DisplayName("Rebuilding the directory")
	class DirectoryRebuild {

		@Test
		@DisplayName("the after-merge rebuild trusts a leaf's instance token and therefore skips an in-place change")
		void shouldRestampOnlyTheLeavesAMergeRebuilt() {
			// A leaf's token is per-instance, so it moves when a commit merge rebuilds the leaf but NOT when the leaf
			// is mutated in place outside a transaction. The after-merge rebuild keys its skip off that token, so it is
			// correct only where every content change came with a fresh instance. Naming the difference in a test is
			// what stops a later caller from reaching for the cheaper variant on the warm-up path
			final TransactionalBucketBPlusTree<Integer> tree = treeWithIds(3);
			tree.rebuildValueIdDirectory();
			final int idOfTwo = tree.valueIdOf(2);
			assertEquals(2, tree.valueOf(idOfTwo), "the fixture must resolve before the leaf is mutated");

			// removing the first bucket slides both survivors one slot left while keeping the very same leaf instance,
			// so every directory entry for this leaf now addresses the slot of its right-hand neighbour
			tree.removeRecord(0, 1);

			tree.rebuildValueIdDirectoryAfterMerge();
			assertNull(
				tree.valueOf(idOfTwo),
				"an unchanged instance token reads as an unchanged leaf, so the after-merge rebuild leaves the stale "
					+ "entry in place and the id resolves to nothing"
			);

			tree.rebuildValueIdDirectory();
			assertEquals(
				2, tree.valueOf(idOfTwo),
				"the unconditional rebuild is the one that picks an in-place mutation up"
			);
		}

		@Test
		@DisplayName("a leaf the previous directory never held is restamped rather than skipped")
		void shouldRestampALeafThePreviousDirectoryNeverHeld() {
			// The reuse check asks `indexOf`/`indexExists` rather than `get` with a sentinel, precisely so that a leaf
			// ABSENT from the previous table is told apart from one whose recorded token happens to be zero. Only the
			// present-and-equal arm was covered; this reaches the absent one, which is what a split between two
			// rebuilds produces - the new leaf has no previous entry at all and its entries must be stamped.
			final TransactionalBucketBPlusTree<Integer> tree = treeWithIds(LEAF_BLOCK_SIZE - 1);
			tree.rebuildValueIdDirectory();
			final int leavesBefore = directoryLeafCount(tree);
			assertEquals(1, leavesBefore, "the fixture must start as a single leaf, or nothing splits");

			// the value that fills the block splits the leaf, so the tree gains a leaf the previous directory has
			// never seen while the original instance is carried forward unchanged
			tree.addRecord(LEAF_BLOCK_SIZE - 1, LEAF_BLOCK_SIZE);
			final int newValueId = tree.valueIdOf(LEAF_BLOCK_SIZE - 1);
			assertTrue(newValueId > 0, "the new value must have been minted an id");
			assertNull(
				tree.valueOf(newValueId),
				"the published directory predates the split, so the new id resolves to nothing until it is rebuilt"
			);

			tree.rebuildValueIdDirectoryAfterMerge();

			assertTrue(
				directoryLeafCount(tree) > leavesBefore,
				"the split must have produced a leaf the previous directory never held, or the absent arm of the "
					+ "reuse check is not the one being exercised"
			);
			assertEquals(
				LEAF_BLOCK_SIZE - 1, tree.valueOf(newValueId),
				"a leaf with no previous entry cannot be skipped as unchanged - it has to be stamped"
			);
		}

		@Test
		@DisplayName("the shared empty leaf-version table a first rebuild probes is never written into")
		void shouldNeverWriteIntoTheSharedEmptyLeafVersionTable() {
			// A tree with no previous directory probes a SHARED static table, whose javadoc says it is never written.
			// Nothing enforced that, and a later edit that put into the probe map instead of reading it would corrupt
			// every other tree's first rebuild - a cross-tree failure with no local symptom at all.
			final TransactionalBucketBPlusTree<Integer> first = treeWithIds(LEAF_BLOCK_SIZE + 2);
			first.rebuildValueIdDirectory();
			assertTrue(sharedEmptyLeafVersions().isEmpty(), "the unconditional first rebuild wrote into it");

			final TransactionalBucketBPlusTree<Integer> second = treeWithIds(LEAF_BLOCK_SIZE + 2);
			second.rebuildValueIdDirectoryAfterMerge();
			assertTrue(sharedEmptyLeafVersions().isEmpty(), "the incremental first rebuild wrote into it");

			// and a tree whose first rebuild came second still resolves everything, which it could not if the shared
			// table had picked up another tree's leaf ids
			final TransactionalBucketBPlusTree<Integer> third = treeWithIds(LEAF_BLOCK_SIZE + 2);
			third.rebuildValueIdDirectoryAfterMerge();
			for (int value = 0; value < LEAF_BLOCK_SIZE + 2; value++) {
				assertEquals(value, third.valueOf(third.valueIdOf(value)));
			}
		}

		/**
		 * @param tree the tree whose published directory to inspect
		 * @return how many leaves the published directory's version table holds
		 */
		private int directoryLeafCount(@Nonnull TransactionalBucketBPlusTree<Integer> tree) {
			final Object directory = Objects.requireNonNull(
				readField(tree, "valueIdDirectory"), "the directory has not been built yet"
			);
			return ((LongLongHashMap) Objects.requireNonNull(
				readField(directory, "directoryVersionByLeafId")
			)).size();
		}

		/**
		 * @return the shared empty leaf-version table every first rebuild probes against
		 */
		@Nonnull
		private LongLongHashMap sharedEmptyLeafVersions() {
			try {
				final Field field = TransactionalBucketBPlusTree.class.getDeclaredField("EMPTY_LEAF_VERSIONS");
				field.setAccessible(true);
				return (LongLongHashMap) Objects.requireNonNull(field.get(null));
			} catch (ReflectiveOperationException ex) {
				throw new GenericEvitaInternalError(
					"the shared empty leaf-version table is gone, so this guard no longer guards anything.", ex
				);
			}
		}
	}

	@Nested
	@DisplayName("Settling a candidate off the slot its id resolves to")
	class CandidateResolution {

		@Test
		@DisplayName("with no predicate and no pattern the bucket comes back and its leaf reaches the sink")
		void shouldAnswerWithoutReadingTheKeyAtAll() {
			// A null predicate is the caller stating that every id it hands over is already known to match, so the key
			// is never decoded. What is observable from here is the rest of the contract: the records come back and
			// the leaf the answer depends on is reported, because the answer would go stale with that leaf.
			final TransactionalBucketBPlusTree<Integer> tree = treeWithIds(LEAF_BLOCK_SIZE + 2);
			tree.rebuildValueIdDirectory();
			final LongArrayList reportedLeaves = new LongArrayList();

			final Bitmap records = tree.recordsOfMatchingValueId(
				tree.valueIdOf(3), null, null, reportedLeaves::add
			);

			assertNotNull(records, "the id names a live bucket, so it must answer");
			assertArrayEquals(new int[]{4}, records.getArray());
			assertEquals(1, reportedLeaves.size(), "exactly the one leaf the answer was read from must be reported");
		}

		@Test
		@DisplayName("a byte pattern settles the candidate itself, so the predicate beside it is never consulted")
		void shouldSettleTheCandidateFromTheStoredBytes() {
			// The byte form REPLACES the predicate wherever the key column can match bytes - it does not pre-filter
			// for it - and a `String`-keyed tree is front-coded, so this is where that applies. Counting the predicate
			// is what tells the two apart: parity alone would look the same with the byte path deleted.
			final TransactionalBucketBPlusTree<String> tree = stringTreeWithIds(
				"alpha item", "beta widget", "gamma item"
			);
			final int[] invocations = new int[1];
			final Predicate<String> counting = value -> {
				invocations[0]++;
				return value.contains("item");
			};
			final LongArrayList reportedLeaves = new LongArrayList();

			final Bitmap matched = tree.recordsOfMatchingValueId(
				tree.valueIdOf("gamma item"), counting, "item".getBytes(StandardCharsets.UTF_8),
				reportedLeaves::add
			);
			assertNotNull(matched, "the value contains the pattern, so the bucket must come back");
			assertArrayEquals(new int[]{3}, matched.getArray());
			assertEquals(1, reportedLeaves.size(), "a match must report the leaf it was read from");
			assertEquals(0, invocations[0], "the stored bytes must have answered, not the predicate");

			final Bitmap rejected = tree.recordsOfMatchingValueId(
				tree.valueIdOf("beta widget"), counting, "item".getBytes(StandardCharsets.UTF_8),
				reportedLeaves::add
			);
			assertNull(rejected, "the value does not contain the pattern, so nothing comes back");
			assertEquals(
				1, reportedLeaves.size(),
				"a rejected candidate contributes no record set, so its leaf must NOT widen the staleness set"
			);
			assertEquals(0, invocations[0], "and the rejection must have come from the bytes as well");
		}

		@Test
		@DisplayName("a key column that cannot match bytes falls back to the predicate rather than refusing")
		void shouldFallBackToThePredicateWhereBytesCannotBeMatched() {
			// `Integer` keys are stored in a primitive column, which reports it cannot match bytes - so the pattern is
			// ignored and the predicate answers. Nothing reaches this arm through the trigram path, because every
			// `String` key is given a front-coded column; it is why the predicate stays required beside the pattern.
			final TransactionalBucketBPlusTree<Integer> tree = treeWithIds(LEAF_BLOCK_SIZE + 2);
			tree.rebuildValueIdDirectory();
			final int[] invocations = new int[1];
			final Predicate<Integer> counting = value -> {
				invocations[0]++;
				return value == 3;
			};
			final LongArrayList reportedLeaves = new LongArrayList();

			final Bitmap matched = tree.recordsOfMatchingValueId(
				tree.valueIdOf(3), counting, "3".getBytes(StandardCharsets.UTF_8), reportedLeaves::add
			);

			assertNotNull(matched, "the predicate accepts the value, so the bucket must come back");
			assertArrayEquals(new int[]{4}, matched.getArray());
			assertEquals(1, invocations[0], "the predicate must have been consulted exactly once, per candidate");
			assertEquals(1, reportedLeaves.size());
		}

		@Test
		@DisplayName("an id naming nothing live answers nothing and reports no leaf")
		void shouldAnswerNothingForAnIdNamingNothingLive() {
			// The trigram postings are keyed by value id and a value can die between the posting being read and this
			// verification running, so an id that resolves to nothing is an ordinary race rather than a divergence -
			// and it must not widen the staleness set on the way past.
			final TransactionalBucketBPlusTree<Integer> tree = treeWithIds(LEAF_BLOCK_SIZE + 2);
			tree.rebuildValueIdDirectory();
			final int deadId = tree.valueIdOf(3);
			tree.removeRecord(3, 4);
			final LongArrayList reportedLeaves = new LongArrayList();

			assertNull(
				tree.recordsOfMatchingValueId(deadId, null, null, reportedLeaves::add),
				"the slot no longer carries that id, so the stale entry must not be believed"
			);
			assertNull(
				tree.recordsOfMatchingValueId(Integer.MAX_VALUE, null, null, reportedLeaves::add),
				"an id the directory has no entry for at all resolves to nothing"
			);
			assertTrue(reportedLeaves.isEmpty(), "nothing matched, so no leaf may enter the staleness set");
		}

		/**
		 * Builds a `String`-keyed tree already carrying value ids, one record per value in the order given. A `String`
		 * key column is the front-coded one, which is the only implementation able to match bytes.
		 *
		 * @param values the values to insert, one record each
		 * @return the populated, id-carrying tree with its directory built
		 */
		@Nonnull
		@SuppressWarnings("unchecked")
		private TransactionalBucketBPlusTree<String> stringTreeWithIds(@Nonnull String... values) {
			// built the way `InvertedIndex` builds it, with the column the key type actually selects: the plain
			// test-facing constructor installs the boxed column instead, and a boxed column cannot match bytes - so
			// on one of those this test would take the predicate fallback and quietly assert nothing about the bytes
			final ValueColumnFactory<String> frontCoded =
				(ValueColumnFactory<String>) ValueColumnFactory.forKey(String.class, null);
			final TransactionalBucketBPlusTree<String> tree = new TransactionalBucketBPlusTree<>(
				LEAF_BLOCK_SIZE, LEAF_BLOCK_SIZE / 2, LEAF_BLOCK_SIZE, LEAF_BLOCK_SIZE / 2,
				String.class, null, frontCoded
			);
			tree.installValueIdMinter(new ValueIdAllocator()::allocate);
			for (int i = 0; i < values.length; i++) {
				tree.addRecord(values[i], i + 1);
			}
			tree.rebuildValueIdDirectory();
			return tree;
		}
	}
}
