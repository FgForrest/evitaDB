/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.index.map;

import io.evitadb.dataType.champ.ChampMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Set;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the contract of {@link PersistentTransactionalMap} — the {@link ChampMap}-backed,
 * plain-value-only STM map. Covers construction, non-transactional operations, transactional commit and
 * rollback semantics, iterator contracts, the {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer}
 * contract, and the constraints specific to the persistent backing: null fail-fast, the inherited
 * `compute`-family routed through `get`/`put`/`remove` (never {@link ChampMap}'s throwing mutators), and that the
 * committed snapshot is a {@link ChampMap}. The generational randomized (fuzz) proof lives in
 * `LongRunningPersistentTransactionalMapTest` in the long-running test module.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@SuppressWarnings("SameParameterValue")
@DisplayName("PersistentTransactionalMap")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class PersistentTransactionalMapTest {
	/** The map under test, pre-populated with {"a"->1, "b"->2} before each test. */
	private PersistentTransactionalMap<String, Integer> tested;

	@BeforeEach
	void setUp() {
		final Map<String, Integer> underlyingData = new LinkedHashMap<>();
		underlyingData.put("a", 1);
		underlyingData.put("b", 2);
		this.tested = new PersistentTransactionalMap<>(underlyingData);
	}

	/**
	 * Construction-time behaviour and identity guarantees.
	 */
	@Nested
	@DisplayName("Construction and identity")
	class ConstructionAndIdentityTest {

		@Test
		@DisplayName("assigns unique id to each instance")
		void shouldAssignUniqueIdPerInstance() {
			final PersistentTransactionalMap<String, Integer> first = new PersistentTransactionalMap<>(new HashMap<>());
			final PersistentTransactionalMap<String, Integer> second = new PersistentTransactionalMap<>(new HashMap<>());

			assertNotEquals(first.getId(), second.getId());
		}

		@Test
		@DisplayName("createLayer returns MapChanges backed by the snapshot")
		void shouldCreateLayer() {
			final MapChanges<String, Integer> layer = PersistentTransactionalMapTest.this.tested.createLayer();

			assertNotNull(layer);
			assertEquals(2, layer.getMapDelegate().size());
			assertTrue(layer.getMapDelegate().containsKey("a"));
			assertTrue(layer.getMapDelegate().containsKey("b"));
		}

		@Test
		@DisplayName("adopts an existing ChampMap as the snapshot in O(1)")
		void shouldAdoptChampMapDirectly() {
			final ChampMap<String, Integer> champ = ChampMap.<String, Integer>empty().updated("x", 9);
			final PersistentTransactionalMap<String, Integer> map = new PersistentTransactionalMap<>(champ);

			assertEquals(9, map.get("x"));
			assertEquals(1, map.size());
		}

	}

	/**
	 * All Map operations when no transaction is active — mutations replace the immutable snapshot in place.
	 */
	@Nested
	@DisplayName("Non-transactional operations")
	class NonTransactionalOperationsTest {

		@Test
		@DisplayName("put adds a new entry by replacing the snapshot")
		void shouldPutWithoutTransaction() {
			PersistentTransactionalMapTest.this.tested.put("c", 3);

			assertMapContains(PersistentTransactionalMapTest.this.tested, new Tuple("a", 1), new Tuple("b", 2), new Tuple("c", 3));
		}

		@Test
		@DisplayName("put returns the previous value for an existing key")
		void shouldPutExistingKeyReturnsPreviousValue() {
			final Integer previous = PersistentTransactionalMapTest.this.tested.put("a", 99);

			assertEquals(1, previous);
			assertEquals(99, PersistentTransactionalMapTest.this.tested.get("a"));
		}

		@Test
		@DisplayName("put returns null when the key did not exist before")
		void shouldPutNewKeyReturnsNull() {
			assertNull(PersistentTransactionalMapTest.this.tested.put("z", 99));
		}

		@Test
		@DisplayName("remove deletes an existing entry and returns its value")
		void shouldRemoveWithoutTransaction() {
			assertEquals(1, PersistentTransactionalMapTest.this.tested.remove("a"));

			assertMapContains(PersistentTransactionalMapTest.this.tested, new Tuple("b", 2));
		}

		@Test
		@DisplayName("remove returns null when the key does not exist")
		void shouldRemoveNonExistentKeyReturnsNull() {
			assertNull(PersistentTransactionalMapTest.this.tested.remove("z"));
		}

		@Test
		@DisplayName("size and isEmpty reflect snapshot state")
		void shouldReportSizeAndIsEmpty() {
			assertEquals(2, PersistentTransactionalMapTest.this.tested.size());
			assertFalse(PersistentTransactionalMapTest.this.tested.isEmpty());

			PersistentTransactionalMapTest.this.tested.remove("a");
			PersistentTransactionalMapTest.this.tested.remove("b");

			assertEquals(0, PersistentTransactionalMapTest.this.tested.size());
			assertTrue(PersistentTransactionalMapTest.this.tested.isEmpty());
		}

		@Test
		@DisplayName("putAll adds all entries from a source map")
		void shouldPutAllWithoutTransaction() {
			final Map<String, Integer> extra = new LinkedHashMap<>();
			extra.put("c", 3);
			extra.put("d", 4);

			PersistentTransactionalMapTest.this.tested.putAll(extra);

			assertMapContains(
				PersistentTransactionalMapTest.this.tested,
				new Tuple("a", 1), new Tuple("b", 2), new Tuple("c", 3), new Tuple("d", 4)
			);
		}

		@Test
		@DisplayName("clear empties the map")
		void shouldClearWithoutTransaction() {
			PersistentTransactionalMapTest.this.tested.clear();

			assertTrue(PersistentTransactionalMapTest.this.tested.isEmpty());
			assertEquals(0, PersistentTransactionalMapTest.this.tested.size());
		}

		@Test
		@DisplayName("keySet, values, and entrySet iterate over snapshot entries")
		void shouldIterateViewsWithoutTransaction() {
			final Set<String> keys = new HashSet<>(PersistentTransactionalMapTest.this.tested.keySet());
			final Set<Integer> values = new HashSet<>(PersistentTransactionalMapTest.this.tested.values());
			final Set<Entry<String, Integer>> entries = new HashSet<>(PersistentTransactionalMapTest.this.tested.entrySet());

			assertEquals(new HashSet<>(Arrays.asList("a", "b")), keys);
			assertEquals(new HashSet<>(Arrays.asList(1, 2)), values);
			assertTrue(entries.contains(new SimpleEntry<>("a", 1)));
			assertTrue(entries.contains(new SimpleEntry<>("b", 2)));
		}

	}

	/**
	 * Null keys/values are rejected fail-fast, matching {@link ChampMap}'s constraint.
	 */
	@Nested
	@DisplayName("Null fail-fast")
	class NullFailFastTest {

		@Test
		@DisplayName("put with a null value throws")
		void shouldRejectNullValue() {
			assertThrows(Exception.class, () -> PersistentTransactionalMapTest.this.tested.put("c", null));
		}

		@Test
		@DisplayName("put with a null key throws")
		void shouldRejectNullKey() {
			assertThrows(Exception.class, () -> PersistentTransactionalMapTest.this.tested.put(null, 1));
		}

		@Test
		@DisplayName("get with a null key throws")
		void shouldRejectNullKeyOnGet() {
			assertThrows(Exception.class, () -> PersistentTransactionalMapTest.this.tested.get(null));
		}

	}

	/**
	 * Mutations inside a transaction are isolated and produce the expected committed snapshot.
	 */
	@Nested
	@DisplayName("Transactional commit")
	class TransactionalCommitTest {

		@Test
		@DisplayName("put and update produce correct committed map without modifying original")
		void shouldNotModifyOriginalStateButCreateModifiedCopy() {
			assertStateAfterCommit(
				PersistentTransactionalMapTest.this.tested,
				original -> {
					original.put("a", 3);
					original.put("c", 3);
					assertMapContains(original, new Tuple("a", 3), new Tuple("b", 2), new Tuple("c", 3));
				},
				(original, committedVersion) -> {
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
					assertMapContains(committedVersion, new Tuple("a", 3), new Tuple("b", 2), new Tuple("c", 3));
				}
			);
		}

		@Test
		@DisplayName("mixed removals, updates, and insertions produce correct committed map")
		void shouldMergeRemovalsAndUpdatesAndInsertionsOnTransactionCommit() {
			assertStateAfterCommit(
				PersistentTransactionalMapTest.this.tested,
				original -> {
					original.remove("a");
					original.put("b", 3);
					original.put("c", 3);
					assertMapContains(original, new Tuple("b", 3), new Tuple("c", 3));
				},
				(original, committedVersion) -> {
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
					assertMapContains(committedVersion, new Tuple("b", 3), new Tuple("c", 3));
				}
			);
		}

		@Test
		@DisplayName("the committed copy is a persistent immutable ChampMap")
		void shouldCommitAChampMapSnapshot() {
			assertStateAfterCommit(
				PersistentTransactionalMapTest.this.tested,
				original -> original.put("c", 3),
				(original, committedVersion) -> {
					assertInstanceOf(ChampMap.class, committedVersion);
					assertMapContains(committedVersion, new Tuple("a", 1), new Tuple("b", 2), new Tuple("c", 3));
				}
			);
		}

		@Test
		@DisplayName("a transaction that reads but changes nothing commits the unchanged snapshot")
		void shouldCommitUnchangedSnapshotWhenNothingMutated() {
			assertStateAfterCommit(
				PersistentTransactionalMapTest.this.tested,
				original -> {
					// only reads, no mutations
					assertEquals(1, original.get("a"));
					assertTrue(original.containsKey("b"));
				},
				(original, committedVersion) -> {
					assertInstanceOf(ChampMap.class, committedVersion);
					assertMapContains(committedVersion, new Tuple("a", 1), new Tuple("b", 2));
				}
			);
		}

		@Test
		@DisplayName("entrySet, keySet and values reflect transactional removes and inserts")
		void shouldProduceValidViews() {
			assertStateAfterCommit(
				PersistentTransactionalMapTest.this.tested,
				original -> {
					original.put("c", 3);
					original.remove("b");

					assertEquals(new HashSet<>(Arrays.asList("a", "c")), new HashSet<>(original.keySet()));
					assertEquals(new HashSet<>(Arrays.asList(1, 3)), new HashSet<>(original.values()));
					final Set<Entry<String, Integer>> entries = new HashSet<>(original.entrySet());
					assertTrue(entries.contains(new SimpleEntry<>("a", 1)));
					assertTrue(entries.contains(new SimpleEntry<>("c", 3)));
				},
				(original, committedVersion) -> assertMapContains(committedVersion, new Tuple("a", 1), new Tuple("c", 3))
			);
		}

		@Test
		@DisplayName("keySet iterator remove does not modify the original state")
		void shouldNotModifyOriginalStateOnKeySetIteratorRemoval() {
			assertStateAfterCommit(
				PersistentTransactionalMapTest.this.tested,
				original -> {
					original.put("c", 3);
					final Iterator<String> it = original.keySet().iterator();
					while (it.hasNext()) {
						if (it.next().equals("b")) {
							it.remove();
						}
					}
					assertMapContains(original, new Tuple("a", 1), new Tuple("c", 3));
				},
				(original, committedVersion) -> {
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
					assertMapContains(committedVersion, new Tuple("a", 1), new Tuple("c", 3));
				}
			);
		}

		@Test
		@DisplayName("entrySet setValue during iteration is reflected in the committed map")
		void shouldMergeChangesInEntrySetIterator() {
			assertStateAfterCommit(
				PersistentTransactionalMapTest.this.tested,
				original -> {
					original.put("c", 3);
					final Iterator<Entry<String, Integer>> it = original.entrySet().iterator();
					while (it.hasNext()) {
						final Entry<String, Integer> entry = it.next();
						if ("b".equals(entry.getKey())) {
							entry.setValue(5);
						}
					}
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 5), new Tuple("c", 3));
				},
				(original, committedVersion) ->
					assertMapContains(committedVersion, new Tuple("a", 1), new Tuple("b", 5), new Tuple("c", 3))
			);
		}

		@Test
		@DisplayName("entrySet iterator hasNext is idempotent and next throws when exhausted")
		void shouldKeepIteratorContract() {
			assertStateAfterCommit(
				PersistentTransactionalMapTest.this.tested,
				original -> {
					original.put("c", 3);
					final List<String> result = new ArrayList<>(3);
					final Iterator<Entry<String, Integer>> it = original.entrySet().iterator();
					for (int i = 0; i < 3; i++) {
						for (int j = 0; j < 10; j++) {
							assertTrue(it.hasNext());
						}
						result.add(it.next().getKey());
					}
					for (int j = 0; j < 10; j++) {
						assertFalse(it.hasNext());
					}
					assertEquals(new HashSet<>(Arrays.asList("a", "b", "c")), new HashSet<>(result));
					assertThrows(NoSuchElementException.class, it::next);
				},
				(original, committedVersion) -> {
					// iterator contract only
				}
			);
		}

		@Test
		@DisplayName("putAll within a transaction commits all entries correctly")
		void shouldPutAllInTransaction() {
			assertStateAfterCommit(
				PersistentTransactionalMapTest.this.tested,
				original -> {
					final Map<String, Integer> extra = new LinkedHashMap<>();
					extra.put("c", 3);
					extra.put("d", 4);
					original.putAll(extra);
				},
				(original, committedVersion) -> {
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
					assertMapContains(
						committedVersion,
						new Tuple("a", 1), new Tuple("b", 2), new Tuple("c", 3), new Tuple("d", 4)
					);
				}
			);
		}

		@Test
		@DisplayName("clear within a transaction commits an empty map")
		void shouldClearInTransaction() {
			assertStateAfterCommit(
				PersistentTransactionalMapTest.this.tested,
				Map::clear,
				(original, committedVersion) -> {
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
					assertMapContains(committedVersion);
				}
			);
		}

	}

	/**
	 * The inherited {@link Map} default `compute`-family is built on `get`/`put`/`remove`, so it must produce
	 * the same result as on a {@link HashMap} and must never reach {@link ChampMap}'s throwing mutators.
	 */
	@Nested
	@DisplayName("compute-family parity")
	class ComputeFamilyTest {

		@Test
		@DisplayName("compute updates, inserts and removes exactly like a HashMap (no transaction)")
		void shouldMatchHashMapComputeWithoutTransaction() {
			final Map<String, Integer> oracle = new HashMap<>(Map.of("a", 1, "b", 2));

			PersistentTransactionalMapTest.this.tested.compute("a", (k, v) -> v + 10);
			oracle.compute("a", (k, v) -> v + 10);

			PersistentTransactionalMapTest.this.tested.compute("c", (k, v) -> 30);
			oracle.compute("c", (k, v) -> 30);

			// returning null must remove the key
			PersistentTransactionalMapTest.this.tested.compute("b", (k, v) -> null);
			oracle.compute("b", (k, v) -> null);

			assertEquals(oracle, PersistentTransactionalMapTest.this.tested);
		}

		@Test
		@DisplayName("computeIfAbsent, computeIfPresent and merge match a HashMap (no transaction)")
		void shouldMatchHashMapComputeIfVariantsWithoutTransaction() {
			final Map<String, Integer> oracle = new HashMap<>(Map.of("a", 1, "b", 2));

			PersistentTransactionalMapTest.this.tested.computeIfAbsent("c", k -> 3);
			oracle.computeIfAbsent("c", k -> 3);

			PersistentTransactionalMapTest.this.tested.computeIfPresent("a", (k, v) -> v * 100);
			oracle.computeIfPresent("a", (k, v) -> v * 100);

			PersistentTransactionalMapTest.this.tested.merge("b", 5, Integer::sum);
			oracle.merge("b", 5, Integer::sum);

			PersistentTransactionalMapTest.this.tested.putIfAbsent("d", 4);
			oracle.putIfAbsent("d", 4);

			assertEquals(oracle, PersistentTransactionalMapTest.this.tested);
		}

		@Test
		@DisplayName("compute inside a transaction commits the same result as a HashMap")
		void shouldMatchHashMapComputeInTransaction() {
			final Map<String, Integer> oracle = new HashMap<>(Map.of("a", 1, "b", 2));
			oracle.compute("a", (k, v) -> v + 10);
			oracle.compute("c", (k, v) -> 30);

			assertStateAfterCommit(
				PersistentTransactionalMapTest.this.tested,
				original -> {
					original.compute("a", (k, v) -> v + 10);
					original.compute("c", (k, v) -> 30);
				},
				(original, committedVersion) -> assertEquals(oracle, committedVersion)
			);
		}

	}

	/**
	 * All mutations performed within a transaction are discarded on rollback.
	 */
	@Nested
	@DisplayName("Transactional rollback")
	class TransactionalRollbackTest {

		@Test
		@DisplayName("mixed operations are all discarded on rollback")
		void shouldDiscardMultipleOperationsOnRollback() {
			assertStateAfterRollback(
				PersistentTransactionalMapTest.this.tested,
				original -> {
					original.put("c", 3);
					original.remove("a");
					original.put("b", 99);
					assertMapContains(original, new Tuple("b", 99), new Tuple("c", 3));
				},
				(original, committedVersion) -> {
					assertNull(committedVersion);
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
				}
			);
		}

	}

	/**
	 * equals, hashCode and toString.
	 */
	@Nested
	@DisplayName("equals, hashCode and toString")
	class EqualsHashCodeTest {

		@Test
		@DisplayName("equals a plain map with identical entries")
		void shouldEqualPlainMap() {
			assertEquals(new HashMap<>(Map.of("a", 1, "b", 2)), PersistentTransactionalMapTest.this.tested);
		}

		@Test
		@DisplayName("not equal to a map with different entries")
		void shouldNotEqualMapWithDifferentEntries() {
			assertNotEquals(new HashMap<>(Map.of("a", 9, "b", 9)), PersistentTransactionalMapTest.this.tested);
		}

		@Test
		@DisplayName("hashCode is consistent across calls and equals the Map contract value")
		void shouldHaveConsistentHashCode() {
			final int expected = new HashMap<>(Map.of("a", 1, "b", 2)).hashCode();
			assertEquals(expected, PersistentTransactionalMapTest.this.tested.hashCode());
			assertEquals(PersistentTransactionalMapTest.this.tested.hashCode(), PersistentTransactionalMapTest.this.tested.hashCode());
		}

		@Test
		@DisplayName("toString is brace-enclosed and contains the entries")
		void shouldProduceReadableToString() {
			final String str = PersistentTransactionalMapTest.this.tested.toString();
			assertTrue(str.startsWith("{"));
			assertTrue(str.endsWith("}"));
			assertTrue(str.contains("a=1"));
			assertTrue(str.contains("b=2"));
		}

	}

	/**
	 * Edge cases that previously broke {@link TransactionalMap} or stress the diff-layer / snapshot interaction.
	 */
	@Nested
	@DisplayName("Edge cases")
	class EdgeCasesTest {

		@Test
		@DisplayName("put then remove the same key within a transaction leaves the map unchanged")
		void shouldCancelPutAndRemoveSameKey() {
			assertStateAfterCommit(
				PersistentTransactionalMapTest.this.tested,
				original -> {
					original.put("c", 3);
					original.remove("c");
					assertMapContains(original, new Tuple("a", 1), new Tuple("b", 2));
				},
				(original, committedVersion) -> assertMapContains(committedVersion, new Tuple("a", 1), new Tuple("b", 2))
			);
		}

		@Test
		@DisplayName("remove then re-put the same key with a new value reflects the new value")
		void shouldReflectNewValueAfterRemoveThenRePut() {
			assertStateAfterCommit(
				PersistentTransactionalMapTest.this.tested,
				original -> {
					original.remove("a");
					original.put("a", 99);
					assertEquals(99, original.get("a"));
				},
				(original, committedVersion) -> assertMapContains(committedVersion, new Tuple("a", 99), new Tuple("b", 2))
			);
		}

		@Test
		@DisplayName("operations on an empty map work correctly inside a transaction")
		void shouldHandleEmptyMap() {
			final PersistentTransactionalMap<String, Integer> empty = new PersistentTransactionalMap<>(new HashMap<>());

			assertStateAfterCommit(
				empty,
				original -> {
					assertTrue(original.isEmpty());
					original.put("x", 42);
					assertMapContains(original, new Tuple("x", 42));
				},
				(original, committedVersion) -> assertMapContains(committedVersion, new Tuple("x", 42))
			);
		}

	}

	// -----------------------------------------------------------------------
	// Shared helpers
	// -----------------------------------------------------------------------

	/**
	 * Verifies that `map` contains exactly the entries described by `data` and no others, exercising size,
	 * isEmpty, get, containsKey, containsValue and all three iterator views.
	 *
	 * @param map  the map under test
	 * @param data the expected key-value pairs; pass no arguments to assert an empty map
	 */
	private static void assertMapContains(@Nonnull Map<String, Integer> map, @Nonnull Tuple... data) {
		if (data.length == 0) {
			assertTrue(map.isEmpty());
		} else {
			assertFalse(map.isEmpty());
		}

		assertEquals(data.length, map.size());

		final Map<String, Integer> expectedMap = new HashMap<>(data.length);
		for (final Tuple tuple : data) {
			expectedMap.put(tuple.key(), tuple.value());
			assertEquals(tuple.value(), map.get(tuple.key()));
			assertTrue(map.containsKey(tuple.key()));
			assertTrue(map.containsValue(tuple.value()));
		}

		for (final Entry<String, Integer> entry : map.entrySet()) {
			assertEquals(expectedMap.get(entry.getKey()), entry.getValue());
		}
		for (final String key : map.keySet()) {
			assertTrue(expectedMap.containsKey(key));
		}
		for (final Integer value : map.values()) {
			assertTrue(expectedMap.containsValue(value));
		}
	}

	/**
	 * A simple key-value pair used as expected data in {@link #assertMapContains}.
	 *
	 * @param key   the map key
	 * @param value the expected value for that key
	 */
	private record Tuple(@Nonnull String key, @Nonnull Integer value) {
	}

}
