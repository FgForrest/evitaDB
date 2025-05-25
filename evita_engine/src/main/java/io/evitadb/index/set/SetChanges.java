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
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.HashSet;
import java.util.Set;

/**
 * Contains combination of changes in a Set and removals made upon it. There is no other possible way
 * how to track removals in a map than to keep a set of keys that was removed in it.
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
	 * Contains set of removed keys.
	 */
	private final Set<K> removedKeys = new HashSet<>();
	/**
	 * Contains set of added keys.
	 */
	private final Set<K> createdKeys = new HashSet<>();

	public SetChanges(@Nonnull Set<K> setDelegate) {
		this.setDelegate = setDelegate;
	}

	/**
	 * Method records insertion of the record with particular key. The update is trapped within this object data.
	 */
	public boolean put(@Nonnull K key) {
		final boolean wasAdded;
		if (containsCreated(key)) {
			wasAdded = false;
		} else {
			final boolean isPartOfOriginal = this.setDelegate.contains(key);
			if (isPartOfOriginal) {
				wasAdded = false;
			} else {
				this.createdKeys.add(key);
				wasAdded = true;
			}
		}
		this.removedKeys.remove(key);
		return wasAdded;
	}

	/**
	 * Records the removal of certain key if it's present in the original set or removes previously inserted record
	 * trapped in this diff layer. If no key is found the call is ignored and returns false.
	 */
	@SuppressWarnings("unchecked")
	public boolean remove(Object key) {
		@SuppressWarnings("SuspiciousMethodCalls") final boolean originalContained = this.setDelegate.contains(key);
		if (originalContained && containsRemoved((K) key)) {
			// value has been already removed - report false and do nothing
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
	 * Computes the size of the set taking changes in this diff layer into an account.
	 */
	public int size() {
		return this.setDelegate.size() - this.removedKeys.size() + this.createdKeys.size();
	}

	/**
	 * Resolves whether the original set with applied changes from this diff layer would produce empty set.
	 */
	public boolean isEmpty() {
		if (this.removedKeys.isEmpty() && this.createdKeys.isEmpty()) {
			return this.setDelegate.isEmpty();
		} else {
			return size() == 0;
		}
	}

	/**
	 * Resolves whether the key is part of the original set or in this diff layer.
	 */
	@SuppressWarnings("unchecked")
	public boolean contains(Object o) {
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
	 * Creates an array with combining non removed keys from the original set with the created keys trapped in this
	 * memory layer.
	 */
	@Nonnull
	public <T> T[] toArray(@Nonnull T[] a) {
		int index = 0;
		// create array of requested size
		//noinspection unchecked
		final T[] resultArray = (T[]) Array.newInstance(a.getClass().getComponentType(), size());
		// iterate original map and copy all values from it
		for (K key : this.setDelegate) {
			// except those that were removed
			if (!containsRemoved(key)) {
				//noinspection unchecked
				resultArray[index++] = (T) key;
			}
		}

		// iterate over inserted or updated keys
		for (K key : getCreatedKeys()) {
			// update the value
			//noinspection unchecked
			resultArray[index++] = (T) key;
		}

		return resultArray;
	}

	/**
	 * Computes the new set originating from {@link #setDelegate} with applied all changes from this diff layer.
	 */
	@SuppressWarnings("unchecked")
	public Set<K> createMergedSet(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// create new hash set of requested size
		final HashSet<K> copy = new HashSet<>(this.setDelegate.size());
		// iterate original map and copy all values from it
		for (K key : this.setDelegate) {
			// we need to always create copy - something in the referenced object might have changed
			// even the removed values need to be evaluated (in order to discard them from transactional memory set)
			if (key instanceof TransactionalLayerProducer) {
				key = (K) transactionalLayer.getStateCopyWithCommittedChanges((TransactionalLayerProducer<?, ?>) key);
			}
			// except those that were removed
			if (!containsRemoved(key)) {
				copy.add(key);
			}
		}

		// iterate over inserted or updated keys
		for (K key : getCreatedKeys()) {
			// we need to always create copy - something in the referenced object might have changed
			if (key instanceof TransactionalLayerProducer) {
				key = (K) transactionalLayer.getStateCopyWithCommittedChanges((TransactionalLayerProducer<?, ?>) key);
			}
			// update the value
			copy.add(key);
		}

		return copy;
	}

	/**
	 * Registers a removed entry.
	 */
	public void registerRemovedKey(K key) {
		this.removedKeys.add(key);
	}

	/**
	 * Clears all changes recorded in this diff layer.
	 */
	void clearAll() {
		this.createdKeys.clear();
		this.removedKeys.addAll(this.setDelegate);
	}

	/**
	 * Returns true if the passed key was not part of {@link #setDelegate} but was added in this transactional memory
	 * diff.
	 */
	boolean containsCreated(K key) {
		return this.createdKeys.contains(key);
	}

	/**
	 * Removes a previously created key in this transactional diff.
	 */
	void removeCreatedKey(K key) {
		this.createdKeys.remove(key);
	}

	/**
	 * Returns set of all newly created keys that were not in the original {@link #setDelegate}.
	 */
	@Nonnull
	Set<K> getCreatedKeys() {
		return this.createdKeys;
	}


	/**
	 * Returns true if particular key is recorded to be removed.
	 */
	boolean containsRemoved(K key) {
		return this.removedKeys.contains(key);
	}

	/**
	 * Copies the changes from this layer to another one.
	 */
	void copyState(SetChanges<K> layer) {
		layer.createdKeys.addAll(this.createdKeys);
		layer.removedKeys.addAll(this.removedKeys);
	}

}
