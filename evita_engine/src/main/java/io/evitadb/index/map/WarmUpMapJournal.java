/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.core.transaction.memory.WarmUpSavepoint;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;

/**
 * Records, into the {@link WarmUpSavepoint} bracketing the current root entity mutation, the inverses of writes that
 * reach a plain delegate {@link Map} in place — the WARM_UP (bulk indexing) write branch of {@link TransactionalMap}
 * and {@link PersistentTransactionalMap}. Both decorators share it because both fall through to an ordinary mutable
 * {@link Map} once there is no diff layer to record into.
 *
 * **Per-operation inverses, never a first-touch copy.** The delegate here is the map's whole accumulated content, so a
 * first-touch memento of it would deep-copy the base structure once per entity — the `O(N²)` rollback cliff the
 * journal strategy exists to avoid (see {@link io.evitadb.core.transaction.memory.UndoJournal}). Each write therefore
 * captures only the ONE slot it overwrites: its key's presence and value before the write, restored absolutely. Under
 * the journal's reverse replay the earliest-pushed inverse for a key runs last and wins, so a key rewritten several
 * times inside one savepoint still ends up at its pre-savepoint value.
 *
 * The two bulk operations are the deliberate exception: {@link #journalWholeMap} copies the entire map, because a
 * `clear()` is `O(N)` in any case and one wholesale restore is cheaper and simpler than `N` per-slot inverses. It
 * composes with the per-slot inverses without any special ordering rule — being an absolute restore of everything, it
 * simply subsumes whatever ran before it and is itself overridden by the per-slot inverses of writes that came after.
 *
 * **The collection views** ({@link #keySet}, {@link #values}, {@link #entrySet}) are wrappers handed out INSTEAD of the
 * delegate's own views while a savepoint is open, because a caller can remove through a view's iterator or overwrite
 * through {@link Entry#setValue} and reach the delegate without passing through any of the map's own mutators. They
 * are the warm-up counterpart of {@link TransactionalMap.TransactionalMemoryKeySet} and its siblings, which close the
 * same hole on the transactional branch. Each wrapper re-resolves the open savepoint at write time rather than
 * capturing the one that was open when the view was handed out, so a view that outlives its savepoint records nothing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class WarmUpMapJournal {

	/**
	 * Purely static - there is nothing to instantiate.
	 */
	private WarmUpMapJournal() {
	}

	/**
	 * Records the inverse of a pending change to a single key of `delegate`: captures whether the key is currently
	 * present and with which value, and pushes an operation restoring exactly that. Must be called BEFORE the write.
	 *
	 * @param savepoint the open savepoint to record into
	 * @param delegate  the map about to be written (mutated in place, never reassigned)
	 * @param key       the key whose slot is about to change
	 * @param <K>       key type
	 * @param <V>       value type
	 */
	static <K, V> void journalSlot(
		@Nonnull WarmUpSavepoint savepoint,
		@Nonnull Map<K, V> delegate,
		K key
	) {
		final boolean present = delegate.containsKey(key);
		final V previous = present ? delegate.get(key) : null;
		savepoint.push(() -> {
			if (present) {
				delegate.put(key, previous);
			} else {
				delegate.remove(key);
			}
		});
	}

	/**
	 * Records the inverse of a pending bulk change that rewrites `delegate` wholesale: captures every entry and pushes
	 * an operation that empties the map and refills it from the capture. Must be called BEFORE the write.
	 *
	 * The capture is an insertion-ordered copy and is refilled in that order, so a delegate whose iteration order is
	 * part of its contract (a `LinkedHashMap`) comes back in the order it had, not merely with the right entries.
	 *
	 * @param savepoint the open savepoint to record into
	 * @param delegate  the map about to be rewritten (mutated in place, never reassigned)
	 * @param <K>       key type
	 * @param <V>       value type
	 */
	static <K, V> void journalWholeMap(@Nonnull WarmUpSavepoint savepoint, @Nonnull Map<K, V> delegate) {
		final Map<K, V> preImage = new LinkedHashMap<>(delegate);
		savepoint.push(() -> {
			delegate.clear();
			delegate.putAll(preImage);
		});
	}

	/**
	 * Records the inverse of a pending change to one key, but only when a savepoint is actually open. This is the form
	 * a single-key mutator uses; the view wrappers use it as well, because they cannot know at construction time
	 * whether the savepoint that justified wrapping them is still the current one when a write finally arrives.
	 *
	 * @param delegate the map about to be written
	 * @param key      the key whose slot is about to change
	 * @param <K>      key type
	 * @param <V>      value type
	 */
	static <K, V> void journalSlotIfOpen(@Nonnull Map<K, V> delegate, K key) {
		final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
		if (savepoint != null) {
			journalSlot(savepoint, delegate, key);
		}
	}

	/**
	 * Records the inverse of a pending wholesale rewrite, but only when a savepoint is actually open (see
	 * {@link #journalSlotIfOpen}).
	 *
	 * @param delegate the map about to be rewritten
	 * @param <K>      key type
	 * @param <V>      value type
	 */
	static <K, V> void journalWholeMapIfOpen(@Nonnull Map<K, V> delegate) {
		final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
		if (savepoint != null) {
			journalWholeMap(savepoint, delegate);
		}
	}

	/**
	 * Returns a key-set view of `delegate` whose removals are journaled.
	 *
	 * @param delegate the backing map
	 * @param <K>      key type
	 * @param <V>      value type
	 * @return the journaling key set
	 */
	@Nonnull
	static <K, V> Set<K> keySet(@Nonnull Map<K, V> delegate) {
		return new JournalingKeySet<>(delegate);
	}

	/**
	 * Returns a values view of `delegate` whose removals are journaled.
	 *
	 * @param delegate the backing map
	 * @param <K>      key type
	 * @param <V>      value type
	 * @return the journaling values collection
	 */
	@Nonnull
	static <K, V> Collection<V> values(@Nonnull Map<K, V> delegate) {
		return new JournalingValues<>(delegate);
	}

	/**
	 * Returns an entry-set view of `delegate` whose removals and in-place {@link Entry#setValue} overwrites are
	 * journaled.
	 *
	 * @param delegate the backing map
	 * @param <K>      key type
	 * @param <V>      value type
	 * @return the journaling entry set
	 */
	@Nonnull
	static <K, V> Set<Entry<K, V>> entrySet(@Nonnull Map<K, V> delegate) {
		return new JournalingEntrySet<>(delegate);
	}

	/**
	 * Key-set view whose every removal path is journaled. The inherited `removeAll` / `retainAll` / `removeIf` all
	 * funnel through {@link #iterator()} or {@link #remove(Object)}, so overriding those two plus {@link #clear()}
	 * covers the whole mutable surface a key set exposes (it accepts no additions by the {@link Map} contract).
	 *
	 * @param <K> key type
	 * @param <V> value type
	 */
	private static final class JournalingKeySet<K, V> extends AbstractSet<K> {
		private final Map<K, V> delegate;

		JournalingKeySet(@Nonnull Map<K, V> delegate) {
			this.delegate = delegate;
		}

		@Nonnull
		@Override
		public Iterator<K> iterator() {
			// projected out of the entry-set iterator rather than the delegate's own key iterator, so remove() has the
			// entry's VALUE at hand and can record an inverse that puts the whole mapping back
			final Iterator<Entry<K, V>> entryIterator = this.delegate.entrySet().iterator();
			return new Iterator<>() {
				@Nullable private Entry<K, V> current;

				@Override
				public boolean hasNext() {
					return entryIterator.hasNext();
				}

				@Override
				public K next() {
					this.current = entryIterator.next();
					return this.current.getKey();
				}

				@Override
				public void remove() {
					if (this.current != null) {
						journalSlotIfOpen(JournalingKeySet.this.delegate, this.current.getKey());
					}
					entryIterator.remove();
				}
			};
		}

		@Override
		public int size() {
			return this.delegate.size();
		}

		@Override
		public boolean isEmpty() {
			return this.delegate.isEmpty();
		}

		@Override
		public boolean contains(Object key) {
			return this.delegate.containsKey(key);
		}

		@Override
		public boolean remove(Object key) {
			if (!this.delegate.containsKey(key)) {
				return false;
			}
			//noinspection unchecked
			journalSlotIfOpen(this.delegate, (K) key);
			this.delegate.remove(key);
			return true;
		}

		@Override
		public void clear() {
			journalWholeMapIfOpen(this.delegate);
			this.delegate.clear();
		}
	}

	/**
	 * Values view whose every removal path is journaled. `remove` / `removeAll` / `retainAll` / `removeIf` are all
	 * inherited and funnel through {@link #iterator()}, exactly as they do on a `HashMap`'s own values view.
	 *
	 * @param <K> key type
	 * @param <V> value type
	 */
	private static final class JournalingValues<K, V> extends AbstractCollection<V> {
		private final Map<K, V> delegate;

		JournalingValues(@Nonnull Map<K, V> delegate) {
			this.delegate = delegate;
		}

		@Nonnull
		@Override
		public Iterator<V> iterator() {
			// projected out of the entry-set iterator: a values iterator alone could not name the KEY whose mapping a
			// remove() drops, and without the key no inverse can put it back
			final Iterator<Entry<K, V>> entryIterator = this.delegate.entrySet().iterator();
			return new Iterator<>() {
				@Nullable private Entry<K, V> current;

				@Override
				public boolean hasNext() {
					return entryIterator.hasNext();
				}

				@Override
				public V next() {
					this.current = entryIterator.next();
					return this.current.getValue();
				}

				@Override
				public void remove() {
					if (this.current != null) {
						journalSlotIfOpen(JournalingValues.this.delegate, this.current.getKey());
					}
					entryIterator.remove();
				}
			};
		}

		@Override
		public int size() {
			return this.delegate.size();
		}

		@Override
		public boolean isEmpty() {
			return this.delegate.isEmpty();
		}

		@Override
		public boolean contains(Object value) {
			return this.delegate.containsValue(value);
		}

		@Override
		public void clear() {
			journalWholeMapIfOpen(this.delegate);
			this.delegate.clear();
		}
	}

	/**
	 * Entry-set view whose removals AND in-place {@link Entry#setValue} overwrites are journaled. The `setValue` path
	 * is the one a caller reaches without ever naming this view - {@link Map#replaceAll} walks the entry set and writes
	 * through each entry - which is why the iterator hands out wrapped entries rather than the delegate's own.
	 *
	 * @param <K> key type
	 * @param <V> value type
	 */
	private static final class JournalingEntrySet<K, V> extends AbstractSet<Entry<K, V>> {
		private final Map<K, V> delegate;

		JournalingEntrySet(@Nonnull Map<K, V> delegate) {
			this.delegate = delegate;
		}

		@Nonnull
		@Override
		public Iterator<Entry<K, V>> iterator() {
			final Iterator<Entry<K, V>> entryIterator = this.delegate.entrySet().iterator();
			return new Iterator<>() {
				@Nullable private Entry<K, V> current;

				@Override
				public boolean hasNext() {
					return entryIterator.hasNext();
				}

				@Override
				public Entry<K, V> next() {
					this.current = entryIterator.next();
					return new JournalingEntry<>(JournalingEntrySet.this.delegate, this.current);
				}

				@Override
				public void remove() {
					if (this.current != null) {
						journalSlotIfOpen(JournalingEntrySet.this.delegate, this.current.getKey());
					}
					entryIterator.remove();
				}
			};
		}

		@Override
		public int size() {
			return this.delegate.size();
		}

		@Override
		public boolean isEmpty() {
			return this.delegate.isEmpty();
		}

		@Override
		public boolean contains(Object o) {
			if (!(o instanceof Entry<?, ?> entry)) {
				return false;
			}
			final Object key = entry.getKey();
			return this.delegate.containsKey(key) && Objects.equals(this.delegate.get(key), entry.getValue());
		}

		@Override
		public boolean remove(Object o) {
			if (!contains(o)) {
				return false;
			}
			//noinspection unchecked
			final K key = (K) ((Entry<?, ?>) o).getKey();
			journalSlotIfOpen(this.delegate, key);
			this.delegate.remove(key);
			return true;
		}

		@Override
		public void clear() {
			journalWholeMapIfOpen(this.delegate);
			this.delegate.clear();
		}
	}

	/**
	 * Entry wrapper that journals an in-place {@link #setValue(Object)} before letting it through. Reads are answered
	 * from the wrapped live entry, so the view stays a view.
	 *
	 * @param <K> key type
	 * @param <V> value type
	 */
	private record JournalingEntry<K, V>(
		@Nonnull Map<K, V> delegate,
		@Nonnull Entry<K, V> wrapped
	) implements Entry<K, V> {

		@Override
		public K getKey() {
			return this.wrapped.getKey();
		}

		@Override
		public V getValue() {
			return this.wrapped.getValue();
		}

		@Override
		public V setValue(V value) {
			journalSlotIfOpen(this.delegate, this.wrapped.getKey());
			return this.wrapped.setValue(value);
		}

		@Override
		public boolean equals(@Nullable Object obj) {
			return this.wrapped.equals(obj);
		}

		@Override
		public int hashCode() {
			return this.wrapped.hashCode();
		}

		@Nonnull
		@Override
		public String toString() {
			return this.wrapped.toString();
		}
	}

}
