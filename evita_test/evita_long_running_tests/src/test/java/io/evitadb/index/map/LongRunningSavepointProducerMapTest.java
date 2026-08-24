/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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
import io.evitadb.core.transaction.memory.AbstractSavepointFuzzTest;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalStateProducer;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;

/**
 * Generational randomized backfill proof that {@code ProducerMapChanges} — the producer-valued
 * {@link PersistentTransactionalProducerMap} diff layer — snapshots and restores correctly under a per-entity savepoint.
 * It extends {@code MapChanges} (already covered by {@code LongRunningSavepointFuzzFrameworkTest} via
 * {@link io.evitadb.index.map.TransactionalMap}) with a producer-only {@code valueMutatedKeys} dirty-key set, populated
 * by {@link PersistentTransactionalProducerMap#markValueMutated}. This test exercises both halves: the inherited map
 * put/remove diff AND the dirty-key set.
 *
 * The map values are minimal layer-less identity-merge {@link MapValue} producers (mirroring the
 * {@code Function.identity()} producer values production uses, e.g. a per-key {@code ChainIndex}); only the map's own
 * diff and dirty-key bookkeeping are under test, not a value's own diff. Each generation rebuilds a fresh map, applies a
 * random baseline batch (must survive), then a random in-savepoint batch with a non-vacuous marker (must revert on
 * rollback / be kept on commit). The oracle pairs the map contents with the dirty-key set (read reflectively), so a
 * dirty key that failed to revert is caught. The transaction is committed at the end so the layer-sweep verification
 * proves the restore left no dangling layer. The run is time-bounded; the random seed is echoed on failure for
 * deterministic reproduction.
 *
 * The scenario is declared once and run by {@link AbstractSavepointFuzzTest} in BOTH phases: the transactional
 * savepoint described above, and the WARM_UP savepoint where the same writes land straight on the delegate
 * structures and are rewound from the inverses they journal themselves. See that class for the shape of one
 * generation, for the mid-savepoint read every case is asserted through, and for why the warm-up half runs
 * exclusively.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ProducerMap savepoint rollback/commit backfill (generational fuzz)")
@Tag(INDEXING)
@Tag(TRANSACTION)
class LongRunningSavepointProducerMapTest extends AbstractSavepointFuzzTest<ProducerMapState> {
	private static final int KEY_SPACE = 48;
	private static final int MARKER = 100_000;
	private static final int MAX_OPS = 8;

	@Nonnull
	@Override
	protected FuzzGeneration<ProducerMapState> newGeneration(@Nonnull Random random) {
		return new MapState(random);
	}

	/**
	 * Builds a fresh producer-valued map seeded with a random subset of the key space (identity-merge values).
	 */
	@Nonnull
	private static PersistentTransactionalProducerMap<Integer, MapValue> newSeededMap(@Nonnull Random random) {
		final Map<Integer, MapValue> seed = new HashMap<>();
		final int size = random.nextInt(KEY_SPACE);
		for (int i = 0; i < size; i++) {
			final int key = random.nextInt(KEY_SPACE);
			seed.put(key, new MapValue(key));
		}
		return new PersistentTransactionalProducerMap<>(seed, MapValue.class, Function.identity());
	}

	/**
	 * Applies `count` random put / remove / mark operations within the current transaction.
	 */
	private static void applyRandomOps(@Nonnull PersistentTransactionalProducerMap<Integer, MapValue> map, @Nonnull Random random, int count) {
		for (int i = 0; i < count; i++) {
			// view-iterator / bulk-view ops (choices >= 3) mutate the diff layer through the entry-set / key-set views,
			// so they require the layer to already exist; when it does not yet, fall back to the direct put/remove/mark
			// that creates it (this is the write path the maintainer's first-touch snapshotting relies on)
			final boolean viewOpsUsable = !Transaction.isTransactionAvailable()
				|| Transaction.getTransactionalMemoryLayerIfExists(map) != null;
			switch (random.nextInt(viewOpsUsable ? 6 : 3)) {
				case 0 -> map.remove(random.nextInt(KEY_SPACE));
				case 1 -> {
					if (!map.isEmpty()) {
						// mark an existing key's value as mutated in place (producer-only dirty-key path)
						map.markValueMutated(pickKey(map, random));
					} else {
						final int key = random.nextInt(KEY_SPACE);
						map.put(key, new MapValue(key));
					}
				}
				case 2 -> {
					final int key = random.nextInt(KEY_SPACE);
					map.put(key, new MapValue(key));
				}
				case 3 -> removeOneViaEntryIterator(map, random);        // entrySet().iterator().remove()
				case 4 -> setOneViaEntryIterator(map, random);           // entry.setValue() (in-place overwrite)
				case 5 -> map.keySet().removeAll(randomKeySubset(random)); // AbstractSet#removeAll -> merged iterator remove
				default -> throw new IllegalStateException("unreachable producer map op choice");
			}
		}
	}

	/**
	 * Removes a single (randomly positioned) entry through the entry-set iterator, exercising the collection-view
	 * removal path that bypasses the direct mutators.
	 */
	private static void removeOneViaEntryIterator(@Nonnull PersistentTransactionalProducerMap<Integer, MapValue> map, @Nonnull Random random) {
		final int size = map.size();
		if (size == 0) {
			return;
		}
		int target = random.nextInt(size);
		final Iterator<Entry<Integer, MapValue>> it = map.entrySet().iterator();
		while (it.hasNext()) {
			it.next();
			if (target-- == 0) {
				it.remove();
				return;
			}
		}
	}

	/**
	 * Overwrites a single (randomly positioned) entry's value in place through the entry-set view's setValue proxy.
	 */
	private static void setOneViaEntryIterator(@Nonnull PersistentTransactionalProducerMap<Integer, MapValue> map, @Nonnull Random random) {
		final int size = map.size();
		if (size == 0) {
			return;
		}
		int target = random.nextInt(size);
		final Iterator<Entry<Integer, MapValue>> it = map.entrySet().iterator();
		while (it.hasNext()) {
			final Entry<Integer, MapValue> entry = it.next();
			if (target-- == 0) {
				final int payload = random.nextInt(KEY_SPACE);
				entry.setValue(new MapValue(payload));
				return;
			}
		}
	}

	/**
	 * Builds a small random subset of the key space to drive {@code keySet().removeAll} through the view.
	 */
	@Nonnull
	private static Set<Integer> randomKeySubset(@Nonnull Random random) {
		final Set<Integer> subset = new HashSet<>();
		final int n = 1 + random.nextInt(4);
		for (int i = 0; i < n; i++) {
			subset.add(random.nextInt(KEY_SPACE));
		}
		return subset;
	}

	/**
	 * Reads the map's logical state — contents plus the producer-only dirty-key set — into an `.equals`-comparable value.
	 */
	@Nonnull
	private static ProducerMapState readState(@Nonnull PersistentTransactionalProducerMap<Integer, MapValue> map) {
		return new ProducerMapState(new TreeMap<>(map), markedKeys(map));
	}

	/**
	 * Reflectively reads the {@code valueMutatedKeys} dirty-key set from the live layer (empty when no layer exists).
	 */
	@Nonnull
	private static List<Integer> markedKeys(@Nonnull PersistentTransactionalProducerMap<Integer, MapValue> map) {
		final MapChanges<Integer, MapValue> layer = Transaction.getTransactionalMemoryLayerIfExists(map);
		if (!(layer instanceof ProducerMapChanges<Integer, MapValue>)) {
			return List.of();
		}
		try {
			final Field field = ProducerMapChanges.class.getDeclaredField("valueMutatedKeys");
			field.setAccessible(true);
			final Set<?> keys = (Set<?>) field.get(layer);
			return keys.stream().map(Integer.class::cast).sorted().toList();
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	@Nonnull
	private static Integer pickKey(@Nonnull PersistentTransactionalProducerMap<Integer, MapValue> map, @Nonnull Random random) {
		final List<Integer> keys = List.copyOf(map.keySet());
		return keys.get(random.nextInt(keys.size()));
	}

	/**
	 * One generation's fixture: a freshly seeded producer-valued map. The oracle pairs its contents with the
	 * producer-only dirty-key set, which exists only in the diff layer — in WARM_UP there is none, so that half reads
	 * empty and the contents half carries the proof.
	 */
	private static final class MapState implements FuzzGeneration<ProducerMapState> {
		private final PersistentTransactionalProducerMap<Integer, MapValue> map;

		MapState(@Nonnull Random random) {
			this.map = newSeededMap(random);
		}

		@Nonnull
		@Override
		public TransactionalStateProducer<?> subject() {
			return this.map;
		}

		@Nonnull
		@Override
		public ProducerMapState contents() {
			return readState(this.map);
		}

		@Override
		public void applyBaselineOperations(@Nonnull Random random) {
			applyRandomOps(this.map, random, 1 + random.nextInt(MAX_OPS));
		}

		@Override
		public void applySavepointOperations(@Nonnull Random random) {
			applyRandomOps(this.map, random, 1 + random.nextInt(MAX_OPS));
			// applied LAST: a marker put first is a key like any other and a later random removal can drop it
			this.map.put(MARKER, new MapValue(MARKER));
			this.map.markValueMutated(MARKER);
		}
	}

}

/**
 * `.equals`-comparable snapshot of a producer map's logical content and its producer-only dirty-key set.
 *
 * Declared at file scope rather than nested in the test class because it is that class's
 * {@link io.evitadb.core.transaction.memory.AbstractSavepointFuzzTest} type argument, and a class may not name its own
 * member type in its own `extends` clause.
 *
 * @param contents the key → value contents
 * @param marked   the sorted dirty (value-mutated) keys
 */
record ProducerMapState(
	@Nonnull Map<Integer, MapValue> contents,
	@Nonnull List<Integer> marked
) {
}

/**
 * Minimal layer-less identity-merge producer used as the map value. Equality is by payload so the oracle compares
 * contents structurally; the value carries no transactional layer of its own.
 *
 * @param payload the value payload
 */
record MapValue(int payload) implements VoidTransactionMemoryProducer<MapValue> {

	@Nonnull
	@Override
	public MapValue createCopyWithMergedTransactionalMemory(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		return this;
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		// no-op: this value holds no transactional layer of its own
	}
}
