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

import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.apache.commons.lang3.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.ArrayUtils.insertIntIntoArrayOnIndex;
import static io.evitadb.utils.ArrayUtils.removeIntFromArrayOnIndex;
import static io.evitadb.utils.ArrayUtils.removeRangeFromArray;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Generational randomized proof tests for {@link TransactionalUnorderedIntArray}, applying
 * random add/remove modifications within transactions and verifying the committed state matches
 * a reference array.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Transactional unordered int array (generational randomized proof)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class LongRunningTransactionalUnorderedIntArrayTest implements TimeBoundedTestSupport {

	@DisplayName("survives generational randomized test applying modifications")
	@ParameterizedTest(name = "TransactionalIntArray should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int initialCount = 100;
		final int[] initialState = generateRandomInitialArray(new Random(input.randomSeed()), initialCount);

		runFor(
			input,
			1_000,
			new TestState(
				new StringBuilder(256),
				initialState
			),
			(random, testState) -> {
				final StringBuilder codeBuffer = testState.code();
				codeBuffer.append("final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[] {")
					.append(
						Arrays.stream(testState.initialArray())
							.mapToObj(Integer::toString)
							.collect(Collectors.joining(", "))
					)
					.append("});");
				final TransactionalUnorderedIntArray transactionalArray = new TransactionalUnorderedIntArray(testState.initialArray());
				final AtomicReference<int[]> nextArrayToCompare = new AtomicReference<>(testState.initialArray());

				assertStateAfterCommit(
					transactionalArray,
					original -> {
						final int operationsInTransaction = random.nextInt(100);
						for (int i = 0; i < operationsInTransaction; i++) {
							final int length = transactionalArray.getLength();
							final int[] comparedArray = nextArrayToCompare.get();
							final int randomIndex = random.nextInt(comparedArray.length);
							final int recOnRandomIndex = comparedArray[randomIndex];
							if ((random.nextBoolean() || length < initialCount * 0.3) && length < initialCount * 1.6) {
								if (length < initialCount * 0.5) {
									// append multiple at the end
									int[] newRecId = new int[random.nextInt(2) + 1];
									Set<Integer> assignedSet = new HashSet<>();
									for (int j = 0; j < newRecId.length; j++) {
										do {
											newRecId[j] = random.nextInt(initialCount << 1);
										} while (transactionalArray.contains(newRecId[j]) || assignedSet.contains(newRecId[j]));
										assignedSet.add(newRecId[j]);
									}

									try {
										codeBuffer.append("\noriginal.appendAll(").append(Arrays.stream(newRecId).mapToObj(String::valueOf).collect(Collectors.joining(", "))).append(");");
										nextArrayToCompare.set(io.evitadb.utils.ArrayUtils.mergeArrays(comparedArray, newRecId));
										transactionalArray.appendAll(newRecId);
									} catch (IllegalArgumentException ex) {
										assertTransactionalArrayIs(
											nextArrayToCompare.get(), transactionalArray,
											"\n Cannot insert " + Arrays.toString(newRecId) + " due to: " + ex.getMessage()
										);
										throw ex;
									}
								} else {
									// insert new item
									int newRecId;
									do {
										newRecId = random.nextInt(initialCount << 1);
									} while (transactionalArray.contains(newRecId));

									try {
										if (random.nextBoolean()) {
											codeBuffer.append("\noriginal.add(").append(recOnRandomIndex).append(", ").append(newRecId).append(");");
											nextArrayToCompare.set(insertIntIntoArrayOnIndex(newRecId, comparedArray, randomIndex + 1));
											transactionalArray.add(recOnRandomIndex, newRecId);
										} else {
											codeBuffer.append("\noriginal.addOnIndex(").append(randomIndex).append(", ").append(newRecId).append(");");
											nextArrayToCompare.set(insertIntIntoArrayOnIndex(newRecId, comparedArray, randomIndex));
											transactionalArray.addOnIndex(randomIndex, newRecId);
										}
									} catch (IllegalArgumentException ex) {
										assertTransactionalArrayIs(
											nextArrayToCompare.get(), transactionalArray,
											"\n Cannot insert " + newRecId + " due to: " + ex.getMessage()
										);
										throw ex;
									}
								}
							} else {
								if (length > initialCount * 1.4) {
									final int removedLength = random.nextInt(8) + 1;
									// remove range
									final int endIndex = Math.min(randomIndex + removedLength, length);
									codeBuffer.append("\noriginal.removeRange(").append(randomIndex).append(", ").append(endIndex).append(");");
									transactionalArray.removeRange(randomIndex, endIndex);
									nextArrayToCompare.set(removeRangeFromArray(comparedArray, randomIndex, endIndex));
								} else {
									// remove existing item
									codeBuffer.append("\noriginal.remove(").append(recOnRandomIndex).append(");");
									transactionalArray.remove(recOnRandomIndex);
									nextArrayToCompare.set(removeIntFromArrayOnIndex(comparedArray, randomIndex));
								}
							}

							try {
								transactionalArray.getArray();
							} catch (RuntimeException ex) {
								fail(ex.getMessage() + "\n\n" + codeBuffer);
							}
						}
					},
					(original, committed) -> {
						assertTransactionalArrayIs(
							nextArrayToCompare.get(),
							new TransactionalUnorderedIntArray(committed), "\nRecipe:\n\n" + codeBuffer
						);
					}
				);

				return new TestState(
					new StringBuilder(256), nextArrayToCompare.get()
				);
			}
		);
	}

	/**
	 * Generates a random initial array of unique integers.
	 */
	private static int[] generateRandomInitialArray(Random rnd, int count) {
		final Set<Integer> uniqueSet = new HashSet<>(256);
		final int[] initialArray = new int[count];
		for (int i = 0; i < count; i++) {
			boolean added;
			do {
				final int recId = rnd.nextInt(count << 1);
				added = uniqueSet.add(recId);
				if (added) {
					initialArray[i] = recId;
				}
			} while (!added);
		}
		return initialArray;
	}

	/**
	 * Asserts that the given {@link TransactionalUnorderedIntArray} matches the expected int array
	 * with an optional additional message appended to assertion failures.
	 */
	private static void assertTransactionalArrayIs(int[] expectedResult, TransactionalUnorderedIntArray array, String additionalMessage) {
		if (ArrayUtils.isEmpty(expectedResult)) {
			assertTrue(array.isEmpty());
			assertThrows(ArrayIndexOutOfBoundsException.class, array::getLastRecordId);
		} else {
			assertFalse(array.isEmpty());
			assertEquals(expectedResult[expectedResult.length - 1], array.getLastRecordId());
		}

		assertArrayEquals(
			expectedResult,
			array.getArray(),
			"\nExpected: " + Arrays.stream(expectedResult).mapToObj(Integer::toString).collect(Collectors.joining(", ")) + "\n" +
				"Actual:   " + Arrays.stream(array.getArray()).mapToObj(Integer::toString).collect(Collectors.joining(", ")) + "\n" +
				ofNullable(additionalMessage).orElse("")
		);
		assertArrayEquals(
			expectedResult,
			CompositeIntArray.toArray(array.iterator()),
			"\nExpected: " + Arrays.stream(expectedResult).mapToObj(Integer::toString).collect(Collectors.joining(", ")) + "\n" +
				"Actual:   " + Arrays.stream(CompositeIntArray.toArray(array.iterator())).mapToObj(Integer::toString).collect(Collectors.joining(", ")) + "\n" +
				ofNullable(additionalMessage).orElse("")
		);

		assertEquals(expectedResult.length, array.getLength());
		for (int i = 0; i < expectedResult.length; i++) {
			final int recordId = expectedResult[i];
			assertTrue(array.contains(recordId), "Array doesn't contain " + recordId);
			assertEquals(i, array.indexOf(recordId), "Index of " + recordId + " is not " + i + ", but " + array.indexOf(recordId));
		}
	}

	/**
	 * Holds the state carried between generational test iterations.
	 */
	private record TestState(
		StringBuilder code,
		int[] initialArray
	) {
	}

}
