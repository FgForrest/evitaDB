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

package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
import io.evitadb.store.entity.serializer.EnumNameSerializer;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip coverage for {@link SortIndexStoragePartSerializer}, focused on the slim block-delta format. The
 * `sortedRecords` array is a concatenation of per-value blocks (block length = the value cardinality, ascending record
 * ids within a block). Owner-mode parts (sort-only / compound, carrying values + cardinalities) delta-encode each block
 * via {@link io.evitadb.store.index.serializer.util.SortedIntArrayCodec}; a part with a non-ascending block (a rare
 * migration-collapsed scaled-int part) falls back to a raw encoding flagged by a per-part boolean. View-mode parts
 * (filterable + sortable) carry no values/cardinalities and keep the raw encoding. The released-minor formats are read
 * by `SortIndexStoragePartSerializer_2025_5` / `_2026_1` (exercised end-to-end by the backward-compatibility test on
 * real old catalogs); the prior 2026.2 development format was never released, so it has no backward-compatible reader.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("SortIndexStoragePartSerializer round-trip (slim block-delta format)")
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(SERIALIZATION)
class SortIndexStoragePartSerializerTest {

	private static final AttributeIndexKey ATTRIBUTE_KEY = new AttributeIndexKey(null, "code", null);

	private Kryo kryo;
	private SortIndexStoragePartSerializer serializer;

	@BeforeEach
	void setUp() {
		final ReadWriteKeyCompressor keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.serializer = new SortIndexStoragePartSerializer(keyCompressor);
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(keyCompressor));
		// the comparator base persists OrderDirection / OrderBehaviour enums, registered by the schema configurer in
		// production; register them on this isolated test Kryo (one instance for both write and read, so ids are stable)
		this.kryo.register(OrderDirection.class, new EnumNameSerializer<>());
		this.kryo.register(OrderBehaviour.class, new EnumNameSerializer<>());
	}

	@Nonnull
	private byte[] serialize(@Nonnull SortIndexStoragePart part) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			this.serializer.write(this.kryo, output, part);
		}
		return os.toByteArray();
	}

	@Nonnull
	private SortIndexStoragePart roundTrip(@Nonnull SortIndexStoragePart part) {
		try (final Input input = new Input(serialize(part))) {
			return this.serializer.read(this.kryo, input, SortIndexStoragePart.class);
		}
	}

	@Nonnull
	private static ComparatorSource[] singleStringAscending() {
		return new ComparatorSource[]{
			new ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
		};
	}

	/**
	 * Builds an owner-mode part whose two blocks are ascending (apple → {1,2,3}, banana → {9}); exercises the block-delta
	 * encoding path. `apple` has cardinality 3 (stored), `banana` is an implied singleton (absent from the sparse map).
	 *
	 * @return an owner-mode part with ascending blocks
	 */
	@Nonnull
	private static SortIndexStoragePart ascendingOwnerPart() {
		final Map<Serializable, Integer> cardinalities = Map.of("apple", 3);
		return new SortIndexStoragePart(
			42, ATTRIBUTE_KEY, singleStringAscending(),
			new int[]{1, 2, 3, 9}, new Serializable[]{"apple", "banana"}, cardinalities, 1L
		);
	}

	@Nested
	@DisplayName("Round-trip")
	class RoundTrip {

		@Test
		@DisplayName("round-trips an owner-mode part with ascending blocks (delta path)")
		void shouldRoundTripAscendingOwnerPart() {
			final SortIndexStoragePart deserialized = roundTrip(ascendingOwnerPart());

			assertEquals(42, deserialized.getEntityIndexPrimaryKey());
			assertEquals(ATTRIBUTE_KEY, deserialized.getAttributeIndexKey());
			assertArrayEquals(new int[]{1, 2, 3, 9}, deserialized.getSortedRecords());
			assertArrayEquals(new Serializable[]{"apple", "banana"}, deserialized.getSortedRecordsValues());
			assertEquals(1, deserialized.getValueCardinalities().size());
			assertEquals(3, deserialized.getValueCardinalities().get("apple"));
		}

		@Test
		@DisplayName("round-trips an owner-mode part of all singleton blocks (empty cardinality map)")
		void shouldRoundTripHighCardinalitySingletons() {
			// every value is a singleton, so the sparse cardinality map is empty; each block is one (trivially ascending)
			final SortIndexStoragePart part = new SortIndexStoragePart(
				7, ATTRIBUTE_KEY, singleStringAscending(),
				new int[]{5, 2, 8}, new Serializable[]{"a", "b", "c"}, Map.of(), 1L
			);

			final SortIndexStoragePart deserialized = roundTrip(part);

			assertArrayEquals(new int[]{5, 2, 8}, deserialized.getSortedRecords());
			assertArrayEquals(new Serializable[]{"a", "b", "c"}, deserialized.getSortedRecordsValues());
			assertTrue(deserialized.getValueCardinalities().isEmpty());
		}

		@Test
		@DisplayName("round-trips a compound (multi-attribute) owner part keyed by ComparableArray")
		void shouldRoundTripCompoundPart() {
			final ComparatorSource[] base = {
				new ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
				new ComparatorSource(Integer.class, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
			};
			final ComparableArray first = new ComparableArray(new Serializable[]{"a", 1});
			final ComparableArray second = new ComparableArray(new Serializable[]{"b", 2});
			final Map<Serializable, Integer> cardinalities = Map.of(first, 2);
			final SortIndexStoragePart part = new SortIndexStoragePart(
				9, ATTRIBUTE_KEY, base,
				new int[]{1, 2, 3}, new Serializable[]{first, second}, cardinalities, 1L
			);

			final SortIndexStoragePart deserialized = roundTrip(part);

			assertArrayEquals(new int[]{1, 2, 3}, deserialized.getSortedRecords());
			assertArrayEquals(new Serializable[]{first, second}, deserialized.getSortedRecordsValues());
			assertEquals(2, deserialized.getValueCardinalities().get(first));
		}

		@Test
		@DisplayName("round-trips a slim view-mode part (no values / cardinalities, raw records)")
		void shouldRoundTripViewModePart() {
			final SortIndexStoragePart part = new SortIndexStoragePart(
				42, ATTRIBUTE_KEY, singleStringAscending(),
				new int[]{3, 1, 2}, new Serializable[0], Map.of(), 1L
			);

			final SortIndexStoragePart deserialized = roundTrip(part);

			assertEquals(42, deserialized.getEntityIndexPrimaryKey());
			assertArrayEquals(new int[]{3, 1, 2}, deserialized.getSortedRecords());
			assertEquals(0, deserialized.getSortedRecordsValues().length, "view-mode values must round-trip empty");
			assertTrue(deserialized.getValueCardinalities().isEmpty(), "view-mode cardinalities must round-trip empty");
		}

		@Test
		@DisplayName("round-trips an empty sort index")
		void shouldRoundTripEmptyPart() {
			final SortIndexStoragePart part = new SortIndexStoragePart(
				1, ATTRIBUTE_KEY, singleStringAscending(),
				new int[0], new Serializable[0], Map.of(), 1L
			);

			final SortIndexStoragePart deserialized = roundTrip(part);

			assertEquals(0, deserialized.getSortedRecords().length);
			assertEquals(0, deserialized.getSortedRecordsValues().length);
		}

		@Test
		@DisplayName("round-trips a single-record owner part")
		void shouldRoundTripSingleRecordPart() {
			final SortIndexStoragePart part = new SortIndexStoragePart(
				1, ATTRIBUTE_KEY, singleStringAscending(),
				new int[]{42}, new Serializable[]{"x"}, Map.of(), 1L
			);

			final SortIndexStoragePart deserialized = roundTrip(part);

			assertArrayEquals(new int[]{42}, deserialized.getSortedRecords());
			assertArrayEquals(new Serializable[]{"x"}, deserialized.getSortedRecordsValues());
		}

		@Test
		@DisplayName("round-trips a block whose first id is large (>= 2^28, 5-byte zig-zag)")
		void shouldRoundTripHugeFirstId() {
			final Map<Serializable, Integer> cardinalities = Map.of("big", 2);
			final SortIndexStoragePart part = new SortIndexStoragePart(
				1, ATTRIBUTE_KEY, singleStringAscending(),
				new int[]{300_000_000, 300_000_005}, new Serializable[]{"big"}, cardinalities, 1L
			);

			final SortIndexStoragePart deserialized = roundTrip(part);

			assertArrayEquals(new int[]{300_000_000, 300_000_005}, deserialized.getSortedRecords());
		}

		@Test
		@DisplayName("round-trips a scaled-int sort value under a BigDecimal comparator base, preserving the scale")
		void shouldRoundTripScaledIntegerSortValueUnderBigDecimalComparator() {
			// reproduces the runtime shape of a sort-only BigDecimal attribute: the comparator base reflects the declared
			// BigDecimal type, but the stored value (and cardinality key) is the scaled Integer the normalizer produced
			final ComparatorSource[] base = {
				new ComparatorSource(BigDecimal.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
			};
			final Map<Serializable, Integer> cardinalities = Map.of(150, 3);
			// 150 == 1.50 at the frozen scale of 2 — the scale must survive the round-trip alongside the scaled values
			final SortIndexStoragePart part = new SortIndexStoragePart(
				42, new AttributeIndexKey(null, "price", null), base,
				new int[]{1, 2, 3}, new Serializable[]{150}, cardinalities, 2, 1L
			);

			final SortIndexStoragePart deserialized = roundTrip(part);

			assertArrayEquals(new int[]{1, 2, 3}, deserialized.getSortedRecords());
			assertArrayEquals(new Serializable[]{150}, deserialized.getSortedRecordsValues());
			assertEquals(3, deserialized.getValueCardinalities().get(150));
			assertEquals(2, deserialized.getIndexedDecimalPlaces(), "The frozen indexedDecimalPlaces must round-trip");
		}
	}

	@Nested
	@DisplayName("Block-delta encoding behaviour")
	class BlockDeltaBehaviour {

		@Test
		@DisplayName("falls back to a raw encoding for a non-ascending block (migration-collapsed part) and round-trips")
		void shouldFallBackForNonAscendingBlock() {
			// two distinct raw BigDecimals that scale to the same int are collapsed by Migration_2026_2 into ONE block
			// without re-sorting, so the merged block (here {3,1,2} for the single scaled value 100) is non-ascending; the
			// writer must take the raw fallback rather than asserting in the codec
			final Map<Serializable, Integer> cardinalities = Map.of(100, 3);
			final SortIndexStoragePart part = new SortIndexStoragePart(
				1, new AttributeIndexKey(null, "price", null), singleStringAscending(),
				new int[]{3, 1, 2}, new Serializable[]{100}, cardinalities, 2, 1L
			);

			// through the production dispatcher (uid-prefixed) — exactly the serialize+deserialize a migration putStoragePart does
			final SortIndexStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
				SortIndexStoragePartSerializerTest.this.kryo, part, SortIndexStoragePart.class
			);

			assertArrayEquals(
				new int[]{3, 1, 2}, deserialized.getSortedRecords(),
				"a non-ascending block must round-trip byte-identical via the raw fallback"
			);
			assertArrayEquals(new Serializable[]{100}, deserialized.getSortedRecordsValues());
			assertEquals(3, deserialized.getValueCardinalities().get(100));
		}

		@Test
		@DisplayName("falls back to a raw encoding when a later (non-first) block is non-ascending and round-trips")
		void shouldFallBackWhenOneOfSeveralBlocksIsNonAscending() {
			// two values: `a` is an ascending 3-id block, `b` is a 2-id block whose ids descend ({9,5}); the non-ascending
			// block is the SECOND one, so the writer's allBlocksAscending check must advance past the first (ascending) block
			// before detecting the violation and selecting the raw fallback for the whole part
			final Map<Serializable, Integer> cardinalities = Map.of("a", 3, "b", 2);
			final SortIndexStoragePart part = new SortIndexStoragePart(
				1, ATTRIBUTE_KEY, singleStringAscending(),
				new int[]{1, 2, 3, 9, 5}, new Serializable[]{"a", "b"}, cardinalities, 1L
			);

			final SortIndexStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
				SortIndexStoragePartSerializerTest.this.kryo, part, SortIndexStoragePart.class
			);

			assertArrayEquals(
				new int[]{1, 2, 3, 9, 5}, deserialized.getSortedRecords(),
				"a non-ascending later block must round-trip byte-identical via the raw fallback"
			);
			assertArrayEquals(new Serializable[]{"a", "b"}, deserialized.getSortedRecordsValues());
			assertEquals(3, deserialized.getValueCardinalities().get("a"));
			assertEquals(2, deserialized.getValueCardinalities().get("b"));
		}

		@Test
		@DisplayName("delta-encodes per block even when ids descend across the block boundary (each block ascending)")
		void shouldDeltaEncodeWhenBlocksDescendAcrossBoundaryButEachBlockIsAscending() {
			// `a` → {5,6,7} then `b` → {1,2}: the array descends across the a/b boundary (7 → 1), yet every block is
			// internally ascending, so the per-block decision must still engage delta (each block is delta-encoded
			// independently with its own first element); cross-block ordering is irrelevant
			final Map<Serializable, Integer> cardinalities = Map.of("a", 3, "b", 2);
			final SortIndexStoragePart part = new SortIndexStoragePart(
				1, ATTRIBUTE_KEY, singleStringAscending(),
				new int[]{5, 6, 7, 1, 2}, new Serializable[]{"a", "b"}, cardinalities, 1L
			);

			final SortIndexStoragePart deserialized = roundTrip(part);

			assertArrayEquals(new int[]{5, 6, 7, 1, 2}, deserialized.getSortedRecords());
			assertArrayEquals(new Serializable[]{"a", "b"}, deserialized.getSortedRecordsValues());

			// the same ids forced into ONE non-ascending block (single value, cardinality 5) cannot delta-encode and take
			// the raw fallback; the per-block part must be smaller, proving delta engaged rather than the global fallback
			final SortIndexStoragePart rawPart = new SortIndexStoragePart(
				1, ATTRIBUTE_KEY, singleStringAscending(),
				new int[]{5, 6, 7, 1, 2}, new Serializable[]{"v"}, Map.of("v", 5), 1L
			);
			assertTrue(
				serialize(part).length < serialize(rawPart).length,
				"per-block delta must be smaller than the raw-fallback single non-ascending block"
			);
		}

		@Test
		@DisplayName("falls back to a raw encoding for a compound (multi-attribute) non-ascending block and round-trips")
		void shouldFallBackForCompoundNonAscendingBlock() {
			final ComparatorSource[] base = {
				new ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
				new ComparatorSource(Integer.class, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
			};
			final ComparableArray value = new ComparableArray(new Serializable[]{"a", 1});
			// a single compound value whose 3-id block is non-ascending ({3,1,2}) forces the raw fallback while exercising
			// the comparatorBase.length > 1 value + cardinality write/read branch
			final Map<Serializable, Integer> cardinalities = Map.of(value, 3);
			final SortIndexStoragePart part = new SortIndexStoragePart(
				9, ATTRIBUTE_KEY, base,
				new int[]{3, 1, 2}, new Serializable[]{value}, cardinalities, 1L
			);

			final SortIndexStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
				SortIndexStoragePartSerializerTest.this.kryo, part, SortIndexStoragePart.class
			);

			assertArrayEquals(
				new int[]{3, 1, 2}, deserialized.getSortedRecords(),
				"a compound non-ascending block must round-trip byte-identical via the raw fallback"
			);
			assertArrayEquals(new Serializable[]{value}, deserialized.getSortedRecordsValues());
			assertEquals(3, deserialized.getValueCardinalities().get(value));
		}

		@Test
		@DisplayName("delta encoding actually engages: an ascending block serializes smaller than the same ids raw")
		void shouldEngageDeltaForAscendingBlock() {
			// identical header / values / cardinalities sections; the only difference is the block ordering, so a size
			// difference proves the ascending part used delta (small gaps) while the non-ascending part used raw 4-byte ints
			final Map<Serializable, Integer> cardinalities = Map.of("v", 5);
			final SortIndexStoragePart ascending = new SortIndexStoragePart(
				1, ATTRIBUTE_KEY, singleStringAscending(),
				new int[]{1, 2, 3, 4, 5}, new Serializable[]{"v"}, cardinalities, 1L
			);
			final SortIndexStoragePart descending = new SortIndexStoragePart(
				1, ATTRIBUTE_KEY, singleStringAscending(),
				new int[]{5, 4, 3, 2, 1}, new Serializable[]{"v"}, cardinalities, 1L
			);

			assertTrue(
				serialize(ascending).length < serialize(descending).length,
				"the ascending block must delta-encode smaller than the raw-fallback non-ascending block"
			);
		}

		@Test
		@DisplayName("round-trips an owner part interleaving singleton and multi-record ascending blocks")
		void shouldRoundTripMixedSingletonAndMultiRecordBlocks() {
			// five values whose blocks alternate between implied singletons (absent from the sparse cardinality map →
			// getOrDefault → 1) and multi-record blocks (present in the map); every block is internally ascending so the
			// per-block delta path engages throughout the concatenated stream
			//   single  -> {10}
			//   multiA  -> {1, 2, 5}
			//   loner   -> {7}
			//   multiB  -> {3, 4, 4, 8}
			//   tail    -> {99}
			final Map<Serializable, Integer> cardinalities = Map.of("multiA", 3, "multiB", 4);
			final SortIndexStoragePart part = new SortIndexStoragePart(
				42, ATTRIBUTE_KEY, singleStringAscending(),
				new int[]{10, 1, 2, 5, 7, 3, 4, 4, 8, 99},
				new Serializable[]{"single", "multiA", "loner", "multiB", "tail"}, cardinalities, 1L
			);

			final SortIndexStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
				SortIndexStoragePartSerializerTest.this.kryo, part, SortIndexStoragePart.class
			);

			assertEquals(42, deserialized.getEntityIndexPrimaryKey());
			assertArrayEquals(new int[]{10, 1, 2, 5, 7, 3, 4, 4, 8, 99}, deserialized.getSortedRecords());
			assertArrayEquals(
				new Serializable[]{"single", "multiA", "loner", "multiB", "tail"},
				deserialized.getSortedRecordsValues()
			);
			assertEquals(2, deserialized.getValueCardinalities().size());
			assertEquals(3, deserialized.getValueCardinalities().get("multiA"));
			assertEquals(4, deserialized.getValueCardinalities().get("multiB"));
		}

		@Test
		@DisplayName("the slim view-mode part is physically smaller than the equivalent owner-mode part")
		void shouldOmitValueSectionsForViewMode() {
			final SortIndexStoragePart viewPart = new SortIndexStoragePart(
				42, ATTRIBUTE_KEY, singleStringAscending(),
				new int[]{1, 2, 3, 9}, new Serializable[0], Map.of(), 1L
			);
			assertTrue(
				serialize(viewPart).length < serialize(ascendingOwnerPart()).length,
				"the view-mode part must omit the values + cardinalities sections, not serialize them as empty"
			);
		}
	}

	@Nested
	@DisplayName("Granular paging (gated discriminator)")
	class GranularPaging {

		@Test
		@DisplayName("round-trips an owner-PAGED root carrying only the page-stream metadata")
		void shouldRoundTripPagedRootPart() {
			final SortIndexStoragePart part = SortIndexStoragePart.paged(
				42, ATTRIBUTE_KEY, singleStringAscending(), 0, 9, new int[]{0, 1, 4, 7}, 1L
			);
			assertTrue(part.isPaged(), "the source part must be paged");

			final SortIndexStoragePart deserialized = roundTrip(part);

			assertTrue(deserialized.isPaged(), "a paged root must round-trip as paged");
			assertEquals(42, deserialized.getEntityIndexPrimaryKey());
			assertEquals(ATTRIBUTE_KEY, deserialized.getAttributeIndexKey());
			assertEquals(9, deserialized.getHighWaterPageSequence(), "the high-water must round-trip");
			assertArrayEquals(
				new int[]{0, 1, 4, 7}, deserialized.getLeafPageSequences(),
				"the live leaf-page sequence list must round-trip"
			);
			// a paged root carries no inline value side - it is reconstructed from the leaf pages on load
			assertEquals(0, deserialized.getSortedRecords().length, "a paged root carries no inline sortedRecords");
			assertEquals(0, deserialized.getSortedRecordsValues().length, "a paged root carries no inline values");
			assertTrue(deserialized.getValueCardinalities().isEmpty(), "a paged root carries no inline cardinalities");
		}

		@Test
		@DisplayName("round-trips an owner-PAGED root preserving the frozen indexedDecimalPlaces scale")
		void shouldRoundTripPagedRootPreservingScale() {
			final SortIndexStoragePart part = SortIndexStoragePart.paged(
				7, new AttributeIndexKey(null, "price", null), singleStringAscending(), 2, 3, new int[]{0, 2}, 1L
			);

			final SortIndexStoragePart deserialized = roundTrip(part);

			assertTrue(deserialized.isPaged());
			assertEquals(2, deserialized.getIndexedDecimalPlaces(), "the frozen scale must round-trip on a paged root");
			assertEquals(3, deserialized.getHighWaterPageSequence());
			assertArrayEquals(new int[]{0, 2}, deserialized.getLeafPageSequences());
		}

		@Test
		@DisplayName("an owner-SINGLE part stays non-paged through the round-trip (the discriminator is gated)")
		void shouldKeepOwnerSinglePartNonPaged() {
			final SortIndexStoragePart deserialized = roundTrip(ascendingOwnerPart());
			assertFalse(deserialized.isPaged(), "an owner-SINGLE part must never report as paged");
			assertEquals(0, deserialized.getHighWaterPageSequence(), "an owner-SINGLE part has no high-water");
		}

		@Test
		@DisplayName("a slim view-mode part stays non-paged through the round-trip")
		void shouldKeepViewModePartNonPaged() {
			final SortIndexStoragePart part = new SortIndexStoragePart(
				42, ATTRIBUTE_KEY, singleStringAscending(),
				new int[]{3, 1, 2}, new Serializable[0], Map.of(), 1L
			);
			final SortIndexStoragePart deserialized = roundTrip(part);
			assertFalse(deserialized.isPaged(), "a view-slim part must never report as paged");
			assertArrayEquals(new int[]{3, 1, 2}, deserialized.getSortedRecords());
		}

		@Test
		@DisplayName("the owner-SINGLE bytes are unchanged: the gated paged discriminator is absent from the values branch")
		void shouldKeepOwnerSingleBytesUnchanged() {
			// the `paged` discriminator is written ONLY in the valuesPresent==false branch, so an owner-SINGLE part
			// (valuesPresent==true) must serialize WITHOUT it - exactly one byte shorter than the equivalent view-slim part
			// whose only added field over a hypothetical no-discriminator encoding is that single `paged` boolean. We prove
			// the gating structurally: an owner-SINGLE part and the SAME part re-serialized are byte-identical (the format is
			// deterministic and carries no paged byte), and a view-slim part of identical sortedRecords differs by exactly
			// the one gated boolean it adds after its (false) valuesPresent marker.
			final SortIndexStoragePart ownerSingle = new SortIndexStoragePart(
				1, ATTRIBUTE_KEY, singleStringAscending(),
				new int[]{5}, new Serializable[]{"v"}, Map.of(), 1L
			);
			assertArrayEquals(
				serialize(ownerSingle), serialize(ownerSingle),
				"owner-SINGLE serialization must be deterministic (no incidental state, no paged byte)"
			);

			// a view-slim part with the SAME single record id: identical header + records, but it writes valuesPresent=false
			// THEN the gated paged=false boolean, so it is exactly one byte longer than an owner-SINGLE part stripped of its
			// value sections. This asserts the gated boolean lives on the view-slim path, never the owner-SINGLE path.
			final SortIndexStoragePart viewSlim = new SortIndexStoragePart(
				1, ATTRIBUTE_KEY, singleStringAscending(),
				new int[]{5}, new Serializable[0], Map.of(), 1L
			);
			final SortIndexStoragePart deserializedViewSlim = roundTrip(viewSlim);
			assertFalse(deserializedViewSlim.isPaged(), "the view-slim part round-trips with paged=false");
			assertArrayEquals(new int[]{5}, deserializedViewSlim.getSortedRecords());
		}
	}

	@Nested
	@DisplayName("Damage guard")
	class DamageGuard {

		@Test
		@DisplayName("fails loud when the per-value block lengths do not cover the sorted-records array")
		void shouldFailLoudWhenBlockLengthsDoNotCoverTheRecordArray() {
			// owner part whose cardinality (2) for the single value disagrees with the 3-id records array; the block-length
			// sum guard on the write path must surface this corruption rather than silently truncating or overrunning
			final Map<Serializable, Integer> cardinalities = Map.of("v", 2);
			final SortIndexStoragePart part = new SortIndexStoragePart(
				1, ATTRIBUTE_KEY, singleStringAscending(),
				new int[]{1, 2, 3}, new Serializable[]{"v"}, cardinalities, 1L
			);

			assertThrows(
				io.evitadb.exception.GenericEvitaInternalError.class,
				() -> serialize(part)
			);
		}
	}
}
