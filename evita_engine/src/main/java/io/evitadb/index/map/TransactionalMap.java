/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.index.map;

import io.evitadb.core.Transaction;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.index.transactionalMemory.TransactionalLayerCreator;
import io.evitadb.index.transactionalMemory.TransactionalLayerMaintainer;
import io.evitadb.index.transactionalMemory.TransactionalLayerProducer;
import io.evitadb.index.transactionalMemory.TransactionalObjectVersion;
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

import static io.evitadb.core.Transaction.getTransactionalMemoryLayer;
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
	 * @param transactionalLayerWrapper the function that wraps result of {@link TransactionalLayerProducer#createCopyWithMergedTransactionalMemory(Object, TransactionalLayerMaintainer, Transaction)} into a V type
	 */
	public <S, T extends TransactionalLayerProducer<?, S>> TransactionalMap(
		@Nonnull Map<K, V> mapDelegate,
		@Nonnull Class<T> valueType,
		@Nonnull Function<S, V> transactionalLayerWrapper
	) {
		Assert.isTrue(TransactionalLayerProducer.class.isAssignableFrom(valueType), "Value type is expected to implement TransactionalLayerProducer!");
		this.valueType = valueType;
		this.mapDelegate = mapDelegate;
		//noinspection unchecked
		this.transactionalLayerWrapper = (Function<Object, V>) transactionalLayerWrapper;
	}

	/*
		TransactionalLayerCreator IMPLEMENTATION
	 */

	@Override
	public MapChanges<K, V> createLayer() {
		//noinspection unchecked,rawtypes
		return this.valueType == null ?
			new MapChanges<>(mapDelegate) :
			new MapChanges<K, V>(mapDelegate, (Class)valueType, transactionalLayerWrapper);
	}

	@Nonnull
	@Override
	public Map<K, V> createCopyWithMergedTransactionalMemory(MapChanges<K, V> layer, @Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Transaction transaction) {
		// iterate over inserted or updated keys
		if (layer != null) {
			return layer.createMergedMap(transactionalLayer, transaction);
		} else {
			// iterate original map and copy all values from it
			List<Tuple<K, V>> modifiedEntries = null;
			for (Entry<K, V> entry : mapDelegate.entrySet()) {
				K key = entry.getKey();
				// we need to always create copy - something in the referenced object might have changed
				// even the removed values need to be evaluated (in order to discard them from transactional memory set)
				if (key instanceof TransactionalLayerProducer) {
					throw new IllegalStateException("Transactional layer producer is not expected to be used as a key!");
				}
				V value = entry.getValue();
				if (value instanceof TransactionalLayerProducer) {
					value = transactionalLayerWrapper.apply(
						transactionalLayer.getStateCopyWithCommittedChanges((TransactionalLayerProducer<?, ?>) value, transaction)
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
				return mapDelegate;
			} else {
				final Map<K, V> copy = new HashMap<>(mapDelegate);
				modifiedEntries.forEach(it -> copy.put(it.key(), it.value()));
				return copy;
			}
		}
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		final MapChanges<K, V> changes = transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		ofNullable(changes).ifPresent(it -> it.cleanAll(transactionalLayer));
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
		final MapChanges<K, V> layer = getTransactionalMemoryLayer(this);
		if (layer == null) {
			return this.mapDelegate.put(key, value);
		} else {
			return layer.put(key, value);
		}
	}

	@Override
	public V remove(Object key) {
		final MapChanges<K, V> layer = getTransactionalMemoryLayer(this);
		if (layer == null) {
			return this.mapDelegate.remove(key);
		} else {
			return layer.remove(key);
		}
	}

	@Override
	public void putAll(@Nonnull Map<? extends K, ? extends V> t) {
		final MapChanges<K, V> layer = getTransactionalMemoryLayer(this);
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
		final MapChanges<K, V> layer = getTransactionalMemoryLayer(this);
		if (layer == null) {
			this.mapDelegate.clear();
		} else {
			layer.cleanAll(
				ofNullable(getTransactionalMemoryLayer())
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
			return new TransactionalMemoryKeySet<>(layer, getTransactionalMemoryLayer());
		}
	}

	@Nonnull
	@Override
	public Collection<V> values() {
		final MapChanges<K, V> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.mapDelegate.values();
		} else {
			return new TransactionalMemoryValues<>(layer, getTransactionalMemoryLayer());
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
			final MapChanges<K, V> clonedLayer = getTransactionalMemoryLayer(clone);
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
			if (fetched) {
				currentValue = computeNext();
				fetched = false;
			}
			return !endOfData;
		}

		@Override
		public Entry<K, V> next() {
			if (endOfData) {
				throw new NoSuchElementException();
			}
			if (fetched) {
				currentValue = computeNext();
			}
			fetched = true;
			return currentValue;
		}

		@Override
		public void remove() {
			if (currentValue == null) {
				throw new EvitaInternalError("Value unexpectedly not found!");
			}

			final K key = currentValue.getKey();
			final boolean existing = layer.getMapDelegate().containsKey(key);
			boolean removedFromTransactionalMemory = !(currentValue instanceof TransactionalMemoryEntryWrapper);
			if (removedFromTransactionalMemory) {
				layerIt.remove();
				if (!existing) {
					layer.decreaseCreatedKeyCount();
				}
			}
			if (existing) {
				layer.registerRemovedKey(key);
			}
		}

		Entry<K, V> endOfData() {
			this.endOfData = true;
			return null;
		}

		Entry<K, V> computeNext() {
			if (endOfData) {
				return null;
			}
			if (layerIt.hasNext()) {
				return layerIt.next();
			} else if (stateIt.hasNext()) {
				Entry<K, V> adept;
				do {
					if (stateIt.hasNext()) {
						adept = stateIt.next();
					} else {
						return endOfData();
					}
				} while (layer.containsRemoved(adept.getKey()) || layer.containsCreatedOrModified(adept.getKey()));
				return new TransactionalMemoryEntryWrapper<>(layer, adept);
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
			return delegate.getKey();
		}

		@Override
		public V getValue() {
			return delegate.getValue();
		}

		@Override
		public V setValue(V value) {
			return layer.registerModifiedKey(delegate.getKey(), value);
		}

		@Override
		public int hashCode() {
			final V overwrittenValue = layer.getCreatedOrModifiedValue(delegate.getKey());
			return overwrittenValue != null ? overwrittenValue.hashCode() : delegate.hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			if (!delegate.getClass().isInstance(obj)) {
				return false;
			}
			final V overwrittenValue = layer.getCreatedOrModifiedValue(delegate.getKey());
			return overwrittenValue != null ? overwrittenValue.equals(obj) : delegate.equals(obj);
		}

		@Override
		public String toString() {
			final V overwrittenValue = layer.getCreatedOrModifiedValue(delegate.getKey());
			return overwrittenValue != null ? overwrittenValue.toString() : delegate.toString();
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
				private final Iterator<Entry<K, V>> i = new TransactionalMemoryEntrySet<>(layer).iterator();

				@Override
				public boolean hasNext() {
					return i.hasNext();
				}

				@Override
				public K next() {
					return i.next().getKey();
				}

				@Override
				public void remove() {
					i.remove();
				}
			};
		}

		@Override
		public int size() {
			return layer.size();
		}

		@Override
		public boolean isEmpty() {
			return layer.isEmpty();
		}

		@Override
		public boolean contains(Object k) {
			return layer.containsKey(k);
		}

		@Override
		public void clear() {
			layer.cleanAll(maintainer);
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
				private final Iterator<Entry<K, V>> i = new TransactionalMemoryEntrySet<>(layer).iterator();

				@Override
				public boolean hasNext() {
					return i.hasNext();
				}

				@Override
				public V next() {
					return i.next().getValue();
				}

				@Override
				public void remove() {
					i.remove();
				}
			};
		}

		@Override
		public int size() {
			return layer.size();
		}

		@Override
		public boolean isEmpty() {
			return layer.isEmpty();
		}

		@Override
		public boolean contains(Object v) {
			return layer.containsValue(v);
		}

		@Override
		public void clear() {
			layer.cleanAll(maintainer);
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
			return new TransactionalMemoryEntryAbstractIterator<>(layer);
		}

		@Override
		public int size() {
			return layer.size();
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			if (!super.equals(o)) return false;
			@SuppressWarnings("unchecked")
			TransactionalMemoryEntrySet<K, V> that = (TransactionalMemoryEntrySet<K, V>) o;
			return layer.equals(that.layer);
		}

		@Override
		public int hashCode() {
			return Objects.hash(super.hashCode(), layer);
		}
	}

	private record Tuple<K, V>(@Nonnull K key, @Nonnull V value) {}

}
