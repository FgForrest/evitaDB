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

package io.evitadb.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.function.IntBinaryOperator;
import java.util.function.ToIntBiFunction;
import javax.annotation.Nonnull;
import java.util.function.UnaryOperator;

import static io.evitadb.utils.ArrayUtils.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test verifies contract of {@link ArrayUtils} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("ArrayUtils contract tests")
class ArrayUtilsTest {

	/**
	 * Asserts that the given insertion position matches the expected found state and index.
	 *
	 * @param insertionPosition the insertion position to verify
	 * @param found             whether the element was expected to be found
	 * @param index             the expected position index
	 */
	private static void assertInsertPosition(
		@Nonnull InsertionPosition insertionPosition,
		boolean found,
		int index
	) {
		if (found) {
			assertTrue(insertionPosition.alreadyPresent());
		} else {
			assertFalse(insertionPosition.alreadyPresent());
		}
		assertEquals(index, insertionPosition.position());
	}

	private enum TestEnum {
		VALUE1, VALUE2, VALUE3
	}

	@Nested
	@DisplayName("Insertion tests")
	class InsertionTests {

		@Test
		@DisplayName("Should insert value at beginning")
		public void shouldInsertValueAtBeginning() {
			int[] array = {10, 20, 30, 40, 50};
			insertIntIntoSameArrayOnIndex(5, array, 0);
			assertArrayEquals(new int[]{5, 10, 20, 30, 40}, array);
		}

		@Test
		@DisplayName("Should insert value at middle")
		public void shouldInsertValueAtMiddle() {
			int[] array = {10, 20, 30, 40, 50};
			insertIntIntoSameArrayOnIndex(25, array, 2);
			assertArrayEquals(new int[]{10, 20, 25, 30, 40}, array);
		}

		@Test
		@DisplayName("Should insert value at end")
		public void shouldInsertValueAtEnd() {
			int[] array = {10, 20, 30, 40, 50};
			insertIntIntoSameArrayOnIndex(60, array, 4);
			assertArrayEquals(new int[]{10, 20, 30, 40, 60}, array);
		}

		@Test
		@DisplayName("Should insert value into single element array")
		public void shouldInsertValueIntoSingleElementArray() {
			int[] array = {10};
			insertIntIntoSameArrayOnIndex(5, array, 0);
			assertArrayEquals(new int[]{5}, array);
		}

		@Test
		@DisplayName("Should fail to insert value at invalid position")
		public void shouldFailToInsertValueAtInvalidPosition() {
			int[] array = {10, 20, 30, 40, 50};
			assertThrows(IndexOutOfBoundsException.class, () -> {
				insertIntIntoSameArrayOnIndex(5, array, 5);
			});
		}

		@Test
		@DisplayName("Should insert record at beginning of array")
		public void shouldInsertRecordAtBeginningOfArray() {
			Integer[] array = {1, 2, 3, 4, 5};
			Integer newRecord = 99;
			Integer[] expected = {99, 1, 2, 3, 4};

			insertRecordIntoSameArrayOnIndex(newRecord, array, 0);
			assertArrayEquals(expected, array);
		}

		@Test
		@DisplayName("Should insert record in middle of array")
		public void shouldInsertRecordInMiddleOfArray() {
			Integer[] array = {1, 2, 3, 4, 5};
			Integer newRecord = 99;
			Integer[] expected = {1, 2, 99, 3, 4};

			insertRecordIntoSameArrayOnIndex(newRecord, array, 2);
			assertArrayEquals(expected, array);
		}

		@Test
		@DisplayName("Should insert record at end of array and overwrite last element")
		public void shouldInsertRecordAtEndOfArrayAndOverwriteLastElement() {
			Integer[] array = {1, 2, 3, 4, 5};
			Integer newRecord = 99;
			Integer[] expected = {1, 2, 3, 4, 99};

			insertRecordIntoSameArrayOnIndex(newRecord, array, 4);
			assertArrayEquals(expected, array);
		}

		@Test
		@DisplayName("Should throw ArrayIndexOutOfBoundsException for invalid index")
		public void shouldThrowArrayIndexOutOfBoundsExceptionForInvalidIndex() {
			Integer[] array = {1, 2, 3, 4, 5};
			Integer newRecord = 99;

			assertThrows(ArrayIndexOutOfBoundsException.class, () ->
				insertRecordIntoSameArrayOnIndex(newRecord, array, 5)
			);
		}

		@Test
		@DisplayName("Should work with strings")
		public void shouldWorkWithStrings() {
			String[] array = {"a", "b", "c", "d", "e"};
			String newRecord = "z";
			String[] expected = {"a", "b", "z", "c", "d"};

			insertRecordIntoSameArrayOnIndex(newRecord, array, 2);
			assertArrayEquals(expected, array);
		}

		@Test
		@DisplayName("Should add ints to array")
		void shouldAddIntsToArray() {
			int[] records = insertIntIntoArrayOnIndex(5, new int[0], 0);
			records = insertIntIntoArrayOnIndex(2, records, 0);
			records = insertIntIntoArrayOnIndex(8, records, 1);
			records = insertIntIntoArrayOnIndex(1, records, 0);
			records = insertIntIntoArrayOnIndex(1, records, 2);
			records = insertIntIntoArrayOnIndex(9, records, 3);

			assertArrayEquals(
				new int[]{1, 2, 1, 9, 8, 5},
				records
			);
		}

		@Test
		@DisplayName("Should add ints to ordered array in proper order")
		void shouldAddIntsToOrderedArrayInProperOrder() {
			int[] records = insertIntIntoOrderedArray(5, new int[0]);
			records = insertIntIntoOrderedArray(2, records);
			records = insertIntIntoOrderedArray(8, records);
			records = insertIntIntoOrderedArray(1, records);
			records = insertIntIntoOrderedArray(1, records);
			records = insertIntIntoOrderedArray(9, records);

			assertArrayEquals(
				new int[]{1, 2, 5, 8, 9},
				records
			);
		}

		@Test
		@DisplayName("Should add records to array")
		void shouldAddRecordsToArray() {
			Integer[] records = insertRecordIntoArrayOnIndex(5, new Integer[0], 0);
			records = insertRecordIntoArrayOnIndex(2, records, 0);
			records = insertRecordIntoArrayOnIndex(8, records, 1);
			records = insertRecordIntoArrayOnIndex(1, records, 0);
			records = insertRecordIntoArrayOnIndex(1, records, 2);
			records = insertRecordIntoArrayOnIndex(9, records, 3);

			assertArrayEquals(
				new Integer[]{1, 2, 1, 9, 8, 5},
				records
			);
		}

		@Test
		@DisplayName("Should add records to array using comparator")
		void shouldAddRecordsToArrayUsingComparator() {
			Integer[] records = insertRecordIntoOrderedArray(5, new Integer[0], Integer::compare);
			records = insertRecordIntoArrayOnIndex(2, records, 0);
			records = insertRecordIntoArrayOnIndex(8, records, 1);
			records = insertRecordIntoArrayOnIndex(1, records, 0);
			records = insertRecordIntoArrayOnIndex(1, records, 2);
			records = insertRecordIntoArrayOnIndex(9, records, 3);

			assertArrayEquals(
				new Integer[]{1, 2, 1, 9, 8, 5},
				records
			);
		}

		@Test
		@DisplayName("Should add records to ordered array in proper order")
		void shouldAddRecordsToOrderedArrayInProperOrder() {
			Integer[] records = insertRecordIntoOrderedArray(5, new Integer[0]);
			records = insertRecordIntoOrderedArray(2, records);
			records = insertRecordIntoOrderedArray(8, records);
			records = insertRecordIntoOrderedArray(1, records);
			records = insertRecordIntoOrderedArray(1, records);
			records = insertRecordIntoOrderedArray(9, records);

			assertArrayEquals(
				new Integer[]{1, 2, 5, 8, 9},
				records
			);
		}

		@Test
		@DisplayName("Should insert into ordered array using comparator")
		void shouldInsertIntoOrderedArrayUsingComparator() {
			final String controlString = "312406587";
			final int[] unsortedArray = {3, 1, 4, 0, 5, 8, 7};
			final IntBinaryOperator comparator = (o1, o2) -> Integer.compare(controlString.indexOf(String.valueOf(o1)), controlString.indexOf(String.valueOf(o2)));
			final int[] array1 = insertIntIntoOrderedArray(2, unsortedArray, comparator);
			assertArrayEquals(
				new int[]{3, 1, 2, 4, 0, 5, 8, 7},
				array1
			);
			final int[] array2 = insertIntIntoOrderedArray(6, array1, comparator);
			assertArrayEquals(
				new int[]{3, 1, 2, 4, 0, 6, 5, 8, 7},
				array2
			);
		}
	}

	@Nested
	@DisplayName("Removal tests")
	class RemovalTests {

		@Test
		@DisplayName("Should remove ints from array and keep order")
		void shouldRemoveIntsFromArrayAndKeepOrder() {
			int[] records = new int[]{1, 2, 5, 6, 8, 9};
			records = removeIntFromOrderedArray(1, records);
			records = removeIntFromOrderedArray(5, records);
			records = removeIntFromOrderedArray(5, records);
			records = removeIntFromOrderedArray(9, records);

			assertArrayEquals(
				new int[]{2, 6, 8},
				records
			);
		}

		@Test
		@DisplayName("Should remove records from array and keep order")
		void shouldRemoveRecordsFromArrayAndKeepOrder() {
			Integer[] records = new Integer[]{1, 2, 5, 6, 8, 9};
			records = removeRecordFromOrderedArray(1, records);
			records = removeRecordFromOrderedArray(5, records);
			records = removeRecordFromOrderedArray(5, records);
			records = removeRecordFromOrderedArray(9, records);

			assertArrayEquals(
				new Integer[]{2, 6, 8},
				records
			);
		}

		@Test
		@DisplayName("Should remove records from array and keep order using comparator")
		void shouldRemoveRecordsFromArrayAndKeepOrderUsingComparator() {
			Integer[] records = new Integer[]{1, 2, 5, 6, 8, 9};
			records = removeRecordFromOrderedArray(1, records, Integer::compareTo);
			records = removeRecordFromOrderedArray(5, records, Integer::compareTo);
			records = removeRecordFromOrderedArray(5, records, Integer::compareTo);
			records = removeRecordFromOrderedArray(9, records, Integer::compareTo);

			assertArrayEquals(
				new Integer[]{2, 6, 8},
				records
			);
		}

		@Test
		@DisplayName("Should remove range from array")
		void shouldRemoveRangeFromArray() {
			final int[] someArray = new int[]{8, 5, 6, 2, 9, 7, 1, 3};
			assertArrayEquals(new int[]{2, 9, 7, 1, 3}, removeRangeFromArray(someArray, 0, 3));
			assertArrayEquals(new int[]{8, 2, 9, 7, 1, 3}, removeRangeFromArray(someArray, 1, 3));
			assertArrayEquals(new int[]{8, 5, 6, 2, 7, 1, 3}, removeRangeFromArray(someArray, 4, 5));
			assertArrayEquals(new int[]{8, 5, 6, 2, 9, 7}, removeRangeFromArray(someArray, 6, 8));
		}

		@Test
		@DisplayName("Should remove int from beginning")
		void shouldRemoveIntFromBeginning() {
			int[] numbers = {10, 20, 30, 40, 50};
			ArrayUtils.removeIntFromSameArrayOnIndex(numbers, 0);
			assertArrayEquals(new int[]{20, 30, 40, 50, 50}, numbers);
		}

		@Test
		@DisplayName("Should remove int from middle")
		void shouldRemoveIntFromMiddle() {
			int[] numbers = {10, 20, 30, 40, 50};
			ArrayUtils.removeIntFromSameArrayOnIndex(numbers, 2);
			assertArrayEquals(new int[]{10, 20, 40, 50, 50}, numbers);
		}

		@Test
		@DisplayName("Should remove int from end")
		void shouldRemoveIntFromEnd() {
			int[] numbers = {10, 20, 30, 40, 50};
			ArrayUtils.removeIntFromSameArrayOnIndex(numbers, 4);
			assertArrayEquals(new int[]{10, 20, 30, 40, 50}, numbers);
		}

		@Test
		@DisplayName("Should handle single int array")
		void shouldHandleSingleIntArray() {
			int[] numbers = {10};
			ArrayUtils.removeIntFromSameArrayOnIndex(numbers, 0);
			assertArrayEquals(new int[]{10}, numbers);
		}

		@Test
		@DisplayName("Should fail to remove int from empty array")
		void shouldFailToRemoveIntFromEmptyArray() {
			int[] numbers = {};
			assertThrows(ArrayIndexOutOfBoundsException.class, () -> ArrayUtils.removeIntFromSameArrayOnIndex(numbers, 0));
		}

		@Test
		@DisplayName("Should remove element from beginning")
		void shouldRemoveElementFromBeginning() {
			Integer[] numbers = {10, 20, 30, 40, 50};
			ArrayUtils.removeRecordFromSameArrayOnIndex(numbers, 0);
			assertArrayEquals(new Integer[]{20, 30, 40, 50, 50}, numbers);
		}

		@Test
		@DisplayName("Should remove element from middle")
		void shouldRemoveElementFromMiddle() {
			Integer[] numbers = {10, 20, 30, 40, 50};
			ArrayUtils.removeRecordFromSameArrayOnIndex(numbers, 2);
			assertArrayEquals(new Integer[]{10, 20, 40, 50, 50}, numbers);
		}

		@Test
		@DisplayName("Should remove element from end")
		void shouldRemoveElementFromEnd() {
			Integer[] numbers = {10, 20, 30, 40, 50};
			ArrayUtils.removeRecordFromSameArrayOnIndex(numbers, 4);
			assertArrayEquals(new Integer[]{10, 20, 30, 40, 50}, numbers);
		}

		@Test
		@DisplayName("Should handle single element array")
		void shouldHandleSingleElementArray() {
			Integer[] numbers = {10};
			ArrayUtils.removeRecordFromSameArrayOnIndex(numbers, 0);
			assertArrayEquals(new Integer[]{10}, numbers);
		}

		@Test
		@DisplayName("Should fail to remove element from empty array")
		void shouldFailToRemoveElementFromEmptyArray() {
			Integer[] numbers = {};
			assertThrows(ArrayIndexOutOfBoundsException.class, () -> ArrayUtils.removeRecordFromSameArrayOnIndex(numbers, 0));
		}
	}

	@Nested
	@DisplayName("Search tests")
	class SearchTests {

		@Test
		@DisplayName("Should return correct position when element is found")
		void shouldReturnCorrectPositionWhenElementIsFound() {
			int[] recordIds = {1, 3, 5, 7, 9};
			InsertionPosition position = computeInsertPositionOfIntInOrderedArray(5, recordIds, 0, recordIds.length);
			assertEquals(2, position.position());
			assertTrue(position.alreadyPresent());
		}

		@Test
		@DisplayName("Should return insertion point when element is not found before start")
		void shouldReturnInsertionPointWhenElementIsNotFoundBeforeStart() {
			int[] recordIds = {1, 3, 5, 7, 9};
			InsertionPosition position = computeInsertPositionOfIntInOrderedArray(0, recordIds, 0, recordIds.length);
			assertEquals(0, position.position());
			assertFalse(position.alreadyPresent());
		}

		@Test
		@DisplayName("Should return insertion point when element is not found in middle")
		void shouldReturnInsertionPointWhenElementIsNotFoundInMiddle() {
			int[] recordIds = {1, 3, 5, 7, 9};
			InsertionPosition position = computeInsertPositionOfIntInOrderedArray(6, recordIds, 0, recordIds.length);
			assertEquals(3, position.position());
			assertFalse(position.alreadyPresent());
		}

		@Test
		@DisplayName("Should return insertion point when element is not found at end")
		void shouldReturnInsertionPointWhenElementIsNotFoundAtEnd() {
			int[] recordIds = {1, 3, 5, 7, 9};
			InsertionPosition position = computeInsertPositionOfIntInOrderedArray(10, recordIds, 0, recordIds.length);
			assertEquals(5, position.position());
			assertFalse(position.alreadyPresent());
		}

		@Test
		@DisplayName("Should return zero and not existing for empty array")
		void shouldReturnZeroAndNotExistingForEmptyArray() {
			int[] recordIds = {};
			InsertionPosition position = computeInsertPositionOfIntInOrderedArray(10, recordIds, 0, 0);
			assertEquals(0, position.position());
			assertFalse(position.alreadyPresent());
		}

		@Test
		@DisplayName("Should return zero and not existing when toIndex is less than or equal to fromIndex")
		void shouldReturnZeroAndNotExistingWhenToIndexIsLessThanOrEqualToFromIndex() {
			int[] recordIds = {1, 3, 5, 7, 9};
			InsertionPosition position = computeInsertPositionOfIntInOrderedArray(3, recordIds, 3, 3);
			assertEquals(0, position.position());
			assertFalse(position.alreadyPresent());
		}

		@Test
		@DisplayName("Should return zero and not existing when value is after the index")
		void shouldReturnZeroAndNotExistingWhenValueIsAfterTheIndex() {
			int[] recordIds = {1, 3, 5, 7, 9};
			InsertionPosition position = computeInsertPositionOfIntInOrderedArray(7, recordIds, 0, 3);
			assertEquals(3, position.position());
			assertFalse(position.alreadyPresent());
		}

		@Test
		@DisplayName("Should return zero and not existing when value is after the index 2")
		void shouldReturnZeroAndNotExistingWhenValueIsAfterTheIndex2() {
			int[] recordIds = {1, 3, 5, 7, 9};
			InsertionPosition position = computeInsertPositionOfIntInOrderedArray(9, recordIds, 0, 3);
			assertEquals(3, position.position());
			assertFalse(position.alreadyPresent());
		}

		@Test
		@DisplayName("Should search by comparator")
		void shouldSearchByComparator() {
			final String controlString = "312405687";
			final int[] unsortedArray = {3, 1, 2, 4, 0, 5, 6, 8, 7};
			final IntBinaryOperator comparator = (o1, o2) -> Integer.compare(controlString.indexOf(String.valueOf(o1)), controlString.indexOf(String.valueOf(o2)));
			assertEquals(0, binarySearch(unsortedArray, 3, comparator));
			assertEquals(1, binarySearch(unsortedArray, 1, comparator));
			assertEquals(2, binarySearch(unsortedArray, 2, comparator));
			assertEquals(3, binarySearch(unsortedArray, 4, comparator));
			assertEquals(4, binarySearch(unsortedArray, 0, comparator));
			assertEquals(5, binarySearch(unsortedArray, 5, comparator));
			assertEquals(6, binarySearch(unsortedArray, 6, comparator));
			assertEquals(7, binarySearch(unsortedArray, 8, comparator));
			assertEquals(8, binarySearch(unsortedArray, 7, comparator));
		}

		@Test
		@DisplayName("Should compute insert position using comparator")
		void shouldComputeInsertPositionUsingComparator() {
			final String controlString = "312406587";
			final int[] unsortedArray = {3, 1, 4, 0, 5, 8, 7};
			final IntBinaryOperator comparator = (o1, o2) -> Integer.compare(controlString.indexOf(String.valueOf(o1)), controlString.indexOf(String.valueOf(o2)));
			assertInsertPosition(computeInsertPositionOfIntInOrderedArray(3, unsortedArray, comparator), true, 0);
			assertInsertPosition(computeInsertPositionOfIntInOrderedArray(1, unsortedArray, comparator), true, 1);
			assertInsertPosition(computeInsertPositionOfIntInOrderedArray(4, unsortedArray, comparator), true, 2);
			assertInsertPosition(computeInsertPositionOfIntInOrderedArray(0, unsortedArray, comparator), true, 3);
			assertInsertPosition(computeInsertPositionOfIntInOrderedArray(5, unsortedArray, comparator), true, 4);
			assertInsertPosition(computeInsertPositionOfIntInOrderedArray(8, unsortedArray, comparator), true, 5);
			assertInsertPosition(computeInsertPositionOfIntInOrderedArray(7, unsortedArray, comparator), true, 6);
			assertInsertPosition(computeInsertPositionOfIntInOrderedArray(2, unsortedArray, comparator), false, 2);
			assertInsertPosition(computeInsertPositionOfIntInOrderedArray(6, unsortedArray, comparator), false, 4);
		}

		@Test
		@DisplayName("Should find index in unsorted array")
		void shouldFindIndexInUnsortedArray() {
			final int[] theArray = {7, 1, 9, 4, 5};
			assertEquals(-1, indexOf(8, theArray));
			assertEquals(0, indexOf(7, theArray));
			assertEquals(4, indexOf(5, theArray));
			assertEquals(1, indexOf(1, theArray));
		}

		@Test
		@DisplayName("Should return correct position when object is found")
		void shouldReturnCorrectPositionWhenObjectIsFound() {
			String[] sortedArray = {"A", "B", "D", "E", "F"};
			InsertionPosition position = computeInsertPositionOfObjInOrderedArray("D", sortedArray, String::compareTo);
			assertEquals(2, position.position());
			assertTrue(position.alreadyPresent());
		}

		@Test
		@DisplayName("Should return correct position when object is found within range")
		void shouldReturnCorrectPositionWhenObjectIsFoundWithinRange() {
			String[] sortedArray = {"A", "B", "D", "E", "F"};
			InsertionPosition position = computeInsertPositionOfObjInOrderedArray("D", sortedArray, 0, 3);
			assertEquals(2, position.position());
			assertTrue(position.alreadyPresent());
		}

		@Test
		@DisplayName("Should not return position when object is outside range")
		void shouldNotReturnPositionWhenObjectIsOutsideRange() {
			String[] sortedArray = {"A", "B", "D", "E", "F"};
			InsertionPosition position = computeInsertPositionOfObjInOrderedArray("D", sortedArray, 0, 2);
			assertEquals(2, position.position());
			assertFalse(position.alreadyPresent());
		}

		@Test
		@DisplayName("Should return insertion position for object not in array")
		void shouldReturnInsertionPositionForObjectNotInArray() {
			String[] sortedArray = {"A", "B", "D", "E", "F"};
			InsertionPosition position = computeInsertPositionOfObjInOrderedArray("C", sortedArray, String::compareTo);
			assertEquals(2, position.position());
			assertFalse(position.alreadyPresent());

			position = computeInsertPositionOfObjInOrderedArray("Z", sortedArray, String::compareTo);
			assertEquals(5, position.position());
			assertFalse(position.alreadyPresent());

			position = computeInsertPositionOfObjInOrderedArray("A", sortedArray, String::compareTo);
			assertEquals(0, position.position());
			assertTrue(position.alreadyPresent());
		}

		@Test
		@DisplayName("Should work with comparator")
		void shouldWorkWithComparator() {
			String[] sortedArray = {"abc", "bcd", "cde", "def"};
			Comparator<String> comparator = Comparator.comparing(String::length).thenComparing(String::compareTo);

			InsertionPosition position = computeInsertPositionOfObjInOrderedArray("aaa", sortedArray, comparator);
			assertEquals(0, position.position());
			assertFalse(position.alreadyPresent());

			position = computeInsertPositionOfObjInOrderedArray("cde", sortedArray, comparator);
			assertEquals(2, position.position());
			assertTrue(position.alreadyPresent());
		}

		@Test
		@DisplayName("Should compute insert position with ToIntBiFunction")
		void shouldComputeInsertPositionWithToIntBiFunction() {
			Integer[] sortedArray = {10, 20, 30, 40, 50};

			ToIntBiFunction<Integer, String> comparator = (arrayValue, searchValue) ->
				Integer.compare(arrayValue, Integer.parseInt(searchValue));

			InsertionPosition position = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
				"30", sortedArray, 0, sortedArray.length, comparator);
			assertEquals(2, position.position());
			assertTrue(position.alreadyPresent());

			position = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
				"25", sortedArray, 0, sortedArray.length, comparator);
			assertEquals(2, position.position());
			assertFalse(position.alreadyPresent());

			position = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
				"40", sortedArray, 0, 3, comparator);
			assertEquals(3, position.position());
			assertFalse(position.alreadyPresent());

			position = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
				"5", sortedArray, 0, sortedArray.length, comparator);
			assertEquals(0, position.position());
			assertFalse(position.alreadyPresent());

			position = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
				"60", sortedArray, 0, sortedArray.length, comparator);
			assertEquals(5, position.position());
			assertFalse(position.alreadyPresent());

			position = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
				"30", sortedArray, 2, 2, comparator);
			assertEquals(2, position.position());
			assertFalse(position.alreadyPresent());
		}
	}

	@Nested
	@DisplayName("EnumSet tests")
	class EnumSetTests {

		@Test
		@DisplayName("Should convert to EnumSet with valid input")
		void shouldConvertToEnumSetWithValidInput() {
			TestEnum[] input = {TestEnum.VALUE1, TestEnum.VALUE2};
			EnumSet<TestEnum> result = toEnumSet(TestEnum.class, input);
			assertEquals(EnumSet.of(TestEnum.VALUE1, TestEnum.VALUE2), result);
		}

		@Test
		@DisplayName("Should convert to EnumSet with single value")
		void shouldConvertToEnumSetWithSingleValue() {
			TestEnum[] input = {TestEnum.VALUE1};
			EnumSet<TestEnum> result = toEnumSet(TestEnum.class, input);
			assertEquals(EnumSet.of(TestEnum.VALUE1), result);
		}

		@Test
		@DisplayName("Should return empty EnumSet with empty input")
		void shouldReturnEmptyEnumSetWithEmptyInput() {
			TestEnum[] input = {};
			EnumSet<TestEnum> result = toEnumSet(TestEnum.class, input);
			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("Should return empty EnumSet with null input")
		void shouldReturnEmptyEnumSetWithNullInput() {
			TestEnum[] input = null;
			EnumSet<TestEnum> result = toEnumSet(TestEnum.class, input);
			assertTrue(result.isEmpty());
		}
	}

	@Nested
	@DisplayName("Array operations tests")
	class ArrayOperationsTests {

		@Test
		@DisplayName("Should return true for empty object array")
		void shouldReturnTrueForEmptyObjectArray() {
			assertTrue(isEmpty((Object[]) null));
			assertTrue(isEmpty(new Object[0]));
		}

		@Test
		@DisplayName("Should return true for empty int array")
		void shouldReturnTrueForEmptyIntArray() {
			assertTrue(isEmpty((int[]) null));
			assertTrue(isEmpty(new int[0]));
		}

		@Test
		@DisplayName("Should return false for not empty array")
		void shouldReturnFalseForNotEmptyArray() {
			assertFalse(isEmpty(new Object[]{1}));
		}

		@Test
		@DisplayName("Should compute sorted array according the first one")
		void shouldComputeSortedArrayAccordingTheFirstOne() {
			assertArrayEquals(
				new int[]{3, 1, 2, 4, 0, 5},
				computeSortedSecondAlongFirstArray(
					new String[]{"F", "B", "D", "A", "E", "I"},
					new int[]{0, 1, 2, 3, 4, 5}
				)
			);
		}

		@Test
		@DisplayName("Should sort array of integers by comparator")
		void shouldSortArrayOfIntegersByComparator() {
			final String[] external = {"F", "B", "D", "A", "E", "I"};
			final int[] theSortedArray = {0, 1, 2, 3, 4, 5};
			sortArray((o1, o2) -> String.CASE_INSENSITIVE_ORDER.compare(external[o1], external[o2]), theSortedArray);
			assertArrayEquals(
				new int[]{3, 1, 2, 4, 0, 5},
				theSortedArray
			);
		}

		@Test
		@DisplayName("Should sort array by comparator")
		void shouldSortArrayByComparator() {
			final String[] external = {"F", "B", "D", "A", "E", "I"};
			sortArray(String.CASE_INSENSITIVE_ORDER, external);
			assertArrayEquals(
				new String[]{"A", "B", "D", "E", "F", "I"},
				external
			);
		}

		@Test
		@DisplayName("Should extract subarray list")
		void shouldExtractSubarrayList() {
			final Integer[] theArray = {7, 1, 9, 4, 5};
			assertArrayEquals(new Object[]{1, 9, 4}, asList(theArray, 1, 4).toArray());
			assertArrayEquals(new Object[]{7}, asList(theArray, 0, 1).toArray());
			assertArrayEquals(new Object[]{5}, asList(theArray, 4, 5).toArray());
		}

		@Test
		@DisplayName("Should extract subarray")
		void shouldExtractSubarray() {
			final Serializable[] theArray = {7, 1, 9, 4, 5};
			assertArrayEquals(new Integer[]{1, 9, 4}, copyOf(theArray, Integer.class, 1, 4));
			assertArrayEquals(new Integer[]{7}, copyOf(theArray, Integer.class, 0, 1));
			assertArrayEquals(new Integer[]{5}, copyOf(theArray, Integer.class, 4, 5));
		}

		@Test
		@DisplayName("Should sort array")
		void shouldSortArray() {
			final int[] sortedArray = new int[]{8, 5, 6, 2, 9, 7, 1, 3};
			final UnaryOperator<int[]> converter = (in) -> {
				sortAlong(sortedArray, in);
				return in;
			};
			assertArrayEquals(new int[]{5, 1, 3}, converter.apply(new int[]{1, 3, 5}));
			assertArrayEquals(new int[]{8, 5, 2, 1, 3}, converter.apply(new int[]{1, 2, 3, 5, 8}));
			assertArrayEquals(new int[]{8, 5, 2, 4, 10}, converter.apply(new int[]{2, 4, 5, 8, 10}));
		}

		@Test
		@DisplayName("Should sort array with duplicate values")
		void shouldSortArrayWithDuplicateValues() {
			final int[] sortedArray = new int[]{8, 5, 5, 6, 2, 9, 7, 1, 3};
			final UnaryOperator<int[]> converter = (in) -> {
				sortAlong(sortedArray, in);
				return in;
			};
			assertArrayEquals(new int[]{5, 1, 3}, converter.apply(new int[]{1, 3, 5}));
			assertArrayEquals(new int[]{5, 1, 3, 3}, converter.apply(new int[]{1, 3, 3, 5}));
		}

		@Test
		@DisplayName("Should return zero for equal arrays")
		void shouldReturnZeroForEqualArrays() {
			Integer[] array1 = {1, 2, 3};
			Integer[] array2 = {1, 2, 3};

			assertEquals(0, ArrayUtils.compare(array1, array2));
		}

		@Test
		@DisplayName("Should return zero for empty arrays")
		void shouldReturnZeroForEmptyArrays() {
			Integer[] array1 = {};
			Integer[] array2 = {};

			assertEquals(0, ArrayUtils.compare(array1, array2));
		}

		@Test
		@DisplayName("Should return negative for first array lesser")
		void shouldReturnNegativeForFirstArrayLesser() {
			Integer[] array1 = {1, 2, 3};
			Integer[] array2 = {1, 2, 4};

			assertTrue(ArrayUtils.compare(array1, array2) < 0);
		}

		@Test
		@DisplayName("Should return positive for second array lesser")
		void shouldReturnPositiveForSecondArrayLesser() {
			Integer[] array1 = {1, 2, 4};
			Integer[] array2 = {1, 2, 3};

			assertTrue(ArrayUtils.compare(array1, array2) > 0);
		}

		@Test
		@DisplayName("Should compare by length for arrays with same beginning")
		void shouldCompareByLengthForArraysWithSameBeginning() {
			Integer[] array1 = {1, 2, 3};
			Integer[] array2 = {1, 2, 3, 4};

			assertTrue(ArrayUtils.compare(array1, array2) < 0);
		}

		@Test
		@DisplayName("Should return negative when first element is null (null-first ordering)")
		void shouldReturnNegativeWhenFirstElementIsNull() {
			Integer[] array1 = {null, 2};
			Integer[] array2 = {1, 2};

			assertTrue(ArrayUtils.compare(array1, array2) < 0);
		}

		@Test
		@DisplayName("Should return positive when second element is null (null-first ordering)")
		void shouldReturnPositiveWhenSecondElementIsNull() {
			Integer[] array1 = {1, 2};
			Integer[] array2 = {null, 2};

			assertTrue(ArrayUtils.compare(array1, array2) > 0);
		}

		@Test
		@DisplayName("Should skip both-null elements and compare next")
		void shouldSkipBothNullAndCompareNext() {
			Integer[] array1 = {null, 1};
			Integer[] array2 = {null, 2};

			assertTrue(ArrayUtils.compare(array1, array2) < 0);
		}

		@Test
		@DisplayName("Should return zero when both arrays contain only nulls")
		void shouldReturnZeroWhenBothArraysHaveOnlyNulls() {
			Integer[] array1 = {null, null};
			Integer[] array2 = {null, null};

			assertEquals(0, ArrayUtils.compare(array1, array2));
		}

		@Test
		@DisplayName("Should treat null as less than non-null at any position")
		void shouldHandleNullAtDifferentPositions() {
			Integer[] array1 = {1, null, 3};
			Integer[] array2 = {1, 2, 3};

			assertTrue(ArrayUtils.compare(array1, array2) < 0);
		}
	}
}
