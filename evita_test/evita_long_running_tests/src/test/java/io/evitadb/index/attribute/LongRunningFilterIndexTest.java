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

package io.evitadb.index.attribute;

import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.NumberRange;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.index.range.TransactionalRangePoint;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
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

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.FILTER;
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
 * Generational randomized stress test for {@link FilterIndex}. Besides the forward commit proof it also drives the
 * transactional-discard rollback path against a value oracle (Ref: #569); the per-entity savepoint rollback path is
 * exercised by the sibling {@code LongRunningSavepointFilterIndexTest} (Ref: #1252). All three share the same random
 * add/remove/delta batch and the same value-comparable {@link #snapshot(FilterIndex)} oracle.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("FilterIndex (generational proof)")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(FILTER)
class LongRunningFilterIndexTest implements TimeBoundedTestSupport {

	@ParameterizedTest(name = "FilterIndex should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int initialCount = 100;
		final Map<IntegerNumberRange, Integer> rangeToRecord = new HashMap<>();
		final Map<Integer, Set<IntegerNumberRange>> recordRanges = new HashMap<>();

		runFor(
			input,
			100,
			new TestState(
				new StringBuilder(256),
				new OwnerFilterIndex(new AttributeIndexKey(null, "c", null), IntegerNumberRange.class)
			),
			(random, testState) -> {
				final StringBuilder codeBuffer = testState.code();
				codeBuffer.append("final FilterIndex filterIndex = new OwnerFilterIndex(String.class);\n")
					.append(
						rangeToRecord.entrySet()
							.stream()
							.map(it -> "filterIndex.addRecord(" + it.getValue() + ", IntegerNumberRange.between(" + it.getKey().getPreciseFrom() + ", " + it.getKey().getPreciseTo() + "));")
							.collect(Collectors.joining("\n"))
					);
				codeBuffer.append("\nOps:\n");

				final OwnerFilterIndex transactionalFilterIndex = testState.filterIndex();
				final AtomicReference<OwnerFilterIndex> committedResult = new AtomicReference<>();

				assertStateAfterCommit(
					transactionalFilterIndex,
					original -> applyRandomBatch(random, original, rangeToRecord, recordRanges, codeBuffer, initialCount),
					(original, committed) -> {
						assertEquals(
							rangeToRecord.size(),
							recordRanges.values().stream().mapToInt(Set::size).sum(),
							"\n" + rangeToRecord.keySet().stream().sorted().map(NumberRange::toString).collect(Collectors.joining(",")) + " vs. \n" +
							recordRanges.values().stream().flatMap(Set::stream).sorted().map(NumberRange::toString).collect(Collectors.joining(",")) +
							"\n" + codeBuffer
						);

						for (Entry<Integer, Set<IntegerNumberRange>> entry : recordRanges.entrySet()) {
							final Set<IntegerNumberRange> values = entry.getValue();
							final IntegerNumberRange[] actual = committed.getInvertedIndex()
								.getValuesForRecord(entry.getKey(), IntegerNumberRange.class);
							assertArrayEquals(
								values.stream().sorted().toArray(),
								Arrays.stream(actual).sorted().toArray(),
								"\nExpected for `" + entry.getKey() + "`: " + Arrays.toString(values.toArray()) + "\n" +
									"Actual:   " + Arrays.toString(actual) + "\n\n" +
									codeBuffer
							);
						}

						final int[] expected = recordRanges.keySet().stream().mapToInt(it -> it).sorted().toArray();
						assertArrayEquals(
							expected,
							committed.getAllRecords().getArray(),
							"\nExpected: " + Arrays.toString(expected) + "\n" +
								"Actual:  " + Arrays.toString(committed.getAllRecords().getArray()) + "\n\n" +
								codeBuffer
						);

						committedResult.set(
							new OwnerFilterIndex(
								new AttributeIndexKey(null, "a", null),
								committed.getInvertedIndex().getValueToRecordBitmap(),
								committed.getRangeIndex(),
								IntegerNumberRange.class
							)
						);
					}
				);
				return new TestState(
					new StringBuilder(256), committedResult.get()
				);
			}
		);
	}

	/**
	 * Generational proof that a **rolled-back** transaction discards every in-transaction mutation and leaves the base
	 * index byte-for-byte intact — the atomic-rollback contract of Ref: #569. Each generation rebuilds a fresh index from
	 * the (random-walking) reference model, captures a value oracle of that base, applies the shared random batch of
	 * add/remove/delta mutations inside a transaction that is then rolled back, and asserts the base index is unchanged
	 * and no committed value was published.
	 */
	@ParameterizedTest(name = "FilterIndex rollback discards every in-transaction mutation and leaves the base intact")
	@Tag(SLOW)
	@Tag(TRANSACTION)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalRollbackProofTest(GenerationalTestInput input) {
		final int initialCount = 100;
		final Map<IntegerNumberRange, Integer> rangeToRecord = new HashMap<>();
		final Map<Integer, Set<IntegerNumberRange>> recordRanges = new HashMap<>();

		runFor(
			input,
			100,
			0,
			(random, iteration) -> {
				final StringBuilder codeBuffer = new StringBuilder(256);
				// rebuild a fresh base index from the (random-walking) reference model
				final OwnerFilterIndex filterIndex = buildReferenceIndex(recordRanges, codeBuffer);
				codeBuffer.append("\nOps:\n");
				// value oracle of the base state that the rollback must return to
				final FilterSnapshot beforeRollback = snapshot(filterIndex);

				assertStateAfterRollback(
					filterIndex,
					original -> applyRandomBatch(random, original, rangeToRecord, recordRanges, codeBuffer, initialCount),
					(original, committed) -> {
						assertNull(committed,
							"A rolled-back transaction must not publish a committed value!\n" + codeBuffer);
						assertEquals(beforeRollback, snapshot(original),
							"FilterIndex changed after rollback — atomic rollback leaked!\n" + codeBuffer);
					}
				);

				// the reference model reflects the attempted (rolled-back) batch, so the next generation starts from a
				// different live base index — a random walk that keeps the proof exploring fresh states
				return iteration + 1;
			}
		);
	}

	/**
	 * Applies a random batch of add / add-delta / remove / remove-delta mutations to `filterIndex`, mirroring each
	 * mutation into the `rangeToRecord` / `recordRanges` reference model so the two stay in lockstep. Shared verbatim by
	 * the commit and rollback proofs, so the commit path keeps the identical random-draw sequence.
	 */
	private static void applyRandomBatch(
		@Nonnull Random random,
		@Nonnull OwnerFilterIndex filterIndex,
		@Nonnull Map<IntegerNumberRange, Integer> rangeToRecord,
		@Nonnull Map<Integer, Set<IntegerNumberRange>> recordRanges,
		@Nonnull StringBuilder codeBuffer,
		int initialCount
	) {
		try {
			final int operationsInTransaction = random.nextInt(100);
			for (int i = 0; i < operationsInTransaction; i++) {
				final int length = filterIndex.size();
				if ((random.nextBoolean() || length < 10) && length < 50) {
					// insert new item
					IntegerNumberRange range;
					do {
						final int from = random.nextInt(initialCount);
						final int to = random.nextInt(initialCount);
						range = IntegerNumberRange.between(Math.min(from, to), Math.max(from, to));
					} while (rangeToRecord.containsKey(range));

					int newRecId = random.nextInt(initialCount);

					final Set<IntegerNumberRange> theRecordValues;
					final Set<IntegerNumberRange> existingRecordValues = recordRanges.get(newRecId);
					if (existingRecordValues == null) {
						theRecordValues = new HashSet<>();
						theRecordValues.add(range);
						recordRanges.put(newRecId, theRecordValues);

						codeBuffer.append("filterIndex.addRecord(")
							.append(newRecId).append(",").append("IntegerNumberRange.between(" + range.getPreciseFrom() + ", " + range.getPreciseTo() + ")").append(");\n");
						filterIndex.addRecord(newRecId, range);
					} else {
						theRecordValues = existingRecordValues;
						theRecordValues.add(range);

						codeBuffer.append("filterIndex.addRecordDelta(")
							.append(newRecId)
							.append(", new IntegerNumberRange[] { ")
							.append("IntegerNumberRange.between(")
							.append(range.getPreciseFrom())
							.append(", ")
							.append(range.getPreciseTo())
							.append(")")
							.append(" });\n");
						filterIndex.addRecordDelta(newRecId, new IntegerNumberRange[] { range });
					}
					rangeToRecord.put(range, newRecId);
				} else {
					// remove existing item
					final Iterator<Entry<IntegerNumberRange, Integer>> it = rangeToRecord.entrySet().iterator();
					Entry<IntegerNumberRange, Integer> valueToRemove = null;
					for (int j = 0; j < random.nextInt(length) + 1; j++) {
						valueToRemove = it.next();
					}

					final Integer removedRecordId = valueToRemove.getValue();
					it.remove();

					boolean removeEntirely = random.nextInt(10) == 0;
					final Set<IntegerNumberRange> theCurrentRecordValues = recordRanges.get(removedRecordId);

					if (!removeEntirely && theCurrentRecordValues.size() > 1) {
						final IntegerNumberRange range = valueToRemove.getKey();
						recordRanges.put(
							removedRecordId,
							theCurrentRecordValues.stream()
								.filter(item -> !item.equals(range))
								.collect(Collectors.toSet())
						);
						codeBuffer.append("filterIndex.removeRecordDelta(")
							.append(removedRecordId).append(", ")
							.append("new IntegerNumberRange[] { IntegerNumberRange.between(" + range.getPreciseFrom() + ", " + range.getPreciseTo() + ") }")
							.append(");\n");
						filterIndex.removeRecordDelta(
							removedRecordId,
							new IntegerNumberRange[] {range}
						);
					} else {
						recordRanges.remove(removedRecordId);
						final IntegerNumberRange[] allRemovedValues = theCurrentRecordValues.stream().sorted().toArray(IntegerNumberRange[]::new);
						for (IntegerNumberRange additionalValueRemoved : allRemovedValues) {
							rangeToRecord.remove(additionalValueRemoved);
						}
						codeBuffer.append("filterIndex.removeRecord(")
							.append(removedRecordId).append(", new IntegerNumberRange[] { ")
							.append(Arrays.stream(allRemovedValues).map(range -> "IntegerNumberRange.between(" + range.getPreciseFrom() + ", " + range.getPreciseTo() + ")").collect(Collectors.joining(", ")))
							.append(" });\n");
						filterIndex.removeRecord(
							removedRecordId,
							allRemovedValues
						);
					}
				}
			}
		} catch (Exception ex) {
			fail("\n" + codeBuffer, ex);
		}
	}

	/**
	 * Rebuilds a fresh {@link OwnerFilterIndex} from the `recordRanges` reference model (the first value of every record
	 * via `addRecord`, each further value via `addRecordDelta`), appending the reconstruction to `codeBuffer` for
	 * failure diagnostics. Used by the rollback proof so each generation starts from a base index that matches the
	 * (random-walking) model exactly.
	 */
	@Nonnull
	private static OwnerFilterIndex buildReferenceIndex(
		@Nonnull Map<Integer, Set<IntegerNumberRange>> recordRanges,
		@Nonnull StringBuilder codeBuffer
	) {
		final OwnerFilterIndex filterIndex = new OwnerFilterIndex(
			new AttributeIndexKey(null, "c", null), IntegerNumberRange.class
		);
		for (final Entry<Integer, Set<IntegerNumberRange>> entry : recordRanges.entrySet()) {
			final int recordId = entry.getKey();
			boolean first = true;
			for (final IntegerNumberRange range : entry.getValue()) {
				if (first) {
					codeBuffer.append("filterIndex.addRecord(").append(recordId)
						.append(", IntegerNumberRange.between(").append(range.getPreciseFrom())
						.append(", ").append(range.getPreciseTo()).append("));\n");
					filterIndex.addRecord(recordId, range);
					first = false;
				} else {
					codeBuffer.append("filterIndex.addRecordDelta(").append(recordId)
						.append(", new IntegerNumberRange[] { IntegerNumberRange.between(").append(range.getPreciseFrom())
						.append(", ").append(range.getPreciseTo()).append(") });\n");
					filterIndex.addRecordDelta(recordId, new IntegerNumberRange[] { range });
				}
			}
		}
		return filterIndex;
	}

	/**
	 * Reads the full logical content of the index into a value-comparable snapshot: the inverted index as value →
	 * sorted record ids, plus the range companion as an ordered list of `(threshold, starts, ends)` points. Two
	 * snapshots taken before and after a rollback can be compared with `.equals` to prove exact restoration. Both reads
	 * are transaction-aware, so the same helper doubles as the savepoint oracle reader.
	 */
	@Nonnull
	static FilterSnapshot snapshot(@Nonnull FilterIndex index) {
		final Map<String, List<Integer>> valueToRecords = new HashMap<>();
		for (final ValueToRecordBitmap bucket : index.getInvertedIndex().getValueToRecordBitmap()) {
			valueToRecords.put(String.valueOf(bucket.getValue()), toList(bucket.getRecordIds()));
		}
		final List<RangePointSnapshot> rangePoints = new ArrayList<>();
		final RangeIndex rangeIndex = index.getRangeIndex();
		if (rangeIndex != null) {
			final Iterator<TransactionalRangePoint> it = rangeIndex.rangesIterator();
			while (it.hasNext()) {
				final TransactionalRangePoint point = it.next();
				rangePoints.add(
					new RangePointSnapshot(point.getThreshold(), toList(point.getStarts()), toList(point.getEnds()))
				);
			}
		}
		return new FilterSnapshot(valueToRecords, rangePoints);
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
		OwnerFilterIndex filterIndex
	) {}

	/**
	 * Value-comparable snapshot of a {@link FilterIndex}: the inverted index (value string → sorted record ids) and the
	 * range companion (ordered `(threshold, starts, ends)` points). Record equality gives deep structural comparison.
	 */
	record FilterSnapshot(
		@Nonnull Map<String, List<Integer>> valueToRecords,
		@Nonnull List<RangePointSnapshot> rangePoints
	) {}

	/**
	 * One range-index point captured as a value type: its `long` threshold and the ascending record-id lists of the
	 * records that start and end at it.
	 */
	record RangePointSnapshot(
		long threshold,
		@Nonnull List<Integer> starts,
		@Nonnull List<Integer> ends
	) {}

}
