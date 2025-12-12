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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link TransactionalSet} implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2017
 */
class TransactionalSetTest implements TimeBoundedTestSupport {
	private TransactionalSet<String> tested;

	@BeforeEach
	void setUp() {
		final Set<String> underlyingData = new LinkedHashSet<>();
		underlyingData.add("a");
		underlyingData.add("b");
		this.tested = new TransactionalSet<>(underlyingData);
	}

	@Test
	void shouldNotModifyOriginalStateButCreateModifiedCopy() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.add("a");
				original.add("c");
				assertSetContains(original, "a", "b", "c");
			},
			(original, committedVersion) -> {
				assertSetContains(original, "a", "b");
				assertSetContains(committedVersion, "a", "b", "c");
			}
		);
	}

	@Test
	void removalsShouldNotModifyOriginalState() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.remove("a");
				original.add("c");
				assertSetContains(original, "b", "c");
			},
			(original, committedVersion) -> {
				assertSetContains(original, "a", "b");
				assertSetContains(committedVersion, "b", "c");
			}
		);
	}

	@Test
	void shouldRetainAllTransactionally() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.retainAll(Arrays.asList("a", "c", "d"));
				assertSetContains(original, "a");
			},
			(original, committedVersion) -> {
				assertSetContains(original, "a", "b");
				assertSetContains(committedVersion, "a");
			}
		);
	}

	@Test
	void shouldRemoveAllTransactionally() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				//noinspection SlowAbstractSetRemoveAll
				original.removeAll(Arrays.asList("a", "c", "d"));
				assertSetContains(original, "b");
			},
			(original, committedVersion) -> {
				assertSetContains(original, "a", "b");
				assertSetContains(committedVersion, "b");
			}
		);
	}

	@Test
	void shouldCreateToArrayTransactionally() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.add("c");
				original.remove("b");
				assertSetContains(original, "a", "c");
				assertArrayEquals(new String[] {"a", "c"}, original.toArray());
			},
			(original, committedVersion) -> {
				assertSetContains(original, "a", "b");
				assertArrayEquals(new String[] {"a", "c"}, committedVersion.toArray());
			}
		);
	}

	@Test
	void verify() {
		/*
		 * START: Q,b,S,3,c,T,e,6,J
		 * */

		this.tested.clear();
		this.tested.add("Q");
		this.tested.add("b");
		this.tested.add("S");
		this.tested.add("3");
		this.tested.add("c");
		this.tested.add("T");
		this.tested.add("e");
		this.tested.add("6");
		this.tested.add("J");

		final HashSet<String> referenceMap = new HashSet<>(this.tested);

		assertStateAfterCommit(
			this.tested,
			original -> {

				/* +D#0+D */

				original.add("D");
				referenceMap.add("D");
				final Iterator<String> it = original.iterator();
				final String entry = it.next();
				it.remove();
				referenceMap.remove(entry);
				original.add("D");
				referenceMap.add("D");

				assertSetContains(original, referenceMap.toArray(String[]::new));
			},
			(original, committedVersion) -> {
				assertSetContains(committedVersion, referenceMap.toArray(String[]::new));
			}
		);
	}

	@Test
	void shouldMergeRemovalsAndUpdatesAndInsertionsOnTransactionCommit() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.remove("a");
				original.add("b");
				original.add("c");

				assertSetContains(original, "b", "c");
			},
			(original, committedVersion) -> {
				assertSetContains(original, "a", "b");
				assertSetContains(committedVersion, "b", "c");
			}
		);
	}

	@Test
	void shouldInterpretIsEmptyCorrectly() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				assertFalse(original.isEmpty());

				original.add("c");
				assertFalse(original.isEmpty());

				original.remove("a");
				assertFalse(original.isEmpty());

				original.remove("c");
				assertFalse(original.isEmpty());

				original.remove("b");
				assertTrue(original.isEmpty());

				original.add("d");
				assertFalse(original.isEmpty());

				original.remove("d");
				assertTrue(original.isEmpty());

				assertSetContains(original);
			},
			(original, committedVersion) -> {
				assertSetContains(original, "a", "b");
				assertSetContains(committedVersion);
			}
		);
	}

	@Test
	void shouldNotModifyOriginalStateOnKeySetIteratorRemoval() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.add("c");

				final Iterator<String> it = original.iterator();
				//noinspection Java8CollectionRemoveIf
				while (it.hasNext()) {
					final String key = it.next();
					if (key.equals("b")) {
						it.remove();
					}
				}

				assertSetContains(original, "a", "c");
			},
			(original, committedVersion) -> {
				assertSetContains(original, "a", "b");
				assertSetContains(committedVersion, "a", "c");
			}
		);
	}

	@Test
	void shouldRemoveValuesWhileIteratingOverThem() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.clear();
				original.add("ac");
				original.add("bc");
				original.add("ad");
				original.add("ae");

				original.removeIf(key -> key.contains("a"));

				assertSetContains(original, "bc");
			},
			(original, committedVersion) -> {
				assertSetContains(original, "a", "b");
				assertSetContains(committedVersion, "bc");
			}
		);
	}

	@Test
	void shouldKeepIteratorContract() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.add("c");

				final List<String> result = new ArrayList<>(3);

				final Iterator<String> it = original.iterator();
				for (int i = 0; i < 50; i++) {
					assertTrue(it.hasNext());
				}

				result.add(it.next());
				for (int i = 0; i < 50; i++) {
					assertTrue(it.hasNext());
				}

				result.add(it.next());
				for (int i = 0; i < 50; i++) {
					assertTrue(it.hasNext());
				}

				result.add(it.next());
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
				original.add("c");
				original.remove("b");

				final List<String> result = new ArrayList<>(3);

				final Iterator<String> it = original.iterator();
				for (int i = 0; i < 50; i++) {
					assertTrue(it.hasNext());
				}

				result.add(it.next());
				for (int i = 0; i < 50; i++) {
					assertTrue(it.hasNext());
				}

				result.add(it.next());
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

	@ParameterizedTest(name = "TransactionalSet should survive generational randomized test applying modifications on it")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int initialCount = 100;
		final Set<String> initialSet = generateRandomInitialSet(new Random(input.randomSeed()), initialCount);

		runFor(
			input,
			50_000,
			new TestState(new StringBuilder(), initialSet),
			(random, testState) -> {
				final TransactionalSet<String> transactionalMap = new TransactionalSet<>(testState.initialSet());
				final Set<String> referenceMap = new HashSet<>(testState.initialSet());
				final AtomicReference<Set<String>> committedResult = new AtomicReference<>();

				assertStateAfterCommit(
					transactionalMap,
					original -> {
						final StringBuilder codeBuffer = testState.code();
						codeBuffer.setLength(0);
						codeBuffer.append("\nSTART: ").append(String.join(",", transactionalMap)).append("\n");

						final int operationsInTransaction = random.nextInt(5);
						for (int i = 0; i < operationsInTransaction; i++) {
							final int length = transactionalMap.size();
							assertEquals(referenceMap.size(), length);
							final int operation = random.nextInt(3);
							if ((operation == 0 || length < 10) && length < 120) {
								// insert / update item
								final String newRecKey = String.valueOf((char) (40 + random.nextInt(64)));
								transactionalMap.add(newRecKey);
								referenceMap.add(newRecKey);
								codeBuffer.append("+").append(newRecKey);
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
								codeBuffer.append("-").append(recKey);
								transactionalMap.remove(recKey);
								referenceMap.remove(recKey);
							} else {
								// remove existing item by iterator
								final int updateIndex = random.nextInt(length);
								codeBuffer.append("#").append(updateIndex);
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
						assertSetContains(committed, referenceMap.toArray(String[]::new));
						committedResult.set(committed);
					}
				);

				return new TestState(
					new StringBuilder(),
					committedResult.get()
				);
			},
			(testState, exc) -> System.out.println(testState.code())
		);
	}

	private Set<String> generateRandomInitialSet(Random rnd, int count) {
		final Set<String> initialArray = new HashSet<>(count);
		for (int i = 0; i < count; i++) {
			final String recKey = String.valueOf((char) (40 + rnd.nextInt(64)));
			initialArray.add(recKey);
		}
		return initialArray;
	}

	@SuppressWarnings("WhileLoopReplaceableByForEach")
	private static void assertSetContains(Set<String> set, String... data) {
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

	private record TestState(
		StringBuilder code,
		Set<String> initialSet
	) {}

}
