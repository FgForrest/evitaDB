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

import io.evitadb.core.transaction.memory.Snapshotable;
import io.evitadb.core.transaction.memory.UndoJournal;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Represents the diff layer for {@link TransactionalSet}, tracking
 * insertions and removals against an immutable delegate set. Created
 * keys are stored in a separate `HashSet`, and removed keys are
 * tracked in another `HashSet`. On commit, these changes are merged
 * with the delegate to produce the final set state. Created keys
 * are absent from the delegate, and removed keys belong to it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2017
 */
@NotThreadSafe
public class SetChanges<K> implements Serializable, Snapshotable<SetChanges.SetChangesMemento<K>> {
	@Serial private static final long serialVersionUID = -6370910459056592080L;

	/**
	 * Contains reference to original immutable set.
	 */
	private final Set<K> setDelegate;
	/**
	 * Contains set of removed keys. Lazily allocated on first write
	 * to avoid unnecessary heap pressure when the layer has no
	 * removals.
	 */
	@Nullable private Set<K> removedKeys;
	/**
	 * Contains set of added keys. Lazily allocated on first write
	 * to avoid unnecessary heap pressure when the layer has no
	 * insertions.
	 */
	@Nullable private Set<K> createdKeys;
	/**
	 * Undo journal recording the inverse of every {@link #createdKeys} / {@link #removedKeys} mutation while a savepoint
	 * is open, enabling an `O(1)` {@link #snapshot()} and an `O(intra-savepoint-ops)` {@link #restore(SetChangesMemento)}
	 * instead of deep-copying the whole accumulated diff. Lazily allocated on the first {@link #snapshot()} (null for
	 * non-savepoint transactions, so they pay nothing) and drained back to null when the savepoint commits (see
	 * {@link #releaseMemento(SetChangesMemento)}).
	 */
	@Nullable private UndoJournal undoJournal;

	public SetChanges(@Nonnull Set<K> setDelegate) {
		this.setDelegate = setDelegate;
	}

	/**
	 * Adds a new key to this layer or cancels a pending removal from the delegate.
	 *
	 * @param key the key to add
	 * @return whether the key was absent from the transactional set
	 */
	public boolean put(@Nonnull K key) {
		journalKey(key);
		if (this.setDelegate.contains(key)) {
			return this.removedKeys != null && this.removedKeys.remove(key);
		}
		return getOrCreateCreatedKeys().add(key);
	}

	/**
	 * Removes a key from this layer or records the removal of a delegate key.
	 *
	 * @param key the key to remove
	 * @return whether the key was present in the transactional set
	 */
	@SuppressWarnings("unchecked")
	public boolean remove(@Nonnull Object key) {
		if (containsCreated((K) key)) {
			journalKey((K) key);
			return this.createdKeys.remove(key);
		}
		//noinspection SuspiciousMethodCalls
		if (this.setDelegate.contains(key) && !containsRemoved((K) key)) {
			journalKey((K) key);
			return getOrCreateRemovedKeys().add((K) key);
		}
		return false;
	}

	/**
	 * Computes the size of the set taking changes in this diff layer
	 * into account.
	 */
	public int size() {
		return this.setDelegate.size()
			- (this.removedKeys != null ? this.removedKeys.size() : 0)
			+ (this.createdKeys != null ? this.createdKeys.size() : 0);
	}

	/**
	 * Resolves whether the original set with applied changes from this
	 * diff layer would produce an empty set.
	 */
	public boolean isEmpty() {
		if ((this.removedKeys == null || this.removedKeys.isEmpty()) &&
			(this.createdKeys == null || this.createdKeys.isEmpty())) {
			return this.setDelegate.isEmpty();
		} else {
			return size() == 0;
		}
	}

	/**
	 * Resolves whether the key is part of the original set or in this
	 * diff layer.
	 */
	@SuppressWarnings("unchecked")
	public boolean contains(@Nonnull Object o) {
		if (containsCreated((K) o)) {
			return true;
		} else if (containsRemoved((K) o)) {
			return false;
		} else {
			//noinspection SuspiciousMethodCalls
			return this.setDelegate.contains(o);
		}
	}

	/**
	 * Creates an array combining non-removed keys from the original set
	 * with the created keys trapped in this diff layer.
	 */
	@Nonnull
	public <T> T[] toArray(@Nonnull T[] a) {
		int index = 0;
		//noinspection unchecked
		final T[] resultArray = (T[]) Array.newInstance(
			a.getClass().getComponentType(), size()
		);
		// iterate original set and copy all values from it
		for (K key : this.setDelegate) {
			// except those that were removed
			if (!containsRemoved(key)) {
				//noinspection unchecked
				resultArray[index++] = (T) key;
			}
		}

		// iterate over inserted keys
		for (K key : getCreatedKeys()) {
			//noinspection unchecked
			resultArray[index++] = (T) key;
		}

		return resultArray;
	}

	/**
	 * Applies this diff layer's membership changes to the delegate.
	 *
	 * @return a new set containing the retained delegate keys and the added keys
	 */
	@Nonnull
	public Set<K> createMergedSet() {
		final Set<K> copy = CollectionUtils.createHashSet(this.setDelegate.size());
		for (final K key : this.setDelegate) {
			if (!containsRemoved(key)) {
				copy.add(key);
			}
		}
		copy.addAll(getCreatedKeys());
		return copy;
	}

	/**
	 * Marks all delegate keys as removed and clears the created set.
	 */
	void clearAll() {
		// bulk op: journal the pre-mutation membership of every key clearAll will touch (all created keys are dropped
		// and every delegate key is tombstoned) so a savepoint rollback can rebuild the exact pre-clearAll diff
		if (this.undoJournal != null) {
			if (this.createdKeys != null) {
				for (final K key : this.createdKeys) {
					this.undoJournal.push(captureKeyInverse(key));
				}
			}
			for (final K key : this.setDelegate) {
				this.undoJournal.push(captureKeyInverse(key));
			}
		}
		if (this.createdKeys != null) {
			this.createdKeys.clear();
		}
		getOrCreateRemovedKeys().addAll(this.setDelegate);
	}

	/**
	 * Returns true if the passed key was not part of
	 * {@link #setDelegate} but was added in this transactional
	 * memory diff.
	 */
	boolean containsCreated(@Nonnull K key) {
		return this.createdKeys != null && this.createdKeys.contains(key);
	}

	/**
	 * Returns set of all newly created keys that were not in the
	 * original {@link #setDelegate}.
	 */
	@Nonnull
	Set<K> getCreatedKeys() {
		return this.createdKeys != null ? this.createdKeys : Collections.emptySet();
	}

	/**
	 * Returns true if particular key is recorded to be removed.
	 */
	boolean containsRemoved(@Nonnull K key) {
		return this.removedKeys != null && this.removedKeys.contains(key);
	}

	/**
	 * Returns the removed-keys set, allocating it on first use.
	 */
	@Nonnull
	private Set<K> getOrCreateRemovedKeys() {
		if (this.removedKeys == null) {
			this.removedKeys = new HashSet<>();
		}
		return this.removedKeys;
	}

	/**
	 * Returns the created-keys set, allocating it on first use.
	 */
	@Nonnull
	private Set<K> getOrCreateCreatedKeys() {
		if (this.createdKeys == null) {
			this.createdKeys = new HashSet<>();
		}
		return this.createdKeys;
	}

	/**
	 * Marks the current position in the undo journal and records which change sets are allocated.
	 *
	 * @return the journal mark and the allocation state of the change sets
	 */
	@Nonnull
	@Override
	public SetChangesMemento<K> snapshot() {
		if (this.undoJournal == null) {
			this.undoJournal = new UndoJournal();
		}
		return new SetChangesMemento<>(
			this.undoJournal.mark(),
			this.createdKeys == null,
			this.removedKeys == null
		);
	}

	/**
	 * Restores the diff state of this layer from the given memento, undoing every
	 * {@link #put}/{@link #remove}/{@link #clearAll} performed since the memento was captured.
	 *
	 * The undo journal restores the recorded memberships. A change set that was unallocated at the
	 * time of capture is reset to null.
	 *
	 * @param memento the previously captured state to restore
	 */
	@Override
	public void restore(@Nonnull SetChangesMemento<K> memento) {
		UndoJournal.assertRestorable(this.undoJournal, memento.mark());
		if (this.undoJournal != null) {
			this.undoJournal.rollbackTo(memento.mark());
		}
		// normalize the lazy container existence to the snapshot moment: a container the journal re-populated but that
		// was null at snapshot time (all its entries were added during the window) is emptied back to null
		if (memento.createdWasNull()) {
			this.createdKeys = null;
		}
		if (memento.removedWasNull()) {
			this.removedKeys = null;
		}
	}

	/**
	 * Releases a closed savepoint's memento (see {@link Snapshotable#releaseMemento(Object)}) - on commit the changes are kept,
	 * on rollback {@link #restore} has already rewound them -
	 * so the journal entries recorded since the mark are discarded (never replayed). When the journal drains empty it is
	 * nulled out, restoring the allocation-free fast path for the rest of the transaction.
	 *
	 * @param memento the committed memento previously produced by {@link #snapshot()}
	 */
	@Override
	public void releaseMemento(@Nonnull SetChangesMemento<K> memento) {
		if (this.undoJournal != null) {
			this.undoJournal.releaseFrom(memento.mark());
			if (this.undoJournal.isEmpty()) {
				this.undoJournal = null;
			}
		}
	}

	/**
	 * Records the inverse of a change about to be applied to {@link #createdKeys} / {@link #removedKeys} for the given
	 * key, but only while a savepoint is open (i.e. the {@link #undoJournal} exists). No-op otherwise, so non-savepoint
	 * transactions pay nothing beyond a null check.
	 *
	 * Package-private so that {@link TransactionalSet}'s merged iterator can capture the pre-mutation membership
	 * before it removes a created key straight through the created-keys iterator (which bypasses the journaled
	 * mutators of this layer).
	 *
	 * @param key the key whose membership in the two change sets is about to change
	 */
	void journalKey(@Nonnull K key) {
		if (this.undoJournal != null) {
			this.undoJournal.push(captureKeyInverse(key));
		}
	}

	/**
	 * Captures both change sets' membership for the key before an operation. Replaying these inverses
	 * in reverse order restores the membership recorded at the savepoint.
	 *
	 * @param key the key whose pre-mutation membership is captured
	 * @return an inverse restoring the key's membership in the two change sets
	 */
	@Nonnull
	private Runnable captureKeyInverse(@Nonnull K key) {
		final boolean wasInCreated = containsCreated(key);
		final boolean wasInRemoved = containsRemoved(key);
		return () -> restoreKeyMembership(key, wasInCreated, wasInRemoved);
	}

	/**
	 * Restores the key's membership in both change sets. The memento handles their allocation state
	 * after all per-key inverses have replayed.
	 *
	 * @param key          the key to restore
	 * @param wasInCreated whether the key was present in {@link #createdKeys}
	 * @param wasInRemoved whether the key was present in {@link #removedKeys}
	 */
	private void restoreKeyMembership(@Nonnull K key, boolean wasInCreated, boolean wasInRemoved) {
		if (wasInCreated) {
			getOrCreateCreatedKeys().add(key);
		} else if (this.createdKeys != null) {
			this.createdKeys.remove(key);
		}
		if (wasInRemoved) {
			getOrCreateRemovedKeys().add(key);
		} else if (this.removedKeys != null) {
			this.removedKeys.remove(key);
		}
	}

	/**
	 * Immutable, `O(1)` memento marking a {@link SetChanges} layer's diff state at a single point in time. It holds the
	 * {@link #undoJournal} position to rewind to plus the two change sets' null-vs-set existence flags (to faithfully
	 * preserve the lazy-allocation invariant on restore). The actual diff contents are rewound via the journal, not
	 * copied here; the baseline {@link SetChanges#setDelegate} is shared and immutable and so is deliberately excluded.
	 *
	 * @param mark           the {@link UndoJournal#mark()} to rewind the layer to on restore
	 * @param createdWasNull whether {@link SetChanges#createdKeys} was `null` (unallocated) at snapshot time
	 * @param removedWasNull whether {@link SetChanges#removedKeys} was `null` (unallocated) at snapshot time
	 * @param <K>            the type of the keys tracked by the set
	 */
	public record SetChangesMemento<K>(
		int mark,
		boolean createdWasNull,
		boolean removedWasNull
	) {
	}

}
