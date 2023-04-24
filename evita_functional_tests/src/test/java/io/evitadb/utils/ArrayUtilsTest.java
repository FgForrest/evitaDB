/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.utils;

import io.evitadb.utils.ArrayUtils.InsertionPosition;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test verifies contract of {@link ArrayUtils} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class ArrayUtilsTest {

	@Test
	void shouldReturnTrueForEmptyObjectArray() {
		assertTrue(ArrayUtils.isEmpty((Object[]) null));
		assertTrue(ArrayUtils.isEmpty(new Object[0]));
	}

	@Test
	void shouldReturnTrueForEmptyIntArray() {
		assertTrue(ArrayUtils.isEmpty((int[]) null));
		assertTrue(ArrayUtils.isEmpty(new int[0]));
	}

	@Test
	void shouldReturnFalseForNotEmptyArray() {
		assertFalse(ArrayUtils.isEmpty(new Object[]{1}));
	}

	@Test
	void shouldAddIntsToArray() {
		int[] records = ArrayUtils.insertIntIntoArrayOnIndex(5, new int[0], 0);
		records = ArrayUtils.insertIntIntoArrayOnIndex(2, records, 0);
		records = ArrayUtils.insertIntIntoArrayOnIndex(8, records, 1);
		records = ArrayUtils.insertIntIntoArrayOnIndex(1, records, 0);
		records = ArrayUtils.insertIntIntoArrayOnIndex(1, records, 2);
		records = ArrayUtils.insertIntIntoArrayOnIndex(9, records, 3);

		assertArrayEquals(
			new int[]{1, 2, 1, 9, 8, 5},
			records
		);
	}

	@Test
	void shouldAddIntsToOrderedArrayInProperOrder() {
		int[] records = ArrayUtils.insertIntIntoOrderedArray(5, new int[0]);
		records = ArrayUtils.insertIntIntoOrderedArray(2, records);
		records = ArrayUtils.insertIntIntoOrderedArray(8, records);
		records = ArrayUtils.insertIntIntoOrderedArray(1, records);
		records = ArrayUtils.insertIntIntoOrderedArray(1, records);
		records = ArrayUtils.insertIntIntoOrderedArray(9, records);

		assertArrayEquals(
			new int[]{1, 2, 5, 8, 9},
			records
		);
	}

	@Test
	void shouldRemoveIntsFromArrayAndKeepOrder() {
		int[] records = new int[]{1, 2, 5, 6, 8, 9};
		records = ArrayUtils.removeIntFromOrderedArray(1, records);
		records = ArrayUtils.removeIntFromOrderedArray(5, records);
		records = ArrayUtils.removeIntFromOrderedArray(5, records);
		records = ArrayUtils.removeIntFromOrderedArray(9, records);

		assertArrayEquals(
			new int[]{2, 6, 8},
			records
		);
	}

	@Test
	void shouldAddRecordsToArray() {
		Integer[] records = ArrayUtils.insertRecordIntoArray(5, new Integer[0], 0);
		records = ArrayUtils.insertRecordIntoArray(2, records, 0);
		records = ArrayUtils.insertRecordIntoArray(8, records, 1);
		records = ArrayUtils.insertRecordIntoArray(1, records, 0);
		records = ArrayUtils.insertRecordIntoArray(1, records, 2);
		records = ArrayUtils.insertRecordIntoArray(9, records, 3);

		assertArrayEquals(
			new Integer[]{1, 2, 1, 9, 8, 5},
			records
		);
	}

	@Test
	void shouldAddRecordsToArrayUsingComparator() {
		Integer[] records = ArrayUtils.insertRecordIntoOrderedArray(5, new Integer[0], Integer::compare);
		records = ArrayUtils.insertRecordIntoArray(2, records, 0);
		records = ArrayUtils.insertRecordIntoArray(8, records, 1);
		records = ArrayUtils.insertRecordIntoArray(1, records, 0);
		records = ArrayUtils.insertRecordIntoArray(1, records, 2);
		records = ArrayUtils.insertRecordIntoArray(9, records, 3);

		assertArrayEquals(
			new Integer[]{1, 2, 1, 9, 8, 5},
			records
		);
	}

	@Test
	void shouldAddRecordsToOrderedArrayInProperOrder() {
		Integer[] records = ArrayUtils.insertRecordIntoOrderedArray(5, new Integer[0]);
		records = ArrayUtils.insertRecordIntoOrderedArray(2, records);
		records = ArrayUtils.insertRecordIntoOrderedArray(8, records);
		records = ArrayUtils.insertRecordIntoOrderedArray(1, records);
		records = ArrayUtils.insertRecordIntoOrderedArray(1, records);
		records = ArrayUtils.insertRecordIntoOrderedArray(9, records);

		assertArrayEquals(
			new Integer[]{1, 2, 5, 8, 9},
			records
		);
	}

	@Test
	void shouldRemoveRecordsFromArrayAndKeepOrder() {
		Integer[] records = new Integer[]{1, 2, 5, 6, 8, 9};
		records = ArrayUtils.removeRecordFromOrderedArray(1, records);
		records = ArrayUtils.removeRecordFromOrderedArray(5, records);
		records = ArrayUtils.removeRecordFromOrderedArray(5, records);
		records = ArrayUtils.removeRecordFromOrderedArray(9, records);

		assertArrayEquals(
			new Integer[]{2, 6, 8},
			records
		);
	}

	@Test
	void shouldRemoveRecordsFromArrayAndKeepOrderUsingComparator() {
		Integer[] records = new Integer[]{1, 2, 5, 6, 8, 9};
		records = ArrayUtils.removeRecordFromOrderedArray(1, records, Integer::compareTo);
		records = ArrayUtils.removeRecordFromOrderedArray(5, records, Integer::compareTo);
		records = ArrayUtils.removeRecordFromOrderedArray(5, records, Integer::compareTo);
		records = ArrayUtils.removeRecordFromOrderedArray(9, records, Integer::compareTo);

		assertArrayEquals(
			new Integer[]{2, 6, 8},
			records
		);
	}

	@Test
	void shouldComputeSortedArrayAccordingTheFirstOne() {
		assertArrayEquals(
			new int[]{3, 1, 2, 4, 0, 5},
			ArrayUtils.computeSortedSecondAlongFirstArray(
				new String[]{"F", "B", "D", "A", "E", "I"},
				new int[]{0, 1, 2, 3, 4, 5}
			)
		);
	}

	@Test
	void shouldSortedArrayByComparator() {
		final String[] external = {"F", "B", "D", "A", "E", "I"};
		final int[] theSortedArray = {0, 1, 2, 3, 4, 5};
		ArrayUtils.sortArray((o1, o2) -> String.CASE_INSENSITIVE_ORDER.compare(external[o1], external[o2]), theSortedArray);
		assertArrayEquals(
			new int[]{3, 1, 2, 4, 0, 5},
			theSortedArray
		);
	}

	@Test
	void shouldSearchByComparator() {
		final String controlString = "312405687";
		final int[] unsortedArray = {3, 1, 2, 4, 0, 5, 6, 8, 7};
		final IntBinaryOperator comparator = (o1, o2) -> Integer.compare(controlString.indexOf(String.valueOf(o1)), controlString.indexOf(String.valueOf(o2)));
		assertEquals(0, ArrayUtils.binarySearch(unsortedArray, 3, comparator));
		assertEquals(1, ArrayUtils.binarySearch(unsortedArray, 1, comparator));
		assertEquals(2, ArrayUtils.binarySearch(unsortedArray, 2, comparator));
		assertEquals(3, ArrayUtils.binarySearch(unsortedArray, 4, comparator));
		assertEquals(4, ArrayUtils.binarySearch(unsortedArray, 0, comparator));
		assertEquals(5, ArrayUtils.binarySearch(unsortedArray, 5, comparator));
		assertEquals(6, ArrayUtils.binarySearch(unsortedArray, 6, comparator));
		assertEquals(7, ArrayUtils.binarySearch(unsortedArray, 8, comparator));
		assertEquals(8, ArrayUtils.binarySearch(unsortedArray, 7, comparator));
	}

	@Test
	void computeInsertPositionOfIntInOrderedArrayUsingComparator() {
		final String controlString = "312406587";
		final int[] unsortedArray = {3, 1, 4, 0, 5, 8, 7};
		final IntBinaryOperator comparator = (o1, o2) -> Integer.compare(controlString.indexOf(String.valueOf(o1)), controlString.indexOf(String.valueOf(o2)));
		assertInsertPosition(ArrayUtils.computeInsertPositionOfIntInOrderedArray(3, unsortedArray, comparator), true, 0);
		assertInsertPosition(ArrayUtils.computeInsertPositionOfIntInOrderedArray(1, unsortedArray, comparator), true, 1);
		assertInsertPosition(ArrayUtils.computeInsertPositionOfIntInOrderedArray(4, unsortedArray, comparator), true, 2);
		assertInsertPosition(ArrayUtils.computeInsertPositionOfIntInOrderedArray(0, unsortedArray, comparator), true, 3);
		assertInsertPosition(ArrayUtils.computeInsertPositionOfIntInOrderedArray(5, unsortedArray, comparator), true, 4);
		assertInsertPosition(ArrayUtils.computeInsertPositionOfIntInOrderedArray(8, unsortedArray, comparator), true, 5);
		assertInsertPosition(ArrayUtils.computeInsertPositionOfIntInOrderedArray(7, unsortedArray, comparator), true, 6);
		assertInsertPosition(ArrayUtils.computeInsertPositionOfIntInOrderedArray(2, unsortedArray, comparator), false, 2);
		assertInsertPosition(ArrayUtils.computeInsertPositionOfIntInOrderedArray(6, unsortedArray, comparator), false, 4);
	}

	@Test
	void insertIntoOrderedArrayUsingComparator() {
		final String controlString = "312406587";
		final int[] unsortedArray = {3, 1, 4, 0, 5, 8, 7};
		final IntBinaryOperator comparator = (o1, o2) -> Integer.compare(controlString.indexOf(String.valueOf(o1)), controlString.indexOf(String.valueOf(o2)));
		final int[] array1 = ArrayUtils.insertIntIntoOrderedArray(2, unsortedArray, comparator);
		assertArrayEquals(
			new int[]{3, 1, 2, 4, 0, 5, 8, 7},
			array1
		);
		final int[] array2 = ArrayUtils.insertIntIntoOrderedArray(6, array1, comparator);
		assertArrayEquals(
			new int[]{3, 1, 2, 4, 0, 6, 5, 8, 7},
			array2
		);
	}

	@Test
	void shouldFindIndexInUnsortedArray() {
		final int[] theArray = {7, 1, 9, 4, 5};
		assertEquals(-1, ArrayUtils.indexOf(8, theArray));
		assertEquals(0, ArrayUtils.indexOf(7, theArray));
		assertEquals(4, ArrayUtils.indexOf(5, theArray));
		assertEquals(1, ArrayUtils.indexOf(1, theArray));
	}

	@Test
	void shouldExtractSubarrayList() {
		final Integer[] theArray = {7, 1, 9, 4, 5};
		assertArrayEquals(new Object[] {1, 9, 4}, ArrayUtils.asList(theArray, 1, 4).toArray());
		assertArrayEquals(new Object[] {7}, ArrayUtils.asList(theArray, 0, 1).toArray());
		assertArrayEquals(new Object[] {5}, ArrayUtils.asList(theArray, 4, 5).toArray());
	}

	@Test
	void shouldExtractSubarray() {
		final Serializable[] theArray = {7, 1, 9, 4, 5};
		assertArrayEquals(new Integer[] {1, 9, 4}, ArrayUtils.copyOf(theArray, Integer.class, 1, 4));
		assertArrayEquals(new Integer[] {7}, ArrayUtils.copyOf(theArray, Integer.class, 0, 1));
		assertArrayEquals(new Integer[] {5}, ArrayUtils.copyOf(theArray, Integer.class, 4, 5));
	}

	@Test
	void shouldSortArray() {
		final int[] sortedArray = new int[] {8, 5, 6, 2, 9, 7, 1, 3};
		assertArrayEquals(new int[] {5, 1, 3}, ArrayUtils.sortAlong(sortedArray, new int[] {1, 3, 5}));
		assertArrayEquals(new int[] {8, 5, 2, 1, 3}, ArrayUtils.sortAlong(sortedArray, new int[] {1, 2, 3, 5, 8}));
		assertArrayEquals(new int[] {8, 5, 2, 4, 10}, ArrayUtils.sortAlong(sortedArray, new int[] {2, 4, 5, 8, 10}));
	}


	private void assertInsertPosition(InsertionPosition insertionPosition, boolean found, int index) {
		if (found) {
			assertTrue(insertionPosition.alreadyPresent());
		} else {
			assertFalse(insertionPosition.alreadyPresent());
		}
		assertEquals(index, insertionPosition.position());
	}
}