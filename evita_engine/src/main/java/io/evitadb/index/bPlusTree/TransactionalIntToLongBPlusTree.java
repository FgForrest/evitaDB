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


import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.dataType.ConsistencySensitiveDataStructure;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.OptionalLong;
import java.util.PrimitiveIterator.OfInt;
import java.util.PrimitiveIterator.OfLong;
import java.util.function.LongUnaryOperator;

import static io.evitadb.utils.ArrayUtils.*;

/**
 * Represents a B+ Tree data structure specifically designed for integer keys and primitive `long` values.
 * The tree is balanced and allows for efficient insertion, deletion, and search operations.
 *
 * This is a faithful, zero-boxing primitive B+ tree for `int` keys with the generic
 * value type fixed to primitive `long`. The value blocks are stored as `long[]`, search returns {@link OptionalLong},
 * the value iterators are {@link OfLong} primitive iterators and the upsert updater is a {@link LongUnaryOperator}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@NotThreadSafe
public class TransactionalIntToLongBPlusTree extends AbstractTransactionalBPlusTree implements
	TransactionalLayerProducer<Void, TransactionalIntToLongBPlusTree>,
	Serializable,
	ConsistencySensitiveDataStructure {
	@Serial private static final long serialVersionUID = 124088192205606248L;
	private static final int DEFAULT_VALUE_BLOCK_SIZE = 64;
	private static final int DEFAULT_MIN_VALUE_BLOCK_SIZE = DEFAULT_VALUE_BLOCK_SIZE / 2 - 1;
	private static final int DEFAULT_INTERNAL_NODE_BLOCK_SIZE = DEFAULT_VALUE_BLOCK_SIZE / 2 - 1;
	private static final int DEFAULT_MIN_INTERNAL_NODE_BLOCK_SIZE = (int) (Math.ceil(
		(float) DEFAULT_INTERNAL_NODE_BLOCK_SIZE / 2.0) - 1);

	/**
	 * Returns the left boundary key of an arbitrary node reached through the key-agnostic {@link BPlusTreeNode} SPI
	 * (e.g. an element of an internal node's children array). The primitive-key accessor lives on the per-tree
	 * {@link IntKeyedNode} marker so it stays out of the shared SPI (which must never expose a typed key); every node
	 * in this tree implements it, so the cast is always safe.
	 *
	 * @param node the node whose left boundary key is requested
	 * @return the left boundary (smallest) key of the node
	 */
	private static int leftBoundaryKeyOf(@Nonnull BPlusTreeNode<?> node) {
		return ((IntKeyedNode) node).getLeftBoundaryKey();
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
				} else if (child instanceof BPlusLeafTreeNode childLeafNode) {
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
	 * Verifies the integrity of the forward key iterator for a given {@link TransactionalIntToLongBPlusTree}.
	 * Checks if the keys from the iterator are returned in strictly increasing order and
	 * validates the total number of keys returned matches the expected size.
	 *
	 * @param tree the {@link TransactionalIntToLongBPlusTree} whose key iterator is to be verified
	 * @param size the expected number of keys in the {@link TransactionalIntToLongBPlusTree}
	 * @throws IllegalStateException if the iterator fails to return keys in increasing order
	 *                               or if the number of keys does not match the expected size
	 */
	private static void verifyForwardKeyIterator(@Nonnull TransactionalIntToLongBPlusTree tree, int size) {
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
			throw new IllegalStateException(
				"Forward iterator returned " + actualSize + " keys, but the tree has " + size + " elements!"
			);
		}
	}

	/**
	 * Verifies the reverse key iterator of the tree by checking if the keys are
	 * returned in strictly decreasing order and the size of elements matches the expected size.
	 *
	 * @param tree the tree whose reverse key iterator is to be verified
	 * @param size the expected number of elements in the tree
	 * @throws IllegalStateException if the iterator returns non-decreasing keys or if the number of
	 *                               keys returned by the iterator does not match the expected size
	 */
	private static void verifyReverseKeyIterator(@Nonnull TransactionalIntToLongBPlusTree tree, int size) {
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
			throw new IllegalStateException(
				"Reverse iterator returned " + actualSize + " keys, but the tree has " + size + " elements!"
			);
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
		final int childIndex = currentNode.searchIndex(key);
		final BPlusTreeNode<?>[] children = currentNode.getChildren();
		path.add(new CursorLevel(children, childIndex, currentNode.getPeek()));
		// if the child is an internal node, continue traversing down the tree
		if (children[childIndex] instanceof BPlusInternalTreeNode childInternalNode) {
			addCursorLevels(childInternalNode, key, path);
		}
	}

	/**
	 * Constructor to initialize the B+ Tree with default block sizes.
	 */
	public TransactionalIntToLongBPlusTree() {
		this(
			DEFAULT_VALUE_BLOCK_SIZE,
			DEFAULT_MIN_VALUE_BLOCK_SIZE,
			DEFAULT_INTERNAL_NODE_BLOCK_SIZE,
			DEFAULT_MIN_INTERNAL_NODE_BLOCK_SIZE,
			new BPlusLeafTreeNode(DEFAULT_VALUE_BLOCK_SIZE, true),
			0
		);
	}

	/**
	 * Constructor to initialize the B+ Tree.
	 *
	 * @param valueBlockSize maximum number of values in a leaf node
	 */
	public TransactionalIntToLongBPlusTree(int valueBlockSize) {
		this(
			valueBlockSize, valueBlockSize / 2,
			valueBlockSize, valueBlockSize / 2
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
	 */
	public TransactionalIntToLongBPlusTree(
		int valueBlockSize,
		int minValueBlockSize,
		int internalNodeBlockSize,
		int minInternalNodeBlockSize
	) {
		this(
			valueBlockSize,
			minValueBlockSize,
			internalNodeBlockSize,
			minInternalNodeBlockSize,
			new BPlusLeafTreeNode(valueBlockSize, true),
			0
		);
	}

	private TransactionalIntToLongBPlusTree(
		int valueBlockSize,
		int minValueBlockSize,
		int internalNodeBlockSize,
		int minInternalNodeBlockSize,
		@Nonnull BPlusTreeNode<?> root,
		int size
	) {
		super(valueBlockSize, minValueBlockSize, internalNodeBlockSize, minInternalNodeBlockSize, root, size);
	}

	@Nonnull
	@Override
	protected BPlusTreeNode<?> newEmptyLeaf() {
		return new BPlusLeafTreeNode(this.valueBlockSize, true);
	}

	/**
	 * Inserts a key-value pair into the B+ tree. If the corresponding leaf node
	 * overflows, it is split to maintain the properties of the tree.
	 *
	 * @param key   the key to be inserted into the B+ tree
	 * @param value the value associated with the key
	 */
	public void insert(int key, long value) {
		final Cursor cursor = createCursor(key);
		final BPlusLeafTreeNode leaf = (BPlusLeafTreeNode) cursor.leafNode();
		if (leaf.insert(key, value)) {
			this.size.set(size() + 1);
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
	 * @param key     the key to update or insert
	 * @param updater a function to compute a new value, must not be null
	 */
	public void upsert(int key, @Nonnull LongUnaryOperator updater) {
		final Cursor cursor = createCursor(key);
		final BPlusLeafTreeNode leaf = (BPlusLeafTreeNode) cursor.leafNode();

		final int existingIndex = leaf.getValueIndex(key);
		if (existingIndex >= 0) {
			// update the value on specified index
			leaf.decoupleTransactionalArrays();
			final long[] values = leaf.getValues();
			final long previousValue = values[existingIndex];
			values[existingIndex] = updater.applyAsLong(previousValue);
		} else {
			// insert the new value
			if (leaf.insert(key, updater.applyAsLong(0L))) {
				this.size.set(size() + 1);
			}

			// Split the leaf node if it exceeds the block size
			if (leaf.isFull()) {
				splitLeafNode(leaf, cursor);
			}
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
		final Cursor cursor = createCursor(key);
		final BPlusLeafTreeNode leaf = (BPlusLeafTreeNode) cursor.leafNode();

		final boolean headRemoved = leaf.size() > 1 && key == leaf.getKeys()[0];
		if (leaf.delete(key)) {
			this.size.set(size() - 1);
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
	 * @return an OptionalLong containing the value associated with the key if it is present,
	 * or an empty OptionalLong if the key is not found in the tree
	 */
	@Nonnull
	public OptionalLong search(int key) {
		final Cursor cursor = createCursor(key);
		return ((BPlusLeafTreeNode) cursor.leafNode()).getValue(key);
	}

	/**
	 * Returns an iterator that traverses the B+ tree keys from left to right.
	 *
	 * @return an iterator that traverses the B+ tree keys from left to right
	 */
	@Nonnull
	public OfInt keyIterator() {
		return new ForwardTreeKeyIterator(createLeftmostCursor());
	}

	/**
	 * Returns an iterator that traverses the B+ tree keys from left to right starting from the specified key or
	 * a key that is immediately greater than the specified key. The key may not be present in the tree.
	 *
	 * @param key the key from which to start the iteration
	 * @return an iterator that traverses the B+ tree keys from left to right starting from the specified key
	 */
	@Nonnull
	public OfInt greaterOrEqualKeyIterator(int key) {
		return new ForwardTreeKeyIterator(createCursor(key), key);
	}

	/**
	 * Returns an iterator that traverses the B+ tree keys from right to left starting from the specified key or
	 * a key that is immediately lesser than the specified key. The key may not be present in the tree.
	 *
	 * @param key the key from which to start the iteration
	 * @return an iterator that traverses the B+ tree keys from right to left starting from the specified key
	 */
	@Nonnull
	public OfInt lesserOrEqualKeyIterator(int key) {
		return new ReverseTreeKeyIterator(createCursor(key), key);
	}

	/**
	 * Returns an iterator that traverses the B+ tree keys from right to left.
	 *
	 * @return an iterator that traverses the B+ tree keys from right to left
	 */
	@Nonnull
	public OfInt keyReverseIterator() {
		return new ReverseTreeKeyIterator(createRightmostCursor());
	}

	/**
	 * Returns an iterator that traverses the B+ tree values from left to right.
	 *
	 * @return an iterator that traverses the B+ tree values from left to right
	 */
	@Nonnull
	public OfLong valueIterator() {
		return new ForwardTreeValueIterator(createLeftmostCursor());
	}

	/**
	 * Returns an iterator that traverses the B+ tree values from left to right starting from the specified key or
	 * a key that is immediately greater than the specified key. The key may not be present in the tree.
	 *
	 * @param key the key from which to start the iteration
	 * @return an iterator that traverses the B+ tree values from left to right starting from the specified key
	 */
	@Nonnull
	public OfLong greaterOrEqualValueIterator(int key) {
		return new ForwardTreeValueIterator(createCursor(key), key);
	}

	/**
	 * Returns an iterator that traverses the B+ tree values from right to left starting from the specified key or
	 * a key that is immediately lesser than the specified key. The key may not be present in the tree.
	 *
	 * @param key the key from which to start the iteration
	 * @return an iterator that traverses the B+ tree values from right to left starting from the specified key
	 */
	@Nonnull
	public OfLong lesserOrEqualValueIterator(int key) {
		return new ReverseTreeValueIterator(createCursor(key), key);
	}

	/**
	 * Returns an iterator that traverses the B+ tree values from right to left.
	 *
	 * @return an iterator that traverses the B+ tree values from right to left
	 */
	@Nonnull
	public OfLong valueReverseIterator() {
		return new ReverseTreeValueIterator(createRightmostCursor());
	}

	/**
	 * Returns an iterator that traverses the B+ tree entries (both keys and values) from left to right.
	 *
	 * @return an iterator that traverses the B+ tree entries (both keys and values) from left to right
	 */
	@Nonnull
	public Iterator<Entry> entryIterator() {
		return new ForwardTreeEntryIterator(createLeftmostCursor());
	}

	/**
	 * Returns an iterator that traverses the B+ tree entries (both keys and values) from left to right starting from the specified key or
	 * a key that is immediately greater than the specified key. The key may not be present in the tree.
	 *
	 * @param key the key from which to start the iteration
	 * @return an iterator that traverses the B+ tree entries (both keys and values) from left to right starting from the specified key
	 */
	@Nonnull
	public Iterator<Entry> greaterOrEqualEntryIterator(int key) {
		return new ForwardTreeEntryIterator(createCursor(key), key);
	}

	/**
	 * Returns an iterator that traverses the B+ tree entries (both keys and values) from right to left starting from the specified key or
	 * a key that is immediately lesser than the specified key. The key may not be present in the tree.
	 *
	 * @param key the key from which to start the iteration
	 * @return an iterator that traverses the B+ tree entries (both keys and values) from right to left starting from the specified key
	 */
	@Nonnull
	public Iterator<Entry> lesserOrEqualEntryIterator(int key) {
		return new ReverseTreeEntryIterator(createCursor(key), key);
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
		// remove the tree's own diff layer
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		// recurse into the size and root references and the whole node graph so that a tree which was created
		// and discarded within the same transaction (e.g. a removed sub-index) does not leave any of its inner
		// transactional objects ALIVE - which would otherwise be detected as stale during the commit sweep
		this.size.removeLayer(transactionalLayer);
		this.root.removeLayer(transactionalLayer);
		removeLayerRecursively(getRoot(), transactionalLayer);
	}

	@Nonnull
	@Override
	public TransactionalIntToLongBPlusTree createCopyWithMergedTransactionalMemory(
		@Nullable Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		final BPlusTreeNode<?> theRoot = transactionalLayer.getStateCopyWithCommittedChanges(this.root).orElseThrow();
		if (theRoot instanceof BPlusLeafTreeNode leafNode) {
			return new TransactionalIntToLongBPlusTree(
				this.valueBlockSize, this.minValueBlockSize,
				this.internalNodeBlockSize, this.minInternalNodeBlockSize,
				transactionalLayer.getStateCopyWithCommittedChanges(leafNode),
				transactionalLayer.getStateCopyWithCommittedChanges(this.size).orElseThrow()
			);
		} else if (theRoot instanceof BPlusInternalTreeNode internalNode) {
			return new TransactionalIntToLongBPlusTree(
				this.valueBlockSize, this.minValueBlockSize,
				this.internalNodeBlockSize, this.minInternalNodeBlockSize,
				transactionalLayer.getStateCopyWithCommittedChanges(internalNode),
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
			final BPlusTreeNode<?> theRoot = getRoot();
			final int height = verifyAndReturnHeight(this);
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
	 * Finds the leaf node in the B+ tree that should contain the specified key.
	 * The method begins its search from the root node and traverses down to the leaf node
	 * by following the appropriate child pointers of internal nodes.
	 *
	 * @param key the key to search for within the B+ tree
	 * @return the cursor to the leaf node that is responsible for storing the provided key;
	 * note that the leaf may not actually contain the key - but it is the correct leaf node for accommodating it
	 */
	@Nonnull
	private Cursor createCursor(int key) {
		final ArrayList<CursorLevel> path = new ArrayList<>(this.size() == 0 ? 1 : (int) (Math.log(this.size()) + 1));
		final BPlusTreeNode<?> theRoot = this.getRoot();
		final BPlusTreeNode<?>[] rootSiblings = new BPlusTreeNode<?>[]{theRoot};
		path.add(new CursorLevel(rootSiblings, 0, 0));
		// if the root is internal node, add the levels to the path until the leaf node is reached
		if (theRoot instanceof BPlusInternalTreeNode rootInternalNode) {
			addCursorLevels(rootInternalNode, key, path);

		}
		return new Cursor(path);
	}

	/**
	 * Splits a full leaf node into two leaf nodes to maintain the properties of the B+ tree.
	 * If the split occurs at the root, a new root is created.
	 *
	 * @param leaf   The leaf node to be split
	 * @param cursor The cursor representing the path from the root to the leaf node
	 */
	private void splitLeafNode(
		@Nonnull BPlusLeafTreeNode leaf,
		@Nonnull Cursor cursor
	) {
		final int mid = this.valueBlockSize / 2;
		final int[] originKeys = leaf.getKeys();
		final long[] originValues = leaf.getValues();

		// Move half the keys to the new arrays of the left leaf node
		final BPlusLeafTreeNode leftLeaf = new BPlusLeafTreeNode(
			originKeys,
			originValues,
			new int[this.valueBlockSize],
			new long[this.valueBlockSize],
			0,
			mid,
			!Transaction.isTransactionAvailable()
		);

		// Move the other half to the start of existing arrays of former leaf in the right leaf node
		final BPlusLeafTreeNode rightLeaf = new BPlusLeafTreeNode(
			originKeys,
			originValues,
			originKeys,
			originValues,
			mid,
			leftLeaf.getKeys().length,
			!Transaction.isTransactionAvailable()
		);

		// remove changes of the previous root - it gets replaced
		if (Transaction.getTransactionalMemoryLayerIfExists(leaf) != null) {
			leaf.removeLayer();
		}

		// if the root splits, create a new root
		if (leaf == this.getRoot()) {
			// remove changes of the previous root - it gets replaced
			this.setRoot(
				new BPlusInternalTreeNode(
					this.valueBlockSize,
					rightLeaf.getKeys()[0],
					leftLeaf, rightLeaf,
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
		@Nonnull BPlusTreeNode<?> original,
		@Nonnull BPlusTreeNode<?> left,
		@Nonnull BPlusTreeNode<?> right,
		int key,
		@Nonnull CursorWithLevel cursor
	) {
		final BPlusInternalTreeNode parent = (BPlusInternalTreeNode) cursor.parent();

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
			0,
			mid - 1,
			0,
			mid,
			!Transaction.isTransactionAvailable()
		);

		// Move the other half to the start of existing arrays of former leaf in the right leaf node
		final BPlusInternalTreeNode rightInternal = new BPlusInternalTreeNode(
			originKeys,
			originChildren,
			mid,
			leftInternal.getKeys().length,
			mid,
			leftInternal.getChildren().length,
			!Transaction.isTransactionAvailable()
		);

		// remove changes of the previous root - it gets replaced
		if (Transaction.getTransactionalMemoryLayerIfExists(internal) != null) {
			internal.removeLayer();
		}

		// if the root splits, create a new root
		if (internal == this.getRoot()) {
			this.setRoot(
				new BPlusInternalTreeNode(
					this.valueBlockSize,
					rightInternal.getLeftBoundaryKey(),
					leftInternal, rightInternal,
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
	 * Per-tree typed marker that exposes the primitive `int` key accessors common to both node kinds, kept off the
	 * key-agnostic {@link BPlusTreeNode} SPI so the shared base never sees (and never boxes) a key. Typed call sites
	 * that hold only a {@link BPlusTreeNode} reference (e.g. a children-array element) cast to this to read the keys.
	 */
	interface IntKeyedNode {

		/**
		 * Retrieves an array of integer keys associated with the node.
		 *
		 * @return an array of integer keys present in the node. The array is guaranteed to be non-null.
		 */
		@Nonnull
		int[] getKeys();

		/**
		 * Retrieves the left boundary (smallest) key contained within the node.
		 *
		 * @return the left boundary key of the node.
		 */
		int getLeftBoundaryKey();

	}

	/**
	 * Internal node implementation of the B+ tree that holds keys and child node pointers. Internal nodes serve
	 * as routing nodes — they do not store values directly but guide searches to the appropriate leaf nodes.
	 */
	static class BPlusInternalTreeNode implements InternalBPlusTreeNode<BPlusInternalTreeNode>, IntKeyedNode {
		@Serial private static final long serialVersionUID = -7649742437563558159L;
		@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
		/**
		 * Indicates whether this instance is permitted to create and use transactional layers. The tree nodes use
		 * themselves (the same class) as their transactional memory layer, and if this layer were to also use
		 * transactional memory, it would create an infinite loop. This flag prevents that behavior.
		 */
		private final boolean transactionalLayer;
		/**
		 * The keys stored in this node.
		 */
		private int[] keys;

		/**
		 * The children of this node.
		 */
		private BPlusTreeNode<?>[] children;

		/**
		 * Index of the last occupied position in the children array.
		 */
		private int peek;

		/**
		 * Creates a new internal node with a single key separating two child nodes. This constructor is used
		 * when creating a new root after a split operation.
		 *
		 * @param blockSize          the maximum number of keys this node can hold
		 * @param key                the initial key separating the two child nodes
		 * @param leftLeaf           the left child node
		 * @param rightLeaf          the right child node
		 * @param transactionalLayer whether this node participates in the transactional memory layer
		 */
		public BPlusInternalTreeNode(
			int blockSize,
			int key,
			@Nonnull BPlusTreeNode<?> leftLeaf,
			@Nonnull BPlusTreeNode<?> rightLeaf,
			boolean transactionalLayer
		) {
			this.keys = new int[blockSize];
			this.children = new BPlusTreeNode[blockSize + 1];
			this.keys[0] = key;
			this.children[0] = leftLeaf;
			this.children[1] = rightLeaf;
			this.peek = 1;
			this.transactionalLayer = transactionalLayer;
		}

		/**
		 * Creates a new internal node by copying a range of keys and children from existing arrays. This constructor
		 * is used during node split operations.
		 *
		 * @param originKeys         the source array of keys to copy from
		 * @param originChildren     the source array of child nodes to copy from
		 * @param keyStart           the start index (inclusive) in the origin keys array
		 * @param keyEnd             the end index (exclusive) in the origin keys array
		 * @param childrenStart      the start index (inclusive) in the origin children array
		 * @param childrenEnd        the end index (exclusive) in the origin children array
		 * @param transactionalLayer whether this node participates in the transactional memory layer
		 */
		public BPlusInternalTreeNode(
			@Nonnull int[] originKeys,
			@Nonnull BPlusTreeNode<?>[] originChildren,
			int keyStart, int keyEnd,
			int childrenStart, int childrenEnd,
			boolean transactionalLayer
		) {
			// we always create a new array for keys and children
			this.keys = new int[originKeys.length];
			this.children = new BPlusTreeNode[originChildren.length];
			// Copy the keys and children from the origin arrays
			System.arraycopy(originKeys, keyStart, this.keys, 0, keyEnd - keyStart);
			System.arraycopy(originChildren, childrenStart, this.children, 0, childrenEnd - childrenStart);
			this.peek = childrenEnd - childrenStart - 1;
			this.transactionalLayer = transactionalLayer;
		}

		private BPlusInternalTreeNode(
			@Nonnull int[] originKeys,
			@Nonnull BPlusTreeNode<?>[] originChildren,
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
		public int[] getKeys() {
			final BPlusInternalTreeNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) :
				null;
			if (layer == null) {
				return this.keys;
			} else {
				return layer.keys;
			}
		}

		@Override
		public int getPeek() {
			final BPlusInternalTreeNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) :
				null;
			if (layer == null) {
				return this.peek;
			} else {
				return layer.peek;
			}
		}

		@Override
		public void setPeek(int peek) {
			final BPlusInternalTreeNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			if (layer == null) {
				final int originPeek = this.peek;
				this.peek = peek;
				if (peek < originPeek) {
					Arrays.fill(this.keys, Math.max(0, peek), originPeek, 0);
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
						layer.keys = new int[this.keys.length];
						System.arraycopy(this.keys, 0, layer.keys, 0, originPeek);
					} else {
						Arrays.fill(layer.keys, Math.max(0, peek), originPeek, 0);
					}
					//noinspection ArrayEquality
					if (layer.children == this.children) {
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
			final BPlusInternalTreeNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) :
				null;
			if (layer == null) {
				return Math.max(this.peek, 0);
			} else {
				return Math.max(layer.peek, 0);
			}
		}

		@Override
		public boolean isFull() {
			final BPlusInternalTreeNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) :
				null;
			if (layer == null) {
				return this.peek == this.children.length - 1;
			} else {
				return layer.peek == layer.children.length - 1;
			}
		}

		@Override
		public void toVerboseString(@Nonnull StringBuilder sb, int level, int indentSpaces) {
			final int[] theKeys;
			final BPlusTreeNode<?>[] theChildren;
			final int thePeek;
			final BPlusInternalTreeNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) :
				null;
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
				final int key = theKeys[i - 1];
				final BPlusTreeNode<?> child = theChildren[i];
				sb.append(" ".repeat(level * indentSpaces)).append(">=").append(key).append(":\n");
				child.toVerboseString(sb, level + 1, indentSpaces);
				if (i < thePeek) {
					sb.append("\n");
				}
			}
		}

		@Override
		public void stealFromLeft(int numberOfTailValues, @Nonnull BPlusInternalTreeNode previousNode) {
			Assert.isPremiseValid(numberOfTailValues > 0, "Number of tail values to steal must be positive!");

			final BPlusInternalTreeNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			if (layer == null) {
				// we preserve all the current node children
				System.arraycopy(this.children, 0, this.children, numberOfTailValues, this.peek + 1);
				// then move the children from the previous node
				System.arraycopy(
					previousNode.getChildren(), previousNode.size() - numberOfTailValues, this.children, 0,
					numberOfTailValues
				);
				// we need to preserve all the current node keys
				System.arraycopy(this.keys, 0, this.keys, numberOfTailValues, this.peek);
				// our original first child newly produces its own key
				this.keys[numberOfTailValues - 1] = leftBoundaryKeyOf(this.children[numberOfTailValues]);
				// and now we can copy the keys from the previous node - but except the first one
				System.arraycopy(
					previousNode.getKeys(), previousNode.keyCount() - numberOfTailValues + 1, this.keys, 0,
					numberOfTailValues - 1
				);
				// and update the peek indexes
				this.peek += numberOfTailValues;
				previousNode.setPeek(previousNode.getPeek() - numberOfTailValues);
			} else {
				decoupleTransactionalArrays();
				previousNode.decoupleTransactionalArrays();
				// we preserve all the current node children
				System.arraycopy(layer.children, 0, layer.children, numberOfTailValues, layer.peek + 1);
				// then move the children from the previous node
				System.arraycopy(
					previousNode.getChildrenForUpdate(), previousNode.size() - numberOfTailValues, layer.children, 0,
					numberOfTailValues
				);
				// we need to preserve all the current node keys
				System.arraycopy(layer.keys, 0, layer.keys, numberOfTailValues, layer.peek);
				// our original first child newly produces its own key
				layer.keys[numberOfTailValues - 1] = leftBoundaryKeyOf(layer.children[numberOfTailValues]);
				// and now we can copy the keys from the previous node - but except the first one
				System.arraycopy(
					previousNode.getKeysForUpdate(), previousNode.keyCount() - numberOfTailValues + 1, layer.keys, 0,
					numberOfTailValues - 1
				);
				// and update the peek indexes
				layer.peek += numberOfTailValues;
				previousNode.setPeek(previousNode.getPeek() - numberOfTailValues);
			}
		}

		@Override
		public void stealFromRight(int numberOfHeadValues, @Nonnull BPlusInternalTreeNode nextNode) {
			Assert.isPremiseValid(numberOfHeadValues > 0, "Number of head values to steal must be positive!");

			final BPlusInternalTreeNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			if (layer == null) {
				// the right sibling may be a committed (shared) node while `this` is a transaction-local node
				// (transactionalLayer == false): steal-from-right SHIFTS the sibling's arrays in place, so it must
				// decouple them first or it would corrupt the shared committed state. The ...ForUpdate accessors
				// decouple a committed sibling inside a transaction and are in-place no-ops outside one.
				final BPlusTreeNode<?>[] nextNodeChildren = nextNode.getChildrenForUpdate();
				System.arraycopy(nextNodeChildren, 0, this.children, this.peek + 1, numberOfHeadValues);
				System.arraycopy(
					nextNodeChildren, numberOfHeadValues, nextNodeChildren, 0, nextNode.size() - numberOfHeadValues);

				// set the key for the first child of the next node
				this.keys[this.peek] = leftBoundaryKeyOf(this.children[this.peek + 1]);

				// we move the keys from the next node for all copied children
				final int[] nextNodeKeys = nextNode.getKeysForUpdate();
				System.arraycopy(nextNodeKeys, 0, this.keys, this.peek + 1, numberOfHeadValues - 1);
				// we need to shift the keys in the next node
				System.arraycopy(
					nextNodeKeys, numberOfHeadValues, nextNodeKeys, 0, nextNodeKeys.length - numberOfHeadValues);

				// and update the peek indexes
				this.peek += numberOfHeadValues;
				nextNode.setPeek(nextNode.getPeek() - numberOfHeadValues);
			} else {
				decoupleTransactionalArrays();
				nextNode.decoupleTransactionalArrays();

				// we move all the children
				final BPlusTreeNode<?>[] nextNodeChildrenForUpdate = nextNode.getChildrenForUpdate();
				System.arraycopy(nextNodeChildrenForUpdate, 0, layer.children, layer.peek + 1, numberOfHeadValues);
				System.arraycopy(
					nextNodeChildrenForUpdate, numberOfHeadValues, nextNodeChildrenForUpdate, 0,
					nextNode.size() - numberOfHeadValues
				);

				// set the key for the first child of the next node
				layer.keys[layer.peek] = leftBoundaryKeyOf(layer.children[layer.peek + 1]);

				// we move the keys from the next node for all copied children
				final int[] nextNodeKeysForUpdate = nextNode.getKeysForUpdate();
				System.arraycopy(nextNodeKeysForUpdate, 0, layer.keys, layer.peek + 1, numberOfHeadValues - 1);
				// we need to shift the keys in the next node
				System.arraycopy(
					nextNodeKeysForUpdate, numberOfHeadValues, nextNodeKeysForUpdate, 0,
					nextNodeKeysForUpdate.length - numberOfHeadValues
				);

				// and update the peek indexes
				layer.peek += numberOfHeadValues;
				nextNode.setPeek(nextNode.getPeek() - numberOfHeadValues);
			}
		}

		@Override
		public void mergeWithLeft(@Nonnull BPlusInternalTreeNode previousNode) {
			// merging into an empty internal node (peek == -1) is never requested by the rebalancer: a node
			// with a single child (peek == 0) is collapsed before another deletion could drain it further,
			// so the shift arithmetic below assumes this node already holds at least one child
			Assert.isPremiseValid(
				getPeek() >= 0, "Cannot merge into an empty internal node (it has no children)!"
			);
			final int mergePeek = previousNode.getPeek();

			final BPlusInternalTreeNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			if (layer == null) {
				System.arraycopy(this.keys, 0, this.keys, mergePeek + 1, this.peek);
				this.keys[mergePeek] = leftBoundaryKeyOf(this.children[0]);
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
				layer.keys[mergePeek] = leftBoundaryKeyOf(layer.children[0]);
				System.arraycopy(layer.children, 0, layer.children, mergePeek + 1, layer.peek + 1);
				System.arraycopy(previousNode.getKeysForUpdate(), 0, layer.keys, 0, mergePeek);
				System.arraycopy(previousNode.getChildrenForUpdate(), 0, layer.children, 0, mergePeek + 1);
				layer.peek += mergePeek + 1;
				previousNode.setPeek(-1);
			}
		}

		@Override
		public void mergeWithRight(@Nonnull BPlusInternalTreeNode nextNode) {
			// merging into an empty internal node (peek == -1) is never requested by the rebalancer: a node
			// with a single child (peek == 0) is collapsed before another deletion could drain it further,
			// so the separator-key write below assumes this node already holds at least one child
			Assert.isPremiseValid(
				getPeek() >= 0, "Cannot merge into an empty internal node (it has no children)!"
			);
			final int mergePeek = nextNode.getPeek();

			final BPlusInternalTreeNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			if (layer == null) {
				System.arraycopy(nextNode.getChildren(), 0, this.children, this.peek + 1, mergePeek + 1);
				this.keys[this.peek] = leftBoundaryKeyOf(nextNode.getChildren()[0]);
				System.arraycopy(nextNode.getKeys(), 0, this.keys, this.peek + 1, mergePeek);
				this.peek += mergePeek + 1;
				nextNode.setPeek(-1);
			} else {
				decoupleTransactionalArrays();
				// we don't need to do: nodeToMergeWith.decoupleTransactionalArrays();
				// the other node will be fully merged to this node, so its arrays remain unmodified by this operation
				System.arraycopy(nextNode.getChildrenForUpdate(), 0, layer.children, layer.peek + 1, mergePeek + 1);
				layer.keys[layer.peek] = leftBoundaryKeyOf(layer.children[layer.peek + 1]);
				System.arraycopy(nextNode.getKeysForUpdate(), 0, layer.keys, layer.peek + 1, mergePeek);
				layer.peek += mergePeek + 1;
				nextNode.setPeek(-1);
			}
		}

		public int getLeftBoundaryKey() {
			final BPlusInternalTreeNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) :
				null;
			if (layer == null) {
				return leftBoundaryKeyOf(this.children[0]);
			} else {
				return leftBoundaryKeyOf(layer.children[0]);
			}
		}

		/**
		 * Retrieves the keys of the current node for updating. If a transactional layer is active, it ensures
		 * that updates are performed on an independent copy of the keys array within the transactional layer.
		 *
		 * @return an array of integers representing the keys of the current node, adjusted for the transactional layer if applicable.
		 */
		@Nonnull
		public int[] getKeysForUpdate() {
			final BPlusInternalTreeNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			if (layer == null) {
				return this.keys;
			} else {
				// internal arrays may have been still identical to the original arrays
				// we need to copy them in the transactional layer, before modifying

				//noinspection ArrayEquality
				if (layer.keys == this.keys) {
					layer.keys = new int[this.keys.length];
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
		public BPlusTreeNode<?>[] getChildren() {
			final BPlusInternalTreeNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) :
				null;
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
		public BPlusTreeNode<?>[] getChildrenForUpdate() {
			final BPlusInternalTreeNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			if (layer == null) {
				return this.children;
			} else {
				// internal arrays may have been still identical to the original arrays
				// we need to copy them in the transactional layer, before modifying

				//noinspection ArrayEquality
				if (layer.children == this.children) {
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
			int key,
			@Nonnull BPlusTreeNode<?> original,
			@Nonnull BPlusTreeNode<?> left,
			@Nonnull BPlusTreeNode<?> right
		) {
			Assert.isPremiseValid(
				!this.isFull(),
				"Internal node must not be full to accommodate two leaf nodes after their split!"
			);

			final BPlusInternalTreeNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			if (layer == null) {
				// the peek relates to children, which are one more than keys, that's why we don't use peek + 1, but mere peek
				final InsertionPosition insertionPosition = computeInsertPositionOfIntInOrderedArray(
					key, this.keys, 0, this.peek);
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
			} else {
				decoupleTransactionalArrays();

				// the peek relates to children, which are one more than keys, that's why we don't use peek + 1, but mere peek
				final InsertionPosition insertionPosition = computeInsertPositionOfIntInOrderedArray(
					key, layer.keys, 0, layer.peek);
				Assert.isPremiseValid(
					original == layer.children[insertionPosition.position()],
					"Original node must be the child of the internal node!"
				);
				Assert.isPremiseValid(
					!insertionPosition.alreadyPresent(),
					"Key already present in the internal node!"
				);

				insertIntIntoSameArrayOnIndex(key, layer.keys, insertionPosition.position());
				layer.children[insertionPosition.position()] = left;
				insertRecordIntoSameArrayOnIndex(right, layer.children, insertionPosition.position() + 1);
				layer.peek++;
			}
		}

		/**
		 * Searches for the child index that should contain the given key.
		 * This method avoids allocating a NodeWithIndex record.
		 *
		 * @param key the integer key to search for within the B+ Tree.
		 * @return the index of the child that should contain the specified key.
		 */
		public int searchIndex(int key) {
			final BPlusInternalTreeNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) :
				null;
			if (layer == null) {
				final InsertionPosition insertionPosition = computeInsertPositionOfIntInOrderedArray(
					key, this.keys, 0, this.peek);
				return insertionPosition.alreadyPresent() ?
					insertionPosition.position() + 1 : insertionPosition.position();
			} else {
				final InsertionPosition insertionPosition = computeInsertPositionOfIntInOrderedArray(
					key, layer.keys, 0, layer.peek);
				return insertionPosition.alreadyPresent() ?
					insertionPosition.position() + 1 : insertionPosition.position();
			}
		}

		/**
		 * Searches for the BPlusTreeNode that should contain the given key.
		 *
		 * @param key the integer key to search for within the B+ Tree.
		 * @return the BPlusTreeNode that should contain the specified key.
		 */
		@Nonnull
		public NodeWithIndex search(int key) {
			final int thePosition = searchIndex(key);
			return new NodeWithIndex(getChildren()[thePosition], thePosition);
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
			final BPlusInternalTreeNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			if (layer == null) {
				removeIntFromSameArrayOnIndex(this.keys, keyIndex);
				this.keys[this.peek - 1] = 0;
				removeRecordFromSameArrayOnIndex(this.children, childIndex);
				this.children[this.peek] = null;
				this.peek--;
			} else {
				decoupleTransactionalArrays();

				removeIntFromSameArrayOnIndex(layer.keys, keyIndex);
				layer.keys[layer.peek - 1] = 0;

				// the removed children may have had its own transactional layer, which needs to be removed
				if (Transaction.getTransactionalMemoryLayerIfExists(layer.children[childIndex]) != null) {
					layer.children[childIndex].removeLayer();
				}

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
		public void updateKeyForNode(int index, @Nonnull BPlusTreeNode<?> node) {
			Assert.isPremiseValid(
				index > 0,
				"Leftmost child node does not have a key in the parent node!"
			);

			final BPlusInternalTreeNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			if (layer == null) {
				Assert.isPremiseValid(
					this.children[index] == node,
					"Node to update key for must match the child node at the specified index!"
				);
				this.keys[index - 1] = leftBoundaryKeyOf(node);
			} else {
				decoupleTransactionalArrays();
				Assert.isPremiseValid(
					layer.children[index] == node,
					"Node to update key for must match the child node at the specified index!"
				);
				layer.keys[index - 1] = leftBoundaryKeyOf(node);
			}
		}

		@Override
		public BPlusInternalTreeNode createLayer() {
			return new BPlusInternalTreeNode(
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
		public BPlusInternalTreeNode createCopyWithMergedTransactionalMemory(
			@Nullable BPlusInternalTreeNode layer,
			@Nonnull TransactionalLayerMaintainer transactionalLayer
		) {
			final int[] theKeys;
			final BPlusTreeNode<?>[] theChildren;
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

			BPlusTreeNode<?>[] newChildren = null;
			for (int i = 0; i < thePeek + 1; i++) {
				final BPlusTreeNode<?> child = transactionalLayer.getStateCopyWithCommittedChanges(theChildren[i]);
				if (newChildren == null && child != theChildren[i]) {
					newChildren = new BPlusTreeNode[theChildren.length];
					System.arraycopy(theChildren, 0, newChildren, 0, i);
				}
				if (newChildren != null) {
					newChildren[i] = child;
				}
			}

			if (newChildren != null) {
				return new BPlusInternalTreeNode(
					theKeys,
					newChildren,
					thePeek,
					true
				);
			} else if (layer != null) {
				return new BPlusInternalTreeNode(
					theKeys,
					theChildren,
					thePeek,
					true
				);
			} else if (!this.transactionalLayer) {
				// nodes created during splits/merges are built with transactionalLayer=false so they do
				// not allocate STM layers mid-transaction; on commit they must be rebuilt as participating
				// (transactionalLayer=true) nodes so subsequent transactions can layer changes over them
				return new BPlusInternalTreeNode(
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
			final StringBuilder sb = new StringBuilder(64);
			toVerboseString(sb, 0, 3);
			return sb.toString();
		}

		/**
		 * Internal arrays may have been still identical to the original arrays we need to copy them in
		 * the transactional layer before modifying.
		 */
		private void decoupleTransactionalArrays() {
			final BPlusInternalTreeNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			if (layer != null) {
				//noinspection ArrayEquality
				if (layer.keys == this.keys) {
					layer.keys = new int[this.keys.length];
					System.arraycopy(this.keys, 0, layer.keys, 0, this.peek);
				}
				//noinspection ArrayEquality
				if (layer.children == this.children) {
					layer.children = new BPlusTreeNode[this.children.length];
					System.arraycopy(this.children, 0, layer.children, 0, this.peek + 1);
				}
			}
		}

	}

	/**
	 * Leaf node implementation of the B+ tree that stores key-value pairs. Leaf nodes hold all actual data
	 * in the tree and are the terminal nodes in the B+ tree structure. Values are stored as primitive `long`.
	 */
	static class BPlusLeafTreeNode implements BPlusTreeNode<BPlusLeafTreeNode>, IntKeyedNode {
		@Serial private static final long serialVersionUID = 5744347408875846162L;
		@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
		/**
		 * Indicates whether this instance is permitted to create and use transactional layers. The tree nodes use
		 * themselves (the same class) as their transactional memory layer, and if this layer were to also use
		 * transactional memory, it would create an infinite loop. This flag prevents that behavior.
		 */
		private final boolean transactionalLayer;
		/**
		 * The keys stored in this node.
		 */
		private int[] keys;
		/**
		 * The values stored in this node. Index i corresponds to the value associated with key i.
		 */
		private long[] values;
		/**
		 * Index of the last occupied position in the keys array.
		 */
		private int peek;

		/**
		 * Creates a new empty leaf node with the specified block size.
		 *
		 * @param blockSize          the maximum number of key-value pairs this leaf node can hold
		 * @param transactionalLayer whether this node participates in the transactional memory layer
		 */
		public BPlusLeafTreeNode(
			int blockSize,
			boolean transactionalLayer
		) {
			this.keys = new int[blockSize];
			this.values = new long[blockSize];
			this.peek = -1;
			this.transactionalLayer = transactionalLayer;
		}

		/**
		 * Creates a new leaf node by copying a range of keys and values from origin arrays into the target arrays.
		 * This constructor is used during node split operations.
		 *
		 * @param originKeys         the source array of keys to copy from
		 * @param originValues       the source array of values to copy from
		 * @param keys               the target array for keys (may be the same as originKeys)
		 * @param values             the target array for values (may be the same as originValues)
		 * @param start              the start index (inclusive) in the origin arrays
		 * @param end                the end index (exclusive) in the origin arrays
		 * @param transactionalLayer whether this node participates in the transactional memory layer
		 */
		public BPlusLeafTreeNode(
			@Nonnull int[] originKeys,
			@Nonnull long[] originValues,
			@Nonnull int[] keys,
			@Nonnull long[] values,
			int start, int end,
			boolean transactionalLayer
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
				Arrays.fill(values, end - start, values.length, 0L);
			}
			this.peek = end - start - 1;
			this.transactionalLayer = transactionalLayer;
		}

		private BPlusLeafTreeNode(
			@Nonnull int[] keys,
			@Nonnull long[] values,
			int peek,
			boolean transactionalLayer
		) {
			this.keys = keys;
			this.values = values;
			this.peek = peek;
			this.transactionalLayer = transactionalLayer;
		}

		@Nonnull
		public int[] getKeys() {
			final BPlusLeafTreeNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) :
				null;
			if (layer == null) {
				return this.keys;
			} else {
				return layer.keys;
			}
		}

		@Override
		public int getPeek() {
			final BPlusLeafTreeNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) :
				null;
			if (layer == null) {
				return this.peek;
			} else {
				return layer.peek;
			}
		}

		@Override
		public void setPeek(int peek) {
			final BPlusLeafTreeNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			if (layer == null) {
				final int originPeek = this.peek;
				this.peek = peek;
				if (peek < originPeek) {
					Arrays.fill(this.keys, peek + 1, originPeek + 1, 0);
					Arrays.fill(this.values, peek + 1, originPeek + 1, 0L);
				}
			} else {
				final int originPeek = layer.peek;
				layer.peek = peek;
				if (peek < originPeek) {
					// internal arrays may have been still identical to the original arrays
					// we need to copy them in the transactional layer, before modifying

					//noinspection ArrayEquality
					if (layer.keys == this.keys) {
						layer.keys = new int[this.keys.length];
						System.arraycopy(this.keys, 0, layer.keys, 0, originPeek + 1);
					} else {
						Arrays.fill(layer.keys, peek + 1, originPeek + 1, 0);
					}
					//noinspection ArrayEquality
					if (layer.values == this.values) {
						layer.values = new long[this.values.length];
						System.arraycopy(this.values, 0, layer.values, 0, originPeek + 1);
					} else {
						Arrays.fill(layer.values, peek + 1, originPeek + 1, 0L);
					}
				}
			}
		}

		@Override
		public int keyCount() {
			final BPlusLeafTreeNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) :
				null;
			if (layer == null) {
				return this.peek + 1;
			} else {
				return layer.peek + 1;
			}
		}

		@Override
		public boolean isFull() {
			final BPlusLeafTreeNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) :
				null;
			if (layer == null) {
				return this.peek == this.values.length - 1;
			} else {
				return layer.peek == layer.values.length - 1;
			}
		}

		@Override
		public void toVerboseString(@Nonnull StringBuilder sb, int level, int indentSpaces) {
			sb.append(" ".repeat(level * indentSpaces));
			final int[] theKeys;
			final long[] theValues;
			final int thePeek;

			final BPlusLeafTreeNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) :
				null;
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
		public void stealFromLeft(int numberOfTailValues, @Nonnull BPlusLeafTreeNode previousNode) {
			Assert.isPremiseValid(numberOfTailValues > 0, "Number of tail values to steal must be positive!");
			final BPlusLeafTreeNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			if (layer == null) {
				System.arraycopy(this.keys, 0, this.keys, numberOfTailValues, this.peek + 1);
				System.arraycopy(this.values, 0, this.values, numberOfTailValues, this.peek + 1);
				System.arraycopy(
					previousNode.getKeys(), previousNode.size() - numberOfTailValues, this.keys, 0, numberOfTailValues);
				System.arraycopy(
					previousNode.getValues(), previousNode.size() - numberOfTailValues, this.values, 0,
					numberOfTailValues
				);
				this.peek += numberOfTailValues;
				previousNode.setPeek(previousNode.getPeek() - numberOfTailValues);
			} else {
				// we need to decouple the arrays before modifying them
				decoupleTransactionalArrays();
				previousNode.decoupleTransactionalArrays();

				System.arraycopy(layer.keys, 0, layer.keys, numberOfTailValues, layer.peek + 1);
				System.arraycopy(layer.values, 0, layer.values, numberOfTailValues, layer.peek + 1);
				System.arraycopy(
					previousNode.getKeys(), previousNode.size() - numberOfTailValues, layer.keys, 0,
					numberOfTailValues
				);
				System.arraycopy(
					previousNode.getValues(), previousNode.size() - numberOfTailValues, layer.values, 0,
					numberOfTailValues
				);
				layer.peek += numberOfTailValues;
				previousNode.setPeek(previousNode.getPeek() - numberOfTailValues);
			}
		}

		@Override
		public void stealFromRight(int numberOfHeadValues, @Nonnull BPlusLeafTreeNode nextNode) {
			Assert.isPremiseValid(numberOfHeadValues > 0, "Number of head values to steal must be positive!");

			final BPlusLeafTreeNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			if (layer == null) {
				// the right sibling may be a committed (shared) node while `this` is a transaction-local node
				// (transactionalLayer == false): steal-from-right SHIFTS the sibling's arrays in place, so it must
				// decouple them first or it would corrupt the shared committed state. getKeysForUpdate /
				// getValuesForUpdate decouple a committed sibling inside a transaction and are in-place no-ops outside
				// one, so they are correct in both the warm-up and the transactional case.
				final int[] nextNodeKeys = nextNode.getKeysForUpdate();
				final long[] nextNodeValues = nextNode.getValuesForUpdate();
				System.arraycopy(nextNodeKeys, 0, this.keys, this.peek + 1, numberOfHeadValues);
				System.arraycopy(nextNodeValues, 0, this.values, this.peek + 1, numberOfHeadValues);
				System.arraycopy(
					nextNodeKeys, numberOfHeadValues, nextNodeKeys, 0,
					nextNode.size() - numberOfHeadValues
				);
				System.arraycopy(
					nextNodeValues, numberOfHeadValues, nextNodeValues, 0,
					nextNode.size() - numberOfHeadValues
				);
				nextNode.setPeek(nextNode.getPeek() - numberOfHeadValues);
				this.peek += numberOfHeadValues;
			} else {
				// we need to decouple the arrays before modifying them
				decoupleTransactionalArrays();
				nextNode.decoupleTransactionalArrays();

				System.arraycopy(nextNode.getKeysForUpdate(), 0, layer.keys, layer.peek + 1, numberOfHeadValues);
				System.arraycopy(nextNode.getValuesForUpdate(), 0, layer.values, layer.peek + 1, numberOfHeadValues);
				System.arraycopy(
					nextNode.getKeysForUpdate(), numberOfHeadValues, nextNode.getKeysForUpdate(), 0,
					nextNode.size() - numberOfHeadValues
				);
				System.arraycopy(
					nextNode.getValuesForUpdate(), numberOfHeadValues, nextNode.getValuesForUpdate(), 0,
					nextNode.size() - numberOfHeadValues
				);
				nextNode.setPeek(nextNode.getPeek() - numberOfHeadValues);
				layer.peek += numberOfHeadValues;
			}
		}

		@Override
		public void mergeWithLeft(@Nonnull BPlusLeafTreeNode previousNode) {
			final int mergePeek = previousNode.getPeek();
			final BPlusLeafTreeNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
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
		public void mergeWithRight(@Nonnull BPlusLeafTreeNode nextNode) {
			final int mergePeek = nextNode.getPeek();
			final BPlusLeafTreeNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
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

		public int getLeftBoundaryKey() {
			final BPlusLeafTreeNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) :
				null;
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
		public int[] getKeysForUpdate() {
			final BPlusLeafTreeNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			if (layer == null) {
				return this.keys;
			} else {
				// internal arrays may have been still identical to the original arrays
				// we need to copy them in the transactional layer, before modifying

				//noinspection ArrayEquality
				if (layer.keys == this.keys) {
					layer.keys = new int[this.keys.length];
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
		public long[] getValues() {
			final BPlusLeafTreeNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) :
				null;
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
		public long[] getValuesForUpdate() {
			final BPlusLeafTreeNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			if (layer == null) {
				return this.values;
			} else {
				// internal arrays may have been still identical to the original arrays
				// we need to copy them in the transactional layer, before modifying

				//noinspection ArrayEquality
				if (layer.values == this.values) {
					layer.values = new long[this.values.length];
					System.arraycopy(this.values, 0, layer.values, 0, this.values.length);
				}
				return layer.values;
			}
		}

		/**
		 * Searches for a value in the node's key-value pairs by the specified key.
		 * If the key is found, returns an OptionalLong containing the associated value;
		 * otherwise returns an empty OptionalLong.
		 *
		 * @param key the key to search for in the leaf node
		 * @return an OptionalLong containing the value associated with the specified key if found;
		 * otherwise, an empty OptionalLong
		 */
		@Nonnull
		public OptionalLong getValue(int key) {
			final int[] theKeys;
			final long[] theValues;
			final int thePeek;

			final BPlusLeafTreeNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) :
				null;
			if (layer == null) {
				theKeys = this.keys;
				theValues = this.values;
				thePeek = this.peek;
			} else {
				theKeys = layer.keys;
				theValues = layer.values;
				thePeek = layer.peek;
			}

			final InsertionPosition insertionPosition = computeInsertPositionOfIntInOrderedArray(
				key, theKeys, 0, thePeek + 1);
			return insertionPosition.alreadyPresent() ?
				OptionalLong.of(theValues[insertionPosition.position()]) : OptionalLong.empty();
		}

		/**
		 * Searches for the index of a value in the node's key-value pairs by the specified key.
		 * Returns the index of the key if found, or -1 if the key is not present.
		 *
		 * @param key the key to search for in the leaf node
		 * @return the index of the key in the keys/values arrays if found; -1 otherwise
		 */
		public int getValueIndex(int key) {
			final int[] theKeys;
			final int thePeek;

			final BPlusLeafTreeNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) :
				null;
			if (layer == null) {
				theKeys = this.keys;
				thePeek = this.peek;
			} else {
				theKeys = layer.keys;
				thePeek = layer.peek;
			}

			final InsertionPosition insertionPosition = computeInsertPositionOfIntInOrderedArray(
				key, theKeys, 0, thePeek + 1);
			return insertionPosition.alreadyPresent() ? insertionPosition.position() : -1;
		}

		@Override
		public String toString() {
			final StringBuilder sb = new StringBuilder(64);
			toVerboseString(sb, 0, 3);
			return sb.toString();
		}

		@Override
		public BPlusLeafTreeNode createLayer() {
			return new BPlusLeafTreeNode(
				this.keys,
				this.values,
				this.keys,
				this.values,
				0,
				this.peek + 1,
				false
			);
		}

		@Override
		public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			transactionalLayer.removeTransactionalMemoryLayer(this);
		}

		@Nonnull
		@Override
		public BPlusLeafTreeNode createCopyWithMergedTransactionalMemory(
			@Nullable BPlusLeafTreeNode layer,
			@Nonnull TransactionalLayerMaintainer transactionalLayer
		) {
			final int[] theKeys;
			final long[] theValues;
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

			// primitive long values never carry their own transactional layer, so there is nothing to merge
			if (layer != null) {
				return new BPlusLeafTreeNode(
					theKeys,
					theValues,
					thePeek,
					true
				);
			} else if (!this.transactionalLayer) {
				// nodes created during splits/merges are built with transactionalLayer=false so they do
				// not allocate STM layers mid-transaction; on commit they must be rebuilt as participating
				// (transactionalLayer=true) nodes so subsequent transactions can layer changes over them
				return new BPlusLeafTreeNode(
					theKeys,
					theValues,
					thePeek,
					true
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
		 */
		public boolean delete(int key) {
			final BPlusLeafTreeNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			if (layer == null) {
				final int index = Arrays.binarySearch(this.keys, 0, this.peek + 1, key);

				if (index >= 0) {
					removeIntFromSameArrayOnIndex(this.keys, index);
					removeLongFromSameArrayOnIndex(this.values, index);
					this.keys[this.peek] = 0;
					this.values[this.peek] = 0L;
					this.peek--;
					return true;
				} else {
					return false;
				}
			} else {
				decoupleTransactionalArrays();
				final int index = Arrays.binarySearch(layer.keys, 0, layer.peek + 1, key);

				if (index >= 0) {
					removeIntFromSameArrayOnIndex(layer.keys, index);
					removeLongFromSameArrayOnIndex(layer.values, index);
					layer.keys[layer.peek] = 0;
					layer.values[layer.peek] = 0L;
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
		 * @param value the value associated with the key
		 * @return true if new key was inserted, otherwise false
		 */
		private boolean insert(int key, long value) {
			final BPlusLeafTreeNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			if (layer == null) {
				Assert.isPremiseValid(
					this.peek < this.keys.length - 1,
					"Cannot insert into a full leaf node, split the node first!"
				);

				final InsertionPosition insertionPosition = computeInsertPositionOfIntInOrderedArray(
					key, this.keys, 0, this.peek + 1);
				if (insertionPosition.alreadyPresent()) {
					this.keys[insertionPosition.position()] = key;
					this.values[insertionPosition.position()] = value;
					return false;
				} else {
					insertIntIntoSameArrayOnIndex(key, this.keys, insertionPosition.position());
					insertLongIntoSameArrayOnIndex(value, this.values, insertionPosition.position());
					this.peek++;
					return true;
				}
			} else {
				decoupleTransactionalArrays();
				Assert.isPremiseValid(
					layer.peek < layer.keys.length - 1,
					"Cannot insert into a full leaf node, split the node first!"
				);

				final InsertionPosition insertionPosition = computeInsertPositionOfIntInOrderedArray(
					key, layer.keys, 0, layer.peek + 1);
				if (insertionPosition.alreadyPresent()) {
					layer.keys[insertionPosition.position()] = key;
					layer.values[insertionPosition.position()] = value;
					return false;
				} else {
					insertIntIntoSameArrayOnIndex(key, layer.keys, insertionPosition.position());
					insertLongIntoSameArrayOnIndex(value, layer.values, insertionPosition.position());
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
			final BPlusLeafTreeNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			if (layer != null) {
				//noinspection ArrayEquality
				if (layer.keys == this.keys) {
					layer.keys = new int[this.keys.length];
					System.arraycopy(this.keys, 0, layer.keys, 0, this.peek + 1);
				}
				//noinspection ArrayEquality
				if (layer.values == this.values) {
					layer.values = new long[this.values.length];
					System.arraycopy(this.values, 0, layer.values, 0, this.peek + 1);
				}
			}
		}

	}

	/**
	 * Int-keyed forward iterator base: layers the typed leaf-array cache on top of the shared key-agnostic
	 * {@link AbstractForwardTreeNavigator}. The concrete key / value / entry iterators read the current element
	 * straight from these cached arrays - the primitive key and value iterators return the primitive directly to avoid
	 * boxing, while the entry iterator indexes the cached key and value arrays.
	 */
	private abstract static class AbstractForwardTreeIterator extends AbstractForwardTreeNavigator {
		/**
		 * The current leaf's key and value arrays, refreshed once per leaf by {@link #loadCurrentLeaf()}. Visible to
		 * subclasses so the key / value / entry iterators index them directly without a per-element accessor call.
		 */
		protected int[] leafKeys;
		protected long[] leafValues;

		/**
		 * Creates a forward iterator starting from the leftmost position of the cursor.
		 *
		 * @param cursor the cursor providing the traversal path through the B+ tree
		 */
		protected AbstractForwardTreeIterator(@Nonnull Cursor cursor) {
			super(cursor);
		}

		/**
		 * Creates a forward iterator starting from the specified key or the first key greater than it.
		 *
		 * @param cursor the cursor providing the traversal path through the B+ tree
		 * @param key    the key to start the iteration from
		 */
		protected AbstractForwardTreeIterator(@Nonnull Cursor cursor, int key) {
			super(
				cursor,
				computeInsertPositionOfIntInOrderedArray(
					key, ((IntKeyedNode) cursor.leafNode()).getKeys(), 0, cursor.leafNode().size()
				)
			);
		}

		@Override
		protected void loadCurrentLeaf() {
			final BPlusLeafTreeNode leaf = (BPlusLeafTreeNode) currentLeafNode();
			this.leafKeys = leaf.getKeys();
			this.leafValues = leaf.getValues();
			this.leafPeek = leaf.getPeek();
		}
	}

	/**
	 * Int-keyed reverse iterator base: layers the typed leaf-array cache on top of the shared key-agnostic
	 * {@link AbstractReverseTreeNavigator}. The concrete key / value / entry iterators read the current element
	 * straight from these cached arrays.
	 */
	private abstract static class AbstractReverseTreeIterator extends AbstractReverseTreeNavigator {
		/**
		 * The current leaf's key and value arrays, refreshed once per leaf by {@link #loadCurrentLeaf()}. Visible to
		 * subclasses so the key / value / entry iterators index them directly without a per-element accessor call.
		 */
		protected int[] leafKeys;
		protected long[] leafValues;

		/**
		 * Creates a reverse iterator starting from the rightmost position of the cursor.
		 *
		 * @param cursor the cursor providing the traversal path through the B+ tree
		 */
		protected AbstractReverseTreeIterator(@Nonnull Cursor cursor) {
			super(cursor);
		}

		/**
		 * Creates a reverse iterator starting from the specified key or the first key lesser than or equal to it.
		 *
		 * @param cursor the cursor providing the traversal path through the B+ tree
		 * @param key    the key to start the iteration from
		 */
		protected AbstractReverseTreeIterator(@Nonnull Cursor cursor, int key) {
			super(
				cursor,
				computeInsertPositionOfIntInOrderedArray(
					key, ((IntKeyedNode) cursor.leafNode()).getKeys(), 0, cursor.leafNode().size()
				)
			);
		}

		@Override
		protected void loadCurrentLeaf() {
			final BPlusLeafTreeNode leaf = (BPlusLeafTreeNode) currentLeafNode();
			this.leafKeys = leaf.getKeys();
			this.leafValues = leaf.getValues();
			this.leafPeek = leaf.getPeek();
		}
	}

	/**
	 * Iterator that traverses the B+ Tree keys from left to right. The primitive key is returned directly
	 * from {@link #nextInt()} so that no boxing occurs on the iteration path.
	 */
	private static class ForwardTreeKeyIterator extends AbstractForwardTreeIterator implements OfInt {

		/**
		 * Creates a forward key iterator starting from the leftmost key.
		 *
		 * @param cursor the cursor providing the traversal path through the B+ tree
		 */
		public ForwardTreeKeyIterator(@Nonnull Cursor cursor) {
			super(cursor);
		}

		/**
		 * Creates a forward key iterator starting from the specified key or the first key greater than it.
		 *
		 * @param cursor the cursor providing the traversal path through the B+ tree
		 * @param key    the key to start the iteration from
		 */
		public ForwardTreeKeyIterator(@Nonnull Cursor cursor, int key) {
			super(cursor, key);
		}

		@Override
		public int nextInt() {
			if (!this.hasNextElement) {
				throw new NoSuchElementException("No more elements available");
			}
			// read straight from the cached leaf key array - no per-element ThreadLocal accessor call
			final int key = this.leafKeys[this.currentIndex];
			advance();
			return key;
		}
	}

	/**
	 * Iterator that traverses the B+ Tree keys from right to left. The primitive key is returned directly
	 * from {@link #nextInt()} so that no boxing occurs on the iteration path.
	 */
	private static class ReverseTreeKeyIterator extends AbstractReverseTreeIterator implements OfInt {

		/**
		 * Creates a reverse key iterator starting from the rightmost key.
		 *
		 * @param cursor the cursor providing the traversal path through the B+ tree
		 */
		public ReverseTreeKeyIterator(@Nonnull Cursor cursor) {
			super(cursor);
		}

		/**
		 * Creates a reverse key iterator starting from the specified key or the first key lesser than or equal to it.
		 *
		 * @param cursor the cursor providing the traversal path through the B+ tree
		 * @param key    the key to start the iteration from
		 */
		public ReverseTreeKeyIterator(@Nonnull Cursor cursor, int key) {
			super(cursor, key);
		}

		@Override
		public int nextInt() {
			if (!this.hasNextElement) {
				throw new NoSuchElementException("No more elements available");
			}
			// read straight from the cached leaf key array - no per-element ThreadLocal accessor call
			final int key = this.leafKeys[this.currentIndex];
			advance();
			return key;
		}
	}

	/**
	 * Iterator that traverses the B+ Tree values from left to right. The primitive value is returned directly
	 * from {@link #nextLong()} so that no boxing occurs on the iteration path.
	 */
	static class ForwardTreeValueIterator extends AbstractForwardTreeIterator implements OfLong {

		/**
		 * Creates a forward value iterator starting from the leftmost value.
		 *
		 * @param cursor the cursor providing the traversal path through the B+ tree
		 */
		public ForwardTreeValueIterator(@Nonnull Cursor cursor) {
			super(cursor);
		}

		/**
		 * Creates a forward value iterator starting from the specified key or the first key greater than it.
		 *
		 * @param cursor the cursor providing the traversal path through the B+ tree
		 * @param key    the key to start the iteration from
		 */
		public ForwardTreeValueIterator(@Nonnull Cursor cursor, int key) {
			super(cursor, key);
		}

		@Override
		public long nextLong() {
			if (!this.hasNextElement) {
				throw new NoSuchElementException("No more elements available");
			}
			// read straight from the cached leaf value array - no per-element ThreadLocal accessor call
			final long value = this.leafValues[this.currentIndex];
			advance();
			return value;
		}
	}

	/**
	 * Iterator that traverses the B+ Tree values from right to left. The primitive value is returned directly
	 * from {@link #nextLong()} so that no boxing occurs on the iteration path.
	 */
	static class ReverseTreeValueIterator extends AbstractReverseTreeIterator implements OfLong {

		/**
		 * Creates a reverse value iterator starting from the rightmost value.
		 *
		 * @param cursor the cursor providing the traversal path through the B+ tree
		 */
		public ReverseTreeValueIterator(@Nonnull Cursor cursor) {
			super(cursor);
		}

		/**
		 * Creates a reverse value iterator starting from the specified key or the first key lesser than or equal to it.
		 *
		 * @param cursor the cursor providing the traversal path through the B+ tree
		 * @param key    the key to start the iteration from
		 */
		public ReverseTreeValueIterator(@Nonnull Cursor cursor, int key) {
			super(cursor, key);
		}

		@Override
		public long nextLong() {
			if (!this.hasNextElement) {
				throw new NoSuchElementException("No more elements available");
			}
			// read straight from the cached leaf value array - no per-element ThreadLocal accessor call
			final long value = this.leafValues[this.currentIndex];
			advance();
			return value;
		}
	}

	/**
	 * Iterator that traverses the B+ Tree from left to right and provides access to entries (both keys and values).
	 */
	static class ForwardTreeEntryIterator extends AbstractForwardTreeIterator implements Iterator<Entry> {

		/**
		 * Creates a forward entry iterator starting from the leftmost entry.
		 *
		 * @param cursor the cursor providing the traversal path through the B+ tree
		 */
		public ForwardTreeEntryIterator(@Nonnull Cursor cursor) {
			super(cursor);
		}

		/**
		 * Creates a forward entry iterator starting from the specified key or the first key greater than it.
		 *
		 * @param cursor the cursor providing the traversal path through the B+ tree
		 * @param key    the key to start the iteration from
		 */
		public ForwardTreeEntryIterator(@Nonnull Cursor cursor, int key) {
			super(cursor, key);
		}

		@Override
		public Entry next() {
			if (!this.hasNextElement) {
				throw new NoSuchElementException("No more elements available");
			}
			// read straight from the cached leaf key/value arrays - no per-element ThreadLocal accessor call
			final Entry entry = new Entry(this.leafKeys[this.currentIndex], this.leafValues[this.currentIndex]);
			advance();
			return entry;
		}
	}

	/**
	 * Iterator that traverses the B+ Tree from right to left and provides access to entries (both keys and values).
	 */
	static class ReverseTreeEntryIterator extends AbstractReverseTreeIterator implements Iterator<Entry> {

		/**
		 * Creates a reverse entry iterator starting from the rightmost entry.
		 *
		 * @param cursor the cursor providing the traversal path through the B+ tree
		 */
		public ReverseTreeEntryIterator(@Nonnull Cursor cursor) {
			super(cursor);
		}

		/**
		 * Creates a reverse entry iterator starting from the specified key or the first key lesser than or equal to it.
		 *
		 * @param cursor the cursor providing the traversal path through the B+ tree
		 * @param key    the key to start the iteration from
		 */
		public ReverseTreeEntryIterator(@Nonnull Cursor cursor, int key) {
			super(cursor, key);
		}

		@Override
		public Entry next() {
			if (!this.hasNextElement) {
				throw new NoSuchElementException("No more elements available");
			}
			// read straight from the cached leaf key/value arrays - no per-element ThreadLocal accessor call
			final Entry entry = new Entry(this.leafKeys[this.currentIndex], this.leafValues[this.currentIndex]);
			advance();
			return entry;
		}
	}

	/**
	 * Entry is an immutable data structure that stores a primitive `int` key together with its primitive `long` value.
	 *
	 * @param key   the primitive key of the entry
	 * @param value the primitive value associated with the key
	 */
	public record Entry(
		int key,
		long value
	) {
	}

}
