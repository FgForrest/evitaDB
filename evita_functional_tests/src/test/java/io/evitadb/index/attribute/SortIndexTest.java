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

import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static java.text.Normalizer.Form;
import static java.text.Normalizer.normalize;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * This test verifies contract of {@link SortIndex}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class SortIndexTest implements TimeBoundedTestSupport {

	private static final Locale CZECH_LOCALE = new Locale("cs");

	@Test
	void shouldCreateIndexWithDifferentCardinalities() {
		final SortIndex sortIndex = createIndexWithBaseCardinalities();
		assertNull(sortIndex.valueCardinalities.get("Z"));
		assertNull(sortIndex.valueCardinalities.get("A"));
		assertEquals(2, sortIndex.valueCardinalities.get("B"));
		assertEquals(3, sortIndex.valueCardinalities.get("C"));
		assertArrayEquals(new String[]{"A", "B", "C"}, sortIndex.sortedRecordsValues.getArray());
		assertArrayEquals(new int[]{6, 4, 5, 1, 2, 3}, sortIndex.sortedRecords.getArray());
	}

	@Test
	void shouldAlterIndexWithDifferentCardinalities() {
		final SortIndex sortIndex = createIndexWithBaseCardinalities();
		sortIndex.removeRecord("A", 6);
		sortIndex.removeRecord("B", 4);
		sortIndex.removeRecord("C", 1);
		assertNull(sortIndex.valueCardinalities.get("Z"));
		assertNull(sortIndex.valueCardinalities.get("A"));
		assertNull(sortIndex.valueCardinalities.get("B"));
		assertEquals(2, sortIndex.valueCardinalities.get("C"));
		assertArrayEquals(new String[]{"B", "C"}, sortIndex.sortedRecordsValues.getArray());
		assertArrayEquals(new int[]{5, 2, 3}, sortIndex.sortedRecords.getArray());
	}

	@Test
	void shouldIndexRecordsAndReturnInAscendingOrder() {
		final SortIndex sortIndex = new SortIndex(Integer.class, null);
		sortIndex.addRecord(7, 2);
		sortIndex.addRecord(3, 4);
		sortIndex.addRecord(4, 3);
		sortIndex.addRecord(9, 1);
		sortIndex.addRecord(1, 5);
		final SortedRecordsProvider ascendingOrderRecordsSupplier = sortIndex.getAscendingOrderRecordsSupplier();
		assertArrayEquals(
			new int[]{5, 4, 3, 2, 1},
			ascendingOrderRecordsSupplier.getSortedRecordIds()
		);
	}

	@Test
	void shouldIndexRecordsAndReturnInDescendingOrder() {
		final SortIndex sortIndex = new SortIndex(Integer.class, null);
		sortIndex.addRecord(7, 2);
		sortIndex.addRecord(3, 4);
		sortIndex.addRecord(4, 3);
		sortIndex.addRecord(9, 1);
		sortIndex.addRecord(1, 5);
		final SortedRecordsProvider ascendingOrderRecordsSupplier = sortIndex.getDescendingOrderRecordsSupplier();
		assertArrayEquals(
			new int[]{1, 2, 3, 4, 5},
			ascendingOrderRecordsSupplier.getSortedRecordIds()
		);
	}

	@Test
	void shouldPassGenerationalTest1() {
		final SortIndex sortIndex = new SortIndex(String.class, null);
		sortIndex.addRecord("W", 49);
		sortIndex.addRecord("Z", 150);
		sortIndex.addRecord("[", 175);
		sortIndex.addRecord("E", 26);
		sortIndex.addRecord("I", 141);
		sortIndex.addRecord("T", 131);
		sortIndex.addRecord("G", 186);
		sortIndex.addRecord("X", 139);
		sortIndex.addRecord("C", 177);
		sortIndex.addRecord("L", 126);

		assertArrayEquals(
			new int[]{177, 26, 186, 141, 126, 131, 49, 139, 150, 175},
			sortIndex.getAscendingOrderRecordsSupplier().getSortedRecordIds()
		);
	}

	@Test
	void shouldSortNationalCharactersCorrectly() {
		final SortIndex sortIndex = new SortIndex(String.class, CZECH_LOCALE);
		sortIndex.addRecord("A", 1);
		sortIndex.addRecord("Š", 2);
		sortIndex.addRecord("T", 3);
		sortIndex.addRecord("B", 4);
		sortIndex.addRecord("Ž", 5);
		sortIndex.addRecord("Ř", 6);
		sortIndex.addRecord("Ň", 7);

		assertArrayEquals(
			new String[]{
				normalize("A", Form.NFD), 
				normalize("B", Form.NFD), 
				normalize("Ň", Form.NFD), 
				normalize("Ř", Form.NFD), 
				normalize("Š", Form.NFD), 
				normalize("T", Form.NFD), 
				normalize("Ž", Form.NFD)
			}, 
			sortIndex.sortedRecordsValues.getArray()
		);
		assertArrayEquals(new int[]{1, 4, 7, 6, 2, 3, 5}, sortIndex.sortedRecords.getArray());
	}

	@ParameterizedTest(name = "SortIndex should survive generational randomized test applying modifications on it")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final Random rnd = new Random();
		final int initialCount = 100;
		final TreeSet<ValueRecord> setToCompare = new TreeSet<>();
		final Set<Integer> currentRecordSet = new HashSet<>();

		runFor(
			input,
			1_000,
			new TestState(new StringBuilder(), new SortIndex(String.class, null)),
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

									ops.append("sortIndex.addRecord(\"").append(newValue).append("\",").append(newRecId).append(");\n");
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

									ops.append("sortIndex.removeRecord(\"").append(valueToRemove.value()).append("\",").append(valueToRemove.recordId()).append(");\n");
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
								"Actual:  " + Arrays.toString(committed.getAscendingOrderRecordsSupplier().getSortedRecordIds()) + "\n\n" +
								ops
						);

						committedResult.set(
							new SortIndex(
								committed.getType(),
								committed.getLocale(),
								committed.sortedRecords.getArray(),
								committed.sortedRecordsValues.getArray(),
								new HashMap<>(committed.valueCardinalities)
							)
						);
					}
				);

				return new TestState(
					new StringBuilder(),
					committedResult.get()
				);
			}
		);
	}

	@Nonnull
	private SortIndex createIndexWithBaseCardinalities() {
		final SortIndex sortIndex = new SortIndex(String.class, Locale.ENGLISH);
		sortIndex.addRecord("B", 5);
		sortIndex.addRecord("A", 6);
		sortIndex.addRecord("C", 3);
		sortIndex.addRecord("C", 2);
		sortIndex.addRecord("B", 4);
		sortIndex.addRecord("C", 1);
		return sortIndex;
	}

	private record TestState(
		StringBuilder code,
		SortIndex sortIndex
	) {

	}

	private record ValueRecord(String value, int recordId) implements Comparable<ValueRecord> {
		@Override
		public int compareTo(ValueRecord o) {
			final int cmp1 = value.compareTo(o.value);
			return cmp1 == 0 ? Integer.compare(recordId, o.recordId) : cmp1;
		}

	}
}