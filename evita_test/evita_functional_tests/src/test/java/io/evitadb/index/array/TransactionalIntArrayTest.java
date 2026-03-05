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

import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator.OfInt;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies behaviour of {@link TransactionalIntArray} covering construction,
 * non-transactional operations, transactional commit and rollback semantics,
 * the {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} contract,
 * `addReturningIndex`, streaming, and edge cases.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@DisplayName("Transactional int array")
class TransactionalIntArrayTest implements TimeBoundedTestSupport {

	/**
	 * Asserts that the given {@link TransactionalIntArray} matches the expected int array
	 * by verifying emptiness, containment, raw array equality, length, and iterator output.
	 *
	 * @param expectedResult the expected sorted int array
	 * @param array          the transactional array to verify
	 */
	private static void assertTransactionalArrayIs(
		@Nonnull int[] expectedResult,
		@Nonnull TransactionalIntArray array
	) {
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

	/**
	 * Tests for {@link TransactionalIntArray} constructors verifying correct initial state.
	 */
	@Nested
	@DisplayName("Constructor")
	class ConstructorTest {

		@Test
		@DisplayName("creates empty array with no-arg constructor")
		void shouldCreateEmptyArrayWithNoArgConstructor() {
			final TransactionalIntArray array = new TransactionalIntArray();

			assertTrue(array.isEmpty());
			assertEquals(0, array.getLength());
			assertArrayEquals(new int[0], array.getArray());
		}

		@Test
		@DisplayName("preserves initial values from constructor argument")
		void shouldPreserveInitialValues() {
			final int[] initial = new int[]{1, 5, 10};
			final TransactionalIntArray array = new TransactionalIntArray(initial);

			assertTransactionalArrayIs(new int[]{1, 5, 10}, array);
		}

		@Test
		@DisplayName("returns bracket notation from toString()")
		void shouldReturnBracketNotationFromToString() {
			final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 5, 10});

			assertEquals("[1, 5, 10]", array.toString());
		}

	}

	/**
	 * Tests verifying that operations on {@link TransactionalIntArray} work correctly
	 * when no transaction is active (the `layer == null` branches).
	 */
	@Nested
	@DisplayName("Non-transactional operations")
	class NonTransactionalOperationsTest {

		@Test
		@DisplayName("add() mutates delegate directly without transaction")
		void shouldAddWithoutTransaction() {
			final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 5, 10});

			array.add(3);

			assertTransactionalArrayIs(new int[]{1, 3, 5, 10}, array);
		}

		@Test
		@DisplayName("remove() mutates delegate directly without transaction")
		void shouldRemoveWithoutTransaction() {
			final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 5, 10});

			array.remove(5);

			assertTransactionalArrayIs(new int[]{1, 10}, array);
		}

		@Test
		@DisplayName("addAll() inserts multiple items without transaction")
		void shouldAddAllWithoutTransaction() {
			final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 10});

			array.addAll(new int[]{5, 3, 15});

			assertTransactionalArrayIs(new int[]{1, 3, 5, 10, 15}, array);
		}

		@Test
		@DisplayName("removeAll() removes multiple items without transaction")
		void shouldRemoveAllWithoutTransaction() {
			final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 3, 5, 10, 15});

			array.removeAll(new int[]{3, 10, 15});

			assertTransactionalArrayIs(new int[]{1, 5}, array);
		}

		@Test
		@DisplayName("indexOf() returns correct position without transaction")
		void shouldReturnIndexOfWithoutTransaction() {
			final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 5, 10});

			assertEquals(0, array.indexOf(1));
			assertEquals(1, array.indexOf(5));
			assertEquals(2, array.indexOf(10));
			// non-existent element returns negative value
			assertTrue(array.indexOf(7) < 0);
		}

		@Test
		@DisplayName("get() returns correct element at index without transaction")
		void shouldGetElementAtIndex() {
			final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 5, 10});

			assertEquals(1, array.get(0));
			assertEquals(5, array.get(1));
			assertEquals(10, array.get(2));
		}

		@Test
		@DisplayName("contains() returns correct result without transaction")
		void shouldReturnContainsCorrectly() {
			final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 5, 10});

			assertTrue(array.contains(1));
			assertTrue(array.contains(5));
			assertTrue(array.contains(10));
			assertFalse(array.contains(2));
			assertFalse(array.contains(0));
			assertFalse(array.contains(11));
		}

	}

	/**
	 * Tests verifying the
	 * {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} interface
	 * methods on {@link TransactionalIntArray}.
	 */
	@Nested
	@DisplayName("TransactionalLayerProducer contract")
	class TransactionalLayerProducerContractTest {

		@Test
		@DisplayName("createLayer() returns null when no transaction is available")
		void shouldReturnNullLayerWhenNoTransactionAvailable() {
			final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 5, 10});

			// outside a transaction, createLayer() should return null
			final IntArrayChanges layer = array.createLayer();

			assertNull(layer);
		}

		@Test
		@DisplayName("returns delegate array when layer is null")
		void shouldReturnDelegateWhenLayerIsNull() {
			final TransactionalLayerMaintainer maintainer =
				Mockito.mock(TransactionalLayerMaintainer.class);
			final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 5, 10});

			final int[] result = array.createCopyWithMergedTransactionalMemory(null, maintainer);

			assertArrayEquals(new int[]{1, 5, 10}, result);
		}

		@Test
		@DisplayName("returns merged array when layer is present")
		void shouldReturnMergedArrayWhenLayerIsPresent() {
			final TransactionalLayerMaintainer maintainer =
				Mockito.mock(TransactionalLayerMaintainer.class);
			final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 5, 10});

			assertStateAfterCommit(
				array,
				original -> {
					original.add(3);
					original.add(7);

					assertTransactionalArrayIs(new int[]{1, 3, 5, 7, 10}, original);
				},
				(original, committed) -> {
					assertArrayEquals(new int[]{1, 3, 5, 7, 10}, committed);
					// original delegate unchanged
					assertTransactionalArrayIs(new int[]{1, 5, 10}, original);
				}
			);
		}

		@Test
		@DisplayName("assigns unique id to each instance")
		void shouldAssignUniqueId() {
			final TransactionalIntArray first = new TransactionalIntArray();
			final TransactionalIntArray second = new TransactionalIntArray();

			assertNotEquals(first.getId(), second.getId());
		}

	}

	/**
	 * Tests verifying the {@link TransactionalIntArray#addReturningIndex(int)} method
	 * which adds an item and returns the position where it was placed.
	 */
	@Nested
	@DisplayName("addReturningIndex()")
	class AddReturningIndexTest {

		@Test
		@DisplayName("returns correct index for newly inserted item without transaction")
		void shouldReturnCorrectIndexForNewItem() {
			final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 5, 10});

			final int index = array.addReturningIndex(3);

			assertEquals(1, index);
			assertTransactionalArrayIs(new int[]{1, 3, 5, 10}, array);
		}

		@Test
		@DisplayName("returns existing index for duplicate item without transaction")
		void shouldReturnExistingIndexForDuplicate() {
			final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 5, 10});

			final int index = array.addReturningIndex(5);

			// item already exists at index 1
			assertEquals(1, index);
			// array is unchanged
			assertTransactionalArrayIs(new int[]{1, 5, 10}, array);
		}

		@Test
		@DisplayName("returns correct index within transaction")
		void shouldReturnCorrectIndexWithinTransaction() {
			final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 5, 10});

			assertStateAfterCommit(
				array,
				original -> {
					final int index = original.addReturningIndex(3);
					assertEquals(1, index);

					assertTransactionalArrayIs(new int[]{1, 3, 5, 10}, original);
				},
				(original, committed) -> {
					assertArrayEquals(new int[]{1, 3, 5, 10}, committed);
					assertTransactionalArrayIs(new int[]{1, 5, 10}, original);
				}
			);
		}

	}

	/**
	 * Tests verifying the {@link TransactionalIntArray#stream()} method
	 * returns elements correctly.
	 */
	@Nested
	@DisplayName("Stream support")
	class StreamTest {

		@Test
		@DisplayName("returns all elements in order")
		void shouldReturnAllElementsInOrder() {
			final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 5, 10});

			final int[] result = array.stream().toArray();

			assertArrayEquals(new int[]{1, 5, 10}, result);
		}

		@Test
		@DisplayName("reflects transactional state in stream output")
		void shouldReflectTransactionalState() {
			final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 5, 10});

			assertStateAfterCommit(
				array,
				original -> {
					original.add(3);
					original.remove(5);

					final int[] result = original.stream().toArray();
					assertArrayEquals(new int[]{1, 3, 10}, result);
				},
				(original, committed) -> {
					assertArrayEquals(new int[]{1, 3, 10}, committed);
				}
			);
		}

	}

	/**
	 * Tests verifying edge cases and boundary conditions of {@link TransactionalIntArray}.
	 */
	@Nested
	@DisplayName("Edge cases")
	class EdgeCaseTest {

		@Test
		@DisplayName("handles operations on empty array")
		void shouldHandleOperationsOnEmptyArray() {
			final TransactionalIntArray array = new TransactionalIntArray();

			assertTrue(array.isEmpty());
			assertEquals(0, array.getLength());
			assertFalse(array.contains(1));
			assertTrue(array.indexOf(1) < 0);
			assertArrayEquals(new int[0], array.stream().toArray());

			// add to empty
			array.add(5);
			assertTransactionalArrayIs(new int[]{5}, array);
		}

		@Test
		@DisplayName("iterator throws NoSuchElementException when exhausted")
		void shouldThrowWhenIteratorExhausted() {
			final TransactionalIntArray array = new TransactionalIntArray(new int[]{1});

			final OfInt it = array.iterator();
			assertTrue(it.hasNext());
			assertEquals(1, it.nextInt());
			assertFalse(it.hasNext());

			assertThrows(NoSuchElementException.class, it::nextInt);
		}

	}

	/**
	 * Tests verifying that transactional changes are correctly committed,
	 * producing the expected new state while the original remains unchanged.
	 */
	@Nested
	@DisplayName("Transactional commit")
	class TransactionalCommitTest {

		@Test
		@DisplayName("adds items on first, last, and middle positions")
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
		@DisplayName("removes items from first, last, and middle positions")
		void shouldCorrectlyRemoveItemsFromFirstLastAndMiddlePositionsAndCommit() {
			final TransactionalIntArray array =
				new TransactionalIntArray(new int[]{1, 2, 5, 6, 10, 11});

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
		@DisplayName("removes multiple consecutive items in a row")
		void shouldCorrectlyRemoveMultipleItemsInARowAndCommit() {
			final TransactionalIntArray array =
				new TransactionalIntArray(new int[]{1, 2, 5, 6, 10, 11});

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
		@DisplayName("removes multiple consecutive items till the end")
		void shouldCorrectlyRemoveMultipleItemsInARowTillTheEndAndCommit() {
			final TransactionalIntArray array =
				new TransactionalIntArray(new int[]{1, 2, 5, 6, 10, 11});

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
		@DisplayName("removes multiple consecutive items from the beginning")
		void shouldCorrectlyRemoveMultipleItemsInARowFromTheBeginningAndCommit() {
			final TransactionalIntArray array =
				new TransactionalIntArray(new int[]{1, 2, 5, 6, 10, 11});

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
		@DisplayName("commits unchanged array when nothing added")
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
		@DisplayName("commits empty array after adding and removing everything")
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
		@DisplayName("adds multiple items on same positions correctly")
		void shouldCorrectlyAddMultipleItemsOnSamePositionsAndCommit() {
			final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 5, 10});

			assertStateAfterCommit(
				array,
				original -> {
					original.addAll(new int[]{11, 6, 0, 3, 7, 12, 2, 8});
					original.add(3);

					assertTransactionalArrayIs(
						new int[]{0, 1, 2, 3, 5, 6, 7, 8, 10, 11, 12}, original
					);
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{1, 5, 10}, original);
					assertArrayEquals(
						new int[]{0, 1, 2, 3, 5, 6, 7, 8, 10, 11, 12}, committed
					);
				}
			);
		}

		@Test
		@DisplayName("adds and removes on non-overlapping positions")
		void shouldCorrectlyAddAndRemoveOnNonOverlappingPositionsAndCommit() {
			final TransactionalIntArray array =
				new TransactionalIntArray(new int[]{1, 2, 5, 6, 10, 11});

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
		@DisplayName("add-then-remove same number produces no net change")
		void shouldCorrectlyAddAndRemoveSameNumberAndCommit() {
			final TransactionalIntArray array =
				new TransactionalIntArray(new int[]{1, 2, 5, 6, 10, 11});

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
		@DisplayName("remove-then-add same number produces no net change")
		void shouldCorrectlyRemoveAndAddSameNumberAndCommit() {
			final TransactionalIntArray array =
				new TransactionalIntArray(new int[]{1, 2, 5, 6, 10, 11});

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
		@DisplayName("adds and removes on overlapping boundary positions")
		void shouldCorrectlyAddAndRemoveOnOverlappingBoundaryPositionsAndCommit() {
			final TransactionalIntArray array =
				new TransactionalIntArray(new int[]{1, 2, 5, 6, 10, 11});

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
		@DisplayName("adds and removes on overlapping middle positions")
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
		@DisplayName("handles complex changes on single position")
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
		@DisplayName("wipes all elements through mixed add/remove operations")
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

	}

	/**
	 * Tests verifying that transactional changes are correctly discarded on rollback,
	 * leaving the original state untouched.
	 */
	@Nested
	@DisplayName("Transactional rollback")
	class TransactionalRollbackTest {

		@Test
		@DisplayName("rolls back added items on first, last, and middle positions")
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
		@DisplayName("rolls back removed items on first, last, and middle positions")
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

	}

	/**
	 * Generational randomized proof test that applies random add/remove modifications
	 * within transactions and verifies the committed state matches a reference JDK array.
	 */
	@Nested
	@DisplayName("Generational randomized proof")
	class GenerationalProofTest {

		@DisplayName("survives generational randomized test applying modifications")
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
					final TransactionalIntArray tested =
						new TransactionalIntArray(testState.initialArray());
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
									final int newRecId =
										rnd.nextInt(testState.initialCount() * 2);
									tested.add(newRecId);
									control.set(
										ArrayUtils.insertIntIntoOrderedArray(
											newRecId, control.get()
										)
									);
								} else {
									// remove existing item
									final int removedRecId = tested.get(rnd.nextInt(length));
									tested.remove(removedRecId);
									control.set(
										ArrayUtils.removeIntFromOrderedArray(
											removedRecId, control.get()
										)
									);
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

		/**
		 * Holds the state carried between generational test iterations.
		 */
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

}
