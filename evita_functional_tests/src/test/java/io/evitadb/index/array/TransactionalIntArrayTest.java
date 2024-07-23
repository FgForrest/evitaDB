/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.HashSet;
import java.util.PrimitiveIterator.OfInt;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies transactional contract of {@link TransactionalIntArray}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
class TransactionalIntArrayTest implements TimeBoundedTestSupport {

	private static void assertTransactionalArrayIs(int[] expectedResult, TransactionalIntArray array) {
		if (ArrayUtils.isEmpty(expectedResult)) {
			assertTrue(array.isEmpty());
		} else {
			assertFalse(array.isEmpty());
		}

		for (int recordId : expectedResult) {
			assertTrue(array.contains(recordId), "Array should contain " + recordId + ", but does not!");
		}

		assertArrayEquals(expectedResult, array.getArray());
		assertEquals(expectedResult.length, array.getLength());

		final OfInt it = array.iterator();
		int index = -1;
		while (it.hasNext()) {
			final int nextInt = it.next();
			assertTrue(expectedResult.length > index + 1);
			assertEquals(expectedResult[++index], nextInt);
			assertEquals(index, array.indexOf(nextInt), "Examined int: " + nextInt);
		}
		assertEquals(
			expectedResult.length, index + 1,
			"There are more expected ints than int array produced by iterator!"
		);
	}

	@Test
	void shouldCorrectlyAddItemsOnFirstLastAndMiddlePositionsAndRollback() {
		final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 5, 10});

		assertStateAfterRollback(
			array,
			original -> {
				original.addAll(new int[]{11, 0, 6});
				assertTransactionalArrayIs(new int[]{0, 1, 5, 6, 10, 11}, array);
			},
			(original, committed) -> {
				assertNull(committed);
				assertTransactionalArrayIs(new int[]{1, 5, 10}, original);
			}
		);
	}

	@Test
	void shouldCorrectlyRemoveItemsOnFirstLastAndMiddlePositionsAndRollback() {
		final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 5, 10});

		assertStateAfterRollback(
			array,
			original -> {
				original.remove(5);
				assertTransactionalArrayIs(new int[]{1, 10}, original);
			},
			(original, committed) -> {
				assertNull(committed);
				assertTransactionalArrayIs(new int[]{1, 5, 10}, original);
			}
		);

	}

	@Test
	void shouldCorrectlyAddItemsOnFirstLastAndMiddlePositionsAndCommit() {
		final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 5, 10});

		assertStateAfterCommit(
			array,
			original -> {
				original.add(11);
				original.addAll(new int[]{11, 0, 6});

				assertTransactionalArrayIs(new int[]{0, 1, 5, 6, 10, 11}, array);
				assertFalse(array.contains(2));
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[]{1, 5, 10}, original);
				assertArrayEquals(new int[]{0, 1, 5, 6, 10, 11}, committed);
			}
		);
	}

	@Test
	void shouldCorrectlyRemoveItemsFromFirstLastAndMiddlePositionsAndCommit() {
		final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 2, 5, 6, 10, 11});

		assertStateAfterCommit(
			array,
			original -> {
				original.removeAll(new int[]{11, 1, 5});
				assertTransactionalArrayIs(new int[]{2, 6, 10}, array);
				assertFalse(array.contains(11));
				assertFalse(array.contains(1));
				assertFalse(array.contains(5));
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[]{1, 2, 5, 6, 10, 11}, original);
				assertArrayEquals(new int[]{2, 6, 10}, committed);
			}
		);
	}

	@Test
	void shouldCorrectlyRemoveMultipleItemsInARowAndCommit() {
		final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 2, 5, 6, 10, 11});

		assertStateAfterCommit(
			array,
			original -> {
				original.removeAll(new int[]{6, 5, 2});

				assertTransactionalArrayIs(new int[]{1, 10, 11}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[]{1, 2, 5, 6, 10, 11}, original);
				assertArrayEquals(new int[]{1, 10, 11}, committed);
			}
		);
	}

	@Test
	void shouldCorrectlyRemoveMultipleItemsInARowTillTheEndAndCommit() {
		final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 2, 5, 6, 10, 11});

		assertStateAfterCommit(
			array,
			original -> {
				original.remove(6);
				original.removeAll(new int[]{6, 5, 10, 11});

				assertTransactionalArrayIs(new int[]{1, 2}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[]{1, 2, 5, 6, 10, 11}, original);
				assertArrayEquals(new int[]{1, 2}, committed);
			}
		);

	}

	@Test
	void shouldCorrectlyRemoveMultipleItemsInARowFromTheBeginningAndCommit() {
		final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 2, 5, 6, 10, 11});

		assertStateAfterCommit(
			array,
			original -> {
				original.removeAll(new int[]{2, 6, 5, 1, 10});

				assertTransactionalArrayIs(new int[]{11}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[]{1, 2, 5, 6, 10, 11}, original);
				assertArrayEquals(new int[]{11}, committed);
			}
		);
	}

	@Test
	void shouldAddNothingAndCommit() {
		final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 5, 10});

		assertStateAfterCommit(
			array,
			original -> {
				assertTransactionalArrayIs(new int[]{1, 5, 10}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[]{1, 5, 10}, array);
				assertArrayEquals(new int[]{1, 5, 10}, committed);
			}
		);
	}

	@Test
	void shouldAddAndRemoveEverythingAndCommit() {
		final TransactionalIntArray array = new TransactionalIntArray(new int[0]);

		assertStateAfterCommit(
			array,
			original -> {
				original.add(1);
				original.add(5);
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
		final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 5, 10});

		assertStateAfterCommit(
			array,
			original -> {
				original.addAll(new int[]{11, 6, 0, 3, 7, 12, 2, 8});
				original.add(3);

				assertTransactionalArrayIs(new int[]{0, 1, 2, 3, 5, 6, 7, 8, 10, 11, 12}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[]{1, 5, 10}, original);
				assertArrayEquals(new int[]{0, 1, 2, 3, 5, 6, 7, 8, 10, 11, 12}, committed);
			}
		);
	}

	@Test
	void shouldCorrectlyAddAndRemoveOnNonOverlappingPositionsAndCommit() {
		final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 2, 5, 6, 10, 11});

		assertStateAfterCommit(
			array,
			original -> {
				original.add(4);
				original.add(3);
				original.remove(10);
				original.remove(6);
				original.add(15);

				assertTransactionalArrayIs(new int[]{1, 2, 3, 4, 5, 11, 15}, original);
				assertFalse(array.contains(10));
				assertFalse(array.contains(6));
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[]{1, 2, 5, 6, 10, 11}, original);
				assertArrayEquals(new int[]{1, 2, 3, 4, 5, 11, 15}, committed);
			}
		);

	}

	@Test
	void shouldCorrectlyAddAndRemoveSameNumberAndCommit() {
		final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 2, 5, 6, 10, 11});

		assertStateAfterCommit(
			array,
			original -> {
				original.add(4);
				original.remove(4);

				assertTransactionalArrayIs(new int[]{1, 2, 5, 6, 10, 11}, original);
				assertFalse(array.contains(4));
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[]{1, 2, 5, 6, 10, 11}, original);
				assertArrayEquals(new int[]{1, 2, 5, 6, 10, 11}, committed);
			}
		);
	}

	@Test
	void shouldCorrectlyRemoveAndAddSameNumberAndCommit() {
		final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 2, 5, 6, 10, 11});

		assertStateAfterCommit(
			array,
			original -> {
				original.remove(5);
				original.remove(10);
				original.remove(11);
				original.add(10);
				original.add(11);
				original.add(5);

				assertTransactionalArrayIs(new int[]{1, 2, 5, 6, 10, 11}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[]{1, 2, 5, 6, 10, 11}, original);
				assertArrayEquals(new int[]{1, 2, 5, 6, 10, 11}, committed);
			}
		);

	}

	@Test
	void shouldCorrectlyAddAndRemoveOnOverlappingBoundaryPositionsAndCommit() {
		final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 2, 5, 6, 10, 11});

		assertStateAfterCommit(
			array,
			original -> {
				original.remove(1);
				original.remove(11);
				original.add(0);
				original.add(12);

				assertTransactionalArrayIs(new int[]{0, 2, 5, 6, 10, 12}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[]{1, 2, 5, 6, 10, 11}, original);
				assertArrayEquals(new int[]{0, 2, 5, 6, 10, 12}, committed);
			}
		);
	}

	@Test
	void shouldCorrectlyAddAndRemoveOnOverlappingMiddlePositionsAndCommit() {
		final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 5, 8, 11});

		assertStateAfterCommit(
			array,
			original -> {
				original.remove(5);
				original.remove(8);
				original.add(6);
				original.add(7);
				original.add(8);
				original.add(9);
				original.add(10);

				assertTransactionalArrayIs(new int[]{1, 6, 7, 8, 9, 10, 11}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[]{1, 5, 8, 11}, original);
				assertArrayEquals(new int[]{1, 6, 7, 8, 9, 10, 11}, committed);
			}
		);
	}

	@Test
	void shouldProperlyHandleChangesOnSinglePosition() {
		final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 2});

		assertStateAfterCommit(
			array,
			original -> {
				original.remove(1);
				original.remove(2);
				original.add(2);
				original.add(4);
				original.remove(2);
				original.add(5);

				assertTransactionalArrayIs(new int[]{4, 5}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new int[]{1, 2}, original);
				assertArrayEquals(new int[]{4, 5}, committed);
			}
		);
	}

	@Test
	void shouldCorrectlyWipeAll() {
		final TransactionalIntArray array = new TransactionalIntArray(new int[]{36, 59, 179});

		assertStateAfterCommit(
			array,
			original -> {
				original.add(31);
				original.remove(31);
				original.addAll(new int[]{140, 115});
				original.removeAll(new int[]{179, 36, 140});
				original.add(58);
				original.removeAll(new int[]{58, 115, 59});
				original.addAll(new int[]{156, 141});
				original.remove(141);
				original.add(52);
				original.removeAll(new int[]{52, 156});

				assertTransactionalArrayIs(new int[0], array);
			},
			(original, committed) -> assertArrayEquals(new int[0], committed)
		);
	}

	@ParameterizedTest(name = "TransactionalIntArray should survive generational randomized test applying modifications on it")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		runFor(
			input,
			10_000,
			new TestState(new Random(), 100),
			(random, testState) -> {
				final Random rnd = testState.rnd();
				final TransactionalIntArray tested = new TransactionalIntArray(testState.initialArray());
				final AtomicReference<int[]> control = new AtomicReference<>(
					Arrays.copyOf(testState.initialArray(), testState.initialArray().length)
				);
				final AtomicReference<int[]> nextArrayToCompare = new AtomicReference<>();

				assertStateAfterCommit(
					tested,
					original -> {
						final int operationsInTransaction = rnd.nextInt(100);
						for (int i = 0; i < operationsInTransaction; i++) {
							final int length = tested.getLength();
							if (rnd.nextBoolean() || length < 10) {
								// insert new item
								final int newRecId = rnd.nextInt(testState.initialCount() * 2);
								tested.add(newRecId);
								control.set(ArrayUtils.insertIntIntoOrderedArray(newRecId, control.get()));
							} else {
								// remove existing item
								final int removedRecId = tested.get(rnd.nextInt(length));
								tested.remove(removedRecId);
								control.set(ArrayUtils.removeIntFromOrderedArray(removedRecId, control.get()));
							}
						}
					},
					(original, committed) -> {
						assertArrayEquals(control.get(), committed);
						nextArrayToCompare.set(committed);
					}
				);

				return new TestState(
					testState,
					nextArrayToCompare.get()
				);
			}
		);
	}

	private record TestState(
		Random rnd,
		int initialCount,
		int[] initialArray,
		int iteration
	) {

		public TestState(@Nonnull Random rnd, int initialCount) {
			this(
				rnd,
				initialCount,
				generateRandomInitialArray(rnd, initialCount),
				0
			);
		}

		public TestState(@Nonnull TestState testState, int[] initialArray) {
			this(
				testState.rnd,
				testState.initialCount,
				initialArray,
				testState.iteration + 1
			);
		}

		private static int[] generateRandomInitialArray(Random rnd, int count) {
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
			Arrays.sort(initialArray);
			return initialArray;
		}
	}

}
