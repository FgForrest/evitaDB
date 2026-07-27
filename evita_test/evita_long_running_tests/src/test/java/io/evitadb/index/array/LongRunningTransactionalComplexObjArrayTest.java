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

package io.evitadb.index.array;

import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import lombok.Data;
import org.apache.commons.lang3.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.ArrayUtils.computeInsertPositionOfObjInOrderedArray;
import static io.evitadb.utils.ArrayUtils.insertRecordIntoArrayOnIndex;
import static io.evitadb.utils.ArrayUtils.isEmpty;
import static io.evitadb.utils.ArrayUtils.removeRecordFromOrderedArray;
import static io.evitadb.utils.AssertionUtils.assertIteratorContains;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generational randomized test that verifies correctness of {@link TransactionalComplexObjArray}
 * under many random transactional operations.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@DisplayName("Transactional complex object array (generational proof)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class LongRunningTransactionalComplexObjArrayTest implements TimeBoundedTestSupport {

	@ParameterizedTest(name = "TransactionalComplexObjArray should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int initialCount = 20;
		final int subCount = 30;
		final DistinctValueHolder[] initialState = generateRandomInitialArray(
			new Random(input.randomSeed()), initialCount, subCount
		);

		runFor(
			input,
			1000,
			new TestState(
				new StringBuilder(),
				initialState
			),
			(random, testState) -> {
				final StringBuilder codeBuffer = testState.code();
				final TransactionalComplexObjArray<DistinctValueHolder> transactionalArray =
					new TransactionalComplexObjArray<>(
						testState.initialArray(),
						DistinctValueHolder::combineWith,
						DistinctValueHolder::subtract,
						DistinctValueHolder::isEmpty,
						DistinctValueHolder::equals
					);
				final AtomicReference<DistinctValueHolder[]> nextArrayToCompare =
					new AtomicReference<>(testState.initialArray());

				assertStateAfterCommit(
					transactionalArray,
					original -> {
						codeBuffer.append("\nSTART:\n")
							.append(
								Arrays.stream(nextArrayToCompare.get())
									.map(DistinctValueHolder::toString)
									.collect(Collectors.joining("\n")))
							.append("\n\n");

						final int operationsInTransaction = random.nextInt(10);
						for (int i = 0; i < operationsInTransaction; i++) {
							if (random.nextBoolean() || transactionalArray.getLength() < 10) {
								// upsert new item
								final String recKey = String.valueOf((char) (40 + random.nextInt(initialCount * 2)));
								final DistinctValueHolder upsertItem = new DistinctValueHolder(recKey, generateRandomArray(random, random.nextInt(subCount)));
								codeBuffer.append("+ ").append(upsertItem).append("\n");
								final int txPosition = transactionalArray.addReturningIndex(upsertItem);
								final DistinctValueHolder[] referenceArray = nextArrayToCompare.get();
								final InsertionPosition position = computeInsertPositionOfObjInOrderedArray(upsertItem, referenceArray);
								if (position.alreadyPresent()) {
									referenceArray[position.position()] = mergeArrays(upsertItem, referenceArray[position.position()]);
								} else if (!upsertItem.getValues().isEmpty()) {
									nextArrayToCompare.set(insertRecordIntoArrayOnIndex(upsertItem, referenceArray, position.position()));
								}
								if (!upsertItem.getValues().isEmpty()) {
									assertEquals(position.position(), txPosition, codeBuffer.toString());
								}
							} else {
								// remove existing item
								final int position = random.nextInt(transactionalArray.getLength());
								final DistinctValueHolder removedRecId = transactionalArray.get(position);
								final DistinctValueHolder removedItem = new DistinctValueHolder(removedRecId.getKey(), pickSomethingRandomlyFrom(random, removedRecId.getValues()));
								codeBuffer.append("- ").append(removedItem).append("\n");
								transactionalArray.remove(removedItem);
								final Integer[] restArray = subtractArrays(removedItem.getValues(), removedRecId.getValues());
								final DistinctValueHolder[] existingArray = nextArrayToCompare.get();
								if (isEmpty(restArray)) {
									nextArrayToCompare.set(removeRecordFromOrderedArray(removedRecId, existingArray));
								} else {
									existingArray[position] = new DistinctValueHolder(removedRecId.getKey(), restArray);
								}
							}
						}

						// after operations the transactional array must match expected array
						assertTransactionalObjArray(nextArrayToCompare.get(), transactionalArray);
					},
					(original, committed) -> {
						codeBuffer.append("\nEXPECTED:\n")
							.append(
								Arrays.stream(nextArrayToCompare.get())
									.map(DistinctValueHolder::toString)
									.collect(Collectors.joining("\n"))
							)
							.append("\n");
						codeBuffer.append("\nGOT:\n")
							.append(
								Arrays.stream(committed)
									.map(DistinctValueHolder::toString)
									.collect(Collectors.joining("\n"))
							)
							.append("\n");
						assertArrayEquals(nextArrayToCompare.get(), committed, codeBuffer.toString());
					}
				);

				return new TestState(
					new StringBuilder(), nextArrayToCompare.get()
				);
			}
		);
	}

	/**
	 * Asserts that the given {@link TransactionalComplexObjArray} of {@link DistinctValueHolder}
	 * matches the expected contents.
	 */
	private static void assertTransactionalObjArray(
		@Nonnull DistinctValueHolder[] expectedContents,
		@Nonnull TransactionalComplexObjArray<DistinctValueHolder> array
	) {
		if (isEmpty(expectedContents)) {
			assertTrue(array.isEmpty());
		} else {
			assertFalse(array.isEmpty());
		}
		assertArrayEquals(expectedContents, array.getArray());
		assertIteratorContains(array.iterator(), expectedContents);

		assertEquals(expectedContents.length, array.getLength());

		for (int i = 0; i < expectedContents.length; i++) {
			assertEquals(expectedContents[i], array.get(i));
			assertEquals(i, array.indexOf(expectedContents[i]));
		}
	}

	@Nonnull
	private Integer[] subtractArrays(
		@Nonnull TreeSet<Integer> subtractedArray,
		@Nonnull TreeSet<Integer> baseArray
	) {
		final TreeSet<Integer> baseArrayCopy = new TreeSet<>(baseArray);
		baseArrayCopy.removeAll(subtractedArray);
		return baseArrayCopy.toArray(new Integer[0]);
	}

	@Nonnull
	private Integer[] pickSomethingRandomlyFrom(
		@Nonnull Random rnd,
		@Nonnull TreeSet<Integer> values
	) {
		final TreeSet<Integer> newSet = new TreeSet<>(values);
		newSet.removeIf(it -> rnd.nextBoolean());
		return newSet.toArray(new Integer[0]);
	}

	@Nonnull
	private DistinctValueHolder mergeArrays(
		@Nonnull DistinctValueHolder upsertItem,
		@Nonnull DistinctValueHolder existingItem
	) {
		final Set<Integer> mergedValues = new TreeSet<>(existingItem.getValues());
		mergedValues.addAll(upsertItem.getValues());
		final Integer[] values = mergedValues.toArray(new Integer[0]);
		return new DistinctValueHolder(existingItem.getKey(), values);
	}

	@Nonnull
	private DistinctValueHolder[] generateRandomInitialArray(
		@Nonnull Random rnd,
		int count,
		int subCount
	) {
		final Set<String> uniqueSet = new HashSet<>();
		final DistinctValueHolder[] initialArray = new DistinctValueHolder[count];
		for (int i = 0; i < count; i++) {
			boolean added;
			do {
				final String recKey = String.valueOf((char) (40 + rnd.nextInt(count * 2)));
				added = uniqueSet.add(recKey);
				if (added) {
					final Integer[] values = generateRandomArray(rnd, subCount);
					if (ArrayUtils.isNotEmpty(values)) {
						initialArray[i] = new DistinctValueHolder(recKey, values);
					} else {
						added = false;
					}
				}
			} while (!added);
		}
		Arrays.sort(initialArray);
		return initialArray;
	}

	@Nonnull
	private Integer[] generateRandomArray(@Nonnull Random rnd, int count) {
		final Set<Integer> uniqueSet = new HashSet<>();
		final Integer[] initialArray = new Integer[count];
		for (int i = 0; i < count; i++) {
			boolean added;
			do {
				final int recId = rnd.nextInt(count * 2);
				added = uniqueSet.add(recId);
				if (added) {
					initialArray[i] = recId;
				}
			} while (!added);
		}
		Arrays.sort(initialArray);
		return initialArray;
	}

	/**
	 * Internal test state for generational proof testing.
	 */
	private record TestState(
		@Nonnull StringBuilder code,
		@Nonnull DistinctValueHolder[] initialArray
	) {
	}

	/**
	 * A {@link TransactionalObject} that holds a key and a set of distinct integer values.
	 * Supports combine (merge values), reduce (subtract values), and obsolete checking
	 * (empty set means obsolete). Used for testing combine/reduce patterns.
	 */
	@Data
	private static class DistinctValueHolder
		implements TransactionalObject<DistinctValueHolder>,
		VoidTransactionMemoryProducer<DistinctValueHolder>,
		Comparable<DistinctValueHolder> {

		private final String key;
		private final TreeSet<Integer> values = new TreeSet<>();

		DistinctValueHolder(@Nonnull String key, @Nonnull Integer... values) {
			this.key = key;
			Collections.addAll(this.values, values);
		}

		@Nonnull
		@Override
		public DistinctValueHolder createCopyWithMergedTransactionalMemory(
			@Nonnull TransactionalLayerMaintainer transactionalLayer
		) {
			return this;
		}

		@Override
		public void removeLayer(
			@Nonnull TransactionalLayerMaintainer transactionalLayer
		) {
			// no-op for test helper
		}

		@Override
		public int compareTo(@Nonnull DistinctValueHolder o) {
			return this.key.compareTo(o.key);
		}

		@Nonnull
		@Override
		public DistinctValueHolder makeClone() {
			return new DistinctValueHolder(
				this.key, this.values.toArray(new Integer[0])
			);
		}

		@Override
		public String toString() {
			return this.key + ":" + this.values.stream()
				.map(Object::toString)
				.collect(Collectors.joining(","));
		}

		void combineWith(@Nonnull DistinctValueHolder otherHolder) {
			Assert.isTrue(
				this.key.equals(otherHolder.getKey()),
				"Keys are expected to be equal!"
			);
			this.values.addAll(otherHolder.getValues());
		}

		boolean isEmpty() {
			return this.values.isEmpty();
		}

		void subtract(@Nonnull DistinctValueHolder otherHolder) {
			Assert.isTrue(
				this.key.equals(otherHolder.getKey()),
				"Keys are expected to be equal!"
			);
			this.values.removeAll(otherHolder.getValues());
		}
	}

}
