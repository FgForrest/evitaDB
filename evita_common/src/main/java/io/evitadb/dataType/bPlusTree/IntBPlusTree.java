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


import io.evitadb.dataType.ConsistencySensitiveDataStructure;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.PrimitiveIterator.OfInt;
import java.util.function.UnaryOperator;

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
	 * Updates the keys in the parent nodes of a B+ Tree based on changes in a specific path.
	 * This method propagates changes up the tree as necessary.
	 *
	 * @param cursorWithLevel the cursor representing the path from the root to the node where the changes occurred
	 */
	private static void updateParentKeys(@Nonnull CursorWithLevel cursorWithLevel) {
		BPlusInternalTreeNode immediateParent = cursorWithLevel.parent();
		while (immediateParent != null) {
			if (cursorWithLevel.currentNodeIndex() > 0) {
				immediateParent.updateKeyForNode(cursorWithLevel.currentNodeIndex(), cursorWithLevel.currentNode());
			}
			cursorWithLevel = cursorWithLevel.toParentLevel();
			immediateParent = cursorWithLevel != null ? cursorWithLevel.parent() : null;
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
	 * This method recursively traverses the B+ tree to find the leaf node responsible
	 * for the specified key. It also populates the path traversed with internal nodes.
	 *
	 * @param currentNode The current internal tree node being traversed. Must not be null.
	 * @param key         The key for which the corresponding leaf node is to be found.
	 * @param path        A list to store the sequence of internal nodes visited. Must not be null.
	 */
	private static void addCursorLevels(
		@Nonnull BPlusInternalTreeNode currentNode,
		int key,
		@Nonnull List<CursorLevel> path
	) {
		final NodeWithIndex child = currentNode.search(key);
		path.add(new CursorLevel(currentNode.getChildren(), child.index(), currentNode.getPeek()));
		// if the child is an internal node, continue traversing down the tree
		if (child.node() instanceof BPlusInternalTreeNode childInternalNode) {
			addCursorLevels(childInternalNode, key, path);
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
		this(
			valueBlockSize, minValueBlockSize,
			internalNodeBlockSize, minInternalNodeBlockSize,
			valueType, 0
		);
	}

	private IntBPlusTree(
		int valueBlockSize,
		int minValueBlockSize,
		int internalNodeBlockSize,
		int minInternalNodeBlockSize,
		@Nonnull Class<V> valueType,
		int size
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
		this.size = size;
	}

	/**
	 * Inserts a key-value pair into the B+ tree. If the corresponding leaf node
	 * overflows, it is split to maintain the properties of the tree.
	 *
	 * @param key   the key to be inserted into the B+ tree
	 * @param value the value associated with the key, must not be null
	 */
	public void insert(int key, @Nonnull V value) {
		final Cursor<V> cursor = createCursor(key);
		final BPlusLeafTreeNode<V> leaf = cursor.leafNode();
		this.size += leaf.insert(key, value) ? 1 : 0;

		// Split the leaf node if it exceeds the block size
		if (leaf.isFull()) {
			splitLeafNode(leaf, cursor);
		}
	}

	/**
	 * Updates an existing key-value pair or inserts a new one into the B+ tree.
	 * If the key is already present, the value is updated based on the result of the updater function.
	 * If the key is not present, a new key-value pair is inserted with the value returned by the updater function.
	 * If the leaf node exceeds its block size after insertion, the node is split.
	 *
	 * @param key     the key to update or insert, must not be null
	 * @param updater a function to compute a new value, must not be null
	 */
	public void upsert(int key, @Nonnull UnaryOperator<V> updater) {
		final Cursor<V> cursor = createCursor(key);
		final BPlusLeafTreeNode<V> leaf = cursor.leafNode();

		leaf.getValueWithIndex(key)
			.ifPresentOrElse(
				// update the value on specified index
				result -> leaf.getValues()[result.index()] = updater.apply(result.value()),
				// insert the new value
				() -> {
					this.size += leaf.insert(key, updater.apply(null)) ? 1 : 0;

					// Split the leaf node if it exceeds the block size
					if (leaf.isFull()) {
						splitLeafNode(leaf, cursor);
					}
				}
			);
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
		final Cursor<V> cursor = createCursor(key);
		final BPlusLeafTreeNode<V> leaf = cursor.leafNode();

		final boolean headRemoved = leaf.size() > 1 && key == leaf.getKeys()[0];
		this.size -= leaf.delete(key) ? 1 : 0;

		// if the head of the leaf has been removed, we need to update parent keys accordingly
		if (headRemoved) {
			updateParentKeys(cursor.toCursorWithLevel());
		}

		consolidate(cursor);
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
		final Cursor<V> cursor = createCursor(key);
		return cursor.leafNode().getValue(key);
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
		return new ForwardKeyIterator(createCursor(Integer.MIN_VALUE));
	}

	/**
	 * Returns an iterator that traverses the B+ tree keys from right to left.
	 *
	 * @return an iterator that traverses the B+ tree keys from right to left
	 */
	@Nonnull
	public OfInt keyReverseIterator() {
		return new ReverseKeyIterator(createCursor(Integer.MAX_VALUE));
	}

	/**
	 * Returns an iterator that traverses the B+ tree values from left to right.
	 *
	 * @return an iterator that traverses the B+ tree values from left to right
	 */
	@Nonnull
	public Iterator<V> valueIterator() {
		return new ForwardTreeValueIterator<>(createCursor(Integer.MIN_VALUE));
	}

	/**
	 * Returns an iterator that traverses the B+ tree values from left to right starting from the specified key or
	 * a key that is immediately greater than the specified key. The key may not be present in the tree.
	 *
	 * @param key the key from which to start the iteration
	 * @return an iterator that traverses the B+ tree values from left to right starting from the specified key
	 */
	@Nonnull
	public Iterator<V> greaterOrEqualValueIterator(int key) {
		return new ForwardTreeValueIterator<>(createCursor(key), key);
	}

	/**
	 * Returns an iterator that traverses the B+ tree values from right to left.
	 *
	 * @return an iterator that traverses the B+ tree values from right to left
	 */
	@Nonnull
	public Iterator<V> valueReverseIterator() {
		return new ReverseTreeValueIterator<>(createCursor(Integer.MAX_VALUE));
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
	 * @param cursor  the cursor representing the path from the root to the node to be consolidated
	 */
	private <N extends BPlusTreeNode<N>> void consolidate(@Nonnull Cursor<V> cursor) {
		CursorWithLevel cursorWithLevel = cursor.toCursorWithLevel();

		while (cursorWithLevel != null) {
			final N node = cursorWithLevel.currentNode();
			// leaf node has less than minBlockSize keys, or internal nodes has less than two children
			final boolean underFlowNode = node.keyCount() < this.minValueBlockSize;
			if (underFlowNode) {
				final BPlusInternalTreeNode parent = cursorWithLevel.parent();
				if (parent != null) {
					boolean nodeIsEmpty = node.size() == 0;
					final CursorWithLevel previousNodeCursor = cursorWithLevel.getCursorForPreviousNode();
					// if previous node with current node exists and shares the same parent
					// and we can steal from the left sibling
					if (previousNodeCursor != null) {
						final N previousNode = previousNodeCursor.currentNode();
						if (previousNode.keyCount() > this.minValueBlockSize) {
							// steal half of the surplus data from the left sibling
							node.stealFromLeft(Math.max(1, (previousNode.keyCount() - this.minValueBlockSize) / 2), previousNode);
							// update parent keys
							updateParentKeys(cursorWithLevel);
							return;
						}
					}

					final CursorWithLevel nextNodeCursor = cursorWithLevel.getCursorForNextNode();
					// if next node with current node exists and shares the same parent
					// and we can steal from the right sibling
					if (nextNodeCursor != null) {
						final N nextNode = nextNodeCursor.currentNode();
						if (nextNode.keyCount() > this.minValueBlockSize) {
							// steal half of the surplus data from the right sibling
							node.stealFromRight(Math.max(1, (nextNode.keyCount() - this.minValueBlockSize) / 2), nextNode);
							// update parent keys of the next node - we've stolen its first key
							updateParentKeys(nextNodeCursor);
							// update parent keys, but only if node was empty - which means first key was added
							if (node instanceof BPlusInternalTreeNode || nodeIsEmpty) {
								updateParentKeys(cursorWithLevel);
							}
							return;
						}
					}

					// if previous node with current node can be merged and share the same parent
					if (previousNodeCursor != null) {
						final N previousNode = previousNodeCursor.currentNode();
						if (previousNode.keyCount() + node.keyCount() < this.valueBlockSize) {
							// merge nodes
							node.mergeWithLeft(previousNode);
							// remove the removed child from the parent
							parent.removeChildOnIndex(previousNodeCursor.currentNodeIndex(), previousNodeCursor.currentNodeIndex());
							// update parent keys, previous node has been removed
							updateParentKeys(previousNodeCursor.withReplacedCurrentNode(node));
							// consolidate the parent node
							cursorWithLevel = cursorWithLevel.toParentLevel();
							// continue with parent level
							continue;
						}
					}

					// if next node with current node can be merged and share the same parent
					if (nextNodeCursor != null) {
						final N nextNode = nextNodeCursor.currentNode();
						if (nextNode.keyCount() + node.keyCount() < this.valueBlockSize) {
							// merge nodes
							node.mergeWithRight(nextNode);
							// remove the removed child from the parent
							parent.removeChildOnIndex(nextNodeCursor.currentNodeIndex() - 1, nextNodeCursor.currentNodeIndex());
							// update parent keys, next node has been removed
							updateParentKeys(cursorWithLevel.withReplacedCurrentNode(node));
							// consolidate the parent node
							cursorWithLevel = cursorWithLevel.toParentLevel();
						}
					}
				} else if (node == this.root) {
					if (node.size() == 1 && node instanceof BPlusInternalTreeNode internalTreeNode) {
						// replace the root with the only child
						this.root = internalTreeNode.getChildren()[0];
					} else if (node.size() == 0 && node instanceof BPlusInternalTreeNode) {
						// the root is empty, create a new empty leaf node
						this.root = new BPlusLeafTreeNode<>(this.valueBlockSize, this.valueType);
					}
					cursorWithLevel = null;
				}
			} else {
				// no underflow, we can break the loop
				cursorWithLevel = null;
			}
		}
	}

	/**
	 * Finds the leaf node in the B+ tree that should contain the specified key.
	 * The method begins its search from the root node and traverses down to the leaf node
	 * by following the appropriate child pointers of internal nodes.
	 *
	 * @param key the key to search for within the B+ tree
	 * @return the cursor to the leaf node that is responsible for storing the provided key;
	 * note that the leaf may not actually contain the key - but it is the correct leaf node for accommodating it
	 */
	@Nonnull
	private Cursor<V> createCursor(int key) {
		final ArrayList<CursorLevel> path = new ArrayList<>(this.size() == 0 ? 1 : (int) (Math.log(this.size()) + 1));
		final BPlusTreeNode<?>[] rootSiblings = (BPlusTreeNode<?>[]) Array.newInstance(this.root.getClass(), 1);
		rootSiblings[0] = this.root;
		path.add(new CursorLevel(rootSiblings, 0, 0));
		// if the root is internal node, add the levels to the path until the leaf node is reached
		if (this.root instanceof BPlusInternalTreeNode rootInternalNode) {
			addCursorLevels(rootInternalNode, key, path);

		}
		return new Cursor<>(path);
	}

	/**
	 * Splits a full leaf node into two leaf nodes to maintain the properties of the B+ tree.
	 * If the split occurs at the root, a new root is created.
	 *
	 * @param leaf   The leaf node to be split
	 * @param cursor The cursor representing the path from the root to the leaf node
	 */
	private void splitLeafNode(
		@Nonnull BPlusLeafTreeNode<V> leaf,
		@Nonnull Cursor<V> cursor
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
				cursor.toCursorWithLevel()
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
	 * @param cursor   The cursor representing the path from the root to the original node.
	 */
	private void replaceNodeInParentInternalNode(
		@Nonnull BPlusTreeNode<?> original,
		@Nonnull BPlusTreeNode<?> left,
		@Nonnull BPlusTreeNode<?> right,
		int key,
		@Nonnull CursorWithLevel cursor
	) {
		final BPlusInternalTreeNode parent = cursor.parent();

		Assert.notNull(parent, "Parent node must not be null.");
		parent.adaptToLeafSplit(key, original, left, right);

		if (parent.isFull()) {
			splitInternalNode(parent, new CursorWithLevel(cursor.path(), cursor.level() - 1));
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
	 * @param cursor   The cursor representing the path from the root to the internal node being split.
	 */
	private void splitInternalNode(
		@Nonnull BPlusInternalTreeNode internal,
		@Nonnull CursorWithLevel cursor
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
				cursor
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
		 * Steals a specified number of values from the end of the left sibling node.
		 *
		 * @param numberOfTailValues the number of values to steal from the left sibling node.
		 * @param previousNode       the left sibling node from which to steal values.
		 */
		void stealFromLeft(int numberOfTailValues, @Nonnull N previousNode);

		/**
		 * Steals a specified number of values from the start of the right sibling node.
		 *
		 * @param numberOfHeadValues the number of values to steal from the right sibling node.
		 * @param nextNode           the right sibling node from which to steal values.
		 */
		void stealFromRight(int numberOfHeadValues, @Nonnull N nextNode);

		/**
		 * Merges the current leaf node with the left sibling leaf node.
		 */
		void mergeWithLeft(@Nonnull N previousNode);

		/**
		 * Merges the current leaf node with the right sibling leaf node.
		 */
		void mergeWithRight(@Nonnull N nextNode);

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
		public void stealFromLeft(int numberOfTailValues, @Nonnull BPlusInternalTreeNode previousNode) {
			Assert.isPremiseValid(numberOfTailValues > 0, "Number of tail values to steal must be positive!");
			// we preserve all the current node children
			System.arraycopy(this.children, 0, this.children, numberOfTailValues, this.peek + 1);
			// then move the children from the previous node
			System.arraycopy(previousNode.getChildren(), previousNode.size() - numberOfTailValues, this.children, 0, numberOfTailValues);
			// we need to preserve all the current node keys
			System.arraycopy(this.keys, 0, this.keys, numberOfTailValues, this.peek);
			// our original first child newly produces its own key
			this.keys[numberOfTailValues - 1] = this.children[numberOfTailValues].getLeftBoundaryKey();
			// and now we can copy the keys from the previous node - but except the first one
			System.arraycopy(previousNode.getKeys(), previousNode.keyCount() - numberOfTailValues + 1, this.keys, 0, numberOfTailValues - 1);
			// and update the peek indexes
			this.peek += numberOfTailValues;
			previousNode.setPeek(previousNode.getPeek() - numberOfTailValues);
		}

		@Override
		public void stealFromRight(int numberOfHeadValues, @Nonnull BPlusInternalTreeNode nextNode) {
			Assert.isPremiseValid(numberOfHeadValues > 0, "Number of head values to steal must be positive!");

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
		public void mergeWithLeft(@Nonnull BPlusInternalTreeNode previousNode) {
			final int mergePeek = previousNode.getPeek();
			System.arraycopy(this.keys, 0, this.keys, mergePeek + 1, this.peek);
			this.keys[mergePeek] = this.children[0].getLeftBoundaryKey();
			System.arraycopy(this.children, 0, this.children, mergePeek + 1, this.peek + 1);
			System.arraycopy(previousNode.getKeys(), 0, this.keys, 0, mergePeek);
			System.arraycopy(previousNode.getChildren(), 0, this.children, 0, mergePeek + 1);
			this.peek += mergePeek + 1;
			previousNode.setPeek(-1);
		}

		@Override
		public void mergeWithRight(@Nonnull BPlusInternalTreeNode nextNode) {
			final int mergePeek = nextNode.getPeek();
			System.arraycopy(nextNode.getChildren(), 0, this.children, this.peek + 1, mergePeek + 1);
			final int offset;
			if (this.peek >= 0) {
				this.keys[this.peek] = nextNode.getChildren()[0].getLeftBoundaryKey();
				offset = 1;
			} else {
				offset = 0;
			}
			System.arraycopy(nextNode.getKeys(), 0, this.keys, this.peek + offset, mergePeek);
			this.peek += mergePeek + 1;
			nextNode.setPeek(-1);
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
		public NodeWithIndex search(int key) {
			final InsertionPosition insertionPosition = computeInsertPositionOfIntInOrderedArray(key, this.keys, 0, this.peek);
			final int thePosition = insertionPosition.alreadyPresent() ?
				insertionPosition.position() + 1 : insertionPosition.position();
			return new NodeWithIndex(thePosition, this.children[thePosition]);
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
		public void removeChildOnIndex(int keyIndex, int childIndex) {
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
		public void stealFromLeft(int numberOfTailValues, @Nonnull BPlusLeafTreeNode<V> previousNode) {
			Assert.isPremiseValid(numberOfTailValues > 0, "Number of tail values to steal must be positive!");
			System.arraycopy(this.keys, 0, this.keys, numberOfTailValues, this.peek + 1);
			System.arraycopy(this.values, 0, this.values, numberOfTailValues, this.peek + 1);
			System.arraycopy(previousNode.getKeys(), previousNode.size() - numberOfTailValues, this.keys, 0, numberOfTailValues);
			System.arraycopy(previousNode.getValues(), previousNode.size() - numberOfTailValues, this.values, 0, numberOfTailValues);
			this.peek += numberOfTailValues;
			previousNode.setPeek(previousNode.getPeek() - numberOfTailValues);
		}

		@Override
		public void stealFromRight(int numberOfHeadValues, @Nonnull BPlusLeafTreeNode<V> nextNode) {
			Assert.isPremiseValid(numberOfHeadValues > 0, "Number of head values to steal must be positive!");
			System.arraycopy(nextNode.getKeys(), 0, this.keys, this.peek + 1, numberOfHeadValues);
			System.arraycopy(nextNode.getValues(), 0, this.values, this.peek + 1, numberOfHeadValues);
			System.arraycopy(nextNode.getKeys(), numberOfHeadValues, nextNode.getKeys(), 0, nextNode.size() - numberOfHeadValues);
			System.arraycopy(nextNode.getValues(), numberOfHeadValues, nextNode.getValues(), 0, nextNode.size() - numberOfHeadValues);
			nextNode.setPeek(nextNode.getPeek() - numberOfHeadValues);
			this.peek += numberOfHeadValues;
		}

		@Override
		public void mergeWithLeft(@Nonnull BPlusLeafTreeNode<V> previousNode) {
			final int mergePeek = previousNode.getPeek();
			System.arraycopy(this.keys, 0, this.keys, mergePeek + 1, this.peek + 1);
			System.arraycopy(this.values, 0, this.values, mergePeek + 1, this.peek + 1);
			System.arraycopy(previousNode.getKeys(), 0, this.keys, 0, mergePeek + 1);
			System.arraycopy(previousNode.getValues(), 0, this.values, 0, mergePeek + 1);
			this.peek += mergePeek + 1;
			previousNode.setPeek(-1);
		}

		@Override
		public void mergeWithRight(@Nonnull BPlusLeafTreeNode<V> nextNode) {
			final int mergePeek = nextNode.getPeek();
			System.arraycopy(nextNode.getKeys(), 0, this.keys, this.peek + 1, mergePeek + 1);
			System.arraycopy(nextNode.getValues(), 0, this.values, this.peek + 1, mergePeek + 1);
			this.peek += mergePeek + 1;
			nextNode.setPeek(-1);
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
		public Optional<V> getValue(int key) {
			final InsertionPosition insertionPosition = computeInsertPositionOfIntInOrderedArray(key, this.keys, 0, this.peek + 1);
			return insertionPosition.alreadyPresent() ?
				Optional.of(this.values[insertionPosition.position()]) : Optional.empty();
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
		public Optional<ValueWithIndex<V>> getValueWithIndex(int key) {
			final InsertionPosition insertionPosition = computeInsertPositionOfIntInOrderedArray(key, this.keys, 0, this.peek + 1);
			return insertionPosition.alreadyPresent() ?
				Optional.of(new ValueWithIndex<>(insertionPosition.position(), this.values[insertionPosition.position()])) : Optional.empty();
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
	 * Represents a cursor for navigating a B+ tree structure with its specific level,
	 * maintaining the state of the current node and its path.
	 *
	 * This record is intended to support operations that perform traversal, modification,
	 * or retrieval within the B+ tree by maintaining references to the current level,
	 * node, and its hierarchical structure.
	 *
	 * @param path The path representing the sequence of nodes traversed to reach the current node.
	 * @param level The current level in the tree where the cursor is positioned.
	 * @param currentNodeOfGenericType The current node at the given level. The node can be passed from the outside
	 *                                 if the current node in the path was replaced by another instance.
	 */
	private record CursorWithLevel(
		@Nonnull List<CursorLevel> path,
		int level,
		@Nonnull BPlusTreeNode<?> currentNodeOfGenericType
	) {

		public CursorWithLevel(@Nonnull List<CursorLevel> path, int level) {
			this(path, level, path.get(level).currentNode());
		}

		/**
		 * Retrieves the current node of the type parameter in the B+ Tree structure.
		 * The current node might represent a replaced node in the structure.
		 *
		 * @return the current node of the generic type {@code N} in the B+ Tree.
		 */
		@Nonnull
		public <N extends BPlusTreeNode<N>> N currentNode() {
			//noinspection unchecked
			return (N) this.currentNodeOfGenericType;
		}

		/**
		 * Retrieves the index of the current node in the path at the current level.
		 *
		 * @return the index of the current node in the path at the specified level.
		 */
		public int currentNodeIndex() {
			return this.path.get(this.level).index();
		}

		/**
		 * Retrieves the parent node of the current node in the B+ Tree structure, if it exists.
		 *
		 * @return the parent node of type {@code BPlusInternalTreeNode} if the current level is greater than 0,
		 *         otherwise {@code null}.
		 */
		@Nullable
		public BPlusInternalTreeNode parent() {
			if (this.level > 0) {
				final CursorLevel parentLevel = this.path.get(this.level - 1);
				return (BPlusInternalTreeNode) parentLevel.siblings()[parentLevel.index()];
			} else {
				return null;
			}
		}

		/**
		 * Creates a new instance of {@code CursorWithLevel} representing the parent level
		 * by reducing the current level by one, if the current level is greater than 0.
		 * If the current level is 0, returns {@code null}.
		 *
		 * @return a new {@code CursorWithLevel} instance at the parent level
		 *         if the current level is greater than 0, otherwise {@code null}.
		 */
		@Nullable
		public CursorWithLevel toParentLevel() {
			return this.level > 0 ? new CursorWithLevel(this.path(), this.level - 1) : null;
		}

		/**
		 * Retrieves a cursor representing the previous node at the current level in the B+ Tree structure.
		 * If there is no previous node at the current level (i.e., the current node is the first sibling),
		 * the method returns {@code null}.
		 *
		 * This method calculates the previous node by decrementing the current index
		 * and reconstructing the cursor path to ensure all levels below the current level
		 * point to the appropriate descendants of the newly identified previous node.
		 *
		 * Method cannot resolve the previous node over multiple parents - it only works on the current level.
		 *
		 * @return a {@code CursorWithLevel} instance pointing to the previous node if it exists,
		 *         otherwise {@code null}.
		 */
		@Nullable
		public CursorWithLevel getCursorForPreviousNode() {
			final CursorLevel cursorLevel = this.path.get(this.level);
			if (cursorLevel.index() > 0) {
				// easy case - we can just move to the previous sibling
				final List<CursorLevel> replacedPath = new ArrayList<>(this.path);
				// we need to replace all levels from the current level up to the original one with the new path
				CursorLevel newCursorLevel = new CursorLevel(
					cursorLevel.siblings(),
					cursorLevel.index() - 1,
					cursorLevel.peek()
				);
				replacedPath.set(this.level, newCursorLevel);
				// all levels below, will point to the last child of the new cursor level
				for (int i = this.level + 1; i < this.path().size(); i++) {
					final BPlusInternalTreeNode currentNode = newCursorLevel.currentNode();
					newCursorLevel = new CursorLevel(
						currentNode.getChildren(),
						currentNode.getPeek(),
						currentNode.getPeek()
					);
					replacedPath.set(i, newCursorLevel);
				}
				// return new cursor with the replaced path
				return new CursorWithLevel(
					replacedPath,
					this.level
				);
			} else {
				return null;
			}
		}

		/**
		 * Retrieves a cursor representing the next node at the current level in the B+ Tree structure.
		 * If the current node is not the last sibling at the current level, the method calculates the
		 * next node by incrementing the current index and reconstructing the cursor path for all subsequent levels.
		 * The reconstruction ensures that all levels below the current level point to the appropriate
		 * descendants of the newly identified sibling node.
		 *
		 * If the current node is the last sibling at the current level, the method returns {@code null}.
		 *
		 * Method cannot resolve the next node over multiple parents - it only works on the current level.
		 *
		 * @return a {@code CursorWithLevel} instance pointing to the next node if it exists,
		 *         otherwise {@code null}.
		 */
		@Nullable
		public CursorWithLevel getCursorForNextNode() {
			final CursorLevel cursorLevel = this.path.get(this.level);
			if (cursorLevel.index() < cursorLevel.peek()) {
				// easy case - we can just move to the next sibling
				final List<CursorLevel> replacedPath = new ArrayList<>(this.path);
				// we need to replace all levels from the current level up to the original one with the new path
				CursorLevel newCursorLevel = new CursorLevel(
					cursorLevel.siblings(),
					cursorLevel.index() + 1,
					cursorLevel.peek()
				);
				replacedPath.set(this.level, newCursorLevel);
				// all levels below, will point to the first child of the new cursor level
				for (int i = this.level + 1; i < this.path.size(); i++) {
					final BPlusInternalTreeNode currentNode = newCursorLevel.currentNode();
					newCursorLevel = new CursorLevel(currentNode.getChildren(), 0, currentNode.getPeek());
					replacedPath.set(i, newCursorLevel);
				}
				// return new cursor with the replaced path
				return new CursorWithLevel(
					replacedPath,
					this.level
				);
			} else {
				return null;
			}
		}

		/**
		 * Creates a new instance of {@code CursorWithLevel} with the same path and level but
		 * with the current node replaced by the provided node.
		 *
		 * @param node the new current node to replace the existing one. It must not be null and must
		 *             satisfy the generic constraints of {@code BPlusTreeNode<N>}.
		 * @return a new {@code CursorWithLevel} instance with the specified node as the current node
		 *         while retaining the original path and level.
		 */
		@Nonnull
		public <N extends BPlusTreeNode<N>> CursorWithLevel withReplacedCurrentNode(@Nonnull N node) {
			return new CursorWithLevel(
				this.path,
				this.level,
				node
			);
		}

	}

	/**
	 * Represents a position or path within a structure, specifically within a nested
	 * or hierarchical tree-like structure such as a B+ tree. This class maintains a
	 * path to a specific location within the tree through a list of CursorLevel objects.
	 *
	 * Cursor always points to a leaf node in the B+ tree structure and contains full path
	 * to the leaf node. The path is represented by a list of CursorLevel objects, where each
	 * CursorLevel object contains an array of sibling nodes at the same level, the index of
	 * the current node within the siblings array, and the peek index of the current node.
	 *
	 * @param path A non-null list of CursorLevel objects representing the path to a
	 *             specific node in the tree structure.
	 * @param <V>  The type of value stored in the B+ tree nodes.
	 */
	private record Cursor<V>(
		@Nonnull List<CursorLevel> path
	) {

		/**
		 * Retrieves the leaf node of the B+ tree at the deepest level of the current path.
		 * This method accesses the last `CursorLevel` in the path to identify and return the
		 * corresponding leaf node in the structure.
		 *
		 * @return The leaf node of the B+ tree at the location specified by the current path.
		 *         Guaranteed to be non-null.
		 */
		@Nonnull
		public BPlusLeafTreeNode<V> leafNode() {
			final CursorLevel deepestLevel = this.path.get(this.path.size() - 1);
			//noinspection unchecked
			return (BPlusLeafTreeNode<V>) deepestLevel.siblings()[deepestLevel.index()];
		}

		/**
		 * Converts the current Cursor instance into a CursorWithLevel object.
		 * The resulting CursorWithLevel encapsulates the same path as the current Cursor
		 * along with the level information of the deepest node in the structure.
		 *
		 * @return A new CursorWithLevel object containing the path and the index of the
		 *         deepest level in the path. Guaranteed to be non-null.
		 */
		@Nonnull
		public CursorWithLevel toCursorWithLevel() {
			return new CursorWithLevel(this.path, this.path.size() - 1);
		}
	}

	/**
	 * A record representing the current level of a cursor within a BPlusTree structure.
	 * Stores references to sibling nodes at the current level and tracks the index
	 * of the current node and a peek index in the siblings array (last meaningful index).
	 *
	 * @param siblings An array of sibling nodes at the current level in the B+ tree structure.
	 * @param index    The index of the current node within the siblings array, must be always > 0 and <= peek.
	 * @param peek     The last meaningful index in the siblings array.
	 */
	private record CursorLevel(
		@Nonnull BPlusTreeNode<?>[] siblings,
		int index,
		int peek
	) {

		/**
		 * Retrieves the current node in the siblings array at the specified index.
		 *
		 * @param <N> the type of the BPlusTreeNode
		 * @return the current BPlusTreeNode of type N at the specified index in the siblings array
		 */
		@Nonnull
		public <N extends BPlusTreeNode<N>> N currentNode() {
			//noinspection unchecked
			return (N) this.siblings[index];
		}
	}

	/**
	 * Represents a node along with its associated index. This class is a record that holds an integer index
	 * and a non-nullable node.
	 *
	 * @param index the index associated with the value
	 * @param node  the non-null node associated with the index
	 */
	private record NodeWithIndex(
		int index,
		@Nonnull BPlusTreeNode<?> node
	) {
	}

	/**
	 * Represents a value along with its associated index. This class is a record that holds an integer index
	 * and a non-nullable value.
	 *
	 * @param <V>   the type of the value
	 * @param index the index associated with the value
	 * @param value the non-null value associated with the index
	 */
	private record ValueWithIndex<V>(
		int index,
		@Nonnull V value
	) {
	}

	/**
	 * Iterator that traverses the B+ Tree from left to right.
	 */
	private static class ForwardKeyIterator implements OfInt {
		/**
		 * Array of arrays representing siblings on each level of the path.
		 */
		@Nonnull private final BPlusTreeNode<?>[][] path;
		/**
		 * The index of the current key on particular path.
		 */
		@Nonnull private final int[] pathIndex;
		/**
		 * The peek index of each sibling array on the path.
		 */
		@Nonnull private final int[] pathPeeks;
		/**
		 * The index of the current key within the current leaf node.
		 */
		private int currentKeyIndex;
		/**
		 * Flag indicating whether there are more elements to traverse.
		 */
		private boolean hasNext;

		public ForwardKeyIterator(@Nonnull Cursor<?> cursor) {
			this.path = new BPlusTreeNode[cursor.path().size()][];
			this.pathIndex = new int[this.path.length];
			this.pathPeeks = new int[this.path.length];
			for (int i = 0; i < cursor.path().size(); i++) {
				final CursorLevel cursorLevel = cursor.path().get(i);
				this.path[i] = cursorLevel.siblings();
				this.pathIndex[i] = cursorLevel.index();
				this.pathPeeks[i] = cursorLevel.peek();
			}
			this.currentKeyIndex = 0;
			this.hasNext = this.path[this.path.length - 1][this.pathIndex[this.pathIndex.length - 1]].getPeek() >= 0;
		}

		@Override
		public int nextInt() {
			if (!this.hasNext) {
				throw new NoSuchElementException("No more elements available");
			}

			final BPlusTreeNode<?> currentLeaf = this.path[this.path.length - 1][this.pathIndex[this.pathIndex.length - 1]];
			final int key = currentLeaf.getKeys()[this.currentKeyIndex];

			if (this.currentKeyIndex < currentLeaf.getPeek()) {
				// easy path, there is another key in current leaf
				this.currentKeyIndex++;
			} else {
				// we need to traverse up the path to find the next sibling
				int level = this.pathIndex.length - 1;
				BPlusTreeNode<?>[] parentLevel = this.path[level];
				while (parentLevel != null) {
					// if parent has index greater than zero
					if (this.pathIndex[level] < this.pathPeeks[level]) {
						// we found the parent that has a next sibling - so move the index
						this.pathIndex[level] = this.pathIndex[level] + 1;
						BPlusTreeNode<?> currentNode = this.path[level][this.pathIndex[level]];
						// all levels below, will point to the first child of the new cursor level
						for (int i = level + 1; i <= this.path.length - 1; i++) {
							Assert.isPremiseValid(currentNode instanceof BPlusInternalTreeNode, "Internal node expected!");
							this.path[i] = ((BPlusInternalTreeNode)currentNode).getChildren();
							this.pathIndex[i] = 0;
							this.pathPeeks[i] = currentNode.getPeek();
							currentNode = this.path[i][0];
						}
						this.currentKeyIndex = 0;
						this.hasNext = true;
						return key;
					} else {
						// we need to continue search with the parent of the parent
						level--;
						parentLevel = level > 0 ? this.path[level] : null;
					}
				}
				this.hasNext = false;
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
		 * Array of arrays representing siblings on each level of the path.
		 */
		@Nonnull private final BPlusTreeNode<?>[][] path;
		/**
		 * The index of the current key on particular path.
		 */
		@Nonnull private final int[] pathIndex;
		/**
		 * The index of the current key within the current leaf node.
		 */
		private int currentKeyIndex;
		/**
		 * Flag indicating whether there are more elements to traverse.
		 */
		private boolean hasNext;

		public ReverseKeyIterator(@Nonnull Cursor<?> cursor) {
			this.path = new BPlusTreeNode[cursor.path().size()][];
			this.pathIndex = new int[this.path.length];
			for (int i = 0; i < cursor.path().size(); i++) {
				final CursorLevel cursorLevel = cursor.path().get(i);
				this.path[i] = cursorLevel.siblings();
				this.pathIndex[i] = cursorLevel.index();
			}
			this.currentKeyIndex = this.path[this.path.length - 1][this.pathIndex[this.pathIndex.length - 1]].getPeek();
			this.hasNext = this.currentKeyIndex >= 0;
		}

		@Override
		public int nextInt() {
			if (!this.hasNext) {
				throw new NoSuchElementException("No more elements available");
			}

			final BPlusTreeNode<?> currentLeaf = this.path[this.path.length - 1][this.pathIndex[this.pathIndex.length - 1]];
			final int key = currentLeaf.getKeys()[this.currentKeyIndex];

			if (this.currentKeyIndex > 0) {
				// easy path, there is another key in current leaf
				this.currentKeyIndex--;
			} else {
				// we need to traverse up the path to find the next sibling
				int level = this.pathIndex.length - 1;
				BPlusTreeNode<?>[] parentLevel = this.path[level];
				while (parentLevel != null) {
					// if parent has index greater than zero
					if (this.pathIndex[level] > 0) {
						// we found the parent that has a next sibling - so move the index
						this.pathIndex[level] = this.pathIndex[level] - 1;
						BPlusTreeNode<?> currentNode = this.path[level][this.pathIndex[level]];
						// all levels below, will point to the first child of the new cursor level
						for (int i = level + 1; i <= this.pathIndex.length - 1; i++) {
							Assert.isPremiseValid(currentNode instanceof BPlusInternalTreeNode, "Internal node expected!");
							this.path[i] = ((BPlusInternalTreeNode)currentNode).getChildren();
							this.pathIndex[i] = currentNode.getPeek();
							currentNode = this.path[i][this.pathIndex[i]];
						}
						this.hasNext = true;
						this.currentKeyIndex = this.path[this.path.length - 1][this.pathIndex[this.pathIndex.length - 1]].getPeek();
						return key;
					} else {
						// we need to continue search with the parent of the parent
						level--;
						parentLevel = level > 0 ? this.path[level] : null;
					}
				}
				this.hasNext = false;
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
		 * Array of arrays representing siblings on each level of the path.
		 */
		@Nonnull private final BPlusTreeNode<?>[][] path;
		/**
		 * The index of the current key on particular path.
		 */
		@Nonnull private final int[] pathIndex;
		/**
		 * The peek index of each sibling array on the path.
		 */
		@Nonnull private final int[] pathPeeks;
		/**
		 * The index of the current value within the current leaf node.
		 */
		private int currentValueIndex;
		/**
		 * Flag indicating whether there are more elements to traverse.
		 */
		private boolean hasNext;

		public ForwardTreeValueIterator(@Nonnull Cursor<V> cursor) {
			this.path = new BPlusTreeNode[cursor.path().size()][];
			this.pathIndex = new int[this.path.length];
			this.pathPeeks = new int[this.path.length];
			for (int i = 0; i < cursor.path().size(); i++) {
				final CursorLevel cursorLevel = cursor.path().get(i);
				this.path[i] = cursorLevel.siblings();
				this.pathIndex[i] = cursorLevel.index();
				this.pathPeeks[i] = cursorLevel.peek();
			}
			this.currentValueIndex = 0;
			this.hasNext = this.path[this.path.length - 1][this.pathIndex[this.pathIndex.length - 1]].getPeek() >= 0;
		}

		public ForwardTreeValueIterator(@Nonnull Cursor<V> cursor, int key) {
			this.path = new BPlusTreeNode[cursor.path().size()][];
			this.pathIndex = new int[this.path.length];
			this.pathPeeks = new int[this.path.length];
			for (int i = 0; i < cursor.path().size(); i++) {
				final CursorLevel cursorLevel = cursor.path().get(i);
				this.path[i] = cursorLevel.siblings();
				this.pathIndex[i] = cursorLevel.index();
				this.pathPeeks[i] = cursorLevel.peek();
			}
			final InsertionPosition insertionPosition = computeInsertPositionOfIntInOrderedArray(key, cursor.leafNode().getKeys(), 0, cursor.leafNode().size());
			this.currentValueIndex = insertionPosition.position();
			this.hasNext = this.path[this.path.length - 1][this.pathIndex[this.pathIndex.length - 1]].getPeek() >= 0;
		}

		@Override
		public V next() {
			if (!this.hasNext) {
				throw new NoSuchElementException("No more elements available");
			}

			//noinspection unchecked
			final BPlusLeafTreeNode<V> currentLeaf = (BPlusLeafTreeNode<V>) this.path[this.path.length - 1][this.pathIndex[this.pathIndex.length - 1]];
			final V value = currentLeaf.getValues()[this.currentValueIndex];

			if (this.currentValueIndex < currentLeaf.getPeek()) {
				// easy path, there is another value in current leaf
				this.currentValueIndex++;
			} else {
				// we need to traverse up the path to find the next sibling
				int level = this.pathIndex.length - 1;
				BPlusTreeNode<?>[] parentLevel = this.path[level];
				while (parentLevel != null) {
					// if parent has index greater than zero
					if (this.pathIndex[level] < this.pathPeeks[level]) {
						// we found the parent that has a next sibling - so move the index
						this.pathIndex[level] = this.pathIndex[level] + 1;
						BPlusTreeNode<?> currentNode = this.path[level][this.pathIndex[level]];
						// all levels below, will point to the first child of the new cursor level
						for (int i = level + 1; i <= this.path.length - 1; i++) {
							Assert.isPremiseValid(currentNode instanceof BPlusInternalTreeNode, "Internal node expected!");
							this.path[i] = ((BPlusInternalTreeNode)currentNode).getChildren();
							this.pathIndex[i] = 0;
							this.pathPeeks[i] = currentNode.getPeek();
							currentNode = this.path[i][0];
						}
						this.currentValueIndex = 0;
						this.hasNext = true;
						return value;
					} else {
						// we need to continue search with the parent of the parent
						level--;
						parentLevel = level > 0 ? this.path[level] : null;
					}
				}
				this.hasNext = false;
			}
			return value;
		}

		@Override
		public boolean hasNext() {
			return this.hasNext;
		}
	}

	/**
	 * Iterator that traverses the B+ Tree from right to left.
	 */
	static class ReverseTreeValueIterator<V> implements Iterator<V> {
		/**
		 * Array of arrays representing siblings on each level of the path.
		 */
		@Nonnull private final BPlusTreeNode<?>[][] path;
		/**
		 * The index of the current key on particular path.
		 */
		@Nonnull private final int[] pathIndex;
		/**
		 * The index of the current value within the current leaf node.
		 */
		private int currentValueIndex;
		/**
		 * Flag indicating whether there are more elements to traverse.
		 */
		private boolean hasNext;

		public ReverseTreeValueIterator(@Nonnull Cursor<V> cursor) {
			this.path = new BPlusTreeNode[cursor.path().size()][];
			this.pathIndex = new int[this.path.length];
			for (int i = 0; i < cursor.path().size(); i++) {
				final CursorLevel cursorLevel = cursor.path().get(i);
				this.path[i] = cursorLevel.siblings();
				this.pathIndex[i] = cursorLevel.index();
			}
			this.currentValueIndex = this.path[this.path.length - 1][this.pathIndex[this.pathIndex.length - 1]].getPeek();
			this.hasNext = this.currentValueIndex >= 0;
		}

		@Override
		public V next() {
			if (!this.hasNext) {
				throw new NoSuchElementException("No more elements available");
			}

			//noinspection unchecked
			final BPlusLeafTreeNode<V> currentLeaf = (BPlusLeafTreeNode<V>) this.path[this.path.length - 1][this.pathIndex[this.pathIndex.length - 1]];
			final V value = currentLeaf.getValues()[this.currentValueIndex];

			if (this.currentValueIndex > 0) {
				// easy path, there is another value in current leaf
				this.currentValueIndex--;
			} else {
				// we need to traverse up the path to find the next sibling
				int level = this.pathIndex.length - 1;
				BPlusTreeNode<?>[] parentLevel = this.path[level];
				while (parentLevel != null) {
					// if parent has index greater than zero
					if (this.pathIndex[level] > 0) {
						// we found the parent that has a next sibling - so move the index
						this.pathIndex[level] = this.pathIndex[level] - 1;
						// all levels below, will point to the first child of the new cursor level
						BPlusTreeNode<?> currentNode = this.path[level][this.pathIndex[level]];
						// all levels below, will point to the first child of the new cursor level
						for (int i = level + 1; i <= this.pathIndex.length - 1; i++) {
							Assert.isPremiseValid(currentNode instanceof BPlusInternalTreeNode, "Internal node expected!");
							this.path[i] = ((BPlusInternalTreeNode)currentNode).getChildren();
							this.pathIndex[i] = currentNode.getPeek();
							currentNode = this.path[i][this.pathIndex[i]];
						}
						this.hasNext = true;
						this.currentValueIndex = this.path[this.path.length - 1][this.pathIndex[this.pathIndex.length - 1]].getPeek();
						return value;
					} else {
						// we need to continue search with the parent of the parent
						level--;
						parentLevel = level > 0 ? this.path[level] : null;
					}
				}
				this.hasNext = false;
			}
			return value;
		}

		@Override
		public boolean hasNext() {
			return this.hasNext;
		}
	}

}
