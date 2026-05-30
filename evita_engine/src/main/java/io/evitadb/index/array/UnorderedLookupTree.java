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

import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * The **position tree** of the two-tree backing for {@link UnorderedLookup}: a count-augmented (order-statistic) B+
 * tree of distinct `int` record ids, ordered by their **logical position** and routed by a stable `long`
 * **order-key**.
 *
 * The leaves are **containers** holding up to {@link #BLOCK_SIZE} record ids in logical order; each container carries
 * a `long` order-key. Internal nodes route by order-key separators **and** carry the record-id count of each child
 * subtree (the augmentation that makes positions implicit). Because order-key order is identical to logical order, a
 * single tree answers both:
 *
 * - **by position** (`getRecordAt` / select): descend choosing the child whose cumulative count brackets the target;
 * - **by order-key** (`findPositionByOrderKey`): descend by key, summing the counts of the left siblings to obtain
 *   the prefix count of records before the container, then add the in-container offset.
 *
 * Descent is **cursor based** (the root → leaf path is captured during descent) — there are **no parent pointers and
 * no sibling links** — so the very same structure path-copies cleanly once the transactional layer is added
 * (mirroring `TransactionalIntBPlusTree`). Mutations touch only the cursor path: `O(log N)` count re-stamps, no
 * suffix renumber, and small fixed-capacity blocks instead of the `O(N)` humongous arrays of the array delegate.
 *
 * This tree is **not** the owner of the `recordId → orderKey` mapping — that is the job of the value index held by
 * the composite that drives this tree. Whenever a mutation assigns or re-stamps a record's order-key (a fresh insert
 * or a container split), the tree reports it through an {@link OrderKeyConsumer} so the composite can keep its value
 * index coherent (INV-COUPLE). Order-keys are minted **only on container split**; on gap exhaustion the whole key
 * space is re-spaced (rare, `O(#containers)`).
 *
 * This class mutates **in place** and is **not** transactional — it is the committed / warm-up delegate. The records
 * must be distinct.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@NotThreadSafe
public class UnorderedLookupTree implements Serializable {
	@Serial private static final long serialVersionUID = -7242020610200620162L;

	/**
	 * Fixed capacity of a single node block (both container record slots and internal child slots). A power of two
	 * keeps the blocks small enough to be TLAB-allocated and cache friendly.
	 */
	static final int BLOCK_SIZE = 64;
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
	 * Upper bound on tree height used to size the descent cursor. Internal nodes keep at least two children (single
	 * child nodes are spliced out), so height is bounded by `log2(N)`; 40 covers the whole positive `int` range.
	 */
	private static final int MAX_HEIGHT = 40;

	/**
	 * Root node of the tree. `null` when the tree is empty.
	 */
	@Nullable private Node root;
	/**
	 * Total number of record ids held by the tree (equals the length of the logical array).
	 */
	private int size;
	/**
	 * Spacing between freshly assigned order-keys (overridable for tests that force re-spacing).
	 */
	private final long orderKeyGap;
	/**
	 * Memoized flattened permutation (logical position → record id). Nullified on every mutation.
	 */
	@Nullable private int[] memoizedArray;

	/**
	 * Creates a new empty tree with the default order-key gap.
	 */
	public UnorderedLookupTree() {
		this(DEFAULT_ORDER_KEY_GAP);
	}

	/**
	 * Creates a new empty tree with a custom order-key gap (used by tests to force the re-spacing path cheaply).
	 */
	UnorderedLookupTree(long orderKeyGap) {
		this.orderKeyGap = orderKeyGap;
	}

	/**
	 * Returns the number of record ids in the tree (length of the logical array).
	 */
	public int size() {
		return this.size;
	}

	/**
	 * Returns true when the tree holds no records.
	 */
	public boolean isEmpty() {
		return this.size == 0;
	}

	/**
	 * Bulk-loads an empty tree from an array of record ids in logical order, building it bottom-up with fully-filled
	 * containers and evenly spaced order-keys. Reports every record's order-key through `assignments`. `O(N)`.
	 *
	 * @param recordIds   record ids in logical order (must be distinct)
	 * @param assignments callback receiving each `recordId → orderKey` assignment
	 */
	public void bulkLoad(@Nonnull int[] recordIds, @Nonnull OrderKeyConsumer assignments) {
		if (this.root != null) {
			throw new GenericEvitaInternalError("Bulk-load is only allowed on an empty tree!");
		}
		final int n = recordIds.length;
		if (n == 0) {
			return;
		}
		// 1. pack records into containers and report their order-keys
		final int containerCount = (n + BLOCK_SIZE - 1) / BLOCK_SIZE;
		Node[] level = new Node[containerCount];
		long[] minKeys = new long[containerCount];
		int[] counts = new int[containerCount];
		int pos = 0;
		for (int c = 0; c < containerCount; c++) {
			final LeafNode container = new LeafNode();
			final long key = (long) c * this.orderKeyGap;
			container.orderKey = key;
			final int cnt = Math.min(BLOCK_SIZE, n - pos);
			System.arraycopy(recordIds, pos, container.recordIds, 0, cnt);
			container.count = cnt;
			for (int i = 0; i < cnt; i++) {
				assignments.accept(recordIds[pos + i], key);
			}
			level[c] = container;
			minKeys[c] = key;
			counts[c] = cnt;
			pos += cnt;
		}
		this.size = n;
		// 2. build internal levels bottom-up until a single root remains
		int levelSize = containerCount;
		while (levelSize > 1) {
			final int parentCount = (levelSize + BLOCK_SIZE - 1) / BLOCK_SIZE;
			final int base = levelSize / parentCount;
			final int remainder = levelSize % parentCount;
			final Node[] parents = new Node[parentCount];
			final long[] parentMinKeys = new long[parentCount];
			final int[] parentCounts = new int[parentCount];
			int childCursor = 0;
			for (int p = 0; p < parentCount; p++) {
				// distribute children evenly so the tail node never ends up with a single child
				final int childN = base + (p < remainder ? 1 : 0);
				final InternalNode internal = new InternalNode();
				internal.childCount = childN;
				int subtree = 0;
				for (int j = 0; j < childN; j++) {
					internal.children[j] = level[childCursor + j];
					internal.counts[j] = counts[childCursor + j];
					subtree += counts[childCursor + j];
					if (j >= 1) {
						internal.separators[j - 1] = minKeys[childCursor + j];
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
		this.root = level[0];
		invalidateMemoizedState();
	}

	/**
	 * Returns the record id located on the passed logical position.
	 *
	 * @throws GenericEvitaInternalError when the position is out of bounds
	 */
	public int getRecordAt(int position) {
		if (position < 0 || position >= this.size || this.root == null) {
			throw new GenericEvitaInternalError(
				"Position " + position + " not found!",
				"Unknown position in the array!"
			);
		}
		Node node = this.root;
		int remaining = position;
		while (node instanceof final InternalNode internal) {
			int childIndex = 0;
			while (childIndex < internal.childCount - 1 && remaining >= internal.counts[childIndex]) {
				remaining -= internal.counts[childIndex];
				childIndex++;
			}
			node = internal.children[childIndex];
		}
		return ((LeafNode) node).recordIds[remaining];
	}

	/**
	 * Returns the last record id in the logical array.
	 *
	 * @throws ArrayIndexOutOfBoundsException when the tree is empty
	 */
	public int getLastRecordId() throws ArrayIndexOutOfBoundsException {
		if (this.size == 0 || this.root == null) {
			throw new ArrayIndexOutOfBoundsException("Array is empty!");
		}
		Node node = this.root;
		while (node instanceof final InternalNode internal) {
			node = internal.children[internal.childCount - 1];
		}
		final LeafNode container = (LeafNode) node;
		return container.recordIds[container.count - 1];
	}

	/**
	 * Returns the logical position of `recordId`, known to live in the container routed by `orderKey`.
	 *
	 * @return the prefix count of the container plus the record's in-container offset
	 * @throws GenericEvitaInternalError when the record is not found in the routed container
	 */
	public int findPositionByOrderKey(long orderKey, int recordId) {
		int prefix = 0;
		Node node = this.root;
		while (node instanceof final InternalNode internal) {
			int childIndex = 0;
			while (childIndex < internal.childCount - 1 && orderKey >= internal.separators[childIndex]) {
				prefix += internal.counts[childIndex];
				childIndex++;
			}
			node = internal.children[childIndex];
		}
		return prefix + indexInContainer((LeafNode) node, recordId);
	}

	/**
	 * Inserts `recordId` at the logical `index` (descending by position to the proper container). Reports the
	 * resulting `recordId → orderKey` assignment(s) through `assignments`.
	 */
	public void insertAtPosition(int index, int recordId, @Nonnull OrderKeyConsumer assignments) {
		if (this.root == null) {
			final LeafNode container = new LeafNode();
			container.orderKey = 0L;
			container.recordIds[0] = recordId;
			container.count = 1;
			this.root = container;
			this.size = 1;
			assignments.accept(recordId, 0L);
			invalidateMemoizedState();
			return;
		}
		final Cursor cursor = new Cursor();
		final LeafNode container = descendByPosition(index, cursor);
		final int offset = Math.min(cursor.leafOffset, container.count);
		insertIntoContainer(container, offset, recordId, cursor, assignments);
		invalidateMemoizedState();
	}

	/**
	 * Inserts `recordId` immediately after `previousRecordId` inside the container routed by `previousOrderKey`.
	 * Reports the resulting `recordId → orderKey` assignment(s) through `assignments`.
	 *
	 * @throws GenericEvitaInternalError when `previousRecordId` is not found in the routed container
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
	 * @throws GenericEvitaInternalError when the record is not found in the routed container
	 */
	public void removeByOrderKey(long orderKey, int recordId, @Nonnull OrderKeyConsumer reassignments) {
		final Cursor cursor = new Cursor();
		final LeafNode container = descendByKey(orderKey, cursor);
		final int offset = indexInContainer(container, recordId);
		System.arraycopy(container.recordIds, offset + 1, container.recordIds, offset, container.count - offset - 1);
		container.count--;
		propagateCountDelta(cursor, -1);
		this.size--;
		if (container.count == 0) {
			removeEmptyContainer(cursor);
		}
		invalidateMemoizedState();
	}

	/**
	 * Returns the flattened unordered array of record ids in logical order.
	 */
	@Nonnull
	public int[] getArray() {
		if (this.memoizedArray == null) {
			final int[] result = new int[this.size];
			if (this.root != null) {
				flattenInto(this.root, result, new int[1]);
			}
			this.memoizedArray = result;
		}
		return this.memoizedArray;
	}

	@Override
	public String toString() {
		return "UnorderedLookupTree" + Arrays.toString(getArray());
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Inserts `recordId` into `container` at the given in-container `offset`, propagates the count up the cursor path,
	 * reports the assignment(s) and splits the container on overflow.
	 */
	private void insertIntoContainer(@Nonnull LeafNode container, int offset, int recordId, @Nonnull Cursor cursor, @Nonnull OrderKeyConsumer assignments) {
		System.arraycopy(container.recordIds, offset, container.recordIds, offset + 1, container.count - offset);
		container.recordIds[offset] = recordId;
		container.count++;
		propagateCountDelta(cursor, +1);
		this.size++;
		if (container.count > BLOCK_SIZE) {
			splitContainer(container, offset, recordId, cursor, assignments);
		} else {
			assignments.accept(recordId, container.orderKey);
		}
	}

	/**
	 * Descends from the root to the container holding logical `position`, capturing the cursor path and storing the
	 * in-container offset in {@link Cursor#leafOffset}.
	 */
	@Nonnull
	private LeafNode descendByPosition(int position, @Nonnull Cursor cursor) {
		cursor.depth = 0;
		Node node = this.root;
		int remaining = position;
		while (node instanceof final InternalNode internal) {
			int childIndex = 0;
			// for an insertion at a child boundary we stay in the left child (append at its end)
			while (childIndex < internal.childCount - 1 && remaining > internal.counts[childIndex]) {
				remaining -= internal.counts[childIndex];
				childIndex++;
			}
			cursor.push(internal, childIndex);
			node = internal.children[childIndex];
		}
		cursor.leafOffset = remaining;
		return (LeafNode) node;
	}

	/**
	 * Descends from the root to the container routed by `orderKey`, capturing the cursor path.
	 */
	@Nonnull
	private LeafNode descendByKey(long orderKey, @Nonnull Cursor cursor) {
		cursor.depth = 0;
		Node node = this.root;
		while (node instanceof final InternalNode internal) {
			int childIndex = 0;
			while (childIndex < internal.childCount - 1 && orderKey >= internal.separators[childIndex]) {
				childIndex++;
			}
			cursor.push(internal, childIndex);
			node = internal.children[childIndex];
		}
		return (LeafNode) node;
	}

	/**
	 * Adjusts the stored subtree counts of every internal node on the cursor path by `delta`.
	 */
	private static void propagateCountDelta(@Nonnull Cursor cursor, int delta) {
		for (int level = 0; level < cursor.depth; level++) {
			cursor.path[level].counts[cursor.idx[level]] += delta;
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
		final int total = container.count;
		final int leftCount = total / 2;
		final int rightCount = total - leftCount;
		final long rightKey = mintOrderKey(container, cursor, assignments);
		final LeafNode right = new LeafNode();
		right.orderKey = rightKey;
		System.arraycopy(container.recordIds, leftCount, right.recordIds, 0, rightCount);
		right.count = rightCount;
		container.count = leftCount;
		Arrays.fill(container.recordIds, leftCount, total, 0);
		// report the new record (in whichever half it landed) and every other record that moved to the right half
		final long newRecordKey = newOffset < leftCount ? container.orderKey : rightKey;
		assignments.accept(newRecordId, newRecordKey);
		for (int i = 0; i < rightCount; i++) {
			final int movedRecordId = right.recordIds[i];
			if (movedRecordId != newRecordId) {
				assignments.accept(movedRecordId, rightKey);
			}
		}
		propagateSplit(cursor, cursor.depth - 1, right, rightKey, rightCount);
	}

	/**
	 * Propagates a node split up the cursor: inserts `newRight` (with `newRightMinKey` / `newRightCount`) into the
	 * parent at `level`, creating a new root when the split reaches the top and splitting parents on overflow.
	 */
	private void propagateSplit(@Nonnull Cursor cursor, int level, @Nonnull Node newRight, long newRightMinKey, int newRightCount) {
		Node right = newRight;
		long rightMinKey = newRightMinKey;
		int rightCount = newRightCount;
		while (true) {
			if (level < 0) {
				// the split reached the root - grow a new root above the two halves
				final InternalNode newRoot = new InternalNode();
				newRoot.children[0] = this.root;
				newRoot.children[1] = right;
				newRoot.counts[0] = subtreeCount(this.root);
				newRoot.counts[1] = rightCount;
				newRoot.separators[0] = rightMinKey;
				newRoot.childCount = 2;
				this.root = newRoot;
				return;
			}
			final InternalNode parent = cursor.path[level];
			final int ci = cursor.idx[level];
			// the existing child's stored count was already incremented for the inserted record; shed the moved part
			parent.counts[ci] -= rightCount;
			insertIntoInternal(parent, ci, right, rightMinKey, rightCount);
			if (parent.childCount <= BLOCK_SIZE) {
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
	private static void insertIntoInternal(@Nonnull InternalNode parent, int ci, @Nonnull Node newChild, long minKey, int childCount) {
		final int target = ci + 1;
		System.arraycopy(parent.children, target, parent.children, target + 1, parent.childCount - target);
		System.arraycopy(parent.counts, target, parent.counts, target + 1, parent.childCount - target);
		// the separator between child ci and the new child goes at index ci, shifting the rest right
		System.arraycopy(parent.separators, ci, parent.separators, ci + 1, parent.childCount - 1 - ci);
		parent.children[target] = newChild;
		parent.counts[target] = childCount;
		parent.separators[ci] = minKey;
		parent.childCount++;
	}

	/**
	 * Splits an overflowing internal node, returning the new right node and reporting (through the single-element
	 * out-parameters) the promoted separator key and the right node's subtree count.
	 */
	@Nonnull
	private InternalNode splitInternal(@Nonnull InternalNode node, @Nonnull long[] promotedKey, @Nonnull int[] promotedCount) {
		final int total = node.childCount;
		final int leftCount = total / 2;
		final int rightCount = total - leftCount;
		final InternalNode right = new InternalNode();
		System.arraycopy(node.children, leftCount, right.children, 0, rightCount);
		System.arraycopy(node.counts, leftCount, right.counts, 0, rightCount);
		// right keeps the separators strictly inside its child range (rightCount - 1 of them)
		System.arraycopy(node.separators, leftCount, right.separators, 0, rightCount - 1);
		right.childCount = rightCount;
		// the separator between the two halves is promoted to the parent (not kept in either node)
		promotedKey[0] = node.separators[leftCount - 1];
		int rightSubtreeCount = 0;
		for (int i = 0; i < rightCount; i++) {
			rightSubtreeCount += right.counts[i];
		}
		promotedCount[0] = rightSubtreeCount;
		// clear the moved slots in the left node
		Arrays.fill(node.children, leftCount, total, null);
		Arrays.fill(node.counts, leftCount, total, 0);
		Arrays.fill(node.separators, leftCount - 1, total - 1, 0L);
		node.childCount = leftCount;
		return right;
	}

	/**
	 * Mints a fresh order-key for a new right container inserted immediately after the container at the bottom of the
	 * cursor, re-spacing the whole key space when the gap to the next container is exhausted.
	 */
	private long mintOrderKey(@Nonnull LeafNode container, @Nonnull Cursor cursor, @Nonnull OrderKeyConsumer assignments) {
		final long nextKey = nextContainerKey(cursor);
		if (nextKey == NO_NEXT_KEY) {
			// rightmost container - append a full gap to the right
			long candidate = container.orderKey + this.orderKeyGap;
			if (candidate <= container.orderKey) {
				respaceOrderKeys(assignments);
				candidate = container.orderKey + this.orderKeyGap;
			}
			return candidate;
		}
		long gap = nextKey - container.orderKey;
		if (gap < 2) {
			respaceOrderKeys(assignments);
			gap = nextContainerKey(cursor) - container.orderKey;
		}
		return container.orderKey + gap / 2;
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
			if (ci < node.childCount - 1) {
				return node.separators[ci];
			}
		}
		return NO_NEXT_KEY;
	}

	/**
	 * Reassigns every container's order-key to an evenly spaced value (preserving logical order) and rebuilds all
	 * internal separators. Rare; `O(#containers)`.
	 */
	private void respaceOrderKeys(@Nonnull OrderKeyConsumer assignments) {
		if (this.root != null) {
			reassignKeys(this.root, new long[]{0L}, assignments);
			recomputeSeparators(this.root);
		}
	}

	/**
	 * Reassigns container order-keys in logical (in-order) sequence, spaced by {@link #orderKeyGap}, reporting each
	 * record's new order-key so the value index stays coherent (re-spacing re-stamps every record).
	 */
	private void reassignKeys(@Nonnull Node node, @Nonnull long[] counter, @Nonnull OrderKeyConsumer assignments) {
		if (node instanceof final LeafNode leaf) {
			leaf.orderKey = counter[0] * this.orderKeyGap;
			counter[0]++;
			for (int i = 0; i < leaf.count; i++) {
				assignments.accept(leaf.recordIds[i], leaf.orderKey);
			}
		} else {
			final InternalNode internal = (InternalNode) node;
			for (int i = 0; i < internal.childCount; i++) {
				reassignKeys(internal.children[i], counter, assignments);
			}
		}
	}

	/**
	 * Recomputes the separators of every internal node in the subtree rooted at `node` from the current container
	 * order-keys, returning the minimum order-key in the subtree.
	 */
	private static long recomputeSeparators(@Nonnull Node node) {
		if (node instanceof final LeafNode leaf) {
			return leaf.orderKey;
		}
		final InternalNode internal = (InternalNode) node;
		final long min = recomputeSeparators(internal.children[0]);
		for (int i = 1; i < internal.childCount; i++) {
			internal.separators[i - 1] = recomputeSeparators(internal.children[i]);
		}
		return min;
	}

	/**
	 * Removes an emptied container from the tree, walking up the cursor to collapse emptied ancestors, splice out
	 * single-child internals and reduce the root height.
	 */
	private void removeEmptyContainer(@Nonnull Cursor cursor) {
		if (cursor.depth == 0) {
			// the container was the root and is now empty - the tree is empty
			this.root = null;
			return;
		}
		int level = cursor.depth - 1;
		while (level >= 0) {
			final InternalNode parent = cursor.path[level];
			final int ci = cursor.idx[level];
			removeChildAt(parent, ci);
			if (parent.childCount == 0) {
				if (level == 0) {
					this.root = null;
					return;
				}
				level--;
				continue;
			}
			if (parent.childCount == 1) {
				if (level == 0) {
					// collapse a single-child root to reduce tree height
					this.root = parent.children[0];
				} else {
					// splice a single-child internal out of its parent (keep internals >= 2 children)
					final InternalNode grandParent = cursor.path[level - 1];
					grandParent.children[cursor.idx[level - 1]] = parent.children[0];
				}
			}
			return;
		}
	}

	/**
	 * Removes the child at index `ci` from `internal`, dropping the adjacent separator.
	 */
	private static void removeChildAt(@Nonnull InternalNode internal, int ci) {
		System.arraycopy(internal.children, ci + 1, internal.children, ci, internal.childCount - ci - 1);
		System.arraycopy(internal.counts, ci + 1, internal.counts, ci, internal.childCount - ci - 1);
		// drop the separator that bordered the removed child (the one after it, or the last one when removing the tail)
		final int separatorIndex = ci < internal.childCount - 1 ? ci : ci - 1;
		if (separatorIndex >= 0) {
			System.arraycopy(internal.separators, separatorIndex + 1, internal.separators, separatorIndex, internal.childCount - separatorIndex - 2);
		}
		internal.childCount--;
		internal.children[internal.childCount] = null;
		internal.counts[internal.childCount] = 0;
		if (internal.childCount >= 1) {
			internal.separators[internal.childCount - 1] = 0L;
		}
	}

	/**
	 * Returns the number of record ids held in the subtree rooted at `node`.
	 */
	private static int subtreeCount(@Nonnull Node node) {
		if (node instanceof final LeafNode leaf) {
			return leaf.count;
		}
		final InternalNode internal = (InternalNode) node;
		int sum = 0;
		for (int i = 0; i < internal.childCount; i++) {
			sum += internal.counts[i];
		}
		return sum;
	}

	/**
	 * Returns the slot index of `recordId` within `container`.
	 */
	private static int indexInContainer(@Nonnull LeafNode container, int recordId) {
		for (int i = 0; i < container.count; i++) {
			if (container.recordIds[i] == recordId) {
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
	private static void flattenInto(@Nonnull Node node, @Nonnull int[] result, @Nonnull int[] positionHolder) {
		if (node instanceof final LeafNode leaf) {
			System.arraycopy(leaf.recordIds, 0, result, positionHolder[0], leaf.count);
			positionHolder[0] += leaf.count;
		} else {
			final InternalNode internal = (InternalNode) node;
			for (int i = 0; i < internal.childCount; i++) {
				flattenInto(internal.children[i], result, positionHolder);
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
	 * Base class for both node types.
	 */
	abstract static class Node implements Serializable {
		@Serial private static final long serialVersionUID = 6037764042741656045L;
	}

	/**
	 * Leaf node = a **container** holding up to {@link #BLOCK_SIZE} record ids in logical order, identified by a
	 * `long` order-key.
	 */
	static final class LeafNode extends Node {
		@Serial private static final long serialVersionUID = -2510718704128926730L;
		/**
		 * Stable order-key identifying this container's slot in the logical order.
		 */
		long orderKey;
		/**
		 * Record ids in logical order (only the first {@link #count} slots are valid).
		 */
		@Nonnull final int[] recordIds = new int[BLOCK_SIZE + 1];
		/**
		 * Number of valid record ids in this container.
		 */
		int count;
	}

	/**
	 * Internal node routing by `long` order-key separators and carrying the record-id count of each child subtree.
	 * Holds up to {@link #BLOCK_SIZE} children and `childCount - 1` separators (`separators[i]` is the minimum
	 * order-key found in `children[i + 1]`).
	 */
	static final class InternalNode extends Node {
		@Serial private static final long serialVersionUID = 1791772842933035170L;
		/**
		 * Child nodes (only the first {@link #childCount} slots are valid).
		 */
		@Nonnull final Node[] children = new Node[BLOCK_SIZE + 1];
		/**
		 * Separator order-keys; `separators[i]` is the minimum order-key in `children[i + 1]`'s subtree.
		 */
		@Nonnull final long[] separators = new long[BLOCK_SIZE];
		/**
		 * Record-id count of each child subtree, aligned with {@link #children}.
		 */
		@Nonnull final int[] counts = new int[BLOCK_SIZE + 1];
		/**
		 * Number of valid children.
		 */
		int childCount;
	}

}
