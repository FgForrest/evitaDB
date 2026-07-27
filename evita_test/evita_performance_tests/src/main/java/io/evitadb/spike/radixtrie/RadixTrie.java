/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.spike.radixtrie;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * A **path-compressed**, order-preserving radix trie over unsigned {@code byte[]} keys (an ART-lite: path
 * compression + per-node sorted child arrays, without ART's adaptive node-size taxonomy which is a future
 * memory optimization, not needed to demonstrate the prefix-sharing thesis). This is the
 * **non-transactional spike** structure used to measure whether storing distinct attribute values in a
 * prefix-shared trie reclaims meaningful heap versus the current {@code TransactionalObjectBPlusTree}, which
 * keeps one full value object per distinct value.
 *
 * Each node carries the compressed edge label ({@code prefix}) consumed to reach it from its parent, an
 * optional terminal {@code value} (present iff a key ends exactly at this node — variable-length keys are
 * handled by value-presence rather than a sentinel terminator byte), and, for branch nodes, parallel sorted
 * arrays of child first-bytes and child references. Keys that share a prefix share the chain of edges that
 * spell it, which is where the memory is saved.
 *
 * Supported operations (sufficient for the equals + range benchmark):
 * - {@link #put(byte[], Object)} / {@link #get(byte[])} — exact match in `O(W)`, `W` = key length.
 * - {@link #rangeCollect(byte[], byte[], Consumer)} — inclusive ordered range with subtree pruning and a
 *   "fully inside" fast path that hands an entire subtree to the sink without per-key bound checks.
 *
 * Not thread-safe; intended for single-threaded build-then-measure use.
 *
 * @param <V> the payload stored at terminal nodes (e.g. a record-id set)
 * @author Claude (radix-trie memory spike), FG Forrest a.s. (c) 2026
 */
public final class RadixTrie<V> {
	private static final byte[] EMPTY = new byte[0];
	private final Node<V> root = new Node<>(EMPTY);
	private int size;

	/**
	 * Associates the given value with the key, returning any value previously stored under it.
	 *
	 * @param key   the order-preserving encoded key (never {@code null})
	 * @param value the payload to store (never {@code null})
	 * @return the previously stored value, or {@code null} if the key was absent
	 */
	@Nullable
	public V put(@Nonnull byte[] key, @Nonnull V value) {
		Node<V> cur = this.root;
		int pos = 0;
		while (true) {
			if (pos == key.length) {
				// the key terminates exactly at the current node
				final V old = cur.value;
				cur.value = value;
				if (old == null) {
					this.size++;
				}
				return old;
			}
			final byte b = key[pos];
			final int ci = cur.indexOfChild(b);
			if (ci < 0) {
				// no child branches on this byte yet — attach the whole remaining suffix as a fresh leaf
				final Node<V> leaf = new Node<>(Arrays.copyOfRange(key, pos, key.length));
				leaf.value = value;
				cur.insertChild(-(ci + 1), leaf);
				this.size++;
				return null;
			}
			final Node<V> child = cur.children[ci];
			final int common = commonPrefixLength(child.prefix, key, pos);
			if (common == child.prefix.length) {
				// the child's whole edge label matched — descend and continue
				pos += common;
				cur = child;
				continue;
			}
			// partial edge match: split the child edge at `common`
			final Node<V> mid = new Node<>(Arrays.copyOfRange(child.prefix, 0, common));
			child.prefix = Arrays.copyOfRange(child.prefix, common, child.prefix.length);
			mid.insertChild(0, child);
			cur.children[ci] = mid;
			if (pos + common == key.length) {
				// the new key ends exactly at the split point
				mid.value = value;
			} else {
				// the new key diverges past the split point — attach its tail as a leaf under `mid`.
				// leaf's first byte necessarily differs from the retained child's first byte (that divergence
				// is exactly why the common-prefix match stopped here), so it is always a fresh slot.
				final Node<V> leaf = new Node<>(Arrays.copyOfRange(key, pos + common, key.length));
				leaf.value = value;
				final int leafAt = mid.indexOfChild(leaf.prefix[0]);
				mid.insertChild(-(leafAt + 1), leaf);
			}
			this.size++;
			return null;
		}
	}

	/**
	 * Looks up the value stored under the exact key.
	 *
	 * @param key the order-preserving encoded key (never {@code null})
	 * @return the stored value, or {@code null} if absent
	 */
	@Nullable
	public V get(@Nonnull byte[] key) {
		Node<V> cur = this.root;
		int pos = 0;
		while (true) {
			if (pos == key.length) {
				return cur.value;
			}
			final int ci = cur.indexOfChild(key[pos]);
			if (ci < 0) {
				return null;
			}
			final Node<V> child = cur.children[ci];
			final byte[] prefix = child.prefix;
			if (pos + prefix.length > key.length || !regionMatches(prefix, key, pos)) {
				return null;
			}
			pos += prefix.length;
			cur = child;
		}
	}

	/**
	 * Collects, in ascending key order, the values of all keys in the inclusive range {@code [from, to]}.
	 * Subtrees wholly outside the range are pruned; subtrees wholly inside are streamed to the sink without
	 * per-key bound checks (the radix-trie range fast path).
	 *
	 * @param from inclusive lower bound (order-preserving encoded key)
	 * @param to   inclusive upper bound (order-preserving encoded key)
	 * @param sink receives each in-range value
	 */
	public void rangeCollect(@Nonnull byte[] from, @Nonnull byte[] to, @Nonnull Consumer<V> sink) {
		rangeCollect(this.root, EMPTY, from, to, sink);
	}

	/**
	 * @return the number of distinct keys stored
	 */
	public int size() {
		return this.size;
	}

	/**
	 * @return the number of trie nodes (diagnostic; reflects structural overhead)
	 */
	public int nodeCount() {
		return countNodes(this.root);
	}

	/* ============================================================================================ */

	/**
	 * Recursive in-order range walk with pruning and a fully-inside fast path.
	 *
	 * @param node     the current node
	 * @param nodePath the full encoded path leading into `node`
	 * @param from     inclusive lower bound
	 * @param to       inclusive upper bound
	 * @param sink     value receiver
	 */
	private static <V> void rangeCollect(
		@Nonnull Node<V> node,
		@Nonnull byte[] nodePath,
		@Nonnull byte[] from,
		@Nonnull byte[] to,
		@Nonnull Consumer<V> sink
	) {
		// the terminal value at this node corresponds exactly to key == nodePath
		if (node.value != null && compareUnsigned(nodePath, from) >= 0 && compareUnsigned(nodePath, to) <= 0) {
			sink.accept(node.value);
		}
		if (node.children == null) {
			return;
		}
		for (int i = 0; i < node.children.length; i++) {
			final Node<V> child = node.children[i];
			final byte[] childPath = concat(nodePath, child.prefix);
			// prune child subtrees that cannot intersect [from, to]
			if (!supGreaterOrEqual(childPath, from) || !infLessOrEqual(childPath, to)) {
				continue;
			}
			// fast path: the whole subtree lies within [from, to] → no per-key checks needed
			if (infGreaterOrEqual(childPath, from) && supLessOrEqual(childPath, to)) {
				collectAll(child, sink);
			} else {
				rangeCollect(child, childPath, from, to, sink);
			}
		}
	}

	/**
	 * Streams every value in the subtree rooted at `node` to the sink, in ascending key order.
	 */
	private static <V> void collectAll(@Nonnull Node<V> node, @Nonnull Consumer<V> sink) {
		if (node.value != null) {
			sink.accept(node.value);
		}
		if (node.children != null) {
			for (int i = 0; i < node.children.length; i++) {
				collectAll(node.children[i], sink);
			}
		}
	}

	private static <V> int countNodes(@Nonnull Node<V> node) {
		int count = 1;
		if (node.children != null) {
			for (int i = 0; i < node.children.length; i++) {
				count += countNodes(node.children[i]);
			}
		}
		return count;
	}

	/* ============================ unsigned byte / prefix comparisons ============================ */

	/**
	 * @return length of the shared prefix between `prefix` and `key[keyStart..]`
	 */
	private static int commonPrefixLength(@Nonnull byte[] prefix, @Nonnull byte[] key, int keyStart) {
		final int max = Math.min(prefix.length, key.length - keyStart);
		int i = 0;
		while (i < max && prefix[i] == key[keyStart + i]) {
			i++;
		}
		return i;
	}

	/**
	 * @return true if `key[keyStart..keyStart+prefix.length]` equals `prefix`
	 */
	private static boolean regionMatches(@Nonnull byte[] prefix, @Nonnull byte[] key, int keyStart) {
		for (int i = 0; i < prefix.length; i++) {
			if (prefix[i] != key[keyStart + i]) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Unsigned lexicographic comparison of two byte arrays (shorter-is-smaller on a shared prefix).
	 */
	private static int compareUnsigned(@Nonnull byte[] a, @Nonnull byte[] b) {
		final int max = Math.min(a.length, b.length);
		for (int i = 0; i < max; i++) {
			final int d = (a[i] & 0xFF) - (b[i] & 0xFF);
			if (d != 0) {
				return d;
			}
		}
		return a.length - b.length;
	}

	/**
	 * `ceil(P) >= F`: may the subtree with prefix `p` contain a key `>= f`? (supremum = `p` then all 0xFF)
	 */
	private static boolean supGreaterOrEqual(@Nonnull byte[] p, @Nonnull byte[] f) {
		final int max = Math.min(p.length, f.length);
		for (int i = 0; i < max; i++) {
			final int d = (p[i] & 0xFF) - (f[i] & 0xFF);
			if (d != 0) {
				return d > 0;
			}
		}
		// no mismatch on the shared length: ceil(p) >= f always holds (trailing 0xFF dominates)
		return true;
	}

	/**
	 * `floor(P) <= T`: may the subtree with prefix `p` contain a key `<= t`? (infimum = `p` then all 0x00)
	 */
	private static boolean infLessOrEqual(@Nonnull byte[] p, @Nonnull byte[] t) {
		final int max = Math.min(p.length, t.length);
		for (int i = 0; i < max; i++) {
			final int d = (p[i] & 0xFF) - (t[i] & 0xFF);
			if (d != 0) {
				return d < 0;
			}
		}
		// no mismatch on the shared length: floor(p) <= t iff p is no longer than t
		return p.length <= t.length;
	}

	/**
	 * `floor(P) >= F` (conservative): is the whole subtree with prefix `p` at or above `f`? A false negative
	 * merely forgoes the fast path and falls back to a correct recursive walk.
	 */
	private static boolean infGreaterOrEqual(@Nonnull byte[] p, @Nonnull byte[] f) {
		final int max = Math.min(p.length, f.length);
		for (int i = 0; i < max; i++) {
			final int d = (p[i] & 0xFF) - (f[i] & 0xFF);
			if (d != 0) {
				return d > 0;
			}
		}
		return p.length >= f.length;
	}

	/**
	 * `ceil(P) <= T` (conservative): is the whole subtree with prefix `p` at or below `t`? A false negative
	 * merely forgoes the fast path and falls back to a correct recursive walk.
	 */
	private static boolean supLessOrEqual(@Nonnull byte[] p, @Nonnull byte[] t) {
		final int max = Math.min(p.length, t.length);
		for (int i = 0; i < max; i++) {
			final int d = (p[i] & 0xFF) - (t[i] & 0xFF);
			if (d != 0) {
				return d < 0;
			}
		}
		// shared prefix with no mismatch: ceil(p) = p + 0xFF... which can only be <= t when p == t exactly
		return p.length == 0 && t.length == 0;
	}

	/**
	 * @return concatenation of two byte arrays
	 */
	@Nonnull
	private static byte[] concat(@Nonnull byte[] a, @Nonnull byte[] b) {
		if (a.length == 0) {
			return b;
		}
		if (b.length == 0) {
			return a;
		}
		final byte[] out = new byte[a.length + b.length];
		System.arraycopy(a, 0, out, 0, a.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}

	/* ============================================================================================ */

	/**
	 * A radix-trie node: a compressed edge label, an optional terminal value, and (for branch nodes) parallel
	 * sorted arrays mapping each child's first byte to the child node. Leaf nodes keep the child arrays
	 * {@code null} to minimise footprint.
	 */
	static final class Node<V> {
		byte[] prefix;
		@Nullable V value;
		@Nullable byte[] childBytes;
		@Nullable Node<V>[] children;

		Node(@Nonnull byte[] prefix) {
			this.prefix = prefix;
		}

		/**
		 * Binary-searches the sorted child array for the given first byte (unsigned).
		 *
		 * @return the child index, or {@code -(insertionPoint) - 1} when absent
		 */
		int indexOfChild(byte b) {
			final byte[] keys = this.childBytes;
			if (keys == null) {
				return -1;
			}
			final int target = b & 0xFF;
			int lo = 0;
			int hi = keys.length - 1;
			while (lo <= hi) {
				final int mid = (lo + hi) >>> 1;
				final int cmp = (keys[mid] & 0xFF) - target;
				if (cmp < 0) {
					lo = mid + 1;
				} else if (cmp > 0) {
					hi = mid - 1;
				} else {
					return mid;
				}
			}
			return -(lo + 1);
		}

		/**
		 * Inserts a child at the given array position (keeping the arrays sorted by first byte).
		 *
		 * @param at    the insertion index (as returned via {@link #indexOfChild(byte)})
		 * @param child the child node to attach
		 */
		@SuppressWarnings("unchecked")
		void insertChild(int at, @Nonnull Node<V> child) {
			final byte first = child.prefix[0];
			if (this.children == null) {
				this.childBytes = new byte[]{first};
				this.children = new Node[]{child};
				return;
			}
			final int n = this.children.length;
			final byte[] newBytes = new byte[n + 1];
			final Node<V>[] newChildren = new Node[n + 1];
			System.arraycopy(this.childBytes, 0, newBytes, 0, at);
			System.arraycopy(this.children, 0, newChildren, 0, at);
			newBytes[at] = first;
			newChildren[at] = child;
			System.arraycopy(this.childBytes, at, newBytes, at + 1, n - at);
			System.arraycopy(this.children, at, newChildren, at + 1, n - at);
			this.childBytes = newBytes;
			this.children = newChildren;
		}
	}
}
