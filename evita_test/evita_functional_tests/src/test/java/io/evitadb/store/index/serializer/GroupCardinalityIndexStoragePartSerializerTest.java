/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.dataType.champ.ChampMap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.map.PersistentTransactionalMap;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GroupCardinalityIndexStoragePart;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SERIALIZATION;

/**
 * Tests for {@link GroupCardinalityIndexStoragePartSerializer} verifying correct
 * serialization and deserialization round-trips for the {@link GroupCardinalityIndexStoragePart}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("GroupCardinalityIndexStoragePartSerializer round-trip")
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(SERIALIZATION)
class GroupCardinalityIndexStoragePartSerializerTest {

	private Kryo kryo;
	private ReadWriteKeyCompressor keyCompressor;
	private GroupCardinalityIndexStoragePartSerializer serializer;

	@BeforeEach
	void setUp() {
		this.keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.serializer = new GroupCardinalityIndexStoragePartSerializer(this.keyCompressor);
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(this.keyCompressor));
	}

	/**
	 * Serializes the given storage part to bytes using the serializer directly,
	 * then deserializes it back and returns the result.
	 *
	 * @param storagePart the storage part to round-trip
	 * @return the deserialized copy
	 */
	@Nonnull
	private GroupCardinalityIndexStoragePart roundTrip(
		@Nonnull GroupCardinalityIndexStoragePart storagePart
	) {
		// ensure the storage part PK is computed before serialization
		storagePart.computeUniquePartIdAndSet(this.keyCompressor);

		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			this.serializer.write(this.kryo, output, storagePart);
		}
		try (final Input input = new Input(os.toByteArray())) {
			return this.serializer.read(this.kryo, input, GroupCardinalityIndexStoragePart.class);
		}
	}

	@Nested
	@DisplayName("Round-trip serialization")
	class RoundTripTest {

		@Test
		@DisplayName("should serialize and deserialize with single entry")
		void shouldRoundTripSingleEntry() {
			final Map<Integer, Integer> pkCardinalities = CollectionUtils.createHashMap(4);
			pkCardinalities.put(10, 2);

			final Map<Integer, TransactionalBitmap> refPksIndex = CollectionUtils.createHashMap(4);
			final TransactionalBitmap bitmap = new TransactionalBitmap();
			bitmap.add(10);
			refPksIndex.put(1, bitmap);

			final GroupCardinalityIndexStoragePart original = new GroupCardinalityIndexStoragePart(
				42, "CATEGORY", pkCardinalities, refPksIndex
			);

			final GroupCardinalityIndexStoragePart deserialized = roundTrip(original);

			assertEquals(42, deserialized.getEntityIndexPrimaryKey());
			assertEquals("CATEGORY", deserialized.getReferenceName());
			assertEquals(1, deserialized.getPkCardinalities().size());
			assertEquals(2, deserialized.getPkCardinalities().get(10));
			assertEquals(1, deserialized.getReferencedPrimaryKeysIndex().size());
			assertTrue(deserialized.getReferencedPrimaryKeysIndex().get(1).contains(10));
		}

		@Test
		@DisplayName("should serialize and deserialize with multiple entries")
		void shouldRoundTripMultipleEntries() {
			final Map<Integer, Integer> pkCardinalities = CollectionUtils.createHashMap(8);
			pkCardinalities.put(10, 3);
			pkCardinalities.put(20, 1);
			pkCardinalities.put(30, 2);

			final Map<Integer, TransactionalBitmap> refPksIndex = CollectionUtils.createHashMap(8);

			final TransactionalBitmap bitmap1 = new TransactionalBitmap();
			bitmap1.add(10);
			bitmap1.add(20);
			refPksIndex.put(1, bitmap1);

			final TransactionalBitmap bitmap2 = new TransactionalBitmap();
			bitmap2.add(10);
			bitmap2.add(30);
			refPksIndex.put(2, bitmap2);

			final TransactionalBitmap bitmap3 = new TransactionalBitmap();
			bitmap3.add(10);
			refPksIndex.put(3, bitmap3);

			final GroupCardinalityIndexStoragePart original = new GroupCardinalityIndexStoragePart(
				7, "BRAND", pkCardinalities, refPksIndex
			);

			final GroupCardinalityIndexStoragePart deserialized = roundTrip(original);

			assertEquals(7, deserialized.getEntityIndexPrimaryKey());
			assertEquals("BRAND", deserialized.getReferenceName());

			// verify PK cardinalities
			final Map<Integer, Integer> deserializedCards = deserialized.getPkCardinalities();
			assertEquals(3, deserializedCards.size());
			assertEquals(3, deserializedCards.get(10));
			assertEquals(1, deserializedCards.get(20));
			assertEquals(2, deserializedCards.get(30));

			// verify referenced PKs index
			final Map<Integer, TransactionalBitmap> deserializedIndex =
				deserialized.getReferencedPrimaryKeysIndex();
			assertEquals(3, deserializedIndex.size());

			final TransactionalBitmap desBitmap1 = deserializedIndex.get(1);
			assertNotNull(desBitmap1);
			assertTrue(desBitmap1.contains(10));
			assertTrue(desBitmap1.contains(20));
			assertEquals(2, desBitmap1.size());

			final TransactionalBitmap desBitmap2 = deserializedIndex.get(2);
			assertNotNull(desBitmap2);
			assertTrue(desBitmap2.contains(10));
			assertTrue(desBitmap2.contains(30));
			assertEquals(2, desBitmap2.size());

			final TransactionalBitmap desBitmap3 = deserializedIndex.get(3);
			assertNotNull(desBitmap3);
			assertTrue(desBitmap3.contains(10));
			assertEquals(1, desBitmap3.size());
		}

		@Test
		@DisplayName("should serialize and deserialize with empty data")
		void shouldRoundTripEmptyData() {
			final Map<Integer, Integer> pkCardinalities = CollectionUtils.createHashMap(4);
			final Map<Integer, TransactionalBitmap> refPksIndex = CollectionUtils.createHashMap(4);

			final GroupCardinalityIndexStoragePart original = new GroupCardinalityIndexStoragePart(
				99, "EMPTY_REF", pkCardinalities, refPksIndex
			);

			final GroupCardinalityIndexStoragePart deserialized = roundTrip(original);

			assertEquals(99, deserialized.getEntityIndexPrimaryKey());
			assertEquals("EMPTY_REF", deserialized.getReferenceName());
			assertTrue(deserialized.getPkCardinalities().isEmpty());
			assertTrue(deserialized.getReferencedPrimaryKeysIndex().isEmpty());
		}

		@Test
		@DisplayName("should preserve storage part PK after round-trip")
		void shouldPreserveStoragePartPk() {
			final Map<Integer, Integer> pkCardinalities = CollectionUtils.createHashMap(4);
			pkCardinalities.put(5, 1);

			final Map<Integer, TransactionalBitmap> refPksIndex = CollectionUtils.createHashMap(4);
			final TransactionalBitmap bitmap = new TransactionalBitmap();
			bitmap.add(5);
			refPksIndex.put(1, bitmap);

			final GroupCardinalityIndexStoragePart original = new GroupCardinalityIndexStoragePart(
				15, "TAG", pkCardinalities, refPksIndex
			);

			// compute and set the storage part PK
			final long expectedPartPk = original.computeUniquePartIdAndSet(
				GroupCardinalityIndexStoragePartSerializerTest.this.keyCompressor);

			final GroupCardinalityIndexStoragePart deserialized = roundTrip(original);

			assertNotNull(deserialized.getStoragePartPK());
			assertEquals(expectedPartPk, deserialized.getStoragePartPK());
		}
	}

	/**
	 * In production the storage part holds the live `pkCardinalities` map by reference, and that
	 * map is a {@link PersistentTransactionalMap} rather than a plain {@link Map}. The serializer
	 * iterates it via `size()` + `entrySet()`, so these tests pin that the Kryo write path reads a
	 * {@link PersistentTransactionalMap}-backed part correctly in both backing states the map can
	 * be in when it is flushed: a thawed warm-up buffer and a sealed (committed) snapshot.
	 */
	@Nested
	@DisplayName("Persistent transactional map backing")
	class PersistentTransactionalMapBackingTest {

		@Test
		@DisplayName("should round-trip a part whose cardinalities are a thawed warm-up map")
		void shouldRoundTripThawedPersistentTransactionalMap() {
			// a freshly populated PersistentTransactionalMap is held thawed (a mutable HashMap) -
			// this mirrors the bulk warm-up / import path before the catalog becomes transactional
			final PersistentTransactionalMap<Integer, Integer> pkCardinalities =
				new PersistentTransactionalMap<>(CollectionUtils.createHashMap(4));
			pkCardinalities.put(10, 3);
			pkCardinalities.put(20, 1);

			final Map<Integer, TransactionalBitmap> refPksIndex = CollectionUtils.createHashMap(4);
			final TransactionalBitmap bitmap = new TransactionalBitmap();
			bitmap.add(10);
			bitmap.add(20);
			refPksIndex.put(1, bitmap);

			final GroupCardinalityIndexStoragePart original = new GroupCardinalityIndexStoragePart(
				51, "CATEGORY", pkCardinalities, refPksIndex
			);

			final GroupCardinalityIndexStoragePart deserialized = roundTrip(original);

			assertEquals(51, deserialized.getEntityIndexPrimaryKey());
			assertEquals("CATEGORY", deserialized.getReferenceName());
			assertEquals(2, deserialized.getPkCardinalities().size());
			assertEquals(3, deserialized.getPkCardinalities().get(10));
			assertEquals(1, deserialized.getPkCardinalities().get(20));
			assertEquals(1, deserialized.getReferencedPrimaryKeysIndex().size());
			assertTrue(deserialized.getReferencedPrimaryKeysIndex().get(1).contains(10));
			assertTrue(deserialized.getReferencedPrimaryKeysIndex().get(1).contains(20));
		}

		@Test
		@DisplayName("should round-trip a part whose cardinalities are a sealed committed snapshot")
		void shouldRoundTripSealedPersistentTransactionalMap() {
			// adopting a ChampMap seals the map in O(1) - this mirrors the transactional steady
			// state where the commit path hands the just-derived immutable snapshot to the part
			final ChampMap<Integer, Integer> snapshot = ChampMap.<Integer, Integer>empty()
				.updated(10, 3)
				.updated(20, 1)
				.updated(30, 2);
			final PersistentTransactionalMap<Integer, Integer> pkCardinalities =
				new PersistentTransactionalMap<>(snapshot);

			final Map<Integer, TransactionalBitmap> refPksIndex = CollectionUtils.createHashMap(4);
			final TransactionalBitmap bitmap = new TransactionalBitmap();
			bitmap.add(10);
			bitmap.add(30);
			refPksIndex.put(2, bitmap);

			final GroupCardinalityIndexStoragePart original = new GroupCardinalityIndexStoragePart(
				52, "BRAND", pkCardinalities, refPksIndex
			);

			final GroupCardinalityIndexStoragePart deserialized = roundTrip(original);

			assertEquals(52, deserialized.getEntityIndexPrimaryKey());
			assertEquals("BRAND", deserialized.getReferenceName());
			assertEquals(3, deserialized.getPkCardinalities().size());
			assertEquals(3, deserialized.getPkCardinalities().get(10));
			assertEquals(1, deserialized.getPkCardinalities().get(20));
			assertEquals(2, deserialized.getPkCardinalities().get(30));
			assertEquals(1, deserialized.getReferencedPrimaryKeysIndex().size());
			assertTrue(deserialized.getReferencedPrimaryKeysIndex().get(2).contains(10));
			assertTrue(deserialized.getReferencedPrimaryKeysIndex().get(2).contains(30));
		}

		@Test
		@DisplayName("should round-trip an empty persistent transactional map")
		void shouldRoundTripEmptyPersistentTransactionalMap() {
			final PersistentTransactionalMap<Integer, Integer> pkCardinalities =
				new PersistentTransactionalMap<>(CollectionUtils.createHashMap(4));
			final Map<Integer, TransactionalBitmap> refPksIndex = CollectionUtils.createHashMap(4);

			final GroupCardinalityIndexStoragePart original = new GroupCardinalityIndexStoragePart(
				53, "EMPTY_REF", pkCardinalities, refPksIndex
			);

			final GroupCardinalityIndexStoragePart deserialized = roundTrip(original);

			assertEquals(53, deserialized.getEntityIndexPrimaryKey());
			assertEquals("EMPTY_REF", deserialized.getReferenceName());
			assertTrue(deserialized.getPkCardinalities().isEmpty());
			assertTrue(deserialized.getReferencedPrimaryKeysIndex().isEmpty());
		}
	}
}
