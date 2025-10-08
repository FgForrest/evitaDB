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

package io.evitadb.index.map;

import io.evitadb.core.transaction.memory.TransactionalLayerCreator;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import static io.evitadb.utils.CollectionUtils.createHashMap;

/**
 * Contains combination of changes in a Map and removals made upon it. There is no other possible way
 * how to track removals in a map than to keep a set of keys that was removed in it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2017
 */
@NotThreadSafe
public class MapChanges<K, V> implements Serializable {
	@Serial private static final long serialVersionUID = -6370910459056592080L;

	/**
	 * Contains reference to original immutable map.
	 */
	@Getter private final Map<K, V> mapDelegate;
	/**
	 * Contains set of removed keys.
	 */
	private final Set<K> removedKeys = new HashSet<>(8);
	/**
	 * Contains map of inserted or updated keys.
	 */
	private final Map<K, V> modifiedKeys = new HashMap<>(8);
	/**
	 * Contains count of inserted keys that were not present in original map.
	 */
	private int createdKeyCount;
	/**
	 * Function used to wrap result of {@link TransactionalLayerProducer#createCopyWithMergedTransactionalMemory(Object, TransactionalLayerMaintainer)}
	 * to a {@link TransactionalLayerProducer} instance.
	 */
	private final Function<Object, V> transactionalLayerWrapper;

	public MapChanges(@Nonnull Map<K, V> mapDelegate) {
		this.mapDelegate = mapDelegate;
		this.transactionalLayerWrapper = null;
	}

	/**
	 * Use this constructor if V implements TransactionalLayerProducer itself.
	 * @param mapDelegate original map
	 * @param transactionalLayerWrapper the function that wraps result of {@link TransactionalLayerProducer#createCopyWithMergedTransactionalMemory(Object, TransactionalLayerMaintainer)} into a V type
	 */
	public <S, T extends TransactionalLayerProducer<?, S>> MapChanges(
		@Nonnull Map<K, V> mapDelegate,
		@Nonnull Class<T> valueType,
		@Nonnull Function<S, V> transactionalLayerWrapper
	) {
		Assert.isTrue(TransactionalLayerProducer.class.isAssignableFrom(valueType), "Value type is expected to implement TransactionalLayerProducer!");
		this.mapDelegate = mapDelegate;
		//noinspection unchecked
		this.transactionalLayerWrapper = (Function<Object, V>) transactionalLayerWrapper;
	}

	/**
	 * Returns set of keys that were removed from the map.
	 */
	@Nonnull
	public Set<K> getRemovedKeys() {
		return Collections.unmodifiableSet(this.removedKeys);
	}

	/**
	 * Returns set of keys that were modified in  the map.
	 */
	@Nonnull
	public Map<K, V> getModifiedKeys() {
		return Collections.unmodifiableMap(this.modifiedKeys);
	}

	/**
	 * Computes the correct value for the passed key taking changes in this diff layer into an account.
	 */
	@Nullable
	@SuppressWarnings("unchecked")
	V get(Object key) {
		if (containsRemoved((K) key)) {
			return null;
		} else if (containsCreatedOrModified((K) key)) {
			return getCreatedOrModifiedValue((K) key);
		} else {
			//noinspection SuspiciousMethodCalls
			return this.mapDelegate.get(key);
		}
	}

	/**
	 * Records the removal of certain key if it's present in the original map or removes previously inserted record
	 * trapped in this diff layer (and {@link #createdKeyCount} is decremented). If no key is found the call is ignored
	 * and returns null.
	 */
	@SuppressWarnings({"unchecked", "SuspiciousMethodCalls"})
	@Nullable
	V remove(Object key) {
		final V originalValue;
		final boolean existing = this.mapDelegate.containsKey(key);
		if (existing && containsRemoved((K) key)) {
			// value has been already removed - report null and do nothing
			return null;
		}
		if (containsCreatedOrModified((K) key)) {
			originalValue = existing ? removeModifiedKey((K) key) : removeCreatedKey((K) key);
		} else {
			originalValue = this.mapDelegate.get(key);
		}
		if (existing) {
			registerRemovedKey((K) key);
		}
		return originalValue;
	}

	/**
	 * Method records insertion / update of the record with particular key. The update is trapped within this object
	 * data. If the record was not in original map the {@link #createdKeyCount} is incremented.
	 */
	@Nullable
	V put(K key, V value) {
		final V originalValue;
		if (containsCreatedOrModified(key)) {
			originalValue = registerModifiedKey(key, value);
		} else {
			originalValue = this.mapDelegate.get(key);
			if (this.mapDelegate.containsKey(key)) {
				registerModifiedKey(key, value);
			} else {
				registerCreatedKey(key, value);
			}
		}
		if (this.removedKeys.remove(key)) {
			if (originalValue instanceof TransactionalLayerProducer<?, ?> transactionalLayerProducer) {
				transactionalLayerProducer.removeLayer();
			}
		}
		return originalValue;
	}

	/**
	 * Resolves whether the key is part of the original map or in this diff layer.
	 */
	@SuppressWarnings("unchecked")
	boolean containsKey(Object key) {
		if (containsCreatedOrModified((K) key)) {
			return true;
		} else if (containsRemoved((K) key)) {
			return false;
		} else {
			//noinspection SuspiciousMethodCalls
			return this.mapDelegate.containsKey(key);
		}
	}

	/**
	 * Resolves whether the value is part of the original map or in this diff layer.
	 */
	boolean containsValue(Object value) {
		//noinspection unchecked
		if (this.modifiedKeys.containsValue((V) value)) {
			return true;
		} else {
			for (Entry<K, V> entry : this.mapDelegate.entrySet()) {
				if (Objects.equals(value, entry.getValue())) {
					return !containsRemoved(entry.getKey());
				}
			}
			return false;
		}
	}

	/**
	 * Decreases {@link #createdKeyCount}.
	 */
	void decreaseCreatedKeyCount() {
		this.createdKeyCount--;
	}

	/**
	 * Computes the size of the map taking changes in this diff layer into an account.
	 */
	int size() {
		return this.mapDelegate.size() - this.removedKeys.size() + this.createdKeyCount;
	}

	/**
	 * Resolves whether the original map with applied changes from this diff layer would produce empty map.
	 */
	boolean isEmpty() {
		if (this.removedKeys.isEmpty() && this.createdKeyCount == 0) {
			return this.mapDelegate.isEmpty();
		} else {
			return size() == 0;
		}
	}

	/**
	 * Computes the new map originating from {@link #mapDelegate} with applied all changes from this diff layer.
	 */
	@Nonnull
	HashMap<K, V> createMergedMap(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// create new hash map of requested size
		final HashMap<K, V> copy = createHashMap(this.mapDelegate.size());
		// iterate original map and copy all values from it
		for (Entry<K, V> entry : this.mapDelegate.entrySet()) {
			final K key = entry.getKey();
			if (!this.modifiedKeys.containsKey(key)) {
				final boolean wasRemoved = containsRemoved(key);
				// we need to always create copy - something in the referenced object might have changed
				// even the removed values need to be evaluated (in order to discard them from transactional memory set)
				if (key instanceof TransactionalLayerProducer) {
					throw new IllegalStateException("Transactional layer producer is not expected to be used as a key!");
				}
				V value = entry.getValue();
				final boolean wasValueRemoved = wasRemoved && !containsValue(value);
				if (value instanceof TransactionalLayerProducer<?, ?> transactionalLayerProducer) {
					if (wasValueRemoved) {
						transactionalLayerProducer.removeLayer(transactionalLayer);
					} else if (!wasRemoved) {
						value = this.transactionalLayerWrapper.apply(
							transactionalLayer.getStateCopyWithCommittedChanges(transactionalLayerProducer)
						);
					}
				}
				// except those that were removed
				if (!wasRemoved) {
					copy.put(key, value);
				}
			}
		}

		for (Entry<K, V> entry : this.modifiedKeys.entrySet()) {
			final K key = entry.getKey();
			// we need to always create copy - something in the referenced object might have changed
			if (key instanceof TransactionalLayerProducer) {
				throw new IllegalStateException("Transactional layer producer is not expected to be used as a key!");
			}
			V value = entry.getValue();
			if (value instanceof TransactionalLayerProducer) {
				value = this.transactionalLayerWrapper.apply(
					transactionalLayer.getStateCopyWithCommittedChanges((TransactionalLayerProducer<?, ?>) value)
				);
			}
			// update the value
			copy.put(key, value);
		}

		return copy;
	}

	/**
	 * Returns iterator over all inserted/updated entries.
	 */
	@Nonnull
	Iterator<Entry<K, V>> getCreatedOrModifiedValuesIterator() {
		return this.modifiedKeys.entrySet().iterator();
	}

	/**
	 * Returns true if particular key is recorded to be removed.
	 */
	boolean containsRemoved(K key) {
		return this.removedKeys.contains(key);
	}

	/**
	 * Returns true if particular key is recorded to be inserted or updated.
	 */
	boolean containsCreatedOrModified(K key) {
		return this.modifiedKeys.containsKey(key);
	}

	/**
	 * Returns inserted / updated value for particular key.
	 */
	V getCreatedOrModifiedValue(K key) {
		return this.modifiedKeys.get(key);
	}

	/**
	 * Registers an inserted entry.
	 */
	@Nullable
	V registerCreatedKey(K key, V value) {
		final V previous = this.modifiedKeys.put(key, value);
		this.createdKeyCount++;
		return previous;
	}

	/**
	 * Registers an updated entry.
	 */
	@Nullable
	V registerModifiedKey(K key, V value) {
		return this.modifiedKeys.put(key, value);
	}

	/**
	 * Registers a removed entry.
	 */
	void registerRemovedKey(K key) {
		this.removedKeys.add(key);
	}

	/**
	 * Removes previously registered inserted entry via. {@link #registerCreatedKey(Object, Object)}.
	 */
	@Nonnull
	V removeCreatedKey(K key) {
		final V previous = this.modifiedKeys.remove(key);
		this.createdKeyCount--;
		return previous;
	}

	/**
	 * Removes previously registered updated entry via. {@link #registerModifiedKey(Object, Object)}.
	 */
	V removeModifiedKey(K key) {
		return this.modifiedKeys.remove(key);
	}

	/**
	 * Copies the changes from this layer to another one.
	 */
	void copyState(@Nonnull MapChanges<K, V> layer) {
		layer.createdKeyCount = this.createdKeyCount;
		layer.removedKeys.addAll(this.removedKeys);
		layer.modifiedKeys.putAll(this.modifiedKeys);
	}

	/**
	 * Clears all changes recorded in this diff layer.
	 */
	void cleanAll(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.createdKeyCount = 0;
		final Iterator<Entry<K, V>> it = this.modifiedKeys.entrySet().iterator();
		while (it.hasNext()) {
			final Entry<K, V> entry = it.next();
			if (entry.getValue() instanceof TransactionalLayerCreator<?> transactionalLayerCreator) {
				transactionalLayerCreator.removeLayer(transactionalLayer);
			}
			it.remove();
		}
		this.removedKeys.addAll(this.mapDelegate.keySet());
	}

}
