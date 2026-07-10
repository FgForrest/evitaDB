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

package io.evitadb.dataType.champ;

import io.evitadb.dataType.champ.ChampMap.Builder;
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

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.DATA_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the correctness of the {@link ChampMap} clean-room CHAMP implementation. The suite is
 * adapted from the Scala standard-library {@code ChampMapSmokeTest} (whose primary purpose is to
 * exercise the canonicalization-on-delete logic — the sharp edge of CHAMP) and extended with
 * {@link java.util.Map} contract checks, insertion-order canonical-form proofs, hash-collision
 * scenarios and {@link ChampMap#merged} coverage.
 *
 * The canonicalization checks rely on {@link ChampMap#equals(Object)} being a *structural*
 * comparison of the underlying tries: because CHAMP keeps a canonical form, two maps with equal
 * content must have byte-identical structure, so a map produced by a delete must equal the same map
 * built from scratch only if the deletion correctly re-canonicalized the trie.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("CHAMP immutable map")
@Tag(CONTRACT)
@Tag(DATA_TYPE)
class ChampMapTest {

	/**
	 * Key (and value) type with a hash code independent of its identity, used to force deliberate
	 * hash collisions when testing the {@link java.util.Map}-collision handling.
	 */
	private record CustomHashInt(int value, int hash) {

		@Override
		public int hashCode() {
			return this.hash;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			return o instanceof CustomHashInt that && this.hash == that.hash && this.value == that.value;
		}
	}

	@Nonnull
	private static CustomHashInt v(int value, int hash) {
		return new CustomHashInt(value, hash);
	}

	@Nonnull
	private static ChampMap<Integer, Integer> mapOf(@Nonnull int... keys) {
		ChampMap<Integer, Integer> map = ChampMap.empty();
		for (final int key : keys) {
			map = map.updated(key, key);
		}
		return map;
	}

	@Nested
	@DisplayName("basic read/write operations")
	class BasicOperations {

		@Test
		@DisplayName("empty map has no entries")
		void shouldStartEmpty() {
			final ChampMap<Integer, Integer> map = ChampMap.empty();
			assertTrue(map.isEmpty());
			assertEquals(0, map.size());
			assertNull(map.get(1));
			assertFalse(map.containsKey(1));
			assertSame(ChampMap.empty(), ChampMap.<Integer, Integer>empty());
		}

		@Test
		@DisplayName("single entry created via factory is retrievable")
		void shouldCreateSingleton() {
			final ChampMap<Integer, Integer> map = ChampMap.of(63, 65);
			assertEquals(1, map.size());
			assertTrue(map.containsKey(63));
			assertEquals(65, map.get(63));
		}

		@Test
		@DisplayName("removing the only entry yields the empty map")
		void shouldRemoveFromSingleton() {
			final ChampMap<Integer, Integer> map = ChampMap.of(63, 65);
			final ChampMap<Integer, Integer> result = map.removed(63);
			assertTrue(result.isEmpty());
			assertFalse(result.containsKey(63));
			assertEquals(ChampMap.empty(), result);
		}

		@Test
		@DisplayName("updated/removed do not mutate the receiver (persistence)")
		void shouldBePersistent() {
			final ChampMap<Integer, Integer> base = mapOf(1, 2, 3);
			final ChampMap<Integer, Integer> added = base.updated(4, 4);
			final ChampMap<Integer, Integer> removed = base.removed(2);

			assertEquals(3, base.size());
			assertEquals(4, added.size());
			assertEquals(2, removed.size());
			assertFalse(base.containsKey(4));
			assertTrue(base.containsKey(2));
		}

		@Test
		@DisplayName("removing an absent key returns the same instance")
		void shouldReturnSameInstanceOnNoOpRemove() {
			final ChampMap<Integer, Integer> map = mapOf(1, 2, 3);
			assertSame(map, map.removed(999));
		}

		@Test
		@DisplayName("re-putting the identical value instance returns the same map")
		void shouldReturnSameInstanceOnIdenticalValue() {
			final String value = "v";
			final ChampMap<Integer, String> map = ChampMap.<Integer, String>empty().updated(1, value);
			assertSame(map, map.updated(1, value));
		}

		@Test
		@DisplayName("re-putting an equal but distinct value instance produces a new map")
		void shouldReplaceEqualButDistinctValue() {
			@SuppressWarnings("StringOperationCanBeSimplified")
			final String v1 = new String("v");
			@SuppressWarnings("StringOperationCanBeSimplified")
			final String v2 = new String("v");
			final ChampMap<Integer, String> map = ChampMap.<Integer, String>empty().updated(1, v1);
			final ChampMap<Integer, String> updated = map.updated(1, v2);
			assertSame(v2, updated.get(1));
		}
	}

	@Nested
	@DisplayName("canonical form (insertion-order independence)")
	class CanonicalForm {

		@Test
		@DisplayName("maps built in different insertion orders are equal with equal hash codes")
		void shouldBuildCanonicalFormRegardlessOfInsertionOrder() {
			final ChampMap<Integer, Integer> res1 = ChampMap.<Integer, Integer>empty()
				.updated(63, 63).updated(64, 64).updated(32768, 32768)
				.updated(2147483647, 2147483647).updated(65536, 65536);
			final ChampMap<Integer, Integer> res2 = ChampMap.<Integer, Integer>empty()
				.updated(2147483647, 2147483647).updated(32768, 32768).updated(63, 63)
				.updated(64, 64).updated(65536, 65536);

			for (final int key : new int[]{63, 64, 32768, 65536, 2147483647}) {
				assertTrue(res1.containsKey(key));
				assertTrue(res2.containsKey(key));
			}
			assertEquals(res1, res2);
			assertEquals(res1.hashCode(), res2.hashCode());
		}

		@Test
		@DisplayName("random insertion orders all converge to one canonical structure")
		void shouldConvergeToCanonicalFormForRandomOrders() {
			final Random random = new Random(42);
			final int[] keys = new int[200];
			for (int i = 0; i < keys.length; i++) {
				keys[i] = random.nextInt(1_000_000);
			}
			final ChampMap<Integer, Integer> reference = mapOf(keys);

			for (int attempt = 0; attempt < 25; attempt++) {
				final List<Integer> shuffled = new ArrayList<>();
				for (final int key : keys) {
					shuffled.add(key);
				}
				Collections.shuffle(shuffled, random);
				ChampMap<Integer, Integer> map = ChampMap.empty();
				for (final int key : shuffled) {
					map = map.updated(key, key);
				}
				assertEquals(reference, map);
				assertEquals(reference.hashCode(), map.hashCode());
			}
		}

		@Test
		@DisplayName("a map built via the Builder equals the same map built via updated")
		void shouldMatchBuilderAndPersistentConstruction() {
			final Builder<Integer, Integer> builder = ChampMap.builder();
			for (int i = 0; i < 500; i++) {
				builder.add(i, i * 2);
			}
			final ChampMap<Integer, Integer> built = builder.build();

			ChampMap<Integer, Integer> persistent = ChampMap.empty();
			for (int i = 0; i < 500; i++) {
				persistent = persistent.updated(i, i * 2);
			}
			assertEquals(persistent, built);
			assertEquals(persistent.hashCode(), built.hashCode());
		}
	}

	@Nested
	@DisplayName("canonicalization on delete")
	class CanonicalizationOnDelete {

		@Test
		@DisplayName("delete that collapses a sub-node re-canonicalizes (begin)")
		void shouldCompactFromBeginUponDelete() {
			final ChampMap<Integer, Integer> res1 = mapOf(1, 2);
			final ChampMap<Integer, Integer> res2 = res1.updated(32769, 32769).removed(2);
			// removing key 2 must leave a canonical {1, 32769} identical to one built from scratch
			assertEquals(mapOf(1, 32769), res2);
			assertNotEquals(res1, res2);
		}

		@Test
		@DisplayName("delete from the middle of a node re-canonicalizes")
		void shouldCompactFromMiddleUponDelete() {
			final ChampMap<Integer, Integer> res1 = mapOf(1, 2, 65, 66);
			final ChampMap<Integer, Integer> res2 = res1.updated(32769, 32769).removed(66);
			assertEquals(mapOf(1, 2, 65, 32769), res2);
			assertNotEquals(res1, res2);
		}

		@Test
		@DisplayName("collapsing a hash-collision bucket back to an inline entry (single bucket)")
		void shouldCompactHashCollisionNode1() {
			final CustomHashInt v11h1 = v(11, 1);
			final CustomHashInt v12h1 = v(12, 1);
			final CustomHashInt v32769 = v(32769, 32769);

			final ChampMap<CustomHashInt, CustomHashInt> res1 =
				ChampMap.<CustomHashInt, CustomHashInt>empty().updated(v11h1, v11h1).updated(v12h1, v12h1);
			assertTrue(res1.containsKey(v11h1));
			assertTrue(res1.containsKey(v12h1));

			final ChampMap<CustomHashInt, CustomHashInt> res2 = res1.removed(v12h1);
			assertTrue(res2.containsKey(v11h1));
			assertEquals(ChampMap.of(v11h1, v11h1), res2);

			final ChampMap<CustomHashInt, CustomHashInt> res3 = res1.removed(v11h1);
			assertTrue(res3.containsKey(v12h1));
			assertEquals(ChampMap.of(v12h1, v12h1), res3);

			final ChampMap<CustomHashInt, CustomHashInt> resX = res1.updated(v32769, v32769).removed(v12h1);
			assertTrue(resX.containsKey(v11h1));
			assertTrue(resX.containsKey(v32769));
			assertNotEquals(res1, resX);
		}

		@Test
		@DisplayName("removing a colliding key while a non-colliding neighbour remains inlines the survivor")
		void shouldCompactHashCollisionNode2() {
			final CustomHashInt v32769a = v(32769 * 10 + 1, 32769);
			final CustomHashInt v32769b = v(32769 * 10 + 2, 32769);
			final CustomHashInt v1h1 = v(1, 1);

			final ChampMap<CustomHashInt, CustomHashInt> res1 =
				ChampMap.<CustomHashInt, CustomHashInt>empty().updated(v32769a, v32769a).updated(v32769b, v32769b);
			assertEquals(2, res1.size());

			final ChampMap<CustomHashInt, CustomHashInt> res2 = res1.updated(v1h1, v1h1);
			assertEquals(3, res2.size());

			final ChampMap<CustomHashInt, CustomHashInt> res3 = res2.removed(v32769b);
			assertEquals(2, res3.size());
			assertTrue(res3.containsKey(v1h1));
			assertTrue(res3.containsKey(v32769a));

			final ChampMap<CustomHashInt, CustomHashInt> expected =
				ChampMap.<CustomHashInt, CustomHashInt>empty().updated(v1h1, v1h1).updated(v32769a, v32769a);
			assertEquals(expected, res3);
		}

		@Test
		@DisplayName("removing the non-colliding neighbour restores the original bucket exactly")
		void shouldCompactHashCollisionNode3() {
			final CustomHashInt v32769a = v(32769 * 10 + 1, 32769);
			final CustomHashInt v32769b = v(32769 * 10 + 2, 32769);
			final CustomHashInt v1h1 = v(1, 1);

			final ChampMap<CustomHashInt, CustomHashInt> res1 =
				ChampMap.<CustomHashInt, CustomHashInt>empty().updated(v32769a, v32769a).updated(v32769b, v32769b);
			final ChampMap<CustomHashInt, CustomHashInt> res2 = res1.updated(v1h1, v1h1);
			final ChampMap<CustomHashInt, CustomHashInt> res3 = res2.removed(v1h1);

			assertEquals(2, res3.size());
			assertTrue(res3.containsKey(v32769a));
			assertTrue(res3.containsKey(v32769b));
			assertEquals(res1, res3);
		}

		@Test
		@DisplayName("removing a neighbour at a different prefix restores the original bucket")
		void shouldCompactHashCollisionNode4() {
			final CustomHashInt v32769a = v(32769 * 10 + 1, 32769);
			final CustomHashInt v32769b = v(32769 * 10 + 2, 32769);
			final CustomHashInt v5h5 = v(5, 5);

			final ChampMap<CustomHashInt, CustomHashInt> res1 =
				ChampMap.<CustomHashInt, CustomHashInt>empty().updated(v32769a, v32769a).updated(v32769b, v32769b);
			final ChampMap<CustomHashInt, CustomHashInt> res2 = res1.updated(v5h5, v5h5);
			final ChampMap<CustomHashInt, CustomHashInt> res3 = res2.removed(v5h5);

			assertEquals(2, res3.size());
			assertTrue(res3.containsKey(v32769a));
			assertTrue(res3.containsKey(v32769b));
			assertEquals(res1, res3);
		}

		@Test
		@DisplayName("many fully-colliding keys can be added and removed in any order")
		void shouldHandleManyCollidingKeys() {
			final int collisions = 50;
			final List<CustomHashInt> keys = new ArrayList<>(collisions);
			ChampMap<CustomHashInt, Integer> map = ChampMap.empty();
			for (int i = 0; i < collisions; i++) {
				final CustomHashInt key = v(i, 7);
				keys.add(key);
				map = map.updated(key, i);
			}
			assertEquals(collisions, map.size());
			for (int i = 0; i < collisions; i++) {
				assertEquals(i, map.get(keys.get(i)));
			}

			final Random random = new Random(1);
			Collections.shuffle(keys, random);
			int expectedSize = collisions;
			for (final CustomHashInt key : keys) {
				map = map.removed(key);
				expectedSize--;
				assertEquals(expectedSize, map.size());
				assertFalse(map.containsKey(key));
			}
			assertTrue(map.isEmpty());
		}

		@Test
		@DisplayName("lookups and a mid-bucket removal work in a large hash-collision bucket")
		void shouldSupportLookupsAcrossLargeCollisionBucket() {
			final int bucketSize = 40;
			final List<CustomHashInt> keys = new ArrayList<>(bucketSize);
			ChampMap<CustomHashInt, Integer> map = ChampMap.empty();
			for (int i = 0; i < bucketSize; i++) {
				// every key shares the same improved hash, collapsing into one collision bucket
				final CustomHashInt key = v(i, 7);
				keys.add(key);
				map = map.updated(key, i * 100);
			}
			assertEquals(bucketSize, map.size());

			// every key must be found by both get() and containsKey() inside the deep bucket
			for (int i = 0; i < bucketSize; i++) {
				final CustomHashInt key = keys.get(i);
				assertTrue(map.containsKey(key));
				assertEquals(i * 100, map.get(key));
			}
			// containsValue drives the value iterator through the whole bucket
			assertTrue(map.containsValue(0));
			assertTrue(map.containsValue((bucketSize - 1) * 100));
			assertFalse(map.containsValue(-1));

			// removing a key from the middle of the bucket must drop only that one entry
			final CustomHashInt middle = keys.get(bucketSize / 2);
			final ChampMap<CustomHashInt, Integer> afterRemoval = map.removed(middle);
			assertEquals(bucketSize - 1, afterRemoval.size());
			assertFalse(afterRemoval.containsKey(middle));
			for (int i = 0; i < bucketSize; i++) {
				final CustomHashInt key = keys.get(i);
				if (key.equals(middle)) {
					continue;
				}
				assertTrue(afterRemoval.containsKey(key));
				assertEquals(i * 100, afterRemoval.get(key));
			}
		}
	}

	@Nested
	@DisplayName("java.util.Map contract")
	class MapContract {

		@Test
		@DisplayName("iteration and views agree with a HashMap oracle")
		void shouldMatchHashMapOracle() {
			final Random random = new Random(7);
			final Map<Integer, Integer> oracle = new HashMap<>();
			ChampMap<Integer, Integer> map = ChampMap.empty();
			for (int i = 0; i < 1000; i++) {
				final int key = random.nextInt(500);
				final int value = random.nextInt();
				oracle.put(key, value);
				map = map.updated(key, value);
			}

			assertEquals(oracle.size(), map.size());
			assertEquals(oracle, map);
			assertEquals(map, oracle);
			assertEquals(oracle.hashCode(), map.hashCode());

			assertEquals(oracle.keySet(), map.keySet());
			assertEquals(new HashSet<>(oracle.entrySet()), map.entrySet());

			final Set<Integer> collectedKeys = new HashSet<>();
			for (final Entry<Integer, Integer> entry : map.entrySet()) {
				assertEquals(oracle.get(entry.getKey()), entry.getValue());
				collectedKeys.add(entry.getKey());
			}
			assertEquals(oracle.keySet(), collectedKeys);

			for (final Entry<Integer, Integer> entry : oracle.entrySet()) {
				final Integer key = entry.getKey();
				assertTrue(map.containsKey(key));
				assertEquals(entry.getValue(), map.get(key));
			}
			for (final Integer value : oracle.values()) {
				assertTrue(map.containsValue(value));
			}
		}

		@Test
		@DisplayName("mutators throw UnsupportedOperationException")
		void shouldRejectMutators() {
			final ChampMap<Integer, Integer> map = mapOf(1, 2, 3);
			assertThrows(UnsupportedOperationException.class, () -> map.put(4, 4));
			assertThrows(UnsupportedOperationException.class, () -> map.remove(1));
			assertThrows(UnsupportedOperationException.class, () -> map.putAll(Map.of(5, 5)));
			assertThrows(UnsupportedOperationException.class, map::clear);
		}

		@Test
		@DisplayName("default Map mutators throw regardless of the map's current state")
		void shouldRejectAllDefaultMutatorsRegardlessOfState() {
			final ChampMap<Integer, Integer> map = ChampMap.<Integer, Integer>empty()
				.updated(1, 10).updated(2, 20);

			// each inherited java.util.Map default mutator must throw unconditionally, including the
			// no-op-shaped calls that the JDK defaults would otherwise short-circuit without throwing
			assertThrows(UnsupportedOperationException.class, () -> map.putIfAbsent(1, 999));
			assertThrows(UnsupportedOperationException.class, () -> map.putIfAbsent(3, 30));
			assertThrows(UnsupportedOperationException.class, () -> map.replace(1, 111));
			assertThrows(UnsupportedOperationException.class, () -> map.replace(99, 1));
			assertThrows(UnsupportedOperationException.class, () -> map.replace(1, 10, 111));
			assertThrows(UnsupportedOperationException.class, () -> map.replace(1, 999, 111));
			assertThrows(UnsupportedOperationException.class, () -> map.remove(1, 10));
			assertThrows(UnsupportedOperationException.class, () -> map.remove(1, 999));
			assertThrows(UnsupportedOperationException.class, () -> map.compute(1, (k, v) -> 111));
			assertThrows(UnsupportedOperationException.class, () -> map.compute(99, (k, v) -> null));
			assertThrows(UnsupportedOperationException.class, () -> map.computeIfAbsent(1, k -> 999));
			assertThrows(UnsupportedOperationException.class, () -> map.computeIfAbsent(3, k -> 30));
			assertThrows(UnsupportedOperationException.class, () -> map.computeIfPresent(1, (k, v) -> 111));
			assertThrows(UnsupportedOperationException.class, () -> map.computeIfPresent(99, (k, v) -> 1));
			assertThrows(UnsupportedOperationException.class, () -> map.merge(1, 5, (a, b) -> a + b));
			assertThrows(UnsupportedOperationException.class, () -> map.merge(99, 1, (a, b) -> a));
			assertThrows(UnsupportedOperationException.class, () -> map.replaceAll((k, v) -> v));
		}

		@Test
		@DisplayName("equality is sensitive to both keys and values")
		void shouldDistinguishOnValues() {
			final ChampMap<Integer, Integer> a = mapOf(1, 2, 3);
			final ChampMap<Integer, Integer> b = a.updated(2, 999);
			assertNotEquals(a, b);
			assertNotEquals(a.hashCode(), b.hashCode());
		}

		@Test
		@DisplayName("a map is never equal to a non-Map object")
		void shouldNotEqualNonMapObject() {
			final ChampMap<Integer, Integer> map = mapOf(1, 2, 3);
			assertNotEquals("not a map", map);
			assertNotEquals(map, 1);
		}

		@Test
		@DisplayName("two maps of equal size differing only in a key are not equal")
		void shouldDistinguishTwoChampMapsOfSameSizeWithDifferentContent() {
			final ChampMap<Integer, Integer> a = mapOf(1, 2, 3);
			final ChampMap<Integer, Integer> b = mapOf(1, 2, 4);
			assertEquals(a.size(), b.size());
			assertNotEquals(a, b);
			assertNotEquals(b, a);
		}

		@Test
		@DisplayName("equality is reflexive and the empty map equals itself")
		void shouldBeReflexiveAndHandleEmptyEquality() {
			final ChampMap<Integer, Integer> map = mapOf(1, 2, 3);
			assertEquals(map, map);
			assertEquals(ChampMap.<Integer, Integer>empty(), ChampMap.<Integer, Integer>empty());
			assertNotEquals(ChampMap.<Integer, Integer>empty(), mapOf(1));
			assertNotEquals(mapOf(1), ChampMap.<Integer, Integer>empty());
		}

		@Test
		@DisplayName("getOrDefault returns the stored value or the fallback for absent and null keys")
		void shouldReturnDefaultFromGetOrDefaultWhenAbsent() {
			final ChampMap<Integer, Integer> map = mapOf(1, 2, 3);
			// present key returns its stored value (which equals the key in mapOf)
			assertEquals(1, map.getOrDefault(1, -1));
			// absent key falls back to the supplied default
			assertEquals(-1, map.getOrDefault(999, -1));
			// a null probe routes through get(), which short-circuits to absent, so the default wins
			assertEquals(-1, map.getOrDefault(null, -1));
		}
	}

	@Nested
	@DisplayName("builder")
	class BuilderBehaviour {

		@Test
		@DisplayName("build, then reuse the builder without corrupting the published map")
		void shouldReuseBuilderSafely() {
			final Builder<Integer, Integer> builder = ChampMap.builder();
			for (int i = 0; i < 100; i++) {
				builder.add(i, i);
			}
			final ChampMap<Integer, Integer> first = builder.build();
			// keep mutating the same builder — the previously published map must stay intact
			for (int i = 100; i < 200; i++) {
				builder.add(i, i);
			}
			final ChampMap<Integer, Integer> second = builder.build();

			assertEquals(100, first.size());
			assertEquals(200, second.size());
			for (int i = 0; i < 100; i++) {
				assertEquals(i, first.get(i));
				assertEquals(i, second.get(i));
			}
			for (int i = 100; i < 200; i++) {
				assertFalse(first.containsKey(i));
				assertTrue(second.containsKey(i));
			}
		}

		@Test
		@DisplayName("builder supports interleaved add and remove")
		void shouldSupportRemoveInBuilder() {
			final Builder<Integer, Integer> builder = ChampMap.builder();
			for (int i = 0; i < 100; i++) {
				builder.add(i, i);
			}
			for (int i = 0; i < 100; i += 2) {
				builder.remove(i);
			}
			final ChampMap<Integer, Integer> map = builder.build();
			assertEquals(50, map.size());
			for (int i = 0; i < 100; i++) {
				assertEquals(i % 2 == 1, map.containsKey(i));
			}

			final ChampMap<Integer, Integer> oddsBuiltDirectly = mapOf(oddNumbers(100));
			assertEquals(oddsBuiltDirectly, map);
		}

		@Nonnull
		private static int[] oddNumbers(int upperBound) {
			final List<Integer> odds = new ArrayList<>();
			for (int i = 1; i < upperBound; i += 2) {
				odds.add(i);
			}
			final int[] result = new int[odds.size()];
			for (int i = 0; i < result.length; i++) {
				result[i] = odds.get(i);
			}
			return result;
		}
	}

	@Nested
	@DisplayName("merged (whole-map union)")
	class Merged {

		@Test
		@DisplayName("merging disjoint maps yields their union")
		void shouldUnionDisjointMaps() {
			final ChampMap<Integer, Integer> left = mapOf(1, 2, 3);
			final ChampMap<Integer, Integer> right = mapOf(4, 5, 6);
			final ChampMap<Integer, Integer> merged = left.merged(right, (l, r) -> l);
			assertEquals(mapOf(1, 2, 3, 4, 5, 6), merged);
		}

		@Test
		@DisplayName("merging with an empty map returns the other operand")
		void shouldShortCircuitEmptyOperands() {
			final ChampMap<Integer, Integer> map = mapOf(1, 2, 3);
			assertSame(map, map.merged(ChampMap.empty(), (l, r) -> l));
			assertSame(map, ChampMap.<Integer, Integer>empty().merged(map, (l, r) -> l));
		}

		@Test
		@DisplayName("resolver decides the surviving value on key conflicts")
		void shouldApplyResolverOnConflicts() {
			final ChampMap<Integer, Integer> left = mapOf(1, 2, 3).updated(10, 100);
			final ChampMap<Integer, Integer> right = mapOf(3, 4, 5).updated(10, 999);
			// keep the right-hand value on conflict
			final ChampMap<Integer, Integer> merged = left.merged(right, (l, r) -> r);

			assertEquals(999, merged.get(10));
			for (final int key : new int[]{1, 2, 3, 4, 5}) {
				assertTrue(merged.containsKey(key));
				assertEquals(key, merged.get(key));
			}
			// union of {1,2,3,10} and {3,4,5,10} → {1,2,3,4,5,10}
			assertEquals(6, merged.size());
		}

		@Test
		@DisplayName("merge result matches a HashMap oracle over random maps")
		void shouldMatchOracleOverRandomMaps() {
			final Random random = new Random(99);
			for (int round = 0; round < 50; round++) {
				final Map<Integer, Integer> leftOracle = new HashMap<>();
				final Map<Integer, Integer> rightOracle = new HashMap<>();
				ChampMap<Integer, Integer> left = ChampMap.empty();
				ChampMap<Integer, Integer> right = ChampMap.empty();
				for (int i = 0; i < 200; i++) {
					final int lk = random.nextInt(300);
					final int lv = random.nextInt();
					leftOracle.put(lk, lv);
					left = left.updated(lk, lv);
					final int rk = random.nextInt(300);
					final int rv = random.nextInt();
					rightOracle.put(rk, rv);
					right = right.updated(rk, rv);
				}

				// resolver keeps the left value; build the matching oracle (right then overlay left)
				final Map<Integer, Integer> mergedOracle = new HashMap<>(rightOracle);
				mergedOracle.putAll(leftOracle);

				final ChampMap<Integer, Integer> merged = left.merged(right, (l, r) -> l);
				assertEquals(mergedOracle, merged);
			}
		}

		@Test
		@DisplayName("merging collision buckets at the same path resolves overlap and keeps the rest")
		void shouldMergeCollisionBucketsAtSamePath() {
			// both maps carry a multi-key hash-collision bucket under the same improved hash (7)
			final CustomHashInt shared1 = v(1, 7);
			final CustomHashInt shared2 = v(2, 7);
			final CustomHashInt leftOnly = v(3, 7);
			final CustomHashInt rightOnly = v(4, 7);

			final ChampMap<CustomHashInt, Integer> left = ChampMap.<CustomHashInt, Integer>empty()
				.updated(shared1, 11).updated(shared2, 12).updated(leftOnly, 13);
			final ChampMap<CustomHashInt, Integer> right = ChampMap.<CustomHashInt, Integer>empty()
				.updated(shared1, 21).updated(shared2, 22).updated(rightOnly, 24);

			// resolver keeps the right value on conflict; oracle = right overlaid on left
			final ChampMap<CustomHashInt, Integer> merged = left.merged(right, (l, r) -> r);

			final Map<CustomHashInt, Integer> oracle = new HashMap<>();
			oracle.put(shared1, 11);
			oracle.put(shared2, 12);
			oracle.put(leftOnly, 13);
			oracle.putAll(Map.of(shared1, 21, shared2, 22, rightOnly, 24));

			assertEquals(4, merged.size());
			assertEquals(oracle, merged);
			assertEquals(21, merged.get(shared1));
			assertEquals(22, merged.get(shared2));
			assertEquals(13, merged.get(leftOnly));
			assertEquals(24, merged.get(rightOnly));
		}

		@Test
		@DisplayName("a resolver returning the left or right entry honours that choice")
		void shouldHonourResolverChoiceOfSuppliedEntry() {
			final ChampMap<Integer, Integer> left = mapOf(1, 2, 3).updated(10, 100);
			final ChampMap<Integer, Integer> right = mapOf(3, 4, 5).updated(10, 999);

			// resolver returning the left entry keeps the left value for the conflicting key 10
			final ChampMap<Integer, Integer> keepLeft = left.merged(right, (l, r) -> l);
			assertEquals(100, keepLeft.get(10));

			// resolver returning the right entry keeps the right value for the conflicting key 10
			final ChampMap<Integer, Integer> keepRight = left.merged(right, (l, r) -> r);
			assertEquals(999, keepRight.get(10));

			// the conflicting key itself survives unchanged regardless of the chosen side
			assertTrue(keepLeft.containsKey(10));
			assertTrue(keepRight.containsKey(10));
			assertEquals(6, keepLeft.size());
			assertEquals(6, keepRight.size());
		}
	}

	@Nested
	@DisplayName("survivor escalation on delete")
	class SurvivorEscalation {

		@Test
		@DisplayName("deleting one of two deeply-nested keys escalates the survivor across levels")
		void shouldEscalateSingleSurvivorThroughMultipleLevels() {
			// keys 0 and 941 share the first two 5-bit hash chunks (two trie levels), so they nest
			// two levels deep before diverging; key 1 sits at a different root slot, keeping the root
			// from collapsing entirely when the deep sub-trie is reduced to a single survivor
			final ChampMap<Integer, Integer> deep = ChampMap.<Integer, Integer>empty()
				.updated(0, 0).updated(941, 941).updated(1, 1);
			assertEquals(3, deep.size());

			final ChampMap<Integer, Integer> afterRemoval = deep.removed(941);
			assertEquals(2, afterRemoval.size());
			assertTrue(afterRemoval.containsKey(0));
			assertTrue(afterRemoval.containsKey(1));
			assertFalse(afterRemoval.containsKey(941));

			// the survivor must escalate so the result is byte-identical to a fresh build
			assertEquals(mapOf(0, 1), afterRemoval);
		}
	}

	@Nested
	@DisplayName("factory construction")
	class Factories {

		@Test
		@DisplayName("a map built from a plain map equals the same map built via updated")
		void shouldBuildFromPlainMap() {
			final Map<Integer, Integer> source = new HashMap<>();
			for (int i = 0; i < 200; i++) {
				source.put(i, i * 3);
			}
			final ChampMap<Integer, Integer> built = ChampMap.from(source);

			assertEquals(source.size(), built.size());
			for (final Entry<Integer, Integer> entry : source.entrySet()) {
				assertEquals(entry.getValue(), built.get(entry.getKey()));
			}
			assertEquals(source, built);
			assertEquals(built, source);
		}

		@Test
		@DisplayName("from returns the same instance when the source is already a ChampMap")
		void shouldReturnSameInstanceWhenSourceIsAlreadyChampMap() {
			final ChampMap<Integer, Integer> source = mapOf(1, 2, 3);
			assertSame(source, ChampMap.from(source));
		}

		@Test
		@DisplayName("from an empty source yields the shared empty map")
		void shouldBuildEmptyMapFromEmptySource() {
			final ChampMap<Integer, Integer> built = ChampMap.from(new HashMap<>());
			assertTrue(built.isEmpty());
			assertSame(ChampMap.<Integer, Integer>empty(), built);
		}
	}

	@Nested
	@DisplayName("null-argument handling")
	class NullHandling {

		@Test
		@DisplayName("updated rejects a null key or value")
		void shouldRejectNullKeyAndValueOnUpdated() {
			final ChampMap<Integer, Integer> map = mapOf(1, 2, 3);
			assertThrows(NullPointerException.class, () -> map.updated(null, 1));
			assertThrows(NullPointerException.class, () -> map.updated(1, null));
		}

		@Test
		@DisplayName("removed rejects a null key")
		void shouldRejectNullKeyOnRemoved() {
			final ChampMap<Integer, Integer> map = mapOf(1, 2, 3);
			assertThrows(NullPointerException.class, () -> map.removed(null));
		}

		@Test
		@DisplayName("the of factory rejects a null key or value")
		void shouldRejectNullArgsOnFactoryOf() {
			assertThrows(NullPointerException.class, () -> ChampMap.of(null, 1));
			assertThrows(NullPointerException.class, () -> ChampMap.of(1, null));
		}

		@Test
		@DisplayName("null probes return null or false on a populated and on an empty map")
		void shouldReturnNullOrFalseForNullProbe() {
			final ChampMap<Integer, Integer> map = mapOf(1, 2, 3);
			assertNull(map.get(null));
			assertFalse(map.containsKey(null));
			assertFalse(map.containsValue(null));

			final ChampMap<Integer, Integer> empty = ChampMap.empty();
			assertNull(empty.get(null));
			assertFalse(empty.containsKey(null));
			assertFalse(empty.containsValue(null));
			// a non-null probe on the empty map also short-circuits to absent
			assertNull(empty.get(1));
			assertFalse(empty.containsKey(1));
		}

		@Test
		@DisplayName("merged rejects a null operand or resolver")
		void shouldRejectNullArgsOnMerged() {
			final ChampMap<Integer, Integer> map = mapOf(1, 2, 3);
			assertThrows(NullPointerException.class, () -> map.merged(null, (l, r) -> l));
			assertThrows(NullPointerException.class, () -> map.merged(map, null));
		}
	}

	@Nested
	@DisplayName("collection views")
	class Views {

		@Test
		@DisplayName("key, value and entry views expose consistent size and membership")
		void shouldExposeKeySetValuesEntrySetConsistently() {
			final ChampMap<Integer, Integer> map = mapOf(1, 2, 3);
			final Set<Integer> keys = map.keySet();
			final Collection<Integer> values = map.values();
			final Set<Entry<Integer, Integer>> entries = map.entrySet();

			assertEquals(3, keys.size());
			assertEquals(3, values.size());
			assertEquals(3, entries.size());

			assertTrue(keys.contains(1));
			assertFalse(keys.contains(999));

			assertTrue(values.contains(2));
			assertFalse(values.contains(999));

			assertTrue(entries.contains(new SimpleImmutableEntry<>(1, 1)));
			assertFalse(entries.contains(new SimpleImmutableEntry<>(1, 999)));
			assertFalse(entries.contains("not an entry"));
			assertFalse(entries.contains(new SimpleImmutableEntry<>(null, 1)));
		}

		@Test
		@DisplayName("the values view iterates every value exactly once")
		void shouldIterateValuesView() {
			final ChampMap<Integer, Integer> map = ChampMap.<Integer, Integer>empty()
				.updated(1, 10).updated(2, 20).updated(3, 30);
			final List<Integer> collected = new ArrayList<>();
			for (final Integer value : map.values()) {
				collected.add(value);
			}
			Collections.sort(collected);
			assertEquals(List.of(10, 20, 30), collected);
		}

		@Test
		@DisplayName("an exhausted view iterator throws NoSuchElementException")
		void shouldThrowNoSuchElementExceptionWhenIteratorExhausted() {
			final ChampMap<Integer, Integer> singleton = ChampMap.of(1, 2);

			final Iterator<Integer> keyIterator = singleton.keySet().iterator();
			assertTrue(keyIterator.hasNext());
			keyIterator.next();
			assertFalse(keyIterator.hasNext());
			assertThrows(NoSuchElementException.class, keyIterator::next);

			final Iterator<Integer> valueIterator = singleton.values().iterator();
			valueIterator.next();
			assertThrows(NoSuchElementException.class, valueIterator::next);

			final Iterator<Entry<Integer, Integer>> entryIterator = singleton.entrySet().iterator();
			entryIterator.next();
			assertThrows(NoSuchElementException.class, entryIterator::next);

			// an empty map yields an immediately-exhausted iterator
			final Iterator<Integer> emptyIterator = ChampMap.<Integer, Integer>empty().keySet().iterator();
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
			assertEquals("{}", ChampMap.empty().toString());
			assertEquals("{1=2}", ChampMap.of(1, 2).toString());

			final ChampMap<Integer, Integer> map = ChampMap.<Integer, Integer>empty()
				.updated(1, 10).updated(2, 20);
			final String rendered = map.toString();
			assertTrue(rendered.startsWith("{"));
			assertTrue(rendered.endsWith("}"));
			assertTrue(rendered.contains("1=10"));
			assertTrue(rendered.contains("2=20"));
		}
	}

	@Nested
	@DisplayName("view and iterator immutability")
	class ViewImmutability {

		@Test
		@DisplayName("the key-set view rejects every mutation")
		void shouldRejectMutationThroughKeySet() {
			final ChampMap<Integer, Integer> map = mapOf(1, 2, 3);
			final Set<Integer> keys = map.keySet();
			// add() is rejected unconditionally; remove() of a present key and clear() on a
			// non-empty view deterministically reach the throwing AbstractCollection path
			assertThrows(UnsupportedOperationException.class, () -> keys.add(9));
			assertThrows(UnsupportedOperationException.class, () -> keys.remove(1));
			assertThrows(UnsupportedOperationException.class, keys::clear);
		}

		@Test
		@DisplayName("the values view rejects every mutation")
		void shouldRejectMutationThroughValues() {
			final ChampMap<Integer, Integer> map = mapOf(1, 2, 3);
			final Collection<Integer> values = map.values();
			assertThrows(UnsupportedOperationException.class, () -> values.add(9));
			assertThrows(UnsupportedOperationException.class, () -> values.remove(1));
			assertThrows(UnsupportedOperationException.class, values::clear);
		}

		@Test
		@DisplayName("the entry-set view rejects every mutation")
		void shouldRejectMutationThroughEntrySet() {
			final ChampMap<Integer, Integer> map = mapOf(1, 2, 3);
			final Set<Entry<Integer, Integer>> entries = map.entrySet();
			final Entry<Integer, Integer> present = new SimpleImmutableEntry<>(1, 1);
			assertThrows(
				UnsupportedOperationException.class,
				() -> entries.add(new SimpleImmutableEntry<>(9, 9)));
			assertThrows(UnsupportedOperationException.class, () -> entries.remove(present));
			assertThrows(UnsupportedOperationException.class, entries::clear);
		}

		@Test
		@DisplayName("view iterators reject remove after advancing")
		void shouldRejectRemoveThroughViewIterators() {
			final ChampMap<Integer, Integer> map = mapOf(1, 2, 3);

			final Iterator<Integer> keyIterator = map.keySet().iterator();
			keyIterator.next();
			assertThrows(UnsupportedOperationException.class, keyIterator::remove);

			final Iterator<Integer> valueIterator = map.values().iterator();
			valueIterator.next();
			assertThrows(UnsupportedOperationException.class, valueIterator::remove);

			final Iterator<Entry<Integer, Integer>> entryIterator = map.entrySet().iterator();
			entryIterator.next();
			assertThrows(UnsupportedOperationException.class, entryIterator::remove);
		}
	}

	@Nested
	@DisplayName("maximum trie depth and hash boundaries")
	class TrieDepthAndBoundaries {

		@Test
		@DisplayName("plain integer keys that diverge only at the deepest chunk escalate correctly")
		void shouldDivergePlainIntKeysAtMaximumTrieDepth() {
			// the improved hashes of 7853 and 131404 agree on the first six 5-bit chunks and differ
			// only at the seventh (shift = 30), forcing the deepest BitmapIndexedMapNode split that
			// real avalanche math (not custom-hash keys) can reach; key 1 holds a separate root slot
			assertDeepestChunkDivergence(7853, 131404, 1);
			// a second, independent pair exercising the same deepest-level split
			assertDeepestChunkDivergence(8109, 131660, 2);
		}

		@Test
		@DisplayName("keys at the hash-code extremes are stored, retrieved and removed canonically")
		void shouldHandleHashCodeBoundaryKeys() {
			// these keys' hashCode() values are the integer extremes themselves
			final int[] boundaryKeys = {Integer.MIN_VALUE, Integer.MAX_VALUE, -1, 0};
			final ChampMap<Integer, Integer> map = mapOf(boundaryKeys);
			assertEquals(boundaryKeys.length, map.size());
			for (final int key : boundaryKeys) {
				assertTrue(map.containsKey(key));
				assertEquals(key, map.get(key));
			}

			// removing each extreme key must re-canonicalize to the exact structure built from the
			// surviving keys alone
			for (final int removedKey : boundaryKeys) {
				final ChampMap<Integer, Integer> afterRemoval = map.removed(removedKey);
				assertFalse(afterRemoval.containsKey(removedKey));
				assertEquals(boundaryKeys.length - 1, afterRemoval.size());
				assertEquals(mapOf(without(boundaryKeys, removedKey)), afterRemoval);
			}
		}

		/**
		 * Inserts `deepA` and `deepB` (whose improved hashes diverge only at the deepest trie chunk)
		 * plus an unrelated root-slot `other` key, then removes `deepB` and asserts the lone survivor
		 * escalates so the result is byte-identical to a map built only from `deepA` and `other`.
		 */
		private static void assertDeepestChunkDivergence(int deepA, int deepB, int other) {
			final ChampMap<Integer, Integer> map = ChampMap.<Integer, Integer>empty()
				.updated(deepA, deepA).updated(deepB, deepB).updated(other, other);
			assertEquals(3, map.size());
			assertTrue(map.containsKey(deepA));
			assertTrue(map.containsKey(deepB));
			assertTrue(map.containsKey(other));

			final ChampMap<Integer, Integer> afterRemoval = map.removed(deepB);
			assertEquals(2, afterRemoval.size());
			assertTrue(afterRemoval.containsKey(deepA));
			assertTrue(afterRemoval.containsKey(other));
			assertFalse(afterRemoval.containsKey(deepB));
			assertEquals(mapOf(deepA, other), afterRemoval);
		}

		/** Returns a copy of `keys` without the single `excluded` element. */
		@Nonnull
		private static int[] without(@Nonnull int[] keys, int excluded) {
			final int[] result = new int[keys.length - 1];
			int cursor = 0;
			for (final int key : keys) {
				if (key != excluded) {
					result[cursor++] = key;
				}
			}
			return result;
		}
	}

	@Nested
	@DisplayName("structural invariants under random churn")
	class StructuralInvariants {

		@Test
		@DisplayName("size, distinct-key count and iteration count stay consistent across random ops")
		void shouldPreserveSizeAndIterationInvariantsAcrossRandomOps() {
			final Random random = new Random(123);
			final Map<Integer, Integer> oracle = new HashMap<>();
			ChampMap<Integer, Integer> map = ChampMap.empty();

			for (int op = 0; op < 4000; op++) {
				final int key = random.nextInt(400);
				if (random.nextInt(100) < 40) {
					map = map.removed(key);
					oracle.remove(key);
				} else {
					final int value = random.nextInt();
					map = map.updated(key, value);
					oracle.put(key, value);
				}

				if (op % 200 == 0) {
					assertInvariants(map, oracle);
				}
			}
			assertInvariants(map, oracle);
		}

		/**
		 * Asserts the three structural invariants that would be violated by a lost or duplicated
		 * entry: cardinality matches the oracle, every key is iterated exactly once (no duplicate),
		 * and the iteration count equals the reported size.
		 */
		private static void assertInvariants(
			@Nonnull ChampMap<Integer, Integer> map, @Nonnull Map<Integer, Integer> oracle) {
			assertEquals(oracle.size(), map.size());

			final Set<Integer> iteratedKeys = new HashSet<>();
			int iterated = 0;
			for (final Integer key : map.keySet()) {
				assertTrue(iteratedKeys.add(key), "Key " + key + " was iterated more than once!");
				iterated++;
			}
			assertEquals(map.size(), iterated, "Iteration count diverged from size!");
			assertEquals(oracle, map);
		}
	}
}
