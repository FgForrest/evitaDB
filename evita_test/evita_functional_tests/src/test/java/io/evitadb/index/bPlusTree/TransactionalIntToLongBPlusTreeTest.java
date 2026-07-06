/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyReport;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.TransactionalIntToLongBPlusTree.Entry;
import io.evitadb.index.reference.TransactionalReference;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies the correctness of the {@link TransactionalIntToLongBPlusTree} implementation. It covers insert,
 * search, upsert, delete, rebalancing (steal and merge), forward and reverse iteration of both keys and values,
 * transactional semantics, tree visualization, constructor validation, and the internal consistency oracle.
 * Bounded, fixed-seed randomized churn tests guard the rebalancing and commit machinery against regressions; the
 * open-ended generational soak test lives in the long-running test module.
 *
 * Values are primitive `long`; the convention throughout this test is that the value stored for key `k` is `k * 10`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Transactional int-to-long B+ tree")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class TransactionalIntToLongBPlusTreeTest {

	/**
	 * Computes the canonical primitive value stored for the given key.
	 *
	 * @param key the key
	 * @return the value associated with the key (`key * 10`)
	 */
	private static long valueOf(int key) {
		return key * 10L;
	}

	/**
	 * Verifies tree consistency by checking the internal consistency report and validating both forward and reverse
	 * value iterators against the expected key array.
	 *
	 * @param bPlusTree     the tree to verify
	 * @param expectedArray the expected sorted key array
	 */
	private static void verifyTreeConsistency(
		@Nonnull TransactionalIntToLongBPlusTree bPlusTree, @Nonnull int... expectedArray
	) {
		final ConsistencyReport consistencyReport = bPlusTree.getConsistencyReport();
		assertEquals(ConsistencyState.CONSISTENT, consistencyReport.state(), consistencyReport.report());
		verifyForwardValueIterator(bPlusTree, expectedArray);
		verifyReverseValueIterator(bPlusTree, expectedArray);
	}

	/**
	 * Iterates the tree forward and verifies that the values match the expected key array in ascending order.
	 *
	 * @param tree     the tree to iterate
	 * @param keyArray the expected sorted keys
	 */
	private static void verifyForwardValueIterator(
		@Nonnull TransactionalIntToLongBPlusTree tree, @Nonnull int... keyArray
	) {
		final long[] expectedArray = new long[keyArray.length];
		for (int i = 0; i < keyArray.length; i++) {
			expectedArray[i] = valueOf(keyArray[i]);
		}
		final long[] reconstructedArray = new long[expectedArray.length];
		int index = 0;
		final PrimitiveIterator.OfLong it = tree.valueIterator();
		while (it.hasNext()) {
			reconstructedArray[index++] = it.nextLong();
			assertEquals(expectedArray[index - 1], reconstructedArray[index - 1]);
		}

		assertArrayEquals(expectedArray, reconstructedArray, "Arrays are not equal!");
		assertThrows(NoSuchElementException.class, it::next, "Iterator should be exhausted!");
	}

	/**
	 * Iterates the tree in reverse and verifies that the values match the expected key array in descending order.
	 *
	 * @param tree     the tree to iterate
	 * @param keyArray the expected sorted keys
	 */
	private static void verifyReverseValueIterator(
		@Nonnull TransactionalIntToLongBPlusTree tree, @Nonnull int... keyArray
	) {
		final long[] expectedArray = new long[keyArray.length];
		for (int i = 0; i < keyArray.length; i++) {
			expectedArray[i] = valueOf(keyArray[i]);
		}
		final long[] reconstructedArray = new long[expectedArray.length];
		int index = expectedArray.length;
		final PrimitiveIterator.OfLong it = tree.valueReverseIterator();
		while (it.hasNext()) {
			reconstructedArray[--index] = it.nextLong();
			assertEquals(expectedArray[index], reconstructedArray[index]);
		}

		assertArrayEquals(expectedArray, reconstructedArray, "Arrays are not equal!");
		assertThrows(NoSuchElementException.class, it::next, "Iterator should be exhausted!");
	}

	/**
	 * Creates a random tree with default block sizes using the given seed and total element count.
	 *
	 * @param seed          the random seed for reproducibility
	 * @param totalElements the number of unique elements to insert
	 * @return a tuple of the tree and its sorted key array
	 */
	@Nonnull
	private static TreeTuple prepareRandomTree(long seed, int totalElements) {
		return prepareRandomTree(3, 1, 3, 1, seed, totalElements);
	}

	/**
	 * Creates a random tree with custom block sizes using the given seed and total element count.
	 *
	 * @param valueBlockSize      the leaf node block size
	 * @param minValueBlockSize   the minimum leaf node occupancy
	 * @param internalNodeSize    the internal node block size
	 * @param minInternalNodeSize the minimum internal node occupancy
	 * @param seed                the random seed
	 * @param totalElements       the number of unique elements
	 * @return a tuple of the tree and its sorted key array
	 */
	@Nonnull
	private static TreeTuple prepareRandomTree(
		int valueBlockSize,
		int minValueBlockSize,
		int internalNodeSize,
		int minInternalNodeSize,
		long seed,
		int totalElements
	) {
		final Random random = new Random(seed);
		final TransactionalIntToLongBPlusTree bPlusTree = new TransactionalIntToLongBPlusTree(
			valueBlockSize, minValueBlockSize, internalNodeSize, minInternalNodeSize
		);
		int[] plainArray = new int[0];
		do {
			final int i = random.nextInt(totalElements << 1);
			bPlusTree.insert(i, valueOf(i));
			plainArray = ArrayUtils.insertIntIntoOrderedArray(i, plainArray);
		} while (plainArray.length < totalElements);

		return new TreeTuple(bPlusTree, plainArray);
	}

	/**
	 * Deletes a key from the tree inside a transaction, verifies consistency before and after commit, and returns
	 * the committed tree.
	 *
	 * @param tree          the tree to delete from
	 * @param expectedArray reference to the current expected key array, updated after deletion
	 * @param keyToDelete   the key to delete
	 * @return the committed tree after deletion
	 */
	@Nonnull
	private static TransactionalIntToLongBPlusTree deleteAndVerify(
		@Nonnull TransactionalIntToLongBPlusTree tree,
		@Nonnull AtomicReference<int[]> expectedArray, int keyToDelete
	) {
		final AtomicReference<TransactionalIntToLongBPlusTree> result = new AtomicReference<>();
		assertStateAfterCommit(
			tree,
			tested -> tested.delete(keyToDelete),
			(original, committed) -> {
				verifyTreeConsistency(original, expectedArray.get());
				expectedArray.set(ArrayUtils.removeIntFromOrderedArray(keyToDelete, expectedArray.get()));
				verifyTreeConsistency(committed, expectedArray.get());
				result.set(committed);
			}
		);
		return result.get();
	}

	/**
	 * Holds a B+ tree together with its expected sorted key array for use in tests.
	 *
	 * @param bPlusTree  the tree under test
	 * @param plainArray the sorted array of inserted keys
	 */
	private record TreeTuple(
		@Nonnull TransactionalIntToLongBPlusTree bPlusTree,
		@Nonnull int[] plainArray
	) {

		/**
		 * Returns the total number of elements in the tree.
		 *
		 * @return the length of the plain array
		 */
		public int totalElements() {
			return this.plainArray.length;
		}

		/**
		 * Converts the key array to a value array where each element is `key * 10`.
		 *
		 * @return the long array of expected values
		 */
		@Nonnull
		public long[] asLongArray() {
			final long[] plainArrayAsLong = new long[this.plainArray.length];
			for (int i = 0; i < this.plainArray.length; i++) {
				plainArrayAsLong[i] = valueOf(this.plainArray[i]);
			}
			return plainArrayAsLong;
		}

	}

	@Nested
	@DisplayName("Insert operations")
	class InsertOperationsTest {

		@Test
		@DisplayName("overwrites value when inserting duplicate key")
		void shouldOverwriteDuplicateKeys() {
			final TransactionalIntToLongBPlusTree bPlusTree = new TransactionalIntToLongBPlusTree(3);
			assertStateAfterCommit(
				bPlusTree,
				tested -> {
					tested.insert(5, 50L);
					tested.insert(5, 999L);
				},
				(original, committed) -> {
					assertEquals(0, original.size());
					assertTrue(original.search(5).isEmpty());

					assertEquals(1, committed.size());
					assertEquals(999L, committed.search(5).orElseThrow());
				}
			);
		}

		@Test
		@DisplayName("splits leaf node when capacity is exceeded")
		void shouldSplitNodeWhenFull() {
			final TransactionalIntToLongBPlusTree bPlusTree = new TransactionalIntToLongBPlusTree(3);

			assertStateAfterCommit(
				bPlusTree,
				tested -> {
					tested.insert(1, 10L);
					tested.insert(2, 20L);
					// this should cause a split
					tested.insert(3, 30L);
					tested.insert(4, 40L);
				},
				(original, committed) -> {
					assertEquals(4, committed.size());
					assertEquals(10L, committed.search(1).orElseThrow());
					assertEquals(20L, committed.search(2).orElseThrow());
					assertEquals(30L, committed.search(3).orElseThrow());
					assertEquals(40L, committed.search(4).orElseThrow());

					verifyTreeConsistency(committed, 1, 2, 3, 4);

					assertEquals(0, original.size());
					assertTrue(original.search(1).isEmpty());
					assertTrue(original.search(2).isEmpty());
					assertTrue(original.search(3).isEmpty());
					assertTrue(original.search(4).isEmpty());
				}
			);
		}

		@Test
		@DisplayName("maintains balanced structure after sequential forward insertions")
		void shouldMaintainBalanced() {
			final TransactionalIntToLongBPlusTree bPlusTree = new TransactionalIntToLongBPlusTree(3);
			final AtomicReference<int[]> keys = new AtomicReference<>(new int[0]);
			assertStateAfterCommit(
				bPlusTree,
				tested -> {
					for (int i = 1; i <= 20; i++) {
						tested.insert(i, valueOf(i));
						keys.set(ArrayUtils.insertIntIntoOrderedArray(i, keys.get()));
					}
				},
				(original, committed) -> {
					assertEquals(
						"""
							< 9:
							   < 5:
							      < 3:
							         < 2:
							            1:10
							         >=2:
							            2:20
							      >=3:
							         < 4:
							            3:30
							         >=4:
							            4:40
							   >=5:
							      < 7:
							         < 6:
							            5:50
							         >=6:
							            6:60
							      >=7:
							         < 8:
							            7:70
							         >=8:
							            8:80
							>=9:
							   < 13:
							      < 11:
							         < 10:
							            9:90
							         >=10:
							            10:100
							      >=11:
							         < 12:
							            11:110
							         >=12:
							            12:120
							   >=13:
							      < 15:
							         < 14:
							            13:130
							         >=14:
							            14:140
							      >=15:
							         < 16:
							            15:150
							         >=16:
							            16:160
							      >=17:
							         < 18:
							            17:170
							         >=18:
							            18:180
							         >=19:
							            19:190, 20:200""",
						committed.toString()
					);

					verifyTreeConsistency(committed, keys.get());
					assertEquals(0, original.size());
				}
			);
		}

		@Test
		@DisplayName("maintains balanced structure after sequential reverse insertions")
		void shouldStayBalancedWhenItemsAreAddedToTheBeginningOnly() {
			final TransactionalIntToLongBPlusTree bPlusTree = new TransactionalIntToLongBPlusTree(3);
			final AtomicReference<int[]> keys = new AtomicReference<>(new int[0]);
			assertStateAfterCommit(
				bPlusTree,
				tested -> {
					for (int i = 20; i > 0; i--) {
						tested.insert(i, valueOf(i));
						keys.set(ArrayUtils.insertIntIntoOrderedArray(i, keys.get()));
					}
				},
				(original, committed) -> {
					assertEquals(
						"""
							< 13:
							   < 5:
							      < 3:
							         1:10, 2:20
							      >=3:
							         3:30, 4:40
							   >=5:
							      < 7:
							         5:50, 6:60
							      >=7:
							         7:70, 8:80
							   >=9:
							      < 11:
							         9:90, 10:100
							      >=11:
							         11:110, 12:120
							>=13:
							   < 17:
							      < 15:
							         13:130, 14:140
							      >=15:
							         15:150, 16:160
							   >=17:
							      < 19:
							         17:170, 18:180
							      >=19:
							         19:190, 20:200""",
						committed.toString()
					);

					verifyTreeConsistency(committed, keys.get());
					assertEquals(0, original.size());
				}
			);
		}

	}

	@Nested
	@DisplayName("Search operations")
	class SearchOperationsTest {

		@Test
		@DisplayName("returns empty optional on empty tree")
		void shouldReturnEmptyWhenSearchingInEmptyTree() {
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);
			assertTrue(tree.search(42).isEmpty());
		}

		@Test
		@DisplayName("returns empty optional for non-existent key")
		void shouldReturnEmptyForNonExistentKey() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			assertTrue(testTree.bPlusTree().search(99999).isEmpty());
		}

		@Test
		@DisplayName("finds single inserted element")
		void shouldInsertAndSearchSingleElement() {
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);
			tree.insert(42, 420L);
			assertEquals(1, tree.size());
			assertEquals(420L, tree.search(42).orElseThrow());
		}

		@Test
		@DisplayName("returns missing sentinel on empty tree via allocation-free searchOrDefault")
		void shouldReturnMissingSentinelWhenSearchingOrDefaultInEmptyTree() {
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);
			assertEquals(Long.MIN_VALUE, tree.searchOrDefault(42, Long.MIN_VALUE));
		}

		@Test
		@DisplayName("returns missing sentinel for non-existent key via allocation-free searchOrDefault")
		void shouldReturnMissingSentinelForNonExistentKeyWithSearchOrDefault() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			assertEquals(-1L, testTree.bPlusTree().searchOrDefault(99999, -1L));
		}

		@Test
		@DisplayName("finds single inserted element via allocation-free searchOrDefault")
		void shouldFindValueWithSearchOrDefault() {
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);
			tree.insert(42, 420L);
			assertEquals(420L, tree.searchOrDefault(42, Long.MIN_VALUE));
		}

		@Test
		@DisplayName("searchOrDefault agrees with search across a randomized tree")
		void shouldMatchSearchAcrossRandomizedTree() {
			final TreeTuple testTree = prepareRandomTree(42, 200);
			final TransactionalIntToLongBPlusTree tree = testTree.bPlusTree();
			// present keys resolve to the same value both ways
			for (final int key : testTree.plainArray()) {
				assertEquals(tree.search(key).orElseThrow(), tree.searchOrDefault(key, Long.MIN_VALUE));
			}
			// absent keys resolve to the caller-supplied sentinel
			for (int key = -5; key < (testTree.totalElements() << 1) + 5; key++) {
				if (tree.search(key).isEmpty()) {
					assertEquals(Long.MIN_VALUE, tree.searchOrDefault(key, Long.MIN_VALUE));
				}
			}
		}

	}

	@Nested
	@DisplayName("Upsert operations")
	class UpsertOperationsTest {

		@Test
		@DisplayName("updates value for existing key")
		void shouldUpdateExistingValue() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final TransactionalIntToLongBPlusTree theTree = testTree.bPlusTree();
			final int[] expectedArray = testTree.plainArray();

			assertStateAfterCommit(
				theTree,
				tested -> {
					assertEquals(valueOf(13), tested.search(13).orElseThrow());
					tested.upsert(13, existingValue -> 180L);
				},
				(original, committed) -> {
					verifyTreeConsistency(original, expectedArray);
					assertEquals(180L, committed.search(13).orElseThrow());
					committed.upsert(13, existingValue -> valueOf(13));
					verifyTreeConsistency(committed, expectedArray);
				}
			);
		}

		@Test
		@DisplayName("inserts new entry for non-existent key")
		void shouldInsertNonExistingValueViaUpsert() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final TransactionalIntToLongBPlusTree theTree = testTree.bPlusTree();
			final int[] expectedArray = testTree.plainArray();

			assertStateAfterCommit(
				theTree,
				tested -> {
					assertTrue(tested.search(100).isEmpty());
					tested.upsert(100, existingValue -> valueOf(100));
				},
				(original, committed) -> {
					verifyTreeConsistency(original, expectedArray);
					assertEquals(valueOf(100), committed.search(100).orElseThrow());
					verifyTreeConsistency(
						committed, ArrayUtils.insertIntIntoOrderedArray(100, expectedArray)
					);
				}
			);
		}

		@Test
		@DisplayName("updates value derived from previous value via upsert")
		void shouldUpdateValueDerivedFromPreviousViaUpsert() {
			// the generic-value tree exercised producer-value layer release here; with primitive long values
			// there is no producer layer, so this instead verifies that the updater receives the previous
			// primitive value and that the derived value is committed correctly
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);
			tree.insert(1, 10L);

			assertStateAfterCommit(
				tree,
				original -> original.upsert(1, existing -> existing + 5L),
				(original, committed) -> {
					assertEquals(1, committed.size());
					assertEquals(15L, committed.search(1).orElseThrow());
				}
			);
		}

	}

	@Nested
	@DisplayName("Delete operations")
	class DeleteOperationsTest {

		@Test
		@DisplayName("deletes all elements one by one in random order")
		void shouldDeleteEntireContentsOfTheTree() {
			final Random rnd = new Random(42);
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final TransactionalIntToLongBPlusTree theTree = testTree.bPlusTree();
			final AtomicReference<int[]> expectedArray = new AtomicReference<>(testTree.plainArray());

			assertStateAfterCommit(
				theTree,
				tested -> {
					while (expectedArray.get().length > 0) {
						final int index = rnd.nextInt(expectedArray.get().length);
						final int key = expectedArray.get()[index];
						tested.delete(key);
						expectedArray.set(ArrayUtils.removeIntFromOrderedArray(key, expectedArray.get()));
						verifyTreeConsistency(tested, expectedArray.get());
					}
				},
				(original, committed) -> {
					verifyTreeConsistency(original, testTree.plainArray());
					assertEquals(0, committed.size());
					verifyTreeConsistency(committed, expectedArray.get());
				}
			);
		}

		@Test
		@DisplayName("does not change tree when deleting non-existent key")
		void shouldHandleDeleteOfNonExistentKey() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final int originalSize = testTree.bPlusTree().size();
			testTree.bPlusTree().delete(99999);
			assertEquals(originalSize, testTree.bPlusTree().size());
			verifyTreeConsistency(testTree.bPlusTree(), testTree.plainArray());
		}

		@Test
		@DisplayName("empties tree after deleting sole element")
		void shouldDeleteSingleElementFromTree() {
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);
			tree.insert(42, 420L);
			tree.delete(42);
			assertEquals(0, tree.size());
			assertTrue(tree.search(42).isEmpty());
			verifyTreeConsistency(tree);
		}

		@Test
		@DisplayName("keeps separator keys and occupancy consistent through randomized insert and delete churn")
		void shouldKeepConsistentThroughRandomizedChurn() {
			for (long seed = 0; seed < 50; seed++) {
				final Random random = new Random(seed);
				final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(
					3, 1, 3, 1);
				final TreeMap<Integer, Long> reference = new TreeMap<>();

				for (int op = 0; op < 400; op++) {
					final int key = random.nextInt(60);
					// bias towards delete once the tree has grown so merges and borrows are exercised heavily
					final boolean delete = reference.size() > 20
						? random.nextInt(3) > 0
						: random.nextBoolean();
					if (delete) {
						tree.delete(key);
						reference.remove(key);
					} else {
						tree.insert(key, valueOf(key));
						reference.put(key, valueOf(key));
					}

					// the consistency report validates separator keys (each internal key equals the left
					// boundary of its child) and minimal node occupancy after every structural change
					final ConsistencyReport report = tree.getConsistencyReport();
					assertEquals(
						ConsistencyState.CONSISTENT, report.state(),
						"Inconsistent at seed " + seed + " op " + op + ": " + report.report()
					);
					assertEquals(reference.size(), tree.size(), "Size mismatch at seed " + seed + " op " + op);
				}

				// final contents must match the reference exactly, in order
				final int[] expectedKeys = reference.keySet().stream().mapToInt(Integer::intValue).toArray();
				verifyForwardValueIterator(tree, expectedKeys);
				verifyReverseValueIterator(tree, expectedKeys);
			}
		}

		@Test
		@DisplayName("sweeps cleanly when a value is added, modified and deleted in the same transaction")
		void shouldSweepCleanlyWhenValueIsAddedModifiedAndDeleted() {
			// the generic-value tree verified per-value producer layer release here; with primitive long
			// values there is no per-value layer, but the node-graph sweep must still complete cleanly when a
			// value is added, mutated and dropped within one transaction
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);

			assertStateAfterCommit(
				tree,
				original -> {
					original.insert(2, 20L);
					original.upsert(2, existing -> existing + 1L);
					original.delete(2);
				},
				(original, committed) -> {
					assertEquals(0, original.size());
					assertEquals(0, committed.size());
				}
			);
		}

		@Test
		@DisplayName("sweeps cleanly when a tree is created and discarded within a transaction")
		void shouldNotLeakLayerWhenTreeIsCreatedAndDiscardedWithinTransaction() {
			// outer tree we never touch - it only provides a transactional context to drive the commit sweep
			final TransactionalIntToLongBPlusTree outer =
				new TransactionalIntToLongBPlusTree(3);

			assertStateAfterCommit(
				outer,
				original -> {
					// build a throwaway sub-tree, open ALIVE layers on its node graph, then discard the whole
					// sub-tree by removing its layers via the maintainer. Without the deep recursion the
					// sub-tree's size/root references and node graph would remain ALIVE and trip
					// StaleTransactionMemoryException during the commit sweep (INV-5).
					final TransactionalIntToLongBPlusTree discarded =
						new TransactionalIntToLongBPlusTree(3, 1, 3, 1);
					for (int i = 0; i < 20; i++) {
						discarded.insert(i, valueOf(i));
					}
					// mutate a handful of values to open additional node layers across the graph
					for (int i = 0; i < 20; i += 3) {
						discarded.upsert(i, existing -> existing + 1L);
					}
					final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
					discarded.removeLayer(maintainer);
				},
				(original, committed) -> assertEquals(0, committed.size())
			);
		}

	}

	@Nested
	@DisplayName("Rebalancing - steal operations")
	class StealOperationsTest {

		@Test
		@DisplayName("steals from left sibling when node underflows")
		void shouldStealFromLeftmostNode() {
			final TransactionalIntToLongBPlusTree bPlusTree = new TransactionalIntToLongBPlusTree(3);
			final AtomicReference<TransactionalIntToLongBPlusTree> theCommittedTree = new AtomicReference<>();

			assertStateAfterCommit(
				bPlusTree,
				tested -> {
					tested.insert(15, valueOf(15));
					tested.insert(17, valueOf(17));
					// this should cause a split
					tested.insert(20, valueOf(20));
					tested.insert(23, valueOf(23));
					tested.insert(25, valueOf(25));
					tested.insert(14, valueOf(14));
				},
				(original, committed) -> {
					verifyTreeConsistency(committed, 14, 15, 17, 20, 23, 25);
					assertEquals(
						"""
							< 20:
							   < 17:
							      14:140, 15:150
							   >=17:
							      17:170
							>=20:
							   < 23:
							      20:200
							   >=23:
							      23:230, 25:250""",
						committed.toString()
					);
					theCommittedTree.set(committed);
				}
			);

			assertStateAfterCommit(
				theCommittedTree.get(),
				tested -> tested.delete(17),
				(original, committed) -> {
					verifyTreeConsistency(committed, 14, 15, 20, 23, 25);
					assertEquals(
						"""
							< 20:
							   < 15:
							      14:140
							   >=15:
							      15:150
							>=20:
							   < 23:
							      20:200
							   >=23:
							      23:230, 25:250""",
						committed.toString()
					);
				}
			);
		}

		@Test
		@DisplayName("steals from right sibling after multiple deletions")
		void shouldStealFromRightNode() {
			final TransactionalIntToLongBPlusTree bPlusTree = new TransactionalIntToLongBPlusTree(3);
			final AtomicReference<TransactionalIntToLongBPlusTree> theCommittedTree = new AtomicReference<>();

			assertStateAfterCommit(
				bPlusTree,
				tested -> {
					tested.insert(15, valueOf(15));
					tested.insert(17, valueOf(17));
					// this should cause a split
					tested.insert(20, valueOf(20));
					tested.insert(23, valueOf(23));
					tested.insert(25, valueOf(25));
					tested.insert(14, valueOf(14));
					tested.insert(16, valueOf(16));
					tested.insert(19, valueOf(19));
					tested.insert(18, valueOf(18));
					tested.insert(11, valueOf(11));
					tested.insert(12, valueOf(12));
					tested.insert(10, valueOf(10));
				},
				(original, committed) -> {
					verifyTreeConsistency(committed, 10, 11, 12, 14, 15, 16, 17, 18, 19, 20, 23, 25);

					assertEquals(
						"""
							< 17:
							   < 12:
							      10:100, 11:110
							   >=12:
							      12:120, 14:140
							   >=15:
							      15:150, 16:160
							>=17:
							   < 18:
							      17:170
							   >=18:
							      18:180, 19:190
							>=20:
							   < 23:
							      20:200
							   >=23:
							      23:230, 25:250""",
						committed.toString()
					);

					theCommittedTree.set(committed);
				}
			);

			assertStateAfterCommit(
				theCommittedTree.get(),
				tested -> tested.delete(11),
				(original, committed) -> {
					verifyTreeConsistency(committed, 10, 12, 14, 15, 16, 17, 18, 19, 20, 23, 25);
					theCommittedTree.set(committed);
				}
			);

			assertStateAfterCommit(
				theCommittedTree.get(),
				tested -> tested.delete(10),
				(original, committed) -> {
					verifyTreeConsistency(committed, 12, 14, 15, 16, 17, 18, 19, 20, 23, 25);

					assertEquals(
						"""
							< 17:
							   < 14:
							      12:120
							   >=14:
							      14:140
							   >=15:
							      15:150, 16:160
							>=17:
							   < 18:
							      17:170
							   >=18:
							      18:180, 19:190
							>=20:
							   < 23:
							      20:200
							   >=23:
							      23:230, 25:250""",
						committed.toString()
					);
				}
			);
		}

		@Test
		@DisplayName("steals from left sibling after multiple deletions")
		void shouldStealFromLeftNode() {
			final TransactionalIntToLongBPlusTree bPlusTree = new TransactionalIntToLongBPlusTree(3);
			final AtomicReference<TransactionalIntToLongBPlusTree> theCommittedTree = new AtomicReference<>();

			assertStateAfterCommit(
				bPlusTree,
				tested -> {
					tested.insert(15, valueOf(15));
					tested.insert(17, valueOf(17));
					// this should cause a split
					tested.insert(20, valueOf(20));
					tested.insert(23, valueOf(23));
					tested.insert(25, valueOf(25));
					tested.insert(14, valueOf(14));
					tested.insert(16, valueOf(16));
					tested.insert(19, valueOf(19));
					tested.insert(18, valueOf(18));
					tested.insert(11, valueOf(11));
					tested.insert(12, valueOf(12));
				},
				(original, committed) -> {
					verifyTreeConsistency(committed, 11, 12, 14, 15, 16, 17, 18, 19, 20, 23, 25);
					assertEquals(
						"""
							< 17:
							   < 12:
							      11:110
							   >=12:
							      12:120, 14:140
							   >=15:
							      15:150, 16:160
							>=17:
							   < 18:
							      17:170
							   >=18:
							      18:180, 19:190
							>=20:
							   < 23:
							      20:200
							   >=23:
							      23:230, 25:250""",
						committed.toString()
					);

					theCommittedTree.set(committed);
				}
			);

			assertStateAfterCommit(
				theCommittedTree.get(),
				tested -> tested.delete(15),
				(original, committed) -> {
					verifyTreeConsistency(committed, 11, 12, 14, 16, 17, 18, 19, 20, 23, 25);
					theCommittedTree.set(committed);
				}
			);

			assertStateAfterCommit(
				theCommittedTree.get(),
				tested -> tested.delete(16),
				(original, committed) -> {
					verifyTreeConsistency(committed, 11, 12, 14, 17, 18, 19, 20, 23, 25);
					assertEquals(
						"""
							< 17:
							   < 12:
							      11:110
							   >=12:
							      12:120
							   >=14:
							      14:140
							>=17:
							   < 18:
							      17:170
							   >=18:
							      18:180, 19:190
							>=20:
							   < 23:
							      20:200
							   >=23:
							      23:230, 25:250""",
						committed.toString()
					);
				}
			);
		}

	}

	@Nested
	@DisplayName("Rebalancing - merge operations")
	class MergeOperationsTest {

		/**
		 * Builds an internal node with no children (peek == -1), the degenerate shape the merge methods
		 * must reject. The node is created via the copy constructor with an empty range.
		 *
		 * @return an empty internal node
		 */
		@Nonnull
		private static TransactionalIntToLongBPlusTree.BPlusInternalTreeNode emptyInternalNode() {
			return new TransactionalIntToLongBPlusTree.BPlusInternalTreeNode(
				new int[3], new BPlusTreeNode<?>[4], 0, 0, 0, 0, true
			);
		}

		/**
		 * Builds a single-element leaf node carrying the given key with a matching primitive value.
		 *
		 * @param key the key (and value source) to store
		 * @return a leaf node holding exactly the one key
		 */
		@Nonnull
		private static TransactionalIntToLongBPlusTree.BPlusLeafTreeNode leaf(int key) {
			final int[] keys = {key};
			final long[] values = {valueOf(key)};
			return new TransactionalIntToLongBPlusTree.BPlusLeafTreeNode(
				keys, values, new int[3], new long[3], 0, 1, true
			);
		}

		/**
		 * Builds an internal node with the given separator keys routing to the supplied children. The number
		 * of keys must be exactly one less than the number of children.
		 *
		 * @param keys     the separator keys
		 * @param children the child nodes
		 * @return a hand-built internal node with the requested occupancy
		 */
		@Nonnull
		private static TransactionalIntToLongBPlusTree.BPlusInternalTreeNode internal(
			@Nonnull int[] keys, @Nonnull BPlusTreeNode<?>... children
		) {
			final int[] keyArray = new int[3];
			System.arraycopy(keys, 0, keyArray, 0, keys.length);
			final BPlusTreeNode<?>[] childArray =
				new BPlusTreeNode<?>[4];
			System.arraycopy(children, 0, childArray, 0, children.length);
			return new TransactionalIntToLongBPlusTree.BPlusInternalTreeNode(
				keyArray, childArray, 0, keys.length, 0, children.length, true
			);
		}

		private static void exerciseChurn(
			int valueBlockSize, int minValueBlockSize,
			int internalNodeBlockSize, int minInternalNodeBlockSize, long seed
		) {
			final Random random = new Random(seed);
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(
				valueBlockSize, minValueBlockSize, internalNodeBlockSize, minInternalNodeBlockSize
			);
			final TreeMap<Integer, Long> reference = new TreeMap<>();
			final int range = 200;
			for (int op = 0; op < 1500; op++) {
				final int key = random.nextInt(range);
				final boolean delete = reference.size() > 40
					? random.nextInt(4) > 0
					: random.nextBoolean();
				if (delete) {
					tree.delete(key);
					reference.remove(key);
				} else {
					tree.insert(key, valueOf(key));
					reference.put(key, valueOf(key));
				}
				final ConsistencyReport report = tree.getConsistencyReport();
				assertEquals(
					ConsistencyState.CONSISTENT, report.state(),
					"Inconsistent at vbs=" + valueBlockSize + " mvbs=" + minValueBlockSize +
						" ibs=" + internalNodeBlockSize + " mibs=" + minInternalNodeBlockSize +
						" seed=" + seed + " op=" + op + ": " + report.report()
				);
			}
		}

		@Test
		@DisplayName("merges with left sibling node")
		void shouldMergeWithLeftNode() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final AtomicReference<int[]> expectedArray = new AtomicReference<>(testTree.plainArray());
			TransactionalIntToLongBPlusTree tree = testTree.bPlusTree();

			tree = deleteAndVerify(tree, expectedArray, 98);
			deleteAndVerify(tree, expectedArray, 94);
		}

		@Test
		@DisplayName("merges with right sibling node")
		void shouldMergeWithRightNode() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final AtomicReference<int[]> expectedArray = new AtomicReference<>(testTree.plainArray());

			deleteAndVerify(testTree.bPlusTree(), expectedArray, 93);
		}

		@Test
		@DisplayName("cascades merge causing parent to steal from left")
		void shouldMergeCausingIntermediateParentToStealFromLeft() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final AtomicReference<int[]> expectedArray = new AtomicReference<>(testTree.plainArray());

			deleteAndVerify(testTree.bPlusTree(), expectedArray, 34);
		}

		@Test
		@DisplayName("cascades merge causing parent to steal from right")
		void shouldMergeCausingIntermediateParentToStealFromRight() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final AtomicReference<int[]> expectedArray = new AtomicReference<>(testTree.plainArray());
			TransactionalIntToLongBPlusTree tree = testTree.bPlusTree();

			tree = deleteAndVerify(tree, expectedArray, 92);
			deleteAndVerify(tree, expectedArray, 87);
		}

		@Test
		@DisplayName("cascades multiple merges with left parent")
		void shouldMergeCausingIntermediateParentToMergeLeft() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final AtomicReference<int[]> expectedArray = new AtomicReference<>(testTree.plainArray());
			TransactionalIntToLongBPlusTree tree = testTree.bPlusTree();

			tree = deleteAndVerify(tree, expectedArray, 32);
			tree = deleteAndVerify(tree, expectedArray, 34);
			tree = deleteAndVerify(tree, expectedArray, 35);
			tree = deleteAndVerify(tree, expectedArray, 37);
			deleteAndVerify(tree, expectedArray, 40);
		}

		@Test
		@DisplayName("cascades multiple merges with right parent")
		void shouldMergeCausingIntermediateParentToMergeRight() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final AtomicReference<int[]> expectedArray = new AtomicReference<>(testTree.plainArray());
			TransactionalIntToLongBPlusTree tree = testTree.bPlusTree();

			tree = deleteAndVerify(tree, expectedArray, 25);
			tree = deleteAndVerify(tree, expectedArray, 26);
			tree = deleteAndVerify(tree, expectedArray, 27);
			deleteAndVerify(tree, expectedArray, 30);
		}

		@Test
		@DisplayName("rejects merging the left sibling into an empty internal node")
		void shouldRejectMergeWithLeftIntoEmptyInternalNode() {
			// the rebalancer never drives a non-root internal node to zero children before merging it (a
			// single-child node is collapsed first), so the merge methods assume at least one child is
			// present; calling them on an empty node must fail loudly instead of corrupting the arrays
			final TransactionalIntToLongBPlusTree.BPlusInternalTreeNode empty = emptyInternalNode();
			final TransactionalIntToLongBPlusTree.BPlusInternalTreeNode sibling =
				internal(new int[]{2}, leaf(1), leaf(2));

			assertThrows(GenericEvitaInternalError.class, () -> empty.mergeWithLeft(sibling));
		}

		@Test
		@DisplayName("rejects merging the right sibling into an empty internal node")
		void shouldRejectMergeWithRightIntoEmptyInternalNode() {
			final TransactionalIntToLongBPlusTree.BPlusInternalTreeNode empty = emptyInternalNode();
			final TransactionalIntToLongBPlusTree.BPlusInternalTreeNode sibling =
				internal(new int[]{2}, leaf(1), leaf(2));

			assertThrows(GenericEvitaInternalError.class, () -> empty.mergeWithRight(sibling));
		}

		@Test
		@DisplayName("survives heavy randomized churn across many block-size configurations")
		void shouldSurviveRandomizedChurnAcrossConfigurations() {
			// internal nodes are physically sized by valueBlockSize, so only internalNodeBlockSize <=
			// valueBlockSize is structurally valid; exercise a broad matrix of valid configurations with
			// long delete-biased churn and assert no exception and CONSISTENT state after every operation
			final int[] blockSizes = {3, 5, 7, 9};
			for (int valueBlockSize : blockSizes) {
				final int maxMinValue = (int) Math.ceil((float) valueBlockSize / 2.0) - 1;
				for (int minValueBlockSize = 1; minValueBlockSize <= maxMinValue; minValueBlockSize++) {
					for (int internalNodeBlockSize : blockSizes) {
						if (internalNodeBlockSize > valueBlockSize) {
							continue;
						}
						final int maxMinInternal = (int) Math.ceil((float) internalNodeBlockSize / 2.0) - 1;
						for (int minInternalNodeBlockSize = 1; minInternalNodeBlockSize <= maxMinInternal; minInternalNodeBlockSize++) {
							for (long seed = 0; seed < 5; seed++) {
								exerciseChurn(
									valueBlockSize, minValueBlockSize,
									internalNodeBlockSize, minInternalNodeBlockSize, seed
								);
							}
						}
					}
				}
			}
		}

	}

	@Nested
	@DisplayName("Forward value iteration")
	class ForwardValueIterationTest {

		@Test
		@DisplayName("iterates all keys in ascending order")
		void shouldIterateThroughLeafNodeKeysFromLeftToRight() {
			final TreeTuple testTree = prepareRandomTree(System.currentTimeMillis(), 100);

			verifyTreeConsistency(testTree.bPlusTree(), testTree.plainArray());
			verifyForwardValueIterator(testTree.bPlusTree(), testTree.plainArray());
			assertEquals(testTree.totalElements(), testTree.bPlusTree().size());
		}

		@Test
		@DisplayName("iterates all values in ascending key order")
		void shouldIterateThroughLeafNodeValuesLeftToRight() {
			final TreeTuple testTree = prepareRandomTree(System.currentTimeMillis(), 100);

			final long[] reconstructedArray = new long[testTree.totalElements()];
			int index = 0;
			final PrimitiveIterator.OfLong it = testTree.bPlusTree().valueIterator();
			while (it.hasNext()) {
				reconstructedArray[index++] = it.nextLong();
			}

			assertArrayEquals(testTree.asLongArray(), reconstructedArray);
			assertThrows(NoSuchElementException.class, it::next);
			assertEquals(testTree.totalElements(), testTree.bPlusTree().size());
		}

		@Test
		@DisplayName("iterates values from exact existing key")
		void shouldIterateThroughLeafNodeValuesLeftToRightFromExactPosition() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final PrimitiveIterator.OfLong it = testTree.bPlusTree().greaterOrEqualValueIterator(40);
			final int[] plainFullArray = testTree.plainArray();
			final InsertionPosition insertionPosition = ArrayUtils.computeInsertPositionOfIntInOrderedArray(
				40, plainFullArray);

			assertTrue(insertionPosition.alreadyPresent());
			final int startPos = insertionPosition.position();
			final long[] partialCopy = new long[plainFullArray.length - startPos];
			for (int i = startPos; i < plainFullArray.length; i++) {
				partialCopy[i - startPos] = valueOf(plainFullArray[i]);
			}

			final long[] reconstructedArray = new long[partialCopy.length];
			int index = 0;
			while (it.hasNext()) {
				reconstructedArray[index++] = it.nextLong();
			}

			assertArrayEquals(partialCopy, reconstructedArray);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("iterates values from non-existing key position")
		void shouldIterateThroughLeafNodeValuesLeftToRightFromExactNonExistingPosition() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final PrimitiveIterator.OfLong it = testTree.bPlusTree().greaterOrEqualValueIterator(39);
			final int[] plainFullArray = testTree.plainArray();
			final InsertionPosition insertionPosition = ArrayUtils.computeInsertPositionOfIntInOrderedArray(
				39, plainFullArray);

			assertFalse(insertionPosition.alreadyPresent());
			final int startPos = insertionPosition.position();
			final long[] partialCopy = new long[plainFullArray.length - startPos];
			for (int i = startPos; i < plainFullArray.length; i++) {
				partialCopy[i - startPos] = valueOf(plainFullArray[i]);
			}

			final long[] reconstructedArray = new long[partialCopy.length];
			int index = 0;
			while (it.hasNext()) {
				reconstructedArray[index++] = it.nextLong();
			}

			assertArrayEquals(partialCopy, reconstructedArray);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("returns empty iterator when start key exceeds maximum")
		void shouldFailToIterateValuesLeftToRightThroughNonExistingValues() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final PrimitiveIterator.OfLong it = testTree.bPlusTree().greaterOrEqualValueIterator(1000);
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("returns empty iterator on empty tree")
		void shouldIterateForwardOnEmptyTree() {
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);
			final PrimitiveIterator.OfLong it = tree.valueIterator();
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("iterates single element tree")
		void shouldIterateForwardOnSingleElementTree() {
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);
			tree.insert(42, valueOf(42));
			final PrimitiveIterator.OfLong it = tree.valueIterator();
			assertTrue(it.hasNext());
			assertEquals(valueOf(42), it.nextLong());
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("iterates from tree's minimum key inclusive")
		void shouldIterateFromFirstKey() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final int[] keys = testTree.plainArray();
			final int firstKey = keys[0];
			final PrimitiveIterator.OfLong it = testTree.bPlusTree().greaterOrEqualValueIterator(firstKey);
			int count = 0;
			while (it.hasNext()) {
				it.nextLong();
				count++;
			}
			assertEquals(keys.length, count);
		}

		@Test
		@DisplayName("iterates from tree's maximum key returning one element")
		void shouldIterateFromLastKey() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final int[] keys = testTree.plainArray();
			final int lastKey = keys[keys.length - 1];
			final PrimitiveIterator.OfLong it = testTree.bPlusTree().greaterOrEqualValueIterator(lastKey);
			assertTrue(it.hasNext());
			assertEquals(valueOf(lastKey), it.nextLong());
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("iterates all elements when start key is below minimum")
		void shouldIterateFromBelowMinimumKey() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final int[] keys = testTree.plainArray();
			final PrimitiveIterator.OfLong it = testTree.bPlusTree().greaterOrEqualValueIterator(Integer.MIN_VALUE);
			int count = 0;
			while (it.hasNext()) {
				it.nextLong();
				count++;
			}
			assertEquals(keys.length, count);
		}

		@Test
		@DisplayName("iterates values when start key falls in the gap after the last key of a leaf")
		void shouldIterateFromKeyInGapBetweenLeaves() {
			// build a tree whose leaves are [10,20,30] | [50,60] so that key 40 lands past the
			// last key of the first leaf while a non-empty following leaf still holds keys >= 40
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);
			final int[] keys = {10, 20, 30, 50, 60};
			for (final int key : keys) {
				tree.insert(key, valueOf(key));
			}

			final PrimitiveIterator.OfLong it = tree.greaterOrEqualValueIterator(40);
			final long[] reconstructed = new long[2];
			int index = 0;
			while (it.hasNext()) {
				reconstructed[index++] = it.nextLong();
			}

			assertArrayEquals(new long[]{valueOf(50), valueOf(60)}, reconstructed);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("matches sorted reference for arbitrary gap start keys across many leaves")
		void shouldMatchReferenceForGapStartKeys() {
			final TreeTuple testTree = prepareRandomTree(913, 200);
			final int[] keys = testTree.plainArray();

			// probe every possible start key from below the minimum to above the maximum
			for (int startKey = keys[0] - 2; startKey <= keys[keys.length - 1] + 2; startKey++) {
				final InsertionPosition position = ArrayUtils.computeInsertPositionOfIntInOrderedArray(startKey, keys);
				final int from = position.position();

				final PrimitiveIterator.OfLong it = testTree.bPlusTree().greaterOrEqualValueIterator(startKey);
				int index = from;
				while (it.hasNext()) {
					assertTrue(
						index < keys.length, "Iterator returned more elements than reference for key " + startKey);
					assertEquals(valueOf(keys[index]), it.nextLong(), "Mismatch at key " + startKey + ", index " + index);
					index++;
				}
				assertEquals(keys.length, index, "Iterator stopped early for start key " + startKey);
			}
		}

	}

	@Nested
	@DisplayName("Forward key iteration")
	class ForwardKeyIterationTest {

		@Test
		@DisplayName("iterates keys from exact existing key")
		void shouldIterateThroughLeafNodeKeysLeftToRightFromExactPosition() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final PrimitiveIterator.OfInt it = testTree.bPlusTree().greaterOrEqualKeyIterator(40);
			final int[] plainFullArray = testTree.plainArray();
			final InsertionPosition insertionPosition =
				ArrayUtils.computeInsertPositionOfIntInOrderedArray(40, plainFullArray);

			assertTrue(insertionPosition.alreadyPresent());
			final int startPos = insertionPosition.position();
			final int[] partialCopy = new int[plainFullArray.length - startPos];
			if (plainFullArray.length - startPos >= 0) {
				System.arraycopy(plainFullArray, startPos, partialCopy, 0, plainFullArray.length - startPos);
			}

			final int[] reconstructedArray = new int[partialCopy.length];
			int index = 0;
			while (it.hasNext()) {
				reconstructedArray[index++] = it.nextInt();
			}

			assertArrayEquals(partialCopy, reconstructedArray);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("returns empty key iterator when exceeding bounds")
		void shouldFailToIterateKeysLeftToRightThroughNonExistingValues() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final int[] keys = testTree.plainArray();

			final PrimitiveIterator.OfInt it = testTree.bPlusTree().greaterOrEqualKeyIterator(
				keys[keys.length - 1] + 1000);
			assertFalse(it.hasNext());

			// start key below the minimum must yield the whole tree, starting with the minimum key
			final PrimitiveIterator.OfInt it2 = testTree.bPlusTree().greaterOrEqualKeyIterator(
				keys[0] - 1000);
			assertTrue(it2.hasNext());
			assertEquals(keys[0], it2.nextInt());
		}

		@Test
		@DisplayName("iterates all keys in ascending order")
		void shouldIterateAllKeysLeftToRight() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final int[] keys = testTree.plainArray();

			final PrimitiveIterator.OfInt it = testTree.bPlusTree().keyIterator();
			int index = 0;
			while (it.hasNext()) {
				assertTrue(index < keys.length, "Iterator returned more keys than expected");
				assertEquals(keys[index], it.nextInt());
				index++;
			}

			assertEquals(keys.length, index);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("iterates full tree from minimum key inclusive")
		void shouldIterateFullTreeFromMinKey() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final int[] keys = testTree.plainArray();
			final int minKey = keys[0];

			final PrimitiveIterator.OfInt it = testTree.bPlusTree().greaterOrEqualKeyIterator(minKey);
			int count = 0;
			while (it.hasNext()) {
				it.nextInt();
				count++;
			}
			assertEquals(keys.length, count);
		}

		@Test
		@DisplayName("iterates keys when start key falls in the gap after the last key of a leaf")
		void shouldIterateKeysFromKeyInGapBetweenLeaves() {
			// build a tree whose leaves are [10,20,30] | [50,60] so that key 40 lands past the last
			// key of the first leaf while a non-empty following leaf still holds keys >= 40; the
			// greaterOrEqualKeyIterator must cross the inter-leaf gap and return every key >= 40
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);
			final int[] keys = {10, 20, 30, 50, 60};
			for (final int key : keys) {
				tree.insert(key, valueOf(key));
			}

			final PrimitiveIterator.OfInt it = tree.greaterOrEqualKeyIterator(40);
			final int[] reconstructed = new int[2];
			int index = 0;
			while (it.hasNext()) {
				reconstructed[index++] = it.nextInt();
			}

			assertArrayEquals(new int[]{50, 60}, reconstructed);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("matches sorted reference for arbitrary gap start keys across many leaves")
		void shouldMatchReferenceForGapStartKeysForward() {
			final TreeTuple testTree = prepareRandomTree(913, 200);
			final int[] keys = testTree.plainArray();

			// probe every possible start key from below the minimum to above the maximum
			for (int startKey = keys[0] - 2; startKey <= keys[keys.length - 1] + 2; startKey++) {
				final InsertionPosition position =
					ArrayUtils.computeInsertPositionOfIntInOrderedArray(startKey, keys);
				final int from = position.position();

				final PrimitiveIterator.OfInt it = testTree.bPlusTree().greaterOrEqualKeyIterator(startKey);
				int index = from;
				while (it.hasNext()) {
					assertTrue(
						index < keys.length, "Iterator returned more keys than reference for key " + startKey);
					assertEquals(keys[index], it.nextInt(), "Mismatch at key " + startKey + ", index " + index);
					index++;
				}
				assertEquals(keys.length, index, "Iterator stopped early for start key " + startKey);
			}
		}

		@Test
		@DisplayName("returns empty key iterator on empty tree")
		void shouldReturnEmptyKeyIteratorOnEmptyTree() {
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);
			final PrimitiveIterator.OfInt it = tree.keyIterator();
			assertFalse(it.hasNext());
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("iterates single key on single-element tree")
		void shouldIterateKeyOnSingleElementTree() {
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);
			tree.insert(42, valueOf(42));
			final PrimitiveIterator.OfInt it = tree.keyIterator();
			assertTrue(it.hasNext());
			assertEquals(42, it.nextInt());
			assertFalse(it.hasNext());
		}

	}

	@Nested
	@DisplayName("Forward entry iteration")
	class ForwardEntryIterationTest {

		@Test
		@DisplayName("iterates entries from exact existing key")
		void shouldIterateThroughLeafNodeEntriesLeftToRightFromExactPosition() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final Iterator<Entry> it = testTree.bPlusTree().greaterOrEqualEntryIterator(40);
			final int[] plainFullArray = testTree.plainArray();
			final InsertionPosition insertionPosition =
				ArrayUtils.computeInsertPositionOfIntInOrderedArray(40, plainFullArray);

			assertTrue(insertionPosition.alreadyPresent());
			final int startPos = insertionPosition.position();
			final Entry[] partialCopy = new Entry[plainFullArray.length - startPos];
			for (int i = startPos; i < plainFullArray.length; i++) {
				partialCopy[i - startPos] = new Entry(plainFullArray[i], valueOf(plainFullArray[i]));
			}

			final Entry[] reconstructedArray = new Entry[partialCopy.length];
			int index = 0;
			while (it.hasNext()) {
				reconstructedArray[index++] = it.next();
			}

			assertArrayEquals(partialCopy, reconstructedArray);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("returns empty entry iterator when start key exceeds maximum")
		void shouldFailToIterateEntriesLeftToRightThroughNonExistingValues() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final int[] keys = testTree.plainArray();
			final Iterator<Entry> it = testTree.bPlusTree().greaterOrEqualEntryIterator(
				keys[keys.length - 1] + 1000);
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("traverses full tree key and value pairs in ascending order")
		void shouldIterateEntireTreeViaEntryIterator() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final int[] keys = testTree.plainArray();

			final Iterator<Entry> it = testTree.bPlusTree().entryIterator();
			int index = 0;
			while (it.hasNext()) {
				final Entry entry = it.next();
				assertEquals(keys[index], entry.key());
				assertEquals(valueOf(keys[index]), entry.value());
				index++;
			}
			assertEquals(keys.length, index);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("iterates entries when start key falls in the gap after the last key of a leaf")
		void shouldIterateEntriesFromKeyInGapBetweenLeaves() {
			// key 40 falls past the last key of the leaf [10,20,30] while the following leaf [50,60]
			// still holds entries >= 40; the iterator must cross the gap and return both entries
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);
			final int[] keys = {10, 20, 30, 50, 60};
			for (final int key : keys) {
				tree.insert(key, valueOf(key));
			}

			final Iterator<Entry> it = tree.greaterOrEqualEntryIterator(40);
			final Entry[] reconstructed = new Entry[2];
			int index = 0;
			while (it.hasNext()) {
				reconstructed[index++] = it.next();
			}

			final Entry[] expected = new Entry[]{
				new Entry(50, valueOf(50)), new Entry(60, valueOf(60))
			};
			assertArrayEquals(expected, reconstructed);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("returns empty entry iterator on empty tree")
		void shouldReturnEmptyEntryIteratorOnEmptyTree() {
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);
			final Iterator<Entry> it = tree.entryIterator();
			assertFalse(it.hasNext());
			assertThrows(NoSuchElementException.class, it::next);
		}

	}

	@Nested
	@DisplayName("Reverse value iteration")
	class ReverseValueIterationTest {

		@Test
		@DisplayName("iterates all keys in descending order")
		void shouldIterateThroughLeafNodeKeysFromRightToLeft() {
			final TreeTuple testTree = prepareRandomTree(System.currentTimeMillis(), 100);

			verifyTreeConsistency(testTree.bPlusTree(), testTree.plainArray());
			verifyReverseValueIterator(testTree.bPlusTree(), testTree.plainArray());
			assertEquals(testTree.totalElements(), testTree.bPlusTree().size());
		}

		@Test
		@DisplayName("iterates all values in descending key order")
		void shouldIterateThroughLeafNodeValuesRightToLeft() {
			final TreeTuple testTree = prepareRandomTree(System.currentTimeMillis(), 100);

			final long[] reconstructedArray = new long[testTree.totalElements()];
			int index = testTree.totalElements();
			final PrimitiveIterator.OfLong it = testTree.bPlusTree().valueReverseIterator();
			while (it.hasNext()) {
				reconstructedArray[--index] = it.nextLong();
			}

			assertArrayEquals(testTree.asLongArray(), reconstructedArray);
			assertThrows(NoSuchElementException.class, it::next);
			assertEquals(testTree.totalElements(), testTree.bPlusTree().size());
		}

		@Test
		@DisplayName("iterates values backwards from exact existing key")
		void shouldIterateThroughLeafNodeValuesRightToLeftFromExactPosition() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final PrimitiveIterator.OfLong it = testTree.bPlusTree().lesserOrEqualValueIterator(40);
			final int[] plainFullArray = testTree.plainArray();
			final InsertionPosition insertionPosition =
				ArrayUtils.computeInsertPositionOfIntInOrderedArray(40, plainFullArray);

			assertTrue(insertionPosition.alreadyPresent());
			final int endPos = insertionPosition.position();
			final long[] partialCopy = new long[endPos + 1];
			for (int i = endPos; i >= 0; i--) {
				partialCopy[endPos - i] = valueOf(plainFullArray[i]);
			}

			final long[] reconstructedArray = new long[partialCopy.length];
			int index = 0;
			while (it.hasNext()) {
				reconstructedArray[index++] = it.nextLong();
			}

			assertArrayEquals(partialCopy, reconstructedArray);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("iterates values backwards from non-existing key position")
		void shouldIterateThroughLeafNodeValuesRightToLeftFromExactNonExistingPosition() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final PrimitiveIterator.OfLong it = testTree.bPlusTree().lesserOrEqualValueIterator(39);
			final int[] plainFullArray = testTree.plainArray();
			final InsertionPosition insertionPosition =
				ArrayUtils.computeInsertPositionOfIntInOrderedArray(39, plainFullArray);

			assertFalse(insertionPosition.alreadyPresent());
			final int thePosition = insertionPosition.position();
			final long[] partialCopy = new long[thePosition];
			for (int i = partialCopy.length - 1; i >= 0; i--) {
				partialCopy[thePosition - i - 1] = valueOf(plainFullArray[i]);
			}

			final long[] reconstructedArray = new long[partialCopy.length];
			int index = 0;
			while (it.hasNext()) {
				reconstructedArray[index++] = it.nextLong();
			}

			assertArrayEquals(partialCopy, reconstructedArray);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("returns empty reverse iterator when below minimum")
		void shouldFailToIterateValuesRightToLeftThroughNonExistingValues() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final int[] keys = testTree.plainArray();

			final PrimitiveIterator.OfLong it = testTree.bPlusTree().lesserOrEqualValueIterator(keys[0] - 1000);
			assertFalse(it.hasNext());

			// start key above the maximum must yield the whole tree backwards, starting with the maximum
			final PrimitiveIterator.OfLong it2 = testTree.bPlusTree().lesserOrEqualValueIterator(
				keys[keys.length - 1] + 1000);
			assertTrue(it2.hasNext());
			assertEquals(valueOf(keys[keys.length - 1]), it2.nextLong());
		}

		@Test
		@DisplayName("returns empty reverse iterator on empty tree")
		void shouldIterateReverseOnEmptyTree() {
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);
			final PrimitiveIterator.OfLong it = tree.valueReverseIterator();
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("reverse iterates single element tree")
		void shouldIterateReverseOnSingleElementTree() {
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);
			tree.insert(42, valueOf(42));
			final PrimitiveIterator.OfLong it = tree.valueReverseIterator();
			assertTrue(it.hasNext());
			assertEquals(valueOf(42), it.nextLong());
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("iterates all elements backwards from maximum key")
		void shouldIterateFromLastKeyReverse() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final int[] keys = testTree.plainArray();
			final int lastKey = keys[keys.length - 1];
			final PrimitiveIterator.OfLong it = testTree.bPlusTree().lesserOrEqualValueIterator(lastKey);
			int count = 0;
			while (it.hasNext()) {
				it.nextLong();
				count++;
			}
			assertEquals(keys.length, count);
		}

		@Test
		@DisplayName("iterates one element backwards from minimum key")
		void shouldIterateFromFirstKeyReverse() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final int[] keys = testTree.plainArray();
			final int firstKey = keys[0];
			final PrimitiveIterator.OfLong it = testTree.bPlusTree().lesserOrEqualValueIterator(firstKey);
			assertTrue(it.hasNext());
			assertEquals(valueOf(firstKey), it.nextLong());
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("iterates values backwards when start key falls in the gap before the first key of a leaf")
		void shouldIterateValuesFromKeyInGapBetweenLeavesReverse() {
			// key 40 falls below the first key of the leaf [50,60] while the preceding leaf [10,20,30]
			// still holds values <= 40; the lesserOrEqualValueIterator must cross the inter-leaf gap and
			// return, in descending order, every value <= 40
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);
			final int[] keys = {10, 20, 30, 50, 60};
			for (final int key : keys) {
				tree.insert(key, valueOf(key));
			}

			final PrimitiveIterator.OfLong it = tree.lesserOrEqualValueIterator(40);
			final long[] reconstructed = new long[3];
			int index = 0;
			while (it.hasNext()) {
				reconstructed[index++] = it.nextLong();
			}

			assertArrayEquals(new long[]{valueOf(30), valueOf(20), valueOf(10)}, reconstructed);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("matches sorted reference for arbitrary gap start keys across many leaves")
		void shouldMatchReferenceForGapStartKeysReverse() {
			final TreeTuple testTree = prepareRandomTree(913, 200);
			final int[] keys = testTree.plainArray();

			// probe every possible start key from below the minimum to above the maximum
			for (int startKey = keys[0] - 2; startKey <= keys[keys.length - 1] + 2; startKey++) {
				final InsertionPosition position =
					ArrayUtils.computeInsertPositionOfIntInOrderedArray(startKey, keys);
				// the reverse iterator yields every key <= startKey in descending order; when startKey is
				// already present the matching index is included, otherwise the insertion point is exclusive
				int from = position.alreadyPresent() ? position.position() : position.position() - 1;

				final PrimitiveIterator.OfLong it = testTree.bPlusTree().lesserOrEqualValueIterator(startKey);
				int index = from;
				while (it.hasNext()) {
					assertTrue(index >= 0, "Iterator returned more values than reference for key " + startKey);
					assertEquals(
						valueOf(keys[index]), it.nextLong(), "Mismatch at key " + startKey + ", index " + index);
					index--;
				}
				assertEquals(-1, index, "Iterator stopped early for start key " + startKey);
			}
		}

	}

	@Nested
	@DisplayName("Reverse key iteration")
	class ReverseKeyIterationTest {

		@Test
		@DisplayName("iterates keys backwards from exact existing key")
		void shouldIterateThroughLeafNodeKeysRightToLeftFromExactPosition() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final PrimitiveIterator.OfInt it = testTree.bPlusTree().lesserOrEqualKeyIterator(40);
			final int[] plainFullArray = testTree.plainArray();
			final InsertionPosition insertionPosition =
				ArrayUtils.computeInsertPositionOfIntInOrderedArray(40, plainFullArray);

			assertTrue(insertionPosition.alreadyPresent());
			final int endPos = insertionPosition.position();
			final int[] partialCopy = new int[endPos + 1];
			for (int i = endPos; i >= 0; i--) {
				partialCopy[endPos - i] = plainFullArray[i];
			}

			final int[] reconstructedArray = new int[partialCopy.length];
			int index = 0;
			while (it.hasNext()) {
				reconstructedArray[index++] = it.nextInt();
			}

			assertArrayEquals(partialCopy, reconstructedArray);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("returns empty reverse key iterator when below minimum")
		void shouldFailToIterateKeysRightToLeftThroughNonExistingValues() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final int[] keys = testTree.plainArray();
			final PrimitiveIterator.OfInt it = testTree.bPlusTree().lesserOrEqualKeyIterator(
				keys[0] - 1000);
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("iterates all keys in descending order")
		void shouldIterateAllKeysRightToLeft() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final int[] keys = testTree.plainArray();

			final PrimitiveIterator.OfInt it = testTree.bPlusTree().keyReverseIterator();
			int index = keys.length;
			while (it.hasNext()) {
				assertTrue(index > 0, "Iterator returned more keys than expected");
				assertEquals(keys[--index], it.nextInt());
			}

			assertEquals(0, index);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("iterates full tree in reverse from maximum key inclusive")
		void shouldIterateFullTreeInReverseFromMaxKey() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final int[] keys = testTree.plainArray();
			final int maxKey = keys[keys.length - 1];

			final PrimitiveIterator.OfInt it = testTree.bPlusTree().lesserOrEqualKeyIterator(maxKey);
			int count = 0;
			while (it.hasNext()) {
				it.nextInt();
				count++;
			}
			assertEquals(keys.length, count);
		}

		@Test
		@DisplayName("iterates keys backwards when start key falls in the gap before the first key of a leaf")
		void shouldIterateKeysFromKeyInGapBetweenLeavesReverse() {
			// key 40 falls below the first key of the leaf [50,60] while the preceding leaf [10,20,30]
			// still holds keys <= 40; the lesserOrEqualKeyIterator must cross the inter-leaf gap and
			// return, in descending order, every key <= 40
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);
			final int[] keys = {10, 20, 30, 50, 60};
			for (final int key : keys) {
				tree.insert(key, valueOf(key));
			}

			final PrimitiveIterator.OfInt it = tree.lesserOrEqualKeyIterator(40);
			final int[] reconstructed = new int[3];
			int index = 0;
			while (it.hasNext()) {
				reconstructed[index++] = it.nextInt();
			}

			assertArrayEquals(new int[]{30, 20, 10}, reconstructed);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("returns empty reverse key iterator on empty tree")
		void shouldReturnEmptyKeyReverseIteratorOnEmptyTree() {
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);
			final PrimitiveIterator.OfInt it = tree.keyReverseIterator();
			assertFalse(it.hasNext());
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("iterates single key on single-element tree")
		void shouldIterateKeyReverseOnSingleElementTree() {
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);
			tree.insert(42, valueOf(42));
			final PrimitiveIterator.OfInt it = tree.keyReverseIterator();
			assertTrue(it.hasNext());
			assertEquals(42, it.nextInt());
			assertFalse(it.hasNext());
		}

	}

	@Nested
	@DisplayName("Reverse entry iteration")
	class ReverseEntryIterationTest {

		@Test
		@DisplayName("iterates entries backwards from exact existing key")
		void shouldIterateThroughLeafNodeEntriesRightToLeftFromExactPosition() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final Iterator<Entry> it = testTree.bPlusTree().lesserOrEqualEntryIterator(40);
			final int[] plainFullArray = testTree.plainArray();
			final InsertionPosition insertionPosition =
				ArrayUtils.computeInsertPositionOfIntInOrderedArray(40, plainFullArray);

			assertTrue(insertionPosition.alreadyPresent());
			final int endPos = insertionPosition.position();
			final Entry[] partialCopy = new Entry[endPos + 1];
			for (int i = endPos; i >= 0; i--) {
				partialCopy[endPos - i] = new Entry(plainFullArray[i], valueOf(plainFullArray[i]));
			}

			final Entry[] reconstructedArray = new Entry[partialCopy.length];
			int index = 0;
			while (it.hasNext()) {
				reconstructedArray[index++] = it.next();
			}

			assertArrayEquals(partialCopy, reconstructedArray);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("returns empty reverse entry iterator when below minimum")
		void shouldFailToIterateEntriesRightToLeftThroughNonExistingValues() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final int[] keys = testTree.plainArray();
			final Iterator<Entry> it = testTree.bPlusTree().lesserOrEqualEntryIterator(
				keys[0] - 1000);
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("iterates entries backwards when start key falls in the gap before the first key of a leaf")
		void shouldIterateEntriesFromKeyInGapBetweenLeavesReverse() {
			// key 40 falls below the first key of the leaf [50,60] while the preceding leaf [10,20,30]
			// still holds entries <= 40; the iterator must cross the gap and return them in descending order
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);
			final int[] keys = {10, 20, 30, 50, 60};
			for (final int key : keys) {
				tree.insert(key, valueOf(key));
			}

			final Iterator<Entry> it = tree.lesserOrEqualEntryIterator(40);
			final Entry[] reconstructed = new Entry[3];
			int index = 0;
			while (it.hasNext()) {
				reconstructed[index++] = it.next();
			}

			final Entry[] expected = new Entry[]{
				new Entry(30, valueOf(30)), new Entry(20, valueOf(20)), new Entry(10, valueOf(10))
			};
			assertArrayEquals(expected, reconstructed);
			assertThrows(NoSuchElementException.class, it::next);
		}

	}

	@Nested
	@DisplayName("Transactional semantics")
	class TransactionalSemanticsTest {

		@Test
		@DisplayName("preserves original tree state after rollback")
		void shouldNotModifyOriginalTreeOnRollback() {
			final TreeTuple testTree = prepareRandomTree(1, 100);

			assertStateAfterRollback(
				testTree.bPlusTree(),
				tested -> {
					tested.insert(101, valueOf(101));
					for (int i = 0; i < testTree.plainArray().length; i = i + 2) {
						tested.delete(testTree.plainArray()[i]);
					}
				},
				(original, committed) -> {
					verifyTreeConsistency(original, testTree.plainArray());
					verifyForwardValueIterator(original, testTree.plainArray());
					assertEquals(testTree.totalElements(), original.size());
					assertNull(committed);
				}
			);
		}

		@Test
		@DisplayName("propagates value changes through transactional layer")
		void shouldHandleTransactionalValueChanges() {
			// the generic-value tree drove a TransactionalLayerProducer value here; with primitive long values
			// this verifies the in-transaction visibility and commit of a value mutation via upsert
			final TransactionalIntToLongBPlusTree theTree = new TransactionalIntToLongBPlusTree(3);
			theTree.insert(1, 100L);

			assertStateAfterCommit(
				theTree,
				tested -> tested.upsert(1, existing -> existing + 1L),
				(original, committed) -> {
					assertEquals(100L, original.search(1).orElseThrow());
					assertEquals(101L, committed.search(1).orElseThrow());
				}
			);
		}

		@Test
		@DisplayName("isolates insert, delete and upsert in single transaction")
		void shouldIsolateMultipleModificationsInTransaction() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final int[] originalArray = testTree.plainArray();

			assertStateAfterRollback(
				testTree.bPlusTree(),
				tested -> {
					tested.insert(9999, valueOf(9999));
					tested.delete(originalArray[0]);
					tested.upsert(originalArray[1], existing -> 1L);
				},
				(original, committed) -> {
					verifyTreeConsistency(original, originalArray);
					assertEquals(originalArray.length, original.size());
					assertNull(committed);
				}
			);
		}

		@Test
		@DisplayName("correctly commits mixed insert and delete operations")
		void shouldCommitInsertAndDeleteMix() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final int[] originalArray = testTree.plainArray();
			final int keyToDelete = originalArray[0];

			assertStateAfterCommit(
				testTree.bPlusTree(),
				tested -> {
					tested.delete(keyToDelete);
					tested.insert(9999, valueOf(9999));
				},
				(original, committed) -> {
					verifyTreeConsistency(original, originalArray);
					assertEquals(originalArray.length, committed.size());
					assertTrue(committed.search(keyToDelete).isEmpty());
					assertEquals(valueOf(9999), committed.search(9999).orElseThrow());
				}
			);
		}

		@Test
		@DisplayName("sees uncommitted modifications within transaction")
		void shouldSearchInTransactionalContext() {
			final TreeTuple testTree = prepareRandomTree(42, 50);

			assertStateAfterCommit(
				testTree.bPlusTree(),
				tested -> {
					tested.insert(9999, valueOf(9999));
					assertEquals(valueOf(9999), tested.search(9999).orElseThrow());
					tested.delete(9999);
					assertTrue(tested.search(9999).isEmpty());
				},
				(original, committed) -> {
					verifyTreeConsistency(committed, testTree.plainArray());
				}
			);
		}

		@Test
		@DisplayName("commit succeeds when a transaction splits then merges nodes under a fresh parent")
		void shouldCommitWhenMergeHappensUnderSplitCreatedParent() {
			// grow a tree large enough to have several internal levels, then within a single transaction
			// trigger node splits (which create internal nodes that do not yet participate in STM) followed
			// by deletes that force the just-created leaves to merge with their siblings - the merged-away
			// leaf still carries a transactional layer that the commit walk must not leave stale
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3, 1, 3, 1);
			final TreeMap<Integer, Long> reference = new TreeMap<>();
			for (int i = 0; i < 30; i++) {
				tree.insert(i, valueOf(i));
				reference.put(i, valueOf(i));
			}

			assertStateAfterCommit(
				tree,
				tested -> {
					// insertions to provoke splits inside the transaction
					for (int i = 100; i < 116; i++) {
						tested.insert(i, valueOf(i));
						reference.put(i, valueOf(i));
					}
					// deletions to provoke merges of the freshly created leaves
					for (int i = 100; i < 116; i++) {
						tested.delete(i);
						reference.remove(i);
					}
					for (int i = 0; i < 20; i++) {
						tested.delete(i);
						reference.remove(i);
					}
				},
				(original, committed) -> {
					final ConsistencyReport report = committed.getConsistencyReport();
					assertEquals(ConsistencyState.CONSISTENT, report.state(), report.report());

					final long[] expectedValues = new long[reference.size()];
					int refIndex = 0;
					for (final Long value : reference.values()) {
						expectedValues[refIndex++] = value;
					}
					final long[] actualValues = new long[expectedValues.length];
					int index = 0;
					final PrimitiveIterator.OfLong it = committed.valueIterator();
					while (it.hasNext()) {
						actualValues[index++] = it.nextLong();
					}
					assertEquals(expectedValues.length, index);
					assertArrayEquals(expectedValues, actualValues);
					assertEquals(reference.size(), committed.size());
				}
			);
		}

		@Test
		@DisplayName("commit of split- and merge-heavy transaction matches non-transactional application")
		void shouldMatchNonTransactionalApplicationAfterSplitAndMergeHeavyCommit() {
			for (long currentSeed = 0; currentSeed < 25; currentSeed++) {
				final long seed = currentSeed;
				final Random random = new Random(seed);

				// seed a tree with a handful of keys (non-transactional), mirror it into a sorted reference
				final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(
					3, 1, 3, 1);
				final TreeMap<Integer, Long> reference = new TreeMap<>();
				for (int i = 0; i < 8; i++) {
					final int key = random.nextInt(40);
					tree.insert(key, valueOf(key));
					reference.put(key, valueOf(key));
				}

				// record the exact operation sequence so it can be replayed non-transactionally
				final int[] ops = new int[60];
				final int[] opKeys = new int[60];
				for (int i = 0; i < ops.length; i++) {
					ops[i] = random.nextInt(3);
					opKeys[i] = random.nextInt(40);
				}

				assertStateAfterCommit(
					tree,
					tested -> {
						for (int i = 0; i < ops.length; i++) {
							final int key = opKeys[i];
							switch (ops[i]) {
								case 0 -> {
									tested.insert(key, valueOf(key));
									reference.put(key, valueOf(key));
								}
								case 1 -> {
									tested.delete(key);
									reference.remove(key);
								}
								default -> {
									tested.upsert(key, existing -> valueOf(key));
									reference.put(key, valueOf(key));
								}
							}
						}
					},
					(original, committed) -> {
						final ConsistencyReport report = committed.getConsistencyReport();
						assertEquals(
							ConsistencyState.CONSISTENT, report.state(),
							"Committed tree inconsistent for seed " + seed + ": " + report.report()
						);

						// committed tree must contain exactly the reference key/value pairs in order
						final int[] expectedKeys = reference.keySet().stream().mapToInt(Integer::intValue).toArray();
						final long[] expectedValues = new long[reference.size()];
						int refIndex = 0;
						for (final Long value : reference.values()) {
							expectedValues[refIndex++] = value;
						}

						final long[] actualValues = new long[expectedValues.length];
						int index = 0;
						final PrimitiveIterator.OfLong it = committed.valueIterator();
						while (it.hasNext()) {
							assertTrue(index < expectedValues.length, "Too many values for seed " + seed);
							actualValues[index++] = it.nextLong();
						}
						assertEquals(expectedValues.length, index, "Value count mismatch for seed " + seed);
						assertArrayEquals(expectedValues, actualValues, "Value mismatch for seed " + seed);
						assertEquals(expectedKeys.length, committed.size(), "Size mismatch for seed " + seed);
						for (final int key : expectedKeys) {
							assertEquals(
								reference.get(key), committed.search(key).orElseThrow(),
								"Search mismatch at key " + key + " for seed " + seed
							);
						}
					}
				);
			}
		}

	}

	@Nested
	@DisplayName("Tree structure visualization")
	class TreeStructureTest {

		@Test
		@DisplayName("prints empty leaf representation for empty tree")
		void shouldPrintEmptyTree() {
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);
			// empty leaf node has no key:value pairs, so the representation is empty
			assertEquals("", tree.toString());
		}

		@Test
		@DisplayName("prints simple two-element tree")
		void shouldPrintVerboseSimpleTree() {
			final TransactionalIntToLongBPlusTree bPlusTree = new TransactionalIntToLongBPlusTree(3);
			assertStateAfterCommit(
				bPlusTree,
				tested -> {
					bPlusTree.insert(5, 50L);
					bPlusTree.insert(15, 500L);
				},
				(original, committed) -> {
					assertEquals(2, committed.size());
					assertEquals(50L, committed.search(5).orElseThrow());
					assertEquals(500L, committed.search(15).orElseThrow());
					assertEquals("5:50, 15:500", committed.toString());

					assertEquals(0, original.size());
					assertTrue(original.search(5).isEmpty());
					assertTrue(original.search(15).isEmpty());
				}
			);
		}

		@Test
		@DisplayName("prints multi-level tree structure")
		void shouldPrintComplexTree() {
			final TransactionalIntToLongBPlusTree bPlusTree = new TransactionalIntToLongBPlusTree(3);

			assertStateAfterCommit(
				bPlusTree,
				tested -> {
					tested.insert(1, 10L);
					tested.insert(2, 20L);
					// this should cause a split
					tested.insert(3, 30L);
					tested.insert(4, 40L);
				},
				(original, committed) -> {
					assertEquals(4, committed.size());
					assertEquals(10L, committed.search(1).orElseThrow());
					assertEquals(20L, committed.search(2).orElseThrow());
					assertEquals(30L, committed.search(3).orElseThrow());
					assertEquals(40L, committed.search(4).orElseThrow());

					assertEquals(
						"""
							< 2:
							   1:10
							>=2:
							   2:20
							>=3:
							   3:30, 4:40""",
						committed.toString()
					);

					assertEquals(0, original.size());
					assertTrue(original.search(1).isEmpty());
					assertTrue(original.search(2).isEmpty());
					assertTrue(original.search(3).isEmpty());
					assertTrue(original.search(4).isEmpty());
				}
			);
		}

	}

	@Nested
	@DisplayName("Constructor validation")
	class ConstructorValidationTest {

		@Test
		@DisplayName("rejects block size smaller than three")
		void shouldRejectBlockSizeSmallerThanThree() {
			assertThrows(
				GenericEvitaInternalError.class, () -> new TransactionalIntToLongBPlusTree(2)
			);
		}

		@Test
		@DisplayName("rejects even internal node block size")
		void shouldRejectEvenInternalNodeBlockSize() {
			assertThrows(
				GenericEvitaInternalError.class, () -> new TransactionalIntToLongBPlusTree(3, 1, 4, 1)
			);
		}

		@Test
		@DisplayName("rejects internal node block size larger than value block size")
		void shouldRejectInternalNodeBlockSizeLargerThanValueBlockSize() {
			assertThrows(
				GenericEvitaInternalError.class, () -> new TransactionalIntToLongBPlusTree(3, 1, 5, 2)
			);
		}

		@Test
		@DisplayName("accepts internal node block size equal to value block size")
		void shouldAcceptInternalNodeBlockSizeEqualToValueBlockSize() {
			assertDoesNotThrow(() -> new TransactionalIntToLongBPlusTree(3, 1, 3, 1));
			assertDoesNotThrow(() -> new TransactionalIntToLongBPlusTree(3));
		}

		@Test
		@DisplayName("returns zero size for newly created tree")
		void shouldReturnZeroSizeForEmptyTree() {
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);
			assertEquals(0, tree.size());
		}

		@Test
		@DisplayName("reports consistent state for empty tree")
		void shouldReportConsistentForEmptyTree() {
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);
			final ConsistencyReport report = tree.getConsistencyReport();
			assertEquals(ConsistencyState.CONSISTENT, report.state());
		}

	}

	@Nested
	@DisplayName("Consistency oracle")
	class ConsistencyOracleTest {

		/**
		 * Replaces the root and element count of the tree via reflection so a hand-built node structure can be
		 * fed to the consistency oracle. This is the only way to exercise occupancy invariants on a node shape
		 * that the rebalancer never produces on its own.
		 *
		 * @param tree the tree whose internal state is replaced
		 * @param root the root node to install
		 * @param size the element count to report
		 */
		@SuppressWarnings("unchecked")
		private static void installRoot(
			@Nonnull TransactionalIntToLongBPlusTree tree,
			@Nonnull BPlusTreeNode<?> root,
			int size
		) {
			try {
				final Field rootField = AbstractTransactionalBPlusTree.class.getDeclaredField("root");
				rootField.setAccessible(true);
				((TransactionalReference<BPlusTreeNode<?>>)
					rootField.get(tree)).set(root);
				final Field sizeField = AbstractTransactionalBPlusTree.class.getDeclaredField("size");
				sizeField.setAccessible(true);
				((TransactionalReference<Integer>) sizeField.get(tree)).set(size);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError("Unable to install hand-built root", e);
			}
		}

		/**
		 * Builds a single-element leaf node carrying the given key with a matching primitive value.
		 *
		 * @param key the key (and value source) to store
		 * @return a leaf node holding exactly the one key
		 */
		@Nonnull
		private static TransactionalIntToLongBPlusTree.BPlusLeafTreeNode leaf(int key) {
			final int[] keys = {key};
			final long[] values = {valueOf(key)};
			return new TransactionalIntToLongBPlusTree.BPlusLeafTreeNode(
				keys, values, new int[3], new long[3], 0, 1, true
			);
		}

		/**
		 * Builds an internal node with the given separator keys routing to the supplied children. The number of
		 * keys must be exactly one less than the number of children.
		 *
		 * @param keys     the separator keys
		 * @param children the child nodes
		 * @return a hand-built internal node with the requested occupancy
		 */
		@Nonnull
		private static TransactionalIntToLongBPlusTree.BPlusInternalTreeNode internal(
			@Nonnull int[] keys, @Nonnull BPlusTreeNode<?>... children
		) {
			final int[] keyArray = new int[5];
			System.arraycopy(keys, 0, keyArray, 0, keys.length);
			final BPlusTreeNode<?>[] childArray =
				new BPlusTreeNode<?>[6];
			System.arraycopy(children, 0, childArray, 0, children.length);
			return new TransactionalIntToLongBPlusTree.BPlusInternalTreeNode(
				keyArray, childArray, 0, keys.length, 0, children.length, true
			);
		}

		@Test
		@DisplayName("flags a non-root internal node that holds fewer keys than the minimum")
		void shouldReportBrokenWhenInternalNodeHasTooFewKeys() {
			// minInternalNodeBlockSize = 2 means every non-root internal node must carry at least 2 keys
			// (3 children); a node with a single key (2 children) is under-occupied even though its child
			// count (2) is not below the minimum - the occupancy invariant is on keys, not children
			// (valueBlockSize must be >= internalNodeBlockSize, so it is raised to 5 to keep the
			// internal-node configuration valid)
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(5, 1, 5, 2);

			// under-occupied internal node: 2 children -> 1 key (keyCount 1 < minInternalNodeBlockSize 2),
			// yet size 2 satisfies the lenient child-count check
			final TransactionalIntToLongBPlusTree.BPlusInternalTreeNode underOccupied =
				internal(new int[]{2}, leaf(1), leaf(2));
			// properly occupied internal node: 3 children -> 2 keys (keyCount 2 == minimum)
			final TransactionalIntToLongBPlusTree.BPlusInternalTreeNode wellOccupied =
				internal(new int[]{4, 5}, leaf(3), leaf(4), leaf(5));

			final TransactionalIntToLongBPlusTree.BPlusInternalTreeNode root =
				internal(new int[]{3}, underOccupied, wellOccupied);

			installRoot(tree, root, 5);

			final ConsistencyReport report = tree.getConsistencyReport();
			assertEquals(ConsistencyState.BROKEN, report.state(), report.report());
		}

		@Test
		@DisplayName("accepts a non-root internal node that meets the minimum key count")
		void shouldReportConsistentWhenInternalNodeMeetsMinimum() {
			// counter-check that the tightened oracle does not flag a legitimately-occupied internal node
			// (valueBlockSize must be >= internalNodeBlockSize, so it is raised to 5 to keep the
			// internal-node configuration valid)
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(5, 1, 5, 2);

			// both children carry the minimum of 2 keys (3 leaves each)
			final TransactionalIntToLongBPlusTree.BPlusInternalTreeNode left =
				internal(new int[]{2, 3}, leaf(1), leaf(2), leaf(3));
			final TransactionalIntToLongBPlusTree.BPlusInternalTreeNode right =
				internal(new int[]{5, 6}, leaf(4), leaf(5), leaf(6));

			final TransactionalIntToLongBPlusTree.BPlusInternalTreeNode root =
				internal(new int[]{4}, left, right);

			installRoot(tree, root, 6);

			final ConsistencyReport report = tree.getConsistencyReport();
			assertEquals(ConsistencyState.CONSISTENT, report.state(), report.report());
		}

	}

	@Nested
	@DisplayName("STM invariants")
	class StmInvariantsTest {

		@Test
		@DisplayName("returns stable and unique id across instances")
		void shouldReturnStableAndUniqueId() {
			final TransactionalIntToLongBPlusTree tree1 = new TransactionalIntToLongBPlusTree(3);
			final TransactionalIntToLongBPlusTree tree2 = new TransactionalIntToLongBPlusTree(3);

			final long id1 = tree1.getId();
			final long id2 = tree2.getId();

			// id is stable on repeated calls
			assertEquals(id1, tree1.getId());
			assertEquals(id2, tree2.getId());
			// ids are unique across instances
			assertNotEquals(id1, id2);
		}

		@Test
		@DisplayName("commit with leaf-only tree exercises leaf branch")
		void shouldCommitTreeWithSingleLeafRoot() {
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);

			// insert only 1 element so root remains a leaf
			assertStateAfterCommit(
				tree,
				tested -> tested.insert(42, valueOf(42)),
				(original, committed) -> {
					assertEquals(0, original.size());
					assertEquals(1, committed.size());
					assertEquals(valueOf(42), committed.search(42).orElseThrow());
					verifyTreeConsistency(committed, 42);
				}
			);
		}

		@Test
		@DisplayName("committed tree is a different instance than original")
		void shouldReturnDifferentInstanceAfterCommit() {
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);

			assertStateAfterCommit(
				tree,
				tested -> tested.insert(1, valueOf(1)),
				(original, committed) -> {
					assertNotSame(original, committed);
					assertNotEquals(original.getId(), committed.getId());
				}
			);
		}

		@Test
		@DisplayName("commit with zero mutations yields structurally equivalent copy")
		void shouldProduceEquivalentCopyOnNoOpCommit() {
			final TreeTuple testTree = prepareRandomTree(42, 30);
			final int[] originalArray = testTree.plainArray();

			assertStateAfterCommit(
				testTree.bPlusTree(),
				tested -> {
					// no mutations at all
				},
				(original, committed) -> {
					assertNotSame(original, committed);
					// both trees should have the same contents
					assertEquals(original.size(), committed.size());
					verifyTreeConsistency(original, originalArray);
					verifyTreeConsistency(committed, originalArray);
				}
			);
		}

		@Test
		@DisplayName("committed tree has no dangling layers")
		void shouldNotHaveDanglingLayersAfterCommit() {
			final TreeTuple testTree = prepareRandomTree(42, 30);
			final int[] originalArray = testTree.plainArray();

			assertStateAfterCommit(
				testTree.bPlusTree(),
				tested -> {
					tested.insert(5555, valueOf(5555));
					tested.delete(originalArray[0]);
				},
				(original, committed) -> {
					// after commit, the committed tree should be consistent
					// and independently usable (no dangling tx layers)
					final int[] expectedArray = ArrayUtils.insertIntIntoOrderedArray(
						5555, ArrayUtils.removeIntFromOrderedArray(originalArray[0], originalArray)
					);
					verifyTreeConsistency(committed, expectedArray);

					// the committed tree should be further modifiable
					// in a new transaction
					assertStateAfterCommit(
						committed,
						tested2 -> tested2.insert(6666, valueOf(6666)),
						(original2, committed2) -> {
							verifyTreeConsistency(original2, expectedArray);
							assertEquals(valueOf(6666), committed2.search(6666).orElseThrow());
						}
					);
				}
			);
		}

	}

	@Nested
	@DisplayName("Extended constructor validation")
	class ExtendedConstructorValidationTest {

		@Test
		@DisplayName("rejects minValueBlockSize less than one")
		void shouldRejectMinValueBlockSizeLessThanOne() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new TransactionalIntToLongBPlusTree(3, 0, 3, 1)
			);
		}

		@Test
		@DisplayName("rejects minValueBlockSize greater than ceil(valueBlockSize/2) - 1")
		void shouldRejectMinValueBlockSizeTooLarge() {
			// valueBlockSize=3, ceil(3/2)-1 = 1, so minValueBlockSize=2 is invalid
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new TransactionalIntToLongBPlusTree(3, 2, 3, 1)
			);
		}

		@Test
		@DisplayName("rejects minInternalNodeBlockSize less than one")
		void shouldRejectMinInternalNodeBlockSizeLessThanOne() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new TransactionalIntToLongBPlusTree(3, 1, 3, 0)
			);
		}

		@Test
		@DisplayName("rejects minInternalNodeBlockSize greater than ceil(internalNodeBlockSize/2) - 1")
		void shouldRejectMinInternalNodeBlockSizeTooLarge() {
			// internalNodeBlockSize=3, ceil(3/2)-1 = 1, so minInternal=2 is invalid
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new TransactionalIntToLongBPlusTree(3, 1, 3, 2)
			);
		}

		@Test
		@DisplayName("rejects internalNodeBlockSize less than three")
		void shouldRejectInternalNodeBlockSizeLessThanThree() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new TransactionalIntToLongBPlusTree(3, 1, 1, 0)
			);
		}

		@Test
		@DisplayName("default block sizes are applied by the no-arg constructor")
		void shouldSetDefaultBlockSizesWithNoArgConstructor() {
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree();
			assertEquals(64, tree.getValueBlockSize());
			assertEquals(31, tree.getMinValueBlockSize());
			assertEquals(31, tree.getInternalNodeBlockSize());
			assertEquals(15, tree.getMinInternalNodeBlockSize());
			assertEquals(0, tree.size());

			tree.insert(1, valueOf(1));
			assertEquals(1, tree.size());
		}

	}

	@Nested
	@DisplayName("Non-transactional mode")
	class NonTransactionalModeTest {

		@Test
		@DisplayName("insert, search, upsert and delete work without a transaction")
		void shouldExerciseCrudWithoutTransaction() {
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);

			// insert
			tree.insert(10, valueOf(10));
			tree.insert(20, valueOf(20));
			tree.insert(30, valueOf(30));
			assertEquals(3, tree.size());

			// search
			assertEquals(valueOf(10), tree.search(10).orElseThrow());
			assertEquals(valueOf(20), tree.search(20).orElseThrow());
			assertEquals(valueOf(30), tree.search(30).orElseThrow());
			assertTrue(tree.search(99).isEmpty());

			// verify consistency before mutation
			verifyTreeConsistency(tree, 10, 20, 30);

			// upsert -- update existing value (changes value, not key)
			tree.upsert(20, existing -> 999L);
			assertEquals(999L, tree.search(20).orElseThrow());
			assertEquals(3, tree.size());

			// upsert -- insert new entry
			tree.upsert(25, existing -> valueOf(25));
			assertEquals(valueOf(25), tree.search(25).orElseThrow());
			assertEquals(4, tree.size());

			// delete
			tree.delete(10);
			assertEquals(3, tree.size());
			assertTrue(tree.search(10).isEmpty());

			// verify consistency -- cannot use verifyTreeConsistency because value for key 20 is now 999L
			final ConsistencyReport report = tree.getConsistencyReport();
			assertEquals(ConsistencyState.CONSISTENT, report.state(), report.report());
		}

		@Test
		@DisplayName("insert triggers split without a transaction")
		void shouldSplitLeafNodeWithoutTransaction() {
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);
			for (int i = 1; i <= 10; i++) {
				tree.insert(i, valueOf(i));
			}
			assertEquals(10, tree.size());
			verifyTreeConsistency(tree, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
		}

	}

	@Nested
	@DisplayName("Iterator transactional consistency")
	class IteratorTransactionalConsistencyTest {

		@Test
		@DisplayName("iterators reflect uncommitted inserts and deletes")
		void shouldReflectUncommittedChangesInIterators() {
			final TreeTuple testTree = prepareRandomTree(42, 30);
			final int[] originalArray = testTree.plainArray();

			assertStateAfterCommit(
				testTree.bPlusTree(),
				tested -> {
					// insert a new key at the end
					tested.insert(9999, valueOf(9999));
					// delete the first key
					tested.delete(originalArray[0]);

					// build expected array
					final int[] expected = ArrayUtils.insertIntIntoOrderedArray(
						9999,
						ArrayUtils.removeIntFromOrderedArray(
							originalArray[0], originalArray
						)
					);

					// forward value iterator should reflect changes
					verifyForwardValueIterator(tested, expected);
					// reverse value iterator should also reflect changes
					verifyReverseValueIterator(tested, expected);
				},
				(original, committed) -> {
					verifyTreeConsistency(original, originalArray);
				}
			);
		}

	}

	@Nested
	@DisplayName("Rollback scenarios")
	class RollbackScenariosTest {

		@Test
		@DisplayName("rollback after split restores original structure")
		void shouldRestoreOriginalAfterSplitCausingRollback() {
			final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(3);
			tree.insert(1, valueOf(1));
			tree.insert(2, valueOf(2));

			assertStateAfterRollback(
				tree,
				tested -> {
					// these inserts will trigger splits
					tested.insert(3, valueOf(3));
					tested.insert(4, valueOf(4));
					tested.insert(5, valueOf(5));
					tested.insert(6, valueOf(6));
					assertEquals(6, tested.size());
				},
				(original, committed) -> {
					// original should be unmodified
					assertEquals(2, original.size());
					assertEquals(valueOf(1), original.search(1).orElseThrow());
					assertEquals(valueOf(2), original.search(2).orElseThrow());
					assertTrue(original.search(3).isEmpty());
					verifyTreeConsistency(original, 1, 2);
					assertNull(committed);
				}
			);
		}

		@Test
		@DisplayName("after rollback, iterators produce pre-transaction sequence")
		void shouldIteratePreTransactionSequenceAfterRollback() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final int[] originalArray = testTree.plainArray();

			assertStateAfterRollback(
				testTree.bPlusTree(),
				tested -> {
					tested.insert(8888, valueOf(8888));
					tested.delete(originalArray[0]);
				},
				(original, committed) -> {
					assertNull(committed);
					// iterators on the original should still produce the pre-transaction sequence
					verifyForwardValueIterator(original, originalArray);
					verifyReverseValueIterator(original, originalArray);
				}
			);
		}

	}

}
