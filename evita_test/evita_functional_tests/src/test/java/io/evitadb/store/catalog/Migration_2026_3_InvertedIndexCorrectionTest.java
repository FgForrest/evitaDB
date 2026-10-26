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

package io.evitadb.store.catalog;

import io.evitadb.dataType.DateTimeRange;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.page.PageEmission;
import io.evitadb.store.catalog.LegacyInvertedIndexCorrection.CorrectedBuckets;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Map;
import java.util.function.Function;

import static io.evitadb.dataType.EvitaDataTypes.clampToSupportedWindow;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static java.util.Arrays.copyOf;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the protocol 6 → 7 migration correction of legacy bucket keys followed by a clean production restore.
 * Collisions, range ordering, retained value ids and page boundaries are checked before and after restoration.
 */
@DisplayName("Migration corrects legacy filter buckets and restores them cleanly")
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(DATA_TYPE)
class Migration_2026_3_InvertedIndexCorrectionTest {
	/**
	 * The millisecond every sub-millisecond twin in this suite collapses onto.
	 */
	private static final Instant COLLAPSE_TARGET = Instant.parse("2026-05-20T12:19:26.123Z");
	/**
	 * The lower twin — the value a pre-truncation writer would have persisted first.
	 */
	private static final Instant LOWER_TWIN = Instant.parse("2026-05-20T12:19:26.123000001Z");
	/**
	 * The upper twin, still inside the same millisecond.
	 */
	private static final Instant UPPER_TWIN = Instant.parse("2026-05-20T12:19:26.123999999Z");
	/**
	 * A neighbouring millisecond that must stay a bucket of its own — without it every assertion below would also
	 * pass on an implementation that simply merged everything.
	 */
	private static final Instant NEIGHBOUR = Instant.parse("2026-05-20T12:19:26.124Z");
	/**
	 * The name the value-id-carrying fixtures register themselves under.
	 */
	private static final String VALUE_ID_CONSUMER = "sub-millisecond-migration-test";

	/**
	 * The lower `LocalTime` twin — the value a pre-truncation writer would have persisted first.
	 */
	private static final LocalTime LOWER_TWIN_TIME = LocalTime.of(12, 19, 26, 123_000_001);
	/**
	 * The upper `LocalTime` twin, still inside the same millisecond.
	 */
	private static final LocalTime UPPER_TWIN_TIME = LocalTime.of(12, 19, 26, 123_999_999);
	/**
	 * The millisecond both `LocalTime` twins collapse onto.
	 */
	private static final LocalTime COLLAPSE_TARGET_TIME = LocalTime.of(12, 19, 26, 123_000_000);
	/**
	 * A neighbouring `LocalTime` millisecond that must stay a bucket of its own.
	 */
	private static final LocalTime NEIGHBOUR_TIME = LocalTime.of(12, 19, 26, 124_000_000);

	/** Canonical input has no correction result and therefore emits no replacement parts. */
	@Test
	@DisplayName("leaves already-canonical inline and paged bucket generations untouched")
	void shouldPlanNoRewriteForCanonicalTrees() {
		final ValueToRecord[][] pages = {
			{new ValueToRecordBitmap(COLLAPSE_TARGET, 1)}, {new ValueToRecordBitmap(NEIGHBOUR, 2)}
		};
		assertNull(LegacyInvertedIndexCorrection.correct(
			OffsetDateTime.class, 0, pages, new int[][]{{11}, {12}}, Comparator.naturalOrder(), true
		));
		assertNull(LegacyInvertedIndexCorrection.correct(
			OffsetDateTime.class, 0, new ValueToRecord[][]{{pages[0][0], pages[1][0]}},
			new int[][]{{11, 12}}, Comparator.naturalOrder(), false
		));
	}

	/** An inversion outside the released range ordering must remain visible to production corruption checks. */
	@Test
	@DisplayName("does not heal an unrecognized range inversion or a stale page twin")
	void shouldLeaveUnrecognizedInversionsForTheLoader() {
		final OffsetDateTime base = OffsetDateTime.parse("2024-01-01T00:00:00Z");
		final DateTimeRange lower = DateTimeRange.between(base, base.plusSeconds(1));
		final DateTimeRange upper = DateTimeRange.between(base.plusSeconds(3), base.plusSeconds(4));
		final ValueToRecord[][] pages = {
			{new ValueToRecordBitmap(upper, 1)}, {new ValueToRecordBitmap(lower, 2)}
		};
		assertNull(LegacyInvertedIndexCorrection.correct(
			DateTimeRange.class, 0, pages, null, Comparator.naturalOrder(), true
		));
		final GenericEvitaInternalError error = assertThrows(GenericEvitaInternalError.class, () ->
			InvertedIndex.fromPersistedPages(DateTimeRange.class, new int[]{1, 2}, pages, null, 2,
				normalizer(DateTimeRange.class), Comparator.naturalOrder(), 0));
		assertTrue(error.getMessage().contains("index corruption"));
	}

	/** A page repeating its predecessor's last stored key is a stale twin, not two values canonicalization joined. */
	@Test
	@DisplayName("does not heal a stale page twin that repeats its predecessor's last stored key")
	void shouldLeaveAnEqualKeyPageTwinForTheLoader() {
		final ValueToRecord[][] pages = {
			{new ValueToRecordBitmap(COLLAPSE_TARGET, 1)},
			{new ValueToRecordBitmap(COLLAPSE_TARGET, 2), new ValueToRecordBitmap(NEIGHBOUR, 3)}
		};

		final CorrectedBuckets corrected = LegacyInvertedIndexCorrection.correct(
			OffsetDateTime.class, 0, pages, null, Comparator.naturalOrder(), true
		);

		assertNull(corrected, "a repeated stored key must reach the loader unchanged rather than be merged");
		final GenericEvitaInternalError error = assertThrows(GenericEvitaInternalError.class, () ->
			InvertedIndex.fromPersistedPages(OffsetDateTime.class, new int[]{1, 2}, pages, null, 2,
				normalizer(), Comparator.naturalOrder(), 0));
		assertTrue(error.getMessage().contains("index corruption"));
	}

	/** A clamp collision and a truncation collision are merged in the very same correction result. */
	@Test
	@DisplayName("clamps before canonicalizing and unions every colliding record")
	void shouldClampAndMergeInOneCorrection() {
		final Instant edge = clampToSupportedWindow(Instant.MAX);
		final ValueToRecord[][] pages = {
			{new ValueToRecordBitmap(edge, 1)},
			{new ValueToRecordBitmap(Instant.MAX.minusSeconds(1), 2), new ValueToRecordBitmap(Instant.MAX, 3)}
		};
		final CorrectedBuckets corrected = LegacyInvertedIndexCorrection.correct(
			OffsetDateTime.class, 0, pages, new int[][]{{11}, {12, 13}}, Comparator.naturalOrder(), true
		);
		assertNotNull(corrected);
		assertEquals(1, corrected.pages().length);
		assertArrayEquals(new int[]{1, 2, 3}, corrected.pages()[0][0].getRecordIds().getArray());
		assertArrayEquals(new int[]{11}, corrected.valueIds()[0]);
	}

	/**
	 * The normalizer an `OffsetDateTime` filter index is built with — the declared type every `Instant`-keyed
	 * fixture in this suite uses.
	 */
	@Nonnull
	private static Function<Object, Serializable> normalizer() {
		return normalizer(OffsetDateTime.class);
	}

	/**
	 * The normalizer a filter index over the given declared attribute type is built with. Each declared type takes a
	 * different branch of {@link FilterIndex#getNormalizer(Class, int)} and rides a different key codec, so the
	 * migration correction has to be driven through each of them rather than argued from one.
	 */
	@Nonnull
	private static Function<Object, Serializable> normalizer(@Nonnull Class<?> declaredType) {
		return FilterIndex.getNormalizer(declaredType, 0);
	}

	/** Inline migration fixtures restored with canonical keys and retained ids. */
	@Nested
	@DisplayName("inline migration correction followed by clean restore")
	class InlineMigrationCorrection {

		/** Migration unions colliding instant buckets while preserving the neighbouring millisecond. */
		@Test
		@DisplayName("merges two persisted sub-millisecond buckets into one, keeping both records")
		void shouldMergeCollidingPersistedBuckets() {
			final ValueToRecordBitmap[] persisted = {
				new ValueToRecordBitmap(LOWER_TWIN, 1),
				new ValueToRecordBitmap(UPPER_TWIN, 2, 3),
				new ValueToRecordBitmap(NEIGHBOUR, 4)
			};

			final InvertedIndex reloaded = migrateInline(
				OffsetDateTime.class, persisted, normalizer(), Comparator.naturalOrder(), 0
			);

			assertEquals(2, reloaded.getBucketCount(), "the two twins must share one bucket");
			assertArrayEquals(
				new int[]{1, 2, 3},
				reloaded.getRecordsEqualTo(COLLAPSE_TARGET).getArray(),
				"every record of both twins must survive the collapse"
			);
			assertArrayEquals(
				new int[]{4},
				reloaded.getRecordsEqualTo(NEIGHBOUR).getArray(),
				"the neighbouring millisecond must keep its own bucket"
			);
			assertFalse(reloaded.isDirty(),
				"a migrated tree loads clean"
			);
		}

		/** The merged instant bucket restores under the exact millisecond key produced by migration. */
		@Test
		@DisplayName("keys the merged bucket by the truncated instant, not by the value that created it")
		void shouldKeyTheMergedBucketByTheTruncatedInstant() {
			final ValueToRecordBitmap[] persisted = {
				new ValueToRecordBitmap(LOWER_TWIN, 1),
				new ValueToRecordBitmap(UPPER_TWIN, 2)
			};

			final InvertedIndex reloaded = migrateInline(
				OffsetDateTime.class, persisted, normalizer(), Comparator.naturalOrder(), 0
			);

			final ValueToRecordBitmap[] rehydrated = reloaded.getValueToRecordBitmap();
			assertEquals(1, rehydrated.length);
			assertEquals(
				COLLAPSE_TARGET, rehydrated[0].getValue(),
				"the surviving bucket must be keyed by the millisecond both twins collapse onto"
			);
		}

		/** Migration compacts inline ids with their buckets without mutating the persisted id array. */
		@Test
		@DisplayName("compacts inline value ids with the buckets retained by migration")
		void shouldRealignTheInlineValueIdColumn() {
			// The migration compacts the positional id column together with the buckets, so the production loader
			// receives matching columns and can restore the retained identities directly.
			final ValueToRecordBitmap[] persisted = {
				new ValueToRecordBitmap(LOWER_TWIN, 1),
				new ValueToRecordBitmap(UPPER_TWIN, 2),
				new ValueToRecordBitmap(NEIGHBOUR, 3)
			};
			final int[] persistedValueIds = {11, 12, 13};

			final InvertedIndex reloaded = migrateInline(
				OffsetDateTime.class, persisted, normalizer(), Comparator.naturalOrder(), 0
			);
			// exactly what AttributeIndexLoader does for a SINGLE (inline) filter index carrying value ids
			reloaded.restoreValueIds(
				14,
				migratedValueIds(
					persisted, persistedValueIds, normalizer(), Comparator.naturalOrder()
				)
			);

			assertEquals(
				11, reloaded.getValueId(COLLAPSE_TARGET),
				"the surviving bucket must keep the id of the twin that created it, not the absorbed one's"
			);
			assertEquals(
				13, reloaded.getValueId(NEIGHBOUR),
				"the bucket after the collapse must keep its own id rather than sliding onto the retired one"
			);
			assertEquals(14, reloaded.getNextValueId(), "the migration correction must mint nothing");
			assertArrayEquals(
				new int[]{11, 12, 13}, persistedValueIds,
				"the realignment must not mutate the array it was handed - it belongs to the storage part"
			);
		}

		/** Canonical inline buckets retain their records and restore without pending changes. */
		@Test
		@DisplayName("preserves canonical inline buckets and restores cleanly")
		void shouldLeaveACanonicalIndexAlone() {
			// Canonical input needs no migration correction and must restore without pending changes.
			final ValueToRecordBitmap[] persisted = {
				new ValueToRecordBitmap(COLLAPSE_TARGET, 1),
				new ValueToRecordBitmap(NEIGHBOUR, 2)
			};

			final InvertedIndex reloaded = migrateInline(
				OffsetDateTime.class, persisted, normalizer(), Comparator.naturalOrder(), 0
			);

			assertEquals(2, reloaded.getBucketCount());
			assertArrayEquals(new int[]{1}, reloaded.getRecordsEqualTo(COLLAPSE_TARGET).getArray());
			assertArrayEquals(new int[]{2}, reloaded.getRecordsEqualTo(NEIGHBOUR).getArray());
			assertFalse(reloaded.isDirty(), "a canonical tree must restore without pending changes");
		}
	}

	/** Migration fixtures for the distinct LocalTime key codec. */
	@Nested
	@DisplayName("LocalTime migration correction followed by clean restore")
	class InlineLocalTimeMigrationCorrection {

		/** Migration unions LocalTime collisions while preserving records in the neighbouring millisecond. */
		@Test
		@DisplayName("merges two persisted sub-millisecond LocalTime buckets into one, keeping both records")
		void shouldMergeCollidingPersistedLocalTimeBuckets() {
			// a `LocalTime` key rides `LongKeyCodec.LOCAL_TIME` (nano-of-day) through its own normalizer branch, so
			// nothing the `Instant` fixtures prove carries over: the claim that legacy twins reach the same
			// comparator-based collision migration correction has to be driven, not argued
			final ValueToRecordBitmap[] persisted = {
				new ValueToRecordBitmap(LOWER_TWIN_TIME, 1),
				new ValueToRecordBitmap(UPPER_TWIN_TIME, 2, 3),
				new ValueToRecordBitmap(NEIGHBOUR_TIME, 4)
			};

			final InvertedIndex reloaded = migrateInline(
				LocalTime.class, persisted, normalizer(LocalTime.class), Comparator.naturalOrder(), 0
			);

			assertEquals(2, reloaded.getBucketCount(), "the two twins must share one bucket");
			assertArrayEquals(
				new int[]{1, 2, 3},
				reloaded.getRecordsEqualTo(COLLAPSE_TARGET_TIME).getArray(),
				"every record of both twins must survive the collapse"
			);
			assertArrayEquals(
				new int[]{4},
				reloaded.getRecordsEqualTo(NEIGHBOUR_TIME).getArray(),
				"the neighbouring millisecond must keep its own bucket"
			);
			assertFalse(reloaded.isDirty(),
				"a migrated tree loads clean"
			);
		}

		/** The merged LocalTime bucket restores under its truncated millisecond value. */
		@Test
		@DisplayName("keys the merged LocalTime bucket by the truncated value, not by the one that created it")
		void shouldKeyTheMergedLocalTimeBucketByTheTruncatedValue() {
			final ValueToRecordBitmap[] persisted = {
				new ValueToRecordBitmap(LOWER_TWIN_TIME, 1),
				new ValueToRecordBitmap(UPPER_TWIN_TIME, 2)
			};

			final InvertedIndex reloaded = migrateInline(
				LocalTime.class, persisted, normalizer(LocalTime.class), Comparator.naturalOrder(), 0
			);

			final ValueToRecordBitmap[] rehydrated = reloaded.getValueToRecordBitmap();
			assertEquals(1, rehydrated.length);
			assertEquals(
				COLLAPSE_TARGET_TIME, rehydrated[0].getValue(),
				"the surviving bucket must be keyed by the millisecond both twins collapse onto"
			);
		}
	}

	/**
	 * A `DateTimeRange`-keyed index persisted by a release that compared at second granularity, whose persisted
	 * bucket order the millisecond comparison **reverses** rather than merely collapses.
	 *
	 * The scalar temporal move is a monotone refinement — a finer key can only split a tie, never swap a pair — so
	 * the collapse-only correction the nests above exercise is enough for it. `DateTimeRange` is different: the move
	 * changed which bound decides a tie, because the previous encoding compared whole seconds and derived an open
	 * bound's threshold from the *other* bound's zone offset. Two shapes reverse, and neither needs the persisted
	 * bytes to be malformed - both were correctly ascending when they were written:
	 *
	 * - two closed ranges opening in the same second, ordered by their upper bound then, ordered by their
	 *   sub-second lower bound now;
	 * - two open-from ranges written at different zone offsets, ordered by descending offset then and by their
	 *   upper bound now.
	 *
	 * The persisted values themselves carry no thresholds — `DateTimeRangeSerializer` reconstructs a range from its
	 * two precise bounds. The migration recomputes their comparison at the new scale and corrects recognized
	 * legacy ordering before the production restore sees the pages.
	 */
	@Nested
	@DisplayName("legacy DateTimeRange migration correction followed by clean paged restore")
	class PagedLegacyRangeOrderMigrationCorrection {
		/**
		 * The moment the closed-range fixtures open in; its whole second is what the previous encoding compared them
		 * by.
		 */
		private static final OffsetDateTime RANGE_BASE =
			LocalDateTime.of(2024, 1, 1, 0, 0, 0).atOffset(ZoneOffset.UTC);

		/** Migration reorders closed ranges within one page and preserves every record on restore. */
		@Test
		@DisplayName("two closed ranges opening in one second, persisted inside a single page")
		void shouldRestoreRangeBucketsWhosePersistedOrderTheMillisecondComparisonReverses() {
			// persisted ascending on (fromSecond, toSecond): the two share a lower SECOND, so the upper bound broke
			// the tie and the 3-second range sorted before the 5-second one. Compared in milliseconds the lower
			// bounds no longer tie, and 900ms sorts AFTER 100ms - the pair is inverted with nothing wrong on disk
			final DateTimeRange laterInTheSecond = DateTimeRange.between(
				RANGE_BASE.plusNanos(900_000_000L), RANGE_BASE.plusSeconds(3)
			);
			final DateTimeRange earlierInTheSecond = DateTimeRange.between(
				RANGE_BASE.plusNanos(100_000_000L), RANGE_BASE.plusSeconds(5)
			);
			assertTrue(
				laterInTheSecond.compareTo(earlierInTheSecond) > 0,
				"the fixture must be inverted under the millisecond comparison, or the test proves nothing"
			);

			final ValueToRecordBitmap[][] perPageBuckets = {
				{
					new ValueToRecordBitmap(laterInTheSecond, 1),
					new ValueToRecordBitmap(earlierInTheSecond, 2)
				},
				{new ValueToRecordBitmap(DateTimeRange.since(RANGE_BASE.plusDays(1)), 3)}
			};

			final InvertedIndex reloaded = migratePages(
				DateTimeRange.class, new int[]{0, 1}, perPageBuckets, null, 1,
				normalizer(DateTimeRange.class), Comparator.naturalOrder(), 0
			);

			assertEquals(
				3, reloaded.getBucketCount(), "no bucket may be lost when the migration re-sorts the persisted order"
			);
			assertArrayEquals(new int[]{2}, reloaded.getRecordsEqualTo(earlierInTheSecond).getArray());
			assertArrayEquals(new int[]{1}, reloaded.getRecordsEqualTo(laterInTheSecond).getArray());

			final ValueToRecordBitmap[] rehydrated = reloaded.getValueToRecordBitmap();
			assertEquals(3, rehydrated.length);
			assertEquals(
				earlierInTheSecond, rehydrated[0].getValue(),
				"the restored tree must iterate in the order the millisecond comparison sorts the keys in"
			);
			assertEquals(laterInTheSecond, rehydrated[1].getValue());
			assertEquals(DateTimeRange.since(RANGE_BASE.plusDays(1)), rehydrated[2].getValue());
			assertFalse(reloaded.isDirty(),
				"a migrated range tree loads clean"
			);
		}

		/** Migration reorders open-from ranges across pages without losing records or leaving dirty state. */
		@Test
		@DisplayName("two open-from ranges written at different offsets, persisted across a page boundary")
		void shouldRestoreRangeBucketsWhosePersistedOrderTheMillisecondComparisonReversesAcrossAPageBoundary() {
			// this shape needs no sub-second precision at all. The previous encoding derived an open-from bound's
			// threshold from the OTHER bound's zone offset, so `until(X)` sorted by descending offset first and by
			// the upper bound only on a tie; both open-from bounds now sit on one offset-independent constant, and
			// the upper bounds alone decide - which reverses this pair
			final DateTimeRange atPositiveOffset = DateTimeRange.until(
				LocalDateTime.of(2024, 6, 1, 0, 0).atOffset(ZoneOffset.ofHours(2))
			);
			final DateTimeRange atUtc = DateTimeRange.until(
				LocalDateTime.of(2024, 1, 1, 0, 0).atOffset(ZoneOffset.UTC)
			);
			assertEquals(
				atPositiveOffset.getFrom(), atUtc.getFrom(),
				"both open-from bounds must land on the one offset-independent constant"
			);
			assertTrue(
				atPositiveOffset.compareTo(atUtc) > 0,
				"the fixture must be inverted under the millisecond comparison, or the test proves nothing"
			);

			final ValueToRecordBitmap[][] perPageBuckets = {
				{new ValueToRecordBitmap(atPositiveOffset, 1)},
				{new ValueToRecordBitmap(atUtc, 2)}
			};

			final InvertedIndex reloaded = migratePages(
				DateTimeRange.class, new int[]{0, 1}, perPageBuckets, null, 1,
				normalizer(DateTimeRange.class), Comparator.naturalOrder(), 0
			);

			assertEquals(
				2, reloaded.getBucketCount(), "no bucket may be lost when the migration re-sorts the persisted order"
			);
			assertArrayEquals(new int[]{2}, reloaded.getRecordsEqualTo(atUtc).getArray());
			assertArrayEquals(new int[]{1}, reloaded.getRecordsEqualTo(atPositiveOffset).getArray());

			final ValueToRecordBitmap[] rehydrated = reloaded.getValueToRecordBitmap();
			assertEquals(2, rehydrated.length);
			assertEquals(
				atUtc, rehydrated[0].getValue(),
				"the restored tree must iterate in the order the millisecond comparison sorts the keys in"
			);
			assertEquals(atPositiveOffset, rehydrated[1].getValue());
			assertFalse(reloaded.isDirty(),
				"a migrated range tree loads clean"
			);
		}

		/** Migration merges equal range keys while restoring the current order of the remaining ranges. */
		@Test
		@DisplayName("buckets that collide under the new comparison are merged while the index is re-sorted")
		void shouldMergeCollidingBucketsWhileResortingALegacyRangeIndex() {
			// two open-from ranges naming the SAME moment at two different zone offsets were distinct keys at second
			// granularity - each one's open lower bound took its threshold from its own upper bound's offset - and are
			// one key now. Migrated together with an inverted pair they meet the re-sort, which has to merge them on
			// the way: fresh pages carrying two equal keys are refused by the bulk load that builds them
			final DateTimeRange atUtc = DateTimeRange.until(
				LocalDateTime.of(2024, 3, 1, 12, 0).atOffset(ZoneOffset.UTC)
			);
			final DateTimeRange sameMomentAtPositiveOffset = DateTimeRange.until(
				LocalDateTime.of(2024, 3, 1, 13, 0).atOffset(ZoneOffset.ofHours(1))
			);
			assertEquals(
				atUtc, sameMomentAtPositiveOffset,
				"the two open-from twins must be one key under the millisecond comparison"
			);
			final DateTimeRange laterInTheSecond = DateTimeRange.between(
				RANGE_BASE.plusNanos(900_000_000L), RANGE_BASE.plusSeconds(3)
			);
			final DateTimeRange earlierInTheSecond = DateTimeRange.between(
				RANGE_BASE.plusNanos(100_000_000L), RANGE_BASE.plusSeconds(5)
			);

			final ValueToRecordBitmap[][] perPageBuckets = {
				{
					new ValueToRecordBitmap(sameMomentAtPositiveOffset, 1),
					new ValueToRecordBitmap(atUtc, 2)
				},
				{
					new ValueToRecordBitmap(laterInTheSecond, 3),
					new ValueToRecordBitmap(earlierInTheSecond, 4)
				}
			};

			final InvertedIndex reloaded = migratePages(
				DateTimeRange.class, new int[]{0, 1}, perPageBuckets, null, 1,
				normalizer(DateTimeRange.class), Comparator.naturalOrder(), 0
			);

			assertEquals(3, reloaded.getBucketCount(),
				"the two twins must share one bucket, the other two keep theirs");
			assertArrayEquals(
				new int[]{1, 2}, reloaded.getRecordsEqualTo(atUtc).getArray(),
				"every record of both twins must survive the merge the re-sort carried out"
			);
			assertArrayEquals(new int[]{4}, reloaded.getRecordsEqualTo(earlierInTheSecond).getArray());
			assertArrayEquals(new int[]{3}, reloaded.getRecordsEqualTo(laterInTheSecond).getArray());

			final ValueToRecordBitmap[] rehydrated = reloaded.getValueToRecordBitmap();
			assertEquals(3, rehydrated.length);
			assertEquals(atUtc, rehydrated[0].getValue());
			assertEquals(earlierInTheSecond, rehydrated[1].getValue());
			assertEquals(laterInTheSecond, rehydrated[2].getValue());
			assertFalse(reloaded.isDirty(), "a migrated tree loads clean");
		}

		/** Ranges already in current order restore with their original records and no pending changes. */
		@Test
		@DisplayName("preserves canonical range order through migration correction and clean restore")
		void shouldLeaveALegacyRangeIndexWhoseOrderSurvivesAlone() {
			// The migration must preserve an order the current comparator already agrees with; the restored tree
			// then starts clean rather than scheduling another rewrite.
			final DateTimeRange first = DateTimeRange.between(RANGE_BASE, RANGE_BASE.plusSeconds(3));
			final DateTimeRange second = DateTimeRange.between(RANGE_BASE.plusSeconds(10), RANGE_BASE.plusSeconds(20));
			final DateTimeRange third = DateTimeRange.since(RANGE_BASE.plusDays(1));
			final ValueToRecordBitmap[][] perPageBuckets = {
				{new ValueToRecordBitmap(first, 1), new ValueToRecordBitmap(second, 2)},
				{new ValueToRecordBitmap(third, 3)}
			};

			final InvertedIndex reloaded = migratePages(
				DateTimeRange.class, new int[]{0, 1}, perPageBuckets, null, 1,
				normalizer(DateTimeRange.class), Comparator.naturalOrder(), 0
			);

			assertEquals(3, reloaded.getBucketCount());
			assertTrue(reloaded.isPaged());
			assertFalse(reloaded.isDirty(), "a canonical tree must restore without pending changes");
			assertArrayEquals(new int[]{1}, reloaded.getRecordsEqualTo(first).getArray());
			assertArrayEquals(new int[]{2}, reloaded.getRecordsEqualTo(second).getArray());
			assertArrayEquals(new int[]{3}, reloaded.getRecordsEqualTo(third).getArray());
		}
	}

	/** Migration fixtures exercising page boundaries, retained ids and merge targets. */
	@Nested
	@DisplayName("paged migration correction followed by clean restore")
	class PagedMigrationCorrection {
		/**
		 * How many buckets the seeded index holds — comfortably more than one 256-slot leaf, so the emission really
		 * produces several pages and the cross-page case below has a boundary to straddle.
		 */
		private static final int SEEDED_BUCKETS = 1_000;

		/** Migration merges an intra-page collision and the corrected tree restores cleanly. */
		@Test
		@DisplayName("merges two sub-millisecond buckets that sit inside one persisted page")
		void shouldMergeCollisionInsideOnePage() {
			final PersistedPages pages = seedAndEmit();
			final int page = pages.perPageBuckets.length / 2;
			// rewrite one interior bucket into a sub-millisecond twin of its predecessor: still strictly ascending as
			// `Instant`s (which is all a pre-truncation writer ever guaranteed), yet both truncate to one key
			final ValueToRecordBitmap[] buckets = pages.perPageBuckets[page];
			final Instant predecessor = (Instant) buckets[10].getValue();
			buckets[11] = new ValueToRecordBitmap(predecessor.plusNanos(1), buckets[11].getRecordIds());

			final InvertedIndex reloaded = pages.reload();

			assertEquals(
				SEEDED_BUCKETS - 1, reloaded.getBucketCount(), "exactly one bucket must have been absorbed"
			);
			assertArrayEquals(
				new int[]{recordOf(predecessor), recordOf(predecessor) + 1},
				reloaded.getRecordsEqualTo(predecessor).getArray(),
				"the absorbed bucket's record must have joined its predecessor"
			);
			assertFalse(reloaded.isDirty(), "a migrated tree loads clean");
		}

		/** Migration unions records from colliding buckets on opposite sides of a page boundary. */
		@Test
		@DisplayName("merges two sub-millisecond buckets that straddle a page boundary")
		void shouldMergeCollisionAcrossAPageBoundary() {
			final PersistedPages pages = seedAndEmit();
			assertTrue(pages.perPageBuckets.length > 1, "the seeded index must span several pages");
			final ValueToRecordBitmap[] previousPage = pages.perPageBuckets[0];
			final ValueToRecordBitmap[] nextPage = pages.perPageBuckets[1];
			final Instant lastOfPreviousPage = (Instant) previousPage[previousPage.length - 1].getValue();
			// the first bucket of the next page becomes a sub-millisecond twin of the last bucket of the previous one
			nextPage[0] = new ValueToRecordBitmap(lastOfPreviousPage.plusNanos(1), nextPage[0].getRecordIds());

			final InvertedIndex reloaded = pages.reload();

			assertEquals(SEEDED_BUCKETS - 1, reloaded.getBucketCount());
			assertArrayEquals(
				new int[]{recordOf(lastOfPreviousPage), recordOf(lastOfPreviousPage) + 1},
				reloaded.getRecordsEqualTo(lastOfPreviousPage).getArray(),
				"the record of the twin that opened the next page must have joined the previous page's last bucket"
			);
			assertFalse(reloaded.isDirty(), "a migrated tree loads clean");
		}

		/** An intra-page merge cannot orphan records later merged across the same page boundary. */
		@Test
		@DisplayName("merges across a boundary out of a page that ALSO lost a bucket to an intra-page collision")
		void shouldMergeAcrossABoundaryOutOfAPageThatWasAlsoTrimmed() {
			// the two sibling tests above each exercise ONE condition, and both pass with the merge target aliased to
			// a page array that is about to be copied away - because a page with no intra-page collision is retained
			// BY REFERENCE, so writing through the stale reference happens to write into the returned array.
			// Combine the conditions on one page and the aliasing bites: the page is retained as a COPY, the later
			// cross-boundary merge writes into the orphaned original, and the absorbed record is lost from the index
			// with no exception anywhere
			final PersistedPages pages = seedAndEmit();
			assertTrue(pages.perPageBuckets.length > 1, "the seeded index must span several pages");
			final ValueToRecordBitmap[] previousPage = pages.perPageBuckets[0];
			final ValueToRecordBitmap[] nextPage = pages.perPageBuckets[1];

			// (1) an INTRA-page collision, which makes the page be retained as a trimmed copy
			final Instant intraPageTarget = (Instant) previousPage[10].getValue();
			previousPage[11] =
				new ValueToRecordBitmap(intraPageTarget.plusNanos(1), previousPage[11].getRecordIds());
			// (2) a CROSS-page collision out of that same page's last surviving bucket
			final Instant lastOfPreviousPage = (Instant) previousPage[previousPage.length - 1].getValue();
			nextPage[0] = new ValueToRecordBitmap(lastOfPreviousPage.plusNanos(1), nextPage[0].getRecordIds());

			final InvertedIndex reloaded = pages.reload();

			assertEquals(
				SEEDED_BUCKETS - 2, reloaded.getBucketCount(), "exactly two buckets must have been absorbed"
			);
			assertArrayEquals(
				new int[]{recordOf(intraPageTarget), recordOf(intraPageTarget) + 1},
				reloaded.getRecordsEqualTo(intraPageTarget).getArray(),
				"the intra-page merge must still hold both records"
			);
			assertArrayEquals(
				new int[]{recordOf(lastOfPreviousPage), recordOf(lastOfPreviousPage) + 1},
				reloaded.getRecordsEqualTo(lastOfPreviousPage).getArray(),
				"the cross-page merge must survive the page having been retained as a copy"
			);
			// and the absorbed record must be reachable AT ALL - the failure mode is silent loss, not a wrong bucket
			assertTrue(
				reloaded.getRecordsEqualTo(lastOfPreviousPage).contains(recordOf(lastOfPreviousPage) + 1),
				"the absorbed bucket's record must not have vanished from the index"
			);
		}

		/** Cross-page records survive when the donating page also contains an intra-page collision. */
		@Test
		@DisplayName("merges across a boundary INTO a page that also loses a bucket of its own")
		void shouldMergeAcrossABoundaryIntoAPageThatIsAlsoTrimmed() {
			// the mirror combination: the RECEIVING page is clean but the DONATING page carries an intra-page
			// collision of its own, so both pages are rewritten and the two merges must not interfere
			final PersistedPages pages = seedAndEmit();
			assertTrue(pages.perPageBuckets.length > 1, "the seeded index must span several pages");
			final ValueToRecordBitmap[] previousPage = pages.perPageBuckets[0];
			final ValueToRecordBitmap[] nextPage = pages.perPageBuckets[1];

			final Instant lastOfPreviousPage = (Instant) previousPage[previousPage.length - 1].getValue();
			nextPage[0] = new ValueToRecordBitmap(lastOfPreviousPage.plusNanos(1), nextPage[0].getRecordIds());
			final Instant intraPageTarget = (Instant) nextPage[5].getValue();
			nextPage[6] = new ValueToRecordBitmap(intraPageTarget.plusNanos(1), nextPage[6].getRecordIds());

			final InvertedIndex reloaded = pages.reload();

			assertEquals(SEEDED_BUCKETS - 2, reloaded.getBucketCount());
			assertArrayEquals(
				new int[]{recordOf(lastOfPreviousPage), recordOf(lastOfPreviousPage) + 1},
				reloaded.getRecordsEqualTo(lastOfPreviousPage).getArray(),
				"the cross-page merge must hold both records"
			);
			assertArrayEquals(
				new int[]{recordOf(intraPageTarget), recordOf(intraPageTarget) + 1},
				reloaded.getRecordsEqualTo(intraPageTarget).getArray(),
				"and so must the donating page's own intra-page merge"
			);
		}

		/** Combined intra-page and cross-page merges retain surviving ids and the allocator high-water. */
		@Test
		@DisplayName("keeps the value ids straight when one page is both trimmed and merged across its boundary")
		void shouldKeepValueIdsWhenAPageIsBothTrimmedAndMergedAcross() {
			// the id column is copied on the very same line as the bucket column and under the very same condition,
			// so it has to be shown - not argued - that it carries no equivalent aliasing hazard. It does not, because
			// nothing writes an id after the page is closed: the merge branch retires the absorbed bucket's id rather
			// than moving it. This drives the exact combination that broke the bucket array and checks the ids too
			final PersistedPages pages = seedAndEmit(true);
			assertTrue(pages.perPageBuckets.length > 1, "the seeded index must span several pages");
			final ValueToRecordBitmap[] previousPage = pages.perPageBuckets[0];
			final ValueToRecordBitmap[] nextPage = pages.perPageBuckets[1];

			final Instant intraPageTarget = (Instant) previousPage[10].getValue();
			final int intraPageTargetId = pages.perPageValueIds[0][10];
			previousPage[11] =
				new ValueToRecordBitmap(intraPageTarget.plusNanos(1), previousPage[11].getRecordIds());
			final int lastSlot = previousPage.length - 1;
			final Instant lastOfPreviousPage = (Instant) previousPage[lastSlot].getValue();
			final int lastOfPreviousPageId = pages.perPageValueIds[0][lastSlot];
			nextPage[0] = new ValueToRecordBitmap(lastOfPreviousPage.plusNanos(1), nextPage[0].getRecordIds());

			final InvertedIndex reloaded = pages.reload();

			assertTrue(reloaded.carriesValueIds());
			assertEquals(
				intraPageTargetId, reloaded.getValueId(intraPageTarget),
				"the intra-page merge target must keep the id it was persisted with"
			);
			assertEquals(
				lastOfPreviousPageId, reloaded.getValueId(lastOfPreviousPage),
				"and so must the cross-page merge target, whose slot moved down when the page was trimmed"
			);
			assertEquals(
				pages.nextValueId, reloaded.getNextValueId(), "the migration correction must not mint anything"
			);
			// the records themselves are the thing the aliasing lost, so assert them here too
			assertArrayEquals(
				new int[]{recordOf(lastOfPreviousPage), recordOf(lastOfPreviousPage) + 1},
				reloaded.getRecordsEqualTo(lastOfPreviousPage).getArray(),
				"the cross-page merge must hold both records"
			);
		}

		/** The corrected tree emits neither changed pages nor freed pages on its first restore emission. */
		@Test
		@DisplayName("restores corrected pages without changed or freed pages")
		void shouldRestoreCorrectedPagesWithoutPendingChanges() {
			final PersistedPages pages = seedAndEmit();
			final ValueToRecordBitmap[] buckets = pages.perPageBuckets[1];
			final Instant predecessor = (Instant) buckets[10].getValue();
			buckets[11] = new ValueToRecordBitmap(predecessor.plusNanos(1), buckets[11].getRecordIds());
			final InvertedIndex reloaded = pages.reload();
			final PageEmission<InvertedIndex.LeafPage> emission = reloaded.collectChangedPages();
			assertTrue(emission.changedPages().isEmpty());
			assertEquals(0, emission.freedPageSequences().length);
		}

		/** Migration retains surviving bucket ids and leaves absorbed ids retired below the allocator high-water. */
		@Test
		@DisplayName("keeps the surviving buckets' value ids and retires only the absorbed one's")
		void shouldKeepValueIdsOfSurvivingBuckets() {
			final PersistedPages pages = seedAndEmit(true);
			final int page = pages.perPageBuckets.length / 2;
			final ValueToRecordBitmap[] buckets = pages.perPageBuckets[page];
			final Instant predecessor = (Instant) buckets[10].getValue();
			final Instant successor = (Instant) buckets[12].getValue();
			final int predecessorId = pages.perPageValueIds[page][10];
			final int successorId = pages.perPageValueIds[page][12];
			final int retiredId = pages.perPageValueIds[page][11];
			buckets[11] = new ValueToRecordBitmap(predecessor.plusNanos(1), buckets[11].getRecordIds());

			final InvertedIndex reloaded = pages.reload();

			assertTrue(reloaded.carriesValueIds());
			assertEquals(
				predecessorId, reloaded.getValueId(predecessor),
				"the bucket that absorbed the twin must keep the id it was persisted with"
			);
			assertEquals(
				successorId, reloaded.getValueId(successor),
				"a bucket the migration correction did not touch must keep its id even though a lower slot vanished"
			);
			assertEquals(
				pages.nextValueId, reloaded.getNextValueId(),
				"the migration correction must not mint anything - the retired id " + retiredId +
					" is simply never reused"
			);
		}

		/** Migration compacts a fully absorbed page while retaining its records in the surviving tree. */
		@Test
		@DisplayName("drops a page whose every bucket was absorbed by its predecessor")
		void shouldDropAPageAbsorbedInItsEntirety() {
			// The correction compacts the root's page list when every bucket of a page is absorbed.
			// Hand-built pages exercise this branch because production leaf occupancy cannot produce this fixture.
			final Instant base = COLLAPSE_TARGET;
			final ValueToRecordBitmap[][] perPageBuckets = {
				{new ValueToRecordBitmap(base, 1), new ValueToRecordBitmap(base.plusMillis(1), 2)},
				{new ValueToRecordBitmap(base.plusMillis(1).plusNanos(7), 3)},
				{new ValueToRecordBitmap(base.plusMillis(2), 4)}
			};
			final int[] orderedPageSequences = {0, 1, 2};

			final InvertedIndex reloaded = migratePages(
				OffsetDateTime.class, orderedPageSequences, perPageBuckets, null, 2,
				normalizer(), Comparator.naturalOrder(), 0
			);

			assertEquals(3, reloaded.getBucketCount());
			assertArrayEquals(
				new int[]{2, 3}, reloaded.getRecordsEqualTo(base.plusMillis(1)).getArray(),
				"the absorbed page's record must have joined the predecessor's last bucket"
			);
			final PageEmission<InvertedIndex.LeafPage> emission = reloaded.collectChangedPages();
			assertEquals(
				2, emission.orderedPageSequences().length, "the compacted tree must use fewer pages"
			);
			assertEquals(0, emission.freedPageSequences().length, "corrected pages restore without pending removals");
		}

		/** Canonical paged trees retain page identities and emit no changed or freed pages after restore. */
		@Test
		@DisplayName("preserves canonical page boundaries through migration correction and clean restore")
		void shouldLeaveACanonicalPagedIndexAlone() {
			// Canonical input keeps its page identities through migration correction and the first restore emission.
			final PersistedPages pages = seedAndEmit();

			final InvertedIndex reloaded = pages.reload();

			assertEquals(SEEDED_BUCKETS, reloaded.getBucketCount());
			assertTrue(reloaded.isPaged());
			assertFalse(reloaded.isDirty(), "a canonical tree must restore without pending changes");
			final PageEmission<InvertedIndex.LeafPage> afterReload = reloaded.collectChangedPages();
			assertArrayEquals(pages.orderedPageSequences, afterReload.orderedPageSequences());
			assertTrue(
				afterReload.changedPages().isEmpty(), "canonical migrated pages must restore without pending changes"
			);
			assertEquals(0, afterReload.freedPageSequences().length);
		}

		/**
		 * Builds a millisecond-exact `OffsetDateTime`-keyed index of {@link #SEEDED_BUCKETS} single-record buckets and
		 * emits it as leaf pages — the persisted state the migration tests then mutate into a pre-truncation shape.
		 */
		@Nonnull
		private static PersistedPages seedAndEmit() {
			return seedAndEmit(false);
		}

		/**
		 * Builds a millisecond-exact `OffsetDateTime`-keyed index of {@link #SEEDED_BUCKETS} single-record buckets and
		 * emits it as leaf pages.
		 */
		@Nonnull
		private static PersistedPages seedAndEmit(boolean withValueIds) {
			final InvertedIndex index = new InvertedIndex(
				OffsetDateTime.class, normalizer(), Comparator.naturalOrder(), 0
			);
			if (withValueIds) {
				index.attachValueIdConsumer(VALUE_ID_CONSUMER);
			}
			for (int i = 0; i < SEEDED_BUCKETS; i++) {
				index.addRecord(instantOf(i).atOffset(ZoneOffset.UTC), i + 1);
			}
			final PageEmission<InvertedIndex.LeafPage> emission = index.collectChangedPages();
			index.getPageStreamRegistry().publishStaged();
			final int[] orderedPageSequences = emission.orderedPageSequences();
			final Map<Integer, ValueToRecordBitmap[]> byPageSequence =
				CollectionUtils.createHashMap(orderedPageSequences.length);
			final Map<Integer, int[]> valueIdsByPageSequence =
				CollectionUtils.createHashMap(orderedPageSequences.length);
			for (final InvertedIndex.LeafPage page : emission.changedPages()) {
				final ValueToRecord[] buckets = page.buckets();
				final ValueToRecordBitmap[] copy = new ValueToRecordBitmap[buckets.length];
				for (int i = 0; i < buckets.length; i++) {
					copy[i] = new ValueToRecordBitmap(buckets[i].getValue(), buckets[i].getRecordIds());
				}
				byPageSequence.put(page.pageSequence(), copy);
				if (page.valueIds() != null) {
					valueIdsByPageSequence.put(page.pageSequence(), page.valueIds());
				}
			}
			final ValueToRecordBitmap[][] perPageBuckets = new ValueToRecordBitmap[orderedPageSequences.length][];
			final int[][] perPageValueIds = withValueIds ? new int[orderedPageSequences.length][] : null;
			for (int i = 0; i < orderedPageSequences.length; i++) {
				perPageBuckets[i] = byPageSequence.get(orderedPageSequences[i]);
				if (perPageValueIds != null) {
					perPageValueIds[i] = valueIdsByPageSequence.get(orderedPageSequences[i]);
				}
			}
			return new PersistedPages(
				orderedPageSequences, perPageBuckets, perPageValueIds, emission.highWaterPageSequence(),
				index.getNextValueId()
			);
		}
	}

	/**
	 * The `i`-th seeded instant: one whole millisecond apart, so a `plusNanos(1)` twin of any of them stays strictly
	 * between it and its successor.
	 */
	@Nonnull
	private static Instant instantOf(int ordinal) {
		return COLLAPSE_TARGET.plusMillis(ordinal);
	}

	/**
	 * The record id the seeded index assigned to a given key — the inverse of {@link #instantOf(int)}.
	 */
	private static int recordOf(@Nonnull Instant instant) {
		return (int) (instant.toEpochMilli() - COLLAPSE_TARGET.toEpochMilli()) + 1;
	}

	/**
	 * The persisted form of a `PAGED` inverted index: the root's ordered page list, each page's buckets, and the
	 * stream high-water — everything `AttributeIndexLoader` reads back off disk before calling
	 * {@link InvertedIndex#fromPersistedPages}.
	 */
	private record PersistedPages(
		@Nonnull int[] orderedPageSequences,
		@Nonnull ValueToRecordBitmap[][] perPageBuckets,
		@Nullable int[][] perPageValueIds,
		int highWaterPageSequence,
		int nextValueId
	) {

		/**
		 * Applies the migration correction, then restores the corrected pages through the production API and restores
		 * the persisted allocator high-water when the pages carry value ids.
		 */
		@Nonnull
		InvertedIndex reload() {
			final InvertedIndex reloaded = migratePages(
				OffsetDateTime.class, this.orderedPageSequences, this.perPageBuckets, this.perPageValueIds,
				this.highWaterPageSequence, normalizer(), Comparator.naturalOrder(), 0
			);
			if (this.perPageValueIds != null) {
				reloaded.restoreValueIds(this.nextValueId);
			}
			return reloaded;
		}
	}

	/** Applies the migration correction before the inline production restore constructor. */
	@Nonnull
	private static InvertedIndex migrateInline(
		@Nonnull Class<?> type, @Nonnull ValueToRecordBitmap[] buckets,
		@Nonnull Function<Object, Serializable> normalizer, @Nonnull Comparator comparator, int scale
	) {
		final CorrectedBuckets corrected = LegacyInvertedIndexCorrection.correct(
			type, scale, new ValueToRecord[][]{buckets}, null, comparator, false
		);
		final ValueToRecordBitmap[] migrated = corrected == null ? buckets :
			copyOf(corrected.pages()[0], corrected.pages()[0].length, ValueToRecordBitmap[].class);
		return new InvertedIndex(type, migrated, normalizer, comparator, scale);
	}

	/** Compacts ids in the migration, leaving the canonical column untouched. */
	@Nonnull
	private static int[] migratedValueIds(
		@Nonnull ValueToRecordBitmap[] buckets, @Nonnull int[] ids,
		@Nonnull Function<Object, Serializable> normalizer, @Nonnull Comparator comparator
	) {
		final CorrectedBuckets corrected = LegacyInvertedIndexCorrection.correct(
			OffsetDateTime.class, 0, new ValueToRecord[][]{buckets}, new int[][]{ids}, comparator, false
		);
		return corrected == null ? ids : corrected.valueIds()[0];
	}

	/** Corrects every page before loading its new persisted identity. */
	@Nonnull
	private static InvertedIndex migratePages(
		@Nonnull Class<?> type, @Nonnull int[] sequences, @Nonnull ValueToRecord[][] buckets,
		@Nullable int[][] ids, int highWater, @Nonnull Function<Object, Serializable> normalizer,
		@Nonnull Comparator comparator, int scale
	) {
		final CorrectedBuckets corrected = LegacyInvertedIndexCorrection.correct(type, scale, buckets, ids, comparator,
			true);
		if (corrected == null) {
			return InvertedIndex.fromPersistedPages(type, sequences, buckets, ids, highWater, normalizer, comparator,
				scale);
		}
		final int[] rewritten = new int[corrected.pages().length];
		for (int i = 0; i < rewritten.length; i++) {
			rewritten[i] = highWater + i + 1;
		}
		return InvertedIndex.fromPersistedPages(
			type, rewritten, corrected.pages(), corrected.valueIds(), highWater + rewritten.length,
			normalizer, comparator, scale
		);
	}
}
