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
import io.evitadb.core.transaction.memory.TransactionalStateProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.dataType.ConsistencySensitiveDataStructure;
import io.evitadb.exception.GenericEvitaInternalError;
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
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.PrimitiveIterator.OfLong;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.function.UnaryOperator;

import static io.evitadb.utils.ArrayUtils.*;

/**
 * Represents a B+ Tree data structure specifically designed for long keys and generic values.
 * The tree is balanced and allows for efficient insertion, deletion, and search operations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@NotThreadSafe
public class TransactionalLongBPlusTree<V> extends AbstractTransactionalBPlusTree implements
	TransactionalLayerProducer<Void, TransactionalLongBPlusTree<V>>,
	DirtyScopeValidator,
	Serializable,
	ConsistencySensitiveDataStructure {
	@Serial private static final long serialVersionUID = 124088192205606247L;
	private static final String ERROR_UPDATER_RETURNED_NULL = "The updater returned null - a B+ tree value must " +
		"never be null, because a stored null is indistinguishable from an absent key on the read path!";
	private static final int DEFAULT_VALUE_BLOCK_SIZE = 64;
	private static final int DEFAULT_MIN_VALUE_BLOCK_SIZE = DEFAULT_VALUE_BLOCK_SIZE / 2 - 1;
	private static final int DEFAULT_INTERNAL_NODE_BLOCK_SIZE = DEFAULT_VALUE_BLOCK_SIZE / 2 - 1;
	private static final int DEFAULT_MIN_INTERNAL_NODE_BLOCK_SIZE = (int) (Math.ceil(
		(float) DEFAULT_INTERNAL_NODE_BLOCK_SIZE / 2.0) - 1);
	/**
	 * The type of the values stored in the tree.
	 */
	@Getter private final Class<V> valueType;
	/**
	 * Operator that wraps the values in a transactional layer.
	 */
	private final Function<Object, V> transactionalLayerWrapper;

	/**
	 * Returns the class type of the generic TransactionalLongBPlusTree with the specified key and value types.
	 * This method may be necessary if you need the proper generic class for constructor of other classes.
	 *
	 * @param <V> the type of values in the TransactionalLongBPlusTree
	 * @return the Class object representing the type TransactionalLongBPlusTree with the specified generic parameters
	 */
	@Nonnull
	public static <V> Class<TransactionalLongBPlusTree<V>> genericClass() {
		//noinspection unchecked
		return (Class<TransactionalLongBPlusTree<V>>) (Class<?>) TransactionalLongBPlusTree.class;
	}

	/**
	 * Returns the left boundary key of an arbitrary node reached through the key-agnostic {@link BPlusTreeNode} SPI
	 * (e.g. an element of an internal node's children array). The primitive-key accessor lives on the per-tree
	 * {@link LongKeyedNode} marker so it stays out of the shared SPI (which must never expose a typed key); every node
	 * in this tree implements it, so the cast is always safe.
	 *
	 * @param node the node whose left boundary key is requested
	 * @return the left boundary (smallest) key of the node
	 */
	private static long leftBoundaryKeyOf(@Nonnull BPlusTreeNode<?> node) {
		return ((LongKeyedNode) node).getLeftBoundaryKey();
	}

	/**
	 * The last slot index a reader may address on the `keys` / `values` arrays **it has already read**, given the
	 * `peek` it has already read.
	 *
	 * **This is a concurrency bound, not a consistency check.** For any caller sharing a happens-before edge with
	 * the writer — the whole write path, every descent under a transaction — it returns `peek` unchanged and is a
	 * pure no-op: a leaf's arrays are grown to the length the mutation will need *before* the moves that raise
	 * `peek`, every shrink lowers `peek` and leaves the arrays alone, and the commit-merge trim builds a **new**
	 * leaf rather than shortening this one. So the bound can never truncate a view that was consistent to begin
	 * with.
	 *
	 * It exists for the readers that have no such edge. {@code EntityCollection#describeIndex} states outright that
	 * it takes no snapshot, and hands a live {@link io.evitadb.index.range.RangeIndex} to
	 * {@code IndexDetailProjection}, which walks it for its heap size from a request thread while a warm-up bulk
	 * load may be mutating it. Such a reader can pair a freshly-read `peek` with the array that preceded the growth
	 * which raised it.
	 *
	 * **This bound became necessary when the leaf arrays started following their content.** While every leaf was
	 * allocated at the full block size and never replaced, the same torn read landed inside a fixed-length array
	 * and merely returned a stale element; now it runs off the end. Both arrays are asked, not just the keys: they
	 * are grown by two independent reallocations, so a torn reader can catch either one behind the other.
	 *
	 * ## CALIBRATION — read this before simplifying the bound away
	 *
	 * A green concurrent sweep on an x86 box is **evidence about the box, not about this code**: x86's total store
	 * order forbids the reordering this guards, while the Java memory model permits it regardless and AArch64 —
	 * which evitaDB is also built for — reaches it in silicon. The deterministic half is what pins this instead:
	 * {@code TransactionalLongBPlusTreeTest.TornLeafReaderBoundTest} builds the torn shape directly and gives each
	 * guarded reader — the heap walk, point lookup, forward and reverse iteration, and the verbose rendering a
	 * debugger or log statement triggers — its own test, so no one of them can be proven by another throwing
	 * first. Remove the clamp and all five throw {@link ArrayIndexOutOfBoundsException}. This mirrors
	 * {@code TransactionalBucketBPlusTree#observableLeafPeek}, whose javadoc carries the same calibration for the
	 * column-backed sibling.
	 *
	 * @param peek   the leaf's own last-occupied slot index, as the caller read it
	 * @param keys   the leaf's key array, as the caller read it
	 * @param values the leaf's value array, as the caller read it
	 * @return the last slot index the reader may address, `-1` when it may read nothing
	 */
	private static int observableLeafPeek(int peek, @Nonnull long[] keys, @Nonnull Object[] values) {
		return Math.min(peek, Math.min(keys.length, values.length) - 1);
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
			final long[] keys = internalNode.getKeys();
			final BPlusTreeNode<?>[] children = internalNode.getChildren();
			if (internalNode.getPeek() >= 0) {
				verifyInternalNodeKeys(children[0]);
			}
			for (int i = 0; i < internalNode.getPeek(); i++) {
				final long key = keys[i];
				final BPlusTreeNode<?> child = children[i + 1];
				if (child instanceof BPlusInternalTreeNode childInternalNode) {
					if (childInternalNode.getLeftBoundaryKey() != key) {
						throw new IllegalStateException(
							"Internal node " + childInternalNode + " has a different left boundary key (" +
								childInternalNode.getLeftBoundaryKey() + ") than the internal node key (" + key + ")!"
						);
					}
					verifyInternalNodeKeys(childInternalNode);
				} else if (child instanceof BPlusLeafTreeNode<?> childLeafNode) {
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
	 * Verifies the integrity of the forward key iterator for a given {@link TransactionalLongBPlusTree}.
	 * Checks if the keys from the iterator are returned in strictly increasing order and
	 * validates the total number of keys returned matches the expected size.
	 *
	 * @param tree the {@link TransactionalLongBPlusTree} whose key iterator is to be verified
	 * @param size the expected number of keys in the {@link TransactionalLongBPlusTree}
	 * @throws IllegalStateException if the iterator fails to return keys in increasing order
	 *                               or if the number of keys does not match the expected size
	 */
	private static void verifyForwardKeyIterator(@Nonnull TransactionalLongBPlusTree<?> tree, int size) {
		int actualSize = 0;
		long previousKey = Long.MIN_VALUE;
		final OfLong it = tree.keyIterator();
		while (it.hasNext()) {
			final long key = it.nextLong();
			if (key <= previousKey && previousKey != Long.MIN_VALUE) {
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
	private static void verifyReverseKeyIterator(@Nonnull TransactionalLongBPlusTree<?> tree, int size) {
		int actualSize = 0;
		long previousKey = Long.MIN_VALUE;
		final OfLong it = tree.keyReverseIterator();
		while (it.hasNext()) {
			final long key = it.nextLong();
			if (key >= previousKey && previousKey != Long.MIN_VALUE) {
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
		long key,
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
	 *
	 * @param valueType                 the type of the values stored in the tree
	 * @param transactionalLayerWrapper operator that wraps the values in a transactional layer
	 */
	public TransactionalLongBPlusTree(
		@Nonnull Class<V> valueType,
		@Nonnull Function<Object, V> transactionalLayerWrapper
	) {
		this(
			DEFAULT_VALUE_BLOCK_SIZE,
			DEFAULT_MIN_VALUE_BLOCK_SIZE,
			DEFAULT_INTERNAL_NODE_BLOCK_SIZE,
			DEFAULT_MIN_INTERNAL_NODE_BLOCK_SIZE,
			valueType,
			transactionalLayerWrapper,
			new BPlusLeafTreeNode<>(DEFAULT_VALUE_BLOCK_SIZE, valueType, transactionalLayerWrapper, true),
			0
		);
	}

	/**
	 * Constructor to initialize the B+ Tree.
	 *
	 * @param valueBlockSize maximum number of values in a leaf node
	 * @param valueType      the type of the values stored in the tree
	 */
	public TransactionalLongBPlusTree(int valueBlockSize, @Nonnull Class<V> valueType) {
		this(
			valueBlockSize, valueBlockSize / 2,
			valueBlockSize, valueBlockSize / 2,
			valueType
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
	 * @param valueType                the type of the values stored in the tree
	 */
	public TransactionalLongBPlusTree(
		int valueBlockSize,
		int minValueBlockSize,
		int internalNodeBlockSize,
		int minInternalNodeBlockSize,
		@Nonnull Class<V> valueType
	) {
		this(
			valueBlockSize,
			minValueBlockSize,
			internalNodeBlockSize,
			minInternalNodeBlockSize,
			valueType,
			null,
			new BPlusLeafTreeNode<>(valueBlockSize, valueType, null, true),
			0
		);
	}

	/**
	 * Constructor to initialize the B+ Tree with explicit block sizes and a value wrapper - the wrapper-aware
	 * counterpart of {@link #TransactionalLongBPlusTree(int, int, int, int, Class)}, required when the value type
	 * implements {@link TransactionalLayerProducer} (e.g. `TransactionalRangePoint`) and therefore must be wrapped on
	 * commit. Lets consumers tune the leaf block size for their workload.
	 *
	 * @param valueBlockSize            maximum number of values in a leaf node
	 * @param minValueBlockSize         minimum number of values in a leaf node
	 * @param internalNodeBlockSize     maximum number of keys in an internal node
	 * @param minInternalNodeBlockSize  minimum number of keys in an internal node
	 * @param valueType                 the type of the values stored in the tree
	 * @param transactionalLayerWrapper operator that wraps the values in a transactional layer
	 */
	public TransactionalLongBPlusTree(
		int valueBlockSize,
		int minValueBlockSize,
		int internalNodeBlockSize,
		int minInternalNodeBlockSize,
		@Nonnull Class<V> valueType,
		@Nonnull Function<Object, V> transactionalLayerWrapper
	) {
		this(
			valueBlockSize,
			minValueBlockSize,
			internalNodeBlockSize,
			minInternalNodeBlockSize,
			valueType,
			transactionalLayerWrapper,
			new BPlusLeafTreeNode<>(valueBlockSize, valueType, transactionalLayerWrapper, true),
			0
		);
	}

	private TransactionalLongBPlusTree(
		int valueBlockSize,
		int minValueBlockSize,
		int internalNodeBlockSize,
		int minInternalNodeBlockSize,
		@Nonnull Class<V> valueType,
		@Nullable Function<Object, V> transactionalLayerWrapper,
		@Nonnull BPlusTreeNode<?> root,
		int size
	) {
		super(valueBlockSize, minValueBlockSize, internalNodeBlockSize, minInternalNodeBlockSize, root, size);
		Assert.isPremiseValid(
			transactionalLayerWrapper != null || !TransactionalStateProducer.class.isAssignableFrom(valueType),
			"Value type cannot implement TransactionalStateProducer if no transactional layer wrapper is provided."
		);
		this.valueType = valueType;
		this.transactionalLayerWrapper = transactionalLayerWrapper;
	}

	@Nonnull
	@Override
	protected BPlusTreeNode<?> newEmptyLeaf() {
		return new BPlusLeafTreeNode<>(this.valueBlockSize, this.valueType, this.transactionalLayerWrapper, true);
	}

	/**
	 * Re-assembles a B+ tree from a pre-built, ascending-ordered sequence of leaf nodes, deriving the internal routing
	 * spine bottom-up. This is the inverse of {@link #enumerateLeaves()} and the foundation of the granular load path:
	 * leaf pages are read straight from disk and the spine is reconstructed here in a single pass rather than rebuilt by
	 * replaying per-record inserts.
	 *
	 * Separators are the left boundary keys of the children, honoring the tree's separator-from-first-key invariant, so
	 * no separators need be persisted. The assembled tree reuses this tree's block-size configuration, value type and
	 * transactional-layer wrapper.
	 *
	 * WARNING: the assembled tree REUSES (aliases) the supplied leaf node instances — it does not copy them. Intended for
	 * the load path (leaves freshly built from disk pages, owned by no other tree) and read-only round-trips. Do NOT keep
	 * mutating the source leaves after handing them here unless the source is being discarded.
	 *
	 * @param orderedLeaves the leaves in ascending key order; must be non-empty
	 * @return a new tree whose values are exactly those held by the supplied leaves
	 */
	@Nonnull
	public TransactionalLongBPlusTree<V> assembleFromLeaves(@Nonnull List<BPlusLeafTreeNode<V>> orderedLeaves) {
		Assert.isPremiseValid(!orderedLeaves.isEmpty(), "At least one leaf node is required to assemble a tree.");
		int totalValues = 0;
		for (BPlusLeafTreeNode<V> orderedLeaf : orderedLeaves) {
			totalValues += orderedLeaf.size();
		}
		final BPlusTreeNode<?> assembledRoot = buildSpine(new ArrayList<>(orderedLeaves));
		return new TransactionalLongBPlusTree<>(
			this.valueBlockSize, this.minValueBlockSize,
			this.internalNodeBlockSize, this.minInternalNodeBlockSize,
			this.valueType,
			this.transactionalLayerWrapper,
			assembledRoot,
			totalValues
		);
	}

	/**
	 * Re-assembles a B+ tree from a sequence of single-leaf source trees — one per persisted leaf page — preserving the
	 * original leaf boundaries exactly and stamping each leaf with its persisted page sequence. This is the
	 * boundary-stable load path for the granular layout: a caller that owns the value representation
	 * builds one single-leaf tree per persisted page via the public {@link #insert} surface, then hands them here in
	 * ascending key order together with their page sequences. The resulting tree's leaf *i* is byte-identical to
	 * persisted page *i*, so the change-detection baseline restored alongside it makes the first post-restart commit a
	 * true no-op for unchanged leaves (no full re-pagination).
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
	 *                               `persisted ` (e.g. `range index for attribute …`)
	 * @return a new tree whose leaves are exactly those of the supplied trees, each stamped with its page sequence
	 * @throws GenericEvitaInternalError when the persisted leaf pages violate strict cross-leaf key order
	 */
	@Nonnull
	public TransactionalLongBPlusTree<V> assembleFromSingleLeafTrees(
		@Nonnull List<TransactionalLongBPlusTree<V>> orderedSingleLeafTrees,
		@Nonnull int[] pageSequences,
		@Nonnull String structureDescription
	) {
		Assert.isPremiseValid(
			orderedSingleLeafTrees.size() == pageSequences.length,
			"The number of single-leaf trees must match the number of page sequences."
		);
		final List<BPlusLeafTreeNode<V>> leaves = new ArrayList<>(pageSequences.length);
		for (int i = 0; i < pageSequences.length; i++) {
			final BPlusTreeNode<?> root = orderedSingleLeafTrees.get(i).getRoot();
			Assert.isPremiseValid(
				root instanceof BPlusLeafTreeNode,
				"Each persisted leaf page must rebuild to exactly one leaf."
			);
			//noinspection unchecked
			final BPlusLeafTreeNode<V> leaf = (BPlusLeafTreeNode<V>) root;
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
	 * sort strictly before the first key of the next key-bearing leaf (natural `long` order). Empty leaves carry no key
	 * and impose no boundary constraint, so they are skipped when locating the previous key-bearing leaf.
	 *
	 * A paged index persists one storage part per B+ tree leaf plus a root part listing the ordered leaf-page sequence;
	 * the reload path re-assembles one in-memory leaf per persisted page. A writer race on a `@NotThreadSafe` warm-up
	 * session can leave a frozen stale snapshot of a leaf reachable next to the page that superseded it, and a one-shot
	 * flush persists BOTH — every subsequent reload then rebuilds a tree whose leaves overlap, silently serving corrupt
	 * data until it crashes later with a confusing signature far from the cause. **The paged persistence layout HAS
	 * shipped** - it went out with the 2026.2 release line (tags `v2026.2.0` .. `v2026.2.6`), and released catalogs are
	 * on disk in it right now, so a production catalog really can carry such a twin and staying loadable across a
	 * restart is a live obligation rather than a theoretical one. It is still not repaired silently: nothing in the
	 * persisted state says which of the two overlapping leaves is authoritative, so adopting the stale one would
	 * resurrect records that were deliberately removed. Per the defensive-design rule any detected overlap therefore
	 * fails fast here with full diagnostics and an operator remediation hint.
	 *
	 * @param leaves               the reassembled leaves in persisted list order
	 * @param pageSequences        the root's ordered leaf-page sequence list, reported as overlap context on failure
	 * @param structureDescription a full identification of the index for diagnostics (see
	 *                             {@link #assembleFromSingleLeafTrees})
	 * @throws GenericEvitaInternalError when a leaf's last key does not sort strictly before the next leaf's first key
	 */
	private void assertCrossLeafBoundaries(
		@Nonnull List<BPlusLeafTreeNode<V>> leaves,
		@Nonnull int[] pageSequences,
		@Nonnull String structureDescription
	) {
		BPlusLeafTreeNode<V> previousKeyBearing = null;
		for (final BPlusLeafTreeNode<V> leaf : leaves) {
			final int peek = leaf.getPeek();
			if (peek < 0) {
				// empty leaf carries no key and cannot violate cross-leaf ordering
				continue;
			}
			final long[] keys = leaf.getKeys();
			// intra-leaf order: a serializer bug, truncated write or bit rot can leave a leaf whose interior keys
			// are out of order, while the cross-leaf walk alone would pass it — binary search inside such a leaf
			// then silently returns wrong answers. Assert each key sorts strictly after its predecessor within the
			// leaf (one comparison per key, once per load).
			for (int i = 1; i <= peek; i++) {
				if (keys[i - 1] >= keys[i]) {
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
				final long previousLastKey = previousKeyBearing.getKeys()[previousKeyBearing.getPeek()];
				final long currentFirstKey = keys[0];
				if (previousLastKey >= currentFirstKey) {
					// error path only: gather the full overlap context (ranges, counts, containment) here, never on a
					// healthy load
					final long predecessorFirstKey = previousKeyBearing.getKeys()[0];
					final long successorLastKey = keys[peek];
					final boolean successorWithinPredecessor =
						currentFirstKey >= predecessorFirstKey && successorLastKey <= previousLastKey;
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
	void assertTailBoundary(@Nonnull Cursor cursor, long newLastKey) {
		final List<CursorLevel> path = cursor.path();
		for (int level = path.size() - 1; level >= 1; level--) {
			final CursorLevel cursorLevel = path.get(level);
			final int childIndex = cursorLevel.index();
			if (childIndex < cursorLevel.peek()) {
				// the ancestor whose children are this level's siblings holds the fence separator at childIndex
				final CursorLevel ancestorLevel = path.get(level - 1);
				final BPlusInternalTreeNode ancestor =
					(BPlusInternalTreeNode) ancestorLevel.siblings()[ancestorLevel.index()];
				checkTailBoundary(true, ancestor.getKeys()[childIndex], newLastKey);
				return;
			}
		}
	}

	/**
	 * The tail-boundary comparison itself, shared by the path-based resolution above and the descent-based one in
	 * {@link #findLeafNodeWithBoundaryContext(long)}. Keeping the comparison in one place is what lets the insert path
	 * stop capturing a cursor without the two resolutions drifting apart.
	 *
	 * @param hasFence   whether the leaf has a successor at all (a rightmost descent at every level means it does not)
	 * @param fence      the leaf's upper fence, meaningful only when `hasFence`
	 * @param newLastKey the leaf's new last key after the mutation
	 * @throws GenericEvitaInternalError when the new last key does not sort strictly before the successor fence
	 */
	private static void checkTailBoundary(boolean hasFence, long fence, long newLastKey) {
		if (hasFence && newLastKey >= fence) {
			throw boundaryMutationError("tail", newLastKey, "before the successor leaf boundary", fence);
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
	void assertHeadBoundary(@Nonnull Cursor cursor, long newFirstKey) {
		checkHeadBoundary(predecessorLeaf(cursor), newFirstKey);
	}

	/**
	 * The head-boundary comparison itself, shared by the path-based predecessor resolution and the descent-based one
	 * in {@link #findLeafNodeWithBoundaryContext(long)}.
	 *
	 * @param predecessor the leaf preceding the mutated one, or {@code null} when it is the tree's leftmost leaf
	 * @param newFirstKey the leaf's new first key after the mutation
	 * @throws GenericEvitaInternalError when the new first key does not sort strictly after the predecessor boundary
	 */
	private static <V> void checkHeadBoundary(@Nullable BPlusLeafTreeNode<V> predecessor, long newFirstKey) {
		if (predecessor == null) {
			// leftmost leaf — no predecessor to violate
			return;
		}
		final int predecessorPeek = predecessor.getPeek();
		if (predecessorPeek < 0) {
			// an empty predecessor carries no key (mirrors the load-time empty-leaf skip)
			return;
		}
		final long predecessorLastKey = predecessor.getKeys()[predecessorPeek];
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
	BPlusLeafTreeNode<V> predecessorLeaf(@Nonnull Cursor cursor) {
		final List<CursorLevel> path = cursor.path();
		final CursorLevel leafLevel = path.get(path.size() - 1);
		final int leafIndex = leafLevel.index();
		if (leafIndex > 0) {
			//noinspection unchecked
			return (BPlusLeafTreeNode<V>) leafLevel.siblings()[leafIndex - 1];
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
				return (BPlusLeafTreeNode<V>) node;
			}
		}
		return null;
	}

	/**
	 * Builds the shared cross-leaf boundary corruption error, whose message names the offending boundary key,
	 * the neighbour boundary it collides with and a remediation hint. Shared by the op-time asserts and the
	 * commit-time relocate-and-validate pass, so its wording is cause-neutral (a mis-routed insertion, a
	 * reverted transactional layer or a merge defect could all produce the overlap) and view-neutral (no WAL /
	 * poison-pill wording — the trunk-replay path adds that when it wraps the exception).
	 *
	 * @param side        {@code "tail"} or {@code "head"} — which leaf boundary is out of order
	 * @param boundaryKey the leaf boundary key that violates cross-leaf order
	 * @param relation    the ordering relation that was expected (e.g. `before the successor leaf boundary`)
	 * @param neighborKey the adjacent leaf boundary that {@code boundaryKey} failed to sort against
	 * @return the corruption error to throw
	 */
	@Nonnull
	private static BPlusTreeCorruptedException boundaryMutationError(
		@Nonnull String side, long boundaryKey, @Nonnull String relation, long neighborKey) {
		return new BPlusTreeCorruptedException(
			"Corrupted in-memory B+ tree: a leaf's " + side + " boundary key " + boundaryKey + " does not sort " +
				relation + " (" + neighborKey + "). This indicates cross-leaf key overlap (a mis-routed insertion, a " +
				"reverted transactional layer, or a merge defect) that would overlap an adjacent leaf page on flush. " +
				"Restore the catalog from a backup, or fully rebuild / reindex the affected catalog."
		);
	}

	/**
	 * Registers a rebalanced leaf's current boundary key as a dirty-scope probe key for this transaction. Invoked by the
	 * base {@link #consolidate(Cursor)} for each leaf whose boundary keys a steal / merge shifted (always a leaf — the
	 * base guards the call with {@code !isInternal}).
	 *
	 * @param leaf the rebalanced leaf node
	 */
	@Override
	protected void registerConsolidatedLeaf(@Nonnull BPlusTreeNode<?> leaf) {
		//noinspection unchecked
		registerDirtyLeafInScope((BPlusLeafTreeNode<V>) leaf);
	}

	/**
	 * Registers the dirtied leaf's CURRENT first key as a dirty-scope probe key for this transaction. Called from every
	 * boundary-changing seam (insert, upsert-insert, delete, split, steal / merge). Reads the key through the
	 * transaction-aware {@link BPlusLeafTreeNode#getKeys()}, so it captures the post-mutation boundary; an emptied leaf
	 * carries no boundary key and is skipped — nothing needs relocating by a key that no longer exists.
	 *
	 * @param leaf the dirtied leaf node
	 */
	private void registerDirtyLeafInScope(@Nonnull BPlusLeafTreeNode<V> leaf) {
		final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
		// gate on the cheap checks before deriving (and boxing) the probe key: outside a transaction there is no
		// registry (warm-up bulk load pays nothing), and an emptied leaf has no boundary key to relocate by
		if (maintainer == null || leaf.getPeek() < 0) {
			return;
		}
		maintainer.registerDirtyScopeToken(this, leaf.getKeys()[0]);
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
	 * @throws BPlusTreeCorruptedException when a relocated leaf overlaps an adjacent leaf
	 */
	@Override
	public void validateDirtyScope(@Nonnull Collection<Object> registeredProbeKeys) {
		for (final Object token : registeredProbeKeys) {
			final long probeKey = (Long) token;
			final Cursor cursor = createCursor(probeKey);
			final BPlusLeafTreeNode<V> leaf = cursor.leafNode();
			final int peek = leaf.getPeek();
			if (peek < 0) {
				// the descent landed on an empty leaf — validating it is sound and vacuous
				continue;
			}
			final long[] keys = leaf.getKeys();
			assertTailBoundary(cursor, keys[peek]);
			assertHeadBoundary(cursor, keys[0]);
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
	static void assertSeparatorOrder(@Nonnull long[] keys, int peek, int slot) {
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
	private static GenericEvitaInternalError separatorOrderError(long leftKey, long rightKey) {
		return new GenericEvitaInternalError(
			"Corrupted in-memory B+ tree: internal separator keys are out of order (" + leftKey + " does not sort " +
				"before " + rightKey + "). This indicates stale/aliased internal-node state. Restore the catalog " +
				"from a backup, or fully rebuild / reindex the affected catalog."
		);
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
		// Pack at most internalNodeBlockSize children per parent (one below the node's children capacity of
		// internalNodeBlockSize + 1). An assembled node must be born non-full so the first child split can still call
		// adaptToLeafSplit (which requires !isFull()) before the parent overflows and splits in turn.
		final int maxChildren = this.internalNodeBlockSize;
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
		final long[] keys = new long[this.internalNodeBlockSize];
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
	 * Returns the heap this tree occupies in bytes, **excluding the values its leaves point at**.
	 *
	 * Like every heap-footprint reading over a tree this is `O(entries / blockSize)` rather than `O(1)`, so it
	 * belongs to the index detail call and never to a query path — see
	 * {@link BucketBPlusTree#getHeapSizeInBytes(ToLongFunction)} for the measured cost and where it goes.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes() {
		return getHeapSizeInBytes(element -> 0L);
	}

	/**
	 * Returns the heap this tree occupies in bytes, **including the values its leaves point at**, each priced by
	 * `elementSizer`.
	 *
	 * The caller owns the policy: return `0` for a value this tree merely borrows, and its real footprint for one
	 * it owns. A {@link io.evitadb.index.range.RangeIndex} owns its range points and prices them; an index holding
	 * values another structure maintains would not.
	 *
	 * @param elementSizer prices a single stored value; must return `0` for values this tree does not own
	 * @return the heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes(@Nonnull ToLongFunction<Object> elementSizer) {
		final VMLayout layout = VMLayout.current();
		// id + four block-size ints + valueType/wrapper/root/size slots, then the two TransactionalReference
		// holders with their AtomicReferences and the boxed size counter
		long ownSize = layout.sizeOfObject(Long.BYTES + 4L * Integer.BYTES + 4L * layout.referenceSize());
		final long transactionalReference = layout.sizeOfObject(Long.BYTES + layout.referenceSize())
			+ layout.sizeOfObject(layout.referenceSize());
		ownSize += 2L * transactionalReference + layout.sizeOfObject(Integer.BYTES);
		return ownSize + getNodeGraphHeapSizeInBytes(elementSizer);
	}

	/**
	 * Returns the heap of this tree's node graph alone — everything {@link #getHeapSizeInBytes()} counts except the
	 * tree object itself. Split out for the same reason as in {@link TransactionalBucketBPlusTree}: the tree holds a
	 * lambda field, and a lambda is a hidden class whose field offsets JOL cannot read, so only the node graph can
	 * be asserted against a real measurement.
	 *
	 * @param elementSizer prices a single stored value
	 * @return the heap footprint of every node in this tree, in bytes
	 */
	long getNodeGraphHeapSizeInBytes(@Nonnull ToLongFunction<Object> elementSizer) {
		return getRoot().getHeapSizeInBytes(elementSizer);
	}

	/**
	 * Inserts a key-value pair into the B+ tree. If the corresponding leaf node
	 * overflows, it is split to maintain the properties of the tree.
	 *
	 * @param key   the key to be inserted into the B+ tree
	 * @param value the value associated with the key, must not be null
	 */
	public void insert(long key, @Nonnull V value) {
		// the cursor path exists ONLY to cascade a split upward, yet it used to be allocated on every insert. The
		// descent below reaches the same leaf and resolves the boundary asserts' operands without capturing anything,
		// so a path is now built only when this insert can actually overflow the leaf.
		final BoundaryContext<V> context = findLeafNodeWithBoundaryContext(key);
		final BPlusLeafTreeNode<V> leaf = context.leaf();
		// captured BEFORE mutating: the split machinery replaces this leaf in its parent, so the path has to reflect
		// the pre-mutation tree
		final Cursor cursor = leaf.isNearlyFull() ? createCursor(key) : null;
		if (leaf.insert(key, value)) {
			this.size.set(size() + 1);
			// op-time boundary-mutation asserts run before the (possible) split, while the descent context still
			// reflects the pre-split spine — a mis-routed insert corrupts cross-leaf order without any structural op
			// firing
			assertInsertBoundaries(context, key);
			// register the dirtied leaf's current boundary key as a dirty-scope probe key for this transaction
			registerDirtyLeafInScope(leaf);
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
	 * Runs the op-time boundary-mutation asserts for a freshly inserted key. A tail insert raises the leaf's last
	 * key, a head insert lowers its first key, and the insert into an empty leaf (the 0→1 transition) does both;
	 * an interior insert cannot violate cross-leaf order in a sound tree and is not checked. Called on the leaf
	 * mutation path shared by warm-up bulk load, transactional ops and trunk replay.
	 *
	 * @param context the mutated leaf plus the fence and predecessor operands from the descent
	 * @param key     the key just inserted
	 */
	void assertInsertBoundaries(@Nonnull BoundaryContext<V> context, long key) {
		final BPlusLeafTreeNode<V> leaf = context.leaf();
		final long[] keys = leaf.getKeys();
		final int peek = leaf.getPeek();
		if (key == keys[peek]) {
			checkTailBoundary(context.hasFence(), context.fence(), key);
		}
		if (key == keys[0]) {
			checkHeadBoundary(context.predecessor(), key);
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
	private GenericEvitaInternalError missingSplitPathError(@Nonnull BPlusLeafTreeNode<V> leaf) {
		return new GenericEvitaInternalError(
			"Leaf is full but no cursor path was captured - `isNearlyFull` failed to predict `isFull` " +
				"(peek: " + leaf.getPeek() + ", capacity: " + leaf.capacity() +
				", tree block size: " + this.valueBlockSize + ")!",
			"Leaf is full but no cursor path was captured!"
		);
	}

	/**
	 * Everything the insert path needs from a single descent: the leaf that accommodates the key, plus the two
	 * neighbour operands the boundary asserts compare against. Produced by
	 * {@link #findLeafNodeWithBoundaryContext(long)}, which resolves all three without capturing a {@link Cursor}.
	 *
	 * The record is short-lived and never escapes the insert method, so the JIT scalar-replaces it.
	 *
	 * @param leaf              the leaf node responsible for the key (it may not yet contain it)
	 * @param hasFence          whether the leaf has a successor at all; {@code false} means it is the tree's last leaf
	 * @param fence             the leaf's upper fence — the first key of the successor leaf — meaningful only when
	 *                          {@code hasFence}
	 * @param predecessorParent the deepest internal node whose chosen child index was greater than zero, or
	 *                          {@code null} when the descent was leftmost at every level
	 * @param predecessorIndex  the child index chosen at {@code predecessorParent}
	 * @param <T>               the value type stored in the tree
	 */
	record BoundaryContext<T>(
		@Nonnull BPlusLeafTreeNode<T> leaf,
		boolean hasFence,
		long fence,
		@Nullable BPlusInternalTreeNode predecessorParent,
		int predecessorIndex
	) {

		/**
		 * Resolves the predecessor leaf. Kept out of the descent itself and behind this call because the head assert
		 * fires only when the inserted key becomes the leaf's first — every other insert would pay a transactional
		 * child-array resolution for an answer nobody reads.
		 *
		 * @return the leaf immediately preceding {@link #leaf()} in key order, or {@code null} when it is the tree's
		 * leftmost leaf
		 */
		@Nullable
		BPlusLeafTreeNode<T> predecessor() {
			return predecessorLeafOf(this.predecessorParent, this.predecessorIndex);
		}
	}

	/**
	 * The insert-path descent: reaches the same leaf as {@link #findLeafNode(long)} and, on the way, resolves the two
	 * operands the boundary asserts consume — the leaf's upper fence and its predecessor leaf.
	 *
	 * This is what lets the insert path stop capturing a cursor. Both asserts read the cursor as nothing but index
	 * arithmetic over the descent, so a descent keeping a few extra locals answers them without a path:
	 *
	 * - the **fence** is the separator at the deepest level whose descent was not into the rightmost child. Walking
	 *   down, the last level satisfying {@code childIndex < node.getPeek()} is exactly the level the cursor's
	 *   bottom-up walk stops at first, so overwriting a single local at every such level lands on the same key.
	 * - the **predecessor** hangs off the deepest level whose descent was not into the leftmost child. When that level
	 *   is the leaf's own parent, {@code children[childIndex - 1]} is the predecessor leaf directly and the right-spine
	 *   walk below is a no-op; when it is higher up, the walk follows the left neighbour's right spine. One rule covers
	 *   both branches of the path-based {@link #predecessorLeaf(Cursor)}.
	 *
	 * This matters more here than the lazy guard alone would: this tree's key order is workload-dependent (range
	 * thresholds), so how often the asserts would demand a path cannot be predicted. Answering them from the descent
	 * removes the question.
	 *
	 * @param key the key whose responsible leaf is located
	 * @return the leaf together with its boundary operands
	 */
	@Nonnull
	BoundaryContext<V> findLeafNodeWithBoundaryContext(long key) {
		BPlusTreeNode<?> node = this.getRoot();
		boolean hasFence = false;
		long fence = 0L;
		BPlusInternalTreeNode predecessorParent = null;
		int predecessorIndex = -1;
		while (node instanceof BPlusInternalTreeNode internalNode) {
			final int childIndex = internalNode.searchIndex(key);
			if (childIndex < internalNode.getPeek()) {
				hasFence = true;
				fence = internalNode.getKeys()[childIndex];
			}
			if (childIndex > 0) {
				predecessorParent = internalNode;
				predecessorIndex = childIndex;
			}
			node = internalNode.getChildren()[childIndex];
		}
		//noinspection unchecked
		final BPlusLeafTreeNode<V> leaf = (BPlusLeafTreeNode<V>) node;
		return new BoundaryContext<>(leaf, hasFence, fence, predecessorParent, predecessorIndex);
	}

	/**
	 * Resolves the predecessor leaf from the deepest descent level that was not into the leftmost child: takes that
	 * node's left neighbour and follows its right spine down.
	 *
	 * @param parent     the deepest internal node whose chosen child index was greater than zero, or {@code null} when
	 *                   the descent was leftmost at every level (the leaf is the tree's leftmost leaf)
	 * @param childIndex the child index chosen at {@code parent}
	 * @return the predecessor leaf, or {@code null} when there is none
	 */
	@Nullable
	private static <T> BPlusLeafTreeNode<T> predecessorLeafOf(
		@Nullable BPlusInternalTreeNode parent,
		int childIndex
	) {
		if (parent == null) {
			return null;
		}
		BPlusTreeNode<?> node = parent.getChildren()[childIndex - 1];
		while (node instanceof BPlusInternalTreeNode internalNode) {
			node = internalNode.getChildren()[internalNode.getPeek()];
		}
		//noinspection unchecked
		return (BPlusLeafTreeNode<T>) node;
	}

	/**
	 * Updates an existing key-value pair or inserts a new one into the B+ tree.
	 * If the key is already present, the value is updated based on the result of the updater function.
	 * If the key is not present, a new key-value pair is inserted with the value returned by the updater function.
	 * If the leaf node exceeds its block size after insertion, the node is split.
	 *
	 * The updater's result is the only door through which a `null` could enter the tree's value array
	 * ({@link #insert(long, Object)} takes a `@Nonnull V`), and a stored `null` would not surface as a failure - see
	 * {@link BPlusLeafTreeNode#getValue(long)}, which would answer it as "this tree does not hold that key" while the
	 * key demonstrably sits in a leaf. Both branches therefore refuse it outright.
	 *
	 * @param key     the key to update or insert, must not be null
	 * @param updater a function to compute a new value, must not be null and must not return null
	 */
	public void upsert(long key, @Nonnull UnaryOperator<V> updater) {
		// see insert(long, V) — the update branch below replaces a value in place and can never overflow the leaf, so
		// the guard sits inside the key-absent branch and is exact there
		final BoundaryContext<V> context = findLeafNodeWithBoundaryContext(key);
		final BPlusLeafTreeNode<V> leaf = context.leaf();

		final int existingIndex = leaf.getValueIndex(key);
		if (existingIndex >= 0) {
			// updating an existing value's slot mutates this leaf's page (the value object is replaced or mutated in
			// place by the updater) — flag the leaf so the granular write path re-emits its page. This branch does NOT
			// register the leaf for dirty-scope validation: it replaces a value at an existing key, so no key boundary
			// moves and no reverted layer could create cross-leaf overlap — validating it would be pure cost.
			leaf.markDirty();
			// update the value on specified index
			leaf.decoupleTransactionalArrays();
			final V[] values = leaf.getValues();
			final V previousValue = values[existingIndex];
			final V newValue = updater.apply(previousValue);
			Assert.isPremiseValid(newValue != null, ERROR_UPDATER_RETURNED_NULL);
			// when the updater returns a different instance the previous one is discarded from the tree;
			// release its transactional diff layer (if any) so it is not left ALIVE and detected as stale
			// during commit; when the updater mutates and returns the same instance, nothing is discarded
			if (newValue != previousValue) {
				BPlusLeafTreeNode.discardRemovedValueLayer(previousValue);
			}
			values[existingIndex] = newValue;
		} else {
			final Cursor cursor = leaf.isNearlyFull() ? createCursor(key) : null;
			final V insertedValue = updater.apply(null);
			Assert.isPremiseValid(insertedValue != null, ERROR_UPDATER_RETURNED_NULL);
			// insert the new value
			if (leaf.insert(key, insertedValue)) {
				this.size.set(size() + 1);
				// op-time boundary-mutation asserts — see insert(long, V)
				assertInsertBoundaries(context, key);
				// register the dirtied leaf's current boundary key as a dirty-scope probe key for this transaction
				registerDirtyLeafInScope(leaf);
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
	public void delete(long key) {
		final Cursor cursor = createCursor(key);
		final BPlusLeafTreeNode<V> leaf = cursor.leafNode();

		final boolean headRemoved = leaf.size() > 1 && key == leaf.getKeys()[0];
		if (leaf.delete(key)) {
			this.size.set(size() - 1);
			// register the dirtied leaf's current boundary key as a dirty-scope probe key for this transaction: a
			// removal narrows the leaf's key range, but a later reverted layer could restore the wider
			// pre-transaction range and overlap a neighbour that split during the transaction — so removals are
			// validated too
			registerDirtyLeafInScope(leaf);
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
	public Optional<V> search(long key) {
		return findLeafNode(key).getValue(key);
	}

	/**
	 * The same search as {@link #search(long)}, answering with `null` instead of an empty {@link Optional}.
	 *
	 * For callers that unwrap the result immediately and repeat the lookup often enough for the wrapper to show - a
	 * substring pattern probes one key per trigram, on every query.
	 *
	 * @param key the key to search for within the B+ tree
	 * @return the value associated with the key, or `null` when the tree does not hold it
	 */
	@Nullable
	public V searchOrNull(long key) {
		return findLeafNode(key).valueOrNull(key);
	}

	/**
	 * Flags the leaf holding the given key as dirty so the granular write path re-emits its page. Needed when a caller
	 * mutates a stored value's content out-of-band — obtaining the value via {@link #search(long)} and changing the
	 * object itself, while the leaf's own columns are untouched (e.g. a range point's record set) — a change the
	 * per-mutation marks on {@link #insert}/{@link #delete} would otherwise miss. A no-op when the key is absent.
	 *
	 * @param key the key whose holding leaf must be flagged dirty
	 */
	public void markDirty(long key) {
		final BPlusLeafTreeNode<V> leaf = findLeafNode(key);
		if (leaf.getValueIndex(key) >= 0) {
			// flags an out-of-band value-content change; no key boundary moves, so this is not registered for the
			// transaction's dirty scope (see the in-place branch of upsert)
			leaf.markDirty();
		}
	}

	/**
	 * Returns an iterator that traverses the B+ tree keys from left to right.
	 *
	 * @return an iterator that traverses the B+ tree keys from left to right
	 */
	@Nonnull
	public OfLong keyIterator() {
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
	public OfLong greaterOrEqualKeyIterator(long key) {
		return new ForwardTreeKeyIterator<>(createCursor(key), key);
	}

	/**
	 * Returns an iterator that traverses the B+ tree keys from right to left starting from the specified key or
	 * a key that is immediately lesser than the specified key. The key may not be present in the tree.
	 *
	 * @param key the key from which to start the iteration
	 * @return an iterator that traverses the B+ tree keys from right to left starting from the specified key
	 */
	@Nonnull
	public OfLong lesserOrEqualKeyIterator(long key) {
		return new ReverseTreeKeyIterator<>(createCursor(key), key);
	}

	/**
	 * Returns an iterator that traverses the B+ tree keys from right to left.
	 *
	 * @return an iterator that traverses the B+ tree keys from right to left
	 */
	@Nonnull
	public OfLong keyReverseIterator() {
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
	public Iterator<V> greaterOrEqualValueIterator(long key) {
		return new ForwardTreeValueIterator<>(createCursor(key), key);
	}

	/**
	 * Returns an iterator that traverses the B+ tree values from right to left starting from the specified key or
	 * a key that is immediately lesser than the specified key. The key may not be present in the tree.
	 *
	 * @param key the key from which to start the iteration
	 * @return an iterator that traverses the B+ tree values from right to left starting from the specified key
	 */
	@Nonnull
	public Iterator<V> lesserOrEqualValueIterator(long key) {
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
	public Iterator<Entry<V>> entryIterator() {
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
	public Iterator<Entry<V>> greaterOrEqualEntryIterator(long key) {
		return new ForwardTreeEntryIterator<>(createCursor(key), key);
	}

	/**
	 * Returns an iterator that traverses the B+ tree entries (both keys and values) from right to left starting from the specified key or
	 * a key that is immediately lesser than the specified key. The key may not be present in the tree.
	 *
	 * @param key the key from which to start the iteration
	 * @return an iterator that traverses the B+ tree entries (both keys and values) from right to left starting from the specified key
	 */
	@Nonnull
	public Iterator<Entry<V>> lesserOrEqualEntryIterator(long key) {
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
		return ((BPlusLeafTreeNode<?>) leaf).getValues();
	}

	@Nonnull
	@Override
	public TransactionalLongBPlusTree<V> createCopyWithMergedTransactionalMemory(
		@Nullable Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		final BPlusTreeNode<?> theRoot = transactionalLayer.getStateCopyWithCommittedChanges(this.root).orElseThrow();
		final TransactionalLongBPlusTree<V> merged;
		if (theRoot instanceof BPlusLeafTreeNode<?> leafNode) {
			//noinspection unchecked
			final BPlusLeafTreeNode<V> theLeafNode = (BPlusLeafTreeNode<V>) leafNode;
			merged = new TransactionalLongBPlusTree<>(
				this.valueBlockSize, this.minValueBlockSize,
				this.internalNodeBlockSize, this.minInternalNodeBlockSize,
				this.valueType,
				this.transactionalLayerWrapper,
				transactionalLayer.getStateCopyWithCommittedChanges(theLeafNode),
				transactionalLayer.getStateCopyWithCommittedChanges(this.size).orElseThrow()
			);
		} else if (theRoot instanceof BPlusInternalTreeNode internalNode) {
			merged = new TransactionalLongBPlusTree<>(
				this.valueBlockSize, this.minValueBlockSize,
				this.internalNodeBlockSize, this.minInternalNodeBlockSize,
				this.valueType,
				this.transactionalLayerWrapper,
				transactionalLayer.getStateCopyWithCommittedChanges(internalNode),
				transactionalLayer.getStateCopyWithCommittedChanges(this.size).orElseThrow()
			);
		} else {
			throw new GenericEvitaInternalError("Unknown node type: " + theRoot);
		}
		// post-replay (merge-time): before this merged version can propagate to the live view, re-derive the
		// cross-leaf boundary invariants for every leaf this transaction dirtied — against the freshly merged
		// structure (plain reads; the merged nodes are fresh or unchanged-and-layer-free, so the descent never
		// consults a diff layer).
		// The registry holds boundary keys, not nodes; each key routes to whatever leaf currently owns it in the
		// merged tree, and that landed leaf is validated on its own re-derived boundaries.
		final Set<Object> dirtyScope = transactionalLayer.getDirtyScopeTokens(this);
		if (!dirtyScope.isEmpty()) {
			merged.validateDirtyScope(dirtyScope);
		}
		return merged;
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
	Cursor createCursor(long key) {
		final ArrayList<CursorLevel> path = new ArrayList<>(estimatedPathLength());
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
	 * Allocation-free leaf descent for READ-ONLY lookups: walks the root-to-leaf spine choosing each child by the same
	 * {@link BPlusInternalTreeNode#searchIndex} rule {@link #addCursorLevels} uses, but WITHOUT capturing the cursor
	 * path (no {@link CursorLevel} list, no backing array, no {@link Cursor}). It reads the transaction-aware
	 * `getChildren()` accessor exactly like the cursor descent, so it resolves the same nodes.
	 *
	 * Measured on this family, a captured path costs ~208 B per descent against ~0 B here, so every lookup that uses
	 * nothing but {@code cursor.leafNode()} takes this route. Structural operations (splits, deletes, consolidation,
	 * parent-key updates) mutate the captured path and must keep using {@link #createCursor(long)}.
	 *
	 * @param key the key whose responsible leaf is located
	 * @return the leaf node that should hold the key (it may not actually contain it)
	 */
	@Nonnull
	BPlusLeafTreeNode<V> findLeafNode(long key) {
		BPlusTreeNode<?> node = this.getRoot();
		while (node instanceof BPlusInternalTreeNode internalNode) {
			node = internalNode.getChildren()[internalNode.searchIndex(key)];
		}
		//noinspection unchecked
		return (BPlusLeafTreeNode<V>) node;
	}

	/**
	 * Splits a full leaf node into two leaf nodes to maintain the properties of the B+ tree.
	 * If the split occurs at the root, a new root is created.
	 *
	 * @param leaf   The leaf node to be split
	 * @param cursor The cursor representing the path from the root to the leaf node
	 */
	private void splitLeafNode(
		@Nonnull BPlusLeafTreeNode<V> leaf,
		@Nonnull Cursor cursor
	) {
		final int mid = this.valueBlockSize / 2;
		final long[] originKeys = leaf.getKeys();
		final V[] originValues = leaf.getValues();

		// structural assert: the split partitions a sorted leaf into a left half [0, mid) and a right half
		// [mid, length); the left leaf's last key must sort strictly before the right leaf's first key, and the
		// promoted separator is exactly that right first key. A violation means the leaf being split was already
		// out of order — fail fast rather than persist two overlapping pages.
		if (originKeys[mid - 1] >= originKeys[mid]) {
			throw new GenericEvitaInternalError(
				"Corrupted in-memory B+ tree: splitting a leaf produced overlapping halves — the left half's last " +
					"key (" + originKeys[mid - 1] + ") does not sort before the right half's first key (" +
					originKeys[mid] + "). The leaf being split was already out of order. Restore the catalog from a " +
					"backup, or fully rebuild / reindex the affected catalog."
			);
		}

		// Move half the keys to the new arrays of the left leaf node
		//noinspection unchecked
		final BPlusLeafTreeNode<V> leftLeaf = new BPlusLeafTreeNode<>(
			originKeys,
			originValues,
			new long[mid],
			(V[]) Array.newInstance(this.valueType, mid),
			this.valueBlockSize,
			0,
			mid,
			// nodes created during a split must participate in the transactional layer so their
			// in-savepoint mutations are captured by the per-entity savepoint and can be rolled back
			true,
			this.transactionalLayerWrapper
		);

		// Move the other half into FRESH arrays of the right leaf node — the former leaf's arrays must stay intact so a
		// per-entity savepoint rollback can restore the pre-split leaf; compacting them in place would
		// corrupt that snapshot
		//noinspection unchecked
		final BPlusLeafTreeNode<V> rightLeaf = new BPlusLeafTreeNode<>(
			originKeys,
			originValues,
			new long[this.valueBlockSize - mid],
			(V[]) Array.newInstance(this.valueType, this.valueBlockSize - mid),
			this.valueBlockSize,
			mid,
			// the LOGICAL capacity, which a split always finds equal to the origin's live count because a leaf only
			// splits when it is full. It must never become a backing array's physical length: now that the halves are
			// sized to their content, `end` would collapse to `mid`, the right leaf would copy the empty range
			// [mid, mid) and half the leaf would vanish with no exception and no failing assert — silent data loss
			this.valueBlockSize,
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
		// a split creates a brand-new adjacent leaf pair with a fresh separator between them — register both halves'
		// current boundary keys as dirty-scope probe keys for this transaction
		registerDirtyLeafInScope(leftLeaf);
		registerDirtyLeafInScope(rightLeaf);
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
		long key,
		@Nonnull CursorWithLevel cursor
	) {
		final BPlusInternalTreeNode parent = cursor.parent();

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
		// The node is full at split time (the only caller guards with isFull()), so occupancy equals capacity. Derive the
		// midpoint from the actual key count rather than valueBlockSize: internal nodes are sized by internalNodeBlockSize,
		// and spines bulk-assembled from persisted pages carry that (smaller) capacity, so a valueBlockSize-derived midpoint
		// overruns their arrays. keyCount is the number of separator keys (children = keyCount + 1).
		final int keyCount = internal.keyCount();
		final int mid = (keyCount + 1) / 2;
		final long[] originKeys = internal.getKeys();
		final BPlusTreeNode<?>[] originChildren = internal.getChildren();

		// Move half the keys to the new arrays of the left leaf node — the split constructor always allocates fresh
		// arrays, so the former node's arrays stay intact for a per-entity savepoint rollback. The new
		// nodes participate in the transactional layer so their in-savepoint mutations are captured.
		final BPlusInternalTreeNode leftInternal = new BPlusInternalTreeNode(
			originKeys,
			originChildren,
			0,
			mid - 1,
			0,
			mid,
			true
		);

		// Move the other half to the start of existing arrays of former leaf in the right leaf node. End bounds are the
		// origin's actual occupancy (keyCount separators, keyCount + 1 children), not the array capacity — capacity may
		// exceed occupancy after the internalNodeBlockSize sizing fix, and only the live range must be copied.
		final BPlusInternalTreeNode rightInternal = new BPlusInternalTreeNode(
			originKeys,
			originChildren,
			mid,
			keyCount,
			mid,
			keyCount + 1,
			true
		);

		// remove changes of the previous root - it gets replaced
		if (Transaction.getTransactionalMemoryLayerIfExists(internal) != null) {
			internal.removeLayer();
		}

		// if the root splits, create a new root
		if (internal == this.getRoot()) {
			this.setRoot(
				new BPlusInternalTreeNode(
					this.internalNodeBlockSize,
					rightInternal.getLeftBoundaryKey(),
					leftInternal, rightInternal,
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
	 * Per-tree typed marker that exposes the primitive `long` key accessors common to both node kinds, kept off the
	 * key-agnostic {@link BPlusTreeNode} SPI so the shared base never sees (and never boxes) a key. Typed call sites
	 * that hold only a {@link BPlusTreeNode} reference (e.g. a children-array element) cast to this to read the keys.
	 */
	interface LongKeyedNode {

		/**
		 * Retrieves an array of long keys associated with the node.
		 *
		 * @return an array of long keys present in the node. The array is guaranteed to be non-null.
		 */
		@Nonnull
		long[] getKeys();

		/**
		 * Retrieves the left boundary (smallest) key contained within the node.
		 *
		 * @return the left boundary key of the node.
		 */
		long getLeftBoundaryKey();

	}

	/**
	 * Internal node implementation of the B+ tree that holds keys and child node pointers. Internal nodes serve
	 * as routing nodes — they do not store values directly but guide searches to the appropriate leaf nodes.
	 */
	static class BPlusInternalTreeNode implements
		InternalBPlusTreeNode<BPlusInternalTreeNode>,
		LongKeyedNode,
		Snapshotable<BPlusInternalTreeNode.BPlusInternalNodeMemento> {
		@Serial private static final long serialVersionUID = -7649742437563558158L;
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
		private long[] keys;

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
			long key,
			@Nonnull BPlusTreeNode<?> leftLeaf,
			@Nonnull BPlusTreeNode<?> rightLeaf,
			boolean transactionalLayer
		) {
			this.keys = new long[blockSize];
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
			@Nonnull long[] originKeys,
			@Nonnull BPlusTreeNode<?>[] originChildren,
			int keyStart, int keyEnd,
			int childrenStart, int childrenEnd,
			boolean transactionalLayer
		) {
			// we always create a new array for keys and children
			this.keys = new long[originKeys.length];
			this.children = new BPlusTreeNode[originChildren.length];
			// Copy the keys and children from the origin arrays
			System.arraycopy(originKeys, keyStart, this.keys, 0, keyEnd - keyStart);
			System.arraycopy(originChildren, childrenStart, this.children, 0, childrenEnd - childrenStart);
			this.peek = childrenEnd - childrenStart - 1;
			this.transactionalLayer = transactionalLayer;
		}

		private BPlusInternalTreeNode(
			@Nonnull long[] originKeys,
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
		public long[] getKeys() {
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
					Arrays.fill(this.keys, Math.max(0, peek), originPeek, 0L);
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
						layer.keys = new long[this.keys.length];
						System.arraycopy(this.keys, 0, layer.keys, 0, originPeek);
					} else {
						Arrays.fill(layer.keys, Math.max(0, peek), originPeek, 0L);
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

		/**
		 * Returns the heap this node and the whole subtree beneath it occupy, in bytes.
		 *
		 * Both backing arrays are charged at their **allocated** length. The separator `keys` are `long` values
		 * rather than boxed objects here, so — unlike the bucket tree — there is nothing in an internal node for
		 * the element sizer to price. Children carried over unchanged from a superseded version are charged in
		 * full: the predecessor is garbage-in-waiting and this version becomes their sole owner.
		 *
		 * @param elementSizer prices one stored value; passed through to the leaves
		 * @return the owned heap footprint of this subtree in bytes
		 */
		@Override
		public long getHeapSizeInBytes(@Nonnull ToLongFunction<Object> elementSizer) {
			final VMLayout layout = VMLayout.current();
			// id + transactionalLayer + keys/children slots + peek
			long size = layout.sizeOfObject(Long.BYTES + 1L + 2L * layout.referenceSize() + Integer.BYTES);
			size += layout.sizeOfArray(this.keys.length, Long.BYTES);
			size += layout.sizeOfArray(this.children.length, layout.referenceSize());
			// THIS instance's own count, deliberately not `keyCount()`: that accessor resolves the calling thread's
			// transactional layer, which is a separate node object owning a separate `children` array
			// `peek` is the last occupied index, so the counts below are peek and peek+1 - and NOT clamped at zero:
			// a node emptied by a merge carries peek == -1 with `children[0]` already nulled, and clamping would
			// walk that slot
			final int childCount = this.peek + 1;
			for (int i = 0; i < childCount; i++) {
				size += this.children[i].getHeapSizeInBytes(elementSizer);
			}
			return size;
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
			final long[] theKeys;
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
				final long key = theKeys[i - 1];
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
				// decouple them first (...ForUpdate) or it would corrupt the shared committed state. The ...ForUpdate
				// accessors decouple a committed sibling inside a transaction and are in-place no-ops outside one.
				final BPlusTreeNode<?>[] nextNodeChildren = nextNode.getChildrenForUpdate();
				System.arraycopy(nextNodeChildren, 0, this.children, this.peek + 1, numberOfHeadValues);
				System.arraycopy(
					nextNodeChildren, numberOfHeadValues, nextNodeChildren, 0, nextNode.size() - numberOfHeadValues);

				// set the key for the first child of the next node
				this.keys[this.peek] = leftBoundaryKeyOf(this.children[this.peek + 1]);

				// we move the keys from the next node for all copied children
				final long[] nextNodeKeys = nextNode.getKeysForUpdate();
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
				final long[] nextNodeKeysForUpdate = nextNode.getKeysForUpdate();
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

		public long getLeftBoundaryKey() {
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
		public long[] getKeysForUpdate() {
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
					layer.keys = new long[this.keys.length];
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
		 * @param key      The long key to be inserted into the B+ Tree node.
		 * @param original The original B+ Tree node that is the child of the internal node. This node is being split into two nodes.
		 * @param left     The left child BPlusTreeNode resulting from the split, containing keys less than the inserted key.
		 * @param right    The right child BPlusTreeNode resulting from the split, containing keys greater than the inserted key.
		 */
		public void adaptToLeafSplit(
			long key,
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
				final InsertionPosition insertionPosition = computeInsertPositionOfLongInOrderedArray(
					key, this.keys, 0, this.peek);
				Assert.isPremiseValid(
					original == this.children[insertionPosition.position()],
					"Original node must be the child of the internal node!"
				);
				Assert.isPremiseValid(
					!insertionPosition.alreadyPresent(),
					"Key already present in the internal node!"
				);

				insertLongIntoSameArrayOnIndex(key, this.keys, insertionPosition.position());
				this.children[insertionPosition.position()] = left;
				insertRecordIntoSameArrayOnIndex(right, this.children, insertionPosition.position() + 1);
				this.peek++;
			} else {
				decoupleTransactionalArrays();

				// the peek relates to children, which are one more than keys, that's why we don't use peek + 1, but mere peek
				final InsertionPosition insertionPosition = computeInsertPositionOfLongInOrderedArray(
					key, layer.keys, 0, layer.peek);
				Assert.isPremiseValid(
					original == layer.children[insertionPosition.position()],
					"Original node must be the child of the internal node!"
				);
				Assert.isPremiseValid(
					!insertionPosition.alreadyPresent(),
					"Key already present in the internal node!"
				);

				insertLongIntoSameArrayOnIndex(key, layer.keys, insertionPosition.position());
				layer.children[insertionPosition.position()] = left;
				insertRecordIntoSameArrayOnIndex(right, layer.children, insertionPosition.position() + 1);
				layer.peek++;
			}
		}

		/**
		 * Searches for the child index that should contain the given key.
		 * This method avoids allocating a NodeWithIndex record.
		 *
		 * @param key the long key to search for within the B+ Tree.
		 * @return the index of the child that should contain the specified key.
		 */
		public int searchIndex(long key) {
			final BPlusInternalTreeNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) :
				null;
			if (layer == null) {
				final InsertionPosition insertionPosition = computeInsertPositionOfLongInOrderedArray(
					key, this.keys, 0, this.peek);
				return insertionPosition.alreadyPresent() ?
					insertionPosition.position() + 1 : insertionPosition.position();
			} else {
				final InsertionPosition insertionPosition = computeInsertPositionOfLongInOrderedArray(
					key, layer.keys, 0, layer.peek);
				return insertionPosition.alreadyPresent() ?
					insertionPosition.position() + 1 : insertionPosition.position();
			}
		}

		/**
		 * Searches for the BPlusTreeNode that should contain the given key.
		 *
		 * @param key the long key to search for within the B+ Tree.
		 * @return the BPlusTreeNode that should contain the specified key.
		 */
		@Nonnull
		public NodeWithIndex search(long key) {
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
				removeLongFromSameArrayOnIndex(this.keys, keyIndex);
				this.keys[this.peek - 1] = 0L;
				removeRecordFromSameArrayOnIndex(this.children, childIndex);
				this.children[this.peek] = null;
				this.peek--;
			} else {
				decoupleTransactionalArrays();

				removeLongFromSameArrayOnIndex(layer.keys, keyIndex);
				layer.keys[layer.peek - 1] = 0L;

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
				// separator-order belt: the rewritten separator must keep strict local order
				assertSeparatorOrder(this.keys, this.peek, index - 1);
			} else {
				decoupleTransactionalArrays();
				Assert.isPremiseValid(
					layer.children[index] == node,
					"Node to update key for must match the child node at the specified index!"
				);
				layer.keys[index - 1] = leftBoundaryKeyOf(node);
				assertSeparatorOrder(layer.keys, layer.peek, index - 1);
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

		/**
		 * Captures this layer's revertable copy-on-write state for a per-entity savepoint. Only the keys
		 * and children arrays and the peek index are mutable here; both arrays are cloned (shallow — the primitive keys
		 * are value types and the child nodes own their own transactional layers and are snapshotted independently) so
		 * that a later mutation, or a repeated {@link #restore}, cannot corrupt the memento.
		 *
		 * @return an independent snapshot of this internal node's array structure
		 */
		@Nonnull
		@Override
		public BPlusInternalNodeMemento snapshot() {
			return new BPlusInternalNodeMemento(this.keys.clone(), this.children.clone(), this.peek);
		}

		/**
		 * Restores the array structure captured by {@link #snapshot}. Fresh clones of the memento's arrays are installed
		 * so the memento stays reusable for a repeated restore.
		 *
		 * @param memento the state previously captured by {@link #snapshot}
		 */
		@Override
		public void restore(@Nonnull BPlusInternalNodeMemento memento) {
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
		public BPlusInternalTreeNode createCopyWithMergedTransactionalMemory(
			@Nullable BPlusInternalTreeNode layer,
			@Nonnull TransactionalLayerMaintainer transactionalLayer
		) {
			final long[] theKeys;
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
					layer.keys = new long[this.keys.length];
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
		 * clones owned by the memento (see {@link #snapshot}); the primitive keys and child-node references they hold
		 * are shared by design.
		 *
		 * @param keys     clone of the separator-key array
		 * @param children clone of the child-pointer array
		 * @param peek     the last occupied child index
		 */
		record BPlusInternalNodeMemento(
			@Nonnull long[] keys,
			@Nonnull BPlusTreeNode<?>[] children,
			int peek
		) {
		}

	}

	/**
	 * Leaf node implementation of the B+ tree that stores key-value pairs. Leaf nodes hold all actual data
	 * in the tree and are the terminal nodes in the B+ tree structure.
	 */
	static class BPlusLeafTreeNode<V> implements
		LeafBPlusTreeNode<BPlusLeafTreeNode<V>>,
		LongKeyedNode,
		Snapshotable<BPlusLeafTreeNode.BPlusLeafNodeMemento<V>> {
		@Serial private static final long serialVersionUID = 5744347408875846161L;
		@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
		/**
		 * Indicates whether this instance is permitted to create and use transactional layers. The tree nodes use
		 * themselves (the same class) as their transactional memory layer, and if this layer were to also use
		 * transactional memory, it would create an infinite loop. This flag prevents that behavior.
		 */
		private final boolean transactionalLayer;
		/**
		 * The function to wrap the values into a transactional layer.
		 */
		@Nullable private final Function<Object, V> transactionalLayerWrapper;
		/**
		 * The keys stored in this node.
		 */
		private long[] keys;
		/**
		 * The values stored in this node. Index i corresponds to the value associated with key i.
		 */
		private V[] values;
		/**
		 * The **logical** capacity — the leaf block size this node was created with, which no mutation ever
		 * changes. {@link #keys} and {@link #values} are sized to the live content instead and grow towards this
		 * bound, so the two numbers are equal only in a leaf that is actually full.
		 *
		 * Everything asking "may one more key go in here" — {@link #isFull()}, {@link #isNearlyFull()}, the insert
		 * premise — reads THIS. Everything indexing an array reads that array's own length.
		 */
		private final int capacity;
		/**
		 * Index of the last occupied position in the keys array.
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
		 * its page. Routed through the leaf's transactional layer (like the columns) so a change made inside a transaction
		 * is visible at flush yet isolated from concurrent readers and discarded on abort. A committed leaf the merge
		 * produces defaults to clean; the emitter clears the flag once it has collected the page. It is the deterministic
		 * replacement for a content hash — every mutation site sets it, so a real change can never be suppressed.
		 */
		private boolean dirty = false;

		/**
		 * Creates a new empty leaf node with the specified block size.
		 *
		 * @param blockSize                 the maximum number of key-value pairs this leaf node can hold
		 * @param valueType                 the class of the values stored in this node
		 * @param transactionalLayerWrapper optional function to wrap values into a transactional layer
		 * @param transactionalLayer        whether this node participates in the transactional memory layer
		 */
		public BPlusLeafTreeNode(
			int blockSize,
			@Nonnull Class<V> valueType,
			@Nullable Function<Object, V> transactionalLayerWrapper,
			boolean transactionalLayer
		) {
			this.capacity = blockSize;
			// an empty leaf allocates nothing — the arrays start at ColumnSizing.MIN_PHYSICAL_LENGTH on the first insert
			this.keys = EMPTY_LONG_ARRAY;
			//noinspection unchecked
			this.values = (V[]) Array.newInstance(valueType, 0);
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
		 * @param capacity                  the node's logical capacity — the leaf block size. The target arrays are
		 *                                  NOT required to match it; they are sized to the range being copied
		 * @param start                     the start index (inclusive) in the origin arrays
		 * @param end                       the end index (exclusive) in the origin arrays
		 * @param transactionalLayer        whether this node participates in the transactional memory layer
		 * @param transactionalLayerWrapper optional function to wrap values into a transactional layer
		 */
		public BPlusLeafTreeNode(
			@Nonnull long[] originKeys,
			@Nonnull V[] originValues,
			@Nonnull long[] keys,
			@Nonnull V[] values,
			int capacity,
			int start, int end,
			boolean transactionalLayer,
			@Nullable Function<Object, V> transactionalLayerWrapper
		) {
			ColumnSizing.assertLoadFitsCapacity(end - start, capacity);
			this.capacity = capacity;
			this.keys = keys;
			this.values = values;
			// Copy the keys and values from the origin arrays
			System.arraycopy(originKeys, start, keys, 0, end - start);
			//noinspection ArrayEquality
			if (keys == originKeys) {
				Arrays.fill(keys, end - start, keys.length, 0L);
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
			@Nonnull long[] keys,
			@Nonnull V[] values,
			int peek,
			int capacity,
			boolean transactionalLayer,
			@Nullable Function<Object, V> transactionalLayerWrapper
		) {
			this.capacity = capacity;
			this.keys = keys;
			this.values = values;
			this.peek = peek;
			this.transactionalLayer = transactionalLayer;
			this.transactionalLayerWrapper = transactionalLayerWrapper;
		}

		/**
		 * Returns the **logical** capacity — the leaf block size this node was created with, which no mutation
		 * ever changes. See {@link #capacity}.
		 *
		 * @return the logical capacity (the leaf block size)
		 */
		int capacity() {
			return this.capacity;
		}

		/**
		 * Grows THIS node's own backing arrays so that the first `requiredLength` slots may be addressed, leaving
		 * the logical {@link #capacity} untouched.
		 *
		 * Call it on the object whose arrays are about to be written — the committed node outside a transaction,
		 * the layer inside one — and inside a transaction only after {@link #decoupleTransactionalArrays()} has
		 * given the layer arrays of its own, or the growth would copy and then abandon the shared committed arrays.
		 *
		 * Each array is grown against **its own** length rather than against the other's. The two are equal in
		 * every state this class produces, but a guard that read only one of them would silently leave the other
		 * short if they ever diverged, and the write that follows indexes both.
		 *
		 * @param requiredLength the number of slots the caller is about to address; never above {@link #capacity}
		 */
		private void ensurePhysicalLength(int requiredLength) {
			if (requiredLength > this.keys.length) {
				this.keys = Arrays.copyOf(
					this.keys, ColumnSizing.grownLength(this.keys.length, requiredLength, this.capacity));
			}
			if (requiredLength > this.values.length) {
				this.values = Arrays.copyOf(
					this.values, ColumnSizing.grownLength(this.values.length, requiredLength, this.capacity));
			}
		}

		@Nonnull
		public long[] getKeys() {
			final BPlusLeafTreeNode<V> layer = this.transactionalLayer ?
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
			final BPlusLeafTreeNode<V> layer = this.transactionalLayer ?
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
			final BPlusLeafTreeNode<V> layer = this.transactionalLayer ?
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
					Arrays.fill(this.keys, peek + 1, originPeek + 1, 0L);
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
						layer.keys = new long[this.keys.length];
						System.arraycopy(this.keys, 0, layer.keys, 0, originPeek + 1);
					} else {
						Arrays.fill(layer.keys, peek + 1, originPeek + 1, 0L);
					}
					//noinspection ArrayEquality
					if (layer.values == this.values) {
						//noinspection unchecked
						layer.values = (V[]) Array.newInstance(
							this.values.getClass().getComponentType(), this.values.length);
						System.arraycopy(this.values, 0, layer.values, 0, originPeek + 1);
					} else {
						Arrays.fill(layer.values, peek + 1, originPeek + 1, null);
					}
				}
			}
		}

		/**
		 * Returns the heap this leaf occupies, in bytes.
		 *
		 * Charges its own object and both backing arrays at their allocated length, then prices the live values
		 * through `elementSizer`. The values are genuine objects — for a {@link io.evitadb.index.range.RangeIndex}
		 * they are its range points — so unlike the primitive columns this leaf really can own a payload, and
		 * whether it does is the caller's policy rather than this leaf's. `transactionalLayerWrapper` is a lambda
		 * every node of the tree receives, so only its slot is charged.
		 *
		 * @param elementSizer prices one stored value; must return `0` for values this tree does not own
		 * @return the owned heap footprint of this leaf in bytes
		 */
		@Override
		public long getHeapSizeInBytes(@Nonnull ToLongFunction<Object> elementSizer) {
			final VMLayout layout = VMLayout.current();
			// id + transactionalLayer + dirty + wrapper/keys/values slots + peek + pageSequence + capacity
			long size = layout.sizeOfObject(Long.BYTES + 2L + 3L * layout.referenceSize() + 3L * Integer.BYTES);
			// an empty leaf parks its keys on the JVM-wide shared empty array, which costs it nothing beyond the slot
			// above — the same policy the ValueColumn family applies, and the same one every heap walk subtracts. The
			// value array is always privately owned (there is no shared empty of an arbitrary component type), so it is
			// charged unconditionally, at a zero length while the leaf is empty
			// both arrays are read ONCE into locals and everything below prices exactly what was read. This walk is
			// reached from a management thread with no happens-before edge to a warm-up writer, so re-reading the
			// field per element could pair a length taken from one array with a reference taken from its successor
			final long[] theKeys = this.keys;
			final V[] theValues = this.values;
			if (theKeys != EMPTY_LONG_ARRAY) {
				size += layout.sizeOfArray(theKeys.length, Long.BYTES);
			}
			size += layout.sizeOfArray(theValues.length, layout.referenceSize());
			// THIS instance's own count, deliberately not `keyCount()`: that accessor resolves the calling thread's
			// transactional layer, which is a separate node object owning a separate `values` array. Clamped to the
			// arrays just read, because a torn reader can hold a peek the growth behind it has not published yet
			final int bound = observableLeafPeek(this.peek, theKeys, theValues);
			for (int i = 0; i <= bound; i++) {
				final V value = theValues[i];
				if (value != null) {
					size += elementSizer.applyAsLong(value);
				}
			}
			return size;
		}

		@Override
		public int keyCount() {
			final BPlusLeafTreeNode<V> layer = this.transactionalLayer ?
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
			final BPlusLeafTreeNode<V> layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) :
				null;
			if (layer == null) {
				return this.peek == this.capacity - 1;
			} else {
				return layer.peek == layer.capacity - 1;
			}
		}

		/**
		 * Whether a single insert of a **new** key could make this leaf {@link #isFull()} — i.e. whether the caller
		 * must capture a cursor path before mutating, so a split has one.
		 *
		 * Deliberately mirrors {@link #isFull()}: it reads `peek` and {@link #capacity} from the **same** resolved
		 * state, so the two can never disagree. Both read the LOGICAL capacity and never a backing array's length —
		 * the arrays are sized to the live content and grow towards the capacity, so an array's length says nothing
		 * about how many more keys this leaf may still accept.
		 *
		 * @return true when one more key could fill this leaf
		 */
		public boolean isNearlyFull() {
			final BPlusLeafTreeNode<V> layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) :
				null;
			if (layer == null) {
				return this.peek >= this.capacity - 2;
			} else {
				return layer.peek >= layer.capacity - 2;
			}
		}

		@Override
		public void toVerboseString(@Nonnull StringBuilder sb, int level, int indentSpaces) {
			sb.append(" ".repeat(level * indentSpaces));
			final long[] theKeys;
			final V[] theValues;
			final int rawPeek;

			final BPlusLeafTreeNode<V> layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) :
				null;
			if (layer == null) {
				theKeys = this.keys;
				theValues = this.values;
				rawPeek = this.peek;
			} else {
				theKeys = layer.keys;
				theValues = layer.values;
				rawPeek = layer.peek;
			}
			// bounded like every other reader, and this one especially: a debugger or a log statement is exactly how a
			// live tree gets read from a thread that never wrote to it, and an out-of-bounds thrown out of a toString
			// would break the diagnostics being used to investigate
			final int thePeek = observableLeafPeek(rawPeek, theKeys, theValues);

			for (int i = 0; i <= thePeek; i++) {
				sb.append(theKeys[i]).append(":").append(theValues[i]);
				if (i < thePeek) {
					sb.append(", ");
				}
			}
		}

		@Override
		public void stealFromLeft(int numberOfTailValues, @Nonnull BPlusLeafTreeNode<V> previousNode) {
			Assert.isPremiseValid(numberOfTailValues > 0, "Number of tail values to steal must be positive!");
			final BPlusLeafTreeNode<V> layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			// the receiving leaf's page changes; the donor is flagged via its own setPeek below
			if (layer == null) {
				this.dirty = true;
			} else {
				layer.dirty = true;
			}
			if (layer == null) {
				ensurePhysicalLength(this.peek + 1 + numberOfTailValues);
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

				layer.ensurePhysicalLength(layer.peek + 1 + numberOfTailValues);
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
		public void stealFromRight(int numberOfHeadValues, @Nonnull BPlusLeafTreeNode<V> nextNode) {
			Assert.isPremiseValid(numberOfHeadValues > 0, "Number of head values to steal must be positive!");

			final BPlusLeafTreeNode<V> layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			// the receiving leaf's page changes; the donor is flagged via its own setPeek below
			if (layer == null) {
				this.dirty = true;
			} else {
				layer.dirty = true;
			}
			if (layer == null) {
				// the right sibling may be a committed (shared) node while `this` is a transaction-local node
				// (transactionalLayer == false): steal-from-right SHIFTS the sibling's arrays in place, so it must
				// decouple them first (...ForUpdate) or it would corrupt the shared committed state. The ...ForUpdate
				// accessors decouple a committed sibling inside a transaction and are in-place no-ops outside one.
				ensurePhysicalLength(this.peek + 1 + numberOfHeadValues);
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

				layer.ensurePhysicalLength(layer.peek + 1 + numberOfHeadValues);
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
		public void mergeWithLeft(@Nonnull BPlusLeafTreeNode<V> previousNode) {
			final int mergePeek = previousNode.getPeek();
			final BPlusLeafTreeNode<V> layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			// the surviving (receiving) leaf's page changes; the emptied donor is flagged via its own setPeek(-1) below
			if (layer == null) {
				this.dirty = true;
			} else {
				layer.dirty = true;
			}
			if (layer == null) {
				ensurePhysicalLength(this.peek + 1 + mergePeek + 1);
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

				layer.ensurePhysicalLength(layer.peek + 1 + mergePeek + 1);
				System.arraycopy(layer.keys, 0, layer.keys, mergePeek + 1, layer.peek + 1);
				System.arraycopy(layer.values, 0, layer.values, mergePeek + 1, layer.peek + 1);
				System.arraycopy(previousNode.getKeysForUpdate(), 0, layer.keys, 0, mergePeek + 1);
				System.arraycopy(previousNode.getValuesForUpdate(), 0, layer.values, 0, mergePeek + 1);
				layer.peek += mergePeek + 1;
				previousNode.setPeek(-1);
			}
		}

		@Override
		public void mergeWithRight(@Nonnull BPlusLeafTreeNode<V> nextNode) {
			final int mergePeek = nextNode.getPeek();
			final BPlusLeafTreeNode<V> layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			// the surviving (receiving) leaf's page changes; the emptied donor is flagged via its own setPeek(-1) below
			if (layer == null) {
				this.dirty = true;
			} else {
				layer.dirty = true;
			}
			if (layer == null) {
				ensurePhysicalLength(this.peek + 1 + mergePeek + 1);
				System.arraycopy(nextNode.getKeys(), 0, this.keys, this.peek + 1, mergePeek + 1);
				System.arraycopy(nextNode.getValues(), 0, this.values, this.peek + 1, mergePeek + 1);
				this.peek += mergePeek + 1;
				nextNode.setPeek(-1);
			} else {
				// we need to decouple the arrays before modifying them
				decoupleTransactionalArrays();
				nextNode.decoupleTransactionalArrays();

				layer.ensurePhysicalLength(layer.peek + 1 + mergePeek + 1);
				System.arraycopy(nextNode.getKeysForUpdate(), 0, layer.keys, layer.peek + 1, mergePeek + 1);
				System.arraycopy(nextNode.getValuesForUpdate(), 0, layer.values, layer.peek + 1, mergePeek + 1);
				layer.peek += mergePeek + 1;
				nextNode.setPeek(-1);
			}
		}

		public long getLeftBoundaryKey() {
			final BPlusLeafTreeNode<V> layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) :
				null;
			if (layer == null) {
				return this.keys[0];
			} else {
				return layer.keys[0];
			}
		}

		/**
		 * Returns this leaf's logical persistence page sequence, or
		 * {@link AbstractTransactionalBPlusTree#UNASSIGNED_PAGE_SEQUENCE} when none has been assigned yet (a split-born or
		 * freshly created leaf). NOT transactional — see {@link #pageSequence}.
		 *
		 * @return the assigned page sequence, or {@link AbstractTransactionalBPlusTree#UNASSIGNED_PAGE_SEQUENCE}
		 */
		@Override
		public int getPageSequence() {
			return this.pageSequence;
		}

		/**
		 * Stamps this leaf's logical persistence page sequence. Direct (non-transactional) write — see {@link #pageSequence}
		 *.
		 *
		 * @param pageSequence the page sequence to assign
		 */
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
		@Override
		public boolean isDirty() {
			final BPlusLeafTreeNode<V> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
			return layer == null ? this.dirty : layer.dirty;
		}

		/**
		 * Marks the leaf dirty, transaction-aware: sets the flag on the transaction's layer (creating it) when running
		 * inside a transaction, otherwise on the committed instance in place (the warm-up bulk path). Used by the tree
		 * when a stored value's content is mutated out-of-band (the value object itself changes while the leaf's columns
		 * do not — e.g. a range point's record set), which the per-method mutation marks would otherwise miss. See
		 * {@link #dirty}.
		 */
		void markDirty() {
			final BPlusLeafTreeNode<V> layer = this.transactionalLayer
				? Transaction.getOrCreateTransactionalMemoryLayer(this)
				: null;
			if (layer == null) {
				this.dirty = true;
			} else {
				layer.dirty = true;
			}
		}

		/**
		 * Clears the change-detection flag once the emitter has collected this leaf's page for the current flush (or
		 * after a boundary-stable reload reconstructs an already-persisted leaf). Transaction-aware: clears the layer's
		 * flag in a transaction (the merge produces a clean committed instance regardless), otherwise the committed
		 * instance in place. See {@link #dirty}.
		 */
		@Override
		public void clearDirty() {
			final BPlusLeafTreeNode<V> layer = this.transactionalLayer
				? Transaction.getTransactionalMemoryLayerIfExists(this)
				: null;
			if (layer == null) {
				this.dirty = false;
			} else {
				layer.dirty = false;
			}
		}

		/**
		 * Retrieves the keys of the current node for updating. If a transactional layer is active, it ensures
		 * that updates are performed on an independent copy of the keys array within the transactional layer.
		 *
		 * @return an array of integers representing the keys of the current node, adjusted for the transactional layer if applicable.
		 */
		@Nonnull
		public long[] getKeysForUpdate() {
			final BPlusLeafTreeNode<V> layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			if (layer == null) {
				return this.keys;
			} else {
				// internal arrays may have been still identical to the original arrays
				// we need to copy them in the transactional layer, before modifying

				//noinspection ArrayEquality
				if (layer.keys == this.keys && this.keys.length > 0) {
					// length 0 is the shared empty array — see `decoupleTransactionalArrays`
					layer.keys = new long[this.keys.length];
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
		public V[] getValues() {
			final BPlusLeafTreeNode<V> layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) :
				null;
			if (layer == null) {
				return this.values;
			} else {
				return layer.values;
			}
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
		 * Retrieves the values of the current node for updating. If a transactional layer is active, it ensures
		 * that updates are performed on an independent copy of the values array within the transactional layer.
		 *
		 * @return an array of values representing the values of the current node, adjusted for the transactional layer if applicable.
		 */
		@Nonnull
		public V[] getValuesForUpdate() {
			final BPlusLeafTreeNode<V> layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			if (layer == null) {
				return this.values;
			} else {
				// internal arrays may have been still identical to the original arrays
				// we need to copy them in the transactional layer, before modifying

				//noinspection ArrayEquality
				if (layer.values == this.values && this.values.length > 0) {
					// length 0 carries nothing to decouple — the first write allocates through `ensurePhysicalLength`
					//noinspection unchecked
					layer.values = (V[]) Array.newInstance(
						this.values.getClass().getComponentType(), this.values.length);
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
		public Optional<V> getValue(long key) {
			// `ofNullable` rather than `of`, and equivalent to it ONLY because no null can reach a value slot: `insert`
			// takes a `@Nonnull V` and `upsert` refuses an updater that returns one. Both doors have to stay shut - the
			// moment one stored null gets in, this line reports a key the tree DOES hold as absent, silently.
			return Optional.ofNullable(valueOrNull(key));
		}

		/**
		 * The same lookup as {@link #getValue(long)}, answering with `null` instead of an empty {@link Optional}.
		 *
		 * For hot lookups that immediately unwrap the result. The Optional is a per-call allocation that escape
		 * analysis is not guaranteed to remove across the polymorphic descent that reaches this node, and a lookup
		 * repeated once per trigram of a search pattern pays for it every time.
		 *
		 * @param key the key to search for in the leaf node
		 * @return the value stored under the key, or `null` when the leaf does not hold it
		 */
		@Nullable
		public V valueOrNull(long key) {
			final long[] theKeys;
			final V[] theValues;
			final int thePeek;

			final BPlusLeafTreeNode<V> layer = this.transactionalLayer ?
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

			// the three fields above are three separate reads, so an unsynchronized reader can hold a peek that
			// belongs to a later growth than the arrays beside it; bound the search by what it actually holds
			final InsertionPosition insertionPosition = computeInsertPositionOfLongInOrderedArray(
				key, theKeys, 0, observableLeafPeek(thePeek, theKeys, theValues) + 1);
			return insertionPosition.alreadyPresent() ? theValues[insertionPosition.position()] : null;
		}

		/**
		 * Searches for the index of a value in the node's key-value pairs by the specified key.
		 * Returns the index of the key if found, or -1 if the key is not present.
		 *
		 * @param key the key to search for in the leaf node
		 * @return the index of the key in the keys/values arrays if found; -1 otherwise
		 */
		public int getValueIndex(long key) {
			final long[] theKeys;
			final V[] theValues;
			final int thePeek;

			final BPlusLeafTreeNode<V> layer = this.transactionalLayer ?
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

			// the value array is read and bounded against even though only the keys are searched: the index this
			// returns is a slot number every caller then addresses on the values, so handing back one the values
			// cannot carry would only move the out-of-bounds to the caller
			final InsertionPosition insertionPosition = computeInsertPositionOfLongInOrderedArray(
				key, theKeys, 0, observableLeafPeek(thePeek, theKeys, theValues) + 1);
			return insertionPosition.alreadyPresent() ? insertionPosition.position() : -1;
		}

		/**
		 * Resolves the insertion position of the given key in this leaf's (transaction-aware) key array. Used by the
		 * keyed-start iterators, which need only the primitive position and present flag.
		 *
		 * It exists so those iterators cannot compute that position from a `getKeys()` and a `size()` resolved
		 * independently of each other — the shape {@link #observableLeafPeek} was written to close, and the one shape
		 * a keyed iterator could not delegate to {@code loadCurrentLeaf()}, because Java requires the position before
		 * the {@code super(...)} that would run it. The sibling {@code TransactionalElementBPlusTree} carries a method
		 * of the same name for the same reason.
		 *
		 * @param key the key to locate
		 * @return the insertion position of the key within this leaf, bounded by the arrays it was read from
		 */
		@Nonnull
		public InsertionPosition findKeyPosition(long key) {
			final long[] theKeys;
			final V[] theValues;
			final int thePeek;

			final BPlusLeafTreeNode<V> layer = this.transactionalLayer ?
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

			return computeInsertPositionOfLongInOrderedArray(
				key, theKeys, 0, observableLeafPeek(thePeek, theKeys, theValues) + 1);
		}

		@Override
		public String toString() {
			final StringBuilder sb = new StringBuilder(64);
			toVerboseString(sb, 0, 3);
			return sb.toString();
		}

		@Override
		public BPlusLeafTreeNode<V> createLayer() {
			return new BPlusLeafTreeNode<>(
				this.keys,
				this.values,
				this.keys,
				this.values,
				this.capacity,
				0,
				this.peek + 1,
				false,
				this.transactionalLayerWrapper
			);
		}

		/**
		 * Captures this layer's revertable copy-on-write state for a per-entity savepoint. Both the key
		 * and value arrays are cloned (shallow — the primitive keys are value types and the values either are immutable
		 * or own their own transactional layers and are snapshotted independently) so that a later mutation, or a
		 * repeated {@link #restore}, cannot corrupt the memento.
		 *
		 * @return an independent snapshot of this leaf's two arrays and peek
		 */
		@Nonnull
		@Override
		public BPlusLeafNodeMemento<V> snapshot() {
			return new BPlusLeafNodeMemento<>(this.keys.clone(), this.values.clone(), this.peek);
		}

		/**
		 * Restores the array state captured by {@link #snapshot}. Fresh clones of the memento's arrays are installed so
		 * the memento stays reusable for a repeated restore.
		 *
		 * @param memento the state previously captured by {@link #snapshot}
		 */
		@Override
		public void restore(@Nonnull BPlusLeafNodeMemento<V> memento) {
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
		public BPlusLeafTreeNode<V> createCopyWithMergedTransactionalMemory(
			@Nullable BPlusLeafTreeNode<V> layer,
			@Nonnull TransactionalLayerMaintainer transactionalLayer
		) {
			final long[] theKeys;
			final V[] theValues;
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

			V[] newValues = null;
			if (TransactionalStateProducer.class.isAssignableFrom(this.values.getClass().getComponentType())) {
				for (int i = 0; i < thePeek + 1; i++) {
					// this.transactionalLayerWrapper is not null, because the values are transactional layers
					//noinspection unchecked,DataFlowIssue
					final V value = this.transactionalLayerWrapper.apply(
						transactionalLayer.getStateCopyWithCommittedChanges(
							(TransactionalStateProducer<? extends V>) theValues[i]
						)
					);
					if (newValues == null && value != theValues[i]) {
						//noinspection unchecked
						newValues = (V[]) Array.newInstance(
							this.values.getClass().getComponentType(), theValues.length);
						System.arraycopy(theValues, 0, newValues, 0, i);
					}
					if (newValues != null) {
						newValues[i] = value;
					}
				}
			}

			final BPlusLeafTreeNode<V> result;
			if (newValues != null) {
				result = trimmedCommittedCopy(theKeys, newValues, thePeek);
			} else if (layer != null) {
				result = trimmedCommittedCopy(theKeys, theValues, thePeek);
			} else if (!this.transactionalLayer) {
				// nodes created during splits/merges are built with transactionalLayer=false so they do
				// not allocate STM layers mid-transaction; on commit they must be rebuilt as participating
				// (transactionalLayer=true) nodes so subsequent transactions can layer changes over them
				result = trimmedCommittedCopy(theKeys, theValues, thePeek);
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
		 * Builds the committed leaf the commit-merge installs, shrinking the backing arrays when the live content
		 * has fallen far enough behind them to pay for the copy ({@link ColumnSizing#trimmedLength}). This is the
		 * only place a leaf's arrays ever get smaller: the incremental paths only grow, so without it a leaf that
		 * once filled up would hold its peak allocation for the rest of the catalog's life.
		 *
		 * Reached only from the branches that already build a new node. A leaf the merge leaves untouched returns
		 * itself and must not be rebuilt merely to trim — that would allocate on every commit for every leaf.
		 *
		 * @param keys   the committed key array (the layer's, or this node's own)
		 * @param values the committed value array, matching `keys` slot for slot
		 * @param peek   the index of the last occupied slot
		 * @return the committed leaf, on arrays trimmed to the live content where that was worth doing
		 */
		@Nonnull
		private BPlusLeafTreeNode<V> trimmedCommittedCopy(@Nonnull long[] keys, @Nonnull V[] values, int peek) {
			final int trimmed = ColumnSizing.trimmedLength(peek + 1, keys.length, this.capacity);
			return new BPlusLeafTreeNode<>(
				trimmed < keys.length ? Arrays.copyOf(keys, trimmed) : keys,
				trimmed < values.length ? Arrays.copyOf(values, trimmed) : values,
				peek,
				this.capacity,
				true,
				this.transactionalLayerWrapper
			);
		}

		/**
		 * Deletes a key-value pair from the BPlusLeafTreeNode based on the specified key.
		 * If the key is found within the node, it removes the corresponding entry,
		 * maintains the node's internal structure, and decrements the count of stored items.
		 *
		 * @param key the key of the entry to be removed from the leaf node
		 * @return true if the key was found and removed, false otherwise
		 */
		public boolean delete(long key) {
			final BPlusLeafTreeNode<V> layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			// deleting an entry mutates this leaf's page: flag it for re-emission (a no-op delete over-reports at worst)
			if (layer == null) {
				this.dirty = true;
			} else {
				layer.dirty = true;
			}
			if (layer == null) {
				final int index = Arrays.binarySearch(this.keys, 0, this.peek + 1, key);

				if (index >= 0) {
					// the value is discarded from the tree - release its transactional diff layer (if any)
					// so it is not left ALIVE and detected as stale during commit; outside a transaction the
					// guard short-circuits and this is a no-op
					discardRemovedValueLayer(this.values[index]);
					removeLongFromSameArrayOnIndex(this.keys, index);
					removeRecordFromSameArrayOnIndex(this.values, index);
					this.keys[this.peek] = 0L;
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
					// the value is discarded from the tree - release its transactional diff layer (if any)
					// so it is not left ALIVE and detected as stale during commit
					discardRemovedValueLayer(layer.values[index]);
					removeLongFromSameArrayOnIndex(layer.keys, index);
					removeRecordFromSameArrayOnIndex(layer.values, index);
					layer.keys[layer.peek] = 0L;
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
		 * (e.g. a [io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer] such as a range point) never opens a
		 * layer of its own — only its children do — so guarding on the parent's layer would leave the children's layers
		 * orphaned and detected as stale during commit. The no-arg `removeLayer()` resolves the current transaction's
		 * maintainer and is a safe no-op when no transaction is open.
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
		private boolean insert(long key, @Nonnull V value) {
			final BPlusLeafTreeNode<V> layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			// inserting or overwriting an entry mutates this leaf's page: flag it for re-emission
			if (layer == null) {
				this.dirty = true;
			} else {
				layer.dirty = true;
			}
			if (layer == null) {
				Assert.isPremiseValid(
					this.peek < this.capacity - 1,
					"Cannot insert into a full leaf node, split the node first!"
				);

				final InsertionPosition insertionPosition = computeInsertPositionOfLongInOrderedArray(
					key, this.keys, 0, this.peek + 1);
				if (insertionPosition.alreadyPresent()) {
					// an existing value is overwritten - release the discarded instance's diff layer (if any
					// and if it is genuinely a different instance) so it is not left ALIVE during commit
					final V previousValue = this.values[insertionPosition.position()];
					if (value != previousValue) {
						discardRemovedValueLayer(previousValue);
					}
					this.keys[insertionPosition.position()] = key;
					this.values[insertionPosition.position()] = value;
					return false;
				} else {
					ensurePhysicalLength(this.peek + 2);
					insertLongIntoSameArrayOnIndex(key, this.keys, insertionPosition.position());
					insertRecordIntoSameArrayOnIndex(value, this.values, insertionPosition.position());
					this.peek++;
					return true;
				}
			} else {
				decoupleTransactionalArrays();
				Assert.isPremiseValid(
					layer.peek < layer.capacity - 1,
					"Cannot insert into a full leaf node, split the node first!"
				);

				final InsertionPosition insertionPosition = computeInsertPositionOfLongInOrderedArray(
					key, layer.keys, 0, layer.peek + 1);
				if (insertionPosition.alreadyPresent()) {
					// an existing value is overwritten - release the discarded instance's diff layer (if any
					// and if it is genuinely a different instance) so it is not left ALIVE during commit
					final V previousValue = layer.values[insertionPosition.position()];
					if (value != previousValue) {
						discardRemovedValueLayer(previousValue);
					}
					layer.keys[insertionPosition.position()] = key;
					layer.values[insertionPosition.position()] = value;
					return false;
				} else {
					layer.ensurePhysicalLength(layer.peek + 2);
					insertLongIntoSameArrayOnIndex(key, layer.keys, insertionPosition.position());
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
			final BPlusLeafTreeNode<V> layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) :
				null;
			if (layer != null) {
				//noinspection ArrayEquality
				if (layer.keys == this.keys) {
					// an empty leaf keeps pointing at the shared empty array: allocating here would mint a private
					// zero-length one, costing a header and breaking the identity every heap walk subtracts
					final int keysHeadroom = ColumnSizing.headroomLength(this.peek + 1, this.keys.length, this.capacity);
					layer.keys = keysHeadroom == 0 ? this.keys : new long[keysHeadroom];
					System.arraycopy(this.keys, 0, layer.keys, 0, this.peek + 1);
				}
				//noinspection ArrayEquality
				if (layer.values == this.values) {
					final int valuesHeadroom = ColumnSizing.headroomLength(this.peek + 1, this.values.length, this.capacity);
					//noinspection unchecked
					layer.values = valuesHeadroom == 0 ?
						this.values :
						(V[]) Array.newInstance(this.values.getClass().getComponentType(), valuesHeadroom);
					System.arraycopy(this.values, 0, layer.values, 0, this.peek + 1);
				}
			}
		}

		/**
		 * Immutable savepoint memento of a leaf node's copy-on-write arrays. The arrays are private clones owned by the
		 * memento (see {@link #snapshot}); the primitive keys and value references they hold are shared by design.
		 *
		 * @param keys   clone of the key array
		 * @param values clone of the value array
		 * @param peek   the last occupied index
		 */
		record BPlusLeafNodeMemento<V>(
			@Nonnull long[] keys,
			@Nonnull V[] values,
			int peek
		) {
		}

	}

	/**
	 * Long-keyed forward iterator base: layers the typed leaf-array cache on top of the shared key-agnostic
	 * {@link AbstractForwardTreeNavigator}. The concrete key / value / entry iterators read the current element
	 * straight from these cached arrays - the primitive key iterator returns the key directly to avoid boxing, while
	 * the value and entry iterators index the cached value (and key) arrays.
	 *
	 * @param <V> the type of the values stored in the tree
	 */
	private abstract static class AbstractForwardTreeIterator<V> extends AbstractForwardTreeNavigator {
		/**
		 * The current leaf's key and value arrays, refreshed once per leaf by {@link #loadCurrentLeaf()}. Visible to
		 * subclasses so the key / value / entry iterators index them directly without a per-element accessor call.
		 */
		protected long[] leafKeys;
		protected V[] leafValues;

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
		protected AbstractForwardTreeIterator(@Nonnull Cursor cursor, long key) {
			// the leaf resolves its own key array, value array and peek together and bounds the search by them.
			// Reading `getKeys()` and `size()` here instead would be two independent resolutions of the leaf's state,
			// so a size raised by a growth could be paired with the shorter array that preceded it and the binary
			// search would probe past its end
			super(cursor, cursor.<BPlusLeafTreeNode<V>>leafNode().findKeyPosition(key));
		}

		@Override
		protected void loadCurrentLeaf() {
			//noinspection unchecked
			final BPlusLeafTreeNode<V> leaf = (BPlusLeafTreeNode<V>) currentLeafNode();
			// three independent accessors, each resolving the transactional layer for itself, so the peek can belong
			// to a later state than the arrays cached beside it. Bounded once per leaf — never per element, the
			// iterators read these fields directly
			final long[] keys = leaf.getKeys();
			final V[] values = leaf.getValues();
			this.leafKeys = keys;
			this.leafValues = values;
			this.leafPeek = observableLeafPeek(leaf.getPeek(), keys, values);
		}
	}

	/**
	 * Long-keyed reverse iterator base: layers the typed leaf-array cache on top of the shared key-agnostic
	 * {@link AbstractReverseTreeNavigator}. The concrete key / value / entry iterators read the current element
	 * straight from these cached arrays.
	 *
	 * @param <V> the type of the values stored in the tree
	 */
	private abstract static class AbstractReverseTreeIterator<V> extends AbstractReverseTreeNavigator {
		/**
		 * The current leaf's key and value arrays, refreshed once per leaf by {@link #loadCurrentLeaf()}. Visible to
		 * subclasses so the key / value / entry iterators index them directly without a per-element accessor call.
		 */
		protected long[] leafKeys;
		protected V[] leafValues;

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
		protected AbstractReverseTreeIterator(@Nonnull Cursor cursor, long key) {
			// see the forward sibling: the position must come from the leaf's own bounded seek, never from a
			// `getKeys()` paired with a separately resolved `size()`
			super(cursor, cursor.<BPlusLeafTreeNode<V>>leafNode().findKeyPosition(key));
		}

		@Override
		protected void loadCurrentLeaf() {
			//noinspection unchecked
			final BPlusLeafTreeNode<V> leaf = (BPlusLeafTreeNode<V>) currentLeafNode();
			// three independent accessors, each resolving the transactional layer for itself, so the peek can belong
			// to a later state than the arrays cached beside it. Bounded once per leaf — never per element, the
			// iterators read these fields directly
			final long[] keys = leaf.getKeys();
			final V[] values = leaf.getValues();
			this.leafKeys = keys;
			this.leafValues = values;
			this.leafPeek = observableLeafPeek(leaf.getPeek(), keys, values);
		}
	}

	/**
	 * Iterator that traverses the B+ Tree keys from left to right. The primitive key is returned directly
	 * from {@link #nextLong()} so that no boxing occurs on the iteration path.
	 *
	 * @param <V> the type of the values stored in the tree
	 */
	private static class ForwardTreeKeyIterator<V> extends AbstractForwardTreeIterator<V> implements OfLong {

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
		public ForwardTreeKeyIterator(@Nonnull Cursor cursor, long key) {
			super(cursor, key);
		}

		@Override
		public long nextLong() {
			if (!this.hasNextElement) {
				throw new NoSuchElementException("No more elements available");
			}
			// read straight from the cached leaf key array - no per-element ThreadLocal accessor call
			final long key = this.leafKeys[this.currentIndex];
			advance();
			return key;
		}
	}

	/**
	 * Iterator that traverses the B+ Tree keys from right to left. The primitive key is returned directly
	 * from {@link #nextLong()} so that no boxing occurs on the iteration path.
	 *
	 * @param <V> the type of the values stored in the tree
	 */
	private static class ReverseTreeKeyIterator<V> extends AbstractReverseTreeIterator<V> implements OfLong {

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
		public ReverseTreeKeyIterator(@Nonnull Cursor cursor, long key) {
			super(cursor, key);
		}

		@Override
		public long nextLong() {
			if (!this.hasNextElement) {
				throw new NoSuchElementException("No more elements available");
			}
			// read straight from the cached leaf key array - no per-element ThreadLocal accessor call
			final long key = this.leafKeys[this.currentIndex];
			advance();
			return key;
		}
	}

	/**
	 * Iterator that traverses the B+ Tree values from left to right.
	 *
	 * @param <V> the type of the values stored in the tree
	 */
	static class ForwardTreeValueIterator<V> extends AbstractForwardTreeIterator<V> implements Iterator<V> {

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
		public ForwardTreeValueIterator(@Nonnull Cursor cursor, long key) {
			super(cursor, key);
		}

		@Override
		public V next() {
			if (!this.hasNextElement) {
				throw new NoSuchElementException("No more elements available");
			}
			// read straight from the cached leaf value array - no per-element ThreadLocal accessor call
			final V value = this.leafValues[this.currentIndex];
			advance();
			return value;
		}
	}

	/**
	 * Iterator that traverses the B+ Tree values from right to left.
	 *
	 * @param <V> the type of the values stored in the tree
	 */
	static class ReverseTreeValueIterator<V> extends AbstractReverseTreeIterator<V> implements Iterator<V> {

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
		public ReverseTreeValueIterator(@Nonnull Cursor cursor, long key) {
			super(cursor, key);
		}

		@Override
		public V next() {
			if (!this.hasNextElement) {
				throw new NoSuchElementException("No more elements available");
			}
			// read straight from the cached leaf value array - no per-element ThreadLocal accessor call
			final V value = this.leafValues[this.currentIndex];
			advance();
			return value;
		}
	}

	/**
	 * Iterator that traverses the B+ Tree from left to right and provides access to entries (both keys and values).
	 *
	 * @param <V> the type of the values stored in the tree
	 */
	static class ForwardTreeEntryIterator<V> extends AbstractForwardTreeIterator<V> implements Iterator<Entry<V>> {

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
		public ForwardTreeEntryIterator(@Nonnull Cursor cursor, long key) {
			super(cursor, key);
		}

		@Override
		public Entry<V> next() {
			if (!this.hasNextElement) {
				throw new NoSuchElementException("No more elements available");
			}
			// build straight from the cached leaf arrays - no per-element ThreadLocal accessor call
			final Entry<V> entry = new Entry<>(this.leafKeys[this.currentIndex], this.leafValues[this.currentIndex]);
			advance();
			return entry;
		}
	}

	/**
	 * Iterator that traverses the B+ Tree from right to left and provides access to entries (both keys and values).
	 *
	 * @param <V> the type of the values stored in the tree
	 */
	static class ReverseTreeEntryIterator<V> extends AbstractReverseTreeIterator<V> implements Iterator<Entry<V>> {

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
		public ReverseTreeEntryIterator(@Nonnull Cursor cursor, long key) {
			super(cursor, key);
		}

		@Override
		public Entry<V> next() {
			if (!this.hasNextElement) {
				throw new NoSuchElementException("No more elements available");
			}
			// build straight from the cached leaf arrays - no per-element ThreadLocal accessor call
			final Entry<V> entry = new Entry<>(this.leafKeys[this.currentIndex], this.leafValues[this.currentIndex]);
			advance();
			return entry;
		}
	}

	/**
	 * Entry is an immutable data structure that stores a primitive `long` key together with its value.
	 *
	 * @param <V>   the type of the value
	 * @param key   the primitive key of the entry
	 * @param value the value associated with the key
	 */
	public record Entry<V>(
		long key,
		@Nonnull V value
	) {
	}

}
