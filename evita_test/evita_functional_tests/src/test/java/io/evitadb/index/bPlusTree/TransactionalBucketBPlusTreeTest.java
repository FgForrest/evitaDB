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

import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyReport;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.SingleRecordBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
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
		@DisplayName("does not demote a reduced multi bucket back to single")
		void shouldNotDemoteReducedMultiBucket() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			tree.addRecord(5, 100, 200);
			tree.removeRecord(5, 200);
			assertEquals(1, tree.cardinalityOf(5));
			// a demoted singleton stays a bitmap, NOT a SingleRecordBitmap
			assertFalse(
				tree.getRecordsEqualTo(5) instanceof SingleRecordBitmap,
				"A reduced multi bucket must stay a bitmap, not demote to a single!"
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
}
