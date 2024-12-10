/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.dataType;

import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator.OfInt;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * This test verifies the correctness of the {@link IntBPlusTree} implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class IntBPlusTreeTest {

	@Test
	void shouldOverwriteDuplicateKeys() {
		IntBPlusTree<String> bPlusTree = new IntBPlusTree<>(3, String.class);
		bPlusTree.insert(5, "Value5");
		bPlusTree.insert(5, "NewValue5");
		assertEquals("NewValue5", bPlusTree.search(5).orElse(null));
	}

	@Test
	void shouldPrintVerboseSimpleTree() {
		IntBPlusTree<String> bPlusTree = new IntBPlusTree<>(3, String.class);
		bPlusTree.insert(5, "Value5");
		bPlusTree.insert(15, "Value50");
		assertEquals(
			"5:Value5, 15:Value50",
			bPlusTree.toString()
		);
	}

	@Test
	void shouldSplitNodeWhenFull() {
		IntBPlusTree<String> bPlusTree = new IntBPlusTree<>(3, String.class);
		bPlusTree.insert(1, "Value1");
		bPlusTree.insert(2, "Value2");
		bPlusTree.insert(3, "Value3");  // This should cause a split
		bPlusTree.insert(4, "Value4");
		assertEquals("Value1", bPlusTree.search(1).orElse(null));
		assertEquals("Value2", bPlusTree.search(2).orElse(null));
		assertEquals("Value3", bPlusTree.search(3).orElse(null));
        assertEquals("Value4", bPlusTree.search(4).orElse(null));
    }

	@Test
	void shouldPrintComplexTree() {
		IntBPlusTree<String> bPlusTree = new IntBPlusTree<>(3, String.class);
		bPlusTree.insert(1, "Value1");
		bPlusTree.insert(2, "Value2");
		bPlusTree.insert(3, "Value3");  // This should cause a split
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

	@Test
	void shouldIterateThroughLeafNodeKeysFromLeftToRight() {
		final TreeTuple testTree = prepareRandomTree(System.currentTimeMillis(), 100);

		int[] reconstructedArray = new int[testTree.totalElements()];
		int index = 0;
		final OfInt it = testTree.bPlusTree().keyIterator();
		while (it.hasNext()) {
			reconstructedArray[index++] = it.nextInt();
		}

		assertArrayEquals(testTree.plainArray(), reconstructedArray);
		assertThrows(NoSuchElementException.class, it::nextInt);
	}

	@Test
	void shouldIterateThroughLeafNodeKeysFromRightToLeft() {
		final TreeTuple testTree = prepareRandomTree(System.currentTimeMillis(), 100);

		int[] reconstructedArray = new int[testTree.totalElements()];
		int index = testTree.totalElements();
		final OfInt it = testTree.bPlusTree().keyReverseIterator();
		while (it.hasNext()) {
			reconstructedArray[--index] = it.nextInt();
		}

		assertArrayEquals(testTree.plainArray(), reconstructedArray);
		assertThrows(NoSuchElementException.class, it::nextInt);
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
	}

	@Test
	void shouldMaintainBalanced() {
		final IntBPlusTree<String> bPlusTree = new IntBPlusTree<>(3, String.class);
		for (int i = 1; i <= 20; i++) {
			bPlusTree.insert(i, "Value" + i);
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
	}

	@Test
	void shouldStayBalancedWhenItemsAreAddedToTheBeginningOnly() {
		final IntBPlusTree<String> bPlusTree = new IntBPlusTree<>(3, String.class);
		for (int i = 20; i > 0; i--) {
			bPlusTree.insert(i, "Value" + i);
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
	}

	private static TreeTuple prepareRandomTree(long seed, int totalElements) {
		final Random random = new Random(seed);
		final IntBPlusTree<String> bPlusTree = new IntBPlusTree<>(3, String.class);
		int[] plainArray = new int[0];
		do {
			final int i = random.nextInt(totalElements * 2);
			bPlusTree.insert(i, "Value" + i);
			plainArray = ArrayUtils.insertIntIntoOrderedArray(i, plainArray);
		} while (plainArray.length < totalElements);

		return new TreeTuple(bPlusTree, plainArray);
	}

	private record TreeTuple(
		@Nonnull IntBPlusTree<String> bPlusTree,
		@Nonnull int[] plainArray
	) {

		public int totalElements() {
			return plainArray.length;
		}

		public String[] asStringArray() {
			final String[] plainArrayAsString = new String[plainArray.length];
			for (int i = 0; i < plainArray.length; i++) {
				plainArrayAsString[i] = "Value" + plainArray[i];
			}
			return plainArrayAsString;
		}

	}
}