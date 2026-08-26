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
import io.evitadb.index.invertedIndex.ValueIdAllocator;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
	}
}
