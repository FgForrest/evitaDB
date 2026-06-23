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
import io.evitadb.index.hierarchy.HierarchyNode;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HierarchyIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HierarchyIndexStoragePart.LevelIndex;
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
import java.util.LinkedHashMap;
import java.util.Map;

import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Round-trip and lazy-upgrade coverage for {@link HierarchyIndexStoragePartSerializer} (children / roots / orphans
 * arrays delta-varint encoded) and the preserved {@link HierarchyIndexStoragePartSerializer_2026_1} (the 2026.1
 * released raw fixed-int format). Covers the routinely-empty roots / orphans arrays, multiple levels with single and
 * large-gap children, and a fully empty hierarchy.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("HierarchyIndexStoragePartSerializer round-trip (delta-varint id arrays)")
@Tag(STORAGE)
@Tag(HIERARCHY)
@Tag(SERIALIZATION)
class HierarchyIndexStoragePartSerializerTest {
	/** The pre-slimming serial-version-uid of {@link HierarchyIndexStoragePart} (kept registered). */
	private static final long LEGACY_2026_1_UID = -3223754922135567923L;

	private Kryo kryo;

	@BeforeEach
	void setUp() {
		final ReadWriteKeyCompressor keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(keyCompressor));
	}

	/**
	 * Builds a hierarchy part with several nodes, multiple levels (single + large-gap children), some roots and one
	 * orphan.
	 *
	 * @return the hierarchy storage part
	 */
	@Nonnull
	private static HierarchyIndexStoragePart richPart() {
		final Map<Integer, HierarchyNode> itemIndex = new LinkedHashMap<>();
		itemIndex.put(1, new HierarchyNode(1, null));
		itemIndex.put(2, new HierarchyNode(2, 1));
		itemIndex.put(3, new HierarchyNode(3, 1));
		itemIndex.put(99, new HierarchyNode(99, 5)); // orphan: parent 5 absent
		final LevelIndex[] levelIndex = {
			new LevelIndex(1, new int[]{2, 3}),
			new LevelIndex(2, new int[]{1_000_000}), // single, large value
			new LevelIndex(3, new int[0]) // empty children
		};
		final int[] roots = {1};
		final int[] orphans = {99};
		return new HierarchyIndexStoragePart(42, itemIndex, roots, levelIndex, orphans);
	}

	/**
	 * Asserts two hierarchy parts are equivalent on every field.
	 *
	 * @param expected the original part
	 * @param actual   the reconstructed part
	 */
	private static void assertPartEquals(
		@Nonnull HierarchyIndexStoragePart expected,
		@Nonnull HierarchyIndexStoragePart actual
	) {
		assertEquals(expected.getEntityIndexPrimaryKey(), actual.getEntityIndexPrimaryKey());
		assertEquals(expected.getItemIndex(), actual.getItemIndex(), "item index");
		assertArrayEquals(expected.getRoots(), actual.getRoots(), "roots");
		assertArrayEquals(expected.getOrphans(), actual.getOrphans(), "orphans");
		final LevelIndex[] expectedLevels = expected.getLevelIndex();
		final LevelIndex[] actualLevels = actual.getLevelIndex();
		assertEquals(expectedLevels.length, actualLevels.length, "level count");
		for (int i = 0; i < expectedLevels.length; i++) {
			assertEquals(expectedLevels[i].parentId(), actualLevels[i].parentId(), "level " + i + " parent");
			assertArrayEquals(
				expectedLevels[i].childrenIds(), actualLevels[i].childrenIds(), "level " + i + " children"
			);
		}
	}

	@Nested
	@DisplayName("Production dispatch round-trip")
	class RoundTrip {

		@Test
		@DisplayName("round-trips a rich hierarchy (levels, roots, orphan)")
		void shouldRoundTripRichPart() {
			final HierarchyIndexStoragePart original = richPart();

			final HierarchyIndexStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
				HierarchyIndexStoragePartSerializerTest.this.kryo, original, HierarchyIndexStoragePart.class
			);

			assertPartEquals(original, deserialized);
		}

		@Test
		@DisplayName("round-trips a fully empty hierarchy (empty roots / orphans / levels)")
		void shouldRoundTripEmptyHierarchy() {
			final HierarchyIndexStoragePart original = new HierarchyIndexStoragePart(
				1, new LinkedHashMap<>(), new int[0], new LevelIndex[0], new int[0]
			);

			final HierarchyIndexStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
				HierarchyIndexStoragePartSerializerTest.this.kryo, original, HierarchyIndexStoragePart.class
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
			final HierarchyIndexStoragePart original = richPart();

			final byte[] legacyBytes = encodePreSlimmingBytes(original);

			final HierarchyIndexStoragePart deserialized = StoragePartSerializerTestSupport.decode(
				HierarchyIndexStoragePartSerializerTest.this.kryo, legacyBytes, HierarchyIndexStoragePart.class
			);

			assertPartEquals(original, deserialized);
		}

		/**
		 * Hand-encodes the 2026.1 released pre-slimming raw-int format for the given part (uid-prefixed), mirroring the
		 * dropped 2026.1 writer's wire exactly so the production dispatcher routes it to the registered 2026.1 reader.
		 * The preserved {@link HierarchyIndexStoragePartSerializer_2026_1} is the frozen prior-production reader; its
		 * write path deliberately throws, so the legacy blob is reproduced here by hand.
		 *
		 * @param part the storage part to encode in the pre-slimming format
		 * @return the legacy-format bytes (uid-prefixed)
		 */
		@Nonnull
		private static byte[] encodePreSlimmingBytes(@Nonnull HierarchyIndexStoragePart part) {
			final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
			try (final Output output = new Output(os, 4_096)) {
				output.writeLong(LEGACY_2026_1_UID);
				output.writeInt(part.getEntityIndexPrimaryKey());

				final Map<Integer, HierarchyNode> itemIndex = part.getItemIndex();
				output.writeVarInt(itemIndex.size(), true);
				for (final HierarchyNode node : itemIndex.values()) {
					output.writeInt(node.entityPrimaryKey());
					final boolean parentReferencePresent = node.parentEntityPrimaryKey() != null;
					output.writeBoolean(parentReferencePresent);
					if (parentReferencePresent) {
						output.writeInt(node.parentEntityPrimaryKey());
					}
				}

				final LevelIndex[] levelIndex = part.getLevelIndex();
				output.writeVarInt(levelIndex.length, true);
				for (final LevelIndex entry : levelIndex) {
					output.writeInt(entry.parentId());
					output.writeVarInt(entry.childrenIds().length, true);
					output.writeInts(entry.childrenIds(), 0, entry.childrenIds().length);
				}

				final int[] roots = part.getRoots();
				output.writeVarInt(roots.length, true);
				output.writeInts(roots, 0, roots.length);

				final int[] orphans = part.getOrphans();
				output.writeVarInt(orphans.length, true);
				output.writeInts(orphans, 0, orphans.length);
			}
			return os.toByteArray();
		}
	}
}
