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

package io.evitadb.index.map;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.JolHeapSize;
import io.evitadb.utils.VMLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToLongFunction;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the heap-size walk over {@link TransactionalMap} and the {@link MapHeapSize} arithmetic beneath it — the
 * substrate under fourteen map fields spread across `ChainIndex`, `GlobalUniqueIndex`,
 * `ReferenceTypeCardinalityIndex` and `AttributeIndex`.
 *
 * # Keys chosen to stay out of the autobox cache
 *
 * Every fixture below uses boxed values above `127`. Inside the cache two equal `Integer`s are the *same* instance,
 * so a JOL walk — which dedupes by identity — would count one where the reported figure charges two, and the
 * assertions would be measuring the JVM's interning rather than this class's arithmetic. That divergence is real and
 * deliberate, but it belongs to the ruling that boxed values are charged to each holder, and it is pinned where it
 * arises rather than here.
 *
 * # The one figure that is inferred rather than measured
 *
 * A `HashMap`'s bucket-table capacity cannot be read from outside the JDK, so {@link MapHeapSize} reconstructs it
 * from the entry count. {@link InferredTableCapacity} pins both sides of that: a map created pre-sized above its
 * content under-reports by exactly its unused table slots, and the gap vanishes the moment the map outgrows the
 * capacity it was built with.
 *
 * @author Claude (heap-size verification), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(DATA_TYPE)
@DisplayName("Transactional map heap-size reporting")
class TransactionalMapHeapSizeTest {
	/**
	 * First key value — comfortably above the autobox cache ceiling so every boxed key is its own instance.
	 */
	private static final int FIRST_KEY = 1_000;

	/**
	 * First value — disjoint from the key range so no key and value can ever be the same boxed instance.
	 */
	private static final int FIRST_VALUE = 500_000;

	/**
	 * Prices a boxed value at its true footprint. Used as both key and value sizer wherever the fixture stores
	 * `Integer`s the map genuinely owns.
	 */
	private static final ToLongFunction<Integer> BOXED = JolHeapSize::ownedSize;

	/**
	 * Prices nothing — the spine-only case, for a map that merely borrows what it points at.
	 */
	private static final ToLongFunction<Object> NOTHING = value -> 0L;

	/**
	 * Fills a map with `entries` distinct boxed key/value pairs, none of which can come from the autobox cache.
	 *
	 * @param map     the map to fill
	 * @param entries how many pairs to add
	 * @return the same map, for chaining
	 */
	@Nonnull
	private static Map<Integer, Integer> fill(@Nonnull Map<Integer, Integer> map, int entries) {
		for (int i = 0; i < entries; i++) {
			map.put(FIRST_KEY + i, FIRST_VALUE + i);
		}
		return map;
	}

	@Nested
	@DisplayName("matches the measured heap")
	class MeasuredExactness {

		@Test
		void shouldMatchMeasuredHeapForEmptyMap() {
			final TransactionalMap<Integer, Integer> map = new TransactionalMap<>(new HashMap<>());

			// an empty HashMap has no bucket table at all - it is allocated on the first put. Charging a phantom
			// 16-slot table here would roughly double the figure, and index-level maps are routinely empty
			assertEquals(JolHeapSize.ownedSize(map), map.getHeapSizeInBytes(BOXED, BOXED));
		}

		@Test
		void shouldMatchMeasuredHeapForSmallMap() {
			final TransactionalMap<Integer, Integer> map =
				new TransactionalMap<>(fill(new HashMap<>(), 5));

			assertEquals(JolHeapSize.ownedSize(map), map.getHeapSizeInBytes(BOXED, BOXED));
		}

		@Test
		void shouldMatchMeasuredHeapAcrossEveryResizeBoundary() {
			// walk through the sizes at which HashMap doubles its table (12/13, 24/25, 48/49, 96/97) so an
			// off-by-one in the capacity reconstruction cannot hide between the test points
			for (int entries : new int[]{1, 12, 13, 24, 25, 48, 49, 96, 97, 200}) {
				final TransactionalMap<Integer, Integer> map =
					new TransactionalMap<>(fill(new HashMap<>(), entries));

				assertEquals(
					JolHeapSize.ownedSize(map),
					map.getHeapSizeInBytes(BOXED, BOXED),
					"figure diverged at " + entries + " entries"
				);
			}
		}

		@Test
		void shouldMatchMeasuredHeapForOwnedBitmapValues() {
			// the shape ChainIndex.successorsByPredecessor and ReferenceTypeCardinalityIndex use: boxed key,
			// wholly-owned bitmap value
			final Map<Integer, TransactionalBitmap> delegate = new HashMap<>();
			for (int i = 0; i < 50; i++) {
				delegate.put(FIRST_KEY + i, new TransactionalBitmap(new int[]{i, i + 1, i + 2}));
			}
			final TransactionalMap<Integer, TransactionalBitmap> map = new TransactionalMap<>(delegate);

			assertEquals(
				JolHeapSize.ownedSize(map),
				map.getHeapSizeInBytes(BOXED, TransactionalBitmap::getHeapSizeInBytes)
			);
		}
	}

	@Nested
	@DisplayName("infers the bucket table when it cannot be read")
	class InferredTableCapacity {

		@Test
		void shouldUnderReportAPreSizedMapByExactlyItsUnusedTableSlots() {
			// CollectionUtils.createHashMap(64) asks for (64 / 0.75) + 1 = 86 slots, which HashMap rounds up to the
			// next power of two: a 128-slot table. Holding three entries, the reconstruction infers the minimum 16
			final Map<Integer, Integer> preSized = CollectionUtils.createHashMap(64);
			fill(preSized, 3);
			final TransactionalMap<Integer, Integer> map = new TransactionalMap<>(preSized);

			final VMLayout layout = VMLayout.current();
			final long unusedSlots = layout.sizeOfArray(128, layout.referenceSize())
				- layout.sizeOfArray(16, layout.referenceSize());

			assertEquals(
				JolHeapSize.ownedSize(map) - unusedSlots,
				map.getHeapSizeInBytes(BOXED, BOXED),
				"a pre-sized map must under-report by exactly the table slots it holds but does not use"
			);
		}

		@Test
		void shouldNotDivergeOnceTheMapOutgrowsItsInitialCapacity() {
			// the same pre-sized map, now holding more than the capacity it was built for: growth has taken the
			// table to exactly where the reconstruction says it should be, so the gap closes completely
			final Map<Integer, Integer> preSized = CollectionUtils.createHashMap(64);
			fill(preSized, 200);
			final TransactionalMap<Integer, Integer> map = new TransactionalMap<>(preSized);

			assertEquals(JolHeapSize.ownedSize(map), map.getHeapSizeInBytes(BOXED, BOXED));
		}
	}

	@Nested
	@DisplayName("refuses to misprice a shape it does not know")
	class UnsupportedShapes {

		@Test
		void shouldThrowForLinkedHashMapRatherThanPricingItAsAHashMap() {
			// LinkedHashMap extends HashMap, so an `instanceof` check would accept it and silently under-report:
			// its map object carries two more references and every entry two more still
			final TransactionalMap<Integer, Integer> map =
				new TransactionalMap<>(fill(new LinkedHashMap<>(), 5));

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> map.getHeapSizeInBytes(BOXED, BOXED)
			);
			assertTrue(
				error.getPrivateMessage().contains("LinkedHashMap"),
				"the error must name the offending implementation, was: " + error.getPrivateMessage()
			);
		}
	}

	@Nested
	@DisplayName("does not disturb what it measures")
	class NonIntrusive {

		@Test
		void shouldNotAllocateTheEntrySetView() {
			final TransactionalMap<Integer, Integer> map =
				new TransactionalMap<>(fill(new HashMap<>(), 20));

			// HashMap.entrySet() lazily creates and caches its view object, so a walk that iterated it would grow
			// the very map it is measuring. Measuring twice around a call proves the walk went through forEach
			final long before = JolHeapSize.ownedSize(map);
			map.getHeapSizeInBytes(BOXED, BOXED);
			final long after = JolHeapSize.ownedSize(map);

			assertEquals(before, after, "measuring the map must not allocate a view on it");
		}
	}

	@Nested
	@DisplayName("leaves key and value ownership to the caller")
	class PayloadOwnership {

		@Test
		void shouldExcludeKeysAndValuesWhenTheSizersDecline() {
			final TransactionalMap<Integer, Integer> map =
				new TransactionalMap<>(fill(new HashMap<>(), 100));

			final long spineOnly = map.getHeapSizeInBytes(NOTHING, NOTHING);
			final long priced = map.getHeapSizeInBytes(BOXED, BOXED);

			// 100 keys and 100 values, each a distinct boxed Integer - the gap must be exactly their footprint
			assertEquals(200L * JolHeapSize.ownedSize(Integer.valueOf(FIRST_VALUE)), priced - spineOnly);
			assertTrue(spineOnly > 0, "the spine itself must still be charged");
		}

		@Test
		void shouldMeasureTheDelegateWhenTheDecoratorCarriesALambda() {
			// the wrapper-carrying constructor puts a lambda on the decorator, and a lambda is a hidden class whose
			// field offsets JOL refuses to read - so this variant is asserted against the delegate subgraph
			final Map<Integer, TransactionalBitmap> delegate = new HashMap<>();
			for (int i = 0; i < 30; i++) {
				delegate.put(FIRST_KEY + i, new TransactionalBitmap(new int[]{i}));
			}
			final TransactionalMap<Integer, TransactionalBitmap> map = new TransactionalMap<>(
				delegate, TransactionalBitmap.class, TransactionalBitmap::new
			);

			assertEquals(
				JolHeapSize.ownedSize(delegate),
				map.getDelegateHeapSizeInBytes(BOXED, TransactionalBitmap::getHeapSizeInBytes)
			);
		}
	}
}
