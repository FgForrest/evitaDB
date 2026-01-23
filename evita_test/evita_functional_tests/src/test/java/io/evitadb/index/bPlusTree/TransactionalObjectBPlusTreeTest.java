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
import io.evitadb.index.bPlusTree.TransactionalObjectBPlusTree.Entry;
import io.evitadb.index.list.TransactionalList;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
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
 * This test verifies the correctness of the {@link TransactionalObjectBPlusTree} implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
class TransactionalObjectBPlusTreeTest implements TimeBoundedTestSupport {

	private static void verifyTreeConsistency(@Nonnull TransactionalObjectBPlusTree<Integer, String> bPlusTree, int... expectedArray) {
		final ConsistencyReport consistencyReport = bPlusTree.getConsistencyReport();
		assertEquals(
			ConsistencyState.CONSISTENT, consistencyReport.state(),
			consistencyReport.report()
		);
		verifyForwardValueIterator(bPlusTree, expectedArray);
		verifyReverseValueIterator(bPlusTree, expectedArray);
	}

	private static void verifyForwardValueIterator(@Nonnull TransactionalObjectBPlusTree<Integer, String> tree, @Nonnull int... keyArray) {
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

	private static void verifyReverseValueIterator(@Nonnull TransactionalObjectBPlusTree<Integer, String> tree, @Nonnull int... keyArray) {
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

	private static TreeTuple prepareRandomTree(long seed, int totalElements) {
		return prepareRandomTree(3, 1, 3, 1, seed, totalElements);
	}

	private static TreeTuple prepareRandomTree(
		int valueBlockSize, int minValueBlockSize,
		int internalNodeSize, int minInternalNodeSize,
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

	@Test
	void shouldNotModifyOriginalTreeOnRollback() {
		final TreeTuple testTree = prepareRandomTree(1, 100);

		assertStateAfterRollback(
			testTree.bPlusTree(),
			tested -> {
				// do modifications
				tested.insert(100001, "Value100001");
				for (int i = 0; i < testTree.plainArray().length; i = i + 2) {
					tested.delete(testTree.plainArray()[i]);
				}
			},
			(original, commited) -> {
				verifyTreeConsistency(original, testTree.plainArray());
				verifyForwardValueIterator(original, testTree.plainArray());
				assertEquals(testTree.totalElements(), original.size());
				assertNull(commited);
			}
		);
	}

	@Test
	void shouldOverwriteDuplicateKeys() {
		TransactionalObjectBPlusTree<Integer, String> bPlusTree = new TransactionalObjectBPlusTree<>(
			3, Integer.class, String.class
		);
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
	void shouldPrintVerboseSimpleTree() {
		TransactionalObjectBPlusTree<Integer, String> bPlusTree = new TransactionalObjectBPlusTree<>(
			3, Integer.class, String.class
		);
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
	void shouldSplitNodeWhenFull() {
		TransactionalObjectBPlusTree<Integer, String> bPlusTree = new TransactionalObjectBPlusTree<>(
			3, Integer.class, String.class
		);

		assertStateAfterCommit(
			bPlusTree,
			tested -> {
				tested.insert(1, "Value1");
				tested.insert(2, "Value2");
				tested.insert(3, "Value3");  // This should cause a split
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
	void shouldPrintComplexTree() {
		TransactionalObjectBPlusTree<Integer, String> bPlusTree = new TransactionalObjectBPlusTree<>(
			3, Integer.class, String.class
		);

		assertStateAfterCommit(
			bPlusTree,
			tested -> {
				tested.insert(1, "Value1");
				tested.insert(2, "Value2");
				tested.insert(3, "Value3");  // This should cause a split
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

	@Test
	void shouldIterateThroughLeafNodeKeysFromLeftToRight() {
		final TreeTuple testTree = prepareRandomTree(System.currentTimeMillis(), 100);

		verifyTreeConsistency(testTree.bPlusTree(), testTree.plainArray());
		verifyForwardValueIterator(testTree.bPlusTree(), testTree.plainArray());
		assertEquals(testTree.totalElements(), testTree.bPlusTree().size());
	}

	@Test
	void shouldIterateThroughLeafNodeValuesLeftToRightFromExactPosition() {
		final TreeTuple testTree = prepareRandomTree(42, 100);
		final Iterator<String> it = testTree.bPlusTree().greaterOrEqualValueIterator(40);
		final int[] plainFullArray = testTree.plainArray();
		final InsertionPosition insertionPosition = ArrayUtils.computeInsertPositionOfIntInOrderedArray(40, plainFullArray);

		assertTrue(insertionPosition.alreadyPresent());
		final String[] partialCopy = new String[plainFullArray.length - insertionPosition.position()];
		for (int i = insertionPosition.position(); i < plainFullArray.length; i++) {
			partialCopy[i - insertionPosition.position()] = "Value" + plainFullArray[i];
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
	void shouldIterateThroughLeafNodeValuesLeftToRightFromExactNonExistingPosition() {
		final TreeTuple testTree = prepareRandomTree(42, 100);
		final Iterator<String> it = testTree.bPlusTree().greaterOrEqualValueIterator(39);
		final int[] plainFullArray = testTree.plainArray();
		final InsertionPosition insertionPosition = ArrayUtils.computeInsertPositionOfIntInOrderedArray(39, plainFullArray);

		assertFalse(insertionPosition.alreadyPresent());
		final String[] partialCopy = new String[plainFullArray.length - insertionPosition.position()];
		for (int i = insertionPosition.position(); i < plainFullArray.length; i++) {
			partialCopy[i - insertionPosition.position()] = "Value" + plainFullArray[i];
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
	void shouldFailToIterateValuesLeftToRightThroughNonExistingValues() {
		final TreeTuple testTree = prepareRandomTree(42, 100);
		final Iterator<String> it = testTree.bPlusTree().greaterOrEqualValueIterator(1000);
		assertFalse(it.hasNext());
	}

	@Test
	void shouldIterateThroughLeafNodeKeysLeftToRightFromExactPosition() {
		final TreeTuple testTree = prepareRandomTree(42, 100);
		final Iterator<Integer> it = testTree.bPlusTree().greaterOrEqualKeyIterator(40);
		final int[] plainFullArray = testTree.plainArray();
		final InsertionPosition insertionPosition = ArrayUtils.computeInsertPositionOfIntInOrderedArray(40, plainFullArray);

		assertTrue(insertionPosition.alreadyPresent());
		final Integer[] partialCopy = new Integer[plainFullArray.length - insertionPosition.position()];
		for (int i = insertionPosition.position(); i < plainFullArray.length; i++) {
			partialCopy[i - insertionPosition.position()] = plainFullArray[i];
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
	void shouldFailToIterateEntriesLeftToRightThroughNonExistingValues() {
		final TreeTuple testTree = prepareRandomTree(42, 100);
		final Iterator<Entry<Integer, String>> it = testTree.bPlusTree().greaterOrEqualEntryIterator(1000);
		assertFalse(it.hasNext());
	}

	@Test
	void shouldIterateThroughLeafNodeEntriesLeftToRightFromExactPosition() {
		final TreeTuple testTree = prepareRandomTree(42, 100);
		final Iterator<Entry<Integer, String>> it = testTree.bPlusTree().greaterOrEqualEntryIterator(40);
		final int[] plainFullArray = testTree.plainArray();
		final InsertionPosition insertionPosition = ArrayUtils.computeInsertPositionOfIntInOrderedArray(40, plainFullArray);

		assertTrue(insertionPosition.alreadyPresent());
		final Entry<Integer, String>[] partialCopy = new Entry[plainFullArray.length - insertionPosition.position()];
		for (int i = insertionPosition.position(); i < plainFullArray.length; i++) {
			partialCopy[i - insertionPosition.position()] = new Entry<>(plainFullArray[i], "Value" + plainFullArray[i]);
		}

		final Entry<Integer, String>[] reconstructedArray = new Entry[partialCopy.length];
		int index = 0;
		while (it.hasNext()) {
			reconstructedArray[index++] = it.next();
		}

		assertArrayEquals(partialCopy, reconstructedArray);
		assertThrows(NoSuchElementException.class, it::next);
	}

	@Test
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

	@Test
	void shouldIterateThroughLeafNodeValuesRightToLeftFromExactPosition() {
		final TreeTuple testTree = prepareRandomTree(42, 100);
		final Iterator<String> it = testTree.bPlusTree().lesserOrEqualValueIterator(40);
		final int[] plainFullArray = testTree.plainArray();
		final InsertionPosition insertionPosition = ArrayUtils.computeInsertPositionOfIntInOrderedArray(40, plainFullArray);

		assertTrue(insertionPosition.alreadyPresent());
		final String[] partialCopy = new String[insertionPosition.position() + 1];
		for (int i = insertionPosition.position(); i >= 0; i--) {
			partialCopy[insertionPosition.position() - i] = "Value" + plainFullArray[i];
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
	void shouldIterateThroughLeafNodeValuesRightToLeftFromExactNonExistingPosition() {
		final TreeTuple testTree = prepareRandomTree(42, 100);
		final Iterator<String> it = testTree.bPlusTree().lesserOrEqualValueIterator(39);
		final int[] plainFullArray = testTree.plainArray();
		final InsertionPosition insertionPosition = ArrayUtils.computeInsertPositionOfIntInOrderedArray(39, plainFullArray);

		assertFalse(insertionPosition.alreadyPresent());
		final int thePosition = insertionPosition.alreadyPresent() ? insertionPosition.position() + 1 : insertionPosition.position();
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
	void shouldIterateThroughLeafNodeKeysRightToLeftFromExactPosition() {
		final TreeTuple testTree = prepareRandomTree(42, 100);
		final Iterator<Integer> it = testTree.bPlusTree().lesserOrEqualKeyIterator(40);
		final int[] plainFullArray = testTree.plainArray();
		final InsertionPosition insertionPosition = ArrayUtils.computeInsertPositionOfIntInOrderedArray(40, plainFullArray);

		assertTrue(insertionPosition.alreadyPresent());
		final Integer[] partialCopy = new Integer[insertionPosition.position() + 1];
		for (int i = insertionPosition.position(); i >= 0; i--) {
			partialCopy[insertionPosition.position() - i] = plainFullArray[i];
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
	void shouldFailToIterateEntriesRightToLeftThroughNonExistingValues() {
		final TreeTuple testTree = prepareRandomTree(42, 100);
		final Iterator<Entry<Integer, String>> it = testTree.bPlusTree().lesserOrEqualEntryIterator(-1000);
		assertFalse(it.hasNext());
	}

	@Test
	void shouldIterateThroughLeafNodeEntriesRightToLeftFromExactPosition() {
		final TreeTuple testTree = prepareRandomTree(42, 100);
		final Iterator<Entry<Integer, String>> it = testTree.bPlusTree().lesserOrEqualEntryIterator(40);
		final int[] plainFullArray = testTree.plainArray();
		final InsertionPosition insertionPosition = ArrayUtils.computeInsertPositionOfIntInOrderedArray(40, plainFullArray);

		assertTrue(insertionPosition.alreadyPresent());
		final Entry<Integer, String>[] partialCopy = new Entry[insertionPosition.position() + 1];
		for (int i = insertionPosition.position(); i >= 0; i--) {
			partialCopy[insertionPosition.position() - i] = new Entry<>(plainFullArray[i], "Value" + plainFullArray[i]);
		}

		final Entry<Integer, String>[] reconstructedArray = new Entry[partialCopy.length];
		int index = 0;
		while (it.hasNext()) {
			reconstructedArray[index++] = it.next();
		}

		assertArrayEquals(partialCopy, reconstructedArray);
		assertThrows(NoSuchElementException.class, it::next);
	}

	@Test
	void shouldFailToIterateKeysRightToLeftThroughNonExistingValues() {
		final TreeTuple testTree = prepareRandomTree(42, 100);
		final Iterator<Integer> it = testTree.bPlusTree().lesserOrEqualKeyIterator(-1000);
		assertFalse(it.hasNext());
	}

	@Test
	void shouldIterateThroughLeafNodeKeysFromRightToLeft() {
		final TreeTuple testTree = prepareRandomTree(System.currentTimeMillis(), 100);

		verifyTreeConsistency(testTree.bPlusTree(), testTree.plainArray());
		verifyReverseValueIterator(testTree.bPlusTree(), testTree.plainArray());
		assertEquals(testTree.totalElements(), testTree.bPlusTree().size());
	}

	@Test
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
	void shouldMaintainBalanced() {
		final TransactionalObjectBPlusTree<Integer, String> bPlusTree = new TransactionalObjectBPlusTree<>(
			3, Integer.class, String.class
		);
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
	void shouldStayBalancedWhenItemsAreAddedToTheBeginningOnly() {
		final TransactionalObjectBPlusTree<Integer, String> bPlusTree = new TransactionalObjectBPlusTree<>(
			3, Integer.class, String.class
		);
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

	@Test
	void shouldStealFromLeftmostNode() {
		final TransactionalObjectBPlusTree<Integer, String> bPlusTree = new TransactionalObjectBPlusTree<>(
			3, Integer.class, String.class
		);
		final AtomicReference<TransactionalObjectBPlusTree<Integer, String>> theCommittedTree = new AtomicReference<>();

		assertStateAfterCommit(
			bPlusTree,
			tested -> {
				tested.insert(15, "Value15");
				tested.insert(17, "Value17");
				tested.insert(20, "Value20");  // This should cause a split
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
			tested -> {
				tested.delete(17);
			},
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
	void shouldStealFromRightNode() {
		final TransactionalObjectBPlusTree<Integer, String> bPlusTree = new TransactionalObjectBPlusTree<>(
			3, Integer.class, String.class
		);
		final AtomicReference<TransactionalObjectBPlusTree<Integer, String>> theCommittedTree = new AtomicReference<>();

		assertStateAfterCommit(
			bPlusTree,
			tested -> {
				tested.insert(15, "Value15");
				tested.insert(17, "Value17");
				tested.insert(20, "Value20");  // This should cause a split
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
			tested -> {
				tested.delete(11);
			},
			(original, committed) -> {
				verifyTreeConsistency(committed, 10, 12, 14, 15, 16, 17, 18, 19, 20, 23, 25);
				theCommittedTree.set(committed);
			}
		);

		assertStateAfterCommit(
			theCommittedTree.get(),
			tested -> {
				tested.delete(10);
			},
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
	void shouldStealFromLeftNode() {
		final TransactionalObjectBPlusTree<Integer, String> bPlusTree = new TransactionalObjectBPlusTree<>(
			3, Integer.class, String.class
		);
		final AtomicReference<TransactionalObjectBPlusTree<Integer, String>> theCommittedTree = new AtomicReference<>();

		assertStateAfterCommit(
			bPlusTree,
			tested -> {
				tested.insert(15, "Value15");
				tested.insert(17, "Value17");
				tested.insert(20, "Value20");  // This should cause a split
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
			tested -> {
				tested.delete(15);
			},
			(original, committed) -> {
				verifyTreeConsistency(committed, 11, 12, 14, 16, 17, 18, 19, 20, 23, 25);
				theCommittedTree.set(committed);
			}
		);

		assertStateAfterCommit(
			theCommittedTree.get(),
			tested -> {
				tested.delete(16);
			},
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

	@Test
	void shouldMergeWithLeftNode() {
		final TreeTuple testTree = prepareRandomTree(42, 50);
		final AtomicReference<TransactionalObjectBPlusTree<Integer, String>> theTree = new AtomicReference<>(testTree.bPlusTree());
		final AtomicReference<int[]> expectedArray = new AtomicReference<>(testTree.plainArray());

		assertStateAfterCommit(
			theTree.get(),
			tested -> {
				tested.delete(98);
			},
			(original, committed) -> {
				verifyTreeConsistency(original, expectedArray.get());
				expectedArray.set(ArrayUtils.removeIntFromOrderedArray(98, expectedArray.get()));
				verifyTreeConsistency(committed, expectedArray.get());
				theTree.set(committed);
			}
		);

		assertStateAfterCommit(
			theTree.get(),
			tested -> {
				tested.delete(94);
			},
			(original, committed) -> {
				verifyTreeConsistency(original, expectedArray.get());
				expectedArray.set(ArrayUtils.removeIntFromOrderedArray(94, expectedArray.get()));
				verifyTreeConsistency(committed, expectedArray.get());
			}
		);
	}

	@Test
	void shouldMergeWithRightNode() {
		final TreeTuple testTree = prepareRandomTree(42, 50);
		final AtomicReference<TransactionalObjectBPlusTree<Integer, String>> theTree = new AtomicReference<>(testTree.bPlusTree());
		final AtomicReference<int[]> expectedArray = new AtomicReference<>(testTree.plainArray());

		assertStateAfterCommit(
			theTree.get(),
			tested -> {
				tested.delete(93);
			},
			(original, committed) -> {
				verifyTreeConsistency(original, expectedArray.get());
				expectedArray.set(ArrayUtils.removeIntFromOrderedArray(93, expectedArray.get()));
				verifyTreeConsistency(committed, expectedArray.get());
			}
		);
	}

	@Test
	void shouldMergeCausingIntermediateParentToStealFromLeft() {
		final TreeTuple testTree = prepareRandomTree(42, 50);
		final AtomicReference<TransactionalObjectBPlusTree<Integer, String>> theTree = new AtomicReference<>(testTree.bPlusTree());
		final AtomicReference<int[]> expectedArray = new AtomicReference<>(testTree.plainArray());

		assertStateAfterCommit(
			theTree.get(),
			tested -> {
				tested.delete(34);
			},
			(original, committed) -> {
				verifyTreeConsistency(original, expectedArray.get());
				expectedArray.set(ArrayUtils.removeIntFromOrderedArray(34, expectedArray.get()));
				verifyTreeConsistency(committed, expectedArray.get());
			}
		);
	}

	@Test
	void shouldMergeCausingIntermediateParentToStealFromRight() {
		final TreeTuple testTree = prepareRandomTree(42, 50);
		final AtomicReference<TransactionalObjectBPlusTree<Integer, String>> theTree = new AtomicReference<>(testTree.bPlusTree());
		final AtomicReference<int[]> expectedArray = new AtomicReference<>(testTree.plainArray());

		assertStateAfterCommit(
			theTree.get(),
			tested -> {
				tested.delete(92);
			},
			(original, committed) -> {
				verifyTreeConsistency(original, expectedArray.get());
				expectedArray.set(ArrayUtils.removeIntFromOrderedArray(92, expectedArray.get()));
				verifyTreeConsistency(committed, expectedArray.get());
				theTree.set(committed);
			}
		);

		assertStateAfterCommit(
			theTree.get(),
			tested -> {
				tested.delete(87);
			},
			(original, committed) -> {
				verifyTreeConsistency(original, expectedArray.get());
				expectedArray.set(ArrayUtils.removeIntFromOrderedArray(87, expectedArray.get()));
				verifyTreeConsistency(committed, expectedArray.get());
			}
		);
	}

	@Test
	void shouldMergeCausingIntermediateParentToMergeLeft() {
		final TreeTuple testTree = prepareRandomTree(42, 50);
		final AtomicReference<TransactionalObjectBPlusTree<Integer, String>> theTree = new AtomicReference<>(testTree.bPlusTree());
		final AtomicReference<int[]> expectedArray = new AtomicReference<>(testTree.plainArray());

		assertStateAfterCommit(
			theTree.get(),
			tested -> {
				tested.delete(32);
			},
			(original, committed) -> {
				verifyTreeConsistency(original, expectedArray.get());
				expectedArray.set(ArrayUtils.removeIntFromOrderedArray(32, expectedArray.get()));
				verifyTreeConsistency(committed, expectedArray.get());
				theTree.set(committed);
			}
		);

		assertStateAfterCommit(
			theTree.get(),
			tested -> {
				tested.delete(34);
			},
			(original, committed) -> {
				verifyTreeConsistency(original, expectedArray.get());
				expectedArray.set(ArrayUtils.removeIntFromOrderedArray(34, expectedArray.get()));
				verifyTreeConsistency(committed, expectedArray.get());
				theTree.set(committed);
			}
		);

		assertStateAfterCommit(
			theTree.get(),
			tested -> {
				tested.delete(35);
			},
			(original, committed) -> {
				verifyTreeConsistency(original, expectedArray.get());
				expectedArray.set(ArrayUtils.removeIntFromOrderedArray(35, expectedArray.get()));
				verifyTreeConsistency(committed, expectedArray.get());
				theTree.set(committed);
			}
		);

		assertStateAfterCommit(
			theTree.get(),
			tested -> {
				tested.delete(37);
			},
			(original, committed) -> {
				verifyTreeConsistency(original, expectedArray.get());
				expectedArray.set(ArrayUtils.removeIntFromOrderedArray(37, expectedArray.get()));
				verifyTreeConsistency(committed, expectedArray.get());
				theTree.set(committed);
			}
		);

		assertStateAfterCommit(
			theTree.get(),
			tested -> {
				tested.delete(40);
			},
			(original, committed) -> {
				verifyTreeConsistency(original, expectedArray.get());
				expectedArray.set(ArrayUtils.removeIntFromOrderedArray(40, expectedArray.get()));
				verifyTreeConsistency(committed, expectedArray.get());
				theTree.set(committed);
			}
		);
	}

	@Test
	void shouldMergeCausingIntermediateParentToMergeRight() {
		final TreeTuple testTree = prepareRandomTree(42, 50);
		final AtomicReference<TransactionalObjectBPlusTree<Integer, String>> theTree = new AtomicReference<>(testTree.bPlusTree());
		final AtomicReference<int[]> expectedArray = new AtomicReference<>(testTree.plainArray());

		assertStateAfterCommit(
			theTree.get(),
			tested -> {
				tested.delete(25);
			},
			(original, committed) -> {
				verifyTreeConsistency(original, expectedArray.get());
				expectedArray.set(ArrayUtils.removeIntFromOrderedArray(25, expectedArray.get()));
				verifyTreeConsistency(committed, expectedArray.get());
				theTree.set(committed);
			}
		);

		assertStateAfterCommit(
			theTree.get(),
			tested -> {
				tested.delete(26);
			},
			(original, committed) -> {
				verifyTreeConsistency(original, expectedArray.get());
				expectedArray.set(ArrayUtils.removeIntFromOrderedArray(26, expectedArray.get()));
				verifyTreeConsistency(committed, expectedArray.get());
				theTree.set(committed);
			}
		);

		assertStateAfterCommit(
			theTree.get(),
			tested -> {
				tested.delete(27);
			},
			(original, committed) -> {
				verifyTreeConsistency(original, expectedArray.get());
				expectedArray.set(ArrayUtils.removeIntFromOrderedArray(27, expectedArray.get()));
				verifyTreeConsistency(committed, expectedArray.get());
				theTree.set(committed);
			}
		);

		assertStateAfterCommit(
			theTree.get(),
			tested -> {
				tested.delete(30);
			},
			(original, committed) -> {
				verifyTreeConsistency(original, expectedArray.get());
				expectedArray.set(ArrayUtils.removeIntFromOrderedArray(30, expectedArray.get()));
				verifyTreeConsistency(committed, expectedArray.get());
				theTree.set(committed);
			}
		);
	}

	@Test
	void shouldUpdateExistingValue() {
		final TreeTuple testTree = prepareRandomTree(42, 50);
		final TransactionalObjectBPlusTree<Integer, String> theTree = testTree.bPlusTree();
		int[] expectedArray = testTree.plainArray();

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
	void shouldInsertNonExistingValueViaUpsert() {
		final TreeTuple testTree = prepareRandomTree(42, 50);
		final TransactionalObjectBPlusTree<Integer, String> theTree = testTree.bPlusTree();
		int[] expectedArray = testTree.plainArray();

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

	@Test
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
	void shouldHandleTransactionalLayerProducers() {
		//noinspection unchecked
		TransactionalObjectBPlusTree<Integer, TransactionalList<String>> theTree = new TransactionalObjectBPlusTree<>(
			Integer.class,
			TransactionalList.genericClass(),
			list -> new TransactionalList<>((List<String>) list)
		);
		theTree.insert(1, new TransactionalList<>(List.of("Value1", "Value2")));

		assertStateAfterCommit(
			theTree,
			tested -> {
				tested.search(1).orElseThrow().add("Value3");
			},
			(original, committed) -> {
				assertEquals(2, original.search(1).orElseThrow().size());
				assertEquals(3, committed.search(1).orElseThrow().size());
			}
		);
	}

	@ParameterizedTest(name = "TransactionalObjectBPlusTreeTest should survive generational randomized test applying modifications on it")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int limitElements = 1000;
		final TreeTuple testTree = prepareRandomTree(16, 7, 7, 3, 42, limitElements);
		final TransactionalObjectBPlusTree<Integer, String> theTree = testTree.bPlusTree();
		int[] initialArray = testTree.plainArray();
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
				final boolean delete = (startArray.length > 0 && random.nextInt(3) == 0) ||
					(testState.limitReached() && startArray.length > limitElements / 2);

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
						testState.limitReached() ? endArray.length > limitElements / 2 :  endArray.length >= limitElements
					);
				} catch (Exception ex) {
					fail(
						"Failed to " + (delete ? "delete" : "insert") + " key " + key + " with initial state: " + theTree,
						ex
					);
					throw ex;
				}
			}
		);
	}

	private record TreeTuple(
		@Nonnull TransactionalObjectBPlusTree<Integer, String> bPlusTree,
		@Nonnull int[] plainArray
	) {

		public int totalElements() {
			return this.plainArray.length;
		}

		public String[] asStringArray() {
			final String[] plainArrayAsString = new String[this.plainArray.length];
			for (int i = 0; i < this.plainArray.length; i++) {
				plainArrayAsString[i] = "Value" + this.plainArray[i];
			}
			return plainArrayAsString;
		}

	}

	private record TestState(
		@Nonnull StringBuilder code,
		@Nonnull int[] initialArray,
		boolean limitReached
	) {
	}

}