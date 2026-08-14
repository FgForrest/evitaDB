/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.utils.JolHeapSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.function.ToLongFunction;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link ChampMap#getHeapSizeInBytes} against a JOL walk of the same trie.
 *
 * # Why the empty map is a shared root everywhere
 *
 * Every assertion below names {@link ChampMap#empty()} as a borrowed root. That single object graph — the empty map,
 * its node and the two zero-length arrays behind it — is a JVM-wide singleton that {@link ChampMap.Builder#build()}
 * hands back for every empty result, and the two arrays additionally turn up inside **live** nodes that carry
 * sub-nodes but no inlined payload. Naming it makes the ownership claim executable: the map under test is charged for
 * everything it reaches except that.
 *
 * # Keys chosen to stay out of the autobox cache
 *
 * Boxed values above `127` throughout, for the reason spelled out in `TransactionalMapHeapSizeTest`: inside the cache
 * two equal `Integer`s are the same instance, so a JOL walk would count one where the arithmetic charges two, and the
 * assertions would be measuring the JVM's interning instead of this class.
 *
 * @author Claude (heap-size verification), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(DATA_TYPE)
@DisplayName("CHAMP map heap-size reporting")
class ChampMapHeapSizeTest {
	/**
	 * First key value — comfortably above the autobox cache ceiling so every boxed key is its own instance.
	 */
	private static final int FIRST_KEY = 1_000;

	/**
	 * First value — disjoint from the key range so no key and value can ever be the same boxed instance.
	 */
	private static final int FIRST_VALUE = 500_000;

	/**
	 * Prices a boxed value at its true footprint.
	 */
	private static final ToLongFunction<Object> OWNED = JolHeapSize::ownedSize;

	/**
	 * Prices nothing — the spine-only case, for a trie that merely borrows what it points at.
	 */
	private static final ToLongFunction<Object> NOTHING = value -> 0L;

	/**
	 * Builds a trie of `entries` distinct boxed key/value pairs, none of which can come from the autobox cache.
	 *
	 * @param entries how many pairs to add
	 * @return the finished immutable map
	 */
	@Nonnull
	private static ChampMap<Integer, Integer> champOf(int entries) {
		final Map<Integer, Integer> source = new HashMap<>();
		for (int i = 0; i < entries; i++) {
			source.put(FIRST_KEY + i, FIRST_VALUE + i);
		}
		return ChampMap.from(source);
	}

	/**
	 * Measures a trie the way its owner does, excluding the empty-map singleton it shares with every other trie.
	 *
	 * @param map the map to measure
	 * @return the measured owned footprint in bytes
	 */
	private static long measured(@Nonnull ChampMap<?, ?> map) {
		return JolHeapSize.ownedSize(map, ChampMap.empty());
	}

	/**
	 * A key whose hash is fixed, so every instance lands in the same hash-collision bucket however deep the trie
	 * grows. Equality is by payload, so the keys remain distinct entries.
	 *
	 * A plain class rather than a record, against this project's usual preference: JOL cannot size a record at all —
	 * `Unsafe.objectFieldOffset` refuses outright on a record class — so a record key would make the fixture
	 * unmeasurable rather than merely awkward.
	 */
	private static final class CollidingKey {
		private final int payload;

		private CollidingKey(int payload) {
			this.payload = payload;
		}

		@Override
		public int hashCode() {
			// deliberately constant: it is the whole point of this key
			return 42;
		}

		@Override
		public boolean equals(@Nullable Object o) {
			return o instanceof CollidingKey that && that.payload == this.payload;
		}
	}

	@Nested
	@DisplayName("matches the measured heap")
	class MeasuredExactness {

		@Test
		void shouldMatchMeasuredHeapForASingleEntry() {
			final ChampMap<Integer, Integer> map = champOf(1);

			assertEquals(measured(map), map.getHeapSizeInBytes(OWNED, OWNED));
		}

		@Test
		void shouldMatchMeasuredHeapAcrossTrieDepths() {
			// 33 entries force a second level, 1_089 a third - so the walk is exercised on tries whose nodes carry
			// inlined payload, sub-nodes, and both at once
			for (int entries : new int[]{2, 8, 33, 100, 1_089, 5_000}) {
				final ChampMap<Integer, Integer> map = champOf(entries);

				assertEquals(
					measured(map),
					map.getHeapSizeInBytes(OWNED, OWNED),
					"figure diverged at " + entries + " entries"
				);
			}
		}

		@Test
		void shouldMatchMeasuredHeapForACollisionBucket() {
			// every key hashes identically, so the trie degenerates into one hash-collision leaf - a node shape with
			// no originalHashes array and no sub-nodes, priced by its own arithmetic
			final Map<CollidingKey, Integer> source = new HashMap<>();
			for (int i = 0; i < 8; i++) {
				source.put(new CollidingKey(i), FIRST_VALUE + i);
			}
			final ChampMap<CollidingKey, Integer> map = ChampMap.from(source);

			assertEquals(8, map.size());
			assertEquals(measured(map), map.getHeapSizeInBytes(OWNED, OWNED));
		}

		@Test
		void shouldMatchMeasuredHeapWhenCollisionsSitBesideOrdinaryEntries() {
			// the mixed shape: a trie whose ordinary bitmap-indexed nodes hold a collision bucket among their children
			final Map<Object, Integer> source = new HashMap<>();
			for (int i = 0; i < 200; i++) {
				source.put(FIRST_KEY + i, FIRST_VALUE + i);
			}
			for (int i = 0; i < 5; i++) {
				source.put(new CollidingKey(i), FIRST_VALUE + i);
			}
			final ChampMap<Object, Integer> map = ChampMap.from(source);

			assertEquals(measured(map), map.getHeapSizeInBytes(OWNED, OWNED));
		}
	}

	@Nested
	@DisplayName("never charges for the shared empty singleton")
	class SharedSingletons {

		@Test
		void shouldChargeNothingForTheEmptySingleton() {
			assertEquals(0L, ChampMap.empty().getHeapSizeInBytes(OWNED, OWNED));
		}

		@Test
		void shouldChargeNothingForAMapSealedFromAnEmptySource() {
			// Builder.build() short-circuits an empty result to the singleton, so this is the SAME object - nobody
			// owns it and charging every empty index map forty bytes for it would be an invention
			final ChampMap<Integer, Integer> sealed = ChampMap.from(new HashMap<>());

			assertSame(ChampMap.empty(), sealed);
			assertEquals(0L, sealed.getHeapSizeInBytes(OWNED, OWNED));
		}

		@Test
		void shouldChargeAMapThatBecameEmptyByRemoval() {
			// removal does NOT re-canonicalize down to the singleton, so this map really does own a trie object and a
			// node object. The exclusion is by identity precisely so this case is still charged
			final ChampMap<Integer, Integer> emptied = ChampMap.of(FIRST_KEY, FIRST_VALUE).removed(FIRST_KEY);

			assertTrue(emptied.isEmpty());
			assertNotSame(ChampMap.empty(), emptied);
			assertTrue(emptied.getHeapSizeInBytes(OWNED, OWNED) > 0L);
			assertEquals(measured(emptied), emptied.getHeapSizeInBytes(OWNED, OWNED));
		}
	}

	@Nested
	@DisplayName("charges structure shared with another version in full")
	class StructuralSharing {

		@Test
		void shouldChargeTheDerivedMapForEverythingItReaches() {
			final ChampMap<Integer, Integer> base = champOf(1_000);
			final ChampMap<Integer, Integer> derived = base.updated(FIRST_KEY, FIRST_VALUE + 999_999);

			// path-copying replaced a handful of nodes and shared the rest by reference. Both versions are charged
			// for the shared sub-tries, because either one alone keeps them alive - the JOL walk of the derived map
			// on its own reaches exactly the same graph, and that is the number the arithmetic has to reproduce
			assertEquals(measured(derived), derived.getHeapSizeInBytes(OWNED, OWNED));
			assertEquals(measured(base), base.getHeapSizeInBytes(OWNED, OWNED));
		}

		@Test
		void shouldReportNearlyTheSameFigureForBothVersions() {
			final ChampMap<Integer, Integer> base = champOf(1_000);
			final ChampMap<Integer, Integer> derived = base.updated(FIRST_KEY, FIRST_VALUE + 999_999);

			// a one-key update path-copies O(log32 N) nodes, so the two figures differ by the replaced value alone
			// plus a rounding of node sizes - well under a percent. A walk that stopped at shared structure would
			// instead report a derived map a hundred times smaller than its base
			final long baseSize = base.getHeapSizeInBytes(OWNED, OWNED);
			final long derivedSize = derived.getHeapSizeInBytes(OWNED, OWNED);
			assertTrue(
				Math.abs(baseSize - derivedSize) < baseSize / 100,
				"a derived version must be charged for the structure it shares, was " + derivedSize + " against "
					+ baseSize
			);
		}
	}

	@Nested
	@DisplayName("leaves key and value ownership to the caller")
	class PayloadOwnership {

		@Test
		void shouldExcludeKeysAndValuesWhenTheSizersDecline() {
			final ChampMap<Integer, Integer> map = champOf(100);

			final long spineOnly = map.getHeapSizeInBytes(NOTHING, NOTHING);
			final long priced = map.getHeapSizeInBytes(OWNED, OWNED);

			// 100 keys and 100 values, each a distinct boxed Integer - the gap must be exactly their footprint
			assertEquals(200L * JolHeapSize.ownedSize(Integer.valueOf(FIRST_VALUE)), priced - spineOnly);
			assertTrue(spineOnly > 0, "the trie itself must still be charged");
		}

		@Test
		void shouldPriceEachOccurrenceOfARepeatedValue() {
			// the same boxed instance stored under many keys: the arithmetic charges it once per holder, deliberately
			// - see the standing ruling on shared payload. So the spine-only figure is the honest one here, and the
			// gap to the priced figure is the repeat count times the value, not the value once
			final Integer shared = FIRST_VALUE;
			final Map<Integer, Integer> source = new HashMap<>();
			for (int i = 0; i < 50; i++) {
				source.put(FIRST_KEY + i, shared);
			}
			final ChampMap<Integer, Integer> map = ChampMap.from(source);

			assertEquals(
				50L * JolHeapSize.ownedSize(shared),
				map.getHeapSizeInBytes(NOTHING, OWNED) - map.getHeapSizeInBytes(NOTHING, NOTHING)
			);
		}
	}
}
