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

import io.evitadb.api.requestResponse.data.ContentComparator;
import io.evitadb.core.transaction.memory.Snapshotable;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalStateProducer;
import io.evitadb.core.transaction.memory.UndoJournal;
import lombok.Getter;

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
 * with the delegate to produce the final set state.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2017
 */
@NotThreadSafe
public class SetChanges<K> implements Serializable, Snapshotable<SetChanges.SetChangesMemento<K>> {
	@Serial private static final long serialVersionUID = -6370910459056592080L;

	/**
	 * Contains reference to original immutable set.
	 */
	@Getter private final Set<K> setDelegate;
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
	 * Records the insertion of the given key. The update is trapped within this diff layer.
	 *
	 * When the key implements {@link ContentComparator} and an equal-by-identity element with
	 * different content is already present (either in the delegate or in the change layer), the
	 * existing instance is substituted by the new one. For the delegate case this is encoded as a
	 * paired "remove + add" in the change layer - {@link #createMergedSet} honors both, so the new
	 * instance ends up in the committed snapshot.
	 */
	public boolean put(@Nonnull K key) {
		// record the pre-mutation membership of this key so a savepoint rollback can undo whatever branch runs below
		journalKey(key);
		if (containsCreated(key)) {
			// already in change layer - replace identity-equal entry if its content has diverged
			replaceInCreatedIfContentDiffers(key);
			return false;
		}
		if (this.setDelegate.contains(key)) {
			// already in original delegate
			if (contentDiffersFromDelegate(key)) {
				// substitute: mark the original position for removal AND record the new instance as
				// created - createMergedSet will drop the original and insert the new instance
				getOrCreateRemovedKeys().add(key);
				getOrCreateCreatedKeys().add(key);
				return false;
			}
			// contents are logically equal - just cancel any pending removal
			return this.removedKeys != null && this.removedKeys.remove(key);
		}
		getOrCreateCreatedKeys().add(key);
		return true;
	}

	/**
	 * Records the removal of a key if it is present in the original
	 * set or removes a previously inserted record trapped in this diff
	 * layer. If no key is found the call is ignored and returns false.
	 */
	@SuppressWarnings("unchecked")
	public boolean remove(@Nonnull Object key) {
		@SuppressWarnings("SuspiciousMethodCalls") final boolean originalContained = this.setDelegate.contains(key);
		final boolean wasInCreated = containsCreated((K) key);
		final boolean wasInRemoved = containsRemoved((K) key);

		// drop any pending creation (covers both fresh adds and substitution replacements)
		if (wasInCreated) {
			removeCreatedKey((K) key);
		}

		if (originalContained) {
			if (wasInRemoved && !wasInCreated) {
				// original already net-removed and no pending substitution to roll back -> no-op
				return false;
			}
			// either this is a removal of an original entry, or a rollback of a "replace" -
			// in both cases the original delegate position must end up removed
			registerRemovedKey((K) key);
			return true;
		}
		// not in delegate - only the pending creation (if any) had any effect
		return wasInCreated;
	}

	/**
	 * Detects whether an element identity-equal to {@code key} is already in {@link #setDelegate} but
	 * carries different content. Returns false for types that do not implement {@link ContentComparator}
	 * because for them {@link Object#equals} is content-aware and {@code setDelegate.contains} already
	 * implies equality.
	 */
	private boolean contentDiffersFromDelegate(@Nonnull K key) {
		if (!(key instanceof ContentComparator)) {
			return false;
		}
		final K existing = findIdentityEqual(this.setDelegate, key);
		return existing != null && ContentComparator.contentDiffers(existing, key);
	}

	/**
	 * If an identity-equal entry exists in the change layer with diverging content, substitutes it
	 * for the new instance. No-op for types whose equals semantics already imply content equality.
	 */
	private void replaceInCreatedIfContentDiffers(@Nonnull K key) {
		if (!(key instanceof ContentComparator)) {
			return;
		}
		final K existing = findIdentityEqual(getCreatedKeys(), key);
		if (existing != null && ContentComparator.contentDiffers(existing, key)) {
			final Set<K> created = getOrCreateCreatedKeys();
			created.remove(key); // removes identity-equal entry from the HashSet
			created.add(key);
		}
	}

	/**
	 * Returns the element in {@code haystack} that is identity-equal (by {@link Object#equals}) to
	 * {@code needle}, or {@code null} when none is present. A linear scan is required because the
	 * {@link Set} API exposes no way to retrieve the stored instance equal to a probe.
	 */
	@Nullable
	private K findIdentityEqual(@Nonnull Set<K> haystack, @Nonnull K needle) {
		for (K candidate : haystack) {
			if (needle.equals(candidate)) {
				return candidate;
			}
		}
		return null;
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
	 * Computes the new set originating from {@link #setDelegate} with
	 * all changes from this diff layer applied.
	 */
	@SuppressWarnings("unchecked")
	@Nonnull
	public Set<K> createMergedSet(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		final HashSet<K> copy = new HashSet<>(this.setDelegate.size());
		// iterate original set and copy all values from it
		for (K key : this.setDelegate) {
			// we need to always create copy - something in the
			// referenced object might have changed; even the removed
			// values need to be evaluated (to discard them from
			// transactional memory set)
			if (key instanceof TransactionalStateProducer) {
				key = (K) transactionalLayer.getStateCopyWithCommittedChanges(
					(TransactionalStateProducer<?>) key
				);
			}
			// except those that were removed
			if (!containsRemoved(key)) {
				copy.add(key);
			}
		}

		// iterate over inserted keys
		for (K key : getCreatedKeys()) {
			if (key instanceof TransactionalStateProducer) {
				key = (K) transactionalLayer.getStateCopyWithCommittedChanges((TransactionalStateProducer<?>) key);
			}
			copy.add(key);
		}

		return copy;
	}

	/**
	 * Registers a removed entry.
	 */
	public void registerRemovedKey(@Nonnull K key) {
		journalKey(key);
		getOrCreateRemovedKeys().add(key);
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
	 * Removes a previously created key in this transactional diff.
	 */
	void removeCreatedKey(@Nonnull K key) {
		journalKey(key);
		if (this.createdKeys != null) {
			this.createdKeys.remove(key);
		}
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
	 * Captures the current diff state of this layer into an independent memento.
	 *
	 * Both change sets are deep-copied into fresh `HashSet` instances so that later
	 * {@link #put}/{@link #remove}/{@link #clearAll} calls (which mutate them in place) cannot corrupt
	 * the captured memento. The lazy null-vs-empty distinction is preserved (a `null` set is captured as
	 * `null`, never as an empty set) so a restored layer keeps the allocation-free fast paths intact.
	 *
	 * The {@link #setDelegate} is the shared immutable baseline and is never mutated by this layer, so it
	 * is intentionally excluded from the memento. The {@code K} elements are captured by reference only -
	 * they are diff keys whose own transactional state (when they are
	 * {@link TransactionalLayerProducer}s) is governed by their own savepoints, not by this layer.
	 *
	 * @return an immutable memento holding deep copies of the two change sets
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
	 * Each change set is rebuilt from a fresh copy of the memento's set (or set to `null` when the
	 * memento captured `null`), so the same memento may be restored more than once without being aliased
	 * or mutated by subsequent layer operations. The {@link #setDelegate} is left untouched.
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
	 * Captures the current (pre-mutation) membership of {@code key} in both change sets and returns an inverse operation
	 * that restores exactly that membership. The inverse is ABSOLUTE (it forces the captured state rather than reversing
	 * a delta), so replaying several inverses for the same key in reverse order correctly lands on the earliest — i.e.
	 * the pre-savepoint — state. For {@link ContentComparator} keys the actual stored instance is captured (not merely a
	 * presence flag) so a same-key content substitution is reverted to the original instance.
	 *
	 * @param key the key whose pre-mutation membership is captured
	 * @return an inverse restoring {@code key}'s captured membership in {@link #createdKeys} / {@link #removedKeys}
	 */
	@Nonnull
	private Runnable captureKeyInverse(@Nonnull K key) {
		final boolean wasInRemoved = this.removedKeys != null && this.removedKeys.contains(key);
		final K createdInstance;
		if (this.createdKeys == null) {
			createdInstance = null;
		} else if (key instanceof ContentComparator) {
			// capture the exact stored instance so a content substitution reverts to the original one
			createdInstance = findIdentityEqual(this.createdKeys, key);
		} else {
			createdInstance = this.createdKeys.contains(key) ? key : null;
		}
		return () -> restoreKeyMembership(key, createdInstance, wasInRemoved);
	}

	/**
	 * Forces {@code key}'s membership in the two change sets back to the captured pre-mutation state. Container
	 * existence (the lazy null-vs-set invariant) is normalized separately by {@link #restore(SetChangesMemento)} after
	 * all per-key inverses have replayed.
	 *
	 * @param key             the key to restore
	 * @param createdInstance the instance that was stored in {@link #createdKeys} for this key, or `null` if absent
	 * @param wasInRemoved    whether the key was present in {@link #removedKeys}
	 */
	private void restoreKeyMembership(@Nonnull K key, @Nullable K createdInstance, boolean wasInRemoved) {
		if (createdInstance == null) {
			if (this.createdKeys != null) {
				this.createdKeys.remove(key);
			}
		} else {
			final Set<K> created = getOrCreateCreatedKeys();
			created.remove(key);          // drop any identity-equal current instance
			created.add(createdInstance); // reinstate the exact pre-mutation instance
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
