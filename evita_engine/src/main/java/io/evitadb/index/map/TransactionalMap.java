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

import io.evitadb.core.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerCreator;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.*;
import java.util.function.Function;

import static io.evitadb.core.Transaction.getTransactionalLayerMaintainer;
import static io.evitadb.core.Transaction.getTransactionalMemoryLayerIfExists;
import static java.util.Optional.ofNullable;

/**
 * This class envelopes simple map and makes it transactional. This means, that the map contents can be updated
 * by multiple writers and also multiple readers can read from its original map without spotting the changes made
 * in transactional access. Each transaction is bound to the same thread and different threads doesn't see changes in
 * another threads.
 *
 * If no transaction is opened, changes are applied directly to the delegate map. In such case the class is not thread
 * safe for multiple writers!
 *
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
	 * Don't use this constructor if V implements {@link TransactionalLayerProducer}.
	 * @param mapDelegate original map
	 */
	public TransactionalMap(@Nonnull Map<K, V> mapDelegate) {
		this.mapDelegate = mapDelegate;
		this.valueType = null;
		this.transactionalLayerWrapper = null;
	}

	/**
	 * Use this constructor if V implements TransactionalLayerProducer itself.
	 * @param mapDelegate original map
	 * @param transactionalLayerWrapper the function that wraps result of {@link TransactionalLayerProducer#createCopyWithMergedTransactionalMemory(Object, TransactionalLayerMaintainer)} into a V type
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
	 * Use this constructor if V implements TransactionalLayerProducer itself.
	 * @param mapDelegate original map
	 * @param transactionalLayerWrapper the function that wraps result of {@link TransactionalLayerProducer#createCopyWithMergedTransactionalMemory(Object, TransactionalLayerMaintainer)} into a V type
	 */
	public TransactionalMap(
		@Nonnull Map<K, V> mapDelegate,
		@Nonnull Function<Object, V> transactionalLayerWrapper
	) {
		this.valueType = TransactionalMap.class;
		this.mapDelegate = mapDelegate;
		this.transactionalLayerWrapper = (o) -> (V) transactionalLayerWrapper.apply(o);
	}

	/*
		TransactionalLayerCreator IMPLEMENTATION
	 */

	@Override
	public MapChanges<K, V> createLayer() {
		//noinspection unchecked,rawtypes
		return this.valueType == null ?
			new MapChanges<>(this.mapDelegate) :
			new MapChanges<K, V>(this.mapDelegate, (Class) this.valueType, this.transactionalLayerWrapper);
	}

	@Nonnull
	@Override
	public Map<K, V> createCopyWithMergedTransactionalMemory(MapChanges<K, V> layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
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
						modifiedEntries = new LinkedList<>();
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
	public boolean containsKey(Object key) {
		final MapChanges<K, V> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.mapDelegate.containsKey(key);
		} else {
			return layer.containsKey(key);
		}
	}

	@Override
	public boolean containsValue(Object value) {
		final MapChanges<K, V> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.mapDelegate.containsValue(value);
		} else {
			return layer.containsValue(value);
		}
	}

	@Override
	public V get(Object key) {
		final MapChanges<K, V> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.mapDelegate.get(key);
		} else {
			return layer.get(key);
		}
	}

	@Override
	public V put(K key, V value) {
		final MapChanges<K, V> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			return this.mapDelegate.put(key, value);
		} else {
			return layer.put(key, value);
		}
	}

	@Override
	public V remove(Object key) {
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

	public int hashCode() {
		int h = 0;
		for (Entry<K, V> kvEntry : entrySet()) h += kvEntry.hashCode();
		return h;
	}

	@SuppressWarnings("unchecked")
	public boolean equals(Object o) {
		if (o == this)
			return true;

		if (!(o instanceof Map))
			return false;
		Map<K, V> m = (Map<K, V>) o;
		if (m.size() != size())
			return false;

		try {
			for (Entry<K, V> e : entrySet()) {
				K key = e.getKey();
				V value = e.getValue();
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

	@Override
	public Object clone() throws CloneNotSupportedException {
		@SuppressWarnings("unchecked") final TransactionalMap<K, V> clone = (TransactionalMap<K, V>) super.clone();
		final MapChanges<K, V> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer != null) {
			final MapChanges<K, V> clonedLayer = Transaction.getOrCreateTransactionalMemoryLayer(clone);
			if (clonedLayer != null) {
				clonedLayer.copyState(layer);
			}
		}
		return clone;
	}

	public String toString() {
		final Iterator<Entry<K, V>> i = entrySet().iterator();
		if (!i.hasNext())
			return "{}";

		StringBuilder sb = new StringBuilder(128);
		sb.append('{');
		for (; ; ) {
			Entry<K, V> e = i.next();
			K key = e.getKey();
			V value = e.getValue();
			sb.append(key == this ? "(this Map)" : key);
			sb.append('=');
			sb.append(value == this ? "(this Map)" : value);
			if (!i.hasNext())
				return sb.append('}').toString();
			sb.append(',').append(' ');
		}
	}

	/*
		INTERNALS
	 */

	/**
	 * Iterator implementation that aggregates values from the original map with modified data on transaction level.
	 */
	private static class TransactionalMemoryEntryAbstractIterator<K, V> implements Iterator<Entry<K, V>> {
		private final MapChanges<K, V> layer;
		private final Iterator<Entry<K, V>> layerIt;
		private final Iterator<Entry<K, V>> stateIt;

		private Entry<K, V> currentValue;
		private boolean fetched = true;
		private boolean endOfData;

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
			boolean removedFromTransactionalMemory = !(this.currentValue instanceof TransactionalMemoryEntryWrapper);
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

		Entry<K, V> endOfData() {
			this.endOfData = true;
			return null;
		}

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
				} while (this.layer.containsRemoved(adept.getKey()) || this.layer.containsCreatedOrModified(adept.getKey()));
				return new TransactionalMemoryEntryWrapper<>(this.layer, adept);
			} else {
				return endOfData();
			}
		}

	}

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

		@Override
		public V setValue(V value) {
			return this.layer.registerModifiedKey(this.delegate.getKey(), value);
		}

		@Override
		public int hashCode() {
			final V overwrittenValue = this.layer.getCreatedOrModifiedValue(this.delegate.getKey());
			return overwrittenValue != null ? overwrittenValue.hashCode() : this.delegate.hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			if (!this.delegate.getClass().isInstance(obj)) {
				return false;
			}
			final V overwrittenValue = this.layer.getCreatedOrModifiedValue(this.delegate.getKey());
			return overwrittenValue != null ? overwrittenValue.equals(obj) : this.delegate.equals(obj);
		}

		@Override
		public String toString() {
			final V overwrittenValue = this.layer.getCreatedOrModifiedValue(this.delegate.getKey());
			return overwrittenValue != null ? overwrittenValue.toString() : this.delegate.toString();
		}
	}

	/**
	 * Basic implementation that maps key set in main class. Iterator is delegated to {@link TransactionalMemoryEntryAbstractIterator}.
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
	 * Basic implementation that maps value set in main class. Iterator is delegated to {@link TransactionalMemoryEntryAbstractIterator}.
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
	 * Basic implementation that entry key set in main class. Iterator is delegated to {@link TransactionalMemoryEntryAbstractIterator}.
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
			TransactionalMemoryEntrySet<K, V> that = (TransactionalMemoryEntrySet<K, V>) o;
			return this.layer.equals(that.layer);
		}

		@Override
		public int hashCode() {
			return Objects.hash(super.hashCode(), this.layer);
		}
	}

	private record Tuple<K, V>(@Nonnull K key, @Nonnull V value) {}

}
