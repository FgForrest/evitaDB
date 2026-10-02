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
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStorageKey;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

import static io.evitadb.store.index.serializer.StoragePartSerializerTestSupport.decode;
import static io.evitadb.store.index.serializer.StoragePartSerializerTestSupport.encodeCurrent;
import static io.evitadb.store.index.serializer.StoragePartSerializerTestSupport.roundTrip;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fulltext section of the {@link EntityIndexStoragePart} manifest: the current format writes the set of
 * {@link FulltextIndexKey}s after the histogram section, and a manifest of the released 2026.2 format - which ends with
 * the histogram section and carries the previous `serialVersionUID` - still reads, through
 * {@link EntityIndexStoragePartSerializer_2026_2}, with no fulltext index.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(SERIALIZATION)
@DisplayName("Entity index manifest - fulltext section")
class EntityIndexStoragePartSerializerTest {

	/**
	 * The `serialVersionUID` of {@link EntityIndexStoragePart} as released in 2026.2.
	 */
	private static final long RELEASED_2026_2_UID = -3842757193845629481L;
	private static final EntityIndexKey GLOBAL_KEY = new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE);

	private Kryo kryo;

	@BeforeEach
	void setUp() {
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(new ReadWriteKeyCompressor(Collections.emptyMap())));
	}

	/**
	 * Builds a manifest with a facet, a localized histogram and the passed fulltext indexes.
	 *
	 * @param fulltextIndexes the fulltext indexes the manifest lists
	 * @return the manifest
	 */
	@Nonnull
	private static EntityIndexStoragePart manifest(@Nonnull Set<FulltextIndexKey> fulltextIndexes) {
		return new EntityIndexStoragePart(
			7, 3, GLOBAL_KEY,
			Set.of(), Set.of(), true, Set.of("brand"),
			Set.of(new HistogramIndexStorageKey(GLOBAL_KEY, "price", Locale.ENGLISH)),
			fulltextIndexes
		);
	}

	/**
	 * Asserts the decoded manifest carries what the original does.
	 *
	 * @param expected the original
	 * @param actual   the decoded one
	 */
	private static void assertSameManifest(@Nonnull EntityIndexStoragePart expected, @Nonnull EntityIndexStoragePart actual) {
		assertEquals(expected.getPrimaryKey(), actual.getPrimaryKey());
		assertEquals(expected.getVersion(), actual.getVersion());
		assertEquals(expected.getEntityIndexKey(), actual.getEntityIndexKey());
		assertEquals(expected.isHierarchyIndex(), actual.isHierarchyIndex());
		assertEquals(expected.getFacetIndexes(), actual.getFacetIndexes());
		assertEquals(expected.getHistogramIndexes(), actual.getHistogramIndexes());
		assertEquals(expected.getFulltextIndexes(), actual.getFulltextIndexes());
	}

	@Test
	@DisplayName("the fulltext indexes round-trip after every other section")
	void shouldRoundTripTheFulltextIndexes() {
		final EntityIndexStoragePart manifest = manifest(
			Set.of(new FulltextIndexKey(Locale.forLanguageTag("cs")), new FulltextIndexKey(Locale.ENGLISH))
		);
		assertSameManifest(manifest, roundTrip(this.kryo, manifest, EntityIndexStoragePart.class));
		final EntityIndexStoragePart empty = manifest(Set.of());
		assertSameManifest(empty, roundTrip(this.kryo, empty, EntityIndexStoragePart.class));
	}

	@Test
	@DisplayName("a manifest of the released 2026.2 format reads with no fulltext index")
	void shouldReadTheReleased2026_2Format() {
		// the 2026.2 layout is the current one without its trailing fulltext section: an empty section is the single
		// varint 0, so dropping it and restamping the released uid renders a 2026.2 record byte for byte
		final EntityIndexStoragePart manifest = manifest(Set.of());
		final byte[] current = encodeCurrent(this.kryo, manifest);
		assertEquals(0, current[current.length - 1], "An empty fulltext section is the single varint 0.");
		final byte[] released = Arrays.copyOf(current, current.length - 1);
		final byte[] releasedStamp = stamp(RELEASED_2026_2_UID);
		assertFalse(
			Arrays.equals(releasedStamp, Arrays.copyOf(current, Long.BYTES)),
			"The current format must carry a new uid."
		);
		System.arraycopy(releasedStamp, 0, released, 0, Long.BYTES);

		final EntityIndexStoragePart decoded = decode(this.kryo, released, EntityIndexStoragePart.class);
		assertSameManifest(manifest, decoded);
		assertTrue(decoded.getFulltextIndexes().isEmpty());
	}

	/**
	 * Renders the version stamp `SerialVersionBasedSerializer` writes in front of a record.
	 *
	 * @param uid the uid
	 * @return its bytes as Kryo writes them
	 */
	@Nonnull
	private static byte[] stamp(long uid) {
		try (final Output output = new Output(Long.BYTES)) {
			output.writeLong(uid);
			final byte[] bytes = output.toBytes();
			assertEquals(Long.BYTES, bytes.length);
			return bytes;
		}
	}

}
