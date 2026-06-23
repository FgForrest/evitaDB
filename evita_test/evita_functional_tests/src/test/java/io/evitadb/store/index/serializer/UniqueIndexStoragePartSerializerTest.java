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
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexStoragePart;
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
import java.util.Collections;
import java.util.Map;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip coverage for {@link UniqueIndexStoragePartSerializer}, focused on the slim view-mode format: a unique
 * index that is folded into the shared `value→ValueToRecord` tree owned by the filter index runs in VIEW mode and
 * re-derives its value-to-record map and record-id bitmap from that shared tree on load, so its storage part omits
 * both sections behind a single boolean marker ({@code dataPresent}). Standalone (OWNER) unique indexes still carry
 * the full map + bitmap. The legacy always-present layout is read by the dropped legacy serializer (exercised by the
 * end-to-end backward-compatibility test on real old catalogs) and is intentionally out of scope here.
 *
 * Since the type-less legacy format was dropped, the `attributeType` is a non-null invariant: a concrete type
 * round-trips, and constructing a part with a null type fails fast at construction rather than producing a part that
 * cannot be read.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("UniqueIndexStoragePartSerializer round-trip (slim view-mode format)")
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(SERIALIZATION)
class UniqueIndexStoragePartSerializerTest {
	private static final AttributeIndexKey ATTRIBUTE_KEY = new AttributeIndexKey(null, "code", null);
	// the 2025.5 format used a different (bridge-encoded) attribute-key layout and a throwing write(); it is read only
	// by the end-to-end backward-compatibility test on real old catalogs, so it is intentionally out of scope here.
	/** The 2026.1 serial-version-uid of {@link UniqueIndexStoragePart} (kept registered for old catalogs). */
	private static final long LEGACY_2026_1_UID = -3921198859032670410L;

	private Kryo kryo;
	private ReadWriteKeyCompressor keyCompressor;
	private UniqueIndexStoragePartSerializer serializer;

	@BeforeEach
	void setUp() {
		this.keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.serializer = new UniqueIndexStoragePartSerializer(this.keyCompressor);
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
	private byte[] serialize(@Nonnull UniqueIndexStoragePart part) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			this.serializer.write(this.kryo, output, part);
		}
		return os.toByteArray();
	}

	/**
	 * Serializes the given storage part to bytes and reads it straight back. The same {@link ReadWriteKeyCompressor}
	 * instance backs both directions, so the attribute key round-trips by id.
	 *
	 * @param part the storage part to round-trip
	 * @return the deserialized copy
	 */
	@Nonnull
	private UniqueIndexStoragePart roundTrip(@Nonnull UniqueIndexStoragePart part) {
		try (final Input input = new Input(serialize(part))) {
			return this.serializer.read(this.kryo, input, UniqueIndexStoragePart.class);
		}
	}

	/**
	 * Builds a full OWNER part carrying the value-to-record map and the record-id bitmap.
	 *
	 * @return an owner-mode storage part
	 */
	@Nonnull
	private static UniqueIndexStoragePart ownerPart() {
		final Map<Serializable, Integer> uniqueValueToRecordId = Map.of("apple", 1, "banana", 2);
		final Bitmap recordIds = new TransactionalBitmap(1, 2);
		final UniqueIndexStoragePart part = new UniqueIndexStoragePart(
			42, ATTRIBUTE_KEY, String.class, uniqueValueToRecordId, recordIds
		);
		part.setStoragePartPK(7L);
		return part;
	}

	/**
	 * Builds a slim VIEW part — no value-to-record map and no record-id bitmap; the data lives in the shared
	 * filter tree.
	 *
	 * @return a view-mode storage part
	 */
	@Nonnull
	private static UniqueIndexStoragePart viewPart() {
		final UniqueIndexStoragePart part = new UniqueIndexStoragePart(42, ATTRIBUTE_KEY, String.class);
		part.setStoragePartPK(7L);
		return part;
	}

	@Nested
	@DisplayName("Round-trip")
	class RoundTrip {

		@Test
		@DisplayName("round-trips an owner-mode part carrying the value map + record-id bitmap")
		void shouldRoundTripOwnerModePart() {
			final UniqueIndexStoragePart deserialized = roundTrip(ownerPart());

			assertEquals(42, deserialized.getEntityIndexPrimaryKey());
			assertEquals(7L, deserialized.getStoragePartPK());
			assertEquals(ATTRIBUTE_KEY, deserialized.getAttributeIndexKey());
			assertSame(String.class, deserialized.getType());
			assertTrue(deserialized.isDataPresent(), "an owner-mode part must round-trip with its data present");

			final Map<Serializable, Integer> values = deserialized.getUniqueValueToRecordId();
			assertNotNull(values, "owner-mode value map must round-trip non-null");
			assertEquals(2, values.size());
			assertEquals(1, values.get("apple"));
			assertEquals(2, values.get("banana"));

			final Bitmap recordIds = deserialized.getRecordIds();
			assertNotNull(recordIds, "owner-mode record-id bitmap must round-trip non-null");
			assertArrayEquals(new int[]{1, 2}, recordIds.getArray());
		}

		@Test
		@DisplayName("round-trips a slim view-mode part with the value sections omitted")
		void shouldRoundTripSlimViewModePart() {
			final UniqueIndexStoragePart deserialized = roundTrip(viewPart());

			assertEquals(42, deserialized.getEntityIndexPrimaryKey());
			assertEquals(7L, deserialized.getStoragePartPK());
			assertEquals(ATTRIBUTE_KEY, deserialized.getAttributeIndexKey());
			assertSame(String.class, deserialized.getType());
			assertFalse(deserialized.isDataPresent(), "a view-mode part must round-trip with no data present");
			assertNull(deserialized.getUniqueValueToRecordId(), "view-mode value map must round-trip null");
			assertNull(deserialized.getRecordIds(), "view-mode record-id bitmap must round-trip null");
		}
	}

	@Nested
	@DisplayName("Production dispatch (uid-prefixed) round-trip")
	class ProductionDispatchRoundTrip {

		@Test
		@DisplayName("round-trips an owner-mode part through the production dispatcher, rebuilding the bitmap")
		void shouldRoundTripOwnerModeThroughDispatcher() {
			final UniqueIndexStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
				UniqueIndexStoragePartSerializerTest.this.kryo, ownerPart(), UniqueIndexStoragePart.class
			);

			assertTrue(deserialized.isDataPresent());
			final Map<Serializable, Integer> values = deserialized.getUniqueValueToRecordId();
			assertNotNull(values);
			assertEquals(2, values.size());
			assertEquals(1, values.get("apple"));
			assertEquals(2, values.get("banana"));
			// the bitmap is no longer persisted; it is reconstructed from the map values on read
			final Bitmap recordIds = deserialized.getRecordIds();
			assertNotNull(recordIds, "the record-id bitmap must be reconstructed from the map values");
			assertArrayEquals(new int[]{1, 2}, recordIds.getArray());
		}

		@Test
		@DisplayName("round-trips a slim view-mode part through the production dispatcher")
		void shouldRoundTripViewModeThroughDispatcher() {
			final UniqueIndexStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
				UniqueIndexStoragePartSerializerTest.this.kryo, viewPart(), UniqueIndexStoragePart.class
			);

			assertFalse(deserialized.isDataPresent());
			assertNull(deserialized.getUniqueValueToRecordId());
			assertNull(deserialized.getRecordIds());
		}

		@Test
		@DisplayName("the reconstructed bitmap dedupes — its array equals the distinct set of the map values")
		void shouldReconstructBitmapAsDistinctSetOfValues() {
			// two distinct keys may map to the same record id; the rebuilt bitmap must collapse the duplicate
			final Map<Serializable, Integer> map = Map.of("a", 5, "b", 5, "c", 9);
			final Bitmap recordIds = new TransactionalBitmap(5, 9);
			final UniqueIndexStoragePart part = new UniqueIndexStoragePart(1, ATTRIBUTE_KEY, String.class, map, recordIds);
			part.setStoragePartPK(3L);

			final UniqueIndexStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
				UniqueIndexStoragePartSerializerTest.this.kryo, part, UniqueIndexStoragePart.class
			);

			final Bitmap rebuilt = deserialized.getRecordIds();
			assertNotNull(rebuilt);
			assertArrayEquals(new int[]{5, 9}, rebuilt.getArray());
		}
	}

	@Nested
	@DisplayName("Lazy upgrade from older formats")
	class LazyUpgrade {

		@Test
		@DisplayName("reads a 2026.1 always-present blob (no marker, bitmap present) through the dispatcher")
		void shouldReadLegacy20261Format() {
			// the 2026.1 format wrote: entityIdx, partId, keyId, class, bitmap, mapSize, (key, fixed-int value)*
			// with NO dataPresent marker and the value as a fixed 4-byte int — encode it by hand and read it back
			final byte[] legacyBytes = encode20261OwnerBytes();

			final UniqueIndexStoragePart deserialized = StoragePartSerializerTestSupport.decode(
				UniqueIndexStoragePartSerializerTest.this.kryo, legacyBytes, UniqueIndexStoragePart.class
			);

			assertTrue(deserialized.isDataPresent());
			final Map<Serializable, Integer> values = deserialized.getUniqueValueToRecordId();
			assertNotNull(values);
			assertEquals(2, values.size());
			assertEquals(1, values.get("apple"));
			assertEquals(2, values.get("banana"));
			assertNotNull(deserialized.getRecordIds());
			assertArrayEquals(new int[]{1, 2}, deserialized.getRecordIds().getArray());
		}

		@Test
		@DisplayName("a 2026.1 owner part survives a migration-style read-old then write-new round-trip")
		void shouldSurviveMigrationStyleReadOldWriteNew() {
			// a storage-protocol migration (e.g. Migration_2025_6) reads each UniqueIndexStoragePart through the
			// registered (possibly old) reader and re-persists it through the CURRENT writer. Emulate exactly that:
			// read a 2026.1 blob through the dispatcher, then re-encode it with the current serializer and read back.
			final UniqueIndexStoragePart migrated = StoragePartSerializerTestSupport.decode(
				UniqueIndexStoragePartSerializerTest.this.kryo, encode20261OwnerBytes(), UniqueIndexStoragePart.class
			);

			final UniqueIndexStoragePart rePersisted = StoragePartSerializerTestSupport.roundTrip(
				UniqueIndexStoragePartSerializerTest.this.kryo, migrated, UniqueIndexStoragePart.class
			);

			assertTrue(rePersisted.isDataPresent());
			final Map<Serializable, Integer> values = rePersisted.getUniqueValueToRecordId();
			assertNotNull(values);
			assertEquals(1, values.get("apple"));
			assertEquals(2, values.get("banana"));
			assertNotNull(rePersisted.getRecordIds());
			assertArrayEquals(new int[]{1, 2}, rePersisted.getRecordIds().getArray());
		}

		/**
		 * Hand-encodes the 2026.1 always-present owner format for {@link #ownerPart()} (uid-prefixed), matching the
		 * dropped 2026.1 serializer's wire exactly so the production dispatcher routes it to the registered 2026.1
		 * reader.
		 *
		 * @return the 2026.1-format bytes
		 */
		@Nonnull
		private byte[] encode20261OwnerBytes() {
			final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
			try (final Output output = new Output(os, 4_096)) {
				output.writeLong(LEGACY_2026_1_UID);
				output.writeInt(42);
				output.writeVarLong(7L, true);
				output.writeVarInt(
					UniqueIndexStoragePartSerializerTest.this.keyCompressor.getId(ATTRIBUTE_KEY), true
				);
				UniqueIndexStoragePartSerializerTest.this.kryo.writeClass(output, String.class);
				UniqueIndexStoragePartSerializerTest.this.kryo.writeObject(output, new TransactionalBitmap(1, 2));
				output.writeVarInt(2, true);
				UniqueIndexStoragePartSerializerTest.this.kryo.writeObject(output, "apple");
				output.writeInt(1);
				UniqueIndexStoragePartSerializerTest.this.kryo.writeObject(output, "banana");
				output.writeInt(2);
			}
			return os.toByteArray();
		}
	}

	@Nested
	@DisplayName("Serialized size")
	class SerializedSize {

		@Test
		@DisplayName("the slim view-mode part is physically smaller than the equivalent owner-mode part")
		void shouldOmitValueSectionsForViewMode() {
			assertTrue(
				serialize(viewPart()).length < serialize(ownerPart()).length,
				"the slim part must omit the value map + record-id bitmap, not serialize them as empty"
			);
		}
	}

	@Nested
	@DisplayName("Construction invariants")
	class ConstructionInvariants {

		@Test
		@DisplayName("round-trips a concrete attributeType")
		void shouldRoundTripConcreteAttributeType() {
			final UniqueIndexStoragePart deserialized = roundTrip(ownerPart());

			assertSame(String.class, deserialized.getType());
		}

		@Test
		@DisplayName("the owner-mode constructor rejects a null attributeType")
		void shouldRejectNullAttributeTypeForOwnerPart() {
			final Map<Serializable, Integer> uniqueValueToRecordId = Map.of("apple", 1);
			final Bitmap recordIds = new TransactionalBitmap(1);
			assertThrows(
				NullPointerException.class,
				() -> new UniqueIndexStoragePart(42, ATTRIBUTE_KEY, null, uniqueValueToRecordId, recordIds),
				"a null attributeType must be rejected — the type-less legacy format is unsupported"
			);
		}

		@Test
		@DisplayName("the view-mode constructor rejects a null attributeType")
		void shouldRejectNullAttributeTypeForViewPart() {
			assertThrows(
				NullPointerException.class,
				() -> new UniqueIndexStoragePart(42, ATTRIBUTE_KEY, null),
				"a null attributeType must be rejected — the type-less legacy format is unsupported"
			);
		}
	}
}
