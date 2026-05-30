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

import com.carrotsearch.hppc.IntIntHashMap;
import com.carrotsearch.hppc.IntIntMap;
import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;

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
 * - **by order-key** (`findPosition`): descend by key, summing the counts of the left siblings to obtain the prefix
 *   count of records before the container, then add the in-container offset.
 *
 * Descent is **cursor based** (the root → leaf path is captured during descent) — there are **no parent pointers** —
 * so the very same structure path-copies cleanly once the transactional layer is added (mirroring
 * `TransactionalIntBPlusTree`). Mutations touch only the cursor path: `O(log N)` count re-stamps, no suffix renumber,
 * and small fixed-capacity blocks instead of the `O(N)` humongous arrays of the array delegate.
 *
 * Order-keys are minted **only on container split** (a fresh key in the gap between neighbours); the `≤ B` record ids
 * that physically move are re-homed in the secondary index. When a gap is exhausted the whole key space is re-spaced
 * (rare, `O(#containers)`). Within-container inserts mint nothing and move no siblings.
 *
 * A secondary `recordId → container` index ({@link IntObjectMap}, no boxing) stands in here for the value index that
 * a later phase promotes to a first-class primitive `int → long` B+ tree; it gives `O(1)` presence and `O(log N)`
 * lookup by value.
 *
 * This class mutates **in place** and is **not** transactional — it is the committed / warm-up delegate. The array
 * must not contain duplicated record ids.
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
	 * Default spacing between freshly assigned order-keys. Wide enough that gap subdivision practically never exhausts
	 * before a tree of realistic height is built; exhaustion is nonetheless handled by re-spacing.
	 */
	static final long DEFAULT_ORDER_KEY_GAP = 1L << 40;
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
	 * Secondary index mapping each present record id to the container that currently holds it. Provides `O(1)`
	 * presence checks and `O(log N)` position lookup / removal by record id without boxing.
	 */
	@Nonnull private final IntObjectMap<LeafNode> recordToLeaf = new IntObjectHashMap<>();
	/**
	 * Memoized flattened permutation (logical position → record id). Nullified on every mutation.
	 */
	@Nullable private int[] memoizedArray;
	/**
	 * Memoized sorted record id array (ascending). Nullified on every mutation.
	 */
	@Nullable private int[] memoizedRecordIds;
	/**
	 * Memoized positions aligned with {@link #memoizedRecordIds}. Nullified on every mutation.
	 */
	@Nullable private int[] memoizedPositions;

	/**
	 * Creates a new single-element tree.
	 */
	public UnorderedLookupTree(int recordId) {
		this(DEFAULT_ORDER_KEY_GAP);
		insertAt(0, recordId);
	}

	/**
	 * Creates a new tree from the passed unordered array (record ids in logical order). The array must not contain
	 * duplicates.
	 */
	public UnorderedLookupTree(@Nonnull int[] unorderedArray) {
		this(DEFAULT_ORDER_KEY_GAP);
		// sequential append - O(N log N), allocates only small node blocks (no humongous temporaries)
		for (int i = 0; i < unorderedArray.length; i++) {
			insertAt(i, unorderedArray[i]);
		}
	}

	/**
	 * Test-only constructor allowing a custom order-key gap so the re-spacing path can be exercised cheaply.
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
	 * Finds and returns the logical position of the passed record id.
	 *
	 * @return {@link Integer#MIN_VALUE} when the record id is not present
	 */
	public int findPosition(int recordId) {
		final LeafNode container = this.recordToLeaf.get(recordId);
		if (container == null) {
			return Integer.MIN_VALUE;
		}
		// prefix = records before this container, obtained by an order-key descent summing left-sibling counts
		final int prefix = prefixCountOf(container.orderKey);
		return prefix + indexInContainer(container, recordId);
	}

	/**
	 * Returns true when the record id is present in the tree.
	 */
	public boolean contains(int recordId) {
		return this.recordToLeaf.containsKey(recordId);
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
	 * Adds a new record id just after `previousRecordId`. The special value {@link Integer#MIN_VALUE} adds the record
	 * to the head of the array.
	 *
	 * @throws GenericEvitaInternalError when `previousRecordId` is not present (and is not the head wildcard)
	 */
	public void addRecord(int previousRecordId, int recordId) {
		Assert.isTrue(
			!this.recordToLeaf.containsKey(recordId),
			"Record with id " + recordId + " is already part of the array!"
		);
		if (previousRecordId == Integer.MIN_VALUE) {
			insertAt(0, recordId);
			return;
		}
		final LeafNode container = this.recordToLeaf.get(previousRecordId);
		if (container == null) {
			throw new GenericEvitaInternalError(
				"Record with id " + previousRecordId + " was not found in the array,"
					+ " cannot add record " + recordId + " after it!",
				"Referenced record was not found in the array! Cannot add record after it."
			);
		}
		// insert directly after the previous record inside its container (a local, sibling-free operation)
		final Cursor cursor = new Cursor();
		descendByKey(container.orderKey, cursor);
		final int offset = indexInContainer(container, previousRecordId) + 1;
		insertIntoContainer(container, offset, recordId, cursor);
		invalidateMemoizedState();
	}

	/**
	 * Adds a new record id on the specified logical position.
	 */
	public void addRecordOnIndex(int index, int recordId) {
		Assert.isTrue(
			!this.recordToLeaf.containsKey(recordId),
			"Record with id " + recordId + " is already part of the array!"
		);
		insertAt(index, recordId);
	}

	/**
	 * Appends a set of (unsorted) record ids at the end of the array.
	 */
	public void appendRecords(@Nonnull int[] newRecordIds) {
		for (final int recordId : newRecordIds) {
			insertAt(this.size, recordId);
		}
	}

	/**
	 * Removes an existing record id from the array.
	 *
	 * @throws GenericEvitaInternalError when the record id is not present
	 */
	public void removeRecord(int recordId) {
		final LeafNode container = this.recordToLeaf.get(recordId);
		if (container == null) {
			throw new GenericEvitaInternalError(
				"Record id " + recordId + " is not part of the array!",
				"Record id is not part of the array!"
			);
		}
		final Cursor cursor = new Cursor();
		descendByKey(container.orderKey, cursor);
		final int offset = indexInContainer(container, recordId);
		// shift the tail of the container one slot to the left to fill the gap
		System.arraycopy(container.recordIds, offset + 1, container.recordIds, offset, container.count - offset - 1);
		container.count--;
		this.recordToLeaf.remove(recordId);
		propagateCountDelta(cursor, -1);
		this.size--;
		if (container.count == 0) {
			removeEmptyContainer(container, cursor);
		}
		invalidateMemoizedState();
	}

	/**
	 * Removes all records between two logical positions.
	 *
	 * @param startIndex inclusive
	 * @param endIndex   exclusive
	 * @return removed record ids in logical order
	 */
	@Nonnull
	public int[] removeRange(int startIndex, int endIndex) {
		final int[] array = getArray();
		final int[] removed = Arrays.copyOfRange(array, startIndex, endIndex);
		for (final int recordId : removed) {
			removeRecord(recordId);
		}
		return removed;
	}

	/**
	 * Returns the possibly modified unordered array of record ids (logical order).
	 */
	@Nonnull
	public int[] getArray() {
		if (this.memoizedArray == null) {
			this.memoizedArray = flatten();
		}
		return this.memoizedArray;
	}

	/**
	 * Returns the ordered array of record ids in ascending order.
	 */
	@Nonnull
	public int[] getRecordIds() {
		computeSnapshotIfNeeded();
		return this.memoizedRecordIds;
	}

	/**
	 * Returns the array of positions corresponding to the ascending record id array {@link #getRecordIds()}; i.e.
	 * `getArray()[positions[i]] == getRecordIds()[i]`.
	 */
	@Nonnull
	public int[] getPositions() {
		computeSnapshotIfNeeded();
		return this.memoizedPositions;
	}

	@Override
	public String toString() {
		return "UnorderedLookupTree" + Arrays.toString(getArray());
	}

	/*
		PRIVATE METHODS
	 */

	/**
	 * Inserts `recordId` at the logical `index`, descending by position to the proper container.
	 */
	private void insertAt(int index, int recordId) {
		if (this.root == null) {
			final LeafNode container = new LeafNode();
			container.orderKey = 0L;
			container.recordIds[0] = recordId;
			container.count = 1;
			this.root = container;
			this.size = 1;
			this.recordToLeaf.put(recordId, container);
			invalidateMemoizedState();
			return;
		}
		final Cursor cursor = new Cursor();
		final LeafNode container = descendByPosition(index, cursor);
		final int offset = Math.min(cursor.leafOffset, container.count);
		insertIntoContainer(container, offset, recordId, cursor);
		invalidateMemoizedState();
	}

	/**
	 * Inserts `recordId` into `container` at the given in-container `offset`, propagates the count up the cursor path
	 * and splits the container on overflow.
	 */
	private void insertIntoContainer(@Nonnull LeafNode container, int offset, int recordId, @Nonnull Cursor cursor) {
		System.arraycopy(container.recordIds, offset, container.recordIds, offset + 1, container.count - offset);
		container.recordIds[offset] = recordId;
		container.count++;
		this.recordToLeaf.put(recordId, container);
		propagateCountDelta(cursor, +1);
		this.size++;
		if (container.count > BLOCK_SIZE) {
			splitContainer(container, cursor);
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
	 * Returns the number of records that precede the container routed by `orderKey` (its prefix count), by an
	 * order-key descent summing the counts of all left siblings along the path.
	 */
	private int prefixCountOf(long orderKey) {
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
		return prefix;
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
	 * Splits an overflowing container into two, mints an order-key for the new right container and propagates the
	 * split up the cursor path.
	 */
	private void splitContainer(@Nonnull LeafNode container, @Nonnull Cursor cursor) {
		final int total = container.count;
		final int leftCount = total / 2;
		final int rightCount = total - leftCount;
		final long rightKey = mintOrderKey(container);
		final LeafNode right = new LeafNode();
		right.orderKey = rightKey;
		System.arraycopy(container.recordIds, leftCount, right.recordIds, 0, rightCount);
		right.count = rightCount;
		container.count = leftCount;
		Arrays.fill(container.recordIds, leftCount, total, 0);
		// re-home the moved record ids in the secondary index
		for (int i = 0; i < rightCount; i++) {
			this.recordToLeaf.put(right.recordIds[i], right);
		}
		// maintain the container sibling links (used by the in-order flatten)
		right.next = container.next;
		right.prev = container;
		if (container.next != null) {
			container.next.prev = right;
		}
		container.next = right;
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
	 * Mints a fresh order-key for a new right container inserted immediately after `container`, re-spacing the whole
	 * key space when the gap to the next container is exhausted.
	 */
	private long mintOrderKey(@Nonnull LeafNode container) {
		if (container.next == null) {
			// rightmost container - append a full gap to the right
			long candidate = container.orderKey + this.orderKeyGap;
			if (candidate <= container.orderKey) {
				respaceOrderKeys();
				candidate = container.orderKey + this.orderKeyGap;
			}
			return candidate;
		}
		long gap = container.next.orderKey - container.orderKey;
		if (gap < 2) {
			respaceOrderKeys();
			gap = container.next.orderKey - container.orderKey;
		}
		return container.orderKey + gap / 2;
	}

	/**
	 * Reassigns every container's order-key to an evenly spaced value (preserving logical order) and rebuilds all
	 * internal separators. Rare; `O(#containers)`.
	 */
	private void respaceOrderKeys() {
		LeafNode container = leftmostContainer();
		long key = 0L;
		while (container != null) {
			container.orderKey = key;
			key += this.orderKeyGap;
			container = container.next;
		}
		if (this.root instanceof InternalNode) {
			recomputeSeparators(this.root);
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
	private void removeEmptyContainer(@Nonnull LeafNode container, @Nonnull Cursor cursor) {
		// unlink from the container chain
		if (container.prev != null) {
			container.prev.next = container.next;
		}
		if (container.next != null) {
			container.next.prev = container.prev;
		}
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
			"Record id " + recordId + " not found in its indexed container!",
			"Inconsistent lookup state!"
		);
	}

	/**
	 * Produces the flat permutation array (logical position → record id) by walking the container chain in order.
	 */
	@Nonnull
	private int[] flatten() {
		final int[] result = new int[this.size];
		if (this.root == null) {
			return result;
		}
		LeafNode container = leftmostContainer();
		int position = 0;
		while (container != null) {
			System.arraycopy(container.recordIds, 0, result, position, container.count);
			position += container.count;
			container = container.next;
		}
		return result;
	}

	/**
	 * Returns the leftmost container of the tree (start of the logical order).
	 */
	@Nullable
	private LeafNode leftmostContainer() {
		Node node = this.root;
		while (node instanceof final InternalNode internal) {
			node = internal.children[0];
		}
		return (LeafNode) node;
	}

	/**
	 * Computes and memoizes the ascending record id array together with the aligned positions array (single primitive
	 * pass, no boxing).
	 */
	private void computeSnapshotIfNeeded() {
		if (this.memoizedRecordIds != null && this.memoizedPositions != null) {
			return;
		}
		final int[] permutation = getArray();
		// map record id -> position (single pass, no boxing)
		final IntIntMap recordToPosition = new IntIntHashMap(permutation.length);
		for (int position = 0; position < permutation.length; position++) {
			recordToPosition.put(permutation[position], position);
		}
		final int[] sortedRecordIds = permutation.clone();
		Arrays.sort(sortedRecordIds);
		final int[] positions = new int[sortedRecordIds.length];
		for (int i = 0; i < sortedRecordIds.length; i++) {
			positions[i] = recordToPosition.get(sortedRecordIds[i]);
		}
		this.memoizedRecordIds = sortedRecordIds;
		this.memoizedPositions = positions;
	}

	/**
	 * Drops all memoized snapshot arrays after a mutation.
	 */
	private void invalidateMemoizedState() {
		this.memoizedArray = null;
		this.memoizedRecordIds = null;
		this.memoizedPositions = null;
	}

	/**
	 * Mutable descent cursor capturing the internal-node path from the root to a leaf (plus the in-container offset
	 * for positional descents). Reused per operation; never escapes the structure, so it carries no transactional
	 * state.
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
	 * `long` order-key and linked to its logical neighbours for a fast in-order flatten.
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
		/**
		 * Next container in logical order, or `null`.
		 */
		@Nullable LeafNode next;
		/**
		 * Previous container in logical order, or `null`.
		 */
		@Nullable LeafNode prev;
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
