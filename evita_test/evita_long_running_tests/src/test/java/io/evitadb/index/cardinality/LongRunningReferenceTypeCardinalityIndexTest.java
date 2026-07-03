/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.index.cardinality;

import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.NumberUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generational randomized proof test for {@link ReferenceTypeCardinalityIndex}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ReferenceTypeCardinalityIndex (generational proof)")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(REFERENCE)
class LongRunningReferenceTypeCardinalityIndexTest implements TimeBoundedTestSupport {

	@DisplayName("survives generational randomized test applying modifications on it")
	@ParameterizedTest(name = "ReferenceTypeCardinalityIndex should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int initialCount = 30;
		final Map<Long, Integer> initialCardinalities = new HashMap<>(initialCount << 1);
		final Map<Integer, Set<Integer>> initialRefPkToIndexPks = new HashMap<>(initialCount << 1);
		populateRandomMaps(
			new Random(input.randomSeed()), initialCount,
			initialCardinalities, initialRefPkToIndexPks
		);

		runFor(
			input,
			10_000,
			new TestState(new StringBuilder(256), initialCardinalities, initialRefPkToIndexPks),
			(random, testState) -> {
				final ReferenceTypeCardinalityIndex index =
					buildIndex(testState.referenceCardinalities());
				final Map<Long, Integer> referenceCardinalities = new HashMap<>(testState.referenceCardinalities());
				final Map<Integer, Set<Integer>> refPkToIndexPks = deepCopy(testState.refPkToIndexPks());

				assertStateAfterCommit(
					index,
					original -> {
						final int opCount = random.nextInt(5) + 1;
						for (int i = 0; i < opCount; i++) {
							final int operation = referenceCardinalities.isEmpty() ? 0 : random.nextInt(4);
							if (operation == 0) {
								// add a new or already-present (indexPk, refPk) pair
								final int indexPk = random.nextInt(20) + 1;
								final int refPk = random.nextInt(10) + 1;
								final long composed = NumberUtils.pack(indexPk, refPk);
								original.addRecord(indexPk, refPk);
								if (referenceCardinalities.merge(composed, 1, Integer::sum) == 1) {
									// first occurrence: register in forward index
									refPkToIndexPks.computeIfAbsent(refPk, k -> new HashSet<>()).add(indexPk);
								}
							} else if (operation == 1) {
								// re-add (increment) a randomly chosen existing pair
								final List<Long> keys = new ArrayList<>(referenceCardinalities.keySet());
								final long composed = keys.get(random.nextInt(keys.size()));
								final int[] parts = NumberUtils.unpack(composed);
								original.addRecord(parts[0], parts[1]);
								referenceCardinalities.merge(composed, 1, Integer::sum);
								// forward index unchanged — pair already registered
							} else if (operation == 2) {
								// decrement a pair with count > 1; fall back to re-add when none exists
								final List<Long> candidates = new ArrayList<>(referenceCardinalities.size());
								for (final Map.Entry<Long, Integer> e : referenceCardinalities.entrySet()) {
									if (e.getValue() > 1) {
										candidates.add(e.getKey());
									}
								}
								if (candidates.isEmpty()) {
									final List<Long> keys = new ArrayList<>(referenceCardinalities.keySet());
									final long composed = keys.get(random.nextInt(keys.size()));
									final int[] parts = NumberUtils.unpack(composed);
									original.addRecord(parts[0], parts[1]);
									referenceCardinalities.merge(composed, 1, Integer::sum);
								} else {
									final long composed = candidates.get(random.nextInt(candidates.size()));
									final int[] parts = NumberUtils.unpack(composed);
									original.removeRecord(parts[0], parts[1]);
									referenceCardinalities.merge(composed, -1, Integer::sum);
									// forward index unchanged — pair still present
								}
							} else {
								// fully remove a pair with count == 1; fall back to re-add when none exists
								final List<Long> candidates = new ArrayList<>(referenceCardinalities.size());
								for (final Map.Entry<Long, Integer> e : referenceCardinalities.entrySet()) {
									if (e.getValue() == 1) {
										candidates.add(e.getKey());
									}
								}
								if (candidates.isEmpty()) {
									final List<Long> keys = new ArrayList<>(referenceCardinalities.keySet());
									final long composed = keys.get(random.nextInt(keys.size()));
									final int[] parts = NumberUtils.unpack(composed);
									original.addRecord(parts[0], parts[1]);
									referenceCardinalities.merge(composed, 1, Integer::sum);
								} else {
									final long composed = candidates.get(random.nextInt(candidates.size()));
									final int[] parts = NumberUtils.unpack(composed);
									original.removeRecord(parts[0], parts[1]);
									referenceCardinalities.remove(composed);
									// update forward index
									final Set<Integer> indexPks = refPkToIndexPks.get(parts[1]);
									if (indexPks != null) {
										indexPks.remove(parts[0]);
										if (indexPks.isEmpty()) {
											refPkToIndexPks.remove(parts[1]);
										}
									}
								}
							}
						}
					},
					(original, committed) -> {
						// Build expected cardinalities in the internal format used by ReferenceTypeCardinalityIndex:
						// join(indexPk, 0) -> total count for that indexPk, -join(indexPk, refPk) -> pair count
						final Map<Long, Integer> expectedInternalCardinalities = new HashMap<>(
							referenceCardinalities.size() << 2);
						for (final Map.Entry<Long, Integer> ce : referenceCardinalities.entrySet()) {
							final long pairKey = ce.getKey();
							final int pairCount = ce.getValue();
							final int[] ceParts = NumberUtils.unpack(pairKey);
							expectedInternalCardinalities.merge(NumberUtils.pack(ceParts[0], 0), pairCount, Integer::sum);
							expectedInternalCardinalities.put(-1L * pairKey, pairCount);
						}
						assertEquals(expectedInternalCardinalities, committed.getCardinalities());
						assertEquals(referenceCardinalities.isEmpty(), committed.isEmpty());
						// verify getAllReferenceIndexes for every tracked refPk
						for (final Map.Entry<Integer, Set<Integer>> e : refPkToIndexPks.entrySet()) {
							final int refPk = e.getKey();
							final int[] expected = e.getValue().stream()
								.mapToInt(Integer::intValue).sorted().toArray();
							final int[] actual = committed.getAllReferenceIndexes(refPk);
							assertArrayEquals(
								expected, actual,
								"getAllReferenceIndexes mismatch for refPk=" + refPk
							);
						}
						// verify getIndexPrimaryKeys across all tracked refPks
						if (!refPkToIndexPks.isEmpty()) {
							final int[] allRefPks = refPkToIndexPks.keySet().stream()
								.mapToInt(Integer::intValue).toArray();
							final PersistentRoaringBitmap query = PersistentRoaringBitmap.bitmapOf(allRefPks);
							final Bitmap result = committed.getIndexPrimaryKeys(query);
							final Set<Integer> expectedIndexPks = new HashSet<>();
							for (final Set<Integer> s : refPkToIndexPks.values()) {
								expectedIndexPks.addAll(s);
							}
							assertEquals(expectedIndexPks.size(), result.size());
							for (final int indexPk : expectedIndexPks) {
								assertTrue(
									result.contains(indexPk),
									"getIndexPrimaryKeys must contain indexPk=" + indexPk
								);
							}
						}
					}
				);

				return new TestState(new StringBuilder(256), referenceCardinalities, refPkToIndexPks);
			}
		);
	}

	@Nonnull
	private static ReferenceTypeCardinalityIndex buildIndex(
		@Nonnull Map<Long, Integer> pairCounts
	) {
		// Build index by replaying addRecord calls — avoids touching internal key format
		final ReferenceTypeCardinalityIndex index = new ReferenceTypeCardinalityIndex();
		for (final Map.Entry<Long, Integer> e : pairCounts.entrySet()) {
			final int[] parts = NumberUtils.unpack(e.getKey());
			final int count = e.getValue();
			for (int i = 0; i < count; i++) {
				index.addRecord(parts[0], parts[1]);
			}
		}
		return index;
	}

	@Nonnull
	private static Map<Integer, Set<Integer>> deepCopy(@Nonnull Map<Integer, Set<Integer>> source) {
		final Map<Integer, Set<Integer>> copy = new HashMap<>(source.size() << 1);
		for (final Map.Entry<Integer, Set<Integer>> e : source.entrySet()) {
			copy.put(e.getKey(), new HashSet<>(e.getValue()));
		}
		return copy;
	}

	private static void populateRandomMaps(
		@Nonnull Random random, int count,
		@Nonnull Map<Long, Integer> cardinalities,
		@Nonnull Map<Integer, Set<Integer>> refPkToIndexPks
	) {
		for (int i = 0; i < count; i++) {
			final int indexPk = random.nextInt(20) + 1;
			final int refPk = random.nextInt(10) + 1;
			final long composed = NumberUtils.pack(indexPk, refPk);
			if (cardinalities.merge(composed, 1, Integer::sum) == 1) {
				refPkToIndexPks.computeIfAbsent(refPk, k -> new HashSet<>()).add(indexPk);
			}
		}
	}

	private record TestState(
		@Nonnull StringBuilder code,
		@Nonnull Map<Long, Integer> referenceCardinalities,
		@Nonnull Map<Integer, Set<Integer>> refPkToIndexPks
	) {}

}
