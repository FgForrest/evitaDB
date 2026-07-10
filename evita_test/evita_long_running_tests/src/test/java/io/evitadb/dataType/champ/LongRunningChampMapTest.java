/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.dataType.champ;

import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.SLOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generational randomized proof test for {@link ChampMap}. Each generation applies a small batch of
 * random `updated` / `removed` / `merged` operations to both a {@link ChampMap} and a reference
 * {@link java.util.HashMap} oracle, then asserts the two are equivalent in every observable way
 * (size, entries, iteration, {@link java.util.Map#equals}/{@link java.util.Map#hashCode}).
 *
 * On top of the oracle equivalence, the test keeps a ring of previously captured `(map, oracle)`
 * snapshots and re-verifies them every generation. This proves the structure's defining property:
 * a persistent snapshot is **never disturbed** by later mutations of maps derived from it — the
 * multi-version guarantee that motivates using CHAMP in the OffsetIndex.
 *
 * The key type {@link CollidableKey} forces frequent hash collisions (its hash is taken modulo a
 * small prime), so the churn exercises both the {@code BitmapIndexedMapNode} and the
 * {@code HashCollisionMapNode} canonicalization paths heavily.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("CHAMP immutable map (generational proof)")
@Tag(CONTRACT)
@Tag(DATA_TYPE)
class LongRunningChampMapTest implements TimeBoundedTestSupport {

	/** Distinct key value space — bounds the map's maximum cardinality. */
	private static final int VALUE_SPACE = 4_000;
	/** Hash modulus (prime) — small enough to force dense hash-collision buckets. */
	private static final int HASH_BUCKETS = 127;
	/** Soft upper bound on the live map size, beyond which removals are favoured. */
	private static final int SIZE_LIMIT = 1_500;
	/** Number of historical snapshots retained and re-checked each generation. */
	private static final int RETAINED_SNAPSHOTS = 8;

	@DisplayName("survives generational randomized updated/removed/merged operations")
	@ParameterizedTest(name = "ChampMap should survive generational randomized modifications")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final Map<CollidableKey, Integer> initialOracle = new HashMap<>();
		ChampMap<CollidableKey, Integer> initialMap = ChampMap.empty();
		final Random seeding = new Random(input.randomSeed());
		for (int i = 0; i < 500; i++) {
			final CollidableKey key = new CollidableKey(seeding.nextInt(VALUE_SPACE));
			final int value = seeding.nextInt();
			initialMap = initialMap.updated(key, value);
			initialOracle.put(key, value);
		}
		assertEquivalent(initialMap, initialOracle);

		runFor(
			input,
			1_000,
			new TestState(new StringBuilder(256), initialMap, initialOracle, new ArrayList<>()),
			(random, testState) -> {
				ChampMap<CollidableKey, Integer> map = testState.map();
				final Map<CollidableKey, Integer> oracle = testState.oracle();
				final StringBuilder code = testState.code();
				code.setLength(0);

				final int operations = random.nextInt(40) + 10;
				for (int i = 0; i < operations; i++) {
					final int roll = random.nextInt(100);
					final boolean favourRemoval = oracle.size() > SIZE_LIMIT;
					if (roll < 5 && oracle.size() < SIZE_LIMIT) {
						// merge a small random delta in one structural pass (resolver: delta wins)
						final Map<CollidableKey, Integer> delta = new HashMap<>();
						ChampMap<CollidableKey, Integer> deltaMap = ChampMap.empty();
						final int deltaSize = random.nextInt(10) + 1;
						for (int j = 0; j < deltaSize; j++) {
							final CollidableKey key = new CollidableKey(random.nextInt(VALUE_SPACE));
							final int value = random.nextInt();
							delta.put(key, value);
							deltaMap = deltaMap.updated(key, value);
						}
						map = map.merged(deltaMap, (left, right) -> right);
						oracle.putAll(delta);
						code.append("M").append(deltaSize).append(' ');
					} else if (favourRemoval || roll < 45) {
						// removal (mixes real removals with no-op misses)
						final CollidableKey key = new CollidableKey(random.nextInt(VALUE_SPACE));
						map = map.removed(key);
						oracle.remove(key);
						code.append("-").append(key.value()).append(' ');
					} else {
						// insert / update
						final CollidableKey key = new CollidableKey(random.nextInt(VALUE_SPACE));
						final int value = random.nextInt();
						map = map.updated(key, value);
						oracle.put(key, value);
						code.append("+").append(key.value()).append(' ');
					}
				}

				assertEquivalent(map, oracle);

				// every retained snapshot must still match its own captured oracle — i.e. the later
				// mutations above did not disturb any previously published version
				final List<Snapshot> retained = testState.retained();
				for (final Snapshot snapshot : retained) {
					assertEquals(
						snapshot.oracle().size(), snapshot.map().size(),
						"Retained snapshot size changed under later mutations!");
					assertEquals(
						snapshot.map(), snapshot.oracle(),
						"Retained snapshot content changed under later mutations!"
					);
				}

				// roll the snapshot ring forward with an immutable copy of the current oracle
				final List<Snapshot> nextRetained = new ArrayList<>(retained);
				nextRetained.add(new Snapshot(map, new HashMap<>(oracle)));
				while (nextRetained.size() > RETAINED_SNAPSHOTS) {
					nextRetained.remove(0);
				}

				return new TestState(code, map, oracle, nextRetained);
			}
		);
	}

	/**
	 * Asserts that the {@link ChampMap} and the oracle are indistinguishable through every read
	 * surface: cardinality, bidirectional {@link java.util.Map#equals}, hash code, per-key lookups
	 * and full entry-set iteration.
	 */
	private static void assertEquivalent(
		@Nonnull ChampMap<CollidableKey, Integer> map, @Nonnull Map<CollidableKey, Integer> oracle) {
		assertEquals(oracle.size(), map.size(), "Size mismatch!");
		assertEquals(map, oracle, "ChampMap does not equal oracle!");
		assertEquals(oracle, map, "Oracle does not equal ChampMap!");
		assertEquals(oracle.hashCode(), map.hashCode(), "Hash code mismatch!");

		for (final Entry<CollidableKey, Integer> entry : oracle.entrySet()) {
			assertTrue(map.containsKey(entry.getKey()), "Missing key " + entry.getKey() + "!");
			assertEquals(entry.getValue(), map.get(entry.getKey()), "Value mismatch for " + entry.getKey() + "!");
		}

		int iterated = 0;
		final Set<CollidableKey> iteratedKeys = new HashSet<>(map.size());
		for (Entry<CollidableKey, Integer> entry : map.entrySet()) {
			assertEquals(oracle.get(entry.getKey()), entry.getValue(), "Iterated value mismatch!");
			// distinct-key guard: a duplicated entry would slip past the equal-size + per-key
			// containsKey checks above, so verify every iterated key is unique
			assertTrue(iteratedKeys.add(entry.getKey()), "Iterator visited a key twice!");
			iterated++;
		}
		assertEquals(oracle.size(), iterated, "Iterator visited a wrong number of entries!");
	}

	/**
	 * A key whose hash code deliberately collides for many distinct values (hash taken modulo a
	 * small prime), forcing the hash-collision code path to be exercised under random churn. Two
	 * keys are equal only when their underlying values are equal.
	 */
	private record CollidableKey(int value) {

		@Override
		public int hashCode() {
			return this.value % HASH_BUCKETS;
		}
	}

	private record TestState(
		@Nonnull StringBuilder code,
		@Nonnull ChampMap<CollidableKey, Integer> map,
		@Nonnull Map<CollidableKey, Integer> oracle,
		@Nonnull List<Snapshot> retained
	) {}

	private record Snapshot(
		@Nonnull ChampMap<CollidableKey, Integer> map,
		@Nonnull Map<CollidableKey, Integer> oracle
	) {}
}
