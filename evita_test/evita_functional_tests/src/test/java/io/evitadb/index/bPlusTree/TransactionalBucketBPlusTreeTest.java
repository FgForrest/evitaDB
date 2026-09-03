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

import io.evitadb.comparator.LocalizedStringComparator;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyReport;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BPlusInternalTreeNode;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BPlusLeafTreeNode;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BPlusTreeNode;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.SingleRecordBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertSavepointCommitKeeps;
import static io.evitadb.utils.AssertionUtils.assertSavepointRollbackRestores;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the correctness of {@link TransactionalBucketBPlusTree} — the columnar-leaf bucket store backing the
 * inverted index. Exercises bucket insert/promotion/demotion, single↔multi representation, point
 * lookups, ordered forward / from-key / reverse cursors, leaf split / merge / steal rebalancing, bucket delete +
 * collapse, negative primary keys (including {@link Integer#MIN_VALUE}) as single and multi members, and the full
 * MVCC machinery (isolation, rollback, commit merge including a deep-committed overflow bitmap, and the
 * delete-a-multi-bucket-then-commit path that proves the discarded bitmap layer is released). Bounded fixed-seed
 * randomized churn guards the rebalancing/commit machinery against regressions.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Transactional bucket B+ tree")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class TransactionalBucketBPlusTreeTest {

	/**
	 * Verifies the tree's consistency oracle reports CONSISTENT and that the forward/reverse cursors enumerate the
	 * expected sorted keys.
	 *
	 * @param tree         the tree to verify
	 * @param expectedKeys the expected sorted key array
	 */
	private static void verifyTreeConsistency(
		@Nonnull TransactionalBucketBPlusTree<Integer> tree, @Nonnull int... expectedKeys
	) {
		final ConsistencyReport consistencyReport = tree.getConsistencyReport();
		assertEquals(ConsistencyState.CONSISTENT, consistencyReport.state(), consistencyReport.report());
		verifyForwardCursorKeys(tree, expectedKeys);
		verifyReverseCursorKeys(tree, expectedKeys);
	}

	/**
	 * Asserts the forward cursor yields exactly the given sorted keys in ascending order.
	 *
	 * @param tree         the tree whose forward cursor is verified
	 * @param expectedKeys the expected sorted key array
	 */
	private static void verifyForwardCursorKeys(
		@Nonnull TransactionalBucketBPlusTree<Integer> tree, @Nonnull int... expectedKeys
	) {
		final int[] reconstructed = new int[expectedKeys.length];
		int index = 0;
		final BucketCursor<Integer> cursor = tree.cursor();
		while (cursor.next()) {
			reconstructed[index++] = cursor.value();
		}
		assertEquals(expectedKeys.length, index, "Forward cursor produced a different number of keys!");
		assertArrayEquals(expectedKeys, reconstructed, "Forward cursor keys differ!");
	}

	/**
	 * Asserts the reverse cursor yields exactly the given sorted keys in descending order.
	 *
	 * @param tree         the tree whose reverse cursor is verified
	 * @param expectedKeys the expected sorted key array
	 */
	private static void verifyReverseCursorKeys(
		@Nonnull TransactionalBucketBPlusTree<Integer> tree, @Nonnull int... expectedKeys
	) {
		final int[] reconstructed = new int[expectedKeys.length];
		int index = expectedKeys.length;
		final BucketCursor<Integer> cursor = tree.reverseCursor();
		while (cursor.next()) {
			reconstructed[--index] = cursor.value();
		}
		assertEquals(0, index, "Reverse cursor produced a different number of keys!");
		assertArrayEquals(expectedKeys, reconstructed, "Reverse cursor keys differ!");
	}

	/**
	 * Collects the record ids associated with a value into a sorted array (allocation is irrelevant in tests).
	 *
	 * @param tree  the tree to read from
	 * @param value the value to look up
	 * @return the sorted record ids for the value
	 */
	@Nonnull
	private static int[] recordsOf(@Nonnull TransactionalBucketBPlusTree<Integer> tree, int value) {
		return tree.getRecordsEqualTo(value).getArray();
	}

	/**
	 * Inserts {@code totalKeys} distinct single-record buckets `i -> i*10` into a small-block tree and returns it with
	 * its sorted key array.
	 *
	 * @param seed      the random seed driving the insertion order
	 * @param totalKeys the number of distinct buckets to create
	 * @return the populated tree and its sorted keys
	 */
	@Nonnull
	private static TreeTuple prepareRandomTree(long seed, int totalKeys) {
		final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
		final Random random = new Random(seed);
		final List<Integer> order = new ArrayList<>(totalKeys);
		for (int i = 0; i < totalKeys; i++) {
			order.add(i);
		}
		java.util.Collections.shuffle(order, random);
		final TreeSet<Integer> keys = new TreeSet<>();
		for (final int key : order) {
			tree.addRecord(key, key * 10);
			keys.add(key);
		}
		return new TreeTuple(tree, keys.stream().mapToInt(Integer::intValue).toArray());
	}

	/**
	 * Inserts `totalKeys` buckets `i -> i*10` into a small-block tree, promoting every `multiStride`-th bucket to a
	 * multi-record bucket (its second id is `i*10 + 1`). All inserts happen outside any transaction so the resulting
	 * tree is a fully committed base layout — the caller may then open a transaction over it.
	 *
	 * @param seed        the random seed driving the insertion order
	 * @param totalKeys   the number of distinct buckets to create
	 * @param multiStride every `multiStride`-th bucket becomes a multi bucket (must be >= 1)
	 * @return the populated tree and its sorted keys
	 */
	@Nonnull
	private static TreeTuple prepareRandomMultiTree(long seed, int totalKeys, int multiStride) {
		final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
		final Random random = new Random(seed);
		final List<Integer> order = new ArrayList<>(totalKeys);
		for (int i = 0; i < totalKeys; i++) {
			order.add(i);
		}
		java.util.Collections.shuffle(order, random);
		final TreeSet<Integer> keys = new TreeSet<>();
		for (final int key : order) {
			if (key % multiStride == 0) {
				tree.addRecord(key, key * 10, key * 10 + 1);
			} else {
				tree.addRecord(key, key * 10);
			}
			keys.add(key);
		}
		return new TreeTuple(tree, keys.stream().mapToInt(Integer::intValue).toArray());
	}

	/**
	 * Small tuple bundling a populated tree with its sorted key array.
	 *
	 * @param tree the populated tree
	 * @param keys the sorted key array
	 */
	private record TreeTuple(@Nonnull TransactionalBucketBPlusTree<Integer> tree, @Nonnull int[] keys) {
	}

	/**
	 * A test-only key type that implements both {@link Comparable} and {@link TransactionalLayerProducer}, used to
	 * verify that the constructor rejects key types participating in the MVCC machinery.
	 */
	private static class TransactionalComparableKey
		implements Comparable<TransactionalComparableKey>,
		TransactionalLayerProducer<Void, TransactionalComparableKey> {

		@Override
		public int compareTo(@Nonnull TransactionalComparableKey o) {
			return 0;
		}

		@Override
		public long getId() {
			return 0;
		}

		@Nullable
		@Override
		public Void createLayer() {
			return null;
		}

		@Override
		public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			// no-op
		}

		@Nonnull
		@Override
		public TransactionalComparableKey createCopyWithMergedTransactionalMemory(
			@Nullable Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
			return this;
		}
	}

	@Nested
	@DisplayName("Insert and promotion")
	class InsertOperationsTest {

		@Test
		@DisplayName("creates single-record buckets in sorted order")
		void shouldInsertSingleRecords() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			assertStateAfterCommit(
				tree,
				tested -> {
					tested.addRecord(5, 500);
					tested.addRecord(1, 100);
					tested.addRecord(3, 300);
				},
				(original, committed) -> {
					assertEquals(0, original.bucketCount());
					assertEquals(3, committed.bucketCount());
					assertEquals(3, committed.recordCount());
					assertArrayEquals(new int[]{100}, recordsOf(committed, 1));
					assertArrayEquals(new int[]{300}, recordsOf(committed, 3));
					assertArrayEquals(new int[]{500}, recordsOf(committed, 5));
					verifyTreeConsistency(committed, 1, 3, 5);
				}
			);
		}

		@Test
		@DisplayName("returns a lean single-record bitmap for a single bucket")
		void shouldExposeSingleRecordBitmap() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(7, 70);
			final Bitmap records = tree.getRecordsEqualTo(7);
			assertInstanceOf(SingleRecordBitmap.class, records, "Single bucket must yield a SingleRecordBitmap view!");
			assertEquals(1, records.size());
			assertEquals(70, records.getFirst());
			assertEquals(1, tree.cardinalityOf(7));
		}

		@Test
		@DisplayName("promotes a single bucket to a bitmap on a second distinct record")
		void shouldPromoteOnSecondRecord() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			assertStateAfterCommit(
				tree,
				tested -> {
					tested.addRecord(5, 100);
					tested.addRecord(5, 200);
				},
				(original, committed) -> {
					assertEquals(1, committed.bucketCount());
					assertEquals(2, committed.cardinalityOf(5));
					final Bitmap records = committed.getRecordsEqualTo(5);
					assertFalse(records instanceof SingleRecordBitmap, "Promoted bucket must not be single!");
					assertArrayEquals(new int[]{100, 200}, records.getArray());
				}
			);
		}

		@Test
		@DisplayName("keeps a single bucket compact when re-adding its sole record")
		void shouldStaySingleWhenReaddingSameRecord() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(5, 100);
			tree.addRecord(5, 100);
			assertEquals(1, tree.cardinalityOf(5));
			assertInstanceOf(SingleRecordBitmap.class, tree.getRecordsEqualTo(5), "Bucket must stay single!");
		}

		@Test
		@DisplayName("creates a single bucket from a one-element vararg, a multi from many")
		void shouldHandleVarargAdd() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(1, new int[]{10});
			tree.addRecord(2, 20, 21, 22);
			assertInstanceOf(
				SingleRecordBitmap.class, tree.getRecordsEqualTo(1), "Single-vararg bucket must be single!");
			assertEquals(1, tree.cardinalityOf(1));
			assertEquals(3, tree.cardinalityOf(2));
			assertArrayEquals(new int[]{20, 21, 22}, recordsOf(tree, 2));
		}

		@Test
		@DisplayName("keeps a single bucket compact when the only vararg id is the held one")
		void shouldStaySingleWhenVarargReaddsSameRecord() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(5, 100);
			tree.addRecord(5, new int[]{100});
			assertEquals(1, tree.cardinalityOf(5));
			assertInstanceOf(SingleRecordBitmap.class, tree.getRecordsEqualTo(5), "Bucket must stay single!");
		}

		@Test
		@DisplayName("promotes a single bucket when a vararg add includes other ids")
		void shouldPromoteOnVarargWithOtherIds() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(5, 100);
			tree.addRecord(5, 100, 200);
			assertEquals(2, tree.cardinalityOf(5));
			assertArrayEquals(new int[]{100, 200}, recordsOf(tree, 5));
		}

		@Test
		@DisplayName("rejects an empty vararg add")
		void shouldRejectEmptyVarargAdd() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			assertThrows(IllegalArgumentException.class, () -> tree.addRecord(5, new int[0]));
		}

		@Test
		@DisplayName("splits a leaf when capacity is exceeded")
		void shouldSplitLeafWhenFull() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			assertStateAfterCommit(
				tree,
				tested -> {
					tested.addRecord(1, 10);
					tested.addRecord(2, 20);
					tested.addRecord(3, 30);
					tested.addRecord(4, 40);
				},
				(original, committed) -> {
					assertEquals(4, committed.bucketCount());
					verifyTreeConsistency(committed, 1, 2, 3, 4);
				}
			);
		}

		@Test
		@DisplayName("stays balanced over a long ascending run")
		void shouldStayBalanced() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			final AtomicReference<int[]> keys = new AtomicReference<>(new int[0]);
			assertStateAfterCommit(
				tree,
				tested -> {
					for (int i = 1; i <= 40; i++) {
						tested.addRecord(i, i * 10);
						keys.set(io.evitadb.utils.ArrayUtils.insertIntIntoOrderedArray(i, keys.get()));
					}
				},
				(original, committed) -> {
					assertEquals(40, committed.bucketCount());
					verifyTreeConsistency(committed, keys.get());
				}
			);
		}
	}

	@Nested
	@DisplayName("Lookup operations")
	class LookupOperationsTest {

		@Test
		@DisplayName("reports absence with empty bitmap, zero cardinality and contains==false")
		void shouldReportAbsence() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(5, 50);
			assertSame(EmptyBitmap.INSTANCE, tree.getRecordsEqualTo(9));
			assertEquals(0, tree.cardinalityOf(9));
			assertFalse(tree.contains(9));
			assertTrue(tree.contains(5));
		}

		@Test
		@DisplayName("treats null lookups as absent")
		void shouldHandleNullLookups() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(5, 50);
			assertSame(EmptyBitmap.INSTANCE, tree.getRecordsEqualTo(null));
			assertEquals(0, tree.cardinalityOf(null));
			assertFalse(tree.contains(null));
		}

		@Test
		@DisplayName("counts total records across single and multi buckets")
		void shouldCountRecords() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(1, 10);
			tree.addRecord(2, 20, 21, 22);
			tree.addRecord(3, 30);
			assertEquals(3, tree.bucketCount());
			assertEquals(5, tree.recordCount());
		}
	}

	@Nested
	@DisplayName("Remove and delete")
	class RemoveOperationsTest {

		@Test
		@DisplayName("deletes a single bucket when its sole record is removed")
		void shouldDeleteSingleBucket() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			assertStateAfterCommit(
				tree,
				tested -> {
					tested.addRecord(1, 10);
					tested.addRecord(2, 20);
					tested.addRecord(3, 30);
					tested.removeRecord(2, 20);
				},
				(original, committed) -> {
					assertEquals(2, committed.bucketCount());
					assertFalse(committed.contains(2));
					verifyTreeConsistency(committed, 1, 3);
				}
			);
		}

		@Test
		@DisplayName("removes a single record from a multi bucket and keeps the bucket")
		void shouldRemoveFromMultiBucket() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(5, 100, 200, 300);
			tree.removeRecord(5, 200);
			assertEquals(2, tree.cardinalityOf(5));
			assertArrayEquals(new int[]{100, 300}, recordsOf(tree, 5));
		}

		@Test
		@DisplayName("deletes a multi bucket when its last record is removed")
		void shouldDeleteMultiBucketWhenEmptied() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(5, 100, 200);
			tree.removeRecord(5, 100, 200);
			assertFalse(tree.contains(5));
			assertEquals(0, tree.bucketCount());
		}

		@Test
		@DisplayName("does not demote mid-operation on a non-transactional remove (demotion is deferred to commit)")
		void shouldNotDemoteOnNonTransactionalRemove() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(5, 100, 200);
			tree.removeRecord(5, 200);
			assertEquals(1, tree.cardinalityOf(5));
			// the non-transactional (in-place) remove path keeps the bitmap; demotion to the primitive single form is
			// decided once at commit (see BitmapDemotionAtCommitTest), never mid-operation, so a bucket oscillating
			// across the 1/2 boundary within one transaction allocates its bitmap at most once
			assertFalse(
				tree.getRecordsEqualTo(5) instanceof SingleRecordBitmap,
				"A non-transactional remove keeps the multi bitmap; demotion is deferred to commit!"
			);
		}

		@Test
		@DisplayName("is a no-op when removing a non-existent value")
		void shouldNoOpOnRemoveOfMissingValue() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(5, 100);
			tree.removeRecord(9, 999);
			assertEquals(1, tree.bucketCount());
			assertTrue(tree.contains(5));
		}

		@Test
		@DisplayName("is a no-op when removing a record id absent from the bucket")
		void shouldNoOpOnRemoveOfMissingRecord() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(5, 100);
			tree.removeRecord(5, 999);
			assertEquals(1, tree.cardinalityOf(5));
			assertArrayEquals(new int[]{100}, recordsOf(tree, 5));
		}

		@Test
		@DisplayName("rejects an empty vararg remove")
		void shouldRejectEmptyVarargRemove() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(5, 100);
			assertThrows(IllegalArgumentException.class, () -> tree.removeRecord(5, new int[0]));
		}

		@Test
		@DisplayName("collapses tree structure as buckets are deleted")
		void shouldConsolidateOnDeletes() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			final AtomicReference<TreeSet<Integer>> keys = new AtomicReference<>(new TreeSet<>());
			assertStateAfterCommit(
				tree,
				tested -> {
					for (int i = 1; i <= 30; i++) {
						tested.addRecord(i, i * 10);
						keys.get().add(i);
					}
					for (int i = 2; i <= 30; i += 2) {
						tested.removeRecord(i, i * 10);
						keys.get().remove(i);
					}
				},
				(original, committed) -> {
					verifyTreeConsistency(committed, keys.get().stream().mapToInt(Integer::intValue).toArray());
				}
			);
		}
	}

	@Nested
	@DisplayName("Bitmap demotion at commit (multi drained to single reverts to the primitive form)")
	class BitmapDemotionAtCommitTest {

		@Test
		@DisplayName("demotes a multi bucket drained to a single record back to the primitive single form at commit")
		void shouldDemoteMultiBucketDrainedToSingleAtCommit() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(9, Integer.class);
			assertStateAfterCommit(
				tree,
				tested -> {
					tested.addRecord(5, 100, 200, 300);
					tested.removeRecord(5, 200, 300);
				},
				(original, committed) -> {
					assertEquals(1, committed.cardinalityOf(5));
					assertInstanceOf(
						SingleRecordBitmap.class, committed.getRecordsEqualTo(5),
						"A multi bucket drained to a single record must demote to the primitive single form at commit!"
					);
					assertArrayEquals(new int[]{100}, recordsOf(committed, 5));
				}
			);
		}

		@Test
		@DisplayName("demotes keeping the surviving record, not the first promoted one")
		void shouldDemoteKeepingTheSurvivingRecordNotTheFirstPromotedOne() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(9, Integer.class);
			assertStateAfterCommit(
				tree,
				tested -> {
					// promotion seeds the bitmap with records[i]=100 (the first id); records[i] is don't-care afterwards
					tested.addRecord(5, 100);
					tested.addRecord(5, 200);
					// remove the first id so the survivor is 200, distinct from the stale records[i]=100
					tested.removeRecord(5, 100);
				},
				(original, committed) -> {
					assertEquals(1, committed.cardinalityOf(5));
					assertInstanceOf(SingleRecordBitmap.class, committed.getRecordsEqualTo(5));
					// the surviving id must be read from the committed bitmap (getFirst()==200), never from records[i]
					assertArrayEquals(new int[]{200}, recordsOf(committed, 5));
				}
			);
		}

		@Test
		@DisplayName("demotes at most once even when the bucket oscillates across the 1/2 boundary within a transaction")
		void shouldNotOscillateWhenCrossingBoundaryRepeatedlyWithinTransaction() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(9, Integer.class);
			assertStateAfterCommit(
				tree,
				tested -> {
					tested.addRecord(5, 100);
					// cross the 1/2 boundary twice: promote, drain, promote, drain
					tested.addRecord(5, 200);
					tested.removeRecord(5, 200);
					tested.addRecord(5, 300);
					tested.removeRecord(5, 300);
				},
				(original, committed) -> {
					// the deferred-to-commit decision yields the final cardinality-1 state as a primitive single;
					// the sweep inside assertStateAfterCommit proves no bitmap layer was left stale
					assertEquals(1, committed.cardinalityOf(5));
					assertInstanceOf(SingleRecordBitmap.class, committed.getRecordsEqualTo(5));
					assertArrayEquals(new int[]{100}, recordsOf(committed, 5));
				}
			);
		}

		@Test
		@DisplayName("demotes several buckets and keeps a multi bucket co-located in the same leaf")
		void shouldDemoteMultipleBucketsAndKeepAMultiBucketInSameLeaf() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(9, Integer.class);
			assertStateAfterCommit(
				tree,
				tested -> {
					// three multi buckets co-located in one leaf (block size 8)
					tested.addRecord(1, 10, 20);
					tested.addRecord(2, 30, 40);
					tested.addRecord(3, 50, 60);
					// drain buckets 1 and 2 down to a single record each; leave bucket 3 multi
					tested.removeRecord(1, 20);
					tested.removeRecord(2, 30);
				},
				(original, committed) -> {
					// bucket 1: survivor 10, demoted
					assertInstanceOf(SingleRecordBitmap.class, committed.getRecordsEqualTo(1));
					assertArrayEquals(new int[]{10}, recordsOf(committed, 1));
					// bucket 2: survivor 40, demoted
					assertInstanceOf(SingleRecordBitmap.class, committed.getRecordsEqualTo(2));
					assertArrayEquals(new int[]{40}, recordsOf(committed, 2));
					// bucket 3: stays multi
					assertEquals(2, committed.cardinalityOf(3));
					assertFalse(committed.getRecordsEqualTo(3) instanceof SingleRecordBitmap);
					assertArrayEquals(new int[]{50, 60}, recordsOf(committed, 3));
				}
			);
		}

		@Test
		@DisplayName("keeps a multi bucket that is reduced but not drained to a single record")
		void shouldKeepMultiBucketWhenNotDrainedToSingle() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(9, Integer.class);
			assertStateAfterCommit(
				tree,
				tested -> {
					tested.addRecord(5, 100, 200, 300);
					tested.removeRecord(5, 200);
				},
				(original, committed) -> {
					assertEquals(2, committed.cardinalityOf(5));
					assertFalse(
						committed.getRecordsEqualTo(5) instanceof SingleRecordBitmap,
						"A multi bucket reduced but not drained to one must stay a multi bitmap!"
					);
					assertArrayEquals(new int[]{100, 300}, recordsOf(committed, 5));
				}
			);
		}

		@Test
		@DisplayName("demotes a leftover cardinality-1 multi bitmap on a leaf touched for another bucket")
		void shouldDemoteLeftoverSingleBitmapOnTouchedLeaf() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(9, Integer.class);
			// establish a cardinality-1 MULTI bitmap out-of-transaction: the non-transactional remove keeps the bitmap
			// (demotion is a commit-time concern), so bucket 1 holds a size-1 bitmap {10}, NOT a primitive single
			tree.addRecord(1, 10, 20);
			tree.removeRecord(1, 20);
			assertEquals(1, tree.cardinalityOf(1));
			assertFalse(
				tree.getRecordsEqualTo(1) instanceof SingleRecordBitmap,
				"Pre-condition: bucket 1 must be a size-1 bitmap, not yet demoted!"
			);
			// now touch the same leaf for a different bucket in a committing transaction: the bitmap of bucket 1 was not
			// mutated this transaction (no layer of its own), yet the leaf commit-merge must still demote it — proving
			// demotion of an unlayered bitmap neither leaks its (absent) layer nor is skipped
			assertStateAfterCommit(
				tree,
				tested -> tested.addRecord(2, 70),
				(original, committed) -> {
					assertInstanceOf(SingleRecordBitmap.class, committed.getRecordsEqualTo(1));
					assertArrayEquals(new int[]{10}, recordsOf(committed, 1));
					assertInstanceOf(SingleRecordBitmap.class, committed.getRecordsEqualTo(2));
					assertArrayEquals(new int[]{70}, recordsOf(committed, 2));
				}
			);
		}

		@Test
		@DisplayName("demotes when the surviving record is a negative primary key (setAt narrowing boundary)")
		void shouldDemoteWhenSurvivingRecordIsANegativePrimaryKey() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(9, Integer.class);
			assertStateAfterCommit(
				tree,
				tested -> {
					// promote with the boundary negative id plus a positive one, then leave the negative survivor
					tested.addRecord(5, Integer.MIN_VALUE, 100);
					tested.removeRecord(5, 100);
				},
				(original, committed) -> {
					assertInstanceOf(SingleRecordBitmap.class, committed.getRecordsEqualTo(5));
					// setAt narrows the survivor sign-preservingly; the demoted id is read from the committed bitmap
					assertArrayEquals(new int[]{Integer.MIN_VALUE}, recordsOf(committed, 5));
				}
			);
			// a realistic externally-assigned negative pk as the survivor
			final TransactionalBucketBPlusTree<Integer> tree2 = new TransactionalBucketBPlusTree<>(9, Integer.class);
			assertStateAfterCommit(
				tree2,
				tested -> {
					tested.addRecord(7, -1, 200);
					tested.removeRecord(7, 200);
				},
				(original, committed) -> {
					assertInstanceOf(SingleRecordBitmap.class, committed.getRecordsEqualTo(7));
					assertArrayEquals(new int[]{-1}, recordsOf(committed, 7));
				}
			);
		}

		@Test
		@DisplayName("demotes a drained bucket on a leaf that also splits in the same transaction")
		void shouldDemoteWhenLeafSplitsInSameTransaction() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			// base: a full leaf (3 buckets) carrying a multi bucket; the transaction both drains it and splits the leaf
			tree.addRecord(1, 10, 11);
			tree.addRecord(2, 20);
			tree.addRecord(3, 30);
			assertStateAfterCommit(
				tree,
				tested -> {
					// drain the multi bucket to a size-1 bitmap, then insert a 4th bucket to split the full leaf
					tested.removeRecord(1, 11);
					tested.addRecord(4, 40);
				},
				(original, committed) -> {
					// the drained bucket demotes to the primitive single form on the split-born leaf
					assertInstanceOf(SingleRecordBitmap.class, committed.getRecordsEqualTo(1));
					assertArrayEquals(new int[]{10}, recordsOf(committed, 1));
					verifyTreeConsistency(committed, 1, 2, 3, 4);
				}
			);
		}
	}

	@Nested
	@DisplayName("Rebalancing (steal and merge)")
	class RebalancingTest {

		@Test
		@DisplayName("steals from the left sibling and stays consistent")
		void shouldStealFromLeft() {
			final TreeTuple tuple = prepareRandomTree(42L, 25);
			final TransactionalBucketBPlusTree<Integer> tree = tuple.tree();
			assertStateAfterCommit(
				tree,
				tested -> tested.removeRecord(
					tuple.keys()[tuple.keys().length - 1],
					tuple.keys()[tuple.keys().length - 1] * 10
				),
				(original, committed) -> {
					final int[] expected = new int[tuple.keys().length - 1];
					System.arraycopy(tuple.keys(), 0, expected, 0, expected.length);
					verifyTreeConsistency(committed, expected);
				}
			);
		}

		@Test
		@DisplayName("steals from the right sibling and stays consistent")
		void shouldStealFromRight() {
			final TreeTuple tuple = prepareRandomTree(7L, 25);
			final TransactionalBucketBPlusTree<Integer> tree = tuple.tree();
			assertStateAfterCommit(
				tree,
				tested -> tested.removeRecord(tuple.keys()[0], tuple.keys()[0] * 10),
				(original, committed) -> {
					final int[] expected = new int[tuple.keys().length - 1];
					System.arraycopy(tuple.keys(), 1, expected, 0, expected.length);
					verifyTreeConsistency(committed, expected);
				}
			);
		}

		@Test
		@DisplayName("merges sibling leaves and stays consistent")
		void shouldMergeLeaves() {
			final TreeTuple tuple = prepareRandomTree(99L, 24);
			final TransactionalBucketBPlusTree<Integer> tree = tuple.tree();
			final TreeSet<Integer> remaining = new TreeSet<>();
			for (final int key : tuple.keys()) {
				remaining.add(key);
			}
			assertStateAfterCommit(
				tree,
				tested -> {
					// remove enough buckets to force merges across the tree
					for (int i = 0; i < tuple.keys().length; i += 3) {
						tested.removeRecord(tuple.keys()[i], tuple.keys()[i] * 10);
						remaining.remove(tuple.keys()[i]);
					}
				},
				(original, committed) ->
					verifyTreeConsistency(committed, remaining.stream().mapToInt(Integer::intValue).toArray())
			);
		}

		@Test
		@DisplayName("moves multi buckets across leaves during rebalancing")
		void shouldRebalanceMultiBuckets() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			final TreeSet<Integer> remaining = new TreeSet<>();
			assertStateAfterCommit(
				tree,
				tested -> {
					for (int i = 1; i <= 20; i++) {
						// every third bucket is multi-record
						if (i % 3 == 0) {
							tested.addRecord(i, i * 10, i * 10 + 1);
						} else {
							tested.addRecord(i, i * 10);
						}
						remaining.add(i);
					}
					// delete a swathe to force steals and merges with multi buckets present
					for (int i = 4; i <= 16; i++) {
						if (i % 3 != 0) {
							tested.removeRecord(i, i * 10);
							remaining.remove(i);
						}
					}
				},
				(original, committed) -> {
					verifyTreeConsistency(committed, remaining.stream().mapToInt(Integer::intValue).toArray());
					// the surviving multi buckets must keep both ids
					assertArrayEquals(new int[]{60, 61}, recordsOf(committed, 6));
					assertArrayEquals(new int[]{90, 91}, recordsOf(committed, 9));
				}
			);
		}
	}

	@Nested
	@DisplayName("Transactional rebalancing (steal and merge of multi buckets)")
	class TransactionalRebalancingTest {

		@Test
		@DisplayName("steals a multi bucket across leaves inside a txn, isolating the pre-commit snapshot")
		void shouldStealMultiBucketInTransaction() {
			// base layout (fully committed): every third bucket is multi
			final TreeTuple tuple = prepareRandomMultiTree(42L, 25, 3);
			final TransactionalBucketBPlusTree<Integer> tree = tuple.tree();
			final int firstKey = tuple.keys()[0];
			assertStateAfterCommit(
				tree,
				// removing the head bucket forces the now-underflowing leaf to steal from its right sibling
				tested -> tested.removeRecord(
					firstKey, firstKey % 3 == 0
						? new int[]{firstKey * 10, firstKey * 10 + 1} : new int[]{firstKey * 10}
				),
				(original, committed) -> {
					// pre-commit snapshot still has the original key and original multi members
					assertTrue(original.contains(firstKey));
					assertArrayEquals(new int[]{30, 31}, recordsOf(original, 3));
					// committed view dropped the head and kept multi buckets intact after the steal moved them
					assertFalse(committed.contains(firstKey));
					assertArrayEquals(new int[]{30, 31}, recordsOf(committed, 3));
					assertArrayEquals(new int[]{60, 61}, recordsOf(committed, 6));
					final int[] expected = new int[tuple.keys().length - 1];
					System.arraycopy(tuple.keys(), 1, expected, 0, expected.length);
					verifyTreeConsistency(committed, expected);
					verifyTreeConsistency(original, tuple.keys());
				}
			);
		}

		@Test
		@DisplayName("merges leaves carrying multi buckets inside a txn without leaving a stale layer")
		void shouldMergeLeavesWithMultiBucketsInTransaction() {
			final TreeTuple tuple = prepareRandomMultiTree(99L, 24, 3);
			final TransactionalBucketBPlusTree<Integer> tree = tuple.tree();
			final TreeSet<Integer> remaining = new TreeSet<>();
			for (final int key : tuple.keys()) {
				remaining.add(key);
			}
			// this MUST NOT throw StaleTransactionMemoryException on commit - merged multi bitmaps stay referenced
			assertStateAfterCommit(
				tree,
				tested -> {
					// remove every other single bucket to drive leaves under the minimum and force merges
					for (int i = 0; i < tuple.keys().length; i++) {
						final int key = tuple.keys()[i];
						if (key % 3 != 0 && key % 2 == 0) {
							tested.removeRecord(key, key * 10);
							remaining.remove(key);
						}
					}
				},
				(original, committed) -> {
					verifyTreeConsistency(committed, remaining.stream().mapToInt(Integer::intValue).toArray());
					// surviving multi buckets retain both ids after the merges relocated them
					assertArrayEquals(new int[]{30, 31}, recordsOf(committed, 3));
					assertArrayEquals(new int[]{60, 61}, recordsOf(committed, 6));
					assertArrayEquals(new int[]{90, 91}, recordsOf(committed, 9));
				}
			);
		}

		@Test
		@DisplayName("a leaf without an overflow column steals a multi bucket from a sibling that has one")
		void shouldEnsureOverflowWhenStealingMultiFromSibling() {
			// hand-build a layout where a single-only leaf is adjacent to a leaf owning a multi bucket; deleting from
			// the single-only leaf makes it underflow and steal the multi bucket - exercising ensureOverflowForSteal
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(1, 10);
			tree.addRecord(2, 20);
			tree.addRecord(3, 30);
			tree.addRecord(4, 40, 41); // multi bucket lands in a later leaf
			tree.addRecord(5, 50);
			tree.addRecord(6, 60);
			assertStateAfterCommit(
				tree,
				tested -> {
					// drain the leftmost leaf so it must pull a value (eventually the multi bucket) across the tree
					tested.removeRecord(1, 10);
					tested.removeRecord(2, 20);
				},
				(original, committed) -> {
					assertFalse(committed.contains(1));
					assertFalse(committed.contains(2));
					// the multi bucket survives the rebalancing with both ids intact
					assertArrayEquals(new int[]{40, 41}, recordsOf(committed, 4));
					verifyTreeConsistency(committed, 3, 4, 5, 6);
				}
			);
		}

		@Test
		@DisplayName("splits a leaf containing multi buckets inside a txn and commits correctly")
		void shouldSplitLeafWithMultiBucketsInTransaction() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			final TreeSet<Integer> keys = new TreeSet<>();
			assertStateAfterCommit(
				tree,
				tested -> {
					// inserting multi buckets into a small-block tree forces splitLeafNode to carry the overflow column
					for (int i = 1; i <= 12; i++) {
						if (i % 2 == 0) {
							tested.addRecord(i, i * 10, i * 10 + 1);
						} else {
							tested.addRecord(i, i * 10);
						}
						keys.add(i);
					}
				},
				(original, committed) -> {
					verifyTreeConsistency(committed, keys.stream().mapToInt(Integer::intValue).toArray());
					// every even bucket is a multi bucket and kept both ids across the splits
					assertArrayEquals(new int[]{40, 41}, recordsOf(committed, 4));
					assertArrayEquals(new int[]{80, 81}, recordsOf(committed, 8));
					assertArrayEquals(new int[]{120, 121}, recordsOf(committed, 12));
				}
			);
		}

		@Test
		@DisplayName("relocates a multi bucket whose bitmap was mutated earlier in the same txn")
		void shouldRelocateMultiBucketMutatedEarlierInTransaction() {
			// base layout: a multi bucket whose bitmap layer will be OPENED (mutated) before the move forces it across
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(1, 10);
			tree.addRecord(2, 20);
			tree.addRecord(3, 30, 31);
			tree.addRecord(4, 40);
			tree.addRecord(5, 50);
			tree.addRecord(6, 60);
			assertStateAfterCommit(
				tree,
				tested -> {
					// open the multi bucket's diff layer first by mutating its bitmap...
					tested.addRecord(3, 32);
					// ...then drain a neighbouring leaf so a steal/merge relocates that same bucket
					tested.removeRecord(5, 50);
					tested.removeRecord(6, 60);
				},
				(original, committed) -> {
					// pre-commit snapshot keeps the original bitmap contents
					assertArrayEquals(new int[]{30, 31}, recordsOf(original, 3));
					// committed view sees the mutated-and-relocated multi bucket with all three ids
					assertArrayEquals(new int[]{30, 31, 32}, recordsOf(committed, 3));
					assertFalse(committed.contains(5));
					assertFalse(committed.contains(6));
					verifyTreeConsistency(committed, 1, 2, 3, 4);
				}
			);
		}
	}

	@Nested
	@DisplayName("Cursor iteration")
	class CursorIterationTest {

		@Test
		@DisplayName("forward cursor yields buckets ascending with correct representation")
		void shouldIterateForward() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(3, 30);
			tree.addRecord(1, 10, 11);
			tree.addRecord(2, 20);
			final BucketCursor<Integer> cursor = tree.cursor();
			assertTrue(cursor.next());
			assertEquals(1, cursor.value());
			assertFalse(cursor.isSingle());
			assertEquals(2, cursor.size());
			assertArrayEquals(new int[]{10, 11}, cursor.records().getArray());
			assertTrue(cursor.next());
			assertEquals(2, cursor.value());
			assertTrue(cursor.isSingle());
			assertEquals(20, cursor.singleRecordId());
			assertEquals(1, cursor.size());
			assertInstanceOf(SingleRecordBitmap.class, cursor.records());
			assertTrue(cursor.next());
			assertEquals(3, cursor.value());
			assertTrue(cursor.isSingle());
			assertEquals(30, cursor.singleRecordId());
			assertFalse(cursor.next());
		}

		@Test
		@DisplayName("reverse cursor yields buckets descending")
		void shouldIterateReverse() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			for (int i = 1; i <= 10; i++) {
				tree.addRecord(i, i * 10);
			}
			final List<Integer> seen = new ArrayList<>();
			final BucketCursor<Integer> cursor = tree.reverseCursor();
			while (cursor.next()) {
				seen.add(cursor.value());
			}
			assertEquals(List.of(10, 9, 8, 7, 6, 5, 4, 3, 2, 1), seen);
		}

		@Test
		@DisplayName("from-key cursor starts at the first bucket >= the key")
		void shouldIterateFromKey() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			for (int i = 0; i < 20; i += 2) {
				tree.addRecord(i, i * 10);
			}
			final List<Integer> seen = new ArrayList<>();
			// 7 is absent - the cursor must start at 8
			final BucketCursor<Integer> cursor = tree.cursor(7);
			while (cursor.next()) {
				seen.add(cursor.value());
			}
			assertEquals(List.of(8, 10, 12, 14, 16, 18), seen);
		}

		@Test
		@DisplayName("from-key cursor on a present key starts at that key")
		void shouldIterateFromPresentKey() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			for (int i = 1; i <= 10; i++) {
				tree.addRecord(i, i * 10);
			}
			final List<Integer> seen = new ArrayList<>();
			final BucketCursor<Integer> cursor = tree.cursor(8);
			while (cursor.next()) {
				seen.add(cursor.value());
			}
			assertEquals(List.of(8, 9, 10), seen);
		}

		@Test
		@DisplayName("cursors over an empty tree yield nothing")
		void shouldIterateEmptyTree() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			assertFalse(tree.cursor().next());
			assertFalse(tree.reverseCursor().next());
		}
	}

	@Nested
	@DisplayName("Negative primary keys")
	class NegativePrimaryKeyTest {

		@Test
		@DisplayName("stores Integer.MIN_VALUE and -1 as single records")
		void shouldStoreNegativeSingleRecords() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(1, Integer.MIN_VALUE);
			tree.addRecord(2, -1);
			assertInstanceOf(SingleRecordBitmap.class, tree.getRecordsEqualTo(1));
			assertEquals(Integer.MIN_VALUE, tree.getRecordsEqualTo(1).getFirst());
			assertArrayEquals(new int[]{-1}, recordsOf(tree, 2));
			assertEquals(1, tree.cardinalityOf(1));
		}

		@Test
		@DisplayName("promotes a bucket holding a negative single to a multi with another negative")
		void shouldPromoteWithNegativeRecords() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(1, Integer.MIN_VALUE);
			tree.addRecord(1, -1);
			assertEquals(2, tree.cardinalityOf(1));
			assertArrayEquals(new int[]{Integer.MIN_VALUE, -1}, recordsOf(tree, 1));
			assertTrue(tree.getRecordsEqualTo(1).contains(Integer.MIN_VALUE));
			assertTrue(tree.getRecordsEqualTo(1).contains(-1));
		}

		@Test
		@DisplayName("creates and removes a multi bucket with negative members")
		void shouldHandleNegativeMultiMembers() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(5, Integer.MIN_VALUE, -1, 0, 1);
			assertEquals(4, tree.cardinalityOf(5));
			tree.removeRecord(5, Integer.MIN_VALUE);
			assertEquals(3, tree.cardinalityOf(5));
			assertFalse(tree.getRecordsEqualTo(5).contains(Integer.MIN_VALUE));
			tree.removeRecord(5, -1, 0, 1);
			assertFalse(tree.contains(5));
		}

		@Test
		@DisplayName("deletes a single bucket holding a negative pk")
		void shouldDeleteNegativeSingleBucket() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(1, -1);
			tree.addRecord(2, 20);
			tree.removeRecord(1, -1);
			assertFalse(tree.contains(1));
			assertEquals(1, tree.bucketCount());
		}
	}

	@Nested
	@DisplayName("MVCC and transactions")
	class TransactionTest {

		@Test
		@DisplayName("isolates a pre-commit reader snapshot from writer mutations")
		void shouldIsolateReaderSnapshot() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(1, 10);
			tree.addRecord(2, 20);
			assertStateAfterCommit(
				tree,
				tested -> {
					tested.addRecord(3, 30);
					tested.removeRecord(1, 10);
					// inside the txn the writer sees the in-progress state
					assertTrue(tested.contains(3));
					assertFalse(tested.contains(1));
				},
				(original, committed) -> {
					// the original (pre-commit) snapshot is untouched
					assertFalse(original.contains(3));
					assertTrue(original.contains(1));
					// the committed view reflects the writes
					assertTrue(committed.contains(3));
					assertFalse(committed.contains(1));
					verifyTreeConsistency(committed, 2, 3);
				}
			);
		}

		@Test
		@DisplayName("rolls back all changes, leaving the original untouched")
		void shouldRollback() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(1, 10);
			tree.addRecord(2, 20);
			assertStateAfterRollback(
				tree,
				tested -> {
					tested.addRecord(3, 30);
					tested.addRecord(1, 11);
					tested.removeRecord(2, 20);
				},
				(original, committed) -> {
					assertFalse(original.contains(3));
					assertEquals(1, original.cardinalityOf(1));
					assertTrue(original.contains(2));
					verifyTreeConsistency(original, 1, 2);
				}
			);
		}

		@Test
		@DisplayName("deep-commits a mutated overflow bitmap")
		void shouldDeepCommitMutatedOverflow() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(5, 100, 200);
			assertStateAfterCommit(
				tree,
				// mutate the existing multi bucket's bitmap inside the txn
				tested -> tested.addRecord(5, 300),
				(original, committed) -> {
					// the pre-commit snapshot still sees the old bitmap
					assertEquals(2, original.cardinalityOf(5));
					assertArrayEquals(new int[]{100, 200}, recordsOf(original, 5));
					// the committed view sees the merged bitmap
					assertEquals(3, committed.cardinalityOf(5));
					assertArrayEquals(new int[]{100, 200, 300}, recordsOf(committed, 5));
				}
			);
		}

		@Test
		@DisplayName("promotes a single bucket to multi within a transaction and commits it")
		void shouldCommitPromotionInTransaction() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(5, 100);
			assertStateAfterCommit(
				tree,
				tested -> tested.addRecord(5, 200),
				(original, committed) -> {
					assertEquals(1, original.cardinalityOf(5));
					assertInstanceOf(SingleRecordBitmap.class, original.getRecordsEqualTo(5));
					assertEquals(2, committed.cardinalityOf(5));
					assertArrayEquals(new int[]{100, 200}, recordsOf(committed, 5));
				}
			);
		}

		@Test
		@DisplayName("deletes a multi bucket inside a txn without leaving a stale layer")
		void shouldDeleteMultiBucketThenCommit() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(1, 10);
			tree.addRecord(5, 100, 200);
			tree.addRecord(9, 90);
			// this MUST NOT throw StaleTransactionMemoryException during the commit sweep - the discarded multi
			// bucket's bitmap layer must be released via discardRemovedValueLayer
			assertStateAfterCommit(
				tree,
				tested -> {
					// mutate the bitmap first (opens its diff layer), then delete the whole bucket
					tested.addRecord(5, 300);
					tested.removeRecord(5, 100, 200, 300);
				},
				(original, committed) -> {
					assertTrue(original.contains(5));
					assertFalse(committed.contains(5));
					verifyTreeConsistency(committed, 1, 9);
				}
			);
		}

		@Test
		@DisplayName("deletes a freshly-created multi bucket inside the same txn and commits")
		void shouldDeleteFreshMultiBucketThenCommit() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(1, 10);
			tree.addRecord(9, 90);
			assertStateAfterCommit(
				tree,
				tested -> {
					// create a brand-new multi bucket and immediately drain it within the same txn
					tested.addRecord(5, 100, 200);
					tested.removeRecord(5, 100, 200);
				},
				(original, committed) -> {
					assertFalse(committed.contains(5));
					verifyTreeConsistency(committed, 1, 9);
				}
			);
		}
	}

	@Nested
	@DisplayName("STM invariants")
	class StmInvariantsTest {

		/**
		 * Returns a sorted key array equal to `keys` with `removed` dropped and `added` inserted.
		 *
		 * @param keys    the original sorted key array
		 * @param removed the key removed from the tree
		 * @param added   the key added to the tree
		 * @return the expected sorted key array after the mutation
		 */
		@Nonnull
		private static int[] expectedKeysAfter(@Nonnull int[] keys, int removed, int added) {
			final TreeSet<Integer> expected = new TreeSet<>();
			for (final int key : keys) {
				expected.add(key);
			}
			expected.remove(removed);
			expected.add(added);
			return expected.stream().mapToInt(Integer::intValue).toArray();
		}

		@Test
		@DisplayName("returns a stable and unique id across instances")
		void shouldReturnStableAndUniqueId() {
			final TransactionalBucketBPlusTree<Integer> tree1 = new TransactionalBucketBPlusTree<>(3, Integer.class);
			final TransactionalBucketBPlusTree<Integer> tree2 = new TransactionalBucketBPlusTree<>(3, Integer.class);

			final long id1 = tree1.getId();
			final long id2 = tree2.getId();

			// the id is stable on repeated calls
			assertEquals(id1, tree1.getId());
			assertEquals(id2, tree2.getId());
			// the ids are unique across instances
			assertNotEquals(id1, id2);
		}

		@Test
		@DisplayName("commits a leaf-only-root tree through the leaf branch")
		void shouldCommitTreeWithSingleLeafRoot() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			// a single bucket keeps the root a leaf
			assertStateAfterCommit(
				tree,
				tested -> tested.addRecord(42, 420),
				(original, committed) -> {
					assertEquals(0, original.bucketCount());
					assertEquals(1, committed.bucketCount());
					assertArrayEquals(new int[]{420}, recordsOf(committed, 42));
					verifyTreeConsistency(committed, 42);
				}
			);
		}

		@Test
		@DisplayName("yields a different instance with a distinct id after a committing mutation")
		void shouldReturnDifferentInstanceAfterCommit() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			assertStateAfterCommit(
				tree,
				tested -> tested.addRecord(1, 10),
				(original, committed) -> {
					assertNotSame(original, committed);
					assertNotEquals(original.getId(), committed.getId());
				}
			);
		}

		@Test
		@DisplayName("produces a structurally equivalent copy on a no-op commit")
		void shouldProduceEquivalentCopyOnNoOpCommit() {
			final TreeTuple tuple = prepareRandomMultiTree(42L, 30, 3);
			assertStateAfterCommit(
				tuple.tree(),
				tested -> {
					// no mutations at all
				},
				(original, committed) -> {
					assertNotSame(original, committed);
					assertEquals(original.bucketCount(), committed.bucketCount());
					verifyTreeConsistency(original, tuple.keys());
					verifyTreeConsistency(committed, tuple.keys());
					// a sampled multi bucket survives the no-op commit unchanged
					assertArrayEquals(recordsOf(original, 3), recordsOf(committed, 3));
				}
			);
		}

		@Test
		@DisplayName("leaves no dangling layers and stays mutable in a second commit")
		void shouldNotHaveDanglingLayersAfterCommit() {
			final TreeTuple tuple = prepareRandomMultiTree(42L, 30, 3);
			assertStateAfterCommit(
				tuple.tree(),
				tested -> {
					tested.addRecord(5555, 55_550, 55_551);
					tested.removeRecord(0, 0, 1);
				},
				(original, committed) -> {
					verifyTreeConsistency(committed, expectedKeysAfter(tuple.keys(), 0, 5555));
					assertArrayEquals(new int[]{55_550, 55_551}, recordsOf(committed, 5555));
					// the committed tree must be independently usable in a fresh transaction
					assertStateAfterCommit(
						committed,
						tested2 -> tested2.addRecord(6666, 66_660),
						(original2, committed2) -> {
							verifyTreeConsistency(original2, expectedKeysAfter(tuple.keys(), 0, 5555));
							assertArrayEquals(new int[]{66_660}, recordsOf(committed2, 6666));
						}
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("Comparator ordering")
	class ComparatorTest {

		@Test
		@DisplayName("orders buckets by a custom comparator")
		void shouldOrderByComparator() {
			// reverse natural order
			final Comparator<Integer> reverse = Comparator.reverseOrder();
			final TransactionalBucketBPlusTree<Integer> tree =
				new TransactionalBucketBPlusTree<>(3, Integer.class, reverse);
			for (int i = 1; i <= 10; i++) {
				tree.addRecord(i, i * 10);
			}
			final List<Integer> seen = new ArrayList<>();
			final BucketCursor<Integer> cursor = tree.cursor();
			while (cursor.next()) {
				seen.add(cursor.value());
			}
			assertEquals(List.of(10, 9, 8, 7, 6, 5, 4, 3, 2, 1), seen);
			assertEquals(ConsistencyState.CONSISTENT, tree.getConsistencyReport().state());
		}
	}

	@Nested
	@DisplayName("Constructor validation")
	class ConstructorValidationTest {

		@Test
		@DisplayName("rejects a block size below 3")
		void shouldRejectTooSmallBlockSize() {
			assertThrows(Exception.class, () -> new TransactionalBucketBPlusTree<>(2, Integer.class));
		}

		@Test
		@DisplayName("accepts the default-block-size constructor")
		void shouldAcceptDefaultConstructor() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(Integer.class);
			tree.addRecord(1, 10);
			assertEquals(1, tree.bucketCount());
		}

		@Test
		@DisplayName("rejects an even internal node block size")
		void shouldRejectEvenInternalNodeBlockSize() {
			// internalNodeBlockSize must be odd
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new TransactionalBucketBPlusTree<>(4, 1, 4, 1, Integer.class, null)
			);
		}

		@Test
		@DisplayName("rejects an internal node block size larger than the value block size")
		void shouldRejectInternalNodeBlockSizeLargerThanValueBlockSize() {
			// internalNodeBlockSize must not exceed valueBlockSize
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new TransactionalBucketBPlusTree<>(3, 1, 5, 1, Integer.class, null)
			);
		}

		@Test
		@DisplayName("rejects a minimum block size below one")
		void shouldRejectMinValueBlockSizeBelowOne() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new TransactionalBucketBPlusTree<>(3, 0, 3, 1, Integer.class, null)
			);
		}

		@Test
		@DisplayName("rejects a minimum block size above half of the block size")
		void shouldRejectMinValueBlockSizeTooLarge() {
			// valueBlockSize=3, ceil(3/2)-1 = 1, so minValueBlockSize=2 is invalid
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new TransactionalBucketBPlusTree<>(3, 2, 3, 1, Integer.class, null)
			);
		}

		@Test
		@DisplayName("rejects a key type implementing TransactionalLayerProducer")
		void shouldRejectKeyTypeImplementingTransactionalLayerProducer() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new TransactionalBucketBPlusTree<>(3, TransactionalComparableKey.class)
			);
		}

		@Test
		@DisplayName("exposes the configured block sizes through its getters")
		void shouldExposeConfiguredBlockSizes() {
			final TransactionalBucketBPlusTree<Integer> tree =
				new TransactionalBucketBPlusTree<>(7, 2, 7, 2, Integer.class, null);
			assertEquals(7, tree.getValueBlockSize());
			assertEquals(2, tree.getMinValueBlockSize());
			assertEquals(7, tree.getInternalNodeBlockSize());
			assertEquals(2, tree.getMinInternalNodeBlockSize());
			assertSame(Integer.class, tree.getKeyType());
			assertEquals(0, tree.bucketCount());
		}
	}

	@Nested
	@DisplayName("Randomized churn")
	class ChurnTest {

		/**
		 * Replays a recorded random op sequence (single add, vararg multi-add, single remove, bulk remove) inside a
		 * single committing transaction and asserts the committed tree is consistent and content-equal to a
		 * {@link TreeMap}+set oracle built from the same sequence.
		 *
		 * @param seed      the random seed
		 * @param blockSize the leaf/internal block size of the tree under test
		 */
		private static void runTransactionalChurn(long seed, int blockSize) {
			final TransactionalBucketBPlusTree<Integer> tree =
				new TransactionalBucketBPlusTree<>(blockSize, Integer.class);
			final TreeMap<Integer, TreeSet<Integer>> oracle = new TreeMap<>();
			final Random random = new Random(seed);
			assertStateAfterCommit(
				tree,
				tested -> {
					for (int op = 0; op < 200; op++) {
						final int value = random.nextInt(30);
						final int roll = random.nextInt(4);
						if (roll == 0) {
							// single add
							final int pk = random.nextInt(200) - 50;
							tested.addRecord(value, pk);
							oracle.computeIfAbsent(value, k -> new TreeSet<>()).add(pk);
						} else if (roll == 1) {
							// vararg multi add
							final int[] pks = {
								random.nextInt(200) - 50, random.nextInt(200) - 50, random.nextInt(200) - 50
							};
							tested.addRecord(value, pks);
							final TreeSet<Integer> set = oracle.computeIfAbsent(value, k -> new TreeSet<>());
							for (final int pk : pks) {
								set.add(pk);
							}
						} else if (roll == 2) {
							// single remove
							final int pk = random.nextInt(200) - 50;
							tested.removeRecord(value, pk);
							removeFromOracle(oracle, value, pk);
						} else {
							// bulk remove
							final int[] pks = {random.nextInt(200) - 50, random.nextInt(200) - 50};
							tested.removeRecord(value, pks);
							for (final int pk : pks) {
								removeFromOracle(oracle, value, pk);
							}
						}
					}
				},
				(original, committed) -> assertMatchesOracle(committed, oracle, seed, blockSize)
			);
		}

		/**
		 * Removes a single record from the oracle, dropping the value entry when its set drains empty.
		 *
		 * @param oracle the reference map
		 * @param value  the value whose record is removed
		 * @param pk     the record id to remove
		 */
		private static void removeFromOracle(
			@Nonnull TreeMap<Integer, TreeSet<Integer>> oracle, int value, int pk) {
			final TreeSet<Integer> set = oracle.get(value);
			if (set != null) {
				set.remove(pk);
				if (set.isEmpty()) {
					oracle.remove(value);
				}
			}
		}

		/**
		 * Asserts the tree is consistent and content-equal to the oracle.
		 *
		 * @param tree      the tree under test
		 * @param oracle    the reference map
		 * @param seed      the random seed (for failure messages)
		 * @param blockSize the block size (for failure messages)
		 */
		private static void assertMatchesOracle(
			@Nonnull TransactionalBucketBPlusTree<Integer> tree,
			@Nonnull TreeMap<Integer, TreeSet<Integer>> oracle,
			long seed, int blockSize
		) {
			final String tag = "seed=" + seed + " blockSize=" + blockSize;
			final ConsistencyReport report = tree.getConsistencyReport();
			assertEquals(ConsistencyState.CONSISTENT, report.state(), tag + ": " + report.report());
			assertEquals(oracle.size(), tree.bucketCount(), tag + " bucket count mismatch");
			for (final var entry : oracle.entrySet()) {
				final int[] expected = entry.getValue().stream().mapToInt(Integer::intValue).toArray();
				assertArrayEquals(
					expected, recordsOf(tree, entry.getKey()),
					tag + " records mismatch for value " + entry.getKey()
				);
			}
		}

		/**
		 * Runs a bounded randomized add/remove churn against a {@link TreeMap}+set oracle, asserting the tree stays
		 * consistent and content-equal throughout.
		 *
		 * @param seed the random seed
		 */
		private static void runChurn(long seed) {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			final TreeMap<Integer, TreeSet<Integer>> oracle = new TreeMap<>();
			final Random random = new Random(seed);
			for (int op = 0; op < 400; op++) {
				final int value = random.nextInt(40);
				final int pk = random.nextInt(200) - 50; // includes negatives
				if (random.nextBoolean()) {
					tree.addRecord(value, pk);
					oracle.computeIfAbsent(value, k -> new TreeSet<>()).add(pk);
				} else {
					tree.removeRecord(value, pk);
					final TreeSet<Integer> set = oracle.get(value);
					if (set != null) {
						set.remove(pk);
						if (set.isEmpty()) {
							oracle.remove(value);
						}
					}
				}
			}
			final ConsistencyReport report = tree.getConsistencyReport();
			assertEquals(ConsistencyState.CONSISTENT, report.state(), "seed=" + seed + ": " + report.report());
			assertEquals(oracle.size(), tree.bucketCount(), "seed=" + seed + " bucket count mismatch");
			for (final var entry : oracle.entrySet()) {
				final int[] expected = entry.getValue().stream().mapToInt(Integer::intValue).toArray();
				assertArrayEquals(
					expected, recordsOf(tree, entry.getKey()),
					"seed=" + seed + " records mismatch for value " + entry.getKey()
				);
			}
		}

		@Test
		@DisplayName("matches an oracle map across bounded randomized add/remove churn")
		void shouldMatchOracle() {
			for (long seed = 0; seed < 20; seed++) {
				runChurn(seed);
			}
		}

		@Test
		@DisplayName("matches an oracle map when a recorded op sequence is replayed inside one transaction")
		void shouldMatchOracleInTransaction() {
			// sweep a few block sizes so steal/merge/split thresholds differ between runs
			for (final int blockSize : new int[]{3, 5, 7}) {
				for (long seed = 0; seed < 4; seed++) {
					runTransactionalChurn(seed, blockSize);
				}
			}
		}
	}

	@Nested
	@DisplayName("Merge overflow-aliasing regression")
	class MergeOverflowAliasingRegressionTest {

		@Test
		@DisplayName("never aliases one overflow bitmap across two buckets when a multi-bucket leaf merges a single-only sibling")
		void shouldNotAliasOverflowBitmapWhenMergingSingleOnlySibling() {
			// Regression for the transactional leaf-merge aliasing bug. When an underflowing leaf merges a sibling it
			// first shifts its own buckets aside with a plain arraycopy (a copy, not a move) and then pulls the donor
			// sibling's overflow column over the vacated range. When the donor carried NO overflow column (all single
			// buckets) that pull was skipped, so the survivor's own moved multi-bucket bitmap stayed ALIASED at two
			// slots. The single TransactionalBitmap was then committed - and discarded - twice during the layer sweep,
			// failing with "Item has been already discarded!" (or, when it carried no layer, silently giving two values
			// the same record set). Surfacing it needs minValueBlockSize >= 2 so an underflowing leaf can still hold a
			// multi bucket - the pre-existing churn used the 2-arg constructor whose derived minimum is 1 for the swept
			// block sizes, so an underflowing leaf was always empty and the multi-bucket-survivor merge never occurred.
			final int[][] configs = {{5, 2}, {7, 2}, {7, 3}, {9, 3}, {9, 4}};
			for (final int[] config : configs) {
				// vary which key becomes the lone multi bucket (front / middle / back leaf) and the collapse direction
				// so both mergeWithLeft / stealFromLeft and mergeWithRight / stealFromRight are exercised
				for (final int multiKey : new int[]{3, 30, 56}) {
					runMultiSurvivorMergeChurn(config[0], config[1], multiKey, true);
					runMultiSurvivorMergeChurn(config[0], config[1], multiKey, false);
				}
			}
		}

		/**
		 * Deterministically reproduces the leaf-merge overflow-aliasing bug. Builds an all-single-bucket tree (so NO
		 * leaf has an overflow column yet), then inside one transaction promotes exactly one key to a multi bucket -
		 * allocating an overflow column on that one leaf only - and collapses the rest of the tree so that leaf keeps
		 * underflowing and rebalancing (steal / merge) against neighbours that still carry a null overflow column. That
		 * null-donor rebalance is the path that left the survivor's moved bitmap aliased at two slots. The multi bucket
		 * is grown on every step so its bitmap holds a transactional layer (the "already discarded" crash arm); a
		 * silent alias is caught instead by the per-key record comparison against the oracle.
		 *
		 * @param valueBlockSize  the leaf block size
		 * @param minBlockSize    the minimum leaf block size (`>= 2`, so an underflowing leaf can still hold a bucket)
		 * @param multiKey        the single key that is promoted to a multi bucket
		 * @param deleteAscending whether the surrounding buckets are deleted low-to-high or high-to-low
		 */
		private static void runMultiSurvivorMergeChurn(
			int valueBlockSize, int minBlockSize, int multiKey, boolean deleteAscending
		) {
			// internal-node block size is a fixed odd value (its own constraint, must not exceed the leaf block size);
			// only the leaf block size + minimum vary, since the bug lives entirely in the leaf overflow column
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(
				valueBlockSize, minBlockSize, 3, 1, Integer.class, null
			);
			final int n = 60;
			final TreeMap<Integer, TreeSet<Integer>> oracle = new TreeMap<>();
			// build phase (non-transactional): every bucket is single, so NO leaf allocates an overflow column
			for (int k = 0; k < n; k++) {
				tree.addRecord(k, 1000 + k);
				oracle.computeIfAbsent(k, x -> new TreeSet<>()).add(1000 + k);
			}
			final String tag =
				"vbs=" + valueBlockSize + " min=" + minBlockSize + " multiKey=" + multiKey + " asc=" + deleteAscending;
			assertStateAfterCommit(
				tree,
				tested -> {
					// promote exactly ONE key to a multi bucket: only its leaf gains an overflow column, so every
					// rebalance that leaf later performs pulls from a still-null-overflow donor leaf
					tested.addRecord(multiKey, 9000);
					oracle.get(multiKey).add(9000);
					// collapse the rest of the tree, forcing the multi-bearing leaf to repeatedly underflow and
					// steal / merge with its single-only neighbours
					for (int i = 0; i < n; i++) {
						final int k = deleteAscending ? i : (n - 1 - i);
						if (k == multiKey || oracle.get(k) == null) {
							continue;
						}
						tested.removeRecord(k, oracle.get(k).stream().mapToInt(Integer::intValue).toArray());
						oracle.remove(k);
						// keep the surviving bucket multi and mutate its bitmap so the aliased instance carries a
						// transactional layer (double-discarded at commit) rather than aliasing silently
						tested.addRecord(multiKey, 9000 + i + 1);
						oracle.get(multiKey).add(9000 + i + 1);
					}
				},
				(original, committed) -> {
					final ConsistencyReport report = committed.getConsistencyReport();
					assertEquals(ConsistencyState.CONSISTENT, report.state(), tag + ": " + report.report());
					assertEquals(oracle.size(), committed.bucketCount(), tag + " bucket count mismatch");
					for (final var entry : oracle.entrySet()) {
						final int[] expected = entry.getValue().stream().mapToInt(Integer::intValue).toArray();
						assertArrayEquals(
							expected, recordsOf(committed, entry.getKey()),
							tag + " records mismatch for value " + entry.getKey()
						);
					}
				}
			);
		}
	}

	/**
	 * Verifies the page-assembly seams ({@link TransactionalBucketBPlusTree#enumerateLeaves()} and
	 * {@link TransactionalBucketBPlusTree#assembleFromLeaves(List)}) the granular FilterIndex storage layout is built
	 * on: a tree decomposed into its ordered leaves and reconstructed from them must be equivalent in bucket ordering
	 * and per-bucket record sets, with a consistent internal spine derived purely from the leaves' boundary keys.
	 */
	@Nested
	@DisplayName("Page assembly (leaf enumeration & spine reconstruction)")
	class PageAssembly {

		@Test
		@DisplayName("enumerates leaves left-to-right covering every bucket exactly once")
		@Tag(INDEXING)
		void shouldEnumerateLeavesInAscendingOrderCoveringAllBuckets() {
			final TreeTuple prepared = prepareRandomTree(42L, 200);
			final List<BPlusLeafTreeNode<Integer>> leaves = prepared.tree().enumerateLeaves();
			assertTrue(leaves.size() > 1, "Fixture should produce a multi-leaf tree.");
			assertArrayEquals(
				prepared.keys(), flattenLeafKeys(leaves),
				"Leaf enumeration must cover all keys in ascending order."
			);
		}

		@Test
		@DisplayName("round-trips a multi-leaf tree through enumerate then assemble")
		@Tag(INDEXING)
		void shouldRoundTripMultiLeafTree() {
			final TreeTuple prepared = prepareRandomTree(7L, 200);
			assertTrue(prepared.tree().enumerateLeaves().size() > 1, "Fixture should be a multi-leaf tree.");
			assertRoundTrip(prepared.tree(), prepared.keys());
		}

		@Test
		@DisplayName("round-trips a tree whose root is a single leaf")
		@Tag(INDEXING)
		void shouldRoundTripSingleLeafTree() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(5, 50);
			tree.addRecord(1, 10);
			assertEquals(1, tree.enumerateLeaves().size(), "Fixture should be a single-leaf tree.");
			assertRoundTrip(tree, new int[] {1, 5});
		}

		@Test
		@DisplayName("round-trips multi-record buckets preserving every record set")
		@Tag(INDEXING)
		void shouldRoundTripMultiRecordBuckets() {
			final TreeTuple prepared = prepareRandomMultiTree(13L, 200, 3);
			assertRoundTrip(prepared.tree(), prepared.keys());
		}

		@Test
		@DisplayName("round-trips an empty tree exposing a single empty leaf")
		@Tag(INDEXING)
		void shouldRoundTripEmptyTree() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			assertEquals(1, tree.enumerateLeaves().size(), "An empty tree must expose a single empty leaf.");
			assertRoundTrip(tree, new int[0]);
		}

		@Test
		@DisplayName("round-trips a multi-level tree whose internal nodes have a minimum occupancy above one")
		@Tag(INDEXING)
		void shouldRoundTripWithHigherMinimumOccupancy() {
			// internalNodeBlockSize 7 (odd) → fan-out 8, minInternal 3 (> 1); 500 keys force several internal levels
			// so the bottom-up spine builder's even-distribution min-occupancy guarantee is actually exercised.
			assertRoundTripForConfig(8, 3, 7, 3, 500);
		}

		@Test
		@DisplayName("round-trips at the InvertedIndex production block sizes")
		@Tag(INDEXING)
		void shouldRoundTripAtProductionBlockSizes() {
			// the live InvertedIndex bucket-tree config: valueBlockSize 256, minValue 127, internal 127, minInternal 63
			assertRoundTripForConfig(256, 127, 127, 63, 4_000);
		}

		/**
		 * Builds a tree at the given block-size configuration, fills it with `totalKeys` shuffled buckets (every fourth
		 * a multi-record bucket), and asserts it survives an enumerate → assemble round-trip.
		 *
		 * @param valueBlockSize           leaf block size
		 * @param minValueBlockSize        minimum leaf occupancy
		 * @param internalNodeBlockSize    internal node block size (odd)
		 * @param minInternalNodeBlockSize minimum internal occupancy
		 * @param totalKeys                number of distinct buckets to insert
		 */
		private static void assertRoundTripForConfig(
			int valueBlockSize, int minValueBlockSize,
			int internalNodeBlockSize, int minInternalNodeBlockSize, int totalKeys
		) {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(
				valueBlockSize, minValueBlockSize, internalNodeBlockSize, minInternalNodeBlockSize,
				Integer.class, null
			);
			final Random random = new Random(99L);
			final List<Integer> order = new ArrayList<>(totalKeys);
			for (int i = 0; i < totalKeys; i++) {
				order.add(i);
			}
			java.util.Collections.shuffle(order, random);
			for (final int key : order) {
				if (key % 4 == 0) {
					tree.addRecord(key, key * 10, key * 10 + 1);
				} else {
					tree.addRecord(key, key * 10);
				}
			}
			assertTrue(tree.enumerateLeaves().size() > 1, "Config must produce a multi-leaf tree.");
			final int[] expectedKeys = new int[totalKeys];
			for (int i = 0; i < totalKeys; i++) {
				expectedKeys[i] = i;
			}
			assertRoundTrip(tree, expectedKeys);
		}

		/**
		 * Flattens the keys held across the ordered leaves into a single ascending array.
		 *
		 * @param leaves the ordered leaves
		 * @return the concatenated keys
		 */
		@Nonnull
		private static int[] flattenLeafKeys(@Nonnull List<BPlusLeafTreeNode<Integer>> leaves) {
			final List<Integer> all = new ArrayList<>();
			for (final BPlusLeafTreeNode<Integer> leaf : leaves) {
				for (int i = 0; i <= leaf.getPeek(); i++) {
					all.add(leaf.keyAt(i));
				}
			}
			return all.stream().mapToInt(Integer::intValue).toArray();
		}

		/**
		 * Enumerates the leaves of `original`, re-assembles a fresh tree from them, and asserts the reconstruction is
		 * consistent and bucket-for-bucket equivalent to the original.
		 *
		 * @param original     the source tree
		 * @param expectedKeys the sorted keys the reconstruction must expose
		 */
		private static void assertRoundTrip(
			@Nonnull TransactionalBucketBPlusTree<Integer> original, @Nonnull int... expectedKeys
		) {
			final List<BPlusLeafTreeNode<Integer>> leaves = original.enumerateLeaves();
			final TransactionalBucketBPlusTree<Integer> reassembled = original.assembleFromLeaves(leaves);
			verifyTreeConsistency(reassembled, expectedKeys);
			assertEquals(original.bucketCount(), reassembled.bucketCount(), "Bucket count must be preserved.");
			for (final int key : expectedKeys) {
				assertArrayEquals(
					recordsOf(original, key), recordsOf(reassembled, key),
					"Record set must be preserved for value " + key
				);
			}
		}
	}

	/**
	 * Verifies {@link TransactionalBucketBPlusTree#collectRebuiltNodesSince(TransactionalBucketBPlusTree)} — the
	 * Option-2 dirty-page detector for the granular FilterIndex layout. The rebuilt set it reports must
	 * equal, by instance identity, the set the transactional merge actually rebuilt (committed nodes absent from the
	 * prior committed tree), it must prune clean subtrees, and — the case a leaf-layer-only predicate would miss — it
	 * must flag a leaf whose record set changed only through an overflow bitmap mutated outside the leaf's own methods.
	 */
	@Nested
	@DisplayName("Committed-page diff (Option-2 rebuilt-node detection)")
	@Tag(TRANSACTION)
	class CommittedPageDiff {

		@Test
		@DisplayName("reports no rebuilt nodes for a no-op commit")
		@Tag(INDEXING)
		void shouldReportNoRebuiltNodesForNoOpCommit() {
			final TreeTuple prepared = prepareRandomTree(11L, 200);
			assertStateAfterCommit(
				prepared.tree(),
				tree -> {
					// no mutation at all
				},
				(original, committed) -> {
					assertSame(original.getRoot(), committed.getRoot(), "A no-op commit must share the root by identity.");
					assertTrue(
						committed.collectRebuiltNodesSince(original).isEmpty(),
						"A no-op commit must rebuild nothing."
					);
				}
			);
		}

		@Test
		@DisplayName("reports exactly the root-to-leaf path for a single changed bucket")
		@Tag(INDEXING)
		void shouldReportRootToLeafPathForSingleChangedBucket() {
			final TreeTuple prepared = prepareRandomTree(12L, 300);
			assertStateAfterCommit(
				prepared.tree(),
				tree -> tree.addRecord(150, 1_500, 9_999), // promote one existing bucket to multi
				(original, committed) -> {
					final List<BPlusTreeNode<Integer, ?>> rebuilt = committed.collectRebuiltNodesSince(original);
					assertRebuiltMatchesMergeSet(original, committed, rebuilt);
					// the change touches one leaf, so only that leaf + its spine path are rebuilt — far fewer than all
					assertTrue(rebuilt.size() < original.bucketCount(), "Must not rebuild the whole tree.");
					assertEquals(1, leafCount(rebuilt), "Exactly one leaf must be rebuilt for a single-bucket change.");
				}
			);
		}

		@Test
		@DisplayName("flags the leaf when a record set changes only through its overflow bitmap")
		@Tag(INDEXING)
		void shouldReportLeafForOverflowOnlyBitmapMutation() {
			// value 0 is a multi bucket (0 % 5 == 0) holding {0, 1}
			final TreeTuple prepared = prepareRandomMultiTree(13L, 300, 5);
			assertStateAfterCommit(
				prepared.tree(),
				tree -> {
					// mutate the live overflow bitmap directly, bypassing the leaf's addRecord — so the leaf node
					// itself never acquires a transactional layer (the case the old leaf-layer predicate missed)
					final Bitmap records = tree.getRecordsEqualTo(0);
					assertInstanceOf(TransactionalBitmap.class, records, "Value 0 must be a multi-record bucket.");
					((TransactionalBitmap) records).add(9_999);
				},
				(original, committed) -> {
					final List<BPlusTreeNode<Integer, ?>> rebuilt = committed.collectRebuiltNodesSince(original);
					assertRebuiltMatchesMergeSet(original, committed, rebuilt);
					assertEquals(1, leafCount(rebuilt), "The overflow mutation must rebuild exactly its one leaf.");
					assertArrayEquals(
						new int[] {0, 1, 9_999}, committed.getRecordsEqualTo(0).getArray(),
						"The committed tree must carry the overflow-added record."
					);
				}
			);
		}

		@Test
		@DisplayName("matches the merge rebuilt set across split-inducing inserts")
		@Tag(INDEXING)
		void shouldMatchMergeSetForSplitInducingInserts() {
			final TreeTuple prepared = prepareRandomTree(14L, 200);
			assertStateAfterCommit(
				prepared.tree(),
				tree -> {
					for (int i = 1_000; i < 1_120; i++) {
						tree.addRecord(i, i * 10);
					}
				},
				(original, committed) -> assertRebuiltMatchesMergeSet(
					original, committed, committed.collectRebuiltNodesSince(original)
				)
			);
		}

		@Test
		@DisplayName("matches the merge rebuilt set across merge-inducing removals")
		@Tag(INDEXING)
		void shouldMatchMergeSetForMergeInducingRemovals() {
			final TreeTuple prepared = prepareRandomTree(15L, 200);
			assertStateAfterCommit(
				prepared.tree(),
				tree -> {
					for (int i = 0; i < 150; i++) {
						tree.removeRecord(i, i * 10);
					}
				},
				(original, committed) -> assertRebuiltMatchesMergeSet(
					original, committed, committed.collectRebuiltNodesSince(original)
				)
			);
		}

		/**
		 * Asserts the reported rebuilt set equals, by identity, the merge's actual rebuilt set — the committed nodes
		 * not identity-present in the prior tree — computed here independently via a full (unpruned) walk of both trees.
		 * Also asserts the report has no duplicates and that no reported node was carried over from the prior tree.
		 *
		 * @param prior     the pre-commit committed tree
		 * @param committed the post-commit committed tree
		 * @param rebuilt   the nodes the detector reported as rebuilt
		 */
		private static void assertRebuiltMatchesMergeSet(
			@Nonnull TransactionalBucketBPlusTree<Integer> prior,
			@Nonnull TransactionalBucketBPlusTree<Integer> committed,
			@Nonnull List<BPlusTreeNode<Integer, ?>> rebuilt
		) {
			final Set<BPlusTreeNode<Integer, ?>> priorNodes = identityNodesOf(prior);
			final Set<BPlusTreeNode<Integer, ?>> committedNodes = identityNodesOf(committed);
			final Set<BPlusTreeNode<Integer, ?>> oracle = Collections.newSetFromMap(new IdentityHashMap<>());
			for (final BPlusTreeNode<Integer, ?> node : committedNodes) {
				if (!priorNodes.contains(node)) {
					oracle.add(node);
				}
			}
			final Set<BPlusTreeNode<Integer, ?>> reported = Collections.newSetFromMap(new IdentityHashMap<>());
			reported.addAll(rebuilt);
			assertEquals(rebuilt.size(), reported.size(), "The report must not contain duplicate nodes.");
			for (final BPlusTreeNode<Integer, ?> node : rebuilt) {
				assertFalse(priorNodes.contains(node), "A reported node must not be one carried over from the prior tree.");
			}
			assertEquals(oracle.size(), reported.size(), "Reported set size must equal the merge rebuilt set.");
			assertTrue(reported.containsAll(oracle), "Reported set must contain every node the merge rebuilt.");
			assertTrue(oracle.containsAll(reported), "Reported set must contain only nodes the merge rebuilt.");
		}

		/**
		 * Collects every node of the tree into an identity set via a full walk.
		 *
		 * @param tree the tree to index
		 * @return the identity set of all its nodes
		 */
		@Nonnull
		private static Set<BPlusTreeNode<Integer, ?>> identityNodesOf(
			@Nonnull TransactionalBucketBPlusTree<Integer> tree
		) {
			final Set<BPlusTreeNode<Integer, ?>> nodes = Collections.newSetFromMap(new IdentityHashMap<>());
			walkNodes(tree.getRoot(), nodes);
			return nodes;
		}

		/**
		 * Recursively adds `node` and its descendants to `out`.
		 *
		 * @param node the subtree root
		 * @param out  the identity set accumulator
		 */
		private static void walkNodes(
			@Nonnull BPlusTreeNode<Integer, ?> node, @Nonnull Set<BPlusTreeNode<Integer, ?>> out) {
			out.add(node);
			if (node instanceof BPlusInternalTreeNode<?> internal) {
				@SuppressWarnings("unchecked") final BPlusInternalTreeNode<Integer> internalNode =
					(BPlusInternalTreeNode<Integer>) internal;
				final BPlusTreeNode<Integer, ?>[] children = internalNode.getChildren();
				for (int i = 0; i <= internalNode.getPeek(); i++) {
					walkNodes(children[i], out);
				}
			}
		}

		/**
		 * Counts how many of the reported nodes are leaves.
		 *
		 * @param nodes the reported nodes
		 * @return the leaf count
		 */
		private static int leafCount(@Nonnull List<BPlusTreeNode<Integer, ?>> nodes) {
			int count = 0;
			for (final BPlusTreeNode<Integer, ?> node : nodes) {
				if (node instanceof BPlusLeafTreeNode<?>) {
					count++;
				}
			}
			return count;
		}
	}

	/**
	 * Verifies the granular FilterIndex page-tree: the STABLE, copy-propagated `pageSequence`
	 * node field. A node's page is its on-disk identity — an in-place rebuild (the transactional commit-merge) must carry
	 * the same page forward so the same storage part is overwritten, a split-born sibling must start
	 * {@link TransactionalBucketBPlusTree#UNASSIGNED_PAGE_SEQUENCE} so the write path allocates it fresh, and the
	 * enumerate→assemble load round-trip must preserve each leaf's page.
	 */
	@Nested
	@DisplayName("Page-sequence threading")
	class PageSequenceThreading {

		@Test
		@DisplayName("a fresh tree's root leaf starts with an unassigned page")
		@Tag(INDEXING)
		@Tag(SERIALIZATION)
		void shouldDefaultPageSequenceToUnassignedForFreshTree() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			final List<BPlusLeafTreeNode<Integer>> leaves = tree.enumerateLeaves();
			assertEquals(1, leaves.size(), "A fresh tree is a single empty leaf.");
			assertEquals(
				TransactionalBucketBPlusTree.UNASSIGNED_PAGE_SEQUENCE, leaves.get(0).getPageSequence(),
				"A freshly built leaf must start unassigned."
			);
		}

		@Test
		@DisplayName("an in-place commit reuses every leaf's page and rebuilds the touched leaf")
		@Tag(INDEXING)
		@Tag(SERIALIZATION)
		void shouldReuseLeafPagesAcrossInPlaceCommit() {
			final TreeTuple prepared = prepareRandomTree(21L, 300);
			final TransactionalBucketBPlusTree<Integer> tree = prepared.tree();
			// assign every committed leaf a distinct page, keyed by its left boundary (stable across an in-place change)
			final TreeMap<Integer, Integer> pageByBoundary = new TreeMap<>();
			final List<BPlusLeafTreeNode<Integer>> before = tree.enumerateLeaves();
			for (int i = 0; i < before.size(); i++) {
				final BPlusLeafTreeNode<Integer> leaf = before.get(i);
				leaf.setPageSequence(i);
				pageByBoundary.put(leaf.getLeftBoundaryKey(), i);
			}
			// capture the leaf owning value 150 so we can prove it is rebuilt (a fresh instance) yet keeps its page
			final BPlusLeafTreeNode<Integer> ownerBefore = leafOwning(before, 150);
			final int ownerPage = ownerBefore.getPageSequence();

			assertStateAfterCommit(
				tree,
				t -> t.addRecord(150, 1_500, 9_999), // promote an existing bucket — rebuilds one leaf, no split/merge
				(original, committed) -> {
					final List<BPlusLeafTreeNode<Integer>> after = committed.enumerateLeaves();
					for (final BPlusLeafTreeNode<Integer> leaf : after) {
						assertEquals(
							pageByBoundary.get(leaf.getLeftBoundaryKey()), Integer.valueOf(leaf.getPageSequence()),
							"Leaf boundary " + leaf.getLeftBoundaryKey() + " must keep its page across an in-place commit."
						);
					}
					final BPlusLeafTreeNode<Integer> ownerAfter = leafOwning(after, 150);
					assertNotSame(ownerBefore, ownerAfter, "The touched leaf must be rebuilt (a fresh instance).");
					assertEquals(ownerPage, ownerAfter.getPageSequence(), "The rebuilt leaf must reuse its source page.");
				}
			);
		}

		@Test
		@DisplayName("split-born leaves start unassigned")
		@Tag(INDEXING)
		@Tag(SERIALIZATION)
		void shouldLeaveSplitBornLeavesUnassigned() {
			// a tiny tree that is still a single leaf, with a page already assigned
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(1, 10);
			tree.addRecord(2, 20);
			final List<BPlusLeafTreeNode<Integer>> before = tree.enumerateLeaves();
			assertEquals(1, before.size(), "Setup must be a single leaf.");
			before.get(0).setPageSequence(42);

			assertStateAfterCommit(
				tree,
				t -> {
					// overflow the single leaf so it splits into two fresh leaves
					t.addRecord(3, 30);
					t.addRecord(4, 40);
					t.addRecord(5, 50);
				},
				(original, committed) -> {
					final List<BPlusLeafTreeNode<Integer>> after = committed.enumerateLeaves();
					assertTrue(after.size() > 1, "The leaf must have split.");
					for (final BPlusLeafTreeNode<Integer> leaf : after) {
						assertEquals(
							TransactionalBucketBPlusTree.UNASSIGNED_PAGE_SEQUENCE, leaf.getPageSequence(),
							"A split-born leaf must start unassigned so the write path allocates it a fresh page."
						);
					}
				}
			);
		}

		@Test
		@DisplayName("enumerate then assemble round-trip preserves each leaf's page")
		@Tag(INDEXING)
		@Tag(SERIALIZATION)
		void shouldPreserveLeafPagesThroughAssemblyRoundTrip() {
			final TreeTuple prepared = prepareRandomTree(22L, 400);
			final TransactionalBucketBPlusTree<Integer> tree = prepared.tree();
			final List<BPlusLeafTreeNode<Integer>> source = tree.enumerateLeaves();
			for (int i = 0; i < source.size(); i++) {
				source.get(i).setPageSequence(100 + i);
			}
			final TransactionalBucketBPlusTree<Integer> assembled = tree.assembleFromLeaves(source);
			final List<BPlusLeafTreeNode<Integer>> roundTripped = assembled.enumerateLeaves();
			assertEquals(source.size(), roundTripped.size(), "Leaf count must survive the round-trip.");
			for (int i = 0; i < source.size(); i++) {
				assertEquals(
					100 + i, roundTripped.get(i).getPageSequence(),
					"Leaf " + i + " must keep its page through enumerate then assemble."
				);
			}
		}

		/**
		 * Returns the leaf whose bucket range contains `value` — the leaf with the greatest left boundary not exceeding
		 * `value`.
		 *
		 * @param leaves the ordered leaves
		 * @param value  the bucket value to locate
		 * @return the owning leaf
		 */
		@Nonnull
		private static BPlusLeafTreeNode<Integer> leafOwning(
			@Nonnull List<BPlusLeafTreeNode<Integer>> leaves, int value) {
			BPlusLeafTreeNode<Integer> owner = leaves.get(0);
			for (final BPlusLeafTreeNode<Integer> leaf : leaves) {
				if (leaf.getLeftBoundaryKey() <= value) {
					owner = leaf;
				} else {
					break;
				}
			}
			return owner;
		}
	}

	@Nested
	@DisplayName("Assemble-from-single-leaf-trees cross-leaf order validation")
	class AssembleFromSingleLeafTreesValidationTest {

		@Test
		@DisplayName("throws when two single-leaf source trees overlap across the leaf-page boundary")
		@Tag(INDEXING)
		@Tag(SERIALIZATION)
		void shouldThrowWhenSingleLeafTreesOverlapAcrossBoundary() {
			// block size 9 keeps three buckets in a single leaf, so each source tree rebuilds to exactly one leaf page
			final TransactionalBucketBPlusTree<Integer> treeA = new TransactionalBucketBPlusTree<>(9, Integer.class);
			treeA.addRecord(1, 10);
			treeA.addRecord(2, 20);
			treeA.addRecord(3, 30);
			// tree B's first key (2) does not sort strictly after tree A's last key (3): a stale-twin overlap shape
			final TransactionalBucketBPlusTree<Integer> treeB = new TransactionalBucketBPlusTree<>(9, Integer.class);
			treeB.addRecord(2, 20);
			treeB.addRecord(3, 30);
			treeB.addRecord(4, 40);

			final TransactionalBucketBPlusTree<Integer> target = new TransactionalBucketBPlusTree<>(9, Integer.class);
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> target.assembleFromSingleLeafTrees(
					List.of(treeA, treeB), new int[]{0, 1}, "bucket B+ tree validation test"
				),
				"Overlapping single-leaf trees must fail the cross-leaf-order validation."
			);
			final String message = ex.getMessage();
			assertTrue(
				message.contains("overlaps its successor leaf-page sequence"),
				"The failure must be the cross-leaf-order corruption diagnostic, got: " + message
			);
			// the enriched diagnostic must carry the full error-path context: both page sequences, the ordered page
			// list, both leaves' key ranges with counts, and the (partial-overlap) containment fact
			assertTrue(
				message.contains("leaf-page sequence 0 overlaps its successor leaf-page sequence 1"),
				"Both offending page sequences must be named, got: " + message
			);
			assertTrue(
				message.contains("orderedLeafPageList=[0, 1]"),
				"The ordered leaf-page list must be reported as overlap context, got: " + message
			);
			assertTrue(
				message.contains("predecessorKeyRange=[1 .. 3] (3 keys)"),
				"The predecessor leaf's key range and count must be reported, got: " + message
			);
			assertTrue(
				message.contains("successorKeyRange=[2 .. 4] (3 keys)"),
				"The successor leaf's key range and count must be reported, got: " + message
			);
			assertTrue(
				message.contains("successorRangeWithinPredecessorRange=false"),
				"A successor that extends beyond the predecessor is a partial overlap, not a nested twin, got: " + message
			);
		}

		@Test
		@DisplayName("reports containment as a fact when the successor's range nests inside the predecessor's")
		@Tag(INDEXING)
		@Tag(SERIALIZATION)
		void shouldReportContainmentWhenSuccessorRangeNestsInsidePredecessor() {
			// predecessor page 0 spans [1..10]; successor page 1 spans [3..5], entirely inside it — the nested shape a
			// stale-donor twin leaves behind. The overlap trigger still fires (10 does not sort before 3) but the
			// containment fact must now read true, distinguishing this shape from a partial overlap
			final TransactionalBucketBPlusTree<Integer> treeA = new TransactionalBucketBPlusTree<>(9, Integer.class);
			treeA.addRecord(1, 10);
			treeA.addRecord(2, 20);
			treeA.addRecord(10, 100);
			final TransactionalBucketBPlusTree<Integer> treeB = new TransactionalBucketBPlusTree<>(9, Integer.class);
			treeB.addRecord(3, 30);
			treeB.addRecord(4, 40);
			treeB.addRecord(5, 50);

			final TransactionalBucketBPlusTree<Integer> target = new TransactionalBucketBPlusTree<>(9, Integer.class);
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> target.assembleFromSingleLeafTrees(
					List.of(treeA, treeB), new int[]{7, 9}, "bucket B+ tree containment test"
				),
				"A successor nested inside the predecessor must still fail the cross-leaf-order validation."
			);
			final String message = ex.getMessage();
			assertTrue(
				message.contains("leaf-page sequence 7 overlaps its successor leaf-page sequence 9"),
				"Both offending page sequences must be named, got: " + message
			);
			assertTrue(
				message.contains("orderedLeafPageList=[7, 9]"),
				"The ordered leaf-page list must be reported, got: " + message
			);
			assertTrue(
				message.contains("predecessorKeyRange=[1 .. 10] (3 keys)"),
				"The predecessor's key range must be reported, got: " + message
			);
			assertTrue(
				message.contains("successorKeyRange=[3 .. 5] (3 keys)"),
				"The successor's key range must be reported, got: " + message
			);
			assertTrue(
				message.contains("successorRangeWithinPredecessorRange=true"),
				"A successor nested inside the predecessor must be reported as contained, got: " + message
			);
		}

		@Test
		@DisplayName("throws when a reassembled leaf page has out-of-order interior keys")
		@Tag(INDEXING)
		@Tag(SERIALIZATION)
		void shouldThrowWhenLeafHasOutOfOrderInteriorKeys() {
			// build a sound single-leaf source tree, then corrupt its interior key order in place (swap two keys) to
			// simulate a serializer bug / truncated write / bit rot; the intra-leaf order check must reject it
			final TransactionalBucketBPlusTree<Integer> corrupt =
				new TransactionalBucketBPlusTree<>(10, 1, 3, 1, Integer.class, null);
			corrupt.addRecord(1, 10);
			corrupt.addRecord(2, 20);
			corrupt.addRecord(3, 30);
			// the boxed key column backs getKeys() directly, so mutating it corrupts the live leaf: [1,2,3] -> [2,1,3]
			final BPlusLeafTreeNode<Integer> leaf = (BPlusLeafTreeNode<Integer>) corrupt.getRoot();
			final Integer[] keys = leaf.getKeys();
			final Integer swap = keys[0];
			keys[0] = keys[1];
			keys[1] = swap;

			final TransactionalBucketBPlusTree<Integer> target =
				new TransactionalBucketBPlusTree<>(10, 1, 3, 1, Integer.class, null);
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> target.assembleFromSingleLeafTrees(
					List.of(corrupt), new int[]{0}, "bucket B+ tree intra-leaf test"),
				"A leaf with out-of-order interior keys must fail the intra-leaf-order validation."
			);
			assertTrue(
				ex.getMessage().contains("out-of-order keys"),
				"Expected the intra-leaf-order corruption diagnostic, got: " + ex.getMessage()
			);
		}
	}

	/**
	 * Verifies the per-leaf version token exposed by {@link BucketCursor#currentLeafId()} — the foundation of the
	 * leaf-granular formula-cache staleness mechanism. The contract that must hold: after a commit, the leaf that
	 * a mutation touched carries a FRESH id (so a cached read that crossed it is invalidated), while every leaf the
	 * mutation did NOT touch keeps its id (so cached reads over untouched value ranges stay valid). If untouched leaves
	 * did not keep their id the token would still be correct but useless — it would invalidate everything on any write.
	 */
	@Nested
	@DisplayName("Leaf version token (currentLeafId)")
	@Tag(CACHE)
	class LeafVersionTokenTest {

		/**
		 * Walks the whole tree forward and maps each bucket value to the version id of the leaf it lives in.
		 *
		 * @param tree the tree to snapshot
		 * @return a value → leaf-version-id map in ascending value order
		 */
		@Nonnull
		private static TreeMap<Integer, Long> snapshotValueToLeafId(@Nonnull TransactionalBucketBPlusTree<Integer> tree) {
			final TreeMap<Integer, Long> result = new TreeMap<>();
			final BucketCursor<Integer> cursor = tree.cursor();
			while (cursor.next()) {
				result.put(cursor.value(), cursor.currentLeafId());
			}
			return result;
		}

		@Test
		@DisplayName("mutating one bucket re-mints only its leaf's id; sibling leaves keep theirs")
		void mutationChangesOnlyTouchedLeafVersion() {
			// small block size -> a handful of buckets already spans several leaves
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			for (int value = 10; value <= 120; value += 10) {
				tree.addRecord(value, value * 100);
			}

			final TreeMap<Integer, Long> before = snapshotValueToLeafId(tree);
			// precondition: the fixture actually spans more than one leaf, otherwise the sibling-stability half is vacuous
			assertTrue(new TreeSet<>(before.values()).size() > 1, "Fixture must span multiple leaves!");

			final int mutatedValue = 60;
			final long touchedLeafIdBefore = before.get(mutatedValue);

			assertStateAfterCommit(
				tree,
				// promote bucket 60 single -> multi: an in-leaf change (no split/merge), so ONLY leaf(60) is rebuilt
				tested -> tested.addRecord(mutatedValue, 999_999),
				(original, committed) -> {
					final TreeMap<Integer, Long> after = snapshotValueToLeafId(committed);
					assertEquals(before.keySet(), after.keySet(), "Commit must not change the set of bucket values!");

					final long touchedLeafIdAfter = after.get(mutatedValue);
					// the touched leaf must carry a fresh version id
					assertNotEquals(touchedLeafIdBefore, touchedLeafIdAfter, "Touched leaf must get a fresh id!");

					boolean sawUntouchedSibling = false;
					for (final Entry<Integer, Long> entry : before.entrySet()) {
						final Integer value = entry.getKey();
						if (entry.getValue() == touchedLeafIdBefore) {
							// values that shared the touched leaf all move to the new leaf id together
							assertEquals(
								touchedLeafIdAfter, (long) after.get(value),
								"Bucket " + value + " shared the touched leaf and must follow its new id!"
							);
						} else {
							// values on any other leaf must keep their id verbatim (no over-invalidation)
							assertEquals(
								entry.getValue(), after.get(value),
								"Untouched sibling bucket " + value + " must keep its leaf id!"
							);
							sawUntouchedSibling = true;
						}
					}
					assertTrue(sawUntouchedSibling, "Test must exercise at least one untouched sibling leaf!");
				}
			);
		}

		@Test
		@DisplayName("currentLeafId before the first positioning fails fast")
		void currentLeafIdBeforePositioningThrows() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(10, 100);
			tree.addRecord(20, 200);

			// a freshly obtained cursor has not advanced onto a bucket yet (next() never returned true), so the
			// leaf-version accessor has nothing to report and must fail fast rather than expose a stale/garbage leaf id
			final BucketCursor<Integer> cursor = tree.cursor();
			assertThrows(GenericEvitaInternalError.class, cursor::currentLeafId);
		}
	}

	@Nested
	@DisplayName("Op-time boundary-mutation asserts")
	class OpTimeBoundaryMutationTest {

		/**
		 * Builds a single-leaf source tree holding the supplied keys. Block size 10 keeps every supplied key in one
		 * leaf (no split), so the tree's root stays a leaf that can be reassembled into a controlled spine.
		 *
		 * @param keys the keys to place in the leaf, in any order
		 * @return a single-leaf tree
		 */
		@Nonnull
		private static TransactionalBucketBPlusTree<Integer> singleLeaf(@Nonnull int... keys) {
			final TransactionalBucketBPlusTree<Integer> tree =
				new TransactionalBucketBPlusTree<>(10, 1, 3, 1, Integer.class, null);
			for (final int key : keys) {
				tree.addRecord(key, key * 10);
			}
			return tree;
		}

		/**
		 * Reassembles the supplied sound single-leaf trees into one tree with a deterministic spine: internal block
		 * size 3 caps a parent at four children, so five leaves split into two parents (P1 = three leaves, P2 = two).
		 * The leaves are non-overlapping, so the cross-leaf validation inside the assembler passes; the assembled tree
		 * is then used to exercise the op-time boundary checks against hypothetical boundary keys.
		 *
		 * @param leaves the ordered, non-overlapping single-leaf trees
		 * @return the assembled tree
		 */
		@Nonnull
		private static TransactionalBucketBPlusTree<Integer> assembleSound(
			@Nonnull List<TransactionalBucketBPlusTree<Integer>> leaves
		) {
			final int[] pageSequences = new int[leaves.size()];
			for (int i = 0; i < pageSequences.length; i++) {
				pageSequences[i] = i;
			}
			return new TransactionalBucketBPlusTree<>(10, 1, 3, 1, Integer.class, null)
				.assembleFromSingleLeafTrees(leaves, pageSequences, "bucket B+ tree op-time boundary test");
		}

		@Test
		@DisplayName("tail insert overlapping the successor leaf under a different parent throws (Check T)")
		void shouldThrowOnMisroutedTailInsertAcrossParentBoundary() {
			// spine (internal block 3 => max 4 children => 5 leaves split 3 + 2): P1 = [L0,L1,L2], P2 = [L3,L4].
			// L2 is the RIGHTMOST child of P1 and its successor L3 is the LEFTMOST child of P2, so the fence
			// (8 = L3's first key) lives at the ROOT, not L2's immediate parent — this proves the cross-parent walk.
			final TransactionalBucketBPlusTree<Integer> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(3, 4), singleLeaf(5, 6), singleLeaf(8, 9), singleLeaf(10, 11)
			));
			final TransactionalBucketBPlusTree.Cursor<Integer> cursor = tree.createCursor(5);
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> tree.assertTailBoundary(cursor, 8),
				"A new last key equal to the successor leaf's first key must be rejected."
			);
			assertTrue(
				ex.getMessage().contains("successor leaf boundary"),
				"Expected the tail cross-leaf diagnostic, got: " + ex.getMessage()
			);
			// a last key strictly below the fence is legal
			assertDoesNotThrow(() -> tree.assertTailBoundary(cursor, 7));
		}

		@Test
		@DisplayName("head insert undercutting the same-parent predecessor throws (Check H)")
		void shouldThrowOnMisroutedHeadInsertWithinParent() {
			// three sound leaves under one parent: L0 = [1,5], L1 = [8,12], L2 = [15,16]. L1's predecessor is its
			// same-parent left sibling L0 (last key 5). A head key of 3 undercuts it.
			final TransactionalBucketBPlusTree<Integer> tree = assembleSound(List.of(
				singleLeaf(1, 5), singleLeaf(8, 12), singleLeaf(15, 16)
			));
			final TransactionalBucketBPlusTree.Cursor<Integer> cursor = tree.createCursor(8);
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> tree.assertHeadBoundary(cursor, 3),
				"A new first key at or below the predecessor leaf's last key must be rejected."
			);
			assertTrue(
				ex.getMessage().contains("predecessor leaf boundary"),
				"Expected the head cross-leaf diagnostic, got: " + ex.getMessage()
			);
			// a first key strictly above the predecessor's last key is legal
			assertDoesNotThrow(() -> tree.assertHeadBoundary(cursor, 6));
			// the separator-order belt is INSUFFICIENT for this shape: propagating the mis-routed first key (3) up
			// as L1's separator yields separators (3, 15) which are still strictly increasing, so the belt would
			// NOT fire — which is exactly why Check H is required
			assertDoesNotThrow(
				() -> TransactionalBucketBPlusTree.assertSeparatorOrder(new Integer[]{3, 15}, 2, 0, null));
		}

		@Test
		@DisplayName("head insert undercutting a predecessor under a different parent throws (Check H right-spine)")
		void shouldThrowOnMisroutedHeadInsertAcrossParentBoundary() {
			// L3 is the LEFTMOST child of P2; its predecessor L2 is the RIGHTMOST child of P1 (cross-parent). Check
			// H must walk up to the clamp ancestor (root) and descend P1's right spine to L2 (last key 6).
			final TransactionalBucketBPlusTree<Integer> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(3, 4), singleLeaf(5, 6), singleLeaf(8, 9), singleLeaf(10, 11)
			));
			final TransactionalBucketBPlusTree.Cursor<Integer> cursor = tree.createCursor(8);
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> tree.assertHeadBoundary(cursor, 5),
				"A new first key at or below the cross-parent predecessor's last key must be rejected."
			);
			assertTrue(
				ex.getMessage().contains("predecessor leaf boundary"),
				"Expected the head cross-leaf diagnostic, got: " + ex.getMessage()
			);
			assertDoesNotThrow(() -> tree.assertHeadBoundary(cursor, 7));
		}

		@Test
		@DisplayName("both boundary checks apply to a single-key (0 to 1) leaf")
		void shouldRunBothChecksForSingleKeyLeaf() {
			// sound tree with a single-key middle leaf L_mid = [6] between L_pred = [1,4] and L_succ = [8,9]
			final TransactionalBucketBPlusTree<Integer> tree = assembleSound(List.of(
				singleLeaf(1, 4), singleLeaf(6), singleLeaf(8, 9)
			));
			final TransactionalBucketBPlusTree.Cursor<Integer> cursor = tree.createCursor(6);
			// the actual 0->1 key (6) is sound: both checks run and pass. Index 0 on a leaf whose peek is 0 is the
			// 0->1 shape the production caller passes here, and it satisfies both branch conditions at once
			assertDoesNotThrow(() -> tree.assertInsertBoundaries(tree.findLeafNodeWithBoundaryContext(6), 6, 0));
			// the SAME single-key leaf is subject to the head check (a smaller key undercuts L_pred's last key 4)
			assertThrows(GenericEvitaInternalError.class, () -> tree.assertHeadBoundary(cursor, 3));
			// ...and to the tail check (a larger key reaches L_succ's first key 8)
			assertThrows(GenericEvitaInternalError.class, () -> tree.assertTailBoundary(cursor, 8));
		}

		@Test
		@DisplayName("the insert-path descent resolves the same operands as a captured cursor")
		void shouldResolveSameBoundaryOperandsAsCursorPath() {
			// the five-leaf spine covers every shape at once: L0 has no predecessor, L4 has no fence, L2/L3 straddle
			// the parent seam (fence at the ROOT, predecessor reached by a right-spine walk), the rest are
			// same-parent neighbours. Probes include keys absent from the tree, which route just like present ones.
			final TransactionalBucketBPlusTree<Integer> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(3, 4), singleLeaf(5, 6), singleLeaf(8, 9), singleLeaf(10, 11)
			));
			for (final int probe : new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}) {
				final TransactionalBucketBPlusTree.Cursor<Integer> cursor = tree.createCursor(probe);
				final TransactionalBucketBPlusTree.BoundaryContext<Integer> context =
					tree.findLeafNodeWithBoundaryContext(probe);
				assertSame(cursor.leafNode(), context.leaf(), "Descent reached a different leaf for key " + probe);
				assertEquals(tree.fenceOf(cursor), context.fence(), "Fence differs for key " + probe);
				assertSame(
					tree.predecessorLeaf(cursor), context.predecessor(),
					"Predecessor differs for key " + probe
				);
			}
		}

		@Test
		@DisplayName("descent-resolved operands are populated, and the asserts built on them still fire")
		void shouldPopulateBoundaryOperandsFromDescent() {
			// a descent that always answered "no fence" / "no predecessor" would keep every other test green, because
			// in a sound tree the asserts never fire — so pin the operands themselves, then prove they still bite
			final TransactionalBucketBPlusTree<Integer> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(3, 4), singleLeaf(5, 6), singleLeaf(8, 9), singleLeaf(10, 11)
			));
			assertEquals(
				Integer.valueOf(8), tree.findLeafNodeWithBoundaryContext(5).fence(),
				"L2 is the rightmost child of P1, so its fence is the ROOT separator (L3's first key)."
			);
			assertEquals(
				Integer.valueOf(3), tree.findLeafNodeWithBoundaryContext(1).fence(),
				"L0's fence is its same-parent successor's first key."
			);
			assertNull(
				tree.findLeafNodeWithBoundaryContext(10).fence(),
				"The rightmost leaf has no successor, hence no fence."
			);
			assertNull(
				tree.findLeafNodeWithBoundaryContext(1).predecessor(),
				"The leftmost leaf has no predecessor."
			);
			assertNotNull(
				tree.findLeafNodeWithBoundaryContext(8).predecessor(),
				"L3 is the leftmost child of P2, so its predecessor is reached across the parent seam."
			);
			// L2 = [5,6] (peek 1): an insert landing at index 1 becomes the new last key and must stay below the fence
			assertThrows(
				GenericEvitaInternalError.class,
				() -> tree.assertInsertBoundaries(tree.findLeafNodeWithBoundaryContext(5), 8, 1),
				"A tail insert reaching the successor leaf's first key must be rejected."
			);
			// L3 = [8,9]: an insert landing at index 0 becomes the new first key and must stay above the predecessor
			assertThrows(
				GenericEvitaInternalError.class,
				() -> tree.assertInsertBoundaries(tree.findLeafNodeWithBoundaryContext(8), 5, 0),
				"A head insert undercutting the cross-parent predecessor must be rejected."
			);
		}

		@Test
		@DisplayName("sequential bulk append and prepend never trip the op-time boundary asserts (happy-path pin)")
		void shouldNotThrowOnSequentialInsertions() {
			// ascending adds are all tail inserts (Check T runs on the rightmost leaf and finds no fence);
			// descending adds are all head inserts on the leftmost leaf (Check H finds no predecessor)
			final TransactionalBucketBPlusTree<Integer> ascending = new TransactionalBucketBPlusTree<>(3, Integer.class);
			assertDoesNotThrow(() -> {
				for (int i = 1; i <= 256; i++) {
					ascending.addRecord(i, i * 10);
				}
			});
			final ConsistencyReport ascendingReport = ascending.getConsistencyReport();
			assertEquals(ConsistencyState.CONSISTENT, ascendingReport.state(), ascendingReport.report());

			final TransactionalBucketBPlusTree<Integer> descending =
				new TransactionalBucketBPlusTree<>(3, Integer.class);
			assertDoesNotThrow(() -> {
				for (int i = 256; i >= 1; i--) {
					descending.addRecord(i, i * 10);
				}
			});
			final ConsistencyReport descendingReport = descending.getConsistencyReport();
			assertEquals(ConsistencyState.CONSISTENT, descendingReport.state(), descendingReport.report());
		}

	}

	/**
	 * Pins the equivalence the op-time boundary asserts stand on. `assertInsertBoundaries` selects its head / tail
	 * branch from the insertion INDEX the leaf add methods return, not by decoding the leaf's first and last key and
	 * comparing them to the inserted one. The two answers coincide only because a comparator-equal key never inserts a
	 * new bucket — `findKeyPosition` reports it as already present, and that branch never reaches the asserts — so the
	 * returned index can never point anywhere but at the slot the key itself occupies.
	 *
	 * That argument is comparator-relative and an ASCII-only test cannot exercise it: under natural order,
	 * comparator-equal and equal-as-strings are the same thing. Every test here therefore runs under a collator, one of
	 * them at PRIMARY strength where two DISTINCT strings compare equal — the shape that would break the equivalence if
	 * the "no comparator-equal duplicates" premise did not hold.
	 */
	@Nested
	@DisplayName("Insertion-index boundary branch selection")
	class InsertionIndexBranchSelectionTest {

		private static final int VALUE_BLOCK_SIZE = 10;
		/**
		 * Mirrors the production sentinel: the leaf add methods return this when the record joined an EXISTING bucket
		 * and no new bucket key was inserted.
		 */
		private static final int NO_NEW_BUCKET = -1;
		/**
		 * Distinct keys that are non-trivial for a collator: mixed case and accented letters, whose collation order
		 * differs from their raw UTF-8 byte order. All are distinct under FRENCH collation at default (tertiary)
		 * strength, so each add inserts a genuinely new bucket.
		 */
		private static final String[] COLLATION_SENSITIVE_KEYS = {
			"Zebre", "abricot", "éclair", "Mangue", "ananas", "Ëtude", "zebre", "Abricot"
		};

		/**
		 * Builds an empty String-keyed tree ordered by the supplied collator, wired to the front-coded value column the
		 * production factory selects for String keys.
		 *
		 * @param collator the comparator defining key order
		 * @return a new empty tree
		 */
		@Nonnull
		@SuppressWarnings({"unchecked", "rawtypes"})
		private static TransactionalBucketBPlusTree<String> collatedTree(@Nonnull Comparator<String> collator) {
			final ValueColumnFactory factory = ValueColumnFactory.forKey(String.class, collator);
			return new TransactionalBucketBPlusTree<>(
				VALUE_BLOCK_SIZE, 3, 7, 3, String.class, collator, factory
			);
		}

		/**
		 * Asserts that the index-based head / tail branch conditions agree with the key-comparison ones they replaced,
		 * against the leaf's post-insert state.
		 *
		 * @param leaf       the leaf the key was just inserted into
		 * @param key        the freshly inserted key
		 * @param insertedAt the slot index the leaf add method reported
		 * @param collator   the comparator defining key order
		 */
		private static void assertIndexAgreesWithKeyComparison(
			@Nonnull BPlusLeafTreeNode<String> leaf,
			@Nonnull String key,
			int insertedAt,
			@Nonnull Comparator<String> collator
		) {
			final int peek = leaf.getPeek();
			final boolean tailByKey = collator.compare(key, leaf.keyAt(peek)) == 0;
			final boolean headByKey = collator.compare(key, leaf.keyAt(0)) == 0;
			assertEquals(
				tailByKey, insertedAt == peek,
				"Tail branch disagreement for '" + key + "' at index " + insertedAt + " (peek " + peek + ")"
			);
			assertEquals(
				headByKey, insertedAt == 0,
				"Head branch disagreement for '" + key + "' at index " + insertedAt + " (peek " + peek + ")"
			);
		}

		/**
		 * Builds an empty String-keyed LONG-PAYLOAD tree ordered by the supplied collator.
		 *
		 * @param collator the comparator defining key order
		 * @return a new empty long-payload tree
		 */
		@Nonnull
		@SuppressWarnings({"unchecked", "rawtypes"})
		private static TransactionalBucketBPlusTree<String> collatedLongPayloadTree(
			@Nonnull Comparator<String> collator
		) {
			final ValueColumnFactory factory = ValueColumnFactory.forKey(String.class, collator);
			return TransactionalBucketBPlusTree.withLongPayload(
				VALUE_BLOCK_SIZE, 3, 7, 3, String.class, collator, factory
			);
		}

		/**
		 * Produces `count` distinct collation-sensitive keys — an accented, mixed-case stem plus a zero-padded ordinal,
		 * so that collation order and raw UTF-8 byte order genuinely disagree across the set.
		 *
		 * @param count the number of keys to produce
		 * @return the generated keys, in generation order (NOT collation order)
		 */
		@Nonnull
		private static String[] collatedKeys(int count) {
			final String[] stems = {"éclair", "Abricot", "Ëtude", "mangue", "Zebre", "ananas"};
			final String[] keys = new String[count];
			for (int i = 0; i < count; i++) {
				keys[i] = stems[i % stems.length] + "-" + String.format("%03d", i);
			}
			return keys;
		}

		@Test
		@DisplayName("index-based branch selection matches key comparison over randomized collated inserts")
		void shouldAgreeWithKeyComparisonOverRandomizedCollatedInserts() {
			final Comparator<String> collator = new LocalizedStringComparator(Locale.FRENCH);
			final Random random = new Random(20260731L);
			for (int trial = 0; trial < 200; trial++) {
				// a fresh single-leaf tree per trial, filled in a random order so head, tail and interior inserts all
				// occur; the key count stays below the block size so no split intervenes and the cursor's leaf is the root
				final TransactionalBucketBPlusTree<String> tree = collatedTree(collator);
				final List<String> shuffled = new ArrayList<>(List.of(COLLATION_SENSITIVE_KEYS));
				Collections.shuffle(shuffled, random);
				int pk = 0;
				for (final String key : shuffled) {
					final TransactionalBucketBPlusTree.BoundaryContext<String> context =
						tree.findLeafNodeWithBoundaryContext(key);
					final BPlusLeafTreeNode<String> leaf = context.leaf();
					final int insertedAt = leaf.addRecord(key, pk++);
					assertNotEquals(
						NO_NEW_BUCKET, insertedAt,
						"Every key in the fixture is distinct under this collator, so each add must insert a bucket."
					);
					assertIndexAgreesWithKeyComparison(leaf, key, insertedAt, collator);
					// the production entry point must accept the index and validate the sound leaf without complaint
					assertDoesNotThrow(() -> tree.assertInsertBoundaries(context, key, insertedAt));
				}
			}
		}

		@Test
		@DisplayName("index-based branch selection matches key comparison on a transactional layer")
		void shouldAgreeWithKeyComparisonOnTransactionalLayer() {
			final Comparator<String> collator = new LocalizedStringComparator(Locale.FRENCH);
			final TransactionalBucketBPlusTree<String> tree = collatedTree(collator);
			tree.addRecord("Mangue", 1);
			tree.addRecord("abricot", 2);
			// the layer branch computes the position from, and inserts it into, the layer's own key column - the
			// rollback keeps the assertions confined to the transactional state without committing a bypassed size
			assertStateAfterRollback(
				tree,
				tested -> {
					int pk = 100;
					for (final String key : new String[]{"Zebre", "Ëtude", "Abricot", "éclair"}) {
						final TransactionalBucketBPlusTree.BoundaryContext<String> context =
							tested.findLeafNodeWithBoundaryContext(key);
						final BPlusLeafTreeNode<String> leaf = context.leaf();
						final int insertedAt = leaf.addRecord(key, pk++);
						assertNotEquals(NO_NEW_BUCKET, insertedAt);
						assertIndexAgreesWithKeyComparison(leaf, key, insertedAt, collator);
						assertDoesNotThrow(() -> tested.assertInsertBoundaries(context, key, insertedAt));
					}
				},
				(original, committed) -> {
					assertTrue(original.contains("Mangue"));
					assertFalse(original.contains("Zebre"));
				}
			);
		}

		@Test
		@DisplayName("distinct strings that compare equal under a PRIMARY collator never insert a second bucket")
		void shouldNotInsertNewBucketForComparatorEqualDistinctStrings() {
			// at PRIMARY strength the collator ignores accents, so "resume" and "résumé" are equal keys despite being
			// different strings. This is precisely the case that would let the returned index point at a slot other
			// than the one holding the key - the add must report NO_NEW_BUCKET instead, so the asserts never run
			final Collator primary = Collator.getInstance(Locale.FRENCH);
			primary.setStrength(Collator.PRIMARY);
			final Comparator<String> collator = new LocalizedStringComparator(primary);
			assertEquals(0, collator.compare("resume", "résumé"), "Fixture precondition: the two keys must collate equal");

			final TransactionalBucketBPlusTree<String> tree = collatedTree(collator);
			final TransactionalBucketBPlusTree.Cursor<String> firstCursor = tree.createCursor("resume");
			assertEquals(0, firstCursor.leafNode().addRecord("resume", 1));

			final TransactionalBucketBPlusTree.Cursor<String> secondCursor = tree.createCursor("résumé");
			assertEquals(
				NO_NEW_BUCKET, secondCursor.leafNode().addRecord("résumé", 2),
				"A comparator-equal key must join the existing bucket, never insert a second one."
			);
		}

		@Test
		@DisplayName("the long-payload leaf add reports an insertion index that matches key comparison")
		void shouldReportInsertionIndexForLongPayloadAdds() {
			// addLongRecord is the method whose result the outer tree API used to discard entirely, so a wrong index
			// there would go unnoticed longest - and it backs GlobalUniqueIndex / ReferenceTypeCardinalityIndex
			final Comparator<String> collator = new LocalizedStringComparator(Locale.FRENCH);
			final TransactionalBucketBPlusTree<String> tree = collatedLongPayloadTree(collator);
			long payload = 1L;
			for (final String key : COLLATION_SENSITIVE_KEYS) {
				final TransactionalBucketBPlusTree.BoundaryContext<String> context =
					tree.findLeafNodeWithBoundaryContext(key);
				final BPlusLeafTreeNode<String> leaf = context.leaf();
				final int insertedAt = leaf.addLongRecord(key, payload++);
				assertNotEquals(NO_NEW_BUCKET, insertedAt, "A long-payload add always inserts a new bucket.");
				assertIndexAgreesWithKeyComparison(leaf, key, insertedAt, collator);
				assertDoesNotThrow(() -> tree.assertInsertBoundaries(context, key, insertedAt));
			}
		}

		@Test
		@DisplayName("index-based branch selection still matches key comparison on leaves reached after splits")
		void shouldAgreeWithKeyComparisonOnLeavesReachedAfterSplits() {
			// the tests above never split (few keys, one root leaf), so the leaf's peek only ever grew by insertion.
			// A split RECOMPUTES peek and hands the cursor a different leaf object - the shape most likely to break a
			// coordinate-space assumption - so this builds a genuinely multi-level tree first
			final Comparator<String> collator = new LocalizedStringComparator(Locale.FRENCH);
			@SuppressWarnings({"unchecked", "rawtypes"})
			final ValueColumnFactory factory = ValueColumnFactory.forKey(String.class, collator);
			@SuppressWarnings({"unchecked", "rawtypes"})
			final TransactionalBucketBPlusTree<String> tree = new TransactionalBucketBPlusTree<>(
				8, 3, 3, 1, String.class, collator, factory
			);
			final String[] built = collatedKeys(120);
			for (int i = 0; i < built.length; i++) {
				tree.addRecord(built[i], i);
			}
			assertInstanceOf(
				BPlusInternalTreeNode.class, tree.getRoot(),
				"Fixture precondition: the tree must have split into a multi-level spine."
			);

			int probed = 0;
			int skipped = 0;
			// coverage is counted in DISTINCT leaves, not probes: each successful direct insert pushes its leaf
			// closer to full, so a probe count alone would drift with the fixture and still read as full coverage
			final Set<BPlusLeafTreeNode<String>> probedLeaves = Collections.newSetFromMap(new IdentityHashMap<>());
			for (int i = 0; i < built.length; i++) {
				// each probe is its base key plus a trailing letter, so it collates immediately after that key and
				// therefore targets the leaf holding it — spreading the probes over EVERY leaf instead of piling
				// them all onto the few leaves that happen to hold the tail of the key space
				final String probe = built[i] + "m";
				final TransactionalBucketBPlusTree.BoundaryContext<String> context =
					tree.findLeafNodeWithBoundaryContext(probe);
				final BPlusLeafTreeNode<String> leaf = context.leaf();
				if (leaf.isFull()) {
					// a direct leaf insert cannot trigger the split the tree API would run, so skip rather than
					// corrupt the fixture — the assertion below pins that most probes still landed
					skipped++;
					continue;
				}
				final int insertedAt = leaf.addRecord(probe, 10_000 + i);
				assertNotEquals(NO_NEW_BUCKET, insertedAt);
				assertIndexAgreesWithKeyComparison(leaf, probe, insertedAt, collator);
				assertDoesNotThrow(() -> tree.assertInsertBoundaries(context, probe, insertedAt));
				probedLeaves.add(leaf);
				probed++;
			}
			assertTrue(
				probedLeaves.size() >= 12,
				"The equivalence must be exercised on several DISTINCT post-split leaves, but only "
					+ probedLeaves.size() + " were reached (" + probed + " probes ran, " + skipped
					+ " skipped on an already-full leaf)."
			);
		}

		@Test
		@DisplayName("the 0 to 1 insert reports index 0 on a leaf whose peek is 0, satisfying both branches")
		void shouldReportIndexZeroForInsertIntoEmptyLeaf() {
			final Comparator<String> collator = new LocalizedStringComparator(Locale.FRENCH);
			final TransactionalBucketBPlusTree<String> tree = collatedTree(collator);
			final TransactionalBucketBPlusTree.BoundaryContext<String> context =
				tree.findLeafNodeWithBoundaryContext("éclair");
			final BPlusLeafTreeNode<String> leaf = context.leaf();
			assertEquals(-1, leaf.getPeek(), "Fixture precondition: the leaf must start empty.");

			final int insertedAt = leaf.addRecord("éclair", 1);
			assertEquals(0, insertedAt);
			assertEquals(0, leaf.getPeek());
			// both branch conditions hold at once, which is what the head/tail asserts require of this transition
			assertIndexAgreesWithKeyComparison(leaf, "éclair", insertedAt, collator);
			assertDoesNotThrow(() -> tree.assertInsertBoundaries(context, "éclair", insertedAt));
		}

	}

	/**
	 * Verifies the internal-node fan-out invariant: an internal node never carries more than
	 * {@code internalNodeBlockSize} keys ({@code internalNodeBlockSize + 1} children), on both the paths that grow a
	 * spine — {@link TransactionalBucketBPlusTree#assembleFromSingleLeafTrees} (the persisted FilterIndex reload
	 * path, which builds a spine directly from a set of leaf pages) and a tree grown purely by incremental
	 * {@link TransactionalBucketBPlusTree#addRecord(Comparable, int)} calls. A child leaf split is absorbed into its
	 * parent internal node and, once that parent itself reaches capacity, the parent is split in turn — both steps
	 * are keyed to the configured internal node block size, not the leaf's.
	 */
	@Nested
	@DisplayName("Internal node fan-out invariant")
	class InternalNodeFanOutTest {

		private static final int VALUE_BLOCK_SIZE = 8;
		private static final int MIN_VALUE_BLOCK_SIZE = 3;
		private static final int INTERNAL_NODE_BLOCK_SIZE = 3;
		private static final int MIN_INTERNAL_NODE_BLOCK_SIZE = 1;

		/**
		 * Builds a fresh tree at this class's fixed block-size configuration ({@code valueBlockSize=8},
		 * {@code minValueBlockSize=3}, {@code internalNodeBlockSize=3}, {@code minInternalNodeBlockSize=1}).
		 *
		 * @return a new empty tree at the fixed configuration
		 */
		@Nonnull
		private static TransactionalBucketBPlusTree<Integer> newConfiguredTree() {
			return new TransactionalBucketBPlusTree<>(
				VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, INTERNAL_NODE_BLOCK_SIZE, MIN_INTERNAL_NODE_BLOCK_SIZE,
				Integer.class, null
			);
		}

		/**
		 * Builds a single-leaf source tree at the fixed block-size configuration holding `keyCount` consecutive
		 * single-record buckets (`key -> key*10`) starting at `firstKey`.
		 *
		 * @param firstKey the first (smallest) key placed in the leaf
		 * @param keyCount the number of consecutive keys placed in the leaf
		 * @return a single-leaf tree
		 */
		@Nonnull
		private static TransactionalBucketBPlusTree<Integer> singleLeaf(int firstKey, int keyCount) {
			final TransactionalBucketBPlusTree<Integer> tree = newConfiguredTree();
			for (int i = 0; i < keyCount; i++) {
				final int key = firstKey + i;
				tree.addRecord(key, key * 10);
			}
			return tree;
		}

		/**
		 * Recursively asserts every internal node reachable from `node` holds at most `internalNodeBlockSize` keys.
		 *
		 * @param node                  the subtree root to check
		 * @param internalNodeBlockSize the configured internal node block size (the fan-out limit is
		 *                              {@code internalNodeBlockSize + 1} children)
		 */
		private static void assertInternalFanOutWithinBlockSize(
			@Nonnull BPlusTreeNode<?, ?> node, int internalNodeBlockSize
		) {
			if (node instanceof BPlusInternalTreeNode<?> internalNode) {
				assertTrue(
					internalNode.keyCount() <= internalNodeBlockSize,
					"Internal node holds " + internalNode.keyCount() + " keys, exceeding the configured internal " +
						"node block size of " + internalNodeBlockSize + "."
				);
				final BPlusTreeNode<?, ?>[] children = internalNode.getChildren();
				for (int i = 0; i <= internalNode.getPeek(); i++) {
					assertInternalFanOutWithinBlockSize(children[i], internalNodeBlockSize);
				}
			}
		}

		@Test
		@DisplayName("splits an assembled parent internal node when a child leaf overflows and cascades a split into it")
		void shouldSplitAssembledParentInternalNodeWhenChildLeafOverflows() {
			final List<TransactionalBucketBPlusTree<Integer>> sourceLeaves = new ArrayList<>(6);
			final TreeSet<Integer> keys = new TreeSet<>();
			for (int i = 0; i < 6; i++) {
				sourceLeaves.add(singleLeaf(i * 100, 4));
				for (int j = 0; j < 4; j++) {
					keys.add(i * 100 + j);
				}
			}
			final int[] pageSequences = {0, 1, 2, 3, 4, 5};
			final TransactionalBucketBPlusTree<Integer> tree = newConfiguredTree().assembleFromSingleLeafTrees(
				sourceLeaves, pageSequences, "bucket B+ tree assembled-spine split test"
			);
			// the assembled spine is two internal nodes of three leaves each (two keys, below capacity) under a root
			assertInstanceOf(
				BPlusInternalTreeNode.class, tree.getRoot(),
				"The fixture must assemble a multi-level spine."
			);

			// fill the FIRST leaf's key range to the leaf's capacity: the last insert splits that leaf, its assembled
			// parent gains a fourth child and reaches its own capacity, and the tree immediately splits that parent too
			assertDoesNotThrow(() -> {
				for (int j = 4; j < VALUE_BLOCK_SIZE; j++) {
					tree.addRecord(j, j * 10);
					keys.add(j);
				}
			});

			final ConsistencyReport report = tree.getConsistencyReport();
			assertEquals(ConsistencyState.CONSISTENT, report.state(), report.report());
			final int[] expectedKeys = keys.stream().mapToInt(Integer::intValue).toArray();
			verifyTreeConsistency(tree, expectedKeys);
			for (final int key : expectedKeys) {
				assertArrayEquals(new int[]{key * 10}, recordsOf(tree, key), "Records must survive for key " + key);
			}
		}

		@Test
		@DisplayName("keeps every internal node's key count within the configured block size as a tree grows incrementally")
		void shouldKeepInternalNodeKeyCountWithinBlockSizeDuringIncrementalGrowth() {
			final TransactionalBucketBPlusTree<Integer> tree = newConfiguredTree();
			for (int i = 0; i < 500; i++) {
				tree.addRecord(i, i * 10);
			}
			assertInstanceOf(BPlusInternalTreeNode.class, tree.getRoot(), "Fixture must build a multi-level spine.");
			assertInternalFanOutWithinBlockSize(tree.getRoot(), INTERNAL_NODE_BLOCK_SIZE);
		}

		@Test
		@DisplayName("splits a child leaf under an internal node built directly by leaf-page assembly")
		void shouldSplitChildLeafUnderAnAssembledInternalNode() {
			final List<TransactionalBucketBPlusTree<Integer>> sourceLeaves = new ArrayList<>(4);
			final TreeSet<Integer> keys = new TreeSet<>();
			for (int i = 0; i < 4; i++) {
				sourceLeaves.add(singleLeaf(i * 100, 4));
				for (int j = 0; j < 4; j++) {
					keys.add(i * 100 + j);
				}
			}
			final int[] pageSequences = {0, 1, 2, 3};
			final TransactionalBucketBPlusTree<Integer> tree = newConfiguredTree().assembleFromSingleLeafTrees(
				sourceLeaves, pageSequences, "bucket B+ tree assembled-root split test"
			);
			assertInstanceOf(
				BPlusInternalTreeNode.class, tree.getRoot(), "The assembled spine must be an internal root.");

			// fill the FIRST leaf's key range to the leaf's capacity: the split must be absorbed by its assembled
			// parent internal node without corrupting the tree
			assertDoesNotThrow(() -> {
				for (int j = 4; j < VALUE_BLOCK_SIZE; j++) {
					tree.addRecord(j, j * 10);
					keys.add(j);
				}
			});

			final ConsistencyReport report = tree.getConsistencyReport();
			assertEquals(ConsistencyState.CONSISTENT, report.state(), report.report());
			verifyTreeConsistency(tree, keys.stream().mapToInt(Integer::intValue).toArray());
		}
	}

	@Nested
	@DisplayName("commit-time structural integrity validation")
	class DirtyLeafScopeValidationTest {

		/**
		 * A mutable comparable key. Bucket leaves store the key OBJECTS in a columnar {@link ValueColumn} (there is no
		 * primitive key array to overwrite), so the columnar analog of the Long/Element tests' "widen a leaf's last key
		 * array slot" corruption is to mutate a stored key object in place — which is exactly what a reverted / corrupted
		 * write layer would leave behind.
		 */
		private static final class MutableIntKey implements Comparable<MutableIntKey> {
			int value;

			MutableIntKey(int value) {
				this.value = value;
			}

			@Override
			public int compareTo(@Nonnull MutableIntKey o) {
				return Integer.compare(this.value, o.value);
			}

			@Override
			public boolean equals(Object o) {
				return o instanceof MutableIntKey k && k.value == this.value;
			}

			@Override
			public int hashCode() {
				return this.value;
			}

			@Override
			public String toString() {
				return String.valueOf(this.value);
			}
		}

		@Nonnull
		private static TransactionalBucketBPlusTree<MutableIntKey> singleLeaf(@Nonnull int... keys) {
			final TransactionalBucketBPlusTree<MutableIntKey> tree =
				new TransactionalBucketBPlusTree<>(10, 1, 3, 1, MutableIntKey.class, null);
			for (final int key : keys) {
				tree.addRecord(new MutableIntKey(key), key * 10);
			}
			return tree;
		}

		@Nonnull
		private static TransactionalBucketBPlusTree<MutableIntKey> assembleSound(
			@Nonnull List<TransactionalBucketBPlusTree<MutableIntKey>> leaves
		) {
			final int[] pageSequences = new int[leaves.size()];
			for (int i = 0; i < pageSequences.length; i++) {
				pageSequences[i] = i;
			}
			return new TransactionalBucketBPlusTree<>(10, 1, 3, 1, MutableIntKey.class, null)
				.assembleFromSingleLeafTrees(leaves, pageSequences, "bucket B+ tree scope test");
		}

		@Nonnull
		private static BPlusLeafTreeNode<MutableIntKey> leafAt(
			@Nonnull TransactionalBucketBPlusTree<MutableIntKey> tree, int key) {
			return tree.createCursor(new MutableIntKey(key)).leafNode();
		}

		@Test
		@DisplayName("a sound dirty scope relocates and validates without throwing")
		void shouldNotThrowWhenDirtyLeavesAreSound() {
			final TransactionalBucketBPlusTree<MutableIntKey> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(5, 6), singleLeaf(10, 11)
			));
			// the registry keeps probe KEYS, not node objects; each key relocates to the leaf that owns it
			assertDoesNotThrow(() -> tree.validateDirtyScope(List.<Object>of(
				new MutableIntKey(1), new MutableIntKey(5), new MutableIntKey(10)
			)));
		}

		@Test
		@DisplayName("a leaf whose last key was widened past its successor is caught (tail)")
		void shouldDetectTailOverlapOnRelocateAndValidate() {
			final TransactionalBucketBPlusTree<MutableIntKey> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(5, 6), singleLeaf(10, 11)
			));
			final BPlusLeafTreeNode<MutableIntKey> middle = leafAt(tree, 5);
			// widen the middle leaf's last key OBJECT from 6 to 10 so it reaches the successor's first key; the
			// separator is untouched, so relocating by the leaf's own first key (5) still lands on it and the tail
			// half-invariant fires
			middle.keyAt(middle.getPeek()).value = 10;
			// relocate by the leaf's own (unchanged) first key 5 — the descent lands on it and the tail half-invariant fires
			final AbstractTransactionalBPlusTree.BPlusTreeCorruptedException ex = assertThrows(
				AbstractTransactionalBPlusTree.BPlusTreeCorruptedException.class,
				() -> tree.validateDirtyScope(List.<Object>of(new MutableIntKey(5)))
			);
			assertTrue(
				ex.getMessage().contains("successor leaf boundary"),
				"Expected the tail cross-leaf diagnostic, got: " + ex.getMessage()
			);
		}

		@Test
		@DisplayName("a leaf undercut by a widened predecessor is caught (head)")
		void shouldDetectHeadOverlapOnRelocateAndValidate() {
			final TransactionalBucketBPlusTree<MutableIntKey> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(5, 6), singleLeaf(10, 11)
			));
			// widen the PREDECESSOR leaf's last key OBJECT from 2 to 5 so it reaches the middle leaf's first key;
			// relocating the middle leaf by its unchanged first key (5) lands on it and the head half-invariant compares
			// against the predecessor's corrupted last key
			final BPlusLeafTreeNode<MutableIntKey> predecessor = leafAt(tree, 1);
			predecessor.keyAt(predecessor.getPeek()).value = 5;
			// relocate the middle leaf by its unchanged first key 5; the head half-invariant compares against the
			// predecessor's corrupted last key
			final AbstractTransactionalBPlusTree.BPlusTreeCorruptedException ex = assertThrows(
				AbstractTransactionalBPlusTree.BPlusTreeCorruptedException.class,
				() -> tree.validateDirtyScope(List.<Object>of(new MutableIntKey(5)))
			);
			assertTrue(
				ex.getMessage().contains("predecessor leaf boundary"),
				"Expected the head cross-leaf diagnostic, got: " + ex.getMessage()
			);
		}

		@Test
		@DisplayName("a probe key landing on an empty leaf is skipped, not dereferenced")
		void shouldSkipWhenDescentLandsOnEmptyLeaf() {
			// an empty tree routes any probe key to its empty root leaf (peek < 0) — the scope validation must skip
			// it rather than dereference the peek slot
			final TransactionalBucketBPlusTree<MutableIntKey> tree =
				new TransactionalBucketBPlusTree<>(10, 1, 3, 1, MutableIntKey.class, null);
			assertDoesNotThrow(() -> tree.validateDirtyScope(List.<Object>of(new MutableIntKey(42))));
		}

		@Test
		@DisplayName("pre-commit pipeline: an int-record add registers its leaf and a healthy scope is accepted")
		void shouldRegisterDirtiedLeafViaAddRecord() {
			final TransactionalBucketBPlusTree<MutableIntKey> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(5, 6), singleLeaf(10, 11)
			));
			assertStateAfterCommit(
				tree,
				t -> {
					t.addRecord(new MutableIntKey(7), 70);
					final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
					// registration fired AND the registry is keyed by this exact tree instance — the identity the
					// pre-commit (pre-WAL) pass and the post-replay merge both look the scope up by
					assertFalse(
						maintainer.getDirtyScopeTokens(t).isEmpty(),
						"the int-record add must register its dirtied leaf under this tree"
					);
					assertDoesNotThrow(maintainer::validateDirtyScopesBeforeCommit);
				},
				(t, committed) -> assertNotNull(committed)
			);
		}

		@Test
		@DisplayName("pre-commit pipeline: a long-payload add registers its leaf and a healthy scope is accepted")
		void shouldRegisterDirtiedLeafViaAddLongRecord() {
			final TransactionalBucketBPlusTree<MutableIntKey> tree = TransactionalBucketBPlusTree.withLongPayload(
				10, 1, 3, 1, MutableIntKey.class, null,
				capacity -> new BoxedObjectColumn<>(MutableIntKey.class, capacity)
			);
			tree.addLongRecord(new MutableIntKey(1), 1L);
			tree.addLongRecord(new MutableIntKey(2), 2L);
			assertStateAfterCommit(
				tree,
				t -> {
					// the long-payload add path is a different leaf primitive than the int-record one — assert it also
					// registers its dirtied leaf, so the seam is proven wired on BOTH add surfaces
					t.addLongRecord(new MutableIntKey(5), 5L);
					final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
					assertFalse(
						maintainer.getDirtyScopeTokens(t).isEmpty(),
						"the long-payload add must register its dirtied leaf under this tree"
					);
					assertDoesNotThrow(maintainer::validateDirtyScopesBeforeCommit);
				},
				(t, committed) -> assertNotNull(committed)
			);
		}

		@Test
		@DisplayName("pre-commit pipeline: a corrupted registered leaf is rejected by the pre-commit pass")
		void shouldRejectCorruptedScopeInPreCommitPass() {
			final TransactionalBucketBPlusTree<MutableIntKey> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(5, 6), singleLeaf(10, 11)
			));
			assertThrows(
				AbstractTransactionalBPlusTree.BPlusTreeCorruptedException.class,
				() -> assertStateAfterCommit(
					tree,
					t -> {
						final MutableIntKey k7 = new MutableIntKey(7);
						t.addRecord(k7, 70);
						// widen the dirtied leaf (now [5,6,7]) so its last key reaches the successor's first key (10)
						k7.value = 10;
						Transaction.getTransactionalLayerMaintainer().validateDirtyScopesBeforeCommit();
					},
					(t, committed) -> fail("the pre-commit pass must reject before commit")
				)
			);
		}

		@Test
		@DisplayName("post-replay pipeline: a corrupted registered leaf is rejected by the commit merge")
		void shouldRejectCorruptedScopeInCommitMerge() {
			final TransactionalBucketBPlusTree<MutableIntKey> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(5, 6), singleLeaf(10, 11)
			));
			assertThrows(
				AbstractTransactionalBPlusTree.BPlusTreeCorruptedException.class,
				() -> assertStateAfterCommit(
					tree,
					t -> {
						final MutableIntKey k7 = new MutableIntKey(7);
						t.addRecord(k7, 70);
						// no pre-commit pass here — the commit merge (post-replay, inside createCopyWithMergedTransactionalMemory)
						// must relocate the registered leaf in the merged tree and catch the overlap
						k7.value = 10;
					},
					(t, committed) -> fail("the commit merge must reject the corrupted scope")
				)
			);
		}

		@Test
		@DisplayName("Registry hygiene: a remove-only transaction still registers its dirtied leaf")
		void shouldRegisterLeafOnRemoveOnlyTransaction() {
			final TransactionalBucketBPlusTree<MutableIntKey> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(5, 6), singleLeaf(10, 11)
			));
			assertStateAfterCommit(
				tree,
				t -> {
					// a clean remove (min block size 1 → [5,6] becomes [5], no underflow/rebalance) empties key 6's
					// bucket and drops it from the leaf; exercises the removal seam only
					t.removeRecord(new MutableIntKey(6), 60);
					assertFalse(
						Transaction.getTransactionalLayerMaintainer().getDirtyScopeTokens(t).isEmpty(),
						"a remove-only transaction must register the leaf it dirtied"
					);
				},
				(t, committed) -> assertNotNull(committed)
			);
		}

		@Test
		@DisplayName("Registry hygiene: a leaf split registers both halves")
		void shouldRegisterBothHalvesOnLeafSplit() {
			// block size 3 splits the root leaf as these four distinct keys are added
			final TransactionalBucketBPlusTree<MutableIntKey> tree =
				new TransactionalBucketBPlusTree<>(3, 1, 3, 1, MutableIntKey.class, null);
			assertStateAfterCommit(
				tree,
				t -> {
					t.addRecord(new MutableIntKey(1), 10);
					t.addRecord(new MutableIntKey(2), 20);
					t.addRecord(new MutableIntKey(3), 30);
					t.addRecord(new MutableIntKey(4), 40);
					// the split must register BOTH leaf halves; the identity-set size collapses to 1 if only one half
					// was registered (or one object was registered twice), which fails this assertion
					assertTrue(
						Transaction.getTransactionalLayerMaintainer().getDirtyScopeTokens(t).size() >= 2,
						"a leaf split must register both halves"
					);
				},
				(t, committed) -> assertNotNull(committed)
			);
		}

	}

	/**
	 * Pins the leaf-side half of the content-sized storage design: a column's backing array follows what the leaf
	 * holds, while the leaf keeps deciding to split, rebalance and page on the **logical** block size.
	 *
	 * The two failure modes these guard are asymmetric, and the worse one is silent. If `capacity()` ever came back
	 * as the backing array's length, a five-value tree would report itself full and split — turning a memory
	 * optimization into a storage-shape change. Worse, the split's own end bound is that same capacity, so the right
	 * half would copy the empty range and half the leaf would simply vanish, with no exception and no failing
	 * consistency report. Hence a content assertion after every split here, not merely a shape one.
	 */
	@Nested
	@DisplayName("Content-sized leaf storage behind a logical capacity")
	@Tag(INDEXING)
	class ContentSizedLeafStorage {

		/**
		 * Sums the heap of a leaf's key and record columns — the proxy this test uses for "how long are the backing
		 * arrays", since the arrays themselves are private to the columns.
		 *
		 * @param leaf the leaf to measure
		 * @return the bytes its two mandatory columns occupy
		 */
		private static long columnBytesOf(@Nonnull BPlusLeafTreeNode<Integer> leaf) {
			// both getters are transaction-aware: inside a transaction they answer for the leaf's LAYER, so this
			// measures the base leaf only outside one. Use the two-column overload to measure captured references
			return columnBytesOf(leaf.getKeyColumn(), leaf.getRecords());
		}

		/**
		 * Sums the heap of one key column and one record column, for a caller holding the two references directly.
		 *
		 * @param keys    the key column to measure
		 * @param records the single-record column to measure
		 * @return the bytes the two occupy
		 */
		private static long columnBytesOf(@Nonnull ValueColumn<Integer> keys, @Nonnull RecordColumn records) {
			return keys.getHeapSizeInBytes(element -> 0L) + records.getHeapSizeInBytes();
		}

		@Test
		@DisplayName("a leaf whose backing arrays are shorter than the block size still does not split")
		void shouldNotSplitALeafWhoseBackingArraysAreShorterThanTheBlockSize() {
			// a production-sized block against five values: the backing arrays hold eight slots, the leaf holds 255
			// (the single-argument constructor derives the minimum as blockSize / 2 and demands it be strictly below
			// half, so an odd block is the largest legal one it accepts)
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(255, Integer.class);
			for (int i = 0; i < 5; i++) {
				tree.addRecord(i, i * 10);
			}

			final List<BPlusLeafTreeNode<Integer>> leaves = tree.enumerateLeaves();
			assertEquals(1, leaves.size(), "five values must never outgrow a 255-bucket leaf");
			assertTrue(tree.getRoot() instanceof BPlusLeafTreeNode, "the root must still be the leaf itself");

			final BPlusLeafTreeNode<Integer> leaf = leaves.get(0);
			assertEquals(5, leaf.getKeyColumn().size(), "the key column holds exactly the five live values");
			assertEquals(255, leaf.getKeyColumn().capacity(), "the LOGICAL capacity is the block size, unchanged");
			assertEquals(255, leaf.getRecords().capacity());
			assertEquals(255, leaf.capacity(), "the leaf answers the logical capacity, not its storage");
			assertFalse(leaf.isFull());
			assertFalse(leaf.isNearlyFull());
			verifyTreeConsistency(tree, 0, 1, 2, 3, 4);
		}

		@Test
		@DisplayName("a real split keeps every bucket of both halves")
		void shouldKeepEveryBucketOfBothHalvesWhenALeafSplits() {
			// block size 5 with six values forces exactly one split, so both halves can be read back whole
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(5, Integer.class);
			for (int i = 0; i < 6; i++) {
				tree.addRecord(i, i * 10);
			}

			final List<BPlusLeafTreeNode<Integer>> leaves = tree.enumerateLeaves();
			assertEquals(2, leaves.size(), "the fixture must actually split");

			// the content assertion is the point: a split whose end bound collapsed to `mid` would leave the right
			// leaf empty and lose half the tree without throwing anything at all
			final BPlusLeafTreeNode<Integer> right = leaves.get(1);
			assertTrue(right.size() > 0, "the right half of a split must never be empty");

			int total = 0;
			for (final BPlusLeafTreeNode<Integer> leaf : leaves) {
				total += leaf.size();
				assertEquals(leaf.getPeek() + 1, leaf.getKeyColumn().size(), "every column covers exactly the leaf");
				assertEquals(leaf.getPeek() + 1, leaf.getRecords().size());
			}
			assertEquals(6, total, "a split must not lose a single bucket");
			verifyTreeConsistency(tree, 0, 1, 2, 3, 4, 5);
			for (int i = 0; i < 6; i++) {
				assertArrayEquals(new int[]{i * 10}, recordsOf(tree, i), "records lost at value " + i);
			}
		}

		@Test
		@DisplayName("a low-cardinality tree in which every bucket is multi-record stays whole")
		void shouldKeepEveryRecordWhenEveryBucketIsMultiRecord() {
			// the shape the overflow column dominates: few distinct values, every one of them promoted
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(255, Integer.class);
			for (int value = 0; value < 6; value++) {
				tree.addRecord(value, value * 100, value * 100 + 1, value * 100 + 2);
			}

			final BPlusLeafTreeNode<Integer> leaf = tree.enumerateLeaves().get(0);
			assertNotNull(leaf.getOverflow(), "every bucket is multi, so the overflow column must exist");
			assertEquals(6, leaf.getOverflow().size(), "the overflow column covers exactly the live buckets");
			assertEquals(255, leaf.getOverflow().capacity(), "its logical capacity is still the block size");
			for (int value = 0; value < 6; value++) {
				assertNotNull(leaf.getOverflow().bitmapAt(value), "bucket " + value + " must carry a bitmap");
				assertArrayEquals(
					new int[]{value * 100, value * 100 + 1, value * 100 + 2}, recordsOf(tree, value),
					"records lost at value " + value
				);
			}
			verifyTreeConsistency(tree, 0, 1, 2, 3, 4, 5);

			// deleting every record of the middle bucket collapses it, and the overflow column must shrink with the
			// rest rather than leave a stale bitmap aliased past the live run
			tree.removeRecord(3, 300, 301, 302);
			assertEquals(5, leaf.getOverflow().size());
			verifyTreeConsistency(tree, 0, 1, 2, 4, 5);
		}

		@Test
		@DisplayName("a steal between leaves of different physical lengths moves every bucket")
		void shouldRebalanceWhenDonorAndReceiverHaveDifferentPhysicalLengths() {
			// small blocks so a handful of removals force a real steal / merge, and the two leaves reach it with
			// arrays grown to different lengths because they were filled at different times
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			for (int i = 0; i < 24; i++) {
				tree.addRecord(i, i * 10);
			}
			// promote a scattering of buckets so the overflow column takes part in the rebalance too
			for (int i = 0; i < 24; i += 3) {
				tree.addRecord(i, i * 10 + 1);
			}
			verifyTreeConsistency(
				tree, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23);

			for (int i = 0; i < 24; i += 2) {
				tree.removeRecord(i, i * 10, i * 10 + 1);
			}
			verifyTreeConsistency(tree, 1, 3, 5, 7, 9, 11, 13, 15, 17, 19, 21, 23);
			for (final BPlusLeafTreeNode<Integer> leaf : tree.enumerateLeaves()) {
				assertEquals(
					leaf.getPeek() + 1, leaf.getKeyColumn().size(), "a rebalance must leave the columns aligned");
				assertEquals(leaf.getPeek() + 1, leaf.getRecords().size());
				if (leaf.getOverflow() != null) {
					assertEquals(leaf.getPeek() + 1, leaf.getOverflow().size());
				}
			}
			for (int i = 1; i < 24; i += 2) {
				assertArrayEquals(
					i % 3 == 0 ? new int[]{i * 10, i * 10 + 1} : new int[]{i * 10}, recordsOf(tree, i),
					"records lost at value " + i
				);
			}
		}

		@Test
		@DisplayName("a commit that changes nothing keeps every leaf by identity")
		@Tag(TRANSACTION)
		void shouldKeepEveryLeafByIdentityWhenACommitChangesNothing() {
			final TreeTuple prepared = prepareRandomTree(4_242L, 300);
			final List<BPlusLeafTreeNode<Integer>> before = prepared.tree().enumerateLeaves();
			assertStateAfterCommit(
				prepared.tree(),
				tree -> {
					// deliberately no mutation: the trim at the commit merge must be reached only where a new
					// committed leaf is being built anyway, never on the untouched fast path
				},
				(original, committed) -> {
					final List<BPlusLeafTreeNode<Integer>> after = committed.enumerateLeaves();
					assertEquals(before.size(), after.size());
					for (int i = 0; i < before.size(); i++) {
						assertSame(
							before.get(i), after.get(i),
							"a no-op commit must not rebuild - and therefore must not trim - leaf " + i
						);
					}
				}
			);
		}

		@Test
		@DisplayName("the commit merge gives back the slack of a leaf that has drained")
		@Tag(TRANSACTION)
		void shouldTrimTheColumnsOfADrainedLeafWhenTheCommitMergeRebuildsIt() {
			// one leaf grown to sixteen buckets, then drained to three inside a transaction: at commit the rebuilt
			// leaf carries columns sized to what is left rather than to the high-water mark
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(255, Integer.class);
			for (int i = 0; i < 16; i++) {
				tree.addRecord(i, i * 10);
			}
			final long grownBytes = columnBytesOf(tree.enumerateLeaves().get(0));

			assertStateAfterCommit(
				tree,
				t -> {
					for (int i = 3; i < 16; i++) {
						t.removeRecord(i, i * 10);
					}
				},
				(original, committed) -> {
					final BPlusLeafTreeNode<Integer> leaf = committed.enumerateLeaves().get(0);
					assertEquals(3, leaf.size(), "three buckets must survive");
					assertEquals(3, leaf.getKeyColumn().size());
					assertEquals(255, leaf.getKeyColumn().capacity(), "trimming never moves the logical capacity");
					assertTrue(
						columnBytesOf(leaf) < grownBytes,
						"the commit merge must give the drained leaf's slack back - was "
							+ columnBytesOf(leaf) + ", grown to " + grownBytes
					);
					verifyTreeConsistency(committed, 0, 1, 2);
				}
			);
		}

		@Test
		@DisplayName("a growth inside a savepoint never reaches the committed columns")
		@Tag(TRANSACTION)
		void shouldLeaveTheCommittedColumnsUntouchedWhenASavepointGrowsALeaf() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(255, Integer.class);
			tree.addRecord(10, 100);

			final BPlusLeafTreeNode<Integer> committedLeaf = tree.enumerateLeaves().get(0);
			final ValueColumn<Integer> committedKeys = committedLeaf.getKeyColumn();
			final RecordColumn committedRecords = committedLeaf.getRecords();
			final long committedBytes = columnBytesOf(committedLeaf);
			final int committedSize = committedKeys.size();

			// five more distinct values force at least one reallocation of the four-slot backing arrays
			assertSavepointRollbackRestores(
				tree,
				t -> {
				},
				t -> {
					final TreeMap<Integer, String> content = new TreeMap<>();
					final BucketCursor<Integer> cursor = t.cursor();
					while (cursor.next()) {
						content.put(cursor.value(), cursor.records().toString());
					}
					return content;
				},
				t -> {
					for (int i = 0; i < 5; i++) {
						t.addRecord(20 + i, 200 + i);
					}
				}
			);

			assertSame(committedKeys, committedLeaf.getKeyColumn(), "the committed key column must not be replaced");
			assertSame(committedRecords, committedLeaf.getRecords());
			assertEquals(committedSize, committedKeys.size(), "the committed column must not have grown");
			assertEquals(
				committedBytes, columnBytesOf(committedLeaf),
				"a grow inside a savepoint must reallocate the LAYER's array, never the committed one"
			);
			assertEquals(10, committedKeys.keyAt(0), "the committed content must be exactly what it was");
		}

		@Test
		@DisplayName("a leaf whose columns have fallen out of step with its peek is refused")
		void shouldRefuseALeafWhoseColumnsHaveFallenOutOfStepWithItsPeek() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(255, Integer.class);
			for (int i = 0; i < 5; i++) {
				tree.addRecord(i, i * 10);
			}
			final BPlusLeafTreeNode<Integer> leaf = tree.enumerateLeaves().get(0);

			// outside a transaction `setPeek` takes the `layer == null` arm, and an UPWARD move raises `peek` without
			// growing a single column - the one shape that reaches the invariant deterministically through a public
			// method. The leaf is left corrupt because `peek` is assigned before the check runs, so this tree must
			// not be reused after the assertions below
			final GenericEvitaInternalError exception = assertThrows(
				GenericEvitaInternalError.class,
				() -> leaf.setPeek(leaf.getPeek() + 1),
				"a leaf whose peek runs ahead of every column must never be published"
			);
			final String message = exception.getMessage();
			assertTrue(message.contains("peek + 1 == 6"), "the report must name the expected run, got: " + message);
			assertTrue(message.contains("keys=5"), "the report must name the key column, got: " + message);
			assertTrue(message.contains("records=5"), "the report must name the record column, got: " + message);
			assertTrue(message.contains("valueIds=n/a"), "the report must name the id column, got: " + message);
			assertTrue(message.contains("overflow=n/a"), "the report must name the overflow column, got: " + message);
		}

		@Test
		@DisplayName("a cursor over a leaf whose peek runs ahead of its columns walks only what is live")
		void shouldBoundTheCursorByTheColumnLiveRunWhenALeafPeekRunsAhead() {
			// a bulk-loaded page sizes every column EXACTLY to the page, so a read one slot past the live run really
			// does run off the end of the backing array - which is what makes the bound below an assertion rather
			// than a formality. This is the state a session-free management or statistics reader can observe while a
			// warm-up bulk load is still growing a column the leaf's `peek` has already moved past
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(255, Integer.class);
			tree.bulkLoadPage(new Object[]{10, 20, 30}, new long[]{1, 2, 3}, null, 3);
			final BPlusLeafTreeNode<Integer> leaf = tree.enumerateLeaves().get(0);

			// the invariant refuses the move, but `peek` is already assigned by the time it does, so the leaf stays
			// in exactly the torn shape the cursors have to survive
			assertThrows(GenericEvitaInternalError.class, () -> leaf.setPeek(leaf.getPeek() + 1));
			assertEquals(3, leaf.getPeek(), "the fixture must leave peek one slot ahead of the columns");
			assertEquals(3, leaf.getKeyColumn().size(), "the columns must be the exactly-sized ones the page built");

			assertEquals(3, tree.recordCount(), "the record count must stop at the column's own live run");
			final List<Integer> forward = new ArrayList<>();
			final BucketCursor<Integer> cursor = tree.cursor();
			while (cursor.next()) {
				forward.add(cursor.value());
			}
			assertEquals(List.of(10, 20, 30), forward, "the forward walk must stop at the live run");

			final List<Integer> reverse = new ArrayList<>();
			final BucketCursor<Integer> reverseCursor = tree.reverseCursor();
			while (reverseCursor.next()) {
				reverse.add(reverseCursor.value());
			}
			assertEquals(List.of(30, 20, 10), reverse, "the reverse walk must stop at the live run");
		}

		@Test
		@DisplayName("a steal whose donor overflow column is short is refused rather than demoting the stolen bucket")
		void shouldRefuseAShortDonorOverflowColumnOnASteal() {
			// a `null` in the overflow column is not an empty slot - it IS the leaf's single/multi discriminator. A
			// donor whose overflow column is shorter than the range its key column donates would hand the receiver
			// every multi bucket in the shortfall marked single, keeping one record out of each bucket's whole set.
			// The key column cannot catch it: the two are driven with the same range, the key column is copied first,
			// and it is intact - so the overflow column has to answer for itself
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(
				9, 4, 3, 1, Integer.class, null
			);
			for (int i = 0; i < 12; i++) {
				tree.addRecord(i, i * 10);
			}
			final List<BPlusLeafTreeNode<Integer>> leaves = tree.enumerateLeaves();
			assertTrue(leaves.size() >= 2, "the fixture needs two sibling leaves");
			final BPlusLeafTreeNode<Integer> donor = leaves.get(0);
			final BPlusLeafTreeNode<Integer> receiver = leaves.get(1);
			assertTrue(
				receiver.size() < receiver.capacity(),
				"the receiver must have room for the bucket it steals"
			);

			// promote the donor's LAST bucket - the one a steal-from-left takes - to a multi one, which is also what
			// allocates the donor's overflow column in the first place
			tree.addRecord(donor.keyAt(donor.size() - 1), 9_000);
			final OverflowColumn donorOverflow = donor.getOverflow();
			assertNotNull(donorOverflow, "promoting a bucket must have given the donor an overflow column");
			assertEquals(donor.size(), donorOverflow.size(), "the fixture starts from an aligned donor");

			// one slot short of the range the donor's key column is about to donate
			donorOverflow.fillEmpty(donorOverflow.size() - 1, donorOverflow.size());

			// the steal is abandoned part-way, which leaves this tree unusable - that is what an internal error
			// means, and nothing below reads the tree again
			final GenericEvitaInternalError exception = assertThrows(
				GenericEvitaInternalError.class, () -> receiver.stealFromLeft(1, donor),
				"a donor that cannot answer for the bucket it donates must not be stolen from"
			);
			assertTrue(
				exception.getMessage().contains("Overflow column source range"),
				"the overflow column must be the one that refuses, got: " + exception.getMessage()
			);
		}

		@Test
		@DisplayName("creating a layer over a misaligned leaf is refused before the committed column is touched")
		void shouldRefuseToCreateALayerOverAMisalignedLeaf() {
			// `createLayer()` passes the leaf's own columns as BOTH origin and target of the split-copy constructor,
			// so the copy and the size-authoritative fill behind it rewrite the committed leaf in place. They are
			// inert only while every column's live run is exactly `peek + 1`, which is why the leaf has to be
			// refused before the first of them runs rather than after the last
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(255, Integer.class);
			for (int i = 0; i < 5; i++) {
				tree.addRecord(i, i * 10);
			}
			final BPlusLeafTreeNode<Integer> leaf = tree.enumerateLeaves().get(0);
			final ValueColumn<Integer> committedKeys = leaf.getKeyColumn();

			// one key appended straight onto the committed column, past the run the leaf's peek covers
			committedKeys.insertKeyAt(committedKeys.size(), 99);
			assertEquals(6, committedKeys.size(), "the fixture must leave the key column one slot longer than peek");
			assertEquals(4, leaf.getPeek());

			final GenericEvitaInternalError exception = assertThrows(
				GenericEvitaInternalError.class, leaf::createLayer,
				"a layer must never be created over a leaf whose columns disagree with its peek"
			);
			final String message = exception.getMessage();
			assertTrue(message.contains("peek + 1 == 5"), "the report must name the expected run, got: " + message);
			assertTrue(message.contains("keys=6"), "the report must name the key column, got: " + message);
			assertEquals(
				6, committedKeys.size(),
				"a refused layer must leave the committed key column exactly as it found it"
			);
		}

		@Test
		@DisplayName("a savepoint that is committed keeps the growth it caused")
		@Tag(TRANSACTION)
		void shouldKeepTheGrowthWhenASavepointIsCommitted() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(255, Integer.class);
			tree.addRecord(10, 100);

			assertSavepointCommitKeeps(
				tree,
				t -> {
				},
				t -> {
					final TreeMap<Integer, String> content = new TreeMap<>();
					final BucketCursor<Integer> cursor = t.cursor();
					while (cursor.next()) {
						content.put(cursor.value(), cursor.records().toString());
					}
					return content;
				},
				t -> {
					for (int i = 0; i < 5; i++) {
						t.addRecord(20 + i, 200 + i);
					}
				}
			);
		}

		/**
		 * Builds a throw-away tree whose single leaf holds exactly `count` buckets, and returns the bytes its two
		 * mandatory columns occupy. Growth doubles from a floor of four, so a run of `count` inserts lands on a
		 * backing array of exactly `count` slots whenever `count` is a power of two at or above the floor — which
		 * makes this the footprint a leaf with `count`-slot columns has, and the expectation the physical-length
		 * assertions below compare against.
		 *
		 * @param count how many buckets the reference leaf holds; a power of two at or above four
		 * @return the bytes a leaf with `count`-slot columns occupies
		 */
		private static long columnBytesOfLeafSizedTo(int count) {
			final TransactionalBucketBPlusTree<Integer> reference =
				new TransactionalBucketBPlusTree<>(255, Integer.class);
			for (int i = 0; i < count; i++) {
				reference.addRecord(i, i);
			}
			return columnBytesOf(reference.enumerateLeaves().get(0));
		}

		/**
		 * Builds a tree holding the eight buckets `0, 10, 20 .. 70`, which leaves its single leaf's columns EXACTLY
		 * full: growth runs 4 -> 8 and the eighth insert still fits, so both backing arrays end at eight slots for
		 * eight live buckets. That is the shape both halves of every split are born in, and the shape every page
		 * replayed from disk arrives in.
		 *
		 * @return the populated tree
		 */
		@Nonnull
		private static TransactionalBucketBPlusTree<Integer> exactlyFullLeafTree() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(255, Integer.class);
			for (int i = 0; i < 8; i++) {
				tree.addRecord(i * 10, i);
			}
			return tree;
		}

		@Test
		@DisplayName("an exactly-full committed leaf is decoupled with room for the insert that follows")
		@Tag(TRANSACTION)
		void shouldDecoupleAnExactlyFullLeafWithRoomForTheInsert() {
			// copying an exactly-full column at its short length and growing it on the very next insert is two
			// allocations where one would do. The decouple's insert path therefore copies straight to
			// grownLength(8, 9, 255) == 16, which is what the layer's footprint below has to show
			final TransactionalBucketBPlusTree<Integer> tree = exactlyFullLeafTree();
			final BPlusLeafTreeNode<Integer> committed = tree.enumerateLeaves().get(0);
			final ValueColumn<Integer> committedKeys = committed.getKeyColumn();
			final RecordColumn committedRecords = committed.getRecords();
			final long committedBytes = columnBytesOf(committedKeys, committedRecords);
			assertEquals(8, committedKeys.size(), "the fixture must leave the columns exactly full");
			assertEquals(columnBytesOfLeafSizedTo(8), committedBytes, "eight buckets must occupy eight slots");
			// measured OUTSIDE the transaction on purpose: the reference tree is never committed, so building it in
			// here would leave its leaf's layer behind and the commit would refuse the whole transaction as stale
			final long sixteenSlotBytes = columnBytesOfLeafSizedTo(16);

			assertStateAfterCommit(
				tree,
				t -> {
					t.addRecord(1, 101);

					final BPlusLeafTreeNode<Integer> layer =
						Transaction.getTransactionalMemoryLayerIfExists(committed);
					assertNotNull(layer, "the insert must have created a layer over the committed leaf");

					// the committed leaf is untouched. Its columns are read through the references captured before
					// the transaction, because the leaf's own getters are transaction-aware and answer for the layer
					assertNotSame(committedKeys, layer.getKeyColumn(), "the layer must decouple its key column");
					assertNotSame(committedRecords, layer.getRecords(), "the layer must decouple its record column");
					assertEquals(8, committedKeys.size(), "the committed leaf must not observe the layer's insert");
					assertEquals(
						committedBytes, columnBytesOf(committedKeys, committedRecords),
						"the committed columns must never be grown"
					);

					// the layer landed at sixteen slots, and stays there for the seven inserts that fill them
					final long layerBytes = columnBytesOf(layer);
					assertEquals(
						sixteenSlotBytes, layerBytes,
						"the decoupled columns must be grownLength(8, 9, 255) == 16 slots long"
					);
					assertTrue(layerBytes > committedBytes, "the layer must be physically longer than the base");
					for (int key = 2; key <= 8; key++) {
						t.addRecord(key, 100 + key);
						assertEquals(
							layerBytes, columnBytesOf(layer),
							"no insert up to the sixteenth bucket may reallocate, failed at key " + key
						);
					}
					assertEquals(16, layer.size(), "the layer must now hold sixteen buckets");
				},
				(t, committedTree) -> {
					// back outside the transaction the leaf's getters answer for the BASE again, and the base is the
					// pre-transaction tree — so this is where the committed columns' identity can actually be read
					assertSame(committedKeys, committed.getKeyColumn(), "the base key column was replaced");
					assertSame(committedRecords, committed.getRecords(), "the base record column was replaced");
					assertEquals(committedBytes, columnBytesOf(committed), "the base kept its eight-slot columns");

					// the merged leaf holds every bucket the fixture and the transaction contributed between them
					verifyTreeConsistency(committedTree, 0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 20, 30, 40, 50, 60, 70);
					assertArrayEquals(new int[]{0}, recordsOf(committedTree, 0));
					assertArrayEquals(new int[]{101}, recordsOf(committedTree, 1));
					assertArrayEquals(new int[]{108}, recordsOf(committedTree, 8));
					assertArrayEquals(new int[]{7}, recordsOf(committedTree, 70));
				}
			);
		}

		@Test
		@DisplayName("an add that joins an existing bucket decouples an exactly-full leaf verbatim")
		@Tag(TRANSACTION)
		void shouldDecoupleAnExactlyFullLeafVerbatimWhenAnAddJoinsAnExistingBucket() {
			// the headroom is for a bucket that is actually being added. An add landing on a key the leaf already
			// holds gains no slot, and taking the headroom for it would grow an exactly-full leaf's columns for
			// good — the commit merge's 4:1 trim gap never reclaims a single doubling. On the reduced trees this
			// work exists for, where a four-key leaf sits on four-slot arrays, that is the whole prize given back
			final TransactionalBucketBPlusTree<Integer> tree = exactlyFullLeafTree();
			final BPlusLeafTreeNode<Integer> committed = tree.enumerateLeaves().get(0);
			final ValueColumn<Integer> committedKeys = committed.getKeyColumn();
			final RecordColumn committedRecords = committed.getRecords();
			final long committedBytes = columnBytesOf(committedKeys, committedRecords);

			assertStateAfterCommit(
				tree,
				t -> {
					// a second record on a key the fixture already holds. The bucket is promoted to a multi one,
					// which legitimately materializes the overflow column — but the key and record columns, which
					// are the two `columnBytesOf` measures, must not move a slot
					t.addRecord(70, 999);

					final BPlusLeafTreeNode<Integer> layer =
						Transaction.getTransactionalMemoryLayerIfExists(committed);
					assertNotNull(layer, "the add must have created a layer over the committed leaf");
					assertNotSame(committedKeys, layer.getKeyColumn(), "a join must still decouple");
					assertEquals(
						committedBytes, columnBytesOf(layer),
						"a join adds no key, so its decouple must copy the columns verbatim"
					);
					assertEquals(8, layer.size(), "the layer must still hold exactly eight buckets");
				},
				(t, committedTree) -> {
					assertSame(committedKeys, committed.getKeyColumn(), "the base key column was replaced");
					assertSame(committedRecords, committed.getRecords(), "the base record column was replaced");
					assertEquals(committedBytes, columnBytesOf(committed), "the base kept its eight-slot columns");
					assertEquals(
						committedBytes, columnBytesOf(committedTree.enumerateLeaves().get(0)),
						"the merged leaf must carry the committed physical length rather than a doubling"
					);
					verifyTreeConsistency(committedTree, 0, 10, 20, 30, 40, 50, 60, 70);
					assertArrayEquals(new int[]{7, 999}, recordsOf(committedTree, 70));
				}
			);
		}

		@Test
		@DisplayName("a join followed by a new key still ends at the grown length with every value present")
		@Tag(TRANSACTION)
		void shouldGrowOnceWhenAJoinIsFollowedByANewKey() {
			final TransactionalBucketBPlusTree<Integer> tree = exactlyFullLeafTree();
			final BPlusLeafTreeNode<Integer> committed = tree.enumerateLeaves().get(0);
			final long committedBytes = columnBytesOf(committed);
			// measured outside the transaction: a reference tree built in here would leave its leaf's layer unswept
			final long sixteenSlotBytes = columnBytesOfLeafSizedTo(16);

			assertStateAfterCommit(
				tree,
				t -> {
					t.addRecord(70, 999);

					final BPlusLeafTreeNode<Integer> layer =
						Transaction.getTransactionalMemoryLayerIfExists(committed);
					assertNotNull(layer, "the add must have created a layer over the committed leaf");
					assertEquals(committedBytes, columnBytesOf(layer), "the join must leave the columns at eight");

					// the new key has to grow them itself. The columns are already decoupled by the join, so the
					// headroom copy is no longer on offer and the ordinary geometric growth takes over — one
					// reallocation, landing on the same sixteen slots a bare insert would have reached
					t.addRecord(1, 101);
					assertEquals(sixteenSlotBytes, columnBytesOf(layer), "the new key must grow the columns to 16");
					assertEquals(9, layer.size(), "the layer must hold nine buckets");
				},
				(t, committedTree) -> {
					verifyTreeConsistency(committedTree, 0, 1, 10, 20, 30, 40, 50, 60, 70);
					assertArrayEquals(new int[]{101}, recordsOf(committedTree, 1));
					assertArrayEquals(new int[]{7, 999}, recordsOf(committedTree, 70));
				}
			);
		}

		@Test
		@DisplayName("a removal decouples an exactly-full leaf verbatim, with no headroom")
		@Tag(TRANSACTION)
		void shouldDecoupleAnExactlyFullLeafVerbatimForARemoval() {
			// the headroom is for an insert and nothing else: a decouple whose mutation is about to SHRINK the
			// columns would be over-allocating for slots that mutation is giving back
			final TransactionalBucketBPlusTree<Integer> tree = exactlyFullLeafTree();
			final BPlusLeafTreeNode<Integer> committed = tree.enumerateLeaves().get(0);
			final ValueColumn<Integer> committedKeys = committed.getKeyColumn();
			final RecordColumn committedRecords = committed.getRecords();
			final long committedBytes = columnBytesOf(committedKeys, committedRecords);

			assertStateAfterCommit(
				tree,
				t -> {
					t.removeRecord(70, 7);

					final BPlusLeafTreeNode<Integer> layer =
						Transaction.getTransactionalMemoryLayerIfExists(committed);
					assertNotNull(layer, "the removal must have created a layer over the committed leaf");
					assertNotSame(committedKeys, layer.getKeyColumn(), "a removal must still decouple");
					assertEquals(
						committedBytes, columnBytesOf(layer),
						"a decouple for a removal must copy the columns verbatim"
					);
					assertEquals(7, layer.size(), "the layer must have dropped the bucket");
					assertEquals(8, committedKeys.size(), "the committed leaf must not observe the removal");
					assertEquals(
						committedBytes, columnBytesOf(committedKeys, committedRecords),
						"the committed columns must never be touched"
					);
				},
				(t, committedTree) -> {
					assertSame(committedKeys, committed.getKeyColumn(), "the base key column was replaced");
					assertSame(committedRecords, committed.getRecords(), "the base record column was replaced");
					assertEquals(committedBytes, columnBytesOf(committed), "the base kept its eight-slot columns");
					verifyTreeConsistency(committedTree, 0, 10, 20, 30, 40, 50, 60);
				}
			);
		}
	}

	/**
	 * Pins the internal-node half of the content-sized storage design. An internal node now allocates four key slots
	 * and four child slots instead of a whole block, sizes a split product to the half it copied, grows on demand and
	 * gives the slack back at the commit merge — the same rule the leaf columns follow.
	 *
	 * The dangerous half is `isFull()`. It asks whether `peek` has reached the **block size**, and the moment it goes
	 * back to asking whether `peek` has reached `children.length - 1` a four-slot node reports itself full holding
	 * three children and splits a node that holds two. That is the exact mirror of the silent failure the leaf's own
	 * content-sizing tests guard, so the cases below always assert the node's content alongside its shape.
	 */
	@Nested
	@DisplayName("Content-sized internal nodes behind a logical block size")
	@Tag(INDEXING)
	class ContentSizedInternalNodes {

		/**
		 * Leaf and internal block size of the fixtures that must not split an internal node. The tree refuses an
		 * internal block above the leaf block and refuses an even one, and derives nothing here — both are given
		 * explicitly so the numbers below read as the ones the node actually holds.
		 */
		private static final int WIDE_BLOCK = 127;

		/**
		 * Collects every internal node of the tree in pre-order.
		 *
		 * @param tree the tree to walk
		 * @return the internal nodes, root first
		 */
		@Nonnull
		private static List<BPlusInternalTreeNode<Integer>> internalNodesOf(
			@Nonnull TransactionalBucketBPlusTree<Integer> tree
		) {
			final List<BPlusInternalTreeNode<Integer>> nodes = new ArrayList<>();
			collectInternalNodes(tree.getRoot(), nodes);
			return nodes;
		}

		/**
		 * Recursively adds `node` and its internal descendants to `out`.
		 *
		 * @param node the subtree root
		 * @param out  the accumulator
		 */
		private static void collectInternalNodes(
			@Nonnull BPlusTreeNode<Integer, ?> node, @Nonnull List<BPlusInternalTreeNode<Integer>> out
		) {
			if (node instanceof BPlusInternalTreeNode<?> internal) {
				@SuppressWarnings("unchecked") final BPlusInternalTreeNode<Integer> internalNode =
					(BPlusInternalTreeNode<Integer>) internal;
				out.add(internalNode);
				final BPlusTreeNode<Integer, ?>[] children = internalNode.getChildren();
				for (int i = 0; i <= internalNode.getPeek(); i++) {
					collectInternalNodes(children[i], out);
				}
			}
		}

		/**
		 * Returns the ascending key array `0 .. count - 1`, the content every fixture below inserts.
		 *
		 * @param count how many keys the tree holds
		 * @return the expected sorted key array
		 */
		@Nonnull
		private static int[] ascendingKeys(int count) {
			final int[] keys = new int[count];
			for (int i = 0; i < count; i++) {
				keys[i] = i;
			}
			return keys;
		}

		@Test
		@DisplayName("an internal node whose arrays are full but whose block is not does not split")
		void shouldNotSplitAnInternalNodeWhoseArraysAreShorterThanTheBlockSize() {
			final TransactionalBucketBPlusTree<Integer> tree =
				new TransactionalBucketBPlusTree<>(WIDE_BLOCK, 63, WIDE_BLOCK, 63, Integer.class, null);

			// insert until the root's child array is exactly filled - the one occupancy at which reading fullness off
			// the array length rather than off the block size would answer "full" and split a node holding four
			// children out of a hundred and twenty-eight
			int inserted = 0;
			BPlusInternalTreeNode<Integer> root = null;
			while (root == null) {
				tree.addRecord(inserted, inserted * 10);
				inserted++;
				assertTrue(inserted < 100_000, "the fixture never filled the root's child array");
				if (tree.getRoot() instanceof BPlusInternalTreeNode<?> internal) {
					@SuppressWarnings("unchecked") final BPlusInternalTreeNode<Integer> candidate =
						(BPlusInternalTreeNode<Integer>) internal;
					if (candidate.getPeek() + 1 == candidate.getChildren().length) {
						root = candidate;
					}
				}
			}

			assertTrue(
				root.getChildren().length < WIDE_BLOCK + 1,
				"the fixture must reach a full array well below the block size, was " + root.getChildren().length
			);
			assertFalse(
				root.isFull(),
				"a node whose ARRAY is full but whose BLOCK is not must never report itself full - it holds "
					+ (root.getPeek() + 1) + " of " + (WIDE_BLOCK + 1) + " children"
			);
			assertEquals(root.getPeek(), root.keyCount(), "the separator count is one below the child count");
			assertTrue(
				root.getKeys().length >= root.keyCount(),
				"the key array must cover every separator the node holds"
			);
			verifyTreeConsistency(tree, ascendingKeys(inserted));
		}

		@Test
		@DisplayName("a root born from the first split allocates the floor, not the block")
		void shouldAllocateAFreshRootAtTheFloorRatherThanTheBlockSize() {
			final TransactionalBucketBPlusTree<Integer> tree =
				new TransactionalBucketBPlusTree<>(WIDE_BLOCK, 63, WIDE_BLOCK, 63, Integer.class, null);

			int inserted = 0;
			while (tree.getRoot() instanceof BPlusLeafTreeNode<?>) {
				tree.addRecord(inserted, inserted * 10);
				inserted++;
				assertTrue(inserted < 100_000, "the fixture never split the root leaf");
			}

			@SuppressWarnings("unchecked") final BPlusInternalTreeNode<Integer> root =
				(BPlusInternalTreeNode<Integer>) tree.getRoot();
			assertEquals(1, root.getPeek(), "a root born from one split separates exactly two children");
			assertEquals(1, root.keyCount());
			assertEquals(4, root.getChildren().length, "a two-child root allocates the four-slot floor");
			assertEquals(4, root.getKeys().length, "and its separator array follows the same floor");
			assertFalse(root.isFull());
			verifyTreeConsistency(tree, ascendingKeys(inserted));
		}

		@Test
		@DisplayName("an internal node grows on demand and keeps every child across the growth")
		void shouldGrowAnInternalNodeOnDemandAndKeepEveryChild() {
			final TransactionalBucketBPlusTree<Integer> tree =
				new TransactionalBucketBPlusTree<>(WIDE_BLOCK, 63, WIDE_BLOCK, 63, Integer.class, null);

			int inserted = 0;
			BPlusInternalTreeNode<Integer> root = null;
			while (root == null) {
				tree.addRecord(inserted, inserted * 10);
				inserted++;
				assertTrue(inserted < 100_000, "the fixture never drove the root past the four-slot floor");
				if (tree.getRoot() instanceof BPlusInternalTreeNode<?> internal) {
					@SuppressWarnings("unchecked") final BPlusInternalTreeNode<Integer> candidate =
						(BPlusInternalTreeNode<Integer>) internal;
					if (candidate.getPeek() + 1 > ColumnSizing.MIN_PHYSICAL_LENGTH) {
						root = candidate;
					}
				}
			}

			assertTrue(
				root.getChildren().length > ColumnSizing.MIN_PHYSICAL_LENGTH,
				"the child array must have grown past the floor, was " + root.getChildren().length
			);
			assertTrue(
				root.getChildren().length <= WIDE_BLOCK + 1, "growth must never overshoot the logical block size");
			assertEquals(root.getPeek(), root.keyCount());
			assertTrue(root.getKeys().length >= root.keyCount());
			for (int i = 0; i <= root.getPeek(); i++) {
				assertNotNull(root.getChildren()[i], "a grown node must not lose the child at slot " + i);
			}
			verifyTreeConsistency(tree, ascendingKeys(inserted));
		}

		@Test
		@DisplayName("both halves of an internal split are sized to the half they copied")
		void shouldSizeASplitInternalNodeToTheHalfItCopied() {
			// a nine-key internal block splits after ten children, which a handful of leaves reaches - and the origin
			// is at the block size by then, so the halves being shorter is a real comparison rather than a tautology
			final TransactionalBucketBPlusTree<Integer> tree =
				new TransactionalBucketBPlusTree<>(9, 4, 9, 4, Integer.class, null);

			int inserted = 0;
			BPlusTreeNode<Integer, ?> previousRoot = tree.getRoot();
			BPlusInternalTreeNode<Integer> splitOrigin = null;
			while (splitOrigin == null) {
				tree.addRecord(inserted, inserted * 10);
				inserted++;
				assertTrue(inserted < 100_000, "the fixture never split an internal node");
				final BPlusTreeNode<Integer, ?> currentRoot = tree.getRoot();
				if (currentRoot != previousRoot && previousRoot instanceof BPlusInternalTreeNode<?> internal) {
					@SuppressWarnings("unchecked") final BPlusInternalTreeNode<Integer> origin =
						(BPlusInternalTreeNode<Integer>) internal;
					splitOrigin = origin;
				}
				previousRoot = currentRoot;
			}

			@SuppressWarnings("unchecked") final BPlusInternalTreeNode<Integer> newRoot =
				(BPlusInternalTreeNode<Integer>) tree.getRoot();
			assertEquals(1, newRoot.getPeek(), "the root the split installed separates the two halves");

			for (int half = 0; half <= 1; half++) {
				@SuppressWarnings("unchecked") final BPlusInternalTreeNode<Integer> product =
					(BPlusInternalTreeNode<Integer>) newRoot.getChildren()[half];
				assertEquals(
					product.getPeek() + 1, product.getChildren().length,
					"half " + half + " must be sized exactly to the children it copied"
				);
				assertTrue(
					product.getChildren().length < splitOrigin.getChildren().length,
					"half " + half + " must not inherit the origin's full-block child array"
				);
				assertTrue(
					product.getKeys().length < splitOrigin.getKeys().length,
					"half " + half + " must not inherit the origin's full-block separator array"
				);
				assertFalse(product.isFull(), "a half-full split product must not report itself full");
			}
			verifyTreeConsistency(tree, ascendingKeys(inserted));
		}

		@Test
		@DisplayName("the commit merge gives back the slack of an internal node that has drained")
		@Tag(TRANSACTION)
		void shouldTrimADrainedInternalNodeWhenTheCommitMergeRebuildsIt() {
			final TransactionalBucketBPlusTree<Integer> tree =
				new TransactionalBucketBPlusTree<>(31, 15, 31, 15, Integer.class, null);

			// grow the root well past the four-slot floor so a drained node has real slack to give back
			int inserted = 0;
			BPlusInternalTreeNode<Integer> root = null;
			while (root == null) {
				tree.addRecord(inserted, inserted * 10);
				inserted++;
				assertTrue(inserted < 100_000, "the fixture never grew the root to eighteen children");
				if (tree.getRoot() instanceof BPlusInternalTreeNode<?> internal) {
					@SuppressWarnings("unchecked") final BPlusInternalTreeNode<Integer> candidate =
						(BPlusInternalTreeNode<Integer>) internal;
					if (candidate.getPeek() + 1 >= 18) {
						root = candidate;
					}
				}
			}
			final int grownChildren = root.getChildren().length;
			final int grownKeys = root.getKeys().length;
			final int survivors = 32;
			final int total = inserted;

			assertStateAfterCommit(
				tree,
				t -> {
					for (int i = survivors; i < total; i++) {
						t.removeRecord(i, i * 10);
					}
				},
				(original, committed) -> {
					assertInstanceOf(
						BPlusInternalTreeNode.class, committed.getRoot(),
						"thirty-two values cannot fit one thirty-one-bucket leaf, so the spine must survive the drain"
					);
					@SuppressWarnings("unchecked") final BPlusInternalTreeNode<Integer> committedRoot =
						(BPlusInternalTreeNode<Integer>) committed.getRoot();
					assertTrue(
						committedRoot.getChildren().length < grownChildren,
						"the commit merge must give the drained node's child slack back - was "
							+ committedRoot.getChildren().length + ", grown to " + grownChildren
					);
					assertTrue(
						committedRoot.getKeys().length < grownKeys,
						"and its separator slack with it - was " + committedRoot.getKeys().length
							+ ", grown to " + grownKeys
					);
					assertTrue(
						committedRoot.getChildren().length >= committedRoot.getPeek() + 1,
						"a trim must never cut into the children the node still holds"
					);
					assertEquals(committedRoot.getPeek(), committedRoot.keyCount());
					verifyTreeConsistency(committed, ascendingKeys(survivors));
				}
			);
		}

		@Test
		@DisplayName("a commit that changes nothing keeps every internal node by identity")
		@Tag(TRANSACTION)
		void shouldKeepEveryInternalNodeByIdentityWhenACommitChangesNothing() {
			final TreeTuple prepared = prepareRandomTree(4_711L, 300);
			final List<BPlusInternalTreeNode<Integer>> before = internalNodesOf(prepared.tree());
			assertFalse(before.isEmpty(), "the fixture must build a multi-level spine");

			assertStateAfterCommit(
				prepared.tree(),
				tree -> {
					// deliberately no mutation: the trim at the commit merge must be reached only where a new
					// committed node is being built anyway, never on the untouched fast path
				},
				(original, committed) -> {
					final List<BPlusInternalTreeNode<Integer>> after = internalNodesOf(committed);
					assertEquals(before.size(), after.size());
					for (int i = 0; i < before.size(); i++) {
						assertSame(
							before.get(i), after.get(i),
							"a no-op commit must not rebuild - and therefore must not trim - internal node " + i
						);
					}
				}
			);
		}
	}
}
