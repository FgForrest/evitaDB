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

import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerCreator;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalStateProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.exception.GenericEvitaInternalError;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;

import static io.evitadb.core.transaction.Transaction.getTransactionalLayerMaintainer;
import static io.evitadb.core.transaction.Transaction.getTransactionalMemoryLayerIfExists;
import static java.util.Optional.ofNullable;

/**
 * This class envelops a list and makes it transactional. This means, that the list contents can be updated
 * by multiple writers and also multiple readers can read from its original list without spotting the changes made
 * in transactional access. Each transaction is bound to the same thread and different threads don't see changes in
 * other threads.
 *
 * If no transaction is opened, changes are applied directly to the delegate list. In such case the class is not thread
 * safe for multiple writers!
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2017
 */
@ThreadSafe
public class TransactionalList<V> implements
	List<V>,
	Serializable,
	TransactionalLayerCreator<ListChanges<V>>,
	TransactionalLayerProducer<ListChanges<V>, List<V>>
{
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

	/**
	 * Creates a new transactional wrapper around the given list delegate.
	 *
	 * @param listDelegate the underlying list to wrap; changes recorded in transactional memory are applied on top of it
	 */
	public TransactionalList(@Nonnull List<V> listDelegate) {
		this.listDelegate = listDelegate;
	}

	/*
		TransactionalLayerCreator IMPLEMENTATION
	 */

	/**
	 * Creates a new transactional diff layer capturing changes made within a single transaction.
	 */
	@Nonnull
	@Override
	public ListChanges<V> createLayer() {
		return new ListChanges<>(this.listDelegate);
	}

	/**
	 * The delegate branch mutates the backing `ArrayList` in place, so a whole-state pre-image would be a deep copy of
	 * the accumulated base list — the rollback cliff the journal strategy exists to avoid. It journals PER OPERATION
	 * instead, recording the slot each write overwrites (and the position each insertion or removal shifts) before
	 * applying it.
	 *
	 * @return always `true` — see above
	 */
	@Override
	public boolean supportsWarmUpRollback() {
		return true;
	}

	/**
	 * Produces an immutable copy of this list with all transactional changes from `layer` merged in.
	 *
	 * @param layer              the diff layer to merge; may be `null` if no changes were recorded
	 * @param transactionalLayer the maintainer used to recursively commit nested transactional objects
	 */
	@Nonnull
	@SuppressWarnings({"unchecked", "rawtypes"})
	@Override
	public List<V> createCopyWithMergedTransactionalMemory(
		@Nullable ListChanges<V> layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		return createCopyWithMergedTransactionalMemory(
			layer,
			value -> (V) transactionalLayer.getStateCopyWithCommittedChanges(
				(TransactionalStateProducer) value
			)
		);
	}

	/**
	 * Discards the transactional diff layer for this object, releasing all recorded changes.
	 */
	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
	}

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
			return new WarmUpJournalingListIterator<>(this.listDelegate, 0);
		} else {
			return new TransactionalMemoryEntryAbstractIterator<>(layer, this, 0);
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
			//noinspection SuspiciousArrayCast
			return (T[]) this.listDelegate.toArray();
		} else {
			// create copy of the list with all changes applied and convert it to the array
			return createCopyWithMergedTransactionalMemory(layer, value -> (V) value)
				.toArray(array);
		}
	}

	@Override
	public boolean add(V v) {
		this.add(size(), v);
		return true;
	}

	@Override
	public boolean remove(Object o) {
		final ListChanges<V> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			Objects.requireNonNull(o);
			final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
			if (savepoint != null) {
				// resolved to a position first, so the inverse can put the element back exactly where it was; the extra
				// scan matches the one `List#remove(Object)` performs itself and is paid only inside a savepoint
				final int index = this.listDelegate.indexOf(o);
				if (index < 0) {
					return false;
				}
				journalRemoval(savepoint, index);
				this.listDelegate.remove(index);
				return true;
			}
			return this.listDelegate.remove(o);
		} else {
			return layer.remove(Objects.requireNonNull(o));
		}
	}

	@Override
	public boolean containsAll(@Nonnull Collection<?> c) {
		for (Object e : c) {
			if (!contains(e)) {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean addAll(@Nonnull Collection<? extends V> c) {
		boolean modified = false;
		for (V e : c) {
			add(e);
			modified = true;
		}
		return modified;
	}

	@Override
	public boolean addAll(int index, @Nonnull Collection<? extends V> c) {
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
			final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
			if (savepoint != null) {
				// the one place a whole-list pre-image is right: the clear is O(size) in any case, so a copy of the
				// same order changes no complexity, and per-element inverses would push one entry per survivor
				final List<V> preImage = new ArrayList<>(this.listDelegate);
				savepoint.push(() -> {
					this.listDelegate.clear();
					this.listDelegate.addAll(preImage);
				});
			}
			this.listDelegate.clear();
		} else {
			layer.cleanAll(
				ofNullable(getTransactionalLayerMaintainer())
					.orElseThrow(() -> new IllegalStateException("Transactional layer must be present!"))
			);
		}
	}

	@Nullable
	@Override
	public V get(int index) {
		final ListChanges<V> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return this.listDelegate.get(index);
		} else {
			return layer.get(index);
		}
	}

	@Nullable
	@Override
	public V set(int index, V element) {
		final ListChanges<V> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
			if (savepoint != null) {
				final V previous = this.listDelegate.get(index);
				savepoint.push(() -> this.listDelegate.set(index, previous));
			}
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
			final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
			if (savepoint != null) {
				// an insertion has no pre-image to capture, so the inverse is recorded only once the insertion has
				// actually happened - a rejected index or element would otherwise leave behind an inverse that drops a
				// position nothing ever added, and a rollback must never do that
				savepoint.push(() -> this.listDelegate.remove(index));
			}
		} else {
			layer.add(index, Objects.requireNonNull(element));
		}
	}

	@Nullable
	@Override
	public V remove(int index) {
		final ListChanges<V> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer == null) {
			final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
			if (savepoint != null) {
				journalRemoval(savepoint, index);
			}
			return this.listDelegate.remove(index);
		} else {
			return layer.remove(index);
		}
	}

	/**
	 * Records the inverse of a pending removal of the element at `index`: captures the element and pushes an operation
	 * that inserts it back at the same position. Must be called BEFORE the removal.
	 *
	 * **Why a positional inverse is still an absolute restore.** A list's slots are addressed by position, so an insert
	 * or a removal shifts every element after it - a per-slot capture like the map's would not describe what changed.
	 * Reverse replay is what makes the positional form exact: the journal runs the inverses newest-first, so by the
	 * time this one runs every later shift has already been undone and `index` addresses the same slot it did when the
	 * element was taken out. This is the identical scheme the transactional branch uses in {@link ListChanges}, whose
	 * `add` / `remove` inverses un-shift its insertion map the same way.
	 *
	 * @param savepoint the open savepoint to record into
	 * @param index     the position about to be removed
	 */
	private void journalRemoval(@Nonnull WarmUpSavepoint savepoint, int index) {
		final V removed = this.listDelegate.get(index);
		savepoint.push(() -> this.listDelegate.add(index, removed));
	}

	/**
	 * Resolves whether a warm-up savepoint brackets the current root entity mutation, and therefore whether a raw
	 * delegate view handed out of this class could carry a write the savepoint would not be able to rewind.
	 *
	 * The iterators no longer ask: they are journaled unconditionally on the non-transactional branch, because the
	 * answer here is only valid at the instant it is asked and an iterator outlives that instant. {@link #subList}
	 * does ask, because it hands out a view it cannot journal at all and has to refuse writes instead.
	 *
	 * @return `true` when a warm-up savepoint is open on this thread
	 */
	private static boolean warmUpSavepointIsOpen() {
		return WarmUpSavepoint.getIfOpen() != null;
	}

	@Override
	public int indexOf(Object o) {
		// use simple iterator - this won't be very fast
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
		// use simple iterator - this won't be very fast
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
			return new WarmUpJournalingListIterator<>(this.listDelegate, 0);
		} else {
			return new TransactionalMemoryEntryAbstractIterator<>(layer, this, 0);
		}
	}

	@Nonnull
	@Override
	public ListIterator<V> listIterator(int index) {
		final ListChanges<V> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			return new WarmUpJournalingListIterator<>(this.listDelegate, index);
		} else {
			return new TransactionalMemoryEntryAbstractIterator<>(layer, this, index);
		}
	}

	/**
	 * Returns a view of the portion of this list between `fromIndex` (inclusive) and `toIndex` (exclusive).
	 *
	 * **Read-only by contract, in both branches.** Inside a transaction this returns a detached copy rather than a
	 * view, so a write made through it is silently discarded - a caller therefore cannot rely on sub-list writes
	 * reaching the list, and none does.
	 *
	 * Outside a transaction the delegate's own live view would reach the delegate without passing any of this class's
	 * journaling mutators, and journaling it properly would mean wrapping the whole positional `List` surface for a
	 * write that is not part of the contract to begin with. While a warm-up savepoint is open the view is therefore
	 * handed out UNMODIFIABLE: the write that a rollback could not have rewound becomes an immediate
	 * {@link UnsupportedOperationException} instead of state the rollback silently misses. The transactional branch
	 * already discards such writes, so no caller can have depended on them landing.
	 */
	@Nonnull
	@Override
	public List<V> subList(int fromIndex, int toIndex) {
		final ListChanges<V> layer = getTransactionalMemoryLayerIfExists(this);
		if (layer == null) {
			final List<V> view = this.listDelegate.subList(fromIndex, toIndex);
			return warmUpSavepointIsOpen() ? Collections.unmodifiableList(view) : view;
		} else {
			final List<V> subList = new ArrayList<>(toIndex - fromIndex);
			// create copy of new list with all changes merged - not entirely fast, but safe
			final Iterator<V> it = iterator();
			int counter = 0;
			while (it.hasNext()) {
				final V element = it.next();
				if (counter >= fromIndex && counter < toIndex) {
					subList.add(element);
				}
				counter++;
				// stop early once all requested elements are collected
				if (counter >= toIndex) {
					break;
				}
			}
			return subList;
		}
	}

	@Override
	public int hashCode() {
		int hashCode = 1;
		for (V e : this)
			hashCode = 31 * hashCode + (e == null ? 0 : e.hashCode());
		return hashCode;
	}

	@Override
	public boolean equals(@Nullable Object o) {
		if (o == this)
			return true;
		if (!(o instanceof List))
			return false;

		ListIterator<V> e1 = listIterator();
		@SuppressWarnings({"unchecked", "rawtypes"}) ListIterator<V> e2 = ((List) o).listIterator();
		while (e1.hasNext() && e2.hasNext()) {
			V o1 = e1.next();
			Object o2 = e2.next();
			if (!(Objects.equals(o1, o2)))
				return false;
		}
		return !(e1.hasNext() || e2.hasNext());
	}

	@Nonnull
	@Override
	public String toString() {
		final Iterator<V> it = iterator();
		if (!it.hasNext())
			return "[]";

		final StringBuilder sb = new StringBuilder(64);
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
	private List<V> createCopyWithMergedTransactionalMemory(
		@Nullable ListChanges<V> layer,
		@Nonnull Function<TransactionalStateProducer<?>, V> transactionLayerExtractor
	) {
		// create new array list of requested size
		final ArrayList<V> copy = new ArrayList<>(size());
		// iterate original list and copy all values from it
		for (int i = 0; i < this.listDelegate.size(); i++) {
			V value = this.listDelegate.get(i);
			// we need to always create copy - something in the referenced object might have changed
			// even the removed values need to be evaluated (in order to discard them from transactional memory set)
			if (value instanceof TransactionalStateProducer) {
				value = transactionLayerExtractor.apply((TransactionalStateProducer) value);
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
				if (value instanceof TransactionalStateProducer) {
					value = transactionLayerExtractor.apply((TransactionalStateProducer) value);
				}
				// add the element in the result list
				copy.add(updatedItem, value);
			}
		}

		return copy;
	}

	/**
	 * `ListIterator` over the raw delegate that journals every {@link #remove()}, {@link #set(Object)} and
	 * {@link #add(Object)} into the open warm-up savepoint. It is handed out instead of the delegate's own iterator
	 * while a savepoint is open, because writing through an iterator reaches the delegate without passing through any
	 * of this list's mutators. It is the warm-up counterpart of {@link TransactionalMemoryEntryAbstractIterator},
	 * which closes the same hole on the transactional branch.
	 *
	 * The savepoint is re-resolved at write time rather than captured at construction, so an iterator that outlives the
	 * savepoint that justified wrapping it records nothing.
	 *
	 * @param <V> the element type of the list
	 */
	private static final class WarmUpJournalingListIterator<V> implements ListIterator<V> {
		private final List<V> listDelegate;
		private final ListIterator<V> delegate;
		/**
		 * Position of the element the last {@link #next()} / {@link #previous()} returned, or `-1` when there is none
		 * to write through - which is exactly when the {@link ListIterator} contract forbids `remove` and `set`.
		 */
		private int lastIndex = -1;
		@Nullable private V lastValue;

		WarmUpJournalingListIterator(@Nonnull List<V> listDelegate, int initialIndex) {
			this.listDelegate = listDelegate;
			this.delegate = listDelegate.listIterator(initialIndex);
		}

		@Override
		public boolean hasNext() {
			return this.delegate.hasNext();
		}

		@Override
		public V next() {
			this.lastIndex = this.delegate.nextIndex();
			this.lastValue = this.delegate.next();
			return this.lastValue;
		}

		@Override
		public boolean hasPrevious() {
			return this.delegate.hasPrevious();
		}

		@Override
		public V previous() {
			this.lastIndex = this.delegate.previousIndex();
			this.lastValue = this.delegate.previous();
			return this.lastValue;
		}

		@Override
		public int nextIndex() {
			return this.delegate.nextIndex();
		}

		@Override
		public int previousIndex() {
			return this.delegate.previousIndex();
		}

		/**
		 * The pre-image of every write here is already in hand before the write runs (it is the element the cursor
		 * last returned), so each inverse is pushed only AFTER the delegate accepted the write - a rejected write then
		 * leaves no inverse behind, and a rollback can never undo something that never happened.
		 */
		@Override
		public void remove() {
			final int index = this.lastIndex;
			final V value = this.lastValue;
			this.delegate.remove();
			final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
			if (savepoint != null && index > -1) {
				savepoint.push(() -> this.listDelegate.add(index, value));
			}
			// per the ListIterator contract neither remove nor set may follow until the cursor moves again
			this.lastIndex = -1;
		}

		@Override
		public void set(V v) {
			final int index = this.lastIndex;
			final V value = this.lastValue;
			this.delegate.set(v);
			final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
			if (savepoint != null && index > -1) {
				savepoint.push(() -> this.listDelegate.set(index, value));
			}
		}

		@Override
		public void add(V v) {
			// the element lands at the cursor, pushing everything from there on one position further; reverse replay
			// undoes the later shifts first, so dropping that position again is an exact inverse
			final int index = this.delegate.nextIndex();
			this.delegate.add(v);
			final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
			if (savepoint != null) {
				savepoint.push(() -> this.listDelegate.remove(index));
			}
			this.lastIndex = -1;
		}
	}

	/**
	 * `ListIterator` implementation backed by a transactional diff layer that supports in-place modifications.
	 * Reads are served from the diff layer's merged view; writes (add, set, remove) are forwarded to the layer.
	 *
	 * @param <V> the element type of the list
	 */
	private static class TransactionalMemoryEntryAbstractIterator<V> implements ListIterator<V> {
		private final ListChanges<V> layer;
		private final TransactionalLayerCreator<ListChanges<V>> layerCreator;
		private int currentPosition;
		private int previousPosition = -1;

		/**
		 * Creates a new iterator starting at the given index within the transactional diff layer.
		 *
		 * @param layer        the diff layer providing the merged list view
		 * @param layerCreator the creator owning the diff layer, used to register the write-touch with the maintainer
		 *                     when {@link #remove()} / {@link #set(Object)} / {@link #add(Object)} mutate the layer
		 * @param initialIndex the index at which iteration begins
		 */
		TransactionalMemoryEntryAbstractIterator(
			@Nonnull ListChanges<V> layer,
			@Nonnull TransactionalLayerCreator<ListChanges<V>> layerCreator,
			int initialIndex
		) {
			this.currentPosition = initialIndex;
			this.layer = layer;
			this.layerCreator = layerCreator;
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
			// decrement first, then record the position of the element being returned
			--this.currentPosition;
			this.previousPosition = this.currentPosition;
			return this.layer.get(this.currentPosition);
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
				// register the write-touch with the maintainer FIRST: when a savepoint is open and this layer has not
				// been touched inside it yet, this records the layer's pre-mutation snapshot (and activates its undo
				// journal) BEFORE the removal below mutates the diff layer - otherwise it would be unrevertable
				Transaction.getTransactionalMemoryLayerForWriteIfExists(this.layerCreator);
				this.currentPosition = this.previousPosition;
				this.layer.remove(this.previousPosition);
				// reset to -1 to prevent a second consecutive remove() call (per ListIterator contract)
				this.previousPosition = -1;
			} else {
				throw new GenericEvitaInternalError("Previous position unexpectedly: " + this.previousPosition);
			}
		}

		@Override
		public void set(V v) {
			if (this.currentPosition > 0) {
				// register the write-touch with the maintainer FIRST (see remove() above) so the in-place set is
				// captured for a per-entity savepoint rollback even when it is the layer's first touch in the savepoint
				Transaction.getTransactionalMemoryLayerForWriteIfExists(this.layerCreator);
				final int index = this.currentPosition - 1;
				// remove element and add on the same index new value
				this.layer.remove(index);
				this.layer.add(index, v);
			} else {
				throw new GenericEvitaInternalError("Current position unexpectedly: " + this.currentPosition);
			}
		}

		@Override
		public void add(V v) {
			// register the write-touch with the maintainer FIRST (see remove() above) so the insertion is captured for
			// a per-entity savepoint rollback even when it is the layer's first touch in the savepoint
			Transaction.getTransactionalMemoryLayerForWriteIfExists(this.layerCreator);
			this.layer.add(this.currentPosition, v);
		}

	}

}
