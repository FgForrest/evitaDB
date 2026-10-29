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
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.index.range.TransactionalRangePoint;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramRangeIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencySuperIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.RangeIndexLeafPagePart;
import io.evitadb.store.entity.exception.StoredVersionNotSupportedException;
import io.evitadb.store.entity.serializer.SerialVersionBasedSerializer;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.ObjectStreamClass;
import java.util.Collections;
import java.util.Currency;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how range points written by a release before the occurrence levels existed are read: the threshold, one bitmap
 * of starts and one of ends, under the released serial version UID, decode to points with occurrence level 1 only —
 * through the one registration of {@link TransactionalRangePoint}, whichever container holds them.
 *
 * The release bytes are produced by a test-side writer that stamps the released UID explicitly. Writing them through
 * the current serializer would stamp the current UID and route them to the current reader, and every assertion here
 * would then pass without ever reaching {@link TransactionalIntRangePointSerializer_2026_2}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Range points written before the occurrence levels")
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(SERIALIZATION)
class TransactionalIntRangePointSerializer_2026_2Test {
	/**
	 * The serial version UID every release up to and including 2026.2 stamped on a `TransactionalRangePoint`.
	 */
	private static final long RELEASED_POINT_UID = -7357845800177404940L;
	private static final AttributeIndexKey ATTRIBUTE_KEY = new AttributeIndexKey(null, "span", null);
	private static final PriceIndexKey PRICE_INDEX_KEY = new PriceIndexKey(
		"basic", Currency.getInstance("EUR"), PriceInnerRecordHandling.NONE
	);

	private ReadWriteKeyCompressor keyCompressor;
	/**
	 * The production configuration - what reads every blob in this test.
	 */
	private Kryo kryo;
	/**
	 * The production configuration with the point registration replaced by {@link ReleasePointWriter}, so every point
	 * a container writes is in the release layout and carries the released UID.
	 */
	private Kryo releaseKryo;

	/**
	 * Writes a point exactly as every release up to 2026.2 did: the released UID the `SerialVersionBasedSerializer`
	 * frame starts with, the threshold, and the bitmaps of starts and ends — nothing after them.
	 */
	private static final class ReleasePointWriter extends Serializer<TransactionalRangePoint> {

		@Override
		public void write(Kryo kryo, Output output, TransactionalRangePoint point) {
			output.writeLong(RELEASED_POINT_UID);
			output.writeLong(point.getThreshold());
			kryo.writeObject(output, point.getStarts());
			kryo.writeObject(output, point.getEnds());
		}

		@Override
		public TransactionalRangePoint read(Kryo kryo, Input input, Class<? extends TransactionalRangePoint> type) {
			throw new UnsupportedOperationException("The release writer only writes.");
		}
	}

	/**
	 * Builds the points of one record valid over `[from, to]`, with the two border sentinels.
	 *
	 * @param from     the lower threshold
	 * @param to       the upper threshold
	 * @param recordId the record
	 * @return the points in ascending threshold order
	 */
	@Nonnull
	private static TransactionalRangePoint[] points(long from, long to, int recordId) {
		return new TransactionalRangePoint[]{
			new TransactionalRangePoint(Long.MIN_VALUE),
			new TransactionalRangePoint(from, new int[]{recordId, recordId + 1}, new int[0]),
			new TransactionalRangePoint(to, new int[0], new int[]{recordId, recordId + 1}),
			new TransactionalRangePoint(Long.MAX_VALUE)
		};
	}

	/**
	 * Asserts the decoded points carry exactly the expected level-1 content and no repeated level.
	 *
	 * @param expected the points that were written
	 * @param actual   the points that were read
	 */
	private static void assertLevelOneOnly(
		@Nonnull TransactionalRangePoint[] expected, @Nonnull TransactionalRangePoint[] actual
	) {
		assertEquals(expected.length, actual.length);
		for (int i = 0; i < expected.length; i++) {
			assertTrue(expected[i].deepEquals(actual[i]), "point " + i + ": " + actual[i]);
			assertEquals(0, actual[i].getRepeatedStarts().length);
			assertEquals(0, actual[i].getRepeatedEnds().length);
		}
	}

	/**
	 * Returns the points of a range index in ascending threshold order.
	 *
	 * @param index the index
	 * @return its points
	 */
	@Nonnull
	private static TransactionalRangePoint[] pointsOf(@Nonnull RangeIndex index) {
		final TransactionalRangePoint[] result = new TransactionalRangePoint[index.getRanges().length];
		for (int i = 0; i < result.length; i++) {
			result[i] = (TransactionalRangePoint) index.getRanges()[i];
		}
		return result;
	}

	/**
	 * Writes an object through the passed Kryo.
	 *
	 * @param kryo   the Kryo to write with
	 * @param object the object
	 * @return the bytes
	 */
	@Nonnull
	private static byte[] encode(@Nonnull Kryo kryo, @Nonnull Object object) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			kryo.writeObject(output, object);
		}
		return os.toByteArray();
	}

	/**
	 * Reads an object through the passed Kryo.
	 *
	 * @param kryo  the Kryo to read with
	 * @param bytes the bytes
	 * @param type  the type to read
	 * @param <T>   the type
	 * @return the object
	 */
	@Nonnull
	private static <T> T decode(@Nonnull Kryo kryo, @Nonnull byte[] bytes, @Nonnull Class<T> type) {
		try (final Input input = new Input(bytes)) {
			return kryo.readObject(input, type);
		}
	}

	@BeforeEach
	void setUp() {
		this.keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(this.keyCompressor));
		this.releaseKryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(this.keyCompressor));
		this.releaseKryo.register(
			TransactionalRangePoint.class, new ReleasePointWriter(),
			this.kryo.getRegistration(TransactionalRangePoint.class).getId()
		);
	}

	@Nested
	@DisplayName("A single point")
	class SinglePoint {

		@Test
		@DisplayName("premise: the point's UID moved on from the released one")
		void shouldCarryNewUidSinceRelease() {
			assertNotEquals(
				RELEASED_POINT_UID, ObjectStreamClass.lookup(TransactionalRangePoint.class).getSerialVersionUID()
			);
		}

		@Test
		@DisplayName("a release point decodes to its level-1 content and no repeated level")
		void shouldReadReleasePointAsLevelOneOnly() {
			final TransactionalRangePoint written = new TransactionalRangePoint(42L, new int[]{1, 2, 3}, new int[]{4});
			final TransactionalRangePoint read = decode(
				TransactionalIntRangePointSerializer_2026_2Test.this.kryo,
				encode(TransactionalIntRangePointSerializer_2026_2Test.this.releaseKryo, written),
				TransactionalRangePoint.class
			);
			assertLevelOneOnly(new TransactionalRangePoint[]{written}, new TransactionalRangePoint[]{read});
		}

		@Test
		@DisplayName("premise: without the backward-compatible reader the release bytes are refused")
		void shouldRefuseReleasePointWithoutCompatibleReader() {
			final Kryo withoutReader = KryoFactory.createKryo(
				new IndexStoragePartConfigurer(TransactionalIntRangePointSerializer_2026_2Test.this.keyCompressor)
			);
			withoutReader.register(
				TransactionalRangePoint.class,
				new SerialVersionBasedSerializer<>(
					new TransactionalIntRangePointSerializer(), TransactionalRangePoint.class
				),
				withoutReader.getRegistration(TransactionalRangePoint.class).getId()
			);
			final byte[] releaseBytes = encode(
				TransactionalIntRangePointSerializer_2026_2Test.this.releaseKryo,
				new TransactionalRangePoint(42L, new int[]{1}, new int[0])
			);
			assertThrows(
				StoredVersionNotSupportedException.class,
				() -> decode(withoutReader, releaseBytes, TransactionalRangePoint.class)
			);
		}

		@Test
		@DisplayName("the current format keeps every repeated level, and costs a level-free point two bytes")
		void shouldRoundTripRepeatedLevelsInCurrentFormat() {
			final TransactionalRangePoint withLevels = new TransactionalRangePoint(
				7L, new BaseBitmap(1, 2), new BaseBitmap(3),
				new Bitmap[]{new BaseBitmap(1, 2), new BaseBitmap(1)}, new Bitmap[]{new BaseBitmap(3)}
			);
			final TransactionalRangePoint read = decode(
				TransactionalIntRangePointSerializer_2026_2Test.this.kryo,
				encode(TransactionalIntRangePointSerializer_2026_2Test.this.kryo, withLevels),
				TransactionalRangePoint.class
			);
			assertTrue(withLevels.deepEquals(read), read.toString());
			assertArrayEquals(new int[]{1}, read.getRepeatedStarts()[1].getArray());

			final TransactionalRangePoint plain = new TransactionalRangePoint(7L, new int[]{1, 2}, new int[]{3});
			assertEquals(
				encode(TransactionalIntRangePointSerializer_2026_2Test.this.releaseKryo, plain).length + 2,
				encode(TransactionalIntRangePointSerializer_2026_2Test.this.kryo, plain).length,
				"a point where nothing repeats spends two bytes on the two zero level counts"
			);
		}
	}

	@Nested
	@DisplayName("Every container of range points")
	class Containers {

		@Test
		@DisplayName("an inline FILTER range axis")
		void shouldReadReleasePointsOfInlineFilterAxis() {
			final TransactionalRangePoint[] points = points(10L, 20L, 1);
			final FilterIndexStoragePart part = new FilterIndexStoragePart(
				7, ATTRIBUTE_KEY, IntegerNumberRange.class,
				new ValueToRecordBitmap[]{new ValueToRecordBitmap(IntegerNumberRange.between(10, 20), 1, 2)},
				new RangeIndex(points),
				3L
			);
			final FilterIndexStoragePart read = decodePart(part, FilterIndexStoragePart.class);
			assertLevelOneOnly(points, pointsOf(read.getRangeIndex()));
		}

		@Test
		@DisplayName("a paged FILTER range axis leaf page")
		void shouldReadReleasePointsOfFilterLeafPage() {
			final TransactionalRangePoint[] points = points(10L, 20L, 1);
			final RangeIndexLeafPagePart page = new RangeIndexLeafPagePart(
				7, new AttributeKeyWithIndexType(null, "span", null, AttributeIndexType.FILTER), 0, points
			);
			page.computeUniquePartIdAndSet(TransactionalIntRangePointSerializer_2026_2Test.this.keyCompressor);
			final RangeIndexLeafPagePart read = decodePart(page, RangeIndexLeafPagePart.class);
			assertLevelOneOnly(points, read.getPoints());
		}

		@Test
		@DisplayName("a histogram range axis leaf page")
		void shouldReadReleasePointsOfHistogramLeafPage() {
			final TransactionalRangePoint[] points = points(10L, 20L, 1);
			final HistogramRangeIndexLeafPagePart page = new HistogramRangeIndexLeafPagePart(
				7, "spanHistogram", null, 0, points
			);
			page.computeUniquePartIdAndSet(TransactionalIntRangePointSerializer_2026_2Test.this.keyCompressor);
			final HistogramRangeIndexLeafPagePart read = decodePart(page, HistogramRangeIndexLeafPagePart.class);
			assertLevelOneOnly(points, read.getPoints());
		}

		@Test
		@DisplayName("a price validity index")
		void shouldReadReleasePointsOfPriceValidity() {
			final TransactionalRangePoint[] points = points(1_000L, 2_000L, 1);
			final PriceListAndCurrencySuperIndexStoragePart part = new PriceListAndCurrencySuperIndexStoragePart(
				42, PRICE_INDEX_KEY, new RangeIndex(points),
				new PriceRecordContract[]{new PriceRecord(1, 1001, 1, 121, 100)},
				7L
			);
			final PriceListAndCurrencySuperIndexStoragePart read = decodePart(
				part, PriceListAndCurrencySuperIndexStoragePart.class
			);
			assertLevelOneOnly(points, pointsOf(read.getValidityIndex()));
		}

		/**
		 * Writes the part with the release point writer and reads it back through the production configuration.
		 *
		 * @param part the part to write
		 * @param type its type
		 * @param <T>  its type
		 * @return the part read back
		 */
		@Nonnull
		private <T extends StoragePart> T decodePart(@Nonnull T part, @Nonnull Class<T> type) {
			return decode(
				TransactionalIntRangePointSerializer_2026_2Test.this.kryo,
				encode(TransactionalIntRangePointSerializer_2026_2Test.this.releaseKryo, part),
				type
			);
		}
	}

}
