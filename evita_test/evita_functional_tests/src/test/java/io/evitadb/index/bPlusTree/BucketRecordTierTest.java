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
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BPlusLeafTreeNode;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.bitmap.SortedArrayBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Arrays;
import java.util.UUID;
import java.util.PrimitiveIterator.OfInt;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the three-tier record-set representation of a {@link TransactionalBucketBPlusTree} bucket: a single record
 * in the leaf's primitive column, a sorted `int[]` up to {@link OverflowRecords#SMALL_BUCKET_THRESHOLD}, and a
 * {@link TransactionalBitmap} above it.
 *
 * The suite covers the boundary at which each transition happens, the MVCC isolation the array tier inherits from the
 * leaf rather than owning itself, the equality of what a query reads across all three tiers, and the heap the tier is
 * there to save.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
@DisplayName("Bucket record-set tiers")
class BucketRecordTierTest {
	/**
	 * A leaf block size wide enough that every test here stays inside one leaf, so a tier assertion reads the same
	 * slot the mutation touched.
	 */
	private static final int LEAF_BLOCK_SIZE = 255;
	/**
	 * The value whose bucket every test mutates.
	 */
	private static final int VALUE = 42;

	/**
	 * @return an empty bucket tree with {@link #LEAF_BLOCK_SIZE} slots per leaf
	 */
	@Nonnull
	private static TransactionalBucketBPlusTree<Integer> emptyTree() {
		return new TransactionalBucketBPlusTree<>(LEAF_BLOCK_SIZE, Integer.class);
	}

	/**
	 * Builds a tree whose {@link #VALUE} bucket holds `recordCount` records, added one at a time so the bucket passes
	 * through every tier transition the production write path would take it through.
	 *
	 * @param recordCount how many records the bucket must end up holding
	 * @return the populated tree
	 */
	@Nonnull
	private static TransactionalBucketBPlusTree<Integer> treeWithBucketOfSize(int recordCount) {
		final TransactionalBucketBPlusTree<Integer> tree = emptyTree();
		for (int record = 1; record <= recordCount; record++) {
			tree.addRecord(VALUE, record);
		}
		return tree;
	}

	/**
	 * Reads the raw overflow slot of {@link #VALUE}'s bucket - the tier itself, not a view of it.
	 *
	 * @param tree the tree to look into
	 * @return the slot content: `null` for a single-record bucket, an `int[]` or a {@link TransactionalBitmap} otherwise
	 */
	@Nullable
	private static Object overflowSlot(@Nonnull TransactionalBucketBPlusTree<Integer> tree) {
		final BPlusLeafTreeNode<Integer> leaf = tree.enumerateLeaves().get(0);
		final OverflowColumn overflow = leaf.getOverflow();
		if (overflow == null) {
			return null;
		}
		return overflow.recordsAt(leaf.getValueIndex(VALUE));
	}

	/**
	 * @param tree the tree to read
	 * @return the record ids of {@link #VALUE}'s bucket, in the order the bucket enumerates them
	 */
	@Nonnull
	private static int[] recordsOfValue(@Nonnull TransactionalBucketBPlusTree<Integer> tree) {
		return tree.getRecordsEqualTo(VALUE).getArray();
	}

	/**
	 * @param count how many ids to produce
	 * @return the ids `1..count`
	 */
	@Nonnull
	private static int[] idsUpTo(int count) {
		final int[] ids = new int[count];
		for (int i = 0; i < count; i++) {
			ids[i] = i + 1;
		}
		return ids;
	}

	/**
	 * @param from the first id, inclusive
	 * @param to   the last id, inclusive
	 * @return the ids `from..to`
	 */
	@Nonnull
	private static int[] idsBetween(int from, int to) {
		final int[] ids = new int[to - from + 1];
		for (int i = 0; i < ids.length; i++) {
			ids[i] = from + i;
		}
		return ids;
	}

	/**
	 * Runs `insideTransaction` under a real transaction, then either commits it - handing the committed copy of the
	 * tree to `afterCommit` - or rolls it back, leaving the original untouched.
	 *
	 * @param tree              the tree under test
	 * @param insideTransaction the writes to perform inside the transaction
	 * @param afterCommit       receives the committed copy; `null` rolls the transaction back instead
	 */
	private static void inTransaction(
		@Nonnull TransactionalBucketBPlusTree<Integer> tree,
		@Nonnull Runnable insideTransaction,
		@Nullable Consumer<TransactionalBucketBPlusTree<Integer>> afterCommit
	) {
		final CommitCapturingHandler handler = new CommitCapturingHandler(tree);
		Transaction.executeInTransactionIfProvided(
			new Transaction(UUID.randomUUID(), handler, false),
			() -> {
				final Transaction transaction = Transaction.getTransaction().orElseThrow();
				try {
					insideTransaction.run();
				} finally {
					if (afterCommit == null) {
						transaction.setRollbackOnly();
					}
					transaction.close();
				}
			}
		);
		if (afterCommit != null) {
			afterCommit.accept(
				assertNotNullCommitted(handler.getCommitted())
			);
		}
	}

	/**
	 * @param committed the committed copy captured by the handler
	 * @return the same instance, once it is proven present
	 */
	@Nonnull
	private static TransactionalBucketBPlusTree<Integer> assertNotNullCommitted(
		@Nullable TransactionalBucketBPlusTree<Integer> committed
	) {
		assertNotNull(committed, "the transaction must have committed and produced a merged tree");
		return committed;
	}

	/**
	 * Transaction handler that captures the committed copy of the tree under test, so a test can assert on what the
	 * commit merge produced rather than on what the open transaction saw.
	 */
	private static final class CommitCapturingHandler implements TransactionHandler {
		@Nonnull private final TransactionalBucketBPlusTree<Integer> tree;
		@Nullable private TransactionalBucketBPlusTree<Integer> committed;

		CommitCapturingHandler(@Nonnull TransactionalBucketBPlusTree<Integer> tree) {
			this.tree = tree;
		}

		@Nullable
		TransactionalBucketBPlusTree<Integer> getCommitted() {
			return this.committed;
		}

		@Override
		public void registerMutation(@Nonnull Mutation mutation) {
			// no mutation recording is needed for a structure-level test
		}

		@Override
		public void commit(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.committed = transactionalLayer.getStateCopyWithCommittedChanges(this.tree);
			transactionalLayer.verifyLayerWasFullySwept();
		}

		@Override
		public void rollback(@Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Throwable cause) {
			// the writes made inside are discarded; only the untouched original matters
		}
	}

	@Nested
	@DisplayName("Promotion boundary")
	class PromotionBoundary {

		@Test
		@DisplayName("the second record leaves the primitive tier for a sorted array, not a bitmap")
		void shouldPromoteSingleToArrayOnSecondRecord() {
			final TransactionalBucketBPlusTree<Integer> tree = treeWithBucketOfSize(1);
			assertNull(overflowSlot(tree), "a one-record bucket keeps its id in the primitive column");

			tree.addRecord(VALUE, 2);

			assertArrayEquals(
				new int[]{1, 2},
				assertInstanceOf(
					int[].class, overflowSlot(tree),
					"the second record must promote the bucket into the sorted-array tier"
				),
				"the promoted array must hold both ids in order"
			);
		}

		@Test
		@DisplayName("a bucket holding exactly the threshold is still an array, and the next record makes it a bitmap")
		void shouldPromoteArrayToBitmapAboveThreshold() {
			final int threshold = OverflowRecords.SMALL_BUCKET_THRESHOLD;
			final TransactionalBucketBPlusTree<Integer> tree = treeWithBucketOfSize(threshold);

			final int[] atThreshold = assertInstanceOf(
				int[].class, overflowSlot(tree),
				"a bucket of exactly " + threshold + " records must still be a sorted array"
			);
			assertEquals(threshold, atThreshold.length);

			tree.addRecord(VALUE, threshold + 1);

			assertInstanceOf(
				TransactionalBitmap.class, overflowSlot(tree),
				"record " + (threshold + 1) + " must promote the bucket to a bitmap"
			);
			assertArrayEquals(idsUpTo(threshold + 1), recordsOfValue(tree), "no record may be lost by the promotion");
		}

		@Test
		@DisplayName("a bulk add that crosses the threshold in one step promotes to a bitmap")
		void shouldPromoteToBitmapOnBulkAddCrossingThreshold() {
			final int threshold = OverflowRecords.SMALL_BUCKET_THRESHOLD;
			final TransactionalBucketBPlusTree<Integer> tree = treeWithBucketOfSize(2);

			final int[] bulk = new int[threshold];
			for (int i = 0; i < threshold; i++) {
				bulk[i] = i + 3;
			}
			tree.addRecord(VALUE, bulk);

			assertInstanceOf(
				TransactionalBitmap.class, overflowSlot(tree),
				"a bulk add whose union exceeds the threshold must land in the bitmap tier"
			);
			assertArrayEquals(idsUpTo(threshold + 2), recordsOfValue(tree));
		}

		@Test
		@DisplayName("re-adding a record the bucket already holds allocates no new array")
		void shouldKeepTheSameArrayWhenReAddingAMember() {
			final TransactionalBucketBPlusTree<Integer> tree = treeWithBucketOfSize(5);
			final Object before = overflowSlot(tree);

			tree.addRecord(VALUE, 3);

			assertTrue(before == overflowSlot(tree), "a no-op add must leave the very same array in the slot");
			assertArrayEquals(idsUpTo(5), recordsOfValue(tree));
		}

		@ParameterizedTest(name = "a bucket of {0} records")
		@ValueSource(ints = {
			OverflowRecords.SMALL_BUCKET_THRESHOLD - 1,
			OverflowRecords.SMALL_BUCKET_THRESHOLD,
			OverflowRecords.SMALL_BUCKET_THRESHOLD + 1
		})
		@DisplayName("the tier a bucket lands in follows the threshold exactly, on both sides of it")
		void shouldPickTheTierByCardinalityAroundTheThreshold(int recordCount) {
			final TransactionalBucketBPlusTree<Integer> tree = treeWithBucketOfSize(recordCount);

			final Object slot = overflowSlot(tree);
			if (recordCount <= OverflowRecords.SMALL_BUCKET_THRESHOLD) {
				assertEquals(
					recordCount, assertInstanceOf(int[].class, slot, "at or below the threshold the tier is an array").length
				);
			} else {
				assertInstanceOf(TransactionalBitmap.class, slot, "above the threshold the tier is a bitmap");
			}
			assertArrayEquals(idsUpTo(recordCount), recordsOfValue(tree), "no record may be lost by the tier choice");
		}

		@Test
		@DisplayName("a bulk add whose union lands exactly on the threshold stays an array")
		void shouldStayAnArrayWhenABulkAddLandsExactlyOnTheThreshold() {
			// the counterpart of shouldPromoteToBitmapOnBulkAddCrossingThreshold: the merge compares the union size
			// with `>`, so equality must NOT promote
			final int threshold = OverflowRecords.SMALL_BUCKET_THRESHOLD;
			final TransactionalBucketBPlusTree<Integer> tree = treeWithBucketOfSize(2);

			tree.addRecord(VALUE, idsBetween(3, threshold));

			assertEquals(
				threshold,
				assertInstanceOf(
					int[].class, overflowSlot(tree),
					"a union of exactly " + threshold + " records must stay in the array tier"
				).length
			);
			assertArrayEquals(idsUpTo(threshold), recordsOfValue(tree));
		}
	}

	@Nested
	@DisplayName("Demotion")
	class Demotion {

		@Test
		@DisplayName("a bitmap that shrinks to the demotion threshold becomes an array at the commit merge")
		void shouldDemoteBitmapToArrayAtCommit() {
			final int threshold = OverflowRecords.SMALL_BUCKET_THRESHOLD;
			final int demotion = OverflowRecords.SMALL_BUCKET_DEMOTION_THRESHOLD;
			final TransactionalBucketBPlusTree<Integer> tree = treeWithBucketOfSize(threshold + 1);
			assertInstanceOf(TransactionalBitmap.class, overflowSlot(tree));

			final int[] doomed = new int[threshold + 1 - demotion];
			for (int i = 0; i < doomed.length; i++) {
				doomed[i] = demotion + 1 + i;
			}
			inTransaction(
				tree,
				() -> {
					tree.removeRecord(VALUE, doomed);
					assertInstanceOf(
						TransactionalBitmap.class, overflowSlot(tree),
						"the representation must NOT change mid-transaction"
					);
				},
				committed -> {
					final int[] demoted = assertInstanceOf(
						int[].class, overflowSlot(committed),
						"a bucket at or below the demotion threshold must settle back into a sorted array"
					);
					assertArrayEquals(idsUpTo(demotion), demoted);
				}
			);
		}

		@Test
		@DisplayName("a bitmap still above the demotion threshold stays a bitmap after the commit merge")
		void shouldKeepBitmapAboveDemotionThreshold() {
			final int threshold = OverflowRecords.SMALL_BUCKET_THRESHOLD;
			final int demotion = OverflowRecords.SMALL_BUCKET_DEMOTION_THRESHOLD;
			final TransactionalBucketBPlusTree<Integer> tree = treeWithBucketOfSize(threshold + 1);

			// remove one record fewer than the demotion test, landing exactly one above the demotion threshold - the
			// hysteresis gap is what this asserts, so the boundary is tested from both sides
			final int[] doomed = new int[threshold - demotion];
			for (int i = 0; i < doomed.length; i++) {
				doomed[i] = demotion + 2 + i;
			}
			inTransaction(
				tree,
				() -> tree.removeRecord(VALUE, doomed),
				committed -> assertInstanceOf(
					TransactionalBitmap.class, overflowSlot(committed),
					"a bucket of " + (demotion + 1) + " records must stay a bitmap - demotion is at " + demotion
				)
			);
		}

		@Test
		@DisplayName("an array bucket reduced to one record returns to the primitive tier at the commit merge")
		void shouldDemoteArrayToSingleAtCommit() {
			final TransactionalBucketBPlusTree<Integer> tree = treeWithBucketOfSize(4);
			assertInstanceOf(int[].class, overflowSlot(tree));

			inTransaction(
				tree,
				() -> {
					tree.removeRecord(VALUE, 2, 3, 4);
					assertInstanceOf(
						int[].class, overflowSlot(tree), "the representation must NOT change mid-transaction"
					);
				},
				committed -> {
					assertNull(overflowSlot(committed), "a one-record bucket must go back to the primitive column");
					assertArrayEquals(new int[]{1}, recordsOfValue(committed));
				}
			);
		}

		@Test
		@DisplayName("draining an array bucket of every record deletes the bucket")
		void shouldDeleteBucketWhenArrayDrains() {
			final TransactionalBucketBPlusTree<Integer> tree = treeWithBucketOfSize(3);

			tree.removeRecord(VALUE, 1, 2, 3);

			assertTrue(tree.getRecordsEqualTo(VALUE).isEmpty(), "the drained bucket must be gone");
			assertEquals(-1, tree.enumerateLeaves().get(0).getValueIndex(VALUE));
		}

		@Test
		@DisplayName("a bitmap bucket drained to one record returns to the primitive tier at the commit merge")
		void shouldDemoteABitmapBucketToThePrimitiveTierAtCommit() {
			// the commit merge reads the surviving id straight off the committed bitmap; only the array arm of the
			// same demotion is covered by shouldDemoteArrayToSingleAtCommit
			final int threshold = OverflowRecords.SMALL_BUCKET_THRESHOLD;
			final TransactionalBucketBPlusTree<Integer> tree = treeWithBucketOfSize(threshold + 1);
			assertInstanceOf(TransactionalBitmap.class, overflowSlot(tree));

			inTransaction(
				tree,
				() -> tree.removeRecord(VALUE, idsBetween(2, threshold + 1)),
				committed -> {
					assertNull(overflowSlot(committed), "a one-record bucket must go back to the primitive column");
					assertArrayEquals(new int[]{1}, recordsOfValue(committed));
				}
			);
		}

		@ParameterizedTest(name = "a bitmap bucket drained to {0} records")
		@ValueSource(ints = {
			OverflowRecords.SMALL_BUCKET_DEMOTION_THRESHOLD - 1,
			OverflowRecords.SMALL_BUCKET_DEMOTION_THRESHOLD,
			OverflowRecords.SMALL_BUCKET_DEMOTION_THRESHOLD + 1
		})
		@DisplayName("the tier a drained bucket settles into follows the demotion threshold exactly")
		void shouldPickTheTierByCardinalityAroundTheDemotionThreshold(int survivingCount) {
			final int threshold = OverflowRecords.SMALL_BUCKET_THRESHOLD;
			final TransactionalBucketBPlusTree<Integer> tree = treeWithBucketOfSize(threshold + 1);

			inTransaction(
				tree,
				() -> tree.removeRecord(VALUE, idsBetween(survivingCount + 1, threshold + 1)),
				committed -> {
					final Object slot = overflowSlot(committed);
					if (survivingCount <= OverflowRecords.SMALL_BUCKET_DEMOTION_THRESHOLD) {
						assertEquals(
							survivingCount,
							assertInstanceOf(
								int[].class, slot, "at or below the demotion threshold the bucket settles into an array"
							).length
						);
					} else {
						assertInstanceOf(
							TransactionalBitmap.class, slot,
							"above the demotion threshold the bucket stays a bitmap - that gap is the hysteresis"
						);
					}
					assertArrayEquals(idsUpTo(survivingCount), recordsOfValue(committed));
				}
			);
		}
	}

	@Nested
	@DisplayName("MVCC isolation")
	class Isolation {

		@Test
		@DisplayName("a rolled-back add to an array bucket leaves the committed bucket unchanged")
		void shouldLeaveCommittedArrayBucketIntactOnRollback() {
			final TransactionalBucketBPlusTree<Integer> tree = treeWithBucketOfSize(4);
			final Object committedSlotBefore = overflowSlot(tree);

			inTransaction(
				tree,
				() -> {
					tree.addRecord(VALUE, 99);
					assertArrayEquals(
						new int[]{1, 2, 3, 4, 99}, recordsOfValue(tree),
						"the writing transaction must see its own record"
					);
				},
				null
			);

			assertArrayEquals(idsUpTo(4), recordsOfValue(tree), "the rolled-back record must not survive");
			assertTrue(
				committedSlotBefore == overflowSlot(tree),
				"the committed leaf must still point at the very array it held before the transaction"
			);
			assertArrayEquals(
				idsUpTo(4), assertInstanceOf(int[].class, committedSlotBefore),
				"and that array must not have been written to in place"
			);
		}

		@Test
		@DisplayName("a committed add to an array bucket is visible after the merge")
		void shouldPublishArrayBucketAddOnCommit() {
			final TransactionalBucketBPlusTree<Integer> tree = treeWithBucketOfSize(4);

			inTransaction(
				tree,
				() -> tree.addRecord(VALUE, 99),
				committed -> {
					assertArrayEquals(
						new int[]{1, 2, 3, 4, 99}, recordsOfValue(committed),
						"the committed tree must carry the record the transaction added"
					);
					assertArrayEquals(
						idsUpTo(4), recordsOfValue(tree),
						"and the pre-commit instance must be untouched, as MVCC requires"
					);
				}
			);
		}

		@Test
		@DisplayName("a rolled-back removal from an array bucket leaves the committed bucket unchanged")
		void shouldLeaveCommittedArrayBucketIntactOnRolledBackRemoval() {
			final TransactionalBucketBPlusTree<Integer> tree = treeWithBucketOfSize(6);

			inTransaction(tree, () -> tree.removeRecord(VALUE, 2, 4), null);

			assertArrayEquals(idsUpTo(6), recordsOfValue(tree), "the rolled-back removal must not survive");
		}

		@Test
		@DisplayName("a rolled-back promotion out of the array tier leaves the committed array untouched")
		void shouldDiscardAnArrayToBitmapPromotionOnRollback() {
			// the bitmap the promotion mints is born INSIDE the transaction, so the rollback has to drop both the
			// slot and the transactional layer that bitmap opened
			final int threshold = OverflowRecords.SMALL_BUCKET_THRESHOLD;
			final TransactionalBucketBPlusTree<Integer> tree = treeWithBucketOfSize(threshold);
			final Object committedSlotBefore = overflowSlot(tree);

			inTransaction(
				tree,
				() -> {
					tree.addRecord(VALUE, threshold + 1);
					assertInstanceOf(
						TransactionalBitmap.class, overflowSlot(tree),
						"the writing transaction must see its own promotion"
					);
				},
				null
			);

			assertSame(
				committedSlotBefore, overflowSlot(tree),
				"the committed leaf must still point at the very array it held before the transaction"
			);
			assertArrayEquals(idsUpTo(threshold), recordsOfValue(tree));
		}

		@Test
		@DisplayName("a committed promotion out of the array tier is published as a bitmap")
		void shouldPublishAnArrayToBitmapPromotionOnCommit() {
			final int threshold = OverflowRecords.SMALL_BUCKET_THRESHOLD;
			final TransactionalBucketBPlusTree<Integer> tree = treeWithBucketOfSize(threshold);

			inTransaction(
				tree,
				() -> tree.addRecord(VALUE, threshold + 1),
				committed -> {
					assertInstanceOf(
						TransactionalBitmap.class, overflowSlot(committed),
						"the committed bucket must carry the promoted bitmap"
					);
					assertArrayEquals(idsUpTo(threshold + 1), recordsOfValue(committed));
					assertArrayEquals(
						idsUpTo(threshold), recordsOfValue(tree),
						"and the pre-commit instance must be untouched, as MVCC requires"
					);
				}
			);
		}

		@Test
		@DisplayName("a rolled-back demotion out of the bitmap tier leaves the committed bitmap untouched")
		void shouldDiscardABitmapToArrayDemotionOnRollback() {
			final int threshold = OverflowRecords.SMALL_BUCKET_THRESHOLD;
			final int demotion = OverflowRecords.SMALL_BUCKET_DEMOTION_THRESHOLD;
			final TransactionalBucketBPlusTree<Integer> tree = treeWithBucketOfSize(threshold + 1);

			inTransaction(tree, () -> tree.removeRecord(VALUE, idsBetween(demotion + 1, threshold + 1)), null);

			assertInstanceOf(
				TransactionalBitmap.class, overflowSlot(tree),
				"a rolled-back drain must not demote the committed bucket"
			);
			assertArrayEquals(idsUpTo(threshold + 1), recordsOfValue(tree));
		}

		@Test
		@DisplayName("a rolled-back promotion out of the primitive tier leaves no overflow slot behind")
		void shouldDiscardASingleToArrayPromotionOnRollback() {
			// the overflow column itself is created lazily by the promotion, so this asserts the lazily-created
			// column did not leak onto the committed leaf
			final TransactionalBucketBPlusTree<Integer> tree = treeWithBucketOfSize(1);

			inTransaction(tree, () -> tree.addRecord(VALUE, 2), null);

			assertNull(overflowSlot(tree), "the bucket must be back in the primitive column");
			assertArrayEquals(new int[]{1}, recordsOfValue(tree));
		}

		@Test
		@DisplayName("a bucket that crosses the threshold and drains back inside one transaction settles by its final size")
		void shouldSettleByFinalCardinalityWhenABucketOscillatesUpwardsFirst() {
			// the hysteresis gap means an intermediate promotion must not decide the committed tier: only the
			// cardinality the commit merge sees does
			final int threshold = OverflowRecords.SMALL_BUCKET_THRESHOLD;
			final int demotion = OverflowRecords.SMALL_BUCKET_DEMOTION_THRESHOLD;
			final TransactionalBucketBPlusTree<Integer> tree = treeWithBucketOfSize(threshold);

			inTransaction(
				tree,
				() -> {
					tree.addRecord(VALUE, threshold + 1);
					tree.removeRecord(VALUE, idsBetween(demotion + 1, threshold + 1));
				},
				committed -> {
					assertEquals(
						demotion,
						assertInstanceOf(
							int[].class, overflowSlot(committed),
							"a bucket of " + demotion + " records must commit as an array whatever it passed through"
						).length
					);
					assertArrayEquals(idsUpTo(demotion), recordsOfValue(committed));
				}
			);
		}

		@Test
		@DisplayName("a bucket that drains below the demotion threshold and grows back settles by its final size")
		void shouldSettleByFinalCardinalityWhenABucketOscillatesDownwardsFirst() {
			final int threshold = OverflowRecords.SMALL_BUCKET_THRESHOLD;
			final int demotion = OverflowRecords.SMALL_BUCKET_DEMOTION_THRESHOLD;
			final TransactionalBucketBPlusTree<Integer> tree = treeWithBucketOfSize(threshold + 1);

			inTransaction(
				tree,
				() -> {
					tree.removeRecord(VALUE, idsBetween(demotion + 1, threshold + 1));
					tree.addRecord(VALUE, idsBetween(demotion + 1, threshold + 1));
				},
				committed -> {
					assertInstanceOf(
						TransactionalBitmap.class, overflowSlot(committed),
						"a bucket back above the threshold must commit as a bitmap"
					);
					assertArrayEquals(idsUpTo(threshold + 1), recordsOfValue(committed));
				}
			);
		}
	}

	@Nested
	@DisplayName("Read parity across the tiers")
	class ReadParity {

		@Test
		@DisplayName("the same record set reads identically whichever tier holds it")
		void shouldReadIdenticallyInEveryTier() {
			// one record: the primitive tier; five: the array tier; threshold + 1: the bitmap tier
			for (final int size : new int[]{1, 5, OverflowRecords.SMALL_BUCKET_THRESHOLD + 1}) {
				final TransactionalBucketBPlusTree<Integer> tree = treeWithBucketOfSize(size);
				final Bitmap records = tree.getRecordsEqualTo(VALUE);

				assertEquals(size, records.size(), "cardinality differs at size " + size);
				assertArrayEquals(idsUpTo(size), records.getArray(), "ids differ at size " + size);
				assertEquals(1, records.getFirst(), "first differs at size " + size);
				assertEquals(size, records.getLast(), "last differs at size " + size);
				assertTrue(records.contains(size), "contains(last) differs at size " + size);
				assertFalse(records.contains(size + 1), "contains(absent) differs at size " + size);
				assertEquals(size, tree.cardinalityOf(VALUE), "tree cardinality differs at size " + size);
			}
		}

		@Test
		@DisplayName("a small multi bucket answers with a read-only array view, never a TransactionalBitmap")
		void shouldNotHandOutATransactionalBitmapForASmallBucket() {
			final Bitmap records = treeWithBucketOfSize(3).getRecordsEqualTo(VALUE);

			assertInstanceOf(
				SortedArrayBitmap.class, records,
				"a caster relying on the pre-tier contract would break on the majority of buckets"
			);
			assertThrows(
				UnsupportedOperationException.class, () -> records.add(4),
				"the view is over the leaf's own array and must refuse every mutation"
			);
			assertInstanceOf(
				TransactionalBitmap.class,
				treeWithBucketOfSize(OverflowRecords.SMALL_BUCKET_THRESHOLD + 1).getRecordsEqualTo(VALUE),
				"only a bucket above the array tier answers with the live bitmap"
			);
		}

		@Test
		@DisplayName("the previous-record anchor answers the same in the array and bitmap tiers")
		void shouldAnswerPreviousRecordIdenticallyInEveryTier() {
			final int threshold = OverflowRecords.SMALL_BUCKET_THRESHOLD;
			final TransactionalBucketBPlusTree<Integer> asArray = treeWithBucketOfSize(threshold);
			final TransactionalBucketBPlusTree<Integer> asBitmap = treeWithBucketOfSize(threshold + 1);
			// take the extra record back out so both trees hold the same ids in different tiers
			asBitmap.removeRecord(VALUE, threshold + 1);
			assertInstanceOf(int[].class, overflowSlot(asArray));
			assertInstanceOf(TransactionalBitmap.class, overflowSlot(asBitmap));

			for (final int probe : new int[]{1, 2, 17, threshold, threshold + 1}) {
				assertEquals(
					asBitmap.computePreviousRecord(VALUE, probe), asArray.computePreviousRecord(VALUE, probe),
					"the anchor before record " + probe + " must not depend on the tier"
				);
			}
		}

		@Test
		@DisplayName("a record set the array tier holds is read-only from outside")
		void shouldRefuseMutationOfTheArrayView() {
			final TransactionalBucketBPlusTree<Integer> tree = treeWithBucketOfSize(4);
			final Bitmap records = tree.getRecordsEqualTo(VALUE);

			assertInstanceOf(SortedArrayBitmap.class, records);
			assertThrows(UnsupportedOperationException.class, () -> records.add(9));
			assertThrows(UnsupportedOperationException.class, () -> records.remove(1));
		}
	}

	@Nested
	@DisplayName("Unsigned ordering")
	class UnsignedOrdering {

		@Test
		@DisplayName("an array bucket holding negative ids answers exactly as the bitmap tier would")
		void shouldPresentNegativeIdsLikeARoaringBitmap() {
			final int[] ids = {7, -3, Integer.MIN_VALUE, 0, Integer.MAX_VALUE, -1};
			final TransactionalBucketBPlusTree<Integer> tree = emptyTree();
			for (final int id : ids) {
				tree.addRecord(VALUE, id);
			}
			assertInstanceOf(int[].class, overflowSlot(tree));
			final Bitmap arrayTier = tree.getRecordsEqualTo(VALUE);
			// the same ids in the tier this bucket would be in above the threshold
			final TransactionalBitmap bitmapTier = new TransactionalBitmap(ids);

			// getArray() is SIGNED (negatives first) on both tiers - that is what TransactionalBitmap answers
			assertArrayEquals(
				bitmapTier.getArray(), arrayTier.getArray(),
				"getArray must answer in the same signed order on both tiers"
			);
			// while positional access and iteration are UNSIGNED on both (negatives last), which is roaring's own order
			assertEquals(bitmapTier.getFirst(), arrayTier.getFirst(), "getFirst differs between the tiers");
			assertEquals(bitmapTier.getLast(), arrayTier.getLast(), "getLast differs between the tiers");
			for (int i = 0; i < ids.length; i++) {
				assertEquals(bitmapTier.get(i), arrayTier.get(i), "get(" + i + ") differs between the tiers");
			}
			final OfInt bitmapIt = bitmapTier.iterator();
			final OfInt arrayIt = arrayTier.iterator();
			while (bitmapIt.hasNext()) {
				assertEquals(bitmapIt.nextInt(), arrayIt.nextInt(), "iteration order differs between the tiers");
			}
			assertFalse(arrayIt.hasNext(), "the array tier must not enumerate more ids than the bitmap tier");
			for (final int id : ids) {
				assertEquals(bitmapTier.indexOf(id), arrayTier.indexOf(id), "indexOf(" + id + ") differs");
			}
		}

		@Test
		@DisplayName("the signed predecessor of an array bucket matches the roaring answer for the same ids")
		void shouldAnswerSignedPreviousValueLikeARoaringBitmap() {
			final int[] ids = {7, -3, Integer.MIN_VALUE, 0, Integer.MAX_VALUE, -1};
			final int[] sorted = RoaringBitmapBackedBitmap.fromArray(ids).toArray();
			final PersistentRoaringBitmap reference = RoaringBitmapBackedBitmap.fromArray(ids);

			for (final int probe : new int[]{
				Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -4, -3, -2, -1, 0, 6, 7, 8, Integer.MAX_VALUE
			}) {
				assertEquals(
					RoaringBitmapBackedBitmap.signedPreviousValue(reference, probe),
					OverflowRecords.signedPreviousValue(sorted, probe),
					"the signed predecessor of " + probe + " must not depend on the tier"
				);
			}
		}
	}

	@Nested
	@DisplayName("Heap")
	class Heap {

		@Test
		@DisplayName("a leaf of small buckets costs far less in the array tier than in the bitmap tier")
		void shouldCostLessInTheArrayTier() {
			final int bucketCount = 200;
			final int recordsPerBucket = 5;

			final TransactionalBucketBPlusTree<Integer> tree = emptyTree();
			for (int value = 0; value < bucketCount; value++) {
				for (int record = 1; record <= recordsPerBucket; record++) {
					tree.addRecord(value, value * 1000 + record);
				}
			}
			final BPlusLeafTreeNode<Integer> leaf = tree.enumerateLeaves().get(0);
			final OverflowColumn overflow = leaf.getOverflow();
			assertNotNull(overflow);

			long arrayTierBytes = 0L;
			long bitmapTierBytes = 0L;
			for (int slot = 0; slot < bucketCount; slot++) {
				final Object records = overflow.recordsAt(slot);
				final int[] asArray = assertInstanceOf(int[].class, records, "bucket " + slot + " must be an array");
				arrayTierBytes += OverflowRecords.heapSizeInBytes(asArray);
				bitmapTierBytes += new TransactionalBitmap(asArray).getHeapSizeInBytes();
			}

			// measured on the running VM's layout: 200 buckets of 5 record ids each
			assertTrue(
				arrayTierBytes * 4 < bitmapTierBytes,
				"the array tier must cost less than a quarter of the bitmap tier, was " + arrayTierBytes
					+ " B against " + bitmapTierBytes + " B"
			);
		}

		@Test
		@DisplayName("the leaf's own heap figure follows the tier a bucket is in")
		void shouldChargeTheLeafForTheTierItHolds() {
			final TransactionalBucketBPlusTree<Integer> small = treeWithBucketOfSize(4);
			final TransactionalBucketBPlusTree<Integer> large =
				treeWithBucketOfSize(OverflowRecords.SMALL_BUCKET_THRESHOLD + 1);

			// the sizer prices a boxed Integer key; it is the same on both sides, so it cancels out of the comparison
			final ToLongFunction<Object> keySizer = key -> 16L;
			assertTrue(
				small.getHeapSizeInBytes(keySizer) < large.getHeapSizeInBytes(keySizer),
				"a four-record array bucket must be charged less than a bitmap bucket"
			);
		}
	}

	@Nested
	@DisplayName("Record-set algebra")
	class RecordSetAlgebra {

		@Test
		@DisplayName("removing ids the bucket does not hold changes nothing")
		void shouldIgnoreAbsentIdsOnRemoval() {
			final TransactionalBucketBPlusTree<Integer> tree = treeWithBucketOfSize(5);
			final Object before = overflowSlot(tree);

			tree.removeRecord(VALUE, 77, 88);

			assertTrue(before == overflowSlot(tree), "a no-op removal must leave the very same array in the slot");
			assertArrayEquals(idsUpTo(5), recordsOfValue(tree));
		}

		@Test
		@DisplayName("a bulk add with repeats and members stores each id exactly once")
		void shouldDeduplicateBulkAdds() {
			final TransactionalBucketBPlusTree<Integer> tree = treeWithBucketOfSize(3);

			tree.addRecord(VALUE, 3, 3, 5, 4, 5, 1);

			assertArrayEquals(new int[]{1, 2, 3, 4, 5}, recordsOfValue(tree));
			assertArrayEquals(new int[]{1, 2, 3, 4, 5}, assertInstanceOf(int[].class, overflowSlot(tree)));
		}

		@Test
		@DisplayName("a bucket born multi-record arrives sorted and distinct")
		void shouldSortAndDeduplicateANewlyInsertedBucket() {
			final TransactionalBucketBPlusTree<Integer> tree = emptyTree();

			tree.addRecord(VALUE, 9, 3, 9, 1);

			assertArrayEquals(new int[]{1, 3, 9}, recordsOfValue(tree));
			assertArrayEquals(new int[]{1, 3, 9}, assertInstanceOf(int[].class, overflowSlot(tree)));
		}

		@Test
		@DisplayName("the unsigned search agrees with a linear scan over ids of both signs")
		void shouldSearchUnsignedSortedArrays() {
			final int[] ids = RoaringBitmapBackedBitmap
				.fromArray(new int[]{5, -7, Integer.MIN_VALUE, 0, Integer.MAX_VALUE}).toArray();

			for (final int id : ids) {
				assertEquals(
					linearIndexOf(ids, id), SortedArrayBitmap.unsignedBinarySearch(ids, id),
					"the search must find " + id + " where a scan does"
				);
			}
			assertTrue(SortedArrayBitmap.unsignedBinarySearch(ids, 1) < 0, "an absent id must report absent");
		}

		/**
		 * @param ids the array to scan
		 * @param id  the id to look for
		 * @return the index of `id`, or `-1`
		 */
		private static int linearIndexOf(@Nonnull int[] ids, int id) {
			for (int i = 0; i < ids.length; i++) {
				if (ids[i] == id) {
					return i;
				}
			}
			return -1;
		}
	}

	@Nested
	@DisplayName("Bulk load")
	class BulkLoad {

		@Test
		@DisplayName("a persisted page of small buckets loads straight into the array tier")
		void shouldLoadSmallBucketsAsArrays() {
			final TransactionalBucketBPlusTree<Integer> tree = emptyTree();
			final Object[] overflow = {
				OverflowRecords.loadedRecordSet(new TransactionalBitmap(1, 2, 3)),
				null,
				OverflowRecords.loadedRecordSet(new TransactionalBitmap(idsUpTo(OverflowRecords.SMALL_BUCKET_THRESHOLD + 1)))
			};

			tree.bulkLoadPage(new Object[]{10, 20, 30}, new long[]{0, 7, 0}, overflow, 3);

			final BPlusLeafTreeNode<Integer> leaf = tree.enumerateLeaves().get(0);
			final OverflowColumn column = leaf.getOverflow();
			assertNotNull(column);
			assertArrayEquals(new int[]{1, 2, 3}, assertInstanceOf(int[].class, column.recordsAt(0)));
			assertNull(column.recordsAt(1), "a single-record bucket carries no slot");
			assertInstanceOf(
				TransactionalBitmap.class, column.recordsAt(2),
				"a bucket above the threshold must load as a bitmap"
			);
			assertArrayEquals(new int[]{1, 2, 3}, tree.getRecordsEqualTo(10).getArray());
			assertArrayEquals(new int[]{7}, tree.getRecordsEqualTo(20).getArray());
			assertEquals(
				OverflowRecords.SMALL_BUCKET_THRESHOLD + 1, tree.getRecordsEqualTo(30).size()
			);
		}

		@Test
		@DisplayName("a loaded bucket of exactly the threshold is an array, and one record more is a bitmap")
		void shouldPickTheLoadedTierByCardinalityAroundTheThreshold() {
			// a load has no prior representation to be hysteretic about, so the tier follows the cardinality
			// directly - the threshold itself is the side no test pinned
			final int threshold = OverflowRecords.SMALL_BUCKET_THRESHOLD;

			assertEquals(
				threshold,
				assertInstanceOf(
					int[].class,
					OverflowRecords.loadedRecordSet(new TransactionalBitmap(idsUpTo(threshold))),
					"a persisted bucket of exactly " + threshold + " records loads into the array tier"
				).length
			);
			assertInstanceOf(
				TransactionalBitmap.class,
				OverflowRecords.loadedRecordSet(new TransactionalBitmap(idsUpTo(threshold + 1))),
				"one record more loads into the bitmap tier"
			);
		}

		@Test
		@DisplayName("a bulk-loaded page carrying a slot of a foreign shape is refused at the load")
		void shouldRefuseAForeignOverflowSlotAtBulkLoad() {
			final TransactionalBucketBPlusTree<Integer> foreignShape = emptyTree();

			assertThrows(
				GenericEvitaInternalError.class,
				() -> foreignShape.bulkLoadPage(
					new Object[]{10, 20, 30}, new long[]{0, 0, 0}, new Object[]{"not a record set", null, null}, 3
				),
				"a slot that is neither a sorted int[] nor a bitmap must be refused where it is loaded, not on a later read"
			);

			final int threshold = OverflowRecords.SMALL_BUCKET_THRESHOLD;
			final TransactionalBucketBPlusTree<Integer> oversizedArray = emptyTree();

			assertThrows(
				GenericEvitaInternalError.class,
				() -> oversizedArray.bulkLoadPage(
					new Object[]{10}, new long[]{0}, new Object[]{idsUpTo(threshold + 1)}, 1
				),
				"an int[] slot above the promote threshold is a shape the write path never produces"
			);

			// the check must not be over-strict: a well-formed page still loads straight into the array tier
			final TransactionalBucketBPlusTree<Integer> wellFormed = emptyTree();
			wellFormed.bulkLoadPage(
				new Object[]{10, 20}, new long[]{0, 5}, new Object[]{idsUpTo(threshold), null}, 2
			);

			final OverflowColumn column = wellFormed.enumerateLeaves().get(0).getOverflow();
			assertNotNull(column);
			assertEquals(threshold, assertInstanceOf(int[].class, column.recordsAt(0)).length);
			assertNull(column.recordsAt(1), "a single-record bucket carries no slot");
			assertArrayEquals(new int[]{5}, wellFormed.getRecordsEqualTo(20).getArray());
		}
	}

	@Nested
	@DisplayName("Split and merge")
	class SplitAndMerge {

		@Test
		@DisplayName("array buckets survive a leaf split with every record intact")
		void shouldKeepArrayBucketsAcrossASplit() {
			// a small block size forces splits after a handful of buckets, moving array slots between leaves
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(5, Integer.class);
			for (int value = 0; value < 40; value++) {
				tree.addRecord(value, value * 10 + 1, value * 10 + 2, value * 10 + 3);
			}

			assertTrue(tree.enumerateLeaves().size() > 1, "the tree must have split");
			for (int value = 0; value < 40; value++) {
				assertArrayEquals(
					new int[]{value * 10 + 1, value * 10 + 2, value * 10 + 3},
					tree.getRecordsEqualTo(value).getArray(),
					"records lost at value " + value
				);
			}
		}

		@Test
		@DisplayName("array buckets survive the leaf merges a bulk deletion causes")
		void shouldKeepArrayBucketsAcrossMerges() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(5, Integer.class);
			for (int value = 0; value < 40; value++) {
				tree.addRecord(value, value * 10 + 1, value * 10 + 2, value * 10 + 3);
			}
			for (int value = 0; value < 40; value += 2) {
				tree.removeRecord(value, value * 10 + 1, value * 10 + 2, value * 10 + 3);
			}

			for (int value = 1; value < 40; value += 2) {
				assertArrayEquals(
					new int[]{value * 10 + 1, value * 10 + 2, value * 10 + 3},
					tree.getRecordsEqualTo(value).getArray(),
					"records lost at surviving value " + value
				);
			}
			for (int value = 0; value < 40; value += 2) {
				assertTrue(tree.getRecordsEqualTo(value).isEmpty(), "value " + value + " must be gone");
			}
		}
	}

	@Nested
	@DisplayName("Refusals")
	class Refusals {

		@Test
		@DisplayName("a slot of an unexpected shape is refused rather than mis-cast")
		void shouldRefuseAForeignSlotShape() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> OverflowRecords.cardinality("not a record set")
			);
		}

		@Test
		@DisplayName("the sorted view refuses a range outside the array")
		void shouldRefuseAnOutOfBoundsRange() {
			final SortedArrayBitmap view = new SortedArrayBitmap(1, 2, 3);
			assertThrows(IndexOutOfBoundsException.class, () -> view.getRange(0, 4));
			assertArrayEquals(new int[]{2, 3}, view.getRange(1, 3));
		}

		@Test
		@DisplayName("the sorted view hands out a copy, never the array it wraps")
		void shouldHandOutACopyOfTheArray() {
			final int[] backing = {1, 2, 3};
			final SortedArrayBitmap view = new SortedArrayBitmap(backing);

			final int[] handedOut = view.getArray();
			handedOut[0] = 99;

			assertArrayEquals(new int[]{1, 2, 3}, backing, "the wrapped array must not be reachable for writing");
			assertArrayEquals(new int[]{1, 2, 3}, Arrays.copyOf(view.getArray(), 3));
		}
	}

	@Nested
	@DisplayName("Record-set edge shapes")
	class RecordSetEdgeShapes {

		@Test
		@DisplayName("an empty record set has no signed predecessor at all")
		void shouldAnswerNoPreviousValueForAnEmptyRecordSet() {
			assertEquals(
				RoaringBitmapBackedBitmap.NO_PREVIOUS_VALUE,
				OverflowRecords.signedPreviousValue(new int[0], 5)
			);
			assertEquals(
				RoaringBitmapBackedBitmap.NO_PREVIOUS_VALUE,
				OverflowRecords.signedPreviousValue(new int[0], -5)
			);
		}

		@Test
		@DisplayName("a non-negative bound over an all-negative record set falls back to the greatest negative id")
		void shouldFallBackToTheGreatestNegativeIdForANonNegativeBound() {
			// every id is signed-smaller than the bound, so the answer is the last element under UNSIGNED ordering -
			// the arm a record set that also holds a non-negative id can never reach
			final int[] allNegative = {Integer.MIN_VALUE, -7, -1};
			final PersistentRoaringBitmap reference = RoaringBitmapBackedBitmap.fromArray(allNegative);

			for (final int probe : new int[]{0, 5, Integer.MAX_VALUE}) {
				assertEquals(
					RoaringBitmapBackedBitmap.signedPreviousValue(reference, probe),
					OverflowRecords.signedPreviousValue(allNegative, probe),
					"the signed predecessor of " + probe + " must not depend on the tier"
				);
			}
		}

		@Test
		@DisplayName("a bound below every id of an all-non-negative record set has no signed predecessor")
		void shouldAnswerNoPreviousValueBelowAnAllNonNegativeRecordSet() {
			final int[] allNonNegative = {5, 9};
			final PersistentRoaringBitmap reference = RoaringBitmapBackedBitmap.fromArray(allNonNegative);

			for (final int probe : new int[]{Integer.MIN_VALUE, -1, 0, 4}) {
				assertEquals(
					RoaringBitmapBackedBitmap.signedPreviousValue(reference, probe),
					OverflowRecords.signedPreviousValue(allNonNegative, probe),
					"the signed predecessor of " + probe + " must not depend on the tier"
				);
			}
		}

		@Test
		@DisplayName("the same id repeated in one removal is dropped exactly once")
		void shouldDropARepeatedIdOnlyOnce() {
			// the survivor array is sized from the count of ids genuinely dropped, so counting a repeat twice would
			// allocate it short and silently lose a record
			final TransactionalBucketBPlusTree<Integer> tree = treeWithBucketOfSize(5);

			tree.removeRecord(VALUE, 3, 3, 3);

			assertArrayEquals(new int[]{1, 2, 4, 5}, recordsOfValue(tree));
			assertEquals(4, assertInstanceOf(int[].class, overflowSlot(tree)).length);
		}

		@Test
		@DisplayName("a removal mixing a repeated id with an absent one drops only what the bucket holds")
		void shouldIgnoreAbsentIdsAmongRepeatedOnes() {
			final TransactionalBucketBPlusTree<Integer> tree = treeWithBucketOfSize(5);

			tree.removeRecord(VALUE, 2, 2, 99, 99, 4);

			assertArrayEquals(new int[]{1, 3, 5}, recordsOfValue(tree));
			assertEquals(3, assertInstanceOf(int[].class, overflowSlot(tree)).length);
		}

		@Test
		@DisplayName("narrowing a slot of a foreign shape to a record array is refused rather than mis-cast")
		void shouldRefuseAForeignSlotShapeWhenNarrowingToAnArray() {
			assertThrows(GenericEvitaInternalError.class, () -> OverflowRecords.asRecordArray("not a record set"));
			assertThrows(
				GenericEvitaInternalError.class,
				() -> OverflowRecords.asRecordArray(new TransactionalBitmap(1, 2, 3))
			);
		}

		@Test
		@DisplayName("a drained record set reads as the shared empty bitmap and costs nothing")
		void shouldPresentADrainedRecordSetAsEmpty() {
			// the empty array is one shared instance for the whole JVM that no leaf owns, so it must be charged to
			// none of them
			assertSame(EmptyBitmap.INSTANCE, OverflowRecords.asBitmapView(new int[0]));
			assertEquals(0L, OverflowRecords.heapSizeInBytes(new int[0]));
		}

		@Test
		@DisplayName("adding only the id a single-record bucket already holds leaves it holding just that id")
		void shouldKeepASingleRecordBucketWhenEveryAddedIdIsTheOneItHolds() {
			// a multi-id add on a single-record bucket takes the promote path even when the union turns out to be the
			// one id it already had - the one reachable shape in which a live slot carries a single-element array
			final TransactionalBucketBPlusTree<Integer> tree = emptyTree();
			tree.addRecord(VALUE, 5);
			assertNull(overflowSlot(tree));

			inTransaction(
				tree,
				() -> {
					tree.addRecord(VALUE, 5, 5);
					assertArrayEquals(new int[]{5}, recordsOfValue(tree));
					assertEquals(1, tree.getRecordsEqualTo(VALUE).size());
				},
				committed -> {
					assertNull(overflowSlot(committed), "the commit merge must put the bucket back in the primitive tier");
					assertArrayEquals(new int[]{5}, recordsOfValue(committed));
				}
			);
		}
	}

}
