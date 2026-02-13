/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.dataType.map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LazyHashMap} verifying that it correctly implements
 * the {@link Map} contract with lazy initialization of the underlying
 * {@link HashMap} delegate.
 *
 * @author evitaDB
 */
@DisplayName("LazyHashMap basic Map contract tests")
class LazyHashMapDelegateTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("New map should be empty with zero size")
		void shouldBeEmptyOnCreation() {
			final LazyHashMap<String, Integer> map =
				new LazyHashMap<>(4);

			assertEquals(0, map.size());
			assertTrue(map.isEmpty());
		}
	}

	@Nested
	@DisplayName("Core map operations")
	class CoreOperationsTest {

		@Test
		@DisplayName("Put and get entries including updates")
		void shouldPutAndGetEntries() {
			final LazyHashMap<String, Integer> map =
				new LazyHashMap<>(2);

			assertNull(map.put("a", 1));
			assertEquals(1, map.size());
			assertEquals(1, map.get("a"));

			// update existing key
			assertEquals(1, map.put("a", 2));
			assertEquals(2, map.get("a"));
			assertEquals(1, map.size());
		}

		@Test
		@DisplayName(
			"ContainsKey and containsValue check presence correctly"
		)
		void shouldSupportContainsKeyAndValue() {
			final LazyHashMap<String, Integer> map =
				new LazyHashMap<>(1);

			map.put("x", 42);

			assertTrue(map.containsKey("x"));
			assertFalse(map.containsKey("y"));
			assertTrue(map.containsValue(42));
			assertFalse(map.containsValue(43));
		}

		@Test
		@DisplayName("Remove entries by key and conditional remove")
		void shouldRemoveEntries() {
			final LazyHashMap<String, Integer> map =
				new LazyHashMap<>(3);
			map.put("a", 1);
			map.put("b", 2);
			map.put("c", 3);

			assertEquals(2, map.remove("b"));
			assertNull(map.get("b"));
			assertEquals(2, map.size());

			// conditional remove
			assertFalse(map.remove("a", 2));
			assertTrue(map.remove("a", 1));
			assertFalse(map.containsKey("a"));
		}

		@Test
		@DisplayName("Clear empties the map")
		void shouldClearMap() {
			final LazyHashMap<String, Integer> map =
				new LazyHashMap<>(2);
			map.put("a", 1);
			map.put("b", 2);

			map.clear();

			assertTrue(map.isEmpty());
			assertEquals(0, map.size());
		}

		@Test
		@DisplayName("PutAll copies entries from another map")
		void shouldPutAllFromAnotherMap() {
			final LazyHashMap<String, Integer> map =
				new LazyHashMap<>(2);
			final Map<String, Integer> other = new HashMap<>();
			other.put("a", 1);
			other.put("b", 2);

			map.putAll(other);

			assertEquals(2, map.size());
			assertEquals(1, map.get("a"));
			assertEquals(2, map.get("b"));
		}
	}

	@Nested
	@DisplayName("View operations")
	class ViewOperationsTest {

		@Test
		@DisplayName(
			"keySet, values, and entrySet reflect map contents"
		)
		void shouldExposeViewsKeySetValuesEntrySet() {
			final LazyHashMap<String, Integer> map =
				new LazyHashMap<>(4);
			map.put("a", 1);
			map.put("b", 2);

			final Set<String> keys = map.keySet();
			final Collection<Integer> values = map.values();
			final Set<Map.Entry<String, Integer>> entries =
				map.entrySet();

			assertEquals(
				new HashSet<>(Arrays.asList("a", "b")),
				new HashSet<>(keys)
			);
			assertEquals(
				new HashSet<>(Arrays.asList(1, 2)),
				new HashSet<>(values)
			);
			assertEquals(2, entries.size());

			// views reflect changes
			map.put("c", 3);
			assertTrue(keys.contains("c"));
			assertTrue(values.contains(3));
			assertEquals(3, entries.size());
		}
	}

	@Nested
	@DisplayName("Compute and merge operations")
	class ComputeAndMergeTest {

		@Test
		@DisplayName(
			"computeIfAbsent, computeIfPresent, merge, "
				+ "replace, and putIfAbsent"
		)
		void shouldSupportComputeReplaceMerge() {
			final LazyHashMap<String, Integer> map =
				new LazyHashMap<>(2);

			assertEquals(
				10,
				map.computeIfAbsent("x", k -> 10)
			);
			assertEquals(10, map.get("x"));

			assertEquals(
				11,
				map.computeIfPresent("x", (k, v) -> v + 1)
			);
			assertEquals(11, map.get("x"));

			assertEquals(
				20,
				map.merge("x", 9, Integer::sum)
			);
			assertEquals(20, map.get("x"));

			assertNull(map.replace("y", 1));
			assertNull(map.get("y"));
			assertNull(map.putIfAbsent("y", 5));
			assertEquals(5, map.get("y"));
			assertTrue(map.replace("y", 5, 6));
			assertEquals(6, map.get("y"));
		}
	}

	@Nested
	@DisplayName("Equality and hashing")
	class EqualityAndHashingTest {

		@Test
		@DisplayName(
			"Equals and hashCode match HashMap with same entries"
		)
		void shouldBeEqualToHashMapWithSameEntries() {
			final LazyHashMap<String, Integer> map =
				new LazyHashMap<>(3);
			map.put("a", 1);
			map.put("b", 2);

			final Map<String, Integer> hm = new HashMap<>();
			hm.put("a", 1);
			hm.put("b", 2);

			assertEquals(hm, map);
			assertEquals(map, hm);
			assertEquals(hm.hashCode(), map.hashCode());
		}

		@Test
		@DisplayName(
			"toString returns \"{}\" when delegate is uninitialized"
		)
		void shouldReturnBracesToStringWhenUninitialized() {
			final LazyHashMap<String, Integer> map =
				new LazyHashMap<>(4);

			assertEquals("{}", map.toString());
		}

		@Test
		@DisplayName(
			"toString returns delegate representation "
				+ "when initialized"
		)
		void shouldReturnDelegateToStringWhenInitialized() {
			final LazyHashMap<String, Integer> map =
				new LazyHashMap<>(2);
			map.put("a", 1);

			final Map<String, Integer> expected = new HashMap<>();
			expected.put("a", 1);

			assertEquals(expected.toString(), map.toString());
		}

		@Test
		@DisplayName(
			"hashCode returns zero when uninitialized per Map contract"
		)
		void shouldReturnZeroHashCodeWhenUninitialized() {
			final LazyHashMap<String, Integer> map =
				new LazyHashMap<>(4);

			assertEquals(0, map.hashCode());
		}

		@Test
		@DisplayName(
			"Uninitialized map equals empty cleared map"
		)
		void shouldBeEqualToEmptyClearedMap() {
			final LazyHashMap<String, Integer> uninitMap =
				new LazyHashMap<>(4);
			final LazyHashMap<String, Integer> clearedMap =
				new LazyHashMap<>(4);
			clearedMap.put("a", 1);
			clearedMap.clear();

			assertTrue(uninitMap.equals(clearedMap));
			assertTrue(clearedMap.equals(uninitMap));
		}
	}

	@Nested
	@DisplayName("Null handling")
	class NullHandlingTest {

		@Test
		@DisplayName(
			"Null keys and values are supported like HashMap"
		)
		void shouldSupportNullKeysAndValuesLikeHashMap() {
			final LazyHashMap<String, Integer> map =
				new LazyHashMap<>(2);

			assertNull(map.put(null, null));
			assertTrue(map.containsKey(null));
			assertTrue(map.containsValue(null));
			assertNull(map.get(null));
		}
	}

	@Nested
	@DisplayName("Lazy initialization")
	class LazyInitializationTest {

		@Test
		@DisplayName(
			"Delegate is lazily initialized "
				+ "on first mutating operation"
		)
		void shouldLazyInitializeOnFirstUseWithoutExceptions() {
			final LazyHashMap<String, Integer> map =
				new LazyHashMap<>(1);

			// calling size() on fresh instance should work
			assertEquals(0, map.size());

			// after first mutating operation, structure behaves
			// as normal HashMap
			map.put("k", 7);
			assertEquals(1, map.size());
			assertEquals(7, map.get("k"));
		}
	}

	@Nested
	@DisplayName("Default Map methods")
	class DefaultMethodTest {

		@Test
		@DisplayName("forEach iterates all entries")
		void shouldIterateWithForEach() {
			final LazyHashMap<String, Integer> map =
				new LazyHashMap<>(3);
			map.put("a", 1);
			map.put("b", 2);
			map.put("c", 3);

			final Map<String, Integer> collected = new HashMap<>();
			map.forEach(collected::put);

			assertEquals(3, collected.size());
			assertEquals(1, collected.get("a"));
			assertEquals(2, collected.get("b"));
			assertEquals(3, collected.get("c"));
		}

		@Test
		@DisplayName(
			"getOrDefault returns default for missing key "
				+ "and real value for existing key"
		)
		void shouldReturnDefaultForMissingKey() {
			final LazyHashMap<String, Integer> map =
				new LazyHashMap<>(2);
			map.put("a", 1);

			assertEquals(
				1, map.getOrDefault("a", 99)
			);
			assertEquals(
				99, map.getOrDefault("missing", 99)
			);
		}

		@Test
		@DisplayName("replaceAll transforms all values")
		void shouldReplaceAllValues() {
			final LazyHashMap<String, Integer> map =
				new LazyHashMap<>(2);
			map.put("a", 1);
			map.put("b", 2);

			map.replaceAll((k, v) -> v * 10);

			assertEquals(10, map.get("a"));
			assertEquals(20, map.get("b"));
		}
	}

	@Nested
	@DisplayName("Operations on uninitialized delegate")
	class UninitializedDelegateTest {

		@Test
		@DisplayName(
			"remove returns null when delegate is uninitialized"
		)
		void shouldReturnNullFromRemoveWhenUninitialized() {
			final LazyHashMap<String, Integer> map =
				new LazyHashMap<>(4);

			assertNull(map.remove("x"));
		}

		@Test
		@DisplayName(
			"containsKey returns false "
				+ "when delegate is uninitialized"
		)
		void shouldReturnFalseFromContainsKeyWhenUninitialized() {
			final LazyHashMap<String, Integer> map =
				new LazyHashMap<>(4);

			assertFalse(map.containsKey("x"));
		}

		@Test
		@DisplayName(
			"containsValue returns false "
				+ "when delegate is uninitialized"
		)
		void shouldReturnFalseFromContainsValueWhenUninitialized() {
			final LazyHashMap<String, Integer> map =
				new LazyHashMap<>(4);

			assertFalse(map.containsValue("x"));
		}

		@Test
		@DisplayName(
			"keySet returns empty set "
				+ "when delegate is uninitialized"
		)
		void shouldReturnEmptyKeySetWhenUninitialized() {
			final LazyHashMap<String, Integer> map =
				new LazyHashMap<>(4);

			final Set<String> keys = map.keySet();

			assertTrue(keys.isEmpty());
		}

		@Test
		@DisplayName(
			"values returns empty collection "
				+ "when delegate is uninitialized"
		)
		void shouldReturnEmptyValuesWhenUninitialized() {
			final LazyHashMap<String, Integer> map =
				new LazyHashMap<>(4);

			final Collection<Integer> values = map.values();

			assertTrue(values.isEmpty());
		}

		@Test
		@DisplayName(
			"entrySet returns empty set "
				+ "when delegate is uninitialized"
		)
		void shouldReturnEmptyEntrySetWhenUninitialized() {
			final LazyHashMap<String, Integer> map =
				new LazyHashMap<>(4);

			final Set<Map.Entry<String, Integer>> entries =
				map.entrySet();

			assertTrue(entries.isEmpty());
		}

		@Test
		@DisplayName(
			"clear does not throw when delegate is uninitialized"
		)
		void shouldNotThrowOnClearWhenUninitialized() {
			final LazyHashMap<String, Integer> map =
				new LazyHashMap<>(4);

			assertDoesNotThrow(map::clear);
		}
	}
}
