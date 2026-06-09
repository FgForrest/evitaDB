/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.index.map;

import io.evitadb.dataType.champ.ChampMap;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.stream.Collectors;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generational randomized proof test for {@link PersistentTransactionalMap} (the {@link ChampMap}-backed STM map for
 * plain values). Each generation rebuilds a fresh map from the previous generation's committed reference, applies a
 * random batch of put / remove / iterator-update / iterator-remove operations inside a transaction, and verifies the
 * committed snapshot — which must be an immutable {@link ChampMap} — matches a JDK reference map tracking the same
 * operations. Because each generation feeds the next, any accumulated drift in the `O(Δ·log₃₂ N)` commit (a stale
 * shared subtree, a mis-applied removal) compounds and is caught over thousands of iterations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("PersistentTransactionalMap (generational randomized proof)")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class LongRunningPersistentTransactionalMapTest implements TimeBoundedTestSupport {

	@DisplayName("survives generational randomized test applying modifications on it")
	@ParameterizedTest(name = "PersistentTransactionalMap should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(@Nonnull GenerationalTestInput input) {
		final int initialCount = 100;
		final Map<String, Integer> initialState = generateRandomInitialMap(new Random(input.randomSeed()), initialCount);

		runFor(
			input,
			10_000,
			new TestState(
				new StringBuilder(256),
				initialState
			),
			(random, testState) -> {
				final PersistentTransactionalMap<String, Integer> transactionalMap =
					new PersistentTransactionalMap<>(testState.initialMap());
				final Map<String, Integer> referenceMap = new HashMap<>(testState.initialMap());

				final StringBuilder codeBuffer = testState.code();
				codeBuffer.append("\nSTART: ")
					.append(
						transactionalMap.entrySet()
							.stream()
							.map(entry -> entry.getKey() + ": " + entry.getValue())
							.collect(Collectors.joining(","))
					)
					.append("\n");

				assertStateAfterCommit(
					transactionalMap,
					original -> {
						final int operationsInTransaction = random.nextInt(5);
						for (int i = 0; i < operationsInTransaction; i++) {
							final int length = transactionalMap.size();
							assertEquals(referenceMap.size(), length);
							final int operation = random.nextInt(4);
							if ((operation == 0 || length < 10) && length < 120) {
								// insert / update item
								final String newRecKey = String.valueOf((char) (40 + random.nextInt(64)));
								final Integer newRecId = random.nextInt(initialCount << 1);
								transactionalMap.put(newRecKey, newRecId);
								referenceMap.put(newRecKey, newRecId);
								codeBuffer.append("+").append(newRecKey).append(":").append(newRecId);
							} else if (operation == 1) {
								String recKey = null;
								final int index = random.nextInt(length);
								final Iterator<String> it = referenceMap.keySet().iterator();
								for (int j = 0; j <= index; j++) {
									final String key = it.next();
									if (j == index) {
										recKey = key;
									}
								}
								codeBuffer.append("-").append(recKey);
								transactionalMap.remove(recKey);
								referenceMap.remove(recKey);
							} else if (operation == 2) {
								// update existing item by iterator
								final int updateIndex = random.nextInt(length);
								final Integer updatedValue = random.nextInt(initialCount << 1);
								codeBuffer.append("!").append(updateIndex).append(":").append(updatedValue);
								final Iterator<Entry<String, Integer>> it = transactionalMap.entrySet().iterator();
								for (int j = 0; j <= updateIndex; j++) {
									final Entry<String, Integer> entry = it.next();
									if (j == updateIndex) {
										entry.setValue(updatedValue);
										referenceMap.put(entry.getKey(), updatedValue);
									}
								}
							} else {
								// remove existing item by iterator
								final int updateIndex = random.nextInt(length);
								codeBuffer.append("#").append(updateIndex);
								final Iterator<Entry<String, Integer>> it = transactionalMap.entrySet().iterator();
								for (int j = 0; j <= updateIndex; j++) {
									final Entry<String, Integer> entry = it.next();
									if (j == updateIndex) {
										it.remove();
										referenceMap.remove(entry.getKey());
									}
								}
							}
						}
						codeBuffer.append("\n");
					},
					(original, committed) -> {
						// the committed snapshot must be the immutable persistent form, derived O(Δ) from the previous one
						assertInstanceOf(ChampMap.class, committed);
						assertMapContains(
							committed,
							referenceMap.entrySet()
								.stream()
								.map(it -> new Tuple(it.getKey(), it.getValue()))
								.toArray(Tuple[]::new)
						);
					}
				);

				return new TestState(
					new StringBuilder(256),
					referenceMap
				);
			}
		);
	}

	@SuppressWarnings("WhileLoopReplaceableByForEach")
	private static void assertMapContains(@Nonnull Map<String, Integer> map, @Nonnull Tuple... data) {
		if (data.length == 0) {
			assertTrue(map.isEmpty());
		} else {
			assertFalse(map.isEmpty());
		}

		assertEquals(data.length, map.size());

		final Map<String, Integer> expectedMap = new HashMap<>(data.length);
		for (Tuple tuple : data) {
			expectedMap.put(tuple.key(), tuple.value());
			assertEquals(tuple.value(), map.get(tuple.key()));
			assertTrue(map.containsKey(tuple.key()));
			assertTrue(map.containsValue(tuple.value()));
		}

		final Iterator<Entry<String, Integer>> it = map.entrySet().iterator();
		while (it.hasNext()) {
			final Entry<String, Integer> entry = it.next();
			assertEquals(expectedMap.get(entry.getKey()), entry.getValue());
		}

		final Iterator<String> keyIt = map.keySet().iterator();
		while (keyIt.hasNext()) {
			final String key = keyIt.next();
			assertTrue(expectedMap.containsKey(key));
		}

		final Iterator<Integer> valueIt = map.values().iterator();
		while (valueIt.hasNext()) {
			final Integer value = valueIt.next();
			assertTrue(expectedMap.containsValue(value));
		}
	}

	@Nonnull
	private static Map<String, Integer> generateRandomInitialMap(@Nonnull Random rnd, int count) {
		final Map<String, Integer> initialArray = new HashMap<>(count);
		for (int i = 0; i < count; i++) {
			final String recKey = String.valueOf((char) (40 + rnd.nextInt(64)));
			final int recId = rnd.nextInt(count << 1);
			initialArray.put(recKey, recId);
		}
		return initialArray;
	}

	private record TestState(
		@Nonnull StringBuilder code,
		@Nonnull Map<String, Integer> initialMap
	) {}

	private record Tuple(@Nonnull String key, @Nonnull Integer value) {
	}
}
