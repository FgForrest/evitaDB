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
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.range.TransactionalRangePoint;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.RangeIndexLeafPagePart;
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

import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Verifies {@link RangeIndexLeafPagePartSerializer} — the Kryo (de)serialization of a granular FilterIndex range leaf
 * page. A write-path page carries its sub-index identity and resolves the `streamId` store-side with
 * {@link io.evitadb.spi.store.catalog.persistence.storageParts.index.LeafStreamKey.StreamKind#RANGE} through the
 * {@link ReadWriteKeyCompressor} when its primary key is assigned; the serializer then writes the resolved
 * `(streamId, pageSequence)` pair and the leaf's range points, recomputing the join-derived primary key on read. Crucially it
 * also asserts the RANGE stream is DISTINCT from the same FilterIndex's BUCKET stream (the stream-kind discriminator), so
 * their leaf-page primary keys never collide.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Range index leaf-page serializer")
@Tag(STORAGE)
@Tag(SERIALIZATION)
class RangeIndexLeafPagePartSerializerTest {

	private Kryo kryo;
	private RangeIndexLeafPagePartSerializer serializer;
	private ReadWriteKeyCompressor keyCompressor;

	@BeforeEach
	void setUp() {
		this.keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.serializer = new RangeIndexLeafPagePartSerializer();
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(this.keyCompressor));
	}

	@Nonnull
	private RangeIndexLeafPagePart page(
		int entityIndexPrimaryKey, @Nonnull String attr, int pageSequence, @Nonnull TransactionalRangePoint... points
	) {
		final RangeIndexLeafPagePart page = new RangeIndexLeafPagePart(
			entityIndexPrimaryKey,
			new AttributeKeyWithIndexType(null, attr, null, AttributeIndexType.FILTER),
			pageSequence,
			points
		);
		page.computeUniquePartIdAndSet(this.keyCompressor);
		return page;
	}

	@Nonnull
	private RangeIndexLeafPagePart roundTrip(@Nonnull RangeIndexLeafPagePart part) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			this.serializer.write(this.kryo, output, part);
		}
		try (final Input input = new Input(os.toByteArray())) {
			return this.serializer.read(this.kryo, input, RangeIndexLeafPagePart.class);
		}
	}

	private static void assertSamePage(
		@Nonnull RangeIndexLeafPagePart expected, @Nonnull RangeIndexLeafPagePart actual
	) {
		assertEquals(expected.getStreamId(), actual.getStreamId(), "Stream id must survive the round-trip.");
		assertEquals(expected.getPageSequence(), actual.getPageSequence(), "Page sequence must survive the round-trip.");
		assertEquals(expected.getStoragePartPK(), actual.getStoragePartPK(), "Primary key must survive the round-trip.");
		assertEquals(expected.getPoints().length, actual.getPoints().length, "Point count must match.");
		for (int i = 0; i < expected.getPoints().length; i++) {
			final TransactionalRangePoint expectedPoint = expected.getPoints()[i];
			final TransactionalRangePoint actualPoint = actual.getPoints()[i];
			assertEquals(expectedPoint.getThreshold(), actualPoint.getThreshold(), "threshold @ " + i);
			assertArrayEquals(
				expectedPoint.getStarts().getArray(), actualPoint.getStarts().getArray(), "starts @ " + i
			);
			assertArrayEquals(
				expectedPoint.getEnds().getArray(), actualPoint.getEnds().getArray(), "ends @ " + i
			);
		}
	}

	@Nested
	@DisplayName("Content round-trip")
	class ContentRoundTrip {

		@Test
		@DisplayName("round-trips points with starts and ends")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRoundTripPoints() {
			final RangeIndexLeafPagePart page = page(
				7, "validity", 3,
				new TransactionalRangePoint(10L, new BaseBitmap(1, 2), new BaseBitmap()),
				new TransactionalRangePoint(20L, new BaseBitmap(3), new BaseBitmap(1)),
				new TransactionalRangePoint(30L, new BaseBitmap(), new BaseBitmap(2, 3))
			);
			assertSamePage(page, roundTrip(page));
		}

		@Test
		@DisplayName("round-trips an empty leaf page")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRoundTripEmptyLeafPage() {
			final RangeIndexLeafPagePart page = page(1, "validity", 0);
			assertSamePage(page, roundTrip(page));
		}
	}

	@Nested
	@DisplayName("Stream-id resolution and primary key")
	class StreamIdAndPrimaryKey {

		@Test
		@DisplayName("resolves the range stream id and joins it with the page sequence")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldResolveStreamIdAndDeriveJoinedPrimaryKey() {
			final RangeIndexLeafPagePart page = page(
				42, "validity", 5, new TransactionalRangePoint(1L, new BaseBitmap(1), new BaseBitmap())
			);
			final int resolvedStreamId = page.getStreamId();
			final long expected = AbstractLeafPagePart.computeUniquePartId(resolvedStreamId, 5);
			assertEquals(Long.valueOf(expected), page.getStoragePartPK(), "Computed key must join (streamId, pageSequence).");
			assertEquals(expected, page.computeUniquePartIdAndSet(this.keyCompressor()), "Re-resolution must be idempotent.");
			assertEquals(resolvedStreamId, page.getStreamId(), "Re-resolution must yield the same stream id.");
		}

		private ReadWriteKeyCompressor keyCompressor() {
			return RangeIndexLeafPagePartSerializerTest.this.keyCompressor;
		}

		@Test
		@DisplayName("the RANGE stream is distinct from the same FilterIndex's BUCKET stream")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldDistinguishRangeStreamFromBucketStream() {
			// same entity index, same attribute, same page sequence — only the stream kind differs
			final RangeIndexLeafPagePart rangePage = page(
				1, "validity", 0, new TransactionalRangePoint(1L, new BaseBitmap(1), new BaseBitmap())
			);
			final FilterIndexLeafPagePart bucketPage = new FilterIndexLeafPagePart(
				1, new AttributeKeyWithIndexType(null, "validity", null, AttributeIndexType.FILTER), 0,
				new io.evitadb.index.invertedIndex.ValueToRecordBitmap[]{
					new io.evitadb.index.invertedIndex.ValueToRecordBitmap(1, 1)
				},
				null
			);
			bucketPage.computeUniquePartIdAndSet(this.keyCompressor());
			assertNotEquals(
				rangePage.getStreamId(), bucketPage.getStreamId(),
				"The range and bucket streams of one FilterIndex must have distinct stream ids."
			);
			assertNotEquals(
				rangePage.getStoragePartPK(), bucketPage.getStoragePartPK(),
				"Range and bucket leaf pages with the same page sequence must have distinct primary keys."
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
			final RangeIndexLeafPagePart page = page(
				9, "validity", 2,
				new TransactionalRangePoint(11L, new BaseBitmap(1), new BaseBitmap()),
				new TransactionalRangePoint(22L, new BaseBitmap(), new BaseBitmap(1))
			);
			final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
			try (final Output output = new Output(os, 4_096)) {
				RangeIndexLeafPagePartSerializerTest.this.kryo.writeObject(output, page);
			}
			final RangeIndexLeafPagePart deserialized;
			try (final Input input = new Input(os.toByteArray())) {
				deserialized = RangeIndexLeafPagePartSerializerTest.this.kryo.readObject(input, RangeIndexLeafPagePart.class);
			}
			assertSamePage(page, deserialized);
		}
	}
}
