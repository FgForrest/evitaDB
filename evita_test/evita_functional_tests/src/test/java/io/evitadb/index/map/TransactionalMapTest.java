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

package io.evitadb.index.map;

import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.util.AbstractMap.SimpleEntry;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * This test verifies contract of {@link TransactionalMap} implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2017
 */
class TransactionalMapTest implements TimeBoundedTestSupport {
	private TransactionalMap<String, Integer> tested;

	@SuppressWarnings("WhileLoopReplaceableByForEach")
	private static void assertMapContains(Map<String, Integer> map, Tuple... data) {
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

	@BeforeEach
	void setUp() {
		HashMap<String, Integer> underlyingData = new LinkedHashMap<>();
		underlyingData.put("a", 1);
		underlyingData.put("b", 2);
		this.tested = new TransactionalMap<>(underlyingData);
	}

	@Test
	void shouldNotModifyOriginalStateButCreateModifiedCopy() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.put("a", 3);
				original.put("c", 3);
				assertMapContains(original, new Tuple("a", 3), new Tuple("b", 2), new Tuple("c", 3));
			},
			(original, committedVersion) -> {
				assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
				assertMapContains(committedVersion, new Tuple("a", 3), new Tuple("b", 2), new Tuple("c", 3));
			}
		);
	}

	@Test
	void removalsShouldNotModifyOriginalState() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.remove("a");
				original.put("c", 3);
				assertMapContains(original, new Tuple("b", 2), new Tuple("c", 3));
			},
			(original, committedVersion) -> {
				assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
				assertMapContains(committedVersion, new Tuple("b", 2), new Tuple("c", 3));
			}
		);
	}

	@Test
	void verify() {
		/*
		 * START: Q: 33,b: 29,S: 185,3: 86,c: 110,T: 181,e: 38,6: 91,J: 65
		 * */

		this.tested.clear();
		this.tested.put("Q", 33);
		this.tested.put("b", 29);
		this.tested.put("S", 185);
		this.tested.put("3", 86);
		this.tested.put("c", 110);
		this.tested.put("T", 181);
		this.tested.put("e", 38);
		this.tested.put("6", 91);
		this.tested.put("J", 65);

		final HashMap<String, Integer> referenceMap = new HashMap<>(this.tested);

		assertStateAfterCommit(
			this.tested,
			original -> {

				/* +D:18#0+D:72 */

				original.put("D", 18);
				referenceMap.put("D", 18);
				final Iterator<Entry<String, Integer>> it = original.entrySet().iterator();
				final Entry<String, Integer> entry = it.next();
				it.remove();
				referenceMap.remove(entry.getKey());
				original.put("D", 72);
				referenceMap.put("D", 72);

				assertMapContains(original, referenceMap.entrySet().stream().map(x -> new Tuple(x.getKey(), x.getValue())).toArray(Tuple[]::new));
			},
			(original, committedVersion) -> {
				assertMapContains(committedVersion, referenceMap.entrySet().stream().map(x -> new Tuple(x.getKey(), x.getValue())).toArray(Tuple[]::new));
			}
		);
	}

	@Test
	void shouldMergeRemovalsAndUpdatesAndInsertionsOnTransactionCommit() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.remove("a");
				original.put("b", 3);
				original.put("c", 3);

				assertMapContains(original, new Tuple("b", 3), new Tuple("c", 3));
			},
			(original, committedVersion) -> {
				assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
				assertMapContains(committedVersion, new Tuple("b", 3), new Tuple("c", 3));
			}
		);
	}

	@Test
	void shouldInterpretIsEmptyCorrectly() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				assertFalse(original.isEmpty());

				original.put("c", 3);
				assertFalse(original.isEmpty());

				original.remove("a");
				assertFalse(original.isEmpty());

				original.remove("c");
				assertFalse(original.isEmpty());

				original.remove("b");
				assertTrue(original.isEmpty());

				original.put("d", 4);
				assertFalse(original.isEmpty());

				original.remove("d");
				assertTrue(original.isEmpty());

				assertMapContains(original);
			},
			(original, committedVersion) -> {
				assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
				assertMapContains(committedVersion);
			}
		);
	}

	@Test
	void shouldProduceValidValueCollection() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.put("c", 3);
				original.remove("b");

				final Set<Integer> result = new HashSet<>(original.values());
				assertEquals(2, result.size());
				assertTrue(result.contains(1));
				assertTrue(result.contains(3));
			},
			(original, committedVersion) -> {
				final Set<Integer> result = new HashSet<>(committedVersion.values());
				assertEquals(2, result.size());
				assertTrue(result.contains(1));
				assertTrue(result.contains(3));
			}
		);
	}

	@Test
	void shouldProduceValidKeySet() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.put("c", 3);
				original.remove("b");

				final Set<String> result = new HashSet<>(original.keySet());
				assertEquals(2, result.size());
				assertTrue(result.contains("a"));
				assertTrue(result.contains("c"));
			},
			(original, committedVersion) -> {
				final Set<String> result = new HashSet<>(committedVersion.keySet());
				assertEquals(2, result.size());
				assertTrue(result.contains("a"));
				assertTrue(result.contains("c"));
			}
		);
	}

	@Test
	void shouldProduceValidEntrySet() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.put("c", 3);
				original.remove("b");

				final Set<Entry<String, Integer>> entries = new HashSet<>(original.entrySet());
				assertEquals(2, entries.size());
				assertTrue(entries.contains(new SimpleEntry<>("a", 1)));
				assertTrue(entries.contains(new SimpleEntry<>("c", 3)));
			},
			(original, committedVersion) -> {
				final Set<Entry<String, Integer>> entries = new HashSet<>(committedVersion.entrySet());
				assertEquals(2, entries.size());
				assertTrue(entries.contains(new SimpleEntry<>("a", 1)));
				assertTrue(entries.contains(new SimpleEntry<>("c", 3)));
			}
		);
	}

	@Test
	void shouldNotModifyOriginalStateOnKeySetIteratorRemoval() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.put("c", 3);

				final Iterator<String> it = original.keySet().iterator();
				//noinspection Java8CollectionRemoveIf
				while (it.hasNext()) {
					final String key = it.next();
					if (key.equals("b")) {
						it.remove();
					}
				}

				assertMapContains(original, new Tuple("a", 1), new Tuple("c", 3));
			},
			(original, committedVersion) -> {
				assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
				assertMapContains(committedVersion, new Tuple("a", 1), new Tuple("c", 3));
			}
		);
	}

	@Test
	void shouldNotModifyOriginalStateOnValuesIteratorRemoval() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.put("c", 3);

				final Iterator<Integer> it = original.values().iterator();
				//noinspection Java8CollectionRemoveIf
				while (it.hasNext()) {
					final Integer value = it.next();
					if (value.equals(2)) {
						it.remove();
					}
				}

				assertMapContains(original, new Tuple("a", 1), new Tuple("c", 3));
			},
			(original, committedVersion) -> {
				assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
				assertMapContains(committedVersion, new Tuple("a", 1), new Tuple("c", 3));
			}
		);
	}

	@Test
	void shouldRemoveValuesWhileIteratingOverThem() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.clear();
				original.put("ac", 1);
				original.put("bc", 2);
				original.put("ad", 3);
				original.put("ae", 4);

				original.keySet().removeIf(key -> key.contains("a"));

				assertMapContains(original, new Tuple("bc", 2));
			},
			(original, committedVersion) -> {
				assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
				assertMapContains(committedVersion, new Tuple("bc", 2));
			}
		);
	}

	@Test
	void shouldMergeChangesInEntrySetIterator() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.put("c", 3);

				final Iterator<Entry<String, Integer>> it = original.entrySet().iterator();
				//noinspection WhileLoopReplaceableByForEach
				while (it.hasNext()) {
					final Entry<String, Integer> entry = it.next();
					if ("b".equals(entry.getKey())) {
						entry.setValue(5);
					}
				}

				assertMapContains(original, new Tuple("a", 1), new Tuple("b", 5), new Tuple("c", 3));
			},
			(original, committedVersion) -> {
				assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
				assertMapContains(committedVersion, new Tuple("a", 1), new Tuple("b", 5), new Tuple("c", 3));
			}
		);
	}

	@Test
	void shouldKeepIteratorContract() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.put("c", 3);

				final List<String> result = new ArrayList<>(3);

				final Iterator<Entry<String, Integer>> it = original.entrySet().iterator();
				for (int i = 0; i < 50; i++) {
					assertTrue(it.hasNext());
				}

				result.add(it.next().getKey());
				for (int i = 0; i < 50; i++) {
					assertTrue(it.hasNext());
				}

				result.add(it.next().getKey());
				for (int i = 0; i < 50; i++) {
					assertTrue(it.hasNext());
				}

				result.add(it.next().getKey());
				for (int i = 0; i < 50; i++) {
					assertFalse(it.hasNext());
				}

				assertEquals(new HashSet<>(Arrays.asList("a", "b", "c")), new HashSet<>(result));

				try {
					it.next();
					fail("Exception expected!");
				} catch (NoSuchElementException ex) {
					//ok
				}
			},
			(original, committedVersion) -> {
				// do nothing
			}
		);
	}

	@Test
	void shouldKeepIteratorContractWhenItemsRemoved() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.put("c", 3);
				original.remove("b");

				final List<String> result = new ArrayList<>(3);

				final Iterator<Entry<String, Integer>> it = original.entrySet().iterator();
				for (int i = 0; i < 50; i++) {
					assertTrue(it.hasNext());
				}

				result.add(it.next().getKey());
				for (int i = 0; i < 50; i++) {
					assertTrue(it.hasNext());
				}

				result.add(it.next().getKey());
				for (int i = 0; i < 50; i++) {
					assertFalse(it.hasNext());
				}

				assertEquals(new HashSet<>(Arrays.asList("a", "c")), new HashSet<>(result));

				try {
					it.next();
					fail("Exception expected!");
				} catch (NoSuchElementException ex) {
					//ok
				}
			},
			(original, committedVersion) -> {
				// do nothing
			}
		);
	}

	@ParameterizedTest(name = "TransactionalMap should survive generational randomized test applying modifications on it")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int initialCount = 100;
		final Map<String, Integer> initialState = generateRandomInitialMap(new Random(input.randomSeed()), initialCount);

		runFor(
			input,
			10_000,
			new TestState(
				new StringBuilder(),
				initialState
			),
			(random, testState) -> {
				final TransactionalMap<String, Integer> transactionalMap = new TransactionalMap<>(testState.initialMap());
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
								final Integer newRecId = random.nextInt(initialCount * 2);
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
								final Integer updatedValue = random.nextInt(initialCount * 2);
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
					new StringBuilder(),
					referenceMap
				);
			}
		);
	}

	private Map<String, Integer> generateRandomInitialMap(Random rnd, int count) {
		final Map<String, Integer> initialArray = new HashMap<>(count);
		for (int i = 0; i < count; i++) {
			final String recKey = String.valueOf((char) (40 + rnd.nextInt(64)));
			final int recId = rnd.nextInt(count * 2);
			initialArray.put(recKey, recId);
		}
		return initialArray;
	}

	private record TestState(
		StringBuilder code,
		Map<String, Integer> initialMap
	) {}

	private record Tuple(String key, Integer value) {
	}

}
