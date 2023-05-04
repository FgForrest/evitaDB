/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * This test verifies {@link FilterIndex} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class FilterIndexTest implements TimeBoundedTestSupport {
	private final FilterIndex stringAttribute = new FilterIndex(String.class);
	private final FilterIndex rangeAttribute = new FilterIndex(NumberRange.class);

	@Test
	void deliberateTestFailure() {
		fail("Deliberate test failure.");
	}

	@Test
	void shouldInsertNewStringRecordId() {
		stringAttribute.addRecord(1, "A");
		stringAttribute.addRecord(2, new String[] {"A", "B"});
		assertArrayEquals(new int[] {1, 2}, stringAttribute.getRecordsEqualTo("A").getArray());
		assertArrayEquals(new int[] {2}, stringAttribute.getRecordsEqualTo("B").getArray());
		assertEquals(2, stringAttribute.getAllRecords().size());
	}

	@Test
	void shouldInsertNewStringRecordIdInTheMiddle() {
		stringAttribute.addRecord(1, "A");
		stringAttribute.addRecord(3, "C");
		assertArrayEquals(new int[] {1}, stringAttribute.getRecordsEqualTo("A").getArray());
		assertArrayEquals(new int[] {3}, stringAttribute.getRecordsEqualTo("C").getArray());
		assertEquals(2, stringAttribute.getAllRecords().size());
		stringAttribute.addRecord(2, "B");
		assertArrayEquals(new int[] {1}, stringAttribute.getRecordsEqualTo("A").getArray());
		assertArrayEquals(new int[] {2}, stringAttribute.getRecordsEqualTo("B").getArray());
		assertArrayEquals(new int[] {3}, stringAttribute.getRecordsEqualTo("C").getArray());
		assertEquals(3, stringAttribute.getAllRecords().size());
	}

	@Test
	void shouldInsertNewStringRecordIdInTheBeginning() {
		stringAttribute.addRecord(1, "C");
		stringAttribute.addRecord(2, "B");
		stringAttribute.addRecord(3, "A");

		assertArrayEquals(new int[] {3}, stringAttribute.getRecordsEqualTo("A").getArray());
		assertArrayEquals(new int[] {2}, stringAttribute.getRecordsEqualTo("B").getArray());
		assertArrayEquals(new int[] {1}, stringAttribute.getRecordsEqualTo("C").getArray());
		assertEquals(3, stringAttribute.getAllRecords().size());
	}

	@Test
	void shouldInsertNewRangeRecord() {
		rangeAttribute.addRecord(1, IntegerNumberRange.between(5, 10));
		rangeAttribute.addRecord(2, IntegerNumberRange.between(11, 20));
		rangeAttribute.addRecord(3, IntegerNumberRange.between(5, 15));

		assertArrayEquals(new int[] {1}, rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(5, 10)).getArray());
		assertArrayEquals(new int[] {2}, rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(11, 20)).getArray());
		assertArrayEquals(new int[] {3}, rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(5, 15)).getArray());
		assertEquals(3, rangeAttribute.getAllRecords().size());
	}

	@Test
	void shouldRemoveStringRecordId() {
		fillStringAttribute();
		stringAttribute.removeRecord(1, new String[] {"A", "C"});

		assertArrayEquals(new int[] {2}, stringAttribute.getRecordsEqualTo("A").getArray());
		assertArrayEquals(new int[] {1, 2}, stringAttribute.getRecordsEqualTo("B").getArray());
		assertArrayEquals(new int[] {3}, stringAttribute.getRecordsEqualTo("C").getArray());
		assertArrayEquals(new int[] {4}, stringAttribute.getRecordsEqualTo("D").getArray());
		assertEquals(4, stringAttribute.getAllRecords().size());
	}

	@Test
	void shouldRemoveStringRecordIdRemovingFirstBucket() {
		fillStringAttribute();
		stringAttribute.removeRecord(1, "A");
		stringAttribute.removeRecord(2, "A");

		assertArrayEquals(new int[] {1, 2}, stringAttribute.getRecordsEqualTo("B").getArray());
		assertArrayEquals(new int[] {1, 3}, stringAttribute.getRecordsEqualTo("C").getArray());
		assertArrayEquals(new int[] {4}, stringAttribute.getRecordsEqualTo("D").getArray());
		assertEquals(4, stringAttribute.getAllRecords().size());
	}

	@Test
	void shouldRemoveStringRecordIdRemovingLastBucket() {
		fillStringAttribute();
		stringAttribute.removeRecord(4, "D");

		assertArrayEquals(new int[] {1, 2}, stringAttribute.getRecordsEqualTo("A").getArray());
		assertArrayEquals(new int[] {1, 2}, stringAttribute.getRecordsEqualTo("B").getArray());
		assertArrayEquals(new int[] {1, 3}, stringAttribute.getRecordsEqualTo("C").getArray());
		assertEquals(3, stringAttribute.getAllRecords().size());
	}

	@Test
	void shouldRemoveStringRecordIdRemovingMiddleBuckets() {
		fillStringAttribute();
		stringAttribute.removeRecord(1, new String[] {"B", "C"});
		stringAttribute.removeRecord(2, "B");
		stringAttribute.removeRecord(3, "C");

		assertArrayEquals(new int[] {1, 2}, stringAttribute.getRecordsEqualTo("A").getArray());
		assertArrayEquals(new int[] {4}, stringAttribute.getRecordsEqualTo("D").getArray());
		assertEquals(3, stringAttribute.getAllRecords().size());
	}

	@Test
	void shouldRemoveRangeRecord() {
		fillRangeAttribute();
		rangeAttribute.removeRecord(1, IntegerNumberRange.between(5, 10));

		assertArrayEquals(new int[] {1}, rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(50, 90)).getArray());
		assertArrayEquals(new int[] {2}, rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(11, 20)).getArray());
		assertArrayEquals(new int[] {3}, rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(5, 15)).getArray());
		assertEquals(3, rangeAttribute.getAllRecords().size());
	}

	@Test
	void shouldRemoveRangeRecordRemovingBucket() {
		fillRangeAttribute();
		rangeAttribute.removeRecord(1, new IntegerNumberRange[] {IntegerNumberRange.between(5, 10), IntegerNumberRange.between(50, 90)});
		rangeAttribute.removeRecord(3, IntegerNumberRange.between(5, 15));

		assertArrayEquals(new int[] {2}, rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(11, 20)).getArray());
		assertEquals(1, rangeAttribute.getAllRecords().size());
	}

	@Test
	void shouldReturnAllRecords() {
		fillStringAttribute();
		assertArrayEquals(new int[] {1, 2, 3, 4}, stringAttribute.getAllRecords().getArray());
	}

	@Test
	void shouldReturnRecordsGreaterThan() {
		fillStringAttribute();
		assertArrayEquals(new int[] {1, 3, 4}, stringAttribute.getRecordsGreaterThan("B").getArray());
	}

	@Test
	void shouldReturnRecordsGreaterThanEq() {
		fillStringAttribute();
		assertArrayEquals(new int[] {1, 3, 4}, stringAttribute.getRecordsGreaterThanEq("C").getArray());
	}

	@Test
	void shouldReturnRecordsLesserThan() {
		fillStringAttribute();
		assertArrayEquals(new int[] {1, 2}, stringAttribute.getRecordsLesserThan("C").getArray());
	}

	@Test
	void shouldReturnRecordsLesserThanEq() {
		fillStringAttribute();
		assertArrayEquals(new int[] {1, 2}, stringAttribute.getRecordsLesserThanEq("B").getArray());
	}

	@Test
	void shouldReturnRecordsBetween() {
		fillStringAttribute();
		assertArrayEquals(new int[]{1, 3, 4}, stringAttribute.getRecordsBetween("C", "D").getArray());
	}

	@Test
	void shouldReturnRecordsValidIn() {
		fillRangeAttribute();
		assertArrayEquals(new int[]{1, 3}, rangeAttribute.getRecordsValidIn(8L).getArray());
	}

	@ParameterizedTest(name = "FilterIndex should survive generational randomized test applying modifications on it")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int initialCount = 100;
		final Map<IntegerNumberRange, Integer> mapToCompare = new HashMap<>();
		final Set<Integer> currentRecordSet = new HashSet<>();
		final Set<IntegerNumberRange> uniqueValues = new HashSet<>();

		runFor(
			input,
			100,
			new TestState(
				new StringBuilder(),
				new FilterIndex(NumberRange.class)
			),
			(random, testState) -> {
				final StringBuilder codeBuffer = testState.code();
				codeBuffer.append("final FilterIndex filterIndex = new FilterIndex(String.class);\n")
					.append(
						mapToCompare.entrySet()
							.stream()
							.map(it -> "filterIndex.addRecord(\"" + it.getValue() + "\"," + it.getKey() + ");")
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
										final int from = random.nextInt(initialCount * 2);
										final int to = random.nextInt(initialCount * 2);
										range = IntegerNumberRange.between(Math.min(from, to), Math.max(from, to));
									} while (uniqueValues.contains(range));

									int newRecId;
									do {
										newRecId = random.nextInt(initialCount * 2);
									} while (currentRecordSet.contains(newRecId));
									mapToCompare.put(range, newRecId);
									currentRecordSet.add(newRecId);
									uniqueValues.add(range);

									codeBuffer.append("filterIndex.addRecord(\"")
										.append(newRecId).append("\",").append(range).append(");\n");
									transactionalFilterIndex.addRecord(newRecId, range);
								} else {
									// remove existing item
									final Iterator<Entry<IntegerNumberRange, Integer>> it = mapToCompare.entrySet().iterator();
									Entry<IntegerNumberRange, Integer> valueToRemove = null;
									for (int j = 0; j < random.nextInt(length) + 1; j++) {
										valueToRemove = it.next();
									}
									it.remove();
									currentRecordSet.remove(valueToRemove.getValue());
									uniqueValues.remove(valueToRemove.getKey());

									codeBuffer.append("filterIndex.removeRecord(\"")
										.append(valueToRemove.getValue()).append("\",").append(valueToRemove.getKey())
										.append(");\n");
									transactionalFilterIndex.removeRecord(valueToRemove.getValue(), valueToRemove.getKey());
								}
							}
						} catch (Exception ex) {
							fail("\n" + codeBuffer, ex);
						}
					},
					(original, committed) -> {
						final int[] expected = currentRecordSet.stream().mapToInt(it -> it).sorted().toArray();
						assertArrayEquals(
							expected,
							committed.getAllRecords().getArray(),
							"\nExpected: " + Arrays.toString(expected) + "\n" +
								"Actual:  " + Arrays.toString(committed.getAllRecords().getArray()) + "\n\n" +
								codeBuffer
						);

						committedResult.set(
							new FilterIndex(
								committed.getHistogram(),
								committed.getRangeIndex()
							)
						);
					}
				);
				return new TestState(
					new StringBuilder(), committedResult.get()
				);
			}
		);
	}

	private void fillStringAttribute() {
		stringAttribute.addRecord(1, new String[]{"A", "B", "C"});
		stringAttribute.addRecord(2, new String[]{"A", "B"});
		stringAttribute.addRecord(3, "C");
		stringAttribute.addRecord(4, "D");
		assertArrayEquals(new int[]{1, 2}, stringAttribute.getRecordsEqualTo("A").getArray());
		assertArrayEquals(new int[]{1, 2}, stringAttribute.getRecordsEqualTo("B").getArray());
		assertArrayEquals(new int[]{1, 3}, stringAttribute.getRecordsEqualTo("C").getArray());
		assertArrayEquals(new int[]{4}, stringAttribute.getRecordsEqualTo("D").getArray());
		assertFalse(stringAttribute.isEmpty());
	}

	private void fillRangeAttribute() {
		rangeAttribute.addRecord(1, new IntegerNumberRange[] {IntegerNumberRange.between(5, 10), IntegerNumberRange.between(50, 90)});
		rangeAttribute.addRecord(2, IntegerNumberRange.between(11, 20));
		rangeAttribute.addRecord(3, IntegerNumberRange.between(5, 15));
		assertArrayEquals(new int[] {1}, rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(5, 10)).getArray());
		assertArrayEquals(new int[] {1}, rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(50, 90)).getArray());
		assertArrayEquals(new int[] {2}, rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(11, 20)).getArray());
		assertArrayEquals(new int[] {3}, rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(5, 15)).getArray());
		assertEquals(3, rangeAttribute.getAllRecords().size());
		assertFalse(rangeAttribute.isEmpty());
	}

	private record TestState(
		StringBuilder code,
		FilterIndex filterIndex
	) {}

}