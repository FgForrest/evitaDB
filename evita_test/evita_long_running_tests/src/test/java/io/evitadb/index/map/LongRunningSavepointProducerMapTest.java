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
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertSavepointCommitKeeps;
import static io.evitadb.utils.AssertionUtils.assertSavepointRollbackRestores;

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
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ProducerMap savepoint rollback/commit backfill (generational fuzz)")
@Tag(INDEXING)
@Tag(TRANSACTION)
class LongRunningSavepointProducerMapTest implements TimeBoundedTestSupport {
	private static final int KEY_SPACE = 48;
	private static final int MARKER = 100_000;
	private static final int MAX_OPS = 8;

	@ParameterizedTest(name = "ProducerMap: savepoint rollback restores contents and the dirty-key set")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("ProducerMap: savepoint rollback restores contents and the dirty-key set")
	void shouldRollBackProducerMap(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final PersistentTransactionalProducerMap<Integer, MapValue> map = newSeededMap(random);
			assertSavepointRollbackRestores(
				map,
				tested -> applyRandomOps(tested, random, 1 + random.nextInt(MAX_OPS)),
				LongRunningSavepointProducerMapTest::readState,
				tested -> {
					// a marker put + mark guarantees both the contents and the dirty-key set change
					tested.put(MARKER, new MapValue(MARKER));
					tested.markValueMutated(MARKER);
					applyRandomOps(tested, random, 1 + random.nextInt(MAX_OPS));
				}
			);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "ProducerMap: savepoint commit keeps contents and the dirty-key set")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("ProducerMap: savepoint commit keeps contents and the dirty-key set")
	void shouldCommitProducerMap(@Nonnull GenerationalTestInput input) {
		runFor(input, 1000, 0L, (random, iteration) -> {
			final PersistentTransactionalProducerMap<Integer, MapValue> map = newSeededMap(random);
			assertSavepointCommitKeeps(
				map,
				tested -> applyRandomOps(tested, random, 1 + random.nextInt(MAX_OPS)),
				LongRunningSavepointProducerMapTest::readState,
				tested -> applyRandomOps(tested, random, 1 + random.nextInt(MAX_OPS))
			);
			return iteration + 1;
		});
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
			final int key = random.nextInt(KEY_SPACE);
			final int choice = random.nextInt(4);
			if (choice == 0) {
				map.remove(key);
			} else if (choice == 1 && !map.isEmpty()) {
				// mark an existing key's value as mutated in place (producer-only dirty-key path)
				map.markValueMutated(pickKey(map, random));
			} else {
				map.put(key, new MapValue(key));
			}
		}
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
			return keys.stream().map(it -> (Integer) it).sorted().toList();
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
	 * `.equals`-comparable snapshot of the map's logical content and its producer-only dirty-key set.
	 *
	 * @param contents the key → value contents
	 * @param marked   the sorted dirty (value-mutated) keys
	 */
	private record ProducerMapState(@Nonnull Map<Integer, MapValue> contents, @Nonnull List<Integer> marked) {
	}

	/**
	 * Minimal layer-less identity-merge producer used as the map value. Equality is by payload so the oracle compares
	 * contents structurally; the value carries no transactional layer of its own.
	 *
	 * @param payload the value payload
	 */
	private record MapValue(int payload) implements VoidTransactionMemoryProducer<MapValue> {

		@Nonnull
		@Override
		public MapValue createCopyWithMergedTransactionalMemory(@Nullable Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
			return this;
		}

		@Override
		public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			// no-op: this value holds no transactional layer of its own
		}
	}

}
