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

package io.evitadb.index.invertedIndex;

import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.page.PageEmission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migration coverage for a filter index persisted **before** temporal values were truncated to whole milliseconds.
 *
 * Such a catalog can hold two buckets whose `Instant` keys differ only below the millisecond. Both now encode to the
 * same `long` in the leaf's key column, so the reload path is handed two persisted buckets that collapse onto one
 * tree key — a state neither reload constructor was written for: the bucket-replaying one documents its input as
 * "unique & monotonic", and the `PAGED` one promises one in-memory leaf per persisted page.
 *
 * The tests below drive both reload paths with exactly that shape of persisted state, hand-built the way a
 * pre-truncation writer would have left it (a real writer cannot produce it any more — `FilterIndex.getNormalizer`
 * truncates on the way in). What must hold either way: **no record may be lost, and the reload must not fail**. The
 * collapsed index is additionally expected to report itself dirty, so the next commit rewrites it in canonical form
 * and the repair is paid for exactly once.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("A filter index persisted before millisecond truncation reloads without losing records")
@Tag(STORAGE)
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(DATA_TYPE)
class InvertedIndexSubMillisecondReloadTest {
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
	private static final String VALUE_ID_CONSUMER = "sub-millisecond-reload-test";

	/**
	 * The normalizer an `OffsetDateTime` filter index is built with.
	 */
	@Nonnull
	private static Function<Object, Serializable> normalizer() {
		return FilterIndex.getNormalizer(OffsetDateTime.class, 0);
	}

	@Nested
	@DisplayName("inline reload (the bucket-replaying constructor)")
	class InlineReload {

		@Test
		@DisplayName("merges two persisted sub-millisecond buckets into one, keeping both records")
		void shouldMergeCollidingPersistedBuckets() {
			final ValueToRecordBitmap[] persisted = {
				new ValueToRecordBitmap(LOWER_TWIN, 1),
				new ValueToRecordBitmap(UPPER_TWIN, 2, 3),
				new ValueToRecordBitmap(NEIGHBOUR, 4)
			};

			final InvertedIndex reloaded = new InvertedIndex(
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
			assertTrue(
				reloaded.isDirty(),
				"a reload that collapsed two persisted buckets must ask to be rewritten in canonical form"
			);
		}

		@Test
		@DisplayName("keys the merged bucket by the truncated instant, not by the value that created it")
		void shouldKeyTheMergedBucketByTheTruncatedInstant() {
			final ValueToRecordBitmap[] persisted = {
				new ValueToRecordBitmap(LOWER_TWIN, 1),
				new ValueToRecordBitmap(UPPER_TWIN, 2)
			};

			final InvertedIndex reloaded = new InvertedIndex(
				OffsetDateTime.class, persisted, normalizer(), Comparator.naturalOrder(), 0
			);

			final ValueToRecordBitmap[] rehydrated = reloaded.getValueToRecordBitmap();
			assertEquals(1, rehydrated.length);
			assertEquals(
				COLLAPSE_TARGET, rehydrated[0].getValue(),
				"the surviving bucket must be keyed by the millisecond both twins collapse onto"
			);
		}

		@Test
		@DisplayName("realigns the persisted inline value id column with the buckets the repair left")
		void shouldRealignTheInlineValueIdColumn() {
			// the inline id column is positional over the buckets that were WRITTEN; a repaired tree holds fewer, and
			// the loader's own alignment premise refuses the pair outright - the catalog does not open at all
			final ValueToRecordBitmap[] persisted = {
				new ValueToRecordBitmap(LOWER_TWIN, 1),
				new ValueToRecordBitmap(UPPER_TWIN, 2),
				new ValueToRecordBitmap(NEIGHBOUR, 3)
			};
			final int[] persistedValueIds = {11, 12, 13};

			final InvertedIndex reloaded = new InvertedIndex(
				OffsetDateTime.class, persisted, normalizer(), Comparator.naturalOrder(), 0
			);
			// exactly what AttributeIndexLoader does for a SINGLE (inline) filter index carrying value ids
			reloaded.restoreValueIds(
				14,
				InvertedIndex.alignPersistedValueIds(
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
			assertEquals(14, reloaded.getNextValueId(), "the repair must mint nothing");
			assertArrayEquals(
				new int[]{11, 12, 13}, persistedValueIds,
				"the realignment must not mutate the array it was handed - it belongs to the storage part"
			);
		}

		@Test
		@DisplayName("hands a collision-free inline id column straight back, without copying it")
		void shouldNotTouchACanonicalInlineValueIdColumn() {
			final ValueToRecordBitmap[] persisted = {
				new ValueToRecordBitmap(COLLAPSE_TARGET, 1),
				new ValueToRecordBitmap(NEIGHBOUR, 2)
			};
			final int[] persistedValueIds = {11, 12};

			assertSame(
				persistedValueIds,
				InvertedIndex.alignPersistedValueIds(
					persisted, persistedValueIds, normalizer(), Comparator.naturalOrder()
				),
				"an index with nothing to repair must not pay an array copy on every catalog load"
			);
		}

		@Test
		@DisplayName("leaves an already-canonical index alone and does not ask to be rewritten")
		void shouldLeaveACanonicalIndexAlone() {
			// the control: the very same shape with no collision in it must reload byte-for-byte as before, and in
			// particular must NOT be flagged dirty - otherwise the repair would rewrite every temporal index in the
			// catalog on the first commit after an upgrade
			final ValueToRecordBitmap[] persisted = {
				new ValueToRecordBitmap(COLLAPSE_TARGET, 1),
				new ValueToRecordBitmap(NEIGHBOUR, 2)
			};

			final InvertedIndex reloaded = new InvertedIndex(
				OffsetDateTime.class, persisted, normalizer(), Comparator.naturalOrder(), 0
			);

			assertEquals(2, reloaded.getBucketCount());
			assertArrayEquals(new int[]{1}, reloaded.getRecordsEqualTo(COLLAPSE_TARGET).getArray());
			assertArrayEquals(new int[]{2}, reloaded.getRecordsEqualTo(NEIGHBOUR).getArray());
			assertFalse(reloaded.isDirty(), "a reload that changed nothing must not schedule a rewrite");
		}
	}

	@Nested
	@DisplayName("PAGED reload (one leaf per persisted page)")
	class PagedReload {
		/**
		 * How many buckets the seeded index holds — comfortably more than one 256-slot leaf, so the emission really
		 * produces several pages and the cross-page case below has a boundary to straddle.
		 */
		private static final int SEEDED_BUCKETS = 1_000;

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
			assertTrue(reloaded.isDirty(), "a reload that collapsed two persisted buckets must ask for a rewrite");
		}

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
			assertTrue(reloaded.isDirty(), "a reload that collapsed two persisted buckets must ask for a rewrite");
		}

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
				pages.nextValueId, reloaded.getNextValueId(), "the repair must not mint anything"
			);
			// the records themselves are the thing the aliasing lost, so assert them here too
			assertArrayEquals(
				new int[]{recordOf(lastOfPreviousPage), recordOf(lastOfPreviousPage) + 1},
				reloaded.getRecordsEqualTo(lastOfPreviousPage).getArray(),
				"the cross-page merge must hold both records"
			);
		}

		@Test
		@DisplayName("rewrites only the pages the repair changed, and frees the records they superseded")
		void shouldRewriteOnlyTheRepairedPage() {
			// the repair is only correct in memory until the merged page is written back: a bulk-loaded leaf is clean,
			// so without releasing its page identity the persisted page would keep the un-merged buckets for good
			final PersistedPages pages = seedAndEmit();
			final int repairedPageIndex = pages.perPageBuckets.length / 2;
			final int repairedPageSequence = pages.orderedPageSequences[repairedPageIndex];
			final ValueToRecordBitmap[] buckets = pages.perPageBuckets[repairedPageIndex];
			final Instant predecessor = (Instant) buckets[10].getValue();
			buckets[11] = new ValueToRecordBitmap(predecessor.plusNanos(1), buckets[11].getRecordIds());

			final InvertedIndex reloaded = pages.reload();
			final PageEmission<InvertedIndex.LeafPage> emission = reloaded.collectChangedPages();

			assertEquals(
				1, emission.changedPages().size(),
				"exactly the one merged page must be rewritten - the untouched leaves keep their identity"
			);
			assertArrayEquals(
				new int[]{repairedPageSequence}, emission.freedPageSequences(),
				"the superseded record of the merged page must be freed"
			);
			assertEquals(
				pages.highWaterPageSequence + 1, emission.highWaterPageSequence(),
				"the rewritten page must take a fresh, never-reused sequence"
			);
			assertEquals(
				pages.orderedPageSequences.length, emission.orderedPageSequences().length,
				"no page may disappear when nothing was absorbed whole"
			);
		}

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
				"a bucket the repair did not touch must keep its id even though a lower slot vanished"
			);
			assertEquals(
				pages.nextValueId, reloaded.getNextValueId(),
				"the repair must not mint anything - the retired id " + retiredId + " is simply never reused"
			);
		}

		@Test
		@DisplayName("drops a page whose every bucket was absorbed by its predecessor")
		void shouldDropAPageAbsorbedInItsEntirety() {
			// unreachable with production leaf occupancy (a persisted page holds at least half a leaf), but it is the
			// one branch of the repair that changes the ROOT's page list, and an unexercised branch that rewrites a
			// root is not a branch anybody should trust. Hand-built pages, since a real index cannot produce one
			final Instant base = COLLAPSE_TARGET;
			final ValueToRecordBitmap[][] perPageBuckets = {
				{new ValueToRecordBitmap(base, 1), new ValueToRecordBitmap(base.plusMillis(1), 2)},
				{new ValueToRecordBitmap(base.plusMillis(1).plusNanos(7), 3)},
				{new ValueToRecordBitmap(base.plusMillis(2), 4)}
			};
			final int[] orderedPageSequences = {0, 1, 2};

			final InvertedIndex reloaded = InvertedIndex.fromPersistedPages(
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
				2, emission.orderedPageSequences().length, "the emptied page must leave the root's page list"
			);
			assertArrayEquals(
				new int[]{0, 1}, emission.freedPageSequences(),
				"both the emptied page and the record the merged page superseded must be freed"
			);
		}

		@Test
		@DisplayName("a collision-free paged reload stays boundary-stable and rewrites nothing")
		void shouldLeaveACanonicalPagedIndexAlone() {
			// the control for both cases above: page identities, the high-water and the "nothing changed" verdict of
			// the first post-reload commit must all survive untouched when there is nothing to repair
			final PersistedPages pages = seedAndEmit();

			final InvertedIndex reloaded = pages.reload();

			assertEquals(SEEDED_BUCKETS, reloaded.getBucketCount());
			assertTrue(reloaded.isPaged());
			assertFalse(reloaded.isDirty(), "a reload that changed nothing must not schedule a rewrite");
			final PageEmission<InvertedIndex.LeafPage> afterReload = reloaded.collectChangedPages();
			assertArrayEquals(pages.orderedPageSequences, afterReload.orderedPageSequences());
			assertTrue(afterReload.changedPages().isEmpty(), "a boundary-stable reload must rewrite no leaf page");
			assertEquals(0, afterReload.freedPageSequences().length);
		}

		/**
		 * Builds a millisecond-exact `OffsetDateTime`-keyed index of {@link #SEEDED_BUCKETS} single-record buckets and
		 * emits it as leaf pages — the persisted state the reload tests then corrupt into a pre-truncation shape.
		 *
		 * @return the emitted pages, ready to be mutated and reloaded
		 */
		@Nonnull
		private static PersistedPages seedAndEmit() {
			return seedAndEmit(false);
		}

		/**
		 * Builds a millisecond-exact `OffsetDateTime`-keyed index of {@link #SEEDED_BUCKETS} single-record buckets and
		 * emits it as leaf pages.
		 *
		 * @param withValueIds whether the seeded tree carries stable value ids
		 * @return the emitted pages, ready to be mutated and reloaded
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
			final Map<Integer, ValueToRecordBitmap[]> byPageSequence = new HashMap<>();
			final Map<Integer, int[]> valueIdsByPageSequence = new HashMap<>();
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
	 *
	 * @param ordinal the bucket ordinal
	 * @return the instant that bucket is keyed by
	 */
	@Nonnull
	private static Instant instantOf(int ordinal) {
		return COLLAPSE_TARGET.plusMillis(ordinal);
	}

	/**
	 * The record id the seeded index assigned to a given key — the inverse of {@link #instantOf(int)}.
	 *
	 * @param instant a seeded key
	 * @return its single record id
	 */
	private static int recordOf(@Nonnull Instant instant) {
		return (int) (instant.toEpochMilli() - COLLAPSE_TARGET.toEpochMilli()) + 1;
	}

	/**
	 * The persisted form of a `PAGED` inverted index: the root's ordered page list, each page's buckets, and the
	 * stream high-water — everything `AttributeIndexLoader` reads back off disk before calling
	 * {@link InvertedIndex#fromPersistedPages}.
	 *
	 * @param orderedPageSequences the root's leaf-page sequences in ascending key order
	 * @param perPageBuckets       each page's buckets, positionally aligned with `orderedPageSequences`
	 * @param highWaterPageSequence the persisted stream high-water
	 */
	private record PersistedPages(
		@Nonnull int[] orderedPageSequences,
		@Nonnull ValueToRecordBitmap[][] perPageBuckets,
		@Nullable int[][] perPageValueIds,
		int highWaterPageSequence,
		int nextValueId
	) {

		/**
		 * Replays this persisted state through the `PAGED` reload constructor, exactly as the loader does — including
		 * the value-id restoration the loader performs right afterwards when the pages carried ids.
		 *
		 * @return the reloaded index
		 */
		@Nonnull
		InvertedIndex reload() {
			final InvertedIndex reloaded = InvertedIndex.fromPersistedPages(
				OffsetDateTime.class, this.orderedPageSequences, this.perPageBuckets, this.perPageValueIds,
				this.highWaterPageSequence, normalizer(), Comparator.naturalOrder(), 0
			);
			if (this.perPageValueIds != null) {
				reloaded.restoreValueIds(this.nextValueId);
			}
			return reloaded;
		}
	}
}
