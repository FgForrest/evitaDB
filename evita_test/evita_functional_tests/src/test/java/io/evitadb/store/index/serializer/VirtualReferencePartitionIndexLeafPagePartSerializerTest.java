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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexLeafPagePart;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.utils.NumberUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.util.Collections;

import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link VirtualReferencePartitionIndexLeafPagePartSerializer}: the delta-coded keys and the half-wise
 * zig-zag payloads round-trip for every sign and magnitude, consecutive partitions stay compact, the stream id is
 * resolved from the identity of the index, and the page round-trips through the registered index Kryo.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Virtual reference partition index leaf-page serializer")
@Tag(STORAGE)
@Tag(SERIALIZATION)
@Tag(REFERENCE)
class VirtualReferencePartitionIndexLeafPagePartSerializerTest {

	private Kryo kryo;
	private VirtualReferencePartitionIndexLeafPagePartSerializer serializer;
	private ReadWriteKeyCompressor keyCompressor;

	@BeforeEach
	void setUp() {
		this.keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.serializer = new VirtualReferencePartitionIndexLeafPagePartSerializer();
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(this.keyCompressor));
	}

	/**
	 * Builds a write-path leaf page and resolves its primary key through the test compressor.
	 *
	 * @param entityIndexPrimaryKey the owning entity index pk
	 * @param referenceName         the reference name of the index
	 * @param pageSequence          the page sequence within the stream
	 * @param keys                  the leaf's keys in ascending order
	 * @param payloads              the payload of each key
	 * @return the key-assigned write-path page
	 */
	@Nonnull
	private VirtualReferencePartitionIndexLeafPagePart page(
		int entityIndexPrimaryKey, @Nonnull String referenceName, int pageSequence,
		@Nonnull long[] keys, @Nonnull long[] payloads
	) {
		final VirtualReferencePartitionIndexLeafPagePart page = new VirtualReferencePartitionIndexLeafPagePart(
			entityIndexPrimaryKey, referenceName, pageSequence, keys, payloads
		);
		page.computeUniquePartIdAndSet(this.keyCompressor);
		return page;
	}

	/**
	 * Serializes the part with the serializer under test and returns the raw bytes.
	 *
	 * @param part the part to serialize
	 * @return the serialized bytes
	 */
	@Nonnull
	private byte[] serialize(@Nonnull VirtualReferencePartitionIndexLeafPagePart part) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			this.serializer.write(this.kryo, output, part);
		}
		return os.toByteArray();
	}

	/**
	 * Serializes the part with the serializer under test and reads it straight back.
	 *
	 * @param part the part to round-trip
	 * @return the deserialized copy
	 */
	@Nonnull
	private VirtualReferencePartitionIndexLeafPagePart roundTrip(
		@Nonnull VirtualReferencePartitionIndexLeafPagePart part
	) {
		try (final Input input = new Input(serialize(part))) {
			return this.serializer.read(this.kryo, input, VirtualReferencePartitionIndexLeafPagePart.class);
		}
	}

	/**
	 * Asserts two pages hold the same stream id, page sequence, primary key and columns.
	 *
	 * @param expected the original page
	 * @param actual   the deserialized page
	 */
	private static void assertSamePage(
		@Nonnull VirtualReferencePartitionIndexLeafPagePart expected,
		@Nonnull VirtualReferencePartitionIndexLeafPagePart actual
	) {
		assertEquals(expected.getStreamId(), actual.getStreamId());
		assertEquals(expected.getPageSequence(), actual.getPageSequence());
		assertEquals(expected.getStoragePartPK(), actual.getStoragePartPK());
		assertArrayEquals(expected.getKeys(), actual.getKeys());
		assertArrayEquals(expected.getPayloads(), actual.getPayloads());
	}

	@Nested
	@DisplayName("Content round-trip")
	class ContentRoundTrip {

		@Test
		@DisplayName("round-trips payloads of every sign and magnitude across slots")
		void shouldRoundTripExtremePayloads() {
			final int[] extremes = {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE};
			final long[] keys = new long[extremes.length * extremes.length + 2];
			final long[] payloads = new long[keys.length];
			int i = 0;
			for (final int owner : extremes) {
				for (final int target : extremes) {
					keys[i] = NumberUtils.pack(0, i + 1);
					payloads[i] = NumberUtils.pack(owner, target);
					i++;
				}
			}
			keys[i] = NumberUtils.pack(1, 1);
			payloads[i++] = 3L;
			keys[i] = NumberUtils.pack(1, Integer.MAX_VALUE);
			payloads[i] = Long.MIN_VALUE;
			final VirtualReferencePartitionIndexLeafPagePart page = page(7, "media", 3, keys, payloads);
			assertSamePage(page, roundTrip(page));
		}

		@Test
		@DisplayName("round-trips an empty leaf page")
		void shouldRoundTripEmptyLeafPage() {
			final VirtualReferencePartitionIndexLeafPagePart page = page(1, "media", 0, new long[0], new long[0]);
			assertSamePage(page, roundTrip(page));
		}

		@Test
		@DisplayName("consecutive partitions with small payloads cost a few bytes per entry")
		void shouldKeepConsecutiveEntriesCompact() {
			final int count = 256;
			final long[] keys = new long[count];
			final long[] payloads = new long[count];
			for (int i = 0; i < count; i++) {
				keys[i] = NumberUtils.pack(1, 1_000_000 + i);
				payloads[i] = i % 3 + 1;
			}
			final VirtualReferencePartitionIndexLeafPagePart page = page(1, "media", 0, keys, payloads);
			// a key delta of one, a zero high half and a one-byte low half per entry, plus a small frame
			assertTrue(
				serialize(page).length < count * 4,
				"Consecutive keys and tuple ids must not be written at full width: " + serialize(page).length
			);
			assertSamePage(page, roundTrip(page));
		}
	}

	@Nested
	@DisplayName("Stream-id resolution and primary key")
	class StreamIdAndPrimaryKey {

		@Test
		@DisplayName("resolves the stream id from the identity of the index and joins it with the page sequence")
		void shouldResolveStreamIdAndDeriveJoinedPrimaryKey() {
			final VirtualReferencePartitionIndexLeafPagePart page = page(
				42, "media", 5, new long[]{NumberUtils.pack(0, 1)}, new long[]{1L}
			);
			final long expected = VirtualReferencePartitionIndexLeafPagePart.computeUniquePartId(page.getStreamId(), 5);
			assertEquals(Long.valueOf(expected), page.getStoragePartPK());
			assertEquals(page.getStoragePartPK(), roundTrip(page).getStoragePartPK());
		}

		@Test
		@DisplayName("indexes of different entity indexes or references have distinct streams")
		void shouldDistinguishStreamsByIndexIdentity() {
			final int streamA = streamIdOf(1, "media");
			final int streamB = streamIdOf(2, "media");
			final int streamC = streamIdOf(1, "categories");
			assertNotEquals(streamA, streamB);
			assertNotEquals(streamA, streamC);
		}

		/**
		 * Resolves the stream id of a fresh write-path page for the given index identity.
		 *
		 * @param entityIndexPrimaryKey the owning entity index pk
		 * @param referenceName         the reference name of the index
		 * @return the resolved stream id
		 */
		private int streamIdOf(int entityIndexPrimaryKey, @Nonnull String referenceName) {
			return page(entityIndexPrimaryKey, referenceName, 0, new long[0], new long[0]).getStreamId();
		}
	}

	@Nested
	@DisplayName("Kryo registration")
	class Registration {

		@Test
		@DisplayName("round-trips through the registered index Kryo")
		void shouldRoundTripViaRegisteredKryo() {
			final VirtualReferencePartitionIndexLeafPagePart page = page(
				9, "media", 2,
				new long[]{NumberUtils.pack(0, 1), NumberUtils.pack(1, 1)},
				new long[]{NumberUtils.pack(-5, 5), 1L}
			);
			final Kryo theKryo = VirtualReferencePartitionIndexLeafPagePartSerializerTest.this.kryo;
			final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
			try (final Output output = new Output(os, 4_096)) {
				theKryo.writeObject(output, page);
			}
			final VirtualReferencePartitionIndexLeafPagePart deserialized;
			try (final Input input = new Input(os.toByteArray())) {
				deserialized = theKryo.readObject(input, VirtualReferencePartitionIndexLeafPagePart.class);
			}
			assertSamePage(page, deserialized);
		}
	}
}
