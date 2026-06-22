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

package io.evitadb.store.offsetIndex.map;

import io.evitadb.store.offsetIndex.model.RecordKey;
import io.evitadb.store.shared.model.FileLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the correctness of {@link OffsetLocationChampMap} — the memory-lean, 3-array packed CHAMP
 * specialisation backing {@link io.evitadb.store.offsetIndex.OffsetIndex}. The suite mirrors
 * {@code ChampMapTest}: it exercises the {@link Map} read surface, persistence (no mutation of the
 * receiver), the canonical form (insertion-order independence proved through structural
 * {@link OffsetLocationChampMap#equals(Object)}), canonicalization-on-delete (the sharp edge of
 * CHAMP), explicit hash-collision buckets, the {@code Builder} vs persistent construction
 * equivalence, the {@link OffsetLocationChampMap#findRecordLength} primitive fast path, and a
 * generational {@link HashMap}-oracle fuzz that also asserts retained-snapshot immutability.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(DATA_TYPE)
@DisplayName("OffsetIndex location CHAMP map")
class OffsetLocationChampMapTest {

	/** A record type used for the bulk of single-type scenarios. */
	private static final byte TYPE = (byte) 7;

	@Nonnull
	private static RecordKey key(long primaryKey) {
		return new RecordKey(TYPE, primaryKey);
	}

	@Nonnull
	private static RecordKey key(byte type, long primaryKey) {
		return new RecordKey(type, primaryKey);
	}

	@Nonnull
	private static FileLocation loc(long primaryKey) {
		// a deterministic, distinct value per key (position and length both derived from the key)
		return new FileLocation(primaryKey * 100, (int) (primaryKey % 4096) + 1);
	}

	@Nonnull
	private static OffsetLocationChampMap mapOf(@Nonnull long... primaryKeys) {
		OffsetLocationChampMap map = OffsetLocationChampMap.empty();
		for (final long pk : primaryKeys) {
			map = map.updated(key(pk), loc(pk));
		}
		return map;
	}

	@Nested
	@DisplayName("basic read/write operations")
	class BasicOperations {

		@Test
		@DisplayName("empty map has no entries and is the shared singleton")
		void shouldStartEmpty() {
			final OffsetLocationChampMap map = OffsetLocationChampMap.empty();
			assertTrue(map.isEmpty());
			assertEquals(0, map.size());
			assertNull(map.get(key(1)));
			assertFalse(map.containsKey(key(1)));
			assertSame(OffsetLocationChampMap.empty(), OffsetLocationChampMap.empty());
		}

		@Test
		@DisplayName("single entry created via factory is retrievable")
		void shouldCreateSingleton() {
			final OffsetLocationChampMap map = OffsetLocationChampMap.of(key(63), loc(63));
			assertEquals(1, map.size());
			assertEquals(loc(63), map.get(key(63)));
			assertTrue(map.containsKey(key(63)));
			assertTrue(map.containsValue(loc(63)));
		}

		@Test
		@DisplayName("removing the only entry yields the empty map")
		void shouldRemoveFromSingleton() {
			final OffsetLocationChampMap map = OffsetLocationChampMap.of(key(63), loc(63));
			final OffsetLocationChampMap result = map.removed(key(63));
			assertTrue(result.isEmpty());
			assertEquals(0, result.size());
			assertEquals(OffsetLocationChampMap.empty(), result);
		}

		@Test
		@DisplayName("updated/removed do not mutate the receiver (persistence)")
		void shouldBePersistent() {
			final OffsetLocationChampMap base = mapOf(1, 2, 3);
			final OffsetLocationChampMap added = base.updated(key(4), loc(4));
			final OffsetLocationChampMap removed = base.removed(key(2));

			assertEquals(3, base.size());
			assertEquals(loc(2), base.get(key(2)));
			assertEquals(4, added.size());
			assertEquals(2, removed.size());
			assertNull(removed.get(key(2)));
		}

		@Test
		@DisplayName("removing an absent key returns the same instance")
		void shouldReturnSameInstanceOnNoOpRemove() {
			final OffsetLocationChampMap map = mapOf(1, 2, 3);
			assertSame(map, map.removed(key(999)));
		}

		@Test
		@DisplayName("re-putting the identical value returns the same map")
		void shouldReturnSameInstanceOnIdenticalValue() {
			final OffsetLocationChampMap map = OffsetLocationChampMap.of(key(1), loc(1));
			assertSame(map, map.updated(key(1), loc(1)));
		}

		@Test
		@DisplayName("re-putting an equal-key different-value produces a new map")
		void shouldReplaceValue() {
			final FileLocation v2 = new FileLocation(999, 7);
			final OffsetLocationChampMap map = OffsetLocationChampMap.of(key(1), loc(1));
			final OffsetLocationChampMap updated = map.updated(key(1), v2);
			assertNotEquals(map, updated);
			assertEquals(v2, updated.get(key(1)));
			assertEquals(1, updated.size());
		}

		@Test
		@DisplayName("the same primaryKey under different record types are distinct keys")
		void shouldDistinguishRecordType() {
			final OffsetLocationChampMap map = OffsetLocationChampMap.empty()
				.updated(key((byte) 1, 42), new FileLocation(10, 1))
				.updated(key((byte) 2, 42), new FileLocation(20, 2));
			assertEquals(2, map.size());
			assertEquals(new FileLocation(10, 1), map.get(key((byte) 1, 42)));
			assertEquals(new FileLocation(20, 2), map.get(key((byte) 2, 42)));
		}
	}

	@Nested
	@DisplayName("canonical form (insertion-order independence)")
	class CanonicalForm {

		@Test
		@DisplayName("maps built in different insertion orders are equal with equal hash codes")
		void shouldBuildCanonicalFormRegardlessOfInsertionOrder() {
			final OffsetLocationChampMap m1 = mapOf(63, 64, 32768, 2147483647, 65536);
			final OffsetLocationChampMap m2 = mapOf(2147483647, 32768, 63, 64, 65536);
			assertEquals(m1, m2);
			assertEquals(m1.hashCode(), m2.hashCode());
		}

		@Test
		@DisplayName("random insertion orders all converge to one canonical structure")
		void shouldConvergeToCanonicalFormForRandomOrders() {
			final Random random = new Random(42);
			final List<Long> keys = new ArrayList<>();
			for (long i = 0; i < 500; i++) {
				keys.add(i * 7 + 1);
			}
			final OffsetLocationChampMap reference = mapOf(keys.stream().mapToLong(Long::longValue).toArray());
			for (int trial = 0; trial < 20; trial++) {
				Collections.shuffle(keys, random);
				OffsetLocationChampMap map = OffsetLocationChampMap.empty();
				for (final long key : keys) {
					map = map.updated(key(key), loc(key));
				}
				assertEquals(reference, map);
				assertEquals(reference.hashCode(), map.hashCode());
			}
		}

		@Test
		@DisplayName("a map built via the Builder equals the same map built via updated")
		void shouldMatchBuilderAndPersistentConstruction() {
			final OffsetLocationChampMap.Builder builder = OffsetLocationChampMap.builder();
			OffsetLocationChampMap persistent = OffsetLocationChampMap.empty();
			for (long i = 0; i < 1000; i++) {
				builder.add(key(i), loc(i));
				persistent = persistent.updated(key(i), loc(i));
			}
			final OffsetLocationChampMap built = builder.build();
			assertEquals(persistent, built);
			assertEquals(persistent.hashCode(), built.hashCode());
			assertEquals(1000, built.size());
		}

		@Test
		@DisplayName("from(Map) reproduces the source map exactly")
		void shouldBuildFromMap() {
			final Map<RecordKey, FileLocation> source = new HashMap<>();
			for (long i = 0; i < 1000; i++) {
				source.put(key(i), loc(i));
			}
			final OffsetLocationChampMap map = OffsetLocationChampMap.from(source);
			assertEquals(source.size(), map.size());
			assertEquals(source, map);
			assertEquals(map, source);
		}
	}

	@Nested
	@DisplayName("canonicalization on delete")
	class CanonicalizationOnDelete {

		@Test
		@DisplayName("delete that collapses a deep sub-node re-canonicalizes")
		void shouldCompactDeepSubNodeUponDelete() {
			// 63 and 32831 (= 63 + 1024*32) share low chunks and force a deep split, exercising the
			// node→inline migration when one of them is removed
			final OffsetLocationChampMap m = mapOf(63, 64, 32831).removed(key(32831));
			final OffsetLocationChampMap expected = mapOf(63, 64);
			assertEquals(expected, m);
			assertEquals(expected.hashCode(), m.hashCode());
		}

		@Test
		@DisplayName("delete down to a single entry re-canonicalizes to the singleton")
		void shouldCompactToSingleton() {
			OffsetLocationChampMap m = mapOf(1, 33, 1025, 32769);
			m = m.removed(key(33)).removed(key(1025)).removed(key(32769));
			assertEquals(mapOf(1), m);
			assertEquals(1, m.size());
		}

		@Test
		@DisplayName("inserting then deleting back yields the original canonical map")
		void shouldRoundTripInsertDelete() {
			final OffsetLocationChampMap base = mapOf(1, 2, 3, 4, 5, 6, 7, 8);
			OffsetLocationChampMap mutated = base;
			for (long i = 100; i < 200; i++) {
				mutated = mutated.updated(key(i), loc(i));
			}
			for (long i = 100; i < 200; i++) {
				mutated = mutated.removed(key(i));
			}
			assertEquals(base, mutated);
			assertEquals(base.hashCode(), mutated.hashCode());
		}

		@Test
		@DisplayName("removing every key one by one drains back to the canonical empty map")
		void shouldDrainToEmpty() {
			final long[] keys = new long[120];
			for (int i = 0; i < keys.length; i++) {
				keys[i] = i * 13L + 1;
			}
			OffsetLocationChampMap map = mapOf(keys);
			assertEquals(keys.length, map.size());

			// remove in a shuffled order so the repeated root-collapse / survivor-escalation path is hit
			final List<Long> order = new ArrayList<>();
			for (final long k : keys) {
				order.add(k);
			}
			Collections.shuffle(order, new Random(99));

			int expectedSize = keys.length;
			for (final long k : order) {
				map = map.removed(key(k));
				expectedSize--;
				assertEquals(expectedSize, map.size());
				assertFalse(map.containsKey(key(k)));
			}
			assertTrue(map.isEmpty());
			assertEquals(OffsetLocationChampMap.empty(), map);
		}

		@Test
		@DisplayName("two keys diverging only at the deepest trie chunk escalate the survivor on delete")
		void shouldEscalateSurvivorAtMaximumTrieDepth() {
			// keyHash(type, pk) = 31 * type + Long.hashCode(pk), then improve(...): the pair below shares
			// the first six 5-bit chunks of the improved hash and differs only at the seventh (shift = 30),
			// forcing the deepest mergeTwoPairs split that real avalanche math (not custom hashes) can
			// reach; key 0 sits at a different root slot, keeping the root from collapsing when the deep
			// sub-trie is reduced to a single survivor
			final long deepA = 7636;
			final long deepB = 131187;
			final long other = 0;
			final OffsetLocationChampMap map = mapOf(deepA, deepB, other);
			assertEquals(3, map.size());
			assertTrue(map.containsKey(key(deepA)));
			assertTrue(map.containsKey(key(deepB)));
			assertTrue(map.containsKey(key(other)));

			final OffsetLocationChampMap afterRemoval = map.removed(key(deepB));
			assertEquals(2, afterRemoval.size());
			assertTrue(afterRemoval.containsKey(key(deepA)));
			assertTrue(afterRemoval.containsKey(key(other)));
			assertFalse(afterRemoval.containsKey(key(deepB)));
			// the lone survivor must escalate so the result is structurally identical to a fresh build
			assertEquals(mapOf(deepA, other), afterRemoval);
			assertEquals(mapOf(deepA, other).hashCode(), afterRemoval.hashCode());
		}
	}

	@Nested
	@DisplayName("hash-collision buckets")
	class HashCollisions {

		@Test
		@DisplayName("two keys sharing a full improved hash live in one bucket and remove cleanly")
		void shouldHandleCollisionBucket() {
			final long[] colliding = collidingPrimaryKeys(3);
			final long a = colliding[0];
			final long b = colliding[1];

			final OffsetLocationChampMap both = OffsetLocationChampMap.empty()
				.updated(key(a), loc(a))
				.updated(key(b), loc(b));
			assertEquals(2, both.size());
			assertEquals(loc(a), both.get(key(a)));
			assertEquals(loc(b), both.get(key(b)));

			// removing one collapses the 2-entry bucket back to an inline singleton
			final OffsetLocationChampMap onlyA = both.removed(key(b));
			assertEquals(mapOf(a), onlyA);
			assertEquals(1, onlyA.size());
			assertNull(onlyA.get(key(b)));

			// a third colliding key forms a 3-bucket; removing the middle keeps the others
			final long c = colliding[2];
			final OffsetLocationChampMap three = both.updated(key(c), loc(c));
			assertEquals(3, three.size());
			final OffsetLocationChampMap withoutA = three.removed(key(a));
			assertEquals(2, withoutA.size());
			assertEquals(loc(b), withoutA.get(key(b)));
			assertEquals(loc(c), withoutA.get(key(c)));
			assertNull(withoutA.get(key(a)));
		}

		@Test
		@DisplayName("collision bucket built in different orders is canonical")
		void shouldCanonicalizeCollisionBucket() {
			final long[] c = collidingPrimaryKeys(3);
			final OffsetLocationChampMap m1 = OffsetLocationChampMap.empty()
				.updated(key(c[0]), loc(c[0])).updated(key(c[1]), loc(c[1])).updated(key(c[2]), loc(c[2]));
			final OffsetLocationChampMap m2 = OffsetLocationChampMap.empty()
				.updated(key(c[2]), loc(c[2])).updated(key(c[0]), loc(c[0])).updated(key(c[1]), loc(c[1]));
			assertEquals(m1, m2);
			assertEquals(m1.hashCode(), m2.hashCode());
		}

		@Test
		@DisplayName("a four-key bucket survives removals at the front, middle and tail against an oracle")
		void shouldRemoveAcrossLargeBucketInAnyOrder() {
			final long[] c = collidingPrimaryKeys(4);
			// removal orders touching the first, a middle and the last surviving slot of the bucket
			final long[][] removalOrders = {
				{c[0], c[1], c[2], c[3]},   // front-first each time
				{c[3], c[2], c[1], c[0]},   // tail-first each time
				{c[1], c[3], c[0], c[2]},   // interleaved middle/tail/front
			};

			for (final long[] order : removalOrders) {
				final HashMap<RecordKey, FileLocation> oracle = new HashMap<>();
				OffsetLocationChampMap map = OffsetLocationChampMap.empty();
				for (final long pk : c) {
					oracle.put(key(pk), loc(pk));
					map = map.updated(key(pk), loc(pk));
				}
				assertEquals(4, map.size());

				for (final long pk : order) {
					oracle.remove(key(pk));
					map = map.removed(key(pk));
					assertEquals(oracle.size(), map.size());
					assertEquals(oracle, map);
					assertNull(map.get(key(pk)));
					// every still-present key remains resolvable inside the (shrinking) bucket
					for (final Entry<RecordKey, FileLocation> survivor : oracle.entrySet()) {
						assertEquals(survivor.getValue(), map.get(survivor.getKey()));
					}
				}
				assertTrue(map.isEmpty());
			}
		}
	}

	@Nested
	@DisplayName("Map read-surface contract")
	class MapContract {

		@Test
		@DisplayName("entrySet / keySet / values reflect all entries")
		void shouldExposeViews() {
			final OffsetLocationChampMap map = mapOf(1, 2, 3, 4, 5);
			final Set<RecordKey> keys = new HashSet<>();
			final Set<FileLocation> values = new HashSet<>();
			int entryCount = 0;
			for (final Entry<RecordKey, FileLocation> entry : map.entrySet()) {
				keys.add(entry.getKey());
				values.add(entry.getValue());
				entryCount++;
			}
			assertEquals(5, entryCount);
			assertEquals(map.keySet(), keys);
			assertEquals(5, map.keySet().size());
			assertEquals(5, map.values().size());
			assertTrue(map.keySet().contains(key(3)));
			assertTrue(map.values().contains(loc(3)));
			assertTrue(values.contains(loc(3)));
		}

		@Test
		@DisplayName("equals against a plain HashMap holds both ways")
		void shouldEqualPlainMap() {
			final Map<RecordKey, FileLocation> oracle = new HashMap<>();
			for (long i = 0; i < 200; i++) {
				oracle.put(key(i), loc(i));
			}
			final OffsetLocationChampMap map = OffsetLocationChampMap.from(oracle);
			assertEquals(oracle, map);
			assertEquals(map, oracle);
			assertEquals(oracle.hashCode(), map.hashCode());
		}

		@Test
		@DisplayName("get/containsKey reject non-RecordKey and absent keys")
		void shouldRejectForeignKeys() {
			final OffsetLocationChampMap map = mapOf(1, 2, 3);
			assertNull(map.get("not a key"));
			assertNull(map.get(null));
			assertFalse(map.containsKey("not a key"));
			assertFalse(map.containsKey(key(999)));
			assertFalse(map.containsValue("not a location"));
		}

		@Test
		@DisplayName("in-place mutators all throw")
		void shouldThrowOnMutators() {
			final OffsetLocationChampMap map = mapOf(1);
			assertThrows(UnsupportedOperationException.class, () -> map.put(key(2), loc(2)));
			assertThrows(UnsupportedOperationException.class, () -> map.remove(key(1)));
			assertThrows(UnsupportedOperationException.class, () -> map.putAll(Map.of()));
			assertThrows(UnsupportedOperationException.class, map::clear);
			assertThrows(UnsupportedOperationException.class, () -> map.putIfAbsent(key(2), loc(2)));
			assertThrows(UnsupportedOperationException.class, () -> map.replace(key(1), loc(1)));
		}

		@Test
		@DisplayName("every inherited default mutator throws regardless of the map's current state")
		void shouldThrowOnDefaultMutatorsRegardlessOfState() {
			final OffsetLocationChampMap map = mapOf(1, 2);
			// each overridden java.util.Map default mutator must throw unconditionally, including the
			// no-op-shaped calls (absent/present key, mismatched value) the JDK defaults would short-circuit
			assertThrows(UnsupportedOperationException.class, () -> map.replace(key(1), loc(1), loc(2)));
			assertThrows(UnsupportedOperationException.class, () -> map.replace(key(9), loc(1), loc(2)));
			assertThrows(UnsupportedOperationException.class, () -> map.remove(key(1), loc(1)));
			assertThrows(UnsupportedOperationException.class, () -> map.remove(key(1), loc(9)));
			assertThrows(UnsupportedOperationException.class, () -> map.compute(key(1), (k, v) -> loc(3)));
			assertThrows(UnsupportedOperationException.class, () -> map.compute(key(9), (k, v) -> null));
			assertThrows(UnsupportedOperationException.class, () -> map.computeIfAbsent(key(1), k -> loc(9)));
			assertThrows(UnsupportedOperationException.class, () -> map.computeIfAbsent(key(9), k -> loc(9)));
			assertThrows(UnsupportedOperationException.class, () -> map.computeIfPresent(key(1), (k, v) -> loc(3)));
			assertThrows(UnsupportedOperationException.class, () -> map.computeIfPresent(key(9), (k, v) -> loc(3)));
			assertThrows(UnsupportedOperationException.class, () -> map.merge(key(1), loc(1), (a, b) -> a));
			assertThrows(UnsupportedOperationException.class, () -> map.merge(key(9), loc(9), (a, b) -> a));
			assertThrows(UnsupportedOperationException.class, () -> map.replaceAll((k, v) -> v));
			// replaceAll must throw even on an empty map where the JDK default never invokes the function
			assertThrows(
				UnsupportedOperationException.class,
				() -> OffsetLocationChampMap.empty().replaceAll((k, v) -> v));
		}

		@Test
		@DisplayName("equality is reflexive and an empty map equals the empty map")
		void shouldBeReflexiveAndHandleEmptyEquality() {
			final OffsetLocationChampMap map = mapOf(1, 2, 3);
			assertEquals(map, map);
			assertEquals(OffsetLocationChampMap.empty(), OffsetLocationChampMap.empty());
			assertNotEquals(OffsetLocationChampMap.empty(), mapOf(1));
			assertNotEquals(mapOf(1), OffsetLocationChampMap.empty());
		}

		@Test
		@DisplayName("a map is never equal to a non-Map object")
		void shouldNotEqualNonMapObject() {
			final OffsetLocationChampMap map = mapOf(1, 2, 3);
			assertNotEquals("not a map", map);
			assertNotEquals(map, "not a map");
			assertNotEquals(map, 1);
		}

		@Test
		@DisplayName("two maps of equal size differing only in one key are not equal")
		void shouldDistinguishSameSizeMapsWithDifferentKey() {
			final OffsetLocationChampMap a = mapOf(1, 2, 3);
			final OffsetLocationChampMap b = mapOf(1, 2, 4);
			assertEquals(a.size(), b.size());
			assertNotEquals(a, b);
			assertNotEquals(b, a);
		}

		@Test
		@DisplayName("equality and hash code are sensitive to the bound value")
		void shouldDistinguishOnValues() {
			final OffsetLocationChampMap a = mapOf(1, 2, 3);
			final OffsetLocationChampMap b = a.updated(key(2), new FileLocation(999_999, 17));
			assertNotEquals(a, b);
			assertNotEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("a same-size foreign map with one differing value is not equal")
		void shouldDistinguishForeignMapOnValue() {
			final OffsetLocationChampMap map = mapOf(1, 2, 3);
			// drives the foreign-Map entry-by-entry fallback of equals to its value-mismatch return
			final Map<RecordKey, FileLocation> foreign = new HashMap<>();
			foreign.put(key(1), loc(1));
			foreign.put(key(2), loc(2));
			foreign.put(key(3), new FileLocation(999_999, 17));
			assertNotEquals(map, foreign);
		}

		@Test
		@DisplayName("containsValue scans the values and rejects absent and non-FileLocation probes")
		void shouldReportContainsValue() {
			final OffsetLocationChampMap map = mapOf(1, 2, 3);
			assertTrue(map.containsValue(loc(2)));
			assertFalse(map.containsValue(new FileLocation(123, 456)));
			assertFalse(map.containsValue("not a location"));
			assertFalse(map.containsValue(null));
			assertFalse(OffsetLocationChampMap.empty().containsValue(loc(1)));
		}

		@Test
		@DisplayName("entrySet membership honours key type, presence and value match")
		void shouldHonourEntrySetContains() {
			final OffsetLocationChampMap map = mapOf(1, 2, 3);
			final Set<Entry<RecordKey, FileLocation>> entries = map.entrySet();
			assertTrue(entries.contains(new SimpleImmutableEntry<>(key(2), loc(2))));
			// present key but wrong value
			assertFalse(entries.contains(new SimpleImmutableEntry<>(key(2), new FileLocation(7, 7))));
			// absent key
			assertFalse(entries.contains(new SimpleImmutableEntry<>(key(999), loc(999))));
			// non-RecordKey key
			assertFalse(entries.contains(new SimpleImmutableEntry<>("not a key", loc(1))));
			// not an Entry at all
			assertFalse(entries.contains("not an entry"));
		}

		@Test
		@DisplayName("getOrDefault returns the stored value or the fallback for absent and foreign keys")
		void shouldReturnDefaultFromGetOrDefaultWhenAbsent() {
			final OffsetLocationChampMap map = mapOf(1, 2, 3);
			final FileLocation fallback = new FileLocation(-1, 1);
			assertEquals(loc(2), map.getOrDefault(key(2), fallback));
			assertEquals(fallback, map.getOrDefault(key(999), fallback));
			assertEquals(fallback, map.getOrDefault("not a key", fallback));
			assertEquals(fallback, map.getOrDefault(null, fallback));
		}
	}

	@Nested
	@DisplayName("findRecordLength primitive fast path")
	class FindRecordLength {

		@Test
		@DisplayName("returns the stored length, matching get().recordLength()")
		void shouldReturnStoredLength() {
			final OffsetLocationChampMap map = mapOf(1, 2, 3, 5000, 123456);
			for (final long pk : new long[]{1, 2, 3, 5000, 123456}) {
				assertEquals(loc(pk).recordLength(), map.findRecordLength(key(pk)));
				assertEquals(map.get(key(pk)).recordLength(), map.findRecordLength(key(pk)));
			}
		}

		@Test
		@DisplayName("returns the absent sentinel for missing keys and the empty map")
		void shouldReturnAbsentSentinel() {
			assertEquals(OffsetLocationChampMap.RECORD_LENGTH_ABSENT,
				OffsetLocationChampMap.empty().findRecordLength(key(1)));
			final OffsetLocationChampMap map = mapOf(1, 2, 3);
			assertEquals(OffsetLocationChampMap.RECORD_LENGTH_ABSENT, map.findRecordLength(key(999)));
			assertEquals(OffsetLocationChampMap.RECORD_LENGTH_ABSENT, map.findRecordLength(key((byte) 99, 1)));
		}
	}

	@Nested
	@DisplayName("null-argument handling")
	class NullHandling {

		@Test
		@DisplayName("updated rejects a null key or value")
		void shouldRejectNullKeyAndValueOnUpdated() {
			final OffsetLocationChampMap map = mapOf(1, 2, 3);
			assertThrows(NullPointerException.class, () -> map.updated(null, loc(1)));
			assertThrows(NullPointerException.class, () -> map.updated(key(1), null));
		}

		@Test
		@DisplayName("removed rejects a null key")
		void shouldRejectNullKeyOnRemoved() {
			final OffsetLocationChampMap map = mapOf(1, 2, 3);
			assertThrows(NullPointerException.class, () -> map.removed(null));
		}

		@Test
		@DisplayName("the of factory rejects a null key or value")
		void shouldRejectNullArgsOnFactoryOf() {
			assertThrows(NullPointerException.class, () -> OffsetLocationChampMap.of(null, loc(1)));
			assertThrows(NullPointerException.class, () -> OffsetLocationChampMap.of(key(1), null));
		}
	}

	@Nested
	@DisplayName("collection views and iterators")
	class Views {

		@Test
		@DisplayName("the key-set view rejects every mutation")
		void shouldRejectMutationThroughKeySet() {
			final OffsetLocationChampMap map = mapOf(1, 2, 3);
			final Set<RecordKey> keys = map.keySet();
			assertThrows(UnsupportedOperationException.class, () -> keys.add(key(9)));
			assertThrows(UnsupportedOperationException.class, () -> keys.remove(key(1)));
			assertThrows(UnsupportedOperationException.class, keys::clear);
		}

		@Test
		@DisplayName("the values view rejects every mutation")
		void shouldRejectMutationThroughValues() {
			final OffsetLocationChampMap map = mapOf(1, 2, 3);
			final Collection<FileLocation> values = map.values();
			assertThrows(UnsupportedOperationException.class, () -> values.add(loc(9)));
			assertThrows(UnsupportedOperationException.class, () -> values.remove(loc(1)));
			assertThrows(UnsupportedOperationException.class, values::clear);
		}

		@Test
		@DisplayName("the entry-set view rejects every mutation")
		void shouldRejectMutationThroughEntrySet() {
			final OffsetLocationChampMap map = mapOf(1, 2, 3);
			final Set<Entry<RecordKey, FileLocation>> entries = map.entrySet();
			assertThrows(
				UnsupportedOperationException.class,
				() -> entries.add(new SimpleImmutableEntry<>(key(9), loc(9))));
			assertThrows(
				UnsupportedOperationException.class,
				() -> entries.remove(new SimpleImmutableEntry<>(key(1), loc(1))));
			assertThrows(UnsupportedOperationException.class, entries::clear);
		}

		@Test
		@DisplayName("view iterators reject remove after advancing")
		void shouldRejectRemoveThroughViewIterators() {
			final OffsetLocationChampMap map = mapOf(1, 2, 3);

			final Iterator<RecordKey> keyIterator = map.keySet().iterator();
			keyIterator.next();
			assertThrows(UnsupportedOperationException.class, keyIterator::remove);

			final Iterator<FileLocation> valueIterator = map.values().iterator();
			valueIterator.next();
			assertThrows(UnsupportedOperationException.class, valueIterator::remove);

			final Iterator<Entry<RecordKey, FileLocation>> entryIterator = map.entrySet().iterator();
			entryIterator.next();
			assertThrows(UnsupportedOperationException.class, entryIterator::remove);
		}

		@Test
		@DisplayName("an exhausted view iterator throws NoSuchElementException")
		void shouldThrowNoSuchElementExceptionWhenIteratorExhausted() {
			final OffsetLocationChampMap singleton = OffsetLocationChampMap.of(key(1), loc(1));

			final Iterator<RecordKey> keyIterator = singleton.keySet().iterator();
			assertTrue(keyIterator.hasNext());
			keyIterator.next();
			assertFalse(keyIterator.hasNext());
			assertThrows(NoSuchElementException.class, keyIterator::next);

			final Iterator<FileLocation> valueIterator = singleton.values().iterator();
			valueIterator.next();
			assertThrows(NoSuchElementException.class, valueIterator::next);

			final Iterator<Entry<RecordKey, FileLocation>> entryIterator = singleton.entrySet().iterator();
			entryIterator.next();
			assertThrows(NoSuchElementException.class, entryIterator::next);

			// an empty map yields an immediately-exhausted iterator
			final Iterator<RecordKey> emptyIterator = OffsetLocationChampMap.empty().keySet().iterator();
			assertFalse(emptyIterator.hasNext());
			assertThrows(NoSuchElementException.class, emptyIterator::next);
		}
	}

	@Nested
	@DisplayName("toString")
	class StringRepresentation {

		@Test
		@DisplayName("renders entries between braces")
		void shouldRenderEntriesAndBraces() {
			assertEquals("{}", OffsetLocationChampMap.empty().toString());
			assertEquals(
				"{" + key(1) + "=" + loc(1) + "}",
				OffsetLocationChampMap.of(key(1), loc(1)).toString());

			final OffsetLocationChampMap map = mapOf(1, 2);
			final String rendered = map.toString();
			assertTrue(rendered.startsWith("{"));
			assertTrue(rendered.endsWith("}"));
			assertTrue(rendered.contains(key(1) + "=" + loc(1)));
			assertTrue(rendered.contains(key(2) + "=" + loc(2)));
		}
	}

	@Nested
	@DisplayName("builder")
	class BuilderBehaviour {

		@Test
		@DisplayName("reusing a builder after build does not corrupt the already-published map")
		void shouldReuseBuilderSafely() {
			final OffsetLocationChampMap.Builder builder = OffsetLocationChampMap.builder();
			for (long i = 0; i < 100; i++) {
				builder.add(key(i), loc(i));
			}
			final OffsetLocationChampMap first = builder.build();
			// keep mutating the same builder — the previously published map must stay intact (the
			// aliased / ensureUnaliased copy-on-reuse guard makes a one-time defensive copy)
			for (long i = 100; i < 200; i++) {
				builder.add(key(i), loc(i));
			}
			final OffsetLocationChampMap second = builder.build();

			assertEquals(100, first.size());
			assertEquals(200, second.size());
			for (long i = 0; i < 100; i++) {
				assertEquals(loc(i), first.get(key(i)));
				assertEquals(loc(i), second.get(key(i)));
			}
			for (long i = 100; i < 200; i++) {
				assertFalse(first.containsKey(key(i)));
				assertTrue(second.containsKey(key(i)));
			}
		}

		@Test
		@DisplayName("building from an empty builder yields the shared empty singleton")
		void shouldReturnSharedEmptyFromEmptyBuilder() {
			final OffsetLocationChampMap built = OffsetLocationChampMap.builder().build();
			assertTrue(built.isEmpty());
			assertSame(OffsetLocationChampMap.empty(), built);
		}
	}

	@Nested
	@DisplayName("factory construction")
	class Factories {

		@Test
		@DisplayName("from returns the same instance when the source is already this map type")
		void shouldReturnSameInstanceWhenSourceIsAlreadyThisType() {
			final OffsetLocationChampMap source = mapOf(1, 2, 3);
			assertSame(source, OffsetLocationChampMap.from(source));
		}

		@Test
		@DisplayName("from an empty source yields the shared empty map")
		void shouldBuildEmptyMapFromEmptySource() {
			final OffsetLocationChampMap built = OffsetLocationChampMap.from(new HashMap<>());
			assertTrue(built.isEmpty());
			assertSame(OffsetLocationChampMap.empty(), built);
		}
	}

	@Nested
	@DisplayName("generational HashMap-oracle fuzz")
	class GenerationalFuzz {

		@Test
		@DisplayName("random updated/removed stay consistent with a HashMap oracle and retained snapshots stay immutable")
		void shouldStayConsistentWithOracle() {
			final Random random = new Random(-1337);
			final HashMap<RecordKey, FileLocation> oracle = new HashMap<>();
			OffsetLocationChampMap map = OffsetLocationChampMap.empty();

			// keep a few historical (snapshot, oracle-copy) pairs and re-verify them after later mutations
			final List<OffsetLocationChampMap> snapshots = new ArrayList<>();
			final List<Map<RecordKey, FileLocation>> snapshotOracles = new ArrayList<>();

			for (int op = 0; op < 50_000; op++) {
				final long pk = random.nextInt(4_000);
				final byte type = (byte) random.nextInt(3);
				final RecordKey k = key(type, pk);
				final boolean remove = random.nextInt(100) < 35 && oracle.containsKey(k);

				if (remove) {
					oracle.remove(k);
					map = map.removed(k);
				} else {
					final FileLocation v = new FileLocation(random.nextInt(1_000_000), random.nextInt(8192) + 1);
					oracle.put(k, v);
					map = map.updated(k, v);
				}

				assertEquals(oracle.size(), map.size());

				if (op % 5_000 == 0) {
					// full structural equivalence check against the oracle
					assertEquals(oracle, map);
					assertEquals(map, oracle);
					// canonical form: a from-scratch build of the same content must be structurally equal
					assertEquals(OffsetLocationChampMap.from(oracle), map);
					// retain this snapshot for later immutability verification
					snapshots.add(map);
					snapshotOracles.add(new HashMap<>(oracle));
				}
			}

			// every retained snapshot must still equal its own oracle copy despite all later mutations
			for (int i = 0; i < snapshots.size(); i++) {
				assertEquals(snapshotOracles.get(i), snapshots.get(i),
					"retained snapshot #" + i + " was mutated by later operations");
			}
		}
	}

	/**
	 * Constructs `count` distinct primary keys (under {@link #TYPE}) that are guaranteed to land in the
	 * same hash-collision bucket. The map hashes a key as `improve(31 * recordType +
	 * Long.hashCode(primaryKey))`, so any two keys with the same {@link Long#hashCode(long)} collide on
	 * the full 32-bit improved hash. `pk_n = (n << 32) | ((base ^ n) & 0xFFFFFFFF)` has
	 * `Long.hashCode = (base ^ n) ^ n = base` for every `n`, while the distinct high words keep the
	 * longs distinct — a deterministic collision family of any size.
	 */
	@Nonnull
	private static long[] collidingPrimaryKeys(int count) {
		final int base = 0x51517;
		final long[] out = new long[count];
		for (int n = 0; n < count; n++) {
			out[n] = ((long) n << 32) | ((base ^ n) & 0xFFFFFFFFL);
		}
		return out;
	}
}
