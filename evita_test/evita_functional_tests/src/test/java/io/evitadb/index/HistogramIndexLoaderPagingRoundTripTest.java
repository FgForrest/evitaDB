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

package io.evitadb.index;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.Scope;
import io.evitadb.function.Functions;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.component.EntityIndexManifest;
import io.evitadb.index.component.HistogramIndexMapComponent;
import io.evitadb.index.component.loader.HistogramIndexMapLoader;
import io.evitadb.index.component.loader.LoadContext;
import io.evitadb.index.component.loader.LoadedComponentBundle;
import io.evitadb.index.component.loader.LoadedComponentBundle.Histograms;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.index.range.TransactionalRangePoint;
import io.evitadb.spi.store.catalog.persistence.StorageDescriptor;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.KeyCompressorSnapshot;
import io.evitadb.spi.store.catalog.persistence.storageParts.DeferredRemovalStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramCardinalityStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramCardinalityStoragePartRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramLeafStreamKey.StreamKind;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HistogramRangeIndexLeafPagePart;
import io.evitadb.store.catalog.CatalogHeaderKryoConfigurer;
import io.evitadb.store.index.IndexStoragePartConfigurer;
import io.evitadb.store.index.SharedIndexStoragePartConfigurer;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.offsetIndex.OffsetIndex;
import io.evitadb.store.offsetIndex.OffsetIndex.NonFlushedBlock;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.store.offsetIndex.OffsetIndexSerializationService.FileLocationAndWrittenBytes;
import io.evitadb.store.offsetIndex.io.WriteOnlyFileHandle;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.schema.SchemaKryoConfigurer;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.store.shared.kryo.SharedClassesConfigurer;
import io.evitadb.store.shared.kryo.VersionedKryoFactory;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end reload of a granular (`PAGED`) {@link HistogramIndex} through the REAL production loader path: a
 * high-cardinality histogram is paged out as individual {@link HistogramIndexLeafPagePart} bucket leaf pages plus a
 * sibling {@link HistogramCardinalityStoragePart}, written to a real on-disk {@link OffsetIndex} (Kryo serialization +
 * the byte-44/46 record types + a real {@code KeyCompressor}), reloaded, and reconstructed by invoking
 * {@link HistogramIndexMapLoader#load(LoadContext)} — which drives the `PAGED` bucket / range branches and the
 * cardinality-sibling fetch for real. The suite covers the non-localized bucket-only histogram, the per-locale
 * independent page streams of a localized histogram, the two-axis (bucket + range) paging of a range-typed histogram,
 * and the granular flush-emission dirty gate (cardinality-only, single-leaf, and `PAGED -> SINGLE` collapse commits).
 * Mirrors {@code ChainIndexLoaderPagingRoundTripTest} and {@code ReferenceTypeCardinalityIndexPagingRoundTripTest}.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(SERIALIZATION)
@Tag(HISTOGRAM)
@DisplayName("Histogram index granular paging reloads through the real HistogramIndexMapLoader + OffsetIndex")
class HistogramIndexLoaderPagingRoundTripTest implements EvitaTestSupport {
	private static final String ENTITY_TYPE = "product";
	private static final String REFERENCE_NAME = "categories";
	private static final String HISTOGRAM_NAME = "price";
	/** A second, independently-paged histogram name used to prove reclaim never crosses histogram-name boundaries. */
	private static final String SECOND_HISTOGRAM_NAME = "width";
	private static final int ENTITY_INDEX_PK = 7;
	private static final EntityIndexKey ENTITY_INDEX_KEY =
		new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_NAME);
	/** > 1 leaf page (bucket leaf capacity) so the histogram pages out across several leaves. */
	private static final int VALUE_COUNT = 3200;
	/** The scale a `BigDecimalNumberRange` histogram freezes its bounds at, and rebuilds them at on reload. */
	private static final int BIG_DECIMAL_SCALE = 2;
	/** Survivors kept after the `PAGED -> SINGLE` collapse; a handful of buckets fit inside a single bucket leaf. */
	private static final int COLLAPSE_KEEP = 12;
	private static final long PERSISTED_VERSION = 1L;
	/** The catalog version the second (post-mutation) flush is applied and reopened at. */
	private static final long SECOND_VERSION = 2L;
	private static final Locale CZECH = Locale.forLanguageTag("cs");
	/** Manifest keys advertising a single non-localized histogram sub-index. */
	private static final Set<HistogramIndexStorageKey> SIMPLE_MANIFEST_KEYS = Set.of(
		new HistogramIndexStorageKey(ENTITY_INDEX_KEY, HISTOGRAM_NAME, null)
	);
	private static final Consumer<NonFlushedBlock> NO_OP_NON_FLUSHED_BLOCK_CALLBACK = Functions.noOpConsumer();
	private static final Consumer<Optional<OffsetDateTime>> NO_OP_OLDEST_RECORD_CALLBACK = Functions.noOpConsumer();

	private final OffsetIndexRecordTypeRegistry recordRegistry = new OffsetIndexRecordTypeRegistry();
	private final StorageSettings storageSettings = new StorageSettings(
		StorageOptions.temporary(), TransactionOptions.builder().build()
	);
	private ObservableOutputKeeper observableOutputKeeper;
	private Path targetFile;

	@BeforeEach
	void setUp() throws Exception {
		this.observableOutputKeeper = ObservableOutputKeeper._internalBuild(Mockito.mock(Scheduler.class));
		this.targetFile = Files.createTempFile("histogramIndexLoaderPagingRoundTrip", ".kryo");
	}

	@AfterEach
	void tearDown() {
		this.observableOutputKeeper.close();
		this.targetFile.toFile().delete();
	}

	@Nested
	@DisplayName("Non-localized bucket paging")
	class BucketPagingRoundTrip {

		@Test
		@DisplayName("a high-cardinality PAGED histogram reloads identically through the real loader page-read path")
		void shouldReloadPagedHistogramThroughTheRealLoader() {
			final SimpleHistogramIndex source = new SimpleHistogramIndex(
				HISTOGRAM_NAME, REFERENCE_NAME, Integer.class, 0
			);
			// one distinct value per owner → one bucket per value, large enough to page the bucket tree out
			for (int i = 1; i <= VALUE_COUNT; i++) {
				source.insertValue(null, i, i);
			}

			final List<StoragePart> emitted = emit(source);
			final HistogramIndexStoragePart root = histogramRoot(emitted);
			assertTrue(root.isPaged(), "the source histogram must page its bucket axis out");
			assertTrue(leafPages(emitted).size() >= 3, "a paged histogram must emit at least three bucket leaf pages");
			assertNotNull(cardinalitySibling(emitted), "the cardinality index must be evicted to a sibling part");

			final OffsetIndexDescriptor descriptor = persist(stripRemovals(emitted));
			OffsetIndex reloaded = null;
			try {
				reloaded = loadOffsetIndex(descriptor, PERSISTED_VERSION);
				final HistogramIndex restored = reloadThroughLoader(
					reloaded, SIMPLE_MANIFEST_KEYS, PERSISTED_VERSION, HISTOGRAM_NAME
				);
				assertReloadIdentical(source, restored, null);

				// a clean reload leaves the index non-dirty: the first post-load flush must emit nothing
				assertEquals(
					0, emit(restored).size(),
					"an untouched reloaded paged histogram must emit nothing on its first flush"
				);
			} finally {
				if (reloaded != null) {
					IOUtils.closeQuietly(reloaded::close);
				}
			}
		}
	}

	@Nested
	@DisplayName("Localized per-locale paging")
	class LocalizedPagingRoundTrip {

		@Test
		@DisplayName("a localized PAGED histogram reloads each locale's independent page stream identically")
		void shouldReloadPagedLocalizedHistogramWithIndependentPerLocaleStreams() {
			final LocalizedHistogramIndex source = new LocalizedHistogramIndex(
				HISTOGRAM_NAME, REFERENCE_NAME, Integer.class, 0
			);
			// page each locale's bucket axis independently: the same value/owner grid under two distinct locales
			for (int i = 1; i <= VALUE_COUNT; i++) {
				source.insertValue(Locale.ENGLISH, i, i);
				source.insertValue(CZECH, i, i);
			}

			final List<StoragePart> emitted = emit(source);

			// two paged roots (one per locale) + two cardinality siblings
			final List<HistogramIndexStoragePart> roots = histogramRoots(emitted);
			assertEquals(2, roots.size(), "a two-locale histogram must emit one root per locale");
			for (final HistogramIndexStoragePart root : roots) {
				assertTrue(root.isPaged(), "each locale's bucket axis must page out");
			}
			assertEquals(
				Set.of(Locale.ENGLISH, CZECH), localesOf(roots),
				"the two roots must belong to the two distinct locales"
			);
			assertEquals(2, cardinalitySiblings(emitted).size(), "each locale must evict its own cardinality sibling");

			// leaf pages must be partitioned across the two distinct per-locale stream keys
			final List<HistogramIndexLeafPagePart> leafPages = leafPages(emitted);
			assertTrue(
				leafPagesOfLocale(leafPages, Locale.ENGLISH).size() >= 3,
				"the English bucket axis must page out across at least three leaves"
			);
			assertTrue(
				leafPagesOfLocale(leafPages, CZECH).size() >= 3,
				"the Czech bucket axis must page out across at least three leaves"
			);

			final Set<HistogramIndexStorageKey> manifestKeys = Set.of(
				new HistogramIndexStorageKey(ENTITY_INDEX_KEY, HISTOGRAM_NAME, Locale.ENGLISH),
				new HistogramIndexStorageKey(ENTITY_INDEX_KEY, HISTOGRAM_NAME, CZECH)
			);
			final OffsetIndexDescriptor descriptor = persist(stripRemovals(emitted));
			OffsetIndex reloaded = null;
			try {
				reloaded = loadOffsetIndex(descriptor, PERSISTED_VERSION);
				final HistogramIndex restored = reloadThroughLoader(
					reloaded, manifestKeys, PERSISTED_VERSION, HISTOGRAM_NAME
				);
				assertInstanceOf(
					LocalizedHistogramIndex.class, restored,
					"a histogram with only non-null-locale parts must reload as a LocalizedHistogramIndex"
				);
				assertReloadIdentical(source, restored, Locale.ENGLISH);
				assertReloadIdentical(source, restored, CZECH);

				assertEquals(
					0, emit(restored).size(),
					"an untouched reloaded localized paged histogram must emit nothing on its first flush"
				);
			} finally {
				if (reloaded != null) {
					IOUtils.closeQuietly(reloaded::close);
				}
			}
		}
	}

	@Nested
	@DisplayName("Range-typed two-axis paging")
	class RangeTypedPagingRoundTrip {

		@Test
		@DisplayName("a range-typed PAGED histogram reloads both its bucket and its range axis through the real loader")
		void shouldReloadPagedRangeTypedHistogramThroughTheRealLoader() {
			final SimpleHistogramIndex source = new SimpleHistogramIndex(
				HISTOGRAM_NAME, REFERENCE_NAME, IntegerNumberRange.class, 0
			);
			// one distinct range per owner: the range value pages the bucket axis, its two thresholds page the range axis
			for (int i = 1; i <= VALUE_COUNT; i++) {
				source.insertValue(null, IntegerNumberRange.between(i, i + 1), i);
			}

			final List<StoragePart> emitted = emit(source);
			final HistogramIndexStoragePart root = histogramRoot(emitted);
			assertTrue(root.isPaged(), "the range-typed histogram must page its bucket axis out");
			assertTrue(root.isRangePaged(), "the range-typed histogram must page its range axis out");
			assertTrue(leafPages(emitted).size() >= 3, "the bucket axis must emit at least three leaf pages");
			assertTrue(
				rangeLeafPages(emitted).size() >= 3, "the range axis must emit at least three range leaf pages"
			);

			final OffsetIndexDescriptor descriptor = persist(stripRemovals(emitted));
			OffsetIndex reloaded = null;
			try {
				reloaded = loadOffsetIndex(descriptor, PERSISTED_VERSION);
				final HistogramIndex restored = reloadThroughLoader(
					reloaded, SIMPLE_MANIFEST_KEYS, PERSISTED_VERSION, HISTOGRAM_NAME
				);
				assertReloadIdentical(source, restored, null);
				assertSameRangeQueries(source, restored);

				assertEquals(
					0, emit(restored).size(),
					"an untouched reloaded range-typed paged histogram must emit nothing on its first flush"
				);
			} finally {
				if (reloaded != null) {
					IOUtils.closeQuietly(reloaded::close);
				}
			}
		}

		@Test
		@DisplayName("a date-time-range PAGED histogram reloads its bounds through the loader without instant drift")
		void shouldReloadPagedDateTimeRangeTypedHistogramThroughTheRealLoader() {
			// the sibling above pages an `IntegerNumberRange`, whose bounds are the caller's own numbers. A
			// `DateTimeRange`'s comparison longs are DERIVED - whole epoch milliseconds - and every bucket in this
			// fixture is built at a different zone offset, so a reload that mishandled the derivation would move the
			// instants without moving anything the `equals`-based parity assertions can see
			final SimpleHistogramIndex source = new SimpleHistogramIndex(
				HISTOGRAM_NAME, REFERENCE_NAME, DateTimeRange.class, 0
			);
			for (int i = 1; i <= VALUE_COUNT; i++) {
				source.insertValue(null, dateTimeRange(i), i);
			}

			final List<StoragePart> emitted = emit(source);
			final HistogramIndexStoragePart root = histogramRoot(emitted);
			assertTrue(root.isPaged(), "the date-time-range histogram must page its bucket axis out");
			assertTrue(root.isRangePaged(), "the date-time-range histogram must page its range axis out");
			assertTrue(leafPages(emitted).size() >= 3, "the bucket axis must emit at least three leaf pages");
			assertTrue(
				rangeLeafPages(emitted).size() >= 3, "the range axis must emit at least three range leaf pages"
			);

			final OffsetIndexDescriptor descriptor = persist(stripRemovals(emitted));
			OffsetIndex reloaded = null;
			try {
				reloaded = loadOffsetIndex(descriptor, PERSISTED_VERSION);
				final HistogramIndex restored = reloadThroughLoader(
					reloaded, SIMPLE_MANIFEST_KEYS, PERSISTED_VERSION, HISTOGRAM_NAME
				);
				assertReloadIdentical(source, restored, null);
				assertSameRangeQueries(
					source, restored,
					new long[][]{
						{dateTimeRange(1).getFrom(), dateTimeRange(5).getTo()},
						{dateTimeRange(1600).getFrom(), dateTimeRange(1600).getTo()},
						{dateTimeRange(VALUE_COUNT).getFrom(), dateTimeRange(VALUE_COUNT).getTo()},
						{dateTimeRange(1).getFrom() - 3600L, dateTimeRange(VALUE_COUNT).getTo() + 3600L}
					}
				);

				final FilterIndex restoredFilter = restored.getFilterIndex(null);
				assertNotNull(restoredFilter, "the reloaded histogram must expose a filter index");
				final ValueToRecordBitmap[] restoredBuckets =
					restoredFilter.getInvertedIndex().getValueToRecordBitmap();
				assertEquals(
					VALUE_COUNT, restoredBuckets.length, "every distinct range must reload as its own bucket"
				);
				// `assertReloadIdentical` compares bucket values with `equals`, which `DateTimeRange` generates from
				// its two comparison longs alone - so it cannot see WHICH instants a bucket came back as, only that
				// the two indexes agree. `toString` renders both bounds as ISO_OFFSET_DATE_TIME and does, and the
				// buckets ascend by (from, to), which for this domain is ordinal order - so the source ranges,
				// re-rendered at UTC, are an independent oracle for the instants themselves. The offsets they were
				// WRITTEN at differ per ordinal and are deliberately not preserved; what must survive is the moment
				for (int i = 0; i < restoredBuckets.length; i++) {
					assertEquals(
						atUtc(dateTimeRange(i + 1)).toString(), restoredBuckets[i].getValue().toString(),
						"bucket " + i + " must reload naming the same two instants it was written with"
					);
				}

				assertEquals(
					0, emit(restored).size(),
					"an untouched reloaded date-time-range paged histogram must emit nothing on its first flush"
				);
			} finally {
				if (reloaded != null) {
					IOUtils.closeQuietly(reloaded::close);
				}
			}
		}

		@Test
		@DisplayName("a big-decimal-range PAGED histogram reloads its bounds at the frozen index scale")
		void shouldReloadPagedBigDecimalRangeTypedHistogramThroughTheRealLoader() {
			// the reload threads the persisted `indexedDecimalPlaces` into every leaf column it rebuilds, and a
			// range rebuilt at the wrong scale is still `equals` to the right one: the comparison longs, the
			// ordering and the range-index thresholds are all scale-invariant. Only the precise bounds move, and
			// this is the only test in the suite that reaches them through the paged reload
			final SimpleHistogramIndex source = new SimpleHistogramIndex(
				HISTOGRAM_NAME, REFERENCE_NAME, BigDecimalNumberRange.class, BIG_DECIMAL_SCALE
			);
			for (int i = 1; i <= VALUE_COUNT; i++) {
				source.insertValue(null, bigDecimalRange(i), i);
			}

			final List<StoragePart> emitted = emit(source);
			final HistogramIndexStoragePart root = histogramRoot(emitted);
			assertTrue(root.isPaged(), "the big-decimal-range histogram must page its bucket axis out");
			assertTrue(root.isRangePaged(), "the big-decimal-range histogram must page its range axis out");
			assertTrue(leafPages(emitted).size() >= 3, "the bucket axis must emit at least three leaf pages");
			assertTrue(
				rangeLeafPages(emitted).size() >= 3, "the range axis must emit at least three range leaf pages"
			);

			final OffsetIndexDescriptor descriptor = persist(stripRemovals(emitted));
			OffsetIndex reloaded = null;
			try {
				reloaded = loadOffsetIndex(descriptor, PERSISTED_VERSION);
				final HistogramIndex restored = reloadThroughLoader(
					reloaded, SIMPLE_MANIFEST_KEYS, PERSISTED_VERSION, HISTOGRAM_NAME
				);
				assertReloadIdentical(source, restored, null);
				// a big-decimal threshold IS the scaled long, so the probes are expressed as the longs the bounds
				// encode to rather than as the decimal values a caller would write
				assertSameRangeQueries(
					source, restored,
					new long[][]{
						{bigDecimalRange(1).getFrom(), bigDecimalRange(5).getTo()},
						{bigDecimalRange(1600).getFrom(), bigDecimalRange(1600).getTo()},
						{bigDecimalRange(VALUE_COUNT).getFrom(), bigDecimalRange(VALUE_COUNT).getTo()},
						{bigDecimalRange(1).getFrom() - 100L, bigDecimalRange(VALUE_COUNT).getTo() + 100L}
					}
				);

				// the scale-level pass, the analogue of the date-time sibling's offset pass: `toString` renders the
				// PRECISE bounds, and `BigDecimal`'s rendering carries its scale - so a bucket reloaded at scale 0
				// renders "[105,180]" where the source renders "[1.05,1.80]", which nothing above can see
				final FilterIndex restoredFilter = restored.getFilterIndex(null);
				assertNotNull(restoredFilter, "the reloaded histogram must expose a filter index");
				final ValueToRecordBitmap[] restoredBuckets =
					restoredFilter.getInvertedIndex().getValueToRecordBitmap();
				assertEquals(
					VALUE_COUNT, restoredBuckets.length, "every distinct range must reload as its own bucket"
				);
				for (int i = 0; i < restoredBuckets.length; i++) {
					assertEquals(
						bigDecimalRange(i + 1).toString(), restoredBuckets[i].getValue().toString(),
						"bucket " + i + " must reload at the frozen index scale"
					);
				}

				assertEquals(
					0, emit(restored).size(),
					"an untouched reloaded big-decimal-range paged histogram must emit nothing on its first flush"
				);
			} finally {
				if (reloaded != null) {
					IOUtils.closeQuietly(reloaded::close);
				}
			}
		}
	}

	/**
	 * The legacy threshold-scale repair, driven through the histogram loader.
	 *
	 * Every catalog format written before {@code DateTimeRange} moved from second to millisecond comparison
	 * granularity persisted its range-index thresholds as epoch **seconds**. A threshold is an untyped `long` shared
	 * with all five `NumberRange` subtypes, whose thresholds are the bounds' own numeric values and were never
	 * rescaled — so the repair is routed on the declared value type, and applying it to the wrong one inflates a
	 * numeric range's bounds by a thousand and answers every query over it with the wrong records, silently.
	 *
	 * `HistogramIndexMapLoader` is the twin of `AttributeIndexLoader`'s filter-index repair and carries the same two
	 * branches — the inline range companion and the range-`PAGED` axis — which `AttributeIndexLoaderTest`'s legacy
	 * nest covers for the attribute side. These drive the histogram side through the real store: the legacy-scaled
	 * thresholds are really written and read back, and only the read-path provenance mark, which no current writer
	 * can persist, is stamped by the read service (see {@link OffsetIndexReadService}).
	 */
	@Nested
	@DisplayName("Legacy second-granularity range thresholds")
	class LegacyRangeThresholdScale {
		/** The moment the seeded validity opens at; the fixture is five days wide. */
		private static final OffsetDateTime LEGACY_VALID_FROM = OffsetDateTime.parse("2026-05-20T12:19:26Z");
		/** A moment inside the seeded validity, used as the positive query probe. */
		private static final OffsetDateTime LEGACY_INSIDE = OffsetDateTime.parse("2026-05-21T00:00:00Z");
		/** A moment after the seeded validity, used as the negative query probe. */
		private static final OffsetDateTime LEGACY_OUTSIDE = OffsetDateTime.parse("2026-06-01T00:00:00Z");
		/** Range points per persisted leaf page, so the fixture really spans several of them. */
		private static final int LEGACY_RANGE_PAGE_SIZE = 64;
		/**
		 * How many one-record validity ranges the paged fixture holds. Two thresholds each plus the two border
		 * sentinels puts the rescaled point count comfortably above the range tree's leaf block size, so the
		 * repaired index pages again rather than collapsing to an inline companion — which is what makes the
		 * root-plus-every-page assertion below say anything.
		 */
		private static final int LEGACY_PAGED_RECORDS = 300;
		/** Seconds between the lower bounds of two consecutive ranges in the paged fixture. */
		private static final int LEGACY_RANGE_STRIDE_SECONDS = 3_600;
		/** Width, in seconds, of each range in the paged fixture — narrower than the stride, so they never overlap. */
		private static final int LEGACY_RANGE_WIDTH_SECONDS = 1_800;

		@Test
		@DisplayName("a range-PAGED second-granularity histogram is rescaled across its leaf pages")
		void shouldRescaleARangePagedSecondGranularityDateTimeHistogram() {
			final ValueToRecordBitmap[] buckets = new ValueToRecordBitmap[LEGACY_PAGED_RECORDS];
			for (int record = 1; record <= LEGACY_PAGED_RECORDS; record++) {
				buckets[record - 1] = new ValueToRecordBitmap(pagedValidity(record), record);
			}
			final List<StoragePart> seeded = legacyRangePagedParts(
				DateTimeRange.class, buckets, pagedSecondGranularityPoints()
			);
			final int seededPageCount = rangeLeafPages(seeded).size();
			assertTrue(seededPageCount > 1, "the fixture must span several persisted range leaf pages");

			final OffsetIndexDescriptor descriptor = persist(seeded);
			OffsetIndex reloaded = null;
			try {
				reloaded = loadOffsetIndex(descriptor, PERSISTED_VERSION);
				final HistogramIndex restored = reloadThroughLoader(
					reloaded, SIMPLE_MANIFEST_KEYS, PERSISTED_VERSION, HISTOGRAM_NAME, true
				);
				final FilterIndex filter = restored.getFilterIndex(null);
				assertNotNull(filter, "the reloaded histogram must expose a filter index");

				// THE assertion: a probe reduced by the CURRENT converter must find the record. Without the rescale
				// the stored thresholds sit a thousand times below it and this returns nothing
				final DateTimeRange first = pagedValidity(1);
				final DateTimeRange last = pagedValidity(LEGACY_PAGED_RECORDS);
				assertArrayEquals(
					new int[]{1},
					filter.getRecordsValidInFormula(first.getFrom() + 1_000L).compute().getArray(),
					"a moment inside the first reloaded paged validity must select its record"
				);
				assertArrayEquals(
					new int[]{LEGACY_PAGED_RECORDS},
					filter.getRecordsValidInFormula(last.getFrom() + 1_000L).compute().getArray(),
					"and so must a moment inside the last one, which sits on another leaf page"
				);
				assertArrayEquals(
					new int[0],
					filter.getRecordsValidInFormula(first.getTo() + 1_000L).compute().getArray(),
					"a moment in the gap between two validities must select nothing"
				);
				// a probe left in the OLD scale must now miss, which is what says the thresholds really moved
				assertArrayEquals(
					new int[0],
					filter.getRecordsValidInFormula(first.getFrom() / 1_000L).compute().getArray(),
					"a second-granularity probe must no longer land inside the rescaled paged range"
				);

				// the repair is PASSIVE: it costs one rescale per load and rewrites nothing on its own, so a reload
				// that is never followed by a write leaves the persisted legacy form untouched
				final List<StoragePart> afterReload = emit(restored);
				assertTrue(
					rangeLeafPages(afterReload).isEmpty() && leafPageRemovals(afterReload).isEmpty(),
					"a reload alone must not rewrite or free a single range leaf page"
				);

				// ... but the rescaled index is deliberately NOT boundary-stable, and the whole atomicity argument
				// rests on the first flush that DOES touch it moving the root and every leaf page together: the
				// persisted scale is carried by the ROOT record, so a commit rewriting pages while leaving the
				// legacy root behind would produce a catalog whose scale marker disagrees with its content
				restored.insertValue(null, pagedValidity(LEGACY_PAGED_RECORDS + 1), LEGACY_PAGED_RECORDS + 1);
				final List<StoragePart> firstFlush = emit(restored);
				final HistogramIndexStoragePart rewrittenRoot = histogramRoot(firstFlush);
				assertTrue(
					rewrittenRoot.isRangePaged(),
					"the repaired index must still page its range axis, or the assertions below say nothing"
				);
				assertEquals(
					seededPageCount, leafPageRemovals(firstFlush).size(),
					"every legacy range leaf page must be freed by the same commit that re-emits the root"
				);
				assertEquals(
					rewrittenRoot.getRangeLeafPageSequences().length, rangeLeafPages(firstFlush).size(),
					"every live range leaf page must be re-emitted alongside the root"
				);
			} finally {
				if (reloaded != null) {
					IOUtils.closeQuietly(reloaded::close);
				}
			}
		}

		@Test
		@DisplayName("an inline second-granularity histogram is rescaled and answers queries at the right moments")
		void shouldRescaleAnInlineSecondGranularityDateTimeHistogram() {
			final DateTimeRange validity = DateTimeRange.between(LEGACY_VALID_FROM, LEGACY_VALID_FROM.plusDays(5));
			final HistogramIndexStoragePart root = legacyInlineRoot(
				DateTimeRange.class,
				new ValueToRecordBitmap[]{new ValueToRecordBitmap(validity, 1)},
				secondGranularityPoints(
					LEGACY_VALID_FROM.toEpochSecond(), LEGACY_VALID_FROM.plusDays(5).toEpochSecond(), 1
				)
			);

			final OffsetIndexDescriptor descriptor = persist(List.<StoragePart>of(root));
			OffsetIndex reloaded = null;
			try {
				reloaded = loadOffsetIndex(descriptor, PERSISTED_VERSION);
				final FilterIndex filter = reloadThroughLoader(
					reloaded, SIMPLE_MANIFEST_KEYS, PERSISTED_VERSION, HISTOGRAM_NAME, true
				).getFilterIndex(null);
				assertNotNull(filter, "the reloaded histogram must expose a filter index");

				assertArrayEquals(
					new int[]{1},
					filter.getRecordsValidInFormula(DateTimeRange.toComparableLong(LEGACY_INSIDE))
						.compute().getArray(),
					"a moment inside the reloaded validity must select the record"
				);
				assertArrayEquals(
					new int[0],
					filter.getRecordsValidInFormula(DateTimeRange.toComparableLong(LEGACY_OUTSIDE))
						.compute().getArray(),
					"a moment after the reloaded validity must select nothing"
				);
				assertArrayEquals(
					new int[0],
					filter.getRecordsValidInFormula(LEGACY_INSIDE.toEpochSecond()).compute().getArray(),
					"a second-granularity probe must no longer land inside the rescaled range"
				);
			} finally {
				if (reloaded != null) {
					IOUtils.closeQuietly(reloaded::close);
				}
			}
		}

		@Test
		@DisplayName("an open-ended second-granularity histogram lands on the constant sentinel, points merged")
		void shouldRescaleAnOpenEndedSecondGranularityDateTimeHistogram() {
			// a pre-change open-ended range ended on `LocalDateTime.MAX.atOffset(from.getOffset()).toEpochSecond()`,
			// which collides with the index's own `Long.MAX_VALUE` border point once rescaled - the collision the
			// repair has to MERGE rather than duplicate, since a range index cannot hold one threshold twice
			final DateTimeRange openEnded = DateTimeRange.since(LEGACY_VALID_FROM);
			final long legacyOpenTo = LocalDateTime.MAX.atOffset(ZoneOffset.UTC).toEpochSecond();
			final HistogramIndexStoragePart root = legacyInlineRoot(
				DateTimeRange.class,
				new ValueToRecordBitmap[]{new ValueToRecordBitmap(openEnded, 1)},
				secondGranularityPoints(LEGACY_VALID_FROM.toEpochSecond(), legacyOpenTo, 1)
			);

			final OffsetIndexDescriptor descriptor = persist(List.<StoragePart>of(root));
			OffsetIndex reloaded = null;
			try {
				reloaded = loadOffsetIndex(descriptor, PERSISTED_VERSION);
				final FilterIndex filter = reloadThroughLoader(
					reloaded, SIMPLE_MANIFEST_KEYS, PERSISTED_VERSION, HISTOGRAM_NAME, true
				).getFilterIndex(null);
				assertNotNull(filter, "the reloaded histogram must expose a filter index");

				assertArrayEquals(
					new int[]{1},
					filter.getRecordsValidInFormula(DateTimeRange.toComparableLong(LEGACY_INSIDE))
						.compute().getArray(),
					"a moment inside the open-ended validity must select the record"
				);
				assertArrayEquals(
					new int[]{1},
					filter.getRecordsValidInFormula(DateTimeRange.toComparableLong(LEGACY_OUTSIDE))
						.compute().getArray(),
					"a moment far after the lower bound must still select an open-ended record"
				);
				assertArrayEquals(
					new int[0],
					filter.getRecordsValidInFormula(
						DateTimeRange.toComparableLong(LEGACY_VALID_FROM.minusDays(1))
					).compute().getArray(),
					"a moment before the lower bound must select nothing"
				);

				// the legacy sentinel and the border point really did merge onto one threshold rather than
				// duplicating it - four persisted points, three after the repair
				final RangeIndex reloadedRange = filter.getRangeIndex();
				assertNotNull(reloadedRange, "the range companion must reload");
				assertEquals(3, reloadedRange.getRanges().length, "the colliding points must have merged");
				assertEquals(
					DateTimeRange.OPEN_TO_THRESHOLD,
					reloadedRange.getRanges()[reloadedRange.getRanges().length - 1].getThreshold(),
					"the legacy open sentinel and the border point must share the constant threshold"
				);
			} finally {
				if (reloaded != null) {
					IOUtils.closeQuietly(reloaded::close);
				}
			}
		}

		@Test
		@DisplayName("a numeric-range histogram read from the same legacy format keeps its thresholds untouched")
		void shouldLeaveANumericRangeHistogramUntouched() {
			// the control, and the one that matters most: the provenance mark is set on this part exactly as it is
			// on the three above, so ONLY the declared value type keeps the rescale away from it. An
			// `IntegerNumberRange` threshold is the caller's own number and was never in seconds
			final IntegerNumberRange quantity = IntegerNumberRange.between(10, 20);
			final HistogramIndexStoragePart root = legacyInlineRoot(
				IntegerNumberRange.class,
				new ValueToRecordBitmap[]{new ValueToRecordBitmap(quantity, 1)},
				secondGranularityPoints(10L, 20L, 1)
			);

			final OffsetIndexDescriptor descriptor = persist(List.<StoragePart>of(root));
			OffsetIndex reloaded = null;
			try {
				reloaded = loadOffsetIndex(descriptor, PERSISTED_VERSION);
				final FilterIndex filter = reloadThroughLoader(
					reloaded, SIMPLE_MANIFEST_KEYS, PERSISTED_VERSION, HISTOGRAM_NAME, true
				).getFilterIndex(null);
				assertNotNull(filter, "the reloaded histogram must expose a filter index");

				assertArrayEquals(
					new int[]{1}, filter.getRecordsValidInFormula(15L).compute().getArray(),
					"the numeric thresholds must be read back verbatim - 15 lies inside [10, 20]"
				);
				assertArrayEquals(
					new int[0], filter.getRecordsValidInFormula(15_000L).compute().getArray(),
					"a rescaled numeric index would have moved [10, 20] to [10000, 20000] and matched here instead"
				);
				final RangeIndex reloadedRange = filter.getRangeIndex();
				assertNotNull(reloadedRange, "the range companion must reload");
				assertEquals(10L, reloadedRange.getRanges()[1].getThreshold(), "the lower bound stays 10");
				assertEquals(20L, reloadedRange.getRanges()[2].getThreshold(), "the upper bound stays 20");
			} finally {
				if (reloaded != null) {
					IOUtils.closeQuietly(reloaded::close);
				}
			}
		}

		/**
		 * The `ordinal`-th validity of the paged fixture: {@value #LEGACY_RANGE_WIDTH_SECONDS} seconds wide and
		 * {@value #LEGACY_RANGE_STRIDE_SECONDS} seconds after its predecessor, so the ranges ascend and never touch.
		 *
		 * @param ordinal the one-based ordinal, which is also the record id the range belongs to
		 * @return the validity range
		 */
		@Nonnull
		private DateTimeRange pagedValidity(int ordinal) {
			final OffsetDateTime from = LEGACY_VALID_FROM.plusSeconds((long) ordinal * LEGACY_RANGE_STRIDE_SECONDS);
			return DateTimeRange.between(from, from.plusSeconds(LEGACY_RANGE_WIDTH_SECONDS));
		}

		/**
		 * Builds the range points a pre-millisecond writer left on disk for the whole paged fixture: the lower
		 * border sentinel, one start / end pair per record in ascending threshold order, and the upper sentinel.
		 *
		 * @return the points, in ascending threshold order and at second granularity
		 */
		@Nonnull
		private TransactionalRangePoint[] pagedSecondGranularityPoints() {
			final TransactionalRangePoint[] points = new TransactionalRangePoint[2 * LEGACY_PAGED_RECORDS + 2];
			points[0] = new TransactionalRangePoint(Long.MIN_VALUE);
			for (int record = 1; record <= LEGACY_PAGED_RECORDS; record++) {
				final DateTimeRange validity = pagedValidity(record);
				points[2 * record - 1] = new TransactionalRangePoint(
					validity.getFrom() / 1_000L, new int[]{record}, new int[0]
				);
				points[2 * record] = new TransactionalRangePoint(
					validity.getTo() / 1_000L, new int[0], new int[]{record}
				);
			}
			points[points.length - 1] = new TransactionalRangePoint(Long.MAX_VALUE);
			return points;
		}

		/**
		 * Builds the range points a pre-millisecond writer left on disk for one record valid over `[from, to]`,
		 * including the two border sentinels a range index always carries.
		 *
		 * @param from     the lower threshold in the writer's own scale
		 * @param to       the upper threshold in the writer's own scale
		 * @param recordId the record the range belongs to
		 * @return the four range points, in ascending threshold order
		 */
		@Nonnull
		private TransactionalRangePoint[] secondGranularityPoints(long from, long to, int recordId) {
			return new TransactionalRangePoint[]{
				new TransactionalRangePoint(Long.MIN_VALUE),
				new TransactionalRangePoint(from, new int[]{recordId}, new int[0]),
				new TransactionalRangePoint(to, new int[0], new int[]{recordId}),
				new TransactionalRangePoint(Long.MAX_VALUE)
			};
		}

		/**
		 * Builds a histogram root whose bucket axis is inline and whose range companion is inline too, carrying the
		 * supplied legacy-scaled thresholds — the shape a small pre-millisecond histogram was persisted in.
		 *
		 * @param valueType   the declared value type, which is what routes the rescale
		 * @param buckets     the inline bucket points
		 * @param rangePoints the inline range points, in ascending threshold order and at second granularity
		 * @return the root storage part
		 */
		@Nonnull
		private HistogramIndexStoragePart legacyInlineRoot(
			@Nonnull Class<?> valueType,
			@Nonnull ValueToRecordBitmap[] buckets,
			@Nonnull TransactionalRangePoint[] rangePoints
		) {
			return new HistogramIndexStoragePart(
				ENTITY_INDEX_PK, HISTOGRAM_NAME, null, valueType, buckets, new RangeIndex(rangePoints), 0
			);
		}

		/**
		 * Builds a histogram whose range axis is `PAGED` across several leaf pages holding legacy-scaled thresholds,
		 * plus the root that lists them. The bucket axis stays inline, so the emission this fixture provokes on
		 * reload carries range pages and nothing else.
		 *
		 * @param valueType   the declared value type, which is what routes the rescale
		 * @param buckets     the inline bucket points
		 * @param rangePoints the range points, in ascending threshold order and at second granularity
		 * @return the leaf pages followed by the root, ready to be persisted
		 */
		@Nonnull
		private List<StoragePart> legacyRangePagedParts(
			@Nonnull Class<?> valueType,
			@Nonnull ValueToRecordBitmap[] buckets,
			@Nonnull TransactionalRangePoint[] rangePoints
		) {
			final int pageCount =
				(rangePoints.length + LEGACY_RANGE_PAGE_SIZE - 1) / LEGACY_RANGE_PAGE_SIZE;
			final int[] pageSequences = new int[pageCount];
			final List<StoragePart> parts = new ArrayList<>(pageCount + 1);
			for (int page = 0; page < pageCount; page++) {
				final int from = page * LEGACY_RANGE_PAGE_SIZE;
				final int to = Math.min(from + LEGACY_RANGE_PAGE_SIZE, rangePoints.length);
				final TransactionalRangePoint[] pagePoints = new TransactionalRangePoint[to - from];
				System.arraycopy(rangePoints, from, pagePoints, 0, to - from);
				pageSequences[page] = page;
				parts.add(
					new HistogramRangeIndexLeafPagePart(ENTITY_INDEX_PK, HISTOGRAM_NAME, null, page, pagePoints)
				);
			}
			parts.add(
				new HistogramIndexStoragePart(
					ENTITY_INDEX_PK, HISTOGRAM_NAME, null, valueType, buckets, null, 0,
					false, -1, new int[0],
					true, pageCount - 1, pageSequences,
					null
				)
			);
			return parts;
		}
	}

	@Nested
	@DisplayName("Granular flush-emission dirty gate")
	class GranularEmissionGate {

		@Test
		@DisplayName("only the cardinality sibling is re-emitted when a commit changes cardinality but no bucket")
		void shouldEmitOnlyCardinalitySiblingWhenOnlyCardinalityChanges() {
			final OffsetIndexDescriptor descriptor = persist(stripRemovals(emit(pagedSource())));
			OffsetIndex reloaded = null;
			try {
				reloaded = loadOffsetIndex(descriptor, PERSISTED_VERSION);
				final HistogramIndex restored = reloadThroughLoader(
					reloaded, SIMPLE_MANIFEST_KEYS, PERSISTED_VERSION, HISTOGRAM_NAME
				);

				// add a SECOND reference for an already-present (value, owner) pair: cardinality goes 1 -> 2 without
				// crossing the membership boundary, so the filter index (bucket axis) stays clean
				restored.insertValue(null, 1, 1);

				final List<StoragePart> secondEmission = emit(restored);
				assertEquals(
					1, cardinalitySiblings(secondEmission).size(),
					"a cardinality-only commit must re-emit exactly one cardinality sibling"
				);
				assertEquals(
					0, histogramRoots(secondEmission).size(),
					"a cardinality-only commit must NOT re-emit the histogram root"
				);
				assertEquals(
					0, leafPages(secondEmission).size(),
					"a cardinality-only commit must NOT re-emit any bucket leaf page"
				);
				assertEquals(
					0, leafPageRemovals(secondEmission).size(),
					"a cardinality-only commit must NOT remove any leaf page"
				);
			} finally {
				if (reloaded != null) {
					IOUtils.closeQuietly(reloaded::close);
				}
			}
		}

		@Test
		@DisplayName("only the changed bucket leaf is re-emitted (root skipped) when the live page list is unchanged")
		void shouldReEmitOnlyChangedBucketLeafAndSkipRootWhenLivePageListUnchanged() {
			final OffsetIndexDescriptor descriptor = persist(stripRemovals(emit(pagedSource())));
			OffsetIndex reloaded = null;
			try {
				reloaded = loadOffsetIndex(descriptor, PERSISTED_VERSION);
				final HistogramIndex restored = reloadThroughLoader(
					reloaded, SIMPLE_MANIFEST_KEYS, PERSISTED_VERSION, HISTOGRAM_NAME
				);

				// add a fresh owner to an already-present value: the value's bucket gains a record (one leaf changes)
				// but no new bucket key is created, so the tree neither splits nor merges — the live page list is stable
				restored.insertValue(null, 1, VALUE_COUNT + 100);

				final List<StoragePart> secondEmission = emit(restored);
				assertEquals(
					1, leafPages(secondEmission).size(),
					"a single-bucket mutation must re-emit exactly the one changed bucket leaf page"
				);
				assertEquals(
					0, histogramRoots(secondEmission).size(),
					"a page-stable bucket mutation must skip the byte-identical histogram root"
				);
				assertEquals(
					1, cardinalitySiblings(secondEmission).size(),
					"the mutation crosses the membership boundary, so its cardinality sibling is re-emitted"
				);
				assertEquals(
					0, leafPageRemovals(secondEmission).size(),
					"a mutation that neither splits nor merges must not remove any leaf page"
				);
			} finally {
				if (reloaded != null) {
					IOUtils.closeQuietly(reloaded::close);
				}
			}
		}

		@Test
		@DisplayName("collapsing PAGED -> SINGLE across two warm-up flushes still removes every prior bucket leaf page")
		void shouldRemovePriorLeafPagesWhenCollapsingAcrossTwoWarmUpFlushesWithoutAnIntermediateReload() {
			// the sibling test above collapses a RELOADED index, whose page baseline the loader restored from disk. A
			// warm-up catalog never reloads and never reaches a commit-merge — the only place the staged page set is
			// published — so its collapse must reclaim against the set the previous flush STAGED, not the published one.
			final SimpleHistogramIndex source = pagedSource();

			// first warm-up flush: stages every bucket leaf page, publishes nothing
			final List<StoragePart> firstEmission = emit(source);
			final int priorLeafPageCount = leafPages(firstEmission).size();
			assertTrue(priorLeafPageCount >= 3, "the source histogram must start paged across several leaves");

			// collapse the SAME in-memory index: drop all but a handful of buckets so the survivors fit a single leaf
			for (int i = COLLAPSE_KEEP + 1; i <= VALUE_COUNT; i++) {
				source.removeValue(null, i, i);
			}

			final List<StoragePart> secondEmission = emit(source);
			assertFalse(
				histogramRoot(secondEmission).isPaged(),
				"the collapsed histogram must emit a single inline (SINGLE) root"
			);
			assertEquals(
				0, leafPages(secondEmission).size(),
				"a collapsed histogram must not re-emit any bucket leaf page"
			);
			assertEquals(
				priorLeafPageCount, leafPageRemovals(secondEmission).size(),
				"the collapse must remove every leaf page the previous warm-up flush wrote — the append-only OffsetIndex " +
					"never reclaims a record that is neither superseded nor explicitly removed, so a missed removal leaks " +
					"the page forever"
			);
		}

		@Test
		@DisplayName("collapsing a range-typed PAGED -> SINGLE across two warm-up flushes removes every prior RANGE leaf page")
		void shouldRemovePriorRangeLeafPagesWhenCollapsingAcrossTwoWarmUpFlushesWithoutAnIntermediateReload() {
			// the bucket-axis sibling above pins this defect on the BUCKET page stream; a range-typed histogram pages a
			// SECOND, independent stream (the RangeIndex threshold tree) whose collapse site must reclaim against the
			// same staged-not-published set — a warm-up catalog never reloads and never reaches the commit-merge that
			// publishes, so a published-set reclaim would free NOTHING and leak every range page ever written.
			final SimpleHistogramIndex source = pagedRangeSource();

			// first warm-up flush: stages every bucket AND range leaf page, publishes nothing
			final List<StoragePart> firstEmission = emit(source);
			final HistogramIndexStoragePart firstRoot = histogramRoot(firstEmission);
			assertTrue(firstRoot.isPaged(), "the source histogram must start with a paged bucket axis");
			assertTrue(firstRoot.isRangePaged(), "the source histogram must start with a paged range axis");
			final int priorBucketPageCount = leafPages(firstEmission).size();
			final int priorRangePageCount = rangeLeafPages(firstEmission).size();
			assertTrue(priorBucketPageCount >= 3, "the bucket axis must start paged across several leaves");
			assertTrue(priorRangePageCount >= 3, "the range axis must start paged across several leaves");
			assertEquals(0, leafPageRemovals(firstEmission).size(), "a first flush frees no leaf page");

			// collapse the SAME in-memory index: drop all but a handful of ranges. The survivors' values fit a single
			// bucket leaf and their thresholds a single range leaf, so BOTH axes fall back to the inline SINGLE shape
			// and NEITHER re-enters the paged branch (which would publish the staged set instead of reclaiming it).
			for (int i = COLLAPSE_KEEP + 1; i <= VALUE_COUNT; i++) {
				source.removeValue(null, IntegerNumberRange.between(i, i + 1), i);
			}

			final List<StoragePart> secondEmission = emit(source);
			final HistogramIndexStoragePart collapsedRoot = histogramRoot(secondEmission);
			assertFalse(collapsedRoot.isRangePaged(), "the collapsed range axis must be carried inline (SINGLE)");
			assertFalse(collapsedRoot.isPaged(), "the collapsed bucket axis must be carried inline (SINGLE)");
			assertEquals(
				0, rangeLeafPages(secondEmission).size(),
				"a collapsed range axis must not re-emit any range leaf page"
			);
			assertEquals(
				priorRangePageCount, leafPageRemovalsOfKind(secondEmission, StreamKind.RANGE).size(),
				"the collapse must remove every RANGE leaf page the previous warm-up flush wrote — the append-only " +
					"OffsetIndex never reclaims a record that is neither superseded nor explicitly removed, so a missed " +
					"removal leaks the page forever"
			);
			assertEquals(
				priorBucketPageCount, leafPageRemovalsOfKind(secondEmission, StreamKind.BUCKET).size(),
				"the same collapse must reclaim the bucket axis' own prior leaf pages — the two page streams are " +
					"independent and each has to free its own"
			);
		}

		@Test
		@DisplayName("collapsing PAGED -> SINGLE removes every prior bucket leaf page and carries the buckets inline")
		void shouldRemovePriorLeafPagesAndCarryInlineWhenCollapsingFromPagedToSingle() {
			final OffsetIndex offsetIndex = openWritableOffsetIndex();
			OffsetIndexDescriptor secondDescriptor = null;
			int priorLeafPageCount = 0;
			try {
				// first flush (version 1): persist every bucket leaf page + the paged root + the cardinality sibling
				final List<StoragePart> firstEmission = emit(pagedSource());
				priorLeafPageCount = leafPages(firstEmission).size();
				assertTrue(priorLeafPageCount >= 3, "the source histogram must start paged across several leaves");
				writeEmission(offsetIndex, PERSISTED_VERSION, stripRemovals(firstEmission));
				offsetIndex.flush(PERSISTED_VERSION);

				// reopen the persisted pages through the real loader (restores the page-stream live-set baseline)
				final HistogramIndex restored = reloadThroughLoader(
					offsetIndex, SIMPLE_MANIFEST_KEYS, PERSISTED_VERSION, HISTOGRAM_NAME
				);

				// collapse: drop all but a handful of buckets so the survivors fit within a single leaf (PAGED -> SINGLE)
				for (int i = COLLAPSE_KEEP + 1; i <= VALUE_COUNT; i++) {
					restored.removeValue(null, i, i);
				}

				final List<StoragePart> secondEmission = emit(restored);
				final HistogramIndexStoragePart collapsedRoot = histogramRoot(secondEmission);
				assertFalse(collapsedRoot.isPaged(), "the collapsed histogram must emit a single inline (SINGLE) root");
				assertEquals(
					COLLAPSE_KEEP, collapsedRoot.getHistogramPoints().length,
					"the SINGLE root must carry every surviving bucket inline"
				);
				assertEquals(
					0, leafPages(secondEmission).size(),
					"a collapsed histogram must not re-emit any bucket leaf page"
				);
				assertEquals(
					priorLeafPageCount, leafPageRemovals(secondEmission).size(),
					"the collapse must remove exactly one leaf page per previously-live bucket page"
				);

				// apply the collapse emission (removals INCLUDED) to the SAME offset index, then flush
				writeEmission(offsetIndex, SECOND_VERSION, secondEmission);
				secondDescriptor = offsetIndex.flush(SECOND_VERSION);
			} finally {
				IOUtils.closeQuietly(offsetIndex::close);
			}

			// reopen the collapsed file and verify the SINGLE index equals an oracle built only from the survivors
			OffsetIndex reopened = null;
			try {
				reopened = loadOffsetIndex(secondDescriptor, SECOND_VERSION);
				final HistogramIndex reloaded = reloadThroughLoader(
					reopened, SIMPLE_MANIFEST_KEYS, SECOND_VERSION, HISTOGRAM_NAME
				);

				final SimpleHistogramIndex oracle = new SimpleHistogramIndex(
					HISTOGRAM_NAME, REFERENCE_NAME, Integer.class, 0
				);
				for (int i = 1; i <= COLLAPSE_KEEP; i++) {
					oracle.insertValue(null, i, i);
				}
				assertReloadIdentical(oracle, reloaded, null);
			} finally {
				if (reopened != null) {
					IOUtils.closeQuietly(reopened::close);
				}
			}
		}
	}

	@Nested
	@DisplayName("Empty-drop leaf-page + cardinality reclaim")
	class EmptyDropReclaim {

		@Test
		@DisplayName("dropping a whole PAGED histogram physically removes every bucket leaf page + its cardinality sibling")
		void shouldReclaimBucketLeafPagesAndCardinalityWhenWholeHistogramDropped() {
			final Map<String, HistogramIndex> backingMap = new HashMap<>();
			backingMap.put(HISTOGRAM_NAME, pagedSource());
			final TransactionalMap<String, HistogramIndex> histogramIndexes = new TransactionalMap<>(backingMap);
			final HistogramIndexMapComponent component =
				new HistogramIndexMapComponent(histogramIndexes, ENTITY_INDEX_KEY);

			final OffsetIndex offsetIndex = openWritableOffsetIndex();
			OffsetIndexDescriptor secondDescriptor = null;
			final List<Long> removedBucketPartPks = new ArrayList<>();
			Long removedCardinalityPartPk = null;
			try {
				// first flush (v1): persist the paged histogram's bucket leaf pages + root + cardinality sibling
				final List<StoragePart> firstEmission = emitComponent(component);
				final int priorLeafPageCount = leafPages(firstEmission).size();
				assertTrue(priorLeafPageCount >= 3, "the source histogram must page out across several leaves");
				assertEquals(0, leafPageRemovals(firstEmission).size(), "a first flush frees no leaf page");
				assertEquals(0, cardinalityRemovals(firstEmission).size(), "a first flush removes no cardinality sibling");
				writeEmission(offsetIndex, PERSISTED_VERSION, stripRemovals(firstEmission));
				offsetIndex.flush(PERSISTED_VERSION);

				// drop the whole histogram from the owning map — exactly what HistogramIndexOperations does when it empties
				backingMap.remove(HISTOGRAM_NAME);

				// second flush (v2): the dropped histogram's own flush never runs again, so the component must reclaim its
				// now-orphaned bucket leaf pages + cardinality sibling instead of leaking them forever
				final List<StoragePart> secondEmission = emitComponent(component);
				assertEquals(0, leafPages(secondEmission).size(), "a drop re-emits no leaf page");
				assertEquals(0, histogramRoots(secondEmission).size(), "a drop re-emits no root");
				assertEquals(0, cardinalitySiblings(secondEmission).size(), "a drop re-emits no cardinality sibling");
				assertEquals(
					priorLeafPageCount, leafPageRemovals(secondEmission).size(),
					"the drop must remove exactly one leaf page per previously-live bucket page"
				);
				assertEquals(
					1, cardinalityRemovals(secondEmission).size(),
					"the drop must remove the histogram's evicted cardinality sibling"
				);

				writeEmission(offsetIndex, SECOND_VERSION, secondEmission);
				for (final HistogramIndexLeafPageRemoval removal : leafPageRemovals(secondEmission)) {
					removedBucketPartPks.add(removal.getStoragePartPK());
				}
				removedCardinalityPartPk = cardinalityRemovals(secondEmission).get(0).getStoragePartPK();
				secondDescriptor = offsetIndex.flush(SECOND_VERSION);
			} finally {
				IOUtils.closeQuietly(offsetIndex::close);
			}

			// reopen at v2 and assert every reclaimed part is physically gone from storage
			OffsetIndex reopened = null;
			try {
				reopened = loadOffsetIndex(secondDescriptor, SECOND_VERSION);
				for (final Long removedBucketPartPk : removedBucketPartPks) {
					assertNotNull(removedBucketPartPk, "each leaf-page removal must resolve its store-side PK");
					assertNull(
						reopened.get(SECOND_VERSION, removedBucketPartPk, HistogramIndexLeafPagePart.class),
						"a reclaimed bucket leaf page must be physically removed from storage"
					);
				}
				assertNotNull(removedCardinalityPartPk, "the cardinality removal must resolve its store-side PK");
				assertNull(
					reopened.get(SECOND_VERSION, removedCardinalityPartPk, HistogramCardinalityStoragePart.class),
					"the reclaimed cardinality sibling must be physically removed from storage"
				);
			} finally {
				if (reopened != null) {
					IOUtils.closeQuietly(reopened::close);
				}
			}
		}

		@Test
		@DisplayName("pruning one locale reclaims only that locale's pages + sibling, never the surviving locale's")
		void shouldReclaimOnlyDroppedLocaleAndSpareTheSurvivor() {
			final LocalizedHistogramIndex localized = new LocalizedHistogramIndex(
				HISTOGRAM_NAME, REFERENCE_NAME, Integer.class, 0
			);
			for (int i = 1; i <= VALUE_COUNT; i++) {
				localized.insertValue(Locale.ENGLISH, i, i);
				localized.insertValue(CZECH, i, i);
			}
			final Map<String, HistogramIndex> backingMap = new HashMap<>();
			backingMap.put(HISTOGRAM_NAME, localized);
			final TransactionalMap<String, HistogramIndex> histogramIndexes = new TransactionalMap<>(backingMap);
			final HistogramIndexMapComponent component =
				new HistogramIndexMapComponent(histogramIndexes, ENTITY_INDEX_KEY);

			final OffsetIndex offsetIndex = openWritableOffsetIndex();
			OffsetIndexDescriptor secondDescriptor = null;
			final List<Long> removedCzechPartPks = new ArrayList<>();
			try {
				final List<StoragePart> firstEmission = emitComponent(component);
				final int czechLeafPageCount = leafPagesOfLocale(leafPages(firstEmission), CZECH).size();
				assertTrue(czechLeafPageCount >= 3, "the Czech locale must page out across several leaves");
				writeEmission(offsetIndex, PERSISTED_VERSION, stripRemovals(firstEmission));
				offsetIndex.flush(PERSISTED_VERSION);

				// prune the Czech locale only: remove all its values, so LocalizedHistogramIndex drops that locale entry
				for (int i = 1; i <= VALUE_COUNT; i++) {
					localized.removeValue(CZECH, i, i);
				}
				assertNull(localized.getFilterIndex(CZECH), "the Czech locale must be pruned from the localized histogram");
				assertNotNull(localized.getFilterIndex(Locale.ENGLISH), "the English locale must survive the prune");

				final List<StoragePart> secondEmission = emitComponent(component);
				// CRITICAL survivor safety: every reclaimed part must belong to the dropped Czech locale, never English
				for (final HistogramIndexLeafPageRemoval removal : leafPageRemovals(secondEmission)) {
					assertEquals(CZECH, removal.getLocale(), "a surviving locale's leaf page must never be reclaimed");
				}
				for (final HistogramCardinalityStoragePartRemoval removal : cardinalityRemovals(secondEmission)) {
					assertEquals(CZECH, removal.getLocale(), "a surviving locale's cardinality sibling must never be reclaimed");
				}
				assertEquals(
					czechLeafPageCount, leafPageRemovals(secondEmission).size(),
					"every previously-live Czech bucket leaf page must be reclaimed"
				);
				assertEquals(
					1, cardinalityRemovals(secondEmission).size(), "the Czech cardinality sibling must be reclaimed"
				);

				writeEmission(offsetIndex, SECOND_VERSION, secondEmission);
				for (final HistogramIndexLeafPageRemoval removal : leafPageRemovals(secondEmission)) {
					removedCzechPartPks.add(removal.getStoragePartPK());
				}
				secondDescriptor = offsetIndex.flush(SECOND_VERSION);
			} finally {
				IOUtils.closeQuietly(offsetIndex::close);
			}

			// reopen at v2: the Czech leaf pages are physically gone, the English locale reloads intact
			OffsetIndex reopened = null;
			try {
				reopened = loadOffsetIndex(secondDescriptor, SECOND_VERSION);
				for (final Long removedCzechPartPk : removedCzechPartPks) {
					assertNull(
						reopened.get(SECOND_VERSION, removedCzechPartPk, HistogramIndexLeafPagePart.class),
						"a reclaimed Czech leaf page must be physically removed from storage"
					);
				}
				final Set<HistogramIndexStorageKey> survivorManifestKeys = Set.of(
					new HistogramIndexStorageKey(ENTITY_INDEX_KEY, HISTOGRAM_NAME, Locale.ENGLISH)
				);
				final HistogramIndex reloaded = reloadThroughLoader(
					reopened, survivorManifestKeys, SECOND_VERSION, HISTOGRAM_NAME
				);
				assertReloadIdentical(localized, reloaded, Locale.ENGLISH);
			} finally {
				if (reopened != null) {
					IOUtils.closeQuietly(reopened::close);
				}
			}
		}

		@Test
		@DisplayName("a content-only commit with no dropped histogram reclaims nothing (survivor churn is not a drop)")
		void shouldReclaimNothingWhenNoHistogramIsDropped() {
			final SimpleHistogramIndex histogram = pagedSource();
			final Map<String, HistogramIndex> backingMap = new HashMap<>();
			backingMap.put(HISTOGRAM_NAME, histogram);
			final TransactionalMap<String, HistogramIndex> histogramIndexes = new TransactionalMap<>(backingMap);
			final HistogramIndexMapComponent component =
				new HistogramIndexMapComponent(histogramIndexes, ENTITY_INDEX_KEY);

			// first flush stages the histogram; the component snapshots its live on-disk page set
			emitComponent(component);

			// mutate CONTENT only — add a fresh owner to an existing value; the histogram stays live in the map
			histogram.insertValue(null, 1, VALUE_COUNT + 100);

			final List<StoragePart> secondEmission = emitComponent(component);
			assertEquals(
				0, leafPageRemovals(secondEmission).size(),
				"a surviving histogram's own page churn must not be reclaimed by the empty-drop diff"
			);
			assertEquals(
				0, cardinalityRemovals(secondEmission).size(),
				"no cardinality sibling may be reclaimed when nothing is dropped"
			);
		}

		@Test
		@DisplayName("dropping an inline SINGLE histogram reclaims its cardinality sibling but no leaf page (it had none)")
		void shouldReclaimOnlyCardinalityWhenInlineHistogramDropped() {
			final SimpleHistogramIndex inline = new SimpleHistogramIndex(
				HISTOGRAM_NAME, REFERENCE_NAME, Integer.class, 0
			);
			// a handful of buckets fit inside a single leaf, so the histogram persists SINGLE (inline, never paged)
			for (int i = 1; i <= COLLAPSE_KEEP; i++) {
				inline.insertValue(null, i, i);
			}
			final Map<String, HistogramIndex> backingMap = new HashMap<>();
			backingMap.put(HISTOGRAM_NAME, inline);
			final TransactionalMap<String, HistogramIndex> histogramIndexes = new TransactionalMap<>(backingMap);
			final HistogramIndexMapComponent component =
				new HistogramIndexMapComponent(histogramIndexes, ENTITY_INDEX_KEY);

			final List<StoragePart> firstEmission = emitComponent(component);
			assertFalse(histogramRoot(firstEmission).isPaged(), "a small histogram must persist as a SINGLE inline root");
			assertEquals(0, leafPages(firstEmission).size(), "an inline histogram writes no leaf page");

			backingMap.remove(HISTOGRAM_NAME);

			final List<StoragePart> secondEmission = emitComponent(component);
			assertEquals(
				0, leafPageRemovals(secondEmission).size(), "an inline histogram has no leaf page to reclaim"
			);
			assertEquals(
				1, cardinalityRemovals(secondEmission).size(),
				"the inline histogram's evicted cardinality sibling must still be reclaimed on drop"
			);
		}

		@Test
		@DisplayName("dropping a range-typed histogram reclaims both its bucket and its range leaf pages from storage")
		void shouldReclaimBothBucketAndRangeLeafPagesWhenRangeTypedHistogramDropped() {
			final SimpleHistogramIndex rangeHistogram = new SimpleHistogramIndex(
				HISTOGRAM_NAME, REFERENCE_NAME, IntegerNumberRange.class, 0
			);
			// one distinct range per owner: the value pages the bucket axis, its two thresholds page the range axis
			for (int i = 1; i <= VALUE_COUNT; i++) {
				rangeHistogram.insertValue(null, IntegerNumberRange.between(i, i + 1), i);
			}
			final Map<String, HistogramIndex> backingMap = new HashMap<>();
			backingMap.put(HISTOGRAM_NAME, rangeHistogram);
			final TransactionalMap<String, HistogramIndex> histogramIndexes = new TransactionalMap<>(backingMap);
			final HistogramIndexMapComponent component =
				new HistogramIndexMapComponent(histogramIndexes, ENTITY_INDEX_KEY);

			final OffsetIndex offsetIndex = openWritableOffsetIndex();
			OffsetIndexDescriptor secondDescriptor = null;
			final List<Long> removedRangePartPks = new ArrayList<>();
			try {
				// first flush (v1): persist both the bucket and the range leaf pages + root + cardinality sibling
				final List<StoragePart> firstEmission = emitComponent(component);
				final int priorBucketPageCount = leafPages(firstEmission).size();
				final int priorRangePageCount = rangeLeafPages(firstEmission).size();
				assertTrue(priorBucketPageCount >= 3, "the range histogram's bucket axis must page across several leaves");
				assertTrue(priorRangePageCount >= 3, "the range histogram's range axis must page across several leaves");
				writeEmission(offsetIndex, PERSISTED_VERSION, stripRemovals(firstEmission));
				offsetIndex.flush(PERSISTED_VERSION);

				// drop the whole range-typed histogram — its own flush never runs again, so BOTH axes must be reclaimed
				backingMap.remove(HISTOGRAM_NAME);

				final List<StoragePart> secondEmission = emitComponent(component);
				assertEquals(
					priorBucketPageCount, leafPageRemovalsOfKind(secondEmission, StreamKind.BUCKET).size(),
					"the drop must reclaim one removal per previously-live bucket leaf page"
				);
				assertEquals(
					priorRangePageCount, leafPageRemovalsOfKind(secondEmission, StreamKind.RANGE).size(),
					"the drop must reclaim one removal per previously-live range leaf page"
				);
				assertEquals(
					1, cardinalityRemovals(secondEmission).size(),
					"the drop must reclaim the range histogram's evicted cardinality sibling"
				);

				writeEmission(offsetIndex, SECOND_VERSION, secondEmission);
				for (final HistogramIndexLeafPageRemoval removal :
					leafPageRemovalsOfKind(secondEmission, StreamKind.RANGE)) {
					removedRangePartPks.add(removal.getStoragePartPK());
				}
				secondDescriptor = offsetIndex.flush(SECOND_VERSION);
			} finally {
				IOUtils.closeQuietly(offsetIndex::close);
			}

			// reopen at v2 and prove the RANGE-axis branch actually deletes each reclaimed range leaf page from storage
			OffsetIndex reopened = null;
			try {
				reopened = loadOffsetIndex(secondDescriptor, SECOND_VERSION);
				for (final Long removedRangePartPk : removedRangePartPks) {
					assertNotNull(removedRangePartPk, "each range leaf-page removal must resolve its store-side PK");
					assertNull(
						reopened.get(SECOND_VERSION, removedRangePartPk, HistogramRangeIndexLeafPagePart.class),
						"a reclaimed range leaf page must be physically removed from storage"
					);
				}
			} finally {
				if (reopened != null) {
					IOUtils.closeQuietly(reopened::close);
				}
			}
		}

		@Test
		@DisplayName("the reflush after an empty drop reclaims nothing (an already-reclaimed part is not removed twice)")
		void shouldReclaimNothingOnTheReflushAfterAnEmptyDrop() {
			final Map<String, HistogramIndex> backingMap = new HashMap<>();
			backingMap.put(HISTOGRAM_NAME, pagedSource());
			final TransactionalMap<String, HistogramIndex> histogramIndexes = new TransactionalMap<>(backingMap);
			final HistogramIndexMapComponent component =
				new HistogramIndexMapComponent(histogramIndexes, ENTITY_INDEX_KEY);

			// baseline flush captures the live on-disk page set as the empty-drop reclaim diff baseline
			emitComponent(component);

			// drop the whole histogram: this flush reclaims every orphaned leaf page + the cardinality sibling
			backingMap.remove(HISTOGRAM_NAME);
			final List<StoragePart> dropEmission = emitComponent(component);
			assertTrue(
				leafPageRemovals(dropEmission).size() >= 3,
				"the drop flush must reclaim every previously-live bucket leaf page"
			);
			assertEquals(
				1, cardinalityRemovals(dropEmission).size(), "the drop flush must reclaim the cardinality sibling"
			);

			// reflush WITHOUT any further mutation: the baseline was advanced to the survivors (none), so the
			// already-reclaimed pages must never be removed a second time
			final List<StoragePart> reflushEmission = emitComponent(component);
			assertEquals(
				0, leafPageRemovals(reflushEmission).size(),
				"the reflush after a drop must not reclaim an already-reclaimed leaf page"
			);
			assertEquals(
				0, cardinalityRemovals(reflushEmission).size(),
				"the reflush after a drop must not reclaim an already-reclaimed cardinality sibling"
			);
		}

		@Test
		@DisplayName("dropping one of two histogram names reclaims only its pages and leaves the other intact on disk")
		void shouldSpareOtherHistogramWhenOneOfTwoNamesDropped() {
			final SimpleHistogramIndex survivor = pagedSourceNamed(SECOND_HISTOGRAM_NAME);
			final Map<String, HistogramIndex> backingMap = new HashMap<>();
			backingMap.put(HISTOGRAM_NAME, pagedSourceNamed(HISTOGRAM_NAME));
			backingMap.put(SECOND_HISTOGRAM_NAME, survivor);
			final TransactionalMap<String, HistogramIndex> histogramIndexes = new TransactionalMap<>(backingMap);
			final HistogramIndexMapComponent component =
				new HistogramIndexMapComponent(histogramIndexes, ENTITY_INDEX_KEY);

			final OffsetIndex offsetIndex = openWritableOffsetIndex();
			OffsetIndexDescriptor secondDescriptor = null;
			try {
				// first flush (v1): both histograms page out under their own independent stream ids
				final List<StoragePart> firstEmission = emitComponent(component);
				assertTrue(
					leafPages(firstEmission).size() >= 6, "both histograms must page out across several leaves each"
				);
				writeEmission(offsetIndex, PERSISTED_VERSION, stripRemovals(firstEmission));
				offsetIndex.flush(PERSISTED_VERSION);

				// drop only the first name; the second must survive untouched
				backingMap.remove(HISTOGRAM_NAME);

				final List<StoragePart> secondEmission = emitComponent(component);
				assertTrue(
					leafPageRemovals(secondEmission).size() >= 3,
					"the dropped name's bucket leaf pages must be reclaimed"
				);
				assertEquals(
					1, cardinalityRemovals(secondEmission).size(),
					"only the dropped name's cardinality sibling must be reclaimed"
				);

				writeEmission(offsetIndex, SECOND_VERSION, secondEmission);
				secondDescriptor = offsetIndex.flush(SECOND_VERSION);
			} finally {
				IOUtils.closeQuietly(offsetIndex::close);
			}

			// reopen at v2 and reload the SURVIVOR name through the real loader: its pages were untouched by the drop,
			// so it must reload identically — the definitive proof reclaim never crosses histogram-name boundaries
			OffsetIndex reopened = null;
			try {
				reopened = loadOffsetIndex(secondDescriptor, SECOND_VERSION);
				final Set<HistogramIndexStorageKey> survivorManifestKeys = Set.of(
					new HistogramIndexStorageKey(ENTITY_INDEX_KEY, SECOND_HISTOGRAM_NAME, null)
				);
				final HistogramIndex reloaded = reloadThroughLoader(
					reopened, survivorManifestKeys, SECOND_VERSION, SECOND_HISTOGRAM_NAME
				);
				assertReloadIdentical(survivor, reloaded, null);
			} finally {
				if (reopened != null) {
					IOUtils.closeQuietly(reopened::close);
				}
			}
		}
	}

	/*
		PRIVATE HELPERS
	 */

	/**
	 * Flushes a whole {@link HistogramIndexMapComponent} (the map-owner seam where a whole-histogram / per-locale drop is
	 * visible) and returns every emitted storage part, removals included.
	 */
	@Nonnull
	private static List<StoragePart> emitComponent(@Nonnull HistogramIndexMapComponent component) {
		final TrappedChanges trappedChanges = new TrappedChanges();
		component.collectModifiedStorageParts(ENTITY_INDEX_PK, new EntityIndexManifest(), trappedChanges);
		final List<StoragePart> parts = new ArrayList<>();
		final Iterator<StoragePart> iterator = trappedChanges.getTrappedChangesIterator();
		while (iterator.hasNext()) {
			parts.add(iterator.next());
		}
		return parts;
	}

	@Nonnull
	private static List<HistogramCardinalityStoragePartRemoval> cardinalityRemovals(@Nonnull List<StoragePart> parts) {
		final List<HistogramCardinalityStoragePartRemoval> result = new ArrayList<>();
		for (final StoragePart part : parts) {
			if (part instanceof HistogramCardinalityStoragePartRemoval removal) {
				result.add(removal);
			}
		}
		return result;
	}

	/**
	 * Filters a flush emission down to the {@link HistogramIndexLeafPageRemoval}s of the given page-stream kind (bucket
	 * or range), so a two-axis drop can be asserted per axis.
	 */
	@Nonnull
	private static List<HistogramIndexLeafPageRemoval> leafPageRemovalsOfKind(
		@Nonnull List<StoragePart> parts, @Nonnull StreamKind streamKind
	) {
		final List<HistogramIndexLeafPageRemoval> result = new ArrayList<>();
		for (final StoragePart part : parts) {
			if (part instanceof HistogramIndexLeafPageRemoval removal && removal.getStreamKind() == streamKind) {
				result.add(removal);
			}
		}
		return result;
	}

	/**
	 * Builds a fresh non-localized histogram paged across several bucket leaves (one distinct value per owner).
	 */
	@Nonnull
	private static SimpleHistogramIndex pagedSource() {
		final SimpleHistogramIndex source = new SimpleHistogramIndex(
			HISTOGRAM_NAME, REFERENCE_NAME, Integer.class, 0
		);
		for (int i = 1; i <= VALUE_COUNT; i++) {
			source.insertValue(null, i, i);
		}
		return source;
	}

	/**
	 * Builds a fresh non-localized RANGE-typed histogram paged on BOTH axes: one distinct range per owner pages the
	 * bucket axis, while each range's two thresholds page the range axis. Consecutive ranges deliberately abut
	 * (`[i, i+1]`, `[i+1, i+2]`) so they share a threshold, exactly as the range-typed reload test's source does.
	 */
	@Nonnull
	private static SimpleHistogramIndex pagedRangeSource() {
		final SimpleHistogramIndex source = new SimpleHistogramIndex(
			HISTOGRAM_NAME, REFERENCE_NAME, IntegerNumberRange.class, 0
		);
		for (int i = 1; i <= VALUE_COUNT; i++) {
			source.insertValue(null, IntegerNumberRange.between(i, i + 1), i);
		}
		return source;
	}

	/**
	 * Builds the ordinal's distinct `DateTimeRange`, an hour wide, at a zone offset varying with the ordinal. The
	 * offsets vary per ordinal on purpose: the reloaded leaf column stores only the two comparison longs, so a
	 * reload that mishandled the derivation would move the instants without moving anything an `equals`-based
	 * parity assertion can see.
	 *
	 * Ordinals map to strictly ascending `getFrom()` instants: the moment advances a full hour (3600 s) per ordinal,
	 * and the offset trails that within each five-ordinal cycle (steps of 1800 s) and overshoots it at the cycle's
	 * wrap (a 7200 s swing the other way) — either way `instant = moment − offset` keeps climbing, so bucket order
	 * is ordinal order and the ranges double as a reload oracle.
	 *
	 * @param ordinal the ordinal to derive the range from
	 * @return an ascending, deterministic date-time range
	 */
	@Nonnull
	private static DateTimeRange dateTimeRange(int ordinal) {
		final ZoneOffset offset = ZoneOffset.ofTotalSeconds((ordinal % 5 - 2) * 1800);
		final LocalDateTime moment = LocalDateTime.of(2024, 1, 1, 0, 0).plusHours(ordinal);
		return DateTimeRange.between(moment.atOffset(offset), moment.plusHours(1).atOffset(offset));
	}

	/**
	 * Re-renders a range with both of its bounds moved to UTC, naming the very same two instants. This is the form
	 * the reloaded leaf column rebuilds a `DateTimeRange` key in — its two comparison longs identify instants and
	 * carry no offset — so it is the oracle a reload's instants are checked against.
	 *
	 * @param range the range to re-render
	 * @return the same two instants, both expressed at UTC
	 */
	@Nonnull
	private static DateTimeRange atUtc(@Nonnull DateTimeRange range) {
		return DateTimeRange.between(
			Objects.requireNonNull(range.getPreciseFrom()).withOffsetSameInstant(ZoneOffset.UTC),
			Objects.requireNonNull(range.getPreciseTo()).withOffsetSameInstant(ZoneOffset.UTC)
		);
	}

	/**
	 * Builds the ordinal's distinct `BigDecimalNumberRange`, three quarters of a unit wide, at
	 * {@link #BIG_DECIMAL_SCALE}. The scale is what the reloaded leaf column has to rebuild the precise bounds at —
	 * the two comparison longs it stores carry no scale of their own, so a reload that lost it is invisible to every
	 * equality, ordering and range-overlap assertion.
	 *
	 * Ordinals map to strictly ascending `getFrom()` thresholds — the lower bound advances a whole unit per ordinal
	 * while the range spans three quarters of one — so bucket order is ordinal order and the ranges double as a
	 * reload oracle.
	 *
	 * @param ordinal the ordinal to derive the range from
	 * @return an ascending, deterministic big decimal range
	 */
	@Nonnull
	private static BigDecimalNumberRange bigDecimalRange(int ordinal) {
		final BigDecimal from = BigDecimal.valueOf(ordinal * 100L + 5L, BIG_DECIMAL_SCALE);
		return BigDecimalNumberRange.between(from, from.add(new BigDecimal("0.75")), BIG_DECIMAL_SCALE);
	}

	/**
	 * Builds a fresh non-localized histogram under the given name, paged across several bucket leaves — used to place
	 * two independently-paged histograms in a single owning map.
	 */
	@Nonnull
	private static SimpleHistogramIndex pagedSourceNamed(@Nonnull String histogramName) {
		final SimpleHistogramIndex source = new SimpleHistogramIndex(
			histogramName, REFERENCE_NAME, Integer.class, 0
		);
		for (int i = 1; i <= VALUE_COUNT; i++) {
			source.insertValue(null, i, i);
		}
		return source;
	}

	/**
	 * Asserts the reconstructed histogram exposes the identical bucket set (value → owner record ids) as the source for
	 * the given locale (`null` for a non-localized histogram).
	 */
	private static void assertReloadIdentical(
		@Nonnull HistogramIndex expected, @Nonnull HistogramIndex actual, @Nullable Locale locale
	) {
		final FilterIndex expectedFilter = expected.getFilterIndex(locale);
		final FilterIndex actualFilter = actual.getFilterIndex(locale);
		assertNotNull(expectedFilter, "the source histogram must expose a filter index for locale " + locale);
		assertNotNull(actualFilter, "the reloaded histogram must expose a filter index for locale " + locale);
		final ValueToRecordBitmap[] expectedBuckets = expectedFilter.getInvertedIndex().getValueToRecordBitmap();
		final ValueToRecordBitmap[] actualBuckets = actualFilter.getInvertedIndex().getValueToRecordBitmap();
		assertEquals(expectedBuckets.length, actualBuckets.length, "bucket count must round-trip");
		for (int i = 0; i < expectedBuckets.length; i++) {
			assertEquals(
				expectedBuckets[i].getValue(), actualBuckets[i].getValue(), "bucket " + i + " value must round-trip"
			);
			assertArrayEquals(
				expectedBuckets[i].getRecordIds().getArray(), actualBuckets[i].getRecordIds().getArray(),
				"bucket " + i + " record ids must round-trip"
			);
		}
	}

	/**
	 * Asserts a set of range-overlap probes returns the identical record ids against the source and the reloaded
	 * range-typed histogram, exercising the reassembled range axis.
	 */
	private static void assertSameRangeQueries(@Nonnull HistogramIndex expected, @Nonnull HistogramIndex actual) {
		assertSameRangeQueries(
			expected, actual,
			new long[][]{
				{1L, 5L}, {100L, 105L}, {1000L, 1000L}, {VALUE_COUNT - 3L, VALUE_COUNT + 1L}, {0L, VALUE_COUNT + 100L}
			}
		);
	}

	/**
	 * Asserts the supplied range-overlap probes return the identical record ids against the source and the reloaded
	 * range-typed histogram. The probes are a parameter because a threshold is whatever the indexed range type
	 * encodes to — small integers for an `IntegerNumberRange` histogram, epoch seconds for a `DateTimeRange` one.
	 *
	 * @param expected the source histogram
	 * @param actual   the reloaded histogram
	 * @param probes   the `(from, to)` threshold pairs to probe with
	 */
	private static void assertSameRangeQueries(
		@Nonnull HistogramIndex expected, @Nonnull HistogramIndex actual, @Nonnull long[][] probes
	) {
		final FilterIndex expectedFilter = expected.getFilterIndex(null);
		final FilterIndex actualFilter = actual.getFilterIndex(null);
		assertNotNull(expectedFilter, "the source range histogram must expose a filter index");
		assertNotNull(actualFilter, "the reloaded range histogram must expose a filter index");
		for (final long[] probe : probes) {
			final Bitmap expectedRecords = expectedFilter.getRecordsOverlapping(probe[0], probe[1]);
			final Bitmap actualRecords = actualFilter.getRecordsOverlapping(probe[0], probe[1]);
			assertArrayEquals(
				expectedRecords.getArray(), actualRecords.getArray(),
				"range overlap [" + probe[0] + ", " + probe[1] + "] must round-trip"
			);
		}
	}

	/**
	 * Wraps `service` in a {@link LoadContext} whose manifest advertises exactly the supplied histogram keys and reads at
	 * the given catalog version.
	 */
	@Nonnull
	private static LoadContext loadContext(
		@Nonnull StoragePartPersistenceService<StorageDescriptor> service,
		@Nonnull Set<HistogramIndexStorageKey> manifestKeys,
		long catalogVersion
	) {
		final EntityIndexStoragePart manifest = new EntityIndexStoragePart(
			ENTITY_INDEX_PK, 1, ENTITY_INDEX_KEY,
			Set.of(), Set.of(), false, Set.of(), manifestKeys
		);
		return new LoadContext(
			catalogVersion,
			ENTITY_INDEX_PK,
			EntitySchema._internalBuild(ENTITY_TYPE),
			ENTITY_INDEX_KEY,
			manifest,
			manifest.getVersion(),
			EmptyBitmap.INSTANCE,
			Map.of(),
			service,
			null
		);
	}

	/**
	 * Drives the REAL {@link HistogramIndexMapLoader} over the given (open) offset index and returns the reconstructed
	 * histogram sub-index for `histogramName`. Reassembling from the persisted pages seeds the page-stream live-set
	 * baseline, so a later flush emits only the actual delta.
	 */
	@Nonnull
	private static HistogramIndex reloadThroughLoader(
		@Nonnull OffsetIndex offsetIndex,
		@Nonnull Set<HistogramIndexStorageKey> manifestKeys,
		long catalogVersion,
		@Nonnull String histogramName
	) {
		return reloadThroughLoader(offsetIndex, manifestKeys, catalogVersion, histogramName, false);
	}

	/**
	 * Reloads a histogram through the production loader, optionally presenting every histogram root as one a
	 * backward-compatible serializer decoded — which is what routes the legacy threshold rescale.
	 *
	 * @param offsetIndex                     the store the loader reads from
	 * @param manifestKeys                    the histogram sub-index keys the manifest advertises
	 * @param catalogVersion                  the catalog version to read at
	 * @param histogramName                   the histogram the caller wants back
	 * @param markSecondGranularityThresholds whether the roots are presented as second-granularity
	 * @return the reconstructed histogram
	 */
	@Nonnull
	private static HistogramIndex reloadThroughLoader(
		@Nonnull OffsetIndex offsetIndex,
		@Nonnull Set<HistogramIndexStorageKey> manifestKeys,
		long catalogVersion,
		@Nonnull String histogramName,
		boolean markSecondGranularityThresholds
	) {
		final StoragePartPersistenceService<StorageDescriptor> service =
			new OffsetIndexReadService(offsetIndex, markSecondGranularityThresholds);
		final LoadedComponentBundle bundle = new HistogramIndexMapLoader().load(
			loadContext(service, manifestKeys, catalogVersion)
		);
		final Histograms histograms = assertInstanceOf(
			Histograms.class, bundle, "the loader must return a Histograms bundle"
		);
		final HistogramIndex restored = histograms.histogramIndexes().get(histogramName);
		assertNotNull(restored, "the loader must reconstruct the '" + histogramName + "' histogram");
		return restored;
	}

	@Nonnull
	private static List<StoragePart> emit(@Nonnull HistogramIndex index) {
		final TrappedChanges trappedChanges = new TrappedChanges();
		index.getModifiedStorageParts(ENTITY_INDEX_PK, trappedChanges);
		final List<StoragePart> parts = new ArrayList<>();
		final Iterator<StoragePart> iterator = trappedChanges.getTrappedChangesIterator();
		while (iterator.hasNext()) {
			parts.add(iterator.next());
		}
		return parts;
	}

	@Nonnull
	private static List<StoragePart> stripRemovals(@Nonnull List<StoragePart> parts) {
		final List<StoragePart> result = new ArrayList<>(parts.size());
		for (final StoragePart part : parts) {
			if (!(part instanceof DeferredRemovalStoragePart)) {
				result.add(part);
			}
		}
		return result;
	}

	@Nonnull
	private static List<HistogramIndexLeafPagePart> leafPages(@Nonnull List<StoragePart> parts) {
		final List<HistogramIndexLeafPagePart> result = new ArrayList<>();
		for (final StoragePart part : parts) {
			if (part instanceof HistogramIndexLeafPagePart leafPage) {
				result.add(leafPage);
			}
		}
		return result;
	}

	@Nonnull
	private static List<HistogramIndexLeafPagePart> leafPagesOfLocale(
		@Nonnull List<HistogramIndexLeafPagePart> leafPages, @Nonnull Locale locale
	) {
		final List<HistogramIndexLeafPagePart> result = new ArrayList<>();
		for (final HistogramIndexLeafPagePart leafPage : leafPages) {
			if (locale.equals(leafPage.getLocale())) {
				result.add(leafPage);
			}
		}
		return result;
	}

	@Nonnull
	private static List<HistogramRangeIndexLeafPagePart> rangeLeafPages(@Nonnull List<StoragePart> parts) {
		final List<HistogramRangeIndexLeafPagePart> result = new ArrayList<>();
		for (final StoragePart part : parts) {
			if (part instanceof HistogramRangeIndexLeafPagePart rangeLeafPage) {
				result.add(rangeLeafPage);
			}
		}
		return result;
	}

	@Nonnull
	private static List<HistogramIndexLeafPageRemoval> leafPageRemovals(@Nonnull List<StoragePart> parts) {
		final List<HistogramIndexLeafPageRemoval> result = new ArrayList<>();
		for (final StoragePart part : parts) {
			if (part instanceof HistogramIndexLeafPageRemoval removal) {
				result.add(removal);
			}
		}
		return result;
	}

	@Nonnull
	private static List<HistogramCardinalityStoragePart> cardinalitySiblings(@Nonnull List<StoragePart> parts) {
		final List<HistogramCardinalityStoragePart> result = new ArrayList<>();
		for (final StoragePart part : parts) {
			if (part instanceof HistogramCardinalityStoragePart sibling) {
				result.add(sibling);
			}
		}
		return result;
	}

	@Nullable
	private static HistogramCardinalityStoragePart cardinalitySibling(@Nonnull List<StoragePart> parts) {
		final List<HistogramCardinalityStoragePart> siblings = cardinalitySiblings(parts);
		return siblings.isEmpty() ? null : siblings.get(0);
	}

	@Nonnull
	private static List<HistogramIndexStoragePart> histogramRoots(@Nonnull List<StoragePart> parts) {
		final List<HistogramIndexStoragePart> result = new ArrayList<>();
		for (final StoragePart part : parts) {
			if (part instanceof HistogramIndexStoragePart root) {
				result.add(root);
			}
		}
		return result;
	}

	@Nonnull
	private static HistogramIndexStoragePart histogramRoot(@Nonnull List<StoragePart> parts) {
		final List<HistogramIndexStoragePart> roots = histogramRoots(parts);
		if (roots.isEmpty()) {
			throw new IllegalStateException("The emission carries no HistogramIndexStoragePart root!");
		}
		return roots.get(0);
	}

	@Nonnull
	private static Set<Locale> localesOf(@Nonnull List<HistogramIndexStoragePart> roots) {
		final Set<Locale> result = new HashSet<>(roots.size());
		for (final HistogramIndexStoragePart root : roots) {
			result.add(root.getLocale());
		}
		return result;
	}

	/**
	 * Writes a flush emission into the given (open) offset index at the given catalog version: regular parts are
	 * `put`, while each {@link DeferredRemovalStoragePart} resolves its store-side primary key against the live
	 * read-only key compressor and is `remove`d — exactly what the production flush drain does.
	 */
	private static void writeEmission(
		@Nonnull OffsetIndex offsetIndex, long catalogVersion, @Nonnull List<StoragePart> parts
	) {
		for (final StoragePart part : parts) {
			if (part instanceof DeferredRemovalStoragePart deferredRemoval) {
				final long removedPartPK =
					deferredRemoval.computeUniquePartIdAndSet(offsetIndex.getReadOnlyKeyCompressor());
				offsetIndex.remove(catalogVersion, removedPartPK, deferredRemoval.removedContainerType());
			} else {
				offsetIndex.put(catalogVersion, part);
			}
		}
	}

	@Nonnull
	private OffsetIndex openWritableOffsetIndex() {
		return new OffsetIndex(
			0L,
			new OffsetIndexDescriptor(new EntityCollectionFileHeader(ENTITY_TYPE, 1, 0), createKryo(), 1.0, 0L),
			this.storageSettings.outputBufferSize(),
			this.storageSettings.maxOpenedReadHandlesOrDefault(),
			this.storageSettings.lockTimeoutSeconds(),
			this.storageSettings.waitOnCloseSeconds(),
			this.storageSettings,
			this.storageSettings,
			this.recordRegistry,
			createWriteHandle(),
			NO_OP_NON_FLUSHED_BLOCK_CALLBACK,
			NO_OP_OLDEST_RECORD_CALLBACK
		);
	}

	@Nonnull
	private OffsetIndexDescriptor persist(@Nonnull List<StoragePart> parts) {
		final OffsetIndex offsetIndex = openWritableOffsetIndex();
		try {
			writeEmission(offsetIndex, PERSISTED_VERSION, parts);
			return offsetIndex.flush(PERSISTED_VERSION);
		} finally {
			IOUtils.closeQuietly(offsetIndex::close);
		}
	}

	@Nonnull
	private OffsetIndex loadOffsetIndex(@Nonnull OffsetIndexDescriptor descriptor, long catalogVersion) {
		return new OffsetIndex(
			catalogVersion,
			new OffsetIndexDescriptor(
				new FileLocationAndWrittenBytes(descriptor.fileLocation(), 0),
				descriptor,
				1.0,
				descriptor.getFileSize()
			),
			this.storageSettings.outputBufferSize(),
			this.storageSettings.maxOpenedReadHandlesOrDefault(),
			this.storageSettings.lockTimeoutSeconds(),
			this.storageSettings.waitOnCloseSeconds(),
			this.storageSettings,
			this.storageSettings,
			this.recordRegistry,
			createWriteHandle(),
			NO_OP_NON_FLUSHED_BLOCK_CALLBACK,
			NO_OP_OLDEST_RECORD_CALLBACK
		);
	}

	@Nonnull
	private WriteOnlyFileHandle createWriteHandle() {
		return new WriteOnlyFileHandle(
			this.targetFile,
			this.storageSettings.outputBufferSize(),
			this.storageSettings.syncWrites(),
			this.storageSettings,
			this.storageSettings,
			this.observableOutputKeeper
		);
	}

	@Nonnull
	private static Function<VersionedKryoKeyInputs, VersionedKryo> createKryo() {
		return keyInputs -> VersionedKryoFactory.createKryo(
			keyInputs.version(),
			SchemaKryoConfigurer.INSTANCE
				.andThen(CatalogHeaderKryoConfigurer.INSTANCE)
				.andThen(SharedClassesConfigurer.INSTANCE)
				.andThen(SharedIndexStoragePartConfigurer.INSTANCE)
				.andThen(new IndexStoragePartConfigurer(keyInputs.keyCompressor()))
		);
	}

	/**
	 * Thin READ-ONLY {@link StoragePartPersistenceService} over a real {@link OffsetIndex}: it forwards the only two
	 * methods the loader calls — {@link #getStoragePart} and {@link #getReadOnlyKeyCompressor} — straight to the real
	 * store and fails loudly on everything else.
	 *
	 * `markSecondGranularityThresholds` reproduces the one thing a round trip through the CURRENT serializer cannot
	 * produce: the read-path provenance mark that says a part was decoded by a backward-compatible reader. Only such
	 * a reader ever sets it (see {@code HistogramIndexStoragePartSerializer_2026_2.read}), and the current writer has
	 * no way to persist it — so a legacy catalog is reproduced by writing legacy-scaled thresholds through the real
	 * store and stamping the mark here, on the same object and at the same point in the read the serializer would.
	 *
	 * @param offsetIndex                    the real store to read from
	 * @param markSecondGranularityThresholds whether every histogram root read is marked as carrying
	 *                                       second-granularity range thresholds
	 */
	private record OffsetIndexReadService(
		@Nonnull OffsetIndex offsetIndex, boolean markSecondGranularityThresholds
	) implements StoragePartPersistenceService<StorageDescriptor> {

		/**
		 * A plain read-only view: every part comes back exactly as the current serializer wrote it.
		 *
		 * @param offsetIndex the real store to read from
		 */
		private OffsetIndexReadService(@Nonnull OffsetIndex offsetIndex) {
			this(offsetIndex, false);
		}

		@Nullable
		@Override
		public <T extends StoragePart> T getStoragePart(
			long catalogVersion, long storagePartPk, @Nonnull Class<T> containerType
		) {
			final T part = this.offsetIndex.get(catalogVersion, storagePartPk, containerType);
			if (this.markSecondGranularityThresholds && part instanceof HistogramIndexStoragePart root) {
				root.setSecondGranularityRangeThresholds(true);
			}
			return part;
		}

		@Nonnull
		@Override
		public KeyCompressor getReadOnlyKeyCompressor() {
			return this.offsetIndex.getReadOnlyKeyCompressor();
		}

		// --- the loader never calls anything below; fail loudly if that changes -------------------

		@Nonnull
		@Override
		public StoragePartPersistenceService<StorageDescriptor> createTransactionalService(@Nonnull UUID transactionId) {
			throw new UnsupportedOperationException();
		}

		@Nullable
		@Override
		public <T extends StoragePart> byte[] getStoragePartAsBinary(
			long catalogVersion, long storagePartPk, @Nonnull Class<T> containerType
		) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T extends StoragePart> long putStoragePart(long catalogVersion, @Nonnull T container) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T extends StoragePart> boolean removeStoragePart(
			long catalogVersion, long storagePartPk, @Nonnull Class<T> containerType
		) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T extends StoragePart> boolean containsStoragePart(
			long catalogVersion, long primaryKey, @Nonnull Class<T> containerType
		) {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public <T extends StoragePart> Stream<T> getEntryStream(@Nonnull Class<T> containerType) {
			throw new UnsupportedOperationException();
		}

		@Override
		public int countStorageParts(long catalogVersion) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T extends StoragePart> int countStorageParts(long catalogVersion, @Nonnull Class<T> containerType) {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public <T extends StoragePart> byte[] serializeStoragePart(@Nonnull T storagePart) {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public <T extends StoragePart> T deserializeStoragePart(
			@Nonnull byte[] storagePart, @Nonnull Class<T> containerType
		) {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public KeyCompressorSnapshot getKeyCompressorSnapshot() {
			throw new UnsupportedOperationException();
		}

		@Override
		public long getVersion() {
			throw new UnsupportedOperationException();
		}

		@Override
		public void forgetVolatileData() {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public StorageDescriptor flush(long catalogVersion) {
			throw new UnsupportedOperationException();
		}

		@Nonnull
		@Override
		public StorageDescriptor copySnapshotTo(
			long catalogVersion, @Nonnull OutputStream outputStream,
			@Nullable IntConsumer progressConsumer, @Nullable StoragePart... updatedStorageParts
		) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void purgeHistoryOlderThan(long lastKnownMinimalActiveVersion) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isNew() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isClosed() {
			throw new UnsupportedOperationException();
		}

		@Override
		public void close() {
			throw new UnsupportedOperationException();
		}
	}
}
