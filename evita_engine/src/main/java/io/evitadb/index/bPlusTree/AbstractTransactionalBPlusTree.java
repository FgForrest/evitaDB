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

import io.evitadb.core.exception.DataStructureCorruptedException;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.DirtyScopeValidator;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.index.reference.TransactionalReference;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Key-type-agnostic base of the transactional B+ tree family. It owns the parts of a B+ tree that do not depend on the
 * concrete key array type — the block-size configuration, the transactional root/size references, the structure
 * maintenance algorithm ({@link #consolidate(Cursor)} with sibling borrow/merge and root collapse), the leftmost /
 * rightmost cursor builders, the parent-key propagation, the balance/occupancy verifiers, and the cursor records that
 * the iterators and mutators navigate with. Every node operation it invokes goes through the {@link BPlusTreeNode} /
 * {@link InternalBPlusTreeNode} SPI, so the base never observes whether keys are {@code long[]}, {@code int[]} or
 * {@code Object[]} — and therefore never boxes a primitive key.
 *
 * The typed parts deliberately stay in the concrete subclasses: the public key-typed API, the {@code createCursor(key)}
 * descent (the hot {@code searchIndex(key)}), the node split family (it allocates the typed key / value arrays), the
 * {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} surface (its self type and the leaf
 * producer-value cleanup are value-typed), the key-typed verifiers, the iterators (their per-element reads index the
 * cached typed leaf arrays) and the typed node classes themselves. The only typed seam the base reaches back through is
 * {@link #newEmptyLeaf()}, used when a collapsing root must be replaced by a fresh empty leaf.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public abstract class AbstractTransactionalBPlusTree implements Serializable {
	@Serial private static final long serialVersionUID = -941486965526807368L;
	/**
	 * Sentinel value of a leaf's {@link LeafBPlusTreeNode#getPageSequence()} before it has been assigned a persistence
	 * page sequence (a split-born or freshly created leaf). The granular write path allocates a real page for such a leaf
	 * and stamps it via {@link LeafBPlusTreeNode#setPageSequence(int)}.
	 */
	public static final int UNASSIGNED_PAGE_SEQUENCE = -1;
	/**
	 * Maximum number of keys per leaf node. Use odd number. The number of children in internal nodes is one more.
	 */
	@Getter protected final int internalNodeBlockSize;
	/**
	 * Minimum number of keys per internal node. Controls branching factor for internal nodes.
	 */
	@Getter protected final int minInternalNodeBlockSize;
	/**
	 * Minimum number of keys = values per leaf node. Controls branching factor for leaf nodes.
	 */
	@Getter protected final int minValueBlockSize;
	/**
	 * Root node of the tree.
	 */
	protected final TransactionalReference<BPlusTreeNode<?>> root;
	/**
	 * Number of elements in the tree.
	 */
	protected final TransactionalReference<Integer> size;
	/**
	 * Maximum number of keys = values per leaf node. Use odd number. The number of keys in internal nodes is one less.
	 */
	@Getter protected final int valueBlockSize;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();

	/**
	 * Updates the keys in the parent nodes of a B+ Tree based on changes in a specific path.
	 * This method propagates changes up the tree as necessary.
	 *
	 * @param cursorWithLevel the cursor representing the path from the root to the node where the changes occurred
	 */
	protected static void updateParentKeys(@Nonnull CursorWithLevel cursorWithLevel) {
		InternalBPlusTreeNode<?> immediateParent = cursorWithLevel.parent();
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
	protected static int verifyAndReturnHeight(@Nonnull AbstractTransactionalBPlusTree tree) {
		final BPlusTreeNode<?> root = tree.getRoot();
		if (root instanceof InternalBPlusTreeNode<?> internalNode) {
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
	 * Verifies that the given B+ tree node and its child nodes satisfy the minimum required values
	 * in their blocks. Throws an IllegalStateException if any node violates the minimum count condition.
	 *
	 * @param node                     the B+ tree node to verify, which can be an internal node or a leaf node. Must not be null.
	 * @param minValueBlockSize        the minimum number of values required in a leaf node that is not the root.
	 * @param minInternalNodeBlockSize the minimum number of values required in an internal node.
	 * @param isRoot                   a boolean indicating if the current node being verified is the root of the tree.
	 */
	protected static void verifyMinimalCountOfValuesInNodes(
		@Nonnull BPlusTreeNode<?> node, int minValueBlockSize, int minInternalNodeBlockSize, boolean isRoot) {
		if (node instanceof InternalBPlusTreeNode<?> internalNode) {
			// the minimum occupancy invariant constrains the number of keys, not children; an internal node
			// with c children holds c - 1 keys, so checking the child count (size()) would be one slot too
			// lenient and would accept a node that is genuinely under-occupied in keys; the root is exempt
			// from the minimum because it may legitimately hold a single key
			if (!isRoot && internalNode.keyCount() < minInternalNodeBlockSize) {
				throw new IllegalStateException(
					"Internal node " + internalNode + " has less than " + minInternalNodeBlockSize +
						" keys (" + internalNode.keyCount() + ")!"
				);
			}
			// recurse into the whole subtree regardless of whether this node is the root - the occupancy of
			// the descendants must always be validated, only the root itself is exempt from the minimum
			for (int i = 0; i < internalNode.size(); i++) {
				verifyMinimalCountOfValuesInNodes(
					internalNode.getChildren()[i], minValueBlockSize, minInternalNodeBlockSize, false
				);
			}
		} else {
			if (node.size() < minValueBlockSize && !isRoot) {
				throw new IllegalStateException(
					"Leaf node " + node + " has less than " + minValueBlockSize + " values (" + node.size() + ")!"
				);
			}
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
		if (node instanceof InternalBPlusTreeNode<?> internalNode) {
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
	private static int verifyAndReturnHeight(@Nonnull InternalBPlusTreeNode<?> node, int currentHeight) {
		final BPlusTreeNode<?> child = node.getChildren()[0];
		if (child instanceof InternalBPlusTreeNode<?> internalChild) {
			return verifyAndReturnHeight(internalChild, currentHeight + 1);
		} else {
			return currentHeight + 1;
		}
	}

	/**
	 * This method recursively traverses the B+ tree to find the least (leftmost) leaf node.
	 * It also populates the path traversed with internal nodes.
	 *
	 * @param currentNode The current internal tree node being traversed. Must not be null.
	 * @param path        A list to store the sequence of internal nodes visited. Must not be null.
	 */
	private static void addLeftmostCursorLevels(
		@Nonnull InternalBPlusTreeNode<?> currentNode,
		@Nonnull List<CursorLevel> path
	) {
		final BPlusTreeNode<?>[] children = currentNode.getChildren();
		path.add(new CursorLevel(children, 0, currentNode.getPeek()));
		// if the child is an internal node, continue traversing down the tree
		if (children[0] instanceof InternalBPlusTreeNode<?> childInternalNode) {
			addLeftmostCursorLevels(childInternalNode, path);
		}
	}

	/**
	 * This method recursively traverses the B+ tree to find the greatest (rightmost) leaf node.
	 * It also populates the path traversed with internal nodes.
	 *
	 * @param currentNode The current internal tree node being traversed. Must not be null.
	 * @param path        A list to store the sequence of internal nodes visited. Must not be null.
	 */
	private static void addRightmostCursorLevels(
		@Nonnull InternalBPlusTreeNode<?> currentNode,
		@Nonnull List<CursorLevel> path
	) {
		final int currentNodePeek = currentNode.getPeek();
		final BPlusTreeNode<?>[] children = currentNode.getChildren();
		path.add(new CursorLevel(children, currentNodePeek, currentNodePeek));
		// if the child is an internal node, continue traversing down the tree
		if (children[currentNodePeek] instanceof InternalBPlusTreeNode<?> childInternalNode) {
			addRightmostCursorLevels(childInternalNode, path);
		}
	}

	/**
	 * Recursively collects the leaf nodes reachable from `node` in ascending key order via an in-order, left-to-right
	 * walk of the internal spine. A node that is not an {@link InternalBPlusTreeNode} is, by construction, a leaf.
	 *
	 * @param node   the subtree root to descend
	 * @param leaves the accumulator receiving leaves in order
	 */
	private static void collectLeaves(@Nonnull BPlusTreeNode<?> node, @Nonnull List<BPlusTreeNode<?>> leaves) {
		if (node instanceof InternalBPlusTreeNode<?> internal) {
			final BPlusTreeNode<?>[] children = internal.getChildren();
			final int peek = internal.getPeek();
			for (int i = 0; i <= peek; i++) {
				collectLeaves(children[i], leaves);
			}
		} else {
			// a non-internal node is, by construction, a leaf — the leaf-page payload the granular write path emits
			leaves.add(node);
		}
	}

	/**
	 * Constructor shared by all concrete trees. Validates the block-size configuration (the value-type specific checks
	 * stay in the subclass) and installs the initial root and size into transactional references.
	 *
	 * @param valueBlockSize           maximum number of values in a leaf node
	 * @param minValueBlockSize        minimum number of values in a leaf node
	 * @param internalNodeBlockSize    maximum number of keys in an internal node
	 * @param minInternalNodeBlockSize minimum number of keys in an internal node
	 * @param root                     the initial root node of the tree
	 * @param size                     the initial number of elements in the tree
	 */
	protected AbstractTransactionalBPlusTree(
		int valueBlockSize,
		int minValueBlockSize,
		int internalNodeBlockSize,
		int minInternalNodeBlockSize,
		@Nonnull BPlusTreeNode<?> root,
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
		this.valueBlockSize = valueBlockSize;
		this.minValueBlockSize = minValueBlockSize;
		this.internalNodeBlockSize = internalNodeBlockSize;
		this.minInternalNodeBlockSize = minInternalNodeBlockSize;
		this.root = new TransactionalReference<>(root);
		this.size = new TransactionalReference<>(size);
	}

	/**
	 * Retrieves the root node of the B+ tree.
	 *
	 * @return the root node of the B+ tree, guaranteed to be non-null.
	 */
	@Nonnull
	public BPlusTreeNode<?> getRoot() {
		return Objects.requireNonNull(this.root.get());
	}

	/**
	 * Sets the root node of the B+ tree to the specified new root node.
	 * This method removes the changes associated with the previous root
	 * before replacing it with the new root.
	 *
	 * @param newRoot the new root node to be set for the B+ tree; must not be null
	 */
	public void setRoot(@Nonnull BPlusTreeNode<?> newRoot) {
		// remove changes of the previous root - it gets replaced
		final BPlusTreeNode<?> currentRoot = getRoot();
		if (Transaction.getTransactionalMemoryLayerIfExists(currentRoot) != null) {
			currentRoot.removeLayer();
		}
		// set new root
		this.root.set(newRoot);
	}

	/**
	 * Returns whether the tree's root is an internal (routing) node, i.e. the tree spans more than one leaf. This is the
	 * granular-storage paging predicate: a single-leaf tree is persisted as one inline part (paging it
	 * would be pure overhead), while a multi-leaf tree is persisted as individual leaf pages.
	 *
	 * @return true when the root is internal (≥ 2 leaves), false when the root itself is the only leaf
	 */
	public boolean isRootInternal() {
		return getRoot() instanceof InternalBPlusTreeNode;
	}

	/**
	 * Returns the number of elements currently stored in the B+ tree.
	 *
	 * @return the size of the tree, represented as the number of elements it contains
	 */
	public int size() {
		return Objects.requireNonNull(this.size.get());
	}

	/**
	 * Returns one {@link LeafPageHandle} per leaf, in ascending key order — the page-emission view of the tree for the
	 * granular write path. The handles wrap the live leaf nodes reached from the current (transaction-aware) root, so
	 * stamping a page sequence through a handle mutates the node the merge will carry forward, and the captured values
	 * are the read-your-writes contents. Only the variants whose leaves implement {@link LeafBPlusTreeNode} (the paging
	 * variants — today the long-keyed tree behind the range index) may call this.
	 *
	 * @param <T> the leaf payload (value) type
	 * @return the ordered leaf-page handles; never empty
	 */
	@Nonnull
	public <T> List<LeafPageHandle<T>> leafPageHandles() {
		final List<BPlusTreeNode<?>> leaves = enumerateLeaves();
		final List<LeafPageHandle<T>> handles = new ArrayList<>(leaves.size());
		for (final BPlusTreeNode<?> leaf : leaves) {
			handles.add(new LeafPageHandleImpl<>((LeafBPlusTreeNode<?>) leaf));
		}
		return handles;
	}

	/**
	 * Creates a fresh, empty leaf node of the concrete tree's leaf type — used when a collapsing root must be replaced
	 * by an empty leaf. This is the single typed seam the base reaches back through, because constructing a leaf needs
	 * the value-array type the subclass owns.
	 *
	 * @return a new empty leaf node
	 */
	@Nonnull
	protected abstract BPlusTreeNode<?> newEmptyLeaf();

	/**
	 * Registers a leaf as dirtied for the current transaction's pre-commit / post-replay dirty-scope validation. A
	 * no-op when no transaction is active — warm-up bulk load and other non-transactional mutations have neither a
	 * registry nor a WAL, so the validation does not apply and pays nothing. The registered object is the leaf's write
	 * LAYER when one exists (it holds the effective in-transaction keys and survives the layer discard the trunk merge
	 * performs), otherwise the leaf itself (a split-born leaf holds its keys directly). Registered objects are key
	 * sources only, so registering a not-strictly-current object cannot cause a false positive (see
	 * {@link DirtyScopeValidator}).
	 *
	 * @param tree the owner tree the leaf belongs to
	 * @param leaf the dirtied leaf node
	 */
	static void registerDirtyLeafInScope(@Nonnull DirtyScopeValidator tree, @Nonnull BPlusTreeNode<?> leaf) {
		final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
		if (maintainer == null) {
			return;
		}
		final Object writable = Transaction.getTransactionalMemoryLayerIfExists(leaf);
		maintainer.registerDirtyScopeToken(tree, writable != null ? writable : leaf);
	}

	/**
	 * Hook invoked by {@link #consolidate(Cursor)} for each leaf whose boundary keys a rebalance (steal / merge)
	 * changed, so the concrete tree can register it in the transaction's dirty-scope validation. The default is a
	 * no-op: the out-of-scope trees ({@code TransactionalObjectBPlusTree}, {@code TransactionalIntToLongBPlusTree}) do
	 * not participate in the dirty-leaf validation. The in-scope paged trees override it to call
	 * {@link #registerDirtyLeafInScope(DirtyScopeValidator, BPlusTreeNode)}.
	 *
	 * @param leaf the rebalanced leaf node
	 */
	protected void registerConsolidatedLeaf(@Nonnull BPlusTreeNode<?> leaf) {
		// no-op by default; the in-scope paged trees override this to register the rebalanced leaf
	}

	/**
	 * Recursively removes the transactional diff layers of the passed node, its descendants and - for leaf nodes whose
	 * values are themselves {@link TransactionalLayerProducer}s - those values. Walks the current (transaction-aware)
	 * view of the tree.
	 *
	 * Shared by every concrete tree: the internal-vs-leaf split and the node-layer removal are key-type-agnostic; the
	 * only value-type-specific decision - which leaf values, if any, carry a nested layer - is delegated to
	 * {@link #transactionalLeafValues(BPlusTreeNode)}. The walk exists so that a tree which was created and discarded
	 * within the same transaction (e.g. a removed sub-index) does not leave any of its inner transactional objects
	 * ALIVE - which would otherwise be detected as stale during the commit sweep.
	 *
	 * @param node               the node whose layer (and that of its subtree) is to be removed
	 * @param transactionalLayer the maintainer that owns the diff layers
	 */
	protected void removeLayerRecursively(
		@Nonnull BPlusTreeNode<?> node,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		if (node instanceof final InternalBPlusTreeNode<?> internalNode) {
			final BPlusTreeNode<?>[] children = internalNode.getChildren();
			final int peek = internalNode.getPeek();
			for (int i = 0; i <= peek; i++) {
				removeLayerRecursively(children[i], transactionalLayer);
			}
		} else {
			// a non-internal node is, by construction, a leaf; only trees whose values are themselves transactional
			// producers expose them here - primitive / element-reference value trees return null and skip the walk
			final Object[] values = transactionalLeafValues(node);
			if (values != null) {
				final int peek = node.getPeek();
				for (int i = 0; i <= peek; i++) {
					// value producers guard their own (and their children's) layer removal internally
					if (values[i] instanceof final TransactionalLayerProducer<?, ?> producer) {
						producer.removeLayer(transactionalLayer);
					}
				}
			}
		}
		// the node's own removeLayer asserts a layer exists - only call it when one is actually open
		if (Transaction.getTransactionalMemoryLayerIfExists(node) != null) {
			node.removeLayer(transactionalLayer);
		}
	}

	/**
	 * Returns the live value array of the given leaf node when those values may themselves be
	 * {@link TransactionalLayerProducer}s whose diff layers must be removed together with a discarded tree (the
	 * generic-value trees), or `null` for trees whose leaf values are primitives / non-transactional references (the
	 * int-to-long and element trees) and therefore hold no nested layer. The returned array is the live, transaction-aware
	 * backing array - never a copy - so the cleanup walk allocates nothing.
	 *
	 * @param leaf the leaf node whose values are inspected
	 * @return the leaf's live value array, or `null` when its values can never be transactional producers
	 */
	@Nullable
	protected Object[] transactionalLeafValues(@Nonnull BPlusTreeNode<?> leaf) {
		return null;
	}

	/**
	 * Collects the tree's leaf nodes in ascending key order via an in-order, left-to-right walk of the internal spine.
	 * This is the structural foundation of the granular write/load path; it observes only the key-agnostic node SPI, so
	 * it serves every variant uniformly. Exposed to subclasses that rebuild the routing spine bottom-up from a known set
	 * of leaves (the granular load path).
	 *
	 * @return the ordered leaf nodes; never empty
	 */
	@Nonnull
	protected List<BPlusTreeNode<?>> enumerateLeaves() {
		final List<BPlusTreeNode<?>> leaves = new ArrayList<>();
		collectLeaves(getRoot(), leaves);
		return leaves;
	}

	/**
	 * Finds the leftmost leaf node in the B+ tree. The method begins its search from the root node and
	 * traverses down to the leaf node by following the first child pointer of each internal node.
	 *
	 * @return the cursor to the leftmost leaf node of the tree
	 */
	@Nonnull
	protected Cursor createLeftmostCursor() {
		final ArrayList<CursorLevel> path = new ArrayList<>(this.size() == 0 ? 1 : (int) (Math.log(this.size()) + 1));
		final BPlusTreeNode<?> theRoot = this.getRoot();
		final BPlusTreeNode<?>[] rootSiblings = new BPlusTreeNode<?>[]{theRoot};
		path.add(new CursorLevel(rootSiblings, 0, 0));
		// if the root is internal node, add the levels to the path until the leaf node is reached
		if (theRoot instanceof InternalBPlusTreeNode<?> rootInternalNode) {
			addLeftmostCursorLevels(rootInternalNode, path);
		}
		return new Cursor(path);
	}

	/**
	 * Finds the rightmost leaf node in the B+ tree. The method begins its search from the root node and
	 * traverses down to the leaf node by following the last child pointer of each internal node.
	 *
	 * @return the cursor to the rightmost leaf node of the tree
	 */
	@Nonnull
	protected Cursor createRightmostCursor() {
		final ArrayList<CursorLevel> path = new ArrayList<>(this.size() == 0 ? 1 : (int) (Math.log(this.size()) + 1));
		final BPlusTreeNode<?> theRoot = this.getRoot();
		final BPlusTreeNode<?>[] rootSiblings = new BPlusTreeNode<?>[]{theRoot};
		path.add(new CursorLevel(rootSiblings, 0, 0));
		// if the root is internal node, add the levels to the path until the leaf node is reached
		if (theRoot instanceof InternalBPlusTreeNode<?> rootInternalNode) {
			addRightmostCursorLevels(rootInternalNode, path);
		}
		return new Cursor(path);
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
	protected <N extends BPlusTreeNode<N>> void consolidate(@Nonnull Cursor cursor) {
		CursorWithLevel cursorWithLevel = cursor.toCursorWithLevel();

		while (cursorWithLevel != null) {
			final N node = cursorWithLevel.currentNode();
			// use appropriate thresholds based on node type
			final boolean isInternal =
				node instanceof InternalBPlusTreeNode;
			final int minBlock = isInternal
				? this.minInternalNodeBlockSize
				: this.minValueBlockSize;
			final int maxBlock = isInternal
				? this.internalNodeBlockSize
				: this.valueBlockSize;
			final boolean underFlowNode =
				node.keyCount() < minBlock;
			if (underFlowNode) {
				final InternalBPlusTreeNode<?> parent = cursorWithLevel.parent();
				if (parent != null) {
					final boolean nodeIsEmpty = node.size() == 0;
					final CursorWithLevel previousNodeCursor = cursorWithLevel.getCursorForPreviousNode();
					// if previous node with current node exists and shares the same parent
					// and we can steal from the left sibling
					if (previousNodeCursor != null) {
						final N previousNode = previousNodeCursor.currentNode();
						if (previousNode.keyCount() > minBlock) {
							// steal half of the surplus data from the left sibling
							node.stealFromLeft(
								Math.max(1, (previousNode.keyCount() - minBlock) / 2), previousNode);
							// update parent keys
							updateParentKeys(cursorWithLevel);
							// both the receiver and the donor leaf had their boundary keys shifted — register them
							// for the transaction's dirty-scope validation
							if (!isInternal) {
								registerConsolidatedLeaf(node);
								registerConsolidatedLeaf(previousNode);
							}
							return;
						}
					}

					final CursorWithLevel nextNodeCursor = cursorWithLevel.getCursorForNextNode();
					// if next node with current node exists and shares the same parent
					// and we can steal from the right sibling
					if (nextNodeCursor != null) {
						final N nextNode = nextNodeCursor.currentNode();
						if (nextNode.keyCount() > minBlock) {
							// steal half of the surplus data from the right sibling
							node.stealFromRight(
								Math.max(1, (nextNode.keyCount() - minBlock) / 2), nextNode);
							// update parent keys of the next node - we've stolen its first key
							updateParentKeys(nextNodeCursor);
							// update parent keys, but only if node was empty - which means first key was added
							if (isInternal || nodeIsEmpty) {
								updateParentKeys(cursorWithLevel);
							}
							// both the receiver and the donor leaf had their boundary keys shifted — register them
							// for the transaction's dirty-scope validation
							if (!isInternal) {
								registerConsolidatedLeaf(node);
								registerConsolidatedLeaf(nextNode);
							}
							return;
						}
					}

					// if previous node with current node can be merged and share the same parent
					if (previousNodeCursor != null) {
						final N previousNode = previousNodeCursor.currentNode();
						if (previousNode.keyCount() + node.keyCount() < maxBlock) {
							// merge nodes
							node.mergeWithLeft(previousNode);
							// remove the removed child from the parent
							parent.removeChildOnIndex(
								previousNodeCursor.currentNodeIndex(), previousNodeCursor.currentNodeIndex());
							// the merged-away node is detached from the tree - drop its transactional layer
							// (touched during the merge) so the commit walk does not leave it stale
							if (Transaction.getTransactionalMemoryLayerIfExists(previousNode) != null) {
								previousNode.removeLayer();
							}
							// update parent keys, previous node has been removed
							updateParentKeys(previousNodeCursor.withReplacedCurrentNode(node));
							// the surviving merged leaf absorbed the left sibling — its first key lowered; register it
							// for the transaction's dirty-scope validation (the merged-away node is gone and needs no
							// registration)
							if (!isInternal) {
								registerConsolidatedLeaf(node);
							}
							// consolidate the parent node
							cursorWithLevel = cursorWithLevel.toParentLevel();
							// continue with parent level
							continue;
						}
					}

					// if next node with current node can be merged and share the same parent
					if (nextNodeCursor != null) {
						final N nextNode = nextNodeCursor.currentNode();
						if (nextNode.keyCount() + node.keyCount() < maxBlock) {
							// merge nodes
							node.mergeWithRight(nextNode);
							// remove the removed child from the parent
							parent.removeChildOnIndex(
								nextNodeCursor.currentNodeIndex() - 1, nextNodeCursor.currentNodeIndex());
							// the merged-away node is detached from the tree - drop its transactional layer
							// (touched during the merge) so the commit walk does not leave it stale
							if (Transaction.getTransactionalMemoryLayerIfExists(nextNode) != null) {
								nextNode.removeLayer();
							}
							// update parent keys, next node has been removed
							updateParentKeys(cursorWithLevel.withReplacedCurrentNode(node));
							// the surviving merged leaf absorbed the right sibling — its last key rose; register it for
							// the transaction's dirty-scope validation (the merged-away node is gone and needs no
							// registration)
							if (!isInternal) {
								registerConsolidatedLeaf(node);
							}
							// consolidate the parent node
							cursorWithLevel = cursorWithLevel.toParentLevel();
						}
					}
				} else if (node == this.getRoot()) {
					final BPlusTreeNode<?> theRoot = this.getRoot();
					if (node.size() == 1 && node instanceof InternalBPlusTreeNode<?> internalTreeNode) {
						final BPlusTreeNode<?> firstChild = internalTreeNode.getChildren()[0];
						if (Transaction.getTransactionalMemoryLayerIfExists(theRoot) != null) {
							theRoot.removeLayer();
						}
						// replace the root with the only child
						this.root.set(firstChild);
					} else if (node.size() == 0 && node instanceof InternalBPlusTreeNode) {
						if (Transaction.getTransactionalMemoryLayerIfExists(theRoot) != null) {
							theRoot.removeLayer();
						}
						// the root is empty, create a new empty leaf node
						this.root.set(newEmptyLeaf());
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
	 * Default {@link LeafPageHandle} backed by a single live leaf node. The leaf's values and peek are captured once
	 * (transaction-aware, read-your-writes) at construction; {@link #setPageSequence} mutates only the non-transactional
	 * page sequence, so the captured view stays valid across a stamp.
	 *
	 * @param <T> the leaf payload (value) type
	 */
	private static final class LeafPageHandleImpl<T> implements LeafPageHandle<T> {
		@Nonnull private final LeafBPlusTreeNode<?> leaf;
		@Nonnull private final Object[] values;
		private final int peek;

		LeafPageHandleImpl(@Nonnull LeafBPlusTreeNode<?> leaf) {
			this.leaf = leaf;
			this.values = leaf.getValueArray();
			this.peek = leaf.getPeek();
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

		@Override
		public int size() {
			return this.peek + 1;
		}

		@SuppressWarnings("unchecked")
		@Nonnull
		@Override
		public T valueAt(int index) {
			return (T) this.values[index];
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
	 */
	protected record Cursor(
		@Nonnull List<CursorLevel> path
	) {

		/**
		 * Retrieves the leaf node of the B+ tree at the deepest level of the current path. Declared generic in the
		 * concrete leaf type so callers bind their typed leaf at the assignment site without an explicit downcast,
		 * exactly like {@link CursorWithLevel#currentNode()}; the deepest path level is always a leaf by the cursor
		 * invariant, so the unchecked cast is safe.
		 *
		 * @param <N> the concrete leaf-node type inferred at the call site
		 * @return The leaf node of the B+ tree at the location specified by the current path.
		 * Guaranteed to be non-null.
		 */
		@Nonnull
		public <N extends BPlusTreeNode<N>> N leafNode() {
			final CursorLevel deepestLevel = this.path.get(this.path.size() - 1);
			//noinspection unchecked
			return (N) deepestLevel.siblings()[deepestLevel.index()];
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
		public CursorWithLevel toCursorWithLevel() {
			return new CursorWithLevel(this.path, this.path.size() - 1);
		}
	}

	/**
	 * A cursor that also tracks the level within the path it currently points at, so the structure-maintenance
	 * algorithms can walk up to the parent and sideways to the previous / next sibling while keeping the full path
	 * consistent.
	 *
	 * @param path                     the path representing the sequence of nodes traversed to reach the current node
	 * @param level                    the current level in the tree where the cursor is positioned
	 * @param currentNodeOfGenericType the node at the current level (may be a node that replaced the original one)
	 */
	protected record CursorWithLevel(
		@Nonnull List<CursorLevel> path,
		int level,
		@Nonnull BPlusTreeNode<?> currentNodeOfGenericType
	) {

		/**
		 * Creates a cursor at the given level using the current node from the path.
		 *
		 * @param path  the path representing the sequence of nodes traversed to reach the current node
		 * @param level the current level in the tree where the cursor is positioned
		 */
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
		 * Retrieves the parent node of the current node in the B+ Tree structure, if it exists. Declared generic in the
		 * concrete internal-node type so callers bind their typed parent at the assignment site without an explicit
		 * downcast, exactly like {@link #currentNode()}; a parent is always an internal node by the tree invariant, so
		 * the unchecked cast is safe.
		 *
		 * @param <N> the concrete internal-node type inferred at the call site
		 * @return the parent node if the current level is greater than 0, otherwise {@code null}.
		 */
		@Nullable
		public <N extends InternalBPlusTreeNode<N>> N parent() {
			if (this.level > 0) {
				final CursorLevel parentLevel = this.path.get(this.level - 1);
				//noinspection unchecked
				return (N) parentLevel.siblings()[parentLevel.index()];
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
		 * otherwise {@code null}.
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
					final InternalBPlusTreeNode<?> currentNode =
						(InternalBPlusTreeNode<?>) newCursorLevel.siblings()[newCursorLevel.index()];
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
		 * otherwise {@code null}.
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
					final InternalBPlusTreeNode<?> currentNode =
						(InternalBPlusTreeNode<?>) newCursorLevel.siblings()[newCursorLevel.index()];
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
		 * while retaining the original path and level.
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
	 * A record representing the current level of a cursor within a BPlusTree structure.
	 * Stores references to sibling nodes at the current level and tracks the index
	 * of the current node and a peek index in the siblings array (last meaningful index).
	 *
	 * @param siblings An array of sibling nodes at the current level in the B+ tree structure.
	 * @param index    The index of the current node within the siblings array, must be always > 0 and <= peek.
	 * @param peek     The last meaningful index in the siblings array.
	 */
	protected record CursorLevel(
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
			return (N) this.siblings[this.index];
		}
	}

	/**
	 * Represents a node along with its associated index. This class is a record that holds an integer index
	 * and a non-nullable node.
	 *
	 * @param node  the non-null node associated with the index
	 * @param index the index associated with the value
	 */
	protected record NodeWithIndex(
		@Nonnull BPlusTreeNode<?> node,
		int index
	) {
	}

	/**
	 * Key-agnostic forward leaf-path walker shared by every concrete forward iterator of the B+ tree family. It owns the
	 * complete path-traversal state - the per-level sibling arrays, the per-level index and peek, the within-leaf
	 * {@link #currentIndex}, the {@link #hasNextElement} flag and the cached {@link #leafPeek} - together with the
	 * leaf-to-leaf advance logic ({@link #advance()} / {@link #moveToNextLeaf()}). The single typed seam is the abstract
	 * {@link #loadCurrentLeaf()}: a concrete iterator caches the typed key / value arrays of {@link #currentLeafNode()}
	 * there, so the hot per-element read stays primitive and never dispatches through the node SPI. The constructors
	 * invoke {@link #loadCurrentLeaf()} so the start leaf is resolved exactly once; the subclass therefore must not
	 * declare leaf-array field initializers that would run after {@code super(...)} and clobber the cached arrays.
	 */
	protected abstract static class AbstractForwardTreeNavigator {
		/**
		 * Array of arrays representing siblings on each level of the path.
		 */
		@Nonnull private final BPlusTreeNode<?>[][] path;
		/**
		 * The index of the current node on each level of the path.
		 */
		@Nonnull private final int[] pathIndex;
		/**
		 * The peek index of each sibling array on the path.
		 */
		@Nonnull private final int[] pathPeeks;
		/**
		 * The index of the current key within the current leaf node.
		 */
		protected int currentIndex;
		/**
		 * Flag indicating whether there are more elements to traverse.
		 */
		protected boolean hasNextElement;
		/**
		 * Last occupied index of the current leaf, refreshed by {@link #loadCurrentLeaf()} each time the navigator
		 * enters a new leaf. The typed key / value arrays themselves are cached by the concrete iterator subclass.
		 */
		protected int leafPeek;

		/**
		 * Initializes the forward navigator starting from the leftmost position of the cursor.
		 *
		 * @param cursor the cursor providing the traversal path through the B+ tree
		 */
		protected AbstractForwardTreeNavigator(@Nonnull Cursor cursor) {
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
			this.hasNextElement = this.leafPeek >= 0;
		}

		/**
		 * Initializes the forward navigator starting from the supplied insertion position (the position of the start
		 * key, or the first key greater than it). The position is computed by the concrete subclass because it depends
		 * on the typed key array; the navigator only needs its primitive {@link InsertionPosition#position()}.
		 *
		 * @param cursor            the cursor providing the traversal path through the B+ tree
		 * @param insertionPosition the position of the start key within the cursor's leaf
		 */
		protected AbstractForwardTreeNavigator(@Nonnull Cursor cursor, @Nonnull InsertionPosition insertionPosition) {
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
			// resolve the start leaf's arrays once; the per-element hot path then reads only the cached arrays
			loadCurrentLeaf();
			this.currentIndex = insertionPosition.position();
			if (this.currentIndex <= this.leafPeek) {
				// the start key lies within the current leaf - it has at least one key >= key
				this.hasNextElement = true;
			} else {
				// the start key is greater than every key in the current leaf - the matching keys, if any,
				// live in a following leaf, so we must advance the path to the next leaf instead of stopping
				// here (otherwise keys/values in the gap between two leaves would be skipped)
				this.hasNextElement = moveToNextLeaf();
			}
		}

		/**
		 * Returns true if there is another element to traverse.
		 *
		 * @return true if there is another element to traverse, false otherwise
		 */
		public boolean hasNext() {
			return this.hasNextElement;
		}

		/**
		 * Returns the leaf node the navigator currently points at, through the key-agnostic {@link BPlusTreeNode}
		 * contract. The concrete iterator subclass downcasts it to its typed leaf inside {@link #loadCurrentLeaf()}.
		 *
		 * @return the leaf node the navigator currently points at
		 */
		@Nonnull
		protected BPlusTreeNode<?> currentLeafNode() {
			return this.path[this.path.length - 1][this.pathIndex[this.pathIndex.length - 1]];
		}

		/**
		 * Caches the typed key / value arrays (and refreshes {@link #leafPeek}) of {@link #currentLeafNode()}.
		 * Implemented by the concrete iterator subclass because the array element types are value-type specific; it is
		 * called once per leaf when the navigator enters it, so the per-element hot path then reads only the cached
		 * arrays instead of dispatching through the node SPI.
		 */
		protected abstract void loadCurrentLeaf();

		/**
		 * Advances the navigator one position to the right after the current element has been consumed. The position
		 * moves to the next key in the current leaf, or - when the current leaf is exhausted - to the first key of the
		 * following leaf via {@link #moveToNextLeaf()}.
		 */
		protected void advance() {
			if (this.currentIndex < this.leafPeek) {
				// easy path, there is another key in the current leaf
				this.currentIndex++;
			} else {
				// we need to traverse up the path to find the next sibling leaf
				this.hasNextElement = moveToNextLeaf();
			}
		}

		/**
		 * Advances the navigator path to the first entry of the next leaf to the right of the current one. On success
		 * the path arrays point at the following leaf and {@link #currentIndex} is reset to its first entry; on failure
		 * the path is left untouched.
		 *
		 * @return true if a following leaf was found, false if the current leaf is the rightmost one
		 */
		private boolean moveToNextLeaf() {
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
							currentNode instanceof InternalBPlusTreeNode,
							"Internal node expected!"
						);
						this.path[i] = ((InternalBPlusTreeNode<?>) currentNode).getChildren();
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
	}

	/**
	 * Key-agnostic reverse leaf-path walker shared by every concrete reverse iterator of the B+ tree family. The mirror
	 * image of {@link AbstractForwardTreeNavigator}: it walks the leaves right-to-left ({@link #advance()} /
	 * {@link #moveToPreviousLeaf()}) and exposes the same {@link #loadCurrentLeaf()} typed seam. It does not keep the
	 * per-level peek array because the reverse step only ever checks for a previous sibling (index &gt; 0).
	 */
	protected abstract static class AbstractReverseTreeNavigator {
		/**
		 * Array of arrays representing siblings on each level of the path.
		 */
		@Nonnull private final BPlusTreeNode<?>[][] path;
		/**
		 * The index of the current node on each level of the path.
		 */
		@Nonnull private final int[] pathIndex;
		/**
		 * The index of the current key within the current leaf node.
		 */
		protected int currentIndex;
		/**
		 * Flag indicating whether there are more elements to traverse.
		 */
		protected boolean hasNextElement;
		/**
		 * Last occupied index of the current leaf, refreshed by {@link #loadCurrentLeaf()} each time the navigator
		 * enters a new leaf. The typed key / value arrays themselves are cached by the concrete iterator subclass.
		 */
		protected int leafPeek;

		/**
		 * Initializes the reverse navigator starting from the rightmost position of the cursor.
		 *
		 * @param cursor the cursor providing the traversal path through the B+ tree
		 */
		protected AbstractReverseTreeNavigator(@Nonnull Cursor cursor) {
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
			this.hasNextElement = this.currentIndex >= 0;
		}

		/**
		 * Initializes the reverse navigator starting from the supplied insertion position (the position of the start
		 * key, or the first key lesser than or equal to it). The position is computed by the concrete subclass because
		 * it depends on the typed key array; the navigator needs only its primitive
		 * {@link InsertionPosition#position()} and {@link InsertionPosition#alreadyPresent()}.
		 *
		 * @param cursor            the cursor providing the traversal path through the B+ tree
		 * @param insertionPosition the position of the start key within the cursor's leaf
		 */
		protected AbstractReverseTreeNavigator(@Nonnull Cursor cursor, @Nonnull InsertionPosition insertionPosition) {
			final List<CursorLevel> cursorPath = cursor.path();
			this.path = new BPlusTreeNode[cursorPath.size()][];
			this.pathIndex = new int[this.path.length];
			for (int i = 0; i < cursorPath.size(); i++) {
				final CursorLevel cursorLevel = cursorPath.get(i);
				this.path[i] = cursorLevel.siblings();
				this.pathIndex[i] = cursorLevel.index();
			}
			// resolve the start leaf's arrays once; the per-element hot path then reads only the cached arrays
			loadCurrentLeaf();
			if (insertionPosition.alreadyPresent()) {
				this.currentIndex = insertionPosition.position();
				this.hasNextElement = true;
			} else {
				this.currentIndex = insertionPosition.position() - 1;
				this.hasNextElement = true;
				if (this.currentIndex < 0) {
					moveToPreviousLeaf();
				}
			}
		}

		/**
		 * Returns true if there is another element to traverse.
		 *
		 * @return true if there is another element to traverse, false otherwise
		 */
		public boolean hasNext() {
			return this.hasNextElement;
		}

		/**
		 * Returns the leaf node the navigator currently points at, through the key-agnostic {@link BPlusTreeNode}
		 * contract. The concrete iterator subclass downcasts it to its typed leaf inside {@link #loadCurrentLeaf()}.
		 *
		 * @return the leaf node the navigator currently points at
		 */
		@Nonnull
		protected BPlusTreeNode<?> currentLeafNode() {
			return this.path[this.path.length - 1][this.pathIndex[this.pathIndex.length - 1]];
		}

		/**
		 * Caches the typed key / value arrays (and refreshes {@link #leafPeek}) of {@link #currentLeafNode()}.
		 * Implemented by the concrete iterator subclass because the array element types are value-type specific; it is
		 * called once per leaf when the navigator enters it, so the per-element hot path then reads only the cached
		 * arrays instead of dispatching through the node SPI.
		 */
		protected abstract void loadCurrentLeaf();

		/**
		 * Advances the navigator one position to the left after the current element has been consumed. The position
		 * moves to the previous key in the current leaf, or - when the current leaf is exhausted - to the last key of
		 * the preceding leaf via {@link #moveToPreviousLeaf()}.
		 */
		protected void advance() {
			if (this.currentIndex > 0) {
				// easy path, there is another key in the current leaf
				this.currentIndex--;
			} else {
				// we need to traverse up the path to find the previous sibling leaf
				moveToPreviousLeaf();
			}
		}

		/**
		 * Iterates through the path of B+ tree nodes in reverse order to position the navigator on the last entry of
		 * the leaf preceding the current one. It walks up the parents until it finds one with a previous sibling, then
		 * descends to that sibling's rightmost leaf. Sets {@link #hasNextElement} to true and {@link #currentIndex} to
		 * the new leaf's last entry on success, or sets {@link #hasNextElement} to false when the current leaf is the
		 * leftmost one.
		 */
		private void moveToPreviousLeaf() {
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
						Assert.isPremiseValid(
							currentNode instanceof InternalBPlusTreeNode,
							"Internal node expected!"
						);
						this.path[i] = ((InternalBPlusTreeNode<?>) currentNode).getChildren();
						this.pathIndex[i] = currentNode.getPeek();
						currentNode = this.path[i][this.pathIndex[i]];
					}
					this.hasNextElement = true;
					// refresh the cached key/value arrays + peek for the newly entered leaf
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
			this.hasNextElement = found;
		}
	}

	/**
	 * Raised when a transactional B+ tree is found to violate its cross-leaf ordering invariants — a leaf whose key range
	 * overlaps an adjacent leaf (the "stale leaf-page twin" corruption class) or an internal separator that no longer
	 * matches the live leaf it fronts.
	 *
	 * It is a dedicated {@link DataStructureCorruptedException} subtype so the transaction commit path can recognise a
	 * tree-corruption rejection specifically — without catching unrelated internal errors. The pre-commit (pre-WAL)
	 * validation rejects the commit with a still-clean write-ahead log; the post-replay (merge-time) validation catches
	 * this exception after the transaction is already durable and re-wraps it with the poison-pill remediation caveat.
	 * The message carried here is deliberately view-neutral (it fires on the isolated merge path too, where no WAL
	 * exists), so the WAL / poison-pill wording lives only in the trunk wrapper.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
	 */
	public static class BPlusTreeCorruptedException extends DataStructureCorruptedException {
		@Serial private static final long serialVersionUID = 6748142835067359214L;

		public BPlusTreeCorruptedException(@Nonnull String publicMessage) {
			super(publicMessage);
		}

	}

	/**
	 * Builds the operator-facing diagnostic for an overlapping-leaf-page corruption detected while reassembling a paged
	 * B+ tree from disk (the "stale leaf-page twin" class: a leaf whose key range overlaps the leaf listed after it).
	 *
	 * This is called ONLY once an overlap has already been detected — on the failure path — so it gathers the full
	 * context a healthy cold load never pays for: the whole ordered leaf-page list (where in the layout the overlap
	 * sits), both offending page sequences, and each leaf's key range and key count. The containment relationship is
	 * reported as a raw fact (`successorRangeWithinPredecessorRange`) computed by the caller with its own comparator,
	 * NOT as an interpreted verdict — the paged layout is new and the next corruption may not be the known twin, so the
	 * shape is left for the reader to classify. Page/leaf versions are deliberately absent: they are not threaded down
	 * to the reassembly layer, and the message says so rather than leaving their absence to look accidental.
	 *
	 * The shared shape is used identically by every tree that reassembles paged leaves; each passes its own keys as
	 * plain objects so this method stays free of the trees' key-type generics.
	 *
	 * @param structureDescription         a full identification of the index for diagnostics
	 * @param orderedPageSequences         the root's ordered leaf-page sequence list (the persisted layout)
	 * @param predecessorPageSequence      the page sequence of the earlier (overlapping) leaf
	 * @param predecessorFirstKey          the earlier leaf's first key
	 * @param predecessorLastKey           the earlier leaf's last key (the one that fails to sort before the successor)
	 * @param predecessorKeyCount          the number of keys in the earlier leaf
	 * @param successorPageSequence        the page sequence of the next leaf in the list
	 * @param successorFirstKey            the next leaf's first key (the one the predecessor's last key overran)
	 * @param successorLastKey             the next leaf's last key
	 * @param successorKeyCount            the number of keys in the next leaf
	 * @param successorWithinPredecessor   whether the successor's key range lies entirely within the predecessor's
	 * @return the full diagnostic message; never null
	 */
	@Nonnull
	static String overlappingLeafPagesDiagnostic(
		@Nonnull String structureDescription,
		@Nonnull int[] orderedPageSequences,
		int predecessorPageSequence, @Nonnull Object predecessorFirstKey, @Nonnull Object predecessorLastKey,
		int predecessorKeyCount,
		int successorPageSequence, @Nonnull Object successorFirstKey, @Nonnull Object successorLastKey,
		int successorKeyCount,
		boolean successorWithinPredecessor
	) {
		return "Corrupted persisted " + structureDescription + ": leaf-page sequence " + predecessorPageSequence +
			" overlaps its successor leaf-page sequence " + successorPageSequence + " — its last key (" +
			predecessorLastKey + ") does not sort before the first key (" + successorFirstKey + ") of the next leaf " +
			"page. Overlap context: orderedLeafPageList=" + Arrays.toString(orderedPageSequences) +
			", predecessorKeyRange=[" + predecessorFirstKey + " .. " + predecessorLastKey + "] (" + predecessorKeyCount +
			" keys), successorKeyRange=[" + successorFirstKey + " .. " + successorLastKey + "] (" + successorKeyCount +
			" keys), successorRangeWithinPredecessorRange=" + successorWithinPredecessor + "; page/leaf versions are " +
			"not available at the reassembly layer. This is a stale leaf-page twin or other index corruption. Restore " +
			"the catalog from a backup, or fully rebuild / reindex the affected catalog.";
	}

}
