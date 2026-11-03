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
import io.evitadb.dataType.ConsistencySensitiveDataStructure;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PrimitiveIterator.OfInt;

/**
 * Shared base of the {@code int}-routed B+ trees of the family — {@link TransactionalIntToLongBPlusTree} and
 * {@link TransactionalElementBPlusTree}. Both descend the tree on a primitive {@code int} separator key (via
 * {@link AbstractIntKeyedInternalNode#searchIndex(int)} and {@code int}-key iterators), so the descent, the internal-node
 * split, the consistency checks and the {@link ConsistencyReport consistency report} are byte-for-byte identical between
 * them. That logic lives here exactly once; the typed seams a generic base cannot fill — constructing the concrete
 * internal node and iterating the concrete key column — stay in the subclasses behind the abstract hooks below.
 *
 * The class is deliberately limited to the two {@code int}-routed trees and is **not** pushed up into the fully
 * key-agnostic {@link AbstractTransactionalBPlusTree}: the {@code long[]}- and {@code Object[]}-routed trees route on a
 * wider key, so folding them in would force the descent / iteration behind boxing — the allocation regression this
 * family is engineered to avoid. Sharing is free here precisely because both trees already route on the identical
 * {@code int} key, reached through the shared {@link AbstractIntKeyedInternalNode}.
 *
 * Performance note: hoisting the hot descent ({@link #createCursor(int)} / {@link #addCursorLevels}) is throughput-
 * neutral. The only change is the receiver of {@code searchIndex} widening to {@link AbstractIntKeyedInternalNode}, but
 * that method is never overridden, so class-hierarchy analysis devirtualizes and inlines it exactly as before. The
 * internal-node split adds a single cold virtual dispatch through {@link #createInternalNode} (taken only on internal-node
 * overflow) and allocates nothing extra.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
abstract class AbstractIntKeyedBPlusTree extends AbstractTransactionalBPlusTree
	implements ConsistencySensitiveDataStructure {
	@Serial private static final long serialVersionUID = 8159528799470124133L;

	/**
	 * Computes the leaf sizes of a bulk build: full leaves of `valueBlockSize - 1` elements and a remainder. A
	 * remainder below `minimum` is topped up from the previous leaf. The constructor guarantees that the previous
	 * leaf can spare the missing elements without underflowing.
	 *
	 * @param count   the number of elements, positive
	 * @param minimum the minimum occupancy of a non-root leaf
	 * @return the size of each leaf in key order
	 */
	@Nonnull
	protected int[] bulkLeafSizes(int count, int minimum) {
		final int fill = this.valueBlockSize - 1;
		final int fullLeaves = count / fill;
		final int remainder = count % fill;
		if (remainder == 0) {
			final int[] sizes = new int[fullLeaves];
			Arrays.fill(sizes, fill);
			return sizes;
		}
		if (fullLeaves == 0 || remainder >= minimum) {
			final int[] sizes = new int[fullLeaves + 1];
			Arrays.fill(sizes, fill);
			sizes[fullLeaves] = remainder;
			return sizes;
		}
		final int missing = minimum - remainder;
		final int[] sizes = new int[fullLeaves + 1];
		Arrays.fill(sizes, fill);
		sizes[fullLeaves - 1] = fill - missing;
		sizes[fullLeaves] = minimum;
		return sizes;
	}

	/**
	 * Adopts the arrays of a freshly assembled internal node without copying them.
	 *
	 * @param keys               the full-capacity separator array
	 * @param children           the full-capacity child array
	 * @param peek               the last occupied child slot
	 * @param transactionalLayer whether the node participates in transactional memory
	 * @return the assembled internal node
	 */
	@Nonnull
	protected abstract AbstractIntKeyedInternalNode<?> createAssembledInternalNode(
		@Nonnull int[] keys, @Nonnull BPlusTreeNode<?>[] children, int peek, boolean transactionalLayer
	);

	/**
	 * Builds one internal level over `level` and recurses until a single node (the root) remains. The level below is
	 * partitioned into `ceil(size / maxChildren)` parents, each receiving an evenly distributed contiguous run of
	 * children so no non-root parent underflows.
	 *
	 * @param level              the ordered nodes of the level immediately below the one being built
	 * @param transactionalLayer whether the internal nodes participate in the transactional memory layer
	 * @return the root of the assembled subtree
	 */
	@Nonnull
	protected BPlusTreeNode<?> buildSpine(@Nonnull List<? extends BPlusTreeNode<?>> level, boolean transactionalLayer) {
		if (level.size() == 1) {
			return level.getFirst();
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
		final List<AbstractIntKeyedInternalNode<?>> parents = new ArrayList<>(parentCount);
		int childIndex = 0;
		for (int p = 0; p < parentCount; p++) {
			final int childCount = baseChildren + (p < withExtra ? 1 : 0);
			parents.add(buildInternalNode(level, childIndex, childCount, transactionalLayer));
			childIndex += childCount;
		}
		return buildSpine(parents, transactionalLayer);
	}

	/**
	 * Constructs a single internal node holding `childCount` children taken from `children` starting at `from`. The
	 * separator before child `i` (for `i >= 1`) is that child's left boundary key. The key / children arrays are
	 * allocated at the node's full capacity (mirroring split-created nodes), leaving the unused tail at its default.
	 *
	 * @param children           the ordered children of the level below
	 * @param from               the index of the first child this node owns
	 * @param childCount         the number of children this node owns (>= 1)
	 * @param transactionalLayer whether the node participates in the transactional memory layer
	 * @return the assembled internal node
	 */
	@Nonnull
	private AbstractIntKeyedInternalNode<?> buildInternalNode(
		@Nonnull List<? extends BPlusTreeNode<?>> children, int from, int childCount, boolean transactionalLayer
	) {
		final int[] keys = new int[this.internalNodeBlockSize];
		final BPlusTreeNode<?>[] childArray = new BPlusTreeNode[this.internalNodeBlockSize + 1];
		for (int i = 0; i < childCount; i++) {
			final BPlusTreeNode<?> child = children.get(from + i);
			childArray[i] = child;
			if (i > 0) {
				// separator i-1 routes to child i — the left boundary key invariant the tree enforces
				keys[i - 1] = AbstractIntKeyedInternalNode.leftBoundaryKeyOf(child);
			}
		}
		return createAssembledInternalNode(keys, childArray, childCount - 1, transactionalLayer);
	}

	/**
	 * Recursively traverses the B+ tree to find the leaf node responsible for the specified key, appending the internal
	 * nodes visited to the cursor path along the way.
	 *
	 * @param currentNode the current internal node being traversed
	 * @param key         the key whose responsible leaf is being located
	 * @param path        the list collecting the visited cursor levels
	 */
	private static void addCursorLevels(
		@Nonnull AbstractIntKeyedInternalNode<?> currentNode,
		int key,
		@Nonnull List<CursorLevel> path
	) {
		final int childIndex = currentNode.searchIndex(key);
		final BPlusTreeNode<?>[] children = currentNode.getChildren();
		path.add(new CursorLevel(children, childIndex, currentNode.getPeek()));
		// if the child is an internal node, continue traversing down the tree
		if (children[childIndex] instanceof AbstractIntKeyedInternalNode<?> childInternalNode) {
			addCursorLevels(childInternalNode, key, path);
		}
	}

	/**
	 * Verifies that each internal-node separator key equals the left boundary key of the child it routes to, recursing
	 * down the whole spine. Both node kinds expose their boundary key through {@link IntBoundaryKeyedNode}.
	 *
	 * @param node the node to verify; recursion is a no-op for leaf nodes
	 * @throws IllegalStateException if any separator key disagrees with its child's left boundary key
	 */
	protected static void verifyInternalNodeKeys(@Nonnull BPlusTreeNode<?> node) {
		if (node instanceof AbstractIntKeyedInternalNode<?> internalNode) {
			final int[] keys = internalNode.getKeys();
			final BPlusTreeNode<?>[] children = internalNode.getChildren();
			if (internalNode.getPeek() >= 0) {
				verifyInternalNodeKeys(children[0]);
			}
			for (int i = 0; i < internalNode.getPeek(); i++) {
				final int key = keys[i];
				final BPlusTreeNode<?> child = children[i + 1];
				if (child instanceof AbstractIntKeyedInternalNode<?> childInternalNode) {
					if (childInternalNode.getLeftBoundaryKey() != key) {
						throw new IllegalStateException(
							"Internal node " + childInternalNode + " has a different left boundary key (" +
								childInternalNode.getLeftBoundaryKey() + ") than the internal node key (" + key + ")!"
						);
					}
					verifyInternalNodeKeys(childInternalNode);
				} else if (child instanceof IntBoundaryKeyedNode childLeafNode) {
					if (childLeafNode.getLeftBoundaryKey() != key) {
						throw new IllegalStateException(
							"Leaf node " + child + " has a different key (" + childLeafNode.getLeftBoundaryKey() +
								") than the internal node key (" + key + ")!"
						);
					}
				} else {
					throw new IllegalStateException("Unknown node type: " + child);
				}
			}
		}
	}

	/**
	 * Constructor shared by the two int-keyed trees (int→long and element-keyed). It only forwards the block-size
	 * configuration, root and size to {@link AbstractTransactionalBPlusTree}; the shared cursor, split and consistency
	 * logic this class hosts needs no additional state of its own.
	 *
	 * @param valueBlockSize           maximum number of values in a leaf node
	 * @param minValueBlockSize        minimum number of values in a leaf node
	 * @param internalNodeBlockSize    maximum number of keys in an internal node
	 * @param minInternalNodeBlockSize minimum number of keys in an internal node
	 * @param root                     the initial root node of the tree
	 * @param size                     the initial number of elements in the tree
	 */
	protected AbstractIntKeyedBPlusTree(
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
	public ConsistencyReport getConsistencyReport() {
		try {
			final BPlusTreeNode<?> theRoot = getRoot();
			final int height = verifyAndReturnHeight(this);
			verifyMinimalCountOfValuesInNodes(theRoot, this.minValueBlockSize, this.minInternalNodeBlockSize, true);
			verifyInternalNodeKeys(theRoot);

			final int theSize = this.size();
			verifyForwardKeyIterator(theSize);
			verifyReverseKeyIterator(theSize);
			return new ConsistencyReport(
				ConsistencyState.CONSISTENT,
				"B+ tree is consistent with height of " + height + " levels and " + theSize + " elements."
			);
		} catch (IllegalStateException e) {
			return new ConsistencyReport(ConsistencyState.BROKEN, e.getMessage());
		}
	}

	/**
	 * Returns a primitive iterator over the tree's keys in ascending order.
	 *
	 * @return an ascending {@code int} key iterator
	 */
	@Nonnull
	public abstract OfInt keyIterator();

	/**
	 * Returns a primitive iterator over the tree's keys in descending order.
	 *
	 * @return a descending {@code int} key iterator
	 */
	@Nonnull
	public abstract OfInt keyReverseIterator();

	/**
	 * Creates a new internal node with a single key separating two children (used when a split produces a new root).
	 * This is a typed seam: a generic base cannot {@code new} the concrete per-tree internal node.
	 *
	 * @param blockSize          the maximum number of keys the node can hold
	 * @param key                the initial separator key
	 * @param leftLeaf           the left child
	 * @param rightLeaf          the right child
	 * @param transactionalLayer whether the node participates in the transactional memory layer
	 * @return the produced internal node
	 */
	@Nonnull
	protected abstract AbstractIntKeyedInternalNode<?> createInternalNode(
		int blockSize,
		int key,
		@Nonnull BPlusTreeNode<?> leftLeaf,
		@Nonnull BPlusTreeNode<?> rightLeaf,
		boolean transactionalLayer
	);

	/**
	 * Creates a new internal node by copying a range of keys and children from existing arrays (used during a split).
	 * This is a typed seam: a generic base cannot {@code new} the concrete per-tree internal node.
	 *
	 * @param originKeys         the source array of keys to copy from
	 * @param originChildren     the source array of child nodes to copy from
	 * @param keyStart           the start index (inclusive) in the origin keys array
	 * @param keyEnd             the end index (exclusive) in the origin keys array
	 * @param childrenStart      the start index (inclusive) in the origin children array
	 * @param childrenEnd        the end index (exclusive) in the origin children array
	 * @param transactionalLayer whether the node participates in the transactional memory layer
	 * @return the produced internal node
	 */
	@Nonnull
	protected abstract AbstractIntKeyedInternalNode<?> createInternalNode(
		@Nonnull int[] originKeys,
		@Nonnull BPlusTreeNode<?>[] originChildren,
		int keyStart, int keyEnd,
		int childrenStart, int childrenEnd,
		boolean transactionalLayer
	);

	/**
	 * The `transactionalLayer` flag for nodes a split creates. Both trees let split offspring join the diff layer:
	 * a savepoint opened later in the same transaction must be able to roll back writes to those nodes. Price-index
	 * commit merges carry the existing tree forward without re-inserting records, so split nodes no longer need to
	 * support inserts inside a finalized commit merge. Outside a transaction the nodes join later transactions' layers;
	 * cold-load bulk construction likewise creates participating nodes outside any bound transaction.
	 *
	 * @return whether nodes produced by a split participate in the transactional memory layer
	 */
	protected abstract boolean splitNodesJoinTransactionalLayer();

	/**
	 * Finds the leaf node in the B+ tree that should contain the specified key. The search begins at the root and
	 * descends to the leaf by following the appropriate child pointers of the internal nodes.
	 *
	 * @param key the key to search for within the B+ tree
	 * @return the cursor to the leaf node responsible for storing the provided key; note that the leaf may not actually
	 * contain the key - but it is the correct leaf node for accommodating it
	 */
	@Nonnull
	protected Cursor createCursor(int key) {
		final ArrayList<CursorLevel> path = new ArrayList<>(estimatedPathLength());
		final BPlusTreeNode<?> theRoot = this.getRoot();
		final BPlusTreeNode<?>[] rootSiblings = new BPlusTreeNode<?>[]{theRoot};
		path.add(new CursorLevel(rootSiblings, 0, 0));
		// if the root is internal node, add the levels to the path until the leaf node is reached
		if (theRoot instanceof AbstractIntKeyedInternalNode<?> rootInternalNode) {
			addCursorLevels(rootInternalNode, key, path);
		}
		return new Cursor(path);
	}

	/**
	 * Allocation-free leaf descent for READ-ONLY lookups: walks the root-to-leaf spine choosing each child by the same
	 * {@link AbstractIntKeyedInternalNode#searchIndex(int)} rule {@link #createCursor(int)} uses, but WITHOUT capturing
	 * the cursor path (no {@link CursorLevel} list, no {@link Cursor}). Reads resolve the transaction-aware child links
	 * exactly like the cursor descent. Splits / deletes still need the full {@link #createCursor(int)} (they mutate the
	 * captured path); this is purely the fast path for {@code search}-style probes on the query hot loop.
	 *
	 * @param key the key whose responsible leaf is located
	 * @return the leaf node that should hold the key (it may not actually contain it)
	 */
	@Nonnull
	protected BPlusTreeNode<?> findLeafNode(int key) {
		BPlusTreeNode<?> node = this.getRoot();
		while (node instanceof AbstractIntKeyedInternalNode<?> internalNode) {
			node = internalNode.getChildren()[internalNode.searchIndex(key)];
		}
		return node;
	}

	/**
	 * Replaces a node in its parent with two new nodes produced by a split, refreshing the parent's separator keys and
	 * cascading the split upward if the parent overflows in turn.
	 *
	 * @param original the original node that was split
	 * @param left     the left node resulting from the split
	 * @param right    the right node resulting from the split
	 * @param key      the partition key separating the left and right nodes
	 * @param cursor   the cursor representing the path from the root to the original node
	 */
	protected void replaceNodeInParentInternalNode(
		@Nonnull BPlusTreeNode<?> original,
		@Nonnull BPlusTreeNode<?> left,
		@Nonnull BPlusTreeNode<?> right,
		int key,
		@Nonnull CursorWithLevel cursor
	) {
		final AbstractIntKeyedInternalNode<?> parent = (AbstractIntKeyedInternalNode<?>) cursor.parent();

		Assert.notNull(parent, "Parent node must not be null.");
		parent.adaptToLeafSplit(key, original, left, right);

		if (parent.isFull()) {
			splitInternalNode(parent, new CursorWithLevel(cursor.path(), cursor.level() - 1));
		}
	}

	/**
	 * Verifies the forward key iterator by checking that the keys are returned in strictly increasing order and that
	 * the number of keys matches the expected size.
	 *
	 * @param size the expected number of keys in the tree
	 * @throws IllegalStateException if the iterator returns non-increasing keys or the count mismatches
	 */
	private void verifyForwardKeyIterator(int size) {
		int actualSize = 0;
		int previousKey = Integer.MIN_VALUE;
		final OfInt it = keyIterator();
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
	 * Verifies the reverse key iterator by checking that the keys are returned in strictly decreasing order and that
	 * the number of keys matches the expected size.
	 *
	 * @param size the expected number of keys in the tree
	 * @throws IllegalStateException if the iterator returns non-decreasing keys or the count mismatches
	 */
	private void verifyReverseKeyIterator(int size) {
		int actualSize = 0;
		int previousKey = Integer.MIN_VALUE;
		final OfInt it = keyReverseIterator();
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
	 * Splits a full internal node into two nodes to maintain the B+ tree properties. The lower half of the keys goes to
	 * a new left node and the upper half to a new right node; if the node being split is the root, a new root is
	 * created, otherwise the parent is updated to reflect the split.
	 *
	 * @param internal the internal node to be split
	 * @param cursor   the cursor representing the path from the root to the internal node being split
	 */
	private void splitInternalNode(
		@Nonnull AbstractIntKeyedInternalNode<?> internal,
		@Nonnull CursorWithLevel cursor
	) {
		// The node is full at split time (the only caller guards with isFull()), so occupancy equals capacity. Derive the
		// midpoint from the actual key count rather than valueBlockSize: internal nodes are sized by internalNodeBlockSize,
		// and spines bulk-assembled from persisted pages carry that (smaller) capacity, so a valueBlockSize-derived midpoint
		// overruns their arrays. keyCount is the number of separator keys (children = keyCount + 1).
		final int keyCount = internal.keyCount();
		final int mid = (keyCount + 1) / 2;
		final int[] originKeys = internal.getKeys();
		final BPlusTreeNode<?>[] originChildren = internal.getChildren();

		// Split offspring participate so savepoints opened later in this transaction can roll back their writes.
		final boolean splitNodesTransactional = splitNodesJoinTransactionalLayer();

		// Move half the keys to the new arrays of the left internal node — the split constructor always allocates fresh
		// arrays, so the former node's arrays stay intact for a per-entity savepoint rollback.
		final AbstractIntKeyedInternalNode<?> leftInternal = createInternalNode(
			originKeys,
			originChildren,
			0,
			mid - 1,
			0,
			mid,
			splitNodesTransactional
		);

		// Move the other half to the start of existing arrays of the former internal node in the right internal node.
		// End bounds are the origin's actual occupancy (keyCount separators, keyCount + 1 children), not the array
		// capacity — capacity may exceed occupancy after the internalNodeBlockSize sizing fix, and only the live range
		// must be copied.
		final AbstractIntKeyedInternalNode<?> rightInternal = createInternalNode(
			originKeys,
			originChildren,
			mid,
			keyCount,
			mid,
			keyCount + 1,
			splitNodesTransactional
		);

		// remove changes of the previous node - it gets replaced
		if (Transaction.getTransactionalMemoryLayerIfExists(internal) != null) {
			internal.removeLayer();
		}

		// if the root splits, create a new root
		if (internal == this.getRoot()) {
			this.setRoot(
				createInternalNode(
					this.internalNodeBlockSize,
					rightInternal.getLeftBoundaryKey(),
					leftInternal, rightInternal,
					splitNodesTransactional
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

}
