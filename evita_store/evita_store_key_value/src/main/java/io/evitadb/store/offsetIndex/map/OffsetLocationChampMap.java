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

package io.evitadb.store.offsetIndex.map;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.NumberUtils;
import io.evitadb.store.offsetIndex.model.RecordKey;
import io.evitadb.store.shared.model.FileLocation;

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
 * Immutable, persistent `(byte recordType, long primaryKey) → (long startingPosition, int
 * recordLength)` hash map based on the CHAMP (Compressed Hash-Array Mapped Prefix-tree) data
 * structure (Steindorfer & Vinju, OOPSLA'15). It is the memory-lean specialisation of the generic
 * {@link io.evitadb.dataType.champ.ChampMap} used as the resident location index of
 * {@link io.evitadb.store.offsetIndex.OffsetIndex}: every "mutation" returns a brand-new map that
 * structurally shares the bulk of its predecessor, so retaining one immutable root per catalog
 * version costs almost nothing and lock-free readers observe a consistent root through a single
 * volatile publication.
 *
 * Why a dedicated map instead of `ChampMap<RecordKey, FileLocation>`: the generic map stores each
 * entry as two heap objects (the `RecordKey` and the `FileLocation`) plus two reference slots in the
 * node payload array, and keeps an `int[] originalHashes` per node. This specialisation collapses
 * the leaf payload into **three** parallel primitive arrays and drops the original-hash array
 * entirely:
 *
 * - `long[] keysPk` — the full 64-bit composed primary key (the `RecordKey` cannot be folded into a
 *   single scalar because `primaryKey` already spans 64 bits; the byte `recordType` is packed
 *   elsewhere);
 * - `long[] valPos` — the `FileLocation.startingPosition`;
 * - `long[] typeLen` — `(recordType << 32) | (recordLength & 0xFFFFFFFF)`, packing the record type
 *   and the value length into one slot.
 *
 * `originalHashes` is gone: the lookup path compares the primitive `(recordType, primaryKey)`
 * directly, and the (rare) node split recomputes the avalanched key hash from those primitives in a
 * handful of ALU ops. The `RecordKey` / `FileLocation` objects materialise only at the
 * {@link Map} boundary (a {@link #get(Object)} reconstructs a `FileLocation`; iteration reconstructs
 * `Entry<RecordKey, FileLocation>`), so callers are unaffected. Measured on real data this trades a
 * naive flatten's byte-neutrality for ~19% less resident memory and ~33% fewer live objects.
 *
 * The trie owns its own hash function over `(recordType, primaryKey)` (see {@link #keyHash}) and
 * uses it consistently for insertion, lookup and node splitting; it never calls
 * {@link RecordKey#hashCode()} for trie placement, so the canonical form is independent of however
 * the `RecordKey` record's compiler-generated hash happens to combine its components.
 *
 * Like {@code ChampMap} this is a clean-room reimplementation studied from the original CHAMP paper
 * and the Scala standard-library `scala.collection.immutable.HashMap` (Apache-2.0); no code was
 * copied. The structure maintains a *canonical* form (after every removal a node that would hold a
 * single surviving entry is inlined back into its parent), which is what makes structural
 * {@link #equals(Object)} sub-linear.
 *
 * All published instances are deeply immutable: the `rootNode` reference is `final` and no node
 * reachable from a published map is mutated again, so an instance may be shared and read by any
 * number of threads. Safe publication is the caller's responsibility — store the reference in a
 * `final`, `volatile` or {@link AtomicReference} field (as the OffsetIndex root is). The constructor
 * issues a {@link VarHandle#releaseFence()} so in-place node writes performed while building are
 * flushed before the reference can escape. The {@link Builder} is mutable and single-thread-confined.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Immutable
@ThreadSafe
public final class OffsetLocationChampMap implements Map<RecordKey, FileLocation> {

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

	/** Sentinel returned by {@link #findRecordLength} when the key is absent (a real length is ≥ 0). */
	public static final int RECORD_LENGTH_ABSENT = -1;

	private static final long[] EMPTY_LONGS = new long[0];
	private static final LocationNode[] EMPTY_NODES = new LocationNode[0];

	/** Shared, immutable empty root node (never mutated). */
	private static final BitmapLocationNode EMPTY_NODE =
		new BitmapLocationNode(0, 0, EMPTY_LONGS, EMPTY_LONGS, EMPTY_LONGS, EMPTY_NODES, 0);

	/** Shared empty map instance. */
	private static final OffsetLocationChampMap EMPTY = new OffsetLocationChampMap(EMPTY_NODE);

	/** The root node of the trie; never null, possibly the shared empty node. */
	@Nonnull private final LocationNode rootNode;

	/* ===========================================================================================
	 * Construction and factories.
	 * =========================================================================================== */

	private OffsetLocationChampMap(@Nonnull LocationNode rootNode) {
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
	public static OffsetLocationChampMap empty() {
		return EMPTY;
	}

	/**
	 * Returns a singleton map holding the passed key/value pair.
	 *
	 * @param key   the sole key (non-null)
	 * @param value the value bound to `key` (non-null)
	 * @return a one-entry map
	 */
	@Nonnull
	public static OffsetLocationChampMap of(@Nonnull RecordKey key, @Nonnull FileLocation value) {
		return empty().updated(key, value);
	}

	/**
	 * Builds a map containing all entries of the passed source map. The source is iterated once
	 * through a {@link Builder}, so construction is `O(M)`.
	 *
	 * @param source the entries to copy; if it is already an {@link OffsetLocationChampMap} it is
	 *               returned as-is
	 * @return a map holding every entry of `source`
	 */
	@Nonnull
	public static OffsetLocationChampMap from(@Nonnull Map<? extends RecordKey, ? extends FileLocation> source) {
		if (source instanceof OffsetLocationChampMap already) {
			return already;
		}
		final Builder builder = new Builder();
		// forEach visits each (key, value) directly; the concrete source (a ConcurrentHashMap built by the offset-index
		// collector) does NOT allocate a Map.Entry per element the way entrySet().iterator() does, so this drops one
		// transient Entry object per record on the compaction rebuild path
		source.forEach(builder::add);
		return builder.build();
	}

	/**
	 * Creates a new, empty {@link Builder} (a transient) for assembling a map from scratch in `O(M)`
	 * time. The builder is single-threaded.
	 *
	 * @return a fresh, empty builder
	 */
	@Nonnull
	public static Builder builder() {
		return new Builder();
	}

	/* ===========================================================================================
	 * Persistent (copy-on-write) mutators returning a new instance.
	 * =========================================================================================== */

	/**
	 * Returns a copy of this map with `key` associated to `value`. If the key is already present with
	 * the very same `(startingPosition, recordLength)`, this map is returned unchanged.
	 *
	 * @param key   the key to bind (non-null)
	 * @param value the value to bind to `key` (non-null)
	 * @return a new map with the binding applied, or this map if nothing changed
	 */
	@Nonnull
	public OffsetLocationChampMap updated(@Nonnull RecordKey key, @Nonnull FileLocation value) {
		Objects.requireNonNull(key, "key must not be null");
		Objects.requireNonNull(value, "value must not be null");
		final byte type = key.recordType();
		final long pk = key.primaryKey();
		final LocationNode newRoot = this.rootNode.updated(
			pk, value.startingPosition(), pack(type, value.recordLength()),
			improve(keyHash(type, pk)), 0);
		return newRoot == this.rootNode ? this : new OffsetLocationChampMap(newRoot);
	}

	/**
	 * Returns a copy of this map without `key`. If the key is absent, this map is returned unchanged.
	 *
	 * @param key the key to drop (non-null)
	 * @return a new map without the key, or this map if the key was absent
	 */
	@Nonnull
	public OffsetLocationChampMap removed(@Nonnull RecordKey key) {
		Objects.requireNonNull(key, "key must not be null");
		if (this.rootNode.size() == 0) {
			return this;
		}
		final byte type = key.recordType();
		final long pk = key.primaryKey();
		final LocationNode newRoot = this.rootNode.removed(type, pk, improve(keyHash(type, pk)), 0);
		return newRoot == this.rootNode ? this : new OffsetLocationChampMap(newRoot);
	}

	/* ===========================================================================================
	 * Primitive fast-path read accessors (no FileLocation allocation).
	 * =========================================================================================== */

	/**
	 * Returns the `recordLength` bound to the passed key, or {@link #RECORD_LENGTH_ABSENT} when the
	 * key is not present. This avoids materialising a {@link FileLocation} on the hot flush-accounting
	 * path, where only the length is needed.
	 *
	 * @param key the key to resolve (non-null)
	 * @return the stored record length, or {@link #RECORD_LENGTH_ABSENT} if the key is absent
	 */
	public int findRecordLength(@Nonnull RecordKey key) {
		if (this.rootNode.size() == 0) {
			return RECORD_LENGTH_ABSENT;
		}
		final byte type = key.recordType();
		final long pk = key.primaryKey();
		return this.rootNode.findRecordLength(type, pk, improve(keyHash(type, pk)), 0);
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
		if (!(key instanceof RecordKey rk) || this.rootNode.size() == 0) {
			return false;
		}
		final byte type = rk.recordType();
		final long pk = rk.primaryKey();
		return this.rootNode.containsKey(type, pk, improve(keyHash(type, pk)), 0);
	}

	@Override
	public boolean containsValue(@Nullable Object value) {
		if (!(value instanceof FileLocation)) {
			return false;
		}
		final Iterator<FileLocation> it = new ValueIterator(this.rootNode);
		while (it.hasNext()) {
			if (value.equals(it.next())) {
				return true;
			}
		}
		return false;
	}

	@Nullable
	@Override
	public FileLocation get(@Nullable Object key) {
		if (!(key instanceof RecordKey rk) || this.rootNode.size() == 0) {
			return null;
		}
		final byte type = rk.recordType();
		final long pk = rk.primaryKey();
		return this.rootNode.get(type, pk, improve(keyHash(type, pk)), 0);
	}

	@Nonnull
	@Override
	public Set<RecordKey> keySet() {
		return new KeySetView();
	}

	@Nonnull
	@Override
	public Collection<FileLocation> values() {
		return new ValuesView();
	}

	@Nonnull
	@Override
	public Set<Entry<RecordKey, FileLocation>> entrySet() {
		return new EntrySetView();
	}

	/**
	 * Honours the {@link Map#equals(Object)} contract. When `o` is another
	 * {@link OffsetLocationChampMap} the comparison is a sub-linear structural trie comparison (both
	 * are in canonical form); otherwise it falls back to the entry-by-entry comparison required for
	 * arbitrary maps.
	 */
	@Override
	public boolean equals(@Nullable Object o) {
		if (o == this) {
			return true;
		}
		if (o instanceof OffsetLocationChampMap other) {
			// both maps are canonical CHAMP tries: equal content implies identical structure, so the
			// structural node comparison is correct and sub-linear
			return this.size() == other.size() && this.rootNode.equals(other.rootNode);
		}
		if (!(o instanceof Map<?, ?> other)) {
			return false;
		}
		if (other.size() != this.size()) {
			return false;
		}
		final Iterator<Entry<RecordKey, FileLocation>> it = new EntryIterator(this.rootNode);
		while (it.hasNext()) {
			final Entry<RecordKey, FileLocation> entry = it.next();
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
		final Iterator<Entry<RecordKey, FileLocation>> it = new EntryIterator(this.rootNode);
		while (it.hasNext()) {
			hash += it.next().hashCode();
		}
		return hash;
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder(16 + (this.size() << 4));
		sb.append('{');
		final Iterator<Entry<RecordKey, FileLocation>> it = new EntryIterator(this.rootNode);
		boolean first = true;
		while (it.hasNext()) {
			final Entry<RecordKey, FileLocation> entry = it.next();
			if (!first) {
				sb.append(", ");
			}
			sb.append(entry.getKey()).append('=').append(entry.getValue());
			first = false;
		}
		return sb.append('}').toString();
	}

	/* ===========================================================================================
	 * java.util.Map mutators — unsupported (the map is immutable; use updated/removed/Builder).
	 * =========================================================================================== */

	/**
	 * Always throws: the map is immutable. Use {@link #updated(RecordKey, FileLocation)} instead.
	 *
	 * @throws UnsupportedOperationException always — the map is immutable
	 */
	@Override
	public FileLocation put(RecordKey key, FileLocation value) {
		throw new UnsupportedOperationException("OffsetLocationChampMap is immutable; use updated(key, value).");
	}

	/**
	 * Always throws: the map is immutable. Use {@link #removed(RecordKey)} instead.
	 *
	 * @throws UnsupportedOperationException always — the map is immutable
	 */
	@Override
	public FileLocation remove(Object key) {
		throw new UnsupportedOperationException("OffsetLocationChampMap is immutable; use removed(key).");
	}

	/**
	 * Always throws: the map is immutable. Use a {@link Builder} instead.
	 *
	 * @throws UnsupportedOperationException always — the map is immutable
	 */
	@Override
	public void putAll(@Nonnull Map<? extends RecordKey, ? extends FileLocation> m) {
		throw new UnsupportedOperationException("OffsetLocationChampMap is immutable; use a Builder.");
	}

	/**
	 * Always throws: the map is immutable. Use {@link #empty()} for an empty map.
	 *
	 * @throws UnsupportedOperationException always — the map is immutable
	 */
	@Override
	public void clear() {
		throw new UnsupportedOperationException("OffsetLocationChampMap is immutable; use OffsetLocationChampMap.empty().");
	}

	/**
	 * Always throws: the map is immutable. The {@link Map} default would silently no-op when the key
	 * is already present, so it is overridden to honour the contract unconditionally.
	 *
	 * @throws UnsupportedOperationException always — the map is immutable
	 */
	@Nullable
	@Override
	public FileLocation putIfAbsent(RecordKey key, FileLocation value) {
		throw new UnsupportedOperationException("OffsetLocationChampMap is immutable; use updated/removed or a Builder.");
	}

	/**
	 * Always throws: the map is immutable. The {@link Map} default would silently no-op when the key
	 * is absent, so it is overridden to honour the contract unconditionally.
	 *
	 * @throws UnsupportedOperationException always — the map is immutable
	 */
	@Nullable
	@Override
	public FileLocation replace(RecordKey key, FileLocation value) {
		throw new UnsupportedOperationException("OffsetLocationChampMap is immutable; use updated/removed or a Builder.");
	}

	/**
	 * Always throws: the map is immutable. The {@link Map} default would silently no-op when the
	 * current value does not match, so it is overridden to honour the contract unconditionally.
	 *
	 * @throws UnsupportedOperationException always — the map is immutable
	 */
	@Override
	public boolean replace(RecordKey key, FileLocation oldValue, FileLocation newValue) {
		throw new UnsupportedOperationException("OffsetLocationChampMap is immutable; use updated/removed or a Builder.");
	}

	/**
	 * Always throws: the map is immutable. The {@link Map} default would silently no-op when the
	 * value does not match, so it is overridden to honour the contract unconditionally.
	 *
	 * @throws UnsupportedOperationException always — the map is immutable
	 */
	@Override
	public boolean remove(@Nullable Object key, @Nullable Object value) {
		throw new UnsupportedOperationException("OffsetLocationChampMap is immutable; use updated/removed or a Builder.");
	}

	/**
	 * Always throws: the map is immutable. The {@link Map} default would silently no-op when the
	 * remapping yields no change, so it is overridden to honour the contract unconditionally.
	 *
	 * @throws UnsupportedOperationException always — the map is immutable
	 */
	@Nullable
	@Override
	public FileLocation compute(
		RecordKey key, @Nonnull BiFunction<? super RecordKey, ? super FileLocation, ? extends FileLocation> remappingFunction) {
		throw new UnsupportedOperationException("OffsetLocationChampMap is immutable; use updated/removed or a Builder.");
	}

	/**
	 * Always throws: the map is immutable. The {@link Map} default would silently no-op when the key
	 * is already present, so it is overridden to honour the contract unconditionally.
	 *
	 * @throws UnsupportedOperationException always — the map is immutable
	 */
	@Nullable
	@Override
	public FileLocation computeIfAbsent(
		RecordKey key, @Nonnull Function<? super RecordKey, ? extends FileLocation> mappingFunction) {
		throw new UnsupportedOperationException("OffsetLocationChampMap is immutable; use updated/removed or a Builder.");
	}

	/**
	 * Always throws: the map is immutable. The {@link Map} default would silently no-op when the key
	 * is absent, so it is overridden to honour the contract unconditionally.
	 *
	 * @throws UnsupportedOperationException always — the map is immutable
	 */
	@Nullable
	@Override
	public FileLocation computeIfPresent(
		RecordKey key, @Nonnull BiFunction<? super RecordKey, ? super FileLocation, ? extends FileLocation> remappingFunction) {
		throw new UnsupportedOperationException("OffsetLocationChampMap is immutable; use updated/removed or a Builder.");
	}

	/**
	 * Always throws: the map is immutable. The {@link Map} default would silently no-op for some
	 * remapping outcomes, so it is overridden to honour the contract unconditionally.
	 *
	 * @throws UnsupportedOperationException always — the map is immutable
	 */
	@Nullable
	@Override
	public FileLocation merge(
		RecordKey key, @Nonnull FileLocation value,
		@Nonnull BiFunction<? super FileLocation, ? super FileLocation, ? extends FileLocation> remappingFunction) {
		throw new UnsupportedOperationException("OffsetLocationChampMap is immutable; use updated/removed or a Builder.");
	}

	/**
	 * Always throws: the map is immutable. Overridden so the contract holds even for an empty map
	 * where the {@link Map} default would never invoke the function.
	 *
	 * @throws UnsupportedOperationException always — the map is immutable
	 */
	@Override
	public void replaceAll(BiFunction<? super RecordKey, ? super FileLocation, ? extends FileLocation> function) {
		throw new UnsupportedOperationException("OffsetLocationChampMap is immutable; use updated/removed or a Builder.");
	}

	/* ===========================================================================================
	 * Static bit-arithmetic and packing helpers (shared by all nodes).
	 * =========================================================================================== */

	/**
	 * Packs the record type (high 32 bits, sign-extended) and length (low 32 bits) into one long via
	 * the established {@link NumberUtils#pack(int, int)}. The byte → int widening sign-extends the type
	 * into the high word exactly as a byte → long widening would; {@link #unpackType} truncates those
	 * high bits back to a byte on read, so the sign extension is harmless.
	 */
	private static long pack(byte recordType, int recordLength) {
		return NumberUtils.pack(recordType, recordLength);
	}

	/**
	 * Extracts the record type from a packed `typeLen` slot — the high-order half written by
	 * {@link #pack}, truncated back to the byte it originated from. Uses the allocation-free
	 * {@link NumberUtils#unpackHigh(long)} (the array-free counterpart of {@link NumberUtils#unpack})
	 * because this accessor sits on the lookup hot path.
	 */
	private static byte unpackType(long typeLen) {
		return (byte) NumberUtils.unpackHigh(typeLen);
	}

	/**
	 * Extracts the record length from a packed `typeLen` slot — the low-order half written by
	 * {@link #pack}. Uses the allocation-free {@link NumberUtils#unpackLow(long)} (the array-free
	 * counterpart of {@link NumberUtils#unpack}) because this accessor sits on the lookup hot path.
	 */
	private static int unpackLen(long typeLen) {
		return NumberUtils.unpackLow(typeLen);
	}

	/**
	 * The trie's own hash of a `(recordType, primaryKey)` key. Defined internally and used
	 * consistently for insertion, lookup and node splitting; deliberately independent of
	 * {@link RecordKey#hashCode()} so the canonical form does not depend on the record's
	 * compiler-generated hash.
	 */
	private static int keyHash(byte recordType, long primaryKey) {
		int result = recordType;
		result = 31 * result + Long.hashCode(primaryKey);
		return result;
	}

	/**
	 * Spreads the bits of a raw hash code so that the 5-bit chunks consumed at each trie level are
	 * well distributed even for hash codes with poor low-bit entropy. The exact function must stay
	 * stable because it determines node layout (and thus canonical form).
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

	/** Returns a copy of `array` with `value` inserted at `index`. */
	@Nonnull
	private static long[] insertLong(@Nonnull long[] array, int index, long value) {
		final long[] result = new long[array.length + 1];
		System.arraycopy(array, 0, result, 0, index);
		result[index] = value;
		System.arraycopy(array, index, result, index + 1, array.length - index);
		return result;
	}

	/** Returns a copy of `array` with the element at `index` removed. */
	@Nonnull
	private static long[] removeLong(@Nonnull long[] array, int index) {
		final long[] result = new long[array.length - 1];
		System.arraycopy(array, 0, result, 0, index);
		System.arraycopy(array, index + 1, result, index, array.length - index - 1);
		return result;
	}

	/** Returns a copy of `array` with `value` inserted at `index`. */
	@Nonnull
	private static LocationNode[] insertNode(@Nonnull LocationNode[] array, int index, @Nonnull LocationNode value) {
		final LocationNode[] result = new LocationNode[array.length + 1];
		System.arraycopy(array, 0, result, 0, index);
		result[index] = value;
		System.arraycopy(array, index, result, index + 1, array.length - index);
		return result;
	}

	/** Returns a copy of `array` with the element at `index` removed. */
	@Nonnull
	private static LocationNode[] removeNode(@Nonnull LocationNode[] array, int index) {
		final LocationNode[] result = new LocationNode[array.length - 1];
		System.arraycopy(array, 0, result, 0, index);
		System.arraycopy(array, index + 1, result, index, array.length - index - 1);
		return result;
	}

	/**
	 * Combines two distinct pairs that share the hash chunk consumed so far into a fresh sub-trie: a
	 * two-entry node if their chunks differ at this level, a deeper sub-node if they still agree, or a
	 * {@link CollisionLocationNode} once the hash is exhausted.
	 */
	@Nonnull
	private static LocationNode mergeTwoPairs(
		long pk0, long pos0, long typeLen0, int keyHash0,
		long pk1, long pos1, long typeLen1, int keyHash1, int shift) {
		if (shift >= HASH_CODE_LENGTH) {
			return new CollisionLocationNode(
				keyHash0, new long[]{pk0, pk1}, new long[]{pos0, pos1}, new long[]{typeLen0, typeLen1});
		}
		final int mask0 = maskFrom(keyHash0, shift);
		final int mask1 = maskFrom(keyHash1, shift);
		if (mask0 != mask1) {
			// distinct chunks — both pairs fit inline at this level
			final int dataMap = bitposFrom(mask0) | bitposFrom(mask1);
			if (mask0 < mask1) {
				return new BitmapLocationNode(
					dataMap, 0, new long[]{pk0, pk1}, new long[]{pos0, pos1},
					new long[]{typeLen0, typeLen1}, EMPTY_NODES, 2);
			} else {
				return new BitmapLocationNode(
					dataMap, 0, new long[]{pk1, pk0}, new long[]{pos1, pos0},
					new long[]{typeLen1, typeLen0}, EMPTY_NODES, 2);
			}
		} else {
			// identical chunk — recurse one level deeper
			final LocationNode child = mergeTwoPairs(
				pk0, pos0, typeLen0, keyHash0, pk1, pos1, typeLen1, keyHash1, shift + BIT_PARTITION_SIZE);
			return new BitmapLocationNode(
				0, bitposFrom(mask0), EMPTY_LONGS, EMPTY_LONGS, EMPTY_LONGS, new LocationNode[]{child}, child.size());
		}
	}

	/* ===========================================================================================
	 * Abstract trie node.
	 * =========================================================================================== */

	/**
	 * Common abstract base of the two concrete node kinds: {@link BitmapLocationNode} (the usual
	 * inner/leaf node) and {@link CollisionLocationNode} (a bucket of keys sharing a full 32-bit
	 * improved hash).
	 */
	private abstract static class LocationNode {

		abstract int size();

		/** Returns the value bound to `(type, pk)` reconstructed as a {@link FileLocation}, or null. */
		@Nullable
		abstract FileLocation get(byte type, long pk, int keyHash, int shift);

		/** Returns the record length bound to `(type, pk)`, or {@link #RECORD_LENGTH_ABSENT}. */
		abstract int findRecordLength(byte type, long pk, int keyHash, int shift);

		/** Returns whether `(type, pk)` is bound in this sub-trie. */
		abstract boolean containsKey(byte type, long pk, int keyHash, int shift);

		/** Returns a node with `(pk, pos, typeLen)` bound, replacing any existing value. */
		@Nonnull
		abstract LocationNode updated(long pk, long pos, long typeLen, int keyHash, int shift);

		/** Returns a node with `(type, pk)` removed, re-canonicalized. */
		@Nonnull
		abstract LocationNode removed(byte type, long pk, int keyHash, int shift);

		abstract boolean hasNodes();

		abstract int nodeArity();

		@Nonnull
		abstract LocationNode getNode(int index);

		abstract boolean hasPayload();

		abstract int payloadArity();

		abstract long pkAt(int index);

		abstract long posAt(int index);

		abstract long typeLenAt(int index);

		/** Emits every entry of this sub-trie into the passed builder (used by {@link #from}). */
		abstract void buildTo(@Nonnull Builder builder);

		/** Deep copy used by the {@link Builder} to gain exclusive ownership before mutating. */
		@Nonnull
		abstract LocationNode copy();
	}

	/* ===========================================================================================
	 * Bitmap-indexed node — the workhorse.
	 * =========================================================================================== */

	/**
	 * A trie node carrying both inlined payload (selected by {@link #dataMap}) and child sub-nodes
	 * (selected by {@link #nodeMap}). Payload lives in the three parallel primitive arrays
	 * {@link #keysPk}/{@link #valPos}/{@link #typeLen}, densely packed in `dataMap` bit order; child
	 * nodes live in {@link #nodes}, densely packed in `nodeMap` bit order. The fields are mutable only
	 * so the {@link Builder} can assemble a node in place; once a node becomes part of a published map
	 * it is never mutated again.
	 */
	private static final class BitmapLocationNode extends LocationNode {

		private int dataMap;
		private int nodeMap;
		@Nonnull private long[] keysPk;
		@Nonnull private long[] valPos;
		@Nonnull private long[] typeLen;
		@Nonnull private LocationNode[] nodes;
		private int size;

		BitmapLocationNode(
			int dataMap, int nodeMap, @Nonnull long[] keysPk, @Nonnull long[] valPos,
			@Nonnull long[] typeLen, @Nonnull LocationNode[] nodes, int size) {
			this.dataMap = dataMap;
			this.nodeMap = nodeMap;
			this.keysPk = keysPk;
			this.valPos = valPos;
			this.typeLen = typeLen;
			this.nodes = nodes;
			this.size = size;
		}

		/** Dense payload index of the data slot at `bitpos`. */
		private int dataIndex(int bitpos) {
			return Integer.bitCount(this.dataMap & (bitpos - 1));
		}

		/** Dense node index of the node slot at `bitpos`. */
		private int nodeIndex(int bitpos) {
			return Integer.bitCount(this.nodeMap & (bitpos - 1));
		}

		@Override
		int size() {
			return this.size;
		}

		@Override
		boolean hasNodes() {
			return this.nodeMap != 0;
		}

		@Override
		int nodeArity() {
			return Integer.bitCount(this.nodeMap);
		}

		@Nonnull
		@Override
		LocationNode getNode(int index) {
			return this.nodes[index];
		}

		@Override
		boolean hasPayload() {
			return this.dataMap != 0;
		}

		@Override
		int payloadArity() {
			return Integer.bitCount(this.dataMap);
		}

		@Override
		long pkAt(int index) {
			return this.keysPk[index];
		}

		@Override
		long posAt(int index) {
			return this.valPos[index];
		}

		@Override
		long typeLenAt(int index) {
			return this.typeLen[index];
		}

		@Nullable
		@Override
		FileLocation get(byte type, long pk, int keyHash, int shift) {
			final int mask = maskFrom(keyHash, shift);
			final int bitpos = bitposFrom(mask);
			if ((this.dataMap & bitpos) != 0) {
				final int index = dataIndex(bitpos);
				return (this.keysPk[index] == pk && unpackType(this.typeLen[index]) == type)
					? new FileLocation(this.valPos[index], unpackLen(this.typeLen[index])) : null;
			} else if ((this.nodeMap & bitpos) != 0) {
				return this.nodes[nodeIndex(bitpos)].get(type, pk, keyHash, shift + BIT_PARTITION_SIZE);
			}
			return null;
		}

		@Override
		int findRecordLength(byte type, long pk, int keyHash, int shift) {
			final int mask = maskFrom(keyHash, shift);
			final int bitpos = bitposFrom(mask);
			if ((this.dataMap & bitpos) != 0) {
				final int index = dataIndex(bitpos);
				return (this.keysPk[index] == pk && unpackType(this.typeLen[index]) == type)
					? unpackLen(this.typeLen[index]) : RECORD_LENGTH_ABSENT;
			} else if ((this.nodeMap & bitpos) != 0) {
				return this.nodes[nodeIndex(bitpos)].findRecordLength(type, pk, keyHash, shift + BIT_PARTITION_SIZE);
			}
			return RECORD_LENGTH_ABSENT;
		}

		@Override
		boolean containsKey(byte type, long pk, int keyHash, int shift) {
			final int mask = maskFrom(keyHash, shift);
			final int bitpos = bitposFrom(mask);
			if ((this.dataMap & bitpos) != 0) {
				final int index = dataIndex(bitpos);
				return this.keysPk[index] == pk && unpackType(this.typeLen[index]) == type;
			} else if ((this.nodeMap & bitpos) != 0) {
				return this.nodes[nodeIndex(bitpos)].containsKey(type, pk, keyHash, shift + BIT_PARTITION_SIZE);
			}
			return false;
		}

		@Nonnull
		@Override
		LocationNode updated(long pk, long pos, long typeLen, int keyHash, int shift) {
			final int mask = maskFrom(keyHash, shift);
			final int bitpos = bitposFrom(mask);

			if ((this.dataMap & bitpos) != 0) {
				final int index = dataIndex(bitpos);
				if (this.keysPk[index] == pk && unpackType(this.typeLen[index]) == unpackType(typeLen)) {
					// same key — replace value (or return this if identical)
					if (this.valPos[index] == pos && this.typeLen[index] == typeLen) {
						return this;
					}
					return copyAndSetValue(index, pos, typeLen);
				} else {
					// hash chunk collision — recompute the resident key's improved hash and split
					final long existingPk = this.keysPk[index];
					final int existingKeyHash = improve(keyHash(unpackType(this.typeLen[index]), existingPk));
					final LocationNode subNode = mergeTwoPairs(
						existingPk, this.valPos[index], this.typeLen[index], existingKeyHash,
						pk, pos, typeLen, keyHash, shift + BIT_PARTITION_SIZE);
					return copyAndMigrateInlineToNode(bitpos, subNode);
				}
			} else if ((this.nodeMap & bitpos) != 0) {
				final int index = nodeIndex(bitpos);
				final LocationNode subNode = this.nodes[index];
				final LocationNode newSub = subNode.updated(pk, pos, typeLen, keyHash, shift + BIT_PARTITION_SIZE);
				return newSub == subNode ? this : copyAndSetNode(index, subNode, newSub);
			} else {
				return copyAndInsertValue(bitpos, pk, pos, typeLen);
			}
		}

		@Nonnull
		@Override
		LocationNode removed(byte type, long pk, int keyHash, int shift) {
			final int mask = maskFrom(keyHash, shift);
			final int bitpos = bitposFrom(mask);

			if ((this.dataMap & bitpos) != 0) {
				final int index = dataIndex(bitpos);
				if (this.keysPk[index] == pk && unpackType(this.typeLen[index]) == type) {
					if (Integer.bitCount(this.dataMap) == 2 && this.nodeMap == 0) {
						// drop down to the single remaining pair; it will either become the new root or be
						// inlined into the parent while unwinding the recursion (canonicalization)
						final int keep = index == 0 ? 1 : 0;
						final int newDataMap = shift == 0
							? (this.dataMap ^ bitpos)
							: bitposFrom(maskFrom(keyHash, 0));
						return new BitmapLocationNode(
							newDataMap, 0,
							new long[]{this.keysPk[keep]}, new long[]{this.valPos[keep]},
							new long[]{this.typeLen[keep]}, EMPTY_NODES, 1);
					}
					return copyAndRemoveValue(bitpos, index);
				}
				return this;
			} else if ((this.nodeMap & bitpos) != 0) {
				final int index = nodeIndex(bitpos);
				final LocationNode subNode = this.nodes[index];
				final LocationNode newSub = subNode.removed(type, pk, keyHash, shift + BIT_PARTITION_SIZE);
				if (newSub == subNode) {
					return this;
				}
				if (newSub.size() == 1) {
					if (this.size == subNode.size()) {
						// the sub-node was the only child of this node — escalate it as the result
						return newSub;
					}
					// inline the single survivor back into this node (move it to the front)
					return copyAndMigrateNodeToInline(bitpos, subNode, newSub);
				}
				// sub-node still has multiple entries — just replace the child reference
				return copyAndSetNode(index, subNode, newSub);
			}
			return this;
		}

		@Override
		void buildTo(@Nonnull Builder builder) {
			final int iN = payloadArity();
			final int jN = nodeArity();
			for (int i = 0; i < iN; i++) {
				builder.addPacked(this.keysPk[i], this.valPos[i], this.typeLen[i]);
			}
			for (int j = 0; j < jN; j++) {
				this.nodes[j].buildTo(builder);
			}
		}

		/* ---- structural copy helpers (persistent path) ---- */

		@Nonnull
		private BitmapLocationNode copyAndSetValue(int index, long pos, long typeLen) {
			final long[] newPos = this.valPos.clone();
			final long[] newTypeLen = this.typeLen.clone();
			newPos[index] = pos;
			newTypeLen[index] = typeLen;
			return new BitmapLocationNode(
				this.dataMap, this.nodeMap, this.keysPk, newPos, newTypeLen, this.nodes, this.size);
		}

		@Nonnull
		private BitmapLocationNode copyAndInsertValue(int bitpos, long pk, long pos, long typeLen) {
			final int index = dataIndex(bitpos);
			return new BitmapLocationNode(
				this.dataMap | bitpos, this.nodeMap,
				insertLong(this.keysPk, index, pk), insertLong(this.valPos, index, pos),
				insertLong(this.typeLen, index, typeLen), this.nodes, this.size + 1);
		}

		@Nonnull
		private BitmapLocationNode copyAndRemoveValue(int bitpos, int index) {
			return new BitmapLocationNode(
				this.dataMap ^ bitpos, this.nodeMap,
				removeLong(this.keysPk, index), removeLong(this.valPos, index),
				removeLong(this.typeLen, index), this.nodes, this.size - 1);
		}

		@Nonnull
		private BitmapLocationNode copyAndSetNode(int index, @Nonnull LocationNode oldNode, @Nonnull LocationNode newNode) {
			final LocationNode[] newNodes = this.nodes.clone();
			newNodes[index] = newNode;
			return new BitmapLocationNode(
				this.dataMap, this.nodeMap, this.keysPk, this.valPos, this.typeLen, newNodes,
				this.size - oldNode.size() + newNode.size());
		}

		@Nonnull
		private BitmapLocationNode copyAndMigrateInlineToNode(int bitpos, @Nonnull LocationNode node) {
			final int dataIdx = dataIndex(bitpos);
			final int nodeIdx = nodeIndex(bitpos);
			return new BitmapLocationNode(
				this.dataMap ^ bitpos, this.nodeMap | bitpos,
				removeLong(this.keysPk, dataIdx), removeLong(this.valPos, dataIdx),
				removeLong(this.typeLen, dataIdx), insertNode(this.nodes, nodeIdx, node),
				this.size - 1 + node.size());
		}

		@Nonnull
		private BitmapLocationNode copyAndMigrateNodeToInline(
			int bitpos, @Nonnull LocationNode oldNode, @Nonnull LocationNode single) {
			final int nodeIdx = nodeIndex(bitpos);
			final int dataIdx = dataIndex(bitpos);
			return new BitmapLocationNode(
				this.dataMap | bitpos, this.nodeMap ^ bitpos,
				insertLong(this.keysPk, dataIdx, single.pkAt(0)),
				insertLong(this.valPos, dataIdx, single.posAt(0)),
				insertLong(this.typeLen, dataIdx, single.typeLenAt(0)),
				removeNode(this.nodes, nodeIdx), this.size - oldNode.size() + 1);
		}

		@Nonnull
		@Override
		LocationNode copy() {
			final LocationNode[] nodesClone = this.nodes.clone();
			// child node slots must be deep-copied so the builder owns every node it might mutate in place
			for (int i = 0; i < nodesClone.length; i++) {
				nodesClone[i] = nodesClone[i].copy();
			}
			return new BitmapLocationNode(
				this.dataMap, this.nodeMap, this.keysPk.clone(), this.valPos.clone(),
				this.typeLen.clone(), nodesClone, this.size);
		}

		// The compared fields are non-final only to let the transient Builder mutate builder-owned nodes
		// in place (the O(M) bulk-build optimisation). Once a node is reachable from a published map it is
		// never mutated again — every persistent updated/removed path-copies into fresh nodes — so by the
		// time equals is invoked (only on published roots) the fields are effectively final.
		@SuppressWarnings("NonFinalFieldReferencedInEquals")
		@Override
		public boolean equals(@Nullable Object o) {
			if (o == this) {
				return true;
			}
			if (!(o instanceof BitmapLocationNode node)) {
				return false;
			}
			// in canonical form, equal content implies identical structure, so the array + recursive node
			// comparison is the structural equality check
			return this.dataMap == node.dataMap
				&& this.nodeMap == node.nodeMap
				&& this.size == node.size
				&& Arrays.equals(this.keysPk, node.keysPk)
				&& Arrays.equals(this.valPos, node.valPos)
				&& Arrays.equals(this.typeLen, node.typeLen)
				&& Arrays.equals(this.nodes, node.nodes);
		}

		@Override
		public int hashCode() {
			throw new UnsupportedOperationException("Trie nodes do not support hashing.");
		}

		/* ---- in-place mutators, used exclusively by the Builder on builder-owned nodes ---- */

		/** Mutably inserts a brand-new pair at `bitpos` (the slot must be empty). */
		void insertValueInPlace(int bitpos, long pk, long pos, long typeLen) {
			final int index = dataIndex(bitpos);
			this.keysPk = insertLong(this.keysPk, index, pk);
			this.valPos = insertLong(this.valPos, index, pos);
			this.typeLen = insertLong(this.typeLen, index, typeLen);
			this.dataMap |= bitpos;
			this.size += 1;
		}

		/** Mutably replaces the value of the inlined pair at the payload `index`. */
		void setValueInPlace(int index, long pos, long typeLen) {
			this.valPos[index] = pos;
			this.typeLen[index] = typeLen;
		}

		/** Mutably converts the inlined pair at `bitpos` into the passed sub-node. */
		void migrateFromInlineToNodeInPlace(int bitpos, @Nonnull LocationNode node) {
			final int dataIdx = dataIndex(bitpos);
			final int nodeIdx = nodeIndex(bitpos);
			this.keysPk = removeLong(this.keysPk, dataIdx);
			this.valPos = removeLong(this.valPos, dataIdx);
			this.typeLen = removeLong(this.typeLen, dataIdx);
			this.nodes = insertNode(this.nodes, nodeIdx, node);
			this.dataMap ^= bitpos;
			this.nodeMap |= bitpos;
			this.size = this.size - 1 + node.size();
		}
	}

	/* ===========================================================================================
	 * Hash-collision node — a bucket of keys sharing a full 32-bit improved hash.
	 * =========================================================================================== */

	/**
	 * A leaf node holding two or more keys whose improved hashes are identical across all 32 bits.
	 * Lookups are linear within the (tiny) bucket. Mutating builder operations always reallocate the
	 * arrays (they never write in place), so a {@link #copy()} can safely share them.
	 */
	private static final class CollisionLocationNode extends LocationNode {

		/** The shared improved hash of every key in this bucket. */
		private final int keyHash;
		@Nonnull private long[] keysPk;
		@Nonnull private long[] valPos;
		@Nonnull private long[] typeLen;

		CollisionLocationNode(int keyHash, @Nonnull long[] keysPk, @Nonnull long[] valPos, @Nonnull long[] typeLen) {
			if (keysPk.length < 2) {
				// defensive: a collision bucket must hold at least two pairs
				throw new GenericEvitaInternalError("Hash-collision node must hold at least two entries.");
			}
			this.keyHash = keyHash;
			this.keysPk = keysPk;
			this.valPos = valPos;
			this.typeLen = typeLen;
		}

		/** Returns the payload index of `(type, pk)` within the bucket, or -1 if absent. */
		private int indexOf(byte type, long pk) {
			for (int i = 0; i < this.keysPk.length; i++) {
				if (this.keysPk[i] == pk && unpackType(this.typeLen[i]) == type) {
					return i;
				}
			}
			return -1;
		}

		@Override
		int size() {
			return this.keysPk.length;
		}

		@Nullable
		@Override
		FileLocation get(byte type, long pk, int keyHash, int shift) {
			final int index = indexOf(type, pk);
			return index < 0 ? null : new FileLocation(this.valPos[index], unpackLen(this.typeLen[index]));
		}

		@Override
		int findRecordLength(byte type, long pk, int keyHash, int shift) {
			final int index = indexOf(type, pk);
			return index < 0 ? RECORD_LENGTH_ABSENT : unpackLen(this.typeLen[index]);
		}

		@Override
		boolean containsKey(byte type, long pk, int keyHash, int shift) {
			return indexOf(type, pk) >= 0;
		}

		@Nonnull
		@Override
		LocationNode updated(long pk, long pos, long typeLen, int keyHash, int shift) {
			final int index = indexOf(unpackType(typeLen), pk);
			if (index >= 0) {
				if (this.valPos[index] == pos && this.typeLen[index] == typeLen) {
					return this;
				}
				final long[] newPos = this.valPos.clone();
				final long[] newTypeLen = this.typeLen.clone();
				newPos[index] = pos;
				newTypeLen[index] = typeLen;
				return new CollisionLocationNode(this.keyHash, this.keysPk, newPos, newTypeLen);
			}
			final int n = this.keysPk.length;
			return new CollisionLocationNode(
				this.keyHash, insertLong(this.keysPk, n, pk),
				insertLong(this.valPos, n, pos), insertLong(this.typeLen, n, typeLen));
		}

		@Nonnull
		@Override
		LocationNode removed(byte type, long pk, int keyHash, int shift) {
			final int index = indexOf(type, pk);
			if (index < 0) {
				return this;
			}
			if (this.keysPk.length == 2) {
				// removing one of two survivors leaves a single pair — canonicalize back to an inlined
				// bitmap node so the parent can absorb it
				final int keep = index == 0 ? 1 : 0;
				return new BitmapLocationNode(
					bitposFrom(maskFrom(this.keyHash, 0)), 0,
					new long[]{this.keysPk[keep]}, new long[]{this.valPos[keep]},
					new long[]{this.typeLen[keep]}, EMPTY_NODES, 1);
			}
			return new CollisionLocationNode(
				this.keyHash, removeLong(this.keysPk, index),
				removeLong(this.valPos, index), removeLong(this.typeLen, index));
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
		LocationNode getNode(int index) {
			throw new IndexOutOfBoundsException("No sub-nodes present in a hash-collision leaf node.");
		}

		@Override
		boolean hasPayload() {
			return true;
		}

		@Override
		int payloadArity() {
			return this.keysPk.length;
		}

		@Override
		long pkAt(int index) {
			return this.keysPk[index];
		}

		@Override
		long posAt(int index) {
			return this.valPos[index];
		}

		@Override
		long typeLenAt(int index) {
			return this.typeLen[index];
		}

		@Override
		void buildTo(@Nonnull Builder builder) {
			for (int i = 0; i < this.keysPk.length; i++) {
				builder.addPacked(this.keysPk[i], this.valPos[i], this.typeLen[i]);
			}
		}

		@Nonnull
		@Override
		LocationNode copy() {
			// arrays are never mutated in place (builder ops reallocate), so sharing them is safe
			return new CollisionLocationNode(this.keyHash, this.keysPk, this.valPos, this.typeLen);
		}

		@Override
		public boolean equals(@Nullable Object o) {
			if (o == this) {
				return true;
			}
			if (!(o instanceof CollisionLocationNode node)) {
				return false;
			}
			if (this.keyHash != node.keyHash || this.keysPk.length != node.keysPk.length) {
				return false;
			}
			// order within a bucket depends on insertion order, so compare as multisets
			for (int i = 0; i < this.keysPk.length; i++) {
				final int otherIndex = node.indexOf(unpackType(this.typeLen[i]), this.keysPk[i]);
				if (otherIndex < 0
					|| this.valPos[i] != node.valPos[otherIndex]
					|| this.typeLen[i] != node.typeLen[otherIndex]) {
					return false;
				}
			}
			return true;
		}

		@Override
		public int hashCode() {
			throw new UnsupportedOperationException("Trie nodes do not support hashing.");
		}

		/* ---- in-place mutators, used exclusively by the Builder ---- */

		/** Mutably appends a new pair (caller guarantees the key is absent). */
		void appendInPlace(long pk, long pos, long typeLen) {
			final int n = this.keysPk.length;
			this.keysPk = insertLong(this.keysPk, n, pk);
			this.valPos = insertLong(this.valPos, n, pos);
			this.typeLen = insertLong(this.typeLen, n, typeLen);
		}

		/** Mutably replaces the value at the given payload `index`. */
		void setInPlace(int index, long pos, long typeLen) {
			final long[] newPos = this.valPos.clone();
			final long[] newTypeLen = this.typeLen.clone();
			newPos[index] = pos;
			newTypeLen[index] = typeLen;
			this.valPos = newPos;
			this.typeLen = newTypeLen;
		}
	}

	/* ===========================================================================================
	 * Builder (transient) — single-threaded, O(M) from-scratch assembly.
	 * =========================================================================================== */

	/**
	 * Mutable, single-threaded assembler of an {@link OffsetLocationChampMap}. Building a map of M
	 * entries with the builder costs `O(M)` rather than the `O(M·log₃₂ M)` of M independent persistent
	 * insertions, because intermediate nodes are mutated in place instead of path-copied.
	 *
	 * The builder follows the "transient" pattern: after {@link #build()} hands out a map, the next
	 * mutation makes a one-time defensive {@link LocationNode#copy()} so the published map is never
	 * disturbed (the {@link #aliased} guard). In the common build-once usage no copy ever happens.
	 */
	@NotThreadSafe
	public static final class Builder {

		/** The last map handed out by {@link #build()}, or null — signals a needed copy-before-mutate. */
		@Nullable private OffsetLocationChampMap aliased;
		/** Root of the partially-built trie; always a fresh, builder-owned node. */
		@Nonnull private BitmapLocationNode rootNode;

		Builder() {
			this.rootNode = newEmptyRootNode();
		}

		@Nonnull
		private static BitmapLocationNode newEmptyRootNode() {
			// distinct mutable node instance (shared empty arrays are fine — they are reallocated on the
			// first structural change, never written in place)
			return new BitmapLocationNode(0, 0, EMPTY_LONGS, EMPTY_LONGS, EMPTY_LONGS, EMPTY_NODES, 0);
		}

		/**
		 * Adds or replaces a key/value pair. Returns this builder for chaining.
		 *
		 * @param key   the key to bind (non-null)
		 * @param value the value to bind (non-null)
		 * @return this builder
		 */
		@Nonnull
		public Builder add(@Nonnull RecordKey key, @Nonnull FileLocation value) {
			Objects.requireNonNull(key, "key must not be null");
			Objects.requireNonNull(value, "value must not be null");
			final byte type = key.recordType();
			final long pk = key.primaryKey();
			ensureUnaliased();
			update(this.rootNode, pk, value.startingPosition(), pack(type, value.recordLength()),
				improve(keyHash(type, pk)), 0);
			return this;
		}

		/**
		 * Returns the finished {@link OffsetLocationChampMap}. The builder may be reused afterwards; the
		 * next mutation transparently copies the shared structure.
		 *
		 * @return the assembled map
		 */
		@Nonnull
		public OffsetLocationChampMap build() {
			if (this.rootNode.size() == 0) {
				return empty();
			} else if (this.aliased != null) {
				return this.aliased;
			} else {
				this.aliased = new OffsetLocationChampMap(this.rootNode);
				return this.aliased;
			}
		}

		/** Adds with precomputed packed payload (used by {@link LocationNode#buildTo}). */
		void addPacked(long pk, long pos, long typeLen) {
			ensureUnaliased();
			update(this.rootNode, pk, pos, typeLen, improve(keyHash(unpackType(typeLen), pk)), 0);
		}

		private void ensureUnaliased() {
			if (this.aliased != null) {
				this.rootNode = (BitmapLocationNode) this.rootNode.copy();
				this.aliased = null;
			}
		}

		/** Mutable upsert into a builder-owned node. */
		private static void update(
			@Nonnull LocationNode node, long pk, long pos, long typeLen, int keyHash, int shift) {
			if (node instanceof BitmapLocationNode bm) {
				final int mask = maskFrom(keyHash, shift);
				final int bitpos = bitposFrom(mask);
				if ((bm.dataMap & bitpos) != 0) {
					final int index = bm.dataIndex(bitpos);
					if (bm.keysPk[index] == pk && unpackType(bm.typeLen[index]) == unpackType(typeLen)) {
						bm.setValueInPlace(index, pos, typeLen);
					} else {
						final long existingPk = bm.keysPk[index];
						final int existingKeyHash = improve(keyHash(unpackType(bm.typeLen[index]), existingPk));
						final LocationNode subNode = mergeTwoPairs(
							existingPk, bm.valPos[index], bm.typeLen[index], existingKeyHash,
							pk, pos, typeLen, keyHash, shift + BIT_PARTITION_SIZE);
						bm.migrateFromInlineToNodeInPlace(bitpos, subNode);
					}
				} else if ((bm.nodeMap & bitpos) != 0) {
					final int index = bm.nodeIndex(bitpos);
					final LocationNode subNode = bm.getNode(index);
					final int beforeSize = subNode.size();
					update(subNode, pk, pos, typeLen, keyHash, shift + BIT_PARTITION_SIZE);
					bm.size += subNode.size() - beforeSize;
				} else {
					bm.insertValueInPlace(bitpos, pk, pos, typeLen);
				}
			} else {
				final CollisionLocationNode hc = (CollisionLocationNode) node;
				final int index = hc.indexOf(unpackType(typeLen), pk);
				if (index < 0) {
					hc.appendInPlace(pk, pos, typeLen);
				} else {
					hc.setInPlace(index, pos, typeLen);
				}
			}
		}
	}

	/* ===========================================================================================
	 * Iterators — fixed-size cursor stack, no parent pointers.
	 * =========================================================================================== */

	/**
	 * Depth-first pre-order traversal over the trie using a fixed-size explicit stack (bounded by
	 * {@link #MAX_DEPTH}). It first yields all payload of the current node, then descends into child
	 * nodes left to right.
	 */
	private abstract static class BaseIterator {

		protected int currentValueCursor;
		protected int currentValueLength;
		protected LocationNode currentValueNode;

		private int currentStackLevel = -1;
		private final int[] nodeCursorsAndLengths = new int[MAX_DEPTH << 1];
		private final LocationNode[] nodes = new LocationNode[MAX_DEPTH];

		BaseIterator(@Nonnull LocationNode rootNode) {
			if (rootNode.hasNodes()) {
				pushNode(rootNode);
			}
			if (rootNode.hasPayload()) {
				setupPayloadNode(rootNode);
			}
		}

		private void setupPayloadNode(@Nonnull LocationNode node) {
			this.currentValueNode = node;
			this.currentValueCursor = 0;
			this.currentValueLength = node.payloadArity();
		}

		private void pushNode(@Nonnull LocationNode node) {
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
					final LocationNode nextNode = this.nodes[this.currentStackLevel].getNode(nodeCursor);
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

	private static final class KeyIterator extends BaseIterator implements Iterator<RecordKey> {
		KeyIterator(@Nonnull LocationNode rootNode) {
			super(rootNode);
		}

		@Override
		public RecordKey next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}
			final long typeLen = this.currentValueNode.typeLenAt(this.currentValueCursor);
			final RecordKey key = new RecordKey(unpackType(typeLen), this.currentValueNode.pkAt(this.currentValueCursor));
			this.currentValueCursor++;
			return key;
		}
	}

	private static final class ValueIterator extends BaseIterator implements Iterator<FileLocation> {
		ValueIterator(@Nonnull LocationNode rootNode) {
			super(rootNode);
		}

		@Override
		public FileLocation next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}
			final FileLocation value = new FileLocation(
				this.currentValueNode.posAt(this.currentValueCursor),
				unpackLen(this.currentValueNode.typeLenAt(this.currentValueCursor)));
			this.currentValueCursor++;
			return value;
		}
	}

	private static final class EntryIterator extends BaseIterator implements Iterator<Entry<RecordKey, FileLocation>> {
		EntryIterator(@Nonnull LocationNode rootNode) {
			super(rootNode);
		}

		@Override
		public Entry<RecordKey, FileLocation> next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}
			final long typeLen = this.currentValueNode.typeLenAt(this.currentValueCursor);
			final Entry<RecordKey, FileLocation> entry = new SimpleImmutableEntry<>(
				new RecordKey(unpackType(typeLen), this.currentValueNode.pkAt(this.currentValueCursor)),
				new FileLocation(this.currentValueNode.posAt(this.currentValueCursor), unpackLen(typeLen)));
			this.currentValueCursor++;
			return entry;
		}
	}

	/* ===========================================================================================
	 * Read-only collection views.
	 * =========================================================================================== */

	private final class KeySetView extends AbstractSet<RecordKey> {
		@Nonnull
		@Override
		public Iterator<RecordKey> iterator() {
			return new KeyIterator(OffsetLocationChampMap.this.rootNode);
		}

		@Override
		public int size() {
			return OffsetLocationChampMap.this.size();
		}

		@Override
		public boolean contains(@Nullable Object o) {
			return OffsetLocationChampMap.this.containsKey(o);
		}
	}

	private final class ValuesView extends AbstractCollection<FileLocation> {
		@Nonnull
		@Override
		public Iterator<FileLocation> iterator() {
			return new ValueIterator(OffsetLocationChampMap.this.rootNode);
		}

		@Override
		public int size() {
			return OffsetLocationChampMap.this.size();
		}

		@Override
		public boolean contains(@Nullable Object o) {
			return OffsetLocationChampMap.this.containsValue(o);
		}
	}

	private final class EntrySetView extends AbstractSet<Entry<RecordKey, FileLocation>> {
		@Nonnull
		@Override
		public Iterator<Entry<RecordKey, FileLocation>> iterator() {
			return new EntryIterator(OffsetLocationChampMap.this.rootNode);
		}

		@Override
		public int size() {
			return OffsetLocationChampMap.this.size();
		}

		@Override
		public boolean contains(@Nullable Object o) {
			if (!(o instanceof Entry<?, ?> entry)) {
				return false;
			}
			final Object key = entry.getKey();
			if (!(key instanceof RecordKey)) {
				return false;
			}
			final FileLocation value = OffsetLocationChampMap.this.get(key);
			return value != null && value.equals(entry.getValue());
		}
	}
}
