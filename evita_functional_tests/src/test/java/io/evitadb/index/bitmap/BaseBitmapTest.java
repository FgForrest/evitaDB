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

package io.evitadb.index.bitmap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.PrimitiveIterator.OfInt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link BaseBitmap}
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class BaseBitmapTest {
	private final BaseBitmap tested = new BaseBitmap();

	@BeforeEach
	void setUp() {
		this.tested.addAll(5, 8, 10, 3);
	}

	@Test
	void shouldReadAllData() {
		assertEquals(4, this.tested.size());
		assertArrayEquals(new int[]{3, 5, 8, 10}, this.tested.getArray());
		assertEquals(1, this.tested.indexOf(5));
		assertEquals(0, this.tested.indexOf(3));
		assertEquals(-1, this.tested.indexOf(0));
		assertEquals(-2, this.tested.indexOf(4));
		assertEquals(-3, this.tested.indexOf(6));
		assertEquals(-4, this.tested.indexOf(9));
		assertEquals(-5, this.tested.indexOf(11));
	}

	@Test
	void shouldIterateOverAllData() {
		final OfInt it = this.tested.iterator();
		assertArrayEquals(new int[] {3, 5, 8, 10}, toArray(it, this.tested.size()));
	}

	@Test
	void shouldAddAndSeeData() {
		this.tested.addAll(1, 15);
		assertEquals(6, this.tested.size());
		assertArrayEquals(new int[] {1, 3, 5, 8, 10, 15}, this.tested.getArray());
		assertArrayEquals(new int[] {1, 3, 5, 8, 10, 15}, toArray(this.tested.iterator(), this.tested.size()));
	}

	@Test
	void shouldRemoveAndMissData() {
		this.tested.removeAll(3, 8);
		assertEquals(2, this.tested.size());
		assertArrayEquals(new int[] {5, 10}, this.tested.getArray());
		assertArrayEquals(new int[] {5, 10}, toArray(this.tested.iterator(), this.tested.size()));
	}

	@Test
	void shouldReturnRange() {
		this.tested.addAll(12, 15, 20, 23);
		assertArrayEquals(new int[] {8, 10, 12}, this.tested.getRange(2, 5));
		assertArrayEquals(new int[] {15, 20, 23}, this.tested.getRange(5, 8));
		assertArrayEquals(new int[] {3, 5, 8, 10, 12}, this.tested.getRange(0, 5));
	}

	@Test
	void shouldFailReturnRangeWhenSizeIsExceeded() {
		this.tested.addAll(12, 15, 20, 23);
		assertThrows(IndexOutOfBoundsException.class, () -> this.tested.getRange(7, 10));
	}

	@Test
	void shouldRemoveAllWithPredicateOnSmallBitmap() {
		// remove even numbers
		this.tested.removeAll(value -> value % 2 == 0);
		assertEquals(2, this.tested.size());
		assertArrayEquals(new int[]{3, 5}, this.tested.getArray());
	}

	@Test
	void shouldRemoveAllWithPredicateOnSmallBitmapRemovingOdds() {
		// remove odd numbers
		this.tested.removeAll(value -> value % 2 != 0);
		assertEquals(2, this.tested.size());
		assertArrayEquals(new int[]{8, 10}, this.tested.getArray());
	}

	@Test
	void shouldRemoveAllWithPredicateOnSmallBitmapRemovingAll() {
		// remove all
		this.tested.removeAll(value -> true);
		assertEquals(0, this.tested.size());
		assertArrayEquals(new int[]{}, this.tested.getArray());
	}

	@Test
	void shouldRemoveAllWithPredicateOnSmallBitmapRemovingNone() {
		// remove none
		this.tested.removeAll(value -> false);
		assertEquals(4, this.tested.size());
		assertArrayEquals(new int[]{3, 5, 8, 10}, this.tested.getArray());
	}

	@Test
	void shouldRemoveAllWithPredicateOnSmallBitmapWithSingleElement() {
		final BaseBitmap singleElement = new BaseBitmap(42);
		singleElement.removeAll(value -> value == 42);
		assertEquals(0, singleElement.size());
		assertArrayEquals(new int[]{}, singleElement.getArray());
	}

	@Test
	void shouldRemoveAllWithPredicateOnSmallBitmapWithSingleElementNotMatching() {
		final BaseBitmap singleElement = new BaseBitmap(42);
		singleElement.removeAll(value -> value != 42);
		assertEquals(1, singleElement.size());
		assertArrayEquals(new int[]{42}, singleElement.getArray());
	}

	@Test
	void shouldRemoveAllWithPredicateOnEmptyBitmap() {
		final BaseBitmap empty = new BaseBitmap();
		empty.removeAll(value -> true);
		assertEquals(0, empty.size());
		assertArrayEquals(new int[]{}, empty.getArray());
	}

	@Test
	void shouldRemoveAllWithPredicateOnSmallBitmapRemovingFirstElement() {
		this.tested.removeAll(value -> value == 3);
		assertEquals(3, this.tested.size());
		assertArrayEquals(new int[]{5, 8, 10}, this.tested.getArray());
	}

	@Test
	void shouldRemoveAllWithPredicateOnSmallBitmapRemovingLastElement() {
		this.tested.removeAll(value -> value == 10);
		assertEquals(3, this.tested.size());
		assertArrayEquals(new int[]{3, 5, 8}, this.tested.getArray());
	}

	@Test
	void shouldRemoveAllWithPredicateOnSmallBitmapRemovingMiddleElements() {
		this.tested.removeAll(value -> value > 3 && value < 10);
		assertEquals(2, this.tested.size());
		assertArrayEquals(new int[]{3, 10}, this.tested.getArray());
	}

	@Test
	void shouldRemoveAllWithPredicateOnLargeBitmap() {
		// Create bitmap with 100 elements (> 64)
		final BaseBitmap large = new BaseBitmap();
		for (int i = 1; i <= 100; i++) {
			large.add(i);
		}
		assertEquals(100, large.size());

		// Remove even numbers
		large.removeAll(value -> value % 2 == 0);
		assertEquals(50, large.size());

		// Verify only odd numbers remain
		final int[] result = large.getArray();
		for (int i = 0; i < result.length; i++) {
			assertEquals(i * 2 + 1, result[i]);
		}
	}

	@Test
	void shouldRemoveAllWithPredicateOnLargeBitmapRemovingAll() {
		// Create bitmap with 200 elements
		final BaseBitmap large = new BaseBitmap();
		for (int i = 1; i <= 200; i++) {
			large.add(i);
		}
		assertEquals(200, large.size());

		// Remove all
		large.removeAll(value -> true);
		assertEquals(0, large.size());
		assertArrayEquals(new int[]{}, large.getArray());
	}

	@Test
	void shouldRemoveAllWithPredicateOnLargeBitmapRemovingNone() {
		// Create bitmap with 150 elements
		final BaseBitmap large = new BaseBitmap();
		for (int i = 1; i <= 150; i++) {
			large.add(i);
		}
		assertEquals(150, large.size());

		// Remove none
		large.removeAll(value -> false);
		assertEquals(150, large.size());

		// Verify all elements remain
		final int[] result = large.getArray();
		for (int i = 0; i < result.length; i++) {
			assertEquals(i + 1, result[i]);
		}
	}

	@Test
	void shouldRemoveAllWithPredicateOnLargeBitmapWithComplexCondition() {
		// Create bitmap with 300 elements
		final BaseBitmap large = new BaseBitmap();
		for (int i = 1; i <= 300; i++) {
			large.add(i);
		}

		// Remove multiples of 3 or 5
		large.removeAll(value -> value % 3 == 0 || value % 5 == 0);

		// Verify correct elements were removed
		final int[] result = large.getArray();
		for (int value : result) {
			assertFalse(value % 3 == 0 || value % 5 == 0,
				"Value " + value + " should not be in result");
		}
	}

	@Test
	void shouldRemoveAllWithPredicateOnLargeBitmapRemovingFirstHalf() {
		// Create bitmap with 200 elements
		final BaseBitmap large = new BaseBitmap();
		for (int i = 1; i <= 200; i++) {
			large.add(i);
		}

		// Remove first half
		large.removeAll(value -> value <= 100);
		assertEquals(100, large.size());

		// Verify only second half remains
		final int[] result = large.getArray();
		for (int i = 0; i < result.length; i++) {
			assertEquals(i + 101, result[i]);
		}
	}

	@Test
	void shouldRemoveAllWithPredicateOnLargeBitmapRemovingLastHalf() {
		// Create bitmap with 200 elements
		final BaseBitmap large = new BaseBitmap();
		for (int i = 1; i <= 200; i++) {
			large.add(i);
		}

		// Remove last half
		large.removeAll(value -> value > 100);
		assertEquals(100, large.size());

		// Verify only first half remains
		final int[] result = large.getArray();
		for (int i = 0; i < result.length; i++) {
			assertEquals(i + 1, result[i]);
		}
	}

	@Test
	void shouldRemoveAllWithPredicateOnBitmapExactly64Elements() {
		// Test edge case at boundary
		final BaseBitmap exact = new BaseBitmap();
		for (int i = 1; i <= 64; i++) {
			exact.add(i);
		}
		assertEquals(64, exact.size());

		// Remove even numbers
		exact.removeAll(value -> value % 2 == 0);
		assertEquals(32, exact.size());

		// Verify only odd numbers remain
		final int[] result = exact.getArray();
		for (int i = 0; i < result.length; i++) {
			assertEquals(i * 2 + 1, result[i]);
		}
	}

	@Test
	void shouldRemoveAllWithPredicateOnBitmapExactly65Elements() {
		// Test edge case just over boundary
		final BaseBitmap justOver = new BaseBitmap();
		for (int i = 1; i <= 65; i++) {
			justOver.add(i);
		}
		assertEquals(65, justOver.size());

		// Remove values greater than 32
		justOver.removeAll(value -> value > 32);
		assertEquals(32, justOver.size());

		// Verify correct elements remain
		final int[] result = justOver.getArray();
		for (int i = 0; i < result.length; i++) {
			assertEquals(i + 1, result[i]);
		}
	}

	@Test
	void shouldRemoveAllWithPredicateOnBitmapExactly63Elements() {
		// Test edge case just under boundary
		final BaseBitmap justUnder = new BaseBitmap();
		for (int i = 1; i <= 63; i++) {
			justUnder.add(i);
		}
		assertEquals(63, justUnder.size());

		// Remove values divisible by 7
		justUnder.removeAll(value -> value % 7 == 0);
		assertEquals(54, justUnder.size());

		// Verify correct elements remain
		final int[] result = justUnder.getArray();
		for (int value : result) {
			assertNotEquals(0, value % 7, "Value " + value + " should not be in result");
		}
	}

	@Test
	void shouldRemoveAllWithPredicateOnSparseSmallBitmap() {
		// Test with sparse bitmap (large gaps between values)
		final BaseBitmap sparse = new BaseBitmap(1, 100, 200, 300, 400);
		assertEquals(5, sparse.size());

		sparse.removeAll(value -> value >= 200);
		assertEquals(2, sparse.size());
		assertArrayEquals(new int[]{1, 100}, sparse.getArray());
	}

	@Test
	void shouldRemoveAllWithPredicateOnSparseLargeBitmap() {
		// Test with large sparse bitmap
		final BaseBitmap sparse = new BaseBitmap();
		for (int i = 0; i < 100; i++) {
			sparse.add(i * 100); // 0, 100, 200, ..., 9900
		}
		assertEquals(100, sparse.size());

		sparse.removeAll(value -> value % 200 == 0);
		assertEquals(50, sparse.size());

		// Verify correct elements remain
		final int[] result = sparse.getArray();
		for (int value : result) {
			assertNotEquals(0, value % 200, "Value " + value + " should not be in result");
		}
	}

	@Test
	void shouldRetainAllWithPredicateOnSmallBitmap() {
		// retain even numbers
		this.tested.retainAll(value -> value % 2 == 0);
		assertEquals(2, this.tested.size());
		assertArrayEquals(new int[]{8, 10}, this.tested.getArray());
	}

	@Test
	void shouldRetainAllWithPredicateOnSmallBitmapRetainingOdds() {
		// retain odd numbers
		this.tested.retainAll(value -> value % 2 != 0);
		assertEquals(2, this.tested.size());
		assertArrayEquals(new int[]{3, 5}, this.tested.getArray());
	}

	@Test
	void shouldRetainAllWithPredicateOnSmallBitmapRetainingAll() {
		// retain all
		this.tested.retainAll(value -> true);
		assertEquals(4, this.tested.size());
		assertArrayEquals(new int[]{3, 5, 8, 10}, this.tested.getArray());
	}

	@Test
	void shouldRetainAllWithPredicateOnSmallBitmapRetainingNone() {
		// retain none
		this.tested.retainAll(value -> false);
		assertEquals(0, this.tested.size());
		assertArrayEquals(new int[]{}, this.tested.getArray());
	}

	@Test
	void shouldRetainAllWithPredicateOnSmallBitmapWithSingleElement() {
		final BaseBitmap singleElement = new BaseBitmap(42);
		singleElement.retainAll(value -> value == 42);
		assertEquals(1, singleElement.size());
		assertArrayEquals(new int[]{42}, singleElement.getArray());
	}

	@Test
	void shouldRetainAllWithPredicateOnSmallBitmapWithSingleElementNotMatching() {
		final BaseBitmap singleElement = new BaseBitmap(42);
		singleElement.retainAll(value -> value != 42);
		assertEquals(0, singleElement.size());
		assertArrayEquals(new int[]{}, singleElement.getArray());
	}

	@Test
	void shouldRetainAllWithPredicateOnEmptyBitmap() {
		final BaseBitmap empty = new BaseBitmap();
		empty.retainAll(value -> true);
		assertEquals(0, empty.size());
		assertArrayEquals(new int[]{}, empty.getArray());
	}

	@Test
	void shouldRetainAllWithPredicateOnSmallBitmapRetainingFirstElement() {
		this.tested.retainAll(value -> value == 3);
		assertEquals(1, this.tested.size());
		assertArrayEquals(new int[]{3}, this.tested.getArray());
	}

	@Test
	void shouldRetainAllWithPredicateOnSmallBitmapRetainingLastElement() {
		this.tested.retainAll(value -> value == 10);
		assertEquals(1, this.tested.size());
		assertArrayEquals(new int[]{10}, this.tested.getArray());
	}

	@Test
	void shouldRetainAllWithPredicateOnSmallBitmapRetainingMiddleElements() {
		this.tested.retainAll(value -> value > 3 && value < 10);
		assertEquals(2, this.tested.size());
		assertArrayEquals(new int[]{5, 8}, this.tested.getArray());
	}

	@Test
	void shouldRetainAllWithPredicateOnLargeBitmap() {
		// Create bitmap with 100 elements (> 64)
		final BaseBitmap large = new BaseBitmap();
		for (int i = 1; i <= 100; i++) {
			large.add(i);
		}
		assertEquals(100, large.size());

		// Retain even numbers
		large.retainAll(value -> value % 2 == 0);
		assertEquals(50, large.size());

		// Verify only even numbers remain
		final int[] result = large.getArray();
		for (int i = 0; i < result.length; i++) {
			assertEquals((i + 1) * 2, result[i]);
		}
	}

	@Test
	void shouldRetainAllWithPredicateOnLargeBitmapRetainingAll() {
		// Create bitmap with 200 elements
		final BaseBitmap large = new BaseBitmap();
		for (int i = 1; i <= 200; i++) {
			large.add(i);
		}
		assertEquals(200, large.size());

		// Retain all
		large.retainAll(value -> true);
		assertEquals(200, large.size());

		// Verify all elements remain
		final int[] result = large.getArray();
		for (int i = 0; i < result.length; i++) {
			assertEquals(i + 1, result[i]);
		}
	}

	@Test
	void shouldRetainAllWithPredicateOnLargeBitmapRetainingNone() {
		// Create bitmap with 150 elements
		final BaseBitmap large = new BaseBitmap();
		for (int i = 1; i <= 150; i++) {
			large.add(i);
		}
		assertEquals(150, large.size());

		// Retain none
		large.retainAll(value -> false);
		assertEquals(0, large.size());
		assertArrayEquals(new int[]{}, large.getArray());
	}

	@Test
	void shouldRetainAllWithPredicateOnLargeBitmapWithComplexCondition() {
		// Create bitmap with 300 elements
		final BaseBitmap large = new BaseBitmap();
		for (int i = 1; i <= 300; i++) {
			large.add(i);
		}

		// Retain multiples of 3 or 5
		large.retainAll(value -> value % 3 == 0 || value % 5 == 0);

		// Verify correct elements were retained
		final int[] result = large.getArray();
		for (int value : result) {
			assertFalse(value % 3 != 0 && value % 5 != 0,
				"Value " + value + " should be in result");
		}
	}

	@Test
	void shouldRetainAllWithPredicateOnLargeBitmapRetainingFirstHalf() {
		// Create bitmap with 200 elements
		final BaseBitmap large = new BaseBitmap();
		for (int i = 1; i <= 200; i++) {
			large.add(i);
		}

		// Retain first half
		large.retainAll(value -> value <= 100);
		assertEquals(100, large.size());

		// Verify only first half remains
		final int[] result = large.getArray();
		for (int i = 0; i < result.length; i++) {
			assertEquals(i + 1, result[i]);
		}
	}

	@Test
	void shouldRetainAllWithPredicateOnLargeBitmapRetainingLastHalf() {
		// Create bitmap with 200 elements
		final BaseBitmap large = new BaseBitmap();
		for (int i = 1; i <= 200; i++) {
			large.add(i);
		}

		// Retain last half
		large.retainAll(value -> value > 100);
		assertEquals(100, large.size());

		// Verify only last half remains
		final int[] result = large.getArray();
		for (int i = 0; i < result.length; i++) {
			assertEquals(i + 101, result[i]);
		}
	}

	@Test
	void shouldRetainAllWithPredicateOnBitmapExactly64Elements() {
		// Test edge case at boundary
		final BaseBitmap exact = new BaseBitmap();
		for (int i = 1; i <= 64; i++) {
			exact.add(i);
		}
		assertEquals(64, exact.size());

		// Retain even numbers
		exact.retainAll(value -> value % 2 == 0);
		assertEquals(32, exact.size());

		// Verify only even numbers remain
		final int[] result = exact.getArray();
		for (int i = 0; i < result.length; i++) {
			assertEquals((i + 1) * 2, result[i]);
		}
	}

	@Test
	void shouldRetainAllWithPredicateOnBitmapExactly65Elements() {
		// Test edge case just over boundary
		final BaseBitmap justOver = new BaseBitmap();
		for (int i = 1; i <= 65; i++) {
			justOver.add(i);
		}
		assertEquals(65, justOver.size());

		// Retain values less than or equal to 32
		justOver.retainAll(value -> value <= 32);
		assertEquals(32, justOver.size());

		// Verify correct elements remain
		final int[] result = justOver.getArray();
		for (int i = 0; i < result.length; i++) {
			assertEquals(i + 1, result[i]);
		}
	}

	@Test
	void shouldRetainAllWithPredicateOnBitmapExactly63Elements() {
		// Test edge case just under boundary
		final BaseBitmap justUnder = new BaseBitmap();
		for (int i = 1; i <= 63; i++) {
			justUnder.add(i);
		}
		assertEquals(63, justUnder.size());

		// Retain values divisible by 7
		justUnder.retainAll(value -> value % 7 == 0);
		assertEquals(9, justUnder.size());

		// Verify correct elements remain
		final int[] result = justUnder.getArray();
		for (int value : result) {
			assertEquals(0, value % 7, "Value " + value + " should be divisible by 7");
		}
	}

	@Test
	void shouldRetainAllWithPredicateOnSparseSmallBitmap() {
		// Test with sparse bitmap (large gaps between values)
		final BaseBitmap sparse = new BaseBitmap(1, 100, 200, 300, 400);
		assertEquals(5, sparse.size());

		sparse.retainAll(value -> value >= 200);
		assertEquals(3, sparse.size());
		assertArrayEquals(new int[]{200, 300, 400}, sparse.getArray());
	}

	@Test
	void shouldRetainAllWithPredicateOnSparseLargeBitmap() {
		// Test with large sparse bitmap
		final BaseBitmap sparse = new BaseBitmap();
		for (int i = 0; i < 100; i++) {
			sparse.add(i * 100); // 0, 100, 200, ..., 9900
		}
		assertEquals(100, sparse.size());

		sparse.retainAll(value -> value % 200 == 0);
		assertEquals(50, sparse.size());

		// Verify correct elements remain
		final int[] result = sparse.getArray();
		for (int value : result) {
			assertEquals(0, value % 200, "Value " + value + " should be divisible by 200");
		}
	}

	@Test
	void shouldRetainAllAndRemoveAllBeInverse() {
		// Test that retainAll and removeAll are logical inverses
		final BaseBitmap bitmap1 = new BaseBitmap();
		final BaseBitmap bitmap2 = new BaseBitmap();
		for (int i = 1; i <= 100; i++) {
			bitmap1.add(i);
			bitmap2.add(i);
		}

		bitmap1.retainAll(value -> value % 3 == 0);
		bitmap2.removeAll(value -> value % 3 != 0);

		assertArrayEquals(bitmap1.getArray(), bitmap2.getArray());
	}

	@Test
	void shouldRemoveAllPreserveCardinality() {
		// Test that size() is correctly updated after removeAll
		final BaseBitmap bitmap = new BaseBitmap();
		for (int i = 1; i <= 100; i++) {
			bitmap.add(i);
		}

		int sizeBefore = bitmap.size();
		bitmap.removeAll(value -> value % 2 == 0);
		int sizeAfter = bitmap.size();

		assertEquals(sizeBefore / 2, sizeAfter);
		assertEquals(sizeAfter, bitmap.getArray().length);
	}

	@Test
	void shouldRetainAllPreserveCardinality() {
		// Test that size() is correctly updated after retainAll
		final BaseBitmap bitmap = new BaseBitmap();
		for (int i = 1; i <= 100; i++) {
			bitmap.add(i);
		}

		int sizeBefore = bitmap.size();
		bitmap.retainAll(value -> value % 2 == 0);
		int sizeAfter = bitmap.size();

		assertEquals(sizeBefore / 2, sizeAfter);
		assertEquals(sizeAfter, bitmap.getArray().length);
	}

	@Test
	void shouldRemoveAllWorkWithNegativeValues() {
		// Test with negative values (if supported by RoaringBitmap)
		final BaseBitmap bitmap = new BaseBitmap(-10, -5, 0, 5, 10);
		assertEquals(5, bitmap.size());

		bitmap.removeAll(value -> value < 0);
		assertEquals(3, bitmap.size());
		assertArrayEquals(new int[]{0, 5, 10}, bitmap.getArray());
	}

	@Test
	void shouldRetainAllWorkWithNegativeValues() {
		// Test with negative values (if supported by RoaringBitmap)
		final BaseBitmap bitmap = new BaseBitmap(-10, -5, 0, 5, 10);
		assertEquals(5, bitmap.size());

		bitmap.retainAll(value -> value < 0);
		assertEquals(2, bitmap.size());
		assertArrayEquals(new int[]{-10, -5}, bitmap.getArray());
	}

	@Test
	void shouldRemoveAllWithPredicateOnLargeBitmapWithAlternatingPattern() {
		// Test batch processing with alternating pattern
		final BaseBitmap large = new BaseBitmap();
		int matchingCount = 0;
		for (int i = 0; i < 200; i++) {
			large.add(i);
			if (i % 3 == 0) {
				matchingCount++;
			}
		}

		// Remove every third element
		large.removeAll(value -> value % 3 == 0);
		assertEquals(200 - matchingCount, large.size()); // 200 - 67 = 133

		// Verify no multiples of 3 remain
		final int[] result = large.getArray();
		for (int value : result) {
			assertNotEquals(0, value % 3, "Value " + value + " should not be in result");
		}
	}

	@Test
	void shouldRetainAllWithPredicateOnLargeBitmapWithAlternatingPattern() {
		// Test batch processing with alternating pattern
		final BaseBitmap large = new BaseBitmap();
		for (int i = 0; i < 200; i++) {
			large.add(i);
		}

		// Retain every third element
		large.retainAll(value -> value % 3 == 0);
		assertEquals(67, large.size()); // 0-199 has 67 multiples of 3 (0, 3, 6, ..., 198)

		// Verify only multiples of 3 remain
		final int[] result = large.getArray();
		for (int value : result) {
			assertEquals(0, value % 3, "Value " + value + " should be divisible by 3");
		}
	}

	private static int[] toArray(OfInt iterator, int size) {
		final int[] result = new int[size];
		int index = 0;
		while (iterator.hasNext() && index < size) {
			result[index++] = iterator.next();
		}
		assertEquals(size, index);
		assertFalse(iterator.hasNext());
		return result;
	}

}
