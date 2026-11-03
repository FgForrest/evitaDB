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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionLeafStreamKey;
import io.evitadb.store.catalog.CatalogHeaderKryoConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * Verifies {@link VirtualReferencePartitionLeafStreamKeySerializer} and the identity contract of
 * {@link VirtualReferencePartitionLeafStreamKey}: the key survives the catalog-header Kryo it is persisted with, and
 * its equality and order are decided by both halves of the identity, so the key compressor assigns one stable id per
 * index.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Virtual reference partition leaf stream key serializer")
@Tag(STORAGE)
@Tag(SERIALIZATION)
class VirtualReferencePartitionLeafStreamKeySerializerTest {

	private Kryo kryo;

	@BeforeEach
	void setUp() {
		this.kryo = KryoFactory.createKryo(
			SharedClassesConfigurer.INSTANCE.andThen(CatalogHeaderKryoConfigurer.INSTANCE)
		);
	}

	/**
	 * Round-trips a key through the registered catalog-header Kryo.
	 *
	 * @param key the key to round-trip
	 * @return the deserialized key
	 */
	@Nonnull
	private VirtualReferencePartitionLeafStreamKey roundTrip(@Nonnull VirtualReferencePartitionLeafStreamKey key) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(256);
		try (final Output output = new Output(os, 256)) {
			this.kryo.writeClassAndObject(output, key);
		}
		try (final Input input = new Input(os.toByteArray())) {
			return (VirtualReferencePartitionLeafStreamKey) this.kryo.readClassAndObject(input);
		}
	}

	@Test
	@DisplayName("round-trips through the registered catalog-header Kryo, negative primary keys included")
	void shouldRoundTripViaRegisteredKryo() {
		final VirtualReferencePartitionLeafStreamKey key = new VirtualReferencePartitionLeafStreamKey(42, "media");
		assertEquals(key, roundTrip(key));
		final VirtualReferencePartitionLeafStreamKey negative = new VirtualReferencePartitionLeafStreamKey(-3, "x");
		assertEquals(negative, roundTrip(negative));
	}

	@Test
	@DisplayName("equality, hash and order are decided by both halves of the identity")
	void shouldDistinguishKeysByBothHalves() {
		final VirtualReferencePartitionLeafStreamKey key = new VirtualReferencePartitionLeafStreamKey(1, "media");
		assertEquals(key, new VirtualReferencePartitionLeafStreamKey(1, "media"));
		assertEquals(key.hashCode(), new VirtualReferencePartitionLeafStreamKey(1, "media").hashCode());
		assertNotEquals(key, new VirtualReferencePartitionLeafStreamKey(2, "media"));
		assertNotEquals(key, new VirtualReferencePartitionLeafStreamKey(1, "categories"));
		assertEquals(0, key.compareTo(new VirtualReferencePartitionLeafStreamKey(1, "media")));
		assertTrue(key.compareTo(new VirtualReferencePartitionLeafStreamKey(2, "a")) < 0);
		assertTrue(key.compareTo(new VirtualReferencePartitionLeafStreamKey(1, "a")) > 0);
	}
}
