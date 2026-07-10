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

package io.evitadb.dataType.champ;

import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.lang.invoke.VarHandle;
import java.util.AbstractCollection;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Immutable, persistent hash map based on the CHAMP (Compressed Hash-Array Mapped Prefix-tree)
 * data structure described by Steindorfer & Vinju (OOPSLA'15). The structure is a 32-way hash
 * trie whose nodes are at most 32-slot arrays; modifications are performed by path-copying, so an
 * update or removal touches only `O(log₃₂ N)` nodes and shares the remaining structure with the
 * previous version. This makes it ideal for multi-version concurrency control: retaining an old
 * snapshot costs almost nothing, and lock-free readers can observe a consistent root via a single
 * volatile publication.
 *
 * Compared to a plain Hash-Array Mapped Trie (Bagwell), CHAMP keeps two separate bitmaps
 * (`dataMap` for inlined key/value payload and `nodeMap` for child sub-nodes) and stores payload at
 * the front of the backing array with sub-nodes packed (reversed) at the back. Crucially it
 * maintains a *canonical* form: after every removal a node that would hold a single surviving entry
 * is inlined back into its parent, so two maps with equal content always have byte-identical
 * structure. That canonicalization is what makes structural {@link #equals(Object)} sub-linear.
 *
 * This is a clean-room reimplementation: the algorithm was studied from the Scala standard library
 * `scala.collection.immutable.HashMap` (Apache-2.0) and the original CHAMP paper, but no code was
 * copied. Credit to Michael J. Steindorfer and Jurgen J. Vinju (CHAMP), Phil Bagwell (HAMT) and the
 * Scala standard-library authors.
 *
 * Differences from the Scala reference, tailored to evitaDB:
 *
 * - generic `<K, V>` but **no `null` key or value support** (the intended keys/values — `RecordKey`
 *   / `FileLocation` — are always non-null, which removes a whole class of branches);
 * - implements the read surface of {@link Map} so the structure can be dropped into call
 *   sites that expect a `Map`, while all mutators throw {@link UnsupportedOperationException};
 * - the persistent mutators are the dedicated {@link #updated(Object, Object)} /
 *   {@link #removed(Object)} methods returning a new instance, and {@link #merged} for whole-map
 *   union with a conflict resolver;
 * - a single-threaded {@link Builder} (a "transient") allows building a map from scratch in
 *   `O(M)` instead of `O(M·log₃₂ M)`.
 *
 * All instances are deeply immutable: the `rootNode` reference is `final` and no node reachable
 * from a published map is ever mutated again, so a single instance may be shared and read
 * concurrently by any number of threads without external synchronisation. Safe publication is the
 * caller's responsibility — a thread observes a fully-constructed map only when the reference
 * reaches it through a happens-before edge, so store it in a `final` field, a `volatile` field or
 * an {@link AtomicReference} (as the OffsetIndex root is), never in a
 * plain mutable field read without synchronisation. The constructor issues a
 * {@link VarHandle#releaseFence()} so the in-place node writes performed while
 * building are flushed before the reference can escape; the caller still supplies the reader-side
 * happens-before. The {@link Builder} is mutable and must be confined to a single thread.
 *
 * @param <K> the key type (non-null)
 * @param <V> the value type (non-null)
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Immutable
@ThreadSafe
public final class ChampMap<K, V> implements Map<K, V> {

	/* ===========================================================================================
	 * Trie geometry and bit-arithmetic constants.
	 * =========================================================================================== */

	/** Width of a Java hash code in bits. */
	private static final int HASH_CODE_LENGTH = 32;
	/** Number of hash bits consumed at each trie level (5 bits → 32-way branching). */
	private static final int BIT_PARTITION_SIZE = 5;
	/** Mask isolating the {@link #BIT_PARTITION_SIZE} low bits. */
	private static final int BIT_PARTITION_MASK = (1 << BIT_PARTITION_SIZE) - 1;
	/** Maximum trie depth = ceil(32 / 5) = 7 levels. */
	private static final int MAX_DEPTH = 7;
	/** Branching factor at every node (2^5 = 32). */
	private static final int BRANCHING_FACTOR = 1 << BIT_PARTITION_SIZE;
	/** Each inlined payload occupies two array slots: the key and the value. */
	private static final int TUPLE_LENGTH = 2;

	private static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];
	private static final int[] EMPTY_INT_ARRAY = new int[0];

	/** Shared, immutable empty root node (never mutated). */
	private static final BitmapIndexedMapNode<?, ?> EMPTY_NODE =
		new BitmapIndexedMapNode<>(0, 0, EMPTY_OBJECT_ARRAY, EMPTY_INT_ARRAY, 0, 0);

	/** Shared empty map instance. */
	private static final ChampMap<?, ?> EMPTY = new ChampMap<>(emptyNode());

	/** The root node of the trie; never null, possibly the shared empty node. */
	private final BitmapIndexedMapNode<K, V> rootNode;

	/* ===========================================================================================
	 * Construction and factories.
	 * =========================================================================================== */

	private ChampMap(@Nonnull BitmapIndexedMapNode<K, V> rootNode) {
		this.rootNode = rootNode;
		// the root node may have been mutated in place while building (Builder); the fence makes
		// those writes visible before the reference to this map can escape to another thread
		VarHandle.releaseFence();
	}

	/**
	 * Returns the canonical empty map. Every call yields the same shared singleton instance.
	 *
	 * @return the shared empty map
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	public static <K, V> ChampMap<K, V> empty() {
		return (ChampMap<K, V>) EMPTY;
	}

	/**
	 * Returns a singleton map holding the passed key/value pair.
	 *
	 * @param key   the sole key (non-null)
	 * @param value the value bound to `key` (non-null)
	 * @return a one-entry map
	 */
	@Nonnull
	public static <K, V> ChampMap<K, V> of(@Nonnull K key, @Nonnull V value) {
		return ChampMap.<K, V>empty().updated(key, value);
	}

	/**
	 * Builds a {@link ChampMap} containing all entries of the passed source map. The source is
	 * iterated once through a {@link Builder}, so construction is `O(M)`.
	 *
	 * @param source the entries to copy; if it is already a {@link ChampMap} it is returned as-is
	 * @return a map holding every entry of `source`
	 */
	@Nonnull
	public static <K, V> ChampMap<K, V> from(@Nonnull Map<? extends K, ? extends V> source) {
		if (source instanceof ChampMap) {
			//noinspection unchecked
			return (ChampMap<K, V>) source;
		}
		final Builder<K, V> builder = new Builder<>();
		for (final Entry<? extends K, ? extends V> entry : source.entrySet()) {
			builder.add(entry.getKey(), entry.getValue());
		}
		return builder.build();
	}

	/**
	 * Creates a new, empty {@link Builder} (a transient) for assembling a map from scratch in
	 * `O(M)` time. The builder is single-threaded.
	 *
	 * @return a fresh, empty builder
	 */
	@Nonnull
	public static <K, V> Builder<K, V> builder() {
		return new Builder<>();
	}

	@Nonnull
	@SuppressWarnings("unchecked")
	private static <K, V> BitmapIndexedMapNode<K, V> emptyNode() {
		return (BitmapIndexedMapNode<K, V>) EMPTY_NODE;
	}

	/* ===========================================================================================
	 * Persistent (copy-on-write) mutators returning a new instance.
	 * =========================================================================================== */

	/**
	 * Returns a copy of this map with `key` associated to `value`. If the key is already present
	 * with the very same value instance, this map is returned unchanged.
	 *
	 * @param key   the key to bind (non-null)
	 * @param value the value to bind to `key` (non-null)
	 * @return a new map with the binding applied, or this map if nothing changed
	 */
	@Nonnull
	public ChampMap<K, V> updated(@Nonnull K key, @Nonnull V value) {
		Objects.requireNonNull(key, "key must not be null");
		Objects.requireNonNull(value, "value must not be null");
		final int originalHash = key.hashCode();
		final BitmapIndexedMapNode<K, V> newRoot =
			this.rootNode.updated(key, value, originalHash, improve(originalHash), 0, true);
		return newRoot == this.rootNode ? this : new ChampMap<>(newRoot);
	}

	/**
	 * Returns a copy of this map without `key`. If the key is absent, this map is returned
	 * unchanged.
	 *
	 * @param key the key to drop (non-null)
	 * @return a new map without the key, or this map if the key was absent
	 */
	@Nonnull
	public ChampMap<K, V> removed(@Nonnull K key) {
		Objects.requireNonNull(key, "key must not be null");
		final int originalHash = key.hashCode();
		final BitmapIndexedMapNode<K, V> newRoot =
			this.rootNode.removed(key, originalHash, improve(originalHash), 0);
		return newRoot == this.rootNode ? this : new ChampMap<>(newRoot);
	}

	/**
	 * Returns the union of this map with `that` map. Where a key is present in both maps, the
	 * passed `resolver` decides the surviving entry; where a key is present in only one map, that
	 * entry is carried over untouched. Sub-trees that exist in only one of the two maps are spliced
	 * in by reference, so the cost approaches `O(overlap)` rather than `O(size(that))`.
	 *
	 * This is the natural primitive for folding a batch of changes (a working set) onto a shared
	 * map in a single structural pass.
	 *
	 * @param that     the right-hand map to merge into this one
	 * @param resolver invoked as `resolver(left, right)` for keys present in both maps; must return
	 *                 the entry to keep (its key is expected to equal the conflicting key)
	 * @return a new map holding the union of both maps, conflicts decided by `resolver`
	 */
	@Nonnull
	public ChampMap<K, V> merged(@Nonnull ChampMap<K, V> that, @Nonnull MergeResolver<K, V> resolver) {
		Objects.requireNonNull(that, "that must not be null");
		Objects.requireNonNull(resolver, "resolver must not be null");
		if (this.isEmpty()) {
			return that;
		} else if (that.isEmpty()) {
			return this;
		}
		final Builder<K, V> builder = new Builder<>();
		this.rootNode.mergeInto(that.rootNode, builder, 0, resolver);
		return builder.build();
	}

	/* ===========================================================================================
	 * java.util.Map read surface.
	 * =========================================================================================== */

	@Override
	public int size() {
		return this.rootNode.size();
	}

	@Override
	public boolean isEmpty() {
		return this.rootNode.size() == 0;
	}

	@Override
	public boolean containsKey(@Nullable Object key) {
		if (key == null || this.rootNode.size() == 0) {
			return false;
		}
		final int originalHash = key.hashCode();
		return this.rootNode.containsKey(key, originalHash, improve(originalHash), 0);
	}

	@Override
	public boolean containsValue(@Nullable Object value) {
		if (value == null) {
			return false;
		}
		final Iterator<V> it = new ChampValueIterator<>(this.rootNode);
		while (it.hasNext()) {
			if (value.equals(it.next())) {
				return true;
			}
		}
		return false;
	}

	@Nullable
	@Override
	public V get(@Nullable Object key) {
		if (key == null || this.rootNode.size() == 0) {
			return null;
		}
		final int originalHash = key.hashCode();
		return this.rootNode.get(key, originalHash, improve(originalHash), 0);
	}

	@Nonnull
	@Override
	public Set<K> keySet() {
		return new KeySetView();
	}

	@Nonnull
	@Override
	public Collection<V> values() {
		return new ValuesView();
	}

	@Nonnull
	@Override
	public Set<Entry<K, V>> entrySet() {
		return new EntrySetView();
	}

	/**
	 * Honours the {@link Map#equals(Object)} contract. When `o` is another {@link ChampMap} the
	 * comparison is a sub-linear structural trie comparison (both are in canonical form); otherwise
	 * it falls back to the entry-by-entry comparison required for arbitrary maps.
	 */
	@Override
	public boolean equals(@Nullable Object o) {
		if (o == this) {
			return true;
		}
		if (o instanceof ChampMap<?, ?> other) {
			// both maps are canonical CHAMP tries: equal content implies byte-identical structure,
			// so the structural node comparison is correct and sub-linear
			return this.size() == other.size() && this.rootNode.equals(other.rootNode);
		}
		if (!(o instanceof Map<?, ?> other)) {
			return false;
		}
		if (other.size() != this.size()) {
			return false;
		}
		final Iterator<Entry<K, V>> it = new ChampEntryIterator<>(this.rootNode);
		while (it.hasNext()) {
			final Entry<K, V> entry = it.next();
			final Object otherValue = other.get(entry.getKey());
			if (!entry.getValue().equals(otherValue)) {
				return false;
			}
		}
		return true;
	}

	@Override
	public int hashCode() {
		// java.util.Map contract: sum of entry hash codes (key.hashCode ^ value.hashCode)
		int hash = 0;
		final Iterator<Entry<K, V>> it = new ChampEntryIterator<>(this.rootNode);
		while (it.hasNext()) {
			hash += it.next().hashCode();
		}
		return hash;
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder(16 + (this.size() << 4));
		sb.append('{');
		final Iterator<Entry<K, V>> it = new ChampEntryIterator<>(this.rootNode);
		boolean first = true;
		while (it.hasNext()) {
			final Entry<K, V> entry = it.next();
			if (!first) {
				sb.append(", ");
			}
			sb.append(entry.getKey()).append('=').append(entry.getValue());
			first = false;
		}
		return sb.append('}').toString();
	}

	/* ===========================================================================================
	 * java.util.Map mutators — unsupported (the map is immutable; use updated/removed/merged).
	 * =========================================================================================== */

	/**
	 * Always throws: the map is immutable. Use {@link #updated(Object, Object)} to obtain a copy
	 * with the binding applied.
	 *
	 * @throws UnsupportedOperationException always — the map is immutable
	 */
	@Override
	public V put(K key, V value) {
		throw new UnsupportedOperationException("ChampMap is immutable; use updated(key, value).");
	}

	/**
	 * Always throws: the map is immutable. Use {@link #removed(Object)} to obtain a copy without the
	 * key.
	 *
	 * @throws UnsupportedOperationException always — the map is immutable
	 */
	@Override
	public V remove(Object key) {
		throw new UnsupportedOperationException("ChampMap is immutable; use removed(key).");
	}

	/**
	 * Always throws: the map is immutable. Use a {@link Builder} or
	 * {@link #merged(ChampMap, MergeResolver)} to fold in a batch of entries.
	 *
	 * @throws UnsupportedOperationException always — the map is immutable
	 */
	@Override
	public void putAll(@Nonnull Map<? extends K, ? extends V> m) {
		throw new UnsupportedOperationException("ChampMap is immutable; use a Builder or merged(...).");
	}

	/**
	 * Always throws: the map is immutable. Use {@link #empty()} for an empty map.
	 *
	 * @throws UnsupportedOperationException always — the map is immutable
	 */
	@Override
	public void clear() {
		throw new UnsupportedOperationException("ChampMap is immutable; use ChampMap.empty().");
	}

	/**
	 * Always throws: the map is immutable. The {@link Map} default would silently no-op when the key
	 * is already present, so it is overridden to honour the contract unconditionally.
	 *
	 * @throws UnsupportedOperationException always — the map is immutable
	 */
	@Nullable
	@Override
	public V putIfAbsent(K key, V value) {
		throw new UnsupportedOperationException(
			"ChampMap is immutable; use updated/removed/merged or a Builder.");
	}

	/**
	 * Always throws: the map is immutable. The {@link Map} default would silently no-op when the key
	 * is absent, so it is overridden to honour the contract unconditionally.
	 *
	 * @throws UnsupportedOperationException always — the map is immutable
	 */
	@Nullable
	@Override
	public V replace(K key, V value) {
		throw new UnsupportedOperationException(
			"ChampMap is immutable; use updated/removed/merged or a Builder.");
	}

	/**
	 * Always throws: the map is immutable. The {@link Map} default would silently no-op when the
	 * current value does not match, so it is overridden to honour the contract unconditionally.
	 *
	 * @throws UnsupportedOperationException always — the map is immutable
	 */
	@Override
	public boolean replace(K key, V oldValue, V newValue) {
		throw new UnsupportedOperationException(
			"ChampMap is immutable; use updated/removed/merged or a Builder.");
	}

	/**
	 * Always throws: the map is immutable. The {@link Map} default would silently no-op when the
	 * value does not match, so it is overridden to honour the contract unconditionally.
	 *
	 * @throws UnsupportedOperationException always — the map is immutable
	 */
	@Override
	public boolean remove(@Nullable Object key, @Nullable Object value) {
		throw new UnsupportedOperationException(
			"ChampMap is immutable; use updated/removed/merged or a Builder.");
	}

	/**
	 * Always throws: the map is immutable. The {@link Map} default would silently no-op when the
	 * remapping yields no change, so it is overridden to honour the contract unconditionally.
	 *
	 * @throws UnsupportedOperationException always — the map is immutable
	 */
	@Nullable
	@Override
	public V compute(K key, @Nonnull BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
		throw new UnsupportedOperationException(
			"ChampMap is immutable; use updated/removed/merged or a Builder.");
	}

	/**
	 * Always throws: the map is immutable. The {@link Map} default would silently no-op when the key
	 * is already present, so it is overridden to honour the contract unconditionally.
	 *
	 * @throws UnsupportedOperationException always — the map is immutable
	 */
	@Nullable
	@Override
	public V computeIfAbsent(K key, @Nonnull Function<? super K, ? extends V> mappingFunction) {
		throw new UnsupportedOperationException(
			"ChampMap is immutable; use updated/removed/merged or a Builder.");
	}

	/**
	 * Always throws: the map is immutable. The {@link Map} default would silently no-op when the key
	 * is absent, so it is overridden to honour the contract unconditionally.
	 *
	 * @throws UnsupportedOperationException always — the map is immutable
	 */
	@Nullable
	@Override
	public V computeIfPresent(
		K key, @Nonnull BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
		throw new UnsupportedOperationException(
			"ChampMap is immutable; use updated/removed/merged or a Builder.");
	}

	/**
	 * Always throws: the map is immutable. The {@link Map} default would silently no-op for some
	 * remapping outcomes, so it is overridden to honour the contract unconditionally.
	 *
	 * @throws UnsupportedOperationException always — the map is immutable
	 */
	@Nullable
	@Override
	public V merge(
		K key, @Nonnull V value,
		@Nonnull BiFunction<? super V, ? super V, ? extends V> remappingFunction
	) {
		throw new UnsupportedOperationException(
			"ChampMap is immutable; use updated/removed/merged or a Builder.");
	}

	/**
	 * Always throws: the map is immutable. Overridden so the contract holds even for an empty map
	 * where the {@link Map} default would never invoke the function.
	 *
	 * @throws UnsupportedOperationException always — the map is immutable
	 */
	@Override
	public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
		throw new UnsupportedOperationException(
			"ChampMap is immutable; use updated/removed/merged or a Builder.");
	}

	/* ===========================================================================================
	 * Static bit-arithmetic helpers (shared by all nodes).
	 * =========================================================================================== */

	/**
	 * Spreads the bits of a raw hash code so that the 5-bit chunks consumed at each trie level are
	 * well distributed even for hash codes with poor low-bit entropy. Bijective-ish avalanche; the
	 * exact function must stay stable because it determines node layout (and thus canonical form).
	 */
	private static int improve(int hashCode) {
		int h = hashCode + ~(hashCode << 9);
		h = h ^ (h >>> 14);
		h = h + (h << 4);
		return h ^ (h >>> 10);
	}

	/** Extracts the 5-bit chunk of `hash` relevant at the given `shift`. */
	private static int maskFrom(int hash, int shift) {
		return (hash >>> shift) & BIT_PARTITION_MASK;
	}

	/** Turns a 0..31 mask into its single-bit position within a 32-bit bitmap. */
	private static int bitposFrom(int mask) {
		return 1 << mask;
	}

	/** Counts how many lower bits of `bitmap` are set below `bitpos` — the dense array index. */
	private static int indexFrom(int bitmap, int bitpos) {
		return Integer.bitCount(bitmap & (bitpos - 1));
	}

	/** Index variant that short-circuits a fully populated bitmap. */
	private static int indexFrom(int bitmap, int mask, int bitpos) {
		return bitmap == -1 ? mask : indexFrom(bitmap, bitpos);
	}

	/** Returns a copy of `array` with `value` inserted at `index`. */
	private static int[] insertInt(@Nonnull int[] array, int index, int value) {
		final int[] result = new int[array.length + 1];
		System.arraycopy(array, 0, result, 0, index);
		result[index] = value;
		System.arraycopy(array, index, result, index + 1, array.length - index);
		return result;
	}

	/** Returns a copy of `array` with the element at `index` removed. */
	private static int[] removeInt(@Nonnull int[] array, int index) {
		final int[] result = new int[array.length - 1];
		System.arraycopy(array, 0, result, 0, index);
		System.arraycopy(array, index + 1, result, index, array.length - index - 1);
		return result;
	}

	/* ===========================================================================================
	 * Merge resolver functional interface.
	 * =========================================================================================== */

	/**
	 * Conflict resolver used by {@link #merged(ChampMap, MergeResolver)}. Invoked only for keys that
	 * are present in both maps; must return the entry that should survive in the result.
	 *
	 * @param <K> key type
	 * @param <V> value type
	 */
	@FunctionalInterface
	public interface MergeResolver<K, V> {

		/**
		 * Resolves a key collision between the two maps being merged. The returned entry's key is
		 * expected to equal the colliding key shared by `left` and `right`; only its value is meant
		 * to vary.
		 *
		 * @param left  the entry from the left (receiver) map
		 * @param right the entry from the right (argument) map
		 * @return the entry to keep in the merged result
		 */
		@Nonnull
		Entry<K, V> resolve(@Nonnull Entry<K, V> left, @Nonnull Entry<K, V> right);
	}

	/* ===========================================================================================
	 * Abstract trie node.
	 * =========================================================================================== */

	/**
	 * Common abstract base of the two concrete node kinds: {@link BitmapIndexedMapNode} (the usual
	 * inner/leaf node) and {@link HashCollisionMapNode} (a bucket of keys that share a full 32-bit
	 * improved hash).
	 */
	private abstract static class MapNode<K, V> {

		/** Returns the value bound to `key`, or {@code null} if absent. */
		@Nullable
		abstract V get(@Nonnull Object key, int originalHash, int keyHash, int shift);

		/** Returns whether `key` is bound in this sub-trie. */
		abstract boolean containsKey(@Nonnull Object key, int originalHash, int keyHash, int shift);

		/** Returns a node with `key` bound to `value` (see {@link MapNode} contract for `replaceValue`). */
		@Nonnull
		abstract MapNode<K, V> updated(
			@Nonnull K key, @Nonnull V value, int originalHash, int keyHash, int shift, boolean replaceValue);

		/** Returns a node with `key` removed, re-canonicalized. */
		@Nonnull
		abstract MapNode<K, V> removed(@Nonnull Object key, int originalHash, int keyHash, int shift);

		/** Total number of key/value pairs in this sub-trie. */
		abstract int size();

		abstract boolean hasNodes();

		abstract int nodeArity();

		@Nonnull
		abstract MapNode<K, V> getNode(int index);

		abstract boolean hasPayload();

		abstract int payloadArity();

		@Nonnull
		abstract K getKey(int index);

		@Nonnull
		abstract V getValue(int index);

		/** The raw (un-improved) hash code of the key at payload `index`. */
		abstract int getHash(int index);

		/** Sum over keys of the improved hash — maintained incrementally; accelerates equals. */
		abstract int cachedJavaKeySetHashCode();

		/** Emits every entry of this sub-trie into the passed builder (used by {@link #merged}). */
		abstract void buildTo(@Nonnull Builder<K, V> builder);

		/** Lock-step merge of this node with `that` into `builder` (see {@link #merged}). */
		abstract void mergeInto(
			@Nonnull MapNode<K, V> that, @Nonnull Builder<K, V> builder, int shift,
			@Nonnull MergeResolver<K, V> resolver);

		/** Deep copy used by the {@link Builder} to gain exclusive ownership before mutating. */
		@Nonnull
		abstract MapNode<K, V> copy();
	}

	/* ===========================================================================================
	 * Bitmap-indexed node — the workhorse.
	 * =========================================================================================== */

	/**
	 * A trie node carrying both inlined key/value payload (selected by {@link #dataMap}) and child
	 * sub-nodes (selected by {@link #nodeMap}). The backing {@link #content} array stores payload at
	 * the front as `[k0, v0, k1, v1, …]` and child nodes at the back in reverse order. The fields are
	 * mutable only so the {@link Builder} can assemble a node in place; once a node becomes part of a
	 * published map it is never mutated again.
	 */
	private static final class BitmapIndexedMapNode<K, V> extends MapNode<K, V> {

		private int dataMap;
		private int nodeMap;
		@Nonnull private Object[] content;
		@Nonnull private int[] originalHashes;
		private int size;
		private int cachedJavaKeySetHashCode;

		BitmapIndexedMapNode(
			int dataMap, int nodeMap, @Nonnull Object[] content, @Nonnull int[] originalHashes,
			int size, int cachedJavaKeySetHashCode) {
			this.dataMap = dataMap;
			this.nodeMap = nodeMap;
			this.content = content;
			this.originalHashes = originalHashes;
			this.size = size;
			this.cachedJavaKeySetHashCode = cachedJavaKeySetHashCode;
		}

		@SuppressWarnings("unchecked")
		@Nonnull
		@Override
		K getKey(int index) {
			return (K) this.content[TUPLE_LENGTH * index];
		}

		@SuppressWarnings("unchecked")
		@Nonnull
		@Override
		V getValue(int index) {
			return (V) this.content[TUPLE_LENGTH * index + 1];
		}

		@Override
		int getHash(int index) {
			return this.originalHashes[index];
		}

		@SuppressWarnings("unchecked")
		@Nonnull
		@Override
		MapNode<K, V> getNode(int index) {
			return (MapNode<K, V>) this.content[this.content.length - 1 - index];
		}

		@Override
		int size() {
			return this.size;
		}

		@Override
		int cachedJavaKeySetHashCode() {
			return this.cachedJavaKeySetHashCode;
		}

		@Override
		boolean hasNodes() {
			return this.nodeMap != 0;
		}

		@Override
		int nodeArity() {
			return Integer.bitCount(this.nodeMap);
		}

		@Override
		boolean hasPayload() {
			return this.dataMap != 0;
		}

		@Override
		int payloadArity() {
			return Integer.bitCount(this.dataMap);
		}

		/** Dense payload index of the data slot at `bitpos`. */
		private int dataIndex(int bitpos) {
			return Integer.bitCount(this.dataMap & (bitpos - 1));
		}

		/** Dense node index of the node slot at `bitpos`. */
		private int nodeIndex(int bitpos) {
			return Integer.bitCount(this.nodeMap & (bitpos - 1));
		}

		@Nullable
		@Override
		V get(@Nonnull Object key, int originalHash, int keyHash, int shift) {
			final int mask = maskFrom(keyHash, shift);
			final int bitpos = bitposFrom(mask);

			if ((this.dataMap & bitpos) != 0) {
				final int index = indexFrom(this.dataMap, mask, bitpos);
				return key.equals(getKey(index)) ? getValue(index) : null;
			} else if ((this.nodeMap & bitpos) != 0) {
				final int index = indexFrom(this.nodeMap, mask, bitpos);
				return getNode(index).get(key, originalHash, keyHash, shift + BIT_PARTITION_SIZE);
			} else {
				return null;
			}
		}

		@Override
		boolean containsKey(@Nonnull Object key, int originalHash, int keyHash, int shift) {
			final int mask = maskFrom(keyHash, shift);
			final int bitpos = bitposFrom(mask);

			if ((this.dataMap & bitpos) != 0) {
				final int index = indexFrom(this.dataMap, mask, bitpos);
				return this.originalHashes[index] == originalHash && key.equals(getKey(index));
			} else if ((this.nodeMap & bitpos) != 0) {
				final int index = indexFrom(this.nodeMap, mask, bitpos);
				return getNode(index).containsKey(key, originalHash, keyHash, shift + BIT_PARTITION_SIZE);
			} else {
				return false;
			}
		}

		@Nonnull
		@Override
		BitmapIndexedMapNode<K, V> updated(
			@Nonnull K key, @Nonnull V value, int originalHash, int keyHash, int shift, boolean replaceValue) {
			final int mask = maskFrom(keyHash, shift);
			final int bitpos = bitposFrom(mask);

			if ((this.dataMap & bitpos) != 0) {
				final int index = indexFrom(this.dataMap, mask, bitpos);
				final K key0 = getKey(index);
				final int key0UnimprovedHash = getHash(index);
				if (key0UnimprovedHash == originalHash && key0.equals(key)) {
					if (replaceValue) {
						final V value0 = getValue(index);
						if (key0 == key && value0 == value) {
							return this;
						} else {
							return copyAndSetValue(bitpos, value);
						}
					} else {
						return this;
					}
				} else {
					final V value0 = getValue(index);
					final int key0Hash = improve(key0UnimprovedHash);
					final MapNode<K, V> subNodeNew = mergeTwoKeyValPairs(
						key0, value0, key0UnimprovedHash, key0Hash,
						key, value, originalHash, keyHash, shift + BIT_PARTITION_SIZE);
					return copyAndMigrateFromInlineToNode(bitpos, key0Hash, subNodeNew);
				}
			} else if ((this.nodeMap & bitpos) != 0) {
				final int index = indexFrom(this.nodeMap, mask, bitpos);
				final MapNode<K, V> subNode = getNode(index);
				final MapNode<K, V> subNodeNew =
					subNode.updated(key, value, originalHash, keyHash, shift + BIT_PARTITION_SIZE, replaceValue);
				return subNodeNew == subNode ? this : copyAndSetNode(bitpos, subNode, subNodeNew);
			} else {
				return copyAndInsertValue(bitpos, key, originalHash, keyHash, value);
			}
		}

		@Nonnull
		@Override
		BitmapIndexedMapNode<K, V> removed(@Nonnull Object key, int originalHash, int keyHash, int shift) {
			final int mask = maskFrom(keyHash, shift);
			final int bitpos = bitposFrom(mask);

			if ((this.dataMap & bitpos) != 0) {
				final int index = indexFrom(this.dataMap, mask, bitpos);
				final K key0 = getKey(index);
				if (key0.equals(key)) {
					if (payloadArity() == 2 && nodeArity() == 0) {
						// drop down to the single remaining pair; it will either become the new root or
						// be inlined into the parent while unwinding the recursion (canonicalization)
						final int newDataMap = shift == 0 ? (this.dataMap ^ bitpos) : bitposFrom(maskFrom(keyHash, 0));
						final int keep = index == 0 ? 1 : 0;
						return new BitmapIndexedMapNode<>(
							newDataMap, 0,
							new Object[]{getKey(keep), getValue(keep)},
							new int[]{this.originalHashes[keep]},
							1, improve(getHash(keep)));
					} else {
						return copyAndRemoveValue(bitpos, keyHash);
					}
				} else {
					return this;
				}
			} else if ((this.nodeMap & bitpos) != 0) {
				final int index = indexFrom(this.nodeMap, mask, bitpos);
				final MapNode<K, V> subNode = getNode(index);
				final MapNode<K, V> subNodeNew =
					subNode.removed(key, originalHash, keyHash, shift + BIT_PARTITION_SIZE);

				if (subNodeNew == subNode) {
					return this;
				}

				final int subNodeNewSize = subNodeNew.size();
				if (subNodeNewSize == 1) {
					if (this.size == subNode.size()) {
						// the sub-node was the only child of this node — escalate it as the result
						return (BitmapIndexedMapNode<K, V>) subNodeNew;
					} else {
						// inline the single survivor back into this node (move it to the front)
						return copyAndMigrateFromNodeToInline(bitpos, subNode, subNodeNew);
					}
				} else {
					// sub-node still has multiple entries — just replace the child reference
					return copyAndSetNode(bitpos, subNode, subNodeNew);
				}
			} else {
				return this;
			}
		}

		/**
		 * Combines two distinct key/value pairs that collided at `shift - BIT_PARTITION_SIZE` into a
		 * fresh sub-trie: a two-entry node if their chunks differ at this level, a deeper sub-node if
		 * they still agree, or a {@link HashCollisionMapNode} once the hash is exhausted.
		 */
		@Nonnull
		private MapNode<K, V> mergeTwoKeyValPairs(
			@Nonnull K key0, @Nonnull V value0, int originalHash0, int keyHash0,
			@Nonnull K key1, @Nonnull V value1, int originalHash1, int keyHash1, int shift) {
			if (shift >= HASH_CODE_LENGTH) {
				return new HashCollisionMapNode<>(
					originalHash0, keyHash0, new Object[]{key0, value0, key1, value1});
			}
			final int mask0 = maskFrom(keyHash0, shift);
			final int mask1 = maskFrom(keyHash1, shift);
			final int newCachedHash = keyHash0 + keyHash1;

			if (mask0 != mask1) {
				// distinct chunks — both pairs fit inline at this level
				final int dataMap = bitposFrom(mask0) | bitposFrom(mask1);
				if (mask0 < mask1) {
					return new BitmapIndexedMapNode<>(
						dataMap, 0, new Object[]{key0, value0, key1, value1},
						new int[]{originalHash0, originalHash1}, 2, newCachedHash);
				} else {
					return new BitmapIndexedMapNode<>(
						dataMap, 0, new Object[]{key1, value1, key0, value0},
						new int[]{originalHash1, originalHash0}, 2, newCachedHash);
				}
			} else {
				// identical chunk — recurse one level deeper
				final int nodeMap = bitposFrom(mask0);
				final MapNode<K, V> node = mergeTwoKeyValPairs(
					key0, value0, originalHash0, keyHash0,
					key1, value1, originalHash1, keyHash1, shift + BIT_PARTITION_SIZE);
				return new BitmapIndexedMapNode<>(
					0, nodeMap, new Object[]{node}, EMPTY_INT_ARRAY, node.size(),
					node.cachedJavaKeySetHashCode());
			}
		}

		@Nonnull
		private BitmapIndexedMapNode<K, V> copyAndSetValue(int bitpos, @Nonnull V newValue) {
			final int idx = TUPLE_LENGTH * dataIndex(bitpos);
			final Object[] dst = this.content.clone();
			dst[idx + 1] = newValue;
			return new BitmapIndexedMapNode<>(
				this.dataMap, this.nodeMap, dst, this.originalHashes, this.size, this.cachedJavaKeySetHashCode);
		}

		@Nonnull
		private BitmapIndexedMapNode<K, V> copyAndSetNode(
			int bitpos, @Nonnull MapNode<K, V> oldNode, @Nonnull MapNode<K, V> newNode) {
			final int idx = this.content.length - 1 - nodeIndex(bitpos);
			final Object[] dst = this.content.clone();
			dst[idx] = newNode;
			return new BitmapIndexedMapNode<>(
				this.dataMap, this.nodeMap, dst, this.originalHashes,
				this.size - oldNode.size() + newNode.size(),
				this.cachedJavaKeySetHashCode - oldNode.cachedJavaKeySetHashCode() + newNode.cachedJavaKeySetHashCode());
		}

		/**
		 * Builds the content array for inserting a brand-new key/value pair at `bitpos`: the two
		 * payload slots are spliced into the front payload block at the dense data index. Shared by
		 * the immutable {@link #copyAndInsertValue} and the in-place {@link #insertValueInPlace} so the
		 * splice arithmetic lives in a single place.
		 */
		@Nonnull
		private Object[] contentWithValueInserted(int bitpos, @Nonnull K key, @Nonnull V value) {
			final int idx = TUPLE_LENGTH * dataIndex(bitpos);
			final Object[] src = this.content;
			final Object[] dst = new Object[src.length + TUPLE_LENGTH];
			System.arraycopy(src, 0, dst, 0, idx);
			dst[idx] = key;
			dst[idx + 1] = value;
			System.arraycopy(src, idx, dst, idx + TUPLE_LENGTH, src.length - idx);
			return dst;
		}

		@Nonnull
		private BitmapIndexedMapNode<K, V> copyAndInsertValue(
			int bitpos, @Nonnull K key, int originalHash, int keyHash, @Nonnull V value) {
			final int dataIx = dataIndex(bitpos);
			final Object[] dst = contentWithValueInserted(bitpos, key, value);
			final int[] dstHashes = insertInt(this.originalHashes, dataIx, originalHash);
			return new BitmapIndexedMapNode<>(
				this.dataMap | bitpos, this.nodeMap, dst, dstHashes,
				this.size + 1, this.cachedJavaKeySetHashCode + keyHash);
		}

		@Nonnull
		private BitmapIndexedMapNode<K, V> copyAndRemoveValue(int bitpos, int keyHash) {
			final int dataIx = dataIndex(bitpos);
			final int idx = TUPLE_LENGTH * dataIx;
			final Object[] src = this.content;
			final Object[] dst = new Object[src.length - TUPLE_LENGTH];
			System.arraycopy(src, 0, dst, 0, idx);
			System.arraycopy(src, idx + TUPLE_LENGTH, dst, idx, src.length - idx - TUPLE_LENGTH);
			final int[] dstHashes = removeInt(this.originalHashes, dataIx);
			return new BitmapIndexedMapNode<>(
				this.dataMap ^ bitpos, this.nodeMap, dst, dstHashes,
				this.size - 1, this.cachedJavaKeySetHashCode - keyHash);
		}

		/**
		 * Builds the content array for the inline→node migration at `bitpos`: the two payload slots
		 * are dropped from the front payload block and `node` is spliced into the back (reverse-packed)
		 * node block. Shared verbatim by the immutable {@link #copyAndMigrateFromInlineToNode} and the
		 * in-place {@link #migrateFromInlineToNodeInPlace} so the delicate index arithmetic lives in a
		 * single place.
		 */
		@Nonnull
		private Object[] contentWithInlineMigratedToNode(int bitpos, @Nonnull MapNode<K, V> node) {
			final int idxOld = TUPLE_LENGTH * dataIndex(bitpos);
			final int idxNew = this.content.length - TUPLE_LENGTH - nodeIndex(bitpos);
			final Object[] src = this.content;
			final Object[] dst = new Object[src.length - TUPLE_LENGTH + 1];
			// remove the 2 payload slots at idxOld and insert the node at idxNew (idxOld <= idxNew)
			System.arraycopy(src, 0, dst, 0, idxOld);
			System.arraycopy(src, idxOld + TUPLE_LENGTH, dst, idxOld, idxNew - idxOld);
			dst[idxNew] = node;
			System.arraycopy(src, idxNew + TUPLE_LENGTH, dst, idxNew + 1, src.length - idxNew - TUPLE_LENGTH);
			return dst;
		}

		/**
		 * Returns a copy of this node in which the inlined payload at `bitpos` has been replaced by
		 * the passed sub-node (payload removed from the front, node inserted at the back).
		 */
		@Nonnull
		private BitmapIndexedMapNode<K, V> copyAndMigrateFromInlineToNode(
			int bitpos, int keyHash, @Nonnull MapNode<K, V> node) {
			final int dataIx = dataIndex(bitpos);
			final Object[] dst = contentWithInlineMigratedToNode(bitpos, node);
			final int[] dstHashes = removeInt(this.originalHashes, dataIx);
			return new BitmapIndexedMapNode<>(
				this.dataMap ^ bitpos, this.nodeMap | bitpos, dst, dstHashes,
				this.size - 1 + node.size(),
				this.cachedJavaKeySetHashCode - keyHash + node.cachedJavaKeySetHashCode());
		}

		/**
		 * Returns a copy of this node in which the sub-node at `bitpos` (now holding a single entry)
		 * has been replaced by that entry inlined at the front — the inverse migration used during
		 * canonicalization on delete.
		 */
		@Nonnull
		private BitmapIndexedMapNode<K, V> copyAndMigrateFromNodeToInline(
			int bitpos, @Nonnull MapNode<K, V> oldNode, @Nonnull MapNode<K, V> node) {
			final int idxOld = this.content.length - 1 - nodeIndex(bitpos);
			final int dataIxNew = dataIndex(bitpos);
			final int idxNew = TUPLE_LENGTH * dataIxNew;
			final K key = node.getKey(0);
			final V value = node.getValue(0);
			final Object[] src = this.content;
			final Object[] dst = new Object[src.length - 1 + TUPLE_LENGTH];
			// remove the 1 node slot at idxOld and insert the 2 payload slots at idxNew (idxOld >= idxNew)
			System.arraycopy(src, 0, dst, 0, idxNew);
			dst[idxNew] = key;
			dst[idxNew + 1] = value;
			System.arraycopy(src, idxNew, dst, idxNew + TUPLE_LENGTH, idxOld - idxNew);
			System.arraycopy(src, idxOld + 1, dst, idxOld + TUPLE_LENGTH, src.length - idxOld - 1);
			final int[] dstHashes = insertInt(this.originalHashes, dataIxNew, node.getHash(0));
			return new BitmapIndexedMapNode<>(
				this.dataMap | bitpos, this.nodeMap ^ bitpos, dst, dstHashes,
				this.size - oldNode.size() + 1,
				this.cachedJavaKeySetHashCode - oldNode.cachedJavaKeySetHashCode() + node.cachedJavaKeySetHashCode());
		}

		@Override
		void buildTo(@Nonnull Builder<K, V> builder) {
			final int iN = payloadArity();
			final int jN = nodeArity();
			for (int i = 0; i < iN; i++) {
				builder.addWithHashes(getKey(i), getValue(i), getHash(i), improve(getHash(i)));
			}
			for (int j = 0; j < jN; j++) {
				getNode(j).buildTo(builder);
			}
		}

		@Override
		void mergeInto(
			@Nonnull MapNode<K, V> that, @Nonnull Builder<K, V> builder, int shift,
			@Nonnull MergeResolver<K, V> resolver) {
			if (!(that instanceof BitmapIndexedMapNode<K, V> bm)) {
				throw new GenericEvitaInternalError("Cannot merge a BitmapIndexedMapNode with a HashCollisionMapNode.");
			}
			if (this.size == 0) {
				bm.buildTo(builder);
				return;
			} else if (bm.size == 0) {
				buildTo(builder);
				return;
			}

			final int allMap = this.dataMap | bm.dataMap | this.nodeMap | bm.nodeMap;
			final int minIndex = Integer.numberOfTrailingZeros(allMap);
			final int maxIndex = BRANCHING_FACTOR - Integer.numberOfLeadingZeros(allMap);

			int leftIdx = 0;
			int rightIdx = 0;
			for (int index = minIndex; index < maxIndex; index++) {
				final int bitpos = bitposFrom(index);

				if ((bitpos & this.dataMap) != 0) {
					final K leftKey = getKey(leftIdx);
					final V leftValue = getValue(leftIdx);
					final int leftOriginalHash = getHash(leftIdx);
					if ((bitpos & bm.dataMap) != 0) {
						// left data and right data
						final K rightKey = bm.getKey(rightIdx);
						final V rightValue = bm.getValue(rightIdx);
						final int rightOriginalHash = bm.getHash(rightIdx);
						if (leftOriginalHash == rightOriginalHash && leftKey.equals(rightKey)) {
							builder.addResolved(resolver.resolve(
								entry(leftKey, leftValue), entry(rightKey, rightValue)));
						} else {
							builder.addWithHash(leftKey, leftValue, leftOriginalHash);
							builder.addWithHash(rightKey, rightValue, rightOriginalHash);
						}
						rightIdx++;
					} else if ((bitpos & bm.nodeMap) != 0) {
						// left data and right node
						final MapNode<K, V> subNode = bm.getNode(bm.nodeIndex(bitpos));
						final int leftImprovedHash = improve(leftOriginalHash);
						final MapNode<K, V> removed = subNode.removed(
							leftKey, leftOriginalHash, leftImprovedHash, shift + BIT_PARTITION_SIZE);
						if (removed == subNode) {
							// no overlap — emit the whole right sub-node, then the left pair
							subNode.buildTo(builder);
							builder.addWithHashes(leftKey, leftValue, leftOriginalHash, leftImprovedHash);
						} else {
							// the left key collided with one inside the right sub-node — resolve it
							final V rightValue = subNode.get(
								leftKey, leftOriginalHash, leftImprovedHash, shift + BIT_PARTITION_SIZE);
							removed.buildTo(builder);
							builder.addResolved(resolver.resolve(
								entry(leftKey, leftValue), entry(leftKey, rightValue)));
						}
					} else {
						// left data, nothing on right
						builder.addWithHash(leftKey, leftValue, leftOriginalHash);
					}
					leftIdx++;
				} else if ((bitpos & this.nodeMap) != 0) {
					if ((bitpos & bm.dataMap) != 0) {
						// left node and right data
						final K rightKey = bm.getKey(rightIdx);
						final V rightValue = bm.getValue(rightIdx);
						final int rightOriginalHash = bm.getHash(rightIdx);
						final int rightImprovedHash = improve(rightOriginalHash);
						final MapNode<K, V> subNode = getNode(nodeIndex(bitpos));
						final MapNode<K, V> removed = subNode.removed(
							rightKey, rightOriginalHash, rightImprovedHash, shift + BIT_PARTITION_SIZE);
						if (removed == subNode) {
							subNode.buildTo(builder);
							builder.addWithHashes(rightKey, rightValue, rightOriginalHash, rightImprovedHash);
						} else {
							final V leftValue = subNode.get(
								rightKey, rightOriginalHash, rightImprovedHash, shift + BIT_PARTITION_SIZE);
							removed.buildTo(builder);
							builder.addResolved(resolver.resolve(
								entry(rightKey, leftValue), entry(rightKey, rightValue)));
						}
						rightIdx++;
					} else if ((bitpos & bm.nodeMap) != 0) {
						// left node and right node — recurse in lock-step
						getNode(nodeIndex(bitpos)).mergeInto(
							bm.getNode(bm.nodeIndex(bitpos)), builder, shift + BIT_PARTITION_SIZE, resolver);
					} else {
						// left node, nothing on right
						getNode(nodeIndex(bitpos)).buildTo(builder);
					}
				} else if ((bitpos & bm.dataMap) != 0) {
					// nothing on left, right data
					final int dataIndex = bm.dataIndex(bitpos);
					builder.addWithHash(bm.getKey(dataIndex), bm.getValue(dataIndex), bm.getHash(dataIndex));
					rightIdx++;
				} else if ((bitpos & bm.nodeMap) != 0) {
					// nothing on left, right node
					bm.getNode(bm.nodeIndex(bitpos)).buildTo(builder);
				}
			}
		}

		@Nonnull
		@Override
		MapNode<K, V> copy() {
			final Object[] contentClone = this.content.clone();
			final int contentLength = contentClone.length;
			// payload slots are shallow-shared; child node slots must be deep-copied so the builder
			// owns every node it might mutate in place
			for (int i = Integer.bitCount(this.dataMap) * TUPLE_LENGTH; i < contentLength; i++) {
				//noinspection unchecked
				contentClone[i] = ((MapNode<K, V>) contentClone[i]).copy();
			}
			return new BitmapIndexedMapNode<>(
				this.dataMap, this.nodeMap, contentClone, this.originalHashes.clone(),
				this.size, this.cachedJavaKeySetHashCode);
		}

		// The compared fields are non-final only to let the transient Builder mutate builder-owned
		// nodes in place (the O(M) bulk-build optimisation, guarded by `ensureUnaliased`). Once a node
		// is reachable from a published ChampMap it is never mutated again — every persistent
		// `updated`/`removed`/`merged` path-copies into fresh nodes — so by the time `equals` is
		// invoked (only on published roots, via ChampMap.equals) the fields are effectively final.
		// This mirrors Scala's immutable.HashMap.BitmapIndexedMapNode, whose fields are likewise `var`.
		@SuppressWarnings("NonFinalFieldReferencedInEquals")
		@Override
		public boolean equals(@Nullable Object o) {
			if (o == this) {
				return true;
			}
			if (!(o instanceof BitmapIndexedMapNode<?, ?> node)) {
				return false;
			}
			// the preceding dataMap/nodeMap/size guards already pin content.length, so the element
			// loop in Arrays.equals (Objects.equals per slot — node slots recurse through
			// MapNode.equals) is the canonical-form structural comparison
			return this.cachedJavaKeySetHashCode == node.cachedJavaKeySetHashCode
				&& this.nodeMap == node.nodeMap
				&& this.dataMap == node.dataMap
				&& this.size == node.size
				&& Arrays.equals(this.originalHashes, node.originalHashes)
				&& Arrays.equals(this.content, node.content);
		}

		@Override
		public int hashCode() {
			throw new UnsupportedOperationException("Trie nodes do not support hashing.");
		}

		/* --- in-place mutators, used exclusively by the Builder on builder-owned nodes --- */

		/** Mutably inserts a brand-new key/value pair at `bitpos` (the slot must be empty). */
		void insertValueInPlace(int bitpos, @Nonnull K key, int originalHash, int keyHash, @Nonnull V value) {
			final int dataIx = dataIndex(bitpos);
			final Object[] dst = contentWithValueInserted(bitpos, key, value);
			this.originalHashes = insertInt(this.originalHashes, dataIx, originalHash);
			this.dataMap |= bitpos;
			this.content = dst;
			this.size += 1;
			this.cachedJavaKeySetHashCode += keyHash;
		}

		/** Mutably replaces the value of the inlined pair at the payload `index`. */
		void setValueInPlace(int index, @Nonnull V value) {
			this.content[TUPLE_LENGTH * index + 1] = value;
		}

		/** Mutably converts the inlined pair at `bitpos` into the passed sub-node. */
		void migrateFromInlineToNodeInPlace(int bitpos, int keyHash, @Nonnull MapNode<K, V> node) {
			final int dataIx = dataIndex(bitpos);
			final Object[] dst = contentWithInlineMigratedToNode(bitpos, node);
			this.originalHashes = removeInt(this.originalHashes, dataIx);
			this.dataMap ^= bitpos;
			this.nodeMap |= bitpos;
			this.content = dst;
			this.size = this.size - 1 + node.size();
			this.cachedJavaKeySetHashCode = this.cachedJavaKeySetHashCode - keyHash + node.cachedJavaKeySetHashCode();
		}
	}

	/* ===========================================================================================
	 * Hash-collision node — a bucket of keys sharing a full 32-bit improved hash.
	 * =========================================================================================== */

	/**
	 * A leaf node holding two or more keys whose improved hashes are identical across all 32 bits.
	 * Lookups are linear within the (tiny) bucket. The {@link #content} array stores pairs as
	 * `[k0, v0, k1, v1, …]`. Mutating builder operations always reallocate the array (they never
	 * write into it in place), so a {@link #copy()} can safely share the array.
	 */
	private static final class HashCollisionMapNode<K, V> extends MapNode<K, V> {

		/** Raw hash of the (first) colliding key — approximate for the bucket, but always rebuildable. */
		private final int originalHash;
		/** The shared improved hash of every key in this bucket. */
		private final int hash;
		@Nonnull private Object[] content;

		HashCollisionMapNode(int originalHash, int hash, @Nonnull Object[] content) {
			if (content.length < TUPLE_LENGTH << 1) {
				// defensive: a collision bucket must hold at least two pairs (four array slots)
				throw new GenericEvitaInternalError("Hash-collision node must hold at least two entries.");
			}
			this.originalHash = originalHash;
			this.hash = hash;
			this.content = content;
		}

		/** Returns the payload index of `key` within the bucket, or -1 if absent. */
		private int indexOf(@Nonnull Object key) {
			final int arity = this.content.length / TUPLE_LENGTH;
			for (int i = 0; i < arity; i++) {
				if (key.equals(this.content[TUPLE_LENGTH * i])) {
					return i;
				}
			}
			return -1;
		}

		@Override
		int size() {
			return this.content.length / TUPLE_LENGTH;
		}

		@Nullable
		@Override
		V get(@Nonnull Object key, int originalHash, int keyHash, int shift) {
			if (this.hash == keyHash) {
				final int index = indexOf(key);
				return index >= 0 ? getValue(index) : null;
			}
			return null;
		}

		@Override
		boolean containsKey(@Nonnull Object key, int originalHash, int keyHash, int shift) {
			return this.hash == keyHash && indexOf(key) >= 0;
		}

		@Nonnull
		@Override
		MapNode<K, V> updated(
			@Nonnull K key, @Nonnull V value, int originalHash, int keyHash, int shift, boolean replaceValue) {
			final int index = indexOf(key);
			if (index >= 0) {
				if (replaceValue) {
					if (getValue(index) == value) {
						return this;
					}
					final Object[] dst = this.content.clone();
					dst[TUPLE_LENGTH * index] = key;
					dst[TUPLE_LENGTH * index + 1] = value;
					return new HashCollisionMapNode<>(this.originalHash, this.hash, dst);
				} else {
					return this;
				}
			} else {
				final Object[] dst = Arrays.copyOf(this.content, this.content.length + TUPLE_LENGTH);
				dst[this.content.length] = key;
				dst[this.content.length + 1] = value;
				return new HashCollisionMapNode<>(this.originalHash, this.hash, dst);
			}
		}

		@Nonnull
		@Override
		MapNode<K, V> removed(@Nonnull Object key, int originalHash, int keyHash, int shift) {
			final int index = indexOf(key);
			if (index < 0) {
				return this;
			}
			if (size() == 2) {
				// removing one of two survivors leaves a single pair — canonicalize back to an
				// inlined bitmap node so the parent can absorb it
				final int keep = index == 0 ? 1 : 0;
				return new BitmapIndexedMapNode<>(
					bitposFrom(maskFrom(this.hash, 0)), 0,
					new Object[]{getKey(keep), getValue(keep)},
					new int[]{this.originalHash}, 1, this.hash);
			}
			final Object[] dst = new Object[this.content.length - TUPLE_LENGTH];
			final int idx = TUPLE_LENGTH * index;
			System.arraycopy(this.content, 0, dst, 0, idx);
			System.arraycopy(this.content, idx + TUPLE_LENGTH, dst, idx, this.content.length - idx - TUPLE_LENGTH);
			return new HashCollisionMapNode<>(this.originalHash, this.hash, dst);
		}

		@Override
		boolean hasNodes() {
			return false;
		}

		@Override
		int nodeArity() {
			return 0;
		}

		@Nonnull
		@Override
		MapNode<K, V> getNode(int index) {
			throw new IndexOutOfBoundsException("No sub-nodes present in a hash-collision leaf node.");
		}

		@Override
		boolean hasPayload() {
			return true;
		}

		@Override
		int payloadArity() {
			return size();
		}

		@SuppressWarnings("unchecked")
		@Nonnull
		@Override
		K getKey(int index) {
			return (K) this.content[TUPLE_LENGTH * index];
		}

		@SuppressWarnings("unchecked")
		@Nonnull
		@Override
		V getValue(int index) {
			return (V) this.content[TUPLE_LENGTH * index + 1];
		}

		@Override
		int getHash(int index) {
			return this.originalHash;
		}

		@Override
		int cachedJavaKeySetHashCode() {
			return size() * this.hash;
		}

		@Override
		void buildTo(@Nonnull Builder<K, V> builder) {
			final int arity = size();
			for (int i = 0; i < arity; i++) {
				builder.addWithHashes(getKey(i), getValue(i), this.originalHash, this.hash);
			}
		}

		@Override
		void mergeInto(
			@Nonnull MapNode<K, V> that, @Nonnull Builder<K, V> builder, int shift,
			@Nonnull MergeResolver<K, V> resolver) {
			if (!(that instanceof HashCollisionMapNode<K, V> hc)) {
				throw new GenericEvitaInternalError("Cannot merge a HashCollisionMapNode with a BitmapIndexedMapNode.");
			}
			final int rightArity = hc.size();
			final boolean[] rightConsumed = new boolean[rightArity];
			final int leftArity = size();
			for (int i = 0; i < leftArity; i++) {
				final K leftKey = getKey(i);
				final V leftValue = getValue(i);
				final int rightIndex = hc.indexOf(leftKey);
				if (rightIndex < 0) {
					builder.addWithHashes(leftKey, leftValue, this.originalHash, this.hash);
				} else {
					rightConsumed[rightIndex] = true;
					builder.addResolved(resolver.resolve(
						entry(leftKey, leftValue), entry(hc.getKey(rightIndex), hc.getValue(rightIndex))));
				}
			}
			for (int i = 0; i < rightArity; i++) {
				if (!rightConsumed[i]) {
					builder.addWithHashes(hc.getKey(i), hc.getValue(i), hc.originalHash, hc.hash);
				}
			}
		}

		@Nonnull
		@Override
		MapNode<K, V> copy() {
			// content is never mutated in place (builder ops reallocate), so sharing it is safe
			return new HashCollisionMapNode<>(this.originalHash, this.hash, this.content);
		}

		@Override
		public boolean equals(@Nullable Object o) {
			if (o == this) {
				return true;
			}
			if (!(o instanceof HashCollisionMapNode<?, ?> node)) {
				return false;
			}
			if (this.hash != node.hash || this.content.length != node.content.length) {
				return false;
			}
			// order within a bucket depends on insertion order, so compare as multisets
			final int arity = size();
			for (int i = 0; i < arity; i++) {
				final int otherIndex = node.indexOf(getKey(i));
				if (otherIndex < 0 || !getValue(i).equals(node.getValue(otherIndex))) {
					return false;
				}
			}
			return true;
		}

		@Override
		public int hashCode() {
			throw new UnsupportedOperationException("Trie nodes do not support hashing.");
		}

		/* --- in-place mutators, used exclusively by the Builder --- */

		/** Mutably appends a new pair (caller guarantees the key is absent). */
		void appendInPlace(@Nonnull K key, @Nonnull V value) {
			final Object[] dst = Arrays.copyOf(this.content, this.content.length + TUPLE_LENGTH);
			dst[this.content.length] = key;
			dst[this.content.length + 1] = value;
			this.content = dst;
		}

		/** Mutably replaces the value (and key) at the given payload `index`. */
		void setInPlace(int index, @Nonnull K key, @Nonnull V value) {
			final Object[] dst = this.content.clone();
			dst[TUPLE_LENGTH * index] = key;
			dst[TUPLE_LENGTH * index + 1] = value;
			this.content = dst;
		}
	}

	/* ===========================================================================================
	 * Builder (transient) — single-threaded, O(M) from-scratch assembly.
	 * =========================================================================================== */

	/**
	 * Mutable, single-threaded assembler of a {@link ChampMap}. Building a map of M entries with the
	 * builder costs `O(M)` rather than the `O(M·log₃₂ M)` of M independent persistent insertions,
	 * because intermediate nodes are mutated in place instead of path-copied.
	 *
	 * The builder follows the "transient" pattern: after {@link #build()} hands out a map, the next
	 * mutation makes a one-time defensive {@link MapNode#copy()} so the published map is never
	 * disturbed (the {@link #aliased} guard, equivalent to Clojure's edit-thread token). In the
	 * common build-once usage no copy ever happens.
	 *
	 * @param <K> key type
	 * @param <V> value type
	 */
	@NotThreadSafe
	public static final class Builder<K, V> {

		/** The last map handed out by {@link #build()}, or null — signals a needed copy-before-mutate. */
		@Nullable private ChampMap<K, V> aliased;
		/** Root of the partially-built trie; always a fresh, builder-owned node. */
		@Nonnull private BitmapIndexedMapNode<K, V> rootNode;

		Builder() {
			this.rootNode = newEmptyRootNode();
		}

		@Nonnull
		private static <K, V> BitmapIndexedMapNode<K, V> newEmptyRootNode() {
			// distinct mutable node instance (shared empty arrays are fine — they are reallocated on
			// the first structural change, never written in place)
			return new BitmapIndexedMapNode<>(0, 0, EMPTY_OBJECT_ARRAY, EMPTY_INT_ARRAY, 0, 0);
		}

		/**
		 * Adds or replaces a key/value pair. Returns this builder for chaining.
		 */
		@Nonnull
		public Builder<K, V> add(@Nonnull K key, @Nonnull V value) {
			Objects.requireNonNull(key, "key must not be null");
			Objects.requireNonNull(value, "value must not be null");
			ensureUnaliased();
			final int originalHash = key.hashCode();
			update(this.rootNode, key, value, originalHash, improve(originalHash), 0);
			return this;
		}

		/**
		 * Removes a key. Returns this builder for chaining.
		 */
		@Nonnull
		public Builder<K, V> remove(@Nonnull K key) {
			Objects.requireNonNull(key, "key must not be null");
			ensureUnaliased();
			final int originalHash = key.hashCode();
			// removal re-canonicalizes; the persistent removed() shares only with the owned tree
			this.rootNode = this.rootNode.removed(key, originalHash, improve(originalHash), 0);
			return this;
		}

		/**
		 * Returns the finished {@link ChampMap}. The builder may be reused afterwards; the next
		 * mutation transparently copies the shared structure.
		 */
		@Nonnull
		public ChampMap<K, V> build() {
			if (this.rootNode.size() == 0) {
				return empty();
			} else if (this.aliased != null) {
				return this.aliased;
			} else {
				this.aliased = new ChampMap<>(this.rootNode);
				return this.aliased;
			}
		}

		/** Adds with a precomputed raw hash (used by {@link MapNode#buildTo}/merge). */
		void addWithHash(@Nonnull K key, @Nonnull V value, int originalHash) {
			ensureUnaliased();
			update(this.rootNode, key, value, originalHash, improve(originalHash), 0);
		}

		/** Adds with precomputed raw and improved hashes. */
		void addWithHashes(@Nonnull K key, @Nonnull V value, int originalHash, int keyHash) {
			ensureUnaliased();
			update(this.rootNode, key, value, originalHash, keyHash, 0);
		}

		/** Adds a resolved entry whose hashes must be recomputed from its key. */
		void addResolved(@Nonnull Entry<K, V> entry) {
			addWithHash(entry.getKey(), entry.getValue(), entry.getKey().hashCode());
		}

		private void ensureUnaliased() {
			if (this.aliased != null) {
				this.rootNode = (BitmapIndexedMapNode<K, V>) this.rootNode.copy();
				this.aliased = null;
			}
		}

		/** Mutable upsert into a builder-owned node. */
		private void update(
			@Nonnull MapNode<K, V> mapNode, @Nonnull K key, @Nonnull V value,
			int originalHash, int keyHash, int shift) {
			if (mapNode instanceof BitmapIndexedMapNode<K, V> bm) {
				final int mask = maskFrom(keyHash, shift);
				final int bitpos = bitposFrom(mask);
				if ((bm.dataMap & bitpos) != 0) {
					final int index = indexFrom(bm.dataMap, mask, bitpos);
					final K key0 = bm.getKey(index);
					final int key0UnimprovedHash = bm.getHash(index);
					if (key0UnimprovedHash == originalHash && key0.equals(key)) {
						bm.setValueInPlace(index, value);
					} else {
						final V value0 = bm.getValue(index);
						final int key0Hash = improve(key0UnimprovedHash);
						final MapNode<K, V> subNodeNew = bm.mergeTwoKeyValPairs(
							key0, value0, key0UnimprovedHash, key0Hash,
							key, value, originalHash, keyHash, shift + BIT_PARTITION_SIZE);
						bm.migrateFromInlineToNodeInPlace(bitpos, key0Hash, subNodeNew);
					}
				} else if ((bm.nodeMap & bitpos) != 0) {
					final int index = indexFrom(bm.nodeMap, mask, bitpos);
					final MapNode<K, V> subNode = bm.getNode(index);
					final int beforeSize = subNode.size();
					final int beforeHash = subNode.cachedJavaKeySetHashCode();
					update(subNode, key, value, originalHash, keyHash, shift + BIT_PARTITION_SIZE);
					bm.size += subNode.size() - beforeSize;
					bm.cachedJavaKeySetHashCode += subNode.cachedJavaKeySetHashCode() - beforeHash;
				} else {
					bm.insertValueInPlace(bitpos, key, originalHash, keyHash, value);
				}
			} else {
				final HashCollisionMapNode<K, V> hc = (HashCollisionMapNode<K, V>) mapNode;
				final int index = hc.indexOf(key);
				if (index < 0) {
					hc.appendInPlace(key, value);
				} else {
					hc.setInPlace(index, key, value);
				}
			}
		}
	}

	/* ===========================================================================================
	 * Iterators — fixed-size cursor stack, no parent pointers.
	 * =========================================================================================== */

	/**
	 * Depth-first pre-order traversal over a CHAMP trie using a fixed-size explicit stack (bounded by
	 * {@link #MAX_DEPTH}). It first yields all payload of the current node, then descends into child
	 * nodes left to right. No parent pointers are stored, which keeps nodes pointer-lean.
	 */
	private abstract static class ChampBaseIterator<K, V> {

		protected int currentValueCursor;
		protected int currentValueLength;
		protected MapNode<K, V> currentValueNode;

		private int currentStackLevel = -1;
		private final int[] nodeCursorsAndLengths = new int[(MAX_DEPTH << 1)];
		@SuppressWarnings("unchecked")
		private final MapNode<K, V>[] nodes = new MapNode[MAX_DEPTH];

		ChampBaseIterator(@Nonnull MapNode<K, V> rootNode) {
			if (rootNode.hasNodes()) {
				pushNode(rootNode);
			}
			if (rootNode.hasPayload()) {
				setupPayloadNode(rootNode);
			}
		}

		private void setupPayloadNode(@Nonnull MapNode<K, V> node) {
			this.currentValueNode = node;
			this.currentValueCursor = 0;
			this.currentValueLength = node.payloadArity();
		}

		private void pushNode(@Nonnull MapNode<K, V> node) {
			this.currentStackLevel++;
			final int cursorIndex = this.currentStackLevel << 1;
			final int lengthIndex = (this.currentStackLevel << 1) + 1;
			this.nodes[this.currentStackLevel] = node;
			this.nodeCursorsAndLengths[cursorIndex] = 0;
			this.nodeCursorsAndLengths[lengthIndex] = node.nodeArity();
		}

		private void popNode() {
			this.currentStackLevel--;
		}

		/** Advances to the next node carrying payload, pushing encountered sub-nodes. */
		private boolean searchNextValueNode() {
			while (this.currentStackLevel >= 0) {
				final int cursorIndex = this.currentStackLevel << 1;
				final int lengthIndex = (this.currentStackLevel << 1) + 1;
				final int nodeCursor = this.nodeCursorsAndLengths[cursorIndex];
				final int nodeLength = this.nodeCursorsAndLengths[lengthIndex];
				if (nodeCursor < nodeLength) {
					this.nodeCursorsAndLengths[cursorIndex]++;
					final MapNode<K, V> nextNode = this.nodes[this.currentStackLevel].getNode(nodeCursor);
					if (nextNode.hasNodes()) {
						pushNode(nextNode);
					}
					if (nextNode.hasPayload()) {
						setupPayloadNode(nextNode);
						return true;
					}
				} else {
					popNode();
				}
			}
			return false;
		}

		public final boolean hasNext() {
			return this.currentValueCursor < this.currentValueLength || searchNextValueNode();
		}
	}

	private static final class ChampKeyIterator<K, V> extends ChampBaseIterator<K, V> implements Iterator<K> {
		ChampKeyIterator(@Nonnull MapNode<K, V> rootNode) {
			super(rootNode);
		}

		@Override
		public K next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}
			final K key = this.currentValueNode.getKey(this.currentValueCursor);
			this.currentValueCursor++;
			return key;
		}
	}

	private static final class ChampValueIterator<K, V> extends ChampBaseIterator<K, V> implements Iterator<V> {
		ChampValueIterator(@Nonnull MapNode<K, V> rootNode) {
			super(rootNode);
		}

		@Override
		public V next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}
			final V value = this.currentValueNode.getValue(this.currentValueCursor);
			this.currentValueCursor++;
			return value;
		}
	}

	private static final class ChampEntryIterator<K, V> extends ChampBaseIterator<K, V>
		implements Iterator<Entry<K, V>> {
		ChampEntryIterator(@Nonnull MapNode<K, V> rootNode) {
			super(rootNode);
		}

		@Override
		public Entry<K, V> next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}
			final Entry<K, V> entry = new SimpleImmutableEntry<>(
				this.currentValueNode.getKey(this.currentValueCursor),
				this.currentValueNode.getValue(this.currentValueCursor));
			this.currentValueCursor++;
			return entry;
		}
	}

	/* ===========================================================================================
	 * Read-only collection views.
	 * =========================================================================================== */

	private final class KeySetView extends AbstractSet<K> {
		@Nonnull
		@Override
		public Iterator<K> iterator() {
			return new ChampKeyIterator<>(ChampMap.this.rootNode);
		}

		@Override
		public int size() {
			return ChampMap.this.size();
		}

		@Override
		public boolean contains(@Nullable Object o) {
			return ChampMap.this.containsKey(o);
		}
	}

	private final class ValuesView extends AbstractCollection<V> {
		@Nonnull
		@Override
		public Iterator<V> iterator() {
			return new ChampValueIterator<>(ChampMap.this.rootNode);
		}

		@Override
		public int size() {
			return ChampMap.this.size();
		}

		@Override
		public boolean contains(@Nullable Object o) {
			return ChampMap.this.containsValue(o);
		}
	}

	private final class EntrySetView extends AbstractSet<Entry<K, V>> {
		@Nonnull
		@Override
		public Iterator<Entry<K, V>> iterator() {
			return new ChampEntryIterator<>(ChampMap.this.rootNode);
		}

		@Override
		public int size() {
			return ChampMap.this.size();
		}

		@Override
		public boolean contains(@Nullable Object o) {
			if (!(o instanceof Entry<?, ?> entry)) {
				return false;
			}
			final Object key = entry.getKey();
			if (key == null) {
				return false;
			}
			final V value = ChampMap.this.get(key);
			return value != null && value.equals(entry.getValue());
		}
	}

	/** Allocates an immutable {@link Entry} (used by the merge resolver call sites). */
	@Nonnull
	private static <K, V> Entry<K, V> entry(@Nonnull K key, @Nonnull V value) {
		return new SimpleImmutableEntry<>(key, value);
	}
}
