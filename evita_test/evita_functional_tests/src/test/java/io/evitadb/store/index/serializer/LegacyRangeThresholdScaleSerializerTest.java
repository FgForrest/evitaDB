/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.index.range.TransactionalRangePoint;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.ReadWriteKeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencyRefIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencySuperIndexStoragePart;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.shared.kryo.KryoFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backward-compatibility coverage for the one format change that is invisible in the bytes: every catalog written
 * before {@link DateTimeRange} moved from second to millisecond comparison granularity persisted its range-index
 * thresholds as epoch **seconds**, and the byte layout did not change with them. A threshold is an untyped `long`, so
 * a reader that got this wrong would answer `attributeInRange` and `priceValidIn` queries with the wrong records and
 * throw nothing at all.
 *
 * The two families are handled differently on purpose, and both are pinned here:
 *
 * - a **price validity** index is always a `DateTimeRange`, so its backward-compatible readers rescale it outright;
 * - an **attribute** range index may be over any of the six range subtypes, so its readers only mark the part's
 *   provenance and the rescale is routed on the declared attribute type by the load path (covered end-to-end, with a
 *   query, by `AttributeIndexLoaderTest`'s legacy nest).
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("legacy second-granularity range thresholds")
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(SERIALIZATION)
@Tag(PRICE)
class LegacyRangeThresholdScaleSerializerTest {
	/** The serial-version-uid release 2026.2 shipped for `FilterIndexStoragePart`. */
	private static final long FILTER_2026_2_UID = 3847290165472938104L;
	/** The serial-version-uid release 2026.2 shipped for `PriceListAndCurrencySuperIndexStoragePart`. */
	private static final long PRICE_SUPER_2026_2_UID = 2938472615049182736L;
	/** The serial-version-uid release 2026.2 shipped for `PriceListAndCurrencyRefIndexStoragePart`. */
	private static final long PRICE_REF_2026_2_UID = 8461029375182640917L;
	/**
	 * The serial-version-uid release 2026.2 shipped for `HistogramIndexStoragePart` — introduced by `fa01ba65f`,
	 * which `git tag --contains` places in v2026.2.0 .. v2026.2.6. It was once assumed unreleased.
	 */
	private static final long HISTOGRAM_2026_2_UID = 5083172946028471653L;

	private static final AttributeIndexKey ATTRIBUTE_KEY = new AttributeIndexKey(null, "validity", null);
	private static final PriceIndexKey PRICE_INDEX_KEY = new PriceIndexKey(
		"basic", Currency.getInstance("EUR"), PriceInnerRecordHandling.NONE
	);
	private static final OffsetDateTime VALID_FROM = OffsetDateTime.parse("2026-05-20T12:19:26Z");
	private static final OffsetDateTime VALID_TO = OffsetDateTime.parse("2026-05-25T12:19:26Z");
	private static final OffsetDateTime INSIDE = OffsetDateTime.parse("2026-05-21T00:00:00Z");
	private static final OffsetDateTime OUTSIDE = OffsetDateTime.parse("2026-06-01T00:00:00Z");

	private Kryo kryo;
	private ReadWriteKeyCompressor keyCompressor;

	/**
	 * Builds the range points a pre-change writer left on disk for one record valid over `[from, to]`, including the
	 * two border sentinels the index always carries.
	 *
	 * @param from     the lower threshold in the writer's own scale
	 * @param to       the upper threshold in the writer's own scale
	 * @param recordId the record the range belongs to
	 * @return the four range points in ascending threshold order
	 */
	@Nonnull
	private static TransactionalRangePoint[] points(long from, long to, int recordId) {
		return new TransactionalRangePoint[]{
			new TransactionalRangePoint(Long.MIN_VALUE),
			new TransactionalRangePoint(from, new int[]{recordId}, new int[0]),
			new TransactionalRangePoint(to, new int[0], new int[]{recordId}),
			new TransactionalRangePoint(Long.MAX_VALUE)
		};
	}

	/**
	 * Returns the index's thresholds in ascending order, so a rescale can be asserted on the numbers themselves
	 * beside the query results.
	 *
	 * @param index the index to read
	 * @return its thresholds, ascending
	 */
	@Nonnull
	private static long[] thresholdsOf(@Nonnull RangeIndex index) {
		final long[] thresholds = new long[index.getRanges().length];
		for (int i = 0; i < thresholds.length; i++) {
			thresholds[i] = index.getRanges()[i].getThreshold();
		}
		return thresholds;
	}

	/**
	 * Re-stamps an already-encoded blob with a different serial-version-uid. The price formats this test exercises
	 * are **byte-identical** to the current ones — only the meaning of their validity thresholds changed — so
	 * re-stamping the current writer's output is a faithful reproduction of what release 2026.2 wrote, and says so
	 * more plainly than a hand-copied wire ever could.
	 *
	 * @param bytes the uid-prefixed blob to re-stamp
	 * @param uid   the serial-version-uid to write over its first eight bytes
	 * @return the same bytes, carrying the legacy uid
	 */
	@Nonnull
	private static byte[] withUid(@Nonnull byte[] bytes, long uid) {
		final byte[] stamp = new byte[Long.BYTES];
		try (final Output output = new Output(stamp)) {
			output.writeLong(uid);
		}
		System.arraycopy(stamp, 0, bytes, 0, Long.BYTES);
		return bytes;
	}

	@BeforeEach
	void setUp() {
		this.keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(this.keyCompressor));
	}

	/**
	 * Hand-encodes the released 2026.2 `FilterIndexStoragePart` wire: the identity header followed by the shared
	 * filter payload, and nothing after it — the value id section that the current format appends did not exist yet.
	 * The deprecated reader's own write path throws, so the wire is reproduced here.
	 *
	 * @param part the part to encode in the legacy format
	 * @return the legacy-format bytes, uid-prefixed
	 */
	@Nonnull
	private byte[] encodeLegacyFilterBytes(@Nonnull FilterIndexStoragePart part) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			output.writeLong(FILTER_2026_2_UID);
			output.writeInt(part.getEntityIndexPrimaryKey());
			output.writeVarLong(part.getStoragePartPK(), true);
			output.writeVarInt(this.keyCompressor.getId(part.getAttributeIndexKey()), true);
			this.kryo.writeClass(output, part.getAttributeType());
			FilterIndexPayloadSerializer.write(
				this.kryo, output,
				part.getHistogramPoints(), part.getRangeIndex(), part.getIndexedDecimalPlaces(),
				part.isPaged(), part.getHighWaterPageSequence(), part.getLeafPageSequences(),
				part.isRangePaged(), part.getRangeHighWaterPageSequence(), part.getRangeLeafPageSequences()
			);
		}
		return os.toByteArray();
	}

	@Nested
	@DisplayName("attribute filter index — the reader marks provenance, the load path routes on the type")
	class FilterPart {

		@Test
		@DisplayName("a released 2026.2 blob reads back marked as second-granularity")
		void shouldMarkALegacyFilterBlob() {
			final DateTimeRange validity = DateTimeRange.between(VALID_FROM, VALID_TO);
			final FilterIndexStoragePart legacy = new FilterIndexStoragePart(
				7, ATTRIBUTE_KEY, DateTimeRange.class,
				new ValueToRecordBitmap[]{new ValueToRecordBitmap(validity, 1)},
				new RangeIndex(points(VALID_FROM.toEpochSecond(), VALID_TO.toEpochSecond(), 1)),
				3L
			);

			final FilterIndexStoragePart decoded = StoragePartSerializerTestSupport.decode(
				LegacyRangeThresholdScaleSerializerTest.this.kryo,
				encodeLegacyFilterBytes(legacy), FilterIndexStoragePart.class
			);

			assertTrue(
				decoded.isSecondGranularityRangeThresholds(),
				"a blob read by a backward-compatible reader must be marked - the load path routes on this"
			);
			// the reader does NOT rescale: it cannot, because a range-PAGED axis keeps its thresholds in leaf-page
			// records this serializer never reads, and because only a `DateTimeRange` index may be rescaled at all
			assertArrayEquals(
				new long[]{Long.MIN_VALUE, VALID_FROM.toEpochSecond(), VALID_TO.toEpochSecond(), Long.MAX_VALUE},
				thresholdsOf(decoded.getRangeIndex()),
				"the reader must hand the thresholds on untouched"
			);
		}

		@Test
		@DisplayName("a part written by the current serializer reads back unmarked")
		void shouldNotMarkACurrentFilterBlob() {
			// the control: nothing but a backward-compatible reader may set the flag, or every catalog would be
			// rescaled on every load and a `DateTimeRange` index would drift by a factor of a thousand per restart
			final DateTimeRange validity = DateTimeRange.between(VALID_FROM, VALID_TO);
			final FilterIndexStoragePart current = new FilterIndexStoragePart(
				7, ATTRIBUTE_KEY, DateTimeRange.class,
				new ValueToRecordBitmap[]{new ValueToRecordBitmap(validity, 1)},
				new RangeIndex(points(validity.getFrom(), validity.getTo(), 1)),
				3L
			);

			final FilterIndexStoragePart decoded = StoragePartSerializerTestSupport.roundTrip(
				LegacyRangeThresholdScaleSerializerTest.this.kryo, current, FilterIndexStoragePart.class
			);

			assertFalse(
				decoded.isSecondGranularityRangeThresholds(),
				"the current serializer writes millisecond thresholds and must never mark its output"
			);
			assertArrayEquals(
				new long[]{Long.MIN_VALUE, validity.getFrom(), validity.getTo(), Long.MAX_VALUE},
				thresholdsOf(decoded.getRangeIndex()),
				"a current blob round-trips its thresholds verbatim"
			);
		}
	}

	@Nested
	@DisplayName("price validity index — the reader rescales outright")
	class PricePart {

		@Test
		@DisplayName("a released 2026.2 super-index blob has its validity rescaled and answers priceValidIn correctly")
		void shouldRescaleASuperIndexValidity() {
			final PriceListAndCurrencySuperIndexStoragePart legacy =
				new PriceListAndCurrencySuperIndexStoragePart(
					42, PRICE_INDEX_KEY,
					new RangeIndex(points(VALID_FROM.toEpochSecond(), VALID_TO.toEpochSecond(), 1)),
					new PriceRecordContract[]{new PriceRecord(1, 1001, 1, 121, 100)},
					7L
				);

			final PriceListAndCurrencySuperIndexStoragePart decoded = StoragePartSerializerTestSupport.decode(
				LegacyRangeThresholdScaleSerializerTest.this.kryo,
				withUid(
					StoragePartSerializerTestSupport.encodeCurrent(
						LegacyRangeThresholdScaleSerializerTest.this.kryo, legacy
					),
					PRICE_SUPER_2026_2_UID
				),
				PriceListAndCurrencySuperIndexStoragePart.class
			);

			assertValidityRescaled(decoded.getValidityIndex());
			assertArrayEquals(
				legacy.getPriceRecords(), decoded.getPriceRecords(),
				"the rest of the record must survive the compatibility read untouched"
			);
		}

		@Test
		@DisplayName("a released 2026.2 ref-index blob has its validity rescaled")
		void shouldRescaleARefIndexValidity() {
			final PriceListAndCurrencyRefIndexStoragePart legacy = new PriceListAndCurrencyRefIndexStoragePart(
				42, PRICE_INDEX_KEY,
				new RangeIndex(points(VALID_FROM.toEpochSecond(), VALID_TO.toEpochSecond(), 1)),
				new int[]{1, 2, 3}, 7L
			);

			final PriceListAndCurrencyRefIndexStoragePart decoded = StoragePartSerializerTestSupport.decode(
				LegacyRangeThresholdScaleSerializerTest.this.kryo,
				withUid(
					StoragePartSerializerTestSupport.encodeCurrent(
						LegacyRangeThresholdScaleSerializerTest.this.kryo, legacy
					),
					PRICE_REF_2026_2_UID
				),
				PriceListAndCurrencyRefIndexStoragePart.class
			);

			assertValidityRescaled(decoded.getValidityIndex());
			assertArrayEquals(
				new int[]{1, 2, 3}, decoded.getPriceIds(),
				"the rest of the record must survive the compatibility read untouched"
			);
		}

		@Test
		@DisplayName("a super-index blob written by the current serializer is not rescaled")
		void shouldNotRescaleACurrentSuperIndexValidity() {
			// the control: a current blob already holds millisecond thresholds, and rescaling it a second time
			// would move every price validity a thousand years into the future
			final DateTimeRange validity = DateTimeRange.between(VALID_FROM, VALID_TO);
			final PriceListAndCurrencySuperIndexStoragePart current =
				new PriceListAndCurrencySuperIndexStoragePart(
					42, PRICE_INDEX_KEY,
					new RangeIndex(points(validity.getFrom(), validity.getTo(), 1)),
					new PriceRecordContract[]{new PriceRecord(1, 1001, 1, 121, 100)},
					7L
				);

			final PriceListAndCurrencySuperIndexStoragePart decoded = StoragePartSerializerTestSupport.roundTrip(
				LegacyRangeThresholdScaleSerializerTest.this.kryo, current,
				PriceListAndCurrencySuperIndexStoragePart.class
			);

			assertValidityRescaled(decoded.getValidityIndex());
		}

		/**
		 * Asserts the validity index answers the enveloping query `priceValidIn` runs at the millisecond scale: the
		 * price is selected at a
		 * moment inside its validity, not selected outside it, and not selected by a probe left in the old
		 * second-granularity scale — the last of which is what fails when the rescale is dropped.
		 *
		 * @param validityIndex the validity index to probe
		 */
		private void assertValidityRescaled(@Nonnull RangeIndex validityIndex) {
			assertArrayEquals(
				new long[]{
					Long.MIN_VALUE, DateTimeRange.toComparableLong(VALID_FROM),
					DateTimeRange.toComparableLong(VALID_TO), Long.MAX_VALUE
				},
				thresholdsOf(validityIndex),
				"the validity thresholds must be whole epoch milliseconds"
			);
			assertArrayEquals(
				new int[]{1},
				validityIndex.getRecordsEnvelopingInclusive(DateTimeRange.toComparableLong(INSIDE))
					.compute().getArray(),
				"a moment inside the validity must select the price"
			);
			assertArrayEquals(
				new int[0],
				validityIndex.getRecordsEnvelopingInclusive(DateTimeRange.toComparableLong(OUTSIDE))
					.compute().getArray(),
				"a moment after the validity must select nothing"
			);
			assertArrayEquals(
				new int[0],
				validityIndex.getRecordsEnvelopingInclusive(INSIDE.toEpochSecond()).compute().getArray(),
				"a second-granularity probe must no longer land inside the rescaled validity"
			);
		}
	}

	@Nested
	@DisplayName("histogram index — a released shape that was once assumed unreleased")
	class HistogramPart {

		@Test
		@DisplayName("a released 2026.2 blob still opens, and reads back marked as second-granularity")
		void shouldReadAReleasedHistogramBlob() {
			// the regression test for a real production-catalog failure: bumping this record's uid without a reader
			// made every released 2026.2 catalog carrying a histogram index fail to open outright with
			// `StoredVersionNotSupportedException ... Supported backward compatible versions for this class are: none`
			final DateTimeRange validity = DateTimeRange.between(VALID_FROM, VALID_TO);
			final HistogramIndexStoragePart legacy = new HistogramIndexStoragePart(
				7, "priceHistogram", null, DateTimeRange.class,
				new ValueToRecordBitmap[]{new ValueToRecordBitmap(validity, 1)},
				new RangeIndex(points(VALID_FROM.toEpochSecond(), VALID_TO.toEpochSecond(), 1)),
				0
			);

			final HistogramIndexStoragePart decoded = StoragePartSerializerTestSupport.decode(
				LegacyRangeThresholdScaleSerializerTest.this.kryo,
				withUid(
					StoragePartSerializerTestSupport.encodeCurrent(
						LegacyRangeThresholdScaleSerializerTest.this.kryo, legacy
					),
					HISTOGRAM_2026_2_UID
				),
				HistogramIndexStoragePart.class
			);

			assertTrue(
				decoded.isSecondGranularityRangeThresholds(),
				"a blob read by a backward-compatible reader must be marked - the load path routes on this"
			);
			assertEquals(DateTimeRange.class, decoded.getValueType(), "the value type must survive the read");
			// the reader hands the thresholds on untouched, exactly as the filter reader does: a range-PAGED axis
			// keeps them in leaf-page records this serializer never sees
			assertArrayEquals(
				new long[]{Long.MIN_VALUE, VALID_FROM.toEpochSecond(), VALID_TO.toEpochSecond(), Long.MAX_VALUE},
				thresholdsOf(decoded.getRangeIndex()),
				"the reader must hand the thresholds on untouched"
			);
		}

		@Test
		@DisplayName("a part written by the current serializer reads back unmarked")
		void shouldNotMarkACurrentHistogramBlob() {
			final DateTimeRange validity = DateTimeRange.between(VALID_FROM, VALID_TO);
			final HistogramIndexStoragePart current = new HistogramIndexStoragePart(
				7, "priceHistogram", null, DateTimeRange.class,
				new ValueToRecordBitmap[]{new ValueToRecordBitmap(validity, 1)},
				new RangeIndex(points(validity.getFrom(), validity.getTo(), 1)),
				0
			);

			final HistogramIndexStoragePart decoded = StoragePartSerializerTestSupport.roundTrip(
				LegacyRangeThresholdScaleSerializerTest.this.kryo, current, HistogramIndexStoragePart.class
			);

			assertFalse(
				decoded.isSecondGranularityRangeThresholds(),
				"the current serializer writes millisecond thresholds and must never mark its output"
			);
			assertArrayEquals(
				new long[]{Long.MIN_VALUE, validity.getFrom(), validity.getTo(), Long.MAX_VALUE},
				thresholdsOf(decoded.getRangeIndex()),
				"a current blob round-trips its thresholds verbatim"
			);
		}
	}

	@Nested
	@DisplayName("the threshold rescale itself")
	class RescaleRule {

		@Test
		@DisplayName("a real second threshold is multiplied, a legacy open sentinel becomes the constant one")
		void shouldSeparateRealThresholdsFromLegacySentinels() {
			// the separation has four orders of magnitude of margin on each side: no date expressible as a scalar
			// temporal attribute exceeds ~1e11 seconds, the window edge sits at ~9.22e15, and the legacy open-bound
			// sentinels are ~3.16e16
			assertEquals(
				1_779_279_566_000L, RangeIndex.rescaleSecondGranularityThreshold(1_779_279_566L),
				"a real moment is multiplied by exactly a thousand"
			);
			assertEquals(
				-1_779_279_566_000L, RangeIndex.rescaleSecondGranularityThreshold(-1_779_279_566L),
				"and so is a pre-epoch one"
			);
			assertEquals(
				DateTimeRange.OPEN_TO_THRESHOLD,
				RangeIndex.rescaleSecondGranularityThreshold(
					java.time.LocalDateTime.MAX.atOffset(java.time.ZoneOffset.ofHours(-18)).toEpochSecond()
				),
				"the legacy open-to sentinel at the extreme offset must land on the constant"
			);
			assertEquals(
				DateTimeRange.OPEN_FROM_THRESHOLD,
				RangeIndex.rescaleSecondGranularityThreshold(
					java.time.LocalDateTime.MIN.atOffset(java.time.ZoneOffset.ofHours(18)).toEpochSecond()
				),
				"and so must the legacy open-from sentinel at the other extreme"
			);
			assertEquals(
				Long.MIN_VALUE, RangeIndex.rescaleSecondGranularityThreshold(Long.MIN_VALUE),
				"the index's own lower border point maps onto itself"
			);
			assertEquals(
				Long.MAX_VALUE, RangeIndex.rescaleSecondGranularityThreshold(Long.MAX_VALUE),
				"and so does the upper one"
			);
		}

		@Test
		@DisplayName("points that collide after the rescale are merged rather than duplicated")
		void shouldMergeCollidingPointsOnRescale() {
			// two open-ended ranges written at DIFFERENT zone offsets held two distinct upper thresholds, and the
			// index's own border point held a third. All three land on `Long.MAX_VALUE` now, and a range index
			// cannot hold one threshold twice - so the repair has to union their record sets
			final long legacyOpenAtUtc =
				java.time.LocalDateTime.MAX.atOffset(java.time.ZoneOffset.UTC).toEpochSecond();
			final long legacyOpenAtPlusTwo =
				java.time.LocalDateTime.MAX.atOffset(java.time.ZoneOffset.ofHours(2)).toEpochSecond();
			// the sentinel written at +02:00 sorts BELOW the one written at UTC, because the same local instant is an
			// earlier moment there - which is exactly the offset dependence the constant sentinels removed
			assertTrue(
				legacyOpenAtPlusTwo < legacyOpenAtUtc, "the two legacy sentinels must really differ, and in this order"
			);

			final RangeIndex rescaled = RangeIndex.rescaledFromSecondGranularity(
				new RangeIndex(new TransactionalRangePoint[]{
					new TransactionalRangePoint(Long.MIN_VALUE),
					new TransactionalRangePoint(VALID_FROM.toEpochSecond(), new int[]{1, 2}, new int[0]),
					new TransactionalRangePoint(legacyOpenAtPlusTwo, new int[0], new int[]{2}),
					new TransactionalRangePoint(legacyOpenAtUtc, new int[0], new int[]{1}),
					new TransactionalRangePoint(Long.MAX_VALUE)
				})
			);

			assertArrayEquals(
				new long[]{Long.MIN_VALUE, DateTimeRange.toComparableLong(VALID_FROM), Long.MAX_VALUE},
				thresholdsOf(rescaled),
				"the two sentinels and the border point must have merged onto one threshold"
			);
			final List<Integer> endsAtMax = new ArrayList<>(2);
			rescaled.getRanges()[2].getEnds().forEach(endsAtMax::add);
			assertEquals(List.of(1, 2), endsAtMax, "both records' ends must survive the merge");
			// and both records really are still selected at a moment past the lower bound
			assertArrayEquals(
				new int[]{1, 2},
				rescaled.getRecordsEnvelopingInclusive(DateTimeRange.toComparableLong(OUTSIDE)).compute().getArray(),
				"both open-ended records must still be valid far after their lower bound"
			);
		}

		@Test
		@DisplayName("a numeric range index is left alone by construction — the rescale is never called for one")
		void shouldNeverTouchANumericRange() {
			// a numeric threshold is the caller's own number: `IntegerNumberRange.between(10, 20)` stores 10 and 20,
			// which the rescale rule would happily multiply. Nothing but the declared attribute type keeps it away,
			// which is why the routing lives in the load path and is pinned there
			final IntegerNumberRange numeric = IntegerNumberRange.between(10, 20);
			assertEquals(10L, numeric.getFrom());
			assertEquals(20L, numeric.getTo());
			assertEquals(
				10_000L, RangeIndex.rescaleSecondGranularityThreshold(numeric.getFrom()),
				"applied to a numeric threshold the rule silently multiplies it - which is the whole hazard"
			);
		}
	}
}
