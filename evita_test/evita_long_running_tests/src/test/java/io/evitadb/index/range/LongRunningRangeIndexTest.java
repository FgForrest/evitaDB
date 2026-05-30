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
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Generational randomized proof test for {@link RangeIndex}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@Tag(INDEXING)
@Tag(DATA_TYPE)
class LongRunningRangeIndexTest implements TimeBoundedTestSupport {

	@ParameterizedTest(name = "RangeIndex should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int optimalCount = 100;
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
					original -> {
						try {
							final int operationsInTransaction = random.nextInt(100);
							for (int i = 0; i < operationsInTransaction; i++) {
								final int length = currentRecordSet.size();
								if ((random.nextBoolean() || length < 10) && length < 50) {
									// insert new item
									IntegerNumberRange range;
									do {
										final int from = random.nextInt(optimalCount * 2);
										final int to = random.nextInt(optimalCount * 2);
										range = IntegerNumberRange.between(Math.min(from, to), Math.max(from, to));
									} while (uniqueValues.contains(range));

									int newRecId;
									do {
										newRecId = random.nextInt(optimalCount);
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

	private record TestState(
		StringBuilder code,
		RangeIndex rangeIndex
	) {}

}
