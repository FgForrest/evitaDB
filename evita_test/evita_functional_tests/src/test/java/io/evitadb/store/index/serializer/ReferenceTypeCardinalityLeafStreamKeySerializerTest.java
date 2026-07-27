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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceTypeCardinalityLeafStreamKey;
import io.evitadb.store.catalog.CatalogHeaderKryoConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;

import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link ReferenceTypeCardinalityLeafStreamKeySerializer} and {@link ReferenceTypeCardinalityLeafStreamKey}'s
 * identity/ordering contract — the per-reference-type-cardinality-index page-stream key of the granular storage layout.
 * The key must survive the catalog-header Kryo round-trip (it rides in the persisted {@code KeyCompressor} dictionary) and
 * must have a restart-stable, deterministic `equals`/`hashCode`/`compareTo` so the compressor assigns one stable,
 * collision-free id per distinct cardinality sub-index — distinguished by the `(entityIndexPrimaryKey, referenceName)`
 * pair, because the same reference name exists independently in every entity index.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reference-type cardinality leaf stream key serializer")
@Tag(STORAGE)
@Tag(SERIALIZATION)
class ReferenceTypeCardinalityLeafStreamKeySerializerTest {

	private Kryo kryo;
	private ReferenceTypeCardinalityLeafStreamKeySerializer serializer;

	@BeforeEach
	void setUp() {
		this.serializer = new ReferenceTypeCardinalityLeafStreamKeySerializer();
		this.kryo = KryoFactory.createKryo(
			SharedClassesConfigurer.INSTANCE.andThen(CatalogHeaderKryoConfigurer.INSTANCE)
		);
	}

	/**
	 * Round-trips a key directly through the serializer under test (no serial-version prefix).
	 *
	 * @param key the key to round-trip
	 * @return the deserialized key
	 */
	@Nonnull
	private ReferenceTypeCardinalityLeafStreamKey roundTrip(@Nonnull ReferenceTypeCardinalityLeafStreamKey key) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(256);
		try (final Output output = new Output(os, 256)) {
			this.serializer.write(this.kryo, output, key);
		}
		try (final Input input = new Input(os.toByteArray())) {
			return this.serializer.read(this.kryo, input, ReferenceTypeCardinalityLeafStreamKey.class);
		}
	}

	@Nested
	@DisplayName("Round-trip")
	class RoundTrip {

		@Test
		@DisplayName("round-trips a key through the serializer under test")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRoundTripViaSerializer() {
			final ReferenceTypeCardinalityLeafStreamKey key =
				new ReferenceTypeCardinalityLeafStreamKey(42, "facet");
			assertEquals(key, roundTrip(key), "Key must survive the serializer round-trip.");
		}

		@Test
		@DisplayName("round-trips through the registered header Kryo")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRoundTripViaRegisteredKryo() {
			final ReferenceTypeCardinalityLeafStreamKey key =
				new ReferenceTypeCardinalityLeafStreamKey(9, "brand");
			final ByteArrayOutputStream os = new ByteArrayOutputStream(256);
			try (final Output output = new Output(os, 256)) {
				ReferenceTypeCardinalityLeafStreamKeySerializerTest.this.kryo.writeObject(output, key);
			}
			final ReferenceTypeCardinalityLeafStreamKey deserialized;
			try (final Input input = new Input(os.toByteArray())) {
				deserialized = ReferenceTypeCardinalityLeafStreamKeySerializerTest.this.kryo.readObject(
					input, ReferenceTypeCardinalityLeafStreamKey.class
				);
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
			final ReferenceTypeCardinalityLeafStreamKey a =
				new ReferenceTypeCardinalityLeafStreamKey(1, "facet");
			final ReferenceTypeCardinalityLeafStreamKey b =
				new ReferenceTypeCardinalityLeafStreamKey(1, "facet");
			assertEquals(a, b, "Same identity must be equal.");
			assertEquals(a.hashCode(), b.hashCode(), "Equal keys must share a hash code.");
			assertEquals(0, a.compareTo(b), "Equal keys must compare equal.");
		}

		@Test
		@DisplayName("the same reference name in different entity indexes is a different stream")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldDistinguishByEntityIndexPrimaryKey() {
			final ReferenceTypeCardinalityLeafStreamKey inIndex1 =
				new ReferenceTypeCardinalityLeafStreamKey(1, "facet");
			final ReferenceTypeCardinalityLeafStreamKey inIndex2 =
				new ReferenceTypeCardinalityLeafStreamKey(2, "facet");
			assertNotEquals(inIndex1, inIndex2, "Same reference in different entity indexes must be distinct streams.");
			assertTrue(inIndex1.compareTo(inIndex2) < 0, "Ordering must sort by entity index pk first.");
		}

		@Test
		@DisplayName("different reference names in the same entity index are different streams")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldDistinguishByReferenceName() {
			final ReferenceTypeCardinalityLeafStreamKey facet =
				new ReferenceTypeCardinalityLeafStreamKey(1, "facet");
			final ReferenceTypeCardinalityLeafStreamKey brand =
				new ReferenceTypeCardinalityLeafStreamKey(1, "brand");
			assertNotEquals(facet, brand, "Different reference names must be distinct streams.");
			assertTrue(facet.compareTo(brand) != 0, "Different reference names must not compare equal.");
		}
	}
}
