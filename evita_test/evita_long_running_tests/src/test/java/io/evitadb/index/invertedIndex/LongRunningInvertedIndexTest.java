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
import io.evitadb.index.bitmap.Bitmap;
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
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Long-running generational randomized proof tests for {@link InvertedIndex}. Besides the forward commit proof it also
 * drives the transactional-discard atomic-rollback path against a value oracle (Ref: #569); the per-entity savepoint
 * rollback (Ref: #1252) is exercised by the sibling {@code LongRunningSavepointInvertedIndexTest}.
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

	@ParameterizedTest(name = "InvertedIndex rollback discards every in-transaction mutation and leaves the base intact")
	@Tag(SLOW)
	@Tag(TRANSACTION)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalRollbackProofTest(GenerationalTestInput input) {
		this.<Long>doExecuteRollback(100, input, Comparator.naturalOrder(), random -> (long) random.nextInt(200));
	}

	@ParameterizedTest(name = "InvertedIndex rollback discards every in-transaction localized mutation and leaves the base intact")
	@Tag(SLOW)
	@Tag(TRANSACTION)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalRollbackProofTestLocalized(GenerationalTestInput input) {
		this.<String>doExecuteRollback(
			100,
			input,
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
					original -> applyRandomBatch(random, original, initialCount, randomValueSupplier, mapToCompare, recordValues, currentRecordSet, uniqueValues, codeBuffer),
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

	/**
	 * Generational proof that a **rolled-back** transaction discards every in-transaction mutation and leaves the base
	 * index byte-for-byte intact — the atomic-rollback contract of Ref: #569. Each generation rebuilds a fresh index
	 * from the (random-walking) reference model, captures a value oracle of that base, applies a random batch of
	 * add/remove mutations inside a transaction that is then rolled back, and asserts the base index is unchanged and no
	 * committed value was published. Shares the batch generator with {@link #doExecute} so both drive the identical
	 * random-draw sequence.
	 */
	private <T extends Serializable> void doExecuteRollback(
		int initialCount,
		@Nonnull GenerationalTestInput input,
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

				// value oracle of the base state that the rollback must return to
				final InvertedIndexSnapshot beforeRollback = snapshot(histogram);

				assertStateAfterRollback(
					histogram,
					original -> applyRandomBatch(random, original, initialCount, randomValueSupplier, mapToCompare, recordValues, currentRecordSet, uniqueValues, codeBuffer),
					(original, committed) -> {
						assertNull(committed,
							"A rolled-back transaction must not publish a committed value!\n" + codeBuffer);
						assertEquals(beforeRollback, snapshot(original),
							"InvertedIndex changed after rollback — atomic rollback leaked!\n" + codeBuffer);
					}
				);

				// the reference model reflects the attempted (rolled-back) batch, so the next generation rebuilds a
				// different base index — a random walk that keeps the proof exploring fresh base indexes
				return new TestState(
					new StringBuilder(256)
				);
			}
		);
	}

	/**
	 * Applies a random batch of add/remove mutations to `histogram`, mirroring each mutation into the `mapToCompare` /
	 * `recordValues` / `currentRecordSet` / `uniqueValues` reference model so the two stay in lockstep. Shared by the
	 * commit and rollback proofs so both drive the identical random-draw sequence.
	 */
	private static <T extends Serializable> void applyRandomBatch(
		@Nonnull Random random,
		@Nonnull InvertedIndex histogram,
		int initialCount,
		@Nonnull Function<Random, T> randomValueSupplier,
		@Nonnull Map<T, List<Integer>> mapToCompare,
		@Nonnull Map<Integer, Set<T>> recordValues,
		@Nonnull Set<Integer> currentRecordSet,
		@Nonnull Set<T> uniqueValues,
		@Nonnull StringBuilder codeBuffer
	) {
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
	}

	/**
	 * Reads the full logical content of the index (transaction-aware) into a value-comparable snapshot — the ordered
	 * sequence of buckets, each as (value, sorted record ids) — so two snapshots taken before and after a rollback can
	 * be compared with `.equals` to prove exact restoration.
	 */
	@Nonnull
	static InvertedIndexSnapshot snapshot(@Nonnull InvertedIndex index) {
		final ValueToRecordBitmap[] buckets = index.getValueToRecordBitmap();
		final List<BucketSnapshot> result = new ArrayList<>(buckets.length);
		for (final ValueToRecordBitmap bucket : buckets) {
			result.add(new BucketSnapshot(bucket.getValue(), toList(bucket.getRecordIds())));
		}
		return new InvertedIndexSnapshot(result);
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
		StringBuilder code
	) {}

	/**
	 * Value-comparable snapshot of an {@link InvertedIndex}: the buckets in ascending value order. Record equality gives
	 * deep structural comparison.
	 */
	record InvertedIndexSnapshot(@Nonnull List<BucketSnapshot> buckets) {}

	/**
	 * One bucket of an {@link InvertedIndexSnapshot}: its value and the ascending record ids assigned to it.
	 */
	record BucketSnapshot(@Nonnull Serializable value, @Nonnull List<Integer> recordIds) {}

}
