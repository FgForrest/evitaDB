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

package io.evitadb.index.set;

import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerCreator;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

import static io.evitadb.core.transaction.Transaction.getTransactionalMemoryLayerIfExists;

/**
 * This class envelops a set and makes it transactional. This means, that
 * the set contents can be updated by multiple writers and also multiple
 * readers can read from its original set without spotting the changes
 * made in transactional access. Each transaction is bound to the same
 * thread and different threads don't see changes in other threads.
 *
 * If no transaction is opened, changes are applied directly to the
 * delegate set. In such case the class is not thread safe for multiple
 * writers!
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2017
 */
@ThreadSafe
public class TransactionalSet<K> implements Set<K>, Serializable, Cloneable,
	TransactionalLayerCreator<SetChanges<K>>, TransactionalLayerProducer<SetChanges<K>, Set<K>> {
	@Serial private static final long serialVersionUID = 6678551073928034251L;
	private static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	private final Set<K> setDelegate;

	/**
	 * Creates a new transactional wrapper around the given delegate set.
	 *
	 * @param setDelegate the underlying set to wrap
	 */
	public TransactionalSet(@Nonnull Set<K> setDelegate) {
		this.setDelegate = setDelegate;
	}

	/*
		TransactionalLayerCreator IMPLEMENTATION
	 */

	@Override
	public SetChanges<K> createLayer() {
		return new SetChanges<>(this.setDelegate);
	}

	@Nonnull
	@Override
	public Set<K> createCopyWithMergedTransactionalMemory(
		@Nullable SetChanges<K> layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// iterate over inserted or updated keys
		if (layer != null) {
			return layer.createMergedSet(transactionalLayer);
		} else {
			// iterate original set and copy all values from it
			List<K> oldEntries = null;
			List<K> newEntries = null;
			for (K entry : this.setDelegate) {
				// we need to always create copy - something in the
				// referenced object might have changed; even the removed
				// values need to be evaluated (in order to discard them
				// from transactional memory set)
				final K transformedEntry;
				if (entry instanceof TransactionalLayerProducer) {
					//noinspection unchecked
					transformedEntry = (K) transactionalLayer.getStateCopyWithCommittedChanges(
						(TransactionalLayerProducer<?, ?>) entry
					);
				} else {
					transformedEntry = entry;
				}

				if (entry != transformedEntry) {
					if (oldEntries == null) {
						oldEntries = new ArrayList<>();
						newEntries = new ArrayList<>();
					}
					oldEntries.add(entry);
					newEntries.add(transformedEntry);
				}
			}
			if (oldEntries == null) {
				return this.setDelegate;
			} else {
				final Set<K> copy = new HashSet<>(this.setDelegate);
				oldEntries.forEach(copy::remove);
				copy.addAll(newEntries);
				return copy;
			}
		}
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
	}

	@Override
	public int size() {
		final SetChanges<K> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.setDelegate.size();
		} else {
			return layer.size();
		}
	}

	@Override
	public boolean isEmpty() {
		final SetChanges<K> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.setDelegate.isEmpty();
		} else {
			return layer.isEmpty();
		}
	}

	@Override
	public boolean contains(@Nullable Object o) {
		Assert.notNull(o, "Null keys are not supported in transactional sets!");
		final SetChanges<K> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.setDelegate.contains(o);
		} else {
			return layer.contains(o);
		}
	}

	@Nonnull
	@Override
	public Iterator<K> iterator() {
		final SetChanges<K> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.setDelegate.iterator();
		} else {
			return new TransactionalMemorySetIterator<>(this.setDelegate, layer);
		}
	}

	@Nonnull
	@Override
	public Object[] toArray() {
		final SetChanges<K> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.setDelegate.toArray();
		} else {
			return layer.toArray(EMPTY_OBJECT_ARRAY);
		}
	}

	@Nonnull
	@Override
	public <T> T[] toArray(@Nonnull T[] a) {
		final SetChanges<K> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.setDelegate.toArray(a);
		} else {
			return layer.toArray(a);
		}
	}

	@Override
	public boolean add(K key) {
		final SetChanges<K> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			return this.setDelegate.add(key);
		} else {
			return layer.put(key);
		}
	}

	@Override
	public boolean remove(@Nullable Object key) {
		Assert.notNull(key, "Null keys are not supported in transactional sets!");
		final SetChanges<K> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			return this.setDelegate.remove(key);
		} else {
			return layer.remove(key);
		}
	}

	@Override
	public boolean containsAll(@Nonnull Collection<?> c) {
		final SetChanges<K> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.setDelegate.containsAll(c);
		} else {
			for (Object element : c) {
				if (!layer.contains(element)) {
					return false;
				}
			}
			return true;
		}
	}

	@Override
	public boolean addAll(@Nonnull Collection<? extends K> c) {
		final SetChanges<K> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			return this.setDelegate.addAll(c);
		} else {
			boolean modified = false;
			for (K key : c) {
				modified |= layer.put(key);
			}
			return modified;
		}
	}

	@Override
	public boolean retainAll(@Nonnull Collection<?> c) {
		final SetChanges<K> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			return this.setDelegate.retainAll(c);
		} else {
			Objects.requireNonNull(c);
			boolean modified = false;
			final Iterator<?> it = iterator();
			while (it.hasNext()) {
				if (!c.contains(it.next())) {
					it.remove();
					modified = true;
				}
			}
			return modified;
		}
	}

	@Override
	public boolean removeAll(@Nonnull Collection<?> c) {
		final SetChanges<K> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			return this.setDelegate.removeAll(c);
		} else {
			Objects.requireNonNull(c);
			boolean modified = false;
			final Iterator<?> it = iterator();
			while (it.hasNext()) {
				if (c.contains(it.next())) {
					it.remove();
					modified = true;
				}
			}
			return modified;
		}
	}

	@Override
	public void clear() {
		final SetChanges<K> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			this.setDelegate.clear();
		} else {
			layer.clearAll();
		}
	}

	/**
	 * Computes the hash code as the sum of the hash codes of all elements,
	 * consistent with the `Set.hashCode()` contract.
	 */
	@Override
	public int hashCode() {
		int h = 0;
		for (K key : this) {
			h += key.hashCode();
		}
		return h;
	}

	/**
	 * Compares this set with the specified object for equality.
	 */
	@Override
	public boolean equals(@Nullable Object o) {
		if (o == this)
			return true;

		if (!(o instanceof Set))
			return false;
		@SuppressWarnings("unchecked") final Set<K> other = (Set<K>) o;
		if (other.size() != size())
			return false;

		try {
			for (K key : this) {
				if (!other.contains(key))
					return false;
			}
		} catch (ClassCastException | NullPointerException unused) {
			return false;
		}

		return true;
	}

	@Nonnull
	@Override
	public Object clone() throws CloneNotSupportedException {
		@SuppressWarnings("unchecked") final TransactionalSet<K> clone = (TransactionalSet<K>) super.clone();
		final SetChanges<K> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer != null) {
			final SetChanges<K> clonedLayer = Transaction.getOrCreateTransactionalMemoryLayer(clone);
			if (clonedLayer != null) {
				layer.copyState(clonedLayer);
			}
		}
		return clone;
	}

	/**
	 * Returns a string representation of this set in the format
	 * `{e1, e2, ...}`.
	 */
	@Override
	public String toString() {
		final Iterator<K> i = iterator();
		if (!i.hasNext())
			return "{}";

		final StringBuilder sb = new StringBuilder(64);
		sb.append('{');
		for (; ; ) {
			final K key = i.next();
			sb.append(key == this ? "(this Set)" : key);
			if (!i.hasNext())
				return sb.append('}').toString();
			sb.append(',').append(' ');
		}
	}

	/**
	 * Iterator that merges elements from the delegate set with changes
	 * from the transactional layer. Created keys are iterated first,
	 * followed by non-removed delegate keys that are not duplicated
	 * in the created set.
	 */
	private static class TransactionalMemorySetIterator<K> implements Iterator<K> {

		private final SetChanges<K> layer;
		private final Iterator<K> layerIt;
		private final Iterator<K> stateIt;

		@Nullable private K currentValue;
		private boolean fetched = true;
		private boolean endOfData;

		/**
		 * Creates a new iterator merging the delegate and layer state.
		 *
		 * @param setDelegate the underlying delegate set
		 * @param layer       the transactional diff layer
		 */
		TransactionalMemorySetIterator(@Nonnull Set<K> setDelegate, @Nonnull SetChanges<K> layer) {
			this.layer = layer;
			this.layerIt = layer.getCreatedKeys().iterator();
			this.stateIt = setDelegate.iterator();
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
		public K next() {
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

			final K key = this.currentValue;
			final boolean existing = this.layer.getSetDelegate().contains(key);
			final boolean removedFromTransactionalMemory = this.layer.getCreatedKeys().contains(key);
			if (removedFromTransactionalMemory) {
				this.layerIt.remove();
				if (!existing) {
					this.layer.removeCreatedKey(key);
				}
			}
			if (existing) {
				this.layer.registerRemovedKey(key);
			}
		}

		/**
		 * Marks this iterator as exhausted and returns null.
		 */
		@Nullable
		K endOfData() {
			this.endOfData = true;
			return null;
		}

		/**
		 * Computes the next element by first draining created keys from
		 * the layer, then iterating the delegate while skipping removed
		 * and already-created keys.
		 */
		@Nullable
		K computeNext() {
			if (this.endOfData) {
				return null;
			}
			if (this.layerIt.hasNext()) {
				return this.layerIt.next();
			} else if (this.stateIt.hasNext()) {
				K adept;
				do {
					if (this.stateIt.hasNext()) {
						adept = this.stateIt.next();
					} else {
						return endOfData();
					}
				} while (this.layer.containsRemoved(adept) || this.layer.containsCreated(adept));
				return adept;
			} else {
				return endOfData();
			}
		}

	}

}
