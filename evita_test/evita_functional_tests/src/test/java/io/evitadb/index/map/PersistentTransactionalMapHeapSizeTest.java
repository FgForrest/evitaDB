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

import io.evitadb.dataType.champ.ChampMap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.utils.JolHeapSize;
import io.evitadb.utils.VMLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;
import java.util.function.ToLongFunction;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the heap-size walk over {@link PersistentTransactionalMap} and its producer-valued subclass — the
 * substrate under `EntityCollection`'s index maps and `AttributeIndex`'s sub-index maps.
 *
 * # The state has two shapes, and the reported figure differs between them
 *
 * `state` is either a mutable {@link HashMap} (the non-transactional warm-up buffer) or an immutable
 * {@link ChampMap} (the transactional steady state), and the concrete type *is* the mode. The two own different
 * things — a bucket table and a node per entry against a hash trie — so the same content reports a different figure
 * depending on which mode the map is in. That is a property of the map, not an inaccuracy, and {@link ModeDependence}
 * pins it so it is not mistaken for one later. It reaches its sharpest form at zero entries: a thawed empty map owns
 * a real `HashMap`, while a sealed empty map *is* {@link ChampMap#empty()}, a JVM-wide singleton nobody owns.
 *
 * # Why the producer map is asserted in two pieces
 *
 * {@link PersistentTransactionalProducerMap} holds a lambda, and JOL cannot walk one — it dies on the hidden class.
 * Its own object is therefore pinned with a **shallow** measurement, which reads the layout without entering any
 * field, and its state through the package-private state-only entry point. That split matters: the own-object term is
 * a subclass layout ending in a `boolean`, exactly where field packing could quietly cost eight bytes.
 *
 * @author Claude (heap-size verification), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(DATA_TYPE)
@DisplayName("Persistent transactional map heap-size reporting")
class PersistentTransactionalMapHeapSizeTest {
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
	 * Prices nothing — the spine-only case.
	 */
	private static final ToLongFunction<Object> NOTHING = value -> 0L;

	/**
	 * Builds a thawed map holding `entries` distinct boxed pairs.
	 *
	 * The entries are `put` after construction rather than handed to the constructor, because that is how a warm-up
	 * buffer actually fills and because it is the growth path {@link MapHeapSize} models — see
	 * {@link InferredTableCapacity} for the case where the two diverge.
	 *
	 * @param entries how many pairs to add
	 * @return a map still in its thawed mode
	 */
	@Nonnull
	private static PersistentTransactionalMap<Integer, Integer> thawedOf(int entries) {
		final PersistentTransactionalMap<Integer, Integer> map = new PersistentTransactionalMap<>(new HashMap<>());
		for (int i = 0; i < entries; i++) {
			map.put(FIRST_KEY + i, FIRST_VALUE + i);
		}
		return map;
	}

	/**
	 * Builds a map holding `entries` distinct boxed pairs and seals it into its {@link ChampMap} mode.
	 *
	 * @param entries how many pairs to add
	 * @return a map whose state is an immutable trie
	 */
	@Nonnull
	private static PersistentTransactionalMap<Integer, Integer> sealedOf(int entries) {
		final PersistentTransactionalMap<Integer, Integer> map = thawedOf(entries);
		map.sealed();
		return map;
	}

	@Nested
	@DisplayName("matches the measured heap")
	class MeasuredExactness {

		@Test
		void shouldMatchMeasuredHeapWhileThawed() {
			for (int entries : new int[]{0, 1, 13, 25, 200}) {
				final PersistentTransactionalMap<Integer, Integer> map = thawedOf(entries);

				assertEquals(
					JolHeapSize.ownedSize(map),
					map.getHeapSizeInBytes(OWNED, OWNED),
					"figure diverged at " + entries + " thawed entries"
				);
			}
		}

		@Test
		void shouldMatchMeasuredHeapWhileSealed() {
			for (int entries : new int[]{1, 13, 25, 200, 2_000}) {
				final PersistentTransactionalMap<Integer, Integer> map = sealedOf(entries);

				// the empty-trie singleton is shared with every other sealed map, and its two zero-length arrays
				// additionally turn up inside live nodes that carry sub-nodes but no inlined payload
				assertEquals(
					JolHeapSize.ownedSize(map, ChampMap.empty()),
					map.getHeapSizeInBytes(OWNED, OWNED),
					"figure diverged at " + entries + " sealed entries"
				);
			}
		}

		@Test
		void shouldMatchMeasuredHeapForOwnedBitmapValues() {
			// the shape the index maps use: a boxed key against a wholly-owned bitmap
			final PersistentTransactionalMap<Integer, TransactionalBitmap> map =
				new PersistentTransactionalMap<>(new HashMap<>());
			for (int i = 0; i < 50; i++) {
				map.put(FIRST_KEY + i, new TransactionalBitmap(new int[]{i, i + 1, i + 2}));
			}
			map.sealed();

			assertEquals(
				JolHeapSize.ownedSize(map, ChampMap.empty()),
				map.getHeapSizeInBytes(OWNED, TransactionalBitmap::getHeapSizeInBytes)
			);
		}
	}

	@Nested
	@DisplayName("reports what the current mode actually owns")
	class ModeDependence {

		@Test
		void shouldReportBothModesOfTheSameContentExactly() {
			final PersistentTransactionalMap<Integer, Integer> thawed = thawedOf(200);
			final PersistentTransactionalMap<Integer, Integer> sealed = sealedOf(200);

			final long thawedSize = thawed.getHeapSizeInBytes(OWNED, OWNED);
			final long sealedSize = sealed.getHeapSizeInBytes(OWNED, OWNED);

			// two different structures holding identical content weigh different amounts - the point of the
			// assertion is that BOTH are exact, not that either is the "right" figure for the content
			assertNotEquals(thawedSize, sealedSize);
			assertEquals(JolHeapSize.ownedSize(thawed), thawedSize);
			assertEquals(JolHeapSize.ownedSize(sealed, ChampMap.empty()), sealedSize);
		}

		@Test
		void shouldChargeNothingBeyondItselfForASealedEmptyMap() {
			final PersistentTransactionalMap<Integer, Integer> map = sealedOf(0);

			// sealing an empty map yields the shared ChampMap singleton, which belongs to nobody - so the decorator
			// is all that is left, and an empty index map costs one object
			final VMLayout layout = VMLayout.current();
			assertEquals(
				layout.sizeOfObject(Long.BYTES + layout.referenceSize()),
				map.getHeapSizeInBytes(OWNED, OWNED)
			);
		}

		@Test
		void shouldChargeTheBufferOfAThawedEmptyMap() {
			final PersistentTransactionalMap<Integer, Integer> map = thawedOf(0);

			// the same map before it is sealed owns a real (table-less) HashMap, so it costs strictly more than the
			// sealed one. Both figures are right; only the mode differs
			assertTrue(map.getHeapSizeInBytes(OWNED, OWNED) > sealedOf(0).getHeapSizeInBytes(OWNED, OWNED));
			assertEquals(JolHeapSize.ownedSize(map), map.getHeapSizeInBytes(OWNED, OWNED));
		}
	}

	@Nested
	@DisplayName("infers the bucket table when it cannot be read")
	class InferredTableCapacity {

		@Test
		void shouldMatchACopiedMapSittingExactlyOnAThreshold() {
			// this constructor is why the capacity model reports the larger of the two construction paths. Twelve
			// entries handed to `new HashMap<>(source)` ask for (12 / 0.75) + 1 = 17 slots, rounded to 32, while a
			// map GROWN to twelve entries stops at 16 - growth doubles only when the count exceeds the threshold.
			// Following growth here would read 64 bytes low on a freshly loaded collection, which is exactly the
			// shape `EntityCollection` hands to its index maps before anything writes to them
			final Map<Integer, Integer> source = new HashMap<>();
			for (int i = 0; i < 12; i++) {
				source.put(FIRST_KEY + i, FIRST_VALUE + i);
			}
			final PersistentTransactionalMap<Integer, Integer> copied = new PersistentTransactionalMap<>(source);

			assertEquals(JolHeapSize.ownedSize(copied), copied.getHeapSizeInBytes(OWNED, OWNED));
		}

		@Test
		void shouldOverReportASmallMapCopiedStraightFromItsSource() {
			// below the sixteen-slot floor the copy constructor undercuts even a grown map - five entries fit in
			// eight slots - and the floor is what keeps the model an upper bound rather than merely the copy
			// constructor's answer. The gap is bounded by one doubling and closes the moment anything is put in
			final Map<Integer, Integer> source = new HashMap<>();
			for (int i = 0; i < 5; i++) {
				source.put(FIRST_KEY + i, FIRST_VALUE + i);
			}
			final PersistentTransactionalMap<Integer, Integer> copied = new PersistentTransactionalMap<>(source);

			final VMLayout layout = VMLayout.current();
			final long phantomSlots = layout.sizeOfArray(16, layout.referenceSize())
				- layout.sizeOfArray(8, layout.referenceSize());

			assertEquals(
				JolHeapSize.ownedSize(copied) + phantomSlots,
				copied.getHeapSizeInBytes(OWNED, OWNED),
				"a small never-grown copy must over-report by exactly the floor it is held to"
			);
		}
	}

	@Nested
	@DisplayName("does not disturb what it measures")
	class NonIntrusive {

		@Test
		void shouldNotSealAThawedMap() {
			final PersistentTransactionalMap<Integer, Integer> map = thawedOf(200);

			// sealing on the measurement path would replace the buffer with a frozen trie, turning the next warm-up
			// write into an O(N) thaw - a monitoring call that degrades the write path it is watching. A JOL walk
			// sees the actual graph, so an unchanged measurement is proof the mode survived
			final long before = JolHeapSize.ownedSize(map);
			map.getHeapSizeInBytes(OWNED, OWNED);
			final long after = JolHeapSize.ownedSize(map);

			assertEquals(before, after, "measuring the map must not change what it holds");
			assertEquals(after, map.getHeapSizeInBytes(OWNED, OWNED));
		}
	}

	@Nested
	@Tag(TRANSACTION)
	@DisplayName("stays on the committed state while a transaction is open")
	class TransactionalLayerSafety {

		@Test
		void shouldReportTheCommittedStateAndNotTheDiffLayer() {
			final PersistentTransactionalMap<Integer, Integer> map = thawedOf(200);
			final long thawed = map.getHeapSizeInBytes(OWNED, OWNED);
			// built outside the transaction on purpose: a `put` inside one creates a diff layer on whatever map it
			// touches, and a helper map acquiring a layer nobody sweeps fails the commit
			final long sealed = sealedOf(200).getHeapSizeInBytes(OWNED, OWNED);

			assertStateAfterCommit(
				map,
				m -> {
					// the first transactional touch creates the diff layer, and creating it SEALS the state. So
					// opening a transaction changes the reported footprint without one committed entry changing -
					// a monitoring caller sampling across the boundary sees a real discontinuity, not a drift
					m.put(FIRST_KEY + 200, FIRST_VALUE + 200);

					final long inTransaction = m.getHeapSizeInBytes(OWNED, OWNED);
					assertNotEquals(thawed, inTransaction, "the first transactional touch must seal the state");
					assertEquals(
						sealed,
						inTransaction,
						"the figure must describe the committed 200 entries, never the layer's uncommitted 201st"
					);
					// the map itself reads through the layer, so the divergence is deliberate: the layer belongs to
					// the transaction, lives in transactional memory, and vanishes at commit or rollback
					assertEquals(201, m.size());
					assertEquals(inTransaction, m.getHeapSizeInBytes(OWNED, OWNED), "the walk must be repeatable");
				},
				(m, committed) -> assertEquals(201, committed.size())
			);
		}
	}

	@Nested
	@DisplayName("leaves key and value ownership to the caller")
	class PayloadOwnership {

		@Test
		void shouldExcludeKeysAndValuesWhenTheSizersDecline() {
			final PersistentTransactionalMap<Integer, Integer> map = sealedOf(100);

			final long spineOnly = map.getHeapSizeInBytes(NOTHING, NOTHING);
			final long priced = map.getHeapSizeInBytes(OWNED, OWNED);

			// 100 keys and 100 values, each a distinct boxed Integer - the gap must be exactly their footprint
			assertEquals(200L * JolHeapSize.ownedSize(Integer.valueOf(FIRST_VALUE)), priced - spineOnly);
			assertTrue(spineOnly > 0, "the spine itself must still be charged");
		}
	}

	@Nested
	@DisplayName("adds the producer subclass's own fields")
	class ProducerVariant {

		/**
		 * Builds a producer-valued map holding `entries` bitmaps, sealed into its trie mode.
		 *
		 * @param entries how many pairs to add
		 * @return a sealed producer-valued map
		 */
		@Nonnull
		private static PersistentTransactionalProducerMap<Integer, TransactionalBitmap> producerOf(int entries) {
			final Map<Integer, TransactionalBitmap> source = new HashMap<>();
			for (int i = 0; i < entries; i++) {
				source.put(FIRST_KEY + i, new TransactionalBitmap(new int[]{i, i + 1}));
			}
			final PersistentTransactionalProducerMap<Integer, TransactionalBitmap> map =
				new PersistentTransactionalProducerMap<>(source, TransactionalBitmap.class, TransactionalBitmap::new);
			map.sealed();
			return map;
		}

		@Test
		void shouldMatchTheShallowLayoutOfTheBaseClass() {
			// id + the state slot, and nothing between them - the term every other figure here is built on
			final VMLayout layout = VMLayout.current();
			assertEquals(
				layout.sizeOfObject(Long.BYTES + layout.referenceSize()),
				JolHeapSize.shallowSize(thawedOf(0))
			);
		}

		@Test
		void shouldMatchTheShallowLayoutOfTheProducerSubclass() {
			// the inherited two fields plus valueType / transactionalLayerWrapper / explicitDirtyKeyMerge. A shallow
			// measurement reads the layout without entering a field, so unlike a full walk it survives the lambda
			final VMLayout layout = VMLayout.current();
			assertEquals(
				layout.sizeOfObject(Long.BYTES + 3L * layout.referenceSize() + 1L),
				JolHeapSize.shallowSize(producerOf(0))
			);
		}

		@Test
		void shouldSumItsOwnObjectAndItsState() {
			final PersistentTransactionalProducerMap<Integer, TransactionalBitmap> map = producerOf(50);

			assertEquals(
				JolHeapSize.shallowSize(map)
					+ map.getStateHeapSizeInBytes(OWNED, TransactionalBitmap::getHeapSizeInBytes),
				map.getHeapSizeInBytes(OWNED, TransactionalBitmap::getHeapSizeInBytes)
			);
		}

		@Test
		void shouldMatchTheMeasuredHeapOfItsState() {
			final PersistentTransactionalProducerMap<Integer, TransactionalBitmap> map = producerOf(50);

			// the state is reachable through `sealed()`, which is what lets the walk be verified around the lambda
			// that stops a full one
			assertEquals(
				JolHeapSize.ownedSize(map.sealed(), ChampMap.empty()),
				map.getStateHeapSizeInBytes(OWNED, TransactionalBitmap::getHeapSizeInBytes)
			);
		}

		@Test
		void shouldCostMoreThanThePlainMapHoldingTheSameState() {
			final PersistentTransactionalProducerMap<Integer, TransactionalBitmap> producer = producerOf(50);
			final PersistentTransactionalMap<Integer, TransactionalBitmap> plain =
				new PersistentTransactionalMap<>(producer.sealed());

			final ToLongFunction<TransactionalBitmap> bitmaps = TransactionalBitmap::getHeapSizeInBytes;
			// the subclass's three extra fields, in a shared object header - not three whole objects
			assertTrue(
				producer.getHeapSizeInBytes(OWNED, bitmaps) > plain.getHeapSizeInBytes(OWNED, bitmaps),
				"the producer variant carries three fields the plain map does not"
			);
		}

		@Test
		void shouldPriceTheExplicitDirtyKeyVariantIdentically() {
			// the two constructors differ only in what they put in the same three fields, never in the layout
			final Map<Integer, TransactionalBitmap> source = new HashMap<>();
			for (int i = 0; i < 50; i++) {
				source.put(FIRST_KEY + i, new TransactionalBitmap(new int[]{i, i + 1}));
			}
			final PersistentTransactionalProducerMap<Integer, TransactionalBitmap> explicit =
				PersistentTransactionalProducerMap.withExplicitDirtyKeyMerge(source, TransactionalBitmap.class::cast);
			explicit.sealed();

			assertEquals(
				JolHeapSize.shallowSize(explicit)
					+ explicit.getStateHeapSizeInBytes(OWNED, TransactionalBitmap::getHeapSizeInBytes),
				explicit.getHeapSizeInBytes(OWNED, TransactionalBitmap::getHeapSizeInBytes)
			);
		}
	}
}
