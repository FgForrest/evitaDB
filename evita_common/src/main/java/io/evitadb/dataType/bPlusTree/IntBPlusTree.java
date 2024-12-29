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

package io.evitadb.dataType.bPlusTree;


import io.evitadb.dataType.ConsistencySensitiveDataStructure;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.PrimitiveIterator.OfInt;

import static io.evitadb.utils.ArrayUtils.*;

/**
 * Represents a B+ Tree data structure specifically designed for integer keys and generic values.
 * The tree is balanced and allows for efficient insertion, deletion, and search operations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@NotThreadSafe
public class IntBPlusTree<V> implements ConsistencySensitiveDataStructure {
	/**
	 * Maximum number of keys = values per leaf node. Use odd number. The number of keys in internal nodes is one less.
	 */
	@Getter private final int valueBlockSize;
	/**
	 * Minimum number of keys = values per leaf node. Controls branching factor for leaf nodes.
	 */
	@Getter private final int minValueBlockSize;
	/**
	 * Maximum number of keys per leaf node. Use odd number. The number of children in internal nodes is one more.
	 */
	@Getter private final int internalNodeBlockSize;
	/**
	 * Minimum number of keys per internal node. Controls branching factor for internal nodes.
	 */
	@Getter private final int minInternalNodeBlockSize;
	/**
	 * The type of the values stored in the tree.
	 */
	@Getter private final Class<V> valueType;
	/**
	 * Number of elements in the tree.
	 */
	private int size;
	/**
	 * Root node of the tree.
	 */
	@Getter(AccessLevel.PACKAGE)
	private BPlusTreeNode<?> root;

	/**
	 * Rewires the sibling pointers of BPlusTreeNodes involved in a split operation.
	 *
	 * @param node       The original node that is being replaced during the sibling rewiring process.
	 * @param leftNode   The new left sibling node resulting from the split operation.
	 * @param rightRight The new right sibling node resulting from the split operation.
	 */
	private static <N extends BPlusTreeNode<N>> void rewireSiblings(
		@Nonnull N node,
		@Nonnull N leftNode,
		@Nonnull N rightRight
	) {
		final N previousNode = node.getPreviousNode();
		if (previousNode != null) {
			previousNode.setNextNode(leftNode);
		}
		leftNode.setPreviousNode(previousNode);
		leftNode.setNextNode(rightRight);
		rightRight.setPreviousNode(leftNode);
		final N nextNode = node.getNextNode();
		if (nextNode != null) {
			nextNode.setPreviousNode(rightRight);
		}
		rightRight.setNextNode(nextNode);
	}

	/**
	 * Updates the keys in the parent nodes of a B+ Tree based on changes in a specific path.
	 * This method propagates changes up the tree as necessary.
	 *
	 * @param path                  A list of B+ internal tree nodes representing the path from the root to the updated node.
	 * @param indexToUpdate         The index of the key to be updated in the parent node.
	 * @param previouslyUpdatedNode The child node that was previously updated and requires the parent node key to be aligned.
	 */
	private static void updateParentKeys(
		@Nonnull List<BPlusInternalTreeNode> path,
		int indexToUpdate,
		@Nonnull BPlusTreeNode<?> previouslyUpdatedNode,
		int watermark
	) {
		// first child doesn't have a key in the parent
		for (int i = path.size() - 1 - watermark; i >= 0; i--) {
			BPlusInternalTreeNode immediateParent = path.get(i);
			if (indexToUpdate > 0) {
				immediateParent.updateKeyForNode(indexToUpdate, previouslyUpdatedNode);
			}
			previouslyUpdatedNode = immediateParent;
			indexToUpdate = i > 0 ? path.get(i - 1).getChildIndex(immediateParent.getLeftBoundaryKey(), immediateParent) : 0;
		}
	}

	/**
	 * Verifies that the height of all tree branches is the same and returns the height of the tree. The B+ tree needs
	 * to be balanced to achieve O(log n) complexity for search operations.
	 *
	 * @param tree the B+ tree to verify
	 * @return the height of the tree
	 */
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

	/**
	 * Verifies that all children of the given BPlusTreeNode have the correct height.
	 * For internal nodes, it recursively verifies the height of their child nodes.
	 * For leaf nodes, it checks if their height matches the maximal height.
	 *
	 * @param node          the BPlusTreeNode whose children are being verified, must not be null
	 * @param nodeHeight    the height of the current node
	 * @param maximalHeight the maximal height value that should be matched by leaf nodes
	 */
	private static void verifyHeightOfAllChildren(@Nonnull BPlusTreeNode<?> node, int nodeHeight, int maximalHeight) {
		if (node instanceof BPlusInternalTreeNode internalNode) {
			final int childHeight = nodeHeight + 1;
			for (int i = 0; i < internalNode.size(); i++) {
				verifyHeightOfAllChildren(internalNode.getChildren()[i], childHeight, maximalHeight);
			}
		} else {
			if (maximalHeight != nodeHeight) {
				throw new IllegalStateException(
					"Leaf node " + node + " has a different height (" + nodeHeight + ") " +
						"than the maximal height (" + maximalHeight + ")!"
				);
			}
		}
	}

	/**
	 * Verifies and calculates the height of a B+ tree starting from the given internal node.
	 *
	 * @param node          the internal node of the B+ tree to start height calculation from; must not be null
	 * @param currentHeight the current height accumulated in the recursive process
	 * @return the height of the B+ tree from the given node
	 */
	private static int verifyAndReturnHeight(@Nonnull BPlusInternalTreeNode node, int currentHeight) {
		final BPlusTreeNode<?> child = node.getChildren()[0];
		if (child instanceof BPlusInternalTreeNode internalChild) {
			return verifyAndReturnHeight(internalChild, currentHeight + 1);
		} else {
			return currentHeight + 1;
		}
	}

	/**
	 * Verifies that the keys in the internal nodes of a B+ tree are consistent with the keys of their child nodes.
	 * This method performs recursive checks to ensure the integrity of the structure of the B+ tree.
	 *
	 * @param node the B+ tree node to verify; should not be null. This can be an internal node or a leaf node.
	 *             If the node is an internal node, its key consistency with its child nodes will be validated.
	 *             For leaf nodes, no recursive checks are performed.
	 * @throws IllegalStateException if any inconsistency is detected in the keys of the internal or leaf nodes.
	 */
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
					if (childInternalNode.getLeftBoundaryKey() != key) {
						throw new IllegalStateException(
							"Internal node " + childInternalNode + " has a different left boundary key (" +
								childInternalNode.getLeftBoundaryKey() + ") than the internal node key (" + key + ")!"
						);
					}
					verifyInternalNodeKeys(childInternalNode);
				} else if (child instanceof BPlusLeafTreeNode<?> childLeafNode) {
					if (childLeafNode.getKeys()[0] != key) {
						throw new IllegalStateException(
							"Leaf node " + childLeafNode + " has a different key (" + childLeafNode.getKeys()[0] + ") " +
								"than the internal node key (" + key + ")!"
						);
					}
				} else {
					throw new IllegalStateException("Unknown node type: " + child);
				}
			}
		}
	}

	/**
	 * Verifies the previous and next node links for nodes at each level of a given B+ tree.
	 * Ensures that the nodes on the same level are properly linked and validates the integrity
	 * of the previous and next node references.
	 *
	 * @param bPlusTree the B+ tree whose node links on each level are to be verified
	 * @param height    the height of the B+ tree, indicating how many levels need to be checked
	 * @throws IllegalStateException if any node fails validation for previous or next node links
	 *                               or if nodes on the same level belong to different classes
	 */
	private static void verifyPreviousAndNextNodesOnEachLevel(@Nonnull IntBPlusTree<?> bPlusTree, int height) {
		for (int i = 0; i <= height; i++) {
			final List<BPlusTreeNode<?>> nodesOnLevel = getNodesOnLevel(bPlusTree, i);
			BPlusTreeNode<?> previousNode = null;
			for (int j = 0; j < nodesOnLevel.size(); j++) {
				final BPlusTreeNode<?> node = nodesOnLevel.get(j);
				if (previousNode != node.getPreviousNode()) {
					throw new IllegalStateException("Node " + node + " has a wrong previous node!");
				}
				if (previousNode != null) {
					if (previousNode.getNextNode() != node) {
						throw new IllegalStateException("Node " + node + " has a wrong next node!");
					}
					if (previousNode.getClass() != node.getClass()) {
						throw new IllegalStateException("Node " + node + " has a different class than the previous node!");
					}
				}
				previousNode = node;
				if (j == nodesOnLevel.size() - 1) {
					if (node.getNextNode() != null) {
						throw new IllegalStateException("Last node on level " + i + " has a next node!");
					}
				}
			}
		}
	}

	/**
	 * Retrieves all nodes of a specific level in a BPlusTree.
	 *
	 * @param tree  the BPlusTree from which nodes will be retrieved
	 * @param level the level of the tree for which nodes are required
	 * @return a list of nodes found at the specified level in the tree
	 */
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

	/**
	 * Verifies that the given B+ tree node and its child nodes satisfy the minimum required values
	 * in their blocks. Throws an IllegalStateException if any node violates the minimum count condition.
	 *
	 * @param node                     the B+ tree node to verify, which can be an internal node or a leaf node. Must not be null.
	 * @param minValueBlockSize        the minimum number of values required in a leaf node that is not the root.
	 * @param minInternalNodeBlockSize the minimum number of values required in an internal node.
	 * @param isRoot                   a boolean indicating if the current node being verified is the root of the tree.
	 */
	private static void verifyMinimalCountOfValuesInNodes(@Nonnull BPlusTreeNode<?> node, int minValueBlockSize, int minInternalNodeBlockSize, boolean isRoot) {
		if (node instanceof BPlusInternalTreeNode internalNode && !isRoot) {
			if (internalNode.size() < minInternalNodeBlockSize) {
				throw new IllegalStateException("Internal node " + internalNode + " has less than " + minInternalNodeBlockSize + " values (" + node.size() + ")!");
			}
			for (int i = 0; i < internalNode.size(); i++) {
				verifyMinimalCountOfValuesInNodes(internalNode.getChildren()[i], minValueBlockSize, minInternalNodeBlockSize, false);
			}
		} else {
			if (node.size() < minValueBlockSize && !isRoot) {
				throw new IllegalStateException("Leaf node " + node + " has less than " + minValueBlockSize + " values (" + node.size() + ")!");
			}
		}
	}

	/**
	 * Traverses the B+ tree and adds nodes present at the specified level to the provided result list.
	 *
	 * @param currentNode  The current node being traversed in the B+ tree. Must not be null.
	 * @param targetLevel  The target level of the B+ tree whose nodes need to be collected.
	 * @param currentLevel The current level of the tree during traversal.
	 * @param resultNodes  The list where nodes at the target level are collected. Must not be null.
	 */
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
			throw new IllegalStateException("Level " + targetLevel + " not found in the tree!");
		}
	}

	/**
	 * Verifies the integrity of the forward key iterator for a given {@link IntBPlusTree}.
	 * Checks if the keys from the iterator are returned in strictly increasing order and
	 * validates the total number of keys returned matches the expected size.
	 *
	 * @param tree the {@link IntBPlusTree} whose key iterator is to be verified
	 * @param size the expected number of keys in the {@link IntBPlusTree}
	 * @throws IllegalStateException if the iterator fails to return keys in increasing order
	 *                               or if the number of keys does not match the expected size
	 */
	private static void verifyForwardKeyIterator(@Nonnull IntBPlusTree<?> tree, int size) {
		int actualSize = 0;
		int previousKey = Integer.MIN_VALUE;
		final OfInt it = tree.keyIterator();
		while (it.hasNext()) {
			final int key = it.nextInt();
			if (key <= previousKey && previousKey != Integer.MIN_VALUE) {
				throw new IllegalStateException("Forward iterator returned non-increasing keys!");
			}
			actualSize++;
			previousKey = key;
		}

		if (actualSize != size) {
			throw new IllegalStateException("Forward iterator returned " + actualSize + " keys, but the tree has " + size + " elements!");
		}
	}

	/**
	 * Verifies the reverse key iterator of an IntBPlusTree by checking if the keys are
	 * returned in strictly decreasing order and the size of elements matches the expected size.
	 *
	 * @param tree the IntBPlusTree whose reverse key iterator is to be verified
	 * @param size the expected number of elements in the tree
	 * @throws IllegalStateException if the iterator returns non-decreasing keys or if the number of
	 *                               keys returned by the iterator does not match the expected size
	 */
	private static void verifyReverseKeyIterator(@Nonnull IntBPlusTree<?> tree, int size) {
		int actualSize = 0;
		int previousKey = Integer.MIN_VALUE;
		final OfInt it = tree.keyReverseIterator();
		while (it.hasNext()) {
			final int key = it.nextInt();
			if (key >= previousKey && previousKey != Integer.MIN_VALUE) {
				throw new IllegalStateException("Reverse iterator returned non-decreasing keys!");
			}
			actualSize++;
			previousKey = key;
		}

		if (actualSize != size) {
			throw new IllegalStateException("Reverse iterator returned " + actualSize + " keys, but the tree has " + size + " elements!");
		}
	}

	/**
	 * Constructor to initialize the B+ Tree.
	 *
	 * @param valueBlockSize maximum number of values in a leaf node
	 * @param valueType      the type of the values stored in the tree
	 */
	public IntBPlusTree(int valueBlockSize, @Nonnull Class<V> valueType) {
		this(
			valueBlockSize, valueBlockSize / 2,
			valueBlockSize, valueBlockSize / 2,
			valueType
		);
	}

	/**
	 * Constructor to initialize the B+ Tree.
	 *
	 * @param valueBlockSize           maximum number of values in a leaf node
	 * @param minValueBlockSize        minimum number of values in a leaf node
	 *                                 (controls branching factor for leaf nodes)
	 * @param internalNodeBlockSize    maximum number of keys in an internal node
	 * @param minInternalNodeBlockSize minimum number of keys in an internal node
	 *                                 (controls branching factor for internal nodes)
	 * @param valueType                the type of the values stored in the tree
	 */
	public IntBPlusTree(
		int valueBlockSize,
		int minValueBlockSize,
		int internalNodeBlockSize,
		int minInternalNodeBlockSize,
		@Nonnull Class<V> valueType
	) {
		Assert.isPremiseValid(valueBlockSize >= 3, "Block size must be at least 3.");
		Assert.isPremiseValid(minValueBlockSize >= 1, "Minimum block size must be at least 1.");
		Assert.isPremiseValid(minValueBlockSize <= Math.ceil((float) valueBlockSize / 2.0) - 1, "Minimum block size must be less than half of the block size, otherwise the tree nodes might be immediately full after merges.");
		Assert.isPremiseValid(internalNodeBlockSize >= 3, "Internal node block size must be at least 3.");
		Assert.isPremiseValid(internalNodeBlockSize % 2 == 1, "Internal node block size must be an odd number.");
		Assert.isPremiseValid(minInternalNodeBlockSize >= 1, "Minimum internal node block size must be at least 1.");
		Assert.isPremiseValid(minInternalNodeBlockSize <= Math.ceil((float) internalNodeBlockSize / 2.0) - 1, "Minimum internal node block size must be less than half of the internal node block size, otherwise the tree nodes might be immediately full after merges.");
		this.valueBlockSize = valueBlockSize;
		this.minValueBlockSize = minValueBlockSize;
		this.internalNodeBlockSize = internalNodeBlockSize;
		this.minInternalNodeBlockSize = minInternalNodeBlockSize;
		this.valueType = valueType;
		this.root = new BPlusLeafTreeNode<>(valueBlockSize, valueType);
	}

	/**
	 * Inserts a key-value pair into the B+ tree. If the corresponding leaf node
	 * overflows, it is split to maintain the properties of the tree.
	 *
	 * @param key   the key to be inserted into the B+ tree
	 * @param value the value associated with the key, must not be null
	 */
	public void insert(int key, @Nonnull V value) {
		final LeafWithPath<V> leafWithPath = findLeafWithPath(key);
		final BPlusLeafTreeNode<V> leaf = leafWithPath.leaf();
		this.size += leaf.insert(key, value) ? 1 : 0;

		// Split the leaf node if it exceeds the block size
		if (leaf.isFull()) {
			splitLeafNode(leaf, leafWithPath.path());
		}
	}

	/**
	 * Deletes the entry associated with the specified key from the B+ tree.
	 * The method locates the appropriate leaf node containing the key and
	 * removes the entry from it, ensuring that the B+ tree properties are
	 * maintained after deletion.
	 *
	 * @param key the key whose associated entry is to be removed from the B+ tree
	 */
	public void delete(int key) {
		final LeafWithPath<V> leafWithPath = findLeafWithPath(key);
		final BPlusLeafTreeNode<V> leaf = leafWithPath.leaf();

		final boolean headRemoved = leaf.size() > 1 && key == leaf.getKeys()[0];
		this.size -= leaf.delete(key) ? 1 : 0;

		final List<BPlusInternalTreeNode> parentPath = leafWithPath.path();
		// if the head of the leaf has been removed, we need to update parent keys accordingly
		if (headRemoved) {
			updateParentKeys(
				parentPath,
				parentPath.get(leafWithPath.path.size() - 1).getChildIndex(key, leaf),
				leaf,
				0
			);
		}

		consolidate(
			leaf,
			parentPath,
			parentPath.size() - 1,
			0
		);
	}

	/**
	 * Searches for the value associated with the given key in the B+ tree.
	 *
	 * @param key the key to search for within the B+ tree
	 * @return an Optional containing the value associated with the key if it is present,
	 * or an empty Optional if the key is not found in the tree
	 */
	@Nonnull
	public Optional<V> search(int key) {
		return findLeaf(key).search(key);
	}

	/**
	 * Returns the number of elements currently stored in the B+ tree.
	 *
	 * @return the size of the tree, represented as the number of elements it contains
	 */
	public int size() {
		return this.size;
	}

	/**
	 * Returns an iterator that traverses the B+ tree keys from left to right.
	 *
	 * @return an iterator that traverses the B+ tree keys from left to right
	 */
	@Nonnull
	public OfInt keyIterator() {
		return new ForwardKeyIterator(findLeaf(Integer.MIN_VALUE));
	}

	/**
	 * Returns an iterator that traverses the B+ tree keys from right to left.
	 *
	 * @return an iterator that traverses the B+ tree keys from right to left
	 */
	@Nonnull
	public OfInt keyReverseIterator() {
		return new ReverseKeyIterator(findLeaf(Integer.MAX_VALUE));
	}

	/**
	 * Returns an iterator that traverses the B+ tree values from left to right.
	 *
	 * @return an iterator that traverses the B+ tree values from left to right
	 */
	@Nonnull
	public Iterator<V> valueIterator() {
		return new ForwardTreeValueIterator<>(findLeaf(Integer.MIN_VALUE));
	}

	/**
	 * Returns an iterator that traverses the B+ tree values from right to left.
	 *
	 * @return an iterator that traverses the B+ tree values from right to left
	 */
	@Nonnull
	public Iterator<V> valueReverseIterator() {
		return new ReverseTreeValueIterator<>(findLeaf(Integer.MAX_VALUE));
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder(1_024);
		this.root.toVerboseString(sb, 0, 3);
		return sb.toString();
	}

	@Nonnull
	@Override
	public ConsistencyReport getConsistencyReport() {
		try {
			int height = verifyAndReturnHeight(this);
			verifyMinimalCountOfValuesInNodes(this.root, this.minValueBlockSize, this.minInternalNodeBlockSize, true);
			verifyInternalNodeKeys(this.root);
			verifyPreviousAndNextNodesOnEachLevel(this, height);
			verifyForwardKeyIterator(this, this.size);
			verifyReverseKeyIterator(this, this.size);
			return new ConsistencyReport(
				ConsistencyState.CONSISTENT,
				"B+ tree is consistent with height of " + height + " levels and " + this.size + " elements."
			);
		} catch (IllegalStateException e) {
			return new ConsistencyReport(ConsistencyState.BROKEN, e.getMessage());
		}
	}

	/**
	 * Consolidates the provided B+ tree node to maintain the structural properties of the tree.
	 * This method is responsible for handling scenarios where nodes might underflow in terms of
	 * the minimum number of keys or children allowed, and attempts strategies such as borrowing
	 * keys from sibling nodes or merging nodes. If changes propagate up the tree (e.g., through
	 * node merges), the parent nodes are also consolidated.
	 *
	 * @param node      The node to consolidate, which could be a leaf node or an internal node.
	 * @param path      A list representing the path from the root to the node being consolidated.
	 *                  This is used to update parent nodes as necessary when modifications occur.
	 * @param index     The index of the parent node in the path, indicating the relationship
	 *                  between the current node and its parent.
	 * @param watermark Tracks the depth of recursion or the number of upward propagations
	 *                  happening during consolidation.
	 */
	private <N extends BPlusTreeNode<N>> void consolidate(
		@Nonnull N node,
		@Nonnull List<BPlusInternalTreeNode> path,
		int index,
		int watermark
	) {
		// leaf node has less than minBlockSize keys, or internal nodes has less than two children
		final boolean underFlowNode = node.keyCount() < this.minValueBlockSize;
		if (underFlowNode) {
			if (path.size() > index && index >= 0) {
				boolean nodeIsEmpty = node.size() == 0;
				final BPlusInternalTreeNode parent = path.get(index);
				final N previousNode = node.getPreviousNode();
				final int previousNodeIndexInParent = previousNode == null ? -1 : parent.getChildIndex(previousNode.getKeys()[0], previousNode);
				// if previous node with current node exists and shares the same parent
				// and we can steal from the left sibling
				if (previousNodeIndexInParent > -1 && previousNode.keyCount() > this.minValueBlockSize) {
					// steal half of the surplus data from the left sibling
					node.stealFromLeft(Math.max(1, (previousNode.keyCount() - this.minValueBlockSize) / 2));
					// update parent keys, but only if node was empty - which means first key was added
					updateParentKeys(path, previousNodeIndexInParent + 1, node, watermark);
					return;
				}

				final N nextNode = node.getNextNode();
				final int nextNodeIndexInParent = nextNode == null ? -1 : parent.getChildIndex(nextNode.getKeys()[0], nextNode);
				// if next node with current node exists and shares the same parent
				// and we can steal from the right sibling
				if (nextNodeIndexInParent > -1 && nextNode.keyCount() > this.minValueBlockSize) {
					// steal half of the surplus data from the right sibling
					node.stealFromRight(Math.max(1, (nextNode.keyCount() - this.minValueBlockSize) / 2));
					// update parent keys of the next node - we've stolen its first key
					updateParentKeys(path, nextNodeIndexInParent, nextNode, watermark);
					// update parent keys, but only if node was empty - which means first key was added
					if (node instanceof BPlusInternalTreeNode || nodeIsEmpty) {
						updateParentKeys(path, nextNodeIndexInParent - 1, node, watermark);
					}
					return;
				}

				// if previous node with current node can be merged and share the same parent
				if (previousNodeIndexInParent > -1 && previousNode.keyCount() + node.keyCount() < this.valueBlockSize) {
					// merge nodes
					node.mergeWithLeft();
					// remove the removed child from the parent
					parent.removeChildOnIndex(previousNodeIndexInParent, previousNodeIndexInParent);
					// update parent keys, previous node has been removed
					updateParentKeys(path, previousNodeIndexInParent, node, watermark);
					// consolidate the parent node
					consolidate(parent, path, index - 1, watermark + 1);
					return;
				}

				// if next node with current node can be merged and share the same parent
				if (nextNodeIndexInParent > -1 && nextNode.keyCount() + node.keyCount() < this.valueBlockSize) {
					// merge nodes
					node.mergeWithRight();
					// remove the removed child from the parent
					parent.removeChildOnIndex(nextNodeIndexInParent - 1, nextNodeIndexInParent);
					// update parent keys, next node has been removed
					updateParentKeys(path, nextNodeIndexInParent - 1, node, watermark);
					// consolidate the parent node
					consolidate(parent, path, index - 1, watermark + 1);
				}
			} else if (node == this.root && node.size() == 1 && node instanceof BPlusInternalTreeNode internalTreeNode) {
				// replace the root with the only child
				this.root = internalTreeNode.getChildren()[0];
			}
		}
	}

	/**
	 * Finds the leaf node in the B+ tree that should contain the specified key.
	 * The method begins its search from the root node and traverses down to the leaf node
	 * by following the appropriate child pointers of internal nodes.
	 *
	 * @param key the key to search for within the B+ tree
	 * @return the leaf node that is responsible for storing the provided key
	 */
	@Nonnull
	private BPlusLeafTreeNode<V> findLeaf(int key) {
		BPlusTreeNode<?> node = this.root;
		while (node instanceof BPlusInternalTreeNode internalNode) {
			node = internalNode.search(key);
		}
		//noinspection unchecked
		return (BPlusLeafTreeNode<V>) node;
	}

	/**
	 * Finds the leaf node in the B+ tree that should contain the specified key.
	 * The method begins its search from the root node and traverses down to the leaf node
	 * by following the appropriate child pointers of internal nodes.
	 *
	 * @param key the key to search for within the B+ tree
	 * @return the leaf node that is responsible for storing the provided key
	 */
	@Nonnull
	private LeafWithPath<V> findLeafWithPath(int key) {
		if (this.root instanceof BPlusInternalTreeNode rootInternalNode) {
			final ArrayList<BPlusInternalTreeNode> path = new ArrayList<>((int) (Math.log(this.size()) + 1));
			path.add(rootInternalNode);
			final BPlusLeafTreeNode<V> leaf = findLeafWithPath(rootInternalNode, key, path);
			return new LeafWithPath<>(path, leaf);
		} else {
			//noinspection unchecked
			return new LeafWithPath<>(List.of(), (BPlusLeafTreeNode<V>) this.root);
		}
	}

	/**
	 * This method recursively traverses the B+ tree to find the leaf node responsible
	 * for the specified key. It also populates the path traversed with internal nodes.
	 *
	 * @param currentNode The current internal tree node being traversed. Must not be null.
	 * @param key         The key for which the corresponding leaf node is to be found.
	 * @param path        A list to store the sequence of internal nodes visited. Must not be null.
	 * @return The leaf tree node that should contain the specified key.
	 */
	@Nonnull
	private BPlusLeafTreeNode<V> findLeafWithPath(
		@Nonnull BPlusInternalTreeNode currentNode,
		int key,
		@Nonnull List<BPlusInternalTreeNode> path
	) {
		final BPlusTreeNode<?> child = currentNode.search(key);
		if (child instanceof BPlusInternalTreeNode childInternalNode) {
			path.add(childInternalNode);
			return findLeafWithPath(childInternalNode, key, path);
		} else {
			//noinspection unchecked
			return (BPlusLeafTreeNode<V>) child;
		}
	}

	/**
	 * Splits a full leaf node into two leaf nodes to maintain the properties of the B+ tree.
	 * If the split occurs at the root, a new root is created.
	 *
	 * @param leaf    The leaf node to be split
	 * @param parents The list of internal nodes representing the path from the root to the leaf node being split
	 */
	private void splitLeafNode(
		@Nonnull BPlusLeafTreeNode<V> leaf,
		@Nonnull List<BPlusInternalTreeNode> parents
	) {
		final int mid = this.valueBlockSize / 2;
		final int[] originKeys = leaf.getKeys();
		final V[] originValues = leaf.getValues();

		// Move half the keys to the new arrays of the left leaf node
		//noinspection unchecked
		final BPlusLeafTreeNode<V> leftLeaf = new BPlusLeafTreeNode<>(
			originKeys,
			originValues,
			new int[this.valueBlockSize],
			(V[]) Array.newInstance(this.valueType, this.valueBlockSize),
			0,
			mid
		);

		// Move the other half to the start of existing arrays of former leaf in the right leaf node
		final BPlusLeafTreeNode<V> rightLeaf = new BPlusLeafTreeNode<>(
			originKeys,
			originValues,
			originKeys,
			originValues,
			mid,
			leftLeaf.getKeys().length
		);

		// rewire the leaf node pointers
		rewireSiblings(leaf, leftLeaf, rightLeaf);

		// If the root splits, create a new root
		if (leaf == this.root) {
			this.root = new BPlusInternalTreeNode(
				this.valueBlockSize,
				rightLeaf.getKeys()[0],
				leftLeaf, rightLeaf
			);
		} else {
			replaceNodeInParentInternalNode(
				leaf,
				leftLeaf,
				rightLeaf,
				rightLeaf.getKeys()[0],
				parents
			);
		}
	}

	/**
	 * Replaces a node in its parent with two new nodes as part of the B+ tree splitting process.
	 * This method is used when a node is split and the parent needs to be updated
	 * to reflect the split structure.
	 *
	 * @param original The original BPlusTreeNode that is being replaced.
	 * @param left     The left child BPlusTreeNode resulting from the split, containing keys less than the new partition key.
	 * @param right    The right child BPlusTreeNode resulting from the split, containing keys greater than the new partition key.
	 * @param key      The partition key that separates the left and right nodes.
	 * @throws GenericEvitaInternalError if the parent node cannot be found for the original node.
	 */
	private void replaceNodeInParentInternalNode(
		@Nonnull BPlusTreeNode<?> original,
		@Nonnull BPlusTreeNode<?> left,
		@Nonnull BPlusTreeNode<?> right,
		int key,
		@Nonnull List<BPlusInternalTreeNode> parents
	) {
		final BPlusInternalTreeNode parent = parents.get(parents.size() - 1);
		parent.adaptToLeafSplit(key, original, left, right);

		if (parent.isFull()) {
			splitInternalNode(parent, parents.subList(0, parents.size() - 1));
		}
	}

	/**
	 * Splits a full internal node in a B+ tree into two separate nodes to maintain the properties of the B+ tree.
	 * The method creates two new nodes: a left node containing the lower half of the original node's keys and
	 * a right node containing the upper half. If the node being split is the root of the tree,
	 * a new root node is created. Otherwise, the parent node is updated to reflect the split.
	 *
	 * @param internal The internal node to be split. It must not be null and must contain a number of keys
	 *                 that necessitate splitting to maintain the B+ tree properties.
	 * @param parents  The list of internal nodes representing the path from the root to the internal node being split.
	 */
	private void splitInternalNode(
		@Nonnull BPlusInternalTreeNode internal,
		@Nonnull List<BPlusInternalTreeNode> parents
	) {
		final int mid = (this.valueBlockSize + 1) / 2;
		final int[] originKeys = internal.getKeys();
		final BPlusTreeNode<?>[] originChildren = internal.getChildren();

		// Move half the keys to the new arrays of the left leaf node
		final BPlusInternalTreeNode leftInternal = new BPlusInternalTreeNode(
			originKeys,
			originChildren,
			new int[this.valueBlockSize],
			new BPlusTreeNode[this.valueBlockSize + 1],
			0,
			mid - 1,
			0,
			mid
		);

		// Move the other half to the start of existing arrays of former leaf in the right leaf node
		final BPlusInternalTreeNode rightInternal = new BPlusInternalTreeNode(
			originKeys,
			originChildren,
			originKeys,
			originChildren,
			mid,
			leftInternal.getKeys().length,
			mid,
			leftInternal.getChildren().length
		);

		// rewire the leaf node pointers
		rewireSiblings(internal, leftInternal, rightInternal);

		// If the root splits, create a new root
		if (internal == this.root) {
			this.root = new BPlusInternalTreeNode(
				this.valueBlockSize,
				rightInternal.getLeftBoundaryKey(),
				leftInternal, rightInternal
			);
		} else {
			replaceNodeInParentInternalNode(
				internal,
				leftInternal,
				rightInternal,
				rightInternal.getLeftBoundaryKey(),
				parents
			);
		}
	}

	/**
	 * B+ Tree Node class to represent internal node.
	 */
	interface BPlusTreeNode<N extends BPlusTreeNode<N>> {

		/**
		 * Retrieves an array of integer keys associated with the node.
		 *
		 * @return an array of integer keys present in the node. The array is guaranteed to be non-null.
		 */
		@Nonnull
		int[] getKeys();

		/**
		 * Retrieves the peek index (last usable value) of the B+ Tree node's values / children.
		 *
		 * @return the peek value of the node, indicating the last usable index in the node's values / children array.
		 */
		int getPeek();

		/**
		 * Sets the peek index of the B+ Tree node. The peek index indicates the last
		 * usable position in the node's values or children array.
		 *
		 * @param peek the new peek index to set for the node
		 */
		void setPeek(int peek);

		/**
		 * Returns number of values in this node - i.e. peek + 1.
		 *
		 * @return number of values in this node
		 */
		default int size() {
			return getPeek() + 1;
		}

		/**
		 * Returns number of keys in this node - which differs between leaf and internal nodes.
		 *
		 * @return number of keys in this node
		 */
		int keyCount();

		/**
		 * Checks if the current B+ Tree leaf node is full, meaning all available slots are occupied.
		 *
		 * @return true if the node is full, false otherwise.
		 */
		boolean isFull();

		/**
		 * Converts the B+ Tree Node to a string representation with a specified level and indentation.
		 *
		 * @param sb           the StringBuilder to which the string representation will be appended.
		 * @param level        the current level of the node in the B+ Tree hierarchy.
		 * @param indentSpaces the number of spaces to use for indenting the string representation.
		 */
		void toVerboseString(@Nonnull StringBuilder sb, int level, int indentSpaces);

		/**
		 * Retrieves the previous node in the B+ Tree structure, if present.
		 *
		 * @return the previous node of type N, or null if there is no previous node.
		 */
		@Nullable
		N getPreviousNode();

		/**
		 * Sets the previous node in the B+ Tree structure for the current node.
		 * The previous node is typically a sibling or adjacent node in the B+ Tree structure.
		 *
		 * @param previousNode the node to set as the previous node. Can be null if there is no previous node.
		 */
		void setPreviousNode(@Nullable N previousNode);

		/**
		 * Retrieves the next node in the B+ Tree structure, if available.
		 *
		 * @return the next node of type N, or null if there is no next node.
		 */
		@Nullable
		N getNextNode();

		/**
		 * Sets the next node in the B+ Tree structure for the current node.
		 * The next node is typically the immediate successor or adjacent node in the B+ Tree.
		 *
		 * @param nextNode the node to set as the next node. Can be null if there is no next node.
		 */
		void setNextNode(@Nullable N nextNode);

		/**
		 * Steals a specified number of values from the end of the left sibling node.
		 *
		 * @param numberOfTailValues the number of values to steal from the left sibling node.
		 */
		void stealFromLeft(int numberOfTailValues);

		/**
		 * Steals a specified number of values from the start of the right sibling node.
		 *
		 * @param numberOfHeadValues the number of values to steal from the right sibling node.
		 */
		void stealFromRight(int numberOfHeadValues);

		/**
		 * Merges the current leaf node with the left sibling leaf node.
		 */
		void mergeWithLeft();

		/**
		 * Merges the current leaf node with the right sibling leaf node.
		 */
		void mergeWithRight();

		/**
		 * Retrieves the left boundary key of the BPlusInternalTreeNode. This key is the smallest key contained
		 * within the leftmost child of the current internal tree node. If the leftmost child is an internal node itself,
		 * the method recursively retrieves the left boundary key of that internal node.
		 *
		 * @return the left boundary key of the BPlusInternalTreeNode.
		 */
		int getLeftBoundaryKey();
	}

	/**
	 * B+ Tree Node class to represent internal node.
	 */
	static class BPlusInternalTreeNode implements BPlusTreeNode<BPlusInternalTreeNode> {
		/**
		 * The keys stored in this node.
		 */
		@Getter private final int[] keys;

		/**
		 * The children of this node.
		 */
		@Getter private final BPlusTreeNode<?>[] children;

		/**
		 * Link to the previous internal node.
		 * Although these data are not used in generic implementations, keeping those pointers allows us to keep
		 * tree balanced during deletions (merges).
		 */
		@Nullable @Getter @Setter
		private BPlusInternalTreeNode previousNode;

		/**
		 * Link to the next internal node.
		 * Although these data are not used in generic implementations, keeping those pointers allows us to keep
		 * tree balanced during deletions (merges).
		 */
		@Nullable @Getter @Setter
		private BPlusInternalTreeNode nextNode;

		/**
		 * Index of the last occupied position in the children array.
		 */
		@Getter private int peek;

		public BPlusInternalTreeNode(
			int blockSize,
			int key,
			@Nonnull BPlusTreeNode<?> leftLeaf,
			@Nonnull BPlusTreeNode<?> rightLeaf
		) {
			this.keys = new int[blockSize];
			this.children = new BPlusTreeNode[blockSize + 1];
			this.keys[0] = key;
			this.children[0] = leftLeaf;
			this.children[1] = rightLeaf;
			this.peek = 1;
		}

		public BPlusInternalTreeNode(
			@Nonnull int[] originKeys,
			@Nonnull BPlusTreeNode<?>[] originChildren,
			@Nonnull int[] keys,
			@Nonnull BPlusTreeNode<?>[] children,
			int keyStart, int keyEnd,
			int childrenStart, int childrenEnd
		) {
			this.keys = keys;
			this.children = children;
			// Copy the keys and children from the origin arrays
			System.arraycopy(originKeys, keyStart, keys, 0, keyEnd - keyStart);
			//noinspection ArrayEquality
			if (keys == originKeys) {
				Arrays.fill(keys, keyEnd - keyStart, keys.length, 0);
			}
			System.arraycopy(originChildren, childrenStart, children, 0, childrenEnd - childrenStart);
			//noinspection ArrayEquality
			if (children == originChildren) {
				Arrays.fill(children, childrenEnd - childrenStart, children.length, null);
			}
			this.peek = childrenEnd - childrenStart - 1;
		}

		@Override
		public void setPeek(int peek) {
			final int originPeek = this.peek;
			this.peek = peek;
			if (peek < originPeek) {
				Arrays.fill(this.keys, Math.max(0, peek), originPeek, 0);
				Arrays.fill(this.children, peek + 1, originPeek + 1, null);
			}
		}

		@Override
		public int keyCount() {
			return Math.max(this.peek, 0);
		}

		@Override
		public boolean isFull() {
			return this.peek == this.children.length - 1;
		}

		@Override
		public void toVerboseString(@Nonnull StringBuilder sb, int level, int indentSpaces) {
			sb.append(" ".repeat(level * indentSpaces)).append("< ").append(this.keys[0]).append(":\n");
			this.children[0].toVerboseString(sb, level + 1, indentSpaces);
			sb.append("\n");
			for (int i = 1; i <= this.peek; i++) {
				final int key = this.keys[i - 1];
				final BPlusTreeNode<?> child = this.children[i];
				sb.append(" ".repeat(level * indentSpaces)).append(">=").append(key).append(":\n");
				child.toVerboseString(sb, level + 1, indentSpaces);
				if (i < this.peek) {
					sb.append("\n");
				}
			}
		}

		@Override
		public void stealFromLeft(int numberOfTailValues) {
			Assert.isPremiseValid(numberOfTailValues > 0, "Number of tail values to steal must be positive!");
			final BPlusInternalTreeNode prevNode = Objects.requireNonNull(this.previousNode);
			// we preserve all the current node children
			System.arraycopy(this.children, 0, this.children, numberOfTailValues, this.peek + 1);
			// then move the children from the previous node
			System.arraycopy(prevNode.getChildren(), prevNode.size() - numberOfTailValues, this.children, 0, numberOfTailValues);
			// we need to preserve all the current node keys
			System.arraycopy(this.keys, 0, this.keys, numberOfTailValues, this.peek);
			// our original first child newly produces its own key
			this.keys[numberOfTailValues - 1] = this.children[numberOfTailValues].getLeftBoundaryKey();
			// and now we can copy the keys from the previous node - but except the first one
			System.arraycopy(prevNode.getKeys(), prevNode.keyCount() - numberOfTailValues + 1, this.keys, 0, numberOfTailValues - 1);
			// and update the peek indexes
			this.peek += numberOfTailValues;
			prevNode.setPeek(prevNode.getPeek() - numberOfTailValues);
		}

		@Override
		public void stealFromRight(int numberOfHeadValues) {
			Assert.isPremiseValid(numberOfHeadValues > 0, "Number of head values to steal must be positive!");
			final BPlusInternalTreeNode nextNode = Objects.requireNonNull(this.nextNode);

			// we move all the children
			System.arraycopy(nextNode.getChildren(), 0, this.children, this.peek + 1, numberOfHeadValues);
			System.arraycopy(nextNode.getChildren(), numberOfHeadValues, nextNode.getChildren(), 0, nextNode.size() - numberOfHeadValues);

			// set the key for the first child of the next node
			this.keys[this.peek] = this.children[this.peek + 1].getLeftBoundaryKey();

			// we move the keys from the next node for all copied children
			System.arraycopy(nextNode.getKeys(), 0, this.keys, this.peek + 1, numberOfHeadValues - 1);
			// we need to shift the keys in the next node
			System.arraycopy(nextNode.getKeys(), numberOfHeadValues, nextNode.getKeys(), 0, nextNode.getKeys().length - numberOfHeadValues);

			// and update the peek indexes
			this.peek += numberOfHeadValues;
			nextNode.setPeek(nextNode.getPeek() - numberOfHeadValues);
		}

		@Override
		public void mergeWithLeft() {
			final BPlusInternalTreeNode nodeToMergeWith = Objects.requireNonNull(this.previousNode);
			final int mergePeek = nodeToMergeWith.getPeek();
			System.arraycopy(this.keys, 0, this.keys, mergePeek + 1, this.peek);
			this.keys[mergePeek] = this.children[0].getLeftBoundaryKey();
			System.arraycopy(this.children, 0, this.children, mergePeek + 1, this.peek + 1);
			System.arraycopy(nodeToMergeWith.getKeys(), 0, this.keys, 0, mergePeek);
			System.arraycopy(nodeToMergeWith.getChildren(), 0, this.children, 0, mergePeek + 1);
			this.peek += mergePeek + 1;
			nodeToMergeWith.setPeek(-1);
		}

		@Override
		public void mergeWithRight() {
			final BPlusInternalTreeNode nodeToMergeWith = Objects.requireNonNull(this.nextNode);
			final int mergePeek = nodeToMergeWith.getPeek();
			System.arraycopy(nodeToMergeWith.getChildren(), 0, this.children, this.peek + 1, mergePeek + 1);
			final int offset;
			if (this.peek >= 0) {
				this.keys[this.peek] = nodeToMergeWith.getChildren()[0].getLeftBoundaryKey();
				offset = 1;
			} else {
				offset = 0;
			}
			System.arraycopy(nodeToMergeWith.getKeys(), 0, this.keys, this.peek + offset, mergePeek);
			this.peek += mergePeek + 1;
			nodeToMergeWith.setPeek(-1);
		}

		@Override
		public int getLeftBoundaryKey() {
			return this.children[0].getLeftBoundaryKey();
		}

		/**
		 * Splits a B+ Tree node by inserting a new key into the node's keys array and updating its children accordingly.
		 * This method is used for managing the internal structure of a B+ Tree when a node needs to be divided due to
		 * overflow.
		 *
		 * @param key      The integer key to be inserted into the B+ Tree node.
		 * @param original The original B+ Tree node that is the child of the internal node. This node is being split into two nodes.
		 * @param left     The left child BPlusTreeNode resulting from the split, containing keys less than the inserted key.
		 * @param right    The right child BPlusTreeNode resulting from the split, containing keys greater than the inserted key.
		 */
		public void adaptToLeafSplit(
			int key,
			@Nonnull BPlusTreeNode<?> original,
			@Nonnull BPlusTreeNode<?> left,
			@Nonnull BPlusTreeNode<?> right
		) {
			Assert.isPremiseValid(
				!this.isFull(),
				"Internal node must not be full to accommodate two leaf nodes after their split!"
			);

			// the peek relates to children, which are one more than keys, that's why we don't use peek + 1, but mere peek
			final InsertionPosition insertionPosition = computeInsertPositionOfIntInOrderedArray(key, this.keys, 0, this.peek);
			Assert.isPremiseValid(
				original == this.children[insertionPosition.position()],
				"Original node must be the child of the internal node!"
			);
			Assert.isPremiseValid(
				!insertionPosition.alreadyPresent(),
				"Key already present in the internal node!"
			);

			insertIntIntoSameArrayOnIndex(key, this.keys, insertionPosition.position());
			this.children[insertionPosition.position()] = left;
			insertRecordIntoSameArrayOnIndex(right, this.children, insertionPosition.position() + 1);
			this.peek++;
		}

		/**
		 * Searches for the BPlusTreeNode that should contain the given key.
		 *
		 * @param key the integer key to search for within the B+ Tree.
		 * @return the BPlusTreeNode that should contain the specified key.
		 */
		@Nonnull
		public BPlusTreeNode<?> search(int key) {
			final InsertionPosition insertionPosition = computeInsertPositionOfIntInOrderedArray(key, this.keys, 0, this.peek);
			return insertionPosition.alreadyPresent() ?
				this.children[insertionPosition.position() + 1] : this.children[insertionPosition.position()];
		}

		/**
		 * Retrieves the index position of the specified child node within the children array.
		 * The search is performed up to the current peek position.
		 *
		 * @param key  the key to search for within the children array.
		 * @param node the BPlusTreeNode whose index needs to be found within the children array.
		 * @return the index of the specified child node if found; otherwise, -1 indicating that the node is not present in the children array.
		 */
		public int getChildIndex(int key, @Nonnull BPlusTreeNode<?> node) {
			if (this.getPeek() < 0) {
				return -1;
			} else {
				final int keyIndex = Arrays.binarySearch(this.keys, 0, this.keyCount(), key);
				if (keyIndex < 0) {
					// the key might have been removed - try to iterate over the children
					for (int i = 0; i <= this.peek; i++) {
						if (this.children[i] == node) {
							return i;
						}
					}
					return -1;
				} else {
					Assert.isPremiseValid(
						this.children[keyIndex + 1] == node,
						"Key index does not match the child node!" // this should never happen
					);
					return keyIndex + 1;
				}
			}
		}

		/**
		 * Removes a child node from the children array at the specified index.
		 * This operation shifts all subsequent child nodes one position to the left,
		 * effectively overwriting the array element at the given index. The size of
		 * the array remains unchanged, but the number of meaningful elements (peek)
		 * is decremented.
		 *
		 * @param keyIndex   The position of the key to be removed from the keys array.
		 * @param childIndex The position of the child node to be removed from the children array.
		 *                   It must be within the bounds of the current number of children (peek).
		 */
		public <N extends BPlusTreeNode<N>> void removeChildOnIndex(int keyIndex, int childIndex) {
			//noinspection unchecked
			final BPlusTreeNode<N> child = (BPlusTreeNode<N>) this.children[childIndex];
			if (child.getPreviousNode() != null) {
				child.getPreviousNode().setNextNode(child.getNextNode());
			}
			if (child.getNextNode() != null) {
				child.getNextNode().setPreviousNode(child.getPreviousNode());
			}


			removeIntFromSameArrayOnIndex(this.keys, keyIndex);
			this.keys[this.peek - 1] = 0;
			removeRecordFromSameArrayOnIndex(this.children, childIndex);
			this.children[this.peek] = null;
			this.peek--;
		}

		/**
		 * Updates the key associated with the specified index in the internal node.
		 * The key to update must correspond to the given child node at the specified index.
		 *
		 * @param index The index in the keys array where the key needs to be updated.
		 *              Must be greater than 0 and within the bounds of the current keys array.
		 * @param node  The BPlusTreeNode whose first key will replace the key at the specified index in the internal node.
		 *              Must match the child node of this internal node at the specified index.
		 */
		public void updateKeyForNode(int index, @Nonnull BPlusTreeNode<?> node) {
			Assert.isPremiseValid(
				index > 0,
				"Leftmost child node does not have a key in the parent node!"
			);
			Assert.isPremiseValid(
				this.children[index] == node,
				"Node to update key for must match the child node at the specified index!"
			);
			this.keys[index - 1] = node.getLeftBoundaryKey();
		}

		@Override
		public String toString() {
			final StringBuilder sb = new StringBuilder(64);
			toVerboseString(sb, 0, 3);
			return sb.toString();
		}

	}

	/**
	 * B+ Tree Node class to represent leaf node with associated values.
	 */
	static class BPlusLeafTreeNode<V> implements BPlusTreeNode<BPlusLeafTreeNode<V>> {
		/**
		 * The keys stored in this node.
		 */
		@Getter private final int[] keys;

		/**
		 * The values stored in this node. Index i corresponds to the value associated with key i.
		 */
		@Getter private final V[] values;

		/**
		 * Link to the previous leaf node.
		 */
		@Nullable @Getter @Setter
		private BPlusLeafTreeNode<V> previousNode;

		/**
		 * Link to the next leaf node.
		 */
		@Nullable @Getter @Setter
		private BPlusLeafTreeNode<V> nextNode;

		/**
		 * Index of the last occupied position in the keys array.
		 */
		@Getter private int peek;

		public BPlusLeafTreeNode(
			int blockSize,
			@Nonnull Class<V> valueType
		) {
			this.keys = new int[blockSize];
			//noinspection unchecked
			this.values = (V[]) Array.newInstance(valueType, blockSize);
			this.previousNode = null;
			this.nextNode = null;
			this.peek = -1;
		}

		public BPlusLeafTreeNode(
			@Nonnull int[] originKeys,
			@Nonnull V[] originValues,
			@Nonnull int[] keys,
			@Nonnull V[] values,
			int start, int end
		) {
			this.keys = keys;
			this.values = values;
			// Copy the keys and values from the origin arrays
			System.arraycopy(originKeys, start, keys, 0, end - start);
			//noinspection ArrayEquality
			if (keys == originKeys) {
				Arrays.fill(keys, end - start, keys.length, 0);
			}
			System.arraycopy(originValues, start, values, 0, end - start);
			//noinspection ArrayEquality
			if (values == originValues) {
				Arrays.fill(values, end - start, values.length, null);
			}
			this.previousNode = null;
			this.nextNode = null;
			this.peek = end - start - 1;
		}

		@Override
		public void setPeek(int peek) {
			final int originPeek = this.peek;
			this.peek = peek;
			if (peek < originPeek) {
				Arrays.fill(this.keys, peek + 1, originPeek + 1, 0);
				Arrays.fill(this.values, peek + 1, originPeek + 1, null);
			}
		}

		@Override
		public int keyCount() {
			return this.peek + 1;
		}

		@Override
		public boolean isFull() {
			return this.peek == this.values.length - 1;
		}

		@Override
		public void toVerboseString(@Nonnull StringBuilder sb, int level, int indentSpaces) {
			sb.append(" ".repeat(level * indentSpaces));
			for (int i = 0; i <= this.peek; i++) {
				sb.append(this.keys[i]).append(":").append(this.values[i]);
				if (i < this.peek) {
					sb.append(", ");
				}
			}
		}

		@Override
		public void stealFromLeft(int numberOfTailValues) {
			Assert.isPremiseValid(numberOfTailValues > 0, "Number of tail values to steal must be positive!");
			final BPlusLeafTreeNode<V> prevNode = Objects.requireNonNull(this.previousNode);
			System.arraycopy(this.keys, 0, this.keys, numberOfTailValues, this.peek + 1);
			System.arraycopy(this.values, 0, this.values, numberOfTailValues, this.peek + 1);
			System.arraycopy(prevNode.getKeys(), prevNode.size() - numberOfTailValues, this.keys, 0, numberOfTailValues);
			System.arraycopy(prevNode.getValues(), prevNode.size() - numberOfTailValues, this.values, 0, numberOfTailValues);
			this.peek += numberOfTailValues;
			prevNode.setPeek(prevNode.getPeek() - numberOfTailValues);
		}

		@Override
		public void stealFromRight(int numberOfHeadValues) {
			Assert.isPremiseValid(numberOfHeadValues > 0, "Number of head values to steal must be positive!");
			final BPlusLeafTreeNode<V> nextNode = Objects.requireNonNull(this.nextNode);
			System.arraycopy(nextNode.getKeys(), 0, this.keys, this.peek + 1, numberOfHeadValues);
			System.arraycopy(nextNode.getValues(), 0, this.values, this.peek + 1, numberOfHeadValues);
			System.arraycopy(nextNode.getKeys(), numberOfHeadValues, nextNode.getKeys(), 0, nextNode.size() - numberOfHeadValues);
			System.arraycopy(nextNode.getValues(), numberOfHeadValues, nextNode.getValues(), 0, nextNode.size() - numberOfHeadValues);
			nextNode.setPeek(nextNode.getPeek() - numberOfHeadValues);
			this.peek += numberOfHeadValues;
		}

		@Override
		public void mergeWithLeft() {
			final BPlusLeafTreeNode<V> nodeToMergeWith = Objects.requireNonNull(this.previousNode);
			final int mergePeek = nodeToMergeWith.getPeek();
			System.arraycopy(this.keys, 0, this.keys, mergePeek + 1, this.peek + 1);
			System.arraycopy(this.values, 0, this.values, mergePeek + 1, this.peek + 1);
			System.arraycopy(nodeToMergeWith.getKeys(), 0, this.keys, 0, mergePeek + 1);
			System.arraycopy(nodeToMergeWith.getValues(), 0, this.values, 0, mergePeek + 1);
			this.peek += mergePeek + 1;
			nodeToMergeWith.setPeek(-1);
		}

		@Override
		public void mergeWithRight() {
			final BPlusLeafTreeNode<V> nodeToMergeWith = Objects.requireNonNull(this.nextNode);
			final int mergePeek = nodeToMergeWith.getPeek();
			System.arraycopy(nodeToMergeWith.getKeys(), 0, this.keys, this.peek + 1, mergePeek + 1);
			System.arraycopy(nodeToMergeWith.getValues(), 0, this.values, this.peek + 1, mergePeek + 1);
			this.peek += mergePeek + 1;
			nodeToMergeWith.setPeek(-1);
		}

		@Override
		public int getLeftBoundaryKey() {
			return this.keys[0];
		}

		/**
		 * Searches for a value in the node's key-value pairs by the specified key.
		 * If the key is found, returns an Optional containing the associated value;
		 * otherwise returns an empty Optional.
		 *
		 * @param key the key to search for in the leaf node
		 * @return an Optional containing the value associated with the specified key if found;
		 * otherwise, an empty Optional
		 */
		@Nonnull
		public Optional<V> search(int key) {
			final InsertionPosition insertionPosition = computeInsertPositionOfIntInOrderedArray(key, this.keys, 0, this.peek + 1);
			return insertionPosition.alreadyPresent() ?
				Optional.of(this.values[insertionPosition.position()]) : Optional.empty();
		}

		@Override
		public String toString() {
			final StringBuilder sb = new StringBuilder(64);
			toVerboseString(sb, 0, 3);
			return sb.toString();
		}

		/**
		 * Deletes a key-value pair from the BPlusLeafTreeNode based on the specified key.
		 * If the key is found within the node, it removes the corresponding entry,
		 * maintains the node's internal structure, and decrements the count of stored items.
		 *
		 * @param key the key of the entry to be removed from the leaf node
		 * @return true if the key was found and removed, false otherwise
		 * @throws GenericEvitaInternalError if the key is not found in the node
		 */
		public boolean delete(int key) {
			final int index = Arrays.binarySearch(this.keys, 0, this.peek + 1, key);

			if (index >= 0) {
				removeIntFromSameArrayOnIndex(this.keys, index);
				removeRecordFromSameArrayOnIndex(this.values, index);
				this.keys[this.peek] = 0;
				this.values[this.peek] = null;
				this.peek--;
				return true;
			} else {
				return false;
			}
		}

		/**
		 * Inserts a key-value pair into a specified leaf node of the B+ tree.
		 * Adjusts the position of the key and maintains the order of keys within the leaf node.
		 * If the key already exists, this method will add it in the correct position to maintain order.
		 *
		 * @param key   the key to be inserted into the leaf node
		 * @param value the value associated with the key, must not be null
		 * @return true if new key was inserted, otherwise false
		 */
		private boolean insert(int key, @Nonnull V value) {
			Assert.isPremiseValid(
				this.peek < this.keys.length - 1,
				"Cannot insert into a full leaf node, split the node first!"
			);

			final InsertionPosition insertionPosition = computeInsertPositionOfIntInOrderedArray(key, this.keys, 0, this.peek + 1);
			if (insertionPosition.alreadyPresent()) {
				this.keys[insertionPosition.position()] = key;
				this.values[insertionPosition.position()] = value;
				return false;
			} else {
				insertIntIntoSameArrayOnIndex(key, this.keys, insertionPosition.position());
				insertRecordIntoSameArrayOnIndex(value, this.values, insertionPosition.position());
				this.peek++;
				return true;
			}
		}

	}

	/**
	 * Represents a result of finding a leaf node in a B+ tree along with the path taken
	 * to reach that leaf node. This is useful for operations that need to track the
	 * traversed path of internal nodes leading to a specific leaf.
	 *
	 * @param <V>  the type of the value stored in the leaf node
	 * @param path a non-null list of internal tree nodes representing the path from the root
	 *             to the leaf node in the B+ tree
	 * @param leaf a non-null leaf tree node where the search or modification operation is targeted
	 */
	private record LeafWithPath<V>(
		@Nonnull List<BPlusInternalTreeNode> path,
		@Nonnull BPlusLeafTreeNode<V> leaf
	) {
	}

	/**
	 * Iterator that traverses the B+ Tree from left to right.
	 */
	private static class ForwardKeyIterator implements OfInt {
		/**
		 * The current leaf node being traversed.
		 */
		@Nullable private BPlusLeafTreeNode<?> currentLeaf;
		/**
		 * The index of the current key within the current leaf node.
		 */
		private int currentKeyIndex;
		/**
		 * Flag indicating whether there are more elements to traverse.
		 */
		private boolean hasNext;

		public ForwardKeyIterator(@Nonnull BPlusLeafTreeNode<?> leaf) {
			this.currentLeaf = leaf;
			this.currentKeyIndex = 0;
			this.hasNext = this.currentLeaf.getPeek() >= 0;
		}

		@Override
		public int nextInt() {
			if (!this.hasNext || this.currentLeaf == null || this.currentKeyIndex > this.currentLeaf.getPeek()) {
				throw new NoSuchElementException("No more elements available");
			}
			final int key = this.currentLeaf.getKeys()[this.currentKeyIndex];
			if (this.currentKeyIndex < this.currentLeaf.getPeek()) {
				this.currentKeyIndex++;
			} else {
				this.currentLeaf = this.currentLeaf.getNextNode();
				this.currentKeyIndex = 0;
				this.hasNext = this.currentLeaf != null && this.currentLeaf.getPeek() >= 0;
			}
			return key;
		}

		@Override
		public boolean hasNext() {
			return this.hasNext;
		}
	}

	/**
	 * Iterator that traverses the B+ Tree from right to left.
	 */
	private static class ReverseKeyIterator implements OfInt {
		/**
		 * The current leaf node being traversed.
		 */
		@Nullable private BPlusLeafTreeNode<?> currentLeaf;
		/**
		 * The index of the current key within the current leaf node.
		 */
		private int currentKeyIndex;
		/**
		 * Flag indicating whether there are more elements to traverse.
		 */
		private boolean hasNext;

		public ReverseKeyIterator(@Nonnull BPlusLeafTreeNode<?> leaf) {
			this.currentLeaf = leaf;
			this.currentKeyIndex = this.currentLeaf.getPeek();
			this.hasNext = this.currentLeaf.getPeek() >= 0;
		}

		@Override
		public int nextInt() {
			if (!this.hasNext || this.currentLeaf == null || this.currentKeyIndex < 0) {
				throw new NoSuchElementException("No more elements available");
			}
			final int key = this.currentLeaf.getKeys()[this.currentKeyIndex];
			if (this.currentKeyIndex > 0) {
				this.currentKeyIndex--;
			} else {
				this.currentLeaf = this.currentLeaf.getPreviousNode();
				this.currentKeyIndex = this.currentLeaf == null ? -1 : this.currentLeaf.getPeek();
				this.hasNext = this.currentLeaf != null && this.currentLeaf.getPeek() >= 0;
			}
			return key;
		}

		@Override
		public boolean hasNext() {
			return this.hasNext;
		}
	}

	/**
	 * Iterator that traverses the B+ Tree from left to right.
	 */
	static class ForwardTreeValueIterator<V> implements Iterator<V> {
		/**
		 * The current leaf node being traversed.
		 */
		@Nullable private BPlusLeafTreeNode<V> currentLeaf;
		/**
		 * The index of the current value within the current leaf node.
		 */
		private int currentValueIndex;
		/**
		 * Flag indicating whether there are more elements to traverse.
		 */
		private boolean hasNext;

		public ForwardTreeValueIterator(@Nonnull BPlusLeafTreeNode<V> leaf) {
			this.currentLeaf = leaf;
			this.currentValueIndex = 0;
			this.hasNext = this.currentLeaf.getPeek() >= 0;
		}

		@Override
		public boolean hasNext() {
			return this.hasNext;
		}

		@Override
		public V next() {
			if (!this.hasNext || this.currentLeaf == null || this.currentValueIndex > this.currentLeaf.getPeek()) {
				throw new NoSuchElementException("No more elements available");
			}
			final V value = this.currentLeaf.getValues()[this.currentValueIndex];
			if (this.currentValueIndex < this.currentLeaf.getPeek()) {
				this.currentValueIndex++;
			} else {
				this.currentLeaf = this.currentLeaf.getNextNode();
				this.currentValueIndex = 0;
				this.hasNext = this.currentLeaf != null && this.currentLeaf.getPeek() >= 0;
			}
			return value;
		}
	}

	/**
	 * Iterator that traverses the B+ Tree from right to left.
	 */
	static class ReverseTreeValueIterator<V> implements Iterator<V> {
		/**
		 * The current leaf node being traversed.
		 */
		@Nullable private BPlusLeafTreeNode<V> currentLeaf;
		/**
		 * The index of the current value within the current leaf node.
		 */
		private int currentValueIndex;
		/**
		 * Flag indicating whether there are more elements to traverse.
		 */
		private boolean hasNext;

		public ReverseTreeValueIterator(@Nonnull BPlusLeafTreeNode<V> leaf) {
			this.currentLeaf = leaf;
			this.currentValueIndex = this.currentLeaf.getPeek();
			this.hasNext = this.currentLeaf.getPeek() >= 0;
		}

		@Override
		public boolean hasNext() {
			return this.hasNext;
		}

		@Override
		public V next() {
			if (!this.hasNext || this.currentLeaf == null || this.currentValueIndex < 0) {
				throw new NoSuchElementException("No more elements available");
			}
			final V value = this.currentLeaf.getValues()[this.currentValueIndex];
			if (this.currentValueIndex > 0) {
				this.currentValueIndex--;
			} else {
				this.currentLeaf = this.currentLeaf.getPreviousNode();
				this.currentValueIndex = this.currentLeaf == null ? -1 : this.currentLeaf.getPeek();
				this.hasNext = this.currentLeaf != null && this.currentLeaf.getPeek() >= 0;
			}
			return value;
		}
	}

}
