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
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.index.range.TransactionalRangePoint;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.stream.Stream;

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
 * Versioned readers convert inline DateTimeRange companions using the root's declared plain type. Only paged
 * temporal companions retain a marker for the storage migration, whose tests cover their cross-record rewrite.
 * Price validity has always been reader-side because it is always inline and temporal.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("legacy second-granularity range thresholds")
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(SERIALIZATION)
@Tag(PRICE)
class LegacyRangeThresholdScaleSerializerTest {
	/**
	 * The serial-version-uid release 2025.5 shipped for `FilterIndexStoragePart` — the oldest of the three
	 * backward-compatible filter readers, and the only one that still finds a plain `AttributeKey` in the slot the
	 * attribute index key now occupies.
	 */
	private static final long FILTER_2025_5_UID = -3363238752052021735L;
	/** The serial-version-uid release 2026.1 shipped for `FilterIndexStoragePart`. */
	private static final long FILTER_2026_1_UID = 942367579256351640L;
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

	/** Registers the production versioned readers and an isolated compressor. */
	@BeforeEach
	void setUp() {
		this.keyCompressor = new ReadWriteKeyCompressor(Collections.emptyMap());
		this.kryo = KryoFactory.createKryo(new IndexStoragePartConfigurer(this.keyCompressor));
	}

	/**
	 * Hand-encodes the released 2026.2 `FilterIndexStoragePart` wire: the identity header followed by the shared
	 * filter payload, and nothing after it — the value id section that the current format appends did not exist yet.
	 * The deprecated reader's own write path throws, so the wire is reproduced here.
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

	/**
	 * Hand-encodes the released pre-freeze `FilterIndexStoragePart` wire. It differs from the 2026.2 one in two places:
	 * the attribute key slot holds a plain {@link AttributeKey} rather than a compressed `AttributeIndexKey` (the
	 * reader bridges it), and the payload tail carries neither the frozen decimal-places scale nor either page-stream
	 * metadata axis — both formats predate them. The deprecated reader's own write path throws, so the wire is
	 * reproduced here.
	 */
	@Nonnull
	private byte[] encodeLegacyPreFreezeFilterBytes(@Nonnull FilterIndexStoragePart part, long uid) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			output.writeLong(uid);
			output.writeInt(part.getEntityIndexPrimaryKey());
			output.writeVarLong(part.getStoragePartPK(), true);
			// the slot the current format spends on a compressed `AttributeIndexKey` held a plain `AttributeKey`
			output.writeVarInt(uid == FILTER_2025_5_UID
				? this.keyCompressor.getId(new AttributeKey("validity"))
				: this.keyCompressor.getId(part.getAttributeIndexKey()), true);
			this.kryo.writeClass(output, part.getAttributeType());
			final ValueToRecordBitmap[] points = part.getHistogramPoints();
			output.writeInt(points.length);
			for (final ValueToRecordBitmap point : points) {
				this.kryo.writeObject(output, point);
			}
			final RangeIndex rangeIndex = part.getRangeIndex();
			output.writeBoolean(rangeIndex != null);
			if (rangeIndex != null) {
				this.kryo.writeObject(output, rangeIndex);
			}
		}
		return os.toByteArray();
	}

	/** Combines each released inline filter wire with scalar, array and numeric declared types. */
	private static Stream<Arguments> inlineFilterFormats() {
		return Stream.of(FILTER_2025_5_UID, FILTER_2026_1_UID, FILTER_2026_2_UID)
			.flatMap(uid -> Stream.of(DateTimeRange.class, DateTimeRange[].class,
				IntegerNumberRange.class, IntegerNumberRange[].class).map(type -> Arguments.of(uid, type)));
	}

	/** Ported loader routing cases now prove every versioned filter reader converts only temporal inline ranges. */
	@ParameterizedTest(name = "uid={0}, type={1}")
	@MethodSource("inlineFilterFormats")
	@DisplayName("routes inline conversion on the persisted plain type for every legacy filter format")
	void shouldConvertOnlyDeclaredTemporalInlineFilterRanges(long uid, Class<?> declaredType) {
		final FilterIndexStoragePart legacy = new FilterIndexStoragePart(7, ATTRIBUTE_KEY, declaredType,
			new ValueToRecordBitmap[0], new RangeIndex(points(10, 20, 1)), 3L);
		final byte[] bytes;
		if (uid == FILTER_2026_2_UID) {
			bytes = encodeLegacyFilterBytes(legacy);
		} else {
			bytes = encodeLegacyPreFreezeFilterBytes(legacy, uid);
		}
		final FilterIndexStoragePart decoded = StoragePartSerializerTestSupport.decode(this.kryo, bytes,
			FilterIndexStoragePart.class);
		final boolean temporal = declaredType == DateTimeRange.class || declaredType == DateTimeRange[].class;
		assertArrayEquals(new long[]{Long.MIN_VALUE, temporal ? 10_000L : 10L,
			temporal ? 20_000L : 20L, Long.MAX_VALUE}, thresholdsOf(decoded.getRangeIndex()));
		assertFalse(decoded.isSecondGranularityRangePages());
		assertArrayEquals(new int[]{1}, decoded.getRangeIndex()
			.getRecordsEnvelopingInclusive(temporal ? 15_000L : 15L).compute().getArray());
		assertArrayEquals(new int[0], decoded.getRangeIndex()
			.getRecordsEnvelopingInclusive(temporal ? 15L : 15_000L).compute().getArray());
		final FilterIndexStoragePart current = StoragePartSerializerTestSupport.roundTrip(this.kryo, decoded,
			FilterIndexStoragePart.class);
		assertArrayEquals(thresholdsOf(decoded.getRangeIndex()), thresholdsOf(current.getRangeIndex()));
	}

	/** Histogram readers apply the same scalar and array type gate, including untouched numeric thresholds. */
	@ParameterizedTest
	@MethodSource("histogramTypes")
	@DisplayName("converts only temporal inline histogram companions")
	void shouldConvertOnlyDeclaredTemporalInlineHistogramRanges(Class<?> declaredType) {
		final HistogramIndexStoragePart legacy = new HistogramIndexStoragePart(7, "ranges", null, declaredType,
			new ValueToRecordBitmap[0], new RangeIndex(points(10, 20, 1)), 0);
		final HistogramIndexStoragePart decoded = StoragePartSerializerTestSupport.decode(this.kryo,
			withUid(StoragePartSerializerTestSupport.encodeCurrent(this.kryo, legacy), HISTOGRAM_2026_2_UID),
			HistogramIndexStoragePart.class);
		final boolean temporal = declaredType == DateTimeRange.class || declaredType == DateTimeRange[].class;
		assertArrayEquals(new long[]{Long.MIN_VALUE, temporal ? 10_000L : 10L,
			temporal ? 20_000L : 20L, Long.MAX_VALUE}, thresholdsOf(decoded.getRangeIndex()));
		assertFalse(decoded.isSecondGranularityRangePages());
		assertArrayEquals(new int[]{1}, decoded.getRangeIndex()
			.getRecordsEnvelopingInclusive(temporal ? 15_000L : 15L).compute().getArray());
		assertArrayEquals(new int[0], decoded.getRangeIndex()
			.getRecordsEnvelopingInclusive(temporal ? 15L : 15_000L).compute().getArray());
	}

	/** Covers scalar and array forms of both temporal and numeric histogram range types. */
	private static Stream<Class<?>> histogramTypes() {
		return Stream.of(
			DateTimeRange.class, DateTimeRange[].class, IntegerNumberRange.class, IntegerNumberRange[].class
		);
	}

	/** Only paged temporal roots retain provenance, since their unversioned leaves require migration. */
	@ParameterizedTest
	@MethodSource("histogramTypes")
	@DisplayName("marks only paged temporal range companions in filter and histogram readers")
	void shouldMarkOnlyTemporalRangePages(Class<?> declaredType) {
		final FilterIndexStoragePart filter = new FilterIndexStoragePart(7, ATTRIBUTE_KEY, declaredType,
			new ValueToRecordBitmap[0], null, 0, false, -1, new int[0], true, 100, new int[]{11, 17}, 3L);
		final FilterIndexStoragePart decodedFilter = StoragePartSerializerTestSupport.decode(this.kryo,
			encodeLegacyFilterBytes(filter), FilterIndexStoragePart.class);
		final HistogramIndexStoragePart histogram = new HistogramIndexStoragePart(7, "ranges", null, declaredType,
			new ValueToRecordBitmap[0], null, 0, false, -1, new int[0], true, 100, new int[]{11, 17}, null);
		final HistogramIndexStoragePart decodedHistogram = StoragePartSerializerTestSupport.decode(this.kryo,
			withUid(StoragePartSerializerTestSupport.encodeCurrent(this.kryo, histogram), HISTOGRAM_2026_2_UID),
			HistogramIndexStoragePart.class);
		final boolean temporal = declaredType == DateTimeRange.class || declaredType == DateTimeRange[].class;
		assertEquals(temporal, decodedFilter.isSecondGranularityRangePages());
		assertEquals(temporal, decodedHistogram.isSecondGranularityRangePages());
		assertArrayEquals(new int[]{11, 17}, decodedFilter.getRangeLeafPageSequences());
		assertArrayEquals(new int[]{11, 17}, decodedHistogram.getRangeLeafPageSequences());
	}

	/** Open-bound collisions are converted and merged by each reader rather than by an engine loader. */
	@ParameterizedTest
	@ValueSource(longs = {FILTER_2025_5_UID, FILTER_2026_1_UID, FILTER_2026_2_UID, HISTOGRAM_2026_2_UID})
	@DisplayName("merges legacy open thresholds with border points and answers future probes")
	void shouldConvertOpenEndedCompanionsInReaders(long uid) {
		final RangeIndex legacyRange = new RangeIndex(points(10,
			LocalDateTime.MAX.atOffset(ZoneOffset.UTC).toEpochSecond(), 1));
		final RangeIndex decoded;
		if (uid == HISTOGRAM_2026_2_UID) {
			final HistogramIndexStoragePart part = new HistogramIndexStoragePart(7, "ranges", null,
				DateTimeRange.class, new ValueToRecordBitmap[0], legacyRange, 0);
			decoded = StoragePartSerializerTestSupport.decode(this.kryo,
				withUid(StoragePartSerializerTestSupport.encodeCurrent(this.kryo, part), uid),
				HistogramIndexStoragePart.class).getRangeIndex();
		} else {
			final FilterIndexStoragePart part = new FilterIndexStoragePart(7, ATTRIBUTE_KEY, DateTimeRange.class,
				new ValueToRecordBitmap[0], legacyRange, 3L);
			decoded = StoragePartSerializerTestSupport.decode(this.kryo, uid == FILTER_2026_2_UID
				? encodeLegacyFilterBytes(part) : encodeLegacyPreFreezeFilterBytes(part, uid),
				FilterIndexStoragePart.class).getRangeIndex();
		}
		assertArrayEquals(new long[]{Long.MIN_VALUE, 10_000L, Long.MAX_VALUE}, thresholdsOf(decoded));
		assertArrayEquals(new int[]{1}, decoded.getRecordsEnvelopingInclusive(1_000_000L).compute().getArray());
		assertArrayEquals(new int[0], decoded.getRecordsEnvelopingInclusive(9_999L).compute().getArray());
	}

	@Nested
	@DisplayName("attribute filter readers convert inline ranges")
	class FilterPart {

		/** The oldest typed inline filter decodes once to concrete millisecond thresholds and bridges its key. */
		@Test
		@DisplayName("a released 2025.5 inline blob reads back in milliseconds, through the attribute key bridge")
		void shouldConvertALegacy2025FilterBlob() {
			final DateTimeRange validity = DateTimeRange.between(VALID_FROM, VALID_TO);
			final FilterIndexStoragePart legacy = new FilterIndexStoragePart(
				7, ATTRIBUTE_KEY, DateTimeRange.class,
				new ValueToRecordBitmap[]{new ValueToRecordBitmap(validity, 1)},
				new RangeIndex(points(VALID_FROM.toEpochSecond(), VALID_TO.toEpochSecond(), 1)),
				3L
			);

			final FilterIndexStoragePart decoded = StoragePartSerializerTestSupport.decode(
				LegacyRangeThresholdScaleSerializerTest.this.kryo,
				encodeLegacyPreFreezeFilterBytes(legacy, FILTER_2025_5_UID), FilterIndexStoragePart.class
			);

			assertFalse(
				decoded.isSecondGranularityRangePages(),
				"inline companions have already been converted"
			);
			assertEquals(
				ATTRIBUTE_KEY, decoded.getAttributeIndexKey(),
				"the plain attribute key must have been bridged into an attribute index key"
			);
			assertArrayEquals(
				new long[]{Long.MIN_VALUE, 1_779_279_566_000L, 1_779_711_566_000L, Long.MAX_VALUE},
				thresholdsOf(decoded.getRangeIndex()),
				"the reader converts every inline threshold exactly once"
			);
		}

		/** The last legacy filter reader converts inline thresholds and leaves no paged migration marker. */
		@Test
		@DisplayName("a released 2026.2 inline blob reads back in milliseconds")
		void shouldConvertALegacyFilterBlob() {
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

			assertFalse(
				decoded.isSecondGranularityRangePages(),
				"inline companions have already been converted"
			);
			assertArrayEquals(
				new long[]{Long.MIN_VALUE, 1_779_279_566_000L, 1_779_711_566_000L, Long.MAX_VALUE},
				thresholdsOf(decoded.getRangeIndex()),
				"the reader converts every inline threshold exactly once"
			);
		}

		/** A current filter round trip preserves millisecond thresholds without marking or multiplying them. */
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
				decoded.isSecondGranularityRangePages(),
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

		/** The legacy super-price reader converts validity while preserving the price records. */
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

		/** The legacy reference-price reader converts validity while retaining all referenced price ids. */
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

		/** A current price root preserves validity thresholds across serialization. */
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

		/** The released histogram reader remains registered and converts inline validity to concrete milliseconds. */
		@Test
		@DisplayName("a released 2026.2 inline histogram opens with millisecond thresholds")
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

			assertFalse(
				decoded.isSecondGranularityRangePages(),
				"inline companions have already been converted"
			);
			assertEquals(DateTimeRange.class, decoded.getValueType(), "the value type must survive the read");
			assertArrayEquals(
				new long[]{Long.MIN_VALUE, 1_779_279_566_000L, 1_779_711_566_000L, Long.MAX_VALUE},
				thresholdsOf(decoded.getRangeIndex()),
				"the reader converts every inline threshold exactly once"
			);
		}

		/** The current histogram reader preserves millisecond thresholds without a migration marker. */
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
				decoded.isSecondGranularityRangePages(),
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

		/** Real positive and negative seconds multiply exactly; legacy sentinels map to constant open borders. */
		@Test
		@DisplayName("a real second threshold is multiplied, a legacy open sentinel becomes the constant one")
		void shouldSeparateRealThresholdsFromLegacySentinels() {
			// the separation has four orders of magnitude of margin on each side: no date expressible as a scalar
			// temporal attribute exceeds ~1e11 seconds, the window edge sits at ~9.22e15, and the legacy open-bound
			// sentinels are ~3.16e16
			assertEquals(
				1_779_279_566_000L, LegacyRangeIndexConversion.rescaleSecondGranularityThreshold(1_779_279_566L),
				"a real moment is multiplied by exactly a thousand"
			);
			assertEquals(
				-1_779_279_566_000L, LegacyRangeIndexConversion.rescaleSecondGranularityThreshold(-1_779_279_566L),
				"and so is a pre-epoch one"
			);
			assertEquals(
				DateTimeRange.OPEN_TO_THRESHOLD,
				LegacyRangeIndexConversion.rescaleSecondGranularityThreshold(
					LocalDateTime.MAX.atOffset(ZoneOffset.ofHours(-18)).toEpochSecond()
				),
				"the legacy open-to sentinel at the extreme offset must land on the constant"
			);
			assertEquals(
				DateTimeRange.OPEN_FROM_THRESHOLD,
				LegacyRangeIndexConversion.rescaleSecondGranularityThreshold(
					LocalDateTime.MIN.atOffset(ZoneOffset.ofHours(18)).toEpochSecond()
				),
				"and so must the legacy open-from sentinel at the other extreme"
			);
			assertEquals(
				Long.MIN_VALUE, LegacyRangeIndexConversion.rescaleSecondGranularityThreshold(Long.MIN_VALUE),
				"the index's own lower border point maps onto itself"
			);
			assertEquals(
				Long.MAX_VALUE, LegacyRangeIndexConversion.rescaleSecondGranularityThreshold(Long.MAX_VALUE),
				"and so does the upper one"
			);
		}

		/** Classification at both representable edges distinguishes the last real seconds from open bounds. */
		@Test
		@DisplayName("the classification boundary itself decides between multiplying and the open-bound constant")
		void shouldClassifyTheRepresentableWindowEdgesExactly()  {
			// the sibling above probes a real moment at ~1.7e9 s and the legacy sentinels at ~3.16e16 s - four orders
			// of magnitude from the cut on either side, so a `<` / `<=` slip or a wrong constant is invisible there.
			// These four assertions sit ON the cut and one step past it
			assertEquals(
				DateTimeRange.MIN_REPRESENTABLE_EPOCH_SECOND * 1000L,
				LegacyRangeIndexConversion.rescaleSecondGranularityThreshold(
					DateTimeRange.MIN_REPRESENTABLE_EPOCH_SECOND
				),
				"the lowest representable second is still a real moment and must be multiplied"
			);
			assertEquals(
				DateTimeRange.OPEN_FROM_THRESHOLD,
				LegacyRangeIndexConversion.rescaleSecondGranularityThreshold(
					DateTimeRange.MIN_REPRESENTABLE_EPOCH_SECOND - 1L
				),
				"one second below it is an open-from bound"
			);
			assertEquals(
				DateTimeRange.MAX_REPRESENTABLE_EPOCH_SECOND * 1000L,
				LegacyRangeIndexConversion.rescaleSecondGranularityThreshold(
					DateTimeRange.MAX_REPRESENTABLE_EPOCH_SECOND
				),
				"the highest representable second is still a real moment and must be multiplied"
			);
			assertEquals(
				DateTimeRange.OPEN_TO_THRESHOLD,
				LegacyRangeIndexConversion.rescaleSecondGranularityThreshold(
					DateTimeRange.MAX_REPRESENTABLE_EPOCH_SECOND + 1L
				),
				"one second above it is an open-to bound"
			);
			// and the multiplied edges really do stay inside the window rather than overflowing into the sentinels,
			// which is the property the two constants are derived to guarantee
			assertTrue(
				DateTimeRange.MIN_REPRESENTABLE_EPOCH_SECOND * 1000L > DateTimeRange.OPEN_FROM_THRESHOLD,
				"the multiplied lower edge must stay above the open-from constant"
			);
			assertTrue(
				DateTimeRange.MAX_REPRESENTABLE_EPOCH_SECOND * 1000L + 999L < DateTimeRange.OPEN_TO_THRESHOLD,
				"the multiplied upper edge plus its largest millisecond remainder must stay below the open-to constant"
			);
		}

		/** Legacy offset-dependent sentinels collapse into one point containing both records. */
		@Test
		@DisplayName("points that collide after the rescale are merged rather than duplicated")
		void shouldMergeCollidingPointsOnRescale() {
			// two open-ended ranges written at DIFFERENT zone offsets held two distinct upper thresholds, and the
			// index's own border point held a third. All three land on `Long.MAX_VALUE` now, and a range index
			// cannot hold one threshold twice - so the repair has to union their record sets
			final long legacyOpenAtUtc =
				LocalDateTime.MAX.atOffset(ZoneOffset.UTC).toEpochSecond();
			final long legacyOpenAtPlusTwo =
				LocalDateTime.MAX.atOffset(ZoneOffset.ofHours(2)).toEpochSecond();
			// the sentinel written at +02:00 sorts BELOW the one written at UTC, because the same local instant is an
			// earlier moment there - which is exactly the offset dependence the constant sentinels removed
			assertTrue(
				legacyOpenAtPlusTwo < legacyOpenAtUtc, "the two legacy sentinels must really differ, and in this order"
			);

			final RangeIndex rescaled = LegacyRangeIndexConversion.rescaledFromSecondGranularity(
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

	}
}
