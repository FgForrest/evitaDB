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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.LeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.LeafStreamKey.StreamKind;
import io.evitadb.store.catalog.CatalogHeaderKryoConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.util.Locale;

import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link LeafStreamKeySerializer} and {@link LeafStreamKey}'s identity/ordering contract — the per-sub-index
 * page-stream key of the granular FilterIndex layout. The key must survive the catalog-header Kryo
 * round-trip (it rides in the persisted {@code KeyCompressor} dictionary) and must have a restart-stable, deterministic
 * {@code equals}/{@code compareTo} so the compressor assigns one stable, collision-free id per distinct sub-index.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Leaf stream key serializer")
@Tag(STORAGE)
@Tag(SERIALIZATION)
class LeafStreamKeySerializerTest {

	private Kryo kryo;
	private LeafStreamKeySerializer serializer;

	@BeforeEach
	void setUp() {
		this.serializer = new LeafStreamKeySerializer();
		this.kryo = KryoFactory.createKryo(CatalogHeaderKryoConfigurer.INSTANCE);
	}

	@Nonnull
	private LeafStreamKey roundTrip(@Nonnull LeafStreamKey key) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(256);
		try (final Output output = new Output(os, 256)) {
			this.serializer.write(this.kryo, output, key);
		}
		try (final Input input = new Input(os.toByteArray())) {
			return this.serializer.read(this.kryo, input, LeafStreamKey.class);
		}
	}

	@Nested
	@DisplayName("Round-trip")
	class RoundTrip {

		@Test
		@DisplayName("round-trips a localized, reference-scoped key")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRoundTripLocalizedReferenceScopedKey() {
			final LeafStreamKey key = new LeafStreamKey(
				42, new AttributeKeyWithIndexType("brand", "name", Locale.ENGLISH, AttributeIndexType.FILTER)
			);
			assertEquals(key, roundTrip(key), "Key must survive the serializer round-trip.");
		}

		@Test
		@DisplayName("round-trips an entity-scoped key with no reference and no locale")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRoundTripEntityScopedKey() {
			final LeafStreamKey key = new LeafStreamKey(
				7, new AttributeKeyWithIndexType(null, "code", null, AttributeIndexType.FILTER)
			);
			assertEquals(key, roundTrip(key), "Key must survive the serializer round-trip.");
		}

		@Test
		@DisplayName("round-trips through the registered header Kryo")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRoundTripViaRegisteredKryo() {
			final LeafStreamKey key = new LeafStreamKey(
				9, new AttributeKeyWithIndexType(null, "ean", Locale.GERMAN, AttributeIndexType.FILTER)
			);
			final ByteArrayOutputStream os = new ByteArrayOutputStream(256);
			try (final Output output = new Output(os, 256)) {
				LeafStreamKeySerializerTest.this.kryo.writeObject(output, key);
			}
			final LeafStreamKey deserialized;
			try (final Input input = new Input(os.toByteArray())) {
				deserialized = LeafStreamKeySerializerTest.this.kryo.readObject(input, LeafStreamKey.class);
			}
			assertEquals(key, deserialized, "Key must survive the registered-Kryo round-trip.");
		}
	}

	@Nested
	@DisplayName("Identity and ordering")
	class IdentityAndOrdering {

		@Test
		@DisplayName("keys with the same identity are equal (so the compressor assigns one stable id)")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldTreatSameIdentityAsEqual() {
			final LeafStreamKey a = new LeafStreamKey(1, new AttributeKeyWithIndexType(null, "name", null, AttributeIndexType.FILTER));
			final LeafStreamKey b = new LeafStreamKey(1, new AttributeKeyWithIndexType(null, "name", null, AttributeIndexType.FILTER));
			assertEquals(a, b, "Same identity must be equal.");
			assertEquals(a.hashCode(), b.hashCode(), "Equal keys must share a hash code.");
			assertEquals(0, a.compareTo(b), "Equal keys must compare equal.");
		}

		@Test
		@DisplayName("the same attribute in different entity indexes is a different stream")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldDistinguishByEntityIndexPrimaryKey() {
			final AttributeKeyWithIndexType attr = new AttributeKeyWithIndexType(null, "name", null, AttributeIndexType.FILTER);
			final LeafStreamKey inIndex1 = new LeafStreamKey(1, attr);
			final LeafStreamKey inIndex2 = new LeafStreamKey(2, attr);
			assertNotEquals(inIndex1, inIndex2, "Same attribute in different entity indexes must be distinct streams.");
			assertTrue(inIndex1.compareTo(inIndex2) < 0, "Ordering must sort by entity index pk first.");
		}

		@Test
		@DisplayName("different attributes in the same entity index are different streams")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldDistinguishByAttribute() {
			final LeafStreamKey name = new LeafStreamKey(1, new AttributeKeyWithIndexType(null, "name", null, AttributeIndexType.FILTER));
			final LeafStreamKey code = new LeafStreamKey(1, new AttributeKeyWithIndexType(null, "code", null, AttributeIndexType.FILTER));
			assertNotEquals(name, code, "Different attributes must be distinct streams.");
			assertTrue(name.compareTo(code) != 0, "Different attributes must not compare equal.");
		}

		@Test
		@DisplayName("the BUCKET and RANGE streams of one sub-index are distinct (and stable across the round-trip)")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldDistinguishByStreamKind() {
			final AttributeKeyWithIndexType attr = new AttributeKeyWithIndexType(null, "validity", null, AttributeIndexType.FILTER);
			final LeafStreamKey bucket = new LeafStreamKey(1, attr, StreamKind.BUCKET);
			final LeafStreamKey range = new LeafStreamKey(1, attr, StreamKind.RANGE);
			assertNotEquals(bucket, range, "BUCKET and RANGE streams of one sub-index must be distinct.");
			assertTrue(bucket.compareTo(range) != 0, "BUCKET and RANGE must not compare equal.");
			// the 2-arg constructor defaults to BUCKET, so it must equal the explicit BUCKET key
			assertEquals(new LeafStreamKey(1, attr), bucket, "The 2-arg constructor must default to BUCKET.");
			// the stream kind must survive the serializer round-trip
			assertEquals(StreamKind.RANGE, roundTrip(range).getStreamKind(), "Stream kind must survive the round-trip.");
			assertEquals(range, roundTrip(range), "A RANGE key must survive the round-trip.");
		}
	}
}
