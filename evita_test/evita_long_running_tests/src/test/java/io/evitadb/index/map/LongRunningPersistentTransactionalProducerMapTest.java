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
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.dataType.champ.ChampMap;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Generational randomized proof test for {@link PersistentTransactionalProducerMap} — the {@link ChampMap}-backed STM
 * map whose values are themselves {@link TransactionalLayerProducer}s. Each generation rebuilds a fresh map from the
 * previous generation's committed reference, applies a random batch of inserts, removals and **in-place marked
 * mutations** inside a transaction, and verifies the committed snapshot matches a `key → committedValue` oracle.
 *
 * This is the load-bearing generational test for the producer-map design: a producer value mutates through its OWN diff
 * layer, invisible to the map's put/remove tracking, so the `O(Δ·log₃₂ N)` commit relies on every in-place mutation
 * being declared via {@link PersistentTransactionalProducerMap#markValueMutated} (paired here in {@link #markAndSet}, as
 * the real `AttributeIndex` paths do). Because each generation feeds the next, a dropped mark, a stale shared producer
 * node, or a mis-swept nested layer compounds and is caught over thousands of iterations.
 *
 * **Within-transaction constraint:** each key is touched at most once per transaction. Mixing an in-place mark with a
 * replace/remove of the SAME key in one transaction would orphan the first value's diff layer — unsupported by the
 * Δ-union commit by design. Keys are revisited freely across generations, which is where accumulated-correctness
 * coverage comes from.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("PersistentTransactionalProducerMap (generational randomized proof)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class LongRunningPersistentTransactionalProducerMapTest implements TimeBoundedTestSupport {

	@DisplayName("survives generational randomized test applying modifications on it")
	@ParameterizedTest(name = "PersistentTransactionalProducerMap should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(@Nonnull GenerationalTestInput input) {
		final int initialCount = 100;
		final Map<String, Integer> initialState = generateRandomInitialMap(new Random(input.randomSeed()), initialCount);

		runFor(
			input,
			10_000,
			new TestState(initialState),
			(random, testState) -> {
				// the oracle holds the committed value each surviving producer must carry, keyed exactly like the map
				final Map<String, Integer> oracle = new HashMap<>(testState.oracle());
				final PersistentTransactionalProducerMap<String, CountingProducer> map = mapOf(seed(oracle));
				// each key is touched at most once per transaction (see class javadoc)
				final Set<String> touchedThisBatch = new HashSet<>();

				assertStateAfterCommit(
					map,
					m -> {
						final int operationsInTransaction = random.nextInt(5);
						for (int i = 0; i < operationsInTransaction; i++) {
							final String key = String.valueOf((char) (40 + random.nextInt(64)));
							if (!touchedThisBatch.add(key)) {
								// already mutated this key in this transaction - skip to stay within supported ops
								continue;
							}
							final int operation = random.nextInt(4);
							final int size = oracle.size();
							if (operation == 0 && oracle.containsKey(key)) {
								// remove
								m.remove(key);
								oracle.remove(key);
							} else if (operation == 1 && oracle.containsKey(key)) {
								// in-place mutation through the producer's OWN layer + the mandatory mark
								final int value = random.nextInt(initialCount << 1) + 1;
								markAndSet(m, key, value);
								oracle.put(key, value);
							} else if (size < 120) {
								// insert or full replacement with a fresh producer carrying the new value
								final int value = random.nextInt(initialCount << 1) + 1;
								m.put(key, new CountingProducer(value));
								oracle.put(key, value);
							}
						}
					},
					(m, committed) -> {
						assertInstanceOf(ChampMap.class, committed);
						assertEquals(oracle.size(), committed.size(), "committed size diverged from the oracle!");
						for (final Map.Entry<String, Integer> entry : oracle.entrySet()) {
							final CountingProducer producer = committed.get(entry.getKey());
							assertNotNull(producer, "committed snapshot is missing key " + entry.getKey());
							assertEquals(
								entry.getValue().intValue(), producer.committedValue(),
								"committed value diverged from the oracle for key " + entry.getKey()
							);
						}
					}
				);

				return new TestState(oracle);
			}
		);
	}

	/**
	 * Builds a producer map seeded with the supplied producers, configured exactly as the attribute sub-index maps are
	 * (concrete producer class + identity wrapper, because the producers merge to themselves).
	 */
	@Nonnull
	private static PersistentTransactionalProducerMap<String, CountingProducer> mapOf(
		@Nonnull Map<String, CountingProducer> seed
	) {
		return new PersistentTransactionalProducerMap<>(seed, CountingProducer.class, Function.identity());
	}

	/**
	 * Materializes a producer per oracle entry, each carrying that entry's committed value.
	 */
	@Nonnull
	private static Map<String, CountingProducer> seed(@Nonnull Map<String, Integer> oracle) {
		final Map<String, CountingProducer> seed = new HashMap<>(oracle.size());
		for (final Map.Entry<String, Integer> entry : oracle.entrySet()) {
			seed.put(entry.getKey(), new CountingProducer(entry.getValue()));
		}
		return seed;
	}

	/**
	 * Mutates the producer value under `key` in place AND declares the mutation to the map — the exact pairing every real
	 * `AttributeIndex` mutation path performs (mark the key, then write through the value's own layer).
	 */
	private static void markAndSet(
		@Nonnull PersistentTransactionalProducerMap<String, CountingProducer> map,
		@Nonnull String key,
		int newValue
	) {
		map.markValueMutated(key);
		map.get(key).set(newValue);
	}

	@Nonnull
	private static Map<String, Integer> generateRandomInitialMap(@Nonnull Random rnd, int count) {
		final Map<String, Integer> initialMap = new HashMap<>(count);
		for (int i = 0; i < count; i++) {
			final String recKey = String.valueOf((char) (40 + rnd.nextInt(64)));
			final int recId = rnd.nextInt(count << 1) + 1;
			initialMap.put(recKey, recId);
		}
		return initialMap;
	}

	private record TestState(@Nonnull Map<String, Integer> oracle) {
	}

	/**
	 * Minimal {@link TransactionalLayerProducer} that mirrors the identity-preservation contract of the real
	 * attribute-index producers: an untouched instance merges to itself (`this`), a touched one to a fresh instance
	 * carrying the new value. Its diff layer is an `int[]{newValue, touchedFlag}`.
	 */
	private static final class CountingProducer implements TransactionalLayerProducer<int[], CountingProducer> {
		private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
		private int value;

		CountingProducer(int value) {
			this.value = value;
		}

		@Override
		public long getId() {
			return this.id;
		}

		/**
		 * Returns the committed (non-transactional) value held by this producer.
		 */
		int committedValue() {
			return this.value;
		}

		/**
		 * Records a new value in this producer's own diff layer when a transaction is open, otherwise applies it
		 * directly. Routing the mutation through the producer's own layer (never the enclosing map) is exactly how the
		 * real attribute indexes mutate in place.
		 */
		void set(int newValue) {
			final int[] layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
			if (layer == null) {
				this.value = newValue;
			} else {
				layer[0] = newValue;
				layer[1] = 1;
			}
		}

		@Nonnull
		@Override
		public int[] createLayer() {
			// [newValue, touchedFlag] - starts untouched, carrying the current value
			return new int[]{this.value, 0};
		}

		@Nonnull
		@Override
		public CountingProducer createCopyWithMergedTransactionalMemory(
			@Nullable int[] layer,
			@Nonnull TransactionalLayerMaintainer transactionalLayer
		) {
			if (layer == null || layer[1] == 0) {
				// not mutated in this transaction → preserve identity so the enclosing map can structurally share it
				return this;
			}
			return new CountingProducer(layer[0]);
		}

		@Override
		public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		}
	}
}
