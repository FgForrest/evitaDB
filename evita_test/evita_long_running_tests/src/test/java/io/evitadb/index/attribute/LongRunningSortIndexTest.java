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

import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Generational randomized stress test for {@link SortIndex}. Verifies the contract under random
 * sequences of add/remove operations applied within transactional commit boundaries.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class LongRunningSortIndexTest implements TimeBoundedTestSupport {

	@ParameterizedTest(name = "SortIndex should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final Random rnd = new Random();
		final int initialCount = 100;
		final TreeSet<ValueRecord> setToCompare = new TreeSet<>();
		final Set<Integer> currentRecordSet = new HashSet<>();

		runFor(
			input,
			1_000,
			new TestState(
				new StringBuilder(256),
				new SortIndex(String.class, new AttributeIndexKey(null, "whatever", null))
			),
			(random, testState) -> {
				final StringBuilder ops = testState.code();
				ops.append("final SortIndex sortIndex = new SortIndex(String.class);\n")
					.append(
						setToCompare.stream()
							.map(it -> "sortIndex.addRecord(\"" + it.value() + "\"," + it.recordId() + ");")
							.collect(Collectors.joining("\n"))
					)
					.append("\nOps:\n");

				final SortIndex sortIndex = testState.sortIndex();
				final AtomicReference<SortIndex> committedResult = new AtomicReference<>();

				assertStateAfterCommit(
					sortIndex,
					original -> {
						try {
							final int operationsInTransaction = rnd.nextInt(100);
							for (int i = 0; i < operationsInTransaction; i++) {
								final int length = sortIndex.size();
								if ((rnd.nextBoolean() || length < 10) && length < 50) {
									// insert new item
									final String newValue = Character.toString(65 + rnd.nextInt(28));
									int newRecId;
									do {
										newRecId = rnd.nextInt(initialCount * 2);
									} while (currentRecordSet.contains(newRecId));
									setToCompare.add(new ValueRecord(newValue, newRecId));
									currentRecordSet.add(newRecId);

									ops.append("sortIndex.addRecord(\"")
										.append(newValue).append("\",")
										.append(newRecId).append(");\n");
									sortIndex.addRecord(newValue, newRecId);
								} else {
									// remove existing item
									final Iterator<ValueRecord> it = setToCompare.iterator();
									ValueRecord valueToRemove = null;
									for (int j = 0; j < rnd.nextInt(length) + 1; j++) {
										valueToRemove = it.next();
									}
									it.remove();
									currentRecordSet.remove(valueToRemove.recordId());

									ops.append("sortIndex.removeRecord(\"")
										.append(valueToRemove.value()).append("\",")
										.append(valueToRemove.recordId()).append(");\n");
									sortIndex.removeRecord(valueToRemove.value(), valueToRemove.recordId());
								}
							}
						} catch (Exception ex) {
							fail("\n" + ops, ex);
						}
					},
					(original, committed) -> {
						final int[] expected = setToCompare.stream().mapToInt(ValueRecord::recordId).toArray();
						assertArrayEquals(
							expected,
							committed.getAscendingOrderRecordsSupplier().getSortedRecordIds(),
							"\nExpected: " + Arrays.toString(expected) + "\n" +
								"Actual:  " + Arrays.toString(
								committed.getAscendingOrderRecordsSupplier().getSortedRecordIds()
							) + "\n\n" + ops
						);

						// rebuild the (array + sparse-cardinality-map) form from the consolidated value→cardinality tree;
						// the (array, map) constructor follows the legacy convention where cardinality 1 is implied, so
						// only entries greater than one go into the map
						final Serializable[] committedValues = committed.getSortedRecordValues();
						final Map<Serializable, Integer> committedCardinalities = new HashMap<>();
						for (final Serializable value : committedValues) {
							final int cardinality = committed.getValueCardinality(value);
							if (cardinality > 1) {
								committedCardinalities.put(value, cardinality);
							}
						}
						committedResult.set(
							new SortIndex(
								committed.comparatorBase,
								null,
								committed.getAttributeIndexKey(),
								committed.sortedRecords.getArray(),
								committedValues,
								committedCardinalities
							)
						);
					}
				);

				return new TestState(
					new StringBuilder(512),
					committedResult.get()
				);
			}
		);
	}

	private record TestState(
		StringBuilder code,
		SortIndex sortIndex
	) {

	}

	private record ValueRecord(String value, int recordId) implements Comparable<ValueRecord> {
		@Override
		public int compareTo(ValueRecord o) {
			final int cmp1 = this.value.compareTo(o.value);
			return cmp1 == 0 ? Integer.compare(this.recordId, o.recordId) : cmp1;
		}

	}
}
