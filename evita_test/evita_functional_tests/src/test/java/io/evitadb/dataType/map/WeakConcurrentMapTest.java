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

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link WeakConcurrentMap} verifying construction, basic
 * operations, identity-based key semantics, weak reference garbage
 * collection, iteration, null rejection, default value behavior,
 * and entry semantics.
 *
 * @author evitaDB
 */
@DisplayName("WeakConcurrentMap tests")
class WeakConcurrentMapTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName("Should have zero size when empty")
		void shouldHaveZeroSizeWhenEmpty() {
			final WeakConcurrentMap<String, String> map =
				new WeakConcurrentMap<>();

			assertEquals(0, map.approximateSize());
		}

		@Test
		@DisplayName("Should have empty iterator when empty")
		void shouldHaveEmptyIteratorWhenEmpty() {
			final WeakConcurrentMap<String, String> map =
				new WeakConcurrentMap<>();

			assertFalse(map.iterator().hasNext());
		}
	}

	@Nested
	@DisplayName("Basic operations")
	class BasicOperationsTest {

		@Test
		@DisplayName("Should put and get entry")
		void shouldPutAndGetEntry() {
			final WeakConcurrentMap<String, String> map =
				new WeakConcurrentMap<>();
			final String key = "key";

			map.put(key, "value");

			assertEquals("value", map.get(key));
		}

		@Test
		@DisplayName("Should return null for missing key")
		void shouldReturnNullForMissingKey() {
			final WeakConcurrentMap<String, String> map =
				new WeakConcurrentMap<>();

			assertNull(map.get("missing"));
		}

		@Test
		@DisplayName("Should remove entry")
		void shouldRemoveEntry() {
			final WeakConcurrentMap<String, String> map =
				new WeakConcurrentMap<>();
			final String key = "key";
			map.put(key, "value");

			map.remove(key);

			assertNull(map.get(key));
		}

		@Test
		@DisplayName("Should contain key after put")
		void shouldContainKeyAfterPut() {
			final WeakConcurrentMap<String, String> map =
				new WeakConcurrentMap<>();
			final String key = "key";

			map.put(key, "value");

			assertTrue(map.containsKey(key));
		}

		@Test
		@DisplayName("Should not contain key after remove")
		void shouldNotContainKeyAfterRemove() {
			final WeakConcurrentMap<String, String> map =
				new WeakConcurrentMap<>();
			final String key = "key";
			map.put(key, "value");

			map.remove(key);

			assertFalse(map.containsKey(key));
		}

		@Test
		@DisplayName("Should clear all entries")
		void shouldClearAllEntries() {
			final WeakConcurrentMap<String, String> map =
				new WeakConcurrentMap<>();
			final String key1 = "key1";
			final String key2 = "key2";
			map.put(key1, "value1");
			map.put(key2, "value2");

			map.clear();

			assertEquals(0, map.approximateSize());
		}

		@Test
		@DisplayName("Should return approximate size")
		void shouldReturnApproximateSize() {
			final WeakConcurrentMap<String, String> map =
				new WeakConcurrentMap<>();
			final String key1 = "key1";
			final String key2 = "key2";
			final String key3 = "key3";

			map.put(key1, "value1");
			map.put(key2, "value2");
			map.put(key3, "value3");

			assertTrue(map.approximateSize() >= 3);
		}
	}

	@Nested
	@DisplayName("Compute if absent")
	class ComputeIfAbsentTest {

		@Test
		@DisplayName("Should compute value for absent key")
		void shouldComputeValueForAbsentKey() {
			final WeakConcurrentMap<String, String> map =
				new WeakConcurrentMap<>();
			final String key = "key";

			final String result = map.computeIfAbsent(
				key, k -> "computed"
			);

			assertEquals("computed", result);
			assertEquals("computed", map.get(key));
		}

		@Test
		@DisplayName(
			"Should not recompute for existing key"
		)
		void shouldNotRecomputeForExistingKey() {
			final WeakConcurrentMap<String, String> map =
				new WeakConcurrentMap<>();
			final String key = "key";
			map.put(key, "original");

			final String result = map.computeIfAbsent(
				key, k -> "recomputed"
			);

			assertEquals("original", result);
			assertEquals("original", map.get(key));
		}
	}

	@Nested
	@DisplayName("Identity-based key semantics")
	class IdentitySemanticsTest {

		@SuppressWarnings("StringOperationCanBeSimplified")
		@Test
		@DisplayName(
			"Should treat different objects with same "
				+ "equals as distinct keys"
		)
		void shouldTreatDifferentObjectsWithSameEqualsAsDistinctKeys() {
			final WeakConcurrentMap<String, String> map =
				new WeakConcurrentMap<>();
			// Force two distinct String instances with
			// identical content but different identity
			final String key1 = new String("same");
			final String key2 = new String("same");

			map.put(key1, "value1");
			map.put(key2, "value2");

			assertEquals("value1", map.get(key1));
			assertEquals("value2", map.get(key2));
			assertTrue(map.approximateSize() >= 2);
		}
	}

	@Nested
	@DisplayName("Weak reference garbage collection")
	class WeakReferenceGcTest {

		@Test
		@DisplayName(
			"Should expunge entries after key is "
				+ "garbage collected"
		)
		void shouldExpungeEntriesAfterKeyIsGarbageCollected()
			throws InterruptedException {
			final WeakConcurrentMap<Object, String> map =
				new WeakConcurrentMap<>();
			Object key = new Object();
			map.put(key, "value");
			assertEquals(1, map.approximateSize());

			// Release the strong reference to the key
			key = null;

			// Non-deterministic: GC behavior varies by JVM
			boolean collected = false;
			for (int i = 0; i < 10; i++) {
				System.gc();
				Thread.sleep(100);
				map.expungeStaleEntries();
				if (map.approximateSize() == 0) {
					collected = true;
					break;
				}
			}

			assertTrue(
				collected,
				"Entry should be expunged after key is "
					+ "garbage collected"
			);
		}
	}

	@Nested
	@DisplayName("Iteration")
	class IterationTest {

		@Test
		@DisplayName("Should iterate over live entries")
		void shouldIterateOverLiveEntries() {
			final WeakConcurrentMap<String, String> map =
				new WeakConcurrentMap<>();
			final String key1 = "key1";
			final String key2 = "key2";
			final String key3 = "key3";
			map.put(key1, "value1");
			map.put(key2, "value2");
			map.put(key3, "value3");

			final Map<String, String> collected =
				new HashMap<>();
			for (final Map.Entry<String, String> entry : map) {
				collected.put(
					entry.getKey(), entry.getValue()
				);
			}

			assertEquals(3, collected.size());
			assertEquals("value1", collected.get(key1));
			assertEquals("value2", collected.get(key2));
			assertEquals("value3", collected.get(key3));
		}

		@Test
		@DisplayName(
			"Should throw UnsupportedOperationException "
				+ "on iterator remove"
		)
		void shouldThrowUnsupportedOperationOnIteratorRemove() {
			final WeakConcurrentMap<String, String> map =
				new WeakConcurrentMap<>();
			final String key = "key";
			map.put(key, "value");
			final Iterator<Map.Entry<String, String>> it =
				map.iterator();
			// advance to first element
			it.next();

			assertThrows(
				UnsupportedOperationException.class,
				it::remove
			);
		}

		@Test
		@DisplayName(
			"Should throw NoSuchElementException "
				+ "when exhausted"
		)
		void shouldThrowNoSuchElementWhenExhausted() {
			final WeakConcurrentMap<String, String> map =
				new WeakConcurrentMap<>();
			final String key = "key";
			map.put(key, "value");
			final Iterator<Map.Entry<String, String>> it =
				map.iterator();
			// exhaust the iterator
			it.next();

			assertThrows(
				NoSuchElementException.class,
				it::next
			);
		}
	}

	@Nested
	@DisplayName("Null key rejection")
	class NullKeyRejectionTest {

		@Test
		@DisplayName("Should throw NPE on get with null")
		void shouldThrowNpeOnGetNull() {
			final WeakConcurrentMap<String, String> map =
				new WeakConcurrentMap<>();

			assertThrows(
				NullPointerException.class,
				() -> map.get(null)
			);
		}

		@Test
		@DisplayName(
			"Should throw NPE on put with null key"
		)
		void shouldThrowNpeOnPutNullKey() {
			final WeakConcurrentMap<String, String> map =
				new WeakConcurrentMap<>();

			assertThrows(
				NullPointerException.class,
				() -> map.put(null, "v")
			);
		}

		@Test
		@DisplayName(
			"Should throw NPE on put with null value"
		)
		void shouldThrowNpeOnPutNullValue() {
			final WeakConcurrentMap<String, String> map =
				new WeakConcurrentMap<>();

			assertThrows(
				NullPointerException.class,
				() -> map.put("k", null)
			);
		}

		@Test
		@DisplayName(
			"Should throw NPE on remove with null"
		)
		void shouldThrowNpeOnRemoveNull() {
			final WeakConcurrentMap<String, String> map =
				new WeakConcurrentMap<>();

			assertThrows(
				NullPointerException.class,
				() -> map.remove(null)
			);
		}

		@Test
		@DisplayName(
			"Should throw NPE on containsKey with null"
		)
		void shouldThrowNpeOnContainsKeyNull() {
			final WeakConcurrentMap<String, String> map =
				new WeakConcurrentMap<>();

			assertThrows(
				NullPointerException.class,
				() -> map.containsKey(null)
			);
		}
	}

	@Nested
	@DisplayName("Default value behavior")
	class DefaultValueTest {

		@Test
		@DisplayName(
			"Should auto-populate via default value"
		)
		void shouldAutoPopulateViaDefaultValue() {
			final WeakConcurrentMap<String, String> map =
				new WeakConcurrentMap<>() {
					@Nullable
					@Override
					protected String defaultValue(
						String key
					) {
						return "default-" + key;
					}
				};
			final String key = "abc";

			final String firstGet = map.get(key);

			assertEquals("default-abc", firstGet);
			// Subsequent get returns the same value
			assertEquals("default-abc", map.get(key));
		}
	}

	@Nested
	@DisplayName("SimpleEntry behavior")
	class SimpleEntryTest {

		@Test
		@DisplayName(
			"Should expose key and value from entry"
		)
		void shouldExposeKeyAndValueFromEntry() {
			final WeakConcurrentMap<String, String> map =
				new WeakConcurrentMap<>();
			final String key = "entryKey";
			map.put(key, "entryValue");

			final Iterator<Map.Entry<String, String>> it =
				map.iterator();
			assertTrue(it.hasNext());
			final Map.Entry<String, String> entry = it.next();

			assertEquals("entryKey", entry.getKey());
			assertEquals("entryValue", entry.getValue());
		}

		@Test
		@DisplayName(
			"Should throw NPE on setValue with null"
		)
		void shouldThrowNpeOnSetValueNull() {
			final WeakConcurrentMap<String, String> map =
				new WeakConcurrentMap<>();
			final String key = "key";
			map.put(key, "value");
			final Iterator<Map.Entry<String, String>> it =
				map.iterator();
			final Map.Entry<String, String> entry = it.next();

			assertThrows(
				NullPointerException.class,
				() -> entry.setValue(null)
			);
		}
	}

	@Nested
	@DisplayName("Equals edge cases")
	class EqualsEdgeCasesTest {

		@Test
		@DisplayName(
			"Should not throw ClassCastException "
				+ "for unknown type in equals"
		)
		void shouldNotThrowClassCastExceptionForUnknownTypeInEquals() {
			final WeakConcurrentMap<String, String> map =
				new WeakConcurrentMap<>();
			final String key = "test";

			map.put(key, "value");

			// Verify public operations complete without
			// ClassCastException
			assertNotNull(map.get(key));
			assertTrue(map.containsKey(key));
			assertEquals("value", map.remove(key));
			assertFalse(map.containsKey(key));
		}
	}
}
