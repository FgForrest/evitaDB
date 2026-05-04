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

import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Generational randomized proof test for {@link TransactionalObjArray}.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Transactional object array (generational randomized proof)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class LongRunningTransactionalObjArrayTest implements TimeBoundedTestSupport {

	@DisplayName(
		"survives generational randomized test applying modifications"
	)
	@ParameterizedTest(
		name = "TransactionalObjArray should survive generational"
			+ " randomized test applying modifications on it"
	)
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		runFor(
			input,
			10_000,
			new TestState(new Random(), 100),
			(random, testState) -> {
				final Random rnd = testState.rnd();
				final TransactionalObjArray<Integer> tested =
					new TransactionalObjArray<>(
						testState.initialArray(),
						Comparator.naturalOrder()
					);
				final AtomicReference<Integer[]> control =
					new AtomicReference<>(
						Arrays.copyOf(
							testState.initialArray(),
							testState.initialArray().length
						)
					);
				final AtomicReference<Integer[]>
					nextArrayToCompare = new AtomicReference<>();

				assertStateAfterCommit(
					tested,
					original -> {
						final int operationsInTransaction =
							rnd.nextInt(100);
						for (
							int i = 0;
							i < operationsInTransaction;
							i++
						) {
							final int length =
								tested.getLength();
							if (
								rnd.nextBoolean()
									|| length < 10
							) {
								// insert new item
								final int newRecId = rnd.nextInt(
									testState.initialCount() * 2
								);
								tested.add(newRecId);
								control.set(
									ArrayUtils
										.insertRecordIntoOrderedArray(
											newRecId,
											control.get()
										)
								);
							} else {
								// remove existing item
								final int removedRecId =
									tested.get(
										rnd.nextInt(length)
									);
								tested.remove(removedRecId);
								control.set(
									ArrayUtils
										.removeRecordFromOrderedArray(
											removedRecId,
											control.get()
										)
								);
							}
						}
					},
					(original, committed) -> {
						assertArrayEquals(
							control.get(), committed
						);
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

	/**
	 * Holds the state carried between generational test iterations.
	 */
	private record TestState(
		Random rnd,
		int initialCount,
		Integer[] initialArray,
		int iteration
	) {

		public TestState(
			@Nonnull Random rnd,
			int initialCount
		) {
			this(
				rnd,
				initialCount,
				generateRandomInitialArray(rnd, initialCount),
				0
			);
		}

		public TestState(
			@Nonnull TestState testState,
			Integer[] initialArray
		) {
			this(
				testState.rnd,
				testState.initialCount,
				initialArray,
				testState.iteration + 1
			);
		}

		private static Integer[] generateRandomInitialArray(
			Random rnd,
			int count
		) {
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
	}

}
