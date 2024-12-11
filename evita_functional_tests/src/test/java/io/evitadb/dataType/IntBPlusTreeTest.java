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

import io.evitadb.dataType.IntBPlusTree.BPlusInternalTreeNode;
import io.evitadb.dataType.IntBPlusTree.BPlusLeafTreeNode;
import io.evitadb.dataType.IntBPlusTree.BPlusTreeNode;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator.OfInt;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

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
		assertEquals(1, bPlusTree.size());
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
		assertEquals(2, bPlusTree.size());
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

		assertEquals(4, bPlusTree.size());
		verifyTreeConsistency(bPlusTree, 1, 2, 3, 4);
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

		verifyForwardKeyIterator(testTree.bPlusTree(), testTree.plainArray());
		assertEquals(testTree.totalElements(), testTree.bPlusTree().size());
	}

	@Test
	void shouldIterateThroughLeafNodeKeysFromRightToLeft() {
		final TreeTuple testTree = prepareRandomTree(System.currentTimeMillis(), 100);

		final OfInt it = verifyReverseKeyIterator(testTree.bPlusTree(), testTree.plainArray());
		assertThrows(NoSuchElementException.class, it::nextInt);
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
		final IntBPlusTree<String> bPlusTree = new IntBPlusTree<>(3, String.class);
		int[] keys = new int[0];
		for (int i = 1; i <= 20; i++) {
			bPlusTree.insert(i, "Value" + i);
			keys = ArrayUtils.insertIntIntoOrderedArray(i, keys);
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
	void shouldStayBalancedWhenItemsAreAddedToTheBeginningOnly() {
		final IntBPlusTree<String> bPlusTree = new IntBPlusTree<>(3, String.class);
		int[] keys = new int[0];
		for (int i = 20; i > 0; i--) {
			bPlusTree.insert(i, "Value" + i);
			keys = ArrayUtils.insertIntIntoOrderedArray(i, keys);
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

	@Test
	void shouldStealFromLeftmostNode() {
		IntBPlusTree<String> bPlusTree = new IntBPlusTree<>(3, String.class);
		bPlusTree.insert(15, "Value15");
		bPlusTree.insert(17, "Value17");
		bPlusTree.insert(20, "Value20");  // This should cause a split
		bPlusTree.insert(23, "Value23");
		bPlusTree.insert(25, "Value25");
		bPlusTree.insert(14, "Value14");
		verifyTreeConsistency(bPlusTree, 14, 15, 17, 20, 23, 25);

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
		verifyTreeConsistency(bPlusTree, 14, 15, 20, 23, 25);

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
	void shouldStealFromLeftNode() {
		IntBPlusTree<String> bPlusTree = new IntBPlusTree<>(3, String.class);
		bPlusTree.insert(15, "Value15");
		bPlusTree.insert(17, "Value17");
		bPlusTree.insert(20, "Value20");  // This should cause a split
		bPlusTree.insert(23, "Value23");
		bPlusTree.insert(25, "Value25");
		bPlusTree.insert(14, "Value14");
		bPlusTree.insert(16, "Value16");
		bPlusTree.insert(19, "Value19");
		bPlusTree.insert(18, "Value18");
		bPlusTree.insert(11, "Value11");
		bPlusTree.insert(12, "Value12");
		bPlusTree.insert(10, "Value10");
		verifyTreeConsistency(bPlusTree, 10, 11, 12, 14, 15, 16, 17, 18, 19, 20, 23, 25);

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
		verifyTreeConsistency(bPlusTree, 10, 12, 14, 15, 16, 17, 18, 19, 20, 23, 25);

		bPlusTree.delete(10);
		verifyTreeConsistency(bPlusTree, 12, 14, 15, 16, 17, 18, 19, 20, 23, 25);

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
	void shouldStealFromRightNode() {
		IntBPlusTree<String> bPlusTree = new IntBPlusTree<>(3, String.class);
		bPlusTree.insert(15, "Value15");
		bPlusTree.insert(17, "Value17");
		bPlusTree.insert(20, "Value20");  // This should cause a split
		bPlusTree.insert(23, "Value23");
		bPlusTree.insert(25, "Value25");
		bPlusTree.insert(14, "Value14");
		bPlusTree.insert(16, "Value16");
		bPlusTree.insert(19, "Value19");
		bPlusTree.insert(18, "Value18");
		bPlusTree.insert(11, "Value11");
		bPlusTree.insert(12, "Value12");
		verifyTreeConsistency(bPlusTree, 11, 12, 14, 15, 16, 17, 18, 19, 20, 23, 25);

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

		bPlusTree.delete(12);
		verifyTreeConsistency(bPlusTree, 11, 14, 15, 16, 17, 18, 19, 20, 23, 25);

		bPlusTree.delete(14);
		verifyTreeConsistency(bPlusTree, 11, 15, 16, 17, 18, 19, 20, 23, 25);

		assertEquals(
			"""
			< 17:
			   < 15:
			      11:Value11
			   >=15:
			      15:Value15
			   >=16:
			      16:Value16
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

	private static void verifyTreeConsistency(@Nonnull IntBPlusTree<?> bPlusTree, int... keys) {
		int height = verifyAndReturnHeight(bPlusTree);
		verifyMinimalCountOfValuesInNodes(bPlusTree.getRoot(), bPlusTree.getMinBlockSize());
		verifyInternalNodeKeys(bPlusTree.getRoot());
		verifyPreviousAndNextNodesOnEachLevel(bPlusTree, height);
		verifyForwardKeyIterator(bPlusTree, keys);
		verifyReverseKeyIterator(bPlusTree, keys);
	}

	private static void verifyPreviousAndNextNodesOnEachLevel(@Nonnull IntBPlusTree<?> bPlusTree, int height) {
		for (int i = 0; i <= height; i++) {
			final List<BPlusTreeNode<?>> nodesOnLevel = getNodesOnLevel(bPlusTree, i);
			BPlusTreeNode<?> previousNode = null;
			for (int j = 0; j < nodesOnLevel.size(); j++) {
				final BPlusTreeNode<?> node = nodesOnLevel.get(j);
				assertSame(previousNode, node.getPreviousNode(), "Node " + node + " has a wrong previous node!");
				if (previousNode != null) {
					assertSame(previousNode.getNextNode(), node, "Node " + node + " has a wrong next node!");
					assertSame(previousNode.getClass(), node.getClass(), "Node " + node + " has a different class than the previous node!");
				}
				previousNode = node;
				if (j == nodesOnLevel.size() - 1) {
					assertNull(node.getNextNode(), "Last node on level " + i + " has a next node!");
				}
			}
		}
	}

	private static void verifyInternalNodeKeys(@Nonnull BPlusTreeNode<?> node) {
		if (node instanceof BPlusInternalTreeNode internalNode) {
			final int[] keys = internalNode.getKeys();
			final BPlusTreeNode<?>[] children = internalNode.getChildren();
			if (internalNode.getPeek() >= 0) {
				verifyInternalNodeKeys(children[0]);
			}
			for (int i = 0; i < internalNode.getPeek(); i++) {
				final int key = keys[i];
				final BPlusTreeNode<?> child = children[i + 1];
				if (child instanceof BPlusInternalTreeNode childInternalNode) {
					assertEquals(childInternalNode.getLeftBoundaryKey(), key, "Internal node " + childInternalNode + " has a different left boundary key!");
					verifyInternalNodeKeys(childInternalNode);
				} else if (child instanceof BPlusLeafTreeNode<?> childLeafNode) {
					assertEquals(childLeafNode.getKeys()[0], key, "Leaf node " + childLeafNode + " has a different key than the internal node key!");
				} else {
					fail("Unknown node type: " + child);
				}
			}
		}
	}

	private static void verifyMinimalCountOfValuesInNodes(@Nonnull BPlusTreeNode<?> node, int minBlockSize) {
		if (node instanceof BPlusInternalTreeNode internalNode) {
			assertTrue(internalNode.size() >= minBlockSize, "Internal node " + internalNode + " has less than " + minBlockSize + " values!");
			for (int i = 0; i < internalNode.size(); i++) {
				verifyMinimalCountOfValuesInNodes(internalNode.getChildren()[i], minBlockSize);
			}
		} else {
			assertTrue(node.size() >= minBlockSize, "Leaf node " + node + " has less than " + minBlockSize + " values!");
		}
	}

	private static int verifyAndReturnHeight(@Nonnull IntBPlusTree<?> tree) {
		final BPlusTreeNode<?> root = tree.getRoot();
		if (root instanceof BPlusInternalTreeNode internalNode) {
			final int resultHeight = verifyAndReturnHeight(internalNode, 0);
			for (int i = 0; i <= internalNode.getPeek(); i++) {
				verifyHeightOfAllChildren(internalNode.getChildren()[i], 1, resultHeight);
			}
			return resultHeight;
		} else {
			return 0;
		}
	}

	private static void verifyHeightOfAllChildren(@Nonnull BPlusTreeNode<?> node, int nodeHeight, int maximalHeight) {
		if (node instanceof BPlusInternalTreeNode internalNode) {
			final int childHeight = nodeHeight + 1;
			for (int i = 0; i <= internalNode.getPeek(); i++) {
				verifyHeightOfAllChildren(internalNode.getChildren()[i], childHeight, maximalHeight);
			}
		} else {
			assertEquals(maximalHeight, nodeHeight, "Leaf node " + node + " has a different height than the maximal height!");
		}
	}

	private static int verifyAndReturnHeight(@Nonnull BPlusInternalTreeNode node, int currentHeight) {
		final BPlusTreeNode<?> child = node.getChildren()[0];
		if (child instanceof BPlusInternalTreeNode internalChild) {
			return verifyAndReturnHeight(internalChild, currentHeight + 1);
		} else {
			return currentHeight + 1;
		}
	}

	@Nonnull
	private static List<BPlusTreeNode<?>> getNodesOnLevel(@Nonnull IntBPlusTree<?> tree, int level) {
		if (level == 0) {
			return List.of(tree.getRoot());
		} else {
			final List<BPlusTreeNode<?>> nodes = new ArrayList<>(32);
			addNodesOnLevel(tree.getRoot(), level, 0, nodes);
			return nodes;
		}
	}

	private static void addNodesOnLevel(
		@Nonnull BPlusTreeNode<?> currentNode,
		int targetLevel,
		int currentLevel,
		@Nonnull List<BPlusTreeNode<?>> resultNodes
	) {
		if (currentNode instanceof IntBPlusTree.BPlusInternalTreeNode internalNode) {
			if (currentLevel == targetLevel) {
				resultNodes.add(internalNode);
			} else {
				for (int i = 0; i <= internalNode.getPeek(); i++) {
					addNodesOnLevel(internalNode.getChildren()[i], targetLevel, currentLevel + 1, resultNodes);
				}
			}
		} else if (currentLevel == targetLevel) {
			resultNodes.add(currentNode);
		} else {
			throw new GenericEvitaInternalError("Level " + targetLevel + " not found in the tree!");
		}
	}

	private static void verifyForwardKeyIterator(@Nonnull IntBPlusTree<?> tree, @Nonnull int... expectedArray) {
		int[] reconstructedArray = new int[expectedArray.length];
		int index = 0;
		final OfInt it = tree.keyIterator();
		while (it.hasNext()) {
			reconstructedArray[index++] = it.nextInt();
		}

		assertArrayEquals(expectedArray, reconstructedArray, "Arrays are not equal!");
		assertThrows(NoSuchElementException.class, it::nextInt, "Iterator should be exhausted!");
	}

	@Nonnull
	private static OfInt verifyReverseKeyIterator(@Nonnull IntBPlusTree<?> tree, @Nonnull int... expectedArray) {
		int[] reconstructedArray = new int[expectedArray.length];
		int index = expectedArray.length;
		final OfInt it = tree.keyReverseIterator();
		while (it.hasNext()) {
			reconstructedArray[--index] = it.nextInt();
		}

		assertArrayEquals(expectedArray, reconstructedArray);
		return it;
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