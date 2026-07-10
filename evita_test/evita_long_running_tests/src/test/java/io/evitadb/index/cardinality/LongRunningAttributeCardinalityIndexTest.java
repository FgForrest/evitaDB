/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.index.cardinality.AttributeCardinalityIndex.AttributeCardinalityKey;
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
import java.util.List;
import java.util.Map;
import java.util.Random;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Generational randomized proof test for {@link AttributeCardinalityIndex}. Besides the forward commit proof it also
 * drives the transactional-discard rollback path against a value oracle (Ref: #569); the per-entity savepoint rollback
 * (Ref: #1252) is exercised by the sibling {@code LongRunningSavepointAttributeCardinalityIndexTest}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("AttributeCardinalityIndex (generational proof)")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class LongRunningAttributeCardinalityIndexTest implements TimeBoundedTestSupport {

	@DisplayName("survives generational randomized test applying modifications on it")
	@ParameterizedTest(name = "AttributeCardinalityIndex should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int initialCount = 30;
		final Map<AttributeCardinalityKey, Integer> initialMap =
			generateRandomInitialMap(new Random(input.randomSeed()), initialCount);

		runFor(
			input,
			10_000,
			new TestState(new StringBuilder(256), initialMap),
			(random, testState) -> {
				final AttributeCardinalityIndex index = new AttributeCardinalityIndex(
					String.class, new HashMap<>(testState.referenceMap())
				);
				final Map<AttributeCardinalityKey, Integer> referenceMap =
					new HashMap<>(testState.referenceMap());

				assertStateAfterCommit(
					index,
					original -> applyRandomBatch(random, original, referenceMap),
					(original, committed) -> {
						assertEquals(referenceMap, committed.getCardinalities());
						assertEquals(referenceMap.isEmpty(), committed.isEmpty());
					}
				);

				return new TestState(new StringBuilder(256), referenceMap);
			}
		);
	}

	/**
	 * Generational proof that a **rolled-back** transaction discards every in-transaction cardinality mutation and
	 * leaves the base index intact — the atomic-rollback contract of Ref: #569. Each generation rebuilds a fresh index
	 * from the (random-walking) reference model, captures a value oracle of that base, applies a random add/remove batch
	 * inside a transaction that is then rolled back, and asserts the base index is unchanged and no committed value was
	 * published.
	 */
	@DisplayName("rollback discards every in-transaction mutation and leaves the base intact")
	@ParameterizedTest(name = "AttributeCardinalityIndex rollback discards every in-transaction mutation and leaves the base intact")
	@Tag(SLOW)
	@Tag(TRANSACTION)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalRollbackProofTest(GenerationalTestInput input) {
		final int initialCount = 30;
		final Map<AttributeCardinalityKey, Integer> initialMap =
			generateRandomInitialMap(new Random(input.randomSeed()), initialCount);

		runFor(
			input,
			10_000,
			new TestState(new StringBuilder(256), initialMap),
			(random, testState) -> {
				final AttributeCardinalityIndex index = new AttributeCardinalityIndex(
					String.class, new HashMap<>(testState.referenceMap())
				);
				final Map<AttributeCardinalityKey, Integer> referenceMap =
					new HashMap<>(testState.referenceMap());
				// value oracle of the base state that the rollback must return to
				final AttributeCardinalitySnapshot beforeRollback = snapshot(index);

				assertStateAfterRollback(
					index,
					original -> applyRandomBatch(random, original, referenceMap),
					(original, committed) -> {
						assertNull(
							committed,
							"A rolled-back transaction must not publish a committed value!"
						);
						assertEquals(
							beforeRollback, snapshot(original),
							"AttributeCardinalityIndex changed after rollback — atomic rollback leaked!"
						);
					}
				);

				// the reference model reflects the attempted (rolled-back) batch, so the next generation seeds a
				// different base index — a random walk that keeps the proof exploring fresh states
				return new TestState(new StringBuilder(256), referenceMap);
			}
		);
	}

	/**
	 * Applies a random batch of 1–5 add/remove cardinality mutations to `index`, mirroring each mutation into the
	 * `referenceMap` model so the two stay in lockstep. Shared by the commit and rollback proofs so both drive the
	 * identical random-draw sequence (behaviour-identical extraction of the former inline commit batch).
	 */
	private static void applyRandomBatch(
		@Nonnull Random random,
		@Nonnull AttributeCardinalityIndex index,
		@Nonnull Map<AttributeCardinalityKey, Integer> referenceMap
	) {
		final int opCount = random.nextInt(5) + 1;
		for (int i = 0; i < opCount; i++) {
			final int operation = referenceMap.isEmpty() ? 0 : random.nextInt(4);
			if (operation == 0) {
				// add new (value, recordId) — may coincide with existing, incrementing count
				final String value = String.valueOf((char) ('a' + random.nextInt(8)));
				final int recordId = random.nextInt(10) + 1;
				final AttributeCardinalityKey key = new AttributeCardinalityKey(recordId, value);
				index.addRecord(value, recordId);
				referenceMap.merge(key, 1, Integer::sum);
			} else if (operation == 1) {
				// increment cardinality of a randomly chosen existing entry
				final List<AttributeCardinalityKey> keys =
					new ArrayList<>(referenceMap.keySet());
				final AttributeCardinalityKey key = keys.get(random.nextInt(keys.size()));
				index.addRecord((String) key.value(), key.recordId());
				referenceMap.merge(key, 1, Integer::sum);
			} else if (operation == 2) {
				// decrement an entry with count > 1; fall back to increment when none exists
				final List<AttributeCardinalityKey> candidates =
					new ArrayList<>(referenceMap.size());
				for (final Map.Entry<AttributeCardinalityKey, Integer> e : referenceMap.entrySet()) {
					if (e.getValue() > 1) {
						candidates.add(e.getKey());
					}
				}
				if (candidates.isEmpty()) {
					final List<AttributeCardinalityKey> keys =
						new ArrayList<>(referenceMap.keySet());
					final AttributeCardinalityKey key = keys.get(random.nextInt(keys.size()));
					index.addRecord((String) key.value(), key.recordId());
					referenceMap.merge(key, 1, Integer::sum);
				} else {
					final AttributeCardinalityKey key =
						candidates.get(random.nextInt(candidates.size()));
					index.removeRecord((String) key.value(), key.recordId());
					referenceMap.merge(key, -1, Integer::sum);
				}
			} else {
				// fully remove an entry with count == 1; fall back to increment when none exists
				final List<AttributeCardinalityKey> candidates =
					new ArrayList<>(referenceMap.size());
				for (final Map.Entry<AttributeCardinalityKey, Integer> e : referenceMap.entrySet()) {
					if (e.getValue() == 1) {
						candidates.add(e.getKey());
					}
				}
				if (candidates.isEmpty()) {
					final List<AttributeCardinalityKey> keys =
						new ArrayList<>(referenceMap.keySet());
					final AttributeCardinalityKey key = keys.get(random.nextInt(keys.size()));
					index.addRecord((String) key.value(), key.recordId());
					referenceMap.merge(key, 1, Integer::sum);
				} else {
					final AttributeCardinalityKey key =
						candidates.get(random.nextInt(candidates.size()));
					index.removeRecord((String) key.value(), key.recordId());
					referenceMap.remove(key);
				}
			}
		}
	}

	@Nonnull
	private static Map<AttributeCardinalityKey, Integer> generateRandomInitialMap(
		@Nonnull Random random, int count
	) {
		final Map<AttributeCardinalityKey, Integer> map = new HashMap<>(count * 2);
		for (int i = 0; i < count; i++) {
			final String value = String.valueOf((char) ('a' + random.nextInt(8)));
			final int recordId = random.nextInt(10) + 1;
			map.merge(new AttributeCardinalityKey(recordId, value), 1, Integer::sum);
		}
		return map;
	}

	/**
	 * Reads the full logical content of the index into a value-comparable snapshot (the whole
	 * `(recordId, value) → cardinality` map, defensively copied), so two snapshots taken before and after a rollback
	 * can be compared with `.equals` to prove exact restoration.
	 */
	@Nonnull
	static AttributeCardinalitySnapshot snapshot(@Nonnull AttributeCardinalityIndex index) {
		return new AttributeCardinalitySnapshot(new HashMap<>(index.getCardinalities()));
	}

	private record TestState(
		@Nonnull StringBuilder code,
		@Nonnull Map<AttributeCardinalityKey, Integer> referenceMap
	) {}

	/**
	 * Value-comparable snapshot of an {@link AttributeCardinalityIndex}: the full `(recordId, value) → cardinality`
	 * map. Record equality gives deep structural comparison.
	 */
	record AttributeCardinalitySnapshot(
		@Nonnull Map<AttributeCardinalityKey, Integer> cardinalities
	) {}

}
