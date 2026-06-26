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
	 * The {@code transactionalLayer} flag for nodes a split creates. The {@code int→long} tree (whose nodes are
	 * {@link io.evitadb.core.transaction.memory.Snapshotable} and which is never rebuilt by re-inserting outside an
	 * active transaction) returns {@code true} so split offspring join the diff layer and their in-savepoint mutations
	 * can be rolled back. A tree that is bulk-rebuilt by inserting during the commit-merge (e.g. the element-keyed price
	 * tree's `newPriceRecordTree` invoked from `attachToCatalog`) must return {@code !Transaction.isTransactionAvailable()}
	 * instead: inside that already-finalized transaction context a split offspring with the flag set would try to open a
	 * fresh diff layer and fail, so it has to mutate in place.
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
		final ArrayList<CursorLevel> path = new ArrayList<>(this.size() == 0 ? 1 : (int) (Math.log(this.size()) + 1));
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
		final int mid = (this.valueBlockSize + 1) / 2;
		final int[] originKeys = internal.getKeys();
		final BPlusTreeNode<?>[] originChildren = internal.getChildren();

		// Whether split offspring join the transactional diff layer: true for the savepoint-participating int→long tree,
		// transaction-aware for the price tree that is bulk-rebuilt during commit-merge (see the hook's contract).
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

		// Move the other half to the start of existing arrays of the former internal node in the right internal node
		final AbstractIntKeyedInternalNode<?> rightInternal = createInternalNode(
			originKeys,
			originChildren,
			mid,
			leftInternal.getKeys().length,
			mid,
			leftInternal.getChildren().length,
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
					this.valueBlockSize,
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
