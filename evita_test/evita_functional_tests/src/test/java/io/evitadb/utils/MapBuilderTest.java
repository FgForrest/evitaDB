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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.evitadb.utils.MapBuilder.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test verifies contract of {@link MapBuilder} class.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("MapBuilder contract tests")
class MapBuilderTest {

	@Nested
	@DisplayName("Basic operations tests")
	class BasicOperationsTests {

		@Test
		@DisplayName("Should create empty map")
		void shouldCreateEmptyMap() {
			final Map<String, Object> result = map().build();
			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("Should create map with entries")
		void shouldCreateMapWithEntries() {
			final Map<String, Object> result = map()
				.e("key1", "value1")
				.e("key2", 42)
				.e("key3", true)
				.build();

			assertEquals(3, result.size());
			assertEquals("value1", result.get("key1"));
			assertEquals(42, result.get("key2"));
			assertEquals(true, result.get("key3"));
		}

		@Test
		@DisplayName("Should return unmodifiable map")
		void shouldReturnUnmodifiableMap() {
			final Map<String, Object> result = map()
				.e("key", "value")
				.build();

			assertThrows(UnsupportedOperationException.class, () -> result.put("newKey", "newValue"));
		}

		@Test
		@DisplayName("Should preserve insertion order")
		void shouldPreserveInsertionOrder() {
			final Map<String, Object> result = map()
				.e("first", 1)
				.e("second", 2)
				.e("third", 3)
				.build();

			final List<String> keys = new ArrayList<>(result.keySet());
			assertEquals("first", keys.get(0));
			assertEquals("second", keys.get(1));
			assertEquals("third", keys.get(2));
		}

		@Test
		@DisplayName("Should handle null values")
		void shouldHandleNullValues() {
			final Map<String, Object> result = map()
				.e("key", null)
				.build();

			assertTrue(result.containsKey("key"));
			assertNull(result.get("key"));
		}
	}

	@Nested
	@DisplayName("Accessor tests")
	class AccessorTests {

		@Test
		@DisplayName("Should get value for existing key")
		void shouldGetValueForExistingKey() {
			final MapBuilder builder = map().e("key", "value");
			assertEquals("value", builder.get("key"));
		}

		@Test
		@DisplayName("Should return null for non-existing key")
		void shouldReturnNullForNonExistingKey() {
			final MapBuilder builder = map().e("key", "value");
			assertNull(builder.get("nonExisting"));
		}

		@Test
		@DisplayName("Should return true when key exists")
		void shouldReturnTrueWhenKeyExists() {
			final MapBuilder builder = map().e("key", "value");
			assertTrue(builder.containsKey("key"));
		}

		@Test
		@DisplayName("Should return false when key does not exist")
		void shouldReturnFalseWhenKeyDoesNotExist() {
			final MapBuilder builder = map().e("key", "value");
			assertFalse(builder.containsKey("nonExisting"));
		}

		@Test
		@DisplayName("Should return true for empty map")
		void shouldReturnTrueForEmptyMap() {
			final MapBuilder builder = map();
			assertTrue(builder.isEmpty());
		}

		@Test
		@DisplayName("Should return false for non-empty map")
		void shouldReturnFalseForNonEmptyMap() {
			final MapBuilder builder = map().e("key", "value");
			assertFalse(builder.isEmpty());
		}
	}

	@Nested
	@DisplayName("Nested builder tests")
	class NestedBuilderTests {

		@Test
		@DisplayName("Should flatten nested MapBuilder")
		void shouldFlattenNestedMapBuilder() {
			final Map<String, Object> result = map()
				.e("outer", map()
					.e("inner", "value"))
				.build();

			assertEquals(1, result.size());
			assertTrue(result.get("outer") instanceof Map);
			@SuppressWarnings("unchecked")
			final Map<String, Object> inner = (Map<String, Object>) result.get("outer");
			assertEquals("value", inner.get("inner"));
		}

		@Test
		@DisplayName("Should flatten nested ListBuilder")
		void shouldFlattenNestedListBuilder() {
			final Map<String, Object> result = map()
				.e("list", ListBuilder.list()
					.i("item1")
					.i("item2"))
				.build();

			assertEquals(1, result.size());
			assertTrue(result.get("list") instanceof List);
			@SuppressWarnings("unchecked")
			final List<Object> list = (List<Object>) result.get("list");
			assertEquals(2, list.size());
			assertEquals("item1", list.get(0));
			assertEquals("item2", list.get(1));
		}

		@Test
		@DisplayName("Should handle deeply nested builders")
		void shouldHandleDeeplyNestedBuilders() {
			final Map<String, Object> result = map()
				.e("level1", map()
					.e("level2", map()
						.e("level3", "deep value")))
				.build();

			@SuppressWarnings("unchecked")
			final Map<String, Object> level1 = (Map<String, Object>) result.get("level1");
			@SuppressWarnings("unchecked")
			final Map<String, Object> level2 = (Map<String, Object>) level1.get("level2");
			assertEquals("deep value", level2.get("level3"));
		}
	}
}
