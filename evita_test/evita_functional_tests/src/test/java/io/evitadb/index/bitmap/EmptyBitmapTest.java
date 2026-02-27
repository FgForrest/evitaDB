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

import java.util.PrimitiveIterator.OfInt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link EmptyBitmap}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("EmptyBitmap")
class EmptyBitmapTest {

	@Nested
	@DisplayName("Singleton instance")
	class SingletonInstanceTest {

		@Test
		@DisplayName("should be same instance on multiple accesses")
		void shouldBeSameInstanceOnMultipleAccesses() {
			assertSame(EmptyBitmap.INSTANCE, EmptyBitmap.INSTANCE);
		}

		@Test
		@DisplayName("should not be null")
		void shouldNotBeNull() {
			assertNotNull(EmptyBitmap.INSTANCE);
		}
	}

	@Nested
	@DisplayName("Read operations")
	class ReadOperationsTest {

		@Test
		@DisplayName("should always be empty")
		void shouldAlwaysBeEmpty() {
			assertTrue(EmptyBitmap.INSTANCE.isEmpty());
		}

		@Test
		@DisplayName("should always return size zero")
		void shouldAlwaysReturnSizeZero() {
			assertEquals(0, EmptyBitmap.INSTANCE.size());
		}

		@Test
		@DisplayName("should never contain any element")
		void shouldNeverContainAnyElement() {
			assertFalse(EmptyBitmap.INSTANCE.contains(0));
			assertFalse(EmptyBitmap.INSTANCE.contains(1));
			assertFalse(EmptyBitmap.INSTANCE.contains(-1));
			assertFalse(EmptyBitmap.INSTANCE.contains(Integer.MAX_VALUE));
		}

		@Test
		@DisplayName("should return negative indexOf for any element")
		void shouldReturnNegativeIndexOfForAnyElement() {
			assertEquals(-1, EmptyBitmap.INSTANCE.indexOf(0));
			assertEquals(-1, EmptyBitmap.INSTANCE.indexOf(1));
			assertEquals(-1, EmptyBitmap.INSTANCE.indexOf(42));
		}

		@Test
		@DisplayName("should throw on get")
		void shouldThrowOnGet() {
			assertThrows(IndexOutOfBoundsException.class, () -> EmptyBitmap.INSTANCE.get(0));
		}

		@Test
		@DisplayName("should throw on getRange")
		void shouldThrowOnGetRange() {
			assertThrows(IndexOutOfBoundsException.class, () -> EmptyBitmap.INSTANCE.getRange(0, 1));
		}

		@Test
		@DisplayName("should throw on getFirst")
		void shouldThrowOnGetFirst() {
			assertThrows(IndexOutOfBoundsException.class, () -> EmptyBitmap.INSTANCE.getFirst());
		}

		@Test
		@DisplayName("should throw on getLast")
		void shouldThrowOnGetLast() {
			assertThrows(IndexOutOfBoundsException.class, () -> EmptyBitmap.INSTANCE.getLast());
		}

		@Test
		@DisplayName("should return empty array")
		void shouldReturnEmptyArray() {
			assertArrayEquals(new int[0], EmptyBitmap.INSTANCE.getArray());
		}
	}

	@Nested
	@DisplayName("Iterator")
	class IteratorTest {

		@Test
		@DisplayName("should return non-null iterator")
		void shouldReturnEmptyIterator() {
			final OfInt iterator = EmptyBitmap.INSTANCE.iterator();
			assertNotNull(iterator);
		}

		@Test
		@DisplayName("should not have next on iterator")
		void shouldNotHaveNextOnIterator() {
			final OfInt iterator = EmptyBitmap.INSTANCE.iterator();
			assertFalse(iterator.hasNext());
		}
	}

	@Nested
	@DisplayName("Mutation operations throw UnsupportedOperationException")
	class MutationOperationsTest {

		@Test
		@DisplayName("should throw on add")
		void shouldThrowOnAdd() {
			assertThrows(UnsupportedOperationException.class, () -> EmptyBitmap.INSTANCE.add(1));
		}

		@Test
		@DisplayName("should throw on addAll varargs")
		void shouldThrowOnAddAllVarargs() {
			assertThrows(UnsupportedOperationException.class, () -> EmptyBitmap.INSTANCE.addAll(1, 2, 3));
		}

		@Test
		@DisplayName("should throw on addAll bitmap")
		void shouldThrowOnAddAllBitmap() {
			final Bitmap other = new BaseBitmap(1, 2, 3);
			assertThrows(UnsupportedOperationException.class, () -> EmptyBitmap.INSTANCE.addAll(other));
		}

		@Test
		@DisplayName("should throw on remove")
		void shouldThrowOnRemove() {
			assertThrows(UnsupportedOperationException.class, () -> EmptyBitmap.INSTANCE.remove(1));
		}

		@Test
		@DisplayName("should throw on removeAll varargs")
		void shouldThrowOnRemoveAllVarargs() {
			assertThrows(UnsupportedOperationException.class, () -> EmptyBitmap.INSTANCE.removeAll(1, 2));
		}

		@Test
		@DisplayName("should throw on removeAll bitmap")
		void shouldThrowOnRemoveAllBitmap() {
			final Bitmap other = new BaseBitmap(1, 2, 3);
			assertThrows(UnsupportedOperationException.class, () -> EmptyBitmap.INSTANCE.removeAll(other));
		}
	}

	@Nested
	@DisplayName("toString")
	class ToStringTest {

		@Test
		@DisplayName("should return EMPTY string")
		void shouldReturnEmptyString() {
			assertEquals("EMPTY", EmptyBitmap.INSTANCE.toString());
		}
	}
}
