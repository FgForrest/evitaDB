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

package io.evitadb.index.map;

import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerCreator;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.*;
import java.util.function.Function;

import static io.evitadb.core.transaction.Transaction.getTransactionalLayerMaintainer;
import static io.evitadb.core.transaction.Transaction.getTransactionalMemoryLayerIfExists;
import static java.util.Optional.ofNullable;

/**
 * Transactional decorator for {@link Map} that participates in the Software Transactional Memory (STM) framework.
 * When a transaction is active, all mutations are recorded in a {@link MapChanges} diff layer that is visible only
 * to the owning transaction; concurrent readers observe the original, unmodified delegate map. On commit the diff
 * layer is merged into a new immutable snapshot; on rollback it is simply discarded.
 *
 * If no transaction is open, mutations fall through directly to the underlying delegate map. In that mode the class
 * is **not** thread-safe for concurrent writers.
 *
 * When the value type `V` itself implements {@link TransactionalLayerProducer}, the map propagates commit/rollback
 * into each value so that nested transactional structures are handled correctly.
 *
 * @param <K> key type
 * @param <V> value type
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2017
 */
@ThreadSafe
public class TransactionalMap<K, V> implements Map<K, V>,
	Serializable,
	Cloneable,
	TransactionalLayerCreator<MapChanges<K, V>>,
	TransactionalLayerProducer<MapChanges<K, V>, Map<K, V>>
{
	@Serial private static final long serialVersionUID = 1111377458028103813L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	private final Map<K, V> mapDelegate;
	private final Class<?> valueType;
	private final Function<Object, V> transactionalLayerWrapper;

	/**
	 * Creates a transactional map whose values are plain (non-transactional) objects. Do **not** use this
	 * constructor when `V` implements {@link TransactionalLayerProducer} — use one of the overloaded
	 * constructors that accept a wrapper function instead.
	 *
	 * @param mapDelegate the backing map to decorate
	 */
	public TransactionalMap(@Nonnull Map<K, V> mapDelegate) {
		this.mapDelegate = mapDelegate;
		this.valueType = null;
		this.transactionalLayerWrapper = null;
	}

	/**
	 * Creates a transactional map whose values implement {@link TransactionalLayerProducer}. During commit,
	 * each value is merged via its own STM layer and the result is converted back to `V` using the supplied
	 * wrapper function.
	 *
	 * @param mapDelegate              the backing map to decorate
	 * @param valueType                concrete class of the transactional value producer
	 * @param transactionalLayerWrapper function that converts the merged state produced by
	 *                                  {@link TransactionalLayerProducer#createCopyWithMergedTransactionalMemory(Object, TransactionalLayerMaintainer)}
	 *                                  back into type `V`
	 * @param <S> the state type produced by the transactional layer producer
	 * @param <T> the concrete type of the transactional layer producer
	 */
	public <S, T extends TransactionalLayerProducer<?, S>> TransactionalMap(
		@Nonnull Map<K, V> mapDelegate,
		@Nonnull Class<T> valueType,
		@Nonnull Function<S, V> transactionalLayerWrapper
	) {
		Assert.isTrue(
			TransactionalLayerProducer.class.isAssignableFrom(valueType),
			"Value type is expected to implement TransactionalLayerProducer!"
		);
		this.valueType = valueType;
		this.mapDelegate = mapDelegate;
		//noinspection unchecked
		this.transactionalLayerWrapper = (Function<Object, V>) transactionalLayerWrapper;
	}

	/**
	 * Creates a transactional map whose values are themselves transactional maps (or other producers whose
	 * concrete type is not statically known). The wrapper function receives the raw merged state object and
	 * must cast/convert it to `V`.
	 *
	 * @param mapDelegate              the backing map to decorate
	 * @param transactionalLayerWrapper function that converts the raw merged state into type `V`
	 */
	public TransactionalMap(
		@Nonnull Map<K, V> mapDelegate,
		@Nonnull Function<Object, V> transactionalLayerWrapper
	) {
		this.valueType = TransactionalMap.class;
		this.mapDelegate = mapDelegate;
		this.transactionalLayerWrapper = (o) -> (V) transactionalLayerWrapper.apply(o);
	}

	/**
	 * {@inheritDoc}
	 *
	 * Creates a new {@link MapChanges} diff layer. If the value type is a {@link TransactionalLayerProducer},
	 * the layer is configured with the wrapper function so that nested producers are handled on commit.
	 */
	@Nonnull
	@Override
	public MapChanges<K, V> createLayer() {
		//noinspection unchecked,rawtypes
		return this.valueType == null ?
			new MapChanges<>(this.mapDelegate) :
			new MapChanges<K, V>(this.mapDelegate, (Class) this.valueType, this.transactionalLayerWrapper);
	}

	/**
	 * {@inheritDoc}
	 *
	 * Produces a new map snapshot that includes all committed changes. When a diff layer is present its
	 * mutations are merged via {@link MapChanges#createMergedMap(TransactionalLayerMaintainer)}. When
	 * no layer exists but values are transactional producers, each value is individually committed so
	 * that nested transactional state is not lost.
	 */
	@Nonnull
	@Override
	public Map<K, V> createCopyWithMergedTransactionalMemory(
		@Nullable MapChanges<K, V> layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		// iterate over inserted or updated keys
		if (layer != null) {
			return layer.createMergedMap(transactionalLayer);
		} else if (this.valueType == null || TransactionalLayerProducer.class.isAssignableFrom(this.valueType)) {
			// iterate original map and copy all values from it
			List<Tuple<K, V>> modifiedEntries = null;
			for (Entry<K, V> entry : this.mapDelegate.entrySet()) {
				K key = entry.getKey();
				// we need to always create copy - something in the referenced object might have changed
				// even the removed values need to be evaluated (in order to discard them from transactional memory set)
				if (key instanceof TransactionalLayerProducer) {
					throw new IllegalStateException("Transactional layer producer is not expected to be used as a key!");
				}
				V value = entry.getValue();
				if (value instanceof TransactionalLayerProducer<?,?> transactionalLayerProducer) {
					value = this.transactionalLayerWrapper.apply(
						transactionalLayer.getStateCopyWithCommittedChanges(transactionalLayerProducer)
					);
				}

				if (key != entry.getKey() || value != entry.getValue()) {
					if (modifiedEntries == null) {
						modifiedEntries = new ArrayList<>();
					}
					modifiedEntries.add(new Tuple<>(key, value));
				}
			}
			if (modifiedEntries == null) {
				return this.mapDelegate;
			} else {
				final Map<K, V> copy = new HashMap<>(this.mapDelegate);
				modifiedEntries.forEach(it -> copy.put(it.key(), it.value()));
				return copy;
			}
		} else {
			return this.mapDelegate;
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * Removes this map's diff layer from the transactional memory and propagates the removal into every
	 * value that is itself a {@link TransactionalLayerProducer}, ensuring full cleanup on rollback.
	 */
	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		final MapChanges<K, V> changes = transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		ofNullable(changes).ifPresent(it -> it.cleanAll(transactionalLayer));
		for (Entry<K, V> entry : this.mapDelegate.entrySet()) {
			V value = entry.getValue();
			if (value instanceof TransactionalLayerProducer<?,?> transactionalLayerProducer) {
				transactionalLayerProducer.removeLayer(transactionalLayer);
			}
		}
	}

	/*
		MAP CONTRACT IMPLEMENTATION
	 */

	@Override
	public int size() {
		final MapChanges<K, V> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.mapDelegate.size();
		} else {
			return layer.size();
		}
	}

	@Override
	public boolean isEmpty() {
		final MapChanges<K, V> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.mapDelegate.isEmpty();
		} else {
			return layer.isEmpty();
		}
	}

	@Override
	public boolean containsKey(@Nullable Object key) {
		Assert.notNull(key, "Null keys are not supported in transactional maps!");
		final MapChanges<K, V> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.mapDelegate.containsKey(key);
		} else {
			return layer.containsKey(key);
		}
	}

	@Override
	public boolean containsValue(@Nullable Object value) {
		final MapChanges<K, V> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.mapDelegate.containsValue(value);
		} else {
			return layer.containsValue(value);
		}
	}

	@Nullable
	@Override
	public V get(@Nullable Object key) {
		Assert.notNull(key, "Null keys are not supported in transactional maps!");
		final MapChanges<K, V> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.mapDelegate.get(key);
		} else {
			return layer.get(key);
		}
	}

	@Nullable
	@Override
	public V put(K key, @Nullable V value) {
		final MapChanges<K, V> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			return this.mapDelegate.put(key, value);
		} else {
			return layer.put(key, value);
		}
	}

	@Nullable
	@Override
	public V remove(@Nullable Object key) {
		Assert.notNull(key, "Null keys are not supported in transactional maps!");
		final MapChanges<K, V> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			return this.mapDelegate.remove(key);
		} else {
			return layer.remove(key);
		}
	}

	@Override
	public void putAll(@Nonnull Map<? extends K, ? extends V> t) {
		final MapChanges<K, V> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			this.mapDelegate.putAll(t);
		} else {
			for (Entry<? extends K, ? extends V> entry : t.entrySet()) {
				layer.put(entry.getKey(), entry.getValue());
			}
		}
	}

	@Override
	public void clear() {
		final MapChanges<K, V> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			this.mapDelegate.clear();
		} else {
			layer.cleanAll(
				ofNullable(getTransactionalLayerMaintainer())
					.orElseThrow(() -> new IllegalStateException("Transactional layer must be present!"))
			);
		}
	}

	@Nonnull
	@Override
	public Set<K> keySet() {
		final MapChanges<K, V> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.mapDelegate.keySet();
		} else {
			return new TransactionalMemoryKeySet<>(layer, getTransactionalLayerMaintainer());
		}
	}

	@Nonnull
	@Override
	public Collection<V> values() {
		final MapChanges<K, V> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.mapDelegate.values();
		} else {
			return new TransactionalMemoryValues<>(layer, getTransactionalLayerMaintainer());
		}
	}

	@Nonnull
	@Override
	public Set<Entry<K, V>> entrySet() {
		final MapChanges<K, V> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.mapDelegate.entrySet();
		} else {
			return new TransactionalMemoryEntrySet<>(layer);
		}
	}

	/**
	 * Computes the hash code as the sum of hash codes of all entries, consistent with the {@link Map} contract.
	 * Uses the transactional entry set so the result reflects in-transaction state.
	 */
	@Override
	public int hashCode() {
		int h = 0;
		for (final Entry<K, V> kvEntry : entrySet()) {
			h += kvEntry.hashCode();
		}
		return h;
	}

	/**
	 * Compares this map with another object for equality following the {@link Map} contract. Two maps are equal
	 * when they contain the same key-value pairs. The comparison uses the transactional entry set so it
	 * reflects in-transaction state.
	 */
	@Override
	@SuppressWarnings("unchecked")
	public boolean equals(@Nullable Object o) {
		if (o == this)
			return true;

		if (!(o instanceof Map))
			return false;
		final Map<K, V> m = (Map<K, V>) o;
		if (m.size() != size())
			return false;

		try {
			for (final Entry<K, V> e : entrySet()) {
				final K key = e.getKey();
				final V value = e.getValue();
				if (value == null) {
					if (!(m.get(key) == null && m.containsKey(key)))
						return false;
				} else {
					if (!value.equals(m.get(key)))
						return false;
				}
			}
		} catch (ClassCastException | NullPointerException unused) {
			return false;
		}

		return true;
	}

	/**
	 * Creates a shallow clone of this transactional map. If an active transaction exists, the current diff
	 * layer state is copied into the clone's own layer so that both instances share the same logical snapshot
	 * but can diverge independently afterward.
	 */
	@Override
	public Object clone() throws CloneNotSupportedException {
		@SuppressWarnings("unchecked") final TransactionalMap<K, V> clone = (TransactionalMap<K, V>) super.clone();
		final MapChanges<K, V> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer != null) {
			final MapChanges<K, V> clonedLayer = Transaction.getOrCreateTransactionalMemoryLayer(clone);
			if (clonedLayer != null) {
				layer.copyState(clonedLayer);
			}
		}
		return clone;
	}

	/**
	 * Returns a string representation of this map in `{key=value, ...}` format, reflecting the transactional
	 * view when a transaction is active.
	 */
	@Nonnull
	@Override
	public String toString() {
		final Iterator<Entry<K, V>> i = entrySet().iterator();
		if (!i.hasNext())
			return "{}";

		final StringBuilder sb = new StringBuilder(128);
		sb.append('{');
		for (; ; ) {
			final Entry<K, V> e = i.next();
			final K key = e.getKey();
			final V value = e.getValue();
			sb.append(key == this ? "(this Map)" : key);
			sb.append('=');
			sb.append(value == this ? "(this Map)" : value);
			if (!i.hasNext())
				return sb.append('}').toString();
			sb.append(',').append(' ');
		}
	}

	/**
	 * Lazy-prefetch iterator that merges entries from the transactional diff layer with the original delegate
	 * map. Created/modified entries from the layer are yielded first, followed by delegate entries that have
	 * not been removed or overwritten. Removal via {@link #remove()} is propagated back into the diff layer.
	 *
	 * @param <K> key type
	 * @param <V> value type
	 */
	private static class TransactionalMemoryEntryAbstractIterator<K, V> implements Iterator<Entry<K, V>> {
		private final MapChanges<K, V> layer;
		private final Iterator<Entry<K, V>> layerIt;
		private final Iterator<Entry<K, V>> stateIt;

		@Nullable private Entry<K, V> currentValue;
		private boolean fetched = true;
		private boolean endOfData;

		/**
		 * Creates a new iterator over the merged view of the given diff layer and its delegate map.
		 *
		 * @param layer the transactional diff layer to iterate over
		 */
		TransactionalMemoryEntryAbstractIterator(@Nonnull MapChanges<K, V> layer) {
			this.layer = layer;
			this.layerIt = layer.getCreatedOrModifiedValuesIterator();
			this.stateIt = layer.getMapDelegate().entrySet().iterator();
		}

		@Override
		public boolean hasNext() {
			if (this.fetched) {
				this.currentValue = computeNext();
				this.fetched = false;
			}
			return !this.endOfData;
		}

		@Nullable
		@Override
		public Entry<K, V> next() {
			if (this.endOfData) {
				throw new NoSuchElementException();
			}
			if (this.fetched) {
				this.currentValue = computeNext();
			}
			this.fetched = true;
			return this.currentValue;
		}

		@Override
		public void remove() {
			if (this.currentValue == null) {
				throw new GenericEvitaInternalError("Value unexpectedly not found!");
			}

			final K key = this.currentValue.getKey();
			final boolean existing = this.layer.getMapDelegate().containsKey(key);
			final boolean removedFromTransactionalMemory = !(this.currentValue instanceof TransactionalMemoryEntryWrapper);
			if (removedFromTransactionalMemory) {
				this.layerIt.remove();
				if (!existing) {
					this.layer.decreaseCreatedKeyCount();
				}
			}
			if (existing) {
				this.layer.registerRemovedKey(key);
			}
		}

		/**
		 * Marks this iterator as exhausted and returns `null`. All subsequent calls to
		 * {@link #hasNext()} will return `false`.
		 *
		 * @return always `null`
		 */
		@Nullable
		Entry<K, V> endOfData() {
			this.endOfData = true;
			return null;
		}

		/**
		 * Advances to the next entry in the merged view. Layer entries (created/modified) are returned first,
		 * then delegate entries that are neither removed nor overwritten in the layer. Returns `null` and
		 * sets the end-of-data flag when all entries have been exhausted.
		 *
		 * @return the next entry, or `null` if no more entries remain
		 */
		@Nullable
		Entry<K, V> computeNext() {
			if (this.endOfData) {
				return null;
			}
			if (this.layerIt.hasNext()) {
				return this.layerIt.next();
			} else if (this.stateIt.hasNext()) {
				Entry<K, V> adept;
				do {
					if (this.stateIt.hasNext()) {
						adept = this.stateIt.next();
					} else {
						return endOfData();
					}
				} while (this.layer.containsRemoved(adept.getKey()) ||
				this.layer.containsCreatedOrModified(adept.getKey()));
				return new TransactionalMemoryEntryWrapper<>(this.layer, adept);
			} else {
				return endOfData();
			}
		}

	}

	/**
	 * Wraps a delegate map entry and proxies write operations through the transactional diff layer,
	 * so that modifications via {@link Entry#setValue(Object)} are tracked in the transaction.
	 */
	@RequiredArgsConstructor
	private static class TransactionalMemoryEntryWrapper<K, V> implements Entry<K, V> {
		private final MapChanges<K, V> layer;
		private final Entry<K, V> delegate;

		@Override
		public K getKey() {
			return this.delegate.getKey();
		}

		@Override
		public V getValue() {
			return this.delegate.getValue();
		}

		@Nullable
		@Override
		public V setValue(V value) {
			return this.layer.registerModifiedKey(this.delegate.getKey(), value);
		}

		@Override
		public int hashCode() {
			final K key = this.delegate.getKey();
			final V overwrittenValue = this.layer.getCreatedOrModifiedValue(key);
			if (overwrittenValue != null) {
				return Objects.hashCode(key) ^ Objects.hashCode(overwrittenValue);
			}
			return this.delegate.hashCode();
		}

		@Override
		public boolean equals(@Nullable Object obj) {
			if (!(obj instanceof Entry<?, ?> other)) {
				return false;
			}
			final K key = this.delegate.getKey();
			final V overwrittenValue = this.layer.getCreatedOrModifiedValue(key);
			final V effectiveValue = overwrittenValue != null ? overwrittenValue : this.delegate.getValue();
			return Objects.equals(key, other.getKey()) && Objects.equals(effectiveValue, other.getValue());
		}

		@Override
		public String toString() {
			final V overwrittenValue = this.layer.getCreatedOrModifiedValue(this.delegate.getKey());
			return overwrittenValue != null ? overwrittenValue.toString() : this.delegate.toString();
		}
	}

	/**
	 * Represents the key set view of a transactional map. Iterator is delegated to
	 * {@link TransactionalMemoryEntryAbstractIterator}.
	 */
	@RequiredArgsConstructor
	private static class TransactionalMemoryKeySet<K, V> extends AbstractSet<K> {
		private final MapChanges<K, V> layer;
		private final TransactionalLayerMaintainer maintainer;

		@Nonnull
		@Override
		public Iterator<K> iterator() {
			return new Iterator<>() {
				private final Iterator<Entry<K, V>> i = new TransactionalMemoryEntrySet<>(TransactionalMemoryKeySet.this.layer).iterator();

				@Override
				public boolean hasNext() {
					return this.i.hasNext();
				}

				@Override
				public K next() {
					return this.i.next().getKey();
				}

				@Override
				public void remove() {
					this.i.remove();
				}
			};
		}

		@Override
		public int size() {
			return this.layer.size();
		}

		@Override
		public boolean isEmpty() {
			return this.layer.isEmpty();
		}

		@Override
		public boolean contains(Object k) {
			return this.layer.containsKey(k);
		}

		@Override
		public void clear() {
			this.layer.cleanAll(this.maintainer);
		}
	}

	/**
	 * Represents the values collection view of a transactional map. Iterator is delegated to
	 * {@link TransactionalMemoryEntryAbstractIterator}.
	 */
	@RequiredArgsConstructor
	private static class TransactionalMemoryValues<K, V> extends AbstractCollection<V> {
		private final MapChanges<K, V> layer;
		private final TransactionalLayerMaintainer maintainer;

		@Nonnull
		@Override
		public Iterator<V> iterator() {
			return new Iterator<>() {
				private final Iterator<Entry<K, V>> i = new TransactionalMemoryEntrySet<>(TransactionalMemoryValues.this.layer).iterator();

				@Override
				public boolean hasNext() {
					return this.i.hasNext();
				}

				@Override
				public V next() {
					return this.i.next().getValue();
				}

				@Override
				public void remove() {
					this.i.remove();
				}
			};
		}

		@Override
		public int size() {
			return this.layer.size();
		}

		@Override
		public boolean isEmpty() {
			return this.layer.isEmpty();
		}

		@Override
		public boolean contains(Object v) {
			return this.layer.containsValue(v);
		}

		@Override
		public void clear() {
			this.layer.cleanAll(this.maintainer);
		}

	}

	/**
	 * Represents the entry set view of a transactional map. Iterator is delegated to
	 * {@link TransactionalMemoryEntryAbstractIterator}.
	 */
	private static class TransactionalMemoryEntrySet<K, V> extends AbstractSet<Entry<K, V>> {
		private final MapChanges<K, V> layer;

		public TransactionalMemoryEntrySet(@Nonnull MapChanges<K, V> layer) {
			this.layer = layer;
		}

		@Nonnull
		@Override
		public Iterator<Entry<K, V>> iterator() {
			return new TransactionalMemoryEntryAbstractIterator<>(this.layer);
		}

		@Override
		public int size() {
			return this.layer.size();
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			if (!super.equals(o)) return false;
			@SuppressWarnings("unchecked")
			final TransactionalMemoryEntrySet<K, V> that = (TransactionalMemoryEntrySet<K, V>) o;
			return this.layer.equals(that.layer);
		}

		@Override
		public int hashCode() {
			return 31 * super.hashCode() + this.layer.hashCode();
		}
	}

	/**
	 * Lightweight key-value pair used internally to collect modified entries during
	 * {@link #createCopyWithMergedTransactionalMemory(MapChanges, TransactionalLayerMaintainer)}.
	 *
	 * @param key   the map key
	 * @param value the (possibly merged) map value
	 * @param <K>   key type
	 * @param <V>   value type
	 */
	private record Tuple<K, V>(@Nonnull K key, @Nonnull V value) {}

}
