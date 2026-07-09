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
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.invertedIndex.ValueToRecordPrimitive;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexLeafPagePart;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.Locale;

import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link HistogramIndexLeafPagePartSerializer} — the Kryo (de)serialization of a granular histogram bucket
 * leaf page. A write-path page carries its sub-index identity and resolves the `streamId` store-side through the
 * {@link ReadWriteKeyCompressor} when its primary key is assigned; the serializer then writes the resolved
 * `(streamId, pageSequence)` pair and the leaf's buckets, recomputing the join-derived primary key on read. Shares its
 * bucket payload framing with {@link FilterIndexLeafPagePartSerializer} via {@link BucketLeafPagePartSerializer}, so
 * this mirrors that test's coverage (single-record, multi-record, mixed and empty leaves) scoped to the histogram
 * identity.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Histogram index leaf-page serializer")
@Tag(STORAGE)
@Tag(SERIALIZATION)
class HistogramIndexLeafPagePartSerializerTest {

	private Kryo kryo;
	private HistogramIndexLeafPagePartSerializer serializer;
	private ReadWriteKeyCompressor keyCompressor;

	@BeforeEach
	void setUp() {
		this.keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.serializer = new HistogramIndexLeafPagePartSerializer();
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(this.keyCompressor));
	}

	/**
	 * Builds a write-path leaf page for the given histogram sub-index identity and resolves its primary key (hence its
	 * stream id) through the test compressor — exactly the store-side sequence the persistence service performs before
	 * writing.
	 */
	@Nonnull
	private HistogramIndexLeafPagePart page(
		int entityIndexPrimaryKey, @Nonnull String histogramName, @Nullable Locale locale, int pageSequence,
		@Nonnull ValueToRecord... buckets
	) {
		final HistogramIndexLeafPagePart page = new HistogramIndexLeafPagePart(
			entityIndexPrimaryKey, histogramName, locale, pageSequence, buckets
		);
		page.computeUniquePartIdAndSet(this.keyCompressor);
		return page;
	}

	@Nonnull
	private byte[] serialize(@Nonnull HistogramIndexLeafPagePart part) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			this.serializer.write(this.kryo, output, part);
		}
		return os.toByteArray();
	}

	@Nonnull
	private HistogramIndexLeafPagePart roundTrip(@Nonnull HistogramIndexLeafPagePart part) {
		try (final Input input = new Input(serialize(part))) {
			return this.serializer.read(this.kryo, input, HistogramIndexLeafPagePart.class);
		}
	}

	/**
	 * Asserts the two leaf pages hold the same stream id, page sequence, primary key, and bucket contents (including
	 * each bucket's representation - primitive vs. bitmap).
	 */
	private static void assertSamePage(
		@Nonnull HistogramIndexLeafPagePart expected, @Nonnull HistogramIndexLeafPagePart actual
	) {
		assertEquals(expected.getStreamId(), actual.getStreamId(), "Stream id must survive the round-trip.");
		assertEquals(expected.getPageSequence(), actual.getPageSequence(), "Page sequence must survive the round-trip.");
		assertEquals(expected.getStoragePartPK(), actual.getStoragePartPK(), "Primary key must survive the round-trip.");
		assertEquals(expected.getBuckets().length, actual.getBuckets().length, "Bucket count must match.");
		for (int i = 0; i < expected.getBuckets().length; i++) {
			final ValueToRecord expectedBucket = expected.getBuckets()[i];
			final ValueToRecord actualBucket = actual.getBuckets()[i];
			assertSame(expectedBucket.getClass(), actualBucket.getClass(), "Bucket representation must match at " + i);
			assertEquals(expectedBucket.getValue(), actualBucket.getValue(), "Bucket value must match at " + i);
			assertArrayEquals(
				expectedBucket.getRecordIds().getArray(), actualBucket.getRecordIds().getArray(),
				"Bucket record set must match at " + i
			);
		}
	}

	@Nested
	@DisplayName("Content round-trip")
	class ContentRoundTrip {

		@Test
		@DisplayName("round-trips single- and multi-record bitmap buckets")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRoundTripSingleAndMultiRecordBuckets() {
			final HistogramIndexLeafPagePart page = page(
				7, "price", null, 3,
				new ValueToRecordBitmap(10, 100),
				new ValueToRecordBitmap(20, 200, 201, 202),
				new ValueToRecordBitmap(30, 300, 305)
			);
			assertSamePage(page, roundTrip(page));
		}

		@Test
		@DisplayName("round-trips an empty leaf page")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRoundTripEmptyLeafPage() {
			final HistogramIndexLeafPagePart page = page(1, "price", null, 0);
			assertSamePage(page, roundTrip(page));
		}

		@Test
		@DisplayName("round-trips compact single-record ValueToRecordPrimitive buckets, preserving their representation")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRoundTripPrimitiveBuckets() {
			final HistogramIndexLeafPagePart page = page(
				7, "price", null, 3,
				new ValueToRecordPrimitive(10, 100),
				new ValueToRecordPrimitive(20, 200)
			);
			final HistogramIndexLeafPagePart deserialized = roundTrip(page);
			assertSamePage(page, deserialized);
			for (final ValueToRecord bucket : deserialized.getBuckets()) {
				assertInstanceOf(ValueToRecordPrimitive.class, bucket, "A single-record bucket must deserialize as the compact primitive representation.");
			}
		}

		@Test
		@DisplayName("round-trips a leaf page mixing primitive single-record and bitmap multi-record buckets")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRoundTripMixedPrimitiveAndBitmapBuckets() {
			final HistogramIndexLeafPagePart page = page(
				7, "price", null, 3,
				new ValueToRecordPrimitive(10, 100),
				new ValueToRecordBitmap(20, 200, 201, 202),
				new ValueToRecordPrimitive(30, 300)
			);
			final HistogramIndexLeafPagePart deserialized = roundTrip(page);
			assertSamePage(page, deserialized);
			assertInstanceOf(ValueToRecordPrimitive.class, deserialized.getBuckets()[0]);
			assertInstanceOf(ValueToRecordBitmap.class, deserialized.getBuckets()[1]);
			assertInstanceOf(ValueToRecordPrimitive.class, deserialized.getBuckets()[2]);
		}

		@Test
		@DisplayName("the primitive encoding of a single-record bucket is smaller on the wire than the bitmap encoding")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldEncodePrimitiveBucketMoreCompactlyThanBitmapBucket() {
			final HistogramIndexLeafPagePart primitivePage = page(7, "price", null, 3, new ValueToRecordPrimitive(10, 100));
			final HistogramIndexLeafPagePart bitmapPage = page(7, "price", null, 3, new ValueToRecordBitmap(10, 100));
			assertTrue(
				serialize(primitivePage).length < serialize(bitmapPage).length,
				"A single-record ValueToRecordPrimitive bucket must serialize smaller than the equivalent ValueToRecordBitmap bucket."
			);
		}

		@Test
		@DisplayName("round-trips a localized histogram bucket leaf page")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRoundTripLocalizedHistogram() {
			final HistogramIndexLeafPagePart page = page(
				7, "weight", Locale.GERMAN, 3, new ValueToRecordPrimitive(10, 100)
			);
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
			final HistogramIndexLeafPagePart page = page(42, "price", null, 5, new ValueToRecordPrimitive(1, 1));
			final int resolvedStreamId = page.getStreamId();
			final long expected = HistogramIndexLeafPagePart.computeUniquePartId(resolvedStreamId, 5);
			assertEquals(Long.valueOf(expected), page.getStoragePartPK(), "Computed key must join (streamId, pageSequence).");
		}

		@Test
		@DisplayName("the same histogram in different locales resolves to different streams")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldDistinguishStreamsByLocale() {
			final HistogramIndexLeafPagePart inEnglish = page(1, "weight", Locale.ENGLISH, 0, new ValueToRecordPrimitive(1, 1));
			final HistogramIndexLeafPagePart inGerman = page(1, "weight", Locale.GERMAN, 0, new ValueToRecordPrimitive(1, 1));
			assertNotEquals(
				inEnglish.getStreamId(), inGerman.getStreamId(),
				"The same histogram in different locales must be distinct streams."
			);
		}

		@Test
		@DisplayName("recomputes the primary key on read rather than storing it in the payload")
		@Tag(STORAGE)
		@Tag(SERIALIZATION)
		void shouldRecomputePrimaryKeyOnRead() {
			final HistogramIndexLeafPagePart page = page(42, "price", null, 5, new ValueToRecordPrimitive(1, 1));
			final HistogramIndexLeafPagePart deserialized = roundTrip(page);
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
			final HistogramIndexLeafPagePart page = page(
				9, "price", null, 2,
				new ValueToRecordPrimitive(11, 1),
				new ValueToRecordBitmap(22, 2, 3)
			);

			final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
			try (final Output output = new Output(os, 4_096)) {
				HistogramIndexLeafPagePartSerializerTest.this.kryo.writeObject(output, page);
			}
			final HistogramIndexLeafPagePart deserialized;
			try (final Input input = new Input(os.toByteArray())) {
				deserialized = HistogramIndexLeafPagePartSerializerTest.this.kryo.readObject(input, HistogramIndexLeafPagePart.class);
			}
			assertSamePage(page, deserialized);
		}
	}
}
