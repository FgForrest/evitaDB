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

package io.evitadb.index.range;

import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Generational randomized proof test for {@link RangeIndex}. Besides the forward commit proof it also drives the
 * transactional-discard atomic-rollback path against a value oracle (Ref: #569); the per-entity savepoint rollback
 * (Ref: #1252) is exercised by the sibling {@code LongRunningSavepointRangeIndexTest}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@Tag(INDEXING)
@Tag(DATA_TYPE)
class LongRunningRangeIndexTest implements TimeBoundedTestSupport {
	private static final int OPTIMAL_COUNT = 100;

	@ParameterizedTest(name = "RangeIndex should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final Map<IntegerNumberRange, Integer> initialState = new HashMap<>();
		final Set<Integer> currentRecordSet = new HashSet<>();
		final Set<IntegerNumberRange> uniqueValues = new HashSet<>();

		runFor(
			input,
			100,
			new TestState(new StringBuilder(), new RangeIndex()),
			(random, testState) -> {
				final RangeIndex intRangeIndex = testState.rangeIndex();
				final AtomicReference<RangeIndex> committedResult = new AtomicReference<>();

				final StringBuilder codeBuffer = testState.code();
				codeBuffer
					.append("final RangeIndex intRangeIndex = new RangeIndex();\n")
					.append(initialState.entrySet().stream().map(it -> "intRangeIndex.addRecord(" + it.getKey().getFrom() + "," + it.getKey().getTo() + "," + it.getValue() + ");").collect(Collectors.joining("\n")))
					.append("\nOps:\n");

				assertStateAfterCommit(
					intRangeIndex,
					original -> applyRandomBatch(random, original, initialState, currentRecordSet, uniqueValues, codeBuffer),
					(original, committed) -> {
						final int[] expected = currentRecordSet.stream().mapToInt(it -> it).sorted().toArray();
						assertArrayEquals(
							expected,
							committed.getAllRecords().getArray(),
							"\nExpected: " + Arrays.toString(expected) + "\n" +
								"Actual:  " + Arrays.toString(committed.getAllRecords().getArray()) + "\n\n" +
								codeBuffer
						);

						// rebuild the index from the committed tree snapshot (simulates a serialization round-trip
						// across generations); the tree is no longer array-backed, so collect its values explicitly
						final List<TransactionalRangePoint> committedPoints = new ArrayList<>(committed.ranges.size());
						final Iterator<TransactionalRangePoint> rangeIt = committed.ranges.valueIterator();
						while (rangeIt.hasNext()) {
							committedPoints.add(rangeIt.next());
						}
						committedResult.set(
							new RangeIndex(committedPoints.toArray(new TransactionalRangePoint[0]))
						);
					}
				);

				return new TestState(
					new StringBuilder(),
					committedResult.get()
				);
			},
			(testState, throwable) -> System.out.println(testState.code())
		);
	}

	/**
	 * Generational proof that a **rolled-back** transaction discards every in-transaction mutation and leaves the base
	 * index byte-for-byte intact — the atomic-rollback contract of Ref: #569. Each generation rebuilds a fresh index
	 * from the (random-walking) reference model, captures a value oracle of that base, applies a random batch of
	 * add/remove mutations inside a transaction that is then rolled back, and asserts the base index is unchanged and no
	 * committed value was published.
	 */
	@ParameterizedTest(name = "RangeIndex rollback discards every in-transaction mutation and leaves the base intact")
	@Tag(SLOW)
	@Tag(TRANSACTION)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalRollbackProofTest(GenerationalTestInput input) {
		final Map<IntegerNumberRange, Integer> initialState = new HashMap<>();
		final Set<Integer> currentRecordSet = new HashSet<>();
		final Set<IntegerNumberRange> uniqueValues = new HashSet<>();

		runFor(
			input,
			100,
			new TestState(new StringBuilder(), new RangeIndex()),
			(random, testState) -> {
				final StringBuilder codeBuffer = testState.code();
				codeBuffer
					.append("final RangeIndex intRangeIndex = new RangeIndex();\n")
					.append(initialState.entrySet().stream().map(it -> "intRangeIndex.addRecord(" + it.getKey().getFrom() + "," + it.getKey().getTo() + "," + it.getValue() + ");").collect(Collectors.joining("\n")))
					.append("\nOps:\n");

				// rebuild a fresh base index from the (random-walking) reference model
				final RangeIndex intRangeIndex = buildRangeIndex(initialState);
				// value oracle of the base state that the rollback must return to
				final RangeSnapshot beforeRollback = snapshot(intRangeIndex);

				assertStateAfterRollback(
					intRangeIndex,
					original -> applyRandomBatch(random, original, initialState, currentRecordSet, uniqueValues, codeBuffer),
					(original, committed) -> {
						assertNull(committed,
							"A rolled-back transaction must not publish a committed value!\n" + codeBuffer);
						assertEquals(beforeRollback, snapshot(original),
							"RangeIndex changed after rollback — atomic rollback leaked!\n" + codeBuffer);
					}
				);

				// the reference model reflects the attempted (rolled-back) batch, so the next generation rebuilds a
				// different base index — a random walk that keeps the proof exploring fresh base indexes
				return new TestState(new StringBuilder(), intRangeIndex);
			},
			(testState, throwable) -> System.out.println(testState.code())
		);
	}

	/**
	 * Applies a random batch of add/remove mutations to `intRangeIndex`, mirroring each mutation into the
	 * `initialState` / `currentRecordSet` / `uniqueValues` reference model so the two stay in lockstep. Shared by the
	 * commit and rollback proofs so both drive the identical random-draw sequence.
	 */
	private static void applyRandomBatch(
		@Nonnull Random random,
		@Nonnull RangeIndex intRangeIndex,
		@Nonnull Map<IntegerNumberRange, Integer> initialState,
		@Nonnull Set<Integer> currentRecordSet,
		@Nonnull Set<IntegerNumberRange> uniqueValues,
		@Nonnull StringBuilder codeBuffer
	) {
		try {
			final int operationsInTransaction = random.nextInt(100);
			for (int i = 0; i < operationsInTransaction; i++) {
				final int length = currentRecordSet.size();
				if ((random.nextBoolean() || length < 10) && length < 50) {
					// insert new item
					IntegerNumberRange range;
					do {
						final int from = random.nextInt(OPTIMAL_COUNT * 2);
						final int to = random.nextInt(OPTIMAL_COUNT * 2);
						range = IntegerNumberRange.between(Math.min(from, to), Math.max(from, to));
					} while (uniqueValues.contains(range));

					int newRecId;
					do {
						newRecId = random.nextInt(OPTIMAL_COUNT);
					} while (currentRecordSet.contains(newRecId));
					initialState.put(range, newRecId);
					currentRecordSet.add(newRecId);
					uniqueValues.add(range);

					codeBuffer.append("intRangeIndex.addRecord(").append(range.getFrom()).append(",").append(range.getTo()).append(",").append(newRecId).append(");\n");
					intRangeIndex.addRecord(range.getFrom(), range.getTo(), newRecId);
				} else {
					// remove existing item
					final Iterator<Entry<IntegerNumberRange, Integer>> it = initialState.entrySet().iterator();
					Entry<IntegerNumberRange, Integer> valueToRemove = null;
					final int itemToRemove = random.nextInt(length);
					for (int j = 0; j < itemToRemove + 1; j++) {
						valueToRemove = it.next();
					}
					it.remove();
					currentRecordSet.remove(valueToRemove.getValue());
					uniqueValues.remove(valueToRemove.getKey());

					codeBuffer.append("intRangeIndex.removeRecord(").append(valueToRemove.getKey().getFrom()).append(",").append(valueToRemove.getKey().getTo()).append(",").append(valueToRemove.getValue()).append(");\n");
					intRangeIndex.removeRecord(valueToRemove.getKey().getFrom(), valueToRemove.getKey().getTo(), valueToRemove.getValue());
				}
			}
		} catch (Exception ex) {
			fail("\n" + codeBuffer, ex);
		}
	}

	/**
	 * Rebuilds a {@link RangeIndex} from the reference model. Every unique record id maps to exactly one unique range,
	 * so no shared-border conflicts arise; content equality of {@link RangeIndex} is order-independent, so the insertion
	 * order does not matter.
	 */
	@Nonnull
	private static RangeIndex buildRangeIndex(@Nonnull Map<IntegerNumberRange, Integer> initialState) {
		final RangeIndex index = new RangeIndex();
		for (final Entry<IntegerNumberRange, Integer> entry : initialState.entrySet()) {
			index.addRecord(entry.getKey().getFrom(), entry.getKey().getTo(), entry.getValue());
		}
		return index;
	}

	/**
	 * Reads the full logical content of the index (transaction-aware) into a value-comparable snapshot — the ordered
	 * sequence of range points, each as (threshold, sorted starts, sorted ends) — so two snapshots taken before and
	 * after a rollback can be compared with `.equals` to prove exact restoration.
	 */
	@Nonnull
	static RangeSnapshot snapshot(@Nonnull RangeIndex index) {
		final List<RangePointSnapshot> points = new ArrayList<>();
		final Iterator<TransactionalRangePoint> it = index.rangesIterator();
		while (it.hasNext()) {
			final TransactionalRangePoint point = it.next();
			points.add(new RangePointSnapshot(point.getThreshold(), toList(point.getStarts()), toList(point.getEnds())));
		}
		return new RangeSnapshot(points);
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
		StringBuilder code,
		RangeIndex rangeIndex
	) {}

	/**
	 * Value-comparable snapshot of a {@link RangeIndex}: the ordered range points. Record equality gives deep
	 * structural comparison.
	 */
	record RangeSnapshot(@Nonnull List<RangePointSnapshot> points) {}

	/**
	 * One range point of a {@link RangeSnapshot}: its threshold and the ascending record ids that start / end there.
	 */
	record RangePointSnapshot(long threshold, @Nonnull List<Integer> starts, @Nonnull List<Integer> ends) {}

}
