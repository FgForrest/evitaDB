/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.SingleRecordBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static io.evitadb.utils.ArrayUtils.*;

/**
 * A transactional, **columnar-leaf** B+ tree that maps a comparable value (the tree key) to a set of record ids. It is
 * the backing store for the inverted index bucket store: instead of storing a per-bucket
 * `ValueToRecord` object in each leaf slot, the leaf decomposes the bucket into parallel columns — the value key, a
 * primitive `int` single-record column, and a sparse, lazily-allocated {@link TransactionalBitmap} overflow column for
 * the few multi-record buckets.
 *
 * **Leaf layout — LAZY-PARALLEL.** All three columns have length `valueBlockSize` and move in lockstep on
 * insert-shift / split / merge / steal:
 *
 * - `K[] keys` — the value, ordered by the {@link #comparator} (natural order when `null`).
 * - `int[] records` — the single record id (pk) when `overflow == null || overflow[i] == null`.
 * - `TransactionalBitmap[] overflow` — **lazy**: `null` until the leaf's first multi bucket, then `overflow[i] != null`
 * marks a multi bucket whose record set is the bitmap.
 *
 * The single/multi discriminator is **always** `overflow == null || overflow[i] == null`, **never** the sign or value
 * of `records[i]`. Externally-assigned primary keys may be any 32-bit int (including `-1` and {@link Integer#MIN_VALUE}),
 * so no int value is reserved as a sentinel; when `overflow[i] != null` the matching `records[i]` is don't-care and is
 * never read.
 *
 * **Promotion / demotion** live inside the leaf mutation (mirroring `InvertedIndex.addRecord/removeRecord`):
 * an absent value inserts a single record; a second distinct record promotes the bucket to a {@link TransactionalBitmap}
 * (allocating the overflow column lazily); removing the last record deletes the bucket. A reduced multi bucket is **not**
 * demoted back to a single (a demoted singleton stays a bitmap). When a multi bucket is deleted, its bitmap's
 * transactional diff layer is explicitly released via {@code discardRemovedValueLayer} so it is not left ALIVE and
 * detected as stale during commit.
 *
 * The tree participates fully in the MVCC framework as a {@link TransactionalLayerProducer}. It depends only on the
 * {@link io.evitadb.index.bitmap} layer and emits a NEUTRAL {@link BucketCursor} so a later task can adapt it to the
 * `ValueToRecord` flyweight surface without this class taking a dependency on `io.evitadb.index.invertedIndex`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NotThreadSafe
public class TransactionalBucketBPlusTree<K extends Comparable<K>> implements
	TransactionalLayerProducer<Void, TransactionalBucketBPlusTree<K>>,
	Serializable,
	ConsistencySensitiveDataStructure
{
	@Serial private static final long serialVersionUID = -2030749900648110509L;
	private static final int DEFAULT_VALUE_BLOCK_SIZE = 64;
	private static final int DEFAULT_MIN_VALUE_BLOCK_SIZE = DEFAULT_VALUE_BLOCK_SIZE / 2 - 1;
	private static final int DEFAULT_INTERNAL_NODE_BLOCK_SIZE = DEFAULT_VALUE_BLOCK_SIZE / 2 - 1;
	private static final int DEFAULT_MIN_INTERNAL_NODE_BLOCK_SIZE =
		(int) (Math.ceil((float) DEFAULT_INTERNAL_NODE_BLOCK_SIZE / 2.0) - 1);
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Maximum number of buckets per leaf node. The number of keys in internal nodes is one less.
	 */
	@Getter private final int valueBlockSize;
	/**
	 * Minimum number of buckets per leaf node. Controls branching factor for leaf nodes.
	 */
	@Getter private final int minValueBlockSize;
	/**
	 * Maximum number of keys per internal node. The number of children in internal nodes is one more.
	 */
	@Getter private final int internalNodeBlockSize;
	/**
	 * Minimum number of keys per internal node. Controls branching factor for internal nodes.
	 */
	@Getter private final int minInternalNodeBlockSize;
	/**
	 * The type of the keys (bucket values) stored in the tree.
	 */
	@Getter private final Class<K> keyType;
	/**
	 * Optional comparator that defines the total order of the keys. When `null`, the keys are ordered by their
	 * natural [Comparable] order. The comparator (when present) is threaded into every node and drives every
	 * key-comparison site so the tree can be ordered by an arbitrary total order (e.g. a locale-aware collator).
	 */
	@Nullable @Getter private final Comparator<K> comparator;
	/**
	 * Factory that creates a fresh empty {@link ValueColumn} of the kind chosen for this tree's key type. It picks a
	 * primitive {@link LongValueColumn} for integral / temporal keys under natural order, and the universal
	 * {@link BoxedObjectColumn} otherwise. Threaded into every empty-leaf creation so the whole tree shares one kind.
	 */
	@Nonnull private final ValueColumnFactory<K> valueColumnFactory;
	/**
	 * Number of buckets in the tree.
	 */
	private final TransactionalReference<Integer> size;
	/**
	 * Root node of the tree.
	 */
	private final TransactionalReference<BPlusTreeNode<K, ?>> root;

	/**
	 * Updates the keys in the parent nodes of a B+ tree based on changes in a specific path. Propagates changes up the
	 * tree as necessary.
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
	 * Verifies that the height of all tree branches is the same and returns the height of the tree.
	 *
	 * @param tree the B+ tree to verify
	 * @return the height of the tree
	 */
	private static int verifyAndReturnHeight(@Nonnull TransactionalBucketBPlusTree<?> tree) {
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
	 * Verifies that all children of the given node have the correct height.
	 *
	 * @param node          the node whose children are being verified, must not be null
	 * @param nodeHeight    the height of the current node
	 * @param maximalHeight the maximal height value that should be matched by leaf nodes
	 */
	private static void verifyHeightOfAllChildren(
		@Nonnull BPlusTreeNode<?, ?> node, int nodeHeight, int maximalHeight) {
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
	 * @param node          the internal node to start height calculation from; must not be null
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
	 *
	 * @param node the B+ tree node to verify; should not be null
	 * @throws IllegalStateException if any inconsistency is detected in the keys
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
				} else if (child instanceof BPlusLeafTreeNode<?> childLeafNode) {
					if (!childLeafNode.keyAt(0).equals(key)) {
						throw new IllegalStateException(
							"Leaf node " + childLeafNode + " has a different key (" + childLeafNode.keyAt(0) + ") " +
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
	 * Verifies that the given node and its child nodes satisfy the minimum required number of values in their blocks.
	 *
	 * @param node                     the node to verify, must not be null
	 * @param minValueBlockSize        the minimum number of values required in a non-root leaf node
	 * @param minInternalNodeBlockSize the minimum number of keys required in an internal node
	 * @param isRoot                   whether the current node is the root of the tree
	 */
	private static void verifyMinimalCountOfValuesInNodes(
		@Nonnull BPlusTreeNode<?, ?> node, int minValueBlockSize, int minInternalNodeBlockSize, boolean isRoot) {
		if (node instanceof BPlusInternalTreeNode<?> internalNode) {
			// the minimum occupancy invariant constrains the number of keys, not children; the root is exempt
			if (!isRoot && internalNode.keyCount() < minInternalNodeBlockSize) {
				throw new IllegalStateException(
					"Internal node " + internalNode + " has less than " + minInternalNodeBlockSize + " keys (" + internalNode.keyCount() + ")!");
			}
			for (int i = 0; i < internalNode.size(); i++) {
				verifyMinimalCountOfValuesInNodes(
					internalNode.getChildren()[i], minValueBlockSize, minInternalNodeBlockSize, false);
			}
		} else {
			if (node.size() < minValueBlockSize && !isRoot) {
				throw new IllegalStateException(
					"Leaf node " + node + " has less than " + minValueBlockSize + " values (" + node.size() + ")!");
			}
		}
	}

	/**
	 * Verifies the forward cursor of the tree: keys must be returned in strictly increasing order and their count must
	 * match the expected size.
	 *
	 * @param tree the tree whose forward cursor is to be verified
	 * @param size the expected number of buckets in the tree
	 * @throws IllegalStateException if the cursor fails to return keys in increasing order or has a wrong count
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void verifyForwardCursor(@Nonnull TransactionalBucketBPlusTree<?> tree, int size) {
		int actualSize = 0;
		Comparable previousKey = null;
		final Comparator comparator = tree.comparator;
		final BucketCursor<?> cursor = tree.cursor();
		while (cursor.next()) {
			final Comparable key = cursor.value();
			final int comparison = comparator == null
				? (previousKey == null ? 0 : key.compareTo(previousKey))
				: (previousKey == null ? 0 : comparator.compare(key, previousKey));
			if (previousKey != null && comparison <= 0) {
				throw new IllegalStateException("Forward cursor returned non-increasing keys!");
			}
			actualSize++;
			previousKey = key;
		}

		if (actualSize != size) {
			throw new IllegalStateException(
				"Forward cursor returned " + actualSize + " keys, but the tree has " + size + " elements!");
		}
	}

	/**
	 * Verifies the reverse cursor of the tree: keys must be returned in strictly decreasing order and their count must
	 * match the expected size.
	 *
	 * @param tree the tree whose reverse cursor is to be verified
	 * @param size the expected number of buckets in the tree
	 * @throws IllegalStateException if the cursor fails to return keys in decreasing order or has a wrong count
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void verifyReverseCursor(@Nonnull TransactionalBucketBPlusTree<?> tree, int size) {
		int actualSize = 0;
		Comparable previousKey = null;
		final Comparator comparator = tree.comparator;
		final BucketCursor<?> cursor = tree.reverseCursor();
		while (cursor.next()) {
			final Comparable key = cursor.value();
			final int comparison = comparator == null
				? (previousKey == null ? 0 : key.compareTo(previousKey))
				: (previousKey == null ? 0 : comparator.compare(key, previousKey));
			if (previousKey != null && comparison >= 0) {
				throw new IllegalStateException("Reverse cursor returned non-decreasing keys!");
			}
			actualSize++;
			previousKey = key;
		}

		if (actualSize != size) {
			throw new IllegalStateException(
				"Reverse cursor returned " + actualSize + " keys, but the tree has " + size + " elements!");
		}
	}

	/**
	 * Recursively traverses the B+ tree to find the leaf node responsible for the specified key, populating the path
	 * traversed with internal nodes.
	 *
	 * @param currentNode the current internal tree node being traversed; must not be null
	 * @param key         the key for which the corresponding leaf node is to be found
	 * @param path        a list to store the sequence of internal nodes visited; must not be null
	 */
	private static <M extends Comparable<M>> void addCursorLevels(
		@Nonnull BPlusInternalTreeNode<M> currentNode,
		@Nonnull M key,
		@Nonnull List<CursorLevel<M>> path
	) {
		final int childIndex = currentNode.searchIndex(key);
		final BPlusTreeNode<M, ?>[] children = currentNode.getChildren();
		path.add(new CursorLevel<>(children, childIndex, currentNode.getPeek()));
		if (children[childIndex] instanceof BPlusInternalTreeNode<?> childInternalNode) {
			//noinspection unchecked
			addCursorLevels((BPlusInternalTreeNode<M>) childInternalNode, key, path);
		}
	}

	/**
	 * Recursively traverses the B+ tree to find the leftmost leaf node, populating the path with internal nodes.
	 *
	 * @param currentNode the current internal tree node being traversed; must not be null
	 * @param path        a list to store the sequence of internal nodes visited; must not be null
	 */
	private static <M extends Comparable<M>> void addLeftmostCursorLevels(
		@Nonnull BPlusInternalTreeNode<M> currentNode,
		@Nonnull List<CursorLevel<M>> path
	) {
		final BPlusTreeNode<M, ?>[] children = currentNode.getChildren();
		path.add(new CursorLevel<>(children, 0, currentNode.getPeek()));
		if (children[0] instanceof BPlusInternalTreeNode<?> childInternalNode) {
			//noinspection unchecked
			addLeftmostCursorLevels((BPlusInternalTreeNode<M>) childInternalNode, path);
		}
	}

	/**
	 * Recursively traverses the B+ tree to find the rightmost leaf node, populating the path with internal nodes.
	 *
	 * @param currentNode the current internal tree node being traversed; must not be null
	 * @param path        a list to store the sequence of internal nodes visited; must not be null
	 */
	private static <M extends Comparable<M>> void addRightmostCursorLevels(
		@Nonnull BPlusInternalTreeNode<M> currentNode,
		@Nonnull List<CursorLevel<M>> path
	) {
		final int currentNodePeek = currentNode.getPeek();
		final BPlusTreeNode<M, ?>[] children = currentNode.getChildren();
		path.add(new CursorLevel<>(children, currentNodePeek, currentNodePeek));
		if (children[currentNodePeek] instanceof BPlusInternalTreeNode<?> childInternalNode) {
			//noinspection unchecked
			addRightmostCursorLevels((BPlusInternalTreeNode<M>) childInternalNode, path);
		}
	}

	/**
	 * Commit-time wrapper for the overflow column: re-wraps a committed bitmap state into a fresh
	 * {@link TransactionalBitmap} (the leaf's overflow column stores {@link TransactionalBitmap}s, but
	 * {@link TransactionalBitmap#createCopyWithMergedTransactionalMemory} yields a plain {@link Bitmap}).
	 *
	 * @param committed the committed bitmap state (never null when invoked)
	 * @return the committed bitmap re-wrapped as a {@link TransactionalBitmap}
	 */
	@Nonnull
	private static TransactionalBitmap wrapOverflow(@Nonnull Object committed) {
		if (committed instanceof final TransactionalBitmap alreadyTransactional) {
			return alreadyTransactional;
		}
		return new TransactionalBitmap((Bitmap) committed);
	}

	/**
	 * Recursively removes the transactional diff layers of the passed node, its descendants and — for leaf nodes —
	 * their producer overflow bitmaps. Walks the current transactional view of the tree.
	 *
	 * @param node               the node whose layer (and that of its subtree) is to be removed
	 * @param transactionalLayer the maintainer that owns the diff layers
	 */
	private static void removeLayerRecursively(
		@Nonnull BPlusTreeNode<?, ?> node,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		if (node instanceof final BPlusInternalTreeNode<?> internalNode) {
			final BPlusTreeNode<?, ?>[] children = internalNode.getChildren();
			final int peek = internalNode.getPeek();
			for (int i = 0; i <= peek; i++) {
				removeLayerRecursively(children[i], transactionalLayer);
			}
		} else if (node instanceof final BPlusLeafTreeNode<?> leafNode) {
			final TransactionalBitmap[] overflow = leafNode.getOverflow();
			if (overflow != null) {
				final int peek = leafNode.getPeek();
				for (int i = 0; i <= peek; i++) {
					// overflow bitmaps guard their own layer removal internally
					if (overflow[i] != null) {
						overflow[i].removeLayer(transactionalLayer);
					}
				}
			}
		} else {
			throw new GenericEvitaInternalError("Unknown node type: " + node);
		}
		if (Transaction.getTransactionalMemoryLayerIfExists(node) != null) {
			node.removeLayer(transactionalLayer);
		}
	}

	/**
	 * Releases the transactional diff layer of an overflow bitmap that is being discarded from a leaf node (a multi
	 * bucket deletion). When the bitmap's layer was opened earlier in the current transaction (e.g. its inner state was
	 * mutated, or it was freshly created and mutated within the same transaction), that layer must be removed
	 * explicitly — otherwise it stays ALIVE after commit and triggers a `StaleTransactionMemoryException` during the
	 * layer sweep. The no-arg `removeLayer()` resolves the current transaction's maintainer and is a safe no-op when no
	 * transaction is open. Must NOT be applied to bitmaps merely moved to a sibling node (steal/merge), as those remain
	 * referenced and their layers must survive.
	 *
	 * @param removed the overflow bitmap removed from the leaf, may be null
	 */
	private static void discardRemovedValueLayer(@Nullable TransactionalBitmap removed) {
		if (removed != null) {
			removed.removeLayer();
		}
	}

	/**
	 * Constructor to initialize the tree with default block sizes and natural key ordering.
	 *
	 * @param keyType the type of the keys (bucket values) stored in the tree
	 */
	public TransactionalBucketBPlusTree(@Nonnull Class<K> keyType) {
		this(
			DEFAULT_VALUE_BLOCK_SIZE,
			DEFAULT_MIN_VALUE_BLOCK_SIZE,
			DEFAULT_INTERNAL_NODE_BLOCK_SIZE,
			DEFAULT_MIN_INTERNAL_NODE_BLOCK_SIZE,
			keyType,
			null
		);
	}

	/**
	 * Constructor to initialize the tree with default block sizes and an optional comparator.
	 *
	 * @param keyType    the type of the keys (bucket values) stored in the tree
	 * @param comparator optional comparator defining the key order; `null` ⇒ natural order
	 */
	public TransactionalBucketBPlusTree(@Nonnull Class<K> keyType, @Nullable Comparator<K> comparator) {
		this(
			DEFAULT_VALUE_BLOCK_SIZE,
			DEFAULT_MIN_VALUE_BLOCK_SIZE,
			DEFAULT_INTERNAL_NODE_BLOCK_SIZE,
			DEFAULT_MIN_INTERNAL_NODE_BLOCK_SIZE,
			keyType,
			comparator
		);
	}

	/**
	 * Constructor to initialize the tree with a single block size used for both leaf and internal nodes.
	 *
	 * @param valueBlockSize maximum number of buckets in a leaf node
	 * @param keyType        the type of the keys (bucket values) stored in the tree
	 */
	public TransactionalBucketBPlusTree(int valueBlockSize, @Nonnull Class<K> keyType) {
		this(valueBlockSize, keyType, null);
	}

	/**
	 * Constructor to initialize the tree with a single block size and an optional comparator.
	 *
	 * @param valueBlockSize maximum number of buckets in a leaf node
	 * @param keyType        the type of the keys (bucket values) stored in the tree
	 * @param comparator     optional comparator defining the key order; `null` ⇒ natural order
	 */
	public TransactionalBucketBPlusTree(
		int valueBlockSize,
		@Nonnull Class<K> keyType,
		@Nullable Comparator<K> comparator
	) {
		this(
			valueBlockSize, valueBlockSize / 2,
			valueBlockSize, valueBlockSize / 2,
			keyType,
			comparator
		);
	}

	/**
	 * Constructor to initialize the tree with explicit block sizes. This is the wrapper-aware counterpart of the
	 * object tree constructor; the overflow wrapper for the {@link TransactionalBitmap} column is internal and never a
	 * caller argument. Lets consumers tune the leaf block size for their workload.
	 *
	 * @param valueBlockSize           maximum number of buckets in a leaf node
	 * @param minValueBlockSize        minimum number of buckets in a leaf node
	 * @param internalNodeBlockSize    maximum number of keys in an internal node
	 * @param minInternalNodeBlockSize minimum number of keys in an internal node
	 * @param keyType                  the type of the keys (bucket values) stored in the tree
	 * @param comparator               optional comparator defining the key order; `null` ⇒ natural order
	 */
	public TransactionalBucketBPlusTree(
		int valueBlockSize,
		int minValueBlockSize,
		int internalNodeBlockSize,
		int minInternalNodeBlockSize,
		@Nonnull Class<K> keyType,
		@Nullable Comparator<K> comparator
	) {
		// the boxed factory keeps this (test-facing) constructor 100% behavior-identical to the pre-phase-1 leaf
		this(
			valueBlockSize,
			minValueBlockSize,
			internalNodeBlockSize,
			minInternalNodeBlockSize,
			keyType,
			comparator,
			capacity -> new BoxedObjectColumn<>(keyType, capacity)
		);
	}

	/**
	 * Constructor to initialize the tree with explicit block sizes and an explicit {@link ValueColumnFactory}. This is
	 * the column-aware entry point used by {@link io.evitadb.index.invertedIndex.InvertedIndex} so an integral / temporal
	 * attribute under natural order stores its keys in a primitive {@link LongValueColumn} instead of a boxed array.
	 *
	 * @param valueBlockSize           maximum number of buckets in a leaf node
	 * @param minValueBlockSize        minimum number of buckets in a leaf node
	 * @param internalNodeBlockSize    maximum number of keys in an internal node
	 * @param minInternalNodeBlockSize minimum number of keys in an internal node
	 * @param keyType                  the type of the keys (bucket values) stored in the tree
	 * @param comparator               optional comparator defining the key order; `null` ⇒ natural order
	 * @param valueColumnFactory       the factory choosing the leaf key-column representation
	 */
	public TransactionalBucketBPlusTree(
		int valueBlockSize,
		int minValueBlockSize,
		int internalNodeBlockSize,
		int minInternalNodeBlockSize,
		@Nonnull Class<K> keyType,
		@Nullable Comparator<K> comparator,
		@Nonnull ValueColumnFactory<K> valueColumnFactory
	) {
		this(
			valueBlockSize,
			minValueBlockSize,
			internalNodeBlockSize,
			minInternalNodeBlockSize,
			keyType,
			comparator,
			valueColumnFactory,
			new BPlusLeafTreeNode<>(valueColumnFactory.create(valueBlockSize), comparator, true),
			0
		);
	}

	private TransactionalBucketBPlusTree(
		int valueBlockSize,
		int minValueBlockSize,
		int internalNodeBlockSize,
		int minInternalNodeBlockSize,
		@Nonnull Class<K> keyType,
		@Nullable Comparator<K> comparator,
		@Nonnull ValueColumnFactory<K> valueColumnFactory,
		@Nonnull BPlusTreeNode<K, ?> root,
		int size
	) {
		Assert.isPremiseValid(valueBlockSize >= 3, "Block size must be at least 3.");
		Assert.isPremiseValid(minValueBlockSize >= 1, "Minimum block size must be at least 1.");
		Assert.isPremiseValid(
			minValueBlockSize <= Math.ceil((float) valueBlockSize / 2.0) - 1,
			"Minimum block size must be less than half of the block size, otherwise the tree nodes might be immediately full after merges."
		);
		Assert.isPremiseValid(internalNodeBlockSize >= 3, "Internal node block size must be at least 3.");
		Assert.isPremiseValid(internalNodeBlockSize % 2 == 1, "Internal node block size must be an odd number.");
		Assert.isPremiseValid(minInternalNodeBlockSize >= 1, "Minimum internal node block size must be at least 1.");
		Assert.isPremiseValid(
			minInternalNodeBlockSize <= Math.ceil((float) internalNodeBlockSize / 2.0) - 1,
			"Minimum internal node block size must be less than half of the internal node block size, otherwise the tree nodes might be immediately full after merges."
		);
		Assert.isPremiseValid(
			internalNodeBlockSize <= valueBlockSize,
			"Internal node block size must not exceed the value block size, otherwise internal node merges overflow the node arrays."
		);
		Assert.isPremiseValid(
			!TransactionalLayerProducer.class.isAssignableFrom(keyType),
			"Key type cannot implement TransactionalLayerProducer."
		);
		Assert.isPremiseValid(
			comparator != null || Comparable.class.isAssignableFrom(keyType),
			"Key type must implement Comparable when no comparator is provided."
		);
		this.comparator = comparator;
		this.valueBlockSize = valueBlockSize;
		this.minValueBlockSize = minValueBlockSize;
		this.internalNodeBlockSize = internalNodeBlockSize;
		this.minInternalNodeBlockSize = minInternalNodeBlockSize;
		this.keyType = keyType;
		this.valueColumnFactory = valueColumnFactory;
		this.root = new TransactionalReference<>(root);
		this.size = new TransactionalReference<>(size);
	}

	/**
	 * Retrieves the root node of the B+ tree.
	 *
	 * @return the root node of the B+ tree, guaranteed to be non-null
	 */
	@Nonnull
	public BPlusTreeNode<K, ?> getRoot() {
		return Objects.requireNonNull(this.root.get());
	}

	/**
	 * Sets the root node of the B+ tree to the specified new root node, removing the changes associated with the
	 * previous root before replacing it.
	 *
	 * @param newRoot the new root node to be set for the B+ tree; must not be null
	 */
	public void setRoot(@Nonnull BPlusTreeNode<K, ?> newRoot) {
		final BPlusTreeNode<K, ?> currentRoot = getRoot();
		if (Transaction.getTransactionalMemoryLayerIfExists(currentRoot) != null) {
			currentRoot.removeLayer();
		}
		this.root.set(newRoot);
	}

	/**
	 * Adds a single record id into the bucket with the specified `value`. If no bucket with this value exists, it is
	 * created as a single-record bucket. A single-record bucket promotes to a multi-record bitmap when a second
	 * distinct record id is added; adding the id it already holds is a no-op. A bitmap bucket is mutated in place so its
	 * transactional diff layer is preserved.
	 *
	 * @param value the value identifying the bucket
	 * @param pk    the record id to add (may be any int, including negative ids)
	 */
	public void addRecord(@Nonnull K value, int pk) {
		final Cursor<K> cursor = createCursor(value);
		final BPlusLeafTreeNode<K> leaf = cursor.leafNode();
		if (leaf.addRecord(value, pk)) {
			this.size.set(size() + 1);
		}
		if (leaf.isFull()) {
			splitLeafNode(leaf, cursor);
		}
	}

	/**
	 * Adds multiple record ids into the bucket with the specified `value`. If no bucket with this value exists, it is
	 * created as a single-record bucket (one id) or a multi-record bitmap (otherwise). A single-record bucket stays
	 * single only when the sole id being added is the one it already holds; otherwise it promotes to a bitmap (the
	 * bitmap deduplicates). A bitmap bucket is mutated in place.
	 *
	 * @param value the value identifying the bucket
	 * @param pks   the record ids to add; must be non-empty (may contain negative ids)
	 */
	public void addRecord(@Nonnull K value, @Nonnull int... pks) {
		Assert.isTrue(pks.length > 0, "Record ids must be not null and non-empty!");
		final Cursor<K> cursor = createCursor(value);
		final BPlusLeafTreeNode<K> leaf = cursor.leafNode();
		if (leaf.addRecords(value, pks)) {
			this.size.set(size() + 1);
		}
		if (leaf.isFull()) {
			splitLeafNode(leaf, cursor);
		}
	}

	/**
	 * Removes one or multiple record ids from the bucket with the specified `value`. If no such bucket exists, or it
	 * contains none of the passed ids, nothing happens. A single-record bucket is deleted when its sole id is removed.
	 * A bitmap bucket has the ids removed in place; when it drops to zero records the bucket is deleted (and its
	 * bitmap's transactional layer released). A reduced bitmap is **not** demoted back to a single record.
	 *
	 * @param value the value identifying the bucket
	 * @param pks   the record ids to remove; must be non-empty (may contain negative ids)
	 */
	public void removeRecord(@Nonnull K value, @Nonnull int... pks) {
		Assert.isTrue(pks.length > 0, "Record ids must be not null and non-empty!");
		final Cursor<K> cursor = createCursor(value);
		final BPlusLeafTreeNode<K> leaf = cursor.leafNode();

		final boolean headRemoved = leaf.size() > 1 && value.equals(leaf.keyAt(0));
		if (leaf.removeRecords(value, pks)) {
			this.size.set(size() - 1);
			// the head of the leaf may have been removed, update parent keys accordingly
			if (headRemoved) {
				updateParentKeys(cursor.toCursorWithLevel());
			}
			consolidate(cursor);
		}
	}

	/**
	 * Returns the record set associated with the given value. A single-record bucket returns a lean
	 * {@link SingleRecordBitmap} view; a multi-record bucket returns its {@link TransactionalBitmap}; an absent value
	 * returns {@link EmptyBitmap#INSTANCE}.
	 *
	 * @param value the value to look up (may be null ⇒ empty bitmap)
	 * @return the record set for the value, never null
	 */
	@Nonnull
	public Bitmap getRecordsEqualTo(@Nullable K value) {
		if (value == null) {
			return EmptyBitmap.INSTANCE;
		}
		return createCursor(value).leafNode().getRecords(value);
	}

	/**
	 * Returns the number of records associated with the given value, without materializing a bitmap. Returns 1 for a
	 * single-record bucket, the bitmap size for a multi-record bucket, and 0 when the value is absent.
	 *
	 * @param value the value to look up (may be null ⇒ 0)
	 * @return the cardinality of the bucket
	 */
	public int cardinalityOf(@Nullable K value) {
		if (value == null) {
			return 0;
		}
		return createCursor(value).leafNode().cardinalityOf(value);
	}

	/**
	 * Returns true if there is a bucket associated with the passed value.
	 *
	 * @param value the value to look up (may be null ⇒ false)
	 * @return true if a bucket exists for the value
	 */
	public boolean contains(@Nullable K value) {
		if (value == null) {
			return false;
		}
		return createCursor(value).leafNode().getValueIndex(value) >= 0;
	}

	/**
	 * Returns the number of buckets currently stored in the tree.
	 *
	 * @return the number of buckets
	 */
	public int bucketCount() {
		return size();
	}

	/**
	 * Returns the total number of records held across all buckets (the sum of all bucket cardinalities).
	 *
	 * @return the total record count
	 */
	public int recordCount() {
		int total = 0;
		final BucketCursor<K> cursor = cursor();
		while (cursor.next()) {
			total += cursor.size();
		}
		return total;
	}

	/**
	 * Returns the number of buckets currently stored in the tree (alias of {@link #bucketCount()}).
	 *
	 * @return the number of buckets
	 */
	public int size() {
		return Objects.requireNonNull(this.size.get());
	}

	/**
	 * Returns a forward NEUTRAL cursor over the buckets, ordered ascending by value.
	 *
	 * @return a forward bucket cursor
	 */
	@Nonnull
	public BucketCursor<K> cursor() {
		return new ForwardBucketCursor<>(createLeftmostCursor());
	}

	/**
	 * Returns a forward NEUTRAL cursor over the buckets, ordered ascending by value, starting from the first bucket
	 * whose value is greater than or equal to the passed value (which need not be present).
	 *
	 * @param value the lower-bound value (inclusive)
	 * @return a forward bucket cursor starting at the value
	 */
	@Nonnull
	public BucketCursor<K> cursor(@Nonnull K value) {
		return new ForwardBucketCursor<>(createCursor(value), value);
	}

	/**
	 * Returns a reverse NEUTRAL cursor over the buckets, ordered descending by value.
	 *
	 * @return a reverse bucket cursor
	 */
	@Nonnull
	public BucketCursor<K> reverseCursor() {
		return new ReverseBucketCursor<>(createRightmostCursor());
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
		this.size.removeLayer(transactionalLayer);
		this.root.removeLayer(transactionalLayer);
		removeLayerRecursively(getRoot(), transactionalLayer);
	}

	@Nonnull
	@Override
	public TransactionalBucketBPlusTree<K> createCopyWithMergedTransactionalMemory(
		@Nullable Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		final BPlusTreeNode<K, ?> theRoot = transactionalLayer.getStateCopyWithCommittedChanges(this.root)
			.orElseThrow();
		if (theRoot instanceof BPlusLeafTreeNode<?> leafNode) {
			//noinspection unchecked
			final BPlusLeafTreeNode<K> theLeafNode = (BPlusLeafTreeNode<K>) leafNode;
			return new TransactionalBucketBPlusTree<>(
				this.valueBlockSize, this.minValueBlockSize,
				this.internalNodeBlockSize, this.minInternalNodeBlockSize,
				this.keyType,
				this.comparator,
				this.valueColumnFactory,
				transactionalLayer.getStateCopyWithCommittedChanges(theLeafNode),
				transactionalLayer.getStateCopyWithCommittedChanges(this.size).orElseThrow()
			);
		} else if (theRoot instanceof BPlusInternalTreeNode<?> internalNode) {
			//noinspection unchecked
			return new TransactionalBucketBPlusTree<>(
				this.valueBlockSize, this.minValueBlockSize,
				this.internalNodeBlockSize, this.minInternalNodeBlockSize,
				this.keyType,
				this.comparator,
				this.valueColumnFactory,
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
			final int height = verifyAndReturnHeight(this);
			verifyMinimalCountOfValuesInNodes(theRoot, this.minValueBlockSize, this.minInternalNodeBlockSize, true);
			verifyInternalNodeKeys(theRoot);

			final int theSize = this.size();
			verifyForwardCursor(this, theSize);
			verifyReverseCursor(this, theSize);
			return new ConsistencyReport(
				ConsistencyState.CONSISTENT,
				"B+ tree is consistent with height of " + height + " levels and " + theSize + " elements."
			);
		} catch (IllegalStateException e) {
			return new ConsistencyReport(ConsistencyState.BROKEN, e.getMessage());
		}
	}

	/**
	 * Consolidates the provided B+ tree node to maintain the structural properties of the tree after a deletion,
	 * borrowing keys from siblings or merging nodes and propagating up the tree as needed.
	 *
	 * @param cursor the cursor representing the path from the root to the node to be consolidated
	 */
	private <N extends BPlusTreeNode<K, N>> void consolidate(@Nonnull Cursor<K> cursor) {
		CursorWithLevel<K> cursorWithLevel = cursor.toCursorWithLevel();

		while (cursorWithLevel != null) {
			final N node = cursorWithLevel.currentNode();
			final boolean isInternal = node instanceof BPlusInternalTreeNode;
			final int minBlock = isInternal ? this.minInternalNodeBlockSize : this.minValueBlockSize;
			final int maxBlock = isInternal ? this.internalNodeBlockSize : this.valueBlockSize;
			final boolean underFlowNode = node.keyCount() < minBlock;
			if (underFlowNode) {
				final BPlusInternalTreeNode<K> parent = cursorWithLevel.parent();
				if (parent != null) {
					final boolean nodeIsEmpty = node.size() == 0;
					final CursorWithLevel<K> previousNodeCursor = cursorWithLevel.getCursorForPreviousNode();
					if (previousNodeCursor != null) {
						final N previousNode = previousNodeCursor.currentNode();
						if (previousNode.keyCount() > minBlock) {
							node.stealFromLeft(
								Math.max(1, (previousNode.keyCount() - minBlock) / 2),
								previousNode
							);
							updateParentKeys(cursorWithLevel);
							return;
						}
					}

					final CursorWithLevel<K> nextNodeCursor = cursorWithLevel.getCursorForNextNode();
					if (nextNodeCursor != null) {
						final N nextNode = nextNodeCursor.currentNode();
						if (nextNode.keyCount() > minBlock) {
							node.stealFromRight(
								Math.max(1, (nextNode.keyCount() - minBlock) / 2),
								nextNode
							);
							updateParentKeys(nextNodeCursor);
							if (isInternal || nodeIsEmpty) {
								updateParentKeys(cursorWithLevel);
							}
							return;
						}
					}

					if (previousNodeCursor != null) {
						final N previousNode = previousNodeCursor.currentNode();
						if (previousNode.keyCount() + node.keyCount() < maxBlock) {
							node.mergeWithLeft(previousNode);
							parent.removeChildOnIndex(
								previousNodeCursor.currentNodeIndex(),
								previousNodeCursor.currentNodeIndex()
							);
							if (Transaction.getTransactionalMemoryLayerIfExists(previousNode) != null) {
								previousNode.removeLayer();
							}
							updateParentKeys(
								previousNodeCursor.withReplacedCurrentNode(node)
							);
							cursorWithLevel = cursorWithLevel.toParentLevel();
							continue;
						}
					}

					if (nextNodeCursor != null) {
						final N nextNode = nextNodeCursor.currentNode();
						if (nextNode.keyCount() + node.keyCount() < maxBlock) {
							node.mergeWithRight(nextNode);
							parent.removeChildOnIndex(
								nextNodeCursor.currentNodeIndex() - 1,
								nextNodeCursor.currentNodeIndex()
							);
							if (Transaction.getTransactionalMemoryLayerIfExists(nextNode) != null) {
								nextNode.removeLayer();
							}
							updateParentKeys(
								cursorWithLevel.withReplacedCurrentNode(node)
							);
							cursorWithLevel = cursorWithLevel.toParentLevel();
						}
					}
				} else if (node == this.getRoot()) {
					final BPlusTreeNode<K, ?> theRoot = this.getRoot();
					if (node.size() == 1 && node instanceof BPlusInternalTreeNode<?> internalTreeNode) {
						//noinspection unchecked
						final BPlusTreeNode<K, ?> firstChild = (BPlusTreeNode<K, ?>) internalTreeNode.getChildren()[0];
						if (Transaction.getTransactionalMemoryLayerIfExists(theRoot) != null) {
							theRoot.removeLayer();
						}
						this.root.set(firstChild);
					} else if (node.size() == 0 && node instanceof BPlusInternalTreeNode) {
						if (Transaction.getTransactionalMemoryLayerIfExists(theRoot) != null) {
							theRoot.removeLayer();
						}
						this.root.set(
							new BPlusLeafTreeNode<>(
								this.valueColumnFactory.create(this.valueBlockSize),
								this.comparator,
								true
							)
						);
					}
					cursorWithLevel = null;
				}
			} else {
				cursorWithLevel = null;
			}
		}
	}

	/**
	 * Finds the leftmost leaf node in the B+ tree and returns a cursor to it.
	 *
	 * @return a cursor positioned at the leftmost leaf node
	 */
	@Nonnull
	private Cursor<K> createLeftmostCursor() {
		final ArrayList<CursorLevel<K>> path = new ArrayList<>(this.size() == 0 ? 1 : (int) (Math.log(
			this.size()) + 1));
		final BPlusTreeNode<K, ?> theRoot = this.getRoot();
		//noinspection unchecked
		final BPlusTreeNode<K, ?>[] rootSiblings = (BPlusTreeNode<K, ?>[]) new BPlusTreeNode[]{theRoot};
		path.add(new CursorLevel<>(rootSiblings, 0, 0));
		if (theRoot instanceof BPlusInternalTreeNode<?> rootInternalNode) {
			//noinspection unchecked
			addLeftmostCursorLevels((BPlusInternalTreeNode<K>) rootInternalNode, path);
		}
		return new Cursor<>(path);
	}

	/**
	 * Finds the rightmost leaf node in the B+ tree and returns a cursor to it.
	 *
	 * @return a cursor positioned at the rightmost leaf node
	 */
	@Nonnull
	private Cursor<K> createRightmostCursor() {
		final ArrayList<CursorLevel<K>> path = new ArrayList<>(this.size() == 0 ? 1 : (int) (Math.log(
			this.size()) + 1));
		final BPlusTreeNode<K, ?> theRoot = this.getRoot();
		//noinspection unchecked
		final BPlusTreeNode<K, ?>[] rootSiblings = (BPlusTreeNode<K, ?>[]) new BPlusTreeNode[]{theRoot};
		path.add(new CursorLevel<>(rootSiblings, 0, 0));
		if (theRoot instanceof BPlusInternalTreeNode<?> rootInternalNode) {
			//noinspection unchecked
			addRightmostCursorLevels((BPlusInternalTreeNode<K>) rootInternalNode, path);
		}
		return new Cursor<>(path);
	}

	/**
	 * Finds the leaf node in the B+ tree that should contain the specified key and returns a cursor to it. The leaf may
	 * not actually contain the key, but it is the correct leaf node for accommodating it.
	 *
	 * @param key the key to search for within the B+ tree
	 * @return a cursor to the leaf node responsible for storing the provided key
	 */
	@Nonnull
	private Cursor<K> createCursor(@Nonnull K key) {
		final ArrayList<CursorLevel<K>> path = new ArrayList<>(this.size() == 0 ? 1 : (int) (Math.log(
			this.size()) + 1));
		final BPlusTreeNode<K, ?> theRoot = this.getRoot();
		//noinspection unchecked
		final BPlusTreeNode<K, ?>[] rootSiblings = (BPlusTreeNode<K, ?>[]) new BPlusTreeNode[]{theRoot};
		path.add(new CursorLevel<>(rootSiblings, 0, 0));
		if (theRoot instanceof BPlusInternalTreeNode<?> rootInternalNode) {
			//noinspection unchecked
			addCursorLevels((BPlusInternalTreeNode<K>) rootInternalNode, key, path);
		}
		return new Cursor<>(path);
	}

	/**
	 * Splits a full leaf node into two leaf nodes to maintain the properties of the B+ tree. If the split occurs at the
	 * root, a new root is created.
	 *
	 * @param leaf   the leaf node to be split
	 * @param cursor the cursor representing the path from the root to the leaf node
	 */
	private void splitLeafNode(
		@Nonnull BPlusLeafTreeNode<K> leaf,
		@Nonnull Cursor<K> cursor
	) {
		final int mid = this.valueBlockSize / 2;
		final ValueColumn<K> originKeys = leaf.getKeyColumn();
		final int[] originRecords = leaf.getRecords();
		final TransactionalBitmap[] originOverflow = leaf.getOverflow();

		// Move half the buckets to fresh arrays of the left leaf node
		final BPlusLeafTreeNode<K> leftLeaf = new BPlusLeafTreeNode<>(
			originKeys,
			originRecords,
			originOverflow,
			originKeys.allocate(this.valueBlockSize),
			new int[this.valueBlockSize],
			originOverflow == null ? null : new TransactionalBitmap[this.valueBlockSize],
			0,
			mid,
			this.comparator,
			!Transaction.isTransactionAvailable()
		);

		// Move the other half to the start of the existing arrays of the former leaf in the right leaf node
		final BPlusLeafTreeNode<K> rightLeaf = new BPlusLeafTreeNode<>(
			originKeys,
			originRecords,
			originOverflow,
			originKeys,
			originRecords,
			originOverflow,
			mid,
			leftLeaf.getKeyColumn().capacity(),
			this.comparator,
			!Transaction.isTransactionAvailable()
		);

		if (Transaction.getTransactionalMemoryLayerIfExists(leaf) != null) {
			leaf.removeLayer();
		}

		if (leaf == this.getRoot()) {
			this.setRoot(
				new BPlusInternalTreeNode<>(
					this.valueBlockSize,
					rightLeaf.keyAt(0),
					leftLeaf, rightLeaf,
					this.keyType,
					this.comparator,
					!Transaction.isTransactionAvailable()
				)
			);
		} else {
			replaceNodeInParentInternalNode(
				leaf,
				leftLeaf,
				rightLeaf,
				rightLeaf.keyAt(0),
				cursor.toCursorWithLevel()
			);
		}
	}

	/**
	 * Replaces a node in its parent with two new nodes as part of the B+ tree splitting process.
	 *
	 * @param original the original node that is being replaced
	 * @param left     the left child resulting from the split
	 * @param right    the right child resulting from the split
	 * @param key      the partition key separating the left and right nodes
	 * @param cursor   the cursor representing the path from the root to the original node
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
	 * Splits a full internal node into two separate nodes to maintain the properties of the B+ tree. If the node being
	 * split is the root, a new root is created.
	 *
	 * @param internal the internal node to be split; must not be null
	 * @param cursor   the cursor representing the path from the root to the internal node being split
	 */
	private void splitInternalNode(
		@Nonnull BPlusInternalTreeNode<K> internal,
		@Nonnull CursorWithLevel<K> cursor
	) {
		final int mid = (this.valueBlockSize + 1) / 2;
		final K[] originKeys = internal.getKeys();
		final BPlusTreeNode<K, ?>[] originChildren = internal.getChildren();

		final BPlusInternalTreeNode<K> leftInternal = new BPlusInternalTreeNode<>(
			originKeys,
			originChildren,
			0,
			mid - 1,
			0,
			mid,
			this.keyType,
			this.comparator,
			!Transaction.isTransactionAvailable()
		);

		final BPlusInternalTreeNode<K> rightInternal = new BPlusInternalTreeNode<>(
			originKeys,
			originChildren,
			mid,
			leftInternal.getKeys().length,
			mid,
			leftInternal.getChildren().length,
			this.keyType,
			this.comparator,
			!Transaction.isTransactionAvailable()
		);

		if (Transaction.getTransactionalMemoryLayerIfExists(internal) != null) {
			internal.removeLayer();
		}

		if (internal == this.getRoot()) {
			this.setRoot(
				new BPlusInternalTreeNode<>(
					this.valueBlockSize,
					rightInternal.getLeftBoundaryKey(),
					leftInternal, rightInternal,
					this.keyType,
					this.comparator,
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
	 * B+ Tree Node interface, implemented by both internal nodes (keys + child pointers) and leaf nodes (the columnar
	 * bucket store).
	 */
	interface BPlusTreeNode<M extends Comparable<M>, N extends BPlusTreeNode<M, N>>
		extends
		TransactionalLayerProducer<N, N>,
		Serializable {

		/**
		 * Retrieves the array of keys associated with the node.
		 *
		 * @return the non-null array of keys present in the node
		 */
		@Nonnull
		M[] getKeys();

		/**
		 * Retrieves the (boxed) key at the given index. Boxing boundary — for the leaf this routes through the columnar
		 * storage; for the internal node it indexes the boxed separator array.
		 *
		 * @param index the slot to read
		 * @return the key at `index`
		 */
		@Nonnull
		M keyAt(int index);

		/**
		 * Retrieves the peek index (last usable position) of the node's values / children.
		 *
		 * @return the peek value of the node
		 */
		int getPeek();

		/**
		 * Sets the peek index of the node, indicating the last usable position in the node's values / children array.
		 *
		 * @param peek the new peek index to set for the node
		 */
		void setPeek(int peek);

		/**
		 * Returns the optional comparator defining the total order of this node's keys, or `null` for natural order.
		 *
		 * @return the key comparator, or `null` for natural ordering
		 */
		@Nullable
		Comparator<M> getComparator();

		/**
		 * Computes the insertion position of the given key within the ordered key range, routing the comparison through
		 * this node's [#getComparator] when present, otherwise through the keys' natural [Comparable] order.
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

		/**
		 * Returns the number of values in this node — i.e. peek + 1.
		 *
		 * @return the number of values in this node
		 */
		default int size() {
			return getPeek() + 1;
		}

		/**
		 * Returns the number of keys in this node — which differs between leaf and internal nodes.
		 *
		 * @return the number of keys in this node
		 */
		int keyCount();

		/**
		 * Checks if the node is full (all available slots occupied).
		 *
		 * @return true if the node is full, false otherwise
		 */
		boolean isFull();

		/**
		 * Appends a verbose string representation of the node to the given builder.
		 *
		 * @param sb           the builder to append to
		 * @param level        the current level of the node in the hierarchy
		 * @param indentSpaces the number of spaces to use for indentation
		 */
		void toVerboseString(@Nonnull StringBuilder sb, int level, int indentSpaces);

		/**
		 * Steals a specified number of values from the end of the left sibling node.
		 *
		 * @param numberOfTailValues the number of values to steal from the left sibling node
		 * @param previousNode       the left sibling node from which to steal values
		 */
		void stealFromLeft(int numberOfTailValues, @Nonnull N previousNode);

		/**
		 * Steals a specified number of values from the start of the right sibling node.
		 *
		 * @param numberOfHeadValues the number of values to steal from the right sibling node
		 * @param nextNode           the right sibling node from which to steal values
		 */
		void stealFromRight(int numberOfHeadValues, @Nonnull N nextNode);

		/**
		 * Merges the current node with the left sibling node.
		 *
		 * @param previousNode the left sibling node to merge into this node
		 */
		void mergeWithLeft(@Nonnull N previousNode);

		/**
		 * Merges the current node with the right sibling node.
		 *
		 * @param nextNode the right sibling node to merge into this node
		 */
		void mergeWithRight(@Nonnull N nextNode);

		/**
		 * Retrieves the left boundary key of the node (the smallest key contained within the leftmost leaf reachable
		 * from this node).
		 *
		 * @return the left boundary key of the node
		 */
		@Nonnull
		M getLeftBoundaryKey();
	}

	/**
	 * NEUTRAL cursor over the buckets of the tree, exposing each bucket without allocating per step. A later task adapts
	 * this into `ValueToRecord` flyweights and `(value, cardinality)` pairs. Advance with {@link #next()}, then read the
	 * current bucket via the accessors. {@link #records()} returns a lean {@link SingleRecordBitmap} for a single bucket
	 * and the {@link TransactionalBitmap} for a multi bucket.
	 *
	 * @param <K> the value (key) type
	 */
	public interface BucketCursor<K extends Comparable<K>> {

		/**
		 * Advances the cursor to the next bucket.
		 *
		 * @return true if a next bucket exists and the cursor now points at it; false when exhausted
		 */
		boolean next();

		/**
		 * Returns the value of the current bucket. Valid only after a {@link #next()} that returned true.
		 *
		 * @return the current bucket's value
		 */
		@Nonnull
		K value();

		/**
		 * Returns whether the current bucket holds exactly one record (single representation).
		 *
		 * @return true if the current bucket is a single-record bucket
		 */
		boolean isSingle();

		/**
		 * Returns the lone record id of the current bucket. Valid only when {@link #isSingle()} is true.
		 *
		 * @return the single record id
		 */
		int singleRecordId();

		/**
		 * Returns the record set of the current bucket: a lean {@link SingleRecordBitmap} for a single bucket, the
		 * {@link TransactionalBitmap} for a multi bucket.
		 *
		 * @return the record set, never null
		 */
		@Nonnull
		Bitmap records();

		/**
		 * Returns the cardinality of the current bucket (1 for single, the bitmap size for multi).
		 *
		 * @return the current bucket's cardinality
		 */
		int size();
	}

	/**
	 * Internal node implementation of the B+ tree, holding keys and child node pointers. Internal nodes serve as routing
	 * nodes — they do not store buckets directly but guide searches to the appropriate leaf nodes. Verbatim copy of the
	 * object tree's internal node (the bucket decomposition only touches the leaf).
	 */
	static class BPlusInternalTreeNode<M extends Comparable<M>> implements BPlusTreeNode<M, BPlusInternalTreeNode<M>> {
		@Serial private static final long serialVersionUID = 3382269323782408764L;
		@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
		/**
		 * Indicates whether this instance is permitted to create and use transactional layers. The tree nodes use
		 * themselves as their transactional memory and if this layer would use transactional memory as well, it would
		 * create an infinite loop. This flag prevents that behavior.
		 */
		private final boolean transactionalLayer;
		/**
		 * Optional comparator defining the total order of the keys. When `null`, keys are ordered by natural order.
		 */
		@Getter @Nullable private final Comparator<M> comparator;
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

		/**
		 * Creates a new internal node with a single key separating two child nodes, used when creating a new root after
		 * a split operation.
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
			@Nonnull BPlusTreeNode<M, ?> leftLeaf,
			@Nonnull BPlusTreeNode<M, ?> rightLeaf,
			@Nonnull Class<M> keyType,
			@Nullable Comparator<M> comparator,
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
			this.comparator = comparator;
			this.transactionalLayer = transactionalLayer;
		}

		/**
		 * Creates a new internal node by copying a range of keys and children from existing arrays, used during node
		 * split operations.
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
			@Nonnull BPlusTreeNode<M, ?>[] originChildren,
			int keyStart, int keyEnd,
			int childrenStart, int childrenEnd,
			@Nonnull Class<M> keyType,
			@Nullable Comparator<M> comparator,
			boolean transactionalLayer
		) {
			//noinspection unchecked
			this.keys = (M[]) Array.newInstance(keyType, originKeys.length);
			//noinspection unchecked
			this.children = new BPlusTreeNode[originChildren.length];
			System.arraycopy(originKeys, keyStart, this.keys, 0, keyEnd - keyStart);
			System.arraycopy(originChildren, childrenStart, this.children, 0, childrenEnd - childrenStart);
			this.peek = childrenEnd - childrenStart - 1;
			this.comparator = comparator;
			this.transactionalLayer = transactionalLayer;
		}

		private BPlusInternalTreeNode(
			@Nonnull M[] originKeys,
			@Nonnull BPlusTreeNode<M, ?>[] originChildren,
			int originPeek,
			@Nullable Comparator<M> comparator,
			boolean transactionalLayer
		) {
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

		@Nonnull
		@Override
		public M keyAt(int index) {
			return getKeys()[index];
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
			final BPlusTreeNode<M, ?>[] theChildren;
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

			final BPlusInternalTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			if (layer == null) {
				System.arraycopy(this.children, 0, this.children, numberOfTailValues, this.peek + 1);
				System.arraycopy(
					previousNode.getChildren(), previousNode.size() - numberOfTailValues, this.children, 0,
					numberOfTailValues
				);
				System.arraycopy(this.keys, 0, this.keys, numberOfTailValues, this.peek);
				this.keys[numberOfTailValues - 1] = this.children[numberOfTailValues].getLeftBoundaryKey();
				System.arraycopy(
					previousNode.getKeys(), previousNode.keyCount() - numberOfTailValues + 1, this.keys, 0,
					numberOfTailValues - 1
				);
				this.peek += numberOfTailValues;
				previousNode.setPeek(previousNode.getPeek() - numberOfTailValues);
			} else {
				decoupleTransactionalArrays();
				previousNode.decoupleTransactionalArrays();
				System.arraycopy(layer.children, 0, layer.children, numberOfTailValues, layer.peek + 1);
				System.arraycopy(
					previousNode.getChildrenForUpdate(), previousNode.size() - numberOfTailValues, layer.children, 0,
					numberOfTailValues
				);
				System.arraycopy(layer.keys, 0, layer.keys, numberOfTailValues, layer.peek);
				layer.keys[numberOfTailValues - 1] = layer.children[numberOfTailValues].getLeftBoundaryKey();
				System.arraycopy(
					previousNode.getKeysForUpdate(), previousNode.keyCount() - numberOfTailValues + 1, layer.keys, 0,
					numberOfTailValues - 1
				);
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
				final BPlusTreeNode<M, ?>[] nextNodeChildren = nextNode.getChildrenForUpdate();
				System.arraycopy(nextNodeChildren, 0, this.children, this.peek + 1, numberOfHeadValues);
				System.arraycopy(
					nextNodeChildren, numberOfHeadValues, nextNodeChildren, 0, nextNode.size() - numberOfHeadValues);

				this.keys[this.peek] = this.children[this.peek + 1].getLeftBoundaryKey();

				final M[] nextNodeKeys = nextNode.getKeysForUpdate();
				System.arraycopy(nextNodeKeys, 0, this.keys, this.peek + 1, numberOfHeadValues - 1);
				System.arraycopy(
					nextNodeKeys, numberOfHeadValues, nextNodeKeys, 0, nextNodeKeys.length - numberOfHeadValues);

				this.peek += numberOfHeadValues;
				nextNode.setPeek(nextNode.getPeek() - numberOfHeadValues);
			} else {
				decoupleTransactionalArrays();
				nextNode.decoupleTransactionalArrays();

				final BPlusTreeNode<M, ?>[] nextNodeChildrenForUpdate = nextNode.getChildrenForUpdate();
				System.arraycopy(nextNodeChildrenForUpdate, 0, layer.children, layer.peek + 1, numberOfHeadValues);
				System.arraycopy(
					nextNodeChildrenForUpdate, numberOfHeadValues, nextNodeChildrenForUpdate, 0,
					nextNode.size() - numberOfHeadValues
				);

				layer.keys[layer.peek] = layer.children[layer.peek + 1].getLeftBoundaryKey();

				final M[] nextNodeKeysForUpdate = nextNode.getKeysForUpdate();
				System.arraycopy(nextNodeKeysForUpdate, 0, layer.keys, layer.peek + 1, numberOfHeadValues - 1);
				System.arraycopy(
					nextNodeKeysForUpdate, numberOfHeadValues, nextNodeKeysForUpdate, 0,
					nextNodeKeysForUpdate.length - numberOfHeadValues
				);

				layer.peek += numberOfHeadValues;
				nextNode.setPeek(nextNode.getPeek() - numberOfHeadValues);
			}
		}

		@Override
		public void mergeWithLeft(@Nonnull BPlusInternalTreeNode<M> previousNode) {
			Assert.isPremiseValid(
				getPeek() >= 0, "Cannot merge into an empty internal node (it has no children)!"
			);
			final int mergePeek = previousNode.getPeek();

			final BPlusInternalTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
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
			Assert.isPremiseValid(
				getPeek() >= 0, "Cannot merge into an empty internal node (it has no children)!"
			);
			final int mergePeek = nextNode.getPeek();

			final BPlusInternalTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			if (layer == null) {
				System.arraycopy(nextNode.getChildren(), 0, this.children, this.peek + 1, mergePeek + 1);
				this.keys[this.peek] = nextNode.getChildren()[0].getLeftBoundaryKey();
				System.arraycopy(nextNode.getKeys(), 0, this.keys, this.peek + 1, mergePeek);
				this.peek += mergePeek + 1;
				nextNode.setPeek(-1);
			} else {
				decoupleTransactionalArrays();
				System.arraycopy(nextNode.getChildrenForUpdate(), 0, layer.children, layer.peek + 1, mergePeek + 1);
				layer.keys[layer.peek] = layer.children[layer.peek + 1].getLeftBoundaryKey();
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
				return this.children[0].getLeftBoundaryKey();
			} else {
				return layer.children[0].getLeftBoundaryKey();
			}
		}

		/**
		 * Retrieves the keys of the current node for updating, decoupling a transactional copy when needed.
		 *
		 * @return the keys array (transaction-local copy when a layer is active)
		 */
		@Nonnull
		public M[] getKeysForUpdate() {
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			if (layer == null) {
				return this.keys;
			} else {
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
		 * Retrieves the children of the current node for READ-ONLY purposes.
		 *
		 * @return the children array
		 */
		@Nonnull
		public BPlusTreeNode<M, ?>[] getChildren() {
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
		 * Retrieves the children of the current node for updating, decoupling a transactional copy when needed.
		 *
		 * @return the children array (transaction-local copy when a layer is active)
		 */
		@Nonnull
		public BPlusTreeNode<M, ?>[] getChildrenForUpdate() {
			final BPlusInternalTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			if (layer == null) {
				return this.children;
			} else {
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
		 * Inserts a new key into the node's keys array and updates its children to reflect a child split.
		 *
		 * @param key      the key to be inserted
		 * @param original the original child node being split
		 * @param left     the left child resulting from the split
		 * @param right    the right child resulting from the split
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

			final BPlusInternalTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			if (layer == null) {
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
		 *
		 * @param key the key to search for
		 * @return the index of the child that should contain the specified key
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
		 * Removes a child node (and its separator key) from the node at the specified indices, shifting subsequent
		 * children left and decrementing the peek.
		 *
		 * @param keyIndex   the position of the key to be removed from the keys array
		 * @param childIndex the position of the child node to be removed from the children array
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

				if (Transaction.getTransactionalMemoryLayerIfExists(layer.children[childIndex]) != null) {
					layer.children[childIndex].removeLayer();
				}

				removeRecordFromSameArrayOnIndex(layer.children, childIndex);
				layer.children[layer.peek] = null;
				layer.peek--;
			}
		}

		/**
		 * Updates the separator key associated with the specified child index to the child's current left boundary key.
		 *
		 * @param index the index in the keys array where the key needs to be updated; must be greater than 0
		 * @param node  the child node whose left boundary key replaces the key at the specified index
		 */
		public void updateKeyForNode(int index, @Nonnull BPlusTreeNode<M, ?> node) {
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
				this.comparator,
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
				// nodes created during splits/merges are built with transactionalLayer=false so they do not allocate
				// STM layers mid-transaction; on commit they must be rebuilt as participating (transactionalLayer=true)
				// nodes so subsequent transactions can layer changes over them
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
		 * Decouples the node's keys and children arrays into a transaction-local copy before mutation.
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
					//noinspection unchecked
					layer.children = new BPlusTreeNode[this.children.length];
					System.arraycopy(this.children, 0, layer.children, 0, this.peek + 1);
				}
			}
		}

	}

	/**
	 * Leaf node implementation: the **columnar bucket store**. Each leaf holds three parallel columns of length
	 * `valueBlockSize` — the value `keys`, the single-record `records` ints, and the lazy `overflow`
	 * {@link TransactionalBitmap}s for multi-record buckets (allocated on the leaf's first promotion). The single/multi
	 * discriminator is `overflow == null || overflow[i] == null`. The leaf encapsulates the promotion/demotion of
	 * buckets and the full MVCC scaffolding (createLayer / decouple / commit-merge / removeLayer / split / merge /
	 * steal) across all three columns.
	 */
	static class BPlusLeafTreeNode<M extends Comparable<M>>
		implements BPlusTreeNode<M, BPlusLeafTreeNode<M>> {
		@Serial private static final long serialVersionUID = 1382269323782408765L;
		@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
		/**
		 * Indicates whether this instance is permitted to create and use transactional layers (see the internal node
		 * for the same flag's purpose).
		 */
		private final boolean transactionalLayer;
		/**
		 * Optional comparator defining the total order of the keys. When `null`, keys are ordered by natural order.
		 */
		@Getter @Nullable private final Comparator<M> comparator;
		/**
		 * The keys (bucket values) stored in this node, behind the pluggable {@link ValueColumn} abstraction so the leaf
		 * can hold them in the cheapest representation for the attribute type (boxed `Object[]` fallback, or a primitive
		 * column for numeric / temporal types).
		 */
		private ValueColumn<M> keys;
		/**
		 * The single-record column. `records[i]` is the lone pk of bucket `i` when `overflow == null || overflow[i] ==
		 * null`; otherwise it is don't-care (never read).
		 */
		private int[] records;
		/**
		 * The lazy multi-record column. `null` until the leaf's first multi bucket; thereafter `overflow[i] != null`
		 * marks a multi bucket whose record set is the {@link TransactionalBitmap}, and is `null` for single buckets.
		 */
		@Nullable private TransactionalBitmap[] overflow;
		/**
		 * Index of the last occupied position in the columns.
		 */
		private int peek;

		/**
		 * Shifts the passed overflow column one slot to the right at `position`, leaving the freed slot null so the
		 * bucket newly inserted at `position` is marked single (it carries no overflow entry). Used in place of the
		 * `@Nonnull` {@link io.evitadb.utils.ArrayUtils#insertRecordIntoSameArrayOnIndex} helper because the value
		 * written into the freed overflow slot is intentionally null.
		 *
		 * @param overflow the non-null overflow column to shift
		 * @param position the position at which the new single bucket is inserted
		 */
		private static void shiftOverflowForSingleInsert(@Nonnull TransactionalBitmap[] overflow, int position) {
			final int tailLength = overflow.length - position - 1;
			System.arraycopy(overflow, position, overflow, position + 1, tailLength);
			overflow[position] = null;
		}

		/**
		 * Copies a range of overflow entries from `src` into `dst` when both are present; a no-op when either is null
		 * (single-only ranges carry no overflow entries). Used by steal/merge to move multi buckets in lockstep with the
		 * key/record columns.
		 *
		 * @param src    the source overflow column (may be null)
		 * @param srcPos the start index in the source
		 * @param dst    the destination overflow column (may be null)
		 * @param dstPos the start index in the destination
		 * @param length the number of entries to copy
		 */
		private static void copyOverflowRange(
			@Nullable TransactionalBitmap[] src, int srcPos,
			@Nullable TransactionalBitmap[] dst, int dstPos, int length
		) {
			if (src != null && dst != null) {
				System.arraycopy(src, srcPos, dst, dstPos, length);
			}
		}

		/**
		 * Creates a new empty leaf node backed by a pre-built key column. The column's capacity defines the leaf block
		 * size, and the column kind (boxed vs. primitive) was chosen by the tree's {@link ValueColumnFactory}.
		 *
		 * @param keys               the empty key column (its capacity is the block size)
		 * @param comparator         optional comparator defining the key order; `null` ⇒ natural order
		 * @param transactionalLayer whether this node participates in the transactional memory layer
		 */
		public BPlusLeafTreeNode(
			@Nonnull ValueColumn<M> keys,
			@Nullable Comparator<M> comparator,
			boolean transactionalLayer
		) {
			this.keys = keys;
			this.records = new int[keys.capacity()];
			this.overflow = null;
			this.comparator = comparator;
			this.peek = -1;
			this.transactionalLayer = transactionalLayer;
		}

		/**
		 * Creates a new leaf node by copying a range of all three columns from origin arrays into the target arrays,
		 * used during node split operations. The overflow column is allocated in the target only when the origin has one.
		 *
		 * @param originKeys         the source key column to copy from
		 * @param originRecords      the source single-record column to copy from
		 * @param originOverflow     the source overflow column to copy from (may be null)
		 * @param keys               the target key column (may be the same as originKeys)
		 * @param records            the target single-record column (may be the same as originRecords)
		 * @param overflow           the target overflow column (may be the same as originOverflow, or null)
		 * @param start              the start index (inclusive) in the origin arrays
		 * @param end                the end index (exclusive) in the origin arrays
		 * @param comparator         optional comparator defining the key order; `null` ⇒ natural order
		 * @param transactionalLayer whether this node participates in the transactional memory layer
		 */
		public BPlusLeafTreeNode(
			@Nonnull ValueColumn<M> originKeys,
			@Nonnull int[] originRecords,
			@Nullable TransactionalBitmap[] originOverflow,
			@Nonnull ValueColumn<M> keys,
			@Nonnull int[] records,
			@Nullable TransactionalBitmap[] overflow,
			int start, int end,
			@Nullable Comparator<M> comparator,
			boolean transactionalLayer
		) {
			this.keys = keys;
			this.records = records;
			this.overflow = overflow;
			originKeys.copyRangeTo(start, keys, 0, end - start);
			if (keys == originKeys) {
				keys.fillEmpty(end - start, keys.capacity());
			}
			System.arraycopy(originRecords, start, records, 0, end - start);
			//noinspection ArrayEquality
			if (records == originRecords) {
				Arrays.fill(records, end - start, records.length, 0);
			}
			if (overflow != null) {
				// originOverflow may be null when the source leaf carried no multi bucket but the target column was
				// requested (it isn't, in our split path — both are allocated together) — guard defensively anyway
				if (originOverflow != null) {
					System.arraycopy(originOverflow, start, overflow, 0, end - start);
				}
				//noinspection ArrayEquality
				if (overflow == originOverflow) {
					Arrays.fill(overflow, end - start, overflow.length, null);
				}
			}
			this.peek = end - start - 1;
			this.comparator = comparator;
			this.transactionalLayer = transactionalLayer;
		}

		private BPlusLeafTreeNode(
			@Nonnull ValueColumn<M> keys,
			@Nonnull int[] records,
			@Nullable TransactionalBitmap[] overflow,
			int peek,
			@Nullable Comparator<M> comparator,
			boolean transactionalLayer
		) {
			this.keys = keys;
			this.records = records;
			this.overflow = overflow;
			this.peek = peek;
			this.comparator = comparator;
			this.transactionalLayer = transactionalLayer;
		}

		@Nonnull
		@Override
		public M[] getKeys() {
			return getKeyColumn().asBoxedArray();
		}

		@Nonnull
		@Override
		public M keyAt(int index) {
			return getKeyColumn().keyAt(index);
		}

		/**
		 * Retrieves the key column of the current node for READ-ONLY purposes (transaction-aware).
		 *
		 * @return the key column (the transaction-local copy when a layer is active)
		 */
		@Nonnull
		public ValueColumn<M> getKeyColumn() {
			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
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
			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
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
			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			if (layer == null) {
				final int originPeek = this.peek;
				this.peek = peek;
				if (peek < originPeek) {
					this.keys.fillEmpty(peek + 1, originPeek + 1);
					Arrays.fill(this.records, peek + 1, originPeek + 1, 0);
					if (this.overflow != null) {
						Arrays.fill(this.overflow, peek + 1, originPeek + 1, null);
					}
				}
			} else {
				final int originPeek = layer.peek;
				layer.peek = peek;
				if (peek < originPeek) {
					if (layer.keys == this.keys) {
						// decouple by deep-copying the shared base column (mirrors the records branch: the freed
						// tail slots stay as-copied since they are beyond the new peek and never read)
						layer.keys = this.keys.duplicate();
					} else {
						layer.keys.fillEmpty(peek + 1, originPeek + 1);
					}
					//noinspection ArrayEquality
					if (layer.records == this.records) {
						layer.records = new int[this.records.length];
						System.arraycopy(this.records, 0, layer.records, 0, originPeek + 1);
					} else {
						Arrays.fill(layer.records, peek + 1, originPeek + 1, 0);
					}
					if (layer.overflow != null) {
						//noinspection ArrayEquality
						if (layer.overflow == this.overflow) {
							layer.overflow = new TransactionalBitmap[this.overflow.length];
							System.arraycopy(this.overflow, 0, layer.overflow, 0, originPeek + 1);
						} else {
							Arrays.fill(layer.overflow, peek + 1, originPeek + 1, null);
						}
					}
				}
			}
		}

		@Override
		public int keyCount() {
			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
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
			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
			if (layer == null) {
				return this.peek == this.records.length - 1;
			} else {
				return layer.peek == layer.records.length - 1;
			}
		}

		@Override
		public void toVerboseString(@Nonnull StringBuilder sb, int level, int indentSpaces) {
			sb.append(" ".repeat(level * indentSpaces));
			final ValueColumn<M> theKeys;
			final int[] theRecords;
			final TransactionalBitmap[] theOverflow;
			final int thePeek;

			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
			if (layer == null) {
				theKeys = this.keys;
				theRecords = this.records;
				theOverflow = this.overflow;
				thePeek = this.peek;
			} else {
				theKeys = layer.keys;
				theRecords = layer.records;
				theOverflow = layer.overflow;
				thePeek = layer.peek;
			}

			for (int i = 0; i <= thePeek; i++) {
				theKeys.appendKey(sb, i);
				sb.append(":");
				if (theOverflow != null && theOverflow[i] != null) {
					sb.append(theOverflow[i]);
				} else {
					sb.append(theRecords[i]);
				}
				if (i < thePeek) {
					sb.append(", ");
				}
			}
		}

		@Override
		public void stealFromLeft(int numberOfTailValues, @Nonnull BPlusLeafTreeNode<M> previousNode) {
			Assert.isPremiseValid(numberOfTailValues > 0, "Number of tail values to steal must be positive!");
			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			if (layer == null) {
				ensureOverflowForSteal(previousNode.getOverflow());
				this.keys.copyRangeTo(0, this.keys, numberOfTailValues, this.peek + 1);
				System.arraycopy(this.records, 0, this.records, numberOfTailValues, this.peek + 1);
				if (this.overflow != null) {
					System.arraycopy(this.overflow, 0, this.overflow, numberOfTailValues, this.peek + 1);
				}
				previousNode.getKeyColumn().copyRangeTo(
					previousNode.size() - numberOfTailValues, this.keys, 0, numberOfTailValues);
				System.arraycopy(
					previousNode.getRecords(), previousNode.size() - numberOfTailValues, this.records, 0,
					numberOfTailValues
				);
				copyOverflowRange(
					previousNode.getOverflow(), previousNode.size() - numberOfTailValues, this.overflow, 0,
					numberOfTailValues
				);
				this.peek += numberOfTailValues;
				previousNode.setPeek(previousNode.getPeek() - numberOfTailValues);
			} else {
				decoupleTransactionalArrays();
				previousNode.decoupleTransactionalArrays();

				ensureLayerOverflowForSteal(layer, previousNode.getOverflow());
				layer.keys.copyRangeTo(0, layer.keys, numberOfTailValues, layer.peek + 1);
				System.arraycopy(layer.records, 0, layer.records, numberOfTailValues, layer.peek + 1);
				if (layer.overflow != null) {
					System.arraycopy(layer.overflow, 0, layer.overflow, numberOfTailValues, layer.peek + 1);
				}
				previousNode.getKeyColumn().copyRangeTo(
					previousNode.size() - numberOfTailValues, layer.keys, 0, numberOfTailValues);
				System.arraycopy(
					previousNode.getRecords(), previousNode.size() - numberOfTailValues, layer.records, 0,
					numberOfTailValues
				);
				copyOverflowRange(
					previousNode.getOverflow(), previousNode.size() - numberOfTailValues, layer.overflow, 0,
					numberOfTailValues
				);
				layer.peek += numberOfTailValues;
				previousNode.setPeek(previousNode.getPeek() - numberOfTailValues);
			}
		}

		@Override
		public void stealFromRight(int numberOfHeadValues, @Nonnull BPlusLeafTreeNode<M> nextNode) {
			Assert.isPremiseValid(numberOfHeadValues > 0, "Number of head values to steal must be positive!");

			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			if (layer == null) {
				final ValueColumn<M> nextKeys = nextNode.getKeyColumnForUpdate();
				final int[] nextRecords = nextNode.getRecordsForUpdate();
				final TransactionalBitmap[] nextOverflow = nextNode.getOverflowForUpdate();
				ensureOverflowForSteal(nextOverflow);
				nextKeys.copyRangeTo(0, this.keys, this.peek + 1, numberOfHeadValues);
				System.arraycopy(nextRecords, 0, this.records, this.peek + 1, numberOfHeadValues);
				copyOverflowRange(nextOverflow, 0, this.overflow, this.peek + 1, numberOfHeadValues);
				nextKeys.copyRangeTo(numberOfHeadValues, nextKeys, 0, nextNode.size() - numberOfHeadValues);
				System.arraycopy(
					nextRecords, numberOfHeadValues, nextRecords, 0, nextNode.size() - numberOfHeadValues);
				if (nextOverflow != null) {
					System.arraycopy(
						nextOverflow, numberOfHeadValues, nextOverflow, 0, nextNode.size() - numberOfHeadValues);
				}
				nextNode.setPeek(nextNode.getPeek() - numberOfHeadValues);
				this.peek += numberOfHeadValues;
			} else {
				decoupleTransactionalArrays();
				nextNode.decoupleTransactionalArrays();

				final ValueColumn<M> nextKeys = nextNode.getKeyColumnForUpdate();
				final int[] nextRecords = nextNode.getRecordsForUpdate();
				final TransactionalBitmap[] nextOverflow = nextNode.getOverflowForUpdate();
				ensureLayerOverflowForSteal(layer, nextOverflow);
				nextKeys.copyRangeTo(0, layer.keys, layer.peek + 1, numberOfHeadValues);
				System.arraycopy(nextRecords, 0, layer.records, layer.peek + 1, numberOfHeadValues);
				copyOverflowRange(nextOverflow, 0, layer.overflow, layer.peek + 1, numberOfHeadValues);
				nextKeys.copyRangeTo(numberOfHeadValues, nextKeys, 0, nextNode.size() - numberOfHeadValues);
				System.arraycopy(
					nextRecords, numberOfHeadValues, nextRecords, 0, nextNode.size() - numberOfHeadValues);
				if (nextOverflow != null) {
					System.arraycopy(
						nextOverflow, numberOfHeadValues, nextOverflow, 0, nextNode.size() - numberOfHeadValues);
				}
				nextNode.setPeek(nextNode.getPeek() - numberOfHeadValues);
				layer.peek += numberOfHeadValues;
			}
		}

		@Override
		public void mergeWithLeft(@Nonnull BPlusLeafTreeNode<M> previousNode) {
			final int mergePeek = previousNode.getPeek();
			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			if (layer == null) {
				ensureOverflowForSteal(previousNode.getOverflow());
				this.keys.copyRangeTo(0, this.keys, mergePeek + 1, this.peek + 1);
				System.arraycopy(this.records, 0, this.records, mergePeek + 1, this.peek + 1);
				if (this.overflow != null) {
					System.arraycopy(this.overflow, 0, this.overflow, mergePeek + 1, this.peek + 1);
				}
				previousNode.getKeyColumn().copyRangeTo(0, this.keys, 0, mergePeek + 1);
				System.arraycopy(previousNode.getRecords(), 0, this.records, 0, mergePeek + 1);
				copyOverflowRange(previousNode.getOverflow(), 0, this.overflow, 0, mergePeek + 1);
				this.peek += mergePeek + 1;
				previousNode.setPeek(-1);
			} else {
				decoupleTransactionalArrays();
				previousNode.decoupleTransactionalArrays();

				ensureLayerOverflowForSteal(layer, previousNode.getOverflow());
				layer.keys.copyRangeTo(0, layer.keys, mergePeek + 1, layer.peek + 1);
				System.arraycopy(layer.records, 0, layer.records, mergePeek + 1, layer.peek + 1);
				if (layer.overflow != null) {
					System.arraycopy(layer.overflow, 0, layer.overflow, mergePeek + 1, layer.peek + 1);
				}
				previousNode.getKeyColumnForUpdate().copyRangeTo(0, layer.keys, 0, mergePeek + 1);
				System.arraycopy(previousNode.getRecordsForUpdate(), 0, layer.records, 0, mergePeek + 1);
				copyOverflowRange(previousNode.getOverflowForUpdate(), 0, layer.overflow, 0, mergePeek + 1);
				layer.peek += mergePeek + 1;
				previousNode.setPeek(-1);
			}
		}

		@Override
		public void mergeWithRight(@Nonnull BPlusLeafTreeNode<M> nextNode) {
			final int mergePeek = nextNode.getPeek();
			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			if (layer == null) {
				ensureOverflowForSteal(nextNode.getOverflow());
				nextNode.getKeyColumn().copyRangeTo(0, this.keys, this.peek + 1, mergePeek + 1);
				System.arraycopy(nextNode.getRecords(), 0, this.records, this.peek + 1, mergePeek + 1);
				copyOverflowRange(nextNode.getOverflow(), 0, this.overflow, this.peek + 1, mergePeek + 1);
				this.peek += mergePeek + 1;
				nextNode.setPeek(-1);
			} else {
				decoupleTransactionalArrays();
				nextNode.decoupleTransactionalArrays();

				ensureLayerOverflowForSteal(layer, nextNode.getOverflow());
				nextNode.getKeyColumnForUpdate().copyRangeTo(0, layer.keys, layer.peek + 1, mergePeek + 1);
				System.arraycopy(nextNode.getRecordsForUpdate(), 0, layer.records, layer.peek + 1, mergePeek + 1);
				copyOverflowRange(nextNode.getOverflowForUpdate(), 0, layer.overflow, layer.peek + 1, mergePeek + 1);
				layer.peek += mergePeek + 1;
				nextNode.setPeek(-1);
			}
		}

		@Nonnull
		@Override
		public M getLeftBoundaryKey() {
			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
			if (layer == null) {
				return this.keys.keyAt(0);
			} else {
				return layer.keys.keyAt(0);
			}
		}

		/**
		 * Retrieves the single-record column for READ-ONLY purposes (transaction-aware).
		 *
		 * @return the records column
		 */
		@Nonnull
		public int[] getRecords() {
			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
			if (layer == null) {
				return this.records;
			} else {
				return layer.records;
			}
		}

		/**
		 * Retrieves the lazy overflow column for READ-ONLY purposes (transaction-aware). May be null when the leaf
		 * carries no multi bucket.
		 *
		 * @return the overflow column, or null
		 */
		@Nullable
		public TransactionalBitmap[] getOverflow() {
			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
			if (layer == null) {
				return this.overflow;
			} else {
				return layer.overflow;
			}
		}

		/**
		 * Retrieves the key column of the current node for updating, decoupling a transaction-local deep copy when a
		 * layer is active and still sharing the base column.
		 *
		 * @return the key column (transaction-local copy when a layer is active)
		 */
		@Nonnull
		public ValueColumn<M> getKeyColumnForUpdate() {
			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			if (layer == null) {
				return this.keys;
			} else {
				if (layer.keys == this.keys) {
					layer.keys = this.keys.duplicate();
				}
				return layer.keys;
			}
		}

		/**
		 * Retrieves the single-record column for updating, decoupling a transactional copy when needed.
		 *
		 * @return the records column (transaction-local copy when a layer is active)
		 */
		@Nonnull
		public int[] getRecordsForUpdate() {
			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			if (layer == null) {
				return this.records;
			} else {
				//noinspection ArrayEquality
				if (layer.records == this.records) {
					layer.records = new int[this.records.length];
					System.arraycopy(this.records, 0, layer.records, 0, this.records.length);
				}
				return layer.records;
			}
		}

		/**
		 * Retrieves the overflow column for updating, decoupling a transactional copy when needed. Returns null when the
		 * leaf carries no overflow column (the caller must allocate one before writing into it).
		 *
		 * @return the overflow column (transaction-local copy when a layer is active), or null
		 */
		@Nullable
		public TransactionalBitmap[] getOverflowForUpdate() {
			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			if (layer == null) {
				return this.overflow;
			} else {
				//noinspection ArrayEquality
				if (layer.overflow != null && layer.overflow == this.overflow) {
					layer.overflow = new TransactionalBitmap[this.overflow.length];
					System.arraycopy(this.overflow, 0, layer.overflow, 0, this.overflow.length);
				}
				return layer.overflow;
			}
		}

		/**
		 * Returns the record set for the given value: a lean {@link SingleRecordBitmap} for a single bucket, the
		 * {@link TransactionalBitmap} for a multi bucket, or {@link EmptyBitmap#INSTANCE} when absent.
		 *
		 * @param value the value to look up
		 * @return the record set, never null
		 */
		@Nonnull
		public Bitmap getRecords(@Nonnull M value) {
			final ValueColumn<M> theKeys;
			final int[] theRecords;
			final TransactionalBitmap[] theOverflow;
			final int thePeek;

			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
			if (layer == null) {
				theKeys = this.keys;
				theRecords = this.records;
				theOverflow = this.overflow;
				thePeek = this.peek;
			} else {
				theKeys = layer.keys;
				theRecords = layer.records;
				theOverflow = layer.overflow;
				thePeek = layer.peek;
			}

			final InsertionPosition insertionPosition =
				theKeys.findKeyPosition(value, 0, thePeek + 1, this.comparator);
			if (!insertionPosition.alreadyPresent()) {
				return EmptyBitmap.INSTANCE;
			}
			final int index = insertionPosition.position();
			if (theOverflow != null && theOverflow[index] != null) {
				return theOverflow[index];
			}
			return new SingleRecordBitmap(theRecords[index]);
		}

		/**
		 * Returns the cardinality of the bucket for the given value (1 for single, bitmap size for multi, 0 absent),
		 * without materializing a bitmap.
		 *
		 * @param value the value to look up
		 * @return the cardinality of the bucket
		 */
		public int cardinalityOf(@Nonnull M value) {
			final ValueColumn<M> theKeys;
			final TransactionalBitmap[] theOverflow;
			final int thePeek;

			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
			if (layer == null) {
				theKeys = this.keys;
				theOverflow = this.overflow;
				thePeek = this.peek;
			} else {
				theKeys = layer.keys;
				theOverflow = layer.overflow;
				thePeek = layer.peek;
			}

			final InsertionPosition insertionPosition =
				theKeys.findKeyPosition(value, 0, thePeek + 1, this.comparator);
			if (!insertionPosition.alreadyPresent()) {
				return 0;
			}
			final int index = insertionPosition.position();
			if (theOverflow != null && theOverflow[index] != null) {
				return theOverflow[index].size();
			}
			return 1;
		}

		/**
		 * Returns the index of the bucket for the given value, or -1 if absent.
		 *
		 * @param value the value to search for
		 * @return the index of the bucket if found; -1 otherwise
		 */
		public int getValueIndex(@Nonnull M value) {
			final ValueColumn<M> theKeys;
			final int thePeek;

			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
			if (layer == null) {
				theKeys = this.keys;
				thePeek = this.peek;
			} else {
				theKeys = layer.keys;
				thePeek = layer.peek;
			}

			final InsertionPosition insertionPosition =
				theKeys.findKeyPosition(value, 0, thePeek + 1, this.comparator);
			return insertionPosition.alreadyPresent() ? insertionPosition.position() : -1;
		}

		@Override
		public String toString() {
			final StringBuilder sb = new StringBuilder(DEFAULT_VALUE_BLOCK_SIZE);
			toVerboseString(sb, 0, 3);
			return sb.toString();
		}

		@Override
		public BPlusLeafTreeNode<M> createLayer() {
			return new BPlusLeafTreeNode<>(
				this.keys,
				this.records,
				this.overflow,
				this.keys,
				this.records,
				this.overflow,
				0,
				this.peek + 1,
				this.comparator,
				false
			);
		}

		@Override
		public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			transactionalLayer.removeTransactionalMemoryLayer(this);
		}

		@Nonnull
		@Override
		public BPlusLeafTreeNode<M> createCopyWithMergedTransactionalMemory(
			@Nullable BPlusLeafTreeNode<M> layer,
			@Nonnull TransactionalLayerMaintainer transactionalLayer
		) {
			final ValueColumn<M> theKeys;
			final int[] theRecords;
			final TransactionalBitmap[] theOverflow;
			final int thePeek;
			if (layer == null) {
				theKeys = this.keys;
				theRecords = this.records;
				theOverflow = this.overflow;
				thePeek = this.peek;
			} else {
				theKeys = layer.keys;
				theRecords = layer.records;
				theOverflow = layer.overflow;
				thePeek = layer.peek;
			}

			// commit-wrap runs ONLY on the overflow column (producer bitmaps); keys/records are plain references
			TransactionalBitmap[] newOverflow = null;
			if (theOverflow != null) {
				for (int i = 0; i < thePeek + 1; i++) {
					final TransactionalBitmap original = theOverflow[i];
					if (original == null) {
						if (newOverflow != null) {
							newOverflow[i] = null;
						}
						continue;
					}
					final TransactionalBitmap committed = wrapOverflow(
						transactionalLayer.getStateCopyWithCommittedChanges(original)
					);
					if (newOverflow == null && committed != original) {
						newOverflow = new TransactionalBitmap[theOverflow.length];
						System.arraycopy(theOverflow, 0, newOverflow, 0, i);
					}
					if (newOverflow != null) {
						newOverflow[i] = committed;
					}
				}
			}

			if (newOverflow != null) {
				return new BPlusLeafTreeNode<>(
					theKeys,
					theRecords,
					newOverflow,
					thePeek,
					this.comparator,
					true
				);
			} else if (layer != null) {
				return new BPlusLeafTreeNode<>(
					theKeys,
					theRecords,
					theOverflow,
					thePeek,
					this.comparator,
					true
				);
			} else if (!this.transactionalLayer) {
				// nodes created during splits/merges are built with transactionalLayer=false so they do not allocate
				// STM layers mid-transaction; on commit they must be rebuilt as participating (transactionalLayer=true)
				// nodes so subsequent transactions can layer changes over them
				return new BPlusLeafTreeNode<>(
					theKeys,
					theRecords,
					theOverflow,
					thePeek,
					this.comparator,
					true
				);
			} else {
				return this;
			}
		}

		/**
		 * Adds a single record to the bucket identified by `value`, applying the promotion rules. See
		 * {@link TransactionalBucketBPlusTree#addRecord(Comparable, int)}.
		 *
		 * @param value the value identifying the bucket
		 * @param pk    the record id to add
		 * @return true if a new bucket was inserted, false otherwise
		 */
		public boolean addRecord(@Nonnull M value, int pk) {
			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			if (layer == null) {
				Assert.isPremiseValid(
					this.peek < this.records.length - 1,
					"Cannot insert into a full leaf node, split the node first!"
				);
				final InsertionPosition insertionPosition =
					this.keys.findKeyPosition(value, 0, this.peek + 1, this.comparator);
				if (insertionPosition.alreadyPresent()) {
					addToExistingBucket(insertionPosition.position(), pk);
					return false;
				}
				insertNewSingleBucket(insertionPosition.position(), value, pk);
				return true;
			} else {
				decoupleTransactionalArrays();
				Assert.isPremiseValid(
					layer.peek < layer.records.length - 1,
					"Cannot insert into a full leaf node, split the node first!"
				);
				final InsertionPosition insertionPosition =
					layer.keys.findKeyPosition(value, 0, layer.peek + 1, this.comparator);
				if (insertionPosition.alreadyPresent()) {
					layer.addToExistingBucket(insertionPosition.position(), pk);
					return false;
				}
				layer.insertNewSingleBucket(insertionPosition.position(), value, pk);
				return true;
			}
		}

		/**
		 * Adds multiple records to the bucket identified by `value`, applying the promotion rules. See
		 * {@link TransactionalBucketBPlusTree#addRecord(Comparable, int...)}.
		 *
		 * @param value the value identifying the bucket
		 * @param pks   the record ids to add; must be non-empty
		 * @return true if a new bucket was inserted, false otherwise
		 */
		public boolean addRecords(@Nonnull M value, @Nonnull int... pks) {
			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			if (layer == null) {
				Assert.isPremiseValid(
					this.peek < this.records.length - 1,
					"Cannot insert into a full leaf node, split the node first!"
				);
				final InsertionPosition insertionPosition =
					this.keys.findKeyPosition(value, 0, this.peek + 1, this.comparator);
				if (insertionPosition.alreadyPresent()) {
					addRecordsToExistingBucket(insertionPosition.position(), pks);
					return false;
				}
				insertNewBucket(insertionPosition.position(), value, pks);
				return true;
			} else {
				decoupleTransactionalArrays();
				Assert.isPremiseValid(
					layer.peek < layer.records.length - 1,
					"Cannot insert into a full leaf node, split the node first!"
				);
				final InsertionPosition insertionPosition =
					layer.keys.findKeyPosition(value, 0, layer.peek + 1, this.comparator);
				if (insertionPosition.alreadyPresent()) {
					layer.addRecordsToExistingBucket(insertionPosition.position(), pks);
					return false;
				}
				layer.insertNewBucket(insertionPosition.position(), value, pks);
				return true;
			}
		}

		/**
		 * Removes records from the bucket identified by `value`. See
		 * {@link TransactionalBucketBPlusTree#removeRecord(Comparable, int...)}.
		 *
		 * @param value the value identifying the bucket
		 * @param pks   the record ids to remove; must be non-empty
		 * @return true if the bucket was deleted, false otherwise
		 */
		public boolean removeRecords(@Nonnull M value, @Nonnull int... pks) {
			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			if (layer == null) {
				final InsertionPosition insertionPosition =
					this.keys.findKeyPosition(value, 0, this.peek + 1, this.comparator);
				if (!insertionPosition.alreadyPresent()) {
					return false;
				}
				return removeFromBucket(insertionPosition.position(), pks);
			} else {
				decoupleTransactionalArrays();
				final InsertionPosition insertionPosition =
					layer.keys.findKeyPosition(value, 0, layer.peek + 1, this.comparator);
				if (!insertionPosition.alreadyPresent()) {
					return false;
				}
				return layer.removeFromBucket(insertionPosition.position(), pks);
			}
		}

		/**
		 * Adds a single record to the existing bucket at `index`, promoting it if needed. Operates on the resolved
		 * (decoupled) column instance (`this` is the layer or the non-transactional node).
		 *
		 * @param index the bucket index
		 * @param pk    the record id to add
		 */
		private void addToExistingBucket(int index, int pk) {
			if (this.overflow != null && this.overflow[index] != null) {
				// multi bucket - mutate in place
				this.overflow[index].add(pk);
				return;
			}
			// single bucket
			if (this.records[index] == pk) {
				// already the sole record - no-op, stay single
				return;
			}
			// second distinct record - promote to a multi-record bitmap
			final TransactionalBitmap[] overflow = ensureOverflowColumn();
			overflow[index] = new TransactionalBitmap(this.records[index], pk);
		}

		/**
		 * Adds multiple records to the existing bucket at `index`, promoting it if needed.
		 *
		 * @param index the bucket index
		 * @param pks   the record ids to add; must be non-empty
		 */
		private void addRecordsToExistingBucket(int index, @Nonnull int... pks) {
			if (this.overflow != null && this.overflow[index] != null) {
				// multi bucket - mutate in place
				this.overflow[index].addAll(pks);
				return;
			}
			// single bucket
			if (pks.length == 1 && pks[0] == this.records[index]) {
				// the only id being added is the one already held - keep the compact form
				return;
			}
			// promote to a bitmap holding the existing id plus all added ids (the bitmap dedupes & orders)
			final TransactionalBitmap[] overflow = ensureOverflowColumn();
			final TransactionalBitmap promoted = new TransactionalBitmap(this.records[index]);
			promoted.addAll(pks);
			overflow[index] = promoted;
		}

		/**
		 * Removes records from the bucket at `index`. A matching single bucket is deleted; a multi bucket has the ids
		 * removed in place and is deleted (with its bitmap layer released) when it drops to zero records.
		 *
		 * @param index the bucket index
		 * @param pks   the record ids to remove; must be non-empty
		 * @return true if the bucket was deleted, false otherwise
		 */
		private boolean removeFromBucket(int index, @Nonnull int... pks) {
			if (this.overflow != null && this.overflow[index] != null) {
				// multi bucket - mutate in place
				final TransactionalBitmap bitmap = this.overflow[index];
				bitmap.removeAll(pks);
				if (bitmap.isEmpty()) {
					// the multi bucket drained to zero - delete it (release its bitmap layer)
					deleteBucketAt(index);
					return true;
				}
				return false;
			}
			// single bucket - removing its sole id deletes the bucket
			final int held = this.records[index];
			for (final int pk : pks) {
				if (pk == held) {
					deleteBucketAt(index);
					return true;
				}
			}
			// none of the ids matched the sole record - silent no-op
			return false;
		}

		/**
		 * Inserts a new single-record bucket at `position`, shifting all three columns right by one.
		 *
		 * @param position the position at which to insert the new bucket
		 * @param value    the bucket value
		 * @param pk       the lone record id
		 */
		private void insertNewSingleBucket(int position, @Nonnull M value, int pk) {
			this.keys.insertKeyAt(position, value);
			insertIntIntoSameArrayOnIndex(pk, this.records, position);
			if (this.overflow != null) {
				shiftOverflowForSingleInsert(this.overflow, position);
			}
			this.peek++;
		}

		/**
		 * Inserts a new bucket at `position` holding the given records (single when one id, a multi bitmap otherwise),
		 * shifting all three columns right by one.
		 *
		 * @param position the position at which to insert the new bucket
		 * @param value    the bucket value
		 * @param pks      the record ids; must be non-empty
		 */
		private void insertNewBucket(int position, @Nonnull M value, @Nonnull int... pks) {
			this.keys.insertKeyAt(position, value);
			if (pks.length == 1) {
				insertIntIntoSameArrayOnIndex(pks[0], this.records, position);
				if (this.overflow != null) {
					shiftOverflowForSingleInsert(this.overflow, position);
				}
			} else {
				// multi bucket from the start - records[position] is don't-care
				insertIntIntoSameArrayOnIndex(0, this.records, position);
				final TransactionalBitmap[] overflow = ensureOverflowColumn();
				insertRecordIntoSameArrayOnIndex(new TransactionalBitmap(pks), overflow, position);
			}
			this.peek++;
		}

		/**
		 * Deletes the bucket at `index`, collapsing all three columns. When the bucket was a multi bucket its bitmap's
		 * transactional layer is released via {@code discardRemovedValueLayer} so it is not detected as stale on commit.
		 *
		 * @param index the bucket index to delete
		 */
		private void deleteBucketAt(int index) {
			if (this.overflow != null) {
				// release the discarded multi bucket's bitmap layer (no-op for a single bucket / null entry)
				discardRemovedValueLayer(this.overflow[index]);
				removeRecordFromSameArrayOnIndex(this.overflow, index);
				this.overflow[this.peek] = null;
			}
			this.keys.removeKeyAt(index);
			removeIntFromSameArrayOnIndex(this.records, index);
			this.keys.clearAt(this.peek);
			this.records[this.peek] = 0;
			this.peek--;
		}

		/**
		 * Allocates the lazy overflow column on this leaf if it is not yet present and returns it.
		 *
		 * @return the overflow column, guaranteed non-null
		 */
		@Nonnull
		private TransactionalBitmap[] ensureOverflowColumn() {
			if (this.overflow == null) {
				this.overflow = new TransactionalBitmap[this.records.length];
			}
			return this.overflow;
		}

		/**
		 * Ensures this (non-transactional) node has an overflow column when the sibling being merged/stolen from carries
		 * one, so multi buckets are not lost during rebalancing.
		 *
		 * @param siblingOverflow the sibling's overflow column (may be null)
		 */
		private void ensureOverflowForSteal(@Nullable TransactionalBitmap[] siblingOverflow) {
			if (siblingOverflow != null && this.overflow == null) {
				this.overflow = new TransactionalBitmap[this.records.length];
			}
		}

		/**
		 * Transactional-layer counterpart of {@link #ensureOverflowForSteal} — ensures the layer's overflow column
		 * exists (decoupled from the base) when the sibling carries one.
		 *
		 * @param layer           the transactional layer leaf
		 * @param siblingOverflow the sibling's overflow column (may be null)
		 */
		private void ensureLayerOverflowForSteal(
			@Nonnull BPlusLeafTreeNode<M> layer, @Nullable TransactionalBitmap[] siblingOverflow) {
			if (siblingOverflow != null && layer.overflow == null) {
				layer.overflow = new TransactionalBitmap[layer.records.length];
			}
		}

		/**
		 * Decouples the node's three columns into transaction-local copies before mutation.
		 */
		private void decoupleTransactionalArrays() {
			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			if (layer != null) {
				if (layer.keys == this.keys) {
					layer.keys = this.keys.duplicate();
				}
				//noinspection ArrayEquality
				if (layer.records == this.records) {
					layer.records = new int[this.records.length];
					System.arraycopy(this.records, 0, layer.records, 0, this.peek + 1);
				}
				//noinspection ArrayEquality
				if (this.overflow != null && layer.overflow == this.overflow) {
					layer.overflow = new TransactionalBitmap[this.overflow.length];
					System.arraycopy(this.overflow, 0, layer.overflow, 0, this.peek + 1);
				}
			}
		}

	}

	/**
	 * Forward {@link BucketCursor}: walks leaves left to right, caching the current leaf's columns so per-bucket access
	 * is plain array indexing.
	 */
	private static final class ForwardBucketCursor<M extends Comparable<M>> implements BucketCursor<M> {
		@Nonnull private final BPlusTreeNode<M, ?>[][] path;
		@Nonnull private final int[] pathIndex;
		@Nonnull private final int[] pathPeeks;
		private int currentIndex;
		private boolean positioned;
		private boolean exhausted;
		private ValueColumn<M> leafKeys;
		private int[] leafRecords;
		@Nullable private TransactionalBitmap[] leafOverflow;
		private int leafPeek;

		ForwardBucketCursor(@Nonnull Cursor<M> cursor) {
			final List<CursorLevel<M>> cursorPath = cursor.path();
			//noinspection unchecked
			this.path = new BPlusTreeNode[cursorPath.size()][];
			this.pathIndex = new int[this.path.length];
			this.pathPeeks = new int[this.path.length];
			for (int i = 0; i < cursorPath.size(); i++) {
				final CursorLevel<M> cursorLevel = cursorPath.get(i);
				this.path[i] = cursorLevel.siblings();
				this.pathIndex[i] = cursorLevel.index();
				this.pathPeeks[i] = cursorLevel.peek();
			}
			loadCurrentLeaf();
			this.currentIndex = -1;
			this.exhausted = this.leafPeek < 0;
		}

		ForwardBucketCursor(@Nonnull Cursor<M> cursor, @Nonnull M key) {
			final List<CursorLevel<M>> cursorPath = cursor.path();
			//noinspection unchecked
			this.path = new BPlusTreeNode[cursorPath.size()][];
			this.pathIndex = new int[this.path.length];
			this.pathPeeks = new int[this.path.length];
			for (int i = 0; i < cursorPath.size(); i++) {
				final CursorLevel<M> cursorLevel = cursorPath.get(i);
				this.path[i] = cursorLevel.siblings();
				this.pathIndex[i] = cursorLevel.index();
				this.pathPeeks[i] = cursorLevel.peek();
			}
			final BPlusLeafTreeNode<M> startLeaf = cursor.leafNode();
			final InsertionPosition insertionPosition = startLeaf.getKeyColumn().findKeyPosition(
				key, 0, startLeaf.size(), startLeaf.getComparator()
			);
			loadCurrentLeaf();
			// position one before the start so the first next() lands on the start bucket
			this.currentIndex = insertionPosition.position() - 1;
			if (insertionPosition.position() <= this.leafPeek) {
				this.exhausted = false;
			} else {
				// start key is greater than every key in this leaf - jump to the next leaf on the first next()
				this.exhausted = false;
				this.currentIndex = this.leafPeek;
			}
		}

		@Override
		public boolean next() {
			if (this.exhausted) {
				return false;
			}
			if (this.currentIndex < this.leafPeek) {
				this.currentIndex++;
				this.positioned = true;
				return true;
			}
			if (moveToNextLeaf()) {
				this.positioned = true;
				return true;
			}
			this.exhausted = true;
			this.positioned = false;
			return false;
		}

		@Nonnull
		@Override
		public M value() {
			Assert.isPremiseValid(this.positioned, "Cursor is not positioned at a bucket!");
			return this.leafKeys.keyAt(this.currentIndex);
		}

		@Override
		public boolean isSingle() {
			Assert.isPremiseValid(this.positioned, "Cursor is not positioned at a bucket!");
			return this.leafOverflow == null || this.leafOverflow[this.currentIndex] == null;
		}

		@Override
		public int singleRecordId() {
			Assert.isPremiseValid(this.positioned, "Cursor is not positioned at a bucket!");
			return this.leafRecords[this.currentIndex];
		}

		@Nonnull
		@Override
		public Bitmap records() {
			Assert.isPremiseValid(this.positioned, "Cursor is not positioned at a bucket!");
			if (this.leafOverflow != null && this.leafOverflow[this.currentIndex] != null) {
				return this.leafOverflow[this.currentIndex];
			}
			return new SingleRecordBitmap(this.leafRecords[this.currentIndex]);
		}

		@Override
		public int size() {
			Assert.isPremiseValid(this.positioned, "Cursor is not positioned at a bucket!");
			if (this.leafOverflow != null && this.leafOverflow[this.currentIndex] != null) {
				return this.leafOverflow[this.currentIndex].size();
			}
			return 1;
		}

		private void loadCurrentLeaf() {
			//noinspection unchecked
			final BPlusLeafTreeNode<M> leaf =
				(BPlusLeafTreeNode<M>) this.path[this.path.length - 1][this.pathIndex[this.pathIndex.length - 1]];
			this.leafKeys = leaf.getKeyColumn();
			this.leafRecords = leaf.getRecords();
			this.leafOverflow = leaf.getOverflow();
			this.leafPeek = leaf.getPeek();
		}

		private boolean moveToNextLeaf() {
			int level = this.pathIndex.length - 1;
			BPlusTreeNode<?, ?>[] parentLevel = this.path[level];
			while (parentLevel != null) {
				if (this.pathIndex[level] < this.pathPeeks[level]) {
					this.pathIndex[level] = this.pathIndex[level] + 1;
					BPlusTreeNode<?, ?> currentNode = this.path[level][this.pathIndex[level]];
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
					loadCurrentLeaf();
					return this.leafPeek >= 0;
				} else {
					level--;
					parentLevel = level > 0 ? this.path[level] : null;
				}
			}
			return false;
		}
	}

	/**
	 * Reverse {@link BucketCursor}: walks leaves right to left.
	 */
	private static final class ReverseBucketCursor<M extends Comparable<M>> implements BucketCursor<M> {
		@Nonnull private final BPlusTreeNode<M, ?>[][] path;
		@Nonnull private final int[] pathIndex;
		private int currentIndex;
		private boolean positioned;
		private boolean exhausted;
		private boolean started;
		private ValueColumn<M> leafKeys;
		private int[] leafRecords;
		@Nullable private TransactionalBitmap[] leafOverflow;
		private int leafPeek;

		ReverseBucketCursor(@Nonnull Cursor<M> cursor) {
			final List<CursorLevel<M>> cursorPath = cursor.path();
			//noinspection unchecked
			this.path = new BPlusTreeNode[cursorPath.size()][];
			this.pathIndex = new int[this.path.length];
			for (int i = 0; i < cursorPath.size(); i++) {
				final CursorLevel<M> cursorLevel = cursorPath.get(i);
				this.path[i] = cursorLevel.siblings();
				this.pathIndex[i] = cursorLevel.index();
			}
			loadCurrentLeaf();
			this.exhausted = this.leafPeek < 0;
		}

		@Override
		public boolean next() {
			if (this.exhausted) {
				return false;
			}
			if (!this.started) {
				this.started = true;
				this.currentIndex = this.leafPeek;
				this.positioned = true;
				return true;
			}
			if (this.currentIndex > 0) {
				this.currentIndex--;
				this.positioned = true;
				return true;
			}
			if (moveToPrevLeaf()) {
				this.positioned = true;
				return true;
			}
			this.exhausted = true;
			this.positioned = false;
			return false;
		}

		@Nonnull
		@Override
		public M value() {
			Assert.isPremiseValid(this.positioned, "Cursor is not positioned at a bucket!");
			return this.leafKeys.keyAt(this.currentIndex);
		}

		@Override
		public boolean isSingle() {
			Assert.isPremiseValid(this.positioned, "Cursor is not positioned at a bucket!");
			return this.leafOverflow == null || this.leafOverflow[this.currentIndex] == null;
		}

		@Override
		public int singleRecordId() {
			Assert.isPremiseValid(this.positioned, "Cursor is not positioned at a bucket!");
			return this.leafRecords[this.currentIndex];
		}

		@Nonnull
		@Override
		public Bitmap records() {
			Assert.isPremiseValid(this.positioned, "Cursor is not positioned at a bucket!");
			if (this.leafOverflow != null && this.leafOverflow[this.currentIndex] != null) {
				return this.leafOverflow[this.currentIndex];
			}
			return new SingleRecordBitmap(this.leafRecords[this.currentIndex]);
		}

		@Override
		public int size() {
			Assert.isPremiseValid(this.positioned, "Cursor is not positioned at a bucket!");
			if (this.leafOverflow != null && this.leafOverflow[this.currentIndex] != null) {
				return this.leafOverflow[this.currentIndex].size();
			}
			return 1;
		}

		private void loadCurrentLeaf() {
			//noinspection unchecked
			final BPlusLeafTreeNode<M> leaf =
				(BPlusLeafTreeNode<M>) this.path[this.path.length - 1][this.pathIndex[this.pathIndex.length - 1]];
			this.leafKeys = leaf.getKeyColumn();
			this.leafRecords = leaf.getRecords();
			this.leafOverflow = leaf.getOverflow();
			this.leafPeek = leaf.getPeek();
		}

		private boolean moveToPrevLeaf() {
			int level = this.pathIndex.length - 1;
			BPlusTreeNode<M, ?>[] parentLevel = this.path[level];
			while (parentLevel != null) {
				if (this.pathIndex[level] > 0) {
					this.pathIndex[level] = this.pathIndex[level] - 1;
					BPlusTreeNode<M, ?> currentNode = this.path[level][this.pathIndex[level]];
					for (int i = level + 1; i <= this.pathIndex.length - 1; i++) {
						Assert.isPremiseValid(currentNode instanceof BPlusInternalTreeNode, "Internal node expected!");
						//noinspection unchecked
						this.path[i] = ((BPlusInternalTreeNode<M>) currentNode).getChildren();
						this.pathIndex[i] = currentNode.getPeek();
						currentNode = this.path[i][this.pathIndex[i]];
					}
					loadCurrentLeaf();
					this.currentIndex = this.leafPeek;
					return this.leafPeek >= 0;
				} else {
					level--;
					parentLevel = level > 0 ? this.path[level] : null;
				}
			}
			return false;
		}
	}

	/**
	 * Represents a cursor for navigating the B+ tree with its specific level, maintaining the current node and its
	 * path.
	 *
	 * @param path                     the path representing the sequence of nodes traversed to reach the current node
	 * @param level                    the current level in the tree where the cursor is positioned
	 * @param currentNodeOfGenericType the current node at the given level (may be a replaced instance)
	 */
	private record CursorWithLevel<M extends Comparable<M>>(
		@Nonnull List<CursorLevel<M>> path,
		int level,
		@Nonnull BPlusTreeNode<M, ?> currentNodeOfGenericType
	) {

		/**
		 * Creates a cursor at the given level using the current node from the path.
		 *
		 * @param path  the path representing the sequence of nodes traversed to reach the current node
		 * @param level the current level in the tree where the cursor is positioned
		 */
		public CursorWithLevel(@Nonnull List<CursorLevel<M>> path, int level) {
			this(path, level, path.get(level).currentNode());
		}

		/**
		 * Retrieves the current node of the type parameter in the B+ tree.
		 *
		 * @return the current node
		 */
		@Nonnull
		public <N extends BPlusTreeNode<M, N>> N currentNode() {
			//noinspection unchecked
			return (N) this.currentNodeOfGenericType;
		}

		/**
		 * Retrieves the index of the current node in the path at the current level.
		 *
		 * @return the index of the current node at the specified level
		 */
		public int currentNodeIndex() {
			return this.path.get(this.level).index();
		}

		/**
		 * Retrieves the parent node of the current node, if it exists.
		 *
		 * @return the parent internal node when the level is greater than 0; otherwise null
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
		 * Creates a new cursor representing the parent level, or null when the current level is 0.
		 *
		 * @return a parent-level cursor, or null
		 */
		@Nullable
		public CursorWithLevel<M> toParentLevel() {
			return this.level > 0 ? new CursorWithLevel<>(this.path(), this.level - 1) : null;
		}

		/**
		 * Retrieves a cursor representing the previous node at the current level, reconstructing the path below it; null
		 * when the current node is the first sibling. Works only within the current parent.
		 *
		 * @return a cursor to the previous sibling node, or null
		 */
		@Nullable
		public CursorWithLevel<M> getCursorForPreviousNode() {
			final CursorLevel<M> cursorLevel = this.path.get(this.level);
			if (cursorLevel.index() > 0) {
				final List<CursorLevel<M>> replacedPath = new ArrayList<>(this.path);
				CursorLevel<M> newCursorLevel = new CursorLevel<>(
					cursorLevel.siblings(),
					cursorLevel.index() - 1,
					cursorLevel.peek()
				);
				replacedPath.set(this.level, newCursorLevel);
				for (int i = this.level + 1; i < this.path().size(); i++) {
					final BPlusInternalTreeNode<M> currentNode = newCursorLevel.currentNode();
					newCursorLevel = new CursorLevel<>(
						currentNode.getChildren(),
						currentNode.getPeek(),
						currentNode.getPeek()
					);
					replacedPath.set(i, newCursorLevel);
				}
				return new CursorWithLevel<>(
					replacedPath,
					this.level
				);
			} else {
				return null;
			}
		}

		/**
		 * Retrieves a cursor representing the next node at the current level, reconstructing the path below it; null when
		 * the current node is the last sibling. Works only within the current parent.
		 *
		 * @return a cursor to the next sibling node, or null
		 */
		@Nullable
		public CursorWithLevel<M> getCursorForNextNode() {
			final CursorLevel<M> cursorLevel = this.path.get(this.level);
			if (cursorLevel.index() < cursorLevel.peek()) {
				final List<CursorLevel<M>> replacedPath = new ArrayList<>(this.path);
				CursorLevel<M> newCursorLevel = new CursorLevel<>(
					cursorLevel.siblings(),
					cursorLevel.index() + 1,
					cursorLevel.peek()
				);
				replacedPath.set(this.level, newCursorLevel);
				for (int i = this.level + 1; i < this.path.size(); i++) {
					final BPlusInternalTreeNode<M> currentNode = newCursorLevel.currentNode();
					newCursorLevel = new CursorLevel<>(currentNode.getChildren(), 0, currentNode.getPeek());
					replacedPath.set(i, newCursorLevel);
				}
				return new CursorWithLevel<>(
					replacedPath,
					this.level
				);
			} else {
				return null;
			}
		}

		/**
		 * Creates a new cursor with the same path and level but with the current node replaced by the provided node.
		 *
		 * @param node the new current node to replace the existing one
		 * @return a new cursor with the specified current node
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
	 * Represents a path within the B+ tree, always pointing to a leaf node and holding the full path to it.
	 *
	 * @param path the path representing the sequence of nodes traversed to reach the leaf node
	 * @param <M>  the type of key stored in the B+ tree nodes
	 */
	private record Cursor<M extends Comparable<M>>(
		@Nonnull List<CursorLevel<M>> path
	) {

		/**
		 * Retrieves the leaf node at the deepest level of the current path.
		 *
		 * @return the leaf node at the location specified by the current path
		 */
		@Nonnull
		public BPlusLeafTreeNode<M> leafNode() {
			final CursorLevel<M> deepestLevel = this.path.get(this.path.size() - 1);
			//noinspection unchecked
			return (BPlusLeafTreeNode<M>) deepestLevel.siblings()[deepestLevel.index()];
		}

		/**
		 * Converts this cursor into a {@link CursorWithLevel} at the deepest level.
		 *
		 * @return a cursor-with-level for the deepest level of this path
		 */
		@Nonnull
		public CursorWithLevel<M> toCursorWithLevel() {
			return new CursorWithLevel<>(this.path, this.path.size() - 1);
		}
	}

	/**
	 * A record representing the current level of a cursor within the B+ tree, holding the sibling nodes at that level
	 * and tracking the current node index and peek.
	 *
	 * @param siblings the sibling nodes at the current level
	 * @param index    the index of the current node within the siblings array (must be > 0 and <= peek)
	 * @param peek     the last meaningful index in the siblings array
	 */
	private record CursorLevel<M extends Comparable<M>>(
		@Nonnull BPlusTreeNode<M, ?>[] siblings,
		int index,
		int peek
	) {

		/**
		 * Retrieves the current node in the siblings array at the specified index.
		 *
		 * @return the current node at the specified index
		 */
		@Nonnull
		public <N extends BPlusTreeNode<M, N>> N currentNode() {
			//noinspection unchecked
			return (N) this.siblings[this.index];
		}
	}

}
