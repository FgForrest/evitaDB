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
import io.evitadb.index.bitmap.TransactionalBitmap;
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
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generational randomized proof test for {@link ReferenceTypeCardinalityIndex}. Besides the forward commit proof it
 * also drives the transactional-discard rollback path against a value oracle (Ref: #569); the per-entity savepoint
 * rollback (Ref: #1252) is exercised by the sibling {@code LongRunningSavepointReferenceTypeCardinalityIndexTest}.
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
					original -> applyRandomBatch(random, original, referenceCardinalities, refPkToIndexPks),
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

	/**
	 * Generational proof that a **rolled-back** transaction discards every in-transaction cardinality mutation and
	 * leaves the base index intact — the atomic-rollback contract of Ref: #569. Each generation rebuilds a fresh index
	 * from the (random-walking) reference model, captures a value oracle of that base (both the composed-key cardinality
	 * tree and the companion referenced-primary-keys map), applies a random add/remove batch inside a transaction that
	 * is then rolled back, and asserts the base index is unchanged and no committed value was published.
	 */
	@DisplayName("rollback discards every in-transaction mutation and leaves the base intact")
	@ParameterizedTest(name = "ReferenceTypeCardinalityIndex rollback discards every in-transaction mutation and leaves the base intact")
	@Tag(SLOW)
	@Tag(TRANSACTION)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalRollbackProofTest(GenerationalTestInput input) {
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
				// value oracle of the base state that the rollback must return to
				final ReferenceCardinalitySnapshot beforeRollback = snapshot(index);

				assertStateAfterRollback(
					index,
					original -> applyRandomBatch(random, original, referenceCardinalities, refPkToIndexPks),
					(original, committed) -> {
						assertNull(
							committed,
							"A rolled-back transaction must not publish a committed value!"
						);
						assertEquals(
							beforeRollback, snapshot(original),
							"ReferenceTypeCardinalityIndex changed after rollback — atomic rollback leaked!"
						);
					}
				);

				// the reference model reflects the attempted (rolled-back) batch, so the next generation seeds a
				// different base index — a random walk that keeps the proof exploring fresh states
				return new TestState(new StringBuilder(256), referenceCardinalities, refPkToIndexPks);
			}
		);
	}

	/**
	 * Applies a random batch of 1–5 add/remove cardinality mutations to `index`, mirroring each mutation into the
	 * `referenceCardinalities` (pair count) and `refPkToIndexPks` (forward index) models so all three stay in lockstep.
	 * Shared by the commit and rollback proofs so both drive the identical random-draw sequence (behaviour-identical
	 * extraction of the former inline commit batch).
	 */
	private static void applyRandomBatch(
		@Nonnull Random random,
		@Nonnull ReferenceTypeCardinalityIndex index,
		@Nonnull Map<Long, Integer> referenceCardinalities,
		@Nonnull Map<Integer, Set<Integer>> refPkToIndexPks
	) {
		final int opCount = random.nextInt(5) + 1;
		for (int i = 0; i < opCount; i++) {
			final int operation = referenceCardinalities.isEmpty() ? 0 : random.nextInt(4);
			if (operation == 0) {
				// add a new or already-present (indexPk, refPk) pair
				final int indexPk = random.nextInt(20) + 1;
				final int refPk = random.nextInt(10) + 1;
				final long composed = NumberUtils.pack(indexPk, refPk);
				index.addRecord(indexPk, refPk);
				if (referenceCardinalities.merge(composed, 1, Integer::sum) == 1) {
					// first occurrence: register in forward index
					refPkToIndexPks.computeIfAbsent(refPk, k -> new HashSet<>()).add(indexPk);
				}
			} else if (operation == 1) {
				// re-add (increment) a randomly chosen existing pair
				final List<Long> keys = new ArrayList<>(referenceCardinalities.keySet());
				final long composed = keys.get(random.nextInt(keys.size()));
				final int[] parts = NumberUtils.unpack(composed);
				index.addRecord(parts[0], parts[1]);
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
					index.addRecord(parts[0], parts[1]);
					referenceCardinalities.merge(composed, 1, Integer::sum);
				} else {
					final long composed = candidates.get(random.nextInt(candidates.size()));
					final int[] parts = NumberUtils.unpack(composed);
					index.removeRecord(parts[0], parts[1]);
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
					index.addRecord(parts[0], parts[1]);
					referenceCardinalities.merge(composed, 1, Integer::sum);
				} else {
					final long composed = candidates.get(random.nextInt(candidates.size()));
					final int[] parts = NumberUtils.unpack(composed);
					index.removeRecord(parts[0], parts[1]);
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

	/**
	 * Reads the full logical content of the index into a value-comparable snapshot: the internal composed-key →
	 * cardinality-count map (defensively copied) plus the companion `referencedEntityPrimaryKey → sorted index-PK list`
	 * map (each bitmap converted to a sorted `List` via `getArray()`), so two snapshots taken before and after a
	 * rollback can be compared with `.equals` to prove exact restoration.
	 */
	@Nonnull
	static ReferenceCardinalitySnapshot snapshot(@Nonnull ReferenceTypeCardinalityIndex index) {
		final Map<Long, Integer> cardinalities = new HashMap<>(index.getCardinalities());
		final Map<Integer, List<Integer>> referencedPrimaryKeys = new HashMap<>();
		for (final Map.Entry<Integer, TransactionalBitmap> entry :
			index.getReferencedPrimaryKeysIndex().entrySet()) {
			referencedPrimaryKeys.put(entry.getKey(), toList(entry.getValue()));
		}
		return new ReferenceCardinalitySnapshot(cardinalities, referencedPrimaryKeys);
	}

	/**
	 * Converts a bitmap into an ascending list of its record ids (a value type with deep `.equals`).
	 */
	@Nonnull
	private static List<Integer> toList(@Nonnull Bitmap bitmap) {
		final int[] array = bitmap.getArray();
		final List<Integer> list = new ArrayList<>(array.length);
		for (final int value : array) {
			list.add(value);
		}
		return list;
	}

	private record TestState(
		@Nonnull StringBuilder code,
		@Nonnull Map<Long, Integer> referenceCardinalities,
		@Nonnull Map<Integer, Set<Integer>> refPkToIndexPks
	) {}

	/**
	 * Value-comparable snapshot of a {@link ReferenceTypeCardinalityIndex}: the internal composed-key → cardinality
	 * count map and the companion `referencedEntityPrimaryKey → sorted index-PK list` map. Record equality gives deep
	 * structural comparison.
	 */
	record ReferenceCardinalitySnapshot(
		@Nonnull Map<Long, Integer> cardinalities,
		@Nonnull Map<Integer, List<Integer>> referencedPrimaryKeys
	) {}

}
