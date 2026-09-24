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
import io.evitadb.core.transaction.memory.TransactionalStateProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
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
import java.util.LinkedHashSet;
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
public class TransactionalSet<K> implements Set<K>, Serializable,
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

	/**
	 * The delegate branch mutates the backing `HashSet` in place, so a whole-state pre-image would be a deep copy of
	 * the accumulated base set — the rollback cliff the journal strategy exists to avoid. It journals PER OPERATION
	 * instead, recording the membership each write changes before applying it.
	 *
	 * @return always `true` — see above
	 */
	@Override
	public boolean supportsWarmUpRollback() {
		return true;
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
				if (entry instanceof TransactionalStateProducer) {
					//noinspection unchecked
					transformedEntry = (K) transactionalLayer.getStateCopyWithCommittedChanges(
						(TransactionalStateProducer<?>) entry
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
			// the wrapper is handed out unconditionally rather than only while a savepoint is open, because the
			// alternative decides at CONSTRUCTION time: an iterator taken before a savepoint opened and removed
			// through after it opened would reach the delegate unjournalled. remove() re-resolves the savepoint per
			// call, so outside one the wrapper is transparent apart from its own allocation
			return new WarmUpJournalingIterator<>(this.setDelegate);
		} else {
			return new TransactionalMemorySetIterator<>(this.setDelegate, layer, this);
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
			journalMembershipIfOpen(key);
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
			//noinspection unchecked
			journalMembershipIfOpen((K) key);
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
			final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
			if (savepoint != null) {
				for (final K key : c) {
					journalMembership(savepoint, key);
				}
			}
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
			final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
			if (savepoint != null) {
				// the scan is the same O(size) the retainAll below performs anyway, and it records an inverse only for
				// the elements that are actually about to be dropped
				for (final K key : this.setDelegate) {
					if (!c.contains(key)) {
						journalMembership(savepoint, key);
					}
				}
			}
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
			final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
			if (savepoint != null) {
				// driven by the ARGUMENT, not by the set: `removeAll` with a small argument touches only that many
				// elements, so scanning the (potentially huge) delegate instead would turn an O(|c|) call into O(size)
				for (final Object key : c) {
					if (this.setDelegate.contains(key)) {
						// only elements proven present are re-added, so the cast is the membership test's own guarantee
						//noinspection unchecked
						journalMembership(savepoint, (K) key);
					}
				}
			}
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
			final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
			if (savepoint != null) {
				// the one place a whole-set pre-image is right: the clear is O(size) in any case, so a copy of the same
				// order changes no complexity, and a per-element inverse would push one journal entry per survivor.
				// Insertion-ordered so a delegate whose iteration order is part of its contract comes back in order
				final Set<K> preImage = new LinkedHashSet<>(this.setDelegate);
				savepoint.push(() -> {
					this.setDelegate.clear();
					this.setDelegate.addAll(preImage);
				});
			}
			this.setDelegate.clear();
		} else {
			layer.clearAll();
		}
	}

	/**
	 * Records the inverse of a pending membership change of `key`, but only when a warm-up savepoint brackets the
	 * current root entity mutation (see {@link WarmUpSavepoint}).
	 *
	 * @param key the element whose membership is about to change
	 */
	private void journalMembershipIfOpen(K key) {
		final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
		if (savepoint != null) {
			journalMembership(savepoint, key);
		}
	}

	/**
	 * Records the inverse of a pending membership change of `key`: captures whether the element is currently in the
	 * delegate and pushes an operation forcing exactly that membership back. Must be called BEFORE the change.
	 *
	 * The inverse is per operation rather than a first-touch copy of the delegate, because the delegate is this set's
	 * whole accumulated content and copying it once per entity is the `O(N²)` rollback cliff the journal strategy
	 * exists to avoid. It is ABSOLUTE - it forces a membership rather than toggling one - so an element added and
	 * removed several times inside one savepoint still ends up with its pre-savepoint membership, the earliest-pushed
	 * inverse being the one reverse replay runs last.
	 *
	 * @param savepoint the open savepoint to record into
	 * @param key       the element whose membership is about to change
	 */
	private void journalMembership(@Nonnull WarmUpSavepoint savepoint, K key) {
		final boolean wasPresent = this.setDelegate.contains(key);
		savepoint.push(() -> {
			if (wasPresent) {
				this.setDelegate.add(key);
			} else {
				this.setDelegate.remove(key);
			}
		});
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
	 * Iterator over the raw delegate that journals every {@link #remove()} into the open warm-up savepoint. It is
	 * handed out instead of the delegate's own iterator on the whole non-transactional branch, because removing
	 * through an iterator reaches the delegate without passing through any of this set's mutators - and `removeAll` /
	 * `retainAll` / `removeIf` on the {@link java.util.Collection} defaults all funnel through it. It is the warm-up
	 * counterpart of {@link TransactionalMemorySetIterator}, which closes the same hole on the transactional branch.
	 *
	 * The savepoint is re-resolved at {@link #remove()} time rather than captured at construction, and this is what
	 * lets the wrapper be handed out unconditionally: an iterator that outlives the savepoint records nothing, and an
	 * iterator created BEFORE a savepoint opened still journals into it. Choosing the wrapper at construction time
	 * would close only the first of those two directions.
	 *
	 * @param <K> element type
	 */
	private static final class WarmUpJournalingIterator<K> implements Iterator<K> {
		private final Set<K> setDelegate;
		private final Iterator<K> delegate;
		@Nullable private K current;

		WarmUpJournalingIterator(@Nonnull Set<K> setDelegate) {
			this.setDelegate = setDelegate;
			this.delegate = setDelegate.iterator();
		}

		@Override
		public boolean hasNext() {
			return this.delegate.hasNext();
		}

		@Override
		public K next() {
			this.current = this.delegate.next();
			return this.current;
		}

		@Override
		public void remove() {
			final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
			if (savepoint != null && this.current != null) {
				// the element is still present at this point, so the inverse is unconditionally an add-back
				final K removed = this.current;
				savepoint.push(() -> this.setDelegate.add(removed));
			}
			this.delegate.remove();
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
		private final TransactionalLayerCreator<SetChanges<K>> layerCreator;
		private final Iterator<K> layerIt;
		private final Iterator<K> stateIt;

		@Nullable private K currentValue;
		private boolean fetched = true;
		private boolean endOfData;

		/**
		 * Creates a new iterator merging the delegate and layer state.
		 *
		 * @param setDelegate  the underlying delegate set
		 * @param layer        the transactional diff layer
		 * @param layerCreator the creator owning the diff layer, used to register the write-touch with the
		 *                     maintainer when {@link #remove()} mutates the layer
		 */
		TransactionalMemorySetIterator(
			@Nonnull Set<K> setDelegate,
			@Nonnull SetChanges<K> layer,
			@Nonnull TransactionalLayerCreator<SetChanges<K>> layerCreator
		) {
			this.layer = layer;
			this.layerCreator = layerCreator;
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

			// register the write-touch with the maintainer FIRST: when a savepoint is open and this layer has not
			// been touched inside it yet, this records the layer's pre-mutation snapshot (and activates its undo
			// journal) BEFORE the removal below mutates the diff layer - otherwise the removal would be unrevertable
			Transaction.getTransactionalMemoryLayerForWriteIfExists(this.layerCreator);

			final K key = this.currentValue;
			final boolean existing = this.layer.getSetDelegate().contains(key);
			final boolean removedFromTransactionalMemory = this.layer.getCreatedKeys().contains(key);
			if (removedFromTransactionalMemory) {
				// capture the pre-mutation membership: the raw created-keys iterator removal below bypasses the
				// layer's journaled mutators, so a savepoint rollback could not reinstate the created key otherwise
				this.layer.journalKey(key);
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
