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

import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyReport;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.index.bPlusTree.TransactionalLongBPlusTree.Entry;
import io.evitadb.index.bitmap.TransactionalBitmap;
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
 * This test verifies the correctness of the {@link TransactionalLongBPlusTree} implementation. It covers insert,
 * search, upsert, delete, rebalancing (steal and merge), forward and reverse iteration of both keys and values,
 * transactional semantics, tree visualization, constructor validation, and the internal consistency oracle.
 * Bounded, fixed-seed randomized churn tests guard the rebalancing and commit machinery against regressions; the
 * open-ended generational soak test lives in the long-running test module.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@SuppressWarnings("StringConcatenationMissingWhitespace")
@DisplayName("Transactional long B+ tree")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class TransactionalLongBPlusTreeTest {

	/**
	 * Verifies tree consistency by checking the internal consistency report and validating both forward and reverse
	 * value iterators against the expected key array.
	 *
	 * @param bPlusTree     the tree to verify
	 * @param expectedArray the expected sorted key array
	 */
	private static void verifyTreeConsistency(
		@Nonnull TransactionalLongBPlusTree<String> bPlusTree, @Nonnull long... expectedArray
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
		@Nonnull TransactionalLongBPlusTree<String> tree, @Nonnull long... keyArray
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
		@Nonnull TransactionalLongBPlusTree<String> tree, @Nonnull long... keyArray
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
		final TransactionalLongBPlusTree<String> bPlusTree = new TransactionalLongBPlusTree<>(
			valueBlockSize, minValueBlockSize, internalNodeSize, minInternalNodeSize, String.class
		);
		long[] plainArray = new long[0];
		do {
			final int i = random.nextInt(totalElements << 1);
			bPlusTree.insert(i, "Value" + i);
			plainArray = ArrayUtils.insertLongIntoOrderedArray(i, plainArray);
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
	private static TransactionalLongBPlusTree<String> deleteAndVerify(
		@Nonnull TransactionalLongBPlusTree<String> tree,
		@Nonnull AtomicReference<long[]> expectedArray, long keyToDelete
	) {
		final AtomicReference<TransactionalLongBPlusTree<String>> result = new AtomicReference<>();
		assertStateAfterCommit(
			tree,
			tested -> tested.delete(keyToDelete),
			(original, committed) -> {
				verifyTreeConsistency(original, expectedArray.get());
				expectedArray.set(ArrayUtils.removeLongFromOrderedArray(keyToDelete, expectedArray.get()));
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
		@Nonnull TransactionalLongBPlusTree<String> bPlusTree,
		@Nonnull long[] plainArray
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
			final TransactionalLongBPlusTree<String> bPlusTree = new TransactionalLongBPlusTree<>(3, String.class);
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
			final TransactionalLongBPlusTree<String> bPlusTree = new TransactionalLongBPlusTree<>(3, String.class);

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
			final TransactionalLongBPlusTree<String> bPlusTree = new TransactionalLongBPlusTree<>(3, String.class);
			final AtomicReference<long[]> keys = new AtomicReference<>(new long[0]);
			assertStateAfterCommit(
				bPlusTree,
				tested -> {
					for (int i = 1; i <= 20; i++) {
						tested.insert(i, "Value" + i);
						keys.set(ArrayUtils.insertLongIntoOrderedArray(i, keys.get()));
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
			final TransactionalLongBPlusTree<String> bPlusTree = new TransactionalLongBPlusTree<>(3, String.class);
			final AtomicReference<long[]> keys = new AtomicReference<>(new long[0]);
			assertStateAfterCommit(
				bPlusTree,
				tested -> {
					for (int i = 20; i > 0; i--) {
						tested.insert(i, "Value" + i);
						keys.set(ArrayUtils.insertLongIntoOrderedArray(i, keys.get()));
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
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(3, String.class);
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
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(3, String.class);
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
			final TransactionalLongBPlusTree<String> theTree = testTree.bPlusTree();
			final long[] expectedArray = testTree.plainArray();

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
		@DisplayName("refuses an updater that returns null instead of storing it")
		void shouldRefuseAnUpdaterReturningNull() {
			// A stored null would not surface as a failure anywhere: `search` and the leaf's `getValue` both answer
			// `Optional.ofNullable`, so the key would read back as ABSENT while it demonstrably sits in a leaf. The
			// updater's result is the only door a null can come through - `insert` takes a `@Nonnull V` - so both of
			// upsert's branches refuse it, and the tree is left exactly as it was.
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(3, String.class);
			tree.insert(10, "Value10");
			tree.insert(20, "Value20");

			// the update branch: the key exists, so the updater is handed the value it would replace
			assertThrows(GenericEvitaInternalError.class, () -> tree.upsert(20, existing -> null));
			assertEquals("Value20", tree.search(20).orElse(null));

			// the insert branch: the key is absent, so the updater is handed null and must not hand one back
			assertThrows(GenericEvitaInternalError.class, () -> tree.upsert(30, existing -> null));
			assertTrue(tree.search(30).isEmpty());
			assertEquals(2, tree.size());

			final ConsistencyReport report = tree.getConsistencyReport();
			assertEquals(ConsistencyState.CONSISTENT, report.state(), report.report());
		}

		@Test
		@DisplayName("inserts new entry for non-existent key")
		void shouldInsertNonExistingValueViaUpsert() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final TransactionalLongBPlusTree<String> theTree = testTree.bPlusTree();
			final long[] expectedArray = testTree.plainArray();

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
						committed, ArrayUtils.insertLongIntoOrderedArray(100, expectedArray)
					);
				}
			);
		}

		@Test
		@DisplayName("releases diff layer of a producer value replaced by a different instance via upsert")
		void shouldNotLeakLayerWhenProducerValueIsReplacedByDifferentInstanceViaUpsert() {
			final TransactionalLongBPlusTree<TransactionalBitmap> tree =
				new TransactionalLongBPlusTree<>(TransactionalBitmap.class, TransactionalBitmap.class::cast);
			tree.insert(1, new TransactionalBitmap(10));

			assertStateAfterCommit(
				tree,
				original -> {
					// mutate the existing value (opens an ALIVE layer) and then replace it with a brand-new
					// instance via upsert - the discarded old instance's layer must be released
					original.upsert(1, existing -> {
						existing.add(11);
						return new TransactionalBitmap(30);
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
			final TransactionalLongBPlusTree<String> theTree = testTree.bPlusTree();
			final AtomicReference<long[]> expectedArray = new AtomicReference<>(testTree.plainArray());

			assertStateAfterCommit(
				theTree,
				tested -> {
					while (expectedArray.get().length > 0) {
						final int index = rnd.nextInt(expectedArray.get().length);
						final long key = expectedArray.get()[index];
						tested.delete(key);
						expectedArray.set(ArrayUtils.removeLongFromOrderedArray(key, expectedArray.get()));
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
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(3, String.class);
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
				final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(
					3, 1, 3, 1, String.class);
				final TreeMap<Long, String> reference = new TreeMap<>();

				for (int op = 0; op < 400; op++) {
					final long key = random.nextInt(60);
					// bias towards delete once the tree has grown so merges and borrows are exercised heavily
					final boolean delete = reference.size() > 20
						? random.nextInt(3) > 0
						: random.nextBoolean();
					if (delete) {
						tree.delete(key);
						reference.remove(key);
					} else if (random.nextBoolean()) {
						tree.insert(key, "Value" + key);
						reference.put(key, "Value" + key);
					} else {
						// upsert exercises the insert-or-update public path; a value-preserving updater keeps the
						// oracle exact whether the key was absent (insert + possible split) or already present
						tree.upsert(key, existing -> "Value" + key);
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
				final long[] expectedKeys = reference.keySet().stream().mapToLong(Long::longValue).toArray();
				verifyForwardValueIterator(tree, expectedKeys);
				verifyReverseValueIterator(tree, expectedKeys);
			}
		}

		@Test
		@DisplayName("survives large randomized insert/delete churn committed as one transaction")
		void shouldSurviveRandomizedTransactionalChurn() {
			// The dirty-scope validation runs only at commit, so a fuzzer that never commits (like the bare-tree
			// consistency-report churn above) is blind to it. This churns hundreds of random insert / upsert / delete
			// operations at small block sizes inside a single committed transaction, so leaves split and merge within
			// the transaction; the commit-time dirty-scope passes must re-derive every dirtied leaf's boundaries
			// cleanly and the committed content must match the oracle.
			for (final int blockSize : new int[]{3, 5, 7}) {
				for (long seed = 0; seed < 25; seed++) {
					exerciseTransactionalChurn(blockSize, seed);
				}
			}
		}

		/**
		 * Churns 400 random insert / upsert / delete operations against a fresh tree of the given block size inside a
		 * single transaction and asserts the committed tree matches the maintained oracle. Unlike the bare-tree churn
		 * that validates a consistency report after every operation, this drives the whole batch through one commit so
		 * the commit-merge and dirty-scope validation paths must reconcile every leaf split and merge accumulated
		 * within the transaction.
		 *
		 * @param blockSize the leaf and internal node block size for the tree
		 * @param seed      the random seed for reproducibility
		 */
		private void exerciseTransactionalChurn(int blockSize, long seed) {
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(
				blockSize, 1, blockSize, 1, String.class
			);
			final TreeMap<Long, String> oracle = new TreeMap<>();
			final Random random = new Random(seed);

			assertStateAfterCommit(
				tree,
				tested -> {
					for (int op = 0; op < 400; op++) {
						final long key = random.nextInt(120);
						// bias towards delete once the tree has grown so merges and borrows are exercised heavily
						final boolean delete = oracle.size() > 40
							? random.nextInt(3) > 0
							: random.nextBoolean();
						if (delete) {
							tested.delete(key);
							oracle.remove(key);
						} else if (random.nextBoolean()) {
							tested.insert(key, "Value" + key);
							oracle.put(key, "Value" + key);
						} else {
							// upsert exercises the insert-or-update public path (and, for this validating tree, its
							// own dirty-scope registration on the upsert-insert seam); value-preserving keeps the
							// oracle exact
							tested.upsert(key, existing -> "Value" + key);
							oracle.put(key, "Value" + key);
						}
					}
					// the whole batch is verified after commit; only the running size is checked mid-transaction
					assertEquals(
						oracle.size(), tested.size(),
						"Size mismatch inside transaction at blockSize=" + blockSize + " seed=" + seed
					);
				},
				(original, committed) -> {
					// every mutation happened inside the transaction, so the pre-transaction tree is still empty
					assertEquals(
						0, original.size(),
						"Original tree changed at blockSize=" + blockSize + " seed=" + seed
					);
					// the committed tree must match the oracle's ascending keys through the consistency report and
					// both forward and reverse value iterators
					final long[] expectedKeys = oracle.keySet().stream().mapToLong(Long::longValue).toArray();
					verifyTreeConsistency(committed, expectedKeys);
				}
			);
		}

		@Test
		@DisplayName("releases diff layer of a modified producer value that is deleted in the same transaction")
		void shouldNotLeakLayerWhenModifiedProducerValueIsDeleted() {
			final TransactionalLongBPlusTree<TransactionalBitmap> tree =
				new TransactionalLongBPlusTree<>(TransactionalBitmap.class, TransactionalBitmap.class::cast);
			tree.insert(1, new TransactionalBitmap(10));

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
			final TransactionalLongBPlusTree<TransactionalBitmap> tree =
				new TransactionalLongBPlusTree<>(TransactionalBitmap.class, TransactionalBitmap.class::cast);

			assertStateAfterCommit(
				tree,
				original -> {
					// create a brand-new producer value, mutate it, then delete it within the same txn
					final TransactionalBitmap fresh = new TransactionalBitmap(20);
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

	}

	@Nested
	@DisplayName("Rebalancing - steal operations")
	class StealOperationsTest {

		@Test
		@DisplayName("steals from left sibling when node underflows")
		void shouldStealFromLeftmostNode() {
			final TransactionalLongBPlusTree<String> bPlusTree = new TransactionalLongBPlusTree<>(3, String.class);
			final AtomicReference<TransactionalLongBPlusTree<String>> theCommittedTree = new AtomicReference<>();

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
			final TransactionalLongBPlusTree<String> bPlusTree = new TransactionalLongBPlusTree<>(3, String.class);
			final AtomicReference<TransactionalLongBPlusTree<String>> theCommittedTree = new AtomicReference<>();

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
			final TransactionalLongBPlusTree<String> bPlusTree = new TransactionalLongBPlusTree<>(3, String.class);
			final AtomicReference<TransactionalLongBPlusTree<String>> theCommittedTree = new AtomicReference<>();

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
		private static TransactionalLongBPlusTree.BPlusInternalTreeNode emptyInternalNode() {
			return new TransactionalLongBPlusTree.BPlusInternalTreeNode(
				new long[3], new BPlusTreeNode<?>[4], 0, 0, 0, 0, true
			);
		}

		/**
		 * Builds a single-element leaf node carrying the given key with a matching string value.
		 *
		 * @param key the key (and value suffix) to store
		 * @return a leaf node holding exactly the one key
		 */
		@Nonnull
		private static TransactionalLongBPlusTree.BPlusLeafTreeNode<String> leaf(long key) {
			final long[] keys = {key};
			final String[] values = {"Value" + key};
			return new TransactionalLongBPlusTree.BPlusLeafTreeNode<>(
				keys, values, new long[3], new String[3], 0, 1, true, null
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
		private static TransactionalLongBPlusTree.BPlusInternalTreeNode internal(
			@Nonnull long[] keys, @Nonnull BPlusTreeNode<?>... children
		) {
			final long[] keyArray = new long[3];
			System.arraycopy(keys, 0, keyArray, 0, keys.length);
			final BPlusTreeNode<?>[] childArray =
				new BPlusTreeNode<?>[4];
			System.arraycopy(children, 0, childArray, 0, children.length);
			return new TransactionalLongBPlusTree.BPlusInternalTreeNode(
				keyArray, childArray, 0, keys.length, 0, children.length, true
			);
		}

		private static void exerciseChurn(
			int valueBlockSize, int minValueBlockSize,
			int internalNodeBlockSize, int minInternalNodeBlockSize, long seed
		) {
			final Random random = new Random(seed);
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(
				valueBlockSize, minValueBlockSize, internalNodeBlockSize, minInternalNodeBlockSize, String.class
			);
			final TreeMap<Long, String> reference = new TreeMap<>();
			final int range = 200;
			for (int op = 0; op < 1500; op++) {
				final long key = random.nextInt(range);
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
			final AtomicReference<long[]> expectedArray = new AtomicReference<>(testTree.plainArray());
			TransactionalLongBPlusTree<String> tree = testTree.bPlusTree();

			tree = deleteAndVerify(tree, expectedArray, 98);
			deleteAndVerify(tree, expectedArray, 94);
		}

		@Test
		@DisplayName("merges with right sibling node")
		void shouldMergeWithRightNode() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final AtomicReference<long[]> expectedArray = new AtomicReference<>(testTree.plainArray());

			deleteAndVerify(testTree.bPlusTree(), expectedArray, 93);
		}

		@Test
		@DisplayName("cascades merge causing parent to steal from left")
		void shouldMergeCausingIntermediateParentToStealFromLeft() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final AtomicReference<long[]> expectedArray = new AtomicReference<>(testTree.plainArray());

			deleteAndVerify(testTree.bPlusTree(), expectedArray, 34);
		}

		@Test
		@DisplayName("cascades merge causing parent to steal from right")
		void shouldMergeCausingIntermediateParentToStealFromRight() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final AtomicReference<long[]> expectedArray = new AtomicReference<>(testTree.plainArray());
			TransactionalLongBPlusTree<String> tree = testTree.bPlusTree();

			tree = deleteAndVerify(tree, expectedArray, 92);
			deleteAndVerify(tree, expectedArray, 87);
		}

		@Test
		@DisplayName("cascades multiple merges with left parent")
		void shouldMergeCausingIntermediateParentToMergeLeft() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final AtomicReference<long[]> expectedArray = new AtomicReference<>(testTree.plainArray());
			TransactionalLongBPlusTree<String> tree = testTree.bPlusTree();

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
			final AtomicReference<long[]> expectedArray = new AtomicReference<>(testTree.plainArray());
			TransactionalLongBPlusTree<String> tree = testTree.bPlusTree();

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
			final TransactionalLongBPlusTree.BPlusInternalTreeNode empty = emptyInternalNode();
			final TransactionalLongBPlusTree.BPlusInternalTreeNode sibling =
				internal(new long[]{2}, leaf(1), leaf(2));

			assertThrows(GenericEvitaInternalError.class, () -> empty.mergeWithLeft(sibling));
		}

		@Test
		@DisplayName("rejects merging the right sibling into an empty internal node")
		void shouldRejectMergeWithRightIntoEmptyInternalNode() {
			final TransactionalLongBPlusTree.BPlusInternalTreeNode empty = emptyInternalNode();
			final TransactionalLongBPlusTree.BPlusInternalTreeNode sibling =
				internal(new long[]{2}, leaf(1), leaf(2));

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
			final long[] plainFullArray = testTree.plainArray();
			final InsertionPosition insertionPosition = ArrayUtils.computeInsertPositionOfLongInOrderedArray(
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
			final long[] plainFullArray = testTree.plainArray();
			final InsertionPosition insertionPosition = ArrayUtils.computeInsertPositionOfLongInOrderedArray(
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
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(3, String.class);
			final Iterator<String> it = tree.valueIterator();
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("iterates single element tree")
		void shouldIterateForwardOnSingleElementTree() {
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(3, String.class);
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
			final long[] keys = testTree.plainArray();
			final long firstKey = keys[0];
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
			final long[] keys = testTree.plainArray();
			final long lastKey = keys[keys.length - 1];
			final Iterator<String> it = testTree.bPlusTree().greaterOrEqualValueIterator(lastKey);
			assertTrue(it.hasNext());
			assertEquals("Value" + lastKey, it.next());
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("iterates all elements when start key is below minimum")
		void shouldIterateFromBelowMinimumKey() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final long[] keys = testTree.plainArray();
			final Iterator<String> it = testTree.bPlusTree().greaterOrEqualValueIterator(Long.MIN_VALUE);
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
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(3, String.class);
			final long[] keys = {10, 20, 30, 50, 60};
			for (final long key : keys) {
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
			final long[] keys = testTree.plainArray();

			// probe every possible start key from below the minimum to above the maximum
			for (long startKey = keys[0] - 2; startKey <= keys[keys.length - 1] + 2; startKey++) {
				final InsertionPosition position = ArrayUtils.computeInsertPositionOfLongInOrderedArray(startKey, keys);
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
			final PrimitiveIterator.OfLong it = testTree.bPlusTree().greaterOrEqualKeyIterator(40);
			final long[] plainFullArray = testTree.plainArray();
			final InsertionPosition insertionPosition =
				ArrayUtils.computeInsertPositionOfLongInOrderedArray(40, plainFullArray);

			assertTrue(insertionPosition.alreadyPresent());
			final int startPos = insertionPosition.position();
			final long[] partialCopy = new long[plainFullArray.length - startPos];
			if (plainFullArray.length - startPos >= 0) {
				System.arraycopy(plainFullArray, startPos, partialCopy, 0, plainFullArray.length - startPos);
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
		@DisplayName("returns empty key iterator when exceeding bounds")
		void shouldFailToIterateKeysLeftToRightThroughNonExistingValues() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final long[] keys = testTree.plainArray();

			final PrimitiveIterator.OfLong it = testTree.bPlusTree().greaterOrEqualKeyIterator(
				keys[keys.length - 1] + 1000);
			assertFalse(it.hasNext());

			// start key below the minimum must yield the whole tree, starting with the minimum key
			final PrimitiveIterator.OfLong it2 = testTree.bPlusTree().greaterOrEqualKeyIterator(
				keys[0] - 1000);
			assertTrue(it2.hasNext());
			assertEquals(keys[0], it2.nextLong());
		}

		@Test
		@DisplayName("iterates all keys in ascending order")
		void shouldIterateAllKeysLeftToRight() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final long[] keys = testTree.plainArray();

			final PrimitiveIterator.OfLong it = testTree.bPlusTree().keyIterator();
			int index = 0;
			while (it.hasNext()) {
				assertTrue(index < keys.length, "Iterator returned more keys than expected");
				assertEquals(keys[index], it.nextLong());
				index++;
			}

			assertEquals(keys.length, index);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("iterates full tree from minimum key inclusive")
		void shouldIterateFullTreeFromMinKey() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final long[] keys = testTree.plainArray();
			final long minKey = keys[0];

			final PrimitiveIterator.OfLong it = testTree.bPlusTree().greaterOrEqualKeyIterator(minKey);
			int count = 0;
			while (it.hasNext()) {
				it.nextLong();
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
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(3, String.class);
			final long[] keys = {10, 20, 30, 50, 60};
			for (final long key : keys) {
				tree.insert(key, "Value" + key);
			}

			final PrimitiveIterator.OfLong it = tree.greaterOrEqualKeyIterator(40);
			final long[] reconstructed = new long[2];
			int index = 0;
			while (it.hasNext()) {
				reconstructed[index++] = it.nextLong();
			}

			assertArrayEquals(new long[]{50, 60}, reconstructed);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("matches sorted reference for arbitrary gap start keys across many leaves")
		void shouldMatchReferenceForGapStartKeysForward() {
			final TreeTuple testTree = prepareRandomTree(913, 200);
			final long[] keys = testTree.plainArray();

			// probe every possible start key from below the minimum to above the maximum
			for (long startKey = keys[0] - 2; startKey <= keys[keys.length - 1] + 2; startKey++) {
				final InsertionPosition position =
					ArrayUtils.computeInsertPositionOfLongInOrderedArray(startKey, keys);
				final int from = position.position();

				final PrimitiveIterator.OfLong it = testTree.bPlusTree().greaterOrEqualKeyIterator(startKey);
				int index = from;
				while (it.hasNext()) {
					assertTrue(
						index < keys.length, "Iterator returned more keys than reference for key " + startKey);
					assertEquals(keys[index], it.nextLong(), "Mismatch at key " + startKey + ", index " + index);
					index++;
				}
				assertEquals(keys.length, index, "Iterator stopped early for start key " + startKey);
			}
		}

		@Test
		@DisplayName("returns empty key iterator on empty tree")
		void shouldReturnEmptyKeyIteratorOnEmptyTree() {
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(3, String.class);
			final PrimitiveIterator.OfLong it = tree.keyIterator();
			assertFalse(it.hasNext());
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("iterates single key on single-element tree")
		void shouldIterateKeyOnSingleElementTree() {
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(3, String.class);
			tree.insert(42, "Value42");
			final PrimitiveIterator.OfLong it = tree.keyIterator();
			assertTrue(it.hasNext());
			assertEquals(42, it.nextLong());
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
			final long[] plainFullArray = testTree.plainArray();
			final InsertionPosition insertionPosition =
				ArrayUtils.computeInsertPositionOfLongInOrderedArray(40, plainFullArray);

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
			final long[] keys = testTree.plainArray();
			final Iterator<Entry<String>> it = testTree.bPlusTree().greaterOrEqualEntryIterator(
				keys[keys.length - 1] + 1000);
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("traverses full tree key and value pairs in ascending order")
		void shouldIterateEntireTreeViaEntryIterator() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final long[] keys = testTree.plainArray();

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
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(3, String.class);
			final long[] keys = {10, 20, 30, 50, 60};
			for (final long key : keys) {
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
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(3, String.class);
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
			final long[] plainFullArray = testTree.plainArray();
			final InsertionPosition insertionPosition =
				ArrayUtils.computeInsertPositionOfLongInOrderedArray(40, plainFullArray);

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
			final long[] plainFullArray = testTree.plainArray();
			final InsertionPosition insertionPosition =
				ArrayUtils.computeInsertPositionOfLongInOrderedArray(39, plainFullArray);

			assertFalse(insertionPosition.alreadyPresent());
			final int thePosition = insertionPosition.position();
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
			final long[] keys = testTree.plainArray();

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
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(3, String.class);
			final Iterator<String> it = tree.valueReverseIterator();
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("reverse iterates single element tree")
		void shouldIterateReverseOnSingleElementTree() {
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(3, String.class);
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
			final long[] keys = testTree.plainArray();
			final long lastKey = keys[keys.length - 1];
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
			final long[] keys = testTree.plainArray();
			final long firstKey = keys[0];
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
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(3, String.class);
			final long[] keys = {10, 20, 30, 50, 60};
			for (final long key : keys) {
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
			final long[] keys = testTree.plainArray();

			// probe every possible start key from below the minimum to above the maximum
			for (long startKey = keys[0] - 2; startKey <= keys[keys.length - 1] + 2; startKey++) {
				final InsertionPosition position =
					ArrayUtils.computeInsertPositionOfLongInOrderedArray(startKey, keys);
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
			final PrimitiveIterator.OfLong it = testTree.bPlusTree().lesserOrEqualKeyIterator(40);
			final long[] plainFullArray = testTree.plainArray();
			final InsertionPosition insertionPosition =
				ArrayUtils.computeInsertPositionOfLongInOrderedArray(40, plainFullArray);

			assertTrue(insertionPosition.alreadyPresent());
			final int endPos = insertionPosition.position();
			final long[] partialCopy = new long[endPos + 1];
			for (int i = endPos; i >= 0; i--) {
				partialCopy[endPos - i] = plainFullArray[i];
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
		@DisplayName("returns empty reverse key iterator when below minimum")
		void shouldFailToIterateKeysRightToLeftThroughNonExistingValues() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final long[] keys = testTree.plainArray();
			final PrimitiveIterator.OfLong it = testTree.bPlusTree().lesserOrEqualKeyIterator(
				keys[0] - 1000);
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("iterates all keys in descending order")
		void shouldIterateAllKeysRightToLeft() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final long[] keys = testTree.plainArray();

			final PrimitiveIterator.OfLong it = testTree.bPlusTree().keyReverseIterator();
			int index = keys.length;
			while (it.hasNext()) {
				assertTrue(index > 0, "Iterator returned more keys than expected");
				assertEquals(keys[--index], it.nextLong());
			}

			assertEquals(0, index);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("iterates full tree in reverse from maximum key inclusive")
		void shouldIterateFullTreeInReverseFromMaxKey() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final long[] keys = testTree.plainArray();
			final long maxKey = keys[keys.length - 1];

			final PrimitiveIterator.OfLong it = testTree.bPlusTree().lesserOrEqualKeyIterator(maxKey);
			int count = 0;
			while (it.hasNext()) {
				it.nextLong();
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
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(3, String.class);
			final long[] keys = {10, 20, 30, 50, 60};
			for (final long key : keys) {
				tree.insert(key, "Value" + key);
			}

			final PrimitiveIterator.OfLong it = tree.lesserOrEqualKeyIterator(40);
			final long[] reconstructed = new long[3];
			int index = 0;
			while (it.hasNext()) {
				reconstructed[index++] = it.nextLong();
			}

			assertArrayEquals(new long[]{30, 20, 10}, reconstructed);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("returns empty reverse key iterator on empty tree")
		void shouldReturnEmptyKeyReverseIteratorOnEmptyTree() {
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(3, String.class);
			final PrimitiveIterator.OfLong it = tree.keyReverseIterator();
			assertFalse(it.hasNext());
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("iterates single key on single-element tree")
		void shouldIterateKeyReverseOnSingleElementTree() {
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(3, String.class);
			tree.insert(42, "Value42");
			final PrimitiveIterator.OfLong it = tree.keyReverseIterator();
			assertTrue(it.hasNext());
			assertEquals(42, it.nextLong());
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
			final long[] plainFullArray = testTree.plainArray();
			final InsertionPosition insertionPosition =
				ArrayUtils.computeInsertPositionOfLongInOrderedArray(40, plainFullArray);

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
			final long[] keys = testTree.plainArray();
			final Iterator<Entry<String>> it = testTree.bPlusTree().lesserOrEqualEntryIterator(
				keys[0] - 1000);
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("iterates entries backwards when start key falls in the gap before the first key of a leaf")
		void shouldIterateEntriesFromKeyInGapBetweenLeavesReverse() {
			// key 40 falls below the first key of the leaf [50,60] while the preceding leaf [10,20,30]
			// still holds entries <= 40; the iterator must cross the gap and return them in descending order
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(3, String.class);
			final long[] keys = {10, 20, 30, 50, 60};
			for (final long key : keys) {
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
			final TransactionalLongBPlusTree<TransactionalList<String>> theTree =
				new TransactionalLongBPlusTree<>(
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
			final long[] originalArray = testTree.plainArray();

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
			final long[] originalArray = testTree.plainArray();
			final long keyToDelete = originalArray[0];

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
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(3, 1, 3, 1, String.class);
			final TreeMap<Long, String> reference = new TreeMap<>();
			for (int i = 0; i < 30; i++) {
				tree.insert(i, "Value" + i);
				reference.put((long) i, "Value" + i);
			}

			assertStateAfterCommit(
				tree,
				tested -> {
					// insertions to provoke splits inside the transaction
					for (int i = 100; i < 116; i++) {
						tested.insert(i, "Value" + i);
						reference.put((long) i, "Value" + i);
					}
					// deletions to provoke merges of the freshly created leaves
					for (int i = 100; i < 116; i++) {
						tested.delete(i);
						reference.remove((long) i);
					}
					for (int i = 0; i < 20; i++) {
						tested.delete(i);
						reference.remove((long) i);
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
				final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(
					3, 1, 3, 1, String.class);
				final TreeMap<Long, String> reference = new TreeMap<>();
				for (int i = 0; i < 8; i++) {
					final long key = random.nextInt(40);
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
							final long key = opKeys[i];
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
						final long[] expectedKeys = reference.keySet().stream().mapToLong(Long::longValue).toArray();
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
						for (final long key : expectedKeys) {
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
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(3, String.class);
			// empty leaf node has no key:value pairs, so the representation is empty
			assertEquals("", tree.toString());
		}

		@Test
		@DisplayName("prints simple two-element tree")
		void shouldPrintVerboseSimpleTree() {
			final TransactionalLongBPlusTree<String> bPlusTree = new TransactionalLongBPlusTree<>(3, String.class);
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
			final TransactionalLongBPlusTree<String> bPlusTree = new TransactionalLongBPlusTree<>(3, String.class);

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
				GenericEvitaInternalError.class, () -> new TransactionalLongBPlusTree<>(2, String.class)
			);
		}

		@Test
		@DisplayName("rejects even internal node block size")
		void shouldRejectEvenInternalNodeBlockSize() {
			assertThrows(
				GenericEvitaInternalError.class, () -> new TransactionalLongBPlusTree<>(3, 1, 4, 1, String.class)
			);
		}

		@Test
		@DisplayName("rejects internal node block size larger than value block size")
		void shouldRejectInternalNodeBlockSizeLargerThanValueBlockSize() {
			assertThrows(
				GenericEvitaInternalError.class, () -> new TransactionalLongBPlusTree<>(3, 1, 5, 2, String.class)
			);
		}

		@Test
		@DisplayName("accepts internal node block size equal to value block size")
		void shouldAcceptInternalNodeBlockSizeEqualToValueBlockSize() {
			assertDoesNotThrow(() -> new TransactionalLongBPlusTree<>(3, 1, 3, 1, String.class));
			assertDoesNotThrow(() -> new TransactionalLongBPlusTree<>(3, String.class));
		}

		@Test
		@DisplayName("returns zero size for newly created tree")
		void shouldReturnZeroSizeForEmptyTree() {
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(3, String.class);
			assertEquals(0, tree.size());
		}

		@Test
		@DisplayName("reports consistent state for empty tree")
		void shouldReportConsistentForEmptyTree() {
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(3, String.class);
			final ConsistencyReport report = tree.getConsistencyReport();
			assertEquals(ConsistencyState.CONSISTENT, report.state());
		}

		@Test
		@DisplayName("genericClass returns the raw tree class")
		void shouldReturnCorrectGenericClass() {
			final Class<TransactionalLongBPlusTree<String>> theClass = TransactionalLongBPlusTree.genericClass();
			assertSame(TransactionalLongBPlusTree.class, theClass);
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
			@Nonnull TransactionalLongBPlusTree<String> tree,
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
		 * Builds a single-element leaf node carrying the given key with a matching string value.
		 *
		 * @param key the key (and value suffix) to store
		 * @return a leaf node holding exactly the one key
		 */
		@Nonnull
		private static TransactionalLongBPlusTree.BPlusLeafTreeNode<String> leaf(long key) {
			final long[] keys = {key};
			final String[] values = {"Value" + key};
			return new TransactionalLongBPlusTree.BPlusLeafTreeNode<>(
				keys, values, new long[3], new String[3], 0, 1, true, null
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
		private static TransactionalLongBPlusTree.BPlusInternalTreeNode internal(
			@Nonnull long[] keys, @Nonnull BPlusTreeNode<?>... children
		) {
			final long[] keyArray = new long[5];
			System.arraycopy(keys, 0, keyArray, 0, keys.length);
			final BPlusTreeNode<?>[] childArray =
				new BPlusTreeNode<?>[6];
			System.arraycopy(children, 0, childArray, 0, children.length);
			return new TransactionalLongBPlusTree.BPlusInternalTreeNode(
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
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(5, 1, 5, 2, String.class);

			// under-occupied internal node: 2 children -> 1 key (keyCount 1 < minInternalNodeBlockSize 2),
			// yet size 2 satisfies the lenient child-count check
			final TransactionalLongBPlusTree.BPlusInternalTreeNode underOccupied =
				internal(new long[]{2}, leaf(1), leaf(2));
			// properly occupied internal node: 3 children -> 2 keys (keyCount 2 == minimum)
			final TransactionalLongBPlusTree.BPlusInternalTreeNode wellOccupied =
				internal(new long[]{4, 5}, leaf(3), leaf(4), leaf(5));

			final TransactionalLongBPlusTree.BPlusInternalTreeNode root =
				internal(new long[]{3}, underOccupied, wellOccupied);

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
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(5, 1, 5, 2, String.class);

			// both children carry the minimum of 2 keys (3 leaves each)
			final TransactionalLongBPlusTree.BPlusInternalTreeNode left =
				internal(new long[]{2, 3}, leaf(1), leaf(2), leaf(3));
			final TransactionalLongBPlusTree.BPlusInternalTreeNode right =
				internal(new long[]{5, 6}, leaf(4), leaf(5), leaf(6));

			final TransactionalLongBPlusTree.BPlusInternalTreeNode root =
				internal(new long[]{4}, left, right);

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
			final TransactionalLongBPlusTree<String> tree1 =
				new TransactionalLongBPlusTree<>(3, String.class);
			final TransactionalLongBPlusTree<String> tree2 =
				new TransactionalLongBPlusTree<>(3, String.class);

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
			final TransactionalLongBPlusTree<String> tree =
				new TransactionalLongBPlusTree<>(3, String.class);

			// insert only 1 element so root remains a leaf
			assertStateAfterCommit(
				tree,
				tested -> tested.insert(42, "Value42"),
				(original, committed) -> {
					assertEquals(0, original.size());
					assertEquals(1, committed.size());
					assertEquals("Value42", committed.search(42).orElse(null));
					verifyTreeConsistency(committed, 42);
				}
			);
		}

		@Test
		@DisplayName("committed tree is a different instance than original")
		void shouldReturnDifferentInstanceAfterCommit() {
			final TransactionalLongBPlusTree<String> tree =
				new TransactionalLongBPlusTree<>(3, String.class);

			assertStateAfterCommit(
				tree,
				tested -> tested.insert(1, "Value1"),
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
			final long[] originalArray = testTree.plainArray();

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
			final long[] originalArray = testTree.plainArray();

			assertStateAfterCommit(
				testTree.bPlusTree(),
				tested -> {
					tested.insert(5555, "Value5555");
					tested.delete(originalArray[0]);
				},
				(original, committed) -> {
					// after commit, the committed tree should be consistent
					// and independently usable (no dangling tx layers)
					final long[] expectedArray = ArrayUtils.insertLongIntoOrderedArray(
						5555, ArrayUtils.removeLongFromOrderedArray(originalArray[0], originalArray)
					);
					verifyTreeConsistency(committed, expectedArray);

					// the committed tree should be further modifiable
					// in a new transaction
					assertStateAfterCommit(
						committed,
						tested2 -> tested2.insert(6666, "Value6666"),
						(original2, committed2) -> {
							verifyTreeConsistency(original2, expectedArray);
							assertEquals("Value6666", committed2.search(6666).orElse(null));
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
				() -> new TransactionalLongBPlusTree<>(3, 0, 3, 1, String.class)
			);
		}

		@Test
		@DisplayName("rejects minValueBlockSize greater than ceil(valueBlockSize/2) - 1")
		void shouldRejectMinValueBlockSizeTooLarge() {
			// valueBlockSize=3, ceil(3/2)-1 = 1, so minValueBlockSize=2 is invalid
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new TransactionalLongBPlusTree<>(3, 2, 3, 1, String.class)
			);
		}

		@Test
		@DisplayName("rejects minInternalNodeBlockSize less than one")
		void shouldRejectMinInternalNodeBlockSizeLessThanOne() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new TransactionalLongBPlusTree<>(3, 1, 3, 0, String.class)
			);
		}

		@Test
		@DisplayName("rejects minInternalNodeBlockSize greater than ceil(internalNodeBlockSize/2) - 1")
		void shouldRejectMinInternalNodeBlockSizeTooLarge() {
			// internalNodeBlockSize=3, ceil(3/2)-1 = 1, so minInternal=2 is invalid
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new TransactionalLongBPlusTree<>(3, 1, 3, 2, String.class)
			);
		}

		@Test
		@DisplayName("rejects internalNodeBlockSize less than three")
		void shouldRejectInternalNodeBlockSizeLessThanThree() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new TransactionalLongBPlusTree<>(3, 1, 1, 0, String.class)
			);
		}

		@Test
		@DisplayName("rejects value type implementing TransactionalLayerProducer without wrapper")
		void shouldRejectTransactionalValueTypeWithoutWrapper() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new TransactionalLongBPlusTree<>(3, 1, 3, 1, TransactionalList.genericClass())
			);
		}

		@Test
		@DisplayName("default block sizes are applied by the wrapper constructor")
		void shouldSetDefaultBlockSizesWithWrapperConstructor() {
			//noinspection unchecked
			final TransactionalLongBPlusTree<TransactionalList<String>> tree =
				new TransactionalLongBPlusTree<>(
					TransactionalList.genericClass(),
					list -> new TransactionalList<>((List<String>) list)
				);
			assertEquals(64, tree.getValueBlockSize());
			assertEquals(31, tree.getMinValueBlockSize());
			assertEquals(31, tree.getInternalNodeBlockSize());
			assertEquals(15, tree.getMinInternalNodeBlockSize());
			assertEquals(0, tree.size());

			// should work -- wrapper is provided
			tree.insert(1, new TransactionalList<>(List.of("A")));
			assertEquals(1, tree.size());
		}

	}

	@Nested
	@DisplayName("Non-transactional mode")
	class NonTransactionalModeTest {

		@Test
		@DisplayName("insert, search, upsert and delete work without a transaction")
		void shouldExerciseCrudWithoutTransaction() {
			final TransactionalLongBPlusTree<String> tree =
				new TransactionalLongBPlusTree<>(3, String.class);

			// insert
			tree.insert(10, "Value10");
			tree.insert(20, "Value20");
			tree.insert(30, "Value30");
			assertEquals(3, tree.size());

			// search
			assertEquals("Value10", tree.search(10).orElse(null));
			assertEquals("Value20", tree.search(20).orElse(null));
			assertEquals("Value30", tree.search(30).orElse(null));
			assertTrue(tree.search(99).isEmpty());

			// verify consistency before mutation
			verifyTreeConsistency(tree, 10, 20, 30);

			// upsert -- update existing value (changes value, not key)
			tree.upsert(20, existing -> "Updated20");
			assertEquals("Updated20", tree.search(20).orElse(null));
			assertEquals(3, tree.size());

			// upsert -- insert new entry
			tree.upsert(25, existing -> "Value25");
			assertEquals("Value25", tree.search(25).orElse(null));
			assertEquals(4, tree.size());

			// delete
			tree.delete(10);
			assertEquals(3, tree.size());
			assertTrue(tree.search(10).isEmpty());

			// verify consistency -- cannot use verifyTreeConsistency because value for key 20 is now "Updated20"
			final ConsistencyReport report = tree.getConsistencyReport();
			assertEquals(ConsistencyState.CONSISTENT, report.state(), report.report());
		}

		@Test
		@DisplayName("insert triggers split without a transaction")
		void shouldSplitLeafNodeWithoutTransaction() {
			final TransactionalLongBPlusTree<String> tree =
				new TransactionalLongBPlusTree<>(3, String.class);
			for (int i = 1; i <= 10; i++) {
				tree.insert(i, "Value" + i);
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
			final long[] originalArray = testTree.plainArray();

			assertStateAfterCommit(
				testTree.bPlusTree(),
				tested -> {
					// insert a new key at the end
					tested.insert(9999, "Value9999");
					// delete the first key
					tested.delete(originalArray[0]);

					// build expected array
					final long[] expected = ArrayUtils.insertLongIntoOrderedArray(
						9999,
						ArrayUtils.removeLongFromOrderedArray(
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
			final TransactionalLongBPlusTree<String> tree =
				new TransactionalLongBPlusTree<>(3, String.class);
			tree.insert(1, "Value1");
			tree.insert(2, "Value2");

			assertStateAfterRollback(
				tree,
				tested -> {
					// these inserts will trigger splits
					tested.insert(3, "Value3");
					tested.insert(4, "Value4");
					tested.insert(5, "Value5");
					tested.insert(6, "Value6");
					assertEquals(6, tested.size());
				},
				(original, committed) -> {
					// original should be unmodified
					assertEquals(2, original.size());
					assertEquals("Value1", original.search(1).orElse(null));
					assertEquals("Value2", original.search(2).orElse(null));
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
			final long[] originalArray = testTree.plainArray();

			assertStateAfterRollback(
				testTree.bPlusTree(),
				tested -> {
					tested.insert(8888, "Value8888");
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

	@Nested
	@DisplayName("Deep atomicity")
	class DeepAtomicityTest {

		@Test
		@DisplayName("rollback of nested TransactionalLayerProducer value undoes changes")
		void shouldRollbackNestedTransactionalLayerProducerValues() {
			//noinspection unchecked
			final TransactionalLongBPlusTree<TransactionalList<String>> tree =
				new TransactionalLongBPlusTree<>(
					TransactionalList.genericClass(),
					list -> new TransactionalList<>((List<String>) list)
				);
			tree.insert(1, new TransactionalList<>(List.of("A", "B")));

			assertStateAfterRollback(
				tree,
				tested -> {
					tested.search(1).orElseThrow().add("C");
					// within transaction, should see 3 elements
					assertEquals(3, tested.search(1).orElseThrow().size());
				},
				(original, committed) -> {
					// after rollback, the original should still have 2
					assertEquals(2, original.search(1).orElseThrow().size());
					assertNull(committed);
				}
			);
		}

		@Test
		@DisplayName("commit with multiple TransactionalLayerProducer values")
		void shouldCommitMultipleTransactionalLayerProducerValues() {
			//noinspection unchecked
			final TransactionalLongBPlusTree<TransactionalList<String>> tree =
				new TransactionalLongBPlusTree<>(
					TransactionalList.genericClass(),
					list -> new TransactionalList<>((List<String>) list)
				);
			tree.insert(1, new TransactionalList<>(List.of("A")));
			tree.insert(2, new TransactionalList<>(List.of("X")));

			assertStateAfterCommit(
				tree,
				tested -> {
					tested.search(1).orElseThrow().add("B");
					tested.search(2).orElseThrow().add("Y");
				},
				(original, committed) -> {
					// original unchanged
					assertEquals(1, original.search(1).orElseThrow().size());
					assertEquals(1, original.search(2).orElseThrow().size());
					// committed has the changes
					final TransactionalList<String> list1 = committed.search(1).orElseThrow();
					assertEquals(2, list1.size());
					final TransactionalList<String> list2 = committed.search(2).orElseThrow();
					assertEquals(2, list2.size());
				}
			);
		}

	}

	@Nested
	@DisplayName("Assemble from single-leaf trees validation")
	class AssembleFromSingleLeafTreesValidationTest {

		@Test
		@DisplayName("rejects reassembled single-leaf pages that overlap across a leaf boundary")
		void shouldThrowWhenSingleLeafTreesOverlapAcrossBoundary() {
			// two single-leaf source trees whose key ranges overlap (a stale leaf-page twin shape):
			// leaf A ends at key 3 while leaf B restarts at key 2, so cross-leaf-order validation
			// must reject them; block size 5 keeps three keys in one leaf so getRoot() is a leaf
			final TransactionalLongBPlusTree<String> treeA = new TransactionalLongBPlusTree<>(5, 1, 5, 2, String.class);
			treeA.insert(1L, "Value1");
			treeA.insert(2L, "Value2");
			treeA.insert(3L, "Value3");

			final TransactionalLongBPlusTree<String> treeB = new TransactionalLongBPlusTree<>(5, 1, 5, 2, String.class);
			treeB.insert(2L, "Value2");
			treeB.insert(3L, "Value3");
			treeB.insert(4L, "Value4");

			final TransactionalLongBPlusTree<String> assembled = new TransactionalLongBPlusTree<>(5, 1, 5, 2, String.class);
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> assembled.assembleFromSingleLeafTrees(
					List.of(treeA, treeB), new int[]{0, 1}, "long B+ tree validation test"),
				"Overlapping single-leaf trees must fail the cross-leaf-order validation."
			);
			assertTrue(
				ex.getMessage().contains("overlaps its successor leaf-page sequence"),
				"The failure must be the cross-leaf-order corruption diagnostic, got: " + ex.getMessage()
			);
		}

		@Test
		@DisplayName("rejects a reassembled leaf page whose interior keys are out of order")
		void shouldThrowWhenLeafHasOutOfOrderInteriorKeys() {
			// build a sound single-leaf source tree, then corrupt its interior key order in place (swap two keys)
			// to simulate a serializer bug / truncated write / bit rot; the intra-leaf order check must reject it
			final TransactionalLongBPlusTree<String> corrupt =
				new TransactionalLongBPlusTree<>(10, 1, 3, 1, String.class);
			corrupt.insert(1L, "Value1");
			corrupt.insert(2L, "Value2");
			corrupt.insert(3L, "Value3");
			// mutate the live leaf key array directly: [1,2,3] -> [2,1,3]
			final long[] keys =
				((TransactionalLongBPlusTree.BPlusLeafTreeNode<String>) corrupt.getRoot()).getKeys();
			final long swap = keys[0];
			keys[0] = keys[1];
			keys[1] = swap;

			final TransactionalLongBPlusTree<String> assembled =
				new TransactionalLongBPlusTree<>(10, 1, 3, 1, String.class);
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> assembled.assembleFromSingleLeafTrees(
					List.of(corrupt), new int[]{0}, "long B+ tree intra-leaf test"),
				"A leaf with out-of-order interior keys must fail the intra-leaf-order validation."
			);
			assertTrue(
				ex.getMessage().contains("out-of-order keys"),
				"Expected the intra-leaf-order corruption diagnostic, got: " + ex.getMessage()
			);
		}

	}

	@Nested
	@DisplayName("Op-time boundary-mutation asserts")
	class OpTimeBoundaryMutationTest {

		/**
		 * Builds a single-leaf source tree holding the supplied keys. Block size 10 keeps every supplied key in
		 * one leaf (no split), so the tree's root stays a leaf that can be reassembled into a controlled spine.
		 *
		 * @param keys the keys to place in the leaf, in any order
		 * @return a single-leaf tree
		 */
		@Nonnull
		private static TransactionalLongBPlusTree<String> singleLeaf(@Nonnull long... keys) {
			final TransactionalLongBPlusTree<String> tree =
				new TransactionalLongBPlusTree<>(10, 1, 3, 1, String.class);
			for (final long key : keys) {
				tree.insert(key, "v" + key);
			}
			return tree;
		}

		/**
		 * Reassembles the supplied sound single-leaf trees into one tree with a deterministic spine: internal
		 * block size 3 caps a parent at four children, so five leaves split into two parents. The leaves are
		 * non-overlapping, so the Phase 1 cross-leaf validation inside the assembler passes; the assembled tree is
		 * then used to exercise the op-time boundary checks against hypothetical boundary keys.
		 *
		 * @param leaves the ordered, non-overlapping single-leaf trees
		 * @return the assembled tree
		 */
		@Nonnull
		private static TransactionalLongBPlusTree<String> assembleSound(
			@Nonnull List<TransactionalLongBPlusTree<String>> leaves
		) {
			final int[] pageSequences = new int[leaves.size()];
			for (int i = 0; i < pageSequences.length; i++) {
				pageSequences[i] = i;
			}
			return new TransactionalLongBPlusTree<String>(10, 1, 3, 1, String.class)
				.assembleFromSingleLeafTrees(leaves, pageSequences, "long B+ tree op-time boundary test");
		}

		@Test
		@DisplayName("tail insert overlapping the successor leaf under a different parent throws (Check T)")
		void shouldThrowOnMisroutedTailInsertAcrossParentBoundary() {
			// spine (internal block 3 => max 4 children => 5 leaves split 3 + 2): P1 = [L0,L1,L2], P2 = [L3,L4].
			// L2 is the RIGHTMOST child of P1 and its successor L3 is the LEFTMOST child of P2, so the fence
			// (8 = L3's first key) lives at the ROOT, not L2's immediate parent — this proves the cross-parent walk.
			final TransactionalLongBPlusTree<String> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(3, 4), singleLeaf(5, 6), singleLeaf(8, 9), singleLeaf(10, 11)
			));
			final AbstractTransactionalBPlusTree.Cursor cursor = tree.createCursor(5);
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
			final TransactionalLongBPlusTree<String> tree = assembleSound(List.of(
				singleLeaf(1, 5), singleLeaf(8, 12), singleLeaf(15, 16)
			));
			final AbstractTransactionalBPlusTree.Cursor cursor = tree.createCursor(8);
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
				() -> TransactionalLongBPlusTree.assertSeparatorOrder(new long[]{3, 15}, 2, 0));
		}

		@Test
		@DisplayName("head insert undercutting a predecessor under a different parent throws (Check H right-spine)")
		void shouldThrowOnMisroutedHeadInsertAcrossParentBoundary() {
			// L3 is the LEFTMOST child of P2; its predecessor L2 is the RIGHTMOST child of P1 (cross-parent). Check
			// H must walk up to the clamp ancestor (root) and descend P1's right spine to L2 (last key 6).
			final TransactionalLongBPlusTree<String> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(3, 4), singleLeaf(5, 6), singleLeaf(8, 9), singleLeaf(10, 11)
			));
			final AbstractTransactionalBPlusTree.Cursor cursor = tree.createCursor(8);
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
			final TransactionalLongBPlusTree<String> tree = assembleSound(List.of(
				singleLeaf(1, 4), singleLeaf(6), singleLeaf(8, 9)
			));
			final AbstractTransactionalBPlusTree.Cursor cursor = tree.createCursor(6);
			// the actual 0->1 key (6) is sound: both checks run and pass
			assertDoesNotThrow(() -> tree.assertInsertBoundaries(tree.findLeafNodeWithBoundaryContext(6), 6));
			// the SAME single-key leaf is subject to the head check (a smaller key undercuts L_pred's last key 4)
			assertThrows(GenericEvitaInternalError.class, () -> tree.assertHeadBoundary(cursor, 3));
			// ...and to the tail check (a larger key reaches L_succ's first key 8)
			assertThrows(GenericEvitaInternalError.class, () -> tree.assertTailBoundary(cursor, 8));
		}

		@Test
		@DisplayName("the insert-path descent resolves the same operands as a captured cursor")
		void shouldResolveSameBoundaryOperandsAsCursorPath() {
			// the SAME five-leaf spine the cross-parent tests use (P1 = [L0,L1,L2], P2 = [L3,L4]) — a three-leaf
			// fixture would put every fence at the leaf's immediate parent and never enter the right-spine walk,
			// leaving exactly the branches this test exists to cover untested
			final TransactionalLongBPlusTree<String> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(3, 4), singleLeaf(5, 6), singleLeaf(8, 9), singleLeaf(10, 11)
			));
			for (final long probe : new long[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}) {
				final AbstractTransactionalBPlusTree.Cursor cursor = tree.createCursor(probe);
				final TransactionalLongBPlusTree.BoundaryContext<String> context =
					tree.findLeafNodeWithBoundaryContext(probe);
				assertSame(cursor.leafNode(), context.leaf(), "Descent reached a different leaf for key " + probe);
				assertSame(
					tree.predecessorLeaf(cursor), context.predecessor(),
					"Predecessor differs for key " + probe
				);
			}
			// a descent that always answered "no fence" / "no predecessor" would keep every other test green, because
			// in a sound tree the asserts never fire — so pin the operands themselves
			assertTrue(tree.findLeafNodeWithBoundaryContext(5).hasFence(), "L2 has a successor, hence a fence.");
			assertEquals(
				8L, tree.findLeafNodeWithBoundaryContext(5).fence(),
				"L2 is the rightmost child of P1, so its fence is the ROOT separator (L3's first key)."
			);
			assertEquals(
				3L, tree.findLeafNodeWithBoundaryContext(1).fence(),
				"L0's fence is its same-parent successor's first key — the DEEPER of the two levels that record one."
			);
			assertFalse(
				tree.findLeafNodeWithBoundaryContext(10).hasFence(),
				"The rightmost leaf has no successor, hence no fence."
			);
			assertNull(
				tree.findLeafNodeWithBoundaryContext(1).predecessor(),
				"The leftmost leaf has no predecessor."
			);
			assertNotNull(
				tree.findLeafNodeWithBoundaryContext(8).predecessor(),
				"L3 is the leftmost child of P2, so its predecessor is reached by the right-spine walk."
			);
		}

		@Test
		@DisplayName("sequential bulk append and prepend never trip the op-time boundary asserts (happy-path pin)")
		void shouldNotThrowOnSequentialInsertions() {
			// ascending inserts are all tail inserts (Check T runs on the rightmost leaf and finds no fence);
			// descending inserts are all head inserts on the leftmost leaf (Check H finds no predecessor)
			final TransactionalLongBPlusTree<String> ascending = new TransactionalLongBPlusTree<>(3, String.class);
			assertDoesNotThrow(() -> {
				for (long i = 1; i <= 256; i++) {
					ascending.insert(i, "v" + i);
				}
			});
			final ConsistencyReport ascendingReport = ascending.getConsistencyReport();
			assertEquals(ConsistencyState.CONSISTENT, ascendingReport.state(), ascendingReport.report());

			final TransactionalLongBPlusTree<String> descending = new TransactionalLongBPlusTree<>(3, String.class);
			assertDoesNotThrow(() -> {
				for (long i = 256; i >= 1; i--) {
					descending.insert(i, "v" + i);
				}
			});
			final ConsistencyReport descendingReport = descending.getConsistencyReport();
			assertEquals(ConsistencyState.CONSISTENT, descendingReport.state(), descendingReport.report());
		}

	}

	@Nested
	@DisplayName("commit-time structural integrity validation")
	class DirtyLeafScopeValidationTest {

		@Nonnull
		private static TransactionalLongBPlusTree<String> singleLeaf(@Nonnull long... keys) {
			final TransactionalLongBPlusTree<String> tree =
				new TransactionalLongBPlusTree<>(10, 1, 3, 1, String.class);
			for (final long key : keys) {
				tree.insert(key, "v" + key);
			}
			return tree;
		}

		@Nonnull
		private static TransactionalLongBPlusTree<String> assembleSound(
			@Nonnull List<TransactionalLongBPlusTree<String>> leaves
		) {
			final int[] pageSequences = new int[leaves.size()];
			for (int i = 0; i < pageSequences.length; i++) {
				pageSequences[i] = i;
			}
			return new TransactionalLongBPlusTree<String>(10, 1, 3, 1, String.class)
				.assembleFromSingleLeafTrees(leaves, pageSequences, "long B+ tree scope test");
		}

		@Nonnull
		private static TransactionalLongBPlusTree.BPlusLeafTreeNode<String> leafAt(
			@Nonnull TransactionalLongBPlusTree<String> tree, long key) {
			return tree.createCursor(key).leafNode();
		}

		@Test
		@DisplayName("a sound dirty scope relocates and validates without throwing")
		void shouldNotThrowWhenDirtyLeavesAreSound() {
			final TransactionalLongBPlusTree<String> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(5, 6), singleLeaf(10, 11)
			));
			// the registry keeps probe KEYS, not node objects; each key relocates to the leaf that owns it
			assertDoesNotThrow(() -> tree.validateDirtyScope(List.<Object>of(1L, 5L, 10L)));
		}

		@Test
		@DisplayName("a leaf whose last key was widened past its successor is caught (tail)")
		void shouldDetectTailOverlapOnRelocateAndValidate() {
			final TransactionalLongBPlusTree<String> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(5, 6), singleLeaf(10, 11)
			));
			final TransactionalLongBPlusTree.BPlusLeafTreeNode<String> middle = leafAt(tree, 5);
			// simulate a reverted / lifecycle-corrupted layer widening the middle leaf's range from [5,6] to [5,10]
			// so its last key reaches the successor's first key; the separator is untouched, so relocating by the
			// leaf's own first key (5) still lands on it and the tail half-invariant fires
			final long[] keys = middle.getKeys();
			keys[1] = 10L;
			// relocate by the leaf's own (unchanged) first key 5 — the descent lands on it and the tail half-invariant fires
			final AbstractTransactionalBPlusTree.BPlusTreeCorruptedException ex = assertThrows(
				AbstractTransactionalBPlusTree.BPlusTreeCorruptedException.class,
				() -> tree.validateDirtyScope(List.<Object>of(5L))
			);
			assertTrue(
				ex.getMessage().contains("successor leaf boundary"),
				"Expected the tail cross-leaf diagnostic, got: " + ex.getMessage()
			);
		}

		@Test
		@DisplayName("a leaf undercut by a widened predecessor is caught (head)")
		void shouldDetectHeadOverlapOnRelocateAndValidate() {
			final TransactionalLongBPlusTree<String> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(5, 6), singleLeaf(10, 11)
			));
			// widen the PREDECESSOR leaf's last key from 2 to 5 so it reaches the middle leaf's first key; relocating
			// the middle leaf by its unchanged first key (5) lands on it and the head half-invariant compares against
			// the predecessor's corrupted last key
			final long[] predecessorKeys = leafAt(tree, 1).getKeys();
			predecessorKeys[1] = 5L;
			// relocate the middle leaf by its unchanged first key 5; the head half-invariant compares against the
			// predecessor's corrupted last key
			final AbstractTransactionalBPlusTree.BPlusTreeCorruptedException ex = assertThrows(
				AbstractTransactionalBPlusTree.BPlusTreeCorruptedException.class,
				() -> tree.validateDirtyScope(List.<Object>of(5L))
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
			// it rather than dereference keys[0]
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(10, 1, 3, 1, String.class);
			assertDoesNotThrow(() -> tree.validateDirtyScope(List.<Object>of(42L)));
		}

		/**
		 * Widens (in place) the effective last key of the leaf that currently routes {@code leafKey}, simulating a
		 * lifecycle bug that reverted / corrupted the leaf's write layer so its range overlaps the successor. Mutates
		 * the write layer when one exists (the transactional case), otherwise the leaf itself.
		 */
		private static void corruptLastKey(
			@Nonnull TransactionalLongBPlusTree<String> tree, long leafKey, long newLastKey) {
			final TransactionalLongBPlusTree.BPlusLeafTreeNode<String> leaf = tree.createCursor(leafKey).leafNode();
			final TransactionalLongBPlusTree.BPlusLeafTreeNode<String> layer =
				Transaction.getTransactionalMemoryLayerIfExists(leaf);
			final TransactionalLongBPlusTree.BPlusLeafTreeNode<String> writable = layer != null ? layer : leaf;
			writable.getKeys()[writable.getPeek()] = newLastKey;
		}

		@Test
		@DisplayName("pre-commit pipeline: a transactional insert registers its leaf and a healthy scope is accepted")
		void shouldRegisterDirtiedLeafAndAcceptHealthyScope() {
			final TransactionalLongBPlusTree<String> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(5, 6), singleLeaf(10, 11)
			));
			assertStateAfterCommit(
				tree,
				t -> {
					t.insert(7, "v7");
					final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
					// registration fired AND the registry is keyed by this exact tree instance — the identity the
					// pre-commit (pre-WAL) pass and the post-replay merge both look the scope up by
					assertFalse(
						maintainer.getDirtyScopeTokens(t).isEmpty(),
						"the transactional insert must register its dirtied leaf under this tree"
					);
					// the healthy scope must be accepted by the pre-commit pass (no false positive)
					assertDoesNotThrow(maintainer::validateDirtyScopesBeforeCommit);
				},
				(t, committed) -> assertNotNull(committed)
			);
		}

		@Test
		@DisplayName("pre-commit pipeline: a corrupted registered leaf is rejected by the pre-commit pass")
		void shouldRejectCorruptedScopeInPreCommitPass() {
			final TransactionalLongBPlusTree<String> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(5, 6), singleLeaf(10, 11)
			));
			assertThrows(
				AbstractTransactionalBPlusTree.BPlusTreeCorruptedException.class,
				() -> assertStateAfterCommit(
					tree,
					t -> {
						t.insert(7, "v7");
						// widen the dirtied leaf (now [5,6,7]) so its last key reaches the successor's first key (10)
						corruptLastKey(t, 5, 10L);
						Transaction.getTransactionalLayerMaintainer().validateDirtyScopesBeforeCommit();
					},
					(t, committed) -> fail("the pre-commit pass must reject before commit")
				)
			);
		}

		@Test
		@DisplayName("post-replay pipeline: a corrupted registered leaf is rejected by the commit merge")
		void shouldRejectCorruptedScopeInCommitMerge() {
			final TransactionalLongBPlusTree<String> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(5, 6), singleLeaf(10, 11)
			));
			assertThrows(
				AbstractTransactionalBPlusTree.BPlusTreeCorruptedException.class,
				() -> assertStateAfterCommit(
					tree,
					t -> {
						t.insert(7, "v7");
						// no pre-commit pass here — the commit merge (post-replay, inside createCopyWithMergedTransactionalMemory)
						// must relocate the registered leaf in the merged tree and catch the overlap
						corruptLastKey(t, 5, 10L);
					},
					(t, committed) -> fail("the commit merge must reject the corrupted scope")
				)
			);
		}

		@Test
		@DisplayName("pre-commit pipeline: a rolled-back savepoint does not spuriously reject the commit")
		void shouldNotRejectAfterSavepointRollback() {
			final TransactionalLongBPlusTree<String> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(5, 6), singleLeaf(10, 11)
			));
			assertStateAfterCommit(
				tree,
				t -> {
					final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
					final TransactionalLayerMaintainer.Savepoint savepoint = maintainer.openSavepoint();
					t.insert(7, "v7");
					// the insert registered the dirtied leaf inside the savepoint; rolling the savepoint back reverts
					// the leaf, leaving the registered object stale — but relocating by its key lands on a healthy
					// leaf, so the pre-commit pass must NOT spuriously reject
					maintainer.rollbackSavepoint(savepoint);
					assertDoesNotThrow(maintainer::validateDirtyScopesBeforeCommit);
				},
				(t, committed) -> assertNotNull(committed)
			);
		}

		@Test
		@DisplayName("Registry hygiene: a remove-only transaction still registers its dirtied leaf")
		void shouldRegisterLeafOnRemoveOnlyTransaction() {
			final TransactionalLongBPlusTree<String> tree = assembleSound(List.of(
				singleLeaf(1, 2), singleLeaf(5, 6), singleLeaf(10, 11)
			));
			assertStateAfterCommit(
				tree,
				t -> {
					// a clean remove (min block size 1 → [5,6] becomes [5], no underflow/rebalance) exercises the
					// delete seam only; a reverted removal layer is exactly what the commit-time passes must be able to relocate
					t.delete(6);
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
			// block size 3 splits the root leaf as these four keys are inserted (mirrors shouldSplitNodeWhenFull)
			final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(3, String.class);
			assertStateAfterCommit(
				tree,
				t -> {
					t.insert(1, "v1");
					t.insert(2, "v2");
					t.insert(3, "v3");
					t.insert(4, "v4");
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

	@Nested
	@DisplayName("Assembled-spine split integrity")
	class AssembledSpineSplitIntegrityTest {

		/**
		 * Builds a single-leaf source tree holding four consecutive keys starting at {@code base}, using the block
		 * sizes (8 value / 3 minimum value / 3 internal / 1 minimum internal) shared by every tree in this class.
		 * Four keys per leaf keeps every source tree well under its leaf capacity, exactly like a persisted page
		 * that has not yet grown to its full size.
		 *
		 * @param base the first of the four consecutive keys to store
		 * @return a single-leaf tree holding keys {@code base} through {@code base + 3}
		 */
		@Nonnull
		private static TransactionalLongBPlusTree<String> singleLeafOfFour(long base) {
			final TransactionalLongBPlusTree<String> tree =
				new TransactionalLongBPlusTree<>(8, 3, 3, 1, String.class);
			for (long key = base; key < base + 4; key++) {
				tree.insert(key, "Value" + key);
			}
			return tree;
		}

		@Test
		@DisplayName("splits an assembled internal node once new writes push it to capacity")
		void shouldSplitInternalNodeAfterLeafSplitPushesParentToCapacity() {
			// six single-leaf pages assembled into a two-level spine: two internal nodes of three leaves each
			// (two separator keys, one below their three-key capacity) under a root - the same shape a granular
			// index reload produces for a range that has outgrown a handful of leaf pages
			final List<TransactionalLongBPlusTree<String>> leaves = List.of(
				singleLeafOfFour(0), singleLeafOfFour(100), singleLeafOfFour(200),
				singleLeafOfFour(300), singleLeafOfFour(400), singleLeafOfFour(500)
			);
			final TransactionalLongBPlusTree<String> tree =
				new TransactionalLongBPlusTree<String>(8, 3, 3, 1, String.class)
					.assembleFromSingleLeafTrees(
						leaves, new int[]{0, 1, 2, 3, 4, 5}, "spine split integrity test");

			// filling the first leaf (keys 0-3) to its value block size of 8 forces it to split; the extra child
			// this hands to its parent internal node fills that parent to ITS OWN capacity (three keys, four
			// children) too, so the parent must split immediately afterwards to keep the spine balanced
			assertDoesNotThrow(() -> {
				tree.insert(4, "Value4");
				tree.insert(5, "Value5");
				tree.insert(6, "Value6");
				tree.insert(7, "Value7");
			});

			final long[] expectedKeys = {
				0, 1, 2, 3, 4, 5, 6, 7,
				100, 101, 102, 103,
				200, 201, 202, 203,
				300, 301, 302, 303,
				400, 401, 402, 403,
				500, 501, 502, 503
			};
			assertEquals(expectedKeys.length, tree.size());
			verifyTreeConsistency(tree, expectedKeys);
		}

	}

	@Nested
	@DisplayName("Internal node fan-out invariants")
	class InternalNodeFanOutInvariantTest {

		/**
		 * Recursively walks the spine rooted at {@code node}, asserting that every internal node's key count stays
		 * within the tree's configured internal node block size. Internal nodes are routing structures sized
		 * independently of the leaf value block size, and growing past their own capacity would overflow their
		 * backing arrays the next time such a node needs to split.
		 *
		 * @param node                  the node to inspect; an internal node is checked and its children are
		 *                              recursed into, a leaf ends the walk
		 * @param internalNodeBlockSize the maximum number of keys an internal node may hold
		 */
		private static void assertInternalNodeCapacity(@Nonnull BPlusTreeNode<?> node, int internalNodeBlockSize) {
			if (node instanceof TransactionalLongBPlusTree.BPlusInternalTreeNode internalNode) {
				assertTrue(
					internalNode.keyCount() <= internalNodeBlockSize,
					"Internal node holds " + internalNode.keyCount() + " keys, exceeding the internal node " +
						"block size " + internalNodeBlockSize + "."
				);
				for (int i = 0; i <= internalNode.getPeek(); i++) {
					assertInternalNodeCapacity(internalNode.getChildren()[i], internalNodeBlockSize);
				}
			}
		}

		@Test
		@DisplayName("keeps every internal node within its configured block size through incremental growth")
		void shouldRespectInternalNodeBlockSizeThroughIncrementalGrowth() {
			final TransactionalLongBPlusTree<String> tree =
				new TransactionalLongBPlusTree<>(8, 3, 3, 1, String.class);
			for (long key = 0; key <= 500; key++) {
				tree.insert(key, "Value" + key);
			}

			assertInternalNodeCapacity(tree.getRoot(), tree.getInternalNodeBlockSize());
		}

	}

}
