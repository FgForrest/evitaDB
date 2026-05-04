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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Generational randomized stress test for {@link FilterIndex}. Verifies the contract under random
 * sequences of add, remove, and delta operations applied within transactional commit boundaries.
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
				new FilterIndex(new AttributeIndexKey(null, "c", null), IntegerNumberRange.class)
			),
			(random, testState) -> {
				final StringBuilder codeBuffer = testState.code();
				codeBuffer.append("final FilterIndex filterIndex = new FilterIndex(String.class);\n")
					.append(
						rangeToRecord.entrySet()
							.stream()
							.map(it -> "filterIndex.addRecord(" + it.getValue() + ", IntegerNumberRange.between(" + it.getKey().getPreciseFrom() + ", " + it.getKey().getPreciseTo() + "));")
							.collect(Collectors.joining("\n"))
					);
				codeBuffer.append("\nOps:\n");

				final FilterIndex transactionalFilterIndex = testState.filterIndex();
				final AtomicReference<FilterIndex> committedResult = new AtomicReference<>();

				assertStateAfterCommit(
					transactionalFilterIndex,
					original -> {
						try {
							final int operationsInTransaction = random.nextInt(100);
							for (int i = 0; i < operationsInTransaction; i++) {
								final int length = transactionalFilterIndex.size();
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
										transactionalFilterIndex.addRecord(newRecId, range);
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
										transactionalFilterIndex.addRecordDelta(newRecId, new IntegerNumberRange[] { range });
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
										transactionalFilterIndex.removeRecordDelta(
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
										transactionalFilterIndex.removeRecord(
											removedRecordId,
											allRemovedValues
										);
									}
								}
							}
						} catch (Exception ex) {
							fail("\n" + codeBuffer, ex);
						}
					},
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
							new FilterIndex(
								new AttributeIndexKey(null, "a", null),
								committed.getInvertedIndex().getValueToRecordBitmap(),
								committed.getRangeIndex(),
								Integer.class
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

	private record TestState(
		StringBuilder code,
		FilterIndex filterIndex
	) {}

}
