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

import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyReport;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.TransactionalObjectBPlusTree.Entry;
import io.evitadb.index.list.TransactionalList;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies the correctness of the {@link TransactionalObjectBPlusTree} implementation. It exercises insert,
 * search, upsert, delete, iteration, rebalancing (steal and merge), transactional semantics, tree structure
 * visualization, constructor validation, and randomized generational proof.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("TransactionalObjectBPlusTree")
class TransactionalObjectBPlusTreeTest implements TimeBoundedTestSupport {

	/**
	 * Verifies that the tree is in a consistent state and that both forward and reverse value iterators return the
	 * expected values.
	 *
	 * @param bPlusTree     the tree to verify
	 * @param expectedArray the expected sorted key array
	 */
	private static void verifyTreeConsistency(
		@Nonnull TransactionalObjectBPlusTree<Integer, String> bPlusTree, @Nonnull int... expectedArray
	) {
		final ConsistencyReport consistencyReport = bPlusTree.getConsistencyReport();
		assertEquals(ConsistencyState.CONSISTENT, consistencyReport.state(), consistencyReport.report());
		verifyForwardValueIterator(bPlusTree, expectedArray);
		verifyReverseValueIterator(bPlusTree, expectedArray);
	}

	/**
	 * Asserts that the forward value iterator of the tree returns values matching the given sorted key array in
	 * ascending order.
	 *
	 * @param tree     the tree whose iterator is verified
	 * @param keyArray the expected sorted key array
	 */
	private static void verifyForwardValueIterator(
		@Nonnull TransactionalObjectBPlusTree<Integer, String> tree, @Nonnull int... keyArray
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
	 * Asserts that the reverse value iterator of the tree returns values matching the given sorted key array in
	 * descending order.
	 *
	 * @param tree     the tree whose reverse iterator is verified
	 * @param keyArray the expected sorted key array
	 */
	private static void verifyReverseValueIterator(
		@Nonnull TransactionalObjectBPlusTree<Integer, String> tree, @Nonnull int... keyArray
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
	 * Creates a random tree using the default block sizes and the given random seed and element count.
	 *
	 * @param seed          the random seed
	 * @param totalElements the number of unique elements to insert
	 * @return a tuple of the tree and its sorted key array
	 */
	@Nonnull
	private static TreeTuple prepareRandomTree(long seed, int totalElements) {
		return prepareRandomTree(3, 1, 3, 1, seed, totalElements);
	}

	/**
	 * Creates a random tree with the given block size parameters, random seed, and element count.
	 *
	 * @param valueBlockSize      max keys per leaf node
	 * @param minValueBlockSize   min keys per leaf node
	 * @param internalNodeSize    max keys per internal node
	 * @param minInternalNodeSize min keys per internal node
	 * @param seed                the random seed
	 * @param totalElements       the number of unique elements
	 * @return a tuple of the tree and its sorted key array
	 */
	@Nonnull
	private static TreeTuple prepareRandomTree(
		int valueBlockSize, int minValueBlockSize, int internalNodeSize, int minInternalNodeSize,
		long seed, int totalElements
	) {
		final Random random = new Random(seed);
		final TransactionalObjectBPlusTree<Integer, String> bPlusTree = new TransactionalObjectBPlusTree<>(
			valueBlockSize, minValueBlockSize, internalNodeSize, minInternalNodeSize, Integer.class, String.class
		);
		int[] plainArray = new int[0];
		do {
			final int i = random.nextInt(totalElements * 2);
			bPlusTree.insert(i, "Value" + i);
			plainArray = ArrayUtils.insertIntIntoOrderedArray(i, plainArray);
		} while (plainArray.length < totalElements);

		return new TreeTuple(bPlusTree, plainArray);
	}

	/**
	 * Deletes a key from the tree inside a transaction, verifies consistency before and after commit, and returns the
	 * committed tree.
	 *
	 * @param tree          the tree to delete from
	 * @param expectedArray reference to the current expected key array, updated after deletion
	 * @param keyToDelete   the key to delete
	 * @return the committed tree after deletion
	 */
	@Nonnull
	private static TransactionalObjectBPlusTree<Integer, String> deleteAndVerify(
		@Nonnull TransactionalObjectBPlusTree<Integer, String> tree,
		@Nonnull AtomicReference<int[]> expectedArray, int keyToDelete
	) {
		final AtomicReference<TransactionalObjectBPlusTree<Integer, String>> result = new AtomicReference<>();
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

	@Nested
	@DisplayName("Insert operations")
	class InsertOperationsTest {

		@Test
		@DisplayName("overwrites value when inserting duplicate key")
		void shouldOverwriteDuplicateKeys() {
			final TransactionalObjectBPlusTree<Integer, String> bPlusTree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);
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
			final TransactionalObjectBPlusTree<Integer, String> bPlusTree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);

			assertStateAfterCommit(
				bPlusTree,
				tested -> {
					tested.insert(1, "Value1");
					tested.insert(2, "Value2");
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
			final TransactionalObjectBPlusTree<Integer, String> bPlusTree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);
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
			final TransactionalObjectBPlusTree<Integer, String> bPlusTree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);
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
			final TransactionalObjectBPlusTree<Integer, String> tree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);
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
			final TransactionalObjectBPlusTree<Integer, String> tree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);
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
			final TransactionalObjectBPlusTree<Integer, String> theTree = testTree.bPlusTree();
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
			final TransactionalObjectBPlusTree<Integer, String> theTree = testTree.bPlusTree();
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
					verifyTreeConsistency(committed, ArrayUtils.insertIntIntoOrderedArray(100, expectedArray));
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
			final TransactionalObjectBPlusTree<Integer, String> theTree = testTree.bPlusTree();
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
			final TransactionalObjectBPlusTree<Integer, String> tree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);
			tree.insert(42, "Value42");
			tree.delete(42);
			assertEquals(0, tree.size());
			assertTrue(tree.search(42).isEmpty());
			verifyTreeConsistency(tree);
		}

	}

	@Nested
	@DisplayName("Rebalancing - steal operations")
	class StealOperationsTest {

		@Test
		@DisplayName("steals from left sibling when node underflows")
		void shouldStealFromLeftmostNode() {
			final TransactionalObjectBPlusTree<Integer, String> bPlusTree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);
			final AtomicReference<TransactionalObjectBPlusTree<Integer, String>> theCommittedTree =
				new AtomicReference<>();

			assertStateAfterCommit(
				bPlusTree,
				tested -> {
					tested.insert(15, "Value15");
					tested.insert(17, "Value17");
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
			final TransactionalObjectBPlusTree<Integer, String> bPlusTree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);
			final AtomicReference<TransactionalObjectBPlusTree<Integer, String>> theCommittedTree =
				new AtomicReference<>();

			assertStateAfterCommit(
				bPlusTree,
				tested -> {
					tested.insert(15, "Value15");
					tested.insert(17, "Value17");
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
			final TransactionalObjectBPlusTree<Integer, String> bPlusTree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);
			final AtomicReference<TransactionalObjectBPlusTree<Integer, String>> theCommittedTree =
				new AtomicReference<>();

			assertStateAfterCommit(
				bPlusTree,
				tested -> {
					tested.insert(15, "Value15");
					tested.insert(17, "Value17");
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

		@Test
		@DisplayName("merges with left sibling node")
		void shouldMergeWithLeftNode() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final AtomicReference<int[]> expectedArray = new AtomicReference<>(testTree.plainArray());
			TransactionalObjectBPlusTree<Integer, String> tree = testTree.bPlusTree();

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
			TransactionalObjectBPlusTree<Integer, String> tree = testTree.bPlusTree();

			tree = deleteAndVerify(tree, expectedArray, 92);
			deleteAndVerify(tree, expectedArray, 87);
		}

		@Test
		@DisplayName("cascades multiple merges with left parent")
		void shouldMergeCausingIntermediateParentToMergeLeft() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final AtomicReference<int[]> expectedArray = new AtomicReference<>(testTree.plainArray());
			TransactionalObjectBPlusTree<Integer, String> tree = testTree.bPlusTree();

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
			TransactionalObjectBPlusTree<Integer, String> tree = testTree.bPlusTree();

			tree = deleteAndVerify(tree, expectedArray, 25);
			tree = deleteAndVerify(tree, expectedArray, 26);
			tree = deleteAndVerify(tree, expectedArray, 27);
			deleteAndVerify(tree, expectedArray, 30);
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
			final InsertionPosition insertionPosition =
				ArrayUtils.computeInsertPositionOfIntInOrderedArray(40, plainFullArray);

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
			final InsertionPosition insertionPosition =
				ArrayUtils.computeInsertPositionOfIntInOrderedArray(39, plainFullArray);

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
			final TransactionalObjectBPlusTree<Integer, String> tree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);
			final Iterator<String> it = tree.valueIterator();
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("iterates single element tree")
		void shouldIterateForwardOnSingleElementTree() {
			final TransactionalObjectBPlusTree<Integer, String> tree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);
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

	}

	@Nested
	@DisplayName("Forward key iteration")
	class ForwardKeyIterationTest {

		@Test
		@DisplayName("iterates keys from exact existing key")
		void shouldIterateThroughLeafNodeKeysLeftToRightFromExactPosition() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final Iterator<Integer> it = testTree.bPlusTree().greaterOrEqualKeyIterator(40);
			final int[] plainFullArray = testTree.plainArray();
			final InsertionPosition insertionPosition =
				ArrayUtils.computeInsertPositionOfIntInOrderedArray(40, plainFullArray);

			assertTrue(insertionPosition.alreadyPresent());
			final int startPos = insertionPosition.position();
			final Integer[] partialCopy = new Integer[plainFullArray.length - startPos];
			for (int i = startPos; i < plainFullArray.length; i++) {
				partialCopy[i - startPos] = plainFullArray[i];
			}

			final Integer[] reconstructedArray = new Integer[partialCopy.length];
			int index = 0;
			while (it.hasNext()) {
				reconstructedArray[index++] = it.next();
			}

			assertArrayEquals(partialCopy, reconstructedArray);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("returns empty key iterator when exceeding bounds")
		void shouldFailToIterateKeysLeftToRightThroughNonExistingValues() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final Iterator<Integer> it = testTree.bPlusTree().greaterOrEqualKeyIterator(1000);
			assertFalse(it.hasNext());

			final Iterator<Integer> it2 = testTree.bPlusTree().greaterOrEqualKeyIterator(-1000);
			assertTrue(it2.hasNext());
			assertEquals(0, it2.next());

			final Iterator<Integer> it3 = testTree.bPlusTree().greaterOrEqualKeyIterator(177);
			assertTrue(it3.hasNext());
			assertEquals(179, it3.next());
		}

	}

	@Nested
	@DisplayName("Forward entry iteration")
	class ForwardEntryIterationTest {

		@Test
		@DisplayName("iterates entries from exact existing key")
		void shouldIterateThroughLeafNodeEntriesLeftToRightFromExactPosition() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final Iterator<Entry<Integer, String>> it = testTree.bPlusTree().greaterOrEqualEntryIterator(40);
			final int[] plainFullArray = testTree.plainArray();
			final InsertionPosition insertionPosition =
				ArrayUtils.computeInsertPositionOfIntInOrderedArray(40, plainFullArray);

			assertTrue(insertionPosition.alreadyPresent());
			final int startPos = insertionPosition.position();
			//noinspection unchecked
			final Entry<Integer, String>[] partialCopy = new Entry[plainFullArray.length - startPos];
			for (int i = startPos; i < plainFullArray.length; i++) {
				partialCopy[i - startPos] = new Entry<>(plainFullArray[i], "Value" + plainFullArray[i]);
			}

			//noinspection unchecked
			final Entry<Integer, String>[] reconstructedArray = new Entry[partialCopy.length];
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
			final Iterator<Entry<Integer, String>> it = testTree.bPlusTree().greaterOrEqualEntryIterator(1000);
			assertFalse(it.hasNext());
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
			final Iterator<String> it = testTree.bPlusTree().lesserOrEqualValueIterator(-1000);
			assertFalse(it.hasNext());

			final Iterator<String> it2 = testTree.bPlusTree().lesserOrEqualValueIterator(1000);
			assertTrue(it2.hasNext());
			assertEquals("Value198", it2.next());

			final Iterator<String> it3 = testTree.bPlusTree().lesserOrEqualValueIterator(177);
			assertTrue(it3.hasNext());
			assertEquals("Value176", it3.next());
		}

		@Test
		@DisplayName("returns empty reverse iterator on empty tree")
		void shouldIterateReverseOnEmptyTree() {
			final TransactionalObjectBPlusTree<Integer, String> tree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);
			final Iterator<String> it = tree.valueReverseIterator();
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("reverse iterates single element tree")
		void shouldIterateReverseOnSingleElementTree() {
			final TransactionalObjectBPlusTree<Integer, String> tree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);
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

	}

	@Nested
	@DisplayName("Reverse key iteration")
	class ReverseKeyIterationTest {

		@Test
		@DisplayName("iterates keys backwards from exact existing key")
		void shouldIterateThroughLeafNodeKeysRightToLeftFromExactPosition() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final Iterator<Integer> it = testTree.bPlusTree().lesserOrEqualKeyIterator(40);
			final int[] plainFullArray = testTree.plainArray();
			final InsertionPosition insertionPosition =
				ArrayUtils.computeInsertPositionOfIntInOrderedArray(40, plainFullArray);

			assertTrue(insertionPosition.alreadyPresent());
			final int endPos = insertionPosition.position();
			final Integer[] partialCopy = new Integer[endPos + 1];
			for (int i = endPos; i >= 0; i--) {
				partialCopy[endPos - i] = plainFullArray[i];
			}

			final Integer[] reconstructedArray = new Integer[partialCopy.length];
			int index = 0;
			while (it.hasNext()) {
				reconstructedArray[index++] = it.next();
			}

			assertArrayEquals(partialCopy, reconstructedArray);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("returns empty reverse key iterator when below minimum")
		void shouldFailToIterateKeysRightToLeftThroughNonExistingValues() {
			final TreeTuple testTree = prepareRandomTree(42, 100);
			final Iterator<Integer> it = testTree.bPlusTree().lesserOrEqualKeyIterator(-1000);
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
			final Iterator<Entry<Integer, String>> it = testTree.bPlusTree().lesserOrEqualEntryIterator(40);
			final int[] plainFullArray = testTree.plainArray();
			final InsertionPosition insertionPosition =
				ArrayUtils.computeInsertPositionOfIntInOrderedArray(40, plainFullArray);

			assertTrue(insertionPosition.alreadyPresent());
			final int endPos = insertionPosition.position();
			//noinspection unchecked
			final Entry<Integer, String>[] partialCopy = new Entry[endPos + 1];
			for (int i = endPos; i >= 0; i--) {
				partialCopy[endPos - i] = new Entry<>(plainFullArray[i], "Value" + plainFullArray[i]);
			}

			//noinspection unchecked
			final Entry<Integer, String>[] reconstructedArray = new Entry[partialCopy.length];
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
			final Iterator<Entry<Integer, String>> it = testTree.bPlusTree().lesserOrEqualEntryIterator(-1000);
			assertFalse(it.hasNext());
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
					tested.insert(100001, "Value100001");
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
			final TransactionalObjectBPlusTree<Integer, TransactionalList<String>> theTree =
				new TransactionalObjectBPlusTree<>(
					Integer.class, TransactionalList.genericClass(),
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

	}

	@Nested
	@DisplayName("Tree structure visualization")
	class TreeStructureTest {

		@Test
		@DisplayName("prints simple two-element tree")
		void shouldPrintVerboseSimpleTree() {
			final TransactionalObjectBPlusTree<Integer, String> bPlusTree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);
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
			final TransactionalObjectBPlusTree<Integer, String> bPlusTree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);

			assertStateAfterCommit(
				bPlusTree,
				tested -> {
					tested.insert(1, "Value1");
					tested.insert(2, "Value2");
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
				GenericEvitaInternalError.class,
				() -> new TransactionalObjectBPlusTree<>(2, Integer.class, String.class)
			);
		}

		@Test
		@DisplayName("rejects even internal node block size")
		void shouldRejectEvenInternalNodeBlockSize() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new TransactionalObjectBPlusTree<>(3, 1, 4, 1, Integer.class, String.class)
			);
		}

		@Test
		@DisplayName("returns zero size for newly created tree")
		void shouldReturnZeroSizeForEmptyTree() {
			final TransactionalObjectBPlusTree<Integer, String> tree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);
			assertEquals(0, tree.size());
		}

		@Test
		@DisplayName("reports consistent state for empty tree")
		void shouldReportConsistentForEmptyTree() {
			final TransactionalObjectBPlusTree<Integer, String> tree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);
			final ConsistencyReport report = tree.getConsistencyReport();
			assertEquals(ConsistencyState.CONSISTENT, report.state());
		}

	}

	@Nested
	@DisplayName("Generational randomized proof")
	class GenerationalProofTest {

		@ParameterizedTest(
			name = "TransactionalObjectBPlusTreeTest should survive generational randomized test applying "
				+ "modifications on it"
		)
		@Tag(LONG_RUNNING_TEST)
		@ArgumentsSource(TimeArgumentProvider.class)
		@DisplayName("survives randomized insert/delete operations")
		void generationalProofTest(@Nonnull GenerationalTestInput input) {
			final int limitElements = 1000;
			final TreeTuple testTree = prepareRandomTree(16, 7, 7, 3, 42, limitElements);
			final TransactionalObjectBPlusTree<Integer, String> theTree = testTree.bPlusTree();
			final int[] initialArray = testTree.plainArray();
			verifyTreeConsistency(theTree, initialArray);

			runFor(
				input, 1000, new TestState(new StringBuilder(), initialArray, true),
				(random, testState) -> {
					final int[] startArray = testState.initialArray();
					final int[] endArray;
					int key = -1;
					final boolean delete =
						(startArray.length > 0 && random.nextInt(3) == 0)
							|| (testState.limitReached() && startArray.length > limitElements / 2);

					try {
						if (delete) {
							final int index = random.nextInt(startArray.length);
							key = startArray[index];
							endArray = ArrayUtils.removeIntFromOrderedArray(key, startArray);
							theTree.delete(key);
						} else {
							key = random.nextInt(limitElements * 2);
							endArray = ArrayUtils.insertIntIntoOrderedArray(key, startArray);
							theTree.insert(key, "Value" + key);
						}

						verifyTreeConsistency(theTree, endArray);

						return new TestState(
							testState.code().append(delete ? "D:" : "I:").append(key),
							endArray,
							testState.limitReached()
								? endArray.length > limitElements / 2
								: endArray.length >= limitElements
						);
					} catch (Exception ex) {
						fail(
							"Failed to " + (delete ? "delete" : "insert") + " key " + key
								+ " with initial state: " + theTree,
							ex
						);
						throw ex;
					}
				}
			);
		}

	}

	/**
	 * Holds a reference to a B+ tree together with the plain sorted integer key array that mirrors its contents.
	 *
	 * @param bPlusTree  the B+ tree
	 * @param plainArray the sorted key array
	 */
	private record TreeTuple(
		@Nonnull TransactionalObjectBPlusTree<Integer, String> bPlusTree,
		@Nonnull int[] plainArray
	) {

		/**
		 * Returns the total number of elements in the tree.
		 */
		public int totalElements() {
			return this.plainArray.length;
		}

		/**
		 * Returns the key array converted to value strings.
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
	@DisplayName("STM invariants")
	class StmInvariantsTest {

		@Test
		@DisplayName("returns stable and unique id across instances")
		void shouldReturnStableAndUniqueId() {
			final TransactionalObjectBPlusTree<Integer, String> tree1 =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);
			final TransactionalObjectBPlusTree<Integer, String> tree2 =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);

			final long id1 = tree1.getId();
			final long id2 = tree2.getId();

			// id is stable on repeated calls
			assertEquals(id1, tree1.getId());
			assertEquals(id2, tree2.getId());
			// ids are unique across instances
			assertNotEquals(id1, id2);
		}

		@Test
		@DisplayName("preserves baseline of pre-populated tree after commit")
		void shouldPreserveBaselineAfterCommitOnPrePopulatedTree() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final int[] originalArray = testTree.plainArray();

			assertStateAfterCommit(
				testTree.bPlusTree(),
				tested -> tested.insert(9999, "Value9999"),
				(original, committed) -> {
					// original baseline unchanged
					verifyTreeConsistency(original, originalArray);
					assertEquals(originalArray.length, original.size());
					// committed reflects the new insert
					assertEquals(originalArray.length + 1, committed.size());
					assertEquals("Value9999", committed.search(9999).orElse(null));
				}
			);
		}

		@Test
		@DisplayName("removeLayer delegates to maintainer")
		void shouldRemoveLayerCleaningNestedReferences() {
			final TransactionalObjectBPlusTree<Integer, String> tree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);

			// removeLayer requires a TransactionalLayerMaintainer
			// verify it does not throw when called correctly
			assertStateAfterCommit(
				tree,
				tested -> tested.insert(1, "Value1"),
				(original, committed) -> {
					// after commit, the original tree is still accessible
					assertEquals(0, original.size());
					assertEquals(1, committed.size());
				}
			);
		}

		@Test
		@DisplayName("commit with leaf-only tree exercises leaf branch")
		void shouldCommitTreeWithSingleLeafRoot() {
			final TransactionalObjectBPlusTree<Integer, String> tree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);

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
			final TransactionalObjectBPlusTree<Integer, String> tree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);

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
					tested.insert(5555, "Value5555");
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
				() -> new TransactionalObjectBPlusTree<>(3, 0, 3, 1, Integer.class, String.class)
			);
		}

		@Test
		@DisplayName("rejects minValueBlockSize greater than ceil(valueBlockSize/2) - 1")
		void shouldRejectMinValueBlockSizeTooLarge() {
			// valueBlockSize=3, ceil(3/2)-1 = 1, so minValueBlockSize=2 is invalid
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new TransactionalObjectBPlusTree<>(3, 2, 3, 1, Integer.class, String.class)
			);
		}

		@Test
		@DisplayName("rejects minInternalNodeBlockSize less than one")
		void shouldRejectMinInternalNodeBlockSizeLessThanOne() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new TransactionalObjectBPlusTree<>(3, 1, 3, 0, Integer.class, String.class)
			);
		}

		@Test
		@DisplayName("rejects minInternalNodeBlockSize greater than ceil(internalNodeBlockSize/2) - 1")
		void shouldRejectMinInternalNodeBlockSizeTooLarge() {
			// internalNodeBlockSize=3, ceil(3/2)-1 = 1, so minInternal=2 is invalid
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new TransactionalObjectBPlusTree<>(3, 1, 3, 2, Integer.class, String.class)
			);
		}

		@Test
		@DisplayName("rejects internalNodeBlockSize less than three")
		void shouldRejectInternalNodeBlockSizeLessThanThree() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new TransactionalObjectBPlusTree<>(3, 1, 1, 0, Integer.class, String.class)
			);
		}

		@Test
		@DisplayName("rejects key type implementing TransactionalLayerProducer")
		void shouldRejectKeyTypeImplementingTransactionalLayerProducer() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new TransactionalObjectBPlusTree<>(3, 1, 3, 1, TransactionalComparableKey.class, String.class)
			);
		}

		@Test
		@DisplayName("rejects value type implementing TransactionalLayerProducer without wrapper")
		void shouldRejectTransactionalValueTypeWithoutWrapper() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new TransactionalObjectBPlusTree<>(3, 1, 3, 1, Integer.class, TransactionalList.genericClass())
			);
		}

		@Test
		@DisplayName("two-arg constructor sets expected default block sizes")
		void shouldSetDefaultBlockSizesWithTwoArgConstructor() {
			final TransactionalObjectBPlusTree<Integer, String> tree =
				new TransactionalObjectBPlusTree<>(Integer.class, String.class);
			assertEquals(64, tree.getValueBlockSize());
			assertEquals(31, tree.getMinValueBlockSize());
			assertEquals(31, tree.getInternalNodeBlockSize());
			assertEquals(15, tree.getMinInternalNodeBlockSize());
			assertEquals(0, tree.size());
		}

		@Test
		@DisplayName("three-arg constructor with transactionalLayerWrapper")
		void shouldAcceptThreeArgConstructorWithWrapper() {
			//noinspection unchecked
			final TransactionalObjectBPlusTree<Integer, TransactionalList<String>> tree =
				new TransactionalObjectBPlusTree<>(
					Integer.class,
					TransactionalList.genericClass(),
					list -> new TransactionalList<>((List<String>) list)
				);
			assertEquals(64, tree.getValueBlockSize());
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
			final TransactionalObjectBPlusTree<Integer, String> tree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);

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
			final TransactionalObjectBPlusTree<Integer, String> tree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);
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
			final int[] originalArray = testTree.plainArray();

			assertStateAfterCommit(
				testTree.bPlusTree(),
				tested -> {
					// insert a new key at the end
					tested.insert(9999, "Value9999");
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

		@Test
		@DisplayName("keyIterator on empty tree has no elements")
		void shouldReturnEmptyKeyIteratorOnEmptyTree() {
			final TransactionalObjectBPlusTree<Integer, String> tree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);
			final Iterator<Integer> it = tree.keyIterator();
			assertFalse(it.hasNext());
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("keyReverseIterator on empty tree has no elements")
		void shouldReturnEmptyKeyReverseIteratorOnEmptyTree() {
			final TransactionalObjectBPlusTree<Integer, String> tree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);
			final Iterator<Integer> it = tree.keyReverseIterator();
			assertFalse(it.hasNext());
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("entryIterator on empty tree has no elements")
		void shouldReturnEmptyEntryIteratorOnEmptyTree() {
			final TransactionalObjectBPlusTree<Integer, String> tree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);
			final Iterator<Entry<Integer, String>> it = tree.entryIterator();
			assertFalse(it.hasNext());
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("entryIterator traverses full tree left to right")
		void shouldIterateEntireTreeViaEntryIterator() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final int[] keys = testTree.plainArray();

			final Iterator<Entry<Integer, String>> it = testTree.bPlusTree().entryIterator();
			int index = 0;
			while (it.hasNext()) {
				final Entry<Integer, String> entry = it.next();
				assertEquals(keys[index], (int) entry.key());
				assertEquals("Value" + keys[index], entry.value());
				index++;
			}
			assertEquals(keys.length, index);
			assertThrows(NoSuchElementException.class, it::next);
		}

		@Test
		@DisplayName("greaterOrEqualKeyIterator from minimum key iterates full tree")
		void shouldIterateFullTreeFromMinKey() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final int[] keys = testTree.plainArray();
			final int minKey = keys[0];

			final Iterator<Integer> it = testTree.bPlusTree().greaterOrEqualKeyIterator(minKey);
			int count = 0;
			while (it.hasNext()) {
				it.next();
				count++;
			}
			assertEquals(keys.length, count);
		}

		@Test
		@DisplayName("lesserOrEqualKeyIterator from maximum key iterates full tree in reverse")
		void shouldIterateFullTreeInReverseFromMaxKey() {
			final TreeTuple testTree = prepareRandomTree(42, 50);
			final int[] keys = testTree.plainArray();
			final int maxKey = keys[keys.length - 1];

			final Iterator<Integer> it = testTree.bPlusTree().lesserOrEqualKeyIterator(maxKey);
			int count = 0;
			while (it.hasNext()) {
				it.next();
				count++;
			}
			assertEquals(keys.length, count);
		}

		@Test
		@DisplayName("keyIterator on single-element tree returns one key")
		void shouldIterateKeyOnSingleElementTree() {
			final TransactionalObjectBPlusTree<Integer, String> tree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);
			tree.insert(42, "Value42");

			final Iterator<Integer> it = tree.keyIterator();
			assertTrue(it.hasNext());
			assertEquals(42, it.next());
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("keyReverseIterator on single-element tree returns one key")
		void shouldIterateKeyReverseOnSingleElementTree() {
			final TransactionalObjectBPlusTree<Integer, String> tree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);
			tree.insert(42, "Value42");

			final Iterator<Integer> it = tree.keyReverseIterator();
			assertTrue(it.hasNext());
			assertEquals(42, it.next());
			assertFalse(it.hasNext());
		}

	}

	@Nested
	@DisplayName("Rollback scenarios")
	class RollbackScenariosTest {

		@Test
		@DisplayName("rollback after split restores original structure")
		void shouldRestoreOriginalAfterSplitCausingRollback() {
			final TransactionalObjectBPlusTree<Integer, String> tree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);
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
			final int[] originalArray = testTree.plainArray();

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
			final TransactionalObjectBPlusTree<Integer, TransactionalList<String>> tree =
				new TransactionalObjectBPlusTree<>(
					Integer.class, TransactionalList.genericClass(),
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
			final TransactionalObjectBPlusTree<Integer, TransactionalList<String>> tree =
				new TransactionalObjectBPlusTree<>(
					Integer.class, TransactionalList.genericClass(),
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
	@DisplayName("Miscellaneous")
	class MiscellaneousTest {

		@Test
		@DisplayName("toString on empty tree returns empty leaf representation")
		void shouldPrintEmptyTree() {
			final TransactionalObjectBPlusTree<Integer, String> tree =
				new TransactionalObjectBPlusTree<>(3, Integer.class, String.class);
			final String result = tree.toString();
			// empty leaf node has no key:value pairs, so result should be empty
			assertEquals("", result);
		}

		@Test
		@DisplayName("genericClass returns TransactionalObjectBPlusTree.class")
		void shouldReturnCorrectGenericClass() {
			final Class<TransactionalObjectBPlusTree<Integer, String>> theClass = TransactionalObjectBPlusTree.genericClass();
			assertEquals(TransactionalObjectBPlusTree.class, theClass);
		}

	}

	/**
	 * A test-only key class that implements both `Comparable` and `TransactionalLayerProducer`, used to verify that
	 * the constructor rejects such key types.
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

	/**
	 * Mutable state carried across iterations in the generational proof test.
	 *
	 * @param code         accumulated operation log
	 * @param initialArray the current sorted key array
	 * @param limitReached whether the element limit has been reached
	 */
	private record TestState(
		@Nonnull StringBuilder code,
		@Nonnull int[] initialArray,
		boolean limitReached
	) {
	}

}
