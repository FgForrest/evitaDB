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

import io.evitadb.dataType.iterator.BatchArrayIterator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator.OfInt;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link CompositeIntArray}.
 * The class organizes tests into nested groups covering construction, access, mutation,
 * iteration, search, monotonicity tracking, memory estimation, and equality semantics.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("CompositeIntArray")
class CompositeIntArrayTest {

	@Nested
	@DisplayName("Construction")
	class Construction {

		@Test
		@DisplayName("should create empty array with default constructor")
		void shouldCreateEmptyArrayWithDefaultConstructor() {
			final CompositeIntArray array = new CompositeIntArray();
			assertTrue(array.isEmpty());
			assertEquals(0, array.getSize());
		}

		@Test
		@DisplayName("should create array from varargs")
		void shouldCreateArrayFromVarargs() {
			final CompositeIntArray array = new CompositeIntArray(10, 20, 30, 40);
			assertEquals(4, array.getSize());
			assertEquals(10, array.get(0));
			assertEquals(20, array.get(1));
			assertEquals(30, array.get(2));
			assertEquals(40, array.get(3));
		}
	}

	@Nested
	@DisplayName("Static factory methods")
	class StaticFactoryMethods {

		@Test
		@DisplayName("should convert iterator to array")
		void shouldConvertIteratorToArray() {
			final List<Integer> source = List.of(5, 10, 15, 20, 25);
			final OfInt iterator = source.stream().mapToInt(Integer::intValue).iterator();
			final int[] result = CompositeIntArray.toArray(iterator);
			assertArrayEquals(new int[]{5, 10, 15, 20, 25}, result);
		}

		@Test
		@DisplayName("should convert empty iterator to empty array")
		void shouldConvertEmptyIteratorToEmptyArray() {
			final List<Integer> source = List.of();
			final OfInt iterator = source.stream().mapToInt(Integer::intValue).iterator();
			final int[] result = CompositeIntArray.toArray(iterator);
			assertEquals(0, result.length);
		}
	}

	@Nested
	@DisplayName("Size and emptiness")
	class SizeAndEmptiness {

		@Test
		@DisplayName("should be empty when newly created")
		void shouldBeEmptyWhenNewlyCreated() {
			final CompositeIntArray array = new CompositeIntArray();
			assertTrue(array.isEmpty());
			assertEquals(0, array.getSize());
		}

		@Test
		@DisplayName("should not be empty after adding element")
		void shouldNotBeEmptyAfterAddingElement() {
			final CompositeIntArray array = new CompositeIntArray();
			array.add(42);
			assertFalse(array.isEmpty());
			assertEquals(1, array.getSize());
		}

		@Test
		@DisplayName("should report correct size at chunk boundaries")
		void shouldReportCorrectSizeAtChunkBoundaries() {
			// CHUNK_SIZE is 50
			final CompositeIntArray array = new CompositeIntArray();

			// add 49 elements (one short of a chunk)
			for (int i = 0; i < 49; i++) {
				array.add(i);
			}
			assertEquals(49, array.getSize());

			// add 50th element (exactly one chunk)
			array.add(49);
			assertEquals(50, array.getSize());

			// add 51st element (first element of second chunk)
			array.add(50);
			assertEquals(51, array.getSize());

			// fill to 100 (exactly two chunks)
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
		@DisplayName("should get elements by index")
		void shouldGetElementsByIndex() {
			final CompositeIntArray array = new CompositeIntArray();
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
		@DisplayName("should get last element")
		void shouldGetLastElement() {
			final CompositeIntArray array = new CompositeIntArray();
			array.add(10);
			assertEquals(10, array.getLast());
			array.add(20);
			assertEquals(20, array.getLast());
			array.add(30);
			assertEquals(30, array.getLast());
		}

		@Test
		@DisplayName("should throw when getting last from empty array")
		void shouldThrowWhenGettingLastFromEmptyArray() {
			final CompositeIntArray array = new CompositeIntArray();
			assertThrows(NoSuchElementException.class, array::getLast);
		}

		@Test
		@DisplayName("should throw when get exceeds bounds")
		void shouldThrowWhenGetExceedsBounds() {
			final CompositeIntArray array = new CompositeIntArray();
			for (int i = 0; i < 10; i++) {
				array.add(i);
			}
			// accessing index in a non-existent chunk should throw
			assertThrows(Exception.class, () -> array.get(1000));
		}
	}

	@Nested
	@DisplayName("Range access")
	class RangeAccess {

		@Test
		@DisplayName("should return range")
		void shouldReturnRange() {
			final CompositeIntArray tested = new CompositeIntArray();
			final int limit = 1900;
			for (int i = 0; i < limit; i++) {
				tested.add(i);
			}
			// small range within first chunk
			assertArrayEquals(new int[]{2, 3, 4}, tested.getRange(2, 5));
			// range spanning multiple chunks
			assertArrayEquals(
				new int[]{510, 511, 512, 513, 514, 515, 516, 517, 518, 519},
				tested.getRange(510, 520)
			);
			// full range
			assertArrayEquals(tested.toArray(), tested.getRange(0, tested.getSize()));
		}

		@Test
		@DisplayName("should return range spanning chunk boundary")
		void shouldReturnRangeSpanningChunkBoundary() {
			// CHUNK_SIZE=50, so chunk boundary is at index 49/50
			final CompositeIntArray tested = new CompositeIntArray();
			for (int i = 0; i < 100; i++) {
				tested.add(i);
			}
			// range crossing the boundary: 48..52
			final int[] range = tested.getRange(48, 53);
			assertArrayEquals(new int[]{48, 49, 50, 51, 52}, range);
		}

		@Test
		@DisplayName("should fail when range exceeds size")
		void shouldFailWhenRangeExceedsSize() {
			final CompositeIntArray tested = new CompositeIntArray();
			tested.addAll(new int[]{5, 8, 10, 3, 12, 15, 20, 23}, 0, 8);
			assertThrows(
				IndexOutOfBoundsException.class,
				() -> tested.getRange(7, 10)
			);
		}
	}

	@Nested
	@DisplayName("Monotonicity tracking")
	class MonotonicityTracking {

		@Test
		@DisplayName("should maintain monotonic row")
		void shouldMaintainMonotonicRow() {
			final CompositeIntArray array = new CompositeIntArray();
			array.add(1);
			array.add(3);
			array.add(4);
			array.add(5);
			array.add(10);
			assertTrue(array.isMonotonic());
		}

		@Test
		@DisplayName("should not maintain monotonic row on duplicate value")
		void shouldNotMaintainMonotonicRowOnDuplicateValue() {
			final CompositeIntArray array = new CompositeIntArray();
			array.add(1);
			array.add(3);
			array.add(4);
			array.add(4);
			array.add(10);
			assertFalse(array.isMonotonic());
		}

		@Test
		@DisplayName("should not maintain monotonic row on lower value")
		void shouldNotMaintainMonotonicRowOnLowerValue() {
			final CompositeIntArray array = new CompositeIntArray();
			array.add(1);
			array.add(3);
			array.add(4);
			array.add(3);
			array.add(10);
			assertFalse(array.isMonotonic());
		}

		@Test
		@DisplayName("should maintain monotonic on bulk add")
		void shouldMaintainMonotonicOnBulkAdd() {
			final CompositeIntArray array = new CompositeIntArray();
			final int[] addedArray = new int[1050];
			for (int i = 0; i < 1050; i++) {
				addedArray[i] = i;
			}
			array.addAll(addedArray, 0, 1050);
			assertTrue(array.isMonotonic());
		}

		@Test
		@DisplayName("should not maintain monotonic on bulk add with lowered value")
		void shouldNotMaintainMonotonicOnBulkAddWithLoweredValue() {
			final CompositeIntArray array = new CompositeIntArray();
			final int[] addedArray = new int[1050];
			for (int i = 0; i < 1050; i++) {
				addedArray[i] = i;
			}
			addedArray[1000] = 4;
			array.addAll(addedArray, 0, 1050);
			assertFalse(array.isMonotonic());
		}

		@Test
		@DisplayName("should maintain monotonic when lowered value outside range")
		void shouldMaintainMonotonicWhenLoweredValueOutsideRange() {
			final CompositeIntArray array = new CompositeIntArray();
			final int[] addedArray = new int[1050];
			for (int i = 0; i < 1050; i++) {
				addedArray[i] = i;
			}
			addedArray[10] = 4;
			array.addAll(addedArray, 100, 950);
			assertTrue(array.isMonotonic());
		}

		@Test
		@DisplayName("should detect non-monotonic values in addAll with non-zero srcPosition")
		void shouldDetectNonMonotonicInAddAllWithNonZeroSrcPosition() {
			// Array: [0,1,2,...,98,99, 4, 101,102,...] with a dip at index 100
			// addAll(array, 95, 10) copies indices 95..104 which include the dip at 100
			final CompositeIntArray array = new CompositeIntArray();
			final int[] source = new int[110];
			for (int i = 0; i < 110; i++) {
				source[i] = i;
			}
			source[100] = 4; // dip inside the range [95, 105)
			array.addAll(source, 95, 10);
			// The dip at index 100 (6th element in the copied range) must be detected
			assertFalse(array.isMonotonic());
		}

		@Test
		@DisplayName("should maintain monotonic across multiple chunks")
		void shouldMaintainMonotonicAcrossMultipleChunks() {
			// 101 sorted elements span 3 chunks (50 + 50 + 1)
			final CompositeIntArray array = new CompositeIntArray();
			for (int i = 0; i < 101; i++) {
				array.add(i);
			}
			assertTrue(array.isMonotonic());
			assertEquals(101, array.getSize());
			// verify boundary values
			assertEquals(49, array.get(49));
			assertEquals(50, array.get(50));
			assertEquals(100, array.get(100));
		}
	}

	@Nested
	@DisplayName("Search - contains")
	class Contains {

		@Test
		@DisplayName("should find value in monotonic array")
		void shouldFindValueInMonotonicArray() {
			final CompositeIntArray array = new CompositeIntArray();
			final int[] addedArray = new int[1050];
			for (int i = 0; i < 1050; i++) {
				addedArray[i] = i;
			}
			array.addAll(addedArray, 0, 1050);
			assertTrue(array.isMonotonic());
			assertTrue(array.contains(999));
		}

		@Test
		@DisplayName("should not find missing value in monotonic array")
		void shouldNotFindMissingValueInMonotonicArray() {
			final CompositeIntArray array = new CompositeIntArray();
			final int[] addedArray = new int[1050];
			for (int i = 0; i < 1050; i++) {
				addedArray[i] = i * 2;
			}
			array.addAll(addedArray, 0, 1050);
			assertTrue(array.isMonotonic());
			// odd numbers are not present
			assertFalse(array.contains(999));
		}

		@Test
		@DisplayName("should find value in non-monotonic array")
		void shouldFindValueInNonMonotonicArray() {
			final CompositeIntArray array = new CompositeIntArray();
			final int[] addedArray = new int[1050];
			int index = 0;
			for (int i = 1049; i >= 0; i--) {
				addedArray[index++] = i;
			}
			array.addAll(addedArray, 0, 1050);
			assertFalse(array.isMonotonic());
			assertTrue(array.contains(50));
		}

		@Test
		@DisplayName("should not find missing value in non-monotonic array")
		void shouldNotFindMissingValueInNonMonotonicArray() {
			final CompositeIntArray array = new CompositeIntArray();
			final int[] addedArray = new int[1050];
			int index = 0;
			for (int i = 1049; i >= 0; i--) {
				addedArray[index++] = i;
			}
			array.addAll(addedArray, 0, 1050);
			assertFalse(array.isMonotonic());
			assertFalse(array.contains(2000));
		}

		@Test
		@DisplayName("should not find zero in trailing slots of non-monotonic array")
		void shouldNotFindZeroInTrailingSlotsOfNonMonotonicArray() {
			// Create array with 53 positive elements (starting from 1 to avoid zero)
			// chunk[0] = [1..50], chunk[1] = [51,52,53, 0,0,...,0]
			// The trailing zeros are NOT data - they're default int[] values
			final CompositeIntArray array = new CompositeIntArray();
			for (int i = 1; i <= 53; i++) {
				array.add(i);
			}
			// 0 is not in the array
			assertFalse(array.contains(0));
		}
	}

	@Nested
	@DisplayName("Search - indexOf")
	class IndexOf {

		@Test
		@DisplayName("should find index in monotonic array")
		void shouldFindIndexInMonotonicArray() {
			final CompositeIntArray array = new CompositeIntArray();
			final int[] addedArray = new int[1050];
			for (int i = 0; i < 1050; i++) {
				addedArray[i] = i;
			}
			array.addAll(addedArray, 0, 1050);
			assertTrue(array.isMonotonic());
			assertEquals(999, array.indexOf(999));
		}

		@Test
		@DisplayName("should return negative for missing in monotonic array")
		void shouldReturnNegativeForMissingInMonotonicArray() {
			final CompositeIntArray array = new CompositeIntArray();
			final int[] addedArray = new int[1050];
			for (int i = 0; i < 1050; i++) {
				addedArray[i] = i * 2;
			}
			array.addAll(addedArray, 0, 1050);
			assertTrue(array.isMonotonic());
			// odd numbers are not present
			assertTrue(array.indexOf(999) < 0);
		}

		@Test
		@DisplayName("should find index in non-monotonic array")
		void shouldFindIndexInNonMonotonicArray() {
			final CompositeIntArray array = new CompositeIntArray();
			final int[] addedArray = new int[1050];
			int index = 0;
			for (int i = 1049; i >= 0; i--) {
				addedArray[index++] = i;
			}
			array.addAll(addedArray, 0, 1050);
			assertFalse(array.isMonotonic());
			// value 50 is at position 1049 - 50 = 999
			assertEquals(999, array.indexOf(50));
		}

		@Test
		@DisplayName("should not find zero in trailing slots of monotonic array")
		void shouldNotFindZeroInTrailingSlotsOfMonotonicArray() {
			// 53 sorted elements starting from 1: chunk[0]=[1..50], chunk[1]=[51,52,53, 0..0]
			// indexOf(0) should be -1 since 0 was never added
			final CompositeIntArray array = new CompositeIntArray();
			for (int i = 1; i <= 53; i++) {
				array.add(i);
			}
			assertEquals(-1, array.indexOf(0));
		}

		@Test
		@DisplayName("should not find zero in trailing slots of non-monotonic array")
		void shouldNotFindZeroInTrailingSlotsOfNonMonotonicArray() {
			// 53 descending elements starting from 53: chunk[0]=[53..4], chunk[1]=[3,2,1, 0..0]
			// indexOf(0) should be -1 since 0 was never added
			final CompositeIntArray array = new CompositeIntArray();
			for (int i = 53; i >= 1; i--) {
				array.add(i);
			}
			assertFalse(array.isMonotonic());
			assertEquals(-1, array.indexOf(0));
		}
	}

	@Nested
	@DisplayName("Mutation - set")
	class SetTests {

		@Test
		@DisplayName("should set record on specific index")
		void shouldSetRecordOnSpecificIndex() {
			final CompositeIntArray array = new CompositeIntArray();
			for (int i = 0; i < 1050; i++) {
				array.add(i);
			}
			array.set(999, 280);
			array.set(999, 800);

			final int[] result = array.toArray();
			assertEquals(279, result[279]);
			assertEquals(999, result[280]);
			assertEquals(281, result[281]);
			assertEquals(799, result[799]);
			assertEquals(999, result[800]);
			assertEquals(801, result[801]);
		}

		@Test
		@DisplayName("should throw on out of bounds set")
		void shouldThrowOnOutOfBoundsSet() {
			final CompositeIntArray array = new CompositeIntArray();
			for (int i = 0; i < 10; i++) {
				array.add(i);
			}
			assertThrows(Exception.class, () -> array.set(42, 100));
		}
	}

	@Nested
	@DisplayName("Append - add and addAll")
	class AddAndAddAll {

		@Test
		@DisplayName("should make composite array by one")
		void shouldMakeCompositeArrayByOne() {
			final CompositeIntArray array = new CompositeIntArray();

			final int limit = 2_048;
			for (int i = 0; i < limit; i++) {
				array.add(i);
			}

			final int[] result = array.toArray();
			assertEquals(limit, result.length);

			for (int i = 0; i < result.length; i++) {
				assertEquals(i, result[i]);
			}
		}

		@Test
		@DisplayName("should make composite array by multiple")
		void shouldMakeCompositeArrayByMultiple() {
			final CompositeIntArray array = new CompositeIntArray();

			final int repetitions = 4;
			final int limit = 700;

			for (int j = 0; j < repetitions; j++) {
				final int[] arr = new int[limit];
				for (int i = 0; i < limit; i++) {
					arr[i] = j * limit + i;
				}
				array.addAll(arr, 0, limit);
			}

			final int[] result = array.toArray();
			assertEquals(repetitions * limit, result.length);

			for (int i = 0; i < result.length; i++) {
				assertEquals(i, result[i]);
			}
		}

		@Test
		@DisplayName("should handle addAll with zero length")
		void shouldHandleAddAllWithZeroLength() {
			final CompositeIntArray array = new CompositeIntArray();
			array.add(1);
			array.addAll(new int[]{10, 20, 30}, 0, 0);
			// size should remain 1
			assertEquals(1, array.getSize());
			assertEquals(1, array.get(0));
		}

		@Test
		@DisplayName("should handle addAll across chunk boundary")
		void shouldHandleAddAllAcrossChunkBoundary() {
			// CHUNK_SIZE=50: fill first chunk to 48 elements, then addAll 4 more
			// this crosses the boundary at index 49/50
			final CompositeIntArray array = new CompositeIntArray();
			for (int i = 0; i < 48; i++) {
				array.add(i);
			}
			array.addAll(new int[]{48, 49, 50, 51}, 0, 4);
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
			final CompositeIntArray array = new CompositeIntArray();
			final int[] result = array.toArray();
			assertEquals(0, result.length);
		}

		@Test
		@DisplayName("should convert single chunk to array")
		void shouldConvertSingleChunkToArray() {
			final CompositeIntArray array = new CompositeIntArray();
			for (int i = 0; i < 30; i++) {
				array.add(i * 2);
			}
			final int[] result = array.toArray();
			assertEquals(30, result.length);
			for (int i = 0; i < 30; i++) {
				assertEquals(i * 2, result[i]);
			}
		}

		@Test
		@DisplayName("should convert multiple chunks to array")
		void shouldConvertMultipleChunksToArray() {
			// test boundary sizes: 49 (under chunk), 50 (exact chunk), 51 (one over)
			for (final int size : new int[]{49, 50, 51}) {
				final CompositeIntArray array = new CompositeIntArray();
				for (int i = 0; i < size; i++) {
					array.add(i);
				}
				final int[] result = array.toArray();
				assertEquals(size, result.length);
				for (int i = 0; i < size; i++) {
					assertEquals(i, result[i]);
				}
			}
		}
	}

	@Nested
	@DisplayName("Iterator")
	class IteratorTests {

		@Test
		@DisplayName("should iterate empty array")
		void shouldIterateEmptyArray() {
			final CompositeIntArray array = new CompositeIntArray();
			assertFalse(array.iterator().hasNext());
		}

		@Test
		@DisplayName("should iterate all elements")
		void shouldIterateAllElements() {
			final CompositeIntArray array = new CompositeIntArray();

			final int limit = 2_048;
			for (int i = 0; i < limit; i++) {
				array.add(i);
			}

			assertEquals(limit, array.getSize());

			final OfInt it = array.iterator();
			int index = 0;
			while (it.hasNext()) {
				final int next = it.nextInt();
				assertEquals(index, next);
				index++;
			}

			assertEquals(limit, index);
		}

		@Test
		@DisplayName("should iterate from index")
		void shouldIterateFromIndex() {
			final CompositeIntArray array = new CompositeIntArray();

			final int limit = 2_048;
			for (int i = 0; i < limit; i++) {
				array.add(i);
			}

			assertEquals(limit, array.getSize());

			for (int j = 0; j < 7; j++) {
				final int startIndex = j * (limit / 7);
				final OfInt it = array.iteratorFrom(startIndex);
				int index = startIndex;
				while (it.hasNext()) {
					final int next = it.nextInt();
					assertEquals(index, next);
					index++;
				}

				assertEquals(limit, index);
			}
		}

		@Test
		@DisplayName("should iterate from chunk boundary")
		void shouldIterateFromChunkBoundary() {
			// CHUNK_SIZE=50: iterate starting from exact chunk boundaries
			final CompositeIntArray array = new CompositeIntArray();
			for (int i = 0; i < 150; i++) {
				array.add(i);
			}

			// start at index 50 (second chunk start)
			final OfInt it50 = array.iteratorFrom(50);
			assertTrue(it50.hasNext());
			assertEquals(50, it50.nextInt());

			// start at index 100 (third chunk start)
			final OfInt it100 = array.iteratorFrom(100);
			assertTrue(it100.hasNext());
			assertEquals(100, it100.nextInt());

			// exhaust the iterator from 100 and count elements
			int count = 1; // already read one above
			while (it100.hasNext()) {
				it100.nextInt();
				count++;
			}
			assertEquals(50, count); // elements 100..149
		}

		@Test
		@DisplayName("should throw NoSuchElement when exhausted")
		void shouldThrowNoSuchElementWhenExhausted() {
			// CHUNK_SIZE=50: fill exactly one chunk so that exhaustion crosses the chunk boundary
			final CompositeIntArray array = new CompositeIntArray();
			for (int i = 0; i < 50; i++) {
				array.add(i);
			}
			final OfInt it = array.iterator();
			// consume all 50 elements
			for (int i = 0; i < 50; i++) {
				it.nextInt();
			}
			assertFalse(it.hasNext());
			assertThrows(NoSuchElementException.class, it::nextInt);
		}
	}

	@Nested
	@DisplayName("Batch iterator")
	class BatchIterator {

		@Test
		@DisplayName("should iterate in batches")
		void shouldIterateInBatches() {
			final CompositeIntArray tested = new CompositeIntArray();
			for (int i = 0; i < 123; i++) {
				tested.add(i * 3);
			}
			final BatchArrayIterator it = tested.batchIterator();
			final Set<Integer> collectedInts = new HashSet<>();
			while (it.hasNext()) {
				final int[] batch = it.nextBatch();
				for (int i = 0; i < it.getPeek(); i++) {
					collectedInts.add(batch[i]);
				}
			}

			assertEquals(123, collectedInts.size());
			for (int i = 0; i < 123; i++) {
				assertTrue(collectedInts.contains(i * 3));
			}
		}

		@Test
		@DisplayName("should handle empty array batch iterator")
		void shouldHandleEmptyArrayBatchIterator() {
			final CompositeIntArray tested = new CompositeIntArray();
			final BatchArrayIterator it = tested.batchIterator();
			// empty array has one chunk but chunkPeek is -1
			// hasNext should return true because there is one chunk, but getPeek would be 0
			// Actually, let's just verify that collecting produces no elements
			final List<Integer> collected = new ArrayList<>();
			while (it.hasNext()) {
				final int[] batch = it.nextBatch();
				for (int i = 0; i < it.getPeek(); i++) {
					collected.add(batch[i]);
				}
			}
			assertTrue(collected.isEmpty());
		}

		@Test
		@DisplayName("should advanceIfNeeded past chunks")
		void shouldAdvanceIfNeededPastChunks() {
			// 150 sorted elements: chunk[0]=[0..49], chunk[1]=[50..99], chunk[2]=[100..149]
			// advanceIfNeeded(100) should skip chunk[0] and chunk[1]
			final CompositeIntArray array = new CompositeIntArray();
			for (int i = 0; i < 150; i++) {
				array.add(i);
			}
			assertTrue(array.isMonotonic());

			final BatchArrayIterator it = array.batchIterator();
			it.advanceIfNeeded(100);
			assertTrue(it.hasNext());
			final int[] batch = it.nextBatch();
			// first element of third chunk
			assertEquals(100, batch[0]);
		}
	}

	@Nested
	@DisplayName("Memory estimation")
	class MemoryEstimation {

		@Test
		@DisplayName("should return larger size for more chunks")
		void shouldReturnLargerSizeForMoreChunks() {
			// More chunks = larger memory footprint
			final CompositeIntArray small = new CompositeIntArray();
			for (int i = 0; i < 10; i++) {
				small.add(i);
			}

			final CompositeIntArray large = new CompositeIntArray();
			for (int i = 0; i < 200; i++) {
				large.add(i);
			}

			// large has 4 chunks (200/50=4), small has 1 chunk -> large must be bigger
			assertTrue(
				large.getSizeInBytes() > small.getSizeInBytes(),
				"Expected large array (" + large.getSizeInBytes() +
					" bytes) to have bigger footprint than small array (" +
					small.getSizeInBytes() + " bytes)"
			);
		}
	}

	@Nested
	@DisplayName("Equals and hashCode")
	class EqualsAndHashCode {

		@Test
		@DisplayName("should be equal for identical content")
		void shouldBeEqualForIdenticalContent() {
			final CompositeIntArray one = new CompositeIntArray();
			final CompositeIntArray two = new CompositeIntArray();
			final Random rnd = new Random(42);
			for (int i = 0; i < 10_000; i++) {
				final int someInt = rnd.nextInt();
				one.add(someInt);
				two.add(someInt);

				assertEquals(one.hashCode(), two.hashCode());
				assertEquals(one, two);
			}
		}

		@Test
		@DisplayName("should not equal different content")
		void shouldNotEqualDifferentContent() {
			final CompositeIntArray one = new CompositeIntArray();
			final CompositeIntArray two = new CompositeIntArray();
			one.add(1);
			one.add(2);
			one.add(3);
			two.add(1);
			two.add(2);
			two.add(4);
			assertNotEquals(one, two);
		}

		@Test
		@DisplayName("should equal self")
		void shouldEqualSelf() {
			final CompositeIntArray array = new CompositeIntArray();
			array.add(10);
			array.add(20);
			assertEquals(array, array);
		}

		@Test
		@DisplayName("should not equal null")
		void shouldNotEqualNull() {
			final CompositeIntArray array = new CompositeIntArray();
			array.add(10);
			assertNotEquals(null, array);
		}
	}
}
