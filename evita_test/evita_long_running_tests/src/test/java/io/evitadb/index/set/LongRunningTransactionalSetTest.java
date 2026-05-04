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

package io.evitadb.index.set;

import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generational randomized proof test for {@link TransactionalSet}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2017
 */
@DisplayName("TransactionalSet (generational randomized proof)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class LongRunningTransactionalSetTest implements TimeBoundedTestSupport {

	@DisplayName("survives generational randomized test")
	@ParameterizedTest(name = "TransactionalSet should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int initialCount = 100;
		final Set<String> initialSet = generateRandomInitialSet(new Random(input.randomSeed()), initialCount);

		runFor(
			input,
			50_000,
			new TestState(new StringBuilder(256), initialSet),
			(random, testState) -> {
				final TransactionalSet<String> transactionalMap = new TransactionalSet<>(testState.initialSet());
				final Set<String> referenceMap = new HashSet<>(testState.initialSet());
				final AtomicReference<Set<String>> committedResult = new AtomicReference<>();

				assertStateAfterCommit(
					transactionalMap,
					original -> {
						final StringBuilder codeBuffer = testState.code();
						codeBuffer.setLength(0);
						codeBuffer.append("\nSTART: ")
							.append(String.join(",", transactionalMap))
							.append("\n");

						final int operationsInTransaction = random.nextInt(5);
						for (int i = 0; i < operationsInTransaction; i++) {
							final int length = transactionalMap.size();
							assertEquals(referenceMap.size(), length);
							final int operation = random.nextInt(3);
							if ((operation == 0 || length < 10) && length < 120) {
								final String newRecKey =
									String.valueOf(
										(char) (40 + random.nextInt(64))
									);
								transactionalMap.add(newRecKey);
								referenceMap.add(newRecKey);
								codeBuffer.append("+")
									.append(newRecKey);
							} else if (operation == 1) {
								String recKey = null;
								final int index = random.nextInt(length);
								final Iterator<String> it = referenceMap.iterator();
								for (int j = 0; j <= index; j++) {
									final String key = it.next();
									if (j == index) {
										recKey = key;
									}
								}
								codeBuffer.append("-")
									.append(recKey);
								transactionalMap.remove(recKey);
								referenceMap.remove(recKey);
							} else {
								final int updateIndex = random.nextInt(length);
								codeBuffer.append("#")
									.append(updateIndex);
								final Iterator<String> it = transactionalMap.iterator();
								for (int j = 0; j <= updateIndex; j++) {
									final String entry = it.next();
									if (j == updateIndex) {
										it.remove();
										referenceMap.remove(entry);
									}
								}
							}
						}
						codeBuffer.append("\n");
					},
					(original, committed) -> {
						assertSetContains(
							committed,
							referenceMap.toArray(String[]::new)
						);
						committedResult.set(committed);
					}
				);

				return new TestState(
					new StringBuilder(256),
					committedResult.get()
				);
			},
			(testState, exc) ->
				System.out.println(testState.code())
		);
	}

	@SuppressWarnings("WhileLoopReplaceableByForEach")
	private static void assertSetContains(
		@Nonnull Set<String> set,
		@Nonnull String... data
	) {
		if (data.length == 0) {
			assertTrue(set.isEmpty());
		} else {
			assertFalse(set.isEmpty());
		}

		assertEquals(data.length, set.size());

		final Set<String> expectedSet = new HashSet<>(data.length);
		for (String dataItem : data) {
			expectedSet.add(dataItem);
			assertTrue(set.contains(dataItem));
		}

		final Iterator<String> it = set.iterator();
		while (it.hasNext()) {
			final String entry = it.next();
			assertTrue(expectedSet.contains(entry));
		}
	}

	@Nonnull
	private static Set<String> generateRandomInitialSet(
		@Nonnull Random rnd,
		int count
	) {
		final Set<String> initialArray = new HashSet<>(count);
		for (int i = 0; i < count; i++) {
			final String recKey = String.valueOf((char) (40 + rnd.nextInt(64)));
			initialArray.add(recKey);
		}
		return initialArray;
	}

	private record TestState(
		@Nonnull StringBuilder code,
		@Nonnull Set<String> initialSet
	) {
	}
}
