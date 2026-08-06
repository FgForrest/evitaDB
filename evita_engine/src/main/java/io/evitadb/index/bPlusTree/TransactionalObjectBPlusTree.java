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
import io.evitadb.core.transaction.memory.Snapshotable;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalStateProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.dataType.ConsistencySensitiveDataStructure;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.function.IntObjTriFunction;
import io.evitadb.utils.Assert;
import io.evitadb.utils.VMLayout;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.function.UnaryOperator;

import static io.evitadb.utils.ArrayUtils.InsertionPosition;
import static io.evitadb.utils.ArrayUtils.computeInsertPositionOfObjInOrderedArray;
import static io.evitadb.utils.ArrayUtils.insertRecordIntoSameArrayOnIndex;
import static io.evitadb.utils.ArrayUtils.removeRecordFromSameArrayOnIndex;

/**
 * Represents a B+ Tree data structure specifically designed for generic comparable keys and generic values.
 * The tree is balanced and allows for efficient insertion, deletion, and search operations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@NotThreadSafe
public class TransactionalObjectBPlusTree<K extends Comparable<K>, V> extends AbstractTransactionalBPlusTree implements
	TransactionalLayerProducer<Void, TransactionalObjectBPlusTree<K, V>>,
	Serializable,
	ConsistencySensitiveDataStructure {
	@Serial private static final long serialVersionUID = -6130739073840635252L;
	private static final int DEFAULT_VALUE_BLOCK_SIZE = 64;
	private static final int DEFAULT_MIN_VALUE_BLOCK_SIZE = DEFAULT_VALUE_BLOCK_SIZE / 2 - 1;
	private static final int DEFAULT_INTERNAL_NODE_BLOCK_SIZE = DEFAULT_VALUE_BLOCK_SIZE / 2 - 1;
	private static final int DEFAULT_MIN_INTERNAL_NODE_BLOCK_SIZE = (int) (Math.ceil(
		(float) DEFAULT_INTERNAL_NODE_BLOCK_SIZE / 2.0) - 1);
	/**
	 * The type of the keys stored in the tree.
	 */
	@Getter private final Class<K> keyType;
	/**
	 * The type of the values stored in the tree.
	 */
	@Getter private final Class<V> valueType;
	/**
	 * Optional comparator that defines the total order of the keys. When `null`, the keys are ordered by their
	 * natural [Comparable] order. The comparator (when present) is threaded into every node and drives every
	 * key-comparison site so the tree can be ordered by an arbitrary total order (e.g. a locale-aware collator).
	 */
	@Nullable @Getter private final Comparator<K> comparator;
	/**
	 * Operator that wraps the values in a transactional layer.
	 */
	private final Function<Object, V> transactionalLayerWrapper;

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
	 * Returns the left boundary key of an arbitrary node reached through the key-agnostic {@link BPlusTreeNode} SPI
	 * (e.g. an element of an internal node's children array). The comparable-key accessor lives on the per-tree
	 * {@link ObjectKeyedNode} marker so it stays out of the shared SPI (which must never expose a typed key); every
	 * node in this tree implements it, so the cast is always safe.
	 *
	 * @param node the node whose left boundary key is requested
	 * @param <M>  the comparable key type
	 * @return the left boundary (smallest) key of the node
	 */
	@Nonnull
	private static <M extends Comparable<M>> M leftBoundaryKeyOf(@Nonnull BPlusTreeNode<?> node) {
		//noinspection unchecked
		return ((ObjectKeyedNode<M>) node).getLeftBoundaryKey();
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
		if (node instanceof BPlusInternalTreeNode<?> internalNode) {
			final Object[] keys = internalNode.getKeys();
			final BPlusTreeNode<?>[] children = internalNode.getChildren();
			if (internalNode.getPeek() >= 0) {
				verifyInternalNodeKeys(children[0]);
			}
			for (int i = 0; i < internalNode.getPeek(); i++) {
				final Object key = keys[i];
				final BPlusTreeNode<?> child = children[i + 1];
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
	 * Verifies the integrity of the forward key iterator for a given {@link TransactionalObjectBPlusTree}.
	 * Checks if the keys from the iterator are returned in strictly increasing order and
	 * validates the total number of keys returned matches the expected size.
	 *
	 * @param tree the {@link TransactionalObjectBPlusTree} whose key iterator is to be verified
	 * @param size the expected number of keys in the {@link TransactionalObjectBPlusTree}
	 * @throws IllegalStateException if the iterator fails to return keys in increasing order
	 *                               or if the number of keys does not match the expected size
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void verifyForwardKeyIterator(@Nonnull TransactionalObjectBPlusTree<?, ?> tree, int size) {
		int actualSize = 0;
		Comparable previousKey = null;
		final Comparator comparator = tree.comparator;
		final Iterator<?> it = tree.keyIterator();
		while (it.hasNext()) {
			final Comparable key = (Comparable) it.next();
			// route the order check through the tree's comparator when present, otherwise natural order
			final int comparison = comparator == null
				? (previousKey == null ? 0 : key.compareTo(previousKey))
				: (previousKey == null ? 0 : comparator.compare(key, previousKey));
			if (previousKey != null && comparison <= 0) {
				throw new IllegalStateException("Forward iterator returned non-increasing keys!");
			}
			actualSize++;
			previousKey = key;
		}

		if (actualSize != size) {
			throw new IllegalStateException(
				"Forward iterator returned " + actualSize + " keys, but the tree has " + size + " elements!");
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
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void verifyReverseKeyIterator(@Nonnull TransactionalObjectBPlusTree<?, ?> tree, int size) {
		int actualSize = 0;
		Comparable previousKey = null;
		final Comparator comparator = tree.comparator;
		final Iterator<?> it = tree.keyReverseIterator();
		while (it.hasNext()) {
			final Comparable key = (Comparable) it.next();
			// route the order check through the tree's comparator when present, otherwise natural order
			final int comparison = comparator == null
				? (previousKey == null ? 0 : key.compareTo(previousKey))
				: (previousKey == null ? 0 : comparator.compare(key, previousKey));
			if (previousKey != null && comparison >= 0) {
				throw new IllegalStateException("Reverse iterator returned non-decreasing keys!");
			}
			actualSize++;
			previousKey = key;
		}

		if (actualSize != size) {
			throw new IllegalStateException(
				"Reverse iterator returned " + actualSize + " keys, but the tree has " + size + " elements!");
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
		@Nonnull List<CursorLevel> path
	) {
		final int childIndex = currentNode.searchIndex(key);
		final BPlusTreeNode<?>[] children = currentNode.getChildren();
		path.add(new CursorLevel(children, childIndex, currentNode.getPeek()));
		// if the child is an internal node, continue traversing down the tree
		if (children[childIndex] instanceof BPlusInternalTreeNode<?> childInternalNode) {
			//noinspection unchecked
			addCursorLevels((BPlusInternalTreeNode<M>) childInternalNode, key, path);
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
			null,
			new BPlusLeafTreeNode<>(DEFAULT_VALUE_BLOCK_SIZE, keyType, valueType, null, null, true),
			0
		);
	}

	/**
	 * Constructor to initialize the B+ Tree with default block sizes.
	 *
	 * @param keyType                   the type of the keys stored in the tree
	 * @param valueType                 the type of the values stored in the tree
	 * @param transactionalLayerWrapper operator that wraps the values in a transactional layer
	 */
	public TransactionalObjectBPlusTree(
		@Nonnull Class<K> keyType,
		@Nonnull Class<V> valueType,
		@Nonnull Function<Object, V> transactionalLayerWrapper
	) {
		this(keyType, valueType, transactionalLayerWrapper, null);
	}

	/**
	 * Constructor to initialize the B+ Tree with default block sizes, a value wrapper and an optional comparator.
	 *
	 * @param keyType                   the type of the keys stored in the tree
	 * @param valueType                 the type of the values stored in the tree
	 * @param transactionalLayerWrapper operator that wraps the values in a transactional layer
	 * @param comparator                optional comparator defining the key order; `null` ⇒ natural order
	 */
	public TransactionalObjectBPlusTree(
		@Nonnull Class<K> keyType,
		@Nonnull Class<V> valueType,
		@Nonnull Function<Object, V> transactionalLayerWrapper,
		@Nullable Comparator<K> comparator
	) {
		this(
			DEFAULT_VALUE_BLOCK_SIZE,
			DEFAULT_MIN_VALUE_BLOCK_SIZE,
			DEFAULT_INTERNAL_NODE_BLOCK_SIZE,
			DEFAULT_MIN_INTERNAL_NODE_BLOCK_SIZE,
			keyType,
			valueType,
			comparator,
			transactionalLayerWrapper,
			new BPlusLeafTreeNode<>(DEFAULT_VALUE_BLOCK_SIZE, keyType, valueType, comparator, transactionalLayerWrapper, true),
			0
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
		this(valueBlockSize, keyType, valueType, null);
	}

	/**
	 * Constructor to initialize the B+ Tree with an optional comparator.
	 *
	 * @param valueBlockSize maximum number of values in a leaf node
	 * @param keyType        the type of the keys stored in the tree
	 * @param valueType      the type of the values stored in the tree
	 * @param comparator     optional comparator defining the key order; `null` ⇒ natural order
	 */
	public TransactionalObjectBPlusTree(
		int valueBlockSize,
		@Nonnull Class<K> keyType,
		@Nonnull Class<V> valueType,
		@Nullable Comparator<K> comparator
	) {
		this(
			valueBlockSize, valueBlockSize / 2,
			valueBlockSize, valueBlockSize / 2,
			keyType,
			valueType,
			comparator
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
			null
		);
	}

	/**
	 * Constructor to initialize the B+ Tree with an optional comparator.
	 *
	 * @param valueBlockSize           maximum number of values in a leaf node
	 * @param minValueBlockSize        minimum number of values in a leaf node
	 *                                 (controls branching factor for leaf nodes)
	 * @param internalNodeBlockSize    maximum number of keys in an internal node
	 * @param minInternalNodeBlockSize minimum number of keys in an internal node
	 *                                 (controls branching factor for internal nodes)
	 * @param keyType                  the type of the keys stored in the tree
	 * @param valueType                the type of the values stored in the tree
	 * @param comparator               optional comparator defining the key order; `null` ⇒ natural order
	 */
	public TransactionalObjectBPlusTree(
		int valueBlockSize,
		int minValueBlockSize,
		int internalNodeBlockSize,
		int minInternalNodeBlockSize,
		@Nonnull Class<K> keyType,
		@Nonnull Class<V> valueType,
		@Nullable Comparator<K> comparator
	) {
		this(
			valueBlockSize,
			minValueBlockSize,
			internalNodeBlockSize,
			minInternalNodeBlockSize,
			keyType,
			valueType,
			comparator,
			null,
			new BPlusLeafTreeNode<>(valueBlockSize, keyType, valueType, comparator, null, true),
			0
		);
	}

	/**
	 * Constructor to initialize the B+ Tree with explicit block sizes, a value wrapper and an optional comparator - the
	 * wrapper-aware counterpart of
	 * {@link #TransactionalObjectBPlusTree(int, int, int, int, Class, Class, Comparator)}, required when the value type
	 * implements {@link TransactionalLayerProducer} (e.g. `ValueToRecordBitmap`) and therefore must be wrapped on
	 * commit. Lets consumers tune the leaf block size for their workload (mirrors `SortIndex.VALUE_BLOCK_SIZE`).
	 *
	 * @param valueBlockSize            maximum number of values in a leaf node
	 * @param minValueBlockSize         minimum number of values in a leaf node
	 * @param internalNodeBlockSize     maximum number of keys in an internal node
	 * @param minInternalNodeBlockSize  minimum number of keys in an internal node
	 * @param keyType                   the type of the keys stored in the tree
	 * @param valueType                 the type of the values stored in the tree
	 * @param transactionalLayerWrapper operator that wraps the values in a transactional layer
	 * @param comparator                optional comparator defining the key order; `null` ⇒ natural order
	 */
	public TransactionalObjectBPlusTree(
		int valueBlockSize,
		int minValueBlockSize,
		int internalNodeBlockSize,
		int minInternalNodeBlockSize,
		@Nonnull Class<K> keyType,
		@Nonnull Class<V> valueType,
		@Nonnull Function<Object, V> transactionalLayerWrapper,
		@Nullable Comparator<K> comparator
	) {
		this(
			valueBlockSize,
			minValueBlockSize,
			internalNodeBlockSize,
			minInternalNodeBlockSize,
			keyType,
			valueType,
			comparator,
			transactionalLayerWrapper,
			new BPlusLeafTreeNode<>(valueBlockSize, keyType, valueType, comparator, transactionalLayerWrapper, true),
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
		@Nullable Comparator<K> comparator,
		@Nullable Function<Object, V> transactionalLayerWrapper,
		@Nonnull BPlusTreeNode<?> root,
		int size
	) {
		super(valueBlockSize, minValueBlockSize, internalNodeBlockSize, minInternalNodeBlockSize, root, size);
		Assert.isPremiseValid(
			!TransactionalStateProducer.class.isAssignableFrom(keyType),
			"Key type cannot implement TransactionalStateProducer."
		);
		Assert.isPremiseValid(
			transactionalLayerWrapper != null || !TransactionalStateProducer.class.isAssignableFrom(valueType),
			"Value type cannot implement TransactionalStateProducer if no transactional layer wrapper is provided."
		);
		Assert.isPremiseValid(
			comparator != null || Comparable.class.isAssignableFrom(keyType),
			"Key type must implement Comparable when no comparator is provided."
		);
		this.comparator = comparator;
		this.keyType = keyType;
		this.valueType = valueType;
		this.transactionalLayerWrapper = transactionalLayerWrapper;
	}

	@Nonnull
	@Override
	protected BPlusTreeNode<?> newEmptyLeaf() {
		return new BPlusLeafTreeNode<>(
			this.valueBlockSize, this.keyType, this.valueType, this.comparator, this.transactionalLayerWrapper, true
		);
	}

	/**
	 * Inserts a key-value pair into the B+ tree. If the corresponding leaf node
	 * overflows, it is split to maintain the properties of the tree.
	 *
	 * @param key   the key to be inserted into the B+ tree
	 * @param value the value associated with the key, must not be null
	 */
	public void insert(@Nonnull K key, @Nonnull V value) {
		// the cursor path exists ONLY to cascade a split upward, yet it used to be allocated on every insert.
		// `findLeafNode` reaches the same leaf by the identical `searchIndex` descent without capturing anything, so
		// the path is now built only when this insert can actually overflow the leaf. This tree carries no boundary
		// asserts and no dirty-scope registration, so nothing else consumes the path.
		final BPlusLeafTreeNode<K, V> leaf = findLeafNode(key);
		// captured BEFORE mutating: the split machinery replaces this leaf in its parent, so the path has to reflect
		// the pre-mutation tree
		final Cursor cursor = leaf.isNearlyFull() ? createCursor(key) : null;
		if (leaf.insert(key, value)) {
			this.size.set(size() + 1);
		}

		// Split the leaf node if it exceeds the block size
		if (leaf.isFull()) {
			if (cursor == null) {
				throw missingSplitPathError(leaf);
			}
			splitLeafNode(leaf, cursor);
		}
	}

	/**
	 * Builds the error raised when a leaf turns out to be {@link BPlusLeafTreeNode#isFull()} although the
	 * {@link BPlusLeafTreeNode#isNearlyFull()} guard decided no cursor path was needed — an unreachable state that
	 * would otherwise surface as a bare `NullPointerException` inside the split.
	 *
	 * @param leaf the leaf whose split has no captured path
	 * @return the error describing the broken invariant
	 */
	@Nonnull
	private GenericEvitaInternalError missingSplitPathError(@Nonnull BPlusLeafTreeNode<K, V> leaf) {
		return new GenericEvitaInternalError(
			"Leaf is full but no cursor path was captured - `isNearlyFull` failed to predict `isFull` " +
				"(peek: " + leaf.getPeek() + ", capacity: " + leaf.getValues().length +
				", tree block size: " + this.valueBlockSize + ")!",
			"Leaf is full but no cursor path was captured!"
		);
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
		// see insert(K, V) — the update branch below replaces a value in place and can never overflow the leaf, so the
		// guard sits inside the key-absent branch and is exact there
		final BPlusLeafTreeNode<K, V> leaf = findLeafNode(key);

		final int existingIndex = leaf.getValueIndex(key);
		if (existingIndex >= 0) {
			// update the value on specified index
			leaf.decoupleTransactionalArrays();
			final V[] values = leaf.getValues();
			final V previousValue = values[existingIndex];
			final V newValue = updater.apply(previousValue);
			// when the updater returns a different instance the previous one is discarded from the tree;
			// release its transactional diff layer (if any) so it is not left ALIVE and detected as stale
			// during commit; when the updater mutates and returns the same instance, nothing is discarded
			if (newValue != previousValue) {
				BPlusLeafTreeNode.discardRemovedValueLayer(previousValue);
			}
			values[existingIndex] = newValue;
		} else {
			final Cursor cursor = leaf.isNearlyFull() ? createCursor(key) : null;
			// insert the new value
			if (leaf.insert(key, updater.apply(null))) {
				this.size.set(size() + 1);
			}

			// Split the leaf node if it exceeds the block size
			if (leaf.isFull()) {
				if (cursor == null) {
					throw missingSplitPathError(leaf);
				}
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
	public void delete(@Nonnull K key) {
		final Cursor cursor = createCursor(key);
		final BPlusLeafTreeNode<K, V> leaf = cursor.leafNode();

		final boolean headRemoved = leaf.size() > 1 && key.equals(leaf.getKeys()[0]);
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
	 * @return an Optional containing the value associated with the key if it is present,
	 * or an empty Optional if the key is not found in the tree
	 */
	@Nonnull
	public Optional<V> search(@Nonnull K key) {
		return findLeafNode(key).getValue(key);
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
	 * Returns an allocation-free forward cursor over the tree entries: each {@link EntryCursor#next()} returns the key
	 * and {@link EntryCursor#value()} the paired value, without building an intermediate {@link Entry} object per step.
	 * Intended for hot full-scan read paths (e.g. sort-supplier traversal) where the per-entry allocation of
	 * {@link #entryIterator()} would dominate.
	 *
	 * @return an allocation-free forward entry cursor
	 */
	@Nonnull
	public EntryCursor<K, V> entryCursor() {
		return new ForwardTreeEntryCursor<>(createLeftmostCursor());
	}

	/**
	 * Returns an allocation-free reverse cursor over the tree entries. The right-to-left counterpart of
	 * {@link #entryCursor()}.
	 *
	 * @return an allocation-free reverse entry cursor
	 */
	@Nonnull
	public EntryCursor<K, V> entryReverseCursor() {
		return new ReverseTreeEntryCursor<>(createRightmostCursor());
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
		// remove the tree's own diff layer
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		// recurse into the size and root references and the whole node/value graph so that a tree which was created
		// and discarded within the same transaction (e.g. a removed sub-index) does not leave any of its inner
		// transactional objects ALIVE - which would otherwise be detected as stale during the commit sweep (INV-5).
		// The current (in-transaction) root MUST be read BEFORE the root reference's own layer is dropped - otherwise
		// getRoot() would fall back to the committed root and the recursion would miss every node created during this
		// transaction (e.g. split offspring), leaking their layers.
		final BPlusTreeNode<?> theRoot = getRoot();
		this.size.removeLayer(transactionalLayer);
		this.root.removeLayer(transactionalLayer);
		removeLayerRecursively(theRoot, transactionalLayer);
	}

	/**
	 * Generic-value leaf: the stored values may themselves be {@link TransactionalLayerProducer}s, so the shared cleanup
	 * walk in {@link AbstractTransactionalBPlusTree#removeLayerRecursively} must descend into them. Returns the live,
	 * transaction-aware value array - never a copy - so no allocation is incurred.
	 *
	 * @param leaf the leaf node whose values are inspected
	 * @return the leaf's live value array
	 */
	@Nonnull
	@Override
	protected Object[] transactionalLeafValues(@Nonnull BPlusTreeNode<?> leaf) {
		return ((BPlusLeafTreeNode<?, ?>) leaf).getValues();
	}

	@Nonnull
	@Override
	public TransactionalObjectBPlusTree<K, V> createCopyWithMergedTransactionalMemory(
		@Nullable Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		final BPlusTreeNode<?> theRoot = transactionalLayer.getStateCopyWithCommittedChanges(this.root)
			.orElseThrow();
		if (theRoot instanceof BPlusLeafTreeNode<?, ?> leafNode) {
			//noinspection unchecked
			final BPlusLeafTreeNode<K, V> theLeafNode = (BPlusLeafTreeNode<K, V>) leafNode;
			return new TransactionalObjectBPlusTree<>(
				this.valueBlockSize, this.minValueBlockSize,
				this.internalNodeBlockSize, this.minInternalNodeBlockSize,
				this.keyType,
				this.valueType,
				this.comparator,
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
				this.comparator,
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
	private Cursor createCursor(@Nonnull K key) {
		final ArrayList<CursorLevel> path = new ArrayList<>(estimatedPathLength());
		final BPlusTreeNode<?> theRoot = this.getRoot();
		final BPlusTreeNode<?>[] rootSiblings = (BPlusTreeNode<?>[]) new BPlusTreeNode[]{theRoot};
		path.add(new CursorLevel(rootSiblings, 0, 0));
		// if the root is internal node, add the levels to the path until the leaf node is reached
		if (theRoot instanceof BPlusInternalTreeNode<?> rootInternalNode) {
			//noinspection unchecked
			addCursorLevels((BPlusInternalTreeNode<K>) rootInternalNode, key, path);

		}
		return new Cursor(path);
	}

	/**
	 * Allocation-free leaf descent for READ-ONLY lookups: walks the root-to-leaf spine choosing each child by the same
	 * {@link BPlusInternalTreeNode#searchIndex} rule {@link #addCursorLevels} uses, but WITHOUT capturing the cursor
	 * path (no {@link CursorLevel} list, no backing array, no {@link Cursor}). It reads the transaction-aware
	 * `getChildren()` accessor exactly like the cursor descent, so it resolves the same nodes.
	 *
	 * Measured on this family, a captured path costs ~208 B per descent against ~0 B here, so every lookup that uses
	 * nothing but {@code cursor.leafNode()} takes this route. Structural operations (splits, deletes, consolidation,
	 * parent-key updates) mutate the captured path and must keep using {@link #createCursor(Comparable)}.
	 *
	 * @param key the key whose responsible leaf is located
	 * @return the leaf node that should hold the key (it may not actually contain it)
	 */
	@Nonnull
	private BPlusLeafTreeNode<K, V> findLeafNode(@Nonnull K key) {
		BPlusTreeNode<?> node = this.getRoot();
		while (node instanceof BPlusInternalTreeNode<?> internal) {
			//noinspection unchecked
			final BPlusInternalTreeNode<K> internalNode = (BPlusInternalTreeNode<K>) internal;
			node = internalNode.getChildren()[internalNode.searchIndex(key)];
		}
		//noinspection unchecked
		return (BPlusLeafTreeNode<K, V>) node;
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
		@Nonnull Cursor cursor
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
			this.comparator,
			// nodes created during a split must participate in the transactional layer so their
			// in-savepoint mutations are captured by the per-entity savepoint and can be rolled back
			true,
			this.transactionalLayerWrapper
		);

		// Move the other half into FRESH arrays of the right leaf node — the former leaf's arrays must stay intact so a
		// per-entity savepoint rollback can restore the pre-split leaf; compacting them in place would
		// corrupt that snapshot
		//noinspection unchecked
		final BPlusLeafTreeNode<K, V> rightLeaf = new BPlusLeafTreeNode<>(
			originKeys,
			originValues,
			(K[]) Array.newInstance(this.keyType, this.valueBlockSize),
			(V[]) Array.newInstance(this.valueType, this.valueBlockSize),
			mid,
			leftLeaf.getKeys().length,
			this.comparator,
			true,
			this.transactionalLayerWrapper
		);

		// remove changes of the previous root - it gets replaced
		if (Transaction.getTransactionalMemoryLayerIfExists(leaf) != null) {
			leaf.removeLayer();
		}

		// if the root splits, create a new root
		if (leaf == this.getRoot()) {
			// remove changes of the previous root - it gets replaced
			this.setRoot(
				new BPlusInternalTreeNode<>(
					this.internalNodeBlockSize,
					rightLeaf.getKeys()[0],
					leftLeaf, rightLeaf,
					this.keyType,
					this.comparator,
					true
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
		@Nonnull K key,
		@Nonnull CursorWithLevel cursor
	) {
		final BPlusInternalTreeNode<K> parent = cursor.parent();

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
		@Nonnull BPlusInternalTreeNode<K> internal,
		@Nonnull CursorWithLevel cursor
	) {
		// The node is full at split time (the only caller guards with isFull()), so occupancy equals capacity. Derive the
		// midpoint from the actual key count rather than valueBlockSize: internal nodes are sized by internalNodeBlockSize,
		// and spines bulk-assembled from persisted pages carry that (smaller) capacity, so a valueBlockSize-derived midpoint
		// overruns their arrays. keyCount is the number of separator keys (children = keyCount + 1).
		final int keyCount = internal.keyCount();
		final int mid = (keyCount + 1) / 2;
		final K[] originKeys = internal.getKeys();
		final BPlusTreeNode<?>[] originChildren = internal.getChildren();

		// Move half the keys to the new arrays of the left leaf node — the split constructor always allocates fresh
		// arrays, so the former node's arrays stay intact for a per-entity savepoint rollback. The new
		// nodes participate in the transactional layer so their in-savepoint mutations are captured.
		final BPlusInternalTreeNode<K> leftInternal = new BPlusInternalTreeNode<>(
			originKeys,
			originChildren,
			0,
			mid - 1,
			0,
			mid,
			this.keyType,
			this.comparator,
			true
		);

		// Move the other half to the start of existing arrays of former leaf in the right leaf node. End bounds are the
		// origin's actual occupancy (keyCount separators, keyCount + 1 children), not the array capacity — capacity may
		// exceed occupancy after the internalNodeBlockSize sizing fix, and only the live range must be copied.
		final BPlusInternalTreeNode<K> rightInternal = new BPlusInternalTreeNode<>(
			originKeys,
			originChildren,
			mid,
			keyCount,
			mid,
			keyCount + 1,
			this.keyType,
			this.comparator,
			true
		);

		// remove changes of the previous root - it gets replaced
		if (Transaction.getTransactionalMemoryLayerIfExists(internal) != null) {
			internal.removeLayer();
		}

		// if the root splits, create a new root
		if (internal == this.getRoot()) {
			this.setRoot(
				new BPlusInternalTreeNode<>(
					this.internalNodeBlockSize,
					rightInternal.getLeftBoundaryKey(),
					leftInternal, rightInternal,
					this.keyType,
					this.comparator,
					true
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
	 * Per-tree typed marker that exposes the comparable-key accessors and the comparator-aware key search common to
	 * both node kinds, kept off the key-agnostic {@link BPlusTreeNode} SPI so the shared base never sees (and never
	 * has to order) a typed key. Typed call sites that hold only a {@link BPlusTreeNode} reference (e.g. a children
	 * array element) cast to this to read the keys or to compute a key position.
	 *
	 * @param <M> the comparable key type
	 */
	interface ObjectKeyedNode<M extends Comparable<M>> {

		/**
		 * Retrieves an array of keys associated with the node.
		 *
		 * @return an array of keys present in the node. The array is guaranteed to be non-null.
		 */
		@Nonnull
		M[] getKeys();

		/**
		 * Retrieves the left boundary (smallest) key contained within the node.
		 *
		 * @return the left boundary key of the node.
		 */
		@Nonnull
		M getLeftBoundaryKey();

		/**
		 * Returns the optional comparator defining the total order of this node's keys, or `null` when the keys are
		 * ordered by their natural [Comparable] order.
		 *
		 * @return the key comparator, or `null` for natural ordering
		 */
		@Nullable
		Comparator<M> getComparator();

		/**
		 * Computes the insertion position of the given key within the ordered key range, routing the comparison
		 * through this node's [#getComparator] when present, otherwise through the keys' natural [Comparable] order.
		 * Shared by both the internal and leaf node implementations.
		 *
		 * @param key  the key whose position is searched
		 * @param keys the ordered key array to search within
		 * @param from the start index (inclusive)
		 * @param to   the end index (exclusive)
		 * @return the computed insertion position
		 */
		@Nonnull
		default InsertionPosition findKeyPosition(@Nonnull M key, @Nonnull M[] keys, int from, int to) {
			final Comparator<M> comparator = getComparator();
			return comparator == null
				? computeInsertPositionOfObjInOrderedArray(key, keys, from, to)
				: computeInsertPositionOfObjInOrderedArray(key, keys, from, to, comparator);
		}
	}

	/**
	 * Internal node implementation of the B+ tree that holds keys and child node pointers. Internal nodes serve
	 * as routing nodes — they do not store values directly but guide searches to the appropriate leaf nodes.
	 */
	static class BPlusInternalTreeNode<M extends Comparable<M>> implements
		InternalBPlusTreeNode<BPlusInternalTreeNode<M>>,
		ObjectKeyedNode<M>,
		Snapshotable<BPlusInternalTreeNode.BPlusInternalNodeMemento<M>> {
		@Serial private static final long serialVersionUID = -7185842083654066615L;
		@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
		/**
		 * Indicates whether this instance is permitted to create and use transactional layers. The tree nodes use themselves
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
		private BPlusTreeNode<?>[] children;

		/**
		 * Index of the last occupied position in the children array.
		 */
		private int peek;

		/**
		 * Optional comparator defining the total order of the keys. When `null`, keys are ordered by their natural
		 * [Comparable] order. Every key comparison performed by this node routes through [#findKeyPosition].
		 */
		@Getter @Nullable private final Comparator<M> comparator;

		/**
		 * Creates a new internal node with a single key separating two child nodes. This constructor is used
		 * when creating a new root after a split operation.
		 *
		 * @param blockSize          the maximum number of keys this node can hold
		 * @param key                the initial key separating the two child nodes
		 * @param leftLeaf           the left child node
		 * @param rightLeaf          the right child node
		 * @param keyType            the class of the key type
		 * @param comparator         optional comparator defining the key order; `null` ⇒ natural order
		 * @param transactionalLayer whether this node participates in the transactional memory layer
		 */
		public BPlusInternalTreeNode(
			int blockSize,
			@Nonnull M key,
			@Nonnull BPlusTreeNode<?> leftLeaf,
			@Nonnull BPlusTreeNode<?> rightLeaf,
			@Nonnull Class<M> keyType,
			@Nullable Comparator<M> comparator,
			boolean transactionalLayer
		) {
			//noinspection unchecked
			this.keys = (M[]) Array.newInstance(keyType, blockSize);
			this.children = new BPlusTreeNode[blockSize + 1];
			this.keys[0] = key;
			this.children[0] = leftLeaf;
			this.children[1] = rightLeaf;
			this.peek = 1;
			this.comparator = comparator;
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
		 * @param keyType            the class of the key type
		 * @param comparator         optional comparator defining the key order; `null` ⇒ natural order
		 * @param transactionalLayer whether this node participates in the transactional memory layer
		 */
		public BPlusInternalTreeNode(
			@Nonnull M[] originKeys,
			@Nonnull BPlusTreeNode<?>[] originChildren,
			int keyStart, int keyEnd,
			int childrenStart, int childrenEnd,
			@Nonnull Class<M> keyType,
			@Nullable Comparator<M> comparator,
			boolean transactionalLayer
		) {
			// we always create a new array for keys and children
			//noinspection unchecked
			this.keys = (M[]) Array.newInstance(keyType, originKeys.length);
			this.children = new BPlusTreeNode[originChildren.length];
			// Copy the keys and children from the origin arrays
			System.arraycopy(originKeys, keyStart, this.keys, 0, keyEnd - keyStart);
			System.arraycopy(originChildren, childrenStart, this.children, 0, childrenEnd - childrenStart);
			this.peek = childrenEnd - childrenStart - 1;
			this.comparator = comparator;
			this.transactionalLayer = transactionalLayer;
		}

		private BPlusInternalTreeNode(
			@Nonnull M[] originKeys,
			@Nonnull BPlusTreeNode<?>[] originChildren,
			int originPeek,
			@Nullable Comparator<M> comparator,
			boolean transactionalLayer
		) {
			// we always create a new array for keys and children
			this.keys = originKeys;
			this.children = originChildren;
			this.peek = originPeek;
			this.comparator = comparator;
			this.transactionalLayer = transactionalLayer;
		}

		@Nonnull
		@Override
		public M[] getKeys() {
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
			if (layer == null) {
				return this.keys;
			} else {
				return layer.keys;
			}
		}

		@Override
		public int getPeek() {
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
			if (layer == null) {
				return this.peek;
			} else {
				return layer.peek;
			}
		}

		@Override
		public void setPeek(int peek) {
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
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
						layer.children = new BPlusTreeNode[this.children.length];
						System.arraycopy(this.children, 0, layer.children, 0, originPeek + 1);
					} else {
						Arrays.fill(layer.children, peek + 1, originPeek + 1, null);
					}
				}
			}
		}

		@Override
		public long getHeapSizeInBytes(@Nonnull ToLongFunction<Object> elementSizer) {
			final VMLayout layout = VMLayout.current();
			// id + transactionalLayer + keys/children slots + peek
			long size = layout.sizeOfObject(Long.BYTES + 1L + 2L * layout.referenceSize() + Integer.BYTES);
			size += layout.sizeOfArray(this.keys.length, layout.referenceSize());
			size += layout.sizeOfArray(this.children.length, layout.referenceSize());
			// separator keys are boxed here, and are often the very instances the leaves below hold, so they go
			// through the caller's sizer rather than being charged unconditionally
			// THIS instance's own count, deliberately not `keyCount()`: that accessor resolves the calling thread's
			// transactional layer, which is a separate node object owning separate arrays
			final int keyCount = Math.max(this.peek, 0);
			for (int i = 0; i < keyCount; i++) {
				final M key = this.keys[i];
				if (key != null) {
					size += elementSizer.applyAsLong(key);
				}
			}
			for (int i = 0; i < keyCount + 1; i++) {
				size += this.children[i].getHeapSizeInBytes(elementSizer);
			}
			return size;
		}

		@Override
		public int keyCount() {
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
			if (layer == null) {
				return Math.max(this.peek, 0);
			} else {
				return Math.max(layer.peek, 0);
			}
		}

		@Override
		public boolean isFull() {
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
			if (layer == null) {
				return this.peek == this.children.length - 1;
			} else {
				return layer.peek == layer.children.length - 1;
			}
		}

		@Override
		public void toVerboseString(@Nonnull StringBuilder sb, int level, int indentSpaces) {
			final M[] theKeys;
			final BPlusTreeNode<?>[] theChildren;
			final int thePeek;
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
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
				final BPlusTreeNode<?> child = theChildren[i];
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

			final BPlusInternalTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
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
		public void stealFromRight(int numberOfHeadValues, @Nonnull BPlusInternalTreeNode<M> nextNode) {
			Assert.isPremiseValid(numberOfHeadValues > 0, "Number of head values to steal must be positive!");

			final BPlusInternalTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			if (layer == null) {
				// we move all the children
				final BPlusTreeNode<?>[] nextNodeChildren = nextNode.getChildrenForUpdate();
				System.arraycopy(nextNodeChildren, 0, this.children, this.peek + 1, numberOfHeadValues);
				System.arraycopy(
					nextNodeChildren, numberOfHeadValues, nextNodeChildren, 0, nextNode.size() - numberOfHeadValues);

				// set the key for the first child of the next node
				this.keys[this.peek] = leftBoundaryKeyOf(this.children[this.peek + 1]);

				// the right sibling may be a committed (shared) node while `this` is a transaction-local node
				// (transactionalLayer == false): steal-from-right SHIFTS the sibling keys in place, so it must
				// decouple them first (...ForUpdate) or it would corrupt the shared committed state.
				final M[] nextNodeKeys = nextNode.getKeysForUpdate();
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
				final M[] nextNodeKeysForUpdate = nextNode.getKeysForUpdate();
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
		public void mergeWithLeft(@Nonnull BPlusInternalTreeNode<M> previousNode) {
			// merging into an empty internal node (peek == -1) is never requested by the rebalancer: a node
			// with a single child (peek == 0) is collapsed before another deletion could drain it further,
			// so the shift arithmetic below assumes this node already holds at least one child
			Assert.isPremiseValid(
				getPeek() >= 0, "Cannot merge into an empty internal node (it has no children)!"
			);
			final int mergePeek = previousNode.getPeek();

			final BPlusInternalTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
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
		public void mergeWithRight(@Nonnull BPlusInternalTreeNode<M> nextNode) {
			// merging into an empty internal node (peek == -1) is never requested by the rebalancer: a node
			// with a single child (peek == 0) is collapsed before another deletion could drain it further,
			// so the separator-key write below assumes this node already holds at least one child
			Assert.isPremiseValid(
				getPeek() >= 0, "Cannot merge into an empty internal node (it has no children)!"
			);
			final int mergePeek = nextNode.getPeek();

			final BPlusInternalTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
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

		@Nonnull
		@Override
		public M getLeftBoundaryKey() {
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
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
		 * @return an array of keys of type M representing the keys of the current node, adjusted for the transactional layer if applicable.
		 */
		@Nonnull
		public M[] getKeysForUpdate() {
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
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
		public BPlusTreeNode<?>[] getChildren() {
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
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
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
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
			@Nonnull M key,
			@Nonnull BPlusTreeNode<?> original,
			@Nonnull BPlusTreeNode<?> left,
			@Nonnull BPlusTreeNode<?> right
		) {
			Assert.isPremiseValid(
				!this.isFull(),
				"Internal node must not be full to accommodate two leaf nodes after their split!"
			);

			final BPlusInternalTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			if (layer == null) {
				// the peek relates to children, which are one more than keys, that's why we don't use peek + 1, but mere peek
				final InsertionPosition insertionPosition = findKeyPosition(key, this.keys, 0, this.peek);
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
				final InsertionPosition insertionPosition = findKeyPosition(key, layer.keys, 0, layer.peek);
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
		 * Searches for the child index that should contain the given key.
		 * This method avoids allocating a NodeWithIndex record.
		 *
		 * @param key the key to search for within the B+ Tree.
		 * @return the index of the child that should contain the specified key.
		 */
		public int searchIndex(@Nonnull M key) {
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
			if (layer == null) {
				final InsertionPosition insertionPosition = findKeyPosition(key, this.keys, 0, this.peek);
				return insertionPosition.alreadyPresent() ?
					insertionPosition.position() + 1 : insertionPosition.position();
			} else {
				final InsertionPosition insertionPosition = findKeyPosition(key, layer.keys, 0, layer.peek);
				return insertionPosition.alreadyPresent() ?
					insertionPosition.position() + 1 : insertionPosition.position();
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
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
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

			final BPlusInternalTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
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
		public BPlusInternalTreeNode<M> createLayer() {
			return new BPlusInternalTreeNode<>(
				this.keys,
				this.children,
				this.peek,
				this.comparator,
				false
			);
		}

		/**
		 * Captures this layer's revertable copy-on-write state for a per-entity savepoint. Only the keys
		 * and children arrays and the peek index are mutable here; both arrays are cloned (shallow — the key objects are
		 * immutable and the child nodes own their own transactional layers and are snapshotted independently) so that a
		 * later mutation, or a repeated {@link #restore}, cannot corrupt the memento.
		 *
		 * @return an independent snapshot of this internal node's array structure
		 */
		@Nonnull
		@Override
		public BPlusInternalNodeMemento<M> snapshot() {
			return new BPlusInternalNodeMemento<>(this.keys.clone(), this.children.clone(), this.peek);
		}

		/**
		 * Restores the array structure captured by {@link #snapshot}. Fresh clones of the memento's arrays are installed
		 * so the memento stays reusable for a repeated restore.
		 *
		 * @param memento the state previously captured by {@link #snapshot}
		 */
		@Override
		public void restore(@Nonnull BPlusInternalNodeMemento<M> memento) {
			this.keys = memento.keys().clone();
			this.children = memento.children().clone();
			this.peek = memento.peek();
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
				return new BPlusInternalTreeNode<>(
					theKeys,
					newChildren,
					thePeek,
					this.comparator,
					true
				);
			} else if (layer != null) {
				return new BPlusInternalTreeNode<>(
					theKeys,
					theChildren,
					thePeek,
					this.comparator,
					true
				);
			} else if (!this.transactionalLayer) {
				// nodes created during splits/merges are built with transactionalLayer=false so they do
				// not allocate STM layers mid-transaction; on commit they must be rebuilt as participating
				// (transactionalLayer=true) nodes so subsequent transactions can layer changes over them
				return new BPlusInternalTreeNode<>(
					theKeys,
					theChildren,
					thePeek,
					this.comparator,
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
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			if (layer != null) {
				//noinspection ArrayEquality
				if (layer.keys == this.keys) {
					//noinspection unchecked
					layer.keys = (M[]) Array.newInstance(this.keys.getClass().getComponentType(), this.keys.length);
					System.arraycopy(this.keys, 0, layer.keys, 0, this.peek);
				}
				//noinspection ArrayEquality
				if (layer.children == this.children) {
					layer.children = new BPlusTreeNode[this.children.length];
					System.arraycopy(this.children, 0, layer.children, 0, this.peek + 1);
				}
			}
		}

		/**
		 * Immutable savepoint memento of an internal node's copy-on-write array structure. The arrays are private
		 * clones owned by the memento (see {@link #snapshot}); the key objects and child-node references they hold are
		 * shared by design.
		 *
		 * @param keys     clone of the separator-key array
		 * @param children clone of the child-pointer array
		 * @param peek     the last occupied child index
		 */
		record BPlusInternalNodeMemento<M extends Comparable<M>>(
			@Nonnull M[] keys,
			@Nonnull BPlusTreeNode<?>[] children,
			int peek
		) {
		}

	}

	/**
	 * Leaf node implementation of the B+ tree that stores key-value pairs. Leaf nodes hold all actual data
	 * in the tree and are the terminal nodes in the B+ tree structure.
	 */
	static class BPlusLeafTreeNode<M extends Comparable<M>, N>
		implements
		BPlusTreeNode<BPlusLeafTreeNode<M, N>>,
		ObjectKeyedNode<M>,
		Snapshotable<BPlusLeafTreeNode.BPlusLeafNodeMemento<M, N>> {
		@Serial private static final long serialVersionUID = 8382269323782408764L;
		@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
		/**
		 * Indicates whether this instance is permitted to create and use transactional layers. The tree nodes use themselves
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

		/**
		 * Optional comparator defining the total order of the keys. When `null`, keys are ordered by their natural
		 * [Comparable] order. Every key comparison performed by this node routes through [#findKeyPosition] /
		 * [#findKeyIndex].
		 */
		@Getter @Nullable private final Comparator<M> comparator;

		/**
		 * Creates a new empty leaf node with the specified block size.
		 *
		 * @param blockSize                 the maximum number of key-value pairs this leaf node can hold
		 * @param keyType                   the class of the keys stored in this node
		 * @param valueType                 the class of the values stored in this node
		 * @param comparator                optional comparator defining the key order; `null` ⇒ natural order
		 * @param transactionalLayerWrapper optional function to wrap values into a transactional layer
		 * @param transactionalLayer        whether this node participates in the transactional memory layer
		 */
		public BPlusLeafTreeNode(
			int blockSize,
			@Nonnull Class<M> keyType,
			@Nonnull Class<N> valueType,
			@Nullable Comparator<M> comparator,
			@Nullable Function<Object, N> transactionalLayerWrapper,
			boolean transactionalLayer
		) {
			//noinspection unchecked
			this.keys = (M[]) Array.newInstance(keyType, blockSize);
			//noinspection unchecked
			this.values = (N[]) Array.newInstance(valueType, blockSize);
			this.comparator = comparator;
			this.transactionalLayerWrapper = transactionalLayerWrapper;
			this.peek = -1;
			this.transactionalLayer = transactionalLayer;
		}

		/**
		 * Creates a new leaf node by copying a range of keys and values from origin arrays into the target arrays.
		 * This constructor is used during node split operations.
		 *
		 * @param originKeys                the source array of keys to copy from
		 * @param originValues              the source array of values to copy from
		 * @param keys                      the target array for keys (may be the same as originKeys)
		 * @param values                    the target array for values (may be the same as originValues)
		 * @param start                     the start index (inclusive) in the origin arrays
		 * @param end                       the end index (exclusive) in the origin arrays
		 * @param comparator                optional comparator defining the key order; `null` ⇒ natural order
		 * @param transactionalLayer        whether this node participates in the transactional memory layer
		 * @param transactionalLayerWrapper optional function to wrap values into a transactional layer
		 */
		public BPlusLeafTreeNode(
			@Nonnull M[] originKeys,
			@Nonnull N[] originValues,
			@Nonnull M[] keys,
			@Nonnull N[] values,
			int start, int end,
			@Nullable Comparator<M> comparator,
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
			this.comparator = comparator;
			this.transactionalLayer = transactionalLayer;
			this.transactionalLayerWrapper = transactionalLayerWrapper;
		}

		private BPlusLeafTreeNode(
			@Nonnull M[] keys,
			@Nonnull N[] values,
			int peek,
			@Nullable Comparator<M> comparator,
			boolean transactionalLayer,
			@Nullable Function<Object, N> transactionalLayerWrapper
		) {
			this.keys = keys;
			this.values = values;
			this.peek = peek;
			this.comparator = comparator;
			this.transactionalLayer = transactionalLayer;
			this.transactionalLayerWrapper = transactionalLayerWrapper;
		}

		@Nonnull
		@Override
		public M[] getKeys() {
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
			if (layer == null) {
				return this.keys;
			} else {
				return layer.keys;
			}
		}

		@Override
		public int getPeek() {
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
			if (layer == null) {
				return this.peek;
			} else {
				return layer.peek;
			}
		}

		@Override
		public void setPeek(int peek) {
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
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
						layer.values = (N[]) Array.newInstance(
							this.values.getClass().getComponentType(), this.values.length
						);
						System.arraycopy(this.values, 0, layer.values, 0, originPeek + 1);
					} else {
						Arrays.fill(layer.values, peek + 1, originPeek + 1, null);
					}
				}
			}
		}

		@Override
		public long getHeapSizeInBytes(@Nonnull ToLongFunction<Object> elementSizer) {
			final VMLayout layout = VMLayout.current();
			// id + transactionalLayer + wrapper/keys/values slots + peek
			long size = layout.sizeOfObject(Long.BYTES + 1L + 3L * layout.referenceSize() + Integer.BYTES);
			size += layout.sizeOfArray(this.keys.length, layout.referenceSize());
			size += layout.sizeOfArray(this.values.length, layout.referenceSize());
			// both the boxed keys and the stored values are the caller's to price
			// THIS instance's own count, deliberately not `keyCount()`: that accessor resolves the calling thread's
			// transactional layer, which is a separate node object owning separate arrays
			final int liveCount = this.peek + 1;
			for (int i = 0; i < liveCount; i++) {
				final M key = this.keys[i];
				if (key != null) {
					size += elementSizer.applyAsLong(key);
				}
				final N value = this.values[i];
				if (value != null) {
					size += elementSizer.applyAsLong(value);
				}
			}
			return size;
		}

		@Override
		public int keyCount() {
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
			if (layer == null) {
				return this.peek + 1;
			} else {
				return layer.peek + 1;
			}
		}

		@Override
		public boolean isFull() {
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
			if (layer == null) {
				return this.peek == this.values.length - 1;
			} else {
				return layer.peek == layer.values.length - 1;
			}
		}

		/**
		 * Whether a single insert of a **new** key could make this leaf {@link #isFull()} — i.e. whether the caller
		 * must capture a cursor path before mutating, so a split has one.
		 *
		 * Deliberately mirrors {@link #isFull()}: it reads `peek` and the capacity from the **same** resolved state,
		 * so the two can never disagree. Comparing against the tree's configured `valueBlockSize` instead would hold
		 * only while every leaf array happens to be allocated at exactly that size, and nothing enforces that
		 * coupling — a shorter array would reach {@link #isFull()} without ever tripping the guard.
		 *
		 * @return true when one more key could fill this leaf
		 */
		public boolean isNearlyFull() {
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
			if (layer == null) {
				return this.peek >= this.values.length - 2;
			} else {
				return layer.peek >= layer.values.length - 2;
			}
		}

		@Override
		public void toVerboseString(@Nonnull StringBuilder sb, int level, int indentSpaces) {
			sb.append(" ".repeat(level * indentSpaces));
			final M[] theKeys;
			final N[] theValues;
			final int thePeek;

			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
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
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
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
		public void stealFromRight(int numberOfHeadValues, @Nonnull BPlusLeafTreeNode<M, N> nextNode) {
			Assert.isPremiseValid(numberOfHeadValues > 0, "Number of head values to steal must be positive!");

			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			if (layer == null) {
				// the right sibling may be a committed (shared) node while `this` is a transaction-local node
				// (transactionalLayer == false): steal-from-right SHIFTS the sibling arrays in place, so it must
				// decouple them first (...ForUpdate) or it would corrupt the shared committed state. The
				// ...ForUpdate accessors decouple a committed sibling inside a transaction, in-place no-op outside.
				System.arraycopy(nextNode.getKeysForUpdate(), 0, this.keys, this.peek + 1, numberOfHeadValues);
				System.arraycopy(nextNode.getValuesForUpdate(), 0, this.values, this.peek + 1, numberOfHeadValues);
				System.arraycopy(
					nextNode.getKeysForUpdate(), numberOfHeadValues, nextNode.getKeysForUpdate(), 0,
					nextNode.size() - numberOfHeadValues
				);
				System.arraycopy(
					nextNode.getValuesForUpdate(), numberOfHeadValues, nextNode.getValuesForUpdate(), 0,
					nextNode.size() - numberOfHeadValues
				);
				nextNode.setPeek(nextNode.getPeek() - numberOfHeadValues);
				this.peek += numberOfHeadValues;
			} else {
				// we need to decouple the arrays before modifying them
				decoupleTransactionalArrays();
				nextNode.decoupleTransactionalArrays();

				System.arraycopy(
					nextNode.getKeysForUpdate(), 0, layer.keys, layer.peek + 1, numberOfHeadValues
				);
				System.arraycopy(
					nextNode.getValuesForUpdate(), 0, layer.values, layer.peek + 1, numberOfHeadValues
				);
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
		public void mergeWithLeft(@Nonnull BPlusLeafTreeNode<M, N> previousNode) {
			final int mergePeek = previousNode.getPeek();
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
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
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			if (layer == null) {
				System.arraycopy(
					nextNode.getKeys(), 0, this.keys, this.peek + 1, mergePeek + 1
				);
				System.arraycopy(
					nextNode.getValues(), 0, this.values, this.peek + 1, mergePeek + 1
				);
				this.peek += mergePeek + 1;
				nextNode.setPeek(-1);
			} else {
				// we need to decouple the arrays before modifying them
				decoupleTransactionalArrays();
				nextNode.decoupleTransactionalArrays();

				System.arraycopy(
					nextNode.getKeysForUpdate(), 0, layer.keys, layer.peek + 1, mergePeek + 1
				);
				System.arraycopy(
					nextNode.getValuesForUpdate(), 0, layer.values, layer.peek + 1, mergePeek + 1
				);
				layer.peek += mergePeek + 1;
				nextNode.setPeek(-1);
			}
		}

		@Nonnull
		@Override
		public M getLeftBoundaryKey() {
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
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
		 * @return an array of keys of type M representing the keys of the current node, adjusted for the transactional layer if applicable.
		 */
		@Nonnull
		public M[] getKeysForUpdate() {
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
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
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
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
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			if (layer == null) {
				return this.values;
			} else {
				// internal arrays may have been still identical to the original arrays
				// we need to copy them in the transactional layer, before modifying

				//noinspection ArrayEquality
				if (layer.values == this.values) {
					//noinspection unchecked
					layer.values = (N[]) Array.newInstance(
						this.values.getClass().getComponentType(), this.values.length);
					System.arraycopy(this.values, 0, layer.values, 0, this.values.length);
				}
				return layer.values;
			}
		}

		/**
		 * Returns the index of the given key within the ordered key range, or a negative value following the
		 * [java.util.Arrays#binarySearch] convention when the key is absent. The comparison routes through this
		 * node's [#comparator] when present, otherwise through the keys' natural [Comparable] order.
		 *
		 * @param key  the key to search for
		 * @param keys the ordered key array to search within
		 * @param from the start index (inclusive)
		 * @param to   the end index (exclusive)
		 * @return the index of the key if present; otherwise `(-(insertion point) - 1)`
		 */
		private int findKeyIndex(@Nonnull M key, @Nonnull M[] keys, int from, int to) {
			// the JDK Arrays.binarySearch with comparator treats a null comparator as natural ordering; we keep
			// the explicit branch so the natural-order path stays on the Comparable overload for clarity
			return this.comparator == null
				? Arrays.binarySearch(keys, from, to, key)
				: Arrays.binarySearch(keys, from, to, key, this.comparator);
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

			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
			if (layer == null) {
				theKeys = this.keys;
				theValues = this.values;
				thePeek = this.peek;
			} else {
				theKeys = layer.keys;
				theValues = layer.values;
				thePeek = layer.peek;
			}

			final InsertionPosition insertionPosition = findKeyPosition(key, theKeys, 0, thePeek + 1);
			return insertionPosition.alreadyPresent()
				? Optional.of(theValues[insertionPosition.position()])
				: Optional.empty();
		}

		/**
		 * Searches for the index of a value in the node's key-value pairs by the specified key.
		 * Returns the index of the key if found, or -1 if the key is not present.
		 *
		 * @param key the key to search for in the leaf node
		 * @return the index of the key in the keys/values arrays if found; -1 otherwise
		 */
		public int getValueIndex(@Nonnull M key) {
			final M[] theKeys;
			final int thePeek;

			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
			if (layer == null) {
				theKeys = this.keys;
				thePeek = this.peek;
			} else {
				theKeys = layer.keys;
				thePeek = layer.peek;
			}

			final InsertionPosition insertionPosition = findKeyPosition(key, theKeys, 0, thePeek + 1);
			return insertionPosition.alreadyPresent()
				? insertionPosition.position()
				: -1;
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
				this.comparator,
				false,
				this.transactionalLayerWrapper
			);
		}

		/**
		 * Captures this layer's revertable copy-on-write state for a per-entity savepoint. Both the key
		 * and value arrays are cloned (shallow — the key objects are immutable and the values either are immutable or
		 * own their own transactional layers and are snapshotted independently) so that a later mutation, or a repeated
		 * {@link #restore}, cannot corrupt the memento.
		 *
		 * @return an independent snapshot of this leaf's two arrays and peek
		 */
		@Nonnull
		@Override
		public BPlusLeafNodeMemento<M, N> snapshot() {
			return new BPlusLeafNodeMemento<>(this.keys.clone(), this.values.clone(), this.peek);
		}

		/**
		 * Restores the array state captured by {@link #snapshot}. Fresh clones of the memento's arrays are installed so
		 * the memento stays reusable for a repeated restore.
		 *
		 * @param memento the state previously captured by {@link #snapshot}
		 */
		@Override
		public void restore(@Nonnull BPlusLeafNodeMemento<M, N> memento) {
			this.keys = memento.keys().clone();
			this.values = memento.values().clone();
			this.peek = memento.peek();
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
			if (TransactionalStateProducer.class.isAssignableFrom(this.values.getClass().getComponentType())) {
				for (int i = 0; i < thePeek + 1; i++) {
					// this.transactionalLayerWrapper is not null, because the values are transactional layers
					//noinspection unchecked,DataFlowIssue
					final N value = this.transactionalLayerWrapper.apply(
						transactionalLayer.getStateCopyWithCommittedChanges(
							(TransactionalStateProducer<? extends N>) theValues[i]
						)
					);
					if (newValues == null && value != theValues[i]) {
						//noinspection unchecked
						newValues = (N[]) Array.newInstance(
							this.values.getClass().getComponentType(), theValues.length
						);
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
					this.comparator,
					true,
					this.transactionalLayerWrapper
				);
			} else if (layer != null) {
				return new BPlusLeafTreeNode<>(
					theKeys,
					theValues,
					thePeek,
					this.comparator,
					true,
					this.transactionalLayerWrapper
				);
			} else if (!this.transactionalLayer) {
				// nodes created during splits/merges are built with transactionalLayer=false so they do
				// not allocate STM layers mid-transaction; on commit they must be rebuilt as participating
				// (transactionalLayer=true) nodes so subsequent transactions can layer changes over them
				return new BPlusLeafTreeNode<>(
					theKeys,
					theValues,
					thePeek,
					this.comparator,
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
		 */
		public boolean delete(@Nonnull M key) {
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			if (layer == null) {
				final int index = findKeyIndex(key, this.keys, 0, this.peek + 1);

				if (index >= 0) {
					// the value is discarded from the tree - release its transactional diff layer (if any)
					// so it is not left ALIVE and detected as stale during commit; outside a transaction the
					// guard short-circuits and this is a no-op
					discardRemovedValueLayer(this.values[index]);
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
				final int index = findKeyIndex(key, layer.keys, 0, layer.peek + 1);

				if (index >= 0) {
					// the value is discarded from the tree - release its transactional diff layer (if any)
					// so it is not left ALIVE and detected as stale during commit
					discardRemovedValueLayer(layer.values[index]);
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
		 * Releases the transactional diff layer of a value that is being discarded from this leaf node. When the
		 * value is a [TransactionalLayerProducer] whose layer was opened earlier in the current transaction (e.g. its
		 * inner state was mutated, or it was freshly created and mutated within the same transaction), that layer must
		 * be removed explicitly - otherwise it stays ALIVE after commit and triggers a
		 * `StaleTransactionMemoryException` during layer sweep verification.
		 *
		 * This mirrors the node-cleanup discipline already applied when nodes are discarded during splits, merges and
		 * root replacement. It must not be applied to values that are merely moved to a sibling node (steal/merge
		 * rebalancing), because those values remain referenced and their layers must survive.
		 *
		 * The cleanup is delegated to [TransactionalLayerProducer#removeLayer()], which recurses into the value's inner
		 * transactional objects. It must NOT be short-circuited on the parent's own layer presence: a composite producer
		 * (e.g. a [io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer] such as a
		 * [io.evitadb.index.invertedIndex.ValueToRecordBitmap]) never opens a layer of its own — only its children do —
		 * so guarding on the parent's layer would leave the children's layers orphaned and detected as stale during
		 * commit. The no-arg `removeLayer()` resolves the current transaction's maintainer and is a safe no-op when no
		 * transaction is open.
		 *
		 * @param removed the value removed from the leaf, may be null
		 */
		private static void discardRemovedValueLayer(@Nullable Object removed) {
			if (removed instanceof final TransactionalStateProducer<?> producer) {
				producer.removeLayer();
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
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			if (layer == null) {
				Assert.isPremiseValid(
					this.peek < this.keys.length - 1,
					"Cannot insert into a full leaf node, split the node first!"
				);

				final InsertionPosition insertionPosition = findKeyPosition(key, this.keys, 0, this.peek + 1);
				if (insertionPosition.alreadyPresent()) {
					// an existing value is overwritten - release the discarded instance's diff layer (if any
					// and if it is genuinely a different instance) so it is not left ALIVE during commit
					final N previousValue = this.values[insertionPosition.position()];
					if (value != previousValue) {
						discardRemovedValueLayer(previousValue);
					}
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

				final InsertionPosition insertionPosition = findKeyPosition(key, layer.keys, 0, layer.peek + 1);
				if (insertionPosition.alreadyPresent()) {
					// an existing value is overwritten - release the discarded instance's diff layer (if any
					// and if it is genuinely a different instance) so it is not left ALIVE during commit
					final N previousValue = layer.values[insertionPosition.position()];
					if (value != previousValue) {
						discardRemovedValueLayer(previousValue);
					}
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
			final BPlusLeafTreeNode<M, N> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
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
					layer.values = (N[]) Array.newInstance(
						this.values.getClass().getComponentType(), this.values.length);
					System.arraycopy(this.values, 0, layer.values, 0, this.peek + 1);
				}
			}
		}

		/**
		 * Immutable savepoint memento of a leaf node's copy-on-write arrays. The arrays are private clones owned by the
		 * memento (see {@link #snapshot}); the key objects and value references they hold are shared by design.
		 *
		 * @param keys   clone of the key array
		 * @param values clone of the value array
		 * @param peek   the last occupied index
		 */
		record BPlusLeafNodeMemento<M extends Comparable<M>, N>(
			@Nonnull M[] keys,
			@Nonnull N[] values,
			int peek
		) {
		}

	}

	/**
	 * Iterator that traverses the B+ Tree from left to right.
	 */
	private static abstract class AbstractForwardTreeIterator<M extends Comparable<M>, N, S> implements Iterator<S> {
		/**
		 * Array of arrays representing siblings on each level of the path. Visible to subclasses so the hot
		 * {@link EntryCursor} can resolve the following leaf for software prefetching.
		 */
		@Nonnull protected final BPlusTreeNode<?>[][] path;
		/**
		 * The index of the current key on particular path. Visible to subclasses for prefetch lookahead.
		 */
		@Nonnull protected final int[] pathIndex;
		/**
		 * The peek index of each sibling array on the path. Visible to subclasses for prefetch lookahead.
		 */
		@Nonnull protected final int[] pathPeeks;
		/**
		 * Function allowing to extract the iterator output from the current index and the cached leaf key/value arrays.
		 */
		@Nonnull private final IntObjTriFunction<M[], N[], S> outputExtractor;
		/**
		 * The index of the current key within the current leaf node. Visible to subclasses so the hot
		 * {@link EntryCursor} can read it without an accessor call.
		 */
		protected int currentIndex;
		/**
		 * Flag indicating whether there are more elements to traverse.
		 */
		protected boolean hasNext;
		/**
		 * The current leaf's key array, value array and last occupied index, resolved through the transactional layer
		 * exactly once per leaf when the iterator enters it (see {@link #loadCurrentLeaf()}). Caching them here turns
		 * the hot per-element path into plain array indexing and removes the per-element `ThreadLocal` lookup that
		 * {@link BPlusLeafTreeNode#getKeys()} / {@link BPlusLeafTreeNode#getValues()} / {@link BPlusLeafTreeNode#getPeek()}
		 * would otherwise repeat on every step. Visible to subclasses so the hot {@link EntryCursor} can index them
		 * directly.
		 */
		protected M[] leafKeys;
		protected N[] leafValues;
		protected int leafPeek;

		/**
		 * Initializes the forward iterator starting from the leftmost position of the cursor.
		 *
		 * @param cursor          the cursor providing the traversal path through the B+ tree
		 * @param outputExtractor function to extract the output value from the current index and leaf node
		 */
		public AbstractForwardTreeIterator(
			@Nonnull Cursor cursor,
			@Nonnull IntObjTriFunction<M[], N[], S> outputExtractor
		) {
			final List<CursorLevel> cursorPath = cursor.path();
			this.path = new BPlusTreeNode[cursorPath.size()][];
			this.pathIndex = new int[this.path.length];
			this.pathPeeks = new int[this.path.length];
			for (int i = 0; i < cursorPath.size(); i++) {
				final CursorLevel cursorLevel = cursorPath.get(i);
				this.path[i] = cursorLevel.siblings();
				this.pathIndex[i] = cursorLevel.index();
				this.pathPeeks[i] = cursorLevel.peek();
			}
			this.currentIndex = 0;
			// resolve the first leaf's arrays once; all subsequent per-element access reads the cached arrays
			loadCurrentLeaf();
			this.hasNext = this.leafPeek >= 0;
			this.outputExtractor = outputExtractor;
		}

		/**
		 * Initializes the forward iterator starting from the specified key or the first key greater than it.
		 *
		 * @param cursor          the cursor providing the traversal path through the B+ tree
		 * @param key             the key to start the iteration from
		 * @param outputExtractor function to extract the output value from the current index and leaf node
		 */
		public AbstractForwardTreeIterator(
			@Nonnull Cursor cursor, @Nonnull M key,
			@Nonnull IntObjTriFunction<M[], N[], S> outputExtractor
		) {
			final List<CursorLevel> cursorPath = cursor.path();
			this.path = new BPlusTreeNode[cursorPath.size()][];
			this.pathIndex = new int[this.path.length];
			this.pathPeeks = new int[this.path.length];
			for (int i = 0; i < cursorPath.size(); i++) {
				final CursorLevel cursorLevel = cursorPath.get(i);
				this.path[i] = cursorLevel.siblings();
				this.pathIndex[i] = cursorLevel.index();
				this.pathPeeks[i] = cursorLevel.peek();
			}
			final BPlusLeafTreeNode<M, N> startLeaf = cursor.leafNode();
			final InsertionPosition insertionPosition = startLeaf.findKeyPosition(
				key, startLeaf.getKeys(), 0, startLeaf.size()
			);
			// resolve the start leaf's arrays once; the per-element hot path then reads only the cached arrays
			loadCurrentLeaf();
			this.currentIndex = insertionPosition.position();
			if (this.currentIndex <= this.leafPeek) {
				// the start key lies within the current leaf - it has at least one key >= key
				this.hasNext = true;
			} else {
				// the start key is greater than every key in the current leaf - the matching keys, if any,
				// live in a following leaf, so we must advance the path to the next leaf instead of stopping
				// here (otherwise keys/values in the gap between two leaves would be skipped); moveToNextLeaf
				// refreshes the cached leaf arrays on success
				this.hasNext = moveToNextLeaf();
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
			// the leaf arrays are cached, so the hot path is plain array indexing - no path walk, no ThreadLocal hit
			final int index = this.currentIndex;
			final S key = this.outputExtractor.apply(index, this.leafKeys, this.leafValues);

			if (index < this.leafPeek) {
				// easy path, there is another key in the current leaf
				this.currentIndex = index + 1;
			} else {
				// we need to traverse up the path to find the next sibling leaf (refreshes the cached arrays)
				this.hasNext = moveToNextLeaf();
			}
			return key;
		}

		/**
		 * Resolves the current leaf (the deepest node on the path) through the transactional layer exactly once and
		 * caches its key array, value array and last occupied index. All per-element access in {@link #next()} then
		 * reads these cached fields, so the costly transactional-layer {@code ThreadLocal} lookup is paid once per
		 * leaf instead of three times per element.
		 */
		private void loadCurrentLeaf() {
			//noinspection unchecked
			final BPlusLeafTreeNode<M, N> leaf =
				(BPlusLeafTreeNode<M, N>) this.path[this.path.length - 1][this.pathIndex[this.pathIndex.length - 1]];
			this.leafKeys = leaf.getKeys();
			this.leafValues = leaf.getValues();
			this.leafPeek = leaf.getPeek();
		}

		/**
		 * Advances the iterator path to the first entry of the next leaf to the right of the current one.
		 * On success the path arrays point at the following leaf, {@link #currentIndex} is reset to its
		 * first entry and the cached leaf arrays are refreshed; on failure the path is left untouched.
		 *
		 * @return true if a following leaf was found, false if the current leaf is the rightmost one
		 */
		protected final boolean moveToNextLeaf() {
			int level = this.pathIndex.length - 1;
			BPlusTreeNode<?>[] parentLevel = this.path[level];
			while (parentLevel != null) {
				// if there is a next sibling at this level
				if (this.pathIndex[level] < this.pathPeeks[level]) {
					// we found the parent that has a next sibling - so move the index
					this.pathIndex[level] = this.pathIndex[level] + 1;
					BPlusTreeNode<?> currentNode = this.path[level][this.pathIndex[level]];
					// all levels below, will point to the first child of the new cursor level
					for (int i = level + 1; i <= this.path.length - 1; i++) {
						Assert.isPremiseValid(
							currentNode instanceof BPlusInternalTreeNode, "Internal node expected!");
						//noinspection unchecked
						this.path[i] = ((BPlusInternalTreeNode<M>) currentNode).getChildren();
						this.pathIndex[i] = 0;
						this.pathPeeks[i] = currentNode.getPeek();
						currentNode = this.path[i][0];
					}
					this.currentIndex = 0;
					// refresh the cached key/value arrays + peek for the newly entered leaf
					loadCurrentLeaf();
					return true;
				} else {
					// we need to continue search with the parent of the parent
					level--;
					parentLevel = level > 0 ? this.path[level] : null;
				}
			}
			return false;
		}

		/**
		 * Resolves the leaf immediately to the right of the current one **without mutating** the cursor's path -
		 * a read-only twin of {@link #moveToNextLeaf()} used purely to obtain the next leaf reference for software
		 * prefetching. Returns `null` when the current leaf is the rightmost one.
		 *
		 * The cursor state is left untouched, so this is safe to call mid-iteration; only the returned node (and the
		 * internal nodes walked to reach it) are read.
		 *
		 * @return the next leaf to the right, or `null` if none
		 */
		@Nullable
		protected final BPlusLeafTreeNode<M, N> peekNextLeaf() {
			int level = this.pathIndex.length - 1;
			while (level > 0) {
				if (this.pathIndex[level] < this.pathPeeks[level]) {
					// the next sibling at this level exists - descend to its leftmost leaf without touching the path
					BPlusTreeNode<?> node = this.path[level][this.pathIndex[level] + 1];
					for (int i = level + 1; i <= this.path.length - 1; i++) {
						if (!(node instanceof BPlusInternalTreeNode)) {
							return null;
						}
						//noinspection unchecked
						node = ((BPlusInternalTreeNode<M>) node).getChildren()[0];
					}
					//noinspection unchecked
					return (BPlusLeafTreeNode<M, N>) node;
				}
				level--;
			}
			return null;
		}
	}

	/**
	 * Iterator that traverses the B+ Tree from right to left.
	 */
	private static class AbstractReverseTreeIterator<M extends Comparable<M>, N, S> implements Iterator<S> {
		/**
		 * Array of arrays representing siblings on each level of the path. Visible to subclasses so the hot
		 * {@link EntryCursor} can resolve the preceding leaf for software prefetching.
		 */
		@Nonnull protected final BPlusTreeNode<?>[][] path;
		/**
		 * The index of the current key on particular path. Visible to subclasses for prefetch lookahead.
		 */
		@Nonnull protected final int[] pathIndex;
		/**
		 * Function allowing to extract the iterator output from the current index and the cached leaf key/value arrays.
		 */
		@Nonnull private final IntObjTriFunction<M[], N[], S> outputExtractor;
		/**
		 * The index of the current key within the current leaf node. Visible to subclasses so the hot
		 * {@link EntryCursor} can read it without an accessor call.
		 */
		protected int currentIndex;
		/**
		 * Flag indicating whether there are more elements to traverse.
		 */
		protected boolean hasNext;
		/**
		 * The current leaf's key array, value array and last occupied index, resolved through the transactional layer
		 * exactly once per leaf when the iterator enters it (see {@link #loadCurrentLeaf()}). Caching them here turns
		 * the hot per-element path into plain array indexing and removes the per-element `ThreadLocal` lookup that
		 * {@link BPlusLeafTreeNode#getKeys()} / {@link BPlusLeafTreeNode#getValues()} / {@link BPlusLeafTreeNode#getPeek()}
		 * would otherwise repeat on every step. Visible to subclasses so the hot {@link EntryCursor} can index them
		 * directly.
		 */
		protected M[] leafKeys;
		protected N[] leafValues;
		protected int leafPeek;

		/**
		 * Initializes the reverse iterator starting from the rightmost position of the cursor.
		 *
		 * @param cursor          the cursor providing the traversal path through the B+ tree
		 * @param outputExtractor function to extract the output value from the current index and leaf node
		 */
		public AbstractReverseTreeIterator(
			@Nonnull Cursor cursor, @Nonnull IntObjTriFunction<M[], N[], S> outputExtractor) {
			final List<CursorLevel> cursorPath = cursor.path();
			this.path = new BPlusTreeNode[cursorPath.size()][];
			this.pathIndex = new int[this.path.length];
			for (int i = 0; i < cursorPath.size(); i++) {
				final CursorLevel cursorLevel = cursorPath.get(i);
				this.path[i] = cursorLevel.siblings();
				this.pathIndex[i] = cursorLevel.index();
			}
			// resolve the rightmost leaf's arrays once; all subsequent per-element access reads the cached arrays
			loadCurrentLeaf();
			this.currentIndex = this.leafPeek;
			this.hasNext = this.currentIndex >= 0;
			this.outputExtractor = outputExtractor;
		}

		/**
		 * Initializes the reverse iterator starting from the specified key or the first key lesser than
		 * or equal to it.
		 *
		 * @param cursor          the cursor providing the traversal path through the B+ tree
		 * @param key             the key to start the iteration from
		 * @param outputExtractor function to extract the output value from the current index and leaf node
		 */
		public AbstractReverseTreeIterator(
			@Nonnull Cursor cursor, @Nonnull M key,
			@Nonnull IntObjTriFunction<M[], N[], S> outputExtractor
		) {
			final List<CursorLevel> cursorPath = cursor.path();
			this.path = new BPlusTreeNode[cursorPath.size()][];
			this.pathIndex = new int[this.path.length];
			for (int i = 0; i < cursorPath.size(); i++) {
				final CursorLevel cursorLevel = cursorPath.get(i);
				this.path[i] = cursorLevel.siblings();
				this.pathIndex[i] = cursorLevel.index();
			}
			final BPlusLeafTreeNode<M, N> startLeaf = cursor.leafNode();
			final InsertionPosition insertionPosition = startLeaf.findKeyPosition(
				key, startLeaf.getKeys(), 0, startLeaf.size()
			);
			// resolve the start leaf's arrays once; the per-element hot path then reads only the cached arrays
			loadCurrentLeaf();
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
			// the leaf arrays are cached, so the hot path is plain array indexing - no path walk, no ThreadLocal hit
			final int index = this.currentIndex;
			final S key = this.outputExtractor.apply(index, this.leafKeys, this.leafValues);
			if (index > 0) {
				// easy path, there is another key in current leaf
				this.currentIndex = index - 1;
			} else {
				// we need to traverse up the path to find the previous sibling (refreshes the cached arrays)
				calculateNextValue();
			}
			return key;
		}

		/**
		 * Resolves the current leaf (the deepest node on the path) through the transactional layer exactly once and
		 * caches its key array, value array and last occupied index. All per-element access in {@link #next()} then
		 * reads these cached fields, so the costly transactional-layer {@code ThreadLocal} lookup is paid once per
		 * leaf instead of three times per element.
		 */
		private void loadCurrentLeaf() {
			//noinspection unchecked
			final BPlusLeafTreeNode<M, N> leaf =
				(BPlusLeafTreeNode<M, N>) this.path[this.path.length - 1][this.pathIndex[this.pathIndex.length - 1]];
			this.leafKeys = leaf.getKeys();
			this.leafValues = leaf.getValues();
			this.leafPeek = leaf.getPeek();
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
		protected final void calculateNextValue() {
			boolean found = false;
			int level = this.pathIndex.length - 1;
			BPlusTreeNode<?>[] parentLevel = this.path[level];
			while (parentLevel != null) {
				// if there is a previous sibling at this level
				if (this.pathIndex[level] > 0) {
					// move to the previous sibling
					this.pathIndex[level] = this.pathIndex[level] - 1;
					BPlusTreeNode<?> currentNode = this.path[level][this.pathIndex[level]];
					// all levels below will point to the last child of the new cursor level
					for (int i = level + 1; i <= this.pathIndex.length - 1; i++) {
						Assert.isPremiseValid(currentNode instanceof BPlusInternalTreeNode, "Internal node expected!");
						//noinspection unchecked
						this.path[i] = ((BPlusInternalTreeNode<M>) currentNode).getChildren();
						this.pathIndex[i] = currentNode.getPeek();
						currentNode = this.path[i][this.pathIndex[i]];
					}
					this.hasNext = true;
					// refresh the cached key/value arrays + peek for the newly entered leaf, then position at its end
					loadCurrentLeaf();
					this.currentIndex = this.leafPeek;
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

		/**
		 * Resolves the leaf immediately to the left of the current one **without mutating** the cursor's path -
		 * a read-only twin of {@link #calculateNextValue()} used purely to obtain the previous leaf reference for
		 * software prefetching. Returns `null` when the current leaf is the leftmost one.
		 *
		 * The cursor state is left untouched, so this is safe to call mid-iteration; only the returned node (and the
		 * internal nodes walked to reach it) are read.
		 *
		 * @return the previous leaf to the left, or `null` if none
		 */
		@Nullable
		protected final BPlusLeafTreeNode<M, N> peekPrevLeaf() {
			int level = this.pathIndex.length - 1;
			while (level > 0) {
				if (this.pathIndex[level] > 0) {
					// the previous sibling at this level exists - descend to its rightmost leaf without touching the path
					BPlusTreeNode<?> node = this.path[level][this.pathIndex[level] - 1];
					for (int i = level + 1; i <= this.path.length - 1; i++) {
						if (!(node instanceof BPlusInternalTreeNode)) {
							return null;
						}
						//noinspection unchecked
						final BPlusInternalTreeNode<M> internal = (BPlusInternalTreeNode<M>) node;
						node = internal.getChildren()[internal.getPeek()];
					}
					//noinspection unchecked
					return (BPlusLeafTreeNode<M, N>) node;
				}
				level--;
			}
			return null;
		}
	}

	/**
	 * Iterator that traverses the B+ Tree from left to right.
	 */
	private static class ForwardTreeKeyIterator<M extends Comparable<M>, N>
		extends AbstractForwardTreeIterator<M, N, M> {

		/**
		 * Creates a forward key iterator starting from the leftmost key.
		 */
		public ForwardTreeKeyIterator(@Nonnull Cursor cursor) {
			super(cursor, (index, keys, values) -> keys[index]);
		}

		/**
		 * Creates a forward key iterator starting from the specified key or the first key greater than it.
		 */
		public ForwardTreeKeyIterator(@Nonnull Cursor cursor, @Nonnull M key) {
			super(cursor, key, (index, keys, values) -> keys[index]);
		}
	}

	/**
	 * Iterator that traverses the B+ Tree from right to left.
	 */
	private static class ReverseTreeKeyIterator<M extends Comparable<M>, N>
		extends AbstractReverseTreeIterator<M, N, M> {

		/**
		 * Creates a reverse key iterator starting from the rightmost key.
		 */
		public ReverseTreeKeyIterator(@Nonnull Cursor cursor) {
			super(cursor, (index, keys, values) -> keys[index]);
		}

		/**
		 * Creates a reverse key iterator starting from the specified key or the first key lesser than or equal to it.
		 */
		public ReverseTreeKeyIterator(@Nonnull Cursor cursor, @Nonnull M key) {
			super(cursor, key, (index, keys, values) -> keys[index]);
		}

	}

	/**
	 * Iterator that traverses the B+ Tree from left to right.
	 */
	static class ForwardTreeValueIterator<M extends Comparable<M>, N> extends AbstractForwardTreeIterator<M, N, N> {

		/**
		 * Creates a forward value iterator starting from the leftmost value.
		 */
		public ForwardTreeValueIterator(@Nonnull Cursor cursor) {
			super(cursor, (index, keys, values) -> values[index]);
		}

		/**
		 * Creates a forward value iterator starting from the specified key or the first key greater than it.
		 */
		public ForwardTreeValueIterator(@Nonnull Cursor cursor, @Nonnull M key) {
			super(cursor, key, (index, keys, values) -> values[index]);
		}
	}

	/**
	 * Iterator that traverses the B+ Tree from right to left.
	 */
	static class ReverseTreeValueIterator<M extends Comparable<M>, N> extends AbstractReverseTreeIterator<M, N, N> {

		/**
		 * Creates a reverse value iterator starting from the rightmost value.
		 */
		public ReverseTreeValueIterator(@Nonnull Cursor cursor) {
			super(cursor, (index, keys, values) -> values[index]);
		}

		/**
		 * Creates a reverse value iterator starting from the specified key or the first key lesser than or equal to it.
		 */
		public ReverseTreeValueIterator(@Nonnull Cursor cursor, @Nonnull M key) {
			super(cursor, key, (index, keys, values) -> values[index]);
		}

	}

	/**
	 * Iterator that traverses the B+ Tree from left to right and provides access to entries (both keys and values).
	 */
	static class ForwardTreeEntryIterator<M extends Comparable<M>, N>
		extends AbstractForwardTreeIterator<M, N, Entry<M, N>> {

		/**
		 * Creates a forward entry iterator starting from the leftmost entry.
		 */
		public ForwardTreeEntryIterator(@Nonnull Cursor cursor) {
			super(cursor, (index, keys, values) -> new Entry<>(keys[index], values[index]));
		}

		/**
		 * Creates a forward entry iterator starting from the specified key or the first key greater than it.
		 */
		public ForwardTreeEntryIterator(@Nonnull Cursor cursor, @Nonnull M key) {
			super(
				cursor, key, (index, keys, values) -> new Entry<>(keys[index], values[index]));
		}
	}

	/**
	 * Iterator that traverses the B+ Tree from right to left and provides access to entries (both keys and values).
	 */
	static class ReverseTreeEntryIterator<M extends Comparable<M>, N>
		extends AbstractReverseTreeIterator<M, N, Entry<M, N>> {

		/**
		 * Creates a reverse entry iterator starting from the specified key or the first key lesser than or equal to it.
		 */
		public ReverseTreeEntryIterator(@Nonnull Cursor cursor, @Nonnull M key) {
			super(
				cursor, key, (index, keys, values) -> new Entry<>(keys[index], values[index]));
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

	/**
	 * Allocation-free cursor over key-value pairs. Unlike {@link #entryIterator()} (which materialises an
	 * {@link Entry} object per step), the cursor returns the key from {@link #next()} and exposes the paired value
	 * through {@link #value()}, reading both directly from the backing leaf. Use it on hot full-scan paths where the
	 * per-entry allocation would otherwise dominate.
	 *
	 * @param <K> the key type
	 * @param <V> the value type
	 */
	public interface EntryCursor<K, V> extends Iterator<K> {

		/**
		 * Returns the value paired with the key most recently returned by {@link #next()}. Must be called only after
		 * at least one {@link #next()} call.
		 *
		 * @return the value paired with the last returned key
		 */
		@Nonnull
		V value();

	}

	/**
	 * Forward {@link EntryCursor} implementation: reuses the forward key traversal (so {@link #next()} returns the key
	 * with no allocation) and reads the paired value from the consumed leaf position.
	 */
	private static final class ForwardTreeEntryCursor<M extends Comparable<M>, N>
		extends AbstractForwardTreeIterator<M, N, M> implements EntryCursor<M, N> {

		/**
		 * The value array and index of the entry the most recent {@link #next()} returned, captured before the cursor
		 * advances so {@link #value()} stays correct even after {@link #next()} crossed into the following leaf.
		 */
		private N[] consumedValues;
		private int consumedIndex;
		/**
		 * Absorbs the software-prefetch touch reads of the upcoming leaf (see {@link #prefetchNextLeaf()}). A live
		 * non-final field the touches feed into stops the JIT from eliminating them as dead loads.
		 */
		private long prefetchSink;

		public ForwardTreeEntryCursor(@Nonnull Cursor cursor) {
			super(cursor, (index, keys, values) -> keys[index]);
			// warm the second leaf while the caller is about to scan the first
			if (this.hasNext) {
				prefetchNextLeaf();
			}
		}

		/**
		 * Direct, allocation- and indirection-free forward step over the cached leaf arrays. Overriding
		 * {@link AbstractForwardTreeIterator#next()} reads the key straight from {@link #leafKeys} and bypasses the
		 * shared output-extractor indirection, keeping the per-element work to plain array indexing on the hot sort
		 * path. (The remaining cost of an ordered sweep is dominated by cross-leaf cache misses, not this dispatch -
		 * see {@link #prefetchNextLeaf()}.)
		 */
		@Nonnull
		@Override
		public M next() {
			if (!this.hasNext) {
				throw new NoSuchElementException("No more elements available");
			}
			final int index = this.currentIndex;
			final M key = this.leafKeys[index];
			// remember the value array + index of the entry just returned so value() stays correct across leaf hops
			this.consumedValues = this.leafValues;
			this.consumedIndex = index;
			if (index < this.leafPeek) {
				this.currentIndex = index + 1;
			} else {
				this.hasNext = moveToNextLeaf();
				if (this.hasNext) {
					// we just stepped into a new leaf; warm the following one so its cold first-touch miss overlaps
					// the upcoming scan of the leaf we just entered (the misses are otherwise serialized at every hop)
					prefetchNextLeaf();
				}
			}
			return key;
		}

		/**
		 * Issues a software prefetch of the leaf following the current one: it resolves the next leaf without moving
		 * the cursor and touches one reference per cache line of its key and value arrays, pulling those lines toward
		 * the CPU while the current leaf is still being scanned. This converts the otherwise serialized cross-leaf
		 * cache miss into overlap (memory-level parallelism), which is the dominant cost of an ordered tree scan.
		 */
		private void prefetchNextLeaf() {
			final BPlusLeafTreeNode<M, N> next = peekNextLeaf();
			if (next != null) {
				final M[] nextKeys = next.getKeys();
				final N[] nextValues = next.getValues();
				long sink = this.prefetchSink;
				// one touch per ~64-byte cache line (8 compressed-oop references per line)
				for (int i = 0; i < nextKeys.length; i += 8) {
					if (nextKeys[i] != null) {
						sink++;
					}
					if (nextValues[i] != null) {
						sink++;
					}
				}
				this.prefetchSink = sink;
			}
		}

		@Nonnull
		@Override
		public N value() {
			return this.consumedValues[this.consumedIndex];
		}
	}

	/**
	 * Reverse {@link EntryCursor} implementation: the right-to-left counterpart of {@link ForwardTreeEntryCursor}.
	 */
	private static final class ReverseTreeEntryCursor<M extends Comparable<M>, N>
		extends AbstractReverseTreeIterator<M, N, M> implements EntryCursor<M, N> {

		/**
		 * The value array and index of the entry the most recent {@link #next()} returned, captured before the cursor
		 * advances so {@link #value()} stays correct even after {@link #next()} crossed into the preceding leaf.
		 */
		private N[] consumedValues;
		private int consumedIndex;
		/**
		 * Absorbs the software-prefetch touch reads of the preceding leaf (see {@link #prefetchPrevLeaf()}). A live
		 * non-final field the touches feed into stops the JIT from eliminating them as dead loads.
		 */
		private long prefetchSink;

		public ReverseTreeEntryCursor(@Nonnull Cursor cursor) {
			super(cursor, (index, keys, values) -> keys[index]);
			// warm the second-to-last leaf while the caller is about to scan the last
			if (this.hasNext) {
				prefetchPrevLeaf();
			}
		}

		/**
		 * Direct, allocation- and indirection-free reverse step over the cached leaf arrays - the right-to-left
		 * counterpart of {@link ForwardTreeEntryCursor#next()}; see that method for why the override matters for
		 * sort-loop inlining.
		 */
		@Nonnull
		@Override
		public M next() {
			if (!this.hasNext) {
				throw new NoSuchElementException("No more elements available");
			}
			final int index = this.currentIndex;
			final M key = this.leafKeys[index];
			// remember the value array + index of the entry just returned so value() stays correct across leaf hops
			this.consumedValues = this.leafValues;
			this.consumedIndex = index;
			if (index > 0) {
				this.currentIndex = index - 1;
			} else {
				calculateNextValue();
				if (this.hasNext) {
					// we just stepped into the preceding leaf; warm the one before it so its cold first-touch miss
					// overlaps the upcoming reverse scan of the leaf we just entered
					prefetchPrevLeaf();
				}
			}
			return key;
		}

		/**
		 * Issues a software prefetch of the leaf preceding the current one: the right-to-left counterpart of
		 * {@link ForwardTreeEntryCursor#prefetchNextLeaf()}; see that method for the rationale.
		 */
		private void prefetchPrevLeaf() {
			final BPlusLeafTreeNode<M, N> prev = peekPrevLeaf();
			if (prev != null) {
				final M[] prevKeys = prev.getKeys();
				final N[] prevValues = prev.getValues();
				long sink = this.prefetchSink;
				// one touch per ~64-byte cache line (8 compressed-oop references per line)
				for (int i = 0; i < prevKeys.length; i += 8) {
					if (prevKeys[i] != null) {
						sink++;
					}
					if (prevValues[i] != null) {
						sink++;
					}
				}
				this.prefetchSink = sink;
			}
		}

		@Nonnull
		@Override
		public N value() {
			return this.consumedValues[this.consumedIndex];
		}
	}

}
