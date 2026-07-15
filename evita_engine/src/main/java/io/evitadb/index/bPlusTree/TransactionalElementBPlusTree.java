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
import io.evitadb.core.transaction.memory.DirtyScopeValidator;
import io.evitadb.core.transaction.memory.Snapshotable;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
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
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator.OfInt;
import java.util.Set;
import java.util.function.ToIntFunction;

import static io.evitadb.utils.ArrayUtils.insertRecordIntoSameArrayOnIndex;
import static io.evitadb.utils.ArrayUtils.removeRecordFromSameArrayOnIndex;

/**
 * An **element-keyed** B+ tree: a balanced, transactional, granularly-persistable map of an integer key to an opaque
 * element, where the key is **derived from the element** (via a {@link ToIntFunction}) rather than stored alongside it.
 * The leaves therefore hold a single ascending-by-derived-key {@code E[]} with **no parallel key array**, so each record
 * costs only its own reference — the memory win that makes this the backing of the price super index's `priceRecords`
 * collection. A decision spike measured ~8.22 B/record structural overhead here versus ~24.41 for
 * a {@link TransactionalLongBPlusTree} keyed on the same id (whose duplicated `long` key is the dead weight), with equal
 * or better latency on every operation.
 *
 * Structurally it is the int-keyed sibling of {@link TransactionalIntToLongBPlusTree} with the leaf's key column elided:
 * internal (routing) nodes still materialize {@code int[]} separators for O(log n) descent, but a leaf re-derives a key
 * on demand via {@code keyExtractor.applyAsInt(value)} during its (zero-boxing) binary searches. All the structure
 * maintenance — descent, split, borrow, merge, root collapse — and the leaf-to-leaf iteration are inherited from
 * {@link AbstractTransactionalBPlusTree} through the key-agnostic node SPI; only the typed seams (the leaf value column,
 * the {@code int}-separator internal node, the descent cursor and the spine reassembly) live here.
 *
 * The element {@code E} is treated as an immutable, **non-transactional** value carried by reference (exactly as the
 * primitive `long` value of the int-to-long tree): it never gets its own transactional diff layer, so the commit-merge
 * neither wraps nor deep-copies it. The tree's leaves implement {@link LeafBPlusTreeNode} and so inherit the base's
 * granular per-leaf page-emission framework ({@link #leafPageHandles()}); the inverse load path
 * ({@link #assembleFromSingleLeafTrees}) rebuilds the routing spine bottom-up from persisted leaf pages.
 *
 * @param <E> the element (leaf value) type; immutable and non-transactional, ordered by its derived {@code int} key
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@NotThreadSafe
public class TransactionalElementBPlusTree<E> extends AbstractIntKeyedBPlusTree implements
	TransactionalLayerProducer<Void, TransactionalElementBPlusTree<E>>,
	DirtyScopeValidator,
	Serializable {
	@Serial private static final long serialVersionUID = -5872551790204880972L;
	private static final int DEFAULT_VALUE_BLOCK_SIZE = 64;
	private static final int DEFAULT_MIN_VALUE_BLOCK_SIZE = DEFAULT_VALUE_BLOCK_SIZE / 2 - 1;
	private static final int DEFAULT_INTERNAL_NODE_BLOCK_SIZE = DEFAULT_VALUE_BLOCK_SIZE / 2 - 1;
	private static final int DEFAULT_MIN_INTERNAL_NODE_BLOCK_SIZE = (int) (Math.ceil(
		(float) DEFAULT_INTERNAL_NODE_BLOCK_SIZE / 2.0) - 1);

	/**
	 * The class of the elements stored in this tree — needed to allocate typed {@code E[]} leaf arrays (a fresh empty
	 * leaf and the {@link #toArray()} projection) and to propagate the value representation into trees assembled from
	 * persisted leaves.
	 */
	@Nonnull private final Class<E> elementType;
	/**
	 * The pure, side-effect-free function deriving the ordering / identity key from an element (e.g.
	 * {@code PriceRecordContract::internalPriceId}). It must be stable for the lifetime of an element in the tree —
	 * a key that changes after insertion would corrupt the ordering invariant.
	 */
	@Nonnull private final ToIntFunction<E> keyExtractor;

	/**
	 * Returns the left boundary key of an arbitrary node reached through the key-agnostic {@link BPlusTreeNode} SPI
	 * (e.g. an element of an internal node's children array). The primitive-key accessor lives on the per-tree
	 * {@link IntBoundaryKeyedNode} marker so it stays out of the shared SPI (which must never expose a typed key); every
	 * node in this tree implements it, so the cast is always safe.
	 *
	 * @param node the node whose left boundary key is requested
	 * @return the left boundary (smallest) key of the node
	 */
	private static int leftBoundaryKeyOf(@Nonnull BPlusTreeNode<?> node) {
		return ((IntBoundaryKeyedNode) node).getLeftBoundaryKey();
	}

	/**
	 * Constructor to initialize the B+ tree with default block sizes.
	 *
	 * @param elementType  the class of the elements stored in this tree
	 * @param keyExtractor the function deriving the ordering / identity key from an element
	 */
	public TransactionalElementBPlusTree(
		@Nonnull Class<E> elementType,
		@Nonnull ToIntFunction<E> keyExtractor
	) {
		this(
			DEFAULT_VALUE_BLOCK_SIZE,
			DEFAULT_MIN_VALUE_BLOCK_SIZE,
			DEFAULT_INTERNAL_NODE_BLOCK_SIZE,
			DEFAULT_MIN_INTERNAL_NODE_BLOCK_SIZE,
			elementType,
			keyExtractor
		);
	}

	/**
	 * Constructor to initialize the B+ tree.
	 *
	 * @param valueBlockSize maximum number of values in a leaf node
	 * @param elementType    the class of the elements stored in this tree
	 * @param keyExtractor   the function deriving the ordering / identity key from an element
	 */
	public TransactionalElementBPlusTree(
		int valueBlockSize,
		@Nonnull Class<E> elementType,
		@Nonnull ToIntFunction<E> keyExtractor
	) {
		this(
			valueBlockSize, valueBlockSize / 2,
			valueBlockSize, valueBlockSize / 2,
			elementType,
			keyExtractor
		);
	}

	/**
	 * Constructor to initialize the B+ tree.
	 *
	 * @param valueBlockSize           maximum number of values in a leaf node
	 * @param minValueBlockSize        minimum number of values in a leaf node (controls branching factor for leaves)
	 * @param internalNodeBlockSize    maximum number of keys in an internal node
	 * @param minInternalNodeBlockSize minimum number of keys in an internal node (controls branching factor)
	 * @param elementType              the class of the elements stored in this tree
	 * @param keyExtractor             the function deriving the ordering / identity key from an element
	 */
	public TransactionalElementBPlusTree(
		int valueBlockSize,
		int minValueBlockSize,
		int internalNodeBlockSize,
		int minInternalNodeBlockSize,
		@Nonnull Class<E> elementType,
		@Nonnull ToIntFunction<E> keyExtractor
	) {
		this(
			valueBlockSize,
			minValueBlockSize,
			internalNodeBlockSize,
			minInternalNodeBlockSize,
			elementType,
			keyExtractor,
			new BPlusLeafTreeNode<>(valueBlockSize, elementType, keyExtractor, true),
			0
		);
	}

	private TransactionalElementBPlusTree(
		int valueBlockSize,
		int minValueBlockSize,
		int internalNodeBlockSize,
		int minInternalNodeBlockSize,
		@Nonnull Class<E> elementType,
		@Nonnull ToIntFunction<E> keyExtractor,
		@Nonnull BPlusTreeNode<?> root,
		int size
	) {
		super(valueBlockSize, minValueBlockSize, internalNodeBlockSize, minInternalNodeBlockSize, root, size);
		this.elementType = elementType;
		this.keyExtractor = keyExtractor;
	}

	@Nonnull
	@Override
	protected BPlusTreeNode<?> newEmptyLeaf() {
		return new BPlusLeafTreeNode<>(this.valueBlockSize, this.elementType, this.keyExtractor, true);
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
		// the element-keyed price tree is bulk-rebuilt by re-inserting records during the commit-merge
		// (newPriceRecordTree invoked from attachToCatalog), where the transaction is already finalized; a split
		// offspring that joined the diff layer there would fail to open a fresh layer, so it must mutate in place.
		// Outside any transaction the flag is true (the node is its own committed state); inside a transaction it is
		// false so split offspring mutate in place, exactly as before the per-entity savepoint work.
		return !Transaction.isTransactionAvailable();
	}

	/**
	 * Re-assembles a B+ tree from a pre-built, ascending-ordered sequence of leaf nodes, deriving the internal routing
	 * spine bottom-up. This is the inverse of {@link #enumerateLeaves()} and the foundation of the granular load path:
	 * leaf pages are read straight from disk and the spine is reconstructed here in a single pass rather than rebuilt by
	 * replaying per-record inserts.
	 *
	 * Separators are the left boundary keys of the children, honoring the tree's separator-from-first-key invariant, so
	 * no separators need be persisted. The assembled tree reuses this tree's block-size configuration, element type and
	 * key extractor.
	 *
	 * WARNING: the assembled tree REUSES (aliases) the supplied leaf node instances — it does not copy them. Intended for
	 * the load path (leaves freshly built from disk pages, owned by no other tree) and read-only round-trips. Do NOT keep
	 * mutating the source leaves after handing them here unless the source is being discarded.
	 *
	 * @param orderedLeaves the leaves in ascending key order; must be non-empty
	 * @return a new tree whose values are exactly those held by the supplied leaves
	 */
	@Nonnull
	public TransactionalElementBPlusTree<E> assembleFromLeaves(@Nonnull List<BPlusLeafTreeNode<E>> orderedLeaves) {
		Assert.isPremiseValid(!orderedLeaves.isEmpty(), "At least one leaf node is required to assemble a tree.");
		int totalValues = 0;
		for (final BPlusLeafTreeNode<E> orderedLeaf : orderedLeaves) {
			totalValues += orderedLeaf.size();
		}
		final BPlusTreeNode<?> assembledRoot = buildSpine(new ArrayList<>(orderedLeaves));
		return new TransactionalElementBPlusTree<>(
			this.valueBlockSize, this.minValueBlockSize,
			this.internalNodeBlockSize, this.minInternalNodeBlockSize,
			this.elementType,
			this.keyExtractor,
			assembledRoot,
			totalValues
		);
	}

	/**
	 * Re-assembles a B+ tree from a sequence of single-leaf source trees — one per persisted leaf page — preserving the
	 * original leaf boundaries exactly and stamping each leaf with its persisted page sequence. This is the
	 * boundary-stable load path for the granular layout: a caller that owns the value representation builds one
	 * single-leaf tree per persisted page via the public {@link #insert} surface, then hands them here in ascending key
	 * order together with their page sequences. The resulting tree's leaf *i* is byte-identical to persisted page *i*, so
	 * the change-detection baseline restored alongside it makes the first post-restart commit a true no-op for unchanged
	 * leaves (no full re-pagination).
	 *
	 * Each source tree MUST consist of a single leaf (a page never exceeds a leaf's capacity); the leaf node is aliased
	 * into the assembled tree exactly as in {@link #assembleFromLeaves} — do not keep mutating the source trees
	 * afterwards.
	 *
	 * The reassembled leaves are validated for strict cross-leaf key order BEFORE the spine is built: the last key of a
	 * key-bearing leaf must sort strictly before the first key of the next key-bearing leaf. A violation means the
	 * persisted page list carries a stale leaf-page twin (a frozen snapshot of a leaf persisted next to the page that
	 * superseded it) or other index corruption; it is not silently repaired but reported via
	 * {@link #assertCrossLeafBoundaries} — see the defensive-design rationale there.
	 *
	 * @param orderedSingleLeafTrees the per-page single-leaf trees in ascending key order
	 * @param pageSequences          the persisted page sequence for each tree, positionally aligned; same length
	 * @param structureDescription   a full identification of the index for diagnostics, reading like a noun phrase after
	 *                               `persisted ` (e.g. `price index for …`)
	 * @return a new tree whose leaves are exactly those of the supplied trees, each stamped with its page sequence
	 * @throws GenericEvitaInternalError when the persisted leaf pages violate strict cross-leaf key order
	 */
	@Nonnull
	public TransactionalElementBPlusTree<E> assembleFromSingleLeafTrees(
		@Nonnull List<TransactionalElementBPlusTree<E>> orderedSingleLeafTrees,
		@Nonnull int[] pageSequences,
		@Nonnull String structureDescription
	) {
		Assert.isPremiseValid(
			orderedSingleLeafTrees.size() == pageSequences.length,
			"The number of single-leaf trees must match the number of page sequences."
		);
		final List<BPlusLeafTreeNode<E>> leaves = new ArrayList<>(pageSequences.length);
		for (int i = 0; i < pageSequences.length; i++) {
			final BPlusTreeNode<?> root = orderedSingleLeafTrees.get(i).getRoot();
			Assert.isPremiseValid(
				root instanceof BPlusLeafTreeNode,
				"Each persisted leaf page must rebuild to exactly one leaf."
			);
			//noinspection unchecked
			final BPlusLeafTreeNode<E> leaf = (BPlusLeafTreeNode<E>) root;
			leaf.setPageSequence(pageSequences[i]);
			leaves.add(leaf);
		}
		// validate cross-leaf key order BEFORE assembly, so the corruption diagnostic fires ahead of any left-boundary
		// separator invariant the spine builder would otherwise trip on with a less actionable message
		assertCrossLeafBoundaries(leaves, structureDescription);
		return assembleFromLeaves(leaves);
	}

	/**
	 * Validates that the supplied leaves are in strict cross-leaf key order: the last key of each key-bearing leaf must
	 * sort strictly before the first key of the next key-bearing leaf (the element order derived via the tree's key
	 * extractor). Empty leaves carry no key and impose no boundary constraint, so they are skipped when locating the
	 * previous key-bearing leaf.
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
	 * @param structureDescription a full identification of the index for diagnostics (see
	 *                             {@link #assembleFromSingleLeafTrees})
	 * @throws GenericEvitaInternalError when a leaf's last key does not sort strictly before the next leaf's first key
	 */
	private void assertCrossLeafBoundaries(
		@Nonnull List<BPlusLeafTreeNode<E>> leaves,
		@Nonnull String structureDescription
	) {
		BPlusLeafTreeNode<E> previousKeyBearing = null;
		for (final BPlusLeafTreeNode<E> leaf : leaves) {
			final int peek = leaf.getPeek();
			if (peek < 0) {
				// empty leaf carries no key and cannot violate cross-leaf ordering
				continue;
			}
			final E[] values = leaf.getValues();
			// intra-leaf order: a serializer bug, truncated write or bit rot can leave a leaf whose interior keys
			// are out of order, while the cross-leaf walk alone would pass it — binary search inside such a leaf
			// then silently returns wrong answers. Assert each key sorts strictly after its predecessor within the
			// leaf (one comparison per key, once per load); keys are re-derived on demand via the key extractor.
			int previousKey = this.keyExtractor.applyAsInt(values[0]);
			for (int i = 1; i <= peek; i++) {
				final int currentKey = this.keyExtractor.applyAsInt(values[i]);
				if (previousKey >= currentKey) {
					throw new GenericEvitaInternalError(
						"Corrupted persisted " + structureDescription + ": leaf-page sequence " +
							leaf.getPageSequence() + " has out-of-order keys — the key at position " + (i - 1) + " (" +
							previousKey + ") does not sort before the key at position " + i + " (" + currentKey + "). This " +
							"is index corruption. Restore the catalog from a backup, or fully rebuild / reindex the " +
							"affected catalog."
					);
				}
				previousKey = currentKey;
			}
			if (previousKeyBearing != null) {
				final int previousLastKey = this.keyExtractor.applyAsInt(
					previousKeyBearing.getValues()[previousKeyBearing.getPeek()]
				);
				final int currentFirstKey = leaf.getLeftBoundaryKey();
				if (previousLastKey >= currentFirstKey) {
					throw new GenericEvitaInternalError(
						"Corrupted persisted " + structureDescription + ": leaf-page sequence " +
							previousKeyBearing.getPageSequence() + " overlaps its successor leaf-page sequence " +
							leaf.getPageSequence() + " — its last key (" + previousLastKey + ") does not sort before the " +
							"first key (" + currentFirstKey + ") of the next leaf page. This is a stale leaf-page twin or " +
							"other index corruption. Restore the catalog from a backup, or fully rebuild / reindex the " +
							"affected catalog."
					);
				}
			}
			previousKeyBearing = leaf;
		}
	}

	/**
	 * Tail-boundary assert. On any leaf mutation that RAISES the leaf's last key (a tail insert, including
	 * the insert into an empty leaf), verifies the new last key still sorts strictly below the leaf's upper fence.
	 * The fence is the separator at the nearest ancestor whose descent was not into the rightmost child — which,
	 * by the separator-from-first-key invariant, equals the first key of the successor leaf, even when that
	 * successor lives under a different parent, grandparent, etc. A rightmost descent at every level means the
	 * leaf is the tree's last leaf and has no successor, so nothing is checked.
	 *
	 * A violation means a key was routed into a leaf whose successor should hold it (a search-path corruption); it
	 * would overlap the successor's page on flush, so it fails fast here. Zero allocation and no cross-parent leaf
	 * navigation: the fence is pure index arithmetic over the cursor arrays. Sequential bulk append descends into
	 * the rightmost child at every level, so the walk finds no fence and returns after a few comparisons.
	 *
	 * @param cursor     the descent path to the mutated leaf
	 * @param newLastKey the leaf's new last key after the mutation
	 * @throws GenericEvitaInternalError when the new last key does not sort strictly before the successor fence
	 */
	void assertTailBoundary(@Nonnull Cursor cursor, int newLastKey) {
		final List<CursorLevel> path = cursor.path();
		for (int level = path.size() - 1; level >= 1; level--) {
			final CursorLevel cursorLevel = path.get(level);
			final int childIndex = cursorLevel.index();
			if (childIndex < cursorLevel.peek()) {
				// the ancestor whose children are this level's siblings holds the fence separator at childIndex
				final CursorLevel ancestorLevel = path.get(level - 1);
				final BPlusInternalTreeNode ancestor =
					(BPlusInternalTreeNode) ancestorLevel.siblings()[ancestorLevel.index()];
				final int fence = ancestor.getKeys()[childIndex];
				if (newLastKey >= fence) {
					throw boundaryMutationError("tail", newLastKey, "before the successor leaf boundary", fence);
				}
				return;
			}
		}
	}

	/**
	 * Head-boundary assert. On any leaf mutation that LOWERS the leaf's first key (a head insert, including
	 * the insert into an empty leaf), verifies the new first key still sorts strictly above the predecessor leaf's
	 * last key. In a sound tree a head insert can only land on the tree's leftmost leaf (the separator-from-first-key
	 * invariant routes every key at-or-above the leaf's own first key), so this check passes trivially; it fires only
	 * on a mis-routed insert that undercuts a real predecessor.
	 *
	 * The predecessor's last key is the only meaningful operand: checking the parent separator would be circular,
	 * as the maintained invariant makes it equal the leaf's own first key.
	 *
	 * @param cursor      the descent path to the mutated leaf
	 * @param newFirstKey the leaf's new first key after the mutation
	 * @throws GenericEvitaInternalError when the new first key does not sort strictly after the predecessor boundary
	 */
	void assertHeadBoundary(@Nonnull Cursor cursor, int newFirstKey) {
		final BPlusLeafTreeNode<E> predecessor = predecessorLeaf(cursor);
		if (predecessor == null) {
			// leftmost leaf — no predecessor to violate
			return;
		}
		final int predecessorPeek = predecessor.getPeek();
		if (predecessorPeek < 0) {
			// an empty predecessor carries no key (mirrors the load-time empty-leaf skip)
			return;
		}
		final int predecessorLastKey = this.keyExtractor.applyAsInt(predecessor.getValues()[predecessorPeek]);
		if (predecessorLastKey >= newFirstKey) {
			throw boundaryMutationError(
				"head", newFirstKey, "after the predecessor leaf boundary", predecessorLastKey);
		}
	}

	/**
	 * Resolves the predecessor leaf of the cursor's leaf. Common case ({@code ci > 0}): the same-parent left sibling
	 * is the predecessor, read in O(1) from the cursor's already-materialized sibling array. Rare case
	 * ({@code ci == 0}): walk up to the nearest ancestor whose descent was into a non-leftmost child (the clamp
	 * ancestor), then follow that ancestor's left-neighbour subtree down its right spine to the predecessor leaf.
	 * That rare branch is O(height) and, if transactional-layer child resolution allocates, allocation is accepted —
	 * it is never taken by sequential append (append never lowers a first key) and only by a random workload that
	 * undercuts a leaf minimum at a parent edge.
	 *
	 * @param cursor the descent path to the leaf whose predecessor is sought
	 * @return the predecessor leaf, or {@code null} when the cursor's leaf is the tree's leftmost leaf
	 */
	@Nullable
	private BPlusLeafTreeNode<E> predecessorLeaf(@Nonnull Cursor cursor) {
		final List<CursorLevel> path = cursor.path();
		final CursorLevel leafLevel = path.get(path.size() - 1);
		final int leafIndex = leafLevel.index();
		if (leafIndex > 0) {
			//noinspection unchecked
			return (BPlusLeafTreeNode<E>) leafLevel.siblings()[leafIndex - 1];
		}
		for (int level = path.size() - 1; level >= 1; level--) {
			final CursorLevel cursorLevel = path.get(level);
			final int childIndex = cursorLevel.index();
			if (childIndex > 0) {
				BPlusTreeNode<?> node = cursorLevel.siblings()[childIndex - 1];
				while (node instanceof BPlusInternalTreeNode internalNode) {
					node = internalNode.getChildren()[internalNode.getPeek()];
				}
				//noinspection unchecked
				return (BPlusLeafTreeNode<E>) node;
			}
		}
		return null;
	}

	/**
	 * Builds the shared boundary-mutation corruption error with the offending key, the neighbour boundary it
	 * collides with and a remediation hint.
	 *
	 * @param side        {@code "tail"} or {@code "head"} — which boundary the mutation changed
	 * @param boundaryKey the leaf's new boundary key that violates cross-leaf order
	 * @param relation    the ordering relation that was expected (e.g. `before the successor leaf boundary`)
	 * @param neighborKey the adjacent leaf boundary that {@code boundaryKey} failed to sort against
	 * @return the corruption error to throw
	 */
	@Nonnull
	private BPlusTreeCorruptedException boundaryMutationError(
		@Nonnull String side, int boundaryKey, @Nonnull String relation, int neighborKey) {
		return new BPlusTreeCorruptedException(
			"Corrupted in-memory B+ tree: a leaf's " + side + " boundary key " + boundaryKey + " does not sort " +
				relation + " (" + neighborKey + "). This indicates cross-leaf key overlap (a mis-routed insertion, a " +
				"reverted transactional layer, or a merge defect) that would overlap an adjacent leaf page on flush. " +
				"Restore the catalog from a backup, or fully rebuild / reindex the affected catalog."
		);
	}

	/**
	 * Registers a rebalanced leaf as a dirty-scope token for this transaction. Invoked by the base
	 * {@link #consolidate(Cursor)} for each leaf whose boundary keys a steal / merge shifted.
	 *
	 * @param leaf the rebalanced leaf node
	 */
	@Override
	protected void registerConsolidatedLeaf(@Nonnull BPlusTreeNode<?> leaf) {
		registerDirtyLeafInScope(this, leaf);
	}

	/**
	 * Dirty-scope validation for this tree. For each registered key source (a writable leaf
	 * this transaction dirtied) it reads the current first key, descends from this tree's root to the leaf that key
	 * routes to, and re-derives both cross-leaf half-invariants on the leaf the descent actually landed on — reusing
	 * the op-time machinery with the leaf's ACTUAL boundary keys ({@link #assertTailBoundary} against the successor
	 * fence, {@link #assertHeadBoundary} against the predecessor's last key). This tree keeps no parallel key array,
	 * so the boundary keys are re-derived on demand: the first key via {@link BPlusLeafTreeNode#getLeftBoundaryKey()},
	 * the last via the key extractor over the peek slot. Empty key sources and descents that land on an empty leaf are
	 * skipped (nothing to relocate by / assert). Called on the live baseline tree for the pre-commit (pre-WAL) pass,
	 * where the descent resolves diff layers, and on a freshly merged tree for the post-replay (merge-time) pass with
	 * plain reads; both use read-path accessors only.
	 *
	 * @param registeredLeafKeySources the writable leaf objects registered for this tree; used only as key sources
	 * @throws BPlusTreeCorruptedException when a relocated leaf overlaps an adjacent leaf
	 */
	@Override
	public void validateDirtyScope(@Nonnull Collection<Object> registeredLeafKeySources) {
		for (final Object keySource : registeredLeafKeySources) {
			//noinspection unchecked
			final BPlusLeafTreeNode<E> registered = (BPlusLeafTreeNode<E>) keySource;
			if (registered.getPeek() < 0) {
				// empty key source — nothing to relocate by (key-source-only rule)
				continue;
			}
			final int probeKey = registered.getLeftBoundaryKey();
			final Cursor cursor = createCursor(probeKey);
			final BPlusLeafTreeNode<E> leaf = cursor.leafNode();
			final int peek = leaf.getPeek();
			if (peek < 0) {
				// the descent landed on an empty leaf — validating it is sound and vacuous
				continue;
			}
			final int firstKey = leaf.getLeftBoundaryKey();
			final int lastKey = this.keyExtractor.applyAsInt(leaf.getValues()[peek]);
			assertTailBoundary(cursor, lastKey);
			assertHeadBoundary(cursor, firstKey);
		}
	}

	/**
	 * Separator-order belt. After a parent separator at {@code slot} is rewritten (head-key propagation),
	 * asserts strict local order against its existing neighbours. This catches stale/aliased internal-node state —
	 * the historical stale-leaf-page twin bugs were object aliasing, whose first symptom is a separator that no
	 * longer matches the live leaf it fronts. It is a belt, not the head-side check: it cannot detect a mis-routed
	 * head insert that keeps the separators individually ordered (that is what {@link #assertHeadBoundary} covers).
	 *
	 * @param keys the internal node's live separator array
	 * @param peek the internal node's peek (separator count is {@code peek}, valid indices {@code 0..peek-1})
	 * @param slot the just-rewritten separator index
	 * @throws GenericEvitaInternalError when the rewritten separator breaks strict local order
	 */
	static void assertSeparatorOrder(@Nonnull int[] keys, int peek, int slot) {
		if (slot > 0 && keys[slot - 1] >= keys[slot]) {
			throw separatorOrderError(keys[slot - 1], keys[slot]);
		}
		if (slot < peek - 1 && keys[slot] >= keys[slot + 1]) {
			throw separatorOrderError(keys[slot], keys[slot + 1]);
		}
	}

	/**
	 * Builds the separator-order corruption error.
	 *
	 * @param leftKey  the separator that should sort strictly before {@code rightKey}
	 * @param rightKey the separator that {@code leftKey} collides with
	 * @return the corruption error to throw
	 */
	@Nonnull
	private static GenericEvitaInternalError separatorOrderError(int leftKey, int rightKey) {
		return new GenericEvitaInternalError(
			"Corrupted in-memory B+ tree: internal separator keys are out of order (" + leftKey + " does not sort " +
				"before " + rightKey + "). This indicates stale/aliased internal-node state. Restore the catalog " +
				"from a backup, or fully rebuild / reindex the affected catalog."
		);
	}

	/**
	 * Runs the op-time boundary-mutation asserts for a freshly inserted key. A tail insert raises the leaf's last
	 * key, a head insert lowers its first key, and the insert into an empty leaf (the 0→1 transition) does both;
	 * an interior insert cannot violate cross-leaf order in a sound tree and is not checked. Called on the leaf
	 * mutation path shared by warm-up bulk load, transactional ops and trunk replay. The leaf's boundary keys are
	 * re-derived on demand via the key extractor (this tree keeps no parallel key array).
	 *
	 * @param cursor the descent path to the mutated leaf
	 * @param key    the key just inserted
	 */
	void assertInsertBoundaries(@Nonnull Cursor cursor, int key) {
		final BPlusLeafTreeNode<E> leaf = cursor.leafNode();
		final int firstKey = leaf.getLeftBoundaryKey();
		final int lastKey = this.keyExtractor.applyAsInt(leaf.getValues()[leaf.getPeek()]);
		if (key == lastKey) {
			assertTailBoundary(cursor, key);
		}
		if (key == firstKey) {
			assertHeadBoundary(cursor, key);
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
	private BPlusTreeNode<?> buildSpine(@Nonnull List<? extends BPlusTreeNode<?>> level) {
		if (level.size() == 1) {
			return level.get(0);
		}
		final int maxChildren = this.internalNodeBlockSize + 1;
		final int childTotal = level.size();
		final int parentCount = (childTotal + maxChildren - 1) / maxChildren;
		final int baseChildren = childTotal / parentCount;
		// the first `withExtra` parents take one extra child so the split is as even as possible
		final int withExtra = childTotal % parentCount;
		final List<BPlusInternalTreeNode> parents = new ArrayList<>(parentCount);
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
	private BPlusInternalTreeNode buildInternalNode(
		@Nonnull List<? extends BPlusTreeNode<?>> children, int from, int childCount
	) {
		final int[] keys = new int[this.internalNodeBlockSize];
		final BPlusTreeNode<?>[] childArray = new BPlusTreeNode[this.internalNodeBlockSize + 1];
		for (int i = 0; i < childCount; i++) {
			final BPlusTreeNode<?> child = children.get(from + i);
			childArray[i] = child;
			if (i > 0) {
				// separator i-1 routes to child i — the left boundary key invariant the tree enforces
				keys[i - 1] = leftBoundaryKeyOf(child);
			}
		}
		return new BPlusInternalTreeNode(keys, childArray, childCount - 1, true);
	}

	/**
	 * Inserts an element into the B+ tree. The ordering / identity key is derived from the element via the tree's key
	 * extractor; if an element with the same key already exists it is replaced. If the owning leaf overflows it is split
	 * to maintain the properties of the tree.
	 *
	 * @param value the element to be inserted into the B+ tree
	 */
	public void insert(@Nonnull E value) {
		final int key = this.keyExtractor.applyAsInt(value);
		final Cursor cursor = createCursor(key);
		final BPlusLeafTreeNode<E> leaf = cursor.leafNode();
		if (leaf.insert(value)) {
			this.size.set(size() + 1);
			// op-time boundary-mutation asserts run before the (possible) split, while the cursor still reflects
			// the pre-split spine — a mis-routed insert corrupts cross-leaf order without any structural op firing
			assertInsertBoundaries(cursor, key);
			// register the dirtied leaf as a dirty-scope token for this transaction
			registerDirtyLeafInScope(this, leaf);
		}

		// Split the leaf node if it exceeds the block size
		if (leaf.isFull()) {
			splitLeafNode(leaf, cursor);
		}
	}

	/**
	 * Deletes the element associated with the specified key from the B+ tree. The method locates the appropriate leaf
	 * node containing the key and removes the entry from it, ensuring that the B+ tree properties are maintained after
	 * deletion.
	 *
	 * @param key the key whose associated element is to be removed from the B+ tree
	 */
	public void delete(int key) {
		final Cursor cursor = createCursor(key);
		final BPlusLeafTreeNode<E> leaf = cursor.leafNode();

		final boolean headRemoved = leaf.size() > 1 && key == leaf.getLeftBoundaryKey();
		if (leaf.delete(key)) {
			this.size.set(size() - 1);
			// register the dirtied leaf as a dirty-scope token for this transaction: a removal
			// narrows the leaf's key range, but a later reverted layer could restore the wider pre-transaction range
			// and overlap a neighbour that split during the transaction — so removals are validated too
			registerDirtyLeafInScope(this, leaf);
		}

		// if the head of the leaf has been removed, we need to update parent keys accordingly
		if (headRemoved) {
			updateParentKeys(cursor.toCursorWithLevel());
		}

		consolidate(cursor);
	}

	/**
	 * Searches for the element associated with the given key in the B+ tree. Returns the stored element reference
	 * directly (or {@code null} when absent) instead of an {@link java.util.Optional} — this is the hot price-record
	 * lookup path and a per-lookup Optional allocation is deliberately avoided.
	 *
	 * @param key the key to search for within the B+ tree
	 * @return the element associated with the key, or {@code null} if the key is not present
	 */
	@Nullable
	public E search(int key) {
		final Cursor cursor = createCursor(key);
		final BPlusLeafTreeNode<E> leaf = cursor.leafNode();
		return leaf.getValue(key);
	}

	/**
	 * Materializes all elements in ascending derived-key order into a freshly allocated typed array — the full-scan
	 * projection.
	 *
	 * @return a new {@code E[]} holding every element in ascending key order
	 */
	@Nonnull
	public E[] toArray() {
		final E[] result = newValueArray(size());
		int cursor = 0;
		final Iterator<E> it = valueIterator();
		while (it.hasNext()) {
			result[cursor++] = it.next();
		}
		return result;
	}

	/**
	 * Returns an iterator that traverses the B+ tree elements from left to right (ascending derived key).
	 *
	 * @return a left-to-right element iterator
	 */
	@Nonnull
	public Iterator<E> valueIterator() {
		return new ForwardElementIterator<>(createLeftmostCursor(), this.keyExtractor);
	}

	/**
	 * Returns an iterator that traverses the B+ tree elements from left to right starting at the element whose derived
	 * key is the specified key, or the first element with a greater key. The key need not be present.
	 *
	 * @param key the key from which to start the iteration
	 * @return a left-to-right element iterator starting at the given key
	 */
	@Nonnull
	public Iterator<E> greaterOrEqualValueIterator(int key) {
		return new ForwardElementIterator<>(createCursor(key), key, this.keyExtractor);
	}

	/**
	 * Returns an iterator that traverses the B+ tree elements from right to left starting at the element whose derived
	 * key is the specified key, or the first element with a lesser key. The key need not be present.
	 *
	 * @param key the key from which to start the iteration
	 * @return a right-to-left element iterator starting at the given key
	 */
	@Nonnull
	public Iterator<E> lesserOrEqualValueIterator(int key) {
		return new ReverseElementIterator<>(createCursor(key), key, this.keyExtractor);
	}

	/**
	 * Returns an iterator that traverses the B+ tree elements from right to left (descending derived key).
	 *
	 * @return a right-to-left element iterator
	 */
	@Nonnull
	public Iterator<E> valueReverseIterator() {
		return new ReverseElementIterator<>(createRightmostCursor(), this.keyExtractor);
	}

	/**
	 * Returns an iterator that traverses the derived keys from left to right (ascending).
	 *
	 * @return a left-to-right key iterator
	 */
	@Nonnull
	@Override
	public OfInt keyIterator() {
		return new ForwardKeyIterator<>(createLeftmostCursor(), this.keyExtractor);
	}

	/**
	 * Returns an iterator that traverses the derived keys from left to right starting from the specified key or the first
	 * key greater than it. The key need not be present.
	 *
	 * @param key the key from which to start the iteration
	 * @return a left-to-right key iterator starting at the given key
	 */
	@Nonnull
	public OfInt greaterOrEqualKeyIterator(int key) {
		return new ForwardKeyIterator<>(createCursor(key), key, this.keyExtractor);
	}

	/**
	 * Returns an iterator that traverses the derived keys from right to left starting from the specified key or the first
	 * key lesser than it. The key need not be present.
	 *
	 * @param key the key from which to start the iteration
	 * @return a right-to-left key iterator starting at the given key
	 */
	@Nonnull
	public OfInt lesserOrEqualKeyIterator(int key) {
		return new ReverseKeyIterator<>(createCursor(key), key, this.keyExtractor);
	}

	/**
	 * Returns an iterator that traverses the derived keys from right to left (descending).
	 *
	 * @return a right-to-left key iterator
	 */
	@Nonnull
	@Override
	public OfInt keyReverseIterator() {
		return new ReverseKeyIterator<>(createRightmostCursor(), this.keyExtractor);
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
	public TransactionalElementBPlusTree<E> createCopyWithMergedTransactionalMemory(
		@Nullable Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		final BPlusTreeNode<?> theRoot = transactionalLayer.getStateCopyWithCommittedChanges(this.root).orElseThrow();
		final TransactionalElementBPlusTree<E> merged;
		if (theRoot instanceof BPlusLeafTreeNode || theRoot instanceof BPlusInternalTreeNode) {
			merged = new TransactionalElementBPlusTree<>(
				this.valueBlockSize, this.minValueBlockSize,
				this.internalNodeBlockSize, this.minInternalNodeBlockSize,
				this.elementType,
				this.keyExtractor,
				transactionalLayer.getStateCopyWithCommittedChanges(theRoot),
				transactionalLayer.getStateCopyWithCommittedChanges(this.size).orElseThrow()
			);
		} else {
			throw new GenericEvitaInternalError("Unknown node type: " + theRoot);
		}
		// post-replay (merge-time): before this merged version can propagate to the live view, re-derive the
		// cross-leaf boundary invariants for every leaf this transaction dirtied — against the freshly merged
		// structure (plain reads; the merged nodes are fresh or unchanged-and-layer-free, so the descent never
		// consults a diff layer). Registered objects are key sources only; each is relocated by its current key
		// in the merged tree.
		final Set<Object> dirtyScope = transactionalLayer.getDirtyScopeTokens(this);
		if (!dirtyScope.isEmpty()) {
			merged.validateDirtyScope(dirtyScope);
		}
		return merged;
	}

	/**
	 * Allocates a fresh typed element array of the given length.
	 *
	 * @param length the array length
	 * @return a new {@code E[]} of the requested length
	 */
	@Nonnull
	private E[] newValueArray(int length) {
		//noinspection unchecked
		return (E[]) Array.newInstance(this.elementType, length);
	}

	/**
	 * Splits a full leaf node into two leaf nodes to maintain the properties of the B+ tree. If the split occurs at the
	 * root, a new root is created.
	 *
	 * @param leaf   The leaf node to be split
	 * @param cursor The cursor representing the path from the root to the leaf node
	 */
	private void splitLeafNode(
		@Nonnull BPlusLeafTreeNode<E> leaf,
		@Nonnull Cursor cursor
	) {
		final int mid = this.valueBlockSize / 2;
		final E[] originValues = leaf.getValues();

		// structural assert: the split partitions a sorted leaf into a left half [0, mid) and a right half
		// [mid, length); the left leaf's last key must sort strictly before the right leaf's first key, and the
		// promoted separator is exactly that right first key. A violation means the leaf being split was already
		// out of order — fail fast rather than persist two overlapping pages.
		final int leftLastKey = this.keyExtractor.applyAsInt(originValues[mid - 1]);
		final int rightFirstKey = this.keyExtractor.applyAsInt(originValues[mid]);
		if (leftLastKey >= rightFirstKey) {
			throw new GenericEvitaInternalError(
				"Corrupted in-memory B+ tree: splitting a leaf produced overlapping halves — the left half's last " +
					"key (" + leftLastKey + ") does not sort before the right half's first key (" +
					rightFirstKey + "). The leaf being split was already out of order. Restore the catalog from a " +
					"backup, or fully rebuild / reindex the affected catalog."
			);
		}

		// Move half the values into the new array of the left leaf node. Split offspring are transaction-aware (see
		// splitNodesJoinTransactionalLayer): inside an active transaction they mutate in place, so the commit-merge
		// rebuild (newPriceRecordTree from attachToCatalog) does not try to open a diff layer on a finalized transaction.
		final BPlusLeafTreeNode<E> leftLeaf = new BPlusLeafTreeNode<>(
			originValues,
			newValueArray(this.valueBlockSize),
			0,
			mid,
			!Transaction.isTransactionAvailable(),
			this.keyExtractor
		);

		// Move the other half to the start of the existing array of the former leaf in the right leaf node
		final BPlusLeafTreeNode<E> rightLeaf = new BPlusLeafTreeNode<>(
			originValues,
			originValues,
			mid,
			originValues.length,
			!Transaction.isTransactionAvailable(),
			this.keyExtractor
		);

		// remove changes of the previous leaf - it gets replaced
		if (Transaction.getTransactionalMemoryLayerIfExists(leaf) != null) {
			leaf.removeLayer();
		}

		// if the root splits, create a new root
		if (leaf == this.getRoot()) {
			this.setRoot(
				new BPlusInternalTreeNode(
					this.valueBlockSize,
					rightLeaf.getLeftBoundaryKey(),
					leftLeaf, rightLeaf,
					!Transaction.isTransactionAvailable()
				)
			);
		} else {
			replaceNodeInParentInternalNode(
				leaf,
				leftLeaf,
				rightLeaf,
				rightLeaf.getLeftBoundaryKey(),
				cursor.toCursorWithLevel()
			);
		}
		// a split creates a brand-new adjacent leaf pair with a fresh separator between them — register both halves
		// as dirty-scope tokens for this transaction
		registerDirtyLeafInScope(this, leftLeaf);
		registerDirtyLeafInScope(this, rightLeaf);
	}

	/**
	 * Internal (routing) node of this element-keyed tree. All structure-maintenance logic — descent, split, borrow,
	 * merge and the copy-on-write transactional bookkeeping — lives in {@link AbstractIntKeyedInternalNode}; only the
	 * constructors and the {@link #createNode} factory are tree-local, because a generic base cannot {@code new} its
	 * own concrete subclass. It is value-type agnostic (its children are reached through the key-agnostic SPI), so it
	 * is non-generic.
	 */
	static class BPlusInternalTreeNode extends AbstractIntKeyedInternalNode<BPlusInternalTreeNode> {
		@Serial private static final long serialVersionUID = -3461802957184756103L;

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

		/**
		 * Rewrites the separator for the child at {@code index} (head-key propagation) and then runs the
		 * separator-order belt over the just-written slot. The superclass writes into whichever separator array is
		 * live — the committed {@code this.keys} when no transactional layer exists, or the decoupled {@code layer.keys}
		 * otherwise — so reading the array back via {@link #getKeys()} / {@link #getPeek()} covers both branches with a
		 * single zero-allocation check on the same array the write landed in.
		 *
		 * @param index the index in the keys array whose separator is rewritten (must be greater than 0)
		 * @param node  the child node whose left boundary key becomes the new separator at {@code index}
		 */
		@Override
		public void updateKeyForNode(int index, @Nonnull BPlusTreeNode<?> node) {
			super.updateKeyForNode(index, node);
			// separator-order belt: the rewritten separator must keep strict local order
			assertSeparatorOrder(getKeys(), getPeek(), index - 1);
		}

	}

	/**
	 * Leaf node implementation of the B+ tree that stores the elements. The leaf holds a single ascending-by-derived-key
	 * {@code E[]} with **no parallel key array** — a key is re-derived on demand via {@link #keyExtractor} during the
	 * (zero-boxing) binary searches — which is the whole memory point of this tree. The leaf also carries the granular
	 * persistence bookkeeping ({@link #pageSequence} / {@link #dirty}) and so implements {@link LeafBPlusTreeNode}.
	 *
	 * @param <E> the element (value) type
	 */
	static class BPlusLeafTreeNode<E> implements
		LeafBPlusTreeNode<BPlusLeafTreeNode<E>>,
		IntBoundaryKeyedNode,
		Snapshotable<BPlusLeafTreeNode.BPlusLeafNodeMemento<E>> {
		@Serial private static final long serialVersionUID = 4087516269781010854L;
		@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
		/**
		 * Indicates whether this instance is permitted to create and use transactional layers. The tree nodes use
		 * themselves (the same class) as their transactional memory layer, and if this layer were to also use
		 * transactional memory, it would create an infinite loop. This flag prevents that behavior.
		 */
		private final boolean transactionalLayer;
		/**
		 * The pure function deriving an element's ordering / identity key, shared with the owning tree and propagated to
		 * every leaf produced by a split / merge / commit-merge so a leaf can binary-search itself without storing keys.
		 */
		@Nonnull private final ToIntFunction<E> keyExtractor;
		/**
		 * The elements stored in this node, kept ascending by their derived key. No parallel key array is held.
		 */
		private E[] values;
		/**
		 * Index of the last occupied position in the values array.
		 */
		private int peek;
		/**
		 * The logical persistence page this leaf occupies. NOT transactional — it is structural bookkeeping carried across
		 * the commit-merge by {@link #createCopyWithMergedTransactionalMemory} (an in-place rebuild of this leaf reuses the
		 * source page so the same storage part is overwritten) and is left at
		 * {@link AbstractTransactionalBPlusTree#UNASSIGNED_PAGE_SEQUENCE} on split-born leaves until the granular write path
		 * allocates a page.
		 */
		private int pageSequence = UNASSIGNED_PAGE_SEQUENCE;
		/**
		 * The granular-storage change-detection flag: `true` when this leaf has been mutated since the last flush emitted
		 * its page. Routed through the leaf's transactional layer (like the value column) so a change made inside a
		 * transaction is visible at flush yet isolated from concurrent readers and discarded on abort. A committed leaf the
		 * merge produces defaults to clean; the emitter clears the flag once it has collected the page. It is the
		 * deterministic replacement for a content hash — every mutation site sets it, so a real change can never be
		 * suppressed.
		 */
		private boolean dirty = false;

		/**
		 * Creates a new empty leaf node with the specified block size.
		 *
		 * @param blockSize          the maximum number of elements this leaf node can hold
		 * @param elementType        the class of the elements stored in this node (used to allocate the typed array)
		 * @param keyExtractor       the function deriving an element's ordering / identity key
		 * @param transactionalLayer whether this node participates in the transactional memory layer
		 */
		public BPlusLeafTreeNode(
			int blockSize,
			@Nonnull Class<E> elementType,
			@Nonnull ToIntFunction<E> keyExtractor,
			boolean transactionalLayer
		) {
			//noinspection unchecked
			this.values = (E[]) Array.newInstance(elementType, blockSize);
			this.keyExtractor = keyExtractor;
			this.peek = -1;
			this.transactionalLayer = transactionalLayer;
		}

		/**
		 * Creates a new leaf node by copying a range of values from an origin array into a target array. This constructor
		 * is used during node split operations.
		 *
		 * @param originValues       the source array of values to copy from
		 * @param values             the target array for values (may be the same as originValues)
		 * @param start              the start index (inclusive) in the origin array
		 * @param end                the end index (exclusive) in the origin array
		 * @param transactionalLayer whether this node participates in the transactional memory layer
		 * @param keyExtractor       the function deriving an element's ordering / identity key
		 */
		public BPlusLeafTreeNode(
			@Nonnull E[] originValues,
			@Nonnull E[] values,
			int start, int end,
			boolean transactionalLayer,
			@Nonnull ToIntFunction<E> keyExtractor
		) {
			this.values = values;
			// Copy the values from the origin array
			System.arraycopy(originValues, start, values, 0, end - start);
			//noinspection ArrayEquality
			if (values == originValues) {
				Arrays.fill(values, end - start, values.length, null);
			}
			this.peek = end - start - 1;
			this.transactionalLayer = transactionalLayer;
			this.keyExtractor = keyExtractor;
		}

		private BPlusLeafTreeNode(
			@Nonnull E[] values,
			int peek,
			boolean transactionalLayer,
			@Nonnull ToIntFunction<E> keyExtractor
		) {
			this.values = values;
			this.peek = peek;
			this.transactionalLayer = transactionalLayer;
			this.keyExtractor = keyExtractor;
		}

		/**
		 * Returns the node holding this leaf's current state — the transactional diff layer if one exists, otherwise
		 * the leaf itself. Allocation-free (returns `this` or the already-allocated layer); the read accessors resolve
		 * the layer once through it instead of repeating the resolve-and-branch prologue.
		 *
		 * @return the node whose `values` / `peek` / `dirty` reflect the current (possibly uncommitted) state
		 */
		@Nonnull
		private BPlusLeafTreeNode<E> currentState() {
			final BPlusLeafTreeNode<E> layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) :
				null;
			return layer == null ? this : layer;
		}

		@Override
		public int getPeek() {
			return currentState().peek;
		}

		@Override
		public void setPeek(int peek) {
			final BPlusLeafTreeNode<E> layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
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
					Arrays.fill(this.values, peek + 1, originPeek + 1, null);
				}
			} else {
				final int originPeek = layer.peek;
				layer.peek = peek;
				if (peek < originPeek) {
					// internal arrays may have been still identical to the original arrays
					// we need to copy them in the transactional layer, before modifying

					//noinspection ArrayEquality
					if (layer.values == this.values) {
						layer.values = newValueArrayLike(this.values, this.values.length);
						System.arraycopy(this.values, 0, layer.values, 0, originPeek + 1);
					} else {
						Arrays.fill(layer.values, peek + 1, originPeek + 1, null);
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
			final BPlusLeafTreeNode<E> current = currentState();
			return current.peek == current.values.length - 1;
		}

		@Override
		public void toVerboseString(@Nonnull StringBuilder sb, int level, int indentSpaces) {
			sb.append(" ".repeat(level * indentSpaces));
			final BPlusLeafTreeNode<E> current = currentState();
			final E[] theValues = current.values;
			final int thePeek = current.peek;

			for (int i = 0; i <= thePeek; i++) {
				sb.append(this.keyExtractor.applyAsInt(theValues[i])).append(":").append(theValues[i]);
				if (i < thePeek) {
					sb.append(", ");
				}
			}
		}

		@Override
		public void stealFromLeft(int numberOfTailValues, @Nonnull BPlusLeafTreeNode<E> previousNode) {
			Assert.isPremiseValid(numberOfTailValues > 0, "Number of tail values to steal must be positive!");
			final BPlusLeafTreeNode<E> layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			// the receiving leaf's page changes; the donor is flagged via its own setPeek below
			if (layer == null) {
				this.dirty = true;
				System.arraycopy(this.values, 0, this.values, numberOfTailValues, this.peek + 1);
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

				layer.dirty = true;
				System.arraycopy(layer.values, 0, layer.values, numberOfTailValues, layer.peek + 1);
				System.arraycopy(
					previousNode.getValues(), previousNode.size() - numberOfTailValues, layer.values, 0,
					numberOfTailValues
				);
				layer.peek += numberOfTailValues;
				previousNode.setPeek(previousNode.getPeek() - numberOfTailValues);
			}
		}

		@Override
		public void stealFromRight(int numberOfHeadValues, @Nonnull BPlusLeafTreeNode<E> nextNode) {
			Assert.isPremiseValid(numberOfHeadValues > 0, "Number of head values to steal must be positive!");

			final BPlusLeafTreeNode<E> layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			// the receiving leaf's page changes; the donor is flagged via its own setPeek below
			if (layer == null) {
				this.dirty = true;
				// the right sibling may be a committed (shared) node while `this` is a transaction-local node
				// (transactionalLayer == false): steal-from-right SHIFTS the sibling's array in place, so it must
				// decouple it first or it would corrupt the shared committed state. getValuesForUpdate decouples a
				// committed sibling inside a transaction and is an in-place no-op outside one.
				final E[] nextNodeValues = nextNode.getValuesForUpdate();
				System.arraycopy(nextNodeValues, 0, this.values, this.peek + 1, numberOfHeadValues);
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

				layer.dirty = true;
				final E[] nextNodeValues = nextNode.getValuesForUpdate();
				System.arraycopy(nextNodeValues, 0, layer.values, layer.peek + 1, numberOfHeadValues);
				System.arraycopy(
					nextNodeValues, numberOfHeadValues, nextNodeValues, 0,
					nextNode.size() - numberOfHeadValues
				);
				nextNode.setPeek(nextNode.getPeek() - numberOfHeadValues);
				layer.peek += numberOfHeadValues;
			}
		}

		@Override
		public void mergeWithLeft(@Nonnull BPlusLeafTreeNode<E> previousNode) {
			final int mergePeek = previousNode.getPeek();
			final BPlusLeafTreeNode<E> layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			// merging shifts this leaf's content and prepends the donor's: flag the receiver (the donor is detached)
			if (layer == null) {
				this.dirty = true;
				System.arraycopy(this.values, 0, this.values, mergePeek + 1, this.peek + 1);
				System.arraycopy(previousNode.getValues(), 0, this.values, 0, mergePeek + 1);
				this.peek += mergePeek + 1;
				previousNode.setPeek(-1);
			} else {
				// we need to decouple the arrays before modifying them
				decoupleTransactionalArrays();
				previousNode.decoupleTransactionalArrays();

				layer.dirty = true;
				System.arraycopy(layer.values, 0, layer.values, mergePeek + 1, layer.peek + 1);
				System.arraycopy(previousNode.getValues(), 0, layer.values, 0, mergePeek + 1);
				layer.peek += mergePeek + 1;
				previousNode.setPeek(-1);
			}
		}

		@Override
		public void mergeWithRight(@Nonnull BPlusLeafTreeNode<E> nextNode) {
			final int mergePeek = nextNode.getPeek();
			final BPlusLeafTreeNode<E> layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			// appending the donor's content mutates this leaf's page: flag the receiver (the donor is detached)
			if (layer == null) {
				this.dirty = true;
				System.arraycopy(nextNode.getValues(), 0, this.values, this.peek + 1, mergePeek + 1);
				this.peek += mergePeek + 1;
				nextNode.setPeek(-1);
			} else {
				// we need to decouple the arrays before modifying them
				decoupleTransactionalArrays();
				nextNode.decoupleTransactionalArrays();

				layer.dirty = true;
				System.arraycopy(nextNode.getValues(), 0, layer.values, layer.peek + 1, mergePeek + 1);
				layer.peek += mergePeek + 1;
				nextNode.setPeek(-1);
			}
		}

		@Override
		public int getLeftBoundaryKey() {
			return this.keyExtractor.applyAsInt(currentState().values[0]);
		}

		/**
		 * Retrieves the values of the current node, but only for READ-ONLY purposes.
		 *
		 * @return an array of values representing the values of the current node.
		 */
		@Nonnull
		public E[] getValues() {
			return currentState().values;
		}

		/**
		 * Value-erased read of this leaf's payload — the granular write path's {@link LeafPageHandle} captures it to
		 * materialize a page. For this tree it is exactly the transaction-aware (read-your-writes) {@link #getValues()}
		 * array, which is already an {@code Object[]} subtype, so no copy is made.
		 *
		 * @return the leaf's value array, value-erased to {@code Object[]}
		 */
		@Nonnull
		@Override
		public Object[] getValueArray() {
			return getValues();
		}

		/**
		 * Retrieves the values of the current node for updating. If a transactional layer is active, it ensures that
		 * updates are performed on an independent copy of the values array within the transactional layer.
		 *
		 * @return an array of values representing the values of the current node, adjusted for the transactional layer if applicable.
		 */
		@Nonnull
		public E[] getValuesForUpdate() {
			final BPlusLeafTreeNode<E> layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			if (layer == null) {
				return this.values;
			} else {
				// internal arrays may have been still identical to the original arrays
				// we need to copy them in the transactional layer, before modifying

				//noinspection ArrayEquality
				if (layer.values == this.values) {
					layer.values = newValueArrayLike(this.values, this.values.length);
					System.arraycopy(this.values, 0, layer.values, 0, this.values.length);
				}
				return layer.values;
			}
		}

		/**
		 * Searches for an element in the leaf by the specified key and returns it, or {@code null} when absent.
		 *
		 * @param key the key to search for in the leaf node
		 * @return the element associated with the specified key if found; otherwise {@code null}
		 */
		@Nullable
		public E getValue(int key) {
			final BPlusLeafTreeNode<E> current = currentState();
			final E[] theValues = current.values;
			final InsertionPosition insertionPosition = searchKey(key, theValues, current.peek);
			return insertionPosition.alreadyPresent() ? theValues[insertionPosition.position()] : null;
		}

		/**
		 * Searches for the index of an element in the leaf by the specified key. Returns the index of the element if
		 * found, or -1 if the key is not present.
		 *
		 * @param key the key to search for in the leaf node
		 * @return the index of the element in the values array if found; -1 otherwise
		 */
		public int getValueIndex(int key) {
			final BPlusLeafTreeNode<E> current = currentState();
			final InsertionPosition insertionPosition = searchKey(key, current.values, current.peek);
			return insertionPosition.alreadyPresent() ? insertionPosition.position() : -1;
		}

		/**
		 * Resolves the insertion position of the given key in the leaf's (transaction-aware) value array. Used by the
		 * keyed-start iterators, which need only the primitive position / present flag.
		 *
		 * @param key the key to locate
		 * @return the insertion position of the key within this leaf
		 */
		@Nonnull
		public InsertionPosition findKeyPosition(int key) {
			final BPlusLeafTreeNode<E> current = currentState();
			return searchKey(key, current.values, current.peek);
		}

		@Override
		public int getPageSequence() {
			return this.pageSequence;
		}

		@Override
		public void setPageSequence(int pageSequence) {
			this.pageSequence = pageSequence;
		}

		@Override
		public boolean isDirty() {
			return currentState().dirty;
		}

		@Override
		public void clearDirty() {
			// symmetric with isDirty(): currentState() resolves the diff layer if one exists (never creating one),
			// so clearing the flag there lands on the committed instance or the transaction's layer as appropriate
			currentState().dirty = false;
		}

		@Override
		public BPlusLeafTreeNode<E> createLayer() {
			return new BPlusLeafTreeNode<>(
				this.values,
				this.peek,
				false,
				this.keyExtractor
			);
		}

		@Override
		public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			transactionalLayer.removeTransactionalMemoryLayer(this);
		}

		/**
		 * Captures this layer's revertable copy-on-write state for a per-entity savepoint. Only the values array and the
		 * peek index are mutable here (the keys are derived from the elements, so no key array is held); the array is
		 * cloned (shallow — the stored elements either are immutable or own their own transactional layers and are
		 * snapshotted independently) so that a later mutation, or a repeated {@link #restore}, cannot corrupt the
		 * memento.
		 *
		 * @return an independent snapshot of this leaf's value array and peek
		 */
		@Nonnull
		@Override
		public BPlusLeafNodeMemento<E> snapshot() {
			return new BPlusLeafNodeMemento<>(this.values.clone(), this.peek);
		}

		/**
		 * Restores the value array captured by {@link #snapshot}. A fresh clone of the memento's array is installed so
		 * the memento stays reusable for a repeated restore.
		 *
		 * @param memento the state previously captured by {@link #snapshot}
		 */
		@Override
		public void restore(@Nonnull BPlusLeafNodeMemento<E> memento) {
			this.values = memento.values().clone();
			this.peek = memento.peek();
		}

		/**
		 * Immutable savepoint memento of an element leaf's copy-on-write state. The array is a private clone owned by the
		 * memento (see {@link #snapshot}); the elements it holds are shared by design.
		 *
		 * @param values clone of the element array
		 * @param peek   the last occupied value index
		 */
		record BPlusLeafNodeMemento<E>(
			@Nonnull E[] values,
			int peek
		) {
		}

		@Nonnull
		@Override
		public BPlusLeafTreeNode<E> createCopyWithMergedTransactionalMemory(
			@Nullable BPlusLeafTreeNode<E> layer,
			@Nonnull TransactionalLayerMaintainer transactionalLayer
		) {
			final E[] theValues;
			final int thePeek;
			if (layer == null) {
				theValues = this.values;
				thePeek = this.peek;
			} else {
				theValues = layer.values;
				thePeek = layer.peek;
			}

			final BPlusLeafTreeNode<E> result;
			// element values are non-transactional references, so there is nothing to merge in the values themselves
			if (layer != null) {
				result = new BPlusLeafTreeNode<>(
					theValues,
					thePeek,
					true,
					this.keyExtractor
				);
			} else if (!this.transactionalLayer) {
				// nodes created during splits/merges are built with transactionalLayer=false so they do
				// not allocate STM layers mid-transaction; on commit they must be rebuilt as participating
				// (transactionalLayer=true) nodes so subsequent transactions can layer changes over them
				result = new BPlusLeafTreeNode<>(
					theValues,
					thePeek,
					true,
					this.keyExtractor
				);
			} else {
				return this;
			}
			// carry the logical persistence page across the rebuild: an in-place rebuild of this leaf rewrites the SAME
			// page (reuse this.pageSequence), while a split-born leaf keeps its UNASSIGNED_PAGE_SEQUENCE so the write path
			// allocates it fresh
			result.pageSequence = this.pageSequence;
			return result;
		}

		/**
		 * Inserts an element into this leaf, preserving ascending derived-key order. If an element with the same key is
		 * already present it is replaced in place (no size change).
		 *
		 * @param value the element to insert
		 * @return true if a new element was inserted, false if it replaced an existing one
		 */
		private boolean insert(@Nonnull E value) {
			final int key = this.keyExtractor.applyAsInt(value);
			final BPlusLeafTreeNode<E> layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			// inserting / replacing an element mutates this leaf's page: flag it for re-emission
			if (layer == null) {
				this.dirty = true;
				Assert.isPremiseValid(
					this.peek < this.values.length - 1,
					"Cannot insert into a full leaf node, split the node first!"
				);

				final InsertionPosition insertionPosition = searchKey(key, this.values, this.peek);
				if (insertionPosition.alreadyPresent()) {
					this.values[insertionPosition.position()] = value;
					return false;
				} else {
					insertRecordIntoSameArrayOnIndex(value, this.values, insertionPosition.position());
					this.peek++;
					return true;
				}
			} else {
				decoupleTransactionalArrays();
				layer.dirty = true;
				Assert.isPremiseValid(
					layer.peek < layer.values.length - 1,
					"Cannot insert into a full leaf node, split the node first!"
				);

				final InsertionPosition insertionPosition = searchKey(key, layer.values, layer.peek);
				if (insertionPosition.alreadyPresent()) {
					layer.values[insertionPosition.position()] = value;
					return false;
				} else {
					insertRecordIntoSameArrayOnIndex(value, layer.values, insertionPosition.position());
					layer.peek++;
					return true;
				}
			}
		}

		/**
		 * Deletes the element with the specified key from this leaf. If the key is found it removes the corresponding
		 * entry, maintains the node's internal structure, and decrements the count of stored items.
		 *
		 * @param key the key of the element to be removed from the leaf node
		 * @return true if the key was found and removed, false otherwise
		 */
		public boolean delete(int key) {
			final BPlusLeafTreeNode<E> layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			// deleting an entry mutates this leaf's page: flag it for re-emission (a no-op delete over-reports at worst)
			if (layer == null) {
				this.dirty = true;
				final InsertionPosition insertionPosition = searchKey(key, this.values, this.peek);
				if (insertionPosition.alreadyPresent()) {
					removeRecordFromSameArrayOnIndex(this.values, insertionPosition.position());
					this.values[this.peek] = null;
					this.peek--;
					return true;
				} else {
					return false;
				}
			} else {
				decoupleTransactionalArrays();
				layer.dirty = true;
				final InsertionPosition insertionPosition = searchKey(key, layer.values, layer.peek);
				if (insertionPosition.alreadyPresent()) {
					removeRecordFromSameArrayOnIndex(layer.values, insertionPosition.position());
					layer.values[layer.peek] = null;
					layer.peek--;
					return true;
				} else {
					return false;
				}
			}
		}

		@Override
		public String toString() {
			final StringBuilder sb = new StringBuilder(64);
			toVerboseString(sb, 0, 3);
			return sb.toString();
		}

		/**
		 * Binary-searches the given value range for the supplied key, deriving each candidate's key on the fly via
		 * {@link #keyExtractor} (no boxing, no key array). The range is `[0, peek]` inclusive.
		 *
		 * @param key    the key to locate
		 * @param values the (transaction-aware) value array to search
		 * @param peek   the index of the last occupied slot
		 * @return the insertion position (present + index, or absent + would-be index)
		 */
		@Nonnull
		private InsertionPosition searchKey(int key, @Nonnull E[] values, int peek) {
			int low = 0;
			int high = peek;
			while (low <= high) {
				final int mid = (low + high) >>> 1;
				final int midKey = this.keyExtractor.applyAsInt(values[mid]);
				if (midKey < key) {
					low = mid + 1;
				} else if (midKey > key) {
					high = mid - 1;
				} else {
					return new InsertionPosition(mid, true);
				}
			}
			return new InsertionPosition(low, false);
		}

		/**
		 * Internal arrays may have been still identical to the original arrays we need to copy them in the transactional
		 * layer before modifying.
		 */
		private void decoupleTransactionalArrays() {
			final BPlusLeafTreeNode<E> layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			if (layer != null) {
				//noinspection ArrayEquality
				if (layer.values == this.values) {
					layer.values = newValueArrayLike(this.values, this.values.length);
					System.arraycopy(this.values, 0, layer.values, 0, this.peek + 1);
				}
			}
		}

		/**
		 * Allocates a fresh array with the same runtime component type as the supplied template — keeps the leaf's typed
		 * {@code E[]} representation when decoupling / resizing without storing the element class on the node.
		 *
		 * @param template the array whose component type the new array adopts
		 * @param length   the length of the new array
		 * @return a new array of the template's component type and the requested length
		 */
		@Nonnull
		private static <E> E[] newValueArrayLike(@Nonnull E[] template, int length) {
			//noinspection unchecked
			return (E[]) Array.newInstance(template.getClass().getComponentType(), length);
		}
	}

	/**
	 * Element forward iterator base: layers the typed leaf-value cache on top of the shared key-agnostic
	 * {@link AbstractForwardTreeNavigator}. The concrete element / key iterators read the current element straight from
	 * the cached array - the element iterator returns it directly, the key iterator derives its key via the extractor.
	 *
	 * @param <E> the element (value) type
	 */
	private abstract static class AbstractForwardElementIterator<E> extends AbstractForwardTreeNavigator {
		/**
		 * The current leaf's value array, refreshed once per leaf by {@link #loadCurrentLeaf()}. Visible to subclasses so
		 * the element / key iterators index it directly without a per-element accessor call.
		 */
		protected E[] leafValues;
		/**
		 * The key extractor, used by the key iterator to derive a key from a cached element. Assigned after the
		 * {@code super(...)} call - {@link #loadCurrentLeaf()} does not read it, so the start leaf resolves correctly.
		 */
		protected final ToIntFunction<E> keyExtractor;

		/**
		 * Creates a forward iterator starting from the leftmost position of the cursor.
		 *
		 * @param cursor       the cursor providing the traversal path through the B+ tree
		 * @param keyExtractor the element key extractor
		 */
		protected AbstractForwardElementIterator(@Nonnull Cursor cursor, @Nonnull ToIntFunction<E> keyExtractor) {
			super(cursor);
			this.keyExtractor = keyExtractor;
		}

		/**
		 * Creates a forward iterator starting from the specified key or the first key greater than it.
		 *
		 * @param cursor       the cursor providing the traversal path through the B+ tree
		 * @param key          the key to start the iteration from
		 * @param keyExtractor the element key extractor
		 */
		protected AbstractForwardElementIterator(
			@Nonnull Cursor cursor, int key, @Nonnull ToIntFunction<E> keyExtractor
		) {
			super(cursor, cursor.<BPlusLeafTreeNode<E>>leafNode().findKeyPosition(key));
			this.keyExtractor = keyExtractor;
		}

		@Override
		protected void loadCurrentLeaf() {
			//noinspection unchecked
			final BPlusLeafTreeNode<E> leaf = (BPlusLeafTreeNode<E>) currentLeafNode();
			this.leafValues = leaf.getValues();
			this.leafPeek = leaf.getPeek();
		}
	}

	/**
	 * Element reverse iterator base: layers the typed leaf-value cache on top of the shared key-agnostic
	 * {@link AbstractReverseTreeNavigator}. The concrete element / key iterators read the current element straight from
	 * the cached array.
	 *
	 * @param <E> the element (value) type
	 */
	private abstract static class AbstractReverseElementIterator<E> extends AbstractReverseTreeNavigator {
		/**
		 * The current leaf's value array, refreshed once per leaf by {@link #loadCurrentLeaf()}. Visible to subclasses so
		 * the element / key iterators index it directly without a per-element accessor call.
		 */
		protected E[] leafValues;
		/**
		 * The key extractor, used by the key iterator to derive a key from a cached element.
		 */
		protected final ToIntFunction<E> keyExtractor;

		/**
		 * Creates a reverse iterator starting from the rightmost position of the cursor.
		 *
		 * @param cursor       the cursor providing the traversal path through the B+ tree
		 * @param keyExtractor the element key extractor
		 */
		protected AbstractReverseElementIterator(@Nonnull Cursor cursor, @Nonnull ToIntFunction<E> keyExtractor) {
			super(cursor);
			this.keyExtractor = keyExtractor;
		}

		/**
		 * Creates a reverse iterator starting from the specified key or the first key lesser than or equal to it.
		 *
		 * @param cursor       the cursor providing the traversal path through the B+ tree
		 * @param key          the key to start the iteration from
		 * @param keyExtractor the element key extractor
		 */
		protected AbstractReverseElementIterator(
			@Nonnull Cursor cursor, int key, @Nonnull ToIntFunction<E> keyExtractor
		) {
			super(cursor, cursor.<BPlusLeafTreeNode<E>>leafNode().findKeyPosition(key));
			this.keyExtractor = keyExtractor;
		}

		@Override
		protected void loadCurrentLeaf() {
			//noinspection unchecked
			final BPlusLeafTreeNode<E> leaf = (BPlusLeafTreeNode<E>) currentLeafNode();
			this.leafValues = leaf.getValues();
			this.leafPeek = leaf.getPeek();
		}
	}

	/**
	 * Iterator that traverses the B+ tree elements from left to right (ascending derived key).
	 *
	 * @param <E> the element (value) type
	 */
	private static class ForwardElementIterator<E> extends AbstractForwardElementIterator<E> implements Iterator<E> {

		/**
		 * Creates a forward element iterator starting from the leftmost element.
		 *
		 * @param cursor       the cursor providing the traversal path through the B+ tree
		 * @param keyExtractor the element key extractor
		 */
		public ForwardElementIterator(@Nonnull Cursor cursor, @Nonnull ToIntFunction<E> keyExtractor) {
			super(cursor, keyExtractor);
		}

		/**
		 * Creates a forward element iterator starting from the specified key or the first key greater than it.
		 *
		 * @param cursor       the cursor providing the traversal path through the B+ tree
		 * @param key          the key to start the iteration from
		 * @param keyExtractor the element key extractor
		 */
		public ForwardElementIterator(@Nonnull Cursor cursor, int key, @Nonnull ToIntFunction<E> keyExtractor) {
			super(cursor, key, keyExtractor);
		}

		@Override
		public E next() {
			if (!this.hasNextElement) {
				throw new NoSuchElementException("No more elements available");
			}
			// read straight from the cached leaf value array - no per-element ThreadLocal accessor call
			final E value = this.leafValues[this.currentIndex];
			advance();
			return value;
		}
	}

	/**
	 * Iterator that traverses the B+ tree elements from right to left (descending derived key).
	 *
	 * @param <E> the element (value) type
	 */
	private static class ReverseElementIterator<E> extends AbstractReverseElementIterator<E> implements Iterator<E> {

		/**
		 * Creates a reverse element iterator starting from the rightmost element.
		 *
		 * @param cursor       the cursor providing the traversal path through the B+ tree
		 * @param keyExtractor the element key extractor
		 */
		public ReverseElementIterator(@Nonnull Cursor cursor, @Nonnull ToIntFunction<E> keyExtractor) {
			super(cursor, keyExtractor);
		}

		/**
		 * Creates a reverse element iterator starting from the specified key or the first key lesser than or equal to it.
		 *
		 * @param cursor       the cursor providing the traversal path through the B+ tree
		 * @param key          the key to start the iteration from
		 * @param keyExtractor the element key extractor
		 */
		public ReverseElementIterator(@Nonnull Cursor cursor, int key, @Nonnull ToIntFunction<E> keyExtractor) {
			super(cursor, key, keyExtractor);
		}

		@Override
		public E next() {
			if (!this.hasNextElement) {
				throw new NoSuchElementException("No more elements available");
			}
			// read straight from the cached leaf value array - no per-element ThreadLocal accessor call
			final E value = this.leafValues[this.currentIndex];
			advance();
			return value;
		}
	}

	/**
	 * Iterator that traverses the derived keys from left to right. The primitive key is returned directly from
	 * {@link #nextInt()} so that no boxing occurs on the iteration path.
	 *
	 * @param <E> the element (value) type
	 */
	private static class ForwardKeyIterator<E> extends AbstractForwardElementIterator<E> implements OfInt {

		/**
		 * Creates a forward key iterator starting from the leftmost key.
		 *
		 * @param cursor       the cursor providing the traversal path through the B+ tree
		 * @param keyExtractor the element key extractor
		 */
		public ForwardKeyIterator(@Nonnull Cursor cursor, @Nonnull ToIntFunction<E> keyExtractor) {
			super(cursor, keyExtractor);
		}

		/**
		 * Creates a forward key iterator starting from the specified key or the first key greater than it.
		 *
		 * @param cursor       the cursor providing the traversal path through the B+ tree
		 * @param key          the key to start the iteration from
		 * @param keyExtractor the element key extractor
		 */
		public ForwardKeyIterator(@Nonnull Cursor cursor, int key, @Nonnull ToIntFunction<E> keyExtractor) {
			super(cursor, key, keyExtractor);
		}

		@Override
		public int nextInt() {
			if (!this.hasNextElement) {
				throw new NoSuchElementException("No more elements available");
			}
			final int key = this.keyExtractor.applyAsInt(this.leafValues[this.currentIndex]);
			advance();
			return key;
		}
	}

	/**
	 * Iterator that traverses the derived keys from right to left. The primitive key is returned directly from
	 * {@link #nextInt()} so that no boxing occurs on the iteration path.
	 *
	 * @param <E> the element (value) type
	 */
	private static class ReverseKeyIterator<E> extends AbstractReverseElementIterator<E> implements OfInt {

		/**
		 * Creates a reverse key iterator starting from the rightmost key.
		 *
		 * @param cursor       the cursor providing the traversal path through the B+ tree
		 * @param keyExtractor the element key extractor
		 */
		public ReverseKeyIterator(@Nonnull Cursor cursor, @Nonnull ToIntFunction<E> keyExtractor) {
			super(cursor, keyExtractor);
		}

		/**
		 * Creates a reverse key iterator starting from the specified key or the first key lesser than or equal to it.
		 *
		 * @param cursor       the cursor providing the traversal path through the B+ tree
		 * @param key          the key to start the iteration from
		 * @param keyExtractor the element key extractor
		 */
		public ReverseKeyIterator(@Nonnull Cursor cursor, int key, @Nonnull ToIntFunction<E> keyExtractor) {
			super(cursor, key, keyExtractor);
		}

		@Override
		public int nextInt() {
			if (!this.hasNextElement) {
				throw new NoSuchElementException("No more elements available");
			}
			final int key = this.keyExtractor.applyAsInt(this.leafValues[this.currentIndex]);
			advance();
			return key;
		}
	}

}
