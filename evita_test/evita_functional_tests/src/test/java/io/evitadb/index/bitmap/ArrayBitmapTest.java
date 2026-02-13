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

import io.evitadb.dataType.array.CompositeIntArray;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.PrimitiveIterator.OfInt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link ArrayBitmap}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("ArrayBitmap")
class ArrayBitmapTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("should create empty bitmap from empty CompositeIntArray")
		void shouldCreateEmptyBitmapFromEmptyCompositeIntArray() {
			final ArrayBitmap bitmap = new ArrayBitmap(new CompositeIntArray());
			assertTrue(bitmap.isEmpty());
			assertEquals(0, bitmap.size());
		}

		@Test
		@DisplayName("should create bitmap from varargs")
		void shouldCreateBitmapFromVarargs() {
			final ArrayBitmap bitmap = new ArrayBitmap(3, 1, 5);
			assertEquals(3, bitmap.size());
			// ArrayBitmap preserves insertion order (not sorted)
			assertArrayEquals(new int[]{3, 1, 5}, bitmap.getArray());
		}

		@Test
		@DisplayName("should create bitmap from CompositeIntArray")
		void shouldCreateBitmapFromCompositeIntArray() {
			final CompositeIntArray intArray = new CompositeIntArray();
			intArray.add(10);
			intArray.add(20);
			intArray.add(30);
			final ArrayBitmap bitmap = new ArrayBitmap(intArray);
			assertEquals(3, bitmap.size());
			assertArrayEquals(new int[]{10, 20, 30}, bitmap.getArray());
		}
	}

	@Nested
	@DisplayName("Add operations")
	class AddOperationsTest {

		@Test
		@DisplayName("should return true when adding new element")
		void shouldReturnTrueWhenAddingNewElement() {
			final ArrayBitmap bitmap = new ArrayBitmap(new CompositeIntArray());
			assertTrue(bitmap.add(5));
		}

		@Test
		@DisplayName("should return false when adding duplicate element")
		void shouldReturnFalseWhenAddingDuplicateElement() {
			final ArrayBitmap bitmap = new ArrayBitmap(10, 20);
			assertFalse(bitmap.add(10));
		}

		@Test
		@DisplayName("should add all varargs")
		void shouldAddAllVarargs() {
			final ArrayBitmap bitmap = new ArrayBitmap(new CompositeIntArray());
			bitmap.addAll(5, 10, 15);
			assertEquals(3, bitmap.size());
			assertArrayEquals(new int[]{5, 10, 15}, bitmap.getArray());
		}

		@Test
		@DisplayName("should add all from bitmap instance")
		void shouldAddAllFromBitmapInstance() {
			final ArrayBitmap bitmap = new ArrayBitmap(new CompositeIntArray());
			final BaseBitmap source = new BaseBitmap(3, 7, 11);
			bitmap.addAll(source);
			assertEquals(3, bitmap.size());
			assertArrayEquals(new int[]{3, 7, 11}, bitmap.getArray());
		}
	}

	@Nested
	@DisplayName("Remove operations throw UnsupportedOperationException")
	class RemoveOperationsTest {

		@Test
		@DisplayName("should throw on remove single element")
		void shouldThrowOnRemoveSingleElement() {
			final ArrayBitmap bitmap = new ArrayBitmap(1, 2, 3);
			assertThrows(UnsupportedOperationException.class, () -> bitmap.remove(1));
		}

		@Test
		@DisplayName("should throw on removeAll varargs")
		void shouldThrowOnRemoveAllVarargs() {
			final ArrayBitmap bitmap = new ArrayBitmap(1, 2, 3);
			assertThrows(UnsupportedOperationException.class, () -> bitmap.removeAll(1, 2));
		}

		@Test
		@DisplayName("should throw on removeAll bitmap instance")
		void shouldThrowOnRemoveAllBitmapInstance() {
			final ArrayBitmap bitmap = new ArrayBitmap(1, 2, 3);
			final BaseBitmap other = new BaseBitmap(1, 2);
			assertThrows(UnsupportedOperationException.class, () -> bitmap.removeAll(other));
		}
	}

	@Nested
	@DisplayName("Read operations")
	class ReadOperationsTest {

		@Test
		@DisplayName("should contain existing element")
		void shouldContainExistingElement() {
			final ArrayBitmap bitmap = new ArrayBitmap(5, 10, 15);
			assertTrue(bitmap.contains(5));
			assertTrue(bitmap.contains(10));
			assertTrue(bitmap.contains(15));
		}

		@Test
		@DisplayName("should not contain missing element")
		void shouldNotContainMissingElement() {
			final ArrayBitmap bitmap = new ArrayBitmap(5, 10, 15);
			assertFalse(bitmap.contains(1));
			assertFalse(bitmap.contains(7));
			assertFalse(bitmap.contains(20));
		}

		@Test
		@DisplayName("should return correct indexOf for non-monotonic data")
		void shouldReturnCorrectIndexOfForNonMonotonicData() {
			// non-monotonic data forces linear scan in CompositeIntArray
			final ArrayBitmap bitmap = new ArrayBitmap(15, 5, 10);
			assertEquals(0, bitmap.indexOf(15));
			assertEquals(1, bitmap.indexOf(5));
			assertEquals(2, bitmap.indexOf(10));
		}

		@Test
		@DisplayName("should return negative indexOf for missing element")
		void shouldReturnNegativeIndexOfForMissingElement() {
			final ArrayBitmap bitmap = new ArrayBitmap(15, 5, 10);
			assertTrue(bitmap.indexOf(99) < 0);
		}

		@Test
		@DisplayName("should return element by index")
		void shouldReturnElementByIndex() {
			final ArrayBitmap bitmap = new ArrayBitmap(5, 10, 15);
			assertEquals(5, bitmap.get(0));
			assertEquals(10, bitmap.get(1));
			assertEquals(15, bitmap.get(2));
		}

		@Test
		@DisplayName("should return correct range")
		void shouldReturnCorrectRange() {
			final ArrayBitmap bitmap = new ArrayBitmap(5, 10, 15, 20, 25);
			assertArrayEquals(new int[]{10, 15, 20}, bitmap.getRange(1, 4));
		}

		@Test
		@DisplayName("should return first element")
		void shouldReturnFirstElement() {
			final ArrayBitmap bitmap = new ArrayBitmap(5, 10, 15);
			assertEquals(5, bitmap.getFirst());
		}

		@Test
		@DisplayName("should return last element")
		void shouldReturnLastElement() {
			final ArrayBitmap bitmap = new ArrayBitmap(5, 10, 15);
			assertEquals(15, bitmap.getLast());
		}

		@Test
		@DisplayName("should return correct size")
		void shouldReturnCorrectSize() {
			final ArrayBitmap bitmap = new ArrayBitmap(5, 10, 15);
			assertEquals(3, bitmap.size());
		}

		@Test
		@DisplayName("should return isEmpty true when empty")
		void shouldReturnIsEmptyTrueWhenEmpty() {
			final ArrayBitmap bitmap = new ArrayBitmap(new CompositeIntArray());
			assertTrue(bitmap.isEmpty());
		}

		@Test
		@DisplayName("should return isEmpty false when non-empty")
		void shouldReturnIsEmptyFalseWhenNonEmpty() {
			final ArrayBitmap bitmap = new ArrayBitmap(1);
			assertFalse(bitmap.isEmpty());
		}
	}

	@Nested
	@DisplayName("Iterator")
	class IteratorTest {

		@Test
		@DisplayName("should iterate over all elements")
		void shouldIterateOverAllElements() {
			final ArrayBitmap bitmap = new ArrayBitmap(5, 10, 15);
			final OfInt it = bitmap.iterator();
			final List<Integer> result = new ArrayList<>();
			while (it.hasNext()) {
				result.add(it.nextInt());
			}
			assertEquals(List.of(5, 10, 15), result);
		}

		@Test
		@DisplayName("should iterate over empty bitmap")
		void shouldIterateOverEmptyBitmap() {
			final ArrayBitmap bitmap = new ArrayBitmap(new CompositeIntArray());
			final OfInt it = bitmap.iterator();
			assertFalse(it.hasNext());
		}
	}

	@Nested
	@DisplayName("Equals, hashCode, and toString")
	class EqualsHashCodeToStringTest {

		@Test
		@DisplayName("should be equal to identical ArrayBitmap")
		void shouldBeEqualToIdenticalArrayBitmap() {
			final ArrayBitmap bitmap1 = new ArrayBitmap(1, 2, 3);
			final ArrayBitmap bitmap2 = new ArrayBitmap(1, 2, 3);
			assertEquals(bitmap1, bitmap2);
		}

		@Test
		@DisplayName("should not be equal to different ArrayBitmap")
		void shouldNotBeEqualToDifferentArrayBitmap() {
			final ArrayBitmap bitmap1 = new ArrayBitmap(1, 2, 3);
			final ArrayBitmap bitmap2 = new ArrayBitmap(4, 5, 6);
			assertNotEquals(bitmap1, bitmap2);
		}

		@Test
		@DisplayName("should not be equal to null")
		void shouldNotBeEqualToNull() {
			final ArrayBitmap bitmap = new ArrayBitmap(1, 2, 3);
			assertNotEquals(null, bitmap);
		}

		@Test
		@DisplayName("should have consistent hashCode")
		void shouldHaveConsistentHashCode() {
			final ArrayBitmap bitmap1 = new ArrayBitmap(1, 2, 3);
			final ArrayBitmap bitmap2 = new ArrayBitmap(1, 2, 3);
			assertEquals(bitmap1.hashCode(), bitmap2.hashCode());
		}

		@Test
		@DisplayName("should produce readable toString")
		void shouldProduceReadableToString() {
			final ArrayBitmap bitmap = new ArrayBitmap(1, 2, 3);
			final String result = bitmap.toString();
			assertNotNull(result);
			assertEquals("[1, 2, 3]", result);
		}
	}
}
