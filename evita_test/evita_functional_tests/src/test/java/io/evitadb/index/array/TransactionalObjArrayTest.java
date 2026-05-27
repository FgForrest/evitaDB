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
import io.evitadb.api.requestResponse.data.ContentComparator;
import io.evitadb.utils.ArrayUtils;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies transactional contract of {@link TransactionalObjArray<Integer>}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class TransactionalObjArrayTest implements TimeBoundedTestSupport {

	@Test
	void shouldCorrectlyAddItemsOnFirstLastAndMiddlePositionsAndRollback() {
		final TransactionalObjArray<Integer> array = new TransactionalObjArray<>(new Integer[]{1, 5, 10}, Comparator.naturalOrder());

		assertStateAfterRollback(
			array,
			original -> {
				original.addAll(new Integer[] {11, 0 , 6});
				assertTransactionalArrayIs(new Integer[]{0, 1, 5, 6, 10, 11}, array);
			},
			(original, committed) -> {
				assertNull(committed);
				assertTransactionalArrayIs(new Integer[] {1,5,10}, original);
			}
		);
	}

	@Test
	void shouldCorrectlyRemoveItemsOnFirstLastAndMiddlePositionsAndRollback() {
		final TransactionalObjArray<Integer> array = new TransactionalObjArray<>(new Integer[]{1, 5, 10}, Comparator.naturalOrder());

		assertStateAfterRollback(
			array,
			original -> {
				original.remove(5);
				assertTransactionalArrayIs(new Integer[] {1,10}, original);
			},
			(original, committed) -> {
				assertNull(committed);
				assertTransactionalArrayIs(new Integer[] {1,5,10}, original);
			}
		);

	}

	@Test
	void shouldCorrectlyAddItemsOnFirstLastAndMiddlePositionsAndCommit() {
		final TransactionalObjArray<Integer> array = new TransactionalObjArray<>(new Integer[]{1, 5, 10}, Comparator.naturalOrder());

		assertStateAfterCommit(
			array,
			original -> {
				original.add(11);
				original.addAll(new Integer[] {11, 0, 6});

				assertTransactionalArrayIs(new Integer[]{0, 1, 5, 6, 10, 11}, array);
				assertFalse(array.contains(2));
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new Integer[] {1, 5, 10}, original);
				assertArrayEquals(new Integer[]{0, 1, 5, 6, 10, 11}, committed);
			}
		);
	}

	@Test
	void shouldCorrectlyRemoveItemsFromFirstLastAndMiddlePositionsAndCommit() {
		final TransactionalObjArray<Integer> array = new TransactionalObjArray<>(new Integer[]{1, 2, 5, 6, 10, 11}, Comparator.naturalOrder());

		assertStateAfterCommit(
			array,
			original -> {
				original.removeAll(new Integer[] {11, 1, 5});
				assertTransactionalArrayIs(new Integer[] {2, 6, 10}, array);
				assertFalse(array.contains(11));
				assertFalse(array.contains(1));
				assertFalse(array.contains(5));
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new Integer[] {1, 2, 5, 6, 10, 11}, original);
				assertArrayEquals(new Integer[] {2, 6, 10}, committed);
			}
		);
	}

	@Test
	void shouldCorrectlyRemoveMultipleItemsInARowAndCommit() {
		final TransactionalObjArray<Integer> array = new TransactionalObjArray<>(new Integer[]{1, 2, 5, 6, 10, 11}, Comparator.naturalOrder());

		assertStateAfterCommit(
			array,
			original -> {
				original.removeAll(new Integer[] {6, 5, 2});

				assertTransactionalArrayIs(new Integer[] {1, 10, 11}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new Integer[] {1, 2, 5, 6, 10, 11}, original);
				assertArrayEquals(new Integer[] {1, 10, 11}, committed);
			}
		);
	}

	@Test
	void shouldCorrectlyRemoveMultipleItemsInARowTillTheEndAndCommit() {
		final TransactionalObjArray<Integer> array = new TransactionalObjArray<>(new Integer[]{1, 2, 5, 6, 10, 11}, Comparator.naturalOrder());

		assertStateAfterCommit(
			array,
			original -> {
				original.remove(6);
				original.removeAll(new Integer[] {6, 5, 10, 11});

				assertTransactionalArrayIs(new Integer[] {1, 2}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new Integer[] {1, 2, 5, 6, 10, 11}, original);
				assertArrayEquals(new Integer[] {1, 2}, committed);
			}
		);

	}

	@Test
	void shouldCorrectlyRemoveMultipleItemsInARowFromTheBeginningAndCommit() {
		final TransactionalObjArray<Integer> array = new TransactionalObjArray<>(new Integer[]{1, 2, 5, 6, 10, 11}, Comparator.naturalOrder());

		assertStateAfterCommit(
			array,
			original -> {
				original.removeAll(new Integer[] {2, 6, 5, 1, 10});

				assertTransactionalArrayIs(new Integer[] {11}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new Integer[] {1, 2, 5, 6, 10, 11}, original);
				assertArrayEquals(new Integer[] {11}, committed);
			}
		);
	}

	@Test
	void shouldAddNothingAndCommit() {
		final TransactionalObjArray<Integer> array = new TransactionalObjArray<>(new Integer[]{1, 5, 10}, Comparator.naturalOrder());

		assertStateAfterCommit(
			array,
			original -> {
				assertTransactionalArrayIs(new Integer[] {1,5,10}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new Integer[] {1,5,10}, array);
				assertArrayEquals(new Integer[] {1,5,10}, committed);
			}
		);
	}

	@Test
	void shouldAddAndRemoveEverythingAndCommit() {
		final TransactionalObjArray<Integer> array = new TransactionalObjArray<>(new Integer[0], Comparator.naturalOrder());

		assertStateAfterCommit(
			array,
			original -> {
				original.add(1);
				original.add(5);
				original.remove(1);
				original.remove(5);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new Integer[0], array);
				assertArrayEquals(new Integer[0], committed);
			}
		);
	}

	@Test
	void shouldCorrectlyAddMultipleItemsOnSamePositionsAndCommit() {
		final TransactionalObjArray<Integer> array = new TransactionalObjArray<>(new Integer[]{1, 5, 10}, Comparator.naturalOrder());

		assertStateAfterCommit(
			array,
			original -> {
				original.addAll(new Integer[] {11, 6, 0, 3, 7, 12, 2, 8});
				original.add(3);

				assertTransactionalArrayIs(new Integer[] {0,1,2,3,5,6,7,8,10,11,12}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new Integer[] {1, 5, 10}, original);
				assertArrayEquals(new Integer[] {0,1,2,3,5,6,7,8,10,11,12}, committed);
			}
		);
	}

	@Test
	void shouldCorrectlyAddAndRemoveOnNonOverlappingPositionsAndCommit() {
		final TransactionalObjArray<Integer> array = new TransactionalObjArray<>(new Integer[]{1, 2, 5, 6, 10, 11}, Comparator.naturalOrder());

		assertStateAfterCommit(
			array,
			original -> {
				original.add(4);
				original.add(3);
				original.remove(10);
				original.remove(6);
				original.add(15);

				assertTransactionalArrayIs(new Integer[] {1, 2, 3, 4, 5, 11, 15}, original);
				assertFalse(array.contains(10));
				assertFalse(array.contains(6));
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new Integer[] {1, 2, 5, 6, 10, 11}, original);
				assertArrayEquals(new Integer[] {1, 2, 3, 4, 5, 11, 15}, committed);
			}
		);

	}

	@Test
	void shouldCorrectlyAddAndRemoveSameNumberAndCommit() {
		final TransactionalObjArray<Integer> array = new TransactionalObjArray<>(new Integer[]{1, 2, 5, 6, 10, 11}, Comparator.naturalOrder());

		assertStateAfterCommit(
			array,
			original -> {
				original.add(4);
				original.remove(4);

				assertTransactionalArrayIs(new Integer[] {1, 2, 5, 6, 10, 11}, original);
				assertFalse(array.contains(4));
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new Integer[] {1, 2, 5, 6, 10, 11}, original);
				assertArrayEquals(new Integer[] {1, 2, 5, 6, 10, 11}, committed);
			}
		);
	}

	@Test
	void shouldCorrectlyRemoveAndAddSameNumberAndCommit() {
		final TransactionalObjArray<Integer> array = new TransactionalObjArray<>(new Integer[]{1, 2, 5, 6, 10, 11}, Comparator.naturalOrder());

		assertStateAfterCommit(
			array,
			original -> {
				original.remove(5);
				original.remove(10);
				original.remove(11);
				original.add(10);
				original.add(11);
				original.add(5);

				assertTransactionalArrayIs(new Integer[] {1, 2, 5, 6, 10, 11}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new Integer[] {1, 2, 5, 6, 10, 11}, original);
				assertArrayEquals(new Integer[] {1, 2, 5, 6, 10, 11}, committed);
			}
		);

	}

	@Test
	void shouldCorrectlyAddAndRemoveOnOverlappingBoundaryPositionsAndCommit() {
		final TransactionalObjArray<Integer> array = new TransactionalObjArray<>(new Integer[]{1, 2, 5, 6, 10, 11}, Comparator.naturalOrder());

		assertStateAfterCommit(
			array,
			original -> {
				original.remove(1);
				original.remove(11);
				original.add(0);
				original.add(12);

				assertTransactionalArrayIs(new Integer[] {0, 2, 5, 6, 10, 12}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new Integer[] {1, 2, 5, 6, 10, 11}, original);
				assertArrayEquals(new Integer[] {0, 2, 5, 6, 10, 12}, committed);
			}
		);
	}

	@Test
	void shouldCorrectlyAddAndRemoveOnOverlappingMiddlePositionsAndCommit() {
		final TransactionalObjArray<Integer> array = new TransactionalObjArray<>(new Integer[]{1, 5, 8, 11}, Comparator.naturalOrder());

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

				assertTransactionalArrayIs(new Integer[] {1, 6, 7, 8, 9, 10, 11}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new Integer[] {1, 5, 8, 11}, original);
				assertArrayEquals(new Integer[] {1, 6, 7, 8, 9, 10, 11}, committed);
			}
		);
	}

	@Test
	void shouldProperlyHandleChangesOnSinglePosition() {
		final TransactionalObjArray<Integer> array = new TransactionalObjArray<>(new Integer[]{1, 2}, Comparator.naturalOrder());

		assertStateAfterCommit(
			array,
			original -> {
				original.remove(1);
				original.remove(2);
				original.add(2);
				original.add(4);
				original.remove(2);
				original.add(5);

				assertTransactionalArrayIs(new Integer[] {4, 5}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new Integer[] {1, 2}, original);
				assertArrayEquals(new Integer[] {4, 5}, committed);
			}
		);
	}

	@Test
	void shouldUseSpecificComparator() {
		final TransactionalObjArray<Integer> array = new TransactionalObjArray<>(new Integer[0], Comparator.reverseOrder());

		assertStateAfterCommit(
			array,
			original -> {
				original.add(5);
				original.add(1);
				original.add(10);
				assertTransactionalArrayIs(new Integer[] {10,5,1}, original);
			},
			(original, committed) -> {
				assertTransactionalArrayIs(new Integer[0], array);
				assertArrayEquals(new Integer[] {10,5,1}, committed);
			}
		);
	}

	@Test
	void shouldReplaceRecordWithSameIdentityButDifferentContentOnRemoveThenAdd() {
		// reproduces the stale-record bug in ObjArrayChanges#addRecordId: when a record is
		// removed and then re-added within the same transaction with the same comparator
		// identity but different content, the merged array must reflect the NEW content
		final Box oldA = new Box(1, "OLD-A");
		final Box oldB = new Box(2, "OLD-B");
		final Box newB = new Box(2, "NEW-B");
		final TransactionalObjArray<Box> array =
			new TransactionalObjArray<>(new Box[]{oldA, oldB}, Box.BY_ID);

		assertStateAfterCommit(
			array,
			original -> {
				original.remove(oldB);
				original.add(newB);
				final Box[] inTx = original.getArray();
				assertEquals(2, inTx.length);
				assertEquals(1, inTx[0].id());
				assertEquals(2, inTx[1].id());
				assertEquals("NEW-B", inTx[1].content(),
					"Transactional view must already reflect the replaced content");
			},
			(original, committed) -> {
				assertEquals(2, committed.length);
				assertEquals(1, committed[0].id());
				assertEquals(2, committed[1].id());
				assertEquals("OLD-A", committed[0].content());
				assertEquals("NEW-B", committed[1].content(),
					"Committed array must contain the NEW content, not the stale OLD one");
			}
		);
	}

	@Test
	void shouldReplaceFreshlyInsertedRecordWithSameIdentityButDifferentContent() {
		// symmetric scenario: record is first added on the change layer, then removed
		// and re-added with the same identity but new content – the add path must replace
		// the previously inserted (not yet committed) value
		final Box first = new Box(7, "FIRST");
		final Box second = new Box(7, "SECOND");
		final TransactionalObjArray<Box> array =
			new TransactionalObjArray<>(new Box[0], Box.BY_ID);

		assertStateAfterCommit(
			array,
			original -> {
				original.add(first);
				original.remove(first);
				original.add(second);
			},
			(original, committed) -> {
				assertEquals(1, committed.length);
				assertEquals(7, committed[0].id());
				assertEquals("SECOND", committed[0].content(),
					"Committed insertion bucket must hold the latest object instance, not the first one");
			}
		);
	}

	@Test
	void shouldRemoveSubstitutedRecordWithinSameTransaction() {
		// after a content-substitution (remove old + add new, same identity), removing the new record
		// again in the same transaction must leave the array WITHOUT that record - the substitution
		// encoding (removed delegate slot + insertion bucket) must not resurrect it
		final Box oldA = new Box(1, "OLD-A");
		final Box oldB = new Box(2, "OLD-B");
		final Box newB = new Box(2, "NEW-B");
		final TransactionalObjArray<Box> array =
			new TransactionalObjArray<>(new Box[]{oldA, oldB}, Box.BY_ID);

		assertStateAfterCommit(
			array,
			original -> {
				original.remove(oldB);
				original.add(newB);
				// right after substitution the record must be reported present
				assertTrue(original.contains(newB), "Substituted record must be visible via contains()");
				// now remove it again - it must disappear entirely
				original.remove(newB);
				assertFalse(original.contains(newB), "Removed substituted record must not be present");
				assertArrayEquals(new Box[]{oldA}, original.getArray());
			},
			(original, committed) -> assertArrayEquals(new Box[]{oldA}, committed)
		);
	}

	@Test
	void shouldKeepLatestContentWhenSubstitutedRecordIsReplacedAgain() {
		// substitute (remove old + add new), then add an even newer instance with the same identity -
		// the committed array must carry the LATEST content
		final Box oldA = new Box(1, "OLD-A");
		final Box v1 = new Box(1, "V1");
		final Box v2 = new Box(1, "V2");
		final TransactionalObjArray<Box> array =
			new TransactionalObjArray<>(new Box[]{oldA}, Box.BY_ID);

		assertStateAfterCommit(
			array,
			original -> {
				original.remove(oldA);
				original.add(v1);
				original.add(v2);
			},
			(original, committed) -> {
				assertEquals(1, committed.length);
				assertEquals("V2", committed[0].content(),
					"Committed array must carry the latest substituted content");
			}
		);
	}

	@Test
	void shouldCorrectlyWipeAll() {
		final TransactionalObjArray<Integer> array = new TransactionalObjArray<>(new Integer[]{36, 59, 179}, Comparator.naturalOrder());

		assertStateAfterCommit(
			array,
			original -> {
				original.add(31);
				original.remove(31);
				original.addAll(new Integer[] {140, 115});
				original.removeAll(new Integer[] {179, 36, 140});
				original.add(58);
				original.removeAll(new Integer[] {58, 115, 59});
				original.addAll(new Integer[] {156, 141});
				original.remove(141);
				original.add(52);
				original.removeAll(new Integer[] {52, 156});

				assertTransactionalArrayIs(new Integer[0], array);
			},
			(original, committed) -> assertArrayEquals(new Integer[0], committed)
		);
	}

	@ParameterizedTest(name = "TransactionalIntArray should survive generational randomized test applying modifications on it")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final int initialCount = 100;
		final Integer[] initialState = generateRandomInitialArray(new Random(input.randomSeed()), initialCount);

		runFor(
			input,
			10_000,
			new TestState(initialState),
			(random, testState) -> {
				final TransactionalObjArray<Integer> transactionalArray = new TransactionalObjArray<>(testState.initialArray(), Comparator.naturalOrder());
				final AtomicReference<Integer[]> nextArrayToCompare = new AtomicReference<>(testState.initialArray());

				assertStateAfterCommit(
					transactionalArray,
					original -> {
						final int operationsInTransaction = random.nextInt(100);
						for (int i = 0; i < operationsInTransaction; i++) {
							final int length = transactionalArray.getLength();
							if (random.nextBoolean() || length < 10) {
								// insert new item
								final int newRecId = random.nextInt(initialCount * 2);
								transactionalArray.add(newRecId);
								nextArrayToCompare.set(ArrayUtils.insertRecordIntoOrderedArray(newRecId, nextArrayToCompare.get()));
							} else {
								// remove existing item
								final int removedRecId = transactionalArray.get(random.nextInt(length));
								transactionalArray.remove(removedRecId);
								nextArrayToCompare.set(ArrayUtils.removeRecordFromOrderedArray(removedRecId, nextArrayToCompare.get()));
							}
						}
					},
					(original, committed) -> {
						assertArrayEquals(committed, nextArrayToCompare.get());
					}
				);

				return new TestState(
					nextArrayToCompare.get()
				);
			}
		);
	}

	private Integer[] generateRandomInitialArray(Random rnd, int count) {
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

	private static void assertTransactionalArrayIs(Integer[] expectedResult, TransactionalObjArray<Integer> array) {
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

		final Iterator<Integer> it = array.iterator();
		int index = -1;
		while (it.hasNext()) {
			final int nextInt = it.next();
			assertTrue(expectedResult.length > index + 1);
			assertEquals(expectedResult[++index], nextInt);
		}
		assertEquals(
			expectedResult.length, index + 1,
			"There are more expected objects than array produced by iterator!"
		);
	}

	private record TestState(
		Integer[] initialArray
	) {
	}

	/**
	 * Identity-based record that mimics PriceRecordContract: equals/hashCode/compareTo
	 * key only on {@link #id()} while {@link #content()} is logical payload that may
	 * change without changing identity.
	 */
	private record Box(int id, @Nonnull String content) implements ContentComparator<Box> {
		static final Comparator<Box> BY_ID = Comparator.comparingInt(Box::id);

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof Box other)) return false;
			return this.id == other.id;
		}

		@Override
		public int hashCode() {
			return Integer.hashCode(this.id);
		}

		@Override
		public boolean differsFrom(@Nullable Box other) {
			if (other == this) return false;
			if (other == null) return true;
			return this.id != other.id || !this.content.equals(other.content);
		}
	}

}
