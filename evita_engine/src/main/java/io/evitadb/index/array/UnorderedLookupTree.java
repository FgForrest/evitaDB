/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.index.array;

import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.Snapshotable;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.dataType.ConsistencySensitiveDataStructure;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.reference.TransactionalReference;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The **position tree** of the two-tree backing for {@link UnorderedLookup}: a count-augmented (order-statistic) B+
 * tree of distinct `int` record ids, ordered by their **logical position** and routed by a stable `long`
 * **order-key**.
 *
 * The leaves are **containers** holding up to {@link #DEFAULT_BLOCK_SIZE} record ids in logical order; each container carries
 * a `long` order-key. Internal nodes route by order-key separators **and** carry the record-id count of each child
 * subtree (the augmentation that makes positions implicit). Because order-key order is identical to logical order, a
 * single tree answers both:
 *
 * - **by position** (`getRecordAt` / select): descend choosing the child whose cumulative count brackets the target;
 * - **by order-key** (`findPositionByOrderKey`): descend by key, summing the counts of the left siblings to obtain
 *   the prefix count of records before the container, then add the in-container offset.
 *
 * Descent is **cursor based** (the root → leaf path is captured during descent) — there are **no parent pointers and
 * no sibling links** — so the very same structure path-copies cleanly under the transactional layer (mirroring
 * {@link io.evitadb.index.bPlusTree.TransactionalIntToLongBPlusTree}). Mutations touch only the cursor path: `O(log N)`
 * count re-stamps, no suffix renumber, and small fixed-capacity blocks instead of the `O(N)` humongous arrays of the
 * array delegate.
 *
 * This tree is **not** the owner of the `recordId → orderKey` mapping — that is the job of the value index held by
 * the composite that drives this tree. Whenever a mutation assigns or re-stamps a record's order-key (a fresh insert
 * or a container split), the tree reports it through an {@link OrderKeyConsumer} so the composite can keep its value
 * index coherent (INV-COUPLE). Order-keys are minted **only on container split**; on gap exhaustion the whole key
 * space is re-spaced (rare, `O(#containers)`).
 *
 * The tree is a {@link TransactionalLayerProducer}: **outside** a transaction it mutates strictly **in place** (the
 * committed / warm-up delegate, byte-for-byte identical to the legacy behaviour); **inside** a transaction it
 * path-copies (copy-on-write) — only the touched root→leaf path's nodes decouple their arrays into the per-transaction
 * layer, the committed tree stays untouched, and the changes materialise on commit. The records must be distinct.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@NotThreadSafe
public class UnorderedLookupTree implements
	TransactionalLayerProducer<Void, UnorderedLookupTree>,
	ConsistencySensitiveDataStructure,
	Serializable {
	@Serial private static final long serialVersionUID = -7242020610200620162L;

	/**
	 * Default (and maximum) physical capacity of a single node block (both container record slots and internal child
	 * slots). A power of two keeps the blocks small enough to be TLAB-allocated and cache friendly. Node arrays are
	 * always allocated to this fixed size; the per-instance {@link #blockSize} is the LOGICAL split threshold (≤ this
	 * value) so tests can force splits/steals/merges at a small fan-out without changing the physical allocation.
	 */
	static final int DEFAULT_BLOCK_SIZE = 64;
	/**
	 * Default spacing between freshly assigned order-keys. Wide enough that gap subdivision practically never
	 * exhausts before a tree of realistic height is built; exhaustion is nonetheless handled by re-spacing.
	 */
	static final long DEFAULT_ORDER_KEY_GAP = 1L << 40;
	/**
	 * Sentinel used during minting to signal "no container to the right" (rightmost container).
	 */
	private static final long NO_NEXT_KEY = Long.MAX_VALUE;
	/**
	 * Upper bound on tree height used to size the descent cursor. The tree is kept balanced (all leaves at equal depth)
	 * by the delete-side steal/merge consolidation, so for the production fan-out (64) height is bounded by
	 * `log32(N) < 7`. The generous cap of 64 also accommodates the small logical {@link #blockSize} values used in tests
	 * (a fan-out of 2 still tops out at `log2(Integer.MAX_VALUE) ≈ 31`).
	 */
	private static final int MAX_HEIGHT = 64;

	/**
	 * Stable identity of this tree, used by the transactional memory machinery to key its per-transaction diff layer.
	 */
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Root node of the tree. Holds `null` when the tree is empty.
	 */
	private final TransactionalReference<Node<?>> root;
	/**
	 * Total number of record ids held by the tree (equals the length of the logical array).
	 */
	private final TransactionalReference<Integer> size;
	/**
	 * Spacing between freshly assigned order-keys (overridable for tests that force re-spacing).
	 */
	private final long orderKeyGap;
	/**
	 * Logical maximum number of entries per node before it splits (container record slots / internal child slots). Equal
	 * to {@link #DEFAULT_BLOCK_SIZE} in production; tests may lower it (≤ {@link #DEFAULT_BLOCK_SIZE}) to force frequent
	 * splits/steals/merges at small fan-out. The physical node arrays are always {@link #DEFAULT_BLOCK_SIZE}-sized.
	 */
	private final int blockSize;
	/**
	 * Minimum number of children a NON-ROOT internal node may hold before it underflows and must steal from / merge with
	 * a sibling. Derived as `max(2, (blockSize + 1) / 2)`, which guarantees two minimally-filled siblings always fit into
	 * one node on merge (`(minChildren - 1) + minChildren ≤ blockSize`). Leaf containers have NO minimum-occupancy floor:
	 * the delete side only ever removes EMPTY containers (never merges non-empty ones), so a record never moves between
	 * containers and never needs its order-key re-stamped — only whole child subtrees are moved during rebalancing.
	 */
	private final int minChildren;
	/**
	 * Memoized flattened permutation (logical position → record id). Nullified on every mutation. Not transactional —
	 * it is a pure read-cache derived from the current view and is recomputed lazily; only ever populated outside a
	 * transaction (mutations inside a transaction never read it back, they always invalidate it on entry).
	 */
	@Nullable private int[] memoizedArray;

	/**
	 * Creates a new empty tree with the production fan-out and the default order-key gap.
	 */
	public UnorderedLookupTree() {
		this(DEFAULT_BLOCK_SIZE, DEFAULT_ORDER_KEY_GAP);
	}

	/**
	 * Creates a new empty tree with the production fan-out and a custom order-key gap (used by tests to force the
	 * re-spacing path cheaply).
	 */
	UnorderedLookupTree(long orderKeyGap) {
		this(DEFAULT_BLOCK_SIZE, orderKeyGap);
	}

	/**
	 * Creates a new empty tree with a custom logical {@link #blockSize} (≤ {@link #DEFAULT_BLOCK_SIZE}) and order-key
	 * gap. Used by tests to force splits/steals/merges at a small fan-out.
	 *
	 * @param blockSize   logical split threshold; must be in `[3, DEFAULT_BLOCK_SIZE]`
	 * @param orderKeyGap the order-key spacing
	 */
	UnorderedLookupTree(int blockSize, long orderKeyGap) {
		if (blockSize < 3 || blockSize > DEFAULT_BLOCK_SIZE) {
			throw new GenericEvitaInternalError(
				"Block size must be in [3, " + DEFAULT_BLOCK_SIZE + "], got " + blockSize + "!"
			);
		}
		this.blockSize = blockSize;
		this.minChildren = Math.max(2, (blockSize + 1) / 2);
		this.orderKeyGap = orderKeyGap;
		this.root = new TransactionalReference<>(null);
		this.size = new TransactionalReference<>(0);
	}

	/**
	 * Internal constructor used by {@link #createCopyWithMergedTransactionalMemory(Void, TransactionalLayerMaintainer)}
	 * to rebuild a committed tree wrapping the already-merged root and size.
	 *
	 * @param blockSize   the logical fan-out to carry over
	 * @param orderKeyGap the order-key spacing to carry over
	 * @param root        the committed root node (or `null` for an empty tree)
	 * @param size        the committed record count
	 */
	private UnorderedLookupTree(int blockSize, long orderKeyGap, @Nullable Node<?> root, int size) {
		this.blockSize = blockSize;
		this.minChildren = Math.max(2, (blockSize + 1) / 2);
		this.orderKeyGap = orderKeyGap;
		this.root = new TransactionalReference<>(root);
		this.size = new TransactionalReference<>(size);
	}

	/**
	 * Returns the number of record ids in the tree (length of the logical array).
	 */
	public int size() {
		final Integer theSize = this.size.get();
		return theSize == null ? 0 : theSize;
	}

	/**
	 * Returns true when the tree holds no records.
	 */
	public boolean isEmpty() {
		return size() == 0;
	}

	/**
	 * Bulk-loads an empty tree from an array of record ids in logical order, building it bottom-up with fully-filled
	 * containers and evenly spaced order-keys. Reports every record's order-key through `assignments`. `O(N)`.
	 *
	 * @param recordIds   record ids in logical order (must be distinct)
	 * @param assignments callback receiving each `recordId → orderKey` assignment
	 */
	public void bulkLoad(@Nonnull int[] recordIds, @Nonnull OrderKeyConsumer assignments) {
		if (getRoot() != null) {
			throw new GenericEvitaInternalError("Bulk-load is only allowed on an empty tree!");
		}
		final int n = recordIds.length;
		if (n == 0) {
			return;
		}
		// 1. pack records into containers and report their order-keys
		final int containerCount = (n + this.blockSize - 1) / this.blockSize;
		Node<?>[] level = new Node<?>[containerCount];
		long[] minKeys = new long[containerCount];
		int[] counts = new int[containerCount];
		int pos = 0;
		for (int c = 0; c < containerCount; c++) {
			final LeafNode container = new LeafNode(true);
			final long key = (long) c * this.orderKeyGap;
			container.setOrderKey(key);
			final int cnt = Math.min(this.blockSize, n - pos);
			final int[] containerRecordIds = container.getRecordIdsForUpdate();
			System.arraycopy(recordIds, pos, containerRecordIds, 0, cnt);
			container.setCount(cnt);
			for (int i = 0; i < cnt; i++) {
				assignments.accept(recordIds[pos + i], key);
			}
			level[c] = container;
			minKeys[c] = key;
			counts[c] = cnt;
			pos += cnt;
		}
		setSize(n);
		// 2. build internal levels bottom-up until a single root remains
		int levelSize = containerCount;
		while (levelSize > 1) {
			final int parentCount = (levelSize + this.blockSize - 1) / this.blockSize;
			final int base = levelSize / parentCount;
			final int remainder = levelSize % parentCount;
			final Node<?>[] parents = new Node<?>[parentCount];
			final long[] parentMinKeys = new long[parentCount];
			final int[] parentCounts = new int[parentCount];
			int childCursor = 0;
			for (int p = 0; p < parentCount; p++) {
				// distribute children evenly so the tail node never ends up with a single child
				final int childN = base + (p < remainder ? 1 : 0);
				final InternalNode internal = new InternalNode(true);
				final Node<?>[] internalChildren = internal.getChildrenForUpdate();
				final int[] internalCounts = internal.getCountsForUpdate();
				final long[] internalSeparators = internal.getSeparatorsForUpdate();
				internal.setChildCount(childN);
				int subtree = 0;
				for (int j = 0; j < childN; j++) {
					internalChildren[j] = level[childCursor + j];
					internalCounts[j] = counts[childCursor + j];
					subtree += counts[childCursor + j];
					if (j >= 1) {
						internalSeparators[j - 1] = minKeys[childCursor + j];
					}
				}
				parents[p] = internal;
				parentMinKeys[p] = minKeys[childCursor];
				parentCounts[p] = subtree;
				childCursor += childN;
			}
			level = parents;
			minKeys = parentMinKeys;
			counts = parentCounts;
			levelSize = parentCount;
		}
		setRoot(level[0]);
		invalidateMemoizedState();
	}

	/**
	 * Returns the record id located on the passed logical position.
	 *
	 * @throws GenericEvitaInternalError when the position is out of bounds
	 */
	public int getRecordAt(int position) {
		final Node<?> theRoot = getRoot();
		if (position < 0 || position >= size() || theRoot == null) {
			throw new GenericEvitaInternalError(
				"Position " + position + " not found!",
				"Unknown position in the array!"
			);
		}
		Node<?> node = theRoot;
		int remaining = position;
		while (node instanceof final InternalNode internal) {
			final int childCount = internal.getChildCount();
			final int[] counts = internal.getCounts();
			final Node<?>[] children = internal.getChildren();
			int childIndex = 0;
			while (childIndex < childCount - 1 && remaining >= counts[childIndex]) {
				remaining -= counts[childIndex];
				childIndex++;
			}
			node = children[childIndex];
		}
		final LeafNode leaf = (LeafNode) node;
		return leaf.getRecordIds()[remaining];
	}

	/**
	 * Returns the last record id in the logical array.
	 *
	 * @throws ArrayIndexOutOfBoundsException when the tree is empty
	 */
	public int getLastRecordId() throws ArrayIndexOutOfBoundsException {
		final Node<?> theRoot = getRoot();
		if (size() == 0 || theRoot == null) {
			throw new ArrayIndexOutOfBoundsException("Array is empty!");
		}
		Node<?> node = theRoot;
		while (node instanceof final InternalNode internal) {
			node = internal.getChildren()[internal.getChildCount() - 1];
		}
		final LeafNode container = (LeafNode) node;
		return container.getRecordIds()[container.getCount() - 1];
	}

	/**
	 * Returns the logical position of `recordId`, known to live in the container routed by `orderKey`.
	 *
	 * @return the prefix count of the container plus the record's in-container offset
	 * @throws GenericEvitaInternalError when the record is not found in the routed container, or when the
	 *                                   routed key resolves into an empty tree (internal-consistency failure,
	 *                                   since the caller guarantees the record lives in a routed container)
	 */
	public int findPositionByOrderKey(long orderKey, int recordId) {
		int prefix = 0;
		Node<?> node = getRoot();
		while (node instanceof final InternalNode internal) {
			final int childCount = internal.getChildCount();
			final int[] counts = internal.getCounts();
			final long[] separators = internal.getSeparators();
			final Node<?>[] children = internal.getChildren();
			int childIndex = 0;
			while (childIndex < childCount - 1 && orderKey >= separators[childIndex]) {
				prefix += counts[childIndex];
				childIndex++;
			}
			node = children[childIndex];
		}
		if (node == null) {
			// the caller guarantees the record lives in a routed container, so the tree can never be empty here
			throw new GenericEvitaInternalError(
				"Order-key " + orderKey + " could not be routed in an empty tree!",
				"Inconsistent lookup state!"
			);
		}
		return prefix + indexInContainer((LeafNode) node, recordId);
	}

	/**
	 * Inserts `recordId` at the logical `index` (descending by position to the proper container). Reports the
	 * resulting `recordId → orderKey` assignment(s) through `assignments`.
	 */
	public void insertAtPosition(int index, int recordId, @Nonnull OrderKeyConsumer assignments) {
		if (getRoot() == null) {
			final LeafNode container = new LeafNode(true);
			container.setOrderKey(0L);
			container.getRecordIdsForUpdate()[0] = recordId;
			container.setCount(1);
			setRoot(container);
			setSize(1);
			assignments.accept(recordId, 0L);
			invalidateMemoizedState();
			return;
		}
		final Cursor cursor = new Cursor();
		final LeafNode container = descendByPosition(index, cursor);
		final int offset = Math.min(cursor.leafOffset, container.getCount());
		insertIntoContainer(container, offset, recordId, cursor, assignments);
		invalidateMemoizedState();
	}

	/**
	 * Inserts `recordId` immediately after `previousRecordId` inside the container routed by `previousOrderKey`.
	 * Reports the resulting `recordId → orderKey` assignment(s) through `assignments`.
	 *
	 * @throws GenericEvitaInternalError when `previousRecordId` is not found in the routed container, or when
	 *                                   the descent is attempted on an empty tree (internal-consistency failure)
	 */
	public void insertAfter(long previousOrderKey, int previousRecordId, int recordId, @Nonnull OrderKeyConsumer assignments) {
		final Cursor cursor = new Cursor();
		final LeafNode container = descendByKey(previousOrderKey, cursor);
		final int offset = indexInContainer(container, previousRecordId) + 1;
		insertIntoContainer(container, offset, recordId, cursor, assignments);
		invalidateMemoizedState();
	}

	/**
	 * Removes `recordId` from the container routed by `orderKey`. Reports any order-key re-stamps caused by container
	 * collapse through `reassignments` (currently none — empty containers are simply unlinked).
	 *
	 * @throws GenericEvitaInternalError when the record is not found in the routed container, or when the
	 *                                   descent is attempted on an empty tree (internal-consistency failure)
	 */
	public void removeByOrderKey(long orderKey, int recordId, @Nonnull OrderKeyConsumer reassignments) {
		final Cursor cursor = new Cursor();
		final LeafNode container = descendByKey(orderKey, cursor);
		final int offset = indexInContainer(container, recordId);
		final int count = container.getCount();
		final int[] recordIds = container.getRecordIdsForUpdate();
		System.arraycopy(recordIds, offset + 1, recordIds, offset, count - offset - 1);
		container.setCount(count - 1);
		propagateCountDelta(cursor, -1);
		setSize(size() - 1);
		if (container.getCount() == 0) {
			removeEmptyContainer(cursor);
		}
		invalidateMemoizedState();
	}

	/**
	 * Returns the flattened unordered array of record ids in logical order.
	 */
	@Nonnull
	public int[] getArray() {
		// inside a transaction the flattened view depends on the per-transaction layer, so it must never be memoized
		// on the shared instance - otherwise the transactional view would poison the committed read-cache (and vice
		// versa). Only the committed (no-transaction) view is cached.
		if (Transaction.isTransactionAvailable()) {
			final int[] result = new int[size()];
			final Node<?> theRoot = getRoot();
			if (theRoot != null) {
				flattenInto(theRoot, result, new int[1]);
			}
			return result;
		}
		if (this.memoizedArray == null) {
			final int[] result = new int[size()];
			final Node<?> theRoot = getRoot();
			if (theRoot != null) {
				flattenInto(theRoot, result, new int[1]);
			}
			this.memoizedArray = result;
		}
		return this.memoizedArray;
	}

	@Override
	public String toString() {
		return "UnorderedLookupTree" + Arrays.toString(getArray());
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
		// getRoot() would fall back to the committed root (null for a tree built within this transaction) and the
		// node-graph recursion would be skipped, leaking every node layer.
		final Node<?> theRoot = getRoot();
		this.size.removeLayer(transactionalLayer);
		this.root.removeLayer(transactionalLayer);
		if (theRoot != null) {
			removeLayerRecursively(theRoot, transactionalLayer);
		}
	}

	@Nonnull
	@Override
	public UnorderedLookupTree createCopyWithMergedTransactionalMemory(
		@Nullable Void layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		// the reference merge yields the (possibly unchanged) root NODE; we must then merge that node's own diff
		// layer and the whole subtree below it, exactly like the reference B+ tree does
		final Node<?> referencedRoot = transactionalLayer.getStateCopyWithCommittedChanges(this.root).orElse(null);
		final Node<?> theRoot = referencedRoot == null
			? null
			: transactionalLayer.getStateCopyWithCommittedChanges(referencedRoot);
		final int theSize = transactionalLayer.getStateCopyWithCommittedChanges(this.size).orElse(0);
		return new UnorderedLookupTree(this.blockSize, this.orderKeyGap, theRoot, theSize);
	}

	@Nonnull
	@Override
	public ConsistencyReport getConsistencyReport() {
		final List<String> errors = new ArrayList<>();
		final Node<?> theRoot = getRoot();
		if (theRoot == null) {
			if (size() != 0) {
				errors.add("Empty tree (null root) but size() reports " + size() + "!");
			}
		} else {
			final int[] leafDepth = new int[]{-1};
			final long[] lastKey = new long[]{Long.MIN_VALUE};
			final long[] recordCount = new long[1];
			verifyConsistency(theRoot, 0, true, leafDepth, lastKey, errors, recordCount);
			if (recordCount[0] != size()) {
				errors.add("Tracked size " + size() + " does not match counted records " + recordCount[0] + "!");
			}
		}
		return errors.isEmpty()
			? new ConsistencyReport(ConsistencyState.CONSISTENT, "The position tree is balanced and consistent.")
			: new ConsistencyReport(ConsistencyState.BROKEN, String.join("\n", errors));
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Recursively verifies the subtree rooted at `node`: equal leaf depth (balance), non-root internal min-occupancy,
	 * block-size bounds, per-child subtree-count augmentation, order-key separators (each separator equals the minimum
	 * order-key of the child it borders) and global strict order-key monotonicity across containers in logical order.
	 * Leaf containers are intentionally NOT checked for a minimum occupancy floor — the delete side only removes EMPTY
	 * containers and never merges non-empty ones, so under-full containers are a legal (memory-only) state. Accumulates
	 * the total record count into `recordCount` and returns the record count of `node`'s subtree.
	 */
	private int verifyConsistency(
		@Nonnull Node<?> node, int depth, boolean isRoot,
		@Nonnull int[] leafDepth, @Nonnull long[] lastKey,
		@Nonnull List<String> errors, @Nonnull long[] recordCount
	) {
		if (node instanceof final LeafNode leaf) {
			if (leafDepth[0] == -1) {
				leafDepth[0] = depth;
			} else if (leafDepth[0] != depth) {
				errors.add("Unbalanced tree: container at depth " + depth + " but expected " + leafDepth[0] + "!");
			}
			final int count = leaf.getCount();
			if (!isRoot && count < 1) {
				errors.add("Empty non-root container encountered!");
			}
			if (count > this.blockSize) {
				errors.add("Container overflow: " + count + " > block size " + this.blockSize + "!");
			}
			final long key = leaf.getOrderKey();
			if (lastKey[0] != Long.MIN_VALUE && key <= lastKey[0]) {
				errors.add("Container order-key " + key + " is not strictly greater than its predecessor " + lastKey[0] + "!");
			}
			lastKey[0] = key;
			recordCount[0] += count;
			return count;
		}
		final InternalNode internal = (InternalNode) node;
		final int childCount = internal.getChildCount();
		if (isRoot) {
			if (childCount < 2) {
				errors.add("Root internal node has fewer than 2 children (" + childCount + ")!");
			}
		} else if (childCount < this.minChildren) {
			errors.add("Internal underflow: " + childCount + " children < minimum " + this.minChildren + "!");
		}
		if (childCount > this.blockSize) {
			errors.add("Internal overflow: " + childCount + " children > block size " + this.blockSize + "!");
		}
		final Node<?>[] children = internal.getChildren();
		final int[] counts = internal.getCounts();
		final long[] separators = internal.getSeparators();
		int sum = 0;
		for (int i = 0; i < childCount; i++) {
			if (i > 0) {
				final long childMin = minOrderKey(children[i]);
				if (separators[i - 1] != childMin) {
					errors.add("Separator[" + (i - 1) + "]=" + separators[i - 1] + " != child subtree min order-key " + childMin + "!");
				}
			}
			final int childRecords = verifyConsistency(children[i], depth + 1, false, leafDepth, lastKey, errors, recordCount);
			if (counts[i] != childRecords) {
				errors.add("Stored subtree count " + counts[i] + " != actual " + childRecords + " at child " + i + "!");
			}
			sum += childRecords;
		}
		return sum;
	}

	/**
	 * Returns the minimum order-key in the subtree rooted at `node` (the order-key of its leftmost container).
	 */
	private static long minOrderKey(@Nonnull Node<?> node) {
		Node<?> current = node;
		while (current instanceof final InternalNode internal) {
			current = internal.getChildren()[0];
		}
		return ((LeafNode) current).getOrderKey();
	}

	/**
	 * Returns the current view of the root node — the transactional view when a layer exists, the committed node
	 * otherwise. Returns `null` for an empty tree.
	 */
	@Nullable
	private Node<?> getRoot() {
		return this.root.get();
	}

	/**
	 * Replaces the root node, dropping any transactional layer held by the previous root that gets discarded.
	 */
	private void setRoot(@Nullable Node<?> newRoot) {
		final Node<?> currentRoot = getRoot();
		if (currentRoot != null && currentRoot != newRoot
			&& Transaction.getTransactionalMemoryLayerIfExists(currentRoot) != null) {
			currentRoot.removeLayer();
		}
		this.root.set(newRoot);
	}

	/**
	 * Sets the total record count.
	 */
	private void setSize(int newSize) {
		this.size.set(newSize);
	}

	/**
	 * Recursively removes the transactional diff layers of the passed node and its descendants. Walks the current
	 * transactional view of the tree. Nodes hold only primitive `int`/`long` payloads, so there are no per-value
	 * {@link TransactionalLayerProducer}s to sweep.
	 *
	 * @param node               the node whose layer (and that of its subtree) is to be removed
	 * @param transactionalLayer the maintainer that owns the diff layers
	 */
	private static void removeLayerRecursively(
		@Nonnull Node<?> node,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		if (node instanceof final InternalNode internal) {
			final Node<?>[] children = internal.getChildren();
			final int childCount = internal.getChildCount();
			for (int i = 0; i < childCount; i++) {
				removeLayerRecursively(children[i], transactionalLayer);
			}
		} else if (node instanceof LeafNode) {
			// leaf payload is primitive ints, never TransactionalLayerProducers - nothing to recurse into
		} else {
			throw new GenericEvitaInternalError("Unknown node type: " + node);
		}
		// the node's own removeLayer asserts a layer exists - only call it when one is actually open
		if (Transaction.getTransactionalMemoryLayerIfExists(node) != null) {
			node.removeLayer(transactionalLayer);
		}
	}

	/**
	 * Inserts `recordId` into `container` at the given in-container `offset`, propagates the count up the cursor path,
	 * reports the assignment(s) and splits the container on overflow.
	 */
	private void insertIntoContainer(@Nonnull LeafNode container, int offset, int recordId, @Nonnull Cursor cursor, @Nonnull OrderKeyConsumer assignments) {
		final int count = container.getCount();
		final int[] recordIds = container.getRecordIdsForUpdate();
		System.arraycopy(recordIds, offset, recordIds, offset + 1, count - offset);
		recordIds[offset] = recordId;
		container.setCount(count + 1);
		propagateCountDelta(cursor, +1);
		setSize(size() + 1);
		if (container.getCount() > this.blockSize) {
			splitContainer(container, offset, recordId, cursor, assignments);
		} else {
			assignments.accept(recordId, container.getOrderKey());
		}
	}

	/**
	 * Descends from the root to the container holding logical `position`, capturing the cursor path and storing the
	 * in-container offset in {@link Cursor#leafOffset}.
	 *
	 * @throws GenericEvitaInternalError when invoked on an empty tree (callers must handle the empty case before
	 *                                   descending)
	 */
	@Nonnull
	private LeafNode descendByPosition(int position, @Nonnull Cursor cursor) {
		cursor.depth = 0;
		Node<?> node = getRoot();
		if (node == null) {
			// positional descent is only invoked on a non-empty tree (the empty case is handled by the caller)
			throw new GenericEvitaInternalError(
				"Cannot descend by position " + position + " into an empty tree!",
				"Inconsistent lookup state!"
			);
		}
		int remaining = position;
		while (node instanceof final InternalNode internal) {
			final int childCount = internal.getChildCount();
			final int[] counts = internal.getCounts();
			final Node<?>[] children = internal.getChildren();
			int childIndex = 0;
			// for an insertion at a child boundary we stay in the left child (append at its end)
			while (childIndex < childCount - 1 && remaining > counts[childIndex]) {
				remaining -= counts[childIndex];
				childIndex++;
			}
			cursor.push(internal, childIndex);
			node = children[childIndex];
		}
		cursor.leafOffset = remaining;
		return (LeafNode) node;
	}

	/**
	 * Descends from the root to the container routed by `orderKey`, capturing the cursor path.
	 *
	 * @throws GenericEvitaInternalError when invoked on an empty tree (descent is only valid for records known to
	 *                                   live in the tree)
	 */
	@Nonnull
	private LeafNode descendByKey(long orderKey, @Nonnull Cursor cursor) {
		cursor.depth = 0;
		Node<?> node = getRoot();
		if (node == null) {
			// key descent is only invoked for records known to live in the tree, so it is never empty here
			throw new GenericEvitaInternalError(
				"Cannot descend by order-key " + orderKey + " into an empty tree!",
				"Inconsistent lookup state!"
			);
		}
		while (node instanceof final InternalNode internal) {
			final int childCount = internal.getChildCount();
			final long[] separators = internal.getSeparators();
			final Node<?>[] children = internal.getChildren();
			int childIndex = 0;
			while (childIndex < childCount - 1 && orderKey >= separators[childIndex]) {
				childIndex++;
			}
			cursor.push(internal, childIndex);
			node = children[childIndex];
		}
		return (LeafNode) node;
	}

	/**
	 * Adjusts the stored subtree counts of every internal node on the cursor path by `delta`.
	 */
	private static void propagateCountDelta(@Nonnull Cursor cursor, int delta) {
		for (int level = 0; level < cursor.depth; level++) {
			final InternalNode node = cursor.path[level];
			final int[] counts = node.getCountsForUpdate();
			counts[cursor.idx[level]] += delta;
		}
	}

	/**
	 * Splits an overflowing container into two, mints an order-key for the new right container, reports the affected
	 * `recordId → orderKey` assignments (the newly inserted record plus the records moved to the right container) and
	 * propagates the split up the cursor path.
	 *
	 * @param newOffset the in-container offset at which the new record was just inserted
	 * @param newRecordId the newly inserted record id
	 */
	private void splitContainer(@Nonnull LeafNode container, int newOffset, int newRecordId, @Nonnull Cursor cursor, @Nonnull OrderKeyConsumer assignments) {
		final int total = container.getCount();
		final int leftCount = total / 2;
		final int rightCount = total - leftCount;
		final long rightKey = mintOrderKey(container, cursor, assignments);
		// the offspring node participates in the transactional layer so that any later in-savepoint
		// mutation routes through a snapshot-able layer and can be rolled back per-entity
		final LeafNode right = new LeafNode(true);
		right.setOrderKey(rightKey);
		final int[] containerRecordIds = container.getRecordIdsForUpdate();
		final int[] rightRecordIds = right.getRecordIdsForUpdate();
		System.arraycopy(containerRecordIds, leftCount, rightRecordIds, 0, rightCount);
		right.setCount(rightCount);
		container.setCount(leftCount);
		Arrays.fill(containerRecordIds, leftCount, total, 0);
		// report the new record (in whichever half it landed) and every other record that moved to the right half
		final long newRecordKey = newOffset < leftCount ? container.getOrderKey() : rightKey;
		assignments.accept(newRecordId, newRecordKey);
		for (int i = 0; i < rightCount; i++) {
			final int movedRecordId = rightRecordIds[i];
			if (movedRecordId != newRecordId) {
				assignments.accept(movedRecordId, rightKey);
			}
		}
		propagateSplit(cursor, cursor.depth - 1, right, rightKey, rightCount);
	}

	/**
	 * Propagates a node split up the cursor: inserts `newRight` (with `newRightMinKey` / `newRightCount`) into the
	 * parent at `level`, creating a new root when the split reaches the top and splitting parents on overflow.
	 *
	 * @throws GenericEvitaInternalError when the split reaches the top yet the root is missing (a split can only
	 *                                   propagate up from an existing leaf)
	 */
	private void propagateSplit(@Nonnull Cursor cursor, int level, @Nonnull Node<?> newRight, long newRightMinKey, int newRightCount) {
		Node<?> right = newRight;
		long rightMinKey = newRightMinKey;
		int rightCount = newRightCount;
		while (true) {
			if (level < 0) {
				// the split reached the root - grow a new root above the two halves
				final Node<?> oldRoot = getRoot();
				if (oldRoot == null) {
					// a split can only propagate up from an existing leaf, so the root is necessarily present
					throw new GenericEvitaInternalError(
						"Split propagated above a missing root!",
						"Inconsistent lookup state!"
					);
				}
				final InternalNode newRoot = new InternalNode(true);
				final Node<?>[] children = newRoot.getChildrenForUpdate();
				final int[] counts = newRoot.getCountsForUpdate();
				final long[] separators = newRoot.getSeparatorsForUpdate();
				children[0] = oldRoot;
				children[1] = right;
				counts[0] = subtreeCount(oldRoot);
				counts[1] = rightCount;
				separators[0] = rightMinKey;
				newRoot.setChildCount(2);
				// the new root replaces the old one, but the old root remains its first child, so do NOT drop the
				// old root's layer here - simply publish the new root reference
				this.root.set(newRoot);
				return;
			}
			final InternalNode parent = cursor.path[level];
			final int ci = cursor.idx[level];
			// the existing child's stored count was already incremented for the inserted record; shed the moved part
			parent.getCountsForUpdate()[ci] -= rightCount;
			insertIntoInternal(parent, ci, right, rightMinKey, rightCount);
			if (parent.getChildCount() <= this.blockSize) {
				return;
			}
			// parent overflowed - split it and continue up the cursor
			final long[] promotedKey = new long[1];
			final int[] promotedCount = new int[1];
			right = splitInternal(parent, promotedKey, promotedCount);
			rightMinKey = promotedKey[0];
			rightCount = promotedCount[0];
			level--;
		}
	}

	/**
	 * Inserts `newChild` (separator `minKey`, subtree count `childCount`) into `parent` immediately after child `ci`.
	 */
	private static void insertIntoInternal(@Nonnull InternalNode parent, int ci, @Nonnull Node<?> newChild, long minKey, int childCount) {
		final int parentChildCount = parent.getChildCount();
		final Node<?>[] children = parent.getChildrenForUpdate();
		final int[] counts = parent.getCountsForUpdate();
		final long[] separators = parent.getSeparatorsForUpdate();
		final int target = ci + 1;
		System.arraycopy(children, target, children, target + 1, parentChildCount - target);
		System.arraycopy(counts, target, counts, target + 1, parentChildCount - target);
		// the separator between child ci and the new child goes at index ci, shifting the rest right
		System.arraycopy(separators, ci, separators, ci + 1, parentChildCount - 1 - ci);
		children[target] = newChild;
		counts[target] = childCount;
		separators[ci] = minKey;
		parent.setChildCount(parentChildCount + 1);
	}

	/**
	 * Splits an overflowing internal node, returning the new right node and reporting (through the single-element
	 * out-parameters) the promoted separator key and the right node's subtree count.
	 */
	@Nonnull
	private static InternalNode splitInternal(
		@Nonnull InternalNode node,
		@Nonnull long[] promotedKey,
		@Nonnull int[] promotedCount
	) {
		final int total = node.getChildCount();
		final int leftCount = total / 2;
		final int rightCount = total - leftCount;
		// the offspring node participates in the transactional layer so that any later in-savepoint
		// mutation routes through a snapshot-able layer and can be rolled back per-entity
		final InternalNode right = new InternalNode(true);
		final Node<?>[] nodeChildren = node.getChildrenForUpdate();
		final int[] nodeCounts = node.getCountsForUpdate();
		final long[] nodeSeparators = node.getSeparatorsForUpdate();
		final Node<?>[] rightChildren = right.getChildrenForUpdate();
		final int[] rightCounts = right.getCountsForUpdate();
		final long[] rightSeparators = right.getSeparatorsForUpdate();
		System.arraycopy(nodeChildren, leftCount, rightChildren, 0, rightCount);
		System.arraycopy(nodeCounts, leftCount, rightCounts, 0, rightCount);
		// right keeps the separators strictly inside its child range (rightCount - 1 of them)
		System.arraycopy(nodeSeparators, leftCount, rightSeparators, 0, rightCount - 1);
		right.setChildCount(rightCount);
		// the separator between the two halves is promoted to the parent (not kept in either node)
		promotedKey[0] = nodeSeparators[leftCount - 1];
		int rightSubtreeCount = 0;
		for (int i = 0; i < rightCount; i++) {
			rightSubtreeCount += rightCounts[i];
		}
		promotedCount[0] = rightSubtreeCount;
		// clear the moved slots in the left node
		Arrays.fill(nodeChildren, leftCount, total, null);
		Arrays.fill(nodeCounts, leftCount, total, 0);
		Arrays.fill(nodeSeparators, leftCount - 1, total - 1, 0L);
		node.setChildCount(leftCount);
		return right;
	}

	/**
	 * Mints a fresh order-key for a new right container inserted immediately after the container at the bottom of the
	 * cursor, re-spacing the whole key space when the gap to the next container is exhausted.
	 *
	 * @throws GenericEvitaInternalError when the configured order-key gap is too small to subdivide between two
	 *                                   adjacent containers even after a full re-spacing (a misconfiguration that
	 *                                   would otherwise mint a colliding order-key)
	 */
	private long mintOrderKey(@Nonnull LeafNode container, @Nonnull Cursor cursor, @Nonnull OrderKeyConsumer assignments) {
		final long nextKey = nextContainerKey(cursor);
		if (nextKey == NO_NEXT_KEY) {
			// rightmost container - append a full gap to the right
			long candidate = container.getOrderKey() + this.orderKeyGap;
			if (candidate <= container.getOrderKey()) {
				respaceOrderKeys(assignments);
				candidate = container.getOrderKey() + this.orderKeyGap;
			}
			return candidate;
		}
		long gap = nextKey - container.getOrderKey();
		if (gap < 2) {
			respaceOrderKeys(assignments);
			gap = nextContainerKey(cursor) - container.getOrderKey();
			if (gap < 2) {
				// even an evenly re-spaced key space cannot subdivide a single gap - the configured order-key gap
				// is too small to host another container between two neighbours, which would mint a colliding key
				throw new GenericEvitaInternalError(
					"Order-key gap " + this.orderKeyGap + " is too small to subdivide between adjacent containers!",
					"Inconsistent lookup state!"
				);
			}
		}
		return container.getOrderKey() + gap / 2;
	}

	/**
	 * Returns the order-key of the container immediately following the one at the bottom of the cursor, or
	 * {@link #NO_NEXT_KEY} when it is the rightmost container. Found by walking up the cursor to the first ancestor
	 * that has a right sibling subtree (whose separator is that subtree's minimum order-key).
	 */
	private static long nextContainerKey(@Nonnull Cursor cursor) {
		for (int level = cursor.depth - 1; level >= 0; level--) {
			final InternalNode node = cursor.path[level];
			final int ci = cursor.idx[level];
			if (ci < node.getChildCount() - 1) {
				return node.getSeparators()[ci];
			}
		}
		return NO_NEXT_KEY;
	}

	/**
	 * Reassigns every container's order-key to an evenly spaced value (preserving logical order) and rebuilds all
	 * internal separators. Rare; `O(#containers)`. Inside a transaction this decouples every node it touches into the
	 * transactional layer - acceptable because re-spacing is rare.
	 */
	private void respaceOrderKeys(@Nonnull OrderKeyConsumer assignments) {
		final Node<?> theRoot = getRoot();
		if (theRoot != null) {
			reassignKeys(theRoot, new long[]{0L}, assignments);
			recomputeSeparators(theRoot);
		}
	}

	/**
	 * Reassigns container order-keys in logical (in-order) sequence, spaced by {@link #orderKeyGap}, reporting each
	 * record's new order-key so the value index stays coherent (re-spacing re-stamps every record).
	 */
	private void reassignKeys(@Nonnull Node<?> node, @Nonnull long[] counter, @Nonnull OrderKeyConsumer assignments) {
		if (node instanceof final LeafNode leaf) {
			final long newKey = counter[0] * this.orderKeyGap;
			leaf.setOrderKey(newKey);
			counter[0]++;
			final int count = leaf.getCount();
			final int[] recordIds = leaf.getRecordIds();
			for (int i = 0; i < count; i++) {
				assignments.accept(recordIds[i], newKey);
			}
		} else {
			final InternalNode internal = (InternalNode) node;
			final int childCount = internal.getChildCount();
			final Node<?>[] children = internal.getChildren();
			for (int i = 0; i < childCount; i++) {
				reassignKeys(children[i], counter, assignments);
			}
		}
	}

	/**
	 * Recomputes the separators of every internal node in the subtree rooted at `node` from the current container
	 * order-keys, returning the minimum order-key in the subtree.
	 */
	private static long recomputeSeparators(@Nonnull Node<?> node) {
		if (node instanceof final LeafNode leaf) {
			return leaf.getOrderKey();
		}
		final InternalNode internal = (InternalNode) node;
		final int childCount = internal.getChildCount();
		final Node<?>[] children = internal.getChildren();
		final long min = recomputeSeparators(children[0]);
		final long[] separators = internal.getSeparatorsForUpdate();
		for (int i = 1; i < childCount; i++) {
			separators[i - 1] = recomputeSeparators(children[i]);
		}
		return min;
	}

	/**
	 * Removes an emptied container from the tree and restores B+ balance. The container is unlinked from its immediate
	 * parent (keeping separators exact), then internal-node underflow is repaired up the cursor by {@link #consolidate}.
	 *
	 * Crucially this performs INTERNAL rebalancing only — it moves whole child SUBTREES between internal siblings (steal)
	 * or fuses two internal siblings (merge), and never moves records between leaf containers. Container membership is
	 * therefore untouched, so no record's order-key is ever re-stamped (no {@link OrderKeyConsumer} traffic on delete).
	 * The price is that under-full (sparse) containers are left in place — a memory-only effect, identical to the legacy
	 * behaviour, and recompacted whenever the tree is rebuilt from a flat array via {@link #bulkLoad}.
	 */
	private void removeEmptyContainer(@Nonnull Cursor cursor) {
		if (cursor.depth == 0) {
			// the emptied container was the root - the tree is now empty
			setRoot(null);
			return;
		}
		// unlink the emptied container from its immediate parent (separators stay exact - see removeChildAt)
		final int leafLevel = cursor.depth - 1;
		final InternalNode parent = cursor.path[leafLevel];
		final int ci = cursor.idx[leafLevel];
		// capture the child before the structural mutation; once detached it is unreachable from the committed root, so
		// its transactional layer (touched while emptying it) must be dropped here or the commit sweep flags it stale
		final Node<?> removedChild = parent.getChildren()[ci];
		removeChildAt(parent, ci);
		dropLayerIfPresent(removedChild);
		// removing the HEAD child (index 0) raises this subtree's minimum order-key, so the separator that borders this
		// subtree on its left in some ancestor is now stale - refresh it up the cursor BEFORE rebalancing. This must run
		// even when no underflow follows, and it also feeds consolidate's merge `bridge` (which reads that separator).
		if (ci == 0) {
			propagateNewMinimumUp(cursor, leafLevel);
		}
		// repair internal underflow upward
		consolidate(cursor, leafLevel);
	}

	/**
	 * Refreshes the ancestor separator that borders the subtree rooted at `cursor.path[fromLevel]` after that subtree's
	 * minimum order-key changed (its head child was removed). Walks up the cursor to the first ancestor where the
	 * subtree is NOT the leftmost child and rewrites that ancestor's bordering separator to the subtree's new minimum;
	 * if every ancestor was entered through its leftmost child the subtree holds the global minimum and no separator
	 * borders it on the left. Keeps separators EXACT (the consistency invariant) and feeds the merge bridge separator.
	 *
	 * @param cursor    the descent cursor captured to the removed container
	 * @param fromLevel the level of the internal node whose subtree minimum just rose
	 */
	private static void propagateNewMinimumUp(@Nonnull Cursor cursor, int fromLevel) {
		final long newMinimum = minOrderKey(cursor.path[fromLevel]);
		for (int level = fromLevel; level >= 1; level--) {
			final int indexInParent = cursor.idx[level - 1];
			if (indexInParent > 0) {
				cursor.path[level - 1].getSeparatorsForUpdate()[indexInParent - 1] = newMinimum;
				return;
			}
		}
	}

	/**
	 * Repairs internal-node underflow from `level` upward after a child was removed at that level. Each underflowing
	 * non-root internal first tries to STEAL a whole child subtree from an adjacent internal sibling that has one to
	 * spare, and otherwise MERGEs with an adjacent sibling (which removes a child from the grandparent and propagates the
	 * underflow check one level up). The root is collapsed onto its only child when it drops to a single child — a
	 * uniform, all-paths height reduction that keeps every leaf at equal depth. Only whole subtrees move between
	 * internals, so leaf container membership — and hence every record's order-key — is left untouched.
	 *
	 * @param cursor the descent cursor captured to the just-removed container
	 * @param level  the level (internal node `cursor.path[level]`) that just lost a child and may now underflow
	 */
	private void consolidate(@Nonnull Cursor cursor, int level) {
		while (level >= 0) {
			final InternalNode node = cursor.path[level];
			if (level == 0) {
				// the root has no minimum-occupancy floor; only collapse it when it degenerates to a single child
				final int rootChildCount = node.getChildCount();
				if (rootChildCount == 1) {
					setRoot(node.getChildren()[0]);
				} else if (rootChildCount == 0) {
					setRoot(null);
				}
				return;
			}
			if (node.getChildCount() >= this.minChildren) {
				// no underflow - the structure above is unaffected
				return;
			}
			final InternalNode grandParent = cursor.path[level - 1];
			final int gi = cursor.idx[level - 1];
			// 1) try to steal a child subtree from the left sibling
			if (gi > 0) {
				final InternalNode left = (InternalNode) grandParent.getChildren()[gi - 1];
				if (left.getChildCount() > this.minChildren) {
					stealChildFromLeft(node, left, grandParent, gi);
					return;
				}
			}
			// 2) try to steal a child subtree from the right sibling
			if (gi < grandParent.getChildCount() - 1) {
				final InternalNode right = (InternalNode) grandParent.getChildren()[gi + 1];
				if (right.getChildCount() > this.minChildren) {
					stealChildFromRight(node, right, grandParent, gi);
					return;
				}
			}
			// 3) no sibling can spare a child - merge with one (prefer the left), then re-check the grandparent
			if (gi > 0) {
				final InternalNode left = (InternalNode) grandParent.getChildren()[gi - 1];
				mergeInternals(left, node, grandParent, gi);
				dropLayerIfPresent(node);
			} else {
				final InternalNode right = (InternalNode) grandParent.getChildren()[gi + 1];
				mergeInternals(node, right, grandParent, gi + 1);
				dropLayerIfPresent(right);
			}
			level--;
		}
	}

	/**
	 * Moves the LAST child subtree of `left` to the FRONT of the underflowing `node`, fixing up the bordering separator
	 * in `grandParent` and the per-child counts. `gi` is the index of `node` within `grandParent` (`> 0`).
	 */
	private static void stealChildFromLeft(
		@Nonnull InternalNode node,
		@Nonnull InternalNode left,
		@Nonnull InternalNode grandParent,
		int gi
	) {
		final int nc = node.getChildCount();
		final int lc = left.getChildCount();
		final Node<?> moved = left.getChildren()[lc - 1];
		final int movedCount = left.getCounts()[lc - 1];
		final long movedMinKey = minOrderKey(moved);
		// the current separator before `node` is the min order-key of node's old first child
		final long oldNodeMinKey = grandParent.getSeparators()[gi - 1];
		// prepend the moved subtree to node
		final Node<?>[] nodeChildren = node.getChildrenForUpdate();
		final int[] nodeCounts = node.getCountsForUpdate();
		final long[] nodeSeparators = node.getSeparatorsForUpdate();
		System.arraycopy(nodeChildren, 0, nodeChildren, 1, nc);
		System.arraycopy(nodeCounts, 0, nodeCounts, 1, nc);
		System.arraycopy(nodeSeparators, 0, nodeSeparators, 1, nc - 1);
		nodeChildren[0] = moved;
		nodeCounts[0] = movedCount;
		nodeSeparators[0] = oldNodeMinKey;
		node.setChildCount(nc + 1);
		// drop the moved subtree from left's tail
		final Node<?>[] leftChildren = left.getChildrenForUpdate();
		final int[] leftCounts = left.getCountsForUpdate();
		final long[] leftSeparators = left.getSeparatorsForUpdate();
		leftChildren[lc - 1] = null;
		leftCounts[lc - 1] = 0;
		leftSeparators[lc - 2] = 0L;
		left.setChildCount(lc - 1);
		// the separator before node now borders the moved subtree
		grandParent.getSeparatorsForUpdate()[gi - 1] = movedMinKey;
		final int[] gpCounts = grandParent.getCountsForUpdate();
		gpCounts[gi - 1] -= movedCount;
		gpCounts[gi] += movedCount;
	}

	/**
	 * Moves the FIRST child subtree of `right` to the END of the underflowing `node`, fixing up the bordering separator
	 * in `grandParent` and the per-child counts. `gi` is the index of `node` within `grandParent` (`right` is at `gi+1`).
	 */
	private static void stealChildFromRight(
		@Nonnull InternalNode node,
		@Nonnull InternalNode right,
		@Nonnull InternalNode grandParent,
		int gi
	) {
		final int nc = node.getChildCount();
		final int rc = right.getChildCount();
		final Node<?> moved = right.getChildren()[0];
		final int movedCount = right.getCounts()[0];
		final long movedMinKey = minOrderKey(moved);
		// after removing right's first child, right's new minimum is the min order-key of its old second child
		final long newRightMinKey = right.getSeparators()[0];
		// append the moved subtree to node's tail
		final Node<?>[] nodeChildren = node.getChildrenForUpdate();
		final int[] nodeCounts = node.getCountsForUpdate();
		final long[] nodeSeparators = node.getSeparatorsForUpdate();
		nodeChildren[nc] = moved;
		nodeCounts[nc] = movedCount;
		nodeSeparators[nc - 1] = movedMinKey;
		node.setChildCount(nc + 1);
		// drop the moved subtree from right's head (shift the remainder left)
		final Node<?>[] rightChildren = right.getChildrenForUpdate();
		final int[] rightCounts = right.getCountsForUpdate();
		final long[] rightSeparators = right.getSeparatorsForUpdate();
		System.arraycopy(rightChildren, 1, rightChildren, 0, rc - 1);
		System.arraycopy(rightCounts, 1, rightCounts, 0, rc - 1);
		System.arraycopy(rightSeparators, 1, rightSeparators, 0, rc - 2);
		rightChildren[rc - 1] = null;
		rightCounts[rc - 1] = 0;
		rightSeparators[rc - 2] = 0L;
		right.setChildCount(rc - 1);
		// the separator after node now borders right's new minimum
		grandParent.getSeparatorsForUpdate()[gi] = newRightMinKey;
		final int[] gpCounts = grandParent.getCountsForUpdate();
		gpCounts[gi] += movedCount;
		gpCounts[gi + 1] -= movedCount;
	}

	/**
	 * Fuses `absorbed` into `survivor` (children appended in logical order) and unlinks `absorbed` (at `absorbedIndex`,
	 * always `= survivorIndex + 1`) from `grandParent`, folding its subtree count into the survivor's grandparent slot.
	 * The bridge separator between the two halves is the min order-key of the absorbed subtree (taken from the
	 * grandparent's exact separator). The caller drops the absorbed node's transactional layer.
	 */
	private static void mergeInternals(
		@Nonnull InternalNode survivor,
		@Nonnull InternalNode absorbed,
		@Nonnull InternalNode grandParent,
		int absorbedIndex
	) {
		final int sc = survivor.getChildCount();
		final int ac = absorbed.getChildCount();
		final long bridge = grandParent.getSeparators()[absorbedIndex - 1];
		final int absorbedCount = grandParent.getCounts()[absorbedIndex];
		// append absorbed's children / counts / separators onto the survivor
		final Node<?>[] sChildren = survivor.getChildrenForUpdate();
		final int[] sCounts = survivor.getCountsForUpdate();
		final long[] sSeparators = survivor.getSeparatorsForUpdate();
		final Node<?>[] aChildren = absorbed.getChildren();
		final int[] aCounts = absorbed.getCounts();
		final long[] aSeparators = absorbed.getSeparators();
		System.arraycopy(aChildren, 0, sChildren, sc, ac);
		System.arraycopy(aCounts, 0, sCounts, sc, ac);
		sSeparators[sc - 1] = bridge;
		System.arraycopy(aSeparators, 0, sSeparators, sc, ac - 1);
		survivor.setChildCount(sc + ac);
		// unlink the absorbed slot from the grandparent, dropping the separator that bordered it on the left and folding
		// its subtree count into the survivor's slot
		final int gcc = grandParent.getChildCount();
		final Node<?>[] gChildren = grandParent.getChildrenForUpdate();
		final int[] gCounts = grandParent.getCountsForUpdate();
		final long[] gSeparators = grandParent.getSeparatorsForUpdate();
		gCounts[absorbedIndex - 1] += absorbedCount;
		System.arraycopy(gChildren, absorbedIndex + 1, gChildren, absorbedIndex, gcc - absorbedIndex - 1);
		System.arraycopy(gCounts, absorbedIndex + 1, gCounts, absorbedIndex, gcc - absorbedIndex - 1);
		System.arraycopy(gSeparators, absorbedIndex, gSeparators, absorbedIndex - 1, gcc - absorbedIndex - 1);
		final int newGcc = gcc - 1;
		grandParent.setChildCount(newGcc);
		gChildren[newGcc] = null;
		gCounts[newGcc] = 0;
		gSeparators[newGcc - 1] = 0L;
	}

	/**
	 * Drops the transactional diff layer of `node` when one is currently open. Used when a node is detached from the
	 * tree mid-transaction (container emptied, internal node collapsed or spliced out): the node is no longer reachable
	 * from the committed root, so its layer would never be swept by the commit walk and would be reported stale.
	 */
	private static void dropLayerIfPresent(@Nonnull Node<?> node) {
		if (Transaction.getTransactionalMemoryLayerIfExists(node) != null) {
			node.removeLayer();
		}
	}

	/**
	 * Removes the child at index `ci` from `internal`, keeping every remaining separator EXACT. The separator dropped is
	 * the one on the LEFT of the removed child (`ci - 1`, equal to the removed child's own min order-key), so the
	 * separator that was on its right slides over to border the surviving left neighbour — which is precisely that
	 * neighbour's correct min-key boundary. When removing the head child (`ci == 0`) the head separator (`0`) is dropped
	 * instead (the new head child needs no separator before it).
	 */
	private static void removeChildAt(@Nonnull InternalNode internal, int ci) {
		final int childCount = internal.getChildCount();
		final Node<?>[] children = internal.getChildrenForUpdate();
		final int[] counts = internal.getCountsForUpdate();
		final long[] separators = internal.getSeparatorsForUpdate();
		System.arraycopy(children, ci + 1, children, ci, childCount - ci - 1);
		System.arraycopy(counts, ci + 1, counts, ci, childCount - ci - 1);
		if (childCount >= 2) {
			final int separatorIndex = ci > 0 ? ci - 1 : 0;
			System.arraycopy(separators, separatorIndex + 1, separators, separatorIndex, childCount - separatorIndex - 2);
		}
		final int newChildCount = childCount - 1;
		internal.setChildCount(newChildCount);
		children[newChildCount] = null;
		counts[newChildCount] = 0;
		if (newChildCount >= 1) {
			separators[newChildCount - 1] = 0L;
		}
	}

	/**
	 * Returns the number of record ids held in the subtree rooted at `node`.
	 */
	private static int subtreeCount(@Nonnull Node<?> node) {
		if (node instanceof final LeafNode leaf) {
			return leaf.getCount();
		}
		final InternalNode internal = (InternalNode) node;
		final int childCount = internal.getChildCount();
		final int[] counts = internal.getCounts();
		int sum = 0;
		for (int i = 0; i < childCount; i++) {
			sum += counts[i];
		}
		return sum;
	}

	/**
	 * Returns the slot index of `recordId` within `container`.
	 */
	private static int indexInContainer(@Nonnull LeafNode container, int recordId) {
		final int count = container.getCount();
		final int[] recordIds = container.getRecordIds();
		for (int i = 0; i < count; i++) {
			if (recordIds[i] == recordId) {
				return i;
			}
		}
		throw new GenericEvitaInternalError(
			"Record id " + recordId + " not found in its routed container!",
			"Inconsistent lookup state!"
		);
	}

	/**
	 * Recursively flattens the subtree rooted at `node` into `result`, advancing the single-element position holder.
	 */
	private static void flattenInto(@Nonnull Node<?> node, @Nonnull int[] result, @Nonnull int[] positionHolder) {
		if (node instanceof final LeafNode leaf) {
			System.arraycopy(leaf.getRecordIds(), 0, result, positionHolder[0], leaf.getCount());
			positionHolder[0] += leaf.getCount();
		} else {
			final InternalNode internal = (InternalNode) node;
			final int childCount = internal.getChildCount();
			final Node<?>[] children = internal.getChildren();
			for (int i = 0; i < childCount; i++) {
				flattenInto(children[i], result, positionHolder);
			}
		}
	}

	/**
	 * Drops the memoized flattened array after a mutation.
	 */
	private void invalidateMemoizedState() {
		this.memoizedArray = null;
	}

	/**
	 * Mutable descent cursor capturing the internal-node path from the root to a leaf (plus the in-container offset
	 * for positional descents). Reused per operation; never escapes the structure.
	 */
	private static final class Cursor {
		/**
		 * Internal nodes along the descent path, `path[0]` being the root.
		 */
		@Nonnull final InternalNode[] path = new InternalNode[MAX_HEIGHT];
		/**
		 * The child index taken at each level of {@link #path}.
		 */
		@Nonnull final int[] idx = new int[MAX_HEIGHT];
		/**
		 * Number of internal levels captured (0 when the root is a leaf).
		 */
		int depth;
		/**
		 * In-container offset reached by a positional descent.
		 */
		int leafOffset;

		/**
		 * Pushes one descent step onto the cursor.
		 */
		void push(@Nonnull InternalNode node, int childIndex) {
			this.path[this.depth] = node;
			this.idx[this.depth] = childIndex;
			this.depth++;
		}
	}

	/**
	 * Base type for both node types. Each node is its own {@link TransactionalLayerProducer} (its diff layer is an
	 * instance of the very same concrete class): a node opens a diff copy of itself in the per-transaction layer on
	 * first write, exactly like the reference B+ trees. The recursive `N` type binds the layer / copy type to the
	 * concrete node type so the transactional accessors stay strongly typed.
	 */
	interface Node<N extends Node<N>> extends TransactionalLayerProducer<N, N>, Serializable {
	}

	/**
	 * Leaf node = a **container** holding up to {@link #DEFAULT_BLOCK_SIZE} record ids in logical order, identified by a
	 * `long` order-key.
	 *
	 * Mirrors `BPlusLeafTreeNode`: each mutable field (`orderKey`, `recordIds`, `count`) is copied-on-write into the
	 * per-transaction layer on first write through the `...ForUpdate()` accessors; read-only accessors read from the
	 * layer if present.
	 */
	static final class LeafNode implements Node<LeafNode>, Snapshotable<LeafNode.LeafNodeMemento> {
		@Serial private static final long serialVersionUID = -2510718704128926730L;
		@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
		/**
		 * Indicates whether this instance is permitted to create and use transactional layers. The tree nodes use
		 * themselves (the same class) as their transactional memory layer; were this layer to also use transactional
		 * memory it would create an infinite loop. This flag prevents that behavior - it is `false` for nodes created
		 * mid-transaction during splits (which mutate in place within the txn and are rebuilt as participating nodes
		 * on commit) and `true` for participating nodes.
		 */
		private final boolean transactionalLayer;
		/**
		 * Stable order-key identifying this container's slot in the logical order.
		 */
		private long orderKey;
		/**
		 * Record ids in logical order (only the first {@link #count} slots are valid).
		 */
		private int[] recordIds;
		/**
		 * Number of valid record ids in this container.
		 */
		private int count;

		/**
		 * Creates a new empty container.
		 *
		 * @param transactionalLayer whether this node participates in the transactional memory layer
		 */
		@SuppressWarnings("CheckForOutOfMemoryOnLargeArrayAllocation")
		LeafNode(boolean transactionalLayer) {
			this.recordIds = new int[DEFAULT_BLOCK_SIZE + 1];
			this.count = 0;
			this.orderKey = 0L;
			this.transactionalLayer = transactionalLayer;
		}

		/**
		 * Internal constructor used by {@link #createLayer()} and {@link #createCopyWithMergedTransactionalMemory}.
		 *
		 * @param orderKey           the container order-key
		 * @param recordIds          the record id array (used directly, not copied)
		 * @param count              the number of valid record ids
		 * @param transactionalLayer whether this node participates in the transactional memory layer
		 */
		LeafNode(long orderKey, @Nonnull int[] recordIds, int count, boolean transactionalLayer) {
			this.orderKey = orderKey;
			this.recordIds = recordIds;
			this.count = count;
			this.transactionalLayer = transactionalLayer;
		}

		/**
		 * Returns the container order-key (read-only view).
		 */
		long getOrderKey() {
			final LeafNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			return layer == null ? this.orderKey : layer.orderKey;
		}

		/**
		 * Sets the container order-key, decoupling into the transactional layer when present.
		 */
		void setOrderKey(long orderKey) {
			final LeafNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				this.orderKey = orderKey;
			} else {
				layer.orderKey = orderKey;
			}
		}

		/**
		 * Returns the number of valid record ids (read-only view).
		 */
		int getCount() {
			final LeafNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			return layer == null ? this.count : layer.count;
		}

		/**
		 * Sets the number of valid record ids, decoupling into the transactional layer when present.
		 */
		void setCount(int count) {
			final LeafNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				this.count = count;
			} else {
				layer.count = count;
			}
		}

		/**
		 * Returns the record id array for READ-ONLY purposes (the layer copy when present).
		 */
		@Nonnull
		int[] getRecordIds() {
			final LeafNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			return layer == null ? this.recordIds : layer.recordIds;
		}

		/**
		 * Returns the record id array for UPDATE, decoupling an independent copy into the transactional layer on first
		 * write so the committed array stays untouched.
		 */
		@Nonnull
		int[] getRecordIdsForUpdate() {
			final LeafNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				return this.recordIds;
			} else {
				//noinspection ArrayEquality
				if (layer.recordIds == this.recordIds) {
					layer.recordIds = new int[this.recordIds.length];
					System.arraycopy(this.recordIds, 0, layer.recordIds, 0, this.recordIds.length);
				}
				return layer.recordIds;
			}
		}

		@Override
		public LeafNode createLayer() {
			return new LeafNode(this.orderKey, this.recordIds, this.count, false);
		}

		/**
		 * Captures this layer's revertable copy-on-write state for a per-entity savepoint. The mutable
		 * fields are the order-key, the record-id array and the count; the record-id array is cloned (the ints are
		 * value types) so that a later mutation, or a repeated {@link #restore}, cannot corrupt the memento.
		 *
		 * @return an independent snapshot of this container's order-key, record ids and count
		 */
		@Nonnull
		@Override
		public LeafNodeMemento snapshot() {
			return new LeafNodeMemento(this.orderKey, this.recordIds.clone(), this.count);
		}

		/**
		 * Restores the state captured by {@link #snapshot}. A fresh clone of the memento's record-id array is installed
		 * so the memento stays reusable for a repeated restore.
		 *
		 * @param memento the state previously captured by {@link #snapshot}
		 */
		@Override
		public void restore(@Nonnull LeafNodeMemento memento) {
			this.orderKey = memento.orderKey();
			this.recordIds = memento.recordIds().clone();
			this.count = memento.count();
		}

		@Override
		public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			transactionalLayer.removeTransactionalMemoryLayer(this);
		}

		@Nonnull
		@Override
		public LeafNode createCopyWithMergedTransactionalMemory(
			@Nullable LeafNode leafLayer,
			@Nonnull TransactionalLayerMaintainer transactionalLayer
		) {
			final long theOrderKey;
			final int[] theRecordIds;
			final int theCount;
			if (leafLayer == null) {
				theOrderKey = this.orderKey;
				theRecordIds = this.recordIds;
				theCount = this.count;
			} else {
				theOrderKey = leafLayer.orderKey;
				theRecordIds = leafLayer.recordIds;
				theCount = leafLayer.count;
			}
			// primitive int record ids never carry their own transactional layer, so there is nothing to merge
			if (leafLayer != null) {
				return new LeafNode(theOrderKey, theRecordIds, theCount, true);
			} else if (!this.transactionalLayer) {
				// nodes created during splits are built with transactionalLayer=false so they do not allocate STM
				// layers mid-transaction; on commit they must be rebuilt as participating (transactionalLayer=true)
				// nodes so subsequent transactions can layer changes over them
				return new LeafNode(theOrderKey, theRecordIds, theCount, true);
			} else {
				return this;
			}
		}

		@Override
		public String toString() {
			final int[] theRecordIds = getRecordIds();
			final int theCount = getCount();
			final StringBuilder sb = new StringBuilder(8 + (theCount << 2));
			sb.append('[');
			for (int i = 0; i < theCount; i++) {
				if (i > 0) {
					sb.append(", ");
				}
				sb.append(theRecordIds[i]);
			}
			sb.append(']');
			return sb.toString();
		}

		/**
		 * Immutable savepoint memento of a container's copy-on-write state. The record-id array is a private clone
		 * owned by the memento (see {@link #snapshot}); the order-key and count are primitive value types.
		 *
		 * @param orderKey  the container order-key
		 * @param recordIds clone of the record-id array
		 * @param count     the number of valid record ids
		 */
		record LeafNodeMemento(
			long orderKey,
			@Nonnull int[] recordIds,
			int count
		) {
		}
	}

	/**
	 * Internal node routing by `long` order-key separators and carrying the record-id count of each child subtree.
	 * Holds up to {@link #DEFAULT_BLOCK_SIZE} children and `childCount - 1` separators (`separators[i]` is the minimum
	 * order-key found in `children[i + 1]`).
	 *
	 * Mirrors `BPlusInternalTreeNode`: each mutable field (`children`, `separators`, `counts`, `childCount`) is
	 * copied-on-write into the per-transaction layer on first write through the `...ForUpdate()` accessors; read-only
	 * accessors read from the layer if present.
	 */
	static final class InternalNode implements Node<InternalNode>, Snapshotable<InternalNode.InternalNodeMemento> {
		@Serial private static final long serialVersionUID = 1791772842933035170L;
		@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
		/**
		 * Indicates whether this instance is permitted to create and use transactional layers (see the matching field
		 * on {@link LeafNode}).
		 */
		private final boolean transactionalLayer;
		/**
		 * Child nodes (only the first {@link #childCount} slots are valid).
		 */
		private Node<?>[] children;
		/**
		 * Separator order-keys; `separators[i]` is the minimum order-key in `children[i + 1]`'s subtree.
		 */
		private long[] separators;
		/**
		 * Record-id count of each child subtree, aligned with {@link #children}.
		 */
		private int[] counts;
		/**
		 * Number of valid children.
		 */
		private int childCount;

		/**
		 * Creates a new empty internal node.
		 *
		 * @param transactionalLayer whether this node participates in the transactional memory layer
		 */
		@SuppressWarnings("CheckForOutOfMemoryOnLargeArrayAllocation")
		InternalNode(boolean transactionalLayer) {
			this.children = new Node<?>[DEFAULT_BLOCK_SIZE + 1];
			this.separators = new long[DEFAULT_BLOCK_SIZE];
			this.counts = new int[DEFAULT_BLOCK_SIZE + 1];
			this.childCount = 0;
			this.transactionalLayer = transactionalLayer;
		}

		/**
		 * Internal constructor used by {@link #createLayer()} and {@link #createCopyWithMergedTransactionalMemory}.
		 *
		 * @param children           the children array (used directly, not copied)
		 * @param separators         the separators array (used directly, not copied)
		 * @param counts             the per-child subtree counts array (used directly, not copied)
		 * @param childCount         the number of valid children
		 * @param transactionalLayer whether this node participates in the transactional memory layer
		 */
		InternalNode(@Nonnull Node<?>[] children, @Nonnull long[] separators, @Nonnull int[] counts, int childCount, boolean transactionalLayer) {
			this.children = children;
			this.separators = separators;
			this.counts = counts;
			this.childCount = childCount;
			this.transactionalLayer = transactionalLayer;
		}

		/**
		 * Returns the number of valid children (read-only view).
		 */
		int getChildCount() {
			final InternalNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			return layer == null ? this.childCount : layer.childCount;
		}

		/**
		 * Sets the number of valid children, decoupling into the transactional layer when present.
		 */
		void setChildCount(int childCount) {
			final InternalNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				this.childCount = childCount;
			} else {
				layer.childCount = childCount;
			}
		}

		/**
		 * Returns the children array for READ-ONLY purposes (the layer copy when present).
		 */
		@Nonnull
		Node<?>[] getChildren() {
			final InternalNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			return layer == null ? this.children : layer.children;
		}

		/**
		 * Returns the children array for UPDATE, decoupling an independent copy into the transactional layer on first
		 * write.
		 */
		@Nonnull
		Node<?>[] getChildrenForUpdate() {
			final InternalNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				return this.children;
			} else {
				//noinspection ArrayEquality
				if (layer.children == this.children) {
					layer.children = new Node<?>[this.children.length];
					System.arraycopy(this.children, 0, layer.children, 0, this.children.length);
				}
				return layer.children;
			}
		}

		/**
		 * Returns the separators array for READ-ONLY purposes (the layer copy when present).
		 */
		@Nonnull
		long[] getSeparators() {
			final InternalNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			return layer == null ? this.separators : layer.separators;
		}

		/**
		 * Returns the separators array for UPDATE, decoupling an independent copy into the transactional layer on
		 * first write.
		 */
		@Nonnull
		long[] getSeparatorsForUpdate() {
			final InternalNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				return this.separators;
			} else {
				//noinspection ArrayEquality
				if (layer.separators == this.separators) {
					layer.separators = new long[this.separators.length];
					System.arraycopy(this.separators, 0, layer.separators, 0, this.separators.length);
				}
				return layer.separators;
			}
		}

		/**
		 * Returns the per-child subtree counts array for READ-ONLY purposes (the layer copy when present).
		 */
		@Nonnull
		int[] getCounts() {
			final InternalNode layer = this.transactionalLayer ?
				Transaction.getTransactionalMemoryLayerIfExists(this) : null;
			return layer == null ? this.counts : layer.counts;
		}

		/**
		 * Returns the per-child subtree counts array for UPDATE, decoupling an independent copy into the transactional
		 * layer on first write.
		 */
		@Nonnull
		int[] getCountsForUpdate() {
			final InternalNode layer = this.transactionalLayer ?
				Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
			if (layer == null) {
				return this.counts;
			} else {
				//noinspection ArrayEquality
				if (layer.counts == this.counts) {
					layer.counts = new int[this.counts.length];
					System.arraycopy(this.counts, 0, layer.counts, 0, this.counts.length);
				}
				return layer.counts;
			}
		}

		@Override
		public InternalNode createLayer() {
			return new InternalNode(this.children, this.separators, this.counts, this.childCount, false);
		}

		/**
		 * Captures this layer's revertable copy-on-write state for a per-entity savepoint. The mutable
		 * fields are the children, separators and counts arrays and the child count; all three arrays are cloned
		 * (shallow for children — the child nodes own their own transactional layers and are snapshotted independently;
		 * the separators and counts are value types) so that a later mutation, or a repeated {@link #restore}, cannot
		 * corrupt the memento.
		 *
		 * @return an independent snapshot of this internal node's array structure
		 */
		@Nonnull
		@Override
		public InternalNodeMemento snapshot() {
			return new InternalNodeMemento(
				this.children.clone(), this.separators.clone(), this.counts.clone(), this.childCount);
		}

		/**
		 * Restores the array structure captured by {@link #snapshot}. Fresh clones of the memento's arrays are
		 * installed so the memento stays reusable for a repeated restore.
		 *
		 * @param memento the state previously captured by {@link #snapshot}
		 */
		@Override
		public void restore(@Nonnull InternalNodeMemento memento) {
			this.children = memento.children().clone();
			this.separators = memento.separators().clone();
			this.counts = memento.counts().clone();
			this.childCount = memento.childCount();
		}

		@Override
		public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			transactionalLayer.removeTransactionalMemoryLayer(this);
		}

		@Nonnull
		@Override
		public InternalNode createCopyWithMergedTransactionalMemory(
			@Nullable InternalNode internalLayer,
			@Nonnull TransactionalLayerMaintainer transactionalLayer
		) {
			final Node<?>[] theChildren;
			final long[] theSeparators;
			final int[] theCounts;
			final int theChildCount;
			if (internalLayer == null) {
				theChildren = this.children;
				theSeparators = this.separators;
				theCounts = this.counts;
				theChildCount = this.childCount;
			} else {
				theChildren = internalLayer.children;
				theSeparators = internalLayer.separators;
				theCounts = internalLayer.counts;
				theChildCount = internalLayer.childCount;
			}

			// merge the committed copies of every child; only allocate a fresh children array when at least one
			// child actually changed under the transaction
			Node<?>[] newChildren = null;
			for (int i = 0; i < theChildCount; i++) {
				final Node<?> child = transactionalLayer.getStateCopyWithCommittedChanges(theChildren[i]);
				if (newChildren == null && child != theChildren[i]) {
					newChildren = new Node<?>[theChildren.length];
					System.arraycopy(theChildren, 0, newChildren, 0, i);
				}
				if (newChildren != null) {
					newChildren[i] = child;
				}
			}

			if (newChildren != null) {
				return new InternalNode(newChildren, theSeparators, theCounts, theChildCount, true);
			} else if (internalLayer != null) {
				return new InternalNode(theChildren, theSeparators, theCounts, theChildCount, true);
			} else if (!this.transactionalLayer) {
				// nodes created during splits are built with transactionalLayer=false so they do not allocate STM
				// layers mid-transaction; on commit they must be rebuilt as participating (transactionalLayer=true)
				// nodes so subsequent transactions can layer changes over them
				return new InternalNode(theChildren, theSeparators, theCounts, theChildCount, true);
			} else {
				return this;
			}
		}

		@Override
		public String toString() {
			return "Internal(children=" + getChildCount() + ')';
		}

		/**
		 * Immutable savepoint memento of an internal node's copy-on-write array structure. The arrays are private
		 * clones owned by the memento (see {@link #snapshot}); the child-node references they hold are shared by design
		 * and the separators / counts are value types.
		 *
		 * @param children   clone of the child-pointer array
		 * @param separators clone of the separator-key array
		 * @param counts     clone of the per-child subtree-count array
		 * @param childCount the number of valid children
		 */
		record InternalNodeMemento(
			@Nonnull Node<?>[] children,
			@Nonnull long[] separators,
			@Nonnull int[] counts,
			int childCount
		) {
		}
	}

}
