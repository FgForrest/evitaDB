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

package io.evitadb.index.list;

import io.evitadb.core.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerCreator;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.exception.GenericEvitaInternalError;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;

import static io.evitadb.core.Transaction.getTransactionalLayerMaintainer;
import static io.evitadb.core.Transaction.getTransactionalMemoryLayerIfExists;
import static java.util.Optional.ofNullable;

/**
 * This class envelopes simple list and makes it transactional. This means, that the list contents can be updated
 * by multiple writers and also multiple readers can read from it's original list without spotting the changes made
 * in transactional access. Each transaction is bound to the same thread and different threads doesn't see changes in
 * another threads.
 *
 * If no transaction is opened, changes are applied directly to the delegate list. In such case the class is not thread
 * safe for multiple writers!
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2017
 */
@ThreadSafe
public class TransactionalList<V> implements List<V>, Serializable, Cloneable, TransactionalLayerCreator<ListChanges<V>>, TransactionalLayerProducer<ListChanges<V>, List<V>> {
	@Serial private static final long serialVersionUID = 7969800648176780425L;
	@Getter private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
	/**
	 * Original immutable list.
	 */
	private final List<V> listDelegate;

	/**
	 * Returns the class type of the generic TransactionalList with the specified key and value types.
	 * This method may be necessary if you need the proper generic class for constructor of other classes.
	 *
	 * @param <V> the type of values in the TransactionalList
	 * @return the Class object representing the type TransactionalList with the specified generic parameters
	 */
	@Nonnull
	public static <V> Class<TransactionalList<V>> genericClass() {
		//noinspection unchecked
		return (Class<TransactionalList<V>>) (Class<?>) TransactionalList.class;
	}

	public TransactionalList(List<V> listDelegate) {
		this.listDelegate = listDelegate;
	}

	/*
		TransactionalLayerCreator IMPLEMENTATION
	 */

	@Override
	public ListChanges<V> createLayer() {
		return new ListChanges<>(this.listDelegate);
	}

	@Nonnull
	@SuppressWarnings({"unchecked", "rawtypes"})
	@Override
	public List<V> createCopyWithMergedTransactionalMemory(@Nullable ListChanges<V> layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		return createCopyWithMergedTransactionalMemory(
			layer,
			value -> (V) transactionalLayer.getStateCopyWithCommittedChanges((TransactionalLayerProducer) value)
		);
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
	}

	/*
		LIST CONTRACT IMPLEMENTATION
	 */

	@Override
	public int size() {
		final ListChanges<V> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.listDelegate.size();
		} else {
			return layer.size();
		}
	}

	@Override
	public boolean isEmpty() {
		final ListChanges<V> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.listDelegate.isEmpty();
		} else {
			return layer.isEmpty();
		}
	}

	@Override
	public boolean contains(Object obj) {
		final ListChanges<V> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.listDelegate.contains(Objects.requireNonNull(obj));
		} else {
			return layer.contains(Objects.requireNonNull(obj));
		}
	}

	@Nonnull
	@Override
	public Iterator<V> iterator() {
		final ListChanges<V> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.listDelegate.iterator();
		} else {
			return new TransactionalMemoryEntryAbstractIterator<>(layer, 0);
		}
	}

	@Nonnull
	@Override
	public Object[] toArray() {
		return toArray(new Object[0]);
	}

	@Nonnull
	@SuppressWarnings("unchecked")
	@Override
	public <T> T[] toArray(@Nonnull T[] array) {
		final ListChanges<V> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return (T[]) this.listDelegate.toArray();
		} else {
			// create copy of the list with all changes applied and convert it to the array
			return createCopyWithMergedTransactionalMemory(layer, value -> (V) value).toArray(array);
		}
	}

	@Override
	public boolean add(V v) {
		// add the element at the end
		this.add(size(), v);
		return true;
	}

	@Override
	public boolean remove(Object o) {
		final ListChanges<V> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			return this.listDelegate.remove(Objects.requireNonNull(o));
		} else {
			return layer.remove(Objects.requireNonNull(o));
		}
	}

	@Override
	public boolean containsAll(Collection<?> c) {
		for (Object e : c) {
			if (!contains(e)) {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean addAll(Collection<? extends V> c) {
		boolean modified = false;
		for (V e : c) {
			add(e);
			modified = true;
		}
		return modified;
	}

	@Override
	public boolean addAll(int index, Collection<? extends V> c) {
		boolean modified = false;
		for (V e : c) {
			add(index++, e);
			modified = true;
		}
		return modified;
	}

	@Override
	public boolean removeAll(@Nonnull Collection<?> collection) {
		boolean modified = false;
		final Iterator<?> it = iterator();
		while (it.hasNext()) {
			if (collection.contains(it.next())) {
				it.remove();
				modified = true;
			}
		}
		return modified;
	}

	@Override
	public boolean retainAll(@Nonnull Collection<?> c) {
		boolean modified = false;
		final Iterator<V> it = iterator();
		while (it.hasNext()) {
			if (!c.contains(it.next())) {
				it.remove();
				modified = true;
			}
		}
		return modified;
	}

	@Override
	public void clear() {
		final ListChanges<V> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			this.listDelegate.clear();
		} else {
			layer.cleanAll(
				ofNullable(getTransactionalLayerMaintainer())
					.orElseThrow(() -> new IllegalStateException("Transactional layer must be present!"))
			);
		}
	}

	@Override
	public V get(int index) {
		final ListChanges<V> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.listDelegate.get(index);
		} else {
			return layer.get(index);
		}
	}

	@Override
	public V set(int index, V element) {
		final ListChanges<V> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			return this.listDelegate.set(index, element);
		} else {
			// remove element and add on the same index new value
			final V result = remove(index);
			add(index, element);
			return result;
		}
	}

	@Override
	public void add(int index, V element) {
		final ListChanges<V> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			this.listDelegate.add(index, Objects.requireNonNull(element));
		} else {
			layer.add(index, Objects.requireNonNull(element));
		}
	}

	@Override
	public V remove(int index) {
		final ListChanges<V> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			return this.listDelegate.remove(index);
		} else {
			return layer.remove(index);
		}
	}

	@Override
	public int indexOf(Object o) {
		// use simple iterator - this won't be much fast
		final ListIterator<V> it = listIterator();
		if (o == null) {
			while (it.hasNext())
				if (it.next() == null)
					return it.previousIndex();
		} else {
			while (it.hasNext())
				if (o.equals(it.next()))
					return it.previousIndex();
		}
		return -1;
	}

	@Override
	public int lastIndexOf(Object o) {
		// use simple iterator - this won't be much fast
		final ListIterator<V> it = listIterator(size());
		if (o == null) {
			while (it.hasPrevious())
				if (it.previous() == null)
					return it.nextIndex();
		} else {
			while (it.hasPrevious())
				if (o.equals(it.previous()))
					return it.nextIndex();
		}
		return -1;
	}

	@Nonnull
	@Override
	public ListIterator<V> listIterator() {
		final ListChanges<V> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.listDelegate.listIterator();
		} else {
			return new TransactionalMemoryEntryAbstractIterator<>(layer, 0);
		}
	}

	@Nonnull
	@Override
	public ListIterator<V> listIterator(int index) {
		final ListChanges<V> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.listDelegate.listIterator(index);
		} else {
			return new TransactionalMemoryEntryAbstractIterator<>(layer, index);
		}
	}

	@Nonnull
	@Override
	public List<V> subList(int fromIndex, int toIndex) {
		final ListChanges<V> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.listDelegate.subList(fromIndex, toIndex);
		} else {
			final List<V> sublist = new ArrayList<>(toIndex - fromIndex);
			// create copy of new list with all changes merged - not entirely fast, but safe
			final Iterator<V> it = iterator();
			int counter = -1;
			while (it.hasNext()) {
				counter++;
				if (counter >= fromIndex && counter < toIndex) {
					sublist.add(it.next());
				}
			}
			return sublist;
		}
	}

	public int hashCode() {
		int hashCode = 1;
		for (V e : this)
			hashCode = 31 * hashCode + (e == null ? 0 : e.hashCode());
		return hashCode;
	}

	public boolean equals(Object o) {
		if (o == this)
			return true;
		if (!(o instanceof List))
			return false;

		ListIterator<V> e1 = listIterator();
		@SuppressWarnings({"unchecked", "rawtypes"}) ListIterator<V> e2 = ((List) o).listIterator();
		while (e1.hasNext() && e2.hasNext()) {
			V o1 = e1.next();
			Object o2 = e2.next();
			if (!(o1 == null ? o2 == null : o1.equals(o2)))
				return false;
		}
		return !(e1.hasNext() || e2.hasNext());
	}

	@Override
	public Object clone() throws CloneNotSupportedException {
		// clone transactional list contents with all recorded changes and create separate transactional memory piece for it
		@SuppressWarnings("unchecked") final TransactionalList<V> clone = (TransactionalList<V>) super.clone();
		final ListChanges<V> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer != null) {
			final ListChanges<V> clonedLayer = Transaction.getOrCreateTransactionalMemoryLayer(clone);
			if (clonedLayer != null) {
				clonedLayer.getRemovedItems().addAll(layer.getRemovedItems());
				clonedLayer.getAddedItems().putAll(layer.getAddedItems());
			}
		}
		return clone;
	}

	public String toString() {
		final Iterator<V> it = iterator();
		if (!it.hasNext())
			return "[]";

		StringBuilder sb = new StringBuilder();
		sb.append('[');
		for (; ; ) {
			V e = it.next();
			sb.append(e == this ? "(this Collection)" : e);
			if (!it.hasNext())
				return sb.append(']').toString();
			sb.append(',').append(' ');
		}
	}

	/**
	 * This method creates copy of the original list with all changes merged into it.
	 */
	@Nonnull
	@SuppressWarnings({"rawtypes"})
	private List<V> createCopyWithMergedTransactionalMemory(@Nullable ListChanges<V> layer, Function<TransactionalLayerProducer<?, ?>, V> transactionLayerExtractor) {
		// create new array list of requested size
		final ArrayList<V> copy = new ArrayList<>(size());
		// iterate original list and copy all values from it
		for (int i = 0; i < this.listDelegate.size(); i++) {
			V value = this.listDelegate.get(i);
			// we need to always create copy - something in the referenced object might have changed
			// even the removed values need to be evaluated (in order to discard them from transactional memory set)
			if (value instanceof TransactionalLayerProducer) {
				value = transactionLayerExtractor.apply((TransactionalLayerProducer) value);
			}
			// except those that were removed
			if (layer == null || !layer.getRemovedItems().contains(i)) {
				copy.add(value);
			}
		}
		// iterate over added items
		if (layer != null && !layer.getAddedItems().isEmpty()) {
			for (Integer updatedItem : layer.getAddedItems().keySet()) {
				V value = layer.getAddedItems().get(updatedItem);
				// we need to always create copy - something in the referenced object might have changed
				if (value instanceof TransactionalLayerProducer) {
					value = transactionLayerExtractor.apply((TransactionalLayerProducer) value);
				}
				// add the element in the result list
				copy.add(updatedItem, value);
			}
		}

		return copy;
	}

	/**
	 * List iterator implementation that supports modifications on the original list.
	 */
	private static class TransactionalMemoryEntryAbstractIterator<V> implements ListIterator<V> {
		private final ListChanges<V> layer;
		private int currentPosition;
		private int previousPosition = -1;

		TransactionalMemoryEntryAbstractIterator(@Nonnull ListChanges<V> layer, int initialIndex) {
			this.currentPosition = initialIndex;
			this.layer = layer;
		}

		@Override
		public boolean hasNext() {
			return this.layer.size() > this.currentPosition;
		}

		@Override
		public V next() {
			if (this.layer.size() > this.currentPosition) {
				this.previousPosition = this.currentPosition;
				return this.layer.get(this.currentPosition++);
			} else {
				throw new NoSuchElementException();
			}
		}

		@Override
		public boolean hasPrevious() {
			return this.currentPosition > 0;
		}

		@Override
		public V previous() {
			if (this.currentPosition <= 0) {
				throw new NoSuchElementException();
			}
			this.previousPosition = this.currentPosition;
			return this.layer.get(--this.currentPosition);
		}

		@Override
		public int nextIndex() {
			return this.currentPosition;
		}

		@Override
		public int previousIndex() {
			return this.currentPosition - 1;
		}

		@Override
		public void remove() {
			if (this.previousPosition > -1) {
				this.currentPosition = this.previousPosition;
				this.layer.remove(this.previousPosition);
			} else {
				throw new GenericEvitaInternalError("Previous position unexpectedly: " + this.previousPosition);
			}
		}

		@Override
		public void set(V v) {
			if (this.currentPosition > 0) {
				final int index = this.currentPosition - 1;
				// remove element and add on the same index new value
				final V result = this.layer.remove(index);
				this.layer.add(index, v);
			} else {
				throw new GenericEvitaInternalError("Current position unexpectedly: " + this.previousPosition);
			}
		}

		@Override
		public void add(V v) {
			this.layer.add(this.currentPosition, v);
		}

	}

}
