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

package io.evitadb.index.bPlusTree;


import io.evitadb.core.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.dataType.ConsistencySensitiveDataStructure;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.function.IntObjBiFunction;
import io.evitadb.index.reference.TransactionalReference;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static io.evitadb.utils.ArrayUtils.InsertionPosition;
import static io.evitadb.utils.ArrayUtils.computeInsertPositionOfObjInOrderedArray;
import static io.evitadb.utils.ArrayUtils.insertRecordIntoSameArrayOnIndex;
import static io.evitadb.utils.ArrayUtils.removeRecordFromSameArrayOnIndex;
import static java.util.Optional.ofNullable;

/**
 * Represents a B+ Tree data structure specifically designed for generic comparable keys and generic values.
 * The tree is balanced and allows for efficient insertion, deletion, and search operations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@NotThreadSafe
public class TransactionalObjectBPlusTree<K extends Comparable<K>, V> implements
	TransactionalLayerProducer<Void, TransactionalObjectBPlusTree<K, V>>,
	Serializable,
	ConsistencySensitiveDataStructure {
	@Serial private static final long serialVersionUID = -6130739073840635252L;
	private static final int DEFAULT_VALUE_BLOCK_SIZE = 64;
	private static final int DEFAULT_MIN_VALUE_BLOCK_SIZE = DEFAULT_VALUE_BLOCK_SIZE / 2 - 1;
	private static final int DEFAULT_INTERNAL_NODE_BLOCK_SIZE = DEFAULT_VALUE_BLOCK_SIZE / 2 - 1;
	private static final int DEFAULT_MIN_INTERNAL_NODE_BLOCK_SIZE = (int) (Math.ceil((float) DEFAULT_INTERNAL_NODE_BLOCK_SIZE / 2.0) - 1);
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
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
	 * The type of the keys stored in the tree.
	 */
	@Getter private final Class<K> keyType;
	/**
	 * The type of the values stored in the tree.
	 */
	@Getter private final Class<V> valueType;
	/**
	 * Operator that wraps the values in a transactional layer.
	 */
	private final Function<Object, V> transactionalLayerWrapper;
	/**
	 * Number of elements in the tree.
	 */
	private final TransactionalReference<Integer> size;
	/**
	 * Root node of the tree.
	 */
	private final TransactionalReference<BPlusTreeNode<K, ?>> root;

	/**
	 * Returns the class type of the generic TransactionalObjectBPlusTree with the specified key and value types.
	 * This method may be necessary if you need the proper generic class for constructor of other classes.
	 *
	 * @param <K> the type of keys in the TransactionalObjectBPlusTree, which must extend Comparable
	 * @param <V> the type of values in the TransactionalObjectBPlusTree
	 * @return the Class object representing the type TransactionalObjectBPlusTree with the specified generic parameters
	 */
	@Nonnull
	public static <K extends Comparable<K>, V> Class<TransactionalObjectBPlusTree<K, V>> genericClass() {
		//noinspection unchecked
		return (Class<TransactionalObjectBPlusTree<K, V>>) (Class<?>) TransactionalObjectBPlusTree.class;
	}

	/**
	 * Updates the keys in the parent nodes of a B+ Tree based on changes in a specific path.
	 * This method propagates changes up the tree as necessary.
	 *
	 * @param cursorWithLevel the cursor representing the path from the root to the node where the changes occurred
	 */
	private static <M extends Comparable<M>> void updateParentKeys(@Nonnull CursorWithLevel<M> cursorWithLevel) {
		BPlusInternalTreeNode<M> immediateParent = cursorWithLevel.parent();
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
	private static int verifyAndReturnHeight(@Nonnull TransactionalObjectBPlusTree<?, ?> tree) {
		final BPlusTreeNode<?, ?> root = tree.getRoot();
		if (root instanceof BPlusInternalTreeNode<?> internalNode) {
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
	private static void verifyHeightOfAllChildren(@Nonnull BPlusTreeNode<?, ?> node, int nodeHeight, int maximalHeight) {
		if (node instanceof BPlusInternalTreeNode<?> internalNode) {
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
	private static int verifyAndReturnHeight(@Nonnull BPlusInternalTreeNode<?> node, int currentHeight) {
		final BPlusTreeNode<?, ?> child = node.getChildren()[0];
		if (child instanceof BPlusInternalTreeNode<?> internalChild) {
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
	private static void verifyInternalNodeKeys(@Nonnull BPlusTreeNode<?, ?> node) {
		if (node instanceof BPlusInternalTreeNode<?> internalNode) {
			final Object[] keys = internalNode.getKeys();
			final BPlusTreeNode<?, ?>[] children = internalNode.getChildren();
			if (internalNode.getPeek() >= 0) {
				verifyInternalNodeKeys(children[0]);
			}
			for (int i = 0; i < internalNode.getPeek(); i++) {
				final Object key = keys[i];
				final BPlusTreeNode<?, ?> child = children[i + 1];
				if (child instanceof BPlusInternalTreeNode<?> childInternalNode) {
					if (!childInternalNode.getLeftBoundaryKey().equals(key)) {
						throw new IllegalStateException(
							"Internal node " + childInternalNode + " has a different left boundary key (" +
								childInternalNode.getLeftBoundaryKey() + ") than the internal node key (" + key + ")!"
						);
					}
					verifyInternalNodeKeys(childInternalNode);
				} else if (child instanceof BPlusLeafTreeNode<?, ?> childLeafNode) {
					if (!childLeafNode.getKeys()[0].equals(key)) {
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
	private static void verifyMinimalCountOfValuesInNodes(@Nonnull BPlusTreeNode<?, ?> node, int minValueBlockSize, int minInternalNodeBlockSize, boolean isRoot) {
		if (node instanceof BPlusInternalTreeNode<?> internalNode && !isRoot) {
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
	 * Verifies the integrity of the forward key iterator for a given {@link TransactionalObjectBPlusTree}.
	 * Checks if the keys from the iterator are returned in strictly increasing order and
	 * validates the total number of keys returned matches the expected size.
	 *
	 * @param tree the {@link TransactionalObjectBPlusTree} whose key iterator is to be verified
	 * @param size the expected number of keys in the {@link TransactionalObjectBPlusTree}
	 * @throws IllegalStateException if the iterator fails to return keys in increasing order
	 *                               or if the number of keys does not match the expected size
	 */
	@SuppressWarnings("rawtypes")
	private static void verifyForwardKeyIterator(@Nonnull TransactionalObjectBPlusTree<?, ?> tree, int size) {
		int actualSize = 0;
		Comparable previousKey = null;
		final Iterator<?> it = tree.keyIterator();
		while (it.hasNext()) {
			final Comparable key = (Comparable) it.next();
			//noinspection unchecked
			if (previousKey != null && key.compareTo(previousKey) <= 0) {
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
	@SuppressWarnings("rawtypes")
	private static void verifyReverseKeyIterator(@Nonnull TransactionalObjectBPlusTree<?, ?> tree, int size) {
		int actualSize = 0;
		Object previousKey = null;
		final Iterator<?> it = tree.keyReverseIterator();
		while (it.hasNext()) {
			final Comparable key = (Comparable) it.next();
			//noinspection unchecked
			if (previousKey != null && key.compareTo(previousKey) >= 0) {
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
	private static <M extends Comparable<M>> void addCursorLevels(
		@Nonnull BPlusInternalTreeNode<M> currentNode,
		@Nonnull M key,
		@Nonnull List<CursorLevel<M>> path
	) {
		final NodeWithIndex<M> child = currentNode.search(key);
		path.add(new CursorLevel<>(currentNode.getChildren(), child.index(), currentNode.getPeek()));
		// if the child is an internal node, continue traversing down the tree
		if (child.node() instanceof BPlusInternalTreeNode<?> childInternalNode) {
			//noinspection unchecked
			addCursorLevels((BPlusInternalTreeNode<M>) childInternalNode, key, path);
		}
	}

	/**
	 * This method recursively traverses the B+ tree to find the least (leftmost) leaf node.
	 * It also populates the path traversed with internal nodes.
	 *
	 * @param currentNode The current internal tree node being traversed. Must not be null.
	 * @param path        A list to store the sequence of internal nodes visited. Must not be null.
	 */
	private static <M extends Comparable<M>> void addLeftmostCursorLevels(
		@Nonnull BPlusInternalTreeNode<M> currentNode,
		@Nonnull List<CursorLevel<M>> path
	) {
		final NodeWithIndex<M> child = new NodeWithIndex<>(currentNode.getChildren()[0], 0);
		path.add(new CursorLevel<>(currentNode.getChildren(), child.index(), currentNode.getPeek()));
		// if the child is an internal node, continue traversing down the tree
		if (child.node() instanceof BPlusInternalTreeNode<?> childInternalNode) {
			//noinspection unchecked
			addLeftmostCursorLevels((BPlusInternalTreeNode<M>) childInternalNode, path);
		}
	}

	/**
	 * This method recursively traverses the B+ tree to find the least (leftmost) leaf node.
	 * It also populates the path traversed with internal nodes.
	 *
	 * @param currentNode The current internal tree node being traversed. Must not be null.
	 * @param path        A list to store the sequence of internal nodes visited. Must not be null.
	 */
	private static <M extends Comparable<M>> void addRightmostCursorLevels(
		@Nonnull BPlusInternalTreeNode<M> currentNode,
		@Nonnull List<CursorLevel<M>> path
	) {
		final int currentNodePeek = currentNode.getPeek();
		final NodeWithIndex<M> child = new NodeWithIndex<>(currentNode.getChildren()[currentNodePeek], currentNodePeek);
		path.add(new CursorLevel<>(currentNode.getChildren(), child.index(), currentNodePeek));
		// if the child is an internal node, continue traversing down the tree
		if (child.node() instanceof BPlusInternalTreeNode<?> childInternalNode) {
			//noinspection unchecked
			addRightmostCursorLevels((BPlusInternalTreeNode<M>) childInternalNode, path);
		}
	}

	/**
	 * Constructor to initialize the B+ Tree with default block sizes.
	 *
	 * @param valueType the type of the values stored in the tree
	 */
	public TransactionalObjectBPlusTree(
		@Nonnull Class<K> keyType,
		@Nonnull Class<V> valueType
	) {
		this(
			DEFAULT_VALUE_BLOCK_SIZE,
			DEFAULT_MIN_VALUE_BLOCK_SIZE,
			DEFAULT_INTERNAL_NODE_BLOCK_SIZE,
			DEFAULT_MIN_INTERNAL_NODE_BLOCK_SIZE,
			keyType,
			valueType,
			null,
			new BPlusLeafTreeNode<>(DEFAULT_VALUE_BLOCK_SIZE, keyType, valueType, null, true),
			0
		);
	}

	/**
	 * Constructor to initialize the B+ Tree with default block sizes.
	 *
	 * @param valueType                 the type of the values stored in the tree
	 * @param transactionalLayerWrapper operator that wraps the values in a transactional layer
	 */
	public TransactionalObjectBPlusTree(
		@Nonnull Class<K> keyType,
		@Nonnull Class<V> valueType,
		@Nonnull Function<Object, V> transactionalLayerWrapper
	) {
		this(
			DEFAULT_VALUE_BLOCK_SIZE,
			DEFAULT_MIN_VALUE_BLOCK_SIZE,
			DEFAULT_INTERNAL_NODE_BLOCK_SIZE,
			DEFAULT_MIN_INTERNAL_NODE_BLOCK_SIZE,
			keyType,
			valueType,
			transactionalLayerWrapper
		);
	}

	/**
	 * Constructor to initialize the B+ Tree.
	 *
	 * @param valueBlockSize maximum number of values in a leaf node
	 * @param keyType        the type of the keys stored in the tree
	 * @param valueType      the type of the values stored in the tree
	 */
	public TransactionalObjectBPlusTree(int valueBlockSize, @Nonnull Class<K> keyType, @Nonnull Class<V> valueType) {
		this(
			valueBlockSize, valueBlockSize / 2,
			valueBlockSize, valueBlockSize / 2,
			keyType,
			valueType
		);
	}

	/**
	 * Constructor to initialize the B+ Tree.
	 *
	 * @param valueBlockSize            maximum number of values in a leaf node
	 * @param valueType                 the type of the values stored in the tree
	 * @param transactionalLayerWrapper operator that wraps the values in a transactional layer
	 */
	public TransactionalObjectBPlusTree(
		int valueBlockSize,
		@Nonnull Class<K> keyType,
		@Nonnull Class<V> valueType,
		@Nonnull Function<Object, V> transactionalLayerWrapper
	) {
		this(
			valueBlockSize, valueBlockSize / 2,
			valueBlockSize, valueBlockSize / 2,
			keyType,
			valueType,
			transactionalLayerWrapper
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
	 * @param keyType                  the type of the keys stored in the tree
	 * @param valueType                the type of the values stored in the tree
	 */
	public TransactionalObjectBPlusTree(
		int valueBlockSize,
		int minValueBlockSize,
		int internalNodeBlockSize,
		int minInternalNodeBlockSize,
		@Nonnull Class<K> keyType,
		@Nonnull Class<V> valueType
	) {
		this(
			valueBlockSize,
			minValueBlockSize,
			internalNodeBlockSize,
			minInternalNodeBlockSize,
			keyType,
			valueType,
			null,
			new BPlusLeafTreeNode<>(valueBlockSize, keyType, valueType, null, true),
			0
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
	 * @param keyType                  the type of the keys stored in the tree
	 * @param valueType                the type of the values stored in the tree
	 */
	public TransactionalObjectBPlusTree(
		int valueBlockSize,
		int minValueBlockSize,
		int internalNodeBlockSize,
		int minInternalNodeBlockSize,
		@Nonnull Class<K> keyType,
		@Nonnull Class<V> valueType,
		@Nonnull Function<Object, V> transactionalLayerWrapper
	) {
		this(
			valueBlockSize,
			minValueBlockSize,
			internalNodeBlockSize,
			minInternalNodeBlockSize,
			keyType,
			valueType,
			transactionalLayerWrapper,
			new BPlusLeafTreeNode<>(valueBlockSize, keyType, valueType, transactionalLayerWrapper, true),
			0
		);
	}

	private TransactionalObjectBPlusTree(
		int valueBlockSize,
		int minValueBlockSize,
		int internalNodeBlockSize,
		int minInternalNodeBlockSize,
		@Nonnull Class<K> keyType,
		@Nonnull Class<V> valueType,
		@Nullable Function<Object, V> transactionalLayerWrapper,
		@Nonnull BPlusTreeNode<K, ?> root,
		int size
	) {
		Assert.isPremiseValid(valueBlockSize >= 3, "Block size must be at least 3.");
		Assert.isPremiseValid(minValueBlockSize >= 1, "Minimum block size must be at least 1.");
		Assert.isPremiseValid(minValueBlockSize <= Math.ceil((float) valueBlockSize / 2.0) - 1, "Minimum block size must be less than half of the block size, otherwise the tree nodes might be immediately full after merges.");
		Assert.isPremiseValid(internalNodeBlockSize >= 3, "Internal node block size must be at least 3.");
		Assert.isPremiseValid(internalNodeBlockSize % 2 == 1, "Internal node block size must be an odd number.");
		Assert.isPremiseValid(minInternalNodeBlockSize >= 1, "Minimum internal node block size must be at least 1.");
		Assert.isPremiseValid(minInternalNodeBlockSize <= Math.ceil((float) internalNodeBlockSize / 2.0) - 1, "Minimum internal node block size must be less than half of the internal node block size, otherwise the tree nodes might be immediately full after merges.");
		Assert.isPremiseValid(!TransactionalLayerProducer.class.isAssignableFrom(keyType), "Key type cannot implement TransactionalLayerProducer.");
		Assert.isPremiseValid(transactionalLayerWrapper != null || !TransactionalLayerProducer.class.isAssignableFrom(valueType), "Value type cannot implement TransactionalLayerProducer if no transactional layer wrapper is provided.");
		this.valueBlockSize = valueBlockSize;
		this.minValueBlockSize = minValueBlockSize;
		this.internalNodeBlockSize = internalNodeBlockSize;
		this.minInternalNodeBlockSize = minInternalNodeBlockSize;
		this.keyType = keyType;
		this.valueType = valueType;
		this.transactionalLayerWrapper = transactionalLayerWrapper;
		this.root = new TransactionalReference<>(root);
		this.size = new TransactionalReference<>(size);
	}

	/**
	 * Retrieves the root node of the B+ tree.
	 *
	 * @return the root node of the B+ tree, guaranteed to be non-null.
	 */
	@Nonnull
	public BPlusTreeNode<K, ?> getRoot() {
		return this.root.get();
	}

	/**
	 * Sets the root node of the B+ tree to the specified new root node.
	 * This method removes the changes associated with the previous root
	 * before replacing it with the new root.
	 *
	 * @param newRoot the new root node to be set for the B+ tree; must not be null
	 */
	public void setRoot(@Nonnull BPlusTreeNode<K, ?> newRoot) {
		// remove changes of the previous root - it gets replaced
		final BPlusTreeNode<K, ?> currentRoot = this.root.get();
		ofNullable(Transaction.getTransactionalMemoryLayerIfExists(currentRoot))
			.ifPresent(layer -> currentRoot.removeLayer());
		// set new root
		this.root.set(newRoot);
	}

	/**
	 * Inserts a key-value pair into the B+ tree. If the corresponding leaf node
	 * overflows, it is split to maintain the properties of the tree.
	 *
	 * @param key   the key to be inserted into the B+ tree
	 * @param value the value associated with the key, must not be null
	 */
	public void insert(@Nonnull K key, @Nonnull V value) {
		final Cursor<K, V> cursor = createCursor(key);
		final BPlusLeafTreeNode<K, V> leaf = cursor.leafNode();
		if (leaf.insert(key, value)) {
			this.size.set(this.size.get() + 1);
		}

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
	public void upsert(@Nonnull K key, @Nonnull UnaryOperator<V> updater) {
		final Cursor<K, V> cursor = createCursor(key);
		final BPlusLeafTreeNode<K, V> leaf = cursor.leafNode();

		leaf.getValueWithIndex(key)
			.ifPresentOrElse(
				// update the value on specified index
				result -> {
					leaf.decoupleTransactionalArrays();
					leaf.getValues()[result.index()] = updater.apply(result.value());
				},
				// insert the new value
				() -> {
					if (leaf.insert(key, updater.apply(null))) {
						this.size.set(this.size.get() + 1);
					}

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
	public void delete(@Nonnull K key) {
		final Cursor<K, V> cursor = createCursor(key);
		final BPlusLeafTreeNode<K, V> leaf = cursor.leafNode();

		final boolean headRemoved = leaf.size() > 1 && key.equals(leaf.getKeys()[0]);
		if (leaf.delete(key)) {
			this.size.set(this.size.get() - 1);
		}

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
	public Optional<V> search(@Nonnull K key) {
		final Cursor<K, V> cursor = createCursor(key);
		return cursor.leafNode().getValue(key);
	}

	/**
	 * Returns the number of elements currently stored in the B+ tree.
	 *
	 * @return the size of the tree, represented as the number of elements it contains
	 */
	public int size() {
		return this.size.get();
	}

	/**
	 * Returns an iterator that traverses the B+ tree keys from left to right.
	 *
	 * @return an iterator that traverses the B+ tree keys from left to right
	 */
	@Nonnull
	public Iterator<K> keyIterator() {
		return new ForwardTreeKeyIterator<>(createLeftmostCursor());
	}

	/**
	 * Returns an iterator that traverses the B+ tree keys from left to right starting from the specified key or
	 * a key that is immediately greater than the specified key. The key may not be present in the tree.
	 *
	 * @param key the key from which to start the iteration
	 * @return an iterator that traverses the B+ tree keys from left to right starting from the specified key
	 */
	@Nonnull
	public Iterator<K> greaterOrEqualKeyIterator(@Nonnull K key) {
		return new ForwardTreeKeyIterator<>(createCursor(key), key);
	}

	/**
	 * Returns an iterator that traverses the B+ tree keys from left to right starting from the specified key or
	 * a key that is immediately greater than the specified key. The key may not be present in the tree.
	 *
	 * @param key the key from which to start the iteration
	 * @return an iterator that traverses the B+ tree keys from left to right starting from the specified key
	 */
	@Nonnull
	public Iterator<K> lesserOrEqualKeyIterator(@Nonnull K key) {
		return new ReverseTreeKeyIterator<>(createCursor(key), key);
	}

	/**
	 * Returns an iterator that traverses the B+ tree keys from right to left.
	 *
	 * @return an iterator that traverses the B+ tree keys from right to left
	 */
	@Nonnull
	public Iterator<K> keyReverseIterator() {
		return new ReverseTreeKeyIterator<>(createRightmostCursor());
	}

	/**
	 * Returns an iterator that traverses the B+ tree values from left to right.
	 *
	 * @return an iterator that traverses the B+ tree values from left to right
	 */
	@Nonnull
	public Iterator<V> valueIterator() {
		return new ForwardTreeValueIterator<>(createLeftmostCursor());
	}

	/**
	 * Returns an iterator that traverses the B+ tree values from left to right starting from the specified key or
	 * a key that is immediately greater than the specified key. The key may not be present in the tree.
	 *
	 * @param key the key from which to start the iteration
	 * @return an iterator that traverses the B+ tree values from left to right starting from the specified key
	 */
	@Nonnull
	public Iterator<V> greaterOrEqualValueIterator(@Nonnull K key) {
		return new ForwardTreeValueIterator<>(createCursor(key), key);
	}

	/**
	 * Returns an iterator that traverses the B+ tree values from left to right starting from the specified key or
	 * a key that is immediately greater than the specified key. The key may not be present in the tree.
	 *
	 * @param key the key from which to start the iteration
	 * @return an iterator that traverses the B+ tree values from left to right starting from the specified key
	 */
	@Nonnull
	public Iterator<V> lesserOrEqualValueIterator(@Nonnull K key) {
		return new ReverseTreeValueIterator<>(createCursor(key), key);
	}

	/**
	 * Returns an iterator that traverses the B+ tree values from right to left.
	 *
	 * @return an iterator that traverses the B+ tree values from right to left
	 */
	@Nonnull
	public Iterator<V> valueReverseIterator() {
		return new ReverseTreeValueIterator<>(createRightmostCursor());
	}

	/**
	 * Returns an iterator that traverses the B+ tree entries (both keys and values) from left to right.
	 *
	 * @return an iterator that traverses the B+ tree entries (both keys and values) from left to right
	 */
	@Nonnull
	public Iterator<Entry<K, V>> entryIterator() {
		return new ForwardTreeEntryIterator<>(createLeftmostCursor());
	}

	/**
	 * Returns an iterator that traverses the B+ tree entries (both keys and values) from left to right starting from the specified key or
	 * a key that is immediately greater than the specified key. The key may not be present in the tree.
	 *
	 * @param key the key from which to start the iteration
	 * @return an iterator that traverses the B+ tree entries (both keys and values) from left to right starting from the specified key
	 */
	@Nonnull
	public Iterator<Entry<K, V>> greaterOrEqualEntryIterator(@Nonnull K key) {
		return new ForwardTreeEntryIterator<>(createCursor(key), key);
	}

	/**
	 * Returns an iterator that traverses the B+ tree entries (both keys and values) from left to right starting from the specified key or
	 * a key that is immediately greater than the specified key. The key may not be present in the tree.
	 *
	 * @param key the key from which to start the iteration
	 * @return an iterator that traverses the B+ tree entries (both keys and values) from left to right starting from the specified key
	 */
	@Nonnull
	public Iterator<Entry<K, V>> lesserOrEqualEntryIterator(@Nonnull K key) {
		return new ReverseTreeEntryIterator<>(createCursor(key), key);
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder(1_024);
		this.getRoot().toVerboseString(sb, 0, 3);
		return sb.toString();
	}

	@Override
	public Void createLayer() {
		return null;
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
	}

	@Nonnull
	@Override
	public TransactionalObjectBPlusTree<K, V> createCopyWithMergedTransactionalMemory(@Nullable Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		final BPlusTreeNode<K, ?> theRoot = transactionalLayer.getStateCopyWithCommittedChanges(this.root).orElseThrow();
		if (theRoot instanceof BPlusLeafTreeNode<?, ?> leafNode) {
			//noinspection unchecked
			final BPlusLeafTreeNode<K, V> theLeafNode = (BPlusLeafTreeNode<K, V>) leafNode;
			return new TransactionalObjectBPlusTree<>(
				this.valueBlockSize, this.minValueBlockSize,
				this.internalNodeBlockSize, this.minInternalNodeBlockSize,
				this.keyType,
				this.valueType,
				this.transactionalLayerWrapper,
				transactionalLayer.getStateCopyWithCommittedChanges(theLeafNode),
				transactionalLayer.getStateCopyWithCommittedChanges(this.size).orElseThrow()
			);
		} else if (theRoot instanceof BPlusInternalTreeNode<?> internalNode) {
			//noinspection unchecked
			return new TransactionalObjectBPlusTree<>(
				this.valueBlockSize, this.minValueBlockSize,
				this.internalNodeBlockSize, this.minInternalNodeBlockSize,
				this.keyType,
				this.valueType,
				this.transactionalLayerWrapper,
				transactionalLayer.getStateCopyWithCommittedChanges((BPlusInternalTreeNode<K>) internalNode),
				transactionalLayer.getStateCopyWithCommittedChanges(this.size).orElseThrow()
			);
		} else {
			throw new GenericEvitaInternalError("Unknown node type: " + theRoot);
		}
	}

	@Nonnull
	@Override
	public ConsistencyReport getConsistencyReport() {
		try {
			final BPlusTreeNode<?, ?> theRoot = getRoot();
			int height = verifyAndReturnHeight(this);
			verifyMinimalCountOfValuesInNodes(theRoot, this.minValueBlockSize, this.minInternalNodeBlockSize, true);
			verifyInternalNodeKeys(theRoot);

			final int theSize = this.size();
			verifyForwardKeyIterator(this, theSize);
			verifyReverseKeyIterator(this, theSize);
			return new ConsistencyReport(
				ConsistencyState.CONSISTENT,
				"B+ tree is consistent with height of " + height + " levels and " + theSize + " elements."
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
	 * @param cursor the cursor representing the path from the root to the node to be consolidated
	 */
	private <N extends BPlusTreeNode<K, N>> void consolidate(@Nonnull Cursor<K, V> cursor) {
		CursorWithLevel<K> cursorWithLevel = cursor.toCursorWithLevel();

		while (cursorWithLevel != null) {
			final N node = cursorWithLevel.currentNode();
			// leaf node has less than minBlockSize keys, or internal nodes has less than two children
			final boolean underFlowNode = node.keyCount() < this.minValueBlockSize;
			if (underFlowNode) {
				final BPlusInternalTreeNode<K> parent = cursorWithLevel.parent();
				if (parent != null) {
					boolean nodeIsEmpty = node.size() == 0;
					final CursorWithLevel<K> previousNodeCursor = cursorWithLevel.getCursorForPreviousNode();
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

					final CursorWithLevel<K> nextNodeCursor = cursorWithLevel.getCursorForNextNode();
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
				} else if (node == this.getRoot()) {
					final BPlusTreeNode<K, ?> theRoot = this.getRoot();
					if (node.size() == 1 && node instanceof BPlusInternalTreeNode<?> internalTreeNode) {
						//noinspection unchecked
						final BPlusTreeNode<K, ?> firstChild = (BPlusTreeNode<K, ?>) internalTreeNode.getChildren()[0];
						ofNullable(Transaction.getTransactionalMemoryLayerIfExists(theRoot))
							.ifPresent(layer -> theRoot.removeLayer());
						// replace the root with the only child
						this.root.set(firstChild);
					} else if (node.size() == 0 && node instanceof BPlusInternalTreeNode) {
						ofNullable(Transaction.getTransactionalMemoryLayerIfExists(theRoot))
							.ifPresent(layer -> theRoot.removeLayer());
						// the root is empty, create a new empty leaf node
						this.root.set(
							new BPlusLeafTreeNode<>(
								this.valueBlockSize,
								this.keyType,
								this.valueType,
								this.transactionalLayerWrapper,
								true
							)
						);
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
	 * Finds the leftmost leaf node in the B+ tree that should contain the specified key.
	 * The method begins its search from the root node and traverses down to the leaf node
	 * by following the appropriate child pointers of internal nodes.
	 *
	 * @return the leaf node that is responsible for storing the provided key
	 */
	@Nonnull
	private Cursor<K, V> createLeftmostCursor() {
		final ArrayList<CursorLevel<K>> path = new ArrayList<>(this.size() == 0 ? 1 : (int) (Math.log(this.size()) + 1));
		final BPlusTreeNode<K, ?> theRoot = this.getRoot();
		//noinspection unchecked
		final BPlusTreeNode<K, ?>[] rootSiblings = (BPlusTreeNode<K, ?>[]) Array.newInstance(theRoot.getClass(), 1);
		rootSiblings[0] = theRoot;
		path.add(new CursorLevel<>(rootSiblings, 0, 0));
		// if the root is internal node, add the levels to the path until the leaf node is reached
		if (theRoot instanceof BPlusInternalTreeNode<?> rootInternalNode) {
			//noinspection unchecked
			addLeftmostCursorLevels((BPlusInternalTreeNode<K>) rootInternalNode, path);

		}
		return new Cursor<>(path);
	}

	/**
	 * Finds the rightmost leaf node in the B+ tree that should contain the specified key.
	 * The method begins its search from the root node and traverses down to the leaf node
	 * by following the appropriate child pointers of internal nodes.
	 *
	 * @return the leaf node that is responsible for storing the provided key
	 */
	@Nonnull
	private Cursor<K, V> createRightmostCursor() {
		final ArrayList<CursorLevel<K>> path = new ArrayList<>(this.size() == 0 ? 1 : (int) (Math.log(this.size()) + 1));
		final BPlusTreeNode<K, ?> theRoot = this.getRoot();
		//noinspection unchecked
		final BPlusTreeNode<K, ?>[] rootSiblings = (BPlusTreeNode<K, ?>[]) Array.newInstance(theRoot.getClass(), 1);
		rootSiblings[0] = theRoot;
		path.add(new CursorLevel<>(rootSiblings, 0, 0));
		// if the root is internal node, add the levels to the path until the leaf node is reached
		if (theRoot instanceof BPlusInternalTreeNode<?> rootInternalNode) {
			//noinspection unchecked
			addRightmostCursorLevels((BPlusInternalTreeNode<K>) rootInternalNode, path);

		}
		return new Cursor<>(path);
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
	private Cursor<K, V> createCursor(@Nonnull K key) {
		final ArrayList<CursorLevel<K>> path = new ArrayList<>(this.size() == 0 ? 1 : (int) (Math.log(this.size()) + 1));
		final BPlusTreeNode<K, ?> theRoot = this.getRoot();
		//noinspection unchecked
		final BPlusTreeNode<K, ?>[] rootSiblings = (BPlusTreeNode<K, ?>[]) Array.newInstance(theRoot.getClass(), 1);
		rootSiblings[0] = theRoot;
		path.add(new CursorLevel<>(rootSiblings, 0, 0));
		// if the root is internal node, add the levels to the path until the leaf node is reached
		if (theRoot instanceof BPlusInternalTreeNode<?> rootInternalNode) {
			//noinspection unchecked
			addCursorLevels((BPlusInternalTreeNode<K>) rootInternalNode, key, path);

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
		@Nonnull BPlusLeafTreeNode<K, V> leaf,
		@Nonnull Cursor<K, V> cursor
	) {
		final int mid = this.valueBlockSize / 2;
		final K[] originKeys = leaf.getKeys();
		final V[] originValues = leaf.getValues();

		// Move half the keys to the new arrays of the left leaf node
		//noinspection unchecked
		final BPlusLeafTreeNode<K, V> leftLeaf = new BPlusLeafTreeNode<>(
			originKeys,
			originValues,
			(K[]) Array.newInstance(this.keyType, this.valueBlockSize),
			(V[]) Array.newInstance(this.valueType, this.valueBlockSize),
			0,
			mid,
			!Transaction.isTransactionAvailable(),
			this.transactionalLayerWrapper
		);

		// Move the other half to the start of existing arrays of former leaf in the right leaf node
		final BPlusLeafTreeNode<K, V> rightLeaf = new BPlusLeafTreeNode<>(
			originKeys,
			originValues,
			originKeys,
			originValues,
			mid,
			leftLeaf.getKeys().length,
			!Transaction.isTransactionAvailable(),
			this.transactionalLayerWrapper
		);

		// remove changes of the previous root - it gets replaced
		ofNullable(Transaction.getTransactionalMemoryLayerIfExists(leaf))
			.ifPresent(layer -> leaf.removeLayer());

		// if the root splits, create a new root
		if (leaf == this.getRoot()) {
			// remove changes of the previous root - it gets replaced
			this.setRoot(
				new BPlusInternalTreeNode<>(
					this.valueBlockSize,
					rightLeaf.getKeys()[0],
					leftLeaf, rightLeaf,
					this.keyType,
					!Transaction.isTransactionAvailable()
				)
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
		@Nonnull BPlusTreeNode<K, ?> original,
		@Nonnull BPlusTreeNode<K, ?> left,
		@Nonnull BPlusTreeNode<K, ?> right,
		@Nonnull K key,
		@Nonnull CursorWithLevel<K> cursor
	) {
		final BPlusInternalTreeNode<K> parent = cursor.parent();

		Assert.notNull(parent, "Parent node must not be null.");
		parent.adaptToLeafSplit(key, original, left, right);

		if (parent.isFull()) {
			splitInternalNode(parent, new CursorWithLevel<>(cursor.path(), cursor.level() - 1));
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
		@Nonnull BPlusInternalTreeNode<K> internal,
		@Nonnull CursorWithLevel<K> cursor
	) {
		final int mid = (this.valueBlockSize + 1) / 2;
		final K[] originKeys = internal.getKeys();
		final BPlusTreeNode<K, ?>[] originChildren = internal.getChildren();

		// Move half the keys to the new arrays of the left leaf node
		final BPlusInternalTreeNode<K> leftInternal = new BPlusInternalTreeNode<>(
			originKeys,
			originChildren,
			0,
			mid - 1,
			0,
			mid,
			this.keyType,
			!Transaction.isTransactionAvailable()
		);

		// Move the other half to the start of existing arrays of former leaf in the right leaf node
		final BPlusInternalTreeNode<K> rightInternal = new BPlusInternalTreeNode<>(
			originKeys,
			originChildren,
			mid,
			leftInternal.getKeys().length,
			mid,
			leftInternal.getChildren().length,
			this.keyType,
			!Transaction.isTransactionAvailable()
		);

		// remove changes of the previous root - it gets replaced
		ofNullable(Transaction.getTransactionalMemoryLayerIfExists(internal))
			.ifPresent(layer -> internal.removeLayer());

		// if the root splits, create a new root
		if (internal == this.getRoot()) {
			this.setRoot(
				new BPlusInternalTreeNode<>(
					this.valueBlockSize,
					rightInternal.getLeftBoundaryKey(),
					leftInternal, rightInternal,
					this.keyType,
					!Transaction.isTransactionAvailable()
				)
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
	interface BPlusTreeNode<M extends Comparable<M>, N extends BPlusTreeNode<M, N>>
		extends
		TransactionalLayerProducer<N, N>,
		Serializable {

		/**
		 * Retrieves an array of integer keys associated with the node.
		 *
		 * @return an array of integer keys present in the node. The array is guaranteed to be non-null.
		 */
		@Nonnull
		M[] getKeys();

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
		@Nonnull
		M getLeftBoundaryKey();
	}

	/**
	 * B+ Tree Node class to represent internal node.
	 */
	static class BPlusInternalTreeNode<M extends Comparable<M>> implements BPlusTreeNode<M, BPlusInternalTreeNode<M>> {
		@Serial private static final long serialVersionUID = -7185842083654066615L;
		@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
		/**
		 * Signalizes this instance if permitted to create and use transactional layers. The tree nodes use themselves
		 * (the same class) as its transactional memory and if this layer would use transactional memory as well, it would
		 * create an infinite loop. Therefore, this flag is used to prevent this behavior.
		 */
		private final boolean transactionalLayer;
		/**
		 * The keys stored in this node.
		 */
		private M[] keys;

		/**
		 * The children of this node.
		 */
		private BPlusTreeNode<M, ?>[] children;

		/**
		 * Index of the last occupied position in the children array.
		 */
		private int peek;

		public BPlusInternalTreeNode(
			int blockSize,
			@Nonnull M key,
			@Nonnull BPlusTreeNode<M, ?> leftLeaf,
			@Nonnull BPlusTreeNode<M, ?> rightLeaf,
			@Nonnull Class<M> keyType,
			boolean transactionalLayer
		) {
			//noinspection unchecked
			this.keys = (M[]) Array.newInstance(keyType, blockSize);
			//noinspection unchecked
			this.children = new BPlusTreeNode[blockSize + 1];
			this.keys[0] = key;
			this.children[0] = leftLeaf;
			this.children[1] = rightLeaf;
			this.peek = 1;
			this.transactionalLayer = transactionalLayer;
		}

		public BPlusInternalTreeNode(
			@Nonnull M[] originKeys,
			@Nonnull BPlusTreeNode<M, ?>[] originChildren,
			int keyStart, int keyEnd,
			int childrenStart, int childrenEnd,
			@Nonnull Class<M> keyType,
			boolean transactionalLayer
		) {
			// we always create a new array for keys and children
			//noinspection unchecked
			this.keys = (M[]) Array.newInstance(keyType, originKeys.length);
			//noinspection unchecked
			this.children = new BPlusTreeNode[originChildren.length];
			// Copy the keys and children from the origin arrays
			System.arraycopy(originKeys, keyStart, keys, 0, keyEnd - keyStart);
			System.arraycopy(originChildren, childrenStart, children, 0, childrenEnd - childrenStart);
			this.peek = childrenEnd - childrenStart - 1;
			this.transactionalLayer = transactionalLayer;
		}

		private BPlusInternalTreeNode(
			@Nonnull M[] originKeys,
			@Nonnull BPlusTreeNode<M, ?>[] originChildren,
			int originPeek,
			boolean transactionalLayer
		) {
			// we always create a new array for keys and children
			this.keys = originKeys;
			this.children = originChildren;
			this.peek = originPeek;
			this.transactionalLayer = transactionalLayer;
		}

		@Nonnull
		@Override
		public M[] getKeys() {
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer ? Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			if (layer == null) {
				return this.keys;
			} else {
				return layer.keys;
			}
		}

		@Override
		public int getPeek() {
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer ? Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			if (layer == null) {
				return this.peek;
			} else {
				return layer.peek;
			}
		}

		@Override
		public void setPeek(int peek) {
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer ? Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				final int originPeek = this.peek;
				this.peek = peek;
				if (peek < originPeek) {
					Arrays.fill(this.keys, Math.max(0, peek), originPeek, null);
					Arrays.fill(this.children, peek + 1, originPeek + 1, null);
				}
			} else {
				final int originPeek = layer.peek;
				layer.peek = peek;
				if (peek < originPeek) {
					// internal arrays may have been still identical to the original arrays
					// we need to copy them in the transactional layer, before modifying

					//noinspection ArrayEquality
					if (layer.keys == this.keys) {
						//noinspection unchecked
						layer.keys = (M[]) Array.newInstance(this.keys.getClass().getComponentType(), this.keys.length);
						System.arraycopy(this.keys, 0, layer.keys, 0, originPeek);
					} else {
						Arrays.fill(layer.keys, Math.max(0, peek), originPeek, null);
					}
					//noinspection ArrayEquality
					if (layer.children == this.children) {
						//noinspection unchecked
						layer.children = new BPlusTreeNode[this.children.length];
						System.arraycopy(this.children, 0, layer.children, 0, originPeek + 1);
					} else {
						Arrays.fill(layer.children, peek + 1, originPeek + 1, null);
					}
				}
			}
		}

		@Override
		public int keyCount() {
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer ? Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			if (layer == null) {
				return Math.max(this.peek, 0);
			} else {
				return Math.max(layer.peek, 0);
			}
		}

		@Override
		public boolean isFull() {
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer ? Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			if (layer == null) {
				return this.peek == this.children.length - 1;
			} else {
				return layer.peek == layer.children.length - 1;
			}
		}

		@Override
		public void toVerboseString(@Nonnull StringBuilder sb, int level, int indentSpaces) {
			final M[] theKeys;
			final BPlusTreeNode<M, ?>[] theChildren;
			final int thePeek;
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer ? Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			if (layer == null) {
				theKeys = this.keys;
				theChildren = this.children;
				thePeek = this.peek;
			} else {
				theKeys = layer.keys;
				theChildren = layer.children;
				thePeek = layer.peek;
			}
			sb.append(" ".repeat(level * indentSpaces)).append("< ").append(theKeys[0]).append(":\n");
			theChildren[0].toVerboseString(sb, level + 1, indentSpaces);
			sb.append("\n");
			for (int i = 1; i <= thePeek; i++) {
				final M key = theKeys[i - 1];
				final BPlusTreeNode<M, ?> child = theChildren[i];
				sb.append(" ".repeat(level * indentSpaces)).append(">=").append(key).append(":\n");
				child.toVerboseString(sb, level + 1, indentSpaces);
				if (i < thePeek) {
					sb.append("\n");
				}
			}
		}

		@Override
		public void stealFromLeft(int numberOfTailValues, @Nonnull BPlusInternalTreeNode<M> previousNode) {
			Assert.isPremiseValid(numberOfTailValues > 0, "Number of tail values to steal must be positive!");

			final BPlusInternalTreeNode<M> layer = this.transactionalLayer ? Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
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
			} else {
				decoupleTransactionalArrays();
				previousNode.decoupleTransactionalArrays();
				// we preserve all the current node children
				System.arraycopy(layer.children, 0, layer.children, numberOfTailValues, layer.peek + 1);
				// then move the children from the previous node
				System.arraycopy(previousNode.getChildrenForUpdate(), previousNode.size() - numberOfTailValues, layer.children, 0, numberOfTailValues);
				// we need to preserve all the current node keys
				System.arraycopy(layer.keys, 0, layer.keys, numberOfTailValues, layer.peek);
				// our original first child newly produces its own key
				layer.keys[numberOfTailValues - 1] = layer.children[numberOfTailValues].getLeftBoundaryKey();
				// and now we can copy the keys from the previous node - but except the first one
				System.arraycopy(previousNode.getKeysForUpdate(), previousNode.keyCount() - numberOfTailValues + 1, layer.keys, 0, numberOfTailValues - 1);
				// and update the peek indexes
				layer.peek += numberOfTailValues;
				previousNode.setPeek(previousNode.getPeek() - numberOfTailValues);
			}
		}

		@Override
		public void stealFromRight(int numberOfHeadValues, @Nonnull BPlusInternalTreeNode<M> nextNode) {
			Assert.isPremiseValid(numberOfHeadValues > 0, "Number of head values to steal must be positive!");

			final BPlusInternalTreeNode<M> layer = this.transactionalLayer ? Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				// we move all the children
				final BPlusTreeNode<M, ?>[] nextNodeChildren = nextNode.getChildren();
				System.arraycopy(nextNodeChildren, 0, this.children, this.peek + 1, numberOfHeadValues);
				System.arraycopy(nextNodeChildren, numberOfHeadValues, nextNodeChildren, 0, nextNode.size() - numberOfHeadValues);

				// set the key for the first child of the next node
				this.keys[this.peek] = this.children[this.peek + 1].getLeftBoundaryKey();

				// we move the keys from the next node for all copied children
				final M[] nextNodeKeys = nextNode.getKeys();
				System.arraycopy(nextNodeKeys, 0, this.keys, this.peek + 1, numberOfHeadValues - 1);
				// we need to shift the keys in the next node
				System.arraycopy(nextNodeKeys, numberOfHeadValues, nextNodeKeys, 0, nextNodeKeys.length - numberOfHeadValues);

				// and update the peek indexes
				this.peek += numberOfHeadValues;
				nextNode.setPeek(nextNode.getPeek() - numberOfHeadValues);
			} else {
				decoupleTransactionalArrays();
				nextNode.decoupleTransactionalArrays();

				// we move all the children
				final BPlusTreeNode<M, ?>[] nextNodeChildrenForUpdate = nextNode.getChildrenForUpdate();
				System.arraycopy(nextNodeChildrenForUpdate, 0, layer.children, layer.peek + 1, numberOfHeadValues);
				System.arraycopy(nextNodeChildrenForUpdate, numberOfHeadValues, nextNodeChildrenForUpdate, 0, nextNode.size() - numberOfHeadValues);

				// set the key for the first child of the next node
				layer.keys[layer.peek] = layer.children[layer.peek + 1].getLeftBoundaryKey();

				// we move the keys from the next node for all copied children
				final M[] nextNodeKeysForUpdate = nextNode.getKeysForUpdate();
				System.arraycopy(nextNodeKeysForUpdate, 0, layer.keys, layer.peek + 1, numberOfHeadValues - 1);
				// we need to shift the keys in the next node
				System.arraycopy(nextNodeKeysForUpdate, numberOfHeadValues, nextNodeKeysForUpdate, 0, nextNodeKeysForUpdate.length - numberOfHeadValues);

				// and update the peek indexes
				layer.peek += numberOfHeadValues;
				nextNode.setPeek(nextNode.getPeek() - numberOfHeadValues);
			}
		}

		@Override
		public void mergeWithLeft(@Nonnull BPlusInternalTreeNode<M> previousNode) {
			final int mergePeek = previousNode.getPeek();

			final BPlusInternalTreeNode<M> layer = this.transactionalLayer ? Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				System.arraycopy(this.keys, 0, this.keys, mergePeek + 1, this.peek);
				this.keys[mergePeek] = this.children[0].getLeftBoundaryKey();
				System.arraycopy(this.children, 0, this.children, mergePeek + 1, this.peek + 1);
				System.arraycopy(previousNode.getKeys(), 0, this.keys, 0, mergePeek);
				System.arraycopy(previousNode.getChildren(), 0, this.children, 0, mergePeek + 1);
				this.peek += mergePeek + 1;
				previousNode.setPeek(-1);
			} else {
				decoupleTransactionalArrays();
				// we don't need to do: nodeToMergeWith.decoupleTransactionalArrays();
				// the other node will be fully merged to this node, so its arrays remain unmodified by this operation
				System.arraycopy(layer.keys, 0, layer.keys, mergePeek + 1, layer.peek);
				layer.keys[mergePeek] = layer.children[0].getLeftBoundaryKey();
				System.arraycopy(layer.children, 0, layer.children, mergePeek + 1, layer.peek + 1);
				System.arraycopy(previousNode.getKeysForUpdate(), 0, layer.keys, 0, mergePeek);
				System.arraycopy(previousNode.getChildrenForUpdate(), 0, layer.children, 0, mergePeek + 1);
				layer.peek += mergePeek + 1;
				previousNode.setPeek(-1);
			}
		}

		@Override
		public void mergeWithRight(@Nonnull BPlusInternalTreeNode<M> nextNode) {
			final int mergePeek = nextNode.getPeek();

			final BPlusInternalTreeNode<M> layer = this.transactionalLayer ? Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
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
			} else {
				decoupleTransactionalArrays();
				// we don't need to do: nodeToMergeWith.decoupleTransactionalArrays();
				// the other node will be fully merged to this node, so its arrays remain unmodified by this operation
				System.arraycopy(nextNode.getChildrenForUpdate(), 0, layer.children, layer.peek + 1, mergePeek + 1);
				final int offset;
				if (layer.peek >= 0) {
					layer.keys[layer.peek] = layer.children[layer.peek + 1].getLeftBoundaryKey();
					offset = 1;
				} else {
					offset = 0;
				}
				System.arraycopy(nextNode.getKeysForUpdate(), 0, layer.keys, layer.peek + offset, mergePeek);
				layer.peek += mergePeek + 1;
				nextNode.setPeek(-1);
			}
		}

		@Nonnull
		@Override
		public M getLeftBoundaryKey() {
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer ? Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			if (layer == null) {
				return this.children[0].getLeftBoundaryKey();
			} else {
				return layer.children[0].getLeftBoundaryKey();
			}
		}

		/**
		 * Retrieves the keys of the current node for updating. If a transactional layer is active, it ensures
		 * that updates are performed on an independent copy of the keys array within the transactional layer.
		 *
		 * @return an array of integers representing the keys of the current node, adjusted for the transactional layer if applicable.
		 */
		@Nonnull
		public M[] getKeysForUpdate() {
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer ? Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				return this.keys;
			} else {
				// internal arrays may have been still identical to the original arrays
				// we need to copy them in the transactional layer, before modifying

				//noinspection ArrayEquality
				if (layer.keys == this.keys) {
					//noinspection unchecked
					layer.keys = (M[]) Array.newInstance(this.keys.getClass().getComponentType(), this.keys.length);
					System.arraycopy(this.keys, 0, layer.keys, 0, this.keys.length);
				}
				return layer.keys;
			}
		}

		/**
		 * Retrieves the children nodes of the current BPlusTree node but only for READ-ONLY purposes.
		 *
		 * @return an array of BPlusTreeNode elements representing the children of the current node.
		 */
		@Nonnull
		public BPlusTreeNode<M, ?>[] getChildren() {
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer ? Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			if (layer == null) {
				return this.children;
			} else {
				return layer.children;
			}
		}

		/**
		 * Retrieves the children nodes of the current BPlusTree node for updating.
		 * If a transactional layer is active, it ensures that the updates are performed
		 * on an independent copy of the children array contained within the transactional layer.
		 *
		 * @return an array of BPlusTreeNode elements representing the children of the
		 * current node, adjusted for the transactional layer if applicable.
		 */
		@Nonnull
		public BPlusTreeNode<M, ?>[] getChildrenForUpdate() {
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer ? Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				return this.children;
			} else {
				// internal arrays may have been still identical to the original arrays
				// we need to copy them in the transactional layer, before modifying

				//noinspection ArrayEquality
				if (layer.children == this.children) {
					//noinspection unchecked
					layer.children = new BPlusTreeNode[this.children.length];
					System.arraycopy(this.children, 0, layer.children, 0, this.children.length);
				}
				return layer.children;
			}
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
			@Nonnull M key,
			@Nonnull BPlusTreeNode<M, ?> original,
			@Nonnull BPlusTreeNode<M, ?> left,
			@Nonnull BPlusTreeNode<M, ?> right
		) {
			Assert.isPremiseValid(
				!this.isFull(),
				"Internal node must not be full to accommodate two leaf nodes after their split!"
			);

			final BPlusInternalTreeNode<M> layer = this.transactionalLayer ? Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				// the peek relates to children, which are one more than keys, that's why we don't use peek + 1, but mere peek
				final InsertionPosition insertionPosition = computeInsertPositionOfObjInOrderedArray(key, this.keys, 0, this.peek);
				Assert.isPremiseValid(
					original == this.children[insertionPosition.position()],
					"Original node must be the child of the internal node!"
				);
				Assert.isPremiseValid(
					!insertionPosition.alreadyPresent(),
					"Key already present in the internal node!"
				);

				insertRecordIntoSameArrayOnIndex(key, this.keys, insertionPosition.position());
				this.children[insertionPosition.position()] = left;
				insertRecordIntoSameArrayOnIndex(right, this.children, insertionPosition.position() + 1);
				this.peek++;
			} else {
				decoupleTransactionalArrays();

				// the peek relates to children, which are one more than keys, that's why we don't use peek + 1, but mere peek
				final InsertionPosition insertionPosition = computeInsertPositionOfObjInOrderedArray(key, layer.keys, 0, layer.peek);
				Assert.isPremiseValid(
					original == layer.children[insertionPosition.position()],
					"Original node must be the child of the internal node!"
				);
				Assert.isPremiseValid(
					!insertionPosition.alreadyPresent(),
					"Key already present in the internal node!"
				);

				insertRecordIntoSameArrayOnIndex(key, layer.keys, insertionPosition.position());
				layer.children[insertionPosition.position()] = left;
				insertRecordIntoSameArrayOnIndex(right, layer.children, insertionPosition.position() + 1);
				layer.peek++;
			}
		}

		/**
		 * Searches for the BPlusTreeNode that should contain the given key.
		 *
		 * @param key the integer key to search for within the B+ Tree.
		 * @return the BPlusTreeNode that should contain the specified key.
		 */
		@Nonnull
		public NodeWithIndex<M> search(@Nonnull M key) {
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer ? Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				final InsertionPosition insertionPosition = computeInsertPositionOfObjInOrderedArray(key, this.keys, 0, this.peek);
				final int thePosition = insertionPosition.alreadyPresent() ?
					insertionPosition.position() + 1 : insertionPosition.position();
				return new NodeWithIndex<>(this.children[thePosition], thePosition);
			} else {
				final InsertionPosition insertionPosition = computeInsertPositionOfObjInOrderedArray(key, layer.keys, 0, layer.peek);
				final int thePosition = insertionPosition.alreadyPresent() ?
					insertionPosition.position() + 1 : insertionPosition.position();
				return new NodeWithIndex<>(layer.children[thePosition], thePosition);
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
		public void removeChildOnIndex(int keyIndex, int childIndex) {
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer ? Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				removeRecordFromSameArrayOnIndex(this.keys, keyIndex);
				this.keys[this.peek - 1] = null;
				removeRecordFromSameArrayOnIndex(this.children, childIndex);
				this.children[this.peek] = null;
				this.peek--;
			} else {
				decoupleTransactionalArrays();

				removeRecordFromSameArrayOnIndex(layer.keys, keyIndex);
				layer.keys[layer.peek - 1] = null;

				// the removed children may have had its own transactional layer, which needs to be removed
				ofNullable(Transaction.getTransactionalMemoryLayerIfExists(layer.children[childIndex]))
					.ifPresent(it -> layer.children[childIndex].removeLayer());

				removeRecordFromSameArrayOnIndex(layer.children, childIndex);
				layer.children[layer.peek] = null;
				layer.peek--;
			}
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
		public void updateKeyForNode(int index, @Nonnull BPlusTreeNode<M, ?> node) {
			Assert.isPremiseValid(
				index > 0,
				"Leftmost child node does not have a key in the parent node!"
			);

			final BPlusInternalTreeNode<M> layer = this.transactionalLayer ? Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				Assert.isPremiseValid(
					this.children[index] == node,
					"Node to update key for must match the child node at the specified index!"
				);
				this.keys[index - 1] = node.getLeftBoundaryKey();
			} else {
				decoupleTransactionalArrays();
				Assert.isPremiseValid(
					layer.children[index] == node,
					"Node to update key for must match the child node at the specified index!"
				);
				layer.keys[index - 1] = node.getLeftBoundaryKey();
			}
		}

		@Override
		public BPlusInternalTreeNode<M> createLayer() {
			return new BPlusInternalTreeNode<>(
				this.keys,
				this.children,
				this.peek,
				false
			);
		}

		@Override
		public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			transactionalLayer.removeTransactionalMemoryLayer(this);
		}

		@Nonnull
		@Override
		public BPlusInternalTreeNode<M> createCopyWithMergedTransactionalMemory(
			@Nullable BPlusInternalTreeNode<M> layer,
			@Nonnull TransactionalLayerMaintainer transactionalLayer
		) {
			final M[] theKeys;
			final BPlusTreeNode<M, ?>[] theChildren;
			final int thePeek;
			if (layer == null) {
				theKeys = this.keys;
				theChildren = this.children;
				thePeek = this.peek;
			} else {
				theKeys = layer.keys;
				theChildren = layer.children;
				thePeek = layer.peek;
			}

			BPlusTreeNode<M, ?>[] newChildren = null;
			for (int i = 0; i < thePeek + 1; i++) {
				final BPlusTreeNode<M, ?> child = transactionalLayer.getStateCopyWithCommittedChanges(theChildren[i]);
				if (newChildren == null && child != theChildren[i]) {
					//noinspection unchecked
					newChildren = new BPlusTreeNode[theChildren.length];
					System.arraycopy(theChildren, 0, newChildren, 0, i);
				}
				if (newChildren != null) {
					newChildren[i] = child;
				}
			}

			if (newChildren != null) {
				return new BPlusInternalTreeNode<>(
					theKeys,
					newChildren,
					thePeek,
					true
				);
			} else if (layer != null) {
				return new BPlusInternalTreeNode<>(
					theKeys,
					theChildren,
					thePeek,
					true
				);
			} else {
				return this;
			}
		}

		@Override
		public String toString() {
			final StringBuilder sb = new StringBuilder(DEFAULT_VALUE_BLOCK_SIZE);
			toVerboseString(sb, 0, 3);
			return sb.toString();
		}

		/**
		 * Internal arrays may have been still identical to the original arrays we need to copy them in
		 * the transactional layer before modifying.
		 */
		private void decoupleTransactionalArrays() {
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer ? Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer != null) {
				//noinspection ArrayEquality
				if (layer.keys == this.keys) {
					//noinspection unchecked
					layer.keys = (M[]) Array.newInstance(this.keys.getClass().getComponentType(), this.keys.length);
					System.arraycopy(this.keys, 0, layer.keys, 0, this.peek);
				}
				//noinspection ArrayEquality
				if (layer.children == this.children) {
					//noinspection unchecked
					layer.children = new BPlusTreeNode[this.children.length];
					System.arraycopy(this.children, 0, layer.children, 0, this.peek + 1);
				}
			}
		}

	}

	/**
	 * B+ Tree Node class to represent leaf node with associated values.
	 */
	static class BPlusLeafTreeNode<M extends Comparable<M>, N>
		implements BPlusTreeNode<M, BPlusLeafTreeNode<M, N>> {
		@Serial private static final long serialVersionUID = 8382269323782408764L;
		@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
		/**
		 * Signalizes this instance if permitted to create and use transactional layers. The tree nodes use themselves
		 * (the same class) as its transactional memory and if this layer would use transactional memory as well, it would
		 * create an infinite loop. Therefore, this flag is used to prevent this behavior.
		 */
		private final boolean transactionalLayer;
		/**
		 * The function to wrap the values into a transactional layer.
		 */
		@Nullable private final Function<Object, N> transactionalLayerWrapper;
		/**
		 * The keys stored in this node.
		 */
		private M[] keys;

		/**
		 * The values stored in this node. Index i corresponds to the value associated with key i.
		 */
		private N[] values;


		/**
		 * Index of the last occupied position in the keys array.
		 */
		private int peek;

		public BPlusLeafTreeNode(
			int blockSize,
			@Nonnull Class<M> keyType,
			@Nonnull Class<N> valueType,
			@Nullable Function<Object, N> transactionalLayerWrapper,
			boolean transactionalLayer
		) {
			//noinspection unchecked
			this.keys = (M[]) Array.newInstance(keyType, blockSize);
			//noinspection unchecked
			this.values = (N[]) Array.newInstance(valueType, blockSize);
			this.transactionalLayerWrapper = transactionalLayerWrapper;
			this.peek = -1;
			this.transactionalLayer = transactionalLayer;
		}

		public BPlusLeafTreeNode(
			@Nonnull M[] originKeys,
			@Nonnull N[] originValues,
			@Nonnull M[] keys,
			@Nonnull N[] values,
			int start, int end,
			boolean transactionalLayer,
			@Nullable Function<Object, N> transactionalLayerWrapper
		) {
			this.keys = keys;
			this.values = values;
			// Copy the keys and values from the origin arrays
			System.arraycopy(originKeys, start, keys, 0, end - start);
			//noinspection ArrayEquality
			if (keys == originKeys) {
				Arrays.fill(keys, end - start, keys.length, null);
			}
			System.arraycopy(originValues, start, values, 0, end - start);
			//noinspection ArrayEquality
			if (values == originValues) {
				Arrays.fill(values, end - start, values.length, null);
			}
			this.peek = end - start - 1;
			this.transactionalLayer = transactionalLayer;
			this.transactionalLayerWrapper = transactionalLayerWrapper;
		}

		private BPlusLeafTreeNode(
			@Nonnull M[] keys,
			@Nonnull N[] values,
			int peek,
			boolean transactionalLayer,
			@Nullable Function<Object, N> transactionalLayerWrapper
		) {
			this.keys = keys;
			this.values = values;
			this.peek = peek;
			this.transactionalLayer = transactionalLayer;
			this.transactionalLayerWrapper = transactionalLayerWrapper;
		}

		@Nonnull
		@Override
		public M[] getKeys() {
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer ? Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			if (layer == null) {
				return this.keys;
			} else {
				return layer.keys;
			}
		}

		public int getPeek() {
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer ? Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			if (layer == null) {
				return this.peek;
			} else {
				return layer.peek;
			}
		}

		@Override
		public void setPeek(int peek) {
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer ? Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				final int originPeek = this.peek;
				this.peek = peek;
				if (peek < originPeek) {
					Arrays.fill(this.keys, peek + 1, originPeek + 1, null);
					Arrays.fill(this.values, peek + 1, originPeek + 1, null);
				}
			} else {
				final int originPeek = layer.peek;
				layer.peek = peek;
				if (peek < originPeek) {
					// internal arrays may have been still identical to the original arrays
					// we need to copy them in the transactional layer, before modifying

					//noinspection ArrayEquality
					if (layer.keys == this.keys) {
						//noinspection unchecked
						layer.keys = (M[]) Array.newInstance(this.keys.getClass().getComponentType(), this.keys.length);
						System.arraycopy(this.keys, 0, layer.keys, 0, originPeek + 1);
					} else {
						Arrays.fill(layer.keys, peek + 1, originPeek + 1, null);
					}
					//noinspection ArrayEquality
					if (layer.values == this.values) {
						//noinspection unchecked
						layer.values = (N[]) Array.newInstance(this.values.getClass().getComponentType(), this.values.length);
						System.arraycopy(this.values, 0, layer.values, 0, originPeek + 1);
					} else {
						Arrays.fill(layer.values, peek + 1, originPeek + 1, null);
					}
				}
			}
		}

		@Override
		public int keyCount() {
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer ? Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			if (layer == null) {
				return this.peek + 1;
			} else {
				return layer.peek + 1;
			}
		}

		@Override
		public boolean isFull() {
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer ? Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			if (layer == null) {
				return this.peek == this.values.length - 1;
			} else {
				return layer.peek == layer.values.length - 1;
			}
		}

		@Override
		public void toVerboseString(@Nonnull StringBuilder sb, int level, int indentSpaces) {
			sb.append(" ".repeat(level * indentSpaces));
			final M[] theKeys;
			final N[] theValues;
			final int thePeek;

			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer ? Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			if (layer == null) {
				theKeys = this.keys;
				theValues = this.values;
				thePeek = this.peek;
			} else {
				theKeys = layer.keys;
				theValues = layer.values;
				thePeek = layer.peek;
			}

			for (int i = 0; i <= thePeek; i++) {
				sb.append(theKeys[i]).append(":").append(theValues[i]);
				if (i < thePeek) {
					sb.append(", ");
				}
			}
		}

		@Override
		public void stealFromLeft(int numberOfTailValues, @Nonnull BPlusLeafTreeNode<M, N> previousNode) {
			Assert.isPremiseValid(numberOfTailValues > 0, "Number of tail values to steal must be positive!");
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer ? Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				System.arraycopy(this.keys, 0, this.keys, numberOfTailValues, this.peek + 1);
				System.arraycopy(this.values, 0, this.values, numberOfTailValues, this.peek + 1);
				System.arraycopy(previousNode.getKeys(), previousNode.size() - numberOfTailValues, this.keys, 0, numberOfTailValues);
				System.arraycopy(previousNode.getValues(), previousNode.size() - numberOfTailValues, this.values, 0, numberOfTailValues);
				this.peek += numberOfTailValues;
				previousNode.setPeek(previousNode.getPeek() - numberOfTailValues);
			} else {
				// we need to decouple the arrays before modifying them
				decoupleTransactionalArrays();
				previousNode.decoupleTransactionalArrays();

				System.arraycopy(layer.keys, 0, layer.keys, numberOfTailValues, layer.peek + 1);
				System.arraycopy(layer.values, 0, layer.values, numberOfTailValues, layer.peek + 1);
				System.arraycopy(previousNode.getKeys(), previousNode.size() - numberOfTailValues, layer.keys, 0, numberOfTailValues);
				System.arraycopy(previousNode.getValues(), previousNode.size() - numberOfTailValues, layer.values, 0, numberOfTailValues);
				layer.peek += numberOfTailValues;
				previousNode.setPeek(previousNode.getPeek() - numberOfTailValues);
			}
		}

		@Override
		public void stealFromRight(int numberOfHeadValues, @Nonnull BPlusLeafTreeNode<M, N> nextNode) {
			Assert.isPremiseValid(numberOfHeadValues > 0, "Number of head values to steal must be positive!");

			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer ? Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				System.arraycopy(nextNode.getKeys(), 0, this.keys, this.peek + 1, numberOfHeadValues);
				System.arraycopy(nextNode.getValues(), 0, this.values, this.peek + 1, numberOfHeadValues);
				System.arraycopy(nextNode.getKeys(), numberOfHeadValues, nextNode.getKeys(), 0, nextNode.size() - numberOfHeadValues);
				System.arraycopy(nextNode.getValues(), numberOfHeadValues, nextNode.getValues(), 0, nextNode.size() - numberOfHeadValues);
				nextNode.setPeek(nextNode.getPeek() - numberOfHeadValues);
				this.peek += numberOfHeadValues;
			} else {
				// we need to decouple the arrays before modifying them
				decoupleTransactionalArrays();
				nextNode.decoupleTransactionalArrays();

				System.arraycopy(nextNode.getKeysForUpdate(), 0, layer.keys, layer.peek + 1, numberOfHeadValues);
				System.arraycopy(nextNode.getValuesForUpdate(), 0, layer.values, layer.peek + 1, numberOfHeadValues);
				System.arraycopy(nextNode.getKeysForUpdate(), numberOfHeadValues, nextNode.getKeysForUpdate(), 0, nextNode.size() - numberOfHeadValues);
				System.arraycopy(nextNode.getValuesForUpdate(), numberOfHeadValues, nextNode.getValuesForUpdate(), 0, nextNode.size() - numberOfHeadValues);
				nextNode.setPeek(nextNode.getPeek() - numberOfHeadValues);
				layer.peek += numberOfHeadValues;
			}
		}

		@Override
		public void mergeWithLeft(@Nonnull BPlusLeafTreeNode<M, N> previousNode) {
			final int mergePeek = previousNode.getPeek();
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer ? Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				System.arraycopy(this.keys, 0, this.keys, mergePeek + 1, this.peek + 1);
				System.arraycopy(this.values, 0, this.values, mergePeek + 1, this.peek + 1);
				System.arraycopy(previousNode.getKeys(), 0, this.keys, 0, mergePeek + 1);
				System.arraycopy(previousNode.getValues(), 0, this.values, 0, mergePeek + 1);
				this.peek += mergePeek + 1;
				previousNode.setPeek(-1);
			} else {
				// we need to decouple the arrays before modifying them
				decoupleTransactionalArrays();
				previousNode.decoupleTransactionalArrays();

				System.arraycopy(layer.keys, 0, layer.keys, mergePeek + 1, layer.peek + 1);
				System.arraycopy(layer.values, 0, layer.values, mergePeek + 1, layer.peek + 1);
				System.arraycopy(previousNode.getKeysForUpdate(), 0, layer.keys, 0, mergePeek + 1);
				System.arraycopy(previousNode.getValuesForUpdate(), 0, layer.values, 0, mergePeek + 1);
				layer.peek += mergePeek + 1;
				previousNode.setPeek(-1);
			}
		}

		@Override
		public void mergeWithRight(@Nonnull BPlusLeafTreeNode<M, N> nextNode) {
			final int mergePeek = nextNode.getPeek();
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer ? Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				System.arraycopy(nextNode.getKeys(), 0, this.keys, this.peek + 1, mergePeek + 1);
				System.arraycopy(nextNode.getValues(), 0, this.values, this.peek + 1, mergePeek + 1);
				this.peek += mergePeek + 1;
				nextNode.setPeek(-1);
			} else {
				// we need to decouple the arrays before modifying them
				decoupleTransactionalArrays();
				nextNode.decoupleTransactionalArrays();

				System.arraycopy(nextNode.getKeysForUpdate(), 0, layer.keys, layer.peek + 1, mergePeek + 1);
				System.arraycopy(nextNode.getValuesForUpdate(), 0, layer.values, layer.peek + 1, mergePeek + 1);
				layer.peek += mergePeek + 1;
				nextNode.setPeek(-1);
			}
		}

		@Nonnull
		@Override
		public M getLeftBoundaryKey() {
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer ? Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			if (layer == null) {
				return this.keys[0];
			} else {
				return layer.keys[0];
			}
		}

		/**
		 * Retrieves the keys of the current node for updating. If a transactional layer is active, it ensures
		 * that updates are performed on an independent copy of the keys array within the transactional layer.
		 *
		 * @return an array of integers representing the keys of the current node, adjusted for the transactional layer if applicable.
		 */
		@Nonnull
		public M[] getKeysForUpdate() {
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer ? Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				return this.keys;
			} else {
				// internal arrays may have been still identical to the original arrays
				// we need to copy them in the transactional layer, before modifying

				//noinspection ArrayEquality
				if (layer.keys == this.keys) {
					//noinspection unchecked
					layer.keys = (M[]) Array.newInstance(this.keys.getClass().getComponentType(), this.keys.length);
					System.arraycopy(this.keys, 0, layer.keys, 0, this.keys.length);
				}
				return layer.keys;
			}
		}

		/**
		 * Retrieves the values of the current node, but only for a READ-ONLY purposes.
		 *
		 * @return an array of values representing the values of the current node.
		 */
		@Nonnull
		public N[] getValues() {
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer ? Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			if (layer == null) {
				return this.values;
			} else {
				return layer.values;
			}
		}

		/**
		 * Retrieves the values of the current node for updating. If a transactional layer is active, it ensures
		 * that updates are performed on an independent copy of the values array within the transactional layer.
		 *
		 * @return an array of values representing the values of the current node, adjusted for the transactional layer if applicable.
		 */
		@Nonnull
		public N[] getValuesForUpdate() {
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer ? Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				return this.values;
			} else {
				// internal arrays may have been still identical to the original arrays
				// we need to copy them in the transactional layer, before modifying

				//noinspection ArrayEquality
				if (layer.values == this.values) {
					//noinspection unchecked
					layer.values = (N[]) Array.newInstance(this.values.getClass().getComponentType(), this.values.length);
					System.arraycopy(this.values, 0, layer.values, 0, this.values.length);
				}
				return layer.values;
			}
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
		public Optional<N> getValue(@Nonnull M key) {
			final M[] theKeys;
			final N[] theValues;
			final int thePeek;

			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer ? Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			if (layer == null) {
				theKeys = this.keys;
				theValues = this.values;
				thePeek = this.peek;
			} else {
				theKeys = layer.keys;
				theValues = layer.values;
				thePeek = layer.peek;
			}

			final InsertionPosition insertionPosition = computeInsertPositionOfObjInOrderedArray(key, theKeys, 0, thePeek + 1);
			return insertionPosition.alreadyPresent() ?
				Optional.of(theValues[insertionPosition.position()]) : Optional.empty();
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
		public Optional<ValueWithIndex<N>> getValueWithIndex(@Nonnull M key) {
			final M[] theKeys;
			final N[] theValues;
			final int thePeek;

			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer ? Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			if (layer == null) {
				theKeys = this.keys;
				theValues = this.values;
				thePeek = this.peek;
			} else {
				theKeys = layer.keys;
				theValues = layer.values;
				thePeek = layer.peek;
			}

			final InsertionPosition insertionPosition = computeInsertPositionOfObjInOrderedArray(key, theKeys, 0, thePeek + 1);
			return insertionPosition.alreadyPresent() ?
				Optional.of(new ValueWithIndex<>(insertionPosition.position(), theValues[insertionPosition.position()])) : Optional.empty();
		}

		@Override
		public String toString() {
			final StringBuilder sb = new StringBuilder(DEFAULT_VALUE_BLOCK_SIZE);
			toVerboseString(sb, 0, 3);
			return sb.toString();
		}

		@Override
		public BPlusLeafTreeNode<M, N> createLayer() {
			return new BPlusLeafTreeNode<>(
				this.keys,
				this.values,
				this.keys,
				this.values,
				0,
				this.peek + 1,
				false,
				this.transactionalLayerWrapper
			);
		}

		@Override
		public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			transactionalLayer.removeTransactionalMemoryLayer(this);
		}

		@Nonnull
		@Override
		public BPlusLeafTreeNode<M, N> createCopyWithMergedTransactionalMemory(
			@Nullable BPlusLeafTreeNode<M, N> layer,
			@Nonnull TransactionalLayerMaintainer transactionalLayer
		) {
			final M[] theKeys;
			final N[] theValues;
			final int thePeek;
			if (layer == null) {
				theKeys = this.keys;
				theValues = this.values;
				thePeek = this.peek;
			} else {
				theKeys = layer.keys;
				theValues = layer.values;
				thePeek = layer.peek;
			}

			N[] newValues = null;
			if (TransactionalLayerProducer.class.isAssignableFrom(this.values.getClass().getComponentType())) {
				for (int i = 0; i < thePeek + 1; i++) {
					// this.transactionalLayerWrapper is not null, because the values are transactional layers
					//noinspection unchecked,DataFlowIssue
					final N value = this.transactionalLayerWrapper.apply(
						transactionalLayer.getStateCopyWithCommittedChanges(
							(TransactionalLayerProducer<?, ? extends N>) theValues[i]
						)
					);
					if (newValues == null && value != theValues[i]) {
						//noinspection unchecked
						newValues = (N[]) Array.newInstance(this.values.getClass().getComponentType(), theValues.length);
						System.arraycopy(theValues, 0, newValues, 0, i);
					}
					if (newValues != null) {
						newValues[i] = value;
					}
				}
			}

			if (newValues != null) {
				return new BPlusLeafTreeNode<>(
					theKeys,
					newValues,
					thePeek,
					true,
					this.transactionalLayerWrapper
				);
			} else if (layer != null) {
				return new BPlusLeafTreeNode<>(
					theKeys,
					theValues,
					thePeek,
					true,
					this.transactionalLayerWrapper
				);
			} else {
				return this;
			}
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
		public boolean delete(@Nonnull M key) {
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer ? Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				final int index = Arrays.binarySearch(this.keys, 0, this.peek + 1, key);

				if (index >= 0) {
					removeRecordFromSameArrayOnIndex(this.keys, index);
					removeRecordFromSameArrayOnIndex(this.values, index);
					this.keys[this.peek] = null;
					this.values[this.peek] = null;
					this.peek--;
					return true;
				} else {
					return false;
				}
			} else {
				decoupleTransactionalArrays();
				final int index = Arrays.binarySearch(layer.keys, 0, layer.peek + 1, key);

				if (index >= 0) {
					removeRecordFromSameArrayOnIndex(layer.keys, index);
					removeRecordFromSameArrayOnIndex(layer.values, index);
					layer.keys[layer.peek] = null;
					layer.values[layer.peek] = null;
					layer.peek--;
					return true;
				} else {
					return false;
				}
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
		private boolean insert(@Nonnull M key, @Nonnull N value) {
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer ? Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				Assert.isPremiseValid(
					this.peek < this.keys.length - 1,
					"Cannot insert into a full leaf node, split the node first!"
				);

				final InsertionPosition insertionPosition = computeInsertPositionOfObjInOrderedArray(key, this.keys, 0, this.peek + 1);
				if (insertionPosition.alreadyPresent()) {
					this.keys[insertionPosition.position()] = key;
					this.values[insertionPosition.position()] = value;
					return false;
				} else {
					insertRecordIntoSameArrayOnIndex(key, this.keys, insertionPosition.position());
					insertRecordIntoSameArrayOnIndex(value, this.values, insertionPosition.position());
					this.peek++;
					return true;
				}
			} else {
				decoupleTransactionalArrays();
				Assert.isPremiseValid(
					layer.peek < layer.keys.length - 1,
					"Cannot insert into a full leaf node, split the node first!"
				);

				final InsertionPosition insertionPosition = computeInsertPositionOfObjInOrderedArray(key, layer.keys, 0, layer.peek + 1);
				if (insertionPosition.alreadyPresent()) {
					layer.keys[insertionPosition.position()] = key;
					layer.values[insertionPosition.position()] = value;
					return false;
				} else {
					insertRecordIntoSameArrayOnIndex(key, layer.keys, insertionPosition.position());
					insertRecordIntoSameArrayOnIndex(value, layer.values, insertionPosition.position());
					layer.peek++;
					return true;
				}
			}
		}

		/**
		 * Internal arrays may have been still identical to the original arrays we need to copy them in
		 * the transactional layer before modifying.
		 */
		private void decoupleTransactionalArrays() {
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer ? Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer != null) {
				//noinspection ArrayEquality
				if (layer.keys == this.keys) {
					//noinspection unchecked
					layer.keys = (M[]) Array.newInstance(this.keys.getClass().getComponentType(), this.keys.length);
					System.arraycopy(this.keys, 0, layer.keys, 0, this.peek + 1);
				}
				//noinspection ArrayEquality
				if (layer.values == this.values) {
					//noinspection unchecked
					layer.values = (N[]) Array.newInstance(this.values.getClass().getComponentType(), this.values.length);
					System.arraycopy(this.values, 0, layer.values, 0, this.peek + 1);
				}
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
	 * @param path                     The path representing the sequence of nodes traversed to reach the current node.
	 * @param level                    The current level in the tree where the cursor is positioned.
	 * @param currentNodeOfGenericType The current node at the given level. The node can be passed from the outside
	 *                                 if the current node in the path was replaced by another instance.
	 */
	private record CursorWithLevel<M extends Comparable<M>>(
		@Nonnull List<CursorLevel<M>> path,
		int level,
		@Nonnull BPlusTreeNode<M, ?> currentNodeOfGenericType
	) {

		public CursorWithLevel(@Nonnull List<CursorLevel<M>> path, int level) {
			this(path, level, path.get(level).currentNode());
		}

		/**
		 * Retrieves the current node of the type parameter in the B+ Tree structure.
		 * The current node might represent a replaced node in the structure.
		 *
		 * @return the current node of the generic type {@code N} in the B+ Tree.
		 */
		@Nonnull
		public <N extends BPlusTreeNode<M, N>> N currentNode() {
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
		 * otherwise {@code null}.
		 */
		@Nullable
		public BPlusInternalTreeNode<M> parent() {
			if (this.level > 0) {
				final CursorLevel<M> parentLevel = this.path.get(this.level - 1);
				//noinspection unchecked
				return (BPlusInternalTreeNode<M>) parentLevel.siblings()[parentLevel.index()];
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
		 * if the current level is greater than 0, otherwise {@code null}.
		 */
		@Nullable
		public CursorWithLevel<M> toParentLevel() {
			return this.level > 0 ? new CursorWithLevel<>(this.path(), this.level - 1) : null;
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
		 * otherwise {@code null}.
		 */
		@Nullable
		public CursorWithLevel<M> getCursorForPreviousNode() {
			final CursorLevel<M> cursorLevel = this.path.get(this.level);
			if (cursorLevel.index() > 0) {
				// easy case - we can just move to the previous sibling
				final List<CursorLevel<M>> replacedPath = new ArrayList<>(this.path);
				// we need to replace all levels from the current level up to the original one with the new path
				CursorLevel<M> newCursorLevel = new CursorLevel<>(
					cursorLevel.siblings(),
					cursorLevel.index() - 1,
					cursorLevel.peek()
				);
				replacedPath.set(this.level, newCursorLevel);
				// all levels below, will point to the last child of the new cursor level
				for (int i = this.level + 1; i < this.path().size(); i++) {
					final BPlusInternalTreeNode<M> currentNode = newCursorLevel.currentNode();
					newCursorLevel = new CursorLevel<>(
						currentNode.getChildren(),
						currentNode.getPeek(),
						currentNode.getPeek()
					);
					replacedPath.set(i, newCursorLevel);
				}
				// return new cursor with the replaced path
				return new CursorWithLevel<>(
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
		 * otherwise {@code null}.
		 */
		@Nullable
		public CursorWithLevel<M> getCursorForNextNode() {
			final CursorLevel<M> cursorLevel = this.path.get(this.level);
			if (cursorLevel.index() < cursorLevel.peek()) {
				// easy case - we can just move to the next sibling
				final List<CursorLevel<M>> replacedPath = new ArrayList<>(this.path);
				// we need to replace all levels from the current level up to the original one with the new path
				CursorLevel<M> newCursorLevel = new CursorLevel<>(
					cursorLevel.siblings(),
					cursorLevel.index() + 1,
					cursorLevel.peek()
				);
				replacedPath.set(this.level, newCursorLevel);
				// all levels below, will point to the first child of the new cursor level
				for (int i = this.level + 1; i < this.path.size(); i++) {
					final BPlusInternalTreeNode<M> currentNode = newCursorLevel.currentNode();
					newCursorLevel = new CursorLevel<>(currentNode.getChildren(), 0, currentNode.getPeek());
					replacedPath.set(i, newCursorLevel);
				}
				// return new cursor with the replaced path
				return new CursorWithLevel<>(
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
		 * while retaining the original path and level.
		 */
		@Nonnull
		public <N extends BPlusTreeNode<M, N>> CursorWithLevel<M> withReplacedCurrentNode(@Nonnull N node) {
			return new CursorWithLevel<>(
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
	 * @param <M>  The type of key stored in the B+ tree nodes.
	 * @param <N>  The type of value stored in the B+ tree nodes.
	 */
	private record Cursor<M extends Comparable<M>, N>(
		@Nonnull List<CursorLevel<M>> path
	) {

		/**
		 * Retrieves the leaf node of the B+ tree at the deepest level of the current path.
		 * This method accesses the last `CursorLevel` in the path to identify and return the
		 * corresponding leaf node in the structure.
		 *
		 * @return The leaf node of the B+ tree at the location specified by the current path.
		 * Guaranteed to be non-null.
		 */
		@Nonnull
		public BPlusLeafTreeNode<M, N> leafNode() {
			final CursorLevel<M> deepestLevel = this.path.get(this.path.size() - 1);
			//noinspection unchecked
			return (BPlusLeafTreeNode<M, N>) deepestLevel.siblings()[deepestLevel.index()];
		}

		/**
		 * Converts the current Cursor instance into a CursorWithLevel object.
		 * The resulting CursorWithLevel encapsulates the same path as the current Cursor
		 * along with the level information of the deepest node in the structure.
		 *
		 * @return A new CursorWithLevel object containing the path and the index of the
		 * deepest level in the path. Guaranteed to be non-null.
		 */
		@Nonnull
		public CursorWithLevel<M> toCursorWithLevel() {
			return new CursorWithLevel<>(this.path, this.path.size() - 1);
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
	private record CursorLevel<M extends Comparable<M>>(
		@Nonnull BPlusTreeNode<M, ?>[] siblings,
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
		public <N extends BPlusTreeNode<M, N>> N currentNode() {
			//noinspection unchecked
			return (N) this.siblings[index];
		}
	}

	/**
	 * Represents a node along with its associated index. This class is a record that holds an integer index
	 * and a non-nullable node.
	 *
	 * @param node  the non-null node associated with the index
	 * @param index the index associated with the value
	 */
	private record NodeWithIndex<M extends Comparable<M>>(
		@Nonnull BPlusTreeNode<M, ?> node,
		int index
	) {
	}

	/**
	 * Represents a value along with its associated index. This class is a record that holds an integer index
	 * and a non-nullable value.
	 *
	 * @param <N>   the type of the value
	 * @param index the index associated with the value
	 * @param value the non-null value associated with the index
	 */
	private record ValueWithIndex<N>(
		int index,
		@Nonnull N value
	) {
	}

	/**
	 * Iterator that traverses the B+ Tree from left to right.
	 */
	private static abstract class AbstractForwardTreeIterator<M extends Comparable<M>, N, S> implements Iterator<S> {
		/**
		 * Array of arrays representing siblings on each level of the path.
		 */
		@Nonnull private final BPlusTreeNode<M, ?>[][] path;
		/**
		 * The index of the current key on particular path.
		 */
		@Nonnull private final int[] pathIndex;
		/**
		 * The peek index of each sibling array on the path.
		 */
		@Nonnull private final int[] pathPeeks;
		/**
		 * Function allowing to extract the iterator output from the current key and value.
		 */
		@Nonnull private final IntObjBiFunction<BPlusLeafTreeNode<M, N>, S> outputExtractor;
		/**
		 * The index of the current key within the current leaf node.
		 */
		private int currentIndex;
		/**
		 * Flag indicating whether there are more elements to traverse.
		 */
		private boolean hasNext;

		public AbstractForwardTreeIterator(@Nonnull Cursor<M, N> cursor, @Nonnull IntObjBiFunction<BPlusLeafTreeNode<M, N>, S> outputExtractor) {
			//noinspection unchecked
			this.path = new BPlusTreeNode[cursor.path().size()][];
			this.pathIndex = new int[this.path.length];
			this.pathPeeks = new int[this.path.length];
			for (int i = 0; i < cursor.path().size(); i++) {
				final CursorLevel<M> cursorLevel = cursor.path().get(i);
				this.path[i] = cursorLevel.siblings();
				this.pathIndex[i] = cursorLevel.index();
				this.pathPeeks[i] = cursorLevel.peek();
			}
			this.currentIndex = 0;
			this.hasNext = this.path[this.path.length - 1][this.pathIndex[this.pathIndex.length - 1]].getPeek() >= 0;
			this.outputExtractor = outputExtractor;
		}

		public AbstractForwardTreeIterator(@Nonnull Cursor<M, N> cursor, @Nonnull M key, @Nonnull IntObjBiFunction<BPlusLeafTreeNode<M, N>, S> outputExtractor) {
			//noinspection unchecked
			this.path = new BPlusTreeNode[cursor.path().size()][];
			this.pathIndex = new int[this.path.length];
			this.pathPeeks = new int[this.path.length];
			for (int i = 0; i < cursor.path().size(); i++) {
				final CursorLevel<M> cursorLevel = cursor.path().get(i);
				this.path[i] = cursorLevel.siblings();
				this.pathIndex[i] = cursorLevel.index();
				this.pathPeeks[i] = cursorLevel.peek();
			}
			final InsertionPosition insertionPosition = computeInsertPositionOfObjInOrderedArray(key, cursor.leafNode().getKeys(), 0, cursor.leafNode().size());
			this.currentIndex = insertionPosition.position();
			this.hasNext = this.path[this.path.length - 1][this.pathIndex[this.pathIndex.length - 1]].getPeek() >= insertionPosition.position();
			this.outputExtractor = outputExtractor;
		}

		@Override
		public boolean hasNext() {
			return this.hasNext;
		}

		@Nonnull
		@Override
		public S next() {
			if (!this.hasNext) {
				throw new NoSuchElementException("No more elements available");
			}
			//noinspection unchecked
			final BPlusLeafTreeNode<M, N> currentLeaf = (BPlusLeafTreeNode<M, N>) this.path[this.path.length - 1][this.pathIndex[this.pathIndex.length - 1]];
			final S key = this.outputExtractor.apply(this.currentIndex, currentLeaf);

			if (this.currentIndex < currentLeaf.getPeek()) {
				// easy path, there is another key in current leaf
				this.currentIndex++;
			} else {
				// we need to traverse up the path to find the next sibling
				int level = this.pathIndex.length - 1;
				BPlusTreeNode<?, ?>[] parentLevel = this.path[level];
				while (parentLevel != null) {
					// if parent has index greater than zero
					if (this.pathIndex[level] < this.pathPeeks[level]) {
						// we found the parent that has a next sibling - so move the index
						this.pathIndex[level] = this.pathIndex[level] + 1;
						BPlusTreeNode<?, ?> currentNode = this.path[level][this.pathIndex[level]];
						// all levels below, will point to the first child of the new cursor level
						for (int i = level + 1; i <= this.path.length - 1; i++) {
							Assert.isPremiseValid(currentNode instanceof BPlusInternalTreeNode, "Internal node expected!");
							//noinspection unchecked
							this.path[i] = ((BPlusInternalTreeNode<M>) currentNode).getChildren();
							this.pathIndex[i] = 0;
							this.pathPeeks[i] = currentNode.getPeek();
							currentNode = this.path[i][0];
						}
						this.currentIndex = 0;
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
	}

	/**
	 * Iterator that traverses the B+ Tree from right to left.
	 */
	private static class AbstractReverseTreeIterator<M extends Comparable<M>, N, S> implements Iterator<S> {
		/**
		 * Array of arrays representing siblings on each level of the path.
		 */
		@Nonnull private final BPlusTreeNode<M, ?>[][] path;
		/**
		 * The index of the current key on particular path.
		 */
		@Nonnull private final int[] pathIndex;
		/**
		 * Function allowing to extract the iterator output from the current key and value.
		 */
		@Nonnull private final IntObjBiFunction<BPlusLeafTreeNode<M, N>, S> outputExtractor;
		/**
		 * The index of the current key within the current leaf node.
		 */
		private int currentIndex;
		/**
		 * Flag indicating whether there are more elements to traverse.
		 */
		private boolean hasNext;

		public AbstractReverseTreeIterator(@Nonnull Cursor<M, N> cursor, @Nonnull IntObjBiFunction<BPlusLeafTreeNode<M, N>, S> outputExtractor) {
			//noinspection unchecked
			this.path = new BPlusTreeNode[cursor.path().size()][];
			this.pathIndex = new int[this.path.length];
			for (int i = 0; i < cursor.path().size(); i++) {
				final CursorLevel<M> cursorLevel = cursor.path().get(i);
				this.path[i] = cursorLevel.siblings();
				this.pathIndex[i] = cursorLevel.index();
			}
			this.currentIndex = this.path[this.path.length - 1][this.pathIndex[this.pathIndex.length - 1]].getPeek();
			this.hasNext = this.currentIndex >= 0;
			this.outputExtractor = outputExtractor;
		}

		public AbstractReverseTreeIterator(@Nonnull Cursor<M, N> cursor, @Nonnull M key, @Nonnull IntObjBiFunction<BPlusLeafTreeNode<M, N>, S> outputExtractor) {
			//noinspection unchecked
			this.path = new BPlusTreeNode[cursor.path().size()][];
			this.pathIndex = new int[this.path.length];
			for (int i = 0; i < cursor.path().size(); i++) {
				final CursorLevel<M> cursorLevel = cursor.path().get(i);
				this.path[i] = cursorLevel.siblings();
				this.pathIndex[i] = cursorLevel.index();
			}
			final InsertionPosition insertionPosition = computeInsertPositionOfObjInOrderedArray(key, cursor.leafNode().getKeys(), 0, cursor.leafNode().size());
			if (insertionPosition.alreadyPresent()) {
				this.currentIndex = insertionPosition.position();
				this.hasNext = true;
			} else {
				this.currentIndex = insertionPosition.position() - 1;
				this.hasNext = true;
				if (this.currentIndex < 0) {
					calculateNextValue();
				}
			}
			this.outputExtractor = outputExtractor;
		}

		@Override
		public boolean hasNext() {
			return this.hasNext;
		}

		@Nonnull
		@Override
		public S next() {
			if (!this.hasNext) {
				throw new NoSuchElementException("No more elements available");
			}
			//noinspection unchecked
			final BPlusLeafTreeNode<M, N> currentLeaf = (BPlusLeafTreeNode<M, N>) this.path[this.path.length - 1][this.pathIndex[this.pathIndex.length - 1]];
			final S key = this.outputExtractor.apply(this.currentIndex, currentLeaf);
			if (this.currentIndex > 0) {
				// easy path, there is another key in current leaf
				this.currentIndex--;
			} else {
				// we need to traverse up the path to find the next sibling
				calculateNextValue();
			}
			return key;
		}

		/**
		 * Iterates through the path of B+ tree nodes in reverse order to calculate the next valid entry.
		 * The method updates internal tracking indexes and flags to determine if there is a next valid entry.
		 *
		 * The method operates by traversing the parent nodes from the current position, and:
		 * 1. Checks if the current parent node has a previous sibling node that can be accessed.
		 * 2. If such a sibling exists, updates internal paths and indices to point to the next valid value.
		 * 3. Ensures that the search continues upwards in the tree hierarchy if no valid sibling is found.
		 * 4. Verifies consistency for internal tree nodes during updates.
		 *
		 * This method sets the `hasNext` field to true if a next valid value is found, and false otherwise.
		 * Additionally, it updates the current index (`currentIndex`) to reflect the new position of the iterator.
		 */
		private void calculateNextValue() {
			boolean found = false;
			int level = this.pathIndex.length - 1;
			BPlusTreeNode<M, ?>[] parentLevel = this.path[level];
			while (parentLevel != null) {
				// if parent has index greater than zero
				if (this.pathIndex[level] > 0) {
					// we found the parent that has a next sibling - so move the index
					this.pathIndex[level] = this.pathIndex[level] - 1;
					BPlusTreeNode<M, ?> currentNode = this.path[level][this.pathIndex[level]];
					// all levels below, will point to the first child of the new cursor level
					for (int i = level + 1; i <= this.pathIndex.length - 1; i++) {
						Assert.isPremiseValid(currentNode instanceof BPlusInternalTreeNode, "Internal node expected!");
						//noinspection unchecked
						this.path[i] = ((BPlusInternalTreeNode<M>) currentNode).getChildren();
						this.pathIndex[i] = currentNode.getPeek();
						currentNode = this.path[i][this.pathIndex[i]];
					}
					this.hasNext = true;
					this.currentIndex = this.path[this.path.length - 1][this.pathIndex[this.pathIndex.length - 1]].getPeek();
					found = true;
					break;
				} else {
					// we need to continue search with the parent of the parent
					level--;
					parentLevel = level > 0 ? this.path[level] : null;
				}
			}
			this.hasNext = found;
		}
	}

	/**
	 * Iterator that traverses the B+ Tree from left to right.
	 */
	private static class ForwardTreeKeyIterator<M extends Comparable<M>, N> extends AbstractForwardTreeIterator<M, N, M> {

		public ForwardTreeKeyIterator(@Nonnull Cursor<M, N> cursor) {
			super(cursor, (index, leafNode) -> leafNode.getKeys()[index]);
		}

		public ForwardTreeKeyIterator(@Nonnull Cursor<M, N> cursor, @Nonnull M key) {
			super(cursor, key, (index, leafNode) -> leafNode.getKeys()[index]);
		}
	}

	/**
	 * Iterator that traverses the B+ Tree from right to left.
	 */
	private static class ReverseTreeKeyIterator<M extends Comparable<M>, N> extends AbstractReverseTreeIterator<M, N, M> {

		public ReverseTreeKeyIterator(@Nonnull Cursor<M, N> cursor) {
			super(cursor, (index, leafNode) -> leafNode.getKeys()[index]);
		}

		public ReverseTreeKeyIterator(@Nonnull Cursor<M, N> cursor, @Nonnull M key) {
			super(cursor, key, (index, leafNode) -> leafNode.getKeys()[index]);
		}

	}

	/**
	 * Iterator that traverses the B+ Tree from left to right.
	 */
	static class ForwardTreeValueIterator<M extends Comparable<M>, N> extends AbstractForwardTreeIterator<M, N, N> {

		public ForwardTreeValueIterator(@Nonnull Cursor<M, N> cursor) {
			super(cursor, (index, leafNode) -> leafNode.getValues()[index]);
		}

		public ForwardTreeValueIterator(@Nonnull Cursor<M, N> cursor, @Nonnull M key) {
			super(cursor, key, (index, leafNode) -> leafNode.getValues()[index]);
		}
	}

	/**
	 * Iterator that traverses the B+ Tree from right to left.
	 */
	static class ReverseTreeValueIterator<M extends Comparable<M>, N> extends AbstractReverseTreeIterator<M, N, N> {

		public ReverseTreeValueIterator(@Nonnull Cursor<M, N> cursor) {
			super(cursor, (index, leafNode) -> leafNode.getValues()[index]);
		}

		public ReverseTreeValueIterator(@Nonnull Cursor<M, N> cursor, @Nonnull M key) {
			super(cursor, key, (index, leafNode) -> leafNode.getValues()[index]);
		}

	}

	/**
	 * Iterator that traverses the B+ Tree from left to right and provides access to entries (both keys and values).
	 */
	static class ForwardTreeEntryIterator<M extends Comparable<M>, N> extends AbstractForwardTreeIterator<M, N, Entry<M, N>> {

		public ForwardTreeEntryIterator(@Nonnull Cursor<M, N> cursor) {
			super(cursor, (index, leafNode) -> new Entry<>(leafNode.getKeys()[index], leafNode.getValues()[index]));
		}

		public ForwardTreeEntryIterator(@Nonnull Cursor<M, N> cursor, @Nonnull M key) {
			super(cursor, key, (index, leafNode) -> new Entry<>(leafNode.getKeys()[index], leafNode.getValues()[index]));
		}
	}

	/**
	 * Iterator that traverses the B+ Tree from left to right and provides access to entries (both keys and values).
	 */
	static class ReverseTreeEntryIterator<M extends Comparable<M>, N> extends AbstractReverseTreeIterator<M, N, Entry<M, N>> {

		public ReverseTreeEntryIterator(@Nonnull Cursor<M, N> cursor) {
			super(cursor, (index, leafNode) -> new Entry<>(leafNode.getKeys()[index], leafNode.getValues()[index]));
		}

		public ReverseTreeEntryIterator(@Nonnull Cursor<M, N> cursor, @Nonnull M key) {
			super(cursor, key, (index, leafNode) -> new Entry<>(leafNode.getKeys()[index], leafNode.getValues()[index]));
		}

	}

	/**
	 * Entry is an immutable data structure that stores a key-value pair.
	 * The key must be of a type that implements the Comparable interface,
	 * while the value can be of any type.
	 *
	 * @param <M> the type of the key, which must extend Comparable
	 * @param <N> the type of the value
	 */
	public record Entry<M extends Comparable<M>, N>(
		@Nonnull M key,
		@Nonnull N value
	) {
	}

}
