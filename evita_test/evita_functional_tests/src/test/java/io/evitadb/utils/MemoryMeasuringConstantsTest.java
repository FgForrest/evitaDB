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
import org.openjdk.jol.vm.VM;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.DATA_TYPE;

/**
 * Test verifies contract of {@link MemoryMeasuringConstants} interface.
 *
 * **Expectations are measured with JOL on every run, not remembered.** See {@link JolHeapSize} for why: deriving
 * the expectation from the same constants the implementation uses - `assertEquals(OBJECT_HEADER_SIZE + INT_SIZE,
 * estimateSize(1))` - is what this test used to do, and it is why an `int[]` estimate that was **6x** too large
 * survived here unnoticed. A test that restates the implementation passes for every formula, including a wrong one.
 *
 * **Do not replace a JOL assertion with a literal.** A literal detects change but says nothing about correctness,
 * and the usual response to its failure is to overwrite it. The one deliberate exception is
 * {@link MemoryMeasuringConstants#computeStringSize(String)}, which assumes UTF-16 rather than scanning for a
 * Latin-1 encoding, and therefore cannot equal JOL for an ASCII string - that divergence is a decision, so its
 * tests assert the properties that survive it rather than a measured size.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("MemoryMeasuringConstants contract tests")
@Tag(ENGINE)
@Tag(DATA_TYPE)
class MemoryMeasuringConstantsTest {

	@Nested
	@DisplayName("Constants tests")
	class ConstantsTests {

		@Test
		@DisplayName("Should run under the compressed layout every other expectation assumes")
		void shouldRunUnderCompressedLayout() {
			// stated first and on its own so a run on an unusual VM reports THIS instead of a dozen numeric
			// mismatches that give the reader no clue why they all moved at once
			assertEquals(
				VMLayout.COMPRESSED, VMLayout.current(),
				"The absolute sizes asserted here were measured under compressed references and compressed class " +
					"pointers, which the VM applies below ~32 GB of heap. This VM reports " + VMLayout.current() +
					" - re-measure with JOL before trusting any failure below."
			);
		}

		@Test
		@DisplayName("Should have correct object header size")
		void shouldHaveCorrectObjectHeaderSize() {
			assertEquals(VM.current().objectHeaderSize(), MemoryMeasuringConstants.OBJECT_HEADER_SIZE);
		}

		@Test
		@DisplayName("Should have correct reference size")
		void shouldHaveCorrectReferenceSize() {
			// measured as what one extra reference slot costs in an array - NOT `VM.addressSize()`, which reports
			// the 64-bit native word and stays 8 even while compressed oops make a reference 4
			assertEquals(JolHeapSize.referenceSize(), MemoryMeasuringConstants.REFERENCE_SIZE);
		}

		@Test
		@DisplayName("Should have correct array base size")
		void shouldHaveCorrectArrayBaseSize() {
			assertEquals(VM.current().arrayHeaderSize(), MemoryMeasuringConstants.ARRAY_BASE_SIZE);
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
		@DisplayName("Should measure a whole BigDecimal object, header and padding included")
		void shouldMeasureAWholeBigDecimalObject() {
			// a compact decimal - the magnitude fits `intCompact`, so no `BigInteger` is allocated and the shallow
			// size IS the whole object. That is the case `BIG_DECIMAL_WHOLE_SIZE` describes
			final BigDecimal compact = BigDecimal.valueOf(12345L, 2);
			assertEquals(
				JolHeapSize.shallowSize(compact),
				MemoryMeasuringConstants.BIG_DECIMAL_WHOLE_SIZE
			);
		}

		@Test
		@DisplayName("Should keep the payload constant strictly smaller than the whole-object one")
		void shouldKeepThePayloadConstantSmallerThanTheWholeOne() {
			// the two are separate constants precisely because they were once one, and every structure that OWNS a
			// `BigDecimal` charged the payload alone - under-reporting itself by a header plus its padding. This
			// pins the gap so the two can never be silently collapsed back together
			assertTrue(MemoryMeasuringConstants.BIG_DECIMAL_SIZE > 0);
			assertTrue(
				MemoryMeasuringConstants.BIG_DECIMAL_WHOLE_SIZE > MemoryMeasuringConstants.BIG_DECIMAL_SIZE,
				"the whole size must exceed the payload by at least the object header"
			);
			assertEquals(
				MemoryMeasuringConstants.align(
					MemoryMeasuringConstants.OBJECT_HEADER_SIZE + MemoryMeasuringConstants.BIG_DECIMAL_SIZE
				),
				MemoryMeasuringConstants.BIG_DECIMAL_WHOLE_SIZE
			);
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
			// a primitive array stores its values inline and holds NO references - the previous expectation charged
			// a reference AND an int per element, which is where the 3x over-estimate came from
			final int[] array = new int[]{1, 2, 3, 4, 5};
			assertEquals(JolHeapSize.shallowSize(array), MemoryMeasuringConstants.computeArraySize(array));
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
			// the bucket table is allocated lazily on the FIRST PUT, so an empty map genuinely has none - the
			// previous expectation of 128 charged it for a phantom 16-slot table.
			// Ground truth is taken BEFORE the estimate, because `computeHashMapSize` walks `entrySet()` and that
			// materializes and caches the map's EntrySet view, adding ~16 bytes to a map that never had one
			final Map<Serializable, Serializable> map = new HashMap<>();
			final long expected = JolHeapSize.ownedSize(map);
			assertEquals(expected, MemoryMeasuringConstants.computeHashMapSize(map));
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
			// `modCount` comes from AbstractList and is easy to miss - leaving it out yields 24 instead of 32
			final List<Serializable> list = new LinkedList<>();
			final long expected = JolHeapSize.ownedSize(list);
			assertEquals(expected, MemoryMeasuringConstants.computeLinkedListSize(list));
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
