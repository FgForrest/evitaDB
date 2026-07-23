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

import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalStateProducer;
import io.evitadb.dataType.champ.ChampMap;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Map;
import java.util.function.Function;

import static java.util.Optional.ofNullable;

/**
 * Producer-valued variant of {@link PersistentTransactionalMap}: a {@link ChampMap}-backed transactional map whose
 * values are themselves {@link TransactionalLayerProducer}s (e.g. {@link InvertedIndex}, {@link RangeIndex}).
 * It exists to give the heavy producer-valued sub-index maps the same cheap, structure-sharing commit that
 * {@link PersistentTransactionalMap} already gives plain-valued maps, **without** regressing that plain path — which
 * is preserved verbatim by subclassing rather than branching.
 *
 * **Why a separate class (the plain path stays pristine).** {@link PersistentTransactionalMap}'s commit is `O(Δ)` and
 * early-outs on a `null` layer (`return sealed()`), because a plain value cannot change without a key-level `put`. A
 * producer value, by contrast, mutates through **its own** diff layer — invisibly to this map's {@link MapChanges}. The
 * gap is closed by explicit declaration: every value-mutation site calls {@link #markValueMutated}, which records the
 * key AND creates this map's layer. So the same `null`-layer early-out is preserved (a `null` layer now provably means
 * no put/remove and no value mutation), and commit walks only the keys that actually changed.
 *
 * This class overrides only {@link #createLayer} (attach the value wrapper via {@link ProducerMapChanges}),
 * {@link #createCopyWithMergedTransactionalMemory} (fold the diff), {@link #markValueMutated} (declare an in-place
 * mutation), and {@link #removeLayer} (release nested producer layers on rollback). Everything else — the two-state
 * thaw/seal warm-up buffer, the read/write surface, `clone`, serialization — is inherited unchanged.
 *
 * **The Δ-union commit.** {@link ProducerMapChanges#createMergedChampMap} derives the next version from the previous
 * immutable {@link ChampMap} by path-copying only the keys in `removedKeys ∪ modifiedKeys ∪ valueMutatedKeys`. A
 * producer that was not mutated is never visited, so its node is shared by reference, not rebuilt. Commit therefore
 * costs true `O(Δ·log₃₂ N)` — no per-value probe walk — versus the plain
 * {@link io.evitadb.index.map.TransactionalMap}, which rebuilds a fresh `O(N)` {@link java.util.HashMap} every commit.
 *
 * **Forgotten-mark safety (loud, not silent).** If a value mutates but its key is never marked (and nothing else
 * touched the map), this layer is never created, the `null` early-out returns {@link #sealed()} without sweeping the
 * value, and the maintainer's `verifyLayerWasFullySwept` throws a `StaleTransactionMemoryException` at commit. A missed
 * mark is thus a hard test failure, never silent staleness — see {@link ProducerMapChanges}.
 *
 * Constraints inherited from {@link ChampMap}: **no `null` keys or values** (fail-fast) and **unordered** iteration.
 * Thread-safe for concurrent readers of a published snapshot, not for concurrent non-transactional writers.
 *
 * @param <K> key type (non-null)
 * @param <V> value type (non-null, a {@link TransactionalLayerProducer})
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ThreadSafe
public class PersistentTransactionalProducerMap<K, V> extends PersistentTransactionalMap<K, V> {
	@Serial private static final long serialVersionUID = -2245098117515227782L;

	/**
	 * Concrete class of the {@link TransactionalLayerProducer} value type, used to configure the diff layer so nested
	 * producer values are committed on merge.
	 */
	private final Class<?> valueType;
	/**
	 * Converts the merged state produced by a value's
	 * {@link TransactionalLayerProducer#createCopyWithMergedTransactionalMemory} back into `V` (usually
	 * {@link Function#identity()} for values that merge to themselves).
	 */
	@Nonnull private final Function<Object, V> transactionalLayerWrapper;

	/**
	 * Creates a producer-valued persistent transactional map seeded with the entries of `source`. The constructor
	 * adopts an existing {@link ChampMap} sealed in `O(1)` (the commit path passes the just-derived snapshot here),
	 * otherwise copies into a private mutable buffer — exactly like {@link PersistentTransactionalMap}.
	 *
	 * @param source                    the initial entries (non-null; keys and values non-null)
	 * @param valueType                 concrete class of the transactional value producer
	 * @param transactionalLayerWrapper function converting a value's merged state back into `V`
	 * @param <S> the state type produced by the value's transactional layer
	 * @param <T> the concrete producer type of the value
	 */
	public <S, T extends TransactionalStateProducer<S>> PersistentTransactionalProducerMap(
		@Nonnull Map<K, V> source,
		@Nonnull Class<T> valueType,
		@Nonnull Function<S, V> transactionalLayerWrapper
	) {
		super(source);
		Assert.isTrue(
			TransactionalStateProducer.class.isAssignableFrom(valueType),
			"Value type is expected to implement TransactionalStateProducer!"
		);
		this.valueType = valueType;
		//noinspection unchecked
		this.transactionalLayerWrapper = (Function<Object, V>) transactionalLayerWrapper;
	}

	/**
	 * {@inheritDoc}
	 *
	 * Seals the state and creates a {@link MapChanges} diff layer backed by the immutable snapshot and configured with
	 * the value wrapper, so nested producer values are committed on merge.
	 */
	@Nonnull
	@Override
	public MapChanges<K, V> createLayer() {
		return newProducerLayer(sealed());
	}

	/**
	 * {@inheritDoc}
	 *
	 * Produces the next committed snapshot by folding the diff layer onto the sealed base via
	 * {@link ProducerMapChanges#createMergedChampMap}, sharing all untouched structure.
	 *
	 * A `null` layer short-circuits to {@link #sealed()} (nothing changed): an in-place value mutation cannot go unseen,
	 * because every mutation site declares its key through {@link #markValueMutated}, which CREATES this layer. So a
	 * `null` layer provably means no key-level put/remove AND no marked value mutation. The safety net
	 * for a FORGOTTEN mark is loud, not silent: the unmarked value still holds its own diff layer, this early-out skips
	 * sweeping it, and the maintainer's `verifyLayerWasFullySwept` then throws a `StaleTransactionMemoryException` at
	 * commit (see {@link ProducerMapChanges}).
	 */
	@Nonnull
	@Override
	public Map<K, V> createCopyWithMergedTransactionalMemory(
		@Nullable MapChanges<K, V> layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		if (layer == null) {
			return sealed();
		}
		return ((ProducerMapChanges<K, V>) layer).createMergedChampMap(transactionalLayer);
	}

	/**
	 * Declares that the producer value held under `key` was mutated in place this transaction — through the value's own
	 * diff layer, invisible to this map's put/remove tracking. This both records the key for the `O(Δ)` commit walk and,
	 * crucially, CREATES this map's transactional layer, so the {@link #createCopyWithMergedTransactionalMemory} null
	 * early-out is only ever taken when genuinely nothing changed. Outside a transaction (no active layer) the call is a
	 * no-op, because mutations then apply directly to the published structure.
	 *
	 * Callers MUST invoke this from every value-mutation path (and only those — never on a read), or the forgotten key's
	 * value layer orphans and surfaces as a `StaleTransactionMemoryException` at commit.
	 *
	 * @param key the key whose value mutated in place
	 */
	public void markValueMutated(@Nonnull K key) {
		final MapChanges<K, V> layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
		if (layer != null) {
			((ProducerMapChanges<K, V>) layer).markValueMutated(key);
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * Removes this map's own diff layer and releases the diff-held producer values (via `cleanAll`), then releases the
	 * layer of every producer value in the committed snapshot — mirroring {@link TransactionalMap#removeLayer} so a
	 * rollback leaves no orphaned nested layer.
	 */
	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		final MapChanges<K, V> changes = transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		ofNullable(changes).ifPresent(it -> it.cleanAll(transactionalLayer));
		for (final Entry<K, V> entry : sealed().entrySet()) {
			final V value = entry.getValue();
			if (value instanceof TransactionalStateProducer<?> transactionalLayerProducer) {
				transactionalLayerProducer.removeLayer(transactionalLayer);
			}
		}
	}

	/**
	 * Creates a producer-configured {@link ProducerMapChanges} diff over the given (sealed) delegate. The raw-type cast
	 * mirrors {@link TransactionalMap#createLayer}: the stored `valueType`/wrapper carry the producer typing that the
	 * generic {@link ProducerMapChanges} constructor requires.
	 *
	 * @param delegate the sealed immutable snapshot to diff against
	 * @return a fresh producer-valued diff layer
	 */
	@Nonnull
	@SuppressWarnings({"unchecked", "rawtypes"})
	private MapChanges<K, V> newProducerLayer(@Nonnull Map<K, V> delegate) {
		return new ProducerMapChanges<K, V>(delegate, (Class) this.valueType, this.transactionalLayerWrapper);
	}

}
