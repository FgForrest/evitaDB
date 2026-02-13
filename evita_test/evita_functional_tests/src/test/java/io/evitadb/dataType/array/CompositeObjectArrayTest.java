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

package io.evitadb.dataType.array;

import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies the contract of {@link CompositeObjectArray}, covering construction,
 * element access, search, mutation, monotonicity tracking, append operations, conversion,
 * and both forward and reverse iteration.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("CompositeObjectArray")
class CompositeObjectArrayTest {

	/**
	 * A simple non-Comparable but Serializable type used for testing monotonicity behavior
	 * with types that do not implement {@link Comparable}.
	 */
	private static class NonComparableItem implements Serializable {
		@Serial
		private static final long serialVersionUID = 1L;
	}

	@Nested
	@DisplayName("Construction")
	class Construction {

		@Test
		@DisplayName("should create empty array with type only")
		void shouldCreateEmptyArrayWithTypeOnly() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			assertTrue(array.isEmpty());
			assertEquals(0, array.getSize());
		}

		@Test
		@DisplayName("should create array from varargs")
		void shouldCreateArrayFromVarargs() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(
				Integer.class, 1, 2, 3
			);
			assertEquals(3, array.getSize());
			assertEquals(1, array.get(0));
			assertEquals(2, array.get(1));
			assertEquals(3, array.get(2));
		}

		@Test
		@DisplayName("should create array with monotonicity tracking enabled")
		void shouldCreateArrayWithMonotonicityTracking() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(
				Integer.class, true
			);
			assertTrue(array.isMonotonic());
		}

		@Test
		@DisplayName("should create array with monotonicity tracking disabled")
		void shouldCreateArrayWithDisabledMonotonicity() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(
				Integer.class, false
			);
			assertFalse(array.isMonotonic());
		}

		@Test
		@DisplayName("should throw when tracking monotonicity on non-Comparable type")
		void shouldThrowWhenTrackingMonotonicityOnNonComparable() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new CompositeObjectArray<>(NonComparableItem.class, true)
			);
		}
	}

	@Nested
	@DisplayName("Static factory methods")
	class StaticFactoryMethods {

		@Test
		@DisplayName("should convert iterator to array")
		void shouldConvertIteratorToArray() {
			final Integer[] result = CompositeObjectArray.toArray(
				Integer.class, List.of(1, 2, 3).iterator()
			);
			assertArrayEquals(new Integer[]{1, 2, 3}, result);
		}

		@Test
		@DisplayName("should convert empty iterator to empty array")
		void shouldConvertEmptyIteratorToEmptyArray() {
			final Integer[] result = CompositeObjectArray.toArray(
				Integer.class, Collections.emptyIterator()
			);
			assertEquals(0, result.length);
		}
	}

	@Nested
	@DisplayName("Size and emptiness")
	class SizeAndEmptiness {

		@Test
		@DisplayName("should be empty when newly created")
		void shouldBeEmptyWhenNewlyCreated() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			assertTrue(array.isEmpty());
		}

		@Test
		@DisplayName("should not be empty after adding element")
		void shouldNotBeEmptyAfterAddingElement() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			array.add(42);
			assertFalse(array.isEmpty());
		}

		@Test
		@DisplayName("should return null for getLast when empty")
		void shouldReturnNullForGetLastWhenEmpty() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			assertNull(array.getLast());
		}

		@Test
		@DisplayName("should report correct size after adding many elements")
		void shouldReportCorrectSize() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			for (int i = 0; i < 100; i++) {
				array.add(i);
			}
			assertEquals(100, array.getSize());
		}
	}

	@Nested
	@DisplayName("Element access")
	class ElementAccess {

		@Test
		@DisplayName("should get element by index across chunks")
		void shouldGetElementByIndex() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			for (int i = 0; i < 100; i++) {
				array.add(i);
			}
			// verify various indices including cross-chunk boundary (CHUNK_SIZE=50)
			assertEquals(0, array.get(0));
			assertEquals(25, array.get(25));
			assertEquals(49, array.get(49));
			assertEquals(50, array.get(50));
			assertEquals(75, array.get(75));
			assertEquals(99, array.get(99));
		}

		@Test
		@DisplayName("should get last element")
		void shouldGetLastElement() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			array.add(10);
			array.add(20);
			array.add(30);
			assertEquals(30, array.getLast());
		}
	}

	@Nested
	@DisplayName("Search - contains")
	class Contains {

		@Test
		@DisplayName("should find value in monotonic array")
		void shouldFindValueInMonotonicArray() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			for (int i = 1; i <= 100; i++) {
				array.add(i);
			}
			assertTrue(array.isMonotonic());
			assertTrue(array.contains(50));
		}

		@Test
		@DisplayName("should not find missing value")
		void shouldNotFindMissingValue() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			for (int i = 1; i <= 100; i++) {
				array.add(i);
			}
			assertFalse(array.contains(999));
		}

		@Test
		@DisplayName("should not crash on partially filled monotonic chunk")
		void shouldNotCrashOnPartiallyFilledMonotonicChunk() {
			// With only 5 elements in a 50-slot chunk, trailing null slots caused NPE
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			for (int i = 1; i <= 5; i++) {
				array.add(i);
			}
			assertTrue(array.isMonotonic());
			// Searching for 100 - binary search probes middle of chunk where slots are null
			// Before fix: NPE. After fix: false
			assertFalse(array.contains(100));
		}

		@Test
		@DisplayName("should not match null in trailing slots of non-monotonic array")
		void shouldNotMatchNullInTrailingSlotsOfNonMonotonicArray() {
			// 53 descending elements (non-monotonic), partial last chunk has null trailing slots
			// contains() non-monotonic path must not scan beyond valid elements
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			for (int i = 53; i >= 1; i--) {
				array.add(i);
			}
			assertFalse(array.isMonotonic());
			assertEquals(53, array.getSize());
			// All values are 1..53, so 999 should not be found
			assertFalse(array.contains(999));
			// Verify all valid values are found
			assertTrue(array.contains(1));
			assertTrue(array.contains(53));
		}
	}

	@Nested
	@DisplayName("Search - indexOf")
	class IndexOf {

		@Test
		@DisplayName("should find index in monotonic array")
		void shouldFindIndexInMonotonicArray() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			for (int i = 0; i < 100; i++) {
				array.add(i);
			}
			assertTrue(array.isMonotonic());
			assertEquals(50, array.indexOf(50));
		}

		@Test
		@DisplayName("should return negative for missing value")
		void shouldReturnNegativeForMissing() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			for (int i = 0; i < 100; i++) {
				array.add(i);
			}
			assertEquals(-1, array.indexOf(999));
		}

		@Test
		@DisplayName("should not match null trailing slots in non-monotonic indexOf")
		void shouldNotMatchNullTrailingSlotsInNonMonotonicIndexOf() {
			// 53 descending elements (non-monotonic), partial last chunk has null trailing slots
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			for (int i = 53; i >= 1; i--) {
				array.add(i);
			}
			assertFalse(array.isMonotonic());
			assertEquals(53, array.getSize());
			// value 1 is at last position (index 52)
			assertEquals(52, array.indexOf(1));
			// value 999 not in array
			assertEquals(-1, array.indexOf(999));
		}

		@Test
		@DisplayName("should find index with custom comparator")
		void shouldFindIndexWithCustomComparator() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			for (int i = 0; i < 100; i++) {
				array.add(i * 2); // even numbers: 0,2,4,...,198
			}
			// Search for value 100 using a comparator that compares Integer to Integer
			final int index = array.indexOf(
				100, (element, target) -> Integer.compare(element, target)
			);
			assertEquals(50, index); // 100 is at index 50 (100/2)
		}

		@Test
		@DisplayName("should throw when using comparator on non-monotonic array")
		void shouldThrowWhenUsingComparatorOnNonMonotonic() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			array.add(3);
			array.add(1); // breaks monotonicity
			assertFalse(array.isMonotonic());
			assertThrows(
				GenericEvitaInternalError.class,
				() -> array.indexOf(1, (element, target) -> Integer.compare(element, target))
			);
		}
	}

	@Nested
	@DisplayName("Mutation - set")
	class Set {

		@Test
		@DisplayName("should set record on specific index")
		void shouldSetRecordOnSpecificIndex() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			for (int i = 0; i < 100; i++) {
				array.add(i);
			}
			array.set(999, 50);
			assertEquals(999, array.get(50));
		}

		@Test
		@DisplayName("should throw on out-of-bounds set")
		void shouldThrowOnOutOfBoundsSet() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			array.add(1);
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> array.set(999, 100)
			);
		}
	}

	@Nested
	@DisplayName("Monotonicity tracking")
	class MonotonicityTracking {

		@Test
		@DisplayName("should track monotonic for Comparable type")
		void shouldTrackMonotonicForComparableType() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			assertTrue(array.isMonotonic());
		}

		@Test
		@DisplayName("should not track monotonic for non-Comparable type")
		void shouldNotTrackMonotonicForNonComparableType() {
			final CompositeObjectArray<NonComparableItem> array = new CompositeObjectArray<>(
				NonComparableItem.class
			);
			assertFalse(array.isMonotonic());
		}

		@Test
		@DisplayName("should break monotonic on unsorted add")
		void shouldBreakMonotonicOnUnsortedAdd() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			array.add(3);
			assertTrue(array.isMonotonic());
			array.add(1); // out of order
			assertFalse(array.isMonotonic());
		}

		@Test
		@DisplayName("should maintain monotonic on sorted bulk add")
		void shouldMaintainMonotonicOnSortedBulkAdd() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			final Integer[] sorted = new Integer[]{1, 2, 3, 4, 5};
			array.addAll(sorted, 0, sorted.length);
			assertTrue(array.isMonotonic());
		}

		@Test
		@DisplayName("should break monotonic on unsorted bulk add")
		void shouldBreakMonotonicOnUnsortedBulkAdd() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			final Integer[] unsorted = new Integer[]{5, 3, 1, 4, 2};
			array.addAll(unsorted, 0, unsorted.length);
			assertFalse(array.isMonotonic());
		}

		@Test
		@DisplayName("should detect non-monotonic values in addAll with non-zero srcPosition")
		void shouldDetectNonMonotonicInAddAllWithNonZeroSrcPosition() {
			// Array: [0,1,...,99, 4, 101,...] with a dip at index 100
			// addAll(array, 95, 10) copies indices 95..104 which include the dip
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			final Integer[] source = new Integer[110];
			for (int i = 0; i < 110; i++) {
				source[i] = i;
			}
			source[100] = 4; // dip inside the range [95, 105)
			array.addAll(source, 95, 10);
			// The dip at index 100 (6th element in the copied range) must be detected
			assertFalse(array.isMonotonic());
		}
	}

	@Nested
	@DisplayName("Append - add and addAll")
	class AddAndAddAll {

		@Test
		@DisplayName("should make composite array by adding one element at a time")
		void shouldMakeCompositeArrayByOne() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);

			final int limit = 2_048;
			for (int i = 0; i < limit; i++) {
				array.add(i);
			}

			final Integer[] result = array.toArray();
			assertEquals(limit, result.length);

			for (int i = 0; i < result.length; i++) {
				assertEquals(i, result[i]);
			}
		}

		@Test
		@DisplayName("should make composite array by adding multiple elements at once")
		void shouldMakeCompositeArrayByMultiple() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);

			final int repetitions = 4;
			final int limit = 700;

			for (int j = 0; j < repetitions; j++) {
				final Integer[] arr = new Integer[limit];
				for (int i = 0; i < limit; i++) {
					arr[i] = j * limit + i;
				}
				array.addAll(arr, 0, limit);
			}

			final Integer[] result = array.toArray();
			assertEquals(repetitions * limit, result.length);

			for (int i = 0; i < result.length; i++) {
				assertEquals(i, result[i]);
			}
		}

		@Test
		@DisplayName("should add all from full array using single-arg addAll")
		void shouldAddAllFromFullArray() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			final Integer[] source = new Integer[]{10, 20, 30, 40, 50};
			array.addAll(source);

			assertEquals(5, array.getSize());
			for (int i = 0; i < source.length; i++) {
				assertEquals(source[i], array.get(i));
			}
		}

		@Test
		@DisplayName("should handle addAll with non-empty array but zero length")
		void shouldHandleAddAllWithNonEmptyArrayButZeroLength() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			array.add(1);
			array.addAll(new Integer[]{10, 20, 30}, 0, 0);
			// size should remain 1 - nothing was added
			assertEquals(1, array.getSize());
			assertEquals(1, array.get(0));
		}

		@Test
		@DisplayName("should add all across chunk boundary correctly")
		void shouldAddAllAcrossChunkBoundary() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			// fill 48 elements, then addAll 4 more to cross the chunk boundary at 50
			for (int i = 0; i < 48; i++) {
				array.add(i);
			}
			final Integer[] crossing = new Integer[]{48, 49, 50, 51};
			array.addAll(crossing, 0, crossing.length);

			assertEquals(52, array.getSize());
			assertEquals(48, array.get(48));
			assertEquals(49, array.get(49));
			assertEquals(50, array.get(50));
			assertEquals(51, array.get(51));
		}
	}

	@Nested
	@DisplayName("Conversion - toArray")
	class ToArray {

		@Test
		@DisplayName("should convert empty array to empty array")
		void shouldConvertEmptyArrayToEmptyArray() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			final Integer[] result = array.toArray();
			assertEquals(0, result.length);
		}

		@Test
		@DisplayName("should return correct type array")
		void shouldReturnCorrectTypeArray() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			array.add(1);
			final Integer[] result = array.toArray();
			// verify result is Integer[] not Object[]
			assertTrue(result instanceof Integer[]);
			assertEquals(1, result[0]);
		}
	}

	@Nested
	@DisplayName("Forward iterator")
	class ForwardIterator {

		@Test
		@DisplayName("should iterate empty array without elements")
		void shouldIterateEmptyArray() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			assertFalse(array.iterator().hasNext());
		}

		@Test
		@DisplayName("should iterate all elements in correct order")
		void shouldIterateAllElements() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);

			final int limit = 2_048;
			for (int i = 0; i < limit; i++) {
				array.add(i);
			}

			int cnt = 0;
			final Iterator<Integer> it = array.iterator();
			while (it.hasNext()) {
				final Integer next = it.next();
				assertEquals(cnt++, next);
			}
			assertEquals(limit, cnt);
		}

		@Test
		@DisplayName("should iterate from random index to end")
		void shouldIterateFromIndex() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);

			final int limit = 2_048;
			for (int i = 0; i < limit; i++) {
				array.add(i);
			}

			final Random rnd = new Random(42);
			for (int j = 0; j < 5; j++) {
				final int startIndex = rnd.nextInt(limit);
				int cnt = startIndex;
				final Iterator<Integer> it = array.iterator(startIndex);
				while (it.hasNext()) {
					final Integer next = it.next();
					assertEquals(cnt++, next);
				}
				assertEquals(limit, cnt);
			}
		}

		@Test
		@DisplayName("should iterate from chunk boundary indices")
		void shouldIterateFromChunkBoundary() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);

			final int limit = 200;
			for (int i = 0; i < limit; i++) {
				array.add(i);
			}

			// iterate from index 50 (start of second chunk)
			int cnt = 50;
			final Iterator<Integer> it50 = array.iterator(50);
			while (it50.hasNext()) {
				final Integer next = it50.next();
				assertEquals(cnt++, next);
			}
			assertEquals(limit, cnt);

			// iterate from index 100 (start of third chunk)
			cnt = 100;
			final Iterator<Integer> it100 = array.iterator(100);
			while (it100.hasNext()) {
				final Integer next = it100.next();
				assertEquals(cnt++, next);
			}
			assertEquals(limit, cnt);
		}

		@Test
		@DisplayName("should throw NoSuchElementException when iterator is exhausted at chunk boundary")
		void shouldThrowNoSuchElementWhenExhausted() {
			// fill exactly one full chunk (CHUNK_SIZE=50) so the iterator exhaustion
			// triggers the chunk boundary path that throws NoSuchElementException
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			for (int i = 0; i < 50; i++) {
				array.add(i);
			}
			final Iterator<Integer> it = array.iterator();
			while (it.hasNext()) {
				it.next();
			}
			assertFalse(it.hasNext());
			assertThrows(NoSuchElementException.class, it::next);
		}
	}

	@Nested
	@DisplayName("Reverse iterator")
	class ReverseIterator {

		@Test
		@DisplayName("should reverse iterate empty array without elements")
		void shouldReverseIterateEmptyArray() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			assertFalse(array.reverseIterator().hasNext());
		}

		@Test
		@DisplayName("should reverse iterate single element")
		void shouldReverseIterateSingleElement() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);
			array.add(1);
			final Iterator<Integer> it = array.reverseIterator();
			assertTrue(it.hasNext());
			assertEquals(1, it.next());
			assertFalse(it.hasNext());
		}

		@Test
		@DisplayName("should reverse iterate all elements in correct order")
		void shouldReverseIterateAllElements() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);

			final int limit = 2_048;
			for (int i = 0; i < limit; i++) {
				array.add(i);
			}

			int cnt = limit - 1;
			final Iterator<Integer> it = array.reverseIterator();
			while (it.hasNext()) {
				final Integer next = it.next();
				assertEquals(cnt--, next);
			}
			assertEquals(-1, cnt);
		}

		@Test
		@DisplayName("should reverse iterate from random index to beginning")
		void shouldReverseIterateFromIndex() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);

			final int limit = 2_048;
			for (int i = 0; i < limit; i++) {
				array.add(i);
			}

			final Random rnd = new Random(42);
			for (int j = 0; j < 5; j++) {
				final int startIndex = rnd.nextInt(limit);
				int cnt = startIndex - 1;
				final Iterator<Integer> it = array.reverseIterator(startIndex);
				while (it.hasNext()) {
					final Integer next = it.next();
					assertEquals(cnt--, next);
				}
				assertEquals(-1, cnt);
			}
		}

		@Test
		@DisplayName("should reverse iterate from chunk boundary index")
		void shouldReverseIterateFromChunkBoundary() {
			final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);

			final int limit = 200;
			for (int i = 0; i < limit; i++) {
				array.add(i);
			}

			// reverse iterate from index 50 (start of second chunk)
			// reverseIterator(50) iterates elements at indices 49,48,...,0
			int cnt = 49;
			final Iterator<Integer> it = array.reverseIterator(50);
			while (it.hasNext()) {
				final Integer next = it.next();
				assertEquals(cnt--, next);
			}
			assertEquals(-1, cnt);
		}
	}
}
