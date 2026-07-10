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
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceNameKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceTypeCardinalityIndexStoragePart;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NumberUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.Map;

import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip coverage for {@link ReferenceTypeCardinalityIndexStoragePartSerializer}, the granular-paging root format of
 * the reference-type cardinality index. A `paged` discriminator selects between the inline SINGLE shape — the
 * composed-key → count `(keys, payloads)` columns ride on the root — and the PAGED shape — only the page-stream metadata
 * (high-water + ordered leaf-page list) rides on the root, the columns living in separate leaf-page records. The
 * `referencedEntityPrimaryKey → reduced-index-PK bitmap` companion map rides inline on the root in BOTH shapes.
 *
 * The composed keys are signed `long`s (positive whole-index-PK counters interleave with negative per-reference
 * counters), so a negative key must survive the non-optimize-positive `writeVarLong(.., false)` sign handling. The
 * released 2025.x–2026.1 inline layout had NO `paged` discriminator; it is read by the registered backward-compatible
 * reader at the old serial-version-uid, exercised here by hand-encoding a legacy blob and decoding it through the
 * production dispatcher.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ReferenceTypeCardinalityIndexStoragePartSerializer round-trip (granular-paging format)")
@Tag(STORAGE)
@Tag(SERIALIZATION)
@Tag(REFERENCE)
class ReferenceTypeCardinalityIndexStoragePartSerializerTest {

	private static final int ENTITY_INDEX_PK = 42;
	private static final String REFERENCE_NAME = "testReference";
	/** The released 2025.x–2026.1 serial-version-uid of {@link ReferenceTypeCardinalityIndexStoragePart}. */
	private static final long LEGACY_2026_1_UID = 8276690113370094734L;

	private Kryo kryo;
	private ReadWriteKeyCompressor keyCompressor;
	private ReferenceTypeCardinalityIndexStoragePartSerializer serializer;

	@BeforeEach
	void setUp() {
		this.keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.serializer = new ReferenceTypeCardinalityIndexStoragePartSerializer(this.keyCompressor);
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(this.keyCompressor));
	}

	/**
	 * Serializes the given storage part to bytes with the serializer directly. The same Kryo instance is used for the
	 * matching {@link #roundTrip} so registration ids are stable across both directions.
	 *
	 * @param part the storage part to serialize
	 * @return the serialized bytes
	 */
	@Nonnull
	private byte[] serialize(@Nonnull ReferenceTypeCardinalityIndexStoragePart part) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			this.serializer.write(this.kryo, output, part);
		}
		return os.toByteArray();
	}

	/**
	 * Serializes the given storage part to bytes and reads it straight back. The same {@link ReadWriteKeyCompressor}
	 * instance backs both directions, so the reference-name key round-trips by id.
	 *
	 * @param part the storage part to round-trip
	 * @return the deserialized copy
	 */
	@Nonnull
	private ReferenceTypeCardinalityIndexStoragePart roundTrip(@Nonnull ReferenceTypeCardinalityIndexStoragePart part) {
		try (final Input input = new Input(serialize(part))) {
			return this.serializer.read(this.kryo, input, ReferenceTypeCardinalityIndexStoragePart.class);
		}
	}

	/**
	 * Builds a single-entry companion `referencedEntityPrimaryKey → reduced-index-PK bitmap` map.
	 *
	 * @return a companion map carrying one referenced PK mapped to a two-element bitmap
	 */
	@Nonnull
	private static Map<Integer, TransactionalBitmap> companionMap() {
		final Map<Integer, TransactionalBitmap> map = CollectionUtils.createHashMap(2);
		map.put(100, new TransactionalBitmap(1, 2));
		return map;
	}

	/**
	 * Builds a write-path SINGLE root carrying the inline `(keys, payloads)` columns (ascending key order) and the
	 * inline companion map.
	 *
	 * @return a SINGLE-shape storage part
	 */
	@Nonnull
	private static ReferenceTypeCardinalityIndexStoragePart singlePart() {
		return new ReferenceTypeCardinalityIndexStoragePart(
			ENTITY_INDEX_PK, REFERENCE_NAME, new long[]{10L, 20L, 30L}, new long[]{1L, 2L, 3L}, companionMap()
		);
	}

	/**
	 * Builds a write-path PAGED root carrying the page-stream metadata (high-water + ordered leaf-page list) and the
	 * inline companion map (the cardinality columns live in separate leaf-page records).
	 *
	 * @return a PAGED-shape storage part
	 */
	@Nonnull
	private static ReferenceTypeCardinalityIndexStoragePart pagedRootPart() {
		return ReferenceTypeCardinalityIndexStoragePart.paged(
			ENTITY_INDEX_PK, REFERENCE_NAME, 5, new int[]{0, 1, 2}, companionMap()
		);
	}

	/**
	 * Asserts the deserialized companion map carries exactly the {@link #companionMap()} entry (key set and per-key
	 * index-PK array).
	 *
	 * @param part the deserialized storage part
	 */
	private static void assertCompanionMap(@Nonnull ReferenceTypeCardinalityIndexStoragePart part) {
		final Map<Integer, TransactionalBitmap> map = part.getReferencedPrimaryKeysIndex();
		assertEquals(Collections.singleton(100), map.keySet(), "companion map key set must round-trip");
		final TransactionalBitmap bitmap = map.get(100);
		assertNotNull(bitmap, "companion bitmap must round-trip non-null");
		assertArrayEquals(new int[]{1, 2}, bitmap.getArray(), "companion bitmap contents must round-trip");
	}

	@Nested
	@DisplayName("Round-trip")
	class RoundTrip {

		@Test
		@DisplayName("round-trips a SINGLE root carrying the inline columns + companion map")
		void shouldRoundTripSinglePartWithInlineColumns() {
			final ReferenceTypeCardinalityIndexStoragePart deserialized = roundTrip(singlePart());

			assertEquals(ENTITY_INDEX_PK, deserialized.getEntityIndexPrimaryKey());
			assertEquals(REFERENCE_NAME, deserialized.getReferenceName());
			assertFalse(deserialized.isPaged(), "an inline root must round-trip as SINGLE");
			assertArrayEquals(new long[]{10L, 20L, 30L}, deserialized.getKeys(), "the key column must round-trip");
			assertArrayEquals(new long[]{1L, 2L, 3L}, deserialized.getPayloads(), "the count column must round-trip");
			assertCompanionMap(deserialized);
		}

		@Test
		@DisplayName("round-trips a PAGED root carrying the high-water + leaf-page list (columns omitted)")
		void shouldRoundTripPagedRootWithPageMetadata() {
			final ReferenceTypeCardinalityIndexStoragePart deserialized = roundTrip(pagedRootPart());

			assertEquals(ENTITY_INDEX_PK, deserialized.getEntityIndexPrimaryKey());
			assertEquals(REFERENCE_NAME, deserialized.getReferenceName());
			assertTrue(deserialized.isPaged(), "the paged discriminator must round-trip");
			assertEquals(5, deserialized.getHighWaterPageSequence(), "the high-water must round-trip");
			assertArrayEquals(new int[]{0, 1, 2}, deserialized.getLeafPageSequences(), "the leaf-page list must round-trip");
			assertNull(deserialized.getKeys(), "a paged root carries no inline key column");
			assertNull(deserialized.getPayloads(), "a paged root carries no inline count column");
			assertCompanionMap(deserialized);
		}

		@Test
		@DisplayName("round-trips a SINGLE root whose key column holds a negative composed key")
		void shouldRoundTripSignedNegativeComposedKeys() {
			// the per-reference counter key is -pack(indexPk, refPk) — a negative signed long that must survive the
			// non-optimize-positive writeVarLong(.., false) sign handling
			final long negativeKey = -NumberUtils.pack(1, 5);
			final ReferenceTypeCardinalityIndexStoragePart part = new ReferenceTypeCardinalityIndexStoragePart(
				ENTITY_INDEX_PK, REFERENCE_NAME, new long[]{negativeKey}, new long[]{7L}, companionMap()
			);

			final ReferenceTypeCardinalityIndexStoragePart deserialized = roundTrip(part);

			assertFalse(deserialized.isPaged());
			assertArrayEquals(new long[]{negativeKey}, deserialized.getKeys(), "the negative composed key must round-trip");
			assertArrayEquals(new long[]{7L}, deserialized.getPayloads(), "the count must round-trip");
		}
	}

	@Nested
	@DisplayName("Production dispatch (uid-prefixed) round-trip")
	class ProductionDispatchRoundTrip {

		@Test
		@DisplayName("round-trips a SINGLE root through the production dispatcher")
		void shouldRoundTripSingleThroughDispatcher() {
			final ReferenceTypeCardinalityIndexStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
				ReferenceTypeCardinalityIndexStoragePartSerializerTest.this.kryo,
				singlePart(), ReferenceTypeCardinalityIndexStoragePart.class
			);

			assertFalse(deserialized.isPaged());
			assertArrayEquals(new long[]{10L, 20L, 30L}, deserialized.getKeys());
			assertArrayEquals(new long[]{1L, 2L, 3L}, deserialized.getPayloads());
			assertCompanionMap(deserialized);
		}

		@Test
		@DisplayName("round-trips a PAGED root through the production dispatcher")
		void shouldRoundTripPagedThroughDispatcher() {
			final ReferenceTypeCardinalityIndexStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
				ReferenceTypeCardinalityIndexStoragePartSerializerTest.this.kryo,
				pagedRootPart(), ReferenceTypeCardinalityIndexStoragePart.class
			);

			assertTrue(deserialized.isPaged());
			assertEquals(5, deserialized.getHighWaterPageSequence());
			assertArrayEquals(new int[]{0, 1, 2}, deserialized.getLeafPageSequences());
			assertNull(deserialized.getKeys());
			assertNull(deserialized.getPayloads());
			assertCompanionMap(deserialized);
		}
	}

	@Nested
	@DisplayName("Lazy upgrade from older formats")
	class LazyUpgrade {

		@Test
		@DisplayName("reads a 2026.1 inline blob (no paged discriminator) as a SINGLE root")
		void shouldReadLegacy20261FormatAsSingle() {
			final ReferenceTypeCardinalityIndexStoragePart deserialized = StoragePartSerializerTestSupport.decode(
				ReferenceTypeCardinalityIndexStoragePartSerializerTest.this.kryo,
				encodeLegacy20261Bytes(), ReferenceTypeCardinalityIndexStoragePart.class
			);

			assertEquals(ENTITY_INDEX_PK, deserialized.getEntityIndexPrimaryKey());
			assertEquals(REFERENCE_NAME, deserialized.getReferenceName());
			assertFalse(deserialized.isPaged(), "the released format never paged — it must load as SINGLE");
			assertArrayEquals(new long[]{10L, 20L}, deserialized.getKeys(), "the inline keys must load");
			assertArrayEquals(new long[]{1L, 2L}, deserialized.getPayloads(), "the inline counts must load");
			assertCompanionMap(deserialized);
		}

		@Test
		@DisplayName("a 2026.1 inline blob survives a migration-style read-old then write-new round-trip")
		void shouldSurviveMigrationStyleReadOldWriteNew() {
			// a storage-protocol migration reads each part through the registered (old) reader and re-persists it through
			// the CURRENT writer — emulate exactly that: read a 2026.1 blob, then re-encode + read back through the current
			// dispatcher
			final ReferenceTypeCardinalityIndexStoragePart migrated = StoragePartSerializerTestSupport.decode(
				ReferenceTypeCardinalityIndexStoragePartSerializerTest.this.kryo,
				encodeLegacy20261Bytes(), ReferenceTypeCardinalityIndexStoragePart.class
			);

			final ReferenceTypeCardinalityIndexStoragePart rePersisted = StoragePartSerializerTestSupport.roundTrip(
				ReferenceTypeCardinalityIndexStoragePartSerializerTest.this.kryo,
				migrated, ReferenceTypeCardinalityIndexStoragePart.class
			);

			assertFalse(rePersisted.isPaged());
			assertArrayEquals(new long[]{10L, 20L}, rePersisted.getKeys());
			assertArrayEquals(new long[]{1L, 2L}, rePersisted.getPayloads());
			assertCompanionMap(rePersisted);
		}

		/**
		 * Hand-encodes the released 2026.1 inline format (uid-prefixed): `entityIdx`, `partId`, `refNameKeyId`, then the
		 * INLINE cardinality columns as `count` followed by `(varLong key, varInt value)*` with NO paged discriminator,
		 * then the companion `referencedEntityPrimaryKey → bitmap` map. The uid prefix routes the blob to the registered
		 * 2026.1 reader through the production dispatcher.
		 *
		 * @return the 2026.1-format bytes
		 */
		@Nonnull
		private byte[] encodeLegacy20261Bytes() {
			final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
			try (final Output output = new Output(os, 4_096)) {
				output.writeLong(LEGACY_2026_1_UID);
				output.writeInt(ENTITY_INDEX_PK);
				output.writeVarLong(7L, true);
				output.writeVarInt(
					ReferenceTypeCardinalityIndexStoragePartSerializerTest.this.keyCompressor.getId(
						new ReferenceNameKey(REFERENCE_NAME)
					),
					true
				);
				// inline cardinality columns: count then (key, value)* — no paged discriminator in the released format
				output.writeVarInt(2, true);
				output.writeVarLong(10L, false);
				output.writeVarInt(1, false);
				output.writeVarLong(20L, false);
				output.writeVarInt(2, false);
				// companion map: one referenced PK mapped to a two-element bitmap
				output.writeVarInt(1, true);
				output.writeVarInt(100, true);
				ReferenceTypeCardinalityIndexStoragePartSerializerTest.this.kryo.writeObject(
					output, new TransactionalBitmap(1, 2)
				);
			}
			return os.toByteArray();
		}
	}

	@Nested
	@DisplayName("Serialized size")
	class SerializedSize {

		@Test
		@DisplayName("a PAGED root (page metadata only) is physically smaller than the equivalent SINGLE root")
		void shouldOmitColumnsForPagedRoot() {
			// a SINGLE root carrying many inline (key, count) pairs must be larger than a PAGED root carrying only the
			// high-water + a short leaf-page list and the same companion map
			final long[] keys = new long[64];
			final long[] payloads = new long[64];
			for (int i = 0; i < keys.length; i++) {
				keys[i] = i;
				payloads[i] = i + 1;
			}
			final ReferenceTypeCardinalityIndexStoragePart bigSingle = new ReferenceTypeCardinalityIndexStoragePart(
				ENTITY_INDEX_PK, REFERENCE_NAME, keys, payloads, companionMap()
			);

			assertTrue(
				serialize(pagedRootPart()).length < serialize(bigSingle).length,
				"the paged root must omit the inline columns, not serialize them"
			);
		}
	}
}
