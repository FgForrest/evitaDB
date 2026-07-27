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

package io.evitadb.index.bitmap;

import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.util.Arrays;
import java.util.HashSet;
import java.util.PrimitiveIterator.OfInt;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generational randomized proof test for {@link TransactionalBitmap}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("TransactionalBitmap (generational randomized proof)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class LongRunningTransactionalBitmapTest implements TimeBoundedTestSupport {

	@ParameterizedTest(name = "TransactionalBitmap should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int initialCount = 100;
		final int[] initialState =
			generateRandomInitialBitmap(new Random(input.randomSeed()), initialCount);

		runFor(
			input,
			10_000,
			new TestState(initialState),
			(random, testState) -> {
				final TransactionalBitmap transactionalBitmap =
					new TransactionalBitmap(testState.initialBitmap());
				final AtomicReference<int[]> nextBitmapToCompare =
					new AtomicReference<>(testState.initialBitmap());

				assertStateAfterCommit(
					transactionalBitmap,
					original -> {
						final int operationsInTransaction = random.nextInt(100);
						for (int i = 0; i < operationsInTransaction; i++) {
							final int length = transactionalBitmap.size();
							if (random.nextBoolean() || length < 10) {
								// insert new item
								final int newRecId =
									random.nextInt(initialCount * 2);
								transactionalBitmap.add(newRecId);
								nextBitmapToCompare.set(
									ArrayUtils.insertIntIntoOrderedArray(
										newRecId, nextBitmapToCompare.get()
									)
								);
							} else {
								// remove existing item
								final int removedRecId =
									transactionalBitmap.get(
										random.nextInt(length)
									);
								transactionalBitmap.remove(removedRecId);
								nextBitmapToCompare.set(
									ArrayUtils.removeIntFromOrderedArray(
										removedRecId,
										nextBitmapToCompare.get()
									)
								);
							}
						}

						assertTransactionalBitmapIs(
							nextBitmapToCompare.get(), transactionalBitmap
						);
					},
					(original, committed) -> {
						assertArrayEquals(
							nextBitmapToCompare.get(),
							committed.getArray()
						);
					}
				);

				return new TestState(
					nextBitmapToCompare.get()
				);
			}
		);
	}

	private int[] generateRandomInitialBitmap(Random rnd, int count) {
		final Set<Integer> uniqueSet = new HashSet<>();
		final int[] initialBitmap = new int[count];
		for (int i = 0; i < count; i++) {
			boolean added;
			do {
				final int recId = rnd.nextInt(count * 2);
				added = uniqueSet.add(recId);
				if (added) {
					initialBitmap[i] = recId;
				}
			} while (!added);
		}
		Arrays.sort(initialBitmap);
		return initialBitmap;
	}

	private static void assertTransactionalBitmapIs(
		int[] expectedResult,
		TransactionalBitmap bitmap
	) {
		if (ArrayUtils.isEmpty(expectedResult)) {
			assertTrue(bitmap.isEmpty());
		} else {
			assertFalse(bitmap.isEmpty());
		}

		for (int recordId : expectedResult) {
			assertTrue(
				bitmap.contains(recordId),
				"IntegerBitmap should contain " + recordId + ", but does not!"
			);
		}

		assertArrayEquals(expectedResult, bitmap.getArray());
		assertEquals(expectedResult.length, bitmap.size());

		final OfInt it = bitmap.iterator();
		int index = -1;
		while (it.hasNext()) {
			final int nextInt = it.next();
			assertTrue(expectedResult.length > index + 1);
			assertEquals(expectedResult[++index], nextInt);
		}
		assertEquals(
			expectedResult.length, index + 1,
			"There are more expected ints than int bitmap produced by iterator!"
		);
	}

	private record TestState(
		int[] initialBitmap
	) {}
}
