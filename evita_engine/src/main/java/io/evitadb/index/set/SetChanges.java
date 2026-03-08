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
public class SetChanges<K> implements Serializable {
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
	 * Records the insertion of the given key. The update is trapped
	 * within this diff layer.
	 */
	public boolean put(@Nonnull K key) {
		final boolean wasRemoved = this.removedKeys != null && this.removedKeys.remove(key);
		if (containsCreated(key)) {
			return false;
		}
		final boolean isPartOfOriginal = this.setDelegate.contains(key);
		if (isPartOfOriginal) {
			return wasRemoved;
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
		if (originalContained && containsRemoved((K) key)) {
			// value has been already removed - report false
			return false;
		}
		final boolean wasRemoved;
		if (containsCreated((K) key)) {
			removeCreatedKey((K) key);
			wasRemoved = true;
		} else if (originalContained) {
			registerRemovedKey((K) key);
			wasRemoved = true;
		} else {
			wasRemoved = false;
		}
		return wasRemoved;
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

}
