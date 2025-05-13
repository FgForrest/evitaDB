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

import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.function.IntBinaryOperator;
import java.util.function.ToIntBiFunction;
import java.util.function.UnaryOperator;

import static io.evitadb.utils.ArrayUtils.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test verifies contract of {@link ArrayUtils} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class ArrayUtilsTest {

	private static void assertInsertPosition(InsertionPosition insertionPosition, boolean found, int index) {
		if (found) {
			assertTrue(insertionPosition.alreadyPresent());
		} else {
			assertFalse(insertionPosition.alreadyPresent());
		}
		assertEquals(index, insertionPosition.position());
	}

	@Test
	public void shouldInsertValueAtBeginning() {
		int[] array = {10, 20, 30, 40, 50};
		insertIntIntoSameArrayOnIndex(5, array, 0);
		assertArrayEquals(new int[]{5, 10, 20, 30, 40}, array);
	}

	@Test
	public void shouldInsertValueAtMiddle() {
		int[] array = {10, 20, 30, 40, 50};
		insertIntIntoSameArrayOnIndex(25, array, 2);
		assertArrayEquals(new int[]{10, 20, 25, 30, 40}, array);
	}

	@Test
	public void shouldInsertValueAtEnd() {
		int[] array = {10, 20, 30, 40, 50};
		insertIntIntoSameArrayOnIndex(60, array, 4);
		assertArrayEquals(new int[]{10, 20, 30, 40, 60}, array);
	}

	@Test
	public void shouldInsertValueIntoSingleElementArray() {
		int[] array = {10};
		insertIntIntoSameArrayOnIndex(5, array, 0);
		assertArrayEquals(new int[]{5}, array);
	}

	@Test
	public void shouldFailToInsertValueAtInvalidPosition() {
		int[] array = {10, 20, 30, 40, 50};
		assertThrows(IndexOutOfBoundsException.class, () -> {
			insertIntIntoSameArrayOnIndex(5, array, 5);
		});
	}

	@Test
	public void shouldInsertRecordAtBeginningOfArray() {
		Integer[] array = {1, 2, 3, 4, 5};
		Integer newRecord = 99;
		Integer[] expected = {99, 1, 2, 3, 4};

		insertRecordIntoSameArrayOnIndex(newRecord, array, 0);
		assertArrayEquals(expected, array);
	}

	@Test
	public void shouldInsertRecordInMiddleOfArray() {
		Integer[] array = {1, 2, 3, 4, 5};
		Integer newRecord = 99;
		Integer[] expected = {1, 2, 99, 3, 4};

		insertRecordIntoSameArrayOnIndex(newRecord, array, 2);
		assertArrayEquals(expected, array);
	}

	@Test
	public void shouldInsertRecordAtEndOfArrayAndOverwriteLastElement() {
		Integer[] array = {1, 2, 3, 4, 5};
		Integer newRecord = 99;
		Integer[] expected = {1, 2, 3, 4, 99};

		insertRecordIntoSameArrayOnIndex(newRecord, array, 4);
		assertArrayEquals(expected, array);
	}

	@Test
	public void shouldThrowArrayIndexOutOfBoundsExceptionForInvalidIndex() {
		Integer[] array = {1, 2, 3, 4, 5};
		Integer newRecord = 99;

		assertThrows(ArrayIndexOutOfBoundsException.class, () ->
			insertRecordIntoSameArrayOnIndex(newRecord, array, 5)
		);
	}

	@Test
	public void shouldWorkWithStrings() {
		String[] array = {"a", "b", "c", "d", "e"};
		String newRecord = "z";
		String[] expected = {"a", "b", "z", "c", "d"};

		insertRecordIntoSameArrayOnIndex(newRecord, array, 2);
		assertArrayEquals(expected, array);
	}

	@Test
	void shouldReturnCorrectPositionWhenElementIsFound() {
		int[] recordIds = {1, 3, 5, 7, 9};
		InsertionPosition position = computeInsertPositionOfIntInOrderedArray(5, recordIds, 0, recordIds.length);
		assertEquals(2, position.position());
		assertTrue(position.alreadyPresent());
	}

	@Test
	void shouldReturnInsertionPointWhenElementIsNotFoundBeforeStart() {
		int[] recordIds = {1, 3, 5, 7, 9};
		InsertionPosition position = computeInsertPositionOfIntInOrderedArray(0, recordIds, 0, recordIds.length);
		assertEquals(0, position.position());
		assertFalse(position.alreadyPresent());
	}

	@Test
	void shouldReturnInsertionPointWhenElementIsNotFoundInMiddle() {
		int[] recordIds = {1, 3, 5, 7, 9};
		InsertionPosition position = computeInsertPositionOfIntInOrderedArray(6, recordIds, 0, recordIds.length);
		assertEquals(3, position.position());
		assertFalse(position.alreadyPresent());
	}

	@Test
	void shouldReturnInsertionPointWhenElementIsNotFoundAtEnd() {
		int[] recordIds = {1, 3, 5, 7, 9};
		InsertionPosition position = computeInsertPositionOfIntInOrderedArray(10, recordIds, 0, recordIds.length);
		assertEquals(5, position.position());
		assertFalse(position.alreadyPresent());
	}

	@Test
	void shouldReturnZeroAndNotExistingForEmptyArray() {
		int[] recordIds = {};
		InsertionPosition position = computeInsertPositionOfIntInOrderedArray(10, recordIds, 0, 0);
		assertEquals(0, position.position());
		assertFalse(position.alreadyPresent());
	}

	@Test
	void shouldReturnZeroAndNotExistingWhenToIndexIsLessThanOrEqualToFromIndex() {
		int[] recordIds = {1, 3, 5, 7, 9};
		InsertionPosition position = computeInsertPositionOfIntInOrderedArray(3, recordIds, 3, 3);
		assertEquals(0, position.position());
		assertFalse(position.alreadyPresent());
	}

	@Test
	void shouldReturnZeroAndNotExistingWhenValueIsAfterTheIndex() {
		int[] recordIds = {1, 3, 5, 7, 9};
		InsertionPosition position = computeInsertPositionOfIntInOrderedArray(7, recordIds, 0, 3);
		assertEquals(3, position.position());
		assertFalse(position.alreadyPresent());
	}

	@Test
	void shouldReturnZeroAndNotExistingWhenValueIsAfterTheIndex2() {
		int[] recordIds = {1, 3, 5, 7, 9};
		InsertionPosition position = computeInsertPositionOfIntInOrderedArray(9, recordIds, 0, 3);
		assertEquals(3, position.position());
		assertFalse(position.alreadyPresent());
	}

	@Test
	void testToEnumSetWithValidInput() {
		TestEnum[] input = {TestEnum.VALUE1, TestEnum.VALUE2};
		EnumSet<TestEnum> result = toEnumSet(TestEnum.class, input);
		assertEquals(EnumSet.of(TestEnum.VALUE1, TestEnum.VALUE2), result);
	}

	@Test
	void testToEnumSetWithSingleValueInput() {
		TestEnum[] input = {TestEnum.VALUE1};
		EnumSet<TestEnum> result = toEnumSet(TestEnum.class, input);
		assertEquals(EnumSet.of(TestEnum.VALUE1), result);
	}

	@Test
	void testToEnumSetWithEmptyInput() {
		TestEnum[] input = {};
		EnumSet<TestEnum> result = toEnumSet(TestEnum.class, input);
		assertTrue(result.isEmpty());
	}

	@Test
	void testToEnumSetWithNullInput() {
		TestEnum[] input = null;
		EnumSet<TestEnum> result = toEnumSet(TestEnum.class, input);
		assertTrue(result.isEmpty());
	}

	@Test
	void shouldReturnTrueForEmptyObjectArray() {
		assertTrue(isEmpty((Object[]) null));
		assertTrue(isEmpty(new Object[0]));
	}

	@Test
	void shouldReturnTrueForEmptyIntArray() {
		assertTrue(isEmpty((int[]) null));
		assertTrue(isEmpty(new int[0]));
	}

	@Test
	void shouldReturnFalseForNotEmptyArray() {
		assertFalse(isEmpty(new Object[]{1}));
	}

	@Test
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
	void shouldSortArrayByComparator() {
		final String[] external = {"F", "B", "D", "A", "E", "I"};
		sortArray(String.CASE_INSENSITIVE_ORDER, external);
		assertArrayEquals(
			new String[]{"A", "B", "D", "E", "F", "I"},
			external
		);
	}

	@Test
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
	void computeInsertPositionOfIntInOrderedArrayUsingComparator() {
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
	void insertIntoOrderedArrayUsingComparator() {
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

	@Test
	void shouldFindIndexInUnsortedArray() {
		final int[] theArray = {7, 1, 9, 4, 5};
		assertEquals(-1, indexOf(8, theArray));
		assertEquals(0, indexOf(7, theArray));
		assertEquals(4, indexOf(5, theArray));
		assertEquals(1, indexOf(1, theArray));
	}

	@Test
	void shouldExtractSubarrayList() {
		final Integer[] theArray = {7, 1, 9, 4, 5};
		assertArrayEquals(new Object[]{1, 9, 4}, asList(theArray, 1, 4).toArray());
		assertArrayEquals(new Object[]{7}, asList(theArray, 0, 1).toArray());
		assertArrayEquals(new Object[]{5}, asList(theArray, 4, 5).toArray());
	}

	@Test
	void shouldExtractSubarray() {
		final Serializable[] theArray = {7, 1, 9, 4, 5};
		assertArrayEquals(new Integer[]{1, 9, 4}, copyOf(theArray, Integer.class, 1, 4));
		assertArrayEquals(new Integer[]{7}, copyOf(theArray, Integer.class, 0, 1));
		assertArrayEquals(new Integer[]{5}, copyOf(theArray, Integer.class, 4, 5));
	}

	@Test
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
	void shouldRemoveRangeFromArray() {
		final int[] someArray = new int[]{8, 5, 6, 2, 9, 7, 1, 3};
		assertArrayEquals(new int[]{2, 9, 7, 1, 3}, removeRangeFromArray(someArray, 0, 3));
		assertArrayEquals(new int[]{8, 2, 9, 7, 1, 3}, removeRangeFromArray(someArray, 1, 3));
		assertArrayEquals(new int[]{8, 5, 6, 2, 7, 1, 3}, removeRangeFromArray(someArray, 4, 5));
		assertArrayEquals(new int[]{8, 5, 6, 2, 9, 7}, removeRangeFromArray(someArray, 6, 8));
	}

	@Test
	void shouldRemoveIntFromBeginning() {
		int[] numbers = {10, 20, 30, 40, 50};
		ArrayUtils.removeIntFromSameArrayOnIndex(numbers, 0);
		assertArrayEquals(new int[]{20, 30, 40, 50, 50}, numbers);
	}

	@Test
	void shouldRemoveIntFromMiddle() {
		int[] numbers = {10, 20, 30, 40, 50};
		ArrayUtils.removeIntFromSameArrayOnIndex(numbers, 2);
		assertArrayEquals(new int[]{10, 20, 40, 50, 50}, numbers);
	}

	@Test
	void shouldRemoveIntFromEnd() {
		int[] numbers = {10, 20, 30, 40, 50};
		ArrayUtils.removeIntFromSameArrayOnIndex(numbers, 4);
		assertArrayEquals(new int[]{10, 20, 30, 40, 50}, numbers); // no change as it's the last int
	}

	@Test
	void shouldHandleSingleIntArray() {
		int[] numbers = {10};
		ArrayUtils.removeIntFromSameArrayOnIndex(numbers, 0);
		assertArrayEquals(new int[]{10}, numbers); // no valid replacement, last int stays "undefined" as before
	}

	@Test
	void shouldFailToRemoveIntFromEmptyArray() {
		int[] numbers = {};
		assertThrows(ArrayIndexOutOfBoundsException.class, () -> ArrayUtils.removeIntFromSameArrayOnIndex(numbers, 0));
	}

	@Test
	void shouldRemoveElementFromBeginning() {
		Integer[] numbers = {10, 20, 30, 40, 50};
		ArrayUtils.removeRecordFromSameArrayOnIndex(numbers, 0);
		assertArrayEquals(new Integer[]{20, 30, 40, 50, 50}, numbers);
	}

	@Test
	void shouldRemoveElementFromMiddle() {
		Integer[] numbers = {10, 20, 30, 40, 50};
		ArrayUtils.removeRecordFromSameArrayOnIndex(numbers, 2);
		assertArrayEquals(new Integer[]{10, 20, 40, 50, 50}, numbers);
	}

	@Test
	void shouldRemoveElementFromEnd() {
		Integer[] numbers = {10, 20, 30, 40, 50};
		ArrayUtils.removeRecordFromSameArrayOnIndex(numbers, 4);
		assertArrayEquals(new Integer[]{10, 20, 30, 40, 50}, numbers); // no change as it's the last element
	}

	@Test
	void shouldHandleSingleElementArray() {
		Integer[] numbers = {10};
		ArrayUtils.removeRecordFromSameArrayOnIndex(numbers, 0);
		assertArrayEquals(new Integer[]{10}, numbers); // no valid replacement, last element stays "undefined" as before
	}

	@Test
	void shouldFailToRemoveElementFromEmptyArray() {
		Integer[] numbers = {};
		assertThrows(ArrayIndexOutOfBoundsException.class, () -> ArrayUtils.removeRecordFromSameArrayOnIndex(numbers, 0));
	}

	private enum TestEnum {
		VALUE1, VALUE2, VALUE3
	}

	@Test
	void shouldReturnCorrectPositionWhenObjectIsFound() {
		String[] sortedArray = {"A", "B", "D", "E", "F"};
		InsertionPosition position = computeInsertPositionOfObjInOrderedArray("D", sortedArray, String::compareTo);
		assertEquals(2, position.position());
		assertTrue(position.alreadyPresent());
	}

	@Test
	void shouldReturnCorrectPositionWhenObjectIsFoundWithinRange() {
		String[] sortedArray = {"A", "B", "D", "E", "F"};
		InsertionPosition position = computeInsertPositionOfObjInOrderedArray("D", sortedArray, 0, 3);
		assertEquals(2, position.position());
		assertTrue(position.alreadyPresent());
	}

	@Test
	void shouldNotReturnPositionWhenObjectIsOutsideRange() {
		String[] sortedArray = {"A", "B", "D", "E", "F"};
		InsertionPosition position = computeInsertPositionOfObjInOrderedArray("D", sortedArray, 0, 2);
		assertEquals(2, position.position());
		assertFalse(position.alreadyPresent());
	}

	@Test
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
	void shouldReturnZeroForEqualArrays() {
		Integer[] array1 = {1, 2, 3};
		Integer[] array2 = {1, 2, 3};

		assertEquals(0, ArrayUtils.compare(array1, array2));
	}

	@Test
	void shouldReturnZeroForEmptyArrays() {
		Integer[] array1 = {};
		Integer[] array2 = {};

		assertEquals(0, ArrayUtils.compare(array1, array2));
	}

	@Test
	void shouldReturnNegativeForFirstArrayLesser() {
		Integer[] array1 = {1, 2, 3};
		Integer[] array2 = {1, 2, 4};

		assertTrue(ArrayUtils.compare(array1, array2) < 0);
	}

	@Test
	void shouldReturnPositiveForSecondArrayLesser() {
		Integer[] array1 = {1, 2, 4};
		Integer[] array2 = {1, 2, 3};

		assertTrue(ArrayUtils.compare(array1, array2) > 0);
	}

	@Test
	void shouldCompareByLengthForArraysWithSameBeginning() {
		Integer[] array1 = {1, 2, 3};
		Integer[] array2 = {1, 2, 3, 4};

		assertTrue(ArrayUtils.compare(array1, array2) < 0);
	}

	@Test
	void shouldComputeInsertPositionWithToIntBiFunction() {
		// Setup an array of integers
		Integer[] sortedArray = {10, 20, 30, 40, 50};

		// Test with a string value that needs to be converted to int for comparison
		// The ToIntBiFunction compares the Integer from the array with the String value
		ToIntBiFunction<Integer, String> comparator = (arrayValue, searchValue) ->
			Integer.compare(arrayValue, Integer.parseInt(searchValue));

		// Test finding an existing value
		InsertionPosition position = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
			"30", sortedArray, 0, sortedArray.length, comparator);
		assertEquals(2, position.position());
		assertTrue(position.alreadyPresent());

		// Test finding insertion point for a value not in the array
		position = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
			"25", sortedArray, 0, sortedArray.length, comparator);
		assertEquals(2, position.position());
		assertFalse(position.alreadyPresent());

		// Test with a limited range where the value exists but is outside the range
		position = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
			"40", sortedArray, 0, 3, comparator);
		assertEquals(3, position.position());
		assertFalse(position.alreadyPresent());

		// Test with a value that would be inserted at the beginning
		position = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
			"5", sortedArray, 0, sortedArray.length, comparator);
		assertEquals(0, position.position());
		assertFalse(position.alreadyPresent());

		// Test with a value that would be inserted at the end
		position = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
			"60", sortedArray, 0, sortedArray.length, comparator);
		assertEquals(5, position.position());
		assertFalse(position.alreadyPresent());

		// Test with an empty range
		position = ArrayUtils.computeInsertPositionOfObjInOrderedArray(
			"30", sortedArray, 2, 2, comparator);
		assertEquals(2, position.position());
		assertFalse(position.alreadyPresent());
	}

}
