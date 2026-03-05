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
 * This test verifies the correctness of the {@link TransactionalIntBPlusTree} implementation. It covers insert,
 * search, upsert, delete, rebalancing (steal and merge), forward and reverse iteration, transactional semantics,
 * tree visualization, and constructor validation. A generational randomized proof test ensures the tree survives
 * many random operations without losing consistency.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Transactional int B+ tree")
class TransactionalIntBPlusTreeTest implements TimeBoundedTestSupport {

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

	}

	@Nested
	@DisplayName("Forward iteration")
	class ForwardIterationTest {

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
			final InsertionPosition insertionPosition = ArrayUtils.computeInsertPositionOfIntInOrderedArray(40, plainFullArray);

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
			final InsertionPosition insertionPosition = ArrayUtils.computeInsertPositionOfIntInOrderedArray(39, plainFullArray);

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
		void shouldFailToIterateThroughNonExistingValues() {
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

	}

	@Nested
	@DisplayName("Reverse iteration")
	class ReverseIterationTest {

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

	}

	@Nested
	@DisplayName("Tree structure visualization")
	class TreeStructureTest {

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

	}

	@Nested
	@DisplayName("Generational randomized proof")
	class GenerationalProofTest {

		@ParameterizedTest(
			name = "TransactionalIntBPlusTreeTest should survive generational randomized test applying modifications on it"
		)
		@Tag(LONG_RUNNING_TEST)
		@ArgumentsSource(TimeArgumentProvider.class)
		@DisplayName("survives randomized insert/delete operations")
		void generationalProofTest(@Nonnull GenerationalTestInput input) {
			final int limitElements = 1000;
			final TreeTuple testTree = prepareRandomTree(16, 7, 7, 3, 42, limitElements);
			final TransactionalIntBPlusTree<String> theTree = testTree.bPlusTree();
			final int[] initialArray = testTree.plainArray();
			verifyTreeConsistency(theTree, initialArray);

			runFor(
				input,
				1000,
				new TestState(
					new StringBuilder(),
					initialArray,
					true
				),
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
							key = random.nextInt(limitElements << 1);
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

	/**
	 * Holds mutable state used during generational randomized testing including the operation log, current key array,
	 * and whether the element limit has been reached.
	 *
	 * @param code         the operation log builder
	 * @param initialArray the current sorted key array
	 * @param limitReached whether the element limit was reached
	 */
	private record TestState(
		@Nonnull StringBuilder code,
		@Nonnull int[] initialArray,
		boolean limitReached
	) {
	}

}
