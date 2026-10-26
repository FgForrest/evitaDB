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

package io.evitadb.index.attribute;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.dataType.ComparableLocale;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.function.Functions;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.spi.store.catalog.persistence.storageParts.DeferredRemovalStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AbstractLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.LeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.SortIndexStoragePart;
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
import io.evitadb.utils.CollectionUtils;
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
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the rewrite the storage migration to protocol 7 performs on an owner sort index release 2026.2 or an older
 * one persisted - its parts written as the owner used to write them: with the raw `OffsetDateTime`, `LocalDateTime` and
 * `LocalTime` values, with a localized `String` element ordered by the bare collator, with a `Locale` element ordered
 * by its language tag alone, and with a `DateTimeRange` element ordered at whole seconds, an open bound derived from
 * the zone offset of the other one. The rewrite emits parts holding only normalized values in the order of the
 * current comparator, and those parts, stored and loaded again by the load path - which corrects nothing - hold the
 * records exactly as an index the current engine wrote from the same values. A paged index is rewritten whole: every
 * legacy leaf page is removed, and no rewritten page takes the identity of a legacy one. An index already in the
 * current form and order is left as it is - a bucket in which the release merged two values its comparator equated
 * included.
 *
 * The legacy parts are produced by {@link LegacyOwnerSortIndexWriter}, a test-only replica of the old writer, and pass
 * through the real Kryo serializers and the real offset index; the rewrite is
 * {@link LegacyOwnerSortIndexCorrection#rewrite}, the very call the migration makes, and its emission is applied the
 * way the migration drains it into a collection data store.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Owner sort indexes of 2026.2 are rewritten in the current form and order by the storage migration")
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(ORDER)
class LegacyOwnerSortIndexCorrectionTest {
	private static final String ENTITY_TYPE = "product";
	private static final int ENTITY_INDEX_PK = 7;
	private static final AttributeIndexKey ATTRIBUTE_KEY = new AttributeIndexKey(null, "moment", null);
	/** A localized attribute - a `String` element of its sort index is compared by the collator of its locale. */
	private static final AttributeIndexKey LOCALIZED_KEY = new AttributeIndexKey(null, "label", Locale.ENGLISH);
	/** The same attribute, not localized - a `String` element of its sort index is compared by `String#compareTo`. */
	private static final AttributeIndexKey NON_LOCALIZED_KEY = new AttributeIndexKey(null, "label", null);
	/** An attribute holding a `Locale` - not localized, as a `Locale` value needs no locale of its own. */
	private static final AttributeIndexKey LANGUAGE_KEY = new AttributeIndexKey(null, "language", null);
	/** An attribute holding a `DateTimeRange`, not localized. */
	private static final AttributeIndexKey PERIOD_KEY = new AttributeIndexKey(null, "period", null);
	/** A range closed at both ends, after every range of the scenarios open at its start - in either release. */
	private static final DateTimeRange CLOSED_RANGE = DateTimeRange.between(
		OffsetDateTime.parse("2025-01-01T00:00:00Z"), OffsetDateTime.parse("2025-01-02T00:00:00Z")
	);
	/** A variant the language tag drops, as it does every ill-formed one. */
	private static final String ILL_FORMED_VARIANT = "ill!formed";
	/**
	 * `en-US` with an ill-formed variant: its language tag is `en-US`, as that of {@link Locale#US}, by which release
	 * 2026.2 equated the two; the current index orders {@link Locale#US} first.
	 */
	private static final Locale ILL_FORMED_US = Locale.of("en", "US", ILL_FORMED_VARIANT);
	/** A plain two-letter text. */
	private static final String PLAIN = "ab";
	/**
	 * The same text with a zero-width space between its letters: the collator of the locale ignores the space and
	 * equates the two, the current index orders the plain one first.
	 */
	private static final String SPACED = "a\u200Bb";
	private static final long LEGACY_VERSION = 1L;
	private static final long REWRITTEN_VERSION = 2L;
	/** Enough raw values to spread the legacy owner tree over several 256-entry leaf pages. */
	private static final int PAGED_VALUE_COUNT = 600;
	/** 09:00Z of the first day the scenarios use. */
	private static final Instant NINE = Instant.parse("2024-01-01T09:00:00Z");
	private static final ZoneOffset PLUS_ONE = ZoneOffset.ofHours(1);
	private static final ZoneOffset PLUS_TWO = ZoneOffset.ofHours(2);
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
		this.targetFile = Files.createTempFile("legacyOwnerSortIndexCorrection", ".kryo");
	}

	@AfterEach
	void tearDown() {
		this.observableOutputKeeper.close();
		//noinspection ResultOfMethodCallIgnored
		this.targetFile.toFile().delete();
	}

	@Nested
	@DisplayName("Inline part")
	class InlinePart {

		@Test
		@DisplayName("merges one moment written at two offsets and two moments differing below the millisecond")
		void shouldMergeCollidingOffsetDateTimes() {
			final ComparatorSource[] base = single(OffsetDateTime.class);
			// raw order: 08:00Z (7), 09:00Z@+01 (5, 9), 09:00Z@+02 (2), 10:00:00.0005Z (4), 10:00:00.0009Z (1, 3)
			final LegacyOwnerSortIndexWriter legacy = legacy(base)
				.add(NINE.minusSeconds(3600).atOffset(ZoneOffset.UTC), 7)
				.add(NINE.atOffset(PLUS_ONE), 5, 9)
				.add(NINE.atOffset(PLUS_TWO), 2)
				.add(NINE.plusSeconds(3600).plusNanos(500_000).atOffset(ZoneOffset.UTC), 4)
				.add(NINE.plusSeconds(3600).plusNanos(900_000).atOffset(ZoneOffset.UTC), 1, 3);

			final OwnerSortIndex rewritten = roundTripInline(
				legacy, legacy.toCurrentOwner(),
				// the raw value of either offset finds the merged block
				index -> {
					index.removeRecord(NINE.atOffset(PLUS_TWO), 5);
					index.addRecord(NINE.atOffset(PLUS_ONE), 6);
				}
			);
			assertArrayEquals(new int[]{7, 2, 6, 9, 1, 3, 4}, rewritten.getSortedRecords());
			assertArrayEquals(
				new Serializable[]{NINE.minusSeconds(3600), NINE, NINE.plusSeconds(3600)},
				rewritten.getSortedRecordValues()
			);
		}

		@Test
		@DisplayName("normalizes local date times to instants and merges local times differing below the millisecond")
		void shouldNormalizeLocalTemporalValues() {
			final LocalDateTime ten = LocalDateTime.parse("2024-01-01T10:00:00");
			final ComparatorSource[] dateTimeBase = single(LocalDateTime.class);
			final LegacyOwnerSortIndexWriter dateTimes = legacy(dateTimeBase)
				.add(ten.plusNanos(100_000), 3)
				.add(ten.plusNanos(200_000), 1)
				.add(ten.plusSeconds(1), 2);
			final OwnerSortIndex rewrittenDateTimes = roundTripInline(
				dateTimes, dateTimes.toCurrentOwner(), index -> index.addRecord(ten.plusSeconds(2), 4)
			);
			assertArrayEquals(new int[]{1, 3, 2, 4}, rewrittenDateTimes.getSortedRecords());
			assertArrayEquals(
				new Serializable[]{
					ten.toInstant(ZoneOffset.UTC), ten.plusSeconds(1).toInstant(ZoneOffset.UTC),
					ten.plusSeconds(2).toInstant(ZoneOffset.UTC)
				},
				rewrittenDateTimes.getSortedRecordValues()
			);

			final ComparatorSource[] timeBase = single(LocalTime.class);
			final LegacyOwnerSortIndexWriter times = legacy(timeBase)
				.add(ten.toLocalTime().plusNanos(100_000), 3)
				.add(ten.toLocalTime().plusNanos(200_000), 1)
				.add(ten.toLocalTime().plusSeconds(1), 2);
			final OwnerSortIndex rewrittenTimes = roundTripInline(
				times, times.toCurrentOwner(), index -> index.addRecord(ten.toLocalTime().plusSeconds(2), 4)
			);
			assertArrayEquals(new int[]{1, 3, 2, 4}, rewrittenTimes.getSortedRecords());
			assertArrayEquals(
				new Serializable[]{
					ten.toLocalTime(), ten.toLocalTime().plusSeconds(1), ten.toLocalTime().plusSeconds(2)
				},
				rewrittenTimes.getSortedRecordValues()
			);
		}

		@Test
		@DisplayName("re-sorts a compound whose temporal element ties only after it is normalized")
		void shouldResortCompoundWhoseTemporalElementTiesAfterNormalization() {
			final ComparatorSource[] base = {
				new ComparatorSource(OffsetDateTime.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
				new ComparatorSource(Integer.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
			};
			// raw order follows the local time of the moment: (@+01, 5), (@+01, 7), (@+02, 3), (@+02, 7), (09:30Z, 1)
			final LegacyOwnerSortIndexWriter legacy = legacy(base)
				.add(new ComparableArray(new Serializable[]{NINE.atOffset(PLUS_ONE), 5}), 1)
				.add(new ComparableArray(new Serializable[]{NINE.atOffset(PLUS_ONE), 7}), 4)
				.add(new ComparableArray(new Serializable[]{NINE.atOffset(PLUS_TWO), 3}), 2)
				.add(new ComparableArray(new Serializable[]{NINE.atOffset(PLUS_TWO), 7}), 5)
				.add(new ComparableArray(new Serializable[]{NINE.plusSeconds(1800).atOffset(ZoneOffset.UTC), 1}), 3);

			// normalized: (09:00Z, 3), (09:00Z, 5), (09:00Z, 7) merged, (09:30Z, 1)
			final OwnerSortIndex expected = legacy.toCurrentOwner();
			assertArrayEquals(new int[]{2, 1, 4, 5, 3}, expected.getSortedRecords());
			final OwnerSortIndex rewritten = roundTripInline(
				legacy, expected,
				index -> index.addRecord(new Serializable[]{NINE.atOffset(ZoneOffset.UTC), 6}, 6)
			);
			assertArrayEquals(new int[]{2, 1, 6, 4, 5, 3}, rewritten.getSortedRecords());
		}

		@Test
		@DisplayName("leaves an index without raw temporal values as it is")
		void shouldLeaveNormalizedIndexUntouched() {
			final ComparatorSource[] base = single(OffsetDateTime.class);
			final OwnerSortIndex current = new OwnerSortIndex(OffsetDateTime.class, ATTRIBUTE_KEY);
			current.addRecord(NINE.atOffset(PLUS_ONE), 2);
			current.addRecord(NINE.atOffset(PLUS_TWO), 1);
			final SortIndexStoragePart part = root(emit(current));

			final TrappedChanges sink = new TrappedChanges();
			assertFalse(
				LegacyOwnerSortIndexCorrection.rewrite(part, null, sink),
				"an index holding normalized values only must not be rewritten"
			);
			assertEquals(0, sink.getTrappedChangesCount(), "nothing may be emitted for it");
			assertArrayEquals(new int[]{1, 2}, loadInline(part).getSortedRecords());
			assertFalse(LegacyOwnerSortIndexCorrection.isApplicable(single(Integer.class), null));
			assertTrue(LegacyOwnerSortIndexCorrection.isApplicable(base, null));
		}
	}

	@Nested
	@DisplayName("Paged part")
	class PagedPart {

		@Test
		@DisplayName("merges colliding moments across leaf pages and rewrites every page")
		void shouldMergeCollidingMomentsAcrossLeafPages() {
			final ComparatorSource[] base = single(OffsetDateTime.class);
			// pairs of one moment written at two offsets - the +01:00 one sorts first raw, but holds the larger record
			final LegacyOwnerSortIndexWriter legacy = legacy(base);
			for (int i = 0; i < PAGED_VALUE_COUNT; i++) {
				legacy.add(NINE.plusSeconds(i / 2).atOffset(i % 2 == 0 ? PLUS_ONE : PLUS_TWO), 10_000 - i);
			}

			assertRoundTripPaged(
				legacy, legacy.toCurrentOwner(), index -> index.addRecord(NINE.minusSeconds(1).atOffset(PLUS_ONE), 1)
			);
		}

		@Test
		@DisplayName("rewrites every page of an index whose values change form without any collision")
		void shouldRewriteEveryPageWhenValuesChangeFormWithoutCollision() {
			final ComparatorSource[] base = single(LocalDateTime.class);
			final LocalDateTime ten = LocalDateTime.parse("2024-01-01T10:00:00");
			final LegacyOwnerSortIndexWriter legacy = legacy(base);
			for (int i = 0; i < PAGED_VALUE_COUNT; i++) {
				legacy.add(ten.plusSeconds(i), i + 1);
			}

			assertRoundTripPaged(
				legacy, legacy.toCurrentOwner(), index -> index.addRecord(ten.minusSeconds(1), 100_000)
			);
		}

		@Test
		@DisplayName("re-sorts a compound whose order the normalization inverts across a leaf page boundary")
		void shouldResortInvertedCompoundAcrossLeafPageBoundary() {
			final ComparatorSource[] base = {
				new ComparatorSource(OffsetDateTime.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
				new ComparatorSource(Integer.class, OrderDirection.DESC, OrderBehaviour.NULLS_LAST)
			};
			// groups of seven values share one moment, written at offsets +00:00 to +06:00: raw, the local time orders
			// a group by its offset; normalized, the moments tie and the descending integer orders it the other way
			final LegacyOwnerSortIndexWriter legacy = legacy(base);
			for (int i = 0; i < PAGED_VALUE_COUNT; i++) {
				legacy.add(
					new ComparableArray(
						new Serializable[]{NINE.plusSeconds(i / 7).atOffset(ZoneOffset.ofHours(i % 7)), i}
					),
					i + 1
				);
			}
			final List<StoragePart> legacyParts = legacy.toPagedParts(0);
			assertTrue(
				tieGroupStraddlesPageBoundary(legacyParts),
				"a group of moments tying after normalization must span two legacy pages"
			);

			assertRoundTripPaged(
				legacyParts, legacy.toCurrentOwner(),
				index -> index.addRecord(new Serializable[]{NINE.minusSeconds(1).atOffset(PLUS_ONE), -1}, 100_000)
			);
		}

		@Test
		@DisplayName("keeps the order of a compound whose temporal element comes last, rewriting every page")
		void shouldKeepOrderOfCompoundWithTemporalElementLast() {
			final ComparatorSource[] base = {
				new ComparatorSource(Integer.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
				new ComparatorSource(OffsetDateTime.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
			};
			// the integer decides every pair before the moment does - the normalization can change no comparison
			final LegacyOwnerSortIndexWriter legacy = legacy(base);
			for (int i = 0; i < PAGED_VALUE_COUNT; i++) {
				legacy.add(
					new ComparableArray(
						new Serializable[]{i / 2, NINE.plusSeconds(i % 2).atOffset(i % 2 == 0 ? PLUS_TWO : PLUS_ONE)}
					),
					PAGED_VALUE_COUNT - i
				);
			}
			final List<StoragePart> legacyParts = legacy.toPagedParts(0);
			final int[] legacyOrder = new int[PAGED_VALUE_COUNT];
			for (int i = 0; i < PAGED_VALUE_COUNT; i++) {
				legacyOrder[i] = PAGED_VALUE_COUNT - i;
			}
			final OwnerSortIndex expected = legacy.toCurrentOwner();
			assertArrayEquals(legacyOrder, expected.getSortedRecords(), "the normalization must not reorder anything");

			assertRoundTripPaged(
				legacyParts, expected,
				index -> index.addRecord(new Serializable[]{-1, NINE.atOffset(PLUS_ONE)}, 100_000)
			);
		}

		@Test
		@DisplayName("collapses a paged index whose values all fall within one millisecond into one inline part")
		void shouldCollapsePagedIndexIntoInlinePart() {
			final ComparatorSource[] base = single(LocalTime.class);
			final LocalTime ten = LocalTime.of(10, 0);
			// distinct raw values a microsecond apart span several leaf pages, and all normalize onto 10:00:00.000
			final LegacyOwnerSortIndexWriter legacy = legacy(base);
			for (int i = 0; i < PAGED_VALUE_COUNT; i++) {
				legacy.add(ten.plusNanos(i * 1_000L), i + 1);
			}
			final OwnerSortIndex expected = legacy.toCurrentOwner();
			assertArrayEquals(new Serializable[]{ten}, expected.getSortedRecordValues(), "one bucket is expected");

			final OffsetIndex offsetIndex = openWritableOffsetIndex();
			final OffsetIndexDescriptor rewrittenDescriptor;
			final Set<Long> legacyPages = new HashSet<>();
			try {
				writeEmission(offsetIndex, LEGACY_VERSION, legacy.toPagedParts(0));
				offsetIndex.flush(LEGACY_VERSION);
				final SortIndexStoragePart legacyRoot = readRoot(offsetIndex, LEGACY_VERSION, ATTRIBUTE_KEY);
				assertTrue(legacyRoot.isPaged(), "the legacy part must be paged");
				assertTrue(legacyRoot.getLeafPageSequences().length >= 2, "the legacy index must span several pages");
				final int legacyStreamId = streamId(offsetIndex, ATTRIBUTE_KEY);
				for (int sequence : legacyRoot.getLeafPageSequences()) {
					legacyPages.add(AbstractLeafPagePart.computeUniquePartId(legacyStreamId, sequence));
				}

				final List<StoragePart> emission = rewrite(
					legacyRoot, readPages(offsetIndex, LEGACY_VERSION, legacyRoot)
				);
				assertFalse(root(emission).isPaged(), "the rewritten index must fall back to one inline part");
				final Set<Long> removedPages = new HashSet<>();
				for (StoragePart part : emission) {
					assertFalse(part instanceof SortIndexLeafPagePart, "an inline index writes no leaf page: " + part);
					if (part instanceof DeferredRemovalStoragePart removal) {
						removedPages.add(removal.computeUniquePartIdAndSet(offsetIndex.getReadOnlyKeyCompressor()));
					}
				}
				assertEquals(legacyPages, removedPages, "every legacy page, and nothing else, must be removed");
				writeEmission(offsetIndex, REWRITTEN_VERSION, emission);
				rewrittenDescriptor = offsetIndex.flush(REWRITTEN_VERSION);
			} finally {
				IOUtils.closeQuietly(offsetIndex::close);
			}

			final OffsetIndex reopened = loadOffsetIndex(rewrittenDescriptor, REWRITTEN_VERSION);
			try {
				for (long pagePrimaryKey : legacyPages) {
					assertNull(
						reopened.get(REWRITTEN_VERSION, pagePrimaryKey, SortIndexLeafPagePart.class),
						"legacy page " + pagePrimaryKey + " must be removed by the rewrite"
					);
				}
				final SortIndexStoragePart rewrittenRoot = readRoot(reopened, REWRITTEN_VERSION, ATTRIBUTE_KEY);
				assertFalse(rewrittenRoot.isPaged(), "the stored root must be inline");
				final OwnerSortIndex reloaded = loadInline(rewrittenRoot);
				assertEquals(PAGED_VALUE_COUNT, reloaded.getSortedRecords().length, "every record must be kept");
				assertSameSortIndex(expected, reloaded, "the collapsed inline part");
			} finally {
				IOUtils.closeQuietly(reopened::close);
			}
		}

		@Test
		@DisplayName("refuses a stale leaf page twin listed after the pages it overlaps instead of merging it")
		void shouldRefuseStaleLeafPageTwin() {
			final ComparatorSource[] base = single(OffsetDateTime.class);
			final LegacyOwnerSortIndexWriter legacy = legacy(base);
			for (int i = 0; i < PAGED_VALUE_COUNT; i++) {
				legacy.add(NINE.plusSeconds(i).atOffset(PLUS_ONE), i + 1);
			}
			final List<StoragePart> parts = legacy.toPagedParts(0);
			final SortIndexStoragePart persistedRoot = root(parts);
			final int[] pages = persistedRoot.getLeafPageSequences();
			// an older copy of the first page under a sequence of its own, listed last by the root
			SortIndexLeafPagePart firstPage = null;
			for (StoragePart part : parts) {
				if (part instanceof SortIndexLeafPagePart page && page.getPageSequence() == pages[0]) {
					firstPage = page;
				}
			}
			assertNotNull(firstPage);
			final int twinSequence = persistedRoot.getHighWaterPageSequence() + 1;
			final int[] pagesWithTwin = Arrays.copyOf(pages, pages.length + 1);
			pagesWithTwin[pages.length] = twinSequence;
			final List<StoragePart> corruptedParts = new ArrayList<>(parts);
			corruptedParts.remove(persistedRoot);
			corruptedParts.add(
				new SortIndexLeafPagePart(
					ENTITY_INDEX_PK, new AttributeKeyWithIndexType(ATTRIBUTE_KEY, AttributeIndexType.SORT),
					twinSequence, firstPage.getBuckets(), 1
				)
			);
			corruptedParts.add(
				SortIndexStoragePart.paged(ENTITY_INDEX_PK, ATTRIBUTE_KEY, base, 0, twinSequence, pagesWithTwin)
			);

			assertRefusedAsCorruption(corruptedParts);
		}

		@Test
		@DisplayName("refuses legacy pages whose persisted order is not ascending instead of merging them")
		void shouldRefuseLegacyPagesWhosePersistedOrderIsNotAscending() {
			final ComparatorSource[] base = single(OffsetDateTime.class);
			final LegacyOwnerSortIndexWriter legacy = legacy(base);
			for (int i = 0; i < PAGED_VALUE_COUNT; i++) {
				legacy.add(NINE.plusSeconds(i).atOffset(PLUS_ONE), i + 1);
			}
			final List<StoragePart> parts = legacy.toPagedParts(0);
			final SortIndexStoragePart persistedRoot = root(parts);
			final int[] pages = persistedRoot.getLeafPageSequences();
			// list the first two pages the other way round - a root no writer produces
			final int[] swappedPages = pages.clone();
			swappedPages[0] = pages[1];
			swappedPages[1] = pages[0];
			final List<StoragePart> corruptedParts = new ArrayList<>(parts);
			corruptedParts.remove(persistedRoot);
			corruptedParts.add(
				SortIndexStoragePart.paged(
					ENTITY_INDEX_PK, ATTRIBUTE_KEY, base, 0, persistedRoot.getHighWaterPageSequence(), swappedPages
				)
			);

			assertRefusedAsCorruption(corruptedParts);
		}
	}

	@Nested
	@DisplayName("Localized text ordered by the bare collator")
	class LocalizedText {

		@Test
		@DisplayName("re-sorts an inline compound whose texts the collator equates and the next element orders")
		void shouldResortInlineCompoundOutOfTheCurrentOrder() {
			final ComparatorSource[] base = textAndInteger();
			// the release ordered (SPACED, 1) before (PLAIN, 2), the collator tying the texts; the current index
			// orders the plain text first
			final LegacyOwnerSortIndexWriter legacy = legacy(LOCALIZED_KEY, base)
				.add(new ComparableArray(new Serializable[]{SPACED, 1}), 1)
				.add(new ComparableArray(new Serializable[]{PLAIN, 2}), 2)
				.add(new ComparableArray(new Serializable[]{"ac", 0}), 3);
			assertArrayEquals(new int[]{1, 2, 3}, legacy.toInlinePart().getSortedRecords(), "the released order");
			final OwnerSortIndex expected = legacy.toCurrentOwner();
			assertArrayEquals(new int[]{2, 1, 3}, expected.getSortedRecords(), "the current order");

			final OwnerSortIndex rewritten = roundTripInline(
				legacy, expected, index -> index.addRecord(new Serializable[]{PLAIN, 1}, 4)
			);
			assertArrayEquals(new int[]{4, 2, 1, 3}, rewritten.getSortedRecords());
		}

		@Test
		@DisplayName("keeps a bucket the release merged exactly as merged while it re-sorts the index around it")
		void shouldKeepAMergedBucketWhileResortingAroundIt() {
			final ComparatorSource[] base = textAndInteger();
			// records 1 and 2 held (PLAIN, 1) and (SPACED, 1): the release kept them in one bucket under the spelling
			// it met first; (SPACED, 0) it ordered first, the current index orders it after the merged bucket
			final LegacyOwnerSortIndexWriter legacy = legacy(LOCALIZED_KEY, base)
				.add(new ComparableArray(new Serializable[]{PLAIN, 1}), 1, 2)
				.add(new ComparableArray(new Serializable[]{SPACED, 0}), 3)
				.add(new ComparableArray(new Serializable[]{"ac", 0}), 4);
			assertArrayEquals(new int[]{3, 1, 2, 4}, legacy.toInlinePart().getSortedRecords(), "the released order");
			final OwnerSortIndex expected = legacy.toCurrentOwner();
			assertArrayEquals(new int[]{1, 2, 3, 4}, expected.getSortedRecords(), "the current order");

			// the merged bucket is found under the spelling it was stored with, both its records in it
			final OwnerSortIndex rewritten = roundTripInline(
				legacy, expected, index -> index.removeRecord(new Serializable[]{PLAIN, 1}, 2)
			);
			assertArrayEquals(new int[]{1, 3, 4}, rewritten.getSortedRecords());
			assertArrayEquals(
				new Serializable[]{
					new ComparableArray(new Serializable[]{PLAIN, 1}),
					new ComparableArray(new Serializable[]{SPACED, 0}),
					new ComparableArray(new Serializable[]{"ac", 0})
				},
				rewritten.getSortedRecordValues()
			);
		}

		@Test
		@DisplayName("re-sorts a paged compound with no temporal element, rewriting every page")
		void shouldResortPagedCompoundWithoutATemporalElement() {
			final ComparatorSource[] base = textAndInteger();
			// pairs of the two spellings of one text: the release ordered each pair by its integer, the spaced one
			// first; the current index orders the plain one first
			final LegacyOwnerSortIndexWriter legacy = legacy(LOCALIZED_KEY, base);
			for (int i = 0; i < PAGED_VALUE_COUNT; i++) {
				legacy.add(
					new ComparableArray(new Serializable[]{textOf(i % 2 == 0 ? SPACED : PLAIN, i / 2), i}), i + 1
				);
			}

			assertRoundTripPaged(
				legacy, legacy.toCurrentOwner(), index -> index.addRecord(new Serializable[]{"aa", 0}, 100_000)
			);
		}

		@Test
		@DisplayName("rewrites a compound of a localized text and a raw moment as the release ordered it")
		void shouldRewriteTemporalAndLocalizedCompound() {
			final ComparatorSource[] base = {
				new ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
				new ComparatorSource(OffsetDateTime.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
			};
			// raw moments at +02:00, and pairs of the two spellings of one text the release ordered by the moment
			final LegacyOwnerSortIndexWriter legacy = legacy(LOCALIZED_KEY, base);
			for (int i = 0; i < PAGED_VALUE_COUNT; i++) {
				legacy.add(
					new ComparableArray(
						new Serializable[]{
							textOf(i % 2 == 0 ? SPACED : PLAIN, i / 2), NINE.plusSeconds(i).atOffset(PLUS_TWO)
						}
					),
					i + 1
				);
			}

			assertRoundTripPaged(
				legacy, legacy.toCurrentOwner(),
				index -> index.addRecord(new Serializable[]{"aa", NINE.atOffset(PLUS_ONE)}, 100_000)
			);
		}

		@Test
		@DisplayName("leaves a compound already in the current order as it is, inline and paged")
		void shouldLeaveCompoundInTheCurrentOrderAsItIs() {
			final ComparatorSource[] base = textAndInteger();
			// pairs of the two spellings of one text the release ordered by the integer the same way the current
			// index orders them: the plain one first
			final LegacyOwnerSortIndexWriter paged = legacy(LOCALIZED_KEY, base);
			for (int i = 0; i < PAGED_VALUE_COUNT; i++) {
				paged.add(
					new ComparableArray(new Serializable[]{textOf(i % 2 == 0 ? PLAIN : SPACED, i / 2), i}), i + 1
				);
			}
			final OwnerSortIndex pagedAsItIs = assertLeftAsItIs(paged.toPagedParts(0));
			assertSameSortIndex(paged.toCurrentOwner(), pagedAsItIs, "the paged index left as it is");

			final LegacyOwnerSortIndexWriter inline = legacy(LOCALIZED_KEY, base)
				.add(new ComparableArray(new Serializable[]{PLAIN, 1}), 1)
				.add(new ComparableArray(new Serializable[]{SPACED, 2}), 2);
			final OwnerSortIndex inlineAsItIs = assertLeftAsItIs(List.of(inline.toInlinePart()));
			assertSameSortIndex(inline.toCurrentOwner(), inlineAsItIs, "the inline index left as it is");
		}

		@Test
		@DisplayName("leaves a plain localized index holding a bucket the release merged as it is, inline and paged")
		void shouldLeavePlainIndexWithAMergedBucketAsItIs() {
			final ComparatorSource[] base = single(String.class);
			// records 1 and 2 held PLAIN and SPACED: the release kept them in one bucket, which does not say which of
			// them holds which spelling - it must stay exactly as it was stored
			final LegacyOwnerSortIndexWriter inline = legacy(LOCALIZED_KEY, base)
				.add(PLAIN, 1, 2)
				.add("ac", 3);
			final OwnerSortIndex inlineAsItIs = assertLeftAsItIs(List.of(inline.toInlinePart()));
			assertArrayEquals(new int[]{1, 2, 3}, inlineAsItIs.getSortedRecords());
			assertArrayEquals(new Serializable[]{PLAIN, "ac"}, inlineAsItIs.getSortedRecordValues());

			final LegacyOwnerSortIndexWriter paged = legacy(LOCALIZED_KEY, base).add(textOf(PLAIN, 0), 1, 2);
			for (int i = 1; i < PAGED_VALUE_COUNT; i++) {
				paged.add(textOf(PLAIN, i), i + 2);
			}
			final OwnerSortIndex pagedAsItIs = assertLeftAsItIs(paged.toPagedParts(0));
			assertArrayEquals(
				paged.getPersistedValues().toArray(Serializable[]::new), pagedAsItIs.getSortedRecordValues(),
				"every bucket must be stored as the release wrote it"
			);
			assertArrayEquals(new int[]{1, 2, 3}, Arrays.copyOf(pagedAsItIs.getSortedRecords(), 3));
		}

		@Test
		@DisplayName("refuses a stale leaf page twin of a compound with no temporal element instead of merging it")
		void shouldRefuseStaleLeafPageTwinOfACompoundWithoutATemporalElement() {
			final ComparatorSource[] base = textAndInteger();
			final LegacyOwnerSortIndexWriter legacy = legacy(LOCALIZED_KEY, base);
			for (int i = 0; i < PAGED_VALUE_COUNT; i++) {
				legacy.add(new ComparableArray(new Serializable[]{textOf(PLAIN, i), i}), i + 1);
			}
			final List<StoragePart> parts = legacy.toPagedParts(0);
			final SortIndexStoragePart persistedRoot = root(parts);
			final int[] pages = persistedRoot.getLeafPageSequences();
			// an older copy of the first page under a sequence of its own, listed last by the root
			SortIndexLeafPagePart firstPage = null;
			for (StoragePart part : parts) {
				if (part instanceof SortIndexLeafPagePart page && page.getPageSequence() == pages[0]) {
					firstPage = page;
				}
			}
			assertNotNull(firstPage);
			final int twinSequence = persistedRoot.getHighWaterPageSequence() + 1;
			final int[] pagesWithTwin = Arrays.copyOf(pages, pages.length + 1);
			pagesWithTwin[pages.length] = twinSequence;
			final List<StoragePart> corruptedParts = new ArrayList<>(parts);
			corruptedParts.remove(persistedRoot);
			corruptedParts.add(
				new SortIndexLeafPagePart(
					ENTITY_INDEX_PK, new AttributeKeyWithIndexType(LOCALIZED_KEY, AttributeIndexType.SORT),
					twinSequence, firstPage.getBuckets(), base.length
				)
			);
			corruptedParts.add(
				SortIndexStoragePart.paged(ENTITY_INDEX_PK, LOCALIZED_KEY, base, 0, twinSequence, pagesWithTwin)
			);

			assertRefusedAsCorruption(corruptedParts);
		}

		@Test
		@DisplayName("does not apply to a text that is not localized, which the release ordered as it does now")
		void shouldNotApplyToTextThatIsNotLocalized() {
			final ComparatorSource[] base = textAndInteger();
			assertFalse(LegacyOwnerSortIndexCorrection.isApplicable(base, null));
			assertFalse(LegacyOwnerSortIndexCorrection.isApplicable(single(String.class), null));
			assertTrue(LegacyOwnerSortIndexCorrection.isApplicable(base, Locale.ENGLISH));
			assertTrue(LegacyOwnerSortIndexCorrection.isApplicable(single(String.class), Locale.ENGLISH));
			assertFalse(LegacyOwnerSortIndexCorrection.isApplicable(single(Integer.class), Locale.ENGLISH));

			final SortIndexStoragePart part = legacy(NON_LOCALIZED_KEY, base)
				.add(new ComparableArray(new Serializable[]{"b", 1}), 1)
				.add(new ComparableArray(new Serializable[]{"a", 2}), 2)
				.toInlinePart();
			final TrappedChanges sink = new TrappedChanges();
			assertThrows(
				GenericEvitaInternalError.class, () -> LegacyOwnerSortIndexCorrection.rewrite(part, null, sink),
				"an index the rewrite does not apply to must be refused rather than rewritten"
			);
			assertEquals(0, sink.getTrappedChangesCount(), "nothing may be emitted for it");
		}
	}

	@Nested
	@DisplayName("Locale ordered by its language tag alone")
	class LocaleElement {

		@Test
		@DisplayName("applies to a Locale element, whether the index is localized or not")
		void shouldApplyToALocaleElement() {
			assertTrue(LegacyOwnerSortIndexCorrection.isApplicable(single(Locale.class), null));
			assertTrue(LegacyOwnerSortIndexCorrection.isApplicable(localeAndInteger(), null));
			assertTrue(LegacyOwnerSortIndexCorrection.isApplicable(localeAndInteger(), Locale.ENGLISH));
		}

		@Test
		@DisplayName("re-sorts an inline compound whose locales share a tag and the next element orders")
		void shouldResortInlineLocaleCompoundOutOfTheCurrentOrder() {
			final ComparatorSource[] base = localeAndInteger();
			// the release ordered (en-US with an ill-formed variant, 1) before (en-US, 2), the tag dropping the
			// variant; the current index orders the locale without the variant first
			final LegacyOwnerSortIndexWriter legacy = legacy(LANGUAGE_KEY, base)
				.add(new ComparableArray(new Serializable[]{new ComparableLocale(ILL_FORMED_US), 1}), 1)
				.add(new ComparableArray(new Serializable[]{new ComparableLocale(Locale.US), 2}), 2)
				.add(new ComparableArray(new Serializable[]{new ComparableLocale(Locale.GERMANY), 0}), 3);
			assertArrayEquals(new int[]{3, 1, 2}, legacy.toInlinePart().getSortedRecords(), "the released order");
			final OwnerSortIndex expected = legacy.toCurrentOwner();
			assertArrayEquals(new int[]{3, 2, 1}, expected.getSortedRecords(), "the current order");

			final OwnerSortIndex rewritten = roundTripInline(
				legacy, expected, index -> index.addRecord(new Serializable[]{Locale.US, 1}, 4)
			);
			assertArrayEquals(new int[]{3, 4, 2, 1}, rewritten.getSortedRecords());
		}

		@Test
		@DisplayName("re-sorts a paged compound whose locales share a language tag, rewriting every page")
		void shouldResortPagedLocaleCompound() {
			final ComparatorSource[] base = localeAndInteger();
			// pairs of one language tag, with and without an ill-formed variant: the release ordered each pair by its
			// integer, the variant first; the current index orders the locale without the variant first
			final LegacyOwnerSortIndexWriter legacy = legacy(LANGUAGE_KEY, base);
			for (int i = 0; i < PAGED_VALUE_COUNT; i++) {
				legacy.add(
					new ComparableArray(new Serializable[]{new ComparableLocale(languageOf(i / 2, i % 2 == 0)), i}),
					i + 1
				);
			}

			assertRoundTripPaged(
				legacy, legacy.toCurrentOwner(),
				index -> index.addRecord(new Serializable[]{Locale.GERMANY, 0}, 100_000)
			);
		}

		@Test
		@DisplayName("leaves a compound already in the current order as it is, inline and paged")
		void shouldLeaveLocaleCompoundInTheCurrentOrderAsItIs() {
			final ComparatorSource[] base = localeAndInteger();
			// pairs of one language tag the release ordered by the integer the same way the current index orders them:
			// the locale without the variant first
			final LegacyOwnerSortIndexWriter paged = legacy(LANGUAGE_KEY, base);
			for (int i = 0; i < PAGED_VALUE_COUNT; i++) {
				paged.add(
					new ComparableArray(new Serializable[]{new ComparableLocale(languageOf(i / 2, i % 2 == 1)), i}),
					i + 1
				);
			}
			final OwnerSortIndex pagedAsItIs = assertLeftAsItIs(paged.toPagedParts(0));
			assertSameSortIndex(paged.toCurrentOwner(), pagedAsItIs, "the paged index left as it is");

			final LegacyOwnerSortIndexWriter inline = legacy(LANGUAGE_KEY, base)
				.add(new ComparableArray(new Serializable[]{new ComparableLocale(Locale.US), 1}), 1)
				.add(new ComparableArray(new Serializable[]{new ComparableLocale(ILL_FORMED_US), 2}), 2);
			final OwnerSortIndex inlineAsItIs = assertLeftAsItIs(List.of(inline.toInlinePart()));
			assertSameSortIndex(inline.toCurrentOwner(), inlineAsItIs, "the inline index left as it is");
		}

		@Test
		@DisplayName("leaves a plain index holding a bucket the release merged as it is, inline and paged")
		void shouldLeavePlainLocaleIndexWithAMergedBucketAsItIs() {
			final ComparatorSource[] base = single(Locale.class);
			// records 1 and 2 held en-US with and without an ill-formed variant: the release kept them in one bucket
			// under the locale it met first, which does not say which record holds which - it must stay as stored
			final ComparableLocale illFormedUs = new ComparableLocale(ILL_FORMED_US);
			final ComparableLocale germany = new ComparableLocale(Locale.GERMANY);
			final LegacyOwnerSortIndexWriter inline = legacy(LANGUAGE_KEY, base)
				.add(illFormedUs, 1)
				.add(new ComparableLocale(Locale.US), 2)
				.add(germany, 3);
			assertEquals(List.of(germany, illFormedUs), inline.getPersistedValues(), "the release merged the pair");
			final OwnerSortIndex inlineAsItIs = assertLeftAsItIs(List.of(inline.toInlinePart()));
			assertArrayEquals(new int[]{3, 1, 2}, inlineAsItIs.getSortedRecords());
			assertArrayEquals(new Serializable[]{germany, illFormedUs}, inlineAsItIs.getSortedRecordValues());

			final ComparableLocale illFormedFirst = new ComparableLocale(languageOf(0, true));
			final LegacyOwnerSortIndexWriter paged = legacy(LANGUAGE_KEY, base)
				.add(illFormedFirst, 1)
				.add(new ComparableLocale(languageOf(0, false)), 2);
			for (int i = 1; i < PAGED_VALUE_COUNT; i++) {
				paged.add(new ComparableLocale(languageOf(i, false)), i + 2);
			}
			assertEquals(illFormedFirst, paged.getPersistedValues().getFirst(), "the release merged the pair");
			final OwnerSortIndex pagedAsItIs = assertLeftAsItIs(paged.toPagedParts(0));
			assertArrayEquals(
				paged.getPersistedValues().toArray(Serializable[]::new), pagedAsItIs.getSortedRecordValues(),
				"every bucket must be stored as the release wrote it"
			);
			assertArrayEquals(new int[]{1, 2, 3}, Arrays.copyOf(pagedAsItIs.getSortedRecords(), 3));
		}

		@Test
		@DisplayName("refuses a stale leaf page twin of a Locale compound instead of merging it")
		void shouldRefuseStaleLeafPageTwinOfALocaleCompound() {
			final ComparatorSource[] base = localeAndInteger();
			final LegacyOwnerSortIndexWriter legacy = legacy(LANGUAGE_KEY, base);
			for (int i = 0; i < PAGED_VALUE_COUNT; i++) {
				legacy.add(
					new ComparableArray(new Serializable[]{new ComparableLocale(languageOf(i, false)), i}), i + 1
				);
			}
			final List<StoragePart> parts = legacy.toPagedParts(0);
			final SortIndexStoragePart persistedRoot = root(parts);
			final int[] pages = persistedRoot.getLeafPageSequences();
			// an older copy of the first page under a sequence of its own, listed last by the root
			SortIndexLeafPagePart firstPage = null;
			for (StoragePart part : parts) {
				if (part instanceof SortIndexLeafPagePart page && page.getPageSequence() == pages[0]) {
					firstPage = page;
				}
			}
			assertNotNull(firstPage);
			final int twinSequence = persistedRoot.getHighWaterPageSequence() + 1;
			final int[] pagesWithTwin = Arrays.copyOf(pages, pages.length + 1);
			pagesWithTwin[pages.length] = twinSequence;
			final List<StoragePart> corruptedParts = new ArrayList<>(parts);
			corruptedParts.remove(persistedRoot);
			corruptedParts.add(
				new SortIndexLeafPagePart(
					ENTITY_INDEX_PK, new AttributeKeyWithIndexType(LANGUAGE_KEY, AttributeIndexType.SORT),
					twinSequence, firstPage.getBuckets(), base.length
				)
			);
			corruptedParts.add(
				SortIndexStoragePart.paged(ENTITY_INDEX_PK, LANGUAGE_KEY, base, 0, twinSequence, pagesWithTwin)
			);

			assertRefusedAsCorruption(corruptedParts);
		}
	}

	@Nested
	@DisplayName("DateTimeRange ordered at whole seconds")
	class DateTimeRangeElement {

		@Test
		@DisplayName("applies to a DateTimeRange element, whether the index is localized or not")
		void shouldApplyToADateTimeRangeElement() {
			assertTrue(LegacyOwnerSortIndexCorrection.isApplicable(single(DateTimeRange.class), null));
			assertTrue(LegacyOwnerSortIndexCorrection.isApplicable(rangeAndInteger(), null));
			assertTrue(LegacyOwnerSortIndexCorrection.isApplicable(rangeAndInteger(), Locale.ENGLISH));
		}

		@Test
		@DisplayName("re-sorts an inline compound whose ranges the release ordered by the offsets of their open bounds")
		void shouldResortInlineRangeCompoundOutOfTheCurrentOrder() {
			final ComparatorSource[] base = rangeAndInteger();
			// the release derived an open lower bound from the offset of the upper one, so it ordered the range closing
			// a day later at +02:00 first; the current index orders the two by the moment they close
			final LegacyOwnerSortIndexWriter legacy = legacy(PERIOD_KEY, base)
				.add(new ComparableArray(new Serializable[]{closingAt(1, PLUS_TWO), 1}), 1)
				.add(new ComparableArray(new Serializable[]{closingAt(0, PLUS_ONE), 2}), 2)
				.add(new ComparableArray(new Serializable[]{CLOSED_RANGE, 0}), 3);
			assertArrayEquals(new int[]{1, 2, 3}, legacy.toInlinePart().getSortedRecords(), "the released order");
			final OwnerSortIndex expected = legacy.toCurrentOwner();
			assertArrayEquals(new int[]{2, 1, 3}, expected.getSortedRecords(), "the current order");

			final OwnerSortIndex rewritten = roundTripInline(
				legacy, expected, index -> index.addRecord(new Serializable[]{closingAt(-1, PLUS_TWO), 0}, 4)
			);
			assertArrayEquals(new int[]{4, 2, 1, 3}, rewritten.getSortedRecords());
		}

		@Test
		@DisplayName("re-sorts a plain index the release ordered by the offsets of its open bounds, inline and paged")
		void shouldResortPlainRangeIndexOutOfTheCurrentOrder() {
			final ComparatorSource[] base = single(DateTimeRange.class);
			// no two ranges tie, yet the release ordered the one closing later first: it carries the larger offset
			final LegacyOwnerSortIndexWriter inline = legacy(PERIOD_KEY, base)
				.add(closingAt(1, PLUS_TWO), 1)
				.add(closingAt(0, PLUS_ONE), 2)
				.add(CLOSED_RANGE, 3);
			assertArrayEquals(new int[]{1, 2, 3}, inline.toInlinePart().getSortedRecords(), "the released order");
			final OwnerSortIndex expected = inline.toCurrentOwner();
			assertArrayEquals(new int[]{2, 1, 3}, expected.getSortedRecords(), "the current order");
			final OwnerSortIndex rewritten = roundTripInline(
				inline, expected, index -> index.addRecord(closingAt(-1, PLUS_TWO), 4)
			);
			assertArrayEquals(new int[]{4, 2, 1, 3}, rewritten.getSortedRecords());

			// every range closing at +02:00 the release ordered before every range closing at +01:00
			final LegacyOwnerSortIndexWriter paged = legacy(PERIOD_KEY, base);
			for (int i = 0; i < PAGED_VALUE_COUNT; i++) {
				paged.add(closingAt(i, i % 2 == 0 ? PLUS_TWO : PLUS_ONE), i + 1);
			}
			assertRoundTripPaged(
				paged, paged.toCurrentOwner(), index -> index.addRecord(closingAt(-1, PLUS_TWO), 100_000)
			);
		}

		@Test
		@DisplayName("leaves an index already in the current order as it is, inline and paged")
		void shouldLeaveRangeIndexInTheCurrentOrderAsItIs() {
			// every range closes at one offset, so the release ordered them as the current index does
			final LegacyOwnerSortIndexWriter paged = legacy(PERIOD_KEY, rangeAndInteger());
			for (int i = 0; i < PAGED_VALUE_COUNT; i++) {
				paged.add(new ComparableArray(new Serializable[]{closingAt(i, PLUS_ONE), i}), i + 1);
			}
			final OwnerSortIndex pagedAsItIs = assertLeftAsItIs(paged.toPagedParts(0));
			assertSameSortIndex(paged.toCurrentOwner(), pagedAsItIs, "the paged index left as it is");

			final LegacyOwnerSortIndexWriter inline = legacy(PERIOD_KEY, single(DateTimeRange.class))
				.add(closingAt(0, PLUS_TWO), 1)
				.add(closingAt(1, PLUS_TWO), 2)
				.add(CLOSED_RANGE, 3);
			final OwnerSortIndex inlineAsItIs = assertLeftAsItIs(List.of(inline.toInlinePart()));
			assertSameSortIndex(inline.toCurrentOwner(), inlineAsItIs, "the inline index left as it is");
		}

		@Test
		@DisplayName("keeps a bucket the release merged as merged while it re-sorts the index, inline and paged")
		void shouldKeepAMergedRangeBucketWhileResortingAroundIt() {
			final ComparatorSource[] base = single(DateTimeRange.class);
			// two ranges opening in one second and closing at one moment: the release kept them in one bucket under the
			// range it met first, which does not say which record holds which range
			final DateTimeRange openingAtHalf = DateTimeRange.between(
				NINE.plusMillis(500).atOffset(ZoneOffset.UTC), NINE.plusSeconds(3600).atOffset(ZoneOffset.UTC)
			);
			final DateTimeRange openingAtFifth = DateTimeRange.between(
				NINE.plusMillis(200).atOffset(ZoneOffset.UTC), NINE.plusSeconds(3600).atOffset(ZoneOffset.UTC)
			);
			assertEquals(
				List.of(openingAtHalf),
				legacy(PERIOD_KEY, base).add(openingAtHalf, 1).add(openingAtFifth, 2).getPersistedValues(),
				"the release must have merged the two ranges"
			);
			// the ranges open at their start the release ordered by the offset of their end, around the bucket
			final LegacyOwnerSortIndexWriter inline = legacy(PERIOD_KEY, base)
				.add(openingAtHalf, 1, 2)
				.add(closingAt(1, PLUS_TWO), 3)
				.add(closingAt(0, PLUS_ONE), 4);
			assertArrayEquals(new int[]{3, 4, 1, 2}, inline.toInlinePart().getSortedRecords(), "the released order");
			final OwnerSortIndex expected = inline.toCurrentOwner();
			assertArrayEquals(new int[]{4, 3, 1, 2}, expected.getSortedRecords(), "the current order");
			// the merged bucket is found under the range it was stored with, both its records in it
			final OwnerSortIndex rewritten = roundTripInline(
				inline, expected, index -> index.removeRecord(openingAtHalf, 2)
			);
			assertArrayEquals(new int[]{4, 3, 1}, rewritten.getSortedRecords());
			assertArrayEquals(
				new Serializable[]{closingAt(0, PLUS_ONE), closingAt(1, PLUS_TWO), openingAtHalf},
				rewritten.getSortedRecordValues()
			);

			final LegacyOwnerSortIndexWriter paged = legacy(PERIOD_KEY, base).add(openingAtHalf, 1, 2);
			for (int i = 1; i < PAGED_VALUE_COUNT; i++) {
				paged.add(closingAt(i, i % 2 == 0 ? PLUS_TWO : PLUS_ONE), i + 2);
			}
			assertRoundTripPaged(paged, paged.toCurrentOwner(), index -> index.removeRecord(openingAtHalf, 2));
		}

		@Test
		@DisplayName("refuses a stale leaf page twin of a compound the release ordered at whole seconds")
		void shouldRefuseStaleLeafPageTwinOfARangeCompound() {
			final ComparatorSource[] base = rangeAndInteger();
			// pages the release ordered legitimately - only its comparator sees them ascend - and a twin of the first
			final LegacyOwnerSortIndexWriter legacy = legacy(PERIOD_KEY, base);
			for (int i = 0; i < PAGED_VALUE_COUNT; i++) {
				legacy.add(
					new ComparableArray(new Serializable[]{closingAt(i, i % 2 == 0 ? PLUS_TWO : PLUS_ONE), i}), i + 1
				);
			}
			final List<StoragePart> parts = legacy.toPagedParts(0);
			final SortIndexStoragePart persistedRoot = root(parts);
			final int[] pages = persistedRoot.getLeafPageSequences();
			// an older copy of the first page under a sequence of its own, listed last by the root
			SortIndexLeafPagePart firstPage = null;
			for (StoragePart part : parts) {
				if (part instanceof SortIndexLeafPagePart page && page.getPageSequence() == pages[0]) {
					firstPage = page;
				}
			}
			assertNotNull(firstPage);
			final int twinSequence = persistedRoot.getHighWaterPageSequence() + 1;
			final int[] pagesWithTwin = Arrays.copyOf(pages, pages.length + 1);
			pagesWithTwin[pages.length] = twinSequence;
			final List<StoragePart> corruptedParts = new ArrayList<>(parts);
			corruptedParts.remove(persistedRoot);
			corruptedParts.add(
				new SortIndexLeafPagePart(
					ENTITY_INDEX_PK, new AttributeKeyWithIndexType(PERIOD_KEY, AttributeIndexType.SORT),
					twinSequence, firstPage.getBuckets(), base.length
				)
			);
			corruptedParts.add(
				SortIndexStoragePart.paged(ENTITY_INDEX_PK, PERIOD_KEY, base, 0, twinSequence, pagesWithTwin)
			);

			// refused at the twin, not at the pages the release ordered
			assertRefusedAsCorruption(corruptedParts, "on leaf page " + twinSequence + ".");
		}

		@Test
		@DisplayName("rewrites a paged compound of a range and a raw moment as the release ordered it")
		void shouldRewriteRangeAndRawMomentCompound() {
			final ComparatorSource[] base = {
				new ComparatorSource(DateTimeRange.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
				new ComparatorSource(OffsetDateTime.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
			};
			// raw moments at +02:00, and ranges the release ordered by the offsets of their open bounds
			final LegacyOwnerSortIndexWriter legacy = legacy(PERIOD_KEY, base);
			for (int i = 0; i < PAGED_VALUE_COUNT; i++) {
				legacy.add(
					new ComparableArray(
						new Serializable[]{
							closingAt(i, i % 2 == 0 ? PLUS_TWO : PLUS_ONE), NINE.plusSeconds(i).atOffset(PLUS_TWO)
						}
					),
					i + 1
				);
			}

			assertRoundTripPaged(
				legacy, legacy.toCurrentOwner(),
				index -> index.addRecord(new Serializable[]{closingAt(-1, PLUS_ONE), NINE.atOffset(PLUS_ONE)}, 100_000)
			);
		}
	}

	/**
	 * The clamp the storage migration puts in front of the normalizer of every persisted temporal key: release 2026.2
	 * stored values beyond the window of moments the current engine can hold, the JDK sentinels included.
	 */
	@Nested
	@DisplayName("Clamp to the supported window")
	class ClampToSupportedWindow {

		@Test
		@DisplayName("clamps every temporal type a persisted index holds and returns anything else as it came")
		void shouldClampTemporalValuesAndReturnAnythingElseAsItCame() {
			assertEquals(
				EvitaDataTypes.clampToSupportedWindow(OffsetDateTime.MAX),
				LegacyOwnerSortIndexCorrection.clampToSupportedWindow(OffsetDateTime.MAX)
			);
			assertEquals(
				EvitaDataTypes.clampToSupportedWindow(OffsetDateTime.MIN),
				LegacyOwnerSortIndexCorrection.clampToSupportedWindow(OffsetDateTime.MIN)
			);
			assertEquals(
				EvitaDataTypes.clampToSupportedWindow(LocalDateTime.MAX),
				LegacyOwnerSortIndexCorrection.clampToSupportedWindow(LocalDateTime.MAX)
			);
			assertEquals(
				EvitaDataTypes.clampToSupportedWindow(LocalDateTime.MIN),
				LegacyOwnerSortIndexCorrection.clampToSupportedWindow(LocalDateTime.MIN)
			);
			// the key 2026.2 gave such a value in a filter index
			assertEquals(
				EvitaDataTypes.clampToSupportedWindow(Instant.MAX),
				LegacyOwnerSortIndexCorrection.clampToSupportedWindow(Instant.MAX)
			);
			assertEquals(
				EvitaDataTypes.clampToSupportedWindow(Instant.MIN),
				LegacyOwnerSortIndexCorrection.clampToSupportedWindow(Instant.MIN)
			);

			// inside the window the very same instance returns, sub-millisecond digits included
			final OffsetDateTime inside = OffsetDateTime.of(2030, 1, 2, 3, 4, 5, 123_456_789, ZoneOffset.UTC);
			assertSame(inside, LegacyOwnerSortIndexCorrection.clampToSupportedWindow(inside));
			final LocalDateTime localInside = inside.toLocalDateTime();
			assertSame(localInside, LegacyOwnerSortIndexCorrection.clampToSupportedWindow(localInside));
			final Instant instantInside = inside.toInstant();
			assertSame(instantInside, LegacyOwnerSortIndexCorrection.clampToSupportedWindow(instantInside));
			final LocalTime time = LocalTime.of(3, 4, 5, 123_456_789);
			assertSame(time, LegacyOwnerSortIndexCorrection.clampToSupportedWindow(time));
			final String text = "text";
			assertSame(text, LegacyOwnerSortIndexCorrection.clampToSupportedWindow(text));
			assertNull(LegacyOwnerSortIndexCorrection.clampToSupportedWindow(null));
		}

		@Test
		@DisplayName("keys a temporal value beyond the window by the edge it is clamped to")
		void shouldKeyOutOfWindowTemporalValueByEdge() {
			final Function<Object, Serializable> offsetNormalizer =
				LegacyOwnerSortIndexCorrection.createClampingNormalizer(OffsetDateTime.class, 0);
			final Function<Object, Serializable> plainOffsetNormalizer =
				FilterIndex.getNormalizer(OffsetDateTime.class, 0);
			final Serializable latestKey =
				plainOffsetNormalizer.apply(EvitaDataTypes.clampToSupportedWindow(OffsetDateTime.MAX));
			assertEquals(latestKey, offsetNormalizer.apply(OffsetDateTime.MAX));
			// the key 2026.2 gave the sentinel lands on the same edge
			assertEquals(latestKey, offsetNormalizer.apply(OffsetDateTime.MAX.toInstant()));
			// inside the window it normalizes as the plain normalizer does - truncated to whole milliseconds
			final OffsetDateTime inside = OffsetDateTime.of(2030, 1, 2, 3, 4, 5, 123_456_789, ZoneOffset.UTC);
			assertEquals(plainOffsetNormalizer.apply(inside), offsetNormalizer.apply(inside));

			final Function<Object, Serializable> localNormalizer =
				LegacyOwnerSortIndexCorrection.createClampingNormalizer(LocalDateTime.class, 0);
			assertEquals(
				FilterIndex.getNormalizer(LocalDateTime.class, 0)
					.apply(EvitaDataTypes.clampToSupportedWindow(LocalDateTime.MIN)),
				localNormalizer.apply(LocalDateTime.MIN)
			);
		}

		@Test
		@DisplayName("gives a type that is not temporal the plain normalizer at the passed decimal places")
		void shouldGivePlainNormalizerToTypeThatIsNotTemporal() {
			final BigDecimal decimal = new BigDecimal("1.25");
			final Serializable scaled =
				LegacyOwnerSortIndexCorrection.createClampingNormalizer(BigDecimal.class, 2).apply(decimal);
			assertEquals(FilterIndex.getNormalizer(BigDecimal.class, 2).apply(decimal), scaled);
			assertNotEquals(FilterIndex.getNormalizer(BigDecimal.class, 0).apply(decimal), scaled);

			final String text = "text";
			assertSame(text, LegacyOwnerSortIndexCorrection.createClampingNormalizer(String.class, 0).apply(text));
		}
	}

	/*
		PRIVATE HELPERS
	 */

	/**
	 * Returns the comparator base of a compound of a range and an integer, both ascending with nulls last.
	 *
	 * @return the comparator base
	 */
	@Nonnull
	private static ComparatorSource[] rangeAndInteger() {
		return new ComparatorSource[]{
			new ComparatorSource(DateTimeRange.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
			new ComparatorSource(Integer.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
		};
	}

	/**
	 * Returns a range open at its start that closes `day` days after 09:00Z of the first day, written at the passed
	 * offset. Release 2026.2 derived the open bound from that offset, so it ordered such ranges by the offset first.
	 *
	 * @param day    the day the range closes on, relative to the first day
	 * @param offset the zone offset the closing moment is written at
	 * @return the range
	 */
	@Nonnull
	private static DateTimeRange closingAt(int day, @Nonnull ZoneOffset offset) {
		return DateTimeRange.until(NINE.plusSeconds(86_400L * day).atOffset(offset));
	}

	/**
	 * Returns the comparator base of a compound of a locale and an integer, both ascending with nulls last.
	 *
	 * @return the comparator base
	 */
	@Nonnull
	private static ComparatorSource[] localeAndInteger() {
		return new ComparatorSource[]{
			new ComparatorSource(Locale.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
			new ComparatorSource(Integer.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
		};
	}

	/**
	 * Returns an English locale of a region of its own for each number - `en-AA`, `en-AB` and so on, in the order of
	 * their language tags - with or without an ill-formed variant, which the language tag drops.
	 *
	 * @param number    the number of the region, below 676
	 * @param illFormed `true` for the locale with the ill-formed variant
	 * @return the locale
	 */
	@Nonnull
	private static Locale languageOf(int number, boolean illFormed) {
		final String region = String.valueOf(new char[]{(char) ('A' + number / 26), (char) ('A' + number % 26)});
		return Locale.of("en", region, illFormed ? ILL_FORMED_VARIANT : "");
	}

	/**
	 * Returns the comparator base of a compound of a text and an integer, both ascending with nulls last.
	 *
	 * @return the comparator base
	 */
	@Nonnull
	private static ComparatorSource[] textAndInteger() {
		return new ComparatorSource[]{
			new ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
			new ComparatorSource(Integer.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
		};
	}

	/**
	 * Returns a text made of a prefix and a four-digit number.
	 *
	 * @param prefix the prefix - {@link #PLAIN} or {@link #SPACED}
	 * @param number the number
	 * @return the text
	 */
	@Nonnull
	private static String textOf(@Nonnull String prefix, int number) {
		return prefix + String.format("%04d", number);
	}

	/**
	 * Persists the parts of an index the rewrite applies to and asserts that the rewrite reads them, leaves them as
	 * they are and emits nothing; then loads them through the load path.
	 *
	 * @param legacyParts the inline part, or the leaf pages and the paged root
	 * @return the index loaded from the persisted parts
	 */
	@Nonnull
	private OwnerSortIndex assertLeftAsItIs(@Nonnull List<StoragePart> legacyParts) {
		final AttributeIndexKey key = root(legacyParts).getAttributeIndexKey();
		final OffsetIndex offsetIndex = openWritableOffsetIndex();
		try {
			writeEmission(offsetIndex, LEGACY_VERSION, legacyParts);
			offsetIndex.flush(LEGACY_VERSION);
			final SortIndexStoragePart persistedRoot = readRoot(offsetIndex, LEGACY_VERSION, key);
			assertNotNull(persistedRoot);
			assertTrue(
				LegacyOwnerSortIndexCorrection.isApplicable(persistedRoot.getComparatorBase(), key.locale()),
				"the index must be one the rewrite applies to, or the case proves nothing"
			);
			final ValueToRecord[][] pages = persistedRoot.isPaged() ?
				readPages(offsetIndex, LEGACY_VERSION, persistedRoot) : null;
			final TrappedChanges sink = new TrappedChanges();
			assertFalse(
				LegacyOwnerSortIndexCorrection.rewrite(persistedRoot, pages, sink),
				"an index in the current form and order must not be rewritten"
			);
			assertEquals(0, sink.getTrappedChangesCount(), "nothing may be emitted for it");
			return persistedRoot.isPaged() ?
				loadPaged(offsetIndex, LEGACY_VERSION, persistedRoot) : loadInline(persistedRoot);
		} finally {
			IOUtils.closeQuietly(offsetIndex::close);
		}
	}

	/**
	 * Returns a single-element comparator base of the passed type, ascending with nulls last.
	 *
	 * @param type the attribute type
	 * @return the comparator base
	 */
	@Nonnull
	private static ComparatorSource[] single(@Nonnull Class<?> type) {
		return new ComparatorSource[]{new ComparatorSource(type, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)};
	}

	/**
	 * Creates an empty legacy index of the test attribute.
	 *
	 * @param base the comparator base
	 * @return the legacy index writer
	 */
	@Nonnull
	private static LegacyOwnerSortIndexWriter legacy(@Nonnull ComparatorSource[] base) {
		return legacy(ATTRIBUTE_KEY, base);
	}

	/**
	 * Creates an empty legacy index of the passed attribute.
	 *
	 * @param key  the attribute of the index, which carries its locale
	 * @param base the comparator base
	 * @return the legacy index writer
	 */
	@Nonnull
	private static LegacyOwnerSortIndexWriter legacy(@Nonnull AttributeIndexKey key, @Nonnull ComparatorSource[] base) {
		return new LegacyOwnerSortIndexWriter(ENTITY_INDEX_PK, key, base);
	}

	/**
	 * Persists the legacy index as an inline part, rewrites it as the migration does and stores the emission, then
	 * loads the stored part through the load path - which corrects nothing - and asserts it equals the owner the
	 * current engine builds from the same raw values and holds normalized values only. Then applies the same write to
	 * both and asserts they stay equal.
	 *
	 * @param legacy   the legacy index
	 * @param expected the owner the current engine builds from the same values
	 * @param write    the write applied to both indexes
	 * @return the index loaded from the rewritten part, the write applied
	 */
	@Nonnull
	private OwnerSortIndex roundTripInline(
		@Nonnull LegacyOwnerSortIndexWriter legacy,
		@Nonnull OwnerSortIndex expected,
		@Nonnull Consumer<OwnerSortIndex> write
	) {
		final OffsetIndex offsetIndex = openWritableOffsetIndex();
		try {
			final SortIndexStoragePart written = legacy.toInlinePart();
			offsetIndex.put(LEGACY_VERSION, written);
			offsetIndex.flush(LEGACY_VERSION);

			final AttributeIndexKey key = written.getAttributeIndexKey();
			final SortIndexStoragePart legacyPart = readRoot(offsetIndex, LEGACY_VERSION, key);
			assertNotNull(legacyPart);
			assertFalse(legacyPart.isPaged(), "the legacy part must be inline");
			assertLegacy(legacyPart, List.of(legacyPart.getSortedRecordsValues()));

			final List<StoragePart> emission = rewrite(legacyPart, null);
			assertEquals(1, emission.size(), "an inline index must be rewritten as one inline part: " + emission);
			final SortIndexStoragePart emittedPart = root(emission);
			assertFalse(emittedPart.isPaged(), "the rewritten part must be inline");
			writeEmission(offsetIndex, REWRITTEN_VERSION, emission);
			offsetIndex.flush(REWRITTEN_VERSION);

			final SortIndexStoragePart rewrittenPart = readRoot(offsetIndex, REWRITTEN_VERSION, key);
			assertNormalized(rewrittenPart.getComparatorBase(), List.of(rewrittenPart.getSortedRecordsValues()));
			final OwnerSortIndex reloaded = loadInline(rewrittenPart);
			assertSameSortIndex(expected, reloaded, "rewritten inline part");

			write.accept(expected);
			write.accept(reloaded);
			assertSameSortIndex(expected, reloaded, "rewritten inline part after a write");
			return reloaded;
		} finally {
			IOUtils.closeQuietly(offsetIndex::close);
		}
	}

	/**
	 * Persists the legacy index as leaf pages, rewrites it as the migration does - asserting that the emission
	 * removes every legacy page and writes every page of the rewritten root afresh, with normalized values, under page
	 * sequences above the legacy high-water - and stores the emission. Then reopens the offset index, asserts that no
	 * legacy page is stored any more, loads the rewritten pages through the load path - which corrects nothing - and
	 * asserts they equal the owner the current engine builds from the same raw values. Then applies the same write to
	 * both and asserts they stay equal.
	 *
	 * @param legacy   the legacy index
	 * @param expected the owner the current engine builds from the same values
	 * @param write    the write applied to both indexes
	 */
	private void assertRoundTripPaged(
		@Nonnull LegacyOwnerSortIndexWriter legacy,
		@Nonnull OwnerSortIndex expected,
		@Nonnull Consumer<OwnerSortIndex> write
	) {
		assertRoundTripPaged(legacy.toPagedParts(0), expected, write);
	}

	/**
	 * The variant of {@link #assertRoundTripPaged(LegacyOwnerSortIndexWriter, OwnerSortIndex, Consumer)} taking the
	 * legacy parts produced already.
	 *
	 * @param legacyParts the legacy leaf pages and paged root
	 * @param expected    the owner the current engine builds from the same values
	 * @param write       the write applied to both indexes
	 */
	private void assertRoundTripPaged(
		@Nonnull List<StoragePart> legacyParts,
		@Nonnull OwnerSortIndex expected,
		@Nonnull Consumer<OwnerSortIndex> write
	) {
		final AttributeIndexKey key = root(legacyParts).getAttributeIndexKey();
		final OffsetIndex offsetIndex = openWritableOffsetIndex();
		final OffsetIndexDescriptor rewrittenDescriptor;
		final int[] legacyPageSequences;
		final int[] rewrittenPageSequences;
		try {
			writeEmission(offsetIndex, LEGACY_VERSION, legacyParts);
			offsetIndex.flush(LEGACY_VERSION);

			final SortIndexStoragePart legacyRoot = readRoot(offsetIndex, LEGACY_VERSION, key);
			assertNotNull(legacyRoot);
			assertTrue(legacyRoot.isPaged(), "the legacy part must be paged");
			legacyPageSequences = legacyRoot.getLeafPageSequences();
			assertTrue(legacyPageSequences.length >= 2, "the legacy index must span several leaf pages");
			final ValueToRecord[][] legacyBuckets = readPages(offsetIndex, LEGACY_VERSION, legacyRoot);
			final List<Serializable> legacyValues = new ArrayList<>(PAGED_VALUE_COUNT);
			for (ValueToRecord[] page : legacyBuckets) {
				for (ValueToRecord bucket : page) {
					legacyValues.add(bucket.getValue());
				}
			}
			assertLegacy(legacyRoot, legacyValues);

			final List<StoragePart> emission = rewrite(legacyRoot, legacyBuckets);
			final SortIndexStoragePart rewrittenRoot = root(emission);
			assertTrue(rewrittenRoot.isPaged(), "the rewritten index must still span several leaf pages");
			rewrittenPageSequences = rewrittenRoot.getLeafPageSequences();
			final Set<Long> removedPages = new HashSet<>();
			final Set<Integer> emittedPages = new HashSet<>();
			for (StoragePart part : emission) {
				if (part instanceof SortIndexLeafPagePart page) {
					assertNormalized(rewrittenRoot.getComparatorBase(), List.of(page.getBuckets()));
					emittedPages.add(page.getPageSequence());
				} else if (part instanceof DeferredRemovalStoragePart removal) {
					assertSame(SortIndexLeafPagePart.class, removal.removedContainerType());
					removedPages.add(removal.computeUniquePartIdAndSet(offsetIndex.getReadOnlyKeyCompressor()));
				}
			}
			final int legacyStreamId = streamId(offsetIndex, key);
			final Set<Long> legacyPages = new HashSet<>();
			for (int sequence : legacyPageSequences) {
				legacyPages.add(AbstractLeafPagePart.computeUniquePartId(legacyStreamId, sequence));
			}
			assertEquals(legacyPages, removedPages, "every legacy page, and nothing else, must be removed");
			final Set<Integer> rewrittenPageSet = new HashSet<>();
			for (int sequence : rewrittenPageSequences) {
				rewrittenPageSet.add(sequence);
				assertFalse(
					legacyPages.contains(AbstractLeafPagePart.computeUniquePartId(legacyStreamId, sequence)),
					"rewritten page " + sequence + " must not reuse the identity of a legacy page"
				);
				assertTrue(
					sequence > legacyRoot.getHighWaterPageSequence(),
					"rewritten page " + sequence + " must lie above the legacy high-water " +
						legacyRoot.getHighWaterPageSequence()
				);
			}
			assertEquals(rewrittenPageSet, emittedPages, "every live page must be written by the rewrite");
			writeEmission(offsetIndex, REWRITTEN_VERSION, emission);
			rewrittenDescriptor = offsetIndex.flush(REWRITTEN_VERSION);
		} finally {
			IOUtils.closeQuietly(offsetIndex::close);
		}

		final OffsetIndex reopened = loadOffsetIndex(rewrittenDescriptor, REWRITTEN_VERSION);
		try {
			final int streamId = streamId(reopened, key);
			for (int sequence : legacyPageSequences) {
				assertNull(
					reopened.get(
						REWRITTEN_VERSION, AbstractLeafPagePart.computeUniquePartId(streamId, sequence),
						SortIndexLeafPagePart.class
					),
					"legacy page " + sequence + " must be removed by the rewrite"
				);
			}
			final SortIndexStoragePart reloadedRoot = readRoot(reopened, REWRITTEN_VERSION, key);
			assertArrayEquals(rewrittenPageSequences, reloadedRoot.getLeafPageSequences());
			final OwnerSortIndex reloaded = loadPaged(reopened, REWRITTEN_VERSION, reloadedRoot);
			assertSameSortIndex(expected, reloaded, "rewritten paged part");

			write.accept(expected);
			write.accept(reloaded);
			assertSameSortIndex(expected, reloaded, "rewritten paged part after a write");
		} finally {
			IOUtils.closeQuietly(reopened::close);
		}
	}

	/**
	 * Persists the parts and asserts that the rewrite refuses them as corruption rather than merging them.
	 *
	 * @param corruptedParts leaf pages and a paged root no writer produces
	 * @param messageDetails further fragments the refusal must name besides the pages out of order
	 */
	private void assertRefusedAsCorruption(
		@Nonnull List<StoragePart> corruptedParts,
		@Nonnull String... messageDetails
	) {
		final OffsetIndex offsetIndex = openWritableOffsetIndex();
		try {
			writeEmission(offsetIndex, LEGACY_VERSION, corruptedParts);
			offsetIndex.flush(LEGACY_VERSION);
			final SortIndexStoragePart corruptedRoot =
				readRoot(offsetIndex, LEGACY_VERSION, root(corruptedParts).getAttributeIndexKey());
			final ValueToRecord[][] corruptedPages = readPages(offsetIndex, LEGACY_VERSION, corruptedRoot);
			final TrappedChanges sink = new TrappedChanges();
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> LegacyOwnerSortIndexCorrection.rewrite(corruptedRoot, corruptedPages, sink),
				"pages out of order are corruption, not a legacy form to rewrite"
			);
			final String message = error.getPrivateMessage();
			assertTrue(message.contains("not in ascending order"), message);
			for (final String messageDetail : messageDetails) {
				assertTrue(message.contains(messageDetail), message);
			}
			assertEquals(0, sink.getTrappedChangesCount(), "nothing may be emitted for a refused index");
		} finally {
			IOUtils.closeQuietly(offsetIndex::close);
		}
	}

	/**
	 * Rewrites a persisted root as the migration does and returns what the rewrite emitted, asserting that it emitted
	 * anything at all.
	 *
	 * @param root           the persisted root
	 * @param perPageBuckets the buckets of its leaf pages, or `null` for an inline root
	 * @return the emitted parts and removals
	 */
	@Nonnull
	private static List<StoragePart> rewrite(
		@Nonnull SortIndexStoragePart root,
		@Nullable ValueToRecord[][] perPageBuckets
	) {
		final TrappedChanges sink = new TrappedChanges();
		assertTrue(
			LegacyOwnerSortIndexCorrection.rewrite(root, perPageBuckets, sink),
			"an index holding raw values or values out of the current order must be rewritten"
		);
		final List<StoragePart> parts = new ArrayList<>(sink.getTrappedChangesCount());
		final Iterator<StoragePart> iterator = sink.getTrappedChangesIterator();
		while (iterator.hasNext()) {
			parts.add(iterator.next());
		}
		return parts;
	}

	/**
	 * Returns `true` when two neighbouring legacy pages hold compound values whose temporal first elements denote the
	 * same moment - a group the normalization makes tie is split by a page boundary.
	 *
	 * @param legacyParts the legacy leaf pages and paged root
	 * @return `true` when some page boundary splits such a group
	 */
	private static boolean tieGroupStraddlesPageBoundary(@Nonnull List<StoragePart> legacyParts) {
		final Map<Integer, SortIndexLeafPagePart> pagesBySequence = CollectionUtils.createHashMap(8);
		for (StoragePart part : legacyParts) {
			if (part instanceof SortIndexLeafPagePart page) {
				pagesBySequence.put(page.getPageSequence(), page);
			}
		}
		final int[] sequences = root(legacyParts).getLeafPageSequences();
		for (int i = 1; i < sequences.length; i++) {
			final ValueToRecord[] previous = pagesBySequence.get(sequences[i - 1]).getBuckets();
			final ValueToRecord[] next = pagesBySequence.get(sequences[i]).getBuckets();
			final OffsetDateTime last = (OffsetDateTime) ((ComparableArray) previous[previous.length - 1].getValue())
				.array()[0];
			final OffsetDateTime first = (OffsetDateTime) ((ComparableArray) next[0].getValue()).array()[0];
			if (last.toInstant().equals(first.toInstant())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Asserts every temporal value or compound element is in its normalized form - the form the normalizer maps onto
	 * itself, which is what the load path, correcting nothing, needs to find.
	 *
	 * @param base   the comparator base
	 * @param values the persisted values, or buckets holding them
	 */
	private static void assertNormalized(@Nonnull ComparatorSource[] base, @Nonnull List<?> values) {
		for (Object item : values) {
			final Serializable value = item instanceof ValueToRecord bucket ? bucket.getValue() : (Serializable) item;
			final Serializable[] elements = value instanceof ComparableArray(Serializable[] array) ?
				array : new Serializable[]{value};
			for (int i = 0; i < base.length; i++) {
				if (SortIndex.isTemporal(base[i].type()) && elements[i] != null) {
					assertSame(
						elements[i], FilterIndex.getNormalizer(base[i].type(), 0).apply(elements[i]),
						"a rewritten value must be normalized: " + elements[i]
					);
				}
			}
		}
	}

	/**
	 * Asserts some temporal value or compound element is raw, or the values are out of the order the current index
	 * compares them in - the premise of a case: without either the rewrite has nothing to do and the case proves
	 * nothing.
	 *
	 * @param root   the persisted root, which carries the comparator base and the locale
	 * @param values the persisted values, in persisted order
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void assertLegacy(@Nonnull SortIndexStoragePart root, @Nonnull List<Serializable> values) {
		final ComparatorSource[] base = root.getComparatorBase();
		for (Serializable value : values) {
			final Serializable[] elements = value instanceof ComparableArray(Serializable[] array) ?
				array : new Serializable[]{value};
			for (int i = 0; i < base.length; i++) {
				if (
					SortIndex.isTemporal(base[i].type()) && elements[i] != null &&
						FilterIndex.getNormalizer(base[i].type(), 0).apply(elements[i]) != elements[i]
				) {
					return;
				}
			}
		}
		final Locale locale = root.getAttributeIndexKey().locale();
		final Comparator comparator = base.length == 1 ?
			SortIndex.createComparatorFor(locale, base[0]) : SortIndex.createCombinedComparatorFor(locale, base);
		for (int i = 1; i < values.size(); i++) {
			if (comparator.compare(values.get(i - 1), values.get(i)) >= 0) {
				return;
			}
		}
		throw new AssertionError(
			"the legacy part must hold a raw temporal value or values out of the current order: " + values
		);
	}

	/**
	 * Loads an inline part the way the attribute index loader loads an owner part - which corrects nothing.
	 *
	 * @param part the persisted part
	 * @return the loaded owner sort index
	 */
	@Nonnull
	private static OwnerSortIndex loadInline(@Nonnull SortIndexStoragePart part) {
		return assertInstanceOf(
			OwnerSortIndex.class,
			SortIndex.create(
				part.getComparatorBase(), null, part.getAttributeIndexKey(), part.getIndexedDecimalPlaces(),
				part.getSortedRecords(), part.getSortedRecordsValues(), part.getValueCardinalities(), null
			)
		);
	}

	/**
	 * Loads a paged part the way the attribute index loader loads it - which corrects nothing.
	 *
	 * @param offsetIndex    the offset index holding the leaf pages
	 * @param catalogVersion the version to read at
	 * @param root           the paged root part
	 * @return the loaded owner sort index
	 */
	@Nonnull
	private static OwnerSortIndex loadPaged(
		@Nonnull OffsetIndex offsetIndex, long catalogVersion, @Nonnull SortIndexStoragePart root
	) {
		return OwnerSortIndex.fromPersistedPages(
			root.getComparatorBase(), null, root.getAttributeIndexKey(), root.getIndexedDecimalPlaces(),
			root.getLeafPageSequences(), readPages(offsetIndex, catalogVersion, root), root.getHighWaterPageSequence()
		);
	}

	/**
	 * Reads the buckets of every leaf page a paged root lists, in the order it lists them.
	 *
	 * @param offsetIndex    the offset index holding the leaf pages
	 * @param catalogVersion the version to read at
	 * @param root           the paged root part
	 * @return the buckets of every leaf page
	 */
	@Nonnull
	private static ValueToRecord[][] readPages(
		@Nonnull OffsetIndex offsetIndex, long catalogVersion, @Nonnull SortIndexStoragePart root
	) {
		final int streamId = streamId(offsetIndex, root.getAttributeIndexKey());
		final int[] orderedPageSequences = root.getLeafPageSequences();
		final ValueToRecord[][] perPageBuckets = new ValueToRecord[orderedPageSequences.length][];
		for (int i = 0; i < orderedPageSequences.length; i++) {
			final SortIndexLeafPagePart leafPage = offsetIndex.get(
				catalogVersion, AbstractLeafPagePart.computeUniquePartId(streamId, orderedPageSequences[i]),
				SortIndexLeafPagePart.class
			);
			assertNotNull(leafPage, "leaf page " + orderedPageSequences[i] + " must be readable");
			perPageBuckets[i] = leafPage.getBuckets();
		}
		return perPageBuckets;
	}

	/**
	 * Asserts two owner sort indexes hold the same records in the same order, and the same distinct values.
	 *
	 * @param expected the expected index
	 * @param actual   the actual index
	 * @param context  describes the assertion
	 */
	private static void assertSameSortIndex(
		@Nonnull OwnerSortIndex expected, @Nonnull OwnerSortIndex actual, @Nonnull String context
	) {
		assertArrayEquals(expected.getSortedRecords(), actual.getSortedRecords(), context + ": record order");
		assertArrayEquals(
			expected.getSortedRecordValues(), actual.getSortedRecordValues(), context + ": distinct values"
		);
	}

	/**
	 * Drains what the index emits on a flush, removals included.
	 *
	 * @param index the index
	 * @return the emitted parts, empty when the index is clean
	 */
	@Nonnull
	private static List<StoragePart> emit(@Nonnull SortIndex index) {
		final TrappedChanges trappedChanges = new TrappedChanges();
		index.appendStorageParts(ENTITY_INDEX_PK, trappedChanges);
		final List<StoragePart> parts = new ArrayList<>(8);
		final Iterator<StoragePart> iterator = trappedChanges.getTrappedChangesIterator();
		while (iterator.hasNext()) {
			parts.add(iterator.next());
		}
		return parts;
	}

	/**
	 * Returns the root part of an emission.
	 *
	 * @param parts the emitted parts
	 * @return the sort index root part
	 */
	@Nonnull
	private static SortIndexStoragePart root(@Nonnull List<StoragePart> parts) {
		for (final StoragePart part : parts) {
			if (part instanceof SortIndexStoragePart rootPart) {
				return rootPart;
			}
		}
		throw new IllegalStateException("The emission carries no SortIndexStoragePart root!");
	}

	/**
	 * Reads the root part of a sort index of the test entity index.
	 *
	 * @param offsetIndex    the offset index
	 * @param catalogVersion the version to read at
	 * @param key            the attribute of the sort index
	 * @return the root part, or `null`
	 */
	private static SortIndexStoragePart readRoot(
		@Nonnull OffsetIndex offsetIndex, long catalogVersion, @Nonnull AttributeIndexKey key
	) {
		final long rootPK = AttributeIndexStoragePart.computeUniquePartId(
			ENTITY_INDEX_PK, AttributeIndexType.SORT, key, offsetIndex.getReadOnlyKeyCompressor()
		);
		return offsetIndex.get(catalogVersion, rootPK, SortIndexStoragePart.class);
	}

	/**
	 * Resolves the leaf-page stream id of a sort index of the test entity index.
	 *
	 * @param offsetIndex the offset index
	 * @param key         the attribute of the sort index
	 * @return the stream id
	 */
	private static int streamId(@Nonnull OffsetIndex offsetIndex, @Nonnull AttributeIndexKey key) {
		return offsetIndex.getReadOnlyKeyCompressor().getId(
			new LeafStreamKey(ENTITY_INDEX_PK, new AttributeKeyWithIndexType(key, AttributeIndexType.SORT))
		);
	}

	/**
	 * Writes parts and executes deferred removals in the offset index.
	 *
	 * @param offsetIndex    the offset index
	 * @param catalogVersion the version to write at
	 * @param parts          the parts
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

}
