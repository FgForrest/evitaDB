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

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.NumberRange;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.range.RangePoint;
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
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link FilterIndex} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class FilterIndexTest implements TimeBoundedTestSupport {
	private final FilterIndex stringAttribute = new FilterIndex(new AttributeKey("a"), String.class);
	private final FilterIndex rangeAttribute = new FilterIndex(new AttributeKey("b"), NumberRange.class);

	@Test
	void shouldInsertNewStringRecordId() {
		this.stringAttribute.addRecord(1, "A");
		this.stringAttribute.addRecord(2, new String[] {"A", "B"});
		assertArrayEquals(new int[] {1, 2}, this.stringAttribute.getRecordsEqualTo("A").getArray());
		assertArrayEquals(new int[] {2}, this.stringAttribute.getRecordsEqualTo("B").getArray());
		assertEquals(2, this.stringAttribute.getAllRecords().size());
	}

	@Test
	void shouldInsertNewStringRecordIdInTheMiddle() {
		this.stringAttribute.addRecord(1, "A");
		this.stringAttribute.addRecord(3, "C");
		assertArrayEquals(new int[] {1}, this.stringAttribute.getRecordsEqualTo("A").getArray());
		assertArrayEquals(new int[] {3}, this.stringAttribute.getRecordsEqualTo("C").getArray());
		assertEquals(2, this.stringAttribute.getAllRecords().size());
		this.stringAttribute.addRecord(2, "B");
		assertArrayEquals(new int[] {1}, this.stringAttribute.getRecordsEqualTo("A").getArray());
		assertArrayEquals(new int[] {2}, this.stringAttribute.getRecordsEqualTo("B").getArray());
		assertArrayEquals(new int[] {3}, this.stringAttribute.getRecordsEqualTo("C").getArray());
		assertEquals(3, this.stringAttribute.getAllRecords().size());
	}

	@Test
	void shouldInsertNewStringRecordIdInTheBeginning() {
		this.stringAttribute.addRecord(1, "C");
		this.stringAttribute.addRecord(2, "B");
		this.stringAttribute.addRecord(3, "A");

		assertArrayEquals(new int[] {3}, this.stringAttribute.getRecordsEqualTo("A").getArray());
		assertArrayEquals(new int[] {2}, this.stringAttribute.getRecordsEqualTo("B").getArray());
		assertArrayEquals(new int[] {1}, this.stringAttribute.getRecordsEqualTo("C").getArray());
		assertEquals(3, this.stringAttribute.getAllRecords().size());
	}

	@Test
	void shouldInsertNewRangeRecord() {
		this.rangeAttribute.addRecord(1, IntegerNumberRange.between(5, 10));
		this.rangeAttribute.addRecord(2, IntegerNumberRange.between(11, 20));
		this.rangeAttribute.addRecord(3, IntegerNumberRange.between(5, 15));

		assertArrayEquals(new int[] {1}, this.rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(5, 10)).getArray());
		assertArrayEquals(new int[] {2}, this.rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(11, 20)).getArray());
		assertArrayEquals(new int[] {3}, this.rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(5, 15)).getArray());
		assertEquals(3, this.rangeAttribute.getAllRecords().size());
	}

	@Test
	void shouldRemoveStringRecordId() {
		fillStringAttribute();
		this.stringAttribute.removeRecord(1, new String[] {"A", "C"});

		assertArrayEquals(new int[] {2}, this.stringAttribute.getRecordsEqualTo("A").getArray());
		assertArrayEquals(new int[] {1, 2}, this.stringAttribute.getRecordsEqualTo("B").getArray());
		assertArrayEquals(new int[] {3}, this.stringAttribute.getRecordsEqualTo("C").getArray());
		assertArrayEquals(new int[] {4}, this.stringAttribute.getRecordsEqualTo("D").getArray());
		assertEquals(4, this.stringAttribute.getAllRecords().size());
	}

	@Test
	void shouldRemoveStringRecordIdRemovingFirstBucket() {
		fillStringAttribute();
		this.stringAttribute.removeRecord(1, "A");
		this.stringAttribute.removeRecord(2, "A");

		assertArrayEquals(new int[] {1, 2}, this.stringAttribute.getRecordsEqualTo("B").getArray());
		assertArrayEquals(new int[] {1, 3}, this.stringAttribute.getRecordsEqualTo("C").getArray());
		assertArrayEquals(new int[] {4}, this.stringAttribute.getRecordsEqualTo("D").getArray());
		assertEquals(4, this.stringAttribute.getAllRecords().size());
	}

	@Test
	void shouldRemoveStringRecordIdRemovingLastBucket() {
		fillStringAttribute();
		this.stringAttribute.removeRecord(4, "D");

		assertArrayEquals(new int[] {1, 2}, this.stringAttribute.getRecordsEqualTo("A").getArray());
		assertArrayEquals(new int[] {1, 2}, this.stringAttribute.getRecordsEqualTo("B").getArray());
		assertArrayEquals(new int[] {1, 3}, this.stringAttribute.getRecordsEqualTo("C").getArray());
		assertEquals(3, this.stringAttribute.getAllRecords().size());
	}

	@Test
	void shouldRemoveStringRecordIdRemovingMiddleBuckets() {
		fillStringAttribute();
		this.stringAttribute.removeRecord(1, new String[] {"B", "C"});
		this.stringAttribute.removeRecord(2, "B");
		this.stringAttribute.removeRecord(3, "C");

		assertArrayEquals(new int[] {1, 2}, this.stringAttribute.getRecordsEqualTo("A").getArray());
		assertArrayEquals(new int[] {4}, this.stringAttribute.getRecordsEqualTo("D").getArray());
		assertEquals(3, this.stringAttribute.getAllRecords().size());
	}

	@Test
	void shouldRemoveRangeRecord() {
		fillRangeAttribute();
		this.rangeAttribute.removeRecord(1, IntegerNumberRange.between(5, 10));

		assertArrayEquals(new int[] {1}, this.rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(50, 90)).getArray());
		assertArrayEquals(new int[] {2}, this.rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(11, 20)).getArray());
		assertArrayEquals(new int[] {3}, this.rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(5, 15)).getArray());
		assertEquals(3, this.rangeAttribute.getAllRecords().size());
	}

	@Test
	void shouldRemoveRangeRecordRemovingBucket() {
		fillRangeAttribute();
		this.rangeAttribute.removeRecord(1, new IntegerNumberRange[] {IntegerNumberRange.between(5, 10), IntegerNumberRange.between(50, 90)});
		this.rangeAttribute.removeRecord(3, IntegerNumberRange.between(5, 15));

		assertArrayEquals(new int[] {2}, this.rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(11, 20)).getArray());
		assertEquals(1, this.rangeAttribute.getAllRecords().size());
	}

	@Test
	void shouldReturnAllRecords() {
		fillStringAttribute();
		assertArrayEquals(new int[] {1, 2, 3, 4}, this.stringAttribute.getAllRecords().getArray());
	}

	@Test
	void shouldReturnRecordsStartingWith() {
		// generate records to verify starts with function
		this.stringAttribute.addRecord(1, "Alfa");
		this.stringAttribute.addRecord(2, "AlfaBeta");
		this.stringAttribute.addRecord(3, "Alfeta");
		this.stringAttribute.addRecord(4, "Ab");
		this.stringAttribute.addRecord(5, "Beta");
		this.stringAttribute.addRecord(6, "Betaversion");
		this.stringAttribute.addRecord(7, "Bet");
		this.stringAttribute.addRecord(8, "Betamax");
		this.stringAttribute.addRecord(9, "Gamma");
		this.stringAttribute.addRecord(10, "GammaAlfa");
		this.stringAttribute.addRecord(11, "GammaBeta");

		assertArrayEquals(new int[] {1, 2}, this.stringAttribute.getRecordsWhoseValuesStartWith("Alfa").compute().getArray());
		assertArrayEquals(new int[] {4}, this.stringAttribute.getRecordsWhoseValuesStartWith("Ab").compute().getArray());
		assertArrayEquals(new int[] {5, 6, 7, 8}, this.stringAttribute.getRecordsWhoseValuesStartWith("Bet").compute().getArray());
		assertArrayEquals(new int[] {5, 6, 8}, this.stringAttribute.getRecordsWhoseValuesStartWith("Beta").compute().getArray());
		assertArrayEquals(new int[] {9, 10, 11}, this.stringAttribute.getRecordsWhoseValuesStartWith("Gamma").compute().getArray());
		assertArrayEquals(new int[] {11}, this.stringAttribute.getRecordsWhoseValuesStartWith("GammaBeta").compute().getArray());
	}

	@Test
	void shouldReturnRecordsGreaterThan() {
		fillStringAttribute();
		assertArrayEquals(new int[] {1, 3, 4}, this.stringAttribute.getRecordsGreaterThan("B").getArray());
	}

	@Test
	void shouldReturnRecordsGreaterThanEq() {
		fillStringAttribute();
		assertArrayEquals(new int[] {1, 3, 4}, this.stringAttribute.getRecordsGreaterThanEq("C").getArray());
	}

	@Test
	void shouldReturnRecordsLesserThan() {
		fillStringAttribute();
		assertArrayEquals(new int[] {1, 2}, this.stringAttribute.getRecordsLesserThan("C").getArray());
	}

	@Test
	void shouldReturnRecordsLesserThanEq() {
		fillStringAttribute();
		assertArrayEquals(new int[] {1, 2}, this.stringAttribute.getRecordsLesserThanEq("B").getArray());
	}

	@Test
	void shouldReturnRecordsLesserThanLocaleSpecific_Czech() {
		FilterIndex czechStringAttribute = new FilterIndex(new AttributeKey("a", new Locale("cs", "CZ")), String.class);
		czechStringAttribute.addRecord(1, "CH");
		czechStringAttribute.addRecord(2, "E");
		czechStringAttribute.addRecord(3, "K");
		czechStringAttribute.addRecord(4, "D");
		czechStringAttribute.addRecord(5, "C");
		czechStringAttribute.addRecord(6, "B");
		assertArrayEquals(new int[] {2, 4, 5, 6}, czechStringAttribute.getRecordsLesserThan("CH").getArray());
	}

	@Test
	void shouldReturnRecordsLesserThanLocaleSpecific_English() {
		FilterIndex czechStringAttribute = new FilterIndex(new AttributeKey("a", Locale.ENGLISH), String.class);
		czechStringAttribute.addRecord(1, "CH");
		czechStringAttribute.addRecord(2, "E");
		czechStringAttribute.addRecord(3, "K");
		czechStringAttribute.addRecord(4, "D");
		czechStringAttribute.addRecord(5, "C");
		czechStringAttribute.addRecord(6, "B");
		assertArrayEquals(new int[] {5, 6}, czechStringAttribute.getRecordsLesserThan("CH").getArray());
	}

	@Test
	void shouldReturnRecordsBetween() {
		fillStringAttribute();
		assertArrayEquals(new int[]{1, 3, 4}, this.stringAttribute.getRecordsBetween("C", "D").getArray());
	}

	@Test
	void shouldReturnRecordsValidIn() {
		fillRangeAttribute();
		assertArrayEquals(new int[]{1, 3}, this.rangeAttribute.getRecordsValidIn(8L).getArray());
	}

	@Test
	void shouldIndexDeltaRanges() {
		fillRangeAttribute();
		this.rangeAttribute.addRecordDelta(1, new IntegerNumberRange[] {IntegerNumberRange.between(2, 5), IntegerNumberRange.between(20, 30)});
		this.rangeAttribute.addRecordDelta(1, new IntegerNumberRange[] {IntegerNumberRange.between(90, 100)});
		this.rangeAttribute.addRecordDelta(2, new IntegerNumberRange[] {IntegerNumberRange.between(20, 30)});

		assertArrayEquals(new int[] {1, 2}, this.rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(20, 30)).getArray());
		final RangePoint<?>[] ranges = this.rangeAttribute.getRangeIndex().getRanges();
		assertEquals(
			"""
					TransactionalRangePoint{threshold=-9223372036854775808, starts=[], ends=[]}
					TransactionalRangePoint{threshold=2, starts=[1], ends=[]}
					TransactionalRangePoint{threshold=5, starts=[3], ends=[]}
					TransactionalRangePoint{threshold=10, starts=[], ends=[1]}
					TransactionalRangePoint{threshold=11, starts=[2], ends=[]}
					TransactionalRangePoint{threshold=15, starts=[], ends=[3]}
					TransactionalRangePoint{threshold=20, starts=[1], ends=[]}
					TransactionalRangePoint{threshold=30, starts=[], ends=[1, 2]}
					TransactionalRangePoint{threshold=50, starts=[1], ends=[]}
					TransactionalRangePoint{threshold=100, starts=[], ends=[1]}
					TransactionalRangePoint{threshold=9223372036854775807, starts=[], ends=[]}""",
			Arrays.stream(ranges).map(Object::toString).collect(Collectors.joining("\n"))
		);
	}

	@Test
	void shouldFailToRemoveNonExistingRange() {
		fillRangeAttribute();
		this.rangeAttribute.addRecordDelta(1, new IntegerNumberRange[] {IntegerNumberRange.between(2, 5), IntegerNumberRange.between(20, 30)});
		this.rangeAttribute.addRecordDelta(1, new IntegerNumberRange[] {IntegerNumberRange.between(90, 100)});
		this.rangeAttribute.addRecordDelta(2, new IntegerNumberRange[] {IntegerNumberRange.between(20, 30)});

		assertThrows(
			IllegalArgumentException.class,
			() -> this.rangeAttribute.removeRecordDelta(1, new IntegerNumberRange[] {IntegerNumberRange.between(4, 6)})
		);
	}

	@Test
	void shouldRemoveIndexedDeltaRanges() {
		fillRangeAttribute();
		this.rangeAttribute.addRecordDelta(1, new IntegerNumberRange[] {IntegerNumberRange.between(2, 5), IntegerNumberRange.between(20, 30)});
		this.rangeAttribute.addRecordDelta(1, new IntegerNumberRange[] {IntegerNumberRange.between(90, 100)});
		this.rangeAttribute.addRecordDelta(2, new IntegerNumberRange[] {IntegerNumberRange.between(20, 30)});

		this.rangeAttribute.removeRecordDelta(1, new IntegerNumberRange[] {IntegerNumberRange.between(5, 10)});
		this.rangeAttribute.removeRecordDelta(1, new IntegerNumberRange[] {IntegerNumberRange.between(20, 30)});
		this.rangeAttribute.removeRecordDelta(1, new IntegerNumberRange[] {IntegerNumberRange.between(90, 100)});

		assertArrayEquals(new int[] {2}, this.rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(20, 30)).getArray());
		final RangePoint<?>[] ranges = this.rangeAttribute.getRangeIndex().getRanges();
		assertEquals(
			"""
			TransactionalRangePoint{threshold=-9223372036854775808, starts=[], ends=[]}
			TransactionalRangePoint{threshold=2, starts=[1], ends=[]}
			TransactionalRangePoint{threshold=5, starts=[3], ends=[1]}
			TransactionalRangePoint{threshold=11, starts=[2], ends=[]}
			TransactionalRangePoint{threshold=15, starts=[], ends=[3]}
			TransactionalRangePoint{threshold=30, starts=[], ends=[2]}
			TransactionalRangePoint{threshold=50, starts=[1], ends=[]}
			TransactionalRangePoint{threshold=90, starts=[], ends=[1]}
			TransactionalRangePoint{threshold=9223372036854775807, starts=[], ends=[]}""",
			Arrays.stream(ranges).map(Object::toString).collect(Collectors.joining("\n"))
		);
	}

	@ParameterizedTest(name = "FilterIndex should survive generational randomized test applying modifications on it")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int initialCount = 100;
		final Map<IntegerNumberRange, Integer> rangeToRecord = new HashMap<>();
		final Map<Integer, Set<IntegerNumberRange>> recordRanges = new HashMap<>();

		runFor(
			input,
			100,
			new TestState(
				new StringBuilder(),
				new FilterIndex(new AttributeKey("c"), IntegerNumberRange.class)
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
											.append(newRecId).append(", new IntegerNumberRange[] { ").append("IntegerNumberRange.between(" + range.getPreciseFrom() + ", " + range.getPreciseTo() + ")").append(" });\n");
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
							@SuppressWarnings({"unchecked", "rawtypes", "SuspiciousArrayCast"})
							final IntegerNumberRange[] actual = (IntegerNumberRange[]) ((InvertedIndex)committed.getInvertedIndex())
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
								new AttributeKey("a"),
								committed.getInvertedIndex().getValueToRecordBitmap(),
								committed.getRangeIndex(),
								Integer.class
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
		this.stringAttribute.addRecord(1, new String[]{"A", "B", "C"});
		this.stringAttribute.addRecord(2, new String[]{"A", "B"});
		this.stringAttribute.addRecord(3, "C");
		this.stringAttribute.addRecord(4, "D");
		assertArrayEquals(new int[]{1, 2}, this.stringAttribute.getRecordsEqualTo("A").getArray());
		assertArrayEquals(new int[]{1, 2}, this.stringAttribute.getRecordsEqualTo("B").getArray());
		assertArrayEquals(new int[]{1, 3}, this.stringAttribute.getRecordsEqualTo("C").getArray());
		assertArrayEquals(new int[]{4}, this.stringAttribute.getRecordsEqualTo("D").getArray());
		assertFalse(this.stringAttribute.isEmpty());
	}

	private void fillRangeAttribute() {
		this.rangeAttribute.addRecord(1, new IntegerNumberRange[] {IntegerNumberRange.between(5, 10), IntegerNumberRange.between(50, 90)});
		this.rangeAttribute.addRecord(2, IntegerNumberRange.between(11, 20));
		this.rangeAttribute.addRecord(3, IntegerNumberRange.between(5, 15));
		assertArrayEquals(new int[] {1}, this.rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(5, 10)).getArray());
		assertArrayEquals(new int[] {1}, this.rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(50, 90)).getArray());
		assertArrayEquals(new int[] {2}, this.rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(11, 20)).getArray());
		assertArrayEquals(new int[] {3}, this.rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(5, 15)).getArray());
		assertEquals(3, this.rangeAttribute.getAllRecords().size());
		assertFalse(this.rangeAttribute.isEmpty());
	}

	private record TestState(
		StringBuilder code,
		FilterIndex filterIndex
	) {}

}
