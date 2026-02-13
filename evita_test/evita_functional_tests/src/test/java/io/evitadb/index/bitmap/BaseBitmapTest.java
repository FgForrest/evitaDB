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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

import java.util.PrimitiveIterator.OfInt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link BaseBitmap}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("BaseBitmap")
class BaseBitmapTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create empty bitmap with default constructor")
		void shouldCreateEmptyBitmapWithDefaultConstructor() {
			final BaseBitmap bitmap = new BaseBitmap();
			assertTrue(bitmap.isEmpty());
			assertEquals(0, bitmap.size());
		}

		@Test
		@DisplayName("should create bitmap from varargs")
		void shouldCreateBitmapFromVarargs() {
			final BaseBitmap bitmap = new BaseBitmap(5, 3, 8, 1);
			assertEquals(4, bitmap.size());
			// BaseBitmap sorts its elements
			assertArrayEquals(new int[]{1, 3, 5, 8}, bitmap.getArray());
		}

		@Test
		@DisplayName("should create bitmap from Bitmap copy")
		void shouldCreateBitmapFromBitmapCopy() {
			final BaseBitmap original = new BaseBitmap(1, 2, 3);
			final BaseBitmap copy = new BaseBitmap(original);
			assertEquals(3, copy.size());
			assertArrayEquals(new int[]{1, 2, 3}, copy.getArray());
			// modifying the copy should not affect the original
			copy.add(4);
			assertEquals(3, original.size());
		}

		@Test
		@DisplayName("should create bitmap from RoaringBitmap")
		void shouldCreateBitmapFromRoaringBitmap() {
			final RoaringBitmap roaring = RoaringBitmap.bitmapOf(10, 20, 30);
			final BaseBitmap bitmap = new BaseBitmap(roaring);
			assertEquals(3, bitmap.size());
			assertArrayEquals(new int[]{10, 20, 30}, bitmap.getArray());
		}
	}

	@Nested
	@DisplayName("Read operations")
	class ReadOperationsTest {

		@Test
		@DisplayName("should read all data with indexOf")
		void shouldReadAllData() {
			final BaseBitmap tested = new BaseBitmap();
			tested.addAll(5, 8, 10, 3);
			assertEquals(4, tested.size());
			assertArrayEquals(new int[]{3, 5, 8, 10}, tested.getArray());
			assertEquals(1, tested.indexOf(5));
			assertEquals(0, tested.indexOf(3));
			assertEquals(-1, tested.indexOf(0));
			assertEquals(-2, tested.indexOf(4));
			assertEquals(-3, tested.indexOf(6));
			assertEquals(-4, tested.indexOf(9));
			assertEquals(-5, tested.indexOf(11));
		}

		@Test
		@DisplayName("should iterate over all data")
		void shouldIterateOverAllData() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			final OfInt it = tested.iterator();
			assertArrayEquals(new int[]{3, 5, 8, 10}, toArray(it, tested.size()));
		}

		@Test
		@DisplayName("should contain existing element")
		void shouldContainExistingElement() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			assertTrue(tested.contains(3));
			assertTrue(tested.contains(5));
			assertTrue(tested.contains(8));
			assertTrue(tested.contains(10));
		}

		@Test
		@DisplayName("should not contain missing element")
		void shouldNotContainMissingElement() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			assertFalse(tested.contains(0));
			assertFalse(tested.contains(4));
			assertFalse(tested.contains(11));
		}

		@Test
		@DisplayName("should return element by index")
		void shouldReturnElementByIndex() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			assertEquals(3, tested.get(0));
			assertEquals(5, tested.get(1));
			assertEquals(8, tested.get(2));
			assertEquals(10, tested.get(3));
		}

		@Test
		@DisplayName("should throw on get with invalid index")
		void shouldThrowOnGetWithInvalidIndex() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			assertThrows(IndexOutOfBoundsException.class, () -> tested.get(4));
		}

		@Test
		@DisplayName("should return first element")
		void shouldReturnFirstElement() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			assertEquals(3, tested.getFirst());
		}

		@Test
		@DisplayName("should return last element")
		void shouldReturnLastElement() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			assertEquals(10, tested.getLast());
		}

		@Test
		@DisplayName("should throw on getFirst when empty")
		void shouldThrowOnGetFirstWhenEmpty() {
			final BaseBitmap tested = new BaseBitmap();
			assertThrows(IndexOutOfBoundsException.class, tested::getFirst);
		}

		@Test
		@DisplayName("should throw on getLast when empty")
		void shouldThrowOnGetLastWhenEmpty() {
			final BaseBitmap tested = new BaseBitmap();
			assertThrows(IndexOutOfBoundsException.class, tested::getLast);
		}

		@Test
		@DisplayName("should return isEmpty true when empty")
		void shouldReturnIsEmptyTrueWhenEmpty() {
			final BaseBitmap tested = new BaseBitmap();
			assertTrue(tested.isEmpty());
		}

		@Test
		@DisplayName("should return isEmpty false when non-empty")
		void shouldReturnIsEmptyFalseWhenNonEmpty() {
			final BaseBitmap tested = new BaseBitmap(1);
			assertFalse(tested.isEmpty());
		}

		@Test
		@DisplayName("should expose RoaringBitmap")
		void shouldExposeRoaringBitmap() {
			final BaseBitmap tested = new BaseBitmap(1, 2, 3);
			final RoaringBitmap roaringBitmap = tested.getRoaringBitmap();
			assertNotNull(roaringBitmap);
			assertEquals(3, roaringBitmap.getCardinality());
		}

		@Test
		@DisplayName("should return range")
		void shouldReturnRange() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10, 12, 15, 20, 23);
			assertArrayEquals(new int[]{8, 10, 12}, tested.getRange(2, 5));
			assertArrayEquals(new int[]{15, 20, 23}, tested.getRange(5, 8));
			assertArrayEquals(new int[]{3, 5, 8, 10, 12}, tested.getRange(0, 5));
		}

		@Test
		@DisplayName("should fail to return range when size is exceeded")
		void shouldFailReturnRangeWhenSizeIsExceeded() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10, 12, 15, 20, 23);
			assertThrows(IndexOutOfBoundsException.class, () -> tested.getRange(7, 10));
		}
	}

	@Nested
	@DisplayName("Add and remove operations")
	class AddAndRemoveOperationsTest {

		@Test
		@DisplayName("should add and see data")
		void shouldAddAndSeeData() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			tested.addAll(1, 15);
			assertEquals(6, tested.size());
			assertArrayEquals(new int[]{1, 3, 5, 8, 10, 15}, tested.getArray());
			assertArrayEquals(new int[]{1, 3, 5, 8, 10, 15}, toArray(tested.iterator(), tested.size()));
		}

		@Test
		@DisplayName("should remove and miss data")
		void shouldRemoveAndMissData() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			tested.removeAll(3, 8);
			assertEquals(2, tested.size());
			assertArrayEquals(new int[]{5, 10}, tested.getArray());
			assertArrayEquals(new int[]{5, 10}, toArray(tested.iterator(), tested.size()));
		}

		@Test
		@DisplayName("should return true on add when element is new")
		void shouldReturnTrueOnAddWhenElementIsNew() {
			final BaseBitmap tested = new BaseBitmap(1, 2, 3);
			assertTrue(tested.add(5));
		}

		@Test
		@DisplayName("should return false on add when element is duplicate")
		void shouldReturnFalseOnAddWhenElementIsDuplicate() {
			final BaseBitmap tested = new BaseBitmap(1, 2, 3);
			assertFalse(tested.add(2));
		}

		@Test
		@DisplayName("should return true on remove when element exists")
		void shouldReturnTrueOnRemoveWhenElementExists() {
			final BaseBitmap tested = new BaseBitmap(1, 2, 3);
			assertTrue(tested.remove(2));
		}

		@Test
		@DisplayName("should return false on remove when element does not exist")
		void shouldReturnFalseOnRemoveWhenElementDoesNotExist() {
			final BaseBitmap tested = new BaseBitmap(1, 2, 3);
			assertFalse(tested.remove(99));
		}

		@Test
		@DisplayName("should addAll from Bitmap instance")
		void shouldAddAllFromBitmapInstance() {
			final BaseBitmap tested = new BaseBitmap(1, 2, 3);
			final BaseBitmap other = new BaseBitmap(4, 5, 6);
			tested.addAll(other);
			assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6}, tested.getArray());
		}

		@Test
		@DisplayName("should removeAll from RoaringBitmapBackedBitmap instance")
		void shouldRemoveAllFromRoaringBitmapBackedBitmap() {
			final BaseBitmap tested = new BaseBitmap(1, 2, 3, 4, 5);
			final BaseBitmap toRemove = new BaseBitmap(2, 4);
			tested.removeAll(toRemove);
			assertArrayEquals(new int[]{1, 3, 5}, tested.getArray());
		}

		@Test
		@DisplayName("should removeAll from non-RoaringBitmap Bitmap instance")
		void shouldRemoveAllFromNonRoaringBitmapInstance() {
			final BaseBitmap tested = new BaseBitmap(1, 2, 3, 4, 5);
			// ArrayBitmap is not a RoaringBitmapBackedBitmap, so the iteration path is used
			final ArrayBitmap toRemove = new ArrayBitmap(2, 4);
			tested.removeAll(toRemove);
			assertArrayEquals(new int[]{1, 3, 5}, tested.getArray());
		}

		@Test
		@DisplayName("should clear all data")
		void shouldClearAllData() {
			final BaseBitmap tested = new BaseBitmap(1, 2, 3);
			tested.clear();
			assertTrue(tested.isEmpty());
			assertEquals(0, tested.size());
			assertArrayEquals(new int[]{}, tested.getArray());
		}
	}

	@Nested
	@DisplayName("RemoveAll with predicate")
	class RemoveAllWithPredicateTest {

		@Test
		@DisplayName("should remove even numbers on small bitmap")
		void shouldRemoveAllWithPredicateOnSmallBitmap() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			tested.removeAll(value -> value % 2 == 0);
			assertEquals(2, tested.size());
			assertArrayEquals(new int[]{3, 5}, tested.getArray());
		}

		@Test
		@DisplayName("should remove odd numbers on small bitmap")
		void shouldRemoveAllWithPredicateOnSmallBitmapRemovingOdds() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			tested.removeAll(value -> value % 2 != 0);
			assertEquals(2, tested.size());
			assertArrayEquals(new int[]{8, 10}, tested.getArray());
		}

		@Test
		@DisplayName("should remove all on small bitmap")
		void shouldRemoveAllWithPredicateOnSmallBitmapRemovingAll() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			tested.removeAll(value -> true);
			assertEquals(0, tested.size());
			assertArrayEquals(new int[]{}, tested.getArray());
		}

		@Test
		@DisplayName("should remove none on small bitmap")
		void shouldRemoveAllWithPredicateOnSmallBitmapRemovingNone() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			tested.removeAll(value -> false);
			assertEquals(4, tested.size());
			assertArrayEquals(new int[]{3, 5, 8, 10}, tested.getArray());
		}

		@Test
		@DisplayName("should remove single matching element")
		void shouldRemoveAllWithPredicateOnSmallBitmapWithSingleElement() {
			final BaseBitmap singleElement = new BaseBitmap(42);
			singleElement.removeAll(value -> value == 42);
			assertEquals(0, singleElement.size());
			assertArrayEquals(new int[]{}, singleElement.getArray());
		}

		@Test
		@DisplayName("should keep single non-matching element")
		void shouldRemoveAllWithPredicateOnSmallBitmapWithSingleElementNotMatching() {
			final BaseBitmap singleElement = new BaseBitmap(42);
			singleElement.removeAll(value -> value != 42);
			assertEquals(1, singleElement.size());
			assertArrayEquals(new int[]{42}, singleElement.getArray());
		}

		@Test
		@DisplayName("should handle empty bitmap")
		void shouldRemoveAllWithPredicateOnEmptyBitmap() {
			final BaseBitmap empty = new BaseBitmap();
			empty.removeAll(value -> true);
			assertEquals(0, empty.size());
			assertArrayEquals(new int[]{}, empty.getArray());
		}

		@Test
		@DisplayName("should remove first element")
		void shouldRemoveAllWithPredicateOnSmallBitmapRemovingFirstElement() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			tested.removeAll(value -> value == 3);
			assertEquals(3, tested.size());
			assertArrayEquals(new int[]{5, 8, 10}, tested.getArray());
		}

		@Test
		@DisplayName("should remove last element")
		void shouldRemoveAllWithPredicateOnSmallBitmapRemovingLastElement() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			tested.removeAll(value -> value == 10);
			assertEquals(3, tested.size());
			assertArrayEquals(new int[]{3, 5, 8}, tested.getArray());
		}

		@Test
		@DisplayName("should remove middle elements")
		void shouldRemoveAllWithPredicateOnSmallBitmapRemovingMiddleElements() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			tested.removeAll(value -> value > 3 && value < 10);
			assertEquals(2, tested.size());
			assertArrayEquals(new int[]{3, 10}, tested.getArray());
		}

		@Test
		@DisplayName("should remove even numbers on large bitmap")
		void shouldRemoveAllWithPredicateOnLargeBitmap() {
			final BaseBitmap large = new BaseBitmap();
			for (int i = 1; i <= 100; i++) {
				large.add(i);
			}
			assertEquals(100, large.size());
			large.removeAll(value -> value % 2 == 0);
			assertEquals(50, large.size());
			final int[] result = large.getArray();
			for (int i = 0; i < result.length; i++) {
				assertEquals(i * 2 + 1, result[i]);
			}
		}

		@Test
		@DisplayName("should remove all on large bitmap")
		void shouldRemoveAllWithPredicateOnLargeBitmapRemovingAll() {
			final BaseBitmap large = new BaseBitmap();
			for (int i = 1; i <= 200; i++) {
				large.add(i);
			}
			assertEquals(200, large.size());
			large.removeAll(value -> true);
			assertEquals(0, large.size());
			assertArrayEquals(new int[]{}, large.getArray());
		}

		@Test
		@DisplayName("should remove none on large bitmap")
		void shouldRemoveAllWithPredicateOnLargeBitmapRemovingNone() {
			final BaseBitmap large = new BaseBitmap();
			for (int i = 1; i <= 150; i++) {
				large.add(i);
			}
			assertEquals(150, large.size());
			large.removeAll(value -> false);
			assertEquals(150, large.size());
			final int[] result = large.getArray();
			for (int i = 0; i < result.length; i++) {
				assertEquals(i + 1, result[i]);
			}
		}

		@Test
		@DisplayName("should remove multiples of 3 or 5 on large bitmap")
		void shouldRemoveAllWithPredicateOnLargeBitmapWithComplexCondition() {
			final BaseBitmap large = new BaseBitmap();
			for (int i = 1; i <= 300; i++) {
				large.add(i);
			}
			large.removeAll(value -> value % 3 == 0 || value % 5 == 0);
			final int[] result = large.getArray();
			for (int value : result) {
				assertFalse(value % 3 == 0 || value % 5 == 0,
					"Value " + value + " should not be in result");
			}
		}

		@Test
		@DisplayName("should remove first half on large bitmap")
		void shouldRemoveAllWithPredicateOnLargeBitmapRemovingFirstHalf() {
			final BaseBitmap large = new BaseBitmap();
			for (int i = 1; i <= 200; i++) {
				large.add(i);
			}
			large.removeAll(value -> value <= 100);
			assertEquals(100, large.size());
			final int[] result = large.getArray();
			for (int i = 0; i < result.length; i++) {
				assertEquals(i + 101, result[i]);
			}
		}

		@Test
		@DisplayName("should remove last half on large bitmap")
		void shouldRemoveAllWithPredicateOnLargeBitmapRemovingLastHalf() {
			final BaseBitmap large = new BaseBitmap();
			for (int i = 1; i <= 200; i++) {
				large.add(i);
			}
			large.removeAll(value -> value > 100);
			assertEquals(100, large.size());
			final int[] result = large.getArray();
			for (int i = 0; i < result.length; i++) {
				assertEquals(i + 1, result[i]);
			}
		}

		@Test
		@DisplayName("should remove even numbers on exactly 64 elements")
		void shouldRemoveAllWithPredicateOnBitmapExactly64Elements() {
			final BaseBitmap exact = new BaseBitmap();
			for (int i = 1; i <= 64; i++) {
				exact.add(i);
			}
			assertEquals(64, exact.size());
			exact.removeAll(value -> value % 2 == 0);
			assertEquals(32, exact.size());
			final int[] result = exact.getArray();
			for (int i = 0; i < result.length; i++) {
				assertEquals(i * 2 + 1, result[i]);
			}
		}

		@Test
		@DisplayName("should remove values > 32 on exactly 65 elements")
		void shouldRemoveAllWithPredicateOnBitmapExactly65Elements() {
			final BaseBitmap justOver = new BaseBitmap();
			for (int i = 1; i <= 65; i++) {
				justOver.add(i);
			}
			assertEquals(65, justOver.size());
			justOver.removeAll(value -> value > 32);
			assertEquals(32, justOver.size());
			final int[] result = justOver.getArray();
			for (int i = 0; i < result.length; i++) {
				assertEquals(i + 1, result[i]);
			}
		}

		@Test
		@DisplayName("should remove multiples of 7 on exactly 63 elements")
		void shouldRemoveAllWithPredicateOnBitmapExactly63Elements() {
			final BaseBitmap justUnder = new BaseBitmap();
			for (int i = 1; i <= 63; i++) {
				justUnder.add(i);
			}
			assertEquals(63, justUnder.size());
			justUnder.removeAll(value -> value % 7 == 0);
			assertEquals(54, justUnder.size());
			final int[] result = justUnder.getArray();
			for (int value : result) {
				assertNotEquals(0, value % 7, "Value " + value + " should not be in result");
			}
		}

		@Test
		@DisplayName("should remove on sparse small bitmap")
		void shouldRemoveAllWithPredicateOnSparseSmallBitmap() {
			final BaseBitmap sparse = new BaseBitmap(1, 100, 200, 300, 400);
			assertEquals(5, sparse.size());
			sparse.removeAll(value -> value >= 200);
			assertEquals(2, sparse.size());
			assertArrayEquals(new int[]{1, 100}, sparse.getArray());
		}

		@Test
		@DisplayName("should remove on sparse large bitmap")
		void shouldRemoveAllWithPredicateOnSparseLargeBitmap() {
			final BaseBitmap sparse = new BaseBitmap();
			for (int i = 0; i < 100; i++) {
				sparse.add(i * 100);
			}
			assertEquals(100, sparse.size());
			sparse.removeAll(value -> value % 200 == 0);
			assertEquals(50, sparse.size());
			final int[] result = sparse.getArray();
			for (int value : result) {
				assertNotEquals(0, value % 200, "Value " + value + " should not be in result");
			}
		}

		@Test
		@DisplayName("should remove alternating pattern on large bitmap")
		void shouldRemoveAllWithPredicateOnLargeBitmapWithAlternatingPattern() {
			final BaseBitmap large = new BaseBitmap();
			int matchingCount = 0;
			for (int i = 0; i < 200; i++) {
				large.add(i);
				if (i % 3 == 0) {
					matchingCount++;
				}
			}
			large.removeAll(value -> value % 3 == 0);
			assertEquals(200 - matchingCount, large.size());
			final int[] result = large.getArray();
			for (int value : result) {
				assertNotEquals(0, value % 3, "Value " + value + " should not be in result");
			}
		}

		@Test
		@DisplayName("should preserve cardinality after removeAll")
		void shouldRemoveAllPreserveCardinality() {
			final BaseBitmap bitmap = new BaseBitmap();
			for (int i = 1; i <= 100; i++) {
				bitmap.add(i);
			}
			final int sizeBefore = bitmap.size();
			bitmap.removeAll(value -> value % 2 == 0);
			final int sizeAfter = bitmap.size();
			assertEquals(sizeBefore / 2, sizeAfter);
			assertEquals(sizeAfter, bitmap.getArray().length);
		}
	}

	@Nested
	@DisplayName("RetainAll with predicate")
	class RetainAllWithPredicateTest {

		@Test
		@DisplayName("should retain even numbers on small bitmap")
		void shouldRetainAllWithPredicateOnSmallBitmap() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			tested.retainAll(value -> value % 2 == 0);
			assertEquals(2, tested.size());
			assertArrayEquals(new int[]{8, 10}, tested.getArray());
		}

		@Test
		@DisplayName("should retain odd numbers on small bitmap")
		void shouldRetainAllWithPredicateOnSmallBitmapRetainingOdds() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			tested.retainAll(value -> value % 2 != 0);
			assertEquals(2, tested.size());
			assertArrayEquals(new int[]{3, 5}, tested.getArray());
		}

		@Test
		@DisplayName("should retain all on small bitmap")
		void shouldRetainAllWithPredicateOnSmallBitmapRetainingAll() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			tested.retainAll(value -> true);
			assertEquals(4, tested.size());
			assertArrayEquals(new int[]{3, 5, 8, 10}, tested.getArray());
		}

		@Test
		@DisplayName("should retain none on small bitmap")
		void shouldRetainAllWithPredicateOnSmallBitmapRetainingNone() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			tested.retainAll(value -> false);
			assertEquals(0, tested.size());
			assertArrayEquals(new int[]{}, tested.getArray());
		}

		@Test
		@DisplayName("should retain single matching element")
		void shouldRetainAllWithPredicateOnSmallBitmapWithSingleElement() {
			final BaseBitmap singleElement = new BaseBitmap(42);
			singleElement.retainAll(value -> value == 42);
			assertEquals(1, singleElement.size());
			assertArrayEquals(new int[]{42}, singleElement.getArray());
		}

		@Test
		@DisplayName("should remove single non-matching element")
		void shouldRetainAllWithPredicateOnSmallBitmapWithSingleElementNotMatching() {
			final BaseBitmap singleElement = new BaseBitmap(42);
			singleElement.retainAll(value -> value != 42);
			assertEquals(0, singleElement.size());
			assertArrayEquals(new int[]{}, singleElement.getArray());
		}

		@Test
		@DisplayName("should handle empty bitmap")
		void shouldRetainAllWithPredicateOnEmptyBitmap() {
			final BaseBitmap empty = new BaseBitmap();
			empty.retainAll(value -> true);
			assertEquals(0, empty.size());
			assertArrayEquals(new int[]{}, empty.getArray());
		}

		@Test
		@DisplayName("should retain first element")
		void shouldRetainAllWithPredicateOnSmallBitmapRetainingFirstElement() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			tested.retainAll(value -> value == 3);
			assertEquals(1, tested.size());
			assertArrayEquals(new int[]{3}, tested.getArray());
		}

		@Test
		@DisplayName("should retain last element")
		void shouldRetainAllWithPredicateOnSmallBitmapRetainingLastElement() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			tested.retainAll(value -> value == 10);
			assertEquals(1, tested.size());
			assertArrayEquals(new int[]{10}, tested.getArray());
		}

		@Test
		@DisplayName("should retain middle elements")
		void shouldRetainAllWithPredicateOnSmallBitmapRetainingMiddleElements() {
			final BaseBitmap tested = new BaseBitmap(3, 5, 8, 10);
			tested.retainAll(value -> value > 3 && value < 10);
			assertEquals(2, tested.size());
			assertArrayEquals(new int[]{5, 8}, tested.getArray());
		}

		@Test
		@DisplayName("should retain even numbers on large bitmap")
		void shouldRetainAllWithPredicateOnLargeBitmap() {
			final BaseBitmap large = new BaseBitmap();
			for (int i = 1; i <= 100; i++) {
				large.add(i);
			}
			assertEquals(100, large.size());
			large.retainAll(value -> value % 2 == 0);
			assertEquals(50, large.size());
			final int[] result = large.getArray();
			for (int i = 0; i < result.length; i++) {
				assertEquals((i + 1) * 2, result[i]);
			}
		}

		@Test
		@DisplayName("should retain all on large bitmap")
		void shouldRetainAllWithPredicateOnLargeBitmapRetainingAll() {
			final BaseBitmap large = new BaseBitmap();
			for (int i = 1; i <= 200; i++) {
				large.add(i);
			}
			assertEquals(200, large.size());
			large.retainAll(value -> true);
			assertEquals(200, large.size());
			final int[] result = large.getArray();
			for (int i = 0; i < result.length; i++) {
				assertEquals(i + 1, result[i]);
			}
		}

		@Test
		@DisplayName("should retain none on large bitmap")
		void shouldRetainAllWithPredicateOnLargeBitmapRetainingNone() {
			final BaseBitmap large = new BaseBitmap();
			for (int i = 1; i <= 150; i++) {
				large.add(i);
			}
			assertEquals(150, large.size());
			large.retainAll(value -> false);
			assertEquals(0, large.size());
			assertArrayEquals(new int[]{}, large.getArray());
		}

		@Test
		@DisplayName("should retain multiples of 3 or 5 on large bitmap")
		void shouldRetainAllWithPredicateOnLargeBitmapWithComplexCondition() {
			final BaseBitmap large = new BaseBitmap();
			for (int i = 1; i <= 300; i++) {
				large.add(i);
			}
			large.retainAll(value -> value % 3 == 0 || value % 5 == 0);
			final int[] result = large.getArray();
			for (int value : result) {
				assertFalse(value % 3 != 0 && value % 5 != 0,
					"Value " + value + " should be in result");
			}
		}

		@Test
		@DisplayName("should retain first half on large bitmap")
		void shouldRetainAllWithPredicateOnLargeBitmapRetainingFirstHalf() {
			final BaseBitmap large = new BaseBitmap();
			for (int i = 1; i <= 200; i++) {
				large.add(i);
			}
			large.retainAll(value -> value <= 100);
			assertEquals(100, large.size());
			final int[] result = large.getArray();
			for (int i = 0; i < result.length; i++) {
				assertEquals(i + 1, result[i]);
			}
		}

		@Test
		@DisplayName("should retain last half on large bitmap")
		void shouldRetainAllWithPredicateOnLargeBitmapRetainingLastHalf() {
			final BaseBitmap large = new BaseBitmap();
			for (int i = 1; i <= 200; i++) {
				large.add(i);
			}
			large.retainAll(value -> value > 100);
			assertEquals(100, large.size());
			final int[] result = large.getArray();
			for (int i = 0; i < result.length; i++) {
				assertEquals(i + 101, result[i]);
			}
		}

		@Test
		@DisplayName("should retain even numbers on exactly 64 elements")
		void shouldRetainAllWithPredicateOnBitmapExactly64Elements() {
			final BaseBitmap exact = new BaseBitmap();
			for (int i = 1; i <= 64; i++) {
				exact.add(i);
			}
			assertEquals(64, exact.size());
			exact.retainAll(value -> value % 2 == 0);
			assertEquals(32, exact.size());
			final int[] result = exact.getArray();
			for (int i = 0; i < result.length; i++) {
				assertEquals((i + 1) * 2, result[i]);
			}
		}

		@Test
		@DisplayName("should retain values <= 32 on exactly 65 elements")
		void shouldRetainAllWithPredicateOnBitmapExactly65Elements() {
			final BaseBitmap justOver = new BaseBitmap();
			for (int i = 1; i <= 65; i++) {
				justOver.add(i);
			}
			assertEquals(65, justOver.size());
			justOver.retainAll(value -> value <= 32);
			assertEquals(32, justOver.size());
			final int[] result = justOver.getArray();
			for (int i = 0; i < result.length; i++) {
				assertEquals(i + 1, result[i]);
			}
		}

		@Test
		@DisplayName("should retain multiples of 7 on exactly 63 elements")
		void shouldRetainAllWithPredicateOnBitmapExactly63Elements() {
			final BaseBitmap justUnder = new BaseBitmap();
			for (int i = 1; i <= 63; i++) {
				justUnder.add(i);
			}
			assertEquals(63, justUnder.size());
			justUnder.retainAll(value -> value % 7 == 0);
			assertEquals(9, justUnder.size());
			final int[] result = justUnder.getArray();
			for (int value : result) {
				assertEquals(0, value % 7, "Value " + value + " should be divisible by 7");
			}
		}

		@Test
		@DisplayName("should retain on sparse small bitmap")
		void shouldRetainAllWithPredicateOnSparseSmallBitmap() {
			final BaseBitmap sparse = new BaseBitmap(1, 100, 200, 300, 400);
			assertEquals(5, sparse.size());
			sparse.retainAll(value -> value >= 200);
			assertEquals(3, sparse.size());
			assertArrayEquals(new int[]{200, 300, 400}, sparse.getArray());
		}

		@Test
		@DisplayName("should retain on sparse large bitmap")
		void shouldRetainAllWithPredicateOnSparseLargeBitmap() {
			final BaseBitmap sparse = new BaseBitmap();
			for (int i = 0; i < 100; i++) {
				sparse.add(i * 100);
			}
			assertEquals(100, sparse.size());
			sparse.retainAll(value -> value % 200 == 0);
			assertEquals(50, sparse.size());
			final int[] result = sparse.getArray();
			for (int value : result) {
				assertEquals(0, value % 200, "Value " + value + " should be divisible by 200");
			}
		}

		@Test
		@DisplayName("should retain alternating pattern on large bitmap")
		void shouldRetainAllWithPredicateOnLargeBitmapWithAlternatingPattern() {
			final BaseBitmap large = new BaseBitmap();
			for (int i = 0; i < 200; i++) {
				large.add(i);
			}
			large.retainAll(value -> value % 3 == 0);
			assertEquals(67, large.size());
			final int[] result = large.getArray();
			for (int value : result) {
				assertEquals(0, value % 3, "Value " + value + " should be divisible by 3");
			}
		}

		@Test
		@DisplayName("should be inverse of removeAll")
		void shouldRetainAllAndRemoveAllBeInverse() {
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
		@DisplayName("should preserve cardinality after retainAll")
		void shouldRetainAllPreserveCardinality() {
			final BaseBitmap bitmap = new BaseBitmap();
			for (int i = 1; i <= 100; i++) {
				bitmap.add(i);
			}
			final int sizeBefore = bitmap.size();
			bitmap.retainAll(value -> value % 2 == 0);
			final int sizeAfter = bitmap.size();
			assertEquals(sizeBefore / 2, sizeAfter);
			assertEquals(sizeAfter, bitmap.getArray().length);
		}
	}

	@Nested
	@DisplayName("Equals, hashCode, and toString")
	class EqualsHashCodeToStringTest {

		@Test
		@DisplayName("should be equal to identical BaseBitmap")
		void shouldBeEqualToIdenticalBaseBitmap() {
			final BaseBitmap bitmap1 = new BaseBitmap(1, 2, 3);
			final BaseBitmap bitmap2 = new BaseBitmap(1, 2, 3);
			assertEquals(bitmap1, bitmap2);
		}

		@Test
		@DisplayName("should not be equal to different BaseBitmap")
		void shouldNotBeEqualToDifferentBaseBitmap() {
			final BaseBitmap bitmap1 = new BaseBitmap(1, 2, 3);
			final BaseBitmap bitmap2 = new BaseBitmap(4, 5, 6);
			assertNotEquals(bitmap1, bitmap2);
		}

		@Test
		@DisplayName("should not be equal to null")
		void shouldNotBeEqualToNull() {
			final BaseBitmap bitmap = new BaseBitmap(1, 2, 3);
			assertNotEquals(null, bitmap);
		}

		@Test
		@DisplayName("should have consistent hashCode")
		void shouldHaveConsistentHashCode() {
			final BaseBitmap bitmap1 = new BaseBitmap(1, 2, 3);
			final BaseBitmap bitmap2 = new BaseBitmap(1, 2, 3);
			assertEquals(bitmap1.hashCode(), bitmap2.hashCode());
		}

		@Test
		@DisplayName("should produce readable toString")
		void shouldProduceReadableToString() {
			final BaseBitmap bitmap = new BaseBitmap(1, 2, 3);
			final String result = bitmap.toString();
			assertNotNull(result);
			assertEquals("[1, 2, 3]", result);
		}

		@Test
		@DisplayName("should produce empty toString for empty bitmap")
		void shouldProduceEmptyToStringForEmptyBitmap() {
			final BaseBitmap bitmap = new BaseBitmap();
			assertEquals("[]", bitmap.toString());
		}
	}

	@Nested
	@DisplayName("Negative values")
	class NegativeValuesTest {

		@Test
		@DisplayName("should removeAll with predicate work with negative values")
		void shouldRemoveAllWorkWithNegativeValues() {
			final BaseBitmap bitmap = new BaseBitmap(-10, -5, 0, 5, 10);
			assertEquals(5, bitmap.size());
			bitmap.removeAll(value -> value < 0);
			assertEquals(3, bitmap.size());
			assertArrayEquals(new int[]{0, 5, 10}, bitmap.getArray());
		}

		@Test
		@DisplayName("should retainAll with predicate work with negative values")
		void shouldRetainAllWorkWithNegativeValues() {
			final BaseBitmap bitmap = new BaseBitmap(-10, -5, 0, 5, 10);
			assertEquals(5, bitmap.size());
			bitmap.retainAll(value -> value < 0);
			assertEquals(2, bitmap.size());
			assertArrayEquals(new int[]{-10, -5}, bitmap.getArray());
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
