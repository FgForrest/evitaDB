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
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.map.MapChanges.ValueMerger;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.utils.Assert;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToLongFunction;

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
 * **The second mode: an explicitly supplied dirty-key set.** An owner that already knows which values its transaction
 * mutated — because it captured them from a source it trusts more than marks scattered across the mutation path — builds
 * the map through {@link #withExplicitDirtyKeyMerge(Map, Function)} and commits it through
 * {@link #createCopyWithMergedTransactionalMemory(MapChanges, TransactionalLayerMaintainer, Set, ValueMerger)}, passing
 * that set in. Everything above about marks applies to the DEFAULT mode only: for such a map an absent layer no longer
 * proves that nothing changed, so the inherited contract method's `null` early-out would be a silent data loss and is
 * therefore refused outright.
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
	 * producer values are committed on merge. Null when the concrete producer class is not statically known — see
	 * {@link #withExplicitDirtyKeyMerge(Map, Function)}.
	 */
	@Nullable private final Class<?> valueType;
	/**
	 * Converts the merged state produced by a value's
	 * {@link TransactionalLayerProducer#createCopyWithMergedTransactionalMemory} back into `V` (usually
	 * {@link Function#identity()} for values that merge to themselves).
	 */
	@Nonnull private final Function<Object, V> transactionalLayerWrapper;
	/**
	 * True when this map commits exclusively through
	 * {@link #createCopyWithMergedTransactionalMemory(MapChanges, TransactionalLayerMaintainer, Set, ValueMerger)} — the
	 * owner supplies the dirty-key set itself and never calls {@link #markValueMutated}. It turns the inherited
	 * {@link TransactionalLayerProducer} contract method into a hard failure, because for such a map an absent layer no
	 * longer proves that no value mutated: returning {@link #sealed()} there would silently drop every in-place mutation
	 * of the transaction.
	 */
	private final boolean explicitDirtyKeyMerge;

	/**
	 * Creates a producer-valued persistent transactional map that commits through the explicit-dirty-key entry point
	 * {@link #createCopyWithMergedTransactionalMemory(MapChanges, TransactionalLayerMaintainer, Set, ValueMerger)}
	 * rather than through {@link #markValueMutated}. Use it when the owner already knows — from a source it trusts more
	 * than marks scattered across the mutation path — exactly which values its transaction mutated.
	 *
	 * The value type is not asserted here: this variant exists for values whose concrete producer class is not statically
	 * known (an abstract base such as `EntityIndex`, whose subclasses are the producers), exactly like
	 * {@link TransactionalMap#TransactionalMap(Map, Function)}.
	 *
	 * @param source                    the initial entries (non-null; keys and values non-null)
	 * @param transactionalLayerWrapper function converting a value's raw merged state back into `V`
	 * @param <K> key type (non-null)
	 * @param <V> value type (non-null)
	 * @return a map whose commit is driven by an explicitly supplied dirty-key set
	 */
	@Nonnull
	public static <K, V> PersistentTransactionalProducerMap<K, V> withExplicitDirtyKeyMerge(
		@Nonnull Map<K, V> source,
		@Nonnull Function<Object, V> transactionalLayerWrapper
	) {
		return new PersistentTransactionalProducerMap<>(source, transactionalLayerWrapper);
	}

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
		this.explicitDirtyKeyMerge = false;
	}

	/**
	 * Backs {@link #withExplicitDirtyKeyMerge(Map, Function)} — see there for the contract.
	 *
	 * @param source                    the initial entries (non-null; keys and values non-null)
	 * @param transactionalLayerWrapper function converting a value's raw merged state back into `V`
	 */
	private PersistentTransactionalProducerMap(
		@Nonnull Map<K, V> source,
		@Nonnull Function<Object, V> transactionalLayerWrapper
	) {
		super(source);
		this.valueType = null;
		this.transactionalLayerWrapper = transactionalLayerWrapper;
		this.explicitDirtyKeyMerge = true;
	}

	/**
	 * {@inheritDoc}
	 *
	 * Adds this subclass's three fields to the inherited figure. All three contribute a slot and nothing more:
	 * `valueType` addresses a {@link Class} the JVM owns for the lifetime of its class loader, and
	 * `transactionalLayerWrapper` is a function the caller supplied and continues to own — sometimes a JVM-wide
	 * singleton such as {@link Function#identity()}, sometimes an instance of its own. Charging only the slot keeps
	 * this consistent with {@link TransactionalMap}, which holds the same two fields and treats them the same way;
	 * the difference either way is one small object per map.
	 */
	@Override
	public long getHeapSizeInBytes(
		@Nonnull ToLongFunction<? super K> keySizer,
		@Nonnull ToLongFunction<? super V> valueSizer
	) {
		final VMLayout layout = VMLayout.current();
		// the inherited id and state, then valueType / transactionalLayerWrapper / explicitDirtyKeyMerge
		return layout.sizeOfObject(Long.BYTES + 3L * layout.referenceSize() + 1L)
			+ getStateHeapSizeInBytes(keySizer, valueSizer);
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
		Assert.isPremiseValid(
			!this.explicitDirtyKeyMerge,
			"This map commits through an explicitly supplied dirty-key set - routing it through the ordinary " +
				"TransactionalLayerProducer contract would silently drop every in-place value mutation of the transaction!"
		);
		if (layer == null) {
			return sealed();
		}
		return ((ProducerMapChanges<K, V>) layer).createMergedChampMap(transactionalLayer);
	}

	/**
	 * Produces the next committed snapshot from a dirty-key set the caller supplies, resolving the committed value of
	 * every touched key through `valueMerger`. This is the commit path of a map created by
	 * {@link #withExplicitDirtyKeyMerge(Map, Function)}: it derives the next {@link ChampMap} from the previous one by
	 * path-copying only `removedKeys ∪ modifiedKeys ∪ dirtyKeys`, so an untouched value is neither merged nor even
	 * visited and its whole subtree is shared by reference.
	 *
	 * Unlike the {@link TransactionalLayerProducer} contract method this one is **not** routed through
	 * {@link TransactionalLayerMaintainer#getStateCopyWithCommittedChanges}, so the caller must dispose of this map's own
	 * diff layer itself — with {@link TransactionalLayerMaintainer#removeTransactionalMemoryLayerIfExists}, never with
	 * {@link #removeLayer}, which descends into every value and would undo the very walk this method exists to avoid.
	 * A forgotten disposal is reported by {@link TransactionalLayerMaintainer#verifyLayerWasFullySwept()} rather than
	 * silently dropping changes.
	 *
	 * A `null` layer means no key was added or removed — the values still need committing, so the dirty keys are
	 * path-copied straight onto the sealed snapshot.
	 *
	 * @param layer              this map's diff layer, or `null` when the key set did not change this transaction
	 * @param transactionalLayer the maintainer resolving committed state
	 * @param dirtyKeys          keys whose value mutated in place this transaction
	 * @param valueMerger        resolves the committed value of every touched key
	 * @return the committed snapshot, structurally sharing untouched nodes with the previous one
	 */
	@Nonnull
	public ChampMap<K, V> createCopyWithMergedTransactionalMemory(
		@Nullable MapChanges<K, V> layer,
		@Nonnull TransactionalLayerMaintainer transactionalLayer,
		@Nonnull Set<? extends K> dirtyKeys,
		@Nonnull ValueMerger<K, V> valueMerger
	) {
		if (layer != null) {
			return ((ProducerMapChanges<K, V>) layer).createMergedChampMap(transactionalLayer, dirtyKeys, valueMerger);
		}
		// no key changed this transaction - only the dirty values need resolving, everything else is shared as-is
		ChampMap<K, V> result = sealed();
		for (final K key : dirtyKeys) {
			final V value = result.get(key);
			if (value == null) {
				// the key left the map without a diff layer being created, which cannot happen: a removal always
				// creates one. Treat it as a broken dirty-key snapshot rather than dropping the mutation silently
				throw new GenericEvitaInternalError(
					"Dirty key `" + key + "` is not present in a map that recorded no key change this transaction!"
				);
			}
			final V committed = valueMerger.mergeSurviving(key, value);
			Assert.isPremiseValid(
				committed != null,
				() -> "Value merger returned NULL for surviving key `" + key + "` - persistent maps forbid null values!"
			);
			// share by identity: only path-copy when the committed instance actually differs from the held one
			if (committed != value) {
				result = result.updated(key, committed);
			}
		}
		return result;
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
		return this.valueType == null ?
			new ProducerMapChanges<>(delegate, this.transactionalLayerWrapper) :
			new ProducerMapChanges<K, V>(delegate, (Class) this.valueType, this.transactionalLayerWrapper);
	}

}
