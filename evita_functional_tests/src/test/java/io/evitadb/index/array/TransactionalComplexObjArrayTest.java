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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
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
 * This test verifies transactional behaviour of {@link TransactionalComplexObjArray}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
class TransactionalComplexObjArrayTest implements TimeBoundedTestSupport {

	private static void assertTransactionalObjArray(TransactionalInteger[] expectedContents, TransactionalComplexObjArray<TransactionalInteger> array) {
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

	private static void assertTransactionalObjArray(DistinctValueHolder[] expectedContents, TransactionalComplexObjArray<DistinctValueHolder> array) {
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

	private static void assertDistinctValueStateAfterCommit(DistinctValueHolder[] startValue, DistinctValueHolder[] expectedValue, Consumer<TransactionalComplexObjArray<DistinctValueHolder>> doInTransaction) {
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

	@Nonnull
	private static TransactionalInteger[] createIntegerArray(int... integers) {
		TransactionalInteger[] result = new TransactionalInteger[integers.length];
		for (int i = 0; i < integers.length; i++) {
			int integer = integers[i];
			result[i] = new TransactionalInteger(integer);
		}
		return result;
	}

	@Test
	void shouldCorrectlyAddItemsOnFirstLastAndMiddlePositionsAndRollback() {
		final TransactionalComplexObjArray<TransactionalInteger> array = new TransactionalComplexObjArray<>(createIntegerArray(1, 5, 10));

		assertStateAfterRollback(
			array,
			original -> {
				original.add(new TransactionalInteger(11));
				original.add(new TransactionalInteger(0));
				original.add(new TransactionalInteger(6));

				assertTransactionalObjArray(createIntegerArray(0, 1, 5, 6, 10, 11), original);
			},
			(original, committed) -> {
				assertNull(committed);
				assertTransactionalObjArray(createIntegerArray(1, 5, 10), original);
			}
		);

	}

	@Test
	void shouldCorrectlyRemoveItemsOnFirstLastAndMiddlePositionsAndRollback() {
		final TransactionalComplexObjArray<TransactionalInteger> array = new TransactionalComplexObjArray<>(createIntegerArray(1, 5, 10));

		assertStateAfterRollback(
			array,
			original -> {
				original.remove(new TransactionalInteger(5));

				assertTransactionalObjArray(createIntegerArray(1, 10), original);
			},
			(original, committed) -> {
				assertNull(committed);
				assertTransactionalObjArray(createIntegerArray(1, 5, 10), original);
			}
		);
	}

	@Test
	void shouldCorrectlyAddItemsOnFirstLastAndMiddlePositionsAndCommit() {
		final TransactionalComplexObjArray<TransactionalInteger> array = new TransactionalComplexObjArray<>(createIntegerArray(1, 5, 10));

		assertStateAfterCommit(
			array,
			original -> {
				original.add(new TransactionalInteger(11));
				original.add(new TransactionalInteger(11));
				original.add(new TransactionalInteger(0));
				original.add(new TransactionalInteger(6));

				assertTransactionalObjArray(createIntegerArray(0, 1, 5, 6, 10, 11), original);
			},
			(original, committed) -> {
				assertArrayEquals(committed, createIntegerArray(0, 1, 5, 6, 10, 11));
				assertTransactionalObjArray(createIntegerArray(1, 5, 10), original);
			}
		);
	}

	@Test
	void shouldCorrectlyRemoveItemsFromFirstLastAndMiddlePositionsAndCommit() {
		final TransactionalComplexObjArray<TransactionalInteger> array = new TransactionalComplexObjArray<>(createIntegerArray(1, 2, 5, 6, 10, 11));

		assertStateAfterCommit(
			array,
			original -> {
				original.remove(new TransactionalInteger(11));
				original.remove(new TransactionalInteger(1));
				original.remove(new TransactionalInteger(5));

				assertTransactionalObjArray(createIntegerArray(2, 6, 10), original);
			},
			(original, committed) -> {
				assertArrayEquals(committed, createIntegerArray(2, 6, 10));
				assertTransactionalObjArray(createIntegerArray(1, 2, 5, 6, 10, 11), original);
			}
		);
	}

	@Test
	void shouldAddAndRemoveEverythingAndCommit() {
		final TransactionalComplexObjArray<TransactionalInteger> array = new TransactionalComplexObjArray<>(new TransactionalInteger[0]);

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
		final TransactionalComplexObjArray<TransactionalInteger> array = new TransactionalComplexObjArray<>(createIntegerArray(1, 2, 5, 6, 10, 11));

		assertStateAfterCommit(
			array,
			original -> {
				original.remove(new TransactionalInteger(6));
				original.remove(new TransactionalInteger(5));
				original.remove(new TransactionalInteger(2));

				assertTransactionalObjArray(createIntegerArray(1, 10, 11), original);
			},
			(original, committed) -> {
				assertArrayEquals(committed, createIntegerArray(1, 10, 11));
				assertTransactionalObjArray(createIntegerArray(1, 2, 5, 6, 10, 11), original);
			}
		);
	}

	@Test
	void shouldCorrectlyRemoveMultipleItemsInARowTillTheEndAndCommit() {
		final TransactionalComplexObjArray<TransactionalInteger> array = new TransactionalComplexObjArray<>(createIntegerArray(1, 2, 5, 6, 10, 11));

		assertStateAfterCommit(
			array,
			original -> {
				original.remove(new TransactionalInteger(6));
				original.remove(new TransactionalInteger(5));
				original.remove(new TransactionalInteger(10));
				original.remove(new TransactionalInteger(11));

				assertTransactionalObjArray(createIntegerArray(1, 2), original);
			},
			(original, committed) -> {
				assertTransactionalObjArray(createIntegerArray(1, 2, 5, 6, 10, 11), original);
				assertArrayEquals(committed, createIntegerArray(1, 2));
			}
		);
	}

	@Test
	void shouldCorrectlyRemoveMultipleItemsInARowFromTheBeginningAndCommit() {
		final TransactionalComplexObjArray<TransactionalInteger> array = new TransactionalComplexObjArray<>(createIntegerArray(1, 2, 5, 6, 10, 11));

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
				assertTransactionalObjArray(createIntegerArray(1, 2, 5, 6, 10, 11), original);
				assertArrayEquals(committed, createIntegerArray(11));
			}
		);
	}

	@Test
	void shouldAddNothingAndCommit() {
		final TransactionalComplexObjArray<TransactionalInteger> array = new TransactionalComplexObjArray<>(createIntegerArray(1, 5, 10));

		assertStateAfterCommit(
			array,
			original -> {
				assertTransactionalObjArray(createIntegerArray(1, 5, 10), original);
			},
			(original, committed) -> {
				assertTransactionalObjArray(createIntegerArray(1, 5, 10), original);
				assertArrayEquals(committed, createIntegerArray(1, 5, 10));
			}
		);
	}

	@Test
	void shouldCorrectlyAddMultipleItemsOnSamePositionsAndCommit() {
		final TransactionalComplexObjArray<TransactionalInteger> array = new TransactionalComplexObjArray<>(createIntegerArray(1, 5, 10));

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

				assertTransactionalObjArray(createIntegerArray(0, 1, 2, 3, 5, 6, 7, 8, 10, 11, 12), original);
			},
			(original, committed) -> {
				assertTransactionalObjArray(createIntegerArray(1, 5, 10), original);
				assertArrayEquals(committed, createIntegerArray(0, 1, 2, 3, 5, 6, 7, 8, 10, 11, 12));
			}
		);
	}

	@Test
	void shouldCorrectlyAddAndRemoveOnNonOverlappingPositionsAndCommit() {
		final TransactionalComplexObjArray<TransactionalInteger> array = new TransactionalComplexObjArray<>(createIntegerArray(1, 2, 5, 6, 10, 11));

		assertStateAfterCommit(
			array,
			original -> {
				original.add(new TransactionalInteger(4));
				original.add(new TransactionalInteger(3));
				original.remove(new TransactionalInteger(10));
				original.remove(new TransactionalInteger(6));
				original.add(new TransactionalInteger(15));

				assertTransactionalObjArray(createIntegerArray(1, 2, 3, 4, 5, 11, 15), original);
			},
			(original, committed) -> {
				assertTransactionalObjArray(createIntegerArray(1, 2, 5, 6, 10, 11), original);
				assertArrayEquals(committed, createIntegerArray(1, 2, 3, 4, 5, 11, 15));
			}
		);
	}

	@Test
	void shouldCorrectlyAddAndRemoveSameNumberAndCommit() {
		final TransactionalComplexObjArray<TransactionalInteger> array = new TransactionalComplexObjArray<>(createIntegerArray(1, 2, 5, 6, 10, 11));

		assertStateAfterCommit(
			array,
			original -> {
				original.add(new TransactionalInteger(4));
				original.remove(new TransactionalInteger(4));

				assertTransactionalObjArray(createIntegerArray(1, 2, 5, 6, 10, 11), original);
			},
			(original, committed) -> {
				assertTransactionalObjArray(createIntegerArray(1, 2, 5, 6, 10, 11), original);
				assertArrayEquals(committed, createIntegerArray(1, 2, 5, 6, 10, 11));
			}
		);
	}

	@Test
	void shouldCorrectlyRemoveAndAddSameNumberAndCommit() {
		final TransactionalComplexObjArray<TransactionalInteger> array = new TransactionalComplexObjArray<>(createIntegerArray(1, 2, 5, 6, 10, 11));

		assertStateAfterCommit(
			array,
			original -> {
				original.remove(new TransactionalInteger(5));
				original.remove(new TransactionalInteger(10));
				original.remove(new TransactionalInteger(11));
				original.add(new TransactionalInteger(10));
				original.add(new TransactionalInteger(11));
				original.add(new TransactionalInteger(5));

				assertTransactionalObjArray(createIntegerArray(1, 2, 5, 6, 10, 11), original);
			},
			(original, committed) -> {
				assertTransactionalObjArray(createIntegerArray(1, 2, 5, 6, 10, 11), original);
				assertArrayEquals(committed, createIntegerArray(1, 2, 5, 6, 10, 11));
			}
		);

	}

	@Test
	void shouldCorrectlyAddAndRemoveOnOverlappingBoundaryPositionsAndCommit() {
		final TransactionalComplexObjArray<TransactionalInteger> array = new TransactionalComplexObjArray<>(createIntegerArray(1, 2, 5, 6, 10, 11));

		assertStateAfterCommit(
			array,
			original -> {
				original.remove(new TransactionalInteger(1));
				original.remove(new TransactionalInteger(11));
				original.add(new TransactionalInteger(0));
				original.add(new TransactionalInteger(12));

				assertTransactionalObjArray(createIntegerArray(0, 2, 5, 6, 10, 12), original);
			},
			(original, committed) -> {
				assertTransactionalObjArray(createIntegerArray(1, 2, 5, 6, 10, 11), original);
				assertArrayEquals(committed, createIntegerArray(0, 2, 5, 6, 10, 12));
			}
		);
	}

	@Test
	void shouldCorrectlyAddAndRemoveOnOverlappingMiddlePositionsAndCommit() {
		final TransactionalComplexObjArray<TransactionalInteger> array = new TransactionalComplexObjArray<>(createIntegerArray(1, 5, 8, 11));

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

				assertTransactionalObjArray(createIntegerArray(1, 6, 7, 8, 9, 10, 11), original);
			},
			(original, committed) -> {
				assertTransactionalObjArray(createIntegerArray(1, 5, 8, 11), original);
				assertArrayEquals(committed, createIntegerArray(1, 6, 7, 8, 9, 10, 11));
			}
		);
	}

	@Test
	void shouldProperlyHandleChangesOnSinglePositionWithInt() {
		final TransactionalComplexObjArray<TransactionalInteger> array = new TransactionalComplexObjArray<>(createIntegerArray(1, 2));

		assertStateAfterCommit(
			array,
			original -> {
				original.remove(new TransactionalInteger(1));
				original.remove(new TransactionalInteger(2));
				original.add(new TransactionalInteger(2));
				original.add(new TransactionalInteger(4));
				original.remove(new TransactionalInteger(2));
				original.add(new TransactionalInteger(5));

				assertTransactionalObjArray(createIntegerArray(4, 5), original);
			},
			(original, committed) -> {
				assertTransactionalObjArray(createIntegerArray(1, 2), original);
				assertArrayEquals(committed, createIntegerArray(4, 5));
			}
		);
	}

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
				final AtomicReference<DistinctValueHolder[]> nextArrayToCompare = new AtomicReference<>(testState.initialArray());

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
								final Integer[] restArray = sutbractArrays(removedItem.getValues(), removedRecId.getValues());
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

	private Integer[] sutbractArrays(TreeSet<Integer> subtractedArray, TreeSet<Integer> baseArray) {
		final TreeSet<Integer> baseArrayCopy = new TreeSet<>(baseArray);
		baseArrayCopy.removeAll(subtractedArray);
		return baseArrayCopy.toArray(new Integer[0]);
	}

	private Integer[] pickSomethingRandomlyFrom(Random rnd, TreeSet<Integer> values) {
		final TreeSet<Integer> newSet = new TreeSet<>(values);
		newSet.removeIf(it -> rnd.nextBoolean());
		return newSet.toArray(new Integer[0]);
	}

	private DistinctValueHolder mergeArrays(DistinctValueHolder upsertItem, DistinctValueHolder existingItem) {
		final Set<Integer> mergedValues = new TreeSet<>(existingItem.getValues());
		mergedValues.addAll(upsertItem.getValues());
		final Integer[] values = mergedValues.toArray(new Integer[0]);
		return new DistinctValueHolder(existingItem.getKey(), values);
	}

	private DistinctValueHolder[] generateRandomInitialArray(Random rnd, int count, int subCount) {
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

	private Integer[] generateRandomArray(Random rnd, int count) {
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

	private record TestState(
		StringBuilder code,
		DistinctValueHolder[] initialArray
	) {
	}

	private record TransactionalInteger(Integer object)
		implements TransactionalObject<TransactionalInteger, Void>, VoidTransactionMemoryProducer<TransactionalInteger>, Comparable<TransactionalInteger> {
		@Nonnull
		@Override
		public TransactionalInteger createCopyWithMergedTransactionalMemory(Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
			return this;
		}

		@Override
		public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {

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

	@Data
	private static class DistinctValueHolder implements TransactionalObject<DistinctValueHolder, Void>, VoidTransactionMemoryProducer<DistinctValueHolder>, Comparable<DistinctValueHolder> {
		private final String key;
		private final TreeSet<Integer> values = new TreeSet<>();

		DistinctValueHolder(String key, Integer... values) {
			this.key = key;
			Collections.addAll(this.values, values);
		}

		@Nonnull
		@Override
		public DistinctValueHolder createCopyWithMergedTransactionalMemory(Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
			return this;
		}

		@Override
		public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {

		}

		@Override
		public int compareTo(@Nonnull DistinctValueHolder o) {
			return this.key.compareTo(o.key);
		}

		@Nonnull
		@Override
		public DistinctValueHolder makeClone() {
			return new DistinctValueHolder(this.key, this.values.toArray(new Integer[0]));
		}

		@Override
		public String toString() {
			return this.key + ":" + this.values.stream().map(Object::toString).collect(Collectors.joining(","));
		}

		void combineWith(DistinctValueHolder otherHolder) {
			Assert.isTrue(this.key.equals(otherHolder.getKey()), "Keys are expected to be equal!");
			this.values.addAll(otherHolder.getValues());
		}

		boolean isEmpty() {
			return this.values.isEmpty();
		}

		void subtract(DistinctValueHolder otherHolder) {
			Assert.isTrue(this.key.equals(otherHolder.getKey()), "Keys are expected to be equal!");
			this.values.removeAll(otherHolder.getValues());
		}
	}

}
