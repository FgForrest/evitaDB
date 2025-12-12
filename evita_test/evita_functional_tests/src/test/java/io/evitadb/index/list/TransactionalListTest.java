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

package io.evitadb.index.list;

import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.util.*;
import java.util.stream.Collectors;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link TransactionalList} implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2018
 */
class TransactionalListTest implements TimeBoundedTestSupport {
	private List<Integer> underlyingData;
	private TransactionalList<Integer> tested;

	@BeforeEach
	void setUp() {
		this.underlyingData = new LinkedList<>();
		this.underlyingData.add(1);
		this.underlyingData.add(2);
		this.tested = new TransactionalList<>(this.underlyingData);
	}

	@Test
	void shouldNotModifyOriginalState() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.add(3);
				original.add(3);
				assertListContains(original, 1, 2, 3, 3);
			},
			(original, committedVersion) -> {
				assertListContains(this.underlyingData, 1, 2);
				assertListContains(committedVersion, 1, 2, 3, 3);
			}
		);
	}

	@Test
	void shouldAppendAtEnd() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.remove(0);
				original.add(1, 3);
				original.add(1, 4);
				original.remove(1);
				assertListContains(original, 2, 3);
			},
			(original, committedVersion) -> {
				assertListContains(this.underlyingData, 1, 2);
				assertListContains(committedVersion, 2, 3);
			}
		);
	}

	@Test
	void shouldMakeModificationsOnSameIndex() {
		this.tested.addAll(Arrays.asList(3, 4, 5, 6, 7, 8, 9, 10));
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.remove(1);
				original.add(5, 0);
				original.remove(2);
				original.remove(5);
				assertListContains(original, 1, 3, 5, 6, 0, 8, 9, 10);
			},
			(original, committedVersion) -> {
				assertListContains(this.underlyingData, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
				assertListContains(committedVersion, 1, 3, 5, 6, 0, 8, 9, 10);
			}
		);
	}

	@Test
	void shouldProcessClear() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.add(4);
				original.add(5);
				original.clear();
				assertListContains(original);
			},
			(original, committedVersion) -> {
				assertListContains(this.underlyingData, 1, 2);
				assertListContains(committedVersion);
			}
		);
	}

	@Test
	void shouldProcessClearAndThenAdd() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.clear();
				original.add(4);
				original.add(5);
				assertListContains(original, 4, 5);
			},
			(original, committedVersion) -> {
				assertListContains(this.underlyingData, 1, 2);
				assertListContains(committedVersion, 4, 5);
			}
		);
	}

	@Test
	void shouldCreateTransactionalCopyWithRemove() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.add(3);
				original.add(4);
				original.remove(1);
				original.remove(0);
				assertListContains(original, 3, 4);
			},
			(original, committedVersion) -> {
				assertListContains(this.underlyingData, 1, 2);
				assertListContains(committedVersion, 3, 4);
			}
		);
	}

	@Test
	void shouldReturnProperlyConstructedCopyOnMultipleModifyOperations() {
		this.tested.add(5);
		this.tested.add(2);
		this.tested.add(9);
		this.tested.add(0);
		this.tested.add(4);

		assertStateAfterCommit(
			this.tested,
			original -> {
				original.remove(5);
				original.add(7);
				original.remove(1);
				original.remove(Integer.valueOf(4));
				original.remove(4);

				assertListContains(original, 1, 5, 2, 9);
			},
			(original, committedVersion) -> {
				assertListContains(this.underlyingData, 1, 2, 5, 2, 9, 0, 4);
				assertListContains(committedVersion, 1, 5, 2, 9);
			}
		);
	}

	@Test
	void removalsShouldNotModifyOriginalState() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.remove(Integer.valueOf(2));
				original.add(3);

				assertListContains(original, 1, 3);
			},
			(original, committedVersion) -> {
				assertListContains(this.underlyingData, 1, 2);
				assertListContains(committedVersion, 1, 3);
			}
		);
	}

	@Test
	void removalsShouldFailWhenPrepended() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.add(0, 4);
				original.add(0, 3);
				original.remove(3);

				assertListContains(this.tested, 3, 4, 1);
			},
			(original, committedVersion) -> {
				assertListContains(this.underlyingData, 1, 2);
				assertListContains(committedVersion, 3, 4, 1);
			}
		);
	}

	@Test
	void shouldMergeRemovalsAndUpdatesAndInsertionsOnTransactionCommit() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.remove(0);
				original.remove(0);
				original.add(3);
				original.add(3);

				assertListContains(this.tested, 3, 3);
			},
			(original, committedVersion) -> {
				assertListContains(this.underlyingData, 1, 2);
				assertListContains(committedVersion, 3, 3);
			}
		);
	}

	@Test
	void shouldAddSeveralItemsOnASamePosition() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.add(0, 3);
				original.add(0, 4);

				assertListContains(this.tested, 4, 3, 1, 2);
			},
			(original, committedVersion) -> {
				assertListContains(this.underlyingData, 1, 2);
				assertListContains(committedVersion, 4, 3, 1, 2);
			}
		);
	}

	@Test
	void shouldInterpretIsEmptyCorrectly() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				assertFalse(original.isEmpty());

				original.add(3);
				assertFalse(original.isEmpty());

				original.remove(Integer.valueOf(1));
				assertFalse(original.isEmpty());

				original.remove(Integer.valueOf(3));
				assertFalse(original.isEmpty());

				original.remove(Integer.valueOf(2));
				assertTrue(original.isEmpty());

				original.add(4);
				assertFalse(original.isEmpty());

				original.remove(Integer.valueOf(4));
				assertTrue(original.isEmpty());
			},
			(original, committedVersion) -> {
				assertListContains(this.underlyingData, 1, 2);
				assertListContains(committedVersion);
			}
		);
	}

	@Test
	void shouldProduceValidValueCollection() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.add(3);
				original.remove(Integer.valueOf(2));

				final Set<Integer> result = new HashSet<>(this.tested);
				assertEquals(2, result.size());
				assertTrue(result.contains(1));
				assertTrue(result.contains(3));
			},
			(original, committedVersion) -> {
				assertListContains(this.underlyingData, 1, 2);
				assertListContains(committedVersion, 1, 3);
			}
		);
	}

	@Test
	void shouldNotModifyOriginalStateOnIteratorRemoval() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.add(3);

				final Iterator<Integer> it = original.iterator();
				final Integer first = it.next();
				final Integer second = it.next();
				it.remove();
				assertTrue(it.hasNext());
				final Integer third = it.next();

				assertListContains(this.tested, 1, 3);
			},
			(original, committedVersion) -> {
				assertListContains(this.underlyingData, 1, 2);
				assertListContains(committedVersion, 1, 3);
			}
		);
	}

	@Test
	void shouldMergeChangesInKeySetIterator() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.add(3);

				final Iterator<Integer> it = original.iterator();
				it.next();
				final Integer removed = it.next();
				it.remove();

				assertEquals(Integer.valueOf(2), removed);

				assertListContains(this.tested, 1, 3);
			},
			(original, committedVersion) -> {
				assertListContains(this.underlyingData, 1, 2);
				assertListContains(committedVersion, 1, 3);
			}
		);
	}

	@Test
	void shouldKeepIteratorContract() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.add(3);

				final List<Integer> result = new ArrayList<>(3);

				final Iterator<Integer> it = original.iterator();
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

				assertEquals(new HashSet<>(Arrays.asList(1, 2, 3)), new HashSet<>(result));

				try {
					it.next();
					fail("Exception expected!");
				} catch (NoSuchElementException ex) {
					//ok
				}
			},
			(original, committedVersion) -> {
				assertListContains(this.underlyingData, 1, 2);
				assertListContains(committedVersion, 1, 2, 3);
			}
		);
	}

	@Test
	void shouldKeepIteratorContractWhenItemsRemoved() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.add(3);
				original.remove(1);

				final List<Integer> result = new ArrayList<>(3);

				final Iterator<Integer> it = original.iterator();
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

				assertEquals(new HashSet<>(Arrays.asList(1, 3)), new HashSet<>(result));

				try {
					it.next();
					fail("Exception expected!");
				} catch (NoSuchElementException ex) {
					//ok
				}
			},
			(original, committedVersion) -> {
				assertListContains(this.underlyingData, 1, 2);
				assertListContains(committedVersion, 1, 3);
			}
		);
	}

	@Test
	void shouldKeepListIteratorContractWhenItemsRemoved() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.add(3);
				original.add(4);
				original.remove(1);
				original.remove(2);

				final ListIterator<Integer> it = original.listIterator();
				assertEquals(Integer.valueOf(1), it.next());
				assertEquals(Integer.valueOf(3), it.next());

				try {
					it.next();
					fail("Exception expected!");
				} catch (NoSuchElementException ex) {
					//ok
				}

				assertEquals(Integer.valueOf(3), it.previous());
				assertEquals(Integer.valueOf(1), it.previous());

				try {
					it.previous();
					fail("Exception expected!");
				} catch (NoSuchElementException ex) {
					//ok
				}

				assertListContains(original, 1, 3);
			},
			(original, committedVersion) -> {
				assertListContains(this.underlyingData, 1, 2);
				assertListContains(committedVersion, 1, 3);
			}
		);
	}

	@Test
	void shouldProperlyReturnLastItemWhenTwoRemovals() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.set(0, 99);
				original.add(3);
				original.add(4);
				original.add(5);
				original.set(2, 13);
				original.set(3, 14);

				final ListIterator<Integer> it = original.listIterator();
				assertEquals(Integer.valueOf(99), it.next());
				assertEquals(Integer.valueOf(2), it.next());
				assertEquals(Integer.valueOf(13), it.next());
				assertEquals(Integer.valueOf(14), it.next());
				assertEquals(Integer.valueOf(5), it.next());
				assertEquals(Integer.valueOf(5), it.previous());
				assertEquals(Integer.valueOf(14), it.previous());
				assertEquals(Integer.valueOf(13), it.previous());
				assertEquals(Integer.valueOf(2), it.previous());
				assertEquals(Integer.valueOf(99), it.previous());
			},
			(original, committedVersion) -> {
				assertListContains(this.underlyingData, 1, 2);
				assertListContains(committedVersion, 99, 2, 13, 14, 5);
			}
		);
	}

	@Test
	void verify() {
		final List<Integer> base = Arrays.asList(18,18,1,5,5,18,18,10,19,5,9,1,13,11,14);
		final List<Integer> verify = new ArrayList<>(base);
		this.tested = new TransactionalList<>(base);
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.add(4, 3);
				verify.add(4, 3);
				original.add(14, 10);
				verify.add(14, 10);
				original.remove(Integer.valueOf(1));
				verify.remove(Integer.valueOf(1));
				original.remove(Integer.valueOf(5));
				verify.remove(Integer.valueOf(5));

				assertListContains(original, verify.stream().mapToInt(it -> it).toArray());
			},
			(original, committedVersion) -> {
				assertListContains(committedVersion, verify.stream().mapToInt(it -> it).toArray());
			}
		);
	}

	@Test
	void verify2() {
		final List<Integer> base = Arrays.asList(0,23,26,12,21,30,9,36,0,3,21,1,22,22,19,7,27,25,22,8);
		final List<Integer> verify = new ArrayList<>(base);
		this.tested = new TransactionalList<>(base);
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.remove(1);
				verify.remove(1);
				original.add(14, 30);
				verify.add(14, 30);
				original.remove(Integer.valueOf(22));
				verify.remove(Integer.valueOf(22));
				original.remove(Integer.valueOf(7));
				verify.remove(Integer.valueOf(7));

				assertListContains(original, verify.stream().mapToInt(it -> it).toArray());
			},
			(original, committedVersion) -> {
				assertListContains(committedVersion, verify.stream().mapToInt(it -> it).toArray());
			}
		);
	}

	@Test
	void verify3() {
		final List<Integer> base = Arrays.asList(31,14,2,4,10,2,13,12,39,15,26,11,21,31);
		final List<Integer> verify = new ArrayList<>(base);
		this.tested = new TransactionalList<>(base);
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.add(4, 33);
				verify.add(4, 33);
				original.remove(Integer.valueOf(10));
				verify.remove(Integer.valueOf(10));

				assertListContains(original, verify.stream().mapToInt(it -> it).toArray());
			},
			(original, committedVersion) -> {
				assertListContains(committedVersion, verify.stream().mapToInt(it -> it).toArray());
			}
		);
	}

	@Test
	void verify4() {
		final List<Integer> base = Arrays.asList(25,32,3,17,21,9,34,29,13,6,4,3,15,38,0,28,13,22,10);
		final List<Integer> verify = new ArrayList<>(base);
		this.tested = new TransactionalList<>(base);
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.remove(5);
				verify.remove(5);
				original.add(15, 16);
				verify.add(15, 16);
				original.add(35);
				verify.add(35);
				original.remove(Integer.valueOf(6));
				verify.remove(Integer.valueOf(6));

				assertListContains(original, verify.stream().mapToInt(it -> it).toArray());
			},
			(original, committedVersion) -> {
				assertListContains(committedVersion, verify.stream().mapToInt(it -> it).toArray());
			}
		);
	}

	@Test
	void verify5() {
		final List<Integer> base = Arrays.asList(15,5,11,2,36,11,31,1,23,37,4,3,7,18,6,8,32,29,9);
		final List<Integer> verify = new ArrayList<>(base);
		this.tested = new TransactionalList<>(base);
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.add(12, 38);
				verify.add(12, 38);
				original.add(2, 23);
				verify.add(2, 23);
				original.remove(6);
				verify.remove(6);
				original.remove(Integer.valueOf(7));
				verify.remove(Integer.valueOf(7));

				assertListContains(original, verify.stream().mapToInt(it -> it).toArray());
			},
			(original, committedVersion) -> {
				assertListContains(committedVersion, verify.stream().mapToInt(it -> it).toArray());
			}
		);
	}

	@ParameterizedTest(name = "TransactionalList should survive generational randomized test applying modifications on it")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int initialCount = 20;
		final List<Integer> initialState = generateRandomInitialArray(new Random(input.randomSeed()), initialCount);

		runFor(
			input,
			10_000,
			new TestState(
				new StringBuilder(),
				initialState
			),
			(random, testState) -> {
				final List<Integer> referenceList = new ArrayList<>(testState.initialState);
				final TransactionalList<Integer> transactionalList = new TransactionalList<>(testState.initialState());

				final StringBuilder codeBuffer = testState.code();
				codeBuffer.append("\nSTART: ")
					.append(transactionalList.stream().map(Object::toString).collect(Collectors.joining(",")))
					.append("\n");

				assertStateAfterCommit(
					transactionalList,
					original -> {

						final int operationsInTransaction = random.nextInt(5);
						for (int i = 0; i < operationsInTransaction; i++) {
							final int length = transactionalList.size();
							assertEquals(referenceList.size(), length);
							final int operation = random.nextInt(3);
							if ((operation == 0 || length < 10) && length < 120) {
								if (random.nextBoolean()) {
									// insert new item at the end
									final Integer newRecId = random.nextInt(initialCount * 2);
									transactionalList.add(newRecId);
									referenceList.add(newRecId);
									codeBuffer.append("+").append(newRecId);
								} else if (length > 0) {
									// insert new item in the middle
									final int addIndex = random.nextInt(length - 1);
									final Integer newRecId = random.nextInt(initialCount * 2);
									transactionalList.add(addIndex, newRecId);
									referenceList.add(addIndex, newRecId);
									codeBuffer.append("++(").append(addIndex).append(")").append(newRecId);
								}
							} else if (operation == 1) {
								if (random.nextBoolean()) {
									// remove existing item by index
									final int removeIndex = random.nextInt(length);
									codeBuffer.append("-").append(removeIndex);
									transactionalList.remove(removeIndex);
									referenceList.remove(removeIndex);
								} else {
									// remove existing item by value
									final Integer removedRecId = transactionalList.get(random.nextInt(length));
									transactionalList.remove(removedRecId);
									referenceList.remove(removedRecId);
									codeBuffer.append("--").append(removedRecId);
								}
							} else {
								// remove existing item by index
								final int updateIndex = random.nextInt(length);
								final Integer updatedValue = random.nextInt(initialCount * 2);
								codeBuffer.append("!").append(updateIndex);
								transactionalList.set(updateIndex, updatedValue);
								referenceList.set(updateIndex, updatedValue);
							}
						}
						codeBuffer.append("\n");
					},
					(original, committed) -> {
						assertListContains(committed, referenceList.stream().mapToInt(it -> it).toArray());
					}
				);

				return new TestState(
					new StringBuilder(),
					referenceList
				);
			}
		);
	}

	private List<Integer> generateRandomInitialArray(Random rnd, int count) {
		final List<Integer> initialArray = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			final int recId = rnd.nextInt(count * 2);
			initialArray.add(recId);
		}
		return initialArray;
	}

	private void assertListContains(List<Integer> list, int... recordIds) {
		final String errorMessage = "\nExpected: " + Arrays.toString(recordIds) +
			"\nActual:   [" + list.stream().map(Object::toString).collect(Collectors.joining(", ")) + "]";

		assertEquals(recordIds.length, list.size(), errorMessage);
		assertArrayEquals(
			Arrays.stream(recordIds).boxed().toArray(Integer[]::new),
			list.toArray(new Integer[0]),
			errorMessage
		);

		if (recordIds.length == 0) {
			assertTrue(list.isEmpty(), errorMessage);
		} else {
			assertFalse(list.isEmpty(), errorMessage);
		}

		for (int i = 0; i < recordIds.length; i++) {
			final int recordId = recordIds[i];
			assertEquals(Integer.valueOf(recordId), list.get(i), errorMessage);
		}

		for (int recordId : recordIds) {
			assertTrue(list.contains(recordId), errorMessage);
		}

		int index = 0;
		final ListIterator<Integer> it = list.listIterator();
		while (it.hasNext()) {
			final Integer nextRecord = it.next();
			assertEquals(recordIds[index++], nextRecord, errorMessage);
		}
		assertEquals(recordIds.length, index, errorMessage);

		while (it.hasPrevious()) {
			final Integer prevRecord = it.previous();
			assertEquals(recordIds[--index], prevRecord, errorMessage);
		}
		assertEquals(0, index, errorMessage);
	}

	private record TestState(
		StringBuilder code,
		List<Integer> initialState
	) {}

}
