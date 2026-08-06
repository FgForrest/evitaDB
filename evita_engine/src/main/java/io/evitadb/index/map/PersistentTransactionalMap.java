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

package io.evitadb.index.map;

import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerCreator;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalStateProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.dataType.champ.ChampMap;
import io.evitadb.index.map.TransactionalMap.TransactionalMemoryEntrySet;
import io.evitadb.index.map.TransactionalMap.TransactionalMemoryKeySet;
import io.evitadb.index.map.TransactionalMap.TransactionalMemoryValues;
import io.evitadb.utils.Assert;
import io.evitadb.utils.VMLayout;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;

import static io.evitadb.core.transaction.Transaction.getTransactionalLayerMaintainer;
import static io.evitadb.core.transaction.Transaction.getTransactionalMemoryLayerIfExists;
import static java.util.Optional.ofNullable;

/**
 * Transactional decorator for {@link Map} whose committed state is a persistent, immutable
 * {@link ChampMap}. It is a drop-in replacement for {@link TransactionalMap} restricted to **plain
 * (non-{@link TransactionalLayerProducer}) values**.
 *
 * The motivation is commit cost. {@link TransactionalMap} rebuilds the entire delegate into a fresh
 * {@link HashMap} on every commit (`O(N)`), even when a single key changed. Because the values here are
 * plain — they carry no nested transactional state that could change without the key changing — the
 * committed map can instead be derived from the previous snapshot by path-copying only the changed
 * keys via {@link ChampMap#updated(Object, Object)} / {@link ChampMap#removed(Object)}. Commit therefore
 * costs `O(Δ·log₃₂ N)` (Δ = number of mutated keys) while sharing all untouched structure with the
 * previous version — the same snapshot-and-derive pattern proven by `OffsetIndex.keyToLocations`.
 *
 * **Two-state representation (the warm-up staging buffer).** The benchmark showed that deriving a fresh
 * immutable {@link ChampMap} *per non-transactional write* is `~5–9×` slower than a `HashMap.put`, which
 * would regress the bulk warm-up / import path (millions of single-row inserts before the catalog goes
 * transactional). To avoid that, the backing {@link #state} is held in one of two forms:
 *
 * - **thawed** — a mutable {@link HashMap}, used during non-transactional warm-up. `put`/`remove`/… are
 *   plain `O(1)` `HashMap` operations, so warm-up keeps `HashMap`-class throughput.
 * - **sealed** — an immutable {@link ChampMap}, used for the transactional (OLTP) steady state. The state
 *   is sealed lazily — on the first transactional touch ({@link #createLayer}) and whenever a committed
 *   snapshot is produced ({@link #createCopyWithMergedTransactionalMemory}) — via {@link ChampMap#from}
 *   (one `O(M)` builder pass). Only the transactional commit path then pays the (tiny) persistent cost.
 *
 * A non-transactional write that arrives while the state is sealed thaws it back to a {@link HashMap}
 * once (`O(N)`); in practice this does not happen because evitaDB performs all warm-up writes before the
 * catalog becomes transactional and routes every later write through a transaction.
 *
 * Other mechanics:
 *
 * - **Transactional** mutations are recorded in a {@link MapChanges} diff layer — reused verbatim from
 *   {@link TransactionalMap} — and folded onto the sealed snapshot on commit.
 * - The inherited {@link Map} default methods (`compute`, `computeIfAbsent`, `computeIfPresent`, `merge`,
 *   `putIfAbsent`, `replace`) are built on `get`/`put`/`remove` and therefore route through the logic
 *   above — they **never** reach {@link ChampMap}'s own `compute`/`merge`/… which throw
 *   {@link UnsupportedOperationException}.
 *
 * Constraints inherited from {@link ChampMap}: **no `null` keys or values** (asserted, fail-fast) and
 * **unordered** iteration (hash-trie order, not insertion order). Callers that need ordering must keep a
 * sibling order-bearing structure, exactly as `UniqueIndex` keeps its `recordIds` bitmap.
 *
 * Like {@link TransactionalMap}, this class is thread-safe for concurrent readers of a published
 * (sealed) snapshot, but **not** thread-safe for concurrent non-transactional writers.
 *
 * @param <K> key type (non-null)
 * @param <V> value type (non-null, plain — never a {@link TransactionalLayerProducer})
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ThreadSafe
public class PersistentTransactionalMap<K, V> implements Map<K, V>,
	Serializable,
	TransactionalLayerCreator<MapChanges<K, V>>,
	TransactionalLayerProducer<MapChanges<K, V>, Map<K, V>>
{
	@Serial private static final long serialVersionUID = 5126774834456936741L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();

	/**
	 * The committed state, held either **sealed** (an immutable {@link ChampMap}, the transactional steady
	 * state) or **thawed** (a mutable {@link HashMap}, the non-transactional warm-up buffer). The concrete
	 * type *is* the mode — {@code state instanceof ChampMap} ⇔ sealed. `transient` because {@link ChampMap}
	 * is not {@link Serializable}; the contents are round-tripped through a plain map in
	 * {@link #writeObject}/{@link #readObject}. `volatile` so a sealed snapshot is safely published to MVCC
	 * readers (see {@link ChampMap}'s release-fence contract).
	 */
	private transient volatile Map<K, V> state;

	/**
	 * Creates a persistent transactional map seeded with the entries of `source`. If `source` is already a
	 * {@link ChampMap} it is adopted **sealed** in `O(1)` (the commit path passes the just-derived
	 * snapshot here). Otherwise the entries are copied into a private mutable {@link HashMap} (**thawed**),
	 * so subsequent non-transactional warm-up writes are `O(1)` and the caller's map is never mutated.
	 * Null keys/values in `source` are rejected (fail-fast) the moment the map is sealed.
	 *
	 * @param source the initial entries (non-null; entries non-null)
	 */
	public PersistentTransactionalMap(@Nonnull Map<K, V> source) {
		this.state = source instanceof ChampMap ? source : new HashMap<>(source);
	}

	/* ===========================================================================================
	 * State transitions — seal (thawed -> sealed) and thaw (sealed -> thawed).
	 * =========================================================================================== */

	/**
	 * Returns the state as an immutable {@link ChampMap}, sealing the thawed {@link HashMap} once (`O(M)`)
	 * and memoizing the result. Used wherever a stable, shareable snapshot is required — the diff-layer
	 * delegate and the committed copy.
	 *
	 * It is public because an owner that forwards this map's contents to a NEXT version unchanged (a version bump
	 * that carries every value by reference) should hand this snapshot to the next map instead of rebuilding a plain
	 * copy: the constructor then adopts it in `O(1)` and the next transactional touch does not have to seal it again.
	 * Only call it when this map has no diff layer, otherwise the snapshot omits the uncommitted key delta.
	 *
	 * @return the sealed snapshot
	 */
	@Nonnull
	public ChampMap<K, V> sealed() {
		final Map<K, V> current = this.state;
		if (current instanceof ChampMap) {
			return (ChampMap<K, V>) current;
		}
		final ChampMap<K, V> frozen = ChampMap.from(current);
		this.state = frozen;
		return frozen;
	}

	/**
	 * Returns an immutable {@link ChampMap} view of the current state **without publishing it**, for a reader that
	 * wants a stable snapshot and must not disturb the map it is reading.
	 *
	 * This is the read-path counterpart of {@link #sealed()}, and the difference matters only while the state is
	 * still thawed. `sealed()` writes the frozen map back into `state`, which is right on the commit path - the next
	 * transactional touch then finds it already sealed - but wrong for a reader:
	 *
	 * - it would make the **next** non-transactional write thaw the map again, an `O(N)` copy, so a reader alternating
	 *   with warm-up writes turns each of them into a full rebuild;
	 * - and publishing a snapshot built by iterating a `HashMap` that another thread is still writing to would
	 *   overwrite `state` with a map missing whatever landed during the iteration, losing committed entries.
	 *
	 * Callers on the commit path must keep using {@link #sealed()} - a reader's snapshot is deliberately not
	 * memoized, so relying on this to make later seals cheap would silently rebuild every time.
	 *
	 * A snapshot taken while another thread performs a non-transactional write may still fail loudly with
	 * {@link java.util.ConcurrentModificationException} rather than silently, which is the intended trade: the
	 * failure is confined to the reader instead of corrupting the map every other caller shares.
	 *
	 * @return an immutable view of the current state
	 */
	@Nonnull
	public ChampMap<K, V> snapshot() {
		final Map<K, V> current = this.state;
		return current instanceof ChampMap ?
			(ChampMap<K, V>) current : ChampMap.from(current);
	}

	/**
	 * Returns the state as a mutable {@link HashMap}, thawing a sealed {@link ChampMap} once (`O(N)` copy)
	 * if necessary. Used by the non-transactional write path so warm-up keeps `HashMap` throughput.
	 *
	 * @return the mutable backing map
	 */
	@Nonnull
	private Map<K, V> thawed() {
		final Map<K, V> current = this.state;
		if (current instanceof ChampMap) {
			final Map<K, V> mutable = new HashMap<>(current);
			this.state = mutable;
			return mutable;
		}
		return current;
	}

	/* ===========================================================================================
	 * Heap accounting.
	 * =========================================================================================== */

	/**
	 * Returns the heap this map occupies, in bytes — this decorator plus the committed state beneath it, priced by
	 * the two supplied sizers.
	 *
	 * The figure covers the **committed** structure only. A diff layer is owned by the transaction that opened it,
	 * lives in transactional memory rather than here, and disappears at commit or rollback, so charging it to the map
	 * would report a footprint that vanishes without the map changing.
	 *
	 * What the state costs depends on which of the two modes it is in, and the difference is real rather than an
	 * artefact: a thawed {@link HashMap} buffer owns a bucket table and a node per entry, while a sealed
	 * {@link ChampMap} owns a trie whose untouched sub-tries are shared with the previous version — and an *empty*
	 * sealed map costs nothing at all, because sealing an empty map yields the JVM-wide {@link ChampMap#empty()}
	 * singleton that no caller owns.
	 *
	 * @param keySizer   prices one key, or returns `0` when this map does not own it
	 * @param valueSizer prices one value, or returns `0` when this map does not own it
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	public long getHeapSizeInBytes(
		@Nonnull ToLongFunction<? super K> keySizer,
		@Nonnull ToLongFunction<? super V> valueSizer
	) {
		final VMLayout layout = VMLayout.current();
		// id + the state slot
		return layout.sizeOfObject(Long.BYTES + layout.referenceSize())
			+ getStateHeapSizeInBytes(keySizer, valueSizer);
	}

	/**
	 * Returns the heap of the backing state alone — everything {@link #getHeapSizeInBytes} counts except this
	 * decorator's own object. Split out because {@link PersistentTransactionalProducerMap} carries a lambda that a
	 * JOL walk cannot enter, so its own object has to be asserted by a separate, shallow measurement.
	 *
	 * @param keySizer   prices one key, or returns `0` when this map does not own it
	 * @param valueSizer prices one value, or returns `0` when this map does not own it
	 * @return the heap footprint of the backing state in bytes
	 */
	long getStateHeapSizeInBytes(
		@Nonnull ToLongFunction<? super K> keySizer,
		@Nonnull ToLongFunction<? super V> valueSizer
	) {
		// `this.state` directly, and deliberately neither `sealed()` nor `snapshot()`. `sealed()` writes the frozen
		// map back into the field, so measuring a warm-up buffer would turn the next write into an O(N) thaw - a
		// monitoring call that silently degrades the write path it is watching. `snapshot()` publishes nothing but
		// still builds a whole throw-away trie per call. One volatile read, then dispatch on the concrete type,
		// which IS the mode
		return MapHeapSize.sizeOf(this.state, keySizer, valueSizer);
	}

	/* ===========================================================================================
	 * TransactionalLayerCreator / TransactionalLayerProducer contract.
	 * =========================================================================================== */

	/**
	 * {@inheritDoc}
	 *
	 * Seals the state and creates a fresh {@link MapChanges} diff layer backed by the immutable snapshot.
	 * There is no value wrapper because values are plain — no nested transactional state is propagated on
	 * commit.
	 */
	@Nonnull
	@Override
	public MapChanges<K, V> createLayer() {
		return new MapChanges<>(sealed());
	}

	/**
	 * {@inheritDoc}
	 *
	 * Produces the next committed snapshot. When a diff layer exists, its modified and removed keys are
	 * folded onto the sealed base by `O(Δ)` path-copying {@link ChampMap#updated(Object, Object)} /
	 * {@link ChampMap#removed(Object)} calls — never a full `O(N)` rebuild. When no layer exists nothing
	 * changed (values are plain, so there is no per-value state to commit) and the sealed snapshot is
	 * returned as-is.
	 *
	 * The returned {@link ChampMap} is itself a valid `Map` and is meant to be wrapped into the next
	 * {@link PersistentTransactionalMap} by the enclosing producer (adopted sealed, in `O(1)`).
	 */
	@Nonnull
	@Override
	public Map<K, V> createCopyWithMergedTransactionalMemory(
		@Nullable MapChanges<K, V> layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		if (layer == null) {
			// plain values: an absent layer means nothing changed - share the sealed snapshot unchanged
			return sealed();
		}
		// the layer was created over a sealed ChampMap (see createLayer); adopt it as the derivation base
		ChampMap<K, V> result = ChampMap.from(layer.getMapDelegate());
		// apply inserts and updates (modifiedKeys and removedKeys are disjoint by MapChanges construction)
		for (final Entry<K, V> entry : layer.getModifiedKeys().entrySet()) {
			if (entry.getKey() instanceof TransactionalStateProducer) {
				throw new IllegalStateException("Transactional layer producer is not expected to be used as a key!");
			}
			result = result.updated(entry.getKey(), entry.getValue());
		}
		// apply removals
		for (final K key : layer.getRemovedKeys()) {
			result = result.removed(key);
		}
		return result;
	}

	/**
	 * {@inheritDoc}
	 *
	 * Removes this map's diff layer from the transactional memory. Values are plain, so there are no
	 * nested producer layers to release.
	 */
	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
	}

	/* ===========================================================================================
	 * Map read surface.
	 * =========================================================================================== */

	@Override
	public int size() {
		final MapChanges<K, V> layer = getTransactionalMemoryLayerIfExists(this);
		return layer == null ? this.state.size() : layer.size();
	}

	@Override
	public boolean isEmpty() {
		final MapChanges<K, V> layer = getTransactionalMemoryLayerIfExists(this);
		return layer == null ? this.state.isEmpty() : layer.isEmpty();
	}

	@Override
	public boolean containsKey(@Nullable Object key) {
		Assert.notNull(key, "Null keys are not supported in transactional maps!");
		final MapChanges<K, V> layer = getTransactionalMemoryLayerIfExists(this);
		return layer == null ? this.state.containsKey(key) : layer.containsKey(key);
	}

	@Override
	public boolean containsValue(@Nullable Object value) {
		final MapChanges<K, V> layer = getTransactionalMemoryLayerIfExists(this);
		return layer == null ? this.state.containsValue(value) : layer.containsValue(value);
	}

	@Nullable
	@Override
	public V get(@Nullable Object key) {
		Assert.notNull(key, "Null keys are not supported in transactional maps!");
		final MapChanges<K, V> layer = getTransactionalMemoryLayerIfExists(this);
		return layer == null ? this.state.get(key) : layer.get(key);
	}

	@Nonnull
	@Override
	public Set<K> keySet() {
		final MapChanges<K, V> layer = getTransactionalMemoryLayerIfExists(this);
		return layer == null ?
			this.state.keySet() :
			new TransactionalMemoryKeySet<>(layer, this, getTransactionalLayerMaintainer());
	}

	@Nonnull
	@Override
	public Collection<V> values() {
		final MapChanges<K, V> layer = getTransactionalMemoryLayerIfExists(this);
		return layer == null ?
			this.state.values() :
			new TransactionalMemoryValues<>(layer, this, getTransactionalLayerMaintainer());
	}

	@Nonnull
	@Override
	public Set<Entry<K, V>> entrySet() {
		final MapChanges<K, V> layer = getTransactionalMemoryLayerIfExists(this);
		return layer == null ?
			this.state.entrySet() :
			new TransactionalMemoryEntrySet<>(layer, this);
	}

	/* ===========================================================================================
	 * Map write surface.
	 * =========================================================================================== */

	/**
	 * {@inheritDoc}
	 *
	 * In a transaction the mutation is recorded in the diff layer. Without a transaction it is applied to
	 * the thawed {@link HashMap} buffer (`O(1)`), thawing a sealed snapshot once if necessary. Null
	 * keys/values are rejected (fail-fast) — {@link ChampMap} forbids them, so the buffer must too.
	 */
	@Nullable
	@Override
	public V put(K key, V value) {
		final MapChanges<K, V> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			Assert.notNull(key, "Null keys are not supported in transactional maps!");
			Assert.notNull(value, "Null values are not supported in persistent transactional maps!");
			return thawed().put(key, value);
		} else {
			return layer.put(key, value);
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * In a transaction the removal is recorded in the diff layer. Without a transaction it is applied to
	 * the thawed {@link HashMap} buffer (`O(1)`), thawing a sealed snapshot once if necessary.
	 */
	@Nullable
	@Override
	public V remove(@Nullable Object key) {
		Assert.notNull(key, "Null keys are not supported in transactional maps!");
		final MapChanges<K, V> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			return thawed().remove(key);
		} else {
			return layer.remove(key);
		}
	}

	@Override
	public void putAll(@Nonnull Map<? extends K, ? extends V> t) {
		final MapChanges<K, V> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			final Map<K, V> mutable = thawed();
			for (final Entry<? extends K, ? extends V> entry : t.entrySet()) {
				Assert.notNull(entry.getKey(), "Null keys are not supported in transactional maps!");
				Assert.notNull(entry.getValue(), "Null values are not supported in persistent transactional maps!");
				mutable.put(entry.getKey(), entry.getValue());
			}
		} else {
			for (final Entry<? extends K, ? extends V> entry : t.entrySet()) {
				layer.put(entry.getKey(), entry.getValue());
			}
		}
	}

	@Override
	public void clear() {
		final MapChanges<K, V> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			// a fresh mutable buffer keeps subsequent non-transactional warm-up writes O(1)
			this.state = new HashMap<>();
		} else {
			layer.cleanAll(
				ofNullable(getTransactionalLayerMaintainer())
					.orElseThrow(() -> new IllegalStateException("Transactional layer must be present!"))
			);
		}
	}

	/* ===========================================================================================
	 * equals / hashCode / toString / clone.
	 * =========================================================================================== */

	/**
	 * Computes the hash code as the sum of entry hash codes, consistent with the {@link Map} contract.
	 * Uses the transactional view so the result reflects in-transaction state.
	 */
	@Override
	public int hashCode() {
		int h = 0;
		for (final Entry<K, V> entry : entrySet()) {
			h += entry.hashCode();
		}
		return h;
	}

	/**
	 * Compares this map with another object following the {@link Map} contract (same key-value pairs).
	 * Uses the transactional view so it reflects in-transaction state.
	 */
	@Override
	@SuppressWarnings("unchecked")
	public boolean equals(@Nullable Object o) {
		if (o == this) {
			return true;
		}
		if (!(o instanceof Map)) {
			return false;
		}
		final Map<K, V> m = (Map<K, V>) o;
		if (m.size() != size()) {
			return false;
		}
		try {
			for (final Entry<K, V> e : entrySet()) {
				final K key = e.getKey();
				final V value = e.getValue();
				if (value == null) {
					if (!(m.get(key) == null && m.containsKey(key))) {
						return false;
					}
				} else {
					if (!value.equals(m.get(key))) {
						return false;
					}
				}
			}
		} catch (ClassCastException | NullPointerException unused) {
			return false;
		}
		return true;
	}

	@Nonnull
	@Override
	public String toString() {
		final Iterator<Entry<K, V>> i = entrySet().iterator();
		if (!i.hasNext()) {
			return "{}";
		}
		final StringBuilder sb = new StringBuilder(128);
		sb.append('{');
		for (; ; ) {
			final Entry<K, V> e = i.next();
			final K key = e.getKey();
			final V value = e.getValue();
			sb.append(key == this ? "(this Map)" : key);
			sb.append('=');
			sb.append(value == this ? "(this Map)" : value);
			if (!i.hasNext()) {
				return sb.append('}').toString();
			}
			sb.append(',').append(' ');
		}
	}

	/* ===========================================================================================
	 * Java serialization — ChampMap is not Serializable, so the contents are round-tripped through a
	 * plain map and the state restored (sealed) via ChampMap.from on read.
	 * =========================================================================================== */

	@Serial
	private void writeObject(@Nonnull ObjectOutputStream out) throws IOException {
		out.defaultWriteObject();
		out.writeObject(new HashMap<>(this.state));
	}

	@Serial
	@SuppressWarnings("unchecked")
	private void readObject(@Nonnull ObjectInputStream in) throws IOException, ClassNotFoundException {
		in.defaultReadObject();
		final Map<K, V> contents = (Map<K, V>) in.readObject();
		this.state = ChampMap.from(contents);
	}

}
