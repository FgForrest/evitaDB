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
 * STM diff layer for {@link TransactionalMap}. Records all insertions, updates, and removals applied within
 * a transaction so that the original delegate map remains unchanged. On commit, changes are merged via
 * {@link #createMergedMap(TransactionalLayerMaintainer)}; on rollback the layer is simply discarded.
 *
 * There is no other possible way to track removals in a map than to keep a set of removed keys — this class
 * maintains that set alongside a map of created/modified keys and a count of newly inserted entries.
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
		Assert.isTrue(
			TransactionalLayerProducer.class.isAssignableFrom(valueType),
			"Value type is expected to implement TransactionalLayerProducer!"
		);
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
	 * Returns set of keys that were modified in the map.
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
	V get(@Nonnull Object key) {
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
	V remove(@Nonnull Object key) {
		final V originalValue;
		final boolean existing = this.mapDelegate.containsKey(key);
		if (existing && containsRemoved((K) key)) {
			// value has been already removed - report null and do nothing
			return null;
		}
		if (containsCreatedOrModified((K) key)) {
			if (existing) {
				originalValue = removeModifiedKey((K) key);
			} else {
				// the key was created (and possibly mutated) earlier in this same transaction and is now being
				// removed before commit — it will never be visited by createMergedMap (it is neither in the
				// delegate nor in modifiedKeys after this call), so we must release the dropped instance's layer
				// here to avoid orphaning it. Release is identity-based: only when no surviving key still holds
				// the very same instance.
				final V removedValue = removeCreatedKey((K) key);
				if (removedValue instanceof TransactionalLayerProducer<?, ?> transactionalLayerProducer
					&& !isInstanceReferencedBySurvivingKey((K) key, removedValue)) {
					transactionalLayerProducer.removeLayer();
				}
				originalValue = removedValue;
			}
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
	V put(@Nonnull K key, @Nullable V value) {
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
			// the key was removed earlier in this transaction and is now being re-inserted with a (potentially)
			// different value — the original instance is discarded, so release its layer. The release is
			// identity-based: keep the layer if some surviving key still references the very same instance.
			if (originalValue instanceof TransactionalLayerProducer<?, ?> transactionalLayerProducer
				&& originalValue != value
				&& !isInstanceReferencedBySurvivingKey(key, originalValue)) {
				transactionalLayerProducer.removeLayer();
			}
		}
		return originalValue;
	}

	/**
	 * Resolves whether the key is part of the original map or in this diff layer.
	 */
	@SuppressWarnings("unchecked")
	boolean containsKey(@Nonnull Object key) {
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
	boolean containsValue(@Nullable Object value) {
		//noinspection unchecked
		if (this.modifiedKeys.containsValue((V) value)) {
			return true;
		} else {
			for (Entry<K, V> entry : this.mapDelegate.entrySet()) {
				if (Objects.equals(value, entry.getValue())) {
					return !containsRemoved(entry.getKey()) && !containsCreatedOrModified(entry.getKey());
				}
			}
			return false;
		}
	}

	/**
	 * Resolves — by **instance identity** (`==`), not content equality — whether the given producer instance is
	 * still referenced by a key that survives the commit. A surviving reference means the instance's transactional
	 * layer will be (or already has been) swept normally via
	 * {@link TransactionalLayerProducer#createCopyWithMergedTransactionalMemory(Object, TransactionalLayerMaintainer)},
	 * so it must not be released as part of removing `removedKey`.
	 *
	 * Identity is essential: a {@link TransactionalLayerProducer} owns its diff layer per-instance, so two distinct
	 * instances with equal content are independent layer owners. Relying on {@link Object#equals(Object)} here would
	 * conflate ownership with content and either orphan a layer or release one that is still needed.
	 *
	 * @param removedKey the key being removed (excluded from the survivor scan)
	 * @param instance   the producer instance whose continued reference is being tested
	 * @return `true` if some surviving key references the very same instance
	 */
	private boolean isInstanceReferencedBySurvivingKey(@Nonnull K removedKey, @Nullable Object instance) {
		// any created/modified key holds a value that will be present in the committed map
		for (Entry<K, V> modifiedEntry : this.modifiedKeys.entrySet()) {
			if (modifiedEntry.getValue() == instance && !Objects.equals(modifiedEntry.getKey(), removedKey)) {
				return true;
			}
		}
		// any delegate key (other than the removed one) that is neither removed nor overridden survives the commit
		for (Entry<K, V> delegateEntry : this.mapDelegate.entrySet()) {
			final K delegateKey = delegateEntry.getKey();
			if (Objects.equals(delegateKey, removedKey)) {
				continue;
			}
			if (delegateEntry.getValue() == instance
				&& !containsRemoved(delegateKey)
				&& !containsCreatedOrModified(delegateKey)) {
				return true;
			}
		}
		return false;
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
				if (value instanceof TransactionalLayerProducer<?, ?> transactionalLayerProducer) {
					if (wasRemoved) {
						// release the removed value's transactional layer, but only when no surviving key still
						// references the very same instance. The decision must be identity-based (`==`): a
						// producer owns its layer per-instance, so two distinct instances with equal content
						// (e.g. two empty bitmaps) are independent layer owners. Using content equality here
						// would either orphan the removed instance's layer (when a content-equal instance
						// survives) or release a layer that a surviving key still needs.
						if (!isInstanceReferencedBySurvivingKey(key, value)) {
							transactionalLayerProducer.removeLayer(transactionalLayer);
						}
					} else {
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
			if (value instanceof TransactionalLayerProducer<?, ?> transactionalLayerProducer) {
				value = this.transactionalLayerWrapper.apply(
					transactionalLayer.getStateCopyWithCommittedChanges(transactionalLayerProducer)
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
	boolean containsRemoved(@Nonnull K key) {
		return this.removedKeys.contains(key);
	}

	/**
	 * Returns true if particular key is recorded to be inserted or updated.
	 */
	boolean containsCreatedOrModified(@Nonnull K key) {
		return this.modifiedKeys.containsKey(key);
	}

	/**
	 * Returns inserted / updated value for particular key.
	 */
	@Nullable
	V getCreatedOrModifiedValue(@Nonnull K key) {
		return this.modifiedKeys.get(key);
	}

	/**
	 * Registers an inserted entry.
	 */
	@Nullable
	V registerCreatedKey(@Nonnull K key, @Nullable V value) {
		final V previous = this.modifiedKeys.put(key, value);
		this.createdKeyCount++;
		return previous;
	}

	/**
	 * Registers an updated entry.
	 */
	@Nullable
	V registerModifiedKey(@Nonnull K key, @Nullable V value) {
		return this.modifiedKeys.put(key, value);
	}

	/**
	 * Registers a removed entry.
	 */
	void registerRemovedKey(@Nonnull K key) {
		this.removedKeys.add(key);
	}

	/**
	 * Removes previously registered inserted entry via {@link #registerCreatedKey(Object, Object)}.
	 */
	@Nullable
	V removeCreatedKey(@Nonnull K key) {
		final V previous = this.modifiedKeys.remove(key);
		this.createdKeyCount--;
		return previous;
	}

	/**
	 * Removes previously registered updated entry via {@link #registerModifiedKey(Object, Object)}.
	 */
	@Nullable
	V removeModifiedKey(@Nonnull K key) {
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
