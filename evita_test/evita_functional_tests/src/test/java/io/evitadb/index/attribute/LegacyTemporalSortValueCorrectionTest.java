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
import io.evitadb.utils.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that an owner sort index persisted before its temporal values were normalized - its parts written as the
 * owner used to write them, with the raw `OffsetDateTime`, `LocalDateTime` and `LocalTime` values - loads normalized,
 * and is written back normalized by the next flush, after which it loads without needing any correction.
 *
 * The legacy parts are produced by {@link LegacyOwnerSortIndexWriter}, a test-only replica of the old writer, and pass
 * through the real Kryo serializers and the real offset index.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Owner sort indexes persisted with raw temporal values load normalized")
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(ORDER)
class LegacyTemporalSortValueCorrectionTest {
	private static final String ENTITY_TYPE = "product";
	private static final int ENTITY_INDEX_PK = 7;
	private static final AttributeIndexKey ATTRIBUTE_KEY = new AttributeIndexKey(null, "moment", null);
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
		this.targetFile = Files.createTempFile("legacyTemporalSortValueCorrection", ".kryo");
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

			final OwnerSortIndex corrected = loadLegacyInline(legacy);
			assertArrayEquals(new int[]{7, 2, 5, 9, 1, 3, 4}, corrected.getSortedRecords());
			assertArrayEquals(
				new Serializable[]{NINE.minusSeconds(3600), NINE, NINE.plusSeconds(3600)},
				corrected.getSortedRecordValues()
			);

			// the raw value of either offset finds the merged block
			final OwnerSortIndex rewritten = roundTripInline(
				legacy, ownerOf(base, legacy),
				index -> {
					index.removeRecord(NINE.atOffset(PLUS_TWO), 5);
					index.addRecord(NINE.atOffset(PLUS_ONE), 6);
				}
			);
			assertArrayEquals(new int[]{7, 2, 6, 9, 1, 3, 4}, rewritten.getSortedRecords());
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
			final OwnerSortIndex correctedDateTimes = roundTripInline(
				dateTimes, ownerOf(dateTimeBase, dateTimes), index -> index.addRecord(ten.plusSeconds(2), 4)
			);
			assertArrayEquals(new int[]{1, 3, 2, 4}, correctedDateTimes.getSortedRecords());
			assertArrayEquals(
				new Serializable[]{
					ten.toInstant(ZoneOffset.UTC), ten.plusSeconds(1).toInstant(ZoneOffset.UTC),
					ten.plusSeconds(2).toInstant(ZoneOffset.UTC)
				},
				correctedDateTimes.getSortedRecordValues()
			);

			final ComparatorSource[] timeBase = single(LocalTime.class);
			final LegacyOwnerSortIndexWriter times = legacy(timeBase)
				.add(ten.toLocalTime().plusNanos(100_000), 3)
				.add(ten.toLocalTime().plusNanos(200_000), 1)
				.add(ten.toLocalTime().plusSeconds(1), 2);
			final OwnerSortIndex correctedTimes = roundTripInline(
				times, ownerOf(timeBase, times), index -> index.addRecord(ten.toLocalTime().plusSeconds(2), 4)
			);
			assertArrayEquals(new int[]{1, 3, 2, 4}, correctedTimes.getSortedRecords());
			assertArrayEquals(
				new Serializable[]{
					ten.toLocalTime(), ten.toLocalTime().plusSeconds(1), ten.toLocalTime().plusSeconds(2)
				},
				correctedTimes.getSortedRecordValues()
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
			assertArrayEquals(new int[]{2, 1, 4, 5, 3}, loadLegacyInline(legacy).getSortedRecords());
			final OwnerSortIndex rewritten = roundTripInline(
				legacy, ownerOf(base, legacy),
				index -> index.addRecord(new Serializable[]{NINE.atOffset(ZoneOffset.UTC), 6}, 6)
			);
			assertArrayEquals(new int[]{2, 1, 6, 4, 5, 3}, rewritten.getSortedRecords());
		}

		@Test
		@DisplayName("leaves an index without raw temporal values untouched and clean")
		void shouldLeaveNormalizedIndexUntouched() {
			final ComparatorSource[] base = single(OffsetDateTime.class);
			final OwnerSortIndex current = new OwnerSortIndex(OffsetDateTime.class, ATTRIBUTE_KEY);
			current.addRecord(NINE.atOffset(PLUS_ONE), 2);
			current.addRecord(NINE.atOffset(PLUS_TWO), 1);
			final SortIndexStoragePart part = root(emit(current));

			final OwnerSortIndex loaded = loadInline(part);

			assertArrayEquals(new int[]{1, 2}, loaded.getSortedRecords());
			assertTrue(emit(loaded).isEmpty(), "an index loaded without correction must not be flagged dirty");
			assertFalse(LegacyTemporalSortValueCorrection.isApplicable(single(Integer.class)));
			assertTrue(LegacyTemporalSortValueCorrection.isApplicable(base));
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

			roundTripPaged(
				legacy, ownerOf(base, legacy), index -> index.addRecord(NINE.minusSeconds(1).atOffset(PLUS_ONE), 1)
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

			roundTripPaged(legacy, ownerOf(base, legacy), index -> index.addRecord(ten.minusSeconds(1), 100_000));
		}

		@Test
		@DisplayName("refuses legacy pages whose persisted order is not ascending instead of healing them")
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

			final OffsetIndex offsetIndex = openWritableOffsetIndex();
			try {
				writeEmission(offsetIndex, LEGACY_VERSION, corruptedParts);
				offsetIndex.flush(LEGACY_VERSION);
				final SortIndexStoragePart corruptedRoot = readRoot(offsetIndex, LEGACY_VERSION);
				assertThrows(
					RuntimeException.class,
					() -> loadPaged(offsetIndex, LEGACY_VERSION, corruptedRoot),
					"pages out of order are corruption, not a legacy form to normalize"
				);
			} finally {
				IOUtils.closeQuietly(offsetIndex::close);
			}
		}

		@Test
		@DisplayName("re-sorts a compound whose order the normalization inverts across leaf pages")
		void shouldResortInvertedCompoundAcrossLeafPages() {
			final ComparatorSource[] base = {
				new ComparatorSource(OffsetDateTime.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
				new ComparatorSource(Integer.class, OrderDirection.DESC, OrderBehaviour.NULLS_LAST)
			};
			// within each pair the +01:00 moment sorts first raw, but carries the smaller integer, which sorts last
			// once the moments tie
			final LegacyOwnerSortIndexWriter legacy = legacy(base);
			for (int i = 0; i < PAGED_VALUE_COUNT; i++) {
				legacy.add(
					new ComparableArray(
						new Serializable[]{NINE.plusSeconds(i / 2).atOffset(i % 2 == 0 ? PLUS_ONE : PLUS_TWO), i}
					),
					i + 1
				);
			}

			roundTripPaged(
				legacy, ownerOf(base, legacy),
				index -> index.addRecord(new Serializable[]{NINE.minusSeconds(1).atOffset(PLUS_ONE), -1}, 100_000)
			);
		}
	}

	/*
		PRIVATE HELPERS
	 */

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
		return new LegacyOwnerSortIndexWriter(ENTITY_INDEX_PK, ATTRIBUTE_KEY, base);
	}

	/**
	 * Builds the owner the current engine builds from the same raw values written one by one - the index the
	 * corrected legacy index must equal.
	 *
	 * @param base   the comparator base
	 * @param legacy the legacy index whose raw values are written
	 * @return the expected owner sort index
	 */
	@Nonnull
	private static OwnerSortIndex ownerOf(
		@Nonnull ComparatorSource[] base, @Nonnull LegacyOwnerSortIndexWriter legacy
	) {
		final OwnerSortIndex owner = base.length == 1 ?
			new OwnerSortIndex(base[0].type(), ATTRIBUTE_KEY) : new OwnerSortIndex(base, ATTRIBUTE_KEY);
		for (Map.Entry<Integer, Serializable> entry : legacy.getValuesByRecord().entrySet()) {
			if (entry.getValue() instanceof ComparableArray compound) {
				owner.addRecord(compound.array().clone(), entry.getKey());
			} else {
				owner.addRecord(entry.getValue(), entry.getKey());
			}
		}
		return owner;
	}

	/**
	 * Loads the legacy index from its inline part, without persisting it.
	 *
	 * @param legacy the legacy index
	 * @return the corrected index
	 */
	@Nonnull
	private static OwnerSortIndex loadLegacyInline(@Nonnull LegacyOwnerSortIndexWriter legacy) {
		return loadInline(legacy.toInlinePart());
	}

	/**
	 * Persists the legacy index as an inline part and loads it through the inline load path, asserting it equals the
	 * expected owner and is not flagged dirty - so the emission every entity index runs into a discarded sink right
	 * after it is loaded writes nothing. Then applies the same write to both, persists what the corrected index emits
	 * and asserts that this loads equal again, with normalized values only.
	 *
	 * @param legacy   the legacy index
	 * @param expected the owner the current engine builds from the same values
	 * @param write    the write applied to both indexes
	 * @return the index loaded from the rewritten part
	 */
	@Nonnull
	private OwnerSortIndex roundTripInline(
		@Nonnull LegacyOwnerSortIndexWriter legacy,
		@Nonnull OwnerSortIndex expected,
		@Nonnull Consumer<OwnerSortIndex> write
	) {
		final OffsetIndex offsetIndex = openWritableOffsetIndex();
		try {
			offsetIndex.put(LEGACY_VERSION, legacy.toInlinePart());
			offsetIndex.flush(LEGACY_VERSION);

			final SortIndexStoragePart legacyPart = readRoot(offsetIndex, LEGACY_VERSION);
			assertNotNull(legacyPart);
			assertFalse(legacyPart.isPaged(), "the legacy part must be inline");
			final OwnerSortIndex corrected = loadInline(legacyPart);
			assertSameSortIndex(expected, corrected, "legacy inline part");
			assertTrue(emit(corrected).isEmpty(), "a corrected index must not be flagged dirty at load");

			write.accept(expected);
			write.accept(corrected);
			final List<StoragePart> emission = emit(corrected);
			writeEmission(offsetIndex, REWRITTEN_VERSION, emission);
			offsetIndex.flush(REWRITTEN_VERSION);

			final SortIndexStoragePart rewrittenPart = readRoot(offsetIndex, REWRITTEN_VERSION);
			assertNormalized(rewrittenPart.getComparatorBase(), List.of(rewrittenPart.getSortedRecordsValues()));
			final OwnerSortIndex reloaded = loadInline(rewrittenPart);
			assertSameSortIndex(expected, reloaded, "rewritten inline part");
			return reloaded;
		} finally {
			IOUtils.closeQuietly(offsetIndex::close);
		}
	}

	/**
	 * Persists the legacy index as leaf pages and loads it through the paged load path, asserting it equals the
	 * expected owner and is not flagged dirty. Then applies the same write to both, persists what the corrected index
	 * emits - asserting that every legacy page is retired and every live page rewritten with normalized values - and
	 * asserts that the rewritten pages load equal again after the offset index is reopened.
	 *
	 * @param legacy   the legacy index
	 * @param expected the owner the current engine builds from the same values
	 * @param write    the write applied to both indexes
	 */
	private void roundTripPaged(
		@Nonnull LegacyOwnerSortIndexWriter legacy,
		@Nonnull OwnerSortIndex expected,
		@Nonnull Consumer<OwnerSortIndex> write
	) {
		final OffsetIndex offsetIndex = openWritableOffsetIndex();
		final OffsetIndexDescriptor rewrittenDescriptor;
		final int[] legacyPageSequences;
		try {
			writeEmission(offsetIndex, LEGACY_VERSION, legacy.toPagedParts(0));
			offsetIndex.flush(LEGACY_VERSION);

			final SortIndexStoragePart legacyRoot = readRoot(offsetIndex, LEGACY_VERSION);
			assertNotNull(legacyRoot);
			assertTrue(legacyRoot.isPaged(), "the legacy part must be paged");
			legacyPageSequences = legacyRoot.getLeafPageSequences();
			assertTrue(legacyPageSequences.length >= 2, "the legacy index must span several leaf pages");
			final OwnerSortIndex corrected = loadPaged(offsetIndex, LEGACY_VERSION, legacyRoot);
			assertSameSortIndex(expected, corrected, "legacy paged part");
			assertTrue(emit(corrected).isEmpty(), "a corrected index must not be flagged dirty at load");

			write.accept(expected);
			write.accept(corrected);
			final List<StoragePart> emission = emit(corrected);
			final SortIndexStoragePart rewrittenRoot = root(emission);
			final Set<Integer> rewrittenPages = new HashSet<>();
			for (int sequence : rewrittenRoot.getLeafPageSequences()) {
				rewrittenPages.add(sequence);
			}
			for (int sequence : legacyPageSequences) {
				assertFalse(rewrittenPages.contains(sequence), "legacy page " + sequence + " must not stay live");
			}
			int emittedPages = 0;
			for (StoragePart part : emission) {
				if (part instanceof SortIndexLeafPagePart page) {
					assertNormalized(rewrittenRoot.getComparatorBase(), List.of(page.getBuckets()));
					emittedPages++;
				}
			}
			assertEquals(rewrittenPages.size(), emittedPages, "every live page must be written by the first flush");
			writeEmission(offsetIndex, REWRITTEN_VERSION, emission);
			rewrittenDescriptor = offsetIndex.flush(REWRITTEN_VERSION);
		} finally {
			IOUtils.closeQuietly(offsetIndex::close);
		}

		final OffsetIndex reopened = loadOffsetIndex(rewrittenDescriptor, REWRITTEN_VERSION);
		try {
			final int streamId = streamId(reopened);
			for (int sequence : legacyPageSequences) {
				assertNull(
					reopened.get(
						REWRITTEN_VERSION, AbstractLeafPagePart.computeUniquePartId(streamId, sequence),
						SortIndexLeafPagePart.class
					),
					"legacy page " + sequence + " must be removed by the rewriting flush"
				);
			}
			final OwnerSortIndex reloaded =
				loadPaged(reopened, REWRITTEN_VERSION, readRoot(reopened, REWRITTEN_VERSION));
			assertSameSortIndex(expected, reloaded, "rewritten paged part");
		} finally {
			IOUtils.closeQuietly(reopened::close);
		}
	}

	/**
	 * Asserts every temporal value or compound element is in its normalized form - the form the normalizer maps onto
	 * itself, which is what makes a load skip the correction.
	 *
	 * @param base   the comparator base
	 * @param values the persisted values, or buckets holding them
	 */
	private static void assertNormalized(@Nonnull ComparatorSource[] base, @Nonnull List<?> values) {
		for (Object item : values) {
			final Serializable value = item instanceof ValueToRecord bucket ? bucket.getValue() : (Serializable) item;
			final Serializable[] elements = value instanceof ComparableArray compound ?
				compound.array() : new Serializable[]{value};
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
	 * Loads an inline part the way the attribute index loader loads an owner part.
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
	 * Loads a paged part the way the attribute index loader loads it.
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
		final int streamId = streamId(offsetIndex);
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
		return OwnerSortIndex.fromPersistedPages(
			root.getComparatorBase(), null, root.getAttributeIndexKey(), root.getIndexedDecimalPlaces(),
			orderedPageSequences, perPageBuckets, root.getHighWaterPageSequence()
		);
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
	 * Reads the root part of the test attribute.
	 *
	 * @param offsetIndex    the offset index
	 * @param catalogVersion the version to read at
	 * @return the root part, or `null`
	 */
	private static SortIndexStoragePart readRoot(@Nonnull OffsetIndex offsetIndex, long catalogVersion) {
		final long rootPK = AttributeIndexStoragePart.computeUniquePartId(
			ENTITY_INDEX_PK, AttributeIndexType.SORT, ATTRIBUTE_KEY, offsetIndex.getReadOnlyKeyCompressor()
		);
		return offsetIndex.get(catalogVersion, rootPK, SortIndexStoragePart.class);
	}

	/**
	 * Resolves the leaf-page stream id of the test attribute.
	 *
	 * @param offsetIndex the offset index
	 * @return the stream id
	 */
	private static int streamId(@Nonnull OffsetIndex offsetIndex) {
		return offsetIndex.getReadOnlyKeyCompressor().getId(
			new LeafStreamKey(ENTITY_INDEX_PK, new AttributeKeyWithIndexType(ATTRIBUTE_KEY, AttributeIndexType.SORT))
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
