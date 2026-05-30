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
import io.evitadb.index.bPlusTree.TransactionalIntBPlusTree.Entry;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.list.TransactionalList;
import io.evitadb.index.reference.TransactionalReference;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
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
 * This test verifies the correctness of the {@link TransactionalIntBPlusTree} implementation. It covers insert,
 * search, upsert, delete, rebalancing (steal and merge), forward and reverse iteration of both keys and values,
 * transactional semantics, tree visualization, constructor validation, and the internal consistency oracle.
 * Bounded, fixed-seed randomized churn tests guard the rebalancing and commit machinery against regressions; the
 * open-ended generational soak test lives in the long-running test module.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@SuppressWarnings("StringConcatenationMissingWhitespace")
@DisplayName("Transactional int B+ tree")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class TransactionalIntBPlusTreeTest {

	/**
	 * Verifies tree consistency by checking the internal consistency report and validating both forward and reverse
	 * value iterators against the expected key array.
	 *
	 * @param bPlusTree     the tree to verify
	 * @param expectedArray the expected sorted key array
	 */
	private static void verifyTreeConsistency(
		@Nonnull TransactionalIntBPlusTree<String> bPlusTree, @Nonnull int... expectedArray
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
		@Nonnull TransactionalIntBPlusTree<String> tree, @Nonnull int... keyArray
	) {
		final String[] expectedArray = Arrays.stream(keyArray).mapToObj(i -> "Value" + i).toArray(String[]::new);
		final String[] reconstructedArray = new String[expectedArray.length];
		int index = 0;
		final Iterator<String> it = tree.valueIterator();
		while (it.hasNext()) {
			reconstructedArray[index++] = it.next();
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
		@Nonnull TransactionalIntBPlusTree<String> tree, @Nonnull int... keyArray
	) {
		final String[] expectedArray = Arrays.stream(keyArray).mapToObj(i -> "Value" + i).toArray(String[]::new);
		final String[] reconstructedArray = new String[expectedArray.length];
		int index = expectedArray.length;
		final Iterator<String> it = tree.valueReverseIterator();
		while (it.hasNext()) {
			reconstructedArray[--index] = it.next();
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
		final TransactionalIntBPlusTree<String> bPlusTree = new TransactionalIntBPlusTree<>(
			valueBlockSize, minValueBlockSize, internalNodeSize, minInternalNodeSize, String.class
		);
		int[] plainArray = new int[0];
		do {
			final int i = random.nextInt(totalElements << 1);
			bPlusTree.insert(i, "Value" + i);
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
	private static TransactionalIntBPlusTree<String> deleteAndVerify(
		@Nonnull TransactionalIntBPlusTree<String> tree,
		@Nonnull AtomicReference<int[]> expectedArray, int keyToDelete
	) {
		final AtomicReference<TransactionalIntBPlusTree<String>> result = new AtomicReference<>();
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
		@Nonnull TransactionalIntBPlusTree<String> bPlusTree,
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
		 * Converts the key array to a string value array where each element is `"Value" + key`.
		 *
		 * @return the string array of expected values
		 */
		@Nonnull
		public String[] asStringArray() {
			final String[] plainArrayAsString = new String[this.plainArray.length];
			for (int i = 0; i < this.plainArray.length; i++) {
				plainArrayAsString[i] = "Value" + this.plainArray[i];
			}
			return plainArrayAsString;
		}

	}

	@Nested
	@DisplayName("Insert operations")
	class InsertOperationsTest {

		@Test
		@DisplayName("overwrites value when inserting duplicate key")
		void shouldOverwriteDuplicateKeys() {
			final TransactionalIntBPlusTree<String> bPlusTree = new TransactionalIntBPlusTree<>(3, String.class);
			assertStateAfterCommit(
				bPlusTree,
				tested -> {
					tested.insert(5, "Value5");
					tested.insert(5, "NewValue5");
				},
				(original, committed) -> {
					assertEquals(0, original.size());
					assertNull(original.search(5).orElse(null));

					assertEquals(1, committed.size());
					assertEquals("NewValue5", committed.search(5).orElse(null));
				}
			);
		}

		@Test
		@DisplayName("splits leaf node when capacity is exceeded")
		void shouldSplitNodeWhenFull() {
			final TransactionalIntBPlusTree<String> bPlusTree = new TransactionalIntBPlusTree<>(3, String.class);

			assertStateAfterCommit(
				bPlusTree,
				tested -> {
					tested.insert(1, "Value1");
					tested.insert(2, "Value2");
					// this should cause a split
					tested.insert(3, "Value3");
					tested.insert(4, "Value4");
				},
				(original, committed) -> {
					assertEquals(4, committed.size());
					assertEquals("Value1", committed.search(1).orElse(null));
					assertEquals("Value2", committed.search(2).orElse(null));
					assertEquals("Value3", committed.search(3).orElse(null));
					assertEquals("Value4", committed.search(4).orElse(null));

					verifyTreeConsistency(committed, 1, 2, 3, 4);

					assertEquals(0, original.size());
					assertNull(original.search(1).orElse(null));
					assertNull(original.search(2).orElse(null));
					assertNull(original.search(3).orElse(null));
					assertNull(original.search(4).orElse(null));
				}
			);
		}

		@Test
		@DisplayName("maintains balanced structure after sequential forward insertions")
		void shouldMaintainBalanced() {
			final TransactionalIntBPlusTree<String> bPlusTree = new TransactionalIntBPlusTree<>(3, String.class);
			final AtomicReference<int[]> keys = new AtomicReference<>(new int[0]);
			assertStateAfterCommit(
				bPlusTree,
				tested -> {
					for (int i = 1; i <= 20; i++) {
						tested.insert(i, "Value" + i);
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
							            1:Value1
							         >=2:
							            2:Value2
							      >=3:
							         < 4:
							            3:Value3
							         >=4:
							            4:Value4
							   >=5:
							      < 7:
							         < 6:
							            5:Value5
							         >=6:
							            6:Value6
							      >=7:
							         < 8:
							            7:Value7
							         >=8:
							            8:Value8
							>=9:
							   < 13:
							      < 11:
							         < 10:
							            9:Value9
							         >=10:
							            10:Value10
							      >=11:
							         < 12:
							            11:Value11
							         >=12:
							            12:Value12
							   >=13:
							      < 15:
							         < 14:
							            13:Value13
							         >=14:
							            14:Value14
							      >=15:
							         < 16:
							            15:Value15
							         >=16:
							            16:Value16
							      >=17:
							         < 18:
							            17:Value17
							         >=18:
							            18:Value18
							         >=19:
							            19:Value19, 20:Value20""",
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
			final TransactionalIntBPlusTree<String> bPlusTree = new TransactionalIntBPlusTree<>(3, String.class);
			final AtomicReference<int[]> keys = new AtomicReference<>(new int[0]);
			assertStateAfterCommit(
				bPlusTree,
				tested -> {
					for (int i = 20; i > 0; i--) {
						tested.insert(i, "Value" + i);
						keys.set(ArrayUtils.insertIntIntoOrderedArray(i, keys.get()));
					}
				},
				(original, committed) -> {
					assertEquals(
						"""
							< 13:
							   < 5:
							      < 3:
							         1:Value1, 2:Value2
							      >=3:
							         3:Value3, 4:Value4
							   >=5:
							      < 7:
							         5:Value5, 6:Value6
							      >=7:
							         7:Value7, 8:Value8
							   >=9:
							      < 11:
							         9:Value9, 10:Value10
							      >=11:
							         11:Value11, 12:Value12
							>=13:
							   < 17:
							      < 15:
							         13:Value13, 14:Value14
							      >=15:
							         15:Value15, 16:Value16
							   >=17:
							      < 19:
							         17:Value17, 18:Value18
							      >=19:
							         19:Value19, 20:Value20""",
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
			final TransactionalIntBPlusTree<String> tree = new TransactionalIntBPlusTree<>(3, String.class);
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
			final TransactionalIntBPlusTree<String> tree = new TransactionalIntBPlusTree<>(3, String.class);
			tree.insert(42, "Value42");
			assertEquals(1, tree.size());
			assertEquals("Value42", tree.search(42).orElse(null));
		}

	}

	@Nested
	@DisplayName("Upsert operations")
	class UpsertOperationsTest {

		@Test
		@DisplayName("updates value for existing key")
		void shouldUpdateExistingValue() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final TransactionalIntBPlusTree<String> theTree = testTree.bPlusTree();
			final int[] expectedArray = testTree.plainArray();

			assertStateAfterCommit(
				theTree,
				tested -> {
					assertEquals("Value13", tested.search(13).orElse(null));
					tested.upsert(13, existingValue -> "NewValue18");
				},
				(original, committed) -> {
					verifyTreeConsistency(original, expectedArray);
					assertEquals("NewValue18", committed.search(13).orElse(null));
					committed.upsert(13, existingValue -> "Value13");
					verifyTreeConsistency(committed, expectedArray);
				}
			);
		}

		@Test
		@DisplayName("inserts new entry for non-existent key")
		void shouldInsertNonExistingValueViaUpsert() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final TransactionalIntBPlusTree<String> theTree = testTree.bPlusTree();
			final int[] expectedArray = testTree.plainArray();

			assertStateAfterCommit(
				theTree,
				tested -> {
					assertNull(tested.search(100).orElse(null));
					tested.upsert(100, existingValue -> "Value100");
				},
				(original, committed) -> {
					verifyTreeConsistency(original, expectedArray);
					assertEquals("Value100", committed.search(100).orElse(null));
					verifyTreeConsistency(
						committed, ArrayUtils.insertIntIntoOrderedArray(100, expectedArray)
					);
				}
			);
		}

		@Test
		@DisplayName("releases diff layer of a producer value replaced by a different instance via upsert")
		void shouldNotLeakLayerWhenProducerValueIsReplacedByDifferentInstanceViaUpsert() {
			final TransactionalIntBPlusTree<TransactionalBitmap> tree =
				new TransactionalIntBPlusTree<>(TransactionalBitmap.class, o -> (TransactionalBitmap) o);
			tree.insert(1, new TransactionalBitmap(new int[]{10}));

			assertStateAfterCommit(
				tree,
				original -> {
					// mutate the existing value (opens an ALIVE layer) and then replace it with a brand-new
					// instance via upsert - the discarded old instance's layer must be released
					original.upsert(1, existing -> {
						existing.add(11);
						return new TransactionalBitmap(new int[]{30});
					});
				},
				(original, committed) -> {
					assertEquals(1, committed.size());
					assertArrayEquals(new int[]{30}, committed.search(1).orElseThrow().getArray());
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
			final TransactionalIntBPlusTree<String> theTree = testTree.bPlusTree();
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
			final TransactionalIntBPlusTree<String> tree = new TransactionalIntBPlusTree<>(3, String.class);
			tree.insert(42, "Value42");
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
				final TransactionalIntBPlusTree<String> tree = new TransactionalIntBPlusTree<>(
					3, 1, 3, 1, String.class);
				final TreeMap<Integer, String> reference = new TreeMap<>();

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
						tree.insert(key, "Value" + key);
						reference.put(key, "Value" + key);
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
		@DisplayName("releases diff layer of a modified producer value that is deleted in the same transaction")
		void shouldNotLeakLayerWhenModifiedProducerValueIsDeleted() {
			final TransactionalIntBPlusTree<TransactionalBitmap> tree =
				new TransactionalIntBPlusTree<>(TransactionalBitmap.class, o -> (TransactionalBitmap) o);
			tree.insert(1, new TransactionalBitmap(new int[]{10}));

			assertStateAfterCommit(
				tree,
				original -> {
					// open an ALIVE layer on the inner bitmap, then drop the whole value in the same txn
					original.search(1).orElseThrow().add(11);
					original.delete(1);
				},
				(original, committed) -> {
					assertEquals(1, original.size());
					assertEquals(0, committed.size());
				}
			);
		}

		@Test
		@DisplayName("sweeps cleanly when a freshly created producer value is added and deleted in the same transaction")
		void shouldNotLeakLayerWhenFreshlyCreatedProducerValueIsAddedThenDeleted() {
			final TransactionalIntBPlusTree<TransactionalBitmap> tree =
				new TransactionalIntBPlusTree<>(TransactionalBitmap.class, o -> (TransactionalBitmap) o);

			assertStateAfterCommit(
				tree,
				original -> {
					// create a brand-new producer value, mutate it, then delete it within the same txn
					final TransactionalBitmap fresh = new TransactionalBitmap(new int[]{20});
					original.insert(2, fresh);
					fresh.add(21);
					original.delete(2);
				},
				(original, committed) -> {
					assertEquals(0, original.size());
					assertEquals(0, committed.size());
				}
			);
		}

		@Test
		@DisplayName("releases inner layers of a composite (Void) producer value deleted in the same transaction")
		void shouldNotLeakLayerWhenCompositeProducerValueIsDeleted() {
			// ValueToRecordBitmap is a VoidTransactionMemoryProducer: it never opens a transactional layer of its
			// own, only its inner TransactionalBitmap child does - exactly the composite producer that exposed the
			// orphaned-child-layer bug fixed by Fix A.
			final TransactionalIntBPlusTree<ValueToRecordBitmap> tree =
				new TransactionalIntBPlusTree<>(ValueToRecordBitmap.class, o -> (ValueToRecordBitmap) o);
			tree.insert(1, new ValueToRecordBitmap("a", 10));

			assertStateAfterCommit(
				tree,
				original -> {
					// open an ALIVE layer on the inner bitmap child (the parent producer stays layer-less), then
					// drop the whole composite value in the same txn - the child's layer must still be released
					original.search(1).orElseThrow().getRecordIds().add(11);
					original.delete(1);
				},
				(original, committed) -> {
					assertEquals(1, original.size());
					assertEquals(0, committed.size());
					assertTrue(committed.search(1).isEmpty());
				}
			);
		}

		@Test
		@DisplayName("sweeps cleanly when a tree with composite values is created and discarded within a transaction")
		void shouldNotLeakLayerWhenTreeIsCreatedAndDiscardedWithinTransaction() {
			// outer tree we never touch - it only provides a transactional context to drive the commit sweep
			final TransactionalIntBPlusTree<String> outer =
				new TransactionalIntBPlusTree<>(3, String.class);

			assertStateAfterCommit(
				outer,
				original -> {
					// build a throwaway sub-tree, open ALIVE layers on its node graph and on inner producer children,
					// then discard the whole sub-tree by removing its layers via the maintainer (Fix B). Without the
					// deep recursion the sub-tree's size/root references, node graph and producer-child layers would
					// remain ALIVE and trip StaleTransactionMemoryException during the commit sweep (INV-5).
					final TransactionalIntBPlusTree<ValueToRecordBitmap> discarded =
						new TransactionalIntBPlusTree<>(
							ValueToRecordBitmap.class, o -> (ValueToRecordBitmap) o
						);
					for (int i = 0; i < 20; i++) {
						discarded.insert(i, new ValueToRecordBitmap("v" + i, i));
					}
					// open ALIVE inner-child layers on a handful of composite values
					for (int i = 0; i < 20; i += 3) {
						discarded.search(i).orElseThrow().getRecordIds().add(1000 + i);
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
			final TransactionalIntBPlusTree<String> bPlusTree = new TransactionalIntBPlusTree<>(3, String.class);
			final AtomicReference<TransactionalIntBPlusTree<String>> theCommittedTree = new AtomicReference<>();

			assertStateAfterCommit(
				bPlusTree,
				tested -> {
					tested.insert(15, "Value15");
					tested.insert(17, "Value17");
					// this should cause a split
					tested.insert(20, "Value20");
					tested.insert(23, "Value23");
					tested.insert(25, "Value25");
					tested.insert(14, "Value14");
				},
				(original, committed) -> {
					verifyTreeConsistency(committed, 14, 15, 17, 20, 23, 25);
					assertEquals(
						"""
							< 20:
							   < 17:
							      14:Value14, 15:Value15
							   >=17:
							      17:Value17
							>=20:
							   < 23:
							      20:Value20
							   >=23:
							      23:Value23, 25:Value25""",
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
							      14:Value14
							   >=15:
							      15:Value15
							>=20:
							   < 23:
							      20:Value20
							   >=23:
							      23:Value23, 25:Value25""",
						committed.toString()
					);
				}
			);
		}

		@Test
		@DisplayName("steals from right sibling after multiple deletions")
		void shouldStealFromRightNode() {
			final TransactionalIntBPlusTree<String> bPlusTree = new TransactionalIntBPlusTree<>(3, String.class);
			final AtomicReference<TransactionalIntBPlusTree<String>> theCommittedTree = new AtomicReference<>();

			assertStateAfterCommit(
				bPlusTree,
				tested -> {
					tested.insert(15, "Value15");
					tested.insert(17, "Value17");
					// this should cause a split
					tested.insert(20, "Value20");
					tested.insert(23, "Value23");
					tested.insert(25, "Value25");
					tested.insert(14, "Value14");
					tested.insert(16, "Value16");
					tested.insert(19, "Value19");
					tested.insert(18, "Value18");
					tested.insert(11, "Value11");
					tested.insert(12, "Value12");
					tested.insert(10, "Value10");
				},
				(original, committed) -> {
					verifyTreeConsistency(committed, 10, 11, 12, 14, 15, 16, 17, 18, 19, 20, 23, 25);

					assertEquals(
						"""
							< 17:
							   < 12:
							      10:Value10, 11:Value11
							   >=12:
							      12:Value12, 14:Value14
							   >=15:
							      15:Value15, 16:Value16
							>=17:
							   < 18:
							      17:Value17
							   >=18:
							      18:Value18, 19:Value19
							>=20:
							   < 23:
							      20:Value20
							   >=23:
							      23:Value23, 25:Value25""",
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
							      12:Value12
							   >=14:
							      14:Value14
							   >=15:
							      15:Value15, 16:Value16
							>=17:
							   < 18:
							      17:Value17
							   >=18:
							      18:Value18, 19:Value19
							>=20:
							   < 23:
							      20:Value20
							   >=23:
							      23:Value23, 25:Value25""",
						committed.toString()
					);
				}
			);
		}

		@Test
		@DisplayName("steals from left sibling after multiple deletions")
		void shouldStealFromLeftNode() {
			final TransactionalIntBPlusTree<String> bPlusTree = new TransactionalIntBPlusTree<>(3, String.class);
			final AtomicReference<TransactionalIntBPlusTree<String>> theCommittedTree = new AtomicReference<>();

			assertStateAfterCommit(
				bPlusTree,
				tested -> {
					tested.insert(15, "Value15");
					tested.insert(17, "Value17");
					// this should cause a split
					tested.insert(20, "Value20");
					tested.insert(23, "Value23");
					tested.insert(25, "Value25");
					tested.insert(14, "Value14");
					tested.insert(16, "Value16");
					tested.insert(19, "Value19");
					tested.insert(18, "Value18");
					tested.insert(11, "Value11");
					tested.insert(12, "Value12");
				},
				(original, committed) -> {
					verifyTreeConsistency(committed, 11, 12, 14, 15, 16, 17, 18, 19, 20, 23, 25);
					assertEquals(
						"""
							< 17:
							   < 12:
							      11:Value11
							   >=12:
							      12:Value12, 14:Value14
							   >=15:
							      15:Value15, 16:Value16
							>=17:
							   < 18:
							      17:Value17
							   >=18:
							      18:Value18, 19:Value19
							>=20:
							   < 23:
							      20:Value20
							   >=23:
							      23:Value23, 25:Value25""",
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
							      11:Value11
							   >=12:
							      12:Value12
							   >=14:
							      14:Value14
							>=17:
							   < 18:
							      17:Value17
							   >=18:
							      18:Value18, 19:Value19
							>=20:
							   < 23:
							      20:Value20
							   >=23:
							      23:Value23, 25:Value25""",
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
		private static TransactionalIntBPlusTree.BPlusInternalTreeNode emptyInternalNode() {
			return new TransactionalIntBPlusTree.BPlusInternalTreeNode(
				new int[3], new TransactionalIntBPlusTree.BPlusTreeNode<?>[4], 0, 0, 0, 0, true
			);
		}

		/**
		 * Builds a single-element leaf node carrying the given key with a matching string value.
		 *
		 * @param key the key (and value suffix) to store
		 * @return a leaf node holding exactly the one key
		 */
		@Nonnull
		private static TransactionalIntBPlusTree.BPlusLeafTreeNode<String> leaf(int key) {
			final int[] keys = {key};
			final String[] values = {"Value" + key};
			return new TransactionalIntBPlusTree.BPlusLeafTreeNode<>(
				keys, values, new int[3], new String[3], 0, 1, true, null
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
		private static TransactionalIntBPlusTree.BPlusInternalTreeNode internal(
			@Nonnull int[] keys, @Nonnull TransactionalIntBPlusTree.BPlusTreeNode<?>... children
		) {
			final int[] keyArray = new int[3];
			System.arraycopy(keys, 0, keyArray, 0, keys.length);
			final TransactionalIntBPlusTree.BPlusTreeNode<?>[] childArray =
				new TransactionalIntBPlusTree.BPlusTreeNode<?>[4];
			System.arraycopy(children, 0, childArray, 0, children.length);
			return new TransactionalIntBPlusTree.BPlusInternalTreeNode(
				keyArray, childArray, 0, keys.length, 0, children.length, true
			);
		}

		private static void exerciseChurn(
			int valueBlockSize, int minValueBlockSize,
			int internalNodeBlockSize, int minInternalNodeBlockSize, long seed
		) {
			final Random random = new Random(seed);
			final TransactionalIntBPlusTree<String> tree = new TransactionalIntBPlusTree<>(
				valueBlockSize, minValueBlockSize, internalNodeBlockSize, minInternalNodeBlockSize, String.class
			);
			final TreeMap<Integer, String> reference = new TreeMap<>();
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
					tree.insert(key, "Value" + key);
					reference.put(key, "Value" + key);
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
			TransactionalIntBPlusTree<String> tree = testTree.bPlusTree();

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
			TransactionalIntBPlusTree<String> tree = testTree.bPlusTree();

			tree = deleteAndVerify(tree, expectedArray, 92);
			deleteAndVerify(tree, expectedArray, 87);
		}

		@Test
		@DisplayName("cascades multiple merges with left parent")
		void shouldMergeCausingIntermediateParentToMergeLeft() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final AtomicReference<int[]> expectedArray = new AtomicReference<>(testTree.plainArray());
			TransactionalIntBPlusTree<String> tree = testTree.bPlusTree();

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
			TransactionalIntBPlusTree<String> tree = testTree.bPlusTree();

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
			final TransactionalIntBPlusTree.BPlusInternalTreeNode empty = emptyInternalNode();
			final TransactionalIntBPlusTree.BPlusInternalTreeNode sibling =
				internal(new int[]{2}, leaf(1), leaf(2));

			assertThrows(GenericEvitaInternalError.class, () -> empty.mergeWithLeft(sibling));
		}

		@Test
		@DisplayName("rejects merging the right sibling into an empty internal node")
		void shouldRejectMergeWithRightIntoEmptyInternalNode() {
			final TransactionalIntBPlusTree.BPlusInternalTreeNode empty = emptyInternalNode();
			final TransactionalIntBPlusTree.BPlusInternalTreeNode sibling =
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

			final String[] reconstructedArray = new String[testTree.totalElements()];
			int index = 0;
			final Iterator<String> it = testTree.bPlusTree().valueIterator();
			while (it.hasNext()) {
				reconstructedArray[index++] = it.next();
			}

			assertArrayEquals(testTree.asStringArray(), reconstructedArray);
			assertThrows(NoSuchElementException.class, it::next);
			assertEquals(testTree.totalElements(), testTree.bPlusTree().size());
		}

		@Test
		@DisplayName("iterates values from exact existing key")
		void shouldIterateThroughLeafNodeValuesLeftToRightFromExactPosition() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final Iterator<String> it = testTree.bPlusTree().greaterOrEqualValueIterator(40);
			final int[] plainFullArray = testTree.plainArray();
			final InsertionPosition insertionPosition = ArrayUtils.computeInsertPositionOfIntInOrderedArray(
				40, plainFullArray);

			assertTrue(insertionPosition.alreadyPresent());
			final int startPos = insertionPosition.position();
			final String[] partialCopy = new String[plainFullArray.length - startPos];
			for (int i = startPos; i < plainFullArray.length; i++) {
				partialCopy[i - startPos] = "Value" + plainFullArray[i];
			}

			final String[] reconstructedArray = new String[partialCopy.length];
			int index = 0;
			while (it.hasNext()) {
				reconstructedArray[index++] = it.next();
			}

			assertArrayEquals(partialCopy, reconstructedArray);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("iterates values from non-existing key position")
		void shouldIterateThroughLeafNodeValuesLeftToRightFromExactNonExistingPosition() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final Iterator<String> it = testTree.bPlusTree().greaterOrEqualValueIterator(39);
			final int[] plainFullArray = testTree.plainArray();
			final InsertionPosition insertionPosition = ArrayUtils.computeInsertPositionOfIntInOrderedArray(
				39, plainFullArray);

			assertFalse(insertionPosition.alreadyPresent());
			final int startPos = insertionPosition.position();
			final String[] partialCopy = new String[plainFullArray.length - startPos];
			for (int i = startPos; i < plainFullArray.length; i++) {
				partialCopy[i - startPos] = "Value" + plainFullArray[i];
			}

			final String[] reconstructedArray = new String[partialCopy.length];
			int index = 0;
			while (it.hasNext()) {
				reconstructedArray[index++] = it.next();
			}

			assertArrayEquals(partialCopy, reconstructedArray);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("returns empty iterator when start key exceeds maximum")
		void shouldFailToIterateValuesLeftToRightThroughNonExistingValues() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final Iterator<String> it = testTree.bPlusTree().greaterOrEqualValueIterator(1000);
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("returns empty iterator on empty tree")
		void shouldIterateForwardOnEmptyTree() {
			final TransactionalIntBPlusTree<String> tree = new TransactionalIntBPlusTree<>(3, String.class);
			final Iterator<String> it = tree.valueIterator();
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("iterates single element tree")
		void shouldIterateForwardOnSingleElementTree() {
			final TransactionalIntBPlusTree<String> tree = new TransactionalIntBPlusTree<>(3, String.class);
			tree.insert(42, "Value42");
			final Iterator<String> it = tree.valueIterator();
			assertTrue(it.hasNext());
			assertEquals("Value42", it.next());
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("iterates from tree's minimum key inclusive")
		void shouldIterateFromFirstKey() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final int[] keys = testTree.plainArray();
			final int firstKey = keys[0];
			final Iterator<String> it = testTree.bPlusTree().greaterOrEqualValueIterator(firstKey);
			int count = 0;
			while (it.hasNext()) {
				it.next();
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
			final Iterator<String> it = testTree.bPlusTree().greaterOrEqualValueIterator(lastKey);
			assertTrue(it.hasNext());
			assertEquals("Value" + lastKey, it.next());
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("iterates all elements when start key is below minimum")
		void shouldIterateFromBelowMinimumKey() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final int[] keys = testTree.plainArray();
			final Iterator<String> it = testTree.bPlusTree().greaterOrEqualValueIterator(Integer.MIN_VALUE);
			int count = 0;
			while (it.hasNext()) {
				it.next();
				count++;
			}
			assertEquals(keys.length, count);
		}

		@Test
		@DisplayName("iterates values when start key falls in the gap after the last key of a leaf")
		void shouldIterateFromKeyInGapBetweenLeaves() {
			// build a tree whose leaves are [10,20,30] | [50,60] so that key 40 lands past the
			// last key of the first leaf while a non-empty following leaf still holds keys >= 40
			final TransactionalIntBPlusTree<String> tree = new TransactionalIntBPlusTree<>(3, String.class);
			final int[] keys = {10, 20, 30, 50, 60};
			for (final int key : keys) {
				tree.insert(key, "Value" + key);
			}

			final Iterator<String> it = tree.greaterOrEqualValueIterator(40);
			final String[] reconstructed = new String[2];
			int index = 0;
			while (it.hasNext()) {
				reconstructed[index++] = it.next();
			}

			assertArrayEquals(new String[]{"Value50", "Value60"}, reconstructed);
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

				final Iterator<String> it = testTree.bPlusTree().greaterOrEqualValueIterator(startKey);
				int index = from;
				while (it.hasNext()) {
					assertTrue(
						index < keys.length, "Iterator returned more elements than reference for key " + startKey);
					assertEquals("Value" + keys[index], it.next(), "Mismatch at key " + startKey + ", index " + index);
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
			for (int i = startPos; i < plainFullArray.length; i++) {
				partialCopy[i - startPos] = plainFullArray[i];
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
			final TransactionalIntBPlusTree<String> tree = new TransactionalIntBPlusTree<>(3, String.class);
			final int[] keys = {10, 20, 30, 50, 60};
			for (final int key : keys) {
				tree.insert(key, "Value" + key);
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
			final TransactionalIntBPlusTree<String> tree = new TransactionalIntBPlusTree<>(3, String.class);
			final PrimitiveIterator.OfInt it = tree.keyIterator();
			assertFalse(it.hasNext());
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("iterates single key on single-element tree")
		void shouldIterateKeyOnSingleElementTree() {
			final TransactionalIntBPlusTree<String> tree = new TransactionalIntBPlusTree<>(3, String.class);
			tree.insert(42, "Value42");
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
			final Iterator<Entry<String>> it = testTree.bPlusTree().greaterOrEqualEntryIterator(40);
			final int[] plainFullArray = testTree.plainArray();
			final InsertionPosition insertionPosition =
				ArrayUtils.computeInsertPositionOfIntInOrderedArray(40, plainFullArray);

			assertTrue(insertionPosition.alreadyPresent());
			final int startPos = insertionPosition.position();
			//noinspection unchecked
			final Entry<String>[] partialCopy = new Entry[plainFullArray.length - startPos];
			for (int i = startPos; i < plainFullArray.length; i++) {
				partialCopy[i - startPos] = new Entry<>(plainFullArray[i], "Value" + plainFullArray[i]);
			}

			//noinspection unchecked
			final Entry<String>[] reconstructedArray = new Entry[partialCopy.length];
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
			final Iterator<Entry<String>> it = testTree.bPlusTree().greaterOrEqualEntryIterator(
				keys[keys.length - 1] + 1000);
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("traverses full tree key and value pairs in ascending order")
		void shouldIterateEntireTreeViaEntryIterator() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final int[] keys = testTree.plainArray();

			final Iterator<Entry<String>> it = testTree.bPlusTree().entryIterator();
			int index = 0;
			while (it.hasNext()) {
				final Entry<String> entry = it.next();
				assertEquals(keys[index], entry.key());
				assertEquals("Value" + keys[index], entry.value());
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
			final TransactionalIntBPlusTree<String> tree = new TransactionalIntBPlusTree<>(3, String.class);
			final int[] keys = {10, 20, 30, 50, 60};
			for (final int key : keys) {
				tree.insert(key, "Value" + key);
			}

			final Iterator<Entry<String>> it = tree.greaterOrEqualEntryIterator(40);
			//noinspection unchecked
			final Entry<String>[] reconstructed = new Entry[2];
			int index = 0;
			while (it.hasNext()) {
				reconstructed[index++] = it.next();
			}

			//noinspection unchecked
			final Entry<String>[] expected = new Entry[]{
				new Entry<>(50, "Value50"), new Entry<>(60, "Value60")
			};
			assertArrayEquals(expected, reconstructed);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("returns empty entry iterator on empty tree")
		void shouldReturnEmptyEntryIteratorOnEmptyTree() {
			final TransactionalIntBPlusTree<String> tree = new TransactionalIntBPlusTree<>(3, String.class);
			final Iterator<Entry<String>> it = tree.entryIterator();
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

			final String[] reconstructedArray = new String[testTree.totalElements()];
			int index = testTree.totalElements();
			final Iterator<String> it = testTree.bPlusTree().valueReverseIterator();
			while (it.hasNext()) {
				reconstructedArray[--index] = it.next();
			}

			assertArrayEquals(testTree.asStringArray(), reconstructedArray);
			assertThrows(NoSuchElementException.class, it::next);
			assertEquals(testTree.totalElements(), testTree.bPlusTree().size());
		}

		@Test
		@DisplayName("iterates values backwards from exact existing key")
		void shouldIterateThroughLeafNodeValuesRightToLeftFromExactPosition() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final Iterator<String> it = testTree.bPlusTree().lesserOrEqualValueIterator(40);
			final int[] plainFullArray = testTree.plainArray();
			final InsertionPosition insertionPosition =
				ArrayUtils.computeInsertPositionOfIntInOrderedArray(40, plainFullArray);

			assertTrue(insertionPosition.alreadyPresent());
			final int endPos = insertionPosition.position();
			final String[] partialCopy = new String[endPos + 1];
			for (int i = endPos; i >= 0; i--) {
				partialCopy[endPos - i] = "Value" + plainFullArray[i];
			}

			final String[] reconstructedArray = new String[partialCopy.length];
			int index = 0;
			while (it.hasNext()) {
				reconstructedArray[index++] = it.next();
			}

			assertArrayEquals(partialCopy, reconstructedArray);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("iterates values backwards from non-existing key position")
		void shouldIterateThroughLeafNodeValuesRightToLeftFromExactNonExistingPosition() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final Iterator<String> it = testTree.bPlusTree().lesserOrEqualValueIterator(39);
			final int[] plainFullArray = testTree.plainArray();
			final InsertionPosition insertionPosition =
				ArrayUtils.computeInsertPositionOfIntInOrderedArray(39, plainFullArray);

			assertFalse(insertionPosition.alreadyPresent());
			final int thePosition = insertionPosition.alreadyPresent()
				? insertionPosition.position() + 1
				: insertionPosition.position();
			final String[] partialCopy = new String[thePosition];
			for (int i = partialCopy.length - 1; i >= 0; i--) {
				partialCopy[thePosition - i - 1] = "Value" + plainFullArray[i];
			}

			final String[] reconstructedArray = new String[partialCopy.length];
			int index = 0;
			while (it.hasNext()) {
				reconstructedArray[index++] = it.next();
			}

			assertArrayEquals(partialCopy, reconstructedArray);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("returns empty reverse iterator when below minimum")
		void shouldFailToIterateValuesRightToLeftThroughNonExistingValues() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final int[] keys = testTree.plainArray();

			final Iterator<String> it = testTree.bPlusTree().lesserOrEqualValueIterator(keys[0] - 1000);
			assertFalse(it.hasNext());

			// start key above the maximum must yield the whole tree backwards, starting with the maximum
			final Iterator<String> it2 = testTree.bPlusTree().lesserOrEqualValueIterator(
				keys[keys.length - 1] + 1000);
			assertTrue(it2.hasNext());
			assertEquals("Value" + keys[keys.length - 1], it2.next());
		}

		@Test
		@DisplayName("returns empty reverse iterator on empty tree")
		void shouldIterateReverseOnEmptyTree() {
			final TransactionalIntBPlusTree<String> tree = new TransactionalIntBPlusTree<>(3, String.class);
			final Iterator<String> it = tree.valueReverseIterator();
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("reverse iterates single element tree")
		void shouldIterateReverseOnSingleElementTree() {
			final TransactionalIntBPlusTree<String> tree = new TransactionalIntBPlusTree<>(3, String.class);
			tree.insert(42, "Value42");
			final Iterator<String> it = tree.valueReverseIterator();
			assertTrue(it.hasNext());
			assertEquals("Value42", it.next());
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("iterates all elements backwards from maximum key")
		void shouldIterateFromLastKeyReverse() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final int[] keys = testTree.plainArray();
			final int lastKey = keys[keys.length - 1];
			final Iterator<String> it = testTree.bPlusTree().lesserOrEqualValueIterator(lastKey);
			int count = 0;
			while (it.hasNext()) {
				it.next();
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
			final Iterator<String> it = testTree.bPlusTree().lesserOrEqualValueIterator(firstKey);
			assertTrue(it.hasNext());
			assertEquals("Value" + firstKey, it.next());
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("iterates values backwards when start key falls in the gap before the first key of a leaf")
		void shouldIterateValuesFromKeyInGapBetweenLeavesReverse() {
			// key 40 falls below the first key of the leaf [50,60] while the preceding leaf [10,20,30]
			// still holds values <= 40; the lesserOrEqualValueIterator must cross the inter-leaf gap and
			// return, in descending order, every value <= 40
			final TransactionalIntBPlusTree<String> tree = new TransactionalIntBPlusTree<>(3, String.class);
			final int[] keys = {10, 20, 30, 50, 60};
			for (final int key : keys) {
				tree.insert(key, "Value" + key);
			}

			final Iterator<String> it = tree.lesserOrEqualValueIterator(40);
			final String[] reconstructed = new String[3];
			int index = 0;
			while (it.hasNext()) {
				reconstructed[index++] = it.next();
			}

			assertArrayEquals(new String[]{"Value30", "Value20", "Value10"}, reconstructed);
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

				final Iterator<String> it = testTree.bPlusTree().lesserOrEqualValueIterator(startKey);
				int index = from;
				while (it.hasNext()) {
					assertTrue(index >= 0, "Iterator returned more values than reference for key " + startKey);
					assertEquals(
						"Value" + keys[index], it.next(), "Mismatch at key " + startKey + ", index " + index);
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
			final TransactionalIntBPlusTree<String> tree = new TransactionalIntBPlusTree<>(3, String.class);
			final int[] keys = {10, 20, 30, 50, 60};
			for (final int key : keys) {
				tree.insert(key, "Value" + key);
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
			final TransactionalIntBPlusTree<String> tree = new TransactionalIntBPlusTree<>(3, String.class);
			final PrimitiveIterator.OfInt it = tree.keyReverseIterator();
			assertFalse(it.hasNext());
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("iterates single key on single-element tree")
		void shouldIterateKeyReverseOnSingleElementTree() {
			final TransactionalIntBPlusTree<String> tree = new TransactionalIntBPlusTree<>(3, String.class);
			tree.insert(42, "Value42");
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
			final Iterator<Entry<String>> it = testTree.bPlusTree().lesserOrEqualEntryIterator(40);
			final int[] plainFullArray = testTree.plainArray();
			final InsertionPosition insertionPosition =
				ArrayUtils.computeInsertPositionOfIntInOrderedArray(40, plainFullArray);

			assertTrue(insertionPosition.alreadyPresent());
			final int endPos = insertionPosition.position();
			//noinspection unchecked
			final Entry<String>[] partialCopy = new Entry[endPos + 1];
			for (int i = endPos; i >= 0; i--) {
				partialCopy[endPos - i] = new Entry<>(plainFullArray[i], "Value" + plainFullArray[i]);
			}

			//noinspection unchecked
			final Entry<String>[] reconstructedArray = new Entry[partialCopy.length];
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
			final Iterator<Entry<String>> it = testTree.bPlusTree().lesserOrEqualEntryIterator(
				keys[0] - 1000);
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("iterates entries backwards when start key falls in the gap before the first key of a leaf")
		void shouldIterateEntriesFromKeyInGapBetweenLeavesReverse() {
			// key 40 falls below the first key of the leaf [50,60] while the preceding leaf [10,20,30]
			// still holds entries <= 40; the iterator must cross the gap and return them in descending order
			final TransactionalIntBPlusTree<String> tree = new TransactionalIntBPlusTree<>(3, String.class);
			final int[] keys = {10, 20, 30, 50, 60};
			for (final int key : keys) {
				tree.insert(key, "Value" + key);
			}

			final Iterator<Entry<String>> it = tree.lesserOrEqualEntryIterator(40);
			//noinspection unchecked
			final Entry<String>[] reconstructed = new Entry[3];
			int index = 0;
			while (it.hasNext()) {
				reconstructed[index++] = it.next();
			}

			//noinspection unchecked
			final Entry<String>[] expected = new Entry[]{
				new Entry<>(30, "Value30"), new Entry<>(20, "Value20"), new Entry<>(10, "Value10")
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
					tested.insert(101, "Value101");
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
		@DisplayName("propagates changes through transactional layer producers")
		void shouldHandleTransactionalLayerProducers() {
			//noinspection unchecked
			final TransactionalIntBPlusTree<TransactionalList<String>> theTree =
				new TransactionalIntBPlusTree<>(
					TransactionalList.genericClass(),
					list -> new TransactionalList<>((List<String>) list)
				);
			theTree.insert(1, new TransactionalList<>(List.of("Value1", "Value2")));

			assertStateAfterCommit(
				theTree,
				tested -> tested.search(1).orElseThrow().add("Value3"),
				(original, committed) -> {
					assertEquals(2, original.search(1).orElseThrow().size());
					assertEquals(3, committed.search(1).orElseThrow().size());
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
					tested.insert(9999, "Value9999");
					tested.delete(originalArray[0]);
					tested.upsert(originalArray[1], existing -> "Modified");
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
					tested.insert(9999, "Value9999");
				},
				(original, committed) -> {
					verifyTreeConsistency(original, originalArray);
					assertEquals(originalArray.length, committed.size());
					assertTrue(committed.search(keyToDelete).isEmpty());
					assertEquals("Value9999", committed.search(9999).orElse(null));
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
					tested.insert(9999, "Value9999");
					assertEquals("Value9999", tested.search(9999).orElse(null));
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
			final TransactionalIntBPlusTree<String> tree = new TransactionalIntBPlusTree<>(3, 1, 3, 1, String.class);
			final TreeMap<Integer, String> reference = new TreeMap<>();
			for (int i = 0; i < 30; i++) {
				tree.insert(i, "Value" + i);
				reference.put(i, "Value" + i);
			}

			assertStateAfterCommit(
				tree,
				tested -> {
					// insertions to provoke splits inside the transaction
					for (int i = 100; i < 116; i++) {
						tested.insert(i, "Value" + i);
						reference.put(i, "Value" + i);
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

					final String[] expectedValues = reference.values().toArray(new String[0]);
					final String[] actualValues = new String[expectedValues.length];
					int index = 0;
					final Iterator<String> it = committed.valueIterator();
					while (it.hasNext()) {
						actualValues[index++] = it.next();
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
				final TransactionalIntBPlusTree<String> tree = new TransactionalIntBPlusTree<>(
					3, 1, 3, 1, String.class);
				final TreeMap<Integer, String> reference = new TreeMap<>();
				for (int i = 0; i < 8; i++) {
					final int key = random.nextInt(40);
					tree.insert(key, "Value" + key);
					reference.put(key, "Value" + key);
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
									tested.insert(key, "Value" + key);
									reference.put(key, "Value" + key);
								}
								case 1 -> {
									tested.delete(key);
									reference.remove(key);
								}
								default -> {
									tested.upsert(key, existing -> "Value" + key);
									reference.put(key, "Value" + key);
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
						final String[] expectedValues = reference.values().toArray(new String[0]);

						final String[] actualValues = new String[expectedValues.length];
						int index = 0;
						final Iterator<String> it = committed.valueIterator();
						while (it.hasNext()) {
							assertTrue(index < expectedValues.length, "Too many values for seed " + seed);
							actualValues[index++] = it.next();
						}
						assertEquals(expectedValues.length, index, "Value count mismatch for seed " + seed);
						assertArrayEquals(expectedValues, actualValues, "Value mismatch for seed " + seed);
						assertEquals(expectedKeys.length, committed.size(), "Size mismatch for seed " + seed);
						for (final int key : expectedKeys) {
							assertEquals(
								reference.get(key), committed.search(key).orElse(null),
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
			final TransactionalIntBPlusTree<String> tree = new TransactionalIntBPlusTree<>(3, String.class);
			// empty leaf node has no key:value pairs, so the representation is empty
			assertEquals("", tree.toString());
		}

		@Test
		@DisplayName("prints simple two-element tree")
		void shouldPrintVerboseSimpleTree() {
			final TransactionalIntBPlusTree<String> bPlusTree = new TransactionalIntBPlusTree<>(3, String.class);
			assertStateAfterCommit(
				bPlusTree,
				tested -> {
					bPlusTree.insert(5, "Value5");
					bPlusTree.insert(15, "Value50");
				},
				(original, committed) -> {
					assertEquals(2, committed.size());
					assertEquals("Value5", committed.search(5).orElse(null));
					assertEquals("Value50", committed.search(15).orElse(null));
					assertEquals("5:Value5, 15:Value50", committed.toString());

					assertEquals(0, original.size());
					assertNull(original.search(5).orElse(null));
					assertNull(original.search(15).orElse(null));
				}
			);
		}

		@Test
		@DisplayName("prints multi-level tree structure")
		void shouldPrintComplexTree() {
			final TransactionalIntBPlusTree<String> bPlusTree = new TransactionalIntBPlusTree<>(3, String.class);

			assertStateAfterCommit(
				bPlusTree,
				tested -> {
					tested.insert(1, "Value1");
					tested.insert(2, "Value2");
					// this should cause a split
					tested.insert(3, "Value3");
					tested.insert(4, "Value4");
				},
				(original, committed) -> {
					assertEquals(4, committed.size());
					assertEquals("Value1", committed.search(1).orElse(null));
					assertEquals("Value2", committed.search(2).orElse(null));
					assertEquals("Value3", committed.search(3).orElse(null));
					assertEquals("Value4", committed.search(4).orElse(null));

					assertEquals(
						"""
							< 2:
							   1:Value1
							>=2:
							   2:Value2
							>=3:
							   3:Value3, 4:Value4""",
						committed.toString()
					);

					assertEquals(0, original.size());
					assertNull(original.search(1).orElse(null));
					assertNull(original.search(2).orElse(null));
					assertNull(original.search(3).orElse(null));
					assertNull(original.search(4).orElse(null));
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
				GenericEvitaInternalError.class, () -> new TransactionalIntBPlusTree<>(2, String.class)
			);
		}

		@Test
		@DisplayName("rejects even internal node block size")
		void shouldRejectEvenInternalNodeBlockSize() {
			assertThrows(
				GenericEvitaInternalError.class, () -> new TransactionalIntBPlusTree<>(3, 1, 4, 1, String.class)
			);
		}

		@Test
		@DisplayName("rejects internal node block size larger than value block size")
		void shouldRejectInternalNodeBlockSizeLargerThanValueBlockSize() {
			assertThrows(
				GenericEvitaInternalError.class, () -> new TransactionalIntBPlusTree<>(3, 1, 5, 2, String.class)
			);
		}

		@Test
		@DisplayName("accepts internal node block size equal to value block size")
		void shouldAcceptInternalNodeBlockSizeEqualToValueBlockSize() {
			assertDoesNotThrow(() -> new TransactionalIntBPlusTree<>(3, 1, 3, 1, String.class));
			assertDoesNotThrow(() -> new TransactionalIntBPlusTree<>(3, String.class));
		}

		@Test
		@DisplayName("returns zero size for newly created tree")
		void shouldReturnZeroSizeForEmptyTree() {
			final TransactionalIntBPlusTree<String> tree = new TransactionalIntBPlusTree<>(3, String.class);
			assertEquals(0, tree.size());
		}

		@Test
		@DisplayName("reports consistent state for empty tree")
		void shouldReportConsistentForEmptyTree() {
			final TransactionalIntBPlusTree<String> tree = new TransactionalIntBPlusTree<>(3, String.class);
			final ConsistencyReport report = tree.getConsistencyReport();
			assertEquals(ConsistencyState.CONSISTENT, report.state());
		}

		@Test
		@DisplayName("genericClass returns the raw tree class")
		void shouldReturnCorrectGenericClass() {
			final Class<TransactionalIntBPlusTree<String>> theClass = TransactionalIntBPlusTree.genericClass();
			assertSame(TransactionalIntBPlusTree.class, theClass);
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
			@Nonnull TransactionalIntBPlusTree<String> tree,
			@Nonnull TransactionalIntBPlusTree.BPlusTreeNode<?> root,
			int size
		) {
			try {
				final Field rootField = TransactionalIntBPlusTree.class.getDeclaredField("root");
				rootField.setAccessible(true);
				((TransactionalReference<TransactionalIntBPlusTree.BPlusTreeNode<?>>)
					rootField.get(tree)).set(root);
				final Field sizeField = TransactionalIntBPlusTree.class.getDeclaredField("size");
				sizeField.setAccessible(true);
				((TransactionalReference<Integer>) sizeField.get(tree)).set(size);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError("Unable to install hand-built root", e);
			}
		}

		/**
		 * Builds a single-element leaf node carrying the given key with a matching string value.
		 *
		 * @param key the key (and value suffix) to store
		 * @return a leaf node holding exactly the one key
		 */
		@Nonnull
		private static TransactionalIntBPlusTree.BPlusLeafTreeNode<String> leaf(int key) {
			final int[] keys = {key};
			final String[] values = {"Value" + key};
			return new TransactionalIntBPlusTree.BPlusLeafTreeNode<>(
				keys, values, new int[3], new String[3], 0, 1, true, null
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
		private static TransactionalIntBPlusTree.BPlusInternalTreeNode internal(
			@Nonnull int[] keys, @Nonnull TransactionalIntBPlusTree.BPlusTreeNode<?>... children
		) {
			final int[] keyArray = new int[5];
			System.arraycopy(keys, 0, keyArray, 0, keys.length);
			final TransactionalIntBPlusTree.BPlusTreeNode<?>[] childArray =
				new TransactionalIntBPlusTree.BPlusTreeNode<?>[6];
			System.arraycopy(children, 0, childArray, 0, children.length);
			return new TransactionalIntBPlusTree.BPlusInternalTreeNode(
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
			final TransactionalIntBPlusTree<String> tree = new TransactionalIntBPlusTree<>(5, 1, 5, 2, String.class);

			// under-occupied internal node: 2 children -> 1 key (keyCount 1 < minInternalNodeBlockSize 2),
			// yet size 2 satisfies the lenient child-count check
			final TransactionalIntBPlusTree.BPlusInternalTreeNode underOccupied =
				internal(new int[]{2}, leaf(1), leaf(2));
			// properly occupied internal node: 3 children -> 2 keys (keyCount 2 == minimum)
			final TransactionalIntBPlusTree.BPlusInternalTreeNode wellOccupied =
				internal(new int[]{4, 5}, leaf(3), leaf(4), leaf(5));

			final TransactionalIntBPlusTree.BPlusInternalTreeNode root =
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
			final TransactionalIntBPlusTree<String> tree = new TransactionalIntBPlusTree<>(5, 1, 5, 2, String.class);

			// both children carry the minimum of 2 keys (3 leaves each)
			final TransactionalIntBPlusTree.BPlusInternalTreeNode left =
				internal(new int[]{2, 3}, leaf(1), leaf(2), leaf(3));
			final TransactionalIntBPlusTree.BPlusInternalTreeNode right =
				internal(new int[]{5, 6}, leaf(4), leaf(5), leaf(6));

			final TransactionalIntBPlusTree.BPlusInternalTreeNode root =
				internal(new int[]{4}, left, right);

			installRoot(tree, root, 6);

			final ConsistencyReport report = tree.getConsistencyReport();
			assertEquals(ConsistencyState.CONSISTENT, report.state(), report.report());
		}

	}

}
