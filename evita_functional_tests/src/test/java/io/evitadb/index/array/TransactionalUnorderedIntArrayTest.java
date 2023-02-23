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

package io.evitadb.index.array;

import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.apache.commons.lang3.ArrayUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.ArrayUtils.insertIntIntoArrayOnIndex;
import static io.evitadb.utils.ArrayUtils.removeIntFromArrayOnIndex;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link TransactionalUnorderedIntArray}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class TransactionalUnorderedIntArrayTest implements TimeBoundedTestSupport {

	@Test
	void shouldCorrectlyAddItemsOnFirstLastAndMiddlePositionsAndRollback() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[] {7, 3, 5});

		assertStateAfterRollback(
			array,
			original -> {
				original.add(3, 6);
				original.add(Integer.MIN_VALUE, 9);
				original.add(5, 8);
				assertTransactionalArrayIs(new int[]{9, 7, 3, 6, 5, 8}, array);
			},
			(original, committed) -> {
				assertNull(committed);
				assertTransactionalArrayIs(new int[]{7, 3, 5}, original);
			}
		);
	}

	@Test
	void shouldCorrectlyAddItemsOnFirstLastAndMiddleIndexesAndRollback() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 3, 5});

		assertStateAfterRollback(
			array,
			original -> {
				original.addOnIndex(2, 6);
				original.addOnIndex(0, 9);
				original.addOnIndex(5, 8);
				assertTransactionalArrayIs(new int[]{9, 7, 3, 6, 5, 8}, array);
			},
			(original, committed) -> {
				assertNull(committed);
				assertTransactionalArrayIs(new int[]{7, 3, 5}, original);
			}
		);
	}

	@Test
	void shouldCorrectlyBuildUpAddedItemsAndRollback() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 3, 5});

		assertStateAfterRollback(
			array,
			original -> {
				original.add(3, 6);
				original.add(Integer.MIN_VALUE, 9);
				original.add(6, 8);
				original.add(6, 11);
				original.add(8, 12);
				original.add(9, 10);
				assertTransactionalArrayIs(new int[]{9, 10, 7, 3, 6, 11, 8, 12, 5}, array);
			},
			(original, committed) -> {
				assertNull(committed);
				assertTransactionalArrayIs(new int[]{7, 3, 5}, original);
			}
		);
	}

	@Test
	void shouldCorrectlyBuildUpAddedItemsOnIndexesAndRollback() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 3, 5});

		assertStateAfterRollback(
			array,
			original -> {
				original.addOnIndex(2, 6);
				original.addOnIndex(0, 9);
				original.addOnIndex(4, 8);
				original.addOnIndex(4, 11);
				original.addOnIndex(6, 12);
				original.addOnIndex(1, 10);
				assertTransactionalArrayIs(new int[]{9, 10, 7, 3, 6, 11, 8, 12, 5}, array);
			},
			(original, committed) -> {
				assertNull(committed);
				assertTransactionalArrayIs(new int[]{7, 3, 5}, original);
			}
		);
	}

	@Test
	void shouldCorrectlyRemoveItemsOnFirstPositionAndRollback() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 3, 5, 2});

		assertStateAfterRollback(
			array,
			original -> {
				original.remove(7);
				original.remove(3);
				original.remove(2);
				assertTransactionalArrayIs(new int[] {5}, original);
			},
			(original, committed) -> {
				assertNull(committed);
				assertTransactionalArrayIs(new int[] {7, 3, 5, 2}, original);
			}
		);

	}

	@Test
	void shouldCorrectlyAddItemsOnFirstLastAndMiddlePositionsAndCommit() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[] {7, 3, 5});

		assertStateAfterCommit(
			array,
			original -> {
				original.add(3, 6);
				original.add(Integer.MIN_VALUE, 9);
				original.add(5, 8);
				assertTransactionalArrayIs(new int[]{9, 7, 3, 6, 5, 8}, array);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[]{7, 3, 5}, array);
				assertArrayEquals(new int[]{9, 7, 3, 6, 5, 8}, committed);
			}
		);
	}

	@Test
	void shouldCorrectlyAddItemsOnFirstLastAndMiddleIndexesAndCommit() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 3, 5});

		assertStateAfterCommit(
			array,
			original -> {
				original.addOnIndex(2, 6);
				original.addOnIndex(0, 9);
				original.addOnIndex(5, 8);
				assertTransactionalArrayIs(new int[]{9, 7, 3, 6, 5, 8}, array);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[]{7, 3, 5}, array);
				assertArrayEquals(new int[]{9, 7, 3, 6, 5, 8}, committed);
			}
		);
	}

	@Test
	void shouldCorrectlyRemoveItemsFromFirstLastAndMiddlePositionsAndCommit() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{6, 8, 2, 4, 5});

		assertStateAfterCommit(
			array,
			original -> {
				original.removeAll(4, 5, 6);
				assertTransactionalArrayIs(new int[]{8, 2}, array);
				assertFalse(array.contains(11));
				assertFalse(array.contains(1));
				assertFalse(array.contains(5));
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[] {6, 8, 2, 4, 5}, original);
				assertArrayEquals(new int[] {8, 2}, committed);
			}
		);
	}

	@Test
	void shouldCorrectlyRemoveMultipleItemsInARowAndCommit() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[] {7, 8, 2, 6, 5, 4});

		assertStateAfterCommit(
			array,
			original -> {
				original.removeAll(6, 5, 2);

				assertTransactionalArrayIs(new int[] {7, 8, 4}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[] {7, 8, 2, 6, 5, 4}, original);
				assertArrayEquals(new int[] {7, 8, 4}, committed);
			}
		);
	}

	@Test
	void shouldCorrectlyRemoveMultipleItemsInARowTillTheEndAndCommit() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[] {7, 2, 4, 9, 8, 3});

		assertStateAfterCommit(
			array,
			original -> {
				original.removeAll(8, 9, 3, 4);

				assertTransactionalArrayIs(new int[] {7, 2}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[] {7, 2, 4, 9, 8, 3}, original);
				assertArrayEquals(new int[] {7, 2}, committed);
			}
		);

	}

	@Test
	void shouldCorrectlyRemoveMultipleItemsInARowFromTheBeginningAndCommit() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[] {7, 2, 4, 9, 8, 3});

		assertStateAfterCommit(
			array,
			original -> {
				original.removeAll(9, 7, 2, 4);

				assertTransactionalArrayIs(new int[] {8, 3}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[] {7, 2, 4, 9, 8, 3}, original);
				assertArrayEquals(new int[] {8, 3}, committed);
			}
		);
	}

	@Test
	void shouldAddNothingAndCommit() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[] {7, 8, 1});

		assertStateAfterCommit(
			array,
			original -> {
				assertTransactionalArrayIs(new int[] {7, 8, 1}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[] {7, 8, 1}, array);
				assertArrayEquals(new int[] {7, 8, 1}, committed);
			}
		);
	}

	@Test
	void shouldDoReversibleRemoveAndAddActionsAndCommit() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[] {7, 8, 1});

		assertStateAfterCommit(
			array,
			original -> {
				original.remove(8);
				original.add(7, 8);
				original.remove(7);
				original.add(Integer.MIN_VALUE, 7);
				original.remove(1);
				original.add(8, 1);
				assertTransactionalArrayIs(new int[] {7, 8, 1}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[] {7, 8, 1}, array);
				assertArrayEquals(new int[] {7, 8, 1}, committed);
			}
		);
	}

	@Test
	void shouldDoReversibleAddAndRemoveActionsAndCommit() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[] {7, 8, 1});

		assertStateAfterCommit(
			array,
			original -> {
				original.add(7, 9);
				original.remove(9);
				original.add(Integer.MIN_VALUE, 6);
				original.remove(6);
				original.add(1, 6);
				original.remove(6);
				assertTransactionalArrayIs(new int[] {7, 8, 1}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[] {7, 8, 1}, array);
				assertArrayEquals(new int[] {7, 8, 1}, committed);
			}
		);
	}

	@Test
	void shouldAddAndRemoveEverythingAndCommit() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[0]);

		assertStateAfterCommit(
			array,
			original -> {
				original.add(Integer.MIN_VALUE, 1);
				original.add(1, 5);
				original.remove(1);
				original.remove(5);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[0], array);
				assertArrayEquals(new int[0], committed);
			}
		);
	}

	@Test
	void shouldCorrectlyAddMultipleItemsOnSamePositionsAndCommit() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[] {2, 7, 3});

		assertStateAfterCommit(
			array,
			original -> {
				original.addAll(Integer.MIN_VALUE, 0, 1);
				original.addAll(2, 4, 5, 6);
				assertTransactionalArrayIs(new int[]{0, 1, 2, 4, 5, 6, 7, 3}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[]{2, 7, 3}, original);
				assertArrayEquals(new int[]{0, 1, 2, 4, 5, 6, 7, 3}, committed);
			}
		);
	}

	@Test
	void shouldCorrectlyAddAndRemoveOnNonOverlappingPositionsAndCommit() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[] {5, 1, 6, 10, 2, 11});

		assertStateAfterCommit(
			array,
			original -> {
				original.add(5, 4);
				original.add(6, 3);
				original.remove(10);
				original.remove(6);
				original.add(Integer.MIN_VALUE, 15);

				assertTransactionalArrayIs(new int[] {15, 5, 4, 1, 3, 2, 11}, original);
				assertFalse(array.contains(10));
				assertFalse(array.contains(6));
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[] {5, 1, 6, 10, 2, 11}, original);
				assertArrayEquals(new int[] {15, 5, 4, 1, 3, 2, 11}, committed);
			}
		);

	}

	@Test
	void shouldCorrectlyAddAndRemoveOnOverlappingBoundaryPositionsAndCommit() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[] {2, 1, 10, 6, 11, 5});

		assertStateAfterCommit(
			array,
			original -> {
				original.remove(2);
				original.remove(5);
				original.add(11, 0);
				original.add(Integer.MIN_VALUE, 12);

				assertTransactionalArrayIs(new int[] {12, 1, 10, 6, 11, 0}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[] {2, 1, 10, 6, 11, 5}, original);
				assertArrayEquals(new int[] {12, 1, 10, 6, 11, 0}, committed);
			}
		);
	}

	@Test
	void shouldCorrectlyAddAndRemoveOnOverlappingMiddlePositionsAndCommit() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[] {1, 5, 8, 11});

		assertStateAfterCommit(
			array,
			original -> {
				original.remove(5);
				original.remove(8);
				original.add(1, 6);
				original.add(6, 7);
				original.add(7, 8);
				original.add(8, 9);
				original.add(9, 10);

				assertTransactionalArrayIs(new int[] {1, 6, 7, 8, 9, 10, 11}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[] {1, 5, 8, 11}, original);
				assertArrayEquals(new int[] {1, 6, 7, 8, 9, 10, 11}, committed);
			}
		);
	}

	@Test
	void shouldProperlyHandleChangesOnSinglePosition() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[] {1, 2});

		assertStateAfterCommit(
			array,
			original -> {
				original.remove(1);
				original.remove(2);
				original.add(Integer.MIN_VALUE,2);
				original.add(2, 4);
				original.remove(2);
				original.add(4, 5);

				assertTransactionalArrayIs(new int[] {4, 5}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[] {1, 2}, original);
				assertArrayEquals(new int[] {4, 5}, committed);
			}
		);
	}

	@Test
	void shouldFailToAddRecordAfterNonExistingRecord() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[] {1, 2});

		assertStateAfterCommit(
			array,
			original -> {
				try {
					array.add(3, 4);
				} catch (IllegalArgumentException ex) {
					// this is expected - previous record doesn't exits
				}
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[] {1, 2}, original);
				assertArrayEquals(new int[] {1, 2}, committed);
			}
		);
	}

	@Test
	void shouldFailToAddRecordAfterRemovedRecord() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[] {1, 2});

		assertStateAfterCommit(
			array,
			original -> {
				array.remove(2);
				try {
					array.add(2, 4);
				} catch (IllegalArgumentException ex) {
					// this is expected - previous record doesn't exits
				}
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[] {1, 2}, original);
				assertArrayEquals(new int[] {1}, committed);
			}
		);
	}

	@Test
	void shouldPassGenerationalTest1() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[] {5, 4, 1, 19, 8, 17});

		assertStateAfterCommit(
			array,
			original -> {
				try {
					original.remove(5);
					original.add(4, 18);
					original.remove(8);
					original.remove(17);
					original.add(18, 14);
					original.add(14, 7);
					original.remove(7);
					original.add(19, 13);
					original.remove(1);
					original.add(19, 8);
					original.add(4, 7);
					original.add(13, 1);
					original.add(13, 17);
					original.remove(19);
				} catch (IllegalArgumentException ex) {
					// this is expected - previous record doesn't exits
				}
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[] {5, 4, 1, 19, 8, 17}, original);
				assertTransactionalArrayIs(new int[] {4, 7, 18, 14, 8, 13, 17, 1}, new TransactionalUnorderedIntArray(committed));
			}
		);
	}

	@Test
	void shouldPassGenerationalTest2() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[] {12, 19, 16, 5, 0, 11, 4, 13});

		assertStateAfterCommit(
			array,
			original -> {
				try {
					original.remove(19);
					original.add(5, 9);
					original.remove(9);
					original.add(11, 2);
					original.add(13, 14);
					original.add(0, 6);
					original.add(6, 18);
					original.remove(14);
					original.remove(11);
					original.remove(5);
					original.remove(4);
					original.remove(16);
					original.add(2, 8);
					original.add(2, 10);
					original.remove(10);
					original.add(12, 9);
					original.add(13, 1);
					original.add(13, 10);
					original.remove(18);
					original.add(0, 11);
				} catch (IllegalArgumentException ex) {
					// this is expected - previous record doesn't exits
				}
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[] {12, 19, 16, 5, 0, 11, 4, 13}, original);
				assertTransactionalArrayIs(new int[] {12, 9, 0, 11, 6, 2, 8, 13, 10, 1}, new TransactionalUnorderedIntArray(committed));
			}
		);
	}

	@Test
	void shouldPassGenerationalTest3() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[] {14, 16, 11, 18, 4, 10, 6, 3});

		assertStateAfterCommit(
			array,
			original -> {
				try {
					original.add(16, 12);
					original.add(3, 0);
					original.add(16, 7);
					original.remove(16);
					original.remove(3);
					original.add(6, 16);
					original.add(6, 5);
					original.remove(14);
					original.remove(12);
					original.add(5, 19);
					original.remove(5);
					original.add(7, 5);
					original.add(0, 9);
					original.add(6, 3);
				} catch (IllegalArgumentException ex) {
					// this is expected - previous record doesn't exits
				}
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[] {14, 16, 11, 18, 4, 10, 6, 3}, original);
				assertTransactionalArrayIs(new int[] {7, 5, 11, 18, 4, 10, 6, 3, 19, 16, 0, 9}, new TransactionalUnorderedIntArray(committed));
			}
		);
	}

	@Test
	void shouldPassGenerationalTest4() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[] {0, 6, 9, 2, 12, 11, 13, 7, 15, 10, 8, 5, 18, 1, 14});

		assertStateAfterCommit(
			array,
			original -> {
				try {
					original.remove(12);
					original.add(8, 19);
					original.remove(2);
					original.add(8, 3);
					original.remove(6);
					original.add(10, 16);
					original.remove(3);
					original.add(15, 3);
					original.remove(9);
					original.remove(1);
					original.remove(18);
					original.add(16, 9);
					original.remove(10);
					original.remove(8);
					original.add(15, 10);
				} catch (IllegalArgumentException ex) {
					// this is expected - previous record doesn't exits
				}
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[]{0, 6, 9, 2, 12, 11, 13, 7, 15, 10, 8, 5, 18, 1, 14}, original);
				assertTransactionalArrayIs(new int[]{0, 11, 13, 7, 15, 10, 3, 16, 9, 19, 5, 14}, new TransactionalUnorderedIntArray(committed));
			}
		);
	}

	@Test
	void shouldPassGenerationalTest5() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{6, 7, 5, 2, 4});

		assertStateAfterCommit(
			array,
			original -> {
				try {
					original.addOnIndex(4, 9);
					original.remove(5);
					original.addOnIndex(4, 0);
				} catch (IllegalArgumentException ex) {
					// this is expected - previous record doesn't exist
				}
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[]{6, 7, 5, 2, 4}, original);
				assertTransactionalArrayIs(new int[]{6, 7, 2, 9, 0, 4}, new TransactionalUnorderedIntArray(committed));
			}
		);
	}

	@Test
	void shouldPassGenerationalTest6() {
		final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{2, 3});

		assertStateAfterCommit(
			array,
			original -> {
				try {
					original.addOnIndex(1, 8);
					original.addOnIndex(1, 9);
					original.remove(2);
					original.addOnIndex(1, 0);
				} catch (IllegalArgumentException ex) {
					// this is expected - previous record doesn't exist
				}
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[]{2, 3}, original);
				assertTransactionalArrayIs(new int[]{9, 0, 8, 3}, new TransactionalUnorderedIntArray(committed));
			}
		);
	}

	@ParameterizedTest(name = "TransactionalIntArray should survive generational randomized test applying modifications on it")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int initialCount = 100;
		final int[] initialState = generateRandomInitialArray(new Random(input.randomSeed()), initialCount);

		runFor(
			input,
			1_000,
			new TestState(
				new StringBuilder(),
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
								// insert new item
								int newRecId;
								do {
									newRecId = random.nextInt(initialCount * 2);
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
							} else {
								// remove existing item
								codeBuffer.append("\noriginal.remove(").append(recOnRandomIndex).append(");");
								transactionalArray.remove(recOnRandomIndex);
								nextArrayToCompare.set(removeIntFromArrayOnIndex(comparedArray, randomIndex));
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
					new StringBuilder(), nextArrayToCompare.get()
				);
			}
		);
	}

	private int[] generateRandomInitialArray(Random rnd, int count) {
		final Set<Integer> uniqueSet = new HashSet<>();
		final int[] initialArray = new int[count];
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
		return initialArray;
	}

	private static void assertTransactionalArrayIs(int[] expectedResult, TransactionalUnorderedIntArray array) {
		assertTransactionalArrayIs(expectedResult, array, null);
	}

	private static void assertTransactionalArrayIs(int[] expectedResult, TransactionalUnorderedIntArray array, String additionalMessage) {
		if (ArrayUtils.isEmpty(expectedResult)) {
			assertTrue(array.isEmpty());
		} else {
			assertFalse(array.isEmpty());
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

		for (int recordId : expectedResult) {
			assertTrue(array.contains(recordId), "Array doesn't contain " + recordId);
		}
	}

	private record TestState(
		StringBuilder code,
		int[] initialArray
	) {
	}

}