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
 * A count-augmented (order-statistic) B+ tree of distinct `int` record ids ordered by their **logical position**.
 *
 * This structure is a drop-in replacement for the dual-`int[]` {@link UnorderedLookup} delegate: it maintains the
 * bidirectional bijection between a record id set and a position permutation, but with `O(log N)` insert / remove /
 * positional access instead of the `O(N)` suffix-renumber + full-array reallocation that the array delegate performs
 * on every write. Loading a single chain to `N` is therefore `O(N log N)` (not `O(N²)`) and never allocates an
 * `O(N)`-sized (humongous) array on the write path — nodes are small fixed-capacity `int[]` blocks.
 *
 * Positions are **implicit**: an internal node stores the element count of each child subtree, so a record's position
 * is derived by summing the counts of the left siblings along the root → leaf path. No absolute position is ever
 * stored, hence an insert/delete only re-stamps the counts along a single path rather than renumbering a suffix.
 *
 * A secondary `recordId → leaf` index ({@link IntObjectMap}, no boxing) gives `O(log N)` lookup / removal by value.
 *
 * The structure is **not** intended to be traversed element-by-element by readers; readers consume the flat
 * {@link #getArray()} / {@link #getPositions()} / {@link #getRecordIds()} snapshot, computed once and memoized.
 *
 * This class mutates **in place** and is **not** transactional — it is the committed / warm-up delegate. Array must
 * not contain duplicated record ids.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@NotThreadSafe
public class UnorderedLookupTree implements Serializable {
	@Serial private static final long serialVersionUID = -7242020610200620162L;

	/**
	 * Fixed capacity of a single node block (both leaf record slots and internal child slots). A power of two keeps
	 * the `int[]` blocks small enough to be TLAB-allocated and cache friendly.
	 */
	static final int BLOCK_SIZE = 64;

	/**
	 * Root node of the tree. `null` when the tree is empty.
	 */
	@Nullable private Node root;
	/**
	 * Total number of record ids held by the tree (equals the length of the logical array).
	 */
	private int size;
	/**
	 * Secondary index mapping each present record id to the leaf node that currently contains it. Provides `O(1)`
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
		final LeafNode leaf = new LeafNode();
		leaf.recordIds[0] = recordId;
		leaf.count = 1;
		this.root = leaf;
		this.size = 1;
		this.recordToLeaf.put(recordId, leaf);
	}

	/**
	 * Creates a new tree from the passed unordered array (record ids in logical order). The array must not contain
	 * duplicates.
	 */
	public UnorderedLookupTree(@Nonnull int[] unorderedArray) {
		// sequential append - O(N log N), allocates only small node blocks (no humongous temporaries)
		for (int i = 0; i < unorderedArray.length; i++) {
			addRecordOnIndex(i, unorderedArray[i]);
		}
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
		final LeafNode leaf = this.recordToLeaf.get(recordId);
		if (leaf == null) {
			return Integer.MIN_VALUE;
		}
		return positionOf(leaf, recordId);
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
		final LeafNode leaf = (LeafNode) node;
		return leaf.recordIds[leaf.count - 1];
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
		final int insertPosition;
		if (previousRecordId == Integer.MIN_VALUE) {
			insertPosition = 0;
		} else {
			final int previousPosition = findPosition(previousRecordId);
			if (previousPosition == Integer.MIN_VALUE) {
				throw new GenericEvitaInternalError(
					"Record with id " + previousRecordId + " was not found in the array,"
						+ " cannot add record " + recordId + " after it!",
					"Referenced record was not found in the array! Cannot add record after it."
				);
			}
			insertPosition = previousPosition + 1;
		}
		insertAt(insertPosition, recordId);
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
		final LeafNode leaf = this.recordToLeaf.get(recordId);
		if (leaf == null) {
			throw new GenericEvitaInternalError(
				"Record id " + recordId + " is not part of the array!",
				"Record id is not part of the array!"
			);
		}
		final int offset = indexInLeaf(leaf, recordId);
		// shift the tail of the leaf one slot to the left to fill the gap
		System.arraycopy(leaf.recordIds, offset + 1, leaf.recordIds, offset, leaf.count - offset - 1);
		leaf.count--;
		this.recordToLeaf.remove(recordId);
		propagateCountDelta(leaf, -1);
		this.size--;
		if (leaf.count == 0) {
			removeEmptyLeaf(leaf);
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
	 * Computes the logical position of `recordId` known to reside in `leaf` by summing the counts of the left siblings
	 * along the root → leaf path.
	 */
	private int positionOf(@Nonnull LeafNode leaf, int recordId) {
		int position = indexInLeaf(leaf, recordId);
		Node node = leaf;
		InternalNode parent = leaf.parent;
		while (parent != null) {
			final int childIndex = parent.indexOfChild(node);
			for (int i = 0; i < childIndex; i++) {
				position += parent.counts[i];
			}
			node = parent;
			parent = parent.parent;
		}
		return position;
	}

	/**
	 * Returns the slot index of `recordId` within `leaf`.
	 */
	private static int indexInLeaf(@Nonnull LeafNode leaf, int recordId) {
		for (int i = 0; i < leaf.count; i++) {
			if (leaf.recordIds[i] == recordId) {
				return i;
			}
		}
		throw new GenericEvitaInternalError(
			"Record id " + recordId + " not found in its indexed leaf!",
			"Inconsistent lookup state!"
		);
	}

	/**
	 * Inserts `recordId` at the logical `index`, descending to the proper leaf, splitting on overflow.
	 */
	private void insertAt(int index, int recordId) {
		if (this.root == null) {
			final LeafNode leaf = new LeafNode();
			leaf.recordIds[0] = recordId;
			leaf.count = 1;
			this.root = leaf;
			this.size = 1;
			this.recordToLeaf.put(recordId, leaf);
			invalidateMemoizedState();
			return;
		}
		// descend to the target leaf, computing the in-leaf offset
		Node node = this.root;
		int remaining = index;
		while (node instanceof final InternalNode internal) {
			int childIndex = 0;
			while (childIndex < internal.childCount - 1 && remaining > internal.counts[childIndex]) {
				remaining -= internal.counts[childIndex];
				childIndex++;
			}
			node = internal.children[childIndex];
		}
		final LeafNode leaf = (LeafNode) node;
		final int offset = Math.min(remaining, leaf.count);
		// shift the tail one slot to the right and insert
		System.arraycopy(leaf.recordIds, offset, leaf.recordIds, offset + 1, leaf.count - offset);
		leaf.recordIds[offset] = recordId;
		leaf.count++;
		this.recordToLeaf.put(recordId, leaf);
		propagateCountDelta(leaf, +1);
		this.size++;
		if (leaf.count > BLOCK_SIZE) {
			splitLeaf(leaf);
		}
		invalidateMemoizedState();
	}

	/**
	 * Adjusts the stored subtree counts of every ancestor of `node` by `delta`.
	 */
	private void propagateCountDelta(@Nonnull Node node, int delta) {
		Node current = node;
		InternalNode parent = current.parent;
		while (parent != null) {
			final int childIndex = parent.indexOfChild(current);
			parent.counts[childIndex] += delta;
			current = parent;
			parent = parent.parent;
		}
	}

	/**
	 * Splits an overflowing leaf into two and propagates the split up the tree.
	 */
	private void splitLeaf(@Nonnull LeafNode leaf) {
		final int total = leaf.count;
		final int leftCount = total / 2;
		final int rightCount = total - leftCount;
		final LeafNode right = new LeafNode();
		System.arraycopy(leaf.recordIds, leftCount, right.recordIds, 0, rightCount);
		right.count = rightCount;
		leaf.count = leftCount;
		Arrays.fill(leaf.recordIds, leftCount, total, 0);
		// re-home the moved record ids in the secondary index
		for (int i = 0; i < rightCount; i++) {
			this.recordToLeaf.put(right.recordIds[i], right);
		}
		// maintain the leaf sibling links (used by the in-order flatten)
		right.next = leaf.next;
		right.prev = leaf;
		if (leaf.next != null) {
			leaf.next.prev = right;
		}
		leaf.next = right;
		insertChildAfter(leaf, right, rightCount);
	}

	/**
	 * Inserts `newChild` immediately after `existingChild` in `existingChild`'s parent, assigning `newChildCount` as
	 * its subtree count and reducing the existing child's stored count accordingly. Creates a new root when the
	 * existing child is the root, and splits the parent on overflow.
	 */
	private void insertChildAfter(@Nonnull Node existingChild, @Nonnull Node newChild, int newChildCount) {
		final InternalNode parent = existingChild.parent;
		if (parent == null) {
			// existing child is the root - create a new root above the two children
			final InternalNode newRoot = new InternalNode();
			newRoot.children[0] = existingChild;
			newRoot.children[1] = newChild;
			// the existing child's stored count was already reduced by the split, so it is taken as-is here
			newRoot.counts[0] = subtreeCount(existingChild);
			newRoot.counts[1] = newChildCount;
			newRoot.childCount = 2;
			existingChild.parent = newRoot;
			newChild.parent = newRoot;
			this.root = newRoot;
			return;
		}
		final int existingIndex = parent.indexOfChild(existingChild);
		// the existing child's stored count must drop by the amount handed to the new child
		parent.counts[existingIndex] -= newChildCount;
		// shift children and counts one slot to the right to make room after existingIndex
		System.arraycopy(parent.children, existingIndex + 1, parent.children, existingIndex + 2, parent.childCount - existingIndex - 1);
		System.arraycopy(parent.counts, existingIndex + 1, parent.counts, existingIndex + 2, parent.childCount - existingIndex - 1);
		parent.children[existingIndex + 1] = newChild;
		parent.counts[existingIndex + 1] = newChildCount;
		parent.childCount++;
		newChild.parent = parent;
		if (parent.childCount > BLOCK_SIZE) {
			splitInternal(parent);
		}
	}

	/**
	 * Splits an overflowing internal node into two and propagates the split up the tree.
	 */
	private void splitInternal(@Nonnull InternalNode internal) {
		final int total = internal.childCount;
		final int leftCount = total / 2;
		final int rightCount = total - leftCount;
		final InternalNode right = new InternalNode();
		System.arraycopy(internal.children, leftCount, right.children, 0, rightCount);
		System.arraycopy(internal.counts, leftCount, right.counts, 0, rightCount);
		right.childCount = rightCount;
		int rightSubtreeCount = 0;
		for (int i = 0; i < rightCount; i++) {
			right.children[i].parent = right;
			rightSubtreeCount += right.counts[i];
		}
		// clear the moved slots in the left node
		Arrays.fill(internal.children, leftCount, total, null);
		Arrays.fill(internal.counts, leftCount, total, 0);
		internal.childCount = leftCount;
		insertChildAfter(internal, right, rightSubtreeCount);
	}

	/**
	 * Removes an emptied leaf from the tree, recursively collapsing emptied ancestors and the root.
	 */
	private void removeEmptyLeaf(@Nonnull LeafNode leaf) {
		// unlink from the sibling chain
		if (leaf.prev != null) {
			leaf.prev.next = leaf.next;
		}
		if (leaf.next != null) {
			leaf.next.prev = leaf.prev;
		}
		final InternalNode parent = leaf.parent;
		if (parent == null) {
			// the leaf was the root and is now empty - the tree is empty
			this.root = null;
			return;
		}
		removeChild(parent, leaf);
	}

	/**
	 * Removes `child` from `parent`, recursively collapsing emptied ancestors and reducing the root height when an
	 * internal root is left with a single child.
	 */
	private void removeChild(@Nonnull InternalNode parent, @Nonnull Node child) {
		final int childIndex = parent.indexOfChild(child);
		System.arraycopy(parent.children, childIndex + 1, parent.children, childIndex, parent.childCount - childIndex - 1);
		System.arraycopy(parent.counts, childIndex + 1, parent.counts, childIndex, parent.childCount - childIndex - 1);
		parent.childCount--;
		parent.children[parent.childCount] = null;
		parent.counts[parent.childCount] = 0;
		if (parent.childCount == 0) {
			final InternalNode grandParent = parent.parent;
			if (grandParent == null) {
				this.root = null;
			} else {
				removeChild(grandParent, parent);
			}
		} else if (parent.parent == null && parent.childCount == 1) {
			// collapse a single-child root to reduce tree height
			final Node onlyChild = parent.children[0];
			onlyChild.parent = null;
			this.root = onlyChild;
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
	 * Produces the flat permutation array (logical position → record id) by walking the leaf sibling chain in order.
	 */
	@Nonnull
	private int[] flatten() {
		final int[] result = new int[this.size];
		if (this.root == null) {
			return result;
		}
		LeafNode leaf = leftmostLeaf();
		int position = 0;
		while (leaf != null) {
			System.arraycopy(leaf.recordIds, 0, result, position, leaf.count);
			position += leaf.count;
			leaf = leaf.next;
		}
		return result;
	}

	/**
	 * Returns the leftmost leaf of the tree (start of the logical order).
	 */
	@Nullable
	private LeafNode leftmostLeaf() {
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
	 * Base class for both node types. Carries the parent link used to walk up the tree when computing implicit
	 * positions and when propagating count deltas.
	 */
	abstract static class Node implements Serializable {
		@Serial private static final long serialVersionUID = 6037764042741656045L;
		/**
		 * Parent node, or `null` for the root.
		 */
		@Nullable InternalNode parent;
	}

	/**
	 * Leaf node holding up to {@link #BLOCK_SIZE} record ids in logical order, linked to its logical neighbours for a
	 * fast in-order flatten.
	 */
	static final class LeafNode extends Node {
		@Serial private static final long serialVersionUID = -2510718704128926730L;
		/**
		 * Record ids in logical order (only the first {@link #count} slots are valid).
		 */
		@Nonnull final int[] recordIds = new int[BLOCK_SIZE + 1];
		/**
		 * Number of valid record ids in this leaf.
		 */
		int count;
		/**
		 * Next leaf in logical order, or `null`.
		 */
		@Nullable LeafNode next;
		/**
		 * Previous leaf in logical order, or `null`.
		 */
		@Nullable LeafNode prev;
	}

	/**
	 * Internal node holding up to {@link #BLOCK_SIZE} children together with the element count of each child subtree
	 * (the augmentation that makes positions implicit).
	 */
	static final class InternalNode extends Node {
		@Serial private static final long serialVersionUID = 1791772842933035170L;
		/**
		 * Child nodes (only the first {@link #childCount} slots are valid).
		 */
		@Nonnull final Node[] children = new Node[BLOCK_SIZE + 1];
		/**
		 * Element count of each child subtree, aligned with {@link #children}.
		 */
		@Nonnull final int[] counts = new int[BLOCK_SIZE + 1];
		/**
		 * Number of valid children.
		 */
		int childCount;

		/**
		 * Returns the slot index of `child` among this node's children.
		 */
		int indexOfChild(@Nonnull Node child) {
			for (int i = 0; i < this.childCount; i++) {
				if (this.children[i] == child) {
					return i;
				}
			}
			throw new GenericEvitaInternalError(
				"Child node not found in parent!",
				"Inconsistent tree state!"
			);
		}
	}

}
