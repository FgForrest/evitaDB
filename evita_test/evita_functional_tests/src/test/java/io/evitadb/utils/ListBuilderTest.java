/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

import java.util.List;
import java.util.Map;

import static io.evitadb.utils.ListBuilder.array;
import static io.evitadb.utils.ListBuilder.list;
import static io.evitadb.utils.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test verifies contract of {@link ListBuilder} class.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ListBuilder contract tests")
class ListBuilderTest {

	@Nested
	@DisplayName("List output tests")
	class ListOutputTests {

		@Test
		@DisplayName("Should create empty list")
		void shouldCreateEmptyList() {
			final Object result = list().build();
			assertTrue(result instanceof List);
			@SuppressWarnings("unchecked")
			final List<Object> listResult = (List<Object>) result;
			assertTrue(listResult.isEmpty());
		}

		@Test
		@DisplayName("Should create list with elements")
		void shouldCreateListWithElements() {
			final Object result = list()
				.i("first")
				.i(42)
				.i(true)
				.build();

			assertTrue(result instanceof List);
			@SuppressWarnings("unchecked")
			final List<Object> listResult = (List<Object>) result;
			assertEquals(3, listResult.size());
			assertEquals("first", listResult.get(0));
			assertEquals(42, listResult.get(1));
			assertEquals(true, listResult.get(2));
		}

		@Test
		@DisplayName("Should return unmodifiable list")
		void shouldReturnUnmodifiableList() {
			final Object result = list()
				.i("element")
				.build();

			@SuppressWarnings("unchecked")
			final List<Object> listResult = (List<Object>) result;
			assertThrows(UnsupportedOperationException.class, () -> listResult.add("newElement"));
		}

		@Test
		@DisplayName("Should preserve element order")
		void shouldPreserveElementOrder() {
			final Object result = list()
				.i("first")
				.i("second")
				.i("third")
				.build();

			@SuppressWarnings("unchecked")
			final List<Object> listResult = (List<Object>) result;
			assertEquals("first", listResult.get(0));
			assertEquals("second", listResult.get(1));
			assertEquals("third", listResult.get(2));
		}
	}

	@Nested
	@DisplayName("Array output tests")
	class ArrayOutputTests {

		@Test
		@DisplayName("Should create empty array")
		void shouldCreateEmptyArray() {
			final Object result = array().build();
			assertTrue(result instanceof Object[]);
			final Object[] arrayResult = (Object[]) result;
			assertEquals(0, arrayResult.length);
		}

		@Test
		@DisplayName("Should create array with elements")
		void shouldCreateArrayWithElements() {
			final Object result = array()
				.i("first")
				.i(42)
				.i(true)
				.build();

			assertTrue(result instanceof Object[]);
			final Object[] arrayResult = (Object[]) result;
			assertEquals(3, arrayResult.length);
			assertArrayEquals(new Object[]{"first", 42, true}, arrayResult);
		}

		@Test
		@DisplayName("Should preserve element order in array")
		void shouldPreserveElementOrderInArray() {
			final Object result = array()
				.i(1)
				.i(2)
				.i(3)
				.build();

			final Object[] arrayResult = (Object[]) result;
			assertArrayEquals(new Object[]{1, 2, 3}, arrayResult);
		}
	}

	@Nested
	@DisplayName("Nested builder tests")
	class NestedBuilderTests {

		@Test
		@DisplayName("Should flatten nested MapBuilder")
		void shouldFlattenNestedMapBuilder() {
			final Object result = list()
				.i(map()
					.e("key", "value"))
				.build();

			@SuppressWarnings("unchecked")
			final List<Object> listResult = (List<Object>) result;
			assertEquals(1, listResult.size());
			assertTrue(listResult.get(0) instanceof Map);
			@SuppressWarnings("unchecked")
			final Map<String, Object> mapElement = (Map<String, Object>) listResult.get(0);
			assertEquals("value", mapElement.get("key"));
		}

		@Test
		@DisplayName("Should flatten nested ListBuilder")
		void shouldFlattenNestedListBuilder() {
			final Object result = list()
				.i(list()
					.i("nested1")
					.i("nested2"))
				.build();

			@SuppressWarnings("unchecked")
			final List<Object> listResult = (List<Object>) result;
			assertEquals(1, listResult.size());
			assertTrue(listResult.get(0) instanceof List);
			@SuppressWarnings("unchecked")
			final List<Object> nestedList = (List<Object>) listResult.get(0);
			assertEquals(2, nestedList.size());
			assertEquals("nested1", nestedList.get(0));
			assertEquals("nested2", nestedList.get(1));
		}

		@Test
		@DisplayName("Should handle mixed nested builders")
		void shouldHandleMixedNestedBuilders() {
			final Object result = list()
				.i("plain")
				.i(map().e("mapKey", "mapValue"))
				.i(list().i("nestedItem"))
				.build();

			@SuppressWarnings("unchecked")
			final List<Object> listResult = (List<Object>) result;
			assertEquals(3, listResult.size());
			assertEquals("plain", listResult.get(0));
			assertTrue(listResult.get(1) instanceof Map);
			assertTrue(listResult.get(2) instanceof List);
		}

		@Test
		@DisplayName("Should handle nested builders in array output")
		void shouldHandleNestedBuildersInArrayOutput() {
			final Object result = array()
				.i(map().e("key", "value"))
				.i(list().i("item"))
				.build();

			final Object[] arrayResult = (Object[]) result;
			assertEquals(2, arrayResult.length);
			assertTrue(arrayResult[0] instanceof Map);
			assertTrue(arrayResult[1] instanceof List);
		}
	}
}
