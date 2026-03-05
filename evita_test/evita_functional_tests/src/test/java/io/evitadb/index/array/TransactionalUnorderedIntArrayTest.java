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
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.apache.commons.lang3.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.ArrayUtils.insertIntIntoArrayOnIndex;
import static io.evitadb.utils.ArrayUtils.removeIntFromArrayOnIndex;
import static io.evitadb.utils.ArrayUtils.removeRangeFromArray;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies behaviour of {@link TransactionalUnorderedIntArray} covering
 * construction, non-transactional operations, transactional commit and rollback semantics,
 * the {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} contract,
 * range operations, and edge cases.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Transactional unordered int array")
class TransactionalUnorderedIntArrayTest implements TimeBoundedTestSupport {

	/**
	 * Tests for {@link TransactionalUnorderedIntArray} constructors verifying correct initial state.
	 */
	@Nested
	@DisplayName("Constructor")
	class ConstructorTest {

		@Test
		@DisplayName("creates empty array with no-arg constructor")
		void shouldCreateEmptyArray() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray();

			assertTrue(array.isEmpty());
			assertEquals(0, array.getLength());
			assertArrayEquals(new int[0], array.getArray());
		}

		@Test
		@DisplayName("preserves initial values from delegate array")
		void shouldCreateFromDelegateArray() {
			final int[] delegate = new int[]{7, 3, 5};
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(delegate);

			assertArrayEquals(new int[]{7, 3, 5}, array.getArray());
			assertEquals(3, array.getLength());
		}

		@Test
		@DisplayName("returns bracket notation from toString()")
		void shouldReturnStringRepresentation() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 3, 5});

			assertEquals("[7, 3, 5]", array.toString());
		}

	}

	/**
	 * Tests verifying that operations on {@link TransactionalUnorderedIntArray} work correctly
	 * when no transaction is active (the `layer == null` branches).
	 */
	@Nested
	@DisplayName("Non-transactional operations")
	class NonTransactionalOperationsTest {

		@Test
		@DisplayName("add() and get() mutate delegate directly without transaction")
		void shouldAddAndGetDirectlyWithoutTransaction() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 3, 5});

			array.add(3, 6);
			array.add(Integer.MIN_VALUE, 9);

			assertEquals(9, array.get(0));
			assertEquals(7, array.get(1));
			assertEquals(3, array.get(2));
			assertEquals(6, array.get(3));
			assertEquals(5, array.get(4));
		}

		@Test
		@DisplayName("remove() mutates delegate directly without transaction")
		void shouldRemoveDirectlyWithoutTransaction() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 3, 5});

			array.remove(3);

			assertArrayEquals(new int[]{7, 5}, array.getArray());
			assertEquals(2, array.getLength());
		}

		@Test
		@DisplayName("getSubArray() returns correct range without transaction")
		void shouldGetSubArrayWithoutTransaction() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 3, 5, 2, 9});

			final int[] sub = array.getSubArray(1, 4);

			assertArrayEquals(new int[]{3, 5, 2}, sub);
		}

		@Test
		@DisplayName("getPositions() and getRecordIds() return valid data without transaction")
		void shouldGetPositionsAndRecordIdsWithoutTransaction() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 3, 5});

			final int[] positions = array.getPositions();
			assertNotNull(positions);
			assertEquals(3, positions.length);

			final var recordIds = array.getRecordIds();
			assertNotNull(recordIds);
			// record ids bitmap should contain 3, 5, 7
			assertTrue(recordIds.contains(3));
			assertTrue(recordIds.contains(5));
			assertTrue(recordIds.contains(7));
		}

	}

	/**
	 * Tests verifying the
	 * {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer} interface
	 * methods on {@link TransactionalUnorderedIntArray}.
	 */
	@Nested
	@DisplayName("TransactionalLayerProducer contract")
	class TransactionalLayerProducerContractTest {

		@Test
		@DisplayName("createLayer() returns null when no transaction is available")
		void shouldReturnNullLayerOutsideTransaction() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 3, 5});

			final UnorderedIntArrayChanges layer = array.createLayer();

			assertNull(layer);
		}

		@Test
		@DisplayName("returns delegate array when merged with null layer")
		void shouldReturnMergedCopyWithNullLayer() {
			final TransactionalLayerMaintainer maintainer =
				Mockito.mock(TransactionalLayerMaintainer.class);
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 3, 5});

			final int[] result = array.createCopyWithMergedTransactionalMemory(null, maintainer);

			assertArrayEquals(new int[]{7, 3, 5}, result);
		}

		@Test
		@DisplayName("assigns unique id to each instance")
		void shouldHaveUniqueId() {
			final TransactionalUnorderedIntArray first = new TransactionalUnorderedIntArray();
			final TransactionalUnorderedIntArray second = new TransactionalUnorderedIntArray();

			assertNotEquals(first.getId(), second.getId());
		}

	}

	/**
	 * Tests verifying that transactional add operations produce correct results
	 * when committed.
	 */
	@Nested
	@DisplayName("Transactional add operations")
	class TransactionalAddTest {

		@Test
		@DisplayName("adds items on first, last, and middle positions")
		void shouldCorrectlyAddItemsOnFirstLastAndMiddlePositions() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 3, 5});
			array.add(3, 6);
			array.add(Integer.MIN_VALUE, 9);
			array.add(5, 8);
			assertTransactionalArrayIs(new int[]{9, 7, 3, 6, 5, 8}, array);
		}

		@Test
		@DisplayName("adds items on first, last, and middle positions and commits")
		void shouldCorrectlyAddItemsOnFirstLastAndMiddlePositionsAndCommit() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 3, 5});

			assertStateAfterCommit(
				array,
				original -> {
					original.add(3, 6);
					original.add(Integer.MIN_VALUE, 9);
					original.add(5, 8);
					assertTransactionalArrayIs(new int[]{9, 7, 3, 6, 5, 8}, array);
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{7, 3, 5}, array);
					assertArrayEquals(new int[]{9, 7, 3, 6, 5, 8}, committed);
				}
			);
		}

		@Test
		@DisplayName("adds items on first, last, and middle indexes")
		void shouldCorrectlyAddItemsOnFirstLastAndMiddleIndexes() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 3, 5});
			array.addOnIndex(2, 6);
			array.addOnIndex(0, 9);
			array.addOnIndex(5, 8);
			assertTransactionalArrayIs(new int[]{9, 7, 3, 6, 5, 8}, array);
		}

		@Test
		@DisplayName("adds items on first, last, and middle indexes and commits")
		void shouldCorrectlyAddItemsOnFirstLastAndMiddleIndexesAndCommit() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 3, 5});

			assertStateAfterCommit(
				array,
				original -> {
					original.addOnIndex(2, 6);
					original.addOnIndex(0, 9);
					original.addOnIndex(5, 8);
					assertTransactionalArrayIs(new int[]{9, 7, 3, 6, 5, 8}, array);
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{7, 3, 5}, array);
					assertArrayEquals(new int[]{9, 7, 3, 6, 5, 8}, committed);
				}
			);
		}

		@Test
		@DisplayName("adds multiple items on same positions")
		void shouldCorrectlyAddMultipleItemsOnSamePositions() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{2, 7, 3});

			array.addAll(Integer.MIN_VALUE, 0, 1);
			array.addAll(2, 4, 5, 6);
			assertTransactionalArrayIs(new int[]{0, 1, 2, 4, 5, 6, 7, 3}, array);
		}

		@Test
		@DisplayName("adds multiple items on same positions and commits")
		void shouldCorrectlyAddMultipleItemsOnSamePositionsAndCommit() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{2, 7, 3});

			assertStateAfterCommit(
				array,
				original -> {
					original.addAll(Integer.MIN_VALUE, 0, 1);
					original.addAll(2, 4, 5, 6);
					assertTransactionalArrayIs(new int[]{0, 1, 2, 4, 5, 6, 7, 3}, original);
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{2, 7, 3}, original);
					assertArrayEquals(new int[]{0, 1, 2, 4, 5, 6, 7, 3}, committed);
				}
			);
		}

		@Test
		@DisplayName("commits unchanged array when nothing added")
		void shouldAddNothingAndCommit() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 8, 1});

			assertStateAfterCommit(
				array,
				original -> {
					assertTransactionalArrayIs(new int[]{7, 8, 1}, original);
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{7, 8, 1}, array);
					assertArrayEquals(new int[]{7, 8, 1}, committed);
				}
			);
		}

		@Test
		@DisplayName("builds up added items sequentially")
		void shouldCorrectlyBuildUpAddedItems() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 3, 5});

			assertStateAfterCommit(
				array,
				original -> {
					original.add(3, 6);
					original.add(Integer.MIN_VALUE, 9);
					original.add(6, 8);
					original.add(6, 11);
					original.add(8, 12);
					original.add(9, 10);
					assertTransactionalArrayIs(new int[]{9, 10, 7, 3, 6, 11, 8, 12, 5}, original);
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{7, 3, 5}, original);
					assertArrayEquals(new int[]{9, 10, 7, 3, 6, 11, 8, 12, 5}, committed);
				}
			);
		}

		@Test
		@DisplayName("builds up added items on indexes sequentially")
		void shouldCorrectlyBuildUpAddedItemsOnIndexes() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 3, 5});

			assertStateAfterCommit(
				array,
				original -> {
					original.addOnIndex(2, 6);
					original.addOnIndex(0, 9);
					original.addOnIndex(4, 8);
					original.addOnIndex(4, 11);
					original.addOnIndex(6, 12);
					original.addOnIndex(1, 10);
					assertTransactionalArrayIs(new int[]{9, 10, 7, 3, 6, 11, 8, 12, 5}, original);
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{7, 3, 5}, original);
					assertArrayEquals(new int[]{9, 10, 7, 3, 6, 11, 8, 12, 5}, committed);
				}
			);
		}

	}

	/**
	 * Tests verifying that transactional remove operations produce correct results
	 * when committed.
	 */
	@Nested
	@DisplayName("Transactional remove operations")
	class TransactionalRemoveTest {

		@Test
		@DisplayName("removes items from first, last, and middle positions")
		void shouldCorrectlyRemoveItemsFromFirstLastAndMiddlePositions() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{6, 8, 2, 4, 5});

			array.removeAll(4, 5, 6);
			assertTransactionalArrayIs(new int[]{8, 2}, array);
			assertFalse(array.contains(11));
			assertFalse(array.contains(1));
			assertFalse(array.contains(5));
		}

		@Test
		@DisplayName("removes items from first, last, and middle positions and commits")
		void shouldCorrectlyRemoveItemsFromFirstLastAndMiddlePositionsAndCommit() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{6, 8, 2, 4, 5});

			assertStateAfterCommit(
				array,
				original -> {
					original.removeAll(4, 5, 6);
					assertTransactionalArrayIs(new int[]{8, 2}, original);
					assertFalse(original.contains(11));
					assertFalse(original.contains(1));
					assertFalse(original.contains(5));
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{6, 8, 2, 4, 5}, original);
					assertArrayEquals(new int[]{8, 2}, committed);
				}
			);
		}

		@Test
		@DisplayName("removes multiple consecutive items and commits")
		void shouldCorrectlyRemoveMultipleItemsInARowAndCommit() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 8, 2, 6, 5, 4});

			assertStateAfterCommit(
				array,
				original -> {
					original.removeAll(6, 5, 2);

					assertTransactionalArrayIs(new int[]{7, 8, 4}, original);
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{7, 8, 2, 6, 5, 4}, original);
					assertArrayEquals(new int[]{7, 8, 4}, committed);
				}
			);
		}

		@Test
		@DisplayName("removes multiple consecutive items till the end")
		void shouldCorrectlyRemoveMultipleItemsInARowTillTheEnd() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 2, 4, 9, 8, 3});
			array.removeAll(8, 9, 3, 4);

			assertTransactionalArrayIs(new int[]{7, 2}, array);
		}

		@Test
		@DisplayName("removes multiple consecutive items till the end and commits")
		void shouldCorrectlyRemoveMultipleItemsInARowTillTheEndAndCommit() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 2, 4, 9, 8, 3});

			assertStateAfterCommit(
				array,
				original -> {
					original.removeAll(8, 9, 3, 4);

					assertTransactionalArrayIs(new int[]{7, 2}, original);
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{7, 2, 4, 9, 8, 3}, original);
					assertArrayEquals(new int[]{7, 2}, committed);
				}
			);
		}

		@Test
		@DisplayName("removes multiple consecutive items from the beginning")
		void shouldCorrectlyRemoveMultipleItemsInARowFromTheBeginning() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 2, 4, 9, 8, 3});
			array.removeAll(9, 7, 2, 4);

			assertTransactionalArrayIs(new int[]{8, 3}, array);
		}

		@Test
		@DisplayName("removes multiple consecutive items from the beginning and commits")
		void shouldCorrectlyRemoveMultipleItemsInARowFromTheBeginningAndCommit() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 2, 4, 9, 8, 3});

			assertStateAfterCommit(
				array,
				original -> {
					original.removeAll(9, 7, 2, 4);

					assertTransactionalArrayIs(new int[]{8, 3}, original);
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{7, 2, 4, 9, 8, 3}, original);
					assertArrayEquals(new int[]{8, 3}, committed);
				}
			);
		}

	}

	/**
	 * Tests verifying mixed add/remove operations within a transaction.
	 */
	@Nested
	@DisplayName("Transactional mixed add/remove")
	class TransactionalMixedTest {

		@Test
		@DisplayName("handles reversible remove-then-add actions")
		void shouldDoReversibleRemoveAndAddActions() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 8, 1});

			array.remove(8);
			array.add(7, 8);
			array.remove(7);
			array.add(Integer.MIN_VALUE, 7);
			array.remove(1);
			array.add(8, 1);
			assertTransactionalArrayIs(new int[]{7, 8, 1}, array);
		}

		@Test
		@DisplayName("handles reversible remove-then-add actions and commits")
		void shouldDoReversibleRemoveAndAddActionsAndCommit() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 8, 1});

			assertStateAfterCommit(
				array,
				original -> {
					original.remove(8);
					original.add(7, 8);
					original.remove(7);
					original.add(Integer.MIN_VALUE, 7);
					original.remove(1);
					original.add(8, 1);
					assertTransactionalArrayIs(new int[]{7, 8, 1}, original);
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{7, 8, 1}, array);
					assertArrayEquals(new int[]{7, 8, 1}, committed);
				}
			);
		}

		@Test
		@DisplayName("handles reversible add-then-remove actions")
		void shouldDoReversibleAddAndRemoveActions() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 8, 1});

			array.add(7, 9);
			array.remove(9);
			array.add(Integer.MIN_VALUE, 6);
			array.remove(6);
			array.add(1, 6);
			array.remove(6);
			assertTransactionalArrayIs(new int[]{7, 8, 1}, array);
		}

		@Test
		@DisplayName("handles reversible add-then-remove actions and commits")
		void shouldDoReversibleAddAndRemoveActionsAndCommit() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 8, 1});

			assertStateAfterCommit(
				array,
				original -> {
					original.add(7, 9);
					original.remove(9);
					original.add(Integer.MIN_VALUE, 6);
					original.remove(6);
					original.add(1, 6);
					original.remove(6);
					assertTransactionalArrayIs(new int[]{7, 8, 1}, original);
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{7, 8, 1}, array);
					assertArrayEquals(new int[]{7, 8, 1}, committed);
				}
			);
		}

		@Test
		@DisplayName("adds and removes everything on empty array")
		void shouldAddAndRemoveEverything() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[0]);

			array.add(Integer.MIN_VALUE, 1);
			array.add(1, 5);
			array.remove(1);
			array.remove(5);

			assertTransactionalArrayIs(new int[0], array);
		}

		@Test
		@DisplayName("adds and removes everything on empty array and commits")
		void shouldAddAndRemoveEverythingAndCommit() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[0]);

			assertStateAfterCommit(
				array,
				original -> {
					original.add(Integer.MIN_VALUE, 1);
					original.add(1, 5);
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
		@DisplayName("adds and removes on non-overlapping positions")
		void shouldCorrectlyAddAndRemoveOnNonOverlappingPositions() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{5, 1, 6, 10, 2, 11});

			array.add(5, 4);
			array.add(6, 3);
			array.remove(10);
			array.remove(6);
			array.add(Integer.MIN_VALUE, 15);

			assertTransactionalArrayIs(new int[]{15, 5, 4, 1, 3, 2, 11}, array);
			assertFalse(array.contains(10));
			assertFalse(array.contains(6));
		}

		@Test
		@DisplayName("adds and removes on non-overlapping positions and commits")
		void shouldCorrectlyAddAndRemoveOnNonOverlappingPositionsAndCommit() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{5, 1, 6, 10, 2, 11});

			assertStateAfterCommit(
				array,
				original -> {
					original.add(5, 4);
					original.add(6, 3);
					original.remove(10);
					original.remove(6);
					original.add(Integer.MIN_VALUE, 15);

					assertTransactionalArrayIs(new int[]{15, 5, 4, 1, 3, 2, 11}, original);
					assertFalse(array.contains(10));
					assertFalse(array.contains(6));
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{5, 1, 6, 10, 2, 11}, original);
					assertArrayEquals(new int[]{15, 5, 4, 1, 3, 2, 11}, committed);
				}
			);
		}

		@Test
		@DisplayName("adds and removes on overlapping boundary positions")
		void shouldCorrectlyAddAndRemoveOnOverlappingBoundaryPositions() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{2, 1, 10, 6, 11, 5});

			array.remove(2);
			array.remove(5);
			array.add(11, 0);
			array.add(Integer.MIN_VALUE, 12);

			assertTransactionalArrayIs(new int[]{12, 1, 10, 6, 11, 0}, array);
		}

		@Test
		@DisplayName("adds and removes on overlapping boundary positions and commits")
		void shouldCorrectlyAddAndRemoveOnOverlappingBoundaryPositionsAndCommit() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{2, 1, 10, 6, 11, 5});

			assertStateAfterCommit(
				array,
				original -> {
					original.remove(2);
					original.remove(5);
					original.add(11, 0);
					original.add(Integer.MIN_VALUE, 12);

					assertTransactionalArrayIs(new int[]{12, 1, 10, 6, 11, 0}, original);
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{2, 1, 10, 6, 11, 5}, original);
					assertArrayEquals(new int[]{12, 1, 10, 6, 11, 0}, committed);
				}
			);
		}

		@Test
		@DisplayName("adds and removes on overlapping middle positions")
		void shouldCorrectlyAddAndRemoveOnOverlappingMiddlePositions() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{1, 5, 8, 11});

			array.remove(5);
			array.remove(8);
			array.add(1, 6);
			array.add(6, 7);
			array.add(7, 8);
			array.add(8, 9);
			array.add(9, 10);

			assertTransactionalArrayIs(new int[]{1, 6, 7, 8, 9, 10, 11}, array);
		}

		@Test
		@DisplayName("adds and removes on overlapping middle positions and commits")
		void shouldCorrectlyAddAndRemoveOnOverlappingMiddlePositionsAndCommit() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{1, 5, 8, 11});

			assertStateAfterCommit(
				array,
				original -> {
					original.remove(5);
					original.remove(8);
					original.add(1, 6);
					original.add(6, 7);
					original.add(7, 8);
					original.add(8, 9);
					original.add(9, 10);

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
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{1, 2});

			array.remove(1);
			array.remove(2);
			array.add(Integer.MIN_VALUE, 2);
			array.add(2, 4);
			array.remove(2);
			array.add(4, 5);

			assertTransactionalArrayIs(new int[]{4, 5}, array);
		}

		@Test
		@DisplayName("handles complex changes on single position and commits")
		void shouldProperlyHandleChangesOnSinglePositionAndCommit() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{1, 2});

			assertStateAfterCommit(
				array,
				original -> {
					original.remove(1);
					original.remove(2);
					original.add(Integer.MIN_VALUE, 2);
					original.add(2, 4);
					original.remove(2);
					original.add(4, 5);

					assertTransactionalArrayIs(new int[]{4, 5}, original);
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{1, 2}, original);
					assertArrayEquals(new int[]{4, 5}, committed);
				}
			);
		}

	}

	/**
	 * Tests verifying transactional read methods (get, getSubArray) inside transactions.
	 */
	@Nested
	@DisplayName("Transactional read methods")
	class TransactionalReadTest {

		@Test
		@DisplayName("get(index) returns correct element inside transaction")
		void shouldGetByIndexInsideTransaction() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 3, 5});

			assertStateAfterCommit(
				array,
				original -> {
					original.add(3, 6);
					original.add(Integer.MIN_VALUE, 9);

					assertEquals(9, original.get(0));
					assertEquals(7, original.get(1));
					assertEquals(3, original.get(2));
					assertEquals(6, original.get(3));
					assertEquals(5, original.get(4));
				},
				(original, committed) -> {
					assertArrayEquals(new int[]{9, 7, 3, 6, 5}, committed);
				}
			);
		}

		@Test
		@DisplayName("getSubArray() returns correct range inside transaction")
		void shouldGetSubArrayInsideTransaction() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 3, 5});

			assertStateAfterCommit(
				array,
				original -> {
					original.add(3, 6);
					original.add(Integer.MIN_VALUE, 9);

					final int[] sub = original.getSubArray(1, 4);
					assertArrayEquals(new int[]{7, 3, 6}, sub);
				},
				(original, committed) -> {
					assertArrayEquals(new int[]{9, 7, 3, 6, 5}, committed);
				}
			);
		}

	}

	/**
	 * Tests verifying range and append operations on {@link TransactionalUnorderedIntArray}.
	 */
	@Nested
	@DisplayName("Range operations")
	class RangeOperationsTest {

		@Test
		@DisplayName("removes range lesser than tail")
		void shouldRemoveRangeLesserThanTail() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{2, 4, 3, 1, 5});
			assertArrayEquals(new int[]{3, 1}, array.removeRange(2, 4));
			assertTransactionalArrayIs(new int[]{2, 4, 5}, array);
		}

		@Test
		@DisplayName("removes range lesser than tail and commits")
		void shouldRemoveRangeLesserThanTailAndCommit() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{2, 4, 3, 1, 5});

			assertStateAfterCommit(
				array,
				original -> {
					assertArrayEquals(new int[]{3, 1}, array.removeRange(2, 4));
					assertTransactionalArrayIs(new int[]{2, 4, 5}, original);
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{2, 4, 3, 1, 5}, original);
					assertArrayEquals(new int[]{2, 4, 5}, committed);
				}
			);
		}

		@Test
		@DisplayName("removes range higher than tail")
		void shouldRemoveRangeHigherThanTail() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{4, 3, 1, 5, 2});
			assertArrayEquals(new int[]{1, 5}, array.removeRange(2, 4));
			assertTransactionalArrayIs(new int[]{4, 3, 2}, array);
		}

		@Test
		@DisplayName("removes range higher than tail and commits")
		void shouldRemoveRangeHigherThanTailAndCommit() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{4, 3, 1, 5, 2});

			assertStateAfterCommit(
				array,
				original -> {
					assertArrayEquals(new int[]{1, 5}, original.removeRange(2, 4));
					assertTransactionalArrayIs(new int[]{4, 3, 2}, original);
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{4, 3, 1, 5, 2}, original);
					assertArrayEquals(new int[]{4, 3, 2}, committed);
				}
			);
		}

		@Test
		@DisplayName("removes tail range")
		void shouldRemoveTail() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{2, 4, 3, 1, 5});
			assertArrayEquals(new int[]{1, 5}, array.removeRange(3, 5));
			assertTransactionalArrayIs(new int[]{2, 4, 3}, array);
		}

		@Test
		@DisplayName("removes tail range and commits")
		void shouldRemoveTailAndCommit() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{2, 4, 3, 1, 5});

			assertStateAfterCommit(
				array,
				original -> {
					assertArrayEquals(new int[]{1, 5}, array.removeRange(3, 5));
					assertTransactionalArrayIs(new int[]{2, 4, 3}, original);
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{2, 4, 3, 1, 5}, original);
					assertArrayEquals(new int[]{2, 4, 3}, committed);
				}
			);
		}

		@Test
		@DisplayName("removes head range")
		void shouldRemoveHead() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{2, 4, 3, 1, 5});
			assertArrayEquals(new int[]{2, 4}, array.removeRange(0, 2));
			assertTransactionalArrayIs(new int[]{3, 1, 5}, array);
		}

		@Test
		@DisplayName("removes head range and commits")
		void shouldRemoveHeadAndCommit() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{2, 4, 3, 1, 5});

			assertStateAfterCommit(
				array,
				original -> {
					assertArrayEquals(new int[]{2, 4}, original.removeRange(0, 2));
					assertTransactionalArrayIs(new int[]{3, 1, 5}, original);
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{2, 4, 3, 1, 5}, original);
					assertArrayEquals(new int[]{3, 1, 5}, committed);
				}
			);
		}

		@Test
		@DisplayName("appends items at the tail")
		void shouldAppendTail() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{2, 4, 3, 1, 5});
			array.appendAll(8, 6);
			assertTransactionalArrayIs(new int[]{2, 4, 3, 1, 5, 8, 6}, array);
		}

		@Test
		@DisplayName("appends items at the tail and commits")
		void shouldAppendTailAndCommit() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{2, 4, 3, 1, 5});

			assertStateAfterCommit(
				array,
				original -> {
					original.appendAll(8, 6);
					assertTransactionalArrayIs(new int[]{2, 4, 3, 1, 5, 8, 6}, original);
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{2, 4, 3, 1, 5}, original);
					assertArrayEquals(new int[]{2, 4, 3, 1, 5, 8, 6}, committed);
				}
			);
		}

	}

	/**
	 * Tests verifying that transactional changes are correctly discarded on rollback,
	 * leaving the original state untouched.
	 */
	@Nested
	@DisplayName("Rollback")
	class RollbackTest {

		@Test
		@DisplayName("rolls back added items on first, last, and middle positions")
		void shouldCorrectlyAddItemsOnFirstLastAndMiddlePositionsAndRollback() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 3, 5});

			assertStateAfterRollback(
				array,
				original -> {
					original.add(3, 6);
					original.add(Integer.MIN_VALUE, 9);
					original.add(5, 8);
					assertTransactionalArrayIs(new int[]{9, 7, 3, 6, 5, 8}, array);
				},
				(original, committed) -> {
					assertNull(committed);
					assertTransactionalArrayIs(new int[]{7, 3, 5}, original);
				}
			);
		}

		@Test
		@DisplayName("rolls back added items on indexes")
		void shouldCorrectlyAddItemsOnFirstLastAndMiddleIndexesAndRollback() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 3, 5});

			assertStateAfterRollback(
				array,
				original -> {
					original.addOnIndex(2, 6);
					original.addOnIndex(0, 9);
					original.addOnIndex(5, 8);
					assertTransactionalArrayIs(new int[]{9, 7, 3, 6, 5, 8}, array);
				},
				(original, committed) -> {
					assertNull(committed);
					assertTransactionalArrayIs(new int[]{7, 3, 5}, original);
				}
			);
		}

		@Test
		@DisplayName("rolls back built-up added items")
		void shouldCorrectlyBuildUpAddedItemsAndRollback() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 3, 5});

			assertStateAfterRollback(
				array,
				original -> {
					original.add(3, 6);
					original.add(Integer.MIN_VALUE, 9);
					original.add(6, 8);
					original.add(6, 11);
					original.add(8, 12);
					original.add(9, 10);
					assertTransactionalArrayIs(new int[]{9, 10, 7, 3, 6, 11, 8, 12, 5}, original);
				},
				(original, committed) -> {
					assertNull(committed);
					assertTransactionalArrayIs(new int[]{7, 3, 5}, original);
				}
			);
		}

		@Test
		@DisplayName("rolls back built-up items added on indexes")
		void shouldCorrectlyBuildUpAddedItemsOnIndexesAndRollback() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 3, 5});

			assertStateAfterRollback(
				array,
				original -> {
					original.addOnIndex(2, 6);
					original.addOnIndex(0, 9);
					original.addOnIndex(4, 8);
					original.addOnIndex(4, 11);
					original.addOnIndex(6, 12);
					original.addOnIndex(1, 10);
					assertTransactionalArrayIs(new int[]{9, 10, 7, 3, 6, 11, 8, 12, 5}, original);
				},
				(original, committed) -> {
					assertNull(committed);
					assertTransactionalArrayIs(new int[]{7, 3, 5}, original);
				}
			);
		}

		@Test
		@DisplayName("rolls back removed items from first position")
		void shouldCorrectlyRemoveItemsOnFirstPositionAndRollback() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{7, 3, 5, 2});

			assertStateAfterRollback(
				array,
				original -> {
					original.remove(7);
					original.remove(3);
					original.remove(2);
					assertTransactionalArrayIs(new int[]{5}, original);
				},
				(original, committed) -> {
					assertNull(committed);
					assertTransactionalArrayIs(new int[]{7, 3, 5, 2}, original);
				}
			);
		}

	}

	/**
	 * Tests verifying edge cases and error handling of {@link TransactionalUnorderedIntArray}.
	 */
	@Nested
	@DisplayName("Edge cases")
	class EdgeCaseTest {

		@Test
		@DisplayName("throws when adding record after non-existing record")
		void shouldFailToAddRecordAfterNonExistingRecord() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{1, 2});

			assertStateAfterCommit(
				array,
				original -> {
					assertThrows(IllegalArgumentException.class, () -> array.add(3, 4));
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{1, 2}, original);
					assertArrayEquals(new int[]{1, 2}, committed);
				}
			);
		}

		@Test
		@DisplayName("throws when adding record after removed record")
		void shouldFailToAddRecordAfterRemovedRecord() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{1, 2});

			assertStateAfterCommit(
				array,
				original -> {
					array.remove(2);
					assertThrows(IllegalArgumentException.class, () -> array.add(2, 4));
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{1, 2}, original);
					assertArrayEquals(new int[]{1}, committed);
				}
			);
		}

	}

	/**
	 * Generational randomized proof tests that apply random add/remove modifications
	 * within transactions and verify the committed state matches a reference array.
	 */
	@Nested
	@DisplayName("Generational proof")
	class GenerationalProofTest {

		@Test
		@DisplayName("passes generational test scenario 1")
		void shouldPassGenerationalTest1() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{5, 4, 1, 19, 8, 17});

			assertStateAfterCommit(
				array,
				original -> {
					try {
						original.remove(5);
						original.add(4, 18);
						original.remove(8);
						original.remove(17);
						original.add(18, 14);
						original.add(14, 7);
						original.remove(7);
						original.add(19, 13);
						original.remove(1);
						original.add(19, 8);
						original.add(4, 7);
						original.add(13, 1);
						original.add(13, 17);
						original.remove(19);
					} catch (IllegalArgumentException ex) {
						// this is expected - previous record doesn't exist
					}
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{5, 4, 1, 19, 8, 17}, original);
					assertTransactionalArrayIs(new int[]{4, 7, 18, 14, 8, 13, 17, 1}, new TransactionalUnorderedIntArray(committed));
				}
			);
		}

		@Test
		@DisplayName("passes generational test scenario 2")
		void shouldPassGenerationalTest2() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{12, 19, 16, 5, 0, 11, 4, 13});

			assertStateAfterCommit(
				array,
				original -> {
					try {
						original.remove(19);
						original.add(5, 9);
						original.remove(9);
						original.add(11, 2);
						original.add(13, 14);
						original.add(0, 6);
						original.add(6, 18);
						original.remove(14);
						original.remove(11);
						original.remove(5);
						original.remove(4);
						original.remove(16);
						original.add(2, 8);
						original.add(2, 10);
						original.remove(10);
						original.add(12, 9);
						original.add(13, 1);
						original.add(13, 10);
						original.remove(18);
						original.add(0, 11);
					} catch (IllegalArgumentException ex) {
						// this is expected - previous record doesn't exist
					}
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{12, 19, 16, 5, 0, 11, 4, 13}, original);
					assertTransactionalArrayIs(new int[]{12, 9, 0, 11, 6, 2, 8, 13, 10, 1}, new TransactionalUnorderedIntArray(committed));
				}
			);
		}

		@Test
		@DisplayName("passes generational test scenario 3")
		void shouldPassGenerationalTest3() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{14, 16, 11, 18, 4, 10, 6, 3});

			assertStateAfterCommit(
				array,
				original -> {
					try {
						original.add(16, 12);
						original.add(3, 0);
						original.add(16, 7);
						original.remove(16);
						original.remove(3);
						original.add(6, 16);
						original.add(6, 5);
						original.remove(14);
						original.remove(12);
						original.add(5, 19);
						original.remove(5);
						original.add(7, 5);
						original.add(0, 9);
						original.add(6, 3);
					} catch (IllegalArgumentException ex) {
						// this is expected - previous record doesn't exist
					}
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{14, 16, 11, 18, 4, 10, 6, 3}, original);
					assertTransactionalArrayIs(new int[]{7, 5, 11, 18, 4, 10, 6, 3, 19, 16, 0, 9}, new TransactionalUnorderedIntArray(committed));
				}
			);
		}

		@Test
		@DisplayName("passes generational test scenario 4")
		void shouldPassGenerationalTest4() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{0, 6, 9, 2, 12, 11, 13, 7, 15, 10, 8, 5, 18, 1, 14});

			assertStateAfterCommit(
				array,
				original -> {
					try {
						original.remove(12);
						original.add(8, 19);
						original.remove(2);
						original.add(8, 3);
						original.remove(6);
						original.add(10, 16);
						original.remove(3);
						original.add(15, 3);
						original.remove(9);
						original.remove(1);
						original.remove(18);
						original.add(16, 9);
						original.remove(10);
						original.remove(8);
						original.add(15, 10);
					} catch (IllegalArgumentException ex) {
						// this is expected - previous record doesn't exist
					}
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{0, 6, 9, 2, 12, 11, 13, 7, 15, 10, 8, 5, 18, 1, 14}, original);
					assertTransactionalArrayIs(new int[]{0, 11, 13, 7, 15, 10, 3, 16, 9, 19, 5, 14}, new TransactionalUnorderedIntArray(committed));
				}
			);
		}

		@Test
		@DisplayName("passes generational test scenario 5")
		void shouldPassGenerationalTest5() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{6, 7, 5, 2, 4});

			assertStateAfterCommit(
				array,
				original -> {
					try {
						original.addOnIndex(4, 9);
						original.remove(5);
						original.addOnIndex(4, 0);
					} catch (IllegalArgumentException ex) {
						// this is expected - previous record doesn't exist
					}
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{6, 7, 5, 2, 4}, original);
					assertTransactionalArrayIs(new int[]{6, 7, 2, 9, 0, 4}, new TransactionalUnorderedIntArray(committed));
				}
			);
		}

		@Test
		@DisplayName("passes generational test scenario 6")
		void shouldPassGenerationalTest6() {
			final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[]{2, 3});

			assertStateAfterCommit(
				array,
				original -> {
					try {
						original.addOnIndex(1, 8);
						original.addOnIndex(1, 9);
						original.remove(2);
						original.addOnIndex(1, 0);
					} catch (IllegalArgumentException ex) {
						// this is expected - previous record doesn't exist
					}
				},
				(original, committed) -> {
					assertTransactionalArrayIs(new int[]{2, 3}, original);
					assertTransactionalArrayIs(new int[]{9, 0, 8, 3}, new TransactionalUnorderedIntArray(committed));
				}
			);
		}

		@DisplayName("survives generational randomized test applying modifications")
		@ParameterizedTest(name = "TransactionalIntArray should survive generational randomized test applying modifications on it")
		@Tag(LONG_RUNNING_TEST)
		@ArgumentsSource(TimeArgumentProvider.class)
		void generationalProofTest(GenerationalTestInput input) {
			final int initialCount = 100;
			final int[] initialState = generateRandomInitialArray(new Random(input.randomSeed()), initialCount);

			runFor(
				input,
				1_000,
				new TestState(
					new StringBuilder(256),
					initialState
				),
				(random, testState) -> {
					final StringBuilder codeBuffer = testState.code();
					codeBuffer.append("final TransactionalUnorderedIntArray array = new TransactionalUnorderedIntArray(new int[] {")
						.append(
							Arrays.stream(testState.initialArray())
								.mapToObj(Integer::toString)
								.collect(Collectors.joining(", "))
						)
						.append("});");
					final TransactionalUnorderedIntArray transactionalArray = new TransactionalUnorderedIntArray(testState.initialArray());
					final AtomicReference<int[]> nextArrayToCompare = new AtomicReference<>(testState.initialArray());

					assertStateAfterCommit(
						transactionalArray,
						original -> {
							final int operationsInTransaction = random.nextInt(100);
							for (int i = 0; i < operationsInTransaction; i++) {
								final int length = transactionalArray.getLength();
								final int[] comparedArray = nextArrayToCompare.get();
								final int randomIndex = random.nextInt(comparedArray.length);
								final int recOnRandomIndex = comparedArray[randomIndex];
								if ((random.nextBoolean() || length < initialCount * 0.3) && length < initialCount * 1.6) {
									if (length < initialCount * 0.5) {
										// append multiple at the end
										int[] newRecId = new int[random.nextInt(2) + 1];
										Set<Integer> assignedSet = new HashSet<>();
										for (int j = 0; j < newRecId.length; j++) {
											do {
												newRecId[j] = random.nextInt(initialCount << 1);
											} while (transactionalArray.contains(newRecId[j]) || assignedSet.contains(newRecId[j]));
											assignedSet.add(newRecId[j]);
										}

										try {
											codeBuffer.append("\noriginal.appendAll(").append(Arrays.stream(newRecId).mapToObj(String::valueOf).collect(Collectors.joining(", "))).append(");");
											nextArrayToCompare.set(io.evitadb.utils.ArrayUtils.mergeArrays(comparedArray, newRecId));
											transactionalArray.appendAll(newRecId);
										} catch (IllegalArgumentException ex) {
											assertTransactionalArrayIs(
												nextArrayToCompare.get(), transactionalArray,
												"\n Cannot insert " + Arrays.toString(newRecId) + " due to: " + ex.getMessage()
											);
											throw ex;
										}
									} else {
										// insert new item
										int newRecId;
										do {
											newRecId = random.nextInt(initialCount << 1);
										} while (transactionalArray.contains(newRecId));

										try {
											if (random.nextBoolean()) {
												codeBuffer.append("\noriginal.add(").append(recOnRandomIndex).append(", ").append(newRecId).append(");");
												nextArrayToCompare.set(insertIntIntoArrayOnIndex(newRecId, comparedArray, randomIndex + 1));
												transactionalArray.add(recOnRandomIndex, newRecId);
											} else {
												codeBuffer.append("\noriginal.addOnIndex(").append(randomIndex).append(", ").append(newRecId).append(");");
												nextArrayToCompare.set(insertIntIntoArrayOnIndex(newRecId, comparedArray, randomIndex));
												transactionalArray.addOnIndex(randomIndex, newRecId);
											}
										} catch (IllegalArgumentException ex) {
											assertTransactionalArrayIs(
												nextArrayToCompare.get(), transactionalArray,
												"\n Cannot insert " + newRecId + " due to: " + ex.getMessage()
											);
											throw ex;
										}
									}
								} else {
									if (length > initialCount * 1.4) {
										final int removedLength = random.nextInt(8) + 1;
										// remove range
										final int endIndex = Math.min(randomIndex + removedLength, length);
										codeBuffer.append("\noriginal.removeRange(").append(randomIndex).append(", ").append(endIndex).append(");");
										transactionalArray.removeRange(randomIndex, endIndex);
										nextArrayToCompare.set(removeRangeFromArray(comparedArray, randomIndex, endIndex));
									} else {
										// remove existing item
										codeBuffer.append("\noriginal.remove(").append(recOnRandomIndex).append(");");
										transactionalArray.remove(recOnRandomIndex);
										nextArrayToCompare.set(removeIntFromArrayOnIndex(comparedArray, randomIndex));
									}
								}

								try {
									transactionalArray.getArray();
								} catch (RuntimeException ex) {
									fail(ex.getMessage() + "\n\n" + codeBuffer);
								}
							}
						},
						(original, committed) -> {
							assertTransactionalArrayIs(
								nextArrayToCompare.get(),
								new TransactionalUnorderedIntArray(committed), "\nRecipe:\n\n" + codeBuffer
							);
						}
					);

					return new TestState(
						new StringBuilder(256), nextArrayToCompare.get()
					);
				}
			);
		}

	}

	/**
	 * Generates a random initial array of unique integers.
	 *
	 * @param rnd   the random generator to use
	 * @param count the number of elements to generate
	 * @return an array of unique random integers
	 */
	private static int[] generateRandomInitialArray(Random rnd, int count) {
		final Set<Integer> uniqueSet = new HashSet<>(256);
		final int[] initialArray = new int[count];
		for (int i = 0; i < count; i++) {
			boolean added;
			do {
				final int recId = rnd.nextInt(count << 1);
				added = uniqueSet.add(recId);
				if (added) {
					initialArray[i] = recId;
				}
			} while (!added);
		}
		return initialArray;
	}

	/**
	 * Asserts that the given {@link TransactionalUnorderedIntArray} matches the expected int array
	 * by verifying emptiness, last record id, raw array equality, iterator output, length,
	 * containment, and index consistency.
	 *
	 * @param expectedResult the expected unordered int array
	 * @param array          the transactional array to verify
	 */
	private static void assertTransactionalArrayIs(int[] expectedResult, TransactionalUnorderedIntArray array) {
		assertTransactionalArrayIs(expectedResult, array, null);
	}

	/**
	 * Asserts that the given {@link TransactionalUnorderedIntArray} matches the expected int array
	 * with an optional additional message appended to assertion failures.
	 *
	 * @param expectedResult    the expected unordered int array
	 * @param array             the transactional array to verify
	 * @param additionalMessage optional message appended to assertion failure output
	 */
	private static void assertTransactionalArrayIs(int[] expectedResult, TransactionalUnorderedIntArray array, String additionalMessage) {
		if (ArrayUtils.isEmpty(expectedResult)) {
			assertTrue(array.isEmpty());
			assertThrows(ArrayIndexOutOfBoundsException.class, array::getLastRecordId);
		} else {
			assertFalse(array.isEmpty());
			assertEquals(expectedResult[expectedResult.length - 1], array.getLastRecordId());
		}

		assertArrayEquals(
			expectedResult,
			array.getArray(),
			"\nExpected: " + Arrays.stream(expectedResult).mapToObj(Integer::toString).collect(Collectors.joining(", ")) + "\n" +
				"Actual:   " + Arrays.stream(array.getArray()).mapToObj(Integer::toString).collect(Collectors.joining(", ")) + "\n" +
				ofNullable(additionalMessage).orElse("")
		);
		assertArrayEquals(
			expectedResult,
			CompositeIntArray.toArray(array.iterator()),
			"\nExpected: " + Arrays.stream(expectedResult).mapToObj(Integer::toString).collect(Collectors.joining(", ")) + "\n" +
				"Actual:   " + Arrays.stream(CompositeIntArray.toArray(array.iterator())).mapToObj(Integer::toString).collect(Collectors.joining(", ")) + "\n" +
				ofNullable(additionalMessage).orElse("")
		);

		assertEquals(expectedResult.length, array.getLength());
		for (int i = 0; i < expectedResult.length; i++) {
			final int recordId = expectedResult[i];
			assertTrue(array.contains(recordId), "Array doesn't contain " + recordId);
			assertEquals(i, array.indexOf(recordId), "Index of " + recordId + " is not " + i + ", but " + array.indexOf(recordId));
		}
	}

	/**
	 * Tests for bugs found during code analysis.
	 */
	@Nested
	@DisplayName("Bug fixes")
	class BugFixTest {

		@Test
		@DisplayName("addIntAfterRecord should use position (not recordId) when adding to removals")
		void shouldUsePositionNotRecordIdWhenMovingBaselineRecordAfterDiffRecord() {
			final TransactionalUnorderedIntArray array =
				new TransactionalUnorderedIntArray(new int[]{10, 20, 30});

			assertStateAfterCommit(
				array,
				original -> {
					// insert 40 at head via diff (40 is not in baseline)
					original.add(Integer.MIN_VALUE, 40);
					// move baseline record 10 (at position 0) after diff-inserted 40
					// this triggers the prevRecLookup.isPresentInDiff() path
					// where removals should get existingPosition=0, not recordId=10
					original.add(40, 10);

					assertTransactionalArrayIs(
						new int[]{40, 10, 20, 30}, array
					);
				},
				(original, committed) -> {
					assertArrayEquals(
						new int[]{40, 10, 20, 30}, committed,
						"Committed array should have 10 moved after 40 without duplicates"
					);
				}
			);
		}

		@Test
		@DisplayName("getLastRecordId should throw ArrayIndexOutOfBoundsException when all elements removed")
		void shouldThrowArrayIndexOutOfBoundsWhenAllElementsRemovedAndMemoNotSet() {
			final TransactionalUnorderedIntArray array =
				new TransactionalUnorderedIntArray(new int[]{10, 20, 30});

			assertStateAfterCommit(
				array,
				original -> {
					// remove all elements in transaction
					original.remove(10);
					original.remove(20);
					original.remove(30);

					// call getLastRecordId WITHOUT calling getArray() first
					// (to avoid memoizedMergedArray being set)
					// should throw ArrayIndexOutOfBoundsException per contract
					assertThrows(
						ArrayIndexOutOfBoundsException.class,
						original::getLastRecordId,
						"Should throw ArrayIndexOutOfBoundsException when array is empty"
					);
				},
				(original, committed) -> {
					assertArrayEquals(
						new int[0], committed,
						"Committed array should be empty"
					);
				}
			);
		}

	}

	/**
	 * Holds the state carried between generational test iterations.
	 *
	 * @param code         a buffer accumulating the test recipe code for debugging
	 * @param initialArray the starting array for the current iteration
	 */
	private record TestState(
		StringBuilder code,
		int[] initialArray
	) {
	}

}
