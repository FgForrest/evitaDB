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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexLeafPagePart;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.util.Collections;

import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Verifies {@link UniqueIndexLeafPagePartSerializer} — the Kryo (de)serialization of a granular standalone (OWNER)
 * unique-index leaf page. A write-path page carries its sub-index identity and resolves the `streamId` store-side
 * through the {@link ReadWriteKeyCompressor} when its primary key is assigned; the serializer then writes the resolved
 * `(streamId, pageSequence)` pair and the leaf's `(value, recordId)` columns, recomputing the join-derived primary key
 * on read. Exercises String and numeric values, an empty leaf, identity-driven stream-id resolution, and the
 * registered-type round-trip through {@link IndexStoragePartConfigurer}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Unique index leaf-page serializer")
@Tag(STORAGE)
@Tag(SERIALIZATION)
class UniqueIndexLeafPagePartSerializerTest {

	private Kryo kryo;
	private UniqueIndexLeafPagePartSerializer serializer;
	private ReadWriteKeyCompressor keyCompressor;

	@BeforeEach
	void setUp() {
		this.keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.serializer = new UniqueIndexLeafPagePartSerializer();
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(this.keyCompressor));
	}

	/**
	 * Builds a write-path leaf page for the entity-scoped attribute `attr` and resolves its primary key (hence its
	 * stream id) through the test compressor — exactly the store-side sequence the persistence service performs before
	 * writing.
	 *
	 * @param entityIndexPrimaryKey the owning entity index pk
	 * @param attr                  the (entity-scoped, non-localized) attribute name
	 * @param pageSequence          the page sequence
	 * @param values                the leaf values
	 * @param recordIds             the single record id per value, aligned with `values`
	 * @return the key-assigned write-path page
	 */
	@Nonnull
	private UniqueIndexLeafPagePart page(
		int entityIndexPrimaryKey, @Nonnull String attr, int pageSequence,
		@Nonnull Serializable[] values, @Nonnull int[] recordIds
	) {
		final UniqueIndexLeafPagePart page = new UniqueIndexLeafPagePart(
			entityIndexPrimaryKey,
			new AttributeKeyWithIndexType(null, attr, null, AttributeIndexType.UNIQUE),
			pageSequence,
			values,
			recordIds
		);
		page.computeUniquePartIdAndSet(this.keyCompressor);
		return page;
	}

	@Nonnull
	private byte[] serialize(@Nonnull UniqueIndexLeafPagePart part) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			this.serializer.write(this.kryo, output, part);
		}
		return os.toByteArray();
	}

	@Nonnull
	private UniqueIndexLeafPagePart roundTrip(@Nonnull UniqueIndexLeafPagePart part) {
		try (final Input input = new Input(serialize(part))) {
			return this.serializer.read(this.kryo, input, UniqueIndexLeafPagePart.class);
		}
	}

	/**
	 * Asserts the two leaf pages hold the same stream id, page sequence, primary key, and `(value, recordId)` columns.
	 *
	 * @param expected the original page
	 * @param actual   the deserialized page
	 */
	private static void assertSamePage(
		@Nonnull UniqueIndexLeafPagePart expected, @Nonnull UniqueIndexLeafPagePart actual
	) {
		assertEquals(expected.getStreamId(), actual.getStreamId(), "Stream id must survive the round-trip.");
		assertEquals(expected.getPageSequence(), actual.getPageSequence(), "Page sequence must survive the round-trip.");
		assertEquals(expected.getStoragePartPK(), actual.getStoragePartPK(), "Primary key must survive the round-trip.");
		assertArrayEquals(expected.getValues(), actual.getValues(), "Values must survive the round-trip.");
		assertArrayEquals(expected.getRecordIds(), actual.getRecordIds(), "Record ids must survive the round-trip.");
	}

	@Nested
	@DisplayName("Content round-trip")
	class ContentRoundTrip {

		@Test
		@DisplayName("round-trips String (URL-slug) values")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRoundTripStringValues() {
			final UniqueIndexLeafPagePart page = page(
				7, "url", 3,
				new Serializable[]{"/a/b/product-1", "/a/b/product-2", "/a/b/product-3"},
				new int[]{100, 200, 300}
			);
			assertSamePage(page, roundTrip(page));
		}

		@Test
		@DisplayName("round-trips numeric values including negative record ids")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRoundTripNumericValues() {
			final UniqueIndexLeafPagePart page = page(
				7, "code", 1,
				new Serializable[]{10, 20, 30},
				new int[]{1, -2, 3}
			);
			assertSamePage(page, roundTrip(page));
		}

		@Test
		@DisplayName("round-trips an empty leaf page")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRoundTripEmptyLeafPage() {
			final UniqueIndexLeafPagePart page = page(1, "name", 0, new Serializable[0], new int[0]);
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
			final UniqueIndexLeafPagePart page = page(
				42, "url", 5, new Serializable[]{"/x"}, new int[]{1}
			);
			final int resolvedStreamId = page.getStreamId();
			final long expected = AbstractLeafPagePart.computeUniquePartId(resolvedStreamId, 5);
			assertEquals(Long.valueOf(expected), page.getStoragePartPK(), "Computed key must join (streamId, pageSequence).");
			assertEquals(
				expected,
				page.computeUniquePartIdAndSet(UniqueIndexLeafPagePartSerializerTest.this.keyCompressor),
				"Re-resolution must be idempotent."
			);
			assertEquals(resolvedStreamId, page.getStreamId(), "Re-resolution must yield the same stream id.");
		}

		@Test
		@DisplayName("the UNIQUE stream is distinct from the FILTER stream of the same attribute")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldDistinguishUniqueStreamFromFilterStream() {
			final UniqueIndexLeafPagePart uniquePage = page(1, "url", 0, new Serializable[]{"/x"}, new int[]{1});
			// the FILTER stream id for the same (entityIndexPk, attribute) must differ — the AttributeIndexType
			// discriminator inside the LeafStreamKey keeps the two page streams from colliding
			final int filterStreamId = this.streamIdOf(1, "url", AttributeIndexType.FILTER);
			assertNotEquals(
				uniquePage.getStreamId(), filterStreamId,
				"The UNIQUE and FILTER streams of the same attribute must be distinct."
			);
		}

		private int streamIdOf(int entityIndexPrimaryKey, @Nonnull String attr, @Nonnull AttributeIndexType indexType) {
			final UniqueIndexLeafPagePart probe = new UniqueIndexLeafPagePart(
				entityIndexPrimaryKey,
				new AttributeKeyWithIndexType(null, attr, null, indexType),
				0, new Serializable[]{"/x"}, new int[]{1}
			);
			probe.computeUniquePartIdAndSet(UniqueIndexLeafPagePartSerializerTest.this.keyCompressor);
			return probe.getStreamId();
		}

		@Test
		@DisplayName("recomputes the primary key on read rather than storing it in the payload")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRecomputePrimaryKeyOnRead() {
			final UniqueIndexLeafPagePart page = page(42, "url", 5, new Serializable[]{"/x"}, new int[]{1});
			final UniqueIndexLeafPagePart deserialized = roundTrip(page);
			assertEquals(
				page.getStoragePartPK(), deserialized.getStoragePartPK(),
				"Read must derive the key from the (streamId, pageSequence) pair."
			);
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
			final UniqueIndexLeafPagePart page = page(
				9, "url", 2,
				new Serializable[]{"/p/1", "/p/2"},
				new int[]{1, 2}
			);

			final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
			try (final Output output = new Output(os, 4_096)) {
				UniqueIndexLeafPagePartSerializerTest.this.kryo.writeObject(output, page);
			}
			final UniqueIndexLeafPagePart deserialized;
			try (final Input input = new Input(os.toByteArray())) {
				deserialized = UniqueIndexLeafPagePartSerializerTest.this.kryo.readObject(input, UniqueIndexLeafPagePart.class);
			}
			assertSamePage(page, deserialized);
		}
	}
}
