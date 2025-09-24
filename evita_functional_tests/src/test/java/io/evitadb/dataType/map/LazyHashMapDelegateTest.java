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
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LazyHashMap basic Map contract tests")
class LazyHashMapDelegateTest {
	@Test
	@DisplayName("shouldBeEmptyOnCreation")
	void shouldBeEmptyOnCreation() {
		final LazyHashMap<String, Integer> map = new LazyHashMap<>(4);
		assertEquals(0, map.size());
		assertTrue(map.isEmpty());
	}

	@Test
	@DisplayName("shouldPutAndGetEntries")
	void shouldPutAndGetEntries() {
		final LazyHashMap<String, Integer> map = new LazyHashMap<>(2);
		assertNull(map.put("a", 1));
		assertEquals(1, map.size());
		assertEquals(1, map.get("a"));

		// update existing key
		assertEquals(1, map.put("a", 2));
		assertEquals(2, map.get("a"));
		assertEquals(1, map.size());
	}

	@Test
	@DisplayName("shouldSupportContainsKeyAndValue")
	void shouldSupportContainsKeyAndValue() {
		final LazyHashMap<String, Integer> map = new LazyHashMap<>(1);
		map.put("x", 42);
		assertTrue(map.containsKey("x"));
		assertFalse(map.containsKey("y"));
		assertTrue(map.containsValue(42));
		assertFalse(map.containsValue(43));
	}

	@Test
	@DisplayName("shouldRemoveEntries")
	void shouldRemoveEntries() {
		final LazyHashMap<String, Integer> map = new LazyHashMap<>(3);
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
	@DisplayName("shouldClearMap")
	void shouldClearMap() {
		final LazyHashMap<String, Integer> map = new LazyHashMap<>(2);
		map.put("a", 1);
		map.put("b", 2);
		map.clear();
		assertTrue(map.isEmpty());
		assertEquals(0, map.size());
	}

	@Test
	@DisplayName("shouldPutAllFromAnotherMap")
	void shouldPutAllFromAnotherMap() {
		final LazyHashMap<String, Integer> map = new LazyHashMap<>(2);
		final Map<String, Integer> other = new HashMap<>();
		other.put("a", 1);
		other.put("b", 2);
		map.putAll(other);
		assertEquals(2, map.size());
		assertEquals(1, map.get("a"));
		assertEquals(2, map.get("b"));
	}

	@Test
	@DisplayName("shouldExposeViewsKeySetValuesEntrySet")
	void shouldExposeViewsKeySetValuesEntrySet() {
		final LazyHashMap<String, Integer> map = new LazyHashMap<>(4);
		map.put("a", 1);
		map.put("b", 2);

		final Set<String> keys = map.keySet();
		final Collection<Integer> values = map.values();
		final Set<Map.Entry<String, Integer>> entries = map.entrySet();

		assertEquals(new HashSet<>(Arrays.asList("a", "b")), new HashSet<>(keys));
		assertEquals(new HashSet<>(Arrays.asList(1, 2)), new HashSet<>(values));
		assertEquals(2, entries.size());

		// views reflect changes
		map.put("c", 3);
		assertTrue(keys.contains("c"));
		assertTrue(values.contains(3));
		assertEquals(3, entries.size());
	}

	@Test
	@DisplayName("shouldSupportComputeReplaceMerge")
	void shouldSupportComputeReplaceMerge() {
		final LazyHashMap<String, Integer> map = new LazyHashMap<>(2);

		assertEquals(10, map.computeIfAbsent("x", k -> 10));
		assertEquals(10, map.get("x"));

		assertEquals(11, map.computeIfPresent("x", (k, v) -> v + 1));
		assertEquals(11, map.get("x"));

		assertEquals(20, map.merge("x", 9, Integer::sum));
		assertEquals(20, map.get("x"));

		assertNull(map.replace("y", 1));
		assertNull(map.get("y"));
		assertNull(map.putIfAbsent("y", 5));
		assertEquals(5, map.get("y"));
		assertTrue(map.replace("y", 5, 6));
		assertEquals(6, map.get("y"));
	}

	@Test
	@DisplayName("shouldBeEqualToHashMapWithSameEntries")
	void shouldBeEqualToHashMapWithSameEntries() {
		final LazyHashMap<String, Integer> map = new LazyHashMap<>(3);
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
	@DisplayName("shouldSupportNullKeysAndValuesLikeHashMap")
	void shouldSupportNullKeysAndValuesLikeHashMap() {
		final LazyHashMap<String, Integer> map = new LazyHashMap<>(2);
		assertNull(map.put(null, null));
		assertTrue(map.containsKey(null));
		assertTrue(map.containsValue(null));
		assertNull(map.get(null));
	}

	@Test
	@DisplayName("shouldLazyInitializeOnFirstUseWithoutExceptions")
	void shouldLazyInitializeOnFirstUseWithoutExceptions() {
		final LazyHashMap<String, Integer> map = new LazyHashMap<>(1);
		// calling size() on fresh instance should work and not throw
		assertEquals(0, map.size());
		// after first mutating operation, structure behaves as normal HashMap
		map.put("k", 7);
		assertEquals(1, map.size());
		assertEquals(7, map.get("k"));
	}
}
