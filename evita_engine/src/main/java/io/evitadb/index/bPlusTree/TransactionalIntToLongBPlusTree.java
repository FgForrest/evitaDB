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
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Iterator;
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
public class TransactionalIntToLongBPlusTree extends AbstractIntKeyedBPlusTree implements
	TransactionalLayerProducer<Void, TransactionalIntToLongBPlusTree>,
	Serializable {
	@Serial private static final long serialVersionUID = 124088192205606248L;
	private static final int DEFAULT_VALUE_BLOCK_SIZE = 64;
	private static final int DEFAULT_MIN_VALUE_BLOCK_SIZE = DEFAULT_VALUE_BLOCK_SIZE / 2 - 1;
	private static final int DEFAULT_INTERNAL_NODE_BLOCK_SIZE = DEFAULT_VALUE_BLOCK_SIZE / 2 - 1;
	private static final int DEFAULT_MIN_INTERNAL_NODE_BLOCK_SIZE =
		(int) (Math.ceil((float) DEFAULT_INTERNAL_NODE_BLOCK_SIZE / 2.0) - 1);

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

	@Nonnull
	@Override
	protected BPlusInternalTreeNode createInternalNode(
		int blockSize,
		int key,
		@Nonnull BPlusTreeNode<?> leftLeaf,
		@Nonnull BPlusTreeNode<?> rightLeaf,
		boolean transactionalLayer
	) {
		return new BPlusInternalTreeNode(blockSize, key, leftLeaf, rightLeaf, transactionalLayer);
	}

	@Nonnull
	@Override
	protected BPlusInternalTreeNode createInternalNode(
		@Nonnull int[] originKeys,
		@Nonnull BPlusTreeNode<?>[] originChildren,
		int keyStart, int keyEnd,
		int childrenStart, int childrenEnd,
		boolean transactionalLayer
	) {
		return new BPlusInternalTreeNode(
			originKeys, originChildren, keyStart, keyEnd, childrenStart, childrenEnd, transactionalLayer
		);
	}

	@Override
	protected boolean splitNodesJoinTransactionalLayer() {
		// the int→long tree's nodes are Snapshotable and the tree is never bulk-rebuilt by re-inserting outside an
		// active transaction, so split offspring always join the diff layer for per-entity savepoint rollback
		return true;
	}

	/**
	 * Inserts a key-value pair into the B+ tree. If the corresponding leaf node
	 * overflows, it is split to maintain the properties of the tree.
	 *
	 * @param key   the key to be inserted into the B+ tree
	 * @param value the value associated with the key
	 */
	public void insert(int key, long value) {
		// The cursor path exists ONLY to cascade a split upward, but it was allocated on EVERY insert - an
		// ArrayList, its backing array, a one-element root sibling array, a CursorLevel per level and the Cursor
		// itself (2.25 GB / 60 s of bulk ingest across this family). `findLeafNode` reaches the same leaf by the
		// identical `searchIndex` descent without capturing anything, so the path is now captured only when this
		// insert can actually overflow the leaf - roughly one insert in `valueBlockSize`.
		final BPlusLeafTreeNode leaf = (BPlusLeafTreeNode) findLeafNode(key);
		// Capture BEFORE mutating: the split machinery replaces this leaf in its parent, so the path has to reflect
		// the pre-mutation tree. `isNearlyFull` reads `peek` and the capacity from the same resolved state that
		// `isFull` does, so it can never fail to fire for an insert that will split. It is not exact in the other
		// direction: a same-key overwrite one slot from full leaves `peek` unchanged, so the cursor is captured and
		// discarded. That waste is bounded and rare. (In `upsert` the guard sits inside the key-absent branch, so
		// the overwrite case cannot reach it and the guard IS exact there.)
		final Cursor cursor = leaf.isNearlyFull() ? createCursor(key) : null;
		if (leaf.insert(key, value)) {
			this.size.set(size() + 1);
		}

		// Split the leaf node if it exceeds the block size
		if (leaf.isFull()) {
			if (cursor == null) {
				throw new GenericEvitaInternalError(
					"Leaf is full but no cursor path was captured - `isNearlyFull` failed to predict `isFull` " +
						"(peek: " + leaf.getPeek() + ", capacity: " + leaf.getKeys().length +
						", tree block size: " + this.valueBlockSize + ")!",
					"Leaf is full but no cursor path was captured!"
				);
			}
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
		// see `insert` - the cursor path is captured lazily and pre-mutation. The update branch below replaces a
		// value in place and can never overflow the leaf, so it needs no path at all.
		final BPlusLeafTreeNode leaf = (BPlusLeafTreeNode) findLeafNode(key);

		final int existingIndex = leaf.getValueIndex(key);
		if (existingIndex >= 0) {
			// update the value on specified index
			leaf.decoupleTransactionalArrays();
			final long[] values = leaf.getValues();
			final long previousValue = values[existingIndex];
			values[existingIndex] = updater.applyAsLong(previousValue);
		} else {
			final Cursor cursor = leaf.isNearlyFull() ? createCursor(key) : null;
			// insert the new value
			if (leaf.insert(key, updater.applyAsLong(0L))) {
				this.size.set(size() + 1);
			}

			// Split the leaf node if it exceeds the block size
			if (leaf.isFull()) {
				if (cursor == null) {
					throw new GenericEvitaInternalError(
						"Leaf is full but no cursor path was captured - `isNearlyFull` failed to predict `isFull` " +
							"(peek: " + leaf.getPeek() + ", capacity: " + leaf.getKeys().length +
							", tree block size: " + this.valueBlockSize + ")!",
						"Leaf is full but no cursor path was captured!"
					);
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
	public void delete(int key) {
		final Cursor cursor = createCursor(key);
		final BPlusLeafTreeNode leaf = cursor.leafNode();

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
		return cursor.<BPlusLeafTreeNode>leafNode().getValue(key);
	}

	/**
	 * Allocation-free variant of {@link #search(int)}: returns the primitive value associated with the given key, or
	 * the caller-supplied `missing` sentinel when the key is absent. Avoids the per-call {@link OptionalLong} wrapper
	 * that {@link #search(int)} allocates, which matters on hot lookup paths (e.g. resolving a record's sort position).
	 * The caller must choose a `missing` value that can never be a legitimate stored value.
	 *
	 * @param key     the key to search for within the B+ tree
	 * @param missing the value to return when the key is not present in the tree
	 * @return the value associated with the key, or `missing` when the key is absent
	 */
	public long searchOrDefault(int key, long missing) {
		// allocation-free leaf descent (no Cursor / CursorLevel path) - this is the query hot lookup path
		return ((BPlusLeafTreeNode) findLeafNode(key)).getValueOrDefault(key, missing);
	}

	/**
	 * Returns an iterator that traverses the B+ tree keys from left to right.
	 *
	 * @return an iterator that traverses the B+ tree keys from left to right
	 */
	@Nonnull
	@Override
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
	@Override
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
		// transactional objects ALIVE - which would otherwise be detected as stale during the commit sweep (INV-5).
		// The current (in-transaction) root MUST be read BEFORE the root reference's own layer is dropped - otherwise
		// getRoot() would fall back to the committed root and the recursion would miss every node created during this
		// transaction (e.g. split offspring), leaking their layers.
		final BPlusTreeNode<?> theRoot = getRoot();
		this.size.removeLayer(transactionalLayer);
		this.root.removeLayer(transactionalLayer);
		removeLayerRecursively(theRoot, transactionalLayer);
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
			// nodes created during a split must participate in the transactional layer so their
			// in-savepoint mutations are captured by the per-entity savepoint and can be rolled back
			true
		);

		// Move the other half into FRESH arrays of the right leaf node — the former leaf's arrays must stay intact so a
		// per-entity savepoint rollback can restore the pre-split leaf; compacting them in place would
		// corrupt that snapshot
		final BPlusLeafTreeNode rightLeaf = new BPlusLeafTreeNode(
			originKeys,
			originValues,
			new int[this.valueBlockSize],
			new long[this.valueBlockSize],
			mid,
			leftLeaf.getKeys().length,
			true
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
					this.internalNodeBlockSize,
					rightLeaf.getKeys()[0],
					leftLeaf, rightLeaf,
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
	 * Per-tree typed marker that exposes the primitive `int` key accessors common to both node kinds, kept off the
	 * key-agnostic {@link BPlusTreeNode} SPI so the shared base never sees (and never boxes) a key. Typed call sites
	 * that hold only a {@link BPlusTreeNode} reference (e.g. a children-array element) cast to this to read the keys.
	 */
	interface IntKeyedNode extends IntBoundaryKeyedNode {

		/**
		 * Retrieves an array of integer keys associated with the node.
		 *
		 * @return an array of integer keys present in the node. The array is guaranteed to be non-null.
		 */
		@Nonnull
		int[] getKeys();

	}

	/**
	 * Internal (routing) node of this {@code int}-keyed tree. All structure-maintenance logic — descent, split,
	 * borrow, merge and the copy-on-write transactional bookkeeping — lives in {@link AbstractIntKeyedInternalNode};
	 * only the constructors and the {@link #createNode} factory are tree-local, because a generic base cannot
	 * {@code new} its own concrete subclass.
	 */
	static class BPlusInternalTreeNode extends AbstractIntKeyedInternalNode<BPlusInternalTreeNode>
		implements IntKeyedNode {
		@Serial private static final long serialVersionUID = -7649742437563558159L;

		/**
		 * Creates a new internal node with a single key separating two child nodes. This constructor is used when
		 * creating a new root after a split operation.
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
			super(blockSize, key, leftLeaf, rightLeaf, transactionalLayer);
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
			super(originKeys, originChildren, keyStart, keyEnd, childrenStart, childrenEnd, transactionalLayer);
		}

		private BPlusInternalTreeNode(
			@Nonnull int[] originKeys,
			@Nonnull BPlusTreeNode<?>[] originChildren,
			int originPeek,
			boolean transactionalLayer
		) {
			super(originKeys, originChildren, originPeek, transactionalLayer);
		}

		@Nonnull
		@Override
		protected BPlusInternalTreeNode createNode(
			@Nonnull int[] keys,
			@Nonnull BPlusTreeNode<?>[] children,
			int peek,
			boolean transactionalLayer
		) {
			return new BPlusInternalTreeNode(keys, children, peek, transactionalLayer);
		}

	}

	/**
	 * Leaf node implementation of the B+ tree that stores key-value pairs. Leaf nodes hold all actual data
	 * in the tree and are the terminal nodes in the B+ tree structure. Values are stored as primitive `long`.
	 */
	static class BPlusLeafTreeNode implements
		BPlusTreeNode<BPlusLeafTreeNode>,
		IntKeyedNode,
		Snapshotable<BPlusLeafTreeNode.BPlusLeafNodeMemento> {
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

		/**
		 * Returns the node holding this leaf's current state — the transactional diff layer if one exists, otherwise
		 * the leaf itself. Allocation-free (returns `this` or the already-allocated layer); the read accessors resolve
		 * the layer once through it instead of repeating the resolve-and-branch prologue.
		 *
		 * @return the node whose `keys` / `values` / `peek` reflect the current (possibly uncommitted) state
		 */
		@Nonnull
		private BPlusLeafTreeNode currentState() {
			final BPlusLeafTreeNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) :
				null;
			return layer == null ? this : layer;
		}

		@Nonnull
		public int[] getKeys() {
			return currentState().keys;
		}

		@Override
		public int getPeek() {
			return currentState().peek;
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
			return currentState().peek + 1;
		}

		@Override
		public boolean isFull() {
			final BPlusLeafTreeNode current = currentState();
			return current.peek == current.values.length - 1;
		}

		/**
		 * Whether a single insert of a **new** key could make this leaf {@link #isFull()}, i.e. whether the caller
		 * must capture a cursor path before mutating so a split has one.
		 *
		 * Deliberately mirrors {@link #isFull()} - it reads `peek` and the capacity from the **same** resolved state,
		 * so the two can never disagree. Comparing against the tree's configured `valueBlockSize` instead would work
		 * only while every leaf array happens to be allocated at exactly that size, and nothing enforces that
		 * coupling; a shorter array would reach `isFull()` without ever tripping the guard.
		 *
		 * Resolves the transactional layer exactly once, so it costs no more than the `getPeek()` call it replaces.
		 *
		 * @return true when one more key could fill this leaf
		 */
		public boolean isNearlyFull() {
			final BPlusLeafTreeNode current = currentState();
			return current.peek >= current.values.length - 2;
		}

		@Override
		public void toVerboseString(@Nonnull StringBuilder sb, int level, int indentSpaces) {
			sb.append(" ".repeat(level * indentSpaces));
			final BPlusLeafTreeNode current = currentState();
			final int[] theKeys = current.keys;
			final long[] theValues = current.values;
			final int thePeek = current.peek;

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
			return currentState().keys[0];
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
			return currentState().values;
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
			final BPlusLeafTreeNode current = currentState();
			final InsertionPosition insertionPosition =
				computeInsertPositionOfIntInOrderedArray(key, current.keys, 0, current.peek + 1);
			return insertionPosition.alreadyPresent() ?
				OptionalLong.of(current.values[insertionPosition.position()]) : OptionalLong.empty();
		}

		/**
		 * Allocation-free variant of {@link #getValue(int)}: returns the value stored for the given key, or the
		 * caller-supplied `missing` sentinel when the key is absent in this leaf. No {@link OptionalLong} is allocated.
		 *
		 * @param key     the key to search for in the leaf node
		 * @param missing the value to return when the key is not present
		 * @return the value associated with the specified key, or `missing` when the key is absent
		 */
		public long getValueOrDefault(int key, long missing) {
			final BPlusLeafTreeNode current = currentState();
			final InsertionPosition insertionPosition =
				computeInsertPositionOfIntInOrderedArray(key, current.keys, 0, current.peek + 1);
			return insertionPosition.alreadyPresent() ? current.values[insertionPosition.position()] : missing;
		}

		/**
		 * Searches for the index of a value in the node's key-value pairs by the specified key.
		 * Returns the index of the key if found, or -1 if the key is not present.
		 *
		 * @param key the key to search for in the leaf node
		 * @return the index of the key in the keys/values arrays if found; -1 otherwise
		 */
		public int getValueIndex(int key) {
			final BPlusLeafTreeNode current = currentState();
			final InsertionPosition insertionPosition = computeInsertPositionOfIntInOrderedArray(
				key, current.keys, 0, current.peek + 1);
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

		/**
		 * Captures this layer's revertable copy-on-write state for a per-entity savepoint. Both the key
		 * and value arrays are cloned (the keys and values are primitive value types) so that a later mutation, or a
		 * repeated {@link #restore}, cannot corrupt the memento.
		 *
		 * @return an independent snapshot of this leaf's two arrays and peek
		 */
		@Nonnull
		@Override
		public BPlusLeafNodeMemento snapshot() {
			return new BPlusLeafNodeMemento(this.keys.clone(), this.values.clone(), this.peek);
		}

		/**
		 * Restores the array state captured by {@link #snapshot}. Fresh clones of the memento's arrays are installed so
		 * the memento stays reusable for a repeated restore.
		 *
		 * @param memento the state previously captured by {@link #snapshot}
		 */
		@Override
		public void restore(@Nonnull BPlusLeafNodeMemento memento) {
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

		/**
		 * Immutable savepoint memento of a leaf node's copy-on-write arrays. The arrays are private clones owned by the
		 * memento (see {@link #snapshot}); the keys and values they hold are primitive value types.
		 *
		 * @param keys   clone of the key array
		 * @param values clone of the value array
		 * @param peek   the last occupied index
		 */
		record BPlusLeafNodeMemento(
			@Nonnull int[] keys,
			@Nonnull long[] values,
			int peek
		) {
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
