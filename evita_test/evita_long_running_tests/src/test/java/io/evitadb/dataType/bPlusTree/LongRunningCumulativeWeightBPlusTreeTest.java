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

package io.evitadb.dataType.bPlusTree;

import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyReport;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static io.evitadb.test.TestTags.COMPARATOR;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.SLOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Generational randomized proof test for {@link CumulativeWeightBPlusTree}, run against a {@link TreeMap}-backed oracle
 * ordered by the very same comparator. Each generation applies a random insert / remove / weight-update and asserts that
 * the tree stays internally consistent and observationally identical to the oracle (size, total weight, ordered
 * entries, and the cumulative-weight {@link CumulativeWeightBPlusTree#rankOf(Object)} for every present key plus a band
 * of absent probes). Although the structure is non-transactional, the no-merge B+ algorithm is intricate enough to
 * warrant the same soak coverage as the transactional B+ trees.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("CumulativeWeightBPlusTree (generational randomized proof)")
@Tag(CONTRACT)
@Tag(DATA_TYPE)
class LongRunningCumulativeWeightBPlusTreeTest implements TimeBoundedTestSupport {
	/**
	 * Upper bound on the live key population; the test oscillates around it.
	 */
	private static final int LIMIT = 1_000;
	/**
	 * Key domain — deliberately ~2x the population so both fresh inserts and collisions (weight bumps) happen often.
	 */
	private static final int KEY_SPACE = LIMIT << 1;

	@ParameterizedTest(name = "survives generational randomized insert/remove/update under the natural order")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("survives randomized operations (natural order)")
	void generationalProofTest(@Nonnull GenerationalTestInput input) {
		runGenerational(input, Comparator.naturalOrder());
	}

	@ParameterizedTest(name = "survives generational randomized insert/remove/update under a reverse comparator")
	@Tag(SLOW)
	@Tag(COMPARATOR)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("survives randomized operations (reverse comparator)")
	void generationalProofTestWithReverseComparator(@Nonnull GenerationalTestInput input) {
		runGenerational(input, Comparator.reverseOrder());
	}

	/**
	 * Shared body of both generational variants: seeds a tree + oracle to the population limit, then drives random
	 * mutations through {@link #runFor}, asserting full equivalence after every operation.
	 */
	private void runGenerational(@Nonnull GenerationalTestInput input, @Nonnull Comparator<Integer> comparator) {
		// a small block size makes splits, empty-leaf unlinks and single-child splices frequent
		final CumulativeWeightBPlusTree<Integer> tree = new CumulativeWeightBPlusTree<>(comparator, 16);
		final TreeMap<Integer, Integer> oracle = new TreeMap<>(comparator);
		final Random seedRandom = new Random(42);
		do {
			final int key = seedRandom.nextInt(KEY_SPACE);
			if (oracle.containsKey(key)) {
				tree.updateWeight(key, 1);
				oracle.merge(key, 1, Integer::sum);
			} else {
				tree.insert(key, 1);
				oracle.put(key, 1);
			}
		} while (oracle.size() < LIMIT);
		assertEquivalent(tree, oracle);

		runFor(
			input, 1000, new TestState(new StringBuilder(), true),
			(random, testState) -> {
				int key = -1;
				String operation = "?";
				try {
					final boolean shrink = testState.limitReached() && oracle.size() > LIMIT / 2;
					final int roll = random.nextInt(10);
					if ((shrink || roll < 3) && !oracle.isEmpty()) {
						// remove an existing key
						key = nthKey(oracle, random.nextInt(oracle.size()));
						operation = "R";
						tree.remove(key);
						oracle.remove(key);
					} else if (roll < 6 && !oracle.isEmpty()) {
						// adjust the weight of an existing key (down only when it stays >= 1)
						key = nthKey(oracle, random.nextInt(oracle.size()));
						final int current = oracle.get(key);
						final int delta = (current > 1 && random.nextBoolean()) ? -1 : (1 + random.nextInt(3));
						operation = "U" + delta;
						tree.updateWeight(key, delta);
						oracle.put(key, current + delta);
					} else {
						// insert a fresh key or bump an existing one
						key = random.nextInt(KEY_SPACE);
						if (oracle.containsKey(key)) {
							operation = "U+1";
							tree.updateWeight(key, 1);
							oracle.merge(key, 1, Integer::sum);
						} else {
							final int weight = 1 + random.nextInt(4);
							operation = "I" + weight;
							tree.insert(key, weight);
							oracle.put(key, weight);
						}
					}

					assertEquivalent(tree, oracle);

					return new TestState(
						testState.code().append(operation).append(':').append(key).append(' '),
						testState.limitReached()
							? oracle.size() > LIMIT / 2
							: oracle.size() >= LIMIT
					);
				} catch (Exception ex) {
					fail(
						"Failed at operation " + operation + " on key " + key + " with state: " + tree,
						ex
					);
					throw ex;
				}
			}
		);
	}

	/**
	 * Asserts the tree is internally consistent and observationally identical to the oracle: size, total weight, the
	 * full ordered entry sequence, and the cumulative weight for every present key plus a band of absent probe keys.
	 */
	private static void assertEquivalent(
		@Nonnull CumulativeWeightBPlusTree<Integer> tree,
		@Nonnull TreeMap<Integer, Integer> oracle
	) {
		final ConsistencyReport report = tree.getConsistencyReport();
		assertEquals(ConsistencyState.CONSISTENT, report.state(), report.report());

		assertEquals(oracle.size(), tree.size(), "size mismatch");
		final int expectedTotal = oracle.values().stream().mapToInt(Integer::intValue).sum();
		assertEquals(expectedTotal, tree.totalWeight(), "total weight mismatch");

		final List<Map.Entry<Integer, Integer>> expectedEntries = new ArrayList<>(oracle.entrySet());
		final List<Map.Entry<Integer, Integer>> actualEntries = new ArrayList<>(oracle.size());
		tree.forEachEntry((key, weight) -> actualEntries.add(Map.entry(key, weight)));
		assertEquals(expectedEntries, actualEntries, "ordered entries mismatch");

		int prefix = 0;
		for (final Map.Entry<Integer, Integer> entry : oracle.entrySet()) {
			final int key = entry.getKey();
			assertTrue(tree.containsKey(key), () -> "expected key " + key + " present");
			assertEquals(entry.getValue().intValue(), tree.weightOf(key), () -> "weight mismatch at " + key);
			assertEquals(prefix, tree.rankOf(key), () -> "rank mismatch at present key " + key);
			prefix += entry.getValue();
		}
		// probe a band of absent keys (in the comparator's order) just outside and inside the populated range
		if (!oracle.isEmpty()) {
			final int first = oracle.firstKey();
			final int last = oracle.lastKey();
			for (int step = -3; step <= 3; step++) {
				final int probe = first + step;
				if (!oracle.containsKey(probe)) {
					assertFalse(tree.containsKey(probe), () -> "unexpected key " + probe);
				}
				final int expectedRank = oracle.headMap(probe, false).values().stream().mapToInt(Integer::intValue).sum();
				assertEquals(expectedRank, tree.rankOf(probe), () -> "rank mismatch at probe " + probe);
				final int probe2 = last + step;
				final int expectedRank2 = oracle.headMap(probe2, false).values().stream().mapToInt(Integer::intValue).sum();
				assertEquals(expectedRank2, tree.rankOf(probe2), () -> "rank mismatch at probe " + probe2);
			}
		}
	}

	/**
	 * Returns the n-th key in the oracle's (comparator) order.
	 */
	private static Integer nthKey(@Nonnull TreeMap<Integer, Integer> map, int n) {
		int i = 0;
		for (final Integer key : map.keySet()) {
			if (i++ == n) {
				return key;
			}
		}
		throw new IllegalStateException("index " + n + " out of range " + map.size());
	}

	/**
	 * Per-generation carry state: an operation log (for failure diagnostics) and the population-limit oscillation flag.
	 */
	private record TestState(
		@Nonnull StringBuilder code,
		boolean limitReached
	) {
	}
}
