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
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.DATA_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the correctness of {@link CumulativeWeightBPlusTree}: constructor validation, the insert / remove /
 * updateWeight mutators, the {@link CumulativeWeightBPlusTree#rankOf(Object)} cumulative-weight query, ordered
 * iteration, custom comparators, and the structural transitions (leaf split, empty-leaf removal, single-child collapse,
 * root height reduction) forced via a tiny block size — each checked against a {@link TreeMap}-backed oracle.
 *
 * The oracle is intentionally trivial (a sorted map summed linearly) so that any divergence pins a bug in the tree, not
 * the reference. The randomized generational fuzz lives exclusively in the long-running module
 * (`LongRunningCumulativeWeightBPlusTreeTest`), which fully supersedes any bounded in-process variant.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Cumulative-weight B+ tree")
@Tag(CONTRACT)
@Tag(DATA_TYPE)
class CumulativeWeightBPlusTreeTest {

	/**
	 * Asserts that the tree reports a consistent internal state and matches the oracle on every observable query: size,
	 * total weight, ordered entries, and — for every present key plus a band of absent probe keys — containsKey,
	 * weightOf and rankOf.
	 */
	private static void assertEquivalent(
		@Nonnull CumulativeWeightBPlusTree<Integer> tree,
		@Nonnull TreeMap<Integer, Integer> oracle
	) {
		final ConsistencyReport report = tree.getConsistencyReport();
		assertEquals(ConsistencyState.CONSISTENT, report.state(), report.report());

		assertEquals(oracle.size(), tree.size(), "size mismatch");
		assertEquals(oracle.isEmpty(), tree.isEmpty(), "isEmpty mismatch");
		final int expectedTotal = oracle.values().stream().mapToInt(Integer::intValue).sum();
		assertEquals(expectedTotal, tree.totalWeight(), "total weight mismatch");

		// ordered entries must match the oracle exactly (order + weights)
		final List<Map.Entry<Integer, Integer>> expectedEntries = new ArrayList<>(oracle.entrySet());
		final List<Map.Entry<Integer, Integer>> actualEntries = new ArrayList<>();
		tree.forEachEntry((key, weight) -> actualEntries.add(Map.entry(key, weight)));
		assertEquals(expectedEntries, actualEntries, "ordered entries mismatch");

		// probe present keys and a band of absent keys around the populated range
		int prefix = 0;
		for (final Map.Entry<Integer, Integer> entry : oracle.entrySet()) {
			final int key = entry.getKey();
			assertTrue(tree.containsKey(key), () -> "expected key " + key + " present");
			assertEquals(entry.getValue().intValue(), tree.weightOf(key), () -> "weight mismatch at " + key);
			assertEquals(prefix, tree.rankOf(key), () -> "rank mismatch at present key " + key);
			prefix += entry.getValue();
		}
		final int lo = oracle.isEmpty() ? -5 : oracle.firstKey() - 5;
		final int hi = oracle.isEmpty() ? 5 : oracle.lastKey() + 5;
		for (int probe = lo; probe <= hi; probe++) {
			if (!oracle.containsKey(probe)) {
				final int p = probe;
				assertFalse(tree.containsKey(p), () -> "unexpected key " + p);
				assertEquals(0, tree.weightOf(p), () -> "absent key " + p + " should weigh 0");
			}
			// rankOf is defined for present and absent keys alike: sum of weights of strictly-smaller keys
			final int expectedRank = oracle.headMap(probe, false).values().stream().mapToInt(Integer::intValue).sum();
			final int p = probe;
			assertEquals(expectedRank, tree.rankOf(p), () -> "rank mismatch at probe " + p);
		}
	}

	@Nested
	@DisplayName("construction & validation")
	class Construction {

		@Test
		@DisplayName("rejects a block size below 2")
		void shouldRejectTinyBlockSize() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new CumulativeWeightBPlusTree<>(Comparator.<Integer>naturalOrder(), 1)
			);
		}

		@Test
		@DisplayName("starts empty")
		void shouldStartEmpty() {
			final CumulativeWeightBPlusTree<Integer> tree = new CumulativeWeightBPlusTree<>(Comparator.naturalOrder());
			assertTrue(tree.isEmpty());
			assertEquals(0, tree.size());
			assertEquals(0, tree.totalWeight());
			assertEquals(0, tree.rankOf(42));
			assertEquals(0, tree.weightOf(42));
			assertFalse(tree.containsKey(42));
			assertEquals(ConsistencyState.CONSISTENT, tree.getConsistencyReport().state());
		}
	}

	@Nested
	@DisplayName("argument guards")
	class Guards {

		@Test
		@DisplayName("rejects a non-positive insert weight")
		void shouldRejectNonPositiveWeight() {
			final CumulativeWeightBPlusTree<Integer> tree = new CumulativeWeightBPlusTree<>(Comparator.naturalOrder());
			assertThrows(GenericEvitaInternalError.class, () -> tree.insert(1, 0));
			assertThrows(GenericEvitaInternalError.class, () -> tree.insert(1, -3));
		}

		@Test
		@DisplayName("rejects inserting a duplicate key")
		void shouldRejectDuplicateInsert() {
			final CumulativeWeightBPlusTree<Integer> tree = new CumulativeWeightBPlusTree<>(Comparator.naturalOrder());
			tree.insert(1, 1);
			assertThrows(GenericEvitaInternalError.class, () -> tree.insert(1, 1));
		}

		@Test
		@DisplayName("rejects removing an absent key")
		void shouldRejectRemovingAbsentKey() {
			final CumulativeWeightBPlusTree<Integer> tree = new CumulativeWeightBPlusTree<>(Comparator.naturalOrder());
			assertThrows(GenericEvitaInternalError.class, () -> tree.remove(1));
			tree.insert(1, 1);
			assertThrows(GenericEvitaInternalError.class, () -> tree.remove(2));
		}

		@Test
		@DisplayName("rejects updating an absent key or driving weight below 1")
		void shouldRejectIllegalWeightUpdates() {
			final CumulativeWeightBPlusTree<Integer> tree = new CumulativeWeightBPlusTree<>(Comparator.naturalOrder());
			assertThrows(GenericEvitaInternalError.class, () -> tree.updateWeight(1, 1));
			tree.insert(1, 2);
			assertThrows(GenericEvitaInternalError.class, () -> tree.updateWeight(1, -2));
			assertThrows(GenericEvitaInternalError.class, () -> tree.updateWeight(1, -5));
		}
	}

	@Nested
	@DisplayName("single-leaf behaviour")
	class SingleLeaf {

		@Test
		@DisplayName("tracks weights and ranks within one leaf")
		void shouldTrackWithinOneLeaf() {
			final CumulativeWeightBPlusTree<Integer> tree = new CumulativeWeightBPlusTree<>(Comparator.naturalOrder());
			final TreeMap<Integer, Integer> oracle = new TreeMap<>();
			tree.insert(10, 3);
			oracle.put(10, 3);
			tree.insert(20, 1);
			oracle.put(20, 1);
			tree.insert(5, 2);
			oracle.put(5, 2);
			assertEquivalent(tree, oracle);

			// rank reflects cumulative weight, not key count
			assertEquals(0, tree.rankOf(5));
			assertEquals(2, tree.rankOf(10));
			assertEquals(5, tree.rankOf(20));
			assertEquals(6, tree.rankOf(21));
		}

		@Test
		@DisplayName("updateWeight shifts the ranks of following keys")
		void shouldShiftRanksOnWeightUpdate() {
			final CumulativeWeightBPlusTree<Integer> tree = new CumulativeWeightBPlusTree<>(Comparator.naturalOrder());
			final TreeMap<Integer, Integer> oracle = new TreeMap<>();
			for (int i = 1; i <= 5; i++) {
				tree.insert(i, 1);
				oracle.put(i, 1);
			}
			tree.updateWeight(2, 4);
			oracle.put(2, 5);
			assertEquivalent(tree, oracle);
			assertEquals(1, tree.rankOf(2));
			assertEquals(6, tree.rankOf(3));
		}
	}

	@Nested
	@DisplayName("structural transitions (block size 3)")
	class Structural {

		@Test
		@DisplayName("splits leaves and grows height as keys are inserted ascending")
		void shouldSplitAscending() {
			final CumulativeWeightBPlusTree<Integer> tree = new CumulativeWeightBPlusTree<>(Comparator.naturalOrder(), 3);
			final TreeMap<Integer, Integer> oracle = new TreeMap<>();
			for (int i = 0; i < 50; i++) {
				tree.insert(i, (i % 4) + 1);
				oracle.put(i, (i % 4) + 1);
				assertEquivalent(tree, oracle);
			}
		}

		@Test
		@DisplayName("splits leaves when keys are inserted descending (new-minimum separator refresh)")
		void shouldSplitDescending() {
			final CumulativeWeightBPlusTree<Integer> tree = new CumulativeWeightBPlusTree<>(Comparator.naturalOrder(), 3);
			final TreeMap<Integer, Integer> oracle = new TreeMap<>();
			for (int i = 50; i > 0; i--) {
				tree.insert(i, (i % 3) + 1);
				oracle.put(i, (i % 3) + 1);
				assertEquivalent(tree, oracle);
			}
		}

		@Test
		@DisplayName("collapses empty leaves and reduces height back to empty on full removal")
		void shouldCollapseOnRemoval() {
			final CumulativeWeightBPlusTree<Integer> tree = new CumulativeWeightBPlusTree<>(Comparator.naturalOrder(), 3);
			final TreeMap<Integer, Integer> oracle = new TreeMap<>();
			for (int i = 0; i < 40; i++) {
				tree.insert(i, 2);
				oracle.put(i, 2);
			}
			assertEquivalent(tree, oracle);
			// remove in a scrambled order to exercise empty-leaf unlink + single-child splice
			final List<Integer> keys = new ArrayList<>(oracle.keySet());
			java.util.Collections.shuffle(keys, new Random(7));
			for (final Integer key : keys) {
				tree.remove(key);
				oracle.remove(key);
				assertEquivalent(tree, oracle);
			}
			assertTrue(tree.isEmpty());
		}

		@Test
		@DisplayName("removing a leaf minimum keeps ranks correct (stale-low separator)")
		void shouldKeepRanksAfterRemovingMinimum() {
			final CumulativeWeightBPlusTree<Integer> tree = new CumulativeWeightBPlusTree<>(Comparator.naturalOrder(), 3);
			final TreeMap<Integer, Integer> oracle = new TreeMap<>();
			for (int i = 0; i < 24; i++) {
				tree.insert(i << 1, 1);
				oracle.put(i << 1, 1);
			}
			// remove a spread of keys including block minima, then re-insert values that fall into the loosened gaps
			for (int i = 0; i < 24; i += 3) {
				tree.remove(i << 1);
				oracle.remove(i << 1);
			}
			assertEquivalent(tree, oracle);
			for (int i = 0; i < 24; i += 3) {
				tree.insert((i << 1) - 1, 2);
				oracle.put((i << 1) - 1, 2);
			}
			assertEquivalent(tree, oracle);
		}
	}

	@Nested
	@DisplayName("custom comparator")
	class CustomComparator {

		@Test
		@DisplayName("honours a reverse comparator for ordering and ranks")
		void shouldHonourReverseComparator() {
			final Comparator<Integer> reverse = Comparator.reverseOrder();
			final CumulativeWeightBPlusTree<Integer> tree = new CumulativeWeightBPlusTree<>(reverse, 3);
			final TreeMap<Integer, Integer> oracle = new TreeMap<>(reverse);
			final Random random = new Random(11);
			for (int i = 0; i < 60; i++) {
				final int key = random.nextInt(40);
				if (oracle.containsKey(key)) {
					tree.updateWeight(key, 1);
					oracle.merge(key, 1, Integer::sum);
				} else {
					tree.insert(key, 1);
					oracle.put(key, 1);
				}
				assertEquivalent(tree, oracle);
			}
			// under the reverse comparator the largest integer has rank 0
			assertEquals(0, tree.rankOf(Integer.MAX_VALUE));
		}
	}
}
