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

package io.evitadb.index.invertedIndex;

import io.evitadb.comparator.LocalizedStringComparator;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyReport;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Long-running generational randomized proof tests for {@link InvertedIndex}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@SuppressWarnings("SameParameterValue")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class LongRunningInvertedIndexTest implements TimeBoundedTestSupport {
	public static final String[] NATIONAL_SPECIFIC_WORDS = {
		"chléb",
		"hlína",
		"chata",
		"chalupa",
		"chatka",
		"chechtat",
		"chirurg",
		"chodba",
		"chodník",
		"choroba",
		"chrám",
		"chránit",
		"chroust",
		"chřest",
		"chuť",
		"chůze",
		"hajný",
		"hajzl",
		"haló",
		"halucinace",
		"hanba",
		"hanka",
		"harfa",
		"harpunář",
		"hasák",
		"hasič",
		"hasička",
		"hasičský",
		"hasit",
		"haslo",
		"házat",
		"hejtman",
		"hejtmanka",
		"herna",
		"hezký",
		"hlad",
		"hledat",
		"hlídka",
		"hloupý",
		"hnůj",
		"hodina",
		"hodiny",
		"hojnost",
		"holka",
		"holub",
		"horko",
		"horší",
		"hostina"
	};

	@ParameterizedTest(name = "InvertedIndex should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		doExecute(100, input, Long.class, Comparator.naturalOrder(), random -> (long) random.nextInt(200));
	}

	@ParameterizedTest(name = "InvertedIndex should survive generational randomized test applying localized modifications on it")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTestLocalized(GenerationalTestInput input) {
		doExecute(
			100,
			input,
			String.class,
			new LocalizedStringComparator(new Locale("cs")),
			random -> NATIONAL_SPECIFIC_WORDS[random.nextInt(NATIONAL_SPECIFIC_WORDS.length)]
		);
	}

	private <T extends Serializable> void doExecute(
		int initialCount,
		@Nonnull GenerationalTestInput input,
		@Nonnull Class<T> type,
		@Nonnull Comparator<T> comparator,
		@Nonnull Function<Random, T> randomValueSupplier
	) {
		final Map<T, List<Integer>> mapToCompare = new HashMap<>();
		final Map<Integer, Set<T>> recordValues = new HashMap<>();
		final Set<Integer> currentRecordSet = new HashSet<>();
		final Set<T> uniqueValues = new TreeSet<>(comparator);

		runFor(
			input,
			1_00,
			new TestState(
				new StringBuilder(256)
			),
			(random, testState) -> {
				final StringBuilder codeBuffer = testState.code();
				codeBuffer.append("final InvertedIndex<Long> histogram = new InvertedIndex<>();\n")
					.append(
						mapToCompare.entrySet()
							.stream()
							.map(it -> "histogram.addRecord(" + it.getKey() + "L," + it.getValue().stream().map(Object::toString).collect(Collectors.joining(", ")) + ");")
							.collect(Collectors.joining("\n"))
					)
					.append("\nOps:\n");

				final InvertedIndex histogram = new InvertedIndex(FilterIndex.NO_NORMALIZATION, comparator);
				for (Entry<T, List<Integer>> entry : mapToCompare.entrySet()) {
					histogram.addRecord(
						entry.getKey(),
						entry.getValue().stream().mapToInt(it -> it).toArray()
					);
				}

				assertStateAfterCommit(
					histogram,
					original -> {
						try {
							final int operationsInTransaction = random.nextInt(100);
							for (int i = 0; i < operationsInTransaction; i++) {
								final int length = histogram.getRecords().getRecordIds().size();
								if ((random.nextBoolean() || length < 10) && length < 50) {
									// insert new item
									final T newValue = randomValueSupplier.apply(random);

									int newRecId;
									do {
										newRecId = random.nextInt(initialCount);
									} while (currentRecordSet.contains(newRecId));

									mapToCompare.computeIfAbsent(newValue, aLong -> new ArrayList<>()).add(newRecId);
									recordValues.computeIfAbsent(newRecId, integer -> new HashSet<>()).add(newValue);
									currentRecordSet.add(newRecId);
									uniqueValues.add(newValue);

									codeBuffer.append("histogram.addRecord(").append(newValue).append("L,").append(newRecId).append(");\n");
									histogram.addRecord(newValue, newRecId);
								} else {
									// remove existing item
									final Iterator<Entry<T, List<Integer>>> it = mapToCompare.entrySet().iterator();
									T valueToRemove = null;
									Integer recordToRemove = null;
									final int removePosition = random.nextInt(length);
									int cnt = 0;
									finder:
									for (int j = 0; j < mapToCompare.size() + 1; j++) {
										final Entry<T, List<Integer>> entry = it.next();
										final Iterator<Integer> valIt = entry.getValue().iterator();
										while (valIt.hasNext()) {
											final Integer recordId = valIt.next();
											if (removePosition == cnt++) {
												valueToRemove = entry.getKey();
												recordToRemove = recordId;
												valIt.remove();
												break finder;
											}
										}
									}
									currentRecordSet.remove(recordToRemove);

									final Set<T> theRecordValues = recordValues.get(recordToRemove);
									theRecordValues.remove(valueToRemove);
									if (theRecordValues.isEmpty()) {
										recordValues.remove(recordToRemove);
									}

									final boolean valueBecameEmpty = mapToCompare.get(valueToRemove).isEmpty();
									if (valueBecameEmpty) {
										uniqueValues.remove(valueToRemove);
										mapToCompare.remove(valueToRemove);
									}

									codeBuffer.append("histogram.removeRecord(").append(valueToRemove).append("L,").append(recordToRemove).append(");\n");
									histogram.removeRecord(Objects.requireNonNull(valueToRemove), recordToRemove);

									// value-based verification: the removed record must no longer be assigned to the value,
									// and the bucket must be gone entirely once its last record was removed
									assertFalse(histogram.getRecordsEqualTo(valueToRemove).contains(recordToRemove));
									if (valueBecameEmpty) {
										assertFalse(histogram.contains(valueToRemove));
									}
								}
							}
						} catch (Exception ex) {
							fail("\n" + codeBuffer, ex);
						}
					},
					(original, committed) -> {
						final int[] expected = currentRecordSet.stream().mapToInt(it -> it).sorted().toArray();
						for (Entry<Integer, Set<T>> entry : recordValues.entrySet()) {
							final Set<T> values = entry.getValue();
							final T[] actual = committed.getValuesForRecord(entry.getKey(), type);
							assertArrayEquals(
								values.stream().sorted(comparator).toArray(),
								Arrays.stream(actual).sorted(comparator).toArray(),
								"\nExpected: " + Arrays.toString(values.toArray()) + "\n" +
									"Actual:   " + Arrays.toString(actual) + "\n\n" +
									codeBuffer
							);
						}
						assertArrayEquals(
							expected,
							committed.getSortedRecords().getRecordIds().getArray(),
							"\nExpected: " + Arrays.toString(expected) + "\n" +
								"Actual:   " + Arrays.toString(committed.getSortedRecords().getRecordIds().getArray()) + "\n\n" +
								codeBuffer
						);
						final ConsistencyReport consistencyReport = committed.getConsistencyReport();
						assertEquals(
							ConsistencyState.CONSISTENT, consistencyReport.state(),
							consistencyReport::report
						);
					}
				);

				return new TestState(
					new StringBuilder(256)
				);
			}
		);
	}

	private record TestState(
		StringBuilder code
	) {}

}
