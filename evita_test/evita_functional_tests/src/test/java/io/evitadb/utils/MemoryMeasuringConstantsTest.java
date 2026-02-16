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
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test verifies contract of {@link MemoryMeasuringConstants} interface.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("MemoryMeasuringConstants contract tests")
class MemoryMeasuringConstantsTest {

	@Nested
	@DisplayName("Constants tests")
	class ConstantsTests {

		@Test
		@DisplayName("Should have correct object header size")
		void shouldHaveCorrectObjectHeaderSize() {
			assertEquals(16, MemoryMeasuringConstants.OBJECT_HEADER_SIZE);
		}

		@Test
		@DisplayName("Should have correct reference size")
		void shouldHaveCorrectReferenceSize() {
			assertEquals(8, MemoryMeasuringConstants.REFERENCE_SIZE);
		}

		@Test
		@DisplayName("Should have correct array base size")
		void shouldHaveCorrectArrayBaseSize() {
			assertEquals(24, MemoryMeasuringConstants.ARRAY_BASE_SIZE);
		}

		@Test
		@DisplayName("Should have correct byte size")
		void shouldHaveCorrectByteSize() {
			assertEquals(1, MemoryMeasuringConstants.BYTE_SIZE);
		}

		@Test
		@DisplayName("Should have correct char size")
		void shouldHaveCorrectCharSize() {
			assertEquals(2, MemoryMeasuringConstants.CHAR_SIZE);
		}

		@Test
		@DisplayName("Should have correct small size")
		void shouldHaveCorrectSmallSize() {
			assertEquals(2, MemoryMeasuringConstants.SMALL_SIZE);
		}

		@Test
		@DisplayName("Should have correct int size")
		void shouldHaveCorrectIntSize() {
			assertEquals(4, MemoryMeasuringConstants.INT_SIZE);
		}

		@Test
		@DisplayName("Should have correct long size")
		void shouldHaveCorrectLongSize() {
			assertEquals(8, MemoryMeasuringConstants.LONG_SIZE);
		}

		@Test
		@DisplayName("Should have positive BigDecimal size")
		void shouldHavePositiveBigDecimalSize() {
			assertTrue(MemoryMeasuringConstants.BIG_DECIMAL_SIZE > 0);
		}

		@Test
		@DisplayName("Should have positive date time sizes")
		void shouldHavePositiveDateTimeSizes() {
			assertTrue(MemoryMeasuringConstants.LOCAL_DATE_TIME_SIZE > 0);
			assertTrue(MemoryMeasuringConstants.LOCAL_DATE_SIZE > 0);
			assertTrue(MemoryMeasuringConstants.LOCAL_TIME_SIZE > 0);
		}
	}

	@Nested
	@DisplayName("String size computation tests")
	class StringSizeComputationTests {

		@Test
		@DisplayName("Should compute string size for empty string")
		void shouldComputeStringSizeForEmptyString() {
			final int size = MemoryMeasuringConstants.computeStringSize("");
			assertTrue(size > 0, "Even empty string should have some memory footprint");
		}

		@Test
		@DisplayName("Should compute string size for short string")
		void shouldComputeStringSizeForShortString() {
			final int size = MemoryMeasuringConstants.computeStringSize("test");
			assertTrue(size > MemoryMeasuringConstants.computeStringSize(""), "Longer string should take more memory");
		}

		@Test
		@DisplayName("Should compute string size proportional to length")
		void shouldComputeStringSizeProportionalToLength() {
			final int shortSize = MemoryMeasuringConstants.computeStringSize("abc");
			final int longSize = MemoryMeasuringConstants.computeStringSize("abcdefghij");
			assertTrue(longSize > shortSize, "Longer string should take more memory");
		}

		@Test
		@DisplayName("Should return aligned size")
		void shouldReturnAlignedSize() {
			final int size = MemoryMeasuringConstants.computeStringSize("test");
			assertEquals(0, size % 8, "Size should be aligned to 8 bytes");
		}
	}

	@Nested
	@DisplayName("Array size computation tests")
	class ArraySizeComputationTests {

		@Test
		@DisplayName("Should compute array size for empty array")
		void shouldComputeArraySizeForEmptyArray() {
			final int size = MemoryMeasuringConstants.computeArraySize(new Serializable[0]);
			assertEquals(MemoryMeasuringConstants.ARRAY_BASE_SIZE, size);
		}

		@Test
		@DisplayName("Should compute array size for non-empty array")
		void shouldComputeArraySizeForNonEmptyArray() {
			final Serializable[] array = new Serializable[]{"a", "b", "c"};
			final int size = MemoryMeasuringConstants.computeArraySize(array);
			assertTrue(size > MemoryMeasuringConstants.ARRAY_BASE_SIZE, "Non-empty array should be larger than base size");
		}

		@Test
		@DisplayName("Should compute int array size")
		void shouldComputeIntArraySize() {
			final int[] array = new int[]{1, 2, 3, 4, 5};
			final int size = MemoryMeasuringConstants.computeArraySize(array);
			final int expectedMinimum = MemoryMeasuringConstants.ARRAY_BASE_SIZE +
				5 * MemoryMeasuringConstants.REFERENCE_SIZE +
				5 * MemoryMeasuringConstants.INT_SIZE;
			assertEquals(expectedMinimum, size);
		}

		@Test
		@DisplayName("Should compute larger size for larger arrays")
		void shouldComputeLargerSizeForLargerArrays() {
			final int[] smallArray = new int[]{1, 2};
			final int[] largeArray = new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
			final int smallSize = MemoryMeasuringConstants.computeArraySize(smallArray);
			final int largeSize = MemoryMeasuringConstants.computeArraySize(largeArray);
			assertTrue(largeSize > smallSize, "Larger array should take more memory");
		}
	}

	@Nested
	@DisplayName("HashMap size computation tests")
	class HashMapSizeComputationTests {

		@Test
		@DisplayName("Should compute HashMap size for empty map")
		void shouldComputeHashMapSizeForEmptyMap() {
			final Map<Serializable, Serializable> map = new HashMap<>();
			final int size = MemoryMeasuringConstants.computeHashMapSize(map);
			// Base size for empty HashMap is 128
			assertEquals(128, size);
		}

		@Test
		@DisplayName("Should compute HashMap size for non-empty map")
		void shouldComputeHashMapSizeForNonEmptyMap() {
			final Map<Serializable, Serializable> map = new HashMap<>();
			map.put("key1", "value1");
			map.put("key2", "value2");
			final int size = MemoryMeasuringConstants.computeHashMapSize(map);
			assertTrue(size > 128, "Non-empty map should be larger than base size");
		}

		@Test
		@DisplayName("Should compute larger size for larger maps")
		void shouldComputeLargerSizeForLargerMaps() {
			final Map<Serializable, Serializable> smallMap = new HashMap<>();
			smallMap.put("key1", "value1");

			final Map<Serializable, Serializable> largeMap = new HashMap<>();
			for (int i = 0; i < 10; i++) {
				largeMap.put("key" + i, "value" + i);
			}

			final int smallSize = MemoryMeasuringConstants.computeHashMapSize(smallMap);
			final int largeSize = MemoryMeasuringConstants.computeHashMapSize(largeMap);
			assertTrue(largeSize > smallSize, "Larger map should take more memory");
		}
	}

	@Nested
	@DisplayName("LinkedList size computation tests")
	class LinkedListSizeComputationTests {

		@Test
		@DisplayName("Should compute LinkedList size for empty list")
		void shouldComputeLinkedListSizeForEmptyList() {
			final List<Serializable> list = new LinkedList<>();
			final int size = MemoryMeasuringConstants.computeLinkedListSize(list);
			// Base size for empty LinkedList is 48
			assertEquals(48, size);
		}

		@Test
		@DisplayName("Should compute LinkedList size for non-empty list")
		void shouldComputeLinkedListSizeForNonEmptyList() {
			final List<Serializable> list = new LinkedList<>();
			list.add("element1");
			list.add("element2");
			final int size = MemoryMeasuringConstants.computeLinkedListSize(list);
			assertTrue(size > 48, "Non-empty list should be larger than base size");
		}
	}

	@Nested
	@DisplayName("Element size tests")
	class ElementSizeTests {

		@Test
		@DisplayName("Should return byte size for byte type")
		void shouldReturnByteSizeForByteType() {
			final int size = MemoryMeasuringConstants.getElementSize(byte.class);
			assertEquals(MemoryMeasuringConstants.BYTE_SIZE, size);
		}

		@Test
		@DisplayName("Should return reference size for object types")
		void shouldReturnReferenceSizeForObjectTypes() {
			final int size = MemoryMeasuringConstants.getElementSize(String.class);
			assertEquals(MemoryMeasuringConstants.REFERENCE_SIZE, size);
		}
	}
}
