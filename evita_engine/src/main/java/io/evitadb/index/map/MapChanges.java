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

import io.evitadb.core.transaction.memory.Snapshotable;
import io.evitadb.core.transaction.memory.TransactionalLayerCreator;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.map.ProducerMapChanges.ProducerMapChangesMemento;
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
import java.util.IdentityHashMap;
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
public class MapChanges<K, V>
	implements Serializable, Snapshotable<MapChanges.MapChangesMemento<K, V>> {
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
	 * Identity set of {@link TransactionalLayerProducer} values that were both CREATED and REMOVED within this same
	 * transaction. Such a key ends up in neither {@link #mapDelegate} nor {@link #modifiedKeys} (and
	 * {@link #createdKeyCount} is decremented back), so the commit-time sweep in
	 * {@link #createMergedMap(TransactionalLayerMaintainer)} (and {@link ProducerMapChanges#createMergedChampMap}) would
	 * never visit it — leaving its nested diff layer orphaned and failing the commit with
	 * `StaleTransactionMemoryException`. These instances are stashed here on removal and released at commit by
	 * {@link #releaseOrphanedCreatedThenRemovedLayers(TransactionalLayerMaintainer)} (survivor-guarded), which keeps the
	 * value's layer ALIVE for the rest of the transaction so read-after-remove callers still see their in-transaction
	 * state. Identity-based (`==`) on purpose: a producer owns its layer per-instance, so two content-equal instances
	 * (e.g. two empty bitmaps) are independent layer owners. Lazily allocated — null until the first such removal, so
	 * the common (no created-then-removed) path stays allocation-free.
	 */
	@Nullable private Set<Object> createdThenRemovedProducers;
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
	 * Exposes the value wrapper to producer-valued subclasses (see {@link ProducerMapChanges}) so they can commit nested
	 * {@link TransactionalLayerProducer} values. Null for the plain (non-producer) diff layer.
	 */
	@Nullable
	protected Function<Object, V> getTransactionalLayerWrapper() {
		return this.transactionalLayerWrapper;
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
				// the key was CREATED and is now REMOVED within this same transaction: after removal it lives in
				// neither the delegate nor the modified set, so the commit-time sweep would never visit it and its
				// nested diff layer would orphan. We do NOT release the layer eagerly here, because remove() returns
				// the value and callers legitimately read its live in-transaction state afterwards (e.g.
				// HierarchyIndex.makeOrphansRecursively iterates the removed TransactionalIntArray, then releases the
				// layer itself). Instead the discarded producer instance is stashed and released at commit time by
				// releaseOrphanedCreatedThenRemovedLayers - keeping the layer ALIVE for the rest of the transaction
				// while still guaranteeing it is swept (releaseLayer is idempotent, so a caller's explicit release is
				// harmless).
				originalValue = removeCreatedKey((K) key);
				if (originalValue instanceof TransactionalLayerProducer) {
					stashCreatedThenRemovedProducer(originalValue);
				}
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
				&& isInstanceNotReferencedBySurvivingKey(key, originalValue)
			) {
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
	 * **Why only the in-transaction diff layer ({@link #modifiedKeys}) is scanned — not {@link #mapDelegate}.** A
	 * producer value is owned by exactly one key; a committed/constructed map never aliases the same instance across
	 * keys:
	 *
	 * 1. Every producer value in {@link #mapDelegate} was minted as a fresh per-key copy — by a prior
	 *    {@link #createMergedMap}/{@link ProducerMapChanges#createMergedChampMap}
	 *    (`wrapper.apply(getStateCopyWithCommittedChanges(...))`) or by a from-storage constructor that builds one
	 *    instance per key — so two delegate keys can never share a reference.
	 * 2. Two *surviving* aliases would already fail the commit hard: the second
	 *    {@link TransactionalLayerMaintainer#getStateCopyWithCommittedChanges} on the same instance discards an
	 *    already-discarded layer (a premise-invalid assertion).
	 *
	 * The only place a surviving alias of `instance` can therefore appear is the in-transaction diff layer — a caller
	 * that stored the same instance under another key this transaction — which lives in {@link #modifiedKeys}. Scanning
	 * just that set is both correct and `O(modifiedKeys)` rather than `O(mapDelegate)`; the latter would silently
	 * degrade producer-map removals to `O(removed · N)` and defeat {@link ProducerMapChanges#createMergedChampMap}'s
	 * intended `O(Δ·log₃₂N)` commit.
	 *
	 * @param removedKey the key being removed (excluded from the survivor scan); pass `null` to exclude nothing
	 *                   (used by the created-then-removed stash sweep, where the instance no longer lives under any
	 *                   single key)
	 * @param instance   the producer instance whose continued reference is being tested
	 * @return `true` if no surviving key references the very same instance (i.e. its layer is safe to release)
	 */
	protected boolean isInstanceNotReferencedBySurvivingKey(@Nullable K removedKey, @Nullable Object instance) {
		// a surviving alias of `instance` can only live in the in-transaction diff layer (see method contract):
		// committed/constructed maps mint one producer instance per key, so mapDelegate never holds a cross-key alias
		for (Entry<K, V> modifiedEntry : this.modifiedKeys.entrySet()) {
			if (modifiedEntry.getValue() == instance && !Objects.equals(modifiedEntry.getKey(), removedKey)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Stashes a {@link TransactionalLayerProducer} value that was created and then removed within the same transaction,
	 * so its (possibly ALIVE) nested diff layer can be released at commit by
	 * {@link #releaseOrphanedCreatedThenRemovedLayers(TransactionalLayerMaintainer)}. See {@link #createdThenRemovedProducers}.
	 *
	 * @param producer the discarded producer instance to track for commit-time release
	 */
	private void stashCreatedThenRemovedProducer(@Nonnull Object producer) {
		if (this.createdThenRemovedProducers == null) {
			this.createdThenRemovedProducers = Collections.newSetFromMap(new IdentityHashMap<>(32));
		}
		this.createdThenRemovedProducers.add(producer);
	}

	/**
	 * Releases the nested diff layers of producer values that were CREATED and then REMOVED within this transaction (see
	 * {@link #createdThenRemovedProducers}). A stashed instance is released only when no surviving key still references
	 * the very same instance (identity-based), so a value re-inserted under another key — or shared with a surviving key
	 * — keeps its layer and is swept normally. Invoked at the end of both commit paths
	 * ({@link #createMergedMap(TransactionalLayerMaintainer)} and {@link ProducerMapChanges#createMergedChampMap}). The
	 * release is idempotent, so it is harmless if a caller already released the layer explicitly.
	 *
	 * @param transactionalLayer the maintainer used to drop the orphaned layers
	 */
	protected void releaseOrphanedCreatedThenRemovedLayers(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		if (this.createdThenRemovedProducers == null) {
			return;
		}
		for (final Object instance : this.createdThenRemovedProducers) {
			if (instance instanceof TransactionalLayerProducer<?, ?> transactionalLayerProducer
				&& isInstanceNotReferencedBySurvivingKey(null, instance)) {
				transactionalLayerProducer.removeLayer(transactionalLayer);
			}
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
				if (value instanceof TransactionalLayerProducer<?, ?> transactionalLayerProducer) {
					if (wasRemoved) {
						// release the removed value's transactional layer, but only when no surviving key still
						// references the very same instance. The decision must be identity-based (`==`): a
						// producer owns its layer per-instance, so two distinct instances with equal content
						// (e.g. two empty bitmaps) are independent layer owners. Using content equality here
						// would either orphan the removed instance's layer (when a content-equal instance
						// survives) or release a layer that a surviving key still needs.
						if (isInstanceNotReferencedBySurvivingKey(key, value)) {
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

		// release the layers of producers created-then-removed within this transaction (invisible to both loops above)
		releaseOrphanedCreatedThenRemovedLayers(transactionalLayer);

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
		if (this.createdThenRemovedProducers != null) {
			layer.createdThenRemovedProducers = Collections.newSetFromMap(
				new IdentityHashMap<>(this.createdThenRemovedProducers.size())
			);
			layer.createdThenRemovedProducers.addAll(this.createdThenRemovedProducers);
		}
	}

	/**
	 * Captures the current mutable diff state into an independent memento (see
	 * {@link Snapshotable#snapshot()}). The three mutable containers ({@link #removedKeys},
	 * {@link #modifiedKeys}, {@link #createdThenRemovedProducers}) are deep-copied so a later mutation of this layer
	 * cannot corrupt the memento, while {@link #createdKeyCount} is copied by value. Producer **values** held inside
	 * {@link #modifiedKeys} (and the instances stashed in {@link #createdThenRemovedProducers}) are captured BY
	 * REFERENCE only — their own nested diff layers are snapshotted by their own {@link Snapshotable}, coordinated by
	 * the maintainer-level savepoint. The immutable {@link #mapDelegate} baseline and the stateless
	 * {@link #transactionalLayerWrapper} are deliberately excluded — they are shared-immutable and never change during
	 * the transaction.
	 *
	 * @return a memento that {@link #restore(MapChangesMemento)} can use to reset this layer to its current state
	 */
	@Nonnull
	@Override
	public MapChangesMemento<K, V> snapshot() {
		return new BaseMapChangesMemento<>(
			new HashSet<>(this.removedKeys),
			new HashMap<>(this.modifiedKeys),
			this.createdKeyCount,
			this.createdThenRemovedProducers == null
				? null
				: copyIdentitySet(this.createdThenRemovedProducers)
		);
	}

	/**
	 * Resets this diff layer back to the exact state captured by the given memento (see
	 * {@link Snapshotable#restore(Object)}). The two `final` containers ({@link #removedKeys}, {@link #modifiedKeys})
	 * are reset IN PLACE via `clear()` + `addAll`/`putAll` (they cannot be reassigned), {@link #createdKeyCount} is
	 * reassigned by value, and {@link #createdThenRemovedProducers} is rebuilt as a fresh identity-backed set (or set
	 * to `null` when the memento captured `null`, preserving the lazy-allocation invariant). State is copied OUT of the
	 * memento so the same memento can be restored more than once. Producer values are restored BY REFERENCE only —
	 * their internal state is never touched here.
	 *
	 * @param memento a memento previously produced by {@link #snapshot()} on this same layer
	 */
	@Override
	public void restore(@Nonnull MapChangesMemento<K, V> memento) {
		final BaseMapChangesMemento<K, V> baseState = baseStateOf(memento);
		this.removedKeys.clear();
		this.removedKeys.addAll(baseState.removedKeys());
		this.modifiedKeys.clear();
		this.modifiedKeys.putAll(baseState.modifiedKeys());
		this.createdKeyCount = baseState.createdKeyCount();
		this.createdThenRemovedProducers = baseState.createdThenRemovedProducers() == null
			? null
			: copyIdentitySet(baseState.createdThenRemovedProducers());
	}

	/**
	 * Resolves the {@link BaseMapChangesMemento} carrying the inherited diff state from a memento that may be either the
	 * base memento itself or a {@link ProducerMapChanges.ProducerMapChangesMemento} wrapping it. This lets
	 * {@link #restore(MapChangesMemento)} reset the inherited fields regardless of which concrete memento the
	 * (possibly producer) subclass produced.
	 *
	 * @param memento the memento to unwrap
	 * @return the base memento carrying the inherited diff state
	 */
	@Nonnull
	protected BaseMapChangesMemento<K, V> baseStateOf(@Nonnull MapChangesMemento<K, V> memento) {
		if (memento instanceof BaseMapChangesMemento<K, V> baseMemento) {
			return baseMemento;
		} else if (memento instanceof ProducerMapChangesMemento<K, V> producerMemento) {
			// the producer memento wraps the inherited diff state produced by super.snapshot()
			return baseStateOf(producerMemento.baseState());
		} else {
			throw new GenericEvitaInternalError(
				"Unexpected MapChangesMemento implementation: " + memento.getClass().getName()
			);
		}
	}

	/**
	 * Copies the given set into a fresh identity-backed set ({@link Collections#newSetFromMap(Map)} over an
	 * {@link IdentityHashMap}), preserving the `==` membership semantics required by
	 * {@link #createdThenRemovedProducers}. A plain content-equality {@link HashSet} would conflate two content-equal
	 * but distinct producer-layer owners (e.g. two empty bitmaps) and corrupt the orphan-release decision.
	 *
	 * @param source the identity set to copy (elements captured by reference)
	 * @return a new identity-backed set holding the same element references
	 */
	@Nonnull
	private static Set<Object> copyIdentitySet(@Nonnull Set<Object> source) {
		final Set<Object> copy = Collections.newSetFromMap(new IdentityHashMap<>(source.size()));
		copy.addAll(source);
		return copy;
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
		// drop the layers of any created-then-removed producers stashed for the deferred commit-time release
		if (this.createdThenRemovedProducers != null) {
			for (final Object instance : this.createdThenRemovedProducers) {
				if (instance instanceof TransactionalLayerCreator<?> transactionalLayerCreator) {
					transactionalLayerCreator.removeLayer(transactionalLayer);
				}
			}
			this.createdThenRemovedProducers = null;
		}
		this.removedKeys.addAll(this.mapDelegate.keySet());
	}

	/**
	 * Opaque memento type produced by {@link MapChanges#snapshot()} and consumed by
	 * {@link MapChanges#restore(MapChangesMemento)}. It is a sealed hierarchy so that the producer-valued subclass can
	 * contribute its own memento ({@link ProducerMapChanges.ProducerMapChangesMemento}) while the inherited
	 * {@link Snapshotable Snapshotable<MapChangesMemento>} contract remains a single, stable type binding — the
	 * maintainer-level savepoint can therefore round-trip {@link Snapshotable#restore(Object) restore(snapshot())}
	 * polymorphically without losing the subclass-specific state.
	 *
	 * @param <K> key type
	 * @param <V> value type
	 */
	public sealed interface MapChangesMemento<K, V>
		permits BaseMapChangesMemento, ProducerMapChangesMemento {
	}

	/**
	 * Immutable memento capturing the mutable diff state of the (base) {@link MapChanges} layer at a single point in
	 * time, used by {@link MapChanges#snapshot()} / {@link MapChanges#restore(MapChangesMemento)} to support savepoint
	 * rollback.
	 *
	 * The collection fields hold independent deep copies of the layer's containers (so a later layer mutation cannot
	 * corrupt the memento); the producer **values** inside {@link #modifiedKeys} and the instances inside
	 * {@link #createdThenRemovedProducers} are held BY REFERENCE only (their own nested layers are governed by their own
	 * {@link Snapshotable}). The {@link #mapDelegate} baseline and the value wrapper are intentionally excluded — they
	 * are shared-immutable and never change during a transaction.
	 *
	 * @param removedKeys                 deep copy of {@link MapChanges#removedKeys}
	 * @param modifiedKeys                deep copy of {@link MapChanges#modifiedKeys} (values by reference)
	 * @param createdKeyCount             value copy of {@link MapChanges#createdKeyCount}
	 * @param createdThenRemovedProducers identity-backed copy of {@link MapChanges#createdThenRemovedProducers}, or
	 *                                    `null` when the layer had none (the lazy-allocation invariant is preserved)
	 * @param <K> key type
	 * @param <V> value type
	 */
	public record BaseMapChangesMemento<K, V>(
		@Nonnull Set<K> removedKeys,
		@Nonnull Map<K, V> modifiedKeys,
		int createdKeyCount,
		@Nullable Set<Object> createdThenRemovedProducers
	) implements MapChangesMemento<K, V> {
	}

}
