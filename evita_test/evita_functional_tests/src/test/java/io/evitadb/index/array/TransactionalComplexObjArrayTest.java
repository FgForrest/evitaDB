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
import io.evitadb.core.transaction.memory.VoidTransactionMemoryProducer;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import io.evitadb.utils.Assert;
import lombok.Data;
import org.apache.commons.lang3.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.ArrayUtils.computeInsertPositionOfObjInOrderedArray;
import static io.evitadb.utils.ArrayUtils.insertRecordIntoArrayOnIndex;
import static io.evitadb.utils.ArrayUtils.isEmpty;
import static io.evitadb.utils.ArrayUtils.removeRecordFromOrderedArray;
import static io.evitadb.utils.AssertionUtils.assertIteratorContains;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TransactionalComplexObjArray} verifying transactional behaviour including
 * commit and rollback semantics, combine/reduce patterns with {@link TransactionalObject}
 * elements, the {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer}
 * contract, bulk operations, and edge cases.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@DisplayName("Transactional complex object array")
class TransactionalComplexObjArrayTest implements TimeBoundedTestSupport {

	/**
	 * Asserts that the given {@link TransactionalComplexObjArray} of {@link TransactionalInteger}
	 * matches the expected contents by verifying emptiness, array equality, length, element
	 * access, indexOf, and iterator output.
	 *
	 * @param expectedContents the expected sorted array of TransactionalInteger
	 * @param array            the transactional array to verify
	 */
	private static void assertTransactionalObjArray(
		@Nonnull TransactionalInteger[] expectedContents,
		@Nonnull TransactionalComplexObjArray<TransactionalInteger> array
	) {
		if (isEmpty(expectedContents)) {
			assertTrue(array.isEmpty());
		} else {
			assertFalse(array.isEmpty());
		}
		assertArrayEquals(expectedContents, array.getArray());
		assertEquals(expectedContents.length, array.getLength());

		for (int i = 0; i < expectedContents.length; i++) {
			assertEquals(expectedContents[i], array.get(i));
			assertEquals(i, array.indexOf(expectedContents[i]));
		}

		assertIteratorContains(array.iterator(), expectedContents);
	}

	/**
	 * Asserts that the given {@link TransactionalComplexObjArray} of {@link DistinctValueHolder}
	 * matches the expected contents by verifying emptiness, array equality, iterator output,
	 * length, element access, and indexOf.
	 *
	 * @param expectedContents the expected sorted array of DistinctValueHolder
	 * @param array            the transactional array to verify
	 */
	private static void assertTransactionalObjArray(
		@Nonnull DistinctValueHolder[] expectedContents,
		@Nonnull TransactionalComplexObjArray<DistinctValueHolder> array
	) {
		if (isEmpty(expectedContents)) {
			assertTrue(array.isEmpty());
		} else {
			assertFalse(array.isEmpty());
		}
		assertArrayEquals(expectedContents, array.getArray());
		assertIteratorContains(array.iterator(), expectedContents);

		assertEquals(expectedContents.length, array.getLength());

		for (int i = 0; i < expectedContents.length; i++) {
			assertEquals(expectedContents[i], array.get(i));
			assertEquals(i, array.indexOf(expectedContents[i]));
		}
	}

	/**
	 * Creates a {@link TransactionalComplexObjArray} of {@link DistinctValueHolder} with
	 * combine/reduce/obsoleteChecker/deepComparator callbacks, executes `doInTransaction`
	 * within a transaction, and verifies that the transactional view matches `expectedValue`
	 * while the original array remains unchanged after commit.
	 *
	 * @param startValue      the initial array contents
	 * @param expectedValue   the expected array after transactional modifications
	 * @param doInTransaction the operations to perform within the transaction
	 */
	private static void assertDistinctValueStateAfterCommit(
		@Nonnull DistinctValueHolder[] startValue,
		@Nonnull DistinctValueHolder[] expectedValue,
		@Nonnull Consumer<TransactionalComplexObjArray<DistinctValueHolder>> doInTransaction
	) {
		final TransactionalComplexObjArray<DistinctValueHolder> array = new TransactionalComplexObjArray<>(
			startValue,
			DistinctValueHolder::combineWith,
			DistinctValueHolder::subtract,
			DistinctValueHolder::isEmpty,
			DistinctValueHolder::equals
		);

		assertStateAfterCommit(
			array,
			original -> {
				doInTransaction.accept(original);

				assertTransactionalObjArray(expectedValue, original);
			},
			(original, committed) -> {
				assertTransactionalObjArray(startValue, original);
				assertArrayEquals(committed, expectedValue);
			}
		);
	}

	/**
	 * Creates an array of {@link TransactionalInteger} from the given int values.
	 *
	 * @param integers the int values to wrap
	 * @return a new array of TransactionalInteger instances
	 */
	@Nonnull
	private static TransactionalInteger[] createIntegerArray(int... integers) {
		final TransactionalInteger[] result = new TransactionalInteger[integers.length];
		for (int i = 0; i < integers.length; i++) {
			final int integer = integers[i];
			result[i] = new TransactionalInteger(integer);
		}
		return result;
	}

	/**
	 * Tests verifying construction, identity, and basic factory behaviour of
	 * {@link TransactionalComplexObjArray}.
	 */
	@Nested
	@DisplayName("Constructors and identity")
	class ConstructorsAndIdentityTest {

		@Test
		void shouldCreateFromDelegateArray() {
			final TransactionalInteger[] delegate = createIntegerArray(3, 7, 11);

			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(delegate);

			assertArrayEquals(delegate, array.getArray());
			assertEquals(3, array.getLength());
			assertFalse(array.isEmpty());
		}

		@Test
		void shouldCreateWithCustomComparator() {
			final TransactionalInteger[] delegate = createIntegerArray(11, 7, 3);
			// natural order comparator applied to already-sorted descending array
			// the array must be pre-sorted for the comparator to work correctly
			final TransactionalInteger[] sorted = createIntegerArray(3, 7, 11);
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(sorted, Comparator.naturalOrder());

			assertArrayEquals(sorted, array.getArray());
			assertEquals(3, array.getLength());
		}

		@Test
		void shouldCreateWithAllCallbacks() {
			final DistinctValueHolder[] delegate = new DistinctValueHolder[]{
				new DistinctValueHolder("A", 1, 2),
				new DistinctValueHolder("B", 3)
			};
			final TransactionalComplexObjArray<DistinctValueHolder> array =
				new TransactionalComplexObjArray<>(
					delegate,
					DistinctValueHolder::combineWith,
					DistinctValueHolder::subtract,
					DistinctValueHolder::isEmpty,
					DistinctValueHolder::equals
				);

			assertArrayEquals(delegate, array.getArray());
			assertEquals(2, array.getLength());
		}

		@Test
		void shouldReturnUniqueId() {
			final TransactionalComplexObjArray<TransactionalInteger> first =
				new TransactionalComplexObjArray<>(createIntegerArray(1));
			final TransactionalComplexObjArray<TransactionalInteger> second =
				new TransactionalComplexObjArray<>(createIntegerArray(2));

			assertNotEquals(first.getId(), second.getId());
		}

		@Test
		void shouldReturnMeaningfulToString() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(createIntegerArray(1, 5, 10));

			final String result = array.toString();

			assertNotNull(result);
			assertFalse(result.isEmpty());
		}

	}

	/**
	 * Tests verifying the {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer}
	 * contract on {@link TransactionalComplexObjArray}.
	 */
	@Nested
	@DisplayName("TransactionalLayerProducer contract")
	class TransactionalLayerProducerContractTest {

		@Test
		void shouldReturnNullLayerOutsideTransaction() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(createIntegerArray(1, 5, 10));

			final ComplexObjArrayChanges<TransactionalInteger> layer = array.createLayer();

			assertNull(layer);
		}

		@Test
		void shouldReturnLayerWithCallbacks() {
			final TransactionalComplexObjArray<DistinctValueHolder> array =
				new TransactionalComplexObjArray<>(
					new DistinctValueHolder[]{new DistinctValueHolder("A", 1)},
					DistinctValueHolder::combineWith,
					DistinctValueHolder::subtract,
					DistinctValueHolder::isEmpty,
					DistinctValueHolder::equals
				);

			// outside transaction, even with producer, createLayer returns null
			final ComplexObjArrayChanges<DistinctValueHolder> layer = array.createLayer();

			assertNull(layer);
		}

		@Test
		void shouldReturnNewCopyOnMerge() {
			final TransactionalLayerMaintainer maintainer =
				Mockito.mock(TransactionalLayerMaintainer.class);
			final TransactionalInteger[] delegate = createIntegerArray(1, 5, 10);
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(delegate);

			final TransactionalInteger[] result =
				array.createCopyWithMergedTransactionalMemory(null, maintainer);

			// when layer is null, returns a new copy of the delegate
			assertArrayEquals(delegate, result);
			assertNotSame(delegate, result);
		}

		@Test
		void shouldReturnUniqueId() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(createIntegerArray(1));

			final long id = array.getId();

			assertTrue(id != 0);
		}

	}

	/**
	 * Tests verifying transactional rollback restores the original array state.
	 */
	@Nested
	@DisplayName("Transactional rollback")
	class TransactionalRollbackTest {

		@Test
		void shouldCorrectlyAddItemsOnFirstLastAndMiddlePositionsAndRollback() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(createIntegerArray(1, 5, 10));

			assertStateAfterRollback(
				array,
				original -> {
					original.add(new TransactionalInteger(11));
					original.add(new TransactionalInteger(0));
					original.add(new TransactionalInteger(6));

					assertTransactionalObjArray(
						createIntegerArray(0, 1, 5, 6, 10, 11), original
					);
				},
				(original, committed) -> {
					assertNull(committed);
					assertTransactionalObjArray(
						createIntegerArray(1, 5, 10), original
					);
				}
			);
		}

		@Test
		void shouldCorrectlyRemoveItemsOnFirstLastAndMiddlePositionsAndRollback() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(createIntegerArray(1, 5, 10));

			assertStateAfterRollback(
				array,
				original -> {
					original.remove(new TransactionalInteger(5));

					assertTransactionalObjArray(
						createIntegerArray(1, 10), original
					);
				},
				(original, committed) -> {
					assertNull(committed);
					assertTransactionalObjArray(
						createIntegerArray(1, 5, 10), original
					);
				}
			);
		}

	}

	/**
	 * Tests verifying transactional commit behaviour with simple add/remove operations
	 * using {@link TransactionalInteger} elements (no combine/reduce logic).
	 */
	@Nested
	@DisplayName("Transactional commit - simple add/remove")
	class TransactionalCommitSimpleTest {

		@Test
		void shouldCorrectlyAddItemsOnFirstLastAndMiddlePositionsAndCommit() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(createIntegerArray(1, 5, 10));

			assertStateAfterCommit(
				array,
				original -> {
					original.add(new TransactionalInteger(11));
					original.add(new TransactionalInteger(11));
					original.add(new TransactionalInteger(0));
					original.add(new TransactionalInteger(6));

					assertTransactionalObjArray(
						createIntegerArray(0, 1, 5, 6, 10, 11), original
					);
				},
				(original, committed) -> {
					assertArrayEquals(
						committed, createIntegerArray(0, 1, 5, 6, 10, 11)
					);
					assertTransactionalObjArray(
						createIntegerArray(1, 5, 10), original
					);
				}
			);
		}

		@Test
		void shouldCorrectlyRemoveItemsFromFirstLastAndMiddlePositionsAndCommit() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(
					createIntegerArray(1, 2, 5, 6, 10, 11)
				);

			assertStateAfterCommit(
				array,
				original -> {
					original.remove(new TransactionalInteger(11));
					original.remove(new TransactionalInteger(1));
					original.remove(new TransactionalInteger(5));

					assertTransactionalObjArray(
						createIntegerArray(2, 6, 10), original
					);
				},
				(original, committed) -> {
					assertArrayEquals(committed, createIntegerArray(2, 6, 10));
					assertTransactionalObjArray(
						createIntegerArray(1, 2, 5, 6, 10, 11), original
					);
				}
			);
		}

		@Test
		void shouldAddAndRemoveEverythingAndCommit() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(new TransactionalInteger[0]);

			assertStateAfterCommit(
				array,
				original -> {
					original.add(new TransactionalInteger(1));
					original.add(new TransactionalInteger(5));
					original.remove(new TransactionalInteger(1));
					original.remove(new TransactionalInteger(5));

					assertTransactionalObjArray(new TransactionalInteger[0], original);
				},
				(original, committed) -> {
					assertArrayEquals(new TransactionalInteger[0], committed);
					assertTransactionalObjArray(new TransactionalInteger[0], original);
				}
			);
		}

		@Test
		void shouldCorrectlyRemoveMultipleItemsInARowAndCommit() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(
					createIntegerArray(1, 2, 5, 6, 10, 11)
				);

			assertStateAfterCommit(
				array,
				original -> {
					original.remove(new TransactionalInteger(6));
					original.remove(new TransactionalInteger(5));
					original.remove(new TransactionalInteger(2));

					assertTransactionalObjArray(
						createIntegerArray(1, 10, 11), original
					);
				},
				(original, committed) -> {
					assertArrayEquals(committed, createIntegerArray(1, 10, 11));
					assertTransactionalObjArray(
						createIntegerArray(1, 2, 5, 6, 10, 11), original
					);
				}
			);
		}

		@Test
		void shouldCorrectlyRemoveMultipleItemsInARowTillTheEndAndCommit() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(
					createIntegerArray(1, 2, 5, 6, 10, 11)
				);

			assertStateAfterCommit(
				array,
				original -> {
					original.remove(new TransactionalInteger(6));
					original.remove(new TransactionalInteger(5));
					original.remove(new TransactionalInteger(10));
					original.remove(new TransactionalInteger(11));

					assertTransactionalObjArray(
						createIntegerArray(1, 2), original
					);
				},
				(original, committed) -> {
					assertTransactionalObjArray(
						createIntegerArray(1, 2, 5, 6, 10, 11), original
					);
					assertArrayEquals(committed, createIntegerArray(1, 2));
				}
			);
		}

		@Test
		void shouldCorrectlyRemoveMultipleItemsInARowFromTheBeginningAndCommit() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(
					createIntegerArray(1, 2, 5, 6, 10, 11)
				);

			assertStateAfterCommit(
				array,
				original -> {
					original.remove(new TransactionalInteger(2));
					original.remove(new TransactionalInteger(6));
					original.remove(new TransactionalInteger(5));
					original.remove(new TransactionalInteger(1));
					original.remove(new TransactionalInteger(10));

					assertTransactionalObjArray(createIntegerArray(11), original);
				},
				(original, committed) -> {
					assertTransactionalObjArray(
						createIntegerArray(1, 2, 5, 6, 10, 11), original
					);
					assertArrayEquals(committed, createIntegerArray(11));
				}
			);
		}

		@Test
		void shouldAddNothingAndCommit() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(createIntegerArray(1, 5, 10));

			assertStateAfterCommit(
				array,
				original -> {
					assertTransactionalObjArray(
						createIntegerArray(1, 5, 10), original
					);
				},
				(original, committed) -> {
					assertTransactionalObjArray(
						createIntegerArray(1, 5, 10), original
					);
					assertArrayEquals(committed, createIntegerArray(1, 5, 10));
				}
			);
		}

		@Test
		void shouldCorrectlyAddMultipleItemsOnSamePositionsAndCommit() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(createIntegerArray(1, 5, 10));

			assertStateAfterCommit(
				array,
				original -> {
					original.add(new TransactionalInteger(11));
					original.add(new TransactionalInteger(6));
					original.add(new TransactionalInteger(0));
					original.add(new TransactionalInteger(3));
					original.add(new TransactionalInteger(3));
					original.add(new TransactionalInteger(7));
					original.add(new TransactionalInteger(12));
					original.add(new TransactionalInteger(2));
					original.add(new TransactionalInteger(8));

					assertTransactionalObjArray(
						createIntegerArray(0, 1, 2, 3, 5, 6, 7, 8, 10, 11, 12),
						original
					);
				},
				(original, committed) -> {
					assertTransactionalObjArray(
						createIntegerArray(1, 5, 10), original
					);
					assertArrayEquals(
						committed,
						createIntegerArray(0, 1, 2, 3, 5, 6, 7, 8, 10, 11, 12)
					);
				}
			);
		}

		@Test
		void shouldCorrectlyAddAndRemoveOnNonOverlappingPositionsAndCommit() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(
					createIntegerArray(1, 2, 5, 6, 10, 11)
				);

			assertStateAfterCommit(
				array,
				original -> {
					original.add(new TransactionalInteger(4));
					original.add(new TransactionalInteger(3));
					original.remove(new TransactionalInteger(10));
					original.remove(new TransactionalInteger(6));
					original.add(new TransactionalInteger(15));

					assertTransactionalObjArray(
						createIntegerArray(1, 2, 3, 4, 5, 11, 15), original
					);
				},
				(original, committed) -> {
					assertTransactionalObjArray(
						createIntegerArray(1, 2, 5, 6, 10, 11), original
					);
					assertArrayEquals(
						committed, createIntegerArray(1, 2, 3, 4, 5, 11, 15)
					);
				}
			);
		}

		@Test
		void shouldCorrectlyAddAndRemoveSameNumberAndCommit() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(
					createIntegerArray(1, 2, 5, 6, 10, 11)
				);

			assertStateAfterCommit(
				array,
				original -> {
					original.add(new TransactionalInteger(4));
					original.remove(new TransactionalInteger(4));

					assertTransactionalObjArray(
						createIntegerArray(1, 2, 5, 6, 10, 11), original
					);
				},
				(original, committed) -> {
					assertTransactionalObjArray(
						createIntegerArray(1, 2, 5, 6, 10, 11), original
					);
					assertArrayEquals(
						committed, createIntegerArray(1, 2, 5, 6, 10, 11)
					);
				}
			);
		}

		@Test
		void shouldCorrectlyRemoveAndAddSameNumberAndCommit() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(
					createIntegerArray(1, 2, 5, 6, 10, 11)
				);

			assertStateAfterCommit(
				array,
				original -> {
					original.remove(new TransactionalInteger(5));
					original.remove(new TransactionalInteger(10));
					original.remove(new TransactionalInteger(11));
					original.add(new TransactionalInteger(10));
					original.add(new TransactionalInteger(11));
					original.add(new TransactionalInteger(5));

					assertTransactionalObjArray(
						createIntegerArray(1, 2, 5, 6, 10, 11), original
					);
				},
				(original, committed) -> {
					assertTransactionalObjArray(
						createIntegerArray(1, 2, 5, 6, 10, 11), original
					);
					assertArrayEquals(
						committed, createIntegerArray(1, 2, 5, 6, 10, 11)
					);
				}
			);
		}

		@Test
		void shouldCorrectlyAddAndRemoveOnOverlappingBoundaryPositionsAndCommit() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(
					createIntegerArray(1, 2, 5, 6, 10, 11)
				);

			assertStateAfterCommit(
				array,
				original -> {
					original.remove(new TransactionalInteger(1));
					original.remove(new TransactionalInteger(11));
					original.add(new TransactionalInteger(0));
					original.add(new TransactionalInteger(12));

					assertTransactionalObjArray(
						createIntegerArray(0, 2, 5, 6, 10, 12), original
					);
				},
				(original, committed) -> {
					assertTransactionalObjArray(
						createIntegerArray(1, 2, 5, 6, 10, 11), original
					);
					assertArrayEquals(
						committed, createIntegerArray(0, 2, 5, 6, 10, 12)
					);
				}
			);
		}

		@Test
		void shouldCorrectlyAddAndRemoveOnOverlappingMiddlePositionsAndCommit() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(createIntegerArray(1, 5, 8, 11));

			assertStateAfterCommit(
				array,
				original -> {
					original.remove(new TransactionalInteger(5));
					original.remove(new TransactionalInteger(8));
					original.add(new TransactionalInteger(6));
					original.add(new TransactionalInteger(7));
					original.add(new TransactionalInteger(8));
					original.add(new TransactionalInteger(9));
					original.add(new TransactionalInteger(10));

					assertTransactionalObjArray(
						createIntegerArray(1, 6, 7, 8, 9, 10, 11), original
					);
				},
				(original, committed) -> {
					assertTransactionalObjArray(
						createIntegerArray(1, 5, 8, 11), original
					);
					assertArrayEquals(
						committed, createIntegerArray(1, 6, 7, 8, 9, 10, 11)
					);
				}
			);
		}

		@Test
		void shouldProperlyHandleChangesOnSinglePositionWithInt() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(createIntegerArray(1, 2));

			assertStateAfterCommit(
				array,
				original -> {
					original.remove(new TransactionalInteger(1));
					original.remove(new TransactionalInteger(2));
					original.add(new TransactionalInteger(2));
					original.add(new TransactionalInteger(4));
					original.remove(new TransactionalInteger(2));
					original.add(new TransactionalInteger(5));

					assertTransactionalObjArray(
						createIntegerArray(4, 5), original
					);
				},
				(original, committed) -> {
					assertTransactionalObjArray(
						createIntegerArray(1, 2), original
					);
					assertArrayEquals(committed, createIntegerArray(4, 5));
				}
			);
		}

	}

	/**
	 * Tests verifying transactional commit behaviour with combine/reduce patterns
	 * using {@link DistinctValueHolder} elements that support inner value merging
	 * and subtraction.
	 */
	@Nested
	@DisplayName("Transactional commit - combine/reduce")
	class TransactionalCommitCombineReduceTest {

		@Test
		void shouldCorrectlyMergeInnerValuesOnAddToEmptyArray() {
			assertDistinctValueStateAfterCommit(
				new DistinctValueHolder[0],
				new DistinctValueHolder[]{
					new DistinctValueHolder("A", 1, 2, 3, 4),
					new DistinctValueHolder("B", 8),
					new DistinctValueHolder("C", 5, 6)
				},
				original -> {
					original.add(new DistinctValueHolder("A", 1, 2));
					original.add(new DistinctValueHolder("B", 8));
					original.add(new DistinctValueHolder("A", 3, 4));
					original.add(new DistinctValueHolder("C", 5, 6));
				}
			);
		}

		@Test
		void shouldCorrectlyMergeInnerValuesOnAddToFilledArray() {
			assertDistinctValueStateAfterCommit(
				new DistinctValueHolder[]{
					new DistinctValueHolder("A", 1, 2),
					new DistinctValueHolder("B", 8)
				},
				new DistinctValueHolder[]{
					new DistinctValueHolder("A", 1, 2, 3, 4),
					new DistinctValueHolder("B", 8),
					new DistinctValueHolder("C", 5, 6)
				},
				original -> {
					original.add(new DistinctValueHolder("A", 3, 4));
					original.add(new DistinctValueHolder("C", 5, 6));
				}
			);
		}

		@Test
		void shouldCorrectlyMergeInnerValuesOnRemovalFromFilledArray() {
			assertDistinctValueStateAfterCommit(
				new DistinctValueHolder[]{
					new DistinctValueHolder("A", 1, 2, 3),
					new DistinctValueHolder("B", 4),
					new DistinctValueHolder("C", 5, 6)
				},
				new DistinctValueHolder[]{
					new DistinctValueHolder("A", 3),
					new DistinctValueHolder("B", 4)
				},
				original -> {
					original.remove(new DistinctValueHolder("A", 1));
					original.remove(new DistinctValueHolder("A", 2));
					original.remove(new DistinctValueHolder("C", 5, 6));
				}
			);
		}

		@Test
		void shouldCorrectlyMergeInnerValuesOnRemovalFromFilledArrayWithAddedValues() {
			assertDistinctValueStateAfterCommit(
				new DistinctValueHolder[]{
					new DistinctValueHolder("A", 1, 2),
					new DistinctValueHolder("B", 3),
					new DistinctValueHolder("C", 4)
				},
				new DistinctValueHolder[]{
					new DistinctValueHolder("A", 3),
					new DistinctValueHolder("B", 3),
					new DistinctValueHolder("C", 5, 6),
					new DistinctValueHolder("E", 8),
					new DistinctValueHolder("F", 9),
				},
				original -> {
					original.add(new DistinctValueHolder("A", 3));
					original.add(new DistinctValueHolder("C", 5, 6));
					original.add(new DistinctValueHolder("D", 7));
					original.add(new DistinctValueHolder("E", 8));
					original.add(new DistinctValueHolder("F", 9));
					original.remove(new DistinctValueHolder("A", 1));
					original.remove(new DistinctValueHolder("A", 2));
					original.remove(new DistinctValueHolder("C", 4));
					original.remove(new DistinctValueHolder("D", 7));
				}
			);
		}

		@Test
		void shouldCorrectlyMergeInnerValuesOnRemovalFromFilledArrayWithAddedValuesOnSamePosition() {
			assertDistinctValueStateAfterCommit(
				new DistinctValueHolder[]{
					new DistinctValueHolder("B", 1, 2),
					new DistinctValueHolder("C", 4)
				},
				new DistinctValueHolder[]{
					new DistinctValueHolder("A", -1)
				},
				original -> {
					original.add(new DistinctValueHolder("B", 3));
					original.add(new DistinctValueHolder("A", -1));
					original.remove(new DistinctValueHolder("B", 1, 2, 3));
					original.remove(new DistinctValueHolder("C", 4));
				}
			);
		}

		@Test
		void shouldCorrectlyMergeInnerValuesOnRemovalFromFilledArrayWithAddedValuesOnSamePositionVariant2() {
			assertDistinctValueStateAfterCommit(
				new DistinctValueHolder[]{
					new DistinctValueHolder("B", 1, 2),
					new DistinctValueHolder("C", 4)
				},
				new DistinctValueHolder[]{
					new DistinctValueHolder("A", -1),
					new DistinctValueHolder("B", 1)
				},
				original -> {
					original.add(new DistinctValueHolder("B", 3));
					original.add(new DistinctValueHolder("A", -1));
					original.remove(new DistinctValueHolder("B", 2, 3));
					original.remove(new DistinctValueHolder("C", 4));
				}
			);
		}

		@Test
		void shouldLeaveArrayEmptyWhenInsertionsAndRemovalsAreMatching() {
			assertDistinctValueStateAfterCommit(
				new DistinctValueHolder[0],
				new DistinctValueHolder[0],
				original -> {
					original.add(new DistinctValueHolder("A", 1, 2));
					original.remove(new DistinctValueHolder("A", 1));
					original.remove(new DistinctValueHolder("A", 2));
					original.add(new DistinctValueHolder("B", 3));
					original.remove(new DistinctValueHolder("B", 3));
					original.add(new DistinctValueHolder("C", 4, 5));
					original.remove(new DistinctValueHolder("C", 4, 5));
					original.add(new DistinctValueHolder("D", 4));
					original.remove(new DistinctValueHolder("D", 4));
				}
			);
		}

		@Test
		void shouldProperlyHandleChangesOnSinglePosition() {
			assertDistinctValueStateAfterCommit(
				new DistinctValueHolder[]{
					new DistinctValueHolder("A", 1, 2),
					new DistinctValueHolder("B", 3, 4)
				},
				new DistinctValueHolder[]{
					new DistinctValueHolder("D", 3),
					new DistinctValueHolder("E", 4)
				},
				original -> {
					original.remove(new DistinctValueHolder("A", 1));
					original.remove(new DistinctValueHolder("A", 2));
					original.remove(new DistinctValueHolder("B", 3, 4));
					original.add(new DistinctValueHolder("B", 5));
					original.add(new DistinctValueHolder("D", 3));
					original.remove(new DistinctValueHolder("B", 5));
					original.add(new DistinctValueHolder("E", 4));
				}
			);
		}

		@Test
		void shouldAddMoreItemsToTheBeginning() {
			assertDistinctValueStateAfterCommit(
				new DistinctValueHolder[]{
					new DistinctValueHolder("C", 5, 6),
					new DistinctValueHolder("D", 7)
				},
				new DistinctValueHolder[]{
					new DistinctValueHolder("A", 1, 2),
					new DistinctValueHolder("B", 3, 4, 5),
					new DistinctValueHolder("C", 5, 6),
					new DistinctValueHolder("D", 7)
				},
				original -> {
					original.add(new DistinctValueHolder("A", 1));
					original.add(new DistinctValueHolder("A", 2));
					original.add(new DistinctValueHolder("B", 3, 4));
					original.add(new DistinctValueHolder("B", 5));
				}
			);
		}

		@Test
		void shouldAddMoreItemsToTheBeginningWithBeginningRemoval() {
			assertDistinctValueStateAfterCommit(
				new DistinctValueHolder[]{
					new DistinctValueHolder("C", 5, 6),
					new DistinctValueHolder("D", 7)
				},
				new DistinctValueHolder[]{
					new DistinctValueHolder("A", 1, 2),
					new DistinctValueHolder("B", 3, 4, 5),
					new DistinctValueHolder("D", 7)
				},
				original -> {
					original.add(new DistinctValueHolder("A", 1));
					original.add(new DistinctValueHolder("A", 2));
					original.remove(new DistinctValueHolder("C", 5, 6));
					original.add(new DistinctValueHolder("B", 3, 4));
					original.add(new DistinctValueHolder("B", 5));
				}
			);
		}

		@Test
		void shouldAddMoreItemsToTheEnd() {
			assertDistinctValueStateAfterCommit(
				new DistinctValueHolder[]{
					new DistinctValueHolder("A", 1, 2),
					new DistinctValueHolder("B", 3)
				},
				new DistinctValueHolder[]{
					new DistinctValueHolder("A", 1, 2),
					new DistinctValueHolder("B", 3),
					new DistinctValueHolder("C", 1, 2),
					new DistinctValueHolder("D", 3, 4, 5)
				},
				original -> {
					original.add(new DistinctValueHolder("C", 1));
					original.add(new DistinctValueHolder("D", 5));
					original.add(new DistinctValueHolder("D", 3, 4));
					original.add(new DistinctValueHolder("C", 2));
				}
			);
		}

		@Test
		void shouldAddMoreItemsToTheEndWithEndRemoval() {
			assertDistinctValueStateAfterCommit(
				new DistinctValueHolder[]{
					new DistinctValueHolder("A", 1, 2),
					new DistinctValueHolder("B", 3)
				},
				new DistinctValueHolder[]{
					new DistinctValueHolder("A", 1, 2),
					new DistinctValueHolder("C", 1, 2),
					new DistinctValueHolder("D", 3, 4, 5)
				},
				original -> {
					original.add(new DistinctValueHolder("C", 1));
					original.add(new DistinctValueHolder("D", 5));
					original.add(new DistinctValueHolder("D", 3, 4));
					original.add(new DistinctValueHolder("C", 2));
					original.remove(new DistinctValueHolder("B", 3));
				}
			);
		}

		@Test
		void shouldAddAndRemoveAllItems() {
			assertDistinctValueStateAfterCommit(
				new DistinctValueHolder[0],
				new DistinctValueHolder[0],
				original -> {
					original.add(new DistinctValueHolder("A", 1));
					original.add(new DistinctValueHolder("B", 2));
					original.remove(new DistinctValueHolder("A", 1));
					original.remove(new DistinctValueHolder("B", 2));
				}
			);
		}

		@Test
		void shouldCorrectlyDoNothingOnRemoveAndAddFinallySameItemsAndCommit() {
			assertDistinctValueStateAfterCommit(
				new DistinctValueHolder[]{
					new DistinctValueHolder("A", 1, 2),
				},
				new DistinctValueHolder[]{
					new DistinctValueHolder("A", 1, 2),
				},
				original -> {
					original.remove(new DistinctValueHolder("A", 1, 2));
					original.add(new DistinctValueHolder("A", 2));
					original.add(new DistinctValueHolder("A", 1));
				}
			);
		}

		@Test
		void shouldCorrectlyDoNothingOnRemoveAndAddSameItemsAndCommit() {
			assertDistinctValueStateAfterCommit(
				new DistinctValueHolder[]{
					new DistinctValueHolder("A", 1, 2),
				},
				new DistinctValueHolder[]{
					new DistinctValueHolder("A", 1, 2),
				},
				original -> {
					original.remove(new DistinctValueHolder("A", 1, 2));
					original.add(new DistinctValueHolder("A", 1, 2));
				}
			);
		}

		@Test
		void verify() {
			assertDistinctValueStateAfterCommit(
				new DistinctValueHolder[]{
					new DistinctValueHolder("(", 2, 4, 5),
					new DistinctValueHolder(")", 3, 7),
					new DistinctValueHolder("*", 0, 1, 2, 3, 5, 6, 7),
					new DistinctValueHolder("+", 0, 3, 6),
					new DistinctValueHolder(",", 0, 1, 2, 3, 4, 5, 6),
					new DistinctValueHolder("-", 7),
					new DistinctValueHolder(".", 0, 1, 2, 4, 5),
					new DistinctValueHolder("/", 1, 2),
					new DistinctValueHolder("0", 0, 1, 2, 3, 4, 5, 7),
					new DistinctValueHolder("1", 0, 1, 2, 3, 5),
				},
				new DistinctValueHolder[]{
					new DistinctValueHolder("(", 0, 1, 2, 3, 4, 5),
					new DistinctValueHolder("*", 0, 1, 2, 3, 5, 6, 7),
					new DistinctValueHolder("+", 0, 3, 6),
					new DistinctValueHolder(",", 0, 1, 2, 3, 4, 5, 6),
					new DistinctValueHolder("-", 0, 4, 5, 7),
					new DistinctValueHolder(".", 1, 2, 4, 5),
					new DistinctValueHolder("/", 2),
					new DistinctValueHolder("0", 0, 1, 2, 3, 4, 5, 7),
					new DistinctValueHolder("1", 0, 2, 3),
				},
				original -> {
					original.add(new DistinctValueHolder("1", 2, 3));
					original.remove(new DistinctValueHolder("/", 1));
					original.remove(new DistinctValueHolder(".", 0));
					original.remove(new DistinctValueHolder("1", 1, 5));
					original.remove(new DistinctValueHolder("-"));
					original.remove(new DistinctValueHolder(")", 3, 7));
					original.add(new DistinctValueHolder("(", 0, 1, 3));
					original.add(new DistinctValueHolder("-", 0, 4, 5));
				}
			);
		}

	}

	/**
	 * Tests verifying the {@link TransactionalComplexObjArray#addReturningIndex(T)} method
	 * which adds an element and returns the position where it was placed.
	 */
	@Nested
	@DisplayName("addReturningIndex")
	class AddReturningIndexTest {

		@Test
		void shouldReturnCorrectIndexForNewElement() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(createIntegerArray(1, 5, 10));

			final int index = array.addReturningIndex(new TransactionalInteger(3));

			assertEquals(1, index);
			assertArrayEquals(createIntegerArray(1, 3, 5, 10), array.getArray());
		}

		@Test
		void shouldReturnCorrectIndexForDuplicate() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(createIntegerArray(1, 5, 10));

			// without combiner, adding duplicate returns existing position
			final int index = array.addReturningIndex(new TransactionalInteger(5));

			assertEquals(1, index);
			// array unchanged (no combiner to merge)
			assertArrayEquals(createIntegerArray(1, 5, 10), array.getArray());
		}

		@Test
		void shouldReturnMinusOneForObsoleteElement() {
			final TransactionalComplexObjArray<DistinctValueHolder> array =
				new TransactionalComplexObjArray<>(
					new DistinctValueHolder[]{
						new DistinctValueHolder("B", 5)
					},
					DistinctValueHolder::combineWith,
					DistinctValueHolder::subtract,
					// obsoleteChecker: consider empty holders obsolete
					DistinctValueHolder::isEmpty,
					DistinctValueHolder::equals
				);

			// adding empty holder is rejected by obsoleteChecker
			final int index = array.addReturningIndex(new DistinctValueHolder("A"));

			assertEquals(-1, index);
		}

		@Test
		void shouldReturnCorrectIndexInTransaction() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(createIntegerArray(1, 5, 10));

			assertStateAfterCommit(
				array,
				original -> {
					final int idx1 = original.addReturningIndex(
						new TransactionalInteger(3)
					);
					final int idx2 = original.addReturningIndex(
						new TransactionalInteger(7)
					);

					assertEquals(1, idx1);
					assertEquals(3, idx2);
					assertTransactionalObjArray(
						createIntegerArray(1, 3, 5, 7, 10), original
					);
				},
				(original, committed) -> {
					assertArrayEquals(
						committed, createIntegerArray(1, 3, 5, 7, 10)
					);
				}
			);
		}

	}

	/**
	 * Tests verifying bulk operations (addAll and removeAll) on
	 * {@link TransactionalComplexObjArray}.
	 */
	@Nested
	@DisplayName("Bulk operations")
	class BulkOperationsTest {

		@Test
		void shouldAddAllElementsAndCommit() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(createIntegerArray(1, 10));

			assertStateAfterCommit(
				array,
				original -> {
					original.addAll(createIntegerArray(3, 5, 7));

					assertTransactionalObjArray(
						createIntegerArray(1, 3, 5, 7, 10), original
					);
				},
				(original, committed) -> {
					assertArrayEquals(
						committed, createIntegerArray(1, 3, 5, 7, 10)
					);
					assertTransactionalObjArray(
						createIntegerArray(1, 10), original
					);
				}
			);
		}

		@Test
		void shouldRemoveAllElementsAndCommit() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(
					createIntegerArray(1, 3, 5, 7, 10)
				);

			assertStateAfterCommit(
				array,
				original -> {
					original.removeAll(createIntegerArray(3, 7));

					assertTransactionalObjArray(
						createIntegerArray(1, 5, 10), original
					);
				},
				(original, committed) -> {
					assertArrayEquals(
						committed, createIntegerArray(1, 5, 10)
					);
					assertTransactionalObjArray(
						createIntegerArray(1, 3, 5, 7, 10), original
					);
				}
			);
		}

		@Test
		void shouldAddAllToEmptyArrayAndCommit() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(new TransactionalInteger[0]);

			assertStateAfterCommit(
				array,
				original -> {
					original.addAll(createIntegerArray(2, 4, 6));

					assertTransactionalObjArray(
						createIntegerArray(2, 4, 6), original
					);
				},
				(original, committed) -> {
					assertArrayEquals(committed, createIntegerArray(2, 4, 6));
					assertTransactionalObjArray(
						new TransactionalInteger[0], original
					);
				}
			);
		}

		@Test
		void shouldRemoveAllFromFullArrayAndCommit() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(createIntegerArray(2, 4, 6));

			assertStateAfterCommit(
				array,
				original -> {
					original.removeAll(createIntegerArray(2, 4, 6));

					assertTransactionalObjArray(
						new TransactionalInteger[0], original
					);
				},
				(original, committed) -> {
					assertArrayEquals(new TransactionalInteger[0], committed);
					assertTransactionalObjArray(
						createIntegerArray(2, 4, 6), original
					);
				}
			);
		}

	}

	/**
	 * Tests verifying contains, indexOf, and get operations within a transaction
	 * where the transactional layer has pending changes.
	 */
	@Nested
	@DisplayName("Contains, indexOf, and get in transaction")
	class ContainsIndexOfGetInTransactionTest {

		@Test
		void shouldFindAddedElementInTransaction() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(createIntegerArray(1, 5, 10));

			assertStateAfterCommit(
				array,
				original -> {
					original.add(new TransactionalInteger(7));

					assertTrue(original.contains(new TransactionalInteger(7)));
					assertTrue(original.contains(new TransactionalInteger(1)));
				},
				(original, committed) -> {
					assertArrayEquals(
						committed, createIntegerArray(1, 5, 7, 10)
					);
				}
			);
		}

		@Test
		void shouldNotFindRemovedElementInTransaction() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(createIntegerArray(1, 5, 10));

			assertStateAfterCommit(
				array,
				original -> {
					original.remove(new TransactionalInteger(5));

					assertFalse(original.contains(new TransactionalInteger(5)));
					assertTrue(original.contains(new TransactionalInteger(1)));
					assertTrue(original.contains(new TransactionalInteger(10)));
				},
				(original, committed) -> {
					assertArrayEquals(
						committed, createIntegerArray(1, 10)
					);
				}
			);
		}

		@Test
		void shouldReturnCorrectIndexOfInTransaction() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(createIntegerArray(1, 5, 10));

			assertStateAfterCommit(
				array,
				original -> {
					original.add(new TransactionalInteger(3));

					assertEquals(
						0, original.indexOf(new TransactionalInteger(1))
					);
					assertEquals(
						1, original.indexOf(new TransactionalInteger(3))
					);
					assertEquals(
						2, original.indexOf(new TransactionalInteger(5))
					);
					assertEquals(
						3, original.indexOf(new TransactionalInteger(10))
					);
				},
				(original, committed) -> {
					assertArrayEquals(
						committed, createIntegerArray(1, 3, 5, 10)
					);
				}
			);
		}

		@Test
		void shouldGetElementByIndexInTransaction() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(createIntegerArray(1, 5, 10));

			assertStateAfterCommit(
				array,
				original -> {
					original.add(new TransactionalInteger(3));

					assertEquals(new TransactionalInteger(1), original.get(0));
					assertEquals(new TransactionalInteger(3), original.get(1));
					assertEquals(new TransactionalInteger(5), original.get(2));
					assertEquals(
						new TransactionalInteger(10), original.get(3)
					);
				},
				(original, committed) -> {
					assertArrayEquals(
						committed, createIntegerArray(1, 3, 5, 10)
					);
				}
			);
		}

	}

	/**
	 * Tests verifying edge cases and boundary conditions.
	 */
	@Nested
	@DisplayName("Edge cases")
	class EdgeCasesTest {

		@Test
		void shouldHandleEmptyArrayOperations() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(new TransactionalInteger[0]);

			assertStateAfterCommit(
				array,
				original -> {
					original.add(new TransactionalInteger(42));
					original.remove(new TransactionalInteger(42));

					assertTransactionalObjArray(
						new TransactionalInteger[0], original
					);
				},
				(original, committed) -> {
					assertArrayEquals(new TransactionalInteger[0], committed);
				}
			);
		}

		@Test
		void shouldHandleSingleElementArray() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(createIntegerArray(5));

			assertStateAfterCommit(
				array,
				original -> {
					original.remove(new TransactionalInteger(5));
					original.add(new TransactionalInteger(10));

					assertTransactionalObjArray(
						createIntegerArray(10), original
					);
				},
				(original, committed) -> {
					assertArrayEquals(committed, createIntegerArray(10));
					assertTransactionalObjArray(createIntegerArray(5), original);
				}
			);
		}

		@Test
		void shouldThrowNoSuchElementWhenIteratorExhausted() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(createIntegerArray(1));

			assertStateAfterCommit(
				array,
				original -> {
					final Iterator<TransactionalInteger> it = original.iterator();
					assertTrue(it.hasNext());
					it.next();
					assertFalse(it.hasNext());
					assertThrows(NoSuchElementException.class, it::next);
				},
				(original, committed) -> {
					assertArrayEquals(committed, createIntegerArray(1));
				}
			);
		}

		@Test
		void shouldRemoveNonExistentElementGracefully() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(createIntegerArray(1, 5, 10));

			// outside transaction, removing non-existent element returns -1
			final int directResult = array.remove(new TransactionalInteger(99));
			assertEquals(-1, directResult);
			assertTransactionalObjArray(
				createIntegerArray(1, 5, 10), array
			);
		}

		@Test
		void shouldCommitArrayWithNonProducerElementsWithoutClassCastException() {
			final NonProducerTransactionalInteger[] delegate = new NonProducerTransactionalInteger[]{
				new NonProducerTransactionalInteger(1),
				new NonProducerTransactionalInteger(5),
				new NonProducerTransactionalInteger(10)
			};
			final TransactionalComplexObjArray<NonProducerTransactionalInteger> array =
				new TransactionalComplexObjArray<>(delegate);

			assertStateAfterCommit(
				array,
				original -> {
					original.add(new NonProducerTransactionalInteger(7));

					final NonProducerTransactionalInteger[] expected =
						new NonProducerTransactionalInteger[]{
							new NonProducerTransactionalInteger(1),
							new NonProducerTransactionalInteger(5),
							new NonProducerTransactionalInteger(7),
							new NonProducerTransactionalInteger(10)
						};
					assertArrayEquals(expected, original.getArray());
				},
				(original, committed) -> {
					final NonProducerTransactionalInteger[] expected =
						new NonProducerTransactionalInteger[]{
							new NonProducerTransactionalInteger(1),
							new NonProducerTransactionalInteger(5),
							new NonProducerTransactionalInteger(7),
							new NonProducerTransactionalInteger(10)
						};
					assertArrayEquals(expected, committed);
				}
			);
		}

		@Test
		void shouldAddSubclassElementsToSameInsertionSlotWithoutArrayStoreException() {
			final BaseTransactionalObj[] delegate = new BaseTransactionalObj[0];
			final TransactionalComplexObjArray<BaseTransactionalObj> array =
				new TransactionalComplexObjArray<>(delegate);

			assertStateAfterCommit(
				array,
				original -> {
					// first insertion creates per-slot array with SubA runtime type
					original.add(new SubA(5));
					// second insertion at different position -- OK
					original.add(new SubB(3));

					final BaseTransactionalObj[] expected = new BaseTransactionalObj[]{
						new SubB(3),
						new SubA(5)
					};
					assertArrayEquals(expected, original.getArray());
				},
				(original, committed) -> {
					final BaseTransactionalObj[] expected = new BaseTransactionalObj[]{
						new SubB(3),
						new SubA(5)
					};
					assertArrayEquals(expected, committed);
				}
			);
		}

		@Test
		void shouldHandleObsoleteCheckerRejectingAdd() {
			final TransactionalComplexObjArray<DistinctValueHolder> array =
				new TransactionalComplexObjArray<>(
					new DistinctValueHolder[]{
						new DistinctValueHolder("B", 5)
					},
					DistinctValueHolder::combineWith,
					DistinctValueHolder::subtract,
					DistinctValueHolder::isEmpty,
					DistinctValueHolder::equals
				);

			// empty DistinctValueHolder is "obsolete" and should be skipped
			array.add(new DistinctValueHolder("A"));

			// array should remain unchanged - the empty holder was rejected
			assertEquals(1, array.getLength());
			assertArrayEquals(
				new DistinctValueHolder[]{new DistinctValueHolder("B", 5)},
				array.getArray()
			);
		}

	}

	/**
	 * Tests verifying the removeLayer cleanup mechanism.
	 */
	@Nested
	@DisplayName("RemoveLayer cleanup")
	class RemoveLayerCleanupTest {

		@Test
		void shouldCleanAllLayersOnRemoveLayer() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(createIntegerArray(1, 5, 10));

			assertStateAfterRollback(
				array,
				original -> {
					original.add(new TransactionalInteger(3));
					original.add(new TransactionalInteger(7));

					// verify modifications are visible in transaction
					assertTransactionalObjArray(
						createIntegerArray(1, 3, 5, 7, 10), original
					);
				},
				(original, committed) -> {
					// after rollback, committed is null and original is restored
					assertNull(committed);
					assertTransactionalObjArray(
						createIntegerArray(1, 5, 10), original
					);
				}
			);
		}

	}

	/**
	 * Generational randomized test that verifies correctness of
	 * {@link TransactionalComplexObjArray} under many random transactional operations.
	 */
	@Nested
	@DisplayName("Generational proof")
	class GenerationalProofTest {

		@ParameterizedTest(name = "TransactionalComplexObjArray should survive generational randomized test applying modifications on it")
		@Tag(LONG_RUNNING_TEST)
		@ArgumentsSource(TimeArgumentProvider.class)
		void generationalProofTest(GenerationalTestInput input) {
			final int initialCount = 20;
			final int subCount = 30;
			final DistinctValueHolder[] initialState = generateRandomInitialArray(
				new Random(input.randomSeed()), initialCount, subCount
			);

			runFor(
				input,
				1000,
				new TestState(
					new StringBuilder(),
					initialState
				),
				(random, testState) -> {
					final StringBuilder codeBuffer = testState.code();
					final TransactionalComplexObjArray<DistinctValueHolder> transactionalArray =
						new TransactionalComplexObjArray<>(
							testState.initialArray(),
							DistinctValueHolder::combineWith,
							DistinctValueHolder::subtract,
							DistinctValueHolder::isEmpty,
							DistinctValueHolder::equals
						);
					final AtomicReference<DistinctValueHolder[]> nextArrayToCompare =
						new AtomicReference<>(testState.initialArray());

					assertStateAfterCommit(
						transactionalArray,
						original -> {
							codeBuffer.append("\nSTART:\n")
								.append(
									Arrays.stream(nextArrayToCompare.get())
										.map(DistinctValueHolder::toString)
										.collect(Collectors.joining("\n")))
								.append("\n\n");

							final int operationsInTransaction = random.nextInt(10);
							for (int i = 0; i < operationsInTransaction; i++) {
								if (random.nextBoolean() || transactionalArray.getLength() < 10) {
									// upsert new item
									final String recKey = String.valueOf((char) (40 + random.nextInt(initialCount * 2)));
									final DistinctValueHolder upsertItem = new DistinctValueHolder(recKey, generateRandomArray(random, random.nextInt(subCount)));
									codeBuffer.append("+ ").append(upsertItem).append("\n");
									final int txPosition = transactionalArray.addReturningIndex(upsertItem);
									final DistinctValueHolder[] referenceArray = nextArrayToCompare.get();
									final InsertionPosition position = computeInsertPositionOfObjInOrderedArray(upsertItem, referenceArray);
									if (position.alreadyPresent()) {
										referenceArray[position.position()] = mergeArrays(upsertItem, referenceArray[position.position()]);
									} else if (!upsertItem.getValues().isEmpty()) {
										nextArrayToCompare.set(insertRecordIntoArrayOnIndex(upsertItem, referenceArray, position.position()));
									}
									if (!upsertItem.getValues().isEmpty()) {
										assertEquals(position.position(), txPosition, codeBuffer.toString());
									}
								} else {
									// remove existing item
									final int position = random.nextInt(transactionalArray.getLength());
									final DistinctValueHolder removedRecId = transactionalArray.get(position);
									final DistinctValueHolder removedItem = new DistinctValueHolder(removedRecId.getKey(), pickSomethingRandomlyFrom(random, removedRecId.getValues()));
									codeBuffer.append("- ").append(removedItem).append("\n");
									transactionalArray.remove(removedItem);
									final Integer[] restArray = subtractArrays(removedItem.getValues(), removedRecId.getValues());
									final DistinctValueHolder[] existingArray = nextArrayToCompare.get();
									if (isEmpty(restArray)) {
										nextArrayToCompare.set(removeRecordFromOrderedArray(removedRecId, existingArray));
									} else {
										existingArray[position] = new DistinctValueHolder(removedRecId.getKey(), restArray);
									}
								}
							}

							// after operations the transactional array must match expected array
							assertTransactionalObjArray(nextArrayToCompare.get(), transactionalArray);
						},
						(original, committed) -> {
							codeBuffer.append("\nEXPECTED:\n")
								.append(
									Arrays.stream(nextArrayToCompare.get())
										.map(DistinctValueHolder::toString)
										.collect(Collectors.joining("\n"))
								)
								.append("\n");
							codeBuffer.append("\nGOT:\n")
								.append(
									Arrays.stream(committed)
										.map(DistinctValueHolder::toString)
										.collect(Collectors.joining("\n"))
								)
								.append("\n");
							assertArrayEquals(nextArrayToCompare.get(), committed, codeBuffer.toString());
						}
					);

					return new TestState(
						new StringBuilder(), nextArrayToCompare.get()
					);
				}
			);
		}

	}

	/**
	 * Subtracts elements of `subtractedArray` from `baseArray` and returns the remainder.
	 *
	 * @param subtractedArray the set of values to subtract
	 * @param baseArray       the original set of values
	 * @return array of remaining values after subtraction
	 */
	@Nonnull
	private Integer[] subtractArrays(
		@Nonnull TreeSet<Integer> subtractedArray,
		@Nonnull TreeSet<Integer> baseArray
	) {
		final TreeSet<Integer> baseArrayCopy = new TreeSet<>(baseArray);
		baseArrayCopy.removeAll(subtractedArray);
		return baseArrayCopy.toArray(new Integer[0]);
	}

	/**
	 * Randomly selects a subset of elements from the given sorted set.
	 *
	 * @param rnd    the random number generator
	 * @param values the source set of values
	 * @return a random subset as an array
	 */
	@Nonnull
	private Integer[] pickSomethingRandomlyFrom(
		@Nonnull Random rnd,
		@Nonnull TreeSet<Integer> values
	) {
		final TreeSet<Integer> newSet = new TreeSet<>(values);
		newSet.removeIf(it -> rnd.nextBoolean());
		return newSet.toArray(new Integer[0]);
	}

	/**
	 * Merges the inner values of two {@link DistinctValueHolder} instances into a new holder.
	 *
	 * @param upsertItem   the item being upserted
	 * @param existingItem the existing item in the array
	 * @return a new holder with merged values
	 */
	@Nonnull
	private DistinctValueHolder mergeArrays(
		@Nonnull DistinctValueHolder upsertItem,
		@Nonnull DistinctValueHolder existingItem
	) {
		final Set<Integer> mergedValues = new TreeSet<>(existingItem.getValues());
		mergedValues.addAll(upsertItem.getValues());
		final Integer[] values = mergedValues.toArray(new Integer[0]);
		return new DistinctValueHolder(existingItem.getKey(), values);
	}

	/**
	 * Generates a random initial array of {@link DistinctValueHolder} with unique keys.
	 *
	 * @param rnd      the random number generator
	 * @param count    the number of holders to generate
	 * @param subCount the maximum number of inner values per holder
	 * @return a sorted array of randomly generated holders
	 */
	@Nonnull
	private DistinctValueHolder[] generateRandomInitialArray(
		@Nonnull Random rnd,
		int count,
		int subCount
	) {
		final Set<String> uniqueSet = new HashSet<>();
		final DistinctValueHolder[] initialArray = new DistinctValueHolder[count];
		for (int i = 0; i < count; i++) {
			boolean added;
			do {
				final String recKey = String.valueOf((char) (40 + rnd.nextInt(count * 2)));
				added = uniqueSet.add(recKey);
				if (added) {
					final Integer[] values = generateRandomArray(rnd, subCount);
					if (ArrayUtils.isNotEmpty(values)) {
						initialArray[i] = new DistinctValueHolder(recKey, values);
					} else {
						added = false;
					}
				}
			} while (!added);
		}
		Arrays.sort(initialArray);
		return initialArray;
	}

	/**
	 * Generates a random sorted array of unique integers.
	 *
	 * @param rnd   the random number generator
	 * @param count the number of elements to generate
	 * @return a sorted array of unique integers
	 */
	@Nonnull
	private Integer[] generateRandomArray(@Nonnull Random rnd, int count) {
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

	/**
	 * Internal test state for generational proof testing.
	 *
	 * @param code         the StringBuilder accumulating test operation log
	 * @param initialArray the initial array state for the current iteration
	 */
	private record TestState(
		@Nonnull StringBuilder code,
		@Nonnull DistinctValueHolder[] initialArray
	) {
	}

	/**
	 * A {@link TransactionalObject} that does NOT implement
	 * {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer}.
	 * Used to verify that the transactional array handles elements
	 * that only implement the base {@link TransactionalObject} contract
	 * without the producer extension.
	 *
	 * @param object the wrapped integer value
	 */
	private record NonProducerTransactionalInteger(Integer object)
		implements TransactionalObject<NonProducerTransactionalInteger, Void>,
		Comparable<NonProducerTransactionalInteger> {

		@Override
		public long getId() {
			return 1L;
		}

		@Override
		public Void createLayer() {
			return null;
		}

		@Override
		public void removeLayer(
			@Nonnull TransactionalLayerMaintainer transactionalLayer
		) {
			// no-op
		}

		@Override
		public int compareTo(@Nonnull NonProducerTransactionalInteger o) {
			return Integer.compare(this.object, o.object);
		}

		@Nonnull
		@Override
		public NonProducerTransactionalInteger makeClone() {
			return new NonProducerTransactionalInteger(this.object);
		}
	}

	/**
	 * A minimal {@link TransactionalObject} wrapping a single int value.
	 * Used for simple add/remove tests without combine/reduce behaviour.
	 *
	 * @param object the wrapped integer value
	 */
	private record TransactionalInteger(Integer object)
		implements TransactionalObject<TransactionalInteger, Void>,
		VoidTransactionMemoryProducer<TransactionalInteger>,
		Comparable<TransactionalInteger> {

		@Nonnull
		@Override
		public TransactionalInteger createCopyWithMergedTransactionalMemory(
			Void layer,
			@Nonnull TransactionalLayerMaintainer transactionalLayer
		) {
			return this;
		}

		@Override
		public void removeLayer(
			@Nonnull TransactionalLayerMaintainer transactionalLayer
		) {
			// no-op for simple wrapper
		}

		@Override
		public int compareTo(@Nonnull TransactionalInteger o) {
			return Integer.compare(this.object, o.object);
		}

		@Nonnull
		@Override
		public TransactionalInteger makeClone() {
			return new TransactionalInteger(this.object);
		}
	}

	/**
	 * A {@link TransactionalObject} that holds a key and a set of distinct integer values.
	 * Supports combine (merge values), reduce (subtract values), and obsolete checking
	 * (empty set means obsolete). Used for testing combine/reduce patterns.
	 */
	@Data
	private static class DistinctValueHolder
		implements TransactionalObject<DistinctValueHolder, Void>,
		VoidTransactionMemoryProducer<DistinctValueHolder>,
		Comparable<DistinctValueHolder> {

		private final String key;
		private final TreeSet<Integer> values = new TreeSet<>();

		DistinctValueHolder(@Nonnull String key, @Nonnull Integer... values) {
			this.key = key;
			Collections.addAll(this.values, values);
		}

		@Nonnull
		@Override
		public DistinctValueHolder createCopyWithMergedTransactionalMemory(
			Void layer,
			@Nonnull TransactionalLayerMaintainer transactionalLayer
		) {
			return this;
		}

		@Override
		public void removeLayer(
			@Nonnull TransactionalLayerMaintainer transactionalLayer
		) {
			// no-op for test helper
		}

		@Override
		public int compareTo(@Nonnull DistinctValueHolder o) {
			return this.key.compareTo(o.key);
		}

		@Nonnull
		@Override
		public DistinctValueHolder makeClone() {
			return new DistinctValueHolder(
				this.key, this.values.toArray(new Integer[0])
			);
		}

		@Override
		public String toString() {
			return this.key + ":" + this.values.stream()
				.map(Object::toString)
				.collect(Collectors.joining(","));
		}

		/**
		 * Merges values from another holder into this holder.
		 *
		 * @param otherHolder the holder whose values to merge
		 */
		void combineWith(@Nonnull DistinctValueHolder otherHolder) {
			Assert.isTrue(
				this.key.equals(otherHolder.getKey()),
				"Keys are expected to be equal!"
			);
			this.values.addAll(otherHolder.getValues());
		}

		/**
		 * Returns true if this holder contains no values (is obsolete).
		 *
		 * @return true when value set is empty
		 */
		boolean isEmpty() {
			return this.values.isEmpty();
		}

		/**
		 * Removes values of another holder from this holder.
		 *
		 * @param otherHolder the holder whose values to subtract
		 */
		void subtract(@Nonnull DistinctValueHolder otherHolder) {
			Assert.isTrue(
				this.key.equals(otherHolder.getKey()),
				"Keys are expected to be equal!"
			);
			this.values.removeAll(otherHolder.getValues());
		}
	}

	/**
	 * Base class for testing polymorphic element insertion into
	 * {@link TransactionalComplexObjArray}. Non-final so subclasses
	 * can have a different runtime class from the component type.
	 */
	private static class BaseTransactionalObj
		implements TransactionalObject<BaseTransactionalObj, Void>,
		VoidTransactionMemoryProducer<BaseTransactionalObj>,
		Comparable<BaseTransactionalObj> {

		protected final int value;

		BaseTransactionalObj(int value) {
			this.value = value;
		}

		@Nonnull
		@Override
		public BaseTransactionalObj createCopyWithMergedTransactionalMemory(
			Void layer,
			@Nonnull TransactionalLayerMaintainer transactionalLayer
		) {
			return this;
		}

		@Override
		public void removeLayer(
			@Nonnull TransactionalLayerMaintainer transactionalLayer
		) {
			// no-op
		}

		@Override
		public int compareTo(@Nonnull BaseTransactionalObj o) {
			return Integer.compare(this.value, o.value);
		}

		@Nonnull
		@Override
		public BaseTransactionalObj makeClone() {
			return new BaseTransactionalObj(this.value);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof BaseTransactionalObj that)) return false;
			return this.value == that.value;
		}

		@Override
		public int hashCode() {
			return Integer.hashCode(this.value);
		}

		@Override
		public String toString() {
			return getClass().getSimpleName() + "(" + this.value + ")";
		}
	}

	/**
	 * Subclass A of {@link BaseTransactionalObj} for polymorphic tests.
	 */
	private static class SubA extends BaseTransactionalObj {
		SubA(int value) {
			super(value);
		}

		@Nonnull
		@Override
		public BaseTransactionalObj makeClone() {
			return new SubA(this.value);
		}
	}

	/**
	 * Subclass B of {@link BaseTransactionalObj} for polymorphic tests.
	 */
	private static class SubB extends BaseTransactionalObj {
		SubB(int value) {
			super(value);
		}

		@Nonnull
		@Override
		public BaseTransactionalObj makeClone() {
			return new SubB(this.value);
		}
	}

}
