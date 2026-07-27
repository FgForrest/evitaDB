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

package io.evitadb.index.range;

import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generational randomized proof test for {@link TransactionalRangePoint}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("TransactionalRangePoint (generational randomized proof)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class LongRunningTransactionalRangePointTest implements TimeBoundedTestSupport {

	@DisplayName("survives generational randomized test applying modifications on it")
	@ParameterizedTest(
		name = "TransactionalRangePoint should survive generational randomized test"
	)
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int maxId = 50;
		final Random seedRandom = new Random(input.randomSeed());
		final long initialThreshold = seedRandom.nextLong(1000);
		final Set<Integer> initialStarts =
			generateRandomIdSet(seedRandom, maxId);
		final Set<Integer> initialEnds =
			generateRandomIdSet(seedRandom, maxId);

		runFor(
			input,
			1_000,
			new TestState(
				new StringBuilder(256),
				initialThreshold,
				initialStarts,
				initialEnds
			),
			(random, testState) -> {
				final TransactionalRangePoint rangePoint =
					new TransactionalRangePoint(
						testState.threshold(),
						toSortedIntArray(testState.starts()),
						toSortedIntArray(testState.ends())
					);
				final Set<Integer> referenceStarts =
					new HashSet<>(testState.starts());
				final Set<Integer> referenceEnds =
					new HashSet<>(testState.ends());

				final StringBuilder codeBuffer = testState.code();
				codeBuffer.setLength(0);
				codeBuffer.append("\nSTART threshold=")
					.append(testState.threshold())
					.append(" starts=")
					.append(referenceStarts)
					.append(" ends=")
					.append(referenceEnds)
					.append("\n");

				assertStateAfterCommit(
					rangePoint,
					original -> {
						final int operationsInTransaction =
							1 + random.nextInt(10);
						for (int i = 0; i < operationsInTransaction; i++) {
							final int operation = random.nextInt(6);
							switch (operation) {
								case 0 -> {
									// addStart — single ID
									final int id = 1 + random.nextInt(maxId);
									original.addStart(id);
									referenceStarts.add(id);
									codeBuffer.append("+S")
										.append(id).append(" ");
								}
								case 1 -> {
									// addEnd — single ID
									final int id = 1 + random.nextInt(maxId);
									original.addEnd(id);
									referenceEnds.add(id);
									codeBuffer.append("+E")
										.append(id).append(" ");
								}
								case 2 -> {
									// addStarts — bulk add
									final int[] ids =
										generateRandomIds(random, maxId);
									original.addStarts(ids);
									for (int id : ids) {
										referenceStarts.add(id);
									}
									codeBuffer.append("+SS")
										.append(java.util.Arrays.toString(ids))
										.append(" ");
								}
								case 3 -> {
									// addEnds — bulk add
									final int[] ids =
										generateRandomIds(random, maxId);
									original.addEnds(ids);
									for (int id : ids) {
										referenceEnds.add(id);
									}
									codeBuffer.append("+EE")
										.append(java.util.Arrays.toString(ids))
										.append(" ");
								}
								case 4 -> {
									// removeStarts — bulk remove
									final int[] ids =
										generateRandomIds(random, maxId);
									original.removeStarts(ids);
									for (int id : ids) {
										referenceStarts.remove(id);
									}
									codeBuffer.append("-SS")
										.append(java.util.Arrays.toString(ids))
										.append(" ");
								}
								case 5 -> {
									// removeEnds — bulk remove
									final int[] ids =
										generateRandomIds(random, maxId);
									original.removeEnds(ids);
									for (int id : ids) {
										referenceEnds.remove(id);
									}
									codeBuffer.append("-EE")
										.append(java.util.Arrays.toString(ids))
										.append(" ");
								}
							}
						}
						codeBuffer.append("\n");
					},
					(original, committed) -> {
						assertBitmapMatchesSet(
							committed.getStarts(),
							referenceStarts,
							"starts mismatch\n" + codeBuffer
						);
						assertBitmapMatchesSet(
							committed.getEnds(),
							referenceEnds,
							"ends mismatch\n" + codeBuffer
						);
						assertEquals(
							testState.threshold(),
							committed.getThreshold(),
							"threshold changed unexpectedly"
						);
					}
				);

				return new TestState(
					new StringBuilder(256),
					testState.threshold(),
					referenceStarts,
					referenceEnds
				);
			}
		);
	}

	/**
	 * Carries the state between generational test iterations.
	 */
	private record TestState(
		@Nonnull StringBuilder code,
		long threshold,
		@Nonnull Set<Integer> starts,
		@Nonnull Set<Integer> ends
	) {}

	@Nonnull
	private static Set<Integer> generateRandomIdSet(
		@Nonnull Random random,
		int maxId
	) {
		final int count = random.nextInt(maxId / 2);
		final Set<Integer> result = new HashSet<>(count);
		for (int i = 0; i < count; i++) {
			result.add(1 + random.nextInt(maxId));
		}
		return result;
	}

	@Nonnull
	private static int[] generateRandomIds(
		@Nonnull Random random,
		int maxId
	) {
		final int count = 1 + random.nextInt(5);
		final int[] ids = new int[count];
		for (int i = 0; i < count; i++) {
			ids[i] = 1 + random.nextInt(maxId);
		}
		return ids;
	}

	@Nonnull
	private static int[] toSortedIntArray(@Nonnull Set<Integer> idSet) {
		return idSet.stream().mapToInt(Integer::intValue).sorted().toArray();
	}

	private static void assertBitmapMatchesSet(
		@Nonnull Bitmap bitmap,
		@Nonnull Set<Integer> referenceSet,
		@Nonnull String message
	) {
		assertEquals(
			referenceSet.size(), bitmap.size(),
			"size mismatch: " + message
		);
		for (final int id : referenceSet) {
			assertTrue(
				bitmap.contains(id),
				"bitmap missing ID " + id + ": " + message
			);
		}
		// verify no extra IDs exist in the bitmap
		final int[] bitmapArray = bitmap.getArray();
		for (final int id : bitmapArray) {
			assertTrue(
				referenceSet.contains(id),
				"bitmap has unexpected ID " + id + ": " + message
			);
		}
	}
}
