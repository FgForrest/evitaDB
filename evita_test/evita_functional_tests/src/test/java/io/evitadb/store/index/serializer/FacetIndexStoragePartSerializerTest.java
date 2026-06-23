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
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FacetIndexStoragePart;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;

import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Round-trip and lazy-upgrade coverage for {@link FacetIndexStoragePartSerializer} (referencing entity id arrays
 * delta-varint encoded) and the preserved {@link FacetIndexStoragePartSerializer_2026_1} (the 2026.1 released raw
 * fixed-int format).
 * Covers empty / single / large-gap referencing arrays, a present and an absent no-group block, and multiple groups.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("FacetIndexStoragePartSerializer round-trip (delta-varint referencing arrays)")
@Tag(STORAGE)
@Tag(FACET)
@Tag(SERIALIZATION)
class FacetIndexStoragePartSerializerTest {
	/** The pre-slimming serial-version-uid of {@link FacetIndexStoragePart} (kept registered). */
	private static final long LEGACY_2026_1_UID = -2348533783771242845L;

	private Kryo kryo;

	@BeforeEach
	void setUp() {
		final ReadWriteKeyCompressor keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(keyCompressor));
	}

	/**
	 * Builds a facet part with a present no-group block and two groups, exercising empty, single and large-gap
	 * referencing arrays.
	 *
	 * @return the facet storage part
	 */
	@Nonnull
	private static FacetIndexStoragePart richPart() {
		final Map<Integer, Bitmap> noGroup = Map.of(
			100, new BaseBitmap(1, 2, 3),
			101, new BaseBitmap() // empty array
		);
		final Map<Integer, Map<Integer, Bitmap>> groups = Map.of(
			7, Map.of(
				200, new BaseBitmap(5), // single element
				201, new BaseBitmap(10, 1_000, 1_000_000, Integer.MAX_VALUE) // large gaps
			),
			8, Map.of(
				300, new BaseBitmap(0, 1, 2)
			)
		);
		return new FacetIndexStoragePart(42, "brand", noGroup, groups);
	}

	/**
	 * Asserts two facet parts are equivalent on every field, comparing bitmaps by their backing arrays.
	 *
	 * @param expected the original part
	 * @param actual   the reconstructed part
	 */
	private static void assertPartEquals(
		@Nonnull FacetIndexStoragePart expected,
		@Nonnull FacetIndexStoragePart actual
	) {
		assertEquals(expected.getEntityIndexPrimaryKey(), actual.getEntityIndexPrimaryKey());
		assertEquals(expected.getReferenceName(), actual.getReferenceName());
		assertGroupEquals(expected.getNoGroupFacetingEntities(), actual.getNoGroupFacetingEntities());
		assertEquals(
			expected.getFacetingEntities().keySet(), actual.getFacetingEntities().keySet(), "group ids"
		);
		for (final Integer groupId : expected.getFacetingEntities().keySet()) {
			assertGroupEquals(
				expected.getFacetingEntities().get(groupId), actual.getFacetingEntities().get(groupId)
			);
		}
	}

	/**
	 * Asserts two facet group maps are equivalent, comparing bitmaps by their backing arrays.
	 *
	 * @param expected the original group map (nullable)
	 * @param actual   the reconstructed group map (nullable)
	 */
	private static void assertGroupEquals(Map<Integer, Bitmap> expected, Map<Integer, Bitmap> actual) {
		if (expected == null) {
			assertNull(actual, "a null no-group block must round-trip null");
			return;
		}
		assertNotNull(actual);
		assertEquals(expected.keySet(), actual.keySet(), "facet ids");
		for (final Entry<Integer, Bitmap> entry : expected.entrySet()) {
			final Integer facetId = entry.getKey();
			assertArrayEquals(
				entry.getValue().getArray(), actual.get(facetId).getArray(), "facet " + facetId + " ids"
			);
		}
	}

	@Nested
	@DisplayName("Production dispatch round-trip")
	class RoundTrip {

		@Test
		@DisplayName("round-trips a rich part (no-group + multiple groups, varied arrays)")
		void shouldRoundTripRichPart() {
			final FacetIndexStoragePart original = richPart();

			final FacetIndexStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
				FacetIndexStoragePartSerializerTest.this.kryo, original, FacetIndexStoragePart.class
			);

			assertPartEquals(original, deserialized);
		}

		@Test
		@DisplayName("round-trips a part with an absent no-group block")
		void shouldRoundTripWithoutNoGroupBlock() {
			final FacetIndexStoragePart original = new FacetIndexStoragePart(
				1, "category", null, Map.of(3, Map.of(9, new BaseBitmap(4, 5, 6)))
			);

			final FacetIndexStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
				FacetIndexStoragePartSerializerTest.this.kryo, original, FacetIndexStoragePart.class
			);

			assertPartEquals(original, deserialized);
		}
	}

	@Nested
	@DisplayName("Lazy upgrade from the pre-slimming format")
	class LazyUpgrade {

		@Test
		@DisplayName("reads a pre-slimming raw-int blob through the dispatcher")
		void shouldReadPreSlimmingFormat() {
			final FacetIndexStoragePart original = richPart();

			final byte[] legacyBytes = encodePreSlimmingBytes(original);

			final FacetIndexStoragePart deserialized = StoragePartSerializerTestSupport.decode(
				FacetIndexStoragePartSerializerTest.this.kryo, legacyBytes, FacetIndexStoragePart.class
			);

			assertPartEquals(original, deserialized);
		}

		/**
		 * Hand-encodes the 2026.1 released pre-slimming raw-int format for the given part (uid-prefixed), mirroring the
		 * dropped 2026.1 writer's wire exactly so the production dispatcher routes it to the registered 2026.1 reader.
		 * The preserved {@link FacetIndexStoragePartSerializer_2026_1} is the frozen prior-production reader; its write
		 * path deliberately throws, so the legacy blob is reproduced here by hand.
		 *
		 * @param part the storage part to encode in the pre-slimming format
		 * @return the legacy-format bytes (uid-prefixed)
		 */
		@Nonnull
		private byte[] encodePreSlimmingBytes(@Nonnull FacetIndexStoragePart part) {
			final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
			try (final Output output = new Output(os, 4_096)) {
				output.writeLong(LEGACY_2026_1_UID);
				output.writeVarInt(part.getEntityIndexPrimaryKey(), true);
				output.writeString(part.getReferenceName());

				final Map<Integer, Bitmap> noGroupFacetingEntities = part.getNoGroupFacetingEntities();
				output.writeBoolean(noGroupFacetingEntities != null);
				if (noGroupFacetingEntities != null) {
					writeGroup(output, noGroupFacetingEntities);
				}

				output.writeVarInt(part.getFacetingEntities().size(), true);
				for (final Map.Entry<Integer, Map<Integer, Bitmap>> groupEntry : part.getFacetingEntities().entrySet()) {
					output.writeInt(groupEntry.getKey());
					writeGroup(output, groupEntry.getValue());
				}
			}
			return os.toByteArray();
		}

		/**
		 * Writes a single facet group block in the pre-slimming raw-int wire format (count followed by, per facet, the
		 * facet id and its referencing entity ids as raw fixed 4-byte ints).
		 *
		 * @param output     the target output
		 * @param groupFacets the facet → referencing entities map for one group
		 */
		private static void writeGroup(@Nonnull Output output, @Nonnull Map<Integer, Bitmap> groupFacets) {
			output.writeVarInt(groupFacets.size(), true);
			for (final Map.Entry<Integer, Bitmap> facetEntry : groupFacets.entrySet()) {
				output.writeInt(facetEntry.getKey());
				final int[] referencingEntityIds = facetEntry.getValue().getArray();
				output.writeVarInt(referencingEntityIds.length, true);
				output.writeInts(referencingEntityIds, 0, referencingEntityIds.length);
			}
		}
	}
}
