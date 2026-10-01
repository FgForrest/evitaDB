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
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceNameKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
import io.evitadb.utils.NumberUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Locale;

import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip coverage for {@link VirtualReferencePartitionIndexStoragePartSerializer}: the inline SINGLE shape with
 * entry payloads of any sign and magnitude, the PAGED shape with its page-stream metadata, and the tuple dictionary of
 * mixed value types and `null` values that both shapes carry.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("VirtualReferencePartitionIndexStoragePartSerializer round-trip")
@Tag(STORAGE)
@Tag(SERIALIZATION)
@Tag(REFERENCE)
class VirtualReferencePartitionIndexStoragePartSerializerTest {
	private static final int ENTITY_INDEX_PK = 42;
	private static final String REFERENCE_NAME = "media";
	private static final int[] TUPLE_IDS = {1, 2, 9};
	private static final Serializable[][] TUPLES = {
		{"gallery"},
		{"video", 7, new BigDecimal("1.50"), null},
		{Locale.ENGLISH, OffsetDateTime.of(2026, 10, 1, 12, 0, 0, 0, ZoneOffset.UTC), Boolean.TRUE}
	};

	private Kryo kryo;
	private ReadWriteKeyCompressor keyCompressor;
	private VirtualReferencePartitionIndexStoragePartSerializer serializer;

	@BeforeEach
	void setUp() {
		this.keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.serializer = new VirtualReferencePartitionIndexStoragePartSerializer(this.keyCompressor);
		this.kryo = KryoFactory.createKryo(
			SharedClassesConfigurer.INSTANCE.andThen(new IndexStoragePartConfigurer(this.keyCompressor))
		);
	}

	/**
	 * Serializes the part with the serializer under test and reads it straight back.
	 *
	 * @param part the part to round-trip
	 * @return the deserialized copy
	 */
	@Nonnull
	private VirtualReferencePartitionIndexStoragePart roundTrip(
		@Nonnull VirtualReferencePartitionIndexStoragePart part
	) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			this.serializer.write(this.kryo, output, part);
		}
		try (final Input input = new Input(os.toByteArray())) {
			return this.serializer.read(this.kryo, input, VirtualReferencePartitionIndexStoragePart.class);
		}
	}

	/**
	 * Asserts the deserialized dictionary equals the original one.
	 *
	 * @param expected the original part
	 * @param actual   the deserialized part
	 */
	private static void assertSameDictionary(
		@Nonnull VirtualReferencePartitionIndexStoragePart expected,
		@Nonnull VirtualReferencePartitionIndexStoragePart actual
	) {
		assertArrayEquals(expected.getTupleIds(), actual.getTupleIds(), "Tuple ids must survive the round-trip.");
		assertEquals(expected.getTuples().length, actual.getTuples().length);
		for (int i = 0; i < expected.getTuples().length; i++) {
			assertArrayEquals(
				expected.getTuples()[i], actual.getTuples()[i], "Tuple " + i + " must survive the round-trip."
			);
		}
	}

	@Nested
	@DisplayName("SINGLE shape")
	class SingleShape {

		@Test
		@DisplayName("round-trips entries whose payloads cover every sign and magnitude")
		void shouldRoundTripInlineEntriesWithExtremePayloads() {
			final int[] extremes = {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE};
			final long[] keys = new long[extremes.length * extremes.length + 1];
			final long[] payloads = new long[keys.length];
			int i = 0;
			for (final int owner : extremes) {
				for (final int target : extremes) {
					keys[i] = NumberUtils.pack(0, i + 1);
					payloads[i] = NumberUtils.pack(owner, target);
					i++;
				}
			}
			// the largest key the index can hold - the last slot entry of the largest partition primary key
			keys[i] = NumberUtils.pack(1, Integer.MAX_VALUE);
			payloads[i] = 9L;

			final VirtualReferencePartitionIndexStoragePart part = VirtualReferencePartitionIndexStoragePart.single(
				ENTITY_INDEX_PK, REFERENCE_NAME, keys, payloads, TUPLE_IDS, TUPLES, null
			);
			final VirtualReferencePartitionIndexStoragePart deserialized = roundTrip(part);

			assertFalse(deserialized.isPaged());
			assertEquals(ENTITY_INDEX_PK, deserialized.getEntityIndexPrimaryKey());
			assertEquals(REFERENCE_NAME, deserialized.getReferenceName());
			assertEquals(part.getStoragePartPK(), deserialized.getStoragePartPK());
			assertArrayEquals(keys, deserialized.getKeys());
			assertArrayEquals(payloads, deserialized.getPayloads());
			assertNull(deserialized.getLeafPageSequences());
			assertSameDictionary(part, deserialized);
		}

		@Test
		@DisplayName("round-trips an empty dictionary")
		void shouldRoundTripEmptyDictionary() {
			final VirtualReferencePartitionIndexStoragePart part = VirtualReferencePartitionIndexStoragePart.single(
				ENTITY_INDEX_PK, REFERENCE_NAME, new long[]{NumberUtils.pack(0, 1)}, new long[]{NumberUtils.pack(5, 6)},
				new int[0], new Serializable[0][], null
			);
			final VirtualReferencePartitionIndexStoragePart deserialized = roundTrip(part);
			assertEquals(0, deserialized.getTupleIds().length);
			assertEquals(0, deserialized.getTuples().length);
		}
	}

	@Nested
	@DisplayName("PAGED shape")
	class PagedShape {

		@Test
		@DisplayName("round-trips the page-stream metadata and the dictionary")
		void shouldRoundTripPagedRoot() {
			final int[] leafPageSequences = {0, 3, 4, 17};
			final VirtualReferencePartitionIndexStoragePart part = VirtualReferencePartitionIndexStoragePart.paged(
				ENTITY_INDEX_PK, REFERENCE_NAME, 17, leafPageSequences, TUPLE_IDS, TUPLES, null
			);
			final VirtualReferencePartitionIndexStoragePart deserialized = roundTrip(part);

			assertTrue(deserialized.isPaged());
			assertEquals(17, deserialized.getHighWaterPageSequence());
			assertArrayEquals(leafPageSequences, deserialized.getLeafPageSequences());
			assertNull(deserialized.getKeys());
			assertNull(deserialized.getPayloads());
			assertSameDictionary(part, deserialized);
		}
	}

	@Nested
	@DisplayName("Primary key")
	class PrimaryKey {

		@Test
		@DisplayName("packs the entity index primary key with the compressed reference name")
		void shouldPackEntityIndexAndReferenceName() {
			final VirtualReferencePartitionIndexStoragePart part = VirtualReferencePartitionIndexStoragePart.single(
				ENTITY_INDEX_PK, REFERENCE_NAME, new long[0], new long[0], new int[0], new Serializable[0][], null
			);
			final long primaryKey = part.computeUniquePartIdAndSet(
				VirtualReferencePartitionIndexStoragePartSerializerTest.this.keyCompressor
			);
			final int referenceNameId = VirtualReferencePartitionIndexStoragePartSerializerTest.this.keyCompressor
				.getId(new ReferenceNameKey(REFERENCE_NAME));
			assertEquals(NumberUtils.pack(ENTITY_INDEX_PK, referenceNameId), primaryKey);
			assertEquals(primaryKey, roundTrip(part).getStoragePartPK());
		}

		@Test
		@DisplayName("round-trips through the registered index Kryo")
		void shouldRoundTripViaRegisteredKryo() {
			final VirtualReferencePartitionIndexStoragePart part = VirtualReferencePartitionIndexStoragePart.paged(
				ENTITY_INDEX_PK, REFERENCE_NAME, 2, new int[]{1, 2}, TUPLE_IDS, TUPLES, null
			);
			final Kryo theKryo = VirtualReferencePartitionIndexStoragePartSerializerTest.this.kryo;
			final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
			try (final Output output = new Output(os, 4_096)) {
				theKryo.writeObject(output, part);
			}
			final VirtualReferencePartitionIndexStoragePart deserialized;
			try (final Input input = new Input(os.toByteArray())) {
				deserialized = theKryo.readObject(input, VirtualReferencePartitionIndexStoragePart.class);
			}
			assertArrayEquals(part.getLeafPageSequences(), deserialized.getLeafPageSequences());
			assertSameDictionary(part, deserialized);
		}
	}
}
