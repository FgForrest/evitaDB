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
import io.evitadb.utils.Assert;
import lombok.Data;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Tests for {@link TransactionalComplexObjArray} verifying non-transactional behaviour
 * where operations are applied directly to the delegate array without any
 * transactional layer involvement.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@DisplayName("Non-transactional complex object array")
class TransactionalComplexObjArrayNoTransactionTest {

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
	 * Collects all elements from an iterator into an array of the given type.
	 *
	 * @param expectedType the class of the array elements
	 * @param iterator     the iterator to drain
	 * @param <T>          the element type
	 * @return an array containing all elements from the iterator
	 */
	@Nonnull
	private static <T> T[] toArray(
		@Nonnull Class<T> expectedType,
		@Nonnull Iterator<T> iterator
	) {
		final List<T> result = new ArrayList<>();
		while (iterator.hasNext()) {
			result.add(iterator.next());
		}
		//noinspection unchecked
		return result.toArray((T[]) Array.newInstance(expectedType, 0));
	}

	/**
	 * Tests verifying non-transactional simple add/remove operations
	 * using {@link TransactionalInteger} elements directly on the delegate array.
	 */
	@Nested
	@DisplayName("Non-transactional simple operations")
	class NonTransactionalSimpleOperationsTest {

		@Test
		void shouldCorrectlyAddItemsOnFirstLastAndMiddlePositions() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(createIntegerArray(1, 5, 10));

			array.add(new TransactionalInteger(11));
			array.add(new TransactionalInteger(11));
			array.add(new TransactionalInteger(0));
			array.add(new TransactionalInteger(6));

			assertArrayEquals(
				createIntegerArray(0, 1, 5, 6, 10, 11), array.getArray()
			);
			assertArrayEquals(
				createIntegerArray(0, 1, 5, 6, 10, 11),
				toArray(TransactionalInteger.class, array.iterator())
			);

			assertArrayEquals(
				createIntegerArray(0, 1, 5, 6, 10, 11), array.getArray()
			);
			assertArrayEquals(
				createIntegerArray(0, 1, 5, 6, 10, 11),
				toArray(TransactionalInteger.class, array.iterator())
			);
		}

		@Test
		void shouldCorrectlyRemoveItemsFromFirstLastAndMiddlePositions() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(
					createIntegerArray(1, 2, 5, 6, 10, 11)
				);

			array.remove(new TransactionalInteger(11));
			array.remove(new TransactionalInteger(1));
			array.remove(new TransactionalInteger(5));

			assertArrayEquals(
				createIntegerArray(2, 6, 10), array.getArray()
			);
			assertArrayEquals(
				createIntegerArray(2, 6, 10),
				toArray(TransactionalInteger.class, array.iterator())
			);

			assertArrayEquals(
				createIntegerArray(2, 6, 10), array.getArray()
			);
			assertArrayEquals(
				createIntegerArray(2, 6, 10),
				toArray(TransactionalInteger.class, array.iterator())
			);
		}

		@Test
		void shouldCorrectlyRemoveMultipleItemsInARow() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(
					createIntegerArray(1, 2, 5, 6, 10, 11)
				);

			array.remove(new TransactionalInteger(6));
			array.remove(new TransactionalInteger(5));
			array.remove(new TransactionalInteger(2));

			assertArrayEquals(
				createIntegerArray(1, 10, 11), array.getArray()
			);
			assertArrayEquals(
				createIntegerArray(1, 10, 11),
				toArray(TransactionalInteger.class, array.iterator())
			);

			assertArrayEquals(
				createIntegerArray(1, 10, 11), array.getArray()
			);
			assertArrayEquals(
				createIntegerArray(1, 10, 11),
				toArray(TransactionalInteger.class, array.iterator())
			);
		}

		@Test
		void shouldCorrectlyRemoveMultipleItemsInARowTillTheEnd() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(
					createIntegerArray(1, 2, 5, 6, 10, 11)
				);

			array.remove(new TransactionalInteger(6));
			array.remove(new TransactionalInteger(5));
			array.remove(new TransactionalInteger(10));
			array.remove(new TransactionalInteger(11));

			assertArrayEquals(
				createIntegerArray(1, 2), array.getArray()
			);
			assertArrayEquals(
				createIntegerArray(1, 2),
				toArray(TransactionalInteger.class, array.iterator())
			);

			assertArrayEquals(
				createIntegerArray(1, 2), array.getArray()
			);
			assertArrayEquals(
				createIntegerArray(1, 2),
				toArray(TransactionalInteger.class, array.iterator())
			);
		}

		@Test
		void shouldCorrectlyRemoveMultipleItemsInARowFromTheBeginning() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(
					createIntegerArray(1, 2, 5, 6, 10, 11)
				);

			array.remove(new TransactionalInteger(2));
			array.remove(new TransactionalInteger(6));
			array.remove(new TransactionalInteger(5));
			array.remove(new TransactionalInteger(1));
			array.remove(new TransactionalInteger(10));

			assertArrayEquals(
				createIntegerArray(11), array.getArray()
			);
			assertArrayEquals(
				createIntegerArray(11),
				toArray(TransactionalInteger.class, array.iterator())
			);

			assertArrayEquals(
				createIntegerArray(11), array.getArray()
			);
			assertArrayEquals(
				createIntegerArray(11),
				toArray(TransactionalInteger.class, array.iterator())
			);
		}

		@Test
		void shouldReturnUnchangedArrayWhenNothingAdded() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(createIntegerArray(1, 5, 10));

			assertArrayEquals(
				createIntegerArray(1, 5, 10), array.getArray()
			);
			assertArrayEquals(
				createIntegerArray(1, 5, 10),
				toArray(TransactionalInteger.class, array.iterator())
			);

			assertArrayEquals(
				createIntegerArray(1, 5, 10), array.getArray()
			);
			assertArrayEquals(
				createIntegerArray(1, 5, 10),
				toArray(TransactionalInteger.class, array.iterator())
			);
		}

		@Test
		void shouldCorrectlyAddMultipleItemsOnSamePositions() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(createIntegerArray(1, 5, 10));

			array.add(new TransactionalInteger(11));
			array.add(new TransactionalInteger(6));
			array.add(new TransactionalInteger(0));
			array.add(new TransactionalInteger(3));
			array.add(new TransactionalInteger(3));
			array.add(new TransactionalInteger(7));
			array.add(new TransactionalInteger(12));
			array.add(new TransactionalInteger(2));
			array.add(new TransactionalInteger(8));

			assertArrayEquals(
				createIntegerArray(0, 1, 2, 3, 5, 6, 7, 8, 10, 11, 12),
				array.getArray()
			);
			assertArrayEquals(
				createIntegerArray(0, 1, 2, 3, 5, 6, 7, 8, 10, 11, 12),
				toArray(TransactionalInteger.class, array.iterator())
			);

			assertArrayEquals(
				createIntegerArray(0, 1, 2, 3, 5, 6, 7, 8, 10, 11, 12),
				array.getArray()
			);
			assertArrayEquals(
				createIntegerArray(0, 1, 2, 3, 5, 6, 7, 8, 10, 11, 12),
				toArray(TransactionalInteger.class, array.iterator())
			);
		}

		@Test
		void shouldCorrectlyAddAndRemoveOnNonOverlappingPositions() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(
					createIntegerArray(1, 2, 5, 6, 10, 11)
				);

			array.add(new TransactionalInteger(4));
			array.add(new TransactionalInteger(3));
			array.remove(new TransactionalInteger(10));
			array.remove(new TransactionalInteger(6));
			array.add(new TransactionalInteger(15));

			assertArrayEquals(
				createIntegerArray(1, 2, 3, 4, 5, 11, 15), array.getArray()
			);
			assertArrayEquals(
				createIntegerArray(1, 2, 3, 4, 5, 11, 15),
				toArray(TransactionalInteger.class, array.iterator())
			);

			assertArrayEquals(
				createIntegerArray(1, 2, 3, 4, 5, 11, 15), array.getArray()
			);
			assertArrayEquals(
				createIntegerArray(1, 2, 3, 4, 5, 11, 15),
				toArray(TransactionalInteger.class, array.iterator())
			);
		}

		@Test
		void shouldCorrectlyAddAndRemoveSameNumber() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(
					createIntegerArray(1, 2, 5, 6, 10, 11)
				);

			array.add(new TransactionalInteger(4));
			array.remove(new TransactionalInteger(4));

			assertArrayEquals(
				createIntegerArray(1, 2, 5, 6, 10, 11), array.getArray()
			);
			assertArrayEquals(
				createIntegerArray(1, 2, 5, 6, 10, 11),
				toArray(TransactionalInteger.class, array.iterator())
			);

			assertArrayEquals(
				createIntegerArray(1, 2, 5, 6, 10, 11), array.getArray()
			);
			assertArrayEquals(
				createIntegerArray(1, 2, 5, 6, 10, 11),
				toArray(TransactionalInteger.class, array.iterator())
			);
		}

		@Test
		void shouldCorrectlyRemoveAndAddSameNumber() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(
					createIntegerArray(1, 2, 5, 6, 10, 11)
				);

			array.remove(new TransactionalInteger(5));
			array.remove(new TransactionalInteger(10));
			array.remove(new TransactionalInteger(11));
			array.add(new TransactionalInteger(10));
			array.add(new TransactionalInteger(11));
			array.add(new TransactionalInteger(5));

			assertArrayEquals(
				createIntegerArray(1, 2, 5, 6, 10, 11), array.getArray()
			);
			assertArrayEquals(
				createIntegerArray(1, 2, 5, 6, 10, 11),
				toArray(TransactionalInteger.class, array.iterator())
			);

			assertArrayEquals(
				createIntegerArray(1, 2, 5, 6, 10, 11), array.getArray()
			);
			assertArrayEquals(
				createIntegerArray(1, 2, 5, 6, 10, 11),
				toArray(TransactionalInteger.class, array.iterator())
			);
		}

		@Test
		void shouldCorrectlyAddAndRemoveOnOverlappingBoundaryPositions() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(
					createIntegerArray(1, 2, 5, 6, 10, 11)
				);

			array.remove(new TransactionalInteger(1));
			array.remove(new TransactionalInteger(11));
			array.add(new TransactionalInteger(0));
			array.add(new TransactionalInteger(12));

			assertArrayEquals(
				createIntegerArray(0, 2, 5, 6, 10, 12), array.getArray()
			);
			assertArrayEquals(
				createIntegerArray(0, 2, 5, 6, 10, 12),
				toArray(TransactionalInteger.class, array.iterator())
			);

			assertArrayEquals(
				createIntegerArray(0, 2, 5, 6, 10, 12), array.getArray()
			);
			assertArrayEquals(
				createIntegerArray(0, 2, 5, 6, 10, 12),
				toArray(TransactionalInteger.class, array.iterator())
			);
		}

	}

	/**
	 * Tests verifying non-transactional combine/reduce operations
	 * using {@link DistinctValueHolder} elements with inner value merging
	 * and subtraction applied directly to the delegate array.
	 */
	@Nested
	@DisplayName("Non-transactional combine/reduce operations")
	class NonTransactionalCombineReduceOperationsTest {

		@Test
		void shouldCorrectlyMergeInnerValuesOnAddToEmptyArray() {
			final TransactionalComplexObjArray<DistinctValueHolder> array =
				new TransactionalComplexObjArray<>(
					new DistinctValueHolder[0],
					DistinctValueHolder::combineWith,
					DistinctValueHolder::subtract,
					DistinctValueHolder::isEmpty,
					DistinctValueHolder::equals
				);

			array.add(new DistinctValueHolder("A", 1, 2));
			array.add(new DistinctValueHolder("B", 8));
			array.add(new DistinctValueHolder("A", 3, 4));
			array.add(new DistinctValueHolder("C", 5, 6));

			final DistinctValueHolder[] expectedResult = {
				new DistinctValueHolder("A", 1, 2, 3, 4),
				new DistinctValueHolder("B", 8),
				new DistinctValueHolder("C", 5, 6)
			};

			assertArrayEquals(expectedResult, array.getArray());
			assertArrayEquals(
				expectedResult,
				toArray(DistinctValueHolder.class, array.iterator())
			);

			assertArrayEquals(expectedResult, array.getArray());
			assertArrayEquals(
				expectedResult,
				toArray(DistinctValueHolder.class, array.iterator())
			);
		}

		@Test
		void shouldCorrectlyMergeInnerValuesOnAddToFilledArray() {
			final TransactionalComplexObjArray<DistinctValueHolder> array =
				new TransactionalComplexObjArray<>(
					new DistinctValueHolder[]{
						new DistinctValueHolder("A", 1, 2),
						new DistinctValueHolder("B", 8)
					},
					DistinctValueHolder::combineWith,
					DistinctValueHolder::subtract,
					DistinctValueHolder::isEmpty,
					DistinctValueHolder::equals
				);

			array.add(new DistinctValueHolder("A", 3, 4));
			array.add(new DistinctValueHolder("C", 5, 6));

			final DistinctValueHolder[] expectedResult = {
				new DistinctValueHolder("A", 1, 2, 3, 4),
				new DistinctValueHolder("B", 8),
				new DistinctValueHolder("C", 5, 6)
			};

			assertArrayEquals(expectedResult, array.getArray());
			assertArrayEquals(
				expectedResult,
				toArray(DistinctValueHolder.class, array.iterator())
			);

			assertArrayEquals(expectedResult, array.getArray());
			assertArrayEquals(
				expectedResult,
				toArray(DistinctValueHolder.class, array.iterator())
			);
		}

		@Test
		void shouldCorrectlyMergeInnerValuesOnRemovalFromFilledArray() {
			final TransactionalComplexObjArray<DistinctValueHolder> array =
				new TransactionalComplexObjArray<>(
					new DistinctValueHolder[]{
						new DistinctValueHolder("A", 1, 2, 3),
						new DistinctValueHolder("B", 4),
						new DistinctValueHolder("C", 5, 6)
					},
					DistinctValueHolder::combineWith,
					DistinctValueHolder::subtract,
					DistinctValueHolder::isEmpty,
					DistinctValueHolder::equals
				);

			array.remove(new DistinctValueHolder("A", 1));
			array.remove(new DistinctValueHolder("A", 2));
			array.remove(new DistinctValueHolder("C", 5, 6));

			final DistinctValueHolder[] expectedResult = {
				new DistinctValueHolder("A", 3),
				new DistinctValueHolder("B", 4)
			};

			assertArrayEquals(expectedResult, array.getArray());
			assertArrayEquals(
				expectedResult,
				toArray(DistinctValueHolder.class, array.iterator())
			);

			assertArrayEquals(expectedResult, array.getArray());
			assertArrayEquals(
				expectedResult,
				toArray(DistinctValueHolder.class, array.iterator())
			);
		}

		@Test
		void shouldCorrectlyMergeInnerValuesOnRemovalFromFilledArrayWithAddedValues() {
			final TransactionalComplexObjArray<DistinctValueHolder> array =
				new TransactionalComplexObjArray<>(
					new DistinctValueHolder[]{
						new DistinctValueHolder("A", 1, 2),
						new DistinctValueHolder("B", 3),
						new DistinctValueHolder("C", 4)
					},
					DistinctValueHolder::combineWith,
					DistinctValueHolder::subtract,
					DistinctValueHolder::isEmpty,
					DistinctValueHolder::equals
				);

			array.add(new DistinctValueHolder("A", 3));
			array.add(new DistinctValueHolder("C", 5, 6));
			array.add(new DistinctValueHolder("D", 7));
			array.add(new DistinctValueHolder("E", 8));
			array.add(new DistinctValueHolder("F", 9));
			array.remove(new DistinctValueHolder("A", 1));
			array.remove(new DistinctValueHolder("A", 2));
			array.remove(new DistinctValueHolder("C", 4));
			array.remove(new DistinctValueHolder("D", 7));

			final DistinctValueHolder[] expectedResult = {
				new DistinctValueHolder("A", 3),
				new DistinctValueHolder("B", 3),
				new DistinctValueHolder("C", 5, 6),
				new DistinctValueHolder("E", 8),
				new DistinctValueHolder("F", 9),
			};

			assertArrayEquals(expectedResult, array.getArray());
			assertArrayEquals(
				expectedResult,
				toArray(DistinctValueHolder.class, array.iterator())
			);

			assertArrayEquals(expectedResult, array.getArray());
			assertArrayEquals(
				expectedResult,
				toArray(DistinctValueHolder.class, array.iterator())
			);
		}

		@Test
		void shouldLeaveArrayEmptyWhenInsertionsAndRemovalsAreMatching() {
			final TransactionalComplexObjArray<DistinctValueHolder> array =
				new TransactionalComplexObjArray<>(
					new DistinctValueHolder[0],
					DistinctValueHolder::combineWith,
					DistinctValueHolder::subtract,
					DistinctValueHolder::isEmpty,
					DistinctValueHolder::equals
				);

			array.add(new DistinctValueHolder("A", 1, 2));
			array.remove(new DistinctValueHolder("A", 1));
			array.remove(new DistinctValueHolder("A", 2));
			array.add(new DistinctValueHolder("B", 3));
			array.remove(new DistinctValueHolder("B", 3));
			array.add(new DistinctValueHolder("C", 4, 5));
			array.remove(new DistinctValueHolder("C", 4, 5));
			array.add(new DistinctValueHolder("D", 4));
			array.remove(new DistinctValueHolder("D", 4));

			final DistinctValueHolder[] expectedResult = new DistinctValueHolder[0];

			assertArrayEquals(expectedResult, array.getArray());
			assertArrayEquals(
				expectedResult,
				toArray(DistinctValueHolder.class, array.iterator())
			);

			assertArrayEquals(expectedResult, array.getArray());
			assertArrayEquals(
				expectedResult,
				toArray(DistinctValueHolder.class, array.iterator())
			);
		}

		@Test
		void shouldCorrectlyAddAndRemoveOnOverlappingMiddlePositions() {
			final TransactionalComplexObjArray<TransactionalInteger> array =
				new TransactionalComplexObjArray<>(
					createIntegerArray(1, 5, 8, 11)
				);

			array.remove(new TransactionalInteger(5));
			array.remove(new TransactionalInteger(8));
			array.add(new TransactionalInteger(6));
			array.add(new TransactionalInteger(7));
			array.add(new TransactionalInteger(8));
			array.add(new TransactionalInteger(9));
			array.add(new TransactionalInteger(10));

			assertArrayEquals(
				createIntegerArray(1, 6, 7, 8, 9, 10, 11), array.getArray()
			);
			assertArrayEquals(
				createIntegerArray(1, 6, 7, 8, 9, 10, 11),
				toArray(TransactionalInteger.class, array.iterator())
			);

			assertArrayEquals(
				createIntegerArray(1, 6, 7, 8, 9, 10, 11), array.getArray()
			);
			assertArrayEquals(
				createIntegerArray(1, 6, 7, 8, 9, 10, 11),
				toArray(TransactionalInteger.class, array.iterator())
			);
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

		@Nonnull
		@Override
		public TransactionalInteger makeClone() {
			return new TransactionalInteger(this.object);
		}

		@Override
		public int compareTo(@Nonnull TransactionalInteger o) {
			return Integer.compare(this.object, o.object);
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

		/**
		 * Merges values from another holder into this holder.
		 *
		 * @param otherHolder the holder whose values to merge
		 */
		void combineWith(@Nonnull DistinctValueHolder otherHolder) {
			Assert.isTrue(
				this.key.equals(otherHolder.getKey()),
				"Keys must be equal!"
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
				"Keys must be equal!"
			);
			this.values.removeAll(otherHolder.getValues());
		}
	}

}
