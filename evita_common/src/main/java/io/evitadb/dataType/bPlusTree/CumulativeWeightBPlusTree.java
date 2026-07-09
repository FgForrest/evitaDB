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

package io.evitadb.dataType.bPlusTree;

import io.evitadb.dataType.ConsistencySensitiveDataStructure;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * A **non-transactional, count-augmented (order-statistic) B+ tree** mapping a {@link Comparator}-ordered key `K` to an
 * `int` weight, answering cumulative-weight (rank) queries in `O(log n)`.
 *
 * Each distinct key carries a positive `int` weight; conceptually the tree models the multiset that repeats every key
 * `weight` times, sorted by the supplied comparator. The leaves hold `(key, weight)` pairs in key order; every internal
 * node carries, per child, the **total weight of that child's subtree** — the augmentation that makes the rank query
 * implicit. {@link #rankOf(Object)} descends by key, summing the subtree weights of the left siblings along the path
 * (plus the in-leaf weights of strictly-smaller keys), so it returns `Σ weight(k') for all k' < key` — equivalently the
 * start offset of `key`'s block in the weight-expanded sequence. The query honours insertion-point semantics: it is well
 * defined for absent keys too (the rank of the position the key would occupy).
 *
 * Descent is **cursor based** (the root → leaf path is captured during descent); there are no parent pointers and no
 * sibling links. Mutations touch only the cursor path: `O(log n)` weight re-stamps, plus small fixed-capacity block
 * shifts. The structure is **NOT thread-safe** and carries **no transactional memory layer** — it is intended as an
 * ephemeral, rebuildable acceleration structure. This is the deliberate difference from
 * `io.evitadb.index.array.UnorderedLookupTree`, whose count-augmented skeleton this mirrors: there is no copy-on-write,
 * no per-node diff layer, and — crucially — **no borrow/merge rebalancing on delete**. On removal an emptied leaf is
 * unlinked and single-child internals are spliced out, exactly like `UnorderedLookupTree`, but a merely under-full
 * (non-empty) node is left alone. Ranks stay exact regardless of node occupancy — merging is only a space optimisation
 * — so the no-merge policy trades a bounded worst-case space overhead (sparse leaves under delete-heavy churn) for a far
 * simpler, allocation-light implementation. Callers that care about the overhead rebuild the tree periodically.
 *
 * **This tree is NOT height-balanced.** A classic B+ tree keeps every leaf at the same depth and every node above a
 * minimum occupancy; the no-merge policy deliberately abandons both guarantees. Splicing a single-child internal
 * shortens only the one root → leaf path that passed through it, so after deletions different leaves may legitimately
 * sit at different depths, and nodes may be arbitrarily under-full. Do not assert equal leaf depth or minimum occupancy
 * against this structure. What IS guaranteed: every internal node keeps `>= 2` children (single-child internals are
 * spliced away) and every leaf is non-empty, and — because height only ever grows on a root split (an insert) and only
 * ever shrinks on a splice/collapse (a delete) — the height never exceeds `O(log n_peak)` for the largest size the tree
 * reached. Deletions do not re-balance, so a tree shrunk far below its peak can be taller than a freshly built tree of
 * the same size would be; the ephemeral rebuild reclaims that. Correctness of {@link #rankOf(Object)} never depends on
 * balance — only on the per-child subtree-weight augmentation, which is maintained on every mutation.
 *
 * Order-keys are not used here (unlike `UnorderedLookupTree`): routing is by the key itself. Because no-merge removal
 * never lowers a separator below the keys it guards (removing a leaf's minimum only raises that leaf's minimum), stale
 * separators remain valid lower bounds and routing stays correct without rewriting them on delete. The one case that
 * does require a separator refresh is inserting a new **minimum** into a non-leftmost leaf — handled by
 * {@link #refreshGuardingSeparatorForNewMinimum}.
 *
 * @param <K> the key type, ordered by the comparator supplied at construction
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NotThreadSafe
public class CumulativeWeightBPlusTree<K> implements ConsistencySensitiveDataStructure {
	/**
	 * Default fixed capacity of a single node block (leaf key slots and internal child slots alike). A power of two
	 * keeps the blocks small enough to be TLAB-allocated and cache friendly.
	 */
	public static final int DEFAULT_BLOCK_SIZE = 64;
	/**
	 * Upper bound on tree height used to size the descent cursor. With a block size `>= 2` and single-child internals
	 * spliced out, height is bounded by `log2(n)`; 64 covers the whole positive `int` range of distinct keys even for
	 * the smallest legal block size.
	 */
	private static final int MAX_HEIGHT = 64;

	/**
	 * Comparator establishing the key order. May order in any direction and apply any null handling — the tree only
	 * relies on it being a total order consistent with key distinctness.
	 */
	@Nonnull private final Comparator<? super K> comparator;
	/**
	 * Maximum number of keys per leaf and children per internal node before the node splits.
	 */
	private final int blockSize;
	/**
	 * Root node of the tree, or `null` when the tree is empty.
	 */
	@Nullable private Node root;
	/**
	 * Number of distinct keys held by the tree.
	 */
	private int size;
	/**
	 * Sum of the weights of all keys (equivalently the length of the weight-expanded sequence).
	 */
	private int totalWeight;

	/**
	 * Creates an empty tree with the {@link #DEFAULT_BLOCK_SIZE}.
	 *
	 * @param comparator the key ordering
	 */
	public CumulativeWeightBPlusTree(@Nonnull Comparator<? super K> comparator) {
		this(comparator, DEFAULT_BLOCK_SIZE);
	}

	/**
	 * Creates an empty tree with a custom block size (used by tests to force splitting at small sizes).
	 *
	 * @param comparator the key ordering
	 * @param blockSize  maximum keys per leaf / children per internal node (must be `>= 2`)
	 */
	public CumulativeWeightBPlusTree(@Nonnull Comparator<? super K> comparator, int blockSize) {
		Assert.isTrue(blockSize >= 2, "Block size must be at least 2!");
		this.comparator = comparator;
		this.blockSize = blockSize;
	}

	/**
	 * Returns the number of distinct keys held by the tree.
	 */
	public int size() {
		return this.size;
	}

	/**
	 * Returns `true` when the tree holds no keys.
	 */
	public boolean isEmpty() {
		return this.size == 0;
	}

	/**
	 * Returns the sum of the weights of all keys (the length of the weight-expanded sequence). Equals the rank of any
	 * key strictly greater than every key currently held.
	 *
	 * The total weight is held as an `int`, consistent with the engine's `int` record addressing — a single tree is
	 * expected to address at most {@link Integer#MAX_VALUE} weight-expanded positions.
	 */
	public int totalWeight() {
		return this.totalWeight;
	}

	/**
	 * Inserts a new key with the given positive weight. The key MUST NOT already be present.
	 *
	 * @param key    the key to insert
	 * @param weight the positive weight to associate with the key
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the weight is `< 1` or the key is already present
	 */
	public void insert(@Nonnull K key, int weight) {
		Assert.isPremiseValid(weight >= 1, "Weight must be a positive integer!");
		if (this.root == null) {
			final LeafNode leaf = new LeafNode(this.blockSize);
			leaf.keys[0] = key;
			leaf.weights[0] = weight;
			leaf.count = 1;
			this.root = leaf;
			this.size = 1;
			this.totalWeight = weight;
			return;
		}
		final Cursor cursor = new Cursor();
		final LeafNode leaf = descend(key, cursor);
		final int pos = leafInsertionIndex(leaf, key);
		Assert.isPremiseValid(
			pos >= leaf.count || compare(leaf.keys[pos], key) != 0,
			"Key is already present in the tree!"
		);
		// shift the suffix right and drop the new (key, weight) into place
		System.arraycopy(leaf.keys, pos, leaf.keys, pos + 1, leaf.count - pos);
		System.arraycopy(leaf.weights, pos, leaf.weights, pos + 1, leaf.count - pos);
		leaf.keys[pos] = key;
		leaf.weights[pos] = weight;
		leaf.count++;
		this.size++;
		this.totalWeight += weight;
		propagateWeightDelta(cursor, weight);
		// inserting a new minimum into a non-leftmost leaf invalidates the separator guarding that leaf
		if (pos == 0) {
			refreshGuardingSeparatorForNewMinimum(cursor, key);
		}
		if (leaf.count > this.blockSize) {
			splitLeaf(leaf, cursor);
		}
	}

	/**
	 * Removes the key from the tree. The key MUST be present.
	 *
	 * @param key the key to remove
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the key is not present
	 */
	public void remove(@Nonnull K key) {
		final Cursor cursor = new Cursor();
		final LeafNode leaf = descendToExistingKey(key, cursor);
		final int pos = cursor.leafIndex;
		final int weight = leaf.weights[pos];
		// shift the suffix left over the removed slot
		System.arraycopy(leaf.keys, pos + 1, leaf.keys, pos, leaf.count - pos - 1);
		System.arraycopy(leaf.weights, pos + 1, leaf.weights, pos, leaf.count - pos - 1);
		leaf.count--;
		leaf.keys[leaf.count] = null;
		this.size--;
		this.totalWeight -= weight;
		propagateWeightDelta(cursor, -weight);
		if (leaf.count == 0) {
			removeEmptyLeaf(cursor);
		}
	}

	/**
	 * Adjusts the weight of an already-present key by `delta`. The resulting weight MUST stay `>= 1` (use
	 * {@link #remove(Object)} to drop a key entirely). This is the hot path: it performs no structural change, only an
	 * `O(log n)` weight re-stamp up the cursor path.
	 *
	 * @param key   the present key whose weight is adjusted
	 * @param delta the signed weight change
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the key is absent or the result would be `< 1`
	 */
	public void updateWeight(@Nonnull K key, int delta) {
		final Cursor cursor = new Cursor();
		final LeafNode leaf = descendToExistingKey(key, cursor);
		final int pos = cursor.leafIndex;
		final int newWeight = leaf.weights[pos] + delta;
		Assert.isPremiseValid(newWeight >= 1, "Resulting weight must stay a positive integer!");
		leaf.weights[pos] = newWeight;
		this.totalWeight += delta;
		propagateWeightDelta(cursor, delta);
	}

	/**
	 * Returns `true` when the key is present in the tree.
	 */
	public boolean containsKey(@Nonnull K key) {
		if (this.root == null) {
			return false;
		}
		final LeafNode leaf = descend(key, null);
		return leafIndexOf(leaf, key) >= 0;
	}

	/**
	 * Returns the weight associated with the key, or `0` when the key is absent.
	 */
	public int weightOf(@Nonnull K key) {
		if (this.root == null) {
			return 0;
		}
		final LeafNode leaf = descend(key, null);
		final int pos = leafIndexOf(leaf, key);
		return pos >= 0 ? leaf.weights[pos] : 0;
	}

	/**
	 * Returns the cumulative weight of every key strictly less than `key` — i.e. `Σ weight(k') for all k' < key`. The
	 * result is well defined whether or not `key` is present: for an absent key it is the rank of the insertion point.
	 * For a present key it is the start offset of the key's block in the weight-expanded sequence.
	 *
	 * @param key the (possibly absent) key whose rank is computed
	 * @return the cumulative weight of all strictly-smaller keys
	 */
	public int rankOf(@Nonnull K key) {
		int prefix = 0;
		Node node = this.root;
		while (node instanceof final InternalNode internal) {
			int childIndex = 0;
			while (childIndex < internal.childCount - 1 && compare(key, internal.separators[childIndex]) >= 0) {
				prefix += internal.subtreeWeights[childIndex];
				childIndex++;
			}
			node = internal.children[childIndex];
		}
		if (node != null) {
			final LeafNode leaf = (LeafNode) node;
			for (int i = 0; i < leaf.count; i++) {
				if (compare(leaf.keys[i], key) < 0) {
					prefix += leaf.weights[i];
				} else {
					// leaf keys are sorted ascending - the first key >= the query ends the prefix
					break;
				}
			}
		}
		return prefix;
	}

	/**
	 * Visits every `(key, weight)` pair in ascending key order. Intended for verification and bulk read-out; allocates
	 * nothing beyond the caller's own bookkeeping.
	 *
	 * @param visitor callback invoked once per distinct key, in ascending order
	 */
	public void forEachEntry(@Nonnull EntryVisitor<K> visitor) {
		if (this.root != null) {
			forEachEntry(this.root, visitor);
		}
	}

	@Nonnull
	@Override
	public ConsistencyReport getConsistencyReport() {
		final List<String> errors = new ArrayList<>(8);
		final long[] accumulator = new long[2]; // [0] = distinct key count, [1] = total weight
		if (this.root != null) {
			verify(this.root, null, null, errors, accumulator);
		}
		if (accumulator[0] != this.size) {
			errors.add("Tracked size " + this.size + " does not match counted keys " + accumulator[0] + "!");
		}
		if (accumulator[1] != this.totalWeight) {
			errors.add("Tracked total weight " + this.totalWeight + " does not match counted weight " + accumulator[1] + "!");
		}
		return errors.isEmpty()
			? new ConsistencyReport(ConsistencyState.CONSISTENT, "The cumulative-weight B+ tree is consistent.")
			: new ConsistencyReport(ConsistencyState.BROKEN, String.join("\n", errors));
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder(16 + (this.size << 3));
		sb.append('{');
		final boolean[] first = {true};
		forEachEntry((key, weight) -> {
			if (!first[0]) {
				sb.append(", ");
			}
			first[0] = false;
			sb.append(key).append('=').append(weight);
		});
		sb.append('}');
		return sb.toString();
	}

	/**
	 * Compares two keys with the configured comparator (the stored slots are erased to {@link Object}).
	 */
	@SuppressWarnings("unchecked")
	private int compare(@Nullable Object a, @Nullable Object b) {
		return this.comparator.compare((K) a, (K) b);
	}

	/**
	 * Descends from the root to the leaf that owns (or would own) `key`, routing by separators. When `cursor` is
	 * non-null the root → leaf internal path is captured into it for a subsequent mutation; read-only callers pass
	 * `null`. Must only be called on a NON-EMPTY tree (callers guard the empty case): an empty tree is a programming
	 * error and fails fast, which is also what lets this method honour its `@Nonnull` return contract.
	 */
	@Nonnull
	private LeafNode descend(@Nonnull K key, @Nullable Cursor cursor) {
		Node node = this.root;
		if (node == null) {
			throw new GenericEvitaInternalError("Cannot descend into an empty tree!");
		}
		if (cursor != null) {
			cursor.depth = 0;
		}
		while (node instanceof final InternalNode internal) {
			int lo = 0;
			int hi = internal.childCount - 1;
			while (lo < hi) {
				final int mid = (lo + hi) >>> 1;
				if (compare(key, internal.separators[mid]) >= 0) {
					lo = mid + 1;
				} else {
					hi = mid;
				}
			}
			final int childIndex = lo;
			if (cursor != null) {
				cursor.push(internal, childIndex);
			}
			node = internal.children[childIndex];
		}
		return (LeafNode) node;
	}

	/**
	 * Descends to the leaf that owns `key` (which MUST be present), capturing the descent path into `cursor` and the
	 * in-leaf slot index into {@link Cursor#leafIndex}. Shared by {@link #remove(Object)} and
	 * {@link #updateWeight(Object, int)}, which both locate a present key before mutating it.
	 *
	 * @param key    the key expected to be present
	 * @param cursor the cursor to capture the path and the located slot index into
	 * @return the owning leaf (never `null`)
	 * @throws GenericEvitaInternalError when the key is not present (an empty tree counts as absent)
	 */
	@Nonnull
	private LeafNode descendToExistingKey(@Nonnull K key, @Nonnull Cursor cursor) {
		Assert.isPremiseValid(this.root != null, "Key is not present in the tree!");
		final LeafNode leaf = descend(key, cursor);
		final int pos = leafIndexOf(leaf, key);
		Assert.isPremiseValid(pos >= 0, "Key is not present in the tree!");
		cursor.leafIndex = pos;
		return leaf;
	}

	/**
	 * Returns the index at which `key` should be inserted to keep the leaf's keys ascending (the first slot holding a
	 * key `>= key`, or `count` when `key` exceeds all present keys).
	 */
	private int leafInsertionIndex(@Nonnull LeafNode leaf, @Nonnull K key) {
		int lo = 0;
		int hi = leaf.count - 1;
		while (lo <= hi) {
			final int mid = (lo + hi) >>> 1;
			final int cmp = compare(leaf.keys[mid], key);
			if (cmp < 0) {
				lo = mid + 1;
			} else {
				hi = mid - 1;
			}
		}
		return lo;
	}

	/**
	 * Returns the slot index of `key` within the leaf, or `-1` when absent.
	 */
	private int leafIndexOf(@Nonnull LeafNode leaf, @Nonnull K key) {
		final int pos = leafInsertionIndex(leaf, key);
		return pos < leaf.count && compare(leaf.keys[pos], key) == 0 ? pos : -1;
	}

	/**
	 * Adjusts the stored subtree weight of every internal node on the cursor path by `delta`.
	 */
	private static void propagateWeightDelta(@Nonnull Cursor cursor, int delta) {
		for (int level = 0; level < cursor.depth; level++) {
			cursor.path[level].subtreeWeights[cursor.idx[level]] += delta;
		}
	}

	/**
	 * Refreshes the separator that guards the leaf at the bottom of the cursor after a new minimum key was inserted into
	 * it. Walks up the cursor to the first ancestor where the descended child is not the leftmost one and rewrites that
	 * ancestor's bordering separator; if every ancestor was entered through its leftmost child the leaf is the global
	 * minimum and no separator guards it.
	 */
	private void refreshGuardingSeparatorForNewMinimum(@Nonnull Cursor cursor, @Nonnull K newMinimum) {
		for (int level = cursor.depth - 1; level >= 0; level--) {
			final int childIndex = cursor.idx[level];
			if (childIndex > 0) {
				cursor.path[level].separators[childIndex - 1] = newMinimum;
				return;
			}
		}
	}

	/**
	 * Splits an overflowing leaf into two even halves and propagates the new right leaf up the cursor.
	 */
	private void splitLeaf(@Nonnull LeafNode leaf, @Nonnull Cursor cursor) {
		final int total = leaf.count;
		final int leftCount = total / 2;
		final int rightCount = total - leftCount;
		final LeafNode right = new LeafNode(this.blockSize);
		System.arraycopy(leaf.keys, leftCount, right.keys, 0, rightCount);
		System.arraycopy(leaf.weights, leftCount, right.weights, 0, rightCount);
		right.count = rightCount;
		Arrays.fill(leaf.keys, leftCount, total, null);
		leaf.count = leftCount;
		int rightWeight = 0;
		for (int i = 0; i < rightCount; i++) {
			rightWeight += right.weights[i];
		}
		propagateSplit(cursor, cursor.depth - 1, right, right.keys[0], rightWeight);
	}

	/**
	 * Propagates a node split up the cursor: inserts `newRight` (with separator `rightMinKey` and subtree weight
	 * `rightWeight`) into the parent at `level`, growing a new root when the split reaches the top and splitting parents
	 * that overflow.
	 */
	private void propagateSplit(@Nonnull Cursor cursor, int level, @Nonnull Node newRight, @Nullable Object rightMinKey, int rightWeight) {
		Node right = newRight;
		Object rightKey = rightMinKey;
		int rightSubtreeWeight = rightWeight;
		while (true) {
			if (level < 0) {
				// the split reached the root - grow a new root above the two halves
				final Node oldRoot = this.root;
				Assert.isPremiseValid(oldRoot != null, "Split propagated above a missing root!");
				final InternalNode newRoot = new InternalNode(this.blockSize);
				newRoot.children[0] = oldRoot;
				newRoot.children[1] = right;
				newRoot.subtreeWeights[0] = subtreeWeight(oldRoot);
				newRoot.subtreeWeights[1] = rightSubtreeWeight;
				newRoot.separators[0] = rightKey;
				newRoot.childCount = 2;
				this.root = newRoot;
				return;
			}
			final InternalNode parent = cursor.path[level];
			final int ci = cursor.idx[level];
			// the left child's stored weight already includes the moved part; shed it before inserting the right node
			parent.subtreeWeights[ci] -= rightSubtreeWeight;
			insertIntoInternal(parent, ci, right, rightKey, rightSubtreeWeight);
			if (parent.childCount <= this.blockSize) {
				return;
			}
			// parent overflowed - split it and continue up the cursor
			final Object[] promotedKey = new Object[1];
			final int[] promotedWeight = new int[1];
			right = splitInternal(parent, promotedKey, promotedWeight);
			rightKey = promotedKey[0];
			rightSubtreeWeight = promotedWeight[0];
			level--;
		}
	}

	/**
	 * Inserts `newChild` (separator `minKey`, subtree weight `childWeight`) into `parent` immediately after child `ci`.
	 */
	private static void insertIntoInternal(@Nonnull InternalNode parent, int ci, @Nonnull Node newChild, @Nullable Object minKey, int childWeight) {
		final int childCount = parent.childCount;
		final int target = ci + 1;
		System.arraycopy(parent.children, target, parent.children, target + 1, childCount - target);
		System.arraycopy(parent.subtreeWeights, target, parent.subtreeWeights, target + 1, childCount - target);
		// the separator between child ci and the new child goes at index ci, shifting the rest right
		System.arraycopy(parent.separators, ci, parent.separators, ci + 1, childCount - 1 - ci);
		parent.children[target] = newChild;
		parent.subtreeWeights[target] = childWeight;
		parent.separators[ci] = minKey;
		parent.childCount = childCount + 1;
	}

	/**
	 * Splits an overflowing internal node into two halves, returning the new right node and reporting (through the
	 * single-element out-parameters) the promoted separator key and the right node's subtree weight.
	 */
	@Nonnull
	private InternalNode splitInternal(@Nonnull InternalNode node, @Nonnull Object[] promotedKey, @Nonnull int[] promotedWeight) {
		final int total = node.childCount;
		final int leftCount = total / 2;
		final int rightCount = total - leftCount;
		final InternalNode right = new InternalNode(this.blockSize);
		System.arraycopy(node.children, leftCount, right.children, 0, rightCount);
		System.arraycopy(node.subtreeWeights, leftCount, right.subtreeWeights, 0, rightCount);
		// right keeps only the separators strictly inside its child range (rightCount - 1 of them)
		System.arraycopy(node.separators, leftCount, right.separators, 0, rightCount - 1);
		right.childCount = rightCount;
		// the separator between the two halves is promoted to the parent (kept in neither node)
		promotedKey[0] = node.separators[leftCount - 1];
		int rightWeight = 0;
		for (int i = 0; i < rightCount; i++) {
			rightWeight += right.subtreeWeights[i];
		}
		promotedWeight[0] = rightWeight;
		Arrays.fill(node.children, leftCount, total, null);
		Arrays.fill(node.separators, leftCount - 1, total - 1, null);
		node.childCount = leftCount;
		return right;
	}

	/**
	 * Returns the total weight held in the subtree rooted at `node`.
	 */
	private static int subtreeWeight(@Nonnull Node node) {
		if (node instanceof final LeafNode leaf) {
			int sum = 0;
			for (int i = 0; i < leaf.count; i++) {
				sum += leaf.weights[i];
			}
			return sum;
		}
		final InternalNode internal = (InternalNode) node;
		int sum = 0;
		for (int i = 0; i < internal.childCount; i++) {
			sum += internal.subtreeWeights[i];
		}
		return sum;
	}

	/**
	 * Removes an emptied leaf from the tree, walking up the cursor to unlink emptied ancestors, splice out single-child
	 * internals and reduce the root height. No borrow/merge is performed on merely under-full nodes (the no-merge
	 * policy).
	 */
	private void removeEmptyLeaf(@Nonnull Cursor cursor) {
		if (cursor.depth == 0) {
			// the leaf was the root and is now empty - the tree is empty
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
					// splice a single-child internal out of its parent (keep internals >= 2 children); the surviving
					// child carries the same subtree weight, so the grandparent's stored weight stays correct
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
		final int childCount = internal.childCount;
		System.arraycopy(internal.children, ci + 1, internal.children, ci, childCount - ci - 1);
		System.arraycopy(internal.subtreeWeights, ci + 1, internal.subtreeWeights, ci, childCount - ci - 1);
		// drop the separator that bordered the removed child (the one after it, or the last one when removing the tail)
		final int separatorIndex = ci < childCount - 1 ? ci : ci - 1;
		if (separatorIndex >= 0) {
			System.arraycopy(internal.separators, separatorIndex + 1, internal.separators, separatorIndex, childCount - separatorIndex - 2);
		}
		final int newChildCount = childCount - 1;
		internal.childCount = newChildCount;
		internal.children[newChildCount] = null;
		internal.subtreeWeights[newChildCount] = 0;
		if (newChildCount >= 1) {
			internal.separators[newChildCount - 1] = null;
		}
	}

	/**
	 * Recursively visits every `(key, weight)` pair in ascending key order.
	 */
	@SuppressWarnings("unchecked")
	private static <K> void forEachEntry(@Nonnull Node node, @Nonnull EntryVisitor<K> visitor) {
		if (node instanceof final LeafNode leaf) {
			for (int i = 0; i < leaf.count; i++) {
				visitor.visit((K) leaf.keys[i], leaf.weights[i]);
			}
		} else {
			final InternalNode internal = (InternalNode) node;
			for (int i = 0; i < internal.childCount; i++) {
				forEachEntry(internal.children[i], visitor);
			}
		}
	}

	/**
	 * Recursively verifies the subtree rooted at `node`: ascending sorted leaf keys with positive weights, separator
	 * ordering, keys falling inside the separator-defined `[lowerBound, upperBound)` window, `>= 2` children per
	 * non-root internal, non-empty non-root leaves, and stored subtree weights matching the recomputed sums.
	 * Accumulates the distinct key count and total weight into `accumulator`. Returns the subtree weight of `node`.
	 *
	 * Note: leaf depth is deliberately NOT checked — the no-merge policy makes the tree unbalanced (leaves may sit at
	 * different depths), which is a legal state, not an inconsistency.
	 *
	 * @param node        the node to verify
	 * @param lowerBound  inclusive lower bound every key in the subtree must satisfy (`key >= lowerBound`), or `null`
	 * @param upperBound  exclusive upper bound every key in the subtree must satisfy (`key < upperBound`), or `null`
	 * @param errors      sink for human-readable inconsistency descriptions
	 * @param accumulator `[0]` running distinct key count, `[1]` running total weight
	 * @return the total weight held in the subtree rooted at `node`
	 */
	private int verify(
		@Nonnull Node node,
		@Nullable Object lowerBound,
		@Nullable Object upperBound,
		@Nonnull List<String> errors,
		@Nonnull long[] accumulator
	) {
		if (node instanceof final LeafNode leaf) {
			if (leaf.count < 1 && this.root != leaf) {
				errors.add("Empty non-root leaf encountered!");
			}
			if (leaf.count > this.blockSize) {
				errors.add("Leaf overflow: " + leaf.count + " > block size " + this.blockSize + "!");
			}
			int weight = 0;
			for (int i = 0; i < leaf.count; i++) {
				if (leaf.weights[i] < 1) {
					errors.add("Non-positive weight " + leaf.weights[i] + " for key " + leaf.keys[i] + "!");
				}
				if (i > 0 && compare(leaf.keys[i - 1], leaf.keys[i]) >= 0) {
					errors.add("Leaf keys not strictly ascending around " + leaf.keys[i] + "!");
				}
				// lower bound is INCLUSIVE: a key may equal its guarding separator (the separator is that key's block
				// minimum); upper bound is EXCLUSIVE
				if (lowerBound != null && compare(leaf.keys[i], lowerBound) < 0) {
					errors.add("Leaf key " + leaf.keys[i] + " violates lower bound " + lowerBound + "!");
				}
				if (upperBound != null && compare(leaf.keys[i], upperBound) >= 0) {
					errors.add("Leaf key " + leaf.keys[i] + " violates upper bound " + upperBound + "!");
				}
				weight += leaf.weights[i];
				accumulator[0]++;
				accumulator[1] += leaf.weights[i];
			}
			return weight;
		}
		final InternalNode internal = (InternalNode) node;
		if (internal.childCount < 2 && this.root != internal) {
			errors.add("Internal node with fewer than 2 children encountered (" + internal.childCount + ")!");
		}
		if (internal.childCount > this.blockSize) {
			errors.add("Internal overflow: " + internal.childCount + " > block size " + this.blockSize + "!");
		}
		// separators must be strictly ascending (there are childCount - 1 of them)
		for (int i = 1; i < internal.childCount - 1; i++) {
			if (compare(internal.separators[i - 1], internal.separators[i]) >= 0) {
				errors.add("Separators not strictly ascending around index " + (i - 1) + "!");
			}
		}
		int sum = 0;
		for (int i = 0; i < internal.childCount; i++) {
			// child i is bounded below (inclusive) by separators[i-1] and above (exclusive) by separators[i]; the outer
			// bounds are inherited at the left/right edges. No-merge keeps separators valid (possibly loose) lower bounds.
			final Object childLower = i == 0 ? lowerBound : internal.separators[i - 1];
			final Object childUpper = i == internal.childCount - 1 ? upperBound : internal.separators[i];
			final int childWeight = verify(internal.children[i], childLower, childUpper, errors, accumulator);
			if (childWeight != internal.subtreeWeights[i]) {
				errors.add("Stored subtree weight " + internal.subtreeWeights[i] + " != actual " + childWeight + " at child " + i + "!");
			}
			sum += childWeight;
		}
		return sum;
	}

	/**
	 * Callback for {@link #forEachEntry(EntryVisitor)} receiving each `(key, weight)` pair in ascending key order.
	 *
	 * @param <K> the key type
	 */
	@FunctionalInterface
	public interface EntryVisitor<K> {

		/**
		 * Receives a single key and its weight.
		 *
		 * @param key    the key
		 * @param weight the positive weight associated with the key
		 */
		void visit(@Nonnull K key, int weight);

	}

	/**
	 * Mutable descent cursor capturing the internal-node path from the root to a leaf. Reused per operation; never
	 * escapes the structure.
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
		 * In-leaf slot index of the located key, set by {@link #descendToExistingKey}.
		 */
		int leafIndex;

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
	 * Marker base type for both node kinds. Nodes are deliberately non-generic and store keys as {@link Object}
	 * (cast back to `K` through the comparator boundary) to avoid generic-array creation.
	 */
	private abstract static class Node {
	}

	/**
	 * Leaf node holding up to {@link #blockSize} `(key, weight)` pairs in ascending key order.
	 */
	private static final class LeafNode extends Node {
		/**
		 * Keys in ascending order (only the first {@link #count} slots are valid). One slot of head-room is reserved so
		 * an overflowing leaf can be filled before the immediate split.
		 */
		@Nonnull final Object[] keys;
		/**
		 * Positive weight of each key, aligned with {@link #keys}.
		 */
		@Nonnull final int[] weights;
		/**
		 * Number of valid keys in this leaf.
		 */
		int count;

		LeafNode(int blockSize) {
			this.keys = new Object[blockSize + 1];
			this.weights = new int[blockSize + 1];
		}
	}

	/**
	 * Internal node routing by key separators and carrying the total subtree weight of each child. Holds up to
	 * {@link #blockSize} children and `childCount - 1` separators (`separators[i]` is a lower bound on the keys in
	 * `children[i + 1]`).
	 */
	private static final class InternalNode extends Node {
		/**
		 * Child nodes (only the first {@link #childCount} slots are valid). One slot of head-room is reserved for the
		 * pre-split overflow.
		 */
		@Nonnull final Node[] children;
		/**
		 * Separator keys; `separators[i]` is a lower bound on the keys found in `children[i + 1]`'s subtree.
		 */
		@Nonnull final Object[] separators;
		/**
		 * Total weight of each child's subtree, aligned with {@link #children}.
		 */
		@Nonnull final int[] subtreeWeights;
		/**
		 * Number of valid children.
		 */
		int childCount;

		InternalNode(int blockSize) {
			this.children = new Node[blockSize + 1];
			this.separators = new Object[blockSize];
			this.subtreeWeights = new int[blockSize + 1];
		}
	}

}
