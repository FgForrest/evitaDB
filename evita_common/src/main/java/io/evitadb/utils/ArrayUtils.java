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

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Array;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.IntBinaryOperator;
import java.util.function.IntFunction;
import java.util.function.ToIntBiFunction;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

/**
 * String utils contains base method for working with Array operations.
 * We know these functions are available in Apache Commons, but we try to keep our transitive dependencies as low as
 * possible, so we rather went through duplication of the code.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ArrayUtils {

	public static final Class<?>[] EMPTY_CLASS_ARRAY = new Class[0];
	public static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];
	public static final String[] EMPTY_STRING_ARRAY = new String[0];
	public static final int[] EMPTY_INT_ARRAY = new int[0];
	public static final long[] EMPTY_LONG_ARRAY = new long[0];

	/**
	 * Returns true if array is either null or has no items in it
	 *
	 * @param array to check
	 * @return true if empty
	 */
	public static boolean isEmpty(@Nullable Object[] array) {
		return array == null || array.length == 0;
	}

	/**
	 * Returns true if array is either null or has no items in it, or all the items are null.
	 *
	 * @param array to check
	 * @return true if empty
	 */
	public static boolean isEmptyOrItsValuesNull(@Nullable Object[] array) {
		return array == null || array.length == 0 || Arrays.stream(array).allMatch(Objects::isNull);
	}

	/**
	 * Returns true if array is either null or has no items in it
	 *
	 * @param array to check
	 * @return true if empty
	 */
	public static boolean isEmpty(@Nullable int[] array) {
		return array == null || array.length == 0;
	}

	/**
	 * Returns true if array is either null or has no items in it
	 *
	 * @param array to check
	 * @return true if empty
	 */
	public static boolean isEmpty(@Nullable long[] array) {
		return array == null || array.length == 0;
	}

	/**
	 * Searches a range of the specified array of object for the specified value using the binary search algorithm.
	 * The range must be sorted (as by the {@link Arrays#sort(Object[])} method) prior to making this call.  If it
	 * is not sorted, the results are undefined.  If the range contains multiple elements with the specified value,
	 * there is no guarantee which one will be found.
	 *
	 * @param a          the array to be searched
	 * @param b          the value that should be compared
	 * @param comparator that envelopes the value to be searched for and returns negative integer for lesser values and
	 *                   positive integer for greater values when comparing passed value with the input one
	 * @return index of the search key, if it is contained in the array within the specified range;
	 * otherwise, <code>(-(<i>insertion point</i>) - 1)</code>. The <i>insertion point</i> is defined as the point at
	 * which the key would be inserted into the array: the index of the first element in the range greater than the key,
	 * or {@code toIndex} if all elements in the range are less than the specified key.
	 * Note that this guarantees that the return value will be &gt;= 0 if and only if the key is found.
	 * @throws IllegalArgumentException       if {@code fromIndex > toIndex}
	 * @throws ArrayIndexOutOfBoundsException if {@code fromIndex < 0 or toIndex > a.length}
	 */
	public static <T, U> int binarySearch(@Nonnull T[] a, @Nonnull U b, @Nonnull ToIntBiFunction<T, U> comparator) {
		return binarySearch(a, b, 0, a.length, comparator);
	}

	/**
	 * Searches a range of the specified array of object for the specified value using the binary search algorithm.
	 * The range must be sorted (as by the {@link Arrays#sort(Object[])} method) prior to making this call.  If it
	 * is not sorted, the results are undefined.  If the range contains multiple elements with the specified value,
	 * there is no guarantee which one will be found.
	 *
	 * @param a          the array to be searched
	 * @param a          the item to be looked up
	 * @param fromIndex  the index of the first element (inclusive) to be
	 *                   searched
	 * @param toIndex    the index of the last element (exclusive) to be searched
	 * @param comparator that envelopes the value to be searched for and returns negative integer for lesser values and
	 *                   positive integer for greater values when comparing passed value with the input one
	 * @return index of the search key, if it is contained in the array within the specified range;
	 * otherwise, <code>(-(<i>insertion point</i>) - 1)</code>. The <i>insertion point</i> is defined as the point at
	 * which the key would be inserted into the array: the index of the first element in the range greater than the key,
	 * or {@code toIndex} if all elements in the range are less than the specified key.
	 * Note that this guarantees that the return value will be &gt;= 0 if and only if the key is found.
	 * @throws IllegalArgumentException       if {@code fromIndex > toIndex}
	 * @throws ArrayIndexOutOfBoundsException if {@code fromIndex < 0 or toIndex > a.length}
	 */
	public static <T, U> int binarySearch(@Nonnull T[] a, @Nonnull U b, int fromIndex, int toIndex, @Nonnull ToIntBiFunction<T, U> comparator) {
		if (fromIndex > toIndex) {
			throw new IllegalArgumentException(
				"fromIndex(" + fromIndex + ") > toIndex(" + toIndex + ")");
		}
		if (fromIndex < 0) {
			throw new ArrayIndexOutOfBoundsException(fromIndex);
		}
		if (toIndex > a.length) {
			throw new ArrayIndexOutOfBoundsException(toIndex);
		}

		int low = fromIndex;
		int high = toIndex - 1;

		while (low <= high) {
			int mid = (low + high) >>> 1;
			T midVal = a[mid];

			final int comparisonResult = comparator.applyAsInt(midVal, b);
			if (comparisonResult < 0)
				low = mid + 1;
			else if (comparisonResult > 0)
				high = mid - 1;
			else
				return mid; // key found
		}
		return -(low + 1);  // key not found.
	}

	/**
	 * Searches a range of the specified array of object for the specified value using the binary search algorithm.
	 * The range must be sorted (as by the {@link Arrays#sort(Object[])} method) prior to making this call.  If it
	 * is not sorted, the results are undefined.  If the range contains multiple elements with the specified value,
	 * there is no guarantee which one will be found.
	 *
	 * @param valueSupplier that extracts particular value for passed index
	 * @param b             the value that should be compared
	 * @param totalCount    the total number of elements in the searched data structure
	 * @param comparator    that envelopes the value to be searched for and returns negative integer for lesser values and
	 *                      positive integer for greater values when comparing passed value with the input one
	 * @return index of the search key, if it is contained in the array within the specified range;
	 * otherwise, <code>(-(<i>insertion point</i>) - 1)</code>. The <i>insertion point</i> is defined as the point at
	 * which the key would be inserted into the array: the index of the first element in the range greater than the key,
	 * or {@code toIndex} if all elements in the range are less than the specified key.
	 * Note that this guarantees that the return value will be &gt;= 0 if and only if the key is found.
	 * @throws IllegalArgumentException       if {@code fromIndex > toIndex}
	 * @throws ArrayIndexOutOfBoundsException if {@code fromIndex < 0 or toIndex > a.length}
	 */
	public static <T, U> int binarySearch(@Nonnull IntFunction<T> valueSupplier, @Nonnull U b, int totalCount, @Nonnull ToIntBiFunction<T, U> comparator) {
		return binarySearch(valueSupplier, b, 0, totalCount, totalCount, comparator);
	}

	/**
	 * Searches a range of the specified array of object for the specified value using the binary search algorithm.
	 * The range must be sorted (as by the {@link Arrays#sort(Object[])} method) prior to making this call.  If it
	 * is not sorted, the results are undefined.  If the range contains multiple elements with the specified value,
	 * there is no guarantee which one will be found.
	 *
	 * @param valueSupplier that extracts particular value for passed index
	 * @param b             the value that should be compared
	 * @param fromIndex     the index of the first element (inclusive) to be
	 *                      searched
	 * @param toIndex       the index of the last element (exclusive) to be searched
	 * @param totalCount    the total number of elements in the searched data structure
	 * @param comparator    that envelopes the value to be searched for and returns negative integer for lesser values and
	 *                      positive integer for greater values when comparing passed value with the input one
	 * @return index of the search key, if it is contained in the array within the specified range;
	 * otherwise, <code>(-(<i>insertion point</i>) - 1)</code>. The <i>insertion point</i> is defined as the point at
	 * which the key would be inserted into the array: the index of the first element in the range greater than the key,
	 * or {@code toIndex} if all elements in the range are less than the specified key.
	 * Note that this guarantees that the return value will be &gt;= 0 if and only if the key is found.
	 * @throws IllegalArgumentException       if {@code fromIndex > toIndex}
	 * @throws ArrayIndexOutOfBoundsException if {@code fromIndex < 0 or toIndex > a.length}
	 */
	public static <T, U> int binarySearch(@Nonnull IntFunction<T> valueSupplier, @Nonnull U b, int fromIndex, int toIndex, int totalCount, @Nonnull ToIntBiFunction<T, U> comparator) {
		if (fromIndex > toIndex) {
			throw new IllegalArgumentException(
				"fromIndex(" + fromIndex + ") > toIndex(" + toIndex + ")");
		}
		if (fromIndex < 0) {
			throw new ArrayIndexOutOfBoundsException(fromIndex);
		}
		if (toIndex > totalCount) {
			throw new ArrayIndexOutOfBoundsException(toIndex);
		}

		int low = fromIndex;
		int high = toIndex - 1;

		while (low <= high) {
			int mid = (low + high) >>> 1;
			T midVal = valueSupplier.apply(mid);

			final int comparisonResult = comparator.applyAsInt(midVal, b);
			if (comparisonResult < 0)
				low = mid + 1;
			else if (comparisonResult > 0)
				high = mid - 1;
			else
				return mid; // key found
		}
		return -(low + 1);  // key not found.
	}

	/**
	 * This variant of binary search allows duplicate records in terms of {@link Comparator} returning zero for multiple
	 * elements of the array. In that sense the binary search scans array to the beginning until it discovers first
	 * element which returns negative number for the {@link Comparator} or reaches the beginning (and in such case it
	 * returns first element of the array).
	 *
	 * Searches a range of the specified array of object for the specified value using the binary search algorithm.
	 * The range must be sorted (as by the {@link Arrays#sort(Object[])} method) prior to making this call.  If it
	 * is not sorted, the results are undefined.  If the range contains multiple elements with the specified value,
	 * there is no guarantee which one will be found.
	 *
	 * @param a          the array to be searched
	 * @param b          the value that should be compared
	 * @param comparator that envelopes the value to be searched for and returns negative integer for lesser values and
	 *                   positive integer for greater values when comparing passed value with the input one
	 * @return index of the search key, if it is contained in the array within the specified range;
	 * otherwise, <code>(-(<i>insertion point</i>) - 1)</code>. The <i>insertion point</i> is defined as the point at
	 * which the key would be inserted into the array: the index of the first element in the range greater than the key,
	 * or {@code toIndex} if all elements in the range are less than the specified key.
	 * Note that this guarantees that the return value will be &gt;= 0 if and only if the key is found.
	 * @throws IllegalArgumentException       if {@code fromIndex > toIndex}
	 * @throws ArrayIndexOutOfBoundsException if {@code fromIndex < 0 or toIndex > a.length}
	 */
	public static <T, U> int binarySearchWithDuplicates(@Nonnull T[] a, @Nonnull U b, @Nonnull ToIntBiFunction<T, U> comparator) {
		return binarySearchWithDuplicates(a, b, 0, a.length, comparator);
	}

	/**
	 * This variant of binary search allows duplicate records in terms of {@link Comparator} returning zero for multiple
	 * elements of the array. In that sense the binary search scans array to the beginning until it discovers first
	 * element which returns negative number for the {@link Comparator} or reaches the beginning (and in such case it
	 * returns first element of the array).
	 *
	 * Searches a range of the specified array of object for the specified value using the binary search algorithm.
	 * The range must be sorted (as by the {@link Arrays#sort(Object[])} method) prior to making this call.  If it
	 * is not sorted, the results are undefined.  If the range contains multiple elements with the specified value,
	 * there is no guarantee which one will be found.
	 *
	 * @param a          the array to be searched
	 * @param a          the item to be looked up
	 * @param fromIndex  the index of the first element (inclusive) to be
	 *                   searched
	 * @param toIndex    the index of the last element (exclusive) to be searched
	 * @param comparator that envelopes the value to be searched for and returns negative integer for lesser values and
	 *                   positive integer for greater values when comparing passed value with the input one
	 * @return index of the search key, if it is contained in the array within the specified range;
	 * otherwise, <code>(-(<i>insertion point</i>) - 1)</code>. The <i>insertion point</i> is defined as the point at
	 * which the key would be inserted into the array: the index of the first element in the range greater than the key,
	 * or {@code toIndex} if all elements in the range are less than the specified key.
	 * Note that this guarantees that the return value will be &gt;= 0 if and only if the key is found.
	 * @throws IllegalArgumentException       if {@code fromIndex > toIndex}
	 * @throws ArrayIndexOutOfBoundsException if {@code fromIndex < 0 or toIndex > a.length}
	 */
	public static <T, U> int binarySearchWithDuplicates(@Nonnull T[] a, @Nonnull U b, int fromIndex, int toIndex, @Nonnull ToIntBiFunction<T, U> comparator) {
		int index = binarySearch(a, b, fromIndex, toIndex, comparator);
		while (index > 0 && comparator.applyAsInt(a[index - 1], b) == 0) {
			index--;
		}
		return index;
	}

	/**
	 * Searches a range of the specified array of object for the specified value using the binary search algorithm.
	 * The range must be sorted (as by the {@link Arrays#sort(Object[])} method) prior to making this call.  If it
	 * is not sorted, the results are undefined.  If the range contains multiple elements with the specified value,
	 * there is no guarantee which one will be found.
	 *
	 * @param a          the array to be searched
	 * @param b          the value that should be compared
	 * @param comparator that envelopes the value to be searched for and returns negative integer for lesser values and
	 *                   positive integer for greater values when comparing passed value with the input one
	 * @return index of the search key, if it is contained in the array within the specified range;
	 * otherwise, <code>(-(<i>insertion point</i>) - 1)</code>. The <i>insertion point</i> is defined as the point at
	 * which the key would be inserted into the array: the index of the first element in the range greater than the key,
	 * or {@code toIndex} if all elements in the range are less than the specified key.
	 * Note that this guarantees that the return value will be &gt;= 0 if and only if the key is found.
	 * @throws IllegalArgumentException       if {@code fromIndex > toIndex}
	 * @throws ArrayIndexOutOfBoundsException if {@code fromIndex < 0 or toIndex > a.length}
	 */
	public static int binarySearch(@Nonnull int[] a, int b, @Nonnull IntBinaryOperator comparator) {
		return binarySearch(a, b, 0, a.length, comparator);
	}

	/**
	 * Searches a range of the specified array of object for the specified value using the binary search algorithm.
	 * The range must be sorted (as by the {@link Arrays#sort(Object[])} method) prior to making this call.  If it
	 * is not sorted, the results are undefined.  If the range contains multiple elements with the specified value,
	 * there is no guarantee which one will be found.
	 *
	 * @param a          the array to be searched
	 * @param a          the item to be looked up
	 * @param fromIndex  the index of the first element (inclusive) to be
	 *                   searched
	 * @param toIndex    the index of the last element (exclusive) to be searched
	 * @param comparator that envelopes the value to be searched for and returns negative integer for lesser values and
	 *                   positive integer for greater values when comparing passed value with the input one
	 * @return index of the search key, if it is contained in the array within the specified range;
	 * otherwise, <code>(-(<i>insertion point</i>) - 1)</code>. The <i>insertion point</i> is defined as the point at
	 * which the key would be inserted into the array: the index of the first element in the range greater than the key,
	 * or {@code toIndex} if all elements in the range are less than the specified key.
	 * Note that this guarantees that the return value will be &gt;= 0 if and only if the key is found.
	 * @throws IllegalArgumentException       if {@code fromIndex > toIndex}
	 * @throws ArrayIndexOutOfBoundsException if {@code fromIndex < 0 or toIndex > a.length}
	 */
	public static int binarySearch(@Nonnull int[] a, int b, int fromIndex, int toIndex, @Nonnull IntBinaryOperator comparator) {
		if (fromIndex > toIndex) {
			throw new IllegalArgumentException(
				"fromIndex(" + fromIndex + ") > toIndex(" + toIndex + ")");
		}
		if (fromIndex < 0) {
			throw new ArrayIndexOutOfBoundsException(fromIndex);
		}
		if (toIndex > a.length) {
			throw new ArrayIndexOutOfBoundsException(toIndex);
		}

		int low = fromIndex;
		int high = toIndex - 1;

		while (low <= high) {
			int mid = (low + high) >>> 1;
			int midVal = a[mid];

			final int comparisonResult = comparator.applyAsInt(midVal, b);
			if (comparisonResult < 0)
				low = mid + 1;
			else if (comparisonResult > 0)
				high = mid - 1;
			else
				return mid; // key found
		}
		return -(low + 1);  // key not found.
	}

	/**
	 * Method computes insertion point of an arbitrary record into the ordered array.
	 * Result object contains information about the position and whether the record is already in the array.
	 */
	public static <T, U> InsertionPosition computeInsertPositionOfObjInOrderedArray(@Nonnull U b, @Nonnull T[] a, @Nonnull ToIntBiFunction<T, U> comparator) {
		final int index = binarySearch(a, b, comparator);
		if (index >= 0) {
			return new InsertionPosition(index, true);
		} else {
			return new InsertionPosition(-1 * (index) - 1, false);
		}
	}

	/**
	 * Method computes insertion point of an arbitrary record into the ordered array.
	 * Result object contains information about the position and whether the record is already in the array.
	 */
	public static <T, U> InsertionPosition computeInsertPositionOfObjInOrderedArray(@Nonnull U b, @Nonnull T[] a, int fromIndex, int toIndex, @Nonnull ToIntBiFunction<T, U> comparator) {
		final int index = binarySearch(a, b, fromIndex, toIndex, comparator);
		if (index >= 0) {
			return new InsertionPosition(index, true);
		} else {
			return new InsertionPosition(-1 * (index) - 1, false);
		}
	}

	/**
	 * Inserts new integer to the sorted array on proper place and returns new expanded array.
	 * Integer is inserted only when array doesn't yet contain the record.
	 */
	@Nonnull
	public static int[] insertIntIntoOrderedArray(int recId, @Nonnull int[] recordIds) {
		final InsertionPosition position = computeInsertPositionOfIntInOrderedArray(recId, recordIds);
		return position.alreadyPresent() ? recordIds : insertIntIntoArrayOnIndex(recId, recordIds, position.position());
	}

	/**
	 * Inserts new integer to the sorted array on proper place and returns new expanded array.
	 * Integer is inserted only when array doesn't yet contain the record.
	 * Method uses comparator to locate the proper position in the array so that other than natural int ordering can
	 * be used for the binary search algorithm that precedes the insertion.
	 */
	@Nonnull
	public static int[] insertIntIntoOrderedArray(int recId, @Nonnull int[] recordIds, @Nonnull IntBinaryOperator comparator) {
		final InsertionPosition position = computeInsertPositionOfIntInOrderedArray(recId, recordIds, comparator);
		return position.alreadyPresent() ? recordIds : insertIntIntoArrayOnIndex(recId, recordIds, position.position());
	}

	/**
	 * Inserts new integer to the array on target position.
	 */
	@Nonnull
	public static int[] insertIntIntoArrayOnIndex(int recId, @Nonnull int[] recordIds, int position) {
		int len = recordIds.length;
		final int targetSize = len + 1;
		int[] newElements = new int[targetSize];
		System.arraycopy(recordIds, 0, newElements, 0, position);
		System.arraycopy(recordIds, position, newElements, position + 1, recordIds.length - position);
		newElements[position] = recId;
		return newElements;
	}

	/**
	 * Inserts new integer to the array on target position. No new array is allocated, the original one is modified.
	 * If there was a meaningful data on the last position of the array, it is overwritten to make space for the new
	 * record.
	 */
	public static void insertIntIntoSameArrayOnIndex(int recId, @Nonnull int[] recordIds, int position) {
		System.arraycopy(recordIds, position, recordIds, position + 1, recordIds.length - position - 1);
		recordIds[position] = recId;
	}

	/**
	 * Removes integer from array on specified index. The array size is not changed and the last element value becomes
	 * "undefined". The caller is responsible for shrinking the array if needed or maintain a peek on the array size.
	 */
	public static void removeIntFromSameArrayOnIndex(@Nonnull int[] recordIds, int position) {
		System.arraycopy(recordIds, position + 1, recordIds, position, recordIds.length - position - 1);
	}

	/**
	 * Removes integer from ordered array and shrinks it.
	 */
	@Nonnull
	public static int[] removeIntFromOrderedArray(int recId, @Nonnull int[] recordIds) {
		int len = recordIds.length;
		final int index = Arrays.binarySearch(recordIds, recId);
		if (index >= 0) {
			final int targetSize = len - 1;
			int[] newElements = new int[targetSize];
			System.arraycopy(recordIds, 0, newElements, 0, index);
			System.arraycopy(recordIds, index + 1, newElements, index, recordIds.length - index - 1);
			return newElements;
		} else {
			// recId not found in array
			return recordIds;
		}
	}

	/**
	 * Removes integer from array on specified index and shrinks it.
	 */
	@Nonnull
	public static int[] removeIntFromArrayOnIndex(@Nonnull int[] recordIds, int index) {
		int len = recordIds.length;
		final int targetSize = len - 1;
		int[] newElements = new int[targetSize];
		System.arraycopy(recordIds, 0, newElements, 0, index);
		System.arraycopy(recordIds, index + 1, newElements, index, recordIds.length - index - 1);
		return newElements;
	}

	/**
	 * Removes integer from array on specified index and shrinks it.
	 */
	@Nonnull
	public static int[] removeRangeFromArray(@Nonnull int[] recordIds, int startIndex, int endIndex) {
		int len = recordIds.length;
		final int targetSize = len - (endIndex - startIndex);
		int[] newElements = new int[targetSize];
		System.arraycopy(recordIds, 0, newElements, 0, startIndex);
		System.arraycopy(recordIds, endIndex, newElements, startIndex, recordIds.length - endIndex);
		return newElements;
	}

	/**
	 * Inserts new long to the sorted array on proper place and returns new expanded array.
	 * Long is inserted only when array doesn't yet contain the record.
	 */
	@Nonnull
	public static long[] insertLongIntoOrderedArray(long recId, @Nonnull long[] recordIds) {
		final InsertionPosition position = computeInsertPositionOfLongInOrderedArray(recId, recordIds);
		return position.alreadyPresent() ? recordIds : insertLongIntoArrayOnIndex(recId, recordIds, position.position());
	}

	/**
	 * Inserts new integer to the array on target position.
	 */
	@Nonnull
	public static long[] insertLongIntoArrayOnIndex(long recId, @Nonnull long[] recordIds, int position) {
		int len = recordIds.length;
		final int targetSize = len + 1;
		long[] newElements = new long[targetSize];
		System.arraycopy(recordIds, 0, newElements, 0, position);
		System.arraycopy(recordIds, position, newElements, position + 1, recordIds.length - position);
		newElements[position] = recId;
		return newElements;
	}

	/**
	 * Removes integer from ordered array and shrinks it.
	 */
	@Nonnull
	public static long[] removeLongFromOrderedArray(long recId, @Nonnull long[] recordIds) {
		final int len = recordIds.length;
		final int index = Arrays.binarySearch(recordIds, recId);
		if (index >= 0) {
			final int targetSize = len - 1;
			long[] newElements = new long[targetSize];
			System.arraycopy(recordIds, 0, newElements, 0, index);
			System.arraycopy(recordIds, index + 1, newElements, index, recordIds.length - index - 1);
			return newElements;
		} else {
			// recId not found in array
			return recordIds;
		}
	}

	/**
	 * Removes integer from array on specified index and shrinks it.
	 */
	@Nonnull
	public static long[] removeLongFromArrayOnIndex(@Nonnull long[] recordIds, int index) {
		final int len = recordIds.length;
		final int targetSize = len - 1;
		long[] newElements = new long[targetSize];
		System.arraycopy(recordIds, 0, newElements, 0, index);
		System.arraycopy(recordIds, index + 1, newElements, index, recordIds.length - index - 1);
		return newElements;
	}

	/**
	 * Inserts any arbitrary record into the ordered array and returns enlarged array.
	 * Record is inserted only when array doesn't yet contain the record.
	 */
	@Nonnull
	public static <T extends Comparable<T>> T[] insertRecordIntoOrderedArray(@Nonnull T recId, @Nonnull T[] recordIds) {
		final int index = Arrays.binarySearch(recordIds, recId);
		if (index >= 0) {
			return recordIds;
		} else {
			return insertRecordIntoArrayOnIndex(recId, recordIds, -1 * (index) - 1);
		}
	}

	/**
	 * Inserts new record to the sorted array on proper place determined by comparator and returns new expanded array.
	 * Integer is inserted only when array doesn't yet contain the record.
	 */
	@Nonnull
	public static <T> T[] insertRecordIntoOrderedArray(@Nonnull T b, @Nonnull T[] a, @Nonnull Comparator<T> comparator) {
		final int index = binarySearch(a, b, comparator::compare);
		if (index < 0) {
			return insertRecordIntoArrayOnIndex(b, a, -1 * (index) - 1);
		} else {
			return a;
		}
	}

	/**
	 * Inserts any arbitrary record into the ordered array and returns enlarged array.
	 * Record is inserted only when array doesn't yet contain the record.
	 *
	 * @param newItem item template with key, that should be inserted into an array
	 * @param mutator used to mutate existing or create new item to be placed on target position
	 */
	@Nonnull
	public static <T extends Comparable<T>> T[] insertRecordIntoOrderedArray(@Nonnull T newItem, @Nonnull UnaryOperator<T> mutator, @Nonnull T[] records) {
		final InsertionPosition position = computeInsertPositionOfObjInOrderedArray(newItem, records);
		final int posIndex = position.position();
		if (position.alreadyPresent()) {
			// mutate existing record and return array untouched
			records[posIndex] = mutator.apply(records[posIndex]);
			return records;
		} else {
			// create new item using mutator and return array with new item inserted to appropriate position
			return insertRecordIntoArrayOnIndex(mutator.apply(null), records, posIndex);
		}
	}

	/**
	 * Inserts any arbitrary record into the array on specific position.
	 */
	@Nonnull
	public static <T> T[] insertRecordIntoArrayOnIndex(@Nonnull T recId, @Nonnull T[] recordIds, int position) {
		int len = recordIds.length;
		final int targetSize = len + 1;
		@SuppressWarnings("unchecked")
		T[] newElements = (T[]) Array.newInstance(recordIds.getClass().getComponentType(), targetSize);
		System.arraycopy(recordIds, 0, newElements, 0, position);
		System.arraycopy(recordIds, position, newElements, position + 1, recordIds.length - position);
		newElements[position] = recId;
		return newElements;
	}

	/**
	 * Inserts any arbitrary record into the array on specific position. No new array is allocated, the original one is
	 * modified. If there was a meaningful data on the last position of the array, it is overwritten to make space for
	 * the new record.
	 */
	public static <T> void insertRecordIntoSameArrayOnIndex(@Nonnull T recId, @Nonnull T[] recordIds, int position) {
		System.arraycopy(recordIds, position, recordIds, position + 1, recordIds.length - position - 1);
		recordIds[position] = recId;
	}

	/**
	 * Removes arbitrary record from the array on specified index. The array size is not changed and the last element
	 * value becomes "undefined". The caller is responsible for shrinking the array if needed or maintain a peek on
	 * the array size.
	 */
	public static <T> void removeRecordFromSameArrayOnIndex(@Nonnull T[] recordIds, int position) {
		System.arraycopy(recordIds, position + 1, recordIds, position, recordIds.length - position - 1);
	}

	/**
	 * Removes arbitrary record from ordered array and shrinks it.
	 */
	@Nonnull
	public static <T extends Comparable<?>> T[] removeRecordFromOrderedArray(@Nonnull T recId, @Nonnull T[] recordIds) {
		int len = recordIds.length;
		final int index = Arrays.binarySearch(recordIds, recId);
		if (index >= 0) {
			final int targetSize = len - 1;
			@SuppressWarnings("unchecked")
			T[] newElements = (T[]) Array.newInstance(recId.getClass(), targetSize);
			System.arraycopy(recordIds, 0, newElements, 0, index);
			System.arraycopy(recordIds, index + 1, newElements, index, recordIds.length - index - 1);
			return newElements;
		} else {
			// recId not found in array
			return recordIds;
		}
	}

	/**
	 * Removes existing record from the sorted array on proper place determined by comparator and returns new expanded array.
	 * Integer is removed only when array doesn't yet contain the record.
	 */
	@Nonnull
	public static <T> T[] removeRecordFromOrderedArray(@Nonnull T b, @Nonnull T[] a, @Nonnull Comparator<T> comparator) {
		final int index = binarySearch(a, b, comparator::compare);
		if (index >= 0) {
			return removeRecordFromArrayOnIndex(a, index);
		} else {
			return a;
		}
	}

	/**
	 * Removes arbitrary record from array on specified index and shrinks it.
	 */
	@Nonnull
	public static <T> T[] removeRecordFromArrayOnIndex(@Nonnull T[] recordIds, int index) {
		int len = recordIds.length;
		final int targetSize = len - 1;
		@SuppressWarnings("unchecked")
		T[] newElements = (T[]) Array.newInstance(recordIds.getClass().getComponentType(), targetSize);
		System.arraycopy(recordIds, 0, newElements, 0, index);
		System.arraycopy(recordIds, index + 1, newElements, index, recordIds.length - index - 1);
		return newElements;
	}

	/**
	 * Method computes index of an integer in the unordered array.
	 */
	public static int indexOf(int recId, @Nonnull int[] recordIds) {
		for (int i = 0; i < recordIds.length; i++) {
			if (recId == recordIds[i]) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Method computes index of a value in the unordered array.
	 *
	 * @return -1 if the value was not found or is null
	 */
	public static <T> int indexOf(@Nullable T value, @Nonnull T[] values) {
		for (int i = 0; i < values.length; i++) {
			if (Objects.equals(value, values[i])) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Method computes insertion point of an integer into the ordered array.
	 * Result object contains information about the position and whether the integer is already in the array.
	 */
	@Nonnull
	public static InsertionPosition computeInsertPositionOfIntInOrderedArray(int recId, @Nonnull int[] recordIds) {
		final int index = Arrays.binarySearch(recordIds, recId);
		if (index >= 0) {
			return new InsertionPosition(index, true);
		} else {
			return new InsertionPosition(-1 * (index) - 1, false);
		}
	}

	/**
	 * Method computes insertion point of an integer into the ordered array.
	 * Result object contains information about the position and whether the integer is already in the array.
	 * If the to index is less or equal to from index, the method returns object with position set to 0
	 * and no presence of the integer in the array.
	 */
	@Nonnull
	public static InsertionPosition computeInsertPositionOfIntInOrderedArray(int recId, @Nonnull int[] recordIds, int fromIndex, int toIndex) {
		if (toIndex <= fromIndex) {
			return new InsertionPosition(0, false);
		}
		final int index = Arrays.binarySearch(recordIds, fromIndex, toIndex, recId);
		if (index >= 0) {
			return new InsertionPosition(index, true);
		} else {
			return new InsertionPosition(-1 * (index) - 1, false);
		}
	}

	/**
	 * Method computes insertion point of an integer into the ordered array.
	 * Result object contains information about the position and whether the integer is already in the array.
	 * Method uses comparator to locate the proper position in the array so that other than natural int ordering can
	 * be used for the binary search algorithm.
	 */
	@Nonnull
	public static InsertionPosition computeInsertPositionOfIntInOrderedArray(int recId, @Nonnull int[] recordIds, @Nonnull IntBinaryOperator comparator) {
		final int index = ArrayUtils.binarySearch(recordIds, recId, comparator);
		if (index >= 0) {
			return new InsertionPosition(index, true);
		} else {
			return new InsertionPosition(-1 * (index) - 1, false);
		}
	}

	/**
	 * Method computes insertion point of a long into the ordered array.
	 * Result object contains information about the position and whether the long is already in the array.
	 */
	@Nonnull
	public static InsertionPosition computeInsertPositionOfLongInOrderedArray(long recId, @Nonnull long[] recordIds) {
		final int index = Arrays.binarySearch(recordIds, recId);
		if (index >= 0) {
			return new InsertionPosition(index, true);
		} else {
			return new InsertionPosition(-1 * (index) - 1, false);
		}
	}

	/**
	 * Method computes insertion point of an arbitrary record into the ordered array.
	 * Result object contains information about the position and whether the record is already in the array.
	 */
	@Nonnull
	public static <T extends Comparable<T>> InsertionPosition computeInsertPositionOfObjInOrderedArray(@Nonnull T recId, @Nonnull T[] recordIds) {
		final int index = Arrays.binarySearch(recordIds, recId);
		if (index >= 0) {
			return new InsertionPosition(index, true);
		} else {
			return new InsertionPosition(-1 * (index) - 1, false);
		}
	}

	/**
	 * Method computes insertion point of an arbitrary record into the ordered array.
	 * Result object contains information about the position and whether the record is already in the array.
	 */
	@Nonnull
	public static <T extends Comparable<T>> InsertionPosition computeInsertPositionOfObjInOrderedArray(@Nonnull T recId, @Nonnull T[] recordIds, int fromIndex, int toIndex) {
		final int index = Arrays.binarySearch(recordIds, fromIndex, toIndex, recId);
		if (index >= 0) {
			return new InsertionPosition(index, true);
		} else {
			return new InsertionPosition(-1 * (index) - 1, false);
		}
	}

	/**
	 * Method computes insertion point of an arbitrary record into the ordered array.
	 * Result object contains information about the position and whether the record is already in the array.
	 */
	@Nonnull
	public static <T> InsertionPosition computeInsertPositionOfObjInOrderedArray(@Nonnull T recId, @Nonnull T[] recordIds, @Nonnull Comparator<T> comparator) {
		final int index = Arrays.binarySearch(recordIds, recId, comparator);
		if (index >= 0) {
			return new InsertionPosition(index, true);
		} else {
			return new InsertionPosition(-1 * (index) - 1, false);
		}
	}

	/**
	 * Method computes insertion point of an arbitrary record into the ordered array.
	 * Result object contains information about the position and whether the record is already in the array.
	 */
	@Nonnull
	public static <T> InsertionPosition computeInsertPositionOfObjInOrderedArray(@Nonnull T recId, @Nonnull T[] recordIds, int fromIndex, int toIndex, @Nonnull Comparator<T> comparator) {
		final int index = Arrays.binarySearch(recordIds, fromIndex, toIndex, recId, comparator);
		if (index >= 0) {
			return new InsertionPosition(index, true);
		} else {
			return new InsertionPosition(-1 * (index) - 1, false);
		}
	}

	/**
	 * Returns TRUE if ALL subSet items are part of superSet items.
	 */
	public static <T> boolean contains(@Nonnull T[] superSet, @Nonnull T[] subSet) {
		for (T subSetItem : subSet) {
			if (!contains(superSet, subSetItem)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Returns TRUE if item is part of superSet items.
	 */
	public static <T> boolean contains(@Nonnull T[] superSet, @Nonnull T item) {
		for (T superSetItem : superSet) {
			if (item.equals(superSetItem)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns TRUE if item is part of superSet items.
	 */
	public static boolean contains(@Nonnull int[] superSet, int item) {
		for (int superSetItem : superSet) {
			if (item == superSetItem) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Reverses contents of the array - i.e. first element becomes the last element of the array.
	 * Modifies contents of the passed array.
	 */
	public static void reverse(@Nonnull Object[] array) {
		int i = 0;
		int j = array.length - 1;
		Object tmp;
		while (j > i) {
			tmp = array[j];
			array[j] = array[i];
			array[i] = tmp;
			j--;
			i++;
		}
	}

	/**
	 * Reverses contents of the array - i.e. first element becomes the last element of the array.
	 * Doesn't modify contents of the passed array - instead crates and returns new.
	 */
	@Nonnull
	public static int[] reverse(@Nonnull int[] array) {
		final int[] result = new int[array.length];
		System.arraycopy(array, 0, result, 0, array.length);

		int i = 0;
		int j = result.length - 1;
		int tmp;
		while (j > i) {
			tmp = result[j];
			result[j] = result[i];
			result[i] = tmp;
			j--;
			i++;
		}

		return result;
	}

	/**
	 * Reverses contents of the array - i.e. first element becomes the last element of the array.
	 * Modifies contents of the passed array.
	 */
	public static void reverseInPlace(@Nonnull int[] array) {
		int i = 0;
		int j = array.length - 1;
		int tmp;
		while (j > i) {
			tmp = array[j];
			array[j] = array[i];
			array[i] = tmp;
			j--;
			i++;
		}
	}

	/**
	 * Sorts second array by the natural ordering of first array. Contents of the first are not changed, but the contents
	 * of the second array are changed.
	 */
	public static void sortSecondAlongFirstArray(@Nonnull int[] first, @Nonnull int[] second) {
		Assert.isTrue(first.length == second.length, "Both arrays must have same length!");
		// now sort positions according to recordId value - this will change ordered positions to unordered ones
		final IntArrayWrapper wrapper = new IntArrayWrapper(second);
		wrapper.sort(Comparator.comparing(o -> first[o]));
	}

	/**
	 * Sorts second array by the natural ordering of first array. Contents of the first nor second array are changed.
	 * Third array is created in the process and sorted contents of the second array are returned in this new third
	 * array as the output of the method.
	 */
	@Nonnull
	public static <T extends Comparable<T>> int[] computeSortedSecondAlongFirstArray(@Nonnull T[] first, @Nonnull int[] second) {
		Assert.isTrue(first.length == second.length, "Both arrays must have same length!");
		return computeSortedSecondAlongFirstArray(Comparator.comparing(o -> first[o]), second);
	}

	/**
	 * Sorts array by the Comparator passed in first argument. Contents of the array are not changed.
	 * Second array is created in the process and sorted contents of the array are returned in this new second array
	 * as the output of the method.
	 */
	@Nonnull
	public static int[] computeSortedSecondAlongFirstArray(@Nonnull Comparator<Integer> comparator, @Nonnull int[] array) {
		// clone original array
		int[] sortedRecordIds = new int[array.length];
		System.arraycopy(array, 0, sortedRecordIds, 0, array.length);

		// now sort positions according to recordId value - this will change ordered positions to unordered ones
		final IntArrayWrapper wrapper = new IntArrayWrapper(sortedRecordIds);
		wrapper.sort(comparator);

		// withdraw sorted ids
		return wrapper.getElements();
	}

	/**
	 * Sorts array by the Comparator passed in first argument. Contents of the array are changed.
	 */
	public static void sortArray(@Nonnull Comparator<Integer> comparator, @Nonnull int[] array) {
		// now sort positions according to recordId value - this will change ordered positions to unordered ones
		final IntArrayWrapper wrapper = new IntArrayWrapper(array);
		wrapper.sort(comparator);
	}

	/**
	 * Sorts array by the Comparator passed in first argument. Contents of the array are changed.
	 */
	public static <T> void sortArray(@Nonnull Comparator<T> comparator, @Nonnull T[] array) {
		// now sort positions according to recordId value - this will change ordered positions to unordered ones
		final ArrayWrapper<T> wrapper = new ArrayWrapper<>(array);
		wrapper.sort(comparator);
	}

	/**
	 * This method will merge all passed arrays into one. All values from all arrays will be combined one after another.
	 * Result array is not sorted.
	 */
	@Nonnull
	public static <T> T[] mergeArrays(@Nonnull T[]... array) {
		if (array.length == 0) {
			throw new IllegalArgumentException("Empty argument is not allowed!");
		}
		int resultSize = 0;
		for (T[] arrayItem : array) {
			resultSize = resultSize + arrayItem.length;
		}
		int offset = 0;
		@SuppressWarnings({"unchecked"})
		T[] result = (T[]) Array.newInstance(array[0].getClass().getComponentType(), resultSize);
		for (T[] configItem : array) {
			System.arraycopy(configItem, 0, result, offset, configItem.length);
			offset = offset + configItem.length;
		}
		return result;
	}

	/**
	 * This method will merge all passed arrays into one. All values from all arrays will be combined one after another.
	 * Result array is not sorted.
	 */
	@Nonnull
	public static int[] mergeArrays(@Nonnull int[]... array) {
		if (array.length == 0) {
			throw new IllegalArgumentException("Empty argument is not allowed!");
		}
		int resultSize = 0;
		for (int[] configItem : array) {
			resultSize = resultSize + configItem.length;
		}
		int offset = 0;
		int[] result = (int[]) Array.newInstance(array[0].getClass().getComponentType(), resultSize);
		for (int[] configItem : array) {
			System.arraycopy(configItem, 0, result, offset, configItem.length);
			offset = offset + configItem.length;
		}
		return result;
	}

	/**
	 * This method will shuffle elements in array in random order. The passed array gets modified.
	 */
	public static void shuffleArray(@Nonnull Random rnd, @Nonnull Object[] array) {
		for (int i = array.length - 1; i > 0; i--) {
			int index = rnd.nextInt(i + 1);
			Object a = array[index];
			array[index] = array[i];
			array[i] = a;
		}
	}

	/**
	 * This method will shuffle elements in array in random order. The passed array gets modified.
	 */
	public static void shuffleArray(@Nonnull Random rnd, @Nonnull int[] array, int maximalCountOfShuffledElements) {
		int shuffled = 0;
		for (int i = 0; i < array.length && shuffled < maximalCountOfShuffledElements; i++) {
			int sourceIndex = rnd.nextInt(array.length);
			int targetIndex = i + rnd.nextInt(array.length / maximalCountOfShuffledElements);
			if (sourceIndex != targetIndex && targetIndex < array.length) {
				int a = array[targetIndex];
				array[targetIndex] = array[sourceIndex];
				array[sourceIndex] = a;
				i = targetIndex;
				shuffled++;
			}
		}
	}

	/**
	 * Extracts sub-array of `items` array starting on `startIndex` (incluting), up to `endIndex` (exclusive) and
	 * returns it as an {@link ArrayList}.
	 */
	@Nonnull
	public static <T> List<T> asList(@Nonnull T[] items, int startIndex, int endIndex) {
		final int size = Math.min(items.length, endIndex - startIndex);
		final ArrayList<T> result = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			result.add(items[startIndex + i]);
		}
		return result;
	}

	/**
	 * Returns copy of sub-array of `items`, cast to `targetType` starting on `startIndex` (incluting), up
	 * to `endIndex` (exclusive).
	 */
	@Nonnull
	public static <T> T[] copyOf(@Nonnull Object[] items, @Nonnull Class<T> targetType, int startIndex, int endIndex) {
		final int size = Math.min(items.length, endIndex - startIndex);
		@SuppressWarnings("unchecked") final T[] result = (T[]) Array.newInstance(targetType, size);
		for (int i = 0; i < size; i++) {
			//noinspection unchecked
			result[i] = (T) items[startIndex + i];
		}
		return result;
	}

	/**
	 * Method sorts `arrayToSort` along the position of the same number in the `sortedArray`. The `arrayToSort` is
	 * expected to be as subset of the `sortedArray`, but it may contain unknown numbers which will be placed at
	 * the end of the list. Method modifies contents of the `arrayToSort` and returns index of last "sorted" item
	 * in that array.
	 */
	public static int sortAlong(@Nonnull int[] sortedArray, @Nonnull int[] arrayToSort) {
		final int lastIndex = arrayToSort.length - 1;
		final int[] positions = new int[arrayToSort.length];
		Arrays.fill(positions, -1);
		int itemsLocalized = 0;

		for (int i = 0; i < sortedArray.length && itemsLocalized < arrayToSort.length; i++) {
			final int number = sortedArray[i];

			if (number >= arrayToSort[0] && number <= arrayToSort[lastIndex]) {
				final int indexInArrayToSort = Arrays.binarySearch(arrayToSort, number);
				if (indexInArrayToSort >= 0 && positions[indexInArrayToSort] == -1) {
					positions[indexInArrayToSort] = itemsLocalized++;
				}
			}
		}

		final int[] copyOfSource = Arrays.copyOf(arrayToSort, arrayToSort.length);
		int unknownNumbers = 0;
		for (int i = 0; i < copyOfSource.length; i++) {
			if (positions[i] >= 0) {
				arrayToSort[positions[i]] = copyOfSource[i];
			} else {
				arrayToSort[itemsLocalized + unknownNumbers++] = copyOfSource[i];
			}
		}

		return arrayToSort.length - unknownNumbers;
	}

	/**
	 * Method sorts `arrayToSort` along the position of the same number in the `sortedArray`. The `arrayToSort` is
	 * expected to be as subset of the `sortedArray`, but it may contain unknown numbers which will be placed at
	 * the end of the list. No input argument content is modified, method returns a new array on as its result.
	 *
	 * The method is a copy of {@link  #sortAlong(int[], int[])} method allowing to sort complex object which
	 * can produce the numbers using `extractor` function.
	 */
	@Nonnull
	public static <T> T[] sortAlong(@Nonnull int[] sortedArray, @Nonnull T[] arrayToSort, @Nonnull ToIntFunction<T> extractor) {
		final int first = extractor.applyAsInt(arrayToSort[0]);
		final int last = extractor.applyAsInt(arrayToSort[arrayToSort.length - 1]);
		final int[] positions = new int[arrayToSort.length];
		Arrays.fill(positions, -1);
		int itemsLocalized = 0;
		for (int i = 0; i < sortedArray.length && itemsLocalized < arrayToSort.length; i++) {
			final int number = sortedArray[i];
			if (number >= first && number <= last) {
				final int indexInArrayToSort = binarySearch(
					arrayToSort,
					number,
					(t, integer) -> Integer.compare(extractor.applyAsInt(t), integer));
				if (indexInArrayToSort >= 0) {
					positions[indexInArrayToSort] = itemsLocalized++;
				}
			}
		}
		@SuppressWarnings("unchecked") final T[] result = (T[]) Array.newInstance(arrayToSort.getClass().getComponentType(), arrayToSort.length);
		int unknownNumbers = 0;
		for (int i = 0; i < arrayToSort.length; i++) {
			if (positions[i] >= 0) {
				result[positions[i]] = arrayToSort[i];
			} else {
				result[itemsLocalized + unknownNumbers++] = arrayToSort[i];
			}
		}
		return result;
	}

	/**
	 * Compares two objects for equality.
	 * This method checks if the specified objects are both arrays (regardles of whether of primitive types or objects)
	 * and whether they're equal or not.
	 *
	 * @param a  the first object to be compared
	 * @param a2 the second object to be compared
	 * @return true if the objects are equal, false otherwise
	 */
	public static boolean equals(@Nullable Object a, @Nullable Object a2) {
		if (a == a2) {
			return true;
		}
		if (a == null || a2 == null) {
			return false;
		}

		if (!a.getClass().isArray() || !a2.getClass().isArray()) {
			return false;
		}

		int length = Array.getLength(a);
		if (Array.getLength(a2) != length) {
			return false;
		}

		for (int i = 0; i < length; i++) {
			if (!Objects.equals(Array.get(a, i), Array.get(a2, i))) {
				return false;
			}
		}

		return true;
	}

	/**
	 * Computes the hash code for the specified object. This method handles both arrays and non-array objects.
	 *
	 * @param a the object for which to compute the hash code
	 * @return the hash code value for the specified object
	 */
	public static int hashCode(@Nullable Object a) {
		if (a == null) {
			return 0;
		}

		int result = 1;

		if (a.getClass().isArray()) {
			final int length = Array.getLength(a);
			for (int i = 0; i < length; i++) {
				final Object element = Array.get(a, i);
				result = 31 * result + (element == null ? 0 : element.hashCode());
			}

			return result;
		} else {
			return a.hashCode();
		}
	}

	/**
	 * Converts an array of enums to an EnumSet of theirs.
	 *
	 * @param scopes an array of Scope enums
	 * @return an EnumSet of Scopes; returns an empty EnumSet if the input array is empty or contains null values
	 */
	@Nonnull
	public static <T extends Enum<T>> EnumSet<T> toEnumSet(@Nonnull Class<T> enumType, @Nullable T[] scopes) {
		return isEmptyOrItsValuesNull(scopes) ?
			EnumSet.noneOf(enumType) : EnumSet.of(scopes[0], Arrays.copyOfRange(scopes, 1, scopes.length));
	}

	/**
	 * Compares two arrays of elements that are instances of {@link Comparable}.
	 * The comparison is lexicographical, element by element.
	 * If both arrays are equal up to the shorter length, the longer array is considered greater.
	 *
	 * @param <T> the type of elements in the arrays, must implement {@link Comparable}
	 * @param a the first array to compare, must not be null
	 * @param b the second array to compare, must not be null
	 * @return a negative integer, zero, or a positive integer as the first array
	 *         is less than, equal to, or greater than the second array
	 * @throws NullPointerException if either array or any element within the arrays is null
	 */
	public static <T extends Comparable<T>> int compare(@Nonnull T[] a, @Nonnull T[] b) {
		final int minLength = Math.min(a.length, b.length);
		for (int i = 0; i < minLength; i++) {
			final int comparisonResult = a[i].compareTo(b[i]);
			if (comparisonResult != 0) {
				return comparisonResult;
			}
		}
		return Integer.compare(a.length, b.length);
	}

	/**
	 * Simple DTO object for passing information about the record lookup in the ordered array.
	 *
	 * @param position       Position where the looked up object:
	 *                       - currently is (when alreadyPresent=true)
	 *                       - should potentially be (when alreadyPresent=false)
	 * @param alreadyPresent Holds true if looked up object was found already present in the array.
	 */
	public record InsertionPosition(int position, boolean alreadyPresent) {
	}

	/**
	 * Internal helper class that is used to sort array A by comparator that uses array B. This cannot be easily done
	 * by other way than mimicking list to get advantage of {@link java.util.AbstractList#sort(Comparator)} function
	 * that accepts external comparator.
	 */
	@RequiredArgsConstructor
	private static class IntArrayWrapper extends AbstractList<Integer> {
		@Getter private final int[] elements;

		@Override
		public Integer get(int index) {
			return this.elements[index];
		}

		@Override
		public Integer set(int index, Integer element) {
			int v = this.elements[index];
			this.elements[index] = element;
			return v;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			if (!super.equals(o)) return false;
			IntArrayWrapper integers = (IntArrayWrapper) o;
			return Arrays.equals(this.elements, integers.elements);
		}

		@Override
		public int hashCode() {
			int result = super.hashCode();
			result = 31 * result + Arrays.hashCode(this.elements);
			return result;
		}

		@Override
		public int size() {
			return this.elements.length;
		}

	}

	/**
	 * Internal helper class that is used to sort array A by comparator that uses array B. This cannot be easily done
	 * by other way than mimicking list to get advantage of {@link java.util.AbstractList#sort(Comparator)} function
	 * that accepts external comparator.
	 */
	@RequiredArgsConstructor
	private static class ArrayWrapper<T> extends AbstractList<T> {
		@Getter private final T[] elements;

		@Override
		public T get(int index) {
			return this.elements[index];
		}

		@Override
		public T set(int index, T element) {
			T v = this.elements[index];
			this.elements[index] = element;
			return v;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			if (!super.equals(o)) return false;
			//noinspection unchecked
			ArrayWrapper<T> otherElements = (ArrayWrapper<T>) o;
			return Arrays.equals(this.elements, otherElements.elements);
		}

		@Override
		public int hashCode() {
			int result = super.hashCode();
			result = 31 * result + Arrays.hashCode(this.elements);
			return result;
		}

		@Override
		public int size() {
			return this.elements.length;
		}

	}

}
