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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceTypeCardinalityIndexLeafPagePart;
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

import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Verifies {@link ReferenceTypeCardinalityIndexLeafPagePartSerializer} — the Kryo (de)serialization of one granular leaf
 * page of a reference-type cardinality bucket tree. A write-path page carries its sub-index
 * `(entityIndexPrimaryKey, referenceName)` identity and resolves the `streamId` store-side through the
 * {@link ReadWriteKeyCompressor} when its primary key is assigned; the serializer then writes the resolved
 * `(streamId, pageSequence)` pair and the leaf's signed-`long` `(key, count)` columns, recomputing the join-derived
 * primary key on read. Exercises mixed positive/negative composed keys, an empty leaf, identity-driven stream-id
 * resolution, and the registered-type round-trip through {@link IndexStoragePartConfigurer}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reference-type cardinality index leaf-page serializer")
@Tag(STORAGE)
@Tag(SERIALIZATION)
class ReferenceTypeCardinalityIndexLeafPagePartSerializerTest {

	private Kryo kryo;
	private ReferenceTypeCardinalityIndexLeafPagePartSerializer serializer;
	private ReadWriteKeyCompressor keyCompressor;

	@BeforeEach
	void setUp() {
		this.keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.serializer = new ReferenceTypeCardinalityIndexLeafPagePartSerializer();
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(this.keyCompressor));
	}

	/**
	 * Builds a write-path leaf page for the sub-index `(entityIndexPrimaryKey, referenceName)` and resolves its primary
	 * key (hence its stream id) through the test compressor — exactly the store-side sequence the persistence service
	 * performs before writing.
	 *
	 * @param entityIndexPrimaryKey the owning entity index pk
	 * @param referenceName         the reference name of the sub-index
	 * @param pageSequence          the page sequence within the stream
	 * @param keys                  the leaf's composed signed `long` keys in ascending key order
	 * @param payloads              the cardinality count owning each key, aligned with `keys`
	 * @return the key-assigned write-path page
	 */
	@Nonnull
	private ReferenceTypeCardinalityIndexLeafPagePart page(
		int entityIndexPrimaryKey, @Nonnull String referenceName, int pageSequence,
		@Nonnull long[] keys, @Nonnull long[] payloads
	) {
		final ReferenceTypeCardinalityIndexLeafPagePart page = new ReferenceTypeCardinalityIndexLeafPagePart(
			entityIndexPrimaryKey, referenceName, pageSequence, keys, payloads
		);
		page.computeUniquePartIdAndSet(this.keyCompressor);
		return page;
	}

	@Nonnull
	private byte[] serialize(@Nonnull ReferenceTypeCardinalityIndexLeafPagePart part) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			this.serializer.write(this.kryo, output, part);
		}
		return os.toByteArray();
	}

	@Nonnull
	private ReferenceTypeCardinalityIndexLeafPagePart roundTrip(@Nonnull ReferenceTypeCardinalityIndexLeafPagePart part) {
		try (final Input input = new Input(serialize(part))) {
			return this.serializer.read(this.kryo, input, ReferenceTypeCardinalityIndexLeafPagePart.class);
		}
	}

	/**
	 * Asserts the two leaf pages hold the same stream id, page sequence, primary key, and `(key, count)` columns.
	 *
	 * @param expected the original page
	 * @param actual   the deserialized page
	 */
	private static void assertSamePage(
		@Nonnull ReferenceTypeCardinalityIndexLeafPagePart expected,
		@Nonnull ReferenceTypeCardinalityIndexLeafPagePart actual
	) {
		assertEquals(expected.getStreamId(), actual.getStreamId(), "Stream id must survive the round-trip.");
		assertEquals(expected.getPageSequence(), actual.getPageSequence(), "Page sequence must survive the round-trip.");
		assertEquals(expected.getStoragePartPK(), actual.getStoragePartPK(), "Primary key must survive the round-trip.");
		assertArrayEquals(expected.getKeys(), actual.getKeys(), "Keys must survive the round-trip.");
		assertArrayEquals(expected.getPayloads(), actual.getPayloads(), "Counts must survive the round-trip.");
	}

	@Nested
	@DisplayName("Content round-trip")
	class ContentRoundTrip {

		@Test
		@DisplayName("round-trips mixed positive and negative composed keys with their counts")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRoundTripPositiveAndNegativeComposedKeys() {
			// per-reference counters are -pack(indexPk, refPk) (negative); the whole-index-PK counter is +pack(indexPk, 0)
			// (positive) — both must survive, interleaved in ascending signed-long order
			final long[] keys = {
				-NumberUtils.pack(1, 5), -NumberUtils.pack(1, 3), NumberUtils.pack(2, 0)
			};
			final long[] payloads = {3L, 5L, 2L};
			final ReferenceTypeCardinalityIndexLeafPagePart page = page(7, "facet", 3, keys, payloads);
			assertSamePage(page, roundTrip(page));
		}

		@Test
		@DisplayName("round-trips an empty leaf page")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRoundTripEmptyLeafPage() {
			final ReferenceTypeCardinalityIndexLeafPagePart page = page(1, "facet", 0, new long[0], new long[0]);
			assertSamePage(page, roundTrip(page));
		}
	}

	@Nested
	@DisplayName("Stream-id resolution and primary key")
	class StreamIdAndPrimaryKey {

		@Test
		@DisplayName("resolves the stream id from the sub-index identity and joins it with the page sequence")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldResolveStreamIdAndDeriveJoinedPrimaryKey() {
			final ReferenceTypeCardinalityIndexLeafPagePart page = page(
				42, "facet", 5, new long[]{NumberUtils.pack(1, 0)}, new long[]{1L}
			);
			final int resolvedStreamId = page.getStreamId();
			final long expected = ReferenceTypeCardinalityIndexLeafPagePart.computeUniquePartId(resolvedStreamId, 5);
			assertEquals(Long.valueOf(expected), page.getStoragePartPK(), "Computed key must join (streamId, pageSequence).");
			assertEquals(
				expected,
				page.computeUniquePartIdAndSet(ReferenceTypeCardinalityIndexLeafPagePartSerializerTest.this.keyCompressor),
				"Re-resolution must be idempotent."
			);
			assertEquals(resolvedStreamId, page.getStreamId(), "Re-resolution must yield the same stream id.");
		}

		@Test
		@DisplayName("recomputes the primary key on read rather than storing it in the payload")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRecomputePrimaryKeyOnRead() {
			final ReferenceTypeCardinalityIndexLeafPagePart page = page(
				42, "facet", 5, new long[]{NumberUtils.pack(1, 0)}, new long[]{1L}
			);
			final ReferenceTypeCardinalityIndexLeafPagePart deserialized = roundTrip(page);
			assertEquals(
				page.getStoragePartPK(), deserialized.getStoragePartPK(),
				"Read must derive the key from the (streamId, pageSequence) pair."
			);
		}

		@Test
		@DisplayName("the same reference name in different entity indexes (and vice-versa) yields distinct stream ids")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldDistinguishStreamsBySubIndexIdentity() {
			// the stream id folds in BOTH halves of the sub-index identity, so neither half alone collides
			final int streamA = streamIdOf(1, "facet");
			final int streamB = streamIdOf(2, "facet");
			final int streamC = streamIdOf(1, "brand");
			assertNotEquals(streamA, streamB, "Same reference name in different entity indexes must be distinct streams.");
			assertNotEquals(streamA, streamC, "Different reference names in the same entity index must be distinct streams.");
		}

		/**
		 * Resolves the stream id of a fresh write-path page for the given sub-index identity through the test compressor.
		 *
		 * @param entityIndexPrimaryKey the owning entity index pk
		 * @param referenceName         the reference name of the sub-index
		 * @return the resolved stream id
		 */
		private int streamIdOf(int entityIndexPrimaryKey, @Nonnull String referenceName) {
			final ReferenceTypeCardinalityIndexLeafPagePart probe = new ReferenceTypeCardinalityIndexLeafPagePart(
				entityIndexPrimaryKey, referenceName, 0, new long[]{NumberUtils.pack(1, 0)}, new long[]{1L}
			);
			probe.computeUniquePartIdAndSet(ReferenceTypeCardinalityIndexLeafPagePartSerializerTest.this.keyCompressor);
			return probe.getStreamId();
		}
	}

	@Nested
	@DisplayName("Kryo registration")
	class Registration {

		@Test
		@DisplayName("round-trips through the registered index Kryo")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRoundTripViaRegisteredKryo() {
			final ReferenceTypeCardinalityIndexLeafPagePart page = page(
				9, "facet", 2,
				new long[]{-NumberUtils.pack(1, 1), NumberUtils.pack(1, 0)},
				new long[]{1L, 2L}
			);

			final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
			try (final Output output = new Output(os, 4_096)) {
				ReferenceTypeCardinalityIndexLeafPagePartSerializerTest.this.kryo.writeObject(output, page);
			}
			final ReferenceTypeCardinalityIndexLeafPagePart deserialized;
			try (final Input input = new Input(os.toByteArray())) {
				deserialized = ReferenceTypeCardinalityIndexLeafPagePartSerializerTest.this.kryo.readObject(
					input, ReferenceTypeCardinalityIndexLeafPagePart.class
				);
			}
			assertSamePage(page, deserialized);
		}
	}
}
