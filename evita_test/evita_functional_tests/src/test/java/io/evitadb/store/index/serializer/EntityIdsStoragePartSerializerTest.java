/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIdsStoragePart;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Round-trip coverage for {@link EntityIdsStoragePartSerializer} — the sibling record carrying the
 * entity-id superset bitmap and per-locale bitmaps evicted out of the entity-index manifest. Covers an
 * empty locale map, a multi-locale map, and a large (gappy) superset bitmap.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("EntityIdsStoragePartSerializer round-trip")
@Tag(STORAGE)
@Tag(SERIALIZATION)
class EntityIdsStoragePartSerializerTest {

	private Kryo kryo;

	@BeforeEach
	void setUp() {
		final ReadWriteKeyCompressor keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(keyCompressor));
	}

	/**
	 * Asserts two bitmap parts are equivalent on every field.
	 *
	 * @param expected the original part
	 * @param actual   the reconstructed part
	 */
	private static void assertPartEquals(
		@Nonnull EntityIdsStoragePart expected,
		@Nonnull EntityIdsStoragePart actual
	) {
		assertEquals(expected.getPrimaryKey(), actual.getPrimaryKey(), "primary key");
		assertEquals(expected.getVersion(), actual.getVersion(), "version");
		assertArrayEquals(expected.getEntityIds().getArray(), actual.getEntityIds().getArray(), "entityIds");
		assertEquals(
			expected.getEntityIdsByLanguage().keySet(),
			actual.getEntityIdsByLanguage().keySet(),
			"locale set"
		);
		for (final Map.Entry<Locale, TransactionalBitmap> entry : expected.getEntityIdsByLanguage().entrySet()) {
			assertArrayEquals(
				entry.getValue().getArray(),
				actual.getEntityIdsByLanguage().get(entry.getKey()).getArray(),
				"locale " + entry.getKey() + " bitmap"
			);
		}
	}

	@Test
	@DisplayName("round-trips a part with an empty locale map")
	void shouldRoundTripEmptyLocaleMap() {
		final EntityIdsStoragePart original = new EntityIdsStoragePart(
			42, 7, new TransactionalBitmap(1, 2, 3), Collections.emptyMap()
		);

		final EntityIdsStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
			this.kryo, original, EntityIdsStoragePart.class
		);

		assertPartEquals(original, deserialized);
	}

	@Test
	@DisplayName("round-trips a part with multiple locales")
	void shouldRoundTripMultiLocaleMap() {
		final Map<Locale, TransactionalBitmap> byLanguage = new LinkedHashMap<>();
		byLanguage.put(Locale.ENGLISH, new TransactionalBitmap(1, 5, 9));
		byLanguage.put(Locale.GERMAN, new TransactionalBitmap(5, 9));
		byLanguage.put(Locale.forLanguageTag("cs"), new TransactionalBitmap(1));
		final EntityIdsStoragePart original = new EntityIdsStoragePart(
			100, 3, new TransactionalBitmap(1, 5, 9), byLanguage
		);

		final EntityIdsStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
			this.kryo, original, EntityIdsStoragePart.class
		);

		assertPartEquals(original, deserialized);
	}

	@Test
	@DisplayName("round-trips a large, gappy superset bitmap")
	void shouldRoundTripLargeBitmap() {
		final int[] ids = new int[10_000];
		for (int i = 0; i < ids.length; i++) {
			ids[i] = i * 7 + 1;
		}
		final EntityIdsStoragePart original = new EntityIdsStoragePart(
			Integer.MAX_VALUE, 999, new TransactionalBitmap(ids), Collections.emptyMap()
		);

		final EntityIdsStoragePart deserialized = StoragePartSerializerTestSupport.roundTrip(
			this.kryo, original, EntityIdsStoragePart.class
		);

		assertPartEquals(original, deserialized);
	}
}
