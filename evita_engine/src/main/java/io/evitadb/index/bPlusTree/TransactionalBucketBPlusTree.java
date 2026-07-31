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
import io.evitadb.core.transaction.memory.DirtyScopeValidator;
import io.evitadb.core.transaction.memory.Snapshotable;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalStateProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
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
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

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
 * - `RecordColumn records` — the single record id (pk) when `overflow == null || overflow[i] == null`; the default
 *   {@link IntRecordColumn} backs it with a bare `int[]`.
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
 * (allocating the overflow column lazily); removing the last record deletes the bucket. Promotion happens eagerly at
 * mutation time; **demotion is deferred to the leaf commit-merge** — a multi bucket reduced back to a single record is
 * not demoted mid-transaction (a bucket oscillating across the 1/2 boundary within one transaction would otherwise
 * allocate and free its bitmap on every crossing). At commit, {@link BPlusLeafTreeNode#createCopyWithMergedTransactionalMemory}
 * reads each overflow bitmap's committed cardinality and, when it has settled at one, reverts the bucket to the
 * primitive single-record form (writing the sole surviving id into the records column, nulled overflow slot); at most
 * one promote-alloc and one demote-free therefore occur per bucket per transaction. When a multi bucket is deleted, its
 * bitmap's transactional diff layer is explicitly released via {@code discardRemovedValueLayer} so it is not left ALIVE
 * and detected as stale during commit; a demoted bitmap needs no such release because the commit-merge consumes its
 * layer via {@code getStateCopyWithCommittedChanges} (the same call every kept-multi bucket uses).
 *
 * The tree participates fully in the MVCC framework as a {@link TransactionalLayerProducer}. It depends only on the
 * {@link io.evitadb.index.bitmap} layer and emits a NEUTRAL {@link BucketCursor} so a later task can adapt it to the
 * `ValueToRecord` flyweight surface without this class taking a dependency on `io.evitadb.index.invertedIndex`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NotThreadSafe
public class TransactionalBucketBPlusTree<K extends Comparable<K>> implements
	IntRecordBucketTree<K>,
	LongPayloadBucketTree<K>,
	DirtyScopeValidator
{
	@Serial private static final long serialVersionUID = -2030749900648110509L;
	/**
	 * Sentinel page sequence marking a node that has not yet been assigned a persistence page under the granular
	 * FilterIndex layout. A freshly built node (initial empty leaf, split-born sibling, new root) and
	 * a node loaded before its page is restored all start here; the write path allocates a concrete page (always
	 * non-negative) at emission, and the load path restores each node's persisted page. Equal to the owner-resident
	 * {@link io.evitadb.index.page.PageStreamRegistry#NO_PAGE} sentinel (both engine-side).
	 */
	public static final int UNASSIGNED_PAGE_SEQUENCE = -1;
	/**
	 * Sentinel returned by the leaf add methods when the record joined an EXISTING bucket, i.e. no new bucket key was
	 * inserted. Any other (non-negative) return is the slot index the new bucket landed on, which
	 * {@link #assertInsertBoundaries(Cursor, Comparable, int)} consumes instead of re-deriving "was this a head / tail
	 * insert?" by decoding and comparing the leaf's boundary keys.
	 */
	private static final int NO_NEW_BUCKET = -1;
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
	 * Factory that creates a fresh empty {@link RecordColumn} of the kind chosen for this tree's single-record payload.
	 * It picks a primitive {@link IntRecordColumn} ({@code int[]}, the 4-byte default) for the inverted / owner-unique
	 * indexes. Threaded into every empty-leaf / split-target creation so the whole tree shares one payload kind, exactly
	 * as {@link #valueColumnFactory} threads the key-column kind.
	 */
	@Nonnull private final RecordColumnFactory recordColumnFactory;
	/**
	 * Whether this tree stores a single `long` payload per key ({@link RecordColumnFactory#LONG}) instead of the default
	 * `int` record-set payload ({@link RecordColumnFactory#INT}). Derived once at construction from
	 * {@link #recordColumnFactory}. A long-payload tree is genuinely UNIQUE (one payload per key, never promoted to the
	 * overflow bitmap) and is mutated only through the `*LongRecord*` API; the int record-set API
	 * ({@link #addRecord}/{@link #removeRecord}/{@link #getRecordsEqualTo}) and the long API are mutually exclusive and
	 * each cheaply guards against the wrong mode via {@link Assert#isPremiseValid}.
	 */
	private final boolean longPayload;
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
			RecordColumnFactory.INT,
			new BPlusLeafTreeNode<>(
				valueColumnFactory.create(valueBlockSize),
				RecordColumnFactory.INT.create(valueBlockSize),
				comparator,
				true
			),
			0
		);
	}

	/**
	 * Builds a value→single-`long` (UNIQUE) bucket tree: each key holds exactly one `long` payload and is NEVER promoted
	 * to the overflow bitmap. This is the additive sibling of the 7-arg {@link ValueColumnFactory}-aware constructor — it
	 * builds the tree identically, but selects the 8-byte {@link RecordColumnFactory#LONG} payload column (both as the
	 * tree's record factory and for the initial root leaf's payload column) so the bucket stores a packed `long` (e.g. a
	 * `(entityType, pk)` join for the global-unique value→entity index) instead of an `int` record set. The tree is then
	 * mutated exclusively through the `*LongRecord*` API ({@link #addLongRecord}, {@link #getLongRecordEqualTo},
	 * {@link #removeLongRecord}); the int record-set API is rejected on a long tree (and vice versa).
	 *
	 * @param valueBlockSize           maximum number of buckets in a leaf node
	 * @param minValueBlockSize        minimum number of buckets in a leaf node
	 * @param internalNodeBlockSize    maximum number of keys in an internal node
	 * @param minInternalNodeBlockSize minimum number of keys in an internal node
	 * @param keyType                  the type of the keys (bucket values) stored in the tree
	 * @param comparator               optional comparator defining the key order; `null` ⇒ natural order
	 * @param valueColumnFactory       the factory choosing the leaf key-column representation
	 * @param <K>                      the key (value) type
	 * @return a new empty long-payload bucket tree
	 */
	@Nonnull
	public static <K extends Comparable<K>> TransactionalBucketBPlusTree<K> withLongPayload(
		int valueBlockSize,
		int minValueBlockSize,
		int internalNodeBlockSize,
		int minInternalNodeBlockSize,
		@Nonnull Class<K> keyType,
		@Nullable Comparator<K> comparator,
		@Nonnull ValueColumnFactory<K> valueColumnFactory
	) {
		return new TransactionalBucketBPlusTree<>(
			valueBlockSize,
			minValueBlockSize,
			internalNodeBlockSize,
			minInternalNodeBlockSize,
			keyType,
			comparator,
			valueColumnFactory,
			RecordColumnFactory.LONG,
			new BPlusLeafTreeNode<>(
				valueColumnFactory.create(valueBlockSize),
				RecordColumnFactory.LONG.create(valueBlockSize),
				comparator,
				true
			),
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
		@Nonnull RecordColumnFactory recordColumnFactory,
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
			!TransactionalStateProducer.class.isAssignableFrom(keyType),
			"Key type cannot implement TransactionalStateProducer."
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
		this.recordColumnFactory = recordColumnFactory;
		this.longPayload = recordColumnFactory == RecordColumnFactory.LONG;
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
	 * Bulk-populates this tree's root as a single leaf page from an already-known, ascending-ordered key/payload
	 * set, in one pass — the load-time counterpart to {@code count} sequential {@link #addRecord}/
	 * {@link #addLongRecord} calls. Intended for the persisted single-leaf-page load path
	 * ({@code fromPersistedPages} implementations), where a page's full content is already deserialized up front
	 * and replaying it through the incremental single-record API would pay avoidable Θ(count²) cost — see
	 * {@link ValueColumn#bulkLoad} / {@link RecordColumn#bulkLoad} for why (each incremental insert shifts its
	 * column's tail out to {@link #valueBlockSize}, and {@link FrontCodedStringColumn}'s incremental insert
	 * additionally re-encodes its entire blob on every call). Requires this tree to still be in its
	 * just-constructed, empty state — the bulk-built leaf unconditionally REPLACES the current root via
	 * {@link #setRoot}.
	 *
	 * Every key maps to exactly one payload; this method never builds an overflow (multi-record) bucket, so it is
	 * only valid for a tree whose persisted page structurally cannot hold a value shared by more than one record
	 * (a global-unique or owner-unique index page — see their {@code fromPersistedPages}). A page that CAN hold
	 * multi-record buckets (e.g. an inverted index's) must use {@link #bulkLoadPage} instead.
	 *
	 * @param keys     the ascending-ordered, distinct keys to load; only {@code keys[0, count)} are read
	 * @param payloads the payload for each key, aligned by index with {@code keys}; only {@code payloads[0, count)}
	 *                 are read (narrowed to {@code int} internally on an int-payload tree)
	 * @param count    the number of live entries ({@code 1 <= count <= valueBlockSize} — a page never exceeds a
	 *                 leaf's capacity)
	 */
	public void bulkLoadSingleRecordPage(@Nonnull Object[] keys, @Nonnull long[] payloads, int count) {
		bulkLoadPage(keys, payloads, null, count);
	}

	/**
	 * Bulk-populates this tree's root as a single leaf page from an already-known, ascending-ordered key/bucket
	 * set, in one pass — the overflow-aware sibling of {@link #bulkLoadSingleRecordPage}, for trees whose persisted
	 * page CAN hold a value shared by more than one record (e.g. {@code InvertedIndex}). Each key maps to either a
	 * single payload ({@code overflow[i] == null}, {@code payloads[i]} is read) or a pre-built multi-record bitmap
	 * ({@code overflow[i] != null}, {@code payloads[i]} is a don't-care, matching the leaf's own contract for a
	 * promoted bucket's primitive slot — see {@link RecordColumn}'s javadoc).
	 *
	 * @param keys     the ascending-ordered, distinct keys to load; only {@code keys[0, count)} are read
	 * @param payloads the single-record payload for each key that is NOT overflow-promoted; only
	 *                 {@code payloads[0, count)} are read, and only where the aligned {@code overflow} slot is null
	 * @param overflow per-key pre-built multi-record bitmap, or {@code null} at a slot whose key holds a single
	 *                 record; pass {@code null} entirely if no key in this page is ever multi-record
	 * @param count    the number of live entries ({@code 1 <= count <= valueBlockSize} — a page never exceeds a
	 *                 leaf's capacity)
	 */
	@SuppressWarnings("unchecked")
	public void bulkLoadPage(
		@Nonnull Object[] keys, @Nonnull long[] payloads, @Nullable TransactionalBitmap[] overflow, int count
	) {
		Assert.isPremiseValid(count > 0, "A bulk-loaded page must hold at least one entry.");
		Assert.isPremiseValid(
			count <= this.valueBlockSize, "A page can never exceed the leaf block size (" + this.valueBlockSize + ")."
		);
		for (int i = 1; i < count; i++) {
			final K previous = (K) keys[i - 1];
			final K current = (K) keys[i];
			final int comparison = this.comparator != null
				? this.comparator.compare(previous, current) : previous.compareTo(current);
			Assert.isPremiseValid(
				comparison < 0, "Bulk-loaded keys must be strictly ascending and distinct, found '"
					+ previous + "' before '" + current + "' at index " + i + "."
			);
		}
		final ValueColumn<K> keyColumn = this.valueColumnFactory.create(this.valueBlockSize);
		keyColumn.bulkLoad(keys, count);
		final RecordColumn recordColumn = this.recordColumnFactory.create(this.valueBlockSize);
		recordColumn.bulkLoad(payloads, count);
		final TransactionalBitmap[] paddedOverflow;
		if (overflow == null) {
			paddedOverflow = null;
		} else {
			paddedOverflow = overflow.length == this.valueBlockSize
				? overflow : Arrays.copyOf(overflow, this.valueBlockSize);
		}
		setRoot(new BPlusLeafTreeNode<>(keyColumn, recordColumn, paddedOverflow, count - 1, this.comparator, true));
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
	@Override
	public void addRecord(@Nonnull K value, int pk) {
		Assert.isPremiseValid(!this.longPayload, "Int record-set API is not available on a long-payload tree!");
		final Cursor<K> cursor = createCursor(value);
		final BPlusLeafTreeNode<K> leaf = cursor.leafNode();
		final int insertedAt = leaf.addRecord(value, pk);
		if (insertedAt != NO_NEW_BUCKET) {
			this.size.set(size() + 1);
			// op-time boundary-mutation asserts run on the new-bucket branch before the (possible) split, while the
			// cursor still reflects the pre-split spine — a mis-routed new bucket corrupts cross-leaf order with no
			// structural op firing
			assertInsertBoundaries(cursor, value, insertedAt);
			// register the dirtied leaf as a dirty-scope token for this transaction
			registerDirtyLeafInScope(leaf);
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
	@Override
	public void addRecord(@Nonnull K value, @Nonnull int... pks) {
		Assert.isPremiseValid(!this.longPayload, "Int record-set API is not available on a long-payload tree!");
		Assert.isTrue(pks.length > 0, "Record ids must be not null and non-empty!");
		final Cursor<K> cursor = createCursor(value);
		final BPlusLeafTreeNode<K> leaf = cursor.leafNode();
		final int insertedAt = leaf.addRecords(value, pks);
		if (insertedAt != NO_NEW_BUCKET) {
			this.size.set(size() + 1);
			// op-time boundary-mutation asserts — see addRecord(K, int); the new-bucket branch validates cross-leaf
			// order before the (possible) split
			assertInsertBoundaries(cursor, value, insertedAt);
			// register the dirtied leaf as a dirty-scope token for this transaction
			registerDirtyLeafInScope(leaf);
		}
		if (leaf.isFull()) {
			splitLeafNode(leaf, cursor);
		}
	}

	/**
	 * Removes one or multiple record ids from the bucket with the specified `value`. If no such bucket exists, or it
	 * contains none of the passed ids, nothing happens. A single-record bucket is deleted when its sole id is removed.
	 * A bitmap bucket has the ids removed in place; when it drops to zero records the bucket is deleted (and its
	 * bitmap's transactional layer released). A bitmap reduced to exactly one record is not demoted mid-transaction;
	 * it is reverted to the primitive single form at the leaf commit-merge (see the class javadoc).
	 *
	 * @param value the value identifying the bucket
	 * @param pks   the record ids to remove; must be non-empty (may contain negative ids)
	 */
	@Override
	public void removeRecord(@Nonnull K value, @Nonnull int... pks) {
		Assert.isPremiseValid(!this.longPayload, "Int record-set API is not available on a long-payload tree!");
		Assert.isTrue(pks.length > 0, "Record ids must be not null and non-empty!");
		final Cursor<K> cursor = createCursor(value);
		final BPlusLeafTreeNode<K> leaf = cursor.leafNode();

		final boolean headRemoved = leaf.size() > 1 && value.equals(leaf.keyAt(0));
		if (leaf.removeRecords(value, pks)) {
			this.size.set(size() - 1);
			// register the dirtied leaf as a dirty-scope token for this transaction: a removal
			// narrows the leaf's key range, but a later reverted layer could restore the wider pre-transaction range
			// and overlap a neighbour that split during the transaction — so removals are validated too
			registerDirtyLeafInScope(leaf);
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
	@Override
	public Bitmap getRecordsEqualTo(@Nullable K value) {
		Assert.isPremiseValid(!this.longPayload, "Int record-set API is not available on a long-payload tree!");
		if (value == null) {
			return EmptyBitmap.INSTANCE;
		}
		return createCursor(value).leafNode().getRecords(value);
	}

	/**
	 * Computes the record id that precedes the would-be position of `recordId` under `value` in the global sort order
	 * this tree defines: buckets ascend by value, records within a bucket ascend by id. Answered bucket-locally in a
	 * single descent — the anchor is the greatest lower id in `value`'s own bucket, otherwise the last record of the
	 * closest preceding bucket (crossing to the preceding leaf when necessary), otherwise
	 * {@link EvitaDataTypes#RESERVED_PRIMARY_KEY} meaning the record belongs first — a value evitaDB never assigns
	 * to an entity, so it cannot be mistaken for a genuine anchor. The answer is insensitive to whether `recordId` is already present in the
	 * bucket, so callers may issue it before or after the record's own insertion.
	 *
	 * @param value    the value the inserted record is associated with
	 * @param recordId the record id being inserted
	 * @return the record id to insert after, or {@link EvitaDataTypes#RESERVED_PRIMARY_KEY} when the record belongs
	 * first
	 */
	public int computePreviousRecord(@Nonnull K value, int recordId) {
		Assert.isPremiseValid(!this.longPayload, "Int record-set API is not available on a long-payload tree!");
		final Cursor<K> cursor = createCursor(value);
		final int inLeafAnchor = cursor.leafNode().previousRecord(value, recordId);
		if (inLeafAnchor != EvitaDataTypes.RESERVED_PRIMARY_KEY) {
			return inLeafAnchor;
		}
		// the predecessor lives in the preceding leaf: climb to the first ancestor with a previous sibling — its
		// previous-node cursor rebuilds the path below as the rightmost descent, i.e. exactly the preceding leaf
		CursorWithLevel<K> levelCursor = cursor.toCursorWithLevel();
		while (levelCursor != null) {
			final CursorWithLevel<K> previousNodeCursor = levelCursor.getCursorForPreviousNode();
			if (previousNodeCursor != null) {
				return new Cursor<>(previousNodeCursor.path()).leafNode().lastRecord();
			}
			levelCursor = levelCursor.toParentLevel();
		}
		// no preceding bucket anywhere - the record belongs to the very first position
		return EvitaDataTypes.RESERVED_PRIMARY_KEY;
	}

	/**
	 * Adds a value→`long` bucket holding exactly one payload. The tree is UNIQUE: the bucket is never promoted to the
	 * overflow bitmap. The value MUST be absent — uniqueness is enforced by the caller (the global-unique index), so a
	 * key already present here is a programming error and throws a {@link GenericEvitaInternalError}. Only available on a
	 * tree built via {@link #withLongPayload}.
	 *
	 * @param value   the value identifying the bucket
	 * @param payload the lone `long` payload to store
	 */
	@Override
	public void addLongRecord(@Nonnull K value, long payload) {
		Assert.isPremiseValid(this.longPayload, "Long-payload API is only available on a long-payload tree!");
		final Cursor<K> cursor = createCursor(value);
		final BPlusLeafTreeNode<K> leaf = cursor.leafNode();
		final int insertedAt = leaf.addLongRecord(value, payload);
		this.size.set(size() + 1);
		// op-time boundary-mutation asserts — a long-payload add always inserts a new bucket (or throws on a duplicate),
		// so validate cross-leaf order unconditionally before the (possible) split
		assertInsertBoundaries(cursor, value, insertedAt);
		// register the dirtied leaf as a dirty-scope token for this transaction
		registerDirtyLeafInScope(leaf);
		if (leaf.isFull()) {
			splitLeafNode(leaf, cursor);
		}
	}

	/**
	 * Returns the `long` payload of the bucket identified by the given value, or {@link OptionalLong#empty()} when the
	 * value is absent (or `null`). Only available on a tree built via {@link #withLongPayload}.
	 *
	 * @param value the value to look up (may be null ⇒ empty)
	 * @return the bucket's payload, or empty when absent
	 */
	@Nonnull
	@Override
	public OptionalLong getLongRecordEqualTo(@Nullable K value) {
		Assert.isPremiseValid(this.longPayload, "Long-payload API is only available on a long-payload tree!");
		if (value == null) {
			return OptionalLong.empty();
		}
		final BPlusLeafTreeNode<K> leaf = createCursor(value).leafNode();
		final int index = leaf.getValueIndex(value);
		return index < 0 ? OptionalLong.empty() : OptionalLong.of(leaf.longRecordAt(index));
	}

	/**
	 * Removes the value→`long` bucket identified by the given value (deleting the whole bucket), rebalancing the tree as
	 * needed. Only available on a tree built via {@link #withLongPayload}.
	 *
	 * @param value the value identifying the bucket to remove
	 * @return true if a bucket was removed, false when the value was absent
	 */
	@Override
	public boolean removeLongRecord(@Nonnull K value) {
		Assert.isPremiseValid(this.longPayload, "Long-payload API is only available on a long-payload tree!");
		final Cursor<K> cursor = createCursor(value);
		final BPlusLeafTreeNode<K> leaf = cursor.leafNode();

		final boolean headRemoved = leaf.size() > 1 && value.equals(leaf.keyAt(0));
		if (leaf.removeLongRecord(value)) {
			this.size.set(size() - 1);
			// register the dirtied leaf as a dirty-scope token for this transaction: a removal
			// narrows the leaf's key range, but a later reverted layer could restore the wider pre-transaction range
			// and overlap a neighbour that split during the transaction — so removals are validated too
			registerDirtyLeafInScope(leaf);
			// the head of the leaf may have been removed, update parent keys accordingly
			if (headRemoved) {
				updateParentKeys(cursor.toCursorWithLevel());
			}
			consolidate(cursor);
			return true;
		}
		return false;
	}

	/**
	 * Returns the number of records associated with the given value, without materializing a bitmap. Returns 1 for a
	 * single-record bucket, the bitmap size for a multi-record bucket, and 0 when the value is absent.
	 *
	 * @param value the value to look up (may be null ⇒ 0)
	 * @return the cardinality of the bucket
	 */
	@Override
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
	@Override
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
	@Override
	public int bucketCount() {
		return size();
	}

	/**
	 * Returns the total number of records held across all buckets (the sum of all bucket cardinalities).
	 *
	 * @return the total record count
	 */
	@Override
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
	@Override
	public int size() {
		return Objects.requireNonNull(this.size.get());
	}

	/**
	 * Returns a forward NEUTRAL cursor over the buckets, ordered ascending by value.
	 *
	 * @return a forward bucket cursor
	 */
	@Nonnull
	@Override
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
	@Override
	public BucketCursor<K> cursor(@Nonnull K value) {
		return new ForwardBucketCursor<>(createCursor(value), value);
	}

	/**
	 * Returns a reverse NEUTRAL cursor over the buckets, ordered descending by value.
	 *
	 * @return a reverse bucket cursor
	 */
	@Nonnull
	@Override
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
		// capture the in-transaction root BEFORE dropping the root reference's own layer - otherwise getRoot() would
		// fall back to the committed root and the node-graph recursion would miss every node created during this
		// transaction (e.g. split offspring), leaking their layers during the commit sweep
		final BPlusTreeNode<K, ?> theRoot = getRoot();
		this.size.removeLayer(transactionalLayer);
		this.root.removeLayer(transactionalLayer);
		removeLayerRecursively(theRoot, transactionalLayer);
	}

	@Nonnull
	@Override
	public TransactionalBucketBPlusTree<K> createCopyWithMergedTransactionalMemory(
		@Nullable Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		final BPlusTreeNode<K, ?> theRoot = transactionalLayer.getStateCopyWithCommittedChanges(this.root)
			.orElseThrow();
		final TransactionalBucketBPlusTree<K> merged;
		if (theRoot instanceof BPlusLeafTreeNode<?> leafNode) {
			//noinspection unchecked
			final BPlusLeafTreeNode<K> theLeafNode = (BPlusLeafTreeNode<K>) leafNode;
			merged = new TransactionalBucketBPlusTree<>(
				this.valueBlockSize, this.minValueBlockSize,
				this.internalNodeBlockSize, this.minInternalNodeBlockSize,
				this.keyType,
				this.comparator,
				this.valueColumnFactory,
				this.recordColumnFactory,
				transactionalLayer.getStateCopyWithCommittedChanges(theLeafNode),
				transactionalLayer.getStateCopyWithCommittedChanges(this.size).orElseThrow()
			);
		} else if (theRoot instanceof BPlusInternalTreeNode<?> internalNode) {
			//noinspection unchecked
			merged = new TransactionalBucketBPlusTree<>(
				this.valueBlockSize, this.minValueBlockSize,
				this.internalNodeBlockSize, this.minInternalNodeBlockSize,
				this.keyType,
				this.comparator,
				this.valueColumnFactory,
				this.recordColumnFactory,
				transactionalLayer.getStateCopyWithCommittedChanges((BPlusInternalTreeNode<K>) internalNode),
				transactionalLayer.getStateCopyWithCommittedChanges(this.size).orElseThrow()
			);
		} else {
			throw new GenericEvitaInternalError("Unknown node type: " + theRoot);
		}
		// post-replay (merge-time): before this merged version can propagate to the live view, re-derive the cross-leaf boundary
		// invariants for every leaf this transaction dirtied — against the freshly merged structure (plain reads;
		// the merged nodes are fresh or unchanged-and-layer-free, so the descent never consults a diff layer).
		// The registry holds boundary keys, not nodes; each key routes to whatever leaf currently owns it in the
		// merged tree, and that landed leaf is validated on its own re-derived boundaries.
		final Set<Object> dirtyScope = transactionalLayer.getDirtyScopeTokens(this);
		if (!dirtyScope.isEmpty()) {
			merged.validateDirtyScope(dirtyScope);
		}
		return merged;
	}

	/**
	 * Enumerates the tree's leaf nodes in ascending key order. This is the page-emission foundation of the granular
	 * FilterIndex storage layout: each leaf becomes one persisted page, so the write path walks this list to discover
	 * what to store. The returned leaves are the live node instances captured as a read-only snapshot — the caller
	 * must not mutate them. Ordering follows the natural left-to-right child order of the internal spine, which is the
	 * very same order the bucket {@link #cursor()} walks.
	 *
	 * @return the ordered list of leaf nodes; never empty (an empty tree has a single empty leaf as its root)
	 */
	@Nonnull
	public List<BPlusLeafTreeNode<K>> enumerateLeaves() {
		final List<BPlusLeafTreeNode<K>> leaves = new ArrayList<>();
		collectLeaves(getRoot(), leaves);
		return leaves;
	}

	/**
	 * Returns whether the tree's root is an internal (routing) node, i.e. the tree spans more than one leaf. This is the
	 * granular-storage paging predicate: a single-leaf tree is persisted as one inline part (paging it
	 * would be pure overhead), while a multi-leaf tree is persisted as individual leaf pages.
	 *
	 * @return true when the root is internal (≥ 2 leaves), false when the root itself is the only leaf
	 */
	@Override
	public boolean isRootInternal() {
		return getRoot() instanceof BPlusInternalTreeNode;
	}

	/**
	 * A live, write-path handle over a single leaf page: it exposes the leaf's logical persistence page
	 * sequence (carried across commits by {@link BPlusTreeNode#getPageSequence()}), lets the emitter assign a freshly
	 * allocated page to a not-yet-paged (split-born or fresh) leaf, and hands out a leaf-scoped {@link BucketCursor} the
	 * emitter materializes the page contents from. The handles are returned in ascending key order — the very order the
	 * persisted leaf-page list records — by {@link #leafPageHandles()}. The page-bookkeeping half (page sequence, dirty
	 * flag, page stamp) lives on the value-agnostic {@link PagedLeafHandle} super-interface this extends; this interface
	 * adds the leaf-scoped {@link BucketCursor} access.
	 *
	 * @param <K> the bucket key type
	 */
	public interface LeafPageHandle<K extends Comparable<K>> extends PagedLeafHandle {

		/**
		 * Returns a fresh leaf-scoped cursor over this leaf's buckets in ascending value order. The cursor reads the
		 * live node columns (the transaction-aware committed-with-changes structure reached from the current root), so
		 * the emitter sees read-your-writes content.
		 *
		 * @return a fresh single-leaf bucket cursor
		 */
		@Nonnull
		BucketCursor<K> cursor();
	}

	/**
	 * Returns one {@link LeafPageHandle} per leaf, in ascending key order — the page-emission view of the tree for the
	 * granular FilterIndex write path. The handles wrap the live leaf nodes reached from the current
	 * (transaction-aware) root, so stamping a page sequence through a handle mutates the node the merge will carry
	 * forward, and the cursors materialize read-your-writes content.
	 *
	 * @return the ordered leaf-page handles; never empty
	 */
	@Nonnull
	@Override
	public List<LeafPageHandle<K>> leafPageHandles() {
		final List<BPlusLeafTreeNode<K>> leaves = enumerateLeaves();
		final List<LeafPageHandle<K>> handles = new ArrayList<>(leaves.size());
		for (final BPlusLeafTreeNode<K> leaf : leaves) {
			handles.add(new LeafPageHandleImpl<>(leaf));
		}
		return handles;
	}

	/**
	 * Default {@link LeafPageHandle} backed by a single live leaf node.
	 *
	 * @param <M> the bucket key type
	 */
	private static final class LeafPageHandleImpl<M extends Comparable<M>> implements LeafPageHandle<M> {
		@Nonnull private final BPlusLeafTreeNode<M> leaf;

		LeafPageHandleImpl(@Nonnull BPlusLeafTreeNode<M> leaf) {
			this.leaf = leaf;
		}

		@Override
		public int getPageSequence() {
			return this.leaf.getPageSequence();
		}

		@Override
		public boolean isDirty() {
			return this.leaf.isDirty();
		}

		@Override
		public void clearDirty() {
			this.leaf.clearDirty();
		}

		@Override
		public void setPageSequence(int pageSequence) {
			this.leaf.setPageSequence(pageSequence);
		}

		@Nonnull
		@Override
		public BucketCursor<M> cursor() {
			return new SingleLeafBucketCursor<>(this.leaf);
		}
	}

	/**
	 * A {@link BucketCursor} restricted to one leaf node, reading its columns directly (the same getters
	 * {@link ForwardBucketCursor} reads). Used by the granular write path to materialize one leaf page at a time
	 * without walking the whole tree.
	 *
	 * @param <M> the bucket key type
	 */
	private static final class SingleLeafBucketCursor<M extends Comparable<M>> implements BucketCursor<M> {
		@Nonnull private final ValueColumn<M> keys;
		@Nonnull private final RecordColumn records;
		@Nullable private final TransactionalBitmap[] overflow;
		private final int peek;
		private final long leafId;
		private int currentIndex = -1;
		private boolean positioned;

		SingleLeafBucketCursor(@Nonnull BPlusLeafTreeNode<M> leaf) {
			this.keys = leaf.getKeyColumn();
			this.records = leaf.getRecords();
			this.overflow = leaf.getOverflow();
			this.peek = leaf.getPeek();
			this.leafId = leaf.getId();
		}

		@Override
		public boolean next() {
			if (this.currentIndex < this.peek) {
				this.currentIndex++;
				this.positioned = true;
				return true;
			}
			this.positioned = false;
			return false;
		}

		@Nonnull
		@Override
		public M value() {
			Assert.isPremiseValid(this.positioned, "Cursor is not positioned at a bucket!");
			return this.keys.keyAt(this.currentIndex);
		}

		@Override
		public boolean isSingle() {
			Assert.isPremiseValid(this.positioned, "Cursor is not positioned at a bucket!");
			return this.overflow == null || this.overflow[this.currentIndex] == null;
		}

		@Override
		public int singleRecordId() {
			Assert.isPremiseValid(this.positioned, "Cursor is not positioned at a bucket!");
			return this.records.intAt(this.currentIndex);
		}

		@Override
		public long longRecordId() {
			Assert.isPremiseValid(this.positioned, "Cursor is not positioned at a bucket!");
			return this.records.longAt(this.currentIndex);
		}

		@Nonnull
		@Override
		public Bitmap records() {
			Assert.isPremiseValid(this.positioned, "Cursor is not positioned at a bucket!");
			if (this.overflow != null && this.overflow[this.currentIndex] != null) {
				return this.overflow[this.currentIndex];
			}
			return new SingleRecordBitmap(this.records.intAt(this.currentIndex));
		}

		@Override
		public int size() {
			Assert.isPremiseValid(this.positioned, "Cursor is not positioned at a bucket!");
			if (this.overflow != null && this.overflow[this.currentIndex] != null) {
				return this.overflow[this.currentIndex].size();
			}
			return 1;
		}

		@Override
		public long currentLeafId() {
			Assert.isPremiseValid(this.positioned, "Cursor is not positioned at a bucket!");
			return this.leafId;
		}
	}

	/**
	 * Re-assembles a B+ tree from a pre-built, ascending-ordered sequence of leaf nodes, deriving the internal routing
	 * spine bottom-up. This is the inverse of {@link #enumerateLeaves()} and the foundation of the granular FilterIndex
	 * load path: leaf pages are read straight from disk and the spine is reconstructed here in a single pass rather
	 * than rebuilt by replaying per-record inserts.
	 *
	 * Each internal level groups the level below into nodes of at most {@link #internalNodeBlockSize}` + 1` children,
	 * distributed as evenly as possible so that every node — except the root, which is exempt from the minimum — meets
	 * the minimum occupancy. The even split is always feasible because the tree invariant guarantees the maximum
	 * fan-out is at least twice the minimum. Separators are the left boundary keys of the children, honoring the
	 * tree's separator-from-first-key invariant, so no separators need be persisted. The assembled tree reuses this
	 * tree's block-size configuration, key type, comparator and value-column factory.
	 *
	 * WARNING: the assembled tree REUSES (aliases) the supplied leaf node instances — it does not copy them. This is
	 * intended for the load path (where the leaves are freshly built from disk pages and owned by no other tree) and
	 * for read-only round-trips. Do NOT keep mutating the source tree (or the leaves) after handing them here unless
	 * the source is being discarded, or the two trees will share — and corrupt — leaf state.
	 *
	 * @param orderedLeaves the leaves in ascending key order (as returned by {@link #enumerateLeaves()}); must be non-empty
	 * @return a new tree whose buckets are exactly those held by the supplied leaves
	 */
	@Nonnull
	public TransactionalBucketBPlusTree<K> assembleFromLeaves(@Nonnull List<BPlusLeafTreeNode<K>> orderedLeaves) {
		Assert.isPremiseValid(!orderedLeaves.isEmpty(), "At least one leaf node is required to assemble a tree.");
		// the tree size is the total bucket count across all leaves (size() == peek + 1 per leaf)
		int totalBuckets = 0;
		for (BPlusLeafTreeNode<K> orderedLeaf : orderedLeaves) {
			totalBuckets += orderedLeaf.size();
		}
		final BPlusTreeNode<K, ?> assembledRoot = buildSpine(new ArrayList<>(orderedLeaves));
		return new TransactionalBucketBPlusTree<>(
			this.valueBlockSize, this.minValueBlockSize,
			this.internalNodeBlockSize, this.minInternalNodeBlockSize,
			this.keyType,
			this.comparator,
			this.valueColumnFactory,
			this.recordColumnFactory,
			assembledRoot,
			totalBuckets
		);
	}

	/**
	 * Re-assembles a B+ tree from a sequence of single-leaf source trees — one per persisted leaf page — preserving the
	 * original leaf boundaries exactly and stamping each leaf with its persisted page sequence. This is the
	 * boundary-stable load path for the granular FilterIndex: a caller that owns the bucket
	 * representation (the inverted index) builds one single-leaf tree per persisted page via the public
	 * {@link #addRecord} surface, then hands them here in ascending key order together with their page sequences. The
	 * resulting tree's leaf *i* is byte-identical to persisted page *i*, so the change-detection baseline restored
	 * alongside it makes the first post-restart commit a true no-op for unchanged leaves (no full re-pagination).
	 *
	 * Each source tree MUST consist of a single leaf (a page never exceeds a leaf's capacity); the leaf node is aliased
	 * into the assembled tree exactly as in {@link #assembleFromLeaves} — do not keep mutating the source trees
	 * afterwards.
	 *
	 * The reassembled leaves are validated for strict cross-leaf key order BEFORE the spine is built: the last key of a
	 * key-bearing leaf must sort strictly before the first key of the next key-bearing leaf (per the tree's comparator or
	 * the keys' natural order). A violation means the persisted page list carries a stale leaf-page twin (a frozen
	 * snapshot of a leaf persisted next to the page that superseded it) or other index corruption; it is not silently
	 * repaired but reported via {@link #assertCrossLeafBoundaries} — see the defensive-design rationale there.
	 *
	 * @param orderedSingleLeafTrees the per-page single-leaf trees in ascending key order
	 * @param pageSequences          the persisted page sequence for each tree, positionally aligned; same length
	 * @param structureDescription   a full identification of the index for diagnostics, reading like a noun phrase after
	 *                               `persisted ` (e.g. `inverted index for type \`X\``)
	 * @return a new tree whose leaves are exactly those of the supplied trees, each stamped with its page sequence
	 * @throws GenericEvitaInternalError when the persisted leaf pages violate strict cross-leaf key order
	 */
	@Nonnull
	public TransactionalBucketBPlusTree<K> assembleFromSingleLeafTrees(
		@Nonnull List<TransactionalBucketBPlusTree<K>> orderedSingleLeafTrees,
		@Nonnull int[] pageSequences,
		@Nonnull String structureDescription
	) {
		Assert.isPremiseValid(
			orderedSingleLeafTrees.size() == pageSequences.length,
			"The number of single-leaf trees must match the number of page sequences."
		);
		final List<BPlusLeafTreeNode<K>> leaves = new ArrayList<>(pageSequences.length);
		for (int i = 0; i < pageSequences.length; i++) {
			final BPlusTreeNode<K, ?> root = orderedSingleLeafTrees.get(i).getRoot();
			Assert.isPremiseValid(
				root instanceof BPlusLeafTreeNode,
				"Each persisted leaf page must rebuild to exactly one leaf."
			);
			//noinspection unchecked
			final BPlusLeafTreeNode<K> leaf = (BPlusLeafTreeNode<K>) root;
			leaf.setPageSequence(pageSequences[i]);
			leaves.add(leaf);
		}
		// validate cross-leaf key order BEFORE assembly, so the corruption diagnostic fires ahead of any left-boundary
		// separator invariant the spine builder would otherwise trip on with a less actionable message
		assertCrossLeafBoundaries(leaves, pageSequences, structureDescription);
		return assembleFromLeaves(leaves);
	}

	/**
	 * Validates that the supplied leaves are in strict cross-leaf key order: the last key of each key-bearing leaf must
	 * sort strictly before the first key of the next key-bearing leaf (per the tree's comparator, or the keys' natural
	 * order when no comparator is set). Empty leaves carry no key and impose no boundary constraint, so they are skipped
	 * when locating the previous key-bearing leaf.
	 *
	 * A paged index persists one storage part per B+ tree leaf plus a root part listing the ordered leaf-page sequence;
	 * the reload path re-assembles one in-memory leaf per persisted page. A writer race on a `@NotThreadSafe` warm-up
	 * session can leave a frozen stale snapshot of a leaf reachable next to the page that superseded it, and a one-shot
	 * flush persists BOTH — every subsequent reload then rebuilds a tree whose leaves overlap, silently serving corrupt
	 * data until it crashes later with a confusing signature far from the cause. Because the paged persistence layout has
	 * never shipped in a released version, no production catalog can carry such a twin; silently repairing one would
	 * contradict the defensive-design rule, so any detected overlap fails fast here with full diagnostics and an operator
	 * remediation hint.
	 *
	 * @param leaves               the reassembled leaves in persisted list order
	 * @param pageSequences        the root's ordered leaf-page sequence list, reported as overlap context on failure
	 * @param structureDescription a full identification of the index for diagnostics (see
	 *                             {@link #assembleFromSingleLeafTrees})
	 * @throws GenericEvitaInternalError when a leaf's last key does not sort strictly before the next leaf's first key
	 */
	private void assertCrossLeafBoundaries(
		@Nonnull List<BPlusLeafTreeNode<K>> leaves,
		@Nonnull int[] pageSequences,
		@Nonnull String structureDescription
	) {
		BPlusLeafTreeNode<K> previousKeyBearing = null;
		for (final BPlusLeafTreeNode<K> leaf : leaves) {
			final int peek = leaf.getPeek();
			if (peek < 0) {
				// empty leaf carries no key and cannot violate cross-leaf ordering
				continue;
			}
			final K[] keys = leaf.getKeys();
			// intra-leaf order: a serializer bug, truncated write or bit rot can leave a leaf whose interior keys
			// are out of order, while the cross-leaf walk alone would pass it — binary search inside such a leaf
			// then silently returns wrong answers. Assert each key sorts strictly after its predecessor within the
			// leaf (one comparison per key, once per load).
			for (int i = 1; i <= peek; i++) {
				if (compareKeys(keys[i - 1], keys[i], this.comparator) >= 0) {
					throw new GenericEvitaInternalError(
						"Corrupted persisted " + structureDescription + ": leaf-page sequence " +
							leaf.getPageSequence() + " has out-of-order keys — the key at position " + (i - 1) + " (" +
							keys[i - 1] + ") does not sort before the key at position " + i + " (" + keys[i] + "). This " +
							"is index corruption. Restore the catalog from a backup, or fully rebuild / reindex the " +
							"affected catalog."
					);
				}
			}
			if (previousKeyBearing != null) {
				final K previousLastKey = previousKeyBearing.getKeys()[previousKeyBearing.getPeek()];
				final K currentFirstKey = keys[0];
				if (compareKeys(previousLastKey, currentFirstKey, this.comparator) >= 0) {
					// error path only: gather the full overlap context (ranges, counts, containment) here, never on a
					// healthy load
					final K predecessorFirstKey = previousKeyBearing.getKeys()[0];
					final K successorLastKey = keys[peek];
					final boolean successorWithinPredecessor =
						compareKeys(currentFirstKey, predecessorFirstKey, this.comparator) >= 0 &&
							compareKeys(successorLastKey, previousLastKey, this.comparator) <= 0;
					throw new GenericEvitaInternalError(
						AbstractTransactionalBPlusTree.overlappingLeafPagesDiagnostic(
							structureDescription, pageSequences,
							previousKeyBearing.getPageSequence(), predecessorFirstKey, previousLastKey,
							previousKeyBearing.getPeek() + 1,
							leaf.getPageSequence(), currentFirstKey, successorLastKey, peek + 1,
							successorWithinPredecessor
						)
					);
				}
			}
			previousKeyBearing = leaf;
		}
	}

	/**
	 * Compares two keys using the supplied comparator when present, otherwise the keys' natural [Comparable] order.
	 * This is the single key-comparison mechanism every boundary / separator assert routes through (it mirrors the
	 * ternary used inside {@link #assertCrossLeafBoundaries}), so the object-keyed tree never falls back to a raw
	 * `>=` on object references.
	 *
	 * @param left       the left operand
	 * @param right      the right operand
	 * @param comparator the total order to use, or `null` for the keys' natural order
	 * @param <M>        the key type
	 * @return a negative, zero or positive int as `left` sorts before, equal to, or after `right`
	 */
	private static <M extends Comparable<M>> int compareKeys(
		@Nonnull M left, @Nonnull M right, @Nullable Comparator<M> comparator) {
		return comparator != null ? comparator.compare(left, right) : left.compareTo(right);
	}

	/**
	 * Tail-boundary assert. On any leaf mutation that RAISES the leaf's last key (a new bucket inserted at the
	 * tail, including the insert into an empty leaf), verifies the new last key still sorts strictly below the leaf's
	 * upper fence. The fence is the separator at the nearest ancestor whose descent was not into the rightmost child —
	 * which, by the separator-from-first-key invariant, equals the first key of the successor leaf, even when that
	 * successor lives under a different parent, grandparent, etc. A rightmost descent at every level means the leaf is
	 * the tree's last leaf and has no successor, so nothing is checked.
	 *
	 * A violation means a key was routed into a leaf whose successor should hold it (a search-path corruption); it would
	 * overlap the successor's page on flush, so it fails fast here. Zero allocation and no cross-parent leaf navigation:
	 * the fence is pure index arithmetic over the cursor arrays (the internal node's `getKeys()` returns its backing
	 * separator array, not a copy). Sequential bulk append descends into the rightmost child at every level, so the walk
	 * finds no fence and returns after a few comparisons.
	 *
	 * @param cursor     the descent path to the mutated leaf
	 * @param newLastKey the leaf's new last key after the mutation
	 * @throws GenericEvitaInternalError when the new last key does not sort strictly before the successor fence
	 */
	void assertTailBoundary(@Nonnull Cursor<K> cursor, @Nonnull K newLastKey) {
		final List<CursorLevel<K>> path = cursor.path();
		for (int level = path.size() - 1; level >= 1; level--) {
			final CursorLevel<K> cursorLevel = path.get(level);
			final int childIndex = cursorLevel.index();
			if (childIndex < cursorLevel.peek()) {
				// the ancestor whose children are this level's siblings holds the fence separator at childIndex
				final CursorLevel<K> ancestorLevel = path.get(level - 1);
				//noinspection unchecked
				final BPlusInternalTreeNode<K> ancestor =
					(BPlusInternalTreeNode<K>) ancestorLevel.siblings()[ancestorLevel.index()];
				final K fence = ancestor.getKeys()[childIndex];
				if (compareKeys(newLastKey, fence, this.comparator) >= 0) {
					throw boundaryMutationError("tail", newLastKey, "before the successor leaf boundary", fence);
				}
				return;
			}
		}
	}

	/**
	 * Head-boundary assert. On any leaf mutation that LOWERS the leaf's first key (a new bucket inserted at the
	 * head, including the insert into an empty leaf), verifies the new first key still sorts strictly above the
	 * predecessor leaf's last key. In a sound tree a head insert can only land on the tree's leftmost leaf (the
	 * separator-from-first-key invariant routes every key at-or-above the leaf's own first key), so this check passes
	 * trivially; it fires only on a mis-routed insert that undercuts a real predecessor.
	 *
	 * The predecessor's last key is the only meaningful operand: checking the parent separator would be circular, as the
	 * maintained invariant makes it equal the leaf's own first key.
	 *
	 * @param cursor      the descent path to the mutated leaf
	 * @param newFirstKey the leaf's new first key after the mutation
	 * @throws GenericEvitaInternalError when the new first key does not sort strictly after the predecessor boundary
	 */
	void assertHeadBoundary(@Nonnull Cursor<K> cursor, @Nonnull K newFirstKey) {
		final BPlusLeafTreeNode<K> predecessor = predecessorLeaf(cursor);
		if (predecessor == null) {
			// leftmost leaf — no predecessor to violate
			return;
		}
		final int predecessorPeek = predecessor.getPeek();
		if (predecessorPeek < 0) {
			// an empty predecessor carries no key (mirrors the load-time empty-leaf skip)
			return;
		}
		final K predecessorLastKey = predecessor.keyAt(predecessorPeek);
		if (compareKeys(predecessorLastKey, newFirstKey, this.comparator) >= 0) {
			throw boundaryMutationError(
				"head", newFirstKey, "after the predecessor leaf boundary", predecessorLastKey);
		}
	}

	/**
	 * Resolves the predecessor leaf of the cursor's leaf. Common case (`ci > 0`): the same-parent left sibling is the
	 * predecessor, read in O(1) from the cursor's already-materialized sibling array. Rare case (`ci == 0`): walk up to
	 * the nearest ancestor whose descent was into a non-leftmost child (the clamp ancestor), then follow that ancestor's
	 * left-neighbour subtree down its right spine to the predecessor leaf. That rare branch is O(height) and is never
	 * taken by sequential append (append never lowers a first key); only a random workload that undercuts a leaf minimum
	 * at a parent edge reaches it.
	 *
	 * @param cursor the descent path to the leaf whose predecessor is sought
	 * @return the predecessor leaf, or `null` when the cursor's leaf is the tree's leftmost leaf
	 */
	@Nullable
	private BPlusLeafTreeNode<K> predecessorLeaf(@Nonnull Cursor<K> cursor) {
		final List<CursorLevel<K>> path = cursor.path();
		final CursorLevel<K> leafLevel = path.get(path.size() - 1);
		final int leafIndex = leafLevel.index();
		if (leafIndex > 0) {
			//noinspection unchecked
			return (BPlusLeafTreeNode<K>) leafLevel.siblings()[leafIndex - 1];
		}
		for (int level = path.size() - 1; level >= 1; level--) {
			final CursorLevel<K> cursorLevel = path.get(level);
			final int childIndex = cursorLevel.index();
			if (childIndex > 0) {
				BPlusTreeNode<K, ?> node = cursorLevel.siblings()[childIndex - 1];
				while (node instanceof BPlusInternalTreeNode<?> internal) {
					//noinspection unchecked
					final BPlusInternalTreeNode<K> internalNode = (BPlusInternalTreeNode<K>) internal;
					node = internalNode.getChildren()[internalNode.getPeek()];
				}
				//noinspection unchecked
				return (BPlusLeafTreeNode<K>) node;
			}
		}
		return null;
	}

	/**
	 * Builds the shared boundary-mutation corruption error (offending key, the neighbour boundary it collides
	 * with, remediation hint).
	 *
	 * @param side        `tail` or `head` — which boundary the mutation changed
	 * @param boundaryKey the leaf's new boundary key that violates cross-leaf order
	 * @param relation    the ordering relation that was expected (e.g. `before the successor leaf boundary`)
	 * @param neighborKey the adjacent leaf boundary that `boundaryKey` failed to sort against
	 * @return the corruption error to throw
	 */
	@Nonnull
	private AbstractTransactionalBPlusTree.BPlusTreeCorruptedException boundaryMutationError(
		@Nonnull String side, @Nonnull K boundaryKey, @Nonnull String relation, @Nonnull K neighborKey) {
		return new AbstractTransactionalBPlusTree.BPlusTreeCorruptedException(
			"Corrupted in-memory B+ tree: a leaf's " + side + " boundary key " + boundaryKey + " does not sort " +
				relation + " (" + neighborKey + "). This indicates cross-leaf key overlap (a mis-routed insertion, a " +
				"reverted transactional layer, or a merge defect) that would overlap an adjacent leaf page on flush. " +
				"Restore the catalog from a backup, or fully rebuild / reindex the affected catalog."
		);
	}

	/**
	 * Registers the dirtied leaf's CURRENT first key as a dirty-scope probe key for this transaction. This standalone
	 * tree does not extend {@link AbstractTransactionalBPlusTree}, so it cannot reuse the base's static registration
	 * helper; it inlines the same semantics instead. A no-op when no transaction is active — warm-up bulk load and other
	 * non-transactional mutations have neither a registry nor a WAL, so the pre-commit (pre-WAL) and post-replay
	 * (merge-time) validations do not apply and pay nothing. Reads the key through the transaction-aware
	 * {@link BPlusLeafTreeNode#keyAt(int)}, so it captures the post-mutation boundary; an emptied leaf carries no
	 * boundary key and is skipped — nothing needs relocating by a key that no longer exists. Keeping the key rather than
	 * the leaf means nothing pins the leaf or its columns to the registry until commit.
	 *
	 * @param leaf the dirtied leaf node
	 */
	private void registerDirtyLeafInScope(@Nonnull BPlusLeafTreeNode<K> leaf) {
		final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
		// gate on the cheap checks before deriving (and boxing) the probe key: outside a transaction there is no
		// registry (warm-up bulk load pays nothing), and an emptied leaf has no boundary key to relocate by
		if (maintainer == null || leaf.getPeek() < 0) {
			return;
		}
		maintainer.registerDirtyScopeToken(this, leaf.keyAt(0));
	}

	/**
	 * Dirty-scope validation for this tree. For each registered probe key (the first key of a leaf this transaction
	 * dirtied, captured at op time) it descends from this tree's root to the leaf that key routes to, and re-derives
	 * both cross-leaf half-invariants on the leaf the descent actually landed on — reusing the op-time machinery with
	 * the leaf's ACTUAL boundary keys ({@link #assertTailBoundary} against the successor fence,
	 * {@link #assertHeadBoundary} against the predecessor's last key). Descents that land on an empty leaf are skipped
	 * (nothing to assert). Called on the live baseline tree for the pre-commit (pre-WAL) pass (the descent resolves diff
	 * layers) and on a freshly merged tree for the post-replay (merge-time) pass (plain reads); both use read-path
	 * accessors only.
	 *
	 * @param registeredProbeKeys the first keys registered for this tree; used only to relocate the leaf to check
	 * @throws AbstractTransactionalBPlusTree.BPlusTreeCorruptedException when a relocated leaf overlaps an adjacent leaf
	 */
	@Override
	public void validateDirtyScope(@Nonnull Collection<Object> registeredProbeKeys) {
		for (final Object token : registeredProbeKeys) {
			//noinspection unchecked
			final K probeKey = (K) token;
			final Cursor<K> cursor = createCursor(probeKey);
			final BPlusLeafTreeNode<K> leaf = cursor.leafNode();
			final int peek = leaf.getPeek();
			if (peek < 0) {
				// the descent landed on an empty leaf — validating it is sound and vacuous
				continue;
			}
			assertTailBoundary(cursor, leaf.keyAt(peek));
			assertHeadBoundary(cursor, leaf.keyAt(0));
		}
	}

	/**
	 * Separator-order belt. After a parent separator at `slot` is rewritten (head-key propagation), asserts strict
	 * local order against its existing neighbours. This catches stale/aliased internal-node state — the historical
	 * stale-leaf-page twin bugs were object aliasing, whose first symptom is a separator that no longer matches the live
	 * leaf it fronts. It is a belt, not the head-side check: it cannot detect a mis-routed head insert that keeps the
	 * separators individually ordered (that is what {@link #assertHeadBoundary} covers). The comparator is passed in
	 * rather than read from `this` so the static belt can run inside the internal node's key-update path.
	 *
	 * @param keys       the internal node's live separator array
	 * @param peek       the internal node's peek (separator count is `peek`, valid indices `0..peek-1`)
	 * @param slot       the just-rewritten separator index
	 * @param comparator the total order to use, or `null` for the keys' natural order
	 * @param <M>        the key type
	 * @throws GenericEvitaInternalError when the rewritten separator breaks strict local order
	 */
	static <M extends Comparable<M>> void assertSeparatorOrder(
		@Nonnull M[] keys, int peek, int slot, @Nullable Comparator<M> comparator) {
		if (slot > 0 && compareKeys(keys[slot - 1], keys[slot], comparator) >= 0) {
			throw separatorOrderError(keys[slot - 1], keys[slot]);
		}
		if (slot < peek - 1 && compareKeys(keys[slot], keys[slot + 1], comparator) >= 0) {
			throw separatorOrderError(keys[slot], keys[slot + 1]);
		}
	}

	/**
	 * Builds the separator-order corruption error.
	 *
	 * @param leftKey  the separator that should sort strictly before `rightKey`
	 * @param rightKey the separator that `leftKey` collides with
	 * @return the corruption error to throw
	 */
	@Nonnull
	private static GenericEvitaInternalError separatorOrderError(@Nonnull Object leftKey, @Nonnull Object rightKey) {
		return new GenericEvitaInternalError(
			"Corrupted in-memory B+ tree: internal separator keys are out of order (" + leftKey + " does not sort " +
				"before " + rightKey + "). This indicates stale/aliased internal-node state. Restore the catalog " +
				"from a backup, or fully rebuild / reindex the affected catalog."
		);
	}

	/**
	 * Runs the op-time boundary-mutation asserts for a freshly inserted bucket key. A tail insert raises the leaf's last
	 * key, a head insert lowers its first key, and the insert into an empty leaf (the 0→1 transition) does both; an
	 * interior insert cannot violate cross-leaf order in a sound tree and is not checked. Called on the new-bucket branch
	 * shared by warm-up bulk load, transactional ops and trunk replay. The leaf accessors are transaction-layer-aware, so
	 * this reflects the post-insert state whether the mutation landed on the node or on its transactional layer.
	 *
	 * Which of the two branches applies is decided by the insertion INDEX the leaf add method just returned, not by
	 * decoding and comparing the leaf's boundary keys. The two are exactly equivalent: no two keys in a leaf can be
	 * comparator-equal, because a comparator-equal key makes `findKeyPosition` report `alreadyPresent()`, which inserts
	 * no new bucket and therefore never reaches this method. So `insertedAt == 0` holds precisely when the new key IS
	 * the leaf's first key, and `insertedAt == peek` precisely when it is the last - including under a collator that
	 * treats distinct strings as equal. The index is read in the same coordinate space it was produced in: the leaf add
	 * methods compute it from, and insert it into, the transactional layer's columns when a layer is active, and
	 * `getPeek()` resolves through that same layer here.
	 *
	 * CONTRACT: that "no comparator-equal duplicates" premise is only sound while the leaf's comparator IS the tree's
	 * own - every `BPlusLeafTreeNode` construction site currently passes `this.comparator`, and a future path that
	 * threads a different one (a locale-specific comparator handed to a split-born leaf, say) would silently break
	 * this method's branch selection without breaking anything that compiles. Keep them the same instance.
	 *
	 * The 0→1 transition satisfies both conditions at once (an empty leaf has `peek == -1`, so the position is `0` and
	 * the post-insert `peek` is `0`), which is why it triggers both branches as required.
	 *
	 * @param cursor     the descent path to the mutated leaf
	 * @param key        the bucket key just inserted
	 * @param insertedAt the slot index the new bucket landed on, as returned by the leaf add method
	 */
	void assertInsertBoundaries(@Nonnull Cursor<K> cursor, @Nonnull K key, int insertedAt) {
		if (insertedAt == cursor.leafNode().getPeek()) {
			assertTailBoundary(cursor, key);
		}
		if (insertedAt == 0) {
			assertHeadBoundary(cursor, key);
		}
	}

	/**
	 * Recursively collects the leaf nodes reachable from `node` in ascending key order via an in-order, left-to-right
	 * walk of the internal spine.
	 *
	 * @param node   the subtree root to descend
	 * @param leaves the accumulator receiving leaves in order
	 */
	private void collectLeaves(@Nonnull BPlusTreeNode<K, ?> node, @Nonnull List<BPlusLeafTreeNode<K>> leaves) {
		if (node instanceof BPlusLeafTreeNode<?> leaf) {
			//noinspection unchecked
			leaves.add((BPlusLeafTreeNode<K>) leaf);
		} else if (node instanceof BPlusInternalTreeNode<?> internal) {
			//noinspection unchecked
			final BPlusInternalTreeNode<K> internalNode = (BPlusInternalTreeNode<K>) internal;
			final BPlusTreeNode<K, ?>[] children = internalNode.getChildren();
			final int peek = internalNode.getPeek();
			for (int i = 0; i <= peek; i++) {
				collectLeaves(children[i], leaves);
			}
		} else {
			throw new GenericEvitaInternalError("Unknown node type: " + node);
		}
	}

	/**
	 * Builds one internal level over `level` and recurses until a single node (the root) remains. The level below is
	 * partitioned into `ceil(size / maxChildren)` parents, each receiving an evenly distributed contiguous run of
	 * children so no non-root parent underflows.
	 *
	 * @param level the ordered nodes of the level immediately below the one being built
	 * @return the root of the assembled subtree
	 */
	@Nonnull
	private BPlusTreeNode<K, ?> buildSpine(@Nonnull List<? extends BPlusTreeNode<K, ?>> level) {
		if (level.size() == 1) {
			return level.get(0);
		}
		// Pack at most internalNodeBlockSize children per parent (one below the node's children capacity of
		// internalNodeBlockSize + 1). An assembled node must be born non-full so the first child split can still call
		// adaptToLeafSplit (which requires !isFull()) before the parent overflows and splits in turn.
		final int maxChildren = this.internalNodeBlockSize;
		final int childTotal = level.size();
		final int parentCount = (childTotal + maxChildren - 1) / maxChildren;
		final int baseChildren = childTotal / parentCount;
		// the first `withExtra` parents take one extra child so the split is as even as possible
		final int withExtra = childTotal % parentCount;
		final List<BPlusInternalTreeNode<K>> parents = new ArrayList<>(parentCount);
		int childIndex = 0;
		for (int p = 0; p < parentCount; p++) {
			final int childCount = baseChildren + (p < withExtra ? 1 : 0);
			parents.add(buildInternalNode(level, childIndex, childCount));
			childIndex += childCount;
		}
		return buildSpine(parents);
	}

	/**
	 * Constructs a single internal node holding `childCount` children taken from `children` starting at `from`. The
	 * separator before child `i` (for `i >= 1`) is that child's left boundary key. The key / children arrays are
	 * allocated at the node's full capacity (mirroring split-created nodes), leaving the unused tail at its default.
	 *
	 * @param children   the ordered children of the level below
	 * @param from       the index of the first child this node owns
	 * @param childCount the number of children this node owns (>= 1)
	 * @return the assembled internal node
	 */
	@Nonnull
	private BPlusInternalTreeNode<K> buildInternalNode(
		@Nonnull List<? extends BPlusTreeNode<K, ?>> children, int from, int childCount
	) {
		//noinspection unchecked
		final K[] keys = (K[]) Array.newInstance(this.keyType, this.internalNodeBlockSize);
		//noinspection unchecked
		final BPlusTreeNode<K, ?>[] childArray = new BPlusTreeNode[this.internalNodeBlockSize + 1];
		for (int i = 0; i < childCount; i++) {
			final BPlusTreeNode<K, ?> child = children.get(from + i);
			childArray[i] = child;
			if (i > 0) {
				// separator i-1 routes to child i — the left boundary key invariant the tree enforces
				keys[i - 1] = child.getLeftBoundaryKey();
			}
		}
		return new BPlusInternalTreeNode<>(keys, childArray, childCount - 1, this.comparator, true);
	}

	/**
	 * Collects the nodes this (committed) tree rebuilt relative to `priorTree` — the page set a commit must (re)write
	 * under the granular FilterIndex layout, driving emission from the merge's instance-identity rebuilt set. A node
	 * carried across the commit unchanged is shared BY IDENTITY between the prior committed tree
	 * and this one (the transactional merge returns the very same instance); a node the merge rebuilds — for ANY reason
	 * it rebuilds: a child changed, the node itself held a transactional layer, one of a leaf's overflow record-set
	 * bitmaps mutated, or it is a split/merge product — is a fresh instance. So `{node in this tree : not
	 * identity-present in priorTree}` is exactly the merge's rebuilt set, with no hand-maintained dirty predicate that
	 * could drift from the merge logic. In particular this catches a leaf whose record set changed only through an
	 * overflow bitmap (e.g. mutated via the live bitmap from {@link #getRecordsEqualTo}) without the leaf node itself
	 * acquiring a layer — the case a leaf-layer-only predicate would miss.
	 *
	 * Both trees must be in their committed (layer-free) state — call this AFTER the commit that produced this tree.
	 * The walk prunes a whole subtree at the first carried-over node (a carried internal node necessarily has an
	 * entirely carried subtree, since the merge returns it unchanged only when no descendant changed), so it visits
	 * `O(changed nodes + their root-paths)`. The prior-tree identity index it builds is the only full-tree cost and is
	 * optimized away later once nodes carry a persisted `pageSequence`.
	 *
	 * @param priorTree the previous committed tree this one was derived from by a transactional merge
	 * @return the rebuilt nodes (leaves and internal spine) in pre-order; empty for a no-op commit
	 */
	@Nonnull
	public List<BPlusTreeNode<K, ?>> collectRebuiltNodesSince(@Nonnull TransactionalBucketBPlusTree<K> priorTree) {
		final Set<BPlusTreeNode<K, ?>> priorNodes = Collections.newSetFromMap(new IdentityHashMap<>());
		indexNodesByIdentity(priorTree.getRoot(), priorNodes);
		final List<BPlusTreeNode<K, ?>> rebuilt = new ArrayList<>();
		collectRebuilt(getRoot(), priorNodes, rebuilt);
		return rebuilt;
	}

	/**
	 * Adds every node reachable from `node` (inclusive) to `out` under identity semantics.
	 *
	 * @param node the subtree root to index
	 * @param out  the identity set accumulator
	 */
	private void indexNodesByIdentity(@Nonnull BPlusTreeNode<K, ?> node, @Nonnull Set<BPlusTreeNode<K, ?>> out) {
		out.add(node);
		if (node instanceof BPlusInternalTreeNode<?> internal) {
			//noinspection unchecked
			final BPlusInternalTreeNode<K> internalNode = (BPlusInternalTreeNode<K>) internal;
			final BPlusTreeNode<K, ?>[] children = internalNode.getChildren();
			final int peek = internalNode.getPeek();
			for (int i = 0; i <= peek; i++) {
				indexNodesByIdentity(children[i], out);
			}
		}
	}

	/**
	 * Walks `node`'s subtree collecting every node not identity-present in `priorNodes` (rebuilt this commit), pruning
	 * whole subtrees at the first carried-over node.
	 *
	 * @param node       the subtree root to scan
	 * @param priorNodes the identity set of the prior committed tree's nodes
	 * @param out        the rebuilt-node accumulator (pre-order)
	 */
	private void collectRebuilt(
		@Nonnull BPlusTreeNode<K, ?> node,
		@Nonnull Set<BPlusTreeNode<K, ?>> priorNodes,
		@Nonnull List<BPlusTreeNode<K, ?>> out
	) {
		if (priorNodes.contains(node)) {
			// carried over unchanged → the whole subtree below is clean too
			return;
		}
		out.add(node);
		if (node instanceof BPlusInternalTreeNode<?> internal) {
			//noinspection unchecked
			final BPlusInternalTreeNode<K> internalNode = (BPlusInternalTreeNode<K>) internal;
			final BPlusTreeNode<K, ?>[] children = internalNode.getChildren();
			final int peek = internalNode.getPeek();
			for (int i = 0; i <= peek; i++) {
				collectRebuilt(children[i], priorNodes, out);
			}
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
							if (!isInternal) {
								// a steal shifts both this leaf's and the donor's boundary keys — register both as
								// dirty-scope tokens for this transaction
								//noinspection unchecked
								registerDirtyLeafInScope((BPlusLeafTreeNode<K>) node);
								//noinspection unchecked
								registerDirtyLeafInScope((BPlusLeafTreeNode<K>) previousNode);
							}
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
							if (!isInternal) {
								// a steal shifts both this leaf's and the donor's boundary keys — register both as
								// dirty-scope tokens for this transaction
								//noinspection unchecked
								registerDirtyLeafInScope((BPlusLeafTreeNode<K>) node);
								//noinspection unchecked
								registerDirtyLeafInScope((BPlusLeafTreeNode<K>) nextNode);
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
							if (!isInternal) {
								// a merge lowers this surviving leaf's head boundary — register it as a
								// dirty-scope token for this transaction
								//noinspection unchecked
								registerDirtyLeafInScope((BPlusLeafTreeNode<K>) node);
							}
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
							if (!isInternal) {
								// a merge raises this surviving leaf's tail boundary — register it as a
								// dirty-scope token for this transaction
								//noinspection unchecked
								registerDirtyLeafInScope((BPlusLeafTreeNode<K>) node);
							}
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
								this.recordColumnFactory.create(this.valueBlockSize),
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
	Cursor<K> createCursor(@Nonnull K key) {
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
		final RecordColumn originRecords = leaf.getRecords();
		final TransactionalBitmap[] originOverflow = leaf.getOverflow();

		// Structural assert: the split partitions a sorted leaf into a left half [0, mid) and a right half
		// [mid, capacity); the left leaf's last key must sort strictly before the right leaf's first key, and the
		// promoted separator is exactly that right first key. A violation means the leaf being split was already out of
		// order — fail fast rather than persist two overlapping pages.
		final K leftHalfLastKey = originKeys.keyAt(mid - 1);
		final K rightHalfFirstKey = originKeys.keyAt(mid);
		if (compareKeys(leftHalfLastKey, rightHalfFirstKey, this.comparator) >= 0) {
			throw new GenericEvitaInternalError(
				"Corrupted in-memory B+ tree: splitting a leaf produced overlapping halves — the left half's last " +
					"key (" + leftHalfLastKey + ") does not sort before the right half's first key (" +
					rightHalfFirstKey + "). The leaf being split was already out of order. Restore the catalog from a " +
					"backup, or fully rebuild / reindex the affected catalog."
			);
		}

		// Move half the buckets to fresh arrays of the left leaf node
		final BPlusLeafTreeNode<K> leftLeaf = new BPlusLeafTreeNode<>(
			originKeys,
			originRecords,
			originOverflow,
			originKeys.allocate(this.valueBlockSize),
			originRecords.allocate(this.valueBlockSize),
			originOverflow == null ? null : new TransactionalBitmap[this.valueBlockSize],
			0,
			mid,
			this.comparator,
			true
		);

		// Move the other half into fresh arrays of the right leaf node. The former leaf's arrays are intentionally NOT
		// reused in place: a per-entity savepoint must be able to restore the former leaf verbatim on rollback (the
		// split is undone by re-attaching the former leaf's layer), which requires its backing arrays to stay intact.
		final BPlusLeafTreeNode<K> rightLeaf = new BPlusLeafTreeNode<>(
			originKeys,
			originRecords,
			originOverflow,
			originKeys.allocate(this.valueBlockSize),
			originRecords.allocate(this.valueBlockSize),
			originOverflow == null ? null : new TransactionalBitmap[this.valueBlockSize],
			mid,
			leftLeaf.getKeyColumn().capacity(),
			this.comparator,
			true
		);

		if (Transaction.getTransactionalMemoryLayerIfExists(leaf) != null) {
			leaf.removeLayer();
		}

		if (leaf == this.getRoot()) {
			this.setRoot(
				new BPlusInternalTreeNode<>(
					this.internalNodeBlockSize,
					rightLeaf.keyAt(0),
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
				rightLeaf.keyAt(0),
				cursor.toCursorWithLevel()
			);
		}
		// a split creates a brand-new adjacent leaf pair with a fresh separator between them — register both halves
		// as dirty-scope tokens for this transaction
		registerDirtyLeafInScope(leftLeaf);
		registerDirtyLeafInScope(rightLeaf);
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
		// The node is full at split time (the only caller guards with isFull()), so occupancy equals capacity. Derive the
		// midpoint from the actual key count rather than valueBlockSize: internal nodes are sized by internalNodeBlockSize,
		// and spines bulk-assembled from persisted pages carry that (smaller) capacity, so a valueBlockSize-derived midpoint
		// overruns their arrays. keyCount is the number of separator keys (children = keyCount + 1). Only keys and child
		// pointers move here — the columnar bucket store lives in the leaves, untouched by an internal-node split.
		final int keyCount = internal.keyCount();
		final int mid = (keyCount + 1) / 2;
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
			true
		);

		// End bounds are the origin's actual occupancy (keyCount separators, keyCount + 1 children), not the array
		// capacity — capacity may exceed occupancy after the internalNodeBlockSize sizing fix, and only the live range
		// must be copied.
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

		if (Transaction.getTransactionalMemoryLayerIfExists(internal) != null) {
			internal.removeLayer();
		}

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

		/**
		 * Returns the logical page this node occupies in its persistence stream under the granular FilterIndex layout
		 *, or {@link TransactionalBucketBPlusTree#UNASSIGNED_PAGE_SEQUENCE} when none has been assigned yet.
		 * The page is the STABLE on-disk identity of the node: an in-place rewrite carries the same page forward (so the
		 * same storage part is overwritten on commit), while a split-born sibling or a not-yet-loaded node reads as
		 * unassigned until the write/load path assigns one.
		 *
		 * @return the assigned page sequence, or {@link TransactionalBucketBPlusTree#UNASSIGNED_PAGE_SEQUENCE}
		 */
		int getPageSequence();

		/**
		 * Assigns the logical page this node occupies in its persistence stream. Called by the write path when a
		 * previously {@link TransactionalBucketBPlusTree#UNASSIGNED_PAGE_SEQUENCE unassigned} node is first allocated a page,
		 * and by the load path to restore each node's persisted page.
		 *
		 * @param pageSequence the page sequence to assign
		 */
		void setPageSequence(int pageSequence);
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
		 * Returns the lone `long` payload of the current bucket. Valid only on a long-payload tree (built via
		 * {@link #withLongPayload}), whose buckets are always single. Mirrors {@link #singleRecordId()} but reads the full
		 * 64-bit payload.
		 *
		 * @return the single `long` payload
		 */
		long longRecordId();

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

		/**
		 * Returns the version id of the leaf node the current bucket lives in. Valid only after a {@link #next()} that
		 * returned true. The leaf id is a per-page version token: an untouched leaf keeps its id across a commit (the
		 * commit-merge returns the same instance) while a leaf whose content changed becomes a fresh instance with a
		 * fresh id (see {@code BPlusLeafTreeNode.createCopyWithMergedTransactionalMemory}). Consumers use it to build a
		 * leaf-granular formula-cache staleness token — a cached read over a value range is invalidated only when a leaf
		 * it actually crossed changes, instead of on any write to the whole index.
		 *
		 * @return the current bucket's leaf version id
		 * @throws GenericEvitaInternalError if called before a {@link #next()} that returned true
		 */
		long currentLeafId();
	}

	/**
	 * Internal node implementation of the B+ tree, holding keys and child node pointers. Internal nodes serve as routing
	 * nodes — they do not store buckets directly but guide searches to the appropriate leaf nodes. Verbatim copy of the
	 * object tree's internal node (the bucket decomposition only touches the leaf).
	 */
	static class BPlusInternalTreeNode<M extends Comparable<M>> implements
		BPlusTreeNode<M, BPlusInternalTreeNode<M>>,
		Snapshotable<BPlusInternalTreeNode.BPlusInternalNodeMemento<M>> {
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
		 * The logical persistence page this node occupies (see {@link BPlusTreeNode#getPageSequence()}). NOT transactional —
		 * it is structural bookkeeping carried across the commit-merge by {@link #createCopyWithMergedTransactionalMemory}
		 * (an in-place rebuild reuses the source page) and is left at {@link #UNASSIGNED_PAGE_SEQUENCE} on split-born nodes
		 * until the write path allocates a page.
		 */
		private int pageSequence = UNASSIGNED_PAGE_SEQUENCE;

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
		public int getPageSequence() {
			// pageSequence is structural (not transactional): it lives on the committed instance and is reused across
			// the commit-merge, so it is read/written directly without routing through any transactional layer
			return this.pageSequence;
		}

		@Override
		public void setPageSequence(int pageSequence) {
			this.pageSequence = pageSequence;
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
				// separator-order belt: the rewritten separator must keep strict local order
				assertSeparatorOrder(this.keys, this.peek, index - 1, this.comparator);
			} else {
				decoupleTransactionalArrays();
				Assert.isPremiseValid(
					layer.children[index] == node,
					"Node to update key for must match the child node at the specified index!"
				);
				layer.keys[index - 1] = node.getLeftBoundaryKey();
				assertSeparatorOrder(layer.keys, layer.peek, index - 1, this.comparator);
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

			final BPlusInternalTreeNode<M> result;
			if (newChildren != null) {
				result = new BPlusInternalTreeNode<>(
					theKeys,
					newChildren,
					thePeek,
					this.comparator,
					true
				);
			} else if (layer != null) {
				result = new BPlusInternalTreeNode<>(
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
				result = new BPlusInternalTreeNode<>(
					theKeys,
					theChildren,
					thePeek,
					this.comparator,
					true
				);
			} else {
				return this;
			}
			// carry the logical persistence page across the rebuild: an in-place rebuild rewrites the SAME page (reuse
			// this.pageSequence), while a split-born node keeps its UNASSIGNED_PAGE_SEQUENCE so the write path allocates it fresh
			result.pageSequence = this.pageSequence;
			return result;
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
			@Nonnull BPlusTreeNode<M, ?>[] children,
			int peek
		) {
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
		implements
		BPlusTreeNode<M, BPlusLeafTreeNode<M>>,
		Snapshotable<BPlusLeafTreeNode.BPlusLeafNodeMemento<M>> {
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
		 * The single-record column behind the pluggable {@link RecordColumn} abstraction so the leaf can hold the lone pk
		 * in the cheapest primitive representation (the default {@link IntRecordColumn} backs it with a bare `int[]`).
		 * `records.intAt(i)` is the lone pk of bucket `i` when `overflow == null || overflow[i] == null`; otherwise it is
		 * don't-care (never read).
		 */
		private RecordColumn records;
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
		 * The logical persistence page this leaf occupies (see {@link BPlusTreeNode#getPageSequence()}). NOT transactional —
		 * it is structural bookkeeping carried across the commit-merge by {@link #createCopyWithMergedTransactionalMemory}
		 * (an in-place rebuild of this leaf reuses the source page so the same storage part is overwritten) and is left
		 * at {@link #UNASSIGNED_PAGE_SEQUENCE} on split-born leaves until the write path allocates a page.
		 */
		private int pageSequence = UNASSIGNED_PAGE_SEQUENCE;
		/**
		 * The granular-storage change-detection flag: `true` when this leaf has been mutated since the last flush emitted
		 * its page. Routed through the leaf's transactional layer (like the columns) so a change made inside a transaction
		 * is visible at flush yet isolated from concurrent readers and discarded on abort. A committed leaf the merge
		 * produces defaults to clean; the emitter clears the flag once it has collected the page. It is the deterministic
		 * replacement for a content hash — every mutation site sets it, so a real change can never be suppressed.
		 */
		private boolean dirty = false;

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
		 * Copies a range of overflow entries from `src` into `dst`. When `dst` is present but `src` is null (the donor
		 * sibling has no overflow column, i.e. every donated bucket is a single record) the destination range is cleared
		 * to null rather than left untouched - the caller has shifted `dst`'s own buckets aside with a plain arraycopy,
		 * so the vacated range still aliases the shifted-from references and must be wiped. A no-op only when `dst` is
		 * null (the destination leaf has no overflow column either). Used by steal/merge to move multi buckets in
		 * lockstep with the key/record columns.
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
			if (dst != null) {
				if (src != null) {
					System.arraycopy(src, srcPos, dst, dstPos, length);
				} else {
					// The sibling carries no overflow column (every bucket it donates is a single record), but `dst`
					// does. The caller has just shifted `dst`'s own buckets aside with a plain arraycopy - which is a
					// copy, not a move, so the vacated destination range still holds those shifted-from references.
					// Clear that range so the donated single buckets are correctly marked single. Skipping it would
					// leave a moved multi bucket's bitmap aliased at two slots, and that single instance would then be
					// committed (and discarded) twice during the transactional merge sweep - an "already discarded".
					Arrays.fill(dst, dstPos, dstPos + length, null);
				}
			}
		}

		/**
		 * Creates a new empty leaf node backed by a pre-built key column and single-record column. The key column's
		 * capacity defines the leaf block size, and the column kinds (boxed vs. primitive key, int vs. long payload) were
		 * chosen by the tree's {@link ValueColumnFactory} / {@link RecordColumnFactory}.
		 *
		 * @param keys               the empty key column (its capacity is the block size)
		 * @param records            the empty single-record column (same capacity, built by the tree's record factory)
		 * @param comparator         optional comparator defining the key order; `null` ⇒ natural order
		 * @param transactionalLayer whether this node participates in the transactional memory layer
		 */
		public BPlusLeafTreeNode(
			@Nonnull ValueColumn<M> keys,
			@Nonnull RecordColumn records,
			@Nullable Comparator<M> comparator,
			boolean transactionalLayer
		) {
			this.keys = keys;
			this.records = records;
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
			@Nonnull RecordColumn originRecords,
			@Nullable TransactionalBitmap[] originOverflow,
			@Nonnull ValueColumn<M> keys,
			@Nonnull RecordColumn records,
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
			originRecords.copyRangeTo(start, records, 0, end - start);
			if (records == originRecords) {
				records.fillEmpty(end - start, records.capacity());
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
			@Nonnull RecordColumn records,
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
			// changing the occupied range is a content mutation (truncation on split/removal, donor shrink on
			// steal/merge): flag the leaf so the granular write path re-emits its page
			if (layer == null) {
				this.dirty = true;
			} else {
				layer.dirty = true;
			}
			if (layer == null) {
				final int originPeek = this.peek;
				this.peek = peek;
				if (peek < originPeek) {
					this.keys.fillEmpty(peek + 1, originPeek + 1);
					this.records.fillEmpty(peek + 1, originPeek + 1);
					if (this.overflow != null) {
						Arrays.fill(this.overflow, peek + 1, originPeek + 1, null);
					}
				}
			} else {
				final int originPeek = layer.peek;
				layer.peek = peek;
				if (peek < originPeek) {
					if (layer.keys == this.keys) {
						// decouple by deep-copying the shared base column before truncating it below
						layer.keys = this.keys.duplicate();
					}
					// truncate the freed tail in both cases: a fixed-array column nulls/zeroes the released slots
					// (as before), while a dense front-coded column actually drops them — a no-op for the former,
					// mandatory for the latter, which has no harmless sentinel tail to leave behind
					layer.keys.fillEmpty(peek + 1, originPeek + 1);
					if (layer.records == this.records) {
						// decouple by deep-copying the shared base column (its tail beyond originPeek is already zero, so
						// the deep copy matches the former fresh-array + copy-[0, originPeek] decouple verbatim)
						layer.records = this.records.duplicate();
					} else {
						layer.records.fillEmpty(peek + 1, originPeek + 1);
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
		public int getPageSequence() {
			// pageSequence is structural (not transactional): it lives on the committed instance and is reused across
			// the commit-merge, so it is read/written directly without routing through any transactional layer
			return this.pageSequence;
		}

		@Override
		public void setPageSequence(int pageSequence) {
			this.pageSequence = pageSequence;
		}

		/**
		 * Returns the change-detection flag, transaction-aware: the in-flight transaction's layer value when a layer
		 * exists, otherwise the committed value. See {@link #dirty}.
		 *
		 * @return true when the leaf has been mutated since its page was last flushed
		 */
		boolean isDirty() {
			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
			return layer == null ? this.dirty : layer.dirty;
		}

		/**
		 * Clears the change-detection flag once the emitter has collected this leaf's page for the current flush. In a
		 * transaction it clears the layer's flag (the merge produces a clean committed instance regardless); in the
		 * warm-up path it clears the committed instance in place so the next flush suppresses it. See {@link #dirty}.
		 */
		void clearDirty() {
			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
			if (layer == null) {
				this.dirty = false;
			} else {
				layer.dirty = false;
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
				return this.peek == this.records.capacity() - 1;
			} else {
				return layer.peek == layer.records.capacity() - 1;
			}
		}

		@Override
		public void toVerboseString(@Nonnull StringBuilder sb, int level, int indentSpaces) {
			sb.append(" ".repeat(level * indentSpaces));
			final ValueColumn<M> theKeys;
			final RecordColumn theRecords;
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
					sb.append(theRecords.intAt(i));
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
			// the receiving leaf's page changes; the donor is flagged via its own setPeek below
			if (layer == null) {
				this.dirty = true;
			} else {
				layer.dirty = true;
			}
			if (layer == null) {
				ensureOverflowForSteal(previousNode.getOverflow());
				this.keys.copyRangeTo(0, this.keys, numberOfTailValues, this.peek + 1);
				this.records.copyRangeTo(0, this.records, numberOfTailValues, this.peek + 1);
				if (this.overflow != null) {
					System.arraycopy(this.overflow, 0, this.overflow, numberOfTailValues, this.peek + 1);
				}
				previousNode.getKeyColumn().copyRangeTo(
					previousNode.size() - numberOfTailValues, this.keys, 0, numberOfTailValues);
				previousNode.getRecords().copyRangeTo(
					previousNode.size() - numberOfTailValues, this.records, 0, numberOfTailValues);
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
				layer.records.copyRangeTo(0, layer.records, numberOfTailValues, layer.peek + 1);
				if (layer.overflow != null) {
					System.arraycopy(layer.overflow, 0, layer.overflow, numberOfTailValues, layer.peek + 1);
				}
				previousNode.getKeyColumn().copyRangeTo(
					previousNode.size() - numberOfTailValues, layer.keys, 0, numberOfTailValues);
				previousNode.getRecords().copyRangeTo(
					previousNode.size() - numberOfTailValues, layer.records, 0, numberOfTailValues);
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
			// the receiving leaf's page changes; the donor is flagged via its own setPeek below
			if (layer == null) {
				this.dirty = true;
			} else {
				layer.dirty = true;
			}
			if (layer == null) {
				final ValueColumn<M> nextKeys = nextNode.getKeyColumnForUpdate();
				final RecordColumn nextRecords = nextNode.getRecordsForUpdate();
				final TransactionalBitmap[] nextOverflow = nextNode.getOverflowForUpdate();
				ensureOverflowForSteal(nextOverflow);
				nextKeys.copyRangeTo(0, this.keys, this.peek + 1, numberOfHeadValues);
				nextRecords.copyRangeTo(0, this.records, this.peek + 1, numberOfHeadValues);
				copyOverflowRange(nextOverflow, 0, this.overflow, this.peek + 1, numberOfHeadValues);
				nextKeys.copyRangeTo(numberOfHeadValues, nextKeys, 0, nextNode.size() - numberOfHeadValues);
				nextRecords.copyRangeTo(numberOfHeadValues, nextRecords, 0, nextNode.size() - numberOfHeadValues);
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
				final RecordColumn nextRecords = nextNode.getRecordsForUpdate();
				final TransactionalBitmap[] nextOverflow = nextNode.getOverflowForUpdate();
				ensureLayerOverflowForSteal(layer, nextOverflow);
				nextKeys.copyRangeTo(0, layer.keys, layer.peek + 1, numberOfHeadValues);
				nextRecords.copyRangeTo(0, layer.records, layer.peek + 1, numberOfHeadValues);
				copyOverflowRange(nextOverflow, 0, layer.overflow, layer.peek + 1, numberOfHeadValues);
				nextKeys.copyRangeTo(numberOfHeadValues, nextKeys, 0, nextNode.size() - numberOfHeadValues);
				nextRecords.copyRangeTo(numberOfHeadValues, nextRecords, 0, nextNode.size() - numberOfHeadValues);
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
			// the surviving (receiving) leaf's page changes; the emptied donor is flagged via its own setPeek(-1) below
			if (layer == null) {
				this.dirty = true;
			} else {
				layer.dirty = true;
			}
			if (layer == null) {
				ensureOverflowForSteal(previousNode.getOverflow());
				this.keys.copyRangeTo(0, this.keys, mergePeek + 1, this.peek + 1);
				this.records.copyRangeTo(0, this.records, mergePeek + 1, this.peek + 1);
				if (this.overflow != null) {
					System.arraycopy(this.overflow, 0, this.overflow, mergePeek + 1, this.peek + 1);
				}
				previousNode.getKeyColumn().copyRangeTo(0, this.keys, 0, mergePeek + 1);
				previousNode.getRecords().copyRangeTo(0, this.records, 0, mergePeek + 1);
				copyOverflowRange(previousNode.getOverflow(), 0, this.overflow, 0, mergePeek + 1);
				this.peek += mergePeek + 1;
				previousNode.setPeek(-1);
			} else {
				decoupleTransactionalArrays();
				previousNode.decoupleTransactionalArrays();

				ensureLayerOverflowForSteal(layer, previousNode.getOverflow());
				layer.keys.copyRangeTo(0, layer.keys, mergePeek + 1, layer.peek + 1);
				layer.records.copyRangeTo(0, layer.records, mergePeek + 1, layer.peek + 1);
				if (layer.overflow != null) {
					System.arraycopy(layer.overflow, 0, layer.overflow, mergePeek + 1, layer.peek + 1);
				}
				previousNode.getKeyColumnForUpdate().copyRangeTo(0, layer.keys, 0, mergePeek + 1);
				previousNode.getRecordsForUpdate().copyRangeTo(0, layer.records, 0, mergePeek + 1);
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
			// the surviving (receiving) leaf's page changes; the emptied donor is flagged via its own setPeek(-1) below
			if (layer == null) {
				this.dirty = true;
			} else {
				layer.dirty = true;
			}
			if (layer == null) {
				ensureOverflowForSteal(nextNode.getOverflow());
				nextNode.getKeyColumn().copyRangeTo(0, this.keys, this.peek + 1, mergePeek + 1);
				nextNode.getRecords().copyRangeTo(0, this.records, this.peek + 1, mergePeek + 1);
				copyOverflowRange(nextNode.getOverflow(), 0, this.overflow, this.peek + 1, mergePeek + 1);
				this.peek += mergePeek + 1;
				nextNode.setPeek(-1);
			} else {
				decoupleTransactionalArrays();
				nextNode.decoupleTransactionalArrays();

				ensureLayerOverflowForSteal(layer, nextNode.getOverflow());
				nextNode.getKeyColumnForUpdate().copyRangeTo(0, layer.keys, layer.peek + 1, mergePeek + 1);
				nextNode.getRecordsForUpdate().copyRangeTo(0, layer.records, layer.peek + 1, mergePeek + 1);
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
		public RecordColumn getRecords() {
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
		public RecordColumn getRecordsForUpdate() {
			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			if (layer == null) {
				return this.records;
			} else {
				if (layer.records == this.records) {
					layer.records = this.records.duplicate();
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
			final RecordColumn theRecords;
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
			return new SingleRecordBitmap(theRecords.intAt(index));
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
		 * Returns the greatest record id positioned before `recordId` within this leaf under the global sort order
		 * (buckets ascend by value, records within a bucket ascend by id): first the greatest lower id in `value`'s own
		 * bucket, then the last record of the closest preceding bucket. Returns
		 * {@link EvitaDataTypes#RESERVED_PRIMARY_KEY} when no such record exists in THIS leaf — the caller continues
		 * in the preceding leaf. evitaDB never assigns that primary key to an entity, so the sentinel cannot collide
		 * with a real record id.
		 *
		 * @param value    the value whose insertion point anchors the search
		 * @param recordId the record id being inserted
		 * @return the anchoring predecessor record id, or {@link EvitaDataTypes#RESERVED_PRIMARY_KEY} when this leaf
		 * holds none
		 */
		public int previousRecord(@Nonnull M value, int recordId) {
			final ValueColumn<M> theKeys;
			final RecordColumn theRecords;
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
			final int index = insertionPosition.position();
			if (insertionPosition.alreadyPresent() && recordId != Integer.MIN_VALUE) {
				// records sharing a value ascend by (signed) id - the anchor is the greatest id strictly below the
				// inserted one; nothing can sort below Integer.MIN_VALUE, so the guard above keeps `recordId - 1`
				// from wrapping around
				final TransactionalBitmap bitmap = theOverflow == null ? null : theOverflow[index];
				if (bitmap == null) {
					final int single = theRecords.intAt(index);
					if (single < recordId) {
						return single;
					}
				} else {
					final long previous = bitmap.signedPreviousValue(recordId - 1);
					if (previous != RoaringBitmapBackedBitmap.NO_PREVIOUS_VALUE) {
						return (int) previous;
					}
				}
			}
			// no in-bucket predecessor - the anchor is the last record of the closest preceding bucket in this leaf
			final int previousIndex = index - 1;
			if (previousIndex >= 0) {
				return lastRecordOfBucket(previousIndex, theRecords, theOverflow);
			}
			return EvitaDataTypes.RESERVED_PRIMARY_KEY;
		}

		/**
		 * Returns the last (greatest) record id of the last bucket in this leaf. The leaf must not be empty.
		 *
		 * @return the last record id stored in this leaf
		 */
		public int lastRecord() {
			final RecordColumn theRecords;
			final TransactionalBitmap[] theOverflow;
			final int thePeek;

			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
			if (layer == null) {
				theRecords = this.records;
				theOverflow = this.overflow;
				thePeek = this.peek;
			} else {
				theRecords = layer.records;
				theOverflow = layer.overflow;
				thePeek = layer.peek;
			}
			Assert.isPremiseValid(thePeek >= 0, "Cannot read the last record of an empty leaf!");
			return lastRecordOfBucket(thePeek, theRecords, theOverflow);
		}

		/**
		 * Returns the greatest record id of the bucket at `index` in signed order: the bitmap's signed maximum for an
		 * overflow bucket, the single payload otherwise. The bitmap case is answered from the transactional diff layer
		 * (never through a merged bitmap), so it costs the same whether or not a transaction is open.
		 *
		 * @param index    the bucket index within the leaf
		 * @param records  the resolved record column
		 * @param overflow the resolved overflow bitmaps (may be null)
		 * @return the greatest record id of the bucket
		 */
		private static int lastRecordOfBucket(
			int index,
			@Nonnull RecordColumn records,
			@Nullable TransactionalBitmap[] overflow
		) {
			final TransactionalBitmap bitmap = overflow == null ? null : overflow[index];
			if (bitmap == null) {
				return records.intAt(index);
			}
			final long last = bitmap.signedPreviousValue(Integer.MAX_VALUE);
			Assert.isPremiseValid(
				last != RoaringBitmapBackedBitmap.NO_PREVIOUS_VALUE,
				"An overflow bucket must never be empty!"
			);
			return (int) last;
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

		/**
		 * Captures this layer's revertable columnar state for a per-entity savepoint. The key column is
		 * deep-copied via {@link ValueColumn#duplicate()}, the single-record column via {@link RecordColumn#duplicate()},
		 * and the lazy overflow
		 * column is shallow-cloned — the overflow {@link TransactionalBitmap}s own their own transactional layers and are
		 * snapshotted independently, so the leaf only needs to remember which slot points to which bitmap. Independent
		 * copies guarantee a later mutation, or a repeated {@link #restore}, cannot corrupt the memento.
		 *
		 * @return an independent snapshot of this leaf's three columns and peek
		 */
		@Nonnull
		@Override
		public BPlusLeafNodeMemento<M> snapshot() {
			return new BPlusLeafNodeMemento<>(
				this.keys.duplicate(),
				this.records.duplicate(),
				this.overflow == null ? null : this.overflow.clone(),
				this.peek
			);
		}

		/**
		 * Restores the columnar state captured by {@link #snapshot}. Fresh copies of the memento's columns are installed
		 * so the memento stays reusable for a repeated restore.
		 *
		 * @param memento the state previously captured by {@link #snapshot}
		 */
		@Override
		public void restore(@Nonnull BPlusLeafNodeMemento<M> memento) {
			this.keys = memento.keys().duplicate();
			this.records = memento.records().duplicate();
			this.overflow = memento.overflow() == null ? null : memento.overflow().clone();
			this.peek = memento.peek();
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
			final RecordColumn theRecords;
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

			// commit-wrap runs ONLY on the overflow column (producer bitmaps); keys/records are plain references, EXCEPT
			// that a multi bucket drained to a single record is DEMOTED here to the primitive single-record form: the
			// sole surviving id (read from the committed bitmap, never from records[i] which is don't-care
			// post-promotion) is written into a copy-on-write records column and the overflow slot is nulled. Demotion is
			// deferred to commit (never mid-transaction) so a bucket oscillating across the 1/2 boundary within one
			// transaction allocates its bitmap at most once — see the class javadoc.
			TransactionalBitmap[] newOverflow = null;
			RecordColumn newRecords = null;
			if (theOverflow != null) {
				for (int i = 0; i < thePeek + 1; i++) {
					final TransactionalBitmap original = theOverflow[i];
					if (original == null) {
						if (newOverflow != null) {
							newOverflow[i] = null;
						}
						continue;
					}
					final Bitmap committedBitmap = transactionalLayer.getStateCopyWithCommittedChanges(original);
					final int committedCardinality = committedBitmap.size();
					if (committedCardinality == 1) {
						// DEMOTE: revert the multi bucket to the primitive single-record form
						if (newOverflow == null) {
							newOverflow = new TransactionalBitmap[theOverflow.length];
							System.arraycopy(theOverflow, 0, newOverflow, 0, i);
						}
						newOverflow[i] = null;
						if (newRecords == null) {
							newRecords = theRecords.duplicate();
						}
						newRecords.setAt(i, committedBitmap.getFirst());
					} else if (committedCardinality == 0) {
						// a present overflow slot can never be empty — a bucket drained to zero is deleted at mutation time
						throw new GenericEvitaInternalError(
							"Empty overflow bucket at index " + i + " — a drained-to-zero bucket must be deleted, not left!"
						);
					} else {
						// keep the multi bucket: re-wrap the committed state as a TransactionalBitmap
						final TransactionalBitmap committed = wrapOverflow(committedBitmap);
						if (newOverflow == null && committed != original) {
							newOverflow = new TransactionalBitmap[theOverflow.length];
							System.arraycopy(theOverflow, 0, newOverflow, 0, i);
						}
						if (newOverflow != null) {
							newOverflow[i] = committed;
						}
					}
				}
			}

			// a demotion (newRecords != null) always nulls its overflow slot, so it implies newOverflow != null; the
			// other rebuild branches are reached only when no overflow slot changed and thus keep the original records
			final RecordColumn theMergedRecords = newRecords != null ? newRecords : theRecords;
			Assert.isPremiseValid(
				newRecords == null || newOverflow != null,
				"A records-column demotion must always be accompanied by an overflow-column change!"
			);
			final BPlusLeafTreeNode<M> result;
			if (newOverflow != null) {
				result = new BPlusLeafTreeNode<>(
					theKeys,
					theMergedRecords,
					newOverflow,
					thePeek,
					this.comparator,
					true
				);
			} else if (layer != null) {
				result = new BPlusLeafTreeNode<>(
					theKeys,
					theMergedRecords,
					theOverflow,
					thePeek,
					this.comparator,
					true
				);
			} else if (!this.transactionalLayer) {
				// nodes created during splits/merges are built with transactionalLayer=false so they do not allocate
				// STM layers mid-transaction; on commit they must be rebuilt as participating (transactionalLayer=true)
				// nodes so subsequent transactions can layer changes over them
				result = new BPlusLeafTreeNode<>(
					theKeys,
					theMergedRecords,
					theOverflow,
					thePeek,
					this.comparator,
					true
				);
			} else {
				return this;
			}
			// carry the logical persistence page across the rebuild: an in-place rebuild of this leaf rewrites the SAME
			// page (reuse this.pageSequence), while a split-born leaf keeps its UNASSIGNED_PAGE_SEQUENCE so the write path allocates
			// it fresh
			result.pageSequence = this.pageSequence;
			return result;
		}

		/**
		 * Adds a single record to the bucket identified by `value`, applying the promotion rules. See
		 * {@link TransactionalBucketBPlusTree#addRecord(Comparable, int)}.
		 *
		 * @param value the value identifying the bucket
		 * @param pk    the record id to add
		 * @return the slot index the new bucket was inserted at, or {@link #NO_NEW_BUCKET} when the record joined an
		 * existing bucket
		 */
		public int addRecord(@Nonnull M value, int pk) {
			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			// adding a record mutates this leaf's page: flag it for re-emission (the layer is created above regardless,
			// so a rare no-op add over-reports at worst — never under-reports)
			if (layer == null) {
				this.dirty = true;
			} else {
				layer.dirty = true;
			}
			if (layer == null) {
				Assert.isPremiseValid(
					this.peek < this.records.capacity() - 1,
					"Cannot insert into a full leaf node, split the node first!"
				);
				final InsertionPosition insertionPosition =
					this.keys.findKeyPosition(value, 0, this.peek + 1, this.comparator);
				if (insertionPosition.alreadyPresent()) {
					addToExistingBucket(insertionPosition.position(), pk);
					return NO_NEW_BUCKET;
				}
				insertNewSingleBucket(insertionPosition.position(), value, pk);
				return insertionPosition.position();
			} else {
				decoupleTransactionalArrays();
				Assert.isPremiseValid(
					layer.peek < layer.records.capacity() - 1,
					"Cannot insert into a full leaf node, split the node first!"
				);
				final InsertionPosition insertionPosition =
					layer.keys.findKeyPosition(value, 0, layer.peek + 1, this.comparator);
				if (insertionPosition.alreadyPresent()) {
					layer.addToExistingBucket(insertionPosition.position(), pk);
					return NO_NEW_BUCKET;
				}
				layer.insertNewSingleBucket(insertionPosition.position(), value, pk);
				return insertionPosition.position();
			}
		}

		/**
		 * Adds a single `long` payload bucket for `value` (create-or-reject). The bucket is UNIQUE and never promoted to
		 * the overflow bitmap. The value MUST be absent — a present key here is a programming error (uniqueness is enforced
		 * by the caller) and throws a {@link GenericEvitaInternalError}. Mirrors {@link #addRecord(Comparable, int)}'s
		 * transactional / non-transactional branching. See {@link TransactionalBucketBPlusTree#addLongRecord(Comparable, long)}.
		 *
		 * @param value   the value identifying the bucket
		 * @param payload the lone `long` payload to store
		 * @return the slot index the new bucket was inserted at (a new bucket is always inserted, so
		 * {@link #NO_NEW_BUCKET} is never returned)
		 */
		public int addLongRecord(@Nonnull M value, long payload) {
			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			// adding a record mutates this leaf's page: flag it for re-emission
			if (layer == null) {
				this.dirty = true;
			} else {
				layer.dirty = true;
			}
			if (layer == null) {
				Assert.isPremiseValid(
					this.peek < this.records.capacity() - 1,
					"Cannot insert into a full leaf node, split the node first!"
				);
				final InsertionPosition insertionPosition =
					this.keys.findKeyPosition(value, 0, this.peek + 1, this.comparator);
				if (insertionPosition.alreadyPresent()) {
					throw new GenericEvitaInternalError("value already present in a unique long-payload bucket tree");
				}
				insertNewSingleBucket(insertionPosition.position(), value, payload);
				return insertionPosition.position();
			} else {
				decoupleTransactionalArrays();
				Assert.isPremiseValid(
					layer.peek < layer.records.capacity() - 1,
					"Cannot insert into a full leaf node, split the node first!"
				);
				final InsertionPosition insertionPosition =
					layer.keys.findKeyPosition(value, 0, layer.peek + 1, this.comparator);
				if (insertionPosition.alreadyPresent()) {
					throw new GenericEvitaInternalError("value already present in a unique long-payload bucket tree");
				}
				layer.insertNewSingleBucket(insertionPosition.position(), value, payload);
				return insertionPosition.position();
			}
		}

		/**
		 * Reads the `long` payload at the given bucket index (transaction-aware: resolves the record column through the
		 * active layer, mirroring {@link #getRecords()}). Valid for a long-payload tree; an `int` tree widens its pk.
		 *
		 * @param index the bucket index (as returned by {@link #getValueIndex(Comparable)})
		 * @return the `long` payload at `index`
		 */
		public long longRecordAt(int index) {
			return getRecords().longAt(index);
		}

		/**
		 * Removes the whole `long` payload bucket identified by `value`. Mirrors
		 * {@link #removeRecords(Comparable, int...)}'s transactional / non-transactional branching but always deletes the
		 * entire (single) bucket. See {@link TransactionalBucketBPlusTree#removeLongRecord(Comparable)}.
		 *
		 * @param value the value identifying the bucket to remove
		 * @return true if a bucket was deleted, false when the value was absent
		 */
		public boolean removeLongRecord(@Nonnull M value) {
			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			// removing a record mutates this leaf's page: flag it for re-emission
			if (layer == null) {
				this.dirty = true;
			} else {
				layer.dirty = true;
			}
			if (layer == null) {
				final InsertionPosition insertionPosition =
					this.keys.findKeyPosition(value, 0, this.peek + 1, this.comparator);
				if (!insertionPosition.alreadyPresent()) {
					return false;
				}
				deleteBucketAt(insertionPosition.position());
				return true;
			} else {
				decoupleTransactionalArrays();
				final InsertionPosition insertionPosition =
					layer.keys.findKeyPosition(value, 0, layer.peek + 1, this.comparator);
				if (!insertionPosition.alreadyPresent()) {
					return false;
				}
				layer.deleteBucketAt(insertionPosition.position());
				return true;
			}
		}

		/**
		 * Adds multiple records to the bucket identified by `value`, applying the promotion rules. See
		 * {@link TransactionalBucketBPlusTree#addRecord(Comparable, int...)}.
		 *
		 * @param value the value identifying the bucket
		 * @param pks   the record ids to add; must be non-empty
		 * @return the slot index the new bucket was inserted at, or {@link #NO_NEW_BUCKET} when the records joined an
		 * existing bucket
		 */
		public int addRecords(@Nonnull M value, @Nonnull int... pks) {
			final BPlusLeafTreeNode<M> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			// adding records mutates this leaf's page: flag it for re-emission
			if (layer == null) {
				this.dirty = true;
			} else {
				layer.dirty = true;
			}
			if (layer == null) {
				Assert.isPremiseValid(
					this.peek < this.records.capacity() - 1,
					"Cannot insert into a full leaf node, split the node first!"
				);
				final InsertionPosition insertionPosition =
					this.keys.findKeyPosition(value, 0, this.peek + 1, this.comparator);
				if (insertionPosition.alreadyPresent()) {
					addRecordsToExistingBucket(insertionPosition.position(), pks);
					return NO_NEW_BUCKET;
				}
				insertNewBucket(insertionPosition.position(), value, pks);
				return insertionPosition.position();
			} else {
				decoupleTransactionalArrays();
				Assert.isPremiseValid(
					layer.peek < layer.records.capacity() - 1,
					"Cannot insert into a full leaf node, split the node first!"
				);
				final InsertionPosition insertionPosition =
					layer.keys.findKeyPosition(value, 0, layer.peek + 1, this.comparator);
				if (insertionPosition.alreadyPresent()) {
					layer.addRecordsToExistingBucket(insertionPosition.position(), pks);
					return NO_NEW_BUCKET;
				}
				layer.insertNewBucket(insertionPosition.position(), value, pks);
				return insertionPosition.position();
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
			// removing records mutates this leaf's page: flag it for re-emission
			if (layer == null) {
				this.dirty = true;
			} else {
				layer.dirty = true;
			}
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
			if (this.records.intAt(index) == pk) {
				// already the sole record - no-op, stay single
				return;
			}
			// second distinct record - promote to a multi-record bitmap
			final TransactionalBitmap[] overflow = ensureOverflowColumn();
			overflow[index] = new TransactionalBitmap(this.records.intAt(index), pk);
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
			if (pks.length == 1 && pks[0] == this.records.intAt(index)) {
				// the only id being added is the one already held - keep the compact form
				return;
			}
			// promote to a bitmap holding the existing id plus all added ids (the bitmap dedupes & orders)
			final TransactionalBitmap[] overflow = ensureOverflowColumn();
			final TransactionalBitmap promoted = new TransactionalBitmap(this.records.intAt(index));
			promoted.addAll(pks);
			overflow[index] = promoted;
		}

		/**
		 * Removes records from the bucket at `index`. A matching single bucket is deleted; a multi bucket has the ids
		 * removed in place and is deleted (with its bitmap layer released) when it drops to zero records. A multi bucket
		 * reduced to exactly one record is **not** demoted here — it stays a bitmap until the leaf commit-merge reverts
		 * it to the primitive single form (see the class javadoc), so a bucket never thrashes its representation within
		 * one transaction.
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
			final int held = this.records.intAt(index);
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
		 * Inserts a new single-record bucket at `position`, shifting all three columns right by one. The payload is widened
		 * to `long` so the same helper serves both the `int` record-set path (an `int` pk auto-widens, then narrows back in
		 * {@link IntRecordColumn#insertAt}) and the `long`-payload path (a packed `long` stored verbatim by
		 * {@link LongRecordColumn#insertAt}).
		 *
		 * @param position the position at which to insert the new bucket
		 * @param value    the bucket value
		 * @param payload  the lone record id (widened `int` pk) or packed `long` payload
		 */
		private void insertNewSingleBucket(int position, @Nonnull M value, long payload) {
			this.keys.insertKeyAt(position, value);
			this.records.insertAt(position, payload);
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
				this.records.insertAt(position, pks[0]);
				if (this.overflow != null) {
					shiftOverflowForSingleInsert(this.overflow, position);
				}
			} else {
				// multi bucket from the start - records[position] is don't-care
				this.records.insertAt(position, 0);
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
			this.records.removeAt(index);
			this.keys.clearAt(this.peek);
			this.records.clearAt(this.peek);
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
				this.overflow = new TransactionalBitmap[this.records.capacity()];
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
				this.overflow = new TransactionalBitmap[this.records.capacity()];
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
				layer.overflow = new TransactionalBitmap[layer.records.capacity()];
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
				if (layer.records == this.records) {
					layer.records = this.records.duplicate();
				}
				//noinspection ArrayEquality
				if (this.overflow != null && layer.overflow == this.overflow) {
					layer.overflow = new TransactionalBitmap[this.overflow.length];
					System.arraycopy(this.overflow, 0, layer.overflow, 0, this.peek + 1);
				}
			}
		}

		/**
		 * Immutable savepoint memento of a leaf node's three columns. The key column and single-record column are
		 * private deep / array copies; the overflow array is a private shallow clone whose {@link TransactionalBitmap}
		 * elements are shared by design (each owns its own snapshotted layer). See {@link #snapshot}.
		 *
		 * @param keys     deep copy of the key (bucket-value) column
		 * @param records  deep copy of the single-record column
		 * @param overflow shallow clone of the lazy overflow column, or {@code null}
		 * @param peek     the last occupied column index
		 */
		record BPlusLeafNodeMemento<M extends Comparable<M>>(
			@Nonnull ValueColumn<M> keys,
			@Nonnull RecordColumn records,
			@Nullable TransactionalBitmap[] overflow,
			int peek
		) {
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
		private RecordColumn leafRecords;
		@Nullable private TransactionalBitmap[] leafOverflow;
		private int leafPeek;
		private long leafId;

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
			return this.leafRecords.intAt(this.currentIndex);
		}

		@Override
		public long longRecordId() {
			Assert.isPremiseValid(this.positioned, "Cursor is not positioned at a bucket!");
			return this.leafRecords.longAt(this.currentIndex);
		}

		@Nonnull
		@Override
		public Bitmap records() {
			Assert.isPremiseValid(this.positioned, "Cursor is not positioned at a bucket!");
			if (this.leafOverflow != null && this.leafOverflow[this.currentIndex] != null) {
				return this.leafOverflow[this.currentIndex];
			}
			return new SingleRecordBitmap(this.leafRecords.intAt(this.currentIndex));
		}

		@Override
		public int size() {
			Assert.isPremiseValid(this.positioned, "Cursor is not positioned at a bucket!");
			if (this.leafOverflow != null && this.leafOverflow[this.currentIndex] != null) {
				return this.leafOverflow[this.currentIndex].size();
			}
			return 1;
		}

		@Override
		public long currentLeafId() {
			Assert.isPremiseValid(this.positioned, "Cursor is not positioned at a bucket!");
			return this.leafId;
		}

		private void loadCurrentLeaf() {
			//noinspection unchecked
			final BPlusLeafTreeNode<M> leaf =
				(BPlusLeafTreeNode<M>) this.path[this.path.length - 1][this.pathIndex[this.pathIndex.length - 1]];
			this.leafKeys = leaf.getKeyColumn();
			this.leafRecords = leaf.getRecords();
			this.leafOverflow = leaf.getOverflow();
			this.leafPeek = leaf.getPeek();
			this.leafId = leaf.getId();
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
		private RecordColumn leafRecords;
		@Nullable private TransactionalBitmap[] leafOverflow;
		private int leafPeek;
		private long leafId;

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
			return this.leafRecords.intAt(this.currentIndex);
		}

		@Override
		public long longRecordId() {
			Assert.isPremiseValid(this.positioned, "Cursor is not positioned at a bucket!");
			return this.leafRecords.longAt(this.currentIndex);
		}

		@Nonnull
		@Override
		public Bitmap records() {
			Assert.isPremiseValid(this.positioned, "Cursor is not positioned at a bucket!");
			if (this.leafOverflow != null && this.leafOverflow[this.currentIndex] != null) {
				return this.leafOverflow[this.currentIndex];
			}
			return new SingleRecordBitmap(this.leafRecords.intAt(this.currentIndex));
		}

		@Override
		public int size() {
			Assert.isPremiseValid(this.positioned, "Cursor is not positioned at a bucket!");
			if (this.leafOverflow != null && this.leafOverflow[this.currentIndex] != null) {
				return this.leafOverflow[this.currentIndex].size();
			}
			return 1;
		}

		@Override
		public long currentLeafId() {
			Assert.isPremiseValid(this.positioned, "Cursor is not positioned at a bucket!");
			return this.leafId;
		}

		private void loadCurrentLeaf() {
			//noinspection unchecked
			final BPlusLeafTreeNode<M> leaf =
				(BPlusLeafTreeNode<M>) this.path[this.path.length - 1][this.pathIndex[this.pathIndex.length - 1]];
			this.leafKeys = leaf.getKeyColumn();
			this.leafRecords = leaf.getRecords();
			this.leafOverflow = leaf.getOverflow();
			this.leafPeek = leaf.getPeek();
			this.leafId = leaf.getId();
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
	record Cursor<M extends Comparable<M>>(
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
