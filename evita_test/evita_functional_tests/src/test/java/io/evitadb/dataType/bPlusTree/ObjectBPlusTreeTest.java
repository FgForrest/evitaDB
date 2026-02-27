/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.dataType.bPlusTree;

import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyReport;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
import io.evitadb.exception.GenericEvitaInternalError;
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
import java.util.NoSuchElementException;
import java.util.Random;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies the correctness of the {@link ObjectBPlusTree}
 * implementation. It covers constructor validation, insert, search,
 * upsert, delete, rebalancing (steal and merge), forward and reverse
 * iteration, tree visualization, and a generational randomized proof
 * test ensuring the tree survives many random operations without
 * losing consistency.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@DisplayName("Object B+ tree")
class ObjectBPlusTreeTest implements TimeBoundedTestSupport {

	/**
	 * Verifies tree consistency by checking the internal consistency
	 * report and validating both forward and reverse value iterators
	 * against the expected key array.
	 *
	 * @param bPlusTree     the tree to verify
	 * @param expectedArray the expected sorted key array
	 */
	private static void verifyTreeConsistency(
		@Nonnull ObjectBPlusTree<Integer, String> bPlusTree,
		@Nonnull Integer... expectedArray
	) {
		final ConsistencyReport consistencyReport =
			bPlusTree.getConsistencyReport();
		assertEquals(
			ConsistencyState.CONSISTENT,
			consistencyReport.state(),
			consistencyReport.report()
		);
		verifyForwardValueIterator(bPlusTree, expectedArray);
		verifyReverseValueIterator(bPlusTree, expectedArray);
	}

	/**
	 * Iterates the tree forward and verifies that the values match
	 * the expected key array in ascending order.
	 *
	 * @param tree     the tree to iterate
	 * @param keyArray the expected sorted keys
	 */
	private static void verifyForwardValueIterator(
		@Nonnull ObjectBPlusTree<Integer, String> tree,
		@Nonnull Integer... keyArray
	) {
		final String[] expectedArray = Arrays.stream(keyArray)
			.map(i -> "Value" + i)
			.toArray(String[]::new);
		final String[] reconstructedArray =
			new String[expectedArray.length];
		int index = 0;
		final Iterator<String> it = tree.valueIterator();
		while (it.hasNext()) {
			reconstructedArray[index++] = it.next();
		}

		assertArrayEquals(
			expectedArray, reconstructedArray,
			"Arrays are not equal!"
		);
		assertThrows(
			NoSuchElementException.class, it::next,
			"Iterator should be exhausted!"
		);
	}

	/**
	 * Iterates the tree in reverse and verifies that the values
	 * match the expected key array in descending order.
	 *
	 * @param tree     the tree to iterate
	 * @param keyArray the expected sorted keys
	 */
	private static void verifyReverseValueIterator(
		@Nonnull ObjectBPlusTree<Integer, String> tree,
		@Nonnull Integer... keyArray
	) {
		final String[] expectedArray = Arrays.stream(keyArray)
			.map(i -> "Value" + i)
			.toArray(String[]::new);
		final String[] reconstructedArray =
			new String[expectedArray.length];
		int index = expectedArray.length;
		final Iterator<String> it = tree.valueReverseIterator();
		while (it.hasNext()) {
			reconstructedArray[--index] = it.next();
		}

		assertArrayEquals(
			expectedArray, reconstructedArray,
			"Arrays are not equal!"
		);
		assertThrows(
			NoSuchElementException.class, it::next,
			"Iterator should be exhausted!"
		);
	}

	/**
	 * Creates a random tree with default block sizes (3, 1, 3, 1).
	 *
	 * @param seed          the random seed for reproducibility
	 * @param totalElements the number of distinct elements to insert
	 * @return a tuple containing the tree and a sorted array of keys
	 */
	private static TreeTuple prepareRandomTree(
		long seed,
		int totalElements
	) {
		return prepareRandomTree(
			3, 1, 3, 1, seed, totalElements
		);
	}

	/**
	 * Creates a random tree with custom block sizes.
	 *
	 * @param valueBlockSize     max values per leaf node
	 * @param minValueBlockSize  min values per leaf node
	 * @param internalNodeSize   max keys per internal node
	 * @param minInternalNodeSize min keys per internal node
	 * @param seed               the random seed
	 * @param totalElements      number of distinct elements
	 * @return a tuple containing the tree and a sorted array of keys
	 */
	private static TreeTuple prepareRandomTree(
		int valueBlockSize,
		int minValueBlockSize,
		int internalNodeSize,
		int minInternalNodeSize,
		long seed,
		int totalElements
	) {
		final Random random = new Random(seed);
		final ObjectBPlusTree<Integer, String> bPlusTree =
			new ObjectBPlusTree<>(
				valueBlockSize, minValueBlockSize,
				internalNodeSize, minInternalNodeSize,
				Integer.class, String.class
			);
		Integer[] plainArray = new Integer[0];
		do {
			final Integer i = random.nextInt(totalElements * 2);
			bPlusTree.insert(i, "Value" + i);
			plainArray = ArrayUtils.insertRecordIntoOrderedArray(
				i, plainArray
			);
		} while (plainArray.length < totalElements);

		return new TreeTuple(bPlusTree, plainArray);
	}

	@Nested
	@DisplayName("Constructor validation")
	class ConstructorValidationTest {

		@Test
		@DisplayName("rejects block size smaller than three")
		void shouldRejectBlockSizeSmallerThanThree() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new ObjectBPlusTree<>(
					2, Integer.class, String.class
				)
			);
		}

		@Test
		@DisplayName("rejects even internal node block size")
		void shouldRejectEvenInternalNodeBlockSize() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> new ObjectBPlusTree<>(
					3, 1, 4, 1,
					Integer.class, String.class
				)
			);
		}

		@Test
		@DisplayName("returns zero size for newly created tree")
		void shouldReturnZeroSizeForEmptyTree() {
			final ObjectBPlusTree<Integer, String> tree =
				new ObjectBPlusTree<>(
					3, Integer.class, String.class
				);
			assertEquals(0, tree.size());
		}

		@Test
		@DisplayName("reports consistent state for empty tree")
		void shouldReportConsistentForEmptyTree() {
			final ObjectBPlusTree<Integer, String> tree =
				new ObjectBPlusTree<>(
					3, Integer.class, String.class
				);
			final ConsistencyReport report =
				tree.getConsistencyReport();
			assertEquals(
				ConsistencyState.CONSISTENT,
				report.state(),
				report.report()
			);
		}
	}

	@Nested
	@DisplayName("Insert operations")
	class InsertOperationsTest {

		@Test
		@DisplayName(
			"overwrites value when inserting duplicate key"
		)
		void shouldOverwriteDuplicateKeys() {
			final ObjectBPlusTree<Integer, String> bPlusTree =
				new ObjectBPlusTree<>(
					3, Integer.class, String.class
				);
			bPlusTree.insert(5, "Value5");
			bPlusTree.insert(5, "NewValue5");
			assertEquals(
				"NewValue5",
				bPlusTree.search(5).orElse(null)
			);
			assertEquals(1, bPlusTree.size());
		}

		@Test
		@DisplayName(
			"splits leaf node when capacity is exceeded"
		)
		void shouldSplitNodeWhenFull() {
			final ObjectBPlusTree<Integer, String> bPlusTree =
				new ObjectBPlusTree<>(
					3, Integer.class, String.class
				);
			bPlusTree.insert(1, "Value1");
			bPlusTree.insert(2, "Value2");
			bPlusTree.insert(3, "Value3");
			bPlusTree.insert(4, "Value4");
			assertEquals(
				"Value1",
				bPlusTree.search(1).orElse(null)
			);
			assertEquals(
				"Value2",
				bPlusTree.search(2).orElse(null)
			);
			assertEquals(
				"Value3",
				bPlusTree.search(3).orElse(null)
			);
			assertEquals(
				"Value4",
				bPlusTree.search(4).orElse(null)
			);

			assertEquals(4, bPlusTree.size());
			verifyTreeConsistency(bPlusTree, 1, 2, 3, 4);
		}

		@Test
		@DisplayName(
			"maintains balanced structure after sequential "
				+ "forward insertions"
		)
		void shouldMaintainBalanced() {
			final ObjectBPlusTree<Integer, String> bPlusTree =
				new ObjectBPlusTree<>(
					3, Integer.class, String.class
				);
			Integer[] keys = new Integer[0];
			for (int i = 1; i <= 20; i++) {
				bPlusTree.insert(i, "Value" + i);
				keys = ArrayUtils.insertRecordIntoOrderedArray(
					i, keys
				);
			}
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
				bPlusTree.toString()
			);

			verifyTreeConsistency(bPlusTree, keys);
		}

		@Test
		@DisplayName(
			"maintains balanced structure after sequential "
				+ "reverse insertions"
		)
		void shouldStayBalancedWhenItemsAreAddedToTheBeginningOnly() {
			final ObjectBPlusTree<Integer, String> bPlusTree =
				new ObjectBPlusTree<>(
					3, Integer.class, String.class
				);
			Integer[] keys = new Integer[0];
			for (int i = 20; i > 0; i--) {
				bPlusTree.insert(i, "Value" + i);
				keys = ArrayUtils.insertRecordIntoOrderedArray(
					i, keys
				);
			}
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
				bPlusTree.toString()
			);

			verifyTreeConsistency(bPlusTree, keys);
		}
	}

	@Nested
	@DisplayName("Search operations")
	class SearchOperationsTest {

		@Test
		@DisplayName("returns empty optional on empty tree")
		void shouldReturnEmptyWhenSearchingInEmptyTree() {
			final ObjectBPlusTree<Integer, String> tree =
				new ObjectBPlusTree<>(
					3, Integer.class, String.class
				);
			assertTrue(tree.search(42).isEmpty());
		}

		@Test
		@DisplayName(
			"returns empty optional for non-existent key"
		)
		void shouldReturnEmptyForNonExistentKey() {
			final TreeTuple testTree =
				prepareRandomTree(42, 50);
			assertTrue(
				testTree.bPlusTree().search(99999).isEmpty()
			);
		}

		@Test
		@DisplayName("finds single inserted element")
		void shouldInsertAndSearchSingleElement() {
			final ObjectBPlusTree<Integer, String> tree =
				new ObjectBPlusTree<>(
					3, Integer.class, String.class
				);
			tree.insert(7, "Value7");
			assertEquals(
				"Value7",
				tree.search(7).orElse(null)
			);
			assertEquals(1, tree.size());
		}
	}

	@Nested
	@DisplayName("Upsert operations")
	class UpsertOperationsTest {

		@Test
		@DisplayName("updates value for existing key")
		void shouldUpdateExistingValue() {
			final TreeTuple testTree =
				prepareRandomTree(42, 50);
			final ObjectBPlusTree<Integer, String> theTree =
				testTree.bPlusTree();
			final Integer[] expectedArray =
				testTree.plainArray();

			assertEquals(
				"Value13",
				theTree.search(13).orElse(null)
			);
			theTree.upsert(
				13, existingValue -> "NewValue18"
			);
			assertEquals(
				"NewValue18",
				theTree.search(13).orElse(null)
			);
			theTree.upsert(
				13, existingValue -> "Value13"
			);

			verifyTreeConsistency(theTree, expectedArray);
		}

		@Test
		@DisplayName(
			"inserts new entry for non-existent key"
		)
		void shouldInsertNonExistingValueViaUpsert() {
			final TreeTuple testTree =
				prepareRandomTree(42, 50);
			final ObjectBPlusTree<Integer, String> theTree =
				testTree.bPlusTree();
			final Integer[] expectedArray =
				testTree.plainArray();

			assertNull(theTree.search(100).orElse(null));
			theTree.upsert(
				100, existingValue -> "Value100"
			);
			assertEquals(
				"Value100",
				theTree.search(100).orElse(null)
			);

			verifyTreeConsistency(
				theTree,
				ArrayUtils.insertRecordIntoOrderedArray(
					100, expectedArray
				)
			);
		}
	}

	@Nested
	@DisplayName("Delete operations")
	class DeleteOperationsTest {

		@Test
		@DisplayName(
			"deletes all elements one by one in random order"
		)
		void shouldDeleteEntireContentsOfTheTree() {
			final Random rnd = new Random(42);
			final TreeTuple testTree =
				prepareRandomTree(42, 50);
			final ObjectBPlusTree<Integer, String> theTree =
				testTree.bPlusTree();
			Integer[] expectedArray = testTree.plainArray();

			while (expectedArray.length > 0) {
				final int index =
					rnd.nextInt(expectedArray.length);
				final int key = expectedArray[index];
				theTree.delete(key);
				expectedArray =
					ArrayUtils.removeRecordFromOrderedArray(
						key, expectedArray
					);
				verifyTreeConsistency(theTree, expectedArray);
			}

			assertEquals(0, theTree.size());
		}

		@Test
		@DisplayName(
			"does not change tree when deleting "
				+ "non-existent key"
		)
		void shouldHandleDeleteOfNonExistentKey() {
			final TreeTuple testTree =
				prepareRandomTree(42, 50);
			final ObjectBPlusTree<Integer, String> theTree =
				testTree.bPlusTree();
			final int originalSize = theTree.size();

			theTree.delete(99999);

			assertEquals(originalSize, theTree.size());
			verifyTreeConsistency(
				theTree, testTree.plainArray()
			);
		}

		@Test
		@DisplayName("empties tree after deleting sole element")
		void shouldDeleteSingleElementFromTree() {
			final ObjectBPlusTree<Integer, String> tree =
				new ObjectBPlusTree<>(
					3, Integer.class, String.class
				);
			tree.insert(42, "Value42");
			assertEquals(1, tree.size());

			tree.delete(42);

			assertEquals(0, tree.size());
			assertTrue(tree.search(42).isEmpty());
			verifyTreeConsistency(tree);
		}
	}

	@Nested
	@DisplayName("Steal operations")
	class StealOperationsTest {

		@Test
		@DisplayName(
			"steals from left sibling when node underflows"
		)
		void shouldStealFromLeftmostNode() {
			final ObjectBPlusTree<Integer, String> bPlusTree =
				new ObjectBPlusTree<>(
					3, Integer.class, String.class
				);
			bPlusTree.insert(15, "Value15");
			bPlusTree.insert(17, "Value17");
			bPlusTree.insert(20, "Value20");
			bPlusTree.insert(23, "Value23");
			bPlusTree.insert(25, "Value25");
			bPlusTree.insert(14, "Value14");
			verifyTreeConsistency(
				bPlusTree, 14, 15, 17, 20, 23, 25
			);

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
				bPlusTree.toString()
			);

			bPlusTree.delete(17);
			verifyTreeConsistency(
				bPlusTree, 14, 15, 20, 23, 25
			);

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
				bPlusTree.toString()
			);
		}

		@Test
		@DisplayName(
			"steals from right sibling after "
				+ "multiple deletions"
		)
		void shouldStealFromRightNode() {
			final ObjectBPlusTree<Integer, String> bPlusTree =
				new ObjectBPlusTree<>(
					3, Integer.class, String.class
				);
			bPlusTree.insert(15, "Value15");
			bPlusTree.insert(17, "Value17");
			bPlusTree.insert(20, "Value20");
			bPlusTree.insert(23, "Value23");
			bPlusTree.insert(25, "Value25");
			bPlusTree.insert(14, "Value14");
			bPlusTree.insert(16, "Value16");
			bPlusTree.insert(19, "Value19");
			bPlusTree.insert(18, "Value18");
			bPlusTree.insert(11, "Value11");
			bPlusTree.insert(12, "Value12");
			bPlusTree.insert(10, "Value10");
			verifyTreeConsistency(
				bPlusTree,
				10, 11, 12, 14, 15, 16,
				17, 18, 19, 20, 23, 25
			);

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
				bPlusTree.toString()
			);

			bPlusTree.delete(11);
			verifyTreeConsistency(
				bPlusTree,
				10, 12, 14, 15, 16,
				17, 18, 19, 20, 23, 25
			);

			bPlusTree.delete(10);
			verifyTreeConsistency(
				bPlusTree,
				12, 14, 15, 16,
				17, 18, 19, 20, 23, 25
			);

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
				bPlusTree.toString()
			);
		}

		@Test
		@DisplayName(
			"steals from left sibling after "
				+ "multiple deletions"
		)
		void shouldStealFromLeftNode() {
			final ObjectBPlusTree<Integer, String> bPlusTree =
				new ObjectBPlusTree<>(
					3, Integer.class, String.class
				);
			bPlusTree.insert(15, "Value15");
			bPlusTree.insert(17, "Value17");
			bPlusTree.insert(20, "Value20");
			bPlusTree.insert(23, "Value23");
			bPlusTree.insert(25, "Value25");
			bPlusTree.insert(14, "Value14");
			bPlusTree.insert(16, "Value16");
			bPlusTree.insert(19, "Value19");
			bPlusTree.insert(18, "Value18");
			bPlusTree.insert(11, "Value11");
			bPlusTree.insert(12, "Value12");
			verifyTreeConsistency(
				bPlusTree,
				11, 12, 14, 15, 16,
				17, 18, 19, 20, 23, 25
			);

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
				bPlusTree.toString()
			);

			bPlusTree.delete(15);
			verifyTreeConsistency(
				bPlusTree,
				11, 12, 14, 16,
				17, 18, 19, 20, 23, 25
			);

			bPlusTree.delete(16);
			verifyTreeConsistency(
				bPlusTree,
				11, 12, 14,
				17, 18, 19, 20, 23, 25
			);

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
				bPlusTree.toString()
			);
		}
	}

	@Nested
	@DisplayName("Merge operations")
	class MergeOperationsTest {

		@Test
		@DisplayName("merges with left sibling node")
		void shouldMergeWithLeftNode() {
			final TreeTuple testTree =
				prepareRandomTree(42, 50);
			final ObjectBPlusTree<Integer, String> theTree =
				testTree.bPlusTree();
			Integer[] expectedArray = testTree.plainArray();

			theTree.delete(98);
			expectedArray =
				ArrayUtils.removeRecordFromOrderedArray(
					98, expectedArray
				);

			theTree.delete(94);
			expectedArray =
				ArrayUtils.removeRecordFromOrderedArray(
					94, expectedArray
				);

			verifyTreeConsistency(theTree, expectedArray);
		}

		@Test
		@DisplayName("merges with right sibling node")
		void shouldMergeWithRightNode() {
			final TreeTuple testTree =
				prepareRandomTree(42, 50);
			final ObjectBPlusTree<Integer, String> theTree =
				testTree.bPlusTree();
			Integer[] expectedArray = testTree.plainArray();

			theTree.delete(93);
			expectedArray =
				ArrayUtils.removeRecordFromOrderedArray(
					93, expectedArray
				);

			verifyTreeConsistency(theTree, expectedArray);
		}

		@Test
		@DisplayName(
			"cascades merge causing parent to steal "
				+ "from left"
		)
		void shouldMergeCausingIntermediateParentToStealFromLeft() {
			final TreeTuple testTree =
				prepareRandomTree(42, 50);
			final ObjectBPlusTree<Integer, String> theTree =
				testTree.bPlusTree();
			Integer[] expectedArray = testTree.plainArray();

			theTree.delete(34);
			expectedArray =
				ArrayUtils.removeRecordFromOrderedArray(
					34, expectedArray
				);

			verifyTreeConsistency(theTree, expectedArray);
		}

		@Test
		@DisplayName(
			"cascades merge causing parent to steal "
				+ "from right"
		)
		void shouldMergeCausingIntermediateParentToStealFromRight() {
			final TreeTuple testTree =
				prepareRandomTree(42, 50);
			final ObjectBPlusTree<Integer, String> theTree =
				testTree.bPlusTree();
			Integer[] expectedArray = testTree.plainArray();

			theTree.delete(92);
			expectedArray =
				ArrayUtils.removeRecordFromOrderedArray(
					92, expectedArray
				);

			theTree.delete(87);
			expectedArray =
				ArrayUtils.removeRecordFromOrderedArray(
					87, expectedArray
				);

			verifyTreeConsistency(theTree, expectedArray);
		}

		@Test
		@DisplayName(
			"cascades multiple merges with left parent"
		)
		void shouldMergeCausingIntermediateParentToMergeLeft() {
			final TreeTuple testTree =
				prepareRandomTree(42, 50);
			final ObjectBPlusTree<Integer, String> theTree =
				testTree.bPlusTree();
			Integer[] expectedArray = testTree.plainArray();

			theTree.delete(32);
			expectedArray =
				ArrayUtils.removeRecordFromOrderedArray(
					32, expectedArray
				);

			theTree.delete(34);
			expectedArray =
				ArrayUtils.removeRecordFromOrderedArray(
					34, expectedArray
				);

			theTree.delete(35);
			expectedArray =
				ArrayUtils.removeRecordFromOrderedArray(
					35, expectedArray
				);

			theTree.delete(37);
			expectedArray =
				ArrayUtils.removeRecordFromOrderedArray(
					37, expectedArray
				);

			theTree.delete(40);
			expectedArray =
				ArrayUtils.removeRecordFromOrderedArray(
					40, expectedArray
				);

			verifyTreeConsistency(theTree, expectedArray);
		}

		@Test
		@DisplayName(
			"cascades multiple merges with right parent"
		)
		void shouldMergeCausingIntermediateParentToMergeRight() {
			final TreeTuple testTree =
				prepareRandomTree(42, 50);
			final ObjectBPlusTree<Integer, String> theTree =
				testTree.bPlusTree();
			Integer[] expectedArray = testTree.plainArray();

			theTree.delete(25);
			expectedArray =
				ArrayUtils.removeRecordFromOrderedArray(
					25, expectedArray
				);

			theTree.delete(26);
			expectedArray =
				ArrayUtils.removeRecordFromOrderedArray(
					26, expectedArray
				);

			theTree.delete(27);
			expectedArray =
				ArrayUtils.removeRecordFromOrderedArray(
					27, expectedArray
				);

			theTree.delete(30);
			expectedArray =
				ArrayUtils.removeRecordFromOrderedArray(
					30, expectedArray
				);

			verifyTreeConsistency(theTree, expectedArray);
		}
	}

	@Nested
	@DisplayName("Forward iteration")
	class ForwardIterationTest {

		@Test
		@DisplayName("iterates all keys in ascending order")
		void shouldIterateThroughLeafNodeKeysFromLeftToRight() {
			final TreeTuple testTree =
				prepareRandomTree(42, 100);

			final ConsistencyReport consistencyReport =
				testTree.bPlusTree().getConsistencyReport();
			assertEquals(
				ConsistencyState.CONSISTENT,
				consistencyReport.state(),
				consistencyReport.report()
			);
			verifyForwardValueIterator(
				testTree.bPlusTree(), testTree.plainArray()
			);
			assertEquals(
				testTree.totalElements(),
				testTree.bPlusTree().size()
			);
		}

		@Test
		@DisplayName(
			"iterates all values in ascending key order"
		)
		void shouldIterateThroughLeafNodeValuesLeftToRight() {
			final TreeTuple testTree =
				prepareRandomTree(
					System.currentTimeMillis(), 100
				);

			final String[] reconstructedArray =
				new String[testTree.totalElements()];
			int index = 0;
			final Iterator<String> it =
				testTree.bPlusTree().valueIterator();
			while (it.hasNext()) {
				reconstructedArray[index++] = it.next();
			}

			assertArrayEquals(
				testTree.asStringArray(),
				reconstructedArray
			);
			assertThrows(
				NoSuchElementException.class, it::next
			);
			assertEquals(
				testTree.totalElements(),
				testTree.bPlusTree().size()
			);
		}

		@Test
		@DisplayName(
			"iterates values from exact existing key"
		)
		void shouldIterateThroughLeafNodeValuesLeftToRightFromExactPosition() {
			final TreeTuple testTree =
				prepareRandomTree(42, 100);
			final Iterator<String> it =
				testTree.bPlusTree()
					.greaterOrEqualValueIterator(40);
			final Integer[] plainFullArray =
				testTree.plainArray();
			final InsertionPosition insertionPosition =
				ArrayUtils
					.computeInsertPositionOfObjInOrderedArray(
						40, plainFullArray
					);

			assertTrue(insertionPosition.alreadyPresent());
			final String[] partialCopy = new String[
				plainFullArray.length
					- insertionPosition.position()
			];
			for (int i = insertionPosition.position();
				i < plainFullArray.length; i++) {
				partialCopy[
					i - insertionPosition.position()
				] = "Value" + plainFullArray[i];
			}

			final String[] reconstructedArray =
				new String[partialCopy.length];
			int index = 0;
			while (it.hasNext()) {
				reconstructedArray[index++] = it.next();
			}

			assertArrayEquals(
				partialCopy, reconstructedArray
			);
			assertThrows(
				NoSuchElementException.class, it::next
			);
		}

		@Test
		@DisplayName(
			"iterates values from non-existing key position"
		)
		void shouldIterateThroughLeafNodeValuesLeftToRightFromExactNonExistingPosition() {
			final TreeTuple testTree =
				prepareRandomTree(42, 100);
			final Iterator<String> it =
				testTree.bPlusTree()
					.greaterOrEqualValueIterator(39);
			final Integer[] plainFullArray =
				testTree.plainArray();
			final InsertionPosition insertionPosition =
				ArrayUtils
					.computeInsertPositionOfObjInOrderedArray(
						39, plainFullArray
					);

			assertFalse(insertionPosition.alreadyPresent());
			final String[] partialCopy = new String[
				plainFullArray.length
					- insertionPosition.position()
			];
			for (int i = insertionPosition.position();
				i < plainFullArray.length; i++) {
				partialCopy[
					i - insertionPosition.position()
				] = "Value" + plainFullArray[i];
			}

			final String[] reconstructedArray =
				new String[partialCopy.length];
			int index = 0;
			while (it.hasNext()) {
				reconstructedArray[index++] = it.next();
			}

			assertArrayEquals(
				partialCopy, reconstructedArray
			);
			assertThrows(
				NoSuchElementException.class, it::next
			);
		}

		@Test
		@DisplayName(
			"returns empty iterator when start key "
				+ "exceeds maximum"
		)
		void shouldFailToIterateThroughNonExistingValues() {
			final TreeTuple testTree =
				prepareRandomTree(42, 100);
			final Iterator<String> it =
				testTree.bPlusTree()
					.greaterOrEqualValueIterator(1000);
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName(
			"returns empty iterator on empty tree"
		)
		void shouldIterateForwardOnEmptyTree() {
			final ObjectBPlusTree<Integer, String> tree =
				new ObjectBPlusTree<>(
					3, Integer.class, String.class
				);
			assertFalse(tree.valueIterator().hasNext());
		}

		@Test
		@DisplayName("iterates single element tree")
		void shouldIterateForwardOnSingleElementTree() {
			final ObjectBPlusTree<Integer, String> tree =
				new ObjectBPlusTree<>(
					3, Integer.class, String.class
				);
			tree.insert(5, "Value5");

			final Iterator<String> it = tree.valueIterator();
			assertTrue(it.hasNext());
			assertEquals("Value5", it.next());
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName(
			"iterates from tree's minimum key inclusive"
		)
		void shouldIterateFromFirstKey() {
			final TreeTuple testTree =
				prepareRandomTree(42, 50);
			final Integer[] keys = testTree.plainArray();
			final int firstKey = keys[0];

			final Iterator<String> it =
				testTree.bPlusTree()
					.greaterOrEqualValueIterator(firstKey);

			int count = 0;
			while (it.hasNext()) {
				it.next();
				count++;
			}
			assertEquals(keys.length, count);
		}

		@Test
		@DisplayName(
			"iterates from tree's maximum key returning "
				+ "one element"
		)
		void shouldIterateFromLastKey() {
			final TreeTuple testTree =
				prepareRandomTree(42, 50);
			final Integer[] keys = testTree.plainArray();
			final int lastKey = keys[keys.length - 1];

			final Iterator<String> it =
				testTree.bPlusTree()
					.greaterOrEqualValueIterator(lastKey);

			assertTrue(it.hasNext());
			assertEquals("Value" + lastKey, it.next());
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName(
			"iterates all elements when start key is "
				+ "below minimum"
		)
		void shouldIterateFromBelowMinimumKey() {
			final TreeTuple testTree =
				prepareRandomTree(42, 50);
			final Integer[] keys = testTree.plainArray();

			final Iterator<String> it =
				testTree.bPlusTree()
					.greaterOrEqualValueIterator(
						Integer.MIN_VALUE
					);

			int count = 0;
			while (it.hasNext()) {
				it.next();
				count++;
			}
			assertEquals(keys.length, count);
		}

		@Test
		@DisplayName(
			"key iterator on empty tree has no elements"
		)
		void shouldIterateKeysForwardOnEmptyTree() {
			final ObjectBPlusTree<Integer, String> tree =
				new ObjectBPlusTree<>(
					3, Integer.class, String.class
				);
			final Iterator<Integer> it = tree.keyIterator();
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName(
			"throws on exhausted forward key iterator"
		)
		void shouldThrowNoSuchElementOnExhaustedKeyIterator() {
			final ObjectBPlusTree<Integer, String> tree =
				new ObjectBPlusTree<>(
					3, Integer.class, String.class
				);
			tree.insert(1, "Value1");

			final Iterator<Integer> it = tree.keyIterator();
			assertTrue(it.hasNext());
			assertEquals(1, it.next());
			assertFalse(it.hasNext());
			assertThrows(
				NoSuchElementException.class, it::next
			);
		}
	}

	@Nested
	@DisplayName("Reverse iteration")
	class ReverseIterationTest {

		@Test
		@DisplayName(
			"iterates all keys in descending order"
		)
		void shouldIterateThroughLeafNodeKeysFromRightToLeft() {
			final TreeTuple testTree =
				prepareRandomTree(
					System.currentTimeMillis(), 100
				);

			final ConsistencyReport consistencyReport =
				testTree.bPlusTree().getConsistencyReport();
			assertEquals(
				ConsistencyState.CONSISTENT,
				consistencyReport.state(),
				consistencyReport.report()
			);
			verifyReverseValueIterator(
				testTree.bPlusTree(), testTree.plainArray()
			);
			assertEquals(
				testTree.totalElements(),
				testTree.bPlusTree().size()
			);
		}

		@Test
		@DisplayName(
			"iterates all values in descending key order"
		)
		void shouldIterateThroughLeafNodeValuesRightToLeft() {
			final TreeTuple testTree =
				prepareRandomTree(
					System.currentTimeMillis(), 100
				);

			final String[] reconstructedArray =
				new String[testTree.totalElements()];
			int index = testTree.totalElements();
			final Iterator<String> it =
				testTree.bPlusTree().valueReverseIterator();
			while (it.hasNext()) {
				reconstructedArray[--index] = it.next();
			}

			assertArrayEquals(
				testTree.asStringArray(),
				reconstructedArray
			);
			assertThrows(
				NoSuchElementException.class, it::next
			);
			assertEquals(
				testTree.totalElements(),
				testTree.bPlusTree().size()
			);
		}

		@Test
		@DisplayName(
			"returns empty reverse iterator on empty tree"
		)
		void shouldIterateReverseOnEmptyTree() {
			final ObjectBPlusTree<Integer, String> tree =
				new ObjectBPlusTree<>(
					3, Integer.class, String.class
				);
			assertFalse(
				tree.valueReverseIterator().hasNext()
			);
		}

		@Test
		@DisplayName(
			"reverse iterates single element tree"
		)
		void shouldIterateReverseOnSingleElementTree() {
			final ObjectBPlusTree<Integer, String> tree =
				new ObjectBPlusTree<>(
					3, Integer.class, String.class
				);
			tree.insert(5, "Value5");

			final Iterator<String> it =
				tree.valueReverseIterator();
			assertTrue(it.hasNext());
			assertEquals("Value5", it.next());
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName(
			"reverse key iterator on empty tree "
				+ "has no elements"
		)
		void shouldIterateKeysReverseOnEmptyTree() {
			final ObjectBPlusTree<Integer, String> tree =
				new ObjectBPlusTree<>(
					3, Integer.class, String.class
				);
			final Iterator<Integer> it =
				tree.keyReverseIterator();
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName(
			"throws on exhausted reverse key iterator"
		)
		void shouldThrowNoSuchElementOnExhaustedReverseKeyIterator() {
			final ObjectBPlusTree<Integer, String> tree =
				new ObjectBPlusTree<>(
					3, Integer.class, String.class
				);
			tree.insert(1, "Value1");

			final Iterator<Integer> it =
				tree.keyReverseIterator();
			assertTrue(it.hasNext());
			assertEquals(1, it.next());
			assertFalse(it.hasNext());
			assertThrows(
				NoSuchElementException.class, it::next
			);
		}
	}

	@Nested
	@DisplayName("Tree structure")
	class TreeStructureTest {

		@Test
		@DisplayName("prints simple two-element tree")
		void shouldPrintVerboseSimpleTree() {
			final ObjectBPlusTree<Integer, String> bPlusTree =
				new ObjectBPlusTree<>(
					3, Integer.class, String.class
				);
			bPlusTree.insert(5, "Value5");
			bPlusTree.insert(15, "Value50");
			assertEquals(
				"5:Value5, 15:Value50",
				bPlusTree.toString()
			);
			assertEquals(2, bPlusTree.size());
		}

		@Test
		@DisplayName("prints multi-level tree structure")
		void shouldPrintComplexTree() {
			final ObjectBPlusTree<Integer, String> bPlusTree =
				new ObjectBPlusTree<>(
					3, Integer.class, String.class
				);
			bPlusTree.insert(1, "Value1");
			bPlusTree.insert(2, "Value2");
			bPlusTree.insert(3, "Value3");
			bPlusTree.insert(4, "Value4");
			assertEquals(
				"""
					< 2:
					   1:Value1
					>=2:
					   2:Value2
					>=3:
					   3:Value3, 4:Value4""",
				bPlusTree.toString()
			);
		}
	}

	@Nested
	@DisplayName("Non-Integer key type support")
	class NonIntegerKeyTypeTest {

		@Test
		@DisplayName("handles String keys through insert, split, delete, and consolidation")
		void shouldWorkWithStringKeys() {
			final ObjectBPlusTree<String, String> bPlusTree =
				new ObjectBPlusTree<>(
					3, String.class, String.class
				);
			bPlusTree.insert("apple", "ValueApple");
			bPlusTree.insert("banana", "ValueBanana");
			bPlusTree.insert("cherry", "ValueCherry");
			bPlusTree.insert("date", "ValueDate");
			bPlusTree.insert("elderberry", "ValueElderberry");
			bPlusTree.insert("fig", "ValueFig");
			bPlusTree.insert("grape", "ValueGrape");

			final ConsistencyReport reportAfterInserts =
				bPlusTree.getConsistencyReport();
			assertEquals(
				ConsistencyState.CONSISTENT,
				reportAfterInserts.state(),
				reportAfterInserts.report()
			);
			assertEquals(7, bPlusTree.size());

			// delete to trigger consolidation (steal/merge)
			bPlusTree.delete("banana");
			bPlusTree.delete("cherry");
			bPlusTree.delete("date");

			final ConsistencyReport reportAfterDeletes =
				bPlusTree.getConsistencyReport();
			assertEquals(
				ConsistencyState.CONSISTENT,
				reportAfterDeletes.state(),
				reportAfterDeletes.report()
			);
			assertEquals(4, bPlusTree.size());
			assertTrue(bPlusTree.search("apple").isPresent());
			assertTrue(bPlusTree.search("elderberry").isPresent());
			assertTrue(bPlusTree.search("fig").isPresent());
			assertTrue(bPlusTree.search("grape").isPresent());
			assertFalse(bPlusTree.search("banana").isPresent());
			assertFalse(bPlusTree.search("cherry").isPresent());
			assertFalse(bPlusTree.search("date").isPresent());
		}

		@Test
		@DisplayName("handles Long keys through insert, split, delete, and consolidation")
		void shouldWorkWithLongKeys() {
			final ObjectBPlusTree<Long, String> bPlusTree =
				new ObjectBPlusTree<>(
					3, Long.class, String.class
				);
			for (long i = 1; i <= 20; i++) {
				bPlusTree.insert(i, "Value" + i);
			}

			final ConsistencyReport reportAfterInserts =
				bPlusTree.getConsistencyReport();
			assertEquals(
				ConsistencyState.CONSISTENT,
				reportAfterInserts.state(),
				reportAfterInserts.report()
			);
			assertEquals(20, bPlusTree.size());

			// delete elements to trigger consolidation
			for (long i = 1; i <= 15; i++) {
				bPlusTree.delete(i);
			}

			final ConsistencyReport reportAfterDeletes =
				bPlusTree.getConsistencyReport();
			assertEquals(
				ConsistencyState.CONSISTENT,
				reportAfterDeletes.state(),
				reportAfterDeletes.report()
			);
			assertEquals(5, bPlusTree.size());
			for (long i = 16; i <= 20; i++) {
				assertTrue(
					bPlusTree.search(i).isPresent(),
					"Key " + i + " should be present"
				);
			}
		}
	}

	@Nested
	@DisplayName("Generational proof test")
	class GenerationalProofTestClass {

		@ParameterizedTest(
			name = "ObjectBPlusTreeTest should survive "
				+ "generational randomized test applying "
				+ "modifications on it"
		)
		@Tag(LONG_RUNNING_TEST)
		@ArgumentsSource(TimeArgumentProvider.class)
		@DisplayName(
			"survives randomized insert/delete operations"
		)
		void generationalProofTest(
			GenerationalTestInput input
		) {
			final int limitElements = 1000;
			final TreeTuple testTree = prepareRandomTree(
				16, 7, 7, 3, 42, limitElements
			);
			final ObjectBPlusTree<Integer, String> theTree =
				testTree.bPlusTree();
			final Integer[] initialArray =
				testTree.plainArray();
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
					final Integer[] startArray =
						testState.initialArray();
					final Integer[] endArray;
					int key = -1;
					final boolean delete =
						(startArray.length > 0
							&& random.nextInt(3) == 0)
						|| (testState.limitReached()
							&& startArray.length
								> limitElements / 2);

					try {
						if (delete) {
							final int index = random.nextInt(
								startArray.length
							);
							key = startArray[index];
							endArray =
								ArrayUtils
									.removeRecordFromOrderedArray(
										key, startArray
									);
							theTree.delete(key);
						} else {
							key = random.nextInt(
								limitElements * 2
							);
							endArray =
								ArrayUtils
									.insertRecordIntoOrderedArray(
										key, startArray
									);
							theTree.insert(
								key, "Value" + key
							);
						}

						verifyTreeConsistency(
							theTree, endArray
						);

						return new TestState(
							testState.code()
								.append(
									delete ? "D:" : "I:"
								)
								.append(key),
							endArray,
							testState.limitReached()
								? endArray.length
									> limitElements / 2
								: endArray.length
									>= limitElements
						);
					} catch (Exception ex) {
						fail(
							"Failed to "
								+ (delete
									? "delete"
									: "insert")
								+ " key " + key
								+ " with initial state: "
								+ theTree,
							ex
						);
						throw ex;
					}
				}
			);
		}
	}

	/**
	 * Holds a tree instance together with a sorted array of its
	 * keys for verification purposes.
	 *
	 * @param bPlusTree  the B+ tree instance
	 * @param plainArray sorted array of all keys in the tree
	 */
	private record TreeTuple(
		@Nonnull ObjectBPlusTree<Integer, String> bPlusTree,
		@Nonnull Integer[] plainArray
	) {

		/**
		 * Returns the total number of elements in the tree.
		 */
		public int totalElements() {
			return this.plainArray.length;
		}

		/**
		 * Converts the key array to a string array where each
		 * element is "Value" + key.
		 */
		public String[] asStringArray() {
			final String[] plainArrayAsString =
				new String[this.plainArray.length];
			for (int i = 0; i < this.plainArray.length; i++) {
				plainArrayAsString[i] =
					"Value" + this.plainArray[i];
			}
			return plainArrayAsString;
		}

	}

	/**
	 * Tracks the state of a generational proof test iteration.
	 *
	 * @param code         log of operations performed
	 * @param initialArray current sorted key array
	 * @param limitReached whether the element limit has been hit
	 */
	private record TestState(
		@Nonnull StringBuilder code,
		@Nonnull Integer[] initialArray,
		boolean limitReached
	) {
	}

}
