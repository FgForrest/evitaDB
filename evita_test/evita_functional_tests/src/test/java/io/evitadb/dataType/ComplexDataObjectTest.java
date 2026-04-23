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

package io.evitadb.dataType;

import io.evitadb.dataType.data.DataItemArray;
import io.evitadb.dataType.data.DataItemMap;
import io.evitadb.dataType.data.DataItemValue;
import io.evitadb.dataType.data.DataItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ComplexDataObject} verifying construction,
 * isEmpty, estimateSize, equality, and toString behavior.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("ComplexDataObject functionality")
class ComplexDataObjectTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName(
			"should reject DataItemValue as root"
		)
		void shouldRejectDataItemValueAsRoot() {
			final DataItemValue value =
				new DataItemValue("test");

			assertThrows(
				IllegalArgumentException.class,
				() -> new ComplexDataObject(value)
			);
		}

		@Test
		@DisplayName(
			"should accept DataItemMap as root"
		)
		void shouldAcceptDataItemMapAsRoot() {
			final DataItemMap map = new DataItemMap(
				Collections.emptyMap()
			);
			final ComplexDataObject obj =
				new ComplexDataObject(map);

			assertEquals(map, obj.root());
		}

		@Test
		@DisplayName(
			"should accept DataItemArray as root"
		)
		void shouldAcceptDataItemArrayAsRoot() {
			final DataItemArray array = new DataItemArray(
				new DataItem[0]
			);
			final ComplexDataObject obj =
				new ComplexDataObject(array);

			assertEquals(array, obj.root());
		}
	}

	@Nested
	@DisplayName("IsEmpty")
	class IsEmptyTest {

		@Test
		@DisplayName(
			"should return true for empty DataItemMap"
		)
		void shouldReturnTrueForEmptyMap() {
			final ComplexDataObject obj =
				new ComplexDataObject(DataItemMap.EMPTY);

			assertTrue(obj.isEmpty());
		}

		@Test
		@DisplayName(
			"should return false for non-empty DataItemMap"
		)
		void shouldReturnFalseForNonEmptyMap() {
			final Map<String, DataItem> props =
				new LinkedHashMap<>();
			props.put("key", new DataItemValue("val"));
			final ComplexDataObject obj =
				new ComplexDataObject(new DataItemMap(props));

			assertFalse(obj.isEmpty());
		}

		@Test
		@DisplayName(
			"should return false for empty DataItemArray"
		)
		void shouldReturnFalseForEmptyArray() {
			final ComplexDataObject obj =
				new ComplexDataObject(
					new DataItemArray(new DataItem[0])
				);

			assertFalse(obj.isEmpty());
		}

		@Test
		@DisplayName(
			"should return false for non-empty " +
				"DataItemArray"
		)
		void shouldReturnFalseForNonEmptyArray() {
			final ComplexDataObject obj =
				new ComplexDataObject(
					new DataItemArray(
						new DataItem[]{
							new DataItemValue("a")
						}
					)
				);

			assertFalse(obj.isEmpty());
		}
	}

	@Nested
	@DisplayName("EstimateSize")
	class EstimateSizeTest {

		@Test
		@DisplayName(
			"should return positive size for " +
				"non-empty object"
		)
		void shouldReturnPositiveSizeForNonEmpty() {
			final Map<String, DataItem> props =
				new LinkedHashMap<>();
			props.put("name", new DataItemValue("test"));
			final ComplexDataObject obj =
				new ComplexDataObject(new DataItemMap(props));

			assertTrue(obj.estimateSize() > 0);
		}

		@Test
		@DisplayName(
			"should return positive size even for " +
				"empty map"
		)
		void shouldReturnPositiveSizeForEmptyMap() {
			final ComplexDataObject obj =
				new ComplexDataObject(DataItemMap.EMPTY);

			// even empty map has overhead
			assertTrue(obj.estimateSize() > 0);
		}

		@Test
		@DisplayName(
			"should return positive size for empty array"
		)
		void shouldReturnPositiveSizeForEmptyArray() {
			final ComplexDataObject obj =
				new ComplexDataObject(
					new DataItemArray(new DataItem[0])
				);

			assertTrue(obj.estimateSize() > 0);
		}
	}

	@Nested
	@DisplayName("Equality")
	class EqualityTest {

		@Test
		@DisplayName("should be equal for same root")
		void shouldBeEqualForSameRoot() {
			final Map<String, DataItem> props =
				new LinkedHashMap<>();
			props.put("key", new DataItemValue("val"));

			final ComplexDataObject obj1 =
				new ComplexDataObject(new DataItemMap(props));
			final ComplexDataObject obj2 =
				new ComplexDataObject(new DataItemMap(props));

			assertEquals(obj1, obj2);
			assertEquals(obj1.hashCode(), obj2.hashCode());
		}

		@Test
		@DisplayName(
			"should not be equal for different roots"
		)
		void shouldNotBeEqualForDifferentRoots() {
			final Map<String, DataItem> props1 =
				new LinkedHashMap<>();
			props1.put("key", new DataItemValue("val1"));

			final Map<String, DataItem> props2 =
				new LinkedHashMap<>();
			props2.put("key", new DataItemValue("val2"));

			final ComplexDataObject obj1 =
				new ComplexDataObject(new DataItemMap(props1));
			final ComplexDataObject obj2 =
				new ComplexDataObject(new DataItemMap(props2));

			assertNotEquals(obj1, obj2);
		}

		@Test
		@DisplayName("should be reflexive")
		void shouldBeReflexive() {
			final ComplexDataObject obj =
				new ComplexDataObject(DataItemMap.EMPTY);

			assertEquals(obj, obj);
		}

		@Test
		@DisplayName("should not be equal to null")
		void shouldNotBeEqualToNull() {
			final ComplexDataObject obj =
				new ComplexDataObject(DataItemMap.EMPTY);

			assertNotEquals(null, obj);
		}

		@Test
		@DisplayName(
			"should not be equal to different type"
		)
		void shouldNotBeEqualToDifferentType() {
			final ComplexDataObject obj =
				new ComplexDataObject(DataItemMap.EMPTY);

			assertNotEquals("not a ComplexDataObject", obj);
		}
	}

	@Nested
	@DisplayName("ToString")
	class ToStringTest {

		@Test
		@DisplayName(
			"should return empty braces for empty map"
		)
		void shouldReturnEmptyBracesForEmptyMap() {
			final ComplexDataObject obj =
				new ComplexDataObject(DataItemMap.EMPTY);

			assertEquals("{}", obj.toString());
		}

		@Test
		@DisplayName(
			"should format non-empty map with indentation"
		)
		void shouldFormatNonEmptyMap() {
			final Map<String, DataItem> props =
				new LinkedHashMap<>();
			props.put("name", new DataItemValue("hello"));
			final ComplexDataObject obj =
				new ComplexDataObject(new DataItemMap(props));

			final String result = obj.toString();
			assertTrue(result.startsWith("{"));
			assertTrue(result.endsWith("}"));
			assertTrue(result.contains("'name'"));
			assertTrue(result.contains("'hello'"));
		}

		@Test
		@DisplayName(
			"should return empty brackets for empty array"
		)
		void shouldReturnEmptyBracketsForEmptyArray() {
			final ComplexDataObject obj =
				new ComplexDataObject(
					new DataItemArray(new DataItem[0])
				);

			assertEquals("[]", obj.toString());
		}

		@Test
		@DisplayName(
			"should format non-empty array with items"
		)
		void shouldFormatNonEmptyArray() {
			final ComplexDataObject obj =
				new ComplexDataObject(
					new DataItemArray(
						new DataItem[]{
							new DataItemValue("first"),
							new DataItemValue("second")
						}
					)
				);

			final String result = obj.toString();
			assertTrue(result.startsWith("["));
			assertTrue(result.endsWith("]"));
			assertTrue(result.contains("'first'"));
			assertTrue(result.contains("'second'"));
		}
	}
}
