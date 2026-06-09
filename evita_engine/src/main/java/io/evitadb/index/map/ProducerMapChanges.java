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

import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.dataType.champ.ChampMap;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Producer-valued STM diff layer for {@link PersistentTransactionalProducerMap}: the {@link ChampMap}-emitting sibling
 * of the plain {@link MapChanges}. It is kept as a SEPARATE subclass — rather than folding producer concerns into the
 * widely-instantiated {@link MapChanges} — so the plain map path stays completely free of producer-only state and API.
 * Only {@link PersistentTransactionalProducerMap} ever constructs this class.
 *
 * **The dirty-key set (`valueMutatedKeys`).** A producer value mutates through its OWN diff layer
 * (`map.get(k).addRecord()`), which is a read as far as this layer's created/modified/{@link #getRemovedKeys removed}
 * tracking is concerned — so an in-place value mutation leaves no trace here. Callers on
 * the mutation path therefore declare it explicitly via {@link PersistentTransactionalProducerMap#markValueMutated},
 * which records the key in {@link #valueMutatedKeys}. {@link #createMergedChampMap} then folds the new committed snapshot
 * by walking only the union `removedKeys ∪ modifiedKeys ∪ valueMutatedKeys` — true `O(Δ·log₃₂ N)` work — instead of
 * probing every value.
 *
 * **Why a missed mark is safe (loud, not silent).** If a value mutated but its key was not marked (and no key-level
 * put/remove happened either), this layer is never created, so {@link PersistentTransactionalProducerMap} short-circuits
 * its commit to {@link PersistentTransactionalProducerMap#sealed()} and never sweeps the mutated value's layer. That
 * orphaned layer is caught at commit by
 * {@link TransactionalLayerMaintainer the maintainer}'s `verifyLayerWasFullySwept`, which throws a
 * {@code StaleTransactionMemoryException}. A forgotten mark is therefore a hard, immediately-visible test failure — never
 * silent staleness.
 *
 * @param <K> key type (non-null)
 * @param <V> value type (non-null, a {@link TransactionalLayerProducer})
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NotThreadSafe
public class ProducerMapChanges<K, V> extends MapChanges<K, V> {
	@Serial private static final long serialVersionUID = 6573611387090950935L;

	/**
	 * Keys whose producer value was mutated in place this transaction (through the value's own diff layer, invisible to
	 * this layer's put/remove tracking). Populated via {@link #markValueMutated}, consumed by
	 * {@link #createMergedChampMap}. Per-transaction state: discarded with the layer on rollback.
	 */
	private final Set<K> valueMutatedKeys = new HashSet<>(8);

	/**
	 * Creates a producer-valued diff layer over the given (sealed) immutable snapshot.
	 *
	 * @param mapDelegate               the sealed immutable snapshot to diff against
	 * @param valueType                 concrete class of the transactional value producer
	 * @param transactionalLayerWrapper function converting a value's merged state back into `V`
	 * @param <S> the state type produced by the value's transactional layer
	 * @param <T> the concrete producer type of the value
	 */
	public <S, T extends TransactionalLayerProducer<?, S>> ProducerMapChanges(
		@Nonnull Map<K, V> mapDelegate,
		@Nonnull Class<T> valueType,
		@Nonnull Function<S, V> transactionalLayerWrapper
	) {
		super(mapDelegate, valueType, transactionalLayerWrapper);
	}

	/**
	 * Records that the producer value held under `key` was mutated in place this transaction, so
	 * {@link #createMergedChampMap} visits it (and sweeps its layer) at commit. Marking a key that was also created,
	 * replaced, or removed is harmless — the union walk deduplicates and applies the higher-precedence change.
	 *
	 * @param key the key whose value mutated in place
	 */
	void markValueMutated(@Nonnull K key) {
		this.valueMutatedKeys.add(key);
	}

	/**
	 * {@inheritDoc}
	 *
	 * Also carries the {@link #valueMutatedKeys} dirty-key set into the target layer when it is itself a
	 * {@link ProducerMapChanges} (the clone path always creates a matching producer layer).
	 */
	@Override
	void copyState(@Nonnull MapChanges<K, V> layer) {
		super.copyState(layer);
		if (layer instanceof ProducerMapChanges<K, V> producerLayer) {
			producerLayer.valueMutatedKeys.addAll(this.valueMutatedKeys);
		}
	}

	/**
	 * Computes the next committed {@link ChampMap} snapshot from the previous one (the {@link #getMapDelegate delegate},
	 * which MUST already be a {@link ChampMap} — {@link PersistentTransactionalProducerMap} seals before creating the
	 * layer) by path-copying ONLY the keys touched this transaction, sharing every untouched subtree with the
	 * predecessor. Unlike {@link MapChanges#createMergedMap}, which rebuilds a fresh `O(N)` {@link java.util.HashMap},
	 * this walks just the union `removedKeys ∪ modifiedKeys ∪ valueMutatedKeys`, giving true `O(Δ·log₃₂ N)` work.
	 *
	 * Precedence is removed > modified > valueMutated (the sets are disjoint for removed/modified by construction;
	 * a value-mutated key that was also removed or replaced is skipped here and handled by the higher-precedence pass).
	 * A producer value whose committed state is identical **by reference** to the held one — an
	 * {@link io.evitadb.index.invertedIndex.InvertedIndex} or {@link io.evitadb.index.range.RangeIndex} returns `this`
	 * when not mutated — is left in place, so its node is shared rather than rebuilt.
	 *
	 * @param transactionalLayer the maintainer used to commit nested producer values
	 * @return the next committed {@link ChampMap}, structurally sharing untouched nodes with the previous snapshot
	 */
	@Nonnull
	ChampMap<K, V> createMergedChampMap(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// start from the previous immutable snapshot and share everything; only the touched keys are path-copied below
		ChampMap<K, V> result = (ChampMap<K, V>) getMapDelegate();
		final Function<Object, V> wrapper = Objects.requireNonNull(getTransactionalLayerWrapper());

		// 1) removals (highest precedence): release the removed producer's layer (survivor-guarded) and drop the key
		for (final K key : getRemovedKeys()) {
			if (key instanceof TransactionalLayerProducer) {
				throw new IllegalStateException("Transactional layer producer is not expected to be used as a key!");
			}
			final V value = getMapDelegate().get(key);
			if (value instanceof TransactionalLayerProducer<?, ?> transactionalLayerProducer
				&& isInstanceNotReferencedBySurvivingKey(key, value)) {
				// release the removed value's layer, but only when no surviving key still references the very same
				// instance (identity-based, exactly as in createMergedMap)
				transactionalLayerProducer.removeLayer(transactionalLayer);
			}
			result = result.removed(key);
		}

		// 2) inserts and replacements
		final Iterator<Entry<K, V>> modifiedIt = getCreatedOrModifiedValuesIterator();
		while (modifiedIt.hasNext()) {
			final Entry<K, V> entry = modifiedIt.next();
			final K key = entry.getKey();
			if (key instanceof TransactionalLayerProducer) {
				throw new IllegalStateException("Transactional layer producer is not expected to be used as a key!");
			}
			V value = entry.getValue();
			if (value instanceof TransactionalLayerProducer<?, ?> transactionalLayerProducer) {
				value = wrapper.apply(
					transactionalLayer.getStateCopyWithCommittedChanges(transactionalLayerProducer)
				);
			}
			result = result.updated(key, value);
		}

		// 3) in-place value mutations not already covered by a removal or replacement above
		for (final K key : this.valueMutatedKeys) {
			if (containsRemoved(key) || containsCreatedOrModified(key)) {
				continue;
			}
			final V value = getMapDelegate().get(key);
			if (value instanceof TransactionalLayerProducer<?, ?> transactionalLayerProducer) {
				final V committed = wrapper.apply(
					transactionalLayer.getStateCopyWithCommittedChanges(transactionalLayerProducer)
				);
				// share by identity: only path-copy when the committed instance actually differs from the held one
				if (committed != value) {
					result = result.updated(key, committed);
				}
			}
		}

		// 4) release layers of producers created-then-removed within this transaction (in none of the sets walked above)
		releaseOrphanedCreatedThenRemovedLayers(transactionalLayer);

		return result;
	}

}
