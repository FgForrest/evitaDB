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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;
import java.util.PrimitiveIterator.OfLong;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies the contract of {@link CompositeLongArray}, including construction,
 * element access, range access, monotonicity tracking, search operations, mutations,
 * iteration, and equality semantics. Special attention is paid to chunk boundary
 * behavior (CHUNK_SIZE = 50) and trailing zero slots in partially filled chunks.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("CompositeLongArray")
class CompositeLongArrayTest {

	@Nested
	@DisplayName("Construction")
	class Construction {

		@Test
		@DisplayName("Should create empty array with default constructor")
		void shouldCreateEmptyArrayWithDefaultConstructor() {
			final CompositeLongArray array = new CompositeLongArray();
			assertEquals(0, array.getSize());
			assertTrue(array.isEmpty());
		}

		@Test
		@DisplayName("Should create array from varargs")
		void shouldCreateArrayFromVarargs() {
			final CompositeLongArray array = new CompositeLongArray(10L, 20L, 30L);
			assertEquals(3, array.getSize());
			assertEquals(10L, array.get(0));
			assertEquals(20L, array.get(1));
			assertEquals(30L, array.get(2));
		}
	}

	@Nested
	@DisplayName("Static factory methods")
	class StaticFactoryMethods {

		@Test
		@DisplayName("Should convert iterator to array")
		void shouldConvertIteratorToArray() {
			final int[] source = {5, 10, 15, 20, 25};
			final PrimitiveIterator.OfInt iterator = new PrimitiveIterator.OfInt() {
				private int index = 0;

				@Override
				public int nextInt() {
					return source[index++];
				}

				@Override
				public boolean hasNext() {
					return index < source.length;
				}
			};

			final long[] result = CompositeLongArray.toArray(iterator);
			assertArrayEquals(new long[]{5L, 10L, 15L, 20L, 25L}, result);
		}

		@Test
		@DisplayName("Should convert empty iterator to empty array")
		void shouldConvertEmptyIteratorToEmptyArray() {
			final PrimitiveIterator.OfInt emptyIterator = new PrimitiveIterator.OfInt() {
				@Override
				public int nextInt() {
					throw new NoSuchElementException();
				}

				@Override
				public boolean hasNext() {
					return false;
				}
			};

			final long[] result = CompositeLongArray.toArray(emptyIterator);
			assertEquals(0, result.length);
		}
	}

	@Nested
	@DisplayName("Size and emptiness")
	class SizeAndEmptiness {

		@Test
		@DisplayName("Should be empty when newly created")
		void shouldBeEmptyWhenNewlyCreated() {
			final CompositeLongArray array = new CompositeLongArray();
			assertTrue(array.isEmpty());
			assertEquals(0, array.getSize());
		}

		@Test
		@DisplayName("Should not be empty after adding element")
		void shouldNotBeEmptyAfterAddingElement() {
			final CompositeLongArray array = new CompositeLongArray();
			array.add(42L);
			assertFalse(array.isEmpty());
			assertEquals(1, array.getSize());
		}

		@Test
		@DisplayName("Should report correct size at chunk boundaries")
		void shouldReportCorrectSizeAtChunkBoundaries() {
			final CompositeLongArray array = new CompositeLongArray();

			// fill to 49 elements (one less than CHUNK_SIZE)
			for (int i = 0; i < 49; i++) {
				array.add(i);
			}
			assertEquals(49, array.getSize());

			// fill to exactly 50 (CHUNK_SIZE)
			array.add(49);
			assertEquals(50, array.getSize());

			// fill to 51 (one past CHUNK_SIZE, triggers new chunk)
			array.add(50);
			assertEquals(51, array.getSize());

			// fill to exactly 100 (two full chunks)
			for (int i = 51; i < 100; i++) {
				array.add(i);
			}
			assertEquals(100, array.getSize());
		}
	}

	@Nested
	@DisplayName("Element access")
	class ElementAccess {

		@Test
		@DisplayName("Should get elements by index across multiple chunks")
		void shouldGetElementsByIndex() {
			final CompositeLongArray array = new CompositeLongArray();
			final int limit = 2_048;
			for (int i = 0; i < limit; i++) {
				array.add(i);
			}

			assertEquals(limit, array.getSize());

			for (int i = 0; i < limit; i++) {
				assertEquals(i, array.get(i));
			}
		}

		@Test
		@DisplayName("Should get last element")
		void shouldGetLastElement() {
			final CompositeLongArray array = new CompositeLongArray();
			array.add(100L);
			assertEquals(100L, array.getLast());

			array.add(200L);
			assertEquals(200L, array.getLast());

			// add enough to cross a chunk boundary
			for (int i = 0; i < 50; i++) {
				array.add(i + 300L);
			}
			assertEquals(349L, array.getLast());
		}

		@Test
		@DisplayName("Should throw when getting last from empty array")
		void shouldThrowWhenGettingLastFromEmptyArray() {
			final CompositeLongArray array = new CompositeLongArray();
			assertThrows(NoSuchElementException.class, array::getLast);
		}

		@Test
		@DisplayName("Should throw when get exceeds bounds")
		void shouldThrowWhenGetExceedsBounds() {
			final CompositeLongArray array = new CompositeLongArray();
			array.add(1L);
			array.add(2L);

			// index 2 is out of bounds for size 2
			assertThrows(Exception.class, () -> array.get(100));
		}
	}

	@Nested
	@DisplayName("Range access")
	class RangeAccess {

		@Test
		@DisplayName("Should return range of elements")
		void shouldReturnRange() {
			final CompositeLongArray tested = new CompositeLongArray();
			final int limit = 1900;
			for (int i = 0; i < limit; i++) {
				tested.add(i);
			}
			assertArrayEquals(
				new long[]{2, 3, 4},
				tested.getRange(2, 5)
			);
			assertArrayEquals(
				new long[]{510, 511, 512, 513, 514, 515, 516, 517, 518, 519},
				tested.getRange(510, 520)
			);
			assertArrayEquals(tested.toArray(), tested.getRange(0, tested.getSize()));
		}

		@Test
		@DisplayName("Should return range spanning chunk boundary")
		void shouldReturnRangeSpanningChunkBoundary() {
			// CHUNK_SIZE = 50, so index 49 is last in chunk 0, index 50 is first in chunk 1
			final CompositeLongArray tested = new CompositeLongArray();
			for (int i = 0; i < 100; i++) {
				tested.add(i * 10L);
			}

			// range crossing boundary at index 49-50
			final long[] range = tested.getRange(48, 53);
			assertArrayEquals(new long[]{480, 490, 500, 510, 520}, range);
		}

		@Test
		@DisplayName("Should fail when range exceeds size")
		void shouldFailWhenRangeExceedsSize() {
			final CompositeLongArray tested = new CompositeLongArray();
			tested.addAll(new long[]{5, 8, 10, 3, 12, 15, 20, 23}, 0, 8);
			assertThrows(
				ArrayIndexOutOfBoundsException.class,
				() -> tested.getRange(7, 10)
			);
		}
	}

	@Nested
	@DisplayName("Monotonicity tracking")
	class MonotonicityTracking {

		@Test
		@DisplayName("Should maintain monotonic row for strictly increasing values")
		void shouldMaintainMonotonicRow() {
			final CompositeLongArray array = new CompositeLongArray();
			array.add(1);
			array.add(3);
			array.add(4);
			array.add(5);
			array.add(10);
			assertTrue(array.isMonotonic());
		}

		@Test
		@DisplayName("Should not maintain monotonic row on duplicate value")
		void shouldNotMaintainMonotonicRowOnDuplicateValue() {
			final CompositeLongArray array = new CompositeLongArray();
			array.add(1);
			array.add(3);
			array.add(4);
			array.add(4);
			array.add(10);
			assertFalse(array.isMonotonic());
		}

		@Test
		@DisplayName("Should not maintain monotonic row on lower value")
		void shouldNotMaintainMonotonicRowOnLowerValue() {
			final CompositeLongArray array = new CompositeLongArray();
			array.add(1);
			array.add(3);
			array.add(4);
			array.add(3);
			array.add(10);
			assertFalse(array.isMonotonic());
		}

		@Test
		@DisplayName("Should maintain monotonic on bulk add of increasing values")
		void shouldMaintainMonotonicOnBulkAdd() {
			final CompositeLongArray array = new CompositeLongArray();
			final long[] addedArray = new long[1050];
			for (int i = 0; i < 1050; i++) {
				addedArray[i] = i;
			}
			array.addAll(addedArray, 0, 1050);
			assertTrue(array.isMonotonic());
		}

		@Test
		@DisplayName("Should not maintain monotonic on bulk add with lowered value")
		void shouldNotMaintainMonotonicOnBulkAddWithLoweredValue() {
			final CompositeLongArray array = new CompositeLongArray();
			final long[] addedArray = new long[1050];
			for (int i = 0; i < 1050; i++) {
				addedArray[i] = i;
			}
			addedArray[1000] = 4;
			array.addAll(addedArray, 0, 1050);
			assertFalse(array.isMonotonic());
		}

		@Test
		@DisplayName(
			"Should maintain monotonic when lowered value is outside added range"
		)
		void shouldMaintainMonotonicWhenLoweredValueOutsideRange() {
			final CompositeLongArray array = new CompositeLongArray();
			final long[] addedArray = new long[1050];
			for (int i = 0; i < 1050; i++) {
				addedArray[i] = i;
			}
			addedArray[10] = 4;
			// only add from index 100, so the lowered value at index 10 is outside
			array.addAll(addedArray, 100, 950);
			assertTrue(array.isMonotonic());
		}

		@Test
		@DisplayName("Should detect non-monotonic values in addAll with non-zero srcPosition")
		void shouldDetectNonMonotonicInAddAllWithNonZeroSrcPosition() {
			// Array: [0,1,...,99, 4, 101,...] with a dip at index 100
			// addAll(array, 95, 10) copies indices 95..104 which include the dip
			final CompositeLongArray array = new CompositeLongArray();
			final long[] source = new long[110];
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
	@DisplayName("Search - contains")
	class Contains {

		@Test
		@DisplayName("Should find value in monotonic array")
		void shouldFindValueInMonotonicArray() {
			final CompositeLongArray array = new CompositeLongArray();
			final long[] addedArray = new long[1050];
			for (int i = 0; i < 1050; i++) {
				addedArray[i] = i;
			}
			array.addAll(addedArray, 0, 1050);
			assertTrue(array.isMonotonic());
			assertTrue(array.contains(0));
			assertTrue(array.contains(999));
			assertTrue(array.contains(1049));
		}

		@Test
		@DisplayName("Should not find missing value in monotonic array")
		void shouldNotFindMissingValueInMonotonicArray() {
			final CompositeLongArray array = new CompositeLongArray();
			final long[] addedArray = new long[1050];
			for (int i = 0; i < 1050; i++) {
				addedArray[i] = i;
			}
			array.addAll(addedArray, 0, 1050);
			assertTrue(array.isMonotonic());
			assertFalse(array.contains(-1));
			assertFalse(array.contains(1050));
			assertFalse(array.contains(9999));
		}

		@Test
		@DisplayName("Should find value in non-monotonic array")
		void shouldFindValueInNonMonotonicArray() {
			final CompositeLongArray array = new CompositeLongArray();
			final long[] addedArray = new long[1050];
			int index = 0;
			for (int i = 1049; i >= 0; i--) {
				addedArray[index++] = i;
			}
			array.addAll(addedArray, 0, 1050);
			assertFalse(array.isMonotonic());
			assertTrue(array.contains(50));
			assertTrue(array.contains(0));
			assertTrue(array.contains(1049));
		}

		@Test
		@DisplayName("Should not find missing value in non-monotonic array")
		void shouldNotFindMissingValueInNonMonotonicArray() {
			final CompositeLongArray array = new CompositeLongArray();
			final long[] addedArray = new long[1050];
			int index = 0;
			for (int i = 1049; i >= 0; i--) {
				addedArray[index++] = i;
			}
			array.addAll(addedArray, 0, 1050);
			assertFalse(array.isMonotonic());
			assertFalse(array.contains(-1));
			assertFalse(array.contains(1050));
		}

		@Test
		@DisplayName(
			"Should not find zero in trailing slots of monotonic array"
		)
		void shouldNotFindZeroInTrailingSlotsOfMonotonicArray() {
			// 53 elements from 1..53: chunk[0]=[1..50], chunk[1]=[51,52,53,0,...,0]
			// trailing zeros must not be found
			final CompositeLongArray array = new CompositeLongArray();
			for (int i = 1; i <= 53; i++) {
				array.add(i);
			}
			assertTrue(array.isMonotonic());
			assertEquals(53, array.getSize());
			assertFalse(array.contains(0));
		}

		@Test
		@DisplayName(
			"Should not find zero in trailing slots of non-monotonic array"
		)
		void shouldNotFindZeroInTrailingSlotsOfNonMonotonicArray() {
			// 53 descending elements from 53 to 1 (all positive, no zeros)
			final CompositeLongArray array = new CompositeLongArray();
			for (int i = 53; i >= 1; i--) {
				array.add(i);
			}
			assertFalse(array.isMonotonic());
			assertEquals(53, array.getSize());
			assertFalse(array.contains(0));
		}
	}

	@Nested
	@DisplayName("Search - indexOf")
	class IndexOf {

		@Test
		@DisplayName("Should find index in monotonic array")
		void shouldFindIndexInMonotonicArray() {
			final CompositeLongArray array = new CompositeLongArray();
			final long[] addedArray = new long[1050];
			for (int i = 0; i < 1050; i++) {
				addedArray[i] = i;
			}
			array.addAll(addedArray, 0, 1050);
			assertTrue(array.isMonotonic());
			assertEquals(0, array.indexOf(0));
			assertEquals(999, array.indexOf(999));
			assertEquals(1049, array.indexOf(1049));
		}

		@Test
		@DisplayName(
			"Should return negative for missing value in monotonic array"
		)
		void shouldReturnNegativeForMissingInMonotonicArray() {
			final CompositeLongArray array = new CompositeLongArray();
			final long[] addedArray = new long[1050];
			for (int i = 0; i < 1050; i++) {
				addedArray[i] = i;
			}
			array.addAll(addedArray, 0, 1050);
			assertTrue(array.isMonotonic());
			assertTrue(array.indexOf(1050) < 0);
			assertTrue(array.indexOf(9999) < 0);
		}

		@Test
		@DisplayName("Should find index in non-monotonic array")
		void shouldFindIndexInNonMonotonicArray() {
			final CompositeLongArray array = new CompositeLongArray();
			final long[] addedArray = new long[1050];
			int index = 0;
			for (int i = 1049; i >= 0; i--) {
				addedArray[index++] = i;
			}
			array.addAll(addedArray, 0, 1050);
			assertFalse(array.isMonotonic());
			// value 50 is at index 1049-50 = 999
			assertEquals(999, array.indexOf(50));
			// value 0 is at the last position
			assertEquals(1049, array.indexOf(0));
		}

		@Test
		@DisplayName(
			"Should not find zero in trailing slots of monotonic array"
		)
		void shouldNotFindZeroInTrailingSlotsOfMonotonicArray() {
			// 53 elements from 1..53 - trailing zero slots must not match
			final CompositeLongArray array = new CompositeLongArray();
			for (int i = 1; i <= 53; i++) {
				array.add(i);
			}
			assertTrue(array.isMonotonic());
			assertEquals(-1, array.indexOf(0));
		}

		@Test
		@DisplayName(
			"Should not find zero in trailing slots of non-monotonic array"
		)
		void shouldNotFindZeroInTrailingSlotsOfNonMonotonicArray() {
			// 53 descending elements from 53 to 1
			final CompositeLongArray array = new CompositeLongArray();
			for (int i = 53; i >= 1; i--) {
				array.add(i);
			}
			assertFalse(array.isMonotonic());
			assertEquals(-1, array.indexOf(0));
		}
	}

	@Nested
	@DisplayName("Mutation - set")
	class Set {

		@Test
		@DisplayName("Should set record on specific index across chunks")
		void shouldSetRecordOnSpecificIndex() {
			final CompositeLongArray array = new CompositeLongArray();
			// fill with 1000 elements
			for (int i = 0; i < 1000; i++) {
				array.add(i);
			}

			// set value at index 280 (chunk 5, position 30)
			array.set(9999L, 280);
			assertEquals(9999L, array.get(280));

			// set value at index 800 (chunk 16, position 0)
			array.set(7777L, 800);
			assertEquals(7777L, array.get(800));

			// verify other elements are unaffected
			assertEquals(0, array.get(0));
			assertEquals(279, array.get(279));
			assertEquals(281, array.get(281));
			assertEquals(999, array.get(999));
		}

		@Test
		@DisplayName("Should accept long value in set method")
		void shouldAcceptLongValueInSetMethod() {
			// CompositeLongArray stores long values, so set() should accept long
			// This test verifies that a long value round-trips correctly
			final CompositeLongArray array = new CompositeLongArray();
			array.add(1L);
			array.add(2L);
			array.add(3L);
			// value within int range
			array.set(42L, 1);
			assertEquals(42L, array.get(1));

			// value beyond int range
			final long largeValue = (long) Integer.MAX_VALUE + 100L;
			array.set(largeValue, 2);
			assertEquals(largeValue, array.get(2));
		}

		@Test
		@DisplayName("Should throw on out-of-bounds set")
		void shouldThrowOnOutOfBoundsSet() {
			final CompositeLongArray array = new CompositeLongArray();
			array.add(1L);
			array.add(2L);
			assertThrows(Exception.class, () -> array.set(99L, 5));
		}
	}

	@Nested
	@DisplayName("Append - add and addAll")
	class AddAndAddAll {

		@Test
		@DisplayName("Should make composite array by adding one element at a time")
		void shouldMakeCompositeArrayByOne() {
			final CompositeLongArray array = new CompositeLongArray();

			final int limit = 2_048;
			for (int i = 0; i < limit; i++) {
				array.add(i);
			}

			final long[] result = array.toArray();
			assertEquals(limit, result.length);

			long last = -1;
			for (final long value : result) {
				assertEquals(last + 1, value);
				last = value;
			}
		}

		@Test
		@DisplayName("Should make composite array by adding multiple elements at once")
		void shouldMakeCompositeArrayByMultiple() {
			final CompositeLongArray array = new CompositeLongArray();

			final int repetitions = 4;
			final int limit = 700;

			for (int j = 0; j < repetitions; j++) {
				final long[] arr = new long[limit];
				for (int i = 0; i < limit; i++) {
					arr[i] = (long) j * limit + i;
				}
				array.addAll(arr, 0, limit);
			}

			final long[] result = array.toArray();
			assertEquals(repetitions * limit, result.length);

			long last = -1;
			for (final long value : result) {
				assertEquals(last + 1, value);
				last = value;
			}
		}

		@Test
		@DisplayName("Should handle addAll with zero-length input array")
		void shouldHandleAddAllWithZeroLength() {
			final CompositeLongArray array = new CompositeLongArray();
			array.add(1L);
			array.addAll(new long[0], 0, 0);
			assertEquals(1, array.getSize());
			assertEquals(1L, array.get(0));
		}

		@Test
		@DisplayName("Should handle addAll with non-empty array but zero length")
		void shouldHandleAddAllWithNonEmptyArrayButZeroLength() {
			final CompositeLongArray array = new CompositeLongArray();
			array.add(1L);
			array.addAll(new long[]{10L, 20L, 30L}, 0, 0);
			// size should remain 1 - nothing was added
			assertEquals(1, array.getSize());
			assertEquals(1L, array.get(0));
		}

		@Test
		@DisplayName("Should handle addAll across chunk boundary")
		void shouldHandleAddAllAcrossChunkBoundary() {
			final CompositeLongArray array = new CompositeLongArray();
			// fill 48 elements to be near the chunk boundary (CHUNK_SIZE=50)
			for (int i = 0; i < 48; i++) {
				array.add(i);
			}
			assertEquals(48, array.getSize());

			// add 10 elements that will span the boundary
			final long[] crossBoundary = {100, 101, 102, 103, 104, 105, 106, 107, 108, 109};
			array.addAll(crossBoundary, 0, 10);
			assertEquals(58, array.getSize());

			// verify boundary elements
			assertEquals(47, array.get(47));  // last of original
			assertEquals(100, array.get(48)); // first of added batch
			assertEquals(101, array.get(49)); // last in chunk 0
			assertEquals(102, array.get(50)); // first in chunk 1
			assertEquals(109, array.get(57)); // last of added batch
		}
	}

	@Nested
	@DisplayName("Conversion - toArray")
	class ToArray {

		@Test
		@DisplayName("Should convert empty array to empty long array")
		void shouldConvertEmptyArrayToEmptyArray() {
			final CompositeLongArray array = new CompositeLongArray();
			final long[] result = array.toArray();
			assertEquals(0, result.length);
		}

		@Test
		@DisplayName("Should convert single chunk to array")
		void shouldConvertSingleChunkToArray() {
			final CompositeLongArray array = new CompositeLongArray();
			for (int i = 0; i < 30; i++) {
				array.add(i * 2L);
			}

			final long[] result = array.toArray();
			assertEquals(30, result.length);
			for (int i = 0; i < 30; i++) {
				assertEquals(i * 2L, result[i]);
			}
		}

		@Test
		@DisplayName("Should convert multiple chunks to array")
		void shouldConvertMultipleChunksToArray() {
			final CompositeLongArray array = new CompositeLongArray();
			// fill 200 elements spanning 4 chunks (CHUNK_SIZE=50)
			for (int i = 0; i < 200; i++) {
				array.add(i);
			}

			final long[] result = array.toArray();
			assertEquals(200, result.length);
			for (int i = 0; i < 200; i++) {
				assertEquals(i, result[i]);
			}
		}
	}

	@Nested
	@DisplayName("Iterator")
	class IteratorTests {

		@Test
		@DisplayName("Should iterate empty array without elements")
		void shouldIterateEmptyArray() {
			final CompositeLongArray array = new CompositeLongArray();
			assertFalse(array.iterator().hasNext());
		}

		@Test
		@DisplayName("Should iterate all elements in correct order")
		void shouldIterateAllElements() {
			final CompositeLongArray array = new CompositeLongArray();

			final int limit = 2_048;
			for (int i = 0; i < limit; i++) {
				array.add(i);
			}

			assertEquals(limit, array.getSize());

			final OfLong it = array.iterator();
			long index = -1;
			while (it.hasNext()) {
				final long next = it.nextLong();
				assertEquals(++index, next);
			}

			assertEquals(limit - 1, index);
		}

		@Test
		@DisplayName("Should throw NoSuchElementException when iterator is exhausted")
		void shouldThrowNoSuchElementWhenExhausted() {
			final CompositeLongArray array = new CompositeLongArray();
			array.add(1L);

			final OfLong it = array.iterator();
			assertTrue(it.hasNext());
			it.nextLong();
			assertFalse(it.hasNext());
			assertThrows(NoSuchElementException.class, it::nextLong);
		}
	}

	@Nested
	@DisplayName("Equals and hashCode")
	class EqualsAndHashCode {

		@Test
		@DisplayName("Should be equal for identical content built independently")
		void shouldBeEqualForIdenticalContent() {
			final CompositeLongArray one = new CompositeLongArray();
			final CompositeLongArray two = new CompositeLongArray();
			final Random rnd = new Random(42L);
			for (int i = 0; i < 10_000; i++) {
				final int someInt = rnd.nextInt();
				one.add(someInt);
				two.add(someInt);

				assertEquals(one.hashCode(), two.hashCode());
				assertEquals(one, two);
			}
		}

		@Test
		@DisplayName("Should not equal array with different content")
		void shouldNotEqualDifferentContent() {
			final CompositeLongArray one = new CompositeLongArray();
			final CompositeLongArray two = new CompositeLongArray();
			one.add(1L);
			one.add(2L);
			one.add(3L);
			two.add(1L);
			two.add(2L);
			two.add(99L);
			assertNotEquals(one, two);
		}

		@Test
		@DisplayName("Should equal self")
		void shouldEqualSelf() {
			final CompositeLongArray array = new CompositeLongArray();
			array.add(1L);
			array.add(2L);
			assertEquals(array, array);
		}

		@Test
		@DisplayName("Should not equal null")
		void shouldNotEqualNull() {
			final CompositeLongArray array = new CompositeLongArray();
			array.add(1L);
			assertNotEquals(null, array);
		}
	}
}
