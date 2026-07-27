/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.TransactionHandler;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyReport;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the transactional path-copying (copy-on-write) STM behaviour of {@link UnorderedLookupTree}. The tree is
 * driven through an {@link OrderKeyConsumer} that records `recordId → orderKey` into a plain (non-transactional)
 * `int → long` map - the STM under test belongs to the **tree**, not to the consumer, so assertions are made via
 * {@link UnorderedLookupTree#getArray()}, {@link UnorderedLookupTree#size()} and
 * {@link UnorderedLookupTree#getRecordAt(int)}.
 *
 * Modelled on the transactional-memory tests in `TransactionalIntToLongBPlusTreeTest`: the {@link io.evitadb.utils.AssertionUtils#assertStateAfterCommit} harness opens a
 * real {@link Transaction}, executes the mutation inside it, commits the transactional memory layer (calling
 * `verifyLayerWasFullySwept()` internally) and hands back both the still-committed `original` view (a fresh, no-layer
 * read - the "other thread") and the merged `committed` copy.
 *
 * The stand-in value index is built **together** with the committed (warm-up) tree, so it already holds the correct
 * committed order-keys before the transaction opens; the consumer keeps it coherent through any re-stamps that happen
 * inside the transaction. This mirrors how the production composite holds the `recordId → orderKey` mapping alongside
 * the tree.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
@DisplayName("UnorderedLookupTree transactional memory")
class UnorderedLookupTreeStmTest {

	/**
	 * Bundles the position tree with a stand-in value index (`recordId → orderKey`), exactly the role the real value
	 * index plays in production. Implements {@link OrderKeyConsumer} to keep the index coherent across order-key
	 * re-stamps. The index map is intentionally **non-transactional** - only the tree's STM is under test.
	 */
	private static final class TreeWithIndex implements OrderKeyConsumer {
		@Nonnull final UnorderedLookupTree tree;
		@Nonnull final IntLongHashMap valueIndex = new IntLongHashMap();

		TreeWithIndex(@Nonnull UnorderedLookupTree tree) {
			this.tree = tree;
		}

		@Override
		public void accept(int recordId, long orderKey) {
			this.valueIndex.put(recordId, orderKey);
		}

		void addAtPosition(int index, int recordId) {
			this.tree.insertAtPosition(index, recordId, this);
		}

		void addAtHead(int recordId) {
			this.tree.insertAtPosition(0, recordId, this);
		}

		void remove(int recordId) {
			this.tree.removeByOrderKey(this.valueIndex.get(recordId), recordId, this);
			this.valueIndex.remove(recordId);
		}

		void markHead(int recordId) {
			this.tree.markHead(this.valueIndex.get(recordId), recordId);
		}

		void unmarkHead(int recordId) {
			this.tree.unmarkHead(this.valueIndex.get(recordId), recordId);
		}
	}

	/**
	 * Builds a freshly committed tree (outside any transaction) holding the records `1000..1000+count` in logical
	 * order, capturing their order-keys into the returned driver's value index.
	 *
	 * @param count number of records to insert
	 * @return a driver wrapping the committed (warm-up) tree and its coherent value index
	 */
	@Nonnull
	private static TreeWithIndex committedTreeOfSize(int count) {
		final TreeWithIndex warmUp = new TreeWithIndex(new UnorderedLookupTree());
		for (int i = 0; i < count; i++) {
			warmUp.addAtPosition(i, 1000 + i);
		}
		return warmUp;
	}

	/**
	 * Builds the expected logical array `1000..1000+count`.
	 */
	@Nonnull
	private static int[] expectedArrayOfSize(int count) {
		final int[] expected = new int[count];
		for (int i = 0; i < count; i++) {
			expected[i] = 1000 + i;
		}
		return expected;
	}

	/**
	 * Asserts the structural consistency report of the passed tree view is CONSISTENT, surfacing the report message on
	 * failure. Run against both the still-committed `original` view and the merged `committed` copy so that the
	 * path-copy / commit merge is verified to preserve balance, augmentation and order-key invariants — not just the
	 * flattened contents.
	 */
	private static void assertConsistent(@Nonnull UnorderedLookupTree tree) {
		final ConsistencyReport report = tree.getConsistencyReport();
		assertEquals(
			ConsistencyState.CONSISTENT, report.state(),
			"Tree reported structural inconsistency:\n" + report.report()
		);
	}

	@Nested
	@DisplayName("Commit")
	class CommitTest {

		@Test
		@DisplayName("persists transactional inserts so getArray and size reflect them")
		void shouldCommitInserts() {
			final TreeWithIndex driver = new TreeWithIndex(new UnorderedLookupTree());

			assertStateAfterCommit(
				driver.tree,
				tested -> {
					driver.addAtPosition(0, 10);
					driver.addAtPosition(1, 20);
					driver.addAtPosition(2, 30);
				},
				(original, committed) -> {
					// the still-committed view never saw the inserts
					assertEquals(0, original.size());
					assertArrayEquals(new int[0], original.getArray());

					// the merged view holds them all, in logical order
					assertEquals(3, committed.size());
					assertArrayEquals(new int[]{10, 20, 30}, committed.getArray());
					assertEquals(10, committed.getRecordAt(0));
					assertEquals(30, committed.getRecordAt(2));
					assertConsistent(original);
					assertConsistent(committed);
				}
			);
		}

		@Test
		@DisplayName("persists transactional removes so getArray and size reflect them")
		void shouldCommitRemoves() {
			final TreeWithIndex driver = committedTreeOfSize(6);

			assertStateAfterCommit(
				driver.tree,
				tested -> {
					driver.remove(1002);
					driver.remove(1000);
				},
				(original, committed) -> {
					assertEquals(6, original.size());
					assertArrayEquals(expectedArrayOfSize(6), original.getArray());

					assertEquals(4, committed.size());
					assertArrayEquals(new int[]{1001, 1003, 1004, 1005}, committed.getArray());
					assertConsistent(original);
					assertConsistent(committed);
				}
			);
		}

		@Test
		@DisplayName("persists inserts that split containers and grow the tree height")
		void shouldCommitInsertsThatSplitAndGrow() {
			final TreeWithIndex driver = new TreeWithIndex(new UnorderedLookupTree());
			final int count = UnorderedLookupTree.DEFAULT_BLOCK_SIZE * 5;

			assertStateAfterCommit(
				driver.tree,
				tested -> {
					for (int i = 0; i < count; i++) {
						driver.addAtPosition(i, 1000 + i);
					}
				},
				(original, committed) -> {
					assertEquals(0, original.size());
					assertEquals(count, committed.size());
					assertArrayEquals(expectedArrayOfSize(count), committed.getArray());
					for (int position = 0; position < count; position++) {
						assertEquals(1000 + position, committed.getRecordAt(position));
					}
					assertConsistent(original);
					assertConsistent(committed);
				}
			);
		}
	}

	@Nested
	@DisplayName("Isolation")
	class IsolationTest {

		@Test
		@DisplayName("makes the new state visible inside the transaction while a fresh committed read does not see it")
		void shouldIsolateUncommittedStateFromCommittedReads() {
			final TreeWithIndex driver = committedTreeOfSize(3);

			assertStateAfterCommit(
				driver.tree,
				tested -> {
					driver.addAtHead(7);
					driver.addAtPosition(tested.size(), 8);

					// INSIDE the transaction the mutated state is fully visible on the same instance
					assertEquals(5, tested.size());
					assertArrayEquals(new int[]{7, 1000, 1001, 1002, 8}, tested.getArray());
				},
				(original, committed) -> {
					// a fresh read of the committed tree (the "other thread") is unaffected by the open transaction
					assertEquals(3, original.size());
					assertArrayEquals(expectedArrayOfSize(3), original.getArray());

					// after commit the merged copy carries the changes
					assertEquals(5, committed.size());
					assertArrayEquals(new int[]{7, 1000, 1001, 1002, 8}, committed.getArray());
					assertConsistent(original);
					assertConsistent(committed);
				}
			);
		}
	}

	@Nested
	@DisplayName("Rollback")
	class RollbackTest {

		@Test
		@DisplayName("leaves the committed tree intact when the transaction is rolled back")
		void shouldLeaveCommittedTreeIntactOnRollback() {
			final TreeWithIndex driver = committedTreeOfSize(4);
			final UnorderedLookupTree tree = driver.tree;
			final int[] expectedBefore = expectedArrayOfSize(4);

			boolean threw = false;
			try {
				assertStateAfterCommit(
					tree,
					tested -> {
						driver.addAtHead(99);
						driver.remove(1001);
						assertEquals(4, tested.size());
						// abort the transaction by raising - assertStateAfterCommit marks it rollback-only and rethrows
						throw new IllegalStateException("forced rollback");
					},
					(original, committed) -> {
						// not reached - the exception propagates out of assertStateAfterCommit
					}
				);
			} catch (IllegalStateException ex) {
				threw = true;
			}

			assertTrue(threw, "the forced rollback exception must propagate");
			// the committed tree must be byte-for-byte unchanged
			assertEquals(4, tree.size());
			assertArrayEquals(expectedBefore, tree.getArray());
			// and structurally intact - the rolled-back path copies must not have leaked into the committed view
			assertConsistent(tree);
		}
	}

	@Nested
	@DisplayName("Layer sweep")
	class LayerSweepTest {

		@Test
		@DisplayName("sweeps the whole node graph cleanly after mutating a tree within a transaction")
		void shouldSweepLayerFullyAfterMutation() {
			// a tree large enough that the touched path spans several internal levels
			final int initial = UnorderedLookupTree.DEFAULT_BLOCK_SIZE << 2;
			final TreeWithIndex driver = committedTreeOfSize(initial);

			// assertStateAfterCommit invokes verifyLayerWasFullySwept() inside its commit; reaching the verify
			// lambda without a StaleTransactionMemoryException proves the composite-producer sweep was complete
			assertStateAfterCommit(
				driver.tree,
				tested -> {
					for (int i = 0; i < 30; i++) {
						driver.addAtPosition(i << 1, 5000 + i);
					}
					driver.remove(1000);
					driver.remove(1010);
				},
				(original, committed) -> {
					assertEquals(initial, original.size());
					assertEquals(initial + 30 - 2, committed.size());
					assertConsistent(original);
					assertConsistent(committed);
				}
			);
		}

		@Test
		@DisplayName("sweeps cleanly when a tree is created and discarded within a transaction")
		void shouldNotLeakLayerWhenTreeIsCreatedAndDiscardedWithinTransaction() {
			// outer tree we never touch - it only provides a transactional context to drive the commit sweep
			final UnorderedLookupTree outer = new UnorderedLookupTree();

			assertStateAfterCommit(
				outer,
				original -> {
					// build a throwaway tree, open ALIVE layers across its whole node graph (splits force a deep
					// tree), then discard it by removing its layers via the maintainer. Without the deep recursion
					// in removeLayer the sub-tree's size/root references and node graph would remain ALIVE and trip
					// StaleTransactionMemoryException during the commit sweep.
					final TreeWithIndex driver = new TreeWithIndex(new UnorderedLookupTree());
					for (int i = 0; i < UnorderedLookupTree.DEFAULT_BLOCK_SIZE * 3; i++) {
						driver.addAtPosition(i, 7000 + i);
					}
					// remove a handful to open additional node layers along the delete path
					driver.remove(7000);
					driver.remove(7005);

					final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
					driver.tree.removeLayer(maintainer);
				},
				(original, committed) -> assertEquals(0, committed.size())
			);
		}
	}

	@Nested
	@DisplayName("Head tracking")
	class HeadTrackingTest {

		/**
		 * Builds a freshly committed head-aware tree holding records `1000..1000+count` in logical order with the records
		 * at `headPositions` marked as chain heads (all in the committed base).
		 */
		@Nonnull
		private static TreeWithIndex committedHeadAwareTree(int count, @Nonnull int[] headPositions) {
			final TreeWithIndex warmUp = new TreeWithIndex(
				new UnorderedLookupTree(
					UnorderedLookupTree.DEFAULT_BLOCK_SIZE - 1, UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP, true)
			);
			for (int i = 0; i < count; i++) {
				warmUp.addAtPosition(i, 1000 + i);
			}
			for (final int hp : headPositions) {
				warmUp.markHead(1000 + hp);
			}
			return warmUp;
		}

		@Test
		@DisplayName("commits head-mark changes so the merged copy reflects them while the committed view is unchanged")
		void shouldCommitHeadMarkChanges() {
			final TreeWithIndex driver = committedHeadAwareTree(6, new int[]{0, 3});

			assertStateAfterCommit(
				driver.tree,
				tested -> {
					driver.addAtPosition(6, 1006);   // append a fresh (non-head) record at the tail
					driver.markHead(1006);           // mark it a head
					driver.unmarkHead(1003);         // and drop the middle head
				},
				(original, committed) -> {
					// the still-committed view keeps its original heads (0 -> 1000, 3 -> 1003)
					assertEquals(2, original.headRank(5));
					assertEquals(1000, (int) original.findHeadCovering(0));
					assertEquals(1003, (int) original.findHeadCovering(3));

					// the merged copy reflects the transactional head changes: 1003 unmarked, 1006 marked
					assertEquals(7, committed.size());
					assertEquals(1, committed.headRank(5));
					assertEquals(2, committed.headRank(6));
					assertEquals(1000, (int) committed.findHeadCovering(5));
					assertEquals(1006, (int) committed.findHeadCovering(6));
					assertConsistent(original);
					assertConsistent(committed);
				}
			);
		}

		@Test
		@DisplayName("leaves committed head marks intact when the transaction is rolled back")
		void shouldLeaveHeadMarksIntactOnRollback() {
			final TreeWithIndex driver = committedHeadAwareTree(6, new int[]{0, 3});
			final UnorderedLookupTree tree = driver.tree;

			boolean threw = false;
			try {
				assertStateAfterCommit(
					tree,
					tested -> {
						driver.markHead(1004);
						driver.unmarkHead(1003);
						driver.addAtPosition(0, 99);
						throw new IllegalStateException("forced rollback");
					},
					(original, committed) -> {
						// not reached
					}
				);
			} catch (IllegalStateException ex) {
				threw = true;
			}

			assertTrue(threw, "the forced rollback exception must propagate");
			// the committed tree keeps exactly its pre-transaction head marks (0 -> 1000, 3 -> 1003)
			assertEquals(6, tree.size());
			assertEquals(2, tree.headRank(5));
			assertEquals(1000, (int) tree.findHeadCovering(0));
			assertEquals(1003, (int) tree.findHeadCovering(3));
			assertConsistent(tree);
		}
	}

	@Nested
	@DisplayName("Flush-time page reset")
	class FlushTimePageResetTest {

		@Test
		@DisplayName("forgetPageStream over untouched leaves must not create a layer at flush time (after commit)")
		void shouldForgetPageStreamWithoutCreatingLayerAfterCommit() {
			// warm-up (outside any transaction): a paged, head-aware tree spanning MULTIPLE leaves - the shape a
			// ChainIndex persists in its PAGED form. Tiny leaves (capacity 4) force many leaves from few records.
			final TreeWithIndex driver = new TreeWithIndex(new UnorderedLookupTree(
				Math.min(UnorderedLookupTree.DEFAULT_BLOCK_SIZE, 4),
				UnorderedLookupTree.DEFAULT_ORDER_KEY_GAP, true, 4, true));
			for (int i = 0; i < 20; i++) {
				driver.addAtPosition(i, 1000 + i);
			}
			final UnorderedLookupTree tree = driver.tree;
			assertTrue(tree.isRootInternal(), "warm-up must span multiple leaves (PAGED shape)");
			// simulate a prior flush: stamp a live page sequence on every leaf and clear its dirty flag. This runs
			// outside any transaction, so the stamps land on the committed baseline and NO leaf carries a layer -
			// exactly the state a leaf a later collapsing transaction never touches is in.
			int seq = 0;
			for (final UnorderedLookupTree.LeafPageHandle handle : tree.leafPageHandles()) {
				handle.setPageSequence(seq++);
				handle.clearDirty();
			}
			assertEquals(seq, tree.livePageSequences().length, "every committed leaf must carry a live page");

			// drive a real transaction whose commit runs forgetPageStream at flush time - i.e. AFTER commit() has
			// flipped allowTransactionalLayerCreation to false, exactly like Catalog.flush -> EntityCollection.flush ->
			// ChainIndex.appendStorageParts on a PAGED->SINGLE collapse. The transaction never touches the tree, so
			// every leaf is an untouched collapse survivor with NO layer and an assigned baseline page sequence: the
			// buggy create-on-first-touch setPageSequence/clearDirty would trip the "already committed" premise here.
			final ForgetAtCommitHandler handler = new ForgetAtCommitHandler(tree);
			Transaction.executeInTransactionIfProvided(
				new Transaction(UUID.randomUUID(), handler, false),
				() -> Transaction.getTransaction().orElseThrow().close()
			);

			// the flush-time forget completed in-thread without throwing, and the merged copy is reset to a clean
			// baseline (no live pages) so a later SINGLE->PAGED regrow starts fresh and re-emits every leaf
			final UnorderedLookupTree committed = handler.getCommitted();
			assertNotNull(committed, "commit must complete without discarding the transaction");
			assertEquals(
				0, committed.livePageSequences().length,
				"forgetPageStream must un-assign every leaf page in the committed copy"
			);
			assertEquals(
				0, committed.collectChangedPages().size(),
				"forgetPageStream must clear every dirty flag in the committed copy"
			);
		}

		/**
		 * Transaction handler that runs the flush-time {@link UnorderedLookupTree#forgetPageStream()} inside
		 * {@link #commit} - which the transaction invokes only AFTER the maintainer has forbidden new layer creation,
		 * reproducing the production ordering (`Catalog.flush` runs during commit, after the commit flag is flipped).
		 */
		private static final class ForgetAtCommitHandler implements TransactionHandler {
			@Nonnull private final UnorderedLookupTree tree;
			@Nullable private UnorderedLookupTree committed;

			ForgetAtCommitHandler(@Nonnull UnorderedLookupTree tree) {
				this.tree = tree;
			}

			@Nullable
			UnorderedLookupTree getCommitted() {
				return this.committed;
			}

			@Override
			public void registerMutation(@Nonnull Mutation mutation) {
				// no-op: this test does not drive WAL mutations
			}

			@Override
			public void commit(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
				// flush-time bookkeeping reset - runs with allowTransactionalLayerCreation already false
				this.tree.forgetPageStream();
				this.committed = transactionalLayer.getStateCopyWithCommittedChanges(this.tree);
				transactionalLayer.verifyLayerWasFullySwept();
			}

			@Override
			public void rollback(@Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Throwable cause) {
				// no-op: the test never rolls back
			}
		}
	}

	@Nested
	@DisplayName("Identity")
	class IdentityTest {

		@Test
		@DisplayName("returns stable and unique id across instances")
		void shouldReturnStableAndUniqueId() {
			final UnorderedLookupTree tree1 = new UnorderedLookupTree();
			final UnorderedLookupTree tree2 = new UnorderedLookupTree();

			final long id1 = tree1.getId();
			final long id2 = tree2.getId();

			// id is stable on repeated calls
			assertEquals(id1, tree1.getId());
			assertEquals(id2, tree2.getId());
			// ids are unique across instances
			assertNotEquals(id1, id2);
		}

		@Test
		@DisplayName("no-op commit yields a distinct, independently usable copy")
		void shouldProduceDistinctUsableInstanceOnNoOpCommit() {
			final TreeWithIndex driver = committedTreeOfSize(3);

			assertStateAfterCommit(
				driver.tree,
				tested -> {
					// no mutations at all
				},
				(original, committed) -> {
					// commit always materialises a fresh instance with its own identity
					assertNotSame(original, committed);
					assertNotEquals(original.getId(), committed.getId());
					// both views hold the same contents
					assertArrayEquals(expectedArrayOfSize(3), original.getArray());
					assertArrayEquals(expectedArrayOfSize(3), committed.getArray());
					assertEquals(3, committed.size());
					// the committed copy is independently addressable
					assertEquals(1000, committed.getRecordAt(0));
					assertEquals(1002, committed.getRecordAt(2));
					assertConsistent(original);
					assertConsistent(committed);
				}
			);
		}
	}
}
