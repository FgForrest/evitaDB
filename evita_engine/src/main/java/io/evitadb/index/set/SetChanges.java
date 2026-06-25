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
			if (key instanceof TransactionalLayerProducer) {
				key = (K) transactionalLayer.getStateCopyWithCommittedChanges(
					(TransactionalLayerProducer<?, ?>) key
				);
			}
			// except those that were removed
			if (!containsRemoved(key)) {
				copy.add(key);
			}
		}

		// iterate over inserted keys
		for (K key : getCreatedKeys()) {
			if (key instanceof TransactionalLayerProducer) {
				key = (K) transactionalLayer.getStateCopyWithCommittedChanges((TransactionalLayerProducer<?, ?>) key);
			}
			copy.add(key);
		}

		return copy;
	}

	/**
	 * Registers a removed entry.
	 */
	public void registerRemovedKey(@Nonnull K key) {
		getOrCreateRemovedKeys().add(key);
	}

	/**
	 * Marks all delegate keys as removed and clears the created set.
	 */
	void clearAll() {
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
	 * Copies the changes from this layer to another one.
	 */
	void copyState(@Nonnull SetChanges<K> layer) {
		if (this.createdKeys != null && !this.createdKeys.isEmpty()) {
			layer.getOrCreateCreatedKeys().addAll(this.createdKeys);
		}
		if (this.removedKeys != null && !this.removedKeys.isEmpty()) {
			layer.getOrCreateRemovedKeys().addAll(this.removedKeys);
		}
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
		return new SetChangesMemento<>(
			this.createdKeys == null ? null : new HashSet<>(this.createdKeys),
			this.removedKeys == null ? null : new HashSet<>(this.removedKeys)
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
		this.createdKeys = memento.created() == null ? null : new HashSet<>(memento.created());
		this.removedKeys = memento.removed() == null ? null : new HashSet<>(memento.removed());
	}

	/**
	 * Immutable memento holding deep copies of the two lazily-allocated change sets of a
	 * {@link SetChanges} layer. Both fields are nullable to faithfully preserve the layer's
	 * null-vs-empty lazy-allocation invariant. The baseline {@link SetChanges#setDelegate} is shared and
	 * immutable, so it is deliberately not part of the memento.
	 *
	 * @param created the created-keys change set, or `null` when none were recorded
	 * @param removed the removed-keys change set, or `null` when none were recorded
	 * @param <K>     the type of the keys tracked by the set
	 */
	public record SetChangesMemento<K>(
		@Nullable Set<K> created,
		@Nullable Set<K> removed
	) {
	}

}
